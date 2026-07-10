package dev.vitorsilverio.armjitter.codegen.jvm;

/// ClassLoader dedicado a blocos JVM gerados em tempo de execução.
final class JvmBlockClassLoader extends ClassLoader {
    JvmBlockClassLoader(ClassLoader parent) {
        super(parent);
    }

    Class<?> define(String internalName, byte[] bytecode) {
        String binaryName = internalName.replace('/', '.');
        return defineClass(binaryName, bytecode, 0, bytecode.length);
    }
}
