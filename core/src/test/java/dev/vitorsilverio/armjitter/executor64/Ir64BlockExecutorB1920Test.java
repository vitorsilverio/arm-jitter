package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.20 (`FEAT_FCMA`) — semântica de `FCADD_90`/`FCADD_270`/`FCMLA_v`/`FCMLA_vi` direto no
/// executor (interpretador = oráculo, G1). O núcleo (`AdvSimdLanes.fpComplexAdd`/
/// `fpComplexMultiplyAccumulate*`) já é validado pela B13.17 (NEON de 32 bits) — aqui só a PONTE
/// A64 (registro de índice de palavra, escrita destrutiva `[127:64]`).
class Ir64BlockExecutorB1920Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int ESZ_SINGLE = 2;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void setComplexF32(Aarch64FpRegisters fp, int reg, int pair, float real, float imag) {
        fp.setElement(reg, pair * 2, ESZ_SINGLE, Float.floatToRawIntBits(real) & 0xFFFF_FFFFL);
        fp.setElement(reg, pair * 2 + 1, ESZ_SINGLE, Float.floatToRawIntBits(imag) & 0xFFFF_FFFFL);
    }

    private static float realOf(Aarch64FpRegisters fp, int reg, int pair) {
        return Float.intBitsToFloat((int) fp.element(reg, pair * 2, ESZ_SINGLE));
    }

    private static float imagOf(Aarch64FpRegisters fp, int reg, int pair) {
        return Float.intBitsToFloat((int) fp.element(reg, pair * 2 + 1, ESZ_SINGLE));
    }

    // ── FCADD: as duas rotações produzem sinais trocados ───────────────────────────────────────

    @Test
    void fcadd90And270ProduceDifferentSignFlippedResults() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 2.0f, 3.0f); // Vn = 2+3i
        setComplexF32(fp, 2, 0, 4.0f, 5.0f); // Vm = 4+5i

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexAdd(false, ESZ_SINGLE, 90, 0, 1, 2));
        // rot90: (aRe-bIm) + (aIm+bRe)i = (2-5) + (3+4)i = -3+7i
        assertEquals(-3.0f, realOf(fp, 0, 0));
        assertEquals(7.0f, imagOf(fp, 0, 0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexAdd(false, ESZ_SINGLE, 270, 3, 1, 2));
        // rot270: (aRe+bIm) + (aIm-bRe)i = (2+5) + (3-4)i = 7-1i
        assertEquals(7.0f, realOf(fp, 3, 0));
        assertEquals(-1.0f, imagOf(fp, 3, 0));
    }

    @Test
    void fcaddNonQZeroesUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 1.0f, 1.0f);
        setComplexF32(fp, 2, 0, 1.0f, 1.0f);
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // sujar a metade alta antes
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexAdd(false, ESZ_SINGLE, 90, 0, 1, 2));
        assertEquals(0L, fp.word(1), "!q zera a metade alta");
    }

    // ── FCMLA: rot0+rot90 reproduz a multiplicação complexa completa ──────────────────────────

    @Test
    void fcmlaRotation0And90ComposeFullComplexProduct() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 2.0f, 3.0f); // a = 2+3i
        setComplexF32(fp, 2, 0, 4.0f, 5.0f); // b = 4+5i
        setComplexF32(fp, 0, 0, 0.0f, 0.0f); // acumulador zerado

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(false, ESZ_SINGLE, 0, 0, 1, 2));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(false, ESZ_SINGLE, 90, 0, 1, 2));

        // (2+3i)*(4+5i) = (8-15) + (10+12)i = -7+22i
        assertEquals(-7.0f, realOf(fp, 0, 0));
        assertEquals(22.0f, imagOf(fp, 0, 0));
    }

    @Test
    void fcmlaRotation180And270ComposeConjugateVariant() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 2.0f, 3.0f);
        setComplexF32(fp, 2, 0, 4.0f, 5.0f);
        setComplexF32(fp, 0, 0, 0.0f, 0.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(false, ESZ_SINGLE, 180, 0, 1, 2));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(false, ESZ_SINGLE, 270, 0, 1, 2));

        // rot180+rot270 é exatamente o NEGATIVO de rot0+rot90 (fórmula direta do ARM DDI 0487
        // `FComplexMulAdd`: os 4 termos trocam de sinal em bloco entre {0,90} e {180,270}).
        assertEquals(7.0f, realOf(fp, 0, 0));
        assertEquals(-22.0f, imagOf(fp, 0, 0));
    }

    @Test
    void fcmlaQFormOperatesOnTwoIndependentPairs() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 1.0f, 0.0f);
        setComplexF32(fp, 1, 1, 0.0f, 1.0f);
        setComplexF32(fp, 2, 0, 2.0f, 0.0f);
        setComplexF32(fp, 2, 1, 0.0f, 2.0f);
        setComplexF32(fp, 0, 0, 0.0f, 0.0f);
        setComplexF32(fp, 0, 1, 0.0f, 0.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(true, ESZ_SINGLE, 0, 0, 1, 2));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpComplexMultiplyAccumulate(true, ESZ_SINGLE, 90, 0, 1, 2));

        // par 0: (1+0i)*(2+0i) = 2+0i
        assertEquals(2.0f, realOf(fp, 0, 0));
        assertEquals(0.0f, imagOf(fp, 0, 0));
        // par 1: (0+1i)*(0+2i) = -2+0i
        assertEquals(-2.0f, realOf(fp, 0, 1));
        assertEquals(0.0f, imagOf(fp, 0, 1));
    }

    // ── FCMLA indexada: seleciona o par certo por índice ───────────────────────────────────────

    @Test
    void fcmlaByElementSelectsCorrectPairFromRm() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 2.0f, 3.0f); // a = 2+3i
        setComplexF32(fp, 1, 1, 100.0f, 200.0f); // outro par de Vn, não usado nesta chamada
        setComplexF32(fp, 2, 0, 9.0f, 9.0f); // par 0 de Vm — NÃO deve ser usado
        setComplexF32(fp, 2, 1, 4.0f, 5.0f); // par 1 de Vm — b = 4+5i, o índice sob teste
        setComplexF32(fp, 0, 0, 0.0f, 0.0f);

        EXECUTOR.executeOp(core,
                new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(false, ESZ_SINGLE, 0, 0, 1, 2, 1));
        EXECUTOR.executeOp(core,
                new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(false, ESZ_SINGLE, 90, 0, 1, 2, 1));

        // (2+3i)*(4+5i) = -7+22i — prova que o par 1 (não o par 0) de Vm foi usado.
        assertEquals(-7.0f, realOf(fp, 0, 0));
        assertEquals(22.0f, imagOf(fp, 0, 0));
    }

    @Test
    void fcmlaByElementReplicatesSameFixedPairAcrossAllLanes() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setComplexF32(fp, 1, 0, 1.0f, 0.0f);
        setComplexF32(fp, 1, 1, 0.0f, 1.0f);
        setComplexF32(fp, 2, 0, 3.0f, 4.0f); // único par real de Vm (índice 0)
        setComplexF32(fp, 0, 0, 0.0f, 0.0f);
        setComplexF32(fp, 0, 1, 0.0f, 0.0f);

        EXECUTOR.executeOp(core,
                new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(true, ESZ_SINGLE, 0, 0, 1, 2, 0));
        EXECUTOR.executeOp(core,
                new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(true, ESZ_SINGLE, 90, 0, 1, 2, 0));

        // par 0: (1+0i)*(3+4i) = 3+4i
        assertEquals(3.0f, realOf(fp, 0, 0));
        assertEquals(4.0f, imagOf(fp, 0, 0));
        // par 1: (0+1i)*(3+4i) = -4+3i — o MESMO par de Vm (índice 0) replicado.
        assertEquals(-4.0f, realOf(fp, 0, 1));
        assertEquals(3.0f, imagOf(fp, 0, 1));
    }
}
