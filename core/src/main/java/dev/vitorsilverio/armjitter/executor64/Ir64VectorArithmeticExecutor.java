package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;

import java.math.BigInteger;

/// Executa a IR de AdvSIMD inteiro — aritmética/comparação (B8.7): "three same"/"three same
/// pairwise"/"three different" (alargando/largo+estreito/estreitando)/"across lanes"/
/// "two-register miscellaneous", e as formas ESCALARES que reaproveitam os mesmos records (ver
/// {@link Ir64Op.VectorArithmeticThreeSame}/{@link Ir64Op.VectorArithmeticUnary}). Sibling de
/// {@link Ir64FpExecutor}: sem estado próprio (nenhuma operação toca memória), métodos estáticos.
/// É o oráculo semântico (G1) — nenhum `Kind` desta task entra em `Ir64NativePolicy`, mesma
/// decisão de todo `Kind` novo desde B8.4/B8.6.
final class Ir64VectorArithmeticExecutor {
    private Ir64VectorArithmeticExecutor() {
    }

    /// `log2` do tamanho de um elemento doubleword (8 bytes) — B8.12, usado para escolher entre
    /// `Wn`/`Xn` em `DUP`/`INS` (registrador geral).
    private static final int DOUBLEWORD_ESZ = 3;

    /// Sign-extende um elemento de `1 << esz` bytes (já lido zero-extendido por
    /// {@link Aarch64FpRegisters#element}) para `long`.
    private static long signExtend(long value, int esz) {
        int bits = 8 << esz;
        if (bits == 64) {
            return value;
        }
        int shift = 64 - bits;
        return (value << shift) >> shift;
    }

    private static long elementMask(int esz) {
        int bits = 8 << esz;
        return bits == 64 ? -1L : (1L << bits) - 1;
    }

    private static long truncate(long value, int esz) {
        return value & elementMask(esz);
    }

    /// Máscara "todos-1" ou `0` para o resultado de uma comparação de elemento.
    private static long boolMask(boolean condition, int esz) {
        return condition ? elementMask(esz) : 0L;
    }

    /// `RBIT_v` (B8.18): inverte a ordem dos bits dentro do BYTE baixo de `a` (sempre byte, arranjo
    /// fixo — ver {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder#decodeVectorUnaryByteOnlyOpcode}).
    /// `Integer.reverse` inverte os 32 bits inteiros; os 8 bits mais altos do resultado são
    /// exatamente o byte baixo original invertido.
    private static long reverseBitsInByte(long a) {
        return (Integer.reverse((int) a) >>> 24) & 0xFFL;
    }

    private static int elementsPerRegister(boolean q, int esz) {
        return (q ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES) >> esz;
    }

