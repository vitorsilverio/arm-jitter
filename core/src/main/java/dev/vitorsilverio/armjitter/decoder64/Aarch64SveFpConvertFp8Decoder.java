package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Decoder SVE2 das conversões `fp8` sem predicado da B17.23 (`FEAT_SVE_F8CVT`), prefixo `0x65`: alargar
/// (`F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/`BF2CVTLT`) e estreitar
/// (`FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT`). Padrões medidos byte a byte contra `target/isa-decode/sve.decode`
/// (linhas 1124-1137) e confirmados por assemblagem real (`aarch64-none-elf-as`).
final class Aarch64SveFpConvertFp8Decoder {
    private static final int REGISTER_MASK = 0b11111;

    // `F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/`BF2CVTLT`: prefixo `0x65`, bits[23:19]
    // fixos em `00 001`, bits[15:12] fixos em `0011`. `top` = bit 16, `bfloat16Destination` = bit 11,
    // `stream2` = bit 10.
    private static final long WIDEN_MASK = 0xFFFEF000L;
    private static final long WIDEN_VALUE = 0x65083000L;
    private static final int WIDEN_TOP_BIT = 16;
    private static final int WIDEN_BFLOAT16_BIT = 11;
    private static final int WIDEN_STREAM2_BIT = 10;

    // `FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT`: prefixo `0x65`, bits[23:16] fixos em `00 001 010`, bit 5 fixo em `0`
    // (o par-fonte é sempre par: `rn` cru de 4 bits em `%rn_ax2`, `× 2`). Só bits[15:10] variam por mnemônico.
    private static final long NARROW_MASK = 0xFFFFC020L;
    private static final long NARROW_BASE_VALUE = 0x650A0000L;
    private static final long NARROW_FCVTN = 0x3000L;
    private static final long NARROW_BFCVTN = 0x3800L;
    private static final long NARROW_FCVTNB = 0x3400L;
    private static final long NARROW_FCVTNT = 0x3C00L;
    private static final int NARROW_RN_RAW_SHIFT = 6;
    private static final int NARROW_RN_RAW_MASK = 0b1111;
    private static final int NARROW_RN_RAW_TO_ACTUAL_SHIFT = 1; // `× 2` (`%rn_ax2`)

    private final Aarch64Architecture architecture;

    Aarch64SveFpConvertFp8Decoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x65`. `null` = não é deste grupo, ou a arquitetura não declara
    /// `FEAT_SVE_F8CVT`.
    Ir64Op decode(int word, long address) {
        if (!architecture.has(Aarch64Feature.FP8_CONVERT)) {
            return null;
        }
        Ir64Op widen = decodeWiden(word, address);
        return widen != null ? widen : decodeNarrow(word, address);
    }

    private Ir64Op decodeWiden(int word, long address) {
        if ((word & WIDEN_MASK) != WIDEN_VALUE) {
            return null;
        }
        boolean top = bit(word, WIDEN_TOP_BIT);
        boolean bfloat16Destination = bit(word, WIDEN_BFLOAT16_BIT);
        boolean stream2 = bit(word, WIDEN_STREAM2_BIT);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        return new SveFpOp64.FpConvertFp8(rd, rn, stream2, top, bfloat16Destination, address);
    }

    private Ir64Op decodeNarrow(int word, long address) {
        if ((word & NARROW_MASK) != NARROW_BASE_VALUE) {
            return null;
        }
        long variant = word & 0x3C00L;
        int rd = field(word, 0, REGISTER_MASK);
        int rnRaw = field(word, NARROW_RN_RAW_SHIFT, NARROW_RN_RAW_MASK);
        int rn = rnRaw << NARROW_RN_RAW_TO_ACTUAL_SHIFT;
        if (variant == NARROW_FCVTN) {
            return new SveFpOp64.FpConvertToFp8(rd, rn, false, false, false, address);
        }
        if (variant == NARROW_BFCVTN) {
            return new SveFpOp64.FpConvertToFp8(rd, rn, true, false, false, address);
        }
        if (variant == NARROW_FCVTNB) {
            return new SveFpOp64.FpConvertToFp8(rd, rn, false, true, false, address);
        }
        if (variant == NARROW_FCVTNT) {
            return new SveFpOp64.FpConvertToFp8(rd, rn, false, true, true, address);
        }
        return null;
    }

    private static boolean bit(int word, int shift) {
        return ((word >>> shift) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
