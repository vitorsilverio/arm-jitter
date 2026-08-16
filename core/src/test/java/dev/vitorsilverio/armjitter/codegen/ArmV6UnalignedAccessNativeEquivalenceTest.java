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

/// Emissão nativa ASM de `LDR`/`STR`/`LDRH`/`STRH` sob `ArmFeature.UNALIGNED_ACCESS` (task B1.7):
/// o `AsmBlockCompiler` escolhe os helpers "Crossed" de {@code AsmRuntimeHelpers} em vez dos
/// legados de alinha+rotaciona quando a feature está ligada e o destino não é o PC — prova de
/// equivalência (invariante G1) contra o interpretado ARMv6K, 14 condições × 16 combinações de
/// NZCV, espelhando a técnica de {@code ArmV6ExtendReverseNativeEquivalenceTest}.
class ArmV6UnalignedAccessNativeEquivalenceTest extends BlockEquivalenceTest {
    private static final int FIRST_COND = 0;  // EQ
    private static final int LAST_COND = 13;  // LE

    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
    private final CodeEmitter v6Reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);

    private static IrBlock liftV6(TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder())
                .lift(memory, 0, count);
    }

    private static void applyFlags(ArmCore core, int nzcv) {
        core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
    }

    /// `LDR Rd,[Rn]` (offset imediato 0, `cond`).
    private static int ldr(int cond, int rn, int rd) {
        return (cond << 28) | 0x0590_0000 | (rn << 16) | (rd << 12);
    }

    /// `STR Rd,[Rn]` (offset imediato 0, `cond`).
    private static int str(int cond, int rn, int rd) {
        return (cond << 28) | 0x0580_0000 | (rn << 16) | (rd << 12);
    }

    /// `LDRH Rd,[Rn]` (offset imediato 0, unsigned, `cond`).
    private static int ldrh(int cond, int rn, int rd) {
        return (cond << 28) | 0x01D0_00B0 | (rn << 16) | (rd << 12);
    }

    /// `STRH Rd,[Rn]` (offset imediato 0, `cond`).
    private static int strh(int cond, int rn, int rd) {
        return (cond << 28) | 0x01C0_00B0 | (rn << 16) | (rd << 12);
    }

    @Test
    void conditionalUnalignedWordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDR<cond> r1,[r0] ; STR<cond> r1,[r2]
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldr(cond, 0, 1));
            memory.put32(4, str(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDR/STR<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 9);  // endereço desalinhado (+1) para leitura
                            core.setRegister(2, 33); // endereço desalinhado (+1) para escrita
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalUnalignedSignedAndUnsignedHalfwordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDRH<cond> r1,[r0] ; STRH<cond> r1,[r2]
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldrh(cond, 0, 1));
            memory.put32(4, strh(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDRH/STRH<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 17); // endereço ímpar (única forma desalinhada de halfword)
                            core.setRegister(2, 41);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void unalignedWordLoadToProgramCounterMatchesInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDR<cond> pc,[r0] — deve continuar alinhando (task B1.7, item 4), mesmo sob a feature.
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldr(cond, 0, 15));
            IrBlock block = liftV6(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "LDR pc,<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 33); // desalinhado — resultado deve ser o rotacionado legado
                            core.memory().write32(32, 0x0000_1000); // valor par: sem troca de estado THUMB
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    /// Achado real (task F3/`virtual-arm-box`): um `LDR`/`STR` com endereço JÁ ALINHADO sob a
    /// feature nunca deve atravessar byte a byte (ver Javadoc de
    /// {@code IrExecutionSupport#readWordForLoad}) — prova de paridade entre o interpretado e o
    /// `AsmBlockCompiler` (invariante G1) usando um endereço múltiplo de 4, que antes da correção
    /// já divergia silenciosamente de qualquer periférico MMIO real (ambos os backends, porém,
    /// concordavam entre si sobre RAM comum — daí este bug nunca ter sido pego por um teste de
    /// equivalência puro contra RAM; o valor em si é coberto pelo teste dedicado no interpretador,
    /// `ArmV6UnalignedAccessTest`, este aqui só garante que o `AsmBlockCompiler` não regride para
    /// um caminho diferente do interpretado no caso alinhado).
    @Test
    void conditionalAlignedWordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDR<cond> r1,[r0] ; STR<cond> r1,[r2]
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldr(cond, 0, 1));
            memory.put32(4, str(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDR/STR<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(0, 8);  // endereço JÁ ALINHADO para leitura
                            core.setRegister(2, 32); // endereço JÁ ALINHADO para escrita
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void syntheticUnalignedAccessBlockHasZeroPerOpFallback() {
        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(
                ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, ldr(0xE, 0, 1));   // LDR r1,[r0]
        memory.put32(4, str(0xE, 2, 1));   // STR r1,[r2]
        memory.put32(8, ldrh(0xE, 0, 3));  // LDRH r3,[r0]
        memory.put32(12, strh(0xE, 2, 3)); // STRH r3,[r2]
        IrBlock block = liftV6(memory, 4);

        assertTrue(perOpEmitter.isNativeSupported(block));
        perOpEmitter.emit(block);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }
}
