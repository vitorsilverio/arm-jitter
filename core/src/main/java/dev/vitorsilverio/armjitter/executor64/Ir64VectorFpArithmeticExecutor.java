package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;

/// Executa a IR de AdvSIMD FP vetorial (B8.9): "three same"/"three same pairwise"/"two-register
/// miscellaneous" de ponto flutuante, só precisão simples/dupla (`esz` `2`/`3` — meia-precisão fica
/// fora, `FEAT_FP16`). Sibling de {@link Ir64VectorArithmeticExecutor} (inteiro) e de
/// {@link Ir64FpExecutor} (escalar) — reaproveita as tabelas de arredondamento/saturação/`FPMaxNum`
/// deste último (mesmo pacote) em vez de duplicá-las. Sem estado próprio, métodos estáticos. É o
/// oráculo semântico (G1) — nenhum `Kind` desta task entra em `Ir64NativePolicy` (cai no
/// interpretador, mesma decisão de todo `Kind` novo desde B8.4).
final class Ir64VectorFpArithmeticExecutor {
    private Ir64VectorFpArithmeticExecutor() {
    }

    private static int elementsPerRegister(boolean q, int esz) {
        return (q ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES) >> esz;
    }

    /// Escrita "SIMD&FP destructive": zera os bits altos de `rd` quando `!q` — mesma disciplina de
    /// {@link Ir64VectorArithmeticExecutor#finishDestructiveWrite} (duplicada aqui porque aquele
    /// método é `private` na classe irmã; ambas as classes preferem duplicar 3 linhas a se
    /// acoplarem por um método `package-private` sem outro uso).
    private static void finishDestructiveWrite(Aarch64FpRegisters fp, int rd, boolean q) {
        if (!q) {
            fp.setQ(rd, fp.low64(rd), 0L);
        }
    }

    private static long floatBits(float value) {
        return Float.floatToRawIntBits(value) & 0xFFFF_FFFFL;
    }

    private static long doubleBits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static long boolMask(boolean condition, int esz) {
        if (!condition) {
            return 0L;
        }
        return esz == 2 ? 0xFFFF_FFFFL : -1L;
    }

    /// `FPMulX` (`ARM DDI 0487`): `0 * Infinito`/`Infinito * 0` devolve `2.0` com o sinal do
    /// produto dos operandos, em vez do `NaN` que a multiplicação IEEE normal produziria — único
    /// desvio de {@code a * b}.
    private static float mulX(float a, float b) {
        if ((a == 0f && Float.isInfinite(b)) || (Float.isInfinite(a) && b == 0f)) {
            int sign = (Float.floatToRawIntBits(a) ^ Float.floatToRawIntBits(b)) & Integer.MIN_VALUE;
            return Float.intBitsToFloat(sign | Float.floatToRawIntBits(2.0f));
        }
        return a * b;
    }

