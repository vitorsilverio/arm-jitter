package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Listener leve para observar execucao sem acoplar o core a logging ou UI.
public interface ArmTraceListener {
    /// Retorna um listener que ignora todos os eventos.
    ///
    /// @return listener sem efeito colateral
    static ArmTraceListener none() {
        return NoOpArmTraceListener.INSTANCE;
    }

    /// Chamado antes de uma instrucao ser buscada e executada pelo interpretador.
    ///
    /// @param core core observado
    /// @param pc endereco de fetch antes da execucao
    /// @param instructionSet conjunto ARM ou THUMB ativo antes da execucao
    default void beforeInstruction(ArmCore core, int pc, InstructionSet instructionSet) {
    }

    /// Chamado depois que uma instrucao foi decodificada e executada pelo interpretador.
    ///
    /// @param core core observado apos a execucao
    /// @param instruction instrucao decodificada que acabou de executar
    default void afterInstruction(ArmCore core, DecodedInstruction instruction) {
    }

    /// Chamado antes de um bloco ser entregue ao runtime JIT.
    ///
    /// @param core core observado
    /// @param pc endereco inicial do bloco
    /// @param instructionSet conjunto ARM ou THUMB ativo no inicio do bloco
    default void beforeBlock(ArmCore core, int pc, InstructionSet instructionSet) {
    }

    /// Chamado depois que um bloco retornou do runtime JIT.
    ///
    /// @param core core observado apos o bloco
    /// @param startPc endereco inicial do bloco
    /// @param instructionSet conjunto ARM ou THUMB usado para iniciar o bloco
    /// @param cycles ciclos reportados pelo runtime
    default void afterBlock(ArmCore core, int startPc, InstructionSet instructionSet, int cycles) {
    }
}
