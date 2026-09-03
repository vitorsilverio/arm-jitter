package dev.vitorsilverio.armjitter.advsimd;

/// Operação "shift by immediate" (não-largo/não-estreito) do núcleo vetorial COMPARTILHADO entre
/// AArch64 (`SSHR`/`USHR`/`SSRA`/.../`SQSHLU`, B8.8) e NEON de 32 bits (`VSHR`/`VSRA`/`VRSHR`/
/// `VSRI`/`VSHL`/`VSLI`/`VQSHL`/`VQSHLU`, B13.7) — RFC B13.2, decisão D1 (reuso, não espelhamento).
///
/// `Rd`/`Rn` têm o MESMO tamanho de elemento; o deslocamento é um IMEDIATO já resolvido pelo
/// decoder (`immh:immb`), NUNCA recalculado no executor. Espelho EXATO de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp} (mesmos 14 nomes): a semântica de
/// lane vive só em {@link AdvSimdLanes#shiftImmediate}, e o lado A64 apenas delega.
public enum AdvSimdShiftImmediateOp {
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
