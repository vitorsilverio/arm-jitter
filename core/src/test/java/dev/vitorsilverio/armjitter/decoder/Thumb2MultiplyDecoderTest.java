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

/// B2.7 (PR2) — `Thumb2MultiplyDecoder`, o espaço `0xFB` inteiro: `MUL.W`/`MLA.W`,
/// `SMULL`/`UMULL`/`SMLAL`/`UMLAL`, `UMAAL`, `USAD8`/`USADA8` e as multiplicações DSP ARMv5TE
/// (`SMLA<x><y>`/`SMUL<x><y>`/`SMLAW<y>`/`SMULW<y>`/`SMLAL<x><y>`). Layout de bits confirmado
/// contra o QEMU `target/arm/tcg/t32.decode` (ver `Thumb2MultiplyDecoder` para a citação completa).
class Thumb2MultiplyDecoderTest {
    private static final ArmArchitecture THUMB2_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ArmV7TestThumb2Multiply", ArmFeature.THUMB2);
    private static final ArmArchitecture THUMB2_ARCH = THUMB2_FEATURES
            .withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(THUMB2_FEATURES)));

    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    // ── Encoders Thumb-2 (ver Thumb2MultiplyDecoder para o layout de bits) ──────────────────

    private static int hi(int family, int rn) {
        return 0xFB00 | (family << 4) | rn;
    }

    private static int lo(int p15_12, int p11_8, int op, int rm) {
        return (p15_12 << 12) | (p11_8 << 8) | (op << 4) | rm;
    }

    // ── Encoders ARM clássico (mesmos layouts de ArmDecoder) — sempre condição AL (0xE) ─────

    private static final int COND_AL = 0xE000_0000;

    private static int armMul(boolean accumulate, int rd, int ra, int rs, int rm) {
        return COND_AL | 0x0000_0090 | (accumulate ? 1 << 21 : 0) | (rd << 16) | (ra << 12) | (rs << 8) | rm;
    }

    private static int armLongMultiply(boolean signed, boolean accumulate, int rdHigh, int rdLow, int rs, int rm) {
        return COND_AL | 0x0080_0090 | (signed ? 1 << 22 : 0) | (accumulate ? 1 << 21 : 0)
                | (rdHigh << 16) | (rdLow << 12) | (rs << 8) | rm;
    }

    private static int armUmaal(int rdHigh, int rdLow, int rs, int rm) {
        return COND_AL | 0x0040_0090 | (rdHigh << 16) | (rdLow << 12) | (rs << 8) | rm;
    }

    private static int armUsada8(int rd, int ra, int rs, int rm) {
        return COND_AL | 0x0780_0010 | (rd << 16) | (ra << 12) | (rs << 8) | rm;
    }

    private static int armDspMultiply(int op2, int rd, int ra, int rs, int x, int y, int rm) {
        return COND_AL | 0x0100_0080 | (op2 << 21) | (rd << 16) | (ra << 12) | (rs << 8) | (y << 6) | (x << 5) | rm;
    }

    // ── B9.7: SMLAD{X}/SMLSD{X}/SMLALD{X}/SMLSLD{X} — mesmos bits de `ArmDecoder` (B9.1) ────

    private static int armDspDualMultiply(boolean subtract, boolean exchange, boolean longForm,
            int rd, int ra, int rm, int rn) {
        return COND_AL | 0x0700_0010 | (longForm ? 1 << 22 : 0) | (subtract ? 1 << 6 : 0)
                | (exchange ? 1 << 5 : 0) | (rd << 16) | (ra << 12) | (rm << 8) | rn;
    }

    private static int armDspTopWordMultiply(boolean subtract, boolean round, int rd, int ra, int rn, int rm) {
        return COND_AL | 0x0750_0010 | (subtract ? 1 << 6 : 0) | (round ? 1 << 5 : 0)
                | (rd << 16) | (ra << 12) | (rm << 8) | rn;
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

    // ── MUL.W/MLA.W: ida-e-volta contra o ARM clássico (Ra=1111 -> MUL, senão MLA) ──────────

    @Test
    void mulWMatchesArmClassicMulWithoutAccumulator() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 6);
        thumb2Core.setRegister(3, 7);
        runThumb2(thumb2Core, hi(0, 1), lo(0xF, 2, 0, 3)); // MUL.W r2,r1,r3 (Ra=1111)

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 6);
        armCore.setRegister(3, 7);
        runArm(armCore, armMul(false, 2, 0, 3, 1));

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(42, thumb2Core.register(2));
    }

    @Test
    void mlaWMatchesArmClassicMlaWithAccumulator() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 6);
        thumb2Core.setRegister(3, 7);
        thumb2Core.setRegister(4, 100); // acumulador (Ra=r4)
        runThumb2(thumb2Core, hi(0, 1), lo(4, 2, 0, 3)); // MLA.W r2,r1,r3,r4

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 6);
        armCore.setRegister(3, 7);
        armCore.setRegister(4, 100);
        runArm(armCore, armMul(true, 2, 4, 3, 1));

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(142, thumb2Core.register(2));
    }

    @Test
    void mulWRejectsStackPointerAndProgramCounterOperands() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 13)); // Rn=SP
        memory.put16(2, lo(0xF, 2, 0, 3));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── SMULL/UMULL/SMLAL/UMLAL: ida-e-volta contra o ARM clássico ──────────────────────────

    @Test
    void longMultiplyFormsMatchArmClassic() {
        int[] families = {0x8, 0xA, 0xC, 0xE}; // SMULL, UMULL, SMLAL, UMLAL
        boolean[] signedFlags = {true, false, true, false};
        boolean[] accumulateFlags = {false, false, true, true};
        for (int i = 0; i < families.length; i++) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0001_0000);
            thumb2Core.setRegister(3, 0xFFFF_FFF0);
            thumb2Core.setRegister(2, 5);
            thumb2Core.setRegister(4, 0);
            runThumb2(thumb2Core, hi(families[i], 1), lo(2, 4, 0, 3)); // <op> r2,r4,r1,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0001_0000);
            armCore.setRegister(3, 0xFFFF_FFF0);
            armCore.setRegister(2, 5);
            armCore.setRegister(4, 0);
            runArm(armCore, armLongMultiply(signedFlags[i], accumulateFlags[i], 4, 2, 3, 1));

            assertEquals(armCore.register(2), thumb2Core.register(2), "family=" + families[i]);
            assertEquals(armCore.register(4), thumb2Core.register(4), "family=" + families[i]);
        }
    }

    @Test
    void longMultiplyRejectsStackPointerAndProgramCounterOperands() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x8, 15)); // Rn=PC
        memory.put16(2, lo(2, 4, 0, 3));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── UMAAL: ida-e-volta contra o ARM clássico ─────────────────────────────────────────────

    @Test
    void umaalMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 6);
        thumb2Core.setRegister(3, 7);
        thumb2Core.setRegister(2, 100);
        thumb2Core.setRegister(4, 9);
        runThumb2(thumb2Core, hi(0xE, 1), lo(2, 4, 0x6, 3)); // UMAAL r2,r4,r1,r3

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 6);
        armCore.setRegister(3, 7);
        armCore.setRegister(2, 100);
        armCore.setRegister(4, 9);
        runArm(armCore, armUmaal(4, 2, 3, 1));

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(armCore.register(4), thumb2Core.register(4));
    }

    @Test
    void umaalIsUndefinedWithoutUmaalFeature() {
        ArmArchitecture noUmaal = ArmArchitecture.of("NoUmaal", ArmFeature.THUMB2);
        ArmArchitecture arch = noUmaal.withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(noUmaal)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0xE, 1));
        memory.put16(2, lo(2, 4, 0x6, 3));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── USAD8/USADA8: ida-e-volta contra o ARM clássico ─────────────────────────────────────

    @Test
    void usad8WithoutAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x1020_3040);
        thumb2Core.setRegister(3, 0x0510_2030);
        runThumb2(thumb2Core, hi(0x7, 1), lo(0xF, 2, 0, 3)); // USAD8 r2,r1,r3

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x1020_3040);
        armCore.setRegister(3, 0x0510_2030);
        runArm(armCore, armUsada8(2, 0xF, 3, 1));

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    @Test
    void usada8WithAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x1020_3040);
        thumb2Core.setRegister(3, 0x0510_2030);
        thumb2Core.setRegister(5, 1000); // acumulador (Ra=r5)
        runThumb2(thumb2Core, hi(0x7, 1), lo(5, 2, 0, 3)); // USADA8 r2,r1,r3,r5

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x1020_3040);
        armCore.setRegister(3, 0x0510_2030);
        armCore.setRegister(5, 1000);
        runArm(armCore, armUsada8(2, 5, 3, 1));

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertTrue(thumb2Core.register(2) > 1000);
    }

    @Test
    void usad8IsUndefinedWithoutPackSaturateFeature() {
        ArmArchitecture noPackSaturate = ArmArchitecture.of("NoPackSaturate", ArmFeature.THUMB2);
        ArmArchitecture arch = noPackSaturate.withThumb32DecoderExtensions(
                List.of(new Thumb2MultiplyDecoder(noPackSaturate)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x7, 1));
        memory.put16(2, lo(0xF, 2, 0, 3));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── SMLA<x><y>/SMUL<x><y> (16x16): ida-e-volta contra o ARM clássico ────────────────────

    @Test
    void smlaXySixteenBySixteenMatchesArmClassicForAllFourHalfCombinations() {
        // op nibble = 0b00NM: BB=0,BT=1,TB=2,TT=3 -> x=N=(op>>1)&1, y=M=op&1.
        int[] opNibbles = {0, 1, 2, 3};
        for (int op : opNibbles) {
            int x = (op >>> 1) & 1;
            int y = op & 1;
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0000_7FFF); // registrador "x" (Rn Thumb-2)
            thumb2Core.setRegister(3, 0xFFFF_0002); // registrador "y" (Rm Thumb-2)
            thumb2Core.setRegister(4, 1000);        // acumulador (Ra=r4)
            runThumb2(thumb2Core, hi(0x1, 1), lo(4, 2, op, 3)); // SMLA<x><y> r2,r1,r3,r4

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0000_7FFF);
            armCore.setRegister(3, 0xFFFF_0002);
            armCore.setRegister(4, 1000);
            runArm(armCore, armDspMultiply(0, 2, 4, 3, x, y, 1));

            assertEquals(armCore.register(2), thumb2Core.register(2), "op=" + op);
        }
    }

    @Test
    void smulXySixteenBySixteenWithoutAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0000_7FFF);
        thumb2Core.setRegister(3, 0xFFFF_0002);
        runThumb2(thumb2Core, hi(0x1, 1), lo(0xF, 2, 0, 3)); // SMULBB r2,r1,r3 (Ra=1111)

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0000_7FFF);
        armCore.setRegister(3, 0xFFFF_0002);
        runArm(armCore, armDspMultiply(3, 2, 0, 3, 0, 0, 1)); // op2=3: SMULxy, sem acumulador

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    // ── SMLAW<y>/SMULW<y>: ida-e-volta contra o ARM clássico ────────────────────────────────

    @Test
    void smlawYWithAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0002_0000); // Rn: multiplicando de 32 bits inteiro
        thumb2Core.setRegister(3, 0xFFFF_0003); // Rm: metade selecionada por M
        thumb2Core.setRegister(4, 1000);        // acumulador (Ra=r4)
        runThumb2(thumb2Core, hi(0x3, 1), lo(4, 2, 1, 3)); // SMLAWT r2,r1,r3,r4 (M=1)

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0002_0000);
        armCore.setRegister(3, 0xFFFF_0003);
        armCore.setRegister(4, 1000);
        runArm(armCore, armDspMultiply(1, 2, 4, 3, 0, 1, 1)); // op2=1,x=0 (acumula),y=1

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    @Test
    void smulwYWithoutAccumulatorMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0002_0000);
        thumb2Core.setRegister(3, 0xFFFF_0003);
        runThumb2(thumb2Core, hi(0x3, 1), lo(0xF, 2, 0, 3)); // SMULWB r2,r1,r3 (Ra=1111, M=0)

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0002_0000);
        armCore.setRegister(3, 0xFFFF_0003);
        runArm(armCore, armDspMultiply(1, 2, 0, 3, 1, 0, 1)); // op2=1,x=1 (sem acumular),y=0

        assertEquals(armCore.register(2), thumb2Core.register(2));
    }

    // ── SMLAL<x><y> (acumulador de 64 bits): ida-e-volta contra o ARM clássico ──────────────

    @Test
    void smlalXyMatchesArmClassicForAllFourHalfCombinations() {
        // op nibble = 0b10NM: BB=8,BT=9,TB=10,TT=11 -> x=N=(op>>1)&1, y=M=op&1.
        int[] opNibbles = {0x8, 0x9, 0xA, 0xB};
        for (int op : opNibbles) {
            int x = (op >>> 1) & 1;
            int y = op & 1;
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0000_7FFF);
            thumb2Core.setRegister(3, 0xFFFF_0002);
            thumb2Core.setRegister(2, 5);  // RdLo
            thumb2Core.setRegister(4, 0);  // RdHi
            runThumb2(thumb2Core, hi(0xC, 1), lo(2, 4, op, 3)); // SMLAL<x><y> r2,r4,r1,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0000_7FFF);
            armCore.setRegister(3, 0xFFFF_0002);
            armCore.setRegister(2, 5);
            armCore.setRegister(4, 0);
            runArm(armCore, armDspMultiply(2, 4, 2, 3, x, y, 1)); // op2=2: rd=RdHi, ra=RdLo

            assertEquals(armCore.register(2), thumb2Core.register(2), "op=" + op);
            assertEquals(armCore.register(4), thumb2Core.register(4), "op=" + op);
        }
    }

    // ── B9.7: SMLAD{X}/SMLSD{X}/SMMLA{R}/SMMLS{R}/SMLALD{X}/SMLSLD{X} ───────────────────────

    @Test
    void smladAndSmladxMatchArmClassicForBothExchangeValues() {
        int[] ops = {0, 1}; // 0=SMLAD, 1=SMLADX (exchange)
        for (int op : ops) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0002_0003);
            thumb2Core.setRegister(2, 0x0005_0007);
            thumb2Core.setRegister(3, 100); // Ra
            runThumb2(thumb2Core, hi(0x2, 1), lo(3, 0, op, 2)); // SMLAD{X} r0,r1,r2,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0002_0003);
            armCore.setRegister(2, 0x0005_0007);
            armCore.setRegister(3, 100);
            runArm(armCore, armDspDualMultiply(false, op == 1, false, 0, 3, 2, 1));

            assertEquals(armCore.register(0), thumb2Core.register(0), "op=" + op);
        }
    }

    @Test
    void smusadAliasWithoutAccumulatorMatchesArmClassic() {
        // Ra=1111: alias sem acumulador (SMUAD), mesmo encoding.
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0002_0003);
        thumb2Core.setRegister(2, 0x0005_0007);
        runThumb2(thumb2Core, hi(0x2, 1), lo(0xF, 0, 0, 2)); // SMUAD r0,r1,r2

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0002_0003);
        armCore.setRegister(2, 0x0005_0007);
        runArm(armCore, armDspDualMultiply(false, false, false, 0, 0xF, 2, 1));

        assertEquals(armCore.register(0), thumb2Core.register(0));
    }

    @Test
    void smlsdMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0002_0003);
        thumb2Core.setRegister(2, 0x0005_0007);
        thumb2Core.setRegister(3, 100);
        runThumb2(thumb2Core, hi(0x4, 1), lo(3, 0, 0, 2)); // SMLSD r0,r1,r2,r3

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0002_0003);
        armCore.setRegister(2, 0x0005_0007);
        armCore.setRegister(3, 100);
        runArm(armCore, armDspDualMultiply(true, false, false, 0, 3, 2, 1));

        assertEquals(armCore.register(0), thumb2Core.register(0));
    }

    @Test
    void smmlaAndSmmlarMatchArmClassicForBothRoundValues() {
        int[] ops = {0, 1}; // 0=SMMLA, 1=SMMLAR (round)
        for (int op : ops) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0002_0003);
            thumb2Core.setRegister(2, 0x0005_0007);
            thumb2Core.setRegister(3, 100);
            runThumb2(thumb2Core, hi(0x5, 1), lo(3, 0, op, 2)); // SMMLA{R} r0,r1,r2,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0002_0003);
            armCore.setRegister(2, 0x0005_0007);
            armCore.setRegister(3, 100);
            runArm(armCore, armDspTopWordMultiply(false, op == 1, 0, 3, 1, 2));

            assertEquals(armCore.register(0), thumb2Core.register(0), "op=" + op);
        }
    }

    @Test
    void smmlsMatchesArmClassic() {
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0x0002_0003);
        thumb2Core.setRegister(2, 0x0005_0007);
        thumb2Core.setRegister(3, 100);
        runThumb2(thumb2Core, hi(0x6, 1), lo(3, 0, 0, 2)); // SMMLS r0,r1,r2,r3

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x0002_0003);
        armCore.setRegister(2, 0x0005_0007);
        armCore.setRegister(3, 100);
        runArm(armCore, armDspTopWordMultiply(true, false, 0, 3, 1, 2));

        assertEquals(armCore.register(0), thumb2Core.register(0));
    }

    @Test
    void smlaldAndSmlaldxMatchArmClassicAndFixTheSilentMisdecodeBug() {
        // Achado real da B9.7 (G8): antes desta task, `op=0xC`/`0xD` (SMLALD/SMLALDX) caía em
        // `decodeDspMultiplySixtyFourBitAccumulate` (SMLAL<x><y>, x=y=0 — IDÊNTICO a SMLALBB,
        // op=0x8) porque só `(op & 0x8) != 0` era checado. Este teste prova que o resultado agora
        // bate com a semântica REAL de SMLALD (soma de DOIS produtos de 16 bits, não um só).
        int[] ops = {0xC, 0xD}; // SMLALD, SMLALDX (exchange)
        for (int op : ops) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0002_0003);
            thumb2Core.setRegister(3, 0x0005_0007);
            thumb2Core.setRegister(2, 5);  // RdLo
            thumb2Core.setRegister(4, 0);  // RdHi
            runThumb2(thumb2Core, hi(0xC, 1), lo(2, 4, op, 3)); // SMLALD{X} r2,r4,r1,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0002_0003);
            armCore.setRegister(3, 0x0005_0007);
            armCore.setRegister(2, 5);
            armCore.setRegister(4, 0);
            runArm(armCore, armDspDualMultiply(false, op == 0xD, true, 4, 2, 3, 1));

            assertEquals(armCore.register(2), thumb2Core.register(2), "op=" + op);
            assertEquals(armCore.register(4), thumb2Core.register(4), "op=" + op);
            // O bug antigo produzia o MESMO resultado de SMLALBB (x=0,y=0) — provamos que o
            // resultado NÃO é isso, comparando com a computação halfword-halfword ingênua.
            ArmCore halfwordBugCore = newArmCore();
            halfwordBugCore.setRegister(1, 0x0002_0003);
            halfwordBugCore.setRegister(3, 0x0005_0007);
            halfwordBugCore.setRegister(2, 5);
            halfwordBugCore.setRegister(4, 0);
            runArm(halfwordBugCore, armDspMultiply(2, 4, 2, 3, 0, 0, 1)); // SMLALBB r2,r4,r1,r3
            assertNotEquals(halfwordBugCore.register(2), thumb2Core.register(2), "op=" + op);
        }
    }

    @Test
    void smlsldMatchesArmClassic() {
        int[] ops = {0xC, 0xD}; // SMLSLD, SMLSLDX (exchange)
        for (int op : ops) {
            ArmCore thumb2Core = newThumb2Core();
            thumb2Core.setRegister(1, 0x0002_0003);
            thumb2Core.setRegister(3, 0x0005_0007);
            thumb2Core.setRegister(2, 5);
            thumb2Core.setRegister(4, 0);
            runThumb2(thumb2Core, hi(0xD, 1), lo(2, 4, op, 3)); // SMLSLD{X} r2,r4,r1,r3

            ArmCore armCore = newArmCore();
            armCore.setRegister(1, 0x0002_0003);
            armCore.setRegister(3, 0x0005_0007);
            armCore.setRegister(2, 5);
            armCore.setRegister(4, 0);
            runArm(armCore, armDspDualMultiply(true, op == 0xD, true, 4, 2, 3, 1));

            assertEquals(armCore.register(2), thumb2Core.register(2), "op=" + op);
            assertEquals(armCore.register(4), thumb2Core.register(4), "op=" + op);
        }
    }

    @Test
    void dspMultiplyFamiliesAreUndefinedWithoutDspMultiplyFeature() {
        ArmArchitecture noDsp = ArmArchitecture.of("NoDsp", ArmFeature.THUMB2);
        ArmArchitecture arch = noDsp.withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(noDsp)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x1, 1)); // SMLABB
        memory.put16(2, lo(4, 2, 0, 3));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── Formas reservadas dentro do prefixo 0xFB (MLS/SMLAD/SDIV, fora de escopo — ver B3.2) ─

    @Test
    void reservedEncodingsWithinPrefixAreUndefinedNotSilentlyDecoded() {
        // MLS: family=0000, op=0001 — fora de escopo (B3.2).
        TestAddressSpace mlsMemory = new TestAddressSpace(16);
        mlsMemory.put16(0, hi(0x0, 1));
        mlsMemory.put16(2, lo(4, 2, 1, 3));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ThumbDecoder(THUMB2_ARCH).decode(mlsMemory, 0).kind());

        // SMLAD (family=0010, op=0000): implementado pela B9.7 — não é mais reservado, ver os
        // testes `smlad*`/`smlsd*`/`smmla*`/`smmls*`/`smlald*`/`smlsld*` abaixo.

        // SDIV: family=1001 — fora de escopo (B3.2).
        TestAddressSpace sdivMemory = new TestAddressSpace(16);
        sdivMemory.put16(0, hi(0x9, 1));
        sdivMemory.put16(2, lo(0xF, 2, 0xF, 3));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ThumbDecoder(THUMB2_ARCH).decode(sdivMemory, 0).kind());
    }

    // ── G2: sem THUMB2, o espaço 0xFB nunca chega a esta extensão ───────────────────────────

    @Test
    void presetsWithoutThumb2NeverRouteToThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 1)); // seria "MUL.W r2,r1,r3" sob THUMB2
        memory.put16(2, lo(0xF, 2, 0, 3));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.MUL, instruction.kind());
    }

    // ── Equivalência interpretado × ASM nativo: perOpFallbackOpCount()==0 esperado ──────────

    @Test
    void mixedBlockOfNewEncodingsHasZeroPerOpFallback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int address = 0;
        memory.put16(address, hi(0, 1)); address += 2;
        memory.put16(address, lo(0xF, 2, 0, 3)); address += 2;   // MUL.W r2,r1,r3
        memory.put16(address, hi(0xC, 1)); address += 2;
        memory.put16(address, lo(4, 5, 0, 3)); address += 2;     // SMLAL r4,r5,r1,r3
        memory.put16(address, hi(0xE, 1)); address += 2;
        memory.put16(address, lo(6, 7, 0x6, 3)); address += 2;   // UMAAL r6,r7,r1,r3
        memory.put16(address, hi(0x7, 1)); address += 2;
        memory.put16(address, lo(0xF, 0, 0, 3)); address += 2;   // USAD8 r0,r1,r3
        memory.put16(address, hi(0x1, 1)); address += 2;
        memory.put16(address, lo(4, 2, 0, 3)); address += 2;     // SMLABB r2,r1,r3,r4
        memory.put16(address, hi(0x3, 1)); address += 2;
        memory.put16(address, lo(4, 2, 0, 3)); address += 2;     // SMLAWB r2,r1,r3,r4
        memory.put16(address, hi(0xC, 1)); address += 2;
        memory.put16(address, lo(2, 4, 0x8, 3)); address += 2;   // SMLALBB r2,r4,r1,r3
        int instructionCount = address / 2 / 2;

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, instructionCount);

        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        assertTrue(perOpEmitter.isNativeSupported(block));
        harness.assertEquivalent(reference, perOpEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.setRegister(1, 0x0000_7FFF);
            core.setRegister(3, 0xFFFF_0002);
            core.setRegister(4, 5);
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
