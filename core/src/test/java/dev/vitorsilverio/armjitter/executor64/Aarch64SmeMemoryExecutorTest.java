package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.4 — `LD1`/`ST1` de tile, `LDR`/`STR` de `ZA` e de `ZT0`. As palavras montadas por {@link #ldst1} foram
/// conferidas contra `aarch64-none-elf-as` em `Aarch64SmeMemoryDecoderTest` (mesmos campos, mesma construção).
class Aarch64SmeMemoryExecutorTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-mem-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;
    private static final long SOURCE = 0x800L;
    private static final long DESTINATION = 0xA00L;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int ESZ_QUAD = 4;

    private static final int LDR_ZA = 0xE1000000;
    private static final int STR_ZA = 0xE1200000;
    private static final int LDR_ZT0 = 0xE11F8000;
    private static final int STR_ZT0 = 0xE13F8000;

    /// Monta um `LDST1` (campo a campo, como o `.decode`): `za:esz` e `off:(4-esz)` nos 4 bits baixos.
    private static int ldst1(int esz, boolean store, boolean vertical, int tile, int off, int pg, int rn, int rm,
            int rsField) {
        int word = esz == ESZ_QUAD ? 0xE1C00000 : 0xE0000000 | (esz << 22);
        word |= (store ? 1 : 0) << 21 | rm << 16 | (vertical ? 1 : 0) << 15 | rsField << 13 | pg << 10 | rn << 5;
        int offsetWidth = 4 - esz;
        return word | tile << offsetWidth | off;
    }

    private static Aarch64Core core(Aarch64Architecture architecture, int svl, long svcr) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, svl,
                svl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(svcr);
        return core;
    }

    private static Aarch64Core core(int svl) {
        return core(Aarch64Architecture.ARMV9_2_A, svl, SVCR_SM | SVCR_ZA);
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int... words) {
        for (int i = 0; i < words.length; i++) {
            core.memory().write32(i * 4L, words[i]);
        }
        core.setProgramCounter(0);
        Ir64BlockExecutor executor = new Ir64BlockExecutor(architecture);
        for (int i = 0; i < words.length; i++) {
            executor.step(core);
        }
    }

    private static void run(Aarch64Core core, int... words) {
        run(Aarch64Architecture.ARMV9_2_A, core, words);
    }

    private static void allTrue(Aarch64Core core, int pg) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerPredicate(); w++) {
            regs.setPWord(pg, w, -1L);
        }
    }

    private static int patternByte(int index) {
        return (index * 7 + 1) & 0xFF;
    }

    private static void fillMemory(Aarch64Core core, long address, int bytes) {
        for (int i = 0; i < bytes; i++) {
            core.memory().write8(address + i, patternByte(i));
        }
    }

    private static void fillMemoryWith(Aarch64Core core, long address, int bytes, int value) {
        for (int i = 0; i < bytes; i++) {
            core.memory().write8(address + i, value);
        }
    }

    private static int memoryByte(Aarch64Core core, long address) {
        return core.memory().read8(address) & 0xFF;
    }

    private static int zaByte(Aarch64Core core, int row, int byteIndex) {
        Aarch64MatrixRegisters matrix = core.matrix();
        long word = matrix.zaWord(row * (matrix.zaRowBytes() / Long.BYTES) + byteIndex / Long.BYTES);
        return (int) ((word >>> ((byteIndex % Long.BYTES) * Byte.SIZE)) & 0xFF);
    }

    private static void fillZaRowsWithRowIndexByte(Aarch64Core core, int svlBytes) {
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int row = 0; row < svlBytes; row++) {
            for (int w = 0; w < rowWords; w++) {
                matrix.setZaWord(row * rowWords + w, (row & 0xFFL) * 0x0101010101010101L);
            }
        }
    }

    // ── LD1 / ST1: ida e volta por memória, todos os esz, os dois eixos ──────────────────────────

    @ParameterizedTest
    @CsvSource({
            "0,false,256", "0,true,256", "1,false,256", "1,true,256", "2,false,256", "2,true,256",
            "3,false,256", "3,true,256", "4,false,256", "4,true,256",
            "0,false,512", "0,true,512", "1,false,512", "1,true,512", "2,false,512", "2,true,512",
            "3,false,512", "3,true,512", "4,false,512", "4,true,512"})
    void tileSliceRoundTripsThroughMemoryAndLandsWhereTheTileAddressingSays(int esz, boolean vertical, int svl) {
        int svlBytes = svl / Byte.SIZE;
        int tile = (1 << esz) - 1;
        int off = esz == ESZ_QUAD ? 0 : 1;
        Aarch64Core core = core(svl);
        allTrue(core, 0);
        fillMemory(core, SOURCE, svlBytes);
        core.setX(0, SOURCE);
        run(core, ldst1(esz, false, vertical, tile, off, 0, 0, 1, 0));

        int size = 1 << esz;
        int elements = svlBytes / size;
        for (int e = 0; e < elements; e++) {
            int row = Aarch64MatrixTileAddressing.rowIndex(tile, esz, vertical ? e : off);
            int base = (vertical ? off : e) * size;
            for (int b = 0; b < size; b++) {
                assertEquals(patternByte(e * size + b), zaByte(core, row, base + b),
                        "esz=" + esz + " elemento " + e + " byte " + b);
            }
        }

        core.setX(0, DESTINATION);
        run(core, ldst1(esz, true, vertical, tile, off, 0, 0, 1, 0));
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(patternByte(i), memoryByte(core, DESTINATION + i), "memória byte " + i);
        }
    }

    // ── Vertical realmente transpõe ──────────────────────────────────────────────────────────────

    @Test
    void verticalStoreWritesTheTransposeOfTheHorizontalOne() {
        int svlBytes = 32;
        Aarch64Core core = core(256);
        allTrue(core, 0);
        fillZaRowsWithRowIndexByte(core, svlBytes); // linha r = bytes todos == r
        core.setX(0, DESTINATION);
        run(core, ldst1(0, true, false, 0, 5, 0, 0, 31, 0)); // slice horizontal 5
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(5, memoryByte(core, DESTINATION + i), "horizontal: linha 5 inteira");
        }
        core.setX(0, DESTINATION + 0x100);
        run(core, ldst1(0, true, true, 0, 5, 0, 0, 31, 0)); // slice vertical 5
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(i, memoryByte(core, DESTINATION + 0x100 + i), "vertical: byte 5 de cada linha i = i");
        }
    }

    @Test
    void verticalLoadFillsOneColumnAndLeavesTheRestAlone() {
        int svlBytes = 32;
        Aarch64Core core = core(256);
        allTrue(core, 0);
        fillMemory(core, SOURCE, svlBytes);
        core.setX(0, SOURCE);
        run(core, ldst1(0, false, true, 0, 3, 0, 0, 31, 0));
        for (int r = 0; r < svlBytes; r++) {
            assertEquals(patternByte(r), zaByte(core, r, 3), "coluna 3, linha " + r);
            assertEquals(0, zaByte(core, r, 2), "coluna 2 intacta, linha " + r);
            assertEquals(0, zaByte(core, r, 4), "coluna 4 intacta, linha " + r);
        }
    }

    // ── Endereçamento ────────────────────────────────────────────────────────────────────────────

    @Test
    void rnEncoding31IsTheStackPointerNotTheZeroRegister() {
        Aarch64Core core = core(256);
        allTrue(core, 0);
        fillMemory(core, SOURCE, 32);
        core.setSp(SOURCE); // se rn=31 virasse XZR, leria do endereço 0 (as instruções)
        run(core, ldst1(0, false, false, 0, 0, 0, STACK_POINTER_ENCODING, 31, 0));
        for (int i = 0; i < 32; i++) {
            assertEquals(patternByte(i), zaByte(core, 0, i), "byte " + i);
        }
    }

    @Test
    void indexRegisterIsScaledByTheElementSize() {
        Aarch64Core core = core(256);
        allTrue(core, 0);
        fillMemory(core, SOURCE, 64);
        core.setX(0, SOURCE);
        core.setX(1, 1); // esz=3 => +8 bytes, não +1
        run(core, ldst1(3, false, false, 0, 0, 0, 0, 1, 0));
        for (int i = 0; i < 8; i++) {
            assertEquals(patternByte(8 + i), zaByte(core, 0, i), "primeiro elemento D vem de SOURCE+8, byte " + i);
        }
    }

    @Test
    void sliceIndexComesFromW12ToW15AndIsModular() {
        Aarch64Core core = core(256);
        allTrue(core, 0);
        fillMemory(core, SOURCE, 32);
        core.setX(0, SOURCE);
        core.setX(14, 40); // rs=W14 (campo 2): (40 + 1) MOD 32 = 9
        run(core, ldst1(0, false, false, 0, 1, 0, 0, 31, 2));
        for (int i = 0; i < 32; i++) {
            assertEquals(patternByte(i), zaByte(core, 9, i), "linha 9, byte " + i);
        }
    }

    // ── Predicado ────────────────────────────────────────────────────────────────────────────────

    @Test
    void inactiveLoadLanesBecomeZeroInsteadOfKeepingTheOldValue() {
        Aarch64Core core = core(256);
        Aarch64ScalableRegisters regs = core.scalable();
        regs.setPWord(0, 0, 0b101L); // só bytes 0 e 2 ativos
        fillMemoryWith(core, SOURCE, 32, 0x5A);
        core.setX(0, SOURCE);
        Aarch64MatrixRegisters matrix = core.matrix();
        for (int w = 0; w < matrix.zaRowBytes() / Long.BYTES; w++) {
            matrix.setZaWord(w, -1L); // linha 0 cheia de 0xFF
        }
        run(core, ldst1(0, false, false, 0, 0, 0, 0, 31, 0));
        assertEquals(0x5A, zaByte(core, 0, 0));
        assertEquals(0, zaByte(core, 0, 1), "inativo: zerado, não preserva 0xFF");
        assertEquals(0x5A, zaByte(core, 0, 2));
        for (int i = 3; i < 32; i++) {
            assertEquals(0, zaByte(core, 0, i), "byte " + i);
        }
    }

    @Test
    void inactiveVerticalLoadLaneIsZeroedToo() {
        Aarch64Core core = core(256);
        core.scalable().setPWord(0, 0, 0b10L); // só a linha 1 ativa
        fillMemoryWith(core, SOURCE, 32, 0x77);
        core.setX(0, SOURCE);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int row = 0; row < 32; row++) {
            for (int w = 0; w < rowWords; w++) {
                matrix.setZaWord(row * rowWords + w, -1L);
            }
        }
        run(core, ldst1(0, false, true, 0, 4, 0, 0, 31, 0));
        assertEquals(0, zaByte(core, 0, 4), "linha 0 inativa: coluna zerada");
        assertEquals(0x77, zaByte(core, 1, 4), "linha 1 ativa");
        assertEquals(0, zaByte(core, 2, 4), "linha 2 inativa");
        assertEquals(0xFF, zaByte(core, 0, 3), "outras colunas intactas");
    }

    @Test
    void inactiveStoreLanesDoNotTouchMemory() {
        Aarch64Core core = core(256);
        core.scalable().setPWord(0, 0, 0b1001L); // bytes 0 e 3 ativos
        fillZaRowsWithRowIndexByte(core, 32);
        fillMemoryWith(core, DESTINATION, 32, 0xAA);
        core.setX(0, DESTINATION);
        run(core, ldst1(0, true, false, 0, 6, 0, 0, 31, 0)); // linha 6 = bytes 6
        assertEquals(6, memoryByte(core, DESTINATION));
        assertEquals(0xAA, memoryByte(core, DESTINATION + 1), "sentinela preservada");
        assertEquals(0xAA, memoryByte(core, DESTINATION + 2), "sentinela preservada");
        assertEquals(6, memoryByte(core, DESTINATION + 3));
        for (int i = 4; i < 32; i++) {
            assertEquals(0xAA, memoryByte(core, DESTINATION + i), "sentinela " + i);
        }
    }

    @Test
    void predicateBitsLiveAtTheFirstByteOfEachWideElement() {
        Aarch64Core core = core(256);
        core.scalable().setPWord(0, 0, 1L | (1L << 8)); // elementos W 0 e 2 (bytes 0 e 8)
        fillMemoryWith(core, SOURCE, 32, 0x33);
        core.setX(0, SOURCE);
        run(core, ldst1(2, false, false, 0, 0, 0, 0, 31, 0));
        assertEquals(0x33, zaByte(core, 0, 0));
        assertEquals(0, zaByte(core, 0, 4), "elemento 1 inativo");
        assertEquals(0x33, zaByte(core, 0, 8));
        assertEquals(0, zaByte(core, 0, 12), "elemento 3 inativo");
    }

    // ── LDR / STR de vetor de ZA ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void arrayVectorRoundTripMovesExactlyOneRowAndUsesTheModularIndex(int svl) {
        int svlBytes = svl / Byte.SIZE;
        Aarch64Core core = core(svl);
        fillMemory(core, SOURCE, svlBytes);
        core.setSp(SOURCE - 15L * svlBytes);
        core.setX(15, 3); // W15
        // ldr za[w15, 15], [sp, #15, mul vl]  (conferido contra o assembler: 0xe10063ef)
        run(core, LDR_ZA | 3 << 13 | STACK_POINTER_ENCODING << 5 | 15);
        int row = (3 + 15) % svlBytes;
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(patternByte(i), zaByte(core, row, i), "linha " + row + " byte " + i);
        }
        assertEquals(0, zaByte(core, row + 1, 0), "a linha vizinha não é tocada");
        assertEquals(0, zaByte(core, row - 1, 0), "a linha vizinha não é tocada");

        fillMemoryWith(core, DESTINATION, 2 * svlBytes, 0xEE);
        core.setX(1, DESTINATION - 7L * svlBytes);
        core.setX(13, row - 7L); // W13: (row - 7 + 7) MOD svlBytes = row
        run(core, STR_ZA | 1 << 13 | 1 << 5 | 7);
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(patternByte(i), memoryByte(core, DESTINATION + i), "STR byte " + i);
        }
        assertEquals(0xEE, memoryByte(core, DESTINATION + svlBytes), "STR não passa de SVL/8 bytes");
    }

    @Test
    void arrayVectorLoadStoreWorksOutsideStreamingModeWithOnlyZaEnabled() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256, SVCR_ZA);
        fillMemory(core, SOURCE, 32);
        core.setX(0, SOURCE);
        run(core, LDR_ZA);
        for (int i = 0; i < 32; i++) {
            assertEquals(patternByte(i), zaByte(core, 0, i), "byte " + i);
        }
    }

    @Test
    void tileLoadOutsideStreamingModeEntersTheSmeTrap() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256, SVCR_ZA);
        allTrue(core, 0);
        run(core, ldst1(0, false, false, 0, 0, 0, 0, 31, 0));
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    @Test
    void arrayVectorWithZaDisabledEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256, 0);
        fillMemory(core, SOURCE, 32);
        core.setX(0, SOURCE);
        run(core, LDR_ZA);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    // ── LDR / STR de ZT0 ─────────────────────────────────────────────────────────────────────────

    private static Aarch64Core zt0Core() {
        Aarch64Core core = core(SME2, 256, SVCR_ZA);
        // `SMCR_ELx.EZT0` (bit 30) precisa estar ligado — sem isso `ZT0` fica inacessível (B18.1).
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, (1L << 30) | 0xF);
        return core;
    }

    @Test
    void zt0RoundTripMovesExactly64Bytes() {
        Aarch64Core core = zt0Core();
        fillMemory(core, SOURCE, 64);
        core.setX(0, SOURCE);
        run(SME2, core, LDR_ZT0);
        for (int w = 0; w < 8; w++) {
            long expected = 0;
            for (int b = 7; b >= 0; b--) {
                expected = (expected << 8) | patternByte(w * 8 + b);
            }
            assertEquals(expected, core.matrix().zt0Word(w), "palavra " + w);
        }
        fillMemoryWith(core, DESTINATION, 80, 0xEE);
        core.setSp(DESTINATION);
        run(SME2, core, STR_ZT0 | STACK_POINTER_ENCODING << 5);
        for (int i = 0; i < 64; i++) {
            assertEquals(patternByte(i), memoryByte(core, DESTINATION + i), "byte " + i);
        }
        assertEquals(0xEE, memoryByte(core, DESTINATION + 64), "não passa de 64 bytes");
    }

    @Test
    void zt0WithoutSmcrEzt0EntersTheSmeTrap() {
        Aarch64Core core = core(SME2, 256, SVCR_ZA);
        run(SME2, core, LDR_ZT0);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    @Test
    void zt0StoreWithoutAccessEntersTheSmeTrap() {
        Aarch64Core core = core(SME2, 256, SVCR_ZA);
        run(SME2, core, STR_ZT0);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }
}
