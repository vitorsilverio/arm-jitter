package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
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

    /// Mapeia a operação FP do record A64 para a do núcleo COMPARTILHADO. O multiply-accumulate do
    /// A64 (`MLA`/`MLS` vetorial) é sempre FUNDIDO → {@link AdvSimdFpThreeSameOp#FMLA}/{@code FMLS}
    /// (as constantes NÃO fundidas do núcleo só o NEON A32 produz).
    private static AdvSimdFpThreeSameOp sharedFpThreeSameOp(Ir64VectorFpThreeSameOp op) {
        return switch (op) {
            case ADD -> AdvSimdFpThreeSameOp.ADD;
            case SUB -> AdvSimdFpThreeSameOp.SUB;
            case MUL -> AdvSimdFpThreeSameOp.MUL;
            case DIV -> AdvSimdFpThreeSameOp.DIV;
            case MAX -> AdvSimdFpThreeSameOp.MAX;
            case MIN -> AdvSimdFpThreeSameOp.MIN;
            case MAXNM -> AdvSimdFpThreeSameOp.MAXNM;
            case MINNM -> AdvSimdFpThreeSameOp.MINNM;
            case MULX -> AdvSimdFpThreeSameOp.MULX;
            case MLA -> AdvSimdFpThreeSameOp.FMLA;
            case MLS -> AdvSimdFpThreeSameOp.FMLS;
            case CMEQ -> AdvSimdFpThreeSameOp.CMEQ;
            case CMGE -> AdvSimdFpThreeSameOp.CMGE;
            case CMGT -> AdvSimdFpThreeSameOp.CMGT;
            case FACGE -> AdvSimdFpThreeSameOp.FACGE;
            case FACGT -> AdvSimdFpThreeSameOp.FACGT;
            case ABD -> AdvSimdFpThreeSameOp.ABD;
            case RECPS -> AdvSimdFpThreeSameOp.RECPS;
            case RSQRTS -> AdvSimdFpThreeSameOp.RSQRTS;
        };
    }

    private static AdvSimdFpPairwiseOp sharedFpPairwiseOp(Ir64VectorFpPairwiseOp op) {
        return switch (op) {
            case ADD -> AdvSimdFpPairwiseOp.ADD;
            case MAX -> AdvSimdFpPairwiseOp.MAX;
            case MIN -> AdvSimdFpPairwiseOp.MIN;
            case MAXNM -> AdvSimdFpPairwiseOp.MAXNM;
            case MINNM -> AdvSimdFpPairwiseOp.MINNM;
        };
    }

    static boolean executeThreeSame(Aarch64Core core, Ir64Op.VectorFpArithmeticThreeSame op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        // Toda a semântica de lane FP vive no núcleo COMPARTILHADO desde B13.6 (D1 da RFC B13.2) —
        // a MESMA função que o NEON de 32 bits chama. A escrita destrutiva de `[127:64]`/escalar
        // continua sendo do lado A64.
        AdvSimdLanes.fpThreeSame(fp, sharedFpThreeSameOp(op.op()), esz, elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
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
                    case MUL -> AdvSimdLanes.floatBits(a * b);
                    case MULX -> AdvSimdLanes.floatBits(AdvSimdLanes.mulX(a, b));
                    case MLA -> AdvSimdLanes.floatBits(Math.fma(a, b, current));
                    case MLS -> AdvSimdLanes.floatBits(Math.fma(-a, b, current));
                    default -> throw new IllegalStateException(
                            "Ir64VectorFpThreeSameOp não suportado em by-element: " + op.op());
                };
            } else {
                double a = Double.longBitsToDouble(fp.element(op.rn(), i, esz));
                double b = Double.longBitsToDouble(fp.element(op.rm(), op.index(), esz));
                double current = Double.longBitsToDouble(fp.element(op.rd(), i, esz));
                resultBits = switch (op.op()) {
                    case MUL -> AdvSimdLanes.doubleBits(a * b);
                    case MULX -> AdvSimdLanes.doubleBits(AdvSimdLanes.mulX(a, b));
                    case MLA -> AdvSimdLanes.doubleBits(Math.fma(a, b, current));
                    case MLS -> AdvSimdLanes.doubleBits(Math.fma(-a, b, current));
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
        AdvSimdFpPairwiseOp shared = sharedFpPairwiseOp(op.op());
        if (op.scalar()) {
            // B19.2: `FADDP_s`/`FMAXP_s`/... — reduz `Rn` lanes `0`/`1` a `Rd` lane `0`. Usa
            // `fpCombinePair` (núcleo) para não duplicar a semântica de `NaN`/sinal-de-zero.
            long result = AdvSimdLanes.fpCombinePair(shared,
                    fp.element(op.rn(), 0, esz), fp.element(op.rn(), 1, esz), esz);
            fp.setElement(op.rd(), 0, esz, result);
            finishScalarAwareWrite(fp, op.rd(), true, false, esz);
            return false;
        }
        int elements = elementsPerRegister(op.q(), esz);
        AdvSimdLanes.fpPairwise(fp, shared, esz, elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
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
        // B19.3: `FCVTXN` (só forma escalar nesta task) tem `esz` de ENTRADA (`f64`) ≠ `esz` de
        // SAÍDA (`f32`) — leitura, escrita e finalização PRÓPRIAS, fora do laço comum (Armadilha 3
        // da task: não deixar a finalização geral rodar de novo com o `esz` do record).
        if (op.op() == Ir64VectorFpUnaryOp.FCVTXN) {
            long narrowed = fcvtxnRoundToOdd(Double.longBitsToDouble(fp.element(op.rn(), 0, 3)));
            fp.setElement(op.rd(), 0, FCVTXN_OUTPUT_ESZ, narrowed);
            finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), FCVTXN_OUTPUT_ESZ);
            return false;
        }
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long inputBits = fp.element(op.rn(), i, esz);
            long resultBits = switch (op.op()) {
                case FRECPX -> esz == 2
                        ? AdvSimdLanes.floatBits(frecpx(Float.intBitsToFloat((int) inputBits)))
                        : AdvSimdLanes.doubleBits(frecpx(Double.longBitsToDouble(inputBits)));
                case FCVTXN -> throw new IllegalStateException("FCVTXN tratado fora do laço");
                case SCVTF, UCVTF -> {
                    boolean signed = op.op() == Ir64VectorFpUnaryOp.SCVTF;
                    double asDouble;
                    if (wide) {
                        asDouble = signed ? (double) inputBits : Ir64FpExecutor.unsignedLongToDouble(inputBits);
                    } else {
                        asDouble = signed ? (double) (int) inputBits : (double) inputBits;
                    }
                    yield esz == 2 ? AdvSimdLanes.floatBits((float) asDouble) : AdvSimdLanes.doubleBits(asDouble);
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
                            case ABS -> AdvSimdLanes.floatBits(Float.intBitsToFloat((int) inputBits & Integer.MAX_VALUE));
                            case NEG -> AdvSimdLanes.floatBits(Float.intBitsToFloat((int) inputBits ^ Integer.MIN_VALUE));
                            case SQRT -> AdvSimdLanes.floatBits((float) Math.sqrt(a));
                            case RECPE -> AdvSimdLanes.floatBits(1.0f / a);
                            case RSQRTE -> AdvSimdLanes.floatBits((float) (1.0 / Math.sqrt(a)));
                            case CMGT0 -> AdvSimdLanes.boolMask(a > 0f, esz);
                            case CMGE0 -> AdvSimdLanes.boolMask(a >= 0f, esz);
                            case CMEQ0 -> AdvSimdLanes.boolMask(a == 0f, esz);
                            case CMLE0 -> AdvSimdLanes.boolMask(a <= 0f, esz);
                            case CMLT0 -> AdvSimdLanes.boolMask(a < 0f, esz);
                            case RINTN, RINTM, RINTP, RINTZ, RINTA, RINTX, RINTI ->
                                    AdvSimdLanes.floatBits((float) Ir64FpExecutor.roundToIntegral(a, RINT_DIRECTION_BY_OP[op.op().ordinal()]));
                            default -> throw new IllegalStateException("tratado no ramo de conversão acima");
                        };
                    }
                    double a = Double.longBitsToDouble(inputBits);
                    yield switch (op.op()) {
                        case ABS -> AdvSimdLanes.doubleBits(Double.longBitsToDouble(inputBits & Long.MAX_VALUE));
                        case NEG -> AdvSimdLanes.doubleBits(Double.longBitsToDouble(inputBits ^ Long.MIN_VALUE));
                        case SQRT -> AdvSimdLanes.doubleBits(Math.sqrt(a));
                        case RECPE -> AdvSimdLanes.doubleBits(1.0 / a);
                        case RSQRTE -> AdvSimdLanes.doubleBits(1.0 / Math.sqrt(a));
                        case CMGT0 -> AdvSimdLanes.boolMask(a > 0.0, esz);
                        case CMGE0 -> AdvSimdLanes.boolMask(a >= 0.0, esz);
                        case CMEQ0 -> AdvSimdLanes.boolMask(a == 0.0, esz);
                        case CMLE0 -> AdvSimdLanes.boolMask(a <= 0.0, esz);
                        case CMLT0 -> AdvSimdLanes.boolMask(a < 0.0, esz);
                        case RINTN, RINTM, RINTP, RINTZ, RINTA, RINTX, RINTI ->
                                AdvSimdLanes.doubleBits(Ir64FpExecutor.roundToIntegral(a, RINT_DIRECTION_BY_OP[op.op().ordinal()]));
                        default -> throw new IllegalStateException("tratado no ramo de conversão acima");
                    };
                }
            };
            fp.setElement(op.rd(), i, esz, resultBits);
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `esz` de saída de {@link Ir64VectorFpUnaryOp#FCVTXN} — sempre `f32` (2), independente do
    /// `esz` de entrada (`f64`, 3) que viaja no record.
    private static final int FCVTXN_OUTPUT_ESZ = 2;

    /// `FPRecpX` (`ARM DDI 0487`, B19.3) para `float` — sinal preservado, mantissa zerada,
    /// expoente refletido `newExp = maxExp - exp` (biased; `maxExp` para `exp==0`/subnormal, com
    /// `maxExp = 2^8 - 2`). NaN → NaN default; `±0` → `±Infinito`; `±Infinito` → `±0`. EXATO, sem
    /// `FPCR`.
    private static float frecpx(float a) {
        int bits = Float.floatToRawIntBits(a);
        int sign = bits & 0x8000_0000;
        int exp = (bits >>> 23) & 0xFF;
        int mant = bits & 0x007F_FFFF;
        if (exp == 0xFF) {
            // NaN → NaN default (quiet, mantissa mínima); Infinito → zero do mesmo sinal.
            return mant != 0 ? Float.intBitsToFloat(0x7FC0_0000) : Float.intBitsToFloat(sign);
        }
        if (exp == 0 && mant == 0) {
            return Float.intBitsToFloat(sign | (0xFF << 23));
        }
        int maxExp = 0xFE;
        int newExp = exp == 0 ? maxExp : maxExp - exp;
        return Float.intBitsToFloat(sign | (newExp << 23));
    }

    /// `FPRecpX` (`ARM DDI 0487`, B19.3) para `double` — idêntico a {@link #frecpx(float)} com
    /// `maxExp = 2^11 - 2` e campo de expoente de 11 bits.
    private static double frecpx(double a) {
        long bits = Double.doubleToRawLongBits(a);
        long sign = bits & Long.MIN_VALUE;
        int exp = (int) ((bits >>> 52) & 0x7FF);
        long mant = bits & 0x000F_FFFF_FFFF_FFFFL;
        if (exp == 0x7FF) {
            return mant != 0 ? Double.longBitsToDouble(0x7FF8_0000_0000_0000L) : Double.longBitsToDouble(sign);
        }
        if (exp == 0 && mant == 0) {
            return Double.longBitsToDouble(sign | (0x7FFL << 52));
        }
        int maxExp = 0x7FE;
        int newExp = exp == 0 ? maxExp : maxExp - exp;
        return Double.longBitsToDouble(sign | ((long) newExp << 52));
    }

    /// `FCVTXN` (`ARM DDI 0487`, B19.3) — `f64` → `f32` com arredondamento "round to odd"
    /// (jamming): converte por truncamento em magnitude e, se a conversão perdeu informação, força
    /// o bit menos significativo da mantissa do `f32` a `1` (indo para o vizinho de maior
    /// magnitude quando já era `0`). Impede o arredondamento duplo de `(float) d`. Devolve os 32
    /// bits do `f32` (zero-estendidos). `NaN` → NaN default; `±Infinito` → `±Infinito`;
    /// `±0` → `±0`.
    private static long fcvtxnRoundToOdd(double d) {
        if (Double.isNaN(d)) {
            return 0x7FC0_0000L;
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? 0x7F80_0000L : 0xFF80_0000L;
        }
        float truncated = truncateToFloatTowardZero(d);
        if ((double) truncated == d) {
            return Float.floatToRawIntBits(truncated) & 0xFFFF_FFFFL;
        }
        int bits = Float.floatToRawIntBits(truncated);
        if ((bits & 1) == 0) {
            // Vizinho de MAIOR magnitude — que tem LSB `1` (representáveis consecutivos alternam
            // paridade de LSB).
            truncated = truncated >= 0f ? Math.nextUp(truncated) : Math.nextDown(truncated);
        }
        return Float.floatToRawIntBits(truncated) & 0xFFFF_FFFFL;
    }

    /// `double` → `float` truncado em MAGNITUDE (toward-zero): `(float) d` arredonda para o mais
    /// próximo; se estourou para longe de zero, recua um `ulp`.
    private static float truncateToFloatTowardZero(double d) {
        float nearest = (float) d;
        if (Math.abs((double) nearest) <= Math.abs(d)) {
            return nearest;
        }
        return d >= 0 ? Math.nextDown(nearest) : Math.nextUp(nearest);
    }

    /// `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` na forma AdvSIMD FP↔ponto fixo (`@fcvt_fixed`, B19.3) —
    /// reaproveita {@link Ir64FpExecutor#roundToIntegralForConversion}/
    /// {@link Ir64FpExecutor#saturateToInteger}/{@link Ir64FpExecutor#unsignedLongToDouble}
    /// (IDÊNTICO ao que {@link #executeUnary} faz nas conversões `_vi`), só com o fator de escala
    /// `2^fractionBits` a mais. Serve tanto a forma ESCALAR (B19.3, `op.scalar()`) quanto a VETORIAL
    /// `_vf` (B19.4, `!op.scalar()`, `q` real) — a única diferença é `elements` e a finalização.
    static boolean executeConvertFixedPoint(Aarch64Core core, Ir64Op.VectorFpConvertFixedPoint op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        boolean wide = esz == 3;
        double scale = Math.scalb(1.0, op.fractionBits());
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long inputBits = fp.element(op.rn(), i, esz);
            long resultBits;
            if (op.toFloat()) {
                double asDouble;
                if (wide) {
                    asDouble = op.signed() ? (double) inputBits : Ir64FpExecutor.unsignedLongToDouble(inputBits);
                } else {
                    asDouble = op.signed() ? (double) (int) inputBits : (double) inputBits;
                }
                double scaled = asDouble / scale;
                resultBits = esz == 2 ? AdvSimdLanes.floatBits((float) scaled) : AdvSimdLanes.doubleBits(scaled);
            } else {
                double value = esz == 2 ? Float.intBitsToFloat((int) inputBits) : Double.longBitsToDouble(inputBits);
                double scaled = value * scale;
                double rounded = Ir64FpExecutor.roundToIntegralForConversion(scaled,
                        Ir64Op.Fp64RoundingDirection.TOWARD_ZERO);
                long converted = Ir64FpExecutor.saturateToInteger(rounded, op.signed(), wide);
                resultBits = converted & (wide ? -1L : 0xFFFF_FFFFL);
            }
            fp.setElement(op.rd(), i, esz, resultBits);
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `FCVTL`/`FCVTN`/`FCVTXN` (AdvSIMD conversão de PRECISÃO vetorial, B19.4). {@code op.esz()} é
    /// sempre o lado ESTREITO; o largo é `op.esz() + 1`. Espelha o par
    /// {@link Ir64VectorArithmeticExecutor#executeShiftWidenImmediate}/`executeShiftNarrowImmediate`
    /// (B8.8), incluindo a convenção de {@code laneOffset} (na FONTE ao alargar, no DESTINO ao
    /// estreitar) e a finalização (só ao estreitar — alargar escreve os 128 bits inteiros). Os
    /// resultados são bufferizados num `long[]` antes de qualquer escrita, porque `Rd` pode ser `Rn`
    /// e ao alargar a escrita de `Rd[0]` (largo) cobre `Rn[0]` E `Rn[1]` (estreitos).
    static boolean executeConvertPrecision(Aarch64Core core, Ir64Op.VectorFpConvertPrecision op) {
        Aarch64FpRegisters fp = core.fp();
        int narrowEsz = op.esz();
        int wideEsz = narrowEsz + 1;
        if (op.op() == Ir64VectorFpConvertPrecisionOp.FCVTL) {
            int outputElements = elementsPerRegister(true, wideEsz);
            int laneOffset = op.q() ? outputElements : 0;
            long[] widened = new long[outputElements];
            for (int i = 0; i < outputElements; i++) {
                long src = fp.element(op.rn(), laneOffset + i, narrowEsz);
                widened[i] = narrowEsz == 1
                        ? AdvSimdLanes.floatBits(Float.float16ToFloat((short) src))
                        : AdvSimdLanes.doubleBits((double) Float.intBitsToFloat((int) src));
            }
            for (int i = 0; i < outputElements; i++) {
                fp.setElement(op.rd(), i, wideEsz, widened[i]);
            }
            return false;
        }
        int elements = elementsPerRegister(false, narrowEsz);
        int laneOffset = op.q() ? elements : 0;
        long[] narrowed = new long[elements];
        for (int i = 0; i < elements; i++) {
            long src = fp.element(op.rn(), i, wideEsz);
            narrowed[i] = switch (op.op()) {
                case FCVTN -> narrowEsz == 1
                        ? (Float.floatToFloat16(Float.intBitsToFloat((int) src)) & 0xFFFFL)
                        : AdvSimdLanes.floatBits((float) Double.longBitsToDouble(src));
                case FCVTXN -> fcvtxnRoundToOdd(Double.longBitsToDouble(src));
                case FCVTL -> throw new IllegalStateException("FCVTL tratado no ramo que alarga");
            };
        }
        for (int i = 0; i < elements; i++) {
            fp.setElement(op.rd(), laneOffset + i, narrowEsz, narrowed[i]);
        }
        finishScalarAwareWrite(fp, op.rd(), false, op.q(), narrowEsz);
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
