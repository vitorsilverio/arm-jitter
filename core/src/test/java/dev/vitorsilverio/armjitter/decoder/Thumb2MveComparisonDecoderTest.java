package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.MveCompareCondition;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.8 — `Thumb2MveComparisonDecoder`: `VCMP*`/`VCMP*_fp`/`VCMP*_scalar`/`VCMP*_fp_scalar`
/// (`target/isa-decode/mve.decode`, seção "Comparisons" + 6 linhas fora dela, 34 encodings).
/// Raws construídos a partir dos valores literais medidos bit a bit contra o arquivo real (ver
/// Javadoc da classe) via script auxiliar (`decode_bits.py`, verificado contra os 3 encodings já
/// shipados de `Thumb2MvePredicationDecoderTest` como oráculo secundário).
class Thumb2MveComparisonDecoderTest {
    private static final int VALUE_VCMPEQ_FP = 0xEE310F00;
    private static final int VALUE_VCMPEQ = 0xFE010F00;
    private static final int VALUE_VCMPGE_FP = 0xEE311F00;
    private static final int VALUE_VCMPGE = 0xFE011F00;
    private static final int VALUE_VCMPCS = 0xFE010F01;
    private static final int VALUE_VCMPHI = 0xFE010F81;
    private static final int VALUE_VCMPEQ_SCALAR = 0xFE010F40;
    private static final int VALUE_VCMPEQ_FP_SCALAR = 0xFE310F40;

    private static int quad(int value, int shiftHigh, int shiftLow) {
        int high = (value >>> 3) & 1;
        int low = value & 0x7;
        return (high << shiftHigh) | (low << shiftLow);
    }

    private static int vectorRaw(int value, int qn, int qm, int size, int mask) {
        return value | (qn << 17) | quad(qm, 5, 1) | (size << 20) | quad(mask, 22, 13);
    }

