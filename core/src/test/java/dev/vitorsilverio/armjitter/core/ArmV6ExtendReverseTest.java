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

/// As instruções ARMv6 da task B1.2: extensão com rotação (SXT*/UXT* e as formas com
/// acumulador), inversão de bytes (REV/REV16/REVSH) e UMAAL. Vetores concretos no ARMV6K,
/// mais a prova de que cada encoding segue UNDEFINED em ARMV4T e ARMV5TE (invariante G2).
class ArmV6ExtendReverseTest {
    /// Rn=1111 nas encodings de extensão = forma sem acumulador.
    private static final int NO_ACCUMULATOR = 0xF;

    /// Codifica `cccc 0110 1uff nnnn dddd rr00 0111 mmmm` (cond AL).
    /// `field`: 0b00=B16, 0b10=B, 0b11=H; `rotate` em múltiplos de 8 bits (0..3).
    private static int extend(boolean unsigned, int field, int rd, int rn, int rm, int rotate) {
        return 0xE680_0070 | (unsigned ? 1 << 22 : 0) | (field << 20)
                | (rn << 16) | (rd << 12) | (rotate << 10) | rm;
    }

    /// Codifica REV (0), REV16 (1) ou REVSH (2): `rd`, `rm`, cond AL.
    private static int rev(int variant, int rd, int rm) {
        int base = switch (variant) {
            case 0 -> 0xE6BF_0F30;
            case 1 -> 0xE6BF_0FB0;
            default -> 0xE6FF_0FB0;
        };
        return base | (rd << 12) | rm;
    }

    /// Codifica `UMAAL rdLo, rdHi, rm, rs` (cond AL).
    private static int umaal(int rdLo, int rdHi, int rm, int rs) {
        return 0xE040_0090 | (rdHi << 16) | (rdLo << 12) | (rs << 8) | rm;
    }

