package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.5 — `Thumb2MveIncrementDupDecoder`: `VIDUP`/`VDDUP` (`@vidup`) e `VIWDUP`/`VDWDUP`
/// (`@viwdup`), layout confirmado por leitura direta de `target/isa-decode/mve.decode` (ver
/// Javadoc da classe).
class Thumb2MveIncrementDupDecoderTest {
    private static final int NO_RM = 0b111;

    private static int raw(int qd, int size, int rnRaw, boolean decrement, int rmField, int rawImm) {
        return (0b1110_1110 << 24)
                | (((qd >>> 3) & 1) << 22)
                | ((size & 0x3) << 20)
                | ((rnRaw & 0x7) << 17)
                | (1 << 16)
                | ((qd & 0x7) << 13)
                | ((decrement ? 1 : 0) << 12)
                | (0b1111 << 8)
                | (((rawImm >>> 1) & 1) << 7)
                | (0b110 << 4)
                | ((rmField & 0x7) << 1)
                | (rawImm & 1);
    }

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int r) {
        return new Thumb2MveIncrementDupDecoder(architecture).tryDecode(r, 0, Condition.AL);
    }

    @Test
    void decodesVidup() {
        int r = raw(2, 1, 3, false, NO_RM, 0b10); // rawImm=2 -> imm=1<<2=4
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveIncrementDup op = assertInstanceOf(IrOp.MveIncrementDup.class, decoded.liftedOp());
        assertEquals(2, op.qd());
        assertEquals(6, op.rn()); // rnRaw=3 -> Rn=6 (sempre par)
        assertEquals(1, op.sizeLog2());
        assertEquals(4, op.imm());
    }

    @Test
    void decodesVddupNegatesImm() {
        int r = raw(2, 1, 3, true, NO_RM, 0b10);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveIncrementDup op = assertInstanceOf(IrOp.MveIncrementDup.class, decoded.liftedOp());
        assertEquals(-4, op.imm());
    }

    @Test
    void decodesViwdup() {
        int r = raw(1, 0, 2, false, 5, 0b01); // rmField=5 -> Rm=5*2+1=11
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWrappingIncrementDup op = assertInstanceOf(IrOp.MveWrappingIncrementDup.class, decoded.liftedOp());
        assertEquals(4, op.rn());
        assertEquals(11, op.rm());
        assertEquals(2, op.imm()); // rawImm=0b01 -> x=(hi<<1|lo)=1 -> imm=1<<1=2
        assertEquals(false, op.decrement());
    }

    @Test
    void decodesVdwdup() {
        int r = raw(1, 0, 2, true, 5, 0b00);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, r);
        IrOp.MveWrappingIncrementDup op = assertInstanceOf(IrOp.MveWrappingIncrementDup.class, decoded.liftedOp());
        assertEquals(true, op.decrement());
        assertEquals(1, op.imm()); // rawImm=0 -> imm=1<<0=1
    }

    @Test
    void rejectsRmEqualsStackPointer() {
        int r = raw(1, 0, 2, false, 6, 0); // rmField=6 -> Rm=13
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // Nota: Rm=15 (`rmField=7`) é INALCANÇÁVEL nesta forma — `rmField=0b111` é exatamente o
    // literal que o decodetree usa para selecionar VIDUP/VDDUP (sem Rm) em vez de VIWDUP/VDWDUP
    // (Armadilha 3), então a checagem `rm==15` do QEMU real é defensiva/morta aqui — só `rm==13`
    // (`rmField=6`) é alcançável e testado acima.

    @Test
    void rejectsDoublewordSize() {
        int r = raw(1, 3, 2, false, NO_RM, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void doesNotDecodeWithoutMveInteger() {
        int r = raw(1, 0, 2, false, NO_RM, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r));
    }

    @Test
    void decodesThroughTheFullThumbPipeline() {
        int r = raw(1, 0, 2, false, NO_RM, 0);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertInstanceOf(IrOp.MveIncrementDup.class, decoded.liftedOp());
    }
}
