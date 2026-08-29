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
            long result = switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }
}
