package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// PMSAv8-64 (ARM DDI 0600A.d, "ARM ARM Supplement — ARMv8-R AArch64", capítulo G1.3, lido via
/// `curl` nesta sessão — não parafraseado, B20.8): liga `MRS`/`MSR (register)` aos 5 registradores
/// de MPU de EL1 (`MPUIR_EL1`/`PRSELR_EL1`/`PRBAR_EL1`/`PRLAR_EL1`/`PRENR_EL1`) e ao bit `M` de
/// `SCTLR_EL1` — espelho estrutural de {@link Aarch64VmsaSystemRegisters} (`handles`/`read`/
/// `write`), mas trocando o modelo de memória: banco de regiões PMSA em vez de tabelas de página
/// VMSA. Composição, não herança (mesma disciplina do precedente).
///
/// **Classe irmã de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8MpuRegisters}** (PMSAv8-32, B20.7), **nunca uma extensão dela**
/// (mesmo precedente da Armadilha 7 da B20.7 aplicado ao par 32/64 bits): os registradores são
/// A64 (`long`, base/limite de 46 bits + `SH`/`AP`/`XN`/`NS`/`AttrIndx`/`EN` em posições
/// DIFERENTES — ver Javadoc de {@link Aarch64SystemRegisterId#PRBAR_EL1}/{@link
/// Aarch64SystemRegisterId#PRLAR_EL1}), não reuso do layout de 32 bits.
///
/// **Só EL1** (decisão de escopo desta sessão, dentro do que a task B20.8 já excluía: "Estágio 2 de
/// proteção" — os registradores `_EL2` ficam de fora, candidata a task futura nomeada junto do
/// estágio 2; sem eles o par `_EL2` nem precisa existir agora).
///
/// `SCTLR_EL1` aqui tem semântica PMSA (`M`=habilita a MPU, `BR`=habilita a região de fundo) — **NÃO**
/// é a mesma classe que {@link Aarch64VmsaSystemRegisters}, que trata `SCTLR_EL1.M` como "habilita
/// tradução de página". Os dois barramentos nunca coexistem no mesmo `Aarch64Core` (VMSA×PMSA são
/// mutuamente exclusivos, ver Javadoc de {@link dev.vitorsilverio.armjitter.arch64.Aarch64Feature#PMSA}).
///
/// `ESR_EL1`/`FAR_EL1`/`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1` delegam para {@link Aarch64ExceptionState}
/// (via {@link Aarch64Core#exceptionState()}), mesma disciplina de fonte única do precedente VMSA —
/// necessários para o guest entrar/sair de um abort de MPU de verdade (`Aarch64Core#enterMemoryAbort`
/// lê/escreve os mesmos registradores).
public final class Pmsav8SystemRegisters64 implements Aarch64SystemRegisterBus {
    /// `SCTLR_EL1.M` (bit 0, DDI 0600A.d — mesma posição que VMSA64) — habilita a MPU de EL1.
    private static final long SCTLR_M_BIT = 1;
    /// `SCTLR_EL1.BR` (bit 17, mesma posição que a versão de 32 bits `Pmsav8MpuRegisters`/PMSAv7 —
    /// não confirmada bit a bit no DDI 0600A nesta rodada, ver `## Resultado` da task).
    private static final long SCTLR_BR_BIT = 1L << 17;

    private final int regionCount;
    private final long[] prbar;
    private final long[] prlar;
    private int prselr;
    /// `PRENR_EL1` — puro armazenamento, sem consumidor (mesmo achado do espelho de 32 bits, ver
    /// Javadoc de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8MpuRegisters#prenr}).
    private long prenr;

    private long sctlr;
    private boolean mpuEnabled;
    private boolean backgroundRegionEnabled;

    private final Aarch64ExceptionState exceptionState;

