package dev.vitorsilverio.armjitter.decodetable;

import java.util.Objects;
import java.util.function.Predicate;

/// E15.9 (D5 do épico E15): uma linha de {@link DecodeTable} — um encoding no estilo `.decode`.
///
/// A palavra casa quando `(word & mask) == value`; `requires` é a feature que o preset precisa
/// declarar para a linha existir (`null` = ISA base). A feature é COLUNA da linha, não um `if` no
/// caminho de decode: {@link DecodeTable#forFeatures} resolve as linhas ausentes uma vez, na
/// construção.
///
/// **Sem a feature** (E15.16a) a linha some, a não ser que declare {@link #whenAbsent}: aí ela
/// continua casando a palavra e constrói por ele. É a diferença, na cascata de 32 bits, entre
/// `if (casa && has(F))` (a palavra segue para quem vier depois) e
/// `if (casa) { if (!has(F)) return indefinida; … }` (o espaço é da instrução mesmo sem ela).
///
/// @param mask bits fixos do encoding
/// @param value valor dos bits fixos (`value & ~mask == 0`)
/// @param requires feature exigida, ou `null` para a ISA base
/// @param alsoRequires segunda feature exigida junto com `requires` (E15.12: `SETG*` = MOPS **e** MTE), ou `null`
/// @param build constrói a operação a partir da palavra (só é chamado quando a linha casa)
/// @param whenAbsent constrói a operação quando o preset não declara as features, ou `null` se a linha some
/// @param <F> tipo da feature (`Aarch64Feature`, `ArmFeature`)
/// @param <T> tipo da operação decodificada
public record DecodeRow<F, T>(int mask, int value, F requires, F alsoRequires, WordDecoder<T> build,
        WordDecoder<T> whenAbsent) {
    /// Constrói a operação de uma palavra que já casou a linha.
    @FunctionalInterface
    public interface WordDecoder<T> {
        /// Decodifica `word`, lida em `address` (E15.10: `ADR`/`ADRP`, branches e load literal guardam o
        /// endereço da instrução). Nunca devolve `null`: as restrições de campo são linhas próprias. A
        /// exceção é restrição de VALOR que não cabe em máscara (a bitmask reservada do logical
        /// imediato): aí o construtor lança `UnsupportedOperationException` (A64) ou devolve a
        /// instrução indefinida (32 bits), como o resto do decoder.
        T decode(int word, long address);
    }

    private static final int WORD_BITS = Integer.SIZE;

    /// Valida `value` contra `mask` e exige `build`.
    public DecodeRow {
        if ((value & ~mask) != 0) {
            throw new IllegalArgumentException("value com bit fora da máscara: " + Integer.toHexString(value));
        }
        Objects.requireNonNull(build, "build");
    }

    /// Linha com uma feature só (ou nenhuma).
    public DecodeRow(int mask, int value, F requires, WordDecoder<T> build) {
        this(mask, value, requires, null, build, null);
    }

    /// Cria a linha a partir de um padrão de 32 símbolos (`0`/`1` fixos, `.` livre, bit 31
    /// primeiro); espaços são ignorados e servem só para separar campos.
    public static <F, T> DecodeRow<F, T> of(String pattern, F requires, WordDecoder<T> build) {
        return of(pattern, requires, null, build);
    }

    /// Como {@link #of(String, Object, WordDecoder)}, exigindo também `alsoRequires`.
    public static <F, T> DecodeRow<F, T> of(String pattern, F requires, F alsoRequires, WordDecoder<T> build) {
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
        return new DecodeRow<>(mask, value, requires, alsoRequires, build, null);
    }

    /// A mesma linha, que sem as features continua casando e constrói por `absent`.
    public DecodeRow<F, T> orWhenAbsent(WordDecoder<T> absent) {
        return new DecodeRow<>(mask, value, requires, alsoRequires, build, Objects.requireNonNull(absent, "absent"));
    }

    /// `true` quando `has` declara as features da linha.
    public boolean supportedBy(Predicate<? super F> has) {
        return (requires == null || has.test(requires)) && (alsoRequires == null || has.test(alsoRequires));
    }

    /// `true` quando os bits fixos de `word` são os desta linha.
    public boolean matches(int word) {
        return (word & mask) == value;
    }
}
