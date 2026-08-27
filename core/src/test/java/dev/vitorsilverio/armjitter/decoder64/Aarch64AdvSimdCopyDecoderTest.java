package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `DUP`/`INS`/`SMOV`/`UMOV` (B8.12, AdvSIMD copy) — quarta família do prefixo vetorial "01110",
/// `bit21=0`, que B8.10 tinha deixado de fora (devolvia `null`, candidata a task própria). Palavras
/// deste teste vêm de `aarch64-none-elf-as`/`objdump` reais (devkitA64 disponível nesta sessão) —
/// corpus real, não fórmula, para os casos válidos; os casos de rejeição (combinações reservadas,
/// G8) são construídos manualmente a partir da fórmula de encoding já validada contra o corpus.
class Aarch64AdvSimdCopyDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void dupElementByteQForm() {
        // `dup v0.16b, v1.b[0]`
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x4e010420);
        assertEquals(true, dup.q());
        assertEquals(0, dup.esz());
        assertEquals(0, dup.rd());
        assertEquals(1, dup.rn());
        assertEquals(0, dup.index());
    }

    @Test
    void dupElementByteHighestIndex() {
        // `dup v0.16b, v1.b[15]`
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x4e1f0420);
        assertEquals(15, dup.index());
        assertEquals(0, dup.esz());
    }

    @Test
    void dupElementHalfword() {
        // `dup v0.8h, v1.h[7]`
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x4e1e0420);
        assertEquals(1, dup.esz());
        assertEquals(7, dup.index());
    }

    @Test
    void dupElementWord() {
        // `dup v0.4s, v1.s[3]`
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x4e1c0420);
        assertEquals(2, dup.esz());
        assertEquals(3, dup.index());
    }

    @Test
    void dupElementDoubleword() {
        // `dup v0.2d, v1.d[1]`
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x4e180420);
        assertEquals(3, dup.esz());
        assertEquals(1, dup.index());
        assertEquals(true, dup.q());
    }

    @Test
    void dupElementDForm() {
        // `dup v0.8b, v1.b[3]` — arranjo de 64 bits
        Ir64Op.VectorDuplicateElement dup = (Ir64Op.VectorDuplicateElement) decodeWord(0x0e070420);
        assertEquals(false, dup.q());
        assertEquals(3, dup.index());
    }

    @Test
    void dupElementDoublewordRejectsDForm() {
        // esz=3 (doubleword) sem Q é reservado — não existe arranjo "1D".
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0e080420));
    }

    @Test
    void dupElementRejectsZeroImm5() {
        // `imm5==0`: nenhum bit de tamanho marcado, reservado.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e000420));
    }

    @Test
    void dupGeneralByteQForm() {
        // `dup v0.16b, w1`
        Ir64Op.VectorDuplicateGeneral dup = (Ir64Op.VectorDuplicateGeneral) decodeWord(0x4e010c20);
        assertEquals(true, dup.q());
        assertEquals(0, dup.esz());
        assertEquals(0, dup.rd());
        assertEquals(1, dup.rn());
    }

    @Test
    void dupGeneralByteDForm() {
        // `dup v0.8b, w1`
        Ir64Op.VectorDuplicateGeneral dup = (Ir64Op.VectorDuplicateGeneral) decodeWord(0x0e010c20);
        assertEquals(false, dup.q());
        assertEquals(0, dup.esz());
    }

    @Test
    void dupGeneralDoubleword() {
        // `dup v0.2d, x1`
        Ir64Op.VectorDuplicateGeneral dup = (Ir64Op.VectorDuplicateGeneral) decodeWord(0x4e080c20);
        assertEquals(3, dup.esz());
        assertEquals(true, dup.q());
    }

    @Test
    void insGeneralByte() {
        // `ins v0.b[0], w1` (`mov` no disassembler)
        Ir64Op.VectorInsertGeneral ins = (Ir64Op.VectorInsertGeneral) decodeWord(0x4e011c20);
        assertEquals(0, ins.esz());
        assertEquals(0, ins.rd());
        assertEquals(1, ins.rn());
        assertEquals(0, ins.index());
    }

    @Test
    void insGeneralByteHighestIndex() {
        // `ins v0.b[15], w1`
        Ir64Op.VectorInsertGeneral ins = (Ir64Op.VectorInsertGeneral) decodeWord(0x4e1f1c20);
        assertEquals(15, ins.index());
    }

    @Test
    void insGeneralDoubleword() {
        // `ins v0.d[0], x1`
        Ir64Op.VectorInsertGeneral ins = (Ir64Op.VectorInsertGeneral) decodeWord(0x4e081c20);
        assertEquals(3, ins.esz());
        assertEquals(0, ins.index());
    }

    @Test
    void insGeneralRejectsWithoutQ() {
        // `Q` é fixo em `1` no encoding real de `INS_general` — `Q=0` é reservado.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0e011c20));
    }

    @Test
    void insElementByte() {
        // `ins v0.b[3], v1.b[5]`
        Ir64Op.VectorInsertElement ins = (Ir64Op.VectorInsertElement) decodeWord(0x6e072c20);
        assertEquals(0, ins.esz());
        assertEquals(0, ins.rd());
        assertEquals(1, ins.rn());
        assertEquals(3, ins.destIndex());
        assertEquals(5, ins.srcIndex());
    }

    @Test
    void insElementHalfword() {
        // `ins v0.h[2], v1.h[6]`
        Ir64Op.VectorInsertElement ins = (Ir64Op.VectorInsertElement) decodeWord(0x6e0a6420);
        assertEquals(1, ins.esz());
        assertEquals(2, ins.destIndex());
        assertEquals(6, ins.srcIndex());
    }

    @Test
    void insElementWord() {
        // `ins v0.s[1], v1.s[2]`
        Ir64Op.VectorInsertElement ins = (Ir64Op.VectorInsertElement) decodeWord(0x6e0c4420);
        assertEquals(2, ins.esz());
        assertEquals(1, ins.destIndex());
        assertEquals(2, ins.srcIndex());
    }

    @Test
    void insElementDoubleword() {
        // `ins v0.d[0], v1.d[1]`
        Ir64Op.VectorInsertElement ins = (Ir64Op.VectorInsertElement) decodeWord(0x6e084420);
        assertEquals(3, ins.esz());
        assertEquals(0, ins.destIndex());
        assertEquals(1, ins.srcIndex());
    }

    @Test
    void insElementRejectsWithoutQ() {
        // Mesma regra de `INS_general`: `Q` é fixo em `1` no encoding real.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x2e010420));
    }

    @Test
    void smovByteToW() {
        // `smov w0, v1.b[0]`
        Ir64Op.VectorMoveElement smov = (Ir64Op.VectorMoveElement) decodeWord(0x0e012c20);
        assertEquals(true, smov.signed());
        assertEquals(false, smov.wide());
        assertEquals(0, smov.esz());
        assertEquals(0, smov.rd());
        assertEquals(1, smov.rn());
        assertEquals(0, smov.index());
    }

    @Test
    void smovByteToX() {
        // `smov x0, v1.b[0]`
        Ir64Op.VectorMoveElement smov = (Ir64Op.VectorMoveElement) decodeWord(0x4e012c20);
        assertEquals(true, smov.wide());
        assertEquals(0, smov.esz());
    }

    @Test
    void smovHalfwordToW() {
        // `smov w0, v1.h[0]`
        Ir64Op.VectorMoveElement smov = (Ir64Op.VectorMoveElement) decodeWord(0x0e022c20);
        assertEquals(false, smov.wide());
        assertEquals(1, smov.esz());
    }

    @Test
    void smovWordToX() {
        // `smov x0, v1.s[0]`
        Ir64Op.VectorMoveElement smov = (Ir64Op.VectorMoveElement) decodeWord(0x4e042c20);
        assertEquals(true, smov.wide());
        assertEquals(2, smov.esz());
    }

    @Test
    void smovRejectsWordToW() {
        // `SMOV Wd,Vn.S[i]` não existe (extensão de sinal de 32 p/ 32 seria redundante com
        // `UMOV`/`MOV`) — `esz=2` exige `Q=1`.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0e042c20));
    }

    @Test
    void smovRejectsDoubleword() {
        // `SMOV` não existe p/ doubleword (nada maior que `Xd` p/ estender).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e082c20));
    }

    @Test
    void umovByte() {
        // `umov w0, v1.b[0]`
        Ir64Op.VectorMoveElement umov = (Ir64Op.VectorMoveElement) decodeWord(0x0e013c20);
        assertEquals(false, umov.signed());
        assertEquals(false, umov.wide());
        assertEquals(0, umov.esz());
        assertEquals(0, umov.index());
    }

    @Test
    void umovHalfword() {
        // `umov w0, v1.h[0]`
        Ir64Op.VectorMoveElement umov = (Ir64Op.VectorMoveElement) decodeWord(0x0e023c20);
        assertEquals(1, umov.esz());
    }

    @Test
    void umovWord() {
        // `mov w0, v1.s[2]` (alias de `umov`)
        Ir64Op.VectorMoveElement umov = (Ir64Op.VectorMoveElement) decodeWord(0x0e143c20);
        assertEquals(false, umov.wide());
        assertEquals(2, umov.esz());
        assertEquals(2, umov.index());
    }

    @Test
    void umovDoubleword() {
        // `mov x0, v1.d[1]` (alias de `umov`)
        Ir64Op.VectorMoveElement umov = (Ir64Op.VectorMoveElement) decodeWord(0x4e183c20);
        assertEquals(true, umov.wide());
        assertEquals(3, umov.esz());
        assertEquals(1, umov.index());
    }

    @Test
    void umovRejectsQMismatch() {
        // `UMOV` exige `Q == (esz==3)` sempre — `esz=0` com `Q=1` é reservado (sem forma
        // "estendida" redundante com o zero-extend automático de `Wd`→`Xd`).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e013c20));
    }

    @Test
    void reservedImm4IsRejected() {
        // `imm4=0b0010` não corresponde a nenhuma das 5 instruções de "AdvSIMD copy".
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e011420));
    }
}
