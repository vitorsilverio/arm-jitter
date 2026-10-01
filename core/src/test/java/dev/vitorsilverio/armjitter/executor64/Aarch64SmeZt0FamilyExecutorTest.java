package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B18.6 — `ZERO` multi-vetor, `MOVT` e `LUTI2`/`LUTI4`. As palavras são montadas campo a campo por {@link #lutWord}/
/// {@link #zeroWord} a partir das bases cujo decode foi conferido contra `aarch64-none-elf-as` em
/// `Aarch64SmeZt0FamilyDecoderTest`; {@link #lutBaseMatchesAssembler} amarra as duas pontas. As referências
/// abaixo são escritas de forma ACHATADA (uma sequência de campos de `isize` bits sobre os bytes de `Zn`), não
/// copiando o laço do executor, e dois casos dourados calculados à mão ancoram a fórmula de segmento.
class Aarch64SmeZt0FamilyExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-zt0-exec-ALL", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2,
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1, Aarch64Feature.SME_LUTV2);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long SMCR_EZT0 = 1L << 30;
    private static final long SMCR_MAX_LEN = 0xF;
    private static final long VBAR = 0x400L;
    private static final int EC_SME = 0x1D;
    private static final int ESR_EC_SHIFT = 26;
    private static final long SMTC_MASK = 0x7;
    private static final long SMTC_INACCESSIBLE_ZT0 = 4;
    private static final long SMTC_INACTIVE_ZA = 3;
    private static final long SMTC_NOT_STREAMING = 2;
    private static final long SENTINEL = 0x5A5A5A5A5A5A5A5AL;
    private static final int ZT0_WORDS = 8;
    private static final int Z_REGISTERS = 32;
    private static final int LUTI2_IDX_BITS = 4;
    private static final int LUTI4_IDX_BITS = 3;

    private static final int MOVT_RZT = 0xC04C03E0;
    private static final int MOVT_ZTR = 0xC04E03E0;
    private static final int MOVT_ZTZ = 0xC04F03E0;

    private static Aarch64Core core(int svlBits, long svcr, boolean zt0) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), ALL, svlBits,
                svlBits);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(svcr);
        if (zt0) {
            core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, SMCR_EZT0 | SMCR_MAX_LEN);
        }
        return core;
    }

    private static Aarch64Core core(int svlBits) {
        return core(svlBits, SVCR_SM | SVCR_ZA, true);
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(ALL).step(core);
    }

    private static int svlBytes(Aarch64Core core) {
        return core.streamingVectorLengthBytes();
    }

    private static void fillZRegisters(Aarch64Core core, long value) {
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < svlBytes(core) / Long.BYTES; w++) {
                core.scalable().setZWord(z, w, value);
            }
        }
    }

    private static void randomZ(Aarch64Core core, int z, Random random) {
        for (int w = 0; w < svlBytes(core) / Long.BYTES; w++) {
            core.scalable().setZWord(z, w, random.nextLong());
        }
    }

    private static void randomZt0(Aarch64Core core, Random random) {
        for (int w = 0; w < ZT0_WORDS; w++) {
            core.matrix().setZt0Word(w, random.nextLong());
        }
    }

    private static int zByte(Aarch64Core core, int z, int index) {
        return (int) (core.scalable().zWord(z, index / Long.BYTES) >>> ((index % Long.BYTES) * Byte.SIZE)) & 0xFF;
    }

    private static int zt0Byte(Aarch64Core core, int index) {
        return (int) (core.matrix().zt0Word(index / Long.BYTES) >>> ((index % Long.BYTES) * Byte.SIZE)) & 0xFF;
    }

    // ── MOVT com registrador geral ───────────────────────────────────────────────────────────────

    @Test
    void movtRoundTripsEveryWordOfZt0() {
        Aarch64Core core = core(256);
        Random random = new Random(1);
        for (int off = 0; off < ZT0_WORDS; off++) {
            long value = random.nextLong();
            core.setX(4, value);
            run(core, MOVT_ZTR | off << 12 | 4);
            assertEquals(value, core.matrix().zt0Word(off), "ZT0[" + off + "]");
            run(core, MOVT_RZT | off << 12 | 9);
            assertEquals(value, core.x(9), "x9 lido de ZT0[" + off + "]");
        }
    }

    @Test
    void movtWithXzrWritesZeroAndDiscardsTheRead() {
        Aarch64Core core = core(256);
        core.matrix().setZt0Word(2, -1L);
        run(core, MOVT_ZTR | 2 << 12 | 31);
        assertEquals(0L, core.matrix().zt0Word(2));
        core.matrix().setZt0Word(3, -1L);
        run(core, MOVT_RZT | 3 << 12 | 31);
        assertEquals(0L, core.x(31));
        assertEquals(-1L, core.matrix().zt0Word(3));
    }

    @Test
    void movtWithoutSmcrEzt0EntersTheZt0Trap() {
        Aarch64Core core = core(256, SVCR_ZA, false);
        run(core, MOVT_RZT | 3);
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(SMTC_INACCESSIBLE_ZT0, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK,
                "síndrome PRÓPRIA de ZT0, não a de ZA");
    }

    @Test
    void movtWithZaDisabledEntersTheZaTrapNotTheZt0One() {
        Aarch64Core core = core(256, 0, true);
        run(core, MOVT_ZTR | 3);
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(SMTC_INACTIVE_ZA, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }

    @Test
    void movtWithGeneralRegisterWorksOutsideStreamingMode() {
        Aarch64Core core = core(256, SVCR_ZA, true);
        core.setX(1, 0x1234L);
        run(core, MOVT_ZTR | 1);
        assertEquals(0x1234L, core.matrix().zt0Word(0));
    }

    // ── MOVT de vetor (FEAT_SME_LUTv2) ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void movtFromVectorCopiesMinSvlAnd64BytesAndZeroesTheRestOnlyAtOffsetZero(int svlBits) {
        Aarch64Core core = core(svlBits);
        Random random = new Random(svlBits);
        int tsize = Math.min(svlBits / 8, 64);
        int segments = 64 / tsize;
        for (int segment = 0; segment < segments; segment++) {
            randomZt0(core, random);
            randomZ(core, 7, random);
            byte[] before = new byte[64];
            for (int i = 0; i < 64; i++) {
                before[i] = (byte) zt0Byte(core, i);
            }
            run(core, MOVT_ZTZ | segment << 12 | 7);
            for (int i = 0; i < 64; i++) {
                int expected;
                if (i >= segment * tsize && i < (segment + 1) * tsize) {
                    expected = zByte(core, 7, i - segment * tsize);
                } else if (segment == 0) {
                    expected = 0; // `maxsz = 64` no deslocamento zero: o resto de ZT0 é zerado
                } else {
                    expected = before[i] & 0xFF;
                }
                assertEquals(expected, zt0Byte(core, i), "svl " + svlBits + " off " + segment + " byte " + i);
            }
        }
    }

    @Test
    void movtFromVectorWithoutZt0AccessEntersTheZt0Trap() {
        Aarch64Core core = core(256, SVCR_SM | SVCR_ZA, false);
        run(core, MOVT_ZTZ | 7);
        assertEquals(SMTC_INACCESSIBLE_ZT0, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }

    @Test
    void movtFromVectorOutsideStreamingModeEntersTheSmeTrap() {
        Aarch64Core core = core(256, SVCR_ZA, true);
        run(core, MOVT_ZTZ | 7);
        assertEquals(SMTC_NOT_STREAMING, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }

    // ── ZERO multi-vetor ─────────────────────────────────────────────────────────────────────────

    private static final int[][] ZERO_BASES = {
            // base, ngrp, nvec, escala do off, máscara do campo
            {0xC00C0000, 2, 1, 1, 0b111},
            {0xC00E0000, 4, 1, 1, 0b111},
            {0xC00C8000, 1, 2, 2, 0b111},
            {0xC00D0000, 2, 2, 2, 0b11},
            {0xC00D8000, 4, 2, 2, 0b11},
            {0xC00E8000, 1, 4, 4, 0b11},
            {0xC00F0000, 2, 4, 4, 0b1},
            {0xC00F8000, 4, 4, 4, 0b1},
    };

    private static int zeroWord(int[] base, int rvField, int offField) {
        return base[0] | rvField << 13 | offField;
    }

    private static void fillZa(Aarch64Core core) {
        int rowWords = core.matrix().zaRowBytes() / Long.BYTES;
        for (int row = 0; row < svlBytes(core); row++) {
            for (int w = 0; w < rowWords; w++) {
                SmeMovaOps.setZaWordAt(core.matrix(), row, w, SENTINEL ^ row);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void zeroArrayZeroesExactlyNgrpTimesNvecRows(int svlBits) {
        for (int[] base : ZERO_BASES) {
            int ngrp = base[1];
            int nvec = base[2];
            for (int offField = 0; offField <= base[4]; offField++) {
                for (long w : new long[] {0L, 5L, 13L, 0x1_0000_0003L}) {
                    Aarch64Core core = core(svlBits);
                    int svl = svlBytes(core);
                    fillZa(core);
                    core.setX(8 + 1, w); // rv field 1 → W9
                    run(core, zeroWord(base, 1, offField));
                    int rowsPerGroup = svl / ngrp;
                    int off = offField * base[3];
                    int start = (int) ((((w & 0xFFFFFFFFL) & ~(long) (nvec - 1)) + off) % rowsPerGroup);
                    int rowWords = core.matrix().zaRowBytes() / Long.BYTES;
                    for (int row = 0; row < svl; row++) {
                        boolean zeroed = false;
                        for (int g = 0; g < ngrp; g++) {
                            for (int v = 0; v < nvec; v++) {
                                zeroed |= row == start + g * rowsPerGroup + v;
                            }
                        }
                        for (int word = 0; word < rowWords; word++) {
                            assertEquals(zeroed ? 0L : SENTINEL ^ row, SmeMovaOps.zaWordAt(core.matrix(), row, word),
                                    String.format("svl %d %dx%d off %d W=%d row %d", svlBits, ngrp, nvec, off, w, row));
                        }
                    }
                }
            }
        }
    }

    @Test
    void zeroArrayScalesTheOffsetPerEncoding() {
        // %off2_x2 com o campo em 1 aponta a linha 2 (não a 1): ngrp=2, nvec=2, W8 = 0.
        Aarch64Core core = core(256);
        fillZa(core);
        run(core, zeroWord(ZERO_BASES[3], 0, 1));
        int rowsPerGroup = svlBytes(core) / 2;
        for (int row = 0; row < svlBytes(core); row++) {
            boolean zeroed = row == 2 || row == 3 || row == rowsPerGroup + 2 || row == rowsPerGroup + 3;
            assertEquals(zeroed ? 0L : SENTINEL ^ row, SmeMovaOps.zaWordAt(core.matrix(), row, 0), "row " + row);
        }
    }

    @Test
    void zeroArrayOutsideStreamingModeEntersTheSmeTrap() {
        Aarch64Core core = core(256, SVCR_ZA, true);
        run(core, zeroWord(ZERO_BASES[0], 0, 0));
        assertEquals(SMTC_NOT_STREAMING, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }

    // ── LUTI2 / LUTI4 ────────────────────────────────────────────────────────────────────────────

    /// `0xC0CC0000` (LUTI2 consecutivo, 1 vetor) e as demais bases — as mesmas conferidas no assembler.
    private static int lutBase(boolean four, int count, boolean strided, boolean pair) {
        if (four) {
            if (pair) {
                return strided ? 0xC09B0000 : 0xC08B0000;
            }
            int[] consecutive = {0, 0xC0CA0000, 0xC08A4000, 0, 0xC08A8000};
            int[] stridedBases = {0, 0, 0xC09A4000, 0, 0xC09A8000};
            return strided ? stridedBases[count] : consecutive[count];
        }
        int[] consecutive = {0, 0xC0CC0000, 0xC08C4000, 0, 0xC08C8000};
        int[] stridedBases = {0, 0, 0xC09C4000, 0, 0xC09C8000};
        return strided ? stridedBases[count] : consecutive[count];
    }

    private static int lutWord(boolean four, int esz, int count, boolean strided, int zd, int zn, int idx) {
        boolean pair = four && esz == 0 && count == 4;
        int log2 = Integer.numberOfTrailingZeros(count);
        int word = lutBase(four, count, strided, pair) | esz << 12;
        word |= pair ? (zn / 2) << 6 : zn << 5;
        if (!pair) {
            word |= idx << (14 + log2);
        }
        if (strided || count == 1) {
            word |= zd;
        } else if (count == 2) {
            word |= (zd / 2) << 1;
        } else {
            word |= (zd / 4) << 2;
        }
        return word;
    }

    @Test
    void lutBaseMatchesAssembler() {
        // palavras do `aarch64-none-elf-as`, ver Aarch64SmeZt0FamilyDecoderTest
        assertEquals(0xC0CCC041, lutWord(false, 0, 1, false, 1, 2, 3));   // luti2 z1.b, zt0, z2[3]
        assertEquals(0xC08ED082, lutWord(false, 1, 2, false, 2, 4, 5));   // luti2 {z2.h-z3.h}, zt0, z4[5]
        assertEquals(0xC08DA084, lutWord(false, 2, 4, false, 4, 4, 1));   // luti2 {z4.s-z7.s}, zt0, z4[1]
        assertEquals(0xC09CD082, lutWord(false, 1, 2, true, 2, 4, 1));    // luti2 {z2.h, z10.h}, zt0, z4[1]
        assertEquals(0xC09D8081, lutWord(false, 0, 4, true, 1, 4, 1));    // luti2 {z1.b, z5.b, z9.b, z13.b}, zt0, z4[1]
        assertEquals(0xC0CBD041, lutWord(true, 1, 1, false, 1, 2, 7));    // luti4 z1.h, zt0, z2[7]
        assertEquals(0xC08B6082, lutWord(true, 2, 2, false, 2, 4, 2));    // luti4 {z2.s-z3.s}, zt0, z4[2]
        assertEquals(0xC08B9084, lutWord(true, 1, 4, false, 4, 4, 1));    // luti4 {z4.h-z7.h}, zt0, z4[1]
        assertEquals(0xC08B0104, lutWord(true, 0, 4, false, 4, 8, 0));    // luti4 {z4.b-z7.b}, zt0, {z8-z9}
        assertEquals(0xC09B0101, lutWord(true, 0, 4, true, 1, 8, 0));     // luti4 {z1.b, z5.b, z9.b, z13.b}, zt0, {z8-z9}
        assertEquals(0xC09B40D0, lutWord(true, 0, 2, true, 16, 6, 2));    // luti4 {z16.b, z24.b}, zt0, z6[2]
    }

    private static long tableEntry(Aarch64Core core, int index) {
        return (core.matrix().zt0Word(index / 2) >>> ((index % 2) * 32)) & 0xFFFF_FFFFL;
    }

    private static int sourceField(byte[] source, int field, int isize) {
        int bit = field * isize;
        return ((source[bit / 8] & 0xFF) >>> (bit % 8)) & ((1 << isize) - 1);
    }

    /// Referência achatada: o elemento `n = r × elementos + e` do destino usa o campo `segmento × count × elementos
    /// + n` do vetor de índices.
    private static long expectedElement(Aarch64Core core, byte[] source, boolean four, int esz, int count, int idx,
            int r, int e) {
        int isize = four ? 4 : 2;
        int elements = svlBytes(core) >>> esz;
        int segments = Math.max(1, (8 << esz) / (isize * count));
        int segment = idx & (segments - 1);
        int index = sourceField(source, segment * count * elements + r * elements + e, isize);
        long entry = tableEntry(core, index);
        return esz == 2 ? entry : entry & ((1L << (8 << esz)) - 1);
    }

    private static byte[] sourceBytes(Aarch64Core core, int zn, int vectors) {
        int svl = svlBytes(core);
        byte[] bytes = new byte[svl * vectors];
        for (int v = 0; v < vectors; v++) {
            for (int i = 0; i < svl; i++) {
                bytes[v * svl + i] = (byte) zByte(core, zn + v, i);
            }
        }
        return bytes;
    }

    private static long zElement(Aarch64Core core, int z, int esz, int e) {
        return SvePredicateOps.elementOf(core.scalable(), z, e, esz);
    }

    private static void checkLut(int svlBits, boolean four, int esz, int count, boolean strided, int zd, int zn,
            int idx, long seed) {
        Aarch64Core core = core(svlBits);
        Random random = new Random(seed);
        fillZRegisters(core, SENTINEL);
        randomZt0(core, random);
        boolean pair = four && esz == 0 && count == 4;
        randomZ(core, zn, random);
        if (pair) {
            randomZ(core, zn + 1, random);
        }
        byte[] source = sourceBytes(core, zn, pair ? 2 : 1);
        run(core, lutWord(four, esz, count, strided, zd, zn, idx));
        int stride = !strided ? 1 : count == 4 ? 4 : 8;
        int elements = svlBytes(core) >>> esz;
        String where = String.format("svl %d luti%d esz %d x%d %s zd %d zn %d idx %d", svlBits, four ? 4 : 2, esz,
                count, strided ? "strided" : "consec", zd, zn, idx);
        for (int r = 0; r < count; r++) {
            for (int e = 0; e < elements; e++) {
                assertEquals(expectedElement(core, source, four, esz, count, idx, r, e),
                        zElement(core, zd + r * stride, esz, e), where + " reg " + r + " elem " + e);
            }
        }
        // só os destinos mudam: os registradores ENTRE os destinos strided seguem intactos
        for (int z = 0; z < Z_REGISTERS; z++) {
            boolean destination = false;
            for (int r = 0; r < count; r++) {
                destination |= z == zd + r * stride;
            }
            if (!destination && z != zn && !(pair && z == zn + 1)) {
                assertEquals(SENTINEL, core.scalable().zWord(z, 0), where + " z" + z + " deveria estar intacto");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void everyConsecutiveLutVariantMatchesTheFlatReferenceForEverySegment(int svlBits) {
        long seed = svlBits;
        for (boolean four : new boolean[] {false, true}) {
            for (int esz = 0; esz <= 2; esz++) {
                for (int count : new int[] {1, 2, 4}) {
                    boolean pair = four && esz == 0 && count == 4;
                    int idxBits = (four ? LUTI4_IDX_BITS : LUTI2_IDX_BITS) - Integer.numberOfTrailingZeros(count);
                    for (int idx = 0; idx < (pair ? 1 : 1 << idxBits); idx++) {
                        int zd = count == 1 ? 3 : count == 2 ? 6 : 12;
                        checkLut(svlBits, four, esz, count, false, zd, pair ? 20 : 17, idx, seed++);
                    }
                }
            }
        }
    }

    /// Não existe `esz = 2` em strided (`LUTI2_s_4s`/`LUTI2_s_2s`/`LUTI4_s_*s` não estão no `.decode`).
    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void everyStridedLutVariantMatchesTheFlatReferenceAndSkipsTheConsecutiveRegisters(int svlBits) {
        long seed = 1000L + svlBits;
        for (boolean four : new boolean[] {false, true}) {
            for (int esz = 0; esz <= 1; esz++) {
                for (int count : new int[] {2, 4}) {
                    boolean pair = four && esz == 0 && count == 4;
                    int idxBits = (four ? LUTI4_IDX_BITS : LUTI2_IDX_BITS) - Integer.numberOfTrailingZeros(count);
                    for (int idx = 0; idx < (pair ? 1 : 1 << idxBits); idx++) {
                        int zd = count == 2 ? 5 : 3;
                        checkLut(svlBits, four, esz, count, true, zd, pair ? 20 : 17, idx, seed++);
                        checkLut(svlBits, four, esz, count, true, zd + 16, pair ? 20 : 17, idx, seed++);
                    }
                }
            }
        }
    }

    @Test
    void stridedAndConsecutiveProduceTheSameValuesInDifferentRegisters() {
        Aarch64Core consecutive = core(256);
        Aarch64Core strided = core(256);
        for (Aarch64Core core : new Aarch64Core[] {consecutive, strided}) {
            Random random = new Random(7);
            fillZRegisters(core, SENTINEL);
            randomZt0(core, random);
            randomZ(core, 4, random);
        }
        run(consecutive, lutWord(false, 1, 2, false, 2, 4, 5));
        run(strided, lutWord(false, 1, 2, true, 2, 4, 5));
        for (int w = 0; w < 4; w++) {
            assertEquals(consecutive.scalable().zWord(2, w), strided.scalable().zWord(2, w), "z2 igual");
            assertEquals(consecutive.scalable().zWord(3, w), strided.scalable().zWord(10, w), "z3 (consec) = z10 (strided)");
            assertEquals(SENTINEL, strided.scalable().zWord(3, w), "strided não toca z3");
            assertNotEquals(SENTINEL, consecutive.scalable().zWord(3, w), "consecutivo escreve z3");
        }
    }

    /// Dourado calculado À MÃO (não pela referência): SVL = 128 (16 bytes), `ZT0` com a entrada `i` = `0x100 + i`
    /// (nas 16 entradas de 32 bits), `Zn` com bytes `0b11_10_01_00` repetidos (índices 0,1,2,3,0,1,2,3…).
    /// `luti2 z1.b, zt0, z2[idx]` → 16 elementos, segmento `idx & 3` começa no campo `16 × idx`: `idx = 0` lê os
    /// campos 0..15 = `0,1,2,3,…`; o byte baixo da entrada `i` é `i`.
    @Test
    void goldenLuti2ByteSingleRegister() {
        Aarch64Core core = core(128);
        for (int i = 0; i < 16; i += 2) {
            core.matrix().setZt0Word(i / 2, ((0x100L + i + 1) << 32) | (0x100L + i));
        }
        for (int w = 0; w < 2; w++) {
            core.scalable().setZWord(2, w, 0xE4E4E4E4E4E4E4E4L);
        }
        run(core, lutWord(false, 0, 1, false, 1, 2, 0));
        for (int e = 0; e < 16; e++) {
            assertEquals(e % 4, zByte(core, 1, e), "elemento " + e);
        }
        // idx = 1: o segmento começa no campo 16 = bit 32 = byte 4 de Zn; o padrão se repete, então o resultado é igual
        run(core, lutWord(false, 0, 1, false, 5, 2, 1));
        for (int e = 0; e < 16; e++) {
            assertEquals(e % 4, zByte(core, 5, e), "idx 1 elemento " + e);
        }
        // Zn assimétrico: byte 4 = 0xFF (índices 3,3,3,3) distingue o segmento 1 do 0
        core.scalable().setZWord(2, 0, 0xE4E4E4FF_E4E4E4E4L);
        run(core, lutWord(false, 0, 1, false, 7, 2, 1));
        for (int e = 0; e < 4; e++) {
            assertEquals(3, zByte(core, 7, e), "segmento 1, elemento " + e + " lê o byte 4 (0xFF → índice 3)");
        }
        for (int e = 4; e < 16; e++) {
            assertEquals(e % 4, zByte(core, 7, e) , "segmento 1, resto: bytes 5.. = 0xE4 → 0,1,2,3");
        }
    }

    /// Dourado à mão da forma `LUTI4_c_4b`: lê `Zn` E `Zn+1` (SVL = 128 → 16 bytes cada, 4 registradores de destino ×
    /// 16 elementos × 4 bits = 32 bytes de índices). O destino `r` usa os campos `16 r`..`16 r + 15`.
    @Test
    void goldenLuti4FourRegistersReadsAPairOfSourceVectors() {
        Aarch64Core core = core(128);
        for (int i = 0; i < 16; i += 2) {
            core.matrix().setZt0Word(i / 2, ((0x100L + i + 1) << 32) | (0x100L + i));
        }
        // Zn (z8): campos 0..31 = 0..15,0..15 → bytes 0x10,0x32,0x54,… ; Zn+1 (z9): campos 32..63 = 15..0, 15..0
        long ascending = 0xFEDCBA9876543210L;
        long descending = 0x0123456789ABCDEFL;
        core.scalable().setZWord(8, 0, ascending);
        core.scalable().setZWord(8, 1, ascending);
        core.scalable().setZWord(9, 0, descending);
        core.scalable().setZWord(9, 1, descending);
        run(core, lutWord(true, 0, 4, false, 4, 8, 0));
        for (int e = 0; e < 16; e++) {
            assertEquals(e, zByte(core, 4, e), "z4 = campos 0..15");
            assertEquals(e, zByte(core, 5, e), "z5 = campos 16..31 = ascendente de novo");
            assertEquals(15 - e, zByte(core, 6, e), "z6 = campos 32..47 = descendente");
            assertEquals(15 - e, zByte(core, 7, e), "z7 = campos 48..63");
        }
    }

    @Test
    void lutWritesTheDestinationAfterReadingAnOverlappingSource() {
        Aarch64Core core = core(256);
        Random random = new Random(11);
        randomZt0(core, random);
        randomZ(core, 2, random);
        randomZ(core, 3, random);
        byte[] source = sourceBytes(core, 2, 1);
        // zd = z2 sobrepõe zn = z2 (e z3 também é destino)
        run(core, lutWord(false, 0, 2, false, 2, 2, 1));
        for (int r = 0; r < 2; r++) {
            for (int e = 0; e < svlBytes(core); e++) {
                assertEquals(expectedElement(core, source, false, 0, 2, 1, r, e) & 0xFF, zByte(core, 2 + r, e),
                        "reg " + r + " elem " + e);
            }
        }
    }

    @Test
    void lutOutsideStreamingModeEntersTheSmeTrapAndWritesNothing() {
        Aarch64Core core = core(256, SVCR_ZA, true);
        fillZRegisters(core, SENTINEL);
        run(core, lutWord(false, 0, 1, false, 1, 2, 0));
        assertEquals(SMTC_NOT_STREAMING, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
        assertEquals(SENTINEL, core.scalable().zWord(1, 0));
    }

    @Test
    void lutWithoutZt0AccessEntersTheZt0Trap() {
        Aarch64Core core = core(256, SVCR_SM | SVCR_ZA, false);
        run(core, lutWord(false, 0, 1, false, 1, 2, 0));
        assertEquals(SMTC_INACCESSIBLE_ZT0, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }

    @Test
    void lutWithZaDisabledEntersTheZaTrap() {
        Aarch64Core core = core(256, SVCR_SM, true);
        run(core, lutWord(false, 0, 1, false, 1, 2, 0));
        assertEquals(SMTC_INACTIVE_ZA, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
    }
}
