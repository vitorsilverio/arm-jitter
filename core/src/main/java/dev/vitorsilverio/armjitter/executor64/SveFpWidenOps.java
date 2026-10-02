package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `FMLALB`/`FMLALT`/`FMLSLB`/`FMLSLT` e `BFMLALB`/`BFMLALT`/`BFMLSLB`/`BFMLSLT` (multiply-add-long
/// `binary16`/`bfloat16` → `binary32`) e `FDOT_zzzz`/`BFDOT_zzzz` (produto escalar de duas vias), vetorial e
/// indexado (B17.23). A multiplicação-acumulação é FUNDIDA (`Math.fma`, um só arredondamento — medido contra
/// `float32_muladd` do QEMU real, `sve2_fmlal_zzzw_s`); o produto escalar soma os 2 produtos em `double`
/// (exatos, dado o pouco alcance de `binary16`/`bfloat16`) antes de UM arredondamento final na soma com o
/// acumulador.
final class SveFpWidenOps {
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int SEGMENT_BYTES = 16;
    private static final int HALVES_PER_SEGMENT = SEGMENT_BYTES / 2;

    private SveFpWidenOps() {
    }

    /// `FMLALB`/`FMLALT`/`FMLSLB`/`FMLSLT`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeMultiplyAddLong(Aarch64Core core, SveFpOp64.FpMultiplyAddLongWiden op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        boolean subtract = op.op() == SveFpOp64.FpMultiplyAddLongWiden.Op.FMLSL;
        multiplyAddLong(core, op.rd(), op.rn(), op.rm(), op.top(), op.indexed(), op.index(), subtract,
                AdvSimdLanes::halfToFloat);
        return false;
    }

    /// `BFMLALB`/`BFMLALT`/`BFMLSLB`/`BFMLSLT`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeMultiplyAddLongBFloat16(Aarch64Core core, SveFpOp64.FpMultiplyAddLongWidenBFloat16 op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        boolean subtract = op.op() == SveFpOp64.FpMultiplyAddLongWidenBFloat16.Op.BFMLSL;
        multiplyAddLong(core, op.rd(), op.rn(), op.rm(), op.top(), op.indexed(), op.index(), subtract,
                AdvSimdLanes::bf16ToFloat);
        return false;
    }

    private interface Unpack {
        float toFloat(long bits);
    }

    private static void multiplyAddLong(Aarch64Core core, int rd, int rn, int rm, boolean top, boolean indexed,
            int index, boolean subtract, Unpack unpack) {
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() >> ESZ_SINGLE;
        int elementsPerSegment = SEGMENT_BYTES >> ESZ_SINGLE;
        int narrowOffset = top ? 1 : 0;
        long[] results = new long[elements];
        for (int e = 0; e < elements; e++) {
            float a = unpack.toFloat(SveIntegerOps.get(regs, rn, 2 * e + narrowOffset, ESZ_HALF));
            float b;
            if (indexed) {
                int segment = e / elementsPerSegment;
                b = unpack.toFloat(SveIntegerOps.get(regs, rm, segment * HALVES_PER_SEGMENT + index, ESZ_HALF));
            } else {
                b = unpack.toFloat(SveIntegerOps.get(regs, rm, 2 * e + narrowOffset, ESZ_HALF));
            }
            float acc = Float.intBitsToFloat((int) SveIntegerOps.get(regs, rd, e, ESZ_SINGLE));
            float result = subtract ? Math.fma(-a, b, acc) : Math.fma(a, b, acc);
            results[e] = Float.floatToRawIntBits(result) & 0xFFFF_FFFFL;
        }
        for (int e = 0; e < elements; e++) {
            SveIntegerOps.set(regs, rd, e, ESZ_SINGLE, results[e]);
        }
    }

    /// `FDOT_zzzz`/`FDOT_zzxz`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeDotProduct(Aarch64Core core, SveFpOp64.FpDotProductWiden op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        dotProduct(core, op.rd(), op.rn(), op.rm(), op.indexed(), op.index(), AdvSimdLanes::halfToFloat);
        return false;
    }

    /// `BFDOT_zzzz`/`BFDOT_zzxz`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeDotProductBFloat16(Aarch64Core core, SveFpOp64.FpDotProductWidenBFloat16 op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        dotProduct(core, op.rd(), op.rn(), op.rm(), op.indexed(), op.index(), AdvSimdLanes::bf16ToFloat);
        return false;
    }

    private static void dotProduct(Aarch64Core core, int rd, int rn, int rm, boolean indexed, int index,
            Unpack unpack) {
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() >> ESZ_SINGLE;
        int elementsPerSegment = SEGMENT_BYTES >> ESZ_SINGLE;
        long[] results = new long[elements];
        for (int e = 0; e < elements; e++) {
            long nPair = SveIntegerOps.get(regs, rn, e, ESZ_SINGLE);
            long mPair;
            if (indexed) {
                int segment = e / elementsPerSegment;
                mPair = SveIntegerOps.get(regs, rm, segment * elementsPerSegment + index, ESZ_SINGLE);
            } else {
                mPair = SveIntegerOps.get(regs, rm, e, ESZ_SINGLE);
            }
            double sum = 0.0;
            for (int k = 0; k < 2; k++) {
                float a = unpack.toFloat((nPair >>> (k * 16)) & 0xFFFFL);
                float b = unpack.toFloat((mPair >>> (k * 16)) & 0xFFFFL);
                sum += (double) a * (double) b;
            }
            float acc = Float.intBitsToFloat((int) SveIntegerOps.get(regs, rd, e, ESZ_SINGLE));
            results[e] = Float.floatToRawIntBits(acc + (float) sum) & 0xFFFF_FFFFL;
        }
        for (int e = 0; e < elements; e++) {
            SveIntegerOps.set(regs, rd, e, ESZ_SINGLE, results[e]);
        }
    }
}
