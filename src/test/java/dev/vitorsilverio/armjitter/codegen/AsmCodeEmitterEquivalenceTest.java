package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.core.ArmCore;
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
        // 0xE1A00211 = MOV r0, r1, LSL r2 — ShiftedRegister src2, always unsupported.
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE1A00211);
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

    // ── Phase 5 equivalence tests ──────────────────────────────────────────────

    @Test
    void loadWordMatchesInterpreted() {
        // LDR r1, [r0] = 0xE5901000
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE5901000);
        memory.put32(0x40, 0xDEADBEEF);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x40)));
    }

    @Test
    void storeWordMatchesInterpreted() {
        // STR r0, [r1] = 0xE5810000
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE5810000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x12345678);
                    core.setRegister(1, 0x40);
                }));
    }

    @Test
    void branchMatchesInterpreted() {
        // B #0 = 0xEA000000 — target = PC + 8 + 0 = 8 (executing at address 0, PC = 8)
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEA000000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void multiplyMatchesInterpreted() {
        // MUL r0, r1, r2 = 0xE0000291  (r0 = r1 * r2)
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE0000291);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(1, 7);
                    core.setRegister(2, 6);
                }));
    }

    @Test
    void branchExchangeMatchesInterpreted() {
        // BX r0 = 0xE12FFF10
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12FFF10);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x100)));
    }

    @Test
    void ldmMatchesInterpreted() {
        // LDMIA r0, {r1, r2} = 0xE8900006
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8900006);
        memory.put32(0x40, 0x11111111);
        memory.put32(0x44, 0x22222222);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x40)));
    }
}
