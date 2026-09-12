package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.11e (`FEAT_FP8`) — semântica de `FSCALE` direto no executor (interpretador = oráculo, G1).
/// O núcleo ({@code AdvSimdLanes.fpScaleByInt}) delega a {@link Math#scalb}, que reproduz
/// `FPScale` do ARM DDI 0487 nos casos especiais — os testes abaixo conferem os 3 casos exigidos
/// pelo Aceite da task (escala positiva, negativa, overflow/underflow do expoente) contra o
/// pseudocódigo do manual, não só contra o comportamento do JDK.
class Ir64BlockExecutorB1911eTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_HALF = 1;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── Precisão simples ────────────────────────────────────────────────────────────────────────

    @Test
    void fscalePositiveExponentSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 2); // Vm[0] = +2 (inteiro, não ponto flutuante)

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(false, ESZ_SINGLE, 0, 1, 2));

        assertEquals(12.0f, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
    }

    @Test
    void fscaleNegativeExponentSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(8.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 0xFFFF_FFFDL); // Vm[0] = -3 (32 bits, sinal em bit31)

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(false, ESZ_SINGLE, 0, 1, 2));

        assertEquals(1.0f, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
    }

    @Test
    void fscaleOverflowSaturatesToSignedInfinitySingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, ESZ_SINGLE, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 1000); // expoente MUITO maior que o máximo IEEE-754 (127)
        fp.setElement(2, 1, ESZ_SINGLE, 1000);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(true, ESZ_SINGLE, 0, 1, 2));

        assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE)));
        assertEquals(Float.NEGATIVE_INFINITY, Float.intBitsToFloat((int) fp.element(0, 1, ESZ_SINGLE)));
    }

    @Test
    void fscaleUnderflowSaturatesToPositiveZeroSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 0xFFFF_FC18L); // Vm[0] = -1000 (expoente MUITO negativo)

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(false, ESZ_SINGLE, 0, 1, 2));

        float result = Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE));
        assertEquals(0.0f, result);
        assertTrue(1 / result > 0, "zero deve preservar o sinal positivo (FPScale de valor positivo)");
    }

    @Test
    void fscaleNaNPassesThroughUnchanged() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        int nanBits = Float.floatToRawIntBits(Float.NaN);
        fp.setElement(1, 0, ESZ_SINGLE, nanBits & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 5);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(false, ESZ_SINGLE, 0, 1, 2));

        assertTrue(Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 0, ESZ_SINGLE))));
    }

    @Test
    void fscaleNonQZeroesUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L); // sujeira nos 128 bits antes da execução
        fp.setElement(1, 0, ESZ_SINGLE, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, ESZ_SINGLE, 1);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(false, ESZ_SINGLE, 0, 1, 2));

        assertEquals(0L, fp.high64(0), "escrita SIMD&FP destrutiva: bits[127:64] zerados quando !q");
    }

    // ── Meia precisão: `Vm` é o inteiro CRU da lane, não bits de ponto flutuante ───────────────────

    @Test
    void fscaleHalfPrecisionReadsExponentAsRawIntegerNotFloatBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, ESZ_HALF, Float.floatToFloat16(1.0f) & 0xFFFFL);
        fp.setElement(2, 0, ESZ_HALF, 4); // Vm[0] = +4 CRU — não é `halfBits(4.0f)`

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpScaleByInt(true, ESZ_HALF, 0, 1, 2));

        assertEquals(16.0f, Float.float16ToFloat((short) fp.element(0, 0, ESZ_HALF)));
    }
}
