package dev.vitorsilverio.armjitter.core;

/// Banco de registradores VFP: 32 registradores S (single, 32 bits) sobrepostos a 16
/// registradores D (double, 64 bits) — `D<i>` = par `S<2i>` (metade BAIXA) + `S<2i+1>`
/// (metade ALTA), layout little-endian fixo do VFPv2/v3-d16 (ARM DDI 0406C A2.6.2).
///
/// Sempre armazena BITS crus IEEE 754: float/double são apenas *views* de conveniência,
/// nunca o tipo primário — isso garante save-state/snapshot bit-exatos, incluindo NaN
/// payloads, que aritmética em `float`/`double` do Java poderia canonicalizar.
public final class VfpRegisters {
    /// Quantidade de registradores S (single-precision).
    public static final int SINGLE_COUNT = 32;

    private final int[] s = new int[SINGLE_COUNT];

    /// Lê os bits crus de `S<index>`.
    public int s(int index) {
        return s[index];
    }

    /// Grava os bits crus de `S<index>`.
    public void setS(int index, int bits) {
        s[index] = bits;
    }

    /// Lê os bits crus de `D<index>` (par `S<2*index>` baixo + `S<2*index+1>` alto).
    public long d(int index) {
        int low = s[2 * index];
        int high = s[2 * index + 1];
        return (((long) high) << 32) | (low & 0xFFFF_FFFFL);
    }

    /// Grava os bits crus de `D<index>`, refletindo nos dois `S` que o compõem.
    public void setD(int index, long bits) {
        s[2 * index] = (int) bits;
        s[2 * index + 1] = (int) (bits >>> 32);
    }

    /// View `float` de `S<index>` (mesmos bits, sem aritmética).
    public float sFloat(int index) {
        return Float.intBitsToFloat(s(index));
    }

    /// Grava `S<index>` a partir de uma view `float` (mesmos bits, sem aritmética).
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

    /// Serializa o banco S completo para um save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (int value : s) {
            out.writeInt(value);
        }
    }

    /// Restaura o banco S completo gravado por {@link #saveState}.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < s.length; i++) {
            s[i] = in.readInt();
        }
    }

    /// Zera o banco S inteiro (usado ao carregar um save state de formato anterior à B3.3,
    /// que não possui banco VFP).
    public void reset() {
        java.util.Arrays.fill(s, 0);
    }

    /// Cópia defensiva do banco S completo, usada pelo harness de equivalência.
    public int[] snapshot() {
        return java.util.Arrays.copyOf(s, s.length);
    }
}
