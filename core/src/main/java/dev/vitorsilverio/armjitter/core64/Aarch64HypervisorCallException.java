package dev.vitorsilverio.armjitter.core64;

/// Sinaliza que o executor encontrou um `HVC` (`ir64/Ir64Op.PrivilegedCall`, B10.4) durante a
/// execução de um bloco — irmã de {@link Aarch64BreakpointException}/
/// {@link Aarch64UndefinedInstructionException} (mesmo raciocínio de captura no
/// `Ir64BlockExecutor#step`/`#executeBlock`, convertida em entrada de exceção síncrona real via
/// {@link Aarch64Core#enterHypervisorCall}).
public final class Aarch64HypervisorCallException extends RuntimeException {
    public Aarch64HypervisorCallException() {
        super("HVC", null, false, false);
    }
}
