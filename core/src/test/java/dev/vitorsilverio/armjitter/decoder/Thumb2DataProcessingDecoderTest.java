package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

/// B2.2 — data-processing Thumb-2 de 32 bits: modified immediate (ThumbExpandImm_C, incl. carry),
/// `MOVW`/`MOVT`, `ADD`/`SUB` plain binary (incl. `ADR` via `Rn=PC`), e a forma registrador com
/// shift imediato. Ver `Thumb2DataProcessingDecoder` para o layout de bits.
class Thumb2DataProcessingDecoderTest extends BlockEquivalenceTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.extending(
                    ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-DataProcessing", ArmFeature.THUMB2)
            .withThumb32DecoderExtensions(List.of(new Thumb2DataProcessingDecoder()));

    // ── Builders de encoding (ver Thumb2DataProcessingDecoder para o layout) ───────────────

    private static int modifiedImmediateHi(int i, int op4, boolean s, int rn) {
        return (0b11110 << 11) | (i << 10) | (op4 << 5) | ((s ? 1 : 0) << 4) | rn;
    }

    private static int dataProcessingLo(int imm3, int rd, int imm8) {
        return (imm3 << 12) | (rd << 8) | imm8;
    }

    private static int registerFormHi(int op4, boolean s, int rn) {
        return (0b1110101 << 9) | (op4 << 5) | ((s ? 1 : 0) << 4) | rn;
    }

    private static int registerFormLo(int imm3, int rd, int imm2, int shiftType, int rm) {
        return (imm3 << 12) | (rd << 8) | (imm2 << 6) | (shiftType << 4) | rm;
    }

    private static int plainBinaryHi(int i, int opNibble, int rn) {
        return (0b11110 << 11) | (i << 10) | (1 << 9) | (opNibble << 5) | rn;
    }

    private static int moveWideHi(int i, int opNibble, int imm4) {
        return (0b11110 << 11) | (i << 10) | (1 << 9) | (opNibble << 5) | imm4;
    }

    private static final int OP4_AND = 0b0000;
    private static final int OP4_ORR = 0b0010;
    private static final int OP4_ORN = 0b0011;
    private static final int OP4_ADD = 0b1000;
    private static final int PLAIN_OP_ADD = 0b0000;
    private static final int PLAIN_OP_SUB = 0b0101;
    private static final int PLAIN_OP_MOVW = 0b0010;
    private static final int PLAIN_OP_MOVT = 0b0110;
    private static final int SHIFT_LSL = 0;
    private static final int SHIFT_ROR = 3;

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void run(ArmCore core, int... halfwords) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        for (int i = 0; i < halfwords.length; i++) {
            memory.put16(base + i * 2, halfwords[i]);
        }
        for (int i = 0; i < halfwords.length; i += 2) {
            core.step();
        }
    }

    // ── ThumbExpandImm: os 4 casos de decodificação ─────────────────────────────────────────

    @Test
    void thumbExpandImmZeroExtendsWhenSelectorIsZero() {
        // i=0, imm3<1:0>=00 (byte simples): AND r0, r0, #0xAB
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b000, 0, 0xAB));
        assertEquals(0x0000_00AB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToAlternatingHalfwordsWhenSelectorIsOne() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b001, 0, 0xAB));
        assertEquals(0x00AB_00AB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToOddHalfwordsWhenSelectorIsTwo() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b010, 0, 0xAB));
        assertEquals(0xAB00_AB00, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToAllFourBytesWhenSelectorIsThree() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b011, 0, 0xAB));
        assertEquals(0xABAB_ABAB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmRotatesAndProducesCarryOutWhenImm3Top2BitsAreNonzero() {
        // i=0, imm3=100, imm8=0x01 -> imm12<11:10> = 0,1 (rotaciona): unrotated=0x81,
        // rotateAmount=8 -> 0x81000000 (bit31=1 -> carry esperado = 1).
        ArmCore core = newCore();
        core.setRegister(1, 0xFFFF_FFFF); // ANDS preserva só os bits do imediato
        run(core, modifiedImmediateHi(0, OP4_AND, true, 1) /* ANDS r0, r1, #imm */,
                dataProcessingLo(0b100, 0, 0x01));
        assertEquals(0x8100_0000, core.register(0));
        assertTrue(core.cpsr().carry(), "carry-out deve refletir o bit 31 do imediato rotacionado");
        assertTrue(core.cpsr().negative());
    }

    // ── MOVW / MOVT ──────────────────────────────────────────────────────────────────────

    @Test
    void movwThenMovtBuildsFullThirtyTwoBitConstant() {
        ArmCore core = newCore();
        run(core, moveWideHi(0, PLAIN_OP_MOVW, 1), dataProcessingLo(0b010, 0, 0x34)); // MOVW r0, #0x1234
        assertEquals(0x0000_1234, core.register(0));
        run(core, moveWideHi(0, PLAIN_OP_MOVT, 5), dataProcessingLo(0b110, 0, 0x78)); // MOVT r0, #0x5678
        assertEquals(0x5678_1234, core.register(0));
    }

    @Test
    void movtPreservesLowHalfOfExistingRegisterValue() {
        ArmCore core = newCore();
        core.setRegister(0, 0xFFFF_CAFE);
        run(core, moveWideHi(0, PLAIN_OP_MOVT, 0), dataProcessingLo(0b000, 0, 0x00)); // MOVT r0, #0
        assertEquals(0x0000_CAFE, core.register(0));
    }

    // ── ADD/SUB (SP/PC) e ADR ────────────────────────────────────────────────────────────

    @Test
    void addSpPlusImmediatePlainBinaryFormComputesStackRelativeAddress() {
        ArmCore core = newCore();
        core.setRegister(13, 0x1000); // SP
        run(core, plainBinaryHi(0, PLAIN_OP_ADD, 13), dataProcessingLo(0b000, 2, 0x20)); // ADDW r2, SP, #0x20
        assertEquals(0x1020, core.register(2));
    }

    @Test
    void addPcPlusImmediateIsAdrWithFourByteAlignedPc() {
        ArmCore core = newCore();
        int base = core.programCounter();
        run(core, plainBinaryHi(0, PLAIN_OP_ADD, 15), dataProcessingLo(0b000, 3, 0x10)); // ADR r3, +0x10
        int expected = ((base + 4) & ~3) + 0x10;
        assertEquals(expected, core.register(3));
    }

    @Test
    void subPcMinusImmediateIsAdrNegative() {
        ArmCore core = newCore();
        int base = core.programCounter();
        run(core, plainBinaryHi(0, PLAIN_OP_SUB, 15), dataProcessingLo(0b000, 3, 0x10)); // ADR r3, -0x10
        int expected = ((base + 4) & ~3) - 0x10;
        assertEquals(expected, core.register(3));
    }

    // ── Forma registrador com shift imediato ────────────────────────────────────────────

    @Test
    void registerFormWithLslShiftMatchesArmClassicEquivalent() {
        // Thumb-2: ADD r2, r0, r1, LSL #4
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(0, 0x1000);
        thumb2Core.setRegister(1, 0x0001);
        run(thumb2Core, registerFormHi(OP4_ADD, true, 0), registerFormLo(0b001, 2, 0b00, SHIFT_LSL, 1));

        // ARM classico: ADDS r2, r0, r1, LSL #4 (E0902201)
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(0, 0x1000);
        armCore.setRegister(1, 0x0001);
        armCore.memory().write32(0, 0xE090_2201);
        armCore.step();

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(armCore.cpsr().negative(), thumb2Core.cpsr().negative());
        assertEquals(armCore.cpsr().zero(), thumb2Core.cpsr().zero());
        assertEquals(armCore.cpsr().carry(), thumb2Core.cpsr().carry());
        assertEquals(armCore.cpsr().overflow(), thumb2Core.cpsr().overflow());
    }

    @Test
    void registerFormRrxMatchesArmClassicEquivalent() {
        // Thumb-2: ANDS r2, r0, r1, RRX
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(0, 0x8000_0001);
        thumb2Core.setRegister(1, 0x0000_0003);
        thumb2Core.cpsr().setNzcv(false, false, true, false); // carry-in = 1
        run(thumb2Core, registerFormHi(OP4_AND, true, 0), registerFormLo(0b000, 2, 0b00, SHIFT_ROR, 1));

        // ARM classico: ANDS r2, r0, r1, RRX (E0102061)
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(0, 0x8000_0001);
        armCore.setRegister(1, 0x0000_0003);
        armCore.cpsr().setNzcv(false, false, true, false);
        armCore.memory().write32(0, 0xE010_2061);
        armCore.step();

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(armCore.cpsr().carry(), thumb2Core.cpsr().carry());
    }

    // ── PER_OP fallback: sem divergência do interpretador (mesmo harness de B1.2-B1.5) ─────

    @Test
    void dataProcessingBlockFallsBackToPerOpAndMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, true, 0));  // ANDS r0, r0, #0xFF
        memory.put16(2, dataProcessingLo(0b000, 0, 0xFF));
        memory.put16(4, modifiedImmediateHi(0, OP4_ORN, false, 0)); // ORN r1, r0, #0xFF (B2.2:
        memory.put16(6, dataProcessingLo(0b000, 1, 0xFF));          // opcode novo, sem emissão nativa ainda)
        memory.put16(8, registerFormHi(OP4_ORR, false, 0));         // ORR r2, r0, r1, LSL #2
        memory.put16(10, registerFormLo(0b000, 2, 0b10, SHIFT_LSL, 1));

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 3);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter thumb2Reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        assertTrue(asmEmitter.perOpFallbackOpCount() >= 0); // sanity: contador existe antes do run
        harness.assertEquivalent(thumb2Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x0000_FF00)));
        assertTrue(asmEmitter.perOpFallbackOpCount() > 0,
                "AND/MOVW/ORR de Thumb-2 (B2.2) ainda não têm emissão ASM nativa");
    }

    // ── Gating G2: sem THUMB2, o candidato cai no caminho legado (comportamento inalterado) ─

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b000, 0, 0xAB));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.AND, instruction.kind());
    }
}
