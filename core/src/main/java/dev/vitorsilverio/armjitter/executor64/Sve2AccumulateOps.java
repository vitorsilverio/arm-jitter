package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das 18 operações SVE2 de `#### SVE2 Accumulate` (B17.21a). Todas leem `Zd` como acumulador — inclusive as
/// seis (`SSRA`/`USRA`/`SRSRA`/`URSRA`/`SABA`/`UABA`) que o `sve.decode` descreve com o formato NÃO acumulativo (o `TODO`
/// literal do arquivo): seguir o formato ao pé da letra sobrescreveria o destino em vez de acumular.
///
/// Todas sem predicado e sem `FPSR.QC`. As fontes de cada elemento são lidas ANTES de qualquer escrita (`Zm`/`Zn` podem ser
/// o próprio `Zd`).
final class Sve2AccumulateOps {
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int PAIR = 2;

    private Sve2AccumulateOps() {
    }

    static void execute(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        switch (op.op()) {
            case CADD, SQCADD -> complexAdd(regs, op, elements);
            case SABAL, UABAL -> absoluteDifferenceLong(regs, op, elements);
            case ADCL, SBCL -> addWithCarryLong(regs, op, elements);
            case SSRA, USRA, SRSRA, URSRA -> shiftRightAccumulate(regs, op, elements);
            case SRI, SLI -> shiftInsert(regs, op, elements);
            default -> absoluteDifferenceAccumulate(regs, op, elements); // SABA/UABA
        }
    }

    /// `CADD`/`SQCADD`: `Zdn` mais `Zm` girado ±90° sobre pares `(real, imaginário)` = elementos `(2k, 2k+1)`.
    /// Rotação 90: `re = n.re - m.im`, `im = n.im + m.re`; rotação 270 (`imm = 1`): `re = n.re + m.im`, `im = n.im - m.re`.
    private static void complexAdd(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        boolean saturate = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SQCADD;
        boolean rotate270 = op.imm() != 0L;
        for (int e = 0; e < elements; e += PAIR) {
            long nr = SveIntegerOps.get(regs, op.rn(), e, esz);
            long ni = SveIntegerOps.get(regs, op.rn(), e + 1, esz);
            long mr = SveIntegerOps.get(regs, op.rm(), e, esz);
            long mi = SveIntegerOps.get(regs, op.rm(), e + 1, esz);
            long real = saturate ? SveIntegerOps.saturateSigned(nr, mi, esz, !rotate270) : rotate270 ? nr + mi : nr - mi;
            long imaginary = saturate ? SveIntegerOps.saturateSigned(ni, mr, esz, rotate270)
                    : rotate270 ? ni - mr : ni + mr;
            SveIntegerOps.set(regs, op.rd(), e, esz, real);
            SveIntegerOps.set(regs, op.rd(), e + 1, esz, imaginary);
        }
    }

    /// `SABAL*`/`UABAL*`: `Zda[e] += |Zn[2e+t] - Zm[2e+t]|`, com as fontes na METADE do tamanho do destino (`t = 1` em `*T`).
    private static void absoluteDifferenceLong(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        int sourceEsz = esz - 1;
        boolean signed = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SABAL;
        int top = (int) op.imm();
        for (int e = 0; e < elements; e++) {
            long n = SveIntegerOps.get(regs, op.rn(), PAIR * e + top, sourceEsz);
            long m = SveIntegerOps.get(regs, op.rm(), PAIR * e + top, sourceEsz);
            long difference = signed
                    ? Math.abs(SveIntegerOps.signExtend(n, sourceEsz) - SveIntegerOps.signExtend(m, sourceEsz))
                    : Math.abs(n - m);
            SveIntegerOps.set(regs, op.rd(), e, esz, SveIntegerOps.get(regs, op.rd(), e, esz) + difference);
        }
    }

