package dev.vitorsilverio.armjitter.memory;

/// Barramento de memoria implementado pelo dispositivo hospedeiro.
public interface AddressSpace {
    /// Le um byte sem sinal no endereco informado.
    int read8(int address);

    /// Le uma halfword de 16 bits no endereco informado.
    int read16(int address);

    /// Le uma word de 32 bits no endereco informado.
    int read32(int address);

    /// Escreve os 8 bits inferiores de `value` no endereco informado.
    void write8(int address, int value);

    /// Escreve os 16 bits inferiores de `value` no endereco informado.
    void write16(int address, int value);

    /// Escreve os 32 bits de `value` no endereco informado.
    void write32(int address, int value);

    /// Notifica que uma escrita ocorreu, permitindo invalidacao de codigo automodificado.
    default void notifyWrite(int address) {
    }
}
