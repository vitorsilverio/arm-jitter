package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.4 — `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (load alargante/store estreitante), fim-a-fim (decode +
/// lift + executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Mesmo padrão de
/// {@code MveLoadStoreTest} (B16.3).
///
/// **Achado que diverge da Aceite original da task, medido contra o QEMU real
/// (`target/arm/tcg/mve_helper.c`, `DO_VLDR`, via `WebFetch`)**: diferente de
/// {@link IrOp.MveLoadStore} (B16.3, que PRESERVA lanes mascaradas), um load alargante grava ZERO
/// nas lanes cujo predicado `VPT` falha (comentário real: "predicated lanes are zeroed instead of
/// keeping their old values") — só lanes de um beat inteiramente ABANDONADO (`ECI`) preservam o
/// valor antigo (`UNKNOWN` permitido pelo hardware). O store, ao contrário, só escreve as lanes
/// ativas (memória mascarada fica intocada, mesmo padrão de {@link IrOp.MveLoadStore}).
class MveWideningLoadStoreTest {
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int CODE_BASE = 0x100;
    private static final int DATA_BASE = 0x1000;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int CFSR_UFSR_INVSTATE_BIT = 1 << 17;

    private static final int SIZE_MARKER_HALFWORD = 0b01;
    private static final int SIZE_MARKER_WORD = 0b10;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        memory.put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1);
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

    private static int raw(boolean p, boolean a, boolean w, boolean l, boolean u, boolean memHalfword,
            int rn, int qd, int sizeMarker, int imm7) {
        return (0b111 << 29)
                | ((u ? 1 : 0) << 28)
                | (0b110 << 25)
                | ((p ? 1 : 0) << 24)
                | ((a ? 1 : 0) << 23)
                | ((w ? 1 : 0) << 21)
                | ((l ? 1 : 0) << 20)
                | ((memHalfword ? 1 : 0) << 19)
                | ((rn & 0x7) << 16)
                | ((qd & 0x7) << 13)
                | (0b111 << 9)
                | (sizeMarker << 7)
                | (imm7 & 0x7F);
    }

    // ── Extensão de sinal/zero (VLDSTB_H) ───────────────────────────────────────────────────────

    @Test
    void signedLoadExtendsNegativeByteToHalfword() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write8(DATA_BASE, 0x80);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, false, false, 1, 2, SIZE_MARKER_HALFWORD, 0); // U=0, sinal
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(0xFF80, core.vfp().element(2, 0, 1), "sinal estendido: 0x80 -> 0xFF80");
    }

    @Test
    void unsignedLoadZeroExtendsByteToHalfword() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write8(DATA_BASE, 0x80);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, true, false, 1, 2, SIZE_MARKER_HALFWORD, 0); // U=1, zero
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(0x0080, core.vfp().element(2, 0, 1), "zero estendido: 0x80 -> 0x0080");
    }

    @Test
    void loadsAllEightHalfwordsFromByteMemory() {
        ArmCore core = newCore();
        for (int i = 0; i < 8; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, true, false, 1, 3, SIZE_MARKER_HALFWORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 8; i++) {
            assertEquals(0x10 + i, core.vfp().element(3, i, 1), "halfword " + i);
        }
    }

    @Test
    void loadsAllFourWordsFromByteMemory() {
        ArmCore core = newCore();
        for (int i = 0; i < 4; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, true, false, 1, 4, SIZE_MARKER_WORD, 0); // VLDSTB_W
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(0x10 + i, core.vfp().element(4, i, 2), "word " + i);
        }
    }

    @Test
    void loadsAllFourWordsFromHalfwordMemoryWithScaledOffset() {
        ArmCore core = newCore();
        for (int i = 0; i < 4; i++) {
            ((TestAddressSpace) core.memory()).write16(DATA_BASE + i * 2, 0x1000 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, true, true, 1, 5, SIZE_MARKER_WORD, 0); // VLDSTH_W
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(0x1000 + i, core.vfp().element(5, i, 2), "word " + i);
        }
    }

    @Test
    void storeTruncatesHalfwordLanesToBytes() {
        ArmCore core = newCore();
        core.setRegister(1, DATA_BASE);
        for (int i = 0; i < 8; i++) {
            core.vfp().setElement(6, i, 1, 0x1000 + (i + 1));
        }
        int r = raw(true, true, false, false, false, false, 1, 6, SIZE_MARKER_HALFWORD, 0); // store, U=0
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 8; i++) {
            assertEquals((i + 1) & 0xFF, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF,
                    "byte " + i + " truncado do halfword");
        }
    }

    // ── Predicação por ELEMENTO: falha de VPT grava ZERO no load, pula escrita no store ────────────

    @Test
    void predicatedLoadZeroesLanesThatFailVpt() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0xAAAA_AAAA_AAAA_AAAAL, 0xAAAA_AAAA_AAAA_AAAAL);
        for (int i = 0; i < 8; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        core.vpr().setP0(0x00FF); // só os 8 bytes baixos ativos -> halfwords 0-3 ativas, 4-7 falham
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(true, true, false, true, true, false, 1, 2, SIZE_MARKER_HALFWORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 4; i++) {
            // MSIZE=1 (byte): cada elemento avança 1 byte no endereço, não `1 << registerSizeLog2`.
            assertEquals(0x10 + i, core.vfp().element(2, i, 1), "halfword " + i + " ativo, carregado");
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(0, core.vfp().element(2, i, 1), "halfword " + i + " falhou VPT -> ZERO (não preservado)");
        }
    }

    @Test
    void predicatedStoreSkipsLanesThatFailVpt() {
        ArmCore core = newCore();
        for (int i = 0; i < 8; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0xEE);
        }
        for (int i = 0; i < 8; i++) {
            core.vfp().setElement(5, i, 1, 0x1000 + (i + 1));
        }
        core.setRegister(1, DATA_BASE);
        core.vpr().setP0(0x00FF);
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(true, true, false, false, false, false, 1, 5, SIZE_MARKER_HALFWORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals((i + 1) & 0xFF, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF,
                    "byte " + i + " ativo, escrito");
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(0xEE, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i) & 0xFF,
                    "byte " + i + " mascarado, intocado");
        }
    }

    // ── Writeback incondicional mesmo totalmente predicado (G4) ────────────────────────────────────

    @Test
    void writebackHappensEvenWhenFullyMasked() {
        ArmCore core = newCore();
        core.setRegister(1, DATA_BASE);
        core.vpr().setP0(0);
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(true, true, true, true, true, false, 1, 0, SIZE_MARKER_HALFWORD, 4); // pré-index, offset=4
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(DATA_BASE + 4, core.register(1), "writeback roda mesmo totalmente mascarado");
    }

    @Test
    void postIndexedWritebackUsesOldBaseForAccess() {
        ArmCore core = newCore();
        for (int i = 0; i < 4; i++) {
            ((TestAddressSpace) core.memory()).write8(DATA_BASE + i, 0x10 + i);
        }
        core.setRegister(1, DATA_BASE);
        int r = raw(false, true, true, true, true, false, 1, 3, SIZE_MARKER_HALFWORD, 4); // p=0 (post), offset=4
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(0x10, core.vfp().element(3, 0, 1), "acesso usou o endereço ANTES do writeback");
        assertEquals(DATA_BASE + 4, core.register(1));
    }

    // ── Avanço do VPR/ECI depois da instrução (beatwise) ────────────────────────────────────────────

    @Test
    void advancesEciAfterExecution() {
        ArmCore core = newCore();
        core.cpsr().setEci(MveVptState.ECI_A0A1A2B0);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, false, true, true, false, 1, 0, SIZE_MARKER_HALFWORD, 0);
        put32(core, CODE_BASE, r >>> 16, r & 0xFFFF);

        core.step();

        assertEquals(MveVptState.ECI_A0, core.cpsr().eci());
    }

    // ── ECI reservado: USAGE_FAULT com UFSR.INVSTATE (via IrOp direto) ─────────────────────────────

    @Test
    void reservedEciEntersUsageFaultWithInvstateSet() {
        ArmCore core = newCore();
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(3);
        core.setRegister(1, DATA_BASE);
        int pcBefore = core.programCounter();

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.MveWideningLoadStore(0, 1, 0, 0, 1, true, true, false, false,
                        Condition.AL), pcBefore);

        assertTrue(pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_INVSTATE_BIT, model.cfsr() & CFSR_UFSR_INVSTATE_BIT);
    }

    // ── Condição falsa: nenhum acesso/writeback ────────────────────────────────────────────────────

    @Test
    void conditionalMveWideningLoadStoreSkippedWhenConditionFalse() {
        ArmCore core = newCore();
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG);
        core.setRegister(1, DATA_BASE);

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.MveWideningLoadStore(0, 1, 4, 0, 1, true, true, true, false,
                        Condition.EQ), core.programCounter());

        assertTrue(!pcChanged);
        assertEquals(DATA_BASE, core.register(1), "condição falsa: nem acesso nem writeback");
    }
}
