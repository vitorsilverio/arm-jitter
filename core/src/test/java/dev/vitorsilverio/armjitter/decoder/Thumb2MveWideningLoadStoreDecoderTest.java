package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.4 — `Thumb2MveWideningLoadStoreDecoder`: `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (`target/isa-decode/
/// mve.decode`, linhas 163-174). Raws construídos bit a bit a partir do layout confirmado via
/// `WebFetch` (ver Javadoc da classe): `bits[31:29]=111`, `U`(28), `bits[27:25]=110`, `P`(24),
/// `A`(23), literal `0`(22, NÃO é `Qd`-alto — Armadilha 1), `W`(21), `L`(20), `H`(19, tamanho em
/// memória: `0`=byte/`1`=halfword), `Rn`(18:16, 3 bits), `Qd`(15:13, 3 bits), literal `0`(12),
/// `bits[11:9]=111`, marcador do tamanho no REGISTRADOR(8:7: `01`=halfword,`10`=word), `imm7`(6:0).
class Thumb2MveWideningLoadStoreDecoderTest {
    private static final int SIZE_MARKER_HALFWORD = 0b01;
    private static final int SIZE_MARKER_WORD = 0b10;

    private static int raw(boolean p, boolean a, boolean w, boolean l, boolean u, boolean memHalfword,
            int rn, int qd, int sizeMarker, int imm7) {
        return (0b111 << 29)
                | ((u ? 1 : 0) << 28)
                | (0b110 << 25)
                | ((p ? 1 : 0) << 24)
                | ((a ? 1 : 0) << 23)
                | ((w ? 1 : 0) << 21)
                | ((l ? 1 : 0) << 20)
                | ((memHalfword ? 1 : 0) << 19)
                | ((rn & 0x7) << 16)
                | ((qd & 0x7) << 13)
                | (0b111 << 9)
                | (sizeMarker << 7)
                | (imm7 & 0x7F);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveWideningLoadStoreDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    @Test
    void decodesVldstbHSignedLoadWithByteScaledOffset() {
        // VLDSTB_H: P=1,W=1, A=1, U=0 (sinal), memória=byte, registrador=halfword, imm7=5 -> offset=5 (<<0).
        int r = raw(true, true, true, true, false, false, 1, 3, SIZE_MARKER_HALFWORD, 5);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWideningLoadStore op = assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
        assertEquals(3, op.qd());
        assertEquals(1, op.rn());
        assertEquals(5, op.offset());
        assertEquals(0, op.memorySizeLog2());
        assertEquals(1, op.registerSizeLog2());
        assertEquals(true, op.load());
        assertEquals(true, op.signed());
        assertEquals(true, op.writeback());
        assertEquals(false, op.postIndexed());
    }

    @Test
    void decodesVldstbHUnsignedLoad() {
        int r = raw(true, true, false, true, true, false, 2, 0, SIZE_MARKER_HALFWORD, 0);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWideningLoadStore op = assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
        assertEquals(false, op.signed());
    }

    @Test
    void decodesVldstbWByteToWord() {
        int r = raw(true, true, false, true, false, false, 1, 4, SIZE_MARKER_WORD, 0);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWideningLoadStore op = assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
        assertEquals(0, op.memorySizeLog2());
        assertEquals(2, op.registerSizeLog2());
    }

    @Test
    void decodesVldsthWWithHalfwordScaledOffset() {
        // VLDSTH_W: memória=halfword, registrador=word; imm7=5 -> offset=10 (<<1).
        int r = raw(true, true, false, true, false, true, 1, 4, SIZE_MARKER_WORD, 5);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWideningLoadStore op = assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
        assertEquals(1, op.memorySizeLog2());
        assertEquals(2, op.registerSizeLog2());
        assertEquals(10, op.offset());
    }

    @Test
    void decodesPostIndexedStoreWithNegativeOffset() {
        // P=0 (força pós-index, W=1 obrigatório), A=0 (subtrai), L=0 (store), U=0 obrigatório.
        int r = raw(false, false, true, false, false, false, 2, 7, SIZE_MARKER_HALFWORD, 9);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWideningLoadStore op = assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
        assertEquals(false, op.load());
        assertEquals(-9, op.offset());
        assertEquals(true, op.writeback());
        assertEquals(true, op.postIndexed());
    }

    @Test
    void rejectsStoreWithUnsignedBit() {
        // "For stores the U bit must be 0 but we catch that in the trans_ function" (comentário real).
        int r = raw(true, true, false, false, true, false, 1, 0, SIZE_MARKER_HALFWORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsRelatedEncodingPZeroWZero() {
        int r = raw(false, true, false, true, false, false, 1, 0, SIZE_MARKER_HALFWORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsSizeMarkerReserved() {
        // marcador 11 ("sz=11 related encoding") e 00 (não usado) ambos rejeitados.
        int r11 = raw(true, true, false, true, false, false, 1, 0, 0b11, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r11));
        int r00 = raw(true, true, false, true, false, false, 1, 0, 0b00, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r00));
    }

    @Test
    void rejectsHalfwordToHalfwordCombinationThatDoesNotExist() {
        // H=1 (memória halfword) + marcador=01 (registrador halfword): "VLDSTH_H" não existe.
        int r = raw(true, true, false, true, false, true, 1, 0, SIZE_MARKER_HALFWORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void bit22SetDoesNotMatchThisInstruction() {
        // Armadilha 1: bit22 é literal fixo 0 aqui (não é o bit alto de Qd da B16.3) — setado, a
        // instrução não decodifica como esta família.
        int r = raw(true, true, false, true, false, false, 1, 3, SIZE_MARKER_HALFWORD, 0) | (1 << 22);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = raw(true, true, false, true, false, false, 1, 0, SIZE_MARKER_HALFWORD, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    // ── Pipeline completo: não vira NOCP sob ARMV8_1M_MVE (mesma Armadilha 3 da B16.3) ─────────────

    @Test
    void decodesAsMveWideningLoadStoreThroughTheFullThumbPipeline() {
        int r = raw(true, true, false, true, false, false, 1, 3, SIZE_MARKER_HALFWORD, 5);
        DecodedInstruction decoded = decodeThumb32(r);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveWideningLoadStore.class, decoded.liftedOp());
    }

    private static DecodedInstruction decodeThumb32(int raw32) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
    }
}
