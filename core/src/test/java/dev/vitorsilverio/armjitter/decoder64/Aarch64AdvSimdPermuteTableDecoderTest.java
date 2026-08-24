package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `EXT`/`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`/`TBL`/`TBX`/`FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV`
/// (B8.10, AdvSIMD permutação/redução/tabela). Palavras deste teste vêm de `aarch64-none-elf-as`/
/// `objdump` reais (devkitA64 DISPONÍVEL nesta sessão, ao contrário de B8.8/B8.9) — corpus real, não
/// fórmula.
class Aarch64AdvSimdPermuteTableDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void extDForm() {
        // `ext v0.8b, v1.8b, v2.8b, #3`
        Ir64Op.VectorExtract ext = (Ir64Op.VectorExtract) decodeWord(0x2e021820);
        assertEquals(false, ext.q());
        assertEquals(3, ext.imm());
        assertEquals(0, ext.rd());
        assertEquals(1, ext.rn());
        assertEquals(2, ext.rm());
    }

    @Test
    void extQForm() {
        // `ext v0.16b, v1.16b, v2.16b, #11`
        Ir64Op.VectorExtract ext = (Ir64Op.VectorExtract) decodeWord(0x6e025820);
        assertEquals(true, ext.q());
        assertEquals(11, ext.imm());
        assertEquals(0, ext.rd());
        assertEquals(1, ext.rn());
        assertEquals(2, ext.rm());
    }

    @Test
    void extDFormRejectsImmAboveSeven() {
        // `imm4` >= 8 sem `Q` é reservado (não existe forma D real com imm3 de 4 bits) — mesmo
        // encoding de `extDForm`, mas com o bit `imm[3]` (bit14) forçado a `1`.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x2e021820 | (1 << 14)));
    }

    @Test
    void uzp1() {
        // `uzp1 v0.8b, v1.8b, v2.8b`
        Ir64Op.VectorPermute op = (Ir64Op.VectorPermute) decodeWord(0x0e021820);
        assertEquals(Ir64VectorPermuteOp.UZP1, op.op());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());

        // `uzp1 v0.16b, v1.16b, v2.16b`
        Ir64Op.VectorPermute opQ = (Ir64Op.VectorPermute) decodeWord(0x4e021820);
        assertEquals(Ir64VectorPermuteOp.UZP1, opQ.op());
        assertEquals(true, opQ.q());
    }

    @Test
    void uzp2() {
        // `uzp2 v3.4h, v4.4h, v5.4h`
        Ir64Op.VectorPermute op = (Ir64Op.VectorPermute) decodeWord(0x0e455883);
        assertEquals(Ir64VectorPermuteOp.UZP2, op.op());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void trn1AndTrn2() {
        // `trn1 v0.4s, v1.4s, v2.4s`
        Ir64Op.VectorPermute trn1 = (Ir64Op.VectorPermute) decodeWord(0x4e822820);
        assertEquals(Ir64VectorPermuteOp.TRN1, trn1.op());
        assertEquals(2, trn1.esz());

        // `trn2 v0.2d, v1.2d, v2.2d`
        Ir64Op.VectorPermute trn2 = (Ir64Op.VectorPermute) decodeWord(0x4ec26820);
        assertEquals(Ir64VectorPermuteOp.TRN2, trn2.op());
        assertEquals(3, trn2.esz());
    }

    @Test
    void zip1AndZip2() {
        // `zip1 v0.16b, v1.16b, v2.16b`
        Ir64Op.VectorPermute zip1 = (Ir64Op.VectorPermute) decodeWord(0x4e023820);
        assertEquals(Ir64VectorPermuteOp.ZIP1, zip1.op());
        assertEquals(0, zip1.esz());

        // `zip2 v6.4s, v7.4s, v8.4s`
        Ir64Op.VectorPermute zip2 = (Ir64Op.VectorPermute) decodeWord(0x4e8878e6);
        assertEquals(Ir64VectorPermuteOp.ZIP2, zip2.op());
        assertEquals(2, zip2.esz());
        assertEquals(6, zip2.rd());
        assertEquals(7, zip2.rn());
        assertEquals(8, zip2.rm());
    }

    @Test
    void tblOneRegister() {
        // `tbl v0.16b, {v1.16b}, v2.16b`
        Ir64Op.VectorTableLookup tbl = (Ir64Op.VectorTableLookup) decodeWord(0x4e020020);
        assertEquals(false, tbl.tbx());
        assertEquals(0, tbl.len());
        assertEquals(true, tbl.q());
        assertEquals(0, tbl.rd());
        assertEquals(1, tbl.rn());
        assertEquals(2, tbl.rm());
    }

    @Test
    void tblTwoRegisters8bArrangement() {
        // `tbl v0.8b, {v1.16b, v2.16b}, v3.8b`
        Ir64Op.VectorTableLookup tbl = (Ir64Op.VectorTableLookup) decodeWord(0x0e032020);
        assertEquals(false, tbl.tbx());
        assertEquals(1, tbl.len());
        assertEquals(false, tbl.q());
        assertEquals(3, tbl.rm());
    }

    @Test
    void tbxThreeRegisters() {
        // `tbx v0.16b, {v1.16b, v2.16b, v3.16b}, v4.16b`
        Ir64Op.VectorTableLookup tbx = (Ir64Op.VectorTableLookup) decodeWord(0x4e045020);
        assertEquals(true, tbx.tbx());
        assertEquals(2, tbx.len());
        assertEquals(4, tbx.rm());
    }

    @Test
    void tblFourRegisters() {
        // `tbl v0.16b, {v1.16b, v2.16b, v3.16b, v4.16b}, v5.16b`
        Ir64Op.VectorTableLookup tbl = (Ir64Op.VectorTableLookup) decodeWord(0x4e056020);
        assertEquals(false, tbl.tbx());
        assertEquals(3, tbl.len());
        assertEquals(5, tbl.rm());
    }

    @Test
    void fpAcrossLanes() {
        // `fmaxnmv s0, v1.4s`
        Ir64Op.VectorFpAcrossLanes maxnmv = (Ir64Op.VectorFpAcrossLanes) decodeWord(0x6e30c820);
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXNMV, maxnmv.op());
        assertEquals(0, maxnmv.rd());
        assertEquals(1, maxnmv.rn());

        // `fminnmv s0, v1.4s`
        assertEquals(Ir64VectorFpAcrossLanesOp.FMINNMV,
                ((Ir64Op.VectorFpAcrossLanes) decodeWord(0x6eb0c820)).op());
        // `fmaxv s0, v1.4s`
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXV,
                ((Ir64Op.VectorFpAcrossLanes) decodeWord(0x6e30f820)).op());
        // `fminv s0, v1.4s`
        assertEquals(Ir64VectorFpAcrossLanesOp.FMINV,
                ((Ir64Op.VectorFpAcrossLanes) decodeWord(0x6eb0f820)).op());
    }

    @Test
    void advancedSimdCopyStaysUnsupported() {
        // `dup v0.4s, w1` / `dup v0.4s, v1.s[2]` — AdvSIMD copy (`bit15=1`), fora de escopo desta
        // task (candidata a task própria); confirma que o sub-dispatch novo devolve `null` para
        // elas em vez de decodificar errado (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e040c20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e140420));
    }
}
