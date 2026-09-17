package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.7 sub-família 3 — `Thumb2MveVector2opFpDecoder`: a seção "2-operand FP" do `.decode` real
/// (`VADD_fp`…`VCMLA270`, 14 encodings, linhas 771-789). Raws construídos a partir dos valores
/// literais medidos bit a bit contra o arquivo real (ver Javadoc da classe), com `Qd`/`Qn`/`Qm`/
/// `size` inseridos nos campos correspondentes (a máscara comum garante que esses bits partem de
/// `0` no literal, então OR é seguro).
class Thumb2MveVector2opFpDecoderTest {
    private static final int VALUE_VADD_FP = 0xEF000D40;
    private static final int VALUE_VSUB_FP = 0xEF200D40;
    private static final int VALUE_VMUL_FP = 0xFF000D50;
    private static final int VALUE_VABD_FP = 0xFF200D40;
    private static final int VALUE_VMAXNM = 0xFF000F50;
    private static final int VALUE_VMINNM = 0xFF200F50;
    private static final int VALUE_VFMA = 0xEF000C50;
    private static final int VALUE_VFMS = 0xEF200C50;
    private static final int VALUE_VCADD90_FP = 0xFC800840;
    private static final int VALUE_VCADD270_FP = 0xFD800840;
    private static final int VALUE_VCMLA0 = 0xFC200840;
    private static final int VALUE_VCMLA90 = 0xFCA00840;
    private static final int VALUE_VCMLA180 = 0xFD200840;
    private static final int VALUE_VCMLA270 = 0xFDA00840;

    private static int raw(int value, int qd, int qn, int qm, int sizeBit) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return value | (qdHigh << 22) | (qdLow << 13) | (qnHigh << 7) | (qnLow << 17) | (qmHigh << 5) | (qmLow << 1)
                | (sizeBit << 20);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveVector2opFpDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── @2op_fp: VADD_fp/VSUB_fp/VMUL_fp/VABD_fp/VMAXNM/VMINNM/VFMA/VFMS ────────────────────────────

    @Test
    void decodesTwoOpArithmeticOperationAndRegisters() {
        IrOp.MveVectorFpTwoOp add = twoOp(VALUE_VADD_FP, 3, 4, 5, 0);
        assertEquals(AdvSimdFpThreeSameOp.ADD, add.op());
        assertEquals(3, add.qd());
        assertEquals(4, add.qn());
        assertEquals(5, add.qm());

        assertEquals(AdvSimdFpThreeSameOp.SUB, twoOp(VALUE_VSUB_FP, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.MUL, twoOp(VALUE_VMUL_FP, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.ABD, twoOp(VALUE_VABD_FP, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.MAXNM, twoOp(VALUE_VMAXNM, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.MINNM, twoOp(VALUE_VMINNM, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.FMLA, twoOp(VALUE_VFMA, 0, 0, 0, 0).op());
        assertEquals(AdvSimdFpThreeSameOp.FMLS, twoOp(VALUE_VFMS, 0, 0, 0, 0).op());
    }

    @Test
    void twoOpSizeIsDirect() {
        // %2op_fp_size: bit20=1 -> binary16 (esz=1); bit20=0 -> binary32 (esz=2).
        assertEquals(1, twoOp(VALUE_VADD_FP, 0, 0, 0, 1).esz());
        assertEquals(2, twoOp(VALUE_VADD_FP, 0, 0, 0, 0).esz());
    }

    private static IrOp.MveVectorFpTwoOp twoOp(int value, int qd, int qn, int qm, int sizeBit) {
        int r = raw(value, qd, qn, qm, sizeBit);
        return assertInstanceOf(IrOp.MveVectorFpTwoOp.class, tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    // ── @2op_fp_size_rev: VCADD90_fp/VCADD270_fp ────────────────────────────────────────────────────

    @Test
    void decodesComplexAddRotationAndRegisters() {
        IrOp.MveVectorFpComplexAdd rot90 = complexAdd(VALUE_VCADD90_FP, 6, 7, 2, 0);
        assertTrue(rot90.rotate90());
        assertEquals(6, rot90.qd());
        assertEquals(7, rot90.qn());
        assertEquals(2, rot90.qm());

        IrOp.MveVectorFpComplexAdd rot270 = complexAdd(VALUE_VCADD270_FP, 0, 0, 0, 0);
        assertFalse(rot270.rotate90());
    }

    @Test
    void complexAddSizeIsReversed() {
        // %2op_fp_size_rev: bit20+1 -> bit20=0 é binary16 (esz=1), bit20=1 é binary32 (esz=2) —
        // exceção "reversa" do resto da seção, ver comentário do arquivo real.
        assertEquals(1, complexAdd(VALUE_VCADD90_FP, 0, 0, 0, 0).esz());
        assertEquals(2, complexAdd(VALUE_VCADD90_FP, 0, 0, 0, 1).esz());
    }

    private static IrOp.MveVectorFpComplexAdd complexAdd(int value, int qd, int qn, int qm, int sizeBit) {
        int r = raw(value, qd, qn, qm, sizeBit);
        return assertInstanceOf(IrOp.MveVectorFpComplexAdd.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    // ── @2op_fp_size_rev: VCMLA0/90/180/270 ──────────────────────────────────────────────────────────

    @Test
    void decodesComplexMultiplyAccumulateRotationAndRegisters() {
        assertEquals(AdvSimdLanes.COMPLEX_ROTATE_0, complexMla(VALUE_VCMLA0, 1, 2, 3, 0).rotation());
        assertEquals(AdvSimdLanes.COMPLEX_ROTATE_90, complexMla(VALUE_VCMLA90, 0, 0, 0, 0).rotation());
        assertEquals(AdvSimdLanes.COMPLEX_ROTATE_180, complexMla(VALUE_VCMLA180, 0, 0, 0, 0).rotation());
        assertEquals(AdvSimdLanes.COMPLEX_ROTATE_270, complexMla(VALUE_VCMLA270, 0, 0, 0, 0).rotation());

        IrOp.MveVectorFpComplexMultiplyAccumulate op = complexMla(VALUE_VCMLA0, 1, 2, 3, 0);
        assertEquals(1, op.qd());
        assertEquals(2, op.qn());
        assertEquals(3, op.qm());
    }

    @Test
    void complexMultiplyAccumulateSizeIsReversed() {
        assertEquals(1, complexMla(VALUE_VCMLA0, 0, 0, 0, 0).esz());
        assertEquals(2, complexMla(VALUE_VCMLA0, 0, 0, 0, 1).esz());
    }

    private static IrOp.MveVectorFpComplexMultiplyAccumulate complexMla(int value, int qd, int qn, int qm,
            int sizeBit) {
        int r = raw(value, qd, qn, qm, sizeBit);
        return assertInstanceOf(IrOp.MveVectorFpComplexMultiplyAccumulate.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    // ── Gate + recusa ────────────────────────────────────────────────────────────────────────────────

    @Test
    void requiresMveFloat() {
        int add = raw(VALUE_VADD_FP, 0, 0, 0, 0);
        int cmla = raw(VALUE_VCMLA0, 0, 0, 0, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, add), "ARMV8_1M sem MVE_FLOAT nem decodifica VADD_fp");
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, cmla), "ARMV8_1M sem MVE_FLOAT nem decodifica VCMLA0");
    }

    @Test
    void rejectsUnmatchedEncoding() {
        int r = raw(VALUE_VADD_FP, 0, 0, 0, 0) ^ (1 << 27); // corrompe um bit fixo fora dos campos.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }
}
