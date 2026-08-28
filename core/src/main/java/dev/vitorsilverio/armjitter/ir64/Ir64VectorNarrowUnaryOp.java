package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticNarrowUnary} (AdvSIMD "narrow unary", B8.8) — reduz um
/// elemento de `esz+1` bytes (`Rn`) para `esz` bytes (`Rd`). Vive no MESMO slot de encoding
/// `Rm=00001` ("two-register misc narrow/widen") que B8.7 deixou explicitamente de fora
/// (`ADVSIMD_INT_RM_NARROW_UNARY`, compartilhado com `FCVTXN`/outras conversões FP, fora de escopo
/// — B8.9). {@link #XTN} (B8.20) é a ÚNICA exceção que NÃO satura (truncamento puro) — as outras 3
/// saturam.
public enum Ir64VectorNarrowUnaryOp {
    /// `SignedSaturate(sext(Rn))` para `esz` bytes — entrada e saída assinadas.
    SQXTN,
    /// `UnsignedSaturate(sext(Rn))` para `esz` bytes — entrada ASSINADA, saída NÃO assinada
    /// (satura em `0` por baixo se `Rn` for negativo).
    SQXTUN,
    /// `UnsignedSaturate(Rn)` para `esz` bytes — entrada e saída não assinadas.
    UQXTN,
    /// `Truncate(Rn)` para `esz` bytes (B8.20) — SEM saturação, mesmo opcode de {@link #SQXTUN}
    /// (`U=0`). Sem forma escalar real (diferente das outras 3).
    XTN
}
