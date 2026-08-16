package dev.vitorsilverio.armjitter.core;

/// Modo de arredondamento do FPSCR (campo `RMODE`\[1:0\], ARM DDI 0406C A2.7.2). Os 4 valores
/// são os do VFPv2/IEEE 754: round-to-nearest (default), round-toward-plus-infinity,
/// round-toward-minus-infinity e round-toward-zero. Task B3.8.
public enum FpRoundingMode {
    /// `RMODE=00`: arredonda para o valor representável mais próximo (empate = par). É o modo
    /// nativo de `float`/`double` do Java — nenhum ajuste extra é necessário nesse modo.
    ROUND_TO_NEAREST,
    /// `RMODE=01`: arredonda em direção a `+infinito` (nunca diminui o valor exato).
    ROUND_TOWARD_PLUS_INFINITY,
    /// `RMODE=10`: arredonda em direção a `-infinito` (nunca aumenta o valor exato).
    ROUND_TOWARD_MINUS_INFINITY,
    /// `RMODE=11`: arredonda em direção a zero (trunca a magnitude).
    ROUND_TOWARD_ZERO;

    /// Decodifica o campo `RMODE`\[1:0\] já isolado e deslocado para os bits `1:0` (ver
    /// {@link FpscrRegister#ROUNDING_MODE_SHIFT}/{@link FpscrRegister#ROUNDING_MODE_MASK}).
    public static FpRoundingMode fromFieldValue(int fieldValue) {
        return switch (fieldValue) {
            case 0b00 -> ROUND_TO_NEAREST;
            case 0b01 -> ROUND_TOWARD_PLUS_INFINITY;
            case 0b10 -> ROUND_TOWARD_MINUS_INFINITY;
            case 0b11 -> ROUND_TOWARD_ZERO;
            default -> throw new IllegalArgumentException("RMode fora do campo de 2 bits: " + fieldValue);
        };
    }
}
