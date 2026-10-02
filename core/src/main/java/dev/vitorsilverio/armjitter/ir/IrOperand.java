package dev.vitorsilverio.armjitter.ir;

/// Operando usado por operações IR.
public sealed interface IrOperand permits IrOperand.Register, IrOperand.Immediate, IrOperand.ShiftedRegister {
    /// Registradores ARM lidos pelo operando, um bit por registrador (`r0..r15`) — a parte do
    /// operando em {@link IrOp#regUse()} (task E15.6). Registrador com `valueOverride` não é lido.
    ///
    /// @return bitmask dos registradores lidos
    int regUse();

    /// Operando que referencia um registrador ARM.
    record Register(
            /// Índice do registrador ARM.
            int index,
            /// Valor fixo para usar no lugar do registrador, ou `-1`.
            int valueOverride) implements IrOperand {
        @Override public int regUse() { return valueOverride < 0 ? (1 << index) : 0; }

        /// Cria uma referência direta a registrador.
        /// @param index índice do registrador ARM.
        public Register(int index) {
            this(index, -1);
        }
    }

    /// Operando imediato já expandido pelo decoder.
    record Immediate(
            /// Valor imediato de 32 bits.
            int value,
            /// Indica que o barrel shifter ARM produziu um carry conhecido.
            boolean carryOutKnown,
            /// Carry produzido pelo barrel shifter ARM quando conhecido.
            boolean carryOut) implements IrOperand {
        @Override public int regUse() { return 0; }

        /// Cria um imediato sem carry explícito do barrel shifter.
        /// @param value valor imediato de 32 bits.
        public Immediate(int value) {
            this(value, false, false);
        }
    }

    /// Operando de registrador passado pelo barrel shifter ARM.
    record ShiftedRegister(
            /// Índice do registrador ARM.
            int index,
            /// Tipo de deslocamento aplicado.
            ShiftType shiftType,
            /// Quantidade imediata de deslocamento.
            int amount,
            /// Registrador que fornece a quantidade de deslocamento, ou `-1`.
            int amountRegister,
            /// Valor fixo para o registrador deslocado, ou `-1`.
            int valueOverride,
            /// Valor fixo para o registrador de quantidade, ou `-1`.
            int amountValueOverride,
            /// Indica o caso especial ARM `RRX`.
            boolean rrx,
            /// Indica que o resultado deslocado deve ser negado.
            boolean negated) implements IrOperand {
        @Override public int regUse() {
            int mask = valueOverride < 0 ? (1 << index) : 0;
            if (amountRegister >= 0 && amountValueOverride < 0) mask |= 1 << amountRegister;
            return mask;
        }

        /// Cria um operando deslocado positivo.
        /// @param index índice do registrador ARM.
        /// @param shiftType tipo de deslocamento aplicado.
        /// @param amount quantidade imediata de deslocamento.
        public ShiftedRegister(int index, ShiftType shiftType, int amount) {
            this(index, shiftType, amount, -1, -1, -1, false, false);
        }

        /// Cria um operando deslocado com sinal opcional.
        /// @param index índice do registrador ARM.
        /// @param shiftType tipo de deslocamento aplicado.
        /// @param amount quantidade imediata de deslocamento.
        /// @param negated indica que o resultado deslocado deve ser negado.
        public ShiftedRegister(int index, ShiftType shiftType, int amount, boolean negated) {
            this(index, shiftType, amount, -1, -1, -1, false, negated);
        }

        /// Cria um operando deslocado ARM completo.
        /// @param index índice do registrador ARM.
        /// @param shiftType tipo de deslocamento aplicado.
        /// @param amount quantidade imediata de deslocamento.
        /// @param amountRegister registrador que fornece a quantidade, ou `-1`.
        /// @param rrx indica o caso especial ARM `RRX`.
        /// @param negated indica que o resultado deslocado deve ser negado.
        public ShiftedRegister(
                int index,
                ShiftType shiftType,
                int amount,
                int amountRegister,
                boolean rrx,
                boolean negated) {
            this(index, shiftType, amount, amountRegister, -1, -1, rrx, negated);
        }
    }
}
