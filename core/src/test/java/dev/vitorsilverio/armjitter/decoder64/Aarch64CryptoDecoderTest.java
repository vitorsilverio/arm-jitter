package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `AESE`/`AESD`/`AESMC`/`AESIMC`/`PMULL`/`PMULL2` (B8.11, ARMv8-A Cryptographic Extension).
/// Palavras vêm de `aarch64-none-elf-as`/`objdump` reais (devkitA64 DISPONÍVEL nesta sessão,
/// `.arch armv8-a+crypto`) — corpus real, não fórmula.
class Aarch64CryptoDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void aese() {
        // `aese v0.16b, v1.16b`
        Ir64Op.CryptoAes op = (Ir64Op.CryptoAes) decodeWord(0x4e284820);
        assertEquals(Ir64CryptoAesOp.AESE, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void aesd() {
        // `aesd v2.16b, v3.16b`
        Ir64Op.CryptoAes op = (Ir64Op.CryptoAes) decodeWord(0x4e285862);
        assertEquals(Ir64CryptoAesOp.AESD, op.op());
        assertEquals(2, op.rd());
        assertEquals(3, op.rn());
    }

    @Test
    void aesmc() {
        // `aesmc v4.16b, v5.16b`
        Ir64Op.CryptoAes op = (Ir64Op.CryptoAes) decodeWord(0x4e2868a4);
        assertEquals(Ir64CryptoAesOp.AESMC, op.op());
        assertEquals(4, op.rd());
        assertEquals(5, op.rn());
    }

    @Test
    void aesimc() {
        // `aesimc v6.16b, v7.16b`
        Ir64Op.CryptoAes op = (Ir64Op.CryptoAes) decodeWord(0x4e2878e6);
        assertEquals(Ir64CryptoAesOp.AESIMC, op.op());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
    }

    @Test
    void pmullP8() {
        // `pmull v8.8h, v9.8b, v10.8b`
        Ir64Op.VectorPolynomialMultiplyLong op = (Ir64Op.VectorPolynomialMultiplyLong) decodeWord(0x0e2ae128);
        assertEquals(false, op.p64());
        assertEquals(false, op.q());
        assertEquals(8, op.rd());
        assertEquals(9, op.rn());
        assertEquals(10, op.rm());
    }

    @Test
    void pmull2P8() {
        // `pmull2 v11.8h, v12.16b, v13.16b`
        Ir64Op.VectorPolynomialMultiplyLong op = (Ir64Op.VectorPolynomialMultiplyLong) decodeWord(0x4e2de18b);
        assertEquals(false, op.p64());
        assertEquals(true, op.q());
        assertEquals(11, op.rd());
        assertEquals(12, op.rn());
        assertEquals(13, op.rm());
    }

    @Test
    void pmullP64() {
        // `pmull v14.1q, v5.1d, v6.1d` — registradores < 16, comparado com
        // {@link #pmullP64WithHighRmNowDecodesCorrectly} (registrador `Rm>=16`, mesma instrução).
        Ir64Op.VectorPolynomialMultiplyLong op = (Ir64Op.VectorPolynomialMultiplyLong) decodeWord(0x0ee6e0ae);
        assertEquals(true, op.p64());
        assertEquals(false, op.q());
        assertEquals(14, op.rd());
        assertEquals(5, op.rn());
        assertEquals(6, op.rm());
    }

    @Test
    void pmull2P64() {
        // `pmull2 v11.1q, v2.2d, v3.2d` — registradores < 16, mesmo motivo de `pmullP64`.
        Ir64Op.VectorPolynomialMultiplyLong op = (Ir64Op.VectorPolynomialMultiplyLong) decodeWord(0x4ee3e04b);
        assertEquals(true, op.p64());
        assertEquals(true, op.q());
        assertEquals(11, op.rd());
        assertEquals(2, op.rn());
        assertEquals(3, op.rm());
    }

    @Test
    void pmullP64WithHighRmNowDecodesCorrectly() {
        // E8: `pmull v14.1q, v15.1d, v16.1d` (Rm=v16, `0b10000`) era rejeitado pelo bug pré-
        // existente achado (não corrigido) na B8.11: {@link Aarch64Decoder#decodeAdvancedSimdInteger}
        // usava "bit4 de Rm setado" como heurística para "AdvSIMD across lanes" — válida SÓ para
        // "two-register miscellaneous"/"across lanes" reais (onde esse campo não é um registrador de
        // verdade), mas ERRADA para "three different"/`PMULL` (onde `Rm` É um registrador livre
        // `0`-`31`, herdado de B8.7/B8.8). Corrigido na E8 trocando o discriminador para bit11 (fixo
        // em `0` só em "three different", ver o achado em `decodeAdvancedSimdInteger`).
        Ir64Op.VectorPolynomialMultiplyLong op = (Ir64Op.VectorPolynomialMultiplyLong) decodeWord(0x0ef0e1ee);
        assertEquals(true, op.p64());
        assertEquals(false, op.q());
        assertEquals(14, op.rd());
        assertEquals(15, op.rn());
        assertEquals(16, op.rm());
    }

    @Test
    void pmullRejectsReservedEsz() {
        // `esz=01`/`10` (halfword/word) são reservados para este opcode (só byte/doubleword têm
        // forma real) — mesmo encoding de `pmullP8`, mas com `size` forçado a `01` (bits[23:22]).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0e2ae128 | (1 << 22)));
    }
}
