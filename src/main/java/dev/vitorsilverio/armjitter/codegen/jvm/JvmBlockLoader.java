package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

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

    /// Define a classe e retorna um bloco que invoca {@code static int execute(ArmCore)}.
    public CompiledBlock load(byte[] bytecode, String internalName) {
        try {
            Class<?> blockClass = classLoader.define(internalName, bytecode);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    blockClass,
                    MethodHandles.lookup());
            MethodType type = MethodType.methodType(int.class, ArmCore.class);
            MethodHandle execute = lookup.findStatic(blockClass, "execute", type);
            return core -> {
                try {
                    return (int) execute.invokeExact(core);
                } catch (Throwable failure) {
                    throw new IllegalStateException("Failed to invoke JVM compiled block", failure);
                }
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to load JVM compiled block", failure);
        }
    }
}
