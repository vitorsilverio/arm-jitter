package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.ParallelAluOp;
import dev.vitorsilverio.armjitter.ir.ParallelAluVariant;

/// Helpers estáticos invocados pelo bytecode ASM gerado: inteiro ARMv5TE (saturação/DSP) e ARMv6 (extend/reverse, paralelas, SEL, saturação, USAD).
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class AsmIntegerHelpers {
    private AsmIntegerHelpers() {
    }

    // ── ARMv5TE (saturação / DSP) ──────────────────────────────────────────────
    // Espelham IrAluExecutor.executeSaturating/executeDspMultiply. Recebem VALORES e devolvem o
    // resultado (o bytecode lê/escreve registradores pelo register cache); só o bit Q sticky é
    // efeito colateral no core.

    /// QADD/QSUB/QDADD/QDSUB. `op`: 0=QADD, 1=QSUB, 2=QDADD, 3=QDSUB. Satura em 32 bits com sinal
    /// e seta o bit Q em overflow (de qualquer etapa, como no interpretador).
    public static int saturating(ArmCore core, int rm, int rn, int op) {
        boolean q = false;
        long result;
        if (op == 0) {
            result = (long) rm + rn;
        } else if (op == 1) {
            result = (long) rm - rn;
        } else {
            long doubled = 2L * rn;
            int clamped;
            if (doubled > Integer.MAX_VALUE) {
                q = true;
                clamped = Integer.MAX_VALUE;
            } else if (doubled < Integer.MIN_VALUE) {
                q = true;
                clamped = Integer.MIN_VALUE;
            } else {
                clamped = (int) doubled;
            }
            result = op == 2 ? (long) rm + clamped : (long) rm - clamped;
        }
        int out;
        if (result > Integer.MAX_VALUE) {
            q = true;
            out = Integer.MAX_VALUE;
        } else if (result < Integer.MIN_VALUE) {
            q = true;
            out = Integer.MIN_VALUE;
        } else {
            out = (int) result;
        }
        if (q) {
            core.cpsr().setSaturation(true); // sticky
        }
        return out;
    }

    /// SMLAxy: (Rm.x * Rs.y) + Rn, com Q se o acúmulo de 32 bits estoura.
    public static int dspSmla(ArmCore core, int rmHalf, int rsHalf, int rn) {
        long sum = (long) (rmHalf * rsHalf) + rn;
        if (sum != (int) sum) {
            core.cpsr().setSaturation(true);
        }
        return (int) sum;
    }

    /// SMLAWy: ((Rm * Rs.y) >> 16) + Rn, com Q se o acúmulo estoura.
    public static int dspSmlaw(ArmCore core, int rm, int rsHalf, int rn) {
        int product = (int) (((long) rm * rsHalf) >> 16);
        long sum = (long) product + rn;
        if (sum != (int) sum) {
            core.cpsr().setSaturation(true);
        }
        return (int) sum;
    }

    /// SMULWy: (Rm * Rs.y) >> 16 (sem acumular, sem Q).
    public static int dspSmulw(int rm, int rsHalf) {
        return (int) (((long) rm * rsHalf) >> 16);
    }

    /// SMLALxy: {RdHi:RdLo} + RmHalf*RsHalf em 64 bits (sem Q).
    public static long dspSmlal(int rdHi, int rdLo, int rmHalf, int rsHalf) {
        long acc = ((long) rdHi << 32) | (rdLo & 0xFFFF_FFFFL);
        return acc + (long) rmHalf * rsHalf;
    }

    // ── ARMv6 (B1.2): extend/reverse ────────────────────────────────────────────
    // Espelham os casos SXTB16/UXTB16/REV16/REVSH de IrAluExecutor.execute. SXTB/SXTH/UXTB/UXTH
    // e REV são bytecode direto no AsmBlockCompiler (I2B/I2S/AND/Integer.reverseBytes).

    /// SXTB16/UXTB16: estende os dois bytes pares (bits 23:16 e 7:0) do valor já rotacionado pelo
    /// operando, cada halfword acumulada de forma independente (módulo 2^16, sem carry entre elas).
    public static int extendByte16(int accumulator, int right, boolean signedExtend) {
        int lowByte = signedExtend ? (byte) right : right & 0xFF;
        int highByte = signedExtend ? (byte) (right >>> 16) : (right >>> 16) & 0xFF;
        int lowHalf = (accumulator + lowByte) & 0xFFFF;
        int highHalf = ((accumulator >>> 16) + highByte) & 0xFFFF;
        return (highHalf << 16) | lowHalf;
    }

    /// REV16: troca os bytes dentro de cada halfword.
    public static int reverseHalfwords(int value) {
        return ((value & 0xFF00_FF00) >>> 8) | ((value & 0x00FF_00FF) << 8);
    }

    /// REVSH: inverte os bytes do halfword baixo e estende o sinal para 32 bits.
    public static int reverseSignedHalfword(int value) {
        return (short) (((value & 0xFF) << 8) | ((value >>> 8) & 0xFF));
    }

    // ── ARMv6 (B1.3): paralelas / SEL / saturação / USAD ────────────────────────
    // Espelham IrAluExecutor.executeParallelAlu/executeSel/executeSaturate/executeAbsDiffSum.

    /// Aritmética paralela ARMv6 (SADD16/UQSUB8/SHASX/...): cada lane é computada em precisão
    /// larga (int) e finalizada pela variante — wrap (escrevendo GE), saturação ou halving. As
    /// variantes saturadas paralelas NÃO tocam o flag Q sticky (diferente de QADD/QSUB).
    public static int parallelAlu(ArmCore core, int rn, int rm, int opOrdinal, int variantOrdinal) {
        ParallelAluOp op = ParallelAluOp.values()[opOrdinal];
        ParallelAluVariant variant = ParallelAluVariant.values()[variantOrdinal];
        int result;
        int ge;
        if (op.laneBits() == 8) {
            boolean add = op == ParallelAluOp.ADD8;
            result = 0;
            ge = 0;
            for (int lane = 0; lane < 4; lane++) {
                int shift = lane * 8;
                int a = parallelLaneValue(rn >> shift, 8, variant.unsigned());
                int b = parallelLaneValue(rm >> shift, 8, variant.unsigned());
                int wide = add ? a + b : a - b;
                result |= (parallelFinishLane(wide, 8, variant) & 0xFF) << shift;
                if (parallelLaneGe(wide, 8, add, variant.unsigned())) {
                    ge |= 1 << lane;
                }
            }
        } else {
            // Formas halfword: ASX/SAX cruzam as lanes de Rm e misturam soma/subtração.
            int rnLow = parallelLaneValue(rn, 16, variant.unsigned());
            int rnHigh = parallelLaneValue(rn >> 16, 16, variant.unsigned());
            int rmLow = parallelLaneValue(rm, 16, variant.unsigned());
            int rmHigh = parallelLaneValue(rm >> 16, 16, variant.unsigned());
            boolean lowAdds;
            boolean highAdds;
            int wideLow;
            int wideHigh;
            switch (op) {
                case ADD16 -> { lowAdds = true; highAdds = true; wideLow = rnLow + rmLow; wideHigh = rnHigh + rmHigh; }
                case SUB16 -> { lowAdds = false; highAdds = false; wideLow = rnLow - rmLow; wideHigh = rnHigh - rmHigh; }
                case SAX -> { lowAdds = true; highAdds = false; wideLow = rnLow + rmHigh; wideHigh = rnHigh - rmLow; }
                case ASX -> { lowAdds = false; highAdds = true; wideLow = rnLow - rmHigh; wideHigh = rnHigh + rmLow; }
                default -> throw new IllegalStateException("Lane de 16 bits inesperada: " + op);
            }
            result = (parallelFinishLane(wideLow, 16, variant) & 0xFFFF)
                    | ((parallelFinishLane(wideHigh, 16, variant) & 0xFFFF) << 16);
            ge = (parallelLaneGe(wideLow, 16, lowAdds, variant.unsigned()) ? 0b0011 : 0)
                    | (parallelLaneGe(wideHigh, 16, highAdds, variant.unsigned()) ? 0b1100 : 0);
        }
        if (variant.writesGe()) {
            core.cpsr().setGe(ge);
        }
        return result;
    }

    /// Valor de uma lane já deslocada para os bits baixos, estendida por sinal ou por zero.
    private static int parallelLaneValue(int shifted, int laneBits, boolean unsigned) {
        if (laneBits == 8) {
            return unsigned ? shifted & 0xFF : (byte) shifted;
        }
        return unsigned ? shifted & 0xFFFF : (short) shifted;
    }

    /// Finaliza a lane conforme a variante: wrap (o chamador trunca), saturação ou halving.
    private static int parallelFinishLane(int wide, int laneBits, ParallelAluVariant variant) {
        if (variant.saturating()) {
            int max = variant.unsigned() ? (1 << laneBits) - 1 : (1 << (laneBits - 1)) - 1;
            int min = variant.unsigned() ? 0 : -(1 << (laneBits - 1));
            return Math.clamp(wide, min, max);
        }
        if (variant.halving()) {
            return wide >> 1;
        }
        return wide;
    }

    /// Regra GE por lane (só usada pelas variantes sem prefixo): com sinal, resultado ≥ 0;
    /// sem sinal, carry na soma (estouro da largura) e ausência de borrow na subtração.
    private static boolean parallelLaneGe(int wide, int laneBits, boolean add, boolean unsigned) {
        if (unsigned && add) {
            return wide >= (1 << laneBits);
        }
        return wide >= 0;
    }

    /// `SEL` (ARMv6): cada byte do resultado vem de Rn quando o GE correspondente está setado,
    /// senão de Rm. Não altera flag algum.
    public static int sel(ArmCore core, int rn, int rm) {
        int ge = core.cpsr().ge();
        int mask = 0;
        for (int lane = 0; lane < 4; lane++) {
            if ((ge & (1 << lane)) != 0) {
                mask |= 0xFF << (lane * 8);
            }
        }
        return (rn & mask) | (rm & ~mask);
    }

    /// SSAT/USAT/SSAT16/USAT16 (ARMv6): satura o operando (já shiftado pelo chamador nas formas
    /// word) para a largura pedida e seta o flag Q sticky quando alguma lane saturou.
    public static int saturate(ArmCore core, int value, int saturateBits, boolean unsignedRange, boolean halfwords) {
        boolean[] q = {false};
        int result;
        if (halfwords) {
            int low = saturateTo((short) value, saturateBits, unsignedRange, q);
            int high = saturateTo((short) (value >> 16), saturateBits, unsignedRange, q);
            result = ((high & 0xFFFF) << 16) | (low & 0xFFFF);
        } else {
            result = saturateTo(value, saturateBits, unsignedRange, q);
        }
        if (q[0]) {
            core.cpsr().setSaturation(true); // sticky, como QADD/QSUB
        }
        return result;
    }

    /// Satura `value` para `bits` bits (com ou sem sinal), marcando `q[0]` quando clampa.
    /// Com sinal, `bits` é 1..32 (32 nunca satura); sem sinal, 0..31 (0 clampa tudo para 0).
    private static int saturateTo(int value, int bits, boolean unsignedRange, boolean[] q) {
        int min;
        int max;
        if (unsignedRange) {
            min = 0;
            max = (1 << bits) - 1;
        } else {
            min = -(1 << (bits - 1));
            max = (1 << (bits - 1)) - 1;
        }
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            q[0] = true;
        }
        return clamped;
    }

    /// USAD8/USADA8 (ARMv6): soma das diferenças absolutas dos quatro bytes sem sinal, com
    /// acumulador opcional. Não altera flag algum.
    public static int absDiffSum(int rm, int rs, boolean hasAccumulator, int accumulator) {
        int sum = 0;
        for (int lane = 0; lane < 4; lane++) {
            int shift = lane * 8;
            sum += Math.abs(((rm >> shift) & 0xFF) - ((rs >> shift) & 0xFF));
        }
        if (hasAccumulator) {
            sum += accumulator;
        }
        return sum;
    }
}
