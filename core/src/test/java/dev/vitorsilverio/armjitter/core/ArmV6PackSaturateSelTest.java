package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// O restante do bloco "media" ARMv6 da task B1.3 (PR2): SEL (consome GE), PKHBT/PKHTB,
/// SSAT/USAT/SSAT16/USAT16 (setam o Q sticky) e USAD8/USADA8. Vetores no ARMV6K e gating
/// UNDEFINED em ARMv4T/v5TE (G2).
class ArmV6PackSaturateSelTest {
    /// Rn=1111 no encoding de USAD = forma sem acumulador.
    private static final int NO_ACCUMULATOR = 0xF;

    /// Codifica `SEL rd, rn, rm` (cond AL).
    private static int sel(int rd, int rn, int rm) {
        return 0xE680_0FB0 | (rn << 16) | (rd << 12) | rm;
    }

    /// Codifica `PKHBT rd, rn, rm, LSL #imm` (tb=false) ou `PKHTB rd, rn, rm, ASR #imm` (tb=true).
    private static int pkh(boolean tb, int rd, int rn, int rm, int imm) {
        return 0xE680_0010 | (rn << 16) | (rd << 12) | (imm << 7) | (tb ? 1 << 6 : 0) | rm;
    }

    /// Codifica `SSAT rd, #satImm+1, rm {shift}` / `USAT rd, #satImm, rm {shift}` (formas word).
    private static int sat(boolean unsigned, int rd, int satImm, int rm, int shiftImm, boolean asr) {
        return 0xE6A0_0010 | (unsigned ? 1 << 22 : 0) | (satImm << 16) | (rd << 12)
                | (shiftImm << 7) | (asr ? 1 << 6 : 0) | rm;
    }

    /// Codifica `SSAT16 rd, #satImm+1, rm` / `USAT16 rd, #satImm, rm`.
    private static int sat16(boolean unsigned, int rd, int satImm, int rm) {
        return 0xE6A0_0F30 | (unsigned ? 1 << 22 : 0) | (satImm << 16) | (rd << 12) | rm;
    }

    /// Codifica `USAD8 rd, rm, rs` (rn=1111) ou `USADA8 rd, rm, rs, rn`.
    private static int usad(int rd, int rm, int rs, int rn) {
        return 0xE780_0010 | (rd << 16) | (rn << 12) | (rs << 8) | rm;
    }

