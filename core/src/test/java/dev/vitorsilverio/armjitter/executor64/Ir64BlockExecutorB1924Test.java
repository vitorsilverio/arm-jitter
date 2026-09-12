package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.24 (`FEAT_FAMINMAX`) — semântica de `FAMAX`/`FAMIN` direto no executor (interpretador =
/// oráculo, G1). `AdvSimdLanes.fpAbsoluteMaxMin` compara pelo VALOR ABSOLUTO mas devolve o
/// operando ORIGINAL do vencedor — os testes abaixo conferem o Aceite da task ("sinal
/// preservado": `FAMAX(-5.0, 3.0)` produz `-5.0`, não `5.0`).
class Ir64BlockExecutorB1924Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── Sinal preservado (Aceite da task) ───────────────────────────────────────────────────────

    @Test
    void famaxPreservesSignOfWinner() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(-5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, false, ESZ_SINGLE, 0, 1, 2));

        // |-5.0| > |3.0| ⇒ vencedor é `Vn` (-5.0), NÃO `5.0` (prova de que a comparação é por
        // magnitude, mas o valor devolvido é o operando original com sinal).
        assertEquals(-5.0f, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
    }

    @Test
    void faminPreservesSignOfWinner() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(-5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(false, false, ESZ_SINGLE, 0, 1, 2));

        // |3.0| < |-5.0| ⇒ vencedor (mínimo) é `Vm` (3.0).
        assertEquals(3.0f, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
    }

    @Test
    void famaxDoesNotReturnAbsoluteValueOfWinner() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(-9.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, false, ESZ_SINGLE, 0, 1, 2));

        float result = Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE));
        assertEquals(-9.0f, result, "FAMAX compara por |valor| mas devolve o operando ORIGINAL, não abs(vencedor)");
    }

    // ── Casos gerais (precisão dupla) ────────────────────────────────────────────────────────────

    @Test
    void famaxDoublePrecision() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_DOUBLE, Double.doubleToRawLongBits(1.5));
        fp.setElement(2, 0, ESZ_DOUBLE, Double.doubleToRawLongBits(-4.5));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, true, ESZ_DOUBLE, 0, 1, 2));

        assertEquals(-4.5, Double.longBitsToDouble(fp.element(0, 0, ESZ_DOUBLE)));
    }

    @Test
    void faminDoublePrecision() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_DOUBLE, Double.doubleToRawLongBits(1.5));
        fp.setElement(2, 0, ESZ_DOUBLE, Double.doubleToRawLongBits(-4.5));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(false, true, ESZ_DOUBLE, 0, 1, 2));

        assertEquals(1.5, Double.longBitsToDouble(fp.element(0, 0, ESZ_DOUBLE)));
    }

    // ── NaN propaga (mesma disciplina de `MAX`/`MIN` deste núcleo, sem `FPProcessNaNs` completo) ───

    @Test
    void famaxNaNPropagates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, false, ESZ_SINGLE, 0, 1, 2));

        assertTrue(Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE))));
    }

    // ── Escrita destrutiva (`!q` zera bits altos) ───────────────────────────────────────────────

    @Test
    void famaxNonQZeroesUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, false, ESZ_SINGLE, 0, 1, 2));

        assertEquals(0L, fp.high64(0), "escrita SIMD&FP destrutiva: bits[127:64] zerados quando !q");
    }

    // ── Meia precisão ────────────────────────────────────────────────────────────────────────────

    @Test
    void famaxHalfPrecisionPreservesSign() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_HALF, Float.floatToFloat16(-6.0f) & 0xFFFFL);
        fp.setElement(2, 0, ESZ_HALF, Float.floatToFloat16(2.0f) & 0xFFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAbsoluteMaxMin(true, true, ESZ_HALF, 0, 1, 2));

        assertEquals(-6.0f, Float.float16ToFloat((short) fp.element(0, 0, ESZ_HALF)));
    }
}
