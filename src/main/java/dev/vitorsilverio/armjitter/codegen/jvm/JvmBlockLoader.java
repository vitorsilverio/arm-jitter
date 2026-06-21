package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.jit.CompiledBlock;

/// Carrega bytecode JVM e expõe um {@link CompiledBlock} invocável.
public final class JvmBlockLoader {
    private final JvmBlockClassLoader classLoader;

    /// Cria um loader com o class loader pai informado.
    public JvmBlockLoader(ClassLoader parent) {
        this.classLoader = new JvmBlockClassLoader(parent);
    }

    /// Cria um loader usando o class loader desta classe como pai.
    public JvmBlockLoader() {
        this(JvmBlockLoader.class.getClassLoader());
    }

    /// Define a classe (que implementa {@link CompiledBlock}) e a instancia, devolvendo um bloco
    /// executável por chamada virtual direta — sem o overhead de `MethodHandle.invokeExact`.
    public CompiledBlock load(byte[] bytecode, String internalName) {
        try {
            Class<?> blockClass = classLoader.define(internalName, bytecode);
            return (CompiledBlock) blockClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to load JVM compiled block", failure);
        }
    }
}
