package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B16.7 sub-família 3 — `VADD_fp`…`VFMS` (`@2op_fp`) e `VCADD90_fp`/`VCADD270_fp`/`VCMLA0/90/180/270`
/// (`@2op_fp_size_rev`), fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV8_1M_MVE`. Foco no que é NOVO desta sub-família: o gancho PREDICADO por byte sobre o núcleo
/// FP já existente (`fpThreeSame`/`fpComplexAdd`/`fpComplexMultiplyAccumulate`, reusados sem
/// alteração), e a convenção de `size` REVERSA de `@2op_fp_size_rev` vs a DIRETA de `@2op_fp`.
class MveVectorFpTwoOpExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static final int VALUE_VADD_FP = 0xEF000D40;
    private static final int VALUE_VFMA = 0xEF000C50;
    private static final int VALUE_VCADD90_FP = 0xFC800840;
    private static final int VALUE_VCADD270_FP = 0xFD800840;
    private static final int VALUE_VCMLA0 = 0xFC200840;
    private static final int VALUE_VCMLA90 = 0xFCA00840;

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

    /// MESMO layout do Javadoc de `Thumb2MveVector2opFpDecoder`/`Thumb2MveVector2opFpDecoderTest`.
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

    private static float single(ArmCore core, int q, int lane) {
        return Float.intBitsToFloat((int) core.vfp().element(q, lane, 2));
    }

    private static void setSingle(ArmCore core, int q, int lane, float value) {
        core.vfp().setElement(q, lane, 2, Float.floatToRawIntBits(value) & 0xFFFF_FFFFL);
    }

    // ── @2op_fp: VADD_fp/VFMA (reuso do núcleo fpThreeSame já existente) ────────────────────────────

    @Test
    void vaddFpAddsCorrespondingSingleLanes() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 2.0f);
        setSingle(core, 3, 0, 3.0f);
        // VADD_fp, size=0 (bit20=0 -> binary32), Qd=1, Qn=2, Qm=3.
        int r = raw(VALUE_VADD_FP, 1, 2, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5.0f, single(core, 1, 0));
    }

    @Test
    void vfmaFusedMultiplyAccumulatesIntoCurrentQd() {
        ArmCore core = newCore();
        setSingle(core, 1, 0, 1.0f); // acumulador atual.
        setSingle(core, 2, 0, 2.0f);
        setSingle(core, 3, 0, 3.0f);
        int r = raw(VALUE_VFMA, 1, 2, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(7.0f, single(core, 1, 0), "1 + 2*3 fundido");
    }

    @Test
    void twoOpSizeBitSelectsBinary16Direct() {
        ArmCore core = newCore();
        // bit20=1 -> binary16 (esz=1): 1.5 + 2.5 = 4.0, representável exatamente em binary16.
        core.vfp().setElement(2, 0, 1, 0x3E00L); // half(1.5).
        core.vfp().setElement(3, 0, 1, 0x4100L); // half(2.5).
        int r = raw(VALUE_VADD_FP, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x4400L, core.vfp().element(1, 0, 1), "half(4.0)");
    }

    // ── Predicação (o gancho NOVO desta sub-família sobre o núcleo FP já existente) ─────────────────

    @Test
    void predicationPreservesInactiveSingleLane() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 2.0f);
        setSingle(core, 2, 1, 20.0f);
        setSingle(core, 3, 0, 3.0f);
        setSingle(core, 3, 1, 30.0f);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L); // Qd inicial: todo-1, para ver a preservação.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só a lane 0 (word, 4 bytes baixos) ativa.
        int r = raw(VALUE_VADD_FP, 1, 2, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5.0f, single(core, 1, 0), "lane 0 ativa: 2+3=5");
        assertEquals(0xFFFF_FFFFL, core.vfp().element(1, 1, 2), "lane 1 mascarada: preserva 0xFFFFFFFF");
    }

    // ── @2op_fp_size_rev: VCADD90_fp/VCADD270_fp (mesma fórmula FComplexAddImpl de FEAT_FCMA) ────────

    @Test
    void vcadd90RotatesRealMinusImagAndImagPlusReal() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 10.0f); // Qn real.
        setSingle(core, 2, 1, 1.0f); // Qn imaginário.
        setSingle(core, 3, 0, 2.0f); // Qm real.
        setSingle(core, 3, 1, 5.0f); // Qm imaginário.
        // VCADD90_fp: size=1 -> bit20+1=1 (binary16)? Não — usamos bit20=1 -> esz=2 (binary32).
        int r = raw(VALUE_VCADD90_FP, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5.0f, single(core, 1, 0), "re' = aRe - bIm = 10 - 5");
        assertEquals(3.0f, single(core, 1, 1), "im' = aIm + bRe = 1 + 2");
    }

    @Test
    void vcadd270RotatesOppositeDirection() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 10.0f);
        setSingle(core, 2, 1, 1.0f);
        setSingle(core, 3, 0, 2.0f);
        setSingle(core, 3, 1, 5.0f);
        int r = raw(VALUE_VCADD270_FP, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(15.0f, single(core, 1, 0), "re' = aRe + bIm = 10 + 5");
        assertEquals(-1.0f, single(core, 1, 1), "im' = aIm - bRe = 1 - 2");
    }

    @Test
    void complexAddSizeRevBit20ZeroIsBinary16() {
        ArmCore core = newCore();
        // %2op_fp_size_rev: bit20+1 -> bit20=0 é binary16.
        core.vfp().setElement(2, 0, 1, 0x4200L); // half(3.0), real.
        core.vfp().setElement(2, 1, 1, 0x3C00L); // half(1.0), imaginário.
        core.vfp().setElement(3, 0, 1, 0x4000L); // half(2.0), real.
        core.vfp().setElement(3, 1, 1, 0x4400L); // half(4.0), imaginário.
        int r = raw(VALUE_VCADD90_FP, 1, 2, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xBC00L, core.vfp().element(1, 0, 1), "re' = 3 - 4 = -1 (half)");
        assertEquals(0x4200L, core.vfp().element(1, 1, 1), "im' = 1 + 2 = 3 (half)");
    }

    // ── @2op_fp_size_rev: VCMLA0/90 (mesma fórmula FComplexMulAdd de FEAT_FCMA) ─────────────────────

    @Test
    void vcmla0AccumulatesRealTimesRealAndRealTimesImag() {
        ArmCore core = newCore();
        setSingle(core, 1, 0, 100.0f); // dRe atual.
        setSingle(core, 1, 1, 200.0f); // dIm atual.
        setSingle(core, 2, 0, 2.0f); // aRe.
        setSingle(core, 2, 1, 3.0f); // aIm (não usado por rotação 0).
        setSingle(core, 3, 0, 5.0f); // bRe.
        setSingle(core, 3, 1, 7.0f); // bIm.
        int r = raw(VALUE_VCMLA0, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(110.0f, single(core, 1, 0), "dRe += aRe*bRe = 100 + 2*5");
        assertEquals(214.0f, single(core, 1, 1), "dIm += aRe*bIm = 200 + 2*7");
    }

    @Test
    void vcmla90AccumulatesNegatedImagTimesImagAndImagTimesReal() {
        ArmCore core = newCore();
        setSingle(core, 1, 0, 100.0f);
        setSingle(core, 1, 1, 200.0f);
        setSingle(core, 2, 0, 2.0f); // aRe (não usado por rotação 90).
        setSingle(core, 2, 1, 3.0f); // aIm.
        setSingle(core, 3, 0, 5.0f); // bRe.
        setSingle(core, 3, 1, 7.0f); // bIm.
        int r = raw(VALUE_VCMLA90, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(79.0f, single(core, 1, 0), "dRe -= aIm*bIm = 100 - 3*7");
        assertEquals(215.0f, single(core, 1, 1), "dIm += aIm*bRe = 200 + 3*5");
    }

    @Test
    void complexPredicationPreservesInactivePairEntirely() {
        ArmCore core = newCore();
        setSingle(core, 1, 0, 100.0f);
        setSingle(core, 1, 1, 200.0f);
        setSingle(core, 1, 2, 9.0f);
        setSingle(core, 1, 3, 9.0f);
        setSingle(core, 2, 0, 2.0f);
        setSingle(core, 2, 1, 3.0f);
        setSingle(core, 3, 0, 5.0f);
        setSingle(core, 3, 1, 7.0f);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00FF); // só o par 0 (word 0 + word 1, bytes 0-7) ativo; par 1 mascarado.
        int r = raw(VALUE_VCMLA0, 1, 2, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(110.0f, single(core, 1, 0), "par 0 ativo: escreve normalmente");
        assertEquals(214.0f, single(core, 1, 1));
        assertEquals(9.0f, single(core, 1, 2), "par 1 mascarado: preserva");
        assertEquals(9.0f, single(core, 1, 3), "par 1 mascarado: preserva");
    }
}
