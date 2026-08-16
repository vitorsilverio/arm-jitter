package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// Emissão nativa ASM de acesso de dados sob `CPSR.E=1` (BE8, task B1.8): os helpers
/// `loadWord`/`loadHalf`/`storeWord`/`storeHalf` (e as variantes `*Crossed` de
/// `ArmFeature.UNALIGNED_ACCESS`) trocam bytes IDENTICAMENTE ao interpretado — prova de
/// equivalência (invariante G1), 14 condições × 16 combinações de NZCV, mesma técnica de
/// `ArmV6UnalignedAccessNativeEquivalenceTest`. `LDR`/`STR`/`LDRH`/`STRH` já são ops nativas
/// (`AsmNativePolicy` nunca rejeitou `IrOp.Load`/`IrOp.Store`) — nenhuma mudança de política
/// foi necessária para BE8 funcionar nativamente, só nos helpers que o bytecode já chama.
class ArmV6Be8NativeEquivalenceTest extends BlockEquivalenceTest {
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
    void conditionalBigEndianAlignedWordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDR<cond> r1,[r0] ; STR<cond> r1,[r2] — endereços ALINHADOS, E=1.
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldr(cond, 0, 1));
            memory.put32(4, str(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDR/STR<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.cpsr().setBigEndian(true);
                            core.setRegister(0, 8);
                            core.setRegister(2, 32);
                            core.setRegister(1, 0x1122_3344);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalBigEndianUnalignedWordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // Mesmo caso de ArmV6UnalignedAccessNativeEquivalenceTest, mas com E=1: exercita o
            // caminho "Crossed" (UNALIGNED_ACCESS) COM BE8 simultaneamente.
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldr(cond, 0, 1));
            memory.put32(4, str(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDR/STR<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.cpsr().setBigEndian(true);
                            core.setRegister(0, 9);  // endereço desalinhado (+1) para leitura
                            core.setRegister(2, 33); // endereço desalinhado (+1) para escrita
                            core.setRegister(1, 0x1122_3344);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalBigEndianHalfwordLoadStoreMatchInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            // LDRH<cond> r1,[r0] ; STRH<cond> r1,[r2] — endereço alinhado, E=1.
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, ldrh(cond, 0, 1));
            memory.put32(4, strh(cond, 2, 1));
            IrBlock block = liftV6(memory, 2);
            assertTrue(asmEmitter.isNativeSupported(block), "LDRH/STRH<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                harness.assertEquivalent(v6Reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.cpsr().setBigEndian(true);
                            core.setRegister(0, 16);
                            core.setRegister(2, 40);
                            core.setRegister(1, 0x1122);
                            applyFlags(core, flags);
                        }));
            }
        }
    }
}
