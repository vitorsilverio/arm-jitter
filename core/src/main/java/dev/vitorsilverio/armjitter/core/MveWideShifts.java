package dev.vitorsilverio.armjitter.core;

/// Aritmética dos "wide shifts" do MVE/Helium sobre registradores de propósito geral (B16.16):
/// `LSLL`/`LSRL`/`ASRL`/`URSHRL`/`SRSHRL`/`UQSHLL`/`SQSHLL`/`UQRSHLL`/`SQRSHRL` (64 bits, também com
/// corte em 48 bits) e `UQSHL`/`SQSHL`/`URSHR`/`SRSHR`/`UQRSHL`/`SQRSHR` (32 bits).
///
/// Porta VERBATIM de `do_sqrshl_bhs`/`do_uqrshl_bhs`/`do_sqrshl_d`/`do_uqrshl_d`
/// (`target/arm/tcg/vec_internal.h`) e `do_sqrshl48_d`/`do_uqrshl48_d` (`mve_helper.c`) do QEMU,
/// lidos do fonte real (não do manual). Convenção comum: `shift > 0` desloca à esquerda, `shift < 0`
/// à direita (com `round`, arredonda para o mais próximo, empates para cima); só o lado ESQUERDO
/// satura. `sat` habilita a detecção de saturação: quando `false`, o valor é apenas truncado e
/// {@link Result#saturated()} nunca é `true` (é o `sat == NULL` do QEMU).
///
/// A bandeira devolvida vai para `APSR.Q` ({@link CpsrRegister#setSaturation}), **não** para
/// `FPSCR.QC` — o resto do MVE vetorial satura em `QC`.
public final class MveWideShifts {
    private static final int WORD_BITS = 32;
    private static final int DOUBLE_BITS = 64;
    private static final int NARROW_BITS = 48;
    private static final int SIGN_SHIFT_32 = 31;
    private static final int SIGN_SHIFT_64 = 63;
    private static final int NARROW_EXTEND_SHIFT = DOUBLE_BITS - NARROW_BITS;
    private static final long NARROW_MASK = (1L << NARROW_BITS) - 1;
    private static final long NARROW_SIGNED_MAX = (1L << (NARROW_BITS - 1)) - 1;
    private static final long NARROW_SIGNED_MIN = ~NARROW_SIGNED_MAX;

    private MveWideShifts() {
    }

    /// Valor produzido e se a operação saturou (para `APSR.Q`).
    ///
    /// @param value     resultado (32 bits nas formas de 32 bits, zero-extendido; 64 bits nas demais)
    /// @param saturated `true` quando o resultado foi grampeado
    public record Result(long value, boolean saturated) {
    }

    private static Result plain(long value) {
        return new Result(value, false);
    }

    private static Result saturated(long value) {
        return new Result(value, true);
    }

    /// `do_sqrshl_bhs` com `bits == 32` (`SQSHL`/`SRSHR`/`SQRSHR`).
    ///
    /// @param src   valor de 32 bits, com sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    /// @param round arredonda o deslocamento à direita
    /// @param sat   detecta saturação
    /// @return resultado como `int` zero-extendido em `long`
    public static Result signedShift32(int src, int shift, boolean round, boolean sat) {
        if (shift <= -WORD_BITS) {
            // Arredondar o bit de sinal sempre produz 0.
            return plain(round ? 0 : (src >> SIGN_SHIFT_32) & 0xFFFF_FFFFL);
        } else if (shift < 0) {
            if (round) {
                int half = src >> (-shift - 1);
                return plain(((half >> 1) + (half & 1)) & 0xFFFF_FFFFL);
            }
            return plain((src >> -shift) & 0xFFFF_FFFFL);
        } else if (shift < WORD_BITS) {
            int value = src << shift;
            if (!sat || (value >> shift) == src) {
                return plain(value & 0xFFFF_FFFFL);
            }
        } else if (!sat || src == 0) {
            return plain(0);
        }
        int limit = Integer.MIN_VALUE - (src >= 0 ? 1 : 0);
        return saturated(limit & 0xFFFF_FFFFL);
    }

    /// `do_uqrshl_bhs` com `bits == 32` (`UQSHL`/`URSHR`/`UQRSHL`).
    ///
    /// @param src   valor de 32 bits, sem sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    /// @param round arredonda o deslocamento à direita
    /// @param sat   detecta saturação
    /// @return resultado como `int` zero-extendido em `long`
    public static Result unsignedShift32(int src, int shift, boolean round, boolean sat) {
        if (shift <= -(WORD_BITS + (round ? 1 : 0))) {
            return plain(0);
        } else if (shift < 0) {
            if (round) {
                int half = src >>> (-shift - 1);
                return plain(((half >>> 1) + (half & 1)) & 0xFFFF_FFFFL);
            }
            return plain((src >>> -shift) & 0xFFFF_FFFFL);
        } else if (shift < WORD_BITS) {
            int value = src << shift;
            if (!sat || (value >>> shift) == src) {
                return plain(value & 0xFFFF_FFFFL);
            }
        } else if (!sat || src == 0) {
            return plain(0);
        }
        return saturated(0xFFFF_FFFFL);
    }

