package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector.Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// SME2 multi-vetor "multiple, array vectors" (`_nn`, grupo contra grupo, resultado em `ZA`) — B18.10. **Toda palavra
/// da tabela foi montada por `aarch64-none-elf-as -march=armv9.5-a+sme+sme2+sme-i16i64+sme-f64f64+sme-f16f16+
/// sme-b16b16+fp8+sme-f8f32+sme-f8f16` (devkitA64)** a partir de um assembly escrito INDEPENDENTE do decoder (as DUAS
/// variantes de cada uma das 100 linhas do `.decode`: `off` máximo com grupos baixos, e `off` mínimo com grupos altos),
/// e o `objdump` devolveu o MESMO texto para as 200 palavras. Os campos esperados (operação, `n`, `W<rv>`, `off` JÁ
/// escalado, `zn`, `zm`) saem do TEXTO do assembly. Os quatro extratores de grupo (`%zn_ax2/4`, `%zm_ax2/4`) usam bases
/// de bits diferentes e a tabela usa valores que os distinguem. Nas 8 instruções `FADD`/`FSUB`/`BFADD`/`BFSUB` (que NÃO
/// têm `Zn`) a coluna `zn` é `0` e `zm` é o único grupo, extraído pela base de bits de `zn`.
class Aarch64SmeArrayMultipleDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-azz-nn-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SME2, "teste-azz-nn-dec-ALL",
            Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
            Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
    private static final long INSTRUCTION_ADDRESS = 0x40;
    private static final int EXPECTED_ENCODINGS = 100;
    private static final int EXPECTED_MNEMONICS = 50;
    private static final int VARIANTS_PER_ENCODING = 2;
    private static final int ACCUMULATE_MNEMONICS = 8;
    /// Bit que separa `USDOT`/`USMLALL` de `SUDOT`/`SUMLALL` (`bits[4]`).
    private static final int SU_SWAP_BIT = 0x10;
    /// Um bit dentro dos `bits[20:16]` FIXOS de `FADD`/`FSUB` (tamanho do elemento).
    private static final int FADD_FIXED_FIELD_BIT = 1 << 19;

    /// `palavra; operação; n; W<rv>; off escalado; zn; zm; assembly`.
    private static final String TABLE = """
            0xC1A63957; ADD_S; 2; 9; 7; 10; 6; add za.s[w9, 7, vgx2], {z10.s-z11.s}, {z6.s-z7.s}
            0xC1B87BD1; ADD_S; 2; 11; 1; 30; 24; add za.s[w11, 1, vgx2], {z30.s-z31.s}, {z24.s-z25.s}
            0xC1B53997; ADD_S; 4; 9; 7; 12; 20; add za.s[w9, 7, vgx4], {z12.s-z15.s}, {z20.s-z23.s}
            0xC1BD7B11; ADD_S; 4; 11; 1; 24; 28; add za.s[w11, 1, vgx4], {z24.s-z27.s}, {z28.s-z31.s}
            0xC1E63957; ADD_D; 2; 9; 7; 10; 6; add za.d[w9, 7, vgx2], {z10.d-z11.d}, {z6.d-z7.d}
            0xC1F87BD1; ADD_D; 2; 11; 1; 30; 24; add za.d[w11, 1, vgx2], {z30.d-z31.d}, {z24.d-z25.d}
            0xC1F53997; ADD_D; 4; 9; 7; 12; 20; add za.d[w9, 7, vgx4], {z12.d-z15.d}, {z20.d-z23.d}
            0xC1FD7B11; ADD_D; 4; 11; 1; 24; 28; add za.d[w11, 1, vgx4], {z24.d-z27.d}, {z28.d-z31.d}
            0xC1A6395F; SUB_S; 2; 9; 7; 10; 6; sub za.s[w9, 7, vgx2], {z10.s-z11.s}, {z6.s-z7.s}
            0xC1B87BD9; SUB_S; 2; 11; 1; 30; 24; sub za.s[w11, 1, vgx2], {z30.s-z31.s}, {z24.s-z25.s}
            0xC1B5399F; SUB_S; 4; 9; 7; 12; 20; sub za.s[w9, 7, vgx4], {z12.s-z15.s}, {z20.s-z23.s}
            0xC1BD7B19; SUB_S; 4; 11; 1; 24; 28; sub za.s[w11, 1, vgx4], {z24.s-z27.s}, {z28.s-z31.s}
            0xC1E6395F; SUB_D; 2; 9; 7; 10; 6; sub za.d[w9, 7, vgx2], {z10.d-z11.d}, {z6.d-z7.d}
            0xC1F87BD9; SUB_D; 2; 11; 1; 30; 24; sub za.d[w11, 1, vgx2], {z30.d-z31.d}, {z24.d-z25.d}
            0xC1F5399F; SUB_D; 4; 9; 7; 12; 20; sub za.d[w9, 7, vgx4], {z12.d-z15.d}, {z20.d-z23.d}
            0xC1FD7B19; SUB_D; 4; 11; 1; 24; 28; sub za.d[w11, 1, vgx4], {z24.d-z27.d}, {z28.d-z31.d}
            0xC1A62943; FMLAL; 2; 9; 6; 10; 6; fmlal za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B86BC1; FMLAL; 2; 11; 2; 30; 24; fmlal za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52983; FMLAL; 4; 9; 6; 12; 20; fmlal za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD6B01; FMLAL; 4; 11; 2; 24; 28; fmlal za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A6294B; FMLSL; 2; 9; 6; 10; 6; fmlsl za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B86BC9; FMLSL; 2; 11; 2; 30; 24; fmlsl za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B5298B; FMLSL; 4; 9; 6; 12; 20; fmlsl za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD6B09; FMLSL; 4; 11; 2; 24; 28; fmlsl za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62953; BFMLAL; 2; 9; 6; 10; 6; bfmlal za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B86BD1; BFMLAL; 2; 11; 2; 30; 24; bfmlal za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52993; BFMLAL; 4; 9; 6; 12; 20; bfmlal za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD6B11; BFMLAL; 4; 11; 2; 24; 28; bfmlal za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A6295B; BFMLSL; 2; 9; 6; 10; 6; bfmlsl za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B86BD9; BFMLSL; 2; 11; 2; 30; 24; bfmlsl za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B5299B; BFMLSL; 4; 9; 6; 12; 20; bfmlsl za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD6B19; BFMLSL; 4; 11; 2; 24; 28; bfmlsl za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A63147; FDOT; 2; 9; 7; 10; 6; fdot za.s[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B873C1; FDOT; 2; 11; 1; 30; 24; fdot za.s[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B53187; FDOT; 4; 9; 7; 12; 20; fdot za.s[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD7301; FDOT; 4; 11; 1; 24; 28; fdot za.s[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A63157; BFDOT; 2; 9; 7; 10; 6; bfdot za.s[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B873D1; BFDOT; 2; 11; 1; 30; 24; bfdot za.s[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B53197; BFDOT; 4; 9; 7; 12; 20; bfdot za.s[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD7311; BFDOT; 4; 11; 1; 24; 28; bfdot za.s[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A6354F; USDOT; 2; 9; 7; 10; 6; usdot za.s[w9, 7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B877C9; USDOT; 2; 11; 1; 30; 24; usdot za.s[w11, 1, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B5358F; USDOT; 4; 9; 7; 12; 20; usdot za.s[w9, 7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD7709; USDOT; 4; 11; 1; 24; 28; usdot za.s[w11, 1, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1A63547; SDOT_4B; 2; 9; 7; 10; 6; sdot za.s[w9, 7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B877C1; SDOT_4B; 2; 11; 1; 30; 24; sdot za.s[w11, 1, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B53587; SDOT_4B; 4; 9; 7; 12; 20; sdot za.s[w9, 7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD7701; SDOT_4B; 4; 11; 1; 24; 28; sdot za.s[w11, 1, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1E63547; SDOT_4H; 2; 9; 7; 10; 6; sdot za.d[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F877C1; SDOT_4H; 2; 11; 1; 30; 24; sdot za.d[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F53587; SDOT_4H; 4; 9; 7; 12; 20; sdot za.d[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7701; SDOT_4H; 4; 11; 1; 24; 28; sdot za.d[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E6354F; SDOT_2H; 2; 9; 7; 10; 6; sdot za.s[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F877C9; SDOT_2H; 2; 11; 1; 30; 24; sdot za.s[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F5358F; SDOT_2H; 4; 9; 7; 12; 20; sdot za.s[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7709; SDOT_2H; 4; 11; 1; 24; 28; sdot za.s[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A63557; UDOT_4B; 2; 9; 7; 10; 6; udot za.s[w9, 7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B877D1; UDOT_4B; 2; 11; 1; 30; 24; udot za.s[w11, 1, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B53597; UDOT_4B; 4; 9; 7; 12; 20; udot za.s[w9, 7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD7711; UDOT_4B; 4; 11; 1; 24; 28; udot za.s[w11, 1, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1E63557; UDOT_4H; 2; 9; 7; 10; 6; udot za.d[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F877D1; UDOT_4H; 2; 11; 1; 30; 24; udot za.d[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F53597; UDOT_4H; 4; 9; 7; 12; 20; udot za.d[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7711; UDOT_4H; 4; 11; 1; 24; 28; udot za.d[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E6355F; UDOT_2H; 2; 9; 7; 10; 6; udot za.s[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F877D9; UDOT_2H; 2; 11; 1; 30; 24; udot za.s[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F5359F; UDOT_2H; 4; 9; 7; 12; 20; udot za.s[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7719; UDOT_2H; 4; 11; 1; 24; 28; udot za.s[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E62943; SMLAL; 2; 9; 6; 10; 6; smlal za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F86BC1; SMLAL; 2; 11; 2; 30; 24; smlal za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F52983; SMLAL; 4; 9; 6; 12; 20; smlal za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6B01; SMLAL; 4; 11; 2; 24; 28; smlal za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E6294B; SMLSL; 2; 9; 6; 10; 6; smlsl za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F86BC9; SMLSL; 2; 11; 2; 30; 24; smlsl za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F5298B; SMLSL; 4; 9; 6; 12; 20; smlsl za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6B09; SMLSL; 4; 11; 2; 24; 28; smlsl za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E62953; UMLAL; 2; 9; 6; 10; 6; umlal za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F86BD1; UMLAL; 2; 11; 2; 30; 24; umlal za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F52993; UMLAL; 4; 9; 6; 12; 20; umlal za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6B11; UMLAL; 4; 11; 2; 24; 28; umlal za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1E6295B; UMLSL; 2; 9; 6; 10; 6; umlsl za.s[w9, 6:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F86BD9; UMLSL; 2; 11; 2; 30; 24; umlsl za.s[w11, 2:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1F5299B; UMLSL; 4; 9; 6; 12; 20; umlsl za.s[w9, 6:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6B19; UMLSL; 4; 11; 2; 24; 28; umlsl za.s[w11, 2:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62141; SMLALL_S; 2; 9; 4; 10; 6; smlall za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863C0; SMLALL_S; 2; 11; 0; 30; 24; smlall za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1E62141; SMLALL_D; 2; 9; 4; 10; 6; smlall za.d[w9, 4:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F863C0; SMLALL_D; 2; 11; 0; 30; 24; smlall za.d[w11, 0:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52181; SMLALL_S; 4; 9; 4; 12; 20; smlall za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6300; SMLALL_S; 4; 11; 0; 24; 28; smlall za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1F52181; SMLALL_D; 4; 9; 4; 12; 20; smlall za.d[w9, 4:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6300; SMLALL_D; 4; 11; 0; 24; 28; smlall za.d[w11, 0:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62149; SMLSLL_S; 2; 9; 4; 10; 6; smlsll za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863C8; SMLSLL_S; 2; 11; 0; 30; 24; smlsll za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1E62149; SMLSLL_D; 2; 9; 4; 10; 6; smlsll za.d[w9, 4:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F863C8; SMLSLL_D; 2; 11; 0; 30; 24; smlsll za.d[w11, 0:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52189; SMLSLL_S; 4; 9; 4; 12; 20; smlsll za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6308; SMLSLL_S; 4; 11; 0; 24; 28; smlsll za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1F52189; SMLSLL_D; 4; 9; 4; 12; 20; smlsll za.d[w9, 4:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6308; SMLSLL_D; 4; 11; 0; 24; 28; smlsll za.d[w11, 0:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62151; UMLALL_S; 2; 9; 4; 10; 6; umlall za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863D0; UMLALL_S; 2; 11; 0; 30; 24; umlall za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1E62151; UMLALL_D; 2; 9; 4; 10; 6; umlall za.d[w9, 4:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F863D0; UMLALL_D; 2; 11; 0; 30; 24; umlall za.d[w11, 0:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52191; UMLALL_S; 4; 9; 4; 12; 20; umlall za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6310; UMLALL_S; 4; 11; 0; 24; 28; umlall za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1F52191; UMLALL_D; 4; 9; 4; 12; 20; umlall za.d[w9, 4:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6310; UMLALL_D; 4; 11; 0; 24; 28; umlall za.d[w11, 0:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62159; UMLSLL_S; 2; 9; 4; 10; 6; umlsll za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863D8; UMLSLL_S; 2; 11; 0; 30; 24; umlsll za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1E62159; UMLSLL_D; 2; 9; 4; 10; 6; umlsll za.d[w9, 4:7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F863D8; UMLSLL_D; 2; 11; 0; 30; 24; umlsll za.d[w11, 0:3, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1B52199; UMLSLL_S; 4; 9; 4; 12; 20; umlsll za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6318; UMLSLL_S; 4; 11; 0; 24; 28; umlsll za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1F52199; UMLSLL_D; 4; 9; 4; 12; 20; umlsll za.d[w9, 4:7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD6318; UMLSLL_D; 4; 11; 0; 24; 28; umlsll za.d[w11, 0:3, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1A62145; USMLALL; 2; 9; 4; 10; 6; usmlall za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863C4; USMLALL; 2; 11; 0; 30; 24; usmlall za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B52185; USMLALL; 4; 9; 4; 12; 20; usmlall za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6304; USMLALL; 4; 11; 0; 24; 28; usmlall za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1E6314F; BFMLA; 2; 9; 7; 10; 6; bfmla za.h[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F873C9; BFMLA; 2; 11; 1; 30; 24; bfmla za.h[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1A6314F; FMLA_H; 2; 9; 7; 10; 6; fmla za.h[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B873C9; FMLA_H; 2; 11; 1; 30; 24; fmla za.h[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1A63947; FMLA_S; 2; 9; 7; 10; 6; fmla za.s[w9, 7, vgx2], {z10.s-z11.s}, {z6.s-z7.s}
            0xC1B87BC1; FMLA_S; 2; 11; 1; 30; 24; fmla za.s[w11, 1, vgx2], {z30.s-z31.s}, {z24.s-z25.s}
            0xC1E63947; FMLA_D; 2; 9; 7; 10; 6; fmla za.d[w9, 7, vgx2], {z10.d-z11.d}, {z6.d-z7.d}
            0xC1F87BC1; FMLA_D; 2; 11; 1; 30; 24; fmla za.d[w11, 1, vgx2], {z30.d-z31.d}, {z24.d-z25.d}
            0xC1F5318F; BFMLA; 4; 9; 7; 12; 20; bfmla za.h[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7309; BFMLA; 4; 11; 1; 24; 28; bfmla za.h[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1B5318F; FMLA_H; 4; 9; 7; 12; 20; fmla za.h[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD7309; FMLA_H; 4; 11; 1; 24; 28; fmla za.h[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1B53987; FMLA_S; 4; 9; 7; 12; 20; fmla za.s[w9, 7, vgx4], {z12.s-z15.s}, {z20.s-z23.s}
            0xC1BD7B01; FMLA_S; 4; 11; 1; 24; 28; fmla za.s[w11, 1, vgx4], {z24.s-z27.s}, {z28.s-z31.s}
            0xC1F53987; FMLA_D; 4; 9; 7; 12; 20; fmla za.d[w9, 7, vgx4], {z12.d-z15.d}, {z20.d-z23.d}
            0xC1FD7B01; FMLA_D; 4; 11; 1; 24; 28; fmla za.d[w11, 1, vgx4], {z24.d-z27.d}, {z28.d-z31.d}
            0xC1E6315F; BFMLS; 2; 9; 7; 10; 6; bfmls za.h[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1F873D9; BFMLS; 2; 11; 1; 30; 24; bfmls za.h[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1A6315F; FMLS_H; 2; 9; 7; 10; 6; fmls za.h[w9, 7, vgx2], {z10.h-z11.h}, {z6.h-z7.h}
            0xC1B873D9; FMLS_H; 2; 11; 1; 30; 24; fmls za.h[w11, 1, vgx2], {z30.h-z31.h}, {z24.h-z25.h}
            0xC1A6394F; FMLS_S; 2; 9; 7; 10; 6; fmls za.s[w9, 7, vgx2], {z10.s-z11.s}, {z6.s-z7.s}
            0xC1B87BC9; FMLS_S; 2; 11; 1; 30; 24; fmls za.s[w11, 1, vgx2], {z30.s-z31.s}, {z24.s-z25.s}
            0xC1E6394F; FMLS_D; 2; 9; 7; 10; 6; fmls za.d[w9, 7, vgx2], {z10.d-z11.d}, {z6.d-z7.d}
            0xC1F87BC9; FMLS_D; 2; 11; 1; 30; 24; fmls za.d[w11, 1, vgx2], {z30.d-z31.d}, {z24.d-z25.d}
            0xC1F5319F; BFMLS; 4; 9; 7; 12; 20; bfmls za.h[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1FD7319; BFMLS; 4; 11; 1; 24; 28; bfmls za.h[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1B5319F; FMLS_H; 4; 9; 7; 12; 20; fmls za.h[w9, 7, vgx4], {z12.h-z15.h}, {z20.h-z23.h}
            0xC1BD7319; FMLS_H; 4; 11; 1; 24; 28; fmls za.h[w11, 1, vgx4], {z24.h-z27.h}, {z28.h-z31.h}
            0xC1B5398F; FMLS_S; 4; 9; 7; 12; 20; fmls za.s[w9, 7, vgx4], {z12.s-z15.s}, {z20.s-z23.s}
            0xC1BD7B09; FMLS_S; 4; 11; 1; 24; 28; fmls za.s[w11, 1, vgx4], {z24.s-z27.s}, {z28.s-z31.s}
            0xC1F5398F; FMLS_D; 4; 9; 7; 12; 20; fmls za.d[w9, 7, vgx4], {z12.d-z15.d}, {z20.d-z23.d}
            0xC1FD7B09; FMLS_D; 4; 11; 1; 24; 28; fmls za.d[w11, 1, vgx4], {z24.d-z27.d}, {z28.d-z31.d}
            0xC1A62161; FMLALL_B; 2; 9; 4; 10; 6; fmlall za.s[w9, 4:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B863E0; FMLALL_B; 2; 11; 0; 30; 24; fmlall za.s[w11, 0:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B521A1; FMLALL_B; 4; 9; 4; 12; 20; fmlall za.s[w9, 4:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6320; FMLALL_B; 4; 11; 0; 24; 28; fmlall za.s[w11, 0:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1A63177; FDOT_SB; 2; 9; 7; 10; 6; fdot za.s[w9, 7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B873F1; FDOT_SB; 2; 11; 1; 30; 24; fdot za.s[w11, 1, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B531B7; FDOT_SB; 4; 9; 7; 12; 20; fdot za.s[w9, 7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD7331; FDOT_SB; 4; 11; 1; 24; 28; fdot za.s[w11, 1, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1A62963; FMLAL_HB; 2; 9; 6; 10; 6; fmlal za.h[w9, 6:7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B86BE1; FMLAL_HB; 2; 11; 2; 30; 24; fmlal za.h[w11, 2:3, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B529A3; FMLAL_HB; 4; 9; 6; 12; 20; fmlal za.h[w9, 6:7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD6B21; FMLAL_HB; 4; 11; 2; 24; 28; fmlal za.h[w11, 2:3, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1A63167; FDOT_HB; 2; 9; 7; 10; 6; fdot za.h[w9, 7, vgx2], {z10.b-z11.b}, {z6.b-z7.b}
            0xC1B873E1; FDOT_HB; 2; 11; 1; 30; 24; fdot za.h[w11, 1, vgx2], {z30.b-z31.b}, {z24.b-z25.b}
            0xC1B531A7; FDOT_HB; 4; 9; 7; 12; 20; fdot za.h[w9, 7, vgx4], {z12.b-z15.b}, {z20.b-z23.b}
            0xC1BD7321; FDOT_HB; 4; 11; 1; 24; 28; fdot za.h[w11, 1, vgx4], {z24.b-z27.b}, {z28.b-z31.b}
            0xC1A43D47; FADD_H; 2; 9; 7; 0; 10; fadd za.h[w9, 7, vgx2], {z10.h-z11.h}
            0xC1A47FC1; FADD_H; 2; 11; 1; 0; 30; fadd za.h[w11, 1, vgx2], {z30.h-z31.h}
            0xC1A03D47; FADD_S; 2; 9; 7; 0; 10; fadd za.s[w9, 7, vgx2], {z10.s-z11.s}
            0xC1A07FC1; FADD_S; 2; 11; 1; 0; 30; fadd za.s[w11, 1, vgx2], {z30.s-z31.s}
            0xC1E03D47; FADD_D; 2; 9; 7; 0; 10; fadd za.d[w9, 7, vgx2], {z10.d-z11.d}
            0xC1E07FC1; FADD_D; 2; 11; 1; 0; 30; fadd za.d[w11, 1, vgx2], {z30.d-z31.d}
            0xC1A53D87; FADD_H; 4; 9; 7; 0; 12; fadd za.h[w9, 7, vgx4], {z12.h-z15.h}
            0xC1A57F01; FADD_H; 4; 11; 1; 0; 24; fadd za.h[w11, 1, vgx4], {z24.h-z27.h}
            0xC1A13D87; FADD_S; 4; 9; 7; 0; 12; fadd za.s[w9, 7, vgx4], {z12.s-z15.s}
            0xC1A17F01; FADD_S; 4; 11; 1; 0; 24; fadd za.s[w11, 1, vgx4], {z24.s-z27.s}
            0xC1E13D87; FADD_D; 4; 9; 7; 0; 12; fadd za.d[w9, 7, vgx4], {z12.d-z15.d}
            0xC1E17F01; FADD_D; 4; 11; 1; 0; 24; fadd za.d[w11, 1, vgx4], {z24.d-z27.d}
            0xC1A43D4F; FSUB_H; 2; 9; 7; 0; 10; fsub za.h[w9, 7, vgx2], {z10.h-z11.h}
            0xC1A47FC9; FSUB_H; 2; 11; 1; 0; 30; fsub za.h[w11, 1, vgx2], {z30.h-z31.h}
            0xC1A03D4F; FSUB_S; 2; 9; 7; 0; 10; fsub za.s[w9, 7, vgx2], {z10.s-z11.s}
            0xC1A07FC9; FSUB_S; 2; 11; 1; 0; 30; fsub za.s[w11, 1, vgx2], {z30.s-z31.s}
            0xC1E03D4F; FSUB_D; 2; 9; 7; 0; 10; fsub za.d[w9, 7, vgx2], {z10.d-z11.d}
            0xC1E07FC9; FSUB_D; 2; 11; 1; 0; 30; fsub za.d[w11, 1, vgx2], {z30.d-z31.d}
            0xC1A53D8F; FSUB_H; 4; 9; 7; 0; 12; fsub za.h[w9, 7, vgx4], {z12.h-z15.h}
            0xC1A57F09; FSUB_H; 4; 11; 1; 0; 24; fsub za.h[w11, 1, vgx4], {z24.h-z27.h}
            0xC1A13D8F; FSUB_S; 4; 9; 7; 0; 12; fsub za.s[w9, 7, vgx4], {z12.s-z15.s}
            0xC1A17F09; FSUB_S; 4; 11; 1; 0; 24; fsub za.s[w11, 1, vgx4], {z24.s-z27.s}
            0xC1E13D8F; FSUB_D; 4; 9; 7; 0; 12; fsub za.d[w9, 7, vgx4], {z12.d-z15.d}
            0xC1E17F09; FSUB_D; 4; 11; 1; 0; 24; fsub za.d[w11, 1, vgx4], {z24.d-z27.d}
            0xC1E43D47; BFADD; 2; 9; 7; 0; 10; bfadd za.h[w9, 7, vgx2], {z10.h-z11.h}
            0xC1E47FC1; BFADD; 2; 11; 1; 0; 30; bfadd za.h[w11, 1, vgx2], {z30.h-z31.h}
            0xC1E53D87; BFADD; 4; 9; 7; 0; 12; bfadd za.h[w9, 7, vgx4], {z12.h-z15.h}
            0xC1E57F01; BFADD; 4; 11; 1; 0; 24; bfadd za.h[w11, 1, vgx4], {z24.h-z27.h}
            0xC1E43D4F; BFSUB; 2; 9; 7; 0; 10; bfsub za.h[w9, 7, vgx2], {z10.h-z11.h}
            0xC1E47FC9; BFSUB; 2; 11; 1; 0; 30; bfsub za.h[w11, 1, vgx2], {z30.h-z31.h}
            0xC1E53D8F; BFSUB; 4; 9; 7; 0; 12; bfsub za.h[w9, 7, vgx4], {z12.h-z15.h}
            0xC1E57F09; BFSUB; 4; 11; 1; 0; 24; bfsub za.h[w11, 1, vgx4], {z24.h-z27.h}
            """;

    private record Case(int word, Op op, int count, int register, int off, int zn, int zm, String assembly) {
        @Override
        public String toString() {
            return assembly;
        }
    }

    /// As 8 instruções que só existem na forma `_nn` e NÃO têm `Zn`.
    private static final Set<Op> ACCUMULATE_ONLY = EnumSet.of(Op.FADD_H, Op.FADD_S, Op.FADD_D, Op.BFADD, Op.FSUB_H,
            Op.FSUB_S, Op.FSUB_D, Op.BFSUB);

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
    /// `FADD_h`/`FSUB_h` não entram: aceitam `F16F16` OU `F8F16` (teste próprio).
    private static Aarch64Feature extraFeature(Op op) {
        return switch (op) {
            case ADD_D, SUB_D, SDOT_4H, UDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D -> Aarch64Feature.SME_I16I64;
            case FMLA_D, FMLS_D, FADD_D, FSUB_D -> Aarch64Feature.SME_F64F64;
            case FMLA_H, FMLS_H -> Aarch64Feature.SME_F16F16;
            case BFMLA, BFMLS, BFADD, BFSUB -> Aarch64Feature.SME_B16B16;
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
        // Cada encoding é um par (operação, n): 50 mnemônicos × {2, 4} — protege contra um `n` trocado na tabela.
        assertEquals(EXPECTED_ENCODINGS, all.stream().map(c -> c.op() + "/" + c.count()).distinct().count());
        assertEquals(EXPECTED_ENCODINGS / 2, all.stream().filter(c -> c.count() == 4).map(Case::op).distinct().count());
        // `SUDOT_nn`/`SUMLALL_nn_s` NÃO existem: todo `Op` menos esses dois é coberto.
        Set<Op> expected = EnumSet.allOf(Op.class);
        expected.remove(Op.SUDOT);
        expected.remove(Op.SUMLALL);
        assertEquals(expected, EnumSet.copyOf(all.stream().map(Case::op).toList()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void decodesEveryEncodingFromTheAssembler(Case c) {
        Ir64Op.SmeArrayMultiVector op = assertInstanceOf(Ir64Op.SmeArrayMultiVector.class, decode(ALL, c.word()));
        assertEquals(c.op(), op.op());
        assertEquals(c.count(), op.count());
        assertEquals(c.register(), op.registerIndex(), "W<rv> = campo + 8");
        assertEquals(c.off(), op.off(), "off JÁ multiplicado pela escala da linha");
        assertEquals(c.zn(), op.zn(), ACCUMULATE_ONLY.contains(c.op()) ? "sem Zn: fica 0" : "base alinhada de Zn");
        assertEquals(c.zm(), op.zm(), "base alinhada de Zm");
        assertTrue(op.multipleZm(), "nas formas _nn o Zm também é um grupo");
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void eachEncodingNeedsExactlyItsOwnFeatures(Case c) {
        if (c.op() == Op.FADD_H || c.op() == Op.FSUB_H) {
            assertThrows(UnsupportedOperationException.class, () -> decode(SME2, c.word()));
            for (Aarch64Feature either : List.of(Aarch64Feature.SME_F16F16, Aarch64Feature.SME_F8F16)) {
                Aarch64Architecture only = Aarch64Architecture.extending(SME2, "teste-azz-nn-dec-" + either, either);
                assertInstanceOf(Ir64Op.SmeArrayMultiVector.class, decode(only, c.word()),
                        "FADD/FSUB half aceitam QUALQUER das duas: " + either);
            }
            return;
        }
        Aarch64Feature extra = extraFeature(c.op());
        if (extra == null) {
            assertInstanceOf(Ir64Op.SmeArrayMultiVector.class, decode(SME2, c.word()));
            return;
        }
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, c.word()),
                "sem " + extra + " continua UNIMPLEMENTED");
        Aarch64Architecture only = Aarch64Architecture.extending(SME2, "teste-azz-nn-dec-" + extra, extra);
        assertInstanceOf(Ir64Op.SmeArrayMultiVector.class, decode(only, c.word()));
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture sme = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-azz-nn-dec-SME",
                Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
                Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
        for (Case c : cases()) {
            assertThrows(UnsupportedOperationException.class, () -> decode(sme, c.word()), c.assembly());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "0xC1A63977", // ADD_nn 2x2 com bit 5 ligado (alinhamento do grupo de Zn)
            "0xC1B73997", // ADD_nn 4x4 com bit 17 ligado (alinhamento do grupo de Zm: bits[17:16] = 11)
            "0xE1A63957", // bit 29 ligado: fora do prefixo 11000001
    })
    void neighbourEncodingsStayUnimplemented(String hex) {
        int word = (int) Long.decode(hex).longValue();
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, word), hex);
    }

    @Test
    void sudotAndSumlallDoNotExistInTheMultipleForm() {
        // Existem em `_n1` (B18.9) trocando só o bit 4 de USDOT/USMLALL; em `_nn` esse espaço é indefinido (G8).
        int checked = 0;
        for (Case c : cases()) {
            if (c.op() == Op.USDOT || c.op() == Op.USMLALL) {
                assertThrows(UnsupportedOperationException.class, () -> decode(ALL, c.word() | SU_SWAP_BIT),
                        "SUDOT/SUMLALL _nn não existem: " + c.assembly());
                checked++;
            }
        }
        assertEquals(VARIANTS_PER_ENCODING * 2 * 2, checked);
    }

    @Test
    void faddFsubFamilyHasNoZnOperandAndTakesZmFromTheZnBitBase() {
        List<Case> accumulate = cases().stream().filter(c -> ACCUMULATE_ONLY.contains(c.op())).toList();
        assertEquals(VARIANTS_PER_ENCODING * 2 * ACCUMULATE_MNEMONICS, accumulate.size());
        for (Case c : accumulate) {
            Ir64Op.SmeArrayMultiVector op = assertInstanceOf(Ir64Op.SmeArrayMultiVector.class, decode(ALL, c.word()));
            assertEquals(0, op.zn(), c.assembly());
            // Os bits[20:16] são FIXOS nestes encodings (tamanho do elemento): qualquer outro valor é indefinido.
            int flipped = c.word() ^ FADD_FIXED_FIELD_BIT;
            assertThrows(UnsupportedOperationException.class, () -> decode(ALL, flipped), c.assembly());
        }
    }

    @Test
    void offsetIsScaledByTheNumberOfVectorsWrittenPerMember() {
        // fmlal za.s[w9, 6:7, vgx2] -> off = 6 (campo 3 × 2): a primeira variante da tabela tem o off máximo.
        Case max = cases().stream().filter(c -> c.op() == Op.FMLAL && c.count() == 2).findFirst().orElseThrow();
        assertEquals(6, max.off());
        // smlall za.s[w9, 4:7, vgx2] -> off = 4 (campo 1 × 4).
        Case quad = cases().stream().filter(c -> c.op() == Op.SMLALL_S && c.count() == 2).findFirst().orElseThrow();
        assertEquals(4, quad.off());
    }
}
