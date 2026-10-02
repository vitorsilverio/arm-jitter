package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;

/// Semântica de `ADDHA`/`ADDVA` e do produto externo acumulado (`FMOPA`/`BFMOPA`/`SMOPA`/`SUMOPA`/`USMOPA`/`UMOPA`/
/// `BMOPA`, B18.5): `ZA<tile>[i][j]` é atualizado a partir de `Zn[i]` e `Zm[j]` onde `Pn[i]` E `Pm[j]` permitem.
/// Transcrito de `sme_helper.c`/`fp8_helper.c` do QEMU (`do_fmopa_*`, `do_imopa_*`, `f16_dotadd`, `bfdotadd*`).
///
/// - **O tile é QUADRADO e inteiro**: `SVL/esz` linhas × `SVL/esz` colunas, `esz` = elemento do ACUMULADOR; não há
///   slice (`get_tile`, não `get_tile_rowcol`).
/// - **O predicado de um elemento são os `1 << esz` bits `P[i << esz ..]`** (um por BYTE do vetor): `FMOPA_s`/`_d`/`_h`
///   e `ADDHA`/`ADDVA` só olham o bit 0 do grupo; os produtos inteiros e `fp8` mascaram a origem POR BYTE/HALFWORD
///   (`pa & pb`); `FMOPA_w_h`/`BFMOPA_w` olham os bits 0 e 2 (as duas metades `binary16`).
/// - **Widening é SOMATÓRIO de produtos** (4 em `SMOPA_s`/`_d`, 2 em `SMOPA2_s`/`FMOPA_w_h`), nunca um produto só.
/// - **FP: sem estado paralelo e sem `FPSR`.** O `FPCR` é o do core; `DN` é sempre `1` e as flags NÃO acumulam em
///   `FPSR` (`FPST_ZA*` do QEMU) — {@link SveFloat.Env#ofZa}. **Não modelados** (pendências nomeadas, como no resto de
///   {@link SveFloat}): `FPCR.AH` (só muda o sinal de NaN — irrelevante com `DN = 1` — e o modo de denormais) e a
///   checagem de acesso a `FPMR` das formas `fp8`.
public final class SmeOuterProductOps {
    private static final int ESZ_BFLOAT16 = SveFloat.ESZ_BFLOAT16;
    private static final int ESZ_HALF = SveFloat.ESZ_HALF;
    private static final int ESZ_SINGLE = SveFloat.ESZ_SINGLE;
    private static final int ESZ_DOUBLE = SveFloat.ESZ_DOUBLE;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    /// Sinal das duas metades `binary16` de um elemento de 32 bits (`FMOPS`).
    private static final long PAIR_SIGNS = 0x8000_8000L;
    private static final long LOW_HALF_MASK = 0xFFFFL;
    private static final long HIGH_HALF_MASK = 0xFFFF_0000L;
    private static final int LOW_HALF_PREDICATE_BIT = 1;
    private static final int HIGH_HALF_PREDICATE_BIT = 1 << 2;
    private static final int BYTE_MASK = 0xFF;
    private static final int HALF_BITS = Short.SIZE;
    private static final long UINT32_MASK = 0xFFFF_FFFFL;
    private static final int BYTES_PER_WORD_ELEMENT = 4;
    private static final int HALVES_PER_WORD_ELEMENT = 2;
    private static final int HALVES_PER_DOUBLE_ELEMENT = 4;

    private SmeOuterProductOps() {
    }

    /// Formatos e ambientes FP/`fp8` de UMA instrução (o `FPCR`/`FPMR` são lidos uma única vez).
    static final class Context {
        SveFloat.Env accumulate;
        SveFloat.Env source;
        SveFloat.Env roundedByHalf;
        SveFloat.Env nonExtended;
        boolean extended;
        boolean nE4m3;
        boolean mE4m3;
        boolean osm;
        int lscale;
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    public static boolean execute(Aarch64Core core, SmeOp64.OuterProduct op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        SmeOp64.OuterProduct.Op kind = op.op();
        int esz = kind.accumulatorEsz();
        int elements = core.streamingVectorLengthBytes() >>> esz;
        boolean addVector = kind == SmeOp64.OuterProduct.Op.ADDHA_S || kind == SmeOp64.OuterProduct.Op.ADDVA_S
                || kind == SmeOp64.OuterProduct.Op.ADDHA_D || kind == SmeOp64.OuterProduct.Op.ADDVA_D;
        int columnSource = addVector ? op.zn() : op.zm();
        Context context = contextFor(core, kind);
        for (int row = 0; row < elements; row++) {
            long n = SvePredicateOps.elementOf(regs, op.zn(), row, esz);
            int pa = predicateGroup(regs, op.pn(), row, esz);
            int zaRow = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, row);
            for (int col = 0; col < elements; col++) {
                long m = SvePredicateOps.elementOf(regs, columnSource, col, esz);
                int pb = predicateGroup(regs, op.pm(), col, esz);
                long accumulator = SmeMovaOps.zaElement(matrix, zaRow, col << esz, esz);
                long result = combine(kind, context, op.subtract(), n, m, accumulator, pa, pb);
                SmeMovaOps.setZaElement(matrix, zaRow, col << esz, esz, result);
            }
        }
        return false;
    }

