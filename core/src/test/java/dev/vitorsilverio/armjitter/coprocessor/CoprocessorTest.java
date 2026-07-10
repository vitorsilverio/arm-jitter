package dev.vitorsilverio.armjitter.coprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Exercita o caminho de coprocessador ARMv5 (MCR/MRC) de ponta a ponta: decodifica via a
/// extensão em ARMV5TE, levanta para IR, executa e entrega ao coprocessor bus do core. O CP15
/// fake apenas registra a transferência — sem semântica real — portanto valida o encadeamento.
class CoprocessorTest {
    // MCR p15, 0, r1, c9, c1, 0   and   MRC p15, 0, r2, c9, c1, 0
    private static final int MCR_P15_R1_C9_C1_0 = 0xEE091F11;
    private static final int MRC_P15_R2_C9_C1_0 = 0xEE192F11;

    @Test
    void mcrForwardsRegisterValueToCoprocessor() {
        CapturingCp15 cp15 = new CapturingCp15(0);
        ArmCore core = newCore(MCR_P15_R1_C9_C1_0, cp15);
        core.setRegister(1, 0xDEADBEEF);

        core.step();

        assertTrue(cp15.wrote);
        assertEquals(15, cp15.coprocessor);
        assertEquals(0, cp15.opcode1);
        assertEquals(9, cp15.crn);
        assertEquals(1, cp15.crm);
        assertEquals(0, cp15.opcode2);
        assertEquals(0xDEADBEEF, cp15.value);
        assertEquals(4, core.programCounter());
    }

    @Test
    void mrcLoadsCoprocessorValueIntoRegister() {
        CapturingCp15 cp15 = new CapturingCp15(0x12345678);
        ArmCore core = newCore(MRC_P15_R2_C9_C1_0, cp15);

        core.step();

        assertEquals(0x12345678, core.register(2));
        assertEquals(9, cp15.crn);
        assertEquals(1, cp15.crm);
        assertEquals(4, core.programCounter());
    }

    @Test
    void absentCoprocessorTakesUndefinedVector() {
        ArmCore core = newCore(MCR_P15_R1_C9_C1_0, null); // keeps the default CoprocessorBus.none()

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter()); // undefined-instruction vector
    }

    private static ArmCore newCore(int instruction, CoprocessorBus cp15) {
        ArrayMemory memory = new ArrayMemory();
        memory.write32(0, instruction);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        if (cp15 != null) {
            core.setCoprocessorBus(cp15);
        }
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);
        return core;
    }

    /// Um CP15 substituto que registra a última transferência e retorna um valor fixo nas leituras.
    private static final class CapturingCp15 implements CoprocessorBus {
        private final int readValue;
        private boolean wrote;
        private int coprocessor;
        private int opcode1;
        private int crn;
        private int crm;
        private int opcode2;
        private int value;

        CapturingCp15(int readValue) {
            this.readValue = readValue;
        }

        @Override
        public boolean handles(int coprocessor) {
            return coprocessor == 15;
        }

        @Override
        public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            this.coprocessor = coprocessor;
            this.opcode1 = opcode1;
            this.crn = crn;
            this.crm = crm;
            this.opcode2 = opcode2;
            return readValue;
        }

        @Override
        public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            this.wrote = true;
            this.coprocessor = coprocessor;
            this.opcode1 = opcode1;
            this.crn = crn;
            this.crm = crm;
            this.opcode2 = opcode2;
            this.value = value;
        }
    }

    /// Um pequeno address space little-endian de 256 bytes para testes de instrução única.
    private static final class ArrayMemory implements AddressSpace {
        private final byte[] data = new byte[0x100];

        @Override
        public int read8(int address) {
            return data[address & 0xFF] & 0xFF;
        }

        @Override
        public int read16(int address) {
            int a = address & 0xFF;
            return (data[a] & 0xFF) | ((data[a + 1] & 0xFF) << 8);
        }

        @Override
        public int read32(int address) {
            int a = address & 0xFF;
            return (data[a] & 0xFF) | ((data[a + 1] & 0xFF) << 8)
                    | ((data[a + 2] & 0xFF) << 16) | ((data[a + 3] & 0xFF) << 24);
        }

        @Override
        public void write8(int address, int value) {
            data[address & 0xFF] = (byte) value;
        }

        @Override
        public void write16(int address, int value) {
            int a = address & 0xFF;
            data[a] = (byte) value;
            data[a + 1] = (byte) (value >>> 8);
        }

        @Override
        public void write32(int address, int value) {
            int a = address & 0xFF;
            data[a] = (byte) value;
            data[a + 1] = (byte) (value >>> 8);
            data[a + 2] = (byte) (value >>> 16);
            data[a + 3] = (byte) (value >>> 24);
        }
    }
}
