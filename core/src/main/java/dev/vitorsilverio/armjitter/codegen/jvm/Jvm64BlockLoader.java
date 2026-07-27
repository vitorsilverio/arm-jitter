package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;

/// Carrega bytecode JVM de blocos A64 e expõe um {@link CompiledBlock64} invocável — sibling de
/// {@link JvmBlockLoader}, introduzido na task B6.4 (PR1).
///
/// **Mora neste pacote (`codegen/jvm/`), não em `codegen64/jvm64/`** — única exceção deliberada
/// à convenção de pacote `*64` desta task (ver `b6.4-aarch64-asm-backend.md`, Especificação #6):
/// reaproveita {@link JvmBlockClassLoader}, que é `package-private` e não tem NADA de específico
/// de ARM-32 (é só um `ClassLoader` de bytecode gerado em runtime) — duplicá-lo num pacote irmão
/// só para preservar a convenção de nomes seria puro boilerplate.
public final class Jvm64BlockLoader {
    private final JvmBlockClassLoader classLoader;

    /// Cria um loader com o class loader pai informado.
    public Jvm64BlockLoader(ClassLoader parent) {
        this.classLoader = new JvmBlockClassLoader(parent);
    }

    /// Cria um loader usando o class loader desta classe como pai.
    public Jvm64BlockLoader() {
        this(Jvm64BlockLoader.class.getClassLoader());
    }

    /// Define a classe (que implementa {@link CompiledBlock64}) e a instancia, devolvendo um
    /// bloco executável por chamada virtual direta.
    public CompiledBlock64 load(byte[] bytecode, String internalName) {
        try {
            Class<?> blockClass = classLoader.define(internalName, bytecode);
            return (CompiledBlock64) blockClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to load JVM compiled A64 block", failure);
        }
    }
}