    static Context contextFor(Aarch64Core core, SmeOp64.OuterProduct.Op kind) {
        Context context = new Context();
        switch (kind) {
            case FMOPA_W_H -> {
                context.source = SveFloat.Env.ofZa(core, ESZ_HALF);
                context.roundedByHalf = SveFloat.Env.ofZaSingleWithHalfFlush(core);
                context.accumulate = SveFloat.Env.ofZa(core, ESZ_SINGLE);
            }
            case BFMOPA_W -> {
                context.extended = SveFloat.Env.extendedBFloat16(core);
                context.source = SveFloat.Env.ofZa(core, ESZ_BFLOAT16);
                context.accumulate = SveFloat.Env.ofZa(core, ESZ_SINGLE);
                context.nonExtended = SveFloat.Env.ofBFloat16NonExtended();
            }
            case FMOPA_SB, FMOPA_HB -> {
                context.nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
                context.mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
                context.osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
                context.lscale = kind == SmeOp64.OuterProduct.Op.FMOPA_SB ? core.fp8MultiplyDownscale()
                        : core.fp8WidenScale();
            }
            case FMOPA_H -> context.accumulate = SveFloat.Env.ofZa(core, ESZ_HALF);
            case BFMOPA -> context.accumulate = SveFloat.Env.ofZa(core, ESZ_BFLOAT16);
            case FMOPA_S -> context.accumulate = SveFloat.Env.ofZa(core, ESZ_SINGLE);
            case FMOPA_D -> context.accumulate = SveFloat.Env.ofZa(core, ESZ_DOUBLE);
            default -> {
                // Aritmética inteira / ADDHA / ADDVA / BMOPA: sem ambiente.
            }
        }
        return context;
    }

    /// Todos os bits de predicado ligados — as formas `MOP4`/`TMOP` (B18.5b) não têm predicado.
    private static final int ALL_ACTIVE = -1;

    /// Mesma aritmética de {@link #combine}, para as formas SEM predicado (`MOP4`/`TMOP`, B18.5b): reaproveita o
    /// mesmo núcleo (`f16_dotadd`, `bfdotadd*`, `fp8`, produtos inteiros) em vez de duplicá-lo.
    static long combineUnpredicated(SmeOp64.OuterProduct.Op kind, Context context, boolean subtract, long n,
            long m, long accumulator) {
        return combine(kind, context, subtract, n, m, accumulator, ALL_ACTIVE, ALL_ACTIVE);
    }

    /// Novo valor do elemento do acumulador. `pa`/`pb` são os grupos de bits de predicado do elemento de linha/coluna.
    private static long combine(SmeOp64.OuterProduct.Op kind, Context context, boolean subtract, long n, long m,
            long accumulator, int pa, int pb) {
        boolean active = ((pa & pb) & 1) != 0;
        return switch (kind) {
            case ADDHA_S, ADDHA_D -> active ? wrap(accumulator + m, kind.accumulatorEsz()) : accumulator;
            case ADDVA_S, ADDVA_D -> active ? wrap(accumulator + n, kind.accumulatorEsz()) : accumulator;
            case FMOPA_H, BFMOPA -> active ? fusedMultiplyAdd(context.accumulate, n, m, accumulator, subtract, ESZ_HALF)
                    : accumulator;
            case FMOPA_S -> active ? fusedMultiplyAdd(context.accumulate, n, m, accumulator, subtract, ESZ_SINGLE)
                    : accumulator;
            case FMOPA_D -> active ? fusedMultiplyAdd(context.accumulate, n, m, accumulator, subtract, ESZ_DOUBLE)
                    : accumulator;
            case FMOPA_W_H -> halfPairs(context, subtract, n, m, accumulator, pa, pb);
            case BFMOPA_W -> bfloatPairs(context, subtract, n, m, accumulator, pa, pb);
            case FMOPA_SB -> fp8Group(context, n, m, accumulator, pa, pb, BYTES_PER_WORD_ELEMENT, true);
            case FMOPA_HB -> fp8Group(context, n, m, accumulator, pa, pb, HALVES_PER_WORD_ELEMENT, false);
            case SMOPA_S -> bytes4(n, m, accumulator, pa & pb, true, true, subtract);
            case SUMOPA_S -> bytes4(n, m, accumulator, pa & pb, true, false, subtract);
            case USMOPA_S -> bytes4(n, m, accumulator, pa & pb, false, true, subtract);
            case UMOPA_S -> bytes4(n, m, accumulator, pa & pb, false, false, subtract);
            case SMOPA_D -> halves4(n, m, accumulator, pa & pb, true, true, subtract);
            case SUMOPA_D -> halves4(n, m, accumulator, pa & pb, true, false, subtract);
            case USMOPA_D -> halves4(n, m, accumulator, pa & pb, false, true, subtract);
            case UMOPA_D -> halves4(n, m, accumulator, pa & pb, false, false, subtract);
            case BMOPA -> bitProduct(n, m, accumulator, pa & pb, subtract);
            case SMOPA2_S -> halves2(n, m, accumulator, pa & pb, true, subtract);
            case UMOPA2_S -> halves2(n, m, accumulator, pa & pb, false, subtract);
        };
    }

