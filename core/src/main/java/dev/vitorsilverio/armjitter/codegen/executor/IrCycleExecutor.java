package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Executa contagem de ciclos e fetch de instrução da IR interpretada.
final class IrCycleExecutor {
    int executeCycle(IrOp.Cycle cycle) {
        return cycle.count();
    }

    void executeFetch(ArmCore core, IrOp.Fetch fetch) {
        core.addMemoryCycles(fetch.address(), fetch.sizeBytes(), MemoryAccessType.INSTRUCTION_FETCH);
    }
}
