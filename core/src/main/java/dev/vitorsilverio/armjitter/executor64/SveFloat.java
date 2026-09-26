package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

import java.math.BigInteger;

/// Ponto flutuante IEEE 754 EXATO das instruções FP do SVE (B17.13): meia (`esz = 1`), simples (`2`) e dupla
/// precisão (`3`), com `FPCR.RMode`, `FPCR.FZ`/`FZ16`, `FPCR.DN` e as flags cumulativas do `FPSR`.
///
/// O núcleo `float`/`double` do JDK não serve aqui: só arredonda para o mais próximo e não diz quais exceções
/// ocorreram. Toda operação calcula o resultado EXATO (`BigInteger`) e o arredonda uma única vez
/// ({@link #round}), o que dá os quatro modos de arredondamento e a detecção precisa de inexato/overflow/
/// underflow. `FPCR`/`FPSR` são os mesmos registradores do A64 (`Aarch64Core`), lidos uma vez por instrução
/// ({@link Env#of}) e escritos uma vez ({@link Env#commit}) — só se alguma flag foi levantada.
///
/// Semântica transcrita do pseudocódigo do Arm ARM (`FPAdd`, `FPMul`, `FPDiv`, `FPMulAdd`, `FPMax`,
/// `FPMaxNum`, `FPMulX`, `FPScale`, `FPRecipStep`, `FPRSqrtStep`, `FPRecipEstimate`, `FPRSqrtEstimate`) e
/// conferida contra `target/arm/vfp_helper.c`/`helper-a64.c` do QEMU. **Não modelado:** `FPCR.AH`
/// (`FEAT_AFP`) e os bits de habilitação de trap (`IOE`/`DZE`/…) — pendências nomeadas da B17.13.
final class SveFloat {
    // ── Flags do FPSR ────────────────────────────────────────────────────────────────────────────
    static final int FLAG_IOC = 1;
    static final int FLAG_DZC = 1 << 1;
    static final int FLAG_OFC = 1 << 2;
    static final int FLAG_UFC = 1 << 3;
    static final int FLAG_IXC = 1 << 4;
    static final int FLAG_IDC = 1 << 7;

    // ── Campos do FPCR ───────────────────────────────────────────────────────────────────────────
    private static final int FPCR_RMODE_SHIFT = 22;
    private static final int FPCR_RMODE_MASK = 0b11;
    private static final int FPCR_FZ_BIT = 24;
    private static final int FPCR_DN_BIT = 25;
    private static final int FPCR_FZ16_BIT = 19;

    // ── Modos de arredondamento (FPCR.RMode) ─────────────────────────────────────────────────────
    static final int RMODE_NEAREST = 0;
    static final int RMODE_PLUS_INFINITY = 1;
    static final int RMODE_MINUS_INFINITY = 2;
    static final int RMODE_ZERO = 3;

    static final int ESZ_HALF = 1;
    static final int ESZ_SINGLE = 2;
    static final int ESZ_DOUBLE = 3;

    private static final int HALF_EXP_BITS = 5;
    private static final int HALF_FRAC_BITS = 10;
    private static final int SINGLE_EXP_BITS = 8;
    private static final int SINGLE_FRAC_BITS = 23;
    private static final int DOUBLE_EXP_BITS = 11;
    private static final int DOUBLE_FRAC_BITS = 52;
    /// Limite do expoente de `FSCALE`: além dele qualquer formato já estourou/zerou.
    private static final int SCALE_CLAMP = 1 << 12;

    // Limiares de `FRECPE` (bits de `|x|` abaixo dos quais o inverso estoura) e de flush-to-zero do resultado.
    private static final long RECPE_OVERFLOW_HALF = 1L << 8;
    private static final long RECPE_OVERFLOW_SINGLE = 1L << 21;
    private static final long RECPE_OVERFLOW_DOUBLE = 1L << 50;
    private static final int RECPE_FLUSH_EXP_HALF = 29;
    private static final int RECPE_FLUSH_EXP_SINGLE = 253;
    private static final int RECPE_FLUSH_EXP_DOUBLE = 2045;
    private static final int RSQRTE_EXP_OFFSET_HALF = 44;
    private static final int RSQRTE_EXP_OFFSET_SINGLE = 380;
    private static final int RSQRTE_EXP_OFFSET_DOUBLE = 3068;

