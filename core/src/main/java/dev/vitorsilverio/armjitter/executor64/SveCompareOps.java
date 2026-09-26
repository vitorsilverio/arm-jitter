package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das comparações SVE da B17.9: as que produzem predicado a partir de vetores/imediato
/// ({@link Ir64Op.SveCompare}) e as de escalares ({@link Ir64Op.SveScalarCompare}: `WHILE*`, `CTERM`).
///
/// **Toda comparação seta `NZCV`** (não existe forma sem `S`) pelo MESMO `PredTest` da B17.4
/// ({@link SvePredicateOps#predTest}) — uma segunda fórmula de flags é a classe de bug que o G1 existe para pegar. O
/// resultado só liga o bit do byte mais baixo de cada elemento, e só onde o predicado governante o liga.
///
/// As fórmulas são transcrições do `sve_helper.c`/`translate-sve.c` do QEMU (revisão fixada em
/// `gerar-cobertura-isa.sh`): `DO_CMP_PPZZ/PPZW/PPZI`, `do_WHILE`, `trans_WHILE_ptr`, `trans_CTERM`,
/// `do_whilel`/`do_whileg` e `pred_count_test`. Em particular:
///
/// - na forma LARGA, `EQ`/`NE` comparam o elemento estendido COM sinal contra os 64 bits de `Zm` (o helper usa `int8_t`
///   contra `uint64_t`), enquanto `HS`/`HI`/`LO`/`LS` são sem sinal e `GE`/`GT`/`LT`/`LE` com sinal;
/// - `WHILE*` colapsa a condição numa CONTAGEM de iterações verdadeiras; `WHILE_gt` gera o predicado de trás para a frente
///   e tem `PredCountTest` invertido; o caso `op1 == maxval` (a soma de `eq` estouraria) vira predicado todo verdadeiro;
/// - `CTERM` só mexe em `N` e `V` (`N = cond`, `V = !cond && !C`); `Z` e `C` ficam como estão.
final class SveCompareOps {
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int LOG2_BYTES_PER_DOUBLEWORD = 3;
    private static final long LOW_32_BITS = 0xFFFF_FFFFL;
    private static final int PAIR_SECOND_OFFSET = 1;

    private SveCompareOps() {
    }

    // ── Comparação que produz predicado ─────────────────────────────────────────────────────────

