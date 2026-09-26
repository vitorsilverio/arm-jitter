package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B17.4 — predicados SVE: lógica, misc (`PTEST`/`PTRUE`/`FFR`/`PFIRST`/`PNEXT`), partition break, contagem
/// por predicado e contagem de elementos. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2p1`). Todo teste de semântica roda em `VL = 256` **e** `VL = 512` — o que prova a
/// disciplina de comprimento de vetor (nada aqui assume `VL = 128`).
class Aarch64SvePredicateTest {
    // ── Palavras (registradores fixos: pd=p1, pg=p2, pn=p3, pm=p4) ─────────────────────────────
    private static final int AND = 0x25044861;
    private static final int ANDS = 0x25444861;
    private static final int BIC = 0x25044871;
    private static final int BICS = 0x25444871;
    private static final int EOR = 0x25044a61;
    private static final int EORS = 0x25444a61;
    private static final int SEL = 0x25044a71;
    private static final int ORR = 0x25844861;
    private static final int ORRS = 0x25c44861;
    private static final int ORN = 0x25844871;
    private static final int ORNS = 0x25c44871;
    private static final int NOR = 0x25844a61;
    private static final int NORS = 0x25c44a61;
    private static final int NAND = 0x25844a71;
    private static final int NANDS = 0x25c44a71;
    private static final int PTEST_P5_P6 = 0x2550d4c0;
    private static final int PTRUE_TEMPLATE = 0x2518e000; // ptrue p0.b, vl?  (pat/esz/rd/S por OR)
    private static final int PTRUE_B_ALL_P1 = 0x2518e3e1;
    private static final int PFALSE_P3 = 0x2518e403;
    private static final int SETFFR = 0x252c9000;
    private static final int RDFFR_P4 = 0x2519f004;
    private static final int RDFFR_P4_PG5 = 0x2518f0a4;
    private static final int RDFFRS_P4_PG5 = 0x2558f0a4;
    private static final int WRFFR_P6 = 0x252890c0;
    private static final int PFIRST_P1_PG2 = 0x2558c041;
    private static final int PNEXT_B = 0x2519c441;
    private static final int PNEXT_H = 0x2559c441;
    private static final int PNEXT_S = 0x2599c441;
    private static final int PNEXT_D = 0x25d9c441;
    private static final int BRKPA = 0x2504c861;
    private static final int BRKPAS = 0x2544c861;
    private static final int BRKPB = 0x2504c871;
    private static final int BRKPBS = 0x2544c871;
    private static final int BRKA_Z = 0x25104861;
    private static final int BRKAS_Z = 0x25504861;
    private static final int BRKA_M = 0x25104871;
    private static final int BRKB_Z = 0x25904861;
    private static final int BRKBS_Z = 0x25d04861;
    private static final int BRKB_M = 0x25904871;
    private static final int BRKN = 0x25184861;
    private static final int BRKNS = 0x25584861;
    private static final int CNTP_B = 0x25208861; // cntp x1, p2, p3.b
    private static final int CNTP_H = 0x25608861;
    private static final int CNTP_S = 0x25a08861;
    private static final int CNTP_D = 0x25e08861;
    private static final int FIRSTP_B = 0x25218861;
    private static final int LASTP_B = 0x25228861;
    private static final int INCP_X_B = 0x252c8841; // incp x1, p2.b
    private static final int DECP_X_H = 0x256d8841;
    private static final int INCP_Z_H = 0x256c8041; // incp z1.h, p2
    private static final int DECP_Z_D = 0x25ed8041;
    private static final int SQINCP_X_W_B = 0x25288841; // sqincp x1, p2.b, w1
    private static final int UQINCP_W_H = 0x25698841;
    private static final int SQDECP_X_W_S = 0x25aa8841;
    private static final int UQDECP_W_D = 0x25eb8841;
    private static final int SQINCP_X_B = 0x25288c41;
    private static final int UQDECP_X_D = 0x25eb8c41;
    private static final int UQINCP_X_B = 0x25298c41;
    private static final int SQDECP_X_B = 0x252a8c41;
    private static final int SQINCP_Z_S = 0x25a88041;
    private static final int UQDECP_Z_H = 0x256b8041;
    private static final int UQINCP_Z_D = 0x25e98041;
    private static final int SQDECP_Z_S = 0x25aa8041;
    private static final int PTRUE_CNT_PN8_B = 0x25207810;
    private static final int CNTP_C = 0x25208301;
    private static final int CNTB = 0x0420e3e1; // cntb x1
    private static final int CNTH_VL3_MUL4 = 0x0463e061;
    private static final int CNTW_POW2 = 0x04a0e001;
    private static final int CNTD_ALL_MUL16 = 0x04efe3e1;
    private static final int INCB = 0x0430e3e1;
    private static final int DECD_VL4_MUL3 = 0x04f2e481;
    private static final int INCH_Z = 0x0470c3e1;
    private static final int DECW_Z_MUL3_MUL2 = 0x04b1c7c1;
    private static final int INCD_Z_ALL_MUL16 = 0x04ffc3e1;
    private static final int SQINCB_X_W = 0x0420f3e1;
    private static final int UQINCB_W = 0x0420f7e1;
    private static final int SQDECW_X_W_VL8_MUL5 = 0x04a4f901;
    private static final int UQDECH_W_ALL_MUL7 = 0x0466ffe1;
    private static final int SQINCB_X = 0x0430f3e1;
    private static final int UQDECD_X_MUL4_MUL9 = 0x04f8ffa1;
    private static final int SQINCH_Z = 0x0460c3e1;
    private static final int UQDECW_Z_VL3_MUL2 = 0x04a1cc61;
    private static final int SQINCD_Z_ALL_MUL16 = 0x04efc3e1;
    private static final int UQINCB_X = 0x0430f7e1;
    private static final int SQDECD_X = 0x04f0fbe1;

    private static final int P1 = 1;
    private static final int P2 = 2;
    private static final int P3 = 3;
    private static final int P4 = 4;
    private static final int NEGATIVE = 0b1000;
    private static final int ZERO = 0b0100;
    private static final int CARRY = 0b0010;
    private static final int NONE_ACTIVE_FLAGS = ZERO | CARRY;
    private static final long ESR_EC_SVE = 0x19L;
    private static final long ESR_EC_UNKNOWN = 0x00L;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final int SMSTART_SM = 0xd503437f;

