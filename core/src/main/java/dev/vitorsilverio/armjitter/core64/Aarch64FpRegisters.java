package dev.vitorsilverio.armjitter.core64;

/// Banco de registradores SIMD&FP de AArch64: 32 registradores `V<n>`, 128 bits cada
/// (`Q<n>`/`D<n>`/`S<n>` são *views* do MESMO armazenamento, não células independentes —
/// nunca existe conceito de par S/D como no VFP32, B6.5.1 D1) — sibling estrutural de
/// {@link dev.vitorsilverio.armjitter.core.VfpRegisters} (VFPv2/32-bit), NÃO uma extensão dele.
///
/// Armazenamento: cada `V<n>` é um par `(lo, hi)` de 64 bits (`lo`=bits 63:0, `hi`=bits 127:64) —
/// nenhum elemento de lane (byte/halfword/word/doubleword) cruza essa fronteira, porque todos os
/// tamanhos de elemento AdvSIMD (8/16/32/64 bits) dividem 64 igualmente (B8.6).
///
/// Sempre armazena BITS crus IEEE 754/inteiros: float/double são apenas *views* de conveniência,
/// nunca o tipo primário — garante save-state/snapshot bit-exatos, incluindo NaN payloads, que
/// aritmética em `float`/`double` do Java poderia canonicalizar (mesma disciplina de
/// `VfpRegisters`).
public final class Aarch64FpRegisters implements dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords {
    /// Quantidade de registradores V (0-31).
    public static final int V_REGISTER_COUNT = 32;

