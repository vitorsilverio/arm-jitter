package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.5 — `Thumb2MveGatherScatterDecoder`: `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (`@vldst_sg`) e
/// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (`@vldst_sg_imm`), layout confirmado
/// por leitura direta de `target/isa-decode/mve.decode` (ver Javadoc da classe).
class Thumb2MveGatherScatterDecoderTest {
    private static int offsetRaw(boolean u, int qd, int lMarker, int rn, int qm, int size, int msize, boolean os) {
        return (0b111 << 29)
                | ((u ? 1 : 0) << 28)
                | (0b1100 << 24)
                | (1 << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((lMarker & 0x3) << 20)
                | ((rn & 0xF) << 16)
                | ((qd & 0x7) << 13)
                | (0b111 << 9)
                | ((size & 0x3) << 7)
                | (((msize >>> 1) & 1) << 6)
                | (((qm >>> 3) & 1) << 5)
                | ((msize & 1) << 4)
                | ((qm & 0x7) << 1)
                | (os ? 1 : 0);
    }

    private static int immRaw(boolean load, boolean a, boolean w, int qd, int qm, int sizeMarker, int imm7) {
        return (0b111 << 29)
                | (1 << 28)
                | (0b1101 << 24)
                | ((a ? 1 : 0) << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((w ? 1 : 0) << 21)
                | ((load ? 1 : 0) << 20)
                | ((qm & 0x7) << 17)
                | ((qd & 0x7) << 13)
                | (1 << 12)
                | ((sizeMarker & 0xF) << 8)
                | (((qm >>> 3) & 1) << 7)
                | (imm7 & 0x7F);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveGatherScatterDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── Forma de offsets (@vldst_sg) ─────────────────────────────────────────────────────────

    @Test
    void decodesVldrSSgWidening() {
        // msize=0(byte),size=1(halfword),os=0: vldrb_sg_sh.
        int r = offsetRaw(false, 3, 0b01, 5, 1, 1, 0, false);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterOffset op = assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
        assertEquals(3, op.qd());
        assertEquals(1, op.qm());
        assertEquals(5, op.rn());
        assertEquals(0, op.memorySizeLog2());
        assertEquals(1, op.registerSizeLog2());
        assertEquals(true, op.signedLoad());
        assertEquals(false, op.offsetScaled());
        assertEquals(true, op.load());
    }

    @Test
    void decodesVldrUSgSameSizeNoWidening() {
        // msize=2(word),size=2(word): vldrw_sg_uw.
        int r = offsetRaw(true, 2, 0b01, 1, 4, 2, 2, false);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterOffset op = assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
        assertEquals(false, op.signedLoad());
        assertEquals(2, op.memorySizeLog2());
        assertEquals(2, op.registerSizeLog2());
    }

    @Test
    void decodesVldrUSgDoubleword() {
        int r = offsetRaw(true, 0, 0b01, 1, 2, 3, 3, false);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterOffset op = assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
        assertEquals(3, op.memorySizeLog2());
        assertEquals(3, op.registerSizeLog2());
    }

    @Test
    void decodesVstrSgStore() {
        int r = offsetRaw(false, 2, 0b00, 1, 3, 0, 0, false);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterOffset op = assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
        assertEquals(false, op.load());
    }

    @Test
    void decodesOffsetScaledForm() {
        int r = offsetRaw(true, 2, 0b01, 1, 3, 2, 1, true); // os=1: vldrh_sg_os_uw
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterOffset op = assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
        assertEquals(true, op.offsetScaled());
    }

    @Test
    void rejectsQdEqualsQmInOffsetForm() {
        int r = offsetRaw(true, 3, 0b01, 1, 3, 2, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsRnEqualsProgramCounter() {
        int r = offsetRaw(true, 2, 0b01, 15, 3, 2, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsInvalidMsizeSizeCombination() {
        // msize=2,size=0 não existe em nenhuma tabela real.
        int r = offsetRaw(true, 2, 0b01, 1, 3, 0, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsSignedFormWithoutWidening() {
        // VLDR_S_sg nunca tem msize==size (sempre alarga).
        int r = offsetRaw(false, 2, 0b01, 1, 3, 2, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsStoreWithUBitSet() {
        int r = offsetRaw(true, 2, 0b00, 1, 3, 0, 0, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsUnrecognizedLMarker() {
        int r = offsetRaw(true, 2, 0b10, 1, 3, 2, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // ── Forma imediata (@vldst_sg_imm) ───────────────────────────────────────────────────────

    @Test
    void decodesVldrwSgImmWithWriteback() {
        int r = immRaw(true, true, true, 2, 5, 0b1110, 4);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterImmediate op = assertInstanceOf(IrOp.MveGatherScatterImmediate.class, decoded.liftedOp());
        assertEquals(2, op.qd());
        assertEquals(5, op.qm());
        assertEquals(16, op.offset()); // imm7=4 << sizeLog2(2) = 16
        assertEquals(2, op.sizeLog2());
        assertEquals(true, op.writeback());
        assertEquals(true, op.load());
    }

    @Test
    void decodesVldrdSgImmNegativeOffset() {
        int r = immRaw(true, false, false, 3, 1, 0b1111, 2);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterImmediate op = assertInstanceOf(IrOp.MveGatherScatterImmediate.class, decoded.liftedOp());
        assertEquals(3, op.sizeLog2());
        assertEquals(-16, op.offset()); // imm7=2 << 3 = 16, a=0 -> negativo
        assertEquals(false, op.writeback());
    }

    @Test
    void decodesVstrwSgImm() {
        int r = immRaw(false, true, false, 2, 1, 0b1110, 0);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveGatherScatterImmediate op = assertInstanceOf(IrOp.MveGatherScatterImmediate.class, decoded.liftedOp());
        assertEquals(false, op.load());
    }

    @Test
    void rejectsQdEqualsQmOnLoadImmediateForm() {
        int r = immRaw(true, true, false, 2, 2, 0b1110, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void acceptsQdEqualsQmOnStoreImmediateForm() {
        // VSTRW_sg_imm não reescreve Qd, então a checagem real não se aplica.
        int r = immRaw(false, true, false, 2, 2, 0b1110, 0);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        assertInstanceOf(IrOp.MveGatherScatterImmediate.class, decoded.liftedOp());
    }

    @Test
    void rejectsInvalidSizeMarker() {
        int r = immRaw(true, true, false, 2, 1, 0b0000, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = offsetRaw(true, 2, 0b01, 1, 3, 2, 2, false);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    // ── Pipeline completo ────────────────────────────────────────────────────────────────────

    @Test
    void decodesAsGatherThroughTheFullThumbPipeline() {
        int r = offsetRaw(true, 2, 0b01, 1, 3, 2, 2, false);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveGatherScatterOffset.class, decoded.liftedOp());
    }
}
