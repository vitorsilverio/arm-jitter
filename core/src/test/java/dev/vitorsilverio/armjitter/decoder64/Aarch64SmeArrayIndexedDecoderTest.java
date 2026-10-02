package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64.ArrayMultiVector.Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.SmeArrayIndexedWords;
import dev.vitorsilverio.armjitter.support.SmeArrayIndexedWords.Word;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// SME2 multi-vetor **indexado** (`_nx`, resultado em `ZA`) — B18.11. **Toda palavra da tabela foi desmontada por
/// `aarch64-none-elf-objdump` (devkitA64, binutils 2.46, `-march=armv9.5-a+sme+sme2+sme-i16i64+sme-f64f64+sme-f16f16+
/// sme-b16b16+fp8+sme-f8f32+sme-f8f16`)** a partir de TRÊS preenchimentos dos bits livres de cada uma das 113 linhas do
/// `.decode` (todos 1, `0x5555…`, `0xAAAA…`), e os campos esperados (operação, `n`, `W<rv>`, `off`, `zn`, `zm`, ÍNDICE)
/// saem do TEXTO do disassembler — nunca do decoder. Os três preenchimentos dão valores DISTINTOS para os pedaços não
/// contíguos de cada um dos 10 extratores de índice, então uma composição trocada de ordem não passa. A última coluna é
/// o assembly.
class Aarch64SmeArrayIndexedDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-azx-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(SME2, "teste-azx-dec-ALL",
            Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
            Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
    private static final long INSTRUCTION_ADDRESS = 0x40;
    private static final int EXPECTED_ENCODINGS = 113;
    private static final int EXPECTED_MNEMONICS = 53;
    private static final int VARIANTS_PER_ENCODING = 3;

    private static List<Word> cases() {
        return SmeArrayIndexedWords.all();
    }

    static Stream<Word> allCases() {
        return cases().stream();
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    /// A feature EXTRA de cada operação, escrita aqui INDEPENDENTE do decoder: `translate-sme.c` e, onde o QEMU é frouxo
    /// (`SVDOT_4h`/`UVDOT_4h` exigem `FEAT_SME_I16I64`; `FVDOT_sh`/`BFVDOT` exigem `FEAT_SME2`, não `FEAT_SME`), o manual.
    private static Aarch64Feature extraFeature(Op op) {
        return switch (op) {
            case SDOT_4H, UDOT_4H, SVDOT_4H, UVDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D ->
                    Aarch64Feature.SME_I16I64;
            case FMLA_D, FMLS_D -> Aarch64Feature.SME_F64F64;
            case FMLA_H, FMLS_H -> Aarch64Feature.SME_F16F16;
            case BFMLA, BFMLS -> Aarch64Feature.SME_B16B16;
            case FMLALL_B, FDOT_SB, FVDOTB, FVDOTT -> Aarch64Feature.SME_F8F32;
            case FMLAL_HB, FDOT_HB, FVDOT_HB -> Aarch64Feature.SME_F8F16;
            default -> null;
        };
    }

    @Test
    void tableCoversTheWholeInventory() {
        List<Word> all = cases();
        assertEquals(VARIANTS_PER_ENCODING * EXPECTED_ENCODINGS, all.size());
        assertEquals(EXPECTED_ENCODINGS, SmeArrayIndexedRows.ROWS.size());
        assertEquals(EXPECTED_MNEMONICS, all.stream().map(Word::op).distinct().count());
        // `ADD`/`SUB`/`FADD`/`FSUB`/`BFADD`/`BFSUB` (B18.9/B18.10) NÃO existem na forma indexada.
        EnumSet<Op> expected = EnumSet.allOf(Op.class);
        expected.removeAll(EnumSet.of(Op.ADD_AAZ_S, Op.ADD_AAZ_D, Op.SUB_AAZ_S, Op.SUB_AAZ_D)); // B18.12: têm teste próprio
        expected.removeAll(EnumSet.of(Op.ADD_S, Op.ADD_D, Op.SUB_S, Op.SUB_D, Op.FADD_H, Op.FADD_S, Op.FADD_D,
                Op.BFADD, Op.FSUB_H, Op.FSUB_S, Op.FSUB_D, Op.BFSUB));
        assertEquals(expected, EnumSet.copyOf(all.stream().map(Word::op).toList()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void decodesEveryEncodingFromTheDisassembler(Word c) {
        SmeOp64.ArrayMultiVector op = assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(ALL, c.word()));
        assertEquals(c.op(), op.op());
        assertEquals(c.count(), op.count());
        assertEquals(c.register(), op.registerIndex(), "W<rv> = campo + 8");
        assertEquals(c.off(), op.off(), "off JÁ multiplicado pela escala da linha");
        assertEquals(c.zn(), op.zn(), "zn: base do grupo alinhado (ou registrador cru no n = 1)");
        assertEquals(c.zm(), op.zm());
        assertEquals(c.index(), op.index(), "índice montado dos bits não contíguos");
        assertTrue(op.indexed());
        assertFalse(op.multipleZm());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void eachEncodingNeedsExactlyItsOwnFeatures(Word c) {
        Aarch64Feature extra = extraFeature(c.op());
        if (extra == null) {
            assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(SME2, c.word()));
            return;
        }
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, c.word()),
                "sem " + extra + " continua UNIMPLEMENTED");
        Aarch64Architecture only = Aarch64Architecture.extending(SME2, "teste-azx-dec-" + extra, extra);
        assertInstanceOf(SmeOp64.ArrayMultiVector.class, decode(only, c.word()));
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture sme = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-azx-dec-SME",
                Aarch64Feature.SME_I16I64, Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16,
                Aarch64Feature.SME_B16B16, Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);
        for (Word c : cases()) {
            assertThrows(UnsupportedOperationException.class, () -> decode(sme, c.word()), c.assembly());
        }
    }

    @Test
    void noTwoIndexedRowsOverlapNorDoTheyOverlapTheNonIndexedArrayRows() {
        List<SmeArrayIndexedRows.Row> rows = SmeArrayIndexedRows.ROWS;
        for (int i = 0; i < rows.size(); i++) {
            SmeArrayIndexedRows.Row a = rows.get(i);
            for (int j = i + 1; j < rows.size(); j++) {
                SmeArrayIndexedRows.Row b = rows.get(j);
                assertTrue(((a.value() ^ b.value()) & a.mask() & b.mask()) != 0,
                        "linhas " + i + " e " + j + " (" + a.op() + "/" + b.op() + ") reconhecem a mesma palavra");
            }
            for (SmeArrayVectorRows.Row other : SmeArrayVectorRows.ROWS) {
                assertTrue(((a.value() ^ other.value()) & a.mask() & other.mask()) != 0,
                        "linha indexada " + a.op() + " colide com a não indexada " + other.op());
            }
        }
    }

    @Test
    void indexExtractorFollowsTheDecodeOrderOfTheNonContiguousPieces() {
        // %idx4_15_10_3 = 15:1 10:2 3:1: bit[15] = 1, bits[11:10] = 0b01, bit[3] = 1 => 0b1011 (spec B18.11).
        SmeArrayIndexedRows.Row row = SmeArrayIndexedRows.ROWS.stream()
                .filter(r -> r.op() == Op.FMLAL_HB && r.count() == 1).findFirst().orElseThrow();
        assertEquals(0b1011, row.index(row.value() | (1 << 15) | (0b01 << 10) | (1 << 3)));
        // Trocando de lugar dois pedaços, o valor muda: 15 = 0, 11:10 = 0b10, 3 = 1 => 0b0101.
        assertEquals(0b0101, row.index(row.value() | (0b10 << 10) | (1 << 3)));
    }

    @Test
    void theIndexIsNotTakenFromOtherFieldsOfTheWord() {
        // fvdotb za.s[w8, 0, vgx4], {z0.b-z1.b}, z0.b[idx]: o índice é `bit[10]:bit[3]`, nunca `zn`/`zm`/`off`.
        SmeArrayIndexedRows.Row row = SmeArrayIndexedRows.ROWS.stream().filter(r -> r.op() == Op.FVDOTB).findFirst()
                .orElseThrow();
        assertEquals(0, row.index(row.value()));
        assertEquals(0b10, row.index(row.value() | (1 << 10)));
        assertEquals(0b01, row.index(row.value() | (1 << 3)));
        assertEquals(0, row.index(row.value() | (0b1111 << 16) | (0b111 << 7) | 0b111));
    }
}
