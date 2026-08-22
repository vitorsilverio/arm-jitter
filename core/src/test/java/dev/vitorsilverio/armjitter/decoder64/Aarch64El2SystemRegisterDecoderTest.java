package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B10.2: `MRS`/`MSR (register)` do grupo EL2 (`op0=3,op1=4`). Diferente de
/// {@code Aarch64DecoderCorpusTest} (que usa `corpus.bin`, produzido por `aarch64-none-elf-as`
/// real), os opcodes aqui são DERIVADOS POR FÓRMULA — esta sessão não tem o toolchain devkitA64
/// disponível (sem `aarch64-none-elf-as`/`objdump` no PATH). A fórmula
/// (`0xD5000000 | L<<21 | op0<<19 | op1<<16 | CRn<<12 | CRm<<8 | op2<<5 | Rt`) foi conferida campo
/// a campo contra os 3 opcodes REAIS de `Aarch64VmsaSystemRegistersTest`/`corpus.bin` para
/// `SCTLR_EL1`/`TTBR0_EL1`/`TCR_EL1` (`op0=3,op1=0` — mesmo prefixo fixo, só `op1` muda para `4`
/// no grupo EL2) antes de gerar os valores abaixo. Task pendente: regenerar via toolchain real
/// quando disponível e migrar para `corpus.bin`/`corpus.s` (ver Armadilhas na task).
class Aarch64El2SystemRegisterDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op.SystemRegister decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsHcrEl2() {
        // mrs x0, hcr_el2 (op0=3,op1=4,CRn=1,CRm=1,op2=0,Rt=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53c1100);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.HCR_EL2, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrHcrEl2() {
        // msr hcr_el2, x0
        Ir64Op.SystemRegister op = decodeWord(0xd51c1100);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.HCR_EL2, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsSctlrEl2() {
        // mrs x1, sctlr_el2 (CRn=1,CRm=0,op2=0,Rt=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53c1001);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.SCTLR_EL2, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsVttbrEl2() {
        // mrs x2, vttbr_el2 (CRn=2,CRm=1,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53c2102);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.VTTBR_EL2, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void mrsVtcrEl2() {
        // mrs x3, vtcr_el2 (CRn=2,CRm=1,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd53c2143);
        assertEquals(Aarch64SystemRegisterId.VTCR_EL2, op.register());
    }

    @Test
    void mrsTcrEl2() {
        // mrs x4, tcr_el2 (CRn=2,CRm=0,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd53c2044);
        assertEquals(Aarch64SystemRegisterId.TCR_EL2, op.register());
    }

    @Test
    void mrsMdcrEl2() {
        // mrs x5, mdcr_el2 (CRn=1,CRm=1,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53c1125);
        assertEquals(Aarch64SystemRegisterId.MDCR_EL2, op.register());
    }

    @Test
    void mrsCptrEl2() {
        // mrs x6, cptr_el2 (CRn=1,CRm=1,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd53c1146);
        assertEquals(Aarch64SystemRegisterId.CPTR_EL2, op.register());
    }

    @Test
    void mrsSpsrEl2() {
        // mrs x7, spsr_el2 (CRn=4,CRm=0,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53c4007);
        assertEquals(Aarch64SystemRegisterId.SPSR_EL2, op.register());
    }

    @Test
    void mrsElrEl2() {
        // mrs x8, elr_el2 (CRn=4,CRm=0,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd53c4028);
        assertEquals(Aarch64SystemRegisterId.ELR_EL2, op.register());
    }

    @Test
    void mrsFarEl2() {
        // mrs x9, far_el2 (CRn=6,CRm=0,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53c6009);
        assertEquals(Aarch64SystemRegisterId.FAR_EL2, op.register());
    }

    @Test
    void mrsEsrEl2() {
        // mrs x10, esr_el2 (CRn=5,CRm=2,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53c520a);
        assertEquals(Aarch64SystemRegisterId.ESR_EL2, op.register());
    }

    @Test
    void mrsCnthctlEl2() {
        // mrs x11, cnthctl_el2 (CRn=14,CRm=1,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53ce10b);
        assertEquals(Aarch64SystemRegisterId.CNTHCTL_EL2, op.register());
    }

    @Test
    void mrsVbarEl2() {
        // mrs x12, vbar_el2 (CRn=12,CRm=0,op2=0)
        Ir64Op.SystemRegister op = decodeWord(0xd53cc00c);
        assertEquals(Aarch64SystemRegisterId.VBAR_EL2, op.register());
    }
}
