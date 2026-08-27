package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
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

    // ── Espaço incondicional (E6) ───────────────────────────────────────────────────────────

    @Test
    void unrecognizedUnconditionalSpaceEncodingIsUnimplementedNotDecodedAsConditional() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // 0xF2000000: um VHADD de NEON (cond==0b1111, espaço incondicional). Antes da E6 este
        // encoding caía no dispatch ALU genérico e virava AND com condição AL (achado real da
        // tabela de cobertura de ISA, E5) — NEON não é implementado, então tem que virar
        // UNIMPLEMENTED, nunca outra instrução.
        memory.put32(0, 0xF200_0000);

        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.AND, instruction.kind());
    }

    @Test
    void unconditionalSpaceCarveOutsStillDecodeAfterE6() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xFA00_0001); // BLX imediato (cond=1111)

        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0);

        assertEquals(InstructionKind.BRANCH_EXCHANGE, instruction.kind());
        assertTrue(instruction.link());
    }

    // ── HVC (B9.8.2, ARM DDI 0406C A8.8.65) ─────────────────────────────────────────────────

    @Test
    void decodesArmHvcWithHypervisorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE141_2374); // HVC #0x1234 (encoding real, arm-none-eabi-as -march=armv7ve)

        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.HVC, instruction.kind());
        assertEquals(Condition.AL, instruction.condition());
        assertEquals(0x1234, instruction.immediate());
    }

    @Test
    void armHvcIsUnimplementedWithoutHypervisorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE141_2374);

        // Default do construtor sem argumentos é ARMV4T — sem ArmFeature#HYPERVISOR_CALL, o
        // espaço é real (não UDF fixo) mas não implementado nesta arquitetura (G8).
        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.HVC, instruction.kind());
    }

    @Test
    void armHvcEntersHypModeWithElrHypAsReturnAddressAndLeavesLrUntouched() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        // LR_usr/LR_sys (compartilhado com Hyp mode, B9.8.1) — DIFERENTE do LR bancado de
        // SUPERVISOR, então grava pelo banco SYSTEM antes de voltar ao modo ativo real do HVC.
        core.switchMode(CpuMode.SYSTEM);
        core.setRegister(14, 0xCAFE);
        core.switchMode(CpuMode.SUPERVISOR);
        core.memory().write32(0, 0xE141_2374); // HVC #0x1234, modo inicial SUPERVISOR (reset)

        core.step();

        assertEquals(CpuMode.HYP, core.mode());
        assertEquals(0x14, core.programCounter());
        assertEquals(4, core.elrHyp()); // endereço da PRÓXIMA instrução, mesma convenção de SWI
        assertEquals(0xCAFE, core.register(14), "LR é o LR_usr/LR_sys compartilhado, não bancado por Hyp");
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void armHvcIsUndefinedWhenExecutedInUserMode() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.switchMode(CpuMode.USER);
        core.memory().write32(0, 0xE141_2374); // HVC #0x1234

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertNotEquals(CpuMode.HYP, core.mode());
    }

    @Test
    void armHvcvsIsSkippedWhenOverflowFlagIsClear() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.cpsr().setNzcv(false, false, false, false); // V=0 -> cond VS falsa
        core.memory().write32(0, 0x6140_0070); // HVCVS #0

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode(), "cond falsa não deve entrar em Hyp mode");
        assertEquals(4, core.programCounter(), "Cycle/Fetch avançam o PC mesmo com guard falho (G4)");
    }
}