    private static final int ESTIMATE_BITS = 8;
    private static final long ESTIMATE_MASK = (1L << ESTIMATE_BITS) - 1L;
    private static final int ESTIMATE_FRAC_SHIFT = 44;
    private static final int SCALED_HIGH_BIT = 1 << ESTIMATE_BITS;
    private static final int SCALED_ODD_HIGH_BIT = 1 << (ESTIMATE_BITS - 1);
    private static final int DOUBLE_TOP_FRAC_BIT = 51;

    private static final int DIVISION_GUARD_BITS = 3;

    private SveFloat() {
    }

    // ── Ambiente ─────────────────────────────────────────────────────────────────────────────────

    /// Ambiente de ponto flutuante de UMA instrução: formato do elemento, `FPCR` congelado e flags acumuladas.
    static final class Env {
        final int esz;
        final int expBits;
        final int fracBits;
        final int bias;
        final int maxBiasedExponent;
        final long fracMask;
        final int rmode;
        final boolean flushToZero;
        final boolean defaultNan;
        int flags;

        private Env(int esz, long fpcr) {
            this.esz = esz;
            this.expBits = switch (esz) {
                case ESZ_HALF -> HALF_EXP_BITS;
                case ESZ_SINGLE -> SINGLE_EXP_BITS;
                default -> DOUBLE_EXP_BITS;
            };
            this.fracBits = switch (esz) {
                case ESZ_HALF -> HALF_FRAC_BITS;
                case ESZ_SINGLE -> SINGLE_FRAC_BITS;
                default -> DOUBLE_FRAC_BITS;
            };
            this.bias = (1 << (expBits - 1)) - 1;
            this.maxBiasedExponent = (1 << expBits) - 1;
            this.fracMask = (1L << fracBits) - 1L;
            this.rmode = (int) ((fpcr >>> FPCR_RMODE_SHIFT) & FPCR_RMODE_MASK);
            // Meia precisão obedece a FZ16; simples/dupla, a FZ (e só elas levantam IDC ao achatar a entrada).
            this.flushToZero = ((fpcr >>> (esz == ESZ_HALF ? FPCR_FZ16_BIT : FPCR_FZ_BIT)) & 1L) != 0L;
            this.defaultNan = ((fpcr >>> FPCR_DN_BIT) & 1L) != 0L;
        }

        /// Lê o `FPCR` do core e monta o ambiente de um elemento de tamanho `esz` (`1`-`3`).
        static Env of(Aarch64Core core, int esz) {
            return new Env(esz, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR));
        }

        /// Ambiente sobre um `FPCR` explícito (testes e usos sem core).
        static Env ofFpcr(long fpcr, int esz) {
            return new Env(esz, fpcr);
        }

