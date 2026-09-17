package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.5 — `Thumb2MveInterleavedLoadStoreDecoder`: `VLD2`/`VLD4`/`VST2`/`VST4` (`@vldst_il`), layout
/// confirmado por leitura direta de `target/isa-decode/mve.decode` (ver Javadoc da classe).
class Thumb2MveInterleavedLoadStoreDecoderTest {
    private static int raw(boolean load, boolean w, int rn, int qd, int size, int pat, int groupMarker) {
        return (0b1111_1100 << 24)
                | (1 << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((w ? 1 : 0) << 21)
                | ((load ? 1 : 0) << 20)
                | ((rn & 0xF) << 16)
                | ((qd & 0x7) << 13)
                | (1 << 12)
                | (0b111 << 9)
                | ((size & 0x3) << 7)
                | ((pat & 0x3) << 5)
                | (groupMarker & 0x1F);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int r) {
        return new Thumb2MveInterleavedLoadStoreDecoder(architecture).tryDecode(r, 0, Condition.AL);
    }

    @Test
    void decodesVld2() {
        int r = raw(true, true, 1, 2, 1, 1, 0b00000);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveInterleavedLoadStore op = assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
        assertEquals(2, op.qd());
        assertEquals(1, op.rn());
        assertEquals(2, op.groupSize());
        assertEquals(1, op.sizeLog2());
        assertEquals(1, op.pat());
        assertEquals(true, op.load());
        assertEquals(true, op.writeback());
    }

    @Test
    void decodesVld4() {
        int r = raw(true, false, 1, 4, 2, 3, 0b00001);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveInterleavedLoadStore op = assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
        assertEquals(4, op.groupSize());
        assertEquals(3, op.pat());
    }

    @Test
    void decodesVst2() {
        int r = raw(false, false, 1, 0, 0, 0, 0b00000);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveInterleavedLoadStore op = assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
        assertEquals(false, op.load());
    }

    @Test
    void decodesVst4() {
        int r = raw(false, false, 1, 0, 0, 2, 0b00001);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveInterleavedLoadStore op = assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
        assertEquals(false, op.load());
        assertEquals(4, op.groupSize());
    }

    @Test
    void rejectsVld2WithPatAboveOne() {
        int r = raw(true, false, 1, 0, 0, 2, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsVld2QdAboveSix() {
        int r = raw(true, false, 1, 7, 0, 0, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsVld4QdAboveFour() {
        int r = raw(true, false, 1, 5, 0, 0, 0b00001);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsDoublewordSize() {
        int r = raw(true, false, 1, 0, 3, 0, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsProgramCounterBase() {
        int r = raw(true, false, 15, 0, 0, 0, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsStackPointerBaseWithWriteback() {
        int r = raw(true, true, 13, 0, 0, 0, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void acceptsStackPointerBaseWithoutWriteback() {
        int r = raw(true, false, 13, 0, 0, 0, 0b00000);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
    }

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = raw(true, false, 1, 0, 0, 0, 0b00000);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    @Test
    void decodesThroughTheFullThumbPipeline() {
        int r = raw(true, false, 1, 0, 0, 0, 0b00000);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveInterleavedLoadStore.class, decoded.liftedOp());
    }
}