    // ── ponto flutuante ──────────────────────────────────────────────────────────────────────────

    /// `FMOPA_h`/`BFMOPA`/`FMOPA_s`/`FMOPA_d`: `acc + (±n) × m` com UM arredondamento. `FMOPS` troca o sinal de `n`
    /// (`n ^ negx` do QEMU).
    private static long fusedMultiplyAdd(SveFloat.Env env, long n, long m, long accumulator, boolean subtract,
            int esz) {
        long signBit = 1L << ((Byte.SIZE << esz) - 1);
        return SveFloat.fusedMultiplyAdd(accumulator, subtract ? n ^ signBit : n, m, 0, env);
    }

    /// `f16mop_adj_pair`: o sinal é trocado ANTES de zerar a metade inativa (assim uma metade inativa vira `+0`).
    private static long adjustPair(long pair, int predicate, long negate) {
        long adjusted = pair ^ negate;
        if ((predicate & LOW_HALF_PREDICATE_BIT) == 0) {
            adjusted &= HIGH_HALF_MASK;
        }
        if ((predicate & HIGH_HALF_PREDICATE_BIT) == 0) {
            adjusted &= LOW_HALF_MASK;
        }
        return adjusted;
    }

    private static boolean pairActive(int pa, int pb) {
        return ((pa & pb) & (LOW_HALF_PREDICATE_BIT | HIGH_HALF_PREDICATE_BIT)) != 0;
    }

    /// `FMOPA_w_h` (`do_fmopa_w_h` + `f16_dotadd`): soma de dois produtos `binary16` com um arredondamento para
    /// `binary32` (honrando `FZ16`), e DEPOIS a acumulação — NÃO fundida — sob `FZ`.
    private static long halfPairs(Context context, boolean subtract, long n, long m, long accumulator, int pa,
            int pb) {
        if (!pairActive(pa, pb)) {
            return accumulator;
        }
        long nn = adjustPair(n, pa, subtract ? PAIR_SIGNS : 0L);
        long mm = adjustPair(m, pb, 0L);
        long product = SveFloat.fusedDotProduct2(nn & LOW_HALF_MASK, mm & LOW_HALF_MASK, nn >>> HALF_BITS,
                mm >>> HALF_BITS, context.source, context.roundedByHalf);
        return SveFloat.add(accumulator, product, false, context.accumulate);
    }

    /// `BFMOPA_w` (`do_bfmopa_w`): com `FPCR.EBF = 1`, soma de dois produtos com um arredondamento (`bfdotadd_ebf`);
    /// com `EBF = 0`, produtos e somas arredondados um a um, em arredondamento ímpar (`bfdotadd`).
    private static long bfloatPairs(Context context, boolean subtract, long n, long m, long accumulator, int pa,
            int pb) {
        if (!pairActive(pa, pb)) {
            return accumulator;
        }
        long nn = adjustPair(n, pa, subtract ? PAIR_SIGNS : 0L);
        long mm = adjustPair(m, pb, 0L);
        if (context.extended) {
            long product = SveFloat.fusedDotProduct2(nn & LOW_HALF_MASK, mm & LOW_HALF_MASK, nn >>> HALF_BITS,
                    mm >>> HALF_BITS, context.source, context.accumulate);
            return SveFloat.add(accumulator, product, false, context.accumulate);
        }
        SveFloat.Env env = context.nonExtended;
        long low = SveFloat.multiply((nn & LOW_HALF_MASK) << HALF_BITS, (mm & LOW_HALF_MASK) << HALF_BITS, env);
        long high = SveFloat.multiply(nn & HIGH_HALF_MASK, mm & HIGH_HALF_MASK, env);
        return SveFloat.add(accumulator, SveFloat.add(low, high, false, env), false, env);
    }

