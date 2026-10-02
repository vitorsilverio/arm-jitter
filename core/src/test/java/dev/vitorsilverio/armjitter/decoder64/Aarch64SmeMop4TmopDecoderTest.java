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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `MOP4` (quarto de tile) e `TMOP` (esparso) — B18.5b. **Toda palavra abaixo foi conferida contra
/// `aarch64-none-elf-as -march=armv9.4-a+sme2+sme-mop4+sme-tmop+sme-i16i64+sme-f64f64+sme-f16f16+sme-b16b16+sme-f8f16+sme-f8f32`
/// (devkitA64, binutils 2.46)**: o assembly de cada linha foi escrito primeiro (com tile/`Zn`/`Zm`/`Zk`/`idx`/`n`/`m`
/// escolhidos) e a palavra extraída com `objdump` — os campos esperados vêm da INTENÇÃO do assembly, nunca do
/// decoder. O `zk` esperado é um registrador `Z20`-`Z23`/`Z28`-`Z31` (`expand_tmop_zk`), nunca os 3 bits crus.
class Aarch64SmeMop4TmopDecoderTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-mop4-dec-ALL", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_MOP4,
            Aarch64Feature.SME_TMOP, Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
            Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
    private static final Aarch64Decoder ALL_DECODER = new Aarch64Decoder(ALL);
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);

    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return decoder.decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    private static int word(String hex) {
        return (int) Long.decode(hex).longValue();
    }

    /// `palavra, op, tile, zn, zm, subtract, n, m`.
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "0x81040049, FMOP4_HH, 1, 2, 20, false, false, false",   // fmop4a za1.h, z2.h, z20.h
            "0x81140249, FMOP4_HH, 1, 2, 20, false, true, true",   // fmop4a za1.h, {z2.h-z3.h}, {z20.h-z21.h}
            "0x81040259, FMOP4_HH, 1, 2, 20, true, true, false",   // fmop4s za1.h, {z2.h-z3.h}, z20.h
            "0x81140059, FMOP4_HH, 1, 2, 20, true, false, true",   // fmop4s za1.h, z2.h, {z20.h-z21.h}
            "0x812A0088, BFMOP4_HH, 0, 4, 26, false, false, false",   // bfmop4a za0.h, z4.h, z26.h
            "0x813A0288, BFMOP4_HH, 0, 4, 26, false, true, true",   // bfmop4a za0.h, {z4.h-z5.h}, {z26.h-z27.h}
            "0x812A0298, BFMOP4_HH, 0, 4, 26, true, true, false",   // bfmop4s za0.h, {z4.h-z5.h}, z26.h
            "0x813A0098, BFMOP4_HH, 0, 4, 26, true, false, true",   // bfmop4s za0.h, z4.h, {z26.h-z27.h}
            "0x800000C3, FMOP4_SS, 3, 6, 16, false, false, false",   // fmop4a za3.s, z6.s, z16.s
            "0x801002C3, FMOP4_SS, 3, 6, 16, false, true, true",   // fmop4a za3.s, {z6.s-z7.s}, {z16.s-z17.s}
            "0x800002D3, FMOP4_SS, 3, 6, 16, true, true, false",   // fmop4s za3.s, {z6.s-z7.s}, z16.s
            "0x801000D3, FMOP4_SS, 3, 6, 16, true, false, true",   // fmop4s za3.s, z6.s, {z16.s-z17.s}
            "0x80C6010A, FMOP4_DD, 2, 8, 22, false, false, false",   // fmop4a za2.d, z8.d, z22.d
            "0x80D6030A, FMOP4_DD, 2, 8, 22, false, true, true",   // fmop4a za2.d, {z8.d-z9.d}, {z22.d-z23.d}
            "0x80C6031A, FMOP4_DD, 2, 8, 22, true, true, false",   // fmop4s za2.d, {z8.d-z9.d}, z22.d
            "0x80D6011A, FMOP4_DD, 2, 8, 22, true, false, true",   // fmop4s za2.d, z8.d, {z22.d-z23.d}
            "0x810C0141, BFMOP4_SH, 1, 10, 28, false, false, false",   // bfmop4a za1.s, z10.h, z28.h
            "0x811C0341, BFMOP4_SH, 1, 10, 28, false, true, true",   // bfmop4a za1.s, {z10.h-z11.h}, {z28.h-z29.h}
            "0x810C0351, BFMOP4_SH, 1, 10, 28, true, true, false",   // bfmop4s za1.s, {z10.h-z11.h}, z28.h
            "0x811C0151, BFMOP4_SH, 1, 10, 28, true, false, true",   // bfmop4s za1.s, z10.h, {z28.h-z29.h}
            "0x81220180, FMOP4_SH, 0, 12, 18, false, false, false",   // fmop4a za0.s, z12.h, z18.h
            "0x81320380, FMOP4_SH, 0, 12, 18, false, true, true",   // fmop4a za0.s, {z12.h-z13.h}, {z18.h-z19.h}
            "0x81220390, FMOP4_SH, 0, 12, 18, true, true, false",   // fmop4s za0.s, {z12.h-z13.h}, z18.h
            "0x81320190, FMOP4_SH, 0, 12, 18, true, false, true",   // fmop4s za0.s, z12.h, {z18.h-z19.h}
            "0x802801C3, FMOP4A_SB, 3, 14, 24, false, false, false",   // fmop4a za3.s, z14.b, z24.b
            "0x803803C3, FMOP4A_SB, 3, 14, 24, false, true, true",   // fmop4a za3.s, {z14.b-z15.b}, {z24.b-z25.b}
            "0x802E0008, FMOP4A_HB, 0, 0, 30, false, false, false",   // fmop4a za0.h, z0.b, z30.b
            "0x803E0208, FMOP4A_HB, 0, 0, 30, false, true, true",   // fmop4a za0.h, {z0.b-z1.b}, {z30.b-z31.b}
            "0x80048049, SMOP4_SH, 1, 2, 20, false, false, false",   // smop4a za1.s, z2.h, z20.h
            "0x80148249, SMOP4_SH, 1, 2, 20, false, true, true",   // smop4a za1.s, {z2.h-z3.h}, {z20.h-z21.h}
            "0x80048259, SMOP4_SH, 1, 2, 20, true, true, false",   // smop4s za1.s, {z2.h-z3.h}, z20.h
            "0x80148059, SMOP4_SH, 1, 2, 20, true, false, true",   // smop4s za1.s, z2.h, {z20.h-z21.h}
            "0x810A8088, UMOP4_SH, 0, 4, 26, false, false, false",   // umop4a za0.s, z4.h, z26.h
            "0x811A8288, UMOP4_SH, 0, 4, 26, false, true, true",   // umop4a za0.s, {z4.h-z5.h}, {z26.h-z27.h}
            "0x810A8298, UMOP4_SH, 0, 4, 26, true, true, false",   // umop4s za0.s, {z4.h-z5.h}, z26.h
            "0x811A8098, UMOP4_SH, 0, 4, 26, true, false, true",   // umop4s za0.s, z4.h, {z26.h-z27.h}
            "0x800080C3, SMOP4_SB, 3, 6, 16, false, false, false",   // smop4a za3.s, z6.b, z16.b
            "0x801082C3, SMOP4_SB, 3, 6, 16, false, true, true",   // smop4a za3.s, {z6.b-z7.b}, {z16.b-z17.b}
            "0x800082D3, SMOP4_SB, 3, 6, 16, true, true, false",   // smop4s za3.s, {z6.b-z7.b}, z16.b
            "0x801080D3, SMOP4_SB, 3, 6, 16, true, false, true",   // smop4s za3.s, z6.b, {z16.b-z17.b}
            "0xA0C6010A, SMOP4_DH, 2, 8, 22, false, false, false",   // smop4a za2.d, z8.h, z22.h
            "0xA0D6030A, SMOP4_DH, 2, 8, 22, false, true, true",   // smop4a za2.d, {z8.h-z9.h}, {z22.h-z23.h}
            "0xA0C6031A, SMOP4_DH, 2, 8, 22, true, true, false",   // smop4s za2.d, {z8.h-z9.h}, z22.h
            "0xA0D6011A, SMOP4_DH, 2, 8, 22, true, false, true",   // smop4s za2.d, z8.h, {z22.h-z23.h}
            "0x802C8141, SUMOP4_SB, 1, 10, 28, false, false, false",   // sumop4a za1.s, z10.b, z28.b
            "0x803C8341, SUMOP4_SB, 1, 10, 28, false, true, true",   // sumop4a za1.s, {z10.b-z11.b}, {z28.b-z29.b}
            "0x802C8351, SUMOP4_SB, 1, 10, 28, true, true, false",   // sumop4s za1.s, {z10.b-z11.b}, z28.b
            "0x803C8151, SUMOP4_SB, 1, 10, 28, true, false, true",   // sumop4s za1.s, z10.b, {z28.b-z29.b}
            "0xA0E20188, SUMOP4_DH, 0, 12, 18, false, false, false",   // sumop4a za0.d, z12.h, z18.h
            "0xA0F20388, SUMOP4_DH, 0, 12, 18, false, true, true",   // sumop4a za0.d, {z12.h-z13.h}, {z18.h-z19.h}
            "0xA0E20398, SUMOP4_DH, 0, 12, 18, true, true, false",   // sumop4s za0.d, {z12.h-z13.h}, z18.h
            "0xA0F20198, SUMOP4_DH, 0, 12, 18, true, false, true",   // sumop4s za0.d, z12.h, {z18.h-z19.h}
            "0x812881C3, UMOP4_SB, 3, 14, 24, false, false, false",   // umop4a za3.s, z14.b, z24.b
            "0x813883C3, UMOP4_SB, 3, 14, 24, false, true, true",   // umop4a za3.s, {z14.b-z15.b}, {z24.b-z25.b}
            "0x812883D3, UMOP4_SB, 3, 14, 24, true, true, false",   // umop4s za3.s, {z14.b-z15.b}, z24.b
            "0x813881D3, UMOP4_SB, 3, 14, 24, true, false, true",   // umop4s za3.s, z14.b, {z24.b-z25.b}
            "0xA1EE000E, UMOP4_DH, 6, 0, 30, false, false, false",   // umop4a za6.d, z0.h, z30.h
            "0xA1FE020E, UMOP4_DH, 6, 0, 30, false, true, true",   // umop4a za6.d, {z0.h-z1.h}, {z30.h-z31.h}
            "0xA1EE021E, UMOP4_DH, 6, 0, 30, true, true, false",   // umop4s za6.d, {z0.h-z1.h}, z30.h
            "0xA1FE001E, UMOP4_DH, 6, 0, 30, true, false, true",   // umop4s za6.d, z0.h, {z30.h-z31.h}
            "0x81048041, USMOP4_SB, 1, 2, 20, false, false, false",   // usmop4a za1.s, z2.b, z20.b
            "0x81148241, USMOP4_SB, 1, 2, 20, false, true, true",   // usmop4a za1.s, {z2.b-z3.b}, {z20.b-z21.b}
            "0x81048251, USMOP4_SB, 1, 2, 20, true, true, false",   // usmop4s za1.s, {z2.b-z3.b}, z20.b
            "0x81148051, USMOP4_SB, 1, 2, 20, true, false, true",   // usmop4s za1.s, z2.b, {z20.b-z21.b}
            "0xA1CA008C, USMOP4_DH, 4, 4, 26, false, false, false",   // usmop4a za4.d, z4.h, z26.h
            "0xA1DA028C, USMOP4_DH, 4, 4, 26, false, true, true",   // usmop4a za4.d, {z4.h-z5.h}, {z26.h-z27.h}
            "0xA1CA029C, USMOP4_DH, 4, 4, 26, true, true, false",   // usmop4s za4.d, {z4.h-z5.h}, z26.h
            "0xA1DA009C, USMOP4_DH, 4, 4, 26, true, false, true",   // usmop4s za4.d, z4.h, {z26.h-z27.h}
    })
    void decodesEveryMop4EncodingWithItsFields(String word, SmeOp64.Mop4.Op expected, int tile, int zn, int zm,
            boolean subtract, boolean n, boolean m) {
        SmeOp64.Mop4 op = assertInstanceOf(SmeOp64.Mop4.class, decode(ALL_DECODER, word(word)));
        assertEquals(expected, op.op());
        assertEquals(tile, op.tile());
        assertEquals(zn, op.zn());
        assertEquals(zm, op.zm());
        assertEquals(subtract, op.subtract());
        assertEquals(n, op.nPair());
        assertEquals(m, op.mPair());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    /// `palavra, op, tile, zn, zm, zk, idx`.
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "0x81630049, BFTMOPA_HH, 1, 2, 3, 20, 0",   // bftmopa za1.h, {z2.h-z3.h}, z3.h, z20[0]
            "0x81691169, BFTMOPA_HH, 1, 10, 9, 28, 2",   // bftmopa za1.h, {z10.h-z11.h}, z9.h, z28[2]
            "0x81480498, FTMOPA_HH, 0, 4, 8, 21, 1",   // ftmopa za0.h, {z4.h-z5.h}, z8.h, z21[1]
            "0x815015B8, FTMOPA_HH, 0, 12, 16, 29, 3",   // ftmopa za0.h, {z12.h-z13.h}, z16.h, z29[3]
            "0x804D08E3, FTMOPA_SS, 3, 6, 13, 22, 2",   // ftmopa za3.s, {z6.s-z7.s}, z13.s, z22[2]
            "0x805719C3, FTMOPA_SS, 3, 14, 23, 30, 0",   // ftmopa za3.s, {z14.s-z15.s}, z23.s, z30[0]
            "0x81520D32, BFTMOPA_SH, 2, 8, 18, 23, 3",   // bftmopa za2.s, {z8.h-z9.h}, z18.h, z23[3]
            "0x815E1E12, BFTMOPA_SH, 2, 16, 30, 31, 1",   // bftmopa za2.s, {z16.h-z17.h}, z30.h, z31[1]
            "0x81770141, FTMOPA_SH, 1, 10, 23, 20, 0",   // ftmopa za1.s, {z10.h-z11.h}, z23.h, z20[0]
            "0x81651261, FTMOPA_SH, 1, 18, 5, 28, 2",   // ftmopa za1.s, {z18.h-z19.h}, z5.h, z28[2]
            "0x807C0598, FTMOPA_HB, 0, 12, 28, 21, 1",   // ftmopa za0.h, {z12.b-z13.b}, z28.b, z21[1]
            "0x806C16B8, FTMOPA_HB, 0, 20, 12, 29, 3",   // ftmopa za0.h, {z20.b-z21.b}, z12.b, z29[3]
            "0x806109E3, FTMOPA_SB, 3, 14, 1, 22, 2",   // ftmopa za3.s, {z14.b-z15.b}, z1.b, z22[2]
            "0x80731AC3, FTMOPA_SB, 3, 22, 19, 30, 0",   // ftmopa za3.s, {z22.b-z23.b}, z19.b, z30[0]
            "0x80468C3A, STMOPA_SH, 2, 0, 6, 23, 3",   // stmopa za2.s, {z0.h-z1.h}, z6.h, z23[3]
            "0x805A9F1A, STMOPA_SH, 2, 24, 26, 31, 1",   // stmopa za2.s, {z24.h-z25.h}, z26.h, z31[1]
            "0x814B8049, UTMOPA_SH, 1, 2, 11, 20, 0",   // utmopa za1.s, {z2.h-z3.h}, z11.h, z20[0]
            "0x81419369, UTMOPA_SH, 1, 26, 1, 28, 2",   // utmopa za1.s, {z26.h-z27.h}, z1.h, z28[2]
            "0x80508490, STMOPA_SB, 0, 4, 16, 21, 1",   // stmopa za0.s, {z4.b-z5.b}, z16.b, z21[1]
            "0x804897B0, STMOPA_SB, 0, 28, 8, 29, 3",   // stmopa za0.s, {z28.b-z29.b}, z8.b, z29[3]
            "0x807588E3, SUTMOPA_SB, 3, 6, 21, 22, 2",   // sutmopa za3.s, {z6.b-z7.b}, z21.b, z22[2]
            "0x806F9BC3, SUTMOPA_SB, 3, 30, 15, 30, 0",   // sutmopa za3.s, {z30.b-z31.b}, z15.b, z30[0]
            "0x815A8D32, USTMOPA_SB, 2, 8, 26, 23, 3",   // ustmopa za2.s, {z8.b-z9.b}, z26.b, z23[3]
            "0x81569C12, USTMOPA_SB, 2, 0, 22, 31, 1",   // ustmopa za2.s, {z0.b-z1.b}, z22.b, z31[1]
            "0x817F8141, UTMOPA_SB, 1, 10, 31, 20, 0",   // utmopa za1.s, {z10.b-z11.b}, z31.b, z20[0]
            "0x817D9061, UTMOPA_SB, 1, 2, 29, 28, 2",   // utmopa za1.s, {z2.b-z3.b}, z29.b, z28[2]
    })
    void decodesEveryTmopEncodingWithItsFields(String word, SmeOp64.Tmop.Op expected, int tile, int zn, int zm,
            int zk, int idx) {
        SmeOp64.Tmop op = assertInstanceOf(SmeOp64.Tmop.class, decode(ALL_DECODER, word(word)));
        assertEquals(expected, op.op());
        assertEquals(tile, op.tile());
        assertEquals(zn, op.zn());
        assertEquals(zm, op.zm());
        assertEquals(zk, op.zk());
        assertEquals(idx, op.index());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    /// Uma palavra de cada op que exige um formato fatiado além de `MOP4`/`TMOP`.
    private static final Object[][] FORMAT_GATED = {
            {"0x81040049", Aarch64Feature.SME_F16F16},   // FMOP4_HH
            {"0x812A0088", Aarch64Feature.SME_B16B16},   // BFMOP4_HH
            {"0x80C6010A", Aarch64Feature.SME_F64F64},   // FMOP4_DD
            {"0x802801C3", Aarch64Feature.SME_F8F32},   // FMOP4A_SB
            {"0x802E0008", Aarch64Feature.SME_F8F16},   // FMOP4A_HB
            {"0xA0C6010A", Aarch64Feature.SME_I16I64},   // SMOP4_DH
            {"0xA0E20188", Aarch64Feature.SME_I16I64},   // SUMOP4_DH
            {"0xA1EE000E", Aarch64Feature.SME_I16I64},   // UMOP4_DH
            {"0xA1CA008C", Aarch64Feature.SME_I16I64},   // USMOP4_DH
            {"0x81630049", Aarch64Feature.SME_B16B16},   // BFTMOPA_HH
            {"0x81480498", Aarch64Feature.SME_F16F16},   // FTMOPA_HH
            {"0x807C0598", Aarch64Feature.SME_F8F16},   // FTMOPA_HB
            {"0x806109E3", Aarch64Feature.SME_F8F32},   // FTMOPA_SB
    };

    /// Uma palavra de cada op que só exige `MOP4`/`TMOP` (e `FEAT_SME`).
    private static final String[] BASE_ONLY = {
            "0x800000C3",   // FMOP4_SS
            "0x810C0141",   // BFMOP4_SH
            "0x81220180",   // FMOP4_SH
            "0x80048049",   // SMOP4_SH
            "0x810A8088",   // UMOP4_SH
            "0x800080C3",   // SMOP4_SB
            "0x802C8141",   // SUMOP4_SB
            "0x812881C3",   // UMOP4_SB
            "0x81048041",   // USMOP4_SB
            "0x804D08E3",   // FTMOPA_SS
            "0x81520D32",   // BFTMOPA_SH
            "0x81770141",   // FTMOPA_SH
            "0x80468C3A",   // STMOPA_SH
            "0x814B8049",   // UTMOPA_SH
            "0x80508490",   // STMOPA_SB
            "0x807588E3",   // SUTMOPA_SB
            "0x815A8D32",   // USTMOPA_SB
            "0x817F8141",   // UTMOPA_SB
    };

    private static Aarch64Architecture architectureWith(String name, Aarch64Feature... features) {
        return Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-mop4-dec-" + name, features);
    }

    private static boolean isTmop(int word) {
        return decode(ALL_DECODER, word) instanceof SmeOp64.Tmop;
    }

    @Test
    void everyFormatSlicedFormNeedsItsBaseFeatureAndItsOwnFormatFeature() {
        for (Object[] entry : FORMAT_GATED) {
            int word = word((String) entry[0]);
            Aarch64Feature format = (Aarch64Feature) entry[1];
            Aarch64Feature base = isTmop(word) ? Aarch64Feature.SME_TMOP : Aarch64Feature.SME_MOP4;
            Aarch64Decoder both = new Aarch64Decoder(architectureWith("both-" + format + base, base, format));
            assertInstanceOf(decode(ALL_DECODER, word).getClass(), decode(both, word), (String) entry[0]);
            Aarch64Decoder onlyBase = new Aarch64Decoder(architectureWith("base-" + base, base));
            assertThrows(UnsupportedOperationException.class, () -> decode(onlyBase, word), "so " + base);
            Aarch64Decoder onlyFormat = new Aarch64Decoder(architectureWith("fmt-" + format, format));
            assertThrows(UnsupportedOperationException.class, () -> decode(onlyFormat, word), "so " + format);
        }
    }

    @Test
    void theBaseOnlyFormsNeedExactlyTheirOwnBaseFeature() {
        for (String hex : BASE_ONLY) {
            int word = word(hex);
            Aarch64Feature base = isTmop(word) ? Aarch64Feature.SME_TMOP : Aarch64Feature.SME_MOP4;
            Aarch64Feature other = base == Aarch64Feature.SME_TMOP ? Aarch64Feature.SME_MOP4 : Aarch64Feature.SME_TMOP;
            assertInstanceOf(decode(ALL_DECODER, word).getClass(),
                    decode(new Aarch64Decoder(architectureWith("b-" + base, base)), word), hex);
            assertThrows(UnsupportedOperationException.class,
                    () -> decode(new Aarch64Decoder(architectureWith("o-" + other, other)), word), hex);
            assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, word), hex);
        }
    }

    @Test
    void theTwoFp8Mop4FormsHaveNoSubtractBit() {
        int sb = word("0x802801C3");
        int hb = word("0x802E0008");
        assertFalse(((SmeOp64.Mop4) decode(ALL_DECODER, sb)).subtract());
        assertFalse(((SmeOp64.Mop4) decode(ALL_DECODER, hb)).subtract());
        // com o bit `s` (4) ligado: o `.decode` fixa `0` — recusa, nao troca por outra instrucao
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, sb | 0x10));
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, hb | 0x10));
    }

    @Test
    void reservedNeighboursStayUnimplemented() {
        int fmop4ss = word("0x800000C3");
        // bit 5 (campo fixo `0` entre `zn` e `s`)
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, fmop4ss | 0x20));
        // bit 16 (fixo `0` em MOP4)
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, fmop4ss | 0x10000));
        int ftmopaSs = word("0x804D08E3");
        // bits[15:13] (fixos `000` em TMOP FP)
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, ftmopaSs | 0x2000));
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL_DECODER, ftmopaSs | 0x4000));
    }

    @Test
    void tmopWithoutTheSparsityFeatureIsRefusedNotMisdecodedAsMop4() {
        Aarch64Decoder mop4Only = new Aarch64Decoder(architectureWith("mop4-only", Aarch64Feature.SME_MOP4));
        for (String hex : BASE_ONLY) {
            int word = word(hex);
            if (isTmop(word)) {
                assertThrows(UnsupportedOperationException.class, () -> decode(mop4Only, word), hex);
            }
        }
    }
}
