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

    /// Repassa a busca de instrução THUMB ao barramento delegado.
    ///
    /// **Achado real (F3/`virtual-arm-box`, sessão de trace instrução-a-instrução)**: antes desta
    /// correção, este decorador não sobrescrevia {@link #fetch16}/{@link #fetch32} — caíam no
    /// padrão de {@link AddressSpace} (`fetch32(addr) -> read32(addr)`), que delega à SUA PRÓPRIA
    /// {@link #read32}, ou seja, ao caminho de DADOS do delegado, não ao de busca de instrução. Sob
    /// um delegado sem MMU isso é inofensivo (`read32`/`fetch32` já são idênticos ali), mas ao
    /// envolver uma {@link dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace} (como
    /// `Bcm2835Machine` passou a fazer para resolver invalidação de SMC) a busca de instrução
    /// silenciosamente parava de usar a TLB de INSTRUÇÃO e o tipo de acesso
    /// `MemoryAccessType.INSTRUCTION_FETCH` — uma falha de tradução na busca virava
    /// `MemoryAccessType.DATA_READ`, e `ArmCore#enterMemoryAbort` decide `PREFETCH_ABORT` vs.
    /// `DATA_ABORT` A PARTIR desse tipo (ver Javadoc de {@link #fetch32}) — todo abort de busca sob
    /// este decorador virava incorretamente `DATA_ABORT` (vetor, FAR/FSR e correção de PC errados
    /// para uma falta que era, na verdade, de instrução).
    ///
    /// @param address endereço virtual da instrução THUMB
    @Override
    public int fetch16(int address) {
        return delegate.fetch16(address);
    }

    /// Repassa a busca de instrução ARM ao barramento delegado. Ver Javadoc de {@link #fetch16}
    /// para o bug real que esta sobrescrita corrige.
    ///
    /// @param address endereço virtual da instrução ARM
    @Override
    public int fetch32(int address) {
        return delegate.fetch32(address);
    }

    /// Repassa a geração de tradução MMU ao barramento delegado — sem esta sobrescrita, o padrão
    /// de {@link AddressSpace#translationGeneration()} (constante `0`) faria o `JitRuntime`
    /// nunca invalidar blocos compilados sob uma tabela de páginas antiga após uma troca de
    /// `TTBR0`/`CONTEXTIDR` (RFC-SOFTMMU §5, B4.1.4) — mesma família de lacuna do
    /// {@link #fetch16}/{@link #fetch32} acima: um método com efeito colateral MMU-específico que
    /// este decorador esquecia de encaminhar.
    @Override
    public int translationGeneration() {
        return delegate.translationGeneration();
    }
}
