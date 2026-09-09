package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "two-register miscellaneous" de conversão de PRECISÃO de ponto flutuante, forma
/// FIXA de 4 elementos (B13.13) — `VCVT_F16_F32`/`VCVT_B16_F32` (estreita: 4 lanes F32 → 4 lanes
/// F16/`bf16`) e `VCVT_F32_F16` (alarga: 4 lanes F16 → 4 lanes F32) do NEON de 32 bits. Encoding
/// `@2misc_q0`: SEMPRE `Q=0` no sentido do template — não existe forma "2"/metade-alta como
/// {@code FCVTN}/{@code FCVTL} do A64 (B19.4), por isso não há campo `esz`/`laneOffset` aqui, só a
/// direção/formato.
public enum AdvSimdFpConvertPrecisionOp {
    /// `VCVT_F16_F32` — estreita, `Float.floatToFloat16` (núcleo já criado pela B19.4).
    NARROW_F16,
    /// `VCVT_B16_F32` — estreita, `AdvSimdLanes#bf16Bits` (núcleo já criado pela B19.7,
    /// `ArmFeature#BFLOAT16`).
    NARROW_BF16,
    /// `VCVT_F32_F16` — alarga, `Float.float16ToFloat`.
    WIDEN_F16
}
