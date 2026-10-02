package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64Fp8Format;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;

/// SME2 multi-vetor "multiple and single, array vectors" (B18.9) e "multiple, array vectors" (B18.10, o `Zm` também é
/// um grupo e o membro `r` usa `Z(zm + r)`; `FADD`/`FSUB`/`BFADD`/`BFSUB` fazem `ZA ±= Zm`, sem `Zn`): `ZA[W<rv> + off, VGx<n>] (+)= f(Zn…, Zm)` — o laço
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
    private static final int BYTES_PER_SEGMENT = 16;
    private static final int FP8_PAIR = 2;

    private SmeArrayMultiVectorOps() {
    }

    /// Ambientes e formatos de UMA instrução (o `FPCR`/`FPMR` são lidos uma única vez).
    private static final class Context {
        SveFloat.Env fma;
        SmeOuterProductOps.Context dot;
        SmeOp64.OuterProduct.Op dotKind;
        boolean flushHalfInputs;
        boolean nE4m3;
        boolean mE4m3;
        boolean osm;
        int lscale;
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, SmeOp64.ArrayMultiVector op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        SmeOp64.ArrayMultiVector.Op kind = op.op();
        int esz = kind.accumulatorEsz();
        int elements = core.streamingVectorLengthBytes() >>> esz;
        int rowsPerMember = core.streamingVectorLengthBytes() / op.count();
        int perMember = kind.vectorsPerMember();
        int base = Aarch64MatrixTileAddressing.resolveSliceIndex(core.x(op.registerIndex()), op.off(), 0,
                rowsPerMember, perMember);
        Context context = contextFor(core, kind);
        if (kind.vertical()) {
            executeVertical(core, op, context, base, rowsPerMember);
            return false;
        }
        for (int member = 0; member < op.count(); member++) {
            int zn = (op.zn() + member) % Z_REGISTER_COUNT;
            int zm = op.multipleZm() ? op.zm() + member : op.zm();
            for (int select = 0; select < perMember; select++) {
                int row = base + member * rowsPerMember + select;
                for (int e = 0; e < elements; e++) {
                    long accumulator = SmeMovaOps.zaElement(matrix, row, e << esz, esz);
                    long result = lane(kind, context, regs, zn, zm, e, select, op.index(), accumulator);
                    SmeMovaOps.setZaElement(matrix, row, e << esz, esz, result);
                }
            }
        }
        return false;
    }

    private static Context contextFor(Aarch64Core core, SmeOp64.ArrayMultiVector.Op kind) {
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
            case BFMLA, BFMLS, BFADD, BFSUB -> context.fma = SveFloat.Env.ofZa(core, SveFloat.ESZ_BFLOAT16);
            case FADD_H, FSUB_H -> context.fma = SveFloat.Env.ofZa(core, ESZ_HALF);
            case FADD_S, FSUB_S -> context.fma = SveFloat.Env.ofZa(core, ESZ_SINGLE);
            case FADD_D, FSUB_D -> context.fma = SveFloat.Env.ofZa(core, ESZ_DOUBLE);
            case FMLALL_B, FMLAL_HB, FVDOTB, FVDOTT, FVDOT_HB -> {
                context.nE4m3 = core.fp8SourceFormat1() == Aarch64Fp8Format.E4M3;
                context.mE4m3 = core.fp8SourceFormat2() == Aarch64Fp8Format.E4M3;
                context.osm = core.fp8OverflowSaturatesToMaxNormalOnMultiply();
                boolean singleDestination = kind == SmeOp64.ArrayMultiVector.Op.FMLALL_B
                        || kind == SmeOp64.ArrayMultiVector.Op.FVDOTB || kind == SmeOp64.ArrayMultiVector.Op.FVDOTT;
                context.lscale = singleDestination ? core.fp8MultiplyDownscale() : core.fp8WidenScale();
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
    private static SmeOp64.OuterProduct.Op dotKind(SmeOp64.ArrayMultiVector.Op kind) {
        return switch (kind) {
            case FDOT, FVDOT_SH -> SmeOp64.OuterProduct.Op.FMOPA_W_H;
            case BFDOT, BFVDOT -> SmeOp64.OuterProduct.Op.BFMOPA_W;
            case USDOT -> SmeOp64.OuterProduct.Op.USMOPA_S;
            case SUDOT -> SmeOp64.OuterProduct.Op.SUMOPA_S;
            case SDOT_4B -> SmeOp64.OuterProduct.Op.SMOPA_S;
            case UDOT_4B -> SmeOp64.OuterProduct.Op.UMOPA_S;
            case SDOT_4H -> SmeOp64.OuterProduct.Op.SMOPA_D;
            case UDOT_4H -> SmeOp64.OuterProduct.Op.UMOPA_D;
            case SDOT_2H -> SmeOp64.OuterProduct.Op.SMOPA2_S;
            case UDOT_2H -> SmeOp64.OuterProduct.Op.UMOPA2_S;
            case FDOT_SB -> SmeOp64.OuterProduct.Op.FMOPA_SB;
            case FDOT_HB -> SmeOp64.OuterProduct.Op.FMOPA_HB;
            default -> null;
        };
    }

    /// Novo valor do elemento `e` do vetor de `ZA`. `select` é o índice do vetor DENTRO do membro (a metade/o byte
    /// da lane de origem nas formas widening).
    private static long lane(SmeOp64.ArrayMultiVector.Op kind, Context context, Aarch64ScalableRegisters regs,
            int zn, int zm, int e, int select, int index, long accumulator) {
        int esz = kind.accumulatorEsz();
        long mask = SveIntegerOps.elementMask(esz);
        return switch (kind) {
            case ADD_S, ADD_D -> (SveIntegerOps.get(regs, zn, e, esz) + SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case SUB_S, SUB_D -> (SveIntegerOps.get(regs, zn, e, esz) - SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case FMLAL, FMLSL -> SveFloat.fusedMultiplyAdd(accumulator,
                    halfToSingle(SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_HALF),
                            kind == SmeOp64.ArrayMultiVector.Op.FMLSL, context.flushHalfInputs),
                    halfToSingle(SveIntegerOps.get(regs, zm, zmPosition(e, select, HALVES_PER_WORD, esz, index),
                            ESZ_HALF), false, context.flushHalfInputs),
                    0, context.fma);
            case BFMLAL, BFMLSL -> SveFloat.fusedMultiplyAdd(accumulator,
                    ((SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_HALF)
                            ^ (kind == SmeOp64.ArrayMultiVector.Op.BFMLSL ? HALF_SIGN : 0)) << BFLOAT16_SHIFT)
                            & UINT32_MASK,
                    SveIntegerOps.get(regs, zm, zmPosition(e, select, HALVES_PER_WORD, esz, index), ESZ_HALF)
                            << BFLOAT16_SHIFT, 0, context.fma);
            case SMLAL, SMLSL, UMLAL, UMLSL -> {
                boolean signed = kind == SmeOp64.ArrayMultiVector.Op.SMLAL
                        || kind == SmeOp64.ArrayMultiVector.Op.SMLSL;
                long product = widenedHalf(regs, zn, HALVES_PER_WORD * e + select, signed)
                        * widenedHalf(regs, zm, zmPosition(e, select, HALVES_PER_WORD, esz, index), signed);
                boolean subtract = kind == SmeOp64.ArrayMultiVector.Op.SMLSL
                        || kind == SmeOp64.ArrayMultiVector.Op.UMLSL;
                yield (subtract ? accumulator - product : accumulator + product) & mask;
            }
            case SMLALL_S, SMLSLL_S, UMLALL_S, UMLSLL_S, USMLALL, SUMLALL -> {
                boolean nSigned = kind == SmeOp64.ArrayMultiVector.Op.SMLALL_S
                        || kind == SmeOp64.ArrayMultiVector.Op.SMLSLL_S
                        || kind == SmeOp64.ArrayMultiVector.Op.SUMLALL;
                boolean mSigned = kind == SmeOp64.ArrayMultiVector.Op.SMLALL_S
                        || kind == SmeOp64.ArrayMultiVector.Op.SMLSLL_S
                        || kind == SmeOp64.ArrayMultiVector.Op.USMLALL;
                long product = quarter(regs, zn, LANES_PER_FP8_WORD * e + select, ESZ_BYTE, nSigned)
                        * quarter(regs, zm, zmPosition(e, select, LANES_PER_FP8_WORD, esz, index), ESZ_BYTE, mSigned);
                boolean subtract = kind == SmeOp64.ArrayMultiVector.Op.SMLSLL_S
                        || kind == SmeOp64.ArrayMultiVector.Op.UMLSLL_S;
                yield (subtract ? accumulator - product : accumulator + product) & mask;
            }
            case SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D -> {
                boolean signed = kind == SmeOp64.ArrayMultiVector.Op.SMLALL_D
                        || kind == SmeOp64.ArrayMultiVector.Op.SMLSLL_D;
                long product = quarter(regs, zn, LANES_PER_FP8_WORD * e + select, ESZ_HALF, signed)
                        * quarter(regs, zm, zmPosition(e, select, LANES_PER_FP8_WORD, esz, index), ESZ_HALF, signed);
                boolean subtract = kind == SmeOp64.ArrayMultiVector.Op.SMLSLL_D
                        || kind == SmeOp64.ArrayMultiVector.Op.UMLSLL_D;
                yield subtract ? accumulator - product : accumulator + product;
            }
            case BFMLA, FMLA_H, FMLA_S, FMLA_D -> SveFloat.fusedMultiplyAdd(accumulator,
                    SveIntegerOps.get(regs, zn, e, esz),
                    SveIntegerOps.get(regs, zm, zmPosition(e, 0, 1, esz, index), esz), 0, context.fma);
            case BFMLS, FMLS_H, FMLS_S, FMLS_D -> SveFloat.fusedMultiplyAdd(accumulator,
                    SveIntegerOps.get(regs, zn, e, esz) ^ (1L << ((Byte.SIZE << esz) - 1)),
                    SveIntegerOps.get(regs, zm, zmPosition(e, 0, 1, esz, index), esz), 0, context.fma);
            case ADD_AAZ_S, ADD_AAZ_D -> (accumulator + SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case SUB_AAZ_S, SUB_AAZ_D -> (accumulator - SveIntegerOps.get(regs, zm, e, esz)) & mask;
            case FADD_H, FADD_S, FADD_D, BFADD -> SveFloat.add(accumulator, SveIntegerOps.get(regs, zm, e, esz), false,
                    context.fma);
            case FSUB_H, FSUB_S, FSUB_D, BFSUB -> SveFloat.add(accumulator, SveIntegerOps.get(regs, zm, e, esz), true,
                    context.fma);
            case FMLALL_B -> AdvSimdLanes.fp8FusedMultiplyAdd(
                    (int) SveIntegerOps.get(regs, zn, LANES_PER_FP8_WORD * e + select, ESZ_BYTE), context.nE4m3,
                    (int) SveIntegerOps.get(regs, zm, zmPosition(e, select, LANES_PER_FP8_WORD, esz, index), ESZ_BYTE),
                    context.mE4m3, context.lscale, context.osm, accumulator, true);
            case FMLAL_HB -> AdvSimdLanes.fp8FusedMultiplyAdd(
                    (int) SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + select, ESZ_BYTE), context.nE4m3,
                    (int) SveIntegerOps.get(regs, zm, zmPosition(e, select, HALVES_PER_WORD, esz, index), ESZ_BYTE),
                    context.mE4m3, context.lscale, context.osm, accumulator, false);
            default -> SmeOuterProductOps.combineUnpredicated(context.dotKind, context.dot, false,
                    SveIntegerOps.get(regs, zn, e, esz),
                    SveIntegerOps.get(regs, zm, zmPosition(e, 0, 1, esz, index), esz), accumulator);
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

    /// Halfword `position` (a metade BAIXA ou ALTA da lane de 32 bits, ou o elemento indexado), com sinal ou não.
    private static long widenedHalf(Aarch64ScalableRegisters regs, int register, int position, boolean signed) {
        long value = SveIntegerOps.get(regs, register, position, ESZ_HALF);
        return signed ? SveIntegerOps.signExtend(value, ESZ_HALF) : value;
    }

    /// Elemento `position` (byte nas formas `_s`, halfword nas `_d`) — o `LL` dos `*MLALL`.
    private static long quarter(Aarch64ScalableRegisters regs, int register, int position, int esz, boolean signed) {
        long value = SveIntegerOps.get(regs, register, position, esz);
        return signed ? SveIntegerOps.signExtend(value, esz) : value;
    }

    /// Posição do elemento de `Zm` que a lane `e` do acumulador consome. Forma `_n1`/`_nn` (sem índice): o elemento
    /// `ratio*e + select`, no mesmo passo das lanes de `Zn`. Forma indexada (`_nx`, B18.11): o elemento `index` do
    /// SEGMENTO DE 128 BITS que contém a lane — `ratio * (e arredondado ao início do segmento) + index`, onde um
    /// segmento tem `16 >> accumulatorEsz` lanes de acumulador. Cada segmento usa o SEU elemento: com `SVL = 128` só há
    /// um segmento, e usar o elemento `index` do vetor inteiro passaria despercebido.
    private static int zmPosition(int e, int select, int ratio, int accumulatorEsz, int index) {
        if (index == SmeOp64.ArrayMultiVector.NOT_INDEXED) {
            return ratio * e + select;
        }
        int lanesPerSegment = BYTES_PER_SEGMENT >> accumulatorEsz;
        return ratio * (e & ~(lanesPerSegment - 1)) + index;
    }

    /// Dot VERTICAL (B18.11; `do_vdot_nx`/`do_vdot` do `translate-sme.c` e, para `FVDOTB`/`FVDOTT`/`FVDOT_hb`, o
    /// PSEUDOCÓDIGO do manual — o QEMU diverge neles): `count` vetores de `ZA` espaçados `SVL/count`; o vetor `member`
    /// consome o elemento `k*e + member` de CADA registrador do grupo `Zn..Zn+k-1` (`k` = nº de registradores de
    /// origem) contra o grupo de `k` elementos adjacentes de `Zm` no índice `segmento + index`.
    private static void executeVertical(Aarch64Core core, SmeOp64.ArrayMultiVector op, Context context, int base,
            int rowsPerMember) {
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        SmeOp64.ArrayMultiVector.Op kind = op.op();
        int esz = kind.accumulatorEsz();
        int elements = core.streamingVectorLengthBytes() >>> esz;
        int lanesPerSegment = BYTES_PER_SEGMENT >> esz;
        for (int member = 0; member < op.count(); member++) {
            int row = base + member * rowsPerMember;
            for (int e = 0; e < elements; e++) {
                int indexedGroup = (e & ~(lanesPerSegment - 1)) + op.index();
                long accumulator = SmeMovaOps.zaElement(matrix, row, e << esz, esz);
                long result = verticalLane(kind, context, regs, op, member, e, indexedGroup, accumulator);
                SmeMovaOps.setZaElement(matrix, row, e << esz, esz, result);
            }
        }
    }

    private static long verticalLane(SmeOp64.ArrayMultiVector.Op kind, Context context,
            Aarch64ScalableRegisters regs, SmeOp64.ArrayMultiVector op, int member, int e, int indexedGroup,
            long accumulator) {
        int zn = op.zn();
        return switch (kind) {
            case SVDOT_2H, UVDOT_2H, SVDOT_4H, UVDOT_4H, SVDOT_4B, UVDOT_4B, SUVDOT, USVDOT ->
                    integerVerticalDot(regs, op, member, e, indexedGroup, accumulator);
            case FVDOT_SH, BFVDOT -> SmeOuterProductOps.combineUnpredicated(context.dotKind, context.dot, false,
                    SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + member, ESZ_HALF)
                            | SveIntegerOps.get(regs, zn + 1, HALVES_PER_WORD * e + member, ESZ_HALF) << Short.SIZE,
                    SveIntegerOps.get(regs, op.zm(), indexedGroup, ESZ_SINGLE), accumulator);
            // `bits(16) op1 = {Zn+1[4e+r], Zn[4e+r]}`; `op2` = metade BAIXA (`B`) ou ALTA (`T`) do grupo de 32 bits.
            case FVDOTB, FVDOTT -> AdvSimdLanes.fp8DotProduct(
                    SveIntegerOps.get(regs, zn, LANES_PER_FP8_WORD * e + member, ESZ_BYTE)
                            | SveIntegerOps.get(regs, zn + 1, LANES_PER_FP8_WORD * e + member, ESZ_BYTE) << Byte.SIZE,
                    SveIntegerOps.get(regs, op.zm(),
                            HALVES_PER_WORD * indexedGroup + (kind == SmeOp64.ArrayMultiVector.Op.FVDOTT ? 1 : 0),
                            ESZ_HALF),
                    context.nE4m3, context.mE4m3, FP8_PAIR, context.lscale, context.osm, accumulator, true);
            default -> AdvSimdLanes.fp8DotProduct(
                    SveIntegerOps.get(regs, zn, HALVES_PER_WORD * e + member, ESZ_BYTE)
                            | SveIntegerOps.get(regs, zn + 1, HALVES_PER_WORD * e + member, ESZ_BYTE) << Byte.SIZE,
                    SveIntegerOps.get(regs, op.zm(), indexedGroup, ESZ_HALF), context.nE4m3, context.mE4m3, FP8_PAIR,
                    context.lscale, context.osm, accumulator, false);
        };
    }

    /// `SVDOT`/`UVDOT`/`SUVDOT`/`USVDOT` (`DO_VDOT_IDX`): `acc + Σ_i Zn+i[k*e + member] × Zm[k*indexedGroup + i]`, com `k`
    /// = nº de vetores do grupo (= `count`) e o elemento estreito de byte (`4b`) ou halfword (`2h`/`4h`).
    private static long integerVerticalDot(Aarch64ScalableRegisters regs, SmeOp64.ArrayMultiVector op, int member,
            int e, int indexedGroup, long accumulator) {
        SmeOp64.ArrayMultiVector.Op kind = op.op();
        int narrow = kind == SmeOp64.ArrayMultiVector.Op.SVDOT_4B || kind == SmeOp64.ArrayMultiVector.Op.UVDOT_4B
                || kind == SmeOp64.ArrayMultiVector.Op.SUVDOT || kind == SmeOp64.ArrayMultiVector.Op.USVDOT
                ? ESZ_BYTE : ESZ_HALF;
        boolean nSigned = kind != SmeOp64.ArrayMultiVector.Op.UVDOT_2H && kind != SmeOp64.ArrayMultiVector.Op.UVDOT_4B
                && kind != SmeOp64.ArrayMultiVector.Op.UVDOT_4H && kind != SmeOp64.ArrayMultiVector.Op.USVDOT;
        boolean mSigned = kind != SmeOp64.ArrayMultiVector.Op.UVDOT_2H && kind != SmeOp64.ArrayMultiVector.Op.UVDOT_4B
                && kind != SmeOp64.ArrayMultiVector.Op.UVDOT_4H && kind != SmeOp64.ArrayMultiVector.Op.SUVDOT;
        int group = op.count();
        long sum = accumulator;
        for (int i = 0; i < group; i++) {
            long nn = quarter(regs, op.zn() + i, group * e + member, narrow, nSigned);
            long mm = quarter(regs, op.zm(), group * indexedGroup + i, narrow, mSigned);
            sum += nn * mm;
        }
        return sum & SveIntegerOps.elementMask(kind.accumulatorEsz());
    }
}
