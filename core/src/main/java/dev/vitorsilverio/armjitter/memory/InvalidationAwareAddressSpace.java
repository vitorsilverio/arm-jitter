package dev.vitorsilverio.armjitter.memory;

import dev.vitorsilverio.armjitter.jit.JitRuntime;

import java.util.Objects;

/// `AddressSpace` decorador que invalida o JIT após escritas.
public final class InvalidationAwareAddressSpace implements AddressSpace {
    private final AddressSpace delegate;
    private final JitRuntime runtime;

    /// Cria um barramento que delega acessos e notifica o runtime em escritas.
    public InvalidationAwareAddressSpace(AddressSpace delegate, JitRuntime runtime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /// Lê um byte pelo barramento delegado.
    @Override
    public int read8(int address) {
        return delegate.read8(address);
    }

    /// Lê uma halfword pelo barramento delegado.
    @Override
    public int read16(int address) {
        return delegate.read16(address);
    }

    /// Lê uma word pelo barramento delegado.
    @Override
    public int read32(int address) {
        return delegate.read32(address);
    }

    /// Escreve um byte e invalida o endereço escrito.
    @Override
    public void write8(int address, int value) {
        delegate.write8(address, value);
        runtime.invalidate(address, Byte.BYTES);
    }

    /// Escreve uma halfword e invalida o intervalo escrito.
    @Override
    public void write16(int address, int value) {
        delegate.write16(address, value);
        runtime.invalidate(address, Short.BYTES);
    }

    /// Escreve uma word e invalida o intervalo escrito.
    @Override
    public void write32(int address, int value) {
        delegate.write32(address, value);
        runtime.invalidate(address, Integer.BYTES);
    }

    /// Repassa o cálculo de waitstates ao barramento delegado.
    ///
    /// @param address endereço acessado
    /// @param sizeBytes tamanho do acesso em bytes
    /// @param type tipo de acesso realizado
    /// @return ciclos extras consumidos pelo acesso delegado
    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return delegate.accessCycles(address, sizeBytes, type);
    }

    /// Encaminha a capacidade de waitstates do barramento delegado, para que o core possa
    /// pular a contabilização por acesso quando o delegado não tem waitstates.
    @Override
    public boolean providesAccessCycles() {
        return delegate.providesAccessCycles();
    }

    /// Repassa a notificação manual ao barramento delegado e invalida o runtime.
    @Override
    public void notifyWrite(int address) {
        delegate.notifyWrite(address);
        runtime.invalidate(address);
    }
}
