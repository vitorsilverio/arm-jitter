package dev.vitorsilverio.armjitter.core64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/// Banco escalável de AArch64 (SVE, B17.3, RFC B17.2 — Opção C): 32 registradores vetoriais
/// `Z0`-`Z31` de `VL` bits, 16 predicados `P0`-`P15` de `VL/8` bits e o registrador de primeiro
/// falha `FFR` — modelado, como no QEMU (`FFR_PRED_NUM`), como o **17º predicado** (índice
/// {@link #FFR_INDEX}) para que toda operação de predicado sirva também a `FFR`.
///
/// **É o armazenamento único dos registradores `V`**: {@link Aarch64FpRegisters} é uma *vista* das
/// duas palavras baixas de cada `Z<n>` (`Z<n>[127:0]` ≡ `V<n>`), nunca um banco irmão sincronizado
/// (Armadilha 1 do épico B17). Cada `Z<n>` ocupa {@link #wordsPerVector()} palavras de 64 bits
/// consecutivas em `z`, palavra `0` = bits `63:0`.
///
/// O `VL` implementado é fixado na construção (múltiplo de {@link #MIN_VECTOR_LENGTH_BITS} entre
/// {@link #MIN_VECTOR_LENGTH_BITS} e {@link #MAX_VECTOR_LENGTH_BITS}); o `VL` EFETIVO (limitado por
/// `ZCR_ELx.LEN`) vive em {@link Aarch64Core#vectorLengthBits()}. Nenhum laço de lane pode assumir
/// `128` (disciplina VLA, G6): tudo lê {@link #vectorLengthBytes()}.
///
/// Sem predicados ({@code withPredicates == false}) — usado por presets sem `FEAT_SVE` — o banco
/// custa exatamente o que `Aarch64FpRegisters` custava antes: `32 × 2` palavras.
public final class Aarch64ScalableRegisters {
    /// Quantidade de registradores `Z` (0-31).
    public static final int Z_REGISTER_COUNT = 32;

    /// Quantidade de registradores de predicado `P` visíveis por encoding (0-15).
    public static final int P_REGISTER_COUNT = 16;

    /// Índice do `FFR` no array de predicados — o 17º predicado, logo depois de `P15`.
    public static final int FFR_INDEX = P_REGISTER_COUNT;

    /// Menor `VL` arquitetural (`128` bits) — também o tamanho de `V<n>`.
    public static final int MIN_VECTOR_LENGTH_BITS = 128;

    /// Maior `VL` arquitetural (`2048` bits).
    public static final int MAX_VECTOR_LENGTH_BITS = 2048;

    /// `VL` default dos presets `ARMV9_*_A` (decisão da RFC B17.2): o menor em que a aritmética VLA
    /// é observável (`VL/128 != 1`).
    public static final int DEFAULT_VECTOR_LENGTH_BITS = 256;

    /// Bits por palavra do armazenamento.
    private static final int WORD_BITS = Long.SIZE;

    /// Um bit de predicado por byte do vetor.
    private static final int VECTOR_BITS_PER_PREDICATE_BIT = Byte.SIZE;

    /// Versão do formato de {@link #saveState}.
    private static final int STATE_FORMAT_VERSION = 1;

    private final int vectorLengthBits;
    private final int wordsPerVector;
    private final int wordsPerPredicate;
    private final long[] z;
    private final long[] p;

    /// Cria o banco zerado.
    ///
    /// @param vectorLengthBits `VL` implementado: múltiplo de {@link #MIN_VECTOR_LENGTH_BITS} em
    ///                         [{@link #MIN_VECTOR_LENGTH_BITS}, {@link #MAX_VECTOR_LENGTH_BITS}]
    /// @param withPredicates   `true` aloca `P0`-`P15` + `FFR`; `false` só o banco `Z`
    public Aarch64ScalableRegisters(int vectorLengthBits, boolean withPredicates) {
        if (vectorLengthBits < MIN_VECTOR_LENGTH_BITS
                || vectorLengthBits > MAX_VECTOR_LENGTH_BITS
                || vectorLengthBits % MIN_VECTOR_LENGTH_BITS != 0) {
            throw new IllegalArgumentException(
                    "VL inválido (múltiplo de " + MIN_VECTOR_LENGTH_BITS + " entre " + MIN_VECTOR_LENGTH_BITS
                            + " e " + MAX_VECTOR_LENGTH_BITS + "): " + vectorLengthBits);
        }
        this.vectorLengthBits = vectorLengthBits;
        this.wordsPerVector = vectorLengthBits / WORD_BITS;
        int predicateBits = vectorLengthBits / VECTOR_BITS_PER_PREDICATE_BIT;
        this.wordsPerPredicate = (predicateBits + WORD_BITS - 1) / WORD_BITS;
        this.z = new long[Z_REGISTER_COUNT * wordsPerVector];
        this.p = withPredicates ? new long[(P_REGISTER_COUNT + 1) * wordsPerPredicate] : new long[0];
    }

    /// `VL` implementado em bits.
    public int vectorLengthBits() {
        return vectorLengthBits;
    }

    /// `VL` implementado em bytes.
    public int vectorLengthBytes() {
        return vectorLengthBits / Byte.SIZE;
    }

    /// Tamanho de um predicado em bytes (`VL/64`; um bit por byte do vetor).
    public int predicateLengthBytes() {
        return vectorLengthBytes() / VECTOR_BITS_PER_PREDICATE_BIT;
    }

    /// Palavras de 64 bits por registrador `Z`.
    public int wordsPerVector() {
        return wordsPerVector;
    }

