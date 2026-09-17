package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.3 — `VLDR_VSTR` contíguo não-alargante, fim-a-fim (decode + lift + executor interpretado)
/// sobre o preset real `ARMV8_1M_MVE`. Mesmo padrão de {@code MvePredicationTest} (B16.2).
class MveLoadStoreTest {
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int CODE_BASE = 0x100;
    private static final int DATA_BASE = 0x1000;
    private static final int MEMORY_SIZE = 0x8000;
    /// Bit `INVSTATE` do `UFSR` — `bit[1]` do `UFSR`, byte alto do `CFSR` (mesma constante de
    /// `MvePredicationTest`).
    private static final int CFSR_UFSR_INVSTATE_BIT = 1 << 17;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        memory.put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1); // vetor Thumb
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int hi, int lo) {
        ((TestAddressSpace) core.memory()).put16(address, hi);
        ((TestAddressSpace) core.memory()).put16(address + 2, lo);
    }

    private static int raw(boolean p, boolean a, int qd, boolean w, boolean l, int rn, int sizeMarker, int imm7) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b1110110 << 25)
                | ((p ? 1 : 0) << 24)
                | ((a ? 1 : 0) << 23)
                | (qdHigh << 22)
                | ((w ? 1 : 0) << 21)
                | ((l ? 1 : 0) << 20)
                | (rn << 16)
                | (qdLow << 13)
                | (sizeMarker << 7)
                | (imm7 & 0x7F);
    }

    private static final int SIZE_MARKER_WORD = 0b111110;

    // ── Load/store completo, sem predicação (P0 default = 0, sem VPT ativo -> elementMask cheio) ──

    @Test
    void loadsAllSixteenBytesFromMemoryIntoQd() {
        ArmCore core = newCore();
        for (int i = 0; i < 16; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, 2, false, true, 1, SIZE_MARKER_WORD, 0); // offset addressing, Qd=2
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 16; i++) {
            assertEquals(0x10 + i, core.vfp().element(2, i, 0), "byte " + i);
        }
    }

    @Test
    void storesAllSixteenBytesFromQdIntoMemory() {
        ArmCore core = newCore();
        core.vfp().setQ(5, 0x0807_0605_0403_0201L, 0x100F_0E0D_0C0B_0A09L);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, 5, false, false, 1, SIZE_MARKER_WORD, 0); // store, Qd=5
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 16; i++) {
            assertEquals((i + 1) & 0xFF, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF, "byte " + i);
        }
    }

    // ── Predicação por BYTE (Armadilha 5): lane mascarada preserva o valor atual ──────────────────

    @Test
    void predicatedLoadLeavesMaskedLanesIntact() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0xAAAA_AAAA_AAAA_AAAAL, 0xAAAA_AAAA_AAAA_AAAAL);
        for (int i = 0; i < 16; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        // mask01/mask23 != 0 -> VPT ativo (senão elementMask ignora P0 e habilita tudo, ver
        // MveVptState#elementMask); só então P0=0x00FF restringe às lanes baixas.
        core.vpr().setP0(0x00FF);
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(true, true, 2, false, true, 1, SIZE_MARKER_WORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 8; i++) {
            assertEquals(0x10 + i, core.vfp().element(2, i, 0), "byte " + i + " ativo, carregado");
        }
        for (int i = 8; i < 16; i++) {
            assertEquals(0xAA, core.vfp().element(2, i, 0), "byte " + i + " mascarado, intocado");
        }
    }

    @Test
    void predicatedStoreLeavesMaskedBytesOfMemoryIntact() {
        ArmCore core = newCore();
        for (int i = 0; i < 16; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0xEE);
        }
        core.vfp().setQ(5, 0x0807_0605_0403_0201L, 0x100F_0E0D_0C0B_0A09L);
        core.setRegister(1, DATA_BASE);
        core.vpr().setP0(0x00FF); // só os 8 bytes baixos ativos
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(true, true, 5, false, false, 1, SIZE_MARKER_WORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 8; i++) {
            assertEquals((i + 1) & 0xFF, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF,
                    "byte " + i + " ativo, escrito");
        }
        for (int i = 8; i < 16; i++) {
            assertEquals(0xEE, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF,
                    "byte " + i + " mascarado, intocado");
        }
    }

    // ── Writeback incondicional mesmo totalmente predicado (Armadilha 6/G4) ───────────────────────

    @Test
    void writebackHappensEvenWhenFullyMasked() {
        ArmCore core = newCore();
        core.setRegister(1, DATA_BASE);
        core.vpr().setP0(0); // nenhuma lane ativa
        core.vpr().setMask01(2);
        core.vpr().setMask23(2); // VPT ativo -> elementMask totalmente vazio
        int r = raw(true, true, 0, true, true, 1, SIZE_MARKER_WORD, 4); // pré-index, writeback, offset=16
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(DATA_BASE + 16, core.register(1), "writeback roda mesmo totalmente mascarado");
    }

    @Test
    void postIndexedWritebackUsesOldBaseForAccess() {
        ArmCore core = newCore();
        for (int i = 0; i < 16; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(false, true, 3, true, true, 1, SIZE_MARKER_WORD, 4); // p=0 (post), offset=16
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(0x10, core.vfp().element(3, 0, 0), "acesso usou o endereço ANTES do writeback");
        assertEquals(DATA_BASE + 16, core.register(1));
    }

    // ── Avanço do VPR/ECI depois da instrução (beatwise, mesmo gancho de VPST/VPSEL) ──────────────

    @Test
    void advancesEciAfterExecution() {
        ArmCore core = newCore();
        core.cpsr().setEci(MveVptState.ECI_A0A1A2B0);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, 0, false, true, 1, SIZE_MARKER_WORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(MveVptState.ECI_A0, core.cpsr().eci());
    }

    // ── ECI reservado: USAGE_FAULT com UFSR.INVSTATE (via IrOp direto, mesmo padrão de
    // MvePredicationTest) ───────────────────────────────────────────────────────────────────────

    @Test
    void reservedEciEntersUsageFaultWithInvstateSet() {
        ArmCore core = newCore();
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(3); // valor reservado
        core.setRegister(1, DATA_BASE);
        int pcBefore = core.programCounter();

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.MveLoadStore(0, 1, 0, true, false, false, Condition.AL), pcBefore);

        assertTrue(pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_INVSTATE_BIT, model.cfsr() & CFSR_UFSR_INVSTATE_BIT);
    }

    // ── Condição falsa: nenhum acesso/writeback, mesmo padrão de MvePredicationTest ───────────────

    @Test
    void conditionalMveLoadStoreSkippedWhenConditionFalse() {
        ArmCore core = newCore();
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa
        core.setRegister(1, DATA_BASE);

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.MveLoadStore(0, 1, 4, true, true, false, Condition.EQ),
                        core.programCounter());

        assertTrue(!pcChanged);
        assertEquals(DATA_BASE, core.register(1), "condição falsa: nem acesso nem writeback");
    }
}
