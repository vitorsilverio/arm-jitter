package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.12: a classe Loads and Stores como tabela ({@link LoadStoreRegisterRows}, {@link LoadStoreExclusiveRows},
/// {@link MemoryOperationRows}, {@link AdvSimdLoadStoreRows}) — invariantes das linhas (as quatro listas formam UMA
/// tabela no decoder, então a sobreposição é conferida no conjunto), roteamento pelo {@link Aarch64Decoder},
/// campos que a cascata calculava, features como coluna (inclusive a conjunção de `SETG*`) e os G8 que a versão
/// em cascata tinha.
class LoadStoreRowsTest {
    /// Todas as features menos SME: com SME o decoder embrulha os AdvSIMD em `StreamingRestricted`.
    static final Aarch64Architecture NO_SME = Aarch64Architecture.of("nosme", Arrays.stream(Aarch64Feature.values())
            .filter(f -> f != Aarch64Feature.SCALABLE_MATRIX_EXTENSION && !f.name().startsWith("SME"))
            .toArray(Aarch64Feature[]::new));
    private static final long ADDRESS = 0x1000L;
    private static final int REGISTER_ROWS = 42;
    private static final int EXCLUSIVE_ROWS = 16;
    private static final int MEMORY_OPERATION_ROWS = 13;
    private static final int ADVSIMD_ROWS = 36;
    /// Linhas sem feature em cada lista.
    private static final int BASE_ROWS = (REGISTER_ROWS - 6) + (EXCLUSIVE_ROWS - 10) + ADVSIMD_ROWS;

    static List<DecodeRow<Aarch64Feature, Ir64Op>> allRows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>(LoadStoreRegisterRows.ROWS);
        rows.addAll(LoadStoreExclusiveRows.ROWS);
        rows.addAll(MemoryOperationRows.ROWS);
        rows.addAll(AdvSimdLoadStoreRows.ROWS);
        return rows;
    }

    /// `LDCLRP`/`LDSETP`/`SWPP`: o construtor recusa `Rt`/`Rt2` iguais ou `XZR` — o alcance sorteado não serve.
    private static boolean isAtomicPair(DecodeRow<Aarch64Feature, Ir64Op> row) {
        return (row.value() >>> 24) == 0b0001_1001 && ((row.value() >>> 21) & 1) == 1;
    }

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word, long address) {
        return decoder.decode(word, address);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(REGISTER_ROWS, LoadStoreRegisterRows.ROWS.size());
        assertEquals(EXCLUSIVE_ROWS, LoadStoreExclusiveRows.ROWS.size());
        assertEquals(MEMORY_OPERATION_ROWS, MemoryOperationRows.ROWS.size());
        assertEquals(ADVSIMD_ROWS, AdvSimdLoadStoreRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(allRows());
    }

    @Test
    void everyRowIsReachable() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = allRows().stream().filter(row -> !isAtomicPair(row)).toList();
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(rows, NO_SME::has);
        assertEquals(rows.size(), table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1512L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        SplittableRandom random = new SplittableRandom(0xE1512L);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : allRows()) {
            int free = random.nextInt() & ~row.mask();
            // Par de 128 bits: Rt=0 e Rt2=1, sempre válidos.
            int word = isAtomicPair(row) ? row.value() | (free & ~0x1F_001F) | 1 << 16 : row.value() | free;
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word, ADDRESS),
                    Integer.toHexString(word));
        }
    }

    @Test
    void baseArchitectureKeepsOnlyTheRowsWithoutFeature() {
        assertEquals(BASE_ROWS, DecodeTable.forFeatures(allRows(), Aarch64Architecture.ARMV8_0_A::has).rows().size());
    }

    @Test
    void literalTargetsAreRelativeToTheInstruction() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // ldr x0, .+4
        assertEquals(new MemoryOp64.LoadLiteral64(0, ADDRESS + 4, true, false), decodeWord(decoder, 0x58000020, ADDRESS));
        // ldrsw x0, .-4
        assertEquals(new MemoryOp64.LoadLiteral64(0, ADDRESS - 4, true, true), decodeWord(decoder, 0x98ffffe0, ADDRESS));
        // ldr q0, .+8
        assertEquals(new FpOp64.LoadLiteral64(0, ADDRESS + 8, Ir64FpMemSize.QUAD), decodeWord(decoder, 0x9c000040, ADDRESS));
    }

    @Test
    void addressingFieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // ldp x0, x1, [x2], #16
        assertEquals(new MemoryOp64.LoadStorePair(true, 0, 1, 2, true, Ir64AddressingMode.POST_INDEX, 16, false),
                decodeWord(decoder, 0xa8c10440, ADDRESS));
        // ldrsh w0, [x1, #-2]!
        assertEquals(new MemoryOp64.Load64(0, 1, Ir64MemSize.HALF, true, false, Ir64AddressingMode.PRE_INDEX, -2, -1,
                null, 0), decodeWord(decoder, 0x78dfec20, ADDRESS));
        // ldr x0, [x1, w2, sxtw #3]
        assertEquals(new MemoryOp64.Load64(0, 1, Ir64MemSize.DOUBLEWORD, false, true,
                Ir64AddressingMode.REGISTER_OFFSET, 0, 2, Ir64ExtendType.SXTW, 3), decodeWord(decoder, 0xf862d820, ADDRESS));
        // str q0, [x1, #32]
        assertEquals(new FpOp64.Store64(0, 1, Ir64FpMemSize.QUAD, Ir64AddressingMode.OFFSET, 32, -1, null, 0),
                decodeWord(decoder, 0x3d800820, ADDRESS));
        // ldraa x0, [x1, #8]!
        assertEquals(new MemoryOp64.Load64(0, 1, Ir64MemSize.DOUBLEWORD, false, true, Ir64AddressingMode.PRE_INDEX, 8,
                -1, null, 0), decodeWord(decoder, 0xf8201c20, ADDRESS));
        // stg x0, [x1], #16 — variant=01 é pós-índice
        assertEquals(new MemoryOp64.MemoryTag(MemoryOp64.Ir64MemoryTagOperation.STORE, false, 1, 0, 1,
                Ir64AddressingMode.POST_INDEX, 16), decodeWord(decoder, 0xd9201420, ADDRESS));
        // ld1 {v0.16b}, [x1], x2 · ld1 {v0.16b}, [x1], #16
        assertEquals(new AdvSimdMoveOp64.LoadStoreMultiple(true, 0, 1, 2, true, true, 0, 1, 1),
                decodeWord(decoder, 0x4cc27020, ADDRESS));
        assertEquals(new AdvSimdMoveOp64.LoadStoreMultiple(true, 0, 1, -1, true, true, 0, 1, 1),
                decodeWord(decoder, 0x4cdf7020, ADDRESS));
        // ld3 {v0.h, v1.h, v2.h}[5], [x1]
        assertEquals(new AdvSimdMoveOp64.LoadStoreSingle(true, 0, 1, -1, false, 1, 3, 5),
                decodeWord(decoder, 0x4d406820, ADDRESS));
    }

    @Test
    void atomicPairRejectsZeroOrRepeatedRegisters() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // ldclrp x0, x1, [x2]
        assertEquals(new MemoryOp64.AtomicMemoryOpPair(0, 1, 2, Ir64AtomicOp.CLR, false, false),
                decodeWord(decoder, 0x19211040, ADDRESS));
        for (int word : new int[] {0x1921105f, 0x193f1040, 0x19201040}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, word, ADDRESS),
                    Integer.toHexString(word));
        }
    }

    @ParameterizedTest(name = "{0} só com a feature")
    @CsvSource(delimiter = '|', textBlock = """
            c8a07c41 | cas x0, x1, [x2]
            48207c82 | casp x0, x1, x2, x3, [x4]
            f8200041 | ldadd x0, x1, [x2]
            f8bfc020 | ldapr x0, [x1]
            d9400020 | ldapur x0, [x1]
            19211040 | ldclrp x0, x1, [x2]
            f8201c20 | ldraa x0, [x1, #8]!
            69000440 | stgp x0, x1, [x2]
            d9200820 | stg x0, [x1]
            19010440 | cpyfp [x0]!, [x1]!, x2!
            d91f0c20 | gcsstr x0, [x1]
            """)
    void featureRowsAreAbsentFromTheBaseArchitecture(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A), value, ADDRESS), objdump);
        assertDoesNotThrow(() -> decodeWord(new Aarch64Decoder(NO_SME), value, ADDRESS), objdump);
    }

    /// `SETGP` exige MOPS **e** MTE: só uma das duas não basta.
    @Test
    void taggedMemorySetNeedsBothFeatures() {
        int setgp = 0x1dc20420;
        Aarch64Decoder mteOnly = new Aarch64Decoder(Aarch64Architecture.of("mte", Aarch64Feature.MEMORY_TAGGING));
        Aarch64Decoder mopsOnly = new Aarch64Decoder(Aarch64Architecture.of("mops", Aarch64Feature.MEMORY_COPY_SET));
        Aarch64Decoder both = new Aarch64Decoder(Aarch64Architecture.of("mte+mops", Aarch64Feature.MEMORY_TAGGING,
                Aarch64Feature.MEMORY_COPY_SET));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(mteOnly, setgp, ADDRESS));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(mopsOnly, setgp, ADDRESS));
        assertEquals(new MemoryOp64.MemorySetTagged(MemoryOp64.Ir64MopsPhase.PROLOGUE, 0, 1, 2),
                decodeWord(both, setgp, ADDRESS));
    }

    /// Coluna 1 = `.inst ... ; undefined` (ou outra instrução) no `objdump` 2.46 do devkitA64 e sem padrão no
    /// `a64.decode` do QEMU; coluna 2 = a vizinha legítima, como o `objdump` a desmonta. As 16 primeiras eram
    /// aceitas pela versão em cascata (G8); as outras já eram recusadas.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            c95f7c20 | c85f7c20 | ldxr x0, [x1] — bit24=1 (o objdump lê LDTXR, FEAT_LSUI)
            c8a00041 | c8a07c41 | cas x0, x1, [x2] — Rt2≠11111
            c880fc20 | c89ffc20 | stlr x0, [x1] — Rs≠11111
            c8dfc020 | c8dffc20 | ldar x0, [x1] — Rt2≠11111
            dd400020 | d9400020 | ldapur x0, [x1] — bit26=1
            59010440 | 19010440 | cpyfp [x0]!, [x1]!, x2! — size≠00
            59c20420 | 19c20420 | setp [x0]!, x1!, x2 — size≠00
            dd200820 | d9200820 | stg x0, [x1] — bit26=1
            d9a01020 | d9a00020 | stgm x0, [x1] — imm9≠0
            59211040 | 19211040 | ldclrp x0, x1, [x2] — size≠00
            68400440 | 69400440 | ldpsw x0, x1, [x2] — modo sem alocação
            68000440 | 69000440 | stgp x0, x1, [x2] — modo sem alocação
            4c607020 | 4c407020 | ld1 {v0.16b}, [x1] — múltiplo com bit21=1
            f8800420 | f8800020 | prfum pldl1keep, [x1] — idx≠00
            f8a22820 | f8a26820 | prfm pldl1keep, [x1, x2] — option<1>=0
            bc400820 | bc400020 | ldur s0, [x1] — SIMD&FP com idx=10
            0c400c20 | 0c400820 | ld4 {v0.2s-v3.2s}, [x1] — .1D com selem>1
            4c427020 | 4c407020 | ld1 {v0.16b}, [x1] — Rm≠0 sem pós-índice
            48217c82 | 48207c82 | casp x0, x1, x2, x3, [x4] — Rs ímpar
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord, ADDRESS), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord, ADDRESS), objdump);
    }
}