    private static ArmCore run(int instruction, Consumer<ArmCore> init) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        init.accept(core);
        core.step();
        return core;
    }

    private static int runUnary(int instruction, int rmValue) {
        return run(instruction, core -> core.setRegister(0, rmValue)).register(1);
    }

    // ── Byte reverse ─────────────────────────────────────────────────────────────

    @Test
    void revReversesTheFourBytes() {
        assertEquals(0x78563412, runUnary(rev(0, 1, 0), 0x12345678));
    }

    @Test
    void rev16ReversesTheBytesOfEachHalfword() {
        assertEquals(0x34127856, runUnary(rev(1, 1, 0), 0x12345678));
    }

    @Test
    void revshReversesTheLowHalfwordAndSignExtends() {
        assertEquals(0xFFFF8083, runUnary(rev(2, 1, 0), 0x00008380));
    }

    // ── Extensão sem acumulador ──────────────────────────────────────────────────

    @Test
    void sxtbSignExtendsTheLowByte() {
        assertEquals(0xFFFFFFFF, runUnary(extend(false, 0b10, 1, NO_ACCUMULATOR, 0, 0), 0x000000FF));
    }

    @Test
    void sxtbAppliesTheOperandRotation() {
        assertEquals(0xFFFFFFFF, runUnary(extend(false, 0b10, 1, NO_ACCUMULATOR, 0, 1), 0x0000FF00));
    }

    @Test
    void sxthSignExtendsTheLowHalfword() {
        assertEquals(0xFFFF8000, runUnary(extend(false, 0b11, 1, NO_ACCUMULATOR, 0, 0), 0x00008000));
    }

    @Test
    void uxtbZeroExtendsTheLowByte() {
        assertEquals(0x000000AB, runUnary(extend(true, 0b10, 1, NO_ACCUMULATOR, 0, 0), 0xFFFFFFAB));
    }

    @Test
    void uxthZeroExtendsTheLowHalfword() {
        assertEquals(0x00005678, runUnary(extend(true, 0b11, 1, NO_ACCUMULATOR, 0, 0), 0x12345678));
    }

    @Test
    void uxthAppliesRotation24() {
        // ROR #24 de 0xAB000000 = 0x000000AB; UXTH mantém o halfword baixo 0x00AB.
        assertEquals(0x000000AB, runUnary(extend(true, 0b11, 1, NO_ACCUMULATOR, 0, 3), 0xAB000000));
    }

    @Test
    void sxtb16SignExtendsBothEvenBytes() {
        // Bytes 7:0 (0x82) e 23:16 (0x81) — bits 23:16 e 7:0, os dois bytes PARES.
        assertEquals(0xFF81FF82, runUnary(extend(false, 0b00, 1, NO_ACCUMULATOR, 0, 0), 0x00810082));
    }

    @Test
    void uxtb16ZeroExtendsBothEvenBytes() {
        assertEquals(0x00810082, runUnary(extend(true, 0b00, 1, NO_ACCUMULATOR, 0, 0), 0xFF81FF82));
    }

    // ── Formas com acumulador ────────────────────────────────────────────────────

    @Test
    void sxtabAddsTheSignExtendedByteToTheAccumulator() {
        // SXTAB r0, r2, r1: 5 + sext(0xFF) = 5 + (-1) = 4.
        ArmCore core = run(extend(false, 0b10, 0, 2, 1, 0), c -> {
            c.setRegister(2, 5);
            c.setRegister(1, 0x000000FF);
        });
        assertEquals(4, core.register(0));
    }

    @Test
    void sxtahAddsTheSignExtendedHalfwordToTheAccumulator() {
        // 0x10000 + sext(0x8000) = 0x10000 - 0x8000 = 0x8000.
        ArmCore core = run(extend(false, 0b11, 0, 2, 1, 0), c -> {
            c.setRegister(2, 0x00010000);
            c.setRegister(1, 0x00008000);
        });
        assertEquals(0x00008000, core.register(0));
    }

    @Test
    void uxtab16AccumulatesEachHalfwordIndependently() {
        // Cada halfword soma módulo 2^16, sem carry entre as metades:
        // low = 0x0001 + 0xFF = 0x0100; high = 0x0001 + 0xFF = 0x0100.
        ArmCore core = run(extend(true, 0b00, 0, 2, 1, 0), c -> {
            c.setRegister(2, 0x00010001);
            c.setRegister(1, 0x00FF00FF);
        });
        assertEquals(0x01000100, core.register(0));
    }

    @Test
    void sxtab16HalfwordOverflowDoesNotCarryAcross() {
        // low = 0xFFFF + sext(0x01) = 0x0000 (estouro fica na metade); high = 0x0000 + 0 = 0.
        ArmCore core = run(extend(false, 0b00, 0, 2, 1, 0), c -> {
            c.setRegister(2, 0x0000FFFF);
            c.setRegister(1, 0x00000001);
        });
        assertEquals(0x00000000, core.register(0));
    }

    // ── UMAAL ────────────────────────────────────────────────────────────────────

    @Test
    void umaalMaxOperandsDoNotLoseTheCarry() {
        // 0xFFFFFFFF * 0xFFFFFFFF + 0xFFFFFFFF + 0xFFFFFFFF = 0xFFFFFFFF_FFFFFFFF (não estoura).
        ArmCore core = run(umaal(2, 3, 0, 1), c -> {
            c.setRegister(0, 0xFFFFFFFF);
            c.setRegister(1, 0xFFFFFFFF);
            c.setRegister(2, 0xFFFFFFFF);
            c.setRegister(3, 0xFFFFFFFF);
        });
        assertEquals(0xFFFFFFFF, core.register(2), "RdLo");
        assertEquals(0xFFFFFFFF, core.register(3), "RdHi");
    }

    @Test
    void umaalAddsBothAccumulatorsAsIndependent32BitTerms() {
        // 3 * 4 + 5 + 6 = 23 -> RdLo=23, RdHi=0.
        ArmCore core = run(umaal(2, 3, 0, 1), c -> {
            c.setRegister(0, 3);
            c.setRegister(1, 4);
            c.setRegister(2, 5);
            c.setRegister(3, 6);
        });
        assertEquals(23, core.register(2), "RdLo");
        assertEquals(0, core.register(3), "RdHi");
    }

    @Test
    void umaalDoesNotTouchFlags() {
        ArmCore core = run(umaal(2, 3, 0, 1), c -> {
            c.cpsr().setNzcv(false, false, true, true);
            c.setRegister(0, 0xFFFFFFFF);
            c.setRegister(1, 0xFFFFFFFF);
            c.setRegister(2, 0xFFFFFFFF);
            c.setRegister(3, 0xFFFFFFFF);
        });
        assertFalse(core.cpsr().zero());
        assertTrue(core.cpsr().carry());
        assertTrue(core.cpsr().overflow());
    }

    // ── Gating de arquitetura (G2) ───────────────────────────────────────────────

    @Test
    void armv6EncodingsStayUndefinedOnArmv4tAndArmv5te() {
        int[] encodings = {
                extend(false, 0b00, 1, NO_ACCUMULATOR, 0, 0), // SXTB16
                extend(false, 0b10, 1, NO_ACCUMULATOR, 0, 0), // SXTB
                extend(false, 0b11, 1, NO_ACCUMULATOR, 0, 0), // SXTH
                extend(true, 0b00, 1, NO_ACCUMULATOR, 0, 0),  // UXTB16
                extend(true, 0b10, 1, NO_ACCUMULATOR, 0, 0),  // UXTB
                extend(true, 0b11, 1, NO_ACCUMULATOR, 0, 0),  // UXTH
                extend(false, 0b10, 0, 2, 1, 0),              // SXTAB
                extend(false, 0b11, 0, 2, 1, 0),              // SXTAH
                extend(false, 0b00, 0, 2, 1, 0),              // SXTAB16
                extend(true, 0b10, 0, 2, 1, 0),               // UXTAB
                extend(true, 0b11, 0, 2, 1, 0),               // UXTAH
                extend(true, 0b00, 0, 2, 1, 0),               // UXTAB16
                rev(0, 1, 0),                                 // REV
                rev(1, 1, 0),                                 // REV16
                rev(2, 1, 0),                                 // REVSH
                umaal(2, 3, 0, 1),                            // UMAAL
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
    void armv6EncodingsDecodeOnArmv6k() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, extend(true, 0b11, 1, NO_ACCUMULATOR, 0, 0));
        memory.put32(4, rev(0, 1, 0));
        memory.put32(8, umaal(2, 3, 0, 1));
        assertEquals(InstructionKind.EXTEND, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.BYTE_REVERSE, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.UMAAL, decoder.decode(memory, 8).kind());
    }

    @Test
    void extendUndefinedFieldHoleStaysUndefinedEvenOnArmv6k() {
        // ff=01 (bits 21:20) é um buraco indefinido do espaço de extensão.
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE690_0070 | (NO_ACCUMULATOR << 16) | (1 << 12));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV6K).decode(memory, 0).kind());
    }
}
