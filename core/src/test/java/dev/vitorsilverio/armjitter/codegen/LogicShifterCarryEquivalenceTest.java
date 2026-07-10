package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Task C2 — carry-out do barrel shifter emitido NATIVAMENTE para ops lógicas com S e para os
/// shifts com S. Property test exaustivo: cada op lógica × tipo de shift com quantidade por
/// REGISTRADOR varre n = 0..255 (e os imediatos especiais LSR/ASR #32, RRX e imediato
/// rotacionado), nos DOIS estados iniciais de carry, comparando o bytecode ASM com o
/// interpretador (o oráculo — invariante G1). O bloco é compilado UMA vez por encoding e
/// reexecutado em cores frescos, então a varredura inteira fica barata.
class LogicShifterCarryEquivalenceTest {
    /// Valores de operando com bits misturados + um negativo (exercita ASR e o bit 31 do ROR).
    private static final int[] VALUES = {0xA5C3_F00F, 0x8000_0001};
    /// Valor fixo de Rn (src1 das lógicas de dois operandos), distinto dos VALUES.
    private static final int SRC1_VALUE = 0x0F0F_1234;

    private final CodeEmitter reference = new InterpretedCodeEmitter();
    private final AsmCodeEmitter candidate = new AsmCodeEmitter();

    /// `<op>S r0, r1, r2, <type> r3` (quantidade por registrador, cond AL).
    private static int armLogicRegShift(int aluOpcode, int shiftType) {
        final int setFlags = 1 << 20;
        final int rn = 1 << 16;
        final int rd = 0 << 12;
        final int rs = 3 << 8;
        final int rm = 2;
        return 0xE000_0010 | (aluOpcode << 21) | setFlags | rn | rd | rs | (shiftType << 5) | rm;
    }

