package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalenceMismatchException;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B18.1 — fundação de estado SME: `SVCR`, `SMCR_EL1/2/3`, `ZA`/`ZT0` preguiçosos, `SVL` independente
/// de `VL`, `ID_AA64PFR1_EL1.SME`/`ID_AA64SMFR0_EL1` por preset, checagens de acesso SME (com síndrome
/// própria, ≠ SVE) e persistência. Testado em dois `SVL` (256 e 512).
class Aarch64MatrixStateTest {
    private static final long SVCR_SM = 1L;
    private static final long SVCR_ZA = 2L;
    private static final long SMCR_EZT0 = 1L << 30;
    private static final long SMCR_FA64 = 1L << 31;
    private static final long ID_AA64PFR1_SME_FIELD = 0xFL << 24;

    // mrs/msr xN, svcr — op0=3,op1=3,CRn=4,CRm=2,op2=2
    private static final int MRS_SVCR_X0 = 0xd53b4240;
    private static final int MSR_SVCR_X0 = 0xd51b4240;
    // mrs/msr xN, smcr_el{1,2,3} — op1=0/4/6, CRn=1, CRm=2, op2=6
    private static final int MRS_SMCR_EL1_X0 = 0xd53812c0;
    private static final int MSR_SMCR_EL1_X0 = 0xd51812c0;
    private static final int MRS_SMCR_EL2_X0 = 0xd53c12c0;
    private static final int MRS_SMCR_EL3_X0 = 0xd53e12c0;
    // mrs xN, id_aa64smfr0_el1 — op1=0,CRn=0,CRm=4,op2=5
    private static final int MRS_ID_AA64SMFR0_X0 = 0xd53804a0;

    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2P1 = Aarch64Architecture.extending(
            SME2, "teste-SME2p1", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);

