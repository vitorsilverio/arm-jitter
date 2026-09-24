package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;

/// CP15 PMSAv7 (ARM DDI 0406C, "Protected Memory System Architecture"): liga as instruções
/// `MCR`/`MRC` que configuram a MPU de um core de perfil R (B20.1, `ArmArchitecture#ARMV7R`) ao
/// banco {@link Pmsav7MpuRegisters}, que a B20.3 (`PmsaAddressSpace`) vai LER para decidir
/// permitir/negar cada acesso. Composição, não herança (RFC-SOFTMMU decisão 4, mesmo precedente
/// de {@link dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor}): o hospedeiro instala
/// `new Pmsav7MpuCoprocessor(mpu, core, architecture)` via {@link ArmCore#setCoprocessorBus}.
/// **Nenhuma checagem de acesso acontece aqui** — só estado + a via de programação.
///
/// Registradores atendidos (todos `opcode1=0`, únicos válidos para PMSAv7):
/// - `MPUIR` (`c0,c0,4`): só leitura, reporta `regionCount` em `DREGION` (bits\[15:8\]); `IREGION`
///   (bits\[23:16\]) fica zero e `nU` (bit 0) fica zero — modelo **unificado**, sem MPU separado de
///   instrução/dados (ver Javadoc de {@link Pmsav7MpuRegisters}, "Não inclui" da B20.2). Escrita é
///   **ignorada silenciosamente** (registrador read-only; UNPREDICTABLE no manual, "ignorar" evita
///   lançar através do core por um guest que escreve nele por engano).
/// - `RGNR` (`c6,c2,0`): índice da região que `DRBAR`/`DRSR`/`DRACR` seguintes acessam — ver
///   {@link Pmsav7MpuRegisters#setRgnr} para o comportamento fora de faixa.
/// - `DRBAR`/`DRSR`/`DRACR` (`c6,c1,{0,2,4}`): armazenamento cru por região, indexado por `RGNR`
///   corrente — nenhum campo é decomposto aqui (ver Javadoc de {@link Pmsav7MpuRegisters}).
/// - `SCTLR` (`c1,c0,0`, MESMO encoding do VMSA — mutuamente exclusivos por `ArmFeature`, ver
///   Armadilha 5 abaixo): armazenamento fiel de 32 bits, com `M` (bit 0, habilita a MPU) e `BR`
///   (bit 17, Background Region) recalculados do estado autoritativo em
///   {@link Pmsav7MpuRegisters} na leitura — mesma lição já paga pelo `Cp15VmsaCoprocessor`
///   (achado F3: RAZ/WI para bits sem efeito modelado quebra software que relê o próprio
///   `SCTLR`). `V` (bit 13, vetores altos) sincroniza com {@link ArmCore#setHighVectors}, igual
///   ao VMSA. **`BR` (bit 17) é o único campo desta classe confirmado só contra o QEMU real**
///   (`target/arm/cpu.h`, `#define SCTLR_BR (1U << 17) /* PMSA only */`) — a segunda fonte
///   independente (ARM DDI 0406C, seção `SCTLR` do capítulo PMSA) não pôde ser lida nesta rodada
///   de spec/implementação (página `developer.arm.com`/`support.arm.com` é renderizada por
///   JavaScript, inacessível a fetch simples; buscas alternativas em Linux/U-Boot/barebox não
///   encontraram o bit documentado fora do QEMU). Ver Armadilha 1 da task B20.2.
///
/// **Gate por `ArmFeature#PMSA`** (Armadilha 5 da B20.2): o construtor recusa uma
/// {@link ArmArchitecture} sem `PMSA` (falha cedo e alto, em vez de instalar um bus inútil). A
/// exclusão mútua com {@link dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor} — os
/// dois reivindicam `c1,c0,0` — **não é detectada em tempo de execução**: {@link ArmCore} guarda
/// só UM {@link CoprocessorBus} por vez ({@link ArmCore#setCoprocessorBus} substitui o anterior),
/// então instalar os dois ao mesmo tempo exigiria um hospedeiro escrever um decorator que
/// encadeia ambos deliberadamente — fora do alcance de uma checagem local a esta classe. Decisão
/// registrada: documentar (este Javadoc), não implementar detecção.
public final class Pmsav7MpuCoprocessor implements CoprocessorBus {
    private static final int CP15 = 15;

    /// `MPUIR` (`c0,c0,4`) — único registrador sob `CRn=0` que esta classe atende.
    private static final int CRN_IDENTIFICATION = 0;
    private static final int CRM_MPUIR = 0;
    private static final int OPCODE2_MPUIR = 4;

    private static final int CRN_SYSTEM_CONTROL = 1;
    private static final int CRM_PRIMARY = 0;
    private static final int OPCODE2_SCTLR = 0;

    /// `RGNR`/`DRBAR`/`DRSR`/`DRACR` moram todos sob `CRn=6` ("Memory region programming
    /// registers", ARM DDI 0406C).
    private static final int CRN_MPU_REGION = 6;
    private static final int CRM_RGNR = 2;
    private static final int OPCODE2_RGNR = 0;
    private static final int CRM_REGION_CONFIG = 1;
    private static final int OPCODE2_DRBAR = 0;
    private static final int OPCODE2_DRSR = 2;
    private static final int OPCODE2_DRACR = 4;

