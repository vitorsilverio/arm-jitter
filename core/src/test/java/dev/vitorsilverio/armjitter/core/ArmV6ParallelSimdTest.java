package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// Aritmética paralela ARMv6 da task B1.3 (PR1): SADD16/SSUB16/SASX/SSAX/SADD8/SSUB8 e as
/// variantes Q/SH/U/UQ/UH, com os flags GE do CPSR. Vetores com lanes independentes no ARMV6K,
/// gating UNDEFINED em ARMv4T/v5TE (G2) e a regressão explícita de MSR pedida pela spec.
class ArmV6ParallelSimdTest {
    /// Bits 22:20 do encoding (variante): S/Q/SH/U/UQ/UH.
    private static final int VARIANT_S = 0b001;
    private static final int VARIANT_Q = 0b010;
    private static final int VARIANT_SH = 0b011;
    private static final int VARIANT_U = 0b101;
    private static final int VARIANT_UQ = 0b110;
    private static final int VARIANT_UH = 0b111;
    /// Bits 7:5 do encoding (operação): lanes somadas/subtraídas.
    private static final int OP_ADD16 = 0b000;
    private static final int OP_ASX = 0b001;
    private static final int OP_SAX = 0b010;
    private static final int OP_SUB16 = 0b011;
    private static final int OP_ADD8 = 0b100;
    private static final int OP_SUB8 = 0b111;

    /// Codifica `cccc 0110 0ppp nnnn dddd 1111 ttt1 mmmm` (cond AL).
    private static int parallel(int variant, int op, int rd, int rn, int rm) {
        return 0xE600_0F10 | (variant << 20) | (rn << 16) | (rd << 12) | (op << 5) | rm;
    }

