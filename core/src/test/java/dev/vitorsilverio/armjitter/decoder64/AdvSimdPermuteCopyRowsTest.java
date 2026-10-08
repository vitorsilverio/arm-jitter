package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.LoadStoreRowsTest.NO_SME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15a: EXT/TBL/permute/copy como tabela ({@link AdvSimdPermuteCopyRows}) e a tabela AdvSIMD inteira
/// do `Aarch64Decoder` (todas as classes de linhas juntas). Comportamento idêntico ao da cascata (oráculo
/// `e15.15a-scripts/AdvSimdBit21ZeroOracle.java`: o único diff é o `Ra` de 5 bits de {@link CryptoRows}).
class AdvSimdPermuteCopyRowsTest {
    private static final long ADDRESS = 0x1000L;
    private static final int ROW_COUNT = 48;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    /// As linhas que o `Aarch64Decoder` junta na `advSimdTable`.
    static List<DecodeRow<Aarch64Feature, Ir64Op>> advSimdTableRows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>(AdvSimdBit21ZeroRows.ROWS);
        rows.addAll(AdvSimdPermuteCopyRows.ROWS);
        rows.addAll(CryptoRows.ROWS);
        rows.addAll(AdvSimdThreeSameRows.ROWS);
        rows.addAll(AdvSimdThreeSameFpRows.ROWS);
        rows.addAll(AdvSimdThreeDifferentRows.ROWS);
        rows.addAll(AdvSimdAcrossLanesRows.ROWS);
        rows.addAll(AdvSimdTwoRegisterMiscRows.ROWS);
        rows.addAll(AdvSimdShiftImmediateRows.ROWS);
        rows.addAll(AdvSimdIndexedElementRows.ROWS);
        return rows;
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdPermuteCopyRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdPermuteCopyRows.ROWS);
    }

    /// A tabela não tem prioridade: as classes de linhas também não podem se sobrepor entre si.
    @Test
    void advSimdTableRowsNeverOverlap() {
        DecodeTableInvariants.assertNoOverlap(advSimdTableRows());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdPermuteCopyRows.ROWS, Aarch64Architecture.ARMV8_0_A::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515AL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        SplittableRandom random = new SplittableRandom(0xE1515AL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdPermuteCopyRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    /// Os campos que a cascata calculava com `switch`/`if`, conferidos contra o `objdump` 2.46 do devkitA64.
    @Test
    void fieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        // ext v0.8b, v1.8b, v2.8b, #5 · ext v3.16b, v4.16b, v5.16b, #13
        assertEquals(new AdvSimdMoveOp64.Extract(false, 5, 0, 1, 2), decodeWord(decoder, 0x2e022820));
        assertEquals(new AdvSimdMoveOp64.Extract(true, 13, 3, 4, 5), decodeWord(decoder, 0x6e056883));
        // tbl v0.16b, {v1.16b-v3.16b}, v4.16b · tbx v5.8b, {v6.16b}, v7.8b
        assertEquals(new AdvSimdMoveOp64.TableLookup(false, 2, true, 0, 1, 4), decodeWord(decoder, 0x4e044020));
        assertEquals(new AdvSimdMoveOp64.TableLookup(true, 0, false, 5, 6, 7), decodeWord(decoder, 0x0e0710c5));
        // uzp2 v0.8h · zip1 v0.2d · trn1 v0.2s
        assertEquals(new AdvSimdMoveOp64.Permute(Ir64VectorPermuteOp.UZP2, true, 1, 0, 1, 2), decodeWord(decoder, 0x4e425820));
        assertEquals(new AdvSimdMoveOp64.Permute(Ir64VectorPermuteOp.ZIP1, true, 3, 0, 1, 2), decodeWord(decoder, 0x4ec23820));
        assertEquals(new AdvSimdMoveOp64.Permute(Ir64VectorPermuteOp.TRN1, false, 2, 0, 1, 2), decodeWord(decoder, 0x0e822820));
        // dup v0.8h, v1.h[5] · dup v0.2d, v1.d[1] · dup v0.4s, w3 · dup v0.2d, x3
        assertEquals(new AdvSimdMoveOp64.DuplicateElement(true, 1, 0, 1, 5), decodeWord(decoder, 0x4e160420));
        assertEquals(new AdvSimdMoveOp64.DuplicateElement(true, 3, 0, 1, 1), decodeWord(decoder, 0x4e180420));
        assertEquals(new AdvSimdMoveOp64.DuplicateGeneral(true, 2, 0, 3), decodeWord(decoder, 0x4e040c60));
        assertEquals(new AdvSimdMoveOp64.DuplicateGeneral(true, 3, 0, 3), decodeWord(decoder, 0x4e080c60));
        // mov v0.b[9], w3
        assertEquals(new AdvSimdMoveOp64.InsertGeneral(0, 0, 3, 9), decodeWord(decoder, 0x4e131c60));
        // smov w0, v1.h[3] · smov x0, v1.s[2] · umov w0, v1.b[13] · mov x0, v1.d[1]
        assertEquals(new AdvSimdMoveOp64.MoveElement(true, false, 1, 0, 1, 3), decodeWord(decoder, 0x0e0e2c20));
        assertEquals(new AdvSimdMoveOp64.MoveElement(true, true, 2, 0, 1, 2), decodeWord(decoder, 0x4e142c20));
        assertEquals(new AdvSimdMoveOp64.MoveElement(false, false, 0, 0, 1, 13), decodeWord(decoder, 0x0e1b3c20));
        assertEquals(new AdvSimdMoveOp64.MoveElement(false, true, 3, 0, 1, 1), decodeWord(decoder, 0x4e183c20));
        // mov v0.s[3], v1.s[1] · mov v0.d[1], v1.d[0]
        assertEquals(new AdvSimdMoveOp64.InsertElement(2, 0, 1, 3, 1), decodeWord(decoder, 0x6e1c2420));
        assertEquals(new AdvSimdMoveOp64.InsertElement(3, 0, 1, 1, 0), decodeWord(decoder, 0x6e180420));
        // mov s0, v1.s[3] · mov d2, v3.d[1]
        assertEquals(new AdvSimdMoveOp64.DuplicateElementScalar(2, 0, 1, 3), decodeWord(decoder, 0x5e1c0420));
        assertEquals(new AdvSimdMoveOp64.DuplicateElementScalar(3, 2, 3, 1), decodeWord(decoder, 0x5e180462));
    }

    /// As formas com feature que entraram em {@link AdvSimdBit21ZeroRows} na E15.15a, mesma conferência.
    @Test
    void featureFieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // bfdot v0.4s · bfmlalt v0.4s · bfmmla v0.4s
        assertEquals(new AdvSimdFpOp64.FpDotProductBFloat16(true, 0, 1, 2), decodeWord(decoder, 0x6e42fc20));
        assertEquals(new AdvSimdFpOp64.FpMultiplyAddLongBFloat16(true, 0, 1, 2), decodeWord(decoder, 0x6ec2fc20));
        assertEquals(new AdvSimdFpOp64.FpMatrixMultiplyAccumulateBFloat16(0, 1, 2), decodeWord(decoder, 0x6e42ec20));
        // usdot v0.2s · smmla · ummla · usmmla · sdot v0.4s · udot v0.2s
        assertEquals(new AdvSimdIntegerOp64.IntegerDotProduct(false, false, true, 0, 1, 2), decodeWord(decoder, 0x0e829c20));
        assertEquals(new AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate(true, true, 0, 1, 2), decodeWord(decoder, 0x4e82a420));
        assertEquals(new AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate(false, false, 0, 1, 2), decodeWord(decoder, 0x6e82a420));
        assertEquals(new AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate(false, true, 0, 1, 2), decodeWord(decoder, 0x4e82ac20));
        assertEquals(new AdvSimdIntegerOp64.IntegerDotProduct(true, true, true, 0, 1, 2), decodeWord(decoder, 0x4e829420));
        assertEquals(new AdvSimdIntegerOp64.IntegerDotProduct(false, false, false, 0, 1, 2), decodeWord(decoder, 0x2e829420));
        // luti2 v0.16b ..[3] · luti2 v0.8h ..[7] · luti4 v0.16b ..[1] · luti4 v0.8h, {v1.8h-v2.8h}, v3[3]
        assertEquals(new AdvSimdMoveOp64.LookupTable(false, 0, 3, 0, 1, 2), decodeWord(decoder, 0x4e827020));
        assertEquals(new AdvSimdMoveOp64.LookupTable(false, 1, 7, 0, 1, 2), decodeWord(decoder, 0x4ec27020));
        assertEquals(new AdvSimdMoveOp64.LookupTable(true, 0, 1, 0, 1, 2), decodeWord(decoder, 0x4e426020));
        assertEquals(new AdvSimdMoveOp64.LookupTable(true, 1, 3, 0, 1, 3), decodeWord(decoder, 0x4e437020));
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            0e183c20 | 4e183c20 | mov x0, v1.d[1] — UMOV com Q≠(esz=3)
            4e182c20 | 4e142c20 | smov x0, v1.s[2] — SMOV de doubleword
            0e082c20 | 4e142c20 | smov x0, v1.s[2] — SMOV de word para Wd
            0e131c60 | 4e131c60 | mov v0.b[9], w3 — INS (geral) com Q=0
            2e1c2420 | 6e1c2420 | mov v0.s[3], v1.s[1] — INS (elemento) com Q=0
            4e100420 | 4e180420 | dup v0.2d, v1.d[1] — imm5=10000
            0e180420 | 4e180420 | dup v0.2d, v1.d[1] — DUP .1d
            5e100420 | 5e1c0420 | mov s0, v1.s[3] — DUP escalar com imm5=10000
            4e444020 | 4e044020 | tbl v0.16b — TBL com size≠00
            4e420820 | 4e425820 | uzp2 v0.8h — permute com opcode 000
            0e827020 | 4e827020 | luti2 v0.16b — LUTI2 com Q=0
            4e82a020 | 4e82a420 | smmla v0.4s — SMMLA com bit10=0
            0e82a420 | 4e82a420 | smmla v0.4s — SMMLA com Q=0
            6e82ac20 | 4e82ac20 | usmmla v0.4s — USMMLA com U=1
            2e42ec20 | 6e42ec20 | bfmmla v0.4s — BFMMLA com Q=0
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }
}
