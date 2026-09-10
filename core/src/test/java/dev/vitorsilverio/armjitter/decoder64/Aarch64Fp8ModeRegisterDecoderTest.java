package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `FPMR` (B19.11a) via `MRS`/`MSR (register)` — gateado por `Aarch64Feature.FP8` (a mesma feature
/// que a B19.11 vai usar para as 12 instruções de conversão, `FEAT_FPMR` é implicada por
/// `FEAT_FP8`). Palavras confirmadas byte a byte contra `aarch64-linux-gnu-as` real (WSL, sem
/// toolchain devkitA64 nesta sessão — ver `## Resultado` da task).
class Aarch64Fp8ModeRegisterDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder FP8_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);

    // mrs x0, fpmr / msr fpmr, x0 / mrs x1, fpmr / msr fpmr, x1
    private static final int MRS_FPMR_X0_WORD = 0xd53b4440;
    private static final int MSR_FPMR_X0_WORD = 0xd51b4440;
    private static final int MRS_FPMR_X1_WORD = 0xd53b4441;
    private static final int MSR_FPMR_X1_WORD = 0xd51b4441;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutFp8() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(DEFAULT_DECODER, MRS_FPMR_X0_WORD));
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(DEFAULT_DECODER, MSR_FPMR_X0_WORD));
    }

    @Test
    void mrsFpmrDecodesWithFp8() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(FP8_DECODER, MRS_FPMR_X0_WORD);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.FPMR, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrFpmrDecodesWithFp8() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(FP8_DECODER, MSR_FPMR_X0_WORD);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.FPMR, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsFpmrDecodesRtOne() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(FP8_DECODER, MRS_FPMR_X1_WORD);
        assertEquals(Aarch64SystemRegisterId.FPMR, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void msrFpmrDecodesRtOne() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(FP8_DECODER, MSR_FPMR_X1_WORD);
        assertEquals(Aarch64SystemRegisterId.FPMR, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void fpcrAndFpsrStillDecodeUnderFp8Architecture() {
        // d53b4400: mrs x0, fpcr / d53b4420: mrs x0, fpsr — não podem ter sido afetados pelo
        // roteamento novo de op2 no mesmo CRm.
        assertEquals(Aarch64SystemRegisterId.FPCR,
                ((Ir64Op.SystemRegister) decodeWord(FP8_DECODER, 0xd53b4400)).register());
        assertEquals(Aarch64SystemRegisterId.FPSR,
                ((Ir64Op.SystemRegister) decodeWord(FP8_DECODER, 0xd53b4420)).register());
    }
}
