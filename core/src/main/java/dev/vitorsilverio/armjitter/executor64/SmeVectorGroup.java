package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;

/// Grupo de `count` registradores `Z` CONSECUTIVOS (`2` ou `4`) tratado como um operando só — a unidade de toda a
/// família SME2 multi-vetor (B18.7-B18.12). `base` já vem multiplicado por `count` (o decoder aplica
/// `%zd_ax2`/`%zd_ax4`, ver `Aarch64SmeDecoder#groupBase`), então o grupo nunca cruza `Z31`.
///
/// **O utilitário só conhece a forma do grupo** (quais registradores, ler/escrever o vetor inteiro); a operação por
/// lane fica com quem usa, para que as seis tasks da família compartilhem esta infraestrutura sem acoplar semântica.
record SmeVectorGroup(int base, int count) {
    /// Registrador `Z` do membro `member` (`0` ≤ `member` < `count`).
    int register(int member) {
        return base + member;
    }

    /// Cópia dos `words` palavras de 64 bits de `Z<register>`. Tirar a cópia ANTES de escrever em qualquer membro é o
    /// que mantém correto o caso em que um operando avulso é também membro do grupo de destino (o QEMU processa o
    /// membro sobreposto por último pelo mesmo motivo — `do_zzz_n1`).
    static long[] snapshot(Aarch64ScalableRegisters regs, int register, int words) {
        long[] copy = new long[words];
        for (int w = 0; w < words; w++) {
            copy[w] = regs.zWord(register, w);
        }
        return copy;
    }

    /// Elemento `index` de largura `esz` (`0` = byte … `3` = doubleword) de uma cópia tirada por {@link #snapshot}.
    static long element(long[] words, int index, int esz) {
        int bit = index * SveIntegerOps.elementBits(esz);
        long value = words[bit >>> WORD_INDEX_SHIFT] >>> (bit & WORD_BIT_MASK);
        return value & SveIntegerOps.elementMask(esz);
    }

    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
}
