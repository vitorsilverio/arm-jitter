package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `BFMMLA`/`FMMLA_s`/`FMMLA_d`/`FMMLA_sb`/`FMMLA_hb` (B17.23) — multiplicação de matriz `2×2`
/// acumulada por bloco (`Zda += Zn × Zm`). **`FMMLA_s`/`FMMLA_d` NÃO são fundidas** (cada produto e a soma dos
/// dois são arredondados separadamente, medido contra `HELPER(fmmla_s)`/`HELPER(fmmla_d)` do QEMU real —
/// `SveFloat.multiply`/`SveFloat.add` em sequência). `BFMMLA` (fonte `bf16`, acumulador SEMPRE `f32`) e as
/// duas `fp8` (`FMMLA_sb`/`FMMLA_hb`) SÃO fundidas (um único arredondamento por célula, reusando
/// {@code AdvSimdLanes#fp8DotProduct}, `f8dotadd_*` do QEMU real).
public final class SveFpMatrixMultiplyOps {
    private static final int BLOCK_ROWS_COLS = 2;
    private static final int BFLOAT16_K = 4;
    private static final int BFLOAT16_SOURCE_ESZ = 1;
    private static final int FP8_SB_ELEMENTS_PER_LANE = 8;
    private static final int FP8_SB_GROUP_ESZ = 3;
    private static final int FP8_HB_ELEMENTS_PER_LANE = 4;
    private static final int FP8_HB_GROUP_ESZ = 2;

    private SveFpMatrixMultiplyOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    public static boolean execute(Aarch64Core core, SveFpOp64.FpMatrixMultiply op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        switch (op.op()) {
            case BFMMLA -> executeBFloat16(core, op);
            case FMMLA_S, FMMLA_D -> executeUnfused(core, op);
            case FMMLA_SB -> executeFp8(core, op, FP8_SB_ELEMENTS_PER_LANE, FP8_SB_GROUP_ESZ, true);
            case FMMLA_HB -> executeFp8(core, op, FP8_HB_ELEMENTS_PER_LANE, FP8_HB_GROUP_ESZ, false);
        }
        return false;
    }

    /// `BFMMLA`: fonte `bf16`, acumulador `f32`, `K = 4`, UM segmento de 128 bits por bloco.
    private static void executeBFloat16(Aarch64Core core, SveFpOp64.FpMatrixMultiply op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int segments = core.vectorLengthBytes() / 16;
        int destEsz = 2; // `f32`
        for (int segment = 0; segment < segments; segment++) {
            int base = segment * 8; // 8 elementos `bf16` por segmento (2 linhas × 4)
            for (int row = 0; row < BLOCK_ROWS_COLS; row++) {
                for (int col = 0; col < BLOCK_ROWS_COLS; col++) {
                    double dot = 0.0;
                    for (int k = 0; k < BFLOAT16_K; k++) {
                        float a = AdvSimdLanes.bf16ToFloat(
                                SveIntegerOps.get(regs, op.rn(), base + row * BFLOAT16_K + k, BFLOAT16_SOURCE_ESZ));
                        float b = AdvSimdLanes.bf16ToFloat(
                                SveIntegerOps.get(regs, op.rm(), base + col * BFLOAT16_K + k, BFLOAT16_SOURCE_ESZ));
                        dot += (double) a * (double) b;
                    }
                    int destIndex = segment * 4 + row * BLOCK_ROWS_COLS + col;
                    float acc = Float.intBitsToFloat((int) SveIntegerOps.get(regs, op.rd(), destIndex, destEsz));
                    SveIntegerOps.set(regs, op.rd(), destIndex, destEsz,
                            Float.floatToRawIntBits(acc + (float) dot) & 0xFFFF_FFFFL);
                }
            }
        }
    }

