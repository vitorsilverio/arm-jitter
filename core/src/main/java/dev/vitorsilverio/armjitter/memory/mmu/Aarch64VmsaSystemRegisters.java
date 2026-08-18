package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

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
public final class Aarch64VmsaSystemRegisters implements Aarch64SystemRegisterBus {
    private static final long SCTLR_M_BIT = 1;

    private final TranslatingAddressSpace64 mmu;
    private final Aarch64ExceptionState exceptionState;

    private long ttbr0;
    private long tcr;
    private long mair;

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
                 SPSR_EL1 -> true;
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
            default -> throw new UnsupportedOperationException(
                    "Aarch64VmsaSystemRegisters não atende: " + register);
        }
    }

    @Override
    public void invalidateTlbAll() {
        mmu.invalidateTlbAll();
    }

    private long sctlrValue() {
        return mmu.mmuEnabled() ? SCTLR_M_BIT : 0L;
    }
}
