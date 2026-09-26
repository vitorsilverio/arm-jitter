package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica do multiply-add de ponto flutuante SVE (B17.14): `FMLA`/`FMLS`/`FNMLA`/`FNMLS` predicados (as oito
/// linhas, `FMAD`/`FMSB`/`FNMAD`/`FNMSB` inclusive), `FMLA`/`FMLS`/`FMUL` por elemento indexado, `FCADD` e `FCMLA`
/// (predicado e indexado). A matemática vive em {@link SveFloat}; aqui só há o laço por elemento.
///
/// **A multiplicação-acumulação é FUNDIDA** (`SveFloat.fusedMultiplyAdd`, um só arredondamento) — `a + b×c` em
/// aritmética separada arredondaria duas vezes e divergiria em `fp32`/`fp16`. `FMLS` nega o multiplicando `Zn`,
/// `FNMLA` nega `Zn` E o addend, `FNMLS` nega só o addend (o sinal é invertido por XOR mesmo em NaN, como o QEMU);
/// `FPCR.AH` não é modelado (pendência nomeada da B17.13): valem os caminhos `AH = 0`.
///
/// **Predicação por ELEMENTO, também em `FCADD`/`FCMLA`**: cada metade (real e imaginária) do par tem o seu próprio
/// bit de predicado — o predicado NÃO governa "pares" (`helper_sve_fcadd_*`/`helper_sve_fcmla_zpzzz_*` do QEMU
/// testam `pg` no índice real e, separadamente, no imaginário). Elemento inativo não executa a operação (não suja
/// o `FPSR`) e o `Zd` fica como estava. As formas indexadas não são predicadas e o índice é POR SEGMENTO de 128
/// bits, com o elemento de `Zm` lido uma vez por segmento antes de qualquer escrita (vale com `Zd == Zm`).
///
/// Nada disto é ilegal em modo streaming.
final class SveFpMultiplyAddOps {
    private static final int SEGMENT_BYTES = 16;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int COMPLEX_PAIR = 2;
    private static final int ROT_FLIP_MASK = 1;
    private static final int ROT_NEGATE_IMAGINARY_SHIFT = 1;
    private static final int FCADD_ROTATE_270 = 1;

    private SveFpMultiplyAddOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveFpMultiplyAdd op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        SveFloat.Env env = SveFloat.Env.of(core, op.esz());
        int elements = core.vectorLengthBytes() >> op.esz();
        switch (op.op()) {
            case FCADD -> complexAdd(regs, op, elements, env);
            case FCMLA -> {
                if (op.indexed()) {
                    complexMultiplyAddIndexed(regs, op, elements, env);
                } else {
                    complexMultiplyAdd(regs, op, elements, env);
                }
            }
            case FMUL -> multiplyIndexed(regs, op, elements, env);
            default -> {
                if (op.indexed()) {
                    multiplyAddIndexed(regs, op, elements, env);
                } else {
                    multiplyAdd(regs, op, elements, env);
                }
            }
        }
        env.commit(core);
        return false;
    }

    /// O elemento `element` está ativo em `P[pg]` (bit do byte mais baixo do elemento).
    private static boolean active(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    // ── FMLA/FMLS/FNMLA/FNMLS predicados ─────────────────────────────────────────────────────────

    private static void multiplyAdd(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op, int elements,
            SveFloat.Env env) {
        int esz = op.esz();
        boolean negateProduct = op.op() == Ir64Op.SveFpMultiplyAdd.Op.FMLS
                || op.op() == Ir64Op.SveFpMultiplyAdd.Op.FNMLA;
        boolean negateAddend = op.op() == Ir64Op.SveFpMultiplyAdd.Op.FNMLA
                || op.op() == Ir64Op.SveFpMultiplyAdd.Op.FNMLS;
        for (int e = 0; e < elements; e++) {
            if (!active(regs, op.pg(), e, esz)) {
                continue;
            }
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = SveIntegerOps.get(regs, op.rm(), e, esz);
            long a = SveIntegerOps.get(regs, op.ra(), e, esz);
            SveIntegerOps.set(regs, op.rd(), e, esz, SveFloat.fusedMultiplyAdd(
                    negateAddend ? env.negate(a) : a, negateProduct ? env.negate(n) : n, m, 0, env));
        }
    }

    // ── FMLA/FMLS/FMUL indexados ─────────────────────────────────────────────────────────────────

    private static void multiplyAddIndexed(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op, int elements,
            SveFloat.Env env) {
        int esz = op.esz();
        int perSegment = SEGMENT_BYTES >> esz;
        boolean negateProduct = op.op() == Ir64Op.SveFpMultiplyAdd.Op.FMLS;
        for (int base = 0; base < elements; base += perSegment) {
            long m = SveIntegerOps.get(regs, op.rm(), base + op.index(), esz);
            for (int e = base; e < base + perSegment; e++) {
                long n = SveIntegerOps.get(regs, op.rn(), e, esz);
                long a = SveIntegerOps.get(regs, op.ra(), e, esz);
                SveIntegerOps.set(regs, op.rd(), e, esz,
                        SveFloat.fusedMultiplyAdd(a, negateProduct ? env.negate(n) : n, m, 0, env));
            }
        }
    }

    private static void multiplyIndexed(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op, int elements,
            SveFloat.Env env) {
        int esz = op.esz();
        int perSegment = SEGMENT_BYTES >> esz;
        for (int base = 0; base < elements; base += perSegment) {
            long m = SveIntegerOps.get(regs, op.rm(), base + op.index(), esz);
            for (int e = base; e < base + perSegment; e++) {
                long n = SveIntegerOps.get(regs, op.rn(), e, esz);
                SveIntegerOps.set(regs, op.rd(), e, esz, SveFloat.multiply(n, m, env));
            }
        }
    }

    // ── Complexos ────────────────────────────────────────────────────────────────────────────────

    /// `FCADD`: `Zdn` (par real/imaginário) mais `Zm` rodado de 90° (`rot = 0`) ou 270° (`rot = 1`):
    /// `90°`: `re = n_re − m_im`, `im = n_im + m_re`; `270°`: `re = n_re + m_im`, `im = n_im − m_re`. O sinal é
    /// invertido no operando e a soma é uma soma (não uma subtração), como o QEMU.
    private static void complexAdd(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op, int elements,
            SveFloat.Env env) {
        int esz = op.esz();
        boolean rotate270 = op.rot() == FCADD_ROTATE_270;
        for (int re = 0; re < elements; re += COMPLEX_PAIR) {
            int im = re + 1;
            long nRe = SveIntegerOps.get(regs, op.rn(), re, esz);
            long nIm = SveIntegerOps.get(regs, op.rn(), im, esz);
            long mRe = SveIntegerOps.get(regs, op.rm(), re, esz);
            long mIm = SveIntegerOps.get(regs, op.rm(), im, esz);
            if (active(regs, op.pg(), re, esz)) {
                SveIntegerOps.set(regs, op.rd(), re, esz,
                        SveFloat.add(nRe, rotate270 ? mIm : env.negate(mIm), false, env));
            }
            if (active(regs, op.pg(), im, esz)) {
                SveIntegerOps.set(regs, op.rd(), im, esz,
                        SveFloat.add(nIm, rotate270 ? env.negate(mRe) : mRe, false, env));
            }
        }
    }

    /// `FCMLA`: uma das quatro parcelas do produto complexo acumuladas em `Zda` (par real/imaginário). Com
    /// `flip = rot & 1`, `negImag = rot >> 1` e `negReal = flip ^ negImag`: `e2 = flip ? n_im : n_re`,
    /// `re += e2 × ±(flip ? m_im : m_re)`, `im += e2 × ±(flip ? m_re : m_im)`.
    private static void complexMultiplyAdd(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op, int elements,
            SveFloat.Env env) {
        int esz = op.esz();
        boolean flip = (op.rot() & ROT_FLIP_MASK) != 0;
        boolean negateImaginary = ((op.rot() >> ROT_NEGATE_IMAGINARY_SHIFT) & 1) != 0;
        boolean negateReal = flip ^ negateImaginary;
        for (int re = 0; re < elements; re += COMPLEX_PAIR) {
            int im = re + 1;
            long nRe = SveIntegerOps.get(regs, op.rn(), re, esz);
            long nIm = SveIntegerOps.get(regs, op.rn(), im, esz);
            long mRe = SveIntegerOps.get(regs, op.rm(), re, esz);
            long mIm = SveIntegerOps.get(regs, op.rm(), im, esz);
            long accRe = SveIntegerOps.get(regs, op.ra(), re, esz);
            long accIm = SveIntegerOps.get(regs, op.ra(), im, esz);
            long e2 = flip ? nIm : nRe;
            long e1 = flip ? mIm : mRe;
            long e3 = flip ? mRe : mIm;
            if (active(regs, op.pg(), re, esz)) {
                SveIntegerOps.set(regs, op.rd(), re, esz,
                        SveFloat.fusedMultiplyAdd(accRe, e2, negateReal ? env.negate(e1) : e1, 0, env));
            }
            if (active(regs, op.pg(), im, esz)) {
                SveIntegerOps.set(regs, op.rd(), im, esz,
                        SveFloat.fusedMultiplyAdd(accIm, e2, negateImaginary ? env.negate(e3) : e3, 0, env));
            }
        }
    }

    /// `FCMLA` indexado: o índice escolhe um PAR (real, imaginário) de `Zm` dentro de cada segmento de 128 bits;
    /// não é predicado.
    private static void complexMultiplyAddIndexed(Aarch64ScalableRegisters regs, Ir64Op.SveFpMultiplyAdd op,
            int elements, SveFloat.Env env) {
        int esz = op.esz();
        int perSegment = SEGMENT_BYTES >> esz;
        boolean flip = (op.rot() & ROT_FLIP_MASK) != 0;
        boolean negateImaginary = ((op.rot() >> ROT_NEGATE_IMAGINARY_SHIFT) & 1) != 0;
        boolean negateReal = flip ^ negateImaginary;
        for (int base = 0; base < elements; base += perSegment) {
            long mRe = SveIntegerOps.get(regs, op.rm(), base + COMPLEX_PAIR * op.index(), esz);
            long mIm = SveIntegerOps.get(regs, op.rm(), base + COMPLEX_PAIR * op.index() + 1, esz);
            long e1 = flip ? mIm : mRe;
            long e3 = flip ? mRe : mIm;
            for (int re = base; re < base + perSegment; re += COMPLEX_PAIR) {
                int im = re + 1;
                long e2 = SveIntegerOps.get(regs, op.rn(), flip ? im : re, esz);
                long accRe = SveIntegerOps.get(regs, op.ra(), re, esz);
                long accIm = SveIntegerOps.get(regs, op.ra(), im, esz);
                SveIntegerOps.set(regs, op.rd(), re, esz,
                        SveFloat.fusedMultiplyAdd(accRe, e2, negateReal ? env.negate(e1) : e1, 0, env));
                SveIntegerOps.set(regs, op.rd(), im, esz,
                        SveFloat.fusedMultiplyAdd(accIm, e2, negateImaginary ? env.negate(e3) : e3, 0, env));
            }
        }
    }
}
