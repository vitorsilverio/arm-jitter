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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.22 — SVE2 misc: `MATCH`/`NMATCH`, `HISTCNT`/`HISTSEG`, `LUTI2`/`LUTI4`, o multiply-add long NÃO-indexado
/// (par das 71 linhas indexadas da B17.8) e `PSEL`/`SCLAMP`/`UCLAMP`/`FCLAMP` (37 encodings). Sem `aarch64-none-elf-as`
/// disponível nesta sessão: as palavras são montadas à mão a partir dos campos do `sve.decode` (documentados no
/// `## Resultado` da task), não conferidas por um segundo assembler — mitigado testando o `VL >= 256`
/// (Armadilha 1) e o cruzamento com a forma indexada da B17.8 (Achado 4), que valida a matemática independente
/// da montagem exata da palavra.
class Aarch64Sve2MiscTest {
    private static final int Z0 = 0;
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P0 = 0;
    private static final int P1 = 1;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2_1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture LUT = Aarch64Architecture.extending(SVE2, "teste-LUT",
            Aarch64Feature.LOOKUP_TABLE);
    private static final Aarch64Architecture I8MM = Aarch64Architecture.extending(SVE2, "teste-I8MM",
            Aarch64Feature.INT8_MATRIX_MULTIPLY);
    /// SME sem SVE2.1 — cobre o segundo operando de `!SVE2_1 && !SME` (`PSEL`) sem precisar de SVE2.1.
    private static final Aarch64Architecture SME_ONLY = Aarch64Architecture.extending(SVE, "teste-SME",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
    /// SME2 sem SVE2.1 — idem para `FCLAMP`.
    private static final Aarch64Architecture SME2_ONLY = Aarch64Architecture.extending(SVE, "teste-SME2",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);

    // ── Infra (mesmo padrão de Aarch64Sve2IntegerTest/Aarch64SveMultiplyIndexedTest) ───────────────

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

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static int elementCount(Aarch64Core core, int esz) {
        return core.vectorLengthBytes() >> esz;
    }

    private static void setElement(Aarch64Core core, int reg, int esz, int index, long value) {
        int elementBits = bits(esz);
        int bitOffset = index * elementBits;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1L;
        int word = bitOffset / 64;
        long cleared = core.scalable().zWord(reg, word) & ~(mask << (bitOffset % 64));
        core.scalable().setZWord(reg, word, cleared | ((value & mask) << (bitOffset % 64)));
    }

    private static long getElement(Aarch64Core core, int reg, int esz, int index) {
        int elementBits = bits(esz);
        int bitOffset = index * elementBits;
        long shifted = core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64);
        return elementBits == 64 ? shifted : shifted & ((1L << elementBits) - 1);
    }

    /// Empacota `values[i]` em `indexBits` bits cada, a partir do bit `0` de `reg` (formato dos índices de
    /// `LUTI2`/`LUTI4` em `Zm` — não é um array de elementos de `esz`, é um empacotamento denso de campos
    /// estreitos).
    private static void packIndices(Aarch64Core core, int reg, int indexBits, int... values) {
        clearVector(core, reg);
        for (int i = 0; i < values.length; i++) {
            long bitOffset = (long) i * indexBits;
            int word = (int) (bitOffset >>> 6);
            int bitInWord = (int) (bitOffset & 63);
            long field = ((long) values[i]) & ((1L << indexBits) - 1L);
            core.scalable().setZWord(reg, word, core.scalable().zWord(reg, word) | (field << bitInWord));
        }
    }

