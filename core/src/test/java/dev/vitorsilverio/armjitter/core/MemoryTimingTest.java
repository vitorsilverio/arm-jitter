package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryTimingTest {
    @Test
    void interpreterAddsFetchAndDataWaitstatesToCoreCycles() {
        TimedAddressSpace memory = new TimedAddressSpace(32);
        memory.put32(0, 0xE590_1000);
        memory.put32(8, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 8);

        core.step();

        assertEquals(0xCAFE_BABE, core.register(1));
        assertEquals(8, core.cycles());
    }

    @Test
    void runtimeAddsFetchAndDataWaitstatesToCoreCycles() {
        TimedAddressSpace memory = new TimedAddressSpace(32);
        memory.put32(0, 0xE590_1000);
        memory.put32(4, 0xE7F0_00F0);
        memory.put32(8, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 8);

        int blockCycles = core.runBlock(dev.vitorsilverio.armjitter.jit.JitRuntimeFactory.interpretedArmThumb(16, 1));

        assertEquals(1, blockCycles);
        assertEquals(0xCAFE_BABE, core.register(1));
        assertEquals(8, core.cycles());
    }

    @Test
    void busReportingNoAccessCyclesSkipsWaitstateAccounting() {
        // O barramento retornaria 1000 ciclos por acesso, mas declara não ter waitstates
        // (providesAccessCycles()==false). O core deve pular accessCycles por completo,
        // resultando nos mesmos ciclos de um barramento com waitstate 0.
        ConfigurableBus optOut = program(new ConfigurableBus(32, 1000, false));
        ConfigurableBus zero = program(new ConfigurableBus(32, 0, true));
        ArmCore optOutCore = new ArmCore(optOut, SwiDispatcher.empty());
        ArmCore zeroCore = new ArmCore(zero, SwiDispatcher.empty());
        optOutCore.setRegister(0, 8);
        zeroCore.setRegister(0, 8);

        optOutCore.step();
        zeroCore.step();

        assertEquals(0xCAFE_BABE, optOutCore.register(1));
        assertEquals(zeroCore.cycles(), optOutCore.cycles(),
                "providesAccessCycles()==false deve pular a contabilização de waitstates");
    }

    private static ConfigurableBus program(ConfigurableBus bus) {
        bus.put32(0, 0xE590_1000); // ldr r1, [r0]
        bus.put32(8, 0xCAFE_BABE);
        return bus;
    }

    private static final class ConfigurableBus implements AddressSpace {
        private final byte[] data;
        private final int waitCycles;
        private final boolean providesAccessCycles;

        private ConfigurableBus(int size, int waitCycles, boolean providesAccessCycles) {
            this.data = new byte[size];
            this.waitCycles = waitCycles;
            this.providesAccessCycles = providesAccessCycles;
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
            return waitCycles;
        }

        @Override
        public boolean providesAccessCycles() {
            return providesAccessCycles;
        }
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
