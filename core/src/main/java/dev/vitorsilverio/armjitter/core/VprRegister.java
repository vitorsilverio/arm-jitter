package dev.vitorsilverio.armjitter.core;

/// Representa o `VPR` (Vector Predication Register, ARMv8.1-M Helium/MVE, `Armv8-M Architecture
/// Reference Manual` B4.24) — três campos empacotados num inteiro de 32 bits (verbatim do QEMU
/// real, `target/arm/cpu.h`):
///
/// ```c
/// FIELD(V7M_VPR, P0, 0, 16)
/// FIELD(V7M_VPR, MASK01, 16, 4)
/// FIELD(V7M_VPR, MASK23, 20, 4)
/// ```
///
/// `P0` é a máscara de predicação por byte (16 bits, um por byte de um `Q` de 128 bits); `MASK01`/
/// `MASK23` são as máscaras de 4 bits usadas por `VPT`/instruções de deslocamento estreitante
/// sobre os pares de metades (0-1 e 2-3) de um registrador. Esta classe (B16.1) é **fundação de
/// armazenamento apenas** — nenhuma lógica de predicação (`mve_element_mask`/`mve_advance_vpt`/
/// `ECI`) vive aqui, isso é a B16.2, o coração da extensão MVE.
///
/// **`VPR` NÃO é registrador memory-mapped do SCS** (diferente de {@link MProfileSystemControl},
/// que despacha por offset de `0xE000E000`): é registrador de CPU, lido/escrito por `VMSR`/`VMRS`
/// (`reg=12`) e salvo/restaurado no frame de exceção de ponto flutuante do perfil M — mesma
/// categoria de {@link FpscrRegister}, que esta classe espelha estruturalmente.
public final class VprRegister {
    /// Deslocamento do campo `P0` (máscara de predicação por byte, 16 bits).
    public static final int P0_SHIFT = 0;
    /// Máscara do campo `P0` (16 bits), já posicionada em `P0_SHIFT`.
    public static final int P0_MASK = 0xFFFF << P0_SHIFT;
    /// Deslocamento do campo `MASK01` (predicação do par de metades 0-1, 4 bits).
    public static final int MASK01_SHIFT = 16;
    /// Máscara do campo `MASK01` (4 bits), já posicionada em `MASK01_SHIFT`.
    public static final int MASK01_MASK = 0xF << MASK01_SHIFT;
    /// Deslocamento do campo `MASK23` (predicação do par de metades 2-3, 4 bits).
    public static final int MASK23_SHIFT = 20;
    /// Máscara do campo `MASK23` (4 bits), já posicionada em `MASK23_SHIFT`.
    public static final int MASK23_MASK = 0xF << MASK23_SHIFT;

    private int value;

    /// Retorna o valor bruto de 32 bits do `VPR` (bits 31:24 são reservados/RES0 na arquitetura
    /// real, mas esta classe não os força — o chamador decide se zera antes de gravar).
    public int value() {
        return value;
    }

    /// Substitui o valor bruto de 32 bits do `VPR` (usado por `VMSR` sobre `reg=12`).
    public void setValue(int value) {
        this.value = value;
    }

    /// Lê o campo `P0` (16 bits, máscara de predicação por byte).
    public int p0() {
        return (value & P0_MASK) >>> P0_SHIFT;
    }

    /// Grava o campo `P0`, sem afetar `MASK01`/`MASK23`/os bits reservados. Só os 16 bits baixos
    /// de `p0` são considerados (valores que transbordam são truncados, nunca propagados).
    public void setP0(int p0) {
        value = (value & ~P0_MASK) | ((p0 << P0_SHIFT) & P0_MASK);
    }

    /// Lê o campo `MASK01` (4 bits, predicação do par de metades 0-1).
    public int mask01() {
        return (value & MASK01_MASK) >>> MASK01_SHIFT;
    }

    /// Grava o campo `MASK01`, sem afetar `P0`/`MASK23`/os bits reservados. Só os 4 bits baixos de
    /// `mask01` são considerados (valores que transbordam são truncados, nunca propagados).
    public void setMask01(int mask01) {
        value = (value & ~MASK01_MASK) | ((mask01 << MASK01_SHIFT) & MASK01_MASK);
    }

    /// Lê o campo `MASK23` (4 bits, predicação do par de metades 2-3).
    public int mask23() {
        return (value & MASK23_MASK) >>> MASK23_SHIFT;
    }

    /// Grava o campo `MASK23`, sem afetar `P0`/`MASK01`/os bits reservados. Só os 4 bits baixos de
    /// `mask23` são considerados (valores que transbordam são truncados, nunca propagados).
    public void setMask23(int mask23) {
        value = (value & ~MASK23_MASK) | ((mask23 << MASK23_SHIFT) & MASK23_MASK);
    }

    /// Serializa o `VPR` para um save state (método NOVO — nunca chamado como efeito colateral de
    /// {@link ArmCore#saveState}, que preserva o formato existente byte a byte; ver
    /// {@link ArmCore#saveStateVpr}).
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(value);
    }

    /// Restaura o `VPR` gravado por {@link #saveState}.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        value = in.readInt();
    }

    /// Zera o `VPR` (estado de reset arquitetural).
    public void reset() {
        value = 0;
    }
}
