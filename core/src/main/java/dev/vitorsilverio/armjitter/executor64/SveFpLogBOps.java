package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `FLOGB` (`_m`/`_z`, B17.23) — expoente (base 2) de `Zn` como inteiro da mesma largura.
/// Medido contra `do_float{16,32,64}_logb_as_int` do QEMU real: zero/`NaN` levantam `Invalid` e devolvem o
/// `int` mínimo representável; Infinito devolve o máximo; subnormal com `FZ` desligado devolve
/// `-viés - clz(fração)`; normal devolve `expoente_não_enviesado - viés`.
public final class SveFpLogBOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;

    private SveFpLogBOps() {
    }

    /// Executa. `true` = a instrução já entrou numa exceção (acesso negado).
    public static boolean execute(Aarch64Core core, SveFpOp64.FpLogB op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        SveFloat.Env env = SveFloat.Env.of(core, esz);
        int elements = core.vectorLengthBytes() >> esz;
        int intBits = SveIntegerOps.elementBits(esz);
        long minValue = -(1L << (intBits - 1));
        long maxValue = (1L << (intBits - 1)) - 1L;
        for (int e = 0; e < elements; e++) {
            int bit = e << esz;
            boolean active = ((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
            if (!active) {
                if (op.zeroing()) {
                    SveIntegerOps.set(regs, op.rd(), e, esz, 0L);
                }
                continue;
            }
            long source = SveIntegerOps.get(regs, op.rn(), e, esz);
            long result = logb(source, env, minValue, maxValue);
            SveIntegerOps.set(regs, op.rd(), e, esz, result & SveIntegerOps.elementMask(esz));
        }
        env.commit(core);
        return false;
    }

    private static long logb(long bits, SveFloat.Env env, long minValue, long maxValue) {
        long exp = (bits >>> env.fracBits) & ((1L << env.expBits) - 1L);
        long frac = bits & env.fracMask;
        if (exp == 0) {
            if (frac != 0 && !env.flushToZero) {
                env.flags |= SveFloat.FLAG_IDC;
                int leadingZeros = Long.numberOfLeadingZeros(frac) - (Long.SIZE - env.fracBits);
                return -env.bias - leadingZeros;
            }
            if (frac != 0) {
                env.flags |= SveFloat.FLAG_IDC;
            }
        } else if (exp == env.maxBiasedExponent) {
            if (frac == 0) {
                return maxValue; // Infinito
            }
        } else {
            return exp - env.bias; // normal
        }
        env.flags |= SveFloat.FLAG_IOC; // zero ou NaN
        return minValue;
    }
}
