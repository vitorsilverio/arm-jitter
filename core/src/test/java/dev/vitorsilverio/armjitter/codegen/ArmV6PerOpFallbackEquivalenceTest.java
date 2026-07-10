package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// Prova o contrato da task B1.2 com o backend ASM: as ops ARMv6 (extend/reverse/UMAAL) NÃO são
/// emitidas nativamente (isso é B1.6) e caem no interpretado inline do modo {@code PER_OP},
/// produzindo estado idêntico ao interpretador (invariante G1) sem mudança alguma no emissor.
class ArmV6PerOpFallbackEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(
            ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
    private final CodeEmitter v6Reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);

    @Test
    void extendReverseUmaalBlockMatchesInterpretedUnderPerOp() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE6AF_1470);  // SXTB r1, r0, ROR #8
        memory.put32(4, 0xE6BF_4F30);  // REV r4, r0
        memory.put32(8, 0xE6F3_5072);  // UXTAH r5, r3, r2
        memory.put32(12, 0xE043_2190); // UMAAL r2, r3, r0, r1
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder()).lift(memory, 0, 4);

        assertFalse(asmEmitter.isNativeSupported(block),
                "as ops ARMv6 só ganham emissão nativa na B1.6");
        harness.assertEquivalent(v6Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x0000FF00);
                    core.setRegister(1, 0x12345678);
                    core.setRegister(2, 0xFFFF0001);
                    core.setRegister(3, 0x8000FFFF);
                }));
        assertTrue(asmEmitter.perOpFallbackOpCount() > 0,
                "as ops ARMv6 devem ter passado pelo fallback por-op");
    }
}
