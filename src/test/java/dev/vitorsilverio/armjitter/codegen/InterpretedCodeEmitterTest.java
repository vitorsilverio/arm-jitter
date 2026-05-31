package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterpretedCodeEmitterTest {
    @Test
    void executesLiftedAluBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        CompiledBlock compiled = new InterpretedCodeEmitter().emit(block);
        int cycles = compiled.execute(core);

        assertEquals(15, core.register(0));
        assertEquals(8, core.programCounter());
        assertEquals(2, cycles);
    }

    @Test
    void executesLiftedBranchBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEA00_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(12, core.programCounter());
    }
}