    /// Executa `CMP<cond>` (vetor, largo ou imediato). `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveCompare op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int bits = SvePredicateOps.predicateBits(core);
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        long[] pg = SvePredicateOps.read(regs, op.pg());
        long[] result = new long[pg.length];
        boolean signed = switch (op.cond()) {
            case EQ, NE, GE, GT, LT, LE -> true;
            case HS, HI, LO, LS -> false;
        };
        // Lê todos os operandos ANTES de escrever: `Pd` não é `Zn`/`Zm`, mas o resultado só vale depois da varredura.
        for (int e = 0; e < elements; e++) {
            int index = e << esz;
            if (!SvePredicateOps.bit(pg, index)) {
                continue;
            }
            long nn = SvePredicateOps.elementOf(regs, op.rn(), e, esz);
            long mm = switch (op.form()) {
                case VECTOR -> SvePredicateOps.elementOf(regs, op.rm(), e, esz);
                case WIDE -> SvePredicateOps.elementOf(regs, op.rm(), index >> LOG2_BYTES_PER_DOUBLEWORD,
                        ESZ_DOUBLEWORD);
                case IMMEDIATE -> op.imm();
            };
            // Só o elemento (e `Zm` na forma vetorial) precisam ser estendidos: o operando largo e o imediato já são de 64 bits.
            long left = signed ? signExtend(nn, esz) : nn;
            long right = op.form() == Ir64Op.SveCompare.Form.VECTOR && signed ? signExtend(mm, esz) : mm;
            if (compare(op.cond(), left, right)) {
                SvePredicateOps.setBit(result, index, true);
            }
        }
        SvePredicateOps.write(regs, op.pd(), result);
        SvePredicateOps.predTest(core, pg, result, esz, bits);
        return false;
    }

    private static boolean compare(Ir64Op.SveCompare.Cond cond, long left, long right) {
        return switch (cond) {
            case EQ -> left == right;
            case NE -> left != right;
            case GE -> Long.compare(left, right) >= 0;
            case GT -> Long.compare(left, right) > 0;
            case LT -> Long.compare(left, right) < 0;
            case LE -> Long.compare(left, right) <= 0;
            case HS -> Long.compareUnsigned(left, right) >= 0;
            case HI -> Long.compareUnsigned(left, right) > 0;
            case LO -> Long.compareUnsigned(left, right) < 0;
            case LS -> Long.compareUnsigned(left, right) <= 0;
        };
    }

    private static long signExtend(long value, int esz) {
        int shift = Long.SIZE - (Byte.SIZE << esz);
        return (value << shift) >> shift;
    }

    // ── WHILE* / CTERM ──────────────────────────────────────────────────────────────────────────

    /// Executa `WHILE*`/`CTERM`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveScalarCompare op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        switch (op.op()) {
            case CTERM -> conditionallyTerminate(core, op);
            case WHILE_PTR -> whilePointers(core, op);
            default -> whileCount(core, op);
        }
        return false;
    }

    /// `CTERMEQ`/`CTERMNE`: `N = cond`, `V = !cond && !C`; `Z` e `C` inalterados (`trans_CTERM`).
    private static void conditionallyTerminate(Aarch64Core core, Ir64Op.SveScalarCompare op) {
        long left = op.sf() ? core.x(op.rn()) : core.x(op.rn()) & LOW_32_BITS;
        long right = op.sf() ? core.x(op.rm()) : core.x(op.rm()) & LOW_32_BITS;
        boolean condition = (left == right) != op.flag();
        var pstate = core.pstate();
        pstate.setNzcv(condition, pstate.zero(), pstate.carry(), !condition && !pstate.carry());
    }

    /// `WHILE<cond>`: quantos elementos, a partir do 0 (`LT`) ou do último (`GT`), satisfazem a condição — limitado
    /// ao que cabe no(s) predicado(s). As formas `_PAIR` escrevem `Pd` e `Pd+1` e contam sobre `2 * VL`.
    private static void whileCount(Aarch64Core core, Ir64Op.SveScalarCompare op) {
        boolean less = op.op() == Ir64Op.SveScalarCompare.Op.WHILE_LT
                || op.op() == Ir64Op.SveScalarCompare.Op.WHILE_LT_PAIR;
        boolean pair = op.op() == Ir64Op.SveScalarCompare.Op.WHILE_LT_PAIR
                || op.op() == Ir64Op.SveScalarCompare.Op.WHILE_GT_PAIR;
        // `flag` é o bit `eq` cru; nas formas `GT` o sentido é invertido (`eq = 0` é `GE`/`HS`).
        boolean orEqual = op.flag() == less;
        long left = core.x(op.rn());
        long right = core.x(op.rm());
        if (!op.sf()) {
            left = op.unsigned() ? left & LOW_32_BITS : (int) left;
            right = op.unsigned() ? right & LOW_32_BITS : (int) right;
        }
        long difference;
        long maxValue;
        boolean holds;
        if (less) {
            difference = right - left;
            if (op.unsigned()) {
                maxValue = op.sf() ? -1L : LOW_32_BITS;
                int order = Long.compareUnsigned(left, right);
                holds = orEqual ? order <= 0 : order < 0;
            } else {
                maxValue = op.sf() ? Long.MAX_VALUE : Integer.MAX_VALUE;
                holds = orEqual ? left <= right : left < right;
            }
        } else {
            difference = left - right;
            if (op.unsigned()) {
                maxValue = 0L;
                int order = Long.compareUnsigned(left, right);
                holds = orEqual ? order >= 0 : order > 0;
            } else {
                maxValue = op.sf() ? Long.MIN_VALUE : Integer.MIN_VALUE;
                holds = orEqual ? left >= right : left > right;
            }
        }
        int vectorBytes = core.vectorLengthBytes();
        long maxElements = ((long) vectorBytes << (pair ? 1 : 0)) >> op.esz();
        if (orEqual) {
            difference += 1; // a igualdade conta uma iteração a mais
            if (right == maxValue) {
                difference = maxElements; // a soma estouraria: o laço nunca termina ⇒ predicado todo verdadeiro
            }
        }
        if (Long.compareUnsigned(difference, maxElements) > 0) {
            difference = maxElements;
        }
        int elements = holds ? (int) difference : 0;
        writeWhile(core, op.rd(), op.esz(), elements, pair, !less);
    }

    /// `WHILERW`/`WHILEWR` (SVE2): quantos elementos podem ser processados sem que os dois ponteiros conflitem.
    private static void whilePointers(Aarch64Core core, Ir64Op.SveScalarCompare op) {
        long first = core.x(op.rn());
        long second = core.x(op.rm());
        long difference;
        if (op.flag()) { // WHILERW: |op0 - op1|, sem sinal
            difference = Long.compareUnsigned(first, second) >= 0 ? first - second : second - first;
        } else { // WHILEWR: subtração saturante — `diff <= 0` vira 0
            difference = Long.compareUnsigned(second, first) > 0 ? second - first : 0L;
        }
        difference >>>= op.esz();
        long maxElements = (long) core.vectorLengthBytes() >> op.esz();
        // diff == 0 ⇒ a condição vale sempre (todos os elementos); o `- 1` com `umin` e o `+ 1` reproduzem isso.
        difference -= 1;
        if (Long.compareUnsigned(difference, maxElements - 1) > 0) {
            difference = maxElements - 1;
        }
        writeWhile(core, op.rd(), op.esz(), (int) difference + 1, false, false);
    }

    /// Escreve o(s) predicado(s) de `elements` elementos e as flags de `PredCountTest`.
    private static void writeWhile(Aarch64Core core, int rd, int esz, int elements, boolean pair, boolean fromEnd) {
        Aarch64ScalableRegisters regs = core.scalable();
        int operandBits = SvePredicateOps.predicateBits(core);
        int count = elements << esz;
        int words = regs.wordsPerPredicate();
        long[] first = new long[words];
        long[] second = new long[words];
        if (!pair) {
            fill(first, count, operandBits, esz, fromEnd);
        } else if (!fromEnd) {
            if (count <= operandBits) {
                fill(first, count, operandBits, esz, false);
            } else {
                fill(first, operandBits, operandBits, esz, false);
                fill(second, count - operandBits, operandBits, esz, false);
            }
        } else if (count <= operandBits) {
            fill(second, count, operandBits, esz, true);
        } else {
            fill(second, operandBits, operandBits, esz, false);
            fill(first, count - operandBits, operandBits, esz, true);
        }
        SvePredicateOps.write(regs, rd, first);
        if (pair) {
            SvePredicateOps.write(regs, rd + PAIR_SECOND_OFFSET, second);
        }
        predCountTest(core, (pair ? 2 : 1) * operandBits, count, fromEnd);
    }

    /// `do_whilel` (`fromEnd = false`: os `count` bits mais baixos) e `do_whileg` (`true`: os mais altos), só nos bits
    /// alinhados ao tamanho do elemento.
    private static void fill(long[] predicate, int count, int operandBits, int esz, boolean fromEnd) {
        int step = 1 << esz;
        int firstBit = fromEnd ? operandBits - count : 0;
        for (int index = firstBit; index < firstBit + count; index += step) {
            SvePredicateOps.setBit(predicate, index, true);
        }
    }

    /// `pred_count_test` do QEMU (`PredCountTest` do manual).
    private static void predCountTest(Aarch64Core core, int elements, int count, boolean invert) {
        boolean negative;
        boolean zero = count == 0;
        boolean carry;
        if (zero) {
            negative = false;
            carry = true;
        } else if (!invert) {
            negative = true;
            carry = count != elements;
        } else {
            negative = count == elements;
            carry = false;
        }
        core.pstate().setNzcv(negative, zero, carry, false);
    }
}
