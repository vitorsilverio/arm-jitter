package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.12 — `FEAT_I8MM` (`USDOT_v`/`USDOT_vi`/`SUDOT_vi`/`SMMLA`/`UMMLA`/`USMMLA`, 6 linhas).
/// Vetores golden conferidos com `aarch64-linux-gnu-as`/`objdump` (Ubuntu/WSL,
/// `-march=armv8.6-a+i8mm`) — sem `aarch64-none-elf-as` (devkitA64) disponível neste ambiente,
/// `binutils-aarch64-linux-gnu` produz o MESMO encoding real (mesma ISA, mesmo assembler GNU).
class Aarch64AdvSimdI8mmDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder I8MM_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_6_A);

    // -- golden: aarch64-linux-gnu-as/objdump (-march=armv8.6-a+i8mm) --
    private static final int USDOT_V0_4S_V1_16B_V2_16B = 0x4e829c20;
    private static final int USDOT_V3_2S_V4_8B_V5_8B = 0x0e859c83;
    private static final int SMMLA_V0_4S_V1_16B_V2_16B = 0x4e82a420;
    private static final int UMMLA_V0_4S_V1_16B_V2_16B = 0x6e82a420;
    private static final int USMMLA_V0_4S_V1_16B_V2_16B = 0x4e82ac20;
    private static final int USDOT_VI_V0_4S_V1_16B_V2_4B0 = 0x4f82f020;
    private static final int USDOT_VI_V0_4S_V1_16B_V2_4B1 = 0x4fa2f020;
    private static final int USDOT_VI_V0_4S_V1_16B_V2_4B2 = 0x4f82f820;
    private static final int USDOT_VI_V0_4S_V1_16B_V2_4B3 = 0x4fa2f820;
    private static final int USDOT_VI_V0_2S_V1_8B_V2_4B3 = 0x0fa2f820;
    private static final int SUDOT_VI_V0_4S_V1_16B_V2_4B0 = 0x4f02f020;
    private static final int SUDOT_VI_V0_4S_V1_16B_V2_4B1 = 0x4f22f020;
    private static final int SUDOT_VI_V0_4S_V1_16B_V2_4B2 = 0x4f02f820;
    private static final int SUDOT_VI_V0_4S_V1_16B_V2_4B3 = 0x4f22f820;
    private static final int SUDOT_VI_V0_2S_V1_8B_V2_4B3 = 0x0f22f820;
    private static final int USDOT_VI_V0_4S_V15_16B_V15_4B3 = 0x4faff9e0;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Regressão negativa: sem a feature, TODAS as 6 formas continuam `unsupported` ───────────────

    @Test
    void allRejectedWithoutInt8MatrixMultiplyFeature() {
        int[] words = {
                USDOT_V0_4S_V1_16B_V2_16B, USDOT_V3_2S_V4_8B_V5_8B,
                SMMLA_V0_4S_V1_16B_V2_16B, UMMLA_V0_4S_V1_16B_V2_16B, USMMLA_V0_4S_V1_16B_V2_16B,
                USDOT_VI_V0_4S_V1_16B_V2_4B0, USDOT_VI_V0_4S_V1_16B_V2_4B1,
                USDOT_VI_V0_4S_V1_16B_V2_4B2, USDOT_VI_V0_4S_V1_16B_V2_4B3,
                SUDOT_VI_V0_4S_V1_16B_V2_4B0, SUDOT_VI_V0_4S_V1_16B_V2_4B1,
                SUDOT_VI_V0_4S_V1_16B_V2_4B2, SUDOT_VI_V0_4S_V1_16B_V2_4B3,
        };
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria ser unsupported sem FEAT_I8MM");
        }
    }

    // ── SDOT_v/UDOT_v não têm decoder neste projeto (B13.18) — sem regressão a testar aqui: o
    // ── opcode U=1 do MESMO slot de `USDOT_v` cai no fallback de sempre (`decodeAdvancedSimdCopy`),
    // ── comportamento IDÊNTICO a antes desta task.

    // ── USDOT_v (vetorial) ──────────────────────────────────────────────────────────────────────

    @Test
    void usdotVector4s() {
        AdvSimdIntegerOp64.IntegerDotProduct op =
                (AdvSimdIntegerOp64.IntegerDotProduct) decode(I8MM_DECODER, USDOT_V0_4S_V1_16B_V2_16B);
        assertTrue(op.q());
        assertFalse(op.signedN());
        assertTrue(op.signedM());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void usdotVector2s() {
        AdvSimdIntegerOp64.IntegerDotProduct op =
                (AdvSimdIntegerOp64.IntegerDotProduct) decode(I8MM_DECODER, USDOT_V3_2S_V4_8B_V5_8B);
        assertFalse(op.q());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    // ── SMMLA/UMMLA/USMMLA (matricial) ─────────────────────────────────────────────────────────

    @Test
    void smmla() {
        AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate op =
                (AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate) decode(I8MM_DECODER, SMMLA_V0_4S_V1_16B_V2_16B);
        assertTrue(op.signedN());
        assertTrue(op.signedM());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void ummla() {
        AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate op =
                (AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate) decode(I8MM_DECODER, UMMLA_V0_4S_V1_16B_V2_16B);
        assertFalse(op.signedN());
        assertFalse(op.signedM());
    }

    @Test
    void usmmla() {
        AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate op =
                (AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate) decode(I8MM_DECODER, USMMLA_V0_4S_V1_16B_V2_16B);
        assertFalse(op.signedN());
        assertTrue(op.signedM());
    }

    // ── USDOT_vi (indexado) ─────────────────────────────────────────────────────────────────────

    @Test
    void usdotIndexedIndex0() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, USDOT_VI_V0_4S_V1_16B_V2_4B0);
        assertTrue(op.q());
        assertFalse(op.signedN());
        assertTrue(op.signedM());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void usdotIndexedIndex1() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, USDOT_VI_V0_4S_V1_16B_V2_4B1);
        assertEquals(1, op.index());
    }

    @Test
    void usdotIndexedIndex2() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, USDOT_VI_V0_4S_V1_16B_V2_4B2);
        assertEquals(2, op.index());
    }

    @Test
    void usdotIndexedIndex3() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, USDOT_VI_V0_4S_V1_16B_V2_4B3);
        assertEquals(3, op.index());
    }

    @Test
    void usdotIndexed2s() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, USDOT_VI_V0_2S_V1_8B_V2_4B3);
        assertFalse(op.q());
        assertEquals(3, op.index());
    }

    @Test
    void usdotIndexedRestrictedRmUpToV15() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op = (AdvSimdIntegerOp64.IntegerDotProductByElement)
                decode(I8MM_DECODER, USDOT_VI_V0_4S_V15_16B_V15_4B3);
        assertEquals(15, op.rn());
        assertEquals(15, op.rm());
        assertEquals(3, op.index());
    }

    // ── SUDOT_vi (indexado) — distinguido de USDOT_vi por size(bits[23:22]), NÃO por `U` ──────────

    @Test
    void sudotIndexedIndex0() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, SUDOT_VI_V0_4S_V1_16B_V2_4B0);
        assertTrue(op.signedN());
        assertFalse(op.signedM());
        assertEquals(0, op.index());
    }

    @Test
    void sudotIndexedIndex1() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, SUDOT_VI_V0_4S_V1_16B_V2_4B1);
        assertEquals(1, op.index());
    }

    @Test
    void sudotIndexedIndex2() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, SUDOT_VI_V0_4S_V1_16B_V2_4B2);
        assertEquals(2, op.index());
    }

    @Test
    void sudotIndexedIndex3() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, SUDOT_VI_V0_4S_V1_16B_V2_4B3);
        assertEquals(3, op.index());
    }

    @Test
    void sudotIndexed2s() {
        AdvSimdIntegerOp64.IntegerDotProductByElement op =
                (AdvSimdIntegerOp64.IntegerDotProductByElement) decode(I8MM_DECODER, SUDOT_VI_V0_2S_V1_8B_V2_4B3);
        assertFalse(op.q());
        assertEquals(3, op.index());
    }

    // ── USDOT_vi × SUDOT_vi: mesmo `opcode`/`U`, encodings DIFERENTES por `size` ────────────────────

    @Test
    void usdotViAndSudotViDecodeToDifferentOperationsAtSameFieldsExceptSize() {
        // 0x4f82f020 (USDOT_vi) e 0x4f02f020 (SUDOT_vi) só diferem em bits[23:22] (`size`).
        AdvSimdIntegerOp64.IntegerDotProductByElement usdot = (AdvSimdIntegerOp64.IntegerDotProductByElement)
                decode(I8MM_DECODER, USDOT_VI_V0_4S_V1_16B_V2_4B0);
        AdvSimdIntegerOp64.IntegerDotProductByElement sudot = (AdvSimdIntegerOp64.IntegerDotProductByElement)
                decode(I8MM_DECODER, SUDOT_VI_V0_4S_V1_16B_V2_4B0);
        assertFalse(usdot.signedN() == sudot.signedN() && usdot.signedM() == sudot.signedM());
    }
}
