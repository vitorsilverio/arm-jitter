package dev.vitorsilverio.armjitter.codegen.jvm;

/// Referência a um método ou campo JVM usado pelo codegen ASM.
///
/// {@code ownerInternalName} usa o formato ASM (ex.: {@code dev/foo/Bar}).
public record HostMethodBinding(String ownerInternalName, String name, String descriptor) {
    /// Nome binário Java (ex.: {@code dev.foo.Bar}).
    public String ownerClassName() {
        return ownerInternalName.replace('/', '.');
    }
}
