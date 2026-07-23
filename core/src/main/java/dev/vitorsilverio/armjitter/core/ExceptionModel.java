package dev.vitorsilverio.armjitter.core;

/// Modelo de entrada/retorno de exceção plugável por perfil de arquitetura (A/R vs M —
/// RFC-M-PROFILE). Extraído de {@link ArmCore#handleException} na B7.1 (refactor zero-diff);
/// o perfil A é {@link AProfileExceptionModel}, instalado por padrão em todo {@link ArmCore}.
public interface ExceptionModel {
    /// Entrada síncrona/assíncrona de exceção (SWI/IRQ/FIQ/undefined/aborts/reset).
    void enterException(ArmCore core, ArmException exception);

    /// Um branch/load-para-PC com este alvo é interceptado pelo modelo?
    ///
    /// Perfil A: sempre {@code false}. Perfil M (B7.2+): {@code true} para valores de
    /// `EXC_RETURN` (`0xFFFFFFF0` em diante).
    default boolean interceptsBranch(int target) {
        return false;
    }

    /// Executa a interceptação de um branch/load-para-PC reconhecido por {@link #interceptsBranch}.
    ///
    /// Perfil A: nunca chamado (nenhum alvo é interceptado) — lança {@link IllegalStateException}.
    default void branchIntercepted(ArmCore core, int target) {
        throw new IllegalStateException("branchIntercepted chamado sem um alvo interceptado");
    }

    /// O modelo despacha `SVC`/`SWI` internamente (como entrada de exceção própria) em vez do
    /// caminho comum {@link ArmCore#swiDispatcher()}?
    ///
    /// Perfil A: sempre {@code false} (dispatcher de SWI de sempre). Perfil M (B7.2+):
    /// {@code true} — `SVC` sempre vira {@link MProfileException#SVCALL}, nunca consulta o
    /// {@code SwiDispatcher}.
    default boolean handlesSupervisorCall() {
        return false;
    }

    /// Há uma exceção assíncrona pendente pronta para entrar agora (habilitada, mais urgente que
    /// a ativa, não mascarada)? Consultado no MESMO ponto quente onde hoje se consulta
    /// {@link ArmCore#interruptLine()} ({@code ArmCore.servicePendingIrq}) — mesma técnica de
    /// {@link #interceptsBranch}: método default {@code false}, sem estado extra no perfil A.
    ///
    /// Perfil A: sempre {@code false} (usa {@code interruptLine()} diretamente, inalterado).
    /// Perfil M (B7.3+): {@code true} quando o SCS (NVIC/SysTick) tem uma exceção pendente cuja
    /// prioridade efetiva preempta a exceção atualmente ativa (ou o Thread mode).
    default boolean hasPendingException() {
        return false;
    }

    /// Entra na exceção pendente reconhecida por {@link #hasPendingException}.
    ///
    /// Perfil A: nunca chamado. Perfil M: escolhe a exceção pendente de maior prioridade,
    /// despende-a e entra (empilha frame, monta `EXC_RETURN`, busca o vetor — mesmo mecanismo de
    /// {@link MProfileExceptionModel#enterException(ArmCore, MProfileException)}).
    default void enterPendingException(ArmCore core) {
        throw new IllegalStateException("enterPendingException chamado sem uma exceção pendente reconhecida");
    }
}
