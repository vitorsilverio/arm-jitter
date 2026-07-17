package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Ler R15 como operando de ALU precisa resultar em (endereço da instrução + 8) mesmo em endereços
/// altos como a BIOS do ARM9 em 0xFFFF0000+, cujos valores de PC são negativos como int. A sentinela
/// de "sem override" era antes distinguida com `>= 0`, o que descartava erroneamente esses overrides
/// negativos e lia o PC obsoleto do bloco — exatamente o bug que travava o tratador de IRQ da BIOS do ARM9.
class HighAddressPcOperandTest {

    /// RAM mapeada numa base arbitrária, para podermos rodar código em endereços no estilo 0xFFFF0000.
    private static final class BasedMemory implements AddressSpace {
        private final int base;
        private final byte[] data;

        BasedMemory(int base, int size) {
            this.base = base;
            this.data = new byte[size];
        }

        void put32(int address, int value) {
            int o = address - base;
            data[o] = (byte) value;
            data[o + 1] = (byte) (value >>> 8);
            data[o + 2] = (byte) (value >>> 16);
            data[o + 3] = (byte) (value >>> 24);
        }

        private int offset(int address) {
            return address - base;
        }

        @Override
        public int read8(int address) {
            int o = offset(address);
            return o >= 0 && o < data.length ? data[o] & 0xFF : 0;
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
            int o = offset(address);
            if (o >= 0 && o < data.length) {
                data[o] = (byte) value;
            }
        }

        @Override
        public void write16(int address, int value) {
            write8(address & ~1, value);
            write8((address & ~1) + 1, value >>> 8);
        }

        @Override
        public void write32(int address, int value) {
            write16(address, value);
            write16(address + 2, value >>> 16);
        }
    }

    @Test
    void aluReadsPcAsAddressPlusEightAtHighAddresses() {
        int base = 0xFFFF0000;
        BasedMemory memory = new BasedMemory(base, 0x100);
        // Dois no-ops iniciais para que o `add` não seja a primeira instrução do bloco, depois
        // `add r0, pc, #0` em 0xFFFF0008 — r0 precisa virar 0xFFFF0010 (endereço + 8).
        memory.put32(0xFFFF0000, 0xE1A01001); // mov r1, r1 (nop)
        memory.put32(0xFFFF0004, 0xE1A01001); // mov r1, r1 (nop)
        memory.put32(0xFFFF0008, 0xE28F0000); // add r0, pc, #0
        memory.put32(0xFFFF000C, 0xEAFFFFFE); // b . (block terminator)

        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.configureExecutionState(base, CpuMode.SYSTEM, InstructionSet.ARM, true, true);
        core.step(); // nop
        core.step(); // nop
        core.step(); // add r0, pc, #0

        assertEquals(0xFFFF0010, core.register(0), "R15 reads as the instruction address + 8");
    }
}