    /// `FMOPA_sb`/`FMOPA_hb`: a origem é mascarada POR BYTE pelo predicado (`expand_pred_b`); o produto escalar é o
    /// mesmo núcleo do `FDOT` `fp8` de SVE/AdvSIMD.
    private static long fp8Group(Context context, long n, long m, long accumulator, int pa, int pb, int lanes,
            boolean wideDestination) {
        int groupMask = (1 << lanes) - 1;
        if (((pa & pb) & groupMask) == 0) {
            return accumulator;
        }
        return AdvSimdLanes.fp8DotProduct(n & expandByteMask(pa & groupMask), m & expandByteMask(pb & groupMask),
                context.nE4m3, context.mE4m3, lanes, context.lscale, context.osm, accumulator, wideDestination);
    }

    /// `expand_pred_b`: cada bit `i` do predicado liga o byte `i`.
    private static long expandByteMask(int predicate) {
        long mask = 0L;
        for (int i = 0; i < Integer.BYTES; i++) {
            if (((predicate >>> i) & 1) != 0) {
                mask |= (long) BYTE_MASK << (i * Byte.SIZE);
            }
        }
        return mask;
    }

    // ── inteiros ─────────────────────────────────────────────────────────────────────────────────

    private static long lane(long value, int index, int bits, boolean signed) {
        long raw = (value >>> (index * bits)) & ((1L << bits) - 1L);
        if (!signed) {
            return raw;
        }
        int shift = Long.SIZE - bits;
        return (raw << shift) >> shift;
    }

    /// `DEF_IMOP_8x4_32`: 4 produtos de bytes somados; a origem `n` é mascarada pelo predicado (byte inativo = 0).
    private static long bytes4(long n, long m, long accumulator, int predicate, boolean nSigned, boolean mSigned,
            boolean subtract) {
        int sum = 0;
        for (int i = 0; i < BYTES_PER_WORD_ELEMENT; i++) {
            if (((predicate >>> i) & 1) != 0) {
                sum += (int) (lane(n, i, Byte.SIZE, nSigned) * lane(m, i, Byte.SIZE, mSigned));
            }
        }
        int result = subtract ? (int) accumulator - sum : (int) accumulator + sum;
        return result & UINT32_MASK;
    }

    /// `DEF_IMOP_16x4_64`: 4 produtos de halfwords em 64 bits; o halfword `i` vale o bit `2i` do predicado.
    private static long halves4(long n, long m, long accumulator, int predicate, boolean nSigned, boolean mSigned,
            boolean subtract) {
        long sum = 0L;
        for (int i = 0; i < HALVES_PER_DOUBLE_ELEMENT; i++) {
            if (((predicate >>> (2 * i)) & 1) != 0) {
                sum += lane(n, i, HALF_BITS, nSigned) * lane(m, i, HALF_BITS, mSigned);
            }
        }
        return subtract ? accumulator - sum : accumulator + sum;
    }

    /// `DEF_IMOP_16x2_32` (`SMOPA2`/`UMOPA2`): 2 produtos de halfwords em 32 bits.
    private static long halves2(long n, long m, long accumulator, int predicate, boolean signed, boolean subtract) {
        int sum = 0;
        for (int i = 0; i < HALVES_PER_WORD_ELEMENT; i++) {
            if (((predicate >>> (2 * i)) & 1) != 0) {
                sum += (int) (lane(n, i, HALF_BITS, signed) * lane(m, i, HALF_BITS, signed));
            }
        }
        int result = subtract ? (int) accumulator - sum : (int) accumulator + sum;
        return result & UINT32_MASK;
    }

    /// `bmopa_s`: `popcount(~(n ^ m))` (bits IGUAIS), negado em `BMOPS`, e zerado quando o predicado é falso.
    private static long bitProduct(long n, long m, long accumulator, int predicate, boolean subtract) {
        int sum = Integer.bitCount(~((int) n ^ (int) m));
        if (subtract) {
            sum = -sum;
        }
        if ((predicate & 1) == 0) {
            sum = 0;
        }
        return ((int) accumulator + sum) & UINT32_MASK;
    }

    // ── auxiliares ───────────────────────────────────────────────────────────────────────────────

    private static long wrap(long value, int esz) {
        return esz == ESZ_DOUBLE ? value : value & ((1L << (Byte.SIZE << esz)) - 1L);
    }

    /// Os `1 << esz` bits de predicado do elemento `index` (um por byte do vetor).
    private static int predicateGroup(Aarch64ScalableRegisters regs, int pg, int index, int esz) {
        int bit = index << esz;
        long word = regs.pWord(pg, bit >>> WORD_INDEX_SHIFT);
        return (int) ((word >>> (bit & WORD_BIT_MASK)) & ((1L << (1 << esz)) - 1L));
    }
}
