package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.5 — `VLD2`/`VLD4`/`VST2`/`VST4` (desentrelaçamento/entrelaçamento), fim-a-fim sobre o preset
/// real `ARMV8_1M_MVE`. As 4 instruções `pat=0..3` de um `VLD4.8`/`VLD2.8` real são executadas em
/// SEQUÊNCIA (uma por teste de round-trip byte-a-byte), mesmo padrão da arquitetura real (um
/// assembler expande `VLD4.8 {Q0-Q3}, [r0]` em 4 instruções consecutivas com `pat` `0..3`).
class MveInterleavedLoadStoreTest {
    private static final int CODE_BASE = 0x100;
    private static final int DATA_BASE = 0x2000;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32At(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int raw(boolean load, boolean w, int rn, int qd, int size, int pat, int groupMarker) {
        return (0b1111_1100 << 24)
                | (1 << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((w ? 1 : 0) << 21)
                | ((load ? 1 : 0) << 20)
                | ((rn & 0xF) << 16)
                | ((qd & 0x7) << 13)
                | (1 << 12)
                | (0b111 << 9)
                | ((size & 0x3) << 7)
                | ((pat & 0x3) << 5)
                | (groupMarker & 0x1F);
    }

    private static void runFourPats(ArmCore core, boolean load, int rn, int qd, int size, int groupSize) {
        int groupMarker = groupSize == 4 ? 0b00001 : 0b00000;
        int pats = groupSize == 4 ? 4 : 2;
        for (int pat = 0; pat < pats; pat++) {
            core.setProgramCounter(CODE_BASE);
            core.setRegister(rn, DATA_BASE);
            int r = raw(load, false, rn, qd, size, pat, groupMarker);
            put32At(core, CODE_BASE, r);
            core.step();
        }
    }

    @Test
    void vld4ByteDeinterleavesFourStructuresRoundTrip() {
        ArmCore core = newCore();
        // Memória: 16 estruturas de 4 bytes (64 bytes), estrutura i = {i*4, i*4+1, i*4+2, i*4+3}.
        for (int i = 0; i < 16; i++) {
            for (int lane = 0; lane < 4; lane++) {
                ((TestAddressSpace) core.memory()).write8(DATA_BASE + i * 4 + lane, i * 4 + lane);
            }
        }
        runFourPats(core, true, 1, 0, 0, 4);

        for (int i = 0; i < 16; i++) {
            for (int lane = 0; lane < 4; lane++) {
                assertEquals((i * 4 + lane) & 0xFF, (int) core.vfp().element(lane, i, 0),
                        "estrutura " + i + " lane " + lane);
            }
        }
    }

    @Test
    void vst4ByteInterleavesRoundTrip() {
        ArmCore core = newCore();
        for (int i = 0; i < 16; i++) {
            for (int lane = 0; lane < 4; lane++) {
                core.vfp().setElement(lane, i, 0, i * 4 + lane);
            }
        }
        runFourPats(core, false, 1, 0, 0, 4);

        for (int i = 0; i < 16; i++) {
            for (int lane = 0; lane < 4; lane++) {
                assertEquals((i * 4 + lane) & 0xFF, ((TestAddressSpace) core.memory()).read8(DATA_BASE + i * 4 + lane) & 0xFF,
                        "estrutura " + i + " lane " + lane);
            }
        }
    }

    @Test
    void vld2HalfwordDeinterleavesRoundTrip() {
        ArmCore core = newCore();
        for (int i = 0; i < 8; i++) {
            for (int lane = 0; lane < 2; lane++) {
                ((TestAddressSpace) core.memory()).write16(DATA_BASE + (i * 2 + lane) * 2, 0x1000 + i * 2 + lane);
            }
        }
        runFourPats(core, true, 1, 0, 1, 2);

        for (int i = 0; i < 8; i++) {
            for (int lane = 0; lane < 2; lane++) {
                assertEquals(0x1000 + i * 2 + lane, (int) core.vfp().element(lane, i, 1),
                        "estrutura " + i + " lane " + lane);
            }
        }
    }

    @Test
    void vld2WordDeinterleavesRoundTrip() {
        ArmCore core = newCore();
        for (int i = 0; i < 4; i++) {
            for (int lane = 0; lane < 2; lane++) {
                ((TestAddressSpace) core.memory()).write32(DATA_BASE + (i * 2 + lane) * 4, 0x2000_0000 + i * 2 + lane);
            }
        }
        runFourPats(core, true, 1, 0, 2, 2);

        for (int i = 0; i < 4; i++) {
            for (int lane = 0; lane < 2; lane++) {
                assertEquals(0x2000_0000 + i * 2 + lane, (int) core.vfp().element(lane, i, 2),
                        "estrutura " + i + " lane " + lane);
            }
        }
    }

    @Test
    void writebackAdvancesRnByGroupSizeTimesSixteen() {
        ArmCore core = newCore();
        core.setRegister(1, DATA_BASE);
        int r = raw(true, true, 1, 0, 0, 0, 0b00001); // VLD4, writeback
        put32At(core, CODE_BASE, r);

        core.step();

        assertEquals(DATA_BASE + 4 * 16, core.register(1));
    }

    @Test
    void advancesEciOnlyWithoutTouchingVprMask() {
        ArmCore core = newCore();
        core.cpsr().setEci(MveVptState.ECI_A0A1A2B0);
        core.vpr().setMask01(7); // simula um VPST ativo — não deve ser tocado por VLD2/VLD4
        core.vpr().setMask23(7);
        core.setRegister(1, DATA_BASE);
        int r = raw(true, false, 1, 0, 0, 0, 0b00001);
        put32At(core, CODE_BASE, r);

        core.step();

        assertEquals(MveVptState.ECI_A0, core.cpsr().eci());
        assertEquals(7, (core.vpr().value() & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(7, (core.vpr().value() & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }
}