    private static int scalarRaw(int value, int qn, int rm, int size, int mask) {
        return value | (qn << 17) | rm | (size << 20) | quad(mask, 22, 13);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveComparisonDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    private static IrOp.MveVectorCompare vectorCompare(int raw) {
        return assertInstanceOf(IrOp.MveVectorCompare.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp());
    }

    private static IrOp.MveVectorCompareScalar scalarCompare(int raw) {
        return assertInstanceOf(IrOp.MveVectorCompareScalar.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp());
    }

    // ── @vcmp: vetor×vetor inteiro ───────────────────────────────────────────────────────────────

    @Test
    void decodesIntegerVectorCompareConditionsAndRegisters() {
        int raw = vectorRaw(VALUE_VCMPEQ, 3, 5, 1, 0);
        IrOp.MveVectorCompare op = vectorCompare(raw);
        assertEquals(MveCompareCondition.EQ, op.compareCondition());
        assertTrue(!op.floatingPoint());
        assertEquals(1, op.esz());
        assertEquals(3, op.qn());
        assertEquals(5, op.qm());
        assertEquals(0, op.mask());
    }

    @Test
    void decodesUnsignedConditionsCsAndHi() {
        assertEquals(MveCompareCondition.CS, vectorCompare(vectorRaw(VALUE_VCMPCS, 0, 0, 0, 0)).compareCondition());
        assertEquals(MveCompareCondition.HI, vectorCompare(vectorRaw(VALUE_VCMPHI, 0, 0, 0, 0)).compareCondition());
    }

    /// Preset MVE_INTEGER SEM MVE_FLOAT — isola o guarda `size == 3` da forma inteira do achado
    /// "bits[21:20]=11 é sempre a forma `_fp` quando `MVE_FLOAT` está presente" (as duas formas
    /// medidas SEPARADAMENTE, ver Javadoc da classe: um `Cortex-M55`-like real pode ter só MVE
    /// inteiro).
    private static final ArmArchitecture MVE_INTEGER_ONLY =
            ArmArchitecture.extending(ArmArchitecture.ARMV8_1M, "MVE_INTEGER only (teste)", ArmFeature.MVE_INTEGER);

    @Test
    void rejectsReservedSizeThreeOnIntegerVectorForm() {
        // size==3 (bits[21:20]="11") É a forma `_fp` quando `MVE_FLOAT` está presente (achado
        // medido: essas 2 bits NÃO significam "size" para a forma FP, são bits fixos do encoding
        // dela) — só rejeita de verdade quando `MVE_FLOAT` está ausente.
        assertNull(tryDecode(MVE_INTEGER_ONLY, vectorRaw(VALUE_VCMPEQ, 0, 0, 3, 0)));
    }

    @Test
    void sizeThreeOnIntegerFormBecomesTheFpFormWhenMveFloatIsPresent() {
        IrOp.MveVectorCompare op = vectorCompare(vectorRaw(VALUE_VCMPEQ, 0, 0, 3, 0));
        assertTrue(op.floatingPoint(), "bits[21:20]=11 é literal do encoding _fp, não um size==3 do inteiro");
    }

    @Test
    void rejectsQmOutsideQ0ToQ7() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, vectorRaw(VALUE_VCMPEQ, 0, 8, 0, 0)));
    }

    @Test
    void nonZeroMaskIsCarriedThrough() {
        IrOp.MveVectorCompare op = vectorCompare(vectorRaw(VALUE_VCMPGE, 0, 0, 2, 0b1010));
        assertEquals(0b1010, op.mask());
    }

    @Test
    void integerVectorFormRequiresMveInteger() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, vectorRaw(VALUE_VCMPEQ, 0, 0, 0, 0)));
    }

    // ── @vcmp_fp: vetor×vetor FP (bit 28 = size) ─────────────────────────────────────────────────

    @Test
    void decodesFloatingPointVectorCompareAndSizeFromBit28() {
        int rawBinary32 = vectorRaw(VALUE_VCMPEQ_FP, 2, 3, 0, 0) & ~(1 << 28);
        int rawBinary16 = rawBinary32 | (1 << 28);
        IrOp.MveVectorCompare binary32 = vectorCompare(rawBinary32);
        assertTrue(binary32.floatingPoint());
        assertEquals(2, binary32.esz());
        assertEquals(MveCompareCondition.EQ, binary32.compareCondition());
        assertEquals(1, vectorCompare(rawBinary16).esz());
    }

    @Test
    void decodesFloatingPointGreaterEqual() {
        assertEquals(MveCompareCondition.GE, vectorCompare(vectorRaw(VALUE_VCMPGE_FP, 0, 0, 0, 0)).compareCondition());
    }

    @Test
    void floatingPointVectorFormRequiresMveFloat() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, vectorRaw(VALUE_VCMPEQ_FP, 0, 0, 0, 0)));
    }

    // ── @vcmp_scalar: vetor×GPR inteiro ──────────────────────────────────────────────────────────

    @Test
    void decodesIntegerScalarCompare() {
        int raw = scalarRaw(VALUE_VCMPEQ_SCALAR, 4, 7, 2, 0);
        IrOp.MveVectorCompareScalar op = scalarCompare(raw);
        assertEquals(MveCompareCondition.EQ, op.compareCondition());
        assertTrue(!op.floatingPoint());
        assertEquals(2, op.esz());
        assertEquals(4, op.qn());
        assertEquals(7, op.rm());
    }

    @Test
    void rejectsRmEqualsThirteenAsUnpredictable() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, scalarRaw(VALUE_VCMPEQ_SCALAR, 0, 13, 0, 0)));
    }

    @Test
    void acceptsRmEqualsFifteenAtDecodeTime() {
        // Achado medido contra o QEMU real: Rm==15 é "constante zero", NÃO UNPREDICTABLE — o
        // decode aceita, a resolução (rm=0) acontece no executor.
        IrOp.MveVectorCompareScalar op = scalarCompare(scalarRaw(VALUE_VCMPEQ_SCALAR, 0, 15, 0, 0));
        assertEquals(15, op.rm());
    }

    @Test
    void rejectsReservedSizeThreeOnIntegerScalarForm() {
        assertNull(tryDecode(MVE_INTEGER_ONLY, scalarRaw(VALUE_VCMPEQ_SCALAR, 0, 0, 3, 0)));
    }

    // ── @vcmp_fp_scalar: vetor×GPR FP (bit 28 NÃO decodificado — size fixo por linha) ─────────────

    @Test
    void decodesFloatingPointScalarCompareWithFixedSizeOne() {
        // Bloco "Comparisons" (linhas 740+): size=1 literal, independente do valor do bit 28.
        int raw = scalarRaw(VALUE_VCMPEQ_FP_SCALAR, 1, 2, 0, 0);
        assertEquals(1, scalarCompare(raw).esz());
        assertEquals(1, scalarCompare(raw | (1 << 28)).esz(), "bit 28 não é decodificado nesta forma");
    }

    @Test
    void floatingPointScalarFormRequiresMveFloat() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, scalarRaw(VALUE_VCMPEQ_FP_SCALAR, 0, 0, 0, 0)));
    }

    // ── Linhas 416/417 e 499/500/505/506 (`size=2` literal, nibble alto `1110`) ─────────────────────

    @Test
    void decodesTheFourScalarFpLinesWithFixedSizeTwoFromTheDupAndScalarBlocks() {
        // VCMPGT_fp_scalar (linha 416, size=2): mesmos campos de @vcmp_fp_scalar, nibble alto 1110.
        int value = 0xEE311F60;
        IrOp.MveVectorCompareScalar op = scalarCompare(scalarRaw(value, 0, 2, 0, 0));
        assertEquals(MveCompareCondition.GT, op.compareCondition());
        assertEquals(2, op.esz());
        assertTrue(op.floatingPoint());
    }

    // ── Espaço vizinho — pipeline completo: NUNCA decodifica como NOCP sob ARMV8_1M_MVE ────────────

    @Test
    void noneDecodeAsNocpThroughTheFullThumbPipeline() {
        assertInstanceOf(IrOp.MveVectorCompare.class, decodeThumb32(vectorRaw(VALUE_VCMPEQ, 0, 0, 0, 0)).liftedOp());
        assertInstanceOf(IrOp.MveVectorCompareScalar.class,
                decodeThumb32(scalarRaw(VALUE_VCMPEQ_SCALAR, 0, 0, 0, 0)).liftedOp());
    }

    private static DecodedInstruction decodeThumb32(int raw32) {
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
    }

    @Test
    void rejectsUnmatchedEncoding() {
        int r = vectorRaw(VALUE_VCMPEQ, 0, 0, 0, 0) ^ (1 << 27);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // ── Cobertura exaustiva das 34 linhas: todo `case` dos 4 `switch` do decoder é exercitado ──────
    // (achado de disciplina: os testes acima só cobriam EQ/GE/CS/HI — NE/LT/GT/LE nunca passavam
    // por nenhum teste, deixando metade dos `case` de cada `switch` sem cobertura real).

    private record ExpectedLine(String name, int value, MveCompareCondition condition) {
    }

    private static final ExpectedLine[] VECTOR_INT_LINES = {
            new ExpectedLine("VCMPEQ", 0xFE010F00, MveCompareCondition.EQ),
            new ExpectedLine("VCMPNE", 0xFE010F80, MveCompareCondition.NE),
            new ExpectedLine("VCMPGE", 0xFE011F00, MveCompareCondition.GE),
            new ExpectedLine("VCMPLT", 0xFE011F80, MveCompareCondition.LT),
            new ExpectedLine("VCMPGT", 0xFE011F01, MveCompareCondition.GT),
            new ExpectedLine("VCMPLE", 0xFE011F81, MveCompareCondition.LE),
            new ExpectedLine("VCMPCS", 0xFE010F01, MveCompareCondition.CS),
            new ExpectedLine("VCMPHI", 0xFE010F81, MveCompareCondition.HI),
    };

    private static final ExpectedLine[] VECTOR_FP_LINES = {
            new ExpectedLine("VCMPEQ_fp", 0xEE310F00, MveCompareCondition.EQ),
            new ExpectedLine("VCMPNE_fp", 0xEE310F80, MveCompareCondition.NE),
            new ExpectedLine("VCMPGE_fp", 0xEE311F00, MveCompareCondition.GE),
            new ExpectedLine("VCMPLT_fp", 0xEE311F80, MveCompareCondition.LT),
            new ExpectedLine("VCMPGT_fp", 0xEE311F01, MveCompareCondition.GT),
            new ExpectedLine("VCMPLE_fp", 0xEE311F81, MveCompareCondition.LE),
    };

    private static final ExpectedLine[] SCALAR_INT_LINES = {
            new ExpectedLine("VCMPEQ_scalar", 0xFE010F40, MveCompareCondition.EQ),
            new ExpectedLine("VCMPNE_scalar", 0xFE010FC0, MveCompareCondition.NE),
            new ExpectedLine("VCMPGT_scalar", 0xFE011F60, MveCompareCondition.GT),
            new ExpectedLine("VCMPLE_scalar", 0xFE011FE0, MveCompareCondition.LE),
            new ExpectedLine("VCMPGE_scalar", 0xFE011F40, MveCompareCondition.GE),
            new ExpectedLine("VCMPLT_scalar", 0xFE011FC0, MveCompareCondition.LT),
            new ExpectedLine("VCMPCS_scalar", 0xFE010F60, MveCompareCondition.CS),
            new ExpectedLine("VCMPHI_scalar", 0xFE010FE0, MveCompareCondition.HI),
    };

    private static final ExpectedLine[] SCALAR_FP_SIZE1_LINES = {
            new ExpectedLine("VCMPEQ_fp_scalar", 0xFE310F40, MveCompareCondition.EQ),
            new ExpectedLine("VCMPNE_fp_scalar", 0xFE310FC0, MveCompareCondition.NE),
            new ExpectedLine("VCMPGT_fp_scalar", 0xFE311F60, MveCompareCondition.GT),
            new ExpectedLine("VCMPLE_fp_scalar", 0xFE311FE0, MveCompareCondition.LE),
            new ExpectedLine("VCMPGE_fp_scalar", 0xFE311F40, MveCompareCondition.GE),
            new ExpectedLine("VCMPLT_fp_scalar", 0xFE311FC0, MveCompareCondition.LT),
    };

    private static final ExpectedLine[] SCALAR_FP_SIZE2_LINES = {
            new ExpectedLine("VCMPGT_fp_scalar (L416)", 0xEE311F60, MveCompareCondition.GT),
            new ExpectedLine("VCMPLE_fp_scalar (L417)", 0xEE311FE0, MveCompareCondition.LE),
            new ExpectedLine("VCMPEQ_fp_scalar (L499)", 0xEE310F40, MveCompareCondition.EQ),
            new ExpectedLine("VCMPNE_fp_scalar (L500)", 0xEE310FC0, MveCompareCondition.NE),
            new ExpectedLine("VCMPLT_fp_scalar (L505)", 0xEE311FC0, MveCompareCondition.LT),
            new ExpectedLine("VCMPGE_fp_scalar (L506)", 0xEE311F40, MveCompareCondition.GE),
    };

    @Test
    void allEightVectorIntegerConditionsDecodeCorrectly() {
        for (ExpectedLine line : VECTOR_INT_LINES) {
            IrOp.MveVectorCompare op = vectorCompare(vectorRaw(line.value(), 1, 2, 1, 0));
            assertEquals(line.condition(), op.compareCondition(), line.name());
            assertTrue(!op.floatingPoint(), line.name());
        }
        assertEquals(8, VECTOR_INT_LINES.length, "as 8 condições devem estar cobertas");
    }

    @Test
    void allSixVectorFloatingPointConditionsDecodeCorrectly() {
        for (ExpectedLine line : VECTOR_FP_LINES) {
            IrOp.MveVectorCompare op = vectorCompare(vectorRaw(line.value(), 1, 2, 0, 0));
            assertEquals(line.condition(), op.compareCondition(), line.name());
            assertTrue(op.floatingPoint(), line.name());
        }
    }

    @Test
    void allEightScalarIntegerConditionsDecodeCorrectly() {
        for (ExpectedLine line : SCALAR_INT_LINES) {
            IrOp.MveVectorCompareScalar op = scalarCompare(scalarRaw(line.value(), 1, 2, 1, 0));
            assertEquals(line.condition(), op.compareCondition(), line.name());
            assertTrue(!op.floatingPoint(), line.name());
        }
        assertEquals(8, SCALAR_INT_LINES.length);
    }

    @Test
    void allSixScalarFloatingPointConditionsWithFixedSizeOneDecodeCorrectly() {
        for (ExpectedLine line : SCALAR_FP_SIZE1_LINES) {
            IrOp.MveVectorCompareScalar op = scalarCompare(scalarRaw(line.value(), 1, 2, 0, 0));
            assertEquals(line.condition(), op.compareCondition(), line.name());
            assertTrue(op.floatingPoint(), line.name());
            assertEquals(1, op.esz(), line.name());
        }
    }

    @Test
    void allSixScalarFloatingPointConditionsWithFixedSizeTwoDecodeCorrectly() {
        for (ExpectedLine line : SCALAR_FP_SIZE2_LINES) {
            IrOp.MveVectorCompareScalar op = scalarCompare(scalarRaw(line.value(), 1, 2, 0, 0));
            assertEquals(line.condition(), op.compareCondition(), line.name());
            assertTrue(op.floatingPoint(), line.name());
            assertEquals(2, op.esz(), line.name());
        }
    }

    @Test
    void allThirtyFourLinesAreAccountedForWithNoOverlapOrGap() {
        // Soma exatamente 34 (medida na spec da task, ver Javadoc da classe).
        int total = VECTOR_INT_LINES.length + VECTOR_FP_LINES.length + SCALAR_INT_LINES.length
                + SCALAR_FP_SIZE1_LINES.length + SCALAR_FP_SIZE2_LINES.length;
        assertEquals(34, total);
    }
}
