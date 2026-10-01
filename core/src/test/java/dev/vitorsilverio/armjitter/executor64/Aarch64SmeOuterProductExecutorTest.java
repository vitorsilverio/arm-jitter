package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.5 — `ADDHA`/`ADDVA` e produto externo. As palavras são montadas campo a campo por {@link #word} a partir das
/// bases cujo decode (e a conferência contra `aarch64-none-elf-as`) está em `Aarch64SmeOuterProductDecoderTest`.
/// As referências de cada teste são REESCRITAS aqui em Java direto (vetores de bytes/halfwords, sem o empacotamento
/// de lanes do executor), não copiadas da implementação.
class Aarch64SmeOuterProductExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-op-exec-ALL", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_I16I64,
            Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16, Aarch64Feature.SME_B16B16,
            Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;

    private static final int ADDHA_S = 0xC0900000;
    private static final int ADDVA_S = 0xC0910000;
    private static final int ADDHA_D = 0xC0D00000;
    private static final int ADDVA_D = 0xC0D10000;
    private static final int FMOPA_H = 0x81800008;
    private static final int BFMOPA = 0x81A00008;
    private static final int FMOPA_S = 0x80800000;
    private static final int FMOPA_D = 0x80C00000;
    private static final int FMOPA_W_H = 0x81A00000;
    private static final int BFMOPA_W = 0x81800000;
    private static final int FMOPA_SB = 0x80A00000;
    private static final int FMOPA_HB = 0x80A00008;
    private static final int SMOPA_S = 0xA0800000;
    private static final int SUMOPA_S = 0xA0A00000;
    private static final int USMOPA_S = 0xA1800000;
    private static final int UMOPA_S = 0xA1A00000;
    private static final int SMOPA_D = 0xA0C00000;
    private static final int SUMOPA_D = 0xA0E00000;
    private static final int USMOPA_D = 0xA1C00000;
    private static final int UMOPA_D = 0xA1E00000;
    private static final int BMOPA = 0x80800008;
    private static final int SMOPA2_S = 0xA0800008;
    private static final int UMOPA2_S = 0xA1800008;
    private static final int SUBTRACT_BIT = 1 << 4;

    private static final int PN = 1;
    private static final int PM = 2;
    private static final int ZN = 3;
    private static final int ZM = 4;

    private static int word(int base, int tile, int zn, int zm, int pn, int pm, boolean subtract) {
        return base | zm << 16 | pm << 13 | pn << 10 | zn << 5 | (subtract ? SUBTRACT_BIT : 0) | tile;
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

    private static int elements(Aarch64Core core, int esz) {
        return core.streamingVectorLengthBytes() >>> esz;
    }

    private static void setZ(Aarch64Core core, int z, int esz, long... values) {
        for (int i = 0; i < values.length; i++) {
            SvePredicateOps.setElementOf(core.scalable(), z, i, esz, values[i]);
        }
    }

    private static void fillZ(Aarch64Core core, int z, int esz, Random random) {
        for (int i = 0; i < elements(core, esz); i++) {
            SvePredicateOps.setElementOf(core.scalable(), z, i, esz, random.nextLong());
        }
    }

    private static void allTrue(Aarch64Core core, int pg) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerPredicate(); w++) {
            regs.setPWord(pg, w, -1L);
        }
    }

    private static void randomPredicate(Aarch64Core core, int pg, Random random) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerPredicate(); w++) {
            regs.setPWord(pg, w, random.nextLong());
        }
    }

    /// Bit `bit` do predicado `pg` (um bit por BYTE do vetor).
    private static boolean predicateBit(Aarch64Core core, int pg, int bit) {
        return ((core.scalable().pWord(pg, bit >>> 6) >>> (bit & 63)) & 1L) != 0L;
    }

    private static void clearPredicateBit(Aarch64Core core, int pg, int bit) {
        Aarch64ScalableRegisters regs = core.scalable();
        regs.setPWord(pg, bit >>> 6, regs.pWord(pg, bit >>> 6) & ~(1L << (bit & 63)));
    }

    private static long tile(Aarch64Core core, int tile, int esz, int row, int col) {
        return SmeMovaOps.zaElement(core.matrix(), Aarch64MatrixTileAddressing.rowIndex(tile, esz, row), col << esz,
                esz);
    }

    private static void setTile(Aarch64Core core, int tile, int esz, int row, int col, long value) {
        SmeMovaOps.setZaElement(core.matrix(), Aarch64MatrixTileAddressing.rowIndex(tile, esz, row), col << esz, esz,
                value);
    }

    private static void setFpcr(Aarch64Core core, long value) {
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, value);
    }

    private static long fpsr(Aarch64Core core) {
        return core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR);
    }

    private static long f32(float value) {
        return Float.floatToRawIntBits(value) & 0xFFFF_FFFFL;
    }

    private static long f16(float value) {
        return Float.floatToFloat16(value) & 0xFFFFL;
    }

    private static long bf16(float value) {
        return (Float.floatToRawIntBits(value) >>> 16) & 0xFFFFL;
    }

    // ── ADDHA / ADDVA ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void addhaAddsTheColumnVectorToEveryActiveRowAndAddvaTheRowElement(int svlBits) {
        Aarch64Core core = core(svlBits);
        int n = elements(core, ESZ_SINGLE);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < n; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, 100L * (i + 1));
        }
        run(core, word(ADDHA_S, 1, ZN, 0, PN, PM, false));
        run(core, word(ADDVA_S, 2, ZN, 0, PN, PM, false));
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                assertEquals(100L * (col + 1), tile(core, 1, ESZ_SINGLE, row, col), "ADDHA " + row + "," + col);
                assertEquals(100L * (row + 1), tile(core, 2, ESZ_SINGLE, row, col), "ADDVA " + row + "," + col);
            }
        }
    }

    @Test
    void addhaWrapsAt32BitsAndAccumulates() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_SINGLE, 0xFFFF_FFFFL, 0xFFFF_FFFFL, 0xFFFF_FFFFL, 0xFFFF_FFFFL, 0xFFFF_FFFFL, 0xFFFF_FFFFL,
                0xFFFF_FFFFL, 0xFFFF_FFFFL);
        run(core, word(ADDHA_S, 0, ZN, 0, PN, PM, false));
        run(core, word(ADDHA_S, 0, ZN, 0, PN, PM, false));
        assertEquals(0xFFFF_FFFEL, tile(core, 0, ESZ_SINGLE, 3, 5), "-1 + -1 = -2 (32 bits, sem vazar para a lane vizinha)");
        assertEquals(0xFFFF_FFFEL, tile(core, 0, ESZ_SINGLE, 3, 6));
    }

    @Test
    void addhaDoubleAndAddvaDoubleUseEightTiles() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_DOUBLE, 10L, 20L, 30L, 40L);
        run(core, word(ADDHA_D, 7, ZN, 0, PN, PM, false));
        run(core, word(ADDVA_D, 6, ZN, 0, PN, PM, false));
        assertEquals(30L, tile(core, 7, ESZ_DOUBLE, 1, 2));
        assertEquals(20L, tile(core, 6, ESZ_DOUBLE, 1, 2));
        assertEquals(0L, tile(core, 5, ESZ_DOUBLE, 1, 2), "outro tile intacto");
    }

    @Test
    void addhaRowPredicateAndColumnPredicateAreIndependent() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_SINGLE, 1, 1, 1, 1, 1, 1, 1, 1);
        clearPredicateBit(core, PN, 3 * 4); // linha 3 inativa
        clearPredicateBit(core, PM, 5 * 4); // coluna 5 inativa
        run(core, word(ADDHA_S, 0, ZN, 0, PN, PM, false));
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                long expected = row == 3 || col == 5 ? 0L : 1L;
                assertEquals(expected, tile(core, 0, ESZ_SINGLE, row, col), row + "," + col);
            }
        }
    }

    // ── FMOPA: o sgemm de brinquedo ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void fmopaSingleComputesTheOuterProductAndAccumulatesTwice(int svlBits) {
        Aarch64Core core = core(svlBits);
        int n = elements(core, ESZ_SINGLE);
        allTrue(core, PN);
        allTrue(core, PM);
        float[] a = new float[n];
        float[] b = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = i + 1;
            b[i] = 0.5f * (i + 2);
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, f32(a[i]));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, f32(b[i]));
        }
        run(core, word(FMOPA_S, 3, ZN, ZM, PN, PM, false));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                assertEquals(f32(a[i] * b[j]), tile(core, 3, ESZ_SINGLE, i, j), "C[" + i + "][" + j + "]");
            }
        }
        run(core, word(FMOPA_S, 3, ZN, ZM, PN, PM, false));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                assertEquals(f32(2 * a[i] * b[j]), tile(core, 3, ESZ_SINGLE, i, j), "2x C[" + i + "][" + j + "]");
            }
        }
        assertEquals(0L, tile(core, 2, ESZ_SINGLE, 1, 1), "outro tile intacto");
    }

    @Test
    void fmopsSubtractsFromTheAccumulator() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, f32(2f));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, f32(3f));
            for (int j = 0; j < 8; j++) {
                setTile(core, 0, ESZ_SINGLE, i, j, f32(10f));
            }
        }
        run(core, word(FMOPA_S, 0, ZN, ZM, PN, PM, true));
        assertEquals(f32(4f), tile(core, 0, ESZ_SINGLE, 2, 6));
    }

    @Test
    void fmopaRowPredicateErasesARowAndColumnPredicateAColumn() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, f32(1f));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, f32(1f));
        }
        clearPredicateBit(core, PN, 2 * 4);
        clearPredicateBit(core, PM, 6 * 4);
        run(core, word(FMOPA_S, 1, ZN, ZM, PN, PM, false));
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(row == 2 || col == 6 ? 0L : f32(1f), tile(core, 1, ESZ_SINGLE, row, col),
                        row + "," + col);
            }
        }
    }

    @Test
    void fmopaDoubleUsesTheEightTilesAndFusesTheMultiplyAdd() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        double x = 1.0 + Math.ulp(1.0); // x*x = 1 + 2ulp + ulp^2: o produto fundido mantém o ulp^2
        long xBits = Double.doubleToRawLongBits(x);
        for (int i = 0; i < 4; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_DOUBLE, xBits);
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_DOUBLE, xBits);
        }
        setTile(core, 7, ESZ_DOUBLE, 1, 2, Double.doubleToRawLongBits(-1.0));
        run(core, word(FMOPA_D, 7, ZN, ZM, PN, PM, false));
        assertEquals(Double.doubleToRawLongBits(Math.fma(x, x, -1.0)), tile(core, 7, ESZ_DOUBLE, 1, 2),
                "um único arredondamento (fusão)");
        assertEquals(Double.doubleToRawLongBits(x * x), tile(core, 7, ESZ_DOUBLE, 0, 0));
    }

    @Test
    void halfPrecisionOuterProductsAccumulateInHalfTiles() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        int n = elements(core, ESZ_HALF);
        for (int i = 0; i < n; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_HALF, f16(1.5f));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_HALF, f16(2f));
        }
        run(core, word(FMOPA_H, 1, ZN, ZM, PN, PM, false));
        run(core, word(FMOPA_H, 1, ZN, ZM, PN, PM, true));
        run(core, word(FMOPA_H, 0, ZN, ZM, PN, PM, false));
        assertEquals(0L, tile(core, 1, ESZ_HALF, 3, 4), "+3 depois -3");
        assertEquals(f16(3f), tile(core, 0, ESZ_HALF, 3, 4));
        for (int i = 0; i < n; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_HALF, bf16(1.5f));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_HALF, bf16(2f));
        }
        run(core, word(BFMOPA, 1, ZN, ZM, PN, PM, false));
        assertEquals(bf16(3f), tile(core, 1, ESZ_HALF, 15, 15));
    }

    @Test
    void fpControlRegisterRoundingModeIsHonoured() {
        long roundUp = 1L << 22;
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        float tiny = (float) Math.scalb(1.0, -15);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, f32(tiny));
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, f32(tiny));
        }
        setTile(core, 0, ESZ_SINGLE, 0, 0, f32(1f));
        run(core, word(FMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(1f), tile(core, 0, ESZ_SINGLE, 0, 0), "ao mais próximo: 1 + 2^-30 = 1");
        setTile(core, 1, ESZ_SINGLE, 0, 0, f32(1f));
        setFpcr(core, roundUp);
        run(core, word(FMOPA_S, 1, ZN, ZM, PN, PM, false));
        assertEquals(f32(1f) + 1, tile(core, 1, ESZ_SINGLE, 0, 0), "RMode = +inf: sobe 1 ulp");
    }

    @Test
    void zaFloatingPointAlwaysYieldsTheDefaultNanAndNeverTouchesFpsr() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, 0x7F80_0000L); // +inf
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, 0L); // 0: inf * 0 é inválido
        }
        setFpcr(core, 0L); // DN = 0 no FPCR — a ZA ignora e usa o NaN padrão do mesmo jeito
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0L); // entrar em streaming já pôs 0x0800009F
        run(core, word(FMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(0x7FC0_0000L, tile(core, 0, ESZ_SINGLE, 0, 0));
        assertEquals(0L, fpsr(core), "as flags da ZA (IOC aqui) não acumulam em FPSR");
    }

    // ── FMOPA widening (binary16/bfloat16 → binary32) ────────────────────────────────────────────

    private static void setHalfPairs(Aarch64Core core, int z, long lo, long hi) {
        for (int i = 0; i < elements(core, ESZ_SINGLE); i++) {
            SvePredicateOps.setElementOf(core.scalable(), z, i, ESZ_SINGLE, lo | hi << 16);
        }
    }

    @Test
    void fmopaWideningHalfSumsTwoProductsPerElement() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setHalfPairs(core, ZN, f16(1f), f16(2f));
        setHalfPairs(core, ZM, f16(3f), f16(4f));
        setTile(core, 2, ESZ_SINGLE, 1, 1, f32(100f));
        run(core, word(FMOPA_W_H, 2, ZN, ZM, PN, PM, false));
        assertEquals(f32(111f), tile(core, 2, ESZ_SINGLE, 1, 1), "100 + 1*3 + 2*4 (dois produtos, não um)");
        run(core, word(FMOPA_W_H, 2, ZN, ZM, PN, PM, true));
        assertEquals(f32(100f), tile(core, 2, ESZ_SINGLE, 1, 1), "FMOPS subtrai os dois");
    }

    @Test
    void fmopaWideningHalfPredicateSelectsEachHalfSeparately() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setHalfPairs(core, ZN, f16(1f), f16(2f));
        setHalfPairs(core, ZM, f16(3f), f16(4f));
        clearPredicateBit(core, PN, 2 * 4 + 2); // linha 2: metade ALTA de Zn inativa (vira +0)
        clearPredicateBit(core, PM, 5 * 4); // coluna 5: metade BAIXA de Zm inativa
        run(core, word(FMOPA_W_H, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(11f), tile(core, 0, ESZ_SINGLE, 0, 0));
        assertEquals(f32(3f), tile(core, 0, ESZ_SINGLE, 2, 0), "só o produto baixo: 1*3");
        assertEquals(f32(8f), tile(core, 0, ESZ_SINGLE, 0, 5), "só o produto alto: 2*4");
        assertEquals(0f, Float.intBitsToFloat((int) tile(core, 0, ESZ_SINGLE, 2, 5)), "nenhuma metade comum ativa");
    }

    @Test
    void fmopaWideningHalfTurnsAnInfinityTimesAnInactiveHalfIntoAnInvalidNan() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setHalfPairs(core, ZN, 0x7C00L, f16(1f)); // +inf, 1
        setHalfPairs(core, ZM, f16(1f), f16(1f));
        clearPredicateBit(core, PM, 0); // metade baixa de Zm na coluna 0 inativa: vira 0 -> inf * 0
        run(core, word(FMOPA_W_H, 0, ZN, ZM, PN, PM, false));
        assertEquals(0x7FC0_0000L, tile(core, 0, ESZ_SINGLE, 0, 0));
        assertEquals(0x7F80_0000L, tile(core, 0, ESZ_SINGLE, 0, 1), "coluna 1: inf*1 + 1*1 = inf");
    }

    @Test
    void bfloatWideningUsesRoundToOddUnlessEbfIsSet() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        long tiny = bf16((float) Math.scalb(1.0, -15));
        setHalfPairs(core, ZN, tiny, 0L);
        setHalfPairs(core, ZM, tiny, 0L);
        setTile(core, 0, ESZ_SINGLE, 0, 0, f32(1f));
        run(core, word(BFMOPA_W, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(1f) + 1, tile(core, 0, ESZ_SINGLE, 0, 0), "EBF = 0: soma inexata em arredondamento ímpar");
        setFpcr(core, 1L << 13); // FPCR.EBF
        setTile(core, 1, ESZ_SINGLE, 0, 0, f32(1f));
        run(core, word(BFMOPA_W, 1, ZN, ZM, PN, PM, false));
        assertEquals(f32(1f), tile(core, 1, ESZ_SINGLE, 0, 0), "EBF = 1: ao mais próximo (soma fundida)");
    }

    @Test
    void bfloatWideningSumsTwoProductsAndSubtracts() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setHalfPairs(core, ZN, bf16(1f), bf16(2f));
        setHalfPairs(core, ZM, bf16(3f), bf16(4f));
        run(core, word(BFMOPA_W, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(11f), tile(core, 0, ESZ_SINGLE, 4, 4));
        run(core, word(BFMOPA_W, 0, ZN, ZM, PN, PM, true));
        assertEquals(0L, tile(core, 0, ESZ_SINGLE, 4, 4));
        setFpcr(core, 1L << 13);
        run(core, word(BFMOPA_W, 1, ZN, ZM, PN, PM, false));
        assertEquals(f32(11f), tile(core, 1, ESZ_SINGLE, 4, 4));
    }

    // ── FMOPA fp8 ────────────────────────────────────────────────────────────────────────────────

    private static final long E5M2_ONE = 0x3CL; // 0 01111 00

    @Test
    void fp8QuadAccumulatesFourProductsAndMasksPerByte() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, E5M2_ONE * 0x0101_0101L);
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, E5M2_ONE * 0x0101_0101L);
        }
        run(core, word(FMOPA_SB, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(4f), tile(core, 0, ESZ_SINGLE, 0, 0), "4 produtos 1*1");
        clearPredicateBit(core, PN, 4 + 1); // linha 1, byte 1 inativo
        clearPredicateBit(core, PN, 4 + 3); // linha 1, byte 3 inativo
        run(core, word(FMOPA_SB, 1, ZN, ZM, PN, PM, false));
        assertEquals(f32(2f), tile(core, 1, ESZ_SINGLE, 1, 0), "só 2 bytes ativos");
        assertEquals(f32(4f), tile(core, 1, ESZ_SINGLE, 0, 0));
    }

    @Test
    void fp8PairAccumulatesInHalfTiles() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 16; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_HALF, E5M2_ONE * 0x0101L);
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_HALF, E5M2_ONE * 0x0101L);
        }
        run(core, word(FMOPA_HB, 1, ZN, ZM, PN, PM, false));
        assertEquals(f16(2f), tile(core, 1, ESZ_HALF, 7, 9), "2 produtos 1*1");
    }

    @Test
    void fp8SourceFormatsComeFromFpmrAndAreNotSwapped() {
        Aarch64Core core = core(256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPMR, 1L); // F8S1 = E4M3, F8S2 = E5M2
        allTrue(core, PN);
        allTrue(core, PM);
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZN, i, ESZ_SINGLE, 0x38L * 0x0101_0101L); // E4M3: 1.0
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, E5M2_ONE * 0x0101_0101L); // E5M2: 1.0
        }
        run(core, word(FMOPA_SB, 0, ZN, ZM, PN, PM, false));
        assertEquals(f32(4f), tile(core, 0, ESZ_SINGLE, 0, 0), "trocar os formatos daria 4 × 0,75");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPMR, 9L); // F8S1 = F8S2 = E4M3
        for (int i = 0; i < 8; i++) {
            SvePredicateOps.setElementOf(core.scalable(), ZM, i, ESZ_SINGLE, 0x38L * 0x0101_0101L);
        }
        run(core, word(FMOPA_SB, 1, ZN, ZM, PN, PM, false));
        assertEquals(f32(4f), tile(core, 1, ESZ_SINGLE, 0, 0), "os dois fontes em E4M3");
    }

    @ParameterizedTest
    @ValueSource(ints = {ADDVA_S, ADDVA_D, FMOPA_H, BFMOPA, FMOPA_D, BFMOPA_W, FMOPA_SB, FMOPA_HB, FMOPA_W_H, FMOPA_S})
    void anInactiveRowPredicateLeavesTheWholeTileUntouched(int base) {
        Aarch64Core core = core(256);
        for (int z : new int[] {ZN, ZM}) {
            for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
                core.scalable().setZWord(z, w, 0x3C3C_3C3C_3C3C_3C3CL);
            }
        }
        allTrue(core, PM); // PN fica zerado: nenhuma linha ativa
        int zm = base == ADDVA_S || base == ADDVA_D ? 0 : ZM; // em ADDVA o campo de `Zm` é parte do opcode
        run(core, word(base, 1, ZN, zm, PN, PM, false));
        for (int w = 0; w < core.matrix().zaRowBytes() / Long.BYTES * core.streamingVectorLengthBytes(); w++) {
            assertEquals(0L, core.matrix().zaWord(w), "palavra " + w);
        }
    }

    // ── produtos inteiros ────────────────────────────────────────────────────────────────────────

    private static long lane(long value, int index, int bits, boolean signed) {
        long raw = (value >>> (index * bits)) & ((1L << bits) - 1);
        int shift = 64 - bits;
        return signed ? (raw << shift) >> shift : raw;
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void byteProductsSumFourLanesPerElementForEverySignedness(int svlBits) {
        int[] bases = {SMOPA_S, SUMOPA_S, USMOPA_S, UMOPA_S};
        boolean[] nSigned = {true, true, false, false};
        boolean[] mSigned = {true, false, true, false};
        for (int variant = 0; variant < bases.length; variant++) {
            for (boolean subtract : new boolean[] {false, true}) {
                Random random = new Random(1234L + variant);
                Aarch64Core core = core(svlBits);
                int n = elements(core, ESZ_SINGLE);
                fillZ(core, ZN, ESZ_SINGLE, random);
                fillZ(core, ZM, ESZ_SINGLE, random);
                randomPredicate(core, PN, random);
                randomPredicate(core, PM, random);
                long[][] initial = new long[n][n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        initial[i][j] = random.nextInt() & 0xFFFF_FFFFL;
                        setTile(core, 2, ESZ_SINGLE, i, j, initial[i][j]);
                    }
                }
                run(core, word(bases[variant], 2, ZN, ZM, PN, PM, subtract));
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        long sum = 0;
                        for (int k = 0; k < 4; k++) {
                            boolean active = predicateBit(core, PN, 4 * i + k) && predicateBit(core, PM, 4 * j + k);
                            if (active) {
                                long nv = lane(SvePredicateOps.elementOf(core.scalable(), ZN, i, ESZ_SINGLE), k, 8,
                                        nSigned[variant]);
                                long mv = lane(SvePredicateOps.elementOf(core.scalable(), ZM, j, ESZ_SINGLE), k, 8,
                                        mSigned[variant]);
                                sum += nv * mv;
                            }
                        }
                        long expected = (subtract ? initial[i][j] - sum : initial[i][j] + sum) & 0xFFFF_FFFFL;
                        assertEquals(expected, tile(core, 2, ESZ_SINGLE, i, j),
                                "variante " + variant + (subtract ? " sub " : " add ") + i + "," + j);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void halfwordProductsSumFourLanesIn64Bits(int svlBits) {
        int[] bases = {SMOPA_D, SUMOPA_D, USMOPA_D, UMOPA_D};
        boolean[] nSigned = {true, true, false, false};
        boolean[] mSigned = {true, false, true, false};
        for (int variant = 0; variant < bases.length; variant++) {
            boolean subtract = variant % 2 == 1; // SUMOPA/UMOPA exercitam o ramo de subtração
            Random random = new Random(99L + variant);
            Aarch64Core core = core(svlBits);
            int n = elements(core, ESZ_DOUBLE);
            fillZ(core, ZN, ESZ_DOUBLE, random);
            fillZ(core, ZM, ESZ_DOUBLE, random);
            randomPredicate(core, PN, random);
            randomPredicate(core, PM, random);
            long[][] initial = new long[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    initial[i][j] = random.nextLong();
                    setTile(core, 5, ESZ_DOUBLE, i, j, initial[i][j]);
                }
            }
            run(core, word(bases[variant], 5, ZN, ZM, PN, PM, subtract));
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    long sum = 0;
                    for (int k = 0; k < 4; k++) {
                        // halfword k de um elemento de 64 bits começa no byte 2k: o bit de predicado é o `8i + 2k`
                        if (predicateBit(core, PN, 8 * i + 2 * k) && predicateBit(core, PM, 8 * j + 2 * k)) {
                            sum += lane(SvePredicateOps.elementOf(core.scalable(), ZN, i, ESZ_DOUBLE), k, 16,
                                    nSigned[variant])
                                    * lane(SvePredicateOps.elementOf(core.scalable(), ZM, j, ESZ_DOUBLE), k, 16,
                                    mSigned[variant]);
                        }
                    }
                    assertEquals(subtract ? initial[i][j] - sum : initial[i][j] + sum, tile(core, 5, ESZ_DOUBLE, i, j),
                            "variante " + variant + " " + i + "," + j);
                }
            }
        }
    }

    @Test
    void widenedProductsDistinguishSumOfFourFromASingleProduct() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_SINGLE, 0x04030201L);
        setZ(core, ZM, ESZ_SINGLE, 0x08070605L);
        run(core, word(SMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(1 * 5 + 2 * 6 + 3 * 7 + 4 * 8, tile(core, 0, ESZ_SINGLE, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void twoWayHalfwordProductsSumTwoLanesIn32Bits(int svlBits) {
        for (boolean signed : new boolean[] {true, false}) {
            Random random = new Random(signed ? 5L : 6L);
            Aarch64Core core = core(svlBits);
            int n = elements(core, ESZ_SINGLE);
            fillZ(core, ZN, ESZ_SINGLE, random);
            fillZ(core, ZM, ESZ_SINGLE, random);
            randomPredicate(core, PN, random);
            randomPredicate(core, PM, random);
            boolean subtract = !signed; // SMOPA2 acumula, UMOPA2 subtrai: os dois ramos
            run(core, word(signed ? SMOPA2_S : UMOPA2_S, 1, ZN, ZM, PN, PM, subtract));
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    long sum = 0;
                    for (int k = 0; k < 2; k++) {
                        if (predicateBit(core, PN, 4 * i + 2 * k) && predicateBit(core, PM, 4 * j + 2 * k)) {
                            sum += lane(SvePredicateOps.elementOf(core.scalable(), ZN, i, ESZ_SINGLE), k, 16, signed)
                                    * lane(SvePredicateOps.elementOf(core.scalable(), ZM, j, ESZ_SINGLE), k, 16,
                                    signed);
                        }
                    }
                    assertEquals((subtract ? -sum : sum) & 0xFFFF_FFFFL, tile(core, 1, ESZ_SINGLE, i, j), i + "," + j);
                }
            }
        }
    }

    @Test
    void bitOuterProductCountsEqualBitsAndSubtracts() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_SINGLE, 0xFFFF_FFFFL, 0L);
        setZ(core, ZM, ESZ_SINGLE, 0xFFFF_0000L, 0xFFFF_FFFFL);
        run(core, word(BMOPA, 1, ZN, ZM, PN, PM, false));
        assertEquals(16L, tile(core, 1, ESZ_SINGLE, 0, 0), "popcount(~(n ^ m)) = bits iguais");
        assertEquals(32L, tile(core, 1, ESZ_SINGLE, 0, 1));
        assertEquals(16L, tile(core, 1, ESZ_SINGLE, 1, 0));
        run(core, word(BMOPA, 1, ZN, ZM, PN, PM, true));
        assertEquals(0L, tile(core, 1, ESZ_SINGLE, 0, 1), "BMOPS subtrai");
        clearPredicateBit(core, PN, 0);
        run(core, word(BMOPA, 1, ZN, ZM, PN, PM, false));
        assertEquals(0L, tile(core, 1, ESZ_SINGLE, 0, 0), "linha inativa: sem efeito");
        assertEquals(16L, tile(core, 1, ESZ_SINGLE, 1, 0));
    }

    @Test
    void runningTheSameProductTwiceDoublesTheResultForIntegersToo() {
        Aarch64Core core = core(256);
        allTrue(core, PN);
        allTrue(core, PM);
        setZ(core, ZN, ESZ_SINGLE, 0x01010101L);
        setZ(core, ZM, ESZ_SINGLE, 0x02020202L);
        run(core, word(UMOPA_S, 0, ZN, ZM, PN, PM, false));
        run(core, word(UMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(16L, tile(core, 0, ESZ_SINGLE, 0, 0));
    }

    // ── acesso ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void outsideStreamingModeEntersTheSmeTrap() {
        Aarch64Core core = core(256);
        core.setSvcr(SVCR_ZA);
        run(core, word(FMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    @Test
    void withZaDisabledEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = core(256);
        core.setSvcr(SVCR_SM);
        run(core, word(SMOPA_S, 0, ZN, ZM, PN, PM, false));
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }
}
