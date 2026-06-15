package dev.vitorsilverio.armjitter.memory;

/// Tipo de acesso de memória usado para cálculo de waitstates.
public enum MemoryAccessType {
    /// Busca de instrução ARM ou THUMB.
    INSTRUCTION_FETCH,
    /// Leitura de dados.
    DATA_READ,
    /// Escrita de dados.
    DATA_WRITE
}