    private static ArmCore run(int instruction, Consumer<ArmCore> init) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        init.accept(core);
        core.step();
        return core;
    }

    // ── SEL ──────────────────────────────────────────────────────────────────────

    @Test
    void selPicksEachByteByTheGeFlag() {
        ArmCore core = run(sel(2, 0, 1), c -> {
            c.cpsr().setGe(0b0101);
            c.setRegister(0, 0x1122_3344);
            c.setRegister(1, 0xAABB_CCDD);
        });
        assertEquals(0xAA22_CC44, core.register(2));
    }

    @Test
    void selConsumesTheGeProducedByParallelArithmetic() {
        // UADD8 r3,r0,r1 gera carry só no byte 3 → SEL escolhe o byte 3 de Rn e o resto de Rm.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE650_3F91); // UADD8 r3, r0, r1
        memory.put32(4, sel(2, 0, 1));
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, 0xFF00_0001);
        core.setRegister(1, 0x0100_0001);
        core.step();
        core.step();
        assertEquals(0b1000, core.cpsr().ge());
        assertEquals(0xFF00_0001, core.register(2));
    }

    // ── PKHBT / PKHTB ────────────────────────────────────────────────────────────

    @Test
    void pkhbtPacksBottomFromRnAndShiftedTopFromRm() {
        ArmCore core = run(pkh(false, 2, 0, 1, 8), c -> {
            c.setRegister(0, 0x1111_2222);
            c.setRegister(1, 0x0000_3344);
        });
        assertEquals(0x0033_2222, core.register(2));
    }

    @Test
    void pkhbtWithoutShiftUsesRmAsIs() {
        ArmCore core = run(pkh(false, 2, 0, 1, 0), c -> {
            c.setRegister(0, 0x1111_2222);
            c.setRegister(1, 0x5566_0000);
        });
        assertEquals(0x5566_2222, core.register(2));
    }

    @Test
    void pkhtbPacksTopFromRnAndAsrShiftedBottomFromRm() {
        ArmCore core = run(pkh(true, 2, 0, 1, 8), c -> {
            c.setRegister(0, 0x1111_2222);
            c.setRegister(1, 0x0001_2345);
        });
        assertEquals(0x1111_0123, core.register(2));
    }

    @Test
    void pkhtbShiftZeroMeansAsr32() {
        ArmCore core = run(pkh(true, 2, 0, 1, 0), c -> {
            c.setRegister(0, 0x1111_2222);
            c.setRegister(1, 0x8000_0000);
        });
        assertEquals(0x1111_FFFF, core.register(2));
    }

    // ── SSAT / USAT (word) ───────────────────────────────────────────────────────

    @Test
    void ssatClampsBothDirectionsAndSetsQ() {
        // SSAT #8 → faixa [-128, 127].
        assertEquals(127, run(sat(false, 2, 7, 1, 0, false),
                c -> c.setRegister(1, 4096)).register(2));
        ArmCore negative = run(sat(false, 2, 7, 1, 0, false), c -> c.setRegister(1, -4096));
        assertEquals(-128, negative.register(2));
        assertTrue(negative.cpsr().saturation());
    }

    @Test
    void ssatInRangeLeavesQUntouched() {
        ArmCore core = run(sat(false, 2, 7, 1, 0, false), c -> c.setRegister(1, 100));
        assertEquals(100, core.register(2));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void ssatAppliesTheShiftBeforeSaturating() {
        // LSL #4: 16 << 4 = 256 → satura em 127; ASR #4: 2048 >> 4 = 128 → satura em 127.
        assertEquals(127, run(sat(false, 2, 7, 1, 4, false),
                c -> c.setRegister(1, 16)).register(2));
        assertEquals(127, run(sat(false, 2, 7, 1, 4, true),
                c -> c.setRegister(1, 2048)).register(2));
    }

    @Test
    void ssatWidth32NeverSaturates() {
        ArmCore core = run(sat(false, 2, 31, 1, 0, false), c -> c.setRegister(1, 0x8000_0000));
        assertEquals(0x8000_0000, core.register(2));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void usatClampsNegativesToZeroAndOverflowToMax() {
        // USAT #8 → faixa [0, 255].
        ArmCore negative = run(sat(true, 2, 8, 1, 0, false), c -> c.setRegister(1, -1));
        assertEquals(0, negative.register(2));
        assertTrue(negative.cpsr().saturation());
        assertEquals(255, run(sat(true, 2, 8, 1, 0, false),
                c -> c.setRegister(1, 300)).register(2));
        ArmCore inRange = run(sat(true, 2, 8, 1, 0, false), c -> c.setRegister(1, 200));
        assertEquals(200, inRange.register(2));
        assertFalse(inRange.cpsr().saturation());
    }

    @Test
    void usatWidthZeroClampsEverythingToZero() {
        ArmCore core = run(sat(true, 2, 0, 1, 0, false), c -> c.setRegister(1, 5));
        assertEquals(0, core.register(2));
        assertTrue(core.cpsr().saturation());
    }

    @Test
    void qFlagIsStickyAcrossNonSaturatingResults() {
        ArmCore core = run(sat(false, 2, 7, 1, 0, false), c -> {
            c.cpsr().setSaturation(true);
            c.setRegister(1, 100);
        });
        assertTrue(core.cpsr().saturation(), "Q é sticky: só MSR limpa");
    }

    // ── SSAT16 / USAT16 ──────────────────────────────────────────────────────────

    @Test
    void ssat16SaturatesEachHalfwordIndependently() {
        // SSAT16 #8: high 32767 → 127; low 100 fica.
        ArmCore core = run(sat16(false, 2, 7, 1), c -> c.setRegister(1, 0x7FFF_0064));
        assertEquals(0x007F_0064, core.register(2));
        assertTrue(core.cpsr().saturation());
    }

    @Test
    void ssat16NegativeHalfwordClampsToMinimum() {
        ArmCore core = run(sat16(false, 2, 7, 1), c -> c.setRegister(1, 0x8000_0032));
        assertEquals(0xFF80_0032, core.register(2));
    }

    @Test
    void usat16TreatsInputHalvesAsSignedAndClampsUnsigned() {
        // high 0x8000 (negativo) → 0; low 0x0123 (291) → 255.
        ArmCore core = run(sat16(true, 2, 8, 1), c -> c.setRegister(1, 0x8000_0123));
        assertEquals(0x0000_00FF, core.register(2));
        assertTrue(core.cpsr().saturation());
    }

    // ── USAD8 / USADA8 ───────────────────────────────────────────────────────────

    @Test
    void usad8SumsAbsoluteByteDifferences() {
        // |1-4| + |2-3| + |3-2| + |4-1| = 8, com bytes maiores e menores misturados.
        ArmCore core = run(usad(2, 0, 1, NO_ACCUMULATOR), c -> {
            c.setRegister(0, 0x0102_0304);
            c.setRegister(1, 0x0403_0201);
        });
        assertEquals(8, core.register(2));
    }

    @Test
    void usada8AddsTheAccumulator() {
        ArmCore core = run(usad(2, 0, 1, 3), c -> {
            c.setRegister(0, 0x0102_0304);
            c.setRegister(1, 0x0403_0201);
            c.setRegister(3, 100);
        });
        assertEquals(108, core.register(2));
    }

    @Test
    void usad8TreatsBytesAsUnsigned() {
        // |0xFF - 0x00| = 255 (sem sinal), não |-1 - 0| = 1.
        ArmCore core = run(usad(2, 0, 1, NO_ACCUMULATOR), c -> {
            c.setRegister(0, 0x0000_00FF);
            c.setRegister(1, 0x0000_0000);
        });
        assertEquals(255, core.register(2));
    }

    // ── Gating de arquitetura (G2) ───────────────────────────────────────────────

    @Test
    void packSaturateSelEncodingsStayUndefinedOnArmv4tAndArmv5te() {
        int[] encodings = {
                sel(2, 0, 1),
                pkh(false, 2, 0, 1, 8),
                pkh(true, 2, 0, 1, 8),
                sat(false, 2, 7, 1, 0, false),
                sat(true, 2, 8, 1, 0, false),
                sat16(false, 2, 7, 1),
                sat16(true, 2, 8, 1),
                usad(2, 0, 1, NO_ACCUMULATOR),
                usad(2, 0, 1, 3),
        };
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind(),
                    () -> "ARMv4T deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind(),
                    () -> "ARMv5TE deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
        }
    }

    @Test
    void packSaturateSelEncodingsDecodeOnArmv6k() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, sel(2, 0, 1));
        memory.put32(4, pkh(false, 2, 0, 1, 8));
        memory.put32(8, sat(false, 2, 7, 1, 0, false));
        memory.put32(12, sat16(true, 2, 8, 1));
        memory.put32(16, usad(2, 0, 1, NO_ACCUMULATOR));
        assertEquals(InstructionKind.SEL, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.PKH, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.SATURATE, decoder.decode(memory, 8).kind());
        assertEquals(InstructionKind.SATURATE, decoder.decode(memory, 12).kind());
        assertEquals(InstructionKind.USAD8, decoder.decode(memory, 16).kind());
    }
}