    /// Palavras de 64 bits por predicado (arredondado para cima; bits acima de `VL/8` ficam `0`).
    public int wordsPerPredicate() {
        return wordsPerPredicate;
    }

    /// `true` quando os predicados/`FFR` foram alocados.
    public boolean hasPredicates() {
        return p.length != 0;
    }

    /// Palavra `word` (0 = bits 63:0) de `Z<reg>`.
    public long zWord(int reg, int word) {
        return z[reg * wordsPerVector + word];
    }

    /// Grava a palavra `word` de `Z<reg>`, sem a semântica "zera o resto" do A64 (quem zera é o
    /// executor).
    public void setZWord(int reg, int word, long value) {
        z[reg * wordsPerVector + word] = value;
    }

    /// Palavra `word` do predicado `reg` (`0`-`15`, ou {@link #FFR_INDEX} para o `FFR`).
    public long pWord(int reg, int word) {
        return p[reg * wordsPerPredicate + word];
    }

    /// Grava a palavra `word` do predicado `reg` (`0`-`15`, ou {@link #FFR_INDEX}).
    public void setPWord(int reg, int word, long value) {
        p[reg * wordsPerPredicate + word] = value;
    }

    /// Palavra `word` do `FFR`.
    public long ffrWord(int word) {
        return pWord(FFR_INDEX, word);
    }

    /// Grava a palavra `word` do `FFR`.
    public void setFfrWord(int word, long value) {
        setPWord(FFR_INDEX, word, value);
    }

    /// Zera `Z<reg>[VL-1:128]` — a regra ARM real de toda escrita AdvSIMD/FP em `V<reg>`
    /// (Armadilha 2 da B17.3). No-op quando `VL == 128`.
    void clearAbove128(int reg) {
        int base = reg * wordsPerVector;
        for (int w = MIN_VECTOR_LENGTH_BITS / WORD_BITS; w < wordsPerVector; w++) {
            z[base + w] = 0L;
        }
    }

    /// Estreita o `VL` efetivo para `newVectorLengthBits` (`ZCR_ELx.LEN` diminuiu): zera os bits de
    /// `Z` acima do novo `VL` e os bits de predicado/`FFR` acima de `newVL/8` — o que o QEMU faz em
    /// `aarch64_sve_narrow_vq`. Não faz nada se `newVectorLengthBits >= vectorLengthBits()`.
    void narrowTo(int newVectorLengthBits) {
        if (newVectorLengthBits >= vectorLengthBits) {
            return;
        }
        int keptVectorWords = newVectorLengthBits / WORD_BITS;
        for (int reg = 0; reg < Z_REGISTER_COUNT; reg++) {
            int base = reg * wordsPerVector;
            for (int w = keptVectorWords; w < wordsPerVector; w++) {
                z[base + w] = 0L;
            }
        }
        if (!hasPredicates()) {
            return;
        }
        int keptPredicateBits = newVectorLengthBits / VECTOR_BITS_PER_PREDICATE_BIT;
        for (int reg = 0; reg <= P_REGISTER_COUNT; reg++) {
            int base = reg * wordsPerPredicate;
            for (int w = 0; w < wordsPerPredicate; w++) {
                int firstBit = w * WORD_BITS;
                if (firstBit >= keptPredicateBits) {
                    p[base + w] = 0L;
                } else if (firstBit + WORD_BITS > keptPredicateBits) {
                    long keepMask = (1L << (keptPredicateBits - firstBit)) - 1;
                    p[base + w] &= keepMask;
                }
            }
        }
    }

    /// Acesso interno de {@link Aarch64FpRegisters} ao armazenamento de `Z`.
    long[] zStorage() {
        return z;
    }

    /// Zera `Z`, `P` e `FFR`.
    public void reset() {
        Arrays.fill(z, 0L);
        Arrays.fill(p, 0L);
    }

    /// Cópia defensiva do banco completo: `Z0`-`Z31` (todas as palavras), depois `P0`-`P15` e
    /// `FFR` (vazio de predicados quando {@link #hasPredicates()} é `false`).
    public long[] snapshot() {
        long[] out = new long[z.length + p.length];
        System.arraycopy(z, 0, out, 0, z.length);
        System.arraycopy(p, 0, out, z.length, p.length);
        return out;
    }

    /// Serializa o banco (formato versionado, com o `VL`).
    public void saveState(DataOutputStream out) throws IOException {
        out.writeInt(STATE_FORMAT_VERSION);
        out.writeInt(vectorLengthBits);
        out.writeBoolean(hasPredicates());
        for (long word : z) {
            out.writeLong(word);
        }
        for (long word : p) {
            out.writeLong(word);
        }
    }

    /// Restaura o banco gravado por {@link #saveState}; rejeita `VL`/presença de predicados
    /// diferentes dos deste banco (o estado não é portável entre `VL`s sem reinterpretação).
    public void loadState(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != STATE_FORMAT_VERSION) {
            throw new IOException("Versão de estado escalável desconhecida: " + version);
        }
        int savedVectorLengthBits = in.readInt();
        boolean savedPredicates = in.readBoolean();
        if (savedVectorLengthBits != vectorLengthBits || savedPredicates != hasPredicates()) {
            throw new IOException("Estado escalável incompatível: VL=" + savedVectorLengthBits
                    + " predicados=" + savedPredicates + ", esperado VL=" + vectorLengthBits
                    + " predicados=" + hasPredicates());
        }
        for (int i = 0; i < z.length; i++) {
            z[i] = in.readLong();
        }
        for (int i = 0; i < p.length; i++) {
            p[i] = in.readLong();
        }
    }
}