    private static ArmCore run(int instruction, Consumer<ArmCore> init) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        init.accept(core);
        core.step();
        return core;
    }

    /// Executa `<op> r2, r0, r1` com r0/r1 informados e devolve o core para as asserções.
    private static ArmCore runBinary(int variant, int op, int rn, int rm) {
        return run(parallel(variant, op, 2, 0, 1), core -> {
            core.setRegister(0, rn);
            core.setRegister(1, rm);
        });
    }

    // ── Formas com sinal (escrevem GE: lane ≥ 0) ────────────────────────────────

    @Test
    void sadd16LanesAreIndependentAndGeReflectsTheSign() {
        // hi: -32768 + 0 = -32768 (<0, GE[3:2]=00); lo: 1 + 1 = 2 (GE[1:0]=11).
        ArmCore core = runBinary(VARIANT_S, OP_ADD16, 0x8000_0001, 0x0000_0001);
        assertEquals(0x8000_0002, core.register(2));
        assertEquals(0b0011, core.cpsr().ge());
    }

    @Test
    void sadd16GeUsesTheWideSumNotTheTruncatedResult() {
        // hi: 32767 + 1 = 32768 (≥0 na soma larga, embora o resultado trunque para 0x8000).
        ArmCore core = runBinary(VARIANT_S, OP_ADD16, 0x7FFF_0000, 0x0001_0000);
        assertEquals(0x8000_0000, core.register(2));
        assertEquals(0b1111, core.cpsr().ge());
    }

    @Test
    void ssub16GeClearsOnNegativeDifference() {
        // hi: 1 - 2 = -1 (GE[3:2]=00); lo: 5 - 3 = 2 (GE[1:0]=11).
        ArmCore core = runBinary(VARIANT_S, OP_SUB16, 0x0001_0005, 0x0002_0003);
        assertEquals(0xFFFF_0002, core.register(2));
        assertEquals(0b0011, core.cpsr().ge());
    }

    @Test
    void sasxSubtractsTheLowLaneAndAddsTheHighLane() {
        // ASX: lo = Rn.lo - Rm.hi = 5 - 6 = -1; hi = Rn.hi + Rm.lo = 3 + 2 = 5.
        ArmCore core = runBinary(VARIANT_S, OP_ASX, 0x0003_0005, 0x0006_0002);
        assertEquals(0x0005_FFFF, core.register(2));
        assertEquals(0b1100, core.cpsr().ge());
    }

    @Test
    void ssaxAddsTheLowLaneAndSubtractsTheHighLane() {
        // SAX: lo = Rn.lo + Rm.hi = 5 + 1 = 6; hi = Rn.hi - Rm.lo = 3 - 4 = -1.
        ArmCore core = runBinary(VARIANT_S, OP_SAX, 0x0003_0005, 0x0001_0004);
        assertEquals(0xFFFF_0006, core.register(2));
        assertEquals(0b0011, core.cpsr().ge());
    }

    @Test
    void sadd8SetsOneGeBitPerByte() {
        // b3: -128+0 (<0); b2: 127+1=128 (≥0); b1: 1+1; b0: -1+1=0 (≥0).
        ArmCore core = runBinary(VARIANT_S, OP_ADD8, 0x807F_01FF, 0x0001_0101);
        assertEquals(0x8080_0200, core.register(2));
        assertEquals(0b0111, core.cpsr().ge());
    }

    // ── Formas sem sinal (GE = carry na soma / sem borrow na subtração) ─────────

    @Test
    void uadd16GeIsTheCarryOfEachLane() {
        // hi: 0xFFFF + 1 = carry (GE[3:2]=11, resultado 0); lo: 1 + 1 sem carry (GE[1:0]=00).
        ArmCore core = runBinary(VARIANT_U, OP_ADD16, 0xFFFF_0001, 0x0001_0001);
        assertEquals(0x0000_0002, core.register(2));
        assertEquals(0b1100, core.cpsr().ge());
    }

    @Test
    void usub16GeIsTheAbsenceOfBorrow() {
        // hi: 1 - 2 = borrow (GE[3:2]=00); lo: 1 - 1 = 0 sem borrow (GE[1:0]=11).
        ArmCore core = runBinary(VARIANT_U, OP_SUB16, 0x0001_0001, 0x0002_0001);
        assertEquals(0xFFFF_0000, core.register(2));
        assertEquals(0b0011, core.cpsr().ge());
    }

    @Test
    void usub8GeReflectsBorrowPerByte() {
        // b3: 5-1 ok; b2: 0-1 borrow; b1: 3-3 ok; b0: 2-3 borrow.
        ArmCore core = runBinary(VARIANT_U, OP_SUB8, 0x0500_0302, 0x0101_0303);
        assertEquals(0x04FF_00FF, core.register(2));
        assertEquals(0b1010, core.cpsr().ge());
    }

    @Test
    void uasxAppliesCarryRuleToTheAddLaneAndBorrowRuleToTheSubLane() {
        // ASX unsigned: lo = 1 - 2 (borrow, GE[1:0]=00); hi = 0xFFFF + 1 (carry, GE[3:2]=11).
        ArmCore core = runBinary(VARIANT_U, OP_ASX, 0xFFFF_0001, 0x0002_0001);
        assertEquals(0x0000_FFFF, core.register(2));
        assertEquals(0b1100, core.cpsr().ge());
    }

    // ── Variantes saturadas (Q/UQ): sem GE e sem o flag Q sticky ────────────────

    @Test
    void qadd16SaturatesBothDirectionsWithoutTouchingGeOrQ() {
        // hi: 32767+1 satura em 0x7FFF; lo: -32768 + (-1) satura em 0x8000.
        ArmCore core = run(parallel(VARIANT_Q, OP_ADD16, 2, 0, 1), c -> {
            c.cpsr().setGe(0b0101);
            c.setRegister(0, 0x7FFF_8000);
            c.setRegister(1, 0x0001_FFFF);
        });
        assertEquals(0x7FFF_8000, core.register(2));
        assertEquals(0b0101, core.cpsr().ge(), "Q* paralelas não escrevem GE");
        assertFalse(core.cpsr().saturation(), "Q* paralelas não setam o flag Q sticky");
    }

    @Test
    void uqadd8SaturatesTo255() {
        // b3: 0xFF+1 e b1: 0xFE+3 saturam em 0xFF; b2: 1+1 e b0: 0+1 seguem normais.
        ArmCore core = runBinary(VARIANT_UQ, OP_ADD8, 0xFF01_FE00, 0x0101_0301);
        assertEquals(0xFF02_FF01, core.register(2));
    }

    @Test
    void uqsub8SaturatesToZero() {
        // b0: 0-2 e b3/b1 com borrow saturam em 0; b2: 5-1=4 segue normal.
        ArmCore core = runBinary(VARIANT_UQ, OP_SUB8, 0x0005_0100, 0x0101_0202);
        assertEquals(0x0004_0000, core.register(2));
    }

    // ── Variantes halving (SH/UH): resultado >> 1, sem saturação e sem GE ───────

    @Test
    void shadd16HalvesWithoutSaturating() {
        // hi: (32767+32767)>>1 = 32767; lo: (-32768-32768)>>1 = -32768. GE pré-setado intocado.
        ArmCore core = run(parallel(VARIANT_SH, OP_ADD16, 2, 0, 1), c -> {
            c.cpsr().setGe(0b1111);
            c.setRegister(0, 0x7FFF_8000);
            c.setRegister(1, 0x7FFF_8000);
        });
        assertEquals(0x7FFF_8000, core.register(2));
        assertEquals(0b1111, core.cpsr().ge(), "SH* não escrevem GE");
    }

    @Test
    void uhadd8HalvesEachByte() {
        // b3: (0xFF+0xFF)>>1 = 0xFF; b0: (2+4)>>1 = 3.
        ArmCore core = runBinary(VARIANT_UH, OP_ADD8, 0xFF00_0002, 0xFF00_0004);
        assertEquals(0xFF00_0003, core.register(2));
    }

    @Test
    void uhsub8HalvesTheNineBitDifference() {
        // b0: (1-255)>>1 = -127 → 0x81 (bits 8:1 da diferença de 9 bits, como no manual).
        ArmCore core = runBinary(VARIANT_UH, OP_SUB8, 0x0000_0001, 0x0000_00FF);
        assertEquals(0x0000_0081, core.register(2));
    }

    // ── Condição falsa não executa nem toca GE ──────────────────────────────────

    @Test
    void falseConditionSkipsResultAndGe() {
        int sadd16ne = (parallel(VARIANT_S, OP_ADD16, 2, 0, 1) & 0x0FFF_FFFF) | 0x1000_0000;
        ArmCore core = run(sadd16ne, c -> {
            c.cpsr().setNzcv(false, true, false, false); // Z=1 → NE falha
            c.cpsr().setGe(0b0101);
            c.setRegister(0, 0x0001_0001);
            c.setRegister(1, 0x0001_0001);
            c.setRegister(2, 0xDEAD_BEEF);
        });
        assertEquals(0xDEAD_BEEF, core.register(2));
        assertEquals(0b0101, core.cpsr().ge());
    }

    // ── MSR e os GE (item 1 da spec) ─────────────────────────────────────────────

    @Test
    void msrFieldSWritesGeOnArmv6k() {
        // MSR CPSR_s, #0x000A0000 (imm8=0x0A, rot=8) — campo s cobre os bits GE 19:16.
        ArmCore core = run(0xE324_F80A, c -> { });
        assertEquals(0xA, core.cpsr().ge());
    }

    /// A spec exige que o comportamento do MSR em ARMv4T/v5TE não mude um bit: o `mergePsr`
    /// já escrevia o campo `s` inteiro (bits 23:16, incluindo as posições 19:16) antes da
    /// B1.3, e continua escrevendo — este teste pina esse comportamento pré-existente.
    @Test
    void msrFieldSBehaviourIsUnchangedOnArmv4tAndArmv5te() {
        for (ArmArchitecture architecture : new ArmArchitecture[] {
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE }) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, 0xE324_F80A); // MSR CPSR_s, #0x000A0000
            ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
            int before = core.cpsr().get();
            core.step();
            assertEquals((before & ~0x00FF_0000) | 0x000A_0000, core.cpsr().get(),
                    "campo s continua escrito por inteiro em " + architecture);
        }
    }

    // ── Gating de arquitetura (G2) e buracos do encoding ─────────────────────────

    @Test
    void parallelEncodingsStayUndefinedOnArmv4tAndArmv5te() {
        int[] variants = {VARIANT_S, VARIANT_Q, VARIANT_SH, VARIANT_U, VARIANT_UQ, VARIANT_UH};
        int[] ops = {OP_ADD16, OP_ASX, OP_SAX, OP_SUB16, OP_ADD8, OP_SUB8};
        for (int variant : variants) {
            for (int op : ops) {
                int encoding = parallel(variant, op, 2, 0, 1);
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
    }

    @Test
    void parallelEncodingsDecodeOnArmv6k() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, parallel(VARIANT_S, OP_ADD16, 2, 0, 1));
        assertEquals(InstructionKind.PARALLEL_ALU, decoder.decode(memory, 0).kind());
    }

    @Test
    void encodingHolesStayUndefinedEvenOnArmv6k() {
        // ppp=000/100 (variantes) e ttt=101/110 (operações) são buracos do espaço paralelo.
        int[] holes = {
                parallel(0b000, OP_ADD16, 2, 0, 1),
                parallel(0b100, OP_ADD16, 2, 0, 1),
                parallel(VARIANT_S, 0b101, 2, 0, 1),
                parallel(VARIANT_S, 0b110, 2, 0, 1),
        };
        for (int encoding : holes) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV6K).decode(memory, 0).kind(),
                    () -> "buraco deve seguir UNDEFINED: 0x" + Integer.toHexString(encoding));
        }
    }
}
