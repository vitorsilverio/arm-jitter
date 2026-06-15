package dev.vitorsilverio.armjitter.memory;

/// Barramento de memória implementado pelo dispositivo hospedeiro.
public interface AddressSpace {
    /// Lê um byte sem sinal no endereço informado.
    int read8(int address);

    /// Lê uma halfword de 16 bits no endereço informado.
    int read16(int address);

    /// Lê uma word de 32 bits no endereço informado.
    int read32(int address);

    /// Escreve os 8 bits inferiores de `value` no endereço informado.
    void write8(int address, int value);

    /// Escreve os 16 bits inferiores de `value` no endereço informado.
    void write16(int address, int value);

    /// Escreve os 32 bits de `value` no endereço informado.
    void write32(int address, int value);

    /// Retorna ciclos extras para um acesso de memória.
    ///
    /// A implementação padrão retorna `0` para manter compatibilidade com testes e
    /// barramentos simples. Emuladores GBA podem sobrescrever este método para aplicar
    /// waitstates de BIOS, IWRAM, EWRAM, VRAM e ROM.
    ///
    /// @param address endereço acessado
    /// @param sizeBytes tamanho do acesso em bytes
    /// @param type tipo de acesso realizado
    /// @return ciclos extras consumidos pelo acesso
    default int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }

    /// Notifica que uma escrita ocorreu, permitindo invalidação de código automodificado.
    default void notifyWrite(int address) {
    }
}
