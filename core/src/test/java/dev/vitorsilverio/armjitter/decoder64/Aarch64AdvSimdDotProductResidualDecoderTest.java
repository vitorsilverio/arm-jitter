package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.23 — `FEAT_DotProd` residual (`SDOT_v`/`UDOT_v`/`SDOT_vi`/`UDOT_vi`, 4 linhas). Ao contrário
/// de `USDOT`/`SUDOT` (`FEAT_I8MM`, B19.12), estas formas de MESMO sinal nos dois operandos não
/// tinham decoder algum no projeto até esta task (confirmado no `## Resultado` da B19.12). Vetores
/// golden conferidos com `aarch64-linux-gnu-as`/`objdump` (Ubuntu/WSL, `-march=armv8.2-a+dotprod`).
class Aarch64AdvSimdDotProductResidualDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder DOTPROD_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    // -- golden: aarch64-linux-gnu-as/objdump (-march=armv8.2-a+dotprod) --
    private static final int SDOT_V0_4S_V1_16B_V2_16B = 0x4e829420;
    private static final int UDOT_V0_4S_V1_16B_V2_16B = 0x6e829420;
    private static final int SDOT_V3_2S_V4_8B_V5_8B = 0x0e859483;
    private static final int UDOT_V3_2S_V4_8B_V5_8B = 0x2e859483;
    private static final int SDOT_VI_V0_4S_V1_16B_V2_4B0 = 0x4f82e020;
    private static final int SDOT_VI_V0_4S_V1_16B_V2_4B1 = 0x4fa2e020;
    private static final int SDOT_VI_V0_4S_V1_16B_V2_4B2 = 0x4f82e820;
    private static final int SDOT_VI_V0_4S_V1_16B_V2_4B3 = 0x4fa2e820;
    private static final int UDOT_VI_V0_4S_V1_16B_V2_4B0 = 0x6f82e020;
    private static final int UDOT_VI_V0_4S_V1_16B_V2_4B1 = 0x6fa2e020;
    private static final int UDOT_VI_V0_4S_V1_16B_V2_4B2 = 0x6f82e820;
    private static final int UDOT_VI_V0_4S_V1_16B_V2_4B3 = 0x6fa2e820;
    private static final int SDOT_VI_V0_2S_V1_8B_V2_4B3 = 0x0fa2e820;
    private static final int SDOT_VI_V0_4S_V1_16B_V31_4B3 = 0x4fbfe820;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Regressão negativa: sem a feature, TODAS as 4 formas continuam `unsupported` ───────────────

    @Test
    void allRejectedWithoutDotProductFeature() {
        int[] words = {
                SDOT_V0_4S_V1_16B_V2_16B, UDOT_V0_4S_V1_16B_V2_16B,
                SDOT_V3_2S_V4_8B_V5_8B, UDOT_V3_2S_V4_8B_V5_8B,
                SDOT_VI_V0_4S_V1_16B_V2_4B0, SDOT_VI_V0_4S_V1_16B_V2_4B1,
                SDOT_VI_V0_4S_V1_16B_V2_4B2, SDOT_VI_V0_4S_V1_16B_V2_4B3,
                UDOT_VI_V0_4S_V1_16B_V2_4B0, UDOT_VI_V0_4S_V1_16B_V2_4B1,
                UDOT_VI_V0_4S_V1_16B_V2_4B2, UDOT_VI_V0_4S_V1_16B_V2_4B3,
        };
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria ser unsupported sem FEAT_DotProd");
        }
    }

    // ── SDOT_v/UDOT_v (vetorial) ────────────────────────────────────────────────────────────────

    @Test
    void sdotVector4s() {
        Ir64Op.VectorIntegerDotProduct op =
                (Ir64Op.VectorIntegerDotProduct) decode(DOTPROD_DECODER, SDOT_V0_4S_V1_16B_V2_16B);
        assertTrue(op.q());
        assertTrue(op.signedN());
        assertTrue(op.signedM());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void udotVector4s() {
        Ir64Op.VectorIntegerDotProduct op =
                (Ir64Op.VectorIntegerDotProduct) decode(DOTPROD_DECODER, UDOT_V0_4S_V1_16B_V2_16B);
        assertTrue(op.q());
        assertFalse(op.signedN());
        assertFalse(op.signedM());
    }

    @Test
    void sdotVector2s() {
        Ir64Op.VectorIntegerDotProduct op =
                (Ir64Op.VectorIntegerDotProduct) decode(DOTPROD_DECODER, SDOT_V3_2S_V4_8B_V5_8B);
        assertFalse(op.q());
        assertTrue(op.signedN());
        assertTrue(op.signedM());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void udotVector2s() {
        Ir64Op.VectorIntegerDotProduct op =
                (Ir64Op.VectorIntegerDotProduct) decode(DOTPROD_DECODER, UDOT_V3_2S_V4_8B_V5_8B);
        assertFalse(op.q());
        assertFalse(op.signedN());
        assertFalse(op.signedM());
    }

    // ── SDOT_vi/UDOT_vi (indexado) — `Rm` de 5 bits LIVRES, diferente do `USDOT_vi` restrito ───────

    @Test
    void sdotIndexedIndex0() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, SDOT_VI_V0_4S_V1_16B_V2_4B0);
        assertTrue(op.q());
        assertTrue(op.signedN());
        assertTrue(op.signedM());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void sdotIndexedIndex1() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, SDOT_VI_V0_4S_V1_16B_V2_4B1);
        assertEquals(1, op.index());
    }

    @Test
    void sdotIndexedIndex2() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, SDOT_VI_V0_4S_V1_16B_V2_4B2);
        assertEquals(2, op.index());
    }

    @Test
    void sdotIndexedIndex3() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, SDOT_VI_V0_4S_V1_16B_V2_4B3);
        assertEquals(3, op.index());
    }

    @Test
    void udotIndexedIndex0() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, UDOT_VI_V0_4S_V1_16B_V2_4B0);
        assertFalse(op.signedN());
        assertFalse(op.signedM());
        assertEquals(0, op.index());
    }

    @Test
    void udotIndexedIndex1() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, UDOT_VI_V0_4S_V1_16B_V2_4B1);
        assertEquals(1, op.index());
    }

    @Test
    void udotIndexedIndex2() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, UDOT_VI_V0_4S_V1_16B_V2_4B2);
        assertEquals(2, op.index());
    }

    @Test
    void udotIndexedIndex3() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, UDOT_VI_V0_4S_V1_16B_V2_4B3);
        assertEquals(3, op.index());
    }

    @Test
    void sdotIndexed2s() {
        Ir64Op.VectorIntegerDotProductByElement op =
                (Ir64Op.VectorIntegerDotProductByElement) decode(DOTPROD_DECODER, SDOT_VI_V0_2S_V1_8B_V2_4B3);
        assertFalse(op.q());
        assertEquals(3, op.index());
    }

    @Test
    void sdotIndexedRmIsFullFiveBitsUnlikeUsdotViRestrictedToV15() {
        // `USDOT_vi`/`SUDOT_vi` restringem `Rm` a `V0`-`V15` (`@qrrx_s` com `Rm` de 4 bits); `SDOT_vi`/
        // `UDOT_vi` usam o `Rm` de 5 bits LIVRES do formato geral `@qrrx_s` — `v31` é alcançável.
        Ir64Op.VectorIntegerDotProductByElement op = (Ir64Op.VectorIntegerDotProductByElement)
                decode(DOTPROD_DECODER, SDOT_VI_V0_4S_V1_16B_V31_4B3);
        assertEquals(31, op.rm());
        assertEquals(3, op.index());
    }
}
