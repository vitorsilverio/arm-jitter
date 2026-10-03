package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrOp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// Registro `record` do IR → {@link AsmEmission}: a única fonte de "esta op tem emissão nativa, em
/// que condição, com quais acessos ao register cache". O {@link AsmBlockCompiler} emite por ele e a
/// {@link AsmNativePolicy} é derivada dele — os dois não podem divergir.
///
/// Record sem entrada = interpretado (bloco inteiro em `WHOLE_BLOCK`, a op em `PER_OP`). Cada família
/// declara as suas entradas no próprio `register`.
final class AsmEmitterRegistry {
    private static final Map<Class<?>, AsmEmission<?>> EMISSIONS = build(List.of(
            AsmAluEmitter::register,
            AsmIntegerEmitter::register,
            AsmMemoryEmitter::register,
            AsmControlEmitter::register,
            AsmVfpEmitter::register));

    private AsmEmitterRegistry() {
    }

    /// A entrada do record de `op`, ou `null` se ele não tem emissão nativa.
    static AsmEmission<?> lookup(IrOp op) {
        return EMISSIONS.get(op.getClass());
    }

    /// `true` se `op` tem entrada e o predicado da entrada a aceita.
    static boolean supports(IrOp op) {
        AsmEmission<?> emission = lookup(op);
        return emission != null && emission.accepts(op);
    }

    /// Monta o mapa a partir do `register` de cada família; record registrado duas vezes é erro.
    static Map<Class<?>, AsmEmission<?>> build(List<Consumer<Builder>> families) {
        Builder builder = new Builder();
        families.forEach(family -> family.accept(builder));
        return Map.copyOf(builder.emissions);
    }

    /// Acumula as entradas de {@link #build}.
    static final class Builder {
        private final Map<Class<?>, AsmEmission<?>> emissions = new HashMap<>();

        void add(AsmEmission<?> emission) {
            if (emissions.putIfAbsent(emission.type(), emission) != null) {
                throw new IllegalStateException("record registrado duas vezes: " + emission.type().getName());
            }
        }
    }
}
