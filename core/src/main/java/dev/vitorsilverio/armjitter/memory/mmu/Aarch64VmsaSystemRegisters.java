package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
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
/// `SPSR_EL1` são só armazenamento nesta task (preenchimento real de `ESR`/`FAR` em abort, e o
/// modelo de exceção EL0→EL1 que consome `VBAR`/`ELR`/`SPSR`, ficam para B6.6.4 — mesmo padrão
/// exato de `DFSR`/`DFAR` no precedente 32-bit).
///
/// `TLBI VMALLE1`/`TLBI VMALLE1IS` (D1 da task — achado real: `TLBI` é `SYS`, não `MRS`/`MSR`)
/// chega via {@link #invalidateTlbAll()}, repassado a {@link TranslatingAddressSpace64#invalidateTlbAll}
/// — mesma simplificação "sem per-ASID/per-VA" que o precedente 32-bit já aplica ao `TLBIALL`
/// unificado.
public final class Aarch64VmsaSystemRegisters implements Aarch64SystemRegisterBus {
    private static final long SCTLR_M_BIT = 1;

    private final TranslatingAddressSpace64 mmu;

    private long ttbr0;
    private long tcr;
    private long mair;
    private long esr;
    private long far;
    private long vbar;
    private long elr;
    private long spsr;

    /// @param mmu  wrapper (B6.6.2) que este barramento controla
    /// @param core reservado para simetria com o precedente 32-bit ({@link Cp15VmsaCoprocessor});
    ///             A64 ainda não sincroniza nenhum estado de {@code core} a partir daqui (sem bit
    ///             `V`, sem modelo de exceção nesta task)
    public Aarch64VmsaSystemRegisters(TranslatingAddressSpace64 mmu, Aarch64Core core) {
        this.mmu = mmu;
        // Reset real de hardware: MMU desligada (SCTLR_EL1.M=0) até o software habilitar.
        mmu.setMmuEnabled(false);
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        return true;
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return switch (register) {
            case SCTLR_EL1 -> sctlrValue();
            case TTBR0_EL1 -> ttbr0;
            case TCR_EL1 -> tcr;
            case MAIR_EL1 -> mair;
            case ESR_EL1 -> esr;
            case FAR_EL1 -> far;
            case VBAR_EL1 -> vbar;
            case ELR_EL1 -> elr;
            case SPSR_EL1 -> spsr;
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
            case ESR_EL1 -> esr = value;
            case FAR_EL1 -> far = value;
            case VBAR_EL1 -> vbar = value;
            case ELR_EL1 -> elr = value;
            case SPSR_EL1 -> spsr = value;
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
