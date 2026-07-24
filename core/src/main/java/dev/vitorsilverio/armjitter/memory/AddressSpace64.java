package dev.vitorsilverio.armjitter.memory;

import java.util.Objects;

/// Barramento de memória de 64 bits, implementado pelo dispositivo hospedeiro AArch64.
///
/// Mesma forma de {@link AddressSpace} (RFC-IR-64BIT.md §3.2, Opção B), mas com endereços `long`
/// — NÃO é uma sobrecarga de {@link AddressSpace}: introduzir métodos `long` na interface de 32
/// bits obrigaria todo hospedeiro ARMv4T/v5TE/v6/v7 existente (gbaemu, ndsemu, armbox) a carregar
/// métodos mortos e abriria uma porta para invocar a variante errada por engano. Os dois mundos
/// são independentes por desenho (G3).
public interface AddressSpace64 {
    /// Lê um byte sem sinal no endereço informado.
    int read8(long address);

    /// Lê uma halfword de 16 bits no endereço informado.
    int read16(long address);

    /// Lê uma word de 32 bits no endereço informado.
    int read32(long address);

    /// Escreve os 8 bits inferiores de `value` no endereço informado.
    void write8(long address, int value);

    /// Escreve os 16 bits inferiores de `value` no endereço informado.
    void write16(long address, int value);

    /// Escreve os 32 bits de `value` no endereço informado.
    void write32(long address, int value);

    /// Lê uma doubleword de 64 bits no endereço informado. Padrão: composição little-endian de
    /// duas {@link #read32} (mesma técnica usada pelo par de words do transfer de dupla precisão
    /// VFP no mundo de 32 bits) — nenhum implementador de {@link AddressSpace64} precisa
    /// sobrescrever isto, mas pode fazê-lo por performance.
    default long read64(long address) {
        long low = Integer.toUnsignedLong(read32(address));
        long high = Integer.toUnsignedLong(read32(address + Integer.BYTES));
        return low | (high << Integer.SIZE);
    }

    /// Escreve uma doubleword de 64 bits no endereço informado. Padrão: composição little-endian
    /// de duas {@link #write32} — ver {@link #read64}.
    default void write64(long address, long value) {
        write32(address, (int) value);
        write32(address + Integer.BYTES, (int) (value >>> Integer.SIZE));
    }

    /// Retorna ciclos extras para um acesso de memória. Padrão `0` (mesma convenção conservadora
    /// de {@link AddressSpace#accessCycles}).
    default int accessCycles(long address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }

    /// Indica se {@link #accessCycles} pode retornar valores diferentes de zero (mesma técnica de
    /// {@link AddressSpace#providesAccessCycles} para pular a chamada virtual no caminho quente).
    default boolean providesAccessCycles() {
        return true;
    }

    /// Notifica que uma escrita ocorreu, permitindo invalidação de código automodificado.
    default void notifyWrite(long address) {
    }

    /// Envolve um {@link AddressSpace} de 32 bits existente, truncando endereços `long` para
    /// `int` — reuso de RAMs de teste simples (`TestAddressSpace` e afins) sem duplicar
    /// implementação. Endereços fora da faixa `int` (fora dos 4 GiB baixos) NÃO são suportados
    /// por este adapter: destinado só a testes/corpora que operam perto do endereço `0`, não a um
    /// hospedeiro real de 64 bits com espaço de endereço maior que 4 GiB.
    ///
    /// @param inner barramento de 32 bits a envolver
    /// @return adapter que delega para `inner`
    static AddressSpace64 wrapping(AddressSpace inner) {
        Objects.requireNonNull(inner, "inner");
        return new Wrapping(inner);
    }

    /// Implementação do adapter de {@link #wrapping}.
    final class Wrapping implements AddressSpace64 {
        private final AddressSpace inner;

        private Wrapping(AddressSpace inner) {
            this.inner = inner;
        }

        @Override
        public int read8(long address) {
            return inner.read8(Math.toIntExact(address));
        }

        @Override
        public int read16(long address) {
            return inner.read16(Math.toIntExact(address));
        }

        @Override
        public int read32(long address) {
            return inner.read32(Math.toIntExact(address));
        }

        @Override
        public void write8(long address, int value) {
            inner.write8(Math.toIntExact(address), value);
        }

        @Override
        public void write16(long address, int value) {
            inner.write16(Math.toIntExact(address), value);
        }

        @Override
        public void write32(long address, int value) {
            inner.write32(Math.toIntExact(address), value);
        }

        @Override
        public int accessCycles(long address, int sizeBytes, MemoryAccessType type) {
            return inner.accessCycles(Math.toIntExact(address), sizeBytes, type);
        }

        @Override
        public boolean providesAccessCycles() {
            return inner.providesAccessCycles();
        }

        @Override
        public void notifyWrite(long address) {
            inner.notifyWrite(Math.toIntExact(address));
        }
    }
}
