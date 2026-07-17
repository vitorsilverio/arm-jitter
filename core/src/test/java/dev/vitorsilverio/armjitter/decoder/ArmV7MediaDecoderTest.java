package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// Inteiro ARMv7 em encodings ARM (task B3.1): `MOVW`/`MOVT`, `MLS`, `SBFX`/`UBFX`, `BFI`/`BFC`,
/// `RBIT`, `SDIV`/`UDIV`, `DMB`/`DSB`/`ISB`. Vetores concretos no preset de teste
/// {@link #B3_1}, gate UNDEFINED sem as features e em ARMV4T/ARMV5TE (G2), e equivalência
/// interpretado×ASM(PER_OP) — sem emissão nativa ainda (B3.6), mesmo padrão de B1.2 pré-B1.6.
///
/// Nenhuma destas instruções é implementada como {@code DecoderExtension}: o Passo 0 desta task
/// provou que TODAS colidem com dispatches genéricos já existentes em {@link ArmDecoder}
/// (halfword-transfer para `MLS`; LDR/STR imediato para `SBFX`/`UBFX`/`BFI`/`BFC`/`RBIT`/`SDIV`/
/// `UDIV`; dispatch ALU para `MOVW`/`MOVT`; LDR/STR imediato de novo para `DMB`/`DSB`/`ISB`) —
/// todas viraram carve-outs diretos em `ArmDecoder`, mesmo padrão de `BLX`/`CLZ`/`UMAAL`/exclusivos.
class ArmV7MediaDecoderTest {
    /// Arquitetura de teste com as features desta task — sem nenhum decoder extra, já que os
    /// carve-outs vivem direto em {@link ArmDecoder} (ver javadoc da classe).
    private static final ArmArchitecture B3_1 = ArmArchitecture.extending(ArmArchitecture.ARMV6K,
            "test-b3.1", ArmFeature.MOVW_MOVT, ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD,
            ArmFeature.BIT_REVERSE, ArmFeature.DIVIDE, ArmFeature.MEMORY_BARRIERS);

