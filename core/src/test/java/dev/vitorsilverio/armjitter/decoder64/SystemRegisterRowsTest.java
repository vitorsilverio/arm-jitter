package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// E15.11: `MRS`/`MSR` como tabela gerada do {@link Aarch64SystemRegisterId} ({@link SystemRegisterRows}) —
/// encoding único por constante, feature como coluna e a prioridade dos escaninhos de debug e de ID.
class SystemRegisterRowsTest {
    /// `mrs x0, <reg>` com `Rt=0` e os `bits[20:5]` em zero.
    private static final int MRS_X0 = 0xd5300000;
    /// `L=1` (`MRS`); com `L=0` é `MSR`.
    private static final int READ_BIT = 1 << 21;
    private static final int ENCODING_SHIFT = 5;
    private static final Set<Aarch64SystemRegisterId> WITHOUT_ENCODING = EnumSet.of(
            Aarch64SystemRegisterId.ID_RESERVED_RAZ, Aarch64SystemRegisterId.DEBUG_UNMODELED,
            Aarch64SystemRegisterId.PAR_EL1);

    @Test
    void everyNamedRegisterHasAUniqueEncoding() {
        Set<Integer> seen = new HashSet<>();
        for (Aarch64SystemRegisterId register : Aarch64SystemRegisterId.values()) {
            if (WITHOUT_ENCODING.contains(register)) {
                assertEquals(Aarch64SystemRegisterId.NO_ENCODING, register.encoding(), register.name());
            } else {
                assertTrue(seen.add(register.encoding()), () -> "encoding repetido: " + register);
            }
        }
        assertEquals(Aarch64SystemRegisterId.values().length - WITHOUT_ENCODING.size(), SystemRegisterRows.ROWS.size());
    }

    @Test
    void rowsNeverOverlapWithinEachTable() {
        DecodeTableInvariants.assertNoOverlap(SystemRegisterRows.ROWS);
        DecodeTableInvariants.assertNoOverlap(SystemRegisterRows.FALLBACK_ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTableInvariants.assertReachable(DecodeTable.forFeatures(SystemRegisterRows.ROWS, ALL::has), 0, 0xE1511L);
        DecodeTableInvariants.assertReachable(
                DecodeTable.forFeatures(SystemRegisterRows.FALLBACK_ROWS, ALL::has), 0, 0xE1511L);
    }

    /// `MRS` e `MSR` de cada registrador nomeado, pelo decoder, com todas as features.
    @Test
    void everyNamedRegisterDecodesBothDirections() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        SplittableRandom random = new SplittableRandom(0xE1511L);
        for (Aarch64SystemRegisterId register : Aarch64SystemRegisterId.values()) {
            if (WITHOUT_ENCODING.contains(register)) {
                continue;
            }
            int rt = random.nextInt(32);
            int msr = MRS_X0 & ~READ_BIT | register.encoding() << ENCODING_SHIFT | rt;
            assertEquals(new SystemOp64.SystemRegister(true, register, rt), decodeWord(decoder, msr | READ_BIT));
            assertEquals(new SystemOp64.SystemRegister(false, register, rt), decodeWord(decoder, msr));
        }
    }

    /// Sem a feature, o registrador é UNDEFINED — exceto `ID_AA64SMFR0_EL1`, que cai no escaninho RAZ do
    /// espaço de identidade.
    @Test
    void featureRegistersFollowTheirColumn() {
        Aarch64Decoder base = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        Aarch64Decoder all = new Aarch64Decoder(ALL);
        for (Aarch64SystemRegisterId register : Aarch64SystemRegisterId.values()) {
            if (register.requires() == null || register == Aarch64SystemRegisterId.ID_AA64SMFR0_EL1) {
                continue;
            }
            int word = MRS_X0 | register.encoding() << ENCODING_SHIFT;
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(base, word), register.name());
            assertEquals(new SystemOp64.SystemRegister(true, register, 0), decodeWord(all, word));
        }
        int smfr0 = MRS_X0 | Aarch64SystemRegisterId.ID_AA64SMFR0_EL1.encoding() << ENCODING_SHIFT;
        assertEquals(read(Aarch64SystemRegisterId.ID_RESERVED_RAZ), decodeWord(base, smfr0));
        assertEquals(read(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1), decodeWord(all, smfr0));
    }

    @Test
    void catchAllsOnlyCoverWhatNoNamedRegisterClaims() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        assertEquals(read(Aarch64SystemRegisterId.MDSCR_EL1), decodeWord(decoder, 0xd5300240)); // mrs x0, mdscr_el1
        assertEquals(read(Aarch64SystemRegisterId.DEBUG_UNMODELED), decodeWord(decoder, 0xd5310000)); // s2_1_c0_c0_0
        assertEquals(read(Aarch64SystemRegisterId.ID_AA64ISAR0_EL1), decodeWord(decoder, 0xd5380600));
        assertEquals(read(Aarch64SystemRegisterId.ID_RESERVED_RAZ), decodeWord(decoder, 0xd5380660)); // id_aa64isar3_el1
        // fora dos escaninhos: CRm=0 do espaço de ID, s3_0_c15_c0_0 e PAR_EL1 (sem MRS ainda)
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xd5380020));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xd538f000));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xd5387400));
    }

    private static Ir64Op read(Aarch64SystemRegisterId register) {
        return new SystemOp64.SystemRegister(true, register, 0);
    }
}
