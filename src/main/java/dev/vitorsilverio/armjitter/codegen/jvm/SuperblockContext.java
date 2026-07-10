package dev.vitorsilverio.armjitter.codegen.jvm;

/// Estado do runtime que o loop-superbloco consulta POR ITERAÇÃO (task C0.3).
///
/// Injetado no campo público `context` da classe gerada pelo [AsmSuperblockCompiler]
/// logo após a instanciação. Os dois valores espelham os guards do chain loop de
/// `JitRuntime.execute` (invariante S1 da spec `tasks/trilha-c-perf/c0-impl-loop-superbloco.md`).
public interface SuperblockContext {
    /// Geração atual do cache de blocos — mudou desde a entrada ⇒ o superbloco sai
    /// (código pode ter sido invalidado por SMC durante a própria corrente).
    long generation();

    /// Orçamento de ciclos do encadeamento (lido na entrada de cada `execute`).
    int chainCycleBudget();
}
