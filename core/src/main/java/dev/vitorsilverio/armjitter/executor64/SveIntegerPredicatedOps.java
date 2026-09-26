package dev.vitorsilverio.armjitter.executor64;

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
                case CLS, CLZ, CNT, CNOT, NOT, FABS, FNEG, ABS, NEG, SXTB, UXTB, SXTH, UXTH, SXTW, UXTW, MOVPRFX ->
                        unary(op.op(), n, esz);
                default -> binary(op.op(), n, SveIntegerOps.get(regs, op.rm(), e, esz), esz);
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
        return false;
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
            case SMULH -> esz == ESZ_DOUBLEWORD ? Math.multiplyHigh(sn, sm) : (sn * sm) >> bits;
            case UMULH -> esz == ESZ_DOUBLEWORD ? Math.unsignedMultiplyHigh(n, m) : (n * m) >>> bits;
            case SDIV -> sm == 0L ? 0L : sn / sm; // MIN / -1 dá MIN em Java (sem exceção), como o Arm exige
            case UDIV -> m == 0L ? 0L : Long.divideUnsigned(n, m);
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
