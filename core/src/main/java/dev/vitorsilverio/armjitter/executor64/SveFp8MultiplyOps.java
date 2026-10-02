package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `FMLAL_hb`/`FMLALL_sb` (multiply-accumulate `fp8` fundido) e `FDOT_hb`/`FDOT_sb` (produto
/// escalar `fp8`), vetorial e indexado (B17.23) — reusa {@code AdvSimdLanes#fp8FusedMultiplyAdd}/
/// {@code #fp8DotProduct}, os MESMOS núcleos já validados pelo AdvSIMD (B19.11b/c/d), só trocando o laço por
/// elemento pela largura do vetor SVE inteiro.
final class SveFp8MultiplyOps {
    private static final int ESZ_BYTE = 0;
    private static final int FP8_BYTE_MASK = 0xFF;
    private static final int SEGMENT_BYTES = 16;

    private SveFp8MultiplyOps() {
    }

    /// `FMLAL_hb`/`FMLALL_sb`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeFusedMultiplyAdd(Aarch64Core core, SveFpOp64.Fp8FusedMultiplyAddLong op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        boolean nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
        boolean mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
        boolean osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
        int lscale = op.wideDestination() ? core.fp8MultiplyDownscale() : core.fp8WidenScale();
        int destEsz = op.wideDestination() ? 2 : 1;
        int stride = 1 << destEsz;
        int elements = core.vectorLengthBytes() >> destEsz;
        int elementsPerSegment = SEGMENT_BYTES >> destEsz;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int nByte = (int) (SveIntegerOps.get(regs, op.rn(), stride * i + op.sourceSelect(), ESZ_BYTE) & FP8_BYTE_MASK);
            int mByte;
            if (op.indexed()) {
                int segment = i / elementsPerSegment;
                mByte = (int) (SveIntegerOps.get(regs, op.rm(), segment * SEGMENT_BYTES + op.index(), ESZ_BYTE)
                        & FP8_BYTE_MASK);
            } else {
                mByte = (int) (SveIntegerOps.get(regs, op.rm(), stride * i + op.sourceSelect(), ESZ_BYTE) & FP8_BYTE_MASK);
            }
            long accBits = SveIntegerOps.get(regs, op.rd(), i, destEsz);
            results[i] = AdvSimdLanes.fp8FusedMultiplyAdd(nByte, nE4m3, mByte, mE4m3, lscale, osm, accBits,
                    op.wideDestination());
        }
        for (int i = 0; i < elements; i++) {
            SveIntegerOps.set(regs, op.rd(), i, destEsz, results[i]);
        }
        return false;
    }

    /// `FDOT_hb`/`FDOT_sb`. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean executeDotProduct(Aarch64Core core, SveFpOp64.Fp8DotProduct op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        boolean nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
        boolean mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
        boolean osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
        int lscale = op.wideDestination() ? core.fp8MultiplyDownscale() : core.fp8WidenScale();
        int destEsz = op.wideDestination() ? 2 : 1;
        int groupEsz = destEsz; // grupo de `elementsPerLane` bytes fp8 tem a MESMA largura do destino
        int elementsPerLane = op.wideDestination() ? 4 : 2; // `FEAT_FP8DOT4`/`FEAT_FP8DOT2`
        int elements = core.vectorLengthBytes() >> destEsz;
        int elementsPerSegment = SEGMENT_BYTES >> groupEsz;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            long nBytes = SveIntegerOps.get(regs, op.rn(), i, groupEsz);
            long mBytes;
            if (op.indexed()) {
                int segment = i / elementsPerSegment;
                mBytes = SveIntegerOps.get(regs, op.rm(), segment * elementsPerSegment + op.index(), groupEsz);
            } else {
                mBytes = SveIntegerOps.get(regs, op.rm(), i, groupEsz);
            }
            long accBits = SveIntegerOps.get(regs, op.rd(), i, destEsz);
            results[i] = AdvSimdLanes.fp8DotProduct(nBytes, mBytes, nE4m3, mE4m3, elementsPerLane, lscale, osm,
                    accBits, op.wideDestination());
        }
        for (int i = 0; i < elements; i++) {
            SveIntegerOps.set(regs, op.rd(), i, destEsz, results[i]);
        }
        return false;
    }
}
