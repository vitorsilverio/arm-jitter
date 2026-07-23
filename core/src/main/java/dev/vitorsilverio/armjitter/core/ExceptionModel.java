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
}