        /// Acumula as flags levantadas em `FPSR` (só escreve se houve alguma).
        void commit(Aarch64Core core) {
            if (flags != 0) {
                long fpsr = core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR);
                core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, fpsr | flags);
            }
        }

        private long signBit() {
            return 1L << (expBits + fracBits);
        }

        private long quietBit() {
            return 1L << (fracBits - 1);
        }

        long defaultNanBits() {
            return ((long) maxBiasedExponent << fracBits) | quietBit();
        }

        long infinity(boolean sign) {
            return ((long) maxBiasedExponent << fracBits) | (sign ? signBit() : 0L);
        }

        long zero(boolean sign) {
            return sign ? signBit() : 0L;
        }

        long maxFinite(boolean sign) {
            return (((long) maxBiasedExponent - 1L) << fracBits) | fracMask | (sign ? signBit() : 0L);
        }

        /// `2^exponent` (com `bias` somado) — as constantes `0.5`/`1.0`/`2.0`/`3.0`/`1.5` do formato.
        long power(int exponent) {
            return (long) (bias + exponent) << fracBits;
        }

        long half() {
            return power(-1);
        }

        long one() {
            return power(0);
        }

        long two() {
            return power(1);
        }

        long three() {
            return power(1) | quietBit();
        }

        long onePointFive() {
            return power(0) | quietBit();
        }

        long negate(long bits) {
            return bits ^ signBit();
        }

        long abs(long bits) {
            return bits & ~signBit();
        }

        boolean isNegative(long bits) {
            return (bits & signBit()) != 0L;
        }

        boolean isNaN(long bits) {
            return ((bits >>> fracBits) & maxBiasedExponent) == maxBiasedExponent && (bits & fracMask) != 0L;
        }

        long withSign(long bits, boolean sign) {
            return sign ? (bits | signBit()) : (bits & ~signBit());
        }
    }

    // ── Valor desempacotado ──────────────────────────────────────────────────────────────────────

    private enum Kind { ZERO, FINITE, INFINITY, QUIET_NAN, SIGNALING_NAN }

    /// `valor = (-1)^sign × mantissa × 2^exponent` quando `FINITE`.
    private record Value(Kind kind, boolean sign, long mantissa, int exponent, long bits) {
        boolean isNaN() {
            return kind == Kind.QUIET_NAN || kind == Kind.SIGNALING_NAN;
        }
    }

    /// `FPUnpack`: entrada denormal vira zero (com o sinal) sob `FZ`/`FZ16`; em simples/dupla isso levanta `IDC`.
    private static Value unpack(long bits, Env env) {
        long frac = bits & env.fracMask;
        int biased = (int) ((bits >>> env.fracBits) & env.maxBiasedExponent);
        boolean sign = env.isNegative(bits);
        if (biased == env.maxBiasedExponent) {
            if (frac == 0L) {
                return new Value(Kind.INFINITY, sign, 0L, 0, bits);
            }
            boolean quiet = (frac & env.quietBit()) != 0L;
            return new Value(quiet ? Kind.QUIET_NAN : Kind.SIGNALING_NAN, sign, 0L, 0, bits);
        }
        if (biased == 0) {
            if (frac == 0L) {
                return new Value(Kind.ZERO, sign, 0L, 0, bits);
            }
            if (env.flushToZero) {
                if (env.esz != ESZ_HALF) {
                    env.flags |= FLAG_IDC;
                }
                return new Value(Kind.ZERO, sign, 0L, 0, bits);
            }
            return new Value(Kind.FINITE, sign, frac, 1 - env.bias - env.fracBits, bits);
        }
        return new Value(Kind.FINITE, sign, frac | (1L << env.fracBits), biased - env.bias - env.fracBits, bits);
    }

    /// `float*_squash_input_denormal`: o mesmo achatamento de {@link #unpack}, mas devolvendo bits.
    private static long squash(long bits, Env env) {
        Value v = unpack(bits, env);
        return v.kind == Kind.ZERO ? env.zero(v.sign) : bits;
    }

    // ── NaN ──────────────────────────────────────────────────────────────────────────────────────

    /// `FPProcessNaN`: SNaN levanta `IOC`; o resultado é o NaN de entrada silenciado, ou o NaN padrão sob `DN`.
    private static long processNaN(Value nan, Env env) {
        if (nan.kind == Kind.SIGNALING_NAN) {
            env.flags |= FLAG_IOC;
        }
        return env.defaultNan ? env.defaultNanBits() : (nan.bits | env.quietBit());
    }

    private static long invalidOperation(Env env) {
        env.flags |= FLAG_IOC;
        return env.defaultNanBits();
    }

    /// `FPProcessNaNs`: NaN sinalizante antes de silencioso, na ordem dos operandos. `null` = sem NaN.
    private static Long processNaNs(Env env, Value... operands) {
        for (Value v : operands) {
            if (v.kind == Kind.SIGNALING_NAN) {
                return processNaN(v, env);
            }
        }
        for (Value v : operands) {
            if (v.kind == Kind.QUIET_NAN) {
                return processNaN(v, env);
            }
        }
        return null;
    }

    // ── Arredondamento ───────────────────────────────────────────────────────────────────────────

    /// Arredonda `±num × 2^exp2` (`num > 0`; com `sticky`, um resto positivo abaixo de `2^exp2`) para o formato.
    /// `FPRound`: achata resultado minúsculo sob `FZ` (`UFC`, sem `IXC`), estoura para infinito/maior finito
    /// conforme o modo (`OFC`+`IXC`), marca `IXC` no inexato e `UFC` no minúsculo-antes-do-arredondamento inexato.
    private static long round(Env env, boolean sign, BigInteger num, int exp2, boolean sticky) {
        int precision = env.fracBits + 1;
        int minNormalExponent = 1 - env.bias;
        int minQuantumExponent = minNormalExponent - env.fracBits;
        int topExponent = exp2 + num.bitLength() - 1;
        boolean tiny = topExponent < minNormalExponent;
        if (tiny && env.flushToZero) {
            env.flags |= FLAG_UFC;
            return env.zero(sign);
        }
        int quantum = Math.max(topExponent - precision + 1, minQuantumExponent);
        int shift = quantum - exp2;
        BigInteger kept;
        boolean inexact;
        int againstHalf; // resto contra a metade do último bit: -1 abaixo, 0 exatamente, 1 acima
        if (shift <= 0) {
            kept = num.shiftLeft(-shift);
            inexact = sticky;
            againstHalf = -1;
        } else {
            kept = num.shiftRight(shift);
            BigInteger remainder = num.subtract(kept.shiftLeft(shift));
            int c = remainder.compareTo(BigInteger.ONE.shiftLeft(shift - 1));
            inexact = remainder.signum() != 0 || sticky;
            againstHalf = c > 0 ? 1 : c < 0 ? -1 : (sticky ? 1 : 0);
        }
        boolean up = switch (env.rmode) {
            case RMODE_NEAREST -> againstHalf > 0 || (againstHalf == 0 && kept.testBit(0));
            case RMODE_PLUS_INFINITY -> inexact && !sign;
            case RMODE_MINUS_INFINITY -> inexact && sign;
            default -> false;
        };
        if (up) {
            kept = kept.add(BigInteger.ONE);
        }
        if (kept.bitLength() > precision) {
            kept = kept.shiftRight(1);
            quantum++;
        }
        long significand = kept.longValue();
        int biased = (significand >>> env.fracBits) != 0L ? quantum + env.fracBits + env.bias : 0;
        if (biased >= env.maxBiasedExponent) {
            env.flags |= FLAG_OFC | FLAG_IXC;
            return overflowsToInfinity(env.rmode, sign) ? env.infinity(sign) : env.maxFinite(sign);
        }
        if (inexact) {
            env.flags |= FLAG_IXC;
            if (tiny) {
                env.flags |= FLAG_UFC;
            }
        }
        return (sign ? env.signBit() : 0L) | ((long) biased << env.fracBits) | (significand & env.fracMask);
    }

    private static boolean overflowsToInfinity(int rmode, boolean sign) {
        return switch (rmode) {
            case RMODE_NEAREST -> true;
            case RMODE_PLUS_INFINITY -> !sign;
            case RMODE_MINUS_INFINITY -> sign;
            default -> false;
        };
    }

    /// Sinal de uma soma exatamente zero de operandos não nulos ou de zeros de sinais diferentes.
    private static boolean exactZeroSign(Env env) {
        return env.rmode == RMODE_MINUS_INFINITY;
    }

    private static BigInteger signed(boolean sign, long mantissa, int shift) {
        BigInteger magnitude = BigInteger.valueOf(mantissa).shiftLeft(shift);
        return sign ? magnitude.negate() : magnitude;
    }

    // ── Aritmética básica ────────────────────────────────────────────────────────────────────────

    /// `FPAdd`/`FPSub` (`subtract` = soma com o segundo operando de sinal trocado; um NaN não muda de sinal).
    static long add(long aBits, long bBits, boolean subtract, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        Long nan = processNaNs(env, a, b);
        if (nan != null) {
            return nan;
        }
        boolean signB = b.sign != subtract;
        if (a.kind == Kind.INFINITY && b.kind == Kind.INFINITY && a.sign != signB) {
            return invalidOperation(env);
        }
        if (a.kind == Kind.INFINITY) {
            return env.infinity(a.sign);
        }
        if (b.kind == Kind.INFINITY) {
            return env.infinity(signB);
        }
        if (a.kind == Kind.ZERO && b.kind == Kind.ZERO) {
            return env.zero(a.sign == signB ? a.sign : exactZeroSign(env));
        }
        int exponent = Math.min(a.kind == Kind.ZERO ? b.exponent : a.exponent, b.kind == Kind.ZERO ? a.exponent : b.exponent);
        BigInteger sum = signed(a.sign, a.mantissa, a.kind == Kind.ZERO ? 0 : a.exponent - exponent)
                .add(signed(signB, b.mantissa, b.kind == Kind.ZERO ? 0 : b.exponent - exponent));
        if (sum.signum() == 0) {
            return env.zero(exactZeroSign(env));
        }
        return round(env, sum.signum() < 0, sum.abs(), exponent, false);
    }

    /// `FPMul`.
    static long multiply(long aBits, long bBits, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        Long nan = processNaNs(env, a, b);
        if (nan != null) {
            return nan;
        }
        boolean sign = a.sign != b.sign;
        boolean infinity = a.kind == Kind.INFINITY || b.kind == Kind.INFINITY;
        boolean zero = a.kind == Kind.ZERO || b.kind == Kind.ZERO;
        if (infinity && zero) {
            return invalidOperation(env);
        }
        if (infinity) {
            return env.infinity(sign);
        }
        if (zero) {
            return env.zero(sign);
        }
        return round(env, sign, BigInteger.valueOf(a.mantissa).multiply(BigInteger.valueOf(b.mantissa)),
                a.exponent + b.exponent, false);
    }

    /// `FPDiv`.
    static long divide(long aBits, long bBits, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        Long nan = processNaNs(env, a, b);
        if (nan != null) {
            return nan;
        }
        boolean sign = a.sign != b.sign;
        boolean bothInfinite = a.kind == Kind.INFINITY && b.kind == Kind.INFINITY;
        boolean bothZero = a.kind == Kind.ZERO && b.kind == Kind.ZERO;
        if (bothInfinite || bothZero) {
            return invalidOperation(env);
        }
        if (a.kind == Kind.INFINITY || b.kind == Kind.ZERO) {
            if (b.kind == Kind.ZERO) {
                env.flags |= FLAG_DZC;
            }
            return env.infinity(sign);
        }
        if (a.kind == Kind.ZERO || b.kind == Kind.INFINITY) {
            return env.zero(sign);
        }
        int quotientBits = env.fracBits + 1 + DIVISION_GUARD_BITS;
        int extra = Math.max(0, quotientBits + Long.SIZE - Long.numberOfLeadingZeros(b.mantissa)
                - (Long.SIZE - Long.numberOfLeadingZeros(a.mantissa)));
        BigInteger[] qr = BigInteger.valueOf(a.mantissa).shiftLeft(extra).divideAndRemainder(BigInteger.valueOf(b.mantissa));
        return round(env, sign, qr[0], a.exponent - b.exponent - extra, qr[1].signum() != 0);
    }

    /// `FPMulAdd` com um só arredondamento: `(a × b + c) × 2^scale`. `scale` existe para o `FRSQRTS`
    /// (`muladd_scalbn(…, -1)` do QEMU: a divisão por 2 entra ANTES do arredondamento).
    static long fusedMultiplyAdd(long cBits, long aBits, long bBits, int scale, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        Value c = unpack(cBits, env);
        boolean infZero = (a.kind == Kind.INFINITY && b.kind == Kind.ZERO) || (a.kind == Kind.ZERO && b.kind == Kind.INFINITY);
        Long nan = processNaNs(env, a, b, c);
        if (nan != null) {
            // `FPMulAdd`: addendo silencioso com produto `∞ × 0` é operação inválida (NaN padrão).
            return c.kind == Kind.QUIET_NAN && infZero ? invalidOperation(env) : nan;
        }
        boolean signP = a.sign != b.sign;
        boolean infP = a.kind == Kind.INFINITY || b.kind == Kind.INFINITY;
        boolean zeroP = a.kind == Kind.ZERO || b.kind == Kind.ZERO;
        if (infZero || (c.kind == Kind.INFINITY && infP && c.sign != signP)) {
            return invalidOperation(env);
        }
        if (c.kind == Kind.INFINITY) {
            return env.infinity(c.sign);
        }
        if (infP) {
            return env.infinity(signP);
        }
        if (c.kind == Kind.ZERO && zeroP) {
            return env.zero(c.sign == signP ? c.sign : exactZeroSign(env));
        }
        BigInteger product = zeroP ? BigInteger.ZERO
                : BigInteger.valueOf(a.mantissa).multiply(BigInteger.valueOf(b.mantissa));
        int productExponent = zeroP ? 0 : a.exponent + b.exponent;
        int exponent = zeroP ? c.exponent : c.kind == Kind.ZERO ? productExponent : Math.min(productExponent, c.exponent);
        BigInteger sum = (signP ? product.negate() : product).shiftLeft(zeroP ? 0 : productExponent - exponent)
                .add(c.kind == Kind.ZERO ? BigInteger.ZERO : signed(c.sign, c.mantissa, c.exponent - exponent));
        if (sum.signum() == 0) {
            return env.zero(exactZeroSign(env));
        }
        return round(env, sum.signum() < 0, sum.abs(), exponent + scale, false);
    }

    // ── Máximo/mínimo ────────────────────────────────────────────────────────────────────────────

    /// `FPMax`/`FPMin`: propaga NaN; entre `+0` e `-0` o máximo é `+0` e o mínimo `-0`.
    static long maxMin(long aBits, long bBits, boolean max, Env env) {
        return maxMin(unpack(aBits, env), unpack(bBits, env), max, env);
    }

    private static long maxMin(Value a, Value b, boolean max, Env env) {
        Long nan = processNaNs(env, a, b);
        if (nan != null) {
            return nan;
        }
        if (a.kind == Kind.ZERO && b.kind == Kind.ZERO && a.sign != b.sign) {
            return env.zero(!max);
        }
        int comparison = compare(a, b);
        Value winner = (max ? comparison > 0 : comparison < 0) ? a : b;
        return winner.kind == Kind.ZERO ? env.zero(winner.sign) : winner.bits;
    }

    /// `FPMaxNum`/`FPMinNum`: um único NaN silencioso vale `-∞` (máximo) ou `+∞` (mínimo).
    static long maxMinNumber(long aBits, long bBits, boolean max, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        Value substitute = new Value(Kind.INFINITY, max, 0L, 0, env.infinity(max));
        if (a.kind == Kind.QUIET_NAN && b.kind != Kind.QUIET_NAN) {
            a = substitute;
        } else if (a.kind != Kind.QUIET_NAN && b.kind == Kind.QUIET_NAN) {
            b = substitute;
        }
        return maxMin(a, b, max, env);
    }

    /// `FAMAX`/`FAMIN` (`FEAT_FAMINMAX`): compara por magnitude e devolve o operando ORIGINAL do vencedor —
    /// a mesma semântica de {@code AdvSimdLanes#fpAbsoluteMaxMin} (B19.24); empate de magnitude cai no
    /// `FPMax`/`FPMin` com sinal.
    static long absoluteMaxMin(long aBits, long bBits, boolean max, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        if (!a.isNaN() && !b.isNaN()) {
            int magnitude = compareMagnitude(a, b);
            if (magnitude != 0) {
                Value winner = (magnitude > 0) == max ? a : b;
                return winner.kind == Kind.ZERO ? env.zero(winner.sign) : winner.bits;
            }
        }
        return maxMin(a, b, max, env);
    }

    /// Ordem de valores (`-1`, `0`, `1`) entre operandos não-NaN.
    private static int compare(Value a, Value b) {
        int classA = valueClass(a);
        int classB = valueClass(b);
        if (classA != classB) {
            return Integer.compare(classA, classB);
        }
        if (a.kind != Kind.FINITE) {
            return 0;
        }
        int magnitude = compareMagnitude(a, b);
        return a.sign ? -magnitude : magnitude;
    }

    private static int valueClass(Value v) {
        return switch (v.kind) {
            case INFINITY -> v.sign ? -2 : 2;
            case ZERO -> 0;
            default -> v.sign ? -1 : 1;
        };
    }

    private static int compareMagnitude(Value a, Value b) {
        if (a.kind == Kind.INFINITY || b.kind == Kind.INFINITY) {
            return Boolean.compare(a.kind == Kind.INFINITY, b.kind == Kind.INFINITY);
        }
        if (a.kind == Kind.ZERO || b.kind == Kind.ZERO) {
            return Boolean.compare(a.kind != Kind.ZERO, b.kind != Kind.ZERO);
        }
        int exponent = Math.min(a.exponent, b.exponent);
        return BigInteger.valueOf(a.mantissa).shiftLeft(a.exponent - exponent)
                .compareTo(BigInteger.valueOf(b.mantissa).shiftLeft(b.exponent - exponent));
    }

    // ── Demais operações ─────────────────────────────────────────────────────────────────────────

    /// `FABD`: `|a − b|` (com `FPCR.AH = 0` o bit de sinal cai também num resultado NaN).
    static long absoluteDifference(long aBits, long bBits, Env env) {
        return env.abs(add(aBits, bBits, true, env));
    }

    /// `FMULX`: `0 × ∞` vale `±2.0` (sinal do produto) em vez de NaN.
    static long multiplyExtended(long aBits, long bBits, Env env) {
        Value a = unpack(aBits, env);
        Value b = unpack(bBits, env);
        if (!a.isNaN() && !b.isNaN()
                && ((a.kind == Kind.INFINITY && b.kind == Kind.ZERO) || (a.kind == Kind.ZERO && b.kind == Kind.INFINITY))) {
            return env.withSign(env.two(), a.sign != b.sign);
        }
        return multiply(aBits, bBits, env);
    }

    /// `FSCALE`: `a × 2^n`, com `n` o inteiro COM SINAL do elemento correspondente do segundo vetor.
    static long scale(long aBits, long exponent, Env env) {
        Value a = unpack(aBits, env);
        if (a.isNaN()) {
            return processNaN(a, env);
        }
        if (a.kind == Kind.INFINITY) {
            return env.infinity(a.sign);
        }
        if (a.kind == Kind.ZERO) {
            return env.zero(a.sign);
        }
        int clamped = (int) Math.max(-SCALE_CLAMP, Math.min(SCALE_CLAMP, exponent));
        return round(env, a.sign, BigInteger.valueOf(a.mantissa), a.exponent + clamped, false);
    }

    /// `FTSMUL`: `a × a` com o bit de sinal tomado do bit 0 de `b` (exceto em NaN).
    static long trigonometricStartingValue(long aBits, long bBits, Env env) {
        long square = multiply(aBits, aBits, env);
        return env.isNaN(square) ? square : env.withSign(square, (bBits & 1L) != 0L);
    }

    /// `FRECPS`: `2 − a × b`, fundido; `∞ × 0` vale `2.0`. O `a` é negado ANTES (como o QEMU: um NaN em `a`
    /// sai com o sinal trocado).
    static long reciprocalStep(long aBits, long bBits, Env env) {
        long a = env.negate(squash(aBits, env));
        long b = squash(bBits, env);
        if (isInfinityTimesZero(a, b, env)) {
            return env.two();
        }
        return fusedMultiplyAdd(env.two(), a, b, 0, env);
    }

    /// `FRSQRTS`: `(3 − a × b) / 2`, fundido; `∞ × 0` vale `1.5`.
    static long reciprocalSquareRootStep(long aBits, long bBits, Env env) {
        long a = env.negate(squash(aBits, env));
        long b = squash(bBits, env);
        if (isInfinityTimesZero(a, b, env)) {
            return env.onePointFive();
        }
        return fusedMultiplyAdd(env.three(), a, b, -1, env);
    }

    private static boolean isInfinityTimesZero(long a, long b, Env env) {
        Value va = unpack(a, env);
        Value vb = unpack(b, env);
        return (va.kind == Kind.INFINITY && vb.kind == Kind.ZERO) || (va.kind == Kind.ZERO && vb.kind == Kind.INFINITY);
    }

    // ── Estimativas (tabelas do manual) ──────────────────────────────────────────────────────────

    /// `RecipEstimate` do manual: entrada de 9 bits em `256..511`, resultado em `256..511`.
    private static int recipEstimate(int input) {
        int a = input * 2 + 1;
        int b = (1 << 19) / a;
        return (b + 1) >> 1;
    }

    /// `RecipSqrtEstimate` do manual: entrada em `128..511`, resultado em `256..511`.
    private static int recipSqrtEstimate(int input) {
        int a = input;
        if (a < 256) {
            a = a * 2 + 1;
        } else {
            a = (a >> 1) << 1;
            a = (a + 1) * 2;
        }
        int b = 512;
        while (a * (b + 1) * (b + 1) < (1 << 28)) {
            b++;
        }
        return (b + 1) / 2;
    }

    private static boolean roundsToInfinity(Env env, boolean sign) {
        return switch (env.rmode) {
            case RMODE_NEAREST -> true;
            case RMODE_PLUS_INFINITY -> !sign;
            case RMODE_MINUS_INFINITY -> sign;
            default -> false;
        };
    }

    /// `FRECPE`: estimativa de 8 bits do inverso (`FPRecipEstimate`).
    static long reciprocalEstimate(long inputBits, Env env) {
        long bits = squash(inputBits, env);
        boolean sign = env.isNegative(bits);
        int exponent = (int) ((bits >>> env.fracBits) & env.maxBiasedExponent);
        long frac = bits & env.fracMask;
        if (env.isNaN(bits)) {
            return processNaN(new Value((bits & env.quietBit()) != 0L ? Kind.QUIET_NAN : Kind.SIGNALING_NAN, sign, 0L, 0, bits), env);
        }
        if (exponent == env.maxBiasedExponent) {
            return env.zero(sign);
        }
        if (exponent == 0 && frac == 0L) {
            env.flags |= FLAG_DZC;
            return env.infinity(sign);
        }
        if (env.abs(bits) < recipOverflowLimit(env)) {
            env.flags |= FLAG_OFC | FLAG_IXC;
            return roundsToInfinity(env, sign) ? env.infinity(sign) : env.maxFinite(sign);
        }
        int flushExponent = env.esz == ESZ_HALF ? RECPE_FLUSH_EXP_HALF
                : env.esz == ESZ_SINGLE ? RECPE_FLUSH_EXP_SINGLE : RECPE_FLUSH_EXP_DOUBLE;
        if (exponent >= flushExponent && env.flushToZero) {
            env.flags |= FLAG_UFC;
            return env.zero(sign);
        }
        long frac64 = frac << (DOUBLE_FRAC_BITS - env.fracBits);
        if (exponent == 0) {
            if ((frac64 >>> DOUBLE_TOP_FRAC_BIT & 1L) == 0L) {
                exponent = -1;
                frac64 <<= 2;
            } else {
                frac64 <<= 1;
            }
        }
        int scaled = SCALED_HIGH_BIT | (int) ((frac64 >>> ESTIMATE_FRAC_SHIFT) & ESTIMATE_MASK);
        long resultFrac = ((long) recipEstimate(scaled) & ESTIMATE_MASK) << ESTIMATE_FRAC_SHIFT;
        int resultExponent = flushExponent - exponent;
        if (resultExponent == 0) {
            resultFrac = (resultFrac >>> 1) | (1L << DOUBLE_TOP_FRAC_BIT);
        } else if (resultExponent == -1) {
            resultFrac = ((resultFrac >>> 2) & ~(3L << (DOUBLE_TOP_FRAC_BIT - 1))) | (1L << (DOUBLE_TOP_FRAC_BIT - 1));
            resultExponent = 0;
        }
        return (sign ? env.signBit() : 0L) | ((long) resultExponent << env.fracBits)
                | ((resultFrac >>> (DOUBLE_FRAC_BITS - env.fracBits)) & env.fracMask);
    }

    private static long recipOverflowLimit(Env env) {
        return switch (env.esz) {
            case ESZ_HALF -> RECPE_OVERFLOW_HALF;
            case ESZ_SINGLE -> RECPE_OVERFLOW_SINGLE;
            default -> RECPE_OVERFLOW_DOUBLE;
        };
    }

    /// `FRSQRTE`: estimativa de 8 bits da raiz inversa (`FPRSqrtEstimate`).
    static long reciprocalSquareRootEstimate(long inputBits, Env env) {
        long bits = squash(inputBits, env);
        boolean sign = env.isNegative(bits);
        int exponent = (int) ((bits >>> env.fracBits) & env.maxBiasedExponent);
        long frac = bits & env.fracMask;
        if (env.isNaN(bits)) {
            return processNaN(new Value((bits & env.quietBit()) != 0L ? Kind.QUIET_NAN : Kind.SIGNALING_NAN, sign, 0L, 0, bits), env);
        }
        if (exponent == 0 && frac == 0L) {
            env.flags |= FLAG_DZC;
            return env.infinity(sign);
        }
        if (sign) {
            return invalidOperation(env);
        }
        if (exponent == env.maxBiasedExponent) {
            return env.zero(false);
        }
        int offset = env.esz == ESZ_HALF ? RSQRTE_EXP_OFFSET_HALF
                : env.esz == ESZ_SINGLE ? RSQRTE_EXP_OFFSET_SINGLE : RSQRTE_EXP_OFFSET_DOUBLE;
        long frac64 = frac << (DOUBLE_FRAC_BITS - env.fracBits);
        if (exponent == 0) {
            while ((frac64 >>> DOUBLE_TOP_FRAC_BIT & 1L) == 0L) {
                frac64 <<= 1;
                exponent--;
            }
            frac64 = (frac64 & ((1L << DOUBLE_TOP_FRAC_BIT) - 1L)) << 1;
        }
        int scaled = (exponent & 1) != 0
                ? SCALED_ODD_HIGH_BIT | (int) ((frac64 >>> (ESTIMATE_FRAC_SHIFT + 1)) & (ESTIMATE_MASK >>> 1))
                : SCALED_HIGH_BIT | (int) ((frac64 >>> ESTIMATE_FRAC_SHIFT) & ESTIMATE_MASK);
        long estimate = (long) recipSqrtEstimate(scaled) & ESTIMATE_MASK;
        int resultExponent = (offset - exponent) / 2;
        return ((long) resultExponent << env.fracBits) | (estimate << (env.fracBits - ESTIMATE_BITS));
    }

}
