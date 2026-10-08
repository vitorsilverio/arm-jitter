package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpConvertFixedPoint;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftImmediate;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftNarrowImmediate;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftWidenImmediate;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.ModifiedImmediate64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15f: o "shift by immediate" e o "modified immediate" como tabela ({@link AdvSimdShiftImmediateRows}).
/// Comportamento idêntico ao da cascata (oráculo `e15.15f-scripts/AdvSimdShiftImmediateOracle.java`, diff vazio).
/// A sobreposição com o resto da `advSimdTable` é testada em
/// {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdShiftImmediateRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 107 deslocamentos (70 vetoriais, 37 escalares), 42 estreitando, 6 alargando, 32 FP↔ponto fixo, 11 modified
    /// immediate.
    private static final int ROW_COUNT = 198;
    /// Sem `FEAT_FP16`: saem as 8 linhas de conversão em meia precisão e o `FMOV` de meia precisão.
    private static final int BASE_ROW_COUNT = 189;
    private static final Aarch64Architecture FP16 = Aarch64Architecture.of("e15.15f", Aarch64Feature.FP16);

    private static Ir64Op decodeWord(Aarch64Architecture architecture, String word) {
        return new Aarch64Decoder(architecture).decode(Integer.parseUnsignedInt(word, 16), ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdShiftImmediateRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdShiftImmediateRows.ROWS);
    }

    @Test
    void featureRowsAreFilteredByThePreset() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forFeatures(AdvSimdShiftImmediateRows.ROWS,
                Aarch64Architecture.ARMV8_0_A::has).rows().size());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdShiftImmediateRows.ROWS, FP16::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515FL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(FP16);
        SplittableRandom random = new SplittableRandom(0xE1515FL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdShiftImmediateRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decoder.decode(word, ADDRESS), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`. `shift` é o
    /// imediato do `objdump`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0f0d0420 | sshr v0.8b, v1.8b, #3       | SSHR   | false | false | 0 | 3
            6f400420 | ushr v0.2d, v1.2d, #64      | USHR   | false | true  | 3 | 64
            0f101420 | ssra v0.4h, v1.4h, #16      | SSRA   | false | false | 1 | 16
            6f3f1420 | usra v0.4s, v1.4s, #1       | USRA   | false | true  | 2 | 1
            4f082420 | srshr v0.16b, v1.16b, #8    | SRSHR  | false | true  | 0 | 8
            7f7b2420 | urshr d0, d1, #5            | URSHR  | true  | false | 3 | 5
            5f403420 | srsra d0, d1, #64           | SRSRA  | true  | false | 3 | 64
            6f1e3420 | ursra v0.8h, v1.8h, #2      | URSRA  | false | true  | 1 | 2
            2f394420 | sri v0.2s, v1.2s, #7        | SRI    | false | false | 2 | 7
            0f1f5420 | shl v0.4h, v1.4h, #15       | SHL    | false | false | 1 | 15
            7f7f5420 | sli d0, d1, #63             | SLI    | true  | false | 3 | 63
            5f0f7420 | sqshl b0, b1, #7            | SQSHL  | true  | false | 0 | 7
            6f407420 | uqshl v0.2d, v1.2d, #0      | UQSHL  | false | true  | 3 | 0
            7f136420 | sqshlu h0, h1, #3           | SQSHLU | true  | false | 1 | 3
            5f3f7420 | sqshl s0, s1, #31           | SQSHL  | true  | false | 2 | 31
            """)
    void shiftFieldsFollowTheEncoding(String word, String objdump, Ir64VectorShiftOp op, boolean scalar, boolean q,
            int esz, int shift) {
        assertEquals(new ShiftImmediate(op, scalar, q, esz, shift, 0, 1), decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                objdump);
    }

    /// `esz` é o elemento ESTREITO.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0f088420 | shrn v0.8b, v1.8h, #8        | SHRN     | false | false | 0 | 8
            4f108c20 | rshrn2 v0.8h, v1.4s, #16     | RSHRN    | false | true  | 1 | 16
            5f209420 | sqshrn s0, d1, #32           | SQSHRN   | true  | false | 2 | 32
            6f3f9420 | uqshrn2 v0.4s, v1.2d, #1     | UQSHRN   | false | true  | 2 | 1
            5f1c9c20 | sqrshrn h0, s1, #4           | SQRSHRN  | true  | false | 1 | 4
            7f0e9c20 | uqrshrn b0, h1, #2           | UQRSHRN  | true  | false | 0 | 2
            2f1d8420 | sqshrun v0.4h, v1.4s, #3     | SQSHRUN  | false | false | 1 | 3
            7f378c20 | sqrshrun s0, d1, #9          | SQRSHRUN | true  | false | 2 | 9
            """)
    void narrowFieldsFollowTheEncoding(String word, String objdump, Ir64VectorShiftNarrowOp op, boolean scalar,
            boolean q, int esz, int shift) {
        assertEquals(new ShiftNarrowImmediate(op, scalar, q, esz, shift, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, word), objdump);
    }

    @Test
    void widenFieldsFollowTheEncoding() {
        // sshll v0.8h, v1.8b, #0 · ushll2 v0.2d, v1.4s, #31
        assertEquals(new ShiftWidenImmediate(Ir64VectorShiftWidenOp.SSHLL, false, 0, 0, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, "0f08a420"));
        assertEquals(new ShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, true, 2, 31, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, "6f3fa420"));
    }

    /// `fbits` é o `#` do `objdump`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4f3de420 | scvtf v0.4s, v1.4s, #3      | false | true  | 2 | 3  | true  | true
            7f40e420 | ucvtf d0, d1, #64           | true  | false | 3 | 64 | true  | false
            4f7ffc20 | fcvtzs v0.2d, v1.2d, #1     | false | true  | 3 | 1  | false | true
            7f20fc20 | fcvtzu s0, s1, #32          | true  | false | 2 | 32 | false | false
            4f10e420 | scvtf v0.8h, v1.8h, #16     | false | true  | 1 | 16 | true  | true
            7f1ffc20 | fcvtzu h0, h1, #1           | true  | false | 1 | 1  | false | false
            """)
    void fixedPointFieldsFollowTheEncoding(String word, String objdump, boolean scalar, boolean q, int esz, int fbits,
            boolean toFloat, boolean signed) {
        assertEquals(new FpConvertFixedPoint(scalar, q, esz, fbits, toFloat, signed, 0, 1), decodeWord(FP16, word),
                objdump);
    }

    /// `imm64` expandido (o `MVNI` guarda o imediato sem inverter — a inversão é da execução).
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0f052560 | movi v0.2s, #0xab, lsl #8   | MOV | false | 0000ab000000ab00
            2f00a640 | mvni v0.4h, #0x12, lsl #8   | MVN | false | 1200120012001200
            4f015680 | orr v0.4s, #0x34, lsl #16   | ORR | true  | 0034000000340000
            6f0296c0 | bic v0.8h, #0x56            | BIC | true  | 0056005600560056
            4f03d700 | movi v0.4s, #0x78, msl #16  | MOV | true  | 0078ffff0078ffff
            4f04e740 | movi v0.16b, #0x9a          | MOV | true  | 9a9a9a9a9a9a9a9a
            2f05e540 | movi d0, #0xff00ff00ff00ff00 | MOV | false | ff00ff00ff00ff00
            6f02e6a0 | movi v0.2d, #0xff00ff00ff00ff | MOV | true | 00ff00ff00ff00ff
            4f03f600 | fmov v0.4s, #1.0            | MOV | true  | 3f8000003f800000
            6f04f480 | fmov v0.2d, #-2.5           | MOV | true  | c004000000000000
            4f03fc00 | fmov v0.8h, #0.5            | MOV | true  | 3800380038003800
            """)
    void modifiedImmediateFieldsFollowTheEncoding(String word, String objdump, AdvSimdModifiedImmediateOp op,
            boolean q, String imm64) {
        assertEquals(new ModifiedImmediate64(op, q, 0, Long.parseUnsignedLong(imm64, 16)), decodeWord(FP16, word),
                objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            5f088420 | 7f088420 | sqshrun b0, h1, #8 — SHRN não tem forma escalar
            5f08a420 | 0f08a420 | sshll v0.8h, v1.8b, #0 — SSHLL não tem forma escalar
            5f0d0420 | 5f400420 | sshr d0, d1, #64 — SSHR escalar só D
            0f400420 | 4f400420 | sshr v0.2d, v1.2d, #64 — não existe .1d
            0f408420 | 0f208420 | shrn v0.2s, v1.2d, #32 — estreito de 64 bits
            0f394420 | 2f394420 | sri v0.2s, v1.2s, #7 — SRI com U=0
            0f136420 | 2f136420 | sqshlu v0.4h, v1.4h, #3 — SQSHLU com U=0
            4f0ce420 | 4f3de420 | scvtf v0.4s, v1.4s, #3 — conversão de byte
            0f7ffc20 | 4f7ffc20 | fcvtzs v0.2d, v1.2d, #1 — conversão .1d
            0f8d0420 | 0f0d0420 | sshr v0.8b, v1.8b, #3 — bit23=1
            0f0d0c20 | 0f0d0420 | sshr v0.8b, v1.8b, #3 — opcode 00001 não alocado
            5f00e420 | 0f00e420 | movi v0.8b, #0x1 — modified immediate não tem forma escalar
            0f00ec20 | 0f00e420 | movi v0.8b, #0x1 — o2=1 fora do FMOV de meia precisão
            2f04f480 | 6f04f480 | fmov v0.2d, #-2.5 — FMOV de dupla só .2d
            2f03fc00 | 0f03fc00 | fmov v0.4h, #0.5 — FMOV de meia precisão com op=1
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16, rejected), objdump);
        assertDoesNotThrow(() -> decodeWord(FP16, neighbour), objdump);
    }

    /// Meia precisão: recusada sem `FEAT_FP16`.
    @ParameterizedTest(name = "{0} precisa de FEAT_FP16")
    @CsvSource(delimiter = '|', textBlock = """
            4f10e420 | scvtf v0.8h, v1.8h, #16
            7f1ffc20 | fcvtzu h0, h1, #1
            4f03fc00 | fmov v0.8h, #0.5
            """)
    void halfPrecisionFormsNeedFp16(String word, String objdump) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                objdump);
        assertDoesNotThrow(() -> decodeWord(FP16, word), objdump);
    }
}
