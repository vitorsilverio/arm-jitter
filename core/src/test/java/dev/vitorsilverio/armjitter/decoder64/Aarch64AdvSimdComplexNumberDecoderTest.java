package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.20 (`FEAT_FCMA`): `FCADD_90`/`FCADD_270`/`FCMLA_v`/`FCMLA_vi` — decoder. Corpus REAL via
/// `aarch64-linux-gnu-as -march=armv8.3-a` (WSL/Ubuntu, `arm-jitter/.tmp-fcma/t.s`), conferido bit
/// a bit contra `objdump -d` (ver `## Resultado` da task).
class Aarch64AdvSimdComplexNumberDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);
    private static final Aarch64Decoder NO_FEATURE_DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    // ── FCADD (three-same) ──────────────────────────────────────────────────────────────────────

    @Test
    void fcadd90Halfword() {
        // 2e42e420: fcadd v0.4h, v1.4h, v2.4h, #90
        Ir64Op.VectorFpComplexAdd op = (Ir64Op.VectorFpComplexAdd) decodeWord(0x2e42e420);
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(90, op.rotation());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fcadd270HalfwordQ() {
        // 6e45f483: fcadd v3.8h, v4.8h, v5.8h, #270
        Ir64Op.VectorFpComplexAdd op = (Ir64Op.VectorFpComplexAdd) decodeWord(0x6e45f483);
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(270, op.rotation());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void fcadd90Word() {
        // 2e88e4e6: fcadd v6.2s, v7.2s, v8.2s, #90
        Ir64Op.VectorFpComplexAdd op = (Ir64Op.VectorFpComplexAdd) decodeWord(0x2e88e4e6);
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(90, op.rotation());
    }

    @Test
    void fcadd270WordQ() {
        // 6e8bf549: fcadd v9.4s, v10.4s, v11.4s, #270
        Ir64Op.VectorFpComplexAdd op = (Ir64Op.VectorFpComplexAdd) decodeWord(0x6e8bf549);
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(270, op.rotation());
    }

    @Test
    void fcadd90DoublewordRequiresQ() {
        // 6ecee5ac: fcadd v12.2d, v13.2d, v14.2d, #90 — Q=1 SEMPRE (par complexo de dupla precisão
        // não cabe em 64 bits).
        Ir64Op.VectorFpComplexAdd op = (Ir64Op.VectorFpComplexAdd) decodeWord(0x6ecee5ac);
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(90, op.rotation());
        assertEquals(12, op.rd());
        assertEquals(13, op.rn());
        assertEquals(14, op.rm());
    }

    @Test
    void fcaddDoublewordWithoutQIsUndefined() {
        // MESMA palavra de fcadd90DoublewordRequiresQ, com bit30 (Q) forçado a 0 — combinação
        // reservada (G8): não existe forma `.1d`.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(0x6ecee5ac & ~(1 << 30)));
    }

    @Test
    void fcaddReservedEszZeroIsUndefined() {
        // MESMA palavra de fcadd90Halfword com `esz` (bits[23:22]) forçado a `00` — reservado.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(0x2e42e420 & ~(1 << 22)));
    }

    // ── FCMLA (three-same, vetorial) ────────────────────────────────────────────────────────────

    @Test
    void fcmlaRotation0Halfword() {
        // 2e42c420: fcmla v0.4h, v1.4h, v2.4h, #0
        Ir64Op.VectorFpComplexMultiplyAccumulate op =
                (Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x2e42c420);
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rotation());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fcmlaRotation90Halfword() {
        // 2e42cc20: fcmla v0.4h, v1.4h, v2.4h, #90
        assertEquals(90, ((Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x2e42cc20)).rotation());
    }

    @Test
    void fcmlaRotation180Halfword() {
        // 2e42d420: fcmla v0.4h, v1.4h, v2.4h, #180
        assertEquals(180, ((Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x2e42d420)).rotation());
    }

    @Test
    void fcmlaRotation270Halfword() {
        // 2e42dc20: fcmla v0.4h, v1.4h, v2.4h, #270
        assertEquals(270, ((Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x2e42dc20)).rotation());
    }

    @Test
    void fcmlaHalfwordQ() {
        // 6e45c483: fcmla v3.8h, v4.8h, v5.8h, #0
        Ir64Op.VectorFpComplexMultiplyAccumulate op =
                (Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x6e45c483);
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
    }

    @Test
    void fcmlaWord() {
        // 2e88cce6: fcmla v6.2s, v7.2s, v8.2s, #90
        Ir64Op.VectorFpComplexMultiplyAccumulate op =
                (Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x2e88cce6);
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(90, op.rotation());
    }

    @Test
    void fcmlaWordQ() {
        // 6e8bd549: fcmla v9.4s, v10.4s, v11.4s, #180
        Ir64Op.VectorFpComplexMultiplyAccumulate op =
                (Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x6e8bd549);
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(180, op.rotation());
    }

    @Test
    void fcmlaDoublewordRequiresQ() {
        // 6eceddac: fcmla v12.2d, v13.2d, v14.2d, #270
        Ir64Op.VectorFpComplexMultiplyAccumulate op =
                (Ir64Op.VectorFpComplexMultiplyAccumulate) decodeWord(0x6eceddac);
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(270, op.rotation());
    }

    // ── FCMLA (indexed) ─────────────────────────────────────────────────────────────────────────

    @Test
    void fcmlaIndexedHalfwordIndex0Rotation0() {
        // 2f421020: fcmla v0.4h, v1.4h, v2.h[0], #0
        Ir64Op.VectorFpComplexMultiplyAccumulateByElement op =
                (Ir64Op.VectorFpComplexMultiplyAccumulateByElement) decodeWord(0x2f421020);
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rotation());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void fcmlaIndexedHalfwordIndex1Rotation90() {
        // 2f623020: fcmla v0.4h, v1.4h, v2.h[1], #90 (!q usa só `L` como índice)
        Ir64Op.VectorFpComplexMultiplyAccumulateByElement op =
                (Ir64Op.VectorFpComplexMultiplyAccumulateByElement) decodeWord(0x2f623020);
        assertEquals(false, op.q());
        assertEquals(90, op.rotation());
        assertEquals(1, op.index());
    }

    @Test
    void fcmlaIndexedHalfwordQIndex3Rotation180() {
        // 6f655883: fcmla v3.8h, v4.8h, v5.h[3], #180 (q usa `H:L`)
        Ir64Op.VectorFpComplexMultiplyAccumulateByElement op =
                (Ir64Op.VectorFpComplexMultiplyAccumulateByElement) decodeWord(0x6f655883);
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(180, op.rotation());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void fcmlaIndexedWordIndex0Rotation270() {
        // 6f8870e6: fcmla v6.4s, v7.4s, v8.s[0], #270 — `S` indexado é SEMPRE Q=1.
        Ir64Op.VectorFpComplexMultiplyAccumulateByElement op =
                (Ir64Op.VectorFpComplexMultiplyAccumulateByElement) decodeWord(0x6f8870e6);
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(270, op.rotation());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void fcmlaIndexedWordIndex1Rotation0() {
        // 6f8b1949: fcmla v9.4s, v10.4s, v11.s[1], #0
        Ir64Op.VectorFpComplexMultiplyAccumulateByElement op =
                (Ir64Op.VectorFpComplexMultiplyAccumulateByElement) decodeWord(0x6f8b1949);
        assertEquals(0, op.rotation());
        assertEquals(1, op.index());
    }

    @Test
    void fcmlaIndexedWordWithoutQIsUndefined() {
        // MESMA palavra de fcmlaIndexedWordIndex0Rotation270, com Q forçado a 0 — não existe forma
        // `.2s` indexada real (G8).
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(0x6f8870e6 & ~(1 << 30)));
    }

    // ── Feature gating ──────────────────────────────────────────────────────────────────────────

    @Test
    void fcaddRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x2e42e420));
    }

    @Test
    void fcmlaRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x2e42c420));
    }

    @Test
    void fcmlaIndexedRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x2f421020));
    }
}
