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
    WFI,
    /// As 10 operações de manutenção de cache `IC`/`DC` decodificadas em
    /// `Aarch64Decoder#SYSTEM_INSTRUCTION_CACHE_OPS` (B6.12): NOP observável, mesmo precedente de
    /// {@link #BARRIER}/{@link #NOP_HINT} — este emulador não modela caches, achado confirmado
    /// contra `helper.c` real do QEMU (`v8_cp_reginfo`, todas marcadas `ARM_CP_NOP` lá pelo mesmo
    /// motivo). `DC ZVA` fica FORA deste grupo (tem efeito observável real, já anunciada como
    /// indisponível via `DCZID_EL0.DZP=1`, B6.10).
    CACHE_MAINTENANCE_NOP,
    /// `CLREX` (B8.3, `ARM DDI 0487 C6.2.62`): fecha o monitor de exclusividade sem completar
    /// nenhum `STXR`/`STLXR` — mesmo efeito observável de uma exceção/`ERET` sobre o monitor
    /// (`Aarch64Core#clearExclusiveMonitor`, já usado por `enterMemoryAbort`/`enterIrq`), só que
    /// disparado por uma instrução explícita do guest em vez de uma entrada de exceção.
    CLEAR_EXCLUSIVE,
    /// Formas de `MSR (immediate)` sem efeito observável neste emulador (B8.3): `UAO`/`PAN`/
    /// `SPSel`/`SBSS`/`DIT`/`TCO` — nenhum desses campos de `PSTATE` é lido por nenhum consumidor
    /// modelado (sem MMU com checagem `PAN`/`UAO`, sem banking real de `SP_EL0`/`SP_EL1` distinto
    /// por `SPSel`, sem telemetria de `DIT`, sem tags MTE que `TCO` afetaria). Mesma disciplina de
    /// {@link #NOP_HINT}: decodifica corretamente e nomeado (evita a confusão silenciosa com
    /// `CFINV`/`XAFLAG`/`AXFLAG`, que compartilhavam o mesmo `CRn` e foram encontradas colidindo
    /// por acaso com estas formas antes desta task — ver Armadilhas da task B8.3), mas não guarda
    /// estado nenhum.
    PSTATE_FIELD_NOP
}
