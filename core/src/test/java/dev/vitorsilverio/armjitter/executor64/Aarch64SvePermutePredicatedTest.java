package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.11 — permutação de predicado, permutação predicada e `SEL`: `ZIP`/`UZP`/`TRN`/`REV`/`PUNPK` de predicado,
/// `COMPACT`/`EXPAND`/`SPLICE`, `LAST*`/`CLAST*` nos três destinos, `CPY` merging, `REVB/H/W`/`RBIT`/`REVD` e
/// `SEL_zpzz`. As 76 palavras foram montadas com `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1+sme+f64mm+sve2p2`) a partir do texto de cada linha do `sve.decode`. Os casos
/// pequenos têm o resultado escrito À MÃO (não calculado por um algoritmo espelho); os aleatórios são conferidos em
/// vários `VL` contra referências por elemento, independentes do executor.
class Aarch64SvePermutePredicatedTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 384, 512};
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0x00L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int Z0 = 0;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P1 = 1;
    private static final int P2 = 2;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture SVE2P2 = Aarch64Architecture.extending(SVE2, "teste-SVE2p2",
            Aarch64Feature.SVE2_2);

    private record Row(int word, String asm, Ir64Op.SvePermutePredicated.Op op, int esz, int rd, int rn, int rm,
            int pg) {
        Ir64Op.SvePermutePredicated expected() {
            return new Ir64Op.SvePermutePredicated(op, esz, rd, rn, rm, pg, 0L);
        }
    }

    private static Row row(int word, String asm, String op, int esz, int rd, int rn, int rm, int pg) {
        return new Row(word, asm, Ir64Op.SvePermutePredicated.Op.valueOf(op), esz, rd, rn, rm, pg);
    }

    private static Stream<Row> rows() {
        return Stream.of(
                row(0x05224020, "zip1 p0.b, p1.b, p2.b", "ZIP1_P", 0, 0, 1, 2, 0),
                row(0x05654483, "zip2 p3.h, p4.h, p5.h", "ZIP2_P", 1, 3, 4, 5, 0),
                row(0x05a24820, "uzp1 p0.s, p1.s, p2.s", "UZP1_P", 2, 0, 1, 2, 0),
                row(0x05ef4c83, "uzp2 p3.d, p4.d, p15.d", "UZP2_P", 3, 3, 4, 15, 0),
                row(0x05225020, "trn1 p0.b, p1.b, p2.b", "TRN1_P", 0, 0, 1, 2, 0),
                row(0x05655483, "trn2 p3.h, p4.h, p5.h", "TRN2_P", 1, 3, 4, 5, 0),
                row(0x05344020, "rev p0.b, p1.b", "REV_P", 0, 0, 1, 0, 0),
                row(0x05744062, "rev p2.h, p3.h", "REV_P", 1, 2, 3, 0, 0),
                row(0x05b440a4, "rev p4.s, p5.s", "REV_P", 2, 4, 5, 0, 0),
                row(0x05f441e6, "rev p6.d, p15.d", "REV_P", 3, 6, 15, 0, 0),
                row(0x05304020, "punpklo p0.h, p1.b", "PUNPKLO", 0, 0, 1, 0, 0),
                row(0x053141e2, "punpkhi p2.h, p15.b", "PUNPKHI", 0, 2, 15, 0, 0),
                row(0x05a18440, "compact z0.s, p1, z2.s", "COMPACT", 2, 0, 2, 0, 1),
                row(0x05e18440, "compact z0.d, p1, z2.d", "COMPACT", 3, 0, 2, 0, 1),
                row(0x05218440, "compact z0.b, p1, z2.b", "COMPACT", 0, 0, 2, 0, 1),
                row(0x05618440, "compact z0.h, p1, z2.h", "COMPACT", 1, 0, 2, 0, 1),
                row(0x05288440, "clasta z0.b, p1, z0.b, z2.b", "CLASTA_Z", 0, 0, 0, 2, 1),
                row(0x05a98440, "clastb z0.s, p1, z0.s, z2.s", "CLASTB_Z", 2, 0, 0, 2, 1),
                row(0x05e88883, "clasta z3.d, p2, z3.d, z4.d", "CLASTA_Z", 3, 3, 0, 4, 2),
                row(0x052a8440, "clasta b0, p1, b0, z2.b", "CLASTA_V", 0, 0, 2, 0, 1),
                row(0x056b8440, "clastb h0, p1, h0, z2.h", "CLASTB_V", 1, 0, 2, 0, 1),
                row(0x05aa8440, "clasta s0, p1, s0, z2.s", "CLASTA_V", 2, 0, 2, 0, 1),
                row(0x05eb8440, "clastb d0, p1, d0, z2.d", "CLASTB_V", 3, 0, 2, 0, 1),
                row(0x0530a440, "clasta w0, p1, w0, z2.b", "CLASTA_R", 0, 0, 2, 0, 1),
                row(0x0571a440, "clastb w0, p1, w0, z2.h", "CLASTB_R", 1, 0, 2, 0, 1),
                row(0x05b0a440, "clasta w0, p1, w0, z2.s", "CLASTA_R", 2, 0, 2, 0, 1),
                row(0x05f1a440, "clastb x0, p1, x0, z2.d", "CLASTB_R", 3, 0, 2, 0, 1),
                row(0x05f0a865, "clasta x5, p2, x5, z3.d", "CLASTA_R", 3, 5, 3, 0, 2),
                row(0x05228440, "lasta b0, p1, z2.b", "LASTA_V", 0, 0, 2, 0, 1),
                row(0x05638440, "lastb h0, p1, z2.h", "LASTB_V", 1, 0, 2, 0, 1),
                row(0x05a28440, "lasta s0, p1, z2.s", "LASTA_V", 2, 0, 2, 0, 1),
                row(0x05e38440, "lastb d0, p1, z2.d", "LASTB_V", 3, 0, 2, 0, 1),
                row(0x0520a440, "lasta w0, p1, z2.b", "LASTA_R", 0, 0, 2, 0, 1),
                row(0x0561a440, "lastb w0, p1, z2.h", "LASTB_R", 1, 0, 2, 0, 1),
                row(0x05a0a440, "lasta w0, p1, z2.s", "LASTA_R", 2, 0, 2, 0, 1),
                row(0x05e1a440, "lastb x0, p1, z2.d", "LASTB_R", 3, 0, 2, 0, 1),
                row(0x05e0ac87, "lasta x7, p3, z4.d", "LASTA_R", 3, 7, 4, 0, 3),
                row(0x05208440, "cpy z0.b, p1/m, b2", "CPY_M_V", 0, 0, 2, 0, 1),
                row(0x05608440, "cpy z0.h, p1/m, h2", "CPY_M_V", 1, 0, 2, 0, 1),
                row(0x05a08440, "cpy z0.s, p1/m, s2", "CPY_M_V", 2, 0, 2, 0, 1),
                row(0x05e08440, "cpy z0.d, p1/m, d2", "CPY_M_V", 3, 0, 2, 0, 1),
                row(0x0528a440, "cpy z0.b, p1/m, w2", "CPY_M_R", 0, 0, 2, 0, 1),
                row(0x0568a440, "cpy z0.h, p1/m, w2", "CPY_M_R", 1, 0, 2, 0, 1),
                row(0x05a8a7e0, "cpy z0.s, p1/m, wsp", "CPY_M_R", 2, 0, 31, 0, 1),
                row(0x05e8a7e0, "cpy z0.d, p1/m, sp", "CPY_M_R", 3, 0, 31, 0, 1),
                row(0x05e8a440, "cpy z0.d, p1/m, x2", "CPY_M_R", 3, 0, 2, 0, 1),
                row(0x05648440, "revb z0.h, p1/m, z2.h", "REVB_M", 1, 0, 2, 0, 1),
                row(0x05a48440, "revb z0.s, p1/m, z2.s", "REVB_M", 2, 0, 2, 0, 1),
                row(0x05e48440, "revb z0.d, p1/m, z2.d", "REVB_M", 3, 0, 2, 0, 1),
                row(0x05a58440, "revh z0.s, p1/m, z2.s", "REVH_M", 2, 0, 2, 0, 1),
                row(0x05e58440, "revh z0.d, p1/m, z2.d", "REVH_M", 3, 0, 2, 0, 1),
                row(0x05e68440, "revw z0.d, p1/m, z2.d", "REVW_M", 3, 0, 2, 0, 1),
                row(0x05278440, "rbit z0.b, p1/m, z2.b", "RBIT_M", 0, 0, 2, 0, 1),
                row(0x05678440, "rbit z0.h, p1/m, z2.h", "RBIT_M", 1, 0, 2, 0, 1),
                row(0x05a78440, "rbit z0.s, p1/m, z2.s", "RBIT_M", 2, 0, 2, 0, 1),
                row(0x05e78440, "rbit z0.d, p1/m, z2.d", "RBIT_M", 3, 0, 2, 0, 1),
                row(0x052e8440, "revd z0.q, p1/m, z2.q", "REVD_M", 0, 0, 2, 0, 1),
                row(0x0564a440, "revb z0.h, p1/z, z2.h", "REVB_Z", 1, 0, 2, 0, 1),
                row(0x05e4a440, "revb z0.d, p1/z, z2.d", "REVB_Z", 3, 0, 2, 0, 1),
                row(0x05a5a440, "revh z0.s, p1/z, z2.s", "REVH_Z", 2, 0, 2, 0, 1),
                row(0x05e6a440, "revw z0.d, p1/z, z2.d", "REVW_Z", 3, 0, 2, 0, 1),
                row(0x0527a440, "rbit z0.b, p1/z, z2.b", "RBIT_Z", 0, 0, 2, 0, 1),
                row(0x05e7a440, "rbit z0.d, p1/z, z2.d", "RBIT_Z", 3, 0, 2, 0, 1),
                row(0x052ea440, "revd z0.q, p1/z, z2.q", "REVD_Z", 0, 0, 2, 0, 1),
                row(0x052c8440, "splice z0.b, p1, z0.b, z2.b", "SPLICE", 0, 0, 0, 2, 1),
                row(0x05ec8883, "splice z3.d, p2, z3.d, z4.d", "SPLICE", 3, 3, 0, 4, 2),
                row(0x052d8440, "splice z0.b, p1, {z2.b, z3.b}", "SPLICE_SVE2", 0, 0, 2, 0, 1),
                row(0x05ed87e0, "splice z0.d, p1, {z31.d, z0.d}", "SPLICE_SVE2", 3, 0, 31, 0, 1),
                row(0x05318440, "expand z0.b, p1, z2.b", "EXPAND", 0, 0, 2, 0, 1),
                row(0x05718440, "expand z0.h, p1, z2.h", "EXPAND", 1, 0, 2, 0, 1),
                row(0x05b18440, "expand z0.s, p1, z2.s", "EXPAND", 2, 0, 2, 0, 1),
                row(0x05f18440, "expand z0.d, p1, z2.d", "EXPAND", 3, 0, 2, 0, 1),
                row(0x0523c440, "sel z0.b, p1, z2.b, z3.b", "SEL", 0, 0, 2, 3, 1),
                row(0x0563e040, "sel z0.h, p8, z2.h, z3.h", "SEL", 1, 0, 2, 3, 8),
                row(0x05a3fc40, "sel z0.s, p15, z2.s, z3.s", "SEL", 2, 0, 2, 3, 15),
                row(0x05e3e440, "sel z0.d, p9, z2.d, z3.d", "SEL", 3, 0, 2, 3, 9));
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static boolean decodesToPredicatedPermute(Aarch64Architecture architecture, int word) {
        try {
            return decode(architecture, word) instanceof Ir64Op.SvePermutePredicated;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    private static void fill(Aarch64Core core, int reg, long seed) {
        Random random = new Random(seed);
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, random.nextLong());
        }
    }

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[core.vectorLengthBytes() >> esz];
        for (int i = 0; i < out.length; i++) {
            out[i] = SvePredicateOps.elementOf(core.scalable(), reg, i, esz);
        }
        return out;
    }

    private static void setElements(Aarch64Core core, int reg, int esz, long... values) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
        for (int i = 0; i < values.length; i++) {
            SvePredicateOps.setElementOf(core.scalable(), reg, i, esz, values[i]);
        }
    }

    private static boolean[] predicateBits(Aarch64Core core, int reg) {
        boolean[] bits = new boolean[core.vectorLengthBytes()];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = ((core.scalable().pWord(reg, i / 64) >>> (i % 64)) & 1L) != 0L;
        }
        return bits;
    }

    private static void setPredicateBits(Aarch64Core core, int reg, boolean[] bits) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(reg, w, 0L);
        }
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                core.scalable().setPWord(reg, i / 64, core.scalable().pWord(reg, i / 64) | (1L << (i % 64)));
            }
        }
    }

    private static boolean[] randomBits(Random random, int length) {
        boolean[] bits = new boolean[length];
        for (int i = 0; i < length; i++) {
            bits[i] = random.nextBoolean();
        }
        return bits;
    }

    /// Ativa os elementos indicados (bit `e << esz`, como o hardware) e zera o resto do predicado.
    private static void activate(Aarch64Core core, int reg, int esz, int... elements) {
        boolean[] bits = new boolean[core.vectorLengthBytes()];
        for (int e : elements) {
            bits[e << esz] = true;
        }
        setPredicateBits(core, reg, bits);
    }

    /// Predicado aleatório; devolve, por elemento, se ele está ativo, e grava os bits (`e << esz`) em `reg`.
    private static boolean[] randomActivity(Aarch64Core core, int reg, int esz, Random random) {
        boolean[] activity = randomBits(random, core.vectorLengthBytes() >> esz);
        boolean[] bits = new boolean[core.vectorLengthBytes()];
        for (int e = 0; e < activity.length; e++) {
            bits[e << esz] = activity[e];
        }
        setPredicateBits(core, reg, bits);
        return activity;
    }

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << (8 << esz)) - 1L;
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("rows")
    void everyEncodingDecodesToTheFieldsOfItsText(Row row) {
        assertEquals(row.expected(), decode(SVE2P2, row.word()), row.asm());
    }

    @Test
    void theTableCoversEveryOperationOfTheGroup() {
        assertEquals(76, rows().count());
        assertEquals(Ir64Op.SvePermutePredicated.Op.values().length, rows().map(Row::op).distinct().count(),
                "toda operação do grupo (36 encodings) tem ao menos uma linha");
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theGroupIsRefusedWithoutSve(Row row) {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, row.word()));
    }

    private enum Tier { BASE, SVE2, SVE2P1, SVE2P2 }

    /// O gate é POR LINHA: SVE base, SVE2 (`SPLICE` construtivo), SVE2p1 (`REVD_m`) e SVE2p2 (as cinco formas
    /// `_z`, `EXPAND` e `COMPACT` de byte/halfword).
    private static Tier tierOf(Row row) {
        return switch (row.op()) {
            case SPLICE_SVE2 -> Tier.SVE2;
            case REVD_M -> Tier.SVE2P1;
            case EXPAND, REVB_Z, REVH_Z, REVW_Z, RBIT_Z, REVD_Z -> Tier.SVE2P2;
            case COMPACT -> row.esz() >= 2 ? Tier.BASE : Tier.SVE2P2;
            default -> Tier.BASE;
        };
    }

    @ParameterizedTest
    @MethodSource("rows")
    void eachRowDecodesOnlyUnderItsOwnFeature(Row row) {
        Tier tier = tierOf(row);
        assertEquals(tier == Tier.BASE, decodesToPredicatedPermute(SVE, row.word()), "SVE: " + row.asm());
        assertEquals(tier.ordinal() <= Tier.SVE2.ordinal(), decodesToPredicatedPermute(SVE2, row.word()),
                "SVE2: " + row.asm());
        assertEquals(tier.ordinal() <= Tier.SVE2P1.ordinal(), decodesToPredicatedPermute(SVE2P1, row.word()),
                "SVE2p1: " + row.asm());
        assertTrue(decodesToPredicatedPermute(SVE2P2, row.word()), "SVE2p2: " + row.asm());
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x05248440, // REVB com esz = 0 (nada a inverter dentro de um byte)
            0x05258440, // REVH com esz = 0
            0x05658440, // REVH com esz = 1
            0x05268440, // REVW com esz = 0
            0x05668440, // REVW com esz = 1
            0x05a68440, // REVW com esz = 2
            0x0524a440, // REVB_z com esz = 0
            0x056e8440, // REVD com esz != 0 (o campo é fixo em 0)
            0x056ea440, // REVD_z com esz != 0
            0x05205800, // ZIP/UZP/TRN de predicado com o opcode 110
            0x05205c00, // idem, 111
            0x05388440, // reversão inexistente (bits 21:16 = 111000, forma merging)
            0x0538a440, // idem, forma zeroing
    })
    void unallocatedEncodingsAreRefusedEvenWithEveryFeature(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2P2, word));
    }

    // ── Permutação de predicado (resultado escrito à mão, VL = 128: 16 bits de predicado) ────────

    private static long runPredicate(int word, long n, long m) {
        Aarch64Core core = core(SVE, 128);
        core.scalable().setPWord(1, 0, n);
        core.scalable().setPWord(2, 0, m);
        run(SVE, core, word);
        return core.scalable().pWord(0, 0);
    }

    @Test
    void predicatePermutationsMatchHandComputedResultsAtVl128() {
        assertEquals(0x5555L, runPredicate(0x05224020, 0xFFFF, 0x0000), "zip1 .b: n nas posições pares");
        assertEquals(0xAAAAL, runPredicate(0x05224020, 0x0000, 0xFFFF), "zip1 .b: m nas ímpares");
        assertEquals(0x5555L, runPredicate(0x05224420, 0xFFFF, 0x0000), "zip2 .b: metade alta de n");
        assertEquals(0xFFFFL, runPredicate(0x05224820, 0x5555, 0xFFFF), "uzp1 .b: pares de n embaixo, de m em cima");
        assertEquals(0xFF00L, runPredicate(0x05224C20, 0x5555, 0xFFFF), "uzp2 .b: ímpares de n = 0, de m = 1");
        assertEquals(0x5555L, runPredicate(0x05225020, 0xFFFF, 0x0000), "trn1 .b");
        assertEquals(0x5555L, runPredicate(0x05225420, 0xFFFF, 0x0000), "trn2 .b");
        assertEquals(0x8000L, runPredicate(0x05344020, 0x0001, 0), "rev .b: bit 0 vai para o bit 15");
        assertEquals(0xC000L, runPredicate(0x05744020, 0x0003, 0), "rev .h: o grupo de 2 bits mantém a ordem");
        assertEquals(0x5555L, runPredicate(0x05304020, 0x00FF, 0), "punpklo: cada bit vira um par");
        assertEquals(0x5555L, runPredicate(0x05314020, 0xFF00, 0), "punpkhi");
    }

    @Test
    void predicatePermutationsCopyWholeGroupsNotJustTheLowBitOfTheElement() {
        // esz = 2: grupos de 4 bits. `zip1 p0.s`: grupo 0 de n (1111) e grupo 1 de n (0110) INTEIROS, com zero entre eles.
        assertEquals(0x060FL, runPredicate(0x05a24020, 0b0110_1111L, 0L));
    }

    private static boolean[] groupwise(boolean[] n, boolean[] m, int width, int[][] map) {
        boolean[] out = new boolean[n.length];
        for (int g = 0; g < n.length / width; g++) {
            boolean[] from = map[g][0] == 0 ? n : m;
            for (int b = 0; b < width; b++) {
                out[g * width + b] = from[map[g][1] * width + b];
            }
        }
        return out;
    }

    /// Mapa `{operando, grupo}` de cada grupo do resultado, escrito direto do pseudocódigo do manual.
    private static int[][] permutationMap(int family, int part, int groups) {
        int[][] map = new int[groups][];
        int half = groups / 2;
        for (int g = 0; g < groups; g++) {
            map[g] = switch (family) {
                case 0 -> new int[] {g % 2, part * half + g / 2};
                case 1 -> new int[] {g < half ? 0 : 1, 2 * (g % half) + part};
                case 2 -> new int[] {g % 2, (g & ~1) + part};
                default -> new int[] {0, groups - 1 - g};
            };
        }
        return map;
    }

    @Test
    void predicatePermutationsMatchTheGroupReferenceAtEveryVectorLengthAndElementSize() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (int opcode = 0; opcode <= 5; opcode++) {
                    Random random = new Random(vl * 31L + esz * 7L + opcode);
                    Aarch64Core core = core(SVE, vl);
                    boolean[] n = randomBits(random, vl / 8);
                    boolean[] m = randomBits(random, vl / 8);
                    setPredicateBits(core, 1, n);
                    setPredicateBits(core, 2, m);
                    int[][] map = permutationMap(opcode >> 1, opcode & 1, (vl / 8) >> esz);
                    run(SVE, core, 0x05204000 | (esz << 22) | (2 << 16) | (opcode << 10) | (1 << 5));
                    assertArrayEquals(groupwise(n, m, 1 << esz, map), predicateBits(core, 0),
                            "opcode=" + opcode + " esz=" + esz + " VL=" + vl);
                }
                Random random = new Random(vl + esz);
                Aarch64Core core = core(SVE, vl);
                boolean[] n = randomBits(random, vl / 8);
                setPredicateBits(core, 1, n);
                run(SVE, core, 0x05344000 | (esz << 22) | (1 << 5)); // rev p0.T, p1.T
                assertArrayEquals(groupwise(n, n, 1 << esz, permutationMap(3, 0, (vl / 8) >> esz)), predicateBits(core, 0),
                        "rev esz=" + esz + " VL=" + vl);
            }
        }
    }

    @Test
    void punpkExpandsEachBitOfTheChosenHalfIntoAPair() {
        for (int vl : VECTOR_LENGTHS) {
            int bits = vl / 8;
            boolean[] n = randomBits(new Random(vl), bits);
            for (int high = 0; high <= 1; high++) {
                Aarch64Core core = core(SVE, vl);
                setPredicateBits(core, 1, n);
                run(SVE, core, 0x05304020 | (high << 16));
                boolean[] expected = new boolean[bits];
                for (int i = 0; i < bits / 2; i++) {
                    expected[2 * i] = n[high * (bits / 2) + i];
                }
                assertArrayEquals(expected, predicateBits(core, 0), "high=" + high + " VL=" + vl);
            }
        }
    }

    @Test
    void permutingAPredicateWithItselfReadsTheOldValueBeforeWriting() {
        Aarch64Core core = core(SVE, 256);
        boolean[] n = randomBits(new Random(5), 32);
        setPredicateBits(core, 1, n);
        run(SVE, core, 0x05204000 | (1 << 16) | (1 << 5) | 1); // zip1 p1.b, p1.b, p1.b
        boolean[] expected = new boolean[32];
        for (int p = 0; p < 16; p++) {
            expected[2 * p] = n[p];
            expected[2 * p + 1] = n[p];
        }
        assertArrayEquals(expected, predicateBits(core, 1));
    }

    // ── COMPACT / EXPAND ─────────────────────────────────────────────────────────────────────────

    @Test
    void compactPacksTheActiveElementsAtTheStartAndZeroesTheRest() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2, 1, 3);
        fill(core, Z0, 1);
        run(SVE, core, 0x05a18440); // compact z0.s, p1, z2.s
        assertArrayEquals(new long[] {20, 40, 0, 0}, elements(core, Z0, 2));
    }

    @Test
    void expandScattersTheContiguousElementsOverTheActivePositionsAndZeroesTheRest() {
        Aarch64Core core = core(SVE2P2, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2, 1, 3);
        fill(core, Z0, 2);
        run(SVE2P2, core, 0x05b18440); // expand z0.s, p1, z2.s
        assertArrayEquals(new long[] {0, 10, 0, 20}, elements(core, Z0, 2));
    }

    @Test
    void compactAndExpandMatchTheElementReferenceAtEverySizeAndLengthWithOneCounterOverTheWholeVector() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE2P2, vl);
                fill(core, Z2, vl + esz);
                boolean[] activity = randomActivity(core, P1, esz, new Random(vl * 13L + esz));
                long[] source = elements(core, Z2, esz);
                long[] packed = new long[source.length];
                long[] spread = new long[source.length];
                int packedCount = 0;
                int spreadCount = 0;
                for (int e = 0; e < source.length; e++) {
                    if (activity[e]) {
                        packed[packedCount++] = source[e];
                        spread[e] = source[spreadCount++];
                    }
                }
                run(SVE2P2, core, 0x05218440 | (esz << 22)); // compact z0.T, p1, z2.T
                assertArrayEquals(packed, elements(core, Z0, esz), "compact esz=" + esz + " VL=" + vl);
                run(SVE2P2, core, 0x05318440 | (esz << 22)); // expand z0.T, p1, z2.T
                assertArrayEquals(spread, elements(core, Z0, esz), "expand esz=" + esz + " VL=" + vl);
            }
        }
    }

    // ── SPLICE / SEL ─────────────────────────────────────────────────────────────────────────────

    @Test
    void spliceConcatenatesTheActiveExtentOfTheFirstOperandWithTheStartOfTheSecond() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z0, 2, 1, 2, 3, 4);
        setElements(core, Z2, 2, 5, 6, 7, 8);
        activate(core, P1, 2, 1, 2);
        run(SVE, core, 0x05ac8440); // splice z0.s, p1, z0.s, z2.s
        assertArrayEquals(new long[] {2, 3, 5, 6}, elements(core, Z0, 2));

        setElements(core, Z0, 2, 1, 2, 3, 4);
        activate(core, P1, 2, 0, 3);
        run(SVE, core, 0x05ac8440);
        assertArrayEquals(new long[] {1, 2, 3, 4}, elements(core, Z0, 2), "extensão cheia: o segundo não entra");

        setElements(core, Z0, 2, 1, 2, 3, 4);
        activate(core, P1, 2);
        run(SVE, core, 0x05ac8440);
        assertArrayEquals(new long[] {5, 6, 7, 8}, elements(core, Z0, 2), "predicado vazio: só o segundo operando");
    }

    @Test
    void constructiveSpliceReadsTheConsecutiveRegisterPairIncludingTheWrapAroundOfZ31() {
        Aarch64Core core = core(SVE2, 128);
        setElements(core, Z2, 2, 1, 2, 3, 4);
        setElements(core, Z3, 2, 5, 6, 7, 8);
        activate(core, P1, 2, 2, 3);
        run(SVE2, core, 0x05ad8440); // splice z0.s, p1, {z2.s, z3.s}
        assertArrayEquals(new long[] {3, 4, 5, 6}, elements(core, Z0, 2));

        setElements(core, 31, 3, 100, 200);
        setElements(core, Z0, 3, 300, 400);
        activate(core, P1, 3, 1);
        run(SVE2, core, 0x05ed87e0); // splice z0.d, p1, {z31.d, z0.d}
        assertArrayEquals(new long[] {200, 300}, elements(core, Z0, 3));
    }

    @Test
    void spliceMatchesTheElementReferenceAtEveryVectorLength() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z0, vl);
                fill(core, Z2, vl + 1);
                boolean[] activity = randomActivity(core, P1, esz, new Random(vl * 3L + esz));
                long[] low = elements(core, Z0, esz);
                long[] high = elements(core, Z2, esz);
                int first = -1;
                int last = -1;
                for (int e = 0; e < activity.length; e++) {
                    if (activity[e]) {
                        first = first < 0 ? e : first;
                        last = e;
                    }
                }
                int length = first < 0 ? 0 : last - first + 1;
                long[] expected = new long[low.length];
                for (int e = 0; e < expected.length; e++) {
                    expected[e] = e < length ? low[first + e] : high[e - length];
                }
                run(SVE, core, 0x052c8440 | (esz << 22));
                assertArrayEquals(expected, elements(core, Z0, esz), "esz=" + esz + " VL=" + vl);
            }
        }
    }

    @Test
    void selectUsesAllSixteenPredicateRegisters() {
        for (int vl : VECTOR_LENGTHS) {
            for (int pg : new int[] {1, 8, 15}) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z2, 1);
                fill(core, Z3, 2);
                boolean[] activity = randomActivity(core, pg, 2, new Random(vl + pg));
                long[] n = elements(core, Z2, 2);
                long[] m = elements(core, Z3, 2);
                long[] expected = new long[n.length];
                for (int e = 0; e < n.length; e++) {
                    expected[e] = activity[e] ? n[e] : m[e];
                }
                run(SVE, core, 0x0520c000 | (2 << 22) | (Z3 << 16) | (pg << 10) | (Z2 << 5)); // sel z0.s, pg, z2.s, z3.s
                assertArrayEquals(expected, elements(core, Z0, 2), "pg=" + pg + " VL=" + vl);
            }
        }
    }

    // ── LAST* / CLAST* ───────────────────────────────────────────────────────────────────────────

    private static final int LASTA_W_S = 0x05a0a440; // lasta w0, p1, z2.s
    private static final int LASTB_W_S = 0x05a1a440; // lastb w0, p1, z2.s

    @Test
    void lastaTakesTheElementAfterTheLastActiveAndLastbTakesTheLastActive() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2, 0, 1);
        core.setX(0, -1L);
        run(SVE, core, LASTA_W_S);
        assertEquals(30L, core.x(0), "lasta: o elemento depois do último ativo");
        run(SVE, core, LASTB_W_S);
        assertEquals(20L, core.x(0), "lastb: o último ativo");
    }

    @Test
    void lastaWrapsToElementZeroWhenTheLastElementIsActive() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2, 3);
        run(SVE, core, LASTA_W_S);
        assertEquals(10L, core.x(0), "dá a volta");
        run(SVE, core, LASTB_W_S);
        assertEquals(40L, core.x(0));
    }

    @Test
    void withAnEmptyPredicateLastaReadsElementZeroAndLastbReadsTheLastElement() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2);
        run(SVE, core, LASTA_W_S);
        assertEquals(10L, core.x(0), "lasta com predicado vazio");
        run(SVE, core, LASTB_W_S);
        assertEquals(40L, core.x(0), "lastb com predicado vazio");
    }

    @Test
    void clastWithAnEmptyPredicateLeavesTheDestinationAloneUnlikeLast() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2);

        setElements(core, Z0, 2, 91, 92, 93, 94);
        run(SVE, core, 0x05a88440); // clasta z0.s, p1, z0.s, z2.s
        assertArrayEquals(new long[] {91, 92, 93, 94}, elements(core, Z0, 2), "clasta z inalterado");
        run(SVE, core, 0x05a98440); // clastb z0.s
        assertArrayEquals(new long[] {91, 92, 93, 94}, elements(core, Z0, 2), "clastb z inalterado");

        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);
        run(SVE, core, 0x05b0a440); // clasta w0, p1, w0, z2.s
        assertEquals(0xFFFF_FFFFL, core.x(0), "clasta w: o valor anterior, truncado ao elemento e zero-estendido");
        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);
        run(SVE, core, 0x0530a440); // clasta w0, p1, w0, z2.b
        assertEquals(0xFFL, core.x(0), "esz = 0: só o byte baixo sobrevive");
        core.setX(0, 0x1122_3344_5566_7788L);
        run(SVE, core, 0x05f1a440); // clastb x0, p1, x0, z2.d
        assertEquals(0x1122_3344_5566_7788L, core.x(0), "clastb x: intacto");

        core.scalable().setZWord(0, 0, 0xAAAA_BBBB_CCCC_DDDDL);
        core.scalable().setZWord(0, 1, 0x1L);
        run(SVE, core, 0x05aa8440); // clasta s0, p1, s0, z2.s
        assertEquals(0xCCCC_DDDDL, core.scalable().zWord(0, 0), "clasta s: o valor anterior do elemento");
        assertEquals(0L, core.scalable().zWord(0, 1), "escrever o escalar zera o resto de Z0");
    }

    @Test
    void clastWithActiveElementsExtractsLikeLastInAllThreeDestinations() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 2, 10, 20, 30, 40);
        activate(core, P1, 2, 0, 1);

        run(SVE, core, 0x05a88440); // clasta z0.s, p1, z0.s, z2.s
        assertArrayEquals(new long[] {30, 30, 30, 30}, elements(core, Z0, 2), "clasta z: difunde o elemento seguinte");
        run(SVE, core, 0x05a98440); // clastb z0.s
        assertArrayEquals(new long[] {20, 20, 20, 20}, elements(core, Z0, 2), "clastb z: difunde o último ativo");

        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);
        run(SVE, core, 0x05b0a440); // clasta w0, p1, w0, z2.s
        assertEquals(30L, core.x(0), "clasta w");

        setElements(core, Z3, 3, 5, 6);
        activate(core, P2, 3, 0);
        core.setX(5, 99L);
        run(SVE, core, 0x05f0a865); // clasta x5, p2, x5, z3.d
        assertEquals(6L, core.x(5), "clasta x: o elemento depois do único ativo");
    }

    @Test
    void lastToAVectorRegisterZeroesTheUpperBitsAndTheGeneralFormsZeroExtend() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z0, 3);
        setElements(core, Z2, 3, 0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L, 0x99L, 0xAAL);
        activate(core, P1, 3, 0, 1);
        run(SVE, core, 0x05e38440); // lastb d0, p1, z2.d
        assertEquals(0x5555_6666_7777_8888L, core.scalable().zWord(0, 0));
        for (int w = 1; w < core.scalable().wordsPerVector(); w++) {
            assertEquals(0L, core.scalable().zWord(0, w), "o resto de Z0 é zerado, w=" + w);
        }
        setElements(core, Z2, 0, 0x10, 0x81);
        activate(core, P1, 0, 0);
        run(SVE, core, 0x0520a440); // lasta w0, p1, z2.b: elemento 1
        assertEquals(0x81L, core.x(0), "zero-estendido, não com sinal");
        run(SVE, core, 0x0521a440); // lastb w0, p1, z2.b: elemento 0
        assertEquals(0x10L, core.x(0));
    }

    @Test
    void lastAndClastMatchTheReferenceAtEveryVectorLengthAndSize() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Random random = new Random(vl * 5L + esz);
                for (int trial = 0; trial < 4; trial++) {
                    Aarch64Core core = core(SVE, vl);
                    fill(core, Z2, vl + trial);
                    boolean[] activity = trial == 0 ? new boolean[core.vectorLengthBytes() >> esz] : randomActivity(core, P1, esz, random);
                    if (trial == 0) {
                        activate(core, P1, esz);
                    }
                    int last = -1;
                    for (int e = 0; e < activity.length; e++) {
                        last = activity[e] ? e : last;
                    }
                    long[] source = elements(core, Z2, esz);
                    long previous = 0xDEAD_BEEF_0BAD_F00DL & mask(esz);
                    long afterValue = source[last + 1 >= source.length ? 0 : last + 1];
                    long beforeValue = source[last < 0 ? source.length - 1 : last];

                    run(SVE, core, (LASTA_W_S & ~(3 << 22)) | (esz << 22));
                    assertEquals(afterValue, core.x(0), "lasta esz=" + esz + " VL=" + vl);
                    run(SVE, core, (LASTB_W_S & ~(3 << 22)) | (esz << 22));
                    assertEquals(beforeValue, core.x(0), "lastb");
                    core.setX(0, 0xDEAD_BEEF_0BAD_F00DL);
                    run(SVE, core, (0x05b0a440 & ~(3 << 22)) | (esz << 22)); // clasta
                    assertEquals(last < 0 ? previous : afterValue, core.x(0), "clasta");
                    core.setX(0, 0xDEAD_BEEF_0BAD_F00DL);
                    run(SVE, core, (0x05b1a440 & ~(3 << 22)) | (esz << 22)); // clastb
                    assertEquals(last < 0 ? previous : beforeValue, core.x(0), "clastb");
                    assertVectorForms(core, esz, last, source, vl);
                }
            }
        }
    }

    /// As formas `_v` (destino escalar SIMD&FP) dos quatro `LAST*`/`CLAST*`: o valor vai para os 64 bits baixos e o
    /// resto de Z0 é zerado; com predicado vazio `CLAST*` devolve o elemento anterior de Z0 e `LAST*` segue a regra.
    private static void assertVectorForms(Aarch64Core core, int esz, int last, long[] source, int vl) {
        int n = source.length;
        int[] words = {0x052a8440, 0x052b8440, 0x05228440, 0x05238440}; // clasta, clastb, lasta, lastb (.b)
        for (int form = 0; form < words.length; form++) {
            fill(core, Z0, 77 + form);
            long previous = elements(core, Z0, esz)[0];
            boolean conditional = form < 2;
            boolean after = form == 0 || form == 2;
            long expected;
            if (conditional && last < 0) {
                expected = previous;
            } else if (after) {
                expected = source[last + 1 >= n ? 0 : last + 1];
            } else {
                expected = source[last < 0 ? n - 1 : last];
            }
            run(SVE, core, (words[form] & ~(3 << 22)) | (esz << 22));
            String label = "form=" + form + " esz=" + esz + " VL=" + vl;
            assertEquals(expected, core.scalable().zWord(0, 0), label);
            for (int w = 1; w < core.scalable().wordsPerVector(); w++) {
                assertEquals(0L, core.scalable().zWord(0, w), label + " w=" + w);
            }
        }
    }

    // ── CPY merging ──────────────────────────────────────────────────────────────────────────────

    @Test
    void cpyMergingCopiesTheScalarOnlyToActiveElementsAndKeepsTheRest() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z0, 9);
                core.setX(2, 0x0123_4567_89AB_CDEFL);
                boolean[] activity = randomActivity(core, P1, esz, new Random(vl + esz));
                long[] before = elements(core, Z0, esz);
                run(SVE, core, 0x0528a440 | (esz << 22)); // cpy z0.T, p1/m, w2
                long[] after = elements(core, Z0, esz);
                for (int e = 0; e < after.length; e++) {
                    assertEquals(activity[e] ? 0x0123_4567_89AB_CDEFL & mask(esz) : before[e], after[e],
                            "esz=" + esz + " e=" + e + " VL=" + vl);
                }
            }
        }
    }

    @Test
    void cpyMergingFromTheStackPointerAndFromAVectorRegisterScalar() {
        Aarch64Core core = core(SVE, 128);
        core.setSp(0x8000_0000_0000_1000L);
        activate(core, P1, 3, 1);
        setElements(core, Z0, 3, 7, 7);
        run(SVE, core, 0x05e8a7e0); // cpy z0.d, p1/m, sp
        assertArrayEquals(new long[] {7, 0x8000_0000_0000_1000L}, elements(core, Z0, 3));

        setElements(core, Z2, 3, 0x1234_5678_9ABC_DEF0L, 0xFFL);
        setElements(core, Z0, 2, 1, 2, 3, 4);
        activate(core, P1, 2, 0, 2);
        run(SVE, core, 0x05a08440); // cpy z0.s, p1/m, s2
        assertArrayEquals(new long[] {0x9ABC_DEF0L, 2, 0x9ABC_DEF0L, 4}, elements(core, Z0, 2));
    }

    // ── REVB / REVH / REVW / RBIT / REVD ─────────────────────────────────────────────────────────

    @Test
    void reverseWithinElementsMatchesHandComputedResults() {
        Aarch64Core core = core(SVE2P2, 128);
        setElements(core, Z2, 3, 0x0102_0304_0506_0708L, 0x1122_3344_5566_7788L);
        activate(core, P1, 3, 0, 1);
        run(SVE2P2, core, 0x05e48440); // revb z0.d
        assertArrayEquals(new long[] {0x0807_0605_0403_0201L, 0x8877_6655_4433_2211L}, elements(core, Z0, 3));
        run(SVE2P2, core, 0x05e58440); // revh z0.d
        assertArrayEquals(new long[] {0x0708_0506_0304_0102L, 0x7788_5566_3344_1122L}, elements(core, Z0, 3));
        run(SVE2P2, core, 0x05e68440); // revw z0.d
        assertArrayEquals(new long[] {0x0506_0708_0102_0304L, 0x5566_7788_1122_3344L}, elements(core, Z0, 3));
        run(SVE2P2, core, 0x05e78440); // rbit z0.d
        assertArrayEquals(new long[] {Long.reverse(0x0102_0304_0506_0708L), Long.reverse(0x1122_3344_5566_7788L)},
                elements(core, Z0, 3));
        setElements(core, Z2, 1, 0x0102, 0x8001);
        activate(core, P1, 1, 0, 1);
        run(SVE2P2, core, 0x05648440); // revb z0.h
        assertEquals(0x0201L, elements(core, Z0, 1)[0]);
        assertEquals(0x0180L, elements(core, Z0, 1)[1]);
        run(SVE2P2, core, 0x05678440); // rbit z0.h
        assertEquals(0x4080L, elements(core, Z0, 1)[0]);
        assertEquals(0x8001L, elements(core, Z0, 1)[1]);
    }

    /// Referência por unidades, sem `Long.reverse`: reordena as unidades pela ordem invertida dos índices.
    private static long reverseReference(int word, long value, int esz) {
        int opcode = (word >>> 16) & 0b11; // 00 = REVB, 01 = REVH, 10 = REVW, 11 = RBIT
        int size = 1 << esz;
        long out = 0;
        if (opcode == 3) {
            for (int b = 0; b < 8 * size; b++) {
                out |= ((value >>> b) & 1L) << (8 * size - 1 - b);
            }
            return out;
        }
        int unit = 1 << opcode;
        for (int u = 0; u < size / unit; u++) {
            long chunk = (value >>> (8 * unit * u)) & ((1L << (8 * unit)) - 1L);
            out |= chunk << (8 * unit * (size / unit - 1 - u));
        }
        return out;
    }

    @Test
    void mergingReverseKeepsInactiveElementsAndZeroingReverseZeroesThem() {
        int[][] forms = {{0x05648440, 1}, {0x05a48440, 2}, {0x05e48440, 3}, {0x05a58440, 2}, {0x05e58440, 3},
                {0x05e68440, 3}, {0x05278440, 0}, {0x05678440, 1}, {0x05a78440, 2}, {0x05e78440, 3}};
        for (int vl : VECTOR_LENGTHS) {
            for (int[] form : forms) {
                for (boolean zeroing : new boolean[] {false, true}) {
                    int esz = form[1];
                    Aarch64Core core = core(SVE2P2, vl);
                    fill(core, Z2, 4);
                    fill(core, Z0, 5);
                    boolean[] activity = randomActivity(core, P1, esz, new Random(vl + form[0]));
                    long[] source = elements(core, Z2, esz);
                    long[] destination = elements(core, Z0, esz);
                    run(SVE2P2, core, zeroing ? form[0] | 0x2000 : form[0]);
                    long[] out = elements(core, Z0, esz);
                    for (int e = 0; e < out.length; e++) {
                        long expected = activity[e] ? reverseReference(form[0], source[e], esz)
                                : zeroing ? 0L : destination[e];
                        assertEquals(expected, out[e],
                                Integer.toHexString(form[0]) + " zeroing=" + zeroing + " e=" + e + " VL=" + vl);
                    }
                }
            }
        }
    }

    @Test
    void revdSwapsTheDoublewordsOfEachActiveQuadwordUsingTheFirstByteOfItsPredicate() {
        for (int vl : VECTOR_LENGTHS) {
            for (boolean zeroing : new boolean[] {false, true}) {
                Aarch64Core core = core(SVE2P2, vl);
                fill(core, Z2, 6);
                fill(core, Z0, 7);
                int quadwords = vl / 8 / 16;
                boolean[] bits = new boolean[vl / 8];
                for (int q = 0; q < quadwords; q++) {
                    bits[q * 16] = q % 2 == 0;
                    bits[q * 16 + 1] = true; // bits que NÃO pertencem ao primeiro byte não contam
                }
                setPredicateBits(core, P1, bits);
                long[] source = elements(core, Z2, 3);
                long[] destination = elements(core, Z0, 3);
                run(SVE2P2, core, zeroing ? 0x052ea440 : 0x052e8440);
                long[] out = elements(core, Z0, 3);
                for (int q = 0; q < quadwords; q++) {
                    boolean on = q % 2 == 0;
                    assertEquals(on ? source[2 * q + 1] : zeroing ? 0L : destination[2 * q], out[2 * q],
                            "q=" + q + " VL=" + vl);
                    assertEquals(on ? source[2 * q] : zeroing ? 0L : destination[2 * q + 1], out[2 * q + 1],
                            "q=" + q + " VL=" + vl);
                }
            }
        }
    }

    // ── Acesso, streaming e blocos ───────────────────────────────────────────────────────────────

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return 0L;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x05224020, 0x05a18440, 0x05a28440, 0x05a88440, 0x05a48440, 0x05ac8440, 0x05a3fc40})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE2P2, 256);
        core.setSystemRegisterBus(new Cpacr());
        fill(core, Z0, 43);
        long[] before = elements(core, Z0, 3);
        run(SVE2P2, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertArrayEquals(before, elements(core, Z0, 3), "a instrução não executou");
    }

    private static Aarch64Core streamingCore(Aarch64Architecture architecture) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256,
                512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        return core;
    }

    private static void runAt(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
    }

    @Test
    void streamingModeUsesTheStreamingVectorLengthAndForbidsCompactAndExpand() {
        Aarch64Architecture architecture = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
                "teste-SME-2p2", Aarch64Feature.SVE2_2);
        Aarch64Core core = streamingCore(architecture);
        for (int w = 0; w < 8; w++) {
            core.scalable().setZWord(Z2, w, w + 1L);
        }
        activate(core, P1, 3, 7);
        runAt(architecture, core, 0x05e38440); // lastb d0, p1, z2.d
        assertEquals(0x14L, core.pc(), "sem exceção");
        assertEquals(8L, core.scalable().zWord(Z0, 0), "SVL = 512: 8 doublewords");

        runAt(architecture, core, 0x05e18440); // compact z0.d, p1, z2.d
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26,
                "EC=0: UNDEFINED em modo streaming");

        Aarch64Core second = streamingCore(architecture);
        runAt(architecture, second, 0x05f18440); // expand z0.d, p1, z2.d
        assertEquals(HANDLER, second.pc());
        assertEquals(ESR_EC_UNKNOWN, second.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
    }

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(SVE, 1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(Z2, w, w + 1L);
        }
        core.scalable().setPWord(P1, 0, 1L << 24); // elemento 3 (esz = 3): o último dos 4 do VL efetivo
        run(SVE, core, 0x05e0a440); // lasta x0, p1, z2.d
        assertEquals(1L, core.x(0), "o índice dá a volta no VL efetivo, não no implementado");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z2, 44);
        core.memory().write32(0, 0x05a18440); // compact z0.s, p1, z2.s
        core.memory().write32(4, 0x0563e040); // sel z0.h, p8, z2.h, z3.h
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
    }
}
