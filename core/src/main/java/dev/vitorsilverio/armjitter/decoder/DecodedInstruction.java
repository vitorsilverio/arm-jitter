package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.Condition;

/// Instrução ARM/THUMB decodificada em um formato neutro inicial.
public record DecodedInstruction(
        /// Endereço de origem da instrução.
        int address,
        /// Palavra ou halfword bruta lida da memória.
        int raw,
        /// Conjunto de instruções usado.
        InstructionSet instructionSet,
        /// Condição ARM associada; em THUMB normal costuma ser `AL`.
        Condition condition,
        /// Tipo semântico decodificado.
        InstructionKind kind,
        /// Registrador de destino, ou `-1` quando não se aplica.
        int destinationRegister,
        /// Primeiro registrador de origem, ou `-1` quando não se aplica.
        int sourceRegister,
        /// Segundo registrador de origem, ou `-1` quando não se aplica.
        int secondSourceRegister,
        /// Imediato já expandido, offset de branch ou número de SWI.
        int immediate,
        /// Indica que o imediato deve ser usado como segundo operando.
        boolean immediateOperand,
        /// Indica que a instrução atualiza NZCV.
        boolean setFlags,
        /// Indica que branch deve atualizar o link register.
        boolean link,
        /// Tamanho do acesso de memória em bytes.
        int accessSizeBytes,
        /// Indica que load deve fazer extensão com sinal.
        boolean signedAccess,
        /// Indica writeback no registrador base.
        boolean writeback,
        /// Indica endereçamento post-index para load/store simples.
        boolean postIndexed,
        /// Modo de endereçamento para transferências múltiplas.
        BlockTransferMode blockTransferMode,
        /// Indica máscara vazia em `LDM`/`STM`, caso especial do ARM7TDMI.
        boolean emptyRegisterList) {

    /// Construtor compacto para instruções sem acesso de memória.
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
                immediate, immediateOperand, setFlags, link, 0, false, false, false, BlockTransferMode.IA, false);
    }

    /// Construtor compacto para instruções com acesso de memória.
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
                immediate, immediateOperand, setFlags, link, accessSizeBytes, signedAccess, false, false,
                BlockTransferMode.IA, false);
    }

    /// Construtor compacto para instruções com acesso de memória e writeback.
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
            boolean signedAccess,
            boolean writeback) {
        this(address, raw, instructionSet, condition, kind, destinationRegister, sourceRegister, secondSourceRegister,
                immediate, immediateOperand, setFlags, link, accessSizeBytes, signedAccess, writeback,
                false, BlockTransferMode.IA, false);
    }

    /// Construtor compacto para instruções com acesso de memória, writeback e post-index.
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
            boolean signedAccess,
            boolean writeback,
            boolean postIndexed) {
        this(address, raw, instructionSet, condition, kind, destinationRegister, sourceRegister, secondSourceRegister,
                immediate, immediateOperand, setFlags, link, accessSizeBytes, signedAccess, writeback, postIndexed,
                BlockTransferMode.IA, false);
    }

    /// Construtor compacto para transferencias multiplas ARM com modo explicito.
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
            boolean signedAccess,
            boolean writeback,
            boolean postIndexed,
            BlockTransferMode blockTransferMode) {
        this(address, raw, instructionSet, condition, kind, destinationRegister, sourceRegister, secondSourceRegister,
                immediate, immediateOperand, setFlags, link, accessSizeBytes, signedAccess, writeback, postIndexed,
                blockTransferMode, false);
    }

    /// Cria uma instrucao nao implementada preservando endereco, bits e modo.
    public static DecodedInstruction unimplemented(int address, int raw, InstructionSet instructionSet, Condition condition) {
        return new DecodedInstruction(address, raw, instructionSet, condition, InstructionKind.UNIMPLEMENTED,
                -1, -1, -1, 0, false, false, false, 0, false, false, false, BlockTransferMode.IA, false);
    }

    /// Cria uma cópia com a condição substituída, preservando todos os outros campos. Usado por
    /// {@code StandardIrBlockLifter} (B2.4) para anotar a condição por-op vinda de um Thumb-2 IT
    /// block ativo — a instrução é decodificada normalmente (com sua condição "natural", quase
    /// sempre AL em Thumb) e o lifter sobrepõe a condição correta APÓS o decode, sem precisar
    /// plumbing de estado IT através do decoder em si (que permanece stateless — ver D1 em
    /// `b2.4-thumb2-branches-it.md`).
    public DecodedInstruction withCondition(Condition newCondition) {
        return new DecodedInstruction(address, raw, instructionSet, newCondition, kind, destinationRegister,
                sourceRegister, secondSourceRegister, immediate, immediateOperand, setFlags, link,
                accessSizeBytes, signedAccess, writeback, postIndexed, blockTransferMode, emptyRegisterList);
    }
}
