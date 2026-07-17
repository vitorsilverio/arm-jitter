package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class VfpRegistersTest {
    @Test
    void singleRegistersOverlapDoubleRegisterLowHighHalves() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setS(2, 0xAAAAAAAA);
        vfp.setS(3, 0xBBBBBBBB);

        assertEquals(0xBBBBBBBB_AAAAAAAAL, vfp.d(1));

        vfp.setD(1, 0x1122_3344_5566_7788L);
        assertEquals(0x55667788, vfp.s(2));
        assertEquals(0x11223344, vfp.s(3));
    }

    @Test
    void floatAndDoubleViewsAreBitExactIncludingNaNPayload() {
        VfpRegisters vfp = new VfpRegisters();
        int nanBits = 0x7FC00001;
        vfp.setS(5, nanBits);
        assertEquals(nanBits, Float.floatToRawIntBits(vfp.sFloat(5)));

        vfp.setSFloat(6, 1.5f);
        assertEquals(Float.floatToRawIntBits(1.5f), vfp.s(6));

        long nanDoubleBits = 0x7FF8_0000_0000_0001L;
        vfp.setD(4, nanDoubleBits);
        assertEquals(nanDoubleBits, Double.doubleToRawLongBits(vfp.dDouble(4)));

        vfp.setDDouble(7, 2.5);
        assertEquals(Double.doubleToRawLongBits(2.5), vfp.d(7));
    }

    @Test
    void saveAndLoadStateRoundTripsTheWholeBank() throws IOException {
        VfpRegisters vfp = new VfpRegisters();
        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            vfp.setS(i, i * 0x01010101);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        vfp.saveState(new DataOutputStream(buffer));

        VfpRegisters restored = new VfpRegisters();
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            assertEquals(i * 0x01010101, restored.s(i));
        }
    }

    @Test
    void resetZeroesTheWholeBank() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setS(10, 0x1234);
        vfp.reset();
        assertEquals(0, vfp.s(10));
    }
}
