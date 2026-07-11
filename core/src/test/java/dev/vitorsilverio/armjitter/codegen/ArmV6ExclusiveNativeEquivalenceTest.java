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

/// Cobertura exaustiva da emissão nativa ASM dos acessos exclusivos ARMv6/v6K (LDREX/STREX/CLREX,
/// task B1.4), emitidas nativamente pela task B1.6. Espelha a técnica de
/// {@code ArmV6ExtendReverseNativeEquivalenceTest} (14 condições × 16 combinações de NZCV) contra
/// o oráculo interpretado ARMv6K (invariante G1) — inclui a checagem do monitor ANTES do write
/// (STREX que falha não deve tocar a memória) e as formas B/H/D.
///
/// {@code CLREX} vive no espaço incondicional (encoding exato {@code 0xF57FF01F}, sempre AL — ver
/// {@link dev.vitorsilverio.armjitter.ir.IrOp.ClearExclusive}), então não entra no loop de
/// condições; é coberto por {@link #clrexBlockIsNativeAndMatchesInterpreted()}.
class ArmV6ExclusiveNativeEquivalenceTest extends BlockEquivalenceTest {
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
    void conditionalLdrexStrexWordSucceedingMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDREX r2, [r0] ; STREX r1, r2, [r0] (monitor recém-marcado — deve suceder: r1=0)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x01902F9F);
            memory.put32(4, (cond << 28) | 0x01801F92);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDREX/STREX<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x10);
                            core.setRegister(2, 0xCAFEBABE);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalStrexWithoutPriorLdrexFailsMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // STREX r1, r2, [r0] sem LDREX antes — monitor fechado, deve falhar (r1=1, memória intacta)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, (cond << 28) | 0x01801F92);
            IrBlock block = liftV6(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "STREX<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x10);
                            core.setRegister(2, 0xCAFEBABE);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalLdrexbStrexhMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDREXB r3, [r0] ; STREXH r1, r2, [r0]
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, (cond << 28) | 0x01D03F9F);
            memory.put32(4, (cond << 28) | 0x01E01F92);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDREXB/STREXH<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x20);
                            core.setRegister(2, 0x1234);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalLdrexdStrexdMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDREXD r4, [r0] (carrega o par r4:r5) ; STREXD r1, r4, [r0] (armazena o par r4:r5)
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, (cond << 28) | 0x01B04F9F);
            memory.put32(4, (cond << 28) | 0x01A01F94);
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDREXD/STREXD<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 0x30);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void clrexBlockIsNativeAndMatchesInterpreted() {
        // CLREX é sempre AL (encoding incondicional) — sem loop de cond, só de NZCV.
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE190_2F9F);  // LDREX r2, [r0]
        memory.put32(4, 0xE180_1F92);  // STREX r1, r2, [r0] (deve suceder)
        memory.put32(8, 0xF57F_F01F);  // CLREX
        memory.put32(12, 0xE180_3F91); // STREX r3, r1, [r0] (deve falhar: monitor aberto)
        IrBlock block = liftV6(memory, 4);
        assertTrue(asmEmitter.isNativeSupported(block), "CLREX (B1.4) ganhou emissão nativa na task B1.6");
        for (int nzcv = 0; nzcv < 16; nzcv++) {
            int flags = nzcv;
            harness.assertEquivalent(v6Reference, asmEmitter, block,
                    EquivalenceTestSupport.independentPair(memory, core -> {
                        core.setRegister(0, 0x10);
                        core.setRegister(2, 0xCAFEBABE);
                        applyFlags(core, flags);
                    }));
        }
    }

    @Test
    void syntheticExclusiveBlockHasZeroPerOpFallback() {
        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(
                ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE190_2F9F);  // LDREX r2, [r0]
        memory.put32(4, 0xE180_1F92);  // STREX r1, r2, [r0] (deve suceder)
        memory.put32(8, 0xF57F_F01F);  // CLREX
        memory.put32(12, 0xE180_3F91); // STREX r3, r1, [r0] (deve falhar: monitor aberto)
        IrBlock block = liftV6(memory, 4);

        assertTrue(perOpEmitter.isNativeSupported(block));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }
}
