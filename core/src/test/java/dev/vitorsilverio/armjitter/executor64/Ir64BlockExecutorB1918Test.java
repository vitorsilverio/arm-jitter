package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.18 (`FEAT_FRINTTS`) — semântica de `FRINT32Z`/`FRINT32X`/`FRINT64Z`/`FRINT64X` direto no
/// executor (interpretador = oráculo, G1). `FRINT32*`/`FRINT64*` arredondam para um valor
/// INTEGRAL mantendo a representação em ponto flutuante (nunca convertem para inteiro de
/// verdade) e SATURAM o resultado para o alcance de um inteiro de 32/64 bits com sinal.
class Ir64BlockExecutorB1918Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final double TWO_POW_31 = 0x1p31;
    private static final double TWO_POW_63 = 0x1p63;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── Escalar: `Z` trunca, `X` arredonda RNE (Aceite: valor de meio-caminho) ─────────────────
    //
    // 2.5 NÃO diferencia (truncar e "mais próximo, par" dão os dois `2.0`, achado desta task,
    // ver Javadoc de `## Resultado`) — `1.5` diferencia: `Z`→`1.0`, `X`(RNE)→`2.0` (par mais
    // próximo).

    @Test
    void scalarZTruncatesTowardZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, 1.5);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertEquals(1.0, fp.dDouble(0));

        fp.setDDouble(1, -1.5);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertEquals(-1.0, fp.dDouble(0));
    }

    @Test
    void scalarXRoundsNearestTiesEven() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, 1.5);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, true, 0, 1));
        assertEquals(2.0, fp.dDouble(0));
    }

    // ── Overflow: satura para ±2^N, não `NaN`/`Infinito` ────────────────────────────────────────

    @Test
    void scalar32BitAnd64BitOverflowSaturateToDifferentLimits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        double hugeValue = 1.0e30; // fora de alcance de 32 E de 64 bits com sinal
        fp.setDDouble(1, hugeValue);

        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertEquals(TWO_POW_31, fp.dDouble(0));

        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, true, true, 2, 1));
        assertEquals(TWO_POW_63, fp.dDouble(2));
    }

    @Test
    void scalarOverflowSaturatesWithOperandSignNotNaNOrInfinite() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, -1.0e30);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertEquals(-TWO_POW_31, fp.dDouble(0));
        assertTrue(Double.isFinite(fp.dDouble(0)));
    }

    @Test
    void scalarInfinityAlsoSaturates() {
        // `±Infinito` NÃO é caso especial (diferente de `NaN`) — satura como qualquer overflow.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, Double.POSITIVE_INFINITY);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, true, 0, 1));
        assertEquals(TWO_POW_63, fp.dDouble(0));
    }

    @Test
    void scalarNaNPassesThroughUnchanged() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, Double.NaN);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertTrue(Double.isNaN(fp.dDouble(0)));
    }

    @Test
    void scalarExactBoundaryDoesNotSaturate() {
        // `2^31` exato É um resultado válido (não `2^31-1`) — achado medido contra o algoritmo
        // real do QEMU (`int32_max_as_float32`), ver Javadoc de `Ir64Op.Fp64RoundRangeLimited`.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setDDouble(1, TWO_POW_31);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, true, 0, 1));
        assertEquals(TWO_POW_31, fp.dDouble(0));
    }

    @Test
    void scalarSinglePrecisionOverflowSaturates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setSFloat(1, 1.0e30f);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64RoundRangeLimited(
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 1));
        assertEquals((float) TWO_POW_31, fp.sFloat(0));
    }

    // ── Vetorial: mesma semântica, por lane ─────────────────────────────────────────────────────

    @Test
    void vectorRint32ZTruncatesEachLane() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, ESZ_SINGLE, Float.floatToRawIntBits(1.0e30f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(
                Ir64VectorFpUnaryOp.RINT32Z, false, false, ESZ_SINGLE, 0, 1));
        assertEquals(1.0f, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
        assertEquals((float) TWO_POW_31, Float.intBitsToFloat((int) fp.element(0, 1, ESZ_SINGLE)));
    }

    @Test
    void vectorRint64XRoundsAndSaturatesDoubleword() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_DOUBLE, Double.doubleToRawLongBits(1.5));
        fp.setElement(1, 1, ESZ_DOUBLE, Double.doubleToRawLongBits(-1.0e30));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(
                Ir64VectorFpUnaryOp.RINT64X, false, true, ESZ_DOUBLE, 0, 1));
        assertEquals(2.0, Double.longBitsToDouble(fp.element(0, 0, ESZ_DOUBLE)));
        assertEquals(-TWO_POW_63, Double.longBitsToDouble(fp.element(0, 1, ESZ_DOUBLE)));
    }
}
