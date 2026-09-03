package dev.vitorsilverio.armjitter.advsimd;

import java.math.BigInteger;

/// Núcleo vetorial COMPARTILHADO pelos dois pipelines (AArch64 `ir64`/`executor64` e o pipeline de
/// 32 bits `ir`/`codegen.executor`) — RFC B13.2, decisão D1 (reuso, não espelhamento).
///
/// Toda a semântica de lane (largura de elemento, extensão de sinal, truncamento) mora aqui e é
/// escrita UMA vez; o que fica com cada pipeline é só o que realmente difere entre as duas
/// arquiteturas: o encoding, a IR, o mapeamento registrador→palavra
/// ({@link AdvSimdRegisterWords}) e a disciplina de escrita do destino (o A64 zera os bits altos
/// do `V<n>`; o NEON de 32 bits escreve exatamente o `D`/`Q` nomeado).
///
/// Sem estado próprio (nenhuma operação toca memória ou registrador geral), métodos estáticos —
/// mesma forma de `Ir64VectorArithmeticExecutor`, do qual esta classe é a extração.
public final class AdvSimdLanes {
    private AdvSimdLanes() {
    }

    /// Bits de uma palavra do banco ({@link AdvSimdRegisterWords}).
    public static final int WORD_BITS = 64;

    /// Máscara de posição dentro de uma palavra de {@value #WORD_BITS} bits.
    private static final int WORD_BIT_MASK = WORD_BITS - 1;

    /// Máscara de um elemento de `1 << esz` bytes (`esz` `0`-`3`: byte/halfword/word/doubleword).
    public static long elementMask(int esz) {
        int bits = 8 << esz;
        return bits == WORD_BITS ? -1L : (1L << bits) - 1;
    }

    /// Sign-extende para `long` um elemento de `1 << esz` bytes já lido zero-extendido por
    /// {@link #element}.
    public static long signExtend(long value, int esz) {
        int bits = 8 << esz;
        if (bits == WORD_BITS) {
            return value;
        }
        int shift = WORD_BITS - bits;
        return (value << shift) >> shift;
    }

    /// Trunca `value` ao tamanho de um elemento de `1 << esz` bytes.
    public static long truncate(long value, int esz) {
        return value & elementMask(esz);
    }

    /// Máscara "todos-1" (do tamanho do elemento) ou `0` para o resultado de uma comparação de
    /// elemento (`VCGT`/`VCGE`/`VTST`/`VCEQ` e os `CM**_v` do A64).
    public static long boolMask(boolean condition, int esz) {
        return condition ? elementMask(esz) : 0L;
    }