    /// `do_sqrshl_d` (`ASRL`/`SRSHRL`/`SQSHLL`/`SQRSHRL`, 64 bits com sinal).
    ///
    /// @param src   valor de 64 bits, com sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    /// @param round arredonda o deslocamento à direita
    /// @param sat   detecta saturação
    public static Result signedShift64(long src, int shift, boolean round, boolean sat) {
        if (shift <= -DOUBLE_BITS) {
            return plain(round ? 0 : src >> SIGN_SHIFT_64);
        } else if (shift < 0) {
            if (round) {
                long half = src >> (-shift - 1);
                return plain((half >> 1) + (half & 1));
            }
            return plain(src >> -shift);
        } else if (shift < DOUBLE_BITS) {
            long value = src << shift;
            if (!sat || (value >> shift) == src) {
                return plain(value);
            }
        } else if (!sat || src == 0) {
            return plain(0);
        }
        return saturated(src < 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
    }

    /// `do_uqrshl_d` (`LSLL`/`LSRL`/`URSHRL`/`UQSHLL`/`UQRSHLL`, 64 bits sem sinal).
    ///
    /// @param src   valor de 64 bits, sem sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    /// @param round arredonda o deslocamento à direita
    /// @param sat   detecta saturação
    public static Result unsignedShift64(long src, int shift, boolean round, boolean sat) {
        if (shift <= -(DOUBLE_BITS + (round ? 1 : 0))) {
            return plain(0);
        } else if (shift < 0) {
            if (round) {
                long half = src >>> (-shift - 1);
                return plain((half >>> 1) + (half & 1));
            }
            return plain(src >>> -shift);
        } else if (shift < DOUBLE_BITS) {
            long value = src << shift;
            if (!sat || (value >>> shift) == src) {
                return plain(value);
            }
        } else if (!sat || src == 0) {
            return plain(0);
        }
        return saturated(-1L);
    }

    /// `do_sqrshl48_d` (`SQRSHRL48`): opera em 64 bits mas satura em 48 (o resultado é o valor de
    /// 48 bits estendido com sinal). Sempre com arredondamento e detecção de saturação, que é como o
    /// QEMU a chama (`mve_sqrshrl48`); por isso `round`/`sat` não são parâmetros.
    ///
    /// @param src   valor de 64 bits, com sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    public static Result signedShift48(long src, int shift) {
        if (shift <= -NARROW_BITS) {
            // Arredondar o bit de sinal sempre produz 0.
            return plain(0);
        } else if (shift < 0) {
            long half = src >> (-shift - 1);
            long value = (half >> 1) + (half & 1);
            long extended = signExtend48(value);
            if (value == extended) {
                return plain(extended);
            }
        } else if (shift < NARROW_BITS) {
            long extended = signExtend48(src << shift);
            if (src == (extended >> shift)) {
                return plain(extended);
            }
        } else if (src == 0) {
            return plain(0);
        }
        return saturated(src >= 0 ? NARROW_SIGNED_MAX : NARROW_SIGNED_MIN);
    }

    /// `do_uqrshl48_d` (`UQRSHLL48`): opera em 64 bits mas satura em 48. Sempre com arredondamento e
    /// detecção de saturação (ver {@link #signedShift48}).
    ///
    /// @param src   valor de 64 bits, sem sinal
    /// @param shift quantidade (`> 0` esquerda, `< 0` direita)
    public static Result unsignedShift48(long src, int shift) {
        if (shift <= -(NARROW_BITS + 1)) {
            return plain(0);
        } else if (shift < 0) {
            long half = src >>> (-shift - 1);
            long value = (half >>> 1) + (half & 1);
            long extended = value & NARROW_MASK;
            if (value == extended) {
                return plain(extended);
            }
        } else if (shift < NARROW_BITS) {
            long extended = (src << shift) & NARROW_MASK;
            if (src == (extended >>> shift)) {
                return plain(extended);
            }
        } else if (src == 0) {
            return plain(0);
        }
        return saturated(NARROW_MASK);
    }

    private static long signExtend48(long value) {
        return (value << NARROW_EXTEND_SHIFT) >> NARROW_EXTEND_SHIFT;
    }
}
