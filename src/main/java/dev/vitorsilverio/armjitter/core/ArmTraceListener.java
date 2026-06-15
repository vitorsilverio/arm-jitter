package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Listener leve para observar execução sem acoplar o core a logging ou UI.
public interface ArmTraceListener {
    /// Retorna um listener que ignora todos os eventos.
    ///
    /// @return listener sem efeito colateral
    static ArmTraceListener none() {
        return NoOpArmTraceListener.INSTANCE;
    }

    /// Chamado antes de uma instrução ser buscada e executada pelo interpretador.
    ///
    /// @param core core observado
    /// @param pc endereço de fetch antes da execução
    /// @param instructionSet conjunto ARM ou THUMB ativo antes da execução
    default void beforeInstruction(ArmCore core, int pc, InstructionSet instructionSet) {
    }

    /// Chamado depois que uma instrução foi decodificada e executada pelo interpretador.
    ///
    /// @param core core observado após a execução
    /// @param instruction instrução decodificada que acabou de executar
    default void afterInstruction(ArmCore core, DecodedInstruction instruction) {
    }

    /// Chamado antes de um bloco ser entregue ao runtime JIT.
    ///
    /// @param core core observado
    /// @param pc endereço inicial do bloco
    /// @param instructionSet conjunto ARM ou THUMB ativo no início do bloco
    default void beforeBlock(ArmCore core, int pc, InstructionSet instructionSet) {
    }

    /// Chamado depois que um bloco retornou do runtime JIT.
    ///
    /// @param core core observado após o bloco
    /// @param startPc endereço inicial do bloco
    /// @param instructionSet conjunto ARM ou THUMB usado para iniciar o bloco
    /// @param cycles ciclos reportados pelo runtime
    default void afterBlock(ArmCore core, int startPc, InstructionSet instructionSet, int cycles) {
    }
}
