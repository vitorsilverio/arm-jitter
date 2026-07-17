package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
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
import java.util.List;
import org.junit.jupiter.api.Test;

/// B3.2 — `MLS`/`SDIV`/`UDIV` Thumb-2 (mesmo espaço `0xFB` de `Thumb2MultiplyDecoder`, B2.7 PR2):
/// paridade dos encodings v7 que faltavam naquele decoder. Layout de bits confirmado contra o
/// QEMU `target/arm/tcg/t32.decode` (ver `Thumb2MultiplyDecoder` para a citação completa). Vetores
/// concretos reaproveitados de `ArmV7MediaDecoderTest` (B3.1, encoding ARM clássico).
class Thumb2MultiplyDivideDecoderTest {
    private static final ArmArchitecture THUMB2_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-MultiplyDivide", ArmFeature.THUMB2,
            ArmFeature.MLS_MULTIPLY, ArmFeature.DIVIDE);
    private static final ArmArchitecture THUMB2_ARCH = THUMB2_FEATURES
            .withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(THUMB2_FEATURES)));

    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    // ── Encoders Thumb-2 (ver Thumb2MultiplyDecoder para o layout de bits) ──────────────────

    private static int hi(int family, int rn) {
        return 0xFB00 | (family << 4) | rn;
    }

    private static int lo(int p15_12, int p11_8, int op, int rm) {
        return (p15_12 << 12) | (p11_8 << 8) | (op << 4) | rm;
    }

    // ── Encoders ARM clássico (mesmos layouts de ArmDecoder) — sempre condição AL (0xE) ─────

    private static final int COND_AL = 0xE000_0000;

    private static int armMls(int rd, int ra, int rm, int rn) {
        return COND_AL | 0x0060_0090 | (rd << 16) | (ra << 12) | (rm << 8) | rn;
    }

    private static int armDivide(boolean signed, int rd, int rn, int rm) {
        return COND_AL | 0x0710_F010 | (signed ? 0 : 1 << 21) | (rd << 16) | (rm << 8) | rn;
    }

    // ── Runners ──────────────────────────────────────────────────────────────────────────────

    private static ArmCore newThumb2Core() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    /// `ArmDecoder` (encoding ARM clássico) também exige `MLS_MULTIPLY`/`DIVIDE` — `ARMV6K` puro
    /// não tem essas features (só chegam em `ARMV7A`, B3.7).
    private static final ArmArchitecture ARM_CLASSIC_ARCH = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARM-TestClassic-MultiplyDivide", ArmFeature.MLS_MULTIPLY, ArmFeature.DIVIDE);

    private static ArmCore newArmCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_ARCH);
    }

    private static void runThumb2(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    private static void runArm(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    // ── MLS: ida-e-volta contra o ARM clássico (mesmo vetor de B3.1) ────────────────────────

    @Test
    void mlsComputesAccumulatorMinusProductMatchesArmClassic() {
        // MLS r0,r3,r2,r1 (Rd=r0,Rn=r3,Rm=r2,Ra=r1) com Ra=10,Rm=4,Rn=3 -> r0 = 10-3*4 = -2.
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 10);
        thumb2Core.setRegister(2, 4);
        thumb2Core.setRegister(3, 3);
        runThumb2(thumb2Core, hi(0, 3), lo(1, 0, 1, 2)); // MLS r0, r3, r2, r1

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 10);
        armCore.setRegister(2, 4);
        armCore.setRegister(3, 3);
        runArm(armCore, armMls(0, 1, 2, 3)); // MLS r0, r3, r2, r1 (Rd,Ra,Rm,Rn nos campos ARM)

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(-2, thumb2Core.register(0));
    }

    @Test
    void mlsIsUndefinedWithoutMlsMultiplyFeature() {
        ArmArchitecture noMls = ArmArchitecture.of("NoMls", ArmFeature.THUMB2);
        ArmArchitecture arch = noMls.withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(noMls)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 3));
        memory.put16(2, lo(1, 0, 1, 2));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void mlsRejectsStackPointerAndProgramCounterOperands() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 15)); // Rn=PC
        memory.put16(2, lo(1, 0, 1, 2));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── SDIV/UDIV: ida-e-volta contra o ARM clássico (mesmos vetores de B3.1) ───────────────

    @Test
    void sdivTruncatesTowardsZeroMatchesArmClassic() {
        // SDIV r0, r1, r2 com r1=-7, r2=2 -> -3 (trunca para zero, não arredonda para baixo).
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, -7);
        thumb2Core.setRegister(2, 2);
        runThumb2(thumb2Core, hi(0x9, 1), lo(0xF, 0, 0xF, 2)); // SDIV r0, r1, r2

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, -7);
        armCore.setRegister(2, 2);
        runArm(armCore, armDivide(true, 0, 1, 2));

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(-3, thumb2Core.register(0));
    }

    @Test
    void sdivByZeroYieldsZeroWithoutException() {
        ArmCore core = newThumb2Core();
        core.setRegister(1, 42);
        core.setRegister(2, 0);
        runThumb2(core, hi(0x9, 1), lo(0xF, 0, 0xF, 2));
        assertEquals(0, core.register(0));
    }

    @Test
    void sdivMinValueDividedByMinusOneOverflowsSilently() {
        ArmCore core = newThumb2Core();
        core.setRegister(1, Integer.MIN_VALUE);
        core.setRegister(2, -1);
        runThumb2(core, hi(0x9, 1), lo(0xF, 0, 0xF, 2));
        assertEquals(Integer.MIN_VALUE, core.register(0));
    }

    @Test
    void udivDividesAsUnsignedMatchesArmClassic() {
        // UDIV r0, r1, r2 com r1=0xFFFFFFFF (unsigned), r2=2 -> 0x7FFFFFFF, não -1.
        ArmCore thumb2Core = newThumb2Core();
        thumb2Core.setRegister(1, 0xFFFF_FFFF);
        thumb2Core.setRegister(2, 2);
        runThumb2(thumb2Core, hi(0xB, 1), lo(0xF, 0, 0xF, 2)); // UDIV r0, r1, r2

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0xFFFF_FFFF);
        armCore.setRegister(2, 2);
        runArm(armCore, armDivide(false, 0, 1, 2));

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0x7FFF_FFFF, thumb2Core.register(0));
    }

    @Test
    void divideIsUndefinedWithoutDivideFeature() {
        ArmArchitecture noDivide = ArmArchitecture.of("NoDivide", ArmFeature.THUMB2);
        ArmArchitecture arch = noDivide.withThumb32DecoderExtensions(List.of(new Thumb2MultiplyDecoder(noDivide)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x9, 1));
        memory.put16(2, lo(0xF, 0, 0xF, 2));
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void divideRejectsStackPointerAndProgramCounterOperands() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x9, 13)); // Rn=SP
        memory.put16(2, lo(0xF, 0, 0xF, 2));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── Vizinho reservado (ARMv7 fora de escopo, ex. SMLAD family=0010) — UNDEFINED, não decode errado ──

    @Test
    void reservedNeighborFamilyIsUndefinedNotDecodedAsMlsOrDivide() {
        // family=0010 (SMLAD/SMLADX, ARMv7, nenhuma task cobre ainda) — reivindicado por
        // claimsEncodingSpace (todo o prefixo 0xFB), então cai em UNDEFINED controlado.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0x2, 1));
        memory.put16(2, lo(2, 0, 0, 3));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.MLS, instruction.kind());
        assertNotEquals(InstructionKind.DIVIDE, instruction.kind());
    }

    @Test
    void reservedOpUnderMulMlaFamilyIsUndefined() {
        // family=0000 (MUL/MLA/MLS), op=0010..1111 (reservado — só 0000=MUL/MLA e 0001=MLS
        // existem) continua UNDEFINED, não decodifica como MLS por engano.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 1));
        memory.put16(2, lo(2, 0, 0x2, 3));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── G2: sem THUMB2, o espaço 0xFB nunca chega a esta extensão ───────────────────────────

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi(0, 3));
        memory.put16(2, lo(1, 0, 1, 2));
        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);
        assertNotEquals(InstructionKind.MLS, instruction.kind());
    }

    // ── PER_OP fallback: sem divergência do interpretador (mesmo harness de B1.2-B1.5) ──────

    @Test
    void mixedBlockFallsBackToPerOpAndMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, hi(0, 3));             // MLS r0, r3, r2, r1
        memory.put16(2, lo(1, 0, 1, 2));
        memory.put16(4, hi(0x9, 1));            // SDIV r4, r1, r2
        memory.put16(6, lo(0xF, 4, 0xF, 2));
        memory.put16(8, hi(0xB, 1));            // UDIV r5, r1, r2
        memory.put16(10, lo(0xF, 5, 0xF, 2));

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 3);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter thumb2Reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        harness.assertEquivalent(thumb2Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(1, 10);
                    core.setRegister(2, 4);
                    core.setRegister(3, 3);
                }));
        assertTrue(asmEmitter.perOpFallbackOpCount() > 0,
                "MLS/SDIV/UDIV Thumb-2 (B3.2) ainda não têm emissão ASM nativa (ver B3.6)");
    }
}
