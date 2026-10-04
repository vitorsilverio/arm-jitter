package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;

import java.util.Objects;

/// E15.9 (D5 do épico E15): uma linha de {@link DecodeTable} — um encoding no estilo `.decode`.
///
/// A palavra casa quando `(word & mask) == value`; `requires` é a feature que o preset precisa
/// declarar para a linha existir (`null` = ISA base). A feature é COLUNA da linha, não um `if` no
/// caminho de decode: {@link DecodeTable#forArchitecture} descarta as linhas ausentes uma vez, na
/// construção.
///
/// @param mask bits fixos do encoding
/// @param value valor dos bits fixos (`value & ~mask == 0`)
/// @param requires feature exigida, ou `null` para a ISA base
/// @param build constrói a operação a partir da palavra (só é chamado quando a linha casa)
/// @param <T> tipo da operação decodificada
record DecodeRow<T>(int mask, int value, Aarch64Feature requires, WordDecoder<T> build) {
    /// Constrói a operação de uma palavra que já casou a linha.
    @FunctionalInterface
    interface WordDecoder<T> {
        /// Decodifica `word`, lida em `address` (E15.10: `ADR`/`ADRP`, branches e load literal guardam o
        /// endereço da instrução). Nunca devolve `null`: as restrições de campo são linhas próprias. A
        /// exceção é restrição de VALOR que não cabe em máscara (a bitmask reservada do logical
        /// imediato): aí o construtor lança `UnsupportedOperationException`, como o resto do decoder.
        T decode(int word, long address);
    }

    private static final int WORD_BITS = Integer.SIZE;

    DecodeRow {
        if ((value & ~mask) != 0) {
            throw new IllegalArgumentException("value com bit fora da máscara: " + Integer.toHexString(value));
        }
        Objects.requireNonNull(build, "build");
    }

    /// Cria a linha a partir de um padrão de 32 símbolos (`0`/`1` fixos, `.` livre, bit 31
    /// primeiro); espaços são ignorados e servem só para separar campos.
    static <T> DecodeRow<T> of(String pattern, Aarch64Feature requires, WordDecoder<T> build) {
        String bits = pattern.replace(" ", "");
        if (bits.length() != WORD_BITS) {
            throw new IllegalArgumentException("padrão sem 32 bits: " + pattern);
        }
        int mask = 0;
        int value = 0;
        for (int i = 0; i < WORD_BITS; i++) {
            char symbol = bits.charAt(i);
            int bit = 1 << (WORD_BITS - 1 - i);
            switch (symbol) {
                case '0' -> mask |= bit;
                case '1' -> {
                    mask |= bit;
                    value |= bit;
                }
                case '.' -> { }
                default -> throw new IllegalArgumentException("símbolo inválido '" + symbol + "' em: " + pattern);
            }
        }
        return new DecodeRow<>(mask, value, requires, build);
    }

    /// `true` quando os bits fixos de `word` são os desta linha.
    boolean matches(int word) {
        return (word & mask) == value;
    }
}
