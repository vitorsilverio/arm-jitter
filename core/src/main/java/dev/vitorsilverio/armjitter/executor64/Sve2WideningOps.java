package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das 43 operações SVE2 de `#### SVE2 Widening Integer Arithmetic` (B17.21b).
///
/// O eixo estrutural é `B`/`T` (bottom/top): `*B` lê o elemento de índice PAR de cada par da fonte estreita, `*T` o ÍMPAR —
/// entrelaçado, NÃO "metade baixa/alta do vetor". O elemento de destino tem o dobro do tamanho ({@code op.esz()}), então
/// há `VL / tamanho` elementos e o elemento `e` lê o `2e + t` das fontes. As duas fontes escolhem `B`/`T` independentemente
/// (`imm` bit 0 = `Zn`, bit 1 = `Zm`) — é isso que faz `SADDLBT`/`SSUBLTB` diferentes de `SADDLB`/`SADDLT`.
///
/// Sem predicado e sem `FPSR.QC`. As fontes de cada elemento são lidas ANTES de escrevê-lo (o destino pode ser uma fonte);
/// como o elemento `e` de destino ocupa os mesmos bytes das fontes `2e`/`2e+1`, o laço em ordem crescente é seguro.
final class Sve2WideningOps {
    private static final int PAIR = 2;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int POLYNOMIAL_BYTE_BITS = 8;
    private static final int POLYNOMIAL_WORD_BITS = 32;
    private static final int QUADWORD_BYTES = 16;
    private static final int MATRIX_DIMENSION = 2;
    private static final int MATRIX_DEPTH = 8;
    private static final int MATRIX_SEGMENT_WORDS = 4;

    private Sve2WideningOps() {
    }

    static void execute(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        switch (op.op()) {
            case SADDL, UADDL, SSUBL, USUBL, SABDL, UABDL, SQDMULL, SMULL, UMULL -> longOperation(regs, op, elements);
            case SADDW, UADDW, SSUBW, USUBW -> wideOperation(regs, op, elements);
            case PMULL -> polynomialMultiplyLong(regs, op, elements);
            case SSHLL, USHLL -> shiftLeftLong(regs, op, elements);
            case EORBT, EORTB -> exclusiveOrInterleaved(regs, op, elements);
            case SMMLA, USMMLA, UMMLA -> matrixMultiply(regs, op, elements);
            default -> bitPermute(regs, op, elements); // BEXT/BDEP/BGRP
        }
    }

    private static boolean signedOperation(Ir64Op.SveIntegerUnpredicated.Op operation) {
        return switch (operation) {
            case SADDL, SSUBL, SABDL, SQDMULL, SMULL, SADDW, SSUBW, SSHLL -> true;
            default -> false;
        };
    }

    /// Estende uma fonte estreita conforme o sinal da operação.
    private static long extend(long value, int sourceEsz, boolean signed) {
        return signed ? SveIntegerOps.signExtend(value, sourceEsz) : value;
    }

