package dev.vitorsilverio.armjitter.core64;

/// Banco de registradores FP escalar de AArch64: 32 registradores `V<n>`, dos quais só os bits
/// 63:0 são modelados (`S<n>`/`D<n>` — NEON/bits 127:64 fora de escopo, B6.5.1 D4) — sibling
/// estrutural de {@link dev.vitorsilverio.armjitter.core.VfpRegisters} (VFPv2/32-bit), NÃO uma
/// extensão dele: ao contrário do VFP32, `S<n>` e `D<n>` aqui são a MESMA célula de
/// armazenamento (a metade baixa de `V<n>`), não um par de índices independentes
/// (`s[2i]`/`s[2i+1]`) — não existe mais o conceito de par S/D em A64 (B6.5.1 D1).
///
/// Sempre armazena BITS crus IEEE 754: float/double são apenas *views* de conveniência, nunca o
/// tipo primário — garante save-state/snapshot bit-exatos, incluindo NaN payloads, que aritmética
/// em `float`/`double` do Java poderia canonicalizar (mesma disciplina de `VfpRegisters`).
public final class Aarch64FpRegisters {
    /// Quantidade de registradores V (0-31).
    public static final int V_REGISTER_COUNT = 32;

    /// Máscara para a metade baixa de 32 bits.
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;

    private final long[] v = new long[V_REGISTER_COUNT];

    /// Lê os 32 bits baixos crus de `V<index>` (visão `S<index>`).
    public int s(int index) {
        return (int) v[index];
    }

    /// Grava os 32 bits baixos de `V<index>` (visão `S<index>`) e ZERA os 32 bits altos —
    /// comportamento arquitetural "SIMD&FP destructive write" (B6.5.1 D3), OPOSTO do VFP32 (lá
    /// `setS` nunca toca em nada além do próprio registrador de 32 bits).
    public void setS(int index, int bits) {
        v[index] = bits & LOW_32_BITS_MASK;
    }

    /// Lê os 64 bits crus de `V<index>` (visão `D<index>`).
    public long d(int index) {
        return v[index];
    }

    /// Grava os 64 bits crus de `V<index>` (visão `D<index>`).
    public void setD(int index, long bits) {
        v[index] = bits;
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

    /// Serializa o banco V completo para um save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (long value : v) {
            out.writeLong(value);
        }
    }

    /// Restaura o banco V completo gravado por {@link #saveState}.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < v.length; i++) {
            v[i] = in.readLong();
        }
    }

    /// Zera o banco V inteiro.
    public void reset() {
        java.util.Arrays.fill(v, 0L);
    }

    /// Cópia defensiva do banco V completo, usada pelo harness de equivalência.
    public long[] snapshot() {
        return java.util.Arrays.copyOf(v, v.length);
    }
}
