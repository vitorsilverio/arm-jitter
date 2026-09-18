package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.10 — deslocamentos por imediato, shift-and-insert e `VSHLL` T1, fim-a-fim (decode + lift +
/// executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Cobre os itens do Aceite que exigem
/// execução: `VSRI`/`VSLI` preservam os bits do destino fora da inserção, `VMOVL` alarga sem
/// deslocar (mesmo `Kind` de `VSHLL`), `FPSCR.QC` só em lane ATIVA nas 3 formas saturantes. Mesmo
/// padrão de `MveVector2opExecutionTest`.
class MveVectorShiftImmediateExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int PREFIX_BYTE = 0b001;
    private static final int PREFIX_HALFWORD = 0b01;

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

    private static int prefixByte(int shift3) {
        return (PREFIX_BYTE << 3) | (shift3 & 0x7);
    }

    private static int prefixHalfword(int shift4) {
        return (PREFIX_HALFWORD << 4) | (shift4 & 0xF);
    }

    /// MESMO layout de `Thumb2MveShiftImmediateDecoderTest#rawShift`.
    private static int rawShift(int u, int nibble, int prefix6, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1111 << 24) | (1 << 23) | (qdHigh << 22) | (prefix6 << 16)
                | (qdLow << 13) | (nibble << 8) | (1 << 6) | (qmHigh << 5) | (1 << 4) | (qmLow << 1);
    }

    /// MESMO layout de `Thumb2MveShiftImmediateDecoderTest#rawVshll`.
    private static int rawVshll(int u, int top, int esz, int shift, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        int widthAndShift = esz == 0 ? (0b01 << 19) | ((shift & 0x7) << 16) : (1 << 20) | ((shift & 0xF) << 16);
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (1 << 21) | widthAndShift
                | (qdLow << 13) | (top << 12) | (0b1111 << 8) | (1 << 6) | (qmHigh << 5) | (qmLow << 1);
    }

    // ── VSRI/VSLI preservam os bits do destino fora da inserção ─────────────────────────────────

    @Test
    void vsriPreservesTheHighBitsOfTheDestination() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0xFFFF_FFFFL, 0L); // Qm (fonte): todos os bits em 1.
        core.vfp().setQ(0, 0x0000_0000L, 0L); // Qd (destino) inicial: todos os bits em 0.
        // VSRI, byte, shift=3 -> raw = N-shift = 8-3=5 no campo cru.
        int r = rawShift(1, 0b0100, prefixByte(5), 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        // Insere os 5 bits altos de 0xFF>>>3 = 0x1F nos bits baixos; os 3 bits ALTOS do destino
        // (que a inserção não cobre) continuam preservados em 0 (valor inicial do destino).
        long element0 = core.vfp().element(0, 0, 0);
        assertEquals(0x1FL, element0, "shift-right-insert: bits baixos vêm da fonte deslocada");
    }

    @Test
    void vsliPreservesTheLowBitsOfTheDestination() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x0000_00FFL, 0L); // Qm (fonte): byte 0 = 0xFF.
        core.vfp().setQ(0, 0x0000_00FFL, 0L); // Qd (destino) inicial: byte 0 = 0xFF (bits baixos).
        // VSLI, byte, shift=3 (direto, não N-shift).
        int r = rawShift(1, 0b0101, prefixByte(3), 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        // 0xFF << 3 = 0x7F8, truncado a byte = 0xF8; os 3 bits BAIXOS preservados do destino (0xFF
        // & 0b111 = 0x07) permanecem.
        long element0 = core.vfp().element(0, 0, 0);
        assertEquals(0xF8L | 0x07L, element0);
    }

    // ── VMOVL = VSHLL com shift=0, sem Kind próprio ─────────────────────────────────────────────

    @Test
    void vmovlSignExtendsBottomLanesWithoutShifting() {
        ArmCore core = newCore();
        // Qm byte lanes: le=0 -> 0x80 (assinado = -128), le=1 -> 0x01 (bytes ímpares, não usados no
        // bottom).
        core.vfp().setQ(1, 0x00_01_00_80L, 0L);
        // VSHLL_BS (signed, bottom), byte, shift=0 -> VMOVL.
        int r = rawVshll(0, 0, 0, 0, 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFF80, core.vfp().element(0, 0, 1) & 0xFFFF, "sinal-estendido, sem deslocamento");
    }

    @Test
    void vshllBottomUnsignedZeroExtendsWithShift() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x0000_0080L, 0L); // le=0 (bottom) = 0x80.
        // VSHLL_BU (unsigned, bottom), byte, shift=1.
        int r = rawVshll(1, 0, 0, 1, 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x100L, core.vfp().element(0, 0, 1), "0x80 zero-estendido e deslocado 1 bit");
    }

    // ── FPSCR.QC só em lane ATIVA (formas saturantes: SQSHL/UQSHL/SQSHLU) ───────────────────────

    @Test
    void saturatingShiftLeftSetsQcOnlyWhenTheSaturatingLaneIsActive() {
        ArmCore core = newCore();
        // Qm: word0 = 0x7000_0000 (satura ao deslocar 4), word1 = 1 (não satura).
        core.vfp().setQ(1, 0x7000_0000L | (1L << 32), 0L);
        core.vfp().setQ(0, 0L, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00F0); // word1 (lane 1) ativa; word0 (lane 0) desligada.
        // VQSHLI_S, word, shift=4.
        int r = rawShift(0, 0b0111, ((1 << 5) | 4), 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0L, core.vfp().element(0, 0, 2), "lane 0 mascarada preserva o destino (0)");
        assertEquals(1L << 4, core.vfp().element(0, 1, 2), "lane 1 ativa desloca normalmente, sem saturar");
        assertFalse(core.fpscr().qc(), "nenhuma lane ATIVA saturou (só a lane mascarada satura)");
    }

    @Test
    void saturatingShiftLeftSetsQcWhenActiveLaneSaturates() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x7000_0000L, 0L);
        core.vfp().setQ(0, 0L, 0L);
        // VQSHLI_S, word, shift=4 -> satura em INT_MAX.
        int r = rawShift(0, 0b0111, ((1 << 5) | 4), 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x7FFF_FFFFL, core.vfp().element(0, 0, 2));
        assertTrue(core.fpscr().qc());
    }

    @Test
    void vqshluiSaturatesSignedSourceToUnsigned() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0xFFFF_FFFFL, 0L); // word0 = -1 (assinado).
        core.vfp().setQ(0, 0L, 0L);
        // VQSHLUI, word, shift=1: fonte assinada -1, satura em 0 (não assinado).
        int r = rawShift(1, 0b0110, ((1 << 5) | 1), 0, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0L, core.vfp().element(0, 0, 2), "fonte negativa satura em 0, não em lixo");
        assertTrue(core.fpscr().qc());
    }

    // ── Formas não-saturantes nunca setam QC ────────────────────────────────────────────────────

    @Test
    void plainShiftRightNeverSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0xFFFF_FFFFL, 0L);
        core.vfp().setQ(0, 0L, 0L);
        int r = rawShift(1, 0b0000, prefixHalfword(1), 0, 1); // VSHRI_U
        put32(core, CODE_BASE, r);

        core.step();

        assertFalse(core.fpscr().qc());
    }
}
