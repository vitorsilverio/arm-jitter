package dev.vitorsilverio.armjitter.decoder;

/// Modo de enderecamento ARM para `LDM`/`STM`.
public enum BlockTransferMode {
    /// Increment after.
    IA,
    /// Increment before.
    IB,
    /// Decrement after.
    DA,
    /// Decrement before.
    DB;

    /// Calcula o primeiro endereco acessado para a quantidade de registradores.
    public int startAddress(int base, int registerCount) {
        return switch (this) {
            case IA -> base;
            case IB -> base + 4;
            case DA -> base - (registerCount - 1) * 4;
            case DB -> base - registerCount * 4;
        };
    }

    /// Calcula o valor gravado no registrador base quando writeback esta ativo.
    public int writebackAddress(int base, int registerCount) {
        return switch (this) {
            case IA, IB -> base + registerCount * 4;
            case DA, DB -> base - registerCount * 4;
        };
    }

    /// Converte bits P/U ARM para o modo de transferencia em bloco.
    public static BlockTransferMode fromArmBits(boolean preIndexed, boolean addOffset) {
        if (addOffset) {
            return preIndexed ? IB : IA;
        }
        return preIndexed ? DB : DA;
    }
}
