package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector.Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.9 + B18.10 — SME2 multi-vetor com resultado em `ZA`: "multiple and single" (`_n1`, 107 linhas) e "multiple"
/// (`_nn`, 100 linhas, `Zm` também é um grupo e o membro `r` usa `Z(zm + r)`; `FADD`/`FSUB`/`BFADD`/`BFSUB` não têm
/// `Zn`). As palavras-base de cada linha vêm do `aarch64-none-elf-as` (devkitA64; ver {@code
/// Aarch64SmeArrayVectorDecoderTest} e {@code Aarch64SmeArrayMultipleDecoderTest}); os campos `Wv`/`off`/`zn`/`zm`
/// são montados por {@link #word}. Cada operação é conferida contra uma referência escrita AQUI (inteiros em `long`,
/// ponto flutuante em `Math.fma`/`float`/`double` e decodificação própria de `FP8`) — NÃO contra
/// {@code SmeOuterProductOps} nem contra as operações de lane que o executor reusa. O endereçamento
/// (`((W arredondado para baixo a nsel) + off) MOD (SVL/n)`, membros a `SVL/n` linhas de distância) é re-derivado
/// do `get_zarray`/`do_azz_acc` do QEMU e conferido contra `ZA` INTEIRO: nenhuma linha fora das escritas muda.
class Aarch64SmeArrayVectorExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-azz-exec", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_I16I64,
            Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16, Aarch64Feature.SME_B16B16,
            Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;
    private static final int EC_SME = 0x1D;
    private static final int ESR_EC_SHIFT = 26;
    private static final int Z_REGISTERS = 32;
    private static final int[] SVLS = {256, 512};
    private static final int TRIALS = 6;
    private static final int RV_FIRST = 8;
    private static final int RV_COUNT = 4;
    private static final int RV_SHIFT = 13;
    private static final int ZN_SHIFT = 5;
    private static final int ZM_SHIFT = 16;
    private static final int ZM_COUNT = 16;
    private static final long M32 = 0xFFFF_FFFFL;
    private static final long M16 = 0xFFFFL;
    private static final long M8 = 0xFFL;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;

    /// `operação; n; palavra-base` (campos `zm`/`Wv`/`zn`/`off` zerados) — da saída do assembler.
    private static final String BASES = """
            ADD_S; 2; 0xC1201810
            ADD_S; 4; 0xC1301810
            ADD_D; 2; 0xC1601810
            ADD_D; 4; 0xC1701810
            SUB_S; 2; 0xC1201818
            SUB_S; 4; 0xC1301818
            SUB_D; 2; 0xC1601818
            SUB_D; 4; 0xC1701818
            FMLAL; 1; 0xC1200C00
            FMLAL; 2; 0xC1200800
            FMLAL; 4; 0xC1300800
            FMLSL; 1; 0xC1200C08
            FMLSL; 2; 0xC1200808
            FMLSL; 4; 0xC1300808
            BFMLAL; 1; 0xC1200C10
            BFMLAL; 2; 0xC1200810
            BFMLAL; 4; 0xC1300810
            BFMLSL; 1; 0xC1200C18
            BFMLSL; 2; 0xC1200818
            BFMLSL; 4; 0xC1300818
            FDOT; 2; 0xC1201000
            FDOT; 4; 0xC1301000
            BFDOT; 2; 0xC1201010
            BFDOT; 4; 0xC1301010
            USDOT; 2; 0xC1201408
            USDOT; 4; 0xC1301408
            SUDOT; 2; 0xC1201418
            SUDOT; 4; 0xC1301418
            SDOT_4B; 2; 0xC1201400
            SDOT_4B; 4; 0xC1301400
            UDOT_4B; 2; 0xC1201410
            UDOT_4B; 4; 0xC1301410
            SDOT_4H; 2; 0xC1601400
            SDOT_4H; 4; 0xC1701400
            UDOT_4H; 2; 0xC1601410
            UDOT_4H; 4; 0xC1701410
            SDOT_2H; 2; 0xC1601408
            SDOT_2H; 4; 0xC1701408
            UDOT_2H; 2; 0xC1601418
            UDOT_2H; 4; 0xC1701418
            SMLAL; 1; 0xC1600C00
            SMLAL; 2; 0xC1600800
            SMLAL; 4; 0xC1700800
            SMLSL; 1; 0xC1600C08
            SMLSL; 2; 0xC1600808
            SMLSL; 4; 0xC1700808
            UMLAL; 1; 0xC1600C10
            UMLAL; 2; 0xC1600810
            UMLAL; 4; 0xC1700810
            UMLSL; 1; 0xC1600C18
            UMLSL; 2; 0xC1600818
            UMLSL; 4; 0xC1700818
            SMLALL_S; 1; 0xC1200400
            SMLALL_S; 2; 0xC1200000
            SMLALL_S; 4; 0xC1300000
            SMLSLL_S; 1; 0xC1200408
            SMLSLL_S; 2; 0xC1200008
            SMLSLL_S; 4; 0xC1300008
            UMLALL_S; 1; 0xC1200410
            UMLALL_S; 2; 0xC1200010
            UMLALL_S; 4; 0xC1300010
            UMLSLL_S; 1; 0xC1200418
            UMLSLL_S; 2; 0xC1200018
            UMLSLL_S; 4; 0xC1300018
            SMLALL_D; 1; 0xC1600400
            SMLALL_D; 2; 0xC1600000
            SMLALL_D; 4; 0xC1700000
            SMLSLL_D; 1; 0xC1600408
            SMLSLL_D; 2; 0xC1600008
            SMLSLL_D; 4; 0xC1700008
            UMLALL_D; 1; 0xC1600410
            UMLALL_D; 2; 0xC1600010
            UMLALL_D; 4; 0xC1700010
            UMLSLL_D; 1; 0xC1600418
            UMLSLL_D; 2; 0xC1600018
            UMLSLL_D; 4; 0xC1700018
            USMLALL; 1; 0xC1200404
            USMLALL; 2; 0xC1200004
            USMLALL; 4; 0xC1300004
            SUMLALL; 2; 0xC1200014
            SUMLALL; 4; 0xC1300014
            BFMLA; 2; 0xC1601C00
            BFMLA; 4; 0xC1701C00
            BFMLS; 2; 0xC1601C08
            BFMLS; 4; 0xC1701C08
            FMLA_H; 2; 0xC1201C00
            FMLA_H; 4; 0xC1301C00
            FMLA_S; 2; 0xC1201800
            FMLA_S; 4; 0xC1301800
            FMLA_D; 2; 0xC1601800
            FMLA_D; 4; 0xC1701800
            FMLS_H; 2; 0xC1201C08
            FMLS_H; 4; 0xC1301C08
            FMLS_S; 2; 0xC1201808
            FMLS_S; 4; 0xC1301808
            FMLS_D; 2; 0xC1601808
            FMLS_D; 4; 0xC1701808
            FMLALL_B; 1; 0xC1300400
            FMLALL_B; 2; 0xC1200002
            FMLALL_B; 4; 0xC1300002
            FDOT_SB; 2; 0xC1201018
            FDOT_SB; 4; 0xC1301018
            FMLAL_HB; 1; 0xC1300C00
            FMLAL_HB; 2; 0xC1200804
            FMLAL_HB; 4; 0xC1300804
            FDOT_HB; 2; 0xC1201008
            FDOT_HB; 4; 0xC1301008
            """;

    /// Como `Zn`/`Zm` são codificados (espelha as três formas de `SmeArrayVectorRows.Form`, mas aqui escrito à parte).
    private enum Form { SINGLE, MULTIPLE, ACCUMULATE }

    /// As 100 linhas `_nn` (B18.10): `operação; n; palavra-base; forma` — os bits FIXOS do `.decode`.
    private static final String NN_BASES = """
            ADD_S; 2; 0xC1A01810; MULTIPLE
            ADD_S; 4; 0xC1A11810; MULTIPLE
            ADD_D; 2; 0xC1E01810; MULTIPLE
            ADD_D; 4; 0xC1E11810; MULTIPLE
            SUB_S; 2; 0xC1A01818; MULTIPLE
            SUB_S; 4; 0xC1A11818; MULTIPLE
            SUB_D; 2; 0xC1E01818; MULTIPLE
            SUB_D; 4; 0xC1E11818; MULTIPLE
            FMLAL; 2; 0xC1A00800; MULTIPLE
            FMLAL; 4; 0xC1A10800; MULTIPLE
            FMLSL; 2; 0xC1A00808; MULTIPLE
            FMLSL; 4; 0xC1A10808; MULTIPLE
            BFMLAL; 2; 0xC1A00810; MULTIPLE
            BFMLAL; 4; 0xC1A10810; MULTIPLE
            BFMLSL; 2; 0xC1A00818; MULTIPLE
            BFMLSL; 4; 0xC1A10818; MULTIPLE
            FDOT; 2; 0xC1A01000; MULTIPLE
            FDOT; 4; 0xC1A11000; MULTIPLE
            BFDOT; 2; 0xC1A01010; MULTIPLE
            BFDOT; 4; 0xC1A11010; MULTIPLE
            USDOT; 2; 0xC1A01408; MULTIPLE
            USDOT; 4; 0xC1A11408; MULTIPLE
            SDOT_4B; 2; 0xC1A01400; MULTIPLE
            SDOT_4B; 4; 0xC1A11400; MULTIPLE
            SDOT_4H; 2; 0xC1E01400; MULTIPLE
            SDOT_4H; 4; 0xC1E11400; MULTIPLE
            SDOT_2H; 2; 0xC1E01408; MULTIPLE
            SDOT_2H; 4; 0xC1E11408; MULTIPLE
            UDOT_4B; 2; 0xC1A01410; MULTIPLE
            UDOT_4B; 4; 0xC1A11410; MULTIPLE
            UDOT_4H; 2; 0xC1E01410; MULTIPLE
            UDOT_4H; 4; 0xC1E11410; MULTIPLE
            UDOT_2H; 2; 0xC1E01418; MULTIPLE
            UDOT_2H; 4; 0xC1E11418; MULTIPLE
            SMLAL; 2; 0xC1E00800; MULTIPLE
            SMLAL; 4; 0xC1E10800; MULTIPLE
            SMLSL; 2; 0xC1E00808; MULTIPLE
            SMLSL; 4; 0xC1E10808; MULTIPLE
            UMLAL; 2; 0xC1E00810; MULTIPLE
            UMLAL; 4; 0xC1E10810; MULTIPLE
            UMLSL; 2; 0xC1E00818; MULTIPLE
            UMLSL; 4; 0xC1E10818; MULTIPLE
            SMLALL_S; 2; 0xC1A00000; MULTIPLE
            SMLALL_D; 2; 0xC1E00000; MULTIPLE
            SMLALL_S; 4; 0xC1A10000; MULTIPLE
            SMLALL_D; 4; 0xC1E10000; MULTIPLE
            SMLSLL_S; 2; 0xC1A00008; MULTIPLE
            SMLSLL_D; 2; 0xC1E00008; MULTIPLE
            SMLSLL_S; 4; 0xC1A10008; MULTIPLE
            SMLSLL_D; 4; 0xC1E10008; MULTIPLE
            UMLALL_S; 2; 0xC1A00010; MULTIPLE
            UMLALL_D; 2; 0xC1E00010; MULTIPLE
            UMLALL_S; 4; 0xC1A10010; MULTIPLE
            UMLALL_D; 4; 0xC1E10010; MULTIPLE
            UMLSLL_S; 2; 0xC1A00018; MULTIPLE
            UMLSLL_D; 2; 0xC1E00018; MULTIPLE
            UMLSLL_S; 4; 0xC1A10018; MULTIPLE
            UMLSLL_D; 4; 0xC1E10018; MULTIPLE
            USMLALL; 2; 0xC1A00004; MULTIPLE
            USMLALL; 4; 0xC1A10004; MULTIPLE
            BFMLA; 2; 0xC1E01008; MULTIPLE
            FMLA_H; 2; 0xC1A01008; MULTIPLE
            FMLA_S; 2; 0xC1A01800; MULTIPLE
            FMLA_D; 2; 0xC1E01800; MULTIPLE
            BFMLA; 4; 0xC1E11008; MULTIPLE
            FMLA_H; 4; 0xC1A11008; MULTIPLE
            FMLA_S; 4; 0xC1A11800; MULTIPLE
            FMLA_D; 4; 0xC1E11800; MULTIPLE
            BFMLS; 2; 0xC1E01018; MULTIPLE
            FMLS_H; 2; 0xC1A01018; MULTIPLE
            FMLS_S; 2; 0xC1A01808; MULTIPLE
            FMLS_D; 2; 0xC1E01808; MULTIPLE
            BFMLS; 4; 0xC1E11018; MULTIPLE
            FMLS_H; 4; 0xC1A11018; MULTIPLE
            FMLS_S; 4; 0xC1A11808; MULTIPLE
            FMLS_D; 4; 0xC1E11808; MULTIPLE
            FMLALL_B; 2; 0xC1A00020; MULTIPLE
            FMLALL_B; 4; 0xC1A10020; MULTIPLE
            FDOT_SB; 2; 0xC1A01030; MULTIPLE
            FDOT_SB; 4; 0xC1A11030; MULTIPLE
            FMLAL_HB; 2; 0xC1A00820; MULTIPLE
            FMLAL_HB; 4; 0xC1A10820; MULTIPLE
            FDOT_HB; 2; 0xC1A01020; MULTIPLE
            FDOT_HB; 4; 0xC1A11020; MULTIPLE
            FADD_H; 2; 0xC1A41C00; ACCUMULATE
            FADD_S; 2; 0xC1A01C00; ACCUMULATE
            FADD_D; 2; 0xC1E01C00; ACCUMULATE
            FADD_H; 4; 0xC1A51C00; ACCUMULATE
            FADD_S; 4; 0xC1A11C00; ACCUMULATE
            FADD_D; 4; 0xC1E11C00; ACCUMULATE
            FSUB_H; 2; 0xC1A41C08; ACCUMULATE
            FSUB_S; 2; 0xC1A01C08; ACCUMULATE
            FSUB_D; 2; 0xC1E01C08; ACCUMULATE
            FSUB_H; 4; 0xC1A51C08; ACCUMULATE
            FSUB_S; 4; 0xC1A11C08; ACCUMULATE
            FSUB_D; 4; 0xC1E11C08; ACCUMULATE
            BFADD; 2; 0xC1E41C00; ACCUMULATE
            BFADD; 4; 0xC1E51C00; ACCUMULATE
            BFSUB; 2; 0xC1E41C08; ACCUMULATE
            BFSUB; 4; 0xC1E51C08; ACCUMULATE
            """;

    private record Shape(Op op, int n, int base, Form form) {
        int offsetBits() {
            int step = step(op);
            return step == 1 ? 3 : step == 2 ? (n == 1 ? 3 : 2) : (n == 1 ? 2 : 1);
        }

        @Override
        public String toString() {
            return op + " n=" + n + (form == Form.SINGLE ? "" : " " + form);
        }
    }

    private static List<Shape> shapes() {
        return Stream.concat(shapesOf(BASES, Form.SINGLE), shapesOf(NN_BASES, null)).toList();
    }

    private static Stream<Shape> shapesOf(String table, Form fixed) {
        return table.lines().map(String::strip).filter(line -> !line.isEmpty()).map(line -> {
            String[] f = line.split("; ");
            return new Shape(Op.valueOf(f[0]), Integer.parseInt(f[1]), (int) Long.decode(f[2]).longValue(),
                    fixed != null ? fixed : Form.valueOf(f[3]));
        });
    }

    static Stream<Arguments> shapesAndSvl() {
        return shapes().stream().flatMap(shape -> Arrays.stream(SVLS).mapToObj(svl -> Arguments.of(shape, svl)));
    }

    private static Shape shape(Op op, int n) {
        return shapes().stream().filter(s -> s.op() == op && s.n() == n && s.form() == Form.SINGLE).findFirst()
                .orElseThrow();
    }

    /// A forma `_nn` (B18.10) da operação.
    private static Shape shapeNn(Op op, int n) {
        return shapes().stream().filter(s -> s.op() == op && s.n() == n && s.form() != Form.SINGLE).findFirst()
                .orElseThrow();
    }

    /// Vetores de `ZA` escritos POR MEMBRO (`nsel`) — re-derivado do número de produtos de cada mnemônico, não do IR.
    private static int step(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, BFMLAL, BFMLSL, SMLAL, SMLSL, UMLAL, UMLSL, FMLAL_HB -> 2;
            case SMLALL_S, SMLSLL_S, UMLALL_S, UMLSLL_S, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D, USMLALL, SUMLALL,
                    FMLALL_B -> 4;
            default -> 1;
        };
    }

    /// Tamanho do elemento do vetor de `ZA` (`1` = half/bfloat16, `2` = word, `3` = doubleword).
    private static int accumulatorEsz(Op op) {
        return switch (op) {
            case ADD_D, SUB_D, SDOT_4H, UDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D, FMLA_D, FMLS_D, FADD_D,
                    FSUB_D -> ESZ_DOUBLE;
            case FMLA_H, FMLS_H, BFMLA, BFMLS, FMLAL_HB, FDOT_HB, FADD_H, FSUB_H, BFADD, BFSUB -> ESZ_HALF;
            default -> ESZ_SINGLE;
        };
    }

    /// `%zn_ax2`/`%zn_ax4`: grupo alinhado em `bits[9:6]`×2 / `bits[9:7]`×4.
    private static int groupZnField(int n, int firstRegister) {
        return n == 2 ? firstRegister / 2 << 6 : firstRegister / 4 << 7;
    }

    /// `%zm_ax2`/`%zm_ax4`: grupo alinhado em `bits[20:17]`×2 / `bits[20:18]`×4.
    private static int groupZmField(int n, int firstRegister) {
        return n == 2 ? firstRegister / 2 << 17 : firstRegister / 4 << 18;
    }

    /// `zn`/`zm` nas formas `_nn` são a PRIMEIRA `Z` de cada grupo (alinhada a `n`). Em `ACCUMULATE` só `zm` conta.
    private static int word(Shape shape, int register, int off, int zn, int zm) {
        int common = shape.base() | (register - RV_FIRST) << RV_SHIFT | off;
        return switch (shape.form()) {
            case SINGLE -> common | zm << ZM_SHIFT | zn << ZN_SHIFT;
            case MULTIPLE -> common | groupZnField(shape.n(), zn) | groupZmField(shape.n(), zm);
            case ACCUMULATE -> common | groupZnField(shape.n(), zm);
        };
    }

    private static Aarch64Core core(int svlBits, long svcr) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), ALL, svlBits,
                svlBits);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(svcr);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(ALL).step(core);
    }

    // ── formatos dos operandos ───────────────────────────────────────────────────────────────────

    /// `INT` = palavras aleatórias de 64 bits. Os demais usam valores EXATOS (inteiros pequenos/quartos, potências de
    /// dois de `FP8`), de modo que o resultado de referência não dependa de modo de arredondamento nem de `double`
    /// round: o que se confere é a escolha das lanes, o sinal e o endereçamento.
    private enum Fmt { INT, HALF, BF16, SINGLE, SINGLE_RANDOM, DOUBLE, FP8 }

    private static Fmt source(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, FDOT, FMLA_H, FMLS_H -> Fmt.HALF;
            case BFMLAL, BFMLSL, BFDOT, BFMLA, BFMLS -> Fmt.BF16;
            case FMLA_S, FMLS_S -> Fmt.SINGLE_RANDOM;
            case FMLA_D, FMLS_D, FADD_D, FSUB_D -> Fmt.DOUBLE;
            case FADD_H, FSUB_H -> Fmt.HALF;
            case BFADD, BFSUB -> Fmt.BF16;
            case FADD_S, FSUB_S -> Fmt.SINGLE;
            case FMLALL_B, FDOT_SB, FMLAL_HB, FDOT_HB -> Fmt.FP8;
            default -> Fmt.INT;
        };
    }

    private static Fmt accumulator(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, BFMLAL, BFMLSL, FDOT, BFDOT, FMLALL_B, FDOT_SB -> Fmt.SINGLE;
            case FMLA_H, FMLS_H, FMLAL_HB, FDOT_HB, FADD_H, FSUB_H -> Fmt.HALF;
            case BFMLA, BFMLS, BFADD, BFSUB -> Fmt.BF16;
            case FMLA_S, FMLS_S -> Fmt.SINGLE_RANDOM;
            case FMLA_D, FMLS_D, FADD_D, FSUB_D -> Fmt.DOUBLE;
            case FADD_S, FSUB_S -> Fmt.SINGLE;
            default -> Fmt.INT;
        };
    }

    private static int elementSize(Fmt format) {
        return switch (format) {
            case INT, DOUBLE -> ESZ_DOUBLE;
            case HALF, BF16 -> ESZ_HALF;
            case SINGLE, SINGLE_RANDOM -> ESZ_SINGLE;
            case FP8 -> ESZ_BYTE;
        };
    }

    private static long value(Fmt format, Random random) {
        return switch (format) {
            case INT -> random.nextLong();
            case HALF -> Float.floatToFloat16((random.nextInt(33) - 16) / 4f) & M16;
            case BF16 -> (Float.floatToRawIntBits((random.nextInt(13) - 6) / 2f) >>> 16) & M16;
            case SINGLE -> Float.floatToRawIntBits((random.nextInt(33) - 16) / 4f) & M32;
            case SINGLE_RANDOM -> Float.floatToRawIntBits(random.nextFloat() * 200f - 100f) & M32;
            case DOUBLE -> Double.doubleToRawLongBits(random.nextDouble() * 200.0 - 100.0);
            // E5M2: expoente 14..16 e fração 0 (±0,5 / ±1 / ±2) — somas de até 4 produtos continuam exatas em half.
            case FP8 -> (random.nextBoolean() ? 0x80 : 0) | (14 + random.nextInt(3)) << 2;
        };
    }

    // ── acesso a Z e ZA (independente do código de produção) ─────────────────────────────────────

    private static long mask(int bits) {
        return bits == Long.SIZE ? -1L : (1L << bits) - 1L;
    }

    private static long lane(long[] words, int esz, int index) {
        int bits = Byte.SIZE << esz;
        int bit = index * bits;
        return (words[bit / Long.SIZE] >>> (bit % Long.SIZE)) & mask(bits);
    }

    private static void put(long[] words, int esz, int index, long value) {
        int bits = Byte.SIZE << esz;
        int bit = index * bits;
        long place = mask(bits) << (bit % Long.SIZE);
        words[bit / Long.SIZE] = words[bit / Long.SIZE] & ~place | value << (bit % Long.SIZE) & place;
    }

    private static long[][] snapshotZ(Aarch64Core core) {
        long[][] regs = new long[Z_REGISTERS][core.streamingVectorLengthBytes() / Long.BYTES];
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < regs[z].length; w++) {
                regs[z][w] = core.scalable().zWord(z, w);
            }
        }
        return regs;
    }

    private static long[][] snapshotZa(Aarch64Core core) {
        int rows = core.streamingVectorLengthBytes();
        int rowWords = rows / Long.BYTES;
        long[][] za = new long[rows][rowWords];
        for (int row = 0; row < rows; row++) {
            for (int w = 0; w < rowWords; w++) {
                za[row][w] = core.matrix().zaWord(row * rowWords + w);
            }
        }
        return za;
    }

    private static void fillRandom(Aarch64Core core, Op op, Random random) {
        Fmt src = source(op);
        int esz = elementSize(src);
        for (int z = 0; z < Z_REGISTERS; z++) {
            long[] words = new long[core.streamingVectorLengthBytes() / Long.BYTES];
            for (int i = 0; i < (core.streamingVectorLengthBytes() >>> esz); i++) {
                put(words, esz, i, value(src, random));
            }
            for (int w = 0; w < words.length; w++) {
                core.scalable().setZWord(z, w, words[w]);
            }
        }
        Fmt acc = accumulator(op);
        int accEsz = accumulatorEsz(op);
        int rows = core.streamingVectorLengthBytes();
        int rowWords = rows / Long.BYTES;
        for (int row = 0; row < rows; row++) {
            long[] words = new long[rowWords];
            for (int i = 0; i < (rows >>> accEsz); i++) {
                put(words, accEsz, i, value(acc, random));
            }
            for (int w = 0; w < rowWords; w++) {
                core.matrix().setZaWord(row * rowWords + w, words[w]);
            }
        }
    }

    // ── referência ───────────────────────────────────────────────────────────────────────────────

    private static float h(long bits) {
        return Float.float16ToFloat((short) bits);
    }

    private static float bf(long bits) {
        return Float.intBitsToFloat((int) (bits << 16));
    }

    private static float f(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    private static double d(long bits) {
        return Double.longBitsToDouble(bits);
    }

    private static long fb(float v) {
        return Float.floatToRawIntBits(v) & M32;
    }

    private static long hb(float v) {
        return Float.floatToFloat16(v) & M16;
    }

    private static long bb(float v) {
        return (Float.floatToRawIntBits(v) >>> 16) & M16;
    }

    /// `E5M2` normalizado: `(−1)^s × 2^(e−15) × (1 + f/4)`.
    private static double fp8(long bits) {
        double magnitude = Math.scalb(1.0 + (bits & 3) / 4.0, (int) (bits >> 2 & 31) - 15);
        return (bits & 0x80) != 0 ? -magnitude : magnitude;
    }

    private static long s8(long v) {
        return (byte) v;
    }

    private static long u8(long v) {
        return v & M8;
    }

    private static long s16(long v) {
        return (short) v;
    }

    private static long u16(long v) {
        return v & M16;
    }

    /// Novo valor do elemento `e` do vetor de `ZA` escrito pelo vetor `sel` do membro cujo `Zn` é `n` (`Zm` = `m`).
    private static long reference(Op op, long acc, int e, int sel, long[] n, long[] m) {
        return switch (op) {
            case ADD_S -> lane(n, ESZ_SINGLE, e) + lane(m, ESZ_SINGLE, e) & M32;
            case SUB_S -> lane(n, ESZ_SINGLE, e) - lane(m, ESZ_SINGLE, e) & M32;
            case ADD_D -> lane(n, ESZ_DOUBLE, e) + lane(m, ESZ_DOUBLE, e);
            case SUB_D -> lane(n, ESZ_DOUBLE, e) - lane(m, ESZ_DOUBLE, e);
            case FMLAL -> fb(Math.fma(h(lane(n, ESZ_HALF, 2 * e + sel)), h(lane(m, ESZ_HALF, 2 * e + sel)), f(acc)));
            case FMLSL -> fb(Math.fma(-h(lane(n, ESZ_HALF, 2 * e + sel)), h(lane(m, ESZ_HALF, 2 * e + sel)), f(acc)));
            case BFMLAL -> fb(Math.fma(bf(lane(n, ESZ_HALF, 2 * e + sel)), bf(lane(m, ESZ_HALF, 2 * e + sel)), f(acc)));
            case BFMLSL ->
                    fb(Math.fma(-bf(lane(n, ESZ_HALF, 2 * e + sel)), bf(lane(m, ESZ_HALF, 2 * e + sel)), f(acc)));
            case FDOT -> fb((float) (f(acc) + (double) h(lane(n, ESZ_HALF, 2 * e)) * h(lane(m, ESZ_HALF, 2 * e))
                    + (double) h(lane(n, ESZ_HALF, 2 * e + 1)) * h(lane(m, ESZ_HALF, 2 * e + 1))));
            case BFDOT -> fb((float) (f(acc) + (double) bf(lane(n, ESZ_HALF, 2 * e)) * bf(lane(m, ESZ_HALF, 2 * e))
                    + (double) bf(lane(n, ESZ_HALF, 2 * e + 1)) * bf(lane(m, ESZ_HALF, 2 * e + 1))));
            case USDOT -> acc + bytes4(n, m, e, false, true) & M32;
            case SUDOT -> acc + bytes4(n, m, e, true, false) & M32;
            case SDOT_4B -> acc + bytes4(n, m, e, true, true) & M32;
            case UDOT_4B -> acc + bytes4(n, m, e, false, false) & M32;
            case SDOT_4H -> acc + halves4(n, m, e, true);
            case UDOT_4H -> acc + halves4(n, m, e, false);
            case SDOT_2H -> acc + s16(lane(n, ESZ_HALF, 2 * e)) * s16(lane(m, ESZ_HALF, 2 * e))
                    + s16(lane(n, ESZ_HALF, 2 * e + 1)) * s16(lane(m, ESZ_HALF, 2 * e + 1)) & M32;
            case UDOT_2H -> acc + u16(lane(n, ESZ_HALF, 2 * e)) * u16(lane(m, ESZ_HALF, 2 * e))
                    + u16(lane(n, ESZ_HALF, 2 * e + 1)) * u16(lane(m, ESZ_HALF, 2 * e + 1)) & M32;
            case SMLAL -> acc + s16(lane(n, ESZ_HALF, 2 * e + sel)) * s16(lane(m, ESZ_HALF, 2 * e + sel)) & M32;
            case SMLSL -> acc - s16(lane(n, ESZ_HALF, 2 * e + sel)) * s16(lane(m, ESZ_HALF, 2 * e + sel)) & M32;
            case UMLAL -> acc + u16(lane(n, ESZ_HALF, 2 * e + sel)) * u16(lane(m, ESZ_HALF, 2 * e + sel)) & M32;
            case UMLSL -> acc - u16(lane(n, ESZ_HALF, 2 * e + sel)) * u16(lane(m, ESZ_HALF, 2 * e + sel)) & M32;
            case SMLALL_S -> acc + s8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case SMLSLL_S -> acc - s8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case UMLALL_S -> acc + u8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case UMLSLL_S -> acc - u8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case USMLALL -> acc + u8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case SUMLALL -> acc + s8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, 4 * e + sel)) & M32;
            case SMLALL_D -> acc + s16(lane(n, ESZ_HALF, 4 * e + sel)) * s16(lane(m, ESZ_HALF, 4 * e + sel));
            case SMLSLL_D -> acc - s16(lane(n, ESZ_HALF, 4 * e + sel)) * s16(lane(m, ESZ_HALF, 4 * e + sel));
            case UMLALL_D -> acc + u16(lane(n, ESZ_HALF, 4 * e + sel)) * u16(lane(m, ESZ_HALF, 4 * e + sel));
            case UMLSLL_D -> acc - u16(lane(n, ESZ_HALF, 4 * e + sel)) * u16(lane(m, ESZ_HALF, 4 * e + sel));
            case BFMLA -> bb(bf(acc) + bf(lane(n, ESZ_HALF, e)) * bf(lane(m, ESZ_HALF, e)));
            case BFMLS -> bb(bf(acc) - bf(lane(n, ESZ_HALF, e)) * bf(lane(m, ESZ_HALF, e)));
            case FMLA_H -> hb(h(acc) + h(lane(n, ESZ_HALF, e)) * h(lane(m, ESZ_HALF, e)));
            case FMLS_H -> hb(h(acc) - h(lane(n, ESZ_HALF, e)) * h(lane(m, ESZ_HALF, e)));
            case FMLA_S -> fb(Math.fma(f(lane(n, ESZ_SINGLE, e)), f(lane(m, ESZ_SINGLE, e)), f(acc)));
            case FMLS_S -> fb(Math.fma(-f(lane(n, ESZ_SINGLE, e)), f(lane(m, ESZ_SINGLE, e)), f(acc)));
            case FMLA_D -> Double.doubleToRawLongBits(
                    Math.fma(d(lane(n, ESZ_DOUBLE, e)), d(lane(m, ESZ_DOUBLE, e)), d(acc)));
            case FMLS_D -> Double.doubleToRawLongBits(
                    Math.fma(-d(lane(n, ESZ_DOUBLE, e)), d(lane(m, ESZ_DOUBLE, e)), d(acc)));
            case FMLALL_B -> fb((float) (f(acc) + fp8(lane(n, ESZ_BYTE, 4 * e + sel)) * fp8(lane(m, ESZ_BYTE, 4 * e + sel))));
            case FDOT_SB -> {
                double sum = f(acc);
                for (int k = 0; k < 4; k++) {
                    sum += fp8(lane(n, ESZ_BYTE, 4 * e + k)) * fp8(lane(m, ESZ_BYTE, 4 * e + k));
                }
                yield fb((float) sum);
            }
            case FMLAL_HB -> hb((float) (h(acc) + fp8(lane(n, ESZ_BYTE, 2 * e + sel)) * fp8(lane(m, ESZ_BYTE, 2 * e + sel))));
            case FDOT_HB -> {
                double sum = h(acc);
                for (int k = 0; k < 2; k++) {
                    sum += fp8(lane(n, ESZ_BYTE, 2 * e + k)) * fp8(lane(m, ESZ_BYTE, 2 * e + k));
                }
                yield hb((float) sum);
            }
            // `FADD`/`FSUB`/`BFADD`/`BFSUB` (B18.10): `ZA ±= Zm`, SEM `Zn` (só `m` entra).
            case FADD_H -> hb(h(acc) + h(lane(m, ESZ_HALF, e)));
            case FSUB_H -> hb(h(acc) - h(lane(m, ESZ_HALF, e)));
            case FADD_S -> fb(f(acc) + f(lane(m, ESZ_SINGLE, e)));
            case FSUB_S -> fb(f(acc) - f(lane(m, ESZ_SINGLE, e)));
            case FADD_D -> Double.doubleToRawLongBits(d(acc) + d(lane(m, ESZ_DOUBLE, e)));
            case FSUB_D -> Double.doubleToRawLongBits(d(acc) - d(lane(m, ESZ_DOUBLE, e)));
            case BFADD -> bb(bf(acc) + bf(lane(m, ESZ_HALF, e)));
            case BFSUB -> bb(bf(acc) - bf(lane(m, ESZ_HALF, e)));
        };
    }

    private static long bytes4(long[] n, long[] m, int e, boolean nSigned, boolean mSigned) {
        long sum = 0;
        for (int k = 0; k < 4; k++) {
            long a = lane(n, ESZ_BYTE, 4 * e + k);
            long b = lane(m, ESZ_BYTE, 4 * e + k);
            sum += (nSigned ? s8(a) : u8(a)) * (mSigned ? s8(b) : u8(b));
        }
        return sum;
    }

    private static long halves4(long[] n, long[] m, int e, boolean signed) {
        long sum = 0;
        for (int k = 0; k < 4; k++) {
            long a = lane(n, ESZ_HALF, 4 * e + k);
            long b = lane(m, ESZ_HALF, 4 * e + k);
            sum += signed ? s16(a) * s16(b) : u16(a) * u16(b);
        }
        return sum;
    }

    /// Aplica a instrução ao modelo: devolve o `ZA` esperado (e as linhas escritas em `written`).
    private static long[][] expectedZa(Shape shape, int svl, long registerValue, int off, int zn, int zm,
            long[][] z, long[][] before, Set<Integer> written) {
        Op op = shape.op();
        int svlBytes = svl / Byte.SIZE;
        int step = step(op);
        int rowsPerMember = svlBytes / shape.n();
        int base = (int) (((registerValue & M32 & ~(step - 1L)) + off) % rowsPerMember);
        int accEsz = accumulatorEsz(op);
        long[][] expected = new long[before.length][];
        for (int row = 0; row < before.length; row++) {
            expected[row] = before[row].clone();
        }
        for (int member = 0; member < shape.n(); member++) {
            long[] n = z[(zn + member) % Z_REGISTERS];
            long[] m = z[shape.form() == Form.SINGLE ? zm : zm + member];
            for (int sel = 0; sel < step; sel++) {
                int row = base + member * rowsPerMember + sel;
                written.add(row);
                for (int e = 0; e < (svlBytes >>> accEsz); e++) {
                    long acc = lane(expected[row], accEsz, e);
                    put(expected[row], accEsz, e, reference(op, acc, e, sel, n, m));
                }
            }
        }
        return expected;
    }

    private static void assertZa(long[][] expected, Aarch64Core core, Set<Integer> written, String what) {
        int rowWords = expected[0].length;
        for (int row = 0; row < expected.length; row++) {
            for (int w = 0; w < rowWords; w++) {
                assertEquals(expected[row][w], core.matrix().zaWord(row * rowWords + w),
                        what + ": linha " + row + " palavra " + w + (written.contains(row) ? " (escrita)" : " (INTACTA)"));
            }
        }
    }

    private static void assertZUnchanged(long[][] before, Aarch64Core core) {
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < before[z].length; w++) {
                assertEquals(before[z][w], core.scalable().zWord(z, w), "Z" + z + " palavra " + w + " não podia mudar");
            }
        }
    }

    // ── todas as 207 formas (107 `_n1` + 100 `_nn`) × SVL 256/512 contra a referência ────────────────────────────────────

    @ParameterizedTest(name = "{0} @SVL{1}")
    @MethodSource("shapesAndSvl")
    void matchesTheReferenceForEveryEncoding(Shape shape, int svl) {
        Random random = new Random(shape.op().ordinal() * 1_000_003L + shape.n() * 101L + svl);
        for (int trial = 0; trial < TRIALS; trial++) {
            Aarch64Core core = core(svl, SVCR_SM | SVCR_ZA);
            fillRandom(core, shape.op(), random);
            int register = RV_FIRST + random.nextInt(RV_COUNT);
            long registerValue = trial == 0 ? 0L : random.nextLong();
            core.setX(register, registerValue);
            int off = random.nextInt(1 << shape.offsetBits());
            int zn;
            int zm;
            if (shape.form() == Form.SINGLE) {
                zn = trial == 1 ? Z_REGISTERS - 1 : random.nextInt(Z_REGISTERS);
                zm = random.nextInt(ZM_COUNT);
            } else {
                // Grupos ALINHADOS a n: o último grupo do banco no trial 1, qualquer um nos demais (podem coincidir).
                int groups = Z_REGISTERS / shape.n();
                zn = (trial == 1 ? groups - 1 : random.nextInt(groups)) * shape.n();
                zm = (trial == 1 ? 0 : random.nextInt(groups)) * shape.n();
            }
            long[][] z = snapshotZ(core);
            Set<Integer> written = new HashSet<>();
            int scaledOff = off * step(shape.op());
            long[][] expected = expectedZa(shape, svl, registerValue, scaledOff, zn, zm, z, snapshotZa(core), written);
            run(core, word(shape, register, off, zn, zm));
            String what = shape + " W" + register + "=" + (registerValue & M32) + " off=" + scaledOff + " zn=" + zn
                    + " zm=" + zm;
            assertZa(expected, core, written, what);
            assertZUnchanged(z, core);
            assertEquals(shape.n() * step(shape.op()), written.size(), what + ": nº de vetores escritos");
        }
    }

    // ── propriedades pedidas no Aceite ───────────────────────────────────────────────────────────

    private static Aarch64Core zeroedCore(int svl) {
        return core(svl, SVCR_SM | SVCR_ZA);
    }

    private static void fillZ(Aarch64Core core, int z, int esz, long value) {
        long[] words = new long[core.streamingVectorLengthBytes() / Long.BYTES];
        for (int i = 0; i < (core.streamingVectorLengthBytes() >>> esz); i++) {
            put(words, esz, i, value);
        }
        for (int w = 0; w < words.length; w++) {
            core.scalable().setZWord(z, w, words[w]);
        }
    }

    private static Set<Integer> changedRows(Aarch64Core core, long[][] before) {
        Set<Integer> changed = new HashSet<>();
        long[][] after = snapshotZa(core);
        for (int row = 0; row < after.length; row++) {
            if (!Arrays.equals(before[row], after[row])) {
                changed.add(row);
            }
        }
        return changed;
    }

    @Test
    void multiplyAccumulateAccumulatesAndAddSubWrite() {
        for (int svl : SVLS) {
            Aarch64Core core = zeroedCore(svl);
            fillZ(core, 3, ESZ_BYTE, 1);
            fillZ(core, 9, ESZ_BYTE, 1);
            Shape smlall = shape(Op.SMLALL_S, 2);
            run(core, word(smlall, RV_FIRST, 0, 3, 9));
            run(core, word(smlall, RV_FIRST, 0, 3, 9));
            assertEquals(2L, lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "executar duas vezes dobra o delta");

            Aarch64Core addCore = zeroedCore(svl);
            fillZ(addCore, 3, ESZ_SINGLE, 5);
            fillZ(addCore, 9, ESZ_SINGLE, 7);
            Shape add = shape(Op.ADD_S, 2);
            run(addCore, word(add, RV_FIRST, 0, 3, 9));
            run(addCore, word(add, RV_FIRST, 0, 3, 9));
            assertEquals(12L, lane(snapshotZa(addCore)[0], ESZ_SINGLE, 0), "ADD ESCREVE Zn+Zm: não acumula");
        }
    }

    @Test
    void dotProductsSumTheRightNumberOfProducts() {
        Aarch64Core core = zeroedCore(256);
        fillZ(core, 3, ESZ_BYTE, 1);
        fillZ(core, 9, ESZ_BYTE, 1);
        run(core, word(shape(Op.SDOT_4B, 2), RV_FIRST, 0, 3, 9));
        assertEquals(4L, lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "SDOT 4B soma 4 produtos");

        Aarch64Core halves = zeroedCore(256);
        fillZ(halves, 3, ESZ_HALF, 1);
        fillZ(halves, 9, ESZ_HALF, 1);
        run(halves, word(shape(Op.SDOT_2H, 2), RV_FIRST, 0, 3, 9));
        assertEquals(2L, lane(snapshotZa(halves)[0], ESZ_SINGLE, 0), "SDOT 2H soma 2 produtos");
    }

    @Test
    void usdotAndSudotDifferInWhichOperandIsSigned() {
        // n = 0xFF, m = 0x02: USDOT = 4 × (255 × 2) = 2040; SUDOT = 4 × (−1 × 2) = −8.
        Aarch64Core us = zeroedCore(256);
        fillZ(us, 3, ESZ_BYTE, 0xFF);
        fillZ(us, 9, ESZ_BYTE, 0x02);
        run(us, word(shape(Op.USDOT, 2), RV_FIRST, 0, 3, 9));
        assertEquals(2040L, lane(snapshotZa(us)[0], ESZ_SINGLE, 0));
        Aarch64Core su = zeroedCore(256);
        fillZ(su, 3, ESZ_BYTE, 0xFF);
        fillZ(su, 9, ESZ_BYTE, 0x02);
        run(su, word(shape(Op.SUDOT, 2), RV_FIRST, 0, 3, 9));
        assertEquals(-8L & M32, lane(snapshotZa(su)[0], ESZ_SINGLE, 0));
    }

    @Test
    void offsetScaleAndMemberStrideAreRespected() {
        for (int svl : SVLS) {
            int rowsPerMember = svl / Byte.SIZE / 2;
            // SMLAL vgx2: 2 vetores por membro; off = 1 × 2 -> base 2; o membro 1 fica `rowsPerMember` linhas depois.
            Aarch64Core x2 = zeroedCore(svl);
            fillZ(x2, 3, ESZ_HALF, 1);
            fillZ(x2, 4, ESZ_HALF, 1);
            fillZ(x2, 9, ESZ_HALF, 1);
            long[][] before = snapshotZa(x2);
            run(x2, word(shape(Op.SMLAL, 2), RV_FIRST, 1, 3, 9));
            assertEquals(Set.of(2, 3, 2 + rowsPerMember, 3 + rowsPerMember), changedRows(x2, before));

            // SMLALL vgx4: 4 vetores por membro; off = 1 × 4 -> base 4.
            int rowsPerMember4 = svl / Byte.SIZE / 4;
            Aarch64Core x4 = zeroedCore(svl);
            for (int z = 3; z <= 6; z++) {
                fillZ(x4, z, ESZ_BYTE, 1);
            }
            fillZ(x4, 9, ESZ_BYTE, 1);
            long[][] before4 = snapshotZa(x4);
            run(x4, word(shape(Op.SMLALL_S, 4), RV_FIRST, 1, 3, 9));
            Set<Integer> expected = new HashSet<>();
            for (int member = 0; member < 4; member++) {
                for (int i = 0; i < 4; i++) {
                    expected.add(4 + member * rowsPerMember4 + i);
                }
            }
            assertEquals(expected, changedRows(x4, before4));
        }
    }

    @Test
    void registerValueIsRoundedDownToTheVectorsWrittenAndWrapsModuloTheGroupStride() {
        Aarch64Core core = zeroedCore(256);
        fillZ(core, 3, ESZ_BYTE, 1);
        fillZ(core, 9, ESZ_BYTE, 1);
        core.setX(RV_FIRST, 7L); // 7 arredondado a múltiplo de 4 = 4
        long[][] before = snapshotZa(core);
        run(core, word(shape(Op.SMLALL_S, 1), RV_FIRST, 0, 3, 9));
        assertEquals(Set.of(4, 5, 6, 7), changedRows(core, before));

        Aarch64Core wrap = zeroedCore(256);
        fillZ(wrap, 3, ESZ_BYTE, 1);
        fillZ(wrap, 9, ESZ_BYTE, 1);
        wrap.setX(RV_FIRST, 0xFFFF_FFFF_0000_0021L); // só os 32 bits baixos contam: 0x21 = 33; 33 MOD 8 = 1 (vgx4)
        long[][] beforeWrap = snapshotZa(wrap);
        run(wrap, word(shape(Op.ADD_S, 4), RV_FIRST, 0, 3, 9));
        // ADD vgx4 não arredonda (nsel = 1): base = 33 MOD (32/4) = 1; membros em 1, 9, 17, 25.
        assertEquals(Set.of(1, 9, 17, 25), changedRows(wrap, beforeWrap));
    }

    @Test
    void znWrapsAroundZ31() {
        Aarch64Core core = zeroedCore(256);
        fillZ(core, 31, ESZ_SINGLE, 3);
        fillZ(core, 0, ESZ_SINGLE, 4);
        fillZ(core, 9, ESZ_SINGLE, 1);
        run(core, word(shape(Op.ADD_S, 2), RV_FIRST, 0, 31, 9));
        long[][] za = snapshotZa(core);
        assertEquals(4L, lane(za[0], ESZ_SINGLE, 0), "membro 0 = Z31 + Zm");
        assertEquals(5L, lane(za[32 / 2], ESZ_SINGLE, 0), "membro 1 = Z0 + Zm (deu a volta)");
    }

    @Test
    void floatingPointNeverAccumulatesIntoFpsrAndProducesTheDefaultNan() {
        Aarch64Core core = zeroedCore(256);
        long fpsrBefore = core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR);
        fillZ(core, 3, ESZ_SINGLE, Float.floatToRawIntBits(Float.POSITIVE_INFINITY) & M32);
        fillZ(core, 9, ESZ_SINGLE, 0L);
        run(core, word(shape(Op.FMLA_S, 2), RV_FIRST, 0, 3, 9));
        assertEquals(0x7FC00000L, lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "inf × 0: NaN padrão (FPST_ZA tem DN)");
        assertEquals(fpsrBefore, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR),
                "FPST_ZA não acumula flags em FPSR");
    }

    private static final int FPCR_FZ16_BIT = 19;
    private static final long FPMR_BOTH_E4M3 = 9L; // F8S1 = F8S2 = E4M3

    @Test
    void fmlalHonoursFz16OnTheHalfPrecisionInputsAndKeepsTheZeroSign() {
        // n = −2^-24 (o menor denormal negativo), m = 1.0, acumulador = −0: com FZ16 a entrada vira −0 e o resultado
        // é −0; sem FZ16 o produto é um denormal de verdade (−2^-24) e o resultado NÃO é zero.
        Shape fmlal = shape(Op.FMLAL, 1);
        Aarch64Core flushed = zeroedCore(256);
        flushed.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, 1L << FPCR_FZ16_BIT);
        fillZ(flushed, 3, ESZ_HALF, 0x8001L);
        fillZ(flushed, 9, ESZ_HALF, 0x3C00L);
        for (int i = 0; i < flushed.streamingVectorLengthBytes() / Float.BYTES; i++) {
            flushed.matrix().setZaWord(i / 2, flushed.matrix().zaWord(i / 2) | 0x8000_0000L << (i % 2 * Integer.SIZE));
        }
        run(flushed, word(fmlal, RV_FIRST, 0, 3, 9));
        assertEquals(0x8000_0000L, lane(snapshotZa(flushed)[0], ESZ_SINGLE, 0), "FZ16: −0 preservado");

        Aarch64Core exact = zeroedCore(256);
        fillZ(exact, 3, ESZ_HALF, 0x8001L);
        fillZ(exact, 9, ESZ_HALF, 0x3C00L);
        run(exact, word(fmlal, RV_FIRST, 0, 3, 9));
        assertEquals(fb(-0x1p-24f), lane(snapshotZa(exact)[0], ESZ_SINGLE, 0), "sem FZ16: o denormal conta");
    }

    @Test
    void fz16FlushesOnlyDenormalHalves() {
        Shape fmlal = shape(Op.FMLAL, 1);
        // {entrada n, resultado esperado de n × 1,0 + 0}: +denormal → +0; zero → 0; normal 1,0 → 1,0.
        long[][] cases = {{0x0001L, 0L}, {0x0000L, 0L}, {0x3C00L, fb(1.0f)}};
        for (long[] c : cases) {
            Aarch64Core core = zeroedCore(256);
            core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, 1L << FPCR_FZ16_BIT);
            fillZ(core, 3, ESZ_HALF, c[0]);
            fillZ(core, 9, ESZ_HALF, 0x3C00L);
            run(core, word(fmlal, RV_FIRST, 0, 3, 9));
            assertEquals(c[1], lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "n = 0x" + Long.toHexString(c[0]));
        }
    }

    @Test
    void fp8UsesTheFormatsSelectedInFpmr() {
        // 0x38 = 1,0 e 0x40 = 2,0 em E4M3, mas 0,5 e 2,0 em E5M2: o produto vale 2,0 (E4M3) ou 1,0 (E5M2).
        Shape fmlall = shape(Op.FMLALL_B, 1);
        Aarch64Core e4m3 = zeroedCore(256);
        e4m3.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPMR, FPMR_BOTH_E4M3);
        fillZ(e4m3, 3, ESZ_BYTE, 0x38L);
        fillZ(e4m3, 9, ESZ_BYTE, 0x40L);
        run(e4m3, word(fmlall, RV_FIRST, 0, 3, 9));
        assertEquals(fb(2.0f), lane(snapshotZa(e4m3)[0], ESZ_SINGLE, 0));

        Aarch64Core e5m2 = zeroedCore(256);
        fillZ(e5m2, 3, ESZ_BYTE, 0x38L);
        fillZ(e5m2, 9, ESZ_BYTE, 0x40L);
        run(e5m2, word(fmlall, RV_FIRST, 0, 3, 9));
        assertEquals(fb(1.0f), lane(snapshotZa(e5m2)[0], ESZ_SINGLE, 0));
    }

    // ── forma `_nn` (B18.10) ─────────────────────────────────────────────────────────────────────

    private static void fillZaRow(Aarch64Core core, int row, int esz, long value) {
        int rowWords = core.streamingVectorLengthBytes() / Long.BYTES;
        long[] words = new long[rowWords];
        for (int i = 0; i < (core.streamingVectorLengthBytes() >>> esz); i++) {
            put(words, esz, i, value);
        }
        for (int w = 0; w < rowWords; w++) {
            core.matrix().setZaWord(row * rowWords + w, words[w]);
        }
    }

    @Test
    void theTwoGroupsAreIndependentAndNeitherIsModified() {
        for (int svl : SVLS) {
            int rowsPerMember = svl / Byte.SIZE / 4;
            Aarch64Core core = zeroedCore(svl);
            // Zn = Z0..Z3 (1..4), Zm = Z8..Z11 (10..40): cada vetor de ZA recebe Zn[i] + Zm[i].
            for (int i = 0; i < 4; i++) {
                fillZ(core, i, ESZ_SINGLE, i + 1);
                fillZ(core, 8 + i, ESZ_SINGLE, 10L * (i + 1));
            }
            long[][] z = snapshotZ(core);
            long[][] before = snapshotZa(core);
            Shape add = shapeNn(Op.ADD_S, 4);
            run(core, word(add, RV_FIRST, 0, 0, 8));
            run(core, word(add, RV_FIRST, 0, 0, 8));
            long[][] za = snapshotZa(core);
            Set<Integer> rows = new HashSet<>();
            for (int member = 0; member < 4; member++) {
                int row = member * rowsPerMember;
                rows.add(row);
                assertEquals(11L * (member + 1), lane(za[row], ESZ_SINGLE, 0),
                        "membro " + member + ": Zn[i] + Zm[i], e executar duas vezes NÃO dobra (ADD escreve)");
            }
            assertEquals(rows, changedRows(core, before));
            assertZUnchanged(z, core);
        }
    }

    @Test
    void eachMemberUsesItsOwnZmRegister() {
        for (int svl : SVLS) {
            int rowsPerMember = svl / Byte.SIZE / 2;
            Aarch64Core core = zeroedCore(svl);
            fillZ(core, 2, ESZ_HALF, 1);
            fillZ(core, 3, ESZ_HALF, 1);
            fillZ(core, 8, ESZ_HALF, 1);
            fillZ(core, 9, ESZ_HALF, 2);
            long[][] before = snapshotZa(core);
            // SMLAL vgx2, campo off = 1 (× 2 = 2): 2 vetores por membro; membro 0 usa Zm = Z8 (1), membro 1 usa Z9 (2).
            run(core, word(shapeNn(Op.SMLAL, 2), RV_FIRST, 1, 2, 8));
            long[][] za = snapshotZa(core);
            assertEquals(Set.of(2, 3, 2 + rowsPerMember, 3 + rowsPerMember), changedRows(core, before));
            assertEquals(1L, lane(za[2], ESZ_SINGLE, 0));
            assertEquals(1L, lane(za[3], ESZ_SINGLE, 0));
            assertEquals(2L, lane(za[2 + rowsPerMember], ESZ_SINGLE, 0));
            assertEquals(2L, lane(za[3 + rowsPerMember], ESZ_SINGLE, 0));
        }
    }

    @Test
    void faddAndFsubAccumulateDirectlyWithoutMultiplyingAndWithoutZn() {
        for (int svl : SVLS) {
            int rowsPerMember = svl / Byte.SIZE / 2;
            Aarch64Core core = zeroedCore(svl);
            // Z4/Z5 = 0,5; todos os OUTROS Z recebem lixo — como não há Zn, nada disso pode entrar na conta.
            for (int zr = 0; zr < Z_REGISTERS; zr++) {
                fillZ(core, zr, ESZ_SINGLE, fb(123.0f));
            }
            fillZ(core, 4, ESZ_SINGLE, fb(0.5f));
            fillZ(core, 5, ESZ_SINGLE, fb(0.5f));
            long[][] before = snapshotZa(core);
            Shape fadd = shapeNn(Op.FADD_S, 2);
            run(core, word(fadd, RV_FIRST, 0, 0, 4));
            run(core, word(fadd, RV_FIRST, 0, 0, 4));
            assertEquals(fb(1.0f), lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "FADD ACUMULA: 0 + 0,5 + 0,5");
            assertEquals(fb(1.0f), lane(snapshotZa(core)[rowsPerMember], ESZ_SINGLE, 0));
            assertEquals(Set.of(0, rowsPerMember), changedRows(core, before));
            run(core, word(shapeNn(Op.FSUB_S, 2), RV_FIRST, 0, 0, 4));
            assertEquals(fb(0.5f), lane(snapshotZa(core)[0], ESZ_SINGLE, 0), "FSUB: 1,0 − 0,5");
        }
    }

    @Test
    void faddFsubUseTheZaVectorAlreadyThereAsTheFirstOperand() {
        Aarch64Core core = zeroedCore(256);
        fillZaRow(core, 0, ESZ_HALF, hb(2.0f));
        fillZaRow(core, 16, ESZ_HALF, hb(3.0f));
        fillZ(core, 4, ESZ_HALF, hb(0.5f));
        fillZ(core, 5, ESZ_HALF, hb(1.0f));
        run(core, word(shapeNn(Op.FSUB_H, 2), RV_FIRST, 0, 0, 4));
        assertEquals(hb(1.5f), lane(snapshotZa(core)[0], ESZ_HALF, 0), "2,0 − 0,5");
        assertEquals(hb(2.0f), lane(snapshotZa(core)[16], ESZ_HALF, 0), "3,0 − 1,0");
    }

    // ── acesso ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void outsideStreamingModeEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = core(256, SVCR_ZA);
        fillZ(core, 3, ESZ_BYTE, 1);
        long[][] before = snapshotZa(core);
        run(core, word(shape(Op.SMLALL_S, 2), RV_FIRST, 0, 3, 9));
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(Set.of(), changedRows(core, before));
    }

    @Test
    void withZaDisabledEntersTheSmeTrapAndTouchesNothing() {
        Aarch64Core core = core(256, SVCR_SM);
        fillZ(core, 3, ESZ_BYTE, 1);
        run(core, word(shape(Op.SMLALL_S, 2), RV_FIRST, 0, 3, 9));
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
    }
}
