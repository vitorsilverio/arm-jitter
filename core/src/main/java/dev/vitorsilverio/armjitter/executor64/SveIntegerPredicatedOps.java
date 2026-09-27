package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das operações inteiras SVE **com predicado governante** (B17.6): aritmética binária,
/// shifts (imediato, vetor e elemento largo) e unárias.
///
/// Todas são **merging**: o elemento inativo de `Zd` fica byte a byte como estava — exceto nas unárias
/// `_z` (`FEAT_SVE2p2`), que o zeram ({@link Ir64Op.SveIntegerPredicated#zeroing()}). Um elemento está
/// ativo quando o bit do byte MAIS BAIXO dele em `P[pg]` está ligado (`P` guarda um bit por byte). Os
/// auxiliares de elemento vêm de {@link SveIntegerOps} — cada operação vive num lugar só.
///
/// As formas reversas (`SUBR`/`SDIVR`/`ASRR`…) já chegam com `rn`/`rm` trocados pelo decoder; aqui o
/// resultado é sempre `op(Zn, Zm)`. `FABS`/`FNEG` são bit-ops (limpam/invertem o bit de sinal);
/// `FPCR.AH` (FEAT_AFP, onde NaN é preservado) não é modelado no core — pendência nomeada da task.
final class SveIntegerPredicatedOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;

    private SveIntegerPredicatedOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveIntegerPredicated op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        if (isPairwise(op.op())) {
            pairwise(regs, op, elements);
            return false;
        }
        int perDoubleword = Long.BYTES >> esz;
        boolean wide = isWide(op.op());
        long wideAmount = 0L;
        for (int e = 0; e < elements; e++) {
            if (wide && e % perDoubleword == 0) {
                // Lido ANTES de escrever o grupo: `Zm` pode ser o próprio `Zdn`.
                wideAmount = regs.zWord(op.rm(), e / perDoubleword);
            }
            int bit = e << esz;
            boolean active = ((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
            if (!active) {
                if (op.zeroing()) {
                    SveIntegerOps.set(regs, op.rd(), e, esz, 0L);
                }
                continue;
            }
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long result = switch (op.op()) {
                case ASR_IMM, LSR_IMM, LSL_IMM, ASRD, SQSHL_IMM, UQSHL_IMM, SRSHR, URSHR, SQSHLU ->
                        immediateShift(op.op(), n, op.imm(), esz);
                case ASR_WIDE, LSR_WIDE, LSL_WIDE -> shift(op.op(), n, wideAmount, esz);
                case CLS, CLZ, CNT, CNOT, NOT, FABS, FNEG, ABS, NEG, SXTB, UXTB, SXTH, UXTH, SXTW, UXTW, MOVPRFX,
                        SQABS, SQNEG, URECPE, URSQRTE ->
                        unary(op.op(), n, esz);
                default -> binary(op.op(), n, SveIntegerOps.get(regs, op.rm(), e, esz), esz);
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
        return false;
    }

    private static boolean isPairwise(Ir64Op.SveIntegerPredicated.Op op) {
        return switch (op) {
            case ADDP, SMAXP, UMAXP, SMINP, UMINP -> true;
            default -> false;
        };
    }

    /// Pairwise SVE2 (B17.20, `DO_ZPZZ_PAIR` do QEMU): os elementos vizinhos `(2k, 2k+1)` de `Zn` viram o elemento `2k` do
    /// destino e os de `Zm` o `2k+1` — dentro de cada segmento de 128 bits, que tem sempre um número par de elementos.
    /// O predicado governa o elemento de DESTINO; o inativo é preservado. As quatro fontes do par são lidas ANTES de
    /// qualquer escrita (`Zm` e `Zn` podem ser o próprio `Zdn`).
    private static void pairwise(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerPredicated op, int elements) {
        int esz = op.esz();
        for (int e = 0; e < elements; e += 2) {
            long n0 = SveIntegerOps.get(regs, op.rn(), e, esz);
            long n1 = SveIntegerOps.get(regs, op.rn(), e + 1, esz);
            long m0 = SveIntegerOps.get(regs, op.rm(), e, esz);
            long m1 = SveIntegerOps.get(regs, op.rm(), e + 1, esz);
            if (elementActive(regs, op.pg(), e, esz)) {
                SveIntegerOps.set(regs, op.rd(), e, esz, pair(op.op(), n0, n1, esz));
            }
            if (elementActive(regs, op.pg(), e + 1, esz)) {
                SveIntegerOps.set(regs, op.rd(), e + 1, esz, pair(op.op(), m0, m1, esz));
            }
        }
    }

    private static boolean elementActive(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    private static long pair(Ir64Op.SveIntegerPredicated.Op kind, long a, long b, int esz) {
        long sa = SveIntegerOps.signExtend(a, esz);
        long sb = SveIntegerOps.signExtend(b, esz);
        return switch (kind) {
            case ADDP -> a + b;
            case SMAXP -> sa >= sb ? a : b;
            case UMAXP -> Long.compareUnsigned(a, b) >= 0 ? a : b;
            case SMINP -> sa >= sb ? b : a;
            default -> Long.compareUnsigned(a, b) >= 0 ? b : a; // UMINP
        };
    }

    /// `SADALP`/`UADALP`: soma as duas METADES (de `esize/2` bits) de `n`, com ou sem sinal; o chamador acumula em `Zda`.
    private static long halfSum(long n, int esz, boolean signed) {
        int half = SveIntegerOps.elementBits(esz) / 2;
        long mask = (1L << half) - 1L;
        long low = n & mask;
        long high = (n >>> half) & mask;
        if (signed) {
            int shift = Long.SIZE - half;
            low = (low << shift) >> shift;
            high = (high << shift) >> shift;
        }
        return low + high;
    }

    private static boolean isWide(Ir64Op.SveIntegerPredicated.Op op) {
        return op == Ir64Op.SveIntegerPredicated.Op.ASR_WIDE || op == Ir64Op.SveIntegerPredicated.Op.LSR_WIDE
                || op == Ir64Op.SveIntegerPredicated.Op.LSL_WIDE;
    }

    // ── Binárias (inclui os shifts por vetor) ───────────────────────────────────────────────────

    private static long binary(Ir64Op.SveIntegerPredicated.Op kind, long n, long m, int esz) {
        long sn = SveIntegerOps.signExtend(n, esz);
        long sm = SveIntegerOps.signExtend(m, esz);
        int bits = SveIntegerOps.elementBits(esz);
        return switch (kind) {
            case ORR -> n | m;
            case EOR -> n ^ m;
            case AND -> n & m;
            case BIC -> n & ~m;
            case ADD -> n + m;
            case SUB -> n - m;
            case SMAX -> sn >= sm ? n : m;
            case UMAX -> Long.compareUnsigned(n, m) >= 0 ? n : m;
            case SMIN -> sn >= sm ? m : n;
            case UMIN -> Long.compareUnsigned(n, m) >= 0 ? m : n;
            case SABD -> sn >= sm ? sn - sm : sm - sn;
            case UABD -> Long.compareUnsigned(n, m) >= 0 ? n - m : m - n;
            case MUL -> n * m;
            case SMULH -> SveIntegerOps.multiplyHigh(n, m, esz, true);
            case UMULH -> SveIntegerOps.multiplyHigh(n, m, esz, false);
            case SDIV -> sm == 0L ? 0L : sn / sm; // MIN / -1 dá MIN em Java (sem exceção), como o Arm exige
            case UDIV -> m == 0L ? 0L : Long.divideUnsigned(n, m);
            case SADALP -> m + halfSum(n, esz, true);
            case UADALP -> m + halfSum(n, esz, false);
            default -> shift(kind, n, m, esz); // ASR/LSR/LSL por vetor
        };
    }

    // ── Shifts ───────────────────────────────────────────────────────────────────────────────────

    /// `amount` é sem sinal de até 64 bits; um valor com o bit 63 ligado aparece negativo e conta como
    /// "estoura o elemento" (`ASR` preenche com o sinal, `LSR`/`LSL` zeram) — todos os bits contam, não
    /// só os baixos (`DO_ASR`/`DO_LSR`/`DO_LSL` do QEMU).
    private static long shift(Ir64Op.SveIntegerPredicated.Op kind, long value, long amount, int esz) {
        int bits = SveIntegerOps.elementBits(esz);
        boolean overflow = amount < 0 || amount >= bits;
        return switch (kind) {
            case ASR, ASR_WIDE, ASR_IMM ->
                    SveIntegerOps.signExtend(value, esz) >> (overflow ? bits - 1 : (int) amount);
            case LSR, LSR_WIDE, LSR_IMM -> overflow ? 0L : value >>> amount;
            default -> overflow ? 0L : value << amount; // LSL, LSL_WIDE, LSL_IMM
        };
    }

    /// Shifts por imediato: `ASR`/`LSR`/`LSL` (shift por `esize` é válido: `ASR` clampa, `LSR` zera), `ASRD`
    /// (arredonda para zero) e as 5 formas SVE2 (saturantes e com arredondamento), sem efeito em `FPSR.QC`.
    private static long immediateShift(Ir64Op.SveIntegerPredicated.Op kind, long value, long amount, int esz) {
        int bits = SveIntegerOps.elementBits(esz);
        long signed = SveIntegerOps.signExtend(value, esz);
        long unsignedMax = SveIntegerOps.elementMask(esz);
        return switch (kind) {
            case ASRD -> amount >= bits ? 0L : (signed + (signed < 0 ? (1L << amount) - 1L : 0L)) >> amount;
            case SRSHR -> amount >= Long.SIZE ? 0L : (signed >> amount) + ((signed >> (amount - 1)) & 1L);
            case URSHR -> amount >= Long.SIZE
                    ? value >>> (Long.SIZE - 1)
                    : (value >>> amount) + ((value >>> (amount - 1)) & 1L);
            case SQSHL_IMM -> {
                long max = esz == ESZ_DOUBLEWORD ? Long.MAX_VALUE : (1L << (bits - 1)) - 1L;
                long min = -max - 1L;
                yield signed > (max >> amount) ? max : signed < (min >> amount) ? min : signed << amount;
            }
            case UQSHL_IMM -> Long.compareUnsigned(value, unsignedMax >>> amount) > 0 ? unsignedMax : value << amount;
            case SQSHLU -> signed < 0L
                    ? 0L
                    : Long.compareUnsigned(signed, unsignedMax >>> amount) > 0 ? unsignedMax : signed << amount;
            default -> shift(kind, value, amount, esz); // ASR_IMM/LSR_IMM/LSL_IMM
        };
    }

    // ── Unárias ──────────────────────────────────────────────────────────────────────────────────

    private static long unary(Ir64Op.SveIntegerPredicated.Op kind, long n, int esz) {
        int bits = SveIntegerOps.elementBits(esz);
        long signed = SveIntegerOps.signExtend(n, esz);
        long signBit = 1L << (bits - 1);
        return switch (kind) {
            // Bits iguais ao de sinal logo abaixo dele: zeros à frente de (n ou ~n) no elemento, menos o próprio sinal.
            case CLS -> Long.numberOfLeadingZeros((signed < 0 ? ~signed : signed) & SveIntegerOps.elementMask(esz))
                    - (Long.SIZE - bits) - 1;
            case CLZ -> Long.numberOfLeadingZeros(n) - (Long.SIZE - bits);
            case CNT -> Long.bitCount(n);
            case CNOT -> n == 0L ? 1L : 0L;
            case NOT -> ~n;
            case FABS -> n & ~signBit;
            case FNEG -> n ^ signBit;
            case ABS -> signed < 0L ? -signed : signed;
            case NEG -> -n;
            case SQABS -> signed == Long.MIN_VALUE >> (Long.SIZE - bits) ? Long.MAX_VALUE >>> (Long.SIZE - bits) : Math.abs(signed);
            case SQNEG -> signed == Long.MIN_VALUE >> (Long.SIZE - bits) ? Long.MAX_VALUE >>> (Long.SIZE - bits) : -signed;
            case URECPE -> AdvSimdLanes.unsignedRecipEstimate32(n);
            case URSQRTE -> AdvSimdLanes.unsignedRSqrtEstimate32(n);
            case SXTB -> SveIntegerOps.signExtend(n, ESZ_BYTE);
            case UXTB -> n & SveIntegerOps.elementMask(ESZ_BYTE);
            case SXTH -> SveIntegerOps.signExtend(n, ESZ_HALF);
            case UXTH -> n & SveIntegerOps.elementMask(ESZ_HALF);
            case SXTW -> SveIntegerOps.signExtend(n, ESZ_WORD);
            case UXTW -> n & SveIntegerOps.elementMask(ESZ_WORD);
            default -> n; // MOVPRFX: cópia predicada de Zn
        };
    }
}
