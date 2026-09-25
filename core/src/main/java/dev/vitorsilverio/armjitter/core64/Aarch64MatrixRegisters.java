package dev.vitorsilverio.armjitter.core64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/// Armazenamento matricial de AArch64 (SME, B18.1): o array `ZA` — um quadrado de `SVL × SVL`
/// **bits**, isto é `(SVL/8)²` bytes — e o registrador `ZT0` de 512 bits (SME2).
///
/// **Alocação preguiçosa é requisito, não otimização** (Armadilha 2 da B18.1): `ZA` custa 4 KiB em
/// `SVL=512` e 64 KiB em `SVL=2048`. Os dois arrays são `null` até {@link #enableZa()}; um core que
/// nunca liga `SVCR.ZA` não paga nada, e {@link #saveState}/{@link #snapshot} representam "nunca
/// alocado" sem materializar o array (mesma disciplina do `memoryTags` esparso da B19.14).
///
/// **O tamanho é o `SVL` IMPLEMENTADO**, não o efetivo: `SMCR_ELx.LEN` só limita o `SVL` com que as
/// instruções endereçam `ZA` ({@link Aarch64Core#streamingVectorLengthBits()}), e reduzi-lo mantém o
/// conteúdo (a escolha "manter" que o QEMU documenta em `smcr_write` para o caso
/// CONSTRAINED UNPREDICTABLE), o que evita zerar `ZA` a cada troca de EL. Quem endereça `ZA` por
/// tile/slice (B18.3) é quem aplica o `SVL` efetivo.
///
/// Layout: `ZA` é `SVL/8` linhas de `SVL/8` bytes, em ordem de linha ({@link #zaWord}: palavra `0`
/// = bytes `7:0` da linha `0`). O endereçamento vertical descontíguo do QEMU (`ZAnH.D[m]` espalhado
/// em `ZA[n+8*m]`) é assunto do endereçamento de tile da B18.3, não do armazenamento.
public final class Aarch64MatrixRegisters {
    /// Menor `SVL` arquitetural (`128` bits).
    public static final int MIN_STREAMING_VECTOR_LENGTH_BITS = 128;

    /// Maior `SVL` arquitetural (`2048` bits — `SMCR_ELx.LEN` tem 4 bits).
    public static final int MAX_STREAMING_VECTOR_LENGTH_BITS = 2048;

    /// `SVL` default (RFC B17.2/B18: o mesmo `256` do `VL`, o menor em que a aritmética VLA é
    /// observável).
    public static final int DEFAULT_STREAMING_VECTOR_LENGTH_BITS = 256;

    /// Tamanho de `ZT0` em bits (`FEAT_SME2`, fixo: não depende de `SVL`).
    public static final int ZT0_BITS = 512;

    /// Versão do formato de {@link #saveState}.
    private static final int STATE_FORMAT_VERSION = 1;

    private static final int WORD_BITS = Long.SIZE;
    private static final int ZT0_WORDS = ZT0_BITS / WORD_BITS;

    private final int streamingVectorLengthBits;
    private final int zaWords;
    /// `null` enquanto `SVCR.ZA` nunca foi ligado (ou desde que foi desligado).
    private long[] za;
    /// `null` até o primeiro acesso a `ZT0` com `ZA` ligado (também zerado por `enableZa`/`releaseZa`).
    private long[] zt0;

    /// Cria o armazenamento vazio (nada alocado).
    ///
    /// @param streamingVectorLengthBits `SVL` implementado: múltiplo de
    ///                                  {@link #MIN_STREAMING_VECTOR_LENGTH_BITS} em
    ///                                  [{@link #MIN_STREAMING_VECTOR_LENGTH_BITS},
    ///                                  {@link #MAX_STREAMING_VECTOR_LENGTH_BITS}]
    public Aarch64MatrixRegisters(int streamingVectorLengthBits) {
        if (streamingVectorLengthBits < MIN_STREAMING_VECTOR_LENGTH_BITS
                || streamingVectorLengthBits > MAX_STREAMING_VECTOR_LENGTH_BITS
                || streamingVectorLengthBits % MIN_STREAMING_VECTOR_LENGTH_BITS != 0) {
            throw new IllegalArgumentException("SVL inválido (múltiplo de " + MIN_STREAMING_VECTOR_LENGTH_BITS
                    + " entre " + MIN_STREAMING_VECTOR_LENGTH_BITS + " e " + MAX_STREAMING_VECTOR_LENGTH_BITS
                    + "): " + streamingVectorLengthBits);
        }
        this.streamingVectorLengthBits = streamingVectorLengthBits;
        long rowBytes = streamingVectorLengthBits / Byte.SIZE;
        this.zaWords = (int) (rowBytes * rowBytes / Long.BYTES);
    }

    /// `SVL` implementado em bits.
    public int streamingVectorLengthBits() {
        return streamingVectorLengthBits;
    }

