package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.3 — `Thumb2MveLoadStoreDecoder`: `VLDR_VSTR` contíguo não-alargante (`target/isa-decode/
/// mve.decode`, linhas 40/60/176-188). Raws construídos bit a bit a partir do layout confirmado
/// via `WebFetch` (ver Javadoc da classe): `bits[31:25]=1110110`, `P`(24)/`A`(23)/`Qd-alto`(22)/
/// `W`(21)/`L`(20)/`Rn`(19:16)/`Qd-baixo`(15:13)/marcador de `size`(12:7)/`imm7`(6:0).
class Thumb2MveLoadStoreDecoderTest {
    private static final int SIZE_MARKER_BYTE = 0b111100;
    private static final int SIZE_MARKER_HALFWORD = 0b111101;
    private static final int SIZE_MARKER_WORD = 0b111110;
    private static final int SIZE_MARKER_RESERVED = 0b111111;

    private static int raw(boolean p, boolean a, int qd, boolean w, boolean l, int rn, int sizeMarker, int imm7) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b1110110 << 25)
                | ((p ? 1 : 0) << 24)
                | ((a ? 1 : 0) << 23)
                | (qdHigh << 22)
                | ((w ? 1 : 0) << 21)
                | ((l ? 1 : 0) << 20)
                | (rn << 16)
                | (qdLow << 13)
                | (sizeMarker << 7)
                | (imm7 & 0x7F);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveLoadStoreDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    @Test
    void decodesPreIndexedLoadWithScaledOffset() {
        // P=1,W=1 (pré-index com writeback), A=1 (soma), size=2 (word, imm7<<2), imm7=5 -> offset=20.
        int r = raw(true, true, 3, true, true, 1, SIZE_MARKER_WORD, 5);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveLoadStore op = assertInstanceOf(IrOp.MveLoadStore.class, decoded.liftedOp());
        assertEquals(3, op.qd());
        assertEquals(1, op.rn());
        assertEquals(20, op.offset());
        assertEquals(true, op.load());
        assertEquals(true, op.writeback());
        assertEquals(false, op.postIndexed());
    }

    @Test
    void decodesPostIndexedStoreWithNegativeByteOffset() {
        // P=0 (força pós-index, W=1 obrigatório), A=0 (subtrai), size=0 (byte, sem escala), L=0 (store).
        int r = raw(false, false, 7, true, false, 2, SIZE_MARKER_BYTE, 9);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveLoadStore op = assertInstanceOf(IrOp.MveLoadStore.class, decoded.liftedOp());
        assertEquals(7, op.qd());
        assertEquals(2, op.rn());
        assertEquals(-9, op.offset());
        assertEquals(false, op.load());
        assertEquals(true, op.writeback());
        assertEquals(true, op.postIndexed());
    }

    @Test
    void decodesOffsetAddressingWithoutWriteback() {
        // P=1,W=0: offset addressing puro, sem writeback. size=1 (halfword, imm7<<1).
        int r = raw(true, true, 0, false, true, 4, SIZE_MARKER_HALFWORD, 3);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveLoadStore op = assertInstanceOf(IrOp.MveLoadStore.class, decoded.liftedOp());
        assertEquals(6, op.offset(), "imm7=3 << size=1 -> 6");
        assertEquals(false, op.writeback());
        assertEquals(false, op.postIndexed());
    }

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = raw(true, true, 0, false, true, 4, SIZE_MARKER_WORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    @Test
    void rejectsProgramCounterAsBase() {
        int r = raw(true, true, 0, false, true, 15, SIZE_MARKER_WORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsStackPointerAsBaseWithWriteback() {
        int r = raw(true, true, 0, true, true, 13, SIZE_MARKER_WORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void allowsStackPointerAsBaseWithoutWriteback() {
        int r = raw(true, true, 0, false, true, 13, SIZE_MARKER_WORD, 0);
        assertInstanceOf(IrOp.MveLoadStore.class, tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    @Test
    void rejectsQuadRegisterOutsideQ0ToQ7() {
        int r = raw(true, true, 8, false, true, 1, SIZE_MARKER_WORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsSizeThree() {
        int r = raw(true, true, 0, false, true, 1, SIZE_MARKER_RESERVED, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsRelatedEncodingPZeroWZero() {
        // P=0,W=0: "related encoding" (B16.4/B15.3), não é VLDR_VSTR.
        int r = raw(false, true, 0, false, true, 1, SIZE_MARKER_WORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // ── Pipeline completo: nenhuma das 6 vira NOCP sob ARMV8_1M_MVE (Armadilha 1) ─────────────────

    @Test
    void decodesAsMveLoadStoreThroughTheFullThumbPipeline() {
        int r = raw(true, true, 3, true, true, 1, SIZE_MARKER_WORD, 5);
        DecodedInstruction decoded = decodeThumb32(r);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveLoadStore.class, decoded.liftedOp());
    }

    private static DecodedInstruction decodeThumb32(int raw32) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
    }
}
