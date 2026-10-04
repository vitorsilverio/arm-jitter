package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;

import java.util.ArrayList;
import java.util.List;

/// E15.9 (D5 do épico E15): decoder por tabela — generaliza o laço `mask`/`value` dos
/// `Sme*Rows`, com a feature como coluna da linha ({@link DecodeRow}).
///
/// **Construção por arquitetura:** {@link #forArchitecture} mantém só as linhas cuja feature o
/// preset declara, então o decode não pergunta `has()` nenhuma vez (D8).
///
/// **Buckets:** `keyMask` reúne bits fixos em TODAS as linhas mantidas, escolhidos pelos que mais
/// dividem as linhas (`min(uns, zeros)`), no máximo {@link #MAX_KEY_BITS}. Como `keyMask` está
/// contido na máscara de toda linha, cada linha mora em exatamente um bucket, endereçado por
/// `Integer.compress(value, keyMask)`; o decode só percorre o bucket da palavra.
///
/// A tabela não tem prioridade entre linhas: duas linhas que casam a mesma palavra são erro de
/// especificação, apontado pelo teste de sobreposição (`DecodeTableInvariants`, nos testes).
///
/// @param <T> tipo da operação decodificada
final class DecodeTable<T> {
    /// Teto de bits da chave de bucket (1024 buckets).
    static final int MAX_KEY_BITS = 10;

    private final List<DecodeRow<T>> rows;
    private final int keyMask;
    private final DecodeRow<T>[][] buckets;

    /// Monta a tabela com as linhas de `rows` que `architecture` suporta, na ordem dada.
    static <T> DecodeTable<T> forArchitecture(List<DecodeRow<T>> rows, Aarch64Architecture architecture) {
        List<DecodeRow<T>> kept = new ArrayList<>();
        for (DecodeRow<T> row : rows) {
            if (row.requires() == null || architecture.has(row.requires())) {
                kept.add(row);
            }
        }
        return new DecodeTable<>(kept);
    }

    @SuppressWarnings("unchecked")
    private DecodeTable(List<DecodeRow<T>> rows) {
        this.rows = List.copyOf(rows);
        this.keyMask = chooseKeyMask(this.rows);
        List<List<DecodeRow<T>>> lists = new ArrayList<>();
        for (int i = 0; i < 1 << Integer.bitCount(keyMask); i++) {
            lists.add(new ArrayList<>());
        }
        for (DecodeRow<T> row : this.rows) {
            lists.get(Integer.compress(row.value(), keyMask)).add(row);
        }
        this.buckets = (DecodeRow<T>[][]) new DecodeRow<?>[lists.size()][];
        for (int i = 0; i < lists.size(); i++) {
            buckets[i] = lists.get(i).toArray(DecodeRow[]::new);
        }
    }

    /// Bits comuns a todas as máscaras, os {@link #MAX_KEY_BITS} que mais dividem as linhas
    /// (empate: o bit mais alto). Bit com o mesmo valor em todas as linhas não entra — não separa
    /// nada. Tabela vazia → `0` (um bucket só, vazio).
    private static int chooseKeyMask(List<? extends DecodeRow<?>> rows) {
        int common = -1;
        for (DecodeRow<?> row : rows) {
            common &= row.mask();
        }
        int[] balance = new int[Integer.SIZE];
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            int ones = 0;
            for (DecodeRow<?> row : rows) {
                ones += (row.value() >>> bit) & 1;
            }
            balance[bit] = ((common >>> bit) & 1) == 0 ? 0 : Math.min(ones, rows.size() - ones);
        }
        int keyMask = 0;
        for (int chosen = 0; chosen < MAX_KEY_BITS; chosen++) {
            int best = 0;
            for (int bit = 1; bit < Integer.SIZE; bit++) {
                if (balance[bit] >= balance[best]) {
                    best = bit;
                }
            }
            if (balance[best] == 0) {
                break;
            }
            keyMask |= 1 << best;
            balance[best] = 0;
        }
        return keyMask;
    }

    /// Decodifica `word` (lida em `address`) pela linha que casa, ou devolve `null` quando nenhuma
    /// casa (o chamador segue para o resto do espaço ou recusa a palavra, G8).
    T decode(int word, long address) {
        for (DecodeRow<T> row : buckets[Integer.compress(word, keyMask)]) {
            if (row.matches(word)) {
                return row.build().decode(word, address);
            }
        }
        return null;
    }

    /// As linhas mantidas, na ordem de declaração.
    List<DecodeRow<T>> rows() {
        return rows;
    }

    /// Os bits usados como chave de bucket.
    int keyMask() {
        return keyMask;
    }
}
