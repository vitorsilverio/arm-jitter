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
    void executesLiftedAluBlockWithShiftedRegisterOperand() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE081_0102);
        memory.put32(4, 0xE1A0_30A2);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 3);
        core.setRegister(2, 8);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(35, core.register(0));
        assertEquals(4, core.register(3));
        assertEquals(8, core.programCounter());
        assertEquals(2, cycles);
    }

    @Test
    void executesLiftedAluBlockWithRegisterShiftAndRrx() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE081_0312);
        memory.put32(4, 0xE1A0_4062);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 5);
        core.setRegister(2, 3);
        core.setRegister(3, 2);
        core.cpsr().setNzcv(false, false, true, false);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(17, core.register(0));
        assertEquals(0x8000_0001, core.register(4));
        assertEquals(8, core.programCounter());
        assertEquals(2, cycles);
    }

    @Test
    void executesLiftedLogicalCarryFromShifterOperand() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE1B0_00A1);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 3);
        core.cpsr().setNzcv(false, false, false, false);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(1, core.register(0));
        assertTrue(core.cpsr().carry());
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedClz() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE16F_1F10);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0x0000_8000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(16, core.register(1));
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedArmAluReadsPipelinePcInsideBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE28F_1004);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(1, core.register(0));
        assertEquals(16, core.register(1));
        assertEquals(8, core.programCounter());
        assertEquals(2, cycles);
    }

    @Test
    void executesLiftedArmBxReadsPipelinePcInsideBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12F_FF1F);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertFalse(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedMlaBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE021_3290);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 6);
        core.setRegister(2, 7);
        core.setRegister(3, 5);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(47, core.register(1));
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedLongMultiplyBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE083_2190);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0xFFFF_FFFF);
        core.setRegister(1, 2);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(0xFFFF_FFFE, core.register(2));
        assertEquals(1, core.register(3));
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedAluPcWriteAsTerminalBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE1A0_F00E);
        memory.put32(4, 0xE3A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(14, 0x103);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(0x100, core.programCounter());
        assertEquals(0, core.register(0));
        assertEquals(1, cycles);
    }

    @Test
    void executesLiftedAluPcWriteWithSpsrRestore() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE25E_F004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(dev.vitorsilverio.armjitter.core.CpuMode.SUPERVISOR);
        core.setSpsr(dev.vitorsilverio.armjitter.core.CpuMode.SUPERVISOR, 0x30);
        core.setRegister(14, 0x105);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        int cycles = new InterpretedCodeEmitter().emit(block).execute(core);

        assertEquals(dev.vitorsilverio.armjitter.core.CpuMode.USER, core.mode());
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
        assertEquals(1, cycles);
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
