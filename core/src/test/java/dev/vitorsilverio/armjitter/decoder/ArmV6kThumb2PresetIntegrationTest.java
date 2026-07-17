package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B2.6, Passo 4/5: as 4 extensões Thumb-2 rodando JUNTAS contra o preset PÚBLICO real
/// (`ArmArchitecture#ARMV6K_THUMB2`), não mais presets sintéticos por teste — e o bloco misto de
/// aceite (`LDR.W` T3 + `IT EQ` + `ADDEQ.W` + `DMB` + `BL`) sem divergência interpretador×ASM.
/// `TBB`/`TBH` contra o mesmo preset real já são cobertos por `Thumb2BranchesItTest` (que usa
/// `ARMV6K_THUMB2` desde B2.4) — não duplicados aqui.
class ArmV6kThumb2PresetIntegrationTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.ARMV6K_THUMB2;
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    // ── Encoders (mesmo layout de bits usado em Thumb2LoadStoreDecoderTest/
    // Thumb2DataProcessingDecoderTest/Thumb2MiscDecoderTest/Thumb2BranchesItTest) ──────────────

    /// `LDR.W` T3 (ARM DDI 0406C A6.3.7): topByte=0xF8, bit23=1, sizeL=0b101 (LDR word), rn, rt,
    /// imm12. Retorna os dois halfwords combinados (`hi<<16|lo`), mesmo layout usado por
    /// `Thumb2LoadStoreDecoderTest`.
    private static int ldrWT3(int rn, int rt, int imm12) {
        int top8 = 0xF8;
        int sizeL = 0b101;
        int hi = (top8 << 8) | (1 << 7) | (sizeL << 4) | rn;
        int lo = (rt << 12) | (imm12 & 0xFFF);
        return (hi << 16) | (lo & 0xFFFF);
    }

    private static int it(int firstCond, int mask) {
        return 0xBF00 | ((firstCond & 0xF) << 4) | (mask & 0xF);
    }

    private static int addWRegisterHi(int rn) {
        return (0b1110101 << 9) | (0b1000 << 5) | rn; // OP4_ADD=0b1000, S=0
    }

    private static int addWRegisterLo(int rd, int rm) {
        return (rd << 8) | rm; // imm3=0, imm2=0, shift=LSL#0
    }

    private static int dmbHi() {
        return 0xF3BF;
    }

    private static int dmbLo() {
        return 0x8F00 | (0x5 << 4) | 0xF; // op=SY(0x5=DMB), option=0xF (full system)
    }

    private static int blPrefixHi(int highOffsetShiftedBy12) {
        int imm10 = (highOffsetShiftedBy12 >> 12) & 0x3FF;
        return 0xF000 | imm10;
    }

    private static int blSuffixLo(int lowOffset) {
        int imm11 = (lowOffset >>> 1) & 0x7FF;
        return 0xF800 | imm11; // BL (bit12=1)
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    @Test
    void allFourExtensionsArePluggedTogetherInThePublicPreset() {
        // B2.7 (PR1) acrescentou Thumb2RegisterDataProcessingDecoder (espaço 0xFA) como a 5ª;
        // B2.7 (PR2) acrescentou Thumb2MultiplyDecoder (espaço 0xFB) como a 6ª.
        assertEquals(6, THUMB2_ARCH.thumb32DecoderExtensions().size());
    }

    @Test
    void ldrWTakenAloneMatchesInterpretedReferenceThroughAsmEmitterOnRealPreset() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int ldr = ldrWT3(1, 0, 0x10); // LDR.W r0,[r1,#0x10]
        memory.put16(0, ldr >>> 16);
        memory.put16(2, ldr & 0xFFFF);
        memory.write32(0x10, 0xCAFEBABE);

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 1);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(1, 0x0)));
    }

    /// Passo 5 da spec: bloco misto de aceite `LDR.W` (T3) + `IT EQ` + `ADDEQ.W` + `DMB` + `BL` —
    /// `BL` é terminal (fecha o bloco, como o par legado já fazia), então é a última instrução do
    /// bloco lifted. `TBB` (também terminal) é coberto contra o mesmo preset real por
    /// `Thumb2BranchesItTest`.
    @Test
    void mixedBlockLdrWItAddEqDmbBlMatchesInterpretedReferenceThroughAsmEmitter() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int ldr = ldrWT3(1, 0, 0x10); // LDR.W r0,[r1,#0x10]
        int itInstr = it(0x0, 0b1000); // IT EQ
        int addHi = addWRegisterHi(0);
        int addLo = addWRegisterLo(2, 1); // ADDEQ.W r2, r0, r1
        int dmbHi = dmbHi();
        int dmbLo = dmbLo();
        int blPrefix = blPrefixHi(0);
        int blSuffix = blSuffixLo(8);

        int address = 0;
        memory.put16(address, ldr >>> 16); address += 2;
        memory.put16(address, ldr & 0xFFFF); address += 2;
        memory.put16(address, itInstr); address += 2;
        memory.put16(address, addHi); address += 2;
        memory.put16(address, addLo); address += 2;
        memory.put16(address, dmbHi); address += 2;
        memory.put16(address, dmbLo); address += 2;
        memory.put16(address, blPrefix); address += 2;
        memory.put16(address, blSuffix); address += 2;
        memory.write32(0x20, 0xCAFEBABE); // endereço = r1(0x10) + imm12(0x10)

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 8);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        // Guard verdadeiro (Z=1 -> EQ): ADDEQ.W deve executar nos dois backends.
        harness.assertEquivalent(reference, asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.cpsr().setNzcv(false, true, false, false);
            core.setRegister(1, 0x10);
        }));
        // Guard falso (Z=0 -> EQ falso): ADDEQ.W deve ser pulado nos dois backends.
        harness.assertEquivalent(reference, asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.cpsr().setNzcv(false, false, false, false);
            core.setRegister(1, 0x10);
        }));
    }

    @Test
    void mixedBlockEndToEndInterpretedExecutionProducesExpectedFinalState() {
        ArmCore core = newCore();
        core.setRegister(1, 0x10);
        core.memory().write32(0x20, 0xCAFEBABE); // endereço = r1(0x10) + imm12(0x10)
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro

        int address = 0;
        int ldr = ldrWT3(1, 0, 0x10);
        put16(core, address, ldr >>> 16); address += 2;
        put16(core, address, ldr & 0xFFFF); address += 2;
        put16(core, address, it(0x0, 0b1000)); address += 2; // IT EQ
        put16(core, address, addWRegisterHi(0)); address += 2;
        put16(core, address, addWRegisterLo(2, 1)); address += 2; // ADDEQ.W r2, r0, r1
        put16(core, address, dmbHi()); address += 2;
        put16(core, address, dmbLo()); address += 2;
        put16(core, address, blPrefixHi(0)); address += 2;
        put16(core, address, blSuffixLo(8)); address += 2; // BL +8

        core.step(); // LDR.W r0,[r1,#0x10]
        assertEquals(0xCAFEBABE, core.register(0));
        core.step(); // IT EQ
        core.step(); // ADDEQ.W r2, r0, r1
        assertEquals(0xCAFEBABE + 0x10, core.register(2));
        core.step(); // DMB
        int pcBeforeBl = core.programCounter();
        core.step(); // BL (decode único de 32 bits, B2.6): LR = (endereço do sufixo + 2) | 1 =
        // (pcBeforeBl + 2 + 2) | 1; alvo = (pcBeforeBl + 4 + highOffset(0)) + lowOffset(8).
        assertEquals((pcBeforeBl + 4) | 1, core.register(14));
        assertEquals(pcBeforeBl + 4 + 8, core.programCounter());
    }
}