    private static IrBlock liftArm(int instruction) {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, instruction);
        return new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);
    }

    private static IrBlock liftThumb(int halfword) {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, halfword);
        return new StandardIrBlockLifter(new ThumbDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);
    }

    private static ArmCore freshCore(boolean carryIn, int src1, int rmValue, int amount) {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.cpsr().setNzcv(false, false, carryIn, false);
        core.setRegister(1, src1);
        core.setRegister(2, rmValue);
        core.setRegister(3, amount);
        return core;
    }

    /// Executa referência e candidato no mesmo estado inicial e compara registradores, NZCV e
    /// ciclos internos.
    private static void assertSameOutcome(CompiledBlock refBlock, CompiledBlock candBlock,
            boolean carryIn, int src1, int rmValue, int amount, String context) {
        ArmCore ref = freshCore(carryIn, src1, rmValue, amount);
        ArmCore cand = freshCore(carryIn, src1, rmValue, amount);
        int refCycles = refBlock.execute(ref);
        int candCycles = candBlock.execute(cand);
        String detail = context + " carryIn=" + carryIn + " rm=0x" + Integer.toHexString(rmValue)
                + " n=" + amount;
        for (int register = 0; register < 16; register++) {
            assertEquals(ref.register(register), cand.register(register), detail + " r" + register);
        }
        assertEquals(ref.cpsr().negative(), cand.cpsr().negative(), detail + " N");
        assertEquals(ref.cpsr().zero(), cand.cpsr().zero(), detail + " Z");
        assertEquals(ref.cpsr().carry(), cand.cpsr().carry(), detail + " C");
        assertEquals(ref.cpsr().overflow(), cand.cpsr().overflow(), detail + " V");
        assertEquals(refCycles, candCycles, detail + " cycles");
    }

    private void sweepAmounts(int instruction, IrBlock block, int maxAmount) {
        assertTrue(candidate.isNativeSupported(block),
                "task C2: 0x" + Integer.toHexString(instruction) + " deve ser nativo agora");
        CompiledBlock refBlock = reference.emit(block);
        CompiledBlock candBlock = candidate.emit(block);
        String context = "0x" + Integer.toHexString(instruction);
        for (int amount = 0; amount <= maxAmount; amount++) {
            for (int value : VALUES) {
                assertSameOutcome(refBlock, candBlock, false, SRC1_VALUE, value, amount, context);
                assertSameOutcome(refBlock, candBlock, true, SRC1_VALUE, value, amount, context);
            }
        }
    }

    @Test
    void armLogicOpsWithRegisterShiftMatchTheInterpreterForAllAmounts() {
        // AND, EOR, TST, TEQ, ORR, MOV, BIC, MVN — todas as lógicas cujo C vem do shifter.
        int[] aluOpcodes = {0x0, 0x1, 0x8, 0x9, 0xC, 0xD, 0xE, 0xF};
        for (int aluOpcode : aluOpcodes) {
            for (int shiftType = 0; shiftType < 4; shiftType++) {
                int instruction = armLogicRegShift(aluOpcode, shiftType);
                sweepAmounts(instruction, liftArm(instruction), 255);
            }
        }
    }

    @Test
    void armImmediateShiftSpecialCasesMatchTheInterpreter() {
        int[] instructions = {
                0xE1B0_0021, // MOVS r0, r1, LSR #32  (encoding LSR #0)
                0xE1B0_0041, // MOVS r0, r1, ASR #32  (encoding ASR #0)
                0xE1B0_0061, // MOVS r0, r1, RRX      (encoding ROR #0)
                0xE1B0_0001, // MOVS r0, r1           (registrador puro — C inalterado)
                0xE1B0_0FE1, // MOVS r0, r1, ROR #31
                0xE3B0_04FF, // MOVS r0, #0xFF000000  (imediato rotacionado — C = bit 31)
                0xE3B0_00FF, // MOVS r0, #0xFF        (rotate 0 — C inalterado)
                0xE211_00FF, // ANDS r0, r1, #0xFF    (rotate 0 — C inalterado)
        };
        for (int instruction : instructions) {
            IrBlock block = liftArm(instruction);
            assertTrue(candidate.isNativeSupported(block),
                    "task C2: 0x" + Integer.toHexString(instruction) + " deve ser nativo agora");
            CompiledBlock refBlock = reference.emit(block);
            CompiledBlock candBlock = candidate.emit(block);
            int[] operands = {0, 1, 0x8000_0000, 0xFFFF_FFFF, 0x7FFF_FFFF, 0xA5C3_F00F};
            String context = "0x" + Integer.toHexString(instruction);
            for (int operand : operands) {
                // r1 é o operando destas formas — o slot `src1` do freshCore.
                assertSameOutcome(refBlock, candBlock, false, operand, 0, 0, context);
                assertSameOutcome(refBlock, candBlock, true, operand, 0, 0, context);
            }
        }
    }

    @Test
    void thumbShiftsByRegisterMatchTheInterpreterForAllAmounts() {
        // Formato 4 do Thumb: `<shift>S r2, r3` — valor em r2, quantidade em r3 (byte baixo).
        int[] halfwords = {
                0x4080 | (3 << 3) | 2, // LSLS r2, r3
                0x40C0 | (3 << 3) | 2, // LSRS r2, r3
                0x4100 | (3 << 3) | 2, // ASRS r2, r3
                0x41C0 | (3 << 3) | 2, // RORS r2, r3
        };
        for (int halfword : halfwords) {
            sweepAmounts(halfword, liftThumb(halfword), 255);
        }
    }

    @Test
    void thumbImmediateShiftsMatchTheInterpreterForAllImmediates() {
        // Formato 1 do Thumb: `<shift>S r0, r2, #imm` — LSR/ASR com imm 0 significam #32.
        int[] bases = {0x0000, 0x0800, 0x1000}; // LSL, LSR, ASR
        for (int base : bases) {
            for (int imm = 0; imm < 32; imm++) {
                int halfword = base | (imm << 6) | (2 << 3);
                sweepAmounts(halfword, liftThumb(halfword), 0);
            }
        }
    }
}
