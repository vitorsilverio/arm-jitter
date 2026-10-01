package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.3 — execução de `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ`. Palavras construídas a partir das mesmas
/// máscaras/valores conferidos em {@code Aarch64SmeDecoderTest} contra `aarch64-none-elf-as`.
class Aarch64SmeMovaExecutorTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-exec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2P1 = Aarch64Architecture.extending(
            SME2, "teste-exec-SME2p1", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;

    // ── MOVA_tz/MOVA_zt (predicada), esz=0 (byte), tile=0, pg=P0, rs=W12 ────────────────────────
    private static final int MOVA_TZ_ESZ0_BASE = 0xC0000000; // zr no shift 5
    private static final int MOVA_ZT_ESZ0_BASE = 0xC0020000; // zr no shift 0, off no shift 5
    private static final int V_BIT = 1 << 15;

    private static Aarch64Core core(Aarch64Architecture architecture, int svl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, svl,
                svl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(SVCR_SM | SVCR_ZA);
        return core;
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

    private static void allTruePredicate(Aarch64Core core, int pg) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerPredicate(); w++) {
            regs.setPWord(pg, w, -1L);
        }
    }

    /// Preenche cada linha `r` de `ZA` (`0`..`svlBytes-1`) com o byte `r` repetido — o padrão que faz
    /// horizontal (constante) e vertical (rampa) divergirem de forma inequívoca.
    private static void fillRowsWithRowIndexByte(Aarch64Core core, int svlBytes) {
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int row = 0; row < svlBytes; row++) {
            long pattern = (row & 0xFFL) * 0x0101010101010101L;
            for (int w = 0; w < rowWords; w++) {
                matrix.setZaWord(row * rowWords + w, pattern);
            }
        }
    }

    // ── `MOVA_tz`/`MOVA_zt` — ida e volta ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void horizontalRoundTripPreservesThePattern(int svl) {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, svl);
        Aarch64ScalableRegisters regs = core.scalable();
        allTruePredicate(core, 0);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(0, w, 0xA5A5_5A5A_DEAD_BEEFL + w);
        }
        int storeTz = MOVA_TZ_ESZ0_BASE; // zr=Z0, tile=0, off=0, pg=P0
        int loadZt = MOVA_ZT_ESZ0_BASE | 1; // zr=Z1
        run(Aarch64Architecture.ARMV9_2_A, core, storeTz, loadZt);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            assertEquals(regs.zWord(0, w), regs.zWord(1, w), "palavra " + w);
        }
    }

    @Test
    void horizontalDiffersFromVerticalForTheSameOffset() {
        int svlBytes = 32; // SVL=256
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        allTruePredicate(core, 0);
        fillRowsWithRowIndexByte(core, svlBytes);
        int off = 5;
        int horizontal = MOVA_ZT_ESZ0_BASE | 2 | (off << 5); // zr=Z2
        int vertical = horizontal | V_BIT; // mesmo off, eixo vertical, zr continua Z2 (sobrescreve)
        run(Aarch64Architecture.ARMV9_2_A, core, horizontal);
        Aarch64ScalableRegisters regs = core.scalable();
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(off, byteOf(regs, 2, i), "horizontal: linha " + off + " inteira, byte " + i);
        }
        run(Aarch64Architecture.ARMV9_2_A, core, vertical);
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(i, byteOf(regs, 2, i), "vertical: byte " + off + " de cada linha i, rampa");
        }
    }

    @Test
    void registerIndexIsModularOverTheEffectiveSvl() {
        int svlBytes = 32;
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        allTruePredicate(core, 0);
        fillRowsWithRowIndexByte(core, svlBytes);
        core.setX(12, 40); // W12 = 40, (40 + 0) MOD 32 = 8
        int load = MOVA_ZT_ESZ0_BASE | 3; // zr=Z3, off=0
        run(Aarch64Architecture.ARMV9_2_A, core, load);
        Aarch64ScalableRegisters regs = core.scalable();
        for (int i = 0; i < svlBytes; i++) {
            assertEquals(8, byteOf(regs, 3, i), "(40+0) MOD 32 = 8: lê a linha 8");
        }
    }

    @Test
    void predicateFalseKeepsTheOldValueInBothDirections() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        Aarch64ScalableRegisters regs = core.scalable();
        // P0: só os 4 primeiros bytes ativos.
        regs.setPWord(0, 0, 0xFL);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(4, w, -1L); // Z4 = tudo 1, origem do store
            regs.setZWord(5, w, 0x1234_5678_9ABC_DEF0L); // Z5 = destino pré-existente do load
        }
        int store = MOVA_TZ_ESZ0_BASE | (4 << 5); // ZA[tile0,row0] = merge(Z4) sob P0
        int load = MOVA_ZT_ESZ0_BASE | 5; // Z5 = merge(ZA[tile0,row0]) sob P0
        run(Aarch64Architecture.ARMV9_2_A, core, store, load);
        for (int i = 0; i < 4; i++) {
            assertEquals(0xFF, byteOf(regs, 5, i), "byte " + i + " ativo: sobrescrito por Z4=0xFF");
        }
        for (int i = 4; i < 32; i++) {
            assertEquals((0x1234_5678_9ABC_DEF0L >>> ((i % 8) * 8)) & 0xFF, byteOf(regs, 5, i),
                    "byte " + i + " inativo: preserva o valor antigo de Z5");
        }
    }

    // ── `rs` (`W12`-`W15`) × `rv` (`W8`-`W11`) — Armadilha 2 ────────────────────────────────────

    @Test
    void groupFormUsesRsAndArrayFormUsesRv() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2, 256);
        fillRowsWithRowIndexByte(core, svlBytes);
        core.setX(8, 3); // W8 (rv)
        core.setX(12, 6); // W12 (rs), já múltiplo de 2 (group=2)

        // MOVA_za2: {z4.b-z5.b} <- za.b[w8, 0] — linhas base = (3+0) MOD 16 = 3, depois 3+16=19.
        int movaZa2 = 0xC0060800 | (2 << 1); // zr group = Z4/2 = 2
        // MOVA_zt2: {z6.b-z7.b} <- za0h.b[w12, 0:1] — slices (6+0) e (6+1) = linhas 6 e 7.
        int movaZt2 = 0xC0060000 | (3 << 1); // zr group = Z6/2 = 3
        run(SME2, core, movaZa2, movaZt2);

        Aarch64ScalableRegisters regs = core.scalable();
        assertEquals(3, byteOf(regs, 4, 0), "rv: primeira linha do grupo array = (W8+off) MOD (svl/n)");
        assertEquals(3 + 16, byteOf(regs, 5, 0), "rv: segunda linha = base + svl/n");
        assertEquals(6, byteOf(regs, 6, 0), "rs: primeira slice do grupo de tile = W12 (já múltiplo de 2) + 0");
        assertEquals(7, byteOf(regs, 7, 0), "rs: segunda slice = W12 + 1");
    }

    // ── `MOVAZ` — zera a origem lida ─────────────────────────────────────────────────────────────

    @Test
    void movazZtZeroesTheRowItRead() {
        Aarch64Core core = core(SME2P1, 256);
        Aarch64MatrixRegisters matrix = core.matrix();
        matrix.setZaWord(0, 0x1122_3344_5566_7788L); // linha 0 (tile 0, off 0), primeira palavra
        int movazZt = 0xC0020200; // movaz z0.b, za0h.b[w12, 0]
        run(SME2P1, core, movazZt);
        assertEquals(0x1122_3344_5566_7788L, core.scalable().zWord(0, 0), "leu o valor antigo");
        assertEquals(0L, matrix.zaWord(0), "a linha lida foi zerada");
    }

    @Test
    void movazZaZeroesEveryRowOfTheGroup() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2P1, 256);
        fillRowsWithRowIndexByte(core, svlBytes);
        core.setX(8, 0);
        int movazZa2 = 0xC0060A00 | (2 << 1); // movaz {z4.b-z5.b}, za.b[w8, 0]
        run(SME2P1, core, movazZa2);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        assertEquals(0L, matrix.zaWord(0 * rowWords), "linha 0 zerada");
        assertEquals(0L, matrix.zaWord(16 * rowWords), "linha 16 (base + svl/2) também zerada");
        assertEquals(1L * 0x0101010101010101L, matrix.zaWord(1 * rowWords), "linha 1, fora do grupo, intocada");
    }

    // ── `ZERO`/`ZERO_zt0` ────────────────────────────────────────────────────────────────────────

    @Test
    void zeroClearsRowsByBitModuloEight() {
        int svlBytes = 32;
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        fillRowsWithRowIndexByte(core, svlBytes);
        int zeroBit0 = 0xC0080000 | 0x01; // imm8 = 0b0000_0001: zera linhas 0, 8, 16, 24
        run(Aarch64Architecture.ARMV9_2_A, core, zeroBit0);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int row = 0; row < svlBytes; row++) {
            long expected = (row % 8 == 0) ? 0L : (row & 0xFFL) * 0x0101010101010101L;
            assertEquals(expected, matrix.zaWord(row * rowWords), "linha " + row);
        }
    }

    @Test
    void zeroZt0ClearsTheWholeRegister() {
        Aarch64Core core = core(SME2, 256);
        // `SMCR_ELx.EZT0` precisa estar ligado (bit 30) — sem isso `ZT0` fica inacessível (B18.1).
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, (1L << 30) | 0xF);
        Aarch64MatrixRegisters matrix = core.matrix();
        matrix.setZt0Word(0, -1L);
        matrix.setZt0Word(7, -1L);
        run(SME2, core, 0xC0480001);
        for (int w = 0; w < Aarch64MatrixRegisters.ZT0_BITS / Long.SIZE; w++) {
            assertEquals(0L, matrix.zt0Word(w), "palavra " + w);
        }
    }

    private static long byteOf(Aarch64ScalableRegisters regs, int reg, int index) {
        long word = regs.zWord(reg, index / 8);
        return (word >>> ((index % 8) * 8)) & 0xFF;
    }

    // ── Acesso negado (`SM`/`ZA` desligados) ────────────────────────────────────────────────────

    @Test
    void zeroWithoutAccessEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)),
                Aarch64Architecture.ARMV9_2_A, 256, 256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(Aarch64Architecture.ARMV9_2_A, core, 0xC00800ff);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    @Test
    void zeroZt0WithoutAccessEntersTheSmeTrap() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), SME2, 256, 256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(SME2, core, 0xC0480001);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    @Test
    void movaWithoutAccessEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)),
                Aarch64Architecture.ARMV9_2_A, 256, 256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(Aarch64Architecture.ARMV9_2_A, core, MOVA_TZ_ESZ0_BASE);
        assertEquals(0x1DL, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=SME access trap");
    }

    // ── Vertical com predicado parcial (merging) ────────────────────────────────────────────────

    @Test
    void verticalPredicateFalseKeepsTheOldValue() {
        int svlBytes = 32;
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        Aarch64ScalableRegisters regs = core.scalable();
        fillRowsWithRowIndexByte(core, svlBytes);
        // P0: só os primeiros 4 elementos (bytes, esz=0) ativos.
        regs.setPWord(0, 0, 0xFL);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(6, w, -1L); // Z6 pré-existente (destino do load vertical)
        }
        int verticalLoad = MOVA_ZT_ESZ0_BASE | 6; // off=0 -> byteOffset=0
        run(Aarch64Architecture.ARMV9_2_A, core, verticalLoad | V_BIT);
        for (int k = 0; k < 4; k++) {
            assertEquals(k, byteOf(regs, 6, k), "elemento " + k + " ativo: ZA[linha k][byte 0] = k");
        }
        for (int k = 4; k < svlBytes; k++) {
            assertEquals(0xFF, byteOf(regs, 6, k), "elemento " + k + " inativo: preserva Z6 antigo");
        }
    }

    // ── Multi-vetor: horizontal armazenando em `ZA`, vertical, e array armazenando em `ZA` ───────

    @Test
    void groupHorizontalStoresWholeRowsIntoZa() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2, 256);
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(8, w, 0x1111_1111_1111_1111L);
            regs.setZWord(9, w, 0x2222_2222_2222_2222L);
        }
        core.setX(12, 6); // W12 par: slices 6 e 7, sem arredondamento visível
        int storeTz2 = 0xC0040000 | (4 << 6); // zr group = Z8/2 = 4
        run(SME2, core, storeTz2);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int w = 0; w < rowWords; w++) {
            assertEquals(0x1111_1111_1111_1111L, matrix.zaWord(6 * rowWords + w), "linha 6 = Z8");
            assertEquals(0x2222_2222_2222_2222L, matrix.zaWord(7 * rowWords + w), "linha 7 = Z9");
        }
    }

    @Test
    void groupVerticalGathersOneColumnPerSubVector() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2, 256);
        fillRowsWithRowIndexByte(core, svlBytes);
        core.setX(12, 0);
        // mov {z4.h-z5.h}, za0v.h[w12, 0:1] — esz=1 (H), tile=0, off=0.
        int verticalZt2 = 0xC0460000 | (2 << 1) | V_BIT; // zr group = Z4/2 = 2
        run(SME2, core, verticalZt2);
        Aarch64ScalableRegisters regs = core.scalable();
        // Sub-vetor 0 (Z4): coluna 0 do tile — linha física k*2, byte 0 = (k*2) repetido no halfword.
        assertEquals((0L & 0xFF) * 0x0101L, regs.zWord(4, 0) & 0xFFFF, "elemento 0 de Z4");
        assertEquals((2L & 0xFF) * 0x0101L, (regs.zWord(4, 0) >>> 16) & 0xFFFF, "elemento 1 de Z4");
        // Sub-vetor 1 (Z5): coluna 1 — byte 1 de cada linha física k*2 (mesmo byte, linha repete valor).
        assertEquals((0L & 0xFF) * 0x0101L, regs.zWord(5, 0) & 0xFFFF, "elemento 0 de Z5");
    }

    @Test
    void movazGroupVerticalZeroesOnlyTheColumnRead() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2P1, 256);
        fillRowsWithRowIndexByte(core, svlBytes);
        core.setX(12, 0);
        // movaz {z4.h-z5.h}, za0v.h[w12, 0:1]
        int movazVertical = 0xC0460200 | (2 << 1) | V_BIT;
        run(SME2P1, core, movazVertical);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        // Linha física 2 (k=1, tile 0, esz=1): os sub-vetores 0 e 1 zeram as colunas 0 e 1 (byte
        // offsets 0 e 2) — juntos, os 4 bytes BAIXOS da linha; os 4 bytes ALTOS (fora da coluna lida)
        // preservam o padrão original (row = 2 -> byte 2 em todo lugar).
        long word2 = matrix.zaWord(2 * rowWords);
        assertEquals(0L, word2 & 0xFFFF_FFFFL, "colunas 0 e 1 (bytes 0-3) da linha 2 zeradas");
        assertEquals(0x0202_0202L, (word2 >>> 32) & 0xFFFF_FFFFL, "bytes 4-7 preservam o padrão (byte=2)");
    }

    @Test
    void arrayHorizontalStoresWholeRowsIntoZa() {
        int svlBytes = 32;
        Aarch64Core core = core(SME2, 256);
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(4, w, 0x3333_3333_3333_3333L);
            regs.setZWord(5, w, 0x4444_4444_4444_4444L);
        }
        core.setX(8, 5); // rv = W8
        int storeAz2 = 0xC0040800 | (2 << 6); // zr group = Z4/2 = 2
        run(SME2, core, storeAz2);
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        for (int w = 0; w < rowWords; w++) {
            assertEquals(0x3333_3333_3333_3333L, matrix.zaWord(5 * rowWords + w), "linha base = (5+0) MOD 16 = 5");
            assertEquals(0x4444_4444_4444_4444L, matrix.zaWord(21 * rowWords + w), "linha base + 16 = 21");
        }
    }

    // ── `esz = Q` (128 bits, par de palavras) e `esz = D` (64 bits, palavra inteira) ────────────

    @Test
    void verticalQuadRoundTripAndZero() {
        Aarch64Core core = core(SME2P1, 256); // svlBytes = 32, elementsPerRow(esz=4) = 2
        Aarch64ScalableRegisters regs = core.scalable();
        allTruePredicate(core, 0);
        regs.setZWord(0, 0, 0x1111_1111_1111_1111L);
        regs.setZWord(0, 1, 0x2222_2222_2222_2222L);
        regs.setZWord(0, 2, 0x3333_3333_3333_3333L);
        regs.setZWord(0, 3, 0x4444_4444_4444_4444L);
        int storeTzQVertical = 0xC0C18000; // mov za0v.q[w12, 0], p0/m, z0.q
        int loadZtQVertical = 0xC0C38000 | 1; // mov z1.q, p0/m, za0v.q[w12, 0]
        run(SME2P1, core, storeTzQVertical, loadZtQVertical);
        for (int w = 0; w < 4; w++) {
            assertEquals(regs.zWord(0, w), regs.zWord(1, w), "palavra " + w + " (ida e volta, 128 bits)");
        }
        // movaz z2.q, za0v.q[w12, 0] — lê o MESMO par de elementos e zera a origem.
        int movazQVertical = 0xC0C30200 | V_BIT | 2;
        run(SME2P1, core, movazQVertical);
        for (int w = 0; w < 4; w++) {
            assertEquals(regs.zWord(0, w), regs.zWord(2, w), "movaz leu o valor antigo (128 bits)");
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowWords = matrix.zaRowBytes() / Long.BYTES;
        assertEquals(0L, matrix.zaWord(0 * rowWords), "linha 0 (elemento 0) zerada");
        assertEquals(0L, matrix.zaWord(16 * rowWords), "linha 16 (elemento 1) zerada");
    }

    @Test
    void verticalDoublewordRoundTrip() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_2_A, 256);
        Aarch64ScalableRegisters regs = core.scalable();
        allTruePredicate(core, 0);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            regs.setZWord(0, w, 0x0102_0304_0506_0708L + w);
        }
        int storeTzDVertical = 0xC0C00000 | V_BIT; // mov za0v.d[w12, 0], p0/m, z0.d
        int loadZtDVertical = (0xC0C20000 | V_BIT) | 1; // mov z1.d, p0/m, za0v.d[w12, 0]
        run(Aarch64Architecture.ARMV9_2_A, core, storeTzDVertical, loadZtDVertical);
        for (int w = 0; w < regs.wordsPerVector(); w++) {
            assertEquals(regs.zWord(0, w), regs.zWord(1, w), "palavra " + w);
        }
    }
}
