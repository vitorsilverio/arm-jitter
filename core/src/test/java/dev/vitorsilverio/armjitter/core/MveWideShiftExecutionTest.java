package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.16 — MVE "long shift" sobre GPR, fim-a-fim (decode + lift + executor interpretado) sobre o
/// preset real `ARMV8_1M_MVE`. Cobre o que exige execução: par `RdaLo:RdaHi` na ordem certa, `shim=0`
/// => 32, byte baixo de `Rm` COM SINAL, `SQRSHR*` negando a quantidade, `APSR.Q` (não `FPSCR.QC`) só
/// quando satura e sticky, `LSLL_rr`/`ASRL_rr` que NUNCA setam `Q`, corte de 48 bits, condição de
/// `IT`, e a regressão de desambiguação contra `MOVS`/`ORRS` (mesmo espaço de bits).
class MveWideShiftExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int TOP12 = 0b1110_1010_0101 << 20;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int wordImmediate(int op, int rda, int shim) {
        return TOP12 | (rda << 16) | ((shim >>> 2) << 12) | (0xF << 8) | ((shim & 3) << 6) | (op << 4) | 0xF;
    }

    private static int pairImmediate(int op, boolean high, int rdaLo, int rdaHi, int shim) {
        return TOP12 | ((rdaLo >>> 1) << 17) | ((high ? 1 : 0) << 16) | ((shim >>> 2) << 12)
                | ((rdaHi >>> 1) << 9) | (1 << 8) | ((shim & 3) << 6) | (op << 4) | 0xF;
    }

    private static int wordRegister(int op, int rda, int rm) {
        return TOP12 | (rda << 16) | (rm << 12) | (0xF << 8) | (op << 4) | 0xD;
    }

    private static int pairRegister(int op, boolean high, int rdaLo, int rdaHi, int rm) {
        return TOP12 | ((rdaLo >>> 1) << 17) | ((high ? 1 : 0) << 16) | (rm << 12) | ((rdaHi >>> 1) << 9)
                | (1 << 8) | (op << 4) | 0xD;
    }

    private static final int LEFT = 0b00;
    private static final int LOGICAL_RIGHT = 0b01;
    private static final int ARITHMETIC_RIGHT = 0b10;
    private static final int SATURATING = 0b11;

    private static void setPair(ArmCore core, int lo, int hi, long value) {
        core.setRegister(lo, (int) value);
        core.setRegister(hi, (int) (value >>> 32));
    }

    private static long pair(ArmCore core, int lo, int hi) {
        return ((long) core.register(hi) << 32) | (core.register(lo) & 0xFFFF_FFFFL);
    }

    // ── imediato, 64 bits ───────────────────────────────────────────────────────────────────────

    @Test
    void lsllImmediateShiftsThePairAcrossTheWordBoundary() {
        ArmCore core = newCore();
        setPair(core, 2, 5, 0x0000_0000_8000_0001L);
        put32(core, CODE_BASE, pairImmediate(LEFT, false, 2, 5, 4));
        core.step();
        assertEquals(0x0000_0008_0000_0010L, pair(core, 2, 5), "RdaLo em R2 (baixo), RdaHi em R5 (alto)");
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void lsrlAndAsrlImmediateDifferOnTheSignBit() {
        ArmCore logical = newCore();
        setPair(logical, 0, 1, 0x8000_0000_0000_0000L);
        put32(logical, CODE_BASE, pairImmediate(LOGICAL_RIGHT, false, 0, 1, 4));
        logical.step();
        assertEquals(0x0800_0000_0000_0000L, pair(logical, 0, 1));

        ArmCore arithmetic = newCore();
        setPair(arithmetic, 0, 1, 0x8000_0000_0000_0000L);
        put32(arithmetic, CODE_BASE, pairImmediate(ARITHMETIC_RIGHT, false, 0, 1, 4));
        arithmetic.step();
        assertEquals(0xF800_0000_0000_0000L, pair(arithmetic, 0, 1));
    }

    @Test
    void shimZeroShiftsByThirtyTwo() {
        ArmCore core = newCore();
        setPair(core, 2, 3, 0x1122_3344_5566_7788L);
        put32(core, CODE_BASE, pairImmediate(LOGICAL_RIGHT, false, 2, 3, 0));
        core.step();
        assertEquals(0x0000_0000_1122_3344L, pair(core, 2, 3));
    }

    @Test
    void roundingRightShiftsRoundToNearest() {
        ArmCore urshrl = newCore();
        setPair(urshrl, 0, 1, 0b1011); // 11 >> 2 = 2,75 -> 3
        put32(urshrl, CODE_BASE, pairImmediate(LOGICAL_RIGHT, true, 0, 1, 2));
        urshrl.step();
        assertEquals(3L, pair(urshrl, 0, 1));

        ArmCore srshrl = newCore();
        setPair(srshrl, 0, 1, -0b1011L); // -11 >> 2 = -2,75 -> -3
        put32(srshrl, CODE_BASE, pairImmediate(ARITHMETIC_RIGHT, true, 0, 1, 2));
        srshrl.step();
        assertEquals(-3L, pair(srshrl, 0, 1));
        assertFalse(srshrl.cpsr().saturation(), "SRSHRL/URSHRL nunca setam Q");
    }

    @Test
    void sqshllAndUqshllSaturateAndSetApsrQ() {
        ArmCore signed = newCore();
        setPair(signed, 0, 1, Long.MAX_VALUE);
        put32(signed, CODE_BASE, pairImmediate(SATURATING, true, 0, 1, 1));
        signed.step();
        assertEquals(Long.MAX_VALUE, pair(signed, 0, 1));
        assertTrue(signed.cpsr().saturation());
        assertFalse(signed.fpscr().qc(), "APSR.Q, não FPSCR.QC");

        ArmCore negative = newCore();
        setPair(negative, 0, 1, Long.MIN_VALUE);
        put32(negative, CODE_BASE, pairImmediate(SATURATING, true, 0, 1, 1));
        negative.step();
        assertEquals(Long.MIN_VALUE, pair(negative, 0, 1));
        assertTrue(negative.cpsr().saturation());

        ArmCore unsigned = newCore();
        setPair(unsigned, 0, 1, 0x8000_0000_0000_0000L);
        put32(unsigned, CODE_BASE, pairImmediate(LEFT, true, 0, 1, 1));
        unsigned.step();
        assertEquals(-1L, pair(unsigned, 0, 1));
        assertTrue(unsigned.cpsr().saturation());
    }

    @Test
    void nonSaturatingResultLeavesQUntouchedAndQIsSticky() {
        ArmCore core = newCore();
        setPair(core, 0, 1, 5);
        put32(core, CODE_BASE, pairImmediate(SATURATING, true, 0, 1, 1));
        core.step();
        assertEquals(10L, pair(core, 0, 1));
        assertFalse(core.cpsr().saturation(), "não saturou => Q intocado");

        core.cpsr().setSaturation(true);
        put32(core, CODE_BASE + 4, pairImmediate(SATURATING, true, 0, 1, 1));
        core.step();
        assertEquals(20L, pair(core, 0, 1));
        assertTrue(core.cpsr().saturation(), "Q é sticky: uma operação que não satura não o limpa");
    }

    // ── imediato, 32 bits ───────────────────────────────────────────────────────────────────────

    @Test
    void wordImmediateFormsOperateOnASingleRegister() {
        ArmCore uqshl = newCore();
        uqshl.setRegister(3, 0x8000_0000);
        put32(uqshl, CODE_BASE, wordImmediate(LEFT, 3, 1));
        uqshl.step();
        assertEquals(0xFFFF_FFFF, uqshl.register(3));
        assertTrue(uqshl.cpsr().saturation());

        ArmCore sqshl = newCore();
        sqshl.setRegister(3, 0x8000_0000);
        put32(sqshl, CODE_BASE, wordImmediate(SATURATING, 3, 1));
        sqshl.step();
        assertEquals(0x8000_0000, sqshl.register(3), "INT_MIN << 1 satura em INT_MIN");
        assertTrue(sqshl.cpsr().saturation());

        ArmCore urshr = newCore();
        urshr.setRegister(3, 0x8000_0000);
        put32(urshr, CODE_BASE, wordImmediate(LOGICAL_RIGHT, 3, 0)); // shim 0 => 32
        urshr.step();
        assertEquals(1, urshr.register(3), "round(0x80000000 / 2^32) = 1");

        ArmCore srshr = newCore();
        srshr.setRegister(3, 0x8000_0000);
        put32(srshr, CODE_BASE, wordImmediate(ARITHMETIC_RIGHT, 3, 0));
        srshr.step();
        assertEquals(0, srshr.register(3));
        assertFalse(srshr.cpsr().saturation());
    }

    // ── por registrador ─────────────────────────────────────────────────────────────────────────

    @Test
    void registerShiftAmountIsTheSignedLowByteOfRm() {
        // Rm = 0x180: o byte baixo é 0x80 = -128, NÃO +384. LSLL_rr por -128 => desloca à direita 128 => 0.
        ArmCore core = newCore();
        setPair(core, 2, 5, 0x1234_5678_9ABC_DEF0L);
        core.setRegister(8, 0x180);
        put32(core, CODE_BASE, pairRegister(0b0000, false, 2, 5, 8));
        core.step();
        assertEquals(0L, pair(core, 2, 5));

        // 0xFF = -1 => desloca 1 à direita (lógico).
        ArmCore minusOne = newCore();
        setPair(minusOne, 2, 5, 0x10L);
        minusOne.setRegister(8, 0x1FF);
        put32(minusOne, CODE_BASE, pairRegister(0b0000, false, 2, 5, 8));
        minusOne.step();
        assertEquals(0x8L, pair(minusOne, 2, 5));

        // 0x100 => byte baixo 0 => sem deslocamento.
        ArmCore zero = newCore();
        setPair(zero, 2, 5, 0x10L);
        zero.setRegister(8, 0x100);
        put32(zero, CODE_BASE, pairRegister(0b0000, false, 2, 5, 8));
        zero.step();
        assertEquals(0x10L, pair(zero, 2, 5));
    }

    @Test
    void lsllAndAsrlRegisterFormsNeverSetQEvenWhenTheValueOverflows() {
        ArmCore lsll = newCore();
        setPair(lsll, 2, 5, 0x4000_0000_0000_0000L);
        lsll.setRegister(8, 4);
        put32(lsll, CODE_BASE, pairRegister(0b0000, false, 2, 5, 8));
        lsll.step();
        assertEquals(0L, pair(lsll, 2, 5), "LSLL_rr só trunca");
        assertFalse(lsll.cpsr().saturation(), "sat=NULL: LSLL_rr nunca seta Q");

        ArmCore uqrshll = newCore();
        setPair(uqrshll, 2, 5, 0x4000_0000_0000_0000L);
        uqrshll.setRegister(8, 4);
        put32(uqrshll, CODE_BASE, pairRegister(0b0000, true, 2, 5, 8));
        uqrshll.step();
        assertEquals(-1L, pair(uqrshll, 2, 5), "UQRSHLL64 satura");
        assertTrue(uqrshll.cpsr().saturation());
    }

    @Test
    void sqrshrlNegatesTheAmountAndAsrlDoesNotRound() {
        // SQRSHRL64 com Rm=2 => desloca 2 à DIREITA com arredondamento: 11 >> 2 = 2,75 -> 3.
        ArmCore sqrshrl = newCore();
        setPair(sqrshrl, 2, 5, 0b1011);
        sqrshrl.setRegister(8, 2);
        put32(sqrshrl, CODE_BASE, pairRegister(0b0010, true, 2, 5, 8));
        sqrshrl.step();
        assertEquals(3L, pair(sqrshrl, 2, 5));

        // ASRL_rr com Rm=2 => 11 >> 2 = 2 (trunca).
        ArmCore asrl = newCore();
        setPair(asrl, 2, 5, 0b1011);
        asrl.setRegister(8, 2);
        put32(asrl, CODE_BASE, pairRegister(0b0010, false, 2, 5, 8));
        asrl.step();
        assertEquals(2L, pair(asrl, 2, 5));
        assertFalse(asrl.cpsr().saturation());
    }

    @Test
    void wordRegisterFormsSaturateAndNegate() {
        ArmCore uqrshl = newCore();
        uqrshl.setRegister(3, 0x4000_0000);
        uqrshl.setRegister(4, 2);
        put32(uqrshl, CODE_BASE, wordRegister(0b0000, 3, 4));
        uqrshl.step();
        assertEquals(0xFFFF_FFFF, uqrshl.register(3));
        assertTrue(uqrshl.cpsr().saturation());

        ArmCore sqrshr = newCore();
        sqrshr.setRegister(3, 11);
        sqrshr.setRegister(4, 2); // SQRSHR nega: desloca 2 à direita, com arredondamento
        put32(sqrshr, CODE_BASE, wordRegister(0b0010, 3, 4));
        sqrshr.step();
        assertEquals(3, sqrshr.register(3));
        assertFalse(sqrshr.cpsr().saturation(), "deslocamento à direita nunca satura");
    }

    @Test
    void fortyEightBitFormsSaturateAtFortyEightBitsNotSixtyFour() {
        ArmCore unsigned = newCore();
        setPair(unsigned, 2, 5, 0x1_0000_0000_0000L);
        unsigned.setRegister(8, 1);
        put32(unsigned, CODE_BASE, pairRegister(0b1000, true, 2, 5, 8));
        unsigned.step();
        assertEquals(0xFFFF_FFFF_FFFFL, pair(unsigned, 2, 5), "UQRSHLL48 satura em 2^48-1");
        assertTrue(unsigned.cpsr().saturation());

        ArmCore signed = newCore();
        setPair(signed, 2, 5, -0x8000_0000_0000L);
        signed.setRegister(8, 0xFF); // byte com sinal = -1; SQRSHRL48 nega => +1 => desloca 1 à ESQUERDA
        put32(signed, CODE_BASE, pairRegister(0b1010, true, 2, 5, 8));
        signed.step();
        assertEquals(0xFFFF_8000_0000_0000L, pair(signed, 2, 5), "SQRSHRL48 satura em -2^47");
        assertTrue(signed.cpsr().saturation());
    }

    // ── IT e regressão de desambiguação ─────────────────────────────────────────────────────────

    @Test
    void instructionInsideAFalseItBlockDoesNothing() {
        ArmCore core = newCore();
        setPair(core, 2, 5, 0x10L);
        core.cpsr().setNzcv(false, true, false, false); // Z=1
        ((TestAddressSpace) core.memory()).put16(CODE_BASE, 0xBF18); // IT NE
        put32(core, CODE_BASE + 2, pairImmediate(LEFT, false, 2, 5, 4));
        core.step(); // IT
        core.step(); // LSLL, condição NE falsa
        assertEquals(0x10L, pair(core, 2, 5));
        assertEquals(CODE_BASE + 6, core.programCounter());
    }

    @Test
    void instructionInsideATrueItBlockExecutes() {
        ArmCore core = newCore();
        setPair(core, 2, 5, 0x10L);
        core.cpsr().setNzcv(false, false, false, false); // Z=0
        ((TestAddressSpace) core.memory()).put16(CODE_BASE, 0xBF18); // IT NE
        put32(core, CODE_BASE + 2, pairImmediate(LEFT, false, 2, 5, 4));
        core.step();
        core.step();
        assertEquals(0x100L, pair(core, 2, 5));
    }

    @Test
    void movsAndOrrsSharingTheEncodingSpaceStillWork() {
        ArmCore movs = newCore();
        movs.setRegister(1, 0x8000_0000);
        put32(movs, CODE_BASE, 0xEA5F_0001); // MOVS.W r0, r1
        movs.step();
        assertEquals(0x8000_0000, movs.register(0));
        assertTrue(movs.cpsr().negative());

        ArmCore orrs = newCore();
        orrs.setRegister(1, 0x0F00);
        orrs.setRegister(2, 0x00F0);
        put32(orrs, CODE_BASE, 0xEA51_0002); // ORRS.W r0, r1, r2
        orrs.step();
        assertEquals(0x0FF0, orrs.register(0));
    }

    @Test
    void movsFromSpWithRdNotPcBehavesExactlyAsWithoutTheLongShiftDecoder() {
        // Rm=13 mas Rd != 15: bits[11:8] != 1111, então não é long shift e TEM que se comportar
        // exatamente como no preset ARMv8.1-M sem MVE (que não registra o decoder novo) — o que o
        // Thumb2DataProcessingDecoder faz com Rm=SP (UNPREDICTABLE em MOVS) é problema dele, não daqui.
        ArmCore withDecoder = newCore();
        put32(withDecoder, CODE_BASE, 0xEA5F_000D); // MOVS.W r0, sp
        withDecoder.step();

        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore without = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M);
        without.cpsr().setThumbMode(true);
        without.setRegister(13, 0x1000);
        without.setProgramCounter(CODE_BASE);
        put32(without, CODE_BASE, 0xEA5F_000D);
        without.step();

        assertEquals(without.register(0), withDecoder.register(0));
        assertEquals(without.programCounter(), withDecoder.programCounter());
    }

    // ── caminho de blocos (lifter + IrBlockExecutor + política ASM) ─────────────────────────────

    private static dev.vitorsilverio.armjitter.ir.IrBlock liftThumb(ArmCore core, int count) {
        return new dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter(
                new dev.vitorsilverio.armjitter.decoder.ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE),
                new dev.vitorsilverio.armjitter.ir.StandardIrBuilder())
                .lift(core.memory(), CODE_BASE, count, 0);
    }

    @Test
    void liftedBlockRunsThroughIrBlockExecutorLikeStep() {
        ArmCore core = newCore();
        setPair(core, 2, 5, 0x0000_0000_8000_0001L);
        core.setRegister(3, 0x8000_0000);
        put32(core, CODE_BASE, pairImmediate(LEFT, false, 2, 5, 4)); // LSLL
        put32(core, CODE_BASE + 4, wordImmediate(LEFT, 3, 1)); // UQSHL (satura)
        var block = liftThumb(core, 2);
        assertEquals(2, block.operations().stream()
                .filter(op -> op instanceof dev.vitorsilverio.armjitter.ir.IrOp.MveWideShift).count());

        new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .execute(block, core);

        assertEquals(0x0000_0008_0000_0010L, pair(core, 2, 5));
        assertEquals(0xFFFF_FFFF, core.register(3));
        assertTrue(core.cpsr().saturation());
        assertEquals(CODE_BASE + 8, core.programCounter());
    }

    @Test
    void wideShiftIsInterpretedOnlyAndRunsThroughExecuteOp() {
        var op = new dev.vitorsilverio.armjitter.ir.IrOp.MveWideShift(
                dev.vitorsilverio.armjitter.ir.IrOp.WideShiftOperation.LSLL_RI, 4, -1, 2, 5, Condition.AL);
        assertFalse(dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(op));
        ArmCore core = newCore();
        setPair(core, 2, 5, 1);
        assertFalse(new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, op, CODE_BASE));
        assertEquals(0x10L, pair(core, 2, 5));
    }

    @Test
    void wideShiftWithFalseConditionDoesNothingInExecuteOp() {
        var op = new dev.vitorsilverio.armjitter.ir.IrOp.MveWideShift(
                dev.vitorsilverio.armjitter.ir.IrOp.WideShiftOperation.LSLL_RI, 4, -1, 2, 5, Condition.NE);
        ArmCore core = newCore();
        core.cpsr().setNzcv(false, true, false, false);
        setPair(core, 2, 5, 1);
        new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, op, CODE_BASE);
        assertEquals(1L, pair(core, 2, 5));
    }
}
