package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;

import java.math.BigInteger;

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

    /// Reinterpreta um `long` java (que pode ser negativo) como o inteiro NÃO ASSINADO de 64 bits
    /// que ele representa em complemento de dois — necessário porque `esz=3` (doubleword) é o
    /// único tamanho em que um elemento não assinado pode ultrapassar {@link Long#MAX_VALUE}, e daí
    /// pra frente toda a aritmética de saturação de B8.8 usa {@link BigInteger} deliberadamente
    /// (não é otimização prematura: `Kind`s desta task não entram em `Ir64NativePolicy`, caem no
    /// interpretador, então exatidão pesa mais que velocidade de bit a bit aqui).
    private static BigInteger unsignedBig(long value) {
        return value >= 0 ? BigInteger.valueOf(value) : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /// Satura `value` (matemático, sem wraparound) ao intervalo representável por um elemento de
    /// {@code esz} bytes, assinado ou não — usado por toda operação `SQ*`/`UQ*` desta task.
    private static long saturateToElement(BigInteger value, int esz, boolean signed) {
        int bits = 8 << esz;
        BigInteger max = signed
                ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger min = signed ? max.negate().subtract(BigInteger.ONE) : BigInteger.ZERO;
        if (value.compareTo(max) > 0) {
            return max.longValue();
        }
        if (value.compareTo(min) < 0) {
            return min.longValue();
        }
        return value.longValue();
    }

    private static long signedSaturatingAdd(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).add(BigInteger.valueOf(sb)), esz, true);
    }

    private static long signedSaturatingSub(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).subtract(BigInteger.valueOf(sb)), esz, true);
    }

    private static long unsignedSaturatingAdd(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).add(unsignedBig(b)), esz, false);
    }

    private static long unsignedSaturatingSub(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).subtract(unsignedBig(b)), esz, false);
    }

    /// Acumulação saturante em elemento ASSINADO com operando NÃO assinado (`SUQADD`).
    private static long signedAccumulateSaturating(long signedCurrent, long unsignedOperand, int esz) {
        return saturateToElement(BigInteger.valueOf(signedCurrent).add(unsignedBig(unsignedOperand)), esz, true);
    }

    /// Acumulação saturante em elemento NÃO assinado com operando ASSINADO (`USQADD`).
    private static long unsignedAccumulateSaturating(long unsignedCurrent, long signedOperand, int esz) {
        return saturateToElement(unsignedBig(unsignedCurrent).add(BigInteger.valueOf(signedOperand)), esz, false);
    }

    /// Deslocamento à esquerda seguro: Java `<<`/`>>>`/`>>` usam o deslocamento MOD 64 para `long`
    /// (`x << 64` == `x << 0`, não `0`) — esta e as duas próximas funções são o guarda-corpo contra
    /// esse comportamento sempre que o deslocamento pode chegar a `64` (só acontece com `esz=3`) ou,
    /// no caso do deslocamento POR REGISTRADOR, a qualquer magnitude de um byte assinado (`-128`..
    /// `127`).
    private static long safeShiftLeft(long value, int shift) {
        return shift >= 64 ? 0L : value << shift;
    }

    private static long logicalShiftRight(long value, int shift) {
        return shift >= 64 ? 0L : value >>> shift;
    }

    private static long arithmeticShiftRight(long value, int shift) {
        return shift >= 64 ? (value < 0 ? -1L : 0L) : value >> shift;
    }

    /// Deslocamento à direita com ARREDONDAMENTO (`round = 1 << (shift-1)` somado antes de
    /// deslocar) — em {@link BigInteger} para não ter que lidar manualmente com o transbordo de 64
    /// bits que a soma `value + round` pode causar quando `esz=3` (ver {@link #unsignedBig}).
    private static long roundingShiftRight(long value, int shift, boolean signed) {
        if (shift <= 0) {
            return value;
        }
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        BigInteger round = BigInteger.ONE.shiftLeft(shift - 1);
        return v.add(round).shiftRight(shift).longValue();
    }

    /// Deslocamento à esquerda por quantidade VARIÁVEL (`0`-`127`, deslocamento por registrador ou
    /// imediato) com saturação ao tamanho do elemento — {@link BigInteger} evita ter que truncar
    /// manualmente antes de saturar (deslocar um `long` de 64 bits por `> 64` bits já perde
    /// informação em Java antes mesmo da saturação rodar).
    private static long saturatingShiftLeft(long value, int shift, int esz, boolean signed) {
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        return saturateToElement(v.shiftLeft(shift), esz, signed);
    }

    /// Multiplicação dobrada de alta ordem saturante (`SQDMULH`/`SQRDMULH`) — `esize = 8<<esz`.
    private static long doublingMultiplyHigh(long sa, long sb, int esz, boolean rounding) {
        int esize = 8 << esz;
        BigInteger product = BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1);
        if (rounding) {
            product = product.add(BigInteger.ONE.shiftLeft(esize - 1));
        }
        return saturateToElement(product.shiftRight(esize), esz, true);
    }

    /// `2*sext(a)*sext(b)`, saturado ao tamanho `esz` (LARGO — `esz` aqui já é o `wideEsz` do
    /// chamador) — usado por `SQDMULL`/`SQDMLAL`/`SQDMLSL`.
    private static long saturatingDoublingProduct(long sa, long sb, int wideEsz) {
        return saturateToElement(BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1), wideEsz, true);
    }

    /// Deslocamento por REGISTRADOR (`SSHL`/`USHL`/`SQSHL`/`UQSHL`/...): a quantidade é o BYTE
    /// BAIXO do elemento `Rm`, sempre — nunca `sext(Rm,esz)` (`ARM DDI 0487`, pseudocódigo de
    /// `SSHL`: `shift = SInt(Elem[m,e,8])`). `>=0` desloca à esquerda; `<0` desloca à direita com a
    /// MAGNITUDE (`-shift`).
    private static int registerShiftAmount(long rmElement) {
        return (byte) rmElement;
    }

    private static long shiftByRegister(long value, int amount, boolean signed) {
        if (amount >= 0) {
            return safeShiftLeft(value, amount);
        }
        int magnitude = -amount;
        return signed ? arithmeticShiftRight(value, magnitude) : logicalShiftRight(value, magnitude);
    }

    private static long roundingShiftByRegister(long value, int amount, boolean signed) {
        if (amount >= 0) {
            return safeShiftLeft(value, amount);
        }
        return roundingShiftRight(value, -amount, signed);
    }

    /// `SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL` por registrador: só o lado ESQUERDO (`amount>=0`) satura;
    /// o lado direito (`amount<0`) é um deslocamento comum (com ou sem arredondamento conforme
    /// {@code rounding}), NUNCA satura (a magnitude só encolhe).
    private static long saturatingShiftByRegister(long value, int amount, int esz, boolean signed, boolean rounding) {
        if (amount >= 0) {
            return saturatingShiftLeft(value, amount, esz, signed);
        }
        int magnitude = -amount;
        return rounding ? roundingShiftRight(value, magnitude, signed)
                : (signed ? arithmeticShiftRight(value, magnitude) : logicalShiftRight(value, magnitude));
    }

    /// "Shift Left and Insert" (`SLI`): desloca `source` à esquerda por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits BAIXOS de `Rd` (o deslocamento já traz zeros nos bits
    /// baixos, então basta unir com a máscara dos bits preservados de `current`).
    private static long insertShiftLeft(long current, long source, int shift) {
        long shifted = safeShiftLeft(source, shift);
        long preserveMask = shift <= 0 ? 0L : (shift >= 64 ? -1L : (1L << shift) - 1);
        return (current & preserveMask) | shifted;
    }

    /// "Shift Right and Insert" (`SRI`): desloca `source` à direita por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits ALTOS de `Rd` dentro da largura do elemento.
    private static long insertShiftRight(long current, long source, int shift, int esz) {
        long shifted = logicalShiftRight(source, shift);
        int esize = 8 << esz;
        long mask = elementMask(esz);
        long preserveMask = shift >= esize ? mask : (mask & ~((1L << (esize - shift)) - 1));
        return (current & preserveMask) | shifted;
    }

    /// Escrita "SIMD&FP destructive": zera os bits altos de `rd` quando `!q` — a forma vetorial
    /// com `q=false` só escreveu os 64 bits baixos; a forma escalar (`esz=3`/`q=false` reaproveitado,
    /// ver javadoc do record) também passa por aqui.
    private static void finishDestructiveWrite(Aarch64FpRegisters fp, int rd, boolean q) {
        if (!q) {
            fp.setQ(rd, fp.low64(rd), 0L);
        }
    }

    /// Escrita destrutiva CIENTE de forma escalar (B8.8): quando {@code scalar}, zera TUDO acima
    /// de {@code esz} bits — inclusive dentro do `low64`, não só os 64 bits altos — porque a forma
    /// escalar de B8.8 processa um ÚNICO elemento que pode ser menor que 64 bits (`SQADD_s.B`,
    /// `SQSHL_s.H`, ...). `fp.element(rd,0,esz)` já devolve o elemento recém-escrito (zero-
    /// extendido a `esz` bits); usá-lo como o `low64` inteiro zera o resto automaticamente. Sem
    /// isto, `sqadd b0,...` deixaria lixo de uma escrita anterior mais larga nos bits `[63:8]` de
    /// `V0` — bug real que {@link #finishDestructiveWrite} (só zera `[127:64]`) não cobre quando
    /// `esz<3` (ver javadoc de {@link Ir64Op.VectorArithmeticThreeSame#scalar}).
    private static void finishScalarAwareWrite(Aarch64FpRegisters fp, int rd, boolean scalar, boolean q, int esz) {
        if (scalar) {
            fp.setQ(rd, fp.element(rd, 0, esz), 0L);
        } else {
            finishDestructiveWrite(fp, rd, q);
        }
    }

    static boolean executeThreeSame(Aarch64Core core, Ir64Op.VectorArithmeticThreeSame op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
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
                case SQADD -> signedSaturatingAdd(sa, sb, esz);
                case UQADD -> unsignedSaturatingAdd(a, b, esz);
                case SQSUB -> signedSaturatingSub(sa, sb, esz);
                case UQSUB -> unsignedSaturatingSub(a, b, esz);
                case SSHL -> shiftByRegister(sa, registerShiftAmount(b), true);
                case USHL -> shiftByRegister(a, registerShiftAmount(b), false);
                case SRSHL -> roundingShiftByRegister(sa, registerShiftAmount(b), true);
                case URSHL -> roundingShiftByRegister(a, registerShiftAmount(b), false);
                case SQSHL -> saturatingShiftByRegister(sa, registerShiftAmount(b), esz, true, false);
                case UQSHL -> saturatingShiftByRegister(a, registerShiftAmount(b), esz, false, false);
                case SQRSHL -> saturatingShiftByRegister(sa, registerShiftAmount(b), esz, true, true);
                case UQRSHL -> saturatingShiftByRegister(a, registerShiftAmount(b), esz, false, true);
                case SQDMULH -> doublingMultiplyHigh(sa, sb, esz, false);
                case SQRDMULH -> doublingMultiplyHigh(sa, sb, esz, true);
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
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
                // `SQDMULL`/`SQDMLAL`/`SQDMLSL` (B8.8): a MULTIPLICAÇÃO satura primeiro
                // (`SignedSaturate(2*sext(Rn)*sext(Rm))`), DEPOIS a soma/subtração satura de novo
                // — duas saturações independentes, conferido contra o pseudocódigo real.
                case SQDMULL -> saturatingDoublingProduct(sa, sb, wideEsz);
                case SQDMLAL -> signedSaturatingAdd(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
                case SQDMLSL -> signedSaturatingSub(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
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
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
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
                case SUQADD -> signedAccumulateSaturating(signExtend(fp.element(op.rd(), i, esz), esz), a, esz);
                case USQADD -> unsignedAccumulateSaturating(fp.element(op.rd(), i, esz), sa, esz);
                case SADDLP, UADDLP, SADALP, UADALP ->
                        throw new IllegalStateException("tratado no ramo widening acima");
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    static boolean executeScalarPairwiseAdd(Aarch64Core core, Ir64Op.VectorScalarPairwiseAdd op) {
        Aarch64FpRegisters fp = core.fp();
        long result = fp.element(op.rn(), 0, 3) + fp.element(op.rn(), 1, 3);
        fp.setD(op.rd(), result);
        return false;
    }

    /// `SQXTN`/`SQXTUN`/`UQXTN` (B8.8) — narrow unário saturante, vetorial e escalar (a forma
    /// escalar processa só o elemento `0`, ver {@link Ir64Op.VectorArithmeticNarrowUnary#scalar}).
    static boolean executeNarrowUnary(Aarch64Core core, Ir64Op.VectorArithmeticNarrowUnary op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int elements = op.scalar() ? 1 : elementsPerRegister(true, wideEsz);
        int laneOffset = op.scalar() ? 0 : (op.q() ? elements : 0);
        for (int i = 0; i < elements; i++) {
            long wide = fp.element(op.rn(), i, wideEsz);
            long signedWide = signExtend(wide, wideEsz);
            long narrow = switch (op.op()) {
                case SQXTN -> saturateToElement(BigInteger.valueOf(signedWide), esz, true);
                case SQXTUN -> saturateToElement(BigInteger.valueOf(signedWide), esz, false);
                case UQXTN -> saturateToElement(unsignedBig(wide), esz, false);
            };
            fp.setElement(op.rd(), laneOffset + i, esz, truncate(narrow, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `SSHR`/`USHR`/`SRSHR`/`URSHR`/`SSRA`/`USRA`/`SRSRA`/`URSRA`/`SRI`/`SHL`/`SLI`/`SQSHL`/
    /// `UQSHL`/`SQSHLU` (B8.8, "shift by immediate" não-largo/não-estreito), vetorial e escalar.
    static boolean executeShiftImmediate(Aarch64Core core, Ir64Op.VectorShiftImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int shift = op.shift();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long a = fp.element(op.rn(), i, esz);
            long sa = signExtend(a, esz);
            long current = fp.element(op.rd(), i, esz);
            long result = switch (op.op()) {
                case SSHR -> arithmeticShiftRight(sa, shift);
                case USHR -> logicalShiftRight(a, shift);
                case SRSHR -> roundingShiftRight(sa, shift, true);
                case URSHR -> roundingShiftRight(a, shift, false);
                case SSRA -> signExtend(current, esz) + arithmeticShiftRight(sa, shift);
                case USRA -> current + logicalShiftRight(a, shift);
                case SRSRA -> signExtend(current, esz) + roundingShiftRight(sa, shift, true);
                case URSRA -> current + roundingShiftRight(a, shift, false);
                case SRI -> insertShiftRight(current, a, shift, esz);
                case SHL -> safeShiftLeft(a, shift);
                case SLI -> insertShiftLeft(current, a, shift);
                case SQSHL -> saturatingShiftLeft(sa, shift, esz, true);
                case UQSHL -> saturatingShiftLeft(a, shift, esz, false);
                // `SQSHLU`: fonte ASSINADA (desloca como `sa`, não `a`) mas saturação NÃO
                // assinada — `saturatingShiftLeft` não serve aqui porque seu único parâmetro
                // `signed` governa as DUAS coisas (interpretação do deslocamento E sinal da
                // saturação), que para `SQSHLU` divergem de propósito (achado real ao testar:
                // `unsignedBig(sa=-1)` trataria `-1` como quase `2^64`, produzindo lixo em vez de
                // saturar em `0`).
                case SQSHLU -> saturateToElement(BigInteger.valueOf(sa).shiftLeft(shift), esz, false);
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `SHRN`/`RSHRN`/`SQSHRN`/`UQSHRN`/`SQSHRUN`/`SQRSHRN`/`UQRSHRN`/`SQRSHRUN` (B8.8, "shift by
    /// immediate" estreitando).
    static boolean executeShiftNarrowImmediate(Aarch64Core core, Ir64Op.VectorShiftNarrowImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int shift = op.shift();
        int elements = op.scalar() ? 1 : elementsPerRegister(true, wideEsz);
        int laneOffset = op.scalar() ? 0 : (op.q() ? elements : 0);
        for (int i = 0; i < elements; i++) {
            long wide = fp.element(op.rn(), i, wideEsz);
            long signedWide = signExtend(wide, wideEsz);
            long narrow = switch (op.op()) {
                case SHRN -> logicalShiftRight(wide, shift);
                case RSHRN -> roundingShiftRight(wide, shift, false);
                case SQSHRN -> saturateToElement(BigInteger.valueOf(arithmeticShiftRight(signedWide, shift)), esz, true);
                case UQSHRN -> saturateToElement(BigInteger.valueOf(logicalShiftRight(wide, shift)), esz, false);
                case SQSHRUN -> saturateToElement(BigInteger.valueOf(arithmeticShiftRight(signedWide, shift)), esz, false);
                case SQRSHRN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(signedWide, shift, true)), esz, true);
                case UQRSHRN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(wide, shift, false)), esz, false);
                case SQRSHRUN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(signedWide, shift, true)), esz, false);
            };
            fp.setElement(op.rd(), laneOffset + i, esz, truncate(narrow, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `SSHLL`/`USHLL` (B8.8, "shift by immediate" alargando) — sempre preenche os 128 bits
    /// inteiros de `Rd`, sem saturar (o valor alargado sempre cabe no container maior).
    static boolean executeShiftWidenImmediate(Aarch64Core core, Ir64Op.VectorShiftWidenImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int shift = op.shift();
        int outputElements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? outputElements : 0;
        for (int i = 0; i < outputElements; i++) {
            long narrow = fp.element(op.rn(), laneOffset + i, esz);
            long extended = op.op() == Ir64VectorShiftWidenOp.SSHLL ? signExtend(narrow, esz) : narrow;
            long result = safeShiftLeft(extended, shift);
            fp.setElement(op.rd(), i, wideEsz, truncate(result, wideEsz));
        }
        return false;
    }

    /// `EXT` (B8.10) — concatena `Rm:Rn` (`Rn` nos bytes BAIXOS) e extrai a janela de
    /// {@code datasize} bytes começando em {@link Ir64Op.VectorExtract#imm}, byte a byte (sempre
    /// `esz=0`, sem aritmética).
    static boolean executeExtract(Aarch64Core core, Ir64Op.VectorExtract op) {
        Aarch64FpRegisters fp = core.fp();
        int datasize = op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES;
        long resultLo = 0L;
        long resultHi = 0L;
        for (int i = 0; i < datasize; i++) {
            int srcIndex = op.imm() + i;
            long byteValue = srcIndex < datasize
                    ? fp.element(op.rn(), srcIndex, 0)
                    : fp.element(op.rm(), srcIndex - datasize, 0);
            if (i < Aarch64FpRegisters.DOUBLEWORD_BYTES) {
                resultLo |= byteValue << (i * 8);
            } else {
                resultHi |= byteValue << ((i - Aarch64FpRegisters.DOUBLEWORD_BYTES) * 8);
            }
        }
        fp.setQ(op.rd(), resultLo, resultHi);
        return false;
    }

    /// `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2` (B8.10) — reorganiza os elementos de `Rn`/`Rm`,
    /// sem aritmética. `pairIndex`/`secondOfPair` decompõem o índice de saída `i` no par
    /// `(par, metade)` que `TRN*`/`ZIP*` precisam; `UZP*` usa `i` diretamente (metade do registro
    /// inteira vem de `Rn`, a outra de `Rm`).
    static boolean executePermute(Aarch64Core core, Ir64Op.VectorPermute op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        int half = elements / 2;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int pairIndex = i / 2;
            boolean secondOfPair = (i % 2) == 1;
            results[i] = switch (op.op()) {
                case UZP1 -> fp.element(i < half ? op.rn() : op.rm(), 2 * (i < half ? i : i - half), esz);
                case UZP2 -> fp.element(i < half ? op.rn() : op.rm(), 2 * (i < half ? i : i - half) + 1, esz);
                case TRN1 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex * 2, esz);
                case TRN2 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex * 2 + 1, esz);
                case ZIP1 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex, esz);
                case ZIP2 -> fp.element(secondOfPair ? op.rm() : op.rn(), half + pairIndex, esz);
            };
        }
        for (int i = 0; i < elements; i++) {
            fp.setElement(op.rd(), i, esz, results[i]);
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    /// `TBL`/`TBX` (B8.10) — trata `Rn`, `Rn+1`, ..., `Rn+len` (módulo
    /// {@link Aarch64FpRegisters#V_REGISTER_COUNT}) como uma tabela contígua de bytes; cada byte de
    /// `Rm` é um índice nessa tabela. Índice fora da tabela: `0` (`TBL`) ou o byte ATUAL de `Rd`
    /// (`TBX`) — lido ANTES da escrita final, já que {@link Aarch64FpRegisters#setQ} substitui os
    /// 128 bits de uma vez.
    static boolean executeTableLookup(Aarch64Core core, Ir64Op.VectorTableLookup op) {
        Aarch64FpRegisters fp = core.fp();
        int tableBytes = (op.len() + 1) * Aarch64FpRegisters.QUADWORD_BYTES;
        int indexCount = op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES;
        long resultLo = 0L;
        long resultHi = 0L;
        for (int i = 0; i < indexCount; i++) {
            int index = (int) fp.element(op.rm(), i, 0);
            long value;
            if (index < tableBytes) {
                int tableReg = (op.rn() + index / Aarch64FpRegisters.QUADWORD_BYTES) % Aarch64FpRegisters.V_REGISTER_COUNT;
                int byteInReg = index % Aarch64FpRegisters.QUADWORD_BYTES;
                value = fp.element(tableReg, byteInReg, 0);
            } else if (op.tbx()) {
                value = fp.element(op.rd(), i, 0);
            } else {
                value = 0L;
            }
            if (i < Aarch64FpRegisters.DOUBLEWORD_BYTES) {
                resultLo |= value << (i * 8);
            } else {
                resultHi |= value << ((i - Aarch64FpRegisters.DOUBLEWORD_BYTES) * 8);
            }
        }
        fp.setQ(op.rd(), resultLo, resultHi);
        return false;
    }
}
