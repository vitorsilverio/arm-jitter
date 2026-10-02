package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `ZERO` multi-vetor de `ZA`, `MOVT` e `LUTI2`/`LUTI4` — B18.6. **Toda palavra abaixo foi conferida contra
/// `aarch64-none-elf-as -march=armv9.4-a+sme2p1+sme-lutv2` (devkitA64, binutils 2.46)**: o assembly de cada linha foi
/// escrito primeiro e a palavra extraída com `objdump` — os campos esperados vêm da INTENÇÃO do assembly, nunca do
/// decoder. O comentário de cada caso é o assembly.
class Aarch64SmeZt0FamilyDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-zt0-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2P1 = Aarch64Architecture.extending(SME2, "teste-zt0-dec-SME2p1",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);
    private static final Aarch64Architecture LUTV2 = Aarch64Architecture.extending(SME2, "teste-zt0-dec-LUTv2",
            Aarch64Feature.SME_LUTV2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SME2P1, "teste-zt0-dec-ALL",
            Aarch64Feature.SME_LUTV2);
    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    private static int word(String hex) {
        return (int) Long.decode(hex).longValue();
    }

    /// `palavra, ngrp, nvec, rv, off`. `off` é o ESCALADO (o do assembly).
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC00C8000, 1, 2, 8, 0",    // zero za.d[w8, 0:1]
            "0xC00D2000, 2, 2, 9, 0",    // zero za.d[w9, 0:1, vgx2]
            "0xC00E4000, 4, 1, 10, 0",   // zero za.d[w10, 0, vgx4]
            "0xC00C6001, 2, 1, 11, 1",   // zero za.d[w11, 1, vgx2]
            "0xC00F8000, 4, 4, 8, 0",    // zero za.d[w8, 0:3, vgx4]
            "0xC00E8001, 1, 4, 8, 4",    // zero za.d[w8, 4:7]
            "0xC00E8000, 1, 4, 8, 0",    // zero za.d[w8, 0:3]
            "0xC00F0000, 2, 4, 8, 0",    // zero za.d[w8, 0:3, vgx2]
            "0xC00D8000, 4, 2, 8, 0",    // zero za.d[w8, 0:1, vgx4]
            "0xC00C8007, 1, 2, 8, 14",   // zero za.d[w8, 14:15]   (%off3_x2)
            "0xC00D0001, 2, 2, 8, 2",    // zero za.d[w8, 2:3, vgx2]   (%off2_x2)
            "0xC00DA001, 4, 2, 9, 2",    // zero za.d[w9, 2:3, vgx4]
            "0xC00E8003, 1, 4, 8, 12",   // zero za.d[w8, 12:15]   (%off2_x4)
            "0xC00F0001, 2, 4, 8, 4",    // zero za.d[w8, 4:7, vgx2]   (%off1_x4)
            "0xC00FC001, 4, 4, 10, 4",   // zero za.d[w10, 4:7, vgx4]
            "0xC00C0007, 2, 1, 8, 7",    // zero za.d[w8, 7, vgx2]   (off:3 sem escala)
            "0xC00E6007, 4, 1, 11, 7",   // zero za.d[w11, 7, vgx4]
    })
    void zeroArrayDecodesEveryNgrpNvecPairWithItsOwnOffsetScale(String hex, int ngrp, int nvec, int rv, int off) {
        SmeOp64.ZeroArray zero = assertInstanceOf(SmeOp64.ZeroArray.class, decode(SME2P1, word(hex)));
        assertEquals(ngrp, zero.ngrp());
        assertEquals(nvec, zero.nvec());
        assertEquals(rv, zero.registerIndex());
        assertEquals(off, zero.off());
    }

    /// `palavra, forma, rt, off` (`off` = índice da palavra de 64 bits para `ZT_TO_X`/`X_TO_ZT`; `mul vl` na vetorial).
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC04C03E3, ZT_TO_X, 3, 0",         // movt x3, zt0[0]
            "0xC04C73E3, ZT_TO_X, 3, 7",         // movt x3, zt0[56]
            "0xC04C13FE, ZT_TO_X, 30, 1",        // movt x30, zt0[8]
            "0xC04C23FF, ZT_TO_X, 31, 2",        // movt xzr, zt0[16]
            "0xC04E53E4, X_TO_ZT, 4, 5",         // movt zt0[40], x4
            "0xC04E33FF, X_TO_ZT, 31, 3",        // movt zt0[24], xzr
    })
    void movtWithGeneralRegisterNeedsOnlySme2(String hex, SmeOp64.Movt.Form form, int rt, int off) {
        SmeOp64.Movt movt = assertInstanceOf(SmeOp64.Movt.class, decode(SME2, word(hex)));
        assertEquals(form, movt.form());
        assertEquals(rt, movt.rt());
        assertEquals(off, movt.off());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC04F03E7, 7, 0",     // movt zt0, z7
            "0xC04F13E7, 7, 1",     // movt zt0[1, mul vl], z7
            "0xC04F33FF, 31, 3",    // movt zt0[3, mul vl], z31
    })
    void movtFromVectorNeedsLutv2(String hex, int rt, int off) {
        SmeOp64.Movt movt = assertInstanceOf(SmeOp64.Movt.class, decode(LUTV2, word(hex)));
        assertEquals(SmeOp64.Movt.Form.VECTOR_TO_ZT, movt.form());
        assertEquals(rt, movt.rt());
        assertEquals(off, movt.off());
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2P1, word(hex)));
    }

    /// `palavra, fourBit, esz, count, strided, zd, zn, idx` — `LUTI4_*_4b` lê um PAR (`zn`, `zn+1`) e tem `idx = 0`.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC0CCC041, false, 0, 1, false, 1, 2, 3",   // luti2 z1.b, zt0, z2[3]
            "0xC0CDD041, false, 1, 1, false, 1, 2, 7",   // luti2 z1.h, zt0, z2[7]
            "0xC0CFE041, false, 2, 1, false, 1, 2, 15",   // luti2 z1.s, zt0, z2[15]
            "0xC08CC082, false, 0, 2, false, 2, 4, 1",   // luti2 {z2.b-z3.b}, zt0, z4[1]
            "0xC08ED082, false, 1, 2, false, 2, 4, 5",   // luti2 {z2.h-z3.h}, zt0, z4[5]
            "0xC08FE082, false, 2, 2, false, 2, 4, 7",   // luti2 {z2.s-z3.s}, zt0, z4[7]
            "0xC08E8084, false, 0, 4, false, 4, 4, 2",   // luti2 {z4.b-z7.b}, zt0, z4[2]
            "0xC08F9084, false, 1, 4, false, 4, 4, 3",   // luti2 {z4.h-z7.h}, zt0, z4[3]
            "0xC08DA084, false, 2, 4, false, 4, 4, 1",   // luti2 {z4.s-z7.s}, zt0, z4[1]
            "0xC09CC082, false, 0, 2, true, 2, 4, 1",   // luti2 {z2.b, z10.b}, zt0, z4[1]
            "0xC09CD082, false, 1, 2, true, 2, 4, 1",   // luti2 {z2.h, z10.h}, zt0, z4[1]
            "0xC09D8081, false, 0, 4, true, 1, 4, 1",   // luti2 {z1.b, z5.b, z9.b, z13.b}, zt0, z4[1]
            "0xC09D9081, false, 1, 4, true, 1, 4, 1",   // luti2 {z1.h, z5.h, z9.h, z13.h}, zt0, z4[1]
            "0xC0CAC041, true, 0, 1, false, 1, 2, 3",   // luti4 z1.b, zt0, z2[3]
            "0xC0CBD041, true, 1, 1, false, 1, 2, 7",   // luti4 z1.h, zt0, z2[7]
            "0xC0CA6041, true, 2, 1, false, 1, 2, 1",   // luti4 z1.s, zt0, z2[1]
            "0xC08AC082, true, 0, 2, false, 2, 4, 1",   // luti4 {z2.b-z3.b}, zt0, z4[1]
            "0xC08BD082, true, 1, 2, false, 2, 4, 3",   // luti4 {z2.h-z3.h}, zt0, z4[3]
            "0xC08B6082, true, 2, 2, false, 2, 4, 2",   // luti4 {z2.s-z3.s}, zt0, z4[2]
            "0xC08B9084, true, 1, 4, false, 4, 4, 1",   // luti4 {z4.h-z7.h}, zt0, z4[1]
            "0xC08BA084, true, 2, 4, false, 4, 4, 1",   // luti4 {z4.s-z7.s}, zt0, z4[1]
            "0xC08B0104, true, 0, 4, false, 4, 8, 0",   // luti4 {z4.b-z7.b}, zt0, {z8-z9}
            "0xC09AC082, true, 0, 2, true, 2, 4, 1",   // luti4 {z2.b, z10.b}, zt0, z4[1]
            "0xC09AD082, true, 1, 2, true, 2, 4, 1",   // luti4 {z2.h, z10.h}, zt0, z4[1]
            "0xC09A9081, true, 1, 4, true, 1, 4, 0",   // luti4 {z1.h, z5.h, z9.h, z13.h}, zt0, z4[0]
            "0xC09B0101, true, 0, 4, true, 1, 8, 0",   // luti4 {z1.b, z5.b, z9.b, z13.b}, zt0, {z8-z9}
            "0xC0CFE3FF, false, 2, 1, false, 31, 31, 15",   // luti2 z31.s, zt0, z31[15]
            "0xC08FD3FE, false, 1, 2, false, 30, 31, 7",   // luti2 {z30.h-z31.h}, zt0, z31[7]
            "0xC08FA3FC, false, 2, 4, false, 28, 31, 3",   // luti2 {z28.s-z31.s}, zt0, z31[3]
            "0xC08BE3FE, true, 2, 2, false, 30, 31, 3",   // luti4 {z30.s-z31.s}, zt0, z31[3]
            "0xC08B93FC, true, 1, 4, false, 28, 31, 1",   // luti4 {z28.h-z31.h}, zt0, z31[1]
            "0xC08B03DC, true, 0, 4, false, 28, 30, 0",   // luti4 {z28.b-z31.b}, zt0, {z30-z31}
            "0xC09C4000, false, 0, 2, true, 0, 0, 0",   // luti2 {z0.b, z8.b}, zt0, z0[0]
            "0xC09C50B7, false, 1, 2, true, 23, 5, 0",   // luti2 {z23.h, z31.h}, zt0, z5[0]
            "0xC09C8090, false, 0, 4, true, 16, 4, 0",   // luti2 {z16.b, z20.b, z24.b, z28.b}, zt0, z4[0]
            "0xC09C8083, false, 0, 4, true, 3, 4, 0",   // luti2 {z3.b, z7.b, z11.b, z15.b}, zt0, z4[0]
            "0xC09B40D0, true, 0, 2, true, 16, 6, 2",   // luti4 {z16.b, z24.b}, zt0, z6[2]
            "0xC09B90D3, true, 1, 4, true, 19, 6, 1",   // luti4 {z19.h, z23.h, z27.h, z31.h}, zt0, z6[1]
            "0xC09B0051, true, 0, 4, true, 17, 2, 0",   // luti4 {z17.b, z21.b, z25.b, z29.b}, zt0, {z2-z3}
    })
    void everyLutVariantDecodesItsOwnFields(String hex, boolean four, int esz, int count, boolean strided, int zd,
            int zn, int idx) {
        SmeOp64.Lut lut = assertInstanceOf(SmeOp64.Lut.class, decode(ALL, word(hex)), hex);
        assertEquals(four, lut.fourBit(), hex + " fourBit");
        assertEquals(esz, lut.esz(), hex + " esz");
        assertEquals(count, lut.count(), hex + " count");
        assertEquals(strided, lut.strided(), hex + " strided");
        assertEquals(zd, lut.zd(), hex + " zd");
        assertEquals(zn, lut.zn(), hex + " zn");
        assertEquals(idx, lut.index(), hex + " idx");
    }

    private static void assertUndefined(Aarch64Architecture architecture, int word, String message) {
        assertThrows(UnsupportedOperationException.class, () -> decode(architecture, word), message);
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture smeOnly = Aarch64Architecture.ARMV9_2_A;
        for (String hex : new String[] {"0xC00C8000", "0xC04C03E3", "0xC04E53E4", "0xC04F03E7", "0xC0CCC041",
                "0xC08CC082", "0xC09C4000", "0xC08B0104"}) {
            assertUndefined(smeOnly, word(hex), hex);
        }
    }

    @Test
    void zeroArrayAndStridedFormsNeedSme2p1AndTheLutv2OnesNeedLutv2() {
        assertUndefined(SME2, word("0xC00C8000"), "ZERO_za sem SME2p1");
        assertUndefined(SME2, word("0xC09C4000"), "LUTI2 strided sem SME2p1");
        assertUndefined(SME2, word("0xC09A9081"), "LUTI4 strided sem SME2p1");
        assertInstanceOf(SmeOp64.Lut.class, decode(SME2P1, word("0xC09C4000")));
        assertUndefined(SME2P1, word("0xC08B0104"), "LUTI4_c_4b sem LUTv2");
        assertUndefined(SME2, word("0xC08B0104"), "LUTI4_c_4b sem LUTv2");
        assertInstanceOf(SmeOp64.Lut.class, decode(LUTV2, word("0xC08B0104")));
        assertUndefined(LUTV2, word("0xC09B0101"), "LUTI4_s_4b com LUTv2 mas sem SME2p1");
        assertUndefined(SME2P1, word("0xC09B0101"), "LUTI4_s_4b com SME2p1 mas sem LUTv2");
        assertInstanceOf(SmeOp64.Lut.class, decode(ALL, word("0xC09B0101")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0xC09C4008",   // LUTI2_s_2b com zd = 8: bit 3 ligado → desalinhado ao espaçamento 8
            "0xC09C400A",   // zd = 10
            "0xC09C8004",   // LUTI2_s_4b com zd = 4: bit 2 ligado → desalinhado ao espaçamento 4
            "0xC09C800C",   // zd = 12
            "0xC09A400C",   // LUTI4_s_2b com zd = 12 (bit 3)
            "0xC09A9008",   // LUTI4_s_4h com zd = 8
    })
    void stridedWithMisalignedDestinationIsRefused(String hex) {
        assertUndefined(ALL, word(hex), hex);
    }

    @Test
    void theLookupTableIsNotACartesianProduct() {
        assertUndefined(ALL, word("0xC09CA000"), "LUTI2_s_4s não existe");
        // `LUTI4_c_4b`: `zn` é par (bit 5 fixo em 0) e `zd` múltiplo de 4 (bits 1:0 fixos em 0).
        assertUndefined(ALL, word("0xC08B0104") | 0x20, "zn ímpar");
        assertUndefined(ALL, word("0xC08B0104") | 0x1, "zd não múltiplo de 4");
        // `LUTI2_c_2*`: `zd` par (bit 0 fixo em 0).
        assertUndefined(ALL, word("0xC08CC082") | 0x1, "zd ímpar em 2 vetores");
        assertUndefined(ALL, word("0xC08E8084") | 0x1, "zd não múltiplo de 4 em 4 vetores");
    }

    @Test
    void zeroArrayNeighboursStayUnimplemented() {
        assertUndefined(ALL, word("0xC00C8000") | 0x10, "bit fixo em 0 do campo off");
        assertUndefined(ALL, word("0xC00D0000") | 0x4, "ZERO_za 2x2 exige off:2 (bit 2 = 0)");
        assertUndefined(ALL, word("0xC00F0000") | 0x2, "ZERO_za 2x4 exige off:1 (bits 2:1 = 0)");
    }
}
