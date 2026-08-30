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

    // ── SMC (B9.8.3, ARM DDI 0406C A8.8.20) ─────────────────────────────────────────────────

    @Test
    void decodesArmSmcWithSecureMonitorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_0075); // SMC #5 (encoding real, arm-none-eabi-as -march=armv7ve)

        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.SMC, instruction.kind());
        assertEquals(Condition.AL, instruction.condition());
        assertEquals(5, instruction.immediate());
    }

    @Test
    void armSmcIsUnimplementedWithoutSecureMonitorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_0075);

        // Default do construtor sem argumentos é ARMV4T — sem ArmFeature#SECURE_MONITOR_CALL, o
        // espaço é real (não UDF fixo) mas não implementado nesta arquitetura (G8).
        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.SMC, instruction.kind());
    }

    @Test
    void armSmcIsAlreadyDecodedInArm11MpcoreDueToOlderGateThanHvc() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_0075);

        // SMC exige só ARMv6K (ENABLE_ARCH_6K), mais antigo que HVC (ENABLE_ARCH_7) — o preset
        // ARM11_MPCORE (3DS) já decodifica SMC de verdade, ao contrário de HVC.
        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARM11_MPCORE).decode(memory, 0);

        assertEquals(InstructionKind.SMC, instruction.kind());
    }

    @Test
    void armSmcEntersMonitorModeWithLrMonAsReturnAddress() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.memory().write32(0, 0xE160_0075); // SMC #5, modo inicial SUPERVISOR (reset)

        core.step();

        assertEquals(CpuMode.MONITOR, core.mode());
        assertEquals(0x08, core.programCounter());
        assertEquals(4, core.register(14), "LR_mon recebe o retorno, banco PRÓPRIO (B9.8.1)");
    }

    @Test
    void armSmcIsUndefinedWhenExecutedInUserMode() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.switchMode(CpuMode.USER);
        core.memory().write32(0, 0xE160_0075); // SMC #5

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertNotEquals(CpuMode.MONITOR, core.mode());
    }

    // ── HLT (B22.1, ARM DDI 0487 — Halting debug, ARMv8-A) ──────────────────────────────────
    // `cccc 0001 0000 imm12 0111 imm4`. Mesmo espaço "Miscellaneous instructions" de BKPT
    // (`0001 0010`)/HVC (`0001 0100`)/SMC (`0001 0110`) — difere só em bits[22:21] (`00`).
    // Nenhum preset atual declara ArmFeature#HALT; o encoding é RECONHECIDO e recusado (G8, em
    // vez do misdecode como ALU `TST` que o espaço tinha antes).

    private static final ArmArchitecture ARM_WITH_HALT =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv8A32-TestHalt", ArmFeature.HALT);

    @Test
    void decodesArmHltWithHaltFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE101_2374); // HLT #0x1234 (imm16 = bits[19:8]<<4 | bits[3:0])

        DecodedInstruction instruction = new ArmDecoder(ARM_WITH_HALT).decode(memory, 0);

        assertEquals(InstructionKind.HALT, instruction.kind());
        assertEquals(Condition.AL, instruction.condition());
        assertEquals(0x1234, instruction.immediate());
    }

    @Test
    void armHltDecodeAcceptsAnyConditionField() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0x1101_2374); // cond=NE sobre o mesmo padrão (UNPREDICTABLE no HW, decode aceita)

        DecodedInstruction instruction = new ArmDecoder(ARM_WITH_HALT).decode(memory, 0);

        assertEquals(InstructionKind.HALT, instruction.kind());
        assertEquals(Condition.NE, instruction.condition());
        assertEquals(0x1234, instruction.immediate());
    }

    @Test
    void armHltIsUnimplementedOnEveryCurrentPreset() {
        for (ArmArchitecture architecture : new ArmArchitecture[]{
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K,
                ArmArchitecture.ARM11_MPCORE, ArmArchitecture.ARMV7A}) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put32(0, 0xE101_2374);

            DecodedInstruction instruction = new ArmDecoder(architecture).decode(memory, 0);

            assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind(),
                    "HLT deve ser recusada explicitamente em " + architecture.name());
        }
    }

    @Test
    void armHltDoesNotDisturbHvcOrSmcNeighbours() {
        TestAddressSpace hvc = new TestAddressSpace(16);
        hvc.put32(0, 0xE141_2374); // HVC #0x1234
        assertEquals(InstructionKind.HVC, new ArmDecoder(ARM_WITH_HALT).decode(hvc, 0).kind());

        TestAddressSpace smc = new TestAddressSpace(16);
        smc.put32(0, 0xE160_0075); // SMC #5
        assertEquals(InstructionKind.SMC, new ArmDecoder(ARM_WITH_HALT).decode(smc, 0).kind());

        // BKPT A32 (`0001 0010`) não deve ser engolido pela máscara de HLT.
        TestAddressSpace bkpt = new TestAddressSpace(16);
        bkpt.put32(0, 0xE120_0070);
        assertNotEquals(InstructionKind.HALT, new ArmDecoder(ARM_WITH_HALT).decode(bkpt, 0).kind());
    }

    // ── ERET (B9.8.4, A32, ARM DDI 0406C B9.3.3) ────────────────────────────────────────────

    // B22.5: ArmFeature#VIRTUALIZATION_EXTENSIONS agora É habilitada em ARMV7A (era incoerência
    // ter HYPERVISOR_CALL sem ela). Este alias continua == ARMV7A (extending é idempotente para
    // feature já presente) e é mantido só para não reescrever as dezenas de referências abaixo.
    private static final ArmArchitecture ARMV7VE = ArmArchitecture.extending(
            ArmArchitecture.ARMV7A, "ARMv7VE", ArmFeature.VIRTUALIZATION_EXTENSIONS);
    // Preset pré-v7 que genuinamente NÃO tem V7VE — usado pelos testes de gating G8 (o espaço
    // ERET/MRS_bank/MSR_bank é real, tem que virar UNIMPLEMENTED explícito sem a feature).
    private static final ArmArchitecture WITHOUT_VIRTUALIZATION = ArmArchitecture.ARMV6K;

    @Test
    void decodesArmEretWithVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_006E); // ERET (encoding real, arm-none-eabi-as -march=armv7ve)

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.ERET, instruction.kind());
        assertEquals(Condition.AL, instruction.condition());
    }

    @Test
    void armEretIsUnimplementedWithoutVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_006E);

        // ARMv6K não tem VIRTUALIZATION_EXTENSIONS (posterior) — o espaço é real (não UDF fixo),
        // só não implementado nesta arquitetura (G8).
        DecodedInstruction instruction = new ArmDecoder(WITHOUT_VIRTUALIZATION).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.ERET, instruction.kind());
    }

    @Test
    void armEretDoesNotCollideWithSmcEncoding() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE160_0075); // SMC #5 — mesmo prefixo bits[27:8], bit4 diferente de ERET

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.SMC, instruction.kind());
        assertNotEquals(InstructionKind.ERET, instruction.kind());
    }

    // ── B22.5: o PRESET público ARMV7A (não só o alias de teste) decodifica os 3 encodings ──────

    @Test
    void armv7aPresetDecodesEretMrsBankAndMsrBank() {
        TestAddressSpace memory = new TestAddressSpace(16);
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV7A);

        memory.put32(0, 0xE160_006E); // ERET
        assertEquals(InstructionKind.ERET, decoder.decode(memory, 0).kind());

        memory.put32(0, 0xE100_0200); // MRS r0, R8_usr
        assertEquals(InstructionKind.MRS_BANK, decoder.decode(memory, 0).kind());

        memory.put32(0, 0xE125_F200); // MSR SP_usr, r0
        assertEquals(InstructionKind.MSR_BANK, decoder.decode(memory, 0).kind());
    }

    @Test
    void armv7aPresetExecutesEretReturningFromHypMode() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.switchMode(CpuMode.HYP);
        core.setElrHyp(0x9000);
        core.setSpsr(CpuMode.HYP, (core.cpsr().get() & ~0x1F) | CpuMode.SUPERVISOR.bits());
        core.memory().write32(0, 0xE160_006E); // ERET

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertEquals(0x9000, core.programCounter());
    }

    @Test
    void armv7aPresetExecutesMrsBankAndMsrBankAgainstAnotherModesBank() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.switchMode(CpuMode.SYSTEM); // SP_usr/SP_sys compartilhado
        core.setRegister(13, 0x1234);
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(1, 0x5678);
        core.memory().write32(0, 0xE105_0200); // MRS r0, SP_usr
        core.memory().write32(4, 0xE125_F201); // MSR SP_usr, r1

        core.step(); // MRS
        assertEquals(0x1234, core.register(0));

        core.step(); // MSR
        assertEquals(0x5678, core.bankedRegister(CpuMode.SYSTEM, 13));
        assertEquals(CpuMode.SUPERVISOR, core.mode(), "acesso bancado não troca o modo ativo");
    }

    @Test
    void armEretInHypModeReturnsViaElrHypNotLr() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ARMV7VE);
        // Entra em Hyp mode "manualmente" (sem HVC) só para preparar o cenário: ELR_hyp e
        // SPSR_hyp com valores conhecidos, LR_usr/LR_sys (compartilhado) com um valor DIFERENTE
        // para provar que ERET em Hyp mode ignora LR.
        core.switchMode(CpuMode.SYSTEM);
        core.setRegister(14, 0xBAD);
        core.switchMode(CpuMode.HYP);
        core.setElrHyp(0x9000);
        core.setSpsr(CpuMode.HYP, (core.cpsr().get() & ~0x1F) | CpuMode.SUPERVISOR.bits());
        core.memory().write32(0, 0xE160_006E); // ERET

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode(), "CPSR restaurado a partir de SPSR_hyp");
        assertEquals(0x9000, core.programCounter(), "PC vem de ELR_hyp, não de LR");
    }

    @Test
    void armEretInSupervisorModeReturnsViaLrSvc() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ARMV7VE);
        core.setRegister(14, 0x9000); // LR_svc
        core.setSpsr(CpuMode.SUPERVISOR, (core.cpsr().get() & ~0x1F) | CpuMode.SYSTEM.bits());
        core.memory().write32(0, 0xE160_006E); // ERET, modo inicial SUPERVISOR (reset)

        core.step();

        assertEquals(CpuMode.SYSTEM, core.mode(), "CPSR restaurado a partir de SPSR_svc");
        assertEquals(0x9000, core.programCounter(), "PC vem de LR do banco ativo (SVC)");
    }

    @Test
    void armEretIsUndefinedWhenExecutedInUserMode() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ARMV7VE);
        core.switchMode(CpuMode.USER);
        core.memory().write32(0, 0xE160_006E); // ERET

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void armEretvsIsSkippedWhenOverflowFlagIsClear() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ARMV7VE);
        core.cpsr().setNzcv(false, false, false, false); // V=0 -> cond VS falsa
        core.memory().write32(0, 0x6160_006E); // ERETVS

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode(), "cond falsa não deve alterar o modo");
        assertEquals(4, core.programCounter(), "Cycle/Fetch avançam o PC mesmo com guard falho (G4)");
    }

    @Test
    void armSmcvsIsSkippedWhenOverflowFlagIsClear() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.cpsr().setNzcv(false, false, false, false); // V=0 -> cond VS falsa
        core.memory().write32(0, 0x6160_0070); // SMCVS #0

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode(), "cond falsa não deve entrar em Monitor mode");
        assertEquals(4, core.programCounter(), "Cycle/Fetch avançam o PC mesmo com guard falho (G4)");
    }

    // ── MRS/MSR bancado (B9.8.5, A32, ARM DDI 0406C A8.8.64/A8.8.66) ────────────────────────

    private static ArmCore newVirtualizationCore() {
        return new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ARMV7VE);
    }

    @Test
    void decodesArmMrsBankWithVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE100_0200); // MRS r0, R8_usr (encoding real, arm-none-eabi-as -march=armv7ve)

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.MRS_BANK, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
    }

    @Test
    void armMrsBankIsUnimplementedWithoutVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE100_0200);

        DecodedInstruction instruction = new ArmDecoder(WITHOUT_VIRTUALIZATION).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.MRS_BANK, instruction.kind());
    }

    @Test
    void armMrsBankWithUnallocatedSysmIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // sysm=0x7 (entre r14_usr=0x6 e r8_fiq=0x8): nenhuma entrada real na tabela ARM DDI 0487 F5.2.3.
        memory.put32(0, 0xE107_0200);

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void armMrsBankWithRdEqualToProgramCounterIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE100_F200); // MRS pc, R8_usr — UNPREDICTABLE

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void armMrsBankReadsGeneralRegisterOfAnotherModeWithoutChangingActiveMode() {
        ArmCore core = newVirtualizationCore();
        core.switchMode(CpuMode.SYSTEM); // SP_usr/SP_sys compartilhado
        core.setRegister(13, 0x1234);
        core.switchMode(CpuMode.SUPERVISOR); // volta ao modo de reset
        core.memory().write32(0, 0xE105_0200); // MRS r0, SP_usr

        core.step();

        assertEquals(0x1234, core.register(0));
        assertEquals(CpuMode.SUPERVISOR, core.mode(), "leitura bancada não deve trocar o modo ativo");
    }

    @Test
    void armMrsBankReadsElrHypDistinctFromLr() {
        ArmCore core = newVirtualizationCore();
        core.setElrHyp(0x9000);
        core.setRegister(14, 0xBAD); // LR_usr/sys compartilhado — ELR_hyp é registrador à parte
        core.memory().write32(0, 0xE10E_0300); // MRS r0, ELR_hyp

        core.step();

        assertEquals(0x9000, core.register(0), "ELR_hyp não é o mesmo registrador que LR");
    }

    @Test
    void armMrsBankReadsSpsrOfAnotherModeWithoutTouchingActiveSpsr() {
        ArmCore core = newVirtualizationCore();
        core.setSpsr(CpuMode.FIQ, 0xABCD);
        core.memory().write32(0, 0xE14E_0200); // MRS r0, SPSR_fiq

        core.step();

        assertEquals(0xABCD, core.register(0));
    }

    @Test
    void armMrsBankIsUndefinedWhenExecutedInUserMode() {
        ArmCore core = newVirtualizationCore();
        core.switchMode(CpuMode.USER);
        core.memory().write32(0, 0xE100_0200); // MRS r0, R8_usr

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void decodesArmMsrBankWithVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE125_F200); // MSR SP_usr, r0

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.MSR_BANK, instruction.kind());
        assertEquals(0, instruction.sourceRegister());
    }

    @Test
    void armMsrBankIsUnimplementedWithoutVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE125_F200);

        DecodedInstruction instruction = new ArmDecoder(WITHOUT_VIRTUALIZATION).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.MSR_BANK, instruction.kind());
    }

    @Test
    void armMsrBankWithRnEqualToProgramCounterIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE125_F20F); // MSR SP_usr, pc — UNPREDICTABLE

        DecodedInstruction instruction = new ArmDecoder(ARMV7VE).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void armMsrBankWritesGeneralRegisterOfAnotherModeWithoutChangingActiveMode() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0x1234);
        core.memory().write32(0, 0xE125_F200); // MSR SP_usr, r0

        core.step();

        core.switchMode(CpuMode.SYSTEM);
        assertEquals(0x1234, core.register(13));
        assertEquals(CpuMode.SYSTEM, core.mode());
    }

    @Test
    void armMsrBankWritesElrHyp() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0x9000);
        core.memory().write32(0, 0xE12E_F300); // MSR ELR_hyp, r0

        core.step();

        assertEquals(0x9000, core.elrHyp());
    }

    @Test
    void armMsrBankWritesSpsrOfAnotherMode() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0xBEEF);
        core.memory().write32(0, 0xE16E_F300); // MSR SPSR_hyp, r0

        core.step();

        assertEquals(0xBEEF, core.spsr(CpuMode.HYP));
    }

    @Test
    void armMsrBankIsUndefinedWhenExecutedInUserMode() {
        ArmCore core = newVirtualizationCore();
        core.switchMode(CpuMode.USER);
        core.memory().write32(0, 0xE125_F200); // MSR SP_usr, r0

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    // ── LDRxT/STRxT (B9.9): P=0 (post-indexed) com W=1 é o encoding "unprivileged" ──────────

    /// Monta o word/byte single-data-transfer clássico (ARM DDI 0406C A5.2.2): `bits[27:26]=01`,
    /// `I`=offset por registrador (sempre `false` aqui, só imediato), `P`/`U`/`B`/`W`/`L` explícitos.
    private static int singleDataTransfer(boolean preIndexed, boolean up, boolean byteAccess, boolean writeback,
            boolean load, int rn, int rd, int imm12) {
        return (0xE << 28) | (0b01 << 26)
                | ((preIndexed ? 1 : 0) << 24) | ((up ? 1 : 0) << 23) | ((byteAccess ? 1 : 0) << 22)
                | ((writeback ? 1 : 0) << 21) | ((load ? 1 : 0) << 20)
                | (rn << 16) | (rd << 12) | (imm12 & 0xFFF);
    }

    /// Monta o halfword/signed "extra load/store" (ARM DDI 0406C A5.2.8), forma imediata
    /// (`bits[22]=1`): mesmos `P`/`U`/`W`/`L` explícitos do bloco word/byte acima.
    private static int extraLoadStoreImmediate(boolean preIndexed, boolean up, boolean writeback, boolean load,
            int rn, int rd, int transferKind, int imm8) {
        int immHigh = (imm8 >>> 4) & 0xF;
        int immLow = imm8 & 0xF;
        return (0xE << 28) | (0b000 << 25)
                | ((preIndexed ? 1 : 0) << 24) | ((up ? 1 : 0) << 23) | (1 << 22)
                | ((writeback ? 1 : 0) << 21) | ((load ? 1 : 0) << 20)
                | (rn << 16) | (rd << 12) | (immHigh << 8) | (1 << 7) | (transferKind << 5) | (1 << 4) | immLow;
    }

    @Test
    void armLdrtWordIsUnprivilegedPostIndexedLoad() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, singleDataTransfer(false, true, false, true, true, 1, 0, 8)); // LDRT r0,[r1],#8

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertTrue(instruction.unprivileged());
        assertTrue(instruction.writeback());
        assertTrue(instruction.postIndexed());
        assertEquals(4, instruction.accessSizeBytes());
    }

    @Test
    void armStrbtByteIsUnprivilegedPostIndexedStore() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, singleDataTransfer(false, true, true, true, false, 1, 0, 4)); // STRBT r0,[r1],#4

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.STORE, instruction.kind());
        assertTrue(instruction.unprivileged());
        assertEquals(1, instruction.accessSizeBytes());
    }

    @Test
    void armPlainPostIndexedStoreIsNotUnprivileged() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, singleDataTransfer(false, true, false, false, false, 1, 0, 4)); // STR r0,[r1],#4 (W=0)

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.STORE, instruction.kind());
        assertFalse(instruction.unprivileged());
        assertTrue(instruction.writeback(), "post-index sempre escreve de volta, com ou sem W");
    }

    @Test
    void armPreIndexedWritebackStoreIsNotUnprivileged() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, singleDataTransfer(true, true, false, true, false, 1, 0, 4)); // STR r0,[r1,#4]!

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.STORE, instruction.kind());
        assertFalse(instruction.unprivileged());
        assertTrue(instruction.writeback());
        assertFalse(instruction.postIndexed());
    }

    @Test
    void armLdrhtHalfwordIsUnprivilegedPostIndexedLoad() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // LDRHT r0,[r1],#8: transferKind=0b01 (halfword), L=1.
        memory.put32(0, extraLoadStoreImmediate(false, true, true, true, 1, 0, 0b01, 8));

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertTrue(instruction.unprivileged());
        assertEquals(2, instruction.accessSizeBytes());
        assertFalse(instruction.signedAccess());
    }

    @Test
    void armLdrsbtIsUnprivilegedPostIndexedSignedLoad() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // LDRSBT r0,[r1],#4: transferKind=0b10 (signed byte), L=1.
        memory.put32(0, extraLoadStoreImmediate(false, true, true, true, 1, 0, 0b10, 4));

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertTrue(instruction.unprivileged());
        assertEquals(1, instruction.accessSizeBytes());
        assertTrue(instruction.signedAccess());
    }

    @Test
    void armStrhtIsUnprivilegedPostIndexedStore() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // STRHT r0,[r1],#4: transferKind=0b01 (halfword), L=0.
        memory.put32(0, extraLoadStoreImmediate(false, true, true, false, 1, 0, 0b01, 4));

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.STORE, instruction.kind());
        assertTrue(instruction.unprivileged());
        assertEquals(2, instruction.accessSizeBytes());
    }

    @Test
    void armPlainPostIndexedHalfwordLoadIsNotUnprivileged() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // LDRH r0,[r1],#4 (W=0): mesmo modo de endereçamento, sem o encoding unprivileged.
        memory.put32(0, extraLoadStoreImmediate(false, true, false, true, 1, 0, 0b01, 4));

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertFalse(instruction.unprivileged());
    }
}
