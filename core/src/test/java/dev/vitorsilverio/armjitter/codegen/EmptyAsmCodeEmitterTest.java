package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmptyAsmCodeEmitterTest {
    @Test
    void emitsJvmBlockThatReturnsZeroInternalCycles() {
        IrBlock block = IrBlock.builder(0).endPc(4).sealed();
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());

        int cycles = new EmptyAsmCodeEmitter().emit(block).execute(core);

        assertEquals(0, cycles);
        assertEquals(0, core.cycles());
    }

    @Test
    void reportsJvmBytecodeBackend() {
        assertEquals(CodegenBackend.JVM_BYTECODE, new EmptyAsmCodeEmitter().backend());
    }
}
