package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.11: hints/barreiras/PSTATE/`SYS` como tabela ({@link SystemInstructionRows}) — invariantes das duas
/// tabelas, prioridade do fallback, roteamento pelo {@link Aarch64Decoder} e os G8 de `op0=00` que a versão
/// em cascata tinha (`L`, `op1`, `Rt` e `CRm` não conferidos).
class SystemInstructionRowsTest {
    private static final int ROW_COUNT = 40;
    private static final int FALLBACK_ROW_COUNT = 15;

    @Test
    void rowsNeverOverlapWithinEachTable() {
        assertEquals(ROW_COUNT, SystemInstructionRows.ROWS.size());
        assertEquals(FALLBACK_ROW_COUNT, SystemInstructionRows.FALLBACK_ROWS.size());
        DecodeTableInvariants.assertNoOverlap(SystemInstructionRows.ROWS);
        DecodeTableInvariants.assertNoOverlap(SystemInstructionRows.FALLBACK_ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTableInvariants.assertReachable(DecodeTable.forArchitecture(SystemInstructionRows.ROWS, ALL), 0, 0xE1511L);
        DecodeTableInvariants.assertReachable(
                DecodeTable.forArchitecture(SystemInstructionRows.FALLBACK_ROWS, ALL), 0, 0xE1511L);
    }

    /// Palavras do fallback que nenhuma linha específica casa decodificam pelo fallback; as que casam uma
    /// linha específica, por ela.
    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        SplittableRandom random = new SplittableRandom(0xE1511L);
        for (DecodeRow<Ir64Op> row : SystemInstructionRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, 0L), decodeWord(decoder, word), Integer.toHexString(word));
        }
        for (DecodeRow<Ir64Op> row : SystemInstructionRows.FALLBACK_ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            DecodeRow<Ir64Op> owner = SystemInstructionRows.ROWS.stream().filter(r -> r.matches(word)).findFirst()
                    .orElse(row);
            assertEquals(owner.build().decode(word, 0L), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void specificRowsWinOverTheirFallback() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        assertEquals(system(Ir64SystemInstructionOp.WFI), decodeWord(decoder, 0xd503207f)); // wfi
        assertEquals(system(Ir64SystemInstructionOp.NOP_HINT), decodeWord(decoder, 0xd503203f)); // yield
        assertEquals(system(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL),
                decodeWord(decoder, 0xd508751f)); // ic iallu
        assertEquals(new SystemOp64.SystemInstruction(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_BY_VA, 2),
                decodeWord(decoder, 0xd50b7522)); // ic ivau, x2
        assertEquals(system(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP), decodeWord(decoder, 0xd50b7e20)); // dc civac
        assertEquals(new SystemOp64.AddressTranslate(Aarch64AddressTranslateForm.S12E0W, 4),
                decodeWord(decoder, 0xd50c78e4)); // at s12e0w, x4
        assertEquals(system(Ir64SystemInstructionOp.TLBI_ALL), decodeWord(decoder, 0xd508871f)); // tlbi vmalle1
        assertEquals(system(Ir64SystemInstructionOp.MAINTENANCE_UNMODELED_NOP),
                decodeWord(decoder, 0xd509871f)); // sys #1, c8, c7, #0 — TLBI fora dos 3 regimes
        assertEquals(system(Ir64SystemInstructionOp.MAINTENANCE_UNMODELED_NOP),
                decodeWord(decoder, 0xd5280000)); // sysl x0, #0, c0, c0, #0
    }

    /// `AT` com regime/forma inexistente e `DC ZVA` ficam fora até do resto de `CRn=0111`.
    @ParameterizedTest(name = "{0} ({1}) recusada")
    @CsvSource(delimiter = '|', textBlock = """
            d50b7420 | dc zva, x0
            d5097800 | sys #1, c7, c8, #0 — AT com op1=001
            d50c7840 | sys #4, c7, c8, #2 — AT EL2 com op2=010
            d50e7880 | sys #6, c7, c8, #4 — AT EL3 com op2=100
            """)
    void addressTranslateGapsAndDataCacheZeroAreRejected(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(new Aarch64Decoder(ALL), value), objdump);
    }

    @Test
    void pstateImmediateCarriesItsFields() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        assertEquals(new SystemOp64.InterruptMask(true, 2), decodeWord(decoder, 0xd50342df)); // msr daifset, #2
        assertEquals(new SystemOp64.InterruptMask(false, 0xf), decodeWord(decoder, 0xd5034fff)); // msr daifclr, #15
        // smstart (SM e ZA) e smstop za
        assertEquals(new SystemOp64.StreamingModeControl(true, true, true, 0), decodeWord(decoder, 0xd503477f));
        assertEquals(new SystemOp64.StreamingModeControl(false, false, true, 0), decodeWord(decoder, 0xd503447f));
    }

    @ParameterizedTest(name = "{0} só com a feature")
    @CsvSource(delimiter = '|', textBlock = """
            d5031003 | wfet x3
            d500401f | cfinv
            d500403f | xaflag
            d500407f | msr uao, #0
            d500409f | msr pan, #0
            d501411f | msr allint, #1
            d503415f | msr dit, #1
            d503477f | smstart
            """)
    void featureRowsAreAbsentFromTheBaseArchitecture(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A), value), objdump);
        assertDoesNotThrow(() -> decodeWord(new Aarch64Decoder(ALL), value), objdump);
    }

    /// Coluna 1 = sem padrão no `a64.decode` do QEMU (o `objdump` imprime `msr s0_...`, não `undefined`);
    /// coluna 2 = a vizinha legítima, como o `objdump` 2.46 a desmonta. Todas eram aceitas pela versão em
    /// cascata (G8).
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            d500201f | d503201f | nop — hint com op1=000
            d503201e | d503201f | nop — hint com Rt≠11111
            d523201f | d503201f | nop — hint com L=1
            d5003f9f | d5033f9f | dsb sy — barreira com op1=000
            d5033f9e | d5033f9f | dsb sy — barreira com Rt≠11111
            d50331ff | d50330ff | sb — SB com CRm≠0000
            d5033f3f | d5033e3f | dsb synxs — DSB nXS com CRm≠xx10
            d500411f | d500401f | cfinv — CFINV com CRm≠0000
            d501421f | d501411f | msr allint, #1 — ALLINT com CRm≠000x
            d5034f7f | d503477f | smstart — SVCR com CRm<3>=1
            d5031103 | d5031003 | wfet x3 — WFET com CRm≠0000
            d5001003 | d5031003 | wfet x3 — WFET com op1=000
            d50342de | d50342df | msr daifset, #2 — PSTATE com Rt≠11111
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }

    private static Ir64Op system(Ir64SystemInstructionOp opcode) {
        return new SystemOp64.SystemInstruction(opcode);
    }
}
