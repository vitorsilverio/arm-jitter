package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// VMSA64 (`ARM DDI 0487 D8`, task B6.6.3): liga `MRS`/`MSR (register)` (B6.6.1) e `TLBI VMALLE1`/
/// `VMALLE1IS` (`Ir64Op.SystemInstruction`, também B6.6.3) aos controles expostos por
/// {@link TranslatingAddressSpace64} (B6.6.2) — espelho direto do precedente 32-bit
/// ({@link Cp15VmsaCoprocessor}, `MCR`/`MRC`), mas ligando `MRS`/`MSR` em vez de `MCR`/`MRC`.
/// Composição, não herança (mesma decisão D2 do precedente): o hospedeiro instala
/// `core.setSystemRegisterBus(new Aarch64VmsaSystemRegisters(mmu, core))`.
///
/// Registradores atendidos (todos `op0=3,op1=0`, únicos válidos para os registradores "gerais" de
/// EL1 cobertos por B6.6.1): `SCTLR_EL1` (só o bit `M`, habilita a MMU — A64 não tem bit `V`/
/// vetores-altos, o vetor de exceção é sempre `VBAR_EL1`), `TTBR0_EL1`, `TCR_EL1`, `MAIR_EL1`
/// ligam em {@link TranslatingAddressSpace64}; `ESR_EL1`/`FAR_EL1`/`VBAR_EL1`/`ELR_EL1`/
/// `SPSR_EL1` (B6.6.4) delegam DIRETAMENTE para {@link Aarch64ExceptionState} (via
/// {@link Aarch64Core#exceptionState()}) — SEM cópia própria: antes de B6.6.4 este barramento
/// tinha campos próprios só de armazenamento; agora {@code core} (antes reservado só "para
/// simetria" com o precedente 32-bit) é a ÚNICA fonte de verdade, já que
/// {@link Aarch64Core#enterMemoryAbort} também lê/escreve esses mesmos registradores para
/// realmente entrar/sair de EL1 — duas cópias divergiriam assim que o guest lesse `ESR_EL1` via
/// `MRS` depois de um abort real.
///
/// `TLBI VMALLE1`/`TLBI VMALLE1IS` (D1 da task — achado real: `TLBI` é `SYS`, não `MRS`/`MSR`)
/// chega via {@link #invalidateTlbAll()}, repassado a {@link TranslatingAddressSpace64#invalidateTlbAll}
/// — mesma simplificação "sem per-ASID/per-VA" que o precedente 32-bit já aplica ao `TLBIALL`
/// unificado.
///
/// **B10.2**: os 13 registradores EL2 (`SCTLR_EL2`/`HCR_EL2`/`MDCR_EL2`/`CPTR_EL2`/`TCR_EL2`/
/// `VTTBR_EL2`/`VTCR_EL2`/`CNTHCTL_EL2`/`ESR_EL2`/`FAR_EL2`/`VBAR_EL2`/`ELR_EL2`/`SPSR_EL2`) também
/// são atendidos aqui, mas puramente como armazenamento — SEM side effect (nenhum código roda em
/// EL2 ainda; `SCTLR_EL2.M` não liga `mmu`, que é stage-1 de EL1). `ESR_EL2`/`FAR_EL2`/`VBAR_EL2`/
/// `ELR_EL2`/`SPSR_EL2` delegam ao banco por nível de {@link Aarch64ExceptionState} (B10.1), mesma
/// fonte única já usada pelos pares EL1.
///
/// **B10.3**: os 7 registradores EL3 (`SCTLR_EL3`/`SCR_EL3`/`MDCR_EL3`/`CPTR_EL3`/`VBAR_EL3`/
/// `ELR_EL3`/`SPSR_EL3`) idem — armazenamento puro (nenhum código roda em EL3 ainda; roteamento
/// real de `SMC` é B10.5). `VBAR_EL3`/`ELR_EL3`/`SPSR_EL3` delegam ao banco por nível, mesma
/// disciplina dos pares EL1/EL2.
///
/// **B10.6**: `PAR_EL1` é armazenamento puro (`par`), mas {@link #addressTranslate} tem side
/// effect REAL — é quem normalmente escreve `par`, traduzindo de verdade via
/// {@link TranslatingAddressSpace64#translateForAddressTranslate}. Único registrador/método deste
/// barramento com efeito observável fora de si mesmo além dos pares EL1 já existentes.
public final class Aarch64VmsaSystemRegisters implements Aarch64SystemRegisterBus {
    private static final long SCTLR_M_BIT = 1;

    private final TranslatingAddressSpace64 mmu;
    private final Aarch64ExceptionState exceptionState;

