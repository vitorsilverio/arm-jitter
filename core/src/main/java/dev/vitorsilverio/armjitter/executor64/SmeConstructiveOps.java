package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;

/// SME2 multi-vetor SVE "constructive" (B18.12): conversões FP, estreitamento/alargamento inteiro, `*RSHR*`, `ZIP`/`UZP`,
/// `*CLAMP` e `SEL` sobre grupos de `2`/`4` registradores `Z` (`translate-sme.c`/`sme_helper.c`/`fp8_helper.c` do QEMU).
///
/// - **Não toca `ZA`**: exige só modo streaming (`SVL`), exceto as três `*RSHRN_sh` (compartilhadas com `FEAT_SVE2p1`),
///   que usam a checagem SVE/SME comum.
/// - **Origens são copiadas ANTES de qualquer escrita** (o destino pode sobrepor o grupo de origem).
/// - Estreitamento "sequencial" põe o registrador `k` no bloco `k` do destino; o `N` ("narrow and interleave") intercala.
/// - Ponto flutuante reusa {@link SveFloat} (`FPCR` lido uma vez, flags acumuladas no fim). `FPCR.AH` e `fpmr_access_check`
///   não são modelados (pendências nomeadas, como no SVE).
public final class SmeConstructiveOps {
    private static final int BYTES_PER_WORD = Long.BYTES;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final int ESZ_QUAD = 4;
    private static final int HALF_BYTES = 2;
    private static final int SINGLE_BYTES = 4;
    private static final int FP8_BYTE_MASK = 0xFF;
    private static final int INT32_BITS = 32;
    private static final int QUAD_BYTES = 16;
    private static final int BITS_PER_LONG_MINUS_ONE = Long.SIZE - 1;
    private static final int PAIR = 2;
    private static final int QUAD = 4;

