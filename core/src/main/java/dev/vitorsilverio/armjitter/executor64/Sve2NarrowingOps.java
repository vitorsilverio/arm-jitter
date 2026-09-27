package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das 33 operações SVE2 de `#### SVE2 Narrowing` (B17.21b): extract narrow, shift right narrow, add/sub narrow
/// high part e as três conversões de par (`SQCVTN`/`UQCVTN`/`SQCVTUN`).
///
/// A fonte é o elemento LARGO `e` ({@code op.esz()}); o resultado tem metade do tamanho. As formas `*B` (bottom) escrevem o
/// resultado no elemento de índice PAR de um par estreito e ZERAM o ímpar (o slot largo inteiro recebe o resultado
/// zero-estendido); as `*T` (top) escrevem SÓ o ímpar e PRESERVAM o par — semântica, não acidente: zerar seria o bug
/// natural.
///
/// Sem predicado e sem `FPSR.QC`. A fonte de cada elemento é lida ANTES de escrevê-lo (o destino pode ser a fonte).
final class Sve2NarrowingOps {
    private static final int ESZ_HALF = 1;
    private static final int ESZ_WORD = 2;
    private static final int PAIR = 2;

    private Sve2NarrowingOps() {
    }

    static void execute(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        switch (op.op()) {
            case SQCVTN, UQCVTN, SQCVTUN -> convertPair(regs, op, elements);
            case ADDHN, RADDHN, SUBHN, RSUBHN -> addSubHighPart(regs, op, elements);
            case SQXTN, UQXTN, SQXTUN -> extractNarrow(regs, op, elements);
            default -> shiftRightNarrow(regs, op, elements);
        }
    }

    /// Escreve o resultado estreito do elemento largo `e`: `top` = elemento ímpar do par (preserva o par), senão o slot largo
    /// inteiro (resultado zero-estendido, zerando o ímpar).
    private static void store(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int e, boolean top,
            long narrow) {
        int esz = op.esz();
        if (top) {
            SveIntegerOps.set(regs, op.rd(), PAIR * e + 1, esz - 1, narrow);
        } else {
            SveIntegerOps.set(regs, op.rd(), e, esz, narrow & SveIntegerOps.elementMask(esz - 1));
        }
    }

    /// `SQXTN*`/`UQXTN*`/`SQXTUN*`: satura o elemento largo ao intervalo estreito (com/sem sinal na origem e no destino).
    private static void extractNarrow(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int narrowEsz = esz - 1;
        boolean top = op.imm() != 0L;
        for (int e = 0; e < elements; e++) {
            long source = SveIntegerOps.get(regs, op.rn(), e, esz);
            long narrow = switch (op.op()) {
                case SQXTN -> saturateSigned(SveIntegerOps.signExtend(source, esz), narrowEsz);
                case UQXTN -> saturateUnsigned(source, narrowEsz);
                default -> signedToUnsigned(SveIntegerOps.signExtend(source, esz), narrowEsz); // SQXTUN
            };
            store(regs, op, e, top, narrow);
        }
    }

    /// `*SHRN*`/`*RSHRN*`/`SQ*SHRUN*`: desloca à direita (`imm`), com ou sem arredondamento, e satura/trunca ao estreito.
    private static void shiftRightNarrow(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int narrowEsz = esz - 1;
        boolean top = op.imm2() != 0L;
        Ir64Op.SveIntegerPredicated.Op shift = switch (op.op()) {
            case SHRN, UQSHRN -> Ir64Op.SveIntegerPredicated.Op.LSR_IMM;
            case RSHRN, UQRSHRN -> Ir64Op.SveIntegerPredicated.Op.URSHR;
            case SQSHRN, SQSHRUN -> Ir64Op.SveIntegerPredicated.Op.ASR_IMM;
            default -> Ir64Op.SveIntegerPredicated.Op.SRSHR; // SQRSHRN/SQRSHRUN
        };
        for (int e = 0; e < elements; e++) {
            long shifted = SveIntegerPredicatedOps.immediateShift(shift, SveIntegerOps.get(regs, op.rn(), e, esz),
                    op.imm(), esz);
            long narrow = switch (op.op()) {
                case SHRN, RSHRN -> shifted; // só trunca (o `store` mascara)
                case SQSHRN, SQRSHRN -> saturateSigned(shifted, narrowEsz);
                case UQSHRN, UQRSHRN -> saturateUnsigned(shifted, narrowEsz);
                default -> signedToUnsigned(shifted, narrowEsz); // SQSHRUN/SQRSHRUN
            };
            store(regs, op, e, top, narrow);
        }
    }

