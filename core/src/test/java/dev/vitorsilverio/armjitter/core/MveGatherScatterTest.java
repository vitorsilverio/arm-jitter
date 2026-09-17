package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.5 — `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (gather/scatter por vetor de offsets) e
/// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (base vetorial + imediato),
/// fim-a-fim (decode + lift + executor interpretado) sobre o preset real `ARMV8_1M_MVE`.
class MveGatherScatterTest {
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

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int offsetRaw(boolean u, int qd, int lMarker, int rn, int qm, int size, int msize, boolean os) {
        return (0b111 << 29)
                | ((u ? 1 : 0) << 28)
                | (0b1100 << 24)
                | (1 << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((lMarker & 0x3) << 20)
                | ((rn & 0xF) << 16)
                | ((qd & 0x7) << 13)
                | (0b111 << 9)
                | ((size & 0x3) << 7)
                | (((msize >>> 1) & 1) << 6)
                | (((qm >>> 3) & 1) << 5)
                | ((msize & 1) << 4)
                | ((qm & 0x7) << 1)
                | (os ? 1 : 0);
    }

    private static int immRaw(boolean load, boolean a, boolean w, int qd, int qm, int sizeMarker, int imm7) {
        return (0b111 << 29)
                | (1 << 28)
                | (0b1101 << 24)
                | ((a ? 1 : 0) << 23)
                | (((qd >>> 3) & 1) << 22)
                | ((w ? 1 : 0) << 21)
                | ((load ? 1 : 0) << 20)
                | ((qm & 0x7) << 17)
                | ((qd & 0x7) << 13)
                | (1 << 12)
                | ((sizeMarker & 0xF) << 8)
                | (((qm >>> 3) & 1) << 7)
                | (imm7 & 0x7F);
    }

    @Test
    void gatherLoadsFourWordsFromOutOfOrderOffsets() {
        ArmCore core = newCore();
        int[] values = {0x1111_1111, 0x2222_2222, 0x3333_3333, 0x4444_4444};
        int[] offsets = {12, 0, 8, 4}; // fora de ordem: prova que não é acesso contíguo disfarçado
        for (int i = 0; i < 4; i++) {
            ((TestAddressSpace) core.memory()).write32(DATA_BASE + offsets[i], values[i]);
            core.vfp().setElement(3, i, 2, offsets[i]); // Qm = vetor de offsets, size=word
        }
        core.setRegister(1, DATA_BASE);
        int r = offsetRaw(true, 2, 0b01, 1, 3, 2, 2, false); // VLDR_U_sg, msize=word,size=word
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(values[i], (int) core.vfp().element(2, i, 2), "lane " + i);
        }
    }

    @Test
    void signedGatherExtendsNegativeByteToWord() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write8(DATA_BASE, 0x80);
        core.vfp().setElement(4, 0, 2, 0); // offset da lane 0
        core.setRegister(1, DATA_BASE);
        int r = offsetRaw(false, 2, 0b01, 1, 4, 2, 0, false); // VLDR_S_sg, msize=byte,size=word
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFFFF_FF80, (int) core.vfp().element(2, 0, 2));
    }

    @Test
    void offsetScaledMultipliesByMemoryElementSize() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write32(DATA_BASE + 8, 0xABCD_EF01);
        core.vfp().setElement(4, 0, 2, 2); // offset lógico=2, escalado por <<2 (word) = 8
        core.setRegister(1, DATA_BASE);
        int r = offsetRaw(true, 2, 0b01, 1, 4, 2, 2, true); // os=1
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xABCD_EF01, (int) core.vfp().element(2, 0, 2));
    }

    @Test
    void scatterStoreWritesToOutOfOrderOffsets() {
        ArmCore core = newCore();
        int[] offsets = {12, 0, 8, 4};
        for (int i = 0; i < 4; i++) {
            core.vfp().setElement(2, i, 2, 0x1000 + i);
            core.vfp().setElement(4, i, 2, offsets[i]);
        }
        core.setRegister(1, DATA_BASE);
        int r = offsetRaw(false, 2, 0b00, 1, 4, 2, 2, false); // VSTR_sg (U=0 sempre)
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(0x1000 + i, ((TestAddressSpace) core.memory()).read32(DATA_BASE + offsets[i]), "offset " + offsets[i]);
        }
    }

    // ── Base vetorial + imediato (VLDRW_sg_imm/VLDRD_sg_imm/VSTRW_sg_imm/VSTRD_sg_imm) ─────────

    @Test
    void immediateFormBaseIsTheVectorAndOffsetIsScalar() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write32(DATA_BASE + 4, 0x9999_0001);
        ((TestAddressSpace) core.memory()).write32(DATA_BASE + 20, 0x9999_0002);
        core.vfp().setElement(5, 0, 2, DATA_BASE); // Qm lane0 = endereço base
        core.vfp().setElement(5, 1, 2, DATA_BASE + 16);
        int r = immRaw(true, true, false, 2, 5, 0b1110, 1); // sizeLog2=2(W), imm7=1<<2=4
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x9999_0001, (int) core.vfp().element(2, 0, 2));
        assertEquals(0x9999_0002, (int) core.vfp().element(2, 1, 2));
    }

    @Test
    void immediateFormWritesBackPerLaneAddress() {
        ArmCore core = newCore();
        core.vfp().setElement(5, 0, 2, DATA_BASE);
        core.vfp().setElement(5, 1, 2, DATA_BASE + 16);
        for (int i = 0; i < 4; i++) {
            core.vfp().setElement(2, i, 2, 0x1000 + i);
        }
        int r = immRaw(false, true, true, 2, 5, 0b1110, 1); // store, writeback, offset=4
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(DATA_BASE + 4, (int) core.vfp().element(5, 0, 2));
        assertEquals(DATA_BASE + 16 + 4, (int) core.vfp().element(5, 1, 2));
    }

    @Test
    void immediateDoublewordFormMovesEightBytesPerLane() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).write32(DATA_BASE, 0x1111_1111);
        ((TestAddressSpace) core.memory()).write32(DATA_BASE + 4, 0x2222_2222);
        core.vfp().setElement(5, 0, 2, DATA_BASE);
        int r = immRaw(true, true, false, 2, 5, 0b1111, 0); // sizeLog2=3(D)
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x2222_2222_1111_1111L, core.vfp().element(2, 0, 3));
    }
}
