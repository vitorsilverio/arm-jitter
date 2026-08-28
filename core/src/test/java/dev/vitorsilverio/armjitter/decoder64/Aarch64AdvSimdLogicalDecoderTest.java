package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// AdvSIMD "three same" LÓGICO (`AND`/`BIC`/`ORR`/`ORN`/`EOR`/`BSL`/`BIT`/`BIF`) e o resto de
/// "two-register miscellaneous" inteiro (`SQABS`/`SQNEG`/`CLS`/`CLZ`/`CNT`/`NOT`/`RBIT`) — B8.18,
/// gap real deixado de fora por B8.7/B8.8/B8.10 (títulos "aritmética/comparação"/"deslocamento,
/// saturação e estreitamento"/"permutação, redução, tabela" nunca cobriam bitwise puro nem o resto
/// do slot `Rm=00000`). Corpus REAL via `aarch64-none-elf-as`/`objdump` (devkitA64).
class Aarch64AdvSimdLogicalDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Three same lógico ───────────────────────────────────────────────────────────────────────

    @Test
    void andVector8b() {
        // 0e221c20: and v0.8b, v1.8b, v2.8b
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e221c20);
        assertEquals(Ir64VectorThreeSameOp.AND, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void bicVector16b() {
        // 4e621c20: bic v0.16b, v1.16b, v2.16b
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x4e621c20);
        assertEquals(Ir64VectorThreeSameOp.BIC, op.op());
        assertEquals(true, op.q());
    }

    @Test
    void orrVector8b() {
        // 0ea21c20: orr v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorThreeSameOp.ORR,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0ea21c20)).op());
    }

    @Test
    void ornVector16b() {
        // 4ee21c20: orn v0.16b, v1.16b, v2.16b
        assertEquals(Ir64VectorThreeSameOp.ORN,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x4ee21c20)).op());
    }

    @Test
    void eorVector8b() {
        // 2e221c20: eor v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorThreeSameOp.EOR,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e221c20)).op());
    }

    @Test
    void bslVector16b() {
        // 6e621c20: bsl v0.16b, v1.16b, v2.16b
        assertEquals(Ir64VectorThreeSameOp.BSL,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x6e621c20)).op());
    }

    @Test
    void bitVector8b() {
        // 2ea21c20: bit v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorThreeSameOp.BIT,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2ea21c20)).op());
    }

    @Test
    void bifVector16b() {
        // 6ee21c20: bif v0.16b, v1.16b, v2.16b
        assertEquals(Ir64VectorThreeSameOp.BIF,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x6ee21c20)).op());
    }

    @Test
    void reservedLogicalEncodingNeverSurfacesAsScalar() {
        // MESMO opcode/esz de `and v0.8b,...` (0x0e221c20), mas com o prefixo escalar D-only
        // (bit28 setado): não existe forma escalar de lógico real — tem que recusar (G8), nunca
        // cair silenciosamente num `AND` escalar inventado.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e221c20));
    }

    // ── Resto de "two-register miscellaneous" inteiro ──────────────────────────────────────────

    @Test
    void sqabsVector8b() {
        // 0e207820: sqabs v0.8b, v1.8b
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e207820);
        assertEquals(Ir64VectorUnaryOp.SQABS, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
    }

    @Test
    void sqabsVector4s() {
        // 4ea07820: sqabs v0.4s, v1.4s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4ea07820);
        assertEquals(Ir64VectorUnaryOp.SQABS, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
    }

    @Test
    void sqnegVector16b() {
        // 6e207820: sqneg v0.16b, v1.16b
        assertEquals(Ir64VectorUnaryOp.SQNEG,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x6e207820)).op());
    }

    @Test
    void sqabsScalarByte() {
        // 5e207820: sqabs b0, b1 — forma escalar aceita esz livre (mesma regra de SUQADD_s).
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x5e207820);
        assertEquals(Ir64VectorUnaryOp.SQABS, op.op());
        assertEquals(true, op.scalar());
        assertEquals(0, op.esz());
    }

    @Test
    void sqnegScalarHalfword() {
        // 7e607820: sqneg h0, h1
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x7e607820);
        assertEquals(Ir64VectorUnaryOp.SQNEG, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
    }

    @Test
    void clsVector4h() {
        // 0e604820: cls v0.4h, v1.4h
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e604820);
        assertEquals(Ir64VectorUnaryOp.CLS, op.op());
        assertEquals(1, op.esz());
    }

    @Test
    void clzVector2s() {
        // 2ea04820: clz v0.2s, v1.2s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x2ea04820);
        assertEquals(Ir64VectorUnaryOp.CLZ, op.op());
        assertEquals(2, op.esz());
        assertEquals(false, op.q());
    }

    @Test
    void clsHasNoScalarForm() {
        // MESMO opcode/esz de `cls v0.4h,...`, prefixo escalar: CLS_s não existe (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e604820));
    }

    @Test
    void cntVector8bAnd16b() {
        // 0e205820: cnt v0.8b, v1.8b / 4e205820: cnt v0.16b, v1.16b
        Ir64Op.VectorArithmeticUnary op8 = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e205820);
        assertEquals(Ir64VectorUnaryOp.CNT, op8.op());
        assertEquals(0, op8.esz());
        assertEquals(false, op8.q());

        Ir64Op.VectorArithmeticUnary op16 = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4e205820);
        assertEquals(Ir64VectorUnaryOp.CNT, op16.op());
        assertEquals(true, op16.q());
    }

    @Test
    void notVector8bAnd16b() {
        // 2e205820: not v0.8b, v1.8b (alias "mvn") / 6e205820: not v0.16b, v1.16b
        assertEquals(Ir64VectorUnaryOp.NOT,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e205820)).op());
        assertEquals(Ir64VectorUnaryOp.NOT,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x6e205820)).op());
    }

    @Test
    void rbitVector8bAnd16b() {
        // 2e605820: rbit v0.8b, v1.8b / 6e605820: rbit v0.16b, v1.16b
        assertEquals(Ir64VectorUnaryOp.RBIT,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e605820)).op());
        assertEquals(Ir64VectorUnaryOp.RBIT,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x6e605820)).op());
    }

    @Test
    void reservedSizeInByteOnlySlotIsUnsupported() {
        // MESMO opcode (0b01011) de CNT/NOT/RBIT, mas com `size=1x` (bits[23:22]) — combinação
        // reservada (nenhuma das 3 mnemônicas usa `size` acima de `01`) — tem que recusar (G8).
        // 0x0e205820 (cnt v0.8b,...) com size forçado para "10": bits[23:22] ficam em 20:16=Rm,
        // então alteramos os bits 23:22 diretamente: 0x0e205820 -> 0x0e a05820? construir a partir
        // do campo size (bits 23:22) setado para 0b10 mantendo o resto de cnt (u=0,opcode=01011).
        int reserved = 0x0e205820 | (0b10 << 22);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(reserved));
    }
}
