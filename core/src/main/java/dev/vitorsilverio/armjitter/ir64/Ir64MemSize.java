package dev.vitorsilverio.armjitter.ir64;

/// Tamanho de transferência de uma instrução de load/store de registrador geral A64
/// (`ARM DDI 0487 C4.1.3`, campo `size`/`opc`). Usado por {@link Ir64Op.Load64}/
/// {@link Ir64Op.Store64}/{@link Ir64Op.LoadStorePair} para saber quantos bytes ler/escrever da
/// memória — a largura do REGISTRADOR (`W`/`X`) é um campo separado (`wide`), já que as duas coisas
/// divergem nas formas com sinal (`LDRSB`/`LDRSH`/`LDRSW`: tamanho de memória menor que o registro).
public enum Ir64MemSize {
    /// 1 byte (`B`).
    BYTE(1),
    /// 2 bytes (`H`).
    HALF(2),
    /// 4 bytes (`W`, palavra).
    WORD(4),
    /// 8 bytes (`X`, doubleword).
    DOUBLEWORD(8);

    private final int bytes;

    Ir64MemSize(int bytes) {
        this.bytes = bytes;
    }

    /// Quantidade de bytes transferidos por esta forma.
    public int bytes() {
        return bytes;
    }

    /// `log2(bytes())` — usado como fator de escala do imediato (forma "unsigned offset") e do
    /// deslocamento de registrador (`LSL`/extend com quantidade implícita).
    public int log2Bytes() {
        return Integer.numberOfTrailingZeros(bytes);
    }
}
