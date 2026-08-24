package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorShiftImmediate} (AdvSIMD "shift by immediate" não-largo/
/// não-estreito, B8.8) — `Rd`/`Rn` têm o MESMO tamanho de elemento; o deslocamento é um IMEDIATO
/// (`immh:immb` do encoding, já resolvido pelo decoder — nunca recalculado no executor). Cobre
/// também a forma ESCALAR das operações que a aceitam: {@link #SSHR}/{@link #USHR}/
/// {@link #SRSHR}/{@link #URSHR}/{@link #SSRA}/{@link #USRA}/{@link #SRSRA}/{@link #URSRA}/
/// {@link #SHL}/{@link #SLI}/{@link #SRI} são D-only (`esz=3` fixo); {@link #SQSHL}/
/// {@link #UQSHL}/{@link #SQSHLU} aceitam qualquer `esz` — o DECODER valida essa restrição, nunca
/// o executor.
public enum Ir64VectorShiftOp {
    /// Deslocamento à direita assinado (aritmético), truncando — `sext(Rn) >> shift`.
    SSHR,
    /// Deslocamento à direita não assinado (lógico), truncando — `Rn >>> shift`.
    USHR,
    /// Como {@link #SSHR}, com ARREDONDAMENTO (`round = 1 << (shift-1)` somado antes do
    /// deslocamento).
    SRSHR,
    /// Como {@link #USHR}, com ARREDONDAMENTO.
    URSHR,
    /// `Rd += (sext(Rn) >> shift)` — acumula no `Rd` ATUAL, sem arredondamento.
    SSRA,
    /// `Rd += (Rn >>> shift)` — acumula no `Rd` ATUAL, sem arredondamento.
    USRA,
    /// Como {@link #SSRA}, com ARREDONDAMENTO no deslocamento antes de acumular.
    SRSRA,
    /// Como {@link #USRA}, com ARREDONDAMENTO no deslocamento antes de acumular.
    URSRA,
    /// "Shift Right and Insert": desloca `Rn` à direita e insere no `Rd` ATUAL, preservando os
    /// `shift` bits ALTOS de `Rd` (só existe forma não assinada — `U=1` fixo no encoding real).
    SRI,
    /// Deslocamento à esquerda, truncando — `Rn << shift`, sem saturar.
    SHL,
    /// "Shift Left and Insert": desloca `Rn` à esquerda e insere no `Rd` ATUAL, preservando os
    /// `shift` bits BAIXOS de `Rd`.
    SLI,
    /// Deslocamento à esquerda assinado, SATURANTE — `SignedSaturate(sext(Rn) << shift)`.
    SQSHL,
    /// Deslocamento à esquerda não assinado, SATURANTE — `UnsignedSaturate(Rn << shift)`.
    UQSHL,
    /// Deslocamento à esquerda de fonte ASSINADA com saturação NÃO ASSINADA —
    /// `UnsignedSaturate(sext(Rn) << shift)` (satura em `0` se `Rn` for negativo).
    SQSHLU
}
