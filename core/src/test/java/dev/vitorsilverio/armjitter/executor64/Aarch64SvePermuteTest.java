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

/// B17.10 — permutação SVE não predicada: `EXT`/`DUP`/`DUPQ`/`EXTQ`/`INSR`/`REV`/`PMOV`/`TBL`/`TBX`/`UNPK` e as três
/// granularidades de `ZIP`/`UZP`/`TRN` (vetor inteiro, elemento de 128 bits, dentro do segmento). As 86 palavras
/// foram montadas com `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+sme+f64mm`) a partir do TEXTO
/// de cada linha do `sve.decode`; os resultados esperados são calculados aqui, por elementos (`long[]`), de forma
/// independente do executor (que trabalha sobre bytes), e conferidos em vários `VL` — inclusive `384`, o único que
/// tem número ÍMPAR de segmentos de 128 bits.
class Aarch64SvePermuteTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 384, 512};
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0x00L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int SEGMENT_BYTES = 16;
    private static final int Z0 = 0;
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture SVE2P2 = Aarch64Architecture.extending(SVE2, "teste-SVE2p2",
            Aarch64Feature.SVE2_2);
    private static final Aarch64Architecture F64MM = Aarch64Architecture.extending(SVE, "teste-F64MM",
            Aarch64Feature.F64MM);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SVE2P1, "teste-tudo",
            Aarch64Feature.F64MM);

    private record Row(int word, String asm, Ir64Op.SvePermute.Op op, int esz, int rd, int rn, int rm, int imm) {
        Ir64Op.SvePermute expected() {
            return new Ir64Op.SvePermute(op, esz, rd, rn, rm, imm, 0L);
        }
    }

    private static Row row(int word, String asm, String op, int esz, int rd, int rn, int rm, int imm) {
        return new Row(word, asm, Ir64Op.SvePermute.Op.valueOf(op), esz, rd, rn, rm, imm);
    }

    private static Stream<Row> rows() {
        return Stream.of(
                row(0x05200020, "ext z0.b, z0.b, z1.b, #0", "EXT", 0, 0, 0, 1, 0),
                row(0x053f1c62, "ext z2.b, z2.b, z3.b, #255", "EXT", 0, 2, 0, 3, 255),
                row(0x052204a4, "ext z4.b, z4.b, z5.b, #17", "EXT", 0, 4, 0, 5, 17),
                row(0x05601441, "ext z1.b, {z2.b-z3.b}, #5", "EXT_SVE2", 0, 1, 2, 0, 5),
                row(0x057903e1, "ext z1.b, {z31.b-z0.b}, #200", "EXT_SVE2", 0, 1, 31, 0, 200),
                row(0x05203820, "dup z0.b, w1", "DUP_S", 0, 0, 1, 0, 0),
                row(0x05603820, "dup z0.h, w1", "DUP_S", 1, 0, 1, 0, 0),
                row(0x05a03be0, "dup z0.s, wsp", "DUP_S", 2, 0, 31, 0, 0),
                row(0x05e03be0, "dup z0.d, sp", "DUP_S", 3, 0, 31, 0, 0),
                row(0x05e03860, "dup z0.d, x3", "DUP_S", 3, 0, 3, 0, 0),
                row(0x05212041, "dup z1.b, z2.b[0]", "DUP_X", 0, 1, 2, 0, 0),
                row(0x05ff2041, "dup z1.b, z2.b[63]", "DUP_X", 0, 1, 2, 0, 63),
                row(0x05362041, "dup z1.h, z2.h[5]", "DUP_X", 1, 1, 2, 0, 5),
                row(0x053c2041, "dup z1.s, z2.s[3]", "DUP_X", 2, 1, 2, 0, 3),
                row(0x05382041, "dup z1.d, z2.d[1]", "DUP_X", 3, 1, 2, 0, 1),
                row(0x05b02041, "dup z1.q, z2.q[2]", "DUP_X", 4, 1, 2, 0, 2),
                row(0x05f02041, "dup z1.q, z2.q[3]", "DUP_X", 4, 1, 2, 0, 3),
                row(0x05212420, "dupq z0.b, z1.b[0]", "DUPQ", 0, 0, 1, 0, 0),
                row(0x053f2420, "dupq z0.b, z1.b[15]", "DUPQ", 0, 0, 1, 0, 15),
                row(0x053e2420, "dupq z0.h, z1.h[7]", "DUPQ", 1, 0, 1, 0, 7),
                row(0x053c2420, "dupq z0.s, z1.s[3]", "DUPQ", 2, 0, 1, 0, 3),
                row(0x05382420, "dupq z0.d, z1.d[1]", "DUPQ", 3, 0, 1, 0, 1),
                row(0x05282420, "dupq z0.d, z1.d[0]", "DUPQ", 3, 0, 1, 0, 0),
                row(0x05602420, "extq z0.b, z0.b, z1.b, #0", "EXTQ", 0, 0, 0, 1, 0),
                row(0x05652462, "extq z2.b, z2.b, z3.b, #5", "EXTQ", 0, 2, 0, 3, 5),
                row(0x056f2462, "extq z2.b, z2.b, z3.b, #15", "EXTQ", 0, 2, 0, 3, 15),
                row(0x05243820, "insr z0.b, w1", "INSR_R", 0, 0, 0, 1, 0),
                row(0x05643820, "insr z0.h, w1", "INSR_R", 1, 0, 0, 1, 0),
                row(0x05a43be0, "insr z0.s, wzr", "INSR_R", 2, 0, 0, 31, 0),
                row(0x05e43840, "insr z0.d, x2", "INSR_R", 3, 0, 0, 2, 0),
                row(0x05343820, "insr z0.b, b1", "INSR_F", 0, 0, 0, 1, 0),
                row(0x05743820, "insr z0.h, h1", "INSR_F", 1, 0, 0, 1, 0),
                row(0x05b43820, "insr z0.s, s1", "INSR_F", 2, 0, 0, 1, 0),
                row(0x05f43be0, "insr z0.d, d31", "INSR_F", 3, 0, 0, 31, 0),
                row(0x05383820, "rev z0.b, z1.b", "REV", 0, 0, 1, 0, 0),
                row(0x05783820, "rev z0.h, z1.h", "REV", 1, 0, 1, 0, 0),
                row(0x05b83820, "rev z0.s, z1.s", "REV", 2, 0, 1, 0, 0),
                row(0x05f83820, "rev z0.d, z1.d", "REV", 3, 0, 1, 0, 0),
                row(0x052a3820, "pmov p0.b, z1", "PMOV_PV", 0, 0, 1, 0, 0),
                row(0x052c3822, "pmov p2.h, z1[0]", "PMOV_PV", 1, 2, 1, 0, 0),
                row(0x052e3822, "pmov p2.h, z1[1]", "PMOV_PV", 1, 2, 1, 0, 1),
                row(0x05683883, "pmov p3.s, z4[0]", "PMOV_PV", 2, 3, 4, 0, 0),
                row(0x056e3883, "pmov p3.s, z4[3]", "PMOV_PV", 2, 3, 4, 0, 3),
                row(0x05a838a4, "pmov p4.d, z5[0]", "PMOV_PV", 3, 4, 5, 0, 0),
                row(0x05ee38a4, "pmov p4.d, z5[7]", "PMOV_PV", 3, 4, 5, 0, 7),
                row(0x052b3801, "pmov z1, p0.b", "PMOV_VP", 0, 1, 0, 0, 0),
                row(0x052d3841, "pmov z1[0], p2.h", "PMOV_VP", 1, 1, 2, 0, 0),
                row(0x052f3841, "pmov z1[1], p2.h", "PMOV_VP", 1, 1, 2, 0, 1),
                row(0x05693864, "pmov z4[0], p3.s", "PMOV_VP", 2, 4, 3, 0, 0),
                row(0x056f3864, "pmov z4[3], p3.s", "PMOV_VP", 2, 4, 3, 0, 3),
                row(0x05a93885, "pmov z5[0], p4.d", "PMOV_VP", 3, 5, 4, 0, 0),
                row(0x05ef3885, "pmov z5[7], p4.d", "PMOV_VP", 3, 5, 4, 0, 7),
                row(0x05223020, "tbl z0.b, {z1.b}, z2.b", "TBL", 0, 0, 1, 2, 0),
                row(0x05623020, "tbl z0.h, {z1.h}, z2.h", "TBL", 1, 0, 1, 2, 0),
                row(0x05a23020, "tbl z0.s, {z1.s}, z2.s", "TBL", 2, 0, 1, 2, 0),
                row(0x05e23020, "tbl z0.d, {z1.d}, z2.d", "TBL", 3, 0, 1, 2, 0),
                row(0x05232820, "tbl z0.b, {z1.b-z2.b}, z3.b", "TBL_SVE2", 0, 0, 1, 3, 0),
                row(0x05e32be0, "tbl z0.d, {z31.d-z0.d}, z3.d", "TBL_SVE2", 3, 0, 31, 3, 0),
                row(0x05222c20, "tbx z0.b, z1.b, z2.b", "TBX", 0, 0, 1, 2, 0),
                row(0x05a22c20, "tbx z0.s, z1.s, z2.s", "TBX", 2, 0, 1, 2, 0),
                row(0x4402f820, "tblq z0.b, {z1.b}, z2.b", "TBLQ", 0, 0, 1, 2, 0),
                row(0x44c2f820, "tblq z0.d, {z1.d}, z2.d", "TBLQ", 3, 0, 1, 2, 0),
                row(0x05223420, "tbxq z0.b, z1.b, z2.b", "TBXQ", 0, 0, 1, 2, 0),
                row(0x05623420, "tbxq z0.h, z1.h, z2.h", "TBXQ", 1, 0, 1, 2, 0),
                row(0x05703820, "sunpklo z0.h, z1.b", "SUNPKLO", 1, 0, 1, 0, 0),
                row(0x05713820, "sunpkhi z0.h, z1.b", "SUNPKHI", 1, 0, 1, 0, 0),
                row(0x05b23820, "uunpklo z0.s, z1.h", "UUNPKLO", 2, 0, 1, 0, 0),
                row(0x05f33820, "uunpkhi z0.d, z1.s", "UUNPKHI", 3, 0, 1, 0, 0),
                row(0x05f03820, "sunpklo z0.d, z1.s", "SUNPKLO", 3, 0, 1, 0, 0),
                row(0x05733820, "uunpkhi z0.h, z1.b", "UUNPKHI", 1, 0, 1, 0, 0),
                row(0x05226020, "zip1 z0.b, z1.b, z2.b", "ZIP1", 0, 0, 1, 2, 0),
                row(0x05626420, "zip2 z0.h, z1.h, z2.h", "ZIP2", 1, 0, 1, 2, 0),
                row(0x05a26820, "uzp1 z0.s, z1.s, z2.s", "UZP1", 2, 0, 1, 2, 0),
                row(0x05e26c20, "uzp2 z0.d, z1.d, z2.d", "UZP2", 3, 0, 1, 2, 0),
                row(0x05227020, "trn1 z0.b, z1.b, z2.b", "TRN1", 0, 0, 1, 2, 0),
                row(0x05627420, "trn2 z0.h, z1.h, z2.h", "TRN2", 1, 0, 1, 2, 0),
                row(0x05a20020, "zip1 z0.q, z1.q, z2.q", "ZIP1_Q", 4, 0, 1, 2, 0),
                row(0x05a20420, "zip2 z0.q, z1.q, z2.q", "ZIP2_Q", 4, 0, 1, 2, 0),
                row(0x05a20820, "uzp1 z0.q, z1.q, z2.q", "UZP1_Q", 4, 0, 1, 2, 0),
                row(0x05a20c20, "uzp2 z0.q, z1.q, z2.q", "UZP2_Q", 4, 0, 1, 2, 0),
                row(0x05a21820, "trn1 z0.q, z1.q, z2.q", "TRN1_Q", 4, 0, 1, 2, 0),
                row(0x05a21c20, "trn2 z0.q, z1.q, z2.q", "TRN2_Q", 4, 0, 1, 2, 0),
                row(0x4402e020, "zipq1 z0.b, z1.b, z2.b", "ZIPQ1", 0, 0, 1, 2, 0),
                row(0x4442e420, "zipq2 z0.h, z1.h, z2.h", "ZIPQ2", 1, 0, 1, 2, 0),
                row(0x4482e820, "uzpq1 z0.s, z1.s, z2.s", "UZPQ1", 2, 0, 1, 2, 0),
                row(0x44c2ec20, "uzpq2 z0.d, z1.d, z2.d", "UZPQ2", 3, 0, 1, 2, 0));
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

    private static boolean decodesToPermute(Aarch64Architecture architecture, int word) {
        try {
            return decode(architecture, word) instanceof Ir64Op.SvePermute;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    /// Bytes distintos por registrador (semente por registrador), para que qualquer troca de posição apareça.
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

    private static byte[] bytesOf(Aarch64Core core, int reg) {
        byte[] out = new byte[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (core.scalable().zWord(reg, i / 8) >>> (8 * (i % 8)));
        }
        return out;
    }

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << (8 << esz)) - 1L;
    }

    private static int word(int base, int esz, int rm, int opcode, int rn, int rd) {
        return base | (esz << 22) | (rm << 16) | (opcode << 10) | (rn << 5) | rd;
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("rows")
    void everyEncodingDecodesToTheFieldsOfItsText(Row row) {
        assertEquals(row.expected(), decode(ALL, row.word()), row.asm());
    }

    @Test
    void theTableHasAllFortyTwoEncodingsPlusTheirVariants() {
        assertEquals(86, rows().count());
        assertEquals(Ir64Op.SvePermute.Op.values().length, rows().map(Row::op).distinct().count(),
                "toda operação do grupo tem ao menos uma linha");
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theGroupIsRefusedWithoutSve(Row row) {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, row.word()));
    }

    private enum Tier { BASE, SVE2, SVE2P1, F64MM }

    private static Tier tierOf(Ir64Op.SvePermute.Op op) {
        return switch (op) {
            case EXT, DUP_S, DUP_X, INSR_F, INSR_R, REV, TBL, SUNPKLO, SUNPKHI, UUNPKLO, UUNPKHI,
                 ZIP1, ZIP2, UZP1, UZP2, TRN1, TRN2 -> Tier.BASE;
            case EXT_SVE2, TBL_SVE2, TBX -> Tier.SVE2;
            case ZIP1_Q, ZIP2_Q, UZP1_Q, UZP2_Q, TRN1_Q, TRN2_Q -> Tier.F64MM;
            default -> Tier.SVE2P1;
        };
    }

    /// O gate é POR LINHA: SVE base, SVE2 (`EXT`/`TBL` de dois registradores/`TBX`), SVE2p1 (as formas por segmento,
    /// `PMOV`, `DUPQ`/`EXTQ`/`TBXQ`) e F64MM (as `_q`, que o rascunho da spec atribuía a SVE2 por engano).
    @ParameterizedTest
    @MethodSource("rows")
    void eachRowDecodesOnlyUnderItsOwnFeature(Row row) {
        Tier tier = tierOf(row.op());
        assertEquals(tier == Tier.BASE, decodesToPermute(SVE, row.word()), "SVE: " + row.asm());
        assertEquals(tier == Tier.BASE || tier == Tier.SVE2, decodesToPermute(SVE2, row.word()), "SVE2: " + row.asm());
        assertEquals(tier != Tier.F64MM, decodesToPermute(SVE2P1, row.word()), "SVE2p1: " + row.asm());
        assertEquals(tier == Tier.BASE || tier == Tier.F64MM, decodesToPermute(F64MM, row.word()),
                "F64MM: " + row.asm());
        assertTrue(decodesToPermute(ALL, row.word()), "tudo: " + row.asm());
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x05303820, // UNPK com esz = 0: não existe elemento menor que o byte
            0x05202041, // DUP (indexado) com tsz = 00000
            0x05302420, // DUPQ com tsz = 10000 (quadword não existe)
            0x05202420, // DUPQ com tsz = 00000
            0x05283822, // PMOV para predicado com o campo posicional 00 000 00 (alocação vazia)
            0x05293802, // PMOV para vetor, idem
            0x05a21020, // ZIP_q com o opcode 100 (só 000-011 e 110-111 existem)
            0x05a21420, // idem, 101
            0x05227820, // TRN_z com opcode 110
            0x05227c20, // idem, 111
            0x4402f020, // TBLQ/ZIPQ com opcode 100
            0x4402f420, // 101
            0x4402fc20, // 111
    })
    void unallocatedEncodingsAreRefusedEvenWithEveryFeature(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, word), Integer.toHexString(word));
    }

    // ── EXT / EXTQ ───────────────────────────────────────────────────────────────────────────────

    private static int extWord(int imm, int rm, int rd) {
        return 0x05200000 | ((imm >> 3) << 16) | ((imm & 7) << 10) | (rm << 5) | rd;
    }

    @Test
    void extExtractsBytesFromTheConcatenationAndTreatsAnOutOfRangeImmediateAsZero() {
        for (int vl : VECTOR_LENGTHS) {
            int bytes = vl / 8;
            for (int imm : new int[] {0, 1, 7, 17, bytes - 1, bytes, bytes + 1, 255}) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z2, 1);
                fill(core, Z3, 2);
                byte[] lo = bytesOf(core, Z2);
                byte[] hi = bytesOf(core, Z3);
                run(SVE, core, extWord(imm, Z3, Z2));
                int offset = imm >= bytes ? 0 : imm;
                byte[] expected = new byte[bytes];
                for (int i = 0; i < bytes; i++) {
                    expected[i] = i + offset < bytes ? lo[i + offset] : hi[i + offset - bytes];
                }
                assertArrayEquals(expected, bytesOf(core, Z2), "VL=" + vl + " imm=" + imm);
            }
        }
    }

    @Test
    void ext_sve2ReadsAConsecutivePairWrappingFrom31To0() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE2, vl);
            fill(core, 31, 3);
            fill(core, Z0, 4);
            byte[] lo = bytesOf(core, 31);
            byte[] hi = bytesOf(core, Z0);
            run(SVE2, core, 0x057903e1); // ext z1.b, { z31.b, z0.b }, #200
            int bytes = vl / 8;
            int offset = 200 >= bytes ? 0 : 200;
            byte[] expected = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                expected[i] = i + offset < bytes ? lo[i + offset] : hi[i + offset - bytes];
            }
            assertArrayEquals(expected, bytesOf(core, Z1), "VL=" + vl);
        }
    }

    @Test
    void extqExtractsInsideEachSegment() {
        for (int vl : VECTOR_LENGTHS) {
            for (int imm : new int[] {0, 1, 5, 15}) {
                Aarch64Core core = core(SVE2P1, vl);
                fill(core, Z2, 5);
                fill(core, Z3, 6);
                byte[] lo = bytesOf(core, Z2);
                byte[] hi = bytesOf(core, Z3);
                run(SVE2P1, core, 0x05602400 | (imm << 16) | (Z3 << 5) | Z2);
                byte[] expected = new byte[vl / 8];
                for (int base = 0; base < expected.length; base += SEGMENT_BYTES) {
                    for (int j = 0; j < SEGMENT_BYTES; j++) {
                        expected[base + j] = j + imm < SEGMENT_BYTES ? lo[base + j + imm]
                                : hi[base + j + imm - SEGMENT_BYTES];
                    }
                }
                assertArrayEquals(expected, bytesOf(core, Z2), "VL=" + vl + " imm=" + imm);
            }
        }
    }

    // ── DUP / DUPQ / INSR / REV ──────────────────────────────────────────────────────────────────

    @Test
    void dupFromAGeneralRegisterBroadcastsTheTruncatedValueAndReadsSpForRegister31() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                core.setSp(0x1122_3344_5566_7788L);
                run(SVE, core, 0x05203be0 | (esz << 22)); // dup z0.T, sp
                long[] out = elements(core, Z0, esz);
                for (long element : out) {
                    assertEquals(0x1122_3344_5566_7788L & mask(esz), element, "esz=" + esz + " VL=" + vl);
                }
            }
        }
    }

    @Test
    void dupFromAnOrdinaryRegisterReadsXnAndNotSp() {
        Aarch64Core core = core(SVE, 256);
        core.setSp(0x1111L);
        core.setX(3, 0x2222L);
        run(SVE, core, 0x05e03860); // dup z0.d, x3
        for (long element : elements(core, Z0, 3)) {
            assertEquals(0x2222L, element);
        }
    }

    @Test
    void theSegmentFormsAreAlsoEnabledByFeatSve2p2WhichImpliesSve2p1() {
        assertTrue(decodesToPermute(SVE2P2, 0x05602420), "extq");
        assertTrue(decodesToPermute(SVE2P2, 0x4402e020), "zipq1");
    }

    @Test
    void dupFromAnElementZeroesTheResultWhenTheIndexIsBeyondTheVector() {
        for (int vl : VECTOR_LENGTHS) {
            int bytes = vl / 8;
            Aarch64Core core = core(SVE, vl);
            fill(core, Z2, 7);
            byte[] source = bytesOf(core, Z2);
            run(SVE, core, 0x05ff2041); // dup z1.b, z2.b[63]
            byte[] expected = new byte[bytes];
            if (63 < bytes) {
                java.util.Arrays.fill(expected, source[63]);
            }
            assertArrayEquals(expected, bytesOf(core, Z1), "VL=" + vl + " (63 " + (63 < bytes ? "dentro" : "fora") + ")");
        }
    }

    @Test
    void dupOfEachElementSizeIncludingTheQuadword() {
        Aarch64Core core = core(SVE, 512);
        fill(core, Z2, 8);
        byte[] source = bytesOf(core, Z2);
        run(SVE, core, 0x05b02041); // dup z1.q, z2.q[2]
        byte[] out = bytesOf(core, Z1);
        for (int segment = 0; segment < 4; segment++) {
            for (int j = 0; j < SEGMENT_BYTES; j++) {
                assertEquals(source[2 * SEGMENT_BYTES + j], out[segment * SEGMENT_BYTES + j]);
            }
        }
        run(SVE, core, 0x05f02041); // dup z1.q, z2.q[3]
        assertEquals(source[3 * SEGMENT_BYTES], bytesOf(core, Z1)[0]);
        run(SVE, core, 0x05362041); // dup z1.h, z2.h[5]
        for (long element : elements(core, Z1, 1)) {
            assertEquals(elements(core, Z2, 1)[5], element);
        }
        Aarch64Core small = core(SVE, 128);
        fill(small, Z2, 9);
        run(SVE, small, 0x05f02041); // q[3] com VL = 128: só há o quadword 0
        assertArrayEquals(new byte[16], bytesOf(small, Z1));
    }

    @Test
    void dupqBroadcastsTheChosenElementOfEachSegmentInsideThatSegment() {
        for (int vl : VECTOR_LENGTHS) {
            for (int[] rowSpec : new int[][] {{0x05212420, 0, 0}, {0x053f2420, 0, 15}, {0x053e2420, 1, 7},
                    {0x053c2420, 2, 3}, {0x05382420, 3, 1}, {0x05282420, 3, 0}}) {
                Aarch64Core core = core(SVE2P1, vl);
                fill(core, Z1, 10);
                long[] source = elements(core, Z1, rowSpec[1]);
                run(SVE2P1, core, rowSpec[0]);
                long[] out = elements(core, Z0, rowSpec[1]);
                int perSegment = SEGMENT_BYTES >> rowSpec[1];
                for (int i = 0; i < out.length; i++) {
                    assertEquals(source[(i / perSegment) * perSegment + rowSpec[2]], out[i],
                            "VL=" + vl + " word=" + Integer.toHexString(rowSpec[0]) + " i=" + i);
                }
            }
        }
    }

    @Test
    void insrShiftsTheWholeVectorUpAndTruncatesTheScalarIntoElementZero() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z0, 11);
                long[] before = elements(core, Z0, esz);
                core.setX(1, 0x0123_4567_89AB_CDEFL);
                run(SVE, core, 0x05243820 | (esz << 22)); // insr z0.T, x1
                long[] after = elements(core, Z0, esz);
                assertEquals(0x0123_4567_89AB_CDEFL & mask(esz), after[0]);
                for (int i = 1; i < after.length; i++) {
                    assertEquals(before[i - 1], after[i], "esz=" + esz + " VL=" + vl + " i=" + i);
                }
            }
        }
    }

    @Test
    void insrFromASimdRegisterReadsTheLowBitsOfVmAndFromRegister31ReadsZero() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z0, 12);
        core.scalable().setZWord(1, 0, 0xAABB_CCDD_EEFF_0011L);
        long before = elements(core, Z0, 3)[0];
        run(SVE, core, 0x05f43820); // insr z0.d, d1
        assertEquals(0xAABB_CCDD_EEFF_0011L, elements(core, Z0, 3)[0]);
        assertEquals(before, elements(core, Z0, 3)[1]);
        run(SVE, core, 0x05b43820); // insr z0.s, s1
        assertEquals(0xEEFF_0011L, elements(core, Z0, 2)[0]);
        run(SVE, core, 0x05a43be0); // insr z0.s, wzr
        assertEquals(0L, elements(core, Z0, 2)[0]);
    }

    @Test
    void revReversesTheElementOrderOfTheWholeVectorNotTheBytesInsideEachElement() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z1, 13);
                long[] source = elements(core, Z1, esz);
                run(SVE, core, 0x05383820 | (esz << 22)); // rev z0.T, z1.T
                long[] out = elements(core, Z0, esz);
                for (int i = 0; i < out.length; i++) {
                    assertEquals(source[source.length - 1 - i], out[i], "esz=" + esz + " VL=" + vl);
                }
            }
        }
    }

    // ── PMOV ─────────────────────────────────────────────────────────────────────────────────────

    private static boolean zBit(Aarch64Core core, int reg, int bit) {
        return ((core.scalable().zWord(reg, bit / 64) >>> (bit % 64)) & 1L) != 0;
    }

    private static boolean pBit(Aarch64Core core, int reg, int bit) {
        return ((core.scalable().pWord(reg, bit / 64) >>> (bit % 64)) & 1L) != 0;
    }

    @Test
    void pmovToPredicateTakesBitElementsTimesIdxPlusEOfTheVectorIntoPredicateBitETimesEsize() {
        int[][] cases = {{0x052a3820, 0, 0}, {0x052c3820, 1, 0}, {0x052e3820, 1, 1}, {0x05683820, 2, 0},
                {0x056e3820, 2, 3}, {0x05a83820, 3, 0}, {0x05ee3820, 3, 7}};
        for (int vl : VECTOR_LENGTHS) {
            for (int[] c : cases) {
                Aarch64Core core = core(SVE2P1, vl);
                fill(core, Z1, 14);
                for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
                    core.scalable().setPWord(0, w, -1L); // lixo que o PMOV tem que apagar
                }
                run(SVE2P1, core, c[0]); // pmov p0.T, z1[imm]
                int size = 1 << c[1];
                int elements = vl / 8 / size;
                for (int bit = 0; bit < vl / 8; bit++) {
                    boolean expected = bit % size == 0 && zBit(core, Z1, elements * c[2] + bit / size);
                    assertEquals(expected, pBit(core, 0, bit), "VL=" + vl + " esz=" + c[1] + " imm=" + c[2]
                            + " bit=" + bit);
                }
            }
        }
    }

    @Test
    void pmovRoundTripsThroughThePredicateAndTheByteFormZeroesTheRestOfTheVector() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE2P1, vl);
            fill(core, Z1, 15);
            byte[] original = bytesOf(core, Z1);
            run(SVE2P1, core, 0x052a3822); // pmov p2.b, z1
            run(SVE2P1, core, 0x052b3840); // pmov z0, p2.b   (rd = 0, rn = 2)
            byte[] out = bytesOf(core, Z0);
            for (int i = 0; i < out.length; i++) {
                assertEquals(i < vl / 64 ? original[i] : 0, out[i], "VL=" + vl + " byte " + i);
            }
        }
    }

    @Test
    void pmovToVectorWithANonZeroSliceKeepsTheOtherSlices() {
        Aarch64Core core = core(SVE2P1, 256);
        fill(core, Z0, 16);
        core.scalable().setPWord(2, 0, -1L);
        byte[] before = bytesOf(core, Z0);
        run(SVE2P1, core, 0x052f3840); // pmov z0[1], p2.h
        int elements = 256 / 8 / 2;
        for (int bit = 0; bit < 256; bit++) {
            boolean inSlice = bit >= elements && bit < 2 * elements;
            boolean expected = inSlice || ((before[bit / 8] >>> (bit % 8)) & 1) != 0;
            assertEquals(expected, zBit(core, Z0, bit), "bit " + bit);
        }
    }

    // ── TBL / TBX / TBLQ / TBXQ ──────────────────────────────────────────────────────────────────

    private static long[] indexes(int count, int esz, long seed) {
        Random random = new Random(seed);
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            long bound = esz == 3 ? 2L * count + 3 : Math.min(2L * count + 3, mask(esz) + 1L);
            out[i] = Math.floorMod(random.nextLong(), bound);
        }
        return out;
    }

    @Test
    void tblIndexesTheWholeVectorAndZeroesOutOfRangeIndexes() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE, vl);
                fill(core, Z1, 17);
                int count = vl / 8 >> esz;
                setElements(core, Z2, esz, indexes(count, esz, 18 + esz));
                long[] table = elements(core, Z1, esz);
                long[] idx = elements(core, Z2, esz);
                run(SVE, core, 0x05203020 | (esz << 22) | (Z2 << 16) | (Z1 << 5)); // tbl z0.T, { z1.T }, z2.T
                long[] out = elements(core, Z0, esz);
                for (int i = 0; i < count; i++) {
                    assertEquals(idx[i] < count ? table[(int) idx[i]] : 0L, out[i], "esz=" + esz + " VL=" + vl);
                }
            }
        }
    }

    @Test
    void tblOfTwoRegistersReadsTheSecondHalfOfTheTable() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                Aarch64Core core = core(SVE2, vl);
                fill(core, Z1, 19);
                fill(core, Z2, 20);
                int count = vl / 8 >> esz;
                setElements(core, Z3, esz, indexes(count, esz, 21 + esz));
                long[] t0 = elements(core, Z1, esz);
                long[] t1 = elements(core, Z2, esz);
                long[] idx = elements(core, Z3, esz);
                run(SVE2, core, 0x05202800 | (esz << 22) | (Z3 << 16) | (Z1 << 5)); // tbl z0.T, { z1.T, z2.T }, z3.T
                long[] out = elements(core, Z0, esz);
                for (int i = 0; i < count; i++) {
                    long expected = idx[i] < count ? t0[(int) idx[i]]
                            : idx[i] < 2L * count ? t1[(int) (idx[i] - count)] : 0L;
                    assertEquals(expected, out[i], "esz=" + esz + " VL=" + vl);
                }
            }
        }
    }

    @Test
    void tblOfTwoRegistersInTheSameRegisterAsTheDestinationStillSeesTheOldTable() {
        Aarch64Core core = core(SVE2, 256);
        fill(core, Z1, 22);
        fill(core, Z2, 23);
        setElements(core, Z3, 3, 5, 0, 7, 2);
        long[] t0 = elements(core, Z1, 3);
        long[] t1 = elements(core, Z2, 3);
        run(SVE2, core, 0x05202800 | (3 << 22) | (Z3 << 16) | (Z1 << 5) | Z1); // tbl z1.d, { z1.d, z2.d }, z3.d
        assertEquals(t1[1], elements(core, Z1, 3)[0]);
        assertEquals(t0[0], elements(core, Z1, 3)[1]);
        assertEquals(t1[3], elements(core, Z1, 3)[2]);
        assertEquals(t0[2], elements(core, Z1, 3)[3]);
    }

    @Test
    void tbxPreservesTheDestinationWhereTheIndexIsOutOfRange() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz : new int[] {0, 2}) {
                Aarch64Core core = core(SVE2, vl);
                fill(core, Z0, 24);
                fill(core, Z1, 25);
                int count = vl / 8 >> esz;
                setElements(core, Z2, esz, indexes(count, esz, 26 + esz));
                long[] before = elements(core, Z0, esz);
                long[] table = elements(core, Z1, esz);
                long[] idx = elements(core, Z2, esz);
                run(SVE2, core, 0x05202c00 | (esz << 22) | (Z2 << 16) | (Z1 << 5)); // tbx z0.T, z1.T, z2.T
                long[] out = elements(core, Z0, esz);
                for (int i = 0; i < count; i++) {
                    assertEquals(idx[i] < count ? table[(int) idx[i]] : before[i], out[i], "esz=" + esz + " VL=" + vl);
                }
            }
        }
    }

    @Test
    void tblqAndTbxqUseTheSegmentAsTheTable() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (boolean extension : new boolean[] {false, true}) {
                    Aarch64Core core = core(SVE2P1, vl);
                    fill(core, Z0, 27);
                    fill(core, Z1, 28);
                    int perSegment = SEGMENT_BYTES >> esz;
                    int count = vl / 8 >> esz;
                    setElements(core, Z2, esz, indexes(count, esz, 29 + esz));
                    long[] before = elements(core, Z0, esz);
                    long[] table = elements(core, Z1, esz);
                    long[] idx = elements(core, Z2, esz);
                    int base = extension ? 0x05203400 : 0x4400f800;
                    run(SVE2P1, core, base | (esz << 22) | (Z2 << 16) | (Z1 << 5));
                    long[] out = elements(core, Z0, esz);
                    for (int i = 0; i < count; i++) {
                        int segment = i / perSegment * perSegment;
                        long expected = idx[i] < perSegment ? table[segment + (int) idx[i]]
                                : extension ? before[i] : 0L;
                        assertEquals(expected, out[i], "esz=" + esz + " VL=" + vl + " tbx=" + extension);
                    }
                }
            }
        }
    }

    // ── UNPK ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unpackExtendsTheLowOrHighHalfWithOrWithoutSignToDoubleWidthElements() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 1; esz <= 3; esz++) {
                for (int flags = 0; flags < 4; flags++) {
                    boolean high = (flags & 1) != 0;
                    boolean unsigned = (flags & 2) != 0;
                    Aarch64Core core = core(SVE, vl);
                    fill(core, Z1, 30);
                    long[] source = elements(core, Z1, esz - 1);
                    run(SVE, core, 0x05303820 | (esz << 22) | (flags << 16)); // [su]unpk{lo,hi} z0.T, z1.Tb
                    long[] out = elements(core, Z0, esz);
                    int base = high ? source.length / 2 : 0;
                    for (int i = 0; i < out.length; i++) {
                        long value = source[base + i];
                        if (!unsigned) {
                            int shift = 64 - (8 << (esz - 1));
                            value = (value << shift) >> shift;
                        }
                        assertEquals(value & mask(esz), out[i], "esz=" + esz + " flags=" + flags + " VL=" + vl);
                    }
                }
            }
        }
    }

    @Test
    void unpackOnTheSameRegisterDoesNotReadItsOwnOutput() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z1, 31);
        long[] source = elements(core, Z1, 0);
        run(SVE, core, 0x05703821); // sunpklo z1.h, z1.b
        long[] out = elements(core, Z1, 1);
        for (int i = 0; i < out.length; i++) {
            assertEquals((long) (byte) source[i] & 0xFFFF, out[i]);
        }
    }

    // ── ZIP / UZP / TRN ──────────────────────────────────────────────────────────────────────────

    private static long[] zip(long[] n, long[] m, int part) {
        long[] d = new long[n.length];
        int half = n.length / 2;
        for (int i = 0; i < half; i++) {
            d[2 * i] = n[part * half + i];
            d[2 * i + 1] = m[part * half + i];
        }
        return d;
    }

    private static long[] uzp(long[] n, long[] m, int part) {
        long[] d = new long[n.length];
        int half = n.length / 2;
        for (int i = 0; i < half; i++) {
            d[i] = n[2 * i + part];
            d[half + i] = m[2 * i + part];
        }
        return d;
    }

    private static long[] trn(long[] n, long[] m, int part) {
        long[] d = new long[n.length];
        for (int i = 0; i < n.length / 2; i++) {
            d[2 * i] = n[2 * i + part];
            d[2 * i + 1] = m[2 * i + part];
        }
        return d;
    }

    private static long[] apply(int family, long[] n, long[] m, int part) {
        return family == 0 ? zip(n, m, part) : family == 1 ? uzp(n, m, part) : trn(n, m, part);
    }

    @Test
    void theWholeVectorFormsPermuteAcrossTheEntireVector() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (int family = 0; family < 3; family++) {
                    for (int part = 0; part < 2; part++) {
                        Aarch64Core core = core(SVE, vl);
                        fill(core, Z1, 32);
                        fill(core, Z2, 33);
                        long[] expected = apply(family, elements(core, Z1, esz), elements(core, Z2, esz), part);
                        run(SVE, core, word(0x05206000, esz, Z2, family * 2 + part, Z1, Z0));
                        assertArrayEquals(expected, elements(core, Z0, esz),
                                "família=" + family + " parte=" + part + " esz=" + esz + " VL=" + vl);
                    }
                }
            }
        }
    }

    @Test
    void thePerSegmentFormsApplyTheSameAlgorithmInsideEach128BitSegment() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (int family = 0; family < 2; family++) {
                    for (int part = 0; part < 2; part++) {
                        Aarch64Core core = core(SVE2P1, vl);
                        fill(core, Z1, 34);
                        fill(core, Z2, 35);
                        long[] n = elements(core, Z1, esz);
                        long[] m = elements(core, Z2, esz);
                        int perSegment = SEGMENT_BYTES >> esz;
                        long[] expected = new long[n.length];
                        for (int base = 0; base < n.length; base += perSegment) {
                            long[] part2 = apply(family, java.util.Arrays.copyOfRange(n, base, base + perSegment),
                                    java.util.Arrays.copyOfRange(m, base, base + perSegment), part);
                            System.arraycopy(part2, 0, expected, base, perSegment);
                        }
                        run(SVE2P1, core, word(0x4400e000, esz, Z2, family * 2 + part, Z1, Z0));
                        assertArrayEquals(expected, elements(core, Z0, esz),
                                "família=" + family + " parte=" + part + " esz=" + esz + " VL=" + vl);
                    }
                }
            }
        }
    }

    /// `ZIP1_z` intercala o vetor INTEIRO e `ZIPQ1` intercala DENTRO de cada segmento: com `VL >= 256` os resultados
    /// DIFEREM (o teste que a spec exige e que é impossível em `VL = 128`).
    @Test
    void wholeVectorAndPerSegmentZipDifferWhenTheVectorHasMoreThanOneSegment() {
        for (int vl : new int[] {256, 384, 512}) {
            Aarch64Core whole = core(SVE2P1, vl);
            Aarch64Core segment = core(SVE2P1, vl);
            for (Aarch64Core core : new Aarch64Core[] {whole, segment}) {
                fill(core, Z1, 36);
                fill(core, Z2, 37);
            }
            run(SVE2P1, whole, word(0x05206000, 3, Z2, 0, Z1, Z0));
            run(SVE2P1, segment, word(0x4400e000, 3, Z2, 0, Z1, Z0));
            long[] n = elements(whole, Z1, 3);
            long[] m = elements(whole, Z2, 3);
            // ZIP1 (vetor inteiro): n0 m0 n1 m1 ...   ZIPQ1 (por segmento): n0 m0 | n2 m2 | ...
            assertEquals(n[1], elements(whole, Z0, 3)[2], "VL=" + vl);
            assertEquals(n[2], elements(segment, Z0, 3)[2], "VL=" + vl);
            assertEquals(m[2], elements(segment, Z0, 3)[3], "VL=" + vl);
            assertTrue(!java.util.Arrays.equals(elements(whole, Z0, 3), elements(segment, Z0, 3)), "VL=" + vl);
        }
        Aarch64Core whole = core(SVE2P1, 128);
        Aarch64Core segment = core(SVE2P1, 128);
        for (Aarch64Core core : new Aarch64Core[] {whole, segment}) {
            fill(core, Z1, 38);
            fill(core, Z2, 39);
        }
        run(SVE2P1, whole, word(0x05206000, 3, Z2, 0, Z1, Z0));
        run(SVE2P1, segment, word(0x4400e000, 3, Z2, 0, Z1, Z0));
        assertArrayEquals(elements(whole, Z0, 3), elements(segment, Z0, 3), "em VL=128 as duas coincidem");
    }

    /// Cada segmento de 128 bits vira um par de doublewords; a referência é a definição do manual
    /// (`result[e] = concat(m:n)[2e + part]` no UZP, pares intercalados no ZIP/TRN, sobras zeradas).
    private static long[][] segmentsOf(long[] doublewords) {
        long[][] segments = new long[doublewords.length / 2][];
        for (int s = 0; s < segments.length; s++) {
            segments[s] = new long[] {doublewords[2 * s], doublewords[2 * s + 1]};
        }
        return segments;
    }

    private static long[] flatten(long[][] segments, int doublewords) {
        long[] out = new long[doublewords];
        for (int s = 0; s < segments.length; s++) {
            if (segments[s] != null) {
                out[2 * s] = segments[s][0];
                out[2 * s + 1] = segments[s][1];
            }
        }
        return out;
    }

    private static long[] segmentReference(int family, int part, long[] n, long[] m) {
        long[][] ns = segmentsOf(n);
        long[][] ms = segmentsOf(m);
        int count = ns.length;
        int pairs = count / 2;
        long[][] out = new long[count][];
        switch (family) {
            case 0 -> {
                for (int p = 0; p < pairs; p++) {
                    out[2 * p] = ns[part * pairs + p];
                    out[2 * p + 1] = ms[part * pairs + p];
                }
            }
            case 1 -> {
                for (int e = 0; e < count; e++) {
                    int index = 2 * e + part;
                    out[e] = index < count ? ns[index] : ms[index - count];
                }
            }
            default -> {
                for (int p = 0; p < pairs; p++) {
                    out[2 * p] = ns[2 * p + part];
                    out[2 * p + 1] = ms[2 * p + part];
                }
            }
        }
        return flatten(out, n.length);
    }

    @Test
    void theSegmentElementFormsPermuteWholeQuadwordsAndLeaveTheLastSegmentZeroWhenTheCountIsOdd() {
        int[] opcodes = {0, 1, 2, 3, 6, 7};
        for (int vl : new int[] {256, 384, 512}) {
            for (int opcode : opcodes) {
                Aarch64Core core = core(F64MM, vl);
                fill(core, Z1, 40);
                fill(core, Z2, 41);
                int family = opcode >= 6 ? 2 : opcode >> 1;
                long[] expected = segmentReference(family, opcode & 1, elements(core, Z1, 3), elements(core, Z2, 3));
                run(F64MM, core, 0x05a00000 | (Z2 << 16) | (opcode << 10) | (Z1 << 5) | Z0);
                assertArrayEquals(expected, elements(core, Z0, 3), "opcode=" + opcode + " VL=" + vl);
            }
        }
    }

    @Test
    void theSegmentElementFormsAreUndefinedAtVl128AndInStreamingMode() {
        Aarch64Core core = core(F64MM, 128);
        run(F64MM, core, 0x05a20020); // zip1 z0.q, z1.q, z2.q
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);

        Aarch64Architecture streaming = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-SME-q",
                Aarch64Feature.F64MM);
        Aarch64Core sm = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), streaming, 256, 256);
        sm.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(streaming, sm, SMSTART_SM);
        sm.memory().write32(0x10, 0x05a20020);
        sm.setProgramCounter(0x10);
        new Ir64BlockExecutor(streaming).step(sm);
        assertEquals(HANDLER, sm.pc());
        assertEquals(ESR_EC_UNKNOWN, sm.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26,
                "EC=0: UNDEFINED em modo streaming");
    }

    // ── Vetor efetivo, aliasing, acesso, streaming e blocos ──────────────────────────────────────

    @Test
    void permutingARegisterWithItselfReadsTheOldValueBeforeWriting() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z1, 42);
        long[] before = elements(core, Z1, 2);
        run(SVE, core, word(0x05206000, 2, Z1, 0, Z1, Z1)); // zip1 z1.s, z1.s, z1.s
        assertArrayEquals(zip(before, before, 0), elements(core, Z1, 2));
    }

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(SVE, 1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(Z1, w, 0xDEAD_BEEFL);
        }
        run(SVE, core, 0x05383820 | (3 << 22)); // rev z0.d, z1.d
        assertEquals(0xDEAD_BEEFL, core.scalable().zWord(Z0, 0));
        assertEquals(0xDEAD_BEEFL, core.scalable().zWord(Z0, 3));
        assertEquals(0L, core.scalable().zWord(Z0, 4), "nada acima do VL efetivo é escrito");
    }

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
    @ValueSource(ints = {0x05200020, 0x05203820, 0x05226020, 0x05223020, 0x05703820})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE, 256);
        core.setSystemRegisterBus(new Cpacr());
        fill(core, Z0, 43);
        long[] before = elements(core, Z0, 3);
        run(SVE, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertArrayEquals(before, elements(core, Z0, 3), "a instrução não executou");
    }

    @Test
    void permutationsAreLegalInStreamingModeAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        for (int w = 0; w < 8; w++) {
            core.scalable().setZWord(Z1, w, w + 1L);
        }
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, 0x05f83820); // rev z0.d, z1.d
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        for (int w = 0; w < 8; w++) {
            assertEquals(8L - w, core.scalable().zWord(Z0, w), "SVL = 512: 8 doublewords");
        }
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        fill(core, Z1, 44);
        core.memory().write32(0, 0x05f83820); // rev z0.d, z1.d
        core.memory().write32(4, 0x05f03840); // sunpklo z0.d, z2.s  (rd = 0, rn = 2)
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
    }
}
