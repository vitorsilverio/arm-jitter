package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Gaps reais de `MRS`/`MSR (register)` achados retomando o boot da F11 (`virtual-arm-box`,
/// `kernel8.img` real do Raspberry Pi 3) depois de `B6.13`/`B6.14` fecharem o sétimo bloqueio
/// (`TTBR1_EL1`/corrupção de `SP`): `CPACR_EL1` (`op0=3,op1=0,CRn=1,CRm=0,op2=2`, escrito logo
/// após `SCTLR_EL1` em `head.S`) e o grupo inteiro de registradores de identidade `ID_AA64*`
/// (`CRn=0,CRm=4-7`) que `head.S`/`cpufeature.c` sondam em sequência — resolvidos de uma vez,
/// em vez de um por sessão, para não repetir o custo de um boot inteiro por gap. Encodings
/// conferidos via `aarch64-none-elf-as`/`aarch64-none-elf-objdump` reais (devkitA64, disponível
/// nesta sessão) — G1.
class Aarch64F11SystemRegisterGapsDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op.SystemRegister decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void msrCpacrEl1Xzr() {
        // msr cpacr_el1, xzr
        Ir64Op.SystemRegister op = decodeWord(0xd518105f);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.CPACR_EL1, op.register());
        assertEquals(31, op.rt());
    }

    @Test
    void mrsCpacrEl1() {
        // mrs x0, cpacr_el1
        Ir64Op.SystemRegister op = decodeWord(0xd5381040);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.CPACR_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrCpacrEl1X1() {
        // msr cpacr_el1, x1
        Ir64Op.SystemRegister op = decodeWord(0xd5181041);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.CPACR_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsIdAa64Mmfr1El1() {
        // mrs x9, id_aa64mmfr1_el1 (CRn=0,CRm=7,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd5380729);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64MMFR1_EL1, op.register());
        assertEquals(9, op.rt());
    }

    @Test
    void mrsIdAa64Mmfr3El1() {
        // mrs x1, id_aa64mmfr3_el1 (CRn=0,CRm=7,op2=3) — bloqueio real que motivou completar o
        // grupo inteiro nesta sessão em vez de gap-a-gap.
        Ir64Op.SystemRegister op = decodeWord(0xd5380761);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64MMFR3_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsIdAa64Mmfr2El1() {
        // mrs x0, id_aa64mmfr2_el1 (CRn=0,CRm=7,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd5380740);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64MMFR2_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Mmfr4El1() {
        // mrs x0, id_aa64mmfr4_el1 (CRn=0,CRm=7,op2=4)
        Ir64Op.SystemRegister op = decodeWord(0xd5380780);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64MMFR4_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Pfr1El1() {
        // mrs x0, id_aa64pfr1_el1 (CRn=0,CRm=4,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd5380420);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64PFR1_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Zfr0El1() {
        // mrs x0, id_aa64zfr0_el1 (CRn=0,CRm=4,op2=4)
        Ir64Op.SystemRegister op = decodeWord(0xd5380480);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Dfr1El1() {
        // mrs x0, id_aa64dfr1_el1 (CRn=0,CRm=5,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd5380520);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64DFR1_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Isar1El1() {
        // mrs x0, id_aa64isar1_el1 (CRn=0,CRm=6,op2=1)
        Ir64Op.SystemRegister op = decodeWord(0xd5380620);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64ISAR1_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsIdAa64Isar2El1() {
        // mrs x0, id_aa64isar2_el1 (CRn=0,CRm=6,op2=2)
        Ir64Op.SystemRegister op = decodeWord(0xd5380640);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.ID_AA64ISAR2_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrTtbr1El1() {
        // msr ttbr1_el1, x1 (CRn=2,CRm=0,op2=1) — OITAVO bloqueio real: diferente dos anteriores,
        // implementado de verdade (TranslatingAddressSpace64#setTtbr1), não só armazenamento.
        Ir64Op.SystemRegister op = decodeWord(0xd5182021);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.TTBR1_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsTtbr1El1() {
        // mrs x2, ttbr1_el1
        Ir64Op.SystemRegister op = decodeWord(0xd5382022);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.TTBR1_EL1, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void mrsRevidrEl1() {
        // mrs x0, revidr_el1 (CRn=0,CRm=0,op2=6)
        Ir64Op.SystemRegister op = decodeWord(0xd53800c0);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.REVIDR_EL1, op.register());
        assertEquals(0, op.rt());
    }
}
