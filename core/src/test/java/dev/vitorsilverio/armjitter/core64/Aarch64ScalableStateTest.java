package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
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

/// B17.3 — fundação de estado SVE: banco `Z`/`P`/`FFR` como armazenamento único de `V`, `VL`
/// configurável, `ZCR_ELx`, `ID_AA64PFR0_EL1`/`ID_AA64ZFR0_EL1` por preset, `sve_access_check` e
/// persistência. Testado em dois `VL` (256 e 512), como manda a RFC B17.2.
class Aarch64ScalableStateTest {
    private static final long ID_AA64PFR0_SVE_FIELD = 0xFL << 32;
    private static final long ZCR_LEN_MASK = 0xFL;

    // mrs/msr xN, zcr_el{1,2,3} — op1=0/4/6, CRn=1, CRm=2, op2=0
    private static final int MRS_ZCR_EL1_X0 = 0xd5381200;
    private static final int MSR_ZCR_EL1_X0 = 0xd5181200;
    private static final int MRS_ZCR_EL2_X0 = 0xd53c1200;
    private static final int MRS_ZCR_EL3_X0 = 0xd53e1200;

    private static Aarch64Core sveCore(int vectorLengthBits) {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV9_0_A, vectorLengthBits);
    }

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Aliasing Z[127:0] ≡ V ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void vAndZLowHalfAreTheSameStorageForAll32Registers(int vl) {
        Aarch64Core core = sveCore(vl);
        Aarch64ScalableRegisters z = core.scalable();
        for (int n = 0; n < Aarch64ScalableRegisters.Z_REGISTER_COUNT; n++) {
            core.fp().setQ(n, 0x1111L * (n + 1), 0x2222L * (n + 1));
            assertEquals(0x1111L * (n + 1), z.zWord(n, 0), "Z" + n + "[63:0]");
            assertEquals(0x2222L * (n + 1), z.zWord(n, 1), "Z" + n + "[127:64]");
        }
        for (int n = 0; n < Aarch64ScalableRegisters.Z_REGISTER_COUNT; n++) {
            z.setZWord(n, 0, 0xA0L + n);
            z.setZWord(n, 1, 0xB0L + n);
            assertEquals(0xA0L + n, core.fp().low64(n));
            assertEquals(0xB0L + n, core.fp().high64(n));
            assertEquals(0xA0L + n, core.fp().word(n * Aarch64FpRegisters.WORDS_PER_REGISTER));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void everyAdvSimdWriteZeroesZAbove128(int vl) {
        Aarch64Core core = sveCore(vl);
        Aarch64ScalableRegisters z = core.scalable();
        int words = z.wordsPerVector();
        for (int variant = 0; variant < 7; variant++) {
            for (int w = 0; w < words; w++) {
                z.setZWord(3, w, -1L);
            }
            switch (variant) {
                case 0 -> core.fp().setS(3, 1);
                case 1 -> core.fp().setD(3, 1L);
                case 2 -> core.fp().setQ(3, 1L, 2L);
                case 3 -> core.fp().setScalar(3, 1, 5L);
                case 4 -> core.fp().setElement(3, 0, 0, 7L);
                case 5 -> core.fp().setWord(6, 9L); // palavra 6 = bits 63:0 de V3
                default -> core.fp().replicateElement(3, 1L, 2, true);
            }
            for (int w = 2; w < words; w++) {
                assertEquals(0L, z.zWord(3, w), "variante " + variant + ", palavra " + w);
            }
        }
    }

    @Test
    void preexistingLaneWriteStillPreservesOtherLowBits() {
        Aarch64Core core = sveCore(256);
        core.fp().setQ(1, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        core.fp().setElement(1, 1, 0, 0xEEL);
        assertEquals(0x112233445566EE88L, core.fp().low64(1));
        assertEquals(0x99AABBCCDDEEFF00L, core.fp().high64(1));
    }

    // ── VL, predicados, FFR ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void vectorAndPredicateLengthsFollowVl(int vl) {
        Aarch64Core core = sveCore(vl);
        assertEquals(vl, core.vectorLengthBits());
        assertEquals(vl / 8, core.vectorLengthBytes());
        assertEquals(vl / 64, core.predicateLengthBytes());
        assertEquals(vl / 64, core.scalable().predicateLengthBytes());
    }

    @Test
    void ffrBehavesAsTheSeventeenthPredicate() {
        Aarch64ScalableRegisters bank = sveCore(512).scalable();
        assertEquals(16, Aarch64ScalableRegisters.FFR_INDEX);
        bank.setFfrWord(0, 0xF0F0L);
        assertEquals(0xF0F0L, bank.ffrWord(0));
        assertEquals(0xF0F0L, bank.pWord(Aarch64ScalableRegisters.FFR_INDEX, 0));
        for (int p = 0; p < Aarch64ScalableRegisters.P_REGISTER_COUNT; p++) {
            assertEquals(0L, bank.pWord(p, 0), "P" + p + " não pode aliasar o FFR");
            bank.setPWord(p, 0, p + 1L);
        }
        assertEquals(0xF0F0L, bank.ffrWord(0));
        bank.reset();
        assertEquals(0L, bank.ffrWord(0));
    }

    @Test
    void invalidVectorLengthsAreRejected() {
        for (int bad : new int[] {0, 64, 127, 129, 200, 2176, -128}) {
            assertThrows(IllegalArgumentException.class, () -> sveCore(bad), "VL=" + bad);
        }
        sveCore(128);
        sveCore(2048);
    }

    @Test
    void presetWithoutSveDoesNotAllocatePredicatesAndKeeps128Bits() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV8_0_A, 512);
        assertFalse(core.hasSve());
        assertFalse(core.scalable().hasPredicates());
        assertEquals(128, core.implementedVectorLengthBits());
        assertEquals(0, core.scalableSnapshot().length);
        core.fp().setQ(0, 1L, 2L);
        assertEquals(2L, core.fp().high64(0));
    }

    // ── ZCR_ELx ─────────────────────────────────────────────────────────────────────────────

    @Test
    void zcrLenLimitsEffectiveVlAndNeverExceedsImplemented() {
        Aarch64Core core = sveCore(512);
        assertEquals(512, core.vectorLengthBits(), "reset: LEN máximo");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 0);
        assertEquals(128, core.vectorLengthBits(), "LEN=0 → mínimo arquitetural");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1);
        assertEquals(256, core.vectorLengthBits());
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 15);
        assertEquals(512, core.vectorLengthBits(), "LEN acima do implementado é limitado");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, ~0L);
        assertEquals(ZCR_LEN_MASK, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1),
                "só LEN é guardado, o resto é RES0");
    }

    @Test
    void zcrOfHigherLevelsCapsTheLowerOnes() {
        Aarch64Core core = sveCore(512);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL3, 1);
        assertEquals(256, core.vectorLengthBits(), "EL0 sofre ZCR_EL3");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL2, 0);
        assertEquals(128, core.vectorLengthBits(), "EL0 sofre ZCR_EL2");
        assertEquals(1L, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL3));
        assertEquals(0L, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL2));
    }

    @Test
    void shrinkingZcrZeroesStateAboveTheNewVl() {
        Aarch64Core core = sveCore(512);
        Aarch64ScalableRegisters bank = core.scalable();
        for (int w = 0; w < bank.wordsPerVector(); w++) {
            bank.setZWord(5, w, -1L);
        }
        for (int w = 0; w < bank.wordsPerPredicate(); w++) {
            bank.setPWord(2, w, -1L);
            bank.setFfrWord(w, -1L);
        }
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // 256 bits
        assertEquals(-1L, bank.zWord(5, 3));
        assertEquals(0L, bank.zWord(5, 4));
        assertEquals(0L, bank.zWord(5, 7));
        long predicateMask = (1L << (256 / 8)) - 1; // 32 bits de predicado no VL=256
        assertEquals(predicateMask, bank.pWord(2, 0));
        assertEquals(predicateMask, bank.ffrWord(0));
    }

    @Test
    void zcrRegistersDecodeOnlyWithSve() {
        Aarch64Decoder sve = new Aarch64Decoder(Aarch64Architecture.ARMV9_0_A);
        Aarch64Decoder plain = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        Ir64Op.SystemRegister mrs = (Ir64Op.SystemRegister) decodeWord(sve, MRS_ZCR_EL1_X0);
        assertTrue(mrs.read());
        assertEquals(Aarch64SystemRegisterId.ZCR_EL1, mrs.register());
        Ir64Op.SystemRegister msr = (Ir64Op.SystemRegister) decodeWord(sve, MSR_ZCR_EL1_X0);
        assertFalse(msr.read());
        assertEquals(Aarch64SystemRegisterId.ZCR_EL1, msr.register());
        assertEquals(Aarch64SystemRegisterId.ZCR_EL2,
                ((Ir64Op.SystemRegister) decodeWord(sve, MRS_ZCR_EL2_X0)).register());
        assertEquals(Aarch64SystemRegisterId.ZCR_EL3,
                ((Ir64Op.SystemRegister) decodeWord(sve, MRS_ZCR_EL3_X0)).register());
        for (int word : new int[] {MRS_ZCR_EL1_X0, MSR_ZCR_EL1_X0, MRS_ZCR_EL2_X0, MRS_ZCR_EL3_X0}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(plain, word));
        }
    }

    // ── ID registers ────────────────────────────────────────────────────────────────────────

    @Test
    void idRegistersAdvertiseSveOnlyWhenThePresetDeclaresIt() {
        Aarch64Core sve = sveCore(256);
        Aarch64Core plain = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV8_0_A);
        long sveField = sve.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR0_EL1)
                & ID_AA64PFR0_SVE_FIELD;
        assertEquals(1L << 32, sveField);
        assertEquals(0L, plain.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR0_EL1)
                & ID_AA64PFR0_SVE_FIELD);
        assertEquals(0x11L, plain.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64PFR0_EL1),
                "sem SVE o valor de sempre");
        assertEquals(0L, sve.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1),
                "SVEver=0 (só SVE); nenhum campo de extensão ainda");
        assertEquals(0L, plain.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1));
    }

    // ── sve_access_check ────────────────────────────────────────────────────────────────────

    /// Barramento mínimo com `CPACR_EL1`/`CPTR_EL2`/`CPTR_EL3` controláveis.
    private static final class TrapRegisters implements Aarch64SystemRegisterBus {
        long cpacr;
        long cptrEl2;
        long cptrEl3 = 1L << 8;

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

    @Test
    void accessIsAllowedWhenNoTrapStateIsModeled() {
        assertEquals(Optional.empty(), sveCore(256).sveAccessTrapLevel());
        assertTrue(sveCore(256).sveAccessCheck(0x1000));
    }

    @Test
    void cpacrZenDeniesAccessAndEntersTheSveTrap() {
        Aarch64Core core = sveCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        regs.cpacr = 0b00L << 16;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.sveAccessTrapLevel());
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x8000);
        assertFalse(core.sveAccessCheck(0x1234));
        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl());
        assertEquals(0x19L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=0x19");
        assertEquals(0x1234L, core.exceptionState().elr(Aarch64ExceptionLevel.EL1));
    }

    @Test
    void cpacrZenValuesBehaveAsSpecifiedPerLevel() {
        Aarch64Core core = sveCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        // EL0
        for (long zen : new long[] {0b00, 0b01, 0b10}) {
            regs.cpacr = zen << 16;
            assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.sveAccessTrapLevel(), "EL0 ZEN=" + zen);
        }
        regs.cpacr = 0b11L << 16;
        assertEquals(Optional.empty(), core.sveAccessTrapLevel());
        // EL1
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        regs.cpacr = 0b01L << 16;
        assertEquals(Optional.empty(), core.sveAccessTrapLevel(), "ZEN=01 só trapa EL0");
        regs.cpacr = 0b10L << 16;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.sveAccessTrapLevel());
        regs.cpacr = 0b00L << 16;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL1), core.sveAccessTrapLevel());
    }

    @Test
    void cptrEl2TzAndCptrEl3EzTrapToTheirLevels() {
        Aarch64Core core = sveCore(256);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        regs.cpacr = 0b11L << 16;
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        regs.cptrEl2 = 1L << 8;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), core.sveAccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL2), core.sveAccessTrapLevel());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertEquals(Optional.empty(), core.sveAccessTrapLevel(), "EL3 não sofre CPTR_EL2");
        regs.cptrEl2 = 0;
        regs.cptrEl3 = 0;
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), core.sveAccessTrapLevel(), "EZ=0 trapa para EL3");
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL0);
        assertEquals(Optional.of(Aarch64ExceptionLevel.EL3), core.sveAccessTrapLevel());
    }

    @Test
    void neverTrapsWithoutSve() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)),
                Aarch64Architecture.ARMV8_0_A);
        TrapRegisters regs = new TrapRegisters();
        core.setSystemRegisterBus(regs);
        assertEquals(Optional.empty(), core.sveAccessTrapLevel());
    }

    // ── Persistência ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void saveAndRestorePreservesZPFfrAndZcrBitForBit(int vl) throws IOException {
        Aarch64Core original = sveCore(vl);
        Aarch64ScalableRegisters bank = original.scalable();
        long seed = 0x9E3779B97F4A7C15L;
        for (int n = 0; n < Aarch64ScalableRegisters.Z_REGISTER_COUNT; n++) {
            for (int w = 0; w < bank.wordsPerVector(); w++) {
                bank.setZWord(n, w, seed * (n * 31L + w + 1));
            }
        }
        long predicateMask = (1L << (vl / 8)) - 1;
        for (int p = 0; p <= Aarch64ScalableRegisters.FFR_INDEX; p++) {
            bank.setPWord(p, 0, (seed * (p + 1L)) & predicateMask);
        }
        original.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL2, 1);
        original.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL3, 3);
        long[] before = original.scalableSnapshot();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.saveScalableState(new DataOutputStream(buffer));
        Aarch64Core restored = sveCore(vl);
        restored.loadScalableState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertArrayEquals(before, restored.scalableSnapshot());
        assertEquals(1L, restored.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL2));
        assertEquals(3L, restored.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL3));
    }

    @Test
    void restoringIntoADifferentVlIsRejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        sveCore(256).saveScalableState(new DataOutputStream(buffer));
        Aarch64Core other = sveCore(512);
        assertThrows(IOException.class,
                () -> other.loadScalableState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))));
    }

    @Test
    void legacyVBankFormatIsUnchanged() throws IOException {
        Aarch64Core core = sveCore(512);
        core.fp().setQ(2, 0x11L, 0x22L);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        core.fp().saveState(new DataOutputStream(buffer));
        assertEquals(Aarch64FpRegisters.V_REGISTER_COUNT * Aarch64FpRegisters.QUADWORD_BYTES, buffer.size());
    }

    // ── Lacunas apontadas pelo JaCoCo ───────────────────────────────────────────────────────

    @Test
    void narrowingAWithoutPredicatesBankOnlyTouchesZ() {
        Aarch64ScalableRegisters bank = new Aarch64ScalableRegisters(512, false);
        bank.setZWord(0, 7, -1L);
        bank.narrowTo(128);
        assertEquals(0L, bank.zWord(0, 7));
        assertEquals(0, bank.snapshot().length - 32 * bank.wordsPerVector(), "sem predicados");
        bank.narrowTo(1024); // não estreita: no-op
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 2048})
    void narrowingMultiWordPredicatesMasksThePartialWordAndZeroesTheRest(int vl) {
        Aarch64ScalableRegisters bank = new Aarch64ScalableRegisters(vl, true);
        assertEquals(vl / 512, bank.wordsPerPredicate());
        for (int w = 0; w < bank.wordsPerPredicate(); w++) {
            bank.setPWord(1, w, -1L);
        }
        bank.narrowTo(640); // 80 bits de predicado: palavra 0 inteira, palavra 1 parcial (16 bits)
        assertEquals(-1L, bank.pWord(1, 0));
        assertEquals(0xFFFFL, bank.pWord(1, 1));
        for (int w = 2; w < bank.wordsPerPredicate(); w++) {
            assertEquals(0L, bank.pWord(1, w));
        }
    }

    @Test
    void loadStateRejectsUnknownVersionAndPredicatePresenceMismatch() throws IOException {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        new DataOutputStream(bad).writeInt(99);
        Aarch64ScalableRegisters bank = new Aarch64ScalableRegisters(256, true);
        assertThrows(IOException.class,
                () -> bank.loadState(new DataInputStream(new ByteArrayInputStream(bad.toByteArray()))));

        ByteArrayOutputStream noPredicates = new ByteArrayOutputStream();
        new Aarch64ScalableRegisters(256, false).saveState(new DataOutputStream(noPredicates));
        assertThrows(IOException.class,
                () -> bank.loadState(new DataInputStream(new ByteArrayInputStream(noPredicates.toByteArray()))));
    }

    @Test
    void coreLoadScalableStateRejectsUnknownVersion() throws IOException {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        new DataOutputStream(bad).writeInt(99);
        assertThrows(IOException.class, () -> sveCore(256)
                .loadScalableState(new DataInputStream(new ByteArrayInputStream(bad.toByteArray()))));
    }

    @Test
    void effectiveVlAtEl2AndEl3IgnoresLowerLevelZcr() {
        Aarch64Core core = sveCore(512);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 0);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL2, 1);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL3, 2);
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        assertEquals(128, core.vectorLengthBits());
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertEquals(256, core.vectorLengthBits(), "EL2: min(ZCR_EL2, ZCR_EL3), sem ZCR_EL1");
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertEquals(384, core.vectorLengthBits(), "EL3: só ZCR_EL3 (VL não é potência de 2)");
    }

    @Test
    void elTransitionsNarrowTheStateToTheNewLevel() {
        Aarch64Core core = sveCore(512);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 0);
        core.scalable().setZWord(4, 6, -1L);
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.narrowScalableStateToCurrentVectorLength();
        assertEquals(0L, core.scalable().zWord(4, 6));
        Aarch64Core noSve = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)));
        noSve.narrowScalableStateToCurrentVectorLength(); // no-op sem SVE
    }

    @Test
    void zfr0AdvertisesSve2VersionWhenTheArchitectureDeclaresSve2() {
        Aarch64Architecture sve2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_0_A, "teste-SVE2",
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.SVE2);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)), sve2, 256);
        assertEquals(1L, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1));
    }

    // ── Aarch64CpuSnapshot (harness de equivalência) ────────────────────────────────────────

    @Test
    void snapshotCapturesScalableStateAndDetectsDivergenceInIt() {
        Aarch64Core a = sveCore(256);
        Aarch64Core b = sveCore(256);
        a.scalable().setZWord(9, 3, 0xABCL);
        var snapA = dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot.capture(a);
        var snapB = dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot.capture(b);
        assertThrows(dev.vitorsilverio.armjitter.codegen.equivalence.EquivalenceMismatchException.class,
                () -> snapA.assertEqualTo(snapB, "z-alto"));
        b.scalable().setZWord(9, 3, 0xABCL);
        snapA.assertEqualTo(dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot.capture(b), "igual");
    }

    @Test
    void legacyEightArgumentSnapshotConstructorStillWorks() {
        var legacy = new dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot(
                new long[31], 0, 0, 0, 0, 0, 0, new long[64]);
        assertEquals(0, legacy.scalableState().length);
    }
}
