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
    void setSTouchesOnlyItsOwnHalfNeverTheRestOfTheRegister() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(3, 0xFFFF_FFFF_FFFF_FFFFL);

        vfp.setS(6, 0x12345678); // metade baixa de D3
        assertEquals(0xFFFF_FFFF_12345678L, vfp.d(3));

        vfp.setS(7, 0x0000_0000); // metade alta de D3
        assertEquals(0x0000_0000_12345678L, vfp.d(3));
    }

    // ── B13.1: vista Q0-Q15 e aliasing com D/S ──

    @Test
    void writingQ0AltersD0D1AndS0ThroughS3() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setQ(0, 0x1111_1111_2222_2222L, 0x3333_3333_4444_4444L);

        assertEquals(0x1111_1111_2222_2222L, vfp.d(0));
        assertEquals(0x3333_3333_4444_4444L, vfp.d(1));
        assertEquals(0x2222_2222, vfp.s(0));
        assertEquals(0x1111_1111, vfp.s(1));
        assertEquals(0x4444_4444, vfp.s(2));
        assertEquals(0x3333_3333, vfp.s(3));

        assertEquals(0x1111_1111_2222_2222L, vfp.low64(0));
        assertEquals(0x3333_3333_4444_4444L, vfp.high64(0));
        assertArrayEquals(new long[] {0x1111_1111_2222_2222L, 0x3333_3333_4444_4444L}, vfp.q(0));
    }

    @Test
    void writingS3AltersHighHalfOfD1AndHighWordOfQ0() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setS(3, 0xDEADBEEF);

        assertEquals(0xDEADBEEF_0000_0000L, vfp.d(1));
        assertEquals(0xDEADBEEF_0000_0000L, vfp.high64(0));
        assertEquals(0L, vfp.low64(0));
    }

    // ── B13.1: element / setElement nas 4 larguras (Q-indexado, span de 128 bits) ──

    @Test
    void elementAndSetElementCoverAllFourWidths() {
        VfpRegisters vfp = new VfpRegisters();

        // byte (sizeLog2=0): 16 lanes num Q
        vfp.setElement(1, 0, 0, 0xAB);
        vfp.setElement(1, 15, 0, 0xCD);
        assertEquals(0xAB, vfp.element(1, 0, 0));
        assertEquals(0xCD, vfp.element(1, 15, 0));
        assertEquals(0xCD00_0000_0000_0000L, vfp.high64(1));

        // halfword (sizeLog2=1)
        vfp.setQ(2, 0, 0);
        vfp.setElement(2, 3, 1, 0x1234);
        assertEquals(0x1234, vfp.element(2, 3, 1));
        assertEquals(0x1234_0000_0000_0000L, vfp.low64(2));

        // word (sizeLog2=2): lane 2 = bits [64:96) do Q = 32 bits BAIXOS de high64
        vfp.setQ(3, 0, 0);
        vfp.setElement(3, 2, 2, 0xCAFEF00DL);
        assertEquals(0xCAFEF00DL, vfp.element(3, 2, 2));
        assertEquals(0xCAFEF00DL, vfp.high64(3));

        // doubleword (sizeLog2=3)
        vfp.setQ(4, 0, 0);
        vfp.setElement(4, 1, 3, 0x0123_4567_89AB_CDEFL);
        assertEquals(0x0123_4567_89AB_CDEFL, vfp.element(4, 1, 3));
        assertEquals(0x0123_4567_89AB_CDEFL, vfp.high64(4));
        assertEquals(0L, vfp.low64(4));
    }

    @Test
    void setScalarAndReplicateElementMatchMirroredSemantics() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setQ(5, -1L, -1L);

        vfp.setScalar(5, 1, 0xBEEF); // halfword na lane 0, zera o resto
        assertEquals(0xBEEFL, vfp.low64(5));
        assertEquals(0L, vfp.high64(5));

        vfp.replicateElement(6, 0xABL, 0, false); // byte, só os 64 baixos
        assertEquals(0xABAB_ABAB_ABAB_ABABL, vfp.low64(6));
        assertEquals(0L, vfp.high64(6));

        vfp.replicateElement(7, 0x7FFFL, 1, true); // halfword, 128 bits
        assertEquals(0x7FFF_7FFF_7FFF_7FFFL, vfp.low64(7));
        assertEquals(0x7FFF_7FFF_7FFF_7FFFL, vfp.high64(7));
    }

    // ── B13.1: D16-D31 endereçáveis e independentes de qualquer S ──

    @Test
    void doubleRegisters16To31AreAddressableAndHaveNoSingleView() {
        VfpRegisters vfp = new VfpRegisters();
        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            vfp.setS(i, 0x0BADF00D);
        }

        for (int i = 16; i < VfpRegisters.DOUBLE_COUNT; i++) {
            assertEquals(0L, vfp.d(i), "D" + i + " não deve ser tocado por nenhum S");
            vfp.setD(i, 0xFEED_FACE_0000_0000L | i);
        }
        // escrever D16-D31 não altera nenhum S (S só cobre D0-D15)
        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            assertEquals(0x0BADF00D, vfp.s(i));
        }
        for (int i = 16; i < VfpRegisters.DOUBLE_COUNT; i++) {
            assertEquals(0xFEED_FACE_0000_0000L | i, vfp.d(i));
        }
        assertEquals(32, VfpRegisters.DOUBLE_COUNT);
        assertEquals(16, VfpRegisters.QUAD_COUNT);
    }

    // ── B13.1: save-state nos DOIS formatos ──

    @Test
    void legacySaveStateRoundTripsS0ThroughS31Only() throws IOException {
        VfpRegisters vfp = new VfpRegisters();
        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            vfp.setS(i, i * 0x01010101);
        }
        for (int i = 16; i < VfpRegisters.DOUBLE_COUNT; i++) {
            vfp.setD(i, 0xDEADBEEF_00000000L | i);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        vfp.saveState(new DataOutputStream(buffer));
        assertEquals(VfpRegisters.SINGLE_COUNT * Integer.BYTES, buffer.size(),
                "formato legado = exatamente 32 int");

        VfpRegisters restored = new VfpRegisters();
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            assertEquals(i * 0x01010101, restored.s(i));
        }
        for (int i = 16; i < VfpRegisters.DOUBLE_COUNT; i++) {
            assertEquals(0L, restored.d(i), "formato legado não carrega D16-D31");
        }
    }

    @Test
    void extendedSaveStateRoundTripsTheFullD0ThroughD31Bank() throws IOException {
        VfpRegisters vfp = new VfpRegisters();
        for (int i = 0; i < VfpRegisters.DOUBLE_COUNT; i++) {
            vfp.setD(i, 0x1122_3344_0000_0000L | (i * 0x0101_0101L));
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        vfp.saveStateExtended(new DataOutputStream(buffer));
        assertEquals(VfpRegisters.DOUBLE_COUNT * Long.BYTES, buffer.size(),
                "formato estendido = exatamente 32 long");

        VfpRegisters restored = new VfpRegisters();
        restored.loadStateExtended(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        for (int i = 0; i < VfpRegisters.DOUBLE_COUNT; i++) {
            assertEquals(0x1122_3344_0000_0000L | (i * 0x0101_0101L), restored.d(i));
        }
        assertArrayEquals(vfp.snapshotD(), restored.snapshotD());
    }

    @Test
    void snapshotStaysIntArrayOfS0ThroughS31ForTheEquivalenceHarness() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, 0xAAAA_BBBB_CCCC_DDDDL);
        vfp.setD(20, -1L);

        int[] snap = vfp.snapshot();
        assertEquals(VfpRegisters.SINGLE_COUNT, snap.length);
        assertEquals(0xCCCC_DDDD, snap[0]);
        assertEquals(0xAAAA_BBBB, snap[1]);

        long[] snapD = vfp.snapshotD();
        assertEquals(VfpRegisters.DOUBLE_COUNT, snapD.length);
        assertEquals(-1L, snapD[20]);
    }

    @Test
    void resetZeroesTheWholeBank() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setS(10, 0x1234);
        vfp.setD(25, 0x5678);
        vfp.reset();
        assertEquals(0, vfp.s(10));
        assertEquals(0L, vfp.d(25));
    }
}
