package dev.vitorsilverio.armjitter.ir64;

/// Operações AdvSIMD (NEON de 64 bits) e da Cryptographic Extension do A64, agrupadas por
/// sub-família.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2); os records vivem nas sub-interfaces permitidas.
public sealed interface AdvSimdOp64 extends Ir64Op permits AdvSimdIntegerOp64, AdvSimdFpOp64,
        AdvSimdMoveOp64, CryptoOp64 {
}
