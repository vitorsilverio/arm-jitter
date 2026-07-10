package dev.vitorsilverio.armjitter.jit;

/// Marcador dos loop-superblocos (task C0.3). O chain loop de [JitRuntime] NÃO encadeia
/// PARA DENTRO de um bloco marcado: um superbloco consome o orçamento de ciclos inteiro
/// internamente, e entrar nele no meio de uma corrente quase dobraria a latência de
/// interleave (violação do invariante S1). Superblocos só são executados como o PRIMEIRO
/// bloco de um `execute` (hit de inline cache no topo).
public interface LoopSuperblock {
}
