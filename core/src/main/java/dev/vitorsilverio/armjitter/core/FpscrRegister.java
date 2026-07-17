package dev.vitorsilverio.armjitter.core;

/// Representa o FPSCR (VFP Floating-Point Status and Control Register, ARM DDI 0406C A2.7.2).
/// Só o modo IEEE round-to-nearest é suportado (decisão nº 3 do épico B3): escrever qualquer
/// valor que peça `RMODE`≠round-to-nearest, `FZ`=1 (flush-to-zero) ou `LEN`/`STRIDE`≠0
/// (aritmética vetorial VFPv2) lança {@link UnsupportedOperationException} com uma mensagem
/// clara, mesmo padrão central de bit único inválido que {@link CpsrRegister} usa para o
/// bit E (`SETEND`, ARMv6).
public final class FpscrRegister {
    /// Bit N do FPSCR (resultado de comparação: menor que).
    public static final int NEGATIVE_FLAG = 1 << 31;
    /// Bit Z do FPSCR (resultado de comparação: igual).
    public static final int ZERO_FLAG = 1 << 30;
    /// Bit C do FPSCR (resultado de comparação: maior-ou-igual/sem-ordem).
    public static final int CARRY_FLAG = 1 << 29;
    /// Bit V do FPSCR (resultado de comparação: sem ordem/NaN).
    public static final int OVERFLOW_FLAG = 1 << 28;
    /// Bit DN do FPSCR (default NaN mode). Aceito e armazenado, mas sem efeito observável —
    /// a semântica de NaN deste core já é a do Java (payload propagado bit a bit), documentado
    /// aqui em vez de implementado como um modo à parte.
    public static final int DEFAULT_NAN_FLAG = 1 << 25;
    /// Bit FZ do FPSCR (flush-to-zero). Escrever `1` não é suportado (ver a classe).
    public static final int FLUSH_TO_ZERO_FLAG = 1 << 24;
    /// Deslocamento do campo RMODE\[1:0\] (modo de arredondamento). Escrever qualquer valor
    /// diferente de `00` (round-to-nearest) não é suportado (ver a classe).
    public static final int ROUNDING_MODE_SHIFT = 22;
    /// Máscara do campo RMODE\[1:0\].
    public static final int ROUNDING_MODE_MASK = 0b11 << ROUNDING_MODE_SHIFT;
    /// Deslocamento do campo STRIDE\[1:0\] (aritmética vetorial VFPv2). Escrever qualquer valor
    /// diferente de `0` não é suportado (ver a classe).
    public static final int STRIDE_SHIFT = 20;
    /// Máscara do campo STRIDE\[1:0\].
    public static final int STRIDE_MASK = 0b11 << STRIDE_SHIFT;
    /// Deslocamento do campo LEN\[2:0\] (aritmética vetorial VFPv2). Escrever qualquer valor
    /// diferente de `0` não é suportado (ver a classe).
    public static final int LEN_SHIFT = 16;
    /// Máscara do campo LEN\[2:0\].
    public static final int LEN_MASK = 0b111 << LEN_SHIFT;
    /// Bit IDC do FPSCR (cumulativo: input denormal).
    public static final int INPUT_DENORMAL_CUMULATIVE_FLAG = 1 << 7;
    /// Bit IXC do FPSCR (cumulativo: inexact).
    public static final int INEXACT_CUMULATIVE_FLAG = 1 << 4;
    /// Bit UFC do FPSCR (cumulativo: underflow).
    public static final int UNDERFLOW_CUMULATIVE_FLAG = 1 << 3;
    /// Bit OFC do FPSCR (cumulativo: overflow).
    public static final int OVERFLOW_CUMULATIVE_FLAG = 1 << 2;
    /// Bit DZC do FPSCR (cumulativo: division by zero).
    public static final int DIVIDE_BY_ZERO_CUMULATIVE_FLAG = 1 << 1;
    /// Bit IOC do FPSCR (cumulativo: invalid operation).
    public static final int INVALID_OPERATION_CUMULATIVE_FLAG = 1;

    private int value;

    /// Retorna o valor bruto de 32 bits do FPSCR.
    public int value() {
        return value;
    }

    /// Substitui o valor bruto do FPSCR.
    ///
    /// @throws UnsupportedOperationException se o valor pedir `RMODE`≠round-to-nearest,
    ///         `FZ`=1 ou `LEN`/`STRIDE`≠0 — únicos modos suportados por este core
    public void setValue(int value) {
        if ((value & ROUNDING_MODE_MASK) != 0
                || (value & FLUSH_TO_ZERO_FLAG) != 0
                || (value & LEN_MASK) != 0
                || (value & STRIDE_MASK) != 0) {
            throw new UnsupportedOperationException(
                    "FPSCR RMode/FZ/Len/Stride não suportados — modo IEEE round-to-nearest apenas");
        }
        this.value = value;
    }

    /// Retorna `true` quando N esta setado.
    public boolean n() {
        return (value & NEGATIVE_FLAG) != 0;
    }

    /// Retorna `true` quando Z esta setado.
    public boolean z() {
        return (value & ZERO_FLAG) != 0;
    }

    /// Retorna `true` quando C esta setado.
    public boolean c() {
        return (value & CARRY_FLAG) != 0;
    }

    /// Retorna `true` quando V esta setado.
    public boolean v() {
        return (value & OVERFLOW_FLAG) != 0;
    }

    /// Atualiza os flags NZCV de comparação de uma vez, a partir do valor compactado
    /// nos 4 bits altos de um CPSR (usado por `VCMP` e `VMRS APSR_nzcv`).
    public void setNzcv(int packed) {
        value = (value & ~(NEGATIVE_FLAG | ZERO_FLAG | CARRY_FLAG | OVERFLOW_FLAG))
                | (packed & (NEGATIVE_FLAG | ZERO_FLAG | CARRY_FLAG | OVERFLOW_FLAG));
    }

    /// Serializa o FPSCR para um save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(value);
    }

    /// Restaura o FPSCR gravado por {@link #saveState}.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        value = in.readInt();
    }

    /// Zera o FPSCR (usado ao carregar um save state de formato anterior à B3.3).
    public void reset() {
        value = 0;
    }
}