    /// Palavras de 64 bits por registrador `V` na vista plana
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords} (RFC B13.2): `V<n>` ocupa
    /// as palavras `2n` (bits 63:0) e `2n+1` (bits 127:64).
    public static final int WORDS_PER_REGISTER = 2;

    /// Largura de um registrador Q (128 bits) em bytes.
    public static final int QUADWORD_BYTES = 16;

    /// Largura de um registrador D (64 bits) em bytes.
    public static final int DOUBLEWORD_BYTES = 8;

    /// Máscara para a metade baixa de 32 bits.
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;

    /// Bits 63:0 de cada `V<n>`.
    private final long[] lo = new long[V_REGISTER_COUNT];

    /// Bits 127:64 de cada `V<n>` — NEON/AdvSIMD (B8.6, antes fora de escopo por B6.5.1 D4).
    private final long[] hi = new long[V_REGISTER_COUNT];

    /// Lê os 32 bits baixos crus de `V<index>` (visão `S<index>`).
    public int s(int index) {
        return (int) lo[index];
    }

    /// Grava os 32 bits baixos de `V<index>` (visão `S<index>`) e ZERA os 96 bits altos —
    /// comportamento arquitetural "SIMD&FP destructive write" (B6.5.1 D3), OPOSTO do VFP32 (lá
    /// `setS` nunca toca em nada além do próprio registrador de 32 bits).
    public void setS(int index, int bits) {
        lo[index] = bits & LOW_32_BITS_MASK;
        hi[index] = 0;
    }

    /// Lê os 64 bits crus de `V<index>` (visão `D<index>`).
    public long d(int index) {
        return lo[index];
    }

    /// Grava os 64 bits crus de `V<index>` (visão `D<index>`) e ZERA os 64 bits altos (mesma
    /// disciplina de "SIMD&FP destructive write" de {@link #setS}).
    public void setD(int index, long bits) {
        lo[index] = bits;
        hi[index] = 0;
    }

    /// Lê os bits 63:0 crus de `V<index>` SEM afetar/depender dos bits 127:64 (uso interno de
    /// operações vetoriais que escrevem os 128 bits de uma vez via {@link #setQ}).
    public long low64(int index) {
        return lo[index];
    }

    /// Lê os bits 127:64 crus de `V<index>` (visão alta de `Q<index>`, sempre `0` para registros
    /// nunca escritos como vetor de 128 bits).
    public long high64(int index) {
        return hi[index];
    }

    /// Grava os 128 bits crus de `V<index>` (visão `Q<index>`) de uma vez — usado por operações
    /// AdvSIMD que produzem um resultado vetorial completo (B8.6+), sem a semântica "zera o resto"
    /// de {@link #setS}/{@link #setD} (aqui os 128 bits INTEIROS são o próprio resultado).
    public void setQ(int index, long loBits, long hiBits) {
        lo[index] = loBits;
        hi[index] = hiBits;
    }

    /// Grava um valor escalar de `1 << sizeLog2` bytes (`sizeLog2` `0`-`3`, `B`/`H`/`S`/`D`) em
    /// `V<index>` e ZERA o resto do registrador — mesma disciplina "SIMD&FP destructive write" de
    /// {@link #setS}/{@link #setD}, generalizada para `B`/`H` (`LDR B`/`LDR H`, B8.13). Para `Q`
    /// (128 bits inteiros) use {@link #setQ} diretamente — não há "resto para zerar".
    public void setScalar(int index, int sizeLog2, long value) {
        int elementBits = 8 << sizeLog2;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        lo[index] = value & mask;
        hi[index] = 0;
    }

    /// Lê um elemento (lane) de `V<index>` de `1 << sizeLog2` bytes, começando no índice `lane`
    /// (lane `0` = bits menos significativos), com zero-extend em um `long`. `sizeLog2` vai de `0`
    /// (byte) a `3` (doubleword) — ARM DDI 0487, notação de "arrangement" AdvSIMD.
    public long element(int index, int lane, int sizeLog2) {
        int elementBits = 8 << sizeLog2;
        int bitOffset = lane * elementBits;
        long word = bitOffset < 64 ? lo[index] : hi[index];
        int shift = bitOffset & 0x3F;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        return (word >>> shift) & mask;
    }

    /// Grava um elemento (lane) de `V<index>` de `1 << sizeLog2` bytes no índice `lane`, sem
    /// afetar nenhum outro bit do registrador (comportamento "load/store single structure" real —
    /// diferente da escrita destrutiva de {@link #setS}/{@link #setD}).
    public void setElement(int index, int lane, int sizeLog2, long value) {
        int elementBits = 8 << sizeLog2;
        int bitOffset = lane * elementBits;
        int shift = bitOffset & 0x3F;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        long shiftedValue = (value & mask) << shift;
        long clearMask = ~(mask << shift);
        if (bitOffset < 64) {
            lo[index] = (lo[index] & clearMask) | shiftedValue;
        } else {
            hi[index] = (hi[index] & clearMask) | shiftedValue;
        }
    }

    /// Replica `value` (elemento de `1 << sizeLog2` bytes) por todas as lanes de `V<index>` —
    /// `quad`=`false` preenche só os 64 bits baixos e ZERA os altos (`LD1R` sem `Q`, mesma
    /// disciplina "destructive write" de {@link #setD}); `quad`=`true` preenche os 128 bits.
    public void replicateElement(int index, long value, int sizeLog2, boolean quad) {
        int elementBits = 8 << sizeLog2;
        int totalBits = quad ? 128 : 64;
        int lanes = totalBits / elementBits;
        for (int lane = 0; lane < lanes; lane++) {
            setElement(index, lane, sizeLog2, value);
        }
        if (!quad) {
            hi[index] = 0;
        }
    }

    /// Vista plana em palavras de 64 bits (RFC B13.2): palavra PAR = bits 63:0 de `V<index/2>`,
    /// palavra ÍMPAR = bits 127:64. Base de `V<n>` = `n * `{@link #WORDS_PER_REGISTER}.
    @Override
    public long word(int index) {
        return (index & 1) == 0 ? lo[index >> 1] : hi[index >> 1];
    }

    /// Grava uma palavra da vista plana (ver {@link #word}) SEM a semântica "zera o resto" de
    /// {@link #setS}/{@link #setD} — quem zera é o executor, depois de escrever todas as lanes.
    @Override
    public void setWord(int index, long value) {
        if ((index & 1) == 0) {
            lo[index >> 1] = value;
        } else {
            hi[index >> 1] = value;
        }
    }

    /// View `float` de `S<index>` (mesmos bits, sem aritmética).
    public float sFloat(int index) {
        return Float.intBitsToFloat(s(index));
    }

    /// Grava `S<index>` a partir de uma view `float` (mesmos bits, sem aritmética; zera os bits
    /// altos, ver {@link #setS}).
    public void setSFloat(int index, float value) {
        setS(index, Float.floatToRawIntBits(value));
    }

    /// View `double` de `D<index>` (mesmos bits, sem aritmética).
    public double dDouble(int index) {
        return Double.longBitsToDouble(d(index));
    }

    /// Grava `D<index>` a partir de uma view `double` (mesmos bits, sem aritmética).
    public void setDDouble(int index, double value) {
        setD(index, Double.doubleToRawLongBits(value));
    }

    /// Serializa o banco V completo (128 bits/registrador) para um save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (int i = 0; i < V_REGISTER_COUNT; i++) {
            out.writeLong(lo[i]);
            out.writeLong(hi[i]);
        }
    }

    /// Restaura o banco V completo gravado por {@link #saveState}.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < V_REGISTER_COUNT; i++) {
            lo[i] = in.readLong();
            hi[i] = in.readLong();
        }
    }

    /// Zera o banco V inteiro.
    public void reset() {
        java.util.Arrays.fill(lo, 0L);
        java.util.Arrays.fill(hi, 0L);
    }

    /// Cópia defensiva do banco V completo (128 bits/registrador, `lo` seguido de `hi` por
    /// registro), usada pelo harness de equivalência.
    public long[] snapshot() {
        long[] out = new long[V_REGISTER_COUNT * 2];
        for (int i = 0; i < V_REGISTER_COUNT; i++) {
            out[i * 2] = lo[i];
            out[i * 2 + 1] = hi[i];
        }
        return out;
    }
}
