package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// SME2 multi-vetor "multiple and single, array vectors" (B18.9): `ZA[W<rv> + off, VGx<n>] (+)= f(Zn…, Zm)` — o laço
/// interno de um `gemm` SME2, sem tile nem predicado (`do_azz_n1`/`do_azz_acc*`/`do_azz_fp` do `translate-sme.c`).
///
/// - Exige modo streaming **e** `ZA` habilitado (`sme_smza_enabled_check`); o comprimento é o `SVL`, nunca o `VL`.
/// - **Endereçamento**: o membro `r` escreve os `vectorsPerMember` vetores `ZA[base + r × (SVL/n) + i]` — NÃO
///   consecutivos entre membros (`vstride = SVL/n`). `Zn` do membro `r` é `(zn + r) MOD 32` (dá a volta); `Zm` é
///   único.
/// - `ADD`/`SUB` ESCREVEM `Zn ± Zm`; as demais ACUMULAM sobre o conteúdo anterior do vetor de `ZA`.
/// - **A aritmética por lane é a da `FMOPA`/`SMOPA` de B18.5, chamada e não copiada** ({@link
///   SmeOuterProductOps#combineUnpredicated}): o produto escalar de `n` vias é o MESMO núcleo dos outer products de
///   2/4 vias, só que sem predicado. Ponto flutuante usa o ambiente `FPST_ZA` ({@link SveFloat.Env#ofZa}): `DN`
///   sempre ligado e flags NUNCA acumuladas em `FPSR`. `FPCR.AH` e o acesso a `FPMR` (`fpmr_access_check`) não são
///   modelados (pendências nomeadas, como nos outer products).
final class SmeArrayMultiVectorOps {
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final int Z_REGISTER_COUNT = 32;
    private static final int HALF_SIGN = 0x8000;
    private static final int HALF_EXPONENT_MASK = 0x7C00;
    private static final int HALF_FRACTION_MASK = 0x03FF;
    private static final int BFLOAT16_SHIFT = 16;
    private static final int SINGLE_SIGN_SHIFT = 31;
    private static final long UINT32_MASK = 0xFFFF_FFFFL;
    private static final int HALVES_PER_WORD = 2;
    private static final int LANES_PER_FP8_WORD = 4;

    private SmeArrayMultiVectorOps() {
    }

    /// Ambientes e formatos de UMA instrução (o `FPCR`/`FPMR` são lidos uma única vez).
    private static final class Context {
        SveFloat.Env fma;
        SmeOuterProductOps.Context dot;
        Ir64Op.SmeOuterProduct.Op dotKind;
        boolean flushHalfInputs;
        boolean nE4m3;
        boolean mE4m3;
        boolean osm;
        int lscale;
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, Ir64Op.SmeArrayMultiVector op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        Ir64Op.SmeArrayMultiVector.Op kind = op.op();
        int esz = kind.accumulatorEsz();
        int elements = core.streamingVectorLengthBytes() >>> esz;
        int rowsPerMember = core.streamingVectorLengthBytes() / op.count();
        int perMember = kind.vectorsPerMember();
        int base = Aarch64MatrixTileAddressing.resolveSliceIndex(core.x(op.registerIndex()), op.off(), 0,
                rowsPerMember, perMember);
        Context context = contextFor(core, kind);
        for (int member = 0; member < op.count(); member++) {
            int zn = (op.zn() + member) % Z_REGISTER_COUNT;
            for (int select = 0; select < perMember; select++) {
                int row = base + member * rowsPerMember + select;
                for (int e = 0; e < elements; e++) {
                    long accumulator = SmeMovaOps.zaElement(matrix, row, e << esz, esz);
                    long result = lane(kind, context, regs, zn, op.zm(), e, select, accumulator);
                    SmeMovaOps.setZaElement(matrix, row, e << esz, esz, result);
                }
            }
        }
        return false;
    }

    private static Context contextFor(Aarch64Core core, Ir64Op.SmeArrayMultiVector.Op kind) {
        Context context = new Context();
        switch (kind) {
            case FMLAL, FMLSL -> {
                context.fma = SveFloat.Env.ofZa(core, ESZ_SINGLE);
                context.flushHalfInputs = SveFloat.Env.ofZa(core, ESZ_HALF).flushToZero;
            }
            case BFMLAL, BFMLSL -> context.fma = SveFloat.Env.ofZa(core, ESZ_SINGLE);
            case FMLA_H, FMLS_H -> context.fma = SveFloat.Env.ofZa(core, ESZ_HALF);
            case FMLA_S, FMLS_S -> context.fma = SveFloat.Env.ofZa(core, ESZ_SINGLE);
            case FMLA_D, FMLS_D -> context.fma = SveFloat.Env.ofZa(core, ESZ_DOUBLE);
            case BFMLA, BFMLS -> context.fma = SveFloat.Env.ofZa(core, SveFloat.ESZ_BFLOAT16);
            case FMLALL_B, FMLAL_HB -> {
                context.nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
                context.mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
                context.osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
                context.lscale = kind == Ir64Op.SmeArrayMultiVector.Op.FMLALL_B ? core.fp8MultiplyDownscale()
                        : core.fp8WidenScale();
            }
            default -> {
                context.dotKind = dotKind(kind);
                if (context.dotKind != null) {
                    context.dot = SmeOuterProductOps.contextFor(core, context.dotKind);
                }
            }
        }
        return context;
    }

