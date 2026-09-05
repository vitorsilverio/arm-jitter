package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "narrow unary" — reduz um elemento de `esz+1` bytes (`Rn`) para `esz` bytes
/// (`Rd`). Núcleo COMPARTILHADO (RFC B13.2, D1) migrado de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp} (B8.8/B8.20) na task B13.12 —
/// mirror EXATO (os 4 valores do A64 têm equivalente 1:1 no NEON de 32 bits: `VQMOVN_S`/
/// `VQMOVUN`/`VQMOVN_U`/`VMOVN`).
public enum AdvSimdNarrowUnaryOp {
    /// `SignedSaturate(sext(Rn))` para `esz` bytes — entrada e saída assinadas.
    SQXTN,
    /// `UnsignedSaturate(sext(Rn))` para `esz` bytes — entrada ASSINADA, saída NÃO assinada.
    SQXTUN,
    /// `UnsignedSaturate(Rn)` para `esz` bytes — entrada e saída não assinadas.
    UQXTN,
    /// `Truncate(Rn)` para `esz` bytes — SEM saturação.
    XTN
}
