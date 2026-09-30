package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das conversões `fp8` sem predicado da B17.23: alargar ({@link Ir64Op.SveFpConvertFp8}) e
/// estreitar ({@link Ir64Op.SveFpConvertToFp8}). Reusa os núcleos escalares de {@link AdvSimdLanes}
/// (`fp8ToFloat`/`floatToFp8`) já validados pelo AdvSIMD (B19.11a) — só o laço por elemento é novo.
final class SveFpConvertFp8Ops {
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_WORD = 2;
    private static final int FP8_BYTE_MASK = 0xFF;

    private SveFpConvertFp8Ops() {
    }

    /// `F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/`BF2CVTLT`. `true` = a instrução já
    /// entrou numa exceção (acesso negado).
    static boolean executeWiden(Aarch64Core core, Ir64Op.SveFpConvertFp8 op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        boolean e4m3 = (op.stream2() ? core.fp8SourceFormat2() : core.fp8SourceFormat1()) == Aarch64Fp8Format.E4M3;
        int scale = op.bfloat16Destination()
                ? (op.stream2() ? core.fp8WidenScale2ForBFloat16() : core.fp8WidenScaleForBFloat16())
                : (op.stream2() ? core.fp8WidenScale2() : core.fp8WidenScale());
        int elements = core.vectorLengthBytes() >> ESZ_HALF;
        for (int i = 0; i < elements; i++) {
            int srcByte = (int) (SveIntegerOps.get(regs, op.rn(), 2 * i + (op.top() ? 1 : 0), ESZ_BYTE) & FP8_BYTE_MASK);
            float value = Math.scalb(AdvSimdLanes.fp8ToFloat(srcByte, e4m3), -scale);
            long resultBits = op.bfloat16Destination() ? AdvSimdLanes.bf16Bits(value) : AdvSimdLanes.halfBits(value);
            SveIntegerOps.set(regs, op.rd(), i, ESZ_HALF, resultBits);
        }
        return false;
    }

    /// `FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeNarrow(Aarch64Core core, Ir64Op.SveFpConvertToFp8 op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        boolean e4m3 = core.fp8DestinationFormat() == Aarch64Fp8Format.E4M3;
        int scale = core.fp8NarrowScale();
        boolean osc = core.fp8OverflowSaturatesToMaxNormal();
        if (op.wideSource()) {
            executeNarrowFromSingle(core, op, regs, e4m3, scale, osc);
        } else {
            executeNarrowFromHalfOrBFloat16(core, op, regs, e4m3, scale, osc);
        }
        return false;
    }

    private static void executeNarrowFromHalfOrBFloat16(Aarch64Core core, Ir64Op.SveFpConvertToFp8 op,
            Aarch64ScalableRegisters regs, boolean e4m3, int scale, boolean osc) {
        // largura do registrador-fonte é sempre H (esz=1), independentemente de `bfloat16Source`.
        int count = core.vectorLengthBytes() >> ESZ_HALF;
        for (int i = 0; i < count; i++) {
            long n0 = SveIntegerOps.get(regs, op.rn(), i, ESZ_HALF);
            long n1 = SveIntegerOps.get(regs, op.rn() + 1, i, ESZ_HALF);
            float v0 = op.bfloat16Source() ? AdvSimdLanes.bf16ToFloat(n0) : AdvSimdLanes.halfToFloat(n0);
            float v1 = op.bfloat16Source() ? AdvSimdLanes.bf16ToFloat(n1) : AdvSimdLanes.halfToFloat(n1);
            int fp8a = AdvSimdLanes.floatToFp8(Math.scalb(v0, scale), e4m3, osc);
            int fp8b = AdvSimdLanes.floatToFp8(Math.scalb(v1, scale), e4m3, osc);
            SveIntegerOps.set(regs, op.rd(), 2 * i, ESZ_BYTE, fp8a);
            SveIntegerOps.set(regs, op.rd(), 2 * i + 1, ESZ_BYTE, fp8b);
        }
    }

    private static void executeNarrowFromSingle(Aarch64Core core, Ir64Op.SveFpConvertToFp8 op,
            Aarch64ScalableRegisters regs, boolean e4m3, int scale, boolean osc) {
        int count = core.vectorLengthBytes() >> ESZ_WORD;
        for (int i = 0; i < count; i++) {
            long n0 = SveIntegerOps.get(regs, op.rn(), i, ESZ_WORD);
            long n1 = SveIntegerOps.get(regs, op.rn() + 1, i, ESZ_WORD);
            float v0 = Float.intBitsToFloat((int) n0);
            float v1 = Float.intBitsToFloat((int) n1);
            int fp8a = AdvSimdLanes.floatToFp8(Math.scalb(v0, scale), e4m3, osc);
            int fp8b = AdvSimdLanes.floatToFp8(Math.scalb(v1, scale), e4m3, osc);
            if (op.top()) {
                // `FCVTNT`: só o byte alto de cada slot `H`, PRESERVA o baixo (idioma bottom-depois-top).
                SveIntegerOps.set(regs, op.rd(), 4 * i + 1, ESZ_BYTE, fp8a);
                SveIntegerOps.set(regs, op.rd(), 4 * i + 3, ESZ_BYTE, fp8b);
            } else {
                // `FCVTNB`: byte baixo de cada slot `H`, ZERA o alto (via escrita de `esz=1` inteira).
                SveIntegerOps.set(regs, op.rd(), 2 * i, ESZ_HALF, fp8a & FP8_BYTE_MASK);
                SveIntegerOps.set(regs, op.rd(), 2 * i + 1, ESZ_HALF, fp8b & FP8_BYTE_MASK);
            }
        }
    }

}
