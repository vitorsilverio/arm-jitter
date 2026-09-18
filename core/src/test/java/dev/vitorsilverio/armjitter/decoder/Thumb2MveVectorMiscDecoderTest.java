package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.13a — `Thumb2MveVectorMiscDecoder`: 1-op misc/`VABS_fp`/`VNEG_fp`/`VDUP`
/// (`target/isa-decode/mve.decode`, linhas 369-395, 15 encodings). Raws montados bit a bit
/// separadamente para as formas `@1op`/`@1op_nosz` e `@vdup` (ver Javadoc da classe).
class Thumb2MveVectorMiscDecoderTest {
    private static int oneOpRaw(int group, int size, int nibble, int sub2, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111111111 << 23)
                | (qdHigh << 22)
                | (0b11 << 20)
                | (size << 18)
                | (group << 16)
                | (qdLow << 13)
                | (nibble << 8)
                | (qmHigh << 5)
                | (sub2 << 6)
                | (qmLow << 1);
    }

    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveVectorMiscDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    // ── @1op / @1op_nosz ────────────────────────────────────────────────────────────────────────

    @Test
    void decodesClsAndClz() {
        IrOp.MveVectorUnary cls = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 1, 0b0100, 0b01, 2, 3)).liftedOp());
        assertEquals(AdvSimdUnaryOp.CLS, cls.op());
        assertEquals(1, cls.esz());
        assertEquals(2, cls.qd());
        assertEquals(3, cls.qm());

        IrOp.MveVectorUnary clz = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 2, 0b0100, 0b11, 4, 5)).liftedOp());
        assertEquals(AdvSimdUnaryOp.CLZ, clz.op());
    }

    @Test
    void decodesRevForms() {
        IrOp.MveVectorUnary rev16 = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 0, 0b0001, 0b01, 1, 2)).liftedOp());
        assertEquals(AdvSimdUnaryOp.REV16, rev16.op());

        IrOp.MveVectorUnary rev32 = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 1, 0b0000, 0b11, 1, 2)).liftedOp());
        assertEquals(AdvSimdUnaryOp.REV32, rev32.op());

        IrOp.MveVectorUnary rev64 = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 2, 0b0000, 0b01, 1, 2)).liftedOp());
        assertEquals(AdvSimdUnaryOp.REV64, rev64.op());

        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0001, 0b11, 1, 2))); // sem mnemônico
    }

    @Test
    void decodesQabsAndQneg() {
        IrOp.MveVectorUnary qabs = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 0, 0b0111, 0b01, 0, 1)).liftedOp());
        assertEquals(AdvSimdUnaryOp.SQABS, qabs.op());

        IrOp.MveVectorUnary qneg = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 0, 0b0111, 0b11, 0, 1)).liftedOp());
        assertEquals(AdvSimdUnaryOp.SQNEG, qneg.op());
    }

    @Test
    void decodesVmvnWithSizeZeroOnly() {
        IrOp.MveVectorUnary mvn = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b00, 0, 0b0101, 0b11, 6, 7)).liftedOp());
        assertEquals(AdvSimdUnaryOp.NOT, mvn.op());
        assertEquals(0, mvn.esz());

        // @1op_nosz: bits[19:16] são literais 0000 na linha real — size != 0 não é VMVN.
        assertNull(tryDecode(oneOpRaw(0b00, 1, 0b0101, 0b11, 6, 7)));
    }

    @Test
    void decodesAbsAndNegInteger() {
        IrOp.MveVectorUnary abs = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b01, 0, 0b0011, 0b01, 1, 2)).liftedOp());
        assertEquals(AdvSimdUnaryOp.ABS, abs.op());

        IrOp.MveVectorUnary neg = assertInstanceOf(IrOp.MveVectorUnary.class,
                tryDecode(oneOpRaw(0b01, 0, 0b0011, 0b11, 1, 2)).liftedOp());
        assertEquals(AdvSimdUnaryOp.NEG, neg.op());
    }

    @Test
    void decodesAbsAndNegFpUnderMveFloat() {
        ArmArchitecture arch = ArmArchitecture.ARMV8_1M_MVE;
        int absFpRaw = oneOpRaw(0b01, 2, 0b0111, 0b01, 1, 2);
        IrOp.MveVectorFpUnary absFp = assertInstanceOf(IrOp.MveVectorFpUnary.class,
                new Thumb2MveVectorMiscDecoder(arch).tryDecode(absFpRaw, 0, Condition.AL).liftedOp());
        assertEquals(AdvSimdFpUnaryOp.ABS, absFp.op());

        int negFpRaw = oneOpRaw(0b01, 2, 0b0111, 0b11, 1, 2);
        IrOp.MveVectorFpUnary negFp = assertInstanceOf(IrOp.MveVectorFpUnary.class,
                new Thumb2MveVectorMiscDecoder(arch).tryDecode(negFpRaw, 0, Condition.AL).liftedOp());
        assertEquals(AdvSimdFpUnaryOp.NEG, negFp.op());
    }

    @Test
    void rejectsSizeInvalid() {
        assertNull(tryDecode(oneOpRaw(0b00, 0b11, 0b0100, 0b01, 1, 2)));
    }

    @Test
    void groupTenAndElevenAreNotThisDecoder() {
        // bits[17:16] em {10,11}: pertence ao Thumb2MveFpConvertDecoder (B16.12), não a este.
        assertNull(tryDecode(oneOpRaw(0b10, 2, 0b0000, 0b00, 1, 2)));
        assertNull(tryDecode(oneOpRaw(0b11, 2, 0b0000, 0b00, 1, 2)));
    }

    @Test
    void requiresMveIntegerFeature() {
        int r = oneOpRaw(0b00, 0, 0b0100, 0b01, 1, 2);
        assertNull(new Thumb2MveVectorMiscDecoder(ArmArchitecture.ARMV7A).tryDecode(r, 0, Condition.AL));
    }

    @Test
    void oneOpRejectsMalformedBit12Bit4Bit0() {
        int good = oneOpRaw(0b00, 0, 0b0100, 0b01, 1, 2); // VCLS.
        assertNull(tryDecode(good | (1 << 12)), "bit12 tem que ser 0");
        assertNull(tryDecode(good | (1 << 4)), "bit4 tem que ser 0");
        assertNull(tryDecode(good | 1), "bit0 tem que ser 0");
    }

    @Test
    void oneOpRejectsQdOrQmInHighBank() {
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0100, 0b01, 8, 2)), "Qd=8 fora de Q0-Q7");
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0100, 0b01, 1, 9)), "Qm=9 fora de Q0-Q7");
    }

    @Test
    void oneOpRejectsUnallocatedNibbleUnderMisc() {
        // group=00 (misc), nibble=0b0010 não corresponde a nenhum mnemônico da família.
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0010, 0b01, 1, 2)));
    }

    @Test
    void miscNibblesRejectTheThirdSub2Value() {
        // Cada nibble de 2 mnemônicos (CLS/CLZ, REV32/REV64, SQABS/SQNEG) só define sub2=01/11 —
        // sub2=00/10 não tem mnemônico correspondente.
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0100, 0b00, 1, 2))); // nem CLS nem CLZ.
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0000, 0b00, 1, 2))); // nem REV32 nem REV64.
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0111, 0b00, 1, 2))); // nem SQABS nem SQNEG.
        // nibble=0101 (slot do VMVN) com sub2 != 11 também não tem mnemônico.
        assertNull(tryDecode(oneOpRaw(0b00, 0, 0b0101, 0b01, 1, 2)));
    }

    @Test
    void absNegIntRejectsUnallocatedSub2() {
        // group=01 (abs/neg), nibble=0011 (ABS/NEG int), sub2 só define 01/11 — 00/10 sem mnemônico.
        assertNull(tryDecode(oneOpRaw(0b01, 0, 0b0011, 0b00, 1, 2)));
        assertNull(tryDecode(oneOpRaw(0b01, 0, 0b0011, 0b10, 1, 2)));
    }

    @Test
    void absNegFpRejectsWithoutMveFloatAndUnallocatedSub2() {
        // MVE_INTEGER presente, MVE_FLOAT ausente (arquitetura sintética — nenhum preset público
        // decompõe as duas hoje, mas o gate tem que valer independentemente).
        ArmArchitecture integerOnly =
                ArmArchitecture.extending(ArmArchitecture.ARMV8_1M, "mve-integer-only-test", ArmFeature.MVE_INTEGER);
        int absFpRaw = oneOpRaw(0b01, 2, 0b0111, 0b01, 1, 2);
        assertNull(new Thumb2MveVectorMiscDecoder(integerOnly).tryDecode(absFpRaw, 0, Condition.AL));

        // Com MVE_FLOAT presente, sub2 só define 01/11 — 00/10 sem mnemônico.
        assertNull(tryDecode(oneOpRaw(0b01, 2, 0b0111, 0b00, 1, 2)));
        assertNull(tryDecode(oneOpRaw(0b01, 2, 0b0111, 0b10, 1, 2)));
    }

    @Test
    void groupAbsNegRejectsNibbleWithoutMnemonic() {
        // group=01, nibble=0101 não é nem ABS_NEG_INT(0011) nem ABS_NEG_FP(0111).
        assertNull(tryDecode(oneOpRaw(0b01, 0, 0b0101, 0b01, 1, 2)));
    }

    // ── @vdup ───────────────────────────────────────────────────────────────────────────────────

    private static int vdupRawReal(int b, int e, int qd, int rt) {
        int qHigh = (qd >>> 3) & 1;
        int qLow = qd & 0x7;
        return (0b1110_1110 << 24)
                | (1 << 23)
                | (b << 22)
                | (0b10 << 20)
                | (qLow << 17)
                | (qHigh << 7)
                | (rt << 12)
                | (0b1011 << 8)
                | (e << 5);
    }

    @Test
    void decodesVdupByteHalfwordWord() {
        IrOp.MveVectorDup byteForm = assertInstanceOf(IrOp.MveVectorDup.class,
                tryDecode(vdupRawReal(1, 0, 3, 5)).liftedOp());
        assertEquals(0, byteForm.esz());
        assertEquals(3, byteForm.qd());
        assertEquals(5, byteForm.rt());

        IrOp.MveVectorDup halfForm = assertInstanceOf(IrOp.MveVectorDup.class,
                tryDecode(vdupRawReal(0, 1, 4, 6)).liftedOp());
        assertEquals(1, halfForm.esz());

        IrOp.MveVectorDup wordForm = assertInstanceOf(IrOp.MveVectorDup.class,
                tryDecode(vdupRawReal(0, 0, 7, 2)).liftedOp());
        assertEquals(2, wordForm.esz());
    }

    @Test
    void vdupUsesQnFieldForQd() {
        // "Qd is in the fields usually named Qn" — bit22 (que seria alto de %qd) É o bit B do
        // tamanho aqui, não parte de Qd; Qd vem inteiramente de bit7+bits[19:17].
        int raw = vdupRawReal(0, 0, 5, 1);
        IrOp.MveVectorDup dup = assertInstanceOf(IrOp.MveVectorDup.class, tryDecode(raw).liftedOp());
        assertEquals(5, dup.qd());
    }

    @Test
    void rejectsVdupBEqualsEOne() {
        assertNull(tryDecode(vdupRawReal(1, 1, 1, 2)));
    }

    @Test
    void rejectsVdupRtSpOrPc() {
        assertNull(tryDecode(vdupRawReal(1, 0, 1, 13)));
        assertNull(tryDecode(vdupRawReal(1, 0, 1, 15)));
    }

    @Test
    void rejectsVdupMalformedLiteralBits() {
        int good = vdupRawReal(1, 0, 3, 5);
        assertNull(tryDecode(good & ~(1 << 23)), "bit23 tem que ser 1");
        assertNull(tryDecode(good | (1 << 6)), "bit6 tem que ser 0");
        assertNull(tryDecode(good | 1), "bits[3:0] têm que ser 0000");
    }

    @Test
    void rejectsVdupQdInHighBank() {
        // Qd=8 via %qn (bit7=1, bits[19:17]=000) — fora de Q0-Q7.
        int raw = vdupRawReal(1, 0, 0, 5) | (1 << 7);
        assertNull(tryDecode(raw));
    }
}
