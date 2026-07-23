package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Testes mínimos da B7.2: layout do frame de exceção, ida-e-volta Thread(PSP)/handler(MSP) com
/// realinhamento, handler→handler encadeado, `EXC_RETURN` inválido, preservação de ITSTATE e
/// equivalência interpretado×ASM (G1) — ver "Testes mínimos" em
/// `tasks/trilha-b-arquiteturas/b7.2-mprofile-exception-model.md`.
class MProfileExceptionModelTest {
    private static final int SVCALL_VECTOR_ADDRESS = 4 * MProfileException.SVCALL.number();
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int SVCALL_HANDLER_PC = 0x1000;
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int MEMORY_SIZE = 4096;

    private static TestAddressSpace newMemoryWithVectors() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        memory.put32(SVCALL_VECTOR_ADDRESS, SVCALL_HANDLER_PC | 1); // bit0=1: Thumb
        memory.put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1);
        return memory;
    }

    private static ArmCore newCore(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty());
    }

    /// Teste mínimo #1: layout do frame de uma SVC em Thread/MSP.
    @Test
    void frameLayoutOnSvcFromThreadMsp() {
        TestAddressSpace memory = newMemoryWithVectors();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);

        int sp = 0x200; // já alinhado a 8
        core.setRegister(13, sp);
        core.setRegister(0, 0x10);
        core.setRegister(1, 0x11);
        core.setRegister(2, 0x12);
        core.setRegister(3, 0x13);
        core.setRegister(12, 0x1C);
        core.setRegister(14, 0xBADC0DE);
        core.setProgramCounter(0x40); // "endereço da instrução seguinte" já calculado pelo chamador

        model.enterException(core, MProfileException.SVCALL);

        int frame = sp - MProfileExceptionModel.EXCEPTION_FRAME_SIZE_BYTES;
        assertEquals(frame, core.register(13));
        assertEquals(0x10, memory.read32(frame));
        assertEquals(0x11, memory.read32(frame + 4));
        assertEquals(0x12, memory.read32(frame + 8));
        assertEquals(0x13, memory.read32(frame + 12));
        assertEquals(0x1C, memory.read32(frame + 16));
        assertEquals(0xBADC0DE, memory.read32(frame + 20));
        assertEquals(0x40, memory.read32(frame + 24));
        assertEquals(MProfileExceptionModel.EXC_RETURN_THREAD_TO_HANDLER_MSP, core.register(14));
        assertEquals(MProfileException.SVCALL.number(), model.currentException());
        assertEquals(SVCALL_HANDLER_PC, core.programCounter());
    }

    /// Teste mínimo #2: ida-e-volta Thread(PSP) → SVC → handler roda em MSP → `BX LR` → estado
    /// 100% restaurado, incluindo o caso com SP desalinhado de 4 (bit 9 do frame).
    @Test
    void roundTripFromThreadPspWithMisalignedStack() {
        TestAddressSpace memory = newMemoryWithVectors();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);

        int msp = 0x300;
        int psp = 0x204; // desalinhado de 4 (bit 2 setado)
        core.setRegister(13, msp);
        model.setProcessStackPointer(psp);
        model.writeControl(core, MProfileExceptionModel.CONTROL_SPSEL_BIT); // Thread/PSP ativo
        assertEquals(psp, core.register(13), "writeControl deveria trocar o SP ativo p/ PSP");

        core.setRegister(0, 1);
        core.setRegister(1, 2);
        core.setRegister(2, 3);
        core.setRegister(3, 4);
        core.setRegister(12, 5);
        core.setRegister(14, 0x11111111);
        core.cpsr().setNzcv(true, false, true, false);

        IrBlock svcBlock = IrBlock.builder(0x80)
                .add(new IrOp.Swi(0, Condition.AL))
                .endPc(0x82)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(svcBlock).execute(core);

        assertEquals(SVCALL_HANDLER_PC, core.programCounter());
        // O frame foi empilhado no PSP (SP ativo na entrada); o handler roda no MSP como estava
        // (nada o decrementou — só o PSP, agora inativo, ficou apontando para o frame).
        assertEquals(msp, core.register(13), "handler roda em MSP, mesmo tendo entrado via PSP");
        assertEquals(MProfileExceptionModel.EXC_RETURN_THREAD_TO_HANDLER_PSP, core.register(14));

        // Handler termina com BX LR.
        IrBlock returnBlock = IrBlock.builder(SVCALL_HANDLER_PC)
                .add(new IrOp.BranchExchange(14, -1, false, 0, Condition.AL))
                .endPc(SVCALL_HANDLER_PC + 2)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(core);

        assertEquals(0x82, core.programCounter(), "retorno restaura o PC empilhado (endereço seguinte à SVC)");
        assertEquals(psp, core.register(13), "retorno restaura o SP desalinhado original (bit 9 desfeito)");
        assertEquals(1, core.register(0));
        assertEquals(2, core.register(1));
        assertEquals(3, core.register(2));
        assertEquals(4, core.register(3));
        assertEquals(5, core.register(12));
        assertEquals(0x11111111, core.register(14));
        assertTrue(core.cpsr().negative());
        assertFalse(core.cpsr().zero());
        assertTrue(core.cpsr().carry());
        assertFalse(core.cpsr().overflow());
        assertEquals(0, model.currentException(), "de volta a Thread mode");
        assertTrue(model.spsel(), "CONTROL.SPSEL restaurado para 1 (PSP)");
    }

    /// Teste mínimo #3: handler→handler (`0xFFFFFFF1`) e retorno em cadeia.
    @Test
    void handlerToHandlerNestedEntryAndReturn() {
        TestAddressSpace memory = newMemoryWithVectors();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.setRegister(13, 0x300);

        model.enterException(core, MProfileException.SVCALL);
        int spAfterFirstEntry = core.register(13);
        assertEquals(MProfileException.SVCALL.number(), model.currentException());

        core.setProgramCounter(0x1010); // "instrução seguinte" dentro do handler
        model.enterException(core, MProfileException.USAGE_FAULT);

        assertEquals(MProfileExceptionModel.EXC_RETURN_HANDLER_TO_HANDLER_MSP, core.register(14));
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(USAGE_FAULT_HANDLER_PC, core.programCounter());
        int spAfterSecondEntry = core.register(13);
        assertEquals(spAfterFirstEntry - MProfileExceptionModel.EXCEPTION_FRAME_SIZE_BYTES, spAfterSecondEntry);

        // Retorno em cadeia: primeiro sai do usage fault (volta ao SVCALL handler), depois do SVCALL.
        IrBlock returnBlock = IrBlock.builder(0)
                .add(new IrOp.BranchExchange(14, -1, false, 0, Condition.AL))
                .endPc(2)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(core);
        assertEquals(0x1010, core.programCounter());
        assertEquals(MProfileException.SVCALL.number(), model.currentException());
        assertEquals(spAfterFirstEntry, core.register(13));

        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(core);
        assertEquals(0, model.currentException());
        assertEquals(0x300, core.register(13));
    }

    /// Teste mínimo #4: `EXC_RETURN` inválido (ex. `0xFFFFFFF3`) → `UNDEFINED` controlado
    /// (`UsageFault`, já que o perfil M não tem `UNDEFINED` clássico).
    @Test
    void invalidExcReturnEntersUsageFault() {
        TestAddressSpace memory = newMemoryWithVectors();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.setRegister(13, 0x300);
        model.enterException(core, MProfileException.SVCALL); // entra em Handler p/ ter frame válido

        core.setRegister(14, 0xFFFFFFF3); // EXC_RETURN inválido (não é F1/F9/FD)
        core.setProgramCounter(0x1004);

        IrBlock returnBlock = IrBlock.builder(0)
                .add(new IrOp.BranchExchange(14, -1, false, 0, Condition.AL))
                .endPc(2)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(core);

        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(USAGE_FAULT_HANDLER_PC, core.programCounter());
    }

    /// Teste mínimo #5: ITSTATE salvo no `xPSR` empilhado, zerado no handler e restaurado no retorno.
    @Test
    void itStatePreservedAcrossException() {
        TestAddressSpace memory = newMemoryWithVectors();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.setRegister(13, 0x300);
        core.setRegister(14, 0x9999);
        core.cpsr().setItState(0b1011_1000);
        core.setProgramCounter(0x40);

        model.enterException(core, MProfileException.SVCALL);
        assertEquals(0, core.cpsr().itState(), "ITSTATE zerado ao entrar no handler");

        IrBlock returnBlock = IrBlock.builder(0)
                .add(new IrOp.BranchExchange(14, -1, false, 0, Condition.AL))
                .endPc(2)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(core);

        assertEquals(0b1011_1000, core.cpsr().itState(), "ITSTATE restaurado ao retornar");
    }

    /// Teste mínimo #6 (G1): bloco com SVC + retorno rodado interpretado × ASM com o modelo M
    /// instalado nos dois — estado idêntico. Também prova que `handlesSupervisorCall()` desvia do
    /// `SwiDispatcher` de fato: o dispatcher tem um handler registrado para a SWI usada, mas
    /// nunca deveria ser chamado (o handler lançaria se fosse).
    @Test
    void interpretedAndAsmBackendsAgreeOnSvcEntryAndReturn() {
        TestAddressSpace asmMemory = newMemoryWithVectors();
        TestAddressSpace interpMemory = asmMemory.copy();

        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.register(0, (CpuState state) -> {
            throw new AssertionError("SwiDispatcher não deveria ser chamado sob M_PROFILE");
        });

        ArmCore asmCore = new ArmCore(asmMemory, dispatcher);
        ArmCore interpCore = new ArmCore(interpMemory, dispatcher);
        MProfileExceptionModel asmModel = new MProfileExceptionModel();
        MProfileExceptionModel interpModel = new MProfileExceptionModel();
        asmCore.setExceptionModel(asmModel);
        interpCore.setExceptionModel(interpModel);
        for (ArmCore core : new ArmCore[]{asmCore, interpCore}) {
            core.setRegister(13, 0x300);
            core.setRegister(0, 7);
            core.setRegister(14, 0x2222);
            core.setProgramCounter(0x40);
        }

        IrBlock svcBlock = IrBlock.builder(0x40)
                .add(new IrOp.Swi(0, Condition.AL))
                .endPc(0x42)
                .sealed();
        new AsmCodeEmitter(ArmArchitecture.ARMV4T).emit(svcBlock).execute(asmCore);
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(svcBlock).execute(interpCore);

        assertEquals(interpCore.programCounter(), asmCore.programCounter());
        assertEquals(interpCore.register(13), asmCore.register(13));
        assertEquals(interpCore.register(14), asmCore.register(14));
        assertEquals(interpModel.currentException(), asmModel.currentException());
        for (int address = 0x300 - MProfileExceptionModel.EXCEPTION_FRAME_SIZE_BYTES; address < 0x300; address += 4) {
            assertEquals(interpMemory.read32(address), asmMemory.read32(address),
                    "frame divergente no endereço 0x" + Integer.toHexString(address));
        }

        IrBlock returnBlock = IrBlock.builder(SVCALL_HANDLER_PC)
                .add(new IrOp.BranchExchange(14, -1, false, 0, Condition.AL))
                .endPc(SVCALL_HANDLER_PC + 2)
                .sealed();
        new AsmCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(asmCore);
        new InterpretedCodeEmitter(ArmArchitecture.ARMV4T).emit(returnBlock).execute(interpCore);

        assertEquals(interpCore.programCounter(), asmCore.programCounter());
        assertEquals(interpCore.register(13), asmCore.register(13));
        assertEquals(interpCore.register(0), asmCore.register(0));
        assertEquals(interpModel.currentException(), asmModel.currentException());
    }
}
