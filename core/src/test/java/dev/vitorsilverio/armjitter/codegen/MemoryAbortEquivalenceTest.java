package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.FaultingAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// B4.1.3 (RFC-SOFTMMU §3, aceite "equivalência interpretado×ASM do fluxo de abort", G1): o mesmo
/// bloco IR (lifted de uma instrução real, exatamente como o runtime faz) roda pelo
/// {@link InterpretedCodeEmitter} (oráculo, via {@link BlockEquivalenceTest}) e pelo
/// {@link AsmCodeEmitter} (JIT nativo) sobre cores independentes com o MESMO
/// {@link FaultingAddressSpace} — `CpuSnapshot#assertEqualTo` exige que os dois terminem no
/// MESMO estado observável (modo, registradores, PC) depois do abort no meio da instrução.
class MemoryAbortEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter();

    private static FaultingAddressSpace faultingCopy(TestAddressSpace physical, int faultAddress, MemoryAccessType type) {
        FaultingAddressSpace memory = new FaultingAddressSpace(physical.copy());
        memory.faultOn(faultAddress, type);
        return memory;
    }

    @Test
    void dataAbortOnLoadMatchesBetweenInterpretedAndNativeAsm() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0, 0xE591_0000); // LDR r0, [r1]
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = new ArmCore(faultingCopy(physical, 0x1000, MemoryAccessType.DATA_READ), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(faultingCopy(physical, 0x1000, MemoryAccessType.DATA_READ), SwiDispatcher.empty());
            reference.setRegister(1, 0x1000);
            candidate.setRegister(1, 0x1000);
            reference.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
            candidate.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
            return new EquivalencePair(reference, candidate);
        });
    }

    @Test
    void ldmAbortingMidTransferMatchesBetweenInterpretedAndNativeAsm() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0, 0xE8B4_0007); // LDMIA r4!, {r0,r1,r2}
        physical.write32(64, 0x1111);
        physical.write32(68, 0x2222);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = new ArmCore(faultingCopy(physical, 72, MemoryAccessType.DATA_READ), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(faultingCopy(physical, 72, MemoryAccessType.DATA_READ), SwiDispatcher.empty());
            reference.setRegister(4, 64);
            candidate.setRegister(4, 64);
            return new EquivalencePair(reference, candidate);
        });
    }
}
