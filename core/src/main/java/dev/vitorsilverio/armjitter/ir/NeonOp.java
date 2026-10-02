package dev.vitorsilverio.armjitter.ir;

/// Operações NEON (Advanced SIMD de 32 bits) e da Cryptographic Extension do AArch32, agrupadas
/// por sub-família.
///
/// Sub-interface selada de {@link IrOp} (task E15.3); os records vivem nas sub-interfaces permitidas.
public sealed interface NeonOp extends IrOp permits NeonIntegerOp, NeonFpOp, NeonMoveOp,
        NeonCryptoOp {
}
