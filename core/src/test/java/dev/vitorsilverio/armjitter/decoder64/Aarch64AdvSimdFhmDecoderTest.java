package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.13 — A64 `FEAT_FHM` (`FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2`, vetorial + indexado, 8 linhas).
/// Vetores golden conferidos com `arm-linux-gnu-as`/`objdump` (WSL, `-march=armv8.2-a+fp16+fp16fml`
/// — o Ubuntu deste ambiente não tem `aarch64-none-elf-as`/devkitA64).
class Aarch64AdvSimdFhmDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder FHM_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    // -- golden: arm-linux-gnu-as/objdump (WSL, -march=armv8.2-a+fp16+fp16fml) --
    private static final int FMLAL_V0_4S_V1_4H_V2_4H = 0x4e22ec20;
    private static final int FMLAL_V0_2S_V1_2H_V2_2H = 0x0e22ec20;
    private static final int FMLAL2_V0_4S_V1_4H_V2_4H = 0x6e22cc20;
    private static final int FMLAL2_V0_2S_V1_2H_V2_2H = 0x2e22cc20;
    private static final int FMLSL_V0_4S_V1_4H_V2_4H = 0x4ea2ec20;
    private static final int FMLSL_V0_2S_V1_2H_V2_2H = 0x0ea2ec20;
    private static final int FMLSL2_V0_4S_V1_4H_V2_4H = 0x6ea2cc20;
    private static final int FMLSL2_V0_2S_V1_2H_V2_2H = 0x2ea2cc20;
    private static final int FMLAL_V1_4S_V1_4H_V2_4H = 0x4e22ec21; // rd==rn (aliasing)
    private static final int FMLAL_V1_4S_V3_4H_V1_4H = 0x4e21ec61; // rd==rm (aliasing)

    private static final int FMLAL_VI_V0_4S_V1_4H_V2_H0 = 0x4f820020;
    private static final int FMLAL_VI_V0_4S_V1_4H_V2_H7 = 0x4fb20820;
    private static final int FMLAL_VI_V0_4S_V1_4H_V2_H3 = 0x4fb20020;
    private static final int FMLAL2_VI_V0_4S_V1_4H_V2_H0 = 0x6f828020;
    private static final int FMLAL2_VI_V0_4S_V1_4H_V2_H3 = 0x6fb28020;
    private static final int FMLSL_VI_V0_4S_V1_4H_V2_H0 = 0x4f824020;
    private static final int FMLSL2_VI_V0_4S_V1_4H_V2_H0 = 0x6f82c020;
    private static final int FMLSL2_VI_V0_4S_V1_4H_V2_H7 = 0x6fb2c820;
    private static final int FMLAL_VI_V0_2S_V1_2H_V2_H0 = 0x0f820020;
    private static final int FMLAL2_VI_V0_2S_V1_2H_V2_H0 = 0x2f828020;
    private static final int FMLAL_VI_V1_4S_V1_4H_V2_H3 = 0x4fb20021; // rd==rn (aliasing)
    private static final int FMLAL_VI_V1_4S_V3_4H_V1_H3 = 0x4fb10061; // rd==rm (aliasing)

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Regressão negativa: sem a feature, as 8 continuam `unsupported` ────────────────────────────

    @Test
    void allEightRejectedWithoutFhmFeature() {
        int[] words = {
                FMLAL_V0_4S_V1_4H_V2_4H, FMLAL2_V0_4S_V1_4H_V2_4H,
                FMLSL_V0_4S_V1_4H_V2_4H, FMLSL2_V0_4S_V1_4H_V2_4H,
                FMLAL_VI_V0_4S_V1_4H_V2_H0, FMLAL2_VI_V0_4S_V1_4H_V2_H0,
                FMLSL_VI_V0_4S_V1_4H_V2_H0, FMLSL2_VI_V0_4S_V1_4H_V2_H0,
        };
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria ser unsupported sem FEAT_FHM");
        }
    }

    // ── Independência de feature (Aceite): FP16 sem FHM recusa; FHM sem FP16 aceita ────────────────

    @Test
    void fp16WithoutFhmStillRejectsTheEight() {
        Aarch64Architecture fp16Only = Aarch64Architecture.of("fp16-only", Aarch64Feature.FP16);
        Aarch64Decoder decoder = new Aarch64Decoder(fp16Only);
        assertThrows(UnsupportedOperationException.class,
                () -> decode(decoder, FMLAL_V0_4S_V1_4H_V2_4H));
        assertThrows(UnsupportedOperationException.class,
                () -> decode(decoder, FMLAL_VI_V0_4S_V1_4H_V2_H0));
    }

    @Test
    void fhmWithoutFp16StillAcceptsTheEight() {
        Aarch64Architecture fhmOnly =
                Aarch64Architecture.of("fhm-only", Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG);
        Aarch64Decoder decoder = new Aarch64Decoder(fhmOnly);
        Ir64Op.VectorFpMultiplyAddLong op =
                (Ir64Op.VectorFpMultiplyAddLong) decode(decoder, FMLAL_V0_4S_V1_4H_V2_4H);
        assertFalse(op.subtract());
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(decoder, FMLAL_VI_V0_4S_V1_4H_V2_H0);
        assertFalse(idx.subtract());
    }

    // ── FMLAL/FMLSL/FMLAL2/FMLSL2 (vetorial) ────────────────────────────────────────────────────────

    @Test
    void fmlalVector4s() {
        Ir64Op.VectorFpMultiplyAddLong op =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL_V0_4S_V1_4H_V2_4H);
        assertTrue(op.q());
        assertFalse(op.top());
        assertFalse(op.subtract());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fmlalVector2s() {
        Ir64Op.VectorFpMultiplyAddLong op =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL_V0_2S_V1_2H_V2_2H);
        assertFalse(op.q());
        assertFalse(op.top());
        assertFalse(op.subtract());
    }

    @Test
    void fmlal2VectorSelectsTop() {
        Ir64Op.VectorFpMultiplyAddLong op4s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL2_V0_4S_V1_4H_V2_4H);
        assertTrue(op4s.q());
        assertTrue(op4s.top());
        assertFalse(op4s.subtract());

        Ir64Op.VectorFpMultiplyAddLong op2s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL2_V0_2S_V1_2H_V2_2H);
        assertFalse(op2s.q());
        assertTrue(op2s.top());
    }

    @Test
    void fmlslVectorSelectsSubtract() {
        Ir64Op.VectorFpMultiplyAddLong op4s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLSL_V0_4S_V1_4H_V2_4H);
        assertFalse(op4s.top());
        assertTrue(op4s.subtract());

        Ir64Op.VectorFpMultiplyAddLong op2s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLSL_V0_2S_V1_2H_V2_2H);
        assertFalse(op2s.top());
        assertTrue(op2s.subtract());
    }

    @Test
    void fmlsl2VectorSelectsTopAndSubtract() {
        Ir64Op.VectorFpMultiplyAddLong op4s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLSL2_V0_4S_V1_4H_V2_4H);
        assertTrue(op4s.top());
        assertTrue(op4s.subtract());

        Ir64Op.VectorFpMultiplyAddLong op2s =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLSL2_V0_2S_V1_2H_V2_2H);
        assertTrue(op2s.top());
        assertTrue(op2s.subtract());
    }

    @Test
    void fmlalVectorAliasingRdEqualsRn() {
        Ir64Op.VectorFpMultiplyAddLong op =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL_V1_4S_V1_4H_V2_4H);
        assertEquals(1, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fmlalVectorAliasingRdEqualsRm() {
        Ir64Op.VectorFpMultiplyAddLong op =
                (Ir64Op.VectorFpMultiplyAddLong) decode(FHM_DECODER, FMLAL_V1_4S_V3_4H_V1_4H);
        assertEquals(1, op.rd());
        assertEquals(3, op.rn());
        assertEquals(1, op.rm());
    }

    // ── FMLAL_vi/FMLSL_vi/FMLAL2_vi/FMLSL2_vi (indexado) ────────────────────────────────────────────

    @Test
    void fmlalIndexedReadsHlmIndex() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx0 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V0_4S_V1_4H_V2_H0);
        assertTrue(idx0.q());
        assertFalse(idx0.top());
        assertFalse(idx0.subtract());
        assertEquals(0, idx0.rd());
        assertEquals(1, idx0.rn());
        assertEquals(2, idx0.rm());
        assertEquals(0, idx0.index());

        Ir64Op.VectorFpMultiplyAddLongByElement idx3 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V0_4S_V1_4H_V2_H3);
        assertEquals(3, idx3.index());

        Ir64Op.VectorFpMultiplyAddLongByElement idx7 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V0_4S_V1_4H_V2_H7);
        assertEquals(7, idx7.index());
    }

    @Test
    void fmlal2IndexedSelectsTop() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx0 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL2_VI_V0_4S_V1_4H_V2_H0);
        assertTrue(idx0.top());
        assertFalse(idx0.subtract());

        Ir64Op.VectorFpMultiplyAddLongByElement idx3 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL2_VI_V0_4S_V1_4H_V2_H3);
        assertTrue(idx3.top());
        assertEquals(3, idx3.index());
    }

    @Test
    void fmlslIndexedSelectsSubtract() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLSL_VI_V0_4S_V1_4H_V2_H0);
        assertFalse(idx.top());
        assertTrue(idx.subtract());
    }

    @Test
    void fmlsl2IndexedSelectsTopAndSubtract() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx0 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLSL2_VI_V0_4S_V1_4H_V2_H0);
        assertTrue(idx0.top());
        assertTrue(idx0.subtract());

        Ir64Op.VectorFpMultiplyAddLongByElement idx7 =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLSL2_VI_V0_4S_V1_4H_V2_H7);
        assertEquals(7, idx7.index());
    }

    @Test
    void fmlalIndexed2s() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V0_2S_V1_2H_V2_H0);
        assertFalse(idx.q());
        assertFalse(idx.top());
    }

    @Test
    void fmlal2Indexed2s() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL2_VI_V0_2S_V1_2H_V2_H0);
        assertFalse(idx.q());
        assertTrue(idx.top());
    }

    @Test
    void fmlalIndexedAliasingRdEqualsRn() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V1_4S_V1_4H_V2_H3);
        assertEquals(1, idx.rd());
        assertEquals(1, idx.rn());
        assertEquals(2, idx.rm());
        assertEquals(3, idx.index());
    }

    @Test
    void fmlalIndexedAliasingRdEqualsRm() {
        Ir64Op.VectorFpMultiplyAddLongByElement idx =
                (Ir64Op.VectorFpMultiplyAddLongByElement) decode(FHM_DECODER, FMLAL_VI_V1_4S_V3_4H_V1_H3);
        assertEquals(1, idx.rd());
        assertEquals(3, idx.rn());
        assertEquals(1, idx.rm());
        assertEquals(3, idx.index());
    }
}
