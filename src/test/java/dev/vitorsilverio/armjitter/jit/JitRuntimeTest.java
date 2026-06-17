package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitRuntimeTest {
    private static final int RESET_CPSR = CpuMode.SUPERVISOR.bits()
            | dev.vitorsilverio.armjitter.core.CpsrRegister.IRQ_DISABLE_FLAG
            | dev.vitorsilverio.armjitter.core.CpsrRegister.FIQ_DISABLE_FLAG;

    @Test
    void interpretsColdThenCachesHotBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 2);

        assertEquals(1, runtime.execute(0, core));
        assertEquals(10, core.register(0));
        assertEquals(0, runtime.blockCache().size());

        core.setRegister(0, 0);
        assertEquals(2, runtime.execute(0, core));
        assertEquals(15, core.register(0));
        assertEquals(1, runtime.blockCache().size());

        core.setRegister(0, 0);
        assertEquals(2, runtime.execute(0, core));
        assertEquals(15, core.register(0));
    }

    @Test
    void compilesBlockExplicitly() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_002A);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1);

        CompiledBlock block = runtime.compile(0, memory);
        int cycles = block.execute(core);

        assertEquals(42, core.register(0));
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }

    @Test
    void coldPathReturnsInternalCyclesWithoutFetchOrMemoryWaitstates() {
        TimedAddressSpace memory = new TimedAddressSpace(32);
        memory.put32(0, 0xE590_1000);
        memory.put32(8, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 8);
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 2);

        assertEquals(1, runtime.execute(0, core));
        assertEquals(0xCAFE_BABE, core.register(1));
        assertEquals(8, core.cycles());
        assertEquals(0, runtime.blockCache().size());
    }

    @Test
    void compiledUnimplementedInstructionEntersUndefinedVector() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEE00_0000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1);

        assertEquals(1, runtime.execute(0, core));

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertEquals(4, core.register(14));
        assertEquals(RESET_CPSR, core.spsr(CpuMode.UNDEFINED));
    }

    private static final class TimedAddressSpace implements AddressSpace {
        private final byte[] data;

        private TimedAddressSpace(int size) {
            data = new byte[size];
        }

        private void put32(int address, int value) {
            data[address] = (byte) value;
            data[address + 1] = (byte) (value >>> 8);
            data[address + 2] = (byte) (value >>> 16);
            data[address + 3] = (byte) (value >>> 24);
        }

        @Override
        public int read8(int address) {
            return data[address] & 0xFF;
        }

        @Override
        public int read16(int address) {
            return read8(address) | (read8(address + 1) << 8);
        }

        @Override
        public int read32(int address) {
            return read16(address) | (read16(address + 2) << 16);
        }

        @Override
        public void write8(int address, int value) {
            data[address] = (byte) value;
        }

        @Override
        public void write16(int address, int value) {
            write8(address, value);
            write8(address + 1, value >>> 8);
        }

        @Override
        public void write32(int address, int value) {
            write16(address, value);
            write16(address + 2, value >>> 16);
        }

        @Override
        public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
            return switch (type) {
                case INSTRUCTION_FETCH -> 2;
                case DATA_READ -> 5;
                case DATA_WRITE -> 7;
            };
        }
    }
}