    /// `FMMLA_s`/`FMMLA_d`: `K = 2`, SEM fusão — cada produto e a soma final arredondam separadamente.
    /// `FMMLA_s` usa segmento de 128 bits (4 elementos `f32`); `FMMLA_d` usa 256 bits (4 elementos `f64`, o
    /// dobro do segmento normal — medido contra `simd_oprsz(desc) / (sizeof(float64) * 4)` do QEMU real).
    private static void executeUnfused(Aarch64Core core, SveFpOp64.FpMatrixMultiply op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        SveFloat.Env env = SveFloat.Env.of(core, esz);
        int blockElements = 4;
        int blockBytes = blockElements << esz;
        int blocks = core.vectorLengthBytes() / blockBytes;
        for (int block = 0; block < blocks; block++) {
            int base = block * blockElements;
            long n00 = SveIntegerOps.get(regs, op.rn(), base, esz);
            long n01 = SveIntegerOps.get(regs, op.rn(), base + 1, esz);
            long n10 = SveIntegerOps.get(regs, op.rn(), base + 2, esz);
            long n11 = SveIntegerOps.get(regs, op.rn(), base + 3, esz);
            long m00 = SveIntegerOps.get(regs, op.rm(), base, esz);
            long m01 = SveIntegerOps.get(regs, op.rm(), base + 1, esz);
            long m10 = SveIntegerOps.get(regs, op.rm(), base + 2, esz);
            long m11 = SveIntegerOps.get(regs, op.rm(), base + 3, esz);
            cell(regs, op.rd(), base, esz, n00, m00, n01, m01, env);
            cell(regs, op.rd(), base + 1, esz, n00, m10, n01, m11, env);
            cell(regs, op.rd(), base + 2, esz, n10, m00, n11, m01, env);
            cell(regs, op.rd(), base + 3, esz, n10, m10, n11, m11, env);
        }
        env.commit(core);
    }

    private static void cell(Aarch64ScalableRegisters regs, int rd, int index, int esz, long nA, long mA, long nB,
            long mB, SveFloat.Env env) {
        long p0 = SveFloat.multiply(nA, mA, env);
        long p1 = SveFloat.multiply(nB, mB, env);
        long sum = SveFloat.add(p0, p1, false, env);
        long acc = SveIntegerOps.get(regs, rd, index, esz);
        SveIntegerOps.set(regs, rd, index, esz, SveFloat.add(acc, sum, false, env));
    }

    /// `FMMLA_sb`/`FMMLA_hb`: fonte `fp8`, `K = 8`/`4`, fundida via {@code AdvSimdLanes#fp8DotProduct} (mesmo
    /// núcleo do produto escalar `fp8`, `f8dotadd_s`/`f8dotadd_h` do QEMU real). `groupEsz` = tamanho, em log2
    /// de bytes, do grupo de `elementsPerLane` bytes `fp8` lido de uma vez (`3` = 8 bytes/`sb`, `2` = 4
    /// bytes/`hb`) — sempre `destEsz + 1`.
    private static void executeFp8(Aarch64Core core, SveFpOp64.FpMatrixMultiply op, int elementsPerLane, int groupEsz,
            boolean wideDestination) {
        Aarch64ScalableRegisters regs = core.scalable();
        boolean nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
        boolean mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
        boolean osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
        int lscale = wideDestination ? core.fp8MultiplyDownscale() : core.fp8WidenScale();
        int destEsz = op.esz();
        int segmentBytes = 4 << destEsz;
        int segments = core.vectorLengthBytes() / segmentBytes;
        for (int segment = 0; segment < segments; segment++) {
            int groupBase = segment * 2;
            long row0 = SveIntegerOps.get(regs, op.rn(), groupBase, groupEsz);
            long row1 = SveIntegerOps.get(regs, op.rn(), groupBase + 1, groupEsz);
            long col0 = SveIntegerOps.get(regs, op.rm(), groupBase, groupEsz);
            long col1 = SveIntegerOps.get(regs, op.rm(), groupBase + 1, groupEsz);
            int destBase = segment * 4;
            long[] rows = {row0, row0, row1, row1};
            long[] cols = {col0, col1, col0, col1};
            for (int cellIndex = 0; cellIndex < 4; cellIndex++) {
                long acc = SveIntegerOps.get(regs, op.rd(), destBase + cellIndex, destEsz);
                long result = AdvSimdLanes.fp8DotProduct(rows[cellIndex], cols[cellIndex], nE4m3, mE4m3,
                        elementsPerLane, lscale, osm, acc, wideDestination);
                SveIntegerOps.set(regs, op.rd(), destBase + cellIndex, destEsz, result);
            }
        }
    }
}
