package dev.vitorsilverio.armjitter.ir;

/// Operação-base da aritmética paralela ARMv6 ({@link IrOp.ParallelAlu}): define quais lanes
/// são somadas/subtraídas e a largura de lane. As variantes de prefixo (S/Q/SH/U/UQ/UH) ficam
/// em {@link ParallelAluVariant}.
public enum ParallelAluOp {
    /// Soma paralela dos dois halfwords (`xADD16`).
    ADD16(16),
    /// Cruzada: halfword alto = soma, halfword baixo = subtração (`xASX`, "add-subtract exchange").
    ASX(16),
    /// Cruzada: halfword alto = subtração, halfword baixo = soma (`xSAX`, "subtract-add exchange").
    SAX(16),
    /// Subtração paralela dos dois halfwords (`xSUB16`).
    SUB16(16),
    /// Soma paralela dos quatro bytes (`xADD8`).
    ADD8(8),
    /// Subtração paralela dos quatro bytes (`xSUB8`).
    SUB8(8);

    private final int laneBits;

    ParallelAluOp(int laneBits) {
        this.laneBits = laneBits;
    }

    /// Largura de cada lane em bits (16 para as formas halfword, 8 para as formas byte).
    public int laneBits() {
        return laneBits;
    }
}
