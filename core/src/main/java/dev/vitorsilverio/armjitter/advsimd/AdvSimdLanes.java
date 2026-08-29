package dev.vitorsilverio.armjitter.advsimd;

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
            // `d` só é lido pelas operações RMW (`SABA`/`UABA`/`MLA`/`MLS`/`BSL`/`BIT`/`BIF`),
            // elemento a elemento — mesmo comportamento arquitetural do executor A64.
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
