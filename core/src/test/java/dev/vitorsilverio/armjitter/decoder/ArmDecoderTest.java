package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmDecoderTest {
    @Test
    void decodesArmMovImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE3A0_002A);

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionSet.ARM, instruction.instructionSet());
        assertEquals(Condition.AL, instruction.condition());
        assertEquals(InstructionKind.MOV, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(42, instruction.immediate());
        assertTrue(instruction.immediateOperand());
    }

    @Test
    void decodesArmBranchWithPipelineOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xEA00_0001);

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.BRANCH, instruction.kind());
        assertEquals(12, instruction.immediate());
    }

    @Test
    void decodesArmClz() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE16F_1F10);

        // CLZ é uma instrução ARMv5; o decoder base ARMv4T a rejeita.
        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0);

        assertEquals(InstructionKind.CLZ, instruction.kind());
        assertEquals(1, instruction.destinationRegister());
        assertEquals(0, instruction.sourceRegister());
    }

    // ── PLD/PLDW/PLI (B2.8) — hints de preload, NOP observável ─────────────────────────────

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static void run(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    @Test
    void pldPldwPliHaveNoObservableEffectAndDoNotAccessTheAddress() {
        int[] rawEncodings = {
                0xF551_F010, // PLD [r1,#0x10]
                0xF511_F010, // PLDW [r1,#0x10]
                0xF451_F010, // PLI [r1,#0x10]
                0xF751_F002, // PLD [r1,r2]
                0xF711_F002, // PLDW [r1,r2]
                0xF651_F002, // PLI [r1,r2]
        };
        for (int raw : rawEncodings) {
            ArmCore core = newCore();
            core.setRegister(0, 0xCAFEBABE);
            core.setRegister(1, 0x2000); // além dos 4096 bytes mapeados -> endereço não mapeado
            core.setRegister(2, 4);
            int cpsrBefore = core.cpsr().get();
            run(core, raw); // não deve lançar exceção (endereço nunca é acessado)
            assertEquals(0xCAFEBABE, core.register(0), () -> "0x" + Integer.toHexString(raw) + " não deve tocar r0");
            assertEquals(0x2000, core.register(1), () -> "0x" + Integer.toHexString(raw) + " não deve tocar a base");
            assertEquals(cpsrBefore, core.cpsr().get(), () -> "0x" + Integer.toHexString(raw) + " não deve tocar CPSR");
        }
    }

    @Test
    void pldIsUndefinedWithoutPreloadHintsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xF551_F010); // PLD [r1,#0x10]

        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }
}
