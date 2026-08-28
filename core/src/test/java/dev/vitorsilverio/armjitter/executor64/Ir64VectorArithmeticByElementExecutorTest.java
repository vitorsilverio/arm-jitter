package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica dos ops de AdvSIMD "vector/scalar × indexed element" (B8.19) direto no executor
/// (interpretador = oráculo, G1) — complementa {@code Aarch64AdvSimdIndexedElementDecoderTest}
/// (decode). O ponto central testado em cada família: `Rm` contribui SEMPRE o mesmo elemento
/// {@code index}, replicado em toda operação — nunca `Rm[lane]` como em
/// {@link Ir64Op.VectorArithmeticThreeSame}/{@link Ir64Op.VectorArithmeticWidening}.
class Ir64VectorArithmeticByElementExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void mulByElementReplicatesSameRmElementAcrossLanes() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 3); // halfword v1[0] = 3
        fp.setElement(1, 1, 1, 5); // halfword v1[1] = 5
        fp.setElement(2, 0, 1, 10); // halfword v2[0] = 10 (NUNCA lido: index=1)
        fp.setElement(2, 1, 1, 7); // halfword v2[1] = 7 (índice usado nas duas lanes)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSameByElement(
                Ir64VectorThreeSameOp.MUL, false, false, 1, 0, 1, 2, 1));

        assertEquals(3 * 7, fp.element(0, 0, 1), "v1[0] * v2[1] (índice fixo), não v2[0]");
        assertEquals(5 * 7, fp.element(0, 1, 1), "v1[1] * v2[1] (MESMO índice), não v2[1] lane-a-lane");
    }

    @Test
    void mlaByElementAccumulatesIntoExistingElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 2, 1000); // word v0[0] = 1000 (acumulador)
        fp.setElement(1, 0, 2, 3);
        fp.setElement(2, 2, 2, 4); // word v2[2] = 4 (índice usado)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSameByElement(
                Ir64VectorThreeSameOp.MLA, false, false, 2, 0, 1, 2, 2));

        assertEquals(1000 + 3 * 4, fp.element(0, 0, 2), "1000 + 3*4 = 1012");
    }

    @Test
    void mlsByElementSubtracts() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 100);
        fp.setElement(1, 0, 1, 3);
        fp.setElement(2, 5, 1, 4);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSameByElement(
                Ir64VectorThreeSameOp.MLS, false, false, 1, 0, 1, 2, 5));

        assertEquals(100 - 3 * 4, fp.element(0, 0, 1));
    }

    @Test
    void sqdmulhByElementVector() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Word (esz=2): SQDMULH = (2*a*b) >> 32, sem saturar aqui (valores escolhidos para caber).
        fp.setElement(1, 0, 2, 0x4000_0000); // 2^30
        fp.setElement(2, 3, 2, 4); // índice usado

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSameByElement(
                Ir64VectorThreeSameOp.SQDMULH, false, false, 2, 0, 1, 2, 3));

        assertEquals(2L, fp.element(0, 0, 2), "(2 * 2^30 * 4) >> 32 = 2");
    }

    @Test
    void sqdmulhByElementScalarProcessesOnlyElementZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujeira pré-existente
        fp.setElement(1, 0, 1, 100); // halfword v1[0] = 100
        fp.setElement(2, 4, 1, 50); // halfword v2[4] = 50 (índice usado)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSameByElement(
                Ir64VectorThreeSameOp.SQDMULH, true, false, 1, 0, 1, 2, 4));

        long expected = (2L * 100 * 50) >> 16;
        assertEquals(expected, fp.element(0, 0, 1));
        assertEquals(0L, fp.high64(0), "forma escalar zera tudo acima do elemento (destructive write)");
    }

    @Test
    void smullByElementWidensAndReplicatesRmIndex() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, (short) -2 & 0xFFFF); // halfword v1[0] = -2 (assinado)
        fp.setElement(1, 1, 1, 3); // halfword v1[1] = 3
        fp.setElement(2, 6, 1, 5); // halfword v2[6] = 5 (índice usado nas duas lanes)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWideningByElement(
                Ir64VectorWideningOp.SMULL, false, false, 1, 0, 1, 2, 6));

        assertEquals((int) (-2 * 5) & 0xFFFF_FFFFL, fp.element(0, 0, 2), "sext(-2)*5 = -10");
        assertEquals(3 * 5, fp.element(0, 1, 2), "3*5 = 15, MESMO índice de Rm");
    }

    @Test
    void umlalByElementAccumulatesUnsigned() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 2, 1000); // word largo v0[0] = 1000 (acumulador)
        fp.setElement(1, 0, 1, 0xFFFF); // halfword v1[0] = 0xFFFF (não assinado)
        fp.setElement(2, 1, 1, 2);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWideningByElement(
                Ir64VectorWideningOp.UMLAL, false, false, 1, 0, 1, 2, 1));

        assertEquals(1000 + 0xFFFFL * 2, fp.element(0, 0, 2));
    }

    @Test
    void sqdmullByElementScalarProducesSingleWideElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujeira pré-existente
        fp.setElement(1, 0, 1, 3); // halfword v1[0] = 3 (Hn escalar)
        fp.setElement(2, 2, 1, 4); // halfword v2[2] = 4 (índice usado)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWideningByElement(
                Ir64VectorWideningOp.SQDMULL, true, false, 1, 0, 1, 2, 2));

        assertEquals(2L * 3 * 4, fp.element(0, 0, 2), "SignedSaturate(2*3*4) = 24, sem overflow");
        assertEquals(0L, fp.high64(0), "forma escalar zera tudo acima do elemento largo (destructive write)");
    }

    @Test
    void fmulByElementVectorSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 3, 2, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL); // índice usado

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSameByElement(
                Ir64VectorFpThreeSameOp.MUL, false, true, 2, 0, 1, 2, 3));

        assertEquals(3.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(6.0f, Float.intBitsToFloat((int) fp.element(0, 1, 2)), "MESMO índice de Rm nas duas lanes");
    }

    @Test
    void fmlaByElementVectorDoubleFusedMultiplyAdd() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 3, Double.doubleToRawLongBits(1.0));
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(2.0));
        fp.setElement(2, 1, 3, Double.doubleToRawLongBits(3.0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSameByElement(
                Ir64VectorFpThreeSameOp.MLA, false, false, 3, 0, 1, 2, 1));

        assertEquals(7.0, Double.longBitsToDouble(fp.element(0, 0, 3)), "1.0 + 2.0*3.0 = 7.0 (fma)");
    }

    @Test
    void fmulxByElementScalarProcessesOnlyElementZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(0.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 2, 2, Float.floatToRawIntBits(Float.POSITIVE_INFINITY) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSameByElement(
                Ir64VectorFpThreeSameOp.MULX, true, false, 2, 0, 1, 2, 2));

        assertEquals(2.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "FPMulX(0, +Inf) = 2.0");
        assertEquals(0L, fp.high64(0));
    }
}
