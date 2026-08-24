package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticWidening} (AdvSIMD "three different" widening, B8.7)
/// — `Rn`/`Rm` têm elementos de {@link Ir64Op.VectorArithmeticWidening#esz}, `Rd` tem elementos de
/// `esz+1` (dobro), sempre preenchendo os 128 bits inteiros. `q` seleciona a metade BAIXA (`false`,
/// forma sem `2`) ou ALTA (`true`, forma `*2`) de `Rn`/`Rm` como entrada.
public enum Ir64VectorWideningOp {
    /// `sext(Rn) * sext(Rm)`.
    SMULL,
    /// `zext(Rn) * zext(Rm)`.
    UMULL,
    /// `Rd += sext(Rn) * sext(Rm)` (lê o `Rd` ATUAL, já no tamanho largo).
    SMLAL,
    /// `Rd += zext(Rn) * zext(Rm)` (lê o `Rd` ATUAL, já no tamanho largo).
    UMLAL,
    /// `Rd -= sext(Rn) * sext(Rm)` (lê o `Rd` ATUAL, já no tamanho largo).
    SMLSL,
    /// `Rd -= zext(Rn) * zext(Rm)` (lê o `Rd` ATUAL, já no tamanho largo).
    UMLSL,
    /// `sext(Rn) + sext(Rm)`.
    SADDL,
    /// `zext(Rn) + zext(Rm)`.
    UADDL,
    /// `sext(Rn) - sext(Rm)`.
    SSUBL,
    /// `zext(Rn) - zext(Rm)`.
    USUBL,
    /// `Rd += |sext(Rn)-sext(Rm)|` (lê o `Rd` ATUAL, já no tamanho largo).
    SABAL,
    /// `Rd += |zext(Rn)-zext(Rm)|` (lê o `Rd` ATUAL, já no tamanho largo).
    UABAL,
    /// `|sext(Rn)-sext(Rm)|`.
    SABDL,
    /// `|zext(Rn)-zext(Rm)|`.
    UABDL,
    /// `SignedSaturate(2*sext(Rn)*sext(Rm))` (B8.8, "three different" saturante) — só `esz`
    /// `1`(H→S)/`2`(S→D), sem forma `U=1` (não existe `UQDMULL`).
    SQDMULL,
    /// `Rd = SignedSaturate(Rd_atual + SignedSaturate(2*sext(Rn)*sext(Rm)))` (B8.8) — a MULTIPLICAÇÃO
    /// satura primeiro, DEPOIS a soma satura de novo (duas saturações independentes, conferido
    /// contra o pseudocódigo real `SQDMLAL`).
    SQDMLAL,
    /// Como {@link #SQDMLAL}, mas subtrai (`Rd_atual - SignedSaturate(2*sext(Rn)*sext(Rm))`,
    /// saturando os dois passos) — B8.8.
    SQDMLSL
}
