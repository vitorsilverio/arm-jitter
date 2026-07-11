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

/// Cobertura exaustiva da emissão nativa ASM da aritmética paralela, SEL, PKHBT/PKHTB,
/// SSAT/USAT(16) e USAD8/USADA8 (ARMv6, task B1.3), emitidas nativamente pela task B1.6. Espelha
/// a técnica de {@code ArmV6ExtendReverseNativeEquivalenceTest} (14 condições × 16 combinações de
/// NZCV) contra o oráculo interpretado ARMv6K (invariante G1).
class ArmV6ParallelSaturateNativeEquivalenceTest extends BlockEquivalenceTest {
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
    void conditionalParallelAluVariantsMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // SADD16 r2,r0,r1 (SIGNED, wrap+GE) ; UQSUB8 r3,r0,r1 (saturada, sem GE)
            // UASX r4,r0,r1 (UNSIGNED cruzada, wrap+GE) ; SHSUB16 r5,r0,r1 (halving, sem GE)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x06102F11);
            memory.put32(4, (cond << 28) | 0x06603FF1);
            memory.put32(8, (cond << 28) | 0x06504F31);
            memory.put32(12, (cond << 28) | 0x06305F71);
            IrBlock block = liftV6(memory, 4);
            assertTrue(asmEmitter.isNativeSupported(block), "paralelas<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x8000_FFFF);
                            core.setRegister(1, 0x0001_7FFF);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalUadd8ThenSelMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // UADD8 r3,r0,r1 (produz GE) ; SEL r4,r0,r1 (consome o GE produzido acima)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x06503F91);
            memory.put32(4, (cond << 28) | 0x06804FB1);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "UADD8/SEL<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0xFF80_1234);
                            core.setRegister(1, 0x017F_4321);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalPkhbtAndPkhtbMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // PKHBT r2,r0,r1,LSL#4 ; PKHTB r5,r0,r1,ASR#8
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x06802211);
            memory.put32(4, (cond << 28) | 0x06805451);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "PKHBT/PKHTB<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0xABCD_1234);
                            core.setRegister(1, 0x5678_EF90);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalSsatUsatWordAndHalfwordMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // SSAT r6,#8,r1 ; USAT r6,#8,r1 ; SSAT16 r6,#4,r1 ; USAT16 r7,#4,r1
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x06A76011);
            memory.put32(4, (cond << 28) | 0x06E76011);
            memory.put32(8, (cond << 28) | 0x06A36F31);
            memory.put32(12, (cond << 28) | 0x06E37F31);
            IrBlock block = liftV6(memory, 4);
            assertTrue(asmEmitter.isNativeSupported(block), "SSAT/USAT<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(1, 0x7FFF_FFFF);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalUsad8AndUsada8MatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // USAD8 r7,r0,r1 (sem acumulador) ; USADA8 r6,r0,r1,r2 (com acumulador)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x0787F110);
            memory.put32(4, (cond << 28) | 0x07862110);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "USAD8/USADA8<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0xFF80_1234);
                            core.setRegister(1, 0x017F_4321);
                            core.setRegister(2, 1000);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void syntheticParallelSaturateBlockHasZeroPerOpFallback() {
        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(
                ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE650_3F91);  // UADD8 r3, r0, r1 (produz GE)
        memory.put32(4, 0xE680_4FB1);  // SEL r4, r0, r1 (consome GE)
        memory.put32(8, 0xE680_5451);  // PKHTB r5, r0, r1, ASR #8
        memory.put32(12, 0xE6A7_6011); // SSAT r6, #8, r1
        memory.put32(16, 0xE787_2110); // USADA8 r7, r0, r1, r2
        IrBlock block = liftV6(memory, 5);

        assertTrue(perOpEmitter.isNativeSupported(block));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }
}
