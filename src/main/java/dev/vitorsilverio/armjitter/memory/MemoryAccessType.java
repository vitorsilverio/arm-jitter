package dev.vitorsilverio.armjitter.memory;

/// Tipo de acesso de memoria usado para calculo de waitstates.
public enum MemoryAccessType {
    /// Busca de instrucao ARM ou THUMB.
    INSTRUCTION_FETCH,
    /// Leitura de dados.
    DATA_READ,
    /// Escrita de dados.
    DATA_WRITE
}
