package dev.vitorsilverio.armjitter.ir;

/// Operações MVE (Helium, ARMv8.1-M), agrupadas por sub-família.
///
/// Sub-interface selada de {@link IrOp} (task E15.3); os records vivem nas sub-interfaces permitidas.
public sealed interface MveOp extends IrOp permits MvePredicationOp, MveMoveOp, MveIntegerOp,
        MveFpOp, MveReductionOp {

    /// Default da família (task E15.6b): toda op MVE predicada por elemento lê o `LR`, o contador
    /// de tail-predication que `MveVptState#elementMask` consulta quando `FPSCR.LTPSIZE < 4`. Records
    /// que também leem outro GPR, ou que não consultam o predicado, sobrescrevem.
    @Override
    default int regUse() {
        return GprMask.MVE_TAIL_PREDICATION;
    }
}