    private long ttbr0;
    private long tcr;
    private long mair;

    // ── B10.2: registradores de sistema EL2, armazenamento puro (sem side effect — ver javadoc de
    // ── cada constante em Aarch64SystemRegisterId). ESR_EL2/FAR_EL2/VBAR_EL2/ELR_EL2/SPSR_EL2 NÃO
    // ── têm campo próprio: delegam ao banco por nível de Aarch64ExceptionState (mesma disciplina
    // ── de fonte única já aplicada aos pares EL1 acima).
    private long sctlrEl2;
    private long hcrEl2;
    private long mdcrEl2;
    private long cptrEl2;
    private long tcrEl2;
    private long vttbrEl2;
    private long vtcrEl2;
    private long cnthctlEl2;

    // ── B10.3: registradores de sistema EL3, armazenamento puro (sem side effect). VBAR_EL3/
    // ── ELR_EL3/SPSR_EL3 NÃO têm campo próprio: delegam ao banco por nível de
    // ── Aarch64ExceptionState (mesma disciplina dos pares EL1/EL2 acima).
    private long sctlrEl3;
    private long scrEl3;
    private long mdcrEl3;
    private long cptrEl3;

    // ── B10.6: PAR_EL1 (armazenamento — quem calcula o valor real é addressTranslate) ──────
    private long par;

