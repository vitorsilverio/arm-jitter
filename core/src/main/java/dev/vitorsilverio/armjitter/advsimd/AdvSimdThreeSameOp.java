package dev.vitorsilverio.armjitter.advsimd;

/// Operação "three same" (dois operandos vetoriais do MESMO arranjo, resultado no mesmo arranjo)
/// do núcleo vetorial COMPARTILHADO entre AArch64 (`ADD_v`/`SUB_v`, B8.7) e NEON de 32 bits
/// (`VADD`/`VSUB` inteiro, B13.4) — RFC B13.2.
///
/// **Protótipo (B13.2)**: só as duas operações que a RFC mediu. A migração das ~60 operações
/// restantes de {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp} para cá é o
/// primeiro passo de B13.4; enquanto uma operação não está aqui, o executor A64 continua usando
/// seu próprio `switch` (ver `Ir64VectorArithmeticExecutor#sharedThreeSameOp`), sem duplicação —
/// cada operação existe em exatamente UM lugar por vez.
public enum AdvSimdThreeSameOp {
    /// Soma por elemento, sem saturação (`ADD_v` no A64, `VADD` inteiro no NEON de 32 bits).
    ADD,
    /// Subtração por elemento, sem saturação (`SUB_v` no A64, `VSUB` inteiro no NEON de 32 bits).
    SUB
}
