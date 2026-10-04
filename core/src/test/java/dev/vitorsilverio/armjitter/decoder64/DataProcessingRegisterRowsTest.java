package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MinMaxOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.LoadStoreRowsTest.NO_SME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.13: a classe "Data Processing — Register" como tabela ({@link DataProcessingRegisterRows}) — invariantes
/// das linhas, roteamento pelo {@link Aarch64Decoder}, campos que a cascata calculava, features como coluna
/// (inclusive o `CNT` escalar, que a cascata não gateava) e os G8 que a versão em cascata tinha.
class DataProcessingRegisterRowsTest {
    private static final long ADDRESS = 0x1000L;
    private static final int ROW_COUNT = 43;
    private static final int BASE_ROW_COUNT = 24;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, DataProcessingRegisterRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(DataProcessingRegisterRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(DataProcessingRegisterRows.ROWS, NO_SME);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1513L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        SplittableRandom random = new SplittableRandom(0xE1513L);
        for (DecodeRow<Ir64Op> row : DataProcessingRegisterRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void baseArchitectureKeepsOnlyTheRowsWithoutFeature() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forArchitecture(DataProcessingRegisterRows.ROWS,
                Aarch64Architecture.ARMV8_0_A).rows().size());
    }

    /// Os campos que a cascata calculava com `switch`/`if`, conferidos contra o `objdump` 2.46 do devkitA64.
    @Test
    void fieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // bics w0, w1, w2, ror #3
        assertEquals(new IntegerOp64.LogicalShiftedRegister(Ir64AluOp.AND, 0, 1, 2, Ir64LogicalShiftType.ROR, 3, true,
                false, true), decodeWord(decoder, 0x6ae20c20));
        // subs x0, x1, x2, asr #7
        assertEquals(new IntegerOp64.AluShiftedRegister(Ir64AluOp.SUB, 0, 1, 2, Ir64ShiftType.ASR, 7, true, true),
                decodeWord(decoder, 0xeb821c20));
        // add sp, x1, w2, sxtw #2 — Rd|SP sem S
        assertEquals(new IntegerOp64.AluExtendedRegister(Ir64AluOp.ADD, 31, 1, 2, Ir64AluExtendType.SXTW, 2, true, false,
                true), decodeWord(decoder, 0x8b22c83f));
        // ccmn x0, #1, #4, ne · ccmp w0, w1, #2, lt
        assertEquals(new IntegerOp64.ConditionalCompare(Ir64AluOp.ADD, 0, true, -1, 1, true, Ir64Condition.NE, 4),
                decodeWord(decoder, 0xba411804));
        assertEquals(new IntegerOp64.ConditionalCompare(Ir64AluOp.SUB, 0, false, 1, -1, false, Ir64Condition.LT, 2),
                decodeWord(decoder, 0x7a41b002));
        // csinv x0, x1, x2, gt · csneg w0, w1, w2, le
        assertEquals(new IntegerOp64.ConditionalSelect(Ir64ConditionalSelectOp.CSINV, 0, 1, 2, true, Ir64Condition.GT),
                decodeWord(decoder, 0xda82c020));
        assertEquals(new IntegerOp64.ConditionalSelect(Ir64ConditionalSelectOp.CSNEG, 0, 1, 2, false, Ir64Condition.LE),
                decodeWord(decoder, 0x5a82d420));
        // umax x0, x1, x2 · smin w0, w1, w2
        assertEquals(new IntegerOp64.MinMaxGeneral(Ir64MinMaxOp.UMAX, 0, 1, 2, true), decodeWord(decoder, 0x9ac26420));
        assertEquals(new IntegerOp64.MinMaxGeneral(Ir64MinMaxOp.SMIN, 0, 1, 2, false), decodeWord(decoder, 0x1ac26820));
        // crc32ch w0, w1, w2 · crc32x w0, w1, x2
        assertEquals(new IntegerOp64.Crc32(0, 1, 2, Short.SIZE, true), decodeWord(decoder, 0x1ac25420));
        assertEquals(new IntegerOp64.Crc32(0, 1, 2, Long.SIZE, false), decodeWord(decoder, 0x9ac24c20));
        // rorv x0, x1, x2 · sdiv w0, w1, w2
        assertEquals(new IntegerOp64.ShiftVariable(0, 1, 2, Ir64LogicalShiftType.ROR, true), decodeWord(decoder, 0x9ac22c20));
        assertEquals(new IntegerOp64.Divide(true, 0, 1, 2, false), decodeWord(decoder, 0x1ac20c20));
        // rev w0, w1 (REV32 com sf=0) · autdzb x0 · xpacd x0
        assertEquals(new IntegerOp64.DataProcessing1Source(Ir64OneSourceOp.REV32, 0, 1, false),
                decodeWord(decoder, 0x5ac00820));
        assertEquals(new IntegerOp64.PointerAuthInPlace(Ir64PointerAuthOp.AUTDB, 0, 31), decodeWord(decoder, 0xdac13fe0));
        assertEquals(new IntegerOp64.PointerAuthInPlace(Ir64PointerAuthOp.XPACD, 0, -1), decodeWord(decoder, 0xdac147e0));
        // rmif x0, #1, #3 · setf16 w0
        assertEquals(new IntegerOp64.RotateIntoFlags(0, 1, 3), decodeWord(decoder, 0xba008403));
        assertEquals(new IntegerOp64.EvaluateIntoFlags(0, 16), decodeWord(decoder, 0x3a00480d));
        // umsubl x0, w1, w2, x3 · umulh x0, x1, x2
        assertEquals(new IntegerOp64.MultiplyAccumulateLong(true, false, 0, 1, 2, 3), decodeWord(decoder, 0x9ba28c20));
        assertEquals(new IntegerOp64.MultiplyHigh(false, 0, 1, 2), decodeWord(decoder, 0x9bc27c20));
    }

    @ParameterizedTest(name = "{1} só com a feature")
    @CsvSource(delimiter = '|', textBlock = """
            ba008400 | rmif x0, #1, #0
            3a00080d | setf8 w0
            9ac24c20 | crc32x w0, w1, x2
            9ac20020 | subp x0, x1, x2
            bac20020 | subps x0, x1, x2
            9ac21020 | irg x0, x1, x2
            9ac21420 | gmi x0, x1, x2
            9ac23020 | pacga x0, x1, x2
            9ac26020 | smax x0, x1, x2
            dac01820 | ctz x0, x1
            5ac01c20 | cnt w0, w1
            dac02020 | abs x0, x1
            dac123e0 | paciza x0
            dac143e0 | xpaci x0
            """)
    void featureRowsAreAbsentFromTheBaseArchitecture(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A), value), objdump);
        assertDoesNotThrow(() -> decodeWord(new Aarch64Decoder(NO_SME), value), objdump);
    }

    /// `CNT` escalar é `FEAT_CSSC`, como `CTZ`/`ABS` — a cascata o aceitava desde ARMv8.0-A.
    @Test
    void scalarCountNeedsCommonShortSequenceCompression() {
        Aarch64Decoder cssc = new Aarch64Decoder(Aarch64Architecture.of("cssc",
                Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION));
        assertEquals(new IntegerOp64.DataProcessing1Source(Ir64OneSourceOp.CNT, 0, 1, false),
                decodeWord(cssc, 0x5ac01c20));
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64 e sem padrão no `a64.decode` do QEMU;
    /// coluna 2 = a vizinha legítima, como o `objdump` a desmonta. As 14 primeiras eram aceitas pela versão em
    /// cascata (G8); as outras já eram recusadas.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            bb020c20 | 9b020c20 | madd x0, x1, x2, x3 — op54≠00
            1b220c20 | 9b220c20 | smaddl x0, w1, w2, x3 — sf=0
            1ba20c20 | 9ba20c20 | umaddl x0, w1, w2, x3 — sf=0
            9b42fc20 | 9b427c20 | smulh x0, x1, x2 — o0=1
            1b427c20 | 9b427c20 | smulh x0, x1, x2 — sf=0
            fa410400 | fa410000 | ccmp x0, x1, #0, eq — o2=1
            fa410010 | fa410000 | ccmp x0, x1, #0, eq — o3=1
            bac21020 | bac20020 | subps x0, x1, x2 — opcode≠000000
            1ac20020 | 9ac20020 | subp x0, x1, x2 — sf=0
            1ac21020 | 9ac21020 | irg x0, x1, x2 — sf=0
            1ac21420 | 9ac21420 | gmi x0, x1, x2 — sf=0
            1ac23020 | 9ac23020 | pacga x0, x1, x2 — sf=0
            8bc20020 | 8b020020 | add x0, x1, x2 — shift=11
            0b028020 | 0b020020 | add w0, w1, w2 — imm6≥32 em 32 bits
            8b221420 | 8b221020 | add x0, x1, w2, uxtb #4 — imm3>4
            0a028020 | 0a020020 | and w0, w1, w2 — imm6≥32 em 32 bits
            1ac24c20 | 9ac24c20 | crc32x w0, w1, x2 — sf=0
            5ac00c20 | dac00c20 | rev x0, x1 — REV64 com sf=0
            dac12020 | dac123e0 | paciza x0 — Z com Rn≠11111
            dac14020 | dac143e0 | xpaci x0 — Rn≠11111
            ba008410 | ba008400 | rmif x0, #1, #0 — bit4=1
            3a00080c | 3a00080d | setf8 w0 — Rd≠01101
            5ac22020 | 5ac00020 | rbit w0, w1 — opcode2 reservado
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }
}
