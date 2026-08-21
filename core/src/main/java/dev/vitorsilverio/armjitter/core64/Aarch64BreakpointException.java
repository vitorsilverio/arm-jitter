package dev.vitorsilverio.armjitter.core64;

/// Sinaliza que o executor encontrou um `BRK` (`ir64/Ir64Op.Breakpoint`, B8.3) durante a execução
/// de um bloco — irmã de {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64}
/// (mesmo raciocínio: unchecked e sem stack trace, capturada no mesmo ponto de
/// `Ir64BlockExecutor#step`/`#executeBlock` que já rastreia o endereço da instrução fetched mais
/// recente, e convertida em uma entrada de exceção síncrona real via
/// {@link Aarch64Core#enterBreakpointException}).
public final class Aarch64BreakpointException extends RuntimeException {
    private final int immediate;

    /// @param immediate imediato de 16 bits do `BRK` (vira `ESR_EL1.ISS[15:0]`)
    public Aarch64BreakpointException(int immediate) {
        super("BRK #" + immediate, null, false, false);
        this.immediate = immediate;
    }

    /// Imediato de 16 bits do `BRK` que causou o trap.
    public int immediate() {
        return immediate;
    }
}
