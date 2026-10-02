package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `ADDHA`/`ADDVA` e produto externo (B18.5). **Toda palavra abaixo foi conferida contra
/// `aarch64-none-elf-as -march=armv9.4-a+sme2+sme-i16i64+sme-f64f64+sme-f16f16+sme-b16b16+sme-f8f16+sme-f8f32`
/// (devkitA64)** — nenhuma calculada à mão (Armadilha 3: `SMOPA`/`SUMOPA`/`USMOPA`/`UMOPA` diferem em dois bits
/// espalhados, e trocar dois deles é invisível sem corpus real).
class Aarch64SmeOuterProductDecoderTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-op-dec-ALL", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_I16I64,
            Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16, Aarch64Feature.SME_B16B16,
            Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);

    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);
    private static final Aarch64Decoder ALL_DECODER = new Aarch64Decoder(ALL);

    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return decoder.decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    /// `palavra, op, tile, zn, zm, pn, pm, subtract` — a coluna `zm` é `0` em `ADDHA`/`ADDVA` (não há `Zm`).
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "0xC0904460, ADDHA_S, 0, 3, 0, 1, 2, false",
            "0xC091D7E3, ADDVA_S, 3, 31, 0, 5, 6, false",
            "0xC0D0E122, ADDHA_D, 2, 9, 0, 0, 7, false",
            "0xC0D18E27, ADDVA_D, 7, 17, 0, 3, 4, false",
            "0x81844469, FMOPA_H, 1, 3, 4, 1, 2, false",
            "0x81844479, FMOPA_H, 1, 3, 4, 1, 2, true",
            "0x81A32049, BFMOPA, 1, 2, 3, 0, 1, false",
            "0x81A32058, BFMOPA, 0, 2, 3, 0, 1, true",
            "0x80844463, FMOPA_S, 3, 3, 4, 1, 2, false",
            "0x809EDFF2, FMOPA_S, 2, 31, 30, 7, 6, true",
            "0x80C44467, FMOPA_D, 7, 3, 4, 1, 2, false",
            "0x80C44476, FMOPA_D, 6, 3, 4, 1, 2, true",
            "0x81A44462, FMOPA_W_H, 2, 3, 4, 1, 2, false",
            "0x81A44473, FMOPA_W_H, 3, 3, 4, 1, 2, true",
            "0x81832041, BFMOPA_W, 1, 2, 3, 0, 1, false",
            "0x81832052, BFMOPA_W, 2, 2, 3, 0, 1, true",
            "0x80A44463, FMOPA_SB, 3, 3, 4, 1, 2, false",
            "0x80A44469, FMOPA_HB, 1, 3, 4, 1, 2, false",
            "0xA0844462, SMOPA_S, 2, 3, 4, 1, 2, false",
            "0xA0844473, SMOPA_S, 3, 3, 4, 1, 2, true",
            "0xA0A44460, SUMOPA_S, 0, 3, 4, 1, 2, false",
            "0xA0A44471, SUMOPA_S, 1, 3, 4, 1, 2, true",
            "0xA1844462, USMOPA_S, 2, 3, 4, 1, 2, false",
            "0xA1844473, USMOPA_S, 3, 3, 4, 1, 2, true",
            "0xA1A44460, UMOPA_S, 0, 3, 4, 1, 2, false",
            "0xA1A44471, UMOPA_S, 1, 3, 4, 1, 2, true",
            "0xA0C44465, SMOPA_D, 5, 3, 4, 1, 2, false",
            "0xA0C44476, SMOPA_D, 6, 3, 4, 1, 2, true",
            "0xA0E44461, SUMOPA_D, 1, 3, 4, 1, 2, false",
            "0xA0E44472, SUMOPA_D, 2, 3, 4, 1, 2, true",
            "0xA1C44463, USMOPA_D, 3, 3, 4, 1, 2, false",
            "0xA1C44474, USMOPA_D, 4, 3, 4, 1, 2, true",
            "0xA1E44465, UMOPA_D, 5, 3, 4, 1, 2, false",
            "0xA1E44477, UMOPA_D, 7, 3, 4, 1, 2, true",
            "0x80832049, BMOPA, 1, 2, 3, 0, 1, false",
            "0x8083205A, BMOPA, 2, 2, 3, 0, 1, true",
            "0xA0832049, SMOPA2_S, 1, 2, 3, 0, 1, false",
            "0xA083205A, SMOPA2_S, 2, 2, 3, 0, 1, true",
            "0xA183204B, UMOPA2_S, 3, 2, 3, 0, 1, false",
            "0xA1832058, UMOPA2_S, 0, 2, 3, 0, 1, true",
    })
    void decodesEveryEncodingWithItsFields(String word, SmeOp64.OuterProduct.Op expected, int tile, int zn, int zm,
            int pn, int pm, boolean subtract) {
        SmeOp64.OuterProduct op = assertInstanceOf(SmeOp64.OuterProduct.class,
                decode(ALL_DECODER, (int) Long.decode(word).longValue()));
        assertEquals(expected, op.op());
        assertEquals(tile, op.tile());
        assertEquals(zn, op.zn());
        assertEquals(zm, op.zm());
        assertEquals(pn, op.pn());
        assertEquals(pm, op.pm());
        assertEquals(subtract, op.subtract());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @Test
    void theTileFieldWidthFollowsTheAccumulatorElementSize() {
        // `zad:1` (16 bits) = 2 tiles, `zad:2` (32 bits) = 4, `zad:3` (64 bits) = 8 — o tile 7 só existe em `.d`.
        assertEquals(1, ((SmeOp64.OuterProduct) decode(ALL_DECODER, 0x81844469)).tile());
        assertEquals(3, ((SmeOp64.OuterProduct) decode(ALL_DECODER, 0x80844463)).tile());
        assertEquals(7, ((SmeOp64.OuterProduct) decode(ALL_DECODER, 0x80C44467)).tile());
    }

    @Test
    void onlyFeatSmeFormsDecodeOnAPlainSmePreset() {
        for (int word : new int[] {0xC0904460, 0xC091D7E3, 0x80844463, 0x81A44462, 0x81832041, 0xA0844462, 0xA0A44460,
                0xA1844462, 0xA1A44460}) {
            assertInstanceOf(SmeOp64.OuterProduct.class, decode(SME_DECODER, word), Integer.toHexString(word));
        }
    }

    @Test
    void slicedFeaturesAreRequired() {
        int[] gated = {
                0xC0D0E122, 0xC0D18E27, // ADDHA_d/ADDVA_d — FEAT_SME_I16I64
                0xA0C44465, 0xA0E44461, 0xA1C44463, 0xA1E44465, // SMOPA_d… — FEAT_SME_I16I64
                0x80C44467, // FMOPA_d — FEAT_SME_F64F64
                0x81844469, // FMOPA_h — FEAT_SME_F16F16
                0x81A32049, // BFMOPA — FEAT_SME_B16B16
                0x80A44463, // FMOPA_sb — FEAT_SME_F8F32
                0x80A44469, // FMOPA_hb — FEAT_SME_F8F16
                0x80832049, 0xA0832049, 0xA183204B, // BMOPA/SMOPA2/UMOPA2 — FEAT_SME2
        };
        for (int word : gated) {
            assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, word),
                    Integer.toHexString(word));
            assertInstanceOf(SmeOp64.OuterProduct.class, decode(ALL_DECODER, word), Integer.toHexString(word));
        }
    }

    @Test
    void eachSlicedFeatureUnlocksOnlyItsOwnForms() {
        assertAccepts(Aarch64Feature.SME_I16I64, 0xA0C44465);
        assertRejects(Aarch64Feature.SME_I16I64, 0x80C44467);
        assertAccepts(Aarch64Feature.SME_F64F64, 0x80C44467);
        assertRejects(Aarch64Feature.SME_F64F64, 0xA0C44465);
        assertAccepts(Aarch64Feature.SME_F16F16, 0x81844469);
        assertRejects(Aarch64Feature.SME_F16F16, 0x81A32049);
        assertAccepts(Aarch64Feature.SME_B16B16, 0x81A32049);
        assertRejects(Aarch64Feature.SME_B16B16, 0x81844469);
        assertAccepts(Aarch64Feature.SME_F8F32, 0x80A44463);
        assertRejects(Aarch64Feature.SME_F8F32, 0x80A44469);
        assertAccepts(Aarch64Feature.SME_F8F16, 0x80A44469);
        assertRejects(Aarch64Feature.SME_F8F16, 0x80A44463);
    }

    private static void assertAccepts(Aarch64Feature feature, int word) {
        Aarch64Architecture architecture = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
                "teste-op-dec-" + feature, feature);
        assertInstanceOf(SmeOp64.OuterProduct.class, decode(new Aarch64Decoder(architecture), word));
    }

    private static void assertRejects(Aarch64Feature feature, int word) {
        Aarch64Architecture architecture = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
                "teste-op-dec-" + feature, feature);
        assertThrows(UnsupportedOperationException.class, () -> decode(new Aarch64Decoder(architecture), word));
    }

    @Test
    void rejectedGenericallyWithoutSme() {
        for (int word : new int[] {0xC0904460, 0x80844463, 0xA0844462, 0x81A44462}) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word),
                    Integer.toHexString(word));
        }
    }

    @Test
    void reservedNeighboursStayUnimplemented() {
        // ADDHA_s com bit 2 (campo `...` reservado) ligado
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, 0xC0904464));
        // ADDHA_d com bit 3 ligado
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, 0xC0D04468));
        // FMOPA_s com bits[3:2] = 11 (nenhuma linha)
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, 0x8084446C));
        // FMOPA_sb com sub = 1 (o `.decode` fixa `0`)
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, 0x80A44473));
        // FMOPA_hb com sub = 1
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, 0x80A44479));
    }

    @Test
    void theTwoFp8FormsHaveNoSubtractBit() {
        assertFalse(((SmeOp64.OuterProduct) decode(ALL_DECODER, 0x80A44463)).subtract());
        assertFalse(((SmeOp64.OuterProduct) decode(ALL_DECODER, 0x80A44469)).subtract());
    }
}
