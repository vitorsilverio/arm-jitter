package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// Cobertura exaustiva da emissão nativa ASM das ops extend/reverse/UMAAL (ARMv6, task B1.2),
/// emitidas nativamente pela task B1.6. Espelha a técnica de {@code AsmCodeEmitterEquivalenceTest}
/// (14 condições × 16 combinações de NZCV) para provar o guard condicional gerado pelo
/// {@code AsmBlockCompiler} contra o oráculo interpretado ARMv6K (invariante G1).
class ArmV6ExtendReverseNativeEquivalenceTest extends BlockEquivalenceTest {
    private static final int FIRST_COND = 0;   // EQ
    private static final int LAST_COND = 13;   // LE

    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
    private final CodeEmitter v6Reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);

    private static IrBlock liftV6(TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder())
                .lift(memory, 0, count);
    }

    private static void applyFlags(ArmCore core, int nzcv) {
        core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
    }

    @Test
    void conditionalSxtbWithRotateMatchesInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // SXTB<cond> r1, r0, ROR #8
            int instruction = (cond << 28) | 0x06AF1470;
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, instruction);
            IrBlock block = liftV6(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "SXTB<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x0000FF00);
                            core.setRegister(1, 0x12345678);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalUxtahAccumulatorFormMatchesInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // UXTAH<cond> r5, r3, r2 (forma COM acumulador)
            int instruction = (cond << 28) | 0x06F35072;
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, instruction);
            IrBlock block = liftV6(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "UXTAH<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(2, 0xFFFF0001);
                            core.setRegister(3, 0x8000FFFF);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalSxtb16AndUxtb16MatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // SXTB16<cond> r1, r0 ; UXTB16<cond> r2, r0
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x068F1070); // SXTB16 (u=0)
            memory.put32(4, (cond << 28) | 0x06CF2070); // UXTB16 (u=1)
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "SXTB16/UXTB16<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x8012_7FED);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalRevRev16RevshMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // REV<cond> r4, r0 ; REV16<cond> r5, r0 ; REVSH<cond> r6, r0
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x06BF4F30);
            memory.put32(4, (cond << 28) | 0x06BF5FB0);
            memory.put32(8, (cond << 28) | 0x06FF6FB0);
            IrBlock block = liftV6(memory, 3);
            assertTrue(asmEmitter.isNativeSupported(block), "REV*<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x1234_FF80);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalUmaalMatchesInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // UMAAL<cond> r2, r3, r0, r1
            int instruction = (cond << 28) | 0x00432190;
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, instruction);
            IrBlock block = liftV6(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "UMAAL<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0xFFFF_FFFF);
                            core.setRegister(1, 0xFFFF_FFFF);
                            core.setRegister(2, 0xFFFF_FFFF); // RdLo
                            core.setRegister(3, 0xFFFF_FFFF); // RdHi — caso máximo, nunca estoura 64 bits
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void syntheticV6ExtendReverseUmaalBlockHasZeroPerOpFallback() {
        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(
                ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE6AF1470);  // SXTB r1, r0, ROR #8
        memory.put32(4, 0xE6BF4F30);  // REV r4, r0
        memory.put32(8, 0xE6F35072);  // UXTAH r5, r3, r2
        memory.put32(12, 0xE0432190); // UMAAL r2, r3, r0, r1
        IrBlock block = liftV6(memory, 4);

        assertTrue(perOpEmitter.isNativeSupported(block));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }
}