    /// Bytes por linha de `ZA` (`SVL/8`), que também é o número de linhas.
    public int zaRowBytes() {
        return streamingVectorLengthBits / Byte.SIZE;
    }

    /// Tamanho de `ZA` em bytes: `(SVL/8)²`.
    public int zaBytes() {
        return zaWords * Long.BYTES;
    }

    /// `true` quando `ZA` está alocado (`SVCR.ZA` ligado).
    public boolean zaAllocated() {
        return za != null;
    }

    /// `true` quando `ZT0` foi materializado.
    public boolean zt0Allocated() {
        return zt0 != null;
    }

    /// `SVCR.ZA` 0→1: aloca `ZA` zerado (regra `ResetSMEState` do manual) e descarta `ZT0`, que
    /// volta a ser zero na próxima leitura.
    public void enableZa() {
        za = new long[zaWords];
        zt0 = null;
    }

    /// `SVCR.ZA` 1→0: libera `ZA` e `ZT0` (o conteúdo seria zerado de qualquer modo).
    public void releaseZa() {
        za = null;
        zt0 = null;
    }

    /// Palavra `index` de `ZA` (0 = bytes `7:0` da linha `0`).
    ///
    /// @throws IllegalStateException se `ZA` não está alocado (`SVCR.ZA = 0`)
    public long zaWord(int index) {
        return requireZa()[index];
    }

    /// Grava a palavra `index` de `ZA`.
    ///
    /// @throws IllegalStateException se `ZA` não está alocado (`SVCR.ZA = 0`)
    public void setZaWord(int index, long value) {
        requireZa()[index] = value;
    }

    /// Palavra `index` (`0`-`7`) de `ZT0`; materializa `ZT0` zerado no primeiro acesso.
    ///
    /// @throws IllegalStateException se `ZA` não está alocado (`ZT0` só é acessível com `ZA` ligado)
    public long zt0Word(int index) {
        return requireZt0()[index];
    }

    /// Grava a palavra `index` (`0`-`7`) de `ZT0`.
    ///
    /// @throws IllegalStateException se `ZA` não está alocado
    public void setZt0Word(int index, long value) {
        requireZt0()[index] = value;
    }

    private long[] requireZa() {
        if (za == null) {
            throw new IllegalStateException("ZA não alocado: SVCR.ZA = 0");
        }
        return za;
    }

    private long[] requireZt0() {
        requireZa();
        if (zt0 == null) {
            zt0 = new long[ZT0_WORDS];
        }
        return zt0;
    }

    /// Cópia defensiva para o harness de equivalência: dois marcadores de presença (`ZA`, `ZT0`)
    /// seguidos das palavras presentes. "Nunca alocado" ocupa 2 posições, não `(SVL/8)²` bytes.
    public long[] snapshot() {
        int size = 2 + (za == null ? 0 : za.length) + (zt0 == null ? 0 : zt0.length);
        long[] out = new long[size];
        out[0] = za == null ? 0L : 1L;
        out[1] = zt0 == null ? 0L : 1L;
        int at = 2;
        if (za != null) {
            System.arraycopy(za, 0, out, at, za.length);
            at += za.length;
        }
        if (zt0 != null) {
            System.arraycopy(zt0, 0, out, at, zt0.length);
        }
        return out;
    }

    /// Serializa o armazenamento (formato versionado, com o `SVL`). Um `ZA` nunca alocado grava só
    /// dois booleanos.
    public void saveState(DataOutputStream out) throws IOException {
        out.writeInt(STATE_FORMAT_VERSION);
        out.writeInt(streamingVectorLengthBits);
        out.writeBoolean(za != null);
        if (za != null) {
            for (long word : za) {
                out.writeLong(word);
            }
        }
        out.writeBoolean(zt0 != null);
        if (zt0 != null) {
            for (long word : zt0) {
                out.writeLong(word);
            }
        }
    }

    /// Restaura o que {@link #saveState} gravou; rejeita `SVL` diferente. Um estado gravado sem `ZA`
    /// **não** o aloca.
    public void loadState(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != STATE_FORMAT_VERSION) {
            throw new IOException("Versão de estado matricial desconhecida: " + version);
        }
        int savedSvl = in.readInt();
        if (savedSvl != streamingVectorLengthBits) {
            throw new IOException("Estado matricial incompatível: SVL=" + savedSvl + ", esperado SVL="
                    + streamingVectorLengthBits);
        }
        if (in.readBoolean()) {
            long[] loaded = new long[zaWords];
            for (int i = 0; i < loaded.length; i++) {
                loaded[i] = in.readLong();
            }
            za = loaded;
        } else {
            za = null;
        }
        if (in.readBoolean()) {
            long[] loaded = new long[ZT0_WORDS];
            for (int i = 0; i < loaded.length; i++) {
                loaded[i] = in.readLong();
            }
            zt0 = loaded;
        } else {
            zt0 = null;
        }
    }
}