    /// A forma de outer product que tem a MESMA aritmética por lane do produto escalar `kind` (`null` = não é um
    /// produto escalar).
    private static Ir64Op.SmeOuterProduct.Op dotKind(Ir64Op.SmeArrayMultiVector.Op kind) {
        return switch (kind) {
            case FDOT -> Ir64Op.SmeOuterProduct.Op.FMOPA_W_H;
            case BFDOT -> Ir64Op.SmeOuterProduct.Op.BFMOPA_W;
            case USDOT -> Ir64Op.SmeOuterProduct.Op.USMOPA_S;
            case SUDOT -> Ir64Op.SmeOuterProduct.Op.SUMOPA_S;
            case SDOT_4B -> Ir64Op.SmeOuterProduct.Op.SMOPA_S;
            case UDOT_4B -> Ir64Op.SmeOuterProduct.Op.UMOPA_S;
            case SDOT_4H -> Ir64Op.SmeOuterProduct.Op.SMOPA_D;
            case UDOT_4H -> Ir64Op.SmeOuterProduct.Op.UMOPA_D;
            case SDOT_2H -> Ir64Op.SmeOuterProduct.Op.SMOPA2_S;
            case UDOT_2H -> Ir64Op.SmeOuterProduct.Op.UMOPA2_S;
            case FDOT_SB -> Ir64Op.SmeOuterProduct.Op.FMOPA_SB;
            case FDOT_HB -> Ir64Op.SmeOuterProduct.Op.FMOPA_HB;
            default -> null;
        };
    }