    private static Aarch64Core core(int vl) {
        return core(Aarch64Architecture.ARMV9_0_A, vl);
    }

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Core core, int... words) {
        run(Aarch64Architecture.ARMV9_0_A, core, words);
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

    /// Bits (um por byte do vetor) de `P<reg>`, no `VL` efetivo.
    private static boolean[] bits(Aarch64Core core, int reg) {
        boolean[] out = new boolean[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (core.scalable().pWord(reg, i / 64) >>> (i % 64) & 1L) != 0L;
        }
        return out;
    }

    private static void setBits(Aarch64Core core, int reg, boolean[] bits) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < regs.wordsPerPredicate(); w++) {
            regs.setPWord(reg, w, 0L);
        }
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                regs.setPWord(reg, i / 64, regs.pWord(reg, i / 64) | (1L << (i % 64)));
            }
        }
    }

    private static boolean[] only(Aarch64Core core, int... indexes) {
        boolean[] out = new boolean[core.vectorLengthBytes()];
        for (int index : indexes) {
            out[index] = true;
        }
        return out;
    }

    private static void setOnly(Aarch64Core core, int reg, int... indexes) {
        setBits(core, reg, only(core, indexes));
    }

    private static boolean[] random(Aarch64Core core, Random random) {
        boolean[] out = new boolean[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = random.nextBoolean();
        }
        return out;
    }

    private static int flags(Aarch64Core core) {
        return core.pstate().nzcv();
    }

    /// `PredTest` escrito de outro jeito (varredura de índices ativos), para não repetir a implementação.
    private static int expectedFlags(boolean[] governing, boolean[] result, int esz) {
        List<Integer> active = new ArrayList<>();
        for (int e = 0; e < governing.length >> esz; e++) {
            if (governing[e << esz]) {
                active.add(e << esz);
            }
        }
        if (active.isEmpty()) {
            return NONE_ACTIVE_FLAGS;
        }
        int nzcv = 0;
        if (result[active.get(0)]) {
            nzcv |= NEGATIVE;
        }
        if (active.stream().noneMatch(i -> result[i])) {
            nzcv |= ZERO;
        }
        if (!result[active.get(active.size() - 1)]) {
            nzcv |= CARRY;
        }
        return nzcv;
    }

    private static void assertBits(boolean[] expected, Aarch64Core core, int reg, String message) {
        assertArrayEquals(expected, bits(core, reg), message);
    }

    // ── Decoder ─────────────────────────────────────────────────────────────────────────────────

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    static Stream<Arguments> decodeCases() {
        return Stream.of(
                Arguments.of(AND, Ir64Op.SvePredicateLogical.class), Arguments.of(ANDS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(BIC, Ir64Op.SvePredicateLogical.class), Arguments.of(BICS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(EOR, Ir64Op.SvePredicateLogical.class), Arguments.of(EORS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(SEL, Ir64Op.SvePredicateLogical.class),
                Arguments.of(ORR, Ir64Op.SvePredicateLogical.class), Arguments.of(ORRS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(ORN, Ir64Op.SvePredicateLogical.class), Arguments.of(ORNS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(NOR, Ir64Op.SvePredicateLogical.class), Arguments.of(NORS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(NAND, Ir64Op.SvePredicateLogical.class), Arguments.of(NANDS, Ir64Op.SvePredicateLogical.class),
                Arguments.of(PTEST_P5_P6, Ir64Op.SvePredicateMisc.class),
                Arguments.of(PTRUE_B_ALL_P1, Ir64Op.SvePredicateMisc.class),
                Arguments.of(PFALSE_P3, Ir64Op.SvePredicateMisc.class),
                Arguments.of(SETFFR, Ir64Op.SvePredicateMisc.class),
                Arguments.of(RDFFR_P4, Ir64Op.SvePredicateMisc.class),
                Arguments.of(RDFFR_P4_PG5, Ir64Op.SvePredicateMisc.class),
                Arguments.of(RDFFRS_P4_PG5, Ir64Op.SvePredicateMisc.class),
                Arguments.of(WRFFR_P6, Ir64Op.SvePredicateMisc.class),
                Arguments.of(PFIRST_P1_PG2, Ir64Op.SvePredicateMisc.class),
                Arguments.of(PNEXT_B, Ir64Op.SvePredicateMisc.class), Arguments.of(PNEXT_D, Ir64Op.SvePredicateMisc.class),
                Arguments.of(BRKPA, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKPAS, Ir64Op.SvePartitionBreak.class),
                Arguments.of(BRKPB, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKPBS, Ir64Op.SvePartitionBreak.class),
                Arguments.of(BRKA_Z, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKAS_Z, Ir64Op.SvePartitionBreak.class),
                Arguments.of(BRKA_M, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKB_Z, Ir64Op.SvePartitionBreak.class),
                Arguments.of(BRKBS_Z, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKB_M, Ir64Op.SvePartitionBreak.class),
                Arguments.of(BRKN, Ir64Op.SvePartitionBreak.class), Arguments.of(BRKNS, Ir64Op.SvePartitionBreak.class),
                Arguments.of(CNTP_B, Ir64Op.SvePredicateCount.class), Arguments.of(CNTP_D, Ir64Op.SvePredicateCount.class),
                Arguments.of(INCP_X_B, Ir64Op.SvePredicateCount.class), Arguments.of(DECP_X_H, Ir64Op.SvePredicateCount.class),
                Arguments.of(INCP_Z_H, Ir64Op.SvePredicateCount.class), Arguments.of(DECP_Z_D, Ir64Op.SvePredicateCount.class),
                Arguments.of(SQINCP_X_W_B, Ir64Op.SvePredicateCount.class),
                Arguments.of(UQINCP_W_H, Ir64Op.SvePredicateCount.class),
                Arguments.of(SQINCP_X_B, Ir64Op.SvePredicateCount.class),
                Arguments.of(UQDECP_X_D, Ir64Op.SvePredicateCount.class),
                Arguments.of(SQINCP_Z_S, Ir64Op.SvePredicateCount.class),
                Arguments.of(UQDECP_Z_H, Ir64Op.SvePredicateCount.class),
                Arguments.of(CNTB, Ir64Op.SveElementCount.class), Arguments.of(CNTH_VL3_MUL4, Ir64Op.SveElementCount.class),
                Arguments.of(INCB, Ir64Op.SveElementCount.class), Arguments.of(DECD_VL4_MUL3, Ir64Op.SveElementCount.class),
                Arguments.of(INCH_Z, Ir64Op.SveElementCount.class),
                Arguments.of(DECW_Z_MUL3_MUL2, Ir64Op.SveElementCount.class),
                Arguments.of(SQINCB_X_W, Ir64Op.SveElementCount.class), Arguments.of(UQINCB_W, Ir64Op.SveElementCount.class),
                Arguments.of(SQINCB_X, Ir64Op.SveElementCount.class), Arguments.of(SQINCH_Z, Ir64Op.SveElementCount.class));
    }

    @ParameterizedTest
    @MethodSource("decodeCases")
    void sveWordsDecodeToTheirGroupUnderAnSvePreset(int word, Class<? extends Ir64Op> expected) {
        assertInstanceOf(expected, decode(Aarch64Architecture.ARMV9_0_A, word));
    }

    @ParameterizedTest
    @MethodSource("decodeCases")
    void sveWordsAreRefusedWithoutFeatSve(int word, Class<? extends Ir64Op> ignored) {
        for (Aarch64Architecture architecture : new Aarch64Architecture[] {
                Aarch64Architecture.ARMV8_0_A, Aarch64Architecture.ARMV8_5_A}) {
            assertThrows(UnsupportedOperationException.class, () -> decode(architecture, word));
        }
    }

    @Test
    void decodedFieldsMatchTheAssemblerOperands() {
        assertEquals(new Ir64Op.SvePredicateLogical(Ir64Op.SvePredicateLogical.Op.ORR, 1, 2, 3, 4, true, 0),
                decode(Aarch64Architecture.ARMV9_0_A, ORRS));
        assertEquals(new Ir64Op.SvePredicateMisc(Ir64Op.SvePredicateMisc.Op.PTRUE, 1, 1, 0, 0, false, 3, 0),
                decode(Aarch64Architecture.ARMV9_0_A, 0x2558e061)); // ptrue p1.h, vl3
        assertEquals(new Ir64Op.SveElementCount(Ir64Op.SveElementCount.Op.CNT, 1, 1, 3, 4, false, true, 0),
                decode(Aarch64Architecture.ARMV9_0_A, CNTH_VL3_MUL4));
    }

    /// Não alocados / pendências nomeadas: recusa (G8), nunca decodificar como a forma comum vizinha.
    @ParameterizedTest
    @ValueSource(ints = {
            0x25444a71, // SEL com S = 1 (não alocado)
            PTRUE_CNT_PN8_B, // predicado-como-contador (SVE2.1) — NÃO é PTRUE
            CNTP_C, // predicado-como-contador — NÃO é CNTP
            0x252c8041, // INCP vetorial com esz = 0 (não alocado)
            0x2518e3f1, // PTRUE com o bit 4 (fixo em 0) ligado
            0x25288041, // SQINCP vetorial com esz = 0
            0x0430c3e1, // INCH vetorial com esz = 0
            0x0420c3e1, // SQINCH vetorial com esz = 0
            0x2584c861, // BRKPA com o bit 23 ligado (não existe)
            0x25504871, // BRKA /M com S = 1 (as formas /M não têm S)
            0x05205800, // ZIP/UZP/TRN de PREDICADO com o opcode 110: não alocado (prefixo 0x05; ZIP1_p é a B17.11)
            0x04200800 // prefixo 0x04, opcode 000010 (buraco entre SUB e SQADD): não alocado
    })
    void unallocatedOrPendingEncodingsAreRefused(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV9_0_A, word));
    }

    @Test
    void firstpAndLastpNeedFeatSve2p2() {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV9_0_A, FIRSTP_B));
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV9_0_A, LASTP_B));
        Aarch64Architecture withSve22 = Aarch64Architecture.extending(
                Aarch64Architecture.ARMV9_0_A, "teste-SVE2p2", Aarch64Feature.SVE2_2);
        assertInstanceOf(Ir64Op.SvePredicateCount.class, decode(withSve22, FIRSTP_B));
        assertInstanceOf(Ir64Op.SvePredicateCount.class, decode(withSve22, LASTP_B));
    }

    // ── Lógica de predicado ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void logicalOperationsComputeBitwiseUnderTheGoverningPredicate(int vl) {
        Random random = new Random(0x5EED + vl);
        for (int round = 0; round < 8; round++) {
            for (boolean setFlags : new boolean[] {false, true}) {
                for (int[] op : new int[][] {
                        {0, AND, ANDS}, {1, BIC, BICS}, {2, EOR, EORS}, {3, ORR, ORRS},
                        {4, ORN, ORNS}, {5, NOR, NORS}, {6, NAND, NANDS}}) {
                    Aarch64Core core = core(vl);
                    boolean[] g = random(core, random);
                    boolean[] n = random(core, random);
                    boolean[] m = random(core, random);
                    setBits(core, P2, g);
                    setBits(core, P3, n);
                    setBits(core, P4, m);
                    run(core, setFlags ? op[2] : op[1]);
                    boolean[] expected = new boolean[g.length];
                    for (int i = 0; i < g.length; i++) {
                        boolean value = switch (op[0]) {
                            case 0 -> n[i] & m[i];
                            case 1 -> n[i] & !m[i];
                            case 2 -> n[i] ^ m[i];
                            case 3 -> n[i] | m[i];
                            case 4 -> n[i] | !m[i];
                            case 5 -> !(n[i] | m[i]);
                            default -> !(n[i] & m[i]);
                        };
                        expected[i] = value && g[i];
                    }
                    assertBits(expected, core, P1, "op " + op[0] + " S=" + setFlags);
                    if (setFlags) {
                        assertEquals(expectedFlags(g, expected, 0), flags(core), "flags op " + op[0]);
                    }
                    if (vl == 256) {
                        assertEquals(0L, core.scalable().pWord(P1, 0) >>> 32, "nenhum bit acima de PL");
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void selPicksPnWhereGoverningAndPmElsewhere(int vl) {
        Aarch64Core core = core(vl);
        Random random = new Random(vl);
        boolean[] g = random(core, random);
        boolean[] n = random(core, random);
        boolean[] m = random(core, random);
        setBits(core, P2, g);
        setBits(core, P3, n);
        setBits(core, P4, m);
        run(core, SEL);
        boolean[] expected = new boolean[g.length];
        for (int i = 0; i < g.length; i++) {
            expected[i] = g[i] ? n[i] : m[i];
        }
        assertBits(expected, core, P1, "SEL");
    }

    @Test
    void logicalOperationDoesNotSetTheFlagsWithoutTheSSuffix() {
        Aarch64Core core = core(256);
        core.pstate().setNzcv(true, true, true, true);
        setOnly(core, P2, 0);
        run(core, AND);
        assertEquals(0b1111, flags(core));
    }

    // ── PTEST / PredTest ─────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> predTestTable() {
        // {pg, pn, esperado} — mesmos padrões nos dois VL (índices são de byte, < 32)
        return Stream.of(
                Arguments.of(new int[] {}, new int[] {}, NONE_ACTIVE_FLAGS),
                Arguments.of(new int[] {3}, new int[] {}, NONE_ACTIVE_FLAGS),
                Arguments.of(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
                        23, 24, 25, 26, 27, 28, 29, 30, 31}, new int[] {0}, NEGATIVE | CARRY),
                Arguments.of(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
                        23, 24, 25, 26, 27, 28, 29, 30, 31}, new int[] {31}, 0),
                Arguments.of(new int[] {3, 5, 9}, new int[] {5}, CARRY),
                Arguments.of(new int[] {3, 5, 9}, new int[] {9}, 0),
                Arguments.of(new int[] {3, 5, 9}, new int[] {3}, NEGATIVE | CARRY),
                Arguments.of(new int[] {3, 5, 9}, new int[] {3, 5, 9}, NEGATIVE),
                Arguments.of(new int[] {3, 5, 9}, new int[] {0, 1, 2, 4, 6, 7, 8}, NONE_ACTIVE_FLAGS));
    }

    @ParameterizedTest
    @MethodSource("predTestTable")
    void ptestSetsNzcvAsFirstNoneAndNotLast(int[] pg, int[] pn, int expected) {
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(vl);
            setOnly(core, 5, pg);
            setOnly(core, 6, pn);
            run(core, PTEST_P5_P6);
            assertEquals(expected, flags(core), "VL=" + vl);
        }
    }

    // ── PTRUE / PFALSE ───────────────────────────────────────────────────────────────────────────

    private static int expectedPatternCount(int pattern, int elements) {
        int bound = switch (pattern) {
            case 1, 2, 3, 4, 5, 6, 7, 8 -> pattern;
            case 9 -> 16;
            case 10 -> 32;
            case 11 -> 64;
            case 12 -> 128;
            case 13 -> 256;
            default -> -1;
        };
        if (bound > 0) {
            return elements >= bound ? bound : 0;
        }
        return switch (pattern) {
            case 0 -> {
                int power = 1;
                while (power * 2 <= elements) {
                    power *= 2;
                }
                yield power;
            }
            case 29 -> elements / 4 * 4;
            case 30 -> elements / 3 * 3;
            case 31 -> elements;
            default -> 0;
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ptrueProducesTheRightPredicateForEveryPatternAndElementSize(int vl) {
        for (int esz = 0; esz < 4; esz++) {
            for (int pattern = 0; pattern < 32; pattern++) {
                for (boolean setFlags : new boolean[] {false, true}) {
                    Aarch64Core core = core(vl);
                    setBits(core, 3, allTrue(core));
                    int word = PTRUE_TEMPLATE | esz << 22 | pattern << 5 | 3 | (setFlags ? 1 << 16 : 0);
                    run(core, word);
                    int count = expectedPatternCount(pattern, vl / 8 >> esz);
                    boolean[] expected = new boolean[vl / 8];
                    for (int e = 0; e < count; e++) {
                        expected[e << esz] = true;
                    }
                    assertBits(expected, core, 3, "VL=" + vl + " esz=" + esz + " pat=" + pattern);
                    if (setFlags) {
                        assertEquals(count > 0 ? NEGATIVE : NONE_ACTIVE_FLAGS, flags(core),
                                "PTRUES VL=" + vl + " esz=" + esz + " pat=" + pattern);
                    }
                }
            }
        }
    }

    @Test
    void ptrueSpotChecksAgainstHandComputedCounts() {
        // VL=256: 32 bytes. VL=512: 64 bytes.
        assertEquals(32, ptrue(256, 0, 0), "POW2 .b VL256");
        assertEquals(16, ptrue(256, 0, 9), "VL16 .b");
        assertEquals(0, ptrue(256, 0, 11), "VL64 .b não cabe em VL256: predicado VAZIO, não exceção");
        assertEquals(64, ptrue(512, 0, 11), "VL64 .b cabe em VL512");
        assertEquals(30, ptrue(256, 0, 30), "MUL3 .b VL256");
        assertEquals(63, ptrue(512, 0, 30), "MUL3 .b VL512");
        assertEquals(4, ptrue(256, 3, 31), "ALL .d VL256");
        assertEquals(8, ptrue(512, 3, 31), "ALL .d VL512");
        assertEquals(0, ptrue(256, 3, 5), "VL5 .d: só 4 elementos");
        assertEquals(0, ptrue(256, 0, 20), "pat 20 não é padrão: predicado vazio");
    }

    private static int ptrue(int vl, int esz, int pattern) {
        Aarch64Core core = core(vl);
        run(core, PTRUE_TEMPLATE | esz << 22 | pattern << 5 | 1);
        int count = 0;
        for (boolean b : bits(core, 1)) {
            count += b ? 1 : 0;
        }
        return count;
    }

    @Test
    void pfalseClearsThePredicate() {
        Aarch64Core core = core(256);
        setBits(core, 3, allTrue(core));
        run(core, PFALSE_P3);
        assertBits(new boolean[32], core, 3, "PFALSE");
    }

    private static boolean[] allTrue(Aarch64Core core) {
        boolean[] out = new boolean[core.vectorLengthBytes()];
        java.util.Arrays.fill(out, true);
        return out;
    }

    // ── FFR ──────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void setffrRdffrAndWrffrMoveTheFfr(int vl) {
        Aarch64Core core = core(vl);
        run(core, SETFFR, RDFFR_P4);
        assertBits(allTrue(core), core, P4, "SETFFR liga todos os bits (PL) e RDFFR copia");
        assertEquals(vl == 256 ? 0xFFFF_FFFFL : -1L, core.scalable().ffrWord(0), "só PL bits ligados");

        Random random = new Random(vl);
        boolean[] value = random(core, random);
        setBits(core, 6, value);
        run(core, WRFFR_P6, RDFFR_P4);
        assertBits(value, core, P4, "WRFFR grava o FFR");
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void predicatedRdffrAndsWithTheGoverningPredicateAndRdffrsSetsFlags(int vl) {
        Aarch64Core core = core(vl);
        Random random = new Random(7 * vl);
        boolean[] ffr = random(core, random);
        boolean[] pg = random(core, random);
        setBits(core, 6, ffr);
        setBits(core, 5, pg);
        run(core, WRFFR_P6, RDFFR_P4_PG5);
        boolean[] expected = new boolean[ffr.length];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = ffr[i] && pg[i];
        }
        assertBits(expected, core, P4, "RDFFR p4.b, p5/z");
        core.pstate().setNzcv(false, false, false, false);
        run(core, RDFFRS_P4_PG5);
        assertEquals(expectedFlags(pg, expected, 0), flags(core));
    }

    // ── PFIRST / PNEXT ───────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void pfirstSetsTheFirstActiveElementAndKeepsTheRest(int vl) {
        Aarch64Core core = core(vl);
        setOnly(core, P2, 3, 5);
        setOnly(core, P1, 20);
        run(core, PFIRST_P1_PG2);
        assertBits(only(core, 3, 20), core, P1, "PFIRST liga o bit 3 e preserva o 20");
        assertEquals(expectedFlags(only(core, 3, 5), only(core, 3, 20), 0), flags(core));

        setOnly(core, P2);
        setOnly(core, P1, 7);
        run(core, PFIRST_P1_PG2);
        assertBits(only(core, 7), core, P1, "sem elemento ativo, Pdn não muda");
        assertEquals(NONE_ACTIVE_FLAGS, flags(core));
    }

    private static List<Integer> iteratePnext(Aarch64Core core, int esz, int word, boolean[] governing) {
        setBits(core, P2, governing);
        setBits(core, P1, new boolean[core.vectorLengthBytes()]);
        List<Integer> visited = new ArrayList<>();
        for (int step = 0; step < governing.length + 1; step++) {
            run(core, word);
            boolean[] current = bits(core, P1);
            int found = -1;
            int count = 0;
            for (int i = 0; i < current.length; i++) {
                if (current[i]) {
                    found = i;
                    count++;
                }
            }
            if (count == 0) {
                assertEquals(NONE_ACTIVE_FLAGS, flags(core), "esgotado: nada ativo => Z=1 C=1");
                return visited;
            }
            assertEquals(1, count, "PNEXT deixa exatamente um elemento");
            assertEquals(0, found & ((1 << esz) - 1), "bit no byte baixo do elemento");
            visited.add(found >> esz);
            assertEquals(expectedFlags(governing, current, esz), flags(core));
        }
        throw new AssertionError("PNEXT não terminou");
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void pnextWalksEveryActiveElementOnceForEachElementSize(int vl) {
        int[] words = {PNEXT_B, PNEXT_H, PNEXT_S, PNEXT_D};
        for (int esz = 0; esz < 4; esz++) {
            Aarch64Core core = core(vl);
            boolean[] governing = new boolean[vl / 8];
            List<Integer> expected = new ArrayList<>();
            Random random = new Random(esz + vl);
            for (int e = 0; e < governing.length >> esz; e++) {
                if (random.nextInt(3) != 0) {
                    governing[e << esz] = true;
                    expected.add(e);
                }
            }
            assertEquals(expected, iteratePnext(core, esz, words[esz], governing), "esz=" + esz);
        }
    }

    @Test
    void pnextStartsFromTheLastTrueElementOfPdnEvenIfPgDoesNotCoverIt() {
        Aarch64Core core = core(256);
        setOnly(core, P2, 2, 3, 30);
        setOnly(core, P1, 10); // último verdadeiro de Pdn = 10, fora de Pg
        run(core, PNEXT_B);
        assertBits(only(core, 30), core, P1, "primeiro ativo depois de 10");
    }

    // ── Partition break ─────────────────────────────────────────────────────────────────────────

    private static int[] range(int from, int toInclusive) {
        return java.util.stream.IntStream.rangeClosed(from, toInclusive).toArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void brkaAndBrkbBreakAfterAndBeforeTheFirstTrueActiveElement(int vl) {
        Aarch64Core core = core(vl);
        setOnly(core, P2, range(0, 7));
        setOnly(core, P3, 3, 5, 12);
        run(core, BRKA_Z);
        assertBits(only(core, range(0, 3)), core, P1, "BRKA: até e incluindo o primeiro");
        run(core, BRKB_Z);
        assertBits(only(core, range(0, 2)), core, P1, "BRKB: antes do primeiro");
        setOnly(core, P3);
        run(core, BRKA_Z);
        assertBits(only(core, range(0, 7)), core, P1, "sem quebra, todos os ativos");
        setOnly(core, P3, 40 % (vl / 8));
        run(core, BRKB_Z);
        assertBits(only(core, range(0, 7)), core, P1, "condição verdadeira só num elemento inativo");
    }

    @Test
    void mergingBreakPreservesTheInactiveElementsOfPd() {
        Aarch64Core core = core(256);
        setOnly(core, P2, range(0, 7));
        setOnly(core, P3, 3, 5);
        setOnly(core, P1, 6, 20, 31);
        run(core, BRKA_M);
        assertBits(only(core, 0, 1, 2, 3, 20, 31), core, P1, "ativos recalculados (6 some), inativos preservados");
        setOnly(core, P1, 6, 20, 31);
        run(core, BRKB_M);
        assertBits(only(core, 0, 1, 2, 20, 31), core, P1, "BRKB merging");
    }

    @Test
    void zeroingBreakZeroesInactiveElementsAndFlagFormsSetNzcv() {
        Aarch64Core core = core(256);
        setOnly(core, P2, range(0, 7));
        setOnly(core, P3, 3, 5);
        setOnly(core, P1, 20);
        run(core, BRKAS_Z);
        boolean[] expected = only(core, 0, 1, 2, 3);
        assertBits(expected, core, P1, "BRKAS");
        assertEquals(expectedFlags(only(core, range(0, 7)), expected, 0), flags(core));
        run(core, BRKBS_Z);
        assertEquals(expectedFlags(only(core, range(0, 7)), only(core, 0, 1, 2), 0), flags(core));
    }

    @Test
    void propagatingBreakDependsOnTheLastActiveElementOfPn() {
        Aarch64Core core = core(256);
        setOnly(core, P2, range(0, 7));
        setOnly(core, P4, 2, 4);
        setOnly(core, P3, 7); // último ativo de Pg está verdadeiro em Pn
        run(core, BRKPA);
        assertBits(only(core, 0, 1, 2), core, P1, "BRKPA: BreakAfter(Pm)");
        run(core, BRKPB);
        assertBits(only(core, 0, 1), core, P1, "BRKPB: BreakBefore(Pm)");
        setOnly(core, P3, 3); // último ativo (7) falso
        run(core, BRKPA);
        assertBits(new boolean[32], core, P1, "BRKPA: predicado zerado");
        run(core, BRKPAS);
        assertEquals(NONE_ACTIVE_FLAGS, flags(core), "BRKPAS com resultado vazio");
        setOnly(core, P3, 7);
        run(core, BRKPBS);
        assertEquals(expectedFlags(only(core, range(0, 7)), only(core, 0, 1), 0), flags(core));
        setOnly(core, P2);
        run(core, BRKPA);
        assertBits(new boolean[32], core, P1, "Pg sem elemento ativo: LastActive é falso, predicado zerado");
    }

    @Test
    void brknKeepsPdmOnlyWhenTheLastActiveElementOfPnIsTrue() {
        Aarch64Core core = core(256);
        setOnly(core, P2, range(0, 7));
        setOnly(core, P3, 7);
        setOnly(core, P1, 5);
        run(core, BRKN);
        assertBits(only(core, 5), core, P1, "mantido");
        run(core, BRKNS);
        // As if PredTest(Ones(PL), Pdm): N=0 (bit 0 falso), Z=0, C=1 (último bit falso)
        assertEquals(CARRY, flags(core), "BRKNS usa Ones como máscara, não Pg");
        setOnly(core, P3, 3);
        run(core, BRKNS);
        assertBits(new boolean[32], core, P1, "zerado");
        assertEquals(NONE_ACTIVE_FLAGS, flags(core));
    }

    // ── Contagem por predicado ──────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void cntpCountsOnlyTheLowestBytePerElementOfBothPredicates(int vl) {
        Aarch64Core core = core(vl);
        boolean[] g = allTrue(core);
        setBits(core, P2, g);
        // Pn: elementos 1 e 3 de 32 bits (bits 4 e 12) + lixo em bytes que não são o mais baixo
        setOnly(core, P3, 4, 5, 12, 15);
        core.setX(1, 0xDEAD);
        run(core, CNTP_S);
        assertEquals(2L, core.x(1), ".s: só os bits 4 e 12 contam");
        run(core, CNTP_B);
        assertEquals(4L, core.x(1), ".b: todos os 4 bits contam");
        run(core, CNTP_H);
        assertEquals(2L, core.x(1), ".h: bits pares (4 e 12)");
        run(core, CNTP_D);
        assertEquals(0L, core.x(1), ".d: bits múltiplos de 8 — nenhum");
        setOnly(core, P2, 4);
        run(core, CNTP_B);
        assertEquals(1L, core.x(1), "Pg restringe");
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void incpAndDecpAddTheCountOfActiveElementsOfTheSamePredicate(int vl) {
        Aarch64Core core = core(vl);
        setBits(core, P2, allTrue(core));
        core.setX(1, 100);
        run(core, INCP_X_B);
        assertEquals(100 + vl / 8, core.x(1));
        core.setX(1, 100);
        run(core, DECP_X_H);
        assertEquals(100 - vl / 16, core.x(1));
        // vetor: cada elemento .h ganha vl/16; .d perde vl/64
        for (int e = 0; e < vl / 16; e++) {
            PredicateAccess.setElement(core, 1, e, 1, 1000 + e);
        }
        run(core, INCP_Z_H);
        for (int e = 0; e < vl / 16; e++) {
            assertEquals(1000 + e + vl / 16, PredicateAccess.element(core, 1, e, 1), "elemento " + e);
        }
        for (int e = 0; e < vl / 64; e++) {
            PredicateAccess.setElement(core, 1, e, 3, 50 + e);
        }
        run(core, DECP_Z_D);
        for (int e = 0; e < vl / 64; e++) {
            assertEquals(50 + e - vl / 64, PredicateAccess.element(core, 1, e, 3));
        }
    }

    @Test
    void saturatingScalarPredicateCountsClampAtTheTypeLimits() {
        Aarch64Core core = core(256);
        setBits(core, P2, allTrue(core)); // .b: 32, .h: 16, .s: 8, .d: 4
        core.setX(1, 0xDEAD_0000_7FFF_FFF0L);
        run(core, SQINCP_X_W_B);
        assertEquals(0x7FFF_FFFFL, core.x(1), "sqincp Xd, Pm.B, Wd: satura em INT32_MAX (bits altos de X ignorados)");
        core.setX(1, 0xFFFF_FFF8L);
        run(core, UQINCP_W_H);
        assertEquals(0xFFFF_FFFFL, core.x(1), "uqincp Wd: satura em UINT32_MAX, zero-estendido");
        core.setX(1, 0x0000_0000_8000_0004L);
        run(core, SQDECP_X_W_S);
        assertEquals(0xFFFF_FFFF_8000_0000L, core.x(1), "sqdecp: satura em INT32_MIN, estendido com sinal");
        core.setX(1, 0xFFFF_FFFF_0000_0002L);
        run(core, UQDECP_W_D);
        assertEquals(0L, core.x(1), "uqdecp Wd: satura em 0");
        core.setX(1, Long.MAX_VALUE - 5);
        run(core, SQINCP_X_B);
        assertEquals(Long.MAX_VALUE, core.x(1), "sqincp Xd: satura em INT64_MAX");
        core.setX(1, Long.MIN_VALUE + 3);
        run(core, SQDECP_X_B);
        assertEquals(Long.MIN_VALUE, core.x(1), "sqdecp Xd: satura em INT64_MIN");
        core.setX(1, 3);
        run(core, UQDECP_X_D);
        assertEquals(0L, core.x(1), "uqdecp Xd: satura em 0");
        core.setX(1, -20L);
        run(core, UQINCP_X_B);
        assertEquals(-1L, core.x(1), "uqincp Xd: satura em UINT64_MAX");
        core.setX(1, 40);
        run(core, SQINCP_X_B);
        assertEquals(72L, core.x(1), "sem saturação, soma normal");
        core.setX(1, 100);
        run(core, SQDECP_X_B);
        assertEquals(68L, core.x(1), "sem saturação, subtração normal");
    }

    @Test
    void saturatingVectorPredicateCountsClampEachElement() {
        Aarch64Core core = core(256);
        setBits(core, P2, allTrue(core));
        PredicateAccess.setElement(core, 1, 0, 2, 0x7FFF_FFFCL);
        PredicateAccess.setElement(core, 1, 1, 2, 5);
        PredicateAccess.setElement(core, 1, 2, 2, 0x8000_0000L);
        run(core, SQINCP_Z_S);
        assertEquals(0x7FFF_FFFFL, PredicateAccess.element(core, 1, 0, 2), "signed .s: satura em INT32_MAX");
        assertEquals(13L, PredicateAccess.element(core, 1, 1, 2), "8 elementos ativos");
        assertEquals(0x8000_0008L, PredicateAccess.element(core, 1, 2, 2), "negativo cresce sem saturar");
        for (int e = 0; e < 16; e++) {
            PredicateAccess.setElement(core, 1, e, 1, e);
        }
        run(core, UQDECP_Z_H);
        for (int e = 0; e < 16; e++) {
            assertEquals(Math.max(0, e - 16), PredicateAccess.element(core, 1, e, 1), "uqdecp .h elemento " + e);
        }
        PredicateAccess.setElement(core, 1, 0, 3, -2L);
        PredicateAccess.setElement(core, 1, 1, 3, 1L);
        run(core, UQINCP_Z_D);
        assertEquals(-1L, PredicateAccess.element(core, 1, 0, 3), "unsigned .d: satura em UINT64_MAX");
        assertEquals(5L, PredicateAccess.element(core, 1, 1, 3));
        PredicateAccess.setElement(core, 1, 0, 2, 0x8000_0003L);
        run(core, SQDECP_Z_S);
        assertEquals(0x8000_0000L, PredicateAccess.element(core, 1, 0, 2), "signed .s: satura em INT32_MIN");
    }

    @Test
    void firstpAndLastpReturnTheElementIndexOrMinusOne() {
        Aarch64Architecture withSve22 = Aarch64Architecture.extending(
                Aarch64Architecture.ARMV9_0_A, "teste-SVE2p2", Aarch64Feature.SVE2_2);
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(withSve22, vl);
            setOnly(core, P2, 1, 4, 9, 20);
            setOnly(core, P3, 4, 9, 30);
            run(withSve22, core, FIRSTP_B);
            assertEquals(4L, core.x(1));
            run(withSve22, core, LASTP_B);
            assertEquals(9L, core.x(1));
            setOnly(core, P3, 30);
            run(withSve22, core, FIRSTP_B);
            assertEquals(-1L, core.x(1), "nenhum ativo: -1");
            run(withSve22, core, LASTP_B);
            assertEquals(-1L, core.x(1));
        }
    }

    // ── Contagem de elementos ───────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void cntFamilyReadsTheVectorLengthAndThePatternAndMultiplier(int vl) {
        Aarch64Core core = core(vl);
        run(core, CNTB);
        assertEquals(vl / 8, core.x(1), "cntb = VL/8");
        run(core, CNTH_VL3_MUL4);
        assertEquals(3 * 4, core.x(1), "cnth vl3 mul #4");
        run(core, CNTW_POW2);
        assertEquals(vl / 32, core.x(1), "cntw pow2: 8 ou 16 palavras");
        run(core, CNTD_ALL_MUL16);
        assertEquals(vl / 64 * 16, core.x(1), "cntd all mul #16 (imm4 = 15 multiplica por 16)");
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void incdecScalarAndVectorFormsAddOrSubtractTheCount(int vl) {
        Aarch64Core core = core(vl);
        core.setX(1, 1000);
        run(core, INCB);
        assertEquals(1000 + vl / 8, core.x(1));
        core.setX(1, 1000);
        run(core, DECD_VL4_MUL3);
        assertEquals(1000 - 12, core.x(1), "decd vl4 mul #3");
        for (int e = 0; e < vl / 16; e++) {
            PredicateAccess.setElement(core, 1, e, 1, 7);
        }
        run(core, INCH_Z);
        for (int e = 0; e < vl / 16; e++) {
            assertEquals(7 + vl / 16, PredicateAccess.element(core, 1, e, 1));
        }
        for (int e = 0; e < vl / 32; e++) {
            PredicateAccess.setElement(core, 1, e, 2, 100);
        }
        run(core, DECW_Z_MUL3_MUL2);
        int words = vl / 32;
        for (int e = 0; e < words; e++) {
            assertEquals(100 - (words - words % 3) * 2, PredicateAccess.element(core, 1, e, 2), "decw mul3 mul #2");
        }
        for (int e = 0; e < vl / 64; e++) {
            PredicateAccess.setElement(core, 1, e, 3, 5);
        }
        run(core, INCD_Z_ALL_MUL16);
        for (int e = 0; e < vl / 64; e++) {
            assertEquals(5 + vl / 64 * 16, PredicateAccess.element(core, 1, e, 3));
        }
    }

    @Test
    void saturatingElementCountsClampScalarAndVector() {
        Aarch64Core core = core(256);
        core.setX(1, 0xDEAD_0000_7FFF_FFF0L);
        run(core, SQINCB_X_W);
        assertEquals(0x7FFF_FFFFL, core.x(1), "sqincb Xd, Wd: 32 bytes => satura");
        core.setX(1, 0xFFFF_FFF0L);
        run(core, UQINCB_W);
        assertEquals(0xFFFF_FFFFL, core.x(1), "uqincb Wd");
        core.setX(1, 0xFFFF_FFFF_8000_000AL);
        run(core, SQDECW_X_W_VL8_MUL5);
        assertEquals(0xFFFF_FFFF_8000_0000L, core.x(1), "sqdecw: 8 palavras × 5 = 40, satura em INT32_MIN");
        core.setX(1, 100);
        run(core, UQDECH_W_ALL_MUL7);
        assertEquals(0L, core.x(1), "uqdech: 16 × 7 = 112 > 100");
        core.setX(1, Long.MAX_VALUE - 1);
        run(core, SQINCB_X);
        assertEquals(Long.MAX_VALUE, core.x(1));
        core.setX(1, 30);
        run(core, UQDECD_X_MUL4_MUL9);
        assertEquals(0L, core.x(1), "uqdecd mul4 mul #9: 4 × 9 = 36 > 30");
        core.setX(1, -10L);
        run(core, UQINCB_X);
        assertEquals(-1L, core.x(1), "uqincb Xd (64 bits): -10 sem sinal é enorme => satura em UINT64_MAX");
    }

    @Test
    void saturatingElementCountOnVectorsClampsEachElement() {
        Aarch64Core core = core(256);
        for (int e = 0; e < 16; e++) {
            PredicateAccess.setElement(core, 1, e, 1, e == 0 ? 0x7FF8 : e);
        }
        run(core, SQINCH_Z);
        assertEquals(0x7FFF, PredicateAccess.element(core, 1, 0, 1), "sqinch .h satura em INT16_MAX");
        assertEquals(1 + 16, PredicateAccess.element(core, 1, 1, 1));
        PredicateAccess.setElement(core, 1, 0, 2, 4);
        PredicateAccess.setElement(core, 1, 1, 2, 10);
        run(core, UQDECW_Z_VL3_MUL2);
        assertEquals(0L, PredicateAccess.element(core, 1, 0, 2), "uqdecw vl3 mul #2 = 6: 4 - 6 satura em 0");
        assertEquals(4L, PredicateAccess.element(core, 1, 1, 2));
        PredicateAccess.setElement(core, 1, 0, 3, Long.MAX_VALUE - 1);
        run(core, SQINCD_Z_ALL_MUL16);
        assertEquals(Long.MAX_VALUE, PredicateAccess.element(core, 1, 0, 3));
    }

    // ── Quantidade zero, VL efetivo menor que o implementado ─────────────────────────────────────

    @Test
    void zeroAmountCountsStillExtendScalarOperandsAndLeaveVectorsUntouched() {
        Aarch64Core core = core(256); // 32 bytes, 16 halfwords: vl32 em .h e vl64 em .b não cabem => 0
        for (int e = 0; e < 16; e++) {
            PredicateAccess.setElement(core, 1, e, 1, 0x100 + e);
        }
        run(core, 0x0470c141); // inch z1.h, vl32
        run(core, 0x0460c141); // sqinch z1.h, vl32
        for (int e = 0; e < 16; e++) {
            assertEquals(0x100 + e, PredicateAccess.element(core, 1, e, 1), "quantidade 0 não muda o vetor");
        }
        core.setX(1, 0xDEAD_0000_8000_0000L);
        run(core, 0x0420f161); // sqincb x1, w1, vl64
        assertEquals(0xFFFF_FFFF_8000_0000L, core.x(1), "quantidade 0 ainda estende o Wn com sinal");
        core.setX(1, 0xDEAD_0000_0000_0005L);
        run(core, 0x0420f561); // uqincb w1, vl64
        assertEquals(5L, core.x(1), "quantidade 0 ainda zero-estende");
        core.setX(1, 0xDEAD_0000_0000_0005L);
        run(core, 0x0430f161); // sqincb x1, vl64
        assertEquals(0xDEAD_0000_0000_0005L, core.x(1));
        run(core, 0x0430f561); // uqincb x1, vl64
        assertEquals(0xDEAD_0000_0000_0005L, core.x(1));
    }

    @Test
    void unsignedDecrementsThatDoNotUnderflowSubtractNormally() {
        Aarch64Core core = core(256);
        core.setX(1, 100);
        run(core, 0x0430ffe1); // uqdecb x1: 32 bytes
        assertEquals(68L, core.x(1));
        setBits(core, P2, allTrue(core));
        core.setX(1, 100);
        run(core, UQDECP_X_D);
        assertEquals(96L, core.x(1), "uqdecp x1, p2.d: 4 elementos");
    }

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 3); // LEN=3 => VL efetivo 512
        assertEquals(512, core.vectorLengthBits());
        // lixo acima de PL (64 bits) em Pg: `PL` é o efetivo, então o resultado não pode carregar esses bits
        core.scalable().setPWord(P2, 0, -1L);
        core.scalable().setPWord(P2, 1, -1L);
        run(core, ORN);
        assertEquals(-1L, core.scalable().pWord(P1, 0));
        assertEquals(0L, core.scalable().pWord(P1, 1), "nada acima do PL efetivo");
        run(core, CNTB);
        assertEquals(64L, core.x(1), "cntb lê o VL efetivo (512), não o implementado (1024)");
    }

    // ── Acesso, streaming e integração com blocos ────────────────────────────────────────────────

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        long value;

        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return value;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {AND, PTRUE_B_ALL_P1, CNTB, CNTP_B, INCP_X_B, PNEXT_B, BRKA_Z, SETFFR})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(256);
        core.setSystemRegisterBus(new Cpacr());
        core.setX(1, 0x1234);
        setOnly(core, P1, 9);
        run(core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0L, core.exceptionState().elr(Aarch64ExceptionLevel.EL1));
        assertEquals(0x1234L, core.x(1), "a instrução não executou");
        assertBits(only(core, 9), core, P1, "predicado intacto");
    }

    @Test
    void predicatesFollowTheStreamingVectorLengthInStreamingMode() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256,
                512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, PTRUE_B_ALL_P1);
        assertEquals(32, popcountBits(core, 1), "fora do streaming: VL=256");
        run(architecture, core, SMSTART_SM, PTRUE_B_ALL_P1, CNTB);
        assertEquals(64, popcountBits(core, 1), "em streaming: SVL=512");
        assertEquals(64L, core.x(1), "cntb lê o SVL");
    }

    @Test
    void ffrInstructionsAreUndefinedInStreamingModeWithoutFa64() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        for (int word : new int[] {SETFFR, RDFFR_P4, RDFFR_P4_PG5, WRFFR_P6}) {
            Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture,
                    256, 256);
            core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
            run(architecture, core, SMSTART_SM);
            core.memory().write32(0x10, word);
            core.setProgramCounter(0x10);
            new Ir64BlockExecutor(architecture).step(core);
            assertEquals(HANDLER, core.pc(), "word " + Integer.toHexString(word));
            assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26,
                    "EC=0: UNDEFINED");
        }
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(256);
        core.memory().write32(0, PTRUE_B_ALL_P1);
        core.memory().write32(4, 0x2518e3e2); // ptrue p2.b
        core.memory().write32(8, 0x25208821); // cntp x1, p2, p1.b
        Ir64Block block = new StandardIr64BlockLifter(Aarch64Architecture.ARMV9_0_A).lift(core.memory(), 0, 3);
        new Ir64BlockExecutor(Aarch64Architecture.ARMV9_0_A).executeBlock(core, block);
        assertEquals(32L, core.x(1));
        assertEquals(12L, core.pc());
        assertFalse(core.streamingModeEnabled());
    }

    private static int popcountBits(Aarch64Core core, int reg) {
        int count = 0;
        for (boolean b : bits(core, reg)) {
            count += b ? 1 : 0;
        }
        return count;
    }

    /// Acesso a elemento de `Z` pelos mesmos primitivos do executor.
    private static final class PredicateAccess {
        static long element(Aarch64Core core, int reg, int index, int esz) {
            int bitsPerElement = 8 << esz;
            long word = core.scalable().zWord(reg, index * bitsPerElement / 64);
            long shifted = word >>> (index * bitsPerElement % 64);
            return bitsPerElement == 64 ? shifted : shifted & ((1L << bitsPerElement) - 1);
        }

        static void setElement(Aarch64Core core, int reg, int index, int esz, long value) {
            int bitsPerElement = 8 << esz;
            int wordIndex = index * bitsPerElement / 64;
            int shift = index * bitsPerElement % 64;
            long mask = bitsPerElement == 64 ? -1L : (1L << bitsPerElement) - 1;
            long word = core.scalable().zWord(reg, wordIndex);
            core.scalable().setZWord(reg, wordIndex, (word & ~(mask << shift)) | ((value & mask) << shift));
        }
    }
}
