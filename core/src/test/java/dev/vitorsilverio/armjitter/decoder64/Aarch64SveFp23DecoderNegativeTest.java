package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/// B17.23 — casos negativos internos dos decoders novos que a cadeia completa do `Aarch64SveDecoder` não
/// alcança sozinha (um decoder anterior já consome o prefixo antes da palavra chegar ao método certo) —
/// mesmo motivo do teste isolado de `Aarch64SveFpCompareReduceDecoderTest` (B17.15).
class Aarch64SveFp23DecoderNegativeTest {
    private static final Aarch64Architecture SVE2 =
            Aarch64Architecture.extending(Aarch64Architecture.ARMV9_0_A, "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture F8CVT =
            Aarch64Architecture.extending(SVE2, "teste-F8CVT", Aarch64Feature.FP8_CONVERT);
    private static final Aarch64Architecture FP8_FMA =
            Aarch64Architecture.extending(SVE2, "teste-FP8FMA", Aarch64Feature.FP8_FUSED_MULTIPLY_ADD);

    @Test
    void matrixDecoderPairwiseRejectsUnmatchedFixedBits() {
        Aarch64SveFpMatrixDecoder decoder = new Aarch64SveFpMatrixDecoder(SVE2);
        // prefixo/`bit21` certos, mas bits[21:19] ≠ `010` (marcador fixo do pairwise).
        assertNull(decoder.decode(0x64000000, 0));
        // casa o pairwise, mas `esz = 0` é BFloat16 (`FEAT_SVE_B16B16`) — pendência nomeada.
        assertNull(decoder.decode(0x64108000, 0)); // FADDP com esz=0
    }

    @Test
    void fp8MultiplyDecoderIndexedFormsRejectUnmatchedFixedBits() {
        Aarch64SveFp8MultiplyDecoder decoder = new Aarch64SveFp8MultiplyDecoder(FP8_FMA);
        // bit 21 = 1 mas bits[22:21] ≠ `01` (marcador fixo de `FMLAL_idx_hb`) nem bits[15:12] = `1100`
        // (marcador fixo de `FMLALL_idx_sb`).
        assertNull(decoder.decode(0x64200000, 0));
        // casa `FMA_VECTOR_MASK`/`VALUE` (que não checa bits[23:22]), mas bits[23:22] = `01`/`11` não são
        // `hb` (`10`) nem `sb` (`00`) — combinação não alocada.
        assertNull(decoder.decode(0x64608800, 0));
    }

    @Test
    void convertFp8DecoderRejectsUnmatchedFixedBits() {
        Aarch64SveFpConvertFp8Decoder decoder = new Aarch64SveFpConvertFp8Decoder(F8CVT);
        // prefixo certo (`0x65`), mas nem o marcador fixo de widen (`001` em bits[21:19]) nem o de narrow
        // (`001 010` em bits[21:16]) batem.
        assertNull(decoder.decode(0x65000000, 0));
        // casa o marcador fixo de narrow, mas bits[13:10] não é nenhuma das 4 variantes conhecidas.
        assertNull(decoder.decode(0x650A0000, 0));
    }
}
