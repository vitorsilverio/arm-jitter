package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.MoveWide} (`ARM DDI 0487 C6.2.203/205/206`).
public enum Ir64MoveWideOp {
    /// `MOVN`: `Rd = ~(imm16 << shift)`, zero-estendido/truncado conforme a largura.
    MOVN,
    /// `MOVZ`: `Rd = imm16 << shift`.
    MOVZ,
    /// `MOVK`: `Rd = (Rd & ~(0xFFFF << shift)) | (imm16 << shift)` — preserva os demais bits.
    MOVK
}