    /// `ADDHN*`/`RADDHN*`/`SUBHN*`/`RSUBHN*`: os `esize/2` bits ALTOS de `Zn ± Zm` (`R*` soma antes `1 << (esize/2 - 1)`).
    private static void addSubHighPart(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int halfBits = SveIntegerOps.elementBits(esz) / 2;
        boolean top = op.imm() != 0L;
        boolean subtract = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SUBHN
                || op.op() == Ir64Op.SveIntegerUnpredicated.Op.RSUBHN;
        boolean rounding = op.op() == Ir64Op.SveIntegerUnpredicated.Op.RADDHN
                || op.op() == Ir64Op.SveIntegerUnpredicated.Op.RSUBHN;
        for (int e = 0; e < elements; e++) {
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = SveIntegerOps.get(regs, op.rm(), e, esz);
            long sum = subtract ? n - m : n + m;
            if (rounding) {
                sum += 1L << (halfBits - 1);
            }
            store(regs, op, e, top, sum >>> halfBits);
        }
    }

    /// `SQCVTN`/`UQCVTN`/`SQCVTUN` (`.H` de um par de `.S`): `Zd[2i] = sat(Zn[i])`, `Zd[2i+1] = sat(Zn+1[i])`. Os dois
    /// registradores-fonte são lidos por inteiro antes de escrever (o destino pode ser um deles).
    private static void convertPair(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        long[] result = new long[PAIR * elements];
        for (int i = 0; i < elements; i++) {
            for (int half = 0; half < PAIR; half++) {
                long source = SveIntegerOps.get(regs, op.rn() + half, i, ESZ_WORD);
                result[PAIR * i + half] = switch (op.op()) {
                    case SQCVTN -> saturateSigned(SveIntegerOps.signExtend(source, ESZ_WORD), ESZ_HALF);
                    case UQCVTN -> saturateUnsigned(source, ESZ_HALF);
                    default -> signedToUnsigned(SveIntegerOps.signExtend(source, ESZ_WORD), ESZ_HALF); // SQCVTUN
                };
            }
        }
        for (int k = 0; k < result.length; k++) {
            SveIntegerOps.set(regs, op.rd(), k, ESZ_HALF, result[k]);
        }
    }

    /// Satura um valor COM sinal ao intervalo com sinal de `narrowEsz`; devolve os bits do elemento.
    private static long saturateSigned(long value, int narrowEsz) {
        long max = (1L << (SveIntegerOps.elementBits(narrowEsz) - 1)) - 1L;
        return Math.max(-max - 1L, Math.min(max, value)) & SveIntegerOps.elementMask(narrowEsz);
    }

    /// Satura um valor SEM sinal (todos os 64 bits são magnitude) ao máximo sem sinal de `narrowEsz`.
    private static long saturateUnsigned(long value, int narrowEsz) {
        long max = SveIntegerOps.elementMask(narrowEsz);
        return Long.compareUnsigned(value, max) > 0 ? max : value;
    }

    /// Satura um valor COM sinal ao intervalo SEM sinal de `narrowEsz` (negativo vira `0`).
    private static long signedToUnsigned(long value, int narrowEsz) {
        long max = SveIntegerOps.elementMask(narrowEsz);
        return value < 0L ? 0L : Long.compareUnsigned(value, max) > 0 ? max : value;
    }
}
