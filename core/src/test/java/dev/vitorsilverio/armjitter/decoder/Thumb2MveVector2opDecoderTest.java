package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.6 — `Thumb2MveVector2opDecoder`: vector 2-op inteiro (`target/isa-decode/mve.decode`,
/// linhas 211-219/281-366, 48 encodings). Raws construídos bit a bit com o MESMO layout do
/// Javadoc da classe (`bits[31:29]=111`, `bit28=U`, `bits[27:24]`, `bit23=0`, `Qd`(22,15:13),
/// `bits[21:20]`, `Qn`(19:17,7), `bit16`, `bit12`, nibble(11:8), `bit6`, `Qm`(5,3:1), `bit4`,
/// `bit0=0`).
class Thumb2MveVector2opDecoderTest {
    private static int raw(int u, int top24, int qd, int field2120, int qn, int bit16, int bit12, int nibble,
            int bit6, int qm, int bit4) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29)
                | (u << 28)
                | (top24 << 24)
                | (0 << 23)
                | (qdHigh << 22)
                | (field2120 << 20)
                | (qnLow << 17)
                | (bit16 << 16)
                | (qdLow << 13)
                | (bit12 << 12)
                | (nibble << 8)
                | (qnHigh << 7)
                | (bit6 << 6)
                | (qmHigh << 5)
                | (bit4 << 4)
                | (qmLow << 1);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveVector2opDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── Bloco 1: lógica (@2op_nosz) ─────────────────────────────────────────────────────────────

    @Test
    void decodesVandWithSize0Forced() {
        int r = raw(0, 0b1111, 1, 0b00, 2, 0, 0, 0b0001, 1, 3, 1);
        IrOp.MveVector2Op op = assertInstanceOf(IrOp.MveVector2Op.class, tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(AdvSimdThreeSameOp.AND, op.op());
        assertEquals(0, op.esz());
        assertEquals(1, op.qd());
        assertEquals(2, op.qn());
        assertEquals(3, op.qm());
    }

    @Test
    void decodesVbicVorrVorn() {
        assertEquals(AdvSimdThreeSameOp.BIC,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0001, 1, 0, 1)).liftedOp()).op());
        assertEquals(AdvSimdThreeSameOp.ORR,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(0, 0b1111, 0, 0b10, 0, 0, 0, 0b0001, 1, 0, 1)).liftedOp()).op());
        assertEquals(AdvSimdThreeSameOp.ORN,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(0, 0b1111, 0, 0b11, 0, 0, 0, 0b0001, 1, 0, 1)).liftedOp()).op());
    }

    @Test
    void decodesVeorWithBit28AndTop24Set() {
        int r = raw(1, 0b1111, 0, 0b00, 0, 0, 0, 0b0001, 1, 0, 1);
        IrOp.MveVector2Op op = assertInstanceOf(IrOp.MveVector2Op.class, tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(AdvSimdThreeSameOp.EOR, op.op());
    }

    // ── Bloco 1: ADD/SUB/MUL (@2op) ─────────────────────────────────────────────────────────────

    @Test
    void decodesVaddVsubVmulWithRealSizeField() {
        assertEquals(AdvSimdThreeSameOp.ADD,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(0, 0b1111, 0, 0b10, 0, 0, 0, 0b1000, 1, 0, 0)).liftedOp()).op());
        assertEquals(AdvSimdThreeSameOp.SUB,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(1, 0b1111, 0, 0b10, 0, 0, 0, 0b1000, 1, 0, 0)).liftedOp()).op());
        assertEquals(AdvSimdThreeSameOp.MUL,
                ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE,
                        raw(0, 0b1111, 0, 0b10, 0, 0, 0, 0b1001, 1, 0, 1)).liftedOp()).op());
    }

    // ── Bloco 2: min/max/abd/halving/saturantes ─────────────────────────────────────────────────

    @Test
    void decodesMaxMinAbdHaddHsubQaddQsub() {
        assertEquals(AdvSimdThreeSameOp.SMAX, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0110, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.UMAX, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0110, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SMIN, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0110, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.UMIN, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0110, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.SABD, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0111, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.UABD, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0111, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SHADD, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0000, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.UHADD, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0000, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SQADD, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0000, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.UQADD, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0000, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.SHSUB, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0010, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SQSUB, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0010, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.SQDMULH, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b1011, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SQRDMULH, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b1011, 1, 0, 0)));
    }

    @Test
    void decodesRhaddDistinctFromLogicAtSameNibble() {
        // Mesmo nibble 0001 da lógica, mas bit4=0 (não 1) -> VRHADD, não VAND/VEOR.
        assertEquals(AdvSimdThreeSameOp.SRHADD, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0001, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.URHADD, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0001, 1, 0, 0)));
    }

    @Test
    void rejectsSizeThreeOnTwoOpForm() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b1111, 0, 0b11, 0, 0, 0, 0b0000, 1, 0, 0)));
    }

    // ── @2op_rev: Qn/Qm trocados de propósito (Armadilha da task) ──────────────────────────────

    @Test
    void shiftFormsSwapQnAndQmPerTheRevConvention() {
        // VSHL_S Qd=1, campo de bit-posição "%qn" (7,19:17) do encoding=5, campo "%qm" (5,3:1)=6.
        // @2op_rev: struct.qn=%qm(encoding)=6, struct.qm=%qn(encoding)=5 — o executor lê `op.qn()`
        // como VALOR (`a`/`sa` de threeSame) e `op.qm()` como CONTAGEM (`b`), então o VALOR
        // deslocado é o registrador na posição de bits "%qm" (6), e a contagem vem da posição
        // "%qn" (5) — exatamente o que o comentário do `mve.decode` real descreve.
        int r = raw(0, 0b1111, 1, 0b01, 5, 0, 0, 0b0100, 1, 6, 0);
        IrOp.MveVector2Op op = assertInstanceOf(IrOp.MveVector2Op.class, tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(AdvSimdThreeSameOp.SSHL, op.op());
        assertEquals(6, op.qn(), "posição de bits '%qm' (6) vira op.qn() do IR = registrador do VALOR deslocado");
        assertEquals(5, op.qm(), "posição de bits '%qn' (5) vira op.qm() do IR = registrador da CONTAGEM de deslocamento");
    }

    @Test
    void decodesQshlAndRshlFamilies() {
        assertEquals(AdvSimdThreeSameOp.URSHL, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0101, 1, 0, 0)));
        assertEquals(AdvSimdThreeSameOp.SQSHL, opOf(raw(0, 0b1111, 0, 0b01, 0, 0, 0, 0b0100, 1, 0, 1)));
        assertEquals(AdvSimdThreeSameOp.UQRSHL, opOf(raw(1, 0b1111, 0, 0b01, 0, 0, 0, 0b0101, 1, 0, 1)));
    }

    // ── Bloco VMULL/VMULLP (bit16=1) ────────────────────────────────────────────────────────────

    @Test
    void decodesIntegerWideningMultiplyBottomAndTop() {
        // VMULL_BS: bit28=0(S), size real=1(halfword), bit12=0(bottom).
        int rb = raw(0, 0b1110, 0, 0b01, 0, 1, 0, 0b1110, 0, 0, 0);
        IrOp.MveVector2OpWidening b = assertInstanceOf(IrOp.MveVector2OpWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rb).liftedOp());
        assertEquals(AdvSimdWideningOp.SMULL, b.op());
        assertEquals(1, b.esz());
        assertEquals(false, b.top());

        // VMULL_TU: bit28=1(U), size real=2(word), bit12=1(top).
        int rt = raw(1, 0b1110, 0, 0b10, 0, 1, 1, 0b1110, 0, 0, 0);
        IrOp.MveVector2OpWidening t = assertInstanceOf(IrOp.MveVector2OpWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rt).liftedOp());
        assertEquals(AdvSimdWideningOp.UMULL, t.op());
        assertEquals(2, t.esz());
        assertEquals(true, t.top());
    }

    @Test
    void decodesPolynomialWideningMultiplyWithSourceEszFromBit28() {
        // VMULLP_B: bits[21:20]="11" literal, bit28=0 -> esz FONTE = 0 (byte->halfword).
        int rb = raw(0, 0b1110, 0, 0b11, 0, 1, 0, 0b1110, 0, 0, 0);
        IrOp.MveVector2OpWidening b = assertInstanceOf(IrOp.MveVector2OpWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rb).liftedOp());
        assertEquals(AdvSimdWideningOp.PMULL, b.op());
        assertEquals(0, b.esz());
        assertEquals(false, b.top());

        // VMULLP_T: bit28=1 -> esz FONTE = 1 (halfword->word).
        int rt = raw(1, 0b1110, 0, 0b11, 0, 1, 1, 0b1110, 0, 0, 0);
        IrOp.MveVector2OpWidening t = assertInstanceOf(IrOp.MveVector2OpWidening.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rt).liftedOp());
        assertEquals(AdvSimdWideningOp.PMULL, t.op());
        assertEquals(1, t.esz());
        assertEquals(true, t.top());
    }

    // ── Bloco carry/soma complexa (bit16=0 sob top24=1110) ──────────────────────────────────────

    @Test
    void decodesVadcVadciVsbcVsbci() {
        IrOp.MveVectorCarry vadc = assertInstanceOf(IrOp.MveVectorCarry.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b1110, 0, 0b11, 0, 0, 0, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(true, vadc.add());
        assertEquals(false, vadc.immediateCarry());

        IrOp.MveVectorCarry vadci = assertInstanceOf(IrOp.MveVectorCarry.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b1110, 0, 0b11, 0, 0, 1, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(true, vadci.add());
        assertEquals(true, vadci.immediateCarry());

        IrOp.MveVectorCarry vsbc = assertInstanceOf(IrOp.MveVectorCarry.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(1, 0b1110, 0, 0b11, 0, 0, 0, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(false, vsbc.add());
        assertEquals(false, vsbc.immediateCarry());

        IrOp.MveVectorCarry vsbci = assertInstanceOf(IrOp.MveVectorCarry.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(1, 0b1110, 0, 0b11, 0, 0, 1, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(false, vsbci.add());
        assertEquals(true, vsbci.immediateCarry());
    }

    @Test
    void decodesVhcaddAndVcaddRotations() {
        IrOp.MveVectorComplexAdd hcadd90 = assertInstanceOf(IrOp.MveVectorComplexAdd.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b1110, 0, 0b01, 0, 0, 0, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(true, hcadd90.rotate90());
        assertEquals(true, hcadd90.halving());

        IrOp.MveVectorComplexAdd hcadd270 = assertInstanceOf(IrOp.MveVectorComplexAdd.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b1110, 0, 0b01, 0, 0, 1, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(false, hcadd270.rotate90());
        assertEquals(true, hcadd270.halving());

        IrOp.MveVectorComplexAdd cadd90 = assertInstanceOf(IrOp.MveVectorComplexAdd.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(1, 0b1110, 0, 0b01, 0, 0, 0, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(true, cadd90.rotate90());
        assertEquals(false, cadd90.halving());

        IrOp.MveVectorComplexAdd cadd270 = assertInstanceOf(IrOp.MveVectorComplexAdd.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(1, 0b1110, 0, 0b01, 0, 0, 1, 0b1111, 0, 0, 0)).liftedOp());
        assertEquals(false, cadd270.rotate90());
        assertEquals(false, cadd270.halving());
    }

    // ── Rejeições gerais ─────────────────────────────────────────────────────────────────────────

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = raw(0, 0b1111, 0, 0b00, 0, 0, 0, 0b0001, 1, 0, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    @Test
    void rejectsQuadRegisterOutsideQ0ToQ7() {
        int r = raw(0, 0b1111, 8, 0b00, 0, 0, 0, 0b0001, 1, 0, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsBit0Set() {
        int r = raw(0, 0b1111, 0, 0b00, 0, 0, 0, 0b0001, 1, 0, 1) | 1;
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // ── Pipeline completo: não vira NOCP sob ARMV8_1M_MVE (Armadilha 1) ─────────────────────────

    @Test
    void decodesAsMveVector2OpThroughTheFullThumbPipeline() {
        int r = raw(0, 0b1111, 1, 0b00, 2, 0, 0, 0b0001, 1, 3, 1);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveVector2Op.class, decoded.liftedOp());
    }

    private static AdvSimdThreeSameOp opOf(int raw) {
        return ((IrOp.MveVector2Op) tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw).liftedOp()).op();
    }
}
