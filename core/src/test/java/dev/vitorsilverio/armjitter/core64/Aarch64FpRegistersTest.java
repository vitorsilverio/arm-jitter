package dev.vitorsilverio.armjitter.core64;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class Aarch64FpRegistersTest {
    @Test
    void writingSZeroesTheUpperHalfOfTheSameCell() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setD(3, 0x1234_5678_9ABC_DEF0L);

        fp.setSFloat(3, 1.5f);

        assertEquals(Float.floatToRawIntBits(1.5f), (int) fp.d(3));
        assertEquals(0, fp.d(3) >>> 32, "setS/setSFloat deve zerar os 32 bits altos da mesma célula");
    }

    @Test
    void dAndSAreTheSameCellNoAritmetic() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setDDouble(4, 2.5);

        assertEquals(Double.doubleToRawLongBits(2.5), fp.d(4));
        assertEquals((int) Double.doubleToRawLongBits(2.5), fp.s(4));
    }

    @Test
    void nanPayloadSurvivesRoundTripBitExact() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        int nanBits = 0x7FC00001;
        fp.setS(5, nanBits);
        assertEquals(nanBits, Float.floatToRawIntBits(fp.sFloat(5)));

        long nanDoubleBits = 0x7FF8_0000_0000_0001L;
        fp.setD(6, nanDoubleBits);
        assertEquals(nanDoubleBits, Double.doubleToRawLongBits(fp.dDouble(6)));
    }

    @Test
    void allThirtyTwoRegistersAreIndependentNoOverlap() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        for (int i = 0; i < Aarch64FpRegisters.V_REGISTER_COUNT; i++) {
            fp.setD(i, (long) i * 0x1111_1111_1111_1111L);
        }
        for (int i = 0; i < Aarch64FpRegisters.V_REGISTER_COUNT; i++) {
            assertEquals((long) i * 0x1111_1111_1111_1111L, fp.d(i), "V" + i);
        }
    }

    @Test
    void vRegisterCountIsThirtyTwo() {
        assertEquals(32, Aarch64FpRegisters.V_REGISTER_COUNT);
    }

    @Test
    void saveAndLoadStateRoundTripsTheWholeBank() throws IOException {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        for (int i = 0; i < Aarch64FpRegisters.V_REGISTER_COUNT; i++) {
            fp.setD(i, (long) i * 0x0101_0101_0101_0101L);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        fp.saveState(new DataOutputStream(buffer));

        Aarch64FpRegisters restored = new Aarch64FpRegisters();
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        for (int i = 0; i < Aarch64FpRegisters.V_REGISTER_COUNT; i++) {
            assertEquals((long) i * 0x0101_0101_0101_0101L, restored.d(i));
        }
    }

    @Test
    void resetZeroesTheWholeBank() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setD(10, 0x1234);
        fp.reset();
        assertEquals(0L, fp.d(10));
    }
}
