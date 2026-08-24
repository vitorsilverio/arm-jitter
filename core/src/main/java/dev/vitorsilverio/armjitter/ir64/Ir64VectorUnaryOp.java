package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticUnary} (AdvSIMD "two-register miscellaneous", B8.7)
/// — um único operando de origem (`Rn`), inteiro. Cobre também a forma ESCALAR (`ABS_s`/`NEG_s`/
/// `CM**0_s`, sempre `esz=3`/`q=false`, mesmo truque de {@link Ir64Op.VectorArithmeticThreeSame}).
public enum Ir64VectorUnaryOp {
    /// `|sext(Rn)|`.
    ABS,
    /// `-Rn` truncado.
    NEG,
    /// `Rn == 0` — elemento vira todos-1 ou `0`.
    CMEQ0,
    /// `sext(Rn) > 0` — elemento vira todos-1 ou `0`.
    CMGT0,
    /// `sext(Rn) >= 0` — elemento vira todos-1 ou `0`.
    CMGE0,
    /// `sext(Rn) < 0` — elemento vira todos-1 ou `0`.
    CMLT0,
    /// `sext(Rn) <= 0` — elemento vira todos-1 ou `0`.
    CMLE0,
    /// Pareamento largo assinado: `sext(Rn[2i]) + sext(Rn[2i+1])`, resultado em `esz+1`.
    SADDLP,
    /// Pareamento largo não assinado: `zext(Rn[2i]) + zext(Rn[2i+1])`, resultado em `esz+1`.
    UADDLP,
    /// Como {@link #SADDLP}, mas ACUMULA no `Rd` ATUAL (já em `esz+1`) em vez de sobrescrever.
    SADALP,
    /// Como {@link #UADDLP}, mas ACUMULA no `Rd` ATUAL (já em `esz+1`) em vez de sobrescrever.
    UADALP,
    /// `SignedSaturate(sext(Rd_atual) + zext(Rn))` (B8.8, "signed saturating accumulate of
    /// unsigned value") — acumula no MESMO tamanho de elemento (ao contrário de `SADALP`, que
    /// alarga). Forma escalar aceita qualquer `esz` (`0`-`3`).
    SUQADD,
    /// `UnsignedSaturate(zext(Rd_atual) + sext(Rn))` (B8.8, "unsigned saturating accumulate of
    /// signed value") — satura em `0` por baixo se `Rn` for bem negativo. Forma escalar aceita
    /// qualquer `esz`.
    USQADD
}
