package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;

/// Estado compartilhado pelos emissores de família de um {@link AsmBlockCompiler}: as flags da
/// arquitetura (fixas) e o que muda por compilação (register cache e PC de fim do bloco).
final class AsmEmitState {
    /// `true` em ARMv5T+: LDR/LDM/POP para PC interworkam pelo bit 0 do valor carregado.
    final boolean loadPcInterworks;
    /// `true` sob {@link dev.vitorsilverio.armjitter.arch.ArmFeature#UNALIGNED_ACCESS} (ARMv6+):
    /// `LDR`/`STR`/`LDRH`/`STRH` com destino diferente do PC emitem os helpers "Crossed".
    final boolean unalignedAccess;
    /// Executor interpretado da arquitetura, registrado com cada op de fallback (ver {@link IrOpInterop}).
    final IrBlockExecutor perOpExecutor;
    /// Register cache do bloco em compilação; {@link AsmRegCache#EMPTY} fora de uma compilação.
    AsmRegCache cache = AsmRegCache.EMPTY;
    /// `block.endPc()` do bloco em compilação (SWI e fallback por op).
    int blockEndPc;

    AsmEmitState(boolean loadPcInterworks, boolean unalignedAccess, IrBlockExecutor perOpExecutor) {
        this.loadPcInterworks = loadPcInterworks;
        this.unalignedAccess = unalignedAccess;
        this.perOpExecutor = perOpExecutor;
    }
}
