package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.function.DoubleToLongFunction;
import java.util.function.LongToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B18.5b — `MOP4` e `TMOP`. As palavras são montadas campo a campo por {@link #mop4Word}/{@link #tmopWord} a partir
/// das bases (valor fixo de cada padrão do `.decode`) cujo decode foi conferido contra `aarch64-none-elf-as` em
/// `Aarch64SmeMop4TmopDecoderTest`. As referências são REESCRITAS aqui direto do pseudocódigo da ARM (DDI 0602,
/// `FTMOPA`/`STMOPA`/`FMOP4A`), elemento a elemento — não copiadas da implementação, que segue o `sme_helper.c` do
/// QEMU com dois desvios (o `Zk` e o deslocamento do segmento de controle), por isso o teste de controle usa bits
/// aleatórios em TODO o registrador `Zk`: um deslocamento errado de segmento muda o resultado.
class Aarch64SmeMop4TmopExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-mop4-exec-ALL", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_MOP4,
            Aarch64Feature.SME_TMOP, Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
            Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;

    private static final int FMOP4_HH = 0x81000008;
    private static final int BFMOP4_HH = 0x81200008;
    private static final int FMOP4_SS = 0x80000000;
    private static final int FMOP4_DD = 0x80C00008;
    private static final int BFMOP4_SH = 0x81000000;
    private static final int FMOP4_SH = 0x81200000;
    private static final int FMOP4A_SB = 0x80200000;
    private static final int FMOP4A_HB = 0x80200008;
    private static final int SMOP4_SH = 0x80008008;
    private static final int UMOP4_SH = 0x81008008;
    private static final int SMOP4_SB = 0x80008000;
    private static final int SMOP4_DH = 0xA0C00008;
    private static final int SUMOP4_SB = 0x80208000;
    private static final int SUMOP4_DH = 0xA0E00008;
    private static final int UMOP4_SB = 0x81208000;
    private static final int UMOP4_DH = 0xA1E00008;
    private static final int USMOP4_SB = 0x81008000;
    private static final int USMOP4_DH = 0xA1C00008;

    private static final int BFTMOPA_HH = 0x81600008;
    private static final int FTMOPA_HH = 0x81400008;
    private static final int FTMOPA_SS = 0x80400000;
    private static final int BFTMOPA_SH = 0x81400000;
    private static final int FTMOPA_SH = 0x81600000;
    private static final int FTMOPA_HB = 0x80600008;
    private static final int FTMOPA_SB = 0x80600000;
    private static final int STMOPA_SH = 0x80408008;
    private static final int UTMOPA_SH = 0x81408008;
    private static final int STMOPA_SB = 0x80408000;
    private static final int SUTMOPA_SB = 0x80608000;
    private static final int USTMOPA_SB = 0x81408000;
    private static final int UTMOPA_SB = 0x81608000;

    private static final int ZN = 6;
    private static final int ZM = 22;
    private static final int ZK = 21;
    private static final long E5M2_ONE = 0x3CL;

    private static int mop4Word(int base, int tile, int zn, int zm, boolean subtract, boolean n, boolean m) {
        return base | (m ? 1 << 20 : 0) | ((zm - 16) / 2) << 17 | (n ? 1 << 9 : 0) | (zn / 2) << 6
                | (subtract ? 1 << 4 : 0) | tile;
    }

    /// `%tmop_zk` é o inverso de `expand_tmop_zk`: `Z20`-`Z23`/`Z28`-`Z31` ↔ 3 bits.
    private static int tmopWord(int base, int tile, int zn, int zm, int zk, int idx) {
        int zkField = (zk & 3) | ((zk >> 3 & 1) << 2);
        return base | zm << 16 | zkField << 10 | (zn / 2) << 6 | idx << 4 | tile;
    }

    private static Aarch64Core core(int svlBits) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), ALL, svlBits,
                svlBits);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(SVCR_SM | SVCR_ZA);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(ALL).step(core);
    }

    private static int elementsOf(Aarch64Core core, int esz) {
        return core.streamingVectorLengthBytes() >>> esz;
    }

    private static long elem(Aarch64Core core, int z, int esz, int index) {
        return SvePredicateOps.elementOf(core.scalable(), z, index, esz);
    }

    private static void setElem(Aarch64Core core, int z, int esz, int index, long value) {
        SvePredicateOps.setElementOf(core.scalable(), z, index, esz, value);
    }

    private static long tile(Aarch64Core core, int tile, int esz, int row, int col) {
        return SmeMovaOps.zaElement(core.matrix(), Aarch64MatrixTileAddressing.rowIndex(tile, esz, row), col << esz,
                esz);
    }

    private static void setTile(Aarch64Core core, int tile, int esz, int row, int col, long value) {
        SmeMovaOps.setZaElement(core.matrix(), Aarch64MatrixTileAddressing.rowIndex(tile, esz, row), col << esz, esz,
                value);
    }

    private static long lane(long value, int index, int bits, boolean signed) {
        long raw = (value >>> (index * bits)) & ((1L << bits) - 1);
        int shift = 64 - bits;
        return signed ? (raw << shift) >> shift : raw;
    }

    private static void randomizeVectors(Aarch64Core core, Random random, int... registers) {
        for (int z : registers) {
            for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
                core.scalable().setZWord(z, w, random.nextLong());
            }
        }
    }

    // ── MOP4 inteiro ─────────────────────────────────────────────────────────────────────────────

    /// `base, esz do acumulador, bits do lane, lanes por elemento, n com sinal, m com sinal`.
    private record IntMop4(int base, int esz, int laneBits, int lanes, boolean nSigned, boolean mSigned) {
    }

    private static final IntMop4[] INT_MOP4 = {
            new IntMop4(SMOP4_SH, ESZ_SINGLE, 16, 2, true, true), new IntMop4(UMOP4_SH, ESZ_SINGLE, 16, 2, false, false),
            new IntMop4(SMOP4_SB, ESZ_SINGLE, 8, 4, true, true), new IntMop4(SUMOP4_SB, ESZ_SINGLE, 8, 4, true, false),
            new IntMop4(UMOP4_SB, ESZ_SINGLE, 8, 4, false, false), new IntMop4(USMOP4_SB, ESZ_SINGLE, 8, 4, false, true),
            new IntMop4(SMOP4_DH, ESZ_DOUBLE, 16, 4, true, true), new IntMop4(SUMOP4_DH, ESZ_DOUBLE, 16, 4, true, false),
            new IntMop4(UMOP4_DH, ESZ_DOUBLE, 16, 4, false, false), new IntMop4(USMOP4_DH, ESZ_DOUBLE, 16, 4, false, true),
    };

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void integerMop4MatchesTheQuadrantDefinitionForEveryPairChoiceAndSign(int svlBits) {
        for (IntMop4 spec : INT_MOP4) {
            for (int variant = 0; variant < 8; variant++) {
                boolean subtract = (variant & 1) != 0;
                boolean n = (variant & 2) != 0;
                boolean m = (variant & 4) != 0;
                Random random = new Random(777L + variant);
                Aarch64Core core = core(svlBits);
                randomizeVectors(core, random, ZN, ZN + 1, ZM, ZM + 1);
                int tile = spec.esz() == ESZ_DOUBLE ? 5 : 2;
                int e = elementsOf(core, spec.esz());
                long[][] initial = new long[e][e];
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        initial[r][c] = spec.esz() == ESZ_DOUBLE ? random.nextLong() : random.nextInt() & 0xFFFF_FFFFL;
                        setTile(core, tile, spec.esz(), r, c, initial[r][c]);
                    }
                }
                run(core, mop4Word(spec.base(), tile, ZN, ZM, subtract, n, m));
                String where = Integer.toHexString(spec.base()) + " sub=" + subtract + " n=" + n + " m=" + m;
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        int nReg = ZN + (c >= e / 2 && n ? 1 : 0);
                        int mReg = ZM + (r >= e / 2 && m ? 1 : 0);
                        long sum = 0;
                        for (int k = 0; k < spec.lanes(); k++) {
                            long nv = lane(elem(core, nReg, spec.esz(), r), k, spec.laneBits(), spec.nSigned());
                            long mv = lane(elem(core, mReg, spec.esz(), c), k, spec.laneBits(), spec.mSigned());
                            sum += nv * mv;
                        }
                        long expected = subtract ? initial[r][c] - sum : initial[r][c] + sum;
                        if (spec.esz() == ESZ_SINGLE) {
                            expected &= 0xFFFF_FFFFL;
                        }
                        assertEquals(expected, tile(core, tile, spec.esz(), r, c), where + " [" + r + "][" + c + "]");
                    }
                }
                int otherTile = tile == 2 ? 3 : 4;
                assertEquals(0L, tile(core, otherTile, spec.esz(), 0, 0), "outros tiles não mudam");
            }
        }
    }

    // ── MOP4 ponto flutuante ─────────────────────────────────────────────────────────────────────

    private record Fmt(int esz, DoubleToLongFunction encode, LongToDoubleFunction decode) {
    }

    private static final Fmt F16 = new Fmt(ESZ_HALF, v -> Float.floatToFloat16((float) v) & 0xFFFFL,
            b -> Float.float16ToFloat((short) b));
    private static final Fmt BF16 = new Fmt(ESZ_HALF, v -> Float.floatToRawIntBits((float) v) >>> 16 & 0xFFFFL,
            b -> Float.intBitsToFloat((int) (b << 16)));
    private static final Fmt F32 = new Fmt(ESZ_SINGLE, v -> Float.floatToRawIntBits((float) v) & 0xFFFF_FFFFL,
            b -> Float.intBitsToFloat((int) b));
    private static final Fmt F64 = new Fmt(ESZ_DOUBLE, Double::doubleToRawLongBits, Double::longBitsToDouble);

    /// `base, acumulador, formato de origem, lanes por elemento` — widening quando `lanes = 2`.
    private record FpMop4(int base, Fmt accumulator, Fmt source, int lanes) {
    }

    private static final FpMop4[] FP_MOP4 = {
            new FpMop4(FMOP4_HH, F16, F16, 1), new FpMop4(BFMOP4_HH, BF16, BF16, 1), new FpMop4(FMOP4_SS, F32, F32, 1),
            new FpMop4(FMOP4_DD, F64, F64, 1), new FpMop4(FMOP4_SH, F32, F16, 2), new FpMop4(BFMOP4_SH, F32, BF16, 2),
    };

    private static void fillSmallInts(Aarch64Core core, Random random, Fmt format, int... registers) {
        for (int z : registers) {
            for (int i = 0; i < elementsOf(core, format.esz()); i++) {
                setElem(core, z, format.esz(), i, format.encode().applyAsLong(random.nextInt(7) - 3));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void floatingPointMop4MatchesTheQuadrantDefinitionOnExactValues(int svlBits) {
        for (FpMop4 spec : FP_MOP4) {
            for (int variant = 0; variant < 8; variant++) {
                boolean subtract = (variant & 1) != 0;
                boolean n = (variant & 2) != 0;
                boolean m = (variant & 4) != 0;
                Random random = new Random(99L + variant);
                Aarch64Core core = core(svlBits);
                Fmt acc = spec.accumulator();
                Fmt src = spec.source();
                fillSmallInts(core, random, src, ZN, ZN + 1, ZM, ZM + 1);
                int tile = acc.esz() == ESZ_DOUBLE ? 6 : 1;
                int e = elementsOf(core, acc.esz());
                double[][] initial = new double[e][e];
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        initial[r][c] = random.nextInt(17) - 8;
                        setTile(core, tile, acc.esz(), r, c, acc.encode().applyAsLong(initial[r][c]));
                    }
                }
                run(core, mop4Word(spec.base(), tile, ZN, ZM, subtract, n, m));
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        int nReg = ZN + (c >= e / 2 && n ? 1 : 0);
                        int mReg = ZM + (r >= e / 2 && m ? 1 : 0);
                        double sum = 0;
                        for (int k = 0; k < spec.lanes(); k++) {
                            long nBits = lane(elem(core, nReg, acc.esz(), r), k, Short.SIZE, false);
                            long mBits = lane(elem(core, mReg, acc.esz(), c), k, Short.SIZE, false);
                            if (spec.lanes() == 1) {
                                nBits = elem(core, nReg, src.esz(), r);
                                mBits = elem(core, mReg, src.esz(), c);
                            }
                            sum += src.decode().applyAsDouble(nBits) * src.decode().applyAsDouble(mBits);
                        }
                        double expected = subtract ? initial[r][c] - sum : initial[r][c] + sum;
                        assertEquals(acc.encode().applyAsLong(expected), tile(core, tile, acc.esz(), r, c),
                                Integer.toHexString(spec.base()) + " sub=" + subtract + " n=" + n + " m=" + m + " ["
                                        + r + "][" + c + "]");
                    }
                }
            }
        }
    }

    // ── TMOP ─────────────────────────────────────────────────────────────────────────────────────

    /// Bit `position` de `Zk`.
    private static boolean controlBit(Aarch64Core core, int zk, long position) {
        return ((core.scalable().zWord(zk, (int) (position >>> 6)) >>> (position & 63)) & 1L) != 0L;
    }

    /// `base, bits por lane, lanes por elemento (2 ou 4), n com sinal, m com sinal`.
    private record IntTmop(int base, int laneBits, int lanes, boolean nSigned, boolean mSigned) {
    }

    private static final IntTmop[] INT_TMOP = {
            new IntTmop(STMOPA_SH, 16, 2, true, true), new IntTmop(UTMOPA_SH, 16, 2, false, false),
            new IntTmop(STMOPA_SB, 8, 4, true, true), new IntTmop(SUTMOPA_SB, 8, 4, true, false),
            new IntTmop(USTMOPA_SB, 8, 4, false, true), new IntTmop(UTMOPA_SB, 8, 4, false, false),
    };

    /// Linha do `TMOP` inteiro montada como no pseudocódigo da ARM: 2-way (`csize = VL/8`, 4 bits por coluna) junta as
    /// meias-palavras dos DOIS registradores nos dois primeiros bits ligados (`i < 2` compartilhado); 4-way (`csize =
    /// VL/4`, 8 bits por coluna) toma até dois bytes de CADA registrador (`i < 2` por registrador, `erow[2*r + i]`).
    private static long[] sparseRow(Aarch64Core core, IntTmop spec, int zk, long segment, int row, int col) {
        int laneEsz = spec.laneBits() == 16 ? ESZ_HALF : ESZ_BYTE;
        long[] erow = new long[spec.lanes()];
        if (spec.lanes() == 2) {
            int i = 0;
            for (int reg = 0; reg < 2; reg++) {
                for (int el = 0; el < 2; el++) {
                    if (i < 2 && controlBit(core, zk, segment + 4L * col + 2 * reg + el)) {
                        long raw = elem(core, ZN + reg, laneEsz, 2 * row + el);
                        erow[i++] = spec.nSigned() ? signExtend(raw, spec.laneBits()) : raw;
                    }
                }
            }
            return erow;
        }
        for (int reg = 0; reg < 2; reg++) {
            int i = 0;
            for (int el = 0; el < 4; el++) {
                if (i < 2 && controlBit(core, zk, segment + 8L * col + 4 * reg + el)) {
                    long raw = elem(core, ZN + reg, laneEsz, 4 * row + el);
                    erow[2 * reg + i++] = spec.nSigned() ? signExtend(raw, spec.laneBits()) : raw;
                }
            }
        }
        return erow;
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void integerTmopSelectsRowsFromTheIndexedControlSegmentOfZk(int svlBits) {
        for (IntTmop spec : INT_TMOP) {
            for (int idx = 0; idx < 4; idx++) {
                for (int zk : new int[] {ZK, 29}) {
                    Random random = new Random(31L * idx + zk);
                    Aarch64Core core = core(svlBits);
                    randomizeVectors(core, random, ZN, ZN + 1, ZM, zk);
                    int e = elementsOf(core, ESZ_SINGLE);
                    long[][] initial = new long[e][e];
                    for (int r = 0; r < e; r++) {
                        for (int c = 0; c < e; c++) {
                            initial[r][c] = random.nextInt() & 0xFFFF_FFFFL;
                            setTile(core, 3, ESZ_SINGLE, r, c, initial[r][c]);
                        }
                    }
                    run(core, tmopWord(spec.base(), 3, ZN, ZM, zk, idx));
                    int bitsPerColumn = spec.lanes() == 2 ? 4 : 8;
                    long segment = (long) idx * e * bitsPerColumn;
                    int laneEsz = spec.laneBits() == 16 ? ESZ_HALF : ESZ_BYTE;
                    for (int r = 0; r < e; r++) {
                        for (int c = 0; c < e; c++) {
                            long[] erow = sparseRow(core, spec, zk, segment, r, c);
                            long sum = 0;
                            for (int j = 0; j < spec.lanes(); j++) {
                                long raw = elem(core, ZM, laneEsz, spec.lanes() * c + j);
                                sum += erow[j] * (spec.mSigned() ? signExtend(raw, spec.laneBits()) : raw);
                            }
                            assertEquals((initial[r][c] + sum) & 0xFFFF_FFFFL, tile(core, 3, ESZ_SINGLE, r, c),
                                    Integer.toHexString(spec.base()) + " idx=" + idx + " zk=" + zk + " [" + r + "]["
                                            + c + "]");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theControlVectorIsZkAndNotZm() {
        Aarch64Core core = core(256);
        // Zk = tudo ligado: as duas primeiras origens entram. Zm (que o QEMU leria como controle) = 0.
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(ZK, w, -1L);
            core.scalable().setZWord(ZM, w, 0L);
            core.scalable().setZWord(ZN, w, 0x0101_0101_0101_0101L);
            core.scalable().setZWord(ZN + 1, w, 0x0101_0101_0101_0101L);
        }
        for (int i = 0; i < elementsOf(core, ESZ_SINGLE); i++) {
            setElem(core, ZM, ESZ_SINGLE, i, 0x0101_0101L);
        }
        run(core, tmopWord(UTMOPA_SB, 0, ZN, ZM, ZK, 0));
        // 2 bytes de Zn + 2 de Zn+1, todos 1, vezes bytes 1 de Zm = 4
        assertEquals(4L, tile(core, 0, ESZ_SINGLE, 3, 5));
    }

    // ── TMOP ponto flutuante ─────────────────────────────────────────────────────────────────────

    /// Referência FP do `FTMOPA` do pseudocódigo da ARM. `lanes = 1` (hh/ss): `Zn[row]` se o bit `2*col` , senão
    /// `Zn+1[row]` se o bit `2*col + 1`, senão 0. `lanes = 2` (sh): as duas primeiras de `Zn`/`Zn+1` (4 bits).
    private static double fpTmopRowTimesColumn(Aarch64Core core, FpMop4 spec, int zk, long segment, int row, int col) {
        Fmt src = spec.source();
        if (spec.lanes() == 1) {
            for (int reg = 0; reg < 2; reg++) {
                if (controlBit(core, zk, segment + 2L * col + reg)) {
                    return src.decode().applyAsDouble(elem(core, ZN + reg, src.esz(), row))
                            * src.decode().applyAsDouble(elem(core, ZM, src.esz(), col));
                }
            }
            return 0.0;
        }
        double sum = 0.0;
        int i = 0;
        for (int reg = 0; reg < 2; reg++) {
            for (int el = 0; el < 2; el++) {
                if (i < 2 && controlBit(core, zk, segment + 4L * col + 2 * reg + el)) {
                    sum += src.decode().applyAsDouble(elem(core, ZN + reg, ESZ_HALF, 2 * row + el))
                            * src.decode().applyAsDouble(elem(core, ZM, ESZ_HALF, 2 * col + i));
                    i++;
                }
            }
        }
        return sum;
    }

    private static final FpMop4[] FP_TMOP = {
            new FpMop4(FTMOPA_HH, F16, F16, 1), new FpMop4(BFTMOPA_HH, BF16, BF16, 1),
            new FpMop4(FTMOPA_SS, F32, F32, 1), new FpMop4(FTMOPA_SH, F32, F16, 2), new FpMop4(BFTMOPA_SH, F32, BF16, 2),
    };

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void floatingPointTmopMatchesThePseudocodeOnExactValues(int svlBits) {
        for (FpMop4 spec : FP_TMOP) {
            for (int idx = 0; idx < 4; idx++) {
                Random random = new Random(5L + idx);
                Aarch64Core core = core(svlBits);
                Fmt acc = spec.accumulator();
                fillSmallInts(core, random, spec.source(), ZN, ZN + 1, ZM);
                randomizeVectors(core, random, ZK);
                int e = elementsOf(core, acc.esz());
                double[][] initial = new double[e][e];
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        initial[r][c] = random.nextInt(17) - 8;
                        setTile(core, 1, acc.esz(), r, c, acc.encode().applyAsLong(initial[r][c]));
                    }
                }
                run(core, tmopWord(spec.base(), 1, ZN, ZM, ZK, idx));
                int bitsPerColumn = spec.lanes() == 1 ? 2 : 4;
                long segment = (long) idx * e * bitsPerColumn;
                for (int r = 0; r < e; r++) {
                    for (int c = 0; c < e; c++) {
                        double expected = initial[r][c] + fpTmopRowTimesColumn(core, spec, ZK, segment, r, c);
                        assertEquals(acc.encode().applyAsLong(expected), tile(core, 1, acc.esz(), r, c),
                                Integer.toHexString(spec.base()) + " idx=" + idx + " [" + r + "][" + c + "]");
                    }
                }
            }
        }
    }

    // ── fp8 e cobertura das 31 formas ────────────────────────────────────────────────────────────

    @Test
    void fp8PairTmopSelectsTwoBytesPerElementFromZk() {
        Aarch64Core core = core(256);
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(ZK, w, -1L);
        }
        for (int i = 0; i < elementsOf(core, ESZ_HALF); i++) {
            setElem(core, ZM, ESZ_HALF, i, E5M2_ONE * 0x0101L);
        }
        for (int i = 0; i < elementsOf(core, ESZ_BYTE); i++) {
            setElem(core, ZN, ESZ_BYTE, i, E5M2_ONE);
            setElem(core, ZN + 1, ESZ_BYTE, i, E5M2_ONE);
        }
        run(core, tmopWord(FTMOPA_HB, 1, ZN, ZM, ZK, 0));
        assertEquals(Float.floatToFloat16(2f) & 0xFFFFL, tile(core, 1, ESZ_HALF, 4, 9), "2 produtos 1*1");
    }

    @ParameterizedTest
    @ValueSource(ints = {FMOP4A_SB, FMOP4A_HB, BFMOP4_HH, FMOP4_HH, FMOP4_SS, FMOP4_DD, BFMOP4_SH, FMOP4_SH,
            SMOP4_SH, UMOP4_SH, SMOP4_SB, SMOP4_DH, SUMOP4_SB, SUMOP4_DH, UMOP4_SB, UMOP4_DH, USMOP4_SB, USMOP4_DH})
    void everyMop4FormWritesItsTile(int base) {
        Aarch64Core core = core(256);
        for (int z : new int[] {ZN, ZN + 1, ZM, ZM + 1}) {
            for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
                core.scalable().setZWord(z, w, 0x3C3C_3C3C_3C3C_3C3CL);
            }
        }
        int tile = 1;
        run(core, mop4Word(base, tile, ZN, ZM, false, true, true));
        int esz = base == FMOP4A_HB || base == BFMOP4_HH || base == FMOP4_HH ? ESZ_HALF
                : base == FMOP4_DD || (base & 0xA0000000) == 0xA0000000 ? ESZ_DOUBLE : ESZ_SINGLE;
        assertNotEquals(0L, tile(core, tile, esz, 1, 1), Integer.toHexString(base));
    }

    @ParameterizedTest
    @ValueSource(ints = {FTMOPA_SB, FTMOPA_HB, BFTMOPA_HH, FTMOPA_HH, FTMOPA_SS, BFTMOPA_SH, FTMOPA_SH, STMOPA_SH,
            UTMOPA_SH, STMOPA_SB, SUTMOPA_SB, USTMOPA_SB, UTMOPA_SB})
    void everyTmopFormWritesItsTile(int base) {
        Aarch64Core core = core(256);
        for (int z : new int[] {ZN, ZN + 1, ZM}) {
            for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
                core.scalable().setZWord(z, w, 0x3C3C_3C3C_3C3C_3C3CL);
            }
        }
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(ZK, w, -1L);
        }
        int esz = base == FTMOPA_HB || base == BFTMOPA_HH || base == FTMOPA_HH ? ESZ_HALF : ESZ_SINGLE;
        run(core, tmopWord(base, 1, ZN, ZM, ZK, 0));
        assertNotEquals(0L, tile(core, 1, esz, 1, 1), Integer.toHexString(base));
    }

    // ── acesso ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void outsideStreamingModeBothFamiliesEnterTheSmeTrap() {
        for (int word : new int[] {mop4Word(FMOP4_SS, 0, ZN, ZM, false, false, false),
                tmopWord(FTMOPA_SS, 0, ZN, ZM, ZK, 0)}) {
            Aarch64Core core = core(256);
            core.setSvcr(SVCR_ZA);
            run(core, word);
            assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
        }
    }

    @Test
    void withZaDisabledBothFamiliesEnterTheSmeTrap() {
        for (int word : new int[] {mop4Word(SMOP4_SB, 0, ZN, ZM, false, false, false),
                tmopWord(STMOPA_SB, 0, ZN, ZM, ZK, 0)}) {
            Aarch64Core core = core(256);
            core.setSvcr(SVCR_SM);
            run(core, word);
            assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
        }
    }

    private static long signExtend(long raw, int bits) {
        int shift = 64 - bits;
        return (raw << shift) >> shift;
    }
}