    /// @param core        core cujo {@link Aarch64Core#exceptionState()} guarda `ESR_EL1`/
    ///                    `FAR_EL1`/`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1` — única fonte de verdade
    /// @param regionCount número de regiões de MPU de EL1 implementadas (`MPUIR_EL1.REGION`) —
    ///                     varia por processador real (`Cortex-R82`), sem default de propósito
    public Pmsav8SystemRegisters64(Aarch64Core core, int regionCount) {
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount deve ser positivo: " + regionCount);
        }
        this.exceptionState = core.exceptionState();
        this.regionCount = regionCount;
        this.prbar = new long[regionCount];
        this.prlar = new long[regionCount];
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        return switch (register) {
            case SCTLR_EL1, MPUIR_EL1, PRSELR_EL1, PRBAR_EL1, PRLAR_EL1, PRENR_EL1,
                 ESR_EL1, FAR_EL1, VBAR_EL1, ELR_EL1, SPSR_EL1 -> true;
            default -> false;
        };
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return switch (register) {
            case SCTLR_EL1 -> sctlrValue();
            case MPUIR_EL1 -> Integer.toUnsignedLong(regionCount);
            case PRSELR_EL1 -> Integer.toUnsignedLong(prselr);
            case PRBAR_EL1 -> prbar[prselr];
            case PRLAR_EL1 -> prlar[prselr];
            case PRENR_EL1 -> prenr;
            case ESR_EL1 -> exceptionState.esr1();
            case FAR_EL1 -> exceptionState.far1();
            case VBAR_EL1 -> exceptionState.vbar1();
            case ELR_EL1 -> exceptionState.elr1();
            case SPSR_EL1 -> exceptionState.spsr1();
            default -> throw new UnsupportedOperationException(
                    "Pmsav8SystemRegisters64 não atende: " + register);
        };
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case SCTLR_EL1 -> applySctlr(value);
            case MPUIR_EL1 -> {
                // Só leitura (DDI 0600A.d §G1.3.14) — escrita ignorada, mesmo padrão do espelho de
                // 32 bits (Cp15Pmsav8MpuCoprocessor: "MPUIR/HMPUIR são só leitura").
            }
            case PRSELR_EL1 -> setPrselr(value);
            case PRBAR_EL1 -> prbar[prselr] = value;
            case PRLAR_EL1 -> prlar[prselr] = value;
            case PRENR_EL1 -> prenr = value;
            case ESR_EL1 -> exceptionState.setEsr1(value);
            case FAR_EL1 -> exceptionState.setFar1(value);
            case VBAR_EL1 -> exceptionState.setVbar1(value);
            case ELR_EL1 -> exceptionState.setElr1(value);
            case SPSR_EL1 -> exceptionState.setSpsr1((int) value);
            default -> throw new UnsupportedOperationException(
                    "Pmsav8SystemRegisters64 não atende: " + register);
        }
    }

    /// Escrita de `PRSELR_EL1` fora de faixa: **ignorada silenciosamente** (CONSTRAINED
    /// UNPREDICTABLE no manual; mesma decisão de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8MpuRegisters#setPrselr}, G8 aplicado
    /// a registrador — nunca corrompe estado nem lança através do core).
    private void setPrselr(long value) {
        if (value < 0 || value >= regionCount) {
            return;
        }
        prselr = (int) value;
    }

    private void applySctlr(long value) {
        sctlr = value;
        mpuEnabled = (value & SCTLR_M_BIT) != 0;
        backgroundRegionEnabled = (value & SCTLR_BR_BIT) != 0;
    }

    private long sctlrValue() {
        long value = sctlr;
        value = mpuEnabled ? (value | SCTLR_M_BIT) : (value & ~SCTLR_M_BIT);
        value = backgroundRegionEnabled ? (value | SCTLR_BR_BIT) : (value & ~SCTLR_BR_BIT);
        return value;
    }

    // ── API de leitura para Pmsav8AddressSpace64 (varre todas as regiões, não só a selecionada) ──

    /// Leitura crua de `PRBAR_EL1` por índice de região — API pensada para
    /// {@link Pmsav8AddressSpace64}, que precisa varrer todas as regiões, não só a selecionada por
    /// `PRSELR_EL1`.
    long base(int region) {
        return prbar[region];
    }

    /// Leitura crua de `PRLAR_EL1` por índice de região (sem decompor `EN`/`AttrIndx`/`NS`).
    long limit(int region) {
        return prlar[region];
    }

    /// `PRLAR_EL1.EN` (bit 0) da região `region` — fonte autoritativa de habilitação (ver Javadoc
    /// de {@link #prenr}).
    boolean regionEnabled(int region) {
        return (prlar[region] & 1) != 0;
    }

    int regionCount() {
        return regionCount;
    }

    boolean mpuEnabled() {
        return mpuEnabled;
    }

    boolean backgroundRegionEnabled() {
        return backgroundRegionEnabled;
    }
}
