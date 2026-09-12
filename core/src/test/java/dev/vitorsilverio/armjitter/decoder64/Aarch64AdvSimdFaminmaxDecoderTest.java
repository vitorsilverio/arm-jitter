package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.24 (`FEAT_FAMINMAX`): `FAMAX`/`FAMIN` (`_h`/`_sd`) — decoder. Corpus REAL via
/// `aarch64-linux-gnu-as -march=armv9.4-a+faminmax` (WSL/Ubuntu), conferido bit a bit contra
/// `objdump -d` (ver `## Resultado` da task).
///
/// **Regressão do `⚠️` medido pela task**: antes desta task, `FAMAX_h`/`FAMIN_h` (o teste
/// {@link #famaxHalfwordMisdecodedAsVectorInsertBeforeThisFix()} reproduz o word exato) caíam no
/// fallback {@code decodeAdvancedSimdCopy}, que lia `Rm` (bits[20:16], o registrador `Vm` de
/// VERDADE) como se fosse `imm5` de `INS_element`/`DUP` — o `⚠️` que `docs/COBERTURA-ISA.md`
/// media (`AARCH64_MISDECODED.put("FAMAX#1", "VectorInsertGeneral")`/`"FAMIN#1"`, removidos por
/// esta task). As formas `_sd` já caíam honestamente em `unsupported` antes (não era misdecode,
/// era decode ausente), mesma classe já documentada pela B19.11e para `FSCALE`.
class Aarch64AdvSimdFaminmaxDecoderTest {
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

    // ── FAMAX_h / FAMIN_h ────────────────────────────────────────────────────────────────────────

    @Test
    void famaxHalfword() {
        // 0ec51c83: famax v3.4h, v4.4h, v5.4h
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x0ec51c83);
        assertEquals(true, op.max());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void faminHalfword() {
        // 2ec51c83: famin v3.4h, v4.4h, v5.4h
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x2ec51c83);
        assertEquals(false, op.max());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void famaxHalfwordQ() {
        // 4ec21c20: famax v0.8h, v1.8h, v2.8h
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x4ec21c20);
        assertEquals(true, op.max());
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void faminHalfwordQ() {
        // 6ec21c20: famin v0.8h, v1.8h, v2.8h
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x6ec21c20);
        assertEquals(false, op.max());
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void famaxHalfwordMisdecodedAsVectorInsertBeforeThisFix() {
        // MESMO word de famaxHalfwordQ (0x4ec21c20) — antes desta task, decodificava como
        // `VectorInsertGeneral`/`VectorInsertElement` (leitura de `Rm=2` como `imm5` de
        // `INS`/`DUP`). Prova que a correção resolveu o sintoma medido pela task.
        Ir64Op op = decodeWord(0x4ec21c20);
        assertEquals(Ir64Op.VectorFpAbsoluteMaxMin.class, op.getClass());
    }

    // ── FAMAX_sd / FAMIN_sd ──────────────────────────────────────────────────────────────────────

    @Test
    void famaxSingle() {
        // 0ea8dce6: famax v6.2s, v7.2s, v8.2s
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x0ea8dce6);
        assertEquals(true, op.max());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    @Test
    void faminSingle() {
        // 2ea8dce6: famin v6.2s, v7.2s, v8.2s
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x2ea8dce6);
        assertEquals(false, op.max());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    @Test
    void famaxDouble() {
        // 4ee8dce6: famax v6.2d, v7.2d, v8.2d — `Q` é sempre `1` (não cabe `.1d`, mesma regra de FSCALE).
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x4ee8dce6);
        assertEquals(true, op.max());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    @Test
    void faminDouble() {
        // 6ee8dce6: famin v6.2d, v7.2d, v8.2d
        Ir64Op.VectorFpAbsoluteMaxMin op = (Ir64Op.VectorFpAbsoluteMaxMin) decodeWord(0x6ee8dce6);
        assertEquals(false, op.max());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    // ── Vizinhos que NÃO podem ser afetados (mesmo opcode 0b1_1011, keys diferentes) ────────────────

    @Test
    void mulStillDecodesCorrectly() {
        // 6e25dc83: fmul v3.4s, v4.4s, v5.4s (key u=1,a=0 — vizinho de FAMAX/FAMIN no MESMO opcode).
        Ir64Op.VectorFpArithmeticThreeSame op =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6e25dc83);
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp.MUL, op.op());
    }

    @Test
    void mulxStillDecodesCorrectly() {
        // 4e25dc83: fmulx v3.4s, v4.4s, v5.4s (key u=0,a=0).
        Ir64Op.VectorFpArithmeticThreeSame op =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e25dc83);
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp.MULX, op.op());
    }

    @Test
    void insElementNeighborStillDecodesCorrectly() {
        // 6e0e2420: mov v0.h[3], v1.h[2] (alias de `ins v0.h[3], v1.h[2]`, a vítima original do
        // `⚠️` no espaço `bit21=0` — continua correta nos SEUS encodings reais).
        Ir64Op.VectorInsertElement op = (Ir64Op.VectorInsertElement) decodeWord(0x6e0e2420);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(3, op.destIndex());
        assertEquals(2, op.srcIndex());
    }

    // ── Feature gating ──────────────────────────────────────────────────────────────────────────

    // Nota: sem a feature, `FAMAX_h`/`FAMIN_h` continuam caindo no MESMO fallback pré-existente
    // (`decodeAdvancedSimdCopy`, que produz `VectorInsertGeneral`/`VectorInsertElement`) — esta
    // task corrige o misdecode só QUANDO a feature está presente (byte a byte idêntico ao
    // comportamento de antes desta task quando ausente), mesmo precedente da B19.11e/`FSCALE_h`
    // (que também não testa rejeição sem feature para a forma `_h`, só para `_sd`).

    @Test
    void famaxSingleRejectedWithoutFeature() {
        // Já caía honestamente em `unsupported` mesmo antes desta task (não era misdecode) —
        // continua assim sem a feature.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0ea8dce6));
    }
}
