package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.MinMaxGeneral} (B19.21, subgrupo "Data-processing (2 source)" de
/// "Data Processing — Register", `FEAT_CSSC`).
public enum Ir64MinMaxOp {
    /// `SMAX` (`ARM DDI 0487`): maior dos dois operandos, comparados COM sinal.
    SMAX,
    /// `SMIN` (`ARM DDI 0487`): menor dos dois operandos, comparados COM sinal.
    SMIN,
    /// `UMAX` (`ARM DDI 0487`): maior dos dois operandos, comparados SEM sinal.
    UMAX,
    /// `UMIN` (`ARM DDI 0487`): menor dos dois operandos, comparados SEM sinal.
    UMIN
}
