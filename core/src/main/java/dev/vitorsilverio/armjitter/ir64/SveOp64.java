package dev.vitorsilverio.armjitter.ir64;

/// Operações SVE/SVE2 do A64, agrupadas por sub-família.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2); os records vivem nas sub-interfaces permitidas.
public sealed interface SveOp64 extends Ir64Op permits SvePredicateOp64, SveIntegerOp64, SveFpOp64,
        SveMemoryOp64 {
}
