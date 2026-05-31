package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.Condition;

/// Instrucao ARM/THUMB decodificada em um formato neutro inicial.
public record DecodedInstruction(
        /// Endereco de origem da instrucao.
        int address,
        /// Palavra ou halfword bruta lida da memoria.
        int raw,
        /// Conjunto de instrucoes usado.
        InstructionSet instructionSet,
        /// Condicao ARM associada; em THUMB normal costuma ser `AL`.
        Condition condition,
        /// Tipo semantico decodificado.
        InstructionKind kind,
        /// Registrador de destino, ou `-1` quando nao se aplica.
        int destinationRegister,
        /// Primeiro registrador de origem, ou `-1` quando nao se aplica.
        int sourceRegister,
        /// Segundo registrador de origem, ou `-1` quando nao se aplica.
        int secondSourceRegister,
        /// Imediato ja expandido, offset de branch ou numero de SWI.
        int immediate,
        /// Indica que o imediato deve ser usado como segundo operando.
        boolean immediateOperand,
        /// Indica que a instrucao atualiza NZCV.
        boolean setFlags,
        /// Indica que branch deve atualizar o link register.
        boolean link,
        /// Tamanho do acesso de memoria em bytes.
        int accessSizeBytes,
        /// Indica que load deve fazer extensao com sinal.
        boolean signedAccess,
        /// Indica writeback no registrador base.
        boolean writeback) {

    /// Construtor compacto para instrucoes sem acesso de memoria.
    public DecodedInstruction(
            int address,
            int raw,
            InstructionSet instructionSet,
            Condition condition,
            InstructionKind kind,
            int destinationRegister,
            int sourceRegister,
            int secondSourceRegister,
            int immediate,
            boolean immediateOperand,
        boolean setFlags,
        boolean link) {
        this(address, raw, instructionSet, condition, kind, destinationRegister, sourceRegister, secondSourceRegister,
                immediate, immediateOperand, setFlags, link, 0, false, false);
    }

    /// Construtor compacto para instrucoes com acesso de memoria.
    public DecodedInstruction(
            int address,
            int raw,
            InstructionSet instructionSet,
            Condition condition,
            InstructionKind kind,
            int destinationRegister,
            int sourceRegister,
            int secondSourceRegister,
            int immediate,
            boolean immediateOperand,
            boolean setFlags,
            boolean link,
            int accessSizeBytes,
            boolean signedAccess) {
        this(address, raw, instructionSet, condition, kind, destinationRegister, sourceRegister, secondSourceRegister,
                immediate, immediateOperand, setFlags, link, accessSizeBytes, signedAccess, false);
    }

    /// Cria uma instrucao nao implementada preservando endereco, bits e modo.
    public static DecodedInstruction unimplemented(int address, int raw, InstructionSet instructionSet, Condition condition) {
        return new DecodedInstruction(address, raw, instructionSet, condition, InstructionKind.UNIMPLEMENTED,
                -1, -1, -1, 0, false, false, false, 0, false, false);
    }
}