    private static final int MPUIR_DREGION_SHIFT = 8;

    private static final int SCTLR_M_BIT = 1;
    private static final int SCTLR_V_BIT = 1 << 13;
    /// `SCTLR.BR` (bit 17, "PMSA only") — ver Javadoc da classe sobre a confirmação única (QEMU).
    private static final int SCTLR_BR_BIT = 1 << 17;

    private final Pmsav7MpuRegisters mpu;
    private final ArmCore core;

    private int sctlr;

    /// @param mpu          banco de estado que este coprocessador programa
    /// @param core         core cujo `SCTLR.V` (vetores altos) este coprocessador sincroniza
    /// @param architecture arquitetura do core, só para o gate de {@link ArmFeature#PMSA} —
    ///                      não é guardada, esta classe não decide nada por versão
    /// @throws IllegalArgumentException se `architecture` não declara {@link ArmFeature#PMSA}
    public Pmsav7MpuCoprocessor(Pmsav7MpuRegisters mpu, ArmCore core, ArmArchitecture architecture) {
        if (!architecture.has(ArmFeature.PMSA)) {
            throw new IllegalArgumentException(
                    "Pmsav7MpuCoprocessor exige ArmFeature.PMSA; arquitetura fornecida não tem PMSA (perfil não-R?)");
        }
        this.mpu = mpu;
        this.core = core;
        // Reset real de hardware: MPU desligada (SCTLR.M=0) até o software habilitar.
        mpu.setMpuEnabled(false);
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor != CP15) {
            return false;
        }
        return switch (crn) {
            case CRN_IDENTIFICATION -> crm == CRM_MPUIR && opcode2 == OPCODE2_MPUIR;
            case CRN_SYSTEM_CONTROL -> crm == CRM_PRIMARY && opcode2 == OPCODE2_SCTLR;
            case CRN_MPU_REGION -> (crm == CRM_RGNR && opcode2 == OPCODE2_RGNR)
                    || (crm == CRM_REGION_CONFIG
                    && (opcode2 == OPCODE2_DRBAR || opcode2 == OPCODE2_DRSR || opcode2 == OPCODE2_DRACR));
            default -> false;
        };
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return switch (crn) {
            case CRN_IDENTIFICATION -> mpu.regionCount() << MPUIR_DREGION_SHIFT;
            case CRN_SYSTEM_CONTROL -> sctlrValue();
            case CRN_MPU_REGION -> readMpuRegion(crm, opcode2);
            default -> throw unsupported(crn, crm, opcode2);
        };
    }

    private int readMpuRegion(int crm, int opcode2) {
        if (crm == CRM_RGNR) {
            return mpu.rgnr();
        }
        return switch (opcode2) {
            case OPCODE2_DRBAR -> mpu.drbar();
            case OPCODE2_DRSR -> mpu.drsr();
            default -> mpu.dracr();
        };
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        switch (crn) {
            case CRN_IDENTIFICATION -> {
                // MPUIR é só leitura (ARM DDI 0406C) — escrita ignorada, ver Javadoc da classe.
            }
            case CRN_SYSTEM_CONTROL -> applySctlr(value);
            case CRN_MPU_REGION -> writeMpuRegion(crm, opcode2, value);
            default -> throw unsupported(crn, crm, opcode2);
        }
    }

    private void writeMpuRegion(int crm, int opcode2, int value) {
        if (crm == CRM_RGNR) {
            mpu.setRgnr(value);
            return;
        }
        switch (opcode2) {
            case OPCODE2_DRBAR -> mpu.setDrbar(value);
            case OPCODE2_DRSR -> mpu.setDrsr(value);
            default -> mpu.setDracr(value);
        }
    }

    private void applySctlr(int value) {
        sctlr = value;
        mpu.setMpuEnabled((value & SCTLR_M_BIT) != 0);
        mpu.setBackgroundRegionEnabled((value & SCTLR_BR_BIT) != 0);
        core.setHighVectors((value & SCTLR_V_BIT) != 0);
    }

    private int sctlrValue() {
        int value = sctlr;
        // M/BR/V continuam autoritativos a partir do estado real do banco/core (podem ter sido
        // alterados por outra via) — mesma lição do Cp15VmsaCoprocessor (achado F3).
        value = mpu.mpuEnabled() ? (value | SCTLR_M_BIT) : (value & ~SCTLR_M_BIT);
        value = mpu.backgroundRegionEnabled() ? (value | SCTLR_BR_BIT) : (value & ~SCTLR_BR_BIT);
        value = core.highVectors() ? (value | SCTLR_V_BIT) : (value & ~SCTLR_V_BIT);
        return value;
    }

    private static IllegalStateException unsupported(int crn, int crm, int opcode2) {
        return new IllegalStateException(
                "bug: executor não consultou handles fino: crn=c%d crm=c%d opcode2=%d".formatted(crn, crm, opcode2));
    }
}
