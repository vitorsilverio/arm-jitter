package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `FPMR` (B19.11a) — AO CONTRÁRIO de `FPCR`/`FPSR` (B8.15), não é armazenamento puro: os getters
/// de campo têm que decompor um valor sintético corretamente, porque a B19.11 (`FEAT_FP8`) vai
/// consumi-los de verdade para escolher formato/escala em cada conversão.
class Aarch64FpModeRegisterTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(8);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    private static void writeFpmr(Aarch64Core core, long value) {
        core.setX(0, value);
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPMR, 0));
    }

    @Test
    void roundTripsThroughExecutorIndependentlyOfFpcrFpsr() {
        Aarch64Core core = newCore();
        core.setX(0, 0x0000_0000_0180_0000L); // FPCR
        core.setX(1, 0x0000_0000_0000_0001L); // FPSR
        core.setX(2, 0x0000_0001_2345_0007L); // FPMR sintético

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPCR, 0));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPSR, 1));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPMR, 2));

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.FPCR, 10));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.FPSR, 11));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.FPMR, 12));

        assertEquals(0x0000_0000_0180_0000L, core.x(10));
        assertEquals(0x0000_0000_0000_0001L, core.x(11));
        assertEquals(0x0000_0001_2345_0007L, core.x(12));
    }

    @Test
    void decodesE5m2AndE4m3Formats() {
        Aarch64Core core = newCore();
        // F8S1=E5M2(0b000), F8S2=E4M3(0b001 << 3), F8D=E4M3(0b001 << 6)
        writeFpmr(core, (0b001L << 6) | (0b001L << 3) | 0b000L);
        assertEquals(Aarch64Fp8Format.E5M2, core.fp8SourceFormat1());
        assertEquals(Aarch64Fp8Format.E4M3, core.fp8SourceFormat2());
        assertEquals(Aarch64Fp8Format.E4M3, core.fp8DestinationFormat());
    }

    @Test
    void decodesOppositeFormatsToProveFieldsAreNotConfused() {
        Aarch64Core core = newCore();
        // F8S1=E4M3, F8S2=E5M2, F8D=E5M2 — o oposto do teste anterior.
        writeFpmr(core, 0b001L);
        assertEquals(Aarch64Fp8Format.E4M3, core.fp8SourceFormat1());
        assertEquals(Aarch64Fp8Format.E5M2, core.fp8SourceFormat2());
        assertEquals(Aarch64Fp8Format.E5M2, core.fp8DestinationFormat());
    }

    @Test
    void nscaleIsSignExtendedEightBitField() {
        Aarch64Core core = newCore();
        writeFpmr(core, 0xFFL << 24); // -1 em 8 bits com sinal
        assertEquals(-1, core.fp8NarrowScale());

        writeFpmr(core, 0x7FL << 24); // +127
        assertEquals(127, core.fp8NarrowScale());
    }

    @Test
    void widenScalesOnlyConsumeLowFourBitsOfEachField() {
        Aarch64Core core = newCore();
        // LSCALE[22:16] = 0b1011010 (7 bits, só os 4 baixos = 0b1010 = 10 valem);
        // LSCALE2[37:32] = 0b101111 (6 bits, só os 4 baixos = 0b1111 = 15 valem).
        long value = (0b101111L << 32) | (0b1011010L << 16);
        writeFpmr(core, value);
        assertEquals(0b1010, core.fp8WidenScale());
        assertEquals(0b1111, core.fp8WidenScale2());
    }

    @Test
    void widenScaleAndScale2AreIndependent() {
        Aarch64Core core = newCore();
        writeFpmr(core, 0b0101L << 16); // só LSCALE setado
        assertEquals(0b0101, core.fp8WidenScale());
        assertEquals(0, core.fp8WidenScale2());

        writeFpmr(core, 0b0110L << 32); // só LSCALE2 setado
        assertEquals(0, core.fp8WidenScale());
        assertEquals(0b0110, core.fp8WidenScale2());
    }

    @Test
    void overflowSaturationFlagDecodesBit15() {
        Aarch64Core core = newCore();
        writeFpmr(core, 0L);
        assertFalse(core.fp8OverflowSaturatesToMaxNormal());

        writeFpmr(core, 1L << 15);
        assertTrue(core.fp8OverflowSaturatesToMaxNormal());
    }
}