    private SmeConstructiveOps() {
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    public static boolean execute(Aarch64Core core, SmeOp64.Constructive op) {
        boolean sharedWithSve = isSharedRshrn(op);
        boolean allowed = sharedWithSve ? SvePredicateOps.accessAllowed(core, op.instructionAddress())
                : core.smeStreamingEnabledCheck(op.instructionAddress());
        if (!allowed) {
            return true;
        }
        int vl = core.streamingModeEnabled() ? core.streamingVectorLengthBytes() : core.vectorLengthBytes();
        Aarch64ScalableRegisters regs = core.scalable();
        switch (op.op()) {
            case ZIP, UZP -> permute(regs, op, vl);
            case SQCVT, UQCVT, SQCVTU, SQCVTN, UQCVTN, SQCVTUN, SQRSHR, UQRSHR, SQRSHRU, SQRSHRN, UQRSHRN,
                    SQRSHRUN -> narrowInteger(regs, op, vl);
            case SUNPK, UUNPK -> unpack(regs, op, vl);
            case FCLAMP, SCLAMP, UCLAMP -> clamp(core, regs, op, vl);
            case SEL -> select(core, regs, op, vl);
            case F1CVT, F2CVT, F1CVTL, F2CVTL, BF1CVT, BF2CVT, BF1CVTL, BF2CVTL -> widenFp8(core, regs, op, vl);
            case FCVT_BH, FCVT_BS, FCVTN_BS -> narrowFp8(core, regs, op, vl);
            default -> floatingPoint(core, regs, op, vl);
        }
        return false;
    }

    private static boolean isSharedRshrn(SmeOp64.Constructive op) {
        return op.esz() == ESZ_SINGLE && op.sources() == PAIR && (op.op() == SmeOp64.Constructive.Op.SQRSHRN
                || op.op() == SmeOp64.Constructive.Op.UQRSHRN || op.op() == SmeOp64.Constructive.Op.SQRSHRUN);
    }

    // ── byte helpers ────────────────────────────────────────────────────────────────────────────

    private static byte[] read(Aarch64ScalableRegisters regs, int register, int vl) {
        byte[] bytes = new byte[vl];
        for (int i = 0; i < vl; i++) {
            long word = regs.zWord(register, i / BYTES_PER_WORD);
            bytes[i] = (byte) (word >>> (Byte.SIZE * (i % BYTES_PER_WORD)));
        }
        return bytes;
    }

    private static void write(Aarch64ScalableRegisters regs, int register, byte[] bytes) {
        for (int w = 0; w < bytes.length / BYTES_PER_WORD; w++) {
            long word = 0;
            for (int b = 0; b < BYTES_PER_WORD; b++) {
                word |= (bytes[w * BYTES_PER_WORD + b] & 0xFFL) << (Byte.SIZE * b);
            }
            regs.setZWord(register, w, word);
        }
    }

    private static byte[][] readGroup(Aarch64ScalableRegisters regs, int first, int count, int vl) {
        byte[][] group = new byte[count][];
        for (int k = 0; k < count; k++) {
            group[k] = read(regs, first + k, vl);
        }
        return group;
    }

    private static void writeGroup(Aarch64ScalableRegisters regs, int first, byte[][] group) {
        for (int k = 0; k < group.length; k++) {
            write(regs, first + k, group[k]);
        }
    }

    /// Elemento `index` de `size` bytes (little-endian), sem extensão.
    private static long get(byte[] bytes, int index, int size) {
        long value = 0;
        for (int b = 0; b < size; b++) {
            value |= (bytes[index * size + b] & 0xFFL) << (Byte.SIZE * b);
        }
        return value;
    }

    private static void put(byte[] bytes, int index, int size, long value) {
        for (int b = 0; b < size; b++) {
            bytes[index * size + b] = (byte) (value >>> (Byte.SIZE * b));
        }
    }

    private static long signExtend(long value, int size) {
        int shift = Long.SIZE - Byte.SIZE * size;
        return (value << shift) >> shift;
    }

    // ── ZIP / UZP ───────────────────────────────────────────────────────────────────────────────

    private static void permute(Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        int elementBytes = esz4Bytes(op.esz());
        if (vl < op.sources() * elementBytes) {
            throw new Aarch64UndefinedInstructionException();
        }
        boolean zip = op.op() == SmeOp64.Constructive.Op.ZIP;
        int n = op.sources();
        byte[][] source = n == PAIR ? new byte[][] {read(regs, op.zn(), vl), read(regs, op.zm(), vl)}
                : readGroup(regs, op.zn(), n, vl);
        byte[][] dest = new byte[n][vl];
        int chunks = vl / (n * elementBytes);
        for (int r = 0; r < n; r++) {
            int base = r * chunks;
            for (int c = 0; c < chunks; c++) {
                for (int j = 0; j < n; j++) {
                    if (zip) {
                        copy(source[j], base + c, dest[r], n * c + j, elementBytes);
                    } else {
                        copy(source[r], n * c + j, dest[j], base + c, elementBytes);
                    }
                }
            }
        }
        writeGroup(regs, op.zd(), dest);
    }

    private static int esz4Bytes(int esz) {
        return esz == ESZ_QUAD ? QUAD_BYTES : 1 << esz;
    }

    private static void copy(byte[] from, int fromIndex, byte[] to, int toIndex, int size) {
        System.arraycopy(from, fromIndex * size, to, toIndex * size, size);
    }

    // ── estreitamento inteiro ───────────────────────────────────────────────────────────────────

    private static void narrowInteger(Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        int wideBytes = 1 << op.esz();
        int narrowEsz = op.esz() - op.sources() / PAIR;
        int narrowBytes = 1 << narrowEsz;
        int n = vl / wideBytes;
        boolean interleave = switch (op.op()) {
            case SQCVTN, UQCVTN, SQCVTUN, SQRSHRN, UQRSHRN, SQRSHRUN -> true;
            default -> false;
        };
        boolean unsignedInput = switch (op.op()) {
            case UQCVT, UQCVTN, UQRSHR, UQRSHRN -> true;
            default -> false;
        };
        boolean unsignedOutput = switch (op.op()) {
            case UQCVT, UQCVTN, UQRSHR, UQRSHRN, SQCVTU, SQCVTUN, SQRSHRU, SQRSHRUN -> true;
            default -> false;
        };
        byte[][] source = readGroup(regs, op.zn(), op.sources(), vl);
        byte[] dest = new byte[vl];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < op.sources(); k++) {
                long raw = get(source[k], i, wideBytes);
                long value = unsignedInput ? raw : signExtend(raw, wideBytes);
                if (op.shift() > 0) {
                    value = unsignedInput ? urshr(value, op.shift()) : srshr(value, op.shift());
                }
                long narrow = unsignedOutput ? saturateToUnsigned(value, unsignedInput, narrowBytes)
                        : saturateToSigned(value, narrowBytes);
                put(dest, interleave ? op.sources() * i + k : k * n + i, narrowBytes, narrow);
            }
        }
        write(regs, op.zd(), dest);
    }

    private static long srshr(long x, int shift) {
        if (shift >= Long.SIZE) {
            return ((x >> BITS_PER_LONG_MINUS_ONE) & 1L) + (x < 0 ? -1L : 0L);
        }
        return (x >> shift) + ((x >> (shift - 1)) & 1L);
    }

    private static long urshr(long x, int shift) {
        if (shift >= Long.SIZE) {
            return (x >>> BITS_PER_LONG_MINUS_ONE) & 1L;
        }
        return (x >>> shift) + ((x >>> (shift - 1)) & 1L);
    }

    private static long saturateToSigned(long value, int narrowBytes) {
        long max = (1L << (Byte.SIZE * narrowBytes - 1)) - 1L;
        return Math.max(-max - 1L, Math.min(max, value));
    }

    /// `value` é sem sinal quando `unsignedInput` (64 bits de magnitude); com sinal, negativo vira `0`.
    private static long saturateToUnsigned(long value, boolean unsignedInput, int narrowBytes) {
        long max = (1L << (Byte.SIZE * narrowBytes)) - 1L;
        if (!unsignedInput && value < 0) {
            return 0L;
        }
        return Long.compareUnsigned(value, max) > 0 ? max : value;
    }

    // ── alargamento inteiro ─────────────────────────────────────────────────────────────────────

    private static void unpack(Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        int wideBytes = 1 << op.esz();
        int narrowBytes = wideBytes / PAIR;
        int n = vl / wideBytes;
        boolean signed = op.op() == SmeOp64.Constructive.Op.SUNPK;
        byte[][] source = readGroup(regs, op.zn(), op.sources(), vl);
        byte[][] dest = new byte[op.destinations()][vl];
        for (int r = 0; r < op.sources(); r++) {
            for (int i = 0; i < PAIR; i++) {
                for (int e = 0; e < n; e++) {
                    long raw = get(source[r], i * n + e, narrowBytes);
                    put(dest[PAIR * r + i], e, wideBytes, signed ? signExtend(raw, narrowBytes) : raw);
                }
            }
        }
        writeGroup(regs, op.zd(), dest);
    }

    // ── clamp / sel ─────────────────────────────────────────────────────────────────────────────

    private static void clamp(Aarch64Core core, Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        int bytes = 1 << op.esz();
        int elements = vl / bytes;
        byte[] lower = read(regs, op.zn(), vl);
        byte[] upper = read(regs, op.zm(), vl);
        byte[][] dest = readGroup(regs, op.zd(), op.destinations(), vl);
        SveFloat.Env env = op.op() == SmeOp64.Constructive.Op.FCLAMP ? SveFloat.Env.of(core, op.esz()) : null;
        boolean unsigned = op.op() == SmeOp64.Constructive.Op.UCLAMP;
        for (int e = 0; e < elements; e++) {
            long n = get(lower, e, bytes);
            long m = get(upper, e, bytes);
            for (byte[] d : dest) {
                long current = get(d, e, bytes);
                long result;
                if (env != null) {
                    result = SveFloat.maxMinNumber(SveFloat.maxMinNumber(n, current, true, env), m, false, env);
                } else if (unsigned) {
                    long high = Long.compareUnsigned(current, n) >= 0 ? current : n;
                    result = Long.compareUnsigned(high, m) <= 0 ? high : m;
                } else {
                    result = Math.min(Math.max(signExtend(current, bytes), signExtend(n, bytes)),
                            signExtend(m, bytes));
                }
                put(d, e, bytes, result);
            }
        }
        if (env != null) {
            env.commit(core);
        }
        writeGroup(regs, op.zd(), dest);
    }

    private static void select(Aarch64Core core, Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        int bytes = 1 << op.esz();
        int elements = vl / bytes;
        SveCounterOps.Counter counter = SveCounterOps.Counter.decode(regs.pWord(op.pg(), 0), vl, op.esz());
        byte[][] first = readGroup(regs, op.zn(), op.destinations(), vl);
        byte[][] second = readGroup(regs, op.zm(), op.destinations(), vl);
        byte[][] dest = new byte[op.destinations()][vl];
        for (int r = 0; r < op.destinations(); r++) {
            for (int e = 0; e < elements; e++) {
                boolean active = counter.active(r * elements + e);
                copy(active ? first[r] : second[r], e, dest[r], e, bytes);
            }
        }
        writeGroup(regs, op.zd(), dest);
    }

    // ── FP8 ─────────────────────────────────────────────────────────────────────────────────────

    private static void widenFp8(Aarch64Core core, Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        SmeOp64.Constructive.Op kind = op.op();
        boolean stream2 = kind == SmeOp64.Constructive.Op.F2CVT || kind == SmeOp64.Constructive.Op.F2CVTL
                || kind == SmeOp64.Constructive.Op.BF2CVT || kind == SmeOp64.Constructive.Op.BF2CVTL;
        boolean bfloat = kind == SmeOp64.Constructive.Op.BF1CVT || kind == SmeOp64.Constructive.Op.BF2CVT
                || kind == SmeOp64.Constructive.Op.BF1CVTL || kind == SmeOp64.Constructive.Op.BF2CVTL;
        boolean interleaved = kind == SmeOp64.Constructive.Op.F1CVTL || kind == SmeOp64.Constructive.Op.F2CVTL
                || kind == SmeOp64.Constructive.Op.BF1CVTL || kind == SmeOp64.Constructive.Op.BF2CVTL;
        boolean e4m3 = (stream2 ? core.fp8SourceFormat2() : core.fp8SourceFormat1()) == Aarch64Fp8Format.E4M3;
        int scale = bfloat ? (stream2 ? core.fp8WidenScale2ForBFloat16() : core.fp8WidenScaleForBFloat16())
                : (stream2 ? core.fp8WidenScale2() : core.fp8WidenScale());
        byte[] source = read(regs, op.zn(), vl);
        byte[][] dest = new byte[PAIR][vl];
        int elements = vl / HALF_BYTES;
        for (int r = 0; r < PAIR; r++) {
            for (int i = 0; i < elements; i++) {
                int index = interleaved ? PAIR * i + r : r * elements + i;
                float value = Math.scalb(AdvSimdLanes.fp8ToFloat((int) get(source, index, 1) & FP8_BYTE_MASK, e4m3),
                        -scale);
                put(dest[r], i, HALF_BYTES, bfloat ? AdvSimdLanes.bf16Bits(value) : AdvSimdLanes.halfBits(value));
            }
        }
        writeGroup(regs, op.zd(), dest);
    }

    private static void narrowFp8(Aarch64Core core, Aarch64ScalableRegisters regs, SmeOp64.Constructive op, int vl) {
        boolean e4m3 = core.fp8DestinationFormat() == Aarch64Fp8Format.E4M3;
        int scale = core.fp8NarrowScale();
        boolean saturate = core.fp8OverflowSaturatesToMaxNormal();
        int sourceBytes = 1 << op.esz();
        int n = vl / sourceBytes;
        boolean interleave = op.op() == SmeOp64.Constructive.Op.FCVTN_BS;
        byte[][] source = readGroup(regs, op.zn(), op.sources(), vl);
        byte[] dest = new byte[vl];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < op.sources(); k++) {
                long bits = get(source[k], i, sourceBytes);
                float value = op.esz() == ESZ_HALF ? AdvSimdLanes.halfToFloat(bits) : Float.intBitsToFloat((int) bits);
                put(dest, interleave ? op.sources() * i + k : k * n + i, 1,
                        AdvSimdLanes.floatToFp8(Math.scalb(value, scale), e4m3, saturate));
            }
        }
        write(regs, op.zd(), dest);
    }

    // ── conversões FP / inteiro↔FP / arredondamento ─────────────────────────────────────────────

    private static void floatingPoint(Aarch64Core core, Aarch64ScalableRegisters regs, SmeOp64.Constructive op,
            int vl) {
        SmeOp64.Constructive.Op kind = op.op();
        byte[][] source = readGroup(regs, op.zn(), op.sources(), vl);
        byte[][] dest = new byte[op.destinations()][vl];
        SveFloat.Env result;
        switch (kind) {
            case BFCVT, BFCVTN, FCVT_N, FCVTN -> {
                boolean bfloat = kind == SmeOp64.Constructive.Op.BFCVT || kind == SmeOp64.Constructive.Op.BFCVTN;
                SveFloat.Env from = SveFloat.Env.ofConversionSource(core, ESZ_SINGLE);
                result = SveFloat.Env.of(core, bfloat ? SveFloat.ESZ_BFLOAT16 : SveFloat.ESZ_HALF);
                boolean interleave = kind == SmeOp64.Constructive.Op.BFCVTN
                        || kind == SmeOp64.Constructive.Op.FCVTN;
                int n = vl / SINGLE_BYTES;
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < PAIR; k++) {
                        put(dest[0], interleave ? PAIR * i + k : k * n + i, HALF_BYTES,
                                SveFloat.convertPrecision(get(source[k], i, SINGLE_BYTES), from, result, false));
                    }
                }
            }
            case FCVT_W, FCVTL -> {
                SveFloat.Env from = SveFloat.Env.ofConversionSource(core, ESZ_HALF);
                result = SveFloat.Env.of(core, ESZ_SINGLE);
                int n = vl / SINGLE_BYTES;
                for (int r = 0; r < PAIR; r++) {
                    for (int i = 0; i < n; i++) {
                        int index = kind == SmeOp64.Constructive.Op.FCVTL ? PAIR * i + r : r * n + i;
                        put(dest[r], i, SINGLE_BYTES,
                                SveFloat.convertPrecision(get(source[0], index, HALF_BYTES), from, result, false));
                    }
                }
            }
            default -> {
                result = SveFloat.Env.of(core, ESZ_SINGLE);
                switch (kind) {
                    case FRINTN -> result.rmode = SveFloat.RMODE_NEAREST;
                    case FRINTP -> result.rmode = SveFloat.RMODE_PLUS_INFINITY;
                    case FRINTM -> result.rmode = SveFloat.RMODE_MINUS_INFINITY;
                    case FRINTA -> result.rmode = SveFloat.RMODE_TIE_AWAY;
                    default -> {
                        // FCVTZ*/SCVTF/UCVTF: `FPCR.RMode` como veio.
                    }
                }
                int n = vl / SINGLE_BYTES;
                for (int k = 0; k < op.sources(); k++) {
                    for (int i = 0; i < n; i++) {
                        long bits = get(source[k], i, SINGLE_BYTES);
                        long out = switch (kind) {
                            case FCVTZS -> SveFloat.toInteger(bits, result, INT32_BITS, false);
                            case FCVTZU -> SveFloat.toInteger(bits, result, INT32_BITS, true);
                            case SCVTF -> SveFloat.fromInteger(signExtend(bits, SINGLE_BYTES), false, result);
                            case UCVTF -> SveFloat.fromInteger(bits, true, result);
                            default -> SveFloat.roundToIntegral(bits, result, result.rmode, false);
                        };
                        put(dest[k], i, SINGLE_BYTES, out);
                    }
                }
            }
        }
        result.commit(core);
        writeGroup(regs, op.zd(), dest);
    }
}
