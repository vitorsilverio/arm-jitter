package dev.vitorsilverio.armjitter.decoder64;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// E15.9 (D5 do épico E15): os dois invariantes que toda {@link DecodeTable} herda de graça,
/// reutilizáveis pelas tabelas das E15.10+.
///
/// - **Sobreposição** — duas linhas só casam a mesma palavra se os bits fixos em AMBAS forem
///   iguais (`((v1 ^ v2) & m1 & m2) == 0`). A tabela não tem prioridade, então isso é sempre erro.
///   Substitui os comentários "nunca colide — conferido" da cascata antiga, e é o G8 verificado
///   por máquina.
/// - **Alcance** — para cada linha, palavras com os bits livres sorteados decodificam por ELA (o
///   resultado da tabela é igual ao `build` da linha e não é `null`). Exercita toda linha, logo
///   todo construtor de op.
final class DecodeTableInvariants {
    private static final int SAMPLES_PER_ROW = 16;
    /// Endereço da instrução nas amostras (nenhum invariante depende dele).
    private static final long ADDRESS = 0x1000L;

    private DecodeTableInvariants() {
    }

    /// Falha listando todos os pares de linhas que casam alguma palavra em comum.
    static void assertNoOverlap(List<? extends DecodeRow<?>> rows) {
        List<String> overlaps = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                DecodeRow<?> a = rows.get(i);
                DecodeRow<?> b = rows.get(j);
                if (((a.value() ^ b.value()) & a.mask() & b.mask()) == 0) {
                    overlaps.add(i + " × " + j);
                }
            }
        }
        assertEquals(List.of(), overlaps, "linhas que casam a mesma palavra");
    }

    /// Para cada linha de `table`, sorteia {@link #SAMPLES_PER_ROW} palavras que a casam e confere
    /// que a tabela devolve exatamente o `build` dela. `fixedZero` são bits que o sorteio mantém em
    /// `0` mesmo quando a linha os deixa livres (ex.: um bit que o chamador ainda não filtra).
    static <T> void assertReachable(DecodeTable<T> table, int fixedZero, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        assertTrue(!table.rows().isEmpty(), "tabela vazia não prova alcance");
        for (DecodeRow<T> row : table.rows()) {
            for (int sample = 0; sample < SAMPLES_PER_ROW; sample++) {
                int word = row.value() | (random.nextInt() & ~row.mask() & ~fixedZero);
                T expected = row.build().decode(word, ADDRESS);
                assertNotNull(expected, () -> "build devolveu null para " + Integer.toHexString(word));
                assertEquals(expected, table.decode(word, ADDRESS), () -> "palavra " + Integer.toHexString(word));
            }
        }
    }
}