    /// @param mmu  wrapper (B6.6.2) que este barramento controla
    /// @param core core cujo {@link Aarch64Core#exceptionState()} guarda `ESR_EL1`/`FAR_EL1`/
    ///             `VBAR_EL1`/`ELR_EL1`/`SPSR_EL1` (B6.6.4) — único consumidor real do parâmetro
    ///             {@code core}, antes reservado só por simetria com o precedente 32-bit
    public Aarch64VmsaSystemRegisters(TranslatingAddressSpace64 mmu, Aarch64Core core) {
        this.mmu = mmu;
        this.exceptionState = core.exceptionState();
        // Reset real de hardware: MMU desligada (SCTLR_EL1.M=0) até o software habilitar.
        mmu.setMmuEnabled(false);
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        // B6.6.7: as identidades da CPU (CurrentEL/MPIDR_EL1/.../TPIDR_EL1) NUNCA chegam aqui de
        // qualquer forma (o executor as resolve intrinsecamente antes de consultar este barramento
        // — ver `Aarch64Core#handlesSystemRegisterIntrinsically`); o timer genérico
        // (`CNTFRQ_EL0`/`CNTPCT_EL0`/`CNTP_*`) segue SEM hospedeiro aqui — este barramento é só de
        // MMU/exceção, um consumidor real (F11/B6.6.6) precisa instalar/compor um bus de timer
        // separado quando existir.
        return switch (register) {
            case SCTLR_EL1, TTBR0_EL1, TCR_EL1, MAIR_EL1, ESR_EL1, FAR_EL1, VBAR_EL1, ELR_EL1,
                 SPSR_EL1, SCTLR_EL2, HCR_EL2, MDCR_EL2, CPTR_EL2, TCR_EL2, VTTBR_EL2, VTCR_EL2,
                 SPSR_EL2, ELR_EL2, FAR_EL2, ESR_EL2, CNTHCTL_EL2, VBAR_EL2,
                 SCTLR_EL3, SCR_EL3, MDCR_EL3, CPTR_EL3, SPSR_EL3, ELR_EL3, VBAR_EL3, PAR_EL1 -> true;
            default -> false;
        };
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return switch (register) {
            case SCTLR_EL1 -> sctlrValue();
            case TTBR0_EL1 -> ttbr0;
            case TCR_EL1 -> tcr;
            case MAIR_EL1 -> mair;
            case ESR_EL1 -> exceptionState.esr1();
            case FAR_EL1 -> exceptionState.far1();
            case VBAR_EL1 -> exceptionState.vbar1();
            case ELR_EL1 -> exceptionState.elr1();
            case SPSR_EL1 -> exceptionState.spsr1();
            case SCTLR_EL2 -> sctlrEl2;
            case HCR_EL2 -> hcrEl2;
            case MDCR_EL2 -> mdcrEl2;
            case CPTR_EL2 -> cptrEl2;
            case TCR_EL2 -> tcrEl2;
            case VTTBR_EL2 -> vttbrEl2;
            case VTCR_EL2 -> vtcrEl2;
            case CNTHCTL_EL2 -> cnthctlEl2;
            case ESR_EL2 -> exceptionState.esr(Aarch64ExceptionLevel.EL2);
            case FAR_EL2 -> exceptionState.far(Aarch64ExceptionLevel.EL2);
            case VBAR_EL2 -> exceptionState.vbar(Aarch64ExceptionLevel.EL2);
            case ELR_EL2 -> exceptionState.elr(Aarch64ExceptionLevel.EL2);
            case SPSR_EL2 -> exceptionState.spsr(Aarch64ExceptionLevel.EL2);
            case SCTLR_EL3 -> sctlrEl3;
            case SCR_EL3 -> scrEl3;
            case MDCR_EL3 -> mdcrEl3;
            case CPTR_EL3 -> cptrEl3;
            case VBAR_EL3 -> exceptionState.vbar(Aarch64ExceptionLevel.EL3);
            case ELR_EL3 -> exceptionState.elr(Aarch64ExceptionLevel.EL3);
            case SPSR_EL3 -> exceptionState.spsr(Aarch64ExceptionLevel.EL3);
            case PAR_EL1 -> par;
            default -> throw new UnsupportedOperationException(
                    "Aarch64VmsaSystemRegisters não atende: " + register);
        };
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case SCTLR_EL1 -> mmu.setMmuEnabled((value & SCTLR_M_BIT) != 0);
            case TTBR0_EL1 -> {
                ttbr0 = value;
                mmu.setTtbr0(value);
            }
            case TCR_EL1 -> {
                tcr = value;
                mmu.setTcr(value);
            }
            case MAIR_EL1 -> {
                mair = value;
                mmu.setMair(value);
            }
            case ESR_EL1 -> exceptionState.setEsr1(value);
            case FAR_EL1 -> exceptionState.setFar1(value);
            case VBAR_EL1 -> exceptionState.setVbar1(value);
            case ELR_EL1 -> exceptionState.setElr1(value);
            case SPSR_EL1 -> exceptionState.setSpsr1((int) value);
            case SCTLR_EL2 -> sctlrEl2 = value;
            case HCR_EL2 -> hcrEl2 = value;
            case MDCR_EL2 -> mdcrEl2 = value;
            case CPTR_EL2 -> cptrEl2 = value;
            case TCR_EL2 -> tcrEl2 = value;
            case VTTBR_EL2 -> vttbrEl2 = value;
            case VTCR_EL2 -> vtcrEl2 = value;
            case CNTHCTL_EL2 -> cnthctlEl2 = value;
            case ESR_EL2 -> exceptionState.setEsr(Aarch64ExceptionLevel.EL2, value);
            case FAR_EL2 -> exceptionState.setFar(Aarch64ExceptionLevel.EL2, value);
            case VBAR_EL2 -> exceptionState.setVbar(Aarch64ExceptionLevel.EL2, value);
            case ELR_EL2 -> exceptionState.setElr(Aarch64ExceptionLevel.EL2, value);
            case SPSR_EL2 -> exceptionState.setSpsr(Aarch64ExceptionLevel.EL2, value);
            case SCTLR_EL3 -> sctlrEl3 = value;
            case SCR_EL3 -> scrEl3 = value;
            case MDCR_EL3 -> mdcrEl3 = value;
            case CPTR_EL3 -> cptrEl3 = value;
            case VBAR_EL3 -> exceptionState.setVbar(Aarch64ExceptionLevel.EL3, value);
            case ELR_EL3 -> exceptionState.setElr(Aarch64ExceptionLevel.EL3, value);
            case SPSR_EL3 -> exceptionState.setSpsr(Aarch64ExceptionLevel.EL3, value);
            case PAR_EL1 -> par = value;
            default -> throw new UnsupportedOperationException(
                    "Aarch64VmsaSystemRegisters não atende: " + register);
        }
    }

    @Override
    public void invalidateTlbAll() {
        mmu.invalidateTlbAll();
    }

    /// `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (B10.6): traduz `va` via
    /// {@link TranslatingAddressSpace64#translateForAddressTranslate} e escreve {@link #par} —
    /// sucesso via {@link Aarch64ParEncoder#success}, falha (capturada AQUI, nunca relançada — `AT`
    /// não gera abort) via {@link Aarch64ParEncoder#fault}.
    @Override
    public void addressTranslate(Aarch64AddressTranslateForm form, long va) {
        MemoryAccessType type = form.isWrite() ? MemoryAccessType.DATA_WRITE : MemoryAccessType.DATA_READ;
        try {
            long physicalAddress = mmu.translateForAddressTranslate(va, type, form.isUnprivileged());
            par = Aarch64ParEncoder.success(physicalAddress);
        } catch (MemoryTranslationException64 fault) {
            par = Aarch64ParEncoder.fault(fault.faultStatus());
        }
    }

    private long sctlrValue() {
        return mmu.mmuEnabled() ? SCTLR_M_BIT : 0L;
    }
}
