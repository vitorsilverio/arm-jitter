package dev.vitorsilverio.armjitter.ir;

/// Operando usado por operacoes IR.
public sealed interface IrOperand permits IrOperand.Register, IrOperand.Immediate {
    /// Operando que referencia um registrador ARM.
    record Register(
            /// Indice do registrador ARM.
            int index) implements IrOperand {
    }

    /// Operando imediato ja expandido pelo decoder.
    record Immediate(
            /// Valor imediato de 32 bits.
            int value) implements IrOperand {
    }
}