    /// `ADCL*`/`SBCL*` (`esz` = 2 ou 3 = tamanho do elemento): para cada PAR `(2k, 2k+1)`,
    /// `soma = Zda[2k] + (Zn[2k+t] ou ~Zn[2k+t] em SBCL) + Zm[2k+1]<0>` — a soma tem `esize + 1` bits: os `esize` baixos vão
    /// para `Zda[2k]` e o vai-um (0 ou 1) para `Zda[2k+1]`.
    private static void addWithCarryLong(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        boolean subtract = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SBCL;
        int top = (int) op.imm();
        for (int e = 0; e < elements; e += PAIR) {
            long accumulator = SveIntegerOps.get(regs, op.rd(), e, esz);
            long addend = SveIntegerOps.get(regs, op.rn(), e + top, esz);
            if (subtract) {
                addend = ~addend & SveIntegerOps.elementMask(esz);
            }
            long carryIn = SveIntegerOps.get(regs, op.rm(), e + 1, esz) & 1L;
            long sum = accumulator + addend + carryIn;
            long carryOut;
            if (esz == ESZ_DOUBLEWORD) {
                long partial = accumulator + addend;
                carryOut = Long.compareUnsigned(partial, accumulator) < 0 || Long.compareUnsigned(sum, partial) < 0
                        ? 1L : 0L;
            } else {
                carryOut = sum >>> SveIntegerOps.elementBits(esz);
            }
            SveIntegerOps.set(regs, op.rd(), e, esz, sum);
            SveIntegerOps.set(regs, op.rd(), e + 1, esz, carryOut);
        }
    }

    /// `SSRA`/`USRA`/`SRSRA`/`URSRA`: `Zda += Zn >> imm` (aritmético/lógico; `SR*` arredondam).
    private static void shiftRightAccumulate(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        Ir64Op.SveIntegerPredicated.Op shift = switch (op.op()) {
            case SSRA -> Ir64Op.SveIntegerPredicated.Op.ASR_IMM;
            case USRA -> Ir64Op.SveIntegerPredicated.Op.LSR_IMM;
            case SRSRA -> Ir64Op.SveIntegerPredicated.Op.SRSHR;
            default -> Ir64Op.SveIntegerPredicated.Op.URSHR; // URSRA
        };
        for (int e = 0; e < elements; e++) {
            long shifted = SveIntegerPredicatedOps.immediateShift(shift, SveIntegerOps.get(regs, op.rn(), e, esz),
                    op.imm(), esz);
            SveIntegerOps.set(regs, op.rd(), e, esz, SveIntegerOps.get(regs, op.rd(), e, esz) + shifted);
        }
    }

    /// `SRI`: `Zd = (Zd & bits altos preservados) | (Zn >> imm)`; `SLI`: `Zd = (Zd & bits baixos preservados) | (Zn << imm)`.
    /// Os bits que o shift não alcança ficam como estavam em `Zd`.
    private static void shiftInsert(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int bits = SveIntegerOps.elementBits(esz);
        long all = SveIntegerOps.elementMask(esz);
        int amount = (int) op.imm();
        boolean left = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SLI;
        for (int e = 0; e < elements; e++) {
            long source = SveIntegerOps.get(regs, op.rn(), e, esz);
            long destination = SveIntegerOps.get(regs, op.rd(), e, esz);
            long result;
            if (left) {
                long kept = (1L << amount) - 1L; // amount < esize: os `amount` bits baixos de Zd
                result = destination & kept | (source << amount) & ~kept;
            } else {
                long inserted = amount >= bits ? 0L : all >>> amount; // bits alcançados pelo shift
                result = destination & ~inserted | (amount >= bits ? 0L : source >>> amount) & inserted;
            }
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
    }

    /// `SABA`/`UABA`: `Zda += |Zn - Zm|`.
    private static void absoluteDifferenceAccumulate(Aarch64ScalableRegisters regs,
            Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        boolean signed = op.op() == Ir64Op.SveIntegerUnpredicated.Op.SABA;
        for (int e = 0; e < elements; e++) {
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = SveIntegerOps.get(regs, op.rm(), e, esz);
            long difference = signed
                    ? absoluteDifference(SveIntegerOps.signExtend(n, esz), SveIntegerOps.signExtend(m, esz), true)
                    : absoluteDifference(n, m, false);
            SveIntegerOps.set(regs, op.rd(), e, esz, SveIntegerOps.get(regs, op.rd(), e, esz) + difference);
        }
    }

    /// `|a - b|` módulo 2^64 (exato mesmo quando a diferença verdadeira não cabe num `long` com sinal).
    private static long absoluteDifference(long a, long b, boolean signed) {
        boolean aGreater = signed ? a >= b : Long.compareUnsigned(a, b) >= 0;
        return aGreater ? a - b : b - a;
    }
}
