package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE2 da B17.23: as 12 conversões "odd elements" (`FCVTNT_sh`/`FCVTLT_hs`/`FCVTNT_ds`/`FCVTLT_sd`/
/// `FCVTXNT_ds`/`BFCVTNT`, formas `_m`/`_z`, prefixo `0x64`) e `FLOGB` (`_m` no `0x65`, `_z` no `0x64`).
/// `FCVTX_ds_m` (a 13ª linha do recorte da B17.23) tem seu PRÓPRIO método aqui, mas devolve um
/// {@link Ir64Op.SveFpUnary} — reusa o MESMO `Kind`/executor de `FCVTX_ds_z` (B17.16, `Op.FCVTX`), já que é a
/// mesma instrução decodificada de um layout de bits diferente (Achado 2 da task).
///
/// Padrões medidos byte a byte contra `target/isa-decode/sve.decode` (linhas 1954-1972) e contra
/// `do_frint_mode`/`gen_helper_sve2_fcvtnt_ds`/`flogb_fns` de `translate-sve.c` real.
final class Aarch64SveFpConvertOddDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_MASK = 0b111;

    // Odd elements: prefixo `0x64`, bits[15:13] fixos em `101`. `a` = bits[23:22], `b` = bits[21:18],
    // `c` = bits[17:16].
    private static final long ODD_MASK = 0xFF00E000L;
    private static final long ODD_VALUE = 0x6400A000L;
    private static final int ODD_A_SHIFT = 22;
    private static final int ODD_B_SHIFT = 18;
    private static final int ODD_B_MASK = 0b1111;
    private static final int ODD_C_SHIFT = 16;
    private static final int ODD_PG_SHIFT = 10;
    private static final int ODD_B_MERGING = 0b0010;
    private static final int ODD_B_ZEROING = 0b0000;

    /// `FCVTX_ds_m`: casamento exato (sem campos além de `pg`/`rn`/`rd`), prefixo `0x65`.
    private static final long FCVTX_DS_M_MASK = 0xFFFFE000L;
    private static final long FCVTX_DS_M_VALUE = 0x650AA000L;

    // `FLOGB_m`: prefixo `0x65`, `esz` em bits[18:17]. `FLOGB_z`: prefixo `0x64`, `esz` em bits[14:13].
    private static final long FLOGB_M_MASK = 0xFFF9E000L;
    private static final long FLOGB_M_VALUE = 0x6518A000L;
    private static final int FLOGB_M_ESZ_SHIFT = 17;
    private static final long FLOGB_Z_MASK = 0xFFFF8000L;
    private static final long FLOGB_Z_VALUE = 0x641E8000L;
    private static final int FLOGB_Z_ESZ_SHIFT = 13;

    private final Aarch64Architecture architecture;

    Aarch64SveFpConvertOddDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x64`. `null` = não é deste grupo, ou a arquitetura não declara
    /// `FEAT_SVE2`.
    Ir64Op decodePrefix64(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        Ir64Op odd = decodeOddElements(word, address);
        return odd != null ? odd : decodeFlogbZ(word, address);
    }

    /// Decodifica uma palavra do prefixo `0x65`. `null` = não é deste grupo, ou a arquitetura não declara
    /// `FEAT_SVE2`.
    Ir64Op decodePrefix65(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        Ir64Op fcvtxDsM = decodeFcvtxDsM(word, address);
        return fcvtxDsM != null ? fcvtxDsM : decodeFlogbM(word, address);
    }

    private Ir64Op decodeOddElements(int word, long address) {
        if ((word & ODD_MASK) != ODD_VALUE) {
            return null;
        }
        int a = field(word, ODD_A_SHIFT, 0b11);
        int b = field(word, ODD_B_SHIFT, ODD_B_MASK);
        int c = field(word, ODD_C_SHIFT, 0b11);
        boolean zeroing;
        if (b == ODD_B_MERGING) {
            zeroing = false;
        } else if (b == ODD_B_ZEROING) {
            zeroing = true;
        } else {
            return null;
        }
        return decodeExact(a, b, c, word, address);
    }

    /// Tabela exata `(a, c) -> instrução`, cobrindo os 6 pares merging (mais os 6 zeroing simétricos via
    /// {@code zeroing}, já resolvido pelo chamador a partir de `b`).
    private Ir64Op decodeExact(int a, int b, int c, int word, long address) {
        boolean zeroing = b == ODD_B_ZEROING;
        Ir64Op.SveFpConvertOddElements.Op op;
        int wideEsz;
        boolean roundToOdd = false;
        boolean bfloat16 = false;
        if (a == 0b00 && c == 0b10) {
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTXNT;
            wideEsz = 3;
            roundToOdd = true;
        } else if (a == 0b10 && c == 0b00) {
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTNT;
            wideEsz = 2;
        } else if (a == 0b10 && c == 0b10) {
            if (!architecture.has(Aarch64Feature.BFLOAT16)) {
                return null; // `BFCVTNT`
            }
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTNT;
            wideEsz = 2;
            bfloat16 = true;
        } else if (a == 0b10 && c == 0b01) {
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTLT;
            wideEsz = 2;
        } else if (a == 0b11 && c == 0b10) {
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTNT;
            wideEsz = 3;
        } else if (a == 0b11 && c == 0b11) {
            op = Ir64Op.SveFpConvertOddElements.Op.FCVTLT;
            wideEsz = 3;
        } else {
            return null;
        }
        int pg = field(word, ODD_PG_SHIFT, PREDICATE_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpConvertOddElements(op, wideEsz, roundToOdd, bfloat16, zeroing, rd, rn, pg, address);
    }

    private Ir64Op decodeFcvtxDsM(int word, long address) {
        if ((word & FCVTX_DS_M_MASK) != FCVTX_DS_M_VALUE) {
            return null;
        }
        int pg = field(word, ODD_PG_SHIFT, PREDICATE_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpUnary(Ir64Op.SveFpUnary.Op.FCVTX, 3, 2, false, rd, rn, pg, address);
    }

    private Ir64Op decodeFlogbM(int word, long address) {
        if ((word & FLOGB_M_MASK) != FLOGB_M_VALUE) {
            return null;
        }
        int esz = field(word, FLOGB_M_ESZ_SHIFT, 0b11);
        int pg = field(word, ODD_PG_SHIFT, PREDICATE_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpLogB(esz, false, rd, rn, pg, address);
    }

    private Ir64Op decodeFlogbZ(int word, long address) {
        if ((word & FLOGB_Z_MASK) != FLOGB_Z_VALUE) {
            return null;
        }
        int esz = field(word, FLOGB_Z_ESZ_SHIFT, 0b11);
        int pg = field(word, ODD_PG_SHIFT, PREDICATE_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpLogB(esz, true, rd, rn, pg, address);
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
