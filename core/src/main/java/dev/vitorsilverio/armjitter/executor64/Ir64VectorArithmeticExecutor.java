package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;

/// Executa a IR de AdvSIMD inteiro — aritmética/comparação (B8.7): "three same"/"three same
/// pairwise"/"three different" (alargando/largo+estreito/estreitando)/"across lanes"/
/// "two-register miscellaneous", e as formas ESCALARES que reaproveitam os mesmos records (ver
/// {@link Ir64Op.VectorArithmeticThreeSame}/{@link Ir64Op.VectorArithmeticUnary}). Sibling de
/// {@link Ir64FpExecutor}: sem estado próprio (nenhuma operação toca memória), métodos estáticos.
/// É o oráculo semântico (G1) — nenhum `Kind` desta task entra em `Ir64NativePolicy`, mesma
/// decisão de todo `Kind` novo desde B8.4/B8.6.
final class Ir64VectorArithmeticExecutor {
    private Ir64VectorArithmeticExecutor() {
    }

    /// Sign-extende um elemento de `1 << esz` bytes (já lido zero-extendido por
    /// {@link Aarch64FpRegisters#element}) para `long`.
    private static long signExtend(long value, int esz) {
        int bits = 8 << esz;
        if (bits == 64) {
            return value;
        }
        int shift = 64 - bits;
        return (value << shift) >> shift;
    }

    private static long elementMask(int esz) {
        int bits = 8 << esz;
        return bits == 64 ? -1L : (1L << bits) - 1;
    }

    private static long truncate(long value, int esz) {
        return value & elementMask(esz);
    }

    /// Máscara "todos-1" ou `0` para o resultado de uma comparação de elemento.
    private static long boolMask(boolean condition, int esz) {
        return condition ? elementMask(esz) : 0L;
    }

    private static int elementsPerRegister(boolean q, int esz) {
        return (q ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES) >> esz;
    }

    /// Escrita "SIMD&FP destructive": zera os bits altos de `rd` quando `!q` — a forma vetorial
    /// com `q=false` só escreveu os 64 bits baixos; a forma escalar (`esz=3`/`q=false` reaproveitado,
    /// ver javadoc do record) também passa por aqui.
    private static void finishDestructiveWrite(Aarch64FpRegisters fp, int rd, boolean q) {
        if (!q) {
            fp.setQ(rd, fp.low64(rd), 0L);
        }
    }

