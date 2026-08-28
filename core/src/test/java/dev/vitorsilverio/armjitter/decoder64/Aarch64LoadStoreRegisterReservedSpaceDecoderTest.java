package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Regressão de decode (B11.3, achado real auditando `docs/COBERTURA-ISA.md` por versão ARM): a
/// classe "Loads and stores" (`x1x0`) tem dois espaços de encoding reservados que
/// `decodeLoadsAndStores`/`decodeLoadStoreSingle`/`decodeFpLoadStoreSingle` aceitavam
/// silenciosamente antes desta task, produzindo instruções ERRADAS em vez de recusar (G8):
///
/// 1. Dentro do bucket `SUBCLASS_LITERAL` (bits `[29:28]=01`), `LDR (literal)`/`LDRSW (literal)`/
///    `PRFM (literal)` reais exigem bit24=0 (`ARM DDI 0487 C4.1.3`, `@ldlit`/`LD_lit` do
///    `a64.decode` real do QEMU). bit24=1 no MESMO bucket é "Memory Copy and Memory Set"
///    (`CPYFP`/`CPYFM`/`CPYFE`/`SETP`/`SETM`/`SETE`, `FEAT_MOPS`/ARMv8.8-A) e "Atomic 128-bit
///    memory operations" (`LDCLRP`/`LDSETP`/`SWPP`, `FEAT_LSE128`) — sem checar bit24, esses 9
///    mnemônicos eram misdecodificados como `LDR (literal)` de um `Rt`/offset que na verdade eram
///    campos completamente diferentes (`Rs`/`options`/`Rn`/`Rd` do MOPS, `a`/`r`/`Rt2` do atômico
///    128-bit). Achado colateral: `LDAPR_i` (`LDAPUR`, `FEAT_LRCPC2`/ARMv8.4-A) também caía no
///    mesmo bucket com bit24=1, então também era um falso positivo, não uma feature já
///    implementada.
/// 2. Dentro de `decodeLoadStoreSingle`/`decodeFpLoadStoreSingle`, `idx==POST_INDEX`/`PRE_INDEX`
///    com bit21=1 não tinha checagem nenhuma — os formatos `@ldst_imm*` reais exigem bit21=0
///    nessas duas formas (só `UNSCALED`/`REGISTER_OFFSET` legitimamente têm bit21=1, e os dois já
///    eram tratados). bit21=1 nesse espaço é `LDRA`/`LDRAB` ("Load/store register (pointer
///    authentication)", `FEAT_PAuth`/ARMv8.3-A) — sem a checagem, `LDRA*` era misdecodificado como
///    `STR`/`STUR`/`LDUR` de um `Rn` que na verdade era a chave de modificador do PAC.
///
/// Todos os 6 casos abaixo foram confirmados por um probe direto no decoder ANTES do fix (todos
/// produziam um `Ir64Op` válido em vez de lançar) e a `docs/isa-nao-aplicavel.tsv` ganhou entradas
/// correspondentes (a auditoria de versão os move de ✅ para ❌/`·`, valor correto — nenhum dos 6
/// tem semântica real implementada). `CPYP`/`CPYM`/`CPYE`/`SETGP`/`SETGM`/`SETGE` (mesma família,
/// bit26/CRn diferentes) já estavam corretamente `·` antes desta task, ver TSV.
class Aarch64LoadStoreRegisterReservedSpaceDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeAt(long address, int word) {
        TestAddressSpace raw = new TestAddressSpace((int) address + 4);
        raw.put32((int) address, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), address);
    }

    // ── Bucket SUBCLASS_LITERAL, bit24=1 (MOPS / atômico 128-bit) — devem ser recusados ─────────

    @Test
    void cpyfpIsRejectedInsteadOfMisdecodedAsLoadLiteral() {
        // CPYFP rs=0,rn=0,rd=0 (`00 011 0 01000 00000 0000 01 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0x19000400));
    }

    @Test
    void setpIsRejectedInsteadOfMisdecodedAsLoadLiteral() {
        // SETP rs=0,rn=0,rd=0 (`00 011001110 00000 00 0 0 01 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0x19C00400));
    }

    @Test
    void ldclrpIsRejectedInsteadOfMisdecodedAsLoadLiteral() {
        // LDCLRP a=0,r=0,rt2=0,rn=0,rt=0 (`00011001 0 0 1 00000 000100 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0x19201000));
    }

    @Test
    void swppIsRejectedInsteadOfMisdecodedAsLoadLiteral() {
        // SWPP a=0,r=0,rt2=0,rn=0,rt=0 (`00011001 0 0 1 00000 100000 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0x19208000));
    }

    @Test
    void ldapriIsRejectedInsteadOfMisdecodedAsLoadLiteral() {
        // LDAPR_i sz=00,imm=0,rn=0,rt=0 (`00 011001 01 0 000000000 00 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0x19400000));
    }

    // ── Bucket SUBCLASS_SINGLE, idx=POST_INDEX+bit21=1 (LDRA) — deve ser recusado ────────────────

    @Test
    void ldraIsRejectedInsteadOfMisdecodedAsStore() {
        // LDRA m=0,imm=0,w=0,rn=0,rt=0 (`11 111 0 00 0 0 1 000000000 0 1 00000 00000`)
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(0, 0xF8200400));
    }

    // ── Regressão positiva: as formas REAIS que compartilham os mesmos buckets continuam ─────────
    // ── decodificando normalmente (o fix não pode rejeitar nada legítimo) ─────────────────────────

    @Test
    void ldrLiteralStillDecodesWithBit24Clear() {
        // LDR (literal) w0, #0 — sz=00,V=0,imm19=0,rt=0
        Ir64Op.LoadLiteral64 op = (Ir64Op.LoadLiteral64) decodeAt(0x1000, 0x18000000);
        assertEquals(0, op.rt());
        assertEquals(0x1000L, op.address());
    }

    @Test
    void sturXStillDecodesWithBit21Clear() {
        // STUR x0, [x0] — idx=UNSCALED(00), bit21=0
        Ir64Op.Store64 op = (Ir64Op.Store64) decodeAt(0, 0xF8000000);
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
    }

    @Test
    void strXPostIndexStillDecodes() {
        // STR x0, [x0], #0 — idx=POST_INDEX(01), bit21=0
        Ir64Op.Store64 op = (Ir64Op.Store64) decodeAt(0, 0xF8000400);
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
    }

    @Test
    void strXPreIndexStillDecodes() {
        // STR x0, [x0, #0]! — idx=PRE_INDEX(11), bit21=0
        Ir64Op.Store64 op = (Ir64Op.Store64) decodeAt(0, 0xF8000C00);
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
    }

    @Test
    void strXRegisterOffsetStillDecodes() {
        // STR x0, [x0, x0] — idx=REGISTER_OFFSET(10), bit21=1 (caso legítimo pré-existente)
        Ir64Op.Store64 op = (Ir64Op.Store64) decodeAt(0, 0xF8206800);
        assertEquals(Ir64AddressingMode.REGISTER_OFFSET, op.addressingMode());
    }
}
