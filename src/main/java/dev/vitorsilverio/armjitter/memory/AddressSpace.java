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

    /// Retorna ciclos extras para um acesso de memoria.
    ///
    /// A implementacao padrao retorna `0` para manter compatibilidade com testes e
    /// barramentos simples. Emuladores GBA podem sobrescrever este metodo para aplicar
    /// waitstates de BIOS, IWRAM, EWRAM, VRAM e ROM.
    ///
    /// @param address endereco acessado
    /// @param sizeBytes tamanho do acesso em bytes
    /// @param type tipo de acesso realizado
    /// @return ciclos extras consumidos pelo acesso
    default int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }

    /// Notifica que uma escrita ocorreu, permitindo invalidacao de codigo automodificado.
    default void notifyWrite(int address) {
    }
}
