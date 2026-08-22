package dev.vitorsilverio.armjitter.core64;

/// Sinaliza que o executor encontrou um `SMC` (`ir64/Ir64Op.PrivilegedCall`, B10.5) durante a
/// execução de um bloco — irmã de {@link Aarch64HypervisorCallException}/
/// {@link Aarch64BreakpointException}/{@link Aarch64UndefinedInstructionException} (mesmo
/// raciocínio de captura no `Ir64BlockExecutor#step`/`#executeBlock`, convertida em entrada de
/// exceção síncrona real via {@link Aarch64Core#enterSecureMonitorCall}).
public final class Aarch64SecureMonitorCallException extends RuntimeException {
    public Aarch64SecureMonitorCallException() {
        super("SMC", null, false, false);
    }
}
