package dev.vitorsilverio.armjitter.core64;

/// Sinaliza que o executor encontrou um `HLT` (`ir64/Ir64Op.UndefinedInstructionTrap`, B8.3)
/// durante a execução de um bloco — sem estado de debug externo modelado, o pseudocódigo real do
/// manual para `HLT` cai no caminho `UNDEFINED`. Irmã de {@link Aarch64BreakpointException} (mesmo
/// raciocínio de captura no `Ir64BlockExecutor`, convertida em exceção síncrona real via
/// {@link Aarch64Core#enterUndefinedInstructionException}).
public final class Aarch64UndefinedInstructionException extends RuntimeException {
    public Aarch64UndefinedInstructionException() {
        super("HLT (UNDEFINED, sem debug state)", null, false, false);
    }
}
