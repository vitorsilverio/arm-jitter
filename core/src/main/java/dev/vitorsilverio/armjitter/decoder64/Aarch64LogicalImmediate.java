package dev.vitorsilverio.armjitter.decoder64;

/// `DecodeBitMasks` (`ARM DDI 0487`, pseudocódigo de referência) — decodifica o campo `dbm`
/// (`N:immr:imms`) do encoding `Logical (immediate)` (`AND`/`ORR`/`EOR`/`ANDS`, `ARM DDI 0487
/// C6.2.9`/`C6.2.226`/`C6.2.98`/`C6.2.10`) para a máscara de 64 bits replicada que a instrução
/// aplica. **Este é o encoding mais traiçoeiro da ISA A64** (aviso do próprio épico B6, ver
/// `tasks/trilha-b-arquiteturas/b6.3.1-aarch64-logical-imm-alu-register.md`) — transcrito
/// campo a campo de `target/arm/tcg/translate-a64.c:5224-5286` (`logic_imm_decode_wmask`, QEMU
/// master, lido 2026-07-24), sem qualquer simplificação além da já feita pelo próprio QEMU
/// (a função original do QEMU já é uma "variante simplificada" do pseudocódigo do manual, só
/// para o caso em que apenas a `wmask` importa — que é exatamente o caso de `AND`/`ORR`/`EOR`/
/// `ANDS`, sem a `tmask` usada por outras instruções como `BFM`).
///
/// Validado por um property test exaustivo contra uma SEGUNDA implementação independente (não
/// esta fórmula reescrita — uma expansão bit-a-bit literal da definição do manual), ver
/// `Aarch64LogicalImmediateTest` — não confiar nesta transcrição sozinha (armadilha documentada
/// na task).
final class Aarch64LogicalImmediate {
    /// Largura em bits do campo `imms`/`immr` (cada um cabe em 6 bits, `0`-`63`).
    private static final int FIELD_WIDTH_BITS = 6;
    /// Máscara dos 6 bits baixos de `imms`/`immr` — nomeada para não deixar `0x3f` solto (G6).
    private static final int FIELD_MASK = (1 << FIELD_WIDTH_BITS) - 1;
    /// Deslocamento do bit `N` quando concatenado à esquerda de `~imms` para achar o tamanho do
    /// elemento (`len = 31 - clz32((N << 6) | (~imms & 0x3f))`).
    private static final int N_CONCAT_SHIFT = FIELD_WIDTH_BITS;
    /// Total de bits de um registrador `X` — o resultado final é sempre replicado até aqui.
    private static final int REGISTER_WIDTH_BITS = 64;

    private Aarch64LogicalImmediate() {
    }

    /// Decodifica `(N, imms, immr)` para a máscara de 64 bits que `AND`/`ORR`/`EOR`/`ANDS`
    /// (imediato) aplicam. Combinações reservadas (arquiteturalmente UNDEFINED, não "não
    /// implementado" — ver a task B6.3.1) lançam {@link UnsupportedOperationException}: (a) a
    /// concatenação `N:~imms` é toda zero (`N=0` e `imms=0b11111x`); (b) a "corrida de uns"
    /// ocupa o elemento inteiro (`s == levels`).
    ///
    /// @param n bit `N` do encoding (`0` ou `1`)
    /// @param imms campo `imms` de 6 bits (`0`-`63`)
    /// @param immr campo `immr` de 6 bits (`0`-`63`)
    /// @return máscara de 64 bits já replicada pelo tamanho do elemento
    /// @throws UnsupportedOperationException quando a combinação é reservada/UNDEFINED
    static long decodeBitMasks(int n, int imms, int immr) {
        if (n != 0 && n != 1) {
            throw new IllegalArgumentException("N deve ser 0 ou 1: " + n);
        }
        if ((imms & ~FIELD_MASK) != 0 || (immr & ~FIELD_MASK) != 0) {
            throw new IllegalArgumentException("imms/immr devem caber em 6 bits: imms=" + imms + " immr=" + immr);
        }
        // Determina o tamanho do elemento: a posição do bit 1 mais alto de N:~imms identifica
        // log2(tamanho do elemento) — ver a tabela do próprio QEMU (translate-a64.c:5233-5254).
        int concat = (n << N_CONCAT_SHIFT) | (~imms & FIELD_MASK);
        int len = 31 - Integer.numberOfLeadingZeros(concat);
        if (len < 1) {
            // N=0, imms=0b11111x: concat=0, nenhum bit 1 -> reservado.
            throw reserved(n, imms, immr, "N:~imms é todo-zero");
        }
        int elementSizeBits = 1 << len;
        int levels = elementSizeBits - 1;
        int runLengthMinusOne = imms & levels;
        int rotation = immr & levels;
        if (runLengthMinusOne == levels) {
            // <length of run - 1> não pode ocupar o elemento inteiro.
            throw reserved(n, imms, immr, "corrida de uns ocupa o elemento inteiro (s == levels)");
        }

        long elementMask = maskOfWidth(runLengthMinusOne + 1);
        if (rotation != 0) {
            elementMask = (elementMask >>> rotation) | (elementMask << (elementSizeBits - rotation));
            elementMask &= maskOfWidth(elementSizeBits);
        }
        return replicateAcross64Bits(elementMask, elementSizeBits);
    }

    /// `MAKE_64BIT_MASK(0, bits)` do QEMU: máscara dos `bits` bits baixos. Shift de 64 bits em
    /// Java é UB por wraparound (`1L << 64 == 1L`, não `0`) — tratado como caso especial, não
    /// confiar em `(1L << n) - 1` funcionar para `n == 64` (armadilha documentada na task).
    private static long maskOfWidth(int bits) {
        return bits >= REGISTER_WIDTH_BITS ? -1L : (1L << bits) - 1L;
    }

    /// `bitfield_replicate` do QEMU: duplica o padrão de `elementSizeBits` bits sucessivamente
    /// até preencher os 64 bits do registrador (`e` dobra a cada passo). Nunca desloca por `64`
    /// (o laço para quando `e` já chegou a `64`, antes de qualquer shift por esse valor).
    private static long replicateAcross64Bits(long elementMask, int elementSizeBits) {
        int e = elementSizeBits;
        long mask = elementMask;
        while (e < REGISTER_WIDTH_BITS) {
            mask |= mask << e;
            e *= 2;
        }
        return mask;
    }

    private static UnsupportedOperationException reserved(int n, int imms, int immr, String reason) {
        return new UnsupportedOperationException(
                "AArch64: Logical (immediate) reservado/UNDEFINED (N=" + n + ", imms=0x"
                        + Integer.toHexString(imms) + ", immr=0x" + Integer.toHexString(immr) + "): " + reason);
    }
}
