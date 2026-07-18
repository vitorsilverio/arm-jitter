package dev.vitorsilverio.armjitter.memory;

import dev.vitorsilverio.armjitter.jit.JitRuntime;

import java.util.Objects;

/// `AddressSpace` decorador que invalida DOIS runtimes JIT após escritas — para hospedeiros
/// multi-CPU cuja RAM é compartilhada (ex. NDS: ARM9 e ARM7 buscam código da mesma Main RAM,
/// cada um com seu próprio block cache; a escrita de um lado precisa derrubar blocos do outro).
///
/// Existe como classe própria (e não como dois [InvalidationAwareAddressSpace] aninhados) por
/// desempenho: aninhar decoradores torna o call-site do `delegate` megamórfico (ele vê o outro
/// decorador E o barramento real) e adiciona um salto virtual a CADA leitura — medido ~-7% em
/// jogo comercial. Aqui as leituras fazem um único salto e o delegate fica monomórfico.
public final class DualInvalidationAwareAddressSpace implements AddressSpace {
    private final AddressSpace delegate;
    private final JitRuntime first;
    private final JitRuntime second;

    /// Cria um barramento que delega acessos e notifica AMBOS os runtimes em escritas.
    public DualInvalidationAwareAddressSpace(AddressSpace delegate, JitRuntime first, JitRuntime second) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.first = Objects.requireNonNull(first, "first");
        this.second = Objects.requireNonNull(second, "second");
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

    /// Escreve um byte e invalida o endereço nos dois runtimes.
    @Override
    public void write8(int address, int value) {
        delegate.write8(address, value);
        first.invalidate(address, Byte.BYTES);
        second.invalidate(address, Byte.BYTES);
    }

    /// Escreve uma halfword e invalida o intervalo nos dois runtimes.
    @Override
    public void write16(int address, int value) {
        delegate.write16(address, value);
        first.invalidate(address, Short.BYTES);
        second.invalidate(address, Short.BYTES);
    }

    /// Escreve uma word e invalida o intervalo nos dois runtimes.
    @Override
    public void write32(int address, int value) {
        delegate.write32(address, value);
        first.invalidate(address, Integer.BYTES);
        second.invalidate(address, Integer.BYTES);
    }

    /// Repassa o cálculo de waitstates ao barramento delegado.
    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return delegate.accessCycles(address, sizeBytes, type);
    }

    /// Encaminha a capacidade de waitstates do barramento delegado.
    @Override
    public boolean providesAccessCycles() {
        return delegate.providesAccessCycles();
    }

    /// Repassa a notificação manual ao barramento delegado e invalida os dois runtimes.
    @Override
    public void notifyWrite(int address) {
        delegate.notifyWrite(address);
        first.invalidate(address);
        second.invalidate(address);
    }
}
