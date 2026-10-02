package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64.Constructive.Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B18.12 — SME2 multi-vetor SVE "constructive". **Cada palavra foi montada por `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.5-a+sme+sme2+sme-i16i64+sme-f16f16+fp8+sve-b16b16+sve2p1`)**; os campos esperados saem do TEXTO do
/// assembly, nunca do decoder. A última coluna é o assembly.
class Aarch64SmeConstructiveDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-constr-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SME2, "teste-constr-ALL",
            Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F16F16, Aarch64Feature.FP8, Aarch64Feature.SVE_B16B16);
    private static final long ADDRESS = 0x40;

    private record Case(int word, Op op, int esz, int sources, int destinations, int zd, int zn, int zm, int shift,
            int pg, String asm) {
    }

    private static Case c(int word, Op op, int esz, int sources, int destinations, int zd, int zn, int zm, int shift,
            int pg, String asm) {
        return new Case(word, op, esz, sources, destinations, zd, zn, zm, shift, pg, asm);
    }

    private static final List<Case> CASES = List.of(
            c(0xc160e083, Op.BFCVT, 2, 2, 1, 3, 4, 0, 0, 0, "bfcvt z3.h, {z4.s-z5.s}"),
            c(0xc160e0a3, Op.BFCVTN, 2, 2, 1, 3, 4, 0, 0, 0, "bfcvtn z3.h, {z4.s-z5.s}"),
            c(0xc120e083, Op.FCVT_N, 2, 2, 1, 3, 4, 0, 0, 0, "fcvt z3.h, {z4.s-z5.s}"),
            c(0xc120e0a3, Op.FCVTN, 2, 2, 1, 3, 4, 0, 0, 0, "fcvtn z3.h, {z4.s-z5.s}"),
            c(0xc1a0e0e4, Op.FCVT_W, 1, 1, 2, 4, 7, 0, 0, 0, "fcvt {z4.s-z5.s}, z7.h"),
            c(0xc1a0e0e5, Op.FCVTL, 1, 1, 2, 4, 7, 0, 0, 0, "fcvtl {z4.s-z5.s}, z7.h"),
            c(0xc121e0c4, Op.FCVTZS, 2, 2, 2, 4, 6, 0, 0, 0, "fcvtzs {z4.s-z5.s}, {z6.s-z7.s}"),
            c(0xc131e124, Op.FCVTZU, 2, 4, 4, 4, 8, 0, 0, 0, "fcvtzu {z4.s-z7.s}, {z8.s-z11.s}"),
            c(0xc132e104, Op.SCVTF, 2, 4, 4, 4, 8, 0, 0, 0, "scvtf {z4.s-z7.s}, {z8.s-z11.s}"),
            c(0xc122e0e4, Op.UCVTF, 2, 2, 2, 4, 6, 0, 0, 0, "ucvtf {z4.s-z5.s}, {z6.s-z7.s}"),
            c(0xc1a8e0c4, Op.FRINTN, 2, 2, 2, 4, 6, 0, 0, 0, "frintn {z4.s-z5.s}, {z6.s-z7.s}"),
            c(0xc1b9e104, Op.FRINTP, 2, 4, 4, 4, 8, 0, 0, 0, "frintp {z4.s-z7.s}, {z8.s-z11.s}"),
            c(0xc1aae0c4, Op.FRINTM, 2, 2, 2, 4, 6, 0, 0, 0, "frintm {z4.s-z5.s}, {z6.s-z7.s}"),
            c(0xc1bce104, Op.FRINTA, 2, 4, 4, 4, 8, 0, 0, 0, "frinta {z4.s-z7.s}, {z8.s-z11.s}"),
            c(0xc123e083, Op.SQCVT, 2, 2, 1, 3, 4, 0, 0, 0, "sqcvt z3.h, {z4.s-z5.s}"),
            c(0xc123e0a3, Op.UQCVT, 2, 2, 1, 3, 4, 0, 0, 0, "uqcvt z3.h, {z4.s-z5.s}"),
            c(0xc163e083, Op.SQCVTU, 2, 2, 1, 3, 4, 0, 0, 0, "sqcvtu z3.h, {z4.s-z5.s}"),
            c(0xc133e083, Op.SQCVT, 2, 4, 1, 3, 4, 0, 0, 0, "sqcvt z3.b, {z4.s-z7.s}"),
            c(0xc1b3e0a3, Op.UQCVT, 3, 4, 1, 3, 4, 0, 0, 0, "uqcvt z3.h, {z4.d-z7.d}"),
            c(0xc1f3e083, Op.SQCVTU, 3, 4, 1, 3, 4, 0, 0, 0, "sqcvtu z3.h, {z4.d-z7.d}"),
            c(0xc133e0c3, Op.SQCVTN, 2, 4, 1, 3, 4, 0, 0, 0, "sqcvtn z3.b, {z4.s-z7.s}"),
            c(0xc1b3e0e3, Op.UQCVTN, 3, 4, 1, 3, 4, 0, 0, 0, "uqcvtn z3.h, {z4.d-z7.d}"),
            c(0xc173e0c3, Op.SQCVTUN, 2, 4, 1, 3, 4, 0, 0, 0, "sqcvtun z3.b, {z4.s-z7.s}"),
            c(0xc165e0e4, Op.SUNPK, 1, 1, 2, 4, 7, 0, 0, 0, "sunpk {z4.h-z5.h}, z7.b"),
            c(0xc1a5e0e5, Op.UUNPK, 2, 1, 2, 4, 7, 0, 0, 0, "uunpk {z4.s-z5.s}, z7.h"),
            c(0xc1e5e0e4, Op.SUNPK, 3, 1, 2, 4, 7, 0, 0, 0, "sunpk {z4.d-z5.d}, z7.s"),
            c(0xc175e104, Op.SUNPK, 1, 2, 4, 4, 8, 0, 0, 0, "sunpk {z4.h-z7.h}, {z8.b-z9.b}"),
            c(0xc1f5e105, Op.UUNPK, 3, 2, 4, 4, 8, 0, 0, 0, "uunpk {z4.d-z7.d}, {z8.s-z9.s}"),
            c(0xc126e0e4, Op.F1CVT, 1, 1, 2, 4, 7, 0, 0, 0, "f1cvt {z4.h-z5.h}, z7.b"),
            c(0xc1a6e0e5, Op.F2CVTL, 1, 1, 2, 4, 7, 0, 0, 0, "f2cvtl {z4.h-z5.h}, z7.b"),
            c(0xc166e0e4, Op.BF1CVT, 1, 1, 2, 4, 7, 0, 0, 0, "bf1cvt {z4.h-z5.h}, z7.b"),
            c(0xc1e6e0e5, Op.BF2CVTL, 1, 1, 2, 4, 7, 0, 0, 0, "bf2cvtl {z4.h-z5.h}, z7.b"),
            c(0xc124e083, Op.FCVT_BH, 1, 2, 1, 3, 4, 0, 0, 0, "fcvt z3.b, {z4.h-z5.h}"),
            c(0xc134e083, Op.FCVT_BS, 2, 4, 1, 3, 4, 0, 0, 0, "fcvt z3.b, {z4.s-z7.s}"),
            c(0xc134e0a3, Op.FCVTN_BS, 2, 4, 1, 3, 4, 0, 0, 0, "fcvtn z3.b, {z4.s-z7.s}"),
            c(0xc1b6e104, Op.ZIP, 2, 4, 4, 4, 8, 0, 0, 0, "zip {z4.s-z7.s}, {z8.s-z11.s}"),
            c(0xc136e106, Op.UZP, 0, 4, 4, 4, 8, 0, 0, 0, "uzp {z4.b-z7.b}, {z8.b-z11.b}"),
            c(0xc137e104, Op.ZIP, 4, 4, 4, 4, 8, 0, 0, 0, "zip {z4.q-z7.q}, {z8.q-z11.q}"),
            c(0xc1f6e106, Op.UZP, 3, 4, 4, 4, 8, 0, 0, 0, "uzp {z4.d-z7.d}, {z8.d-z11.d}"),
            c(0xc1e3d483, Op.SQRSHR, 2, 2, 1, 3, 4, 0, 13, 0, "sqrshr z3.h, {z4.s-z5.s}, #13"),
            c(0xc1efd4a3, Op.UQRSHR, 2, 2, 1, 3, 4, 0, 1, 0, "uqrshr z3.h, {z4.s-z5.s}, #1"),
            c(0xc1f0d483, Op.SQRSHRU, 2, 2, 1, 3, 4, 0, 16, 0, "sqrshru z3.h, {z4.s-z5.s}, #16"),
            c(0xc17bd883, Op.SQRSHR, 2, 4, 1, 3, 4, 0, 5, 0, "sqrshr z3.b, {z4.s-z7.s}, #5"),
            c(0xc1e3d883, Op.SQRSHR, 3, 4, 1, 3, 4, 0, 29, 0, "sqrshr z3.h, {z4.d-z7.d}, #29"),
            c(0xc1a0d8a3, Op.UQRSHR, 3, 4, 1, 3, 4, 0, 64, 0, "uqrshr z3.h, {z4.d-z7.d}, #64"),
            c(0x45b32883, Op.SQRSHRN, 2, 2, 1, 3, 4, 0, 13, 0, "sqrshrn z3.h, {z4.s-z5.s}, #13"),
            c(0x45bd3883, Op.UQRSHRN, 2, 2, 1, 3, 4, 0, 3, 0, "uqrshrn z3.h, {z4.s-z5.s}, #3"),
            c(0x45b00883, Op.SQRSHRUN, 2, 2, 1, 3, 4, 0, 16, 0, "sqrshrun z3.h, {z4.s-z5.s}, #16"),
            c(0xc160dc83, Op.SQRSHRN, 2, 4, 1, 3, 4, 0, 32, 0, "sqrshrn z3.b, {z4.s-z7.s}, #32"),
            c(0xc1bddcc3, Op.SQRSHRUN, 3, 4, 1, 3, 4, 0, 35, 0, "sqrshrun z3.h, {z4.d-z7.d}, #35"),
            c(0xc1a9d104, Op.ZIP, 2, 2, 2, 4, 8, 9, 0, 0, "zip {z4.s-z5.s}, z8.s, z9.s"),
            c(0xc129d505, Op.UZP, 4, 2, 2, 4, 8, 9, 0, 0, "uzp {z4.q-z5.q}, z8.q, z9.q"),
            c(0xc1a9c104, Op.FCLAMP, 2, 2, 2, 4, 8, 9, 0, 0, "fclamp {z4.s-z5.s}, z8.s, z9.s"),
            c(0xc129cd04, Op.SCLAMP, 0, 4, 4, 4, 8, 9, 0, 0, "sclamp {z4.b-z7.b}, z8.b, z9.b"),
            c(0xc1e9c505, Op.UCLAMP, 3, 2, 2, 4, 8, 9, 0, 0, "uclamp {z4.d-z5.d}, z8.d, z9.d"),
            c(0xc1a884c4, Op.SEL, 2, 2, 2, 4, 6, 8, 0, 9, "sel {z4.s-z5.s}, pn9, {z6.s-z7.s}, {z8.s-z9.s}"),
            c(0xc12d9d04, Op.SEL, 0, 4, 4, 4, 8, 12, 0, 15, "sel {z4.b-z7.b}, pn15, {z8.b-z11.b}, {z12.b-z15.b}"));

    static Stream<Case> cases() {
        return CASES.stream();
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), ADDRESS);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void decodesTheAssemblerWord(Case c) {
        SmeOp64.Constructive op = assertInstanceOf(SmeOp64.Constructive.class, decode(ALL, c.word()), c.asm());
        assertEquals(c.op(), op.op(), c.asm());
        assertEquals(c.esz(), op.esz(), c.asm());
        assertEquals(c.sources(), op.sources(), c.asm());
        assertEquals(c.destinations(), op.destinations(), c.asm());
        assertEquals(c.zd(), op.zd(), c.asm());
        assertEquals(c.zn(), op.zn(), c.asm());
        assertEquals(c.zm(), op.zm(), c.asm());
        assertEquals(c.shift(), op.shift(), c.asm());
        assertEquals(c.pg(), op.pg(), c.asm());
    }

    @Test
    void everyOperationHasAtLeastOneRow() {
        assertEquals(94, SmeConstructiveRows.ROWS.size());
        for (Op op : Op.values()) {
            assertEquals(true, SmeConstructiveRows.ROWS.stream().anyMatch(r -> r.op() == op), op.name());
        }
    }

    @Test
    void rowsNeverOverlap() {
        List<SmeConstructiveRows.Row> rows = SmeConstructiveRows.ROWS;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                SmeConstructiveRows.Row a = rows.get(i);
                SmeConstructiveRows.Row b = rows.get(j);
                int common = a.mask() & b.mask();
                assertNotEquals(a.value() & common, b.value() & common, "linhas " + i + " e " + j + " se sobrepõem");
            }
        }
    }

    @Test
    void everyRowDecodesWithItsOwnOperationUnderAllFeatures() {
        for (SmeConstructiveRows.Row row : SmeConstructiveRows.ROWS) {
            int word = row.value() | 0x0;
            SmeOp64.Constructive op = assertInstanceOf(SmeOp64.Constructive.class, decode(ALL, word));
            assertEquals(row.op(), op.op());
        }
    }

    @Test
    void withoutSme2NothingDecodes() {
        for (Case c : CASES) {
            assertThrows(RuntimeException.class, () -> decode(Aarch64Architecture.ARMV9_2_A, c.word()), c.asm());
        }
    }

    @Test
    void featureSpecificGates() {
        // FCVT_w/FCVTL: FEAT_SME_F16F16; FP8: FEAT_FP8; FCLAMP bfloat16: FEAT_SVE_B16B16.
        assertThrows(RuntimeException.class, () -> decode(SME2, 0xc1a0e0e4));
        assertThrows(RuntimeException.class, () -> decode(SME2, 0xc126e0e4));
        assertThrows(RuntimeException.class, () -> decode(SME2, 0xc124e083));
        int fclampBf16 = 0xc1a9c104 & ~(0b11 << 22) | (0b00 << 22);
        assertThrows(RuntimeException.class, () -> decode(SME2, fclampBf16));
        assertInstanceOf(SmeOp64.Constructive.class, decode(ALL, fclampBf16));
        // as três `*RSHRN_sh` do espaço SVE: SME2 OU SVE2p1
        int sqrshrn = 0x45b32883;
        assertInstanceOf(SmeOp64.Constructive.class, decode(SME2, sqrshrn));
        Aarch64Architecture sve21 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-sve21",
                Aarch64Feature.SVE2_1);
        assertInstanceOf(SmeOp64.Constructive.class, decode(sve21, sqrshrn));
        assertThrows(RuntimeException.class, () -> decode(Aarch64Architecture.ARMV9_2_A, sqrshrn));
    }

    @Test
    void addSubArrayAccumulatorsDecodeWithTheI16I64Gate() {
        // add za.s[w8, 1, vgx2], {z4.s-z5.s} / sub za.d[w9, 7, vgx4], {z4.d-z7.d}
        SmeOp64.ArrayMultiVector add = assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(SME2, 0xc1a01c91));
        assertEquals(SmeOp64.ArrayMultiVector.Op.ADD_AAZ_S, add.op());
        assertEquals(2, add.count());
        assertEquals(8, add.registerIndex());
        assertEquals(1, add.off());
        assertEquals(4, add.zm());
        assertThrows(RuntimeException.class, () -> decode(SME2, 0xc1e13c9f));
        SmeOp64.ArrayMultiVector sub = assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(ALL, 0xc1e13c9f));
        assertEquals(SmeOp64.ArrayMultiVector.Op.SUB_AAZ_D, sub.op());
        assertEquals(4, sub.count());
        assertEquals(9, sub.registerIndex());
        assertEquals(7, sub.off());
    }
}
