package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.7 — `FEAT_BF16` (`BFCVT`/`BFCVTN`/`BFDOT`/`BFMLALB`/`BFMLALT`/`BFMMLA`, 8 linhas). Vetores
/// golden conferidos com `aarch64-none-elf-as`/`objdump` (devkitA64, `-march=armv8.6-a+bf16`, via
/// WSL).
class Aarch64AdvSimdBFloat16DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder BF16_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_6_A);

    // -- golden: aarch64-none-elf-as/objdump (devkitA64, WSL, -march=armv8.6-a+bf16) --
    private static final int BFCVT_H0_S1 = 0x1e634020;
    private static final int BFCVTN_V0_4H_V1_4S = 0x0ea16820;
    private static final int BFCVTN2_V0_8H_V1_4S = 0x4ea16820;
    private static final int BFMLALB_V0_4S_V1_8H_V2_8H = 0x2ec2fc20;
    private static final int BFMLALT_V0_4S_V1_8H_V2_8H = 0x6ec2fc20;
    private static final int BFMLALB_V0_4S_V1_8H_V2_H3 = 0x0ff2f020;
    private static final int BFMLALT_V0_4S_V1_8H_V2_H3 = 0x4ff2f020;
    private static final int BFDOT_V0_2S_V1_4H_V2_4H = 0x2e42fc20;
    private static final int BFDOT_V0_4S_V1_8H_V2_8H = 0x6e42fc20;
    private static final int BFDOT_V0_2S_V1_4H_V2_2H1 = 0x0f62f020;
    private static final int BFDOT_V0_4S_V1_8H_V2_2H1 = 0x4f62f020;
    private static final int BFMMLA_V0_4S_V1_8H_V2_8H = 0x6e42ec20;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Regressão negativa: sem a feature, TODAS as 8 continuam `unsupported` ─────────────────────

    @Test
    void allEightRejectedWithoutBFloat16Feature() {
        int[] words = {
                BFCVT_H0_S1, BFCVTN_V0_4H_V1_4S, BFCVTN2_V0_8H_V1_4S,
                BFMLALB_V0_4S_V1_8H_V2_8H, BFMLALT_V0_4S_V1_8H_V2_8H,
                BFMLALB_V0_4S_V1_8H_V2_H3, BFMLALT_V0_4S_V1_8H_V2_H3,
                BFDOT_V0_2S_V1_4H_V2_4H, BFDOT_V0_4S_V1_8H_V2_8H,
                BFDOT_V0_2S_V1_4H_V2_2H1, BFDOT_V0_4S_V1_8H_V2_2H1,
                BFMMLA_V0_4S_V1_8H_V2_8H,
        };
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria ser unsupported sem FEAT_BF16");
        }
    }

    // ── BFCVT ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfcvtScalar() {
        Ir64Op.Fp64ConvertToBf16 op = (Ir64Op.Fp64ConvertToBf16) decode(BF16_DECODER, BFCVT_H0_S1);
        assertEquals(0, op.vd());
        assertEquals(1, op.vn());
    }

    // ── BFCVTN/BFCVTN2 ──────────────────────────────────────────────────────────────────────────

    @Test
    void bfcvtnLowHalf() {
        Ir64Op.VectorFpConvertPrecision op =
                (Ir64Op.VectorFpConvertPrecision) decode(BF16_DECODER, BFCVTN_V0_4H_V1_4S);
        assertEquals(Ir64VectorFpConvertPrecisionOp.BFCVTN, op.op());
        assertFalse(op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void bfcvtn2HighHalf() {
        Ir64Op.VectorFpConvertPrecision op =
                (Ir64Op.VectorFpConvertPrecision) decode(BF16_DECODER, BFCVTN2_V0_8H_V1_4S);
        assertEquals(Ir64VectorFpConvertPrecisionOp.BFCVTN, op.op());
        assertTrue(op.q());
    }

    @Test
    void fcvtnUnaffectedByBFloat16Feature() {
        // Zero-diff: FCVTN_v (a=0) continua decodificando igual, feature presente ou não.
        int fcvtnV0_2s_v1_2d = 0x0e616820; // fcvtn v0.2s, v1.2d (golden devkitA64)
        Ir64Op.VectorFpConvertPrecision op =
                (Ir64Op.VectorFpConvertPrecision) decode(BF16_DECODER, fcvtnV0_2s_v1_2d);
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTN, op.op());
        assertEquals(2, op.esz());
    }

    // ── BFMLALB/BFMLALT ─────────────────────────────────────────────────────────────────────────

    @Test
    void bfmlalbVector() {
        Ir64Op.VectorFpMultiplyAddLongBFloat16 op =
                (Ir64Op.VectorFpMultiplyAddLongBFloat16) decode(BF16_DECODER, BFMLALB_V0_4S_V1_8H_V2_8H);
        assertFalse(op.top());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void bfmlaltVector() {
        Ir64Op.VectorFpMultiplyAddLongBFloat16 op =
                (Ir64Op.VectorFpMultiplyAddLongBFloat16) decode(BF16_DECODER, BFMLALT_V0_4S_V1_8H_V2_8H);
        assertTrue(op.top());
    }

    @Test
    void bfmlalbIndexed() {
        Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement op =
                (Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement) decode(BF16_DECODER, BFMLALB_V0_4S_V1_8H_V2_H3);
        assertFalse(op.top());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void bfmlaltIndexed() {
        Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement op =
                (Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement) decode(BF16_DECODER, BFMLALT_V0_4S_V1_8H_V2_H3);
        assertTrue(op.top());
        assertEquals(3, op.index());
    }

    // ── BFDOT ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfdotVector2s() {
        Ir64Op.VectorFpDotProductBFloat16 op =
                (Ir64Op.VectorFpDotProductBFloat16) decode(BF16_DECODER, BFDOT_V0_2S_V1_4H_V2_4H);
        assertFalse(op.q());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void bfdotVector4s() {
        Ir64Op.VectorFpDotProductBFloat16 op =
                (Ir64Op.VectorFpDotProductBFloat16) decode(BF16_DECODER, BFDOT_V0_4S_V1_8H_V2_8H);
        assertTrue(op.q());
    }

    @Test
    void bfdotIndexed2s() {
        Ir64Op.VectorFpDotProductBFloat16ByElement op =
                (Ir64Op.VectorFpDotProductBFloat16ByElement) decode(BF16_DECODER, BFDOT_V0_2S_V1_4H_V2_2H1);
        assertFalse(op.q());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(1, op.index());
    }

    @Test
    void bfdotIndexed4s() {
        Ir64Op.VectorFpDotProductBFloat16ByElement op =
                (Ir64Op.VectorFpDotProductBFloat16ByElement) decode(BF16_DECODER, BFDOT_V0_4S_V1_8H_V2_2H1);
        assertTrue(op.q());
        assertEquals(1, op.index());
    }

    // ── BFMMLA ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfmmla() {
        Ir64Op.VectorFpMatrixMultiplyAccumulateBFloat16 op =
                (Ir64Op.VectorFpMatrixMultiplyAccumulateBFloat16) decode(BF16_DECODER, BFMMLA_V0_4S_V1_8H_V2_8H);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }
}
