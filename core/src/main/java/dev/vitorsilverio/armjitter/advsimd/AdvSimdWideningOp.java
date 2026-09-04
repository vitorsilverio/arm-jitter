package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "three different" ALARGANDO (widening "Long" form, B13.10/B8.7) — `Rn`/`Rm` têm
/// elementos de {@code esz} bytes, `Rd` recebe elementos de {@code esz+1} (dobro), sempre
/// preenchendo o operando de destino inteiro. Mirror exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp} (A64, B8.7/B8.8/B8.20), mais
/// {@link #PMULL} — que não existe no lado A64 como membro deste enum (o A64 tem `PMULL`/`PMULL2`
/// como `IrOp` próprio, `VectorPolynomialMultiplyLong`, fora desta família) mas é a MESMA operação
/// que `VMULL.P8` do NEON A32 precisa (B13.10), reaproveitando
/// {@link AdvSimdLanes#polynomialMultiply8}.
public enum AdvSimdWideningOp {
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
    /// `SignedSaturate(2*sext(Rn)*sext(Rm))` — só `esz` `0`(B→H)/`1`(H→S)/`2`(S→D), sem forma
    /// `U=1` (não existe `UQDMULL`/`VQDMULL.U`).
    SQDMULL,
    /// `Rd = SignedSaturate(Rd_atual + SignedSaturate(2*sext(Rn)*sext(Rm)))` — a MULTIPLICAÇÃO
    /// satura primeiro, DEPOIS a soma satura de novo (duas saturações independentes).
    SQDMLAL,
    /// Como {@link #SQDMLAL}, mas subtrai (saturando os dois passos).
    SQDMLSL,
    /// `PolynomialMult(Rn, Rm)` (`GF(2)`, `VMULL.P8`, B13.10) — só `esz=0` (byte→halfword); sem
    /// carry, sem sinal. Reusa {@link AdvSimdLanes#polynomialMultiply8}, a MESMA função que
    /// `PMULL`/`PMULL2` `.p8` do A64 chama.
    PMULL
}