    private static int decodeKindRaw(ArmArchitecture architecture, int raw) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, raw);
        return new ArmDecoder(architecture).decode(memory, 0).kind() == InstructionKind.UNIMPLEMENTED ? 1 : 0;
    }

    private static ArmCore run(ArmArchitecture architecture, int[] program, Consumer<ArmCore> init) {
        TestAddressSpace memory = new TestAddressSpace(program.length * 4 + 4);
        for (int i = 0; i < program.length; i++) {
            memory.put32(i * 4, program[i]);
        }
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
        init.accept(core);
        for (int i = 0; i < program.length; i++) {
            core.step();
        }
        return core;
    }

    private static ArmCore run(int raw, Consumer<ArmCore> init) {
        return run(B3_1, new int[] {raw}, init);
    }

    // ── Passo 0: sonda — o `ArmDecoder` base NÃO reivindica nenhum destes espaços hoje ──────────

    @Test
    void probeAllTableEntriesAreUnimplementedUnderPlainArmv6k() {
        // MOVW r0,#0x1234 / MOVT r0,#0x5678 / MLS r0,r1,r2,r3 / SBFX r0,r1,#0,#8 /
        // UBFX r0,r1,#4,#8 / BFI r0,r1,#4,#8 / BFC r0,#4,#8 / RBIT r0,r1 / SDIV r0,r1,r2 /
        // UDIV r0,r1,r2 / DMB SY / DSB SY / ISB SY.
        int[] rawTable = {
            0xE301_0234, 0xE345_0678, 0xE060_1293, 0xE7A7_0051, 0xE7E7_0051,
            0xE7CB_0211, 0xE7CB_021F, 0xE6FF_0F31, 0xE710_F211, 0xE730_F211,
            0xF57F_F05F, 0xF57F_F04F, 0xF57F_F06F,
        };
        for (int raw : rawTable) {
            assertEquals(1, decodeKindRaw(ArmArchitecture.ARMV6K, raw),
                    "raw=" + Integer.toHexString(raw) + " deveria ser UNIMPLEMENTED sob ARMV6K puro");
        }
    }

    // ── Vetores concretos ────────────────────────────────────────────────────────────────────

    @Test
    void movwMovtComposeA32BitConstant() {
        ArmCore core = run(B3_1, new int[] {0xE301_0234, 0xE345_0678}, c -> { });
        assertEquals(0x5678_1234, core.register(0));
    }

    @Test
    void movwMovtRejectProgramCounterAsDestination() {
        // MOVW pc,#0 — UNPREDICTABLE, deve virar UNIMPLEMENTED (não decodifica como MOV real).
        assertEquals(1, decodeKindRaw(B3_1, 0xE300_F000));
        assertEquals(1, decodeKindRaw(B3_1, 0xE340_F000));
    }

    @Test
    void mlsComputesAccumulatorMinusProduct() {
        // raw=0xE0601293: Rd=r0, Ra(acumulador)=r1, Rm=r2, Rn=r3 -> r0 = r1 - r3*r2 = 10-3*4=-2.
        ArmCore core = run(0xE060_1293, c -> {
            c.setRegister(1, 10);
            c.setRegister(2, 4);
            c.setRegister(3, 3);
        });
        assertEquals(-2, core.register(0));
    }

    @Test
    void ubfxExtractsFieldWithoutSignExtension() {
        // UBFX r0, r1, #4, #8 com r1=0xABCD1234 -> r0=0x23 (exemplo da spec da task).
        ArmCore core = run(0xE7E7_0251, c -> c.setRegister(1, 0xABCD_1234));
        assertEquals(0x23, core.register(0));
    }

    @Test
    void sbfxSignExtendsTheExtractedField() {
        // SBFX r0, r1, #0, #8 com r1=0x80 (bit 7 do campo ligado) -> r0=0xFFFFFF80 (com sinal);
        // UBFX no mesmo raw base -> 0x80 (sem sinal), provando a diferença entre as duas formas.
        ArmCore signedCore = run(0xE7A7_0051, c -> c.setRegister(1, 0x80));
        assertEquals(0xFFFF_FF80, signedCore.register(0));
        ArmCore unsignedCore = run(0xE7E7_0051, c -> c.setRegister(1, 0x80));
        assertEquals(0x80, unsignedCore.register(0));
    }

    @Test
    void bfcClearsTheFieldLeavingTheRestUntouched() {
        // BFC r0, #4, #8 com r0=0xFFFFFFFF -> limpa bits [11:4] -> 0xFFFFF00F.
        ArmCore core = run(0xE7CB_021F, c -> c.setRegister(0, 0xFFFF_FFFF));
        assertEquals(0xFFFF_F00F, core.register(0));
    }

    @Test
    void bfiInsertsWithoutTouchingTheRestOfDst() {
        // BFI r0, r1, #4, #8 com r0=0xFFFFFFFF, r1=0xAB -> insere 0xAB em [11:4], resto intacto.
        ArmCore core = run(0xE7CB_0211, c -> {
            c.setRegister(0, 0xFFFF_FFFF);
            c.setRegister(1, 0xAB);
        });
        assertEquals(0xFFFF_FABF, core.register(0));
    }

    @Test
    void rbitReversesAllThirtyTwoBits() {
        // RBIT r0, r1 com r1=0x80000001 -> palíndromo de bits -> 0x80000001 (exemplo da spec).
        ArmCore core = run(0xE6FF_0F31, c -> c.setRegister(1, 0x8000_0001));
        assertEquals(0x8000_0001, core.register(0));
    }

    @Test
    void sdivTruncatesTowardsZero() {
        // SDIV r0, r1, r2 com r1=-7, r2=2 -> -3 (trunca para zero, não arredonda para baixo).
        ArmCore core = run(0xE710_F211, c -> {
            c.setRegister(1, -7);
            c.setRegister(2, 2);
        });
        assertEquals(-3, core.register(0));
    }

    @Test
    void sdivByZeroYieldsZeroWithoutException() {
        ArmCore core = run(0xE710_F211, c -> {
            c.setRegister(1, 42);
            c.setRegister(2, 0);
        });
        assertEquals(0, core.register(0));
    }

    @Test
    void sdivMinValueDividedByMinusOneOverflowsSilently() {
        ArmCore core = run(0xE710_F211, c -> {
            c.setRegister(1, Integer.MIN_VALUE);
            c.setRegister(2, -1);
        });
        assertEquals(Integer.MIN_VALUE, core.register(0));
    }

    @Test
    void udivDividesAsUnsigned() {
        // UDIV r0, r1, r2 com r1=0xFFFFFFFF (unsigned 4294967295), r2=2 -> 2147483647 (0x7FFFFFFF),
        // não -1 (que seria o resultado de uma divisão COM sinal).
        ArmCore core = run(0xE730_F211, c -> {
            c.setRegister(1, 0xFFFF_FFFF);
            c.setRegister(2, 2);
        });
        assertEquals(0x7FFF_FFFF, core.register(0));
    }

    @Test
    void memoryBarriersAreObservableNoOps() {
        // DMB/DSB/ISB SY seguidos de um MOV — nenhuma das barreiras deve alterar registrador algum.
        ArmCore core = run(B3_1, new int[] {0xF57F_F05F, 0xF57F_F04F, 0xF57F_F06F, 0xE3A0_002A}, c -> { });
        assertEquals(42, core.register(0));
    }

    // ── Gate: sem a feature, cada instrução vira UNIMPLEMENTED ──────────────────────────────────

    @Test
    void everyInstructionIsUndefinedWithoutItsFeature() {
        int[] rawTable = {
            0xE301_0234, 0xE345_0678, 0xE060_1293, 0xE7A7_0051, 0xE7E7_0051,
            0xE7CB_0211, 0xE7CB_021F, 0xE6FF_0F31, 0xE710_F211, 0xE730_F211,
            0xF57F_F05F, 0xF57F_F04F, 0xF57F_F06F,
        };
        for (int raw : rawTable) {
            assertEquals(1, decodeKindRaw(ArmArchitecture.ARMV6K, raw),
                    "raw=" + Integer.toHexString(raw) + " não deveria decodificar sem a feature");
        }
    }

    // ── G2: nada disso decodifica em ARMV4T/ARMV5TE ─────────────────────────────────────────────

    @Test
    void nothingDecodesUnderArmv4tOrArmv5te() {
        int[] rawTable = {
            0xE301_0234, 0xE345_0678, 0xE060_1293, 0xE7A7_0051, 0xE7E7_0051,
            0xE7CB_0211, 0xE7CB_021F, 0xE6FF_0F31, 0xE710_F211, 0xE730_F211,
            0xF57F_F05F, 0xF57F_F04F, 0xF57F_F06F,
        };
        for (int raw : rawTable) {
            assertEquals(1, decodeKindRaw(ArmArchitecture.ARMV4T, raw));
            assertEquals(1, decodeKindRaw(ArmArchitecture.ARMV5TE, raw));
        }
    }

    // ── Equivalência PER_OP: interpretado × ASM (sem emissão nativa até B3.6) ────────────────────

    static class PerOpEquivalence extends BlockEquivalenceTest {
        private final AsmCodeEmitter asmEmitter =
                new AsmCodeEmitter(B3_1, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        private final CodeEmitter b31Reference = new InterpretedCodeEmitter(B3_1);

        @Test
        void mixedBlockFallsBackPerOpAndMatchesInterpreted() {
            TestAddressSpace memory = new TestAddressSpace(64);
            memory.put32(0, 0xE060_1293);  // MLS r0, r1, r2, r3
            memory.put32(4, 0xE7E7_4251);  // UBFX r4, r1, #4, #8
            memory.put32(8, 0xE7CB_5211);  // BFI r5, r1, #4, #8 (r5 preservado fora do campo)
            memory.put32(12, 0xE6FF_6F31); // RBIT r6, r1
            memory.put32(16, 0xE717_F211); // SDIV r7, r1, r2
            IrBlock block = new StandardIrBlockLifter(new ArmDecoder(B3_1), new StandardIrBuilder())
                    .lift(memory, 0, 5);

            asmEmitter.emit(block);
            assertTrue(asmEmitter.perOpFallbackOpCount() > 0,
                    "ops B3.1 devem cair no fallback PER_OP até a emissão nativa da B3.6");
            harness.assertEquivalent(b31Reference, asmEmitter, block,
                    EquivalenceTestSupport.independentPair(memory, core -> {
                        core.setRegister(1, 3);
                        core.setRegister(2, 4);
                        core.setRegister(3, 10);
                        core.setRegister(5, 0xFFFF_FFFF);
                    }));
        }
    }
}
