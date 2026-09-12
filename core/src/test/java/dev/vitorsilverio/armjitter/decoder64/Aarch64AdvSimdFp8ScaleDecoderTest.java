package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.11e (`FEAT_FP8`): `FSCALE` (`_h`/`_sd`) — decoder. Corpus REAL via
/// `aarch64-linux-gnu-as -march=armv9.5-a+fp8` (WSL/Ubuntu), conferido bit a bit contra
/// `objdump -d` (ver `## Resultado` da task).
///
/// **Regressão do `⚠️` medido pela task**: antes desta task, `FSCALE_h` (o teste
/// {@link #fscaleHalfwordMisdecodedAsInsElementBeforeThisFix()} reproduz o word exato) caía no
/// fallback `decodeAdvancedSimdExtractPermuteTable`/`decodeAdvancedSimdCopy`, que lia `Rm`
/// (bits[20:16], o registrador `Vm` de VERDADE) como se fosse `imm5` de `INS_element` — o
/// `⚠️` que `docs/COBERTURA-ISA.md` media (`AARCH64_MISDECODED.put("FSCALE#1", "VectorInsertElement")`,
/// removido pela B19.11e). `FSCALE_sd` já caía honestamente em `unsupported` antes (não era
/// misdecode, era decode ausente).
class Aarch64AdvSimdFp8ScaleDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);
    private static final Aarch64Decoder NO_FEATURE_DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    // ── FSCALE_h ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void fscaleHalfword() {
        // 2ec23c20: fscale v0.4h, v1.4h, v2.4h
        Ir64Op.VectorFpScaleByInt op = (Ir64Op.VectorFpScaleByInt) decodeWord(0x2ec23c20);
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fscaleHalfwordQ() {
        // 6ec23c20: fscale v0.8h, v1.8h, v2.8h
        Ir64Op.VectorFpScaleByInt op = (Ir64Op.VectorFpScaleByInt) decodeWord(0x6ec23c20);
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fscaleHalfwordMisdecodedAsInsElementBeforeThisFix() {
        // MESMO word de fscaleHalfwordQ (0x6ec23c20) — antes desta task, decodificava como
        // `VectorInsertElement[esz=1, rd=0, rn=1, destIndex=0, srcIndex=3]` (leitura de `Rm=2`
        // como `imm5` de `INS_element`). Prova que a correção resolveu o sintoma medido.
        Ir64Op op = decodeWord(0x6ec23c20);
        assertEquals(Ir64Op.VectorFpScaleByInt.class, op.getClass());
    }

    // ── FSCALE_sd ────────────────────────────────────────────────────────────────────────────────

    @Test
    void fscaleWord() {
        // 2ea5fc83: fscale v3.2s, v4.2s, v5.2s
        Ir64Op.VectorFpScaleByInt op = (Ir64Op.VectorFpScaleByInt) decodeWord(0x2ea5fc83);
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void fscaleWordQ() {
        // 6ea5fc83: fscale v3.4s, v4.4s, v5.4s
        Ir64Op.VectorFpScaleByInt op = (Ir64Op.VectorFpScaleByInt) decodeWord(0x6ea5fc83);
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void fscaleDoubleword() {
        // 6ee8fce6: fscale v6.2d, v7.2d, v8.2d — `Q` é sempre `1` no encoding real (não cabe uma
        // forma `.1d` de doubleword; o assembler nunca produz `!q`).
        Ir64Op.VectorFpScaleByInt op = (Ir64Op.VectorFpScaleByInt) decodeWord(0x6ee8fce6);
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    // ── Vizinhos que NÃO podem ser afetados (mesmo opcode 0b1_1111, keys diferentes) ────────────────

    @Test
    void divStillDecodesCorrectly() {
        // fdiv v3.2s, v4.2s, v5.2s (key u=1,a=0 — vizinho de FSCALE no MESMO opcode).
        Ir64Op.VectorFpArithmeticThreeSame op =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x2ea5fc83 & ~(1 << 23));
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp.DIV, op.op());
    }

    @Test
    void recpsStillDecodesCorrectly() {
        // frecps v3.2s, v4.2s, v5.2s (key u=0,a=0).
        Ir64Op.VectorFpArithmeticThreeSame op =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x2ea5fc83 & ~(1 << 23) & ~(1 << 29));
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp.RECPS, op.op());
    }

    @Test
    void rsqrtsStillDecodesCorrectly() {
        // frsqrts v3.2s, v4.2s, v5.2s (key u=0,a=1).
        Ir64Op.VectorFpArithmeticThreeSame op =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x2ea5fc83 & ~(1 << 29));
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp.RSQRTS, op.op());
    }

    // ── Feature gating ──────────────────────────────────────────────────────────────────────────

    @Test
    void fscaleWordRejectedWithoutFeature() {
        // A forma `_sd` já caía honestamente em `unsupported` mesmo antes desta task (não era
        // misdecode) — continua assim sem a feature.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x6ea5fc83));
    }
}