    /// `*ADDL`/`*SUBL`/`*ABDL`/`*MULL`/`SQDMULL`: as duas fontes estreitas (`2e + t`) são estendidas e operadas no tamanho
    /// largo. O produto de `*MULL` cabe no destino; só o dobro de `SQDMULL` pode estourar (e satura).
    private static void longOperation(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int sourceEsz = esz - 1;
        boolean signed = signedOperation(op.op());
        int nTop = (int) (op.imm() & 1L);
        int mTop = (int) ((op.imm() >>> 1) & 1L);
        for (int e = 0; e < elements; e++) {
            long n = extend(SveIntegerOps.get(regs, op.rn(), PAIR * e + nTop, sourceEsz), sourceEsz, signed);
            long m = extend(SveIntegerOps.get(regs, op.rm(), PAIR * e + mTop, sourceEsz), sourceEsz, signed);
            long result = switch (op.op()) {
                case SADDL, UADDL -> n + m;
                case SSUBL, USUBL -> n - m;
                case SABDL, UABDL -> Math.abs(n - m);
                case SQDMULL -> SveIntegerOps.saturateSigned(n * m, n * m, esz, false);
                default -> n * m; // SMULL/UMULL
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
    }

    /// `*ADDW`/`*SUBW`: `Zn` já é largo; só `Zm` é estreito (`2e + t`) e estendido.
    private static void wideOperation(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int sourceEsz = esz - 1;
        boolean signed = signedOperation(op.op());
        boolean subtract = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SSUBW
                || op.op() == Ir64Op.SveIntegerUnpredicated.Op.USUBW;
        int mTop = (int) ((op.imm() >>> 1) & 1L);
        for (int e = 0; e < elements; e++) {
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = extend(SveIntegerOps.get(regs, op.rm(), PAIR * e + mTop, sourceEsz), sourceEsz, signed);
            SveIntegerOps.set(regs, op.rd(), e, esz, subtract ? n - m : n + m);
        }
    }

    /// `PMULLB`/`PMULLT`: produto polinomial (sem vai-um) `.H` de `.B`, `.D` de `.S` ou — `FEAT_SVE_PMULL128`, `esz = 0` —
    /// `.Q` de `.D`.
    private static void polynomialMultiplyLong(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        int nTop = (int) (op.imm() & 1L);
        int mTop = (int) ((op.imm() >>> 1) & 1L);
        if (esz == ESZ_BYTE) {
            // `elements` = bytes do vetor; cada segmento de 128 bits tem 2 doublewords fonte e 1 resultado de 128 bits.
            for (int segment = 0; segment < elements / QUADWORD_BYTES; segment++) {
                long n = SveIntegerOps.get(regs, op.rn(), PAIR * segment + nTop, ESZ_DOUBLEWORD);
                long m = SveIntegerOps.get(regs, op.rm(), PAIR * segment + mTop, ESZ_DOUBLEWORD);
                long low = 0L;
                long high = 0L;
                for (int bit = 0; bit < Long.SIZE; bit++) {
                    if (((m >>> bit) & 1L) != 0L) {
                        low ^= n << bit;
                        high ^= bit == 0 ? 0L : n >>> (Long.SIZE - bit);
                    }
                }
                SveIntegerOps.set(regs, op.rd(), PAIR * segment, ESZ_DOUBLEWORD, low);
                SveIntegerOps.set(regs, op.rd(), PAIR * segment + 1, ESZ_DOUBLEWORD, high);
            }
            return;
        }
        int sourceEsz = esz - 1;
        int bits = esz == ESZ_DOUBLEWORD ? POLYNOMIAL_WORD_BITS : POLYNOMIAL_BYTE_BITS;
        for (int e = 0; e < elements; e++) {
            long n = SveIntegerOps.get(regs, op.rn(), PAIR * e + nTop, sourceEsz);
            long m = SveIntegerOps.get(regs, op.rm(), PAIR * e + mTop, sourceEsz);
            SveIntegerOps.set(regs, op.rd(), e, esz, AdvSimdLanes.polynomialMultiply(n, m, bits));
        }
    }

    /// `SSHLLB`/`SSHLLT`/`USHLLB`/`USHLLT`: estende o elemento estreito `2e + t` e desloca à esquerda (`imm`) no destino.
    private static void shiftLeftLong(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int sourceEsz = esz - 1;
        boolean signed = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SSHLL;
        int top = (int) op.imm2();
        int shift = (int) op.imm();
        for (int e = 0; e < elements; e++) {
            long n = extend(SveIntegerOps.get(regs, op.rn(), PAIR * e + top, sourceEsz), sourceEsz, signed);
            SveIntegerOps.set(regs, op.rd(), e, esz, n << shift);
        }
    }

    /// `EORBT`/`EORTB`: em cada par `(2e, 2e+1)`, escreve UM elemento — o do lado de `Zn` — com `Zn ^ Zm` (lados opostos) e
    /// PRESERVA o outro elemento do par (semântica, não acidente: zerar seria o bug natural).
    private static void exclusiveOrInterleaved(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        int nSide = (int) (op.imm() & 1L);
        int mSide = (int) ((op.imm() >>> 1) & 1L);
        for (int e = 0; e < elements; e += PAIR) {
            long value = SveIntegerOps.get(regs, op.rn(), e + nSide, esz) ^ SveIntegerOps.get(regs, op.rm(), e + mSide, esz);
            SveIntegerOps.set(regs, op.rd(), e + nSide, esz, value);
        }
    }

    /// `SMMLA`/`USMMLA`/`UMMLA`: em cada segmento de 128 bits, `Zn` são DUAS linhas de 8 bytes, `Zm` DUAS colunas de 8 bytes
    /// e `Zda` a matriz 2×2 de words: `Zda[2·linha+coluna] += Σₖ n[8·linha+k] · m[8·coluna+k]`, com wrap (nunca satura).
    /// `USMMLA`: `Zn` sem sinal, `Zm` com sinal.
    private static void matrixMultiply(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        boolean signedN = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SMMLA;
        boolean signedM = op.op() != Ir64Op.SveIntegerUnpredicated.Op.UMMLA;
        long[] results = new long[MATRIX_DIMENSION * MATRIX_DIMENSION];
        for (int segment = 0; segment < elements / MATRIX_SEGMENT_WORDS; segment++) {
            int byteBase = segment * QUADWORD_BYTES;
            int wordBase = segment * MATRIX_SEGMENT_WORDS;
            for (int row = 0; row < MATRIX_DIMENSION; row++) {
                for (int column = 0; column < MATRIX_DIMENSION; column++) {
                    long sum = SveIntegerOps.get(regs, op.rd(), wordBase + MATRIX_DIMENSION * row + column, ESZ_WORD);
                    for (int k = 0; k < MATRIX_DEPTH; k++) {
                        long n = extend(SveIntegerOps.get(regs, op.rn(), byteBase + MATRIX_DEPTH * row + k, ESZ_BYTE),
                                ESZ_BYTE, signedN);
                        long m = extend(SveIntegerOps.get(regs, op.rm(), byteBase + MATRIX_DEPTH * column + k, ESZ_BYTE),
                                ESZ_BYTE, signedM);
                        sum += n * m;
                    }
                    results[MATRIX_DIMENSION * row + column] = sum;
                }
            }
            for (int lane = 0; lane < results.length; lane++) {
                SveIntegerOps.set(regs, op.rd(), wordBase + lane, ESZ_WORD, results[lane]);
            }
        }
    }

    /// `BEXT`/`BDEP`/`BGRP` (`FEAT_SVE_BitPerm`) por elemento: dados em `Zn`, máscara em `Zm`.
    private static void bitPermute(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int bits = SveIntegerOps.elementBits(esz);
        for (int e = 0; e < elements; e++) {
            long data = SveIntegerOps.get(regs, op.rn(), e, esz);
            long mask = SveIntegerOps.get(regs, op.rm(), e, esz);
            long result = switch (op.op()) {
                case BEXT -> extractBits(data, mask, bits);
                case BDEP -> depositBits(data, mask, bits);
                default -> groupBits(data, mask, bits); // BGRP
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
    }

    /// Junta, na ordem, os bits de `data` nas posições em que `mask` vale 1 (parte baixa do resultado).
    private static long extractBits(long data, long mask, int bits) {
        long result = 0L;
        int out = 0;
        for (int bit = 0; bit < bits; bit++) {
            if (((mask >>> bit) & 1L) != 0L) {
                result |= ((data >>> bit) & 1L) << out++;
            }
        }
        return result;
    }

    /// O inverso de {@link #extractBits}: espalha os bits baixos de `data` pelas posições em que `mask` vale 1.
    private static long depositBits(long data, long mask, int bits) {
        long result = 0L;
        int in = 0;
        for (int bit = 0; bit < bits; bit++) {
            if (((mask >>> bit) & 1L) != 0L) {
                result |= ((data >>> in++) & 1L) << bit;
            }
        }
        return result;
    }

    /// Bits com `mask = 1` primeiro (parte baixa), depois os com `mask = 0`, cada grupo na ordem original.
    private static long groupBits(long data, long mask, int bits) {
        long masked = 0L;
        long unmasked = 0L;
        int maskedCount = 0;
        int unmaskedCount = 0;
        for (int bit = 0; bit < bits; bit++) {
            long value = (data >>> bit) & 1L;
            if (((mask >>> bit) & 1L) != 0L) {
                masked |= value << maskedCount++;
            } else {
                unmasked |= value << unmaskedCount++;
            }
        }
        return masked | unmasked << maskedCount;
    }
}