    private static double mulX(double a, double b) {
        if ((a == 0.0 && Double.isInfinite(b)) || (Double.isInfinite(a) && b == 0.0)) {
            long sign = (Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b)) & Long.MIN_VALUE;
            return Double.longBitsToDouble(sign | Double.doubleToRawLongBits(2.0));
        }
        return a * b;
    }

    static boolean executeThreeSame(Aarch64Core core, Ir64Op.VectorFpArithmeticThreeSame op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long resultBits;
            if (esz == 2) {
                float a = Float.intBitsToFloat((int) fp.element(op.rn(), i, esz));
                float b = Float.intBitsToFloat((int) fp.element(op.rm(), i, esz));
                float current = Float.intBitsToFloat((int) fp.element(op.rd(), i, esz));
                resultBits = switch (op.op()) {
                    case ADD -> floatBits(a + b);
                    case SUB -> floatBits(a - b);
                    case MUL -> floatBits(a * b);
                    case DIV -> floatBits(a / b);
                    case MAX -> floatBits(Math.max(a, b));
                    case MIN -> floatBits(Math.min(a, b));
                    case MAXNM -> floatBits(Ir64FpExecutor.maxNum(a, b));
                    case MINNM -> floatBits(Ir64FpExecutor.minNum(a, b));
                    case MULX -> floatBits(mulX(a, b));
                    // FMLA/FMLS: multiply-accumulate FUNDIDO (arredondamento único) — mesma
                    // decisão de {@link Ir64Op.Fp64MultiplyAdd}, `Math.fma`.
                    case MLA -> floatBits(Math.fma(a, b, current));
                    case MLS -> floatBits(Math.fma(-a, b, current));
                    case CMEQ -> boolMask(a == b, esz);
                    case CMGE -> boolMask(a >= b, esz);
                    case CMGT -> boolMask(a > b, esz);
                    case FACGE -> boolMask(Math.abs(a) >= Math.abs(b), esz);
                    case FACGT -> boolMask(Math.abs(a) > Math.abs(b), esz);
                    case ABD -> floatBits(Math.abs(a - b));
                    case RECPS -> floatBits(2.0f - a * b);
                    case RSQRTS -> floatBits((3.0f - a * b) / 2.0f);
                };
            } else {
                double a = Double.longBitsToDouble(fp.element(op.rn(), i, esz));
                double b = Double.longBitsToDouble(fp.element(op.rm(), i, esz));
                double current = Double.longBitsToDouble(fp.element(op.rd(), i, esz));
                resultBits = switch (op.op()) {
                    case ADD -> doubleBits(a + b);
                    case SUB -> doubleBits(a - b);
                    case MUL -> doubleBits(a * b);
                    case DIV -> doubleBits(a / b);
                    case MAX -> doubleBits(Math.max(a, b));
                    case MIN -> doubleBits(Math.min(a, b));
                    case MAXNM -> doubleBits(Ir64FpExecutor.maxNum(a, b));
                    case MINNM -> doubleBits(Ir64FpExecutor.minNum(a, b));
                    case MULX -> doubleBits(mulX(a, b));
                    case MLA -> doubleBits(Math.fma(a, b, current));
                    case MLS -> doubleBits(Math.fma(-a, b, current));
                    case CMEQ -> boolMask(a == b, esz);
                    case CMGE -> boolMask(a >= b, esz);
                    case CMGT -> boolMask(a > b, esz);
                    case FACGE -> boolMask(Math.abs(a) >= Math.abs(b), esz);
                    case FACGT -> boolMask(Math.abs(a) > Math.abs(b), esz);
                    case ABD -> doubleBits(Math.abs(a - b));
                    case RECPS -> doubleBits(2.0 - a * b);
                    case RSQRTS -> doubleBits((3.0 - a * b) / 2.0);
                };
            }
            fp.setElement(op.rd(), i, esz, resultBits);
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    /// Escrita destrutiva CIENTE de forma escalar — mesma disciplina de
    /// {@link Ir64VectorArithmeticExecutor#finishScalarAwareWrite} (duplicada aqui pelo mesmo
    /// motivo de {@link #finishDestructiveWrite}: aquele método é `private` na classe irmã).
    private static void finishScalarAwareWrite(Aarch64FpRegisters fp, int rd, boolean scalar, boolean q, int esz) {
        if (scalar) {
            fp.setQ(rd, fp.element(rd, 0, esz), 0L);
        } else {
            finishDestructiveWrite(fp, rd, q);
        }
    }

    /// B8.19: `FMUL_{vi,si}`/`FMLA_{vi,si}`/`FMLS_{vi,si}`/`FMULX_{vi,si}` — MESMA lógica de
    /// {@link #executeThreeSame}, exceto que `b` (de `Rm`) é lido UMA VEZ fora do laço, sempre no
    /// elemento {@link Ir64Op.VectorFpArithmeticThreeSameByElement#index} (replicado), nunca
    /// `fp.element(op.rm(), i, esz)`. A forma ESCALAR processa só o elemento `0`.
    static boolean executeThreeSameByElement(Aarch64Core core, Ir64Op.VectorFpArithmeticThreeSameByElement op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long resultBits;
            if (esz == 2) {
                float a = Float.intBitsToFloat((int) fp.element(op.rn(), i, esz));
                float b = Float.intBitsToFloat((int) fp.element(op.rm(), op.index(), esz));
                float current = Float.intBitsToFloat((int) fp.element(op.rd(), i, esz));
                resultBits = switch (op.op()) {
                    case MUL -> floatBits(a * b);
                    case MULX -> floatBits(mulX(a, b));
                    case MLA -> floatBits(Math.fma(a, b, current));
                    case MLS -> floatBits(Math.fma(-a, b, current));
                    default -> throw new IllegalStateException(
                            "Ir64VectorFpThreeSameOp não suportado em by-element: " + op.op());
                };
            } else {
                double a = Double.longBitsToDouble(fp.element(op.rn(), i, esz));
                double b = Double.longBitsToDouble(fp.element(op.rm(), op.index(), esz));
                double current = Double.longBitsToDouble(fp.element(op.rd(), i, esz));
                resultBits = switch (op.op()) {
                    case MUL -> doubleBits(a * b);
                    case MULX -> doubleBits(mulX(a, b));
                    case MLA -> doubleBits(Math.fma(a, b, current));
                    case MLS -> doubleBits(Math.fma(-a, b, current));
                    default -> throw new IllegalStateException(
                            "Ir64VectorFpThreeSameOp não suportado em by-element: " + op.op());
                };
            }
            fp.setElement(op.rd(), i, esz, resultBits);
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    static boolean executePairwise(Aarch64Core core, Ir64Op.VectorFpArithmeticPairwise op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        int half = elements / 2;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int register = i < half ? op.rn() : op.rm();
            int pairBase = (i < half ? i : i - half) * 2;
            if (esz == 2) {
                float a = Float.intBitsToFloat((int) fp.element(register, pairBase, esz));
                float b = Float.intBitsToFloat((int) fp.element(register, pairBase + 1, esz));
                results[i] = switch (op.op()) {
                    case ADD -> floatBits(a + b);
                    case MAX -> floatBits(Math.max(a, b));
                    case MIN -> floatBits(Math.min(a, b));
                    case MAXNM -> floatBits(Ir64FpExecutor.maxNum(a, b));
                    case MINNM -> floatBits(Ir64FpExecutor.minNum(a, b));
                };
            } else {
                double a = Double.longBitsToDouble(fp.element(register, pairBase, esz));
                double b = Double.longBitsToDouble(fp.element(register, pairBase + 1, esz));
                results[i] = switch (op.op()) {
                    case ADD -> doubleBits(a + b);
                    case MAX -> doubleBits(Math.max(a, b));
                    case MIN -> doubleBits(Math.min(a, b));
                    case MAXNM -> doubleBits(Ir64FpExecutor.maxNum(a, b));
                    case MINNM -> doubleBits(Ir64FpExecutor.minNum(a, b));
                };
            }
        }
        for (int i = 0; i < elements; i++) {
            fp.setElement(op.rd(), i, esz, results[i]);
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    private static final Ir64Op.Fp64RoundingDirection[] RINT_DIRECTION_BY_OP =
            new Ir64Op.Fp64RoundingDirection[Ir64VectorFpUnaryOp.values().length];

    static {
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTN.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTM.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY;
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTP.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY;
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTZ.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_ZERO;
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTA.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY;
        // RINTX/RINTI: sem modelo de exceção de inexatidão/FPCR.RMode neste emulador — mesma
        // decisão do escalar Fp64Round (B8.5) — caem no arredondamento "mais próximo, par".
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTX.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
        RINT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.RINTI.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
    }

    private static final Ir64Op.Fp64RoundingDirection[] FCVT_DIRECTION_BY_OP =
            new Ir64Op.Fp64RoundingDirection[Ir64VectorFpUnaryOp.values().length];

    static {
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTNS.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTNU.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTPS.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTPU.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTMS.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTMU.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTZS.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_ZERO;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTZU.ordinal()] = Ir64Op.Fp64RoundingDirection.TOWARD_ZERO;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTAS.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY;
        FCVT_DIRECTION_BY_OP[Ir64VectorFpUnaryOp.FCVTAU.ordinal()] = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY;
    }

    private static boolean isUnsignedFcvt(Ir64VectorFpUnaryOp op) {
        return op == Ir64VectorFpUnaryOp.FCVTNU || op == Ir64VectorFpUnaryOp.FCVTPU
                || op == Ir64VectorFpUnaryOp.FCVTMU || op == Ir64VectorFpUnaryOp.FCVTZU
                || op == Ir64VectorFpUnaryOp.FCVTAU;
    }

    static boolean executeUnary(Aarch64Core core, Ir64Op.VectorFpArithmeticUnary op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        boolean wide = esz == 3;
        int elements = elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long inputBits = fp.element(op.rn(), i, esz);
            long resultBits = switch (op.op()) {
                case SCVTF, UCVTF -> {
                    boolean signed = op.op() == Ir64VectorFpUnaryOp.SCVTF;
                    double asDouble;
                    if (wide) {
                        asDouble = signed ? (double) inputBits : Ir64FpExecutor.unsignedLongToDouble(inputBits);
                    } else {
                        asDouble = signed ? (double) (int) inputBits : (double) inputBits;
                    }
                    yield esz == 2 ? floatBits((float) asDouble) : doubleBits(asDouble);
                }
                case FCVTNS, FCVTNU, FCVTPS, FCVTPU, FCVTMS, FCVTMU, FCVTZS, FCVTZU, FCVTAS, FCVTAU -> {
                    double value = esz == 2 ? Float.intBitsToFloat((int) inputBits) : Double.longBitsToDouble(inputBits);
                    double rounded = Ir64FpExecutor.roundToIntegralForConversion(value, FCVT_DIRECTION_BY_OP[op.op().ordinal()]);
                    long converted = Ir64FpExecutor.saturateToInteger(rounded, !isUnsignedFcvt(op.op()), wide);
                    yield converted & (wide ? -1L : 0xFFFF_FFFFL);
                }
                default -> {
                    if (esz == 2) {
                        float a = Float.intBitsToFloat((int) inputBits);
                        yield switch (op.op()) {
                            case ABS -> floatBits(Float.intBitsToFloat((int) inputBits & Integer.MAX_VALUE));
                            case NEG -> floatBits(Float.intBitsToFloat((int) inputBits ^ Integer.MIN_VALUE));
                            case SQRT -> floatBits((float) Math.sqrt(a));
                            case RECPE -> floatBits(1.0f / a);
                            case RSQRTE -> floatBits((float) (1.0 / Math.sqrt(a)));
                            case CMGT0 -> boolMask(a > 0f, esz);
                            case CMGE0 -> boolMask(a >= 0f, esz);
                            case CMEQ0 -> boolMask(a == 0f, esz);
                            case CMLE0 -> boolMask(a <= 0f, esz);
                            case CMLT0 -> boolMask(a < 0f, esz);
                            case RINTN, RINTM, RINTP, RINTZ, RINTA, RINTX, RINTI ->
                                    floatBits((float) Ir64FpExecutor.roundToIntegral(a, RINT_DIRECTION_BY_OP[op.op().ordinal()]));
                            default -> throw new IllegalStateException("tratado no ramo de conversão acima");
                        };
                    }
                    double a = Double.longBitsToDouble(inputBits);
                    yield switch (op.op()) {
                        case ABS -> doubleBits(Double.longBitsToDouble(inputBits & Long.MAX_VALUE));
                        case NEG -> doubleBits(Double.longBitsToDouble(inputBits ^ Long.MIN_VALUE));
                        case SQRT -> doubleBits(Math.sqrt(a));
                        case RECPE -> doubleBits(1.0 / a);
                        case RSQRTE -> doubleBits(1.0 / Math.sqrt(a));
                        case CMGT0 -> boolMask(a > 0.0, esz);
                        case CMGE0 -> boolMask(a >= 0.0, esz);
                        case CMEQ0 -> boolMask(a == 0.0, esz);
                        case CMLE0 -> boolMask(a <= 0.0, esz);
                        case CMLT0 -> boolMask(a < 0.0, esz);
                        case RINTN, RINTM, RINTP, RINTZ, RINTA, RINTX, RINTI ->
                                doubleBits(Ir64FpExecutor.roundToIntegral(a, RINT_DIRECTION_BY_OP[op.op().ordinal()]));
                        default -> throw new IllegalStateException("tratado no ramo de conversão acima");
                    };
                }
            };
            fp.setElement(op.rd(), i, esz, resultBits);
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    /// `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (B8.10) — reduz os 4 elementos SIMPLES de `Rn` a um
    /// único escalar S em `Rd`. Só precisão simples (único arranjo real desta família, `esz`
    /// sempre `2` — ver {@link Ir64Op.VectorFpAcrossLanes}), por isso sem o `switch` esz==2/3 do
    /// resto desta classe.
    static boolean executeFpAcrossLanes(Aarch64Core core, Ir64Op.VectorFpAcrossLanes op) {
        Aarch64FpRegisters fp = core.fp();
        float result = Float.intBitsToFloat((int) fp.element(op.rn(), 0, 2));
        for (int i = 1; i < 4; i++) {
            float v = Float.intBitsToFloat((int) fp.element(op.rn(), i, 2));
            result = switch (op.op()) {
                case FMAXNMV -> Ir64FpExecutor.maxNum(result, v);
                case FMINNMV -> Ir64FpExecutor.minNum(result, v);
                case FMAXV -> Math.max(result, v);
                case FMINV -> Math.min(result, v);
            };
        }
        fp.setS(op.rd(), Float.floatToRawIntBits(result));
        return false;
    }
}
