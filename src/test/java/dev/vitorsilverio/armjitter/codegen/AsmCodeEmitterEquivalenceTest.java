package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsmCodeEmitterEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter();

    @Test
    void movAddBlockMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        memory.put32(8, 0xE7F0_00F0);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void subAndCmpBlockMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0014);
        memory.put32(4, 0xE240_0004);
        memory.put32(8, 0xE200_0003);
        memory.put32(12, 0xE350_000A);
        memory.put32(16, 0xE7F0_00F0);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void fallsBackToInterpretedForUnsupportedBlocks() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xEA00_0000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        assertFalse(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void reportsJvmBackendAndSupportedOpcodes() {
        assertEquals(CodegenBackend.JVM_BYTECODE, asmEmitter.backend());
        assertTrue(AsmCodeEmitter.supportedAluOpcodes().contains(IrOpCode.MOV));
        assertTrue(AsmCodeEmitter.supportedAluOpcodes().contains(IrOpCode.CMP));
    }
}
