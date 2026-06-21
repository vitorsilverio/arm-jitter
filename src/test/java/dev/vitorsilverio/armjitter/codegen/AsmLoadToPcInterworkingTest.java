package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Regressão do bug ARMv5: o backend ASM emitia load->PC sempre com a semântica ARMv4T
/// (`loadToPcArm4`, sem interworking), então `POP {pc}` / `LDR pc` / `LDM ..,pc` em código
/// ARMv5 (ARM9) não trocavam de estado pelo bit 0 do valor carregado — o que travava o boot
/// do JUS no JIT. gbaemu (ARMv4T) nunca expôs porque seu código volta sempre para THUMB.
class AsmLoadToPcInterworkingTest {
    private static final int STACK = 0x20;
    private static final int ARM_TARGET = 0x00000100;   // bit 0 = 0 -> ARM
    private static final int THUMB_TARGET = 0x00000201; // bit 0 = 1 -> THUMB

    /// `POP {pc}` (THUMB). registerMask=0, includePc=true.
    private static final IrBlock POP_PC = IrBlock.builder(0)
            .add(new IrOp.Pop(0, true, Condition.AL))
            .endPc(2)
            .sealed();

    @Test
    void armv5PopPcInterworksToArmAndMatchesInterpreter() {
        assertPopPc(ArmArchitecture.ARMV5TE, ARM_TARGET, /*expectThumb=*/false, 0x100);
    }

    @Test
    void armv5PopPcStaysThumbForThumbTarget() {
        assertPopPc(ArmArchitecture.ARMV5TE, THUMB_TARGET, /*expectThumb=*/true, 0x200);
    }

    @Test
    void armv4tPopPcDoesNotInterwork() {
        // ARMv4T: sem interworking — fica em THUMB independentemente do bit 0.
        assertPopPc(ArmArchitecture.ARMV4T, ARM_TARGET, /*expectThumb=*/true, 0x100);
    }

    private void assertPopPc(ArmArchitecture architecture, int stackValue, boolean expectThumb, int expectedPc) {
        TestAddressSpace asmMemory = new TestAddressSpace(64);
        asmMemory.put32(STACK, stackValue);
        TestAddressSpace interpMemory = asmMemory.copy();

        ArmCore asmCore = thumbCore(asmMemory, architecture);
        ArmCore interpCore = thumbCore(interpMemory, architecture);

        new AsmCodeEmitter(architecture).emit(POP_PC).execute(asmCore);
        new InterpretedCodeEmitter(architecture).emit(POP_PC).execute(interpCore);

        // O ASM deve casar com o interpretador (oráculo) e com o esperado.
        assertEquals(expectThumb, interpCore.cpsr().isThumbMode(), "interpretador (oráculo)");
        assertEquals(expectThumb, asmCore.cpsr().isThumbMode(), "ASM deve casar o estado T do interpretador");
        assertEquals(expectedPc, interpCore.programCounter());
        assertEquals(expectedPc, asmCore.programCounter());
    }

    private static ArmCore thumbCore(TestAddressSpace memory, ArmArchitecture architecture) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, STACK); // SP -> topo da pilha
        return core;
    }
}
