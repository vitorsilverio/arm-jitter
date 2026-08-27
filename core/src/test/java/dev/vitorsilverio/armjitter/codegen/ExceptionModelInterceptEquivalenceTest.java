package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.AProfileExceptionModel;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.ExceptionModel;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Prova (B7.1, teste mínimo #2) que os 3 caminhos que escrevem PC vindo de dado —
/// `BranchExchange` (BX), `Pop` (POP {pc}) e `Load` (LDR pc) — passam pelo
/// {@link ExceptionModel#interceptsBranch}/{@link ExceptionModel#branchIntercepted} instalado
/// via {@link ArmCore#setExceptionModel}, nos DOIS backends (interpretado e ASM), em vez de
/// saltar normalmente. Com o modelo A-profile default (nenhum outro teste deste arquivo),
/// {@code interceptsBranch} é sempre `false` e o comportamento é o de sempre — coberto pela
/// suíte existente (ex.: `AsmLoadToPcInterworkingTest`).
class ExceptionModelInterceptEquivalenceTest {
    private static final int EXC_RETURN = 0xFFFF_FFF1; // bit 0 = 1: interworkaria p/ THUMB se não interceptado
    private static final int MARKER_PC = 0x0000_2000;
    private static final int STACK = 0x20;

    /// Modelo fake: intercepta um único alvo mágico e move o PC para um marcador distinto de
    /// qualquer resultado possível de branch/load-to-pc normal (prova que o caminho normal NÃO
    /// rodou), delegando o resto (entrada de exceção) ao perfil A real.
    private static final class RecordingExceptionModel implements ExceptionModel {
        private final ExceptionModel delegate = new AProfileExceptionModel();
        boolean intercepted;

        @Override
        public void enterException(ArmCore core, ArmException exception) {
            delegate.enterException(core, exception);
        }

        @Override
        public boolean interceptsBranch(int target) {
            return target == EXC_RETURN;
        }

        @Override
        public void branchIntercepted(ArmCore core, int target) {
            intercepted = true;
            core.setProgramCounter(MARKER_PC);
        }
    }

    @Test
    void branchExchangeToInterceptedTargetCallsBranchInterceptedInBothBackends() {
        IrBlock block = IrBlock.builder(0)
                .add(new IrOp.BranchExchange(0, -1, false, 0, Condition.AL))
                .endPc(4)
                .sealed();

        assertInterceptedInBothBackends(block, ArmArchitecture.ARMV4T, core -> core.setRegister(0, EXC_RETURN));
    }

    @Test
    void popPcToInterceptedTargetCallsBranchInterceptedInBothBackends() {
        IrBlock block = IrBlock.builder(0)
                .add(new IrOp.Pop(0, true, Condition.AL))
                .endPc(2)
                .sealed();

        assertInterceptedInBothBackends(block, ArmArchitecture.ARMV5TE, core -> core.setRegister(13, STACK));
    }

    @Test
    void loadPcToInterceptedTargetCallsBranchInterceptedInBothBackends() {
        IrBlock block = IrBlock.builder(0)
                .add(new IrOp.Load(15, 0, -1, new IrOperand.Immediate(0), 4,
                        false, false, true, false, Condition.AL))
                .endPc(4)
                .sealed();

        assertInterceptedInBothBackends(block, ArmArchitecture.ARMV5TE, core -> core.setRegister(0, 0x40));
    }

    private void assertInterceptedInBothBackends(IrBlock block, ArmArchitecture architecture, java.util.function.Consumer<ArmCore> init) {
        TestAddressSpace asmMemory = new TestAddressSpace(128);
        asmMemory.put32(STACK, EXC_RETURN);
        asmMemory.put32(0x40, EXC_RETURN);
        TestAddressSpace interpMemory = asmMemory.copy();

        ArmCore asmCore = new ArmCore(asmMemory, SwiDispatcher.empty(), architecture);
        ArmCore interpCore = new ArmCore(interpMemory, SwiDispatcher.empty(), architecture);
        RecordingExceptionModel asmModel = new RecordingExceptionModel();
        RecordingExceptionModel interpModel = new RecordingExceptionModel();
        asmCore.setExceptionModel(asmModel);
        interpCore.setExceptionModel(interpModel);
        init.accept(asmCore);
        init.accept(interpCore);

        new AsmCodeEmitter(architecture).emit(block).execute(asmCore);
        new InterpretedCodeEmitter(architecture).emit(block).execute(interpCore);

        assertTrue(interpModel.intercepted, "interpretador (oráculo) deveria ter interceptado");
        assertTrue(asmModel.intercepted, "ASM deveria ter interceptado");
        assertEquals(MARKER_PC, interpCore.programCounter());
        assertEquals(MARKER_PC, asmCore.programCounter());
        assertFalse(interpCore.cpsr().isThumbMode(), "branchIntercepted não mexeu no bit T");
        assertFalse(asmCore.cpsr().isThumbMode(), "branchIntercepted não mexeu no bit T");
    }
}