    private static Aarch64Core smeCore(int svl) {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_2_A,
                Aarch64ScalableRegisters.DEFAULT_VECTOR_LENGTH_BITS, svl);
    }

    private static Aarch64Core plainCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), Aarch64Architecture.ARMV8_0_A);
    }

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Alocação preguiçosa (Aceite central) ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void zaIsNeverAllocatedUntilSvcrZaIsTurnedOn(int svl) {
        Aarch64Core core = smeCore(svl);
        assertFalse(core.matrix().zaAllocated());
        assertFalse(core.matrix().zt0Allocated());
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SVCR, SVCR_SM);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 1);
        core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SVCR);
        assertFalse(core.matrix().zaAllocated(), "SM sozinho e SMCR não alocam ZA");
        assertEquals(2, core.matrix().snapshot().length, "nunca alocado = só os 2 marcadores");
    }

    @Test
    void presetWithoutSmeNeverHasWorkToDo() {
        Aarch64Core core = plainCore();
        assertFalse(core.hasSme());
        assertFalse(core.matrix().zaAllocated());
        assertEquals(0, core.implementedStreamingVectorLengthBits());
        assertEquals(0, core.streamingVectorLengthBits());
        assertEquals(0, core.streamingVectorLengthBytes());
        assertEquals(0, core.matrixSnapshot().length);
        assertThrows(IllegalStateException.class, () -> core.setSvcr(SVCR_ZA));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void turningZaOnAllocatesASquareOfSvlBitsZeroed(int svl) {
        Aarch64Core core = smeCore(svl);
        core.setSvcr(SVCR_ZA);
        assertTrue(core.matrix().zaAllocated());
        int rowBytes = svl / 8;
        assertEquals(rowBytes, core.matrix().zaRowBytes());
        assertEquals(rowBytes * rowBytes, core.matrix().zaBytes(), "(SVL/8)² bytes");
        for (int i = 0; i < core.matrix().zaBytes() / Long.BYTES; i++) {
            assertEquals(0L, core.matrix().zaWord(i));
        }
    }

    @Test
    void enableWriteDisableEnableReturnsZeroedZaAndReleasesOnDisable() {
        Aarch64Core core = smeCore(256);
        core.setSvcr(SVCR_ZA);
        core.matrix().setZaWord(3, 0xDEADL);
        core.matrix().setZt0Word(2, 0xBEEFL);
        assertTrue(core.matrix().zt0Allocated());
        core.setSvcr(0);
        assertFalse(core.matrix().zaAllocated(), "desligar libera");
        assertFalse(core.matrix().zt0Allocated());
        assertThrows(IllegalStateException.class, () -> core.matrix().zaWord(0));
        assertThrows(IllegalStateException.class, () -> core.matrix().setZaWord(0, 1));
        assertThrows(IllegalStateException.class, () -> core.matrix().zt0Word(0));
        core.setSvcr(SVCR_ZA);
        assertEquals(0L, core.matrix().zaWord(3));
        assertEquals(0L, core.matrix().zt0Word(2), "ZT0 também volta zerado");
    }

    @Test
    void rewritingTheSameZaValueDoesNotZeroTheContents() {
        Aarch64Core core = smeCore(256);
        core.setSvcr(SVCR_ZA);
        core.matrix().setZaWord(1, 0x77L);
        core.setSvcr(SVCR_ZA | SVCR_SM);
        assertEquals(0x77L, core.matrix().zaWord(1), "só transição de ZA zera (aarch64_set_svcr do QEMU)");
        core.setSvcr(SVCR_ZA);
        assertEquals(0x77L, core.matrix().zaWord(1), "mudar SM não toca ZA");
    }

    @Test
    void svcrKeepsOnlySmAndZaAndCrossingIntoStreamingZeroesTheScalableState() {
        Aarch64Core core = smeCore(256);
        core.scalable().setZWord(2, 1, 0x1234L);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SVCR, ~0L);
        assertEquals(SVCR_SM | SVCR_ZA, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SVCR));
        assertTrue(core.streamingModeEnabled());
        assertTrue(core.zaEnabled());
        assertEquals(0L, core.scalable().zWord(2, 1), "B18.2: atravessar a fronteira de streaming zera Z/P/FFR");
    }

    // ── SMCR_ELx e SVL efetivo ───────────────────────────────────────────────────────────────

    @Test
    void smcrLenLimitsEffectiveSvlAndNeverExceedsImplemented() {
        Aarch64Core core = smeCore(512);
        assertEquals(512, core.implementedStreamingVectorLengthBits());
        assertEquals(512, core.streamingVectorLengthBits(), "reset = máximo");
        assertEquals(64, core.streamingVectorLengthBytes());
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 1);
        assertEquals(256, core.streamingVectorLengthBits());
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0);
        assertEquals(128, core.streamingVectorLengthBits(), "nunca abaixo do mínimo arquitetural");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0xF);
        assertEquals(512, core.streamingVectorLengthBits(), "nunca acima do implementado");
    }

    @Test
    void smcrOfHigherLevelsCapsTheLowerOnesAndEachLevelIgnoresLowerRegisters() {
        Aarch64Core core = smeCore(512);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, 1);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, 2);
        assertEquals(128, core.streamingVectorLengthBits(), "EL0: min dos três");
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertEquals(256, core.streamingVectorLengthBits(), "EL2: min(SMCR_EL2, SMCR_EL3)");
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertEquals(384, core.streamingVectorLengthBits(), "EL3: só SMCR_EL3");
    }

    @Test
    void smcrWriteMasksReservedBitsAndGatesEzt0OnSme2() {
        Aarch64Core sme1 = smeCore(256);
        sme1.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, ~0L);
        assertEquals(0xFL | SMCR_FA64, sme1.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1),
                "sem SME2, EZT0 é RES0");
        Aarch64Core sme2 = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        sme2.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, ~0L);
        assertEquals(0xFL | SMCR_FA64 | SMCR_EZT0,
                sme2.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3));
        assertEquals(0xFL, sme2.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2), "reset: LEN máximo");
    }

    @Test
    void svlAndVlAreIndependent() {
        Aarch64Core wideSvl = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_2_A, 256, 512);
        assertEquals(256, wideSvl.vectorLengthBits());
        assertEquals(512, wideSvl.streamingVectorLengthBits());
        Aarch64Core wideVl = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_2_A, 512, 256);
        assertEquals(512, wideVl.vectorLengthBits());
        assertEquals(256, wideVl.streamingVectorLengthBits());
        Aarch64Core threeArg = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_2_A, 512);
        assertEquals(Aarch64MatrixRegisters.DEFAULT_STREAMING_VECTOR_LENGTH_BITS,
                threeArg.streamingVectorLengthBits());
    }

    @Test
    void invalidStreamingVectorLengthsAreRejected() {
        for (int bad : new int[] {0, 64, 130, 4096}) {
            assertThrows(IllegalArgumentException.class, () -> new Aarch64MatrixRegisters(bad), "SVL=" + bad);
        }
    }

    // ── Decode de MRS/MSR (gateado por FEAT_SME) ─────────────────────────────────────────────

    @Test
    void smeRegistersDecodeOnlyWithSme() {
        Aarch64Decoder sme = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);
        Aarch64Decoder noSme = new Aarch64Decoder(Aarch64Architecture.ARMV9_0_A);
        Aarch64Decoder plain = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        Object[][] table = {
                {MRS_SVCR_X0, true, Aarch64SystemRegisterId.SVCR},
                {MSR_SVCR_X0, false, Aarch64SystemRegisterId.SVCR},
                {MRS_SMCR_EL1_X0, true, Aarch64SystemRegisterId.SMCR_EL1},
                {MSR_SMCR_EL1_X0, false, Aarch64SystemRegisterId.SMCR_EL1},
                {MRS_SMCR_EL2_X0, true, Aarch64SystemRegisterId.SMCR_EL2},
                {MRS_SMCR_EL3_X0, true, Aarch64SystemRegisterId.SMCR_EL3},
                {MRS_ID_AA64SMFR0_X0, true, Aarch64SystemRegisterId.ID_AA64SMFR0_EL1},
        };
        for (Object[] row : table) {
            int word = (Integer) row[0];
            Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(sme, word);
            assertEquals(row[1], op.read(), Integer.toHexString(word));
            assertEquals(row[2], op.register(), Integer.toHexString(word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(noSme, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(plain, word));
        }
    }

    @Test
    void neighbouringEncodingsOfSmeRegistersAreNotMisdecoded() {
        Aarch64Decoder sme = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);
        int[] neighbours = {
                0xd5381220, // (op1=0,CRn=1,CRm=2,op2=1) — entre ZCR_EL1 e SMCR_EL1
                0xd53c1220, // idem em EL2
                0xd53e1220, // idem em EL3
                0xd53c1300, // (op1=4,CRn=1,CRm=3,op2=0) — CRn de SMCR, CRm diferente (EL2)
                0xd53e1300, // idem em EL3
                0xd53804c0, // (0,0,4,6) — vizinho de ID_AA64SMFR0_EL1
                0xd53b4260, // (op1=3,CRn=4,CRm=2,op2=3) — vizinho de SVCR
        };
        for (int word : neighbours) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(sme, word),
                    "G8: " + Integer.toHexString(word) + " não pode virar outro registrador SME");
        }
    }

    // ── ID registers ─────────────────────────────────────────────────────────────────────────

    @Test
    void idRegistersAdvertiseSmeOnlyWhenThePresetDeclaresIt() {
        Aarch64Core sme = smeCore(256);
        assertEquals(1L << 24, sme.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR1_EL1)
                & ID_AA64PFR1_SME_FIELD);
        assertEquals(0L, plainCore().readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR1_EL1)
                & ID_AA64PFR1_SME_FIELD);
        Aarch64Core sveOnly = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_0_A);
        assertEquals(0L, sveOnly.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR1_EL1)
                & ID_AA64PFR1_SME_FIELD, "ARMv9.0-A tem SVE mas não SME");
    }

    @Test
    void smfr0AdvertisesOnlySmeVersionAndNoCapabilityField() {
        assertEquals(0L, smeCore(256).readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1),
                "SME 1: SMEver=0 e nenhum campo de capacidade (Armadilha 5)");
        assertEquals(0L, plainCore().readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1));
        Aarch64Core sme2 = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        assertEquals(1L << 56, sme2.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1));
        assertEquals(2L << 24, sme2.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR1_EL1)
                & ID_AA64PFR1_SME_FIELD);
        Aarch64Core sme21 = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2P1, 256, 256);
        assertEquals(2L << 56, sme21.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1));
        assertThrows(UnsupportedOperationException.class,
                () -> sme21.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1, 1));
    }

    // ── Checagens de acesso SME ──────────────────────────────────────────────────────────────

    /// Barramento mínimo com `CPACR_EL1`/`CPTR_EL2`/`CPTR_EL3` controláveis.
    private static final class TrapRegisters implements Aarch64SystemRegisterBus {
        long cpacr;
        long cptrEl2;
        long cptrEl3 = 1L << 12;

        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1
                    || register == Aarch64SystemRegisterId.CPTR_EL2
                    || register == Aarch64SystemRegisterId.CPTR_EL3;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return switch (register) {
                case CPACR_EL1 -> cpacr;
                case CPTR_EL2 -> cptrEl2;
                default -> cptrEl3;
            };
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long value) {
            throw new UnsupportedOperationException();
        }
    }

    private static long esrEc(Aarch64Core core, Aarch64ExceptionLevel el) {
        return core.exceptionState().esr(el) >>> 26;
    }

    private static long esrSmtc(Aarch64Core core, Aarch64ExceptionLevel el) {
        return core.exceptionState().esr(el) & 0x7L;
    }

    @Test
    void accessIsAllowedWhenNoTrapStateIsModeled() {
        Aarch64Core core = smeCore(256);
        assertEquals(Optional.empty(), core.smeAccessTrapLevel());
        assertTrue(core.smeEnabledCheck(0x1000));
    }

    @Test
    void cpacrSmenDeniesAccessAndEntersTheSmeTrapNotTheSveOne() {
        Aarch64Core core = smeCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        regs.cpacr = 0b00L << 24;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.smeAccessTrapLevel());
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeEnabledCheck(0x1234));
        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl());
        assertEquals(0x1DL, esrEc(core, Aarch64ExceptionLevel.EL1), "EC=0x1D (SME), não 0x19 (SVE)");
        assertEquals(0L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SMTC=0: acesso trapado");
        assertEquals(0x1234L, core.exceptionState().elr(Aarch64ExceptionLevel.EL1));
        // ZEN (bits 17:16) NÃO governa SME — o bit certo é SMEN (25:24).
        Aarch64Core other = smeCore(256);
        TrapRegisters onlyZenOff = new TrapRegisters();
        onlyZenOff.cpacr = 0b11L << 24;
        other.setSystemRegisterBus(onlyZenOff);
        assertEquals(Optional.empty(), other.smeAccessTrapLevel());
    }

    @Test
    void cpacrSmenValuesBehaveAsSpecifiedPerLevel() {
        Aarch64Core core = smeCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        for (long smen : new long[] {0b00, 0b01, 0b10}) {
            regs.cpacr = smen << 24;
            assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.smeAccessTrapLevel(), "EL0 SMEN=" + smen);
        }
        regs.cpacr = 0b11L << 24;
        assertEquals(Optional.empty(), core.smeAccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        regs.cpacr = 0b01L << 24;
        assertEquals(Optional.empty(), core.smeAccessTrapLevel(), "SMEN=01 só trapa EL0");
        regs.cpacr = 0b10L << 24;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.smeAccessTrapLevel());
    }

    @Test
    void cptrEl2TsmAndCptrEl3EsmTrapToTheirLevels() {
        Aarch64Core core = smeCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        regs.cpacr = 0b11L << 24;
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        regs.cptrEl2 = 1L << 12;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), core.smeAccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), core.smeAccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertEquals(Optional.empty(), core.smeAccessTrapLevel(), "EL3 não sofre CPTR_EL2");
        regs.cptrEl2 = 0;
        regs.cptrEl3 = 0;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), core.smeAccessTrapLevel(), "ESM=0 trapa para EL3");
    }

    @Test
    void neverTrapsWithoutSme() {
        Aarch64Core core = plainCore();
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        assertEquals(Optional.empty(), core.smeAccessTrapLevel());
        assertEquals(Optional.empty(), core.zt0AccessTrapLevel());
    }

    @Test
    void streamingCheckRaisesNotStreamingWithSmZeroAndPassesWithSmOne() {
        Aarch64Core core = smeCore(256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeStreamingEnabledCheck(0x40));
        assertEquals(0x1DL, esrEc(core, Aarch64ExceptionLevel.EL1));
        assertEquals(2L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SMTC=2: não está em streaming");
        Aarch64Core streaming = smeCore(256);
        streaming.setSvcr(SVCR_SM);
        assertTrue(streaming.smeStreamingEnabledCheck(0x40));
    }

    @Test
    void zaCheckRaisesInactiveZaWithZaZeroAndPassesWithZaOne() {
        Aarch64Core core = smeCore(256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeZaEnabledCheck(0x44));
        assertEquals(3L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SMTC=3: ZA inativo");
        Aarch64Core on = smeCore(256);
        on.setSvcr(SVCR_ZA);
        assertTrue(on.smeZaEnabledCheck(0x44));
    }

    @Test
    void streamingAndZaCheckTestsSmBeforeZa() {
        Aarch64Core core = smeCore(256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeStreamingAndZaEnabledCheck(0x48));
        assertEquals(2L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SM é conferido primeiro");
        Aarch64Core onlySm = smeCore(256);
        onlySm.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        onlySm.setSvcr(SVCR_SM);
        assertFalse(onlySm.smeStreamingAndZaEnabledCheck(0x48));
        assertEquals(3L, esrSmtc(onlySm, Aarch64ExceptionLevel.EL1));
        Aarch64Core both = smeCore(256);
        both.setSvcr(SVCR_SM | SVCR_ZA);
        assertTrue(both.smeStreamingAndZaEnabledCheck(0x48));
    }

    @Test
    void notStreamingAndInactiveZaFromEl2StayAtEl2() {
        Aarch64Core core = smeCore(256);
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL2, 0x9000);
        assertFalse(core.smeStreamingEnabledCheck(0x50));
        assertEquals(Aarch64ExceptionLevel.EL2, core.exceptionState().currentEl(),
                "EL atual mais privilegiado que EL1 não é rebaixado");
        assertEquals(2L, esrSmtc(core, Aarch64ExceptionLevel.EL2));
    }

    @Test
    void accessTrapTakesPrecedenceOverStreamingAndZaChecks() {
        Aarch64Core core = smeCore(256);
        TrapRegisters regs = new TrapRegisters();
        regs.cpacr = 0L;
        core.setSystemRegisterBus(regs);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeStreamingEnabledCheck(0x54));
        assertEquals(0L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SMTC=0 vence SMTC=2");
        Aarch64Core za = smeCore(256);
        za.setSystemRegisterBus(regs);
        za.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(za.smeZaEnabledCheck(0x58));
        assertEquals(0L, esrSmtc(za, Aarch64ExceptionLevel.EL1));
    }

    @Test
    void zt0IsInaccessibleUntilSmcrEzt0IsSetAtEveryModeledLevel() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        core.setSvcr(SVCR_ZA);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.zt0AccessTrapLevel());
        assertFalse(core.smeZt0EnabledCheck(0x60));
        assertEquals(0x1DL, esrEc(core, Aarch64ExceptionLevel.EL1));
        assertEquals(4L, esrSmtc(core, Aarch64ExceptionLevel.EL1), "SMTC=4: ZT0 inacessível");

        Aarch64Core ok = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        ok.setSvcr(SVCR_ZA);
        ok.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, SMCR_EZT0);
        assertEquals(Optional.empty(), ok.zt0AccessTrapLevel(),
                "sem EL2/EL3 modelados, só SMCR_EL1.EZT0 conta (boot direto em EL1)");
        assertTrue(ok.smeZt0EnabledCheck(0x60));

        Aarch64Core modeled = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        TrapRegisters regs = new TrapRegisters();
        regs.cpacr = 0b11L << 24;
        modeled.setSystemRegisterBus(regs);
        modeled.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        modeled.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, SMCR_EZT0);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), modeled.zt0AccessTrapLevel(), "SMCR_EL2.EZT0=0");
        modeled.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, SMCR_EZT0);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), modeled.zt0AccessTrapLevel(), "SMCR_EL3.EZT0=0");
        modeled.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, SMCR_EZT0);
        assertEquals(Optional.empty(), modeled.zt0AccessTrapLevel());
    }

    @Test
    void zt0LevelsAboveTheCurrentElAreTheOnlyOnesConsulted() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        TrapRegisters regs = new TrapRegisters();
        regs.cpacr = 0b11L << 24;
        core.setSystemRegisterBus(regs);
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), core.zt0AccessTrapLevel(),
                "EL2 ignora SMCR_EL1 e cai em SMCR_EL2.EZT0=0");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, SMCR_EZT0);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), core.zt0AccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), core.zt0AccessTrapLevel(),
                "EL3 só olha SMCR_EL3");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, SMCR_EZT0);
        assertEquals(Optional.empty(), core.zt0AccessTrapLevel());
    }

    @Test
    void sme21AloneAlsoRaisesThePfr1FieldToSme2() {
        Aarch64Architecture only21 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-SME2p1-so",
                Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), only21, 256, 256);
        assertFalse(core.hasSme2());
        assertEquals(2L << 24, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR1_EL1)
                & ID_AA64PFR1_SME_FIELD);
        assertEquals(2L << 56, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64SMFR0_EL1));
    }

    @Test
    void zt0CheckStillRequiresZaAndSme2() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, 256);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.smeZt0EnabledCheck(0x64), "ZA desligado falha antes do ZT0");
        assertEquals(3L, esrSmtc(core, Aarch64ExceptionLevel.EL1));
        assertEquals(Optional.empty(), smeCore(256).zt0AccessTrapLevel(), "SME 1 não tem ZT0");
    }

    // ── Persistência e snapshot ──────────────────────────────────────────────────────────────

    private static byte[] save(Aarch64Core core) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            core.saveMatrixState(out);
        }
        return bytes.toByteArray();
    }

    private static void load(Aarch64Core core, byte[] data) throws IOException {
        core.loadMatrixState(new DataInputStream(new ByteArrayInputStream(data)));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void saveAndRestorePreservesSvcrSmcrZaAndZt0BitForBit(int svl) throws IOException {
        Aarch64Core a = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, svl);
        a.setSvcr(SVCR_SM | SVCR_ZA);
        a.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, SMCR_EZT0 | 1);
        a.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, SMCR_FA64 | 2);
        a.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, 3);
        for (int i = 0; i < a.matrix().zaBytes() / Long.BYTES; i++) {
            a.matrix().setZaWord(i, 0x0101010101010101L * (i + 1));
        }
        for (int i = 0; i < 8; i++) {
            a.matrix().setZt0Word(i, 0xF0F0L + i);
        }
        Aarch64Core b = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), SME2, 256, svl);
        load(b, save(a));
        assertArrayEquals(a.matrixSnapshot(), b.matrixSnapshot());
        assertEquals(a.svcr(), b.svcr());
        assertEquals(SVCR_SM | SVCR_ZA, b.svcr());
    }

    @Test
    void restoringAStateThatNeverAllocatedZaDoesNotAllocateIt() throws IOException {
        Aarch64Core a = smeCore(512);
        a.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 2);
        byte[] data = save(a);
        assertTrue(data.length < 128, "estado sem ZA é pequeno: " + data.length + " bytes");
        Aarch64Core b = smeCore(512);
        b.setSvcr(SVCR_ZA);
        b.matrix().setZaWord(0, 9);
        load(b, data);
        assertFalse(b.matrix().zaAllocated(), "restore de estado sem ZA libera/não cria");
        assertFalse(b.matrix().zt0Allocated());
        assertEquals(2L, b.readIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1));
    }

    @Test
    void restoreRejectsDifferentSvlAndUnknownVersions() throws IOException {
        byte[] data = save(smeCore(256));
        assertThrows(IOException.class, () -> load(smeCore(512), data), "SVL diferente");
        byte[] badCore = data.clone();
        badCore[3] = 99;
        assertThrows(IOException.class, () -> load(smeCore(256), badCore), "versão do core");
        // versão do armazenamento: fica logo após a versão(4) + svcr(8) + smcr×3(24) do core
        byte[] badMatrix = data.clone();
        badMatrix[4 + 8 + 24 + 3] = 99;
        assertThrows(IOException.class, () -> load(smeCore(256), badMatrix), "versão do armazenamento");
    }

    @Test
    void snapshotCapturesMatrixStateAndDetectsDivergenceInIt() {
        Aarch64Core a = smeCore(256);
        Aarch64Core b = smeCore(256);
        a.setSvcr(SVCR_ZA);
        a.matrix().setZaWord(5, 0xABCL);
        Aarch64CpuSnapshot snapA = Aarch64CpuSnapshot.capture(a);
        Aarch64CpuSnapshot snapB = Aarch64CpuSnapshot.capture(b);
        assertThrows(EquivalenceMismatchException.class, () -> snapA.assertEqualTo(snapB, "za"));
        b.setSvcr(SVCR_ZA);
        b.matrix().setZaWord(5, 0xABCL);
        snapA.assertEqualTo(Aarch64CpuSnapshot.capture(b), "igual");
        assertEquals(0, Aarch64CpuSnapshot.capture(plainCore()).matrixState().length);
    }

    @Test
    void legacySnapshotConstructorsStillWork() {
        var eight = new Aarch64CpuSnapshot(new long[31], 0, 0, 0, 0, 0, 0, new long[64]);
        assertEquals(0, eight.matrixState().length);
        assertEquals(0, eight.scalableState().length);
        var nine = new Aarch64CpuSnapshot(new long[31], 0, 0, 0, 0, 0, 0, new long[64], new long[2]);
        assertEquals(0, nine.matrixState().length);
        assertEquals(2, nine.scalableState().length);
    }

    // ── Aarch64MatrixRegisters isolado ───────────────────────────────────────────────────────

    @Test
    void zt0MaterializesZeroedOnFirstAccessAndOnlyWithZa() {
        Aarch64MatrixRegisters m = new Aarch64MatrixRegisters(128);
        assertThrows(IllegalStateException.class, () -> m.setZt0Word(0, 1));
        m.enableZa();
        assertFalse(m.zt0Allocated());
        assertEquals(0L, m.zt0Word(7));
        assertTrue(m.zt0Allocated());
        assertEquals(128, m.streamingVectorLengthBits());
        assertEquals(16 * 16, m.zaBytes());
    }

    @Test
    void loadStateRejectsUnknownVersion() {
        Aarch64MatrixRegisters m = new Aarch64MatrixRegisters(128);
        byte[] bad = {0, 0, 0, 42, 0, 0, 0, (byte) 128, 0, 0};
        assertThrows(IOException.class, () -> m.loadState(new DataInputStream(new ByteArrayInputStream(bad))));
    }
}
