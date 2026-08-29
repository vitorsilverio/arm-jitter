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
}