    static boolean executeThreeSame(Aarch64Core core, Ir64Op.VectorArithmeticThreeSame op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long a = fp.element(op.rn(), i, esz);
            long b = fp.element(op.rm(), i, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            long result = switch (op.op()) {
                case ADD -> a + b;
                case SUB -> a - b;
                case CMGT -> boolMask(sa > sb, esz);
                case CMHI -> boolMask(Long.compareUnsigned(a, b) > 0, esz);
                case CMGE -> boolMask(sa >= sb, esz);
                case CMHS -> boolMask(Long.compareUnsigned(a, b) >= 0, esz);
                case CMTST -> boolMask((a & b) != 0, esz);
                case CMEQ -> boolMask(a == b, esz);
                case SHADD -> (sa + sb) >> 1;
                case UHADD -> (a + b) >>> 1;
                case SHSUB -> (sa - sb) >> 1;
                case UHSUB -> (a - b) >>> 1;
                case SRHADD -> (sa + sb + 1) >> 1;
                case URHADD -> (a + b + 1) >>> 1;
                case SMAX -> Math.max(sa, sb);
                case UMAX -> Long.compareUnsigned(a, b) >= 0 ? a : b;
                case SMIN -> Math.min(sa, sb);
                case UMIN -> Long.compareUnsigned(a, b) <= 0 ? a : b;
                case SABD -> Math.abs(sa - sb);
                case UABD -> Long.compareUnsigned(a, b) >= 0 ? a - b : b - a;
                case SABA -> signExtend(fp.element(op.rd(), i, esz), esz) + Math.abs(sa - sb);
                case UABA -> fp.element(op.rd(), i, esz)
                        + (Long.compareUnsigned(a, b) >= 0 ? a - b : b - a);
                case MUL -> a * b;
                case PMUL -> polynomialMultiply8(a, b);
                case MLA -> fp.element(op.rd(), i, esz) + a * b;
                case MLS -> fp.element(op.rd(), i, esz) - a * b;
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    /// Multiplicação polinomial (`GF(2)`, `PMUL_v`, sempre `byte`): XOR de `a<<i` para cada bit `i`
    /// setado de `b`, sem carry — conferido contra a definição real de `PolynomialMult` do manual.
    private static long polynomialMultiply8(long a, long b) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            if (((b >>> i) & 1) != 0) {
                result ^= a << i;
            }
        }
        return result;
    }

    static boolean executePairwise(Aarch64Core core, Ir64Op.VectorArithmeticPairwise op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        int half = elements / 2;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int register = i < half ? op.rn() : op.rm();
            int pairBase = (i < half ? i : i - half) * 2;
            long a = fp.element(register, pairBase, esz);
            long b = fp.element(register, pairBase + 1, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            results[i] = switch (op.op()) {
                case ADD -> a + b;
                case SMAX -> Math.max(sa, sb);
                case UMAX -> Long.compareUnsigned(a, b) >= 0 ? a : b;
                case SMIN -> Math.min(sa, sb);
                case UMIN -> Long.compareUnsigned(a, b) <= 0 ? a : b;
            };
        }
        for (int i = 0; i < elements; i++) {
            fp.setElement(op.rd(), i, esz, truncate(results[i], esz));
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    static boolean executeWidening(Aarch64Core core, Ir64Op.VectorArithmeticWidening op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int outputElements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? outputElements : 0;
        for (int i = 0; i < outputElements; i++) {
            int lane = laneOffset + i;
            long a = fp.element(op.rn(), lane, esz);
            long b = fp.element(op.rm(), lane, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            long current = fp.element(op.rd(), i, wideEsz);
            long result = switch (op.op()) {
                case SMULL -> sa * sb;
                case UMULL -> a * b;
                case SMLAL -> current + sa * sb;
                case UMLAL -> current + a * b;
                case SMLSL -> current - sa * sb;
                case UMLSL -> current - a * b;
                case SADDL -> sa + sb;
                case UADDL -> a + b;
                case SSUBL -> sa - sb;
                case USUBL -> a - b;
                case SABAL -> signExtend(current, wideEsz) + Math.abs(sa - sb);
                case UABAL -> current + (Long.compareUnsigned(a, b) >= 0 ? a - b : b - a);
                case SABDL -> Math.abs(sa - sb);
                case UABDL -> Long.compareUnsigned(a, b) >= 0 ? a - b : b - a;
            };
            fp.setElement(op.rd(), i, wideEsz, truncate(result, wideEsz));
        }
        return false;
    }

    static boolean executeWide(Aarch64Core core, Ir64Op.VectorArithmeticWide op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int elements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? elements : 0;
        for (int i = 0; i < elements; i++) {
            long wide = fp.element(op.rn(), i, wideEsz);
            long narrow = fp.element(op.rm(), laneOffset + i, esz);
            long extended = switch (op.op()) {
                case SADDW, SSUBW -> signExtend(narrow, esz);
                case UADDW, USUBW -> narrow;
            };
            long result = switch (op.op()) {
                case SADDW, UADDW -> wide + extended;
                case SSUBW, USUBW -> wide - extended;
            };
            fp.setElement(op.rd(), i, wideEsz, truncate(result, wideEsz));
        }
        return false;
    }

    static boolean executeNarrow(Aarch64Core core, Ir64Op.VectorArithmeticNarrow op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int narrowBits = 8 << esz;
        int elements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? elements : 0;
        long rounding = 1L << (narrowBits - 1);
        for (int i = 0; i < elements; i++) {
            long a = fp.element(op.rn(), i, wideEsz);
            long b = fp.element(op.rm(), i, wideEsz);
            long sum = switch (op.op()) {
                case ADDHN -> a + b;
                case RADDHN -> a + b + rounding;
                case SUBHN -> a - b;
                case RSUBHN -> a - b + rounding;
            };
            fp.setElement(op.rd(), laneOffset + i, esz, truncate(sum >>> narrowBits, esz));
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    static boolean executeAcrossLanes(Aarch64Core core, Ir64Op.VectorAcrossLanes op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        boolean signed = op.op() != Ir64VectorAcrossLanesOp.UADDLV
                && op.op() != Ir64VectorAcrossLanesOp.UMAXV && op.op() != Ir64VectorAcrossLanesOp.UMINV;
        long accumulator;
        boolean widen = op.op() == Ir64VectorAcrossLanesOp.SADDLV || op.op() == Ir64VectorAcrossLanesOp.UADDLV;
        boolean isMax = op.op() == Ir64VectorAcrossLanesOp.SMAXV || op.op() == Ir64VectorAcrossLanesOp.UMAXV;
        boolean isMin = op.op() == Ir64VectorAcrossLanesOp.SMINV || op.op() == Ir64VectorAcrossLanesOp.UMINV;
        if (isMax || isMin) {
            accumulator = extendMaybe(fp.element(op.rn(), 0, esz), esz, signed);
            for (int i = 1; i < elements; i++) {
                long v = extendMaybe(fp.element(op.rn(), i, esz), esz, signed);
                accumulator = isMax ? Math.max(accumulator, v) : Math.min(accumulator, v);
            }
        } else {
            accumulator = 0;
            for (int i = 0; i < elements; i++) {
                accumulator += extendMaybe(fp.element(op.rn(), i, esz), esz, signed);
            }
        }
        int resultEsz = widen ? esz + 1 : esz;
        fp.setElement(op.rd(), 0, resultEsz, truncate(accumulator, resultEsz));
        finishDestructiveWrite(fp, op.rd(), false);
        return false;
    }

    private static long extendMaybe(long value, int esz, boolean signed) {
        return signed ? signExtend(value, esz) : value;
    }

    static boolean executeUnary(Aarch64Core core, Ir64Op.VectorArithmeticUnary op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        boolean widening = op.op() == Ir64VectorUnaryOp.SADDLP || op.op() == Ir64VectorUnaryOp.UADDLP
                || op.op() == Ir64VectorUnaryOp.SADALP || op.op() == Ir64VectorUnaryOp.UADALP;
        if (widening) {
            int wideEsz = esz + 1;
            int inputElements = elementsPerRegister(op.q(), esz);
            int outputElements = inputElements / 2;
            boolean signed = op.op() == Ir64VectorUnaryOp.SADDLP || op.op() == Ir64VectorUnaryOp.SADALP;
            boolean accumulate = op.op() == Ir64VectorUnaryOp.SADALP || op.op() == Ir64VectorUnaryOp.UADALP;
            long[] results = new long[outputElements];
            for (int i = 0; i < outputElements; i++) {
                long a = extendMaybe(fp.element(op.rn(), i * 2, esz), esz, signed);
                long b = extendMaybe(fp.element(op.rn(), i * 2 + 1, esz), esz, signed);
                long sum = a + b;
                results[i] = accumulate
                        ? extendMaybe(fp.element(op.rd(), i, wideEsz), wideEsz, signed) + sum
                        : sum;
            }
            for (int i = 0; i < outputElements; i++) {
                fp.setElement(op.rd(), i, wideEsz, truncate(results[i], wideEsz));
            }
            finishDestructiveWrite(fp, op.rd(), op.q());
            return false;
        }
        int elements = elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long a = fp.element(op.rn(), i, esz);
            long sa = signExtend(a, esz);
            long result = switch (op.op()) {
                case ABS -> Math.abs(sa);
                case NEG -> -a;
                case CMEQ0 -> boolMask(sa == 0, esz);
                case CMGT0 -> boolMask(sa > 0, esz);
                case CMGE0 -> boolMask(sa >= 0, esz);
                case CMLT0 -> boolMask(sa < 0, esz);
                case CMLE0 -> boolMask(sa <= 0, esz);
                case SADDLP, UADDLP, SADALP, UADALP ->
                        throw new IllegalStateException("tratado no ramo widening acima");
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    static boolean executeScalarPairwiseAdd(Aarch64Core core, Ir64Op.VectorScalarPairwiseAdd op) {
        Aarch64FpRegisters fp = core.fp();
        long result = fp.element(op.rn(), 0, 3) + fp.element(op.rn(), 1, 3);
        fp.setD(op.rd(), result);
        return false;
    }
}
