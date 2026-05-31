package dev.vitorsilverio.armjitter.jit;

/// Politica simples para decidir quando um PC deve migrar de interpretado para JIT.
public record ExecutionThreshold(
        /// Numero de hits antes da compilacao.
        int hotCount) {

    /// Cria uma politica validando que o limite nao seja negativo.
    public ExecutionThreshold {
        if (hotCount < 0) {
            throw new IllegalArgumentException("hotCount must be >= 0");
        }
    }

    /// Retorna `true` quando `hits` ja alcancou o limite configurado.
    public boolean isHot(int hits) {
        return hits >= hotCount;
    }
}
