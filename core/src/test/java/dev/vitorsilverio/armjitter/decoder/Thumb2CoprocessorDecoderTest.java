package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B2.7 (PR3) — `Thumb2CoprocessorDecoder`: casca fina que reusa `CoprocessorDecoder` (`MCR`/
/// `MRC`) para o espaço Thumb-2 de 32 bits (`hw1=0xEE`, MESMO layout de bits do encoding ARM
/// clássico). Ver `Thumb2CoprocessorDecoder` para a citação do QEMU `t32.decode`.
class Thumb2CoprocessorDecoderTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.ARMV6K_THUMB2;
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    // ── Encoder MCR/MRC Thumb-2 (raw32 = hi<<16|lo, MESMO layout de CoprocessorTest) ────────

    private static int mcrMrcThumb2(boolean load, int opc1, int crn, int rt, int cp, int opc2, int crm) {
        return (0xEE << 24) | ((opc1 & 0x7) << 21) | ((load ? 1 : 0) << 20) | ((crn & 0xF) << 16)
                | ((rt & 0xF) << 12) | ((cp & 0xF) << 8) | ((opc2 & 0x7) << 5) | (1 << 4) | (crm & 0xF);
    }

    // ── Encoder MCR/MRC ARM clássico (mesmo layout de CoprocessorTest) ─────────────────────

    private static int mcrMrcArm(boolean load, int opc1, int crn, int rt, int cp, int opc2, int crm) {
        return 0xE000_0010 | (0xE << 24) | ((opc1 & 0x7) << 21) | ((load ? 1 : 0) << 20) | ((crn & 0xF) << 16)
                | ((rt & 0xF) << 12) | ((cp & 0xF) << 8) | ((opc2 & 0x7) << 5) | (crm & 0xF);
    }

    // ── LDREX/STREX/CLREX Thumb-2 word (mesmo layout de Thumb2LoadStoreDecoderTest/Thumb2MiscDecoderTest) ──

    private static int ldrexWordT(int rn, int rt) {
        return (0b1110100 << 25) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12) | (0xF << 8);
    }

    private static int strexWordT(int rn, int rt, int rd) {
        return (0b1110100 << 25) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12) | (rd << 8);
    }

    private static final int MISC_CONTROL_HI = 0xF3BF;
    private static final int CLREX_LO = 0x8F2F;

    private static ArmCore newThumb2Core() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }

    private static void run32(ArmCore core, int raw) {
        int base = core.programCounter();
        put16(core, base, raw >>> 16);
        put16(core, base + 2, raw & 0xFFFF);
        core.step();
    }

    /// Um CP15 substituto que registra a última transferência e retorna um valor fixo nas
    /// leituras — MESMO papel de `CoprocessorTest#CapturingCp15`.
    private static final class CapturingCp15 implements CoprocessorBus {
        private final int readValue;
        boolean wrote;
        int coprocessor;
        int opcode1;
        int crn;
        int crm;
        int opcode2;
        int value;

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

    // ── MCR/MRC Thumb-2: ida-e-volta com bus fake, comparado com o ARM clássico ────────────

    @Test
    void mcrThumb2ForwardsRegisterValueToCoprocessorLikeArmClassic() {
        CapturingCp15 thumb2Bus = new CapturingCp15(0);
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setCoprocessorBus(thumb2Bus);
        thumb2Core.setRegister(1, 0xDEADBEEF);
        run32(thumb2Core, mcrMrcThumb2(false, 0, 9, 1, 15, 0, 1)); // MCR p15,0,r1,c9,c1,0

        CapturingCp15 armBus = new CapturingCp15(0);
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setCoprocessorBus(armBus);
        armCore.setRegister(1, 0xDEADBEEF);
        armCore.memory().write32(0, mcrMrcArm(false, 0, 9, 1, 15, 0, 1));
        armCore.step();

        assertTrue(thumb2Bus.wrote);
        assertEquals(armBus.coprocessor, thumb2Bus.coprocessor);
        assertEquals(armBus.opcode1, thumb2Bus.opcode1);
        assertEquals(armBus.crn, thumb2Bus.crn);
        assertEquals(armBus.crm, thumb2Bus.crm);
        assertEquals(armBus.opcode2, thumb2Bus.opcode2);
        assertEquals(armBus.value, thumb2Bus.value);
        assertEquals(0xDEADBEEF, thumb2Bus.value);
    }

    @Test
    void mrcThumb2LoadsCoprocessorValueIntoRegisterLikeArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setCoprocessorBus(new CapturingCp15(0x12345678));
        run32(thumb2Core, mcrMrcThumb2(true, 0, 9, 2, 15, 0, 1)); // MRC p15,0,r2,c9,c1,0

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setCoprocessorBus(new CapturingCp15(0x12345678));
        armCore.memory().write32(0, mcrMrcArm(true, 0, 9, 2, 15, 0, 1));
        armCore.step();

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(0x12345678, thumb2Core.register(2));
    }

    // ── Gating G2: sem THUMB2, o candidato não vira COPROCESSOR ─────────────────────────────

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = mcrMrcThumb2(false, 0, 9, 1, 15, 0, 1);
        memory.put16(0, raw >>> 16);
        memory.put16(2, raw & 0xFFFF);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.COPROCESSOR, instruction.kind());
    }

    // ── Equivalência interpretado × ASM nativo: LDREX+STREX+CLREX+MCR+MRC, 0 fallback ──────

    @Test
    void mixedExclusiveAndCoprocessorBlockHasZeroPerOpFallback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int address = 0;
        int ldrex = ldrexWordT(1, 2);
        memory.put16(address, ldrex >>> 16); address += 2;
        memory.put16(address, ldrex & 0xFFFF); address += 2;
        int strex = strexWordT(1, 2, 3);
        memory.put16(address, strex >>> 16); address += 2;
        memory.put16(address, strex & 0xFFFF); address += 2;
        memory.put16(address, MISC_CONTROL_HI); address += 2;
        memory.put16(address, CLREX_LO); address += 2;
        int mcr = mcrMrcThumb2(false, 0, 9, 1, 15, 0, 1);
        memory.put16(address, mcr >>> 16); address += 2;
        memory.put16(address, mcr & 0xFFFF); address += 2;
        int mrc = mcrMrcThumb2(true, 0, 9, 4, 15, 0, 1);
        memory.put16(address, mrc >>> 16); address += 2;
        memory.put16(address, mrc & 0xFFFF); address += 2;
        int instructionCount = address / 2 / 2;

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, instructionCount);

        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        harness.assertEquivalent(reference, perOpEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.setRegister(1, 0x20);
            core.setCoprocessorBus(new CapturingCp15(0x99));
        }));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }

    // ── Regressão: BL/BLX de 32 bits (B2.6) intacto com a extensão nova plugada ─────────────

    @Test
    void blImmediateStillDecodesAsSingleThirtyTwoBitInstructionWithThisExtensionPlugged() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // prefixo BL, highOffset=0
        memory.put16(2, 0xF800); // sufixo BL, lowOffset=0
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.LONG_BRANCH_32, instruction.kind());
    }
}
