package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

/// E15.16a: o que as classes `A32*Rows` têm em comum — os três tipos de linha e os campos.
final class A32Rows {
    /// Registrador PC: em vários encodings é UNPREDICTABLE e o decoder recusa.
    static final int PROGRAM_COUNTER = 15;
    private static final int CONDITION_SHIFT = 28;

    private A32Rows() {
    }

    /// Linha da ISA base (ARMv4T).
    static DecodeRow<ArmFeature, DecodedInstruction> row(String pattern,
            DecodeRow.WordDecoder<DecodedInstruction> build) {
        return DecodeRow.of(pattern, null, build);
    }

    /// Linha que some sem a feature: a palavra segue para as camadas de baixo (o
    /// `if (casa && has(F))` da cascata antiga).
    static DecodeRow<ArmFeature, DecodedInstruction> optional(String pattern, ArmFeature requires,
            DecodeRow.WordDecoder<DecodedInstruction> build) {
        return DecodeRow.of(pattern, requires, build);
    }

    /// Linha cujo espaço é da instrução mesmo sem a feature: aí a palavra é indefinida (o
    /// `if (casa) { if (!has(F)) return indefinida; … }` da cascata antiga).
    static DecodeRow<ArmFeature, DecodedInstruction> claimed(String pattern, ArmFeature requires,
            DecodeRow.WordDecoder<DecodedInstruction> build) {
        return DecodeRow.of(pattern, requires, build).orWhenAbsent(A32Rows::undefined);
    }

    /// A condição do nibble `bits[31:28]`.
    static Condition condition(int raw) {
        return ArmDecoder.decodeCondition(raw >>> CONDITION_SHIFT);
    }

    /// Instrução indefinida (`UNIMPLEMENTED`) para `raw`.
    static DecodedInstruction undefined(int raw, long address) {
        return DecodedInstruction.unimplemented((int) address, raw, InstructionSet.ARM, condition(raw));
    }

    /// Instrução condicional sem os campos de acesso à memória.
    static DecodedInstruction instruction(int raw, long address, InstructionKind kind, int rd, int rn, int rm,
            int immediate, boolean immediateOperand, boolean setFlags, boolean link) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw), kind, rd, rn, rm,
                immediate, immediateOperand, setFlags, link);
    }

    /// Instrução do espaço `cond=1111`, que grava `AL`.
    static DecodedInstruction unconditional(int raw, long address, InstructionKind kind, int rn, int immediate,
            boolean immediateOperand, boolean link) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, Condition.AL, kind, -1, rn, -1,
                immediate, immediateOperand, false, link);
    }

    /// O campo de 4 bits que começa em `shift`.
    static int nibble(int raw, int shift) {
        return (raw >>> shift) & 0xF;
    }

    /// `true` quando o bit `index` de `raw` está ligado.
    static boolean bit(int raw, int index) {
        return (raw & (1 << index)) != 0;
    }
}
