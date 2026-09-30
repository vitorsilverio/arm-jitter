package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.23 — SVE2 FP: conversões FP8/BF16 (`F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/
/// `BF2CVTLT`, `FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT`), FP pairwise (`FADDP`/`FMAXNMP`/`FMINNMP`/`FMAXP`/`FMINP`),
/// matmul (`BFMMLA`/`FMMLA_s`/`FMMLA_d`/`FMMLA_sb`/`FMMLA_hb`), conversões "odd elements"
/// (`FCVTNT`/`FCVTLT`/`FCVTXNT`), `FLOGB`, multiply-add long (`FMLALB`/`T`/`FMLSLB`/`T`, `BFMLALB`/`T`/
/// `BFMLSLB`/`T`) e dot-product (`FDOT_zzzz`/`BFDOT_zzzz`, `FDOT_hb`/`FDOT_sb`), vetorial e indexado — 65
/// encodings. Palavras conferidas byte a byte contra `aarch64-none-elf-as` real (não à mão).
class Aarch64SveFp23Test {
    private static final int Z0 = 0;
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int Z9 = 9;
    private static final int P0 = 0;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2_1 =
            Aarch64Architecture.extending(SVE2, "teste-SVE2p1", Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture BF16 =
            Aarch64Architecture.extending(SVE2, "teste-BF16", Aarch64Feature.BFLOAT16);
    private static final Aarch64Architecture F8CVT =
            Aarch64Architecture.extending(SVE2, "teste-F8CVT", Aarch64Feature.FP8_CONVERT);
    private static final Aarch64Architecture F32MM =
            Aarch64Architecture.extending(SVE2, "teste-F32MM", Aarch64Feature.F32MM);
    private static final Aarch64Architecture F64MM =
            Aarch64Architecture.extending(SVE2, "teste-F64MM", Aarch64Feature.F64MM);
    private static final Aarch64Architecture FP8_FMA =
            Aarch64Architecture.extending(SVE2, "teste-FP8FMA", Aarch64Feature.FP8_FUSED_MULTIPLY_ADD);
    private static final Aarch64Architecture FP8_DOT2 =
            Aarch64Architecture.extending(SVE2, "teste-FP8DOT2", Aarch64Feature.FP8_DOT_PRODUCT_2WAY);
    private static final Aarch64Architecture FP8_DOT4 =
            Aarch64Architecture.extending(SVE2, "teste-FP8DOT4", Aarch64Feature.FP8_DOT_PRODUCT_4WAY);
    private static final Aarch64Architecture FP8_MM32 =
            Aarch64Architecture.extending(SVE2, "teste-FP8MM32", Aarch64Feature.FP8_MATRIX_MULTIPLY_FP32);
    private static final Aarch64Architecture FP8_MM16 =
            Aarch64Architecture.extending(SVE2, "teste-FP8MM16", Aarch64Feature.FP8_MATRIX_MULTIPLY_FP16);

    // ── Infra (mesmo padrão de Aarch64Sve2MiscTest) ─────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x400L);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static void setPredicateAllTrue(Aarch64Core core, int p) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(p, w, -1L);
        }
    }

    private static void clearVector(Aarch64Core core, int reg) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
    }

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static void setElement(Aarch64Core core, int reg, int esz, int index, long value) {
        int elementBits = bits(esz);
        long bitOffset = (long) index * elementBits;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1L;
        int word = (int) (bitOffset / 64);
        int shift = (int) (bitOffset % 64);
        long cleared = core.scalable().zWord(reg, word) & ~(mask << shift);
        core.scalable().setZWord(reg, word, cleared | ((value & mask) << shift));
    }

    private static long getElement(Aarch64Core core, int reg, int esz, int index) {
        int elementBits = bits(esz);
        long bitOffset = (long) index * elementBits;
        long shifted = core.scalable().zWord(reg, (int) (bitOffset / 64)) >>> (int) (bitOffset % 64);
        return elementBits == 64 ? shifted : shifted & ((1L << elementBits) - 1);
    }

    // ── F1CVT/F2CVT/F1CVTLT/F2CVTLT/BF1CVT/BF2CVT/BF1CVTLT/BF2CVTLT (widen de fp8) ──────────────────

    /// `F1CVT Zd.H, Zn.B` (conferido contra `aarch64-none-elf-as`: `65083020`).
    private static int f1cvtWord(int rd, int rn) {
        return 0x65083000 | (rn << 5) | rd;
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256})
    void f1cvtWidensFp8ToHalfAtEveryVl(int vl) {
        Aarch64Core core = core(F8CVT, vl);
        clearVector(core, Z1);
        // `E5M2` (formato padrão com `FPMR = 0`), bits `0_01111_00` = `1.0`.
        setElement(core, Z1, 0, 0, 0x3CL);
        setElement(core, Z1, 0, 1, 0x3CL);
        run(F8CVT, core, f1cvtWord(Z0, Z1));
        assertEquals(0x3C00L, getElement(core, Z0, 1, 0)); // `1.0` em `binary16`
    }

    @Test
    void f1cvtRefusedWithoutFeature() {
        assertNull(decodeOrNull(SVE2, f1cvtWord(Z0, Z1)), "sem FEAT_SVE_F8CVT recusa");
        assertInstanceOf(Ir64Op.SveFpConvertFp8.class, decodeOrNull(F8CVT, f1cvtWord(Z0, Z1)));
    }

    /// `F1CVT`/`F1CVTLT` leem bytes PAR/ÍMPAR distintos — Achado 5/7 da task (metade correta).
    @Test
    void f1cvtAndF1cvtltReadDifferentBytesOfThePair() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z1);
        setElement(core, Z1, 0, 0, 0x3CL); // byte par = 1.0 (E5M2)
        setElement(core, Z1, 0, 1, 0x40L); // byte ímpar = 2.0 (E5M2)
        run(F8CVT, core, f1cvtWord(Z0, Z1));
        assertEquals(0x3C00L, getElement(core, Z0, 1, 0)); // F1CVT leu o par: 1.0

        int f1cvtlt = 0x65093000 | (Z1 << 5) | Z0;
        clearVector(core, Z0);
        run(F8CVT, core, f1cvtlt);
        assertEquals(0x4000L, getElement(core, Z0, 1, 0)); // F1CVTLT leu o ímpar: 2.0
    }

    // ── FCVTN/BFCVTN/FCVTNB/FCVTNT (estreita para fp8) ──────────────────────────────────────────────

    /// `FCVTN Zd.B, {Zn.H, Zn+1.H}` (conferido: `650a3000`, `rn` cru `%rn_ax2` ⇒ par `Zn`/`Zn+1`).
    private static int fcvtnWord(int rd, int rnRaw) {
        return 0x650A3000 | (rnRaw << 6) | rd;
    }

    /// Aceite: lê o PAR `Zn`, `Zn+1` (linhas 1134-1137 do `sve.decode`).
    @Test
    void fcvtnReadsThePairZnAndZnPlus1() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z0);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z2, 1, 0, 0x3C00L); // Zn = 1.0
        setElement(core, Z3, 1, 0, 0x4000L); // Zn+1 = 2.0
        run(F8CVT, core, fcvtnWord(Z0, Z2 / 2)); // rnRaw × 2 = Z2
        assertEquals(0x3CL, getElement(core, Z0, 0, 0)); // fp8(1.0), E5M2
        assertEquals(0x40L, getElement(core, Z0, 0, 1)); // fp8(2.0), E5M2
    }

    /// `FCVTNB`/`FCVTNT`: `FCVTNB` escreve o byte BAIXO (zera o alto); `FCVTNT` só o ALTO (preserva o baixo).
    /// `FCVTNB`/`FCVTNT` leem o MESMO par `{Zn, Zn+1}` — `FCVTNB` escreve o byte BAIXO dos slots `H[2i]`/
    /// `H[2i+1]` (zerando o alto); `FCVTNT` escreve o byte ALTO dos MESMOS slots com o MESMO par-fonte,
    /// PRESERVANDO o baixo que `FCVTNB` já tinha escrito (idioma real: `FCVTNB` com um par de origem, depois
    /// `FCVTNT` com OUTRO par, empacota 4 `f32` num slot `H` de cada — aqui usamos o MESMO par duas vezes só
    /// para isolar a propriedade de preservação, medido contra `sve2_fcvtnb_bs`/`sve2_fcvtnt_bs` do QEMU real).
    @Test
    void fcvtnbWritesLowByteAndFcvtntPreservesIt() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z0);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z2, 2, 0, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        setElement(core, Z3, 2, 0, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        int fcvtnb = 0x650A3400 | (Z2 / 2 << 6);
        run(F8CVT, core, fcvtnb);
        assertEquals(0x3CL, getElement(core, Z0, 0, 0)); // slot H[0], byte baixo = fp8(1.0) (de Zn)
        assertEquals(0L, getElement(core, Z0, 0, 1)); // slot H[0], byte alto ZERADO
        assertEquals(0x40L, getElement(core, Z0, 0, 2)); // slot H[1], byte baixo = fp8(2.0) (de Zn+1)

        int fcvtnt = 0x650A3C00 | (Z2 / 2 << 6);
        run(F8CVT, core, fcvtnt);
        assertEquals(0x3CL, getElement(core, Z0, 0, 0), "FCVTNT preserva o byte baixo do slot H[0]");
        assertEquals(0x3CL, getElement(core, Z0, 0, 1), "FCVTNT escreve o byte alto do slot H[0] com fp8(Zn)");
        assertEquals(0x40L, getElement(core, Z0, 0, 2), "FCVTNT preserva o byte baixo do slot H[1]");
        assertEquals(0x40L, getElement(core, Z0, 0, 3), "FCVTNT escreve o byte alto do slot H[1] com fp8(Zn+1)");
    }

    // ── FADDP/FMAXNMP/FMINNMP/FMAXP/FMINP (pairwise) ────────────────────────────────────────────────

    private static int pairwiseWord(int xy, int z, int esz, int pg, int rm, int rd) {
        return 0x64108000 | (esz << 22) | (xy << 17) | (z << 16) | (pg << 10) | (rm << 5) | rd;
    }

    /// Aceite: predicada e por segmento de 128 bits — `VL >= 256` distingue de "vetor inteiro".
    @Test
    void faddpIsPredicatedAndPerSegment() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        // Segmento 0 (elementos 0-3 de S): Zdn = {1,2,3,4}; Segmento 1 (4-7): Zdn = {10,20,30,40}.
        for (int i = 0; i < 4; i++) {
            setElement(core, Z0, 2, i, Float.floatToRawIntBits(i + 1) & 0xFFFF_FFFFL);
            setElement(core, Z0, 2, 4 + i, Float.floatToRawIntBits((i + 1) * 10) & 0xFFFF_FFFFL);
            setElement(core, Z1, 2, i, 0L);
            setElement(core, Z1, 2, 4 + i, 0L);
        }
        run(SVE2, core, pairwiseWord(0b00, 0, 2, P0, Z1, Z0));
        // par(Zn[0],Zn[1])=1+2=3 no destino 0; par(Zm[0],Zm[1])=0 no destino 1 (segmento 0).
        assertEquals(3f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
        // Segmento 1: par(Zn[4],Zn[5])=10+20=30.
        assertEquals(30f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 4)));
    }

    // ── BFMMLA/FMMLA_s/FMMLA_d/FMMLA_sb/FMMLA_hb (matmul) ───────────────────────────────────────────

    private static int matmulWord(int kind2, int kind1, int rm, int rn, int rd) {
        return 0x6420E000 | (kind2 << 22) | (rm << 16) | (kind1 << 10) | (rn << 5) | rd;
    }

    @Test
    void fmmlaSAndDDecodeUnderTheirOwnFeatures() {
        int fmmlaS = matmulWord(0b10, 0b001, Z2, Z1, Z0);
        int fmmlaD = matmulWord(0b11, 0b001, Z2, Z1, Z0);
        assertNull(decodeOrNull(SVE2, fmmlaS), "sem F32MM recusa");
        assertInstanceOf(Ir64Op.SveFpMatrixMultiply.class, decodeOrNull(F32MM, fmmlaS));
        assertNull(decodeOrNull(SVE2, fmmlaD), "sem F64MM recusa");
        assertInstanceOf(Ir64Op.SveFpMatrixMultiply.class, decodeOrNull(F64MM, fmmlaD));
    }

    /// `FMMLA_s`: `Zda[i][j] += Σ_k Zn[i][k] × Zm[k][j]` (não fundida — `SveFloat.multiply`/`add` separados).
    @Test
    void fmmlaSComputesTwoByTwoMatrixMultiply() {
        Aarch64Core core = core(F32MM, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        // Zn = [[1,0],[0,1]] (identidade); Zm = [[2,3],[4,5]].
        setElement(core, Z1, 2, 0, Float.floatToRawIntBits(1f) & 0xFFFF_FFFFL);
        setElement(core, Z1, 2, 3, Float.floatToRawIntBits(1f) & 0xFFFF_FFFFL);
        setElement(core, Z2, 2, 0, Float.floatToRawIntBits(2f) & 0xFFFF_FFFFL);
        setElement(core, Z2, 2, 1, Float.floatToRawIntBits(3f) & 0xFFFF_FFFFL);
        setElement(core, Z2, 2, 2, Float.floatToRawIntBits(4f) & 0xFFFF_FFFFL);
        setElement(core, Z2, 2, 3, Float.floatToRawIntBits(5f) & 0xFFFF_FFFFL);
        run(F32MM, core, matmulWord(0b10, 0b001, Z2, Z1, Z0));
        // `Zm` é lido em PARES-COLUNA (`HELPER(fmmla_s)` real: `d[1] = n00·m10 + n01·m11`, não `m01`) — com
        // `Zn` identidade, `d[i][j] = Zm[colunaPar_j][linha_i]`.
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
        assertEquals(4f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 1)));
        assertEquals(3f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 2)));
        assertEquals(5f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 3)));
    }

    @Test
    void fmmlaSbAndHbDecodeUnderTheirOwnFeatures() {
        int fmmlaSb = matmulWord(0b00, 0b000, Z2, Z1, Z0);
        int fmmlaHb = matmulWord(0b01, 0b000, Z2, Z1, Z0);
        assertNull(decodeOrNull(SVE2, fmmlaSb));
        assertInstanceOf(Ir64Op.SveFpMatrixMultiply.class, decodeOrNull(FP8_MM32, fmmlaSb));
        assertNull(decodeOrNull(SVE2, fmmlaHb));
        assertInstanceOf(Ir64Op.SveFpMatrixMultiply.class, decodeOrNull(FP8_MM16, fmmlaHb));
    }

    // ── FCVTNT/FCVTLT/FCVTXNT (odd elements) + FCVTX_ds_m ───────────────────────────────────────────

    private static int oddWord(int a, int b, int c, int pg, int rn, int rd) {
        return 0x6400A000 | (a << 22) | (b << 18) | (c << 16) | (pg << 10) | (rn << 5) | rd;
    }

    /// `FCVTNT_sh`: escreve SÓ o elemento estreito ÍMPAR e PRESERVA o par.
    @Test
    void fcvtntPreservesTheOtherHalf() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z0, 1, 0, 0x1234L); // valor sentinela no elemento par (deve sobreviver)
        setElement(core, Z1, 2, 0, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        run(SVE2, core, oddWord(0b10, 0b0010, 0b00, P0, Z1, Z0)); // FCVTNT_sh_m
        assertEquals(0x1234L, getElement(core, Z0, 1, 0), "o elemento PAR não pode ser tocado");
        assertEquals(0x3C00L, getElement(core, Z0, 1, 1), "o elemento ÍMPAR recebe 1.0 em binary16");
    }

    /// `FCVTLT_hs`: lê o elemento estreito ÍMPAR e escreve o elemento largo inteiro.
    @Test
    void fcvtltReadsTheOddNarrowElement() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 1, 0, 0x4000L); // par = 2.0 (h) — NÃO deve ser lido
        setElement(core, Z1, 1, 1, 0x3C00L); // ímpar = 1.0 (h) — deve ser lido
        run(SVE2, core, oddWord(0b10, 0b0010, 0b01, P0, Z1, Z0)); // FCVTLT_hs_m
        assertEquals(Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    @Test
    void fcvtxDsMReusesTheExistingFcvtxKind() {
        int fcvtxDsM = 0x650AA000 | (P0 << 10) | (Z1 << 5) | Z0;
        Ir64Op decoded = decodeOrNull(SVE2, fcvtxDsM);
        assertInstanceOf(Ir64Op.SveFpUnary.class, decoded);
        assertEquals(Ir64Op.SveFpUnary.Op.FCVTX, ((Ir64Op.SveFpUnary) decoded).op());
    }

    // ── FLOGB ────────────────────────────────────────────────────────────────────────────────────────

    /// Aceite: `esz` em posições DIFERENTES em `_m` (bits[18:17]) e `_z` (bits[14:13]) — Armadilha 6.
    @Test
    void flogbMergingAndZeroingDecodeTheSameEszFromDifferentBitPositions() {
        int flogbMEszS = 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
        int flogbZEszS = 0x641E8000 | (2 << 13) | (P0 << 10) | (Z1 << 5) | Z0;
        Ir64Op m = decodeOrNull(SVE2, flogbMEszS);
        Ir64Op z = decodeOrNull(SVE2, flogbZEszS);
        assertInstanceOf(Ir64Op.SveFpLogB.class, m);
        assertInstanceOf(Ir64Op.SveFpLogB.class, z);
        assertEquals(2, ((Ir64Op.SveFpLogB) m).esz());
        assertEquals(2, ((Ir64Op.SveFpLogB) z).esz());
        assertTrue(!((Ir64Op.SveFpLogB) m).zeroing());
        assertTrue(((Ir64Op.SveFpLogB) z).zeroing());
    }

    @Test
    void flogbComputesTheUnbiasedExponent() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, Float.floatToRawIntBits(8.0f) & 0xFFFF_FFFFL); // 2^3
        int flogbMEszS = 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbMEszS);
        assertEquals(3L, getElement(core, Z0, 2, 0));
    }

    // ── FMLALB/FMLALT/FMLSLB/FMLSLT + BF (multiply-add-long widen), vetorial e indexado ────────────

    /// `FMLALB Zda.S, Zn.H, Zm.H` (conferido: `64a28020`).
    private static int mlalWord(int family, boolean subtract, boolean top, int rm, int rn, int rd) {
        return 0x64208000 | (family << 22) | ((subtract ? 1 : 0) << 13) | ((top ? 1 : 0) << 10) | (rm << 16)
                | (rn << 5) | rd;
    }

    @Test
    void fmlalbComputesFusedMultiplyAddLong() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 1, 0, 0x3C00L); // Zn.H[0] = 1.0
        setElement(core, Z2, 1, 0, 0x4000L); // Zm.H[0] = 2.0
        setElement(core, Z0, 2, 0, Float.floatToRawIntBits(0.5f) & 0xFFFF_FFFFL); // acc = 0.5
        run(SVE2, core, mlalWord(0b10, false, false, Z2, Z1, Z0));
        assertEquals(2.5f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0))); // 0.5 + 1.0*2.0
    }

    @Test
    void bfmlalRequiresBFloat16AndBfmlslRequiresSve21() {
        int bfmlalb = mlalWord(0b11, false, false, Z2, Z1, Z0);
        int bfmlslb = mlalWord(0b11, true, false, Z2, Z1, Z0);
        assertNull(decodeOrNull(SVE2, bfmlalb), "sem BFLOAT16 recusa BFMLALB");
        assertInstanceOf(Ir64Op.SveFpMultiplyAddLongWidenBFloat16.class, decodeOrNull(BF16, bfmlalb));
        assertNull(decodeOrNull(BF16, bfmlslb), "BFLOAT16 sozinho não basta para BFMLSLB");
        assertInstanceOf(Ir64Op.SveFpMultiplyAddLongWidenBFloat16.class, decodeOrNull(SVE2_1, bfmlslb));
    }

    /// Indexado: `Zm` fixo por segmento de 128 bits — `VL >= 256` distingue de "índice global".
    @Test
    void fmlalbIndexedReadsAFixedElementPerSegment() {
        int fmlalbIdx = 0x64204000 | (0b10 << 22) | (0 << 20) /* index high2 = 0 */ | (0 << 11) /* low = 0 */
                | (Z2 << 16) | (Z1 << 5) | Z0;
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        for (int e = 0; e < 8; e++) {
            setElement(core, Z0, 2, e, Float.floatToRawIntBits(0f) & 0xFFFF_FFFFL);
            setElement(core, Z1, 1, 2 * e, 0x3C00L); // Zn = 1.0 em todo lugar
        }
        setElement(core, Z2, 1, 0, 0x4000L); // segmento 0, índice 0: 2.0
        setElement(core, Z2, 1, 8, 0x4400L); // segmento 1, índice 0: 4.0
        run(SVE2, core, fmlalbIdx);
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
        assertEquals(4f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 4)), "segmento 1 usa o índice DELE");
    }

    // ── FDOT_zzzz/BFDOT_zzzz (dot-product widen) × FMLALB/FMLALT (Aceite: não confundir) ───────────

    private static int fdotWidenWord(int family, int rm, int rn, int rd) {
        return 0x64208000 | (family << 22) | (rm << 16) | (rn << 5) | rd;
    }

    /// Aceite: `FDOT_zzzz` (bit 10 = 0) não é confundido com `FMLALB_zzzw` (bit 10 = 0 também, mas
    /// bits[15:8] = `0x80` vs `0x84`+índice — na prática o bit que separa os DOIS GRUPOS é o 21/estrutura de
    /// opcode completa: aqui testamos que cada palavra produz o `Kind` certo).
    @Test
    void fdotZzzzIsNotConfusedWithFmlalbZzzw() {
        int fdotZzzz = fdotWidenWord(0b00, Z2, Z1, Z0);
        int fmlalbZzzw = mlalWord(0b10, false, false, Z2, Z1, Z0);
        assertInstanceOf(Ir64Op.SveFpDotProductWiden.class, decodeOrNull(SVE2_1, fdotZzzz));
        assertInstanceOf(Ir64Op.SveFpMultiplyAddLongWiden.class, decodeOrNull(SVE2, fmlalbZzzw));
    }

    @Test
    void bfdotZzzzIsNotConfusedWithFmlaltZzzw() {
        int bfdotZzzz = fdotWidenWord(0b01, Z2, Z1, Z0);
        int fmlaltZzzw = mlalWord(0b10, false, true, Z2, Z1, Z0);
        assertInstanceOf(Ir64Op.SveFpDotProductWidenBFloat16.class, decodeOrNull(BF16, bfdotZzzz));
        assertInstanceOf(Ir64Op.SveFpMultiplyAddLongWiden.class, decodeOrNull(SVE2, fmlaltZzzw));
    }

    @Test
    void fdotHbIsNotConfusedWithFmlaltZzzwOrFmlalb() {
        int fdotHb = 0x64208400 | (Z2 << 16) | (Z1 << 5) | Z0;
        int fdotSb = 0x64608400 | (Z2 << 16) | (Z1 << 5) | Z0;
        assertInstanceOf(Ir64Op.SveFp8DotProduct.class, decodeOrNull(FP8_DOT2, fdotHb));
        assertNull(decodeOrNull(SVE2, fdotHb), "sem FEAT_FP8DOT2 recusa");
        assertInstanceOf(Ir64Op.SveFp8DotProduct.class, decodeOrNull(FP8_DOT4, fdotSb));
        assertInstanceOf(Ir64Op.SveFpMultiplyAddLongWiden.class,
                decodeOrNull(SVE2, mlalWord(0b10, false, true, Z2, Z1, Z0)));
    }

    // ── FMLAL_hb/FMLALL_sb + FDOT_hb/FDOT_sb (fp8) ──────────────────────────────────────────────────

    private static int fmlalHbWord(int rm, int rn, int rd) {
        return 0x64A08800 | (rm << 16) | (rn << 5) | rd;
    }

    @Test
    void fmlalHbDecodesUnderFp8Fma() {
        int word = fmlalHbWord(Z2, Z1, Z0);
        assertNull(decodeOrNull(SVE2, word), "sem FEAT_FP8FMA recusa");
        assertInstanceOf(Ir64Op.SveFp8FusedMultiplyAddLong.class, decodeOrNull(FP8_FMA, word));
    }

    @Test
    void fdotHbAndSbDecodeUnderTheirOwnFeature() {
        int fdotHb = 0x64208400 | (Z2 << 16) | (Z1 << 5) | Z0;
        int fdotSb = 0x64608400 | (Z2 << 16) | (Z1 << 5) | Z0;
        assertInstanceOf(Ir64Op.SveFp8DotProduct.class, decodeOrNull(FP8_DOT2, fdotHb));
        assertNull(decodeOrNull(FP8_DOT4, fdotHb), "FP8DOT4 não habilita a forma de 2 vias");
        assertInstanceOf(Ir64Op.SveFp8DotProduct.class, decodeOrNull(FP8_DOT4, fdotSb));
    }

    /// `FMLAL_hb Zda.H, Zn.B, Zm.B`: 1.0 × 1.0 + 0.0 = 1.0 em `binary16` (`FPMR = 0` ⇒ `E5M2`, escala 0).
    @Test
    void fmlalHbComputesFusedMultiplyAdd() {
        Aarch64Core core = core(FP8_FMA, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 0, 0, 0x3CL); // fp8(1.0)
        setElement(core, Z2, 0, 0, 0x3CL); // fp8(1.0)
        run(FP8_FMA, core, fmlalHbWord(Z2, Z1, Z0));
        assertEquals(0x3C00L, getElement(core, Z0, 1, 0));
    }

    /// `FMLALL_sb Zda.S, Zn.B, Zm.B`: destino `binary32`.
    @Test
    void fmlallSbComputesFusedMultiplyAdd() {
        Aarch64Core core = core(FP8_FMA, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fmlallSb = 0x64208800 | (Z2 << 16) | (Z1 << 5) | Z0; // bits[23:22]=00 ⇒ sb
        setElement(core, Z1, 0, 0, 0x3CL);
        setElement(core, Z2, 0, 0, 0x3CL);
        run(FP8_FMA, core, fmlallSb);
        assertEquals(Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    /// `FMLAL_idx_hb`: `Zm` fixo por segmento de 128 bits.
    @Test
    void fmlalIdxHbUsesAFixedByteOfZmPerSegment() {
        Aarch64Core core = core(FP8_FMA, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fmlalIdxHb = 0x64205000 | (Z2 << 16) | (Z1 << 5) | Z0; // idxn=0, index=0
        setElement(core, Z1, 0, 0, 0x3CL); // fp8(1.0)
        setElement(core, Z2, 0, 0, 0x40L); // fp8(2.0), índice 0 do segmento
        run(FP8_FMA, core, fmlalIdxHb);
        assertEquals(0x4000L, getElement(core, Z0, 1, 0)); // 1.0 × 2.0 = 2.0
    }

    /// `FMLALL_idx_sb`.
    @Test
    void fmlallIdxSbUsesAFixedByteOfZmPerSegment() {
        Aarch64Core core = core(FP8_FMA, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fmlallIdxSb = 0x6420C000 | (Z2 << 16) | (Z1 << 5) | Z0; // idxn=00, index=0
        setElement(core, Z1, 0, 0, 0x3CL);
        setElement(core, Z2, 0, 0, 0x40L);
        run(FP8_FMA, core, fmlallIdxSb);
        assertEquals(Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    /// `FDOT_hb Zda.H, Zn.B, Zm.B`: soma 2 produtos fp8 fundidos.
    @Test
    void fdotHbComputesDotProduct() {
        Aarch64Core core = core(FP8_DOT2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fdotHb = 0x64208400 | (Z2 << 16) | (Z1 << 5) | Z0;
        setElement(core, Z1, 0, 0, 0x3CL); // 1.0
        setElement(core, Z1, 0, 1, 0x3CL); // 1.0
        setElement(core, Z2, 0, 0, 0x3CL); // 1.0
        setElement(core, Z2, 0, 1, 0x3CL); // 1.0
        run(FP8_DOT2, core, fdotHb);
        assertEquals(0x4000L, getElement(core, Z0, 1, 0)); // 1·1 + 1·1 = 2.0
    }

    /// `FDOT_sb Zda.S, Zn.B, Zm.B`: soma 4 produtos fp8 fundidos.
    @Test
    void fdotSbComputesDotProduct() {
        Aarch64Core core = core(FP8_DOT4, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fdotSb = 0x64608400 | (Z2 << 16) | (Z1 << 5) | Z0;
        for (int b = 0; b < 4; b++) {
            setElement(core, Z1, 0, b, 0x3CL);
            setElement(core, Z2, 0, b, 0x3CL);
        }
        run(FP8_DOT4, core, fdotSb);
        assertEquals(Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    /// `FDOT_idx_hb`/`FDOT_idx_sb`: `Zm` fixo por segmento.
    @Test
    void fdotIndexedUsesAFixedGroupOfZmPerSegment() {
        Aarch64Core core = core(FP8_DOT2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fdotIdxHb = 0x64204400 | (Z2 << 16) | (Z1 << 5) | Z0; // index = 0
        setElement(core, Z1, 0, 0, 0x3CL);
        setElement(core, Z1, 0, 1, 0x3CL);
        setElement(core, Z2, 0, 0, 0x3CL);
        setElement(core, Z2, 0, 1, 0x3CL);
        run(FP8_DOT2, core, fdotIdxHb);
        assertEquals(0x4000L, getElement(core, Z0, 1, 0));

        Aarch64Core core2 = core(FP8_DOT4, 128);
        clearVector(core2, Z0);
        clearVector(core2, Z1);
        clearVector(core2, Z2);
        int fdotIdxSb = 0x64604400 | (Z2 << 16) | (Z1 << 5) | Z0; // index = 0
        for (int b = 0; b < 4; b++) {
            setElement(core2, Z1, 0, b, 0x3CL);
            setElement(core2, Z2, 0, b, 0x3CL);
        }
        run(FP8_DOT4, core2, fdotIdxSb);
        assertEquals(Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL, getElement(core2, Z0, 2, 0));
    }

    // ── BFMLALB/BFMLSLB (widen bf16) e BFDOT_zzzz (widen bf16 dot) — execução ──────────────────────

    @Test
    void bfmlalbComputesFusedMultiplyAdd() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 1, 0, 0x3F80L); // bf16(1.0)
        setElement(core, Z2, 1, 0, 0x4000L); // bf16(2.0)
        run(BF16, core, mlalWord(0b11, false, false, Z2, Z1, Z0)); // BFMLALB
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
    }

    @Test
    void bfmlslbComputesFusedMultiplySubtract() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 1, 0, 0x3F80L); // bf16(1.0)
        setElement(core, Z2, 1, 0, 0x4000L); // bf16(2.0)
        setElement(core, Z0, 2, 0, Float.floatToRawIntBits(5f) & 0xFFFF_FFFFL);
        run(SVE2_1, core, mlalWord(0b11, true, false, Z2, Z1, Z0)); // BFMLSLB
        assertEquals(3f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0))); // 5 - 1*2
    }

    @Test
    void bfmlalbIndexedUsesAFixedElementPerSegment() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int bfmlalbIdx = 0x64204000 | (0b11 << 22) | (Z2 << 16) | (Z1 << 5) | Z0; // index = 0
        setElement(core, Z1, 1, 0, 0x3F80L);
        setElement(core, Z2, 1, 0, 0x4000L);
        run(BF16, core, bfmlalbIdx);
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
    }

    @Test
    void bfdotZzzzComputesDotProduct() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 1, 0, 0x3F80L); // bf16(1.0)
        setElement(core, Z1, 1, 1, 0x3F80L);
        setElement(core, Z2, 1, 0, 0x3F80L);
        setElement(core, Z2, 1, 1, 0x3F80L);
        run(BF16, core, fdotWidenWord(0b01, Z2, Z1, Z0));
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
    }

    @Test
    void bfdotZzxzUsesAFixedPairPerSegment() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int bfdotIdx = 0x64604000 | (Z2 << 16) | (Z1 << 5) | Z0; // index = 0
        setElement(core, Z1, 1, 0, 0x3F80L);
        setElement(core, Z1, 1, 1, 0x3F80L);
        setElement(core, Z2, 1, 0, 0x3F80L);
        setElement(core, Z2, 1, 1, 0x3F80L);
        run(BF16, core, bfdotIdx);
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
    }

    @Test
    void fdotZzxzUsesAFixedPairPerSegment() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        int fdotIdx = 0x64204000 | (Z2 << 16) | (Z1 << 5) | Z0; // index = 0
        setElement(core, Z1, 1, 0, 0x3C00L); // half(1.0)
        setElement(core, Z1, 1, 1, 0x3C00L);
        setElement(core, Z2, 1, 0, 0x3C00L);
        setElement(core, Z2, 1, 1, 0x3C00L);
        run(SVE2_1, core, fdotIdx);
        assertEquals(2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
    }

    // ── BFMMLA/FMMLA_d/FMMLA_sb/FMMLA_hb — execução ─────────────────────────────────────────────────

    @Test
    void bfmmlaComputesMatrixMultiply() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        // `K = 4`: linha 0 de Zn = {1,1,1,1}, linha 1 = {0,0,0,0}; coluna 0 de Zm = {1,1,1,1}, coluna 1 =
        // {0,0,0,0} — `d[0][0] = 4·(1×1) = 4`, o resto fica zero.
        for (int k = 0; k < 4; k++) {
            setElement(core, Z1, 1, k, 0x3F80L); // bf16(1.0)
            setElement(core, Z2, 1, k, 0x3F80L); // bf16(1.0)
        }
        run(BF16, core, matmulWord(0b01, 0b001, Z2, Z1, Z0));
        assertEquals(4f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
        assertEquals(0f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 1)));
        assertEquals(0f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 2)));
        assertEquals(0f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 3)));
    }

    @Test
    void fmmlaDComputesMatrixMultiply() {
        Aarch64Core core = core(F64MM, 256); // 256 bits = 1 bloco de FMMLA_d (4 elementos D)
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 3, 0, Double.doubleToRawLongBits(1.0));
        setElement(core, Z1, 3, 3, Double.doubleToRawLongBits(1.0));
        setElement(core, Z2, 3, 0, Double.doubleToRawLongBits(2.0));
        setElement(core, Z2, 3, 2, Double.doubleToRawLongBits(4.0));
        run(F64MM, core, matmulWord(0b11, 0b001, Z2, Z1, Z0));
        assertEquals(2.0, Double.longBitsToDouble(getElement(core, Z0, 3, 0)));
    }

    @Test
    void fmmlaSbComputesMatrixMultiply() {
        Aarch64Core core = core(FP8_MM32, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        for (int b = 0; b < 8; b++) {
            setElement(core, Z1, 0, b, 0x3CL); // linha 0 e 1 = 1.0 em fp8
            setElement(core, Z2, 0, b, 0x3CL); // col 0 e 1 = 1.0 em fp8
        }
        run(FP8_MM32, core, matmulWord(0b00, 0b000, Z2, Z1, Z0));
        assertEquals(8f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0))); // 8 produtos de 1.0
    }

    @Test
    void fmmlaHbComputesMatrixMultiply() {
        Aarch64Core core = core(FP8_MM16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        for (int b = 0; b < 8; b++) {
            setElement(core, Z1, 0, b, 0x3CL);
            setElement(core, Z2, 0, b, 0x3CL);
        }
        run(FP8_MM16, core, matmulWord(0b01, 0b000, Z2, Z1, Z0));
        assertEquals(0x4400L, getElement(core, Z0, 1, 0)); // half(4.0) = 4 produtos de 1.0 por bloco de 64 bits
    }

    // ── FCVTLT_sd/FCVTXNT_ds/BFCVTNT — execução (as demais 4 das 6 combinações (a, c)) ─────────────

    @Test
    void fcvtltSdWidensSingleToDouble() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 1, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL); // ímpar
        run(SVE2, core, oddWord(0b11, 0b0010, 0b11, P0, Z1, Z0)); // FCVTLT_sd_m
        assertEquals(2.5, Double.longBitsToDouble(getElement(core, Z0, 3, 0)));
    }

    @Test
    void fcvtxntDsRoundsToOddAndPreservesTheOtherHalf() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z0, 2, 0, 0xDEADL); // sentinela no par — deve sobreviver
        setElement(core, Z1, 3, 0, Double.doubleToRawLongBits(2.0));
        run(SVE2, core, oddWord(0b00, 0b0010, 0b10, P0, Z1, Z0)); // FCVTXNT_ds_m
        assertEquals(0xDEADL, getElement(core, Z0, 2, 0));
        assertEquals(Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL, getElement(core, Z0, 2, 1));
    }

    @Test
    void bfcvtntNarrowsSingleToBFloat16() {
        Aarch64Core core = core(BF16, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        run(BF16, core, oddWord(0b10, 0b0010, 0b10, P0, Z1, Z0)); // BFCVTNT_m
        assertEquals(0x3F80L, getElement(core, Z0, 1, 1));
    }

    @Test
    void oddElementsZeroingWritesZeroWhenInactive() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P0, w, 0L); // tudo inativo
        }
        setElement(core, Z0, 1, 1, 0x1234L); // será zerado
        run(SVE2, core, oddWord(0b10, 0b0000, 0b00, P0, Z1, Z0)); // FCVTNT_sh_z
        assertEquals(0L, getElement(core, Z0, 1, 1));
    }

    // ── FLOGB_z — execução ───────────────────────────────────────────────────────────────────────────

    @Test
    void flogbZeroingComputesTheUnbiasedExponent() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, Float.floatToRawIntBits(8.0f) & 0xFFFF_FFFFL);
        int flogbZ = 0x641E8000 | (2 << 13) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbZ);
        assertEquals(3L, getElement(core, Z0, 2, 0));
    }

    @Test
    void flogbZeroAndInfinityAndZeroingInactive() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, 0L); // +0.0
        setElement(core, Z1, 2, 1, 0x7F800000L); // +Infinito
        int flogbM = 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbM);
        assertEquals(Integer.MIN_VALUE & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
        assertEquals(Integer.MAX_VALUE & 0xFFFF_FFFFL, getElement(core, Z0, 2, 1));
    }

    // ── FMAXNMP/FMINNMP/FMAXP/FMINP — execução ──────────────────────────────────────────────────────

    @Test
    void pairwiseMaxAndMinVariants() {
        Aarch64Core core = core(SVE2, 128);
        setPredicateAllTrue(core, P0);
        for (int variant = 0b10; variant <= 0b11; variant++) {
            for (int z = 0; z <= 1; z++) {
                clearVector(core, Z0);
                clearVector(core, Z1);
                setElement(core, Z0, 2, 0, Float.floatToRawIntBits(1f) & 0xFFFF_FFFFL);
                setElement(core, Z0, 2, 1, Float.floatToRawIntBits(3f) & 0xFFFF_FFFFL);
                run(SVE2, core, pairwiseWord(variant, z, 2, P0, Z1, Z0));
                float expected = z == 0 ? 3f : 1f; // max ou min do par (1,3)
                assertEquals(expected, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
            }
        }
    }

    // ── Ramos negativos dos decoders (cobertura de linha/branch) ────────────────────────────────────

    @Test
    void convertFp8DecoderRefusesUnmatchedWords() {
        assertNull(decodeOrNull(F8CVT, 0x00000000)); // nem widen nem narrow
        assertInstanceOf(Ir64Op.SveFpConvertToFp8.class, decodeOrNull(F8CVT, 0x650A3800)); // BFCVTN (rn=0)
    }

    @Test
    void oddDecoderRefusesBfcvtntWithoutBFloat16AndDecodesFcvtntDsPlain() {
        int bfcvtnt = oddWord(0b10, 0b0010, 0b10, P0, Z1, Z0);
        assertNull(decodeOrNull(SVE2, bfcvtnt), "sem BFLOAT16 recusa BFCVTNT");
        int fcvtntDs = oddWord(0b11, 0b0010, 0b10, P0, Z1, Z0);
        Ir64Op decoded = decodeOrNull(SVE2, fcvtntDs);
        assertInstanceOf(Ir64Op.SveFpConvertOddElements.class, decoded);
        assertEquals(Ir64Op.SveFpConvertOddElements.Op.FCVTNT, ((Ir64Op.SveFpConvertOddElements) decoded).op());
        assertEquals(3, ((Ir64Op.SveFpConvertOddElements) decoded).wideEsz());
    }

    @Test
    void fp8MultiplyDecoderRefusesUnmatchedAndReservedWords() {
        assertNull(decodeOrNull(FP8_FMA, 0x00000000), "não é nem FMA indexado nem vetorial");
        int hbWithBit13Set = fmlalHbWord(Z2, Z1, Z0) | (1 << 13); // reservado para `hb`
        assertNull(decodeOrNull(FP8_FMA, hbWithBit13Set));
    }

    @Test
    void matrixDecoderRefusesUnmatchedAndUnallocatedWords() {
        assertNull(decodeOrNull(SVE2, 0x00000000)); // nem pairwise nem matmul
        assertNull(decodeOrNull(SVE2, pairwiseWord(0b01, 0, 2, P0, Z1, Z0)), "xy = 01 não alocado");
        assertNull(decodeOrNull(F32MM, matmulWord(0b00, 0b001, Z2, Z1, Z0)), "kind2 = 00 com BF_S_D não alocado");
        assertNull(decodeOrNull(FP8_MM32, matmulWord(0b10, 0b000, Z2, Z1, Z0)), "kind2 = 10 com FP8 não alocado");
    }

    // ── FLOGB: zeroing inativo + subnormal (com/sem FZ) ─────────────────────────────────────────────

    @Test
    void flogbZeroingWritesZeroWhenInactive() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P0, w, 0L);
        }
        setElement(core, Z0, 2, 0, 0x1234L);
        int flogbZ = 0x641E8000 | (2 << 13) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbZ);
        assertEquals(0L, getElement(core, Z0, 2, 0));
    }

    /// Subnormal `binary32` com `FPCR.FZ` desligado (padrão): `-viés - clz(fração)`.
    @Test
    void flogbSubnormalWithoutFlushToZero() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, 0x00400000L); // subnormal: exp=0, frac = 2^22 (bit mais alto do campo)
        int flogbM = 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbM);
        // valor = frac · 2^(1−viés−fracBits) = 2^22 · 2^(1−127−23) = 2^−127 ⇒ log2 = −127.
        assertEquals(-127L & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    // ── Ramos de formato/branch restantes (F2CVT/BF1CVT/BFCVTN, `FPMR` = `E4M3`) ────────────────────

    private static void writeFpmr(Aarch64Core core, Aarch64Architecture architecture, long value) {
        core.setX(0, value);
        new Ir64BlockExecutor(architecture).executeOp(core, new Ir64Op.SystemRegister(false,
                dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.FPMR, 0));
    }

    @Test
    void f2cvtUsesTheSecondStreamFormatAndScale() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        writeFpmr(core, F8CVT, 0b001L << 3); // FPMR.F8S2 = E4M3
        setElement(core, Z1, 0, 0, 0x38L); // 1.0 em E4M3 (sign0,exp7=0111,frac000)
        int f2cvt = 0x65083400 | (Z1 << 5) | Z0; // stream2 = bit 10
        run(F8CVT, core, f2cvt);
        assertEquals(0x3C00L, getElement(core, Z0, 1, 0));
    }

    @Test
    void bf1cvtWidensFp8ToBFloat16() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setElement(core, Z1, 0, 0, 0x3CL); // 1.0 em E5M2
        int bf1cvt = 0x65083800 | (Z1 << 5) | Z0; // bfloat16Destination = bit 11
        run(F8CVT, core, bf1cvt);
        assertEquals(0x3F80L, getElement(core, Z0, 1, 0)); // bf16(1.0)
    }

    @Test
    void bfcvtnNarrowsBFloat16ToFp8() {
        Aarch64Core core = core(F8CVT, 128);
        clearVector(core, Z0);
        clearVector(core, Z2);
        clearVector(core, Z3);
        writeFpmr(core, F8CVT, 0b001L << 6); // FPMR.F8D = E4M3
        setElement(core, Z2, 1, 0, 0x3F80L); // bf16(1.0)
        setElement(core, Z3, 1, 0, 0x3F80L); // bf16(1.0)
        int bfcvtn = 0x650A3800 | (Z2 / 2 << 6);
        run(F8CVT, core, bfcvtn);
        assertEquals(0x38L, getElement(core, Z0, 0, 0)); // fp8(1.0) em E4M3
    }

    @Test
    void fcvtltZeroingWritesZeroWhenInactive() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P0, w, 0L); // tudo inativo
        }
        setElement(core, Z0, 2, 0, Float.floatToRawIntBits(9f) & 0xFFFF_FFFFL);
        run(SVE2, core, oddWord(0b10, 0b0000, 0b01, P0, Z1, Z0)); // FCVTLT_hs_z
        assertEquals(0L, getElement(core, Z0, 2, 0));
    }

    /// Subnormal com `FPCR.FZ` LIGADO: tratado como zero (`Invalid`, mínimo representável) — não entra no
    /// ramo `-viés - clz(fração)`.
    @Test
    void flogbSubnormalWithFlushToZero() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        setPredicateAllTrue(core, P0);
        setElement(core, Z1, 2, 0, 0x00400000L); // subnormal
        core.setX(0, 1L << 24); // FPCR.FZ
        new Ir64BlockExecutor(SVE2).executeOp(core,
                new Ir64Op.SystemRegister(false, dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.FPCR, 0));
        int flogbM = 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
        run(SVE2, core, flogbM);
        assertEquals(Integer.MIN_VALUE & 0xFFFF_FFFFL, getElement(core, Z0, 2, 0));
    }

    @Test
    void fp8OpsUseTheE4m3FormatWhenFpmrSelectsIt() {
        Aarch64Core core = core(FP8_FMA, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        writeFpmr(core, FP8_FMA, 0b001L | (0b001L << 3)); // F8S1 = F8S2 = E4M3
        setElement(core, Z1, 0, 0, 0x38L); // 1.0 em E4M3
        setElement(core, Z2, 0, 0, 0x38L);
        run(FP8_FMA, core, fmlalHbWord(Z2, Z1, Z0));
        assertEquals(0x3C00L, getElement(core, Z0, 1, 0));
    }

    @Test
    void fmlaltComputesFusedMultiplyAddOnTheOddNarrowElement() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        clearVector(core, Z2);
        setElement(core, Z1, 1, 1, 0x3C00L); // Zn.H[1] (ímpar) = 1.0
        setElement(core, Z2, 1, 1, 0x4000L); // Zm.H[1] (ímpar) = 2.0
        run(SVE2, core, mlalWord(0b10, true, true, Z2, Z1, Z0)); // FMLSLT
        assertEquals(-2f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0))); // 0 − 1·2
    }

    @Test
    void faddpSkipsInactivePairs() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z0);
        clearVector(core, Z1);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P0, w, 0L); // tudo inativo
        }
        setElement(core, Z0, 2, 0, Float.floatToRawIntBits(42f) & 0xFFFF_FFFFL); // preservado
        setElement(core, Z0, 2, 1, Float.floatToRawIntBits(7f) & 0xFFFF_FFFFL); // preservado
        run(SVE2, core, pairwiseWord(0b00, 0, 2, P0, Z1, Z0));
        assertEquals(42f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 0)));
        assertEquals(7f, Float.intBitsToFloat((int) getElement(core, Z0, 2, 1)));
    }

    // ── Acesso SVE negado (`CPACR_EL1.ZEN = 0`) — cobre o ramo `accessAllowed == false` de todos os novos
    // executores num único teste, mesmo padrão de `Aarch64Sve2MiscTest`.

    private static final class DenyingCpacr implements dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus {
        @Override
        public boolean handles(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register) {
            return register == dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register) {
            return 0L;
        }

        @Override
        public void write(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void everyNewGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int group) {
        int word = switch (group) {
            case 0 -> f1cvtWord(Z0, Z1);
            case 1 -> fcvtnWord(Z0, 0);
            case 2 -> pairwiseWord(0b00, 0, 2, P0, Z1, Z0);
            case 3 -> matmulWord(0b10, 0b001, Z2, Z1, Z0);
            case 4 -> oddWord(0b10, 0b0010, 0b00, P0, Z1, Z0);
            case 5 -> 0x6518A000 | (2 << 17) | (P0 << 10) | (Z1 << 5) | Z0;
            case 6 -> fmlalHbWord(Z2, Z1, Z0);
            case 7 -> 0x64208400 | (Z2 << 16) | (Z1 << 5) | Z0;
            case 8 -> mlalWord(0b10, false, false, Z2, Z1, Z0);
            case 9 -> mlalWord(0b11, false, false, Z2, Z1, Z0);
            case 10 -> fdotWidenWord(0b00, Z2, Z1, Z0);
            default -> fdotWidenWord(0b01, Z2, Z1, Z0);
        };
        Aarch64Architecture architecture = switch (group) {
            case 0 -> F8CVT;
            case 1 -> F8CVT;
            case 3 -> F32MM;
            case 6 -> FP8_FMA;
            case 7 -> FP8_DOT2;
            case 9 -> BF16;
            case 10 -> SVE2_1;
            case 11 -> BF16;
            default -> SVE2;
        };
        Aarch64Core core = core(architecture, 256);
        core.setSystemRegisterBus(new DenyingCpacr());
        core.setX(0, 0x1234L);
        run(architecture, core, word);
        assertEquals(core.exceptionState().vbar(Aarch64ExceptionLevel.EL1) + 0x400L, core.pc(), "grupo " + group);
        assertEquals(0x19L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC = 0x19 (SVE), grupo " + group);
        assertEquals(0x1234L, core.x(0), "a instrução não executou, grupo " + group);
    }
}
