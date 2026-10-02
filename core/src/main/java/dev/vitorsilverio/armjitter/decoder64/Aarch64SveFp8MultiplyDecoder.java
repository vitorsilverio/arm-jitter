package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Decoder SVE2 da B17.23: multiply-accumulate `fp8` fundido (`FMLAL_hb`/`FMLALL_sb`, vetorial e indexado,
/// `FEAT_FP8FMA`) e produto escalar `fp8` (`FDOT_hb`/`FDOT_sb`, vetorial e indexado, `FEAT_FP8DOT2`/
/// `FEAT_FP8DOT4`), prefixo `0x64`. **Todo padrão aqui foi medido byte a byte contra `aarch64-none-elf-as`
/// real** (`.arch_extension fp8fma`/`fp8dot2`/`fp8dot4`), não só contra o `.decode` — o campo `idxn` de
/// `FMLAL_hb`/`FMLALL_sb` (não indexados) vive nos bits `[13:12]` (não em `[16]` como o `.decode` bruto
/// sugeriria por analogia com outros grupos), e as formas indexadas usam o MESMO layout de `FMLAL_idx_hb`/
/// `FDOT_idx_hb` (`%index4_19_10`/`%index3_19_11`: metade alta em `[20:19]`, baixa em `[11]`/`[11:10]`).
final class Aarch64SveFp8MultiplyDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int REGISTER_LOW_MASK = 0b111;

    // `FMLAL_hb`/`FMLALL_sb` (não indexado, `&rxx idxm=0`): bit 21 fixo em 1, bit 15 fixo em 1, bit 14 fixo em
    // 0, bit 11 fixo em 1, bit 10 fixo em 0 (o bit 11 = 1 é o que os distingue de `FMLALB_zzzw`/`BFMLALB_zzzw`,
    // que têm bit 11 = 0 no MESMO espaço de bits). `sourceSelect`: 1 bit (bit 12) em `hb`, 2 bits (bits 13:12)
    // em `sb`.
    private static final long FMA_VECTOR_MASK = 0xFF20CC00L;
    private static final long FMA_VECTOR_VALUE = 0x64208800L;
    private static final long FMA_VECTOR_FAMILY_MASK = 0xC00000L;
    private static final long FMA_VECTOR_FAMILY_HB = 0x800000L; // bits[23:22] = `10`
    private static final long FMA_VECTOR_FAMILY_SB = 0x000000L; // bits[23:22] = `00`
    private static final int FMA_VECTOR_RM_SHIFT = 16;
    private static final int FMA_VECTOR_SELECT_SHIFT = 12;

    // `FMLAL_idx_hb` (`&rxx idxm=%index4_19_10`): bits[22:21] fixos em `01`, bits[15:12] fixos em `0101`.
    // `idxn` (`sourceSelect`, 1 bit) = bit 23.
    private static final long FMA_IDX_HB_MASK = 0xFF60F000L;
    private static final long FMA_IDX_HB_VALUE = 0x64205000L;
    private static final int FMA_IDX_HB_IDXN_BIT = 23;

    // `FMLALL_idx_sb`: bit 21 fixo em 1, bits[15:12] fixos em `1100`. `idxn` (`sourceSelect`, 2 bits) =
    // bits[23:22].
    private static final long FMA_IDX_SB_MASK = 0xFF20F000L;
    private static final long FMA_IDX_SB_VALUE = 0x6420C000L;
    private static final int FMA_IDX_SB_IDXN_SHIFT = 22;

    // Índice de 4 bits partido (`%index4_19_10`), compartilhado pelas duas formas indexadas de FMA e por
    // `FDOT_idx_hb`: metade alta em bits[20:19], baixa em bits[11:10] (2+2) ou só bit 11 (2+1, `FDOT_idx_hb`).
    private static final int INDEX_HIGH_SHIFT = 19;
    private static final int INDEX_HIGH_MASK = 0b11;
    private static final int INDEX_LOW2_SHIFT = 10;
    private static final int INDEX_LOW2_MASK = 0b11;
    private static final int INDEX_LOW1_SHIFT = 11;
    private static final int RM_LOW3_SHIFT = 16;

    // `FDOT_hb`/`FDOT_sb` (vetorial, `&rda_rn_rm_ex`-like sem índice): bit 21 fixo em 1, bits[15:8] fixos em
    // `1000 0100`. `wideDestination` = bit 22 (`0` = `hb`, `1` = `sb`; bit 23 sempre 0).
    // Bit 22 é o seletor `hb`/`sb` (variável, NÃO faz parte do valor fixo) — só bit 23 (sempre 0) e bit 21
    // (sempre 1) são fixos nesta família.
    private static final long DOT_VECTOR_MASK = 0xFFA0FF00L;
    private static final long DOT_VECTOR_VALUE = 0x64208400L;
    private static final long DOT_VECTOR_WIDE_BIT_MASK = 0x400000L;

    // `FDOT_idx_hb` (2 vias): bits[23:22] fixos em `00`, bit 21 fixo em 1, bits[15:12] fixos em `0100`,
    // bits[10:8] fixos em `100`.
    private static final long DOT_IDX_HB_MASK = 0xFFE0F700L;
    private static final long DOT_IDX_HB_VALUE = 0x64204400L;

    // `FDOT_idx_sb` (4 vias): bits[23:22] fixos em `01`, bit 21 fixo em 1, bits[15:8] fixos em `0100 0100`
    // (índice de 2 bits DIRETO em bits[20:19], sem metade baixa).
    private static final long DOT_IDX_SB_MASK = 0xFFE0FF00L;
    private static final long DOT_IDX_SB_VALUE = 0x64604400L;

    private final Aarch64Architecture architecture;

    Aarch64SveFp8MultiplyDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x64`. `null` = não é deste grupo.
    Ir64Op decode(int word, long address) {
        Ir64Op fma = decodeFusedMultiplyAdd(word, address);
        return fma != null ? fma : decodeDotProduct(word, address);
    }

    private Ir64Op decodeFusedMultiplyAdd(int word, long address) {
        if (!architecture.has(Aarch64Feature.FP8_FUSED_MULTIPLY_ADD)) {
            return null;
        }
        Ir64Op indexed = decodeFmaIndexedHb(word, address);
        if (indexed != null) {
            return indexed;
        }
        indexed = decodeFmaIndexedSb(word, address);
        if (indexed != null) {
            return indexed;
        }
        return decodeFmaVector(word, address);
    }

    private Ir64Op decodeFmaVector(int word, long address) {
        if ((word & FMA_VECTOR_MASK) != FMA_VECTOR_VALUE) {
            return null;
        }
        long family = word & FMA_VECTOR_FAMILY_MASK;
        boolean wideDestination;
        if (family == FMA_VECTOR_FAMILY_HB) {
            wideDestination = false;
        } else if (family == FMA_VECTOR_FAMILY_SB) {
            wideDestination = true;
        } else {
            return null;
        }
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, FMA_VECTOR_RM_SHIFT, REGISTER_MASK);
        int selectBits = wideDestination ? 2 : 1;
        int select = field(word, FMA_VECTOR_SELECT_SHIFT, (1 << selectBits) - 1);
        if (!wideDestination && bit(word, 13)) {
            return null; // `hb`: bit 13 tem que ser 0 (só `sb` usa os 2 bits)
        }
        return new SveFpOp64.Fp8FusedMultiplyAddLong(wideDestination, rd, rn, rm, select, false, 0, address);
    }

    private Ir64Op decodeFmaIndexedHb(int word, long address) {
        if ((word & FMA_IDX_HB_MASK) != FMA_IDX_HB_VALUE) {
            return null;
        }
        boolean top = bit(word, FMA_IDX_HB_IDXN_BIT);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, RM_LOW3_SHIFT, REGISTER_LOW_MASK);
        int index = index4(word);
        return new SveFpOp64.Fp8FusedMultiplyAddLong(false, rd, rn, rm, top ? 1 : 0, true, index, address);
    }

    private Ir64Op decodeFmaIndexedSb(int word, long address) {
        if ((word & FMA_IDX_SB_MASK) != FMA_IDX_SB_VALUE) {
            return null;
        }
        int idxn = field(word, FMA_IDX_SB_IDXN_SHIFT, 0b11);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, RM_LOW3_SHIFT, REGISTER_LOW_MASK);
        int index = index4(word);
        return new SveFpOp64.Fp8FusedMultiplyAddLong(true, rd, rn, rm, idxn, true, index, address);
    }

    /// `%index4_19_10`: bits[20:19] (alta) concatenados com bits[11:10] (baixa).
    private static int index4(int word) {
        int high = field(word, INDEX_HIGH_SHIFT, INDEX_HIGH_MASK);
        int low = field(word, INDEX_LOW2_SHIFT, INDEX_LOW2_MASK);
        return (high << 2) | low;
    }

    private Ir64Op decodeDotProduct(int word, long address) {
        Ir64Op indexed = decodeDotIndexedHb(word, address);
        if (indexed != null) {
            return indexed;
        }
        indexed = decodeDotIndexedSb(word, address);
        if (indexed != null) {
            return indexed;
        }
        return decodeDotVector(word, address);
    }

    private Ir64Op decodeDotVector(int word, long address) {
        if ((word & DOT_VECTOR_MASK) != DOT_VECTOR_VALUE) {
            return null;
        }
        boolean wideDestination = (word & DOT_VECTOR_WIDE_BIT_MASK) != 0;
        if (!architecture.has(wideDestination ? Aarch64Feature.FP8_DOT_PRODUCT_4WAY : Aarch64Feature.FP8_DOT_PRODUCT_2WAY)) {
            return null;
        }
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, 16, REGISTER_MASK);
        return new SveFpOp64.Fp8DotProduct(wideDestination, rd, rn, rm, false, 0, address);
    }

    private Ir64Op decodeDotIndexedHb(int word, long address) {
        if (!architecture.has(Aarch64Feature.FP8_DOT_PRODUCT_2WAY) || (word & DOT_IDX_HB_MASK) != DOT_IDX_HB_VALUE) {
            return null;
        }
        int high = field(word, INDEX_HIGH_SHIFT, INDEX_HIGH_MASK);
        int low = bit(word, INDEX_LOW1_SHIFT) ? 1 : 0;
        int index = (high << 1) | low;
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, RM_LOW3_SHIFT, REGISTER_LOW_MASK);
        return new SveFpOp64.Fp8DotProduct(false, rd, rn, rm, true, index, address);
    }

    private Ir64Op decodeDotIndexedSb(int word, long address) {
        if (!architecture.has(Aarch64Feature.FP8_DOT_PRODUCT_4WAY) || (word & DOT_IDX_SB_MASK) != DOT_IDX_SB_VALUE) {
            return null;
        }
        int index = field(word, INDEX_HIGH_SHIFT, INDEX_HIGH_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, RM_LOW3_SHIFT, REGISTER_LOW_MASK);
        return new SveFpOp64.Fp8DotProduct(true, rd, rn, rm, true, index, address);
    }

    private static boolean bit(int word, int shift) {
        return ((word >>> shift) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
