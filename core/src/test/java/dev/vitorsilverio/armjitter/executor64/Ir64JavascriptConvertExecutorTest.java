package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.29 — semântica de `FJCVTZS` (interpretador = oráculo, G1). Ver Armadilhas da task:
/// `NaN`/infinito/overflow produzem `Wd=0` SEM saturar (ao contrário de `FCVTZS`), e `PSTATE.Z`
/// sinaliza EXATIDÃO da conversão, não "resultado é zero" — os dois efeitos são testados
/// separadamente para não confundir um com o outro.
class Ir64JavascriptConvertExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore(double dn) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
        core.fp().setDDouble(1, dn);
        return core;
    }

    private static Aarch64Core convert(double dn) {
        Aarch64Core core = newCore(dn);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64JavascriptConvert(0, 1));
        return core;
    }

    @Test
    void truncatesTowardZero() {
        assertEquals(3, (int) convert(3.7).x(0));
        assertEquals(-3, (int) convert(-3.7).x(0));
    }

    @Test
    void nanAndInfinityProduceZeroNotSaturation() {
        assertEquals(0, (int) convert(Double.NaN).x(0));
        assertEquals(0, (int) convert(Double.POSITIVE_INFINITY).x(0));
        assertEquals(0, (int) convert(Double.NEGATIVE_INFINITY).x(0));
    }

    @Test
    void overflowProducesZeroNotSaturation() {
        // FCVTZS satura para INT32_MAX/INT32_MIN aqui; FJCVTZS produz 0 — a diferença central.
        assertEquals(0, (int) convert(1e30).x(0));
        assertEquals(0, (int) convert(-1e30).x(0));
    }

    @Test
    void zFlagSetWhenConversionExact() {
        Aarch64Core core = convert(5.0);
        assertEquals(5, (int) core.x(0));
        assertTrue(core.pstate().zero());
    }

    @Test
    void zFlagClearWhenFractionalPart() {
        Aarch64Core core = convert(5.5);
        assertEquals(5, (int) core.x(0));
        assertFalse(core.pstate().zero());
    }

    @Test
    void zFlagClearOnOverflowEvenThoughResultIsZero() {
        // Wd=0 por overflow (NÃO por a entrada ser zero) — Z tem que ficar CLEAR, não SET, para
        // não confundir "resultado zero" com "conversão exata" (Armadilha nº2 da task).
        Aarch64Core core = convert(1e30);
        assertEquals(0, (int) core.x(0));
        assertFalse(core.pstate().zero());
    }

    @Test
    void zFlagSetWhenInputIsExactZero() {
        Aarch64Core core = convert(0.0);
        assertEquals(0, (int) core.x(0));
        assertTrue(core.pstate().zero());
    }

    @Test
    void otherFlagsAlwaysCleared() {
        Aarch64Core core = convert(-3.7);
        assertFalse(core.pstate().negative());
        assertFalse(core.pstate().carry());
        assertFalse(core.pstate().overflow());
    }
}
