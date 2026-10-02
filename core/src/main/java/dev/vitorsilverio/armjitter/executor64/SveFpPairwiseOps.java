package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `FADDP`/`FMAXNMP`/`FMINNMP`/`FMAXP`/`FMINP` (B17.23) — soma/máximo/mínimo par a par dentro de
/// cada segmento de 128 bits. Medido contra `DO_ZPZZ_PAIR_FP` do QEMU real: para cada par de posições `(p,
/// p+1)` dentro do segmento, o destino em `p` vem do par de `Zn` e o destino em `p+1` vem do MESMO par de
/// `Zm` — sempre MERGING (elemento inativo preserva `Zdn`).
final class SveFpPairwiseOps {
    private static final int SEGMENT_BYTES = 16;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int PAIR = 2;

    private SveFpPairwiseOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, SveFpOp64.FpPairwise op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        SveFloat.Env env = SveFloat.Env.of(core, esz);
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        long[] n = snapshot(regs, op.rd(), elements, esz); // `rd` = `rn` = `Zdn` (destrutivo)
        long[] m = snapshot(regs, op.rm(), elements, esz);
        for (int segment = 0; segment < elements; segment += perSegment) {
            for (int p = 0; p < perSegment; p += PAIR) {
                int destN = segment + p;
                int destM = segment + p + 1;
                if (active(regs, op.pg(), destN, esz)) {
                    SveIntegerOps.set(regs, op.rd(), destN, esz, combine(op.op(), n[destN], n[destN + 1], env));
                }
                if (active(regs, op.pg(), destM, esz)) {
                    SveIntegerOps.set(regs, op.rd(), destM, esz, combine(op.op(), m[destM - 1], m[destM], env));
                }
            }
        }
        env.commit(core);
        return false;
    }

    private static long combine(SveFpOp64.FpPairwise.Op op, long a, long b, SveFloat.Env env) {
        return switch (op) {
            case FADDP -> SveFloat.add(a, b, false, env);
            case FMAXNMP -> SveFloat.maxMinNumber(a, b, true, env);
            case FMINNMP -> SveFloat.maxMinNumber(a, b, false, env);
            case FMAXP -> SveFloat.maxMin(a, b, true, env);
            case FMINP -> SveFloat.maxMin(a, b, false, env);
        };
    }

    private static long[] snapshot(Aarch64ScalableRegisters regs, int reg, int elements, int esz) {
        long[] copy = new long[elements];
        for (int e = 0; e < elements; e++) {
            copy[e] = SveIntegerOps.get(regs, reg, e, esz);
        }
        return copy;
    }

    private static boolean active(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }
}
