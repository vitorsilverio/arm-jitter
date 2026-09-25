package dev.vitorsilverio.armjitter.decoder;

/// Empacotamento do campo `immediate` de {@link InstructionKind#VFP_CORE_TRANSFER} para as formas
/// **NEON de 8/16 bits** de `VMOV_to_gp`/`VMOV_from_gp` (`VMOV.{S8,U8,S16,U16,8,16}`, ARM DDI 0406C
/// A8.8.343/A8.8.344, B22.10): elas transferem UM elemento de um registrador `D` (`0`-`31`) em vez de
/// um `S` inteiro. `immediate == 1` continua sendo `VMOV_half` (B22.2) e `0` as formas de 32 bits;
/// qualquer valor com {@link #LANE_FLAG} ligado é uma transferência de lane.
public final class VfpLaneTransferEncoding {
    /// Marca "transferência de lane NEON" (distingue de `VMOV_half`, que usa `immediate == 1`).
    public static final int LANE_FLAG = 1 << 8;
    /// Extensão de sinal na leitura (`VMOV.S8`/`VMOV.S16`); ausente = zero-extend (`U8`/`U16`).
    public static final int SIGNED_FLAG = 1 << 7;
    private static final int SIZE_LOG2_SHIFT = 4;
    private static final int SIZE_LOG2_MASK = 0b11;
    private static final int LANE_MASK = 0b1111;

    private VfpLaneTransferEncoding() {
    }

    /// Empacota `sizeLog2` (`0` = byte, `1` = halfword), o índice `lane` e o sinal.
    public static int encode(int sizeLog2, int lane, boolean signExtend) {
        return LANE_FLAG | (signExtend ? SIGNED_FLAG : 0) | (sizeLog2 << SIZE_LOG2_SHIFT) | lane;
    }

    /// `true` se `immediate` descreve uma transferência de lane.
    public static boolean isLane(int immediate) {
        return (immediate & LANE_FLAG) != 0;
    }

    /// `log2` do tamanho do elemento em bytes (`0` byte, `1` halfword).
    public static int sizeLog2(int immediate) {
        return (immediate >>> SIZE_LOG2_SHIFT) & SIZE_LOG2_MASK;
    }

    /// Índice do elemento dentro do registrador `D`.
    public static int lane(int immediate) {
        return immediate & LANE_MASK;
    }

    /// `true` para leitura com extensão de sinal.
    public static boolean signExtend(int immediate) {
        return (immediate & SIGNED_FLAG) != 0;
    }
}
