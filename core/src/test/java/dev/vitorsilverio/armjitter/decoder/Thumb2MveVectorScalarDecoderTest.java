package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.9 — `Thumb2MveVectorScalarDecoder`: operações escalares (vetor × GPR broadcast,
/// `target/isa-decode/mve.decode`, linhas 496-573, 35 encodings). Raws construídos bit a bit com o
/// MESMO layout do Javadoc da classe (`bits[31:29]=111`, `bit28=U`, `bits[27:24]=1110` fixo,
/// `bit23=0`, `Qd`(22,15:13), `bits[21:20]`(`size` ou `11` fixo p/ formas mais específicas),
/// `Qn`(19:17,7) — repropositado em `@shl_scalar`, `bit16`, `bit12`, nibble(11:8), nibble(6:4),
/// `Rm`(3:0)).
class Thumb2MveVectorScalarDecoderTest {
    private static final ArmArchitecture MVE_INTEGER_ONLY =
            ArmArchitecture.extending(ArmArchitecture.ARMV8_1M, "MVE_INTEGER only (teste)", ArmFeature.MVE_INTEGER);

    /// Forma `@2scalar`/`@2scalar_nosz`/`@2op_fp_scalar`: `Qn` real (bits 19:17,7).
    private static int raw2scalar(int u, int size, int qd, int qn, int bit16, int bit12, int nibble, int nibble3,
            int rm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (size << 20) | (qnLow << 17)
                | (bit16 << 16) | (qdLow << 13) | (bit12 << 12) | (nibble << 8) | (qnHigh << 7) | (nibble3 << 4)
                | rm;
    }

    /// Forma `@shl_scalar`: `bits[21:20]=11` fixo, `size` real em `19:18`, `round`=bit17, `sat`=bit7
    /// (repropositado — não existe `Qn`).
    private static int rawShl(int u, int size, int qd, int round, int sat, int rm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (0b11 << 20) | (size << 18)
                | (round << 17) | (1 << 16) | (qdLow << 13) | (1 << 12) | (0b1110 << 8) | (sat << 7) | (0b110 << 4)
                | rm;
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveVectorScalarDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    private static IrOp.MveVectorScalar decodeScalar(int raw) {
        return assertInstanceOf(IrOp.MveVectorScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp());
    }

    // ── VADD_scalar / VSUB_scalar (nib2=1111, b16=1) ────────────────────────────────────────────

    @Test
    void decodesVaddScalar() {
        IrOp.MveVectorScalar op = decodeScalar(raw2scalar(0, 1, 4, 5, 1, 0, 0b1111, 0b100, 6));
        assertEquals(AdvSimdThreeSameOp.ADD, op.op());
        assertEquals(1, op.esz());
        assertEquals(4, op.qd());
        assertEquals(5, op.qn());
        assertEquals(6, op.rm());
    }

    @Test
    void decodesVsubScalar() {
        IrOp.MveVectorScalar op = decodeScalar(raw2scalar(0, 2, 0, 1, 1, 1, 0b1111, 0b100, 2));
        assertEquals(AdvSimdThreeSameOp.SUB, op.op());
        assertEquals(2, op.esz());
    }

    @Test
    void vaddScalarWithUnsignedBitSetIsUnallocated() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 1, 0, 1, 1, 0, 0b1111, 0b100, 2)));
    }

    // ── @shl_scalar: VSHL/VRSHL/VQSHL/VQRSHL × S/U (colide com VMUL_scalar/VBRSR) ────────────────

    @Test
    void decodesVshlSScalarQdaIsSourceAndDestNotQn() {
        IrOp.MveVectorScalar op = assertInstanceOf(IrOp.MveVectorScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rawShl(0, 1, 3, 0, 0, 5)).liftedOp());
        assertEquals(AdvSimdThreeSameOp.SSHL, op.op());
        assertEquals(1, op.esz());
        assertEquals(3, op.qd());
        assertEquals(3, op.qn(), "Qda é fonte E destino — qn deve ser o MESMO valor de qd");
        assertEquals(5, op.rm());
    }

    @Test
    void decodesAllEightShlScalarForms() {
        assertEquals(AdvSimdThreeSameOp.SSHL,
                decodeShl(rawShl(0, 0, 0, 0, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.SRSHL,
                decodeShl(rawShl(0, 0, 0, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.SQSHL,
                decodeShl(rawShl(0, 0, 0, 0, 1, 1)));
        assertEquals(AdvSimdThreeSameOp.SQRSHL,
                decodeShl(rawShl(0, 0, 0, 1, 1, 1)));
        assertEquals(AdvSimdThreeSameOp.USHL,
                decodeShl(rawShl(1, 0, 0, 0, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.URSHL,
                decodeShl(rawShl(1, 0, 0, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.UQSHL,
                decodeShl(rawShl(1, 0, 0, 0, 1, 1)));
        assertEquals(AdvSimdThreeSameOp.UQRSHL,
                decodeShl(rawShl(1, 0, 0, 1, 1, 1)));
    }

    private static AdvSimdThreeSameOp decodeShl(int raw) {
        return assertInstanceOf(IrOp.MveVectorScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp()).op();
    }

    @Test
    void shlScalarSizeIsAtBits19_18NotWhereTwoScalarPutsIt() {
        // size=2 (word) em bits[19:18]; bits[21:20] continuam fixos em 11.
        IrOp.MveVectorScalar op = assertInstanceOf(IrOp.MveVectorScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rawShl(0, 2, 0, 0, 0, 1)).liftedOp());
        assertEquals(2, op.esz());
    }

    // ── VMUL_scalar / VBRSR (mesmo slot do @shl_scalar quando bits[21:20] != 3) ─────────────────

    @Test
    void decodesVmulScalarWhenSizeFieldIsNotThree() {
        IrOp.MveVectorScalar op = decodeScalar(raw2scalar(0, 1, 2, 3, 1, 1, 0b1110, 0b110, 4));
        assertEquals(AdvSimdThreeSameOp.MUL, op.op());
        assertEquals(1, op.esz());
        assertEquals(2, op.qd());
        assertEquals(3, op.qn());
    }

    @Test
    void decodesVbrsr() {
        IrOp.MveVectorScalarSpecial op = assertInstanceOf(IrOp.MveVectorScalarSpecial.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 0, 1, 2, 1, 1, 0b1110, 0b110, 3)).liftedOp());
        assertEquals(IrOp.MveVectorScalarSpecial.SpecialOp.VBRSR, op.op());
        assertEquals(0, op.esz());
        assertEquals(1, op.qd());
        assertEquals(2, op.qn());
        assertEquals(3, op.rm());
    }

    // ── VHADD_S/U, VHSUB_S/U, VQADD_S/U, VQSUB_S/U (nib2=1111, b16=0, size livre) ────────────────

    @Test
    void decodesVhaddSAndU() {
        assertEquals(AdvSimdThreeSameOp.SHADD,
                decodeScalar(raw2scalar(0, 0, 0, 1, 0, 0, 0b1111, 0b100, 2)).op());
        assertEquals(AdvSimdThreeSameOp.UHADD,
                decodeScalar(raw2scalar(1, 0, 0, 1, 0, 0, 0b1111, 0b100, 2)).op());
    }

    @Test
    void decodesVhsubSAndU() {
        assertEquals(AdvSimdThreeSameOp.SHSUB,
                decodeScalar(raw2scalar(0, 0, 0, 1, 0, 1, 0b1111, 0b100, 2)).op());
        assertEquals(AdvSimdThreeSameOp.UHSUB,
                decodeScalar(raw2scalar(1, 0, 0, 1, 0, 1, 0b1111, 0b100, 2)).op());
    }

    @Test
    void decodesVqaddSAndU() {
        assertEquals(AdvSimdThreeSameOp.SQADD,
                decodeScalar(raw2scalar(0, 0, 0, 1, 0, 0, 0b1111, 0b110, 2)).op());
        assertEquals(AdvSimdThreeSameOp.UQADD,
                decodeScalar(raw2scalar(1, 0, 0, 1, 0, 0, 0b1111, 0b110, 2)).op());
    }

    @Test
    void decodesVqsubSAndU() {
        assertEquals(AdvSimdThreeSameOp.SQSUB,
                decodeScalar(raw2scalar(0, 0, 0, 1, 0, 1, 0b1111, 0b110, 2)).op());
        assertEquals(AdvSimdThreeSameOp.UQSUB,
                decodeScalar(raw2scalar(1, 0, 0, 1, 0, 1, 0b1111, 0b110, 2)).op());
    }

    // ── VADD_fp_scalar / VSUB_fp_scalar (size == 3 fixo, MESMO slot de VHADD/VHSUB) ─────────────

    @Test
    void decodesVaddFpScalarAndVsubFpScalar() {
        IrOp.MveVectorFpScalar add = assertInstanceOf(IrOp.MveVectorFpScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 0, 0, 0b1111, 0b100, 2)).liftedOp());
        assertEquals(AdvSimdFpThreeSameOp.ADD, add.op());
        assertEquals(2, add.esz(), "bit28=0 -> binary32");
        IrOp.MveVectorFpScalar sub = assertInstanceOf(IrOp.MveVectorFpScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 3, 0, 1, 0, 1, 0b1111, 0b100, 2)).liftedOp());
        assertEquals(AdvSimdFpThreeSameOp.SUB, sub.op());
        assertEquals(1, sub.esz(), "bit28=1 -> binary16");
    }

    @Test
    void vaddFpScalarWithoutMveFloatIsRejectedAndDoesNotBecomeVhadd() {
        assertNull(tryDecode(MVE_INTEGER_ONLY, raw2scalar(0, 3, 0, 1, 0, 0, 0b1111, 0b100, 2)));
    }

    // ── VQDMULLB_scalar / VQDMULLT_scalar (size == 3 fixo, MESMO slot de VQADD/VQSUB) ───────────

    @Test
    void decodesVqdmullbAndVqdmulltScalar() {
        IrOp.MveVectorScalarWidening b = assertInstanceOf(IrOp.MveVectorScalarWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 0, 0, 0b1111, 0b110, 2)).liftedOp());
        assertEquals(1, b.esz(), "bit28=0 -> halfword fonte");
        assertEquals(false, b.top());
        IrOp.MveVectorScalarWidening t = assertInstanceOf(IrOp.MveVectorScalarWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 3, 0, 1, 0, 1, 0b1111, 0b110, 2)).liftedOp());
        assertEquals(2, t.esz(), "bit28=1 -> word fonte");
        assertEquals(true, t.top());
    }

    @Test
    void vqdmullbScalarRejectsQdEqualsQnWithWordSize() {
        // bit28=1 (esz=2, word) e Qd==Qn: UNPREDICTABLE, "choose to undef".
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 3, 2, 2, 0, 0, 0b1111, 0b110, 2)));
    }

    @Test
    void vqdmullbScalarAllowsQdEqualsQnWithHalfwordSize() {
        // bit28=0 (esz=1, halfword): Qd==Qn é permitido (a restrição só vale para word).
        assertInstanceOf(IrOp.MveVectorScalarWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 2, 2, 0, 0, 0b1111, 0b110, 2)).liftedOp());
    }

    @Test
    void vqdmullbScalarWithoutMveFloatStillDecodesUnderMveIntegerOnly() {
        assertInstanceOf(IrOp.MveVectorScalarWidening.class,
                tryDecode(MVE_INTEGER_ONLY, raw2scalar(0, 3, 0, 1, 0, 0, 0b1111, 0b110, 2)).liftedOp());
    }

    // ── VMUL_fp_scalar / VQDMULH_scalar / VQRDMULH_scalar ───────────────────────────────────────

    @Test
    void decodesVmulFpScalar() {
        IrOp.MveVectorFpScalar op = assertInstanceOf(IrOp.MveVectorFpScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 1, 0, 0b1110, 0b110, 2)).liftedOp());
        assertEquals(AdvSimdFpThreeSameOp.MUL, op.op());
        assertEquals(2, op.esz());
    }

    @Test
    void decodesVqdmulhAndVqrdmulhScalar() {
        assertEquals(AdvSimdThreeSameOp.SQDMULH,
                decodeScalar(raw2scalar(0, 1, 0, 1, 1, 0, 0b1110, 0b110, 2)).op());
        assertEquals(AdvSimdThreeSameOp.SQRDMULH,
                decodeScalar(raw2scalar(1, 1, 0, 1, 1, 0, 0b1110, 0b110, 2)).op());
    }

    // ── VMLA / VFMA_scalar ("111 -" bit 28 don't-care para VMLA) ────────────────────────────────

    @Test
    void vmlaDecodesTheSameRegardlessOfBit28() {
        int rawU0 = raw2scalar(0, 1, 0, 1, 1, 0, 0b1110, 0b100, 2);
        int rawU1 = raw2scalar(1, 1, 0, 1, 1, 0, 0b1110, 0b100, 2);
        IrOp.MveVectorScalar opU0 = decodeScalar(rawU0);
        IrOp.MveVectorScalar opU1 = decodeScalar(rawU1);
        assertEquals(AdvSimdThreeSameOp.MLA, opU0.op());
        assertEquals(opU0, opU1, "os DOIS valores de bit 28 devem produzir a MESMA instrução");
    }

    @Test
    void decodesVfmaScalar() {
        IrOp.MveVectorFpScalarFma op = assertInstanceOf(IrOp.MveVectorFpScalarFma.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 1, 0, 0b1110, 0b100, 2)).liftedOp());
        assertEquals(false, op.swapAccumulator());
        assertEquals(2, op.esz());
    }

    @Test
    void vfmaScalarWithoutMveFloatIsRejectedAndDoesNotBecomeVmla() {
        assertNull(tryDecode(MVE_INTEGER_ONLY, raw2scalar(0, 3, 0, 1, 1, 0, 0b1110, 0b100, 2)));
    }

    // ── VMLAS / VFMAS_scalar ─────────────────────────────────────────────────────────────────────

    @Test
    void vmlasDecodesTheSameRegardlessOfBit28() {
        int rawU0 = raw2scalar(0, 1, 0, 1, 1, 1, 0b1110, 0b100, 2);
        int rawU1 = raw2scalar(1, 1, 0, 1, 1, 1, 0b1110, 0b100, 2);
        IrOp.MveVectorScalarSpecial opU0 = assertInstanceOf(IrOp.MveVectorScalarSpecial.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rawU0).liftedOp());
        IrOp.MveVectorScalarSpecial opU1 = assertInstanceOf(IrOp.MveVectorScalarSpecial.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rawU1).liftedOp());
        assertEquals(IrOp.MveVectorScalarSpecial.SpecialOp.VMLAS, opU0.op());
        assertEquals(opU0, opU1);
    }

    @Test
    void decodesVfmasScalar() {
        IrOp.MveVectorFpScalarFma op = assertInstanceOf(IrOp.MveVectorFpScalarFma.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 1, 1, 0b1110, 0b100, 2)).liftedOp());
        assertEquals(true, op.swapAccumulator());
    }

    // ── VQRDMLAH / VQRDMLASH / VQDMLAH / VQDMLASH (U fixo em 0) ─────────────────────────────────

    @Test
    void decodesAllFourDoublingAccumulateForms() {
        assertOp(IrOp.MveVectorScalarSpecial.SpecialOp.VQRDMLAH, raw2scalar(0, 1, 0, 1, 0, 0, 0b1110, 0b100, 2));
        assertOp(IrOp.MveVectorScalarSpecial.SpecialOp.VQRDMLASH, raw2scalar(0, 1, 0, 1, 0, 1, 0b1110, 0b100, 2));
        assertOp(IrOp.MveVectorScalarSpecial.SpecialOp.VQDMLAH, raw2scalar(0, 1, 0, 1, 0, 0, 0b1110, 0b110, 2));
        assertOp(IrOp.MveVectorScalarSpecial.SpecialOp.VQDMLASH, raw2scalar(0, 1, 0, 1, 0, 1, 0b1110, 0b110, 2));
    }

    private static void assertOp(IrOp.MveVectorScalarSpecial.SpecialOp expected, int raw) {
        IrOp.MveVectorScalarSpecial op = assertInstanceOf(IrOp.MveVectorScalarSpecial.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp());
        assertEquals(expected, op.op());
    }

    @Test
    void doublingAccumulateFormsHaveNoUnsignedVariant() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 1, 0, 1, 0, 0, 0b1110, 0b100, 2)));
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(1, 1, 0, 1, 0, 0, 0b1110, 0b110, 2)));
    }

    // ── G8: rejeições ────────────────────────────────────────────────────────────────────────────

    @Test
    void rejectsRmEqualsThirteen() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 1, 0, 1, 1, 0, 0b1111, 0b100, 13)));
    }

    @Test
    void rejectsRmEqualsFifteen() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 1, 0, 1, 1, 0, 0b1111, 0b100, 15)));
    }

    @Test
    void rejectsSizeEqualsThreeOnPlainTwoScalarForms() {
        // VADD_scalar não tem forma FP/alargante vizinha nesse slot -> size==3 é genuinamente vazio.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 3, 0, 1, 1, 0, 0b1111, 0b100, 2)));
    }

    @Test
    void rejectsQdGreaterThanSeven() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                raw2scalar(0, 1, 8 /* inválido, mas high bit cabe em qdHigh */, 1, 1, 0, 0b1111, 0b100, 2)));
    }

    @Test
    void rejectsQnGreaterThanSeven() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw2scalar(0, 1, 0, 9, 1, 0, 0b1111, 0b100, 2)));
    }

    // ── Pipeline completo: nunca vira NOCP ───────────────────────────────────────────────────────

    @Test
    void fullPipelineNeverDecodesAsNocp() {
        int r = raw2scalar(0, 1, 4, 5, 1, 0, 0b1111, 0b100, 6);
        assertInstanceOf(IrOp.MveVectorScalar.class, decodeThumb32(r).liftedOp());
    }

    private static DecodedInstruction decodeThumb32(int raw32) {
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
    }
}
