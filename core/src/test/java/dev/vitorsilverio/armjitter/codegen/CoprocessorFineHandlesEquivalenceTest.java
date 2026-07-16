package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B4.0.4.1: prova que a emissão ASM nativa de {@link dev.vitorsilverio.armjitter.ir.IrOp.Coprocessor}
/// (native desde sempre, ver {@code AsmNativePolicy}) consulta o mesmo predicado fino de
/// {@link CoprocessorBus#handles(int, int, int, int, int)} que o interpretado — um bus que atende
/// CP15 (grosso) mas só reivindica um registrador específico (fino) precisa produzir o MESMO
/// estado final (registrador atualizado OU entrada Undefined) nos dois backends (invariante G1).
class CoprocessorFineHandlesEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV5TE);

    @Test
    void claimedRegisterIsNativeAndMatchesInterpreted() {
        // MRC p15, 0, r0, c13, c0, 3 — o único registrador que o bus fake reivindica.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xEE1D0F70);
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV5TE), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "IrOp.Coprocessor é nativo desde sempre");
        harness.assertEquivalent(referenceEmitter, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setCoprocessorBus(new PartialCp15())));
    }

    @Test
    void unclaimedRegisterUndefinesIdenticallyOnBothBackends() {
        // MCR p15, 0, r1, c9, c1, 0 — o bus atende CP15 (grosso), mas não reivindica c9 (fino).
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xEE091F11);
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV5TE), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "IrOp.Coprocessor é nativo desde sempre");
        harness.assertEquivalent(referenceEmitter, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setCoprocessorBus(new PartialCp15());
                    core.setRegister(1, 0xDEADBEEF);
                }));
    }

    /// CP15 fake: atende (grosso) CP15, mas só reivindica (fino) `c13,c0,3`. `read`/`write`
    /// lançam para qualquer outro registrador — provam que nenhum backend chega a chamá-los para
    /// o cenário não-reivindicado.
    private static final class PartialCp15 implements CoprocessorBus {
        @Override
        public boolean handles(int coprocessor) {
            return coprocessor == 15;
        }

        @Override
        public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            return coprocessor == 15 && crn == 13 && crm == 0 && opcode2 == 3;
        }

        @Override
        public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            if (crn == 13 && crm == 0 && opcode2 == 3) {
                return 0xABCDEF01;
            }
            throw new IllegalStateException("bug: executor não consultou handles fino");
        }

        @Override
        public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            throw new IllegalStateException("bug: executor não consultou handles fino");
        }
    }
}
