package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.math.BigInteger;

/// Semântica por elemento das 28 operações SVE2 predicadas da B17.21a: shift por vetor saturante/arredondado
/// (`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL`), halving (`SHADD`/`SRHADD`/`SHSUB` e as sem sinal) e saturating
/// add/sub (`SQADD`/`UQADD`/`SQSUB`/`UQSUB`/`SUQADD`/`USQADD`). As formas reversas chegam com os operandos já trocados
/// pelo decoder, então tudo aqui é `op(n, m)`. SVE não tem `FPSR.QC`: a saturação só limita o valor.
final class Sve2VectorOps {
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int LAST_BIT = Long.SIZE - 1;

    private Sve2VectorOps() {
    }

    static long apply(Ir64Op.SveIntegerPredicated.Op op, long n, long m, int esz) {
        return switch (op) {
            case SRSHL -> shift(n, m, esz, true, true, false);
            case URSHL -> shift(n, m, esz, false, true, false);
            case SQSHL_VECTOR -> shift(n, m, esz, true, false, true);
            case UQSHL_VECTOR -> shift(n, m, esz, false, false, true);
            case SQRSHL -> shift(n, m, esz, true, true, true);
            case UQRSHL -> shift(n, m, esz, false, true, true);
            case SHADD -> halve(SveIntegerOps.signExtend(n, esz), SveIntegerOps.signExtend(m, esz), true, Halving.ADD);
            case UHADD -> halve(n, m, false, Halving.ADD);
            case SRHADD -> halve(SveIntegerOps.signExtend(n, esz), SveIntegerOps.signExtend(m, esz), true, Halving.ROUND);
            case URHADD -> halve(n, m, false, Halving.ROUND);
            case SHSUB -> halve(SveIntegerOps.signExtend(n, esz), SveIntegerOps.signExtend(m, esz), true, Halving.SUB);
            case UHSUB -> halve(n, m, false, Halving.SUB);
            case SQADD -> SveIntegerOps.saturateSigned(n, m, esz, false);
            case UQADD -> SveIntegerOps.saturateUnsigned(n, m, esz, false);
            case SQSUB -> SveIntegerOps.saturateSigned(n, m, esz, true);
            case UQSUB -> SveIntegerOps.saturateUnsigned(n, m, esz, true);
            case SUQADD -> signedPlusUnsigned(n, m, esz);
            default -> unsignedPlusSigned(n, m, esz); // USQADD
        };
    }

    // ── Shift por vetor ──────────────────────────────────────────────────────────────────────────

    /// A quantidade é o BYTE baixo do elemento de `Zm`, com sinal (`(int8_t)m` do QEMU), em todos os tamanhos: positivo
    /// desloca à esquerda (saturando se `saturate`), negativo à direita (com arredondamento se `round`).
    private static long shift(long value, long amountElement, int esz, boolean signed, boolean round,
            boolean saturate) {
        int bits = SveIntegerOps.elementBits(esz);
        int amount = (byte) amountElement;
        long v = signed ? SveIntegerOps.signExtend(value, esz) : value & SveIntegerOps.elementMask(esz);
        return amount < 0 ? shiftRight(v, -amount, bits, signed, round) : shiftLeft(v, amount, esz, signed, saturate);
    }

    /// `k` é 1 a 128. Arredondar = somar meio e truncar; deslocar por mais que o elemento dá 0 (ou o sinal, sem arredondar).
    private static long shiftRight(long v, int k, int bits, boolean signed, boolean round) {
        if (round) {
            if (k > bits) {
                return 0L;
            }
            long t = signed ? v >> (k - 1) : v >>> (k - 1);
            return (signed ? t >> 1 : t >>> 1) + (t & 1L);
        }
        if (signed) {
            return v >> Math.min(k, LAST_BIT);
        }
        return k >= bits ? 0L : v >>> k;
    }

    private static long shiftLeft(long v, int amount, int esz, boolean signed, boolean saturate) {
        int bits = SveIntegerOps.elementBits(esz);
        long saturated = signed ? (v < 0L ? minSigned(esz) : maxSigned(esz)) : SveIntegerOps.elementMask(esz);
        if (amount >= bits) {
            return !saturate || v == 0L ? 0L : saturated;
        }
        long shifted = v << amount;
        if (!saturate) {
            return shifted;
        }
        boolean fits = signed
                ? (shifted >> amount) == v && shifted == SveIntegerOps.signExtend(shifted, esz)
                : (shifted >>> amount) == v && (shifted & SveIntegerOps.elementMask(esz)) == shifted;
        return fits ? shifted : saturated;
    }

    private static long maxSigned(int esz) {
        return SveIntegerOps.elementMask(esz) >>> 1;
    }

    private static long minSigned(int esz) {
        return ~maxSigned(esz);
    }

    // ── Halving ─────────────────────────────────────────────────────────────────────────────────

    private enum Halving { ADD, ROUND, SUB }

    /// `(a op b) >> 1` sem estourar 64 bits: `a` e `b` chegam estendidos (com ou sem sinal) e a metade é o piso da média.
    /// `a = 2*a1 + a0`, `b = 2*b1 + b0` ⇒ soma = `a1 + b1 + (a0 & b0)`, arredondada = `a1 + b1 + (a0 | b0)`,
    /// diferença = `a1 - b1 - (~a0 & b0)`.
    private static long halve(long a, long b, boolean signed, Halving kind) {
        long ah = signed ? a >> 1 : a >>> 1;
        long bh = signed ? b >> 1 : b >>> 1;
        return switch (kind) {
            case ADD -> ah + bh + (a & b & 1L);
            case ROUND -> ah + bh + ((a | b) & 1L);
            case SUB -> ah - bh - (~a & b & 1L);
        };
    }

    // ── Saturating de sinais mistos ─────────────────────────────────────────────────────────────

    /// `SUQADD`: `Zdn` COM sinal + `Zm` SEM sinal, saturado ao intervalo com sinal.
    private static long signedPlusUnsigned(long n, long m, int esz) {
        long a = SveIntegerOps.signExtend(n, esz);
        long b = m & SveIntegerOps.elementMask(esz);
        if (esz == ESZ_DOUBLEWORD) {
            BigInteger sum = BigInteger.valueOf(a).add(unsignedBig(b));
            return sum.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : sum.longValue();
        }
        return Math.min(a + b, maxSigned(esz));
    }

    /// `USQADD`: `Zdn` SEM sinal + `Zm` COM sinal, saturado a `[0, máximo sem sinal]`.
    private static long unsignedPlusSigned(long n, long m, int esz) {
        long a = n & SveIntegerOps.elementMask(esz);
        long b = SveIntegerOps.signExtend(m, esz);
        if (esz == ESZ_DOUBLEWORD) {
            BigInteger sum = unsignedBig(a).add(BigInteger.valueOf(b));
            if (sum.signum() < 0) {
                return 0L;
            }
            return sum.bitLength() > Long.SIZE ? -1L : sum.longValue();
        }
        return Math.max(0L, Math.min(a + b, SveIntegerOps.elementMask(esz)));
    }

    private static BigInteger unsignedBig(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }
}
