package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.BranchOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.11: branches e exceção como tabela ({@link BranchExceptionRows}) — invariantes da tabela, roteamento
/// pelo {@link Aarch64Decoder}, endereço/sinal dos alvos, as duas tabelas de `cc` do `CB<cc>`, gates de
/// feature como coluna e os G8 que a versão em cascata tinha.
class BranchExceptionRowsTest {
    static final Aarch64Architecture ALL = Aarch64Architecture.of("all", Aarch64Feature.values());
    private static final int ROW_COUNT = 28;
    private static final long ADDRESS = 0x1000L;

    static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeAt(Aarch64Decoder decoder, int word, long address) {
        TestAddressSpace raw = new TestAddressSpace((int) address + 4);
        raw.put32((int) address, word);
        return decoder.decode(AddressSpace64.wrapping(raw), address);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, BranchExceptionRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(BranchExceptionRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(BranchExceptionRows.ROWS, ALL);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1511L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        SplittableRandom random = new SplittableRandom(0xE1511L);
        for (DecodeRow<Ir64Op> row : BranchExceptionRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, 0L), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void baseArchitectureKeepsOnlyTheRowsWithoutFeature() {
        assertEquals(ROW_COUNT - 8 - 6,
                DecodeTable.forArchitecture(BranchExceptionRows.ROWS, Aarch64Architecture.ARMV8_0_A).rows().size());
    }

    @Test
    void immediateTargetsAreRelativeToTheInstructionAndSignExtended() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        // bl .-4
        assertEquals(new BranchOp64.Branch64(Ir64BranchForm.IMMEDIATE, ADDRESS, ADDRESS - 4, -1, true,
                Ir64Condition.AL), decodeAt(decoder, 0x97ffffff, ADDRESS));
        // b.ne .+8
        assertEquals(new BranchOp64.Branch64(Ir64BranchForm.IMMEDIATE, ADDRESS, ADDRESS + 8, -1, false,
                Ir64Condition.NE), decodeAt(decoder, 0x54000041, ADDRESS));
        // cbnz w3, .-8
        assertEquals(new BranchOp64.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 3, false, -1, true, ADDRESS - 8),
                decodeAt(decoder, 0x35ffffc3, ADDRESS));
        // tbz x5, #33, .+12
        assertEquals(new BranchOp64.CompareBranch64(Ir64CompareBranchForm.TBZ_TBNZ, 5, true, 33, false, ADDRESS + 12),
                decodeAt(decoder, 0xb6080065, ADDRESS));
    }

    /// As formas registrador e imediata do `CB<cc>` codificam `cc` de forma diferente (`trans_CB_cond` ×
    /// `trans_CB_cond_imm` do QEMU): `cc=001` é `GE` numa e `LT` na outra.
    @Test
    void compareAndBranchConditionsDifferBetweenForms() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        // cbge w1, w2, .+8 — cc=001
        assertEquals(new BranchOp64.CompareAndBranchRegister(Ir64CompareBranchCondition.GREATER_OR_EQUAL, 1, 2,
                Ir64MemSize.WORD, ADDRESS + 8), decodeAt(decoder, 0x74220041, ADDRESS));
        // cblt x1, #5, .+8 — cc=001
        assertEquals(new BranchOp64.CompareAndBranchImmediate(Ir64CompareBranchCondition.LESS_THAN, 1, true, 5,
                ADDRESS + 8), decodeAt(decoder, 0xf5228041, ADDRESS));
        // cbbhi w1, w2, .+8 — CBB, cc=010
        assertEquals(new BranchOp64.CompareAndBranchRegister(Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 1,
                2, Ir64MemSize.BYTE, ADDRESS + 8), decodeAt(decoder, 0x74428041, ADDRESS));
    }

    @Test
    void exceptionGeneratingKeepsTheImmediateWhereItMatters() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        assertEquals(new SystemOp64.Svc(0x1234), decodeWord(decoder, 0xd4024681));
        assertEquals(new SystemOp64.PrivilegedCall(true), decodeWord(decoder, 0xd4024682));
        assertEquals(new SystemOp64.PrivilegedCall(false), decodeWord(decoder, 0xd4024683));
        assertEquals(new SystemOp64.Breakpoint(0x1234), decodeWord(decoder, 0xd4224680));
        assertEquals(new SystemOp64.UndefinedInstructionTrap(), decodeWord(decoder, 0xd4424680));
    }

    @Test
    void returnAuthenticatedBranchesToTheLinkRegister() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        // retab
        assertEquals(new BranchOp64.Branch64(Ir64BranchForm.REGISTER, 0, 0, 30, false, Ir64Condition.AL),
                decodeWord(decoder, 0xd65f0fff));
        // blraa x3, x4
        assertEquals(new BranchOp64.Branch64(Ir64BranchForm.REGISTER, 0, 0, 3, true, Ir64Condition.AL),
                decodeWord(decoder, 0xd73f0864));
    }

    @ParameterizedTest(name = "{0} só com a feature")
    @CsvSource(delimiter = '|', textBlock = """
            74220041 | cbge w1, w2, .+8
            f5228041 | cblt x1, #5, .+8
            d65f0fff | retab
            d73f0864 | blraa x3, x4
            """)
    void featureRowsAreAbsentFromTheBaseArchitecture(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A), value), objdump);
        assertDoesNotThrow(() -> decodeWord(new Aarch64Decoder(ALL), value), objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64 e sem padrão no `a64.decode` do
    /// QEMU; coluna 2 = a vizinha legítima, como o `objdump` a desmonta. As 3 primeiras eram aceitas pela
    /// versão em cascata (G8); as outras já eram recusadas.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            f4428041 | 74428041 | cbbhi w1, w2, .+8 — CBB com sf=1
            f442c041 | 7442c041 | cbhhi w1, w2, .+8 — CBH com sf=1
            d69f00e0 | d69f03e0 | eret — ERET com Rn≠11111
            74824041 | 74220041 | cbge w1, w2, .+8 — CB<cc> com cc=100
            74224041 | 74220041 | cbge w1, w2, .+8 — CB<cc> com esz=01
            f522c041 | f5228041 | cblt x1, #5, .+8 — CB<cc> imediato com bit14=1
            d4224681 | d4224680 | brk #0x1234 — BRK com LL≠00
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }

    /// `bc.ne` (`o0=1`, `FEAT_HBC`) é instrução real ainda sem linha — recusada, não confundida com `b.ne`.
    @Test
    void hintedConditionalBranchIsNotMistakenForBranchConditional() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(new Aarch64Decoder(ALL), 0x54000051));
    }
}
