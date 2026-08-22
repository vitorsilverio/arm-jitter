package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B10.3: `MRS`/`MSR (register)` do grupo EL3 (`op0=3,op1=6`). Mesma disciplina de
/// {@code Aarch64El2SystemRegisterDecoderTest}: toolchain devkitA64 indisponível nesta sessão
/// também, opcodes DERIVADOS POR FÓRMULA
/// (`0xD5000000 | L<<21 | op0<<19 | op1<<16 | CRn<<12 | CRm<<8 | op2<<5 | Rt`), conferida contra
/// os opcodes já existentes no repo para o grupo EL2 (`op1` muda de `4` para `6`).
class Aarch64El3SystemRegisterDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op.SystemRegister decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsSctlrEl3() {
        // mrs x0, sctlr_el3 (op0=3,op1=6,CRn=1,CRm=0,op2=0,Rt=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53e1000);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.SCTLR_EL3, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsScrEl3() {
        // mrs x1, scr_el3 (CRn=1,CRm=1,op2=0,Rt=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53e1101);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.SCR_EL3, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void msrScrEl3() {
        // msr scr_el3, x1
        Ir64Op.SystemRegister op = decodeWord(0xd51e1101);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.SCR_EL3, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsCptrEl3() {
        // mrs x2, cptr_el3 (CRn=1,CRm=1,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd53e1142);
        assertEquals(Aarch64SystemRegisterId.CPTR_EL3, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void mrsMdcrEl3() {
        // mrs x3, mdcr_el3 (CRn=1,CRm=3,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53e1323);
        assertEquals(Aarch64SystemRegisterId.MDCR_EL3, op.register());
        assertEquals(3, op.rt());
    }

    @Test
    void mrsVbarEl3() {
        // mrs x4, vbar_el3 (CRn=12,CRm=0,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53ec004);
        assertEquals(Aarch64SystemRegisterId.VBAR_EL3, op.register());
        assertEquals(4, op.rt());
    }

    @Test
    void mrsElrEl3() {
        // mrs x5, elr_el3 (CRn=4,CRm=0,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53e4025);
        assertEquals(Aarch64SystemRegisterId.ELR_EL3, op.register());
        assertEquals(5, op.rt());
    }

    @Test
    void mrsSpsrEl3() {
        // mrs x6, spsr_el3 (CRn=4,CRm=0,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53e4006);
        assertEquals(Aarch64SystemRegisterId.SPSR_EL3, op.register());
        assertEquals(6, op.rt());
    }
}
