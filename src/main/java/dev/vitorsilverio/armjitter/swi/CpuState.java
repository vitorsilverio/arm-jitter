package dev.vitorsilverio.armjitter.swi;

/// Snapshot imutavel dos registradores visiveis para handlers de SWI.
public record CpuState(
        /// Registrador r0.
        int r0,
        /// Registrador r1.
        int r1,
        /// Registrador r2.
        int r2,
        /// Registrador r3.
        int r3,
        /// Stack pointer.
        int sp,
        /// Link register.
        int lr,
        /// Program counter.
        int pc,
        /// Valor bruto do CPSR.
        int cpsr) {

    /// Cria uma copia alterando somente `r0`.
    public CpuState withR0(int value) {
        return new CpuState(value, r1, r2, r3, sp, lr, pc, cpsr);
    }

    /// Cria uma copia alterando somente o `pc`.
    public CpuState withPc(int value) {
        return new CpuState(r0, r1, r2, r3, sp, lr, value, cpsr);
    }
}
