package dev.vitorsilverio.armjitter.ir;

/// Operações MVE (Helium, ARMv8.1-M), agrupadas por sub-família.
///
/// Sub-interface selada de {@link IrOp} (task E15.3); os records vivem nas sub-interfaces permitidas.
public sealed interface MveOp extends IrOp permits MvePredicationOp, MveMoveOp, MveIntegerOp,
        MveFpOp, MveReductionOp {
}
