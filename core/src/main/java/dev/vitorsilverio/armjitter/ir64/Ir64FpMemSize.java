package dev.vitorsilverio.armjitter.ir64;

/// Tamanho de transferência de uma instrução de load/store **SIMD&FP** escalar A64 (`ARM DDI 0487
/// C4.1.5`, campo `size`+`opc` combinado) — irmão de {@link Ir64MemSize} (registrador geral), mas
/// com um quinto tamanho (`QUAD`, 128 bits) que não existe para `X`/`W` (B8.13). Usado por
/// {@link Ir64Op.FpLoad64}/{@link Ir64Op.FpStore64}/{@link Ir64Op.FpLoadStorePair}/
/// {@link Ir64Op.FpLoadLiteral64}.
public enum Ir64FpMemSize {
    /// 1 byte (`B<t>`).
    BYTE(1, 0),
    /// 2 bytes (`H<t>`).
    HALF(2, 1),
    /// 4 bytes (`S<t>`).
    SINGLE(4, 2),
    /// 8 bytes (`D<t>`).
    DOUBLE(8, 3),
    /// 16 bytes (`Q<t>`) — só existe em `LDR`/`STR` escalar SIMD&FP, nunca em registrador geral.
    QUAD(16, 4);

    private final int bytes;
    private final int sizeLog2;

    Ir64FpMemSize(int bytes, int sizeLog2) {
        this.bytes = bytes;
        this.sizeLog2 = sizeLog2;
    }

    /// Quantidade de bytes transferidos por esta forma.
    public int bytes() {
        return bytes;
    }

    /// `log2(bytes())` — usado como fator de escala do imediato (forma "unsigned offset") e como
    /// índice de tamanho de {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}.
    public int sizeLog2() {
        return sizeLog2;
    }
}