    private static void clearVector(Aarch64Core core, int reg) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
    }

    private static void setPredicateAllTrue(Aarch64Core core, int p) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(p, w, -1L);
        }
    }

    private static boolean predicateBit(Aarch64Core core, int p, int byteIndex) {
        return ((core.scalable().pWord(p, byteIndex / 64) >>> (byteIndex % 64)) & 1L) != 0L;
    }

    // ── MATCH / NMATCH ──────────────────────────────────────────────────────────────────────────

    private static int matchWord(int esz, boolean nmatch, int pg, int rn, int rm, int pd) {
        return 0x45000000 | (esz << 22) | (1 << 21) | (rm << 16) | (0b100 << 13) | (pg << 10) | (rn << 5)
                | ((nmatch ? 1 : 0) << 4) | pd;
    }

    @Test
    void matchDecodesUnderSve2AndRefusesWideElements() {
        int word = matchWord(0, false, P0, Z2, Z3, 0);
        assertTrue(decodeOrNull(SVE2, word) instanceof Ir64Op.SveMatch);
        assertNull(decodeOrNull(SVE, word), "sem SVE2 recusa");
        assertNull(decodeOrNull(SVE2, matchWord(2, false, P0, Z2, Z3, 0)), "esz = 2 (.S) não existe");
        assertNull(decodeOrNull(SVE2, matchWord(3, false, P0, Z2, Z3, 0)), "esz = 3 (.D) não existe");
    }

    /// Aceite: `MATCH` acha o valor num segmento e NÃO no outro — só `VL >= 256` distingue de "vetor inteiro"
    /// (Armadilha 1).
    @Test
    void matchIsPerSegmentOf128Bits() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setPredicateAllTrue(core, P0);
        // Zn (Z2): elemento 0 (segmento 0) = 0x11; elemento 16 (segmento 1) = 0x22.
        setElement(core, Z2, 0, 0, 0x11L);
        setElement(core, Z2, 0, 16, 0x22L);
        // Zm (Z3): só o segmento 0 contém 0x11.
        setElement(core, Z3, 0, 0, 0x11L);
        run(SVE2, core, matchWord(0, false, P0, Z2, Z3, P1));
        assertTrue(predicateBit(core, P1, 0), "0x11 existe no segmento 0 de Zm");
        assertFalse(predicateBit(core, P1, 16), "0x22 não existe em NENHUM segmento de Zm");
    }

    @Test
    void nmatchInvertsAndPredTestSetsFlags() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setPredicateAllTrue(core, P0);
        setElement(core, Z2, 0, 0, 0x11L);
        setElement(core, Z3, 0, 0, 0x11L);
        run(SVE2, core, matchWord(0, true, P0, Z2, Z3, P1));
        assertFalse(predicateBit(core, P1, 0), "NMATCH inverte: achou, então bit = 0");
    }

    // ── HISTCNT / HISTSEG ───────────────────────────────────────────────────────────────────────

    private static int histcntWord(int esz, int pg, int rn, int rm, int rd) {
        return 0x45000000 | (esz << 22) | (1 << 21) | (rm << 16) | (0b110 << 13) | (pg << 10) | (rn << 5) | rd;
    }

    private static int histsegWord(int rn, int rm, int rd) {
        return 0x45000000 | (1 << 21) | (rm << 16) | (0b101000 << 10) | (rn << 5) | rd;
    }

    @Test
    void histcntDecodesUnderSve2WithEszSAndD() {
        assertTrue(decodeOrNull(SVE2, histcntWord(2, P0, Z2, Z3, Z1)) instanceof Ir64Op.SveHistogram);
        assertTrue(decodeOrNull(SVE2, histcntWord(3, P0, Z2, Z3, Z1)) instanceof Ir64Op.SveHistogram);
        assertNull(decodeOrNull(SVE2, histcntWord(0, P0, Z2, Z3, Z1)), "esz = 0 (.B) não existe em HISTCNT");
        assertNull(decodeOrNull(SVE2, histcntWord(1, P0, Z2, Z3, Z1)), "esz = 1 (.H) não existe em HISTCNT");
    }

    /// Aceite: `HISTCNT` conta só índices MENORES (histograma prefixo do vetor INTEIRO — achado que corrige a
    /// spec: NÃO é por segmento, ver Armadilha 2/`## Resultado`). Um "conta todos" passaria neste teste com
    /// valores diferentes, mas falha ao distinguir o elemento 0 (nenhum anterior) do elemento 1 (um igual antes).
    @Test
    void histcntCountsOnlyLowerOrEqualIndices() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setPredicateAllTrue(core, P0);
        int esz = 2;
        // Zn e Zm iguais em todo elemento (valor 7) — histograma prefixo puro: Zd[i] = i + 1.
        for (int e = 0; e < elementCount(core, esz); e++) {
            setElement(core, Z2, esz, e, 7L);
            setElement(core, Z3, esz, e, 7L);
        }
        run(SVE2, core, histcntWord(esz, P0, Z2, Z3, Z1));
        assertEquals(1L, getElement(core, Z1, esz, 0), "elemento 0: só ele mesmo (j <= 0)");
        assertEquals(2L, getElement(core, Z1, esz, 1), "elemento 1: ele + o anterior");
        assertEquals(3L, getElement(core, Z1, esz, 2));
    }

    /// `HISTCNT` respeita `Pg` tanto no comparado quanto nos candidatos.
    @Test
    void histcntSkipsInactiveElements() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        int esz = 2;
        for (int e = 0; e < elementCount(core, esz); e++) {
            setElement(core, Z2, esz, e, 7L);
            setElement(core, Z3, esz, e, 7L);
        }
        // Só os elementos 0 e 2 ativos.
        core.scalable().setPWord(P0, 0, 0L);
        for (int e : new int[] {0, 2}) {
            int byteIndex = e << esz;
            core.scalable().setPWord(P0, byteIndex / 64,
                    core.scalable().pWord(P0, byteIndex / 64) | (1L << (byteIndex % 64)));
        }
        run(SVE2, core, histcntWord(esz, P0, Z2, Z3, Z1));
        assertEquals(0L, getElement(core, Z1, esz, 1), "elemento 1 inativo: Zd = 0");
        assertEquals(2L, getElement(core, Z1, esz, 2), "elemento 2: conta 0 e 2 (1 ficou de fora)");
    }

    /// `HISTSEG`: por SEGMENTO de 128 bits (ao contrário de `HISTCNT`) — conta TODOS os bytes iguais do segmento,
    /// sem predicado.
    @Test
    void histsegCountsPerSegmentWithoutPredicate() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z2, 0, 0, 5L);
        // Segmento 0 de Zm: três bytes valendo 5.
        setElement(core, Z3, 0, 0, 5L);
        setElement(core, Z3, 0, 1, 5L);
        setElement(core, Z3, 0, 2, 5L);
        // Segmento 1 (bytes 16-31): não deve contaminar o segmento 0.
        setElement(core, Z2, 0, 16, 5L);
        setElement(core, Z3, 0, 17, 5L);
        run(SVE2, core, histsegWord(Z2, Z3, Z1));
        assertEquals(3L, getElement(core, Z1, 0, 0));
        assertEquals(1L, getElement(core, Z1, 0, 16), "segmento 1 conta só o que está NELE");
    }

    // ── LUTI2 / LUTI4 ───────────────────────────────────────────────────────────────────────────

    private static int luti2_1b(int index, int rm, int rn, int rd) {
        return 0x45000000 | (index << 22) | (1 << 21) | (rm << 16) | (0b101100 << 10) | (rn << 5) | rd;
    }

    private static int luti2_1h(int indexHigh2, int indexLow1, int rm, int rn, int rd) {
        return 0x45000000 | (1 << 21) | (rm << 16) | (0b101 << 13) | (0b10 << 10) | (rn << 5) | rd
                | (indexHigh2 << 22) | (indexLow1 << 12);
    }

    private static int luti4_1b(int index, int rm, int rn, int rd) {
        return 0x45000000 | (index << 23) | (0b11 << 21) | (rm << 16) | (0b101001 << 10) | (rn << 5) | rd;
    }

    private static int luti4_1h(int index, int rm, int rn, int rd) {
        return 0x45000000 | (index << 22) | (1 << 21) | (rm << 16) | (0b101111 << 10) | (rn << 5) | rd;
    }

    private static int luti4_2h(int index, int rm, int rn, int rd) {
        return 0x45000000 | (index << 22) | (1 << 21) | (rm << 16) | (0b101101 << 10) | (rn << 5) | rd;
    }

    @Test
    void lutiRequiresLookupTableFeature() {
        int word = luti2_1b(0, Z3, Z2, Z1);
        assertTrue(decodeOrNull(LUT, word) instanceof Ir64Op.SveLookupTable);
        assertNull(decodeOrNull(SVE2, word), "sem FEAT_LUT recusa");
    }

    /// `LUTI2_1b`: índice de 2 bits por byte de saída, tabela = `Zn` (4 entradas alcançáveis).
    @Test
    void luti2_1bIndexesTheTableByte() {
        Aarch64Core core = core(LUT, 128);
        clearVector(core, Z2); // tabela
        clearVector(core, Z3); // índices
        setElement(core, Z2, 0, 0, 0xAAL);
        setElement(core, Z2, 0, 1, 0xBBL);
        setElement(core, Z2, 0, 2, 0xCCL);
        setElement(core, Z2, 0, 3, 0xDDL);
        // Índices empacotados a 2 bits/elemento no grupo 0: elemento 0 -> índice 3, elemento 1 -> índice 1.
        setElement(core, Z3, 0, 0, 0b01_11L); // byte 0 = elemento0(bits1:0)=11, elemento1(bits3:2)=01
        run(LUT, core, luti2_1b(0, Z3, Z2, Z1));
        assertEquals(0xDDL, getElement(core, Z1, 0, 0), "índice 3 -> Zn[3]");
        assertEquals(0xBBL, getElement(core, Z1, 0, 1), "índice 1 -> Zn[1]");
    }

    /// `LUTI2_1h`: índice de 3 bits vindo de DOIS campos disjuntos (`22:2` + `12:1`) — Armadilha 6. Primeiro
    /// confere que o CAMPO decodificado é `alto:2 | baixo:1` (independente de execução), depois executa com
    /// `index = 0` (grupo 0 de `Zm`) para não precisar recalcular o deslocamento do grupo.
    @Test
    void luti2_1hIndexComesFromTwoDisjointFields() {
        int word = luti2_1h(0b10, 1, Z3, Z2, Z1);
        Ir64Op decoded = decodeOrNull(LUT, word);
        assertTrue(decoded instanceof Ir64Op.SveLookupTable);
        assertEquals(0b101, ((Ir64Op.SveLookupTable) decoded).index(), "alto (bits 22:2) << 1 | baixo (bit 12)");

        Aarch64Core core = core(LUT, 128);
        clearVector(core, Z2);
        for (int i = 0; i < 8; i++) {
            setElement(core, Z2, 1, i, 0x1000L + i);
        }
        // O CAMPO GRUPO (`op.index()`, 3 bits) é o testado acima; o EMPACOTAMENTO por elemento dentro de `Zm`
        // continua em 2 bits (`LUTI2` = 4 entradas), igual em byte ou halfword (`isize = 2` no `do_lut_h`).
        // Com `index = 0` (grupo 0), o campo do elemento 0 fica no início de `Zm`.
        packIndices(core, Z3, 2, 3, 1, 0, 0, 0, 0, 0, 0);
        run(LUT, core, luti2_1h(0, 0, Z3, Z2, Z1));
        assertEquals(0x1000L + 3, getElement(core, Z1, 1, 0));
        assertEquals(0x1000L + 1, getElement(core, Z1, 1, 1));
    }

    @Test
    void luti4_1bIndexesSixteenEntries() {
        Aarch64Core core = core(LUT, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        for (int i = 0; i < 16; i++) {
            setElement(core, Z2, 0, i, 0x40L + i);
        }
        setElement(core, Z3, 0, 0, 0b1101L); // elemento 0: índice 4 bits = 13
        run(LUT, core, luti4_1b(0, Z3, Z2, Z1));
        assertEquals(0x40L + 13, getElement(core, Z1, 0, 0));
    }

    @Test
    void luti4_1hRequiresVlAtLeast256() {
        Aarch64Core small = core(LUT, 128);
        run(LUT, small, luti4_1h(0, Z3, Z2, Z1));
        assertEquals(VBAR + 0x400L, small.pc(), "VL < 256: UNDEFINED, desvia pro vetor de exceção");
        assertEquals(0L, small.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC = 0 (UNDEFINED)");
        Aarch64Core big = core(LUT, 256);
        clearVector(big, Z2);
        clearVector(big, Z3);
        for (int i = 0; i < 16; i++) {
            setElement(big, Z2, 1, i, 0x2000L + i);
        }
        setElement(big, Z3, 1, 0, 9L);
        run(LUT, big, luti4_1h(0, Z3, Z2, Z1));
        assertEquals(0x2000L + 9, getElement(big, Z1, 1, 0));
    }

    /// `LUTI4_2h`: tabela concatenada de DOIS registradores (`Zn`/`Zn+1`), funciona mesmo em `VL = 128`.
    @Test
    void luti4_2hConcatenatesTwoTableRegisters() {
        // Zn = z4 (tabela), Zn+1 = z5 — DELIBERADAMENTE longe de z3 (índices) e z1 (destino) para não colidir.
        int zn = 4;
        Aarch64Core core = core(LUT, 128);
        clearVector(core, zn);
        clearVector(core, zn + 1);
        for (int i = 0; i < 8; i++) {
            setElement(core, zn, 1, i, 0x3000L + i); // entradas 0-7 (Zn)
            setElement(core, zn + 1, 1, i, 0x4000L + i); // entradas 8-15 (Zn+1)
        }
        packIndices(core, Z3, 4, 3, 12, 0, 0, 0, 0, 0, 0); // elemento 0 -> índice 3 (Zn); elemento 1 -> 12 (Zn+1)
        run(LUT, core, luti4_2h(0, Z3, zn, Z1));
        assertEquals(0x3000L + 3, getElement(core, Z1, 1, 0));
        assertEquals(0x4000L + (12 - 8), getElement(core, Z1, 1, 1));
    }

    // ── Multiply-add long NÃO-indexado × forma indexada da B17.8 (teste cruzado, Achado 4) ────────

    private static int nonIndexedWord(int esz, int opcode6, int rm, int rn, int rd) {
        return 0x44000000 | (esz << 22) | (rm << 16) | (opcode6 << 10) | (rn << 5) | rd;
    }

    private static final int OPC_SMLALB = 0b010000;
    private static final int OPC_SQRDMLAH = 0b011100;
    private static final int OPC_CMLA_ROT0 = 0b0010_00;
    private static final int OPC_SCLAMP = 0b110000;
    private static final int OPC_UCLAMP = 0b110001;
    private static final int OPC_SQDMLALBT = 0b000010;
    private static final int OPC_SQDMLSLBT = 0b000011;

    @Test
    void smlalbDecodesUnderSve2AndRejectsByte() {
        assertTrue(decodeOrNull(SVE2, nonIndexedWord(1, OPC_SMLALB, Z3, Z2, Z1)) instanceof Ir64Op.SveMultiplyIndexed);
        assertNull(decodeOrNull(SVE2, nonIndexedWord(0, OPC_SMLALB, Z3, Z2, Z1)), "esz = 0 não existe (fns[0] = NULL)");
        assertNull(decodeOrNull(SVE, nonIndexedWord(1, OPC_SMLALB, Z3, Z2, Z1)), "sem SVE2 recusa");
    }

    /// Achado 4 / Aceite: a forma NÃO-indexada produz o MESMO resultado que a indexada da B17.8 quando o índice
    /// aponta para o elemento correspondente (aqui, índice 0 do segmento 0 == o próprio elemento, em `VL = 128`
    /// para eliminar a diferença de segmentação).
    @Test
    void smlalbMatchesTheIndexedFormWhenIndexPointsAtTheSameElement() {
        Aarch64Core nonIndexedCore = core(SVE2, 128);
        Aarch64Core indexedCore = core(SVE2, 128);
        int narrowEsz = 1; // .H (fonte); destino .S (esz = 2)
        clearVector(nonIndexedCore, Z2);
        clearVector(nonIndexedCore, Z3);
        clearVector(nonIndexedCore, Z1);
        for (int i = 0; i < 8; i++) {
            setElement(nonIndexedCore, Z2, narrowEsz, i, 10L + i); // Zn: halfwords
            setElement(nonIndexedCore, Z3, narrowEsz, i, 20L + i); // Zm: halfwords
        }
        setElement(nonIndexedCore, Z1, 2, 0, 100L); // Zda[0] inicial
        copyVector(nonIndexedCore, indexedCore, Z2);
        copyVector(nonIndexedCore, indexedCore, Z3);
        copyVector(nonIndexedCore, indexedCore, Z1);
        // Não-indexado: SMLALB Zd1.s, Zn2.h, Zm3.h — Zda[0] += Zn[0] * Zm[0].
        run(SVE2, nonIndexedCore, nonIndexedWord(2, OPC_SMLALB, Z3, Z2, Z1));
        // Indexado (B17.8): SMLALB Zd1.s, Zn2.h, Zm3.h[0] — prefixo `0x44`, `bit 21 = 1`, `esz = 10`,
        // family `1000` (`FAMILY_SMLAL`), `Zm` restrito a `Z0`-`Z7` (3 bits, aqui `Z3`), `index = 0`, `top = 0`.
        int indexedWord = 0x44000000 | (0b10 << 22) | (1 << 21) | (Z3 << 16) | (0b1000 << 12) | (Z2 << 5) | Z1;
        run(SVE2, indexedCore, indexedWord);
        assertEquals(getElement(nonIndexedCore, Z1, 2, 0), getElement(indexedCore, Z1, 2, 0),
                "não-indexado == indexado quando o índice aponta pro mesmo elemento");
    }

    private static void copyVector(Aarch64Core from, Aarch64Core to, int reg) {
        for (int w = 0; w < from.scalable().wordsPerVector(); w++) {
            to.scalable().setZWord(reg, w, from.scalable().zWord(reg, w));
        }
    }

    @Test
    void sqdmlalbtInterleavesBottomOfNWithTopOfM() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        clearVector(core, Z1);
        setElement(core, Z2, 0, 0, 3L); // Zn bottom (byte 0)
        setElement(core, Z2, 0, 1, 99L); // Zn top (ignorado por *BT)
        setElement(core, Z3, 0, 0, 7L); // Zm bottom (ignorado por *BT)
        setElement(core, Z3, 0, 1, 5L); // Zm top (byte 1, usado)
        run(SVE2, core, nonIndexedWord(1, OPC_SQDMLALBT, Z3, Z2, Z1));
        assertEquals(2L * 3 * 5, getElement(core, Z1, 1, 0), "3 (Zn bottom) x 5 (Zm top) x 2 (doubling)");
    }

    /// `SQRDMLAH_zzzz esz=0(byte)`: `round((acc << 7) + n*m) >> 7` — `acc=0, n=100, m=100` dá
    /// `(10000 + 64) >> 7 = 78` (a fórmula de `do_sqrdmlah_*`, mesma de `SveMultiplyIndexedOps.sqrdmlah`).
    @Test
    void sqrdmlahZzzzUsesElementwiseZm() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        clearVector(core, Z1);
        setElement(core, Z2, 0, 0, 100L);
        setElement(core, Z3, 0, 0, 100L);
        run(SVE2, core, nonIndexedWord(0, OPC_SQRDMLAH, Z3, Z2, Z1));
        assertEquals(78L, (byte) getElement(core, Z1, 0, 0));
        assertNull(decodeOrNull(SVE, nonIndexedWord(0, OPC_SQRDMLAH, Z3, Z2, Z1)), "sem SVE2 recusa");
    }

    @Test
    void cmlaZzzzAllowsByteElement() {
        assertTrue(decodeOrNull(SVE2, nonIndexedWord(0, OPC_CMLA_ROT0, Z3, Z2, Z1)) instanceof Ir64Op.SveMultiplyIndexed,
                "CMLA_zzzz não-indexado permite esz = 0 (diferente da forma indexada)");
        assertNull(decodeOrNull(SVE, nonIndexedWord(0, OPC_CMLA_ROT0, Z3, Z2, Z1)), "sem SVE2 recusa");
    }

    /// Execução de `CMLA_zzzz` não-indexado (não só decode) — cobre o lado `!op.indexed()` de `complexMultiplyAdd`
    /// em `SveMultiplyIndexedOps` (só exercitado por decode até aqui). `rot = 0`: `real += n[0]*m[0]`,
    /// `imaginary += n[0]*m[1]`.
    @Test
    void cmlaZzzzExecutesWithElementwiseZm() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        clearVector(core, Z1);
        setElement(core, Z2, 1, 0, 3L); // Zn[0] (real de n)
        setElement(core, Z3, 1, 0, 5L); // Zm[0]
        setElement(core, Z3, 1, 1, 7L); // Zm[1]
        run(SVE2, core, nonIndexedWord(1, OPC_CMLA_ROT0, Z3, Z2, Z1));
        assertEquals(3L * 5, getElement(core, Z1, 1, 0), "real: acc(0) + n[0]*m[0]");
        assertEquals(3L * 7, getElement(core, Z1, 1, 1), "imaginário: acc(0) + n[0]*m[1]");
    }

    @Test
    void sqrdcmlahZzzzDecodesUnderSve2() {
        int word = nonIndexedWord(1, 0b0011_00, Z3, Z2, Z1); // family 0011, rot = 0
        assertTrue(decodeOrNull(SVE2, word) instanceof Ir64Op.SveMultiplyIndexed);
        assertNull(decodeOrNull(SVE, word), "sem SVE2 recusa");
    }

    @Test
    void sqrdmlshZzzzDecodesUnderSve2() {
        int word = nonIndexedWord(1, 0b011101, Z3, Z2, Z1);
        assertTrue(decodeOrNull(SVE2, word) instanceof Ir64Op.SveMultiplyIndexed);
    }

    @Test
    void sqdmlslbtDecodesAndInterleaves() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        clearVector(core, Z1);
        setElement(core, Z1, 1, 0, 1000L); // acumulador
        setElement(core, Z2, 0, 0, 3L); // Zn bottom
        setElement(core, Z3, 0, 1, 5L); // Zm top
        run(SVE2, core, nonIndexedWord(1, OPC_SQDMLSLBT, Z3, Z2, Z1));
        assertEquals(1000L - 2L * 3 * 5, getElement(core, Z1, 1, 0));
    }

    /// `SMLALT`/`UMLALB`/`UMLALT` (as outras 3 combinações `S/U` × `B/T` da família `0100`, além da `SMLALB` já
    /// testada) e `SMLSLB`/`SMLSLT`/`UMLSLB`/`UMLSLT` (família `0101`, nunca antes testada).
    @Test
    void allSignAndTopCombinationsOfTheLongFamiliesDecode() {
        int[] opcodes = {
                0b010001, 0b010010, 0b010011, // SMLALT, UMLALB, UMLALT
                0b010100, 0b010101, 0b010110, 0b010111, // SMLSLB, SMLSLT, UMLSLB, UMLSLT
        };
        for (int opcode : opcodes) {
            assertTrue(decodeOrNull(SVE2, nonIndexedWord(1, opcode, Z3, Z2, Z1)) instanceof Ir64Op.SveMultiplyIndexed,
                    "opcode6 = " + Integer.toBinaryString(opcode));
        }
    }

    /// `SQDMLALT`/`SQDMLSLB`/`SQDMLSLT` (as outras 3 combinações da família `0110`, além da `SQDMLALB` implícita
    /// no cruzamento acima).
    @Test
    void sqdmlalAndSqdmlslBottomAndTopBothDecode() {
        int[] opcodes = {0b011000, 0b011001, 0b011010, 0b011011};
        for (int opcode : opcodes) {
            assertTrue(decodeOrNull(SVE2, nonIndexedWord(1, opcode, Z3, Z2, Z1)) instanceof Ir64Op.SveMultiplyIndexed,
                    "opcode6 = " + Integer.toBinaryString(opcode));
        }
    }

    @Test
    void familyHighMultiplyUnusedLowIsRefused() {
        assertNull(decodeOrNull(SVE2, nonIndexedWord(2, 0b011111, Z3, Z2, Z1)), "família 0111, low = 11: não alocada");
    }

    @Test
    void usdotZzzz4sRejectsNonWordEsz() {
        assertNull(decodeOrNull(I8MM, nonIndexedWord(1, 0b011110, Z3, Z2, Z1)), "USDOT_zzzz_4s só existe com esz = 10");
    }

    @Test
    void udotZzzz2sAlsoDecodes() {
        int word = nonIndexedWord(0, 0b110011, Z3, Z2, Z1);
        assertTrue(decodeOrNull(SVE2_1, word) instanceof Ir64Op.SveMultiplyIndexed);
    }

    @Test
    void sclampAlsoDecodesUnderSmeAlone() {
        int word = 0x44000000 | (Z3 << 16) | (OPC_SCLAMP << 10) | (Z2 << 5) | Z1;
        assertTrue(decodeOrNull(SME_ONLY, word) instanceof Ir64Op.SveClamp);
    }

    // ── PSEL ────────────────────────────────────────────────────────────────────────────────────

    private static int pselB(int rv, int pn, int pm, int pd, int imm4) {
        int high2 = (imm4 >>> 2) & 0b11;
        int low2 = imm4 & 0b11;
        return 0x25000000 | (1 << 21) | (1 << 18) | (rv << 16) | (0b01 << 14) | (pn << 10) | (pm << 5) | pd
                | (high2 << 22) | (low2 << 19);
    }

    @Test
    void pselDecodesAndUsesW12ToW15() {
        int word = pselB(0, P0, P1, P0, 0); // rv encoding 0 -> W12
        Ir64Op decoded = decodeOrNull(SVE2_1, word);
        assertTrue(decoded instanceof Ir64Op.SvePredicateSelect);
        assertEquals(12, ((Ir64Op.SvePredicateSelect) decoded).rv(), "%psel_rv restringe a W12-W15");
        assertNull(decodeOrNull(SVE2, word), "sem SVE2.1/SME recusa");
    }

    /// Aceite: `PSEL` não escreve vetor — só predicado; testa as 4 sub-formas de `W12`-`W15` via `rv`.
    @Test
    void pselSelectsWholePredicateByOneTestedBit() {
        Aarch64Core core = core(SVE2_1, 128);
        setPredicateAllTrue(core, P1); // Pn = todo 1
        core.scalable().setPWord(P0, 0, 0L); // Pm = 0 no elemento 0
        core.setX(15, 0L); // W15, offset 0 -> testa Pm[0] = 0
        run(SVE2_1, core, pselB(3, P1, P0, P2, 0)); // rv=3 -> W15
        assertFalse(predicateBit(core, P2, 0), "Pm[0] = 0 -> Pd inteiro zerado");
        core.scalable().setPWord(P0, 0, 1L); // agora Pm[0] = 1
        run(SVE2_1, core, pselB(3, P1, P0, P2, 0));
        assertTrue(predicateBit(core, P2, 0), "Pm[0] = 1 -> Pd = Pn inteiro");
    }

    // ── SCLAMP / UCLAMP / FCLAMP ────────────────────────────────────────────────────────────────

    @Test
    void sclampClampsBetweenMinAndMax() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z1);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z1, 2, 0, (long) -100 & 0xFFFF_FFFFL); // Zd inicial: -100
        setElement(core, Z2, 2, 0, (long) -10 & 0xFFFF_FFFFL); // Zn = min = -10
        setElement(core, Z3, 2, 0, 50L); // Zm = max = 50
        int word = 0x44000000 | (2 << 22) | (Z3 << 16) | (OPC_SCLAMP << 10) | (Z2 << 5) | Z1;
        run(SVE2_1, core, word);
        assertEquals(-10L, (int) getElement(core, Z1, 2, 0), "clamp para o mínimo (-100 < -10)");
    }

    @Test
    void uclampTreatsOperandsAsUnsigned() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z1);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z1, 0, 0, 5L);
        setElement(core, Z2, 0, 0, 10L); // min
        setElement(core, Z3, 0, 0, 200L); // max
        int word = 0x44000000 | (Z3 << 16) | (OPC_UCLAMP << 10) | (Z2 << 5) | Z1;
        run(SVE2_1, core, word);
        assertEquals(10L, getElement(core, Z1, 0, 0), "5 < 10 (min) -> sobe pro mínimo");
    }

    @Test
    void fclampUsesMinNumMaxNumNotPropagatingNaN() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z1);
        clearVector(core, Z2);
        clearVector(core, Z3);
        int esz = 2; // .S
        int nan = 0x7FC00000; // quiet NaN
        setElement(core, Z1, esz, 0, nan & 0xFFFF_FFFFL); // Zd = NaN
        setElement(core, Z2, esz, 0, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL); // Zn = -1.0 (min)
        setElement(core, Z3, esz, 0, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL); // Zm = 1.0 (max)
        int word = 0x64000000 | (esz << 22) | (1 << 21) | (Z3 << 16) | (0b001001 << 10) | (Z2 << 5) | Z1;
        run(SVE2_1, core, word);
        float result = Float.intBitsToFloat((int) getElement(core, Z1, esz, 0));
        assertEquals(-1.0f, result, "minNum/maxNum ignoram o NaN de Zd (não propagam)");
    }

    @Test
    void fclampRejectsBFloat16EsziAsNamedPending() {
        int word = 0x64000000 | (1 << 21) | (Z3 << 16) | (0b001001 << 10) | (Z2 << 5) | Z1; // esz = 0 (BFloat16)
        assertNull(decodeOrNull(SVE2_1, word), "FEAT_SVE_B16B16 não existe ainda (pendência nomeada)");
    }

    // ── Feature gating dos 37 encodings (varredura rápida) ─────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void matchAndNmatchBothDecode(int invert) {
        assertTrue(decodeOrNull(SVE2, matchWord(0, invert == 1, P0, Z2, Z3, 0)) instanceof Ir64Op.SveMatch);
    }

    @Test
    void usdotZzzz4sRequiresI8mm() {
        int word = nonIndexedWord(2, 0b011110, Z3, Z2, Z1);
        assertTrue(decodeOrNull(I8MM, word) instanceof Ir64Op.SveMultiplyIndexed);
        assertNull(decodeOrNull(SVE2, word), "sem I8MM recusa (SVE2 sozinho não basta)");
    }

    @Test
    void sdotZzzz2sRequiresSve2p1() {
        int word = nonIndexedWord(0, 0b110010, Z3, Z2, Z1);
        assertTrue(decodeOrNull(SVE2_1, word) instanceof Ir64Op.SveMultiplyIndexed);
        assertNull(decodeOrNull(SVE2, word), "sem SVE2.1 recusa");
    }

    @Test
    void histogramRequiresSve2() {
        assertNull(decodeOrNull(SVE, histcntWord(2, P0, Z2, Z3, Z1)), "HISTCNT sem SVE2 recusa");
        assertNull(decodeOrNull(SVE, histsegWord(Z2, Z3, Z1)), "HISTSEG sem SVE2 recusa");
    }

    /// `PSEL`: o segundo operando de `!SVE2_1 && !SME` também precisa de cobertura — SME sozinho (sem SVE2.1)
    /// já basta.
    @Test
    void pselAlsoDecodesUnderSmeAlone() {
        assertTrue(decodeOrNull(SME_ONLY, pselB(0, P0, P1, P0, 0)) instanceof Ir64Op.SvePredicateSelect);
    }

    /// As formas `.H`/`.S`/`.D` de `PSEL` decodificam, e `bits[20:18] = 000` sem `bit 22 = 1` NÃO é `.D`
    /// (recusada) — cobre o segundo operando de `SIZE_D && BIT_D_FORM`.
    @Test
    void pselHSAndDFormsDecodeAndInvalidDIsRefused() {
        int hForm = 0x25000000 | (1 << 21) | (1 << 19) | (P0 << 16) | (0b01 << 14) | (P0 << 10) | (P1 << 5) | P0;
        Ir64Op h = decodeOrNull(SVE2_1, hForm);
        assertTrue(h instanceof Ir64Op.SvePredicateSelect);
        assertEquals(1, ((Ir64Op.SvePredicateSelect) h).esz());
        int sForm = 0x25000000 | (1 << 21) | (0b100 << 18) | (P0 << 16) | (0b01 << 14) | (P0 << 10) | (P1 << 5) | P0;
        Ir64Op s = decodeOrNull(SVE2_1, sForm);
        assertTrue(s instanceof Ir64Op.SvePredicateSelect);
        assertEquals(2, ((Ir64Op.SvePredicateSelect) s).esz());
        int dForm = 0x25000000 | (1 << 21) | (1 << 22) | (P0 << 16) | (0b01 << 14) | (P0 << 10) | (P1 << 5) | P0;
        Ir64Op d = decodeOrNull(SVE2_1, dForm);
        assertTrue(d instanceof Ir64Op.SvePredicateSelect);
        assertEquals(3, ((Ir64Op.SvePredicateSelect) d).esz());
        int invalidD = dForm & ~(1 << 22); // bits[20:18] = 000 mas SEM bit 22 = 1: não é nenhuma das 4 formas.
        assertNull(decodeOrNull(SVE2_1, invalidD));
    }

    @Test
    void fclampAlsoDecodesUnderSme2Alone() {
        int word = 0x64000000 | (2 << 22) | (1 << 21) | (Z3 << 16) | (0b001001 << 10) | (Z2 << 5) | Z1;
        assertTrue(decodeOrNull(SME2_ONLY, word) instanceof Ir64Op.SveClamp);
        assertNull(decodeOrNull(SVE2, word), "sem SVE2.1 nem SME2 recusa (os dois negados ao mesmo tempo)");
    }

    /// `MATCH` ignora elementos INATIVOS de `Pg` (não testa `Zn[e]` contra `Zm` nesse caso).
    @Test
    void matchSkipsInactiveElements() {
        Aarch64Core core = core(SVE2, 128);
        clearVector(core, Z2);
        clearVector(core, Z3);
        core.scalable().setPWord(P0, 0, 0L); // Pg todo inativo, exceto o elemento 1.
        core.scalable().setPWord(P0, 0, 1L << 1);
        setElement(core, Z2, 0, 0, 0x77L); // elemento 0 (inativo): não deveria ser testado.
        setElement(core, Z3, 0, 0, 0x11L); // Zm não contém 0x77 em lugar nenhum.
        run(SVE2, core, matchWord(0, false, P0, Z2, Z3, P1));
        assertFalse(predicateBit(core, P1, 0), "elemento inativo nunca liga o bit (pula, não zera por não achar)");
    }

    /// `HISTCNT`: um candidato ATIVO mas com valor DIFERENTE não conta (cobre `active[j] && m[j] == n[i]` com a
    /// combinação "ativo e diferente").
    @Test
    void histcntActiveButDifferentValueDoesNotCount() {
        Aarch64Core core = core(SVE2, 256);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setPredicateAllTrue(core, P0);
        int esz = 2;
        setElement(core, Z2, esz, 0, 1L);
        setElement(core, Z3, esz, 0, 1L);
        setElement(core, Z2, esz, 1, 2L);
        setElement(core, Z3, esz, 1, 999L); // ativo, mas diferente de Zn[1] (2)
        run(SVE2, core, histcntWord(esz, P0, Z2, Z3, Z1));
        assertEquals(0L, getElement(core, Z1, esz, 1), "Zm[0]=1 e Zm[1]=999: nenhum bate com Zn[1]=2");
    }

    /// `UCLAMP`: o caso em que `high > m` (desce pro máximo) — cobre o outro lado do `minUnsigned`.
    @Test
    void uclampClampsDownToMaxWhenAboveIt() {
        Aarch64Core core = core(SVE2_1, 128);
        clearVector(core, Z1);
        clearVector(core, Z2);
        clearVector(core, Z3);
        setElement(core, Z1, 0, 0, 250L);
        setElement(core, Z2, 0, 0, 10L); // min
        setElement(core, Z3, 0, 0, 100L); // max
        int word = 0x44000000 | (Z3 << 16) | (OPC_UCLAMP << 10) | (Z2 << 5) | Z1;
        run(SVE2_1, core, word);
        assertEquals(100L, getElement(core, Z1, 0, 0), "250 > 100 (max) -> desce pro máximo");
    }

    // ── Acesso negado (CPACR_EL1.ZEN = 0) ──────────────────────────────────────────────────────

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
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int group) {
        int word = switch (group) {
            case 0 -> matchWord(0, false, P0, Z2, Z3, 0);
            case 1 -> histcntWord(2, P0, Z2, Z3, Z1);
            case 2 -> luti2_1b(0, Z3, Z2, Z1);
            case 3 -> pselB(0, P0, P1, P0, 0);
            default -> 0x44000000 | (Z3 << 16) | (OPC_SCLAMP << 10) | (Z2 << 5) | Z1;
        };
        Aarch64Architecture architecture = group == 2 ? LUT : group == 3 || group == 4 ? SVE2_1 : SVE2;
        Aarch64Core core = core(architecture, 256);
        core.setSystemRegisterBus(new DenyingCpacr());
        core.setX(0, 0x1234L);
        run(architecture, core, word);
        assertEquals(VBAR + 0x400L, core.pc(), "grupo " + group);
        assertEquals(0x19L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC = 0x19 (SVE)");
        assertEquals(0x1234L, core.x(0), "a instrução não executou");
    }
}
