package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;

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

    /// Chamado por {@link ArmCore#enterMemoryAbort} no exato momento em que uma
    /// {@link MemoryTranslationException} é convertida em `PREFETCH_ABORT`/`DATA_ABORT` — ANTES de
    /// qualquer mutação de estado (PC/CPSR/banco de registradores da exceção), com o PC ainda
    /// apontando para a instrução faltosa real.
    ///
    /// Diferente de {@link #beforeInstruction}/{@link #afterInstruction}, este gancho dispara nos
    /// TRÊS caminhos de execução (`ArmCore#step()`, blocos interpretados via `IrBlockExecutor` e
    /// blocos compilados via `AsmBlockCompiler`/JIT) porque `enterMemoryAbort` é o único ponto de
    /// convergência dos três — `beforeInstruction`/`afterInstruction` só disparam sob `step()`, uma
    /// lacuna de cobertura real sob `ArmCore#runBlocks`/`JitRuntime#execute` (nenhum evento
    /// por-instrução existe ali, blocos são executados atomicamente por desenho/desempenho).
    /// Instrumentação adicionada para diagnosticar exatamente esse tipo de falha (achado da task
    /// F3/`virtual-arm-box`, sessão de fechamento): quando um bloco falha por uma falta de memória,
    /// este é o único gancho que reporta o PC exato da instrução culpada independente do backend
    /// que a executou.
    ///
    /// @param core core observado, ainda com o PC pré-abort (== `instructionAddress`)
    /// @param instructionAddress endereço da instrução que causou a falta (não o PC de retorno)
    /// @param fault falha capturada (tipo de acesso, endereço virtual, status)
    default void onMemoryAbort(ArmCore core, int instructionAddress, MemoryTranslationException fault) {
    }
}
