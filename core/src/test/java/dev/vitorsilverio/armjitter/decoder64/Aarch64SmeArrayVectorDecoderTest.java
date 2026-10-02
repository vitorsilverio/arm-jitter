package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64.ArrayMultiVector.Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// SME2 multi-vetor "multiple and single, array vectors" (`_n1`, resultado em `ZA`) — B18.9. **Toda palavra da
/// tabela foi montada por `aarch64-none-elf-as -march=armv9.5-a+sme+sme2+sme-i16i64+sme-f64f64+sme-f16f16+
/// sme-b16b16+fp8+sme-f8f32+sme-f8f16` (devkitA64)**: o assembly de cada linha foi escrito primeiro (as DUAS variantes
/// de cada uma das 107 linhas do `.decode`: `off` máximo com `zn` desalinhado, e `off = 1` com `zn` dando a volta em
/// `Z31`), a palavra extraída com `objdump`, e os campos esperados (operação, `n`, `W<rv>`, `off` JÁ escalado, `zn`,
/// `zm`) saem do TEXTO do assembly, nunca do decoder. A última coluna de cada linha é o assembly.
class Aarch64SmeArrayVectorDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-azz-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SME2, "teste-azz-dec-ALL",
            Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
            Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
    private static final long INSTRUCTION_ADDRESS = 0x40;
    private static final int EXPECTED_ENCODINGS = 107;
    private static final int EXPECTED_MNEMONICS = 44;
    private static final int VARIANTS_PER_ENCODING = 2;

    /// `palavra; operação; n; W<rv>; off escalado; zn; zm; assembly`.
    private static final String TABLE = """
            0xC1293877; ADD_S; 2; 9; 7; 3; 9; add za.s[w9, 7, vgx2], {z3.s-z4.s}, z9.s
            0xC12F7BF1; ADD_S; 2; 11; 1; 31; 15; add za.s[w11, 1, vgx2], {z31.s-z0.s}, z15.s
            0xC1393877; ADD_S; 4; 9; 7; 3; 9; add za.s[w9, 7, vgx4], {z3.s-z6.s}, z9.s
            0xC13F7BB1; ADD_S; 4; 11; 1; 29; 15; add za.s[w11, 1, vgx4], {z29.s-z0.s}, z15.s
            0xC1693877; ADD_D; 2; 9; 7; 3; 9; add za.d[w9, 7, vgx2], {z3.d-z4.d}, z9.d
            0xC16F7BF1; ADD_D; 2; 11; 1; 31; 15; add za.d[w11, 1, vgx2], {z31.d-z0.d}, z15.d
            0xC1793877; ADD_D; 4; 9; 7; 3; 9; add za.d[w9, 7, vgx4], {z3.d-z6.d}, z9.d
            0xC17F7BB1; ADD_D; 4; 11; 1; 29; 15; add za.d[w11, 1, vgx4], {z29.d-z0.d}, z15.d
            0xC129387F; SUB_S; 2; 9; 7; 3; 9; sub za.s[w9, 7, vgx2], {z3.s-z4.s}, z9.s
            0xC12F7BF9; SUB_S; 2; 11; 1; 31; 15; sub za.s[w11, 1, vgx2], {z31.s-z0.s}, z15.s
            0xC139387F; SUB_S; 4; 9; 7; 3; 9; sub za.s[w9, 7, vgx4], {z3.s-z6.s}, z9.s
            0xC13F7BB9; SUB_S; 4; 11; 1; 29; 15; sub za.s[w11, 1, vgx4], {z29.s-z0.s}, z15.s
            0xC169387F; SUB_D; 2; 9; 7; 3; 9; sub za.d[w9, 7, vgx2], {z3.d-z4.d}, z9.d
            0xC16F7BF9; SUB_D; 2; 11; 1; 31; 15; sub za.d[w11, 1, vgx2], {z31.d-z0.d}, z15.d
            0xC179387F; SUB_D; 4; 9; 7; 3; 9; sub za.d[w9, 7, vgx4], {z3.d-z6.d}, z9.d
            0xC17F7BB9; SUB_D; 4; 11; 1; 29; 15; sub za.d[w11, 1, vgx4], {z29.d-z0.d}, z15.d
            0xC1292CA7; FMLAL; 1; 9; 14; 5; 9; fmlal za.s[w9, 14:15], z5.h, z9.h
            0xC12F6FE1; FMLAL; 1; 11; 2; 31; 15; fmlal za.s[w11, 2:3], z31.h, z15.h
            0xC1292863; FMLAL; 2; 9; 6; 3; 9; fmlal za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F6BE1; FMLAL; 2; 11; 2; 31; 15; fmlal za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC1392863; FMLAL; 4; 9; 6; 3; 9; fmlal za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F6BA1; FMLAL; 4; 11; 2; 29; 15; fmlal za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1292CAF; FMLSL; 1; 9; 14; 5; 9; fmlsl za.s[w9, 14:15], z5.h, z9.h
            0xC12F6FE9; FMLSL; 1; 11; 2; 31; 15; fmlsl za.s[w11, 2:3], z31.h, z15.h
            0xC129286B; FMLSL; 2; 9; 6; 3; 9; fmlsl za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F6BE9; FMLSL; 2; 11; 2; 31; 15; fmlsl za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC139286B; FMLSL; 4; 9; 6; 3; 9; fmlsl za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F6BA9; FMLSL; 4; 11; 2; 29; 15; fmlsl za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1292CB7; BFMLAL; 1; 9; 14; 5; 9; bfmlal za.s[w9, 14:15], z5.h, z9.h
            0xC12F6FF1; BFMLAL; 1; 11; 2; 31; 15; bfmlal za.s[w11, 2:3], z31.h, z15.h
            0xC1292873; BFMLAL; 2; 9; 6; 3; 9; bfmlal za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F6BF1; BFMLAL; 2; 11; 2; 31; 15; bfmlal za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC1392873; BFMLAL; 4; 9; 6; 3; 9; bfmlal za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F6BB1; BFMLAL; 4; 11; 2; 29; 15; bfmlal za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1292CBF; BFMLSL; 1; 9; 14; 5; 9; bfmlsl za.s[w9, 14:15], z5.h, z9.h
            0xC12F6FF9; BFMLSL; 1; 11; 2; 31; 15; bfmlsl za.s[w11, 2:3], z31.h, z15.h
            0xC129287B; BFMLSL; 2; 9; 6; 3; 9; bfmlsl za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F6BF9; BFMLSL; 2; 11; 2; 31; 15; bfmlsl za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC139287B; BFMLSL; 4; 9; 6; 3; 9; bfmlsl za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F6BB9; BFMLSL; 4; 11; 2; 29; 15; bfmlsl za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1293067; FDOT; 2; 9; 7; 3; 9; fdot za.s[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F73E1; FDOT; 2; 11; 1; 31; 15; fdot za.s[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1393067; FDOT; 4; 9; 7; 3; 9; fdot za.s[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F73A1; FDOT; 4; 11; 1; 29; 15; fdot za.s[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1293077; BFDOT; 2; 9; 7; 3; 9; bfdot za.s[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F73F1; BFDOT; 2; 11; 1; 31; 15; bfdot za.s[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1393077; BFDOT; 4; 9; 7; 3; 9; bfdot za.s[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F73B1; BFDOT; 4; 11; 1; 29; 15; bfdot za.s[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC129346F; USDOT; 2; 9; 7; 3; 9; usdot za.s[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F77E9; USDOT; 2; 11; 1; 31; 15; usdot za.s[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC139346F; USDOT; 4; 9; 7; 3; 9; usdot za.s[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F77A9; USDOT; 4; 11; 1; 29; 15; usdot za.s[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            0xC129347F; SUDOT; 2; 9; 7; 3; 9; sudot za.s[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F77F9; SUDOT; 2; 11; 1; 31; 15; sudot za.s[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC139347F; SUDOT; 4; 9; 7; 3; 9; sudot za.s[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F77B9; SUDOT; 4; 11; 1; 29; 15; sudot za.s[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            0xC1293467; SDOT_4B; 2; 9; 7; 3; 9; sdot za.s[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F77E1; SDOT_4B; 2; 11; 1; 31; 15; sdot za.s[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC1393467; SDOT_4B; 4; 9; 7; 3; 9; sdot za.s[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F77A1; SDOT_4B; 4; 11; 1; 29; 15; sdot za.s[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            0xC1293477; UDOT_4B; 2; 9; 7; 3; 9; udot za.s[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F77F1; UDOT_4B; 2; 11; 1; 31; 15; udot za.s[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC1393477; UDOT_4B; 4; 9; 7; 3; 9; udot za.s[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F77B1; UDOT_4B; 4; 11; 1; 29; 15; udot za.s[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            0xC1693467; SDOT_4H; 2; 9; 7; 3; 9; sdot za.d[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F77E1; SDOT_4H; 2; 11; 1; 31; 15; sdot za.d[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1793467; SDOT_4H; 4; 9; 7; 3; 9; sdot za.d[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F77A1; SDOT_4H; 4; 11; 1; 29; 15; sdot za.d[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1693477; UDOT_4H; 2; 9; 7; 3; 9; udot za.d[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F77F1; UDOT_4H; 2; 11; 1; 31; 15; udot za.d[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1793477; UDOT_4H; 4; 9; 7; 3; 9; udot za.d[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F77B1; UDOT_4H; 4; 11; 1; 29; 15; udot za.d[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC169346F; SDOT_2H; 2; 9; 7; 3; 9; sdot za.s[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F77E9; SDOT_2H; 2; 11; 1; 31; 15; sdot za.s[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC179346F; SDOT_2H; 4; 9; 7; 3; 9; sdot za.s[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F77A9; SDOT_2H; 4; 11; 1; 29; 15; sdot za.s[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC169347F; UDOT_2H; 2; 9; 7; 3; 9; udot za.s[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F77F9; UDOT_2H; 2; 11; 1; 31; 15; udot za.s[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC179347F; UDOT_2H; 4; 9; 7; 3; 9; udot za.s[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F77B9; UDOT_2H; 4; 11; 1; 29; 15; udot za.s[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1692CA7; SMLAL; 1; 9; 14; 5; 9; smlal za.s[w9, 14:15], z5.h, z9.h
            0xC16F6FE1; SMLAL; 1; 11; 2; 31; 15; smlal za.s[w11, 2:3], z31.h, z15.h
            0xC1692863; SMLAL; 2; 9; 6; 3; 9; smlal za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F6BE1; SMLAL; 2; 11; 2; 31; 15; smlal za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC1792863; SMLAL; 4; 9; 6; 3; 9; smlal za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F6BA1; SMLAL; 4; 11; 2; 29; 15; smlal za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1692CAF; SMLSL; 1; 9; 14; 5; 9; smlsl za.s[w9, 14:15], z5.h, z9.h
            0xC16F6FE9; SMLSL; 1; 11; 2; 31; 15; smlsl za.s[w11, 2:3], z31.h, z15.h
            0xC169286B; SMLSL; 2; 9; 6; 3; 9; smlsl za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F6BE9; SMLSL; 2; 11; 2; 31; 15; smlsl za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC179286B; SMLSL; 4; 9; 6; 3; 9; smlsl za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F6BA9; SMLSL; 4; 11; 2; 29; 15; smlsl za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1692CB7; UMLAL; 1; 9; 14; 5; 9; umlal za.s[w9, 14:15], z5.h, z9.h
            0xC16F6FF1; UMLAL; 1; 11; 2; 31; 15; umlal za.s[w11, 2:3], z31.h, z15.h
            0xC1692873; UMLAL; 2; 9; 6; 3; 9; umlal za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F6BF1; UMLAL; 2; 11; 2; 31; 15; umlal za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC1792873; UMLAL; 4; 9; 6; 3; 9; umlal za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F6BB1; UMLAL; 4; 11; 2; 29; 15; umlal za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC1692CBF; UMLSL; 1; 9; 14; 5; 9; umlsl za.s[w9, 14:15], z5.h, z9.h
            0xC16F6FF9; UMLSL; 1; 11; 2; 31; 15; umlsl za.s[w11, 2:3], z31.h, z15.h
            0xC169287B; UMLSL; 2; 9; 6; 3; 9; umlsl za.s[w9, 6:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F6BF9; UMLSL; 2; 11; 2; 31; 15; umlsl za.s[w11, 2:3, vgx2], {z31.h-z0.h}, z15.h
            0xC179287B; UMLSL; 4; 9; 6; 3; 9; umlsl za.s[w9, 6:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F6BB9; UMLSL; 4; 11; 2; 29; 15; umlsl za.s[w11, 2:3, vgx4], {z29.h-z0.h}, z15.h
            0xC12924A3; SMLALL_S; 1; 9; 12; 5; 9; smlall za.s[w9, 12:15], z5.b, z9.b
            0xC12F67E1; SMLALL_S; 1; 11; 4; 31; 15; smlall za.s[w11, 4:7], z31.b, z15.b
            0xC1292061; SMLALL_S; 2; 9; 4; 3; 9; smlall za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63E1; SMLALL_S; 2; 11; 4; 31; 15; smlall za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392061; SMLALL_S; 4; 9; 4; 3; 9; smlall za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63A1; SMLALL_S; 4; 11; 4; 29; 15; smlall za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC12924AB; SMLSLL_S; 1; 9; 12; 5; 9; smlsll za.s[w9, 12:15], z5.b, z9.b
            0xC12F67E9; SMLSLL_S; 1; 11; 4; 31; 15; smlsll za.s[w11, 4:7], z31.b, z15.b
            0xC1292069; SMLSLL_S; 2; 9; 4; 3; 9; smlsll za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63E9; SMLSLL_S; 2; 11; 4; 31; 15; smlsll za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392069; SMLSLL_S; 4; 9; 4; 3; 9; smlsll za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63A9; SMLSLL_S; 4; 11; 4; 29; 15; smlsll za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC12924B3; UMLALL_S; 1; 9; 12; 5; 9; umlall za.s[w9, 12:15], z5.b, z9.b
            0xC12F67F1; UMLALL_S; 1; 11; 4; 31; 15; umlall za.s[w11, 4:7], z31.b, z15.b
            0xC1292071; UMLALL_S; 2; 9; 4; 3; 9; umlall za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63F1; UMLALL_S; 2; 11; 4; 31; 15; umlall za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392071; UMLALL_S; 4; 9; 4; 3; 9; umlall za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63B1; UMLALL_S; 4; 11; 4; 29; 15; umlall za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC12924BB; UMLSLL_S; 1; 9; 12; 5; 9; umlsll za.s[w9, 12:15], z5.b, z9.b
            0xC12F67F9; UMLSLL_S; 1; 11; 4; 31; 15; umlsll za.s[w11, 4:7], z31.b, z15.b
            0xC1292079; UMLSLL_S; 2; 9; 4; 3; 9; umlsll za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63F9; UMLSLL_S; 2; 11; 4; 31; 15; umlsll za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392079; UMLSLL_S; 4; 9; 4; 3; 9; umlsll za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63B9; UMLSLL_S; 4; 11; 4; 29; 15; umlsll za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC16924A3; SMLALL_D; 1; 9; 12; 5; 9; smlall za.d[w9, 12:15], z5.h, z9.h
            0xC16F67E1; SMLALL_D; 1; 11; 4; 31; 15; smlall za.d[w11, 4:7], z31.h, z15.h
            0xC1692061; SMLALL_D; 2; 9; 4; 3; 9; smlall za.d[w9, 4:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F63E1; SMLALL_D; 2; 11; 4; 31; 15; smlall za.d[w11, 4:7, vgx2], {z31.h-z0.h}, z15.h
            0xC1792061; SMLALL_D; 4; 9; 4; 3; 9; smlall za.d[w9, 4:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F63A1; SMLALL_D; 4; 11; 4; 29; 15; smlall za.d[w11, 4:7, vgx4], {z29.h-z0.h}, z15.h
            0xC16924AB; SMLSLL_D; 1; 9; 12; 5; 9; smlsll za.d[w9, 12:15], z5.h, z9.h
            0xC16F67E9; SMLSLL_D; 1; 11; 4; 31; 15; smlsll za.d[w11, 4:7], z31.h, z15.h
            0xC1692069; SMLSLL_D; 2; 9; 4; 3; 9; smlsll za.d[w9, 4:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F63E9; SMLSLL_D; 2; 11; 4; 31; 15; smlsll za.d[w11, 4:7, vgx2], {z31.h-z0.h}, z15.h
            0xC1792069; SMLSLL_D; 4; 9; 4; 3; 9; smlsll za.d[w9, 4:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F63A9; SMLSLL_D; 4; 11; 4; 29; 15; smlsll za.d[w11, 4:7, vgx4], {z29.h-z0.h}, z15.h
            0xC16924B3; UMLALL_D; 1; 9; 12; 5; 9; umlall za.d[w9, 12:15], z5.h, z9.h
            0xC16F67F1; UMLALL_D; 1; 11; 4; 31; 15; umlall za.d[w11, 4:7], z31.h, z15.h
            0xC1692071; UMLALL_D; 2; 9; 4; 3; 9; umlall za.d[w9, 4:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F63F1; UMLALL_D; 2; 11; 4; 31; 15; umlall za.d[w11, 4:7, vgx2], {z31.h-z0.h}, z15.h
            0xC1792071; UMLALL_D; 4; 9; 4; 3; 9; umlall za.d[w9, 4:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F63B1; UMLALL_D; 4; 11; 4; 29; 15; umlall za.d[w11, 4:7, vgx4], {z29.h-z0.h}, z15.h
            0xC16924BB; UMLSLL_D; 1; 9; 12; 5; 9; umlsll za.d[w9, 12:15], z5.h, z9.h
            0xC16F67F9; UMLSLL_D; 1; 11; 4; 31; 15; umlsll za.d[w11, 4:7], z31.h, z15.h
            0xC1692079; UMLSLL_D; 2; 9; 4; 3; 9; umlsll za.d[w9, 4:7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F63F9; UMLSLL_D; 2; 11; 4; 31; 15; umlsll za.d[w11, 4:7, vgx2], {z31.h-z0.h}, z15.h
            0xC1792079; UMLSLL_D; 4; 9; 4; 3; 9; umlsll za.d[w9, 4:7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F63B9; UMLSLL_D; 4; 11; 4; 29; 15; umlsll za.d[w11, 4:7, vgx4], {z29.h-z0.h}, z15.h
            0xC12924A7; USMLALL; 1; 9; 12; 5; 9; usmlall za.s[w9, 12:15], z5.b, z9.b
            0xC12F67E5; USMLALL; 1; 11; 4; 31; 15; usmlall za.s[w11, 4:7], z31.b, z15.b
            0xC1292065; USMLALL; 2; 9; 4; 3; 9; usmlall za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63E5; USMLALL; 2; 11; 4; 31; 15; usmlall za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392065; USMLALL; 4; 9; 4; 3; 9; usmlall za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63A5; USMLALL; 4; 11; 4; 29; 15; usmlall za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC1292075; SUMLALL; 2; 9; 4; 3; 9; sumlall za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63F5; SUMLALL; 2; 11; 4; 31; 15; sumlall za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392075; SUMLALL; 4; 9; 4; 3; 9; sumlall za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63B5; SUMLALL; 4; 11; 4; 29; 15; sumlall za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC1693C67; BFMLA; 2; 9; 7; 3; 9; bfmla za.h[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F7FE1; BFMLA; 2; 11; 1; 31; 15; bfmla za.h[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1793C67; BFMLA; 4; 9; 7; 3; 9; bfmla za.h[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F7FA1; BFMLA; 4; 11; 1; 29; 15; bfmla za.h[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1693C6F; BFMLS; 2; 9; 7; 3; 9; bfmls za.h[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC16F7FE9; BFMLS; 2; 11; 1; 31; 15; bfmls za.h[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1793C6F; BFMLS; 4; 9; 7; 3; 9; bfmls za.h[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC17F7FA9; BFMLS; 4; 11; 1; 29; 15; bfmls za.h[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1293C67; FMLA_H; 2; 9; 7; 3; 9; fmla za.h[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F7FE1; FMLA_H; 2; 11; 1; 31; 15; fmla za.h[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1393C67; FMLA_H; 4; 9; 7; 3; 9; fmla za.h[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F7FA1; FMLA_H; 4; 11; 1; 29; 15; fmla za.h[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC1293867; FMLA_S; 2; 9; 7; 3; 9; fmla za.s[w9, 7, vgx2], {z3.s-z4.s}, z9.s
            0xC12F7BE1; FMLA_S; 2; 11; 1; 31; 15; fmla za.s[w11, 1, vgx2], {z31.s-z0.s}, z15.s
            0xC1393867; FMLA_S; 4; 9; 7; 3; 9; fmla za.s[w9, 7, vgx4], {z3.s-z6.s}, z9.s
            0xC13F7BA1; FMLA_S; 4; 11; 1; 29; 15; fmla za.s[w11, 1, vgx4], {z29.s-z0.s}, z15.s
            0xC1693867; FMLA_D; 2; 9; 7; 3; 9; fmla za.d[w9, 7, vgx2], {z3.d-z4.d}, z9.d
            0xC16F7BE1; FMLA_D; 2; 11; 1; 31; 15; fmla za.d[w11, 1, vgx2], {z31.d-z0.d}, z15.d
            0xC1793867; FMLA_D; 4; 9; 7; 3; 9; fmla za.d[w9, 7, vgx4], {z3.d-z6.d}, z9.d
            0xC17F7BA1; FMLA_D; 4; 11; 1; 29; 15; fmla za.d[w11, 1, vgx4], {z29.d-z0.d}, z15.d
            0xC1293C6F; FMLS_H; 2; 9; 7; 3; 9; fmls za.h[w9, 7, vgx2], {z3.h-z4.h}, z9.h
            0xC12F7FE9; FMLS_H; 2; 11; 1; 31; 15; fmls za.h[w11, 1, vgx2], {z31.h-z0.h}, z15.h
            0xC1393C6F; FMLS_H; 4; 9; 7; 3; 9; fmls za.h[w9, 7, vgx4], {z3.h-z6.h}, z9.h
            0xC13F7FA9; FMLS_H; 4; 11; 1; 29; 15; fmls za.h[w11, 1, vgx4], {z29.h-z0.h}, z15.h
            0xC129386F; FMLS_S; 2; 9; 7; 3; 9; fmls za.s[w9, 7, vgx2], {z3.s-z4.s}, z9.s
            0xC12F7BE9; FMLS_S; 2; 11; 1; 31; 15; fmls za.s[w11, 1, vgx2], {z31.s-z0.s}, z15.s
            0xC139386F; FMLS_S; 4; 9; 7; 3; 9; fmls za.s[w9, 7, vgx4], {z3.s-z6.s}, z9.s
            0xC13F7BA9; FMLS_S; 4; 11; 1; 29; 15; fmls za.s[w11, 1, vgx4], {z29.s-z0.s}, z15.s
            0xC169386F; FMLS_D; 2; 9; 7; 3; 9; fmls za.d[w9, 7, vgx2], {z3.d-z4.d}, z9.d
            0xC16F7BE9; FMLS_D; 2; 11; 1; 31; 15; fmls za.d[w11, 1, vgx2], {z31.d-z0.d}, z15.d
            0xC179386F; FMLS_D; 4; 9; 7; 3; 9; fmls za.d[w9, 7, vgx4], {z3.d-z6.d}, z9.d
            0xC17F7BA9; FMLS_D; 4; 11; 1; 29; 15; fmls za.d[w11, 1, vgx4], {z29.d-z0.d}, z15.d
            0xC13924A3; FMLALL_B; 1; 9; 12; 5; 9; fmlall za.s[w9, 12:15], z5.b, z9.b
            0xC13F67E1; FMLALL_B; 1; 11; 4; 31; 15; fmlall za.s[w11, 4:7], z31.b, z15.b
            0xC1292063; FMLALL_B; 2; 9; 4; 3; 9; fmlall za.s[w9, 4:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F63E3; FMLALL_B; 2; 11; 4; 31; 15; fmlall za.s[w11, 4:7, vgx2], {z31.b-z0.b}, z15.b
            0xC1392063; FMLALL_B; 4; 9; 4; 3; 9; fmlall za.s[w9, 4:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F63A3; FMLALL_B; 4; 11; 4; 29; 15; fmlall za.s[w11, 4:7, vgx4], {z29.b-z0.b}, z15.b
            0xC129307F; FDOT_SB; 2; 9; 7; 3; 9; fdot za.s[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F73F9; FDOT_SB; 2; 11; 1; 31; 15; fdot za.s[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC139307F; FDOT_SB; 4; 9; 7; 3; 9; fdot za.s[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F73B9; FDOT_SB; 4; 11; 1; 29; 15; fdot za.s[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            0xC1392CA7; FMLAL_HB; 1; 9; 14; 5; 9; fmlal za.h[w9, 14:15], z5.b, z9.b
            0xC13F6FE1; FMLAL_HB; 1; 11; 2; 31; 15; fmlal za.h[w11, 2:3], z31.b, z15.b
            0xC1292867; FMLAL_HB; 2; 9; 6; 3; 9; fmlal za.h[w9, 6:7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F6BE5; FMLAL_HB; 2; 11; 2; 31; 15; fmlal za.h[w11, 2:3, vgx2], {z31.b-z0.b}, z15.b
            0xC1392867; FMLAL_HB; 4; 9; 6; 3; 9; fmlal za.h[w9, 6:7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F6BA5; FMLAL_HB; 4; 11; 2; 29; 15; fmlal za.h[w11, 2:3, vgx4], {z29.b-z0.b}, z15.b
            0xC129306F; FDOT_HB; 2; 9; 7; 3; 9; fdot za.h[w9, 7, vgx2], {z3.b-z4.b}, z9.b
            0xC12F73E9; FDOT_HB; 2; 11; 1; 31; 15; fdot za.h[w11, 1, vgx2], {z31.b-z0.b}, z15.b
            0xC139306F; FDOT_HB; 4; 9; 7; 3; 9; fdot za.h[w9, 7, vgx4], {z3.b-z6.b}, z9.b
            0xC13F73A9; FDOT_HB; 4; 11; 1; 29; 15; fdot za.h[w11, 1, vgx4], {z29.b-z0.b}, z15.b
            """;

    private record Case(int word, Op op, int count, int register, int off, int zn, int zm, String assembly) {
        @Override
        public String toString() {
            return assembly;
        }
    }

    private static List<Case> cases() {
        return TABLE.lines().map(String::strip).filter(line -> !line.isEmpty()).map(line -> {
            String[] f = line.split("; ");
            return new Case((int) Long.decode(f[0]).longValue(), Op.valueOf(f[1]), Integer.parseInt(f[2]),
                    Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                    f[7]);
        }).toList();
    }

    static Stream<Case> allCases() {
        return cases().stream();
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    /// A feature EXTRA de cada operação, escrita aqui INDEPENDENTE do decoder (medida no `translate-sme.c`).
    private static Aarch64Feature extraFeature(Op op) {
        return switch (op) {
            case ADD_D, SUB_D, SDOT_4H, UDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D -> Aarch64Feature.SME_I16I64;
            case FMLA_D, FMLS_D -> Aarch64Feature.SME_F64F64;
            case FMLA_H, FMLS_H -> Aarch64Feature.SME_F16F16;
            case BFMLA, BFMLS -> Aarch64Feature.SME_B16B16;
            case FMLALL_B, FDOT_SB -> Aarch64Feature.SME_F8F32;
            case FMLAL_HB, FDOT_HB -> Aarch64Feature.SME_F8F16;
            default -> null;
        };
    }

    @Test
    void tableCoversTheWholeInventory() {
        List<Case> all = cases();
        assertEquals(VARIANTS_PER_ENCODING * EXPECTED_ENCODINGS, all.size());
        assertEquals(EXPECTED_MNEMONICS, all.stream().map(Case::op).distinct().count());
        // B18.10 acrescentou 8 `Op` que só existem na forma `_nn` (`FADD`/`FSUB`/`BFADD`/`BFSUB`).
        EnumSet<Op> expected = EnumSet.allOf(Op.class);
        expected.removeAll(EnumSet.of(Op.ADD_AAZ_S, Op.ADD_AAZ_D, Op.SUB_AAZ_S, Op.SUB_AAZ_D)); // B18.12: têm teste próprio
        expected.removeIf(Op::vertical); // dot vertical só existe na forma indexada (B18.11)
        expected.removeAll(EnumSet.of(Op.FADD_H, Op.FADD_S, Op.FADD_D, Op.BFADD, Op.FSUB_H, Op.FSUB_S, Op.FSUB_D,
                Op.BFSUB));
        assertEquals(expected, EnumSet.copyOf(all.stream().map(Case::op).toList()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void decodesEveryEncodingFromTheAssembler(Case c) {
        SmeOp64.ArrayMultiVector op = assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(ALL, c.word()));
        assertEquals(c.op(), op.op());
        assertEquals(c.count(), op.count());
        assertEquals(c.register(), op.registerIndex(), "W<rv> = campo + 8");
        assertEquals(c.off(), op.off(), "off JÁ multiplicado pela escala da linha");
        assertEquals(c.zn(), op.zn(), "zn é o registrador cru (sem alinhamento), pode ser Z31");
        assertEquals(c.zm(), op.zm());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void eachEncodingNeedsExactlyItsOwnFeatures(Case c) {
        Aarch64Feature extra = extraFeature(c.op());
        if (extra == null) {
            assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(SME2, c.word()));
            return;
        }
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, c.word()),
                "sem " + extra + " continua UNIMPLEMENTED");
        Aarch64Architecture only = Aarch64Architecture.extending(SME2, "teste-azz-dec-" + extra, extra);
        assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(only, c.word()));
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture sme = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-azz-dec-SME",
                Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
                Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
        for (Case c : cases()) {
            assertThrows(UnsupportedOperationException.class, () -> decode(sme, c.word()), c.assembly());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "0xC12924B7", // SUMLALL n=1 NÃO existe (só n=2/4): USMLALL n=1 com bits[4:2] = 101
            "0xC129286F", // FMLAL x2 com bits[4:2] = 011 (000/001/010/100/110 existem; 011 não)
            "0xC129A863", // FMLAL x2 com bit 15 ligado: o prefixo 001x só existe com bit 15 = 0 (B18.11: 0xC109… virou SMLALL indexado)
            "0xE1292863", // bit 29 ligado: fora do prefixo 11000001
            "0xC1293C73", // bits[12:10] = 111 com bits[4:3] = 10 (só 00/01 existem: FMLA_h/FMLS_h)
    })
    void neighbourEncodingsStayUnimplemented(String hex) {
        int word = (int) Long.decode(hex).longValue();
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, word), hex);
    }

    @Test
    void asymmetriesAreRealAndNotGeneratedByLoop() {
        List<Case> all = cases();
        assertEquals(List.of(2, 4), all.stream().filter(c -> c.op() == Op.SUMLALL).map(Case::count).distinct()
                .sorted().toList());
        assertEquals(List.of(1, 2, 4), all.stream().filter(c -> c.op() == Op.USMLALL).map(Case::count).distinct()
                .sorted().toList());
        assertEquals(List.of(2, 4), all.stream().filter(c -> c.op() == Op.SDOT_4B).map(Case::count).distinct()
                .sorted().toList());
    }

    @Test
    void offsetIsScaledByTheNumberOfVectorsWrittenPerMember() {
        // fmlal za.s[w9, 6:7, vgx2] -> off = 6 (o2x2: campo 3 × 2); usmlall za.s[w9, 12:15] -> off = 12 (o2x4: 3 × 4).
        assertEquals(6, assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(ALL, 0xC1292863)).off());
        assertEquals(12, assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(ALL, 0xC12924A7)).off());
    }
}
