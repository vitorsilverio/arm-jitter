package dev.vitorsilverio.armjitter.memory;

import dev.vitorsilverio.armjitter.jit.JitRuntime;

import java.util.Objects;

/// `AddressSpace` decorador que invalida o JIT apos escritas.
public final class InvalidationAwareAddressSpace implements AddressSpace {
    private final AddressSpace delegate;
    private final JitRuntime runtime;

    /// Cria um barramento que delega acessos e notifica o runtime em escritas.
    public InvalidationAwareAddressSpace(AddressSpace delegate, JitRuntime runtime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /// Le um byte pelo barramento delegado.
    @Override
    public int read8(int address) {
        return delegate.read8(address);
    }

    /// Le uma halfword pelo barramento delegado.
    @Override
    public int read16(int address) {
        return delegate.read16(address);
    }

    /// Le uma word pelo barramento delegado.
    @Override
    public int read32(int address) {
        return delegate.read32(address);
    }

    /// Escreve um byte e invalida o endereco escrito.
    @Override
    public void write8(int address, int value) {
        delegate.write8(address, value);
        runtime.invalidate(address);
    }

    /// Escreve uma halfword e invalida o endereco escrito.
    @Override
    public void write16(int address, int value) {
        delegate.write16(address, value);
        runtime.invalidate(address);
    }

    /// Escreve uma word e invalida o endereco escrito.
    @Override
    public void write32(int address, int value) {
        delegate.write32(address, value);
        runtime.invalidate(address);
    }

    /// Repassa a notificacao manual ao barramento delegado e invalida o runtime.
    @Override
    public void notifyWrite(int address) {
        delegate.notifyWrite(address);
        runtime.invalidate(address);
    }
}