    /// Reinterpreta um `long` java (que pode ser negativo) como o inteiro NÃO ASSINADO de 64 bits
    /// que ele representa em complemento de dois — necessário porque `esz=3` (doubleword) é o
    /// único tamanho em que um elemento não assinado pode ultrapassar {@link Long#MAX_VALUE}, e daí
    /// pra frente toda a aritmética de saturação de B8.8 usa {@link BigInteger} deliberadamente
    /// (não é otimização prematura: `Kind`s desta task não entram em `Ir64NativePolicy`, caem no
    /// interpretador, então exatidão pesa mais que velocidade de bit a bit aqui).
    private static BigInteger unsignedBig(long value) {
        return value >= 0 ? BigInteger.valueOf(value) : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /// Satura `value` (matemático, sem wraparound) ao intervalo representável por um elemento de
    /// {@code esz} bytes, assinado ou não — usado por toda operação `SQ*`/`UQ*` desta task.
    private static long saturateToElement(BigInteger value, int esz, boolean signed) {
        int bits = 8 << esz;
        BigInteger max = signed
                ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger min = signed ? max.negate().subtract(BigInteger.ONE) : BigInteger.ZERO;
        if (value.compareTo(max) > 0) {
            return max.longValue();
        }
        if (value.compareTo(min) < 0) {
            return min.longValue();
        }
        return value.longValue();
    }

    private static long signedSaturatingAdd(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).add(BigInteger.valueOf(sb)), esz, true);
    }

    private static long signedSaturatingSub(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).subtract(BigInteger.valueOf(sb)), esz, true);
    }

    private static long unsignedSaturatingAdd(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).add(unsignedBig(b)), esz, false);
    }

    private static long unsignedSaturatingSub(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).subtract(unsignedBig(b)), esz, false);
    }

    /// Acumulação saturante em elemento ASSINADO com operando NÃO assinado (`SUQADD`).
    private static long signedAccumulateSaturating(long signedCurrent, long unsignedOperand, int esz) {
        return saturateToElement(BigInteger.valueOf(signedCurrent).add(unsignedBig(unsignedOperand)), esz, true);
    }

    /// Acumulação saturante em elemento NÃO assinado com operando ASSINADO (`USQADD`).
    private static long unsignedAccumulateSaturating(long unsignedCurrent, long signedOperand, int esz) {
        return saturateToElement(unsignedBig(unsignedCurrent).add(BigInteger.valueOf(signedOperand)), esz, false);
    }

    /// Deslocamento à esquerda seguro: Java `<<`/`>>>`/`>>` usam o deslocamento MOD 64 para `long`
    /// (`x << 64` == `x << 0`, não `0`) — esta e as duas próximas funções são o guarda-corpo contra
    /// esse comportamento sempre que o deslocamento pode chegar a `64` (só acontece com `esz=3`) ou,
    /// no caso do deslocamento POR REGISTRADOR, a qualquer magnitude de um byte assinado (`-128`..
    /// `127`).
    private static long safeShiftLeft(long value, int shift) {
        return shift >= 64 ? 0L : value << shift;
    }

    private static long logicalShiftRight(long value, int shift) {
        return shift >= 64 ? 0L : value >>> shift;
    }

    private static long arithmeticShiftRight(long value, int shift) {
        return shift >= 64 ? (value < 0 ? -1L : 0L) : value >> shift;
    }

    /// Deslocamento à direita com ARREDONDAMENTO (`round = 1 << (shift-1)` somado antes de
    /// deslocar) — em {@link BigInteger} para não ter que lidar manualmente com o transbordo de 64
    /// bits que a soma `value + round` pode causar quando `esz=3` (ver {@link #unsignedBig}).
    private static long roundingShiftRight(long value, int shift, boolean signed) {
        if (shift <= 0) {
            return value;
        }
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        BigInteger round = BigInteger.ONE.shiftLeft(shift - 1);
        return v.add(round).shiftRight(shift).longValue();
    }

    /// Deslocamento à esquerda por quantidade VARIÁVEL (`0`-`127`, deslocamento por registrador ou
    /// imediato) com saturação ao tamanho do elemento — {@link BigInteger} evita ter que truncar
    /// manualmente antes de saturar (deslocar um `long` de 64 bits por `> 64` bits já perde
    /// informação em Java antes mesmo da saturação rodar).
    private static long saturatingShiftLeft(long value, int shift, int esz, boolean signed) {
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        return saturateToElement(v.shiftLeft(shift), esz, signed);
    }

    /// Multiplicação dobrada de alta ordem saturante (`SQDMULH`/`SQRDMULH`) — `esize = 8<<esz`.
    private static long doublingMultiplyHigh(long sa, long sb, int esz, boolean rounding) {
        int esize = 8 << esz;
        BigInteger product = BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1);
        if (rounding) {
            product = product.add(BigInteger.ONE.shiftLeft(esize - 1));
        }
        return saturateToElement(product.shiftRight(esize), esz, true);
    }

    /// `2*sext(a)*sext(b)`, saturado ao tamanho `esz` (LARGO — `esz` aqui já é o `wideEsz` do
    /// chamador) — usado por `SQDMULL`/`SQDMLAL`/`SQDMLSL`.
    private static long saturatingDoublingProduct(long sa, long sb, int wideEsz) {
        return saturateToElement(BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1), wideEsz, true);
    }

    // `registerShiftAmount`/`shiftByRegister`/`roundingShiftByRegister`/`saturatingShiftByRegister`
    // (deslocamento por registrador de `SSHL`/`USHL`/`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/`SQRSHL`/
    // `UQRSHL`) migraram para o núcleo COMPARTILHADO `advsimd/AdvSimdLanes` em B13.5 — as 16
    // operações que os usavam agora vivem só lá (ver `sharedThreeSameOp`). B13.7 fez o mesmo com
    // `insertShiftLeft`/`insertShiftRight` (`SLI`/`SRI`): vivem só em `AdvSimdLanes.shiftImmediate`.

    /// Escrita "SIMD&FP destructive": zera os bits altos de `rd` quando `!q` — a forma vetorial
    /// com `q=false` só escreveu os 64 bits baixos; a forma escalar (`esz=3`/`q=false` reaproveitado,
    /// ver javadoc do record) também passa por aqui.
    private static void finishDestructiveWrite(Aarch64FpRegisters fp, int rd, boolean q) {
        if (!q) {
            fp.setQ(rd, fp.low64(rd), 0L);
        }
    }

    /// Escrita destrutiva CIENTE de forma escalar (B8.8): quando {@code scalar}, zera TUDO acima
    /// de {@code esz} bits — inclusive dentro do `low64`, não só os 64 bits altos — porque a forma
    /// escalar de B8.8 processa um ÚNICO elemento que pode ser menor que 64 bits (`SQADD_s.B`,
    /// `SQSHL_s.H`, ...). `fp.element(rd,0,esz)` já devolve o elemento recém-escrito (zero-
    /// extendido a `esz` bits); usá-lo como o `low64` inteiro zera o resto automaticamente. Sem
    /// isto, `sqadd b0,...` deixaria lixo de uma escrita anterior mais larga nos bits `[63:8]` de
    /// `V0` — bug real que {@link #finishDestructiveWrite} (só zera `[127:64]`) não cobre quando
    /// `esz<3` (ver javadoc de {@link Ir64Op.VectorArithmeticThreeSame#scalar}).
    private static void finishScalarAwareWrite(Aarch64FpRegisters fp, int rd, boolean scalar, boolean q, int esz) {
        if (scalar) {
            fp.setQ(rd, fp.element(rd, 0, esz), 0L);
        } else {
            finishDestructiveWrite(fp, rd, q);
        }
    }

    /// RFC B13.2 (D1, reuso do núcleo vetorial): mapeia TODA operação "three same" para o núcleo
    /// COMPARTILHADO {@link AdvSimdLanes}. A B13.4 migrou o subconjunto inteiro não saturante/lógico
    /// e a B13.5 migrou as 16 saturantes / de deslocamento por registrador (`SQADD`..`SQRDMLSH`) —
    /// desde então NENHUMA operação fica no `switch` local; {@link #executeThreeSame} só delega. O
    /// `default -> throw` documenta o contrato (todo valor de {@link Ir64VectorThreeSameOp} TEM que
    /// ter entrada aqui). Cada operação existe em exatamente UM lugar: o núcleo.
    private static AdvSimdThreeSameOp sharedThreeSameOp(Ir64VectorThreeSameOp op) {
        return switch (op) {
            case ADD -> AdvSimdThreeSameOp.ADD;
            case SUB -> AdvSimdThreeSameOp.SUB;
            case CMGT -> AdvSimdThreeSameOp.CMGT;
            case CMHI -> AdvSimdThreeSameOp.CMHI;
            case CMGE -> AdvSimdThreeSameOp.CMGE;
            case CMHS -> AdvSimdThreeSameOp.CMHS;
            case CMTST -> AdvSimdThreeSameOp.CMTST;
            case CMEQ -> AdvSimdThreeSameOp.CMEQ;
            case SHADD -> AdvSimdThreeSameOp.SHADD;
            case UHADD -> AdvSimdThreeSameOp.UHADD;
            case SHSUB -> AdvSimdThreeSameOp.SHSUB;
            case UHSUB -> AdvSimdThreeSameOp.UHSUB;
            case SRHADD -> AdvSimdThreeSameOp.SRHADD;
            case URHADD -> AdvSimdThreeSameOp.URHADD;
            case SMAX -> AdvSimdThreeSameOp.SMAX;
            case UMAX -> AdvSimdThreeSameOp.UMAX;
            case SMIN -> AdvSimdThreeSameOp.SMIN;
            case UMIN -> AdvSimdThreeSameOp.UMIN;
            case SABD -> AdvSimdThreeSameOp.SABD;
            case UABD -> AdvSimdThreeSameOp.UABD;
            case SABA -> AdvSimdThreeSameOp.SABA;
            case UABA -> AdvSimdThreeSameOp.UABA;
            case MUL -> AdvSimdThreeSameOp.MUL;
            case PMUL -> AdvSimdThreeSameOp.PMUL;
            case MLA -> AdvSimdThreeSameOp.MLA;
            case MLS -> AdvSimdThreeSameOp.MLS;
            case AND -> AdvSimdThreeSameOp.AND;
            case BIC -> AdvSimdThreeSameOp.BIC;
            case ORR -> AdvSimdThreeSameOp.ORR;
            case ORN -> AdvSimdThreeSameOp.ORN;
            case EOR -> AdvSimdThreeSameOp.EOR;
            case BSL -> AdvSimdThreeSameOp.BSL;
            case BIT -> AdvSimdThreeSameOp.BIT;
            case BIF -> AdvSimdThreeSameOp.BIF;
            // B13.5 — saturantes / deslocamento por registrador (migradas do `switch` local):
            case SQADD -> AdvSimdThreeSameOp.SQADD;
            case UQADD -> AdvSimdThreeSameOp.UQADD;
            case SQSUB -> AdvSimdThreeSameOp.SQSUB;
            case UQSUB -> AdvSimdThreeSameOp.UQSUB;
            case SSHL -> AdvSimdThreeSameOp.SSHL;
            case USHL -> AdvSimdThreeSameOp.USHL;
            case SRSHL -> AdvSimdThreeSameOp.SRSHL;
            case URSHL -> AdvSimdThreeSameOp.URSHL;
            case SQSHL -> AdvSimdThreeSameOp.SQSHL;
            case UQSHL -> AdvSimdThreeSameOp.UQSHL;
            case SQRSHL -> AdvSimdThreeSameOp.SQRSHL;
            case UQRSHL -> AdvSimdThreeSameOp.UQRSHL;
            case SQDMULH -> AdvSimdThreeSameOp.SQDMULH;
            case SQRDMULH -> AdvSimdThreeSameOp.SQRDMULH;
            case SQRDMLAH -> AdvSimdThreeSameOp.SQRDMLAH;
            case SQRDMLSH -> AdvSimdThreeSameOp.SQRDMLSH;
        };
    }

    static boolean executeThreeSame(Aarch64Core core, Ir64Op.VectorArithmeticThreeSame op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        // Toda operação "three same" vive no núcleo COMPARTILHADO desde B13.5 (nada mais no `switch`
        // local). A escrita destrutiva de `[127:64]`/escalar continua sendo do lado A64.
        AdvSimdLanes.threeSame(fp, sharedThreeSameOp(op.op()), esz, elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    static boolean executePairwise(Aarch64Core core, Ir64Op.VectorArithmeticPairwise op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        // RFC B13.2 (D1): a redução pairwise vive no núcleo COMPARTILHADO — a MESMA função que o
        // NEON de 32 bits chama para `VPADD`/`VPMAX`/`VPMIN` (B13.4). A escrita destrutiva de
        // `[127:64]` continua sendo do lado A64.
        AdvSimdLanes.pairwise(fp, sharedPairwiseOp(op.op()), esz, elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    private static AdvSimdPairwiseOp sharedPairwiseOp(Ir64VectorPairwiseOp op) {
        return switch (op) {
            case ADD -> AdvSimdPairwiseOp.ADD;
            case SMAX -> AdvSimdPairwiseOp.SMAX;
            case UMAX -> AdvSimdPairwiseOp.UMAX;
            case SMIN -> AdvSimdPairwiseOp.SMIN;
            case UMIN -> AdvSimdPairwiseOp.UMIN;
        };
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorWideningOp` → {@link AdvSimdWideningOp} 1:1 (sem `default`
    /// — o `switch` cobre as 16; {@link AdvSimdWideningOp#PMULL} não tem par aqui, é exclusivo do
    /// `VMULL.P8` do NEON A32). B13.10 migrou a semântica para lá.
    private static AdvSimdWideningOp sharedWideningOp(Ir64VectorWideningOp op) {
        return switch (op) {
            case SMULL -> AdvSimdWideningOp.SMULL;
            case UMULL -> AdvSimdWideningOp.UMULL;
            case SMLAL -> AdvSimdWideningOp.SMLAL;
            case UMLAL -> AdvSimdWideningOp.UMLAL;
            case SMLSL -> AdvSimdWideningOp.SMLSL;
            case UMLSL -> AdvSimdWideningOp.UMLSL;
            case SADDL -> AdvSimdWideningOp.SADDL;
            case UADDL -> AdvSimdWideningOp.UADDL;
            case SSUBL -> AdvSimdWideningOp.SSUBL;
            case USUBL -> AdvSimdWideningOp.USUBL;
            case SABAL -> AdvSimdWideningOp.SABAL;
            case UABAL -> AdvSimdWideningOp.UABAL;
            case SABDL -> AdvSimdWideningOp.SABDL;
            case UABDL -> AdvSimdWideningOp.UABDL;
            case SQDMULL -> AdvSimdWideningOp.SQDMULL;
            case SQDMLAL -> AdvSimdWideningOp.SQDMLAL;
            case SQDMLSL -> AdvSimdWideningOp.SQDMLSL;
        };
    }

    /// `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SADDL`/`UADDL`/`SSUBL`/`USUBL`/`SABAL`/
    /// `UABAL`/`SABDL`/`UABDL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL` (B8.7/B8.8/B8.20, "three different"
    /// alargando). Desde B13.10 só delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#widening}) —
    /// a MESMA função que o NEON de 32 bits chama para `VADDL`/`VMULL`/`VMLAL`/...; a escrita
    /// destrutiva de `[127:64]`/escalar continua sendo do lado A64.
    static boolean executeWidening(Aarch64Core core, Ir64Op.VectorArithmeticWidening op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        // B8.20: forma ESCALAR (`SQDMULL_s`/`SQDMLAL_s`/`SQDMLSL_s`) processa um ÚNICO elemento,
        // sem metade de registrador (mesmo padrão de {@link #executeWideningByElement}).
        int outputElements = op.scalar() ? 1 : elementsPerRegister(true, wideEsz);
        int laneOffset = (!op.scalar() && op.q()) ? outputElements : 0;
        AdvSimdLanes.widening(fp, sharedWideningOp(op.op()), esz, outputElements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        if (op.scalar()) {
            finishScalarAwareWrite(fp, op.rd(), true, false, wideEsz);
        }
        return false;
    }

    /// B8.19: `MUL_vi`/`MLA_vi`/`MLS_vi`/`SQDMULH_{vi,si}`/`SQRDMULH_{vi,si}` — desde B13.11 só
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#threeSameByElement}) — a MESMA função que
    /// o NEON de 32 bits chama para `VMLA_2sc`/`VQDMULH_2sc`/... A escrita destrutiva de
    /// `[127:64]`/escalar continua sendo do lado A64.
    static boolean executeThreeSameByElement(Aarch64Core core, Ir64Op.VectorArithmeticThreeSameByElement op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        AdvSimdLanes.threeSameByElement(fp, sharedThreeSameOp(op.op()), esz, elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER, op.index());
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// B8.19: `SMULL_vi`/`UMULL_vi`/`SMLAL_vi`/`UMLAL_vi`/`SMLSL_vi`/`UMLSL_vi`/`SQDMULL_{vi,si}`/
    /// `SQDMLAL_{vi,si}`/`SQDMLSL_{vi,si}` — desde B13.11 só delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#wideningByElement}) — a MESMA função que o NEON de 32 bits chama para
    /// `VMLAL_2sc`/`VQDMULL_2sc`/... A forma ESCALAR (`SQDMULL_si`/`SQDMLAL_si`/`SQDMLSL_si`) produz
    /// um ÚNICO elemento largo, com escrita destrutiva ciente de tamanho
    /// ({@link #finishScalarAwareWrite}) — diferente de {@link #executeWidening}, que sempre
    /// preenche os 128 bits inteiros.
    static boolean executeWideningByElement(Aarch64Core core, Ir64Op.VectorArithmeticWideningByElement op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int outputElements = op.scalar() ? 1 : elementsPerRegister(true, wideEsz);
        int laneOffset = (!op.scalar() && op.q()) ? outputElements : 0;
        AdvSimdLanes.wideningByElement(fp, sharedWideningOp(op.op()), esz, outputElements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER, op.index());
        if (op.scalar()) {
            finishScalarAwareWrite(fp, op.rd(), true, false, wideEsz);
        }
        return false;
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorWideOp` → {@link AdvSimdWideOp} 1:1 (sem `default` — o
    /// `switch` cobre as 4). B13.10 migrou a semântica para lá.
    private static AdvSimdWideOp sharedWideOp(Ir64VectorWideOp op) {
        return switch (op) {
            case SADDW -> AdvSimdWideOp.SADDW;
            case UADDW -> AdvSimdWideOp.UADDW;
            case SSUBW -> AdvSimdWideOp.SSUBW;
            case USUBW -> AdvSimdWideOp.USUBW;
        };
    }

    /// `SADDW`/`UADDW`/`SSUBW`/`USUBW` (B8.7, "three different" larga). Desde B13.10 só delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#wide}) — a MESMA função que o NEON de 32 bits chama
    /// para `VADDW`/`VSUBW`.
    static boolean executeWide(Aarch64Core core, Ir64Op.VectorArithmeticWide op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int elements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? elements : 0;
        AdvSimdLanes.wide(fp, sharedWideOp(op.op()), esz, elements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        return false;
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorNarrowOp` → {@link AdvSimdNarrowOp} 1:1 (sem `default` — o
    /// `switch` cobre as 4). B13.10 migrou a semântica para lá.
    private static AdvSimdNarrowOp sharedHalfNarrowOp(Ir64VectorNarrowOp op) {
        return switch (op) {
            case ADDHN -> AdvSimdNarrowOp.ADDHN;
            case RADDHN -> AdvSimdNarrowOp.RADDHN;
            case SUBHN -> AdvSimdNarrowOp.SUBHN;
            case RSUBHN -> AdvSimdNarrowOp.RSUBHN;
        };
    }

    /// `ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN` (B8.7, "three different" estreitando/"half narrowing").
    /// Desde B13.10 só delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#narrow}) — a MESMA
    /// função que o NEON de 32 bits chama para `VADDHN`/`VSUBHN`; a escrita destrutiva de
    /// `[127:64]` continua sendo do lado A64.
    static boolean executeNarrow(Aarch64Core core, Ir64Op.VectorArithmeticNarrow op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int elements = elementsPerRegister(true, wideEsz);
        int laneOffset = op.q() ? elements : 0;
        AdvSimdLanes.narrow(fp, sharedHalfNarrowOp(op.op()), esz, elements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rm() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    static boolean executeAcrossLanes(Aarch64Core core, Ir64Op.VectorAcrossLanes op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        boolean signed = op.op() != Ir64VectorAcrossLanesOp.UADDLV
                && op.op() != Ir64VectorAcrossLanesOp.UMAXV && op.op() != Ir64VectorAcrossLanesOp.UMINV;
        long accumulator;
        boolean widen = op.op() == Ir64VectorAcrossLanesOp.SADDLV || op.op() == Ir64VectorAcrossLanesOp.UADDLV;
        boolean isMax = op.op() == Ir64VectorAcrossLanesOp.SMAXV || op.op() == Ir64VectorAcrossLanesOp.UMAXV;
        boolean isMin = op.op() == Ir64VectorAcrossLanesOp.SMINV || op.op() == Ir64VectorAcrossLanesOp.UMINV;
        if (isMax || isMin) {
            accumulator = extendMaybe(fp.element(op.rn(), 0, esz), esz, signed);
            for (int i = 1; i < elements; i++) {
                long v = extendMaybe(fp.element(op.rn(), i, esz), esz, signed);
                accumulator = isMax ? Math.max(accumulator, v) : Math.min(accumulator, v);
            }
        } else {
            accumulator = 0;
            for (int i = 0; i < elements; i++) {
                accumulator += extendMaybe(fp.element(op.rn(), i, esz), esz, signed);
            }
        }
        int resultEsz = widen ? esz + 1 : esz;
        fp.setElement(op.rd(), 0, resultEsz, truncate(accumulator, resultEsz));
        finishDestructiveWrite(fp, op.rd(), false);
        return false;
    }

    private static long extendMaybe(long value, int esz, boolean signed) {
        return signed ? signExtend(value, esz) : value;
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorUnaryOp` → {@link AdvSimdUnaryOp} do núcleo COMPARTILHADO,
    /// ou `null` para {@code SUQADD}/{@code USQADD}/{@code RBIT} — os 3 valores sem encoding A32
    /// correspondente neste grupo (B13.12 não os migrou, ver Javadoc de {@link AdvSimdUnaryOp}).
    private static AdvSimdUnaryOp sharedUnaryOp(Ir64VectorUnaryOp op) {
        return switch (op) {
            case ABS -> AdvSimdUnaryOp.ABS;
            case NEG -> AdvSimdUnaryOp.NEG;
            case CMEQ0 -> AdvSimdUnaryOp.CMEQ0;
            case CMGT0 -> AdvSimdUnaryOp.CMGT0;
            case CMGE0 -> AdvSimdUnaryOp.CMGE0;
            case CMLT0 -> AdvSimdUnaryOp.CMLT0;
            case CMLE0 -> AdvSimdUnaryOp.CMLE0;
            case SADDLP -> AdvSimdUnaryOp.SADDLP;
            case UADDLP -> AdvSimdUnaryOp.UADDLP;
            case SADALP -> AdvSimdUnaryOp.SADALP;
            case UADALP -> AdvSimdUnaryOp.UADALP;
            case SQABS -> AdvSimdUnaryOp.SQABS;
            case SQNEG -> AdvSimdUnaryOp.SQNEG;
            case CLS -> AdvSimdUnaryOp.CLS;
            case CLZ -> AdvSimdUnaryOp.CLZ;
            case CNT -> AdvSimdUnaryOp.CNT;
            case NOT -> AdvSimdUnaryOp.NOT;
            case REV64 -> AdvSimdUnaryOp.REV64;
            case REV32 -> AdvSimdUnaryOp.REV32;
            case REV16 -> AdvSimdUnaryOp.REV16;
            case URECPE -> AdvSimdUnaryOp.URECPE;
            case URSQRTE -> AdvSimdUnaryOp.URSQRTE;
            case SUQADD, USQADD, RBIT -> null;
        };
    }

    /// `REV64`/`REV32`/`REV16`/pareamento largo (`SADDLP`/...) fazem escrita DESTRUTIVA (zeram
    /// `[127:64]` quando `!q`); o resto usa a finalização "scalar-aware" comum.
    private static boolean isDestructiveOnlyUnary(Ir64VectorUnaryOp op) {
        return switch (op) {
            case REV64, REV32, REV16, SADDLP, UADDLP, SADALP, UADALP -> true;
            default -> false;
        };
    }

    static boolean executeUnary(Aarch64Core core, Ir64Op.VectorArithmeticUnary op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        AdvSimdUnaryOp shared = sharedUnaryOp(op.op());
        if (shared != null) {
            // `op.scalar()` só é `true` para o resto (o decoder já nega forma escalar de
            // REV*/pareamento largo — ver `Aarch64Decoder#decodeVectorUnaryOpcode`).
            int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
            AdvSimdLanes.unary(fp, shared, esz, elements,
                    op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                    op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
            if (isDestructiveOnlyUnary(op.op())) {
                finishDestructiveWrite(fp, op.rd(), op.q());
            } else {
                finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
            }
            return false;
        }
        // `SUQADD`/`USQADD`/`RBIT`: sem encoding A32 correspondente (B13.12 não os migrou) —
        // continuam aqui, únicos sobreviventes do `switch` original.
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        for (int i = 0; i < elements; i++) {
            long a = fp.element(op.rn(), i, esz);
            long sa = signExtend(a, esz);
            long result = switch (op.op()) {
                case SUQADD -> signedAccumulateSaturating(signExtend(fp.element(op.rd(), i, esz), esz), a, esz);
                case USQADD -> unsignedAccumulateSaturating(fp.element(op.rd(), i, esz), sa, esz);
                // `RBIT` (B8.18): sempre `esz=0` (byte), forçado pelo decoder — ver
                // {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder#decodeVectorUnaryByteOnlyOpcode}.
                case RBIT -> reverseBitsInByte(a);
                default -> throw new IllegalStateException("tratado no ramo compartilhado acima: " + op.op());
            };
            fp.setElement(op.rd(), i, esz, truncate(result, esz));
        }
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    static boolean executeScalarPairwiseAdd(Aarch64Core core, Ir64Op.VectorScalarPairwiseAdd op) {
        Aarch64FpRegisters fp = core.fp();
        long result = fp.element(op.rn(), 0, 3) + fp.element(op.rn(), 1, 3);
        fp.setD(op.rd(), result);
        return false;
    }

    /// `SQXTN`/`SQXTUN`/`UQXTN` (B8.8) — narrow unário saturante, vetorial e escalar (a forma
    /// escalar processa só o elemento `0`, ver {@link Ir64Op.VectorArithmeticNarrowUnary#scalar}).
    /// RFC B13.2 (D1): mapeia `Ir64VectorNarrowUnaryOp` → {@link AdvSimdNarrowUnaryOp} do núcleo
    /// COMPARTILHADO 1:1 (os 4 valores são os MESMOS nos dois lados, migrados na B13.12).
    private static AdvSimdNarrowUnaryOp sharedNarrowUnaryOp(Ir64VectorNarrowUnaryOp op) {
        return switch (op) {
            case SQXTN -> AdvSimdNarrowUnaryOp.SQXTN;
            case SQXTUN -> AdvSimdNarrowUnaryOp.SQXTUN;
            case UQXTN -> AdvSimdNarrowUnaryOp.UQXTN;
            case XTN -> AdvSimdNarrowUnaryOp.XTN;
        };
    }

    /// `SQXTN`/`SQXTUN`/`UQXTN`/`XTN` (B8.8/B8.20) — narrow unário, vetorial e escalar (a forma
    /// escalar processa só o elemento `0`). Desde B13.12 só delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#narrowUnary}) — a MESMA função que o NEON de 32 bits chama.
    static boolean executeNarrowUnary(Aarch64Core core, Ir64Op.VectorArithmeticNarrowUnary op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int wideEsz = esz + 1;
        int elements = op.scalar() ? 1 : elementsPerRegister(true, wideEsz);
        int laneOffset = op.scalar() ? 0 : (op.q() ? elements : 0);
        AdvSimdLanes.narrowUnary(fp, sharedNarrowUnaryOp(op.op()), esz, elements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// RFC B13.2 (D1, reuso do núcleo vetorial): mapeia toda operação "shift by immediate" para o
    /// núcleo COMPARTILHADO {@link AdvSimdLanes}. Nomes homônimos 1:1 com {@link Ir64VectorShiftOp},
    /// sem `default` — o `switch` cobre as 14. B13.7 migrou a semântica (o `switch` de
    /// {@link #executeShiftImmediate} e os helpers `insertShiftLeft`/`insertShiftRight`) para lá.
    private static AdvSimdShiftImmediateOp sharedShiftOp(Ir64VectorShiftOp op) {
        return switch (op) {
            case SSHR -> AdvSimdShiftImmediateOp.SSHR;
            case USHR -> AdvSimdShiftImmediateOp.USHR;
            case SRSHR -> AdvSimdShiftImmediateOp.SRSHR;
            case URSHR -> AdvSimdShiftImmediateOp.URSHR;
            case SSRA -> AdvSimdShiftImmediateOp.SSRA;
            case USRA -> AdvSimdShiftImmediateOp.USRA;
            case SRSRA -> AdvSimdShiftImmediateOp.SRSRA;
            case URSRA -> AdvSimdShiftImmediateOp.URSRA;
            case SRI -> AdvSimdShiftImmediateOp.SRI;
            case SHL -> AdvSimdShiftImmediateOp.SHL;
            case SLI -> AdvSimdShiftImmediateOp.SLI;
            case SQSHL -> AdvSimdShiftImmediateOp.SQSHL;
            case UQSHL -> AdvSimdShiftImmediateOp.UQSHL;
            case SQSHLU -> AdvSimdShiftImmediateOp.SQSHLU;
        };
    }

    /// `SSHR`/`USHR`/`SRSHR`/`URSHR`/`SSRA`/`USRA`/`SRSRA`/`URSRA`/`SRI`/`SHL`/`SLI`/`SQSHL`/
    /// `UQSHL`/`SQSHLU` (B8.8, "shift by immediate" não-largo/não-estreito), vetorial e escalar.
    /// Desde B13.7 só delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#shiftImmediate}) — a
    /// MESMA função que o NEON de 32 bits chama; a escrita destrutiva de `[127:64]`/escalar
    /// continua sendo do lado A64.
    static boolean executeShiftImmediate(Aarch64Core core, Ir64Op.VectorShiftImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(op.q(), esz);
        AdvSimdLanes.shiftImmediate(fp, sharedShiftOp(op.op()), esz, op.shift(), elements,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorShiftNarrowOp` → {@link AdvSimdShiftNarrowOp} do núcleo
    /// COMPARTILHADO 1:1, sem `default` (o `switch` cobre as 8). B13.8 migrou a semântica para lá.
    private static AdvSimdShiftNarrowOp sharedNarrowOp(Ir64VectorShiftNarrowOp op) {
        return switch (op) {
            case SHRN -> AdvSimdShiftNarrowOp.SHRN;
            case RSHRN -> AdvSimdShiftNarrowOp.RSHRN;
            case SQSHRN -> AdvSimdShiftNarrowOp.SQSHRN;
            case UQSHRN -> AdvSimdShiftNarrowOp.UQSHRN;
            case SQSHRUN -> AdvSimdShiftNarrowOp.SQSHRUN;
            case SQRSHRN -> AdvSimdShiftNarrowOp.SQRSHRN;
            case UQRSHRN -> AdvSimdShiftNarrowOp.UQRSHRN;
            case SQRSHRUN -> AdvSimdShiftNarrowOp.SQRSHRUN;
        };
    }

    /// RFC B13.2 (D1): mapeia `Ir64VectorShiftWidenOp` → {@link AdvSimdShiftWidenOp} 1:1.
    private static AdvSimdShiftWidenOp sharedWidenOp(Ir64VectorShiftWidenOp op) {
        return switch (op) {
            case SSHLL -> AdvSimdShiftWidenOp.SSHLL;
            case USHLL -> AdvSimdShiftWidenOp.USHLL;
        };
    }

    /// `SHRN`/`RSHRN`/`SQSHRN`/`UQSHRN`/`SQSHRUN`/`SQRSHRN`/`UQRSHRN`/`SQRSHRUN` (B8.8, "shift by
    /// immediate" estreitando). Desde B13.8 só delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftNarrowImmediate}) — a MESMA função que o NEON de 32 bits chama; a
    /// escrita destrutiva de `[127:64]`/escalar continua sendo do lado A64.
    static boolean executeShiftNarrowImmediate(Aarch64Core core, Ir64Op.VectorShiftNarrowImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = op.scalar() ? 1 : elementsPerRegister(true, esz + 1);
        int laneOffset = op.scalar() ? 0 : (op.q() ? elements : 0);
        AdvSimdLanes.shiftNarrowImmediate(fp, sharedNarrowOp(op.op()), esz, op.shift(), elements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        finishScalarAwareWrite(fp, op.rd(), op.scalar(), op.q(), esz);
        return false;
    }

    /// `SSHLL`/`USHLL` (B8.8, "shift by immediate" alargando) — sempre preenche os 128 bits
    /// inteiros de `Rd`, sem saturar. Desde B13.8 só delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftWidenImmediate}).
    static boolean executeShiftWidenImmediate(Aarch64Core core, Ir64Op.VectorShiftWidenImmediate op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int outputElements = elementsPerRegister(true, esz + 1);
        int laneOffset = op.q() ? outputElements : 0;
        AdvSimdLanes.shiftWidenImmediate(fp, sharedWidenOp(op.op()), esz, op.shift(), outputElements, laneOffset,
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
        return false;
    }

    /// `EXT` (B8.10) — concatena `Rm:Rn` (`Rn` nos bytes BAIXOS) e extrai a janela de
    /// {@code datasize} bytes começando em {@link Ir64Op.VectorExtract#imm}, byte a byte (sempre
    /// `esz=0`, sem aritmética).
    static boolean executeExtract(Aarch64Core core, Ir64Op.VectorExtract op) {
        Aarch64FpRegisters fp = core.fp();
        int datasize = op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES;
        long resultLo = 0L;
        long resultHi = 0L;
        for (int i = 0; i < datasize; i++) {
            int srcIndex = op.imm() + i;
            long byteValue = srcIndex < datasize
                    ? fp.element(op.rn(), srcIndex, 0)
                    : fp.element(op.rm(), srcIndex - datasize, 0);
            if (i < Aarch64FpRegisters.DOUBLEWORD_BYTES) {
                resultLo |= byteValue << (i * 8);
            } else {
                resultHi |= byteValue << ((i - Aarch64FpRegisters.DOUBLEWORD_BYTES) * 8);
            }
        }
        fp.setQ(op.rd(), resultLo, resultHi);
        return false;
    }

    /// `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2` (B8.10) — reorganiza os elementos de `Rn`/`Rm`,
    /// sem aritmética. `pairIndex`/`secondOfPair` decompõem o índice de saída `i` no par
    /// `(par, metade)` que `TRN*`/`ZIP*` precisam; `UZP*` usa `i` diretamente (metade do registro
    /// inteira vem de `Rn`, a outra de `Rm`).
    static boolean executePermute(Aarch64Core core, Ir64Op.VectorPermute op) {
        Aarch64FpRegisters fp = core.fp();
        int esz = op.esz();
        int elements = elementsPerRegister(op.q(), esz);
        int half = elements / 2;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int pairIndex = i / 2;
            boolean secondOfPair = (i % 2) == 1;
            results[i] = switch (op.op()) {
                case UZP1 -> fp.element(i < half ? op.rn() : op.rm(), 2 * (i < half ? i : i - half), esz);
                case UZP2 -> fp.element(i < half ? op.rn() : op.rm(), 2 * (i < half ? i : i - half) + 1, esz);
                case TRN1 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex * 2, esz);
                case TRN2 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex * 2 + 1, esz);
                case ZIP1 -> fp.element(secondOfPair ? op.rm() : op.rn(), pairIndex, esz);
                case ZIP2 -> fp.element(secondOfPair ? op.rm() : op.rn(), half + pairIndex, esz);
            };
        }
        for (int i = 0; i < elements; i++) {
            fp.setElement(op.rd(), i, esz, results[i]);
        }
        finishDestructiveWrite(fp, op.rd(), op.q());
        return false;
    }

    /// `TBL`/`TBX` (B8.10) — trata `Rn`, `Rn+1`, ..., `Rn+len` (módulo
    /// {@link Aarch64FpRegisters#V_REGISTER_COUNT}) como uma tabela contígua de bytes; cada byte de
    /// `Rm` é um índice nessa tabela. Índice fora da tabela: `0` (`TBL`) ou o byte ATUAL de `Rd`
    /// (`TBX`) — lido ANTES da escrita final, já que {@link Aarch64FpRegisters#setQ} substitui os
    /// 128 bits de uma vez.
    static boolean executeTableLookup(Aarch64Core core, Ir64Op.VectorTableLookup op) {
        Aarch64FpRegisters fp = core.fp();
        int tableBytes = (op.len() + 1) * Aarch64FpRegisters.QUADWORD_BYTES;
        int indexCount = op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES;
        long resultLo = 0L;
        long resultHi = 0L;
        for (int i = 0; i < indexCount; i++) {
            int index = (int) fp.element(op.rm(), i, 0);
            long value;
            if (index < tableBytes) {
                int tableReg = (op.rn() + index / Aarch64FpRegisters.QUADWORD_BYTES) % Aarch64FpRegisters.V_REGISTER_COUNT;
                int byteInReg = index % Aarch64FpRegisters.QUADWORD_BYTES;
                value = fp.element(tableReg, byteInReg, 0);
            } else if (op.tbx()) {
                value = fp.element(op.rd(), i, 0);
            } else {
                value = 0L;
            }
            if (i < Aarch64FpRegisters.DOUBLEWORD_BYTES) {
                resultLo |= value << (i * 8);
            } else {
                resultHi |= value << ((i - Aarch64FpRegisters.DOUBLEWORD_BYTES) * 8);
            }
        }
        fp.setQ(op.rd(), resultLo, resultHi);
        return false;
    }

    /// `DUP` elemento vetorial (B8.12) — {@link Aarch64FpRegisters#replicateElement} já zera os
    /// bits altos quando `!q` (mesma disciplina de {@link #finishDestructiveWrite}).
    static boolean executeDuplicateElement(Aarch64Core core, Ir64Op.VectorDuplicateElement op) {
        Aarch64FpRegisters fp = core.fp();
        long value = fp.element(op.rn(), op.index(), op.esz());
        fp.replicateElement(op.rd(), value, op.esz(), op.q());
        return false;
    }

    /// `DUP` registrador geral (B8.12) — `esz==3` lê `Xn` (64 bits), senão `Wn` (32, zero-
    /// estendido — {@link Aarch64Core#xForWidth} já zero-estende, e
    /// {@link Aarch64FpRegisters#replicateElement} só usa os `esz` bytes baixos do valor).
    static boolean executeDuplicateGeneral(Aarch64Core core, Ir64Op.VectorDuplicateGeneral op) {
        Aarch64FpRegisters fp = core.fp();
        long value = core.xForWidth(op.rn(), op.esz() == DOUBLEWORD_ESZ);
        fp.replicateElement(op.rd(), value, op.esz(), op.q());
        return false;
    }

    /// `INS` registrador geral (B8.12) — {@link Aarch64FpRegisters#setElement} já é não-
    /// destrutivo (só o elemento indicado muda, resto de `Rd` preservado).
    static boolean executeInsertGeneral(Aarch64Core core, Ir64Op.VectorInsertGeneral op) {
        Aarch64FpRegisters fp = core.fp();
        long value = core.xForWidth(op.rn(), op.esz() == DOUBLEWORD_ESZ);
        fp.setElement(op.rd(), op.index(), op.esz(), value);
        return false;
    }

    /// `INS` elemento vetorial (B8.12) — copia `Rn[srcIndex]` para `Rd[destIndex]`, sem afetar o
    /// resto de `Rd`.
    static boolean executeInsertElement(Aarch64Core core, Ir64Op.VectorInsertElement op) {
        Aarch64FpRegisters fp = core.fp();
        long value = fp.element(op.rn(), op.srcIndex(), op.esz());
        fp.setElement(op.rd(), op.destIndex(), op.esz(), value);
        return false;
    }

    /// `SMOV`/`UMOV` (B8.12) — {@link Aarch64FpRegisters#element} já devolve o elemento zero-
    /// estendido (`UMOV`); {@link #signExtend} estende o sinal a partir de `esz` bytes quando
    /// {@link Ir64Op.VectorMoveElement#signed} (`SMOV`) — a mesma extensão serve para `Wd`/`Xd`
    /// porque {@link Aarch64Core#setXForWidth} trunca o resultado de 64 bits para os 32 baixos
    /// quando `!wide`, preservando o sinal correto dentro dessa largura.
    static boolean executeMoveElement(Aarch64Core core, Ir64Op.VectorMoveElement op) {
        Aarch64FpRegisters fp = core.fp();
        long raw = fp.element(op.rn(), op.index(), op.esz());
        long value = op.signed() ? signExtend(raw, op.esz()) : raw;
        core.setXForWidth(op.rd(), value, op.wide());
        return false;
    }

    /// `PMULL`/`PMULL2` (`p8`/`p64`, B8.11) — multiplicação polinomial `GF(2)` alargando, SEM
    /// redução (diferente de `PMUL`, que trunca — {@link AdvSimdLanes#polynomialMultiply8}). `p8`:
    /// 8 lanes de byte→halfword, reaproveita {@link AdvSimdLanes#polynomialMultiply8} sem truncar o
    /// resultado de 15 bits. `p64`: um único elemento de 64 bits→128 bits,
    /// {@link #polynomialMultiply64}.
    static boolean executePolynomialMultiplyLong(Aarch64Core core, Ir64Op.VectorPolynomialMultiplyLong op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.p64()) {
            int index = op.q() ? 1 : 0;
            long a = fp.element(op.rn(), index, 3);
            long b = fp.element(op.rm(), index, 3);
            long[] result = polynomialMultiply64(a, b);
            fp.setQ(op.rd(), result[0], result[1]);
            return false;
        }
        int outputElements = elementsPerRegister(true, 1);
        int laneOffset = op.q() ? outputElements : 0;
        for (int i = 0; i < outputElements; i++) {
            int lane = laneOffset + i;
            long a = fp.element(op.rn(), lane, 0);
            long b = fp.element(op.rm(), lane, 0);
            fp.setElement(op.rd(), i, 1, AdvSimdLanes.polynomialMultiply8(a, b));
        }
        return false;
    }

    /// Multiplicação polinomial `GF(2)` de 64×64→128 bits, SEM redução: XOR de `a<<i` (como valor
    /// de 128 bits) para cada bit `i` setado de `b` — mesma definição de {@link #polynomialMultiply8}
    /// generalizada para 64 bits. `i=0` tratado à parte porque `x >>> 64` em Java equivale a
    /// `x >>> 0` (o deslocamento é módulo 64), não a zero.
    private static long[] polynomialMultiply64(long a, long b) {
        long resultLo = 0L;
        long resultHi = 0L;
        for (int i = 0; i < 64; i++) {
            if (((b >>> i) & 1) != 0) {
                if (i == 0) {
                    resultLo ^= a;
                } else {
                    resultLo ^= a << i;
                    resultHi ^= a >>> (64 - i);
                }
            }
        }
        return new long[] {resultLo, resultHi};
    }
}
