package dev.vitorsilverio.armjitter.advsimd;

/// Núcleo compartilhado do algoritmo `CRC32`/`CRC32C` (B14.3), extraído de
/// `Ir64BlockExecutor#executeCrc32` (B19.17) para o lado A64 delegar aqui em vez de manter uma
/// segunda cópia do laço — mesma disciplina "D1" da RFC `b13.2-rfc-nucleo-vetorial.md` (extrair
/// para um núcleo neutro e fazer as duas larguras delegarem), aplicada ao pacote `advsimd` por já
/// ser o único hoje importado tanto pelo lado de 32 bits (`codegen.executor.IrNeonExecutor`/
/// `IrSystemExecutor`) quanto pelo de 64 (`executor64.Ir64BlockExecutor`).
///
/// A instrução em si **não complementa entrada nem saída** (ao contrário do CRC-32 "de aplicação"
/// de `zlib`/Ethernet) — quem chama fornece o acumulador já invertido quando quiser reproduzir o
/// CRC-32 clássico (tipicamente `~0`).
public final class Crc32Checksum {
    private static final int POLY_IEEE_802_3_REFLECTED = 0xEDB88320;
    private static final int POLY_CASTAGNOLI_REFLECTED = 0x82F63B78;

    private Crc32Checksum() {
    }

    /// Consome {@code data} byte a byte (do menos para o mais significativo, `dataWidthBits / 8`
    /// bytes) sobre o acumulador `crc`, com o polinômio refletido escolhido por
    /// {@code castagnoli}.
    public static int compute(int crc, long data, int dataWidthBits, boolean castagnoli) {
        int poly = castagnoli ? POLY_CASTAGNOLI_REFLECTED : POLY_IEEE_802_3_REFLECTED;
        int byteCount = dataWidthBits / Byte.SIZE;
        for (int i = 0; i < byteCount; i++) {
            int dataByte = (int) (data >>> (i * Byte.SIZE)) & 0xFF;
            crc ^= dataByte;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                boolean lsbSet = (crc & 1) != 0;
                crc >>>= 1;
                if (lsbSet) {
                    crc ^= poly;
                }
            }
        }
        return crc;
    }
}
