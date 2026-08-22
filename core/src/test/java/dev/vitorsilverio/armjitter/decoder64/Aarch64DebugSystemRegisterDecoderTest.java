package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B10.7: `MRS`/`MSR (register)` do grupo de debug (`op0=2,op1=0`). Mesma disciplina de
/// {@code Aarch64El2SystemRegisterDecoderTest}/{@code Aarch64El3SystemRegisterDecoderTest}:
/// toolchain devkitA64 indisponível nesta sessão também, opcodes DERIVADOS POR FÓRMULA
/// (`0xD5000000 | L<<21 | op0<<19 | op1<<16 | CRn<<12 | CRm<<8 | op2<<5 | Rt`), conferida contra
/// os opcodes já existentes no repo para os grupos EL2/EL3 (só `op0` muda de `3` para `2`).
class Aarch64DebugSystemRegisterDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op.SystemRegister decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsMdscrEl1() {
        // mrs x0, mdscr_el1 (op0=2,op1=0,CRn=0,CRm=2,op2=2,Rt=0)
        Ir64Op.SystemRegister op = decodeWord(0xd5300240);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.MDSCR_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrMdscrEl1() {
        // msr mdscr_el1, x1
        Ir64Op.SystemRegister op = decodeWord(0xd5100241);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.MDSCR_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsOslarEl1() {
        // mrs x0, oslar_el1 (CRn=1,CRm=0,op2=4) — WO no hardware real, decodificado mesmo assim
        Ir64Op.SystemRegister op = decodeWord(0xd5301080);
        assertEquals(Aarch64SystemRegisterId.OSLAR_EL1, op.register());
    }

    @Test
    void msrOslarEl1() {
        // msr oslar_el1, x2
        Ir64Op.SystemRegister op = decodeWord(0xd5101082);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.OSLAR_EL1, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void mrsOslsrEl1() {
        // mrs x3, oslsr_el1 (CRn=1,CRm=1,op2=4)
        Ir64Op.SystemRegister op = decodeWord(0xd5301183);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.OSLSR_EL1, op.register());
        assertEquals(3, op.rt());
    }

    @Test
    void mrsDbgbvr0El1() {
        // mrs x5, dbgbvr0_el1 (CRn=0,CRm=0,op2=4)
        Ir64Op.SystemRegister op = decodeWord(0xd5300085);
        assertEquals(Aarch64SystemRegisterId.DBGBVR0_EL1, op.register());
        assertEquals(5, op.rt());
    }

    @Test
    void msrDbgbcr0El1() {
        // msr dbgbcr0_el1, x8 (CRn=0,CRm=0,op2=5)
        Ir64Op.SystemRegister op = decodeWord(0xd51000a8);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.DBGBCR0_EL1, op.register());
        assertEquals(8, op.rt());
    }

    @Test
    void mrsDbgwvr0El1() {
        // mrs x9, dbgwvr0_el1 (CRn=0,CRm=0,op2=6)
        Ir64Op.SystemRegister op = decodeWord(0xd53000c9);
        assertEquals(Aarch64SystemRegisterId.DBGWVR0_EL1, op.register());
        assertEquals(9, op.rt());
    }

    @Test
    void msrDbgwcr0El1() {
        // msr dbgwcr0_el1, x12 (CRn=0,CRm=0,op2=7)
        Ir64Op.SystemRegister op = decodeWord(0xd51000ec);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.DBGWCR0_EL1, op.register());
        assertEquals(12, op.rt());
    }
}
