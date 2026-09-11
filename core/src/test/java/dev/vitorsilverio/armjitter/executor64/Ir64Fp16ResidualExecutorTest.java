package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.26 — semântica de `FMOV_hx`/`FMOV_xh` (bits crus) e `FCVT_s_hs`/`FCVT_s_hd`/`FCVT_s_sh`/
/// `FCVT_s_dh` (conversão de valor). Interpretador = oráculo (G1).
class Ir64Fp16ResidualExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    @Test
    void fmovXToHMovesRawBitsWithoutReinterpretation() {
        // 0xFFFF é um bit pattern que NÃO seria um `float16` "óbvio" (é o NaN canônico de sinal
        // negativo em binary16) — confirma que os bits sobrevivem crus, sem reinterpretação.
        Aarch64Core core = newCore();
        core.setX(1, 0x1234_0000_0000_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64HalfPrecisionGeneralRegisterMove(true, 0, 1));
        assertEquals(0xFFFFL, core.fp().element(0, 0, 1));
        // Escrita destrutiva: o resto do registrador V0 é zerado (mesma disciplina de setS/setD).
        assertEquals(0L, core.fp().high64(0));
    }

    @Test
    void fmovHToXMovesRawBitsZeroExtended() {
        Aarch64Core core = newCore();
        core.fp().setScalar(3, 1, 0xFFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64HalfPrecisionGeneralRegisterMove(false, 3, 2));
        assertEquals(0xFFFFL, core.x(2));
    }

    @Test
    void fmovResultIndependentOfGpRegisterWidth() {
        // Comentário do `a64.decode` real: "Half-precision allows both sf=0 and sf=1 with
        // identical results" — o executor sempre resolve o lado geral como X completo, então o
        // resultado observável não pode depender de qual largura a instrução original usava.
        Aarch64Core core = newCore();
        core.setX(1, 0xABCDL);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64HalfPrecisionGeneralRegisterMove(true, 0, 1));
        assertEquals(0xABCDL, core.fp().element(0, 0, 1));
    }

    @Test
    void singleToHalfAndBackAreInverseForExactlyRepresentableValue() {
        // 1.5 é exatamente representável em binary16 E binary32 — ida e volta preserva o valor.
        Aarch64Core core = newCore();
        core.fp().setSFloat(5, 1.5f);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.SINGLE_TO_HALF, 4, 5));
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_SINGLE, 8, 4));
        assertEquals(1.5f, core.fp().sFloat(8));
    }

    @Test
    void doubleToHalfRoundsCorrectlyNarrowing() {
        // 1 + 2^-20 só cabe exato em dupla precisão (mantissa de binary16 tem só 10 bits) —
        // confirma arredondamento correto ao estreitar (arredonda para 1.0, o binary16 mais
        // próximo, em vez de truncar ou propagar lixo).
        Aarch64Core core = newCore();
        double value = 1.0 + Math.pow(2, -20);
        core.fp().setDDouble(7, value);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.DOUBLE_TO_HALF, 6, 7));
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_DOUBLE, 9, 6));
        assertEquals(1.0, core.fp().dDouble(9));
    }

    @Test
    void halfToDoubleWidensExactly() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(9, 1.5f);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.SINGLE_TO_HALF, 11, 9));
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertHalfPrecision(
                Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_DOUBLE, 12, 11));
        assertEquals(1.5, core.fp().dDouble(12));
    }
}
