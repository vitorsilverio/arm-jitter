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

/// B2.7 (PR1) — `Thumb2RegisterDataProcessingDecoder`, o espaço `0xFA` inteiro: shift por
/// registrador (`LSL.W`/`LSR.W`/`ASR.W`/`ROR.W`), extensão com rotação/acumulador (`SXTAH`.../
/// `UXTAB16`...), `REV`/`REV16`/`REVSH`/`CLZ`/`SEL`, saturação ARMv5TE (`QADD`/`QSUB`/`QDADD`/
/// `QDSUB`) e as 36 formas de aritmética paralela ARMv6. Layout de bits confirmado contra o QEMU
/// `target/arm/tcg/t32.decode` (ver `Thumb2RegisterDataProcessingDecoder` para a citação completa).
class Thumb2RegisterDataProcessingDecoderTest {
    private static final ArmArchitecture THUMB2_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-RegisterDataProcessing", ArmFeature.THUMB2,
            ArmFeature.BIT_REVERSE); // B3.2: RBIT
    // SSAT/USAT/SSAT16/USAT16 vivem em Thumb2DataProcessingDecoder (top5=0b11110), não em
    // Thumb2RegisterDataProcessingDecoder (0xFA) — as duas extensões plugadas juntas, mesmo
    // padrão do preset público ARMV6K_THUMB2, para que os testes de SSAT/USAT e o bloco misto
    // de perOpFallback (que usa os dois espaços) funcionem contra esta arquitetura de teste.
    private static final ArmArchitecture THUMB2_ARCH = THUMB2_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new Thumb2DataProcessingDecoder(THUMB2_FEATURES),
                    new Thumb2RegisterDataProcessingDecoder(THUMB2_FEATURES)));

    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    // ── Encoders Thumb-2 (ver Thumb2RegisterDataProcessingDecoder para o layout de bits) ────

    private static int shiftRegHi(int shty, boolean s, int rm) {
        return 0xFA00 | (shty << 5) | (s ? 1 << 4 : 0) | rm;
    }

    private static int shiftRegLo(int rd, int rs) {
        return 0xF000 | (rd << 8) | rs;
    }

    private static int extendHi(int op4, int rn) {
        return 0xFA00 | (op4 << 4) | rn;
    }

    private static int extendLo(int rd, int rot, int rm) {
        return 0xF000 | (rd << 8) | 0x80 | (rot << 4) | rm;
    }

    private static int twoSourceHi(int family, int rn) {
        return 0xFA00 | (family << 4) | rn;
    }

    private static int twoSourceLo(int rd, int op, int rm) {
        return 0xF000 | (rd << 8) | (op << 4) | rm;
    }

    private static int satHi(boolean unsigned, boolean sh, int rn) {
        return 0xF300 | (unsigned ? 0x80 : 0) | (sh ? 0x20 : 0) | rn;
    }

    private static int satLo(int imm3, int rd, int imm2, int satImm) {
        return (imm3 << 12) | (rd << 8) | (imm2 << 6) | satImm;
    }

    // ── Encoders ARM clássico (mesmos layouts de ArmDecoder) — sempre condição AL (0xE) ─────

    private static final int COND_AL = 0xE000_0000;

    private static int armMovRegisterShiftByRegister(boolean s, int rd, int rs, int shty, int rm) {
        return COND_AL | 0x01A0_0000 | (s ? 1 << 20 : 0) | (rd << 12) | (rs << 8) | (shty << 5) | (1 << 4) | rm;
    }

    private static int armExtend(boolean unsigned, int field, int rn, int rd, int rot, int rm) {
        return COND_AL | 0x0680_0070 | (unsigned ? 1 << 22 : 0) | (field << 20) | (rn << 16) | (rd << 12)
                | (rot << 10) | rm;
    }

    private static int armRev(int rd, int rm) {
        return COND_AL | 0x06BF_0F30 | (rd << 12) | rm;
    }

    private static int armRev16(int rd, int rm) {
        return COND_AL | 0x06BF_0FB0 | (rd << 12) | rm;
    }

    private static int armRevsh(int rd, int rm) {
        return COND_AL | 0x06FF_0FB0 | (rd << 12) | rm;
    }

    private static int armRbit(int rd, int rm) {
        return COND_AL | 0x06FF_0F30 | (rd << 12) | rm;
    }

    private static int armClz(int rd, int rm) {
        return COND_AL | 0x016F_0F10 | (rd << 12) | rm;
    }

    private static int armSel(int rn, int rd, int rm) {
        return COND_AL | 0x0680_0FB0 | (rn << 16) | (rd << 12) | rm;
    }

    private static int armSaturating(int op, int rn, int rd, int rm) {
        return COND_AL | 0x0100_0050 | (op << 21) | (rn << 16) | (rd << 12) | rm;
    }

    private static int armParallelAlu(int variantBits, int opBits, int rn, int rd, int rm) {
        return COND_AL | 0x0600_0F10 | (variantBits << 20) | (opBits << 5) | (rn << 16) | (rd << 12) | rm;
    }

    private static int armSsatUsat(boolean unsigned, int satImm, int rd, int shiftImm, boolean asr, int rm) {
        return COND_AL | 0x06A0_0010 | (unsigned ? 1 << 22 : 0) | (satImm << 16) | (rd << 12) | (shiftImm << 7)
                | (asr ? 1 << 6 : 0) | rm;
    }

    private static int armSsat16Usat16(boolean unsigned, int satImm, int rd, int rm) {
        return COND_AL | 0x06A0_0F30 | (unsigned ? 1 << 22 : 0) | (satImm << 16) | (rd << 12) | rm;
    }

    // ── Runners ──────────────────────────────────────────────────────────────────────────────

    private static ArmCore newThumb2Core() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static ArmCore newArmCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static void runThumb2(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    private static void runArm(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    // ── Shift por registrador: ida-e-volta contra MOV Rd,Rm,<shift> Rs ARM clássico ─────────

    @Test
    void shiftByRegisterMatchesArmClassicMovEquivalentForAllFourShiftTypes() {
        int[] shtyValues = {0, 1, 2, 3}; // LSL, LSR, ASR, ROR
        for (int shty : shtyValues) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(0, 0x8000_0001); // valor a deslocar (rm=r0)
            thumb2Core.setRegister(3, 5);           // quantidade (rs=r3)
            runThumb2(thumb2Core, shiftRegHi(shty, true, 0), shiftRegLo(2, 3)); // <shift>.W r2,r0,r3 (S=1)

            ArmCore armCore = newArmCore();
            armCore.setRegister(0, 0x8000_0001);
            armCore.setRegister(3, 5);
            runArm(armCore, armMovRegisterShiftByRegister(true, 2, 3, shty, 0));

            assertEquals(armCore.register(2), thumb2Core.register(2), "shty=" + shty);
            // Bit T (Thumb) é o único que legitimamente diverge (um core roda ARM, outro THUMB) —
            // mascarado, mesmo padrão de Thumb2MiscDecoderTest#mrsThumb2MatchesArmClassicForCpsr.
            assertEquals(armCore.cpsr().get() & ~dev.vitorsilverio.armjitter.core.CpsrRegister.THUMB_FLAG,
                    thumb2Core.cpsr().get() & ~dev.vitorsilverio.armjitter.core.CpsrRegister.THUMB_FLAG,
                    "shty=" + shty);
        }
    }

    @Test
    void shiftByRegisterRejectsStackPointerAndProgramCounterOperands() {
        TestAddressSpace memory = new TestAddressSpace(16);
        int hi = shiftRegHi(0, false, 13); // Rm=SP
        int lo = shiftRegLo(2, 3);
        memory.put16(0, hi);
        memory.put16(2, lo);
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── Extensão com rotação/acumulador: SXTAH (com acumulador) e UXTB16 (sem acumulador) ───

    @Test
    void sxtahWithAccumulatorAndRotationMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0000_1000); // acumulador (rn)
        thumb2Core.setRegister(0, 0xFFFF_FF00); // valor a estender (rm)
        runThumb2(thumb2Core, extendHi(0, 1), extendLo(2, 1, 0)); // SXTAH r2,r1,r0,ROR#8 (op4=0)

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0000_1000);
        armCore.setRegister(0, 0xFFFF_FF00);
        runArm(armCore, armExtend(false, 0b11, 1, 2, 1, 0)); // SXTAH r2,r1,r0,ROR#8

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    @Test
    void uxtb16WithoutAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x8012_7FED);
        runThumb2(thumb2Core, extendHi(3, 0xF), extendLo(2, 0, 0)); // UXTB16 r2,r0 (op4=3, Rn=1111)

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x8012_7FED);
        runArm(armCore, armExtend(true, 0b00, 0xF, 2, 0, 0)); // UXTB16 r2,r0

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    @Test
    void extendFormIsUndefinedWithoutExtendRotateFeature() {
        ArmArchitecture noExtend = ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoExtend", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2RegisterDataProcessingDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoExtend-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, extendHi(0, 0xF));
        memory.put16(2, extendLo(0, 0, 1));
        DecodedInstruction instruction = new ThumbDecoder(noExtend).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── REV/REV16/REVSH/CLZ/SEL: ida-e-volta contra o ARM clássico ──────────────────────────

    @Test
    void revRev16RevshMatchArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x1234_FF80);
        runThumb2(thumb2Core, twoSourceHi(0x9, 0), twoSourceLo(4, 0x8, 0)); // REV r4,r0
        runThumb2(thumb2Core, twoSourceHi(0x9, 0), twoSourceLo(5, 0x9, 0)); // REV16 r5,r0
        runThumb2(thumb2Core, twoSourceHi(0x9, 0), twoSourceLo(6, 0xB, 0)); // REVSH r6,r0

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x1234_FF80);
        runArm(armCore, armRev(4, 0));
        runArm(armCore, armRev16(5, 0));
        runArm(armCore, armRevsh(6, 0));

        assertEquals(armCore.register(4), thumb2Core.register(4));
        assertEquals(armCore.register(5), thumb2Core.register(5));
        assertEquals(armCore.register(6), thumb2Core.register(6));
    }

    @Test
    void rbitReversesAllThirtyTwoBitsMatchesArmClassic() {
        // RBIT r0,r1 com r1=0x80000001 -> palíndromo de bits -> 0x80000001 (mesmo vetor de B3.1).
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x8000_0001);
        runThumb2(thumb2Core, twoSourceHi(0x9, 1), twoSourceLo(0, 0xA, 1)); // RBIT r0,r1

        // `ArmDecoder` também exige BIT_REVERSE — `ARMV6K` puro não tem essa feature (só chega em
        // ARMV7A, B3.7) — usa uma arquitetura de teste dedicada, não `newArmCore()`.
        ArmArchitecture armClassicArch = ArmArchitecture.extending(ArmArchitecture.ARMV6K,
                "ARM-TestClassic-BitReverse", ArmFeature.BIT_REVERSE);
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), armClassicArch);
        armCore.setRegister(1, 0x8000_0001);
        runArm(armCore, armRbit(0, 1));

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0x8000_0001, thumb2Core.register(0));
    }

    @Test
    void rbitIsUndefinedWithoutBitReverseFeature() {
        // BYTE_REVERSE (REV/REV16/REVSH) presente via ARMV6K, mas BIT_REVERSE (RBIT) ausente —
        // prova que RBIT usa um gate PRÓPRIO, distinto do resto da família REV.
        ArmArchitecture noBitReverse = ArmArchitecture.extending(ArmArchitecture.ARMV6K, "NoBitReverse",
                ArmFeature.THUMB2);
        ArmArchitecture arch = noBitReverse.withThumb32DecoderExtensions(
                List.of(new Thumb2RegisterDataProcessingDecoder(noBitReverse)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0x9, 1));
        memory.put16(2, twoSourceLo(0, 0xA, 1));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void clzMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x0000_00FF);
        runThumb2(thumb2Core, twoSourceHi(0xB, 0), twoSourceLo(1, 0x8, 0)); // CLZ r1,r0

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x0000_00FF);
        runArm(armCore, armClz(1, 0));

        assertEquals(armCore.register(1), thumb2Core.register(1));
        assertEquals(24, thumb2Core.register(1));
    }

    @Test
    void selMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x1111_2222);
        thumb2Core.setRegister(2, 0x3333_4444);
        thumb2Core.cpsr().setGe(0b1010);
        runThumb2(thumb2Core, twoSourceHi(0xA, 1), twoSourceLo(0, 0x8, 2)); // SEL r0,r1,r2

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x1111_2222);
        armCore.setRegister(2, 0x3333_4444);
        armCore.cpsr().setGe(0b1010);
        runArm(armCore, armSel(1, 0, 2));

        assertEquals(armCore.register(0), thumb2Core.register(0));
    }

    @Test
    void reverseFamilyIsUndefinedWithoutByteReverseFeature() {
        ArmArchitecture noReverse = ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoReverse", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2RegisterDataProcessingDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoReverse-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0x9, 0));
        memory.put16(2, twoSourceLo(4, 0x8, 0));
        DecodedInstruction instruction = new ThumbDecoder(noReverse).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void clzIsUndefinedWithoutClzFeature() {
        ArmArchitecture noClz = ArmArchitecture.of("NoClz", ArmFeature.THUMB2);
        ArmArchitecture arch = noClz.withThumb32DecoderExtensions(
                List.of(new Thumb2RegisterDataProcessingDecoder(noClz)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0xB, 0));
        memory.put16(2, twoSourceLo(1, 0x8, 0));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void selIsUndefinedWithoutParallelSimdFeature() {
        ArmArchitecture noParallel = ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoParallel", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2RegisterDataProcessingDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoParallel-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0xA, 1));
        memory.put16(2, twoSourceLo(0, 0x8, 2));
        DecodedInstruction instruction = new ThumbDecoder(noParallel).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── QADD/QSUB/QDADD/QDSUB: ida-e-volta contra o ARM clássico ────────────────────────────

    @Test
    void saturatingQaddFamilyMatchesArmClassic() {
        // nibble[7:4]: 1000=QADD(op0), 1010=QSUB(op1), 1001=QDADD(op2), 1011=QDSUB(op3).
        int[] thumbOpNibble = {0x8, 0xA, 0x9, 0xB};
        int[] armOp = {0, 1, 2, 3};
        for (int i = 0; i < thumbOpNibble.length; i++) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x7FFF_FFF0); // rm
            thumb2Core.setRegister(2, 0x0000_0100); // rn
            runThumb2(thumb2Core, twoSourceHi(0x8, 2), twoSourceLo(0, thumbOpNibble[i], 1));

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x7FFF_FFF0);
            armCore.setRegister(2, 0x0000_0100);
            runArm(armCore, armSaturating(armOp[i], 2, 0, 1));

            assertEquals(armCore.register(0), thumb2Core.register(0), "op=" + armOp[i]);
            assertEquals(armCore.cpsr().saturation(), thumb2Core.cpsr().saturation(), "op=" + armOp[i]);
        }
    }

    @Test
    void saturatingQaddFamilyIsUndefinedWithoutSaturatingFeature() {
        ArmArchitecture noSaturating = ArmArchitecture.of("NoSaturating", ArmFeature.THUMB2);
        ArmArchitecture arch = noSaturating.withThumb32DecoderExtensions(
                List.of(new Thumb2RegisterDataProcessingDecoder(noSaturating)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0x8, 2));
        memory.put16(2, twoSourceLo(0, 0x8, 1));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── As 36 paralelas: amostra representativa cobrindo família e variante ────────────────

    @Test
    void parallelAluSampleMatchesArmClassic() {
        // {family Thumb2, op Thumb2, variantBits ARM, opBits ARM, nome} — cobre plain/saturating/
        // halving e signed/unsigned, e famílias ADD8/SUB16/SAX.
        int[][] cases = {
                {0x8, 0x0, 0b001, 0b100}, // SADD8
                {0x8, 0x5, 0b110, 0b100}, // UQADD8
                {0xD, 0x4, 0b101, 0b011}, // USUB16
                {0xE, 0x2, 0b011, 0b010}, // SHSAX
        };
        for (int[] c : cases) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0102_0304); // rn
            thumb2Core.setRegister(2, 0x0001_0001); // rm
            runThumb2(thumb2Core, twoSourceHi(c[0], 1), twoSourceLo(0, c[1], 2));

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0102_0304);
            armCore.setRegister(2, 0x0001_0001);
            runArm(armCore, armParallelAlu(c[2], c[3], 1, 0, 2));

            assertEquals(armCore.register(0), thumb2Core.register(0),
                    "family=" + c[0] + " op=" + c[1]);
            assertEquals(armCore.cpsr().ge(), thumb2Core.cpsr().ge(),
                    "family=" + c[0] + " op=" + c[1]);
        }
    }

    @Test
    void parallelAluIsUndefinedWithoutParallelSimdFeature() {
        ArmArchitecture noParallel = ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoParallel2", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2RegisterDataProcessingDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoParallel2-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0x8, 1));
        memory.put16(2, twoSourceLo(0, 0x0, 2));
        DecodedInstruction instruction = new ThumbDecoder(noParallel).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── SSAT/USAT/SSAT16/USAT16 (Thumb2DataProcessingDecoder) — ida-e-volta ARM clássico ───

    @Test
    void ssatWithLslShiftMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x0000_1000);
        runThumb2(thumb2Core, satHi(false, false, 0), satLo(0b001, 1, 0b10, 7)); // SSAT r1,#8,r0,LSL#6

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x0000_1000);
        runArm(armCore, armSsatUsat(false, 7, 1, 6, false, 0));

        assertEquals(armCore.register(1), thumb2Core.register(1));
    }

    @Test
    void usatWithAsrShiftMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0xFFFF_0000);
        runThumb2(thumb2Core, satHi(true, true, 0), satLo(0b000, 1, 0b10, 15)); // USAT r1,#15,r0,ASR#2

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0xFFFF_0000);
        runArm(armCore, armSsatUsat(true, 15, 1, 2, true, 0));

        assertEquals(armCore.register(1), thumb2Core.register(1));
    }

    @Test
    void ssat16MatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x7FFF_8000);
        // sh=1, imm3=0, imm2=0 -> SSAT16 (não SSAT ASR#0/#32) por causa do shift totalmente zero.
        runThumb2(thumb2Core, satHi(false, true, 0), satLo(0, 1, 0, 3)); // SSAT16 r1,#4,r0

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x7FFF_8000);
        runArm(armCore, armSsat16Usat16(false, 3, 1, 0));

        assertEquals(armCore.register(1), thumb2Core.register(1));
    }

    @Test
    void usat16MatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(0, 0x0001_FFFF);
        runThumb2(thumb2Core, satHi(true, true, 0), satLo(0, 1, 0, 5)); // USAT16 r1,#5,r0

        ArmCore armCore = newArmCore();
        armCore.setRegister(0, 0x0001_FFFF);
        runArm(armCore, armSsat16Usat16(true, 5, 1, 0));

        assertEquals(armCore.register(1), thumb2Core.register(1));
    }

    @Test
    void ssatFamilyIsUndefinedWithoutPackSaturateFeature() {
        ArmArchitecture noPackSaturate = ArmArchitecture.extending(
                ArmArchitecture.ARMV5TE, "NoPackSaturate", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2DataProcessingDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoPackSaturate-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, satHi(false, false, 0));
        memory.put16(2, satLo(0, 1, 0, 7));
        DecodedInstruction instruction = new ThumbDecoder(noPackSaturate).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── G2: sem THUMB2, o espaço 0xFA nunca chega a esta extensão ───────────────────────────

    @Test
    void presetsWithoutThumb2NeverRouteToThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, twoSourceHi(0x9, 0)); // seria "REV r4,r0" sob THUMB2
        memory.put16(2, twoSourceLo(4, 0x8, 0));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.BYTE_REVERSE, instruction.kind());
    }

    // ── Equivalência interpretado × ASM nativo: perOpFallbackOpCount()==0 esperado ──────────

    @Test
    void mixedBlockOfNewEncodingsHasZeroPerOpFallback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int address = 0;
        memory.put16(address, shiftRegHi(0, false, 0)); address += 2;
        memory.put16(address, shiftRegLo(2, 3)); address += 2;               // LSL.W r2,r0,r3
        memory.put16(address, extendHi(4, 0xF)); address += 2;
        memory.put16(address, extendLo(1, 0, 0)); address += 2;               // SXTAB r1,r0 (op4=4, sem acc)
        memory.put16(address, twoSourceHi(0x9, 0)); address += 2;
        memory.put16(address, twoSourceLo(4, 0x8, 0)); address += 2;          // REV r4,r0
        memory.put16(address, twoSourceHi(0xB, 0)); address += 2;
        memory.put16(address, twoSourceLo(5, 0x8, 0)); address += 2;          // CLZ r5,r0
        memory.put16(address, twoSourceHi(0x8, 2)); address += 2;
        memory.put16(address, twoSourceLo(6, 0x8, 1)); address += 2;          // QADD r6,r1,r2
        memory.put16(address, twoSourceHi(0x8, 1)); address += 2;
        memory.put16(address, twoSourceLo(7, 0x0, 2)); address += 2;          // SADD8 r7,r1,r2
        memory.put16(address, satHi(false, false, 0)); address += 2;
        memory.put16(address, satLo(0b001, 3, 0b01, 7)); address += 2;        // SSAT r3,#8,r0,LSL#6
        int instructionCount = address / 2 / 2;

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, instructionCount);

        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        assertTrue(perOpEmitter.isNativeSupported(block));
        harness.assertEquivalent(reference, perOpEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.setRegister(0, 0x0102_0304);
            core.setRegister(1, 0x0001_0001);
            core.setRegister(2, 5);
        }));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }

    // ── Regressão: BL/BLX de 32 bits (B2.6) intacto com esta extensão plugada ───────────────

    @Test
    void blImmediateStillDecodesAsSingleThirtyTwoBitInstructionWithThisExtensionPlugged() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // prefixo BL, highOffset=0
        memory.put16(2, 0xF800); // sufixo BL, lowOffset=0
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.LONG_BRANCH_32, instruction.kind());
    }
}
