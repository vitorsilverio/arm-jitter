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
    // MCR p15, 0, r1, c9, c1, 0   e   MRC p15, 0, r2, c9, c1, 0
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
        ArmCore core = newCore(MCR_P15_R1_C9_C1_0, null); // mantém o CoprocessorBus.none() padrão

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter()); // vetor de instrução indefinida
    }

    @Test
    void finePartialCp15AllowsClaimedRegisterAndUndefinesTheRest() {
        // MRC p15, 0, r0, c13, c0, 3 — o único registrador que este bus fake reivindica.
        int mrcC13 = 0xEE1D0F70;
        ArmCore core = newCore(mrcC13, new PartialCp15(0xABCDEF01));

        core.step();

        assertEquals(0xABCDEF01, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void finePartialCp15UndefinesUnclaimedRegisterWithoutLeakingJavaException() {
        // o MESMO bus fake de handles fino, mas agora MCR_P15_R1_C9_C1_0 (c9), que ele não reivindica
        ArmCore core = newCore(MCR_P15_R1_C9_C1_0, new PartialCp15(0));

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter()); // vetor de instrução indefinida
    }

    // MCRR p15, 0, r1, r2, c6   e   MRRC p15, 0, r1, r2, c6 (F3 — transferência DUPLA)
    private static final int MCRR_P15_R1_R2_C6 = 0xEC421F06;
    private static final int MRRC_P15_R1_R2_C6 = 0xEC521F06;

    @Test
    void mcrrForwardsBothRegisterValuesToCoprocessor() {
        CapturingDoubleCp15 cp15 = new CapturingDoubleCp15(0);
        ArmCore core = newCore(MCRR_P15_R1_R2_C6, cp15);
        core.setRegister(1, 0x1111_1111);
        core.setRegister(2, 0x2222_2222);

        core.step();

        assertTrue(cp15.wrote);
        assertEquals(15, cp15.coprocessor);
        assertEquals(0, cp15.opcode1);
        assertEquals(6, cp15.crm);
        assertEquals(0x1111_1111, cp15.rt);
        assertEquals(0x2222_2222, cp15.rt2);
        assertEquals(4, core.programCounter());
    }

    @Test
    void mrrcLoadsCoprocessorValueIntoBothRegisters() {
        // rt (baixo) = 0x600DF00D, rt2 (alto) = 0xCAFEBABE
        long packed = 0x600D_F00DL | (0xCAFE_BABEL << 32);
        CapturingDoubleCp15 cp15 = new CapturingDoubleCp15(packed);
        ArmCore core = newCore(MRRC_P15_R1_R2_C6, cp15);

        core.step();

        assertEquals(0x600D_F00D, core.register(1));
        assertEquals(0xCAFE_BABE, core.register(2));
        assertEquals(6, cp15.crm);
        assertEquals(4, core.programCounter());
    }

    @Test
    void absentCoprocessorDoubleTakesUndefinedVector() {
        ArmCore core = newCore(MCRR_P15_R1_R2_C6, null); // mantém o CoprocessorBus.none() padrão

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void handlesDoubleDefaultsToFalseEvenWhenCoarseHandlesIsTrue() {
        // Regressão (achado real desta sessão): um CoprocessorBus que só implementa MCR/MRC
        // (handles(int)=true grosso para reivindicar o coprocessador inteiro, padrão de TODAS as
        // implementações pré-F3 deste código-base) NÃO deve reivindicar MCRR/MRRC por acidente —
        // handlesDouble tem que devolver false por padrão, não delegar ao grosso.
        ArmCore core = newCore(MCRR_P15_R1_R2_C6, new CapturingCp15(0)); // só implementa MCR/MRC

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
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

    /// Um CP15 substituto que registra a última transferência DUPLA (`MCRR`/`MRRC`, F3) e retorna
    /// um valor de 64 bits fixo nas leituras (empacotado com `Rt` nos bits baixos, `Rt2` nos altos
    /// — mesma convenção de {@link CoprocessorBus#readDouble}).
    private static final class CapturingDoubleCp15 implements CoprocessorBus {
        private final long readValue;
        private boolean wrote;
        private int coprocessor;
        private int opcode1;
        private int crm;
        private int rt;
        private int rt2;

        CapturingDoubleCp15(long readValue) {
            this.readValue = readValue;
        }

        @Override
        public boolean handles(int coprocessor) {
            return coprocessor == 15;
        }

        @Override
        public boolean handlesDouble(int coprocessor, int opcode1, int crm) {
            return coprocessor == 15;
        }

        @Override
        public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            throw new IllegalStateException("bug: teste só exercita a forma DUPLA");
        }

        @Override
        public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            throw new IllegalStateException("bug: teste só exercita a forma DUPLA");
        }

        @Override
        public long readDouble(int coprocessor, int opcode1, int crm) {
            this.coprocessor = coprocessor;
            this.opcode1 = opcode1;
            this.crm = crm;
            return readValue;
        }

        @Override
        public void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
            this.wrote = true;
            this.coprocessor = coprocessor;
            this.opcode1 = opcode1;
            this.crm = crm;
            this.rt = rt;
            this.rt2 = rt2;
        }
    }

    /// Um CP15 fake que atende (grosso) CP15, mas só reivindica (fino) `c13,c0,3` — usado para
    /// provar que `IrSystemExecutor#executeCoprocessor` consulta o predicado fino, não o grosso,
    /// e que nenhuma exceção Java escapa para um registrador que este bus não reivindica.
    private static final class PartialCp15 implements CoprocessorBus {
        private final int c13Value;

        PartialCp15(int c13Value) {
            this.c13Value = c13Value;
        }

        @Override
        public boolean handles(int coprocessor) {
            return coprocessor == 15;
        }

        @Override
        public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            return coprocessor == 15 && crn == 13 && crm == 0 && opcode2 == 3;
        }

        @Override
        public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            if (crn == 13 && crm == 0 && opcode2 == 3) {
                return c13Value;
            }
            throw new IllegalStateException("bug: executor não consultou handles fino");
        }

        @Override
        public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            throw new IllegalStateException("bug: executor não consultou handles fino");
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
