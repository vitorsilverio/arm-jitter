package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen.EmptyAsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockEquivalenceHarnessTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    @Test
    void interpretedEmitterMatchesItselfOnLiftedAluBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);
        InterpretedCodeEmitter emitter = new InterpretedCodeEmitter();

        assertDoesNotThrow(() -> harness.assertEquivalent(
                emitter,
                emitter,
                block,
                EquivalenceTestSupport.independentPair(memory, core -> { })));
    }

    @Test
    void reportsMismatchWhenCandidateDoesNotExecuteBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE7F0_00F0);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertThrows(
                EquivalenceMismatchException.class,
                () -> harness.assertEquivalent(
                        new InterpretedCodeEmitter(),
                        new EmptyAsmCodeEmitter(),
                        block,
                        EquivalenceTestSupport.independentPair(memory, core -> { })));
    }
}
