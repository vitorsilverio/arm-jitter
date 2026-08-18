package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.SystemInstruction} (task B6.6.3) — instruções `SYS`/`SYS(L)` e
/// barreiras de memória, distintas de `MRS`/`MSR (register)` ({@link Ir64Op.SystemRegister},
/// B6.6.1) mesmo compartilhando a mesma classe de encoding top-level (`op0` diferente, ver
/// `Aarch64Decoder#decodeBranchExceptionSystem`).
public enum Ir64SystemInstructionOp {
    /// `TLBI VMALLE1`/`TLBI VMALLE1IS` (`ARM DDI 0487 C5.2.3`): invalida toda a TLB do EL1 (sem
    /// per-ASID/per-VA — mesma simplificação "invalidar tudo" que `Cp15VmsaCoprocessor`
    /// (32-bit, `TLBIALL`) já aplica). `IS` (inner-shareable) não tem efeito observável adicional
    /// sem múltiplos cores modelados — as duas formas mapeiam para o mesmo valor.
    TLBI_ALL,
    /// `DSB`/`ISB`/`DMB` (qualquer opção de barreira): NOP observável, mesmo precedente de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier} (32-bit) — sem cache nem
    /// pipeline modelados.
    BARRIER,
    /// `NOP`/`YIELD`/`WFE`/`SEV`/`SEVL` (B6.6.7, subgrupo "Hints") — NOP observável, mesmo
    /// tratamento de {@link #BARRIER} (sem event-stream modelado: `WFE` não dorme, `SEV`/`SEVL`
    /// não têm receptor). Registrado como sub-operação separada de {@link #BARRIER} só para deixar
    /// explícito, no código, que a origem do encoding é o subgrupo "Hints" (`CRn=0b0010`), não
    /// "Barriers" (`CRn=0b0011`) — a semântica executada é idêntica.
    NOP_HINT,
    /// `WFI` (B6.6.7, `ARM DDI 0487 C6.2.394`): põe o core para dormir até uma interrupção. Único
    /// hint desta task com semântica própria — ver
    /// {@link dev.vitorsilverio.armjitter.core.CpuSleepState} (reaproveitado diretamente de
    /// `Aarch64Core`, genérico o bastante — mesma disciplina de `ExecutionThreshold` em B6.4 PR1)
    /// e `Aarch64Core#interruptLine`.
    WFI
}