    /// Multiplicação polinomial (`GF(2)`, `PMUL_v`/`VMUL.P8`, sempre `byte`): XOR de `a<<i` para
    /// cada bit `i` setado de `b`, sem carry — definição real de `PolynomialMult` do ARM ARM.
    /// Pública porque `PMULL`/`PMULL2` do A64 (B8.11, alargando sem truncar) reaproveita o mesmo
    /// produto de 15 bits.
    public static long polynomialMultiply8(long a, long b) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            if (((b >>> i) & 1) != 0) {
                result ^= a << i;
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Aritmética de SATURAÇÃO e deslocamento por registrador — B13.5 (migração D1 da RFC B13.2).
    // Cópias VERBATIM dos helpers homônimos de `executor64/Ir64VectorArithmeticExecutor` (B8.8/
    // B11.4): as 16 operações `SQADD`..`SQRDMLSH` que a B13.4 tinha deixado no `switch` A64 agora
    // vivem SÓ aqui. Nenhuma modela `FPSCR.QC`/`FPSR.QC` (só o VALOR saturado é observável —
    // paridade consciente com o A64, que nunca modelou o bit cumulativo). Toda a aritmética larga
    // usa {@link BigInteger} deliberadamente: estas ops caem no interpretador (nenhum `Kind`
    // vetorial entra em política ASM nativa), então exatidão pesa mais que velocidade.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /// Reinterpreta um `long` java (que pode ser negativo) como o inteiro NÃO ASSINADO de 64 bits
    /// que ele representa em complemento de dois — `esz=3` (doubleword) é o único tamanho em que um
    /// elemento não assinado pode ultrapassar {@link Long#MAX_VALUE}.
    private static BigInteger unsignedBig(long value) {
        return value >= 0 ? BigInteger.valueOf(value) : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /// Satura `value` (matemático, sem wraparound) ao intervalo representável por um elemento de
    /// `esz` bytes, assinado ou não.
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

    /// Deslocamento à esquerda seguro: Java `<<`/`>>>`/`>>` usam o deslocamento MOD 64 para `long`
    /// (`x << 64` == `x << 0`, não `0`) — guarda-corpo para quando o deslocamento pode chegar a 64
    /// (`esz=3`) ou, por registrador, a qualquer magnitude de um byte assinado.
    private static long safeShiftLeft(long value, int shift) {
        return shift >= 64 ? 0L : value << shift;
    }

    private static long logicalShiftRight(long value, int shift) {
        return shift >= 64 ? 0L : value >>> shift;
    }

    private static long arithmeticShiftRight(long value, int shift) {
        return shift >= 64 ? (value < 0 ? -1L : 0L) : value >> shift;
    }

    /// Deslocamento à direita com ARREDONDAMENTO (`round = 1 << (shift-1)` somado antes de deslocar)
    /// — em {@link BigInteger} para não lidar manualmente com o transbordo de 64 bits.
    private static long roundingShiftRight(long value, int shift, boolean signed) {
        if (shift <= 0) {
            return value;
        }
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        BigInteger round = BigInteger.ONE.shiftLeft(shift - 1);
        return v.add(round).shiftRight(shift).longValue();
    }

    /// Deslocamento à esquerda por quantidade VARIÁVEL com saturação ao tamanho do elemento.
    private static long saturatingShiftLeft(long value, int shift, int esz, boolean signed) {
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        return saturateToElement(v.shiftLeft(shift), esz, signed);
    }

    /// "Shift Left and Insert" (`SLI`): desloca `source` à esquerda por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits BAIXOS de `Rd` (o deslocamento já traz zeros nos bits
    /// baixos, então basta unir com a máscara dos bits preservados de `current`). Movido VERBATIM de
    /// `executor64/Ir64VectorArithmeticExecutor` em B13.7 (D1 da RFC B13.2).
    private static long insertShiftLeft(long current, long source, int shift) {
        long shifted = safeShiftLeft(source, shift);
        long preserveMask = shift <= 0 ? 0L : (shift >= 64 ? -1L : (1L << shift) - 1);
        return (current & preserveMask) | shifted;
    }

    /// "Shift Right and Insert" (`SRI`): desloca `source` à direita por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits ALTOS de `Rd` dentro da largura do elemento. Movido
    /// VERBATIM de `executor64/Ir64VectorArithmeticExecutor` em B13.7 (D1 da RFC B13.2).
    private static long insertShiftRight(long current, long source, int shift, int esz) {
        long shifted = logicalShiftRight(source, shift);
        int esize = 8 << esz;
        long mask = elementMask(esz);
        long preserveMask = shift >= esize ? mask : (mask & ~((1L << (esize - shift)) - 1));
        return (current & preserveMask) | shifted;
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

    /// Deslocamento por REGISTRADOR (`SSHL`/`USHL`/`SQSHL`/`UQSHL`/...): a quantidade é o BYTE BAIXO
    /// do elemento `Rm`, sempre — nunca `sext(Rm,esz)` (`ARM DDI 0487`, pseudocódigo de `SSHL`:
    /// `shift = SInt(Elem[m,e,8])`). `>=0` desloca à esquerda; `<0` desloca à direita com a
    /// MAGNITUDE.
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
    /// o lado direito (`amount<0`) é um deslocamento comum (com ou sem arredondamento), NUNCA satura.
    private static long saturatingShiftByRegister(long value, int amount, int esz, boolean signed, boolean rounding) {
        if (amount >= 0) {
            return saturatingShiftLeft(value, amount, esz, signed);
        }
        int magnitude = -amount;
        return rounding ? roundingShiftRight(value, magnitude, signed)
                : (signed ? arithmeticShiftRight(value, magnitude) : logicalShiftRight(value, magnitude));
    }

    /// Lê a lane `lane` (elemento de `1 << esz` bytes, lane `0` = bits menos significativos) do
    /// operando que começa na palavra `baseWord`, com zero-extend em um `long`.
    public static long element(AdvSimdRegisterWords regs, int baseWord, int lane, int esz) {
        int elementBits = 8 << esz;
        int bitOffset = lane * elementBits;
        long word = regs.word(baseWord + (bitOffset / WORD_BITS));
        return (word >>> (bitOffset & WORD_BIT_MASK)) & elementMask(esz);
    }

    /// Grava a lane `lane` do operando que começa na palavra `baseWord`, sem afetar nenhum outro
    /// bit do banco.
    public static void setElement(AdvSimdRegisterWords regs, int baseWord, int lane, int esz, long value) {
        int elementBits = 8 << esz;
        int bitOffset = lane * elementBits;
        int wordIndex = baseWord + (bitOffset / WORD_BITS);
        int shift = bitOffset & WORD_BIT_MASK;
        long mask = elementMask(esz);
        long current = regs.word(wordIndex);
        regs.setWord(wordIndex, (current & ~(mask << shift)) | ((value & mask) << shift));
    }

    /// Executa uma operação "three same" sobre `lanes` elementos de `1 << esz` bytes: cada lane de
    /// `baseRd` recebe `op` aplicada às lanes correspondentes de `baseRn` e `baseRm`. Os três
    /// `base*` são índices de PALAVRA (ver {@link AdvSimdRegisterWords}), não de registrador — é o
    /// chamador que traduz `V<n>`/`D<n>`/`Q<n>` para palavra.
    ///
    /// Nada aqui zera bits fora das lanes escritas: a escrita destrutiva do A64 (bits 127:64 do
    /// `V<n>` quando o arranjo tem 64 bits) é responsabilidade do executor daquele pipeline.
    public static void threeSame(AdvSimdRegisterWords regs, AdvSimdThreeSameOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        for (int i = 0; i < lanes; i++) {
            long a = element(regs, baseRn, i, esz);
            long b = element(regs, baseRm, i, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            // `d` só é lido pelas operações RMW (`SABA`/`UABA`/`MLA`/`MLS`/`BSL`/`BIT`/`BIF` e,
            // desde B13.5, `SQRDMLAH`/`SQRDMLSH`), elemento a elemento — mesmo comportamento
            // arquitetural do executor A64.
            long d = element(regs, baseRd, i, esz);
            long result = switch (op) {
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
                case SABA -> signExtend(d, esz) + Math.abs(sa - sb);
                case UABA -> d + (Long.compareUnsigned(a, b) >= 0 ? a - b : b - a);
                case MUL -> a * b;
                case PMUL -> polynomialMultiply8(a, b);
                case MLA -> d + a * b;
                case MLS -> d - a * b;
                case AND -> a & b;
                case BIC -> a & ~b;
                case ORR -> a | b;
                case ORN -> a | ~b;
                case EOR -> a ^ b;
                case BSL -> (d & a) | (~d & b);
                case BIT -> (a & b) | (d & ~b);
                case BIF -> (a & ~b) | (d & b);
                // B13.5 — saturantes / deslocamento por registrador (verbatim do A64):
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
                // RMW: acumula/subtrai a MESMA multiplicação dobrada arredondada de `SQRDMULH` sobre
                // o `Rd` ATUAL sign-extendido, com DUAS saturações independentes.
                case SQRDMLAH -> signedSaturatingAdd(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
                case SQRDMLSH -> signedSaturatingSub(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    /// Executa uma operação "pairwise" (ver {@link AdvSimdPairwiseOp}): concatena o operando que
    /// começa em `baseRn` com o que começa em `baseRm`, combina pares de elementos ADJACENTES
    /// nessa sequência e grava `lanes` resultados a partir de `baseRd` (os `lanes/2` primeiros
    /// vindos de `baseRn`, os demais de `baseRm`).
    ///
    /// Como no A64, os resultados são calculados num buffer ANTES de qualquer escrita — `baseRd`
    /// pode coincidir com `baseRn`/`baseRm`. Nenhuma escrita destrutiva fora das lanes: é do
    /// chamador (o A64 zera os bits altos do `V`; o VFP32 não).
    public static void pairwise(AdvSimdRegisterWords regs, AdvSimdPairwiseOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        int half = lanes / 2;
        long[] results = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            int base = i < half ? baseRn : baseRm;
            int pairBase = (i < half ? i : i - half) * 2;
            long a = element(regs, base, pairBase, esz);
            long b = element(regs, base, pairBase + 1, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            results[i] = switch (op) {
                case ADD -> a + b;
                case SMAX -> Math.max(sa, sb);
                case UMAX -> Long.compareUnsigned(a, b) >= 0 ? a : b;
                case SMIN -> Math.min(sa, sb);
                case UMIN -> Long.compareUnsigned(a, b) <= 0 ? a : b;
            };
        }
        for (int i = 0; i < lanes; i++) {
            setElement(regs, baseRd, i, esz, truncate(results[i], esz));
        }
    }

    /// Executa uma operação "shift by immediate" (ver {@link AdvSimdShiftImmediateOp}) sobre
    /// `lanes` elementos de `1 << esz` bytes: cada lane de `baseRd` recebe `op` aplicada à lane
    /// correspondente de `baseRn` (e do próprio `baseRd` para as formas RMW `SSRA`/`USRA`/`SRSRA`/
    /// `URSRA`/`SRI`/`SLI`, que ACUMULAM ou INSEREM no destino atual). `shift` já vem resolvido do
    /// decoder (`immh:immb`), na faixa `1..esize` para os deslocamentos à direita e `0..esize-1`
    /// para os à esquerda. Os `base*` são índices de PALAVRA (ver {@link AdvSimdRegisterWords}).
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeShiftImmediate` (B8.8) em
    /// B13.7 (D1 da RFC B13.2) — inclusive o tratamento especial de `SQSHLU` (fonte assinada,
    /// saturação NÃO assinada). Nada aqui zera bits fora das lanes escritas: a escrita destrutiva
    /// do A64 é do chamador.
    public static void shiftImmediate(AdvSimdRegisterWords regs, AdvSimdShiftImmediateOp op,
            int esz, int shift, int lanes, int baseRd, int baseRn) {
        for (int i = 0; i < lanes; i++) {
            long a = element(regs, baseRn, i, esz);
            long sa = signExtend(a, esz);
            long current = element(regs, baseRd, i, esz);
            long result = switch (op) {
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
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // PONTO FLUTUANTE — B13.6 (migração D1 da RFC B13.2). Primeiro caminho FP do núcleo. As ops
    // "three same"/"pairwise" de FP do A64 (B8.9) vivem SÓ aqui a partir desta task; o
    // `Ir64VectorFpArithmeticExecutor` só delega. Sem modelo de `FPSCR`/`FPCR` (RMode/FZ/exceções)
    // — paridade consciente com o escalar (B3.8/B8.5) e com o inteiro saturante acima (`QC`).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /// Bits crus (zero-estendidos a 64) de um `float` — usado para gravar uma lane F32.
    public static long floatBits(float value) {
        return Float.floatToRawIntBits(value) & 0xFFFF_FFFFL;
    }

    /// Bits crus de um `double` — usado para gravar uma lane F64.
    public static long doubleBits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    /// `FPMulX` (`ARM DDI 0487`): `0 * Infinito`/`Infinito * 0` devolve `2.0` com o sinal do
    /// produto dos operandos, em vez do `NaN` que a multiplicação IEEE normal produziria — único
    /// desvio de {@code a * b}.
    public static float mulX(float a, float b) {
        if ((a == 0f && Float.isInfinite(b)) || (Float.isInfinite(a) && b == 0f)) {
            int sign = (Float.floatToRawIntBits(a) ^ Float.floatToRawIntBits(b)) & Integer.MIN_VALUE;
            return Float.intBitsToFloat(sign | Float.floatToRawIntBits(2.0f));
        }
        return a * b;
    }

    /// @see #mulX(float, float)
    public static double mulX(double a, double b) {
        if ((a == 0.0 && Double.isInfinite(b)) || (Double.isInfinite(a) && b == 0.0)) {
            long sign = (Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b)) & Long.MIN_VALUE;
            return Double.longBitsToDouble(sign | Double.doubleToRawLongBits(2.0));
        }
        return a * b;
    }

    /// `FPMaxNum` (`ARM DDI 0487`): se exatamente um operando é `NaN`, devolve o OUTRO; se os dois
    /// são `NaN`, devolve `NaN`; senão, `Math.max` normal (mesma semântica de sinal de zero do
    /// `MAX`). `Ir64FpExecutor` (escalar) delega a este método.
    public static float maxNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.max(a, b);
    }

    /// @see #maxNum(float, float)
    public static double maxNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.max(a, b);
    }

    /// `FPMinNum` — espelho de {@link #maxNum(float, float)} com `Math.min`.
    public static float minNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.min(a, b);
    }

    /// @see #minNum(float, float)
    public static double minNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.min(a, b);
    }

    /// Executa uma operação "three same" de PONTO FLUTUANTE sobre `lanes` elementos de `1 << esz`
    /// bytes (`esz` `2` = F32, `3` = F64): cada lane de `baseRd` recebe `op` aplicada às lanes
    /// correspondentes de `baseRn`/`baseRm` (e do próprio `baseRd` para as formas RMW
    /// `MLA`/`MLS`/`FMLA`/`FMLS`). Os três `base*` são índices de PALAVRA.
    ///
    /// Nada aqui zera bits fora das lanes escritas: a escrita destrutiva do A64 é do chamador.
    /// Sem escrita destrutiva DENTRO do laço tampouco.
    public static void fpThreeSame(AdvSimdRegisterWords regs, AdvSimdFpThreeSameOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        for (int i = 0; i < lanes; i++) {
            long resultBits;
            if (esz == 2) {
                float a = Float.intBitsToFloat((int) element(regs, baseRn, i, esz));
                float b = Float.intBitsToFloat((int) element(regs, baseRm, i, esz));
                float current = Float.intBitsToFloat((int) element(regs, baseRd, i, esz));
                resultBits = switch (op) {
                    case ADD -> floatBits(a + b);
                    case SUB -> floatBits(a - b);
                    case MUL -> floatBits(a * b);
                    case DIV -> floatBits(a / b);
                    case MAX -> floatBits(Math.max(a, b));
                    case MIN -> floatBits(Math.min(a, b));
                    case MAXNM -> floatBits(maxNum(a, b));
                    case MINNM -> floatBits(minNum(a, b));
                    case MULX -> floatBits(mulX(a, b));
                    // NÃO fundido (dois arredondamentos) — `VMLA.F32`/`VMLS.F32` NEON.
                    case MLA -> floatBits(a * b + current);
                    case MLS -> floatBits(current - a * b);
                    // FUNDIDO (arredondamento único) — `VFMA.F32`/`VFMS.F32` e `FMLA_v`/`FMLS_v` A64.
                    case FMLA -> floatBits(Math.fma(a, b, current));
                    case FMLS -> floatBits(Math.fma(-a, b, current));
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
                double a = Double.longBitsToDouble(element(regs, baseRn, i, esz));
                double b = Double.longBitsToDouble(element(regs, baseRm, i, esz));
                double current = Double.longBitsToDouble(element(regs, baseRd, i, esz));
                resultBits = switch (op) {
                    case ADD -> doubleBits(a + b);
                    case SUB -> doubleBits(a - b);
                    case MUL -> doubleBits(a * b);
                    case DIV -> doubleBits(a / b);
                    case MAX -> doubleBits(Math.max(a, b));
                    case MIN -> doubleBits(Math.min(a, b));
                    case MAXNM -> doubleBits(maxNum(a, b));
                    case MINNM -> doubleBits(minNum(a, b));
                    case MULX -> doubleBits(mulX(a, b));
                    case MLA -> doubleBits(a * b + current);
                    case MLS -> doubleBits(current - a * b);
                    case FMLA -> doubleBits(Math.fma(a, b, current));
                    case FMLS -> doubleBits(Math.fma(-a, b, current));
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
            setElement(regs, baseRd, i, esz, resultBits);
        }
    }

    /// Executa uma operação "pairwise" de PONTO FLUTUANTE (ver {@link AdvSimdFpPairwiseOp}):
    /// concatena `baseRn` com `baseRm`, combina pares de elementos ADJACENTES nessa sequência e
    /// grava `lanes` resultados a partir de `baseRd` (os `lanes/2` primeiros vindos de `baseRn`, os
    /// demais de `baseRm`). Resultados calculados num buffer ANTES de qualquer escrita — `baseRd`
    /// pode coincidir com `baseRn`/`baseRm`. Sem escrita destrutiva: é do chamador.
    public static void fpPairwise(AdvSimdRegisterWords regs, AdvSimdFpPairwiseOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        int half = lanes / 2;
        long[] results = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            int base = i < half ? baseRn : baseRm;
            int pairBase = (i < half ? i : i - half) * 2;
            results[i] = fpCombinePair(op, element(regs, base, pairBase, esz),
                    element(regs, base, pairBase + 1, esz), esz);
        }
        for (int i = 0; i < lanes; i++) {
            setElement(regs, baseRd, i, esz, results[i]);
        }
    }

    /// Combina um par de elementos FP de tamanho `esz` (`2`/`3`) segundo `op` — mesma semântica nas
    /// formas vetorial e escalar: `MAX`/`MIN` propagam `NaN` (`Math.max`/`Math.min`), `MAXNM`/
    /// `MINNM` só quando os DOIS são `NaN`, `ADD` é `+` IEEE. Público para a forma ESCALAR do A64
    /// (`FADDP_s`/... , B19.2) reusar sem duplicar.
    public static long fpCombinePair(AdvSimdFpPairwiseOp op, long aBits, long bBits, int esz) {
        if (esz == 2) {
            float a = Float.intBitsToFloat((int) aBits);
            float b = Float.intBitsToFloat((int) bBits);
            return switch (op) {
                case ADD -> floatBits(a + b);
                case MAX -> floatBits(Math.max(a, b));
                case MIN -> floatBits(Math.min(a, b));
                case MAXNM -> floatBits(maxNum(a, b));
                case MINNM -> floatBits(minNum(a, b));
            };
        }
        double a = Double.longBitsToDouble(aBits);
        double b = Double.longBitsToDouble(bBits);
        return switch (op) {
            case ADD -> doubleBits(a + b);
            case MAX -> doubleBits(Math.max(a, b));
            case MIN -> doubleBits(Math.min(a, b));
            case MAXNM -> doubleBits(maxNum(a, b));
            case MINNM -> doubleBits(minNum(a, b));
        };
    }
}
