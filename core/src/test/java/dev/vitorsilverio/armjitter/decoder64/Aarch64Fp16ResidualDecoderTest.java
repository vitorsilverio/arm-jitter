package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.26 — `FEAT_FP16` residual: `FMOV_hx`/`FMOV_xh` (`Fp64HalfPrecisionGeneralRegisterMove`) e
/// `FCVT_s_hs`/`FCVT_s_hd`/`FCVT_s_sh`/`FCVT_s_dh` (`Fp64ConvertHalfPrecision`). Os 6 encodings
/// abaixo foram gerados e conferidos byte a byte contra `aarch64-linux-gnu-as -march=armv8.2-a+fp16`
/// (WSL Ubuntu, binutils real): `fmov h0,x1`/`fmov x2,h3`/`fcvt h4,s5`/`fcvt h6,d7`/`fcvt s8,h9`/
/// `fcvt d10,h11`.
class Aarch64Fp16ResidualDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder FP16_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    private static final int FMOV_H0_X1 = 0x9EE70020;
    private static final int FMOV_X2_H3 = 0x9EE60062;
    private static final int FCVT_H4_S5 = 0x1E23C0A4;
    private static final int FCVT_H6_D7 = 0x1E63C0E6;
    private static final int FCVT_S8_H9 = 0x1EE24128;
    private static final int FCVT_D10_H11 = 0x1EE2C16A;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void fmovHxGatedByFp16Feature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FMOV_X2_H3));
    }

    @Test
    void fmovXhGatedByFp16Feature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FMOV_H0_X1));
    }

    @Test
    void fcvtHalfFormsGatedByFp16Feature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FCVT_H4_S5));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FCVT_H6_D7));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FCVT_S8_H9));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FCVT_D10_H11));
    }

    @Test
    void decodesFmovXToH() {
        // `fmov h0, x1`: Xn(gp) -> Hd(float), toFloat=true.
        Ir64Op.Fp64HalfPrecisionGeneralRegisterMove op =
                (Ir64Op.Fp64HalfPrecisionGeneralRegisterMove) decode(FP16_DECODER, FMOV_H0_X1);
        assertEquals(true, op.toFloat());
        assertEquals(0, op.fpReg());
        assertEquals(1, op.gpReg());
    }

    @Test
    void decodesFmovHToX() {
        // `fmov x2, h3`: Hn(float) -> Xd(gp), toFloat=false.
        Ir64Op.Fp64HalfPrecisionGeneralRegisterMove op =
                (Ir64Op.Fp64HalfPrecisionGeneralRegisterMove) decode(FP16_DECODER, FMOV_X2_H3);
        assertEquals(false, op.toFloat());
        assertEquals(3, op.fpReg());
        assertEquals(2, op.gpReg());
    }

    @Test
    void fmovXToHIgnoresSfBit() {
        // Comentário do decode real (`a64.decode` do QEMU): "Half-precision allows both sf=0 and
        // sf=1 with identical results" — o record não carrega `sf`/`wide`, então forçar sf=0 (bit31)
        // deve decodificar exatamente igual.
        int narrowForm = FMOV_H0_X1 & ~(1 << 31);
        Ir64Op.Fp64HalfPrecisionGeneralRegisterMove op =
                (Ir64Op.Fp64HalfPrecisionGeneralRegisterMove) decode(FP16_DECODER, narrowForm);
        assertEquals(true, op.toFloat());
        assertEquals(0, op.fpReg());
        assertEquals(1, op.gpReg());
    }

    @Test
    void decodesSingleToHalf() {
        Ir64Op.Fp64ConvertHalfPrecision op =
                (Ir64Op.Fp64ConvertHalfPrecision) decode(FP16_DECODER, FCVT_H4_S5);
        assertEquals(Ir64Op.Fp64HalfPrecisionConversion.SINGLE_TO_HALF, op.conversion());
        assertEquals(4, op.vd());
        assertEquals(5, op.vn());
    }

    @Test
    void decodesDoubleToHalf() {
        Ir64Op.Fp64ConvertHalfPrecision op =
                (Ir64Op.Fp64ConvertHalfPrecision) decode(FP16_DECODER, FCVT_H6_D7);
        assertEquals(Ir64Op.Fp64HalfPrecisionConversion.DOUBLE_TO_HALF, op.conversion());
        assertEquals(6, op.vd());
        assertEquals(7, op.vn());
    }

    @Test
    void decodesHalfToSingle() {
        Ir64Op.Fp64ConvertHalfPrecision op =
                (Ir64Op.Fp64ConvertHalfPrecision) decode(FP16_DECODER, FCVT_S8_H9);
        assertEquals(Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_SINGLE, op.conversion());
        assertEquals(8, op.vd());
        assertEquals(9, op.vn());
    }

    @Test
    void decodesHalfToDouble() {
        Ir64Op.Fp64ConvertHalfPrecision op =
                (Ir64Op.Fp64ConvertHalfPrecision) decode(FP16_DECODER, FCVT_D10_H11);
        assertEquals(Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_DOUBLE, op.conversion());
        assertEquals(10, op.vd());
        assertEquals(11, op.vn());
    }
}