    /// Novo valor do elemento `e` do vetor de `ZA`. `select` é o índice do vetor DENTRO do membro (a metade/o byte
    /// da lane de origem nas formas widening).
    private static long lane(Ir64Op.SmeArrayMultiVector.Op kind, Context context, Aarch64ScalableRegisters regs,
            int zn, int zm, int e, int select, long accumulator) {
        int esz = kind.accumulatorEsz();
        long mask = SveIntegerOps.elementMask(esz);
        return switch (kind) {
            case ADD_S, ADD_D -> (SveIntegerOps.get(regs, zn, e, esz) + SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case SUB_S, SUB_D -> (SveIntegerOps.get(regs, zn, e, esz) - SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case FMLAL, FMLSL -> SveFloat.fusedMultiplyAdd(accumulator,
                    halfToSingle(SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_HALF),
                            kind == Ir64Op.SmeArrayMultiVector.Op.FMLSL, context.flushHalfInputs),
                    halfToSingle(SveIntegerOps.get(regs, zm, HALVES_PER_WORD * e + select, ESZ_HALF), false,
                            context.flushHalfInputs),
                    0, context.fma);
            case BFMLAL, BFMLSL -> SveFloat.fusedMultiplyAdd(accumulator,
                    ((SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_HALF)
                            ^ (kind == Ir64Op.SmeArrayMultiVector.Op.BFMLSL ? HALF_SIGN : 0)) << BFLOAT16_SHIFT)
                            & UINT32_MASK,
                    SveIntegerOps.get(regs, zm, HALVES_PER_WORD * e + select, ESZ_HALF) << BFLOAT16_SHIFT, 0,
                    context.fma);
            case SMLAL, SMLSL, UMLAL, UMLSL -> {
                boolean signed = kind == Ir64Op.SmeArrayMultiVector.Op.SMLAL
                        || kind == Ir64Op.SmeArrayMultiVector.Op.SMLSL;
                long product = widenedHalf(regs, zn, e, select, signed) * widenedHalf(regs, zm, e, select, signed);
                boolean subtract = kind == Ir64Op.SmeArrayMultiVector.Op.SMLSL
                        || kind == Ir64Op.SmeArrayMultiVector.Op.UMLSL;
                yield (subtract ? accumulator - product : accumulator + product) & mask;
            }
            case SMLALL_S, SMLSLL_S, UMLALL_S, UMLSLL_S, USMLALL, SUMLALL -> {
                boolean nSigned = kind == Ir64Op.SmeArrayMultiVector.Op.SMLALL_S
                        || kind == Ir64Op.SmeArrayMultiVector.Op.SMLSLL_S
                        || kind == Ir64Op.SmeArrayMultiVector.Op.SUMLALL;
                boolean mSigned = kind == Ir64Op.SmeArrayMultiVector.Op.SMLALL_S
                        || kind == Ir64Op.SmeArrayMultiVector.Op.SMLSLL_S
                        || kind == Ir64Op.SmeArrayMultiVector.Op.USMLALL;
                long product = quarter(regs, zn, e, select, ESZ_BYTE, nSigned) * quarter(regs, zm, e, select,
                        ESZ_BYTE, mSigned);
                boolean subtract = kind == Ir64Op.SmeArrayMultiVector.Op.SMLSLL_S
                        || kind == Ir64Op.SmeArrayMultiVector.Op.UMLSLL_S;
                yield (subtract ? accumulator - product : accumulator + product) & mask;
            }
            case SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D -> {
                boolean signed = kind == Ir64Op.SmeArrayMultiVector.Op.SMLALL_D
                        || kind == Ir64Op.SmeArrayMultiVector.Op.SMLSLL_D;
                long product = quarter(regs, zn, e, select, ESZ_HALF, signed) * quarter(regs, zm, e, select,
                        ESZ_HALF, signed);
                boolean subtract = kind == Ir64Op.SmeArrayMultiVector.Op.SMLSLL_D
                        || kind == Ir64Op.SmeArrayMultiVector.Op.UMLSLL_D;
                yield subtract ? accumulator - product : accumulator + product;
            }
            case BFMLA, FMLA_H, FMLA_S, FMLA_D -> SveFloat.fusedMultiplyAdd(accumulator,
                    SveIntegerOps.get(regs, zn, e, esz), SveIntegerOps.get(regs, zm, e, esz), 0, context.fma);
            case BFMLS, FMLS_H, FMLS_S, FMLS_D -> SveFloat.fusedMultiplyAdd(accumulator,
                    SveIntegerOps.get(regs, zn, e, esz) ^ (1L << ((Byte.SIZE << esz) - 1)),
                    SveIntegerOps.get(regs, zm, e, esz), 0, context.fma);
            case FMLALL_B -> AdvSimdLanes.fp8FusedMultiplyAdd(
                    (int) SveIntegerOps.get(regs, zn, LANES_PER_FP8_WORD * e + select, ESZ_BYTE), context.nE4m3,
                    (int) SveIntegerOps.get(regs, zm, LANES_PER_FP8_WORD * e + select, ESZ_BYTE), context.mE4m3,
                    context.lscale, context.osm, accumulator, true);
            case FMLAL_HB -> AdvSimdLanes.fp8FusedMultiplyAdd(
                    (int) SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_BYTE), context.nE4m3,
                    (int) SveIntegerOps.get(regs, zm, HALVES_PER_WORD * e + select, ESZ_BYTE), context.mE4m3,
                    context.lscale, context.osm, accumulator, false);
            default -> SmeOuterProductOps.combineUnpredicated(context.dotKind, context.dot, false,
                    SveIntegerOps.get(regs, zn, e, esz), SveIntegerOps.get(regs, zm, e, esz), accumulator);
        };
    }

    /// `float16_to_float32_by_bits(x ^ negx, fz16)`: exato, e com `FZ16` ligado um denormal vira zero COM sinal.
    private static long halfToSingle(long half, boolean negate, boolean flushDenormals) {
        long bits = negate ? half ^ HALF_SIGN : half;
        if (flushDenormals && (bits & HALF_EXPONENT_MASK) == 0 && (bits & HALF_FRACTION_MASK) != 0) {
            return (bits & HALF_SIGN) != 0 ? 1L << SINGLE_SIGN_SHIFT : 0L;
        }
        return Float.floatToRawIntBits(AdvSimdLanes.halfToFloat(bits)) & UINT32_MASK;
    }

    /// Halfword `2e + select` (a metade BAIXA ou ALTA da lane de 32 bits), com sinal ou não.
    private static long widenedHalf(Aarch64ScalableRegisters regs, int register, int e, int select, boolean signed) {
        long value = SveIntegerOps.get(regs, register, HALVES_PER_WORD * e + select, ESZ_HALF);
        return signed ? SveIntegerOps.signExtend(value, ESZ_HALF) : value;
    }

    /// Elemento `4e + select` (byte nas formas `_s`, halfword nas `_d`) — o `LL` dos `*MLALL`.
    private static long quarter(Aarch64ScalableRegisters regs, int register, int e, int select, int esz,
            boolean signed) {
        long value = SveIntegerOps.get(regs, register, LANES_PER_FP8_WORD * e + select, esz);
        return signed ? SveIntegerOps.signExtend(value, esz) : value;
    }
}
