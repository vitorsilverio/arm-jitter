package dev.vitorsilverio.armjitter.codegen.jvm;

/// Contrato de emissão de acessos de memória guest em bytecode JVM.
///
/// O interpretador soma waitstates via {@link #addMemoryCycles()} após cada acesso; o ASM
/// deve seguir o mesmo padrão para manter {@code core.cycles()} equivalente.
public interface MemAccessEmitter {
    /// Barramento guest ({@link dev.vitorsilverio.armjitter.memory.AddressSpace}).
    HostMethodBinding memory();

    HostMethodBinding read8();

    HostMethodBinding read16();

    HostMethodBinding read32();

    HostMethodBinding write8();

    HostMethodBinding write16();

    HostMethodBinding write32();

    /// Waitstates extras do barramento + acumulação em {@link dev.vitorsilverio.armjitter.core.ArmCore}.
    HostMethodBinding accessCycles();

    HostMethodBinding addMemoryCycles();
}
