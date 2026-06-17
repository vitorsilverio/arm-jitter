package dev.vitorsilverio.armjitter.codegen.equivalence;

/// Indica divergência entre o emissor de referência e o candidato no harness de equivalência.
public final class EquivalenceMismatchException extends AssertionError {
    private EquivalenceMismatchException(String message) {
        super(message);
    }

    /// Cria uma exceção com campo e valores formatados.
    public static EquivalenceMismatchException of(String label, String field, String reference, String candidate) {
        return new EquivalenceMismatchException(
                "%s: %s diverged (reference=%s, candidate=%s)".formatted(label, field, reference, candidate));
    }
}
