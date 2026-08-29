package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Consumer;

/// Cobertura exaustiva da emissão nativa ASM do VFP (B3.4/B3.5), emitida nativamente pela task
/// B3.6 (PR2). Os `IrOp` são montados à mão (mesmo padrão de {@code IrVfpExecutorTest}, já que o
/// decoder de VFP — B3.5 — não expõe um preset `ARMV7A` habilitado ainda, B3.7); o que se mede
/// aqui é a equivalência bit-exata ASM×interpretado (invariante G1), não o decode.
class VfpNativeEquivalenceTest extends BlockEquivalenceTest {
    private static final int FIRST_COND = 0;   // EQ
    private static final int LAST_COND = 13;   // LE
    private static final int RANDOM_VECTOR_COUNT = 64;
    private static final long RANDOM_SEED = 0xB3_6L;

    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV4T);

    private static IrBlock block(IrOp... ops) {
        IrBlock.Builder builder = IrBlock.builder(0);
        for (IrOp op : ops) {
            builder.add(op);
        }
        return builder.endPc(4 * Math.max(1, ops.length)).sealed();
    }

    private static Condition cond(int ordinal) {
        return Condition.values()[ordinal];
    }

    private void assertEquivalentVfp(IrBlock ir, Consumer<ArmCore> init) {
        TestAddressSpace memory = new TestAddressSpace(64);
        assertTrue(asmEmitter.isNativeSupported(ir), "bloco VFP deve ser nativo: " + ir.operations());
        harness.assertEquivalent(referenceEmitter, asmEmitter, ir, EquivalenceTestSupport.independentPair(memory, init));
    }

    // ── 1. VfpAlu: ADD/SUB/MUL/DIV single+double (bytecode direto) ──────────────

    @Test
    void conditionalArithMatchInterpretedAcrossAllCodesAndFlagsSingleAndDouble() {
        // Bancos S e D não podem colidir dentro do MESMO bloco (D<i> = S<2i>/S<2i+1>): entradas e
        // saídas single usam S0-S5, entradas/saídas double usam D8-D13 (S16-S27) — sem overlap.
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.SUB, false, 3, 0, 1, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.MUL, false, 4, 0, 1, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 5, 0, 1, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.ADD, true, 10, 8, 9, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.SUB, true, 11, 8, 9, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.MUL, true, 12, 8, 9, condition),
                    new IrOp.VfpAlu(IrOp.VfpOperation.DIV, true, 13, 8, 9, condition));
            assertEquivalentVfp(ir, core -> {
                core.vfp().setSFloat(0, 0.1f);
                core.vfp().setSFloat(1, 0.2f);
                core.vfp().setDDouble(8, 5.0);
                core.vfp().setDDouble(9, 2.0);
                applyFlags(core, flags);
            });
        }
    }

    @Test
    void divByZeroAndSubnormalMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 2, 0, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 3, 4, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 5, 6, 7, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.DIV, true, 8, 10, 11, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.vfp().setSFloat(0, 1.0f);
            core.vfp().setS(1, 0); // +0.0f — 1/0 = +Inf
            core.vfp().setSFloat(4, -1.0f); // -1/0 = -Inf
            core.vfp().setS(6, 0x0000_0001); // subnormal mínimo
            core.vfp().setS(7, 0x0000_0002); // subnormal
            core.vfp().setD(10, 1L);
            core.vfp().setD(11, 0L);
        });
    }

    // ── 2. VfpAlu: MLA/MLS/NMUL (helper vfpAluCold) ──────────────────────────────

    @Test
    void mlaMlsNmulSingleAndDoubleMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.MLA, false, 2, 0, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.MLS, false, 3, 0, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.NMUL, false, 4, 0, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.MLA, true, 8, 6, 7, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.MLS, true, 9, 6, 7, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.NMUL, true, 10, 6, 7, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.vfp().setSFloat(0, 1.0000001f);
            core.vfp().setSFloat(1, 1.0000001f);
            core.vfp().setSFloat(2, -1.0000002f);
            core.vfp().setSFloat(3, -1.0000002f);
            core.vfp().setSFloat(4, -1.0000002f);
            core.vfp().setDDouble(6, 2.0);
            core.vfp().setDDouble(7, 3.0);
            core.vfp().setDDouble(8, 10.0);
            core.vfp().setDDouble(9, 10.0);
        });
    }

    // ── 3. VfpAlu: NEG/ABS (bit de sinal, NaN payload, -0.0) ────────────────────

    @Test
    void negAbsPreserveNanPayloadAndSignedZeroMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.NEG, false, 1, 0, 0, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.NEG, false, 3, 0, 2, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.ABS, false, 5, 0, 4, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.NEG, true, 9, 0, 8, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.ABS, true, 11, 0, 10, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.vfp().setS(0, 0x7FC00001); // NaN quieto com payload
            core.vfp().setSFloat(2, 0.0f);
            core.vfp().setS(4, 0xFF80_0000); // -Inf
            core.vfp().setD(8, 0xFFF8_0000_0000_0001L); // NaN duplo, sinal negativo, payload
            core.vfp().setD(10, Long.MIN_VALUE); // -0.0 duplo
        });
    }

    // ── 4. VfpAlu: SQRT (helper vfpAluCold) ──────────────────────────────────────

    @Test
    void sqrtOfNegativeAndPositiveMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, false, 1, 0, 0, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, false, 3, 0, 2, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, true, 6, 0, 5, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.vfp().setSFloat(0, -1.0f);
            core.vfp().setSFloat(2, 2.0f);
            core.vfp().setDDouble(5, 2.0);
        });
    }

    // ── 5. VfpAlu: COPY ──────────────────────────────────────────────────────────

    @Test
    void copySingleAndDoubleMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.COPY, false, 1, 0, 0, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.COPY, true, 3, 0, 2, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.vfp().setS(0, 0x7FC00001);
            core.vfp().setD(2, 0xFFF8_0000_0000_0001L);
        });
    }

    // ── 6. VfpMoveImmediate ──────────────────────────────────────────────────────

    @Test
    void moveImmediateSingleAndDoubleMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpMoveImmediate(false, 0, 0x3F000000L, Condition.AL), // 0.5f
                new IrOp.VfpMoveImmediate(true, 2, 0x3FE0000000000000L, Condition.AL)); // 0.5
        assertEquivalentVfp(ir, core -> { });
    }

    // ── 7. VfpCompare: 4 quadrantes × 14 condições ──────────────────────────────

    @Test
    void conditionalCompareAllQuadrantsMatchInterpretedAcrossAllCodes() {
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpCompare(false, false, false, 0, 1, condition), // eq
                    new IrOp.VfpCompare(false, false, false, 2, 3, condition), // lt
                    new IrOp.VfpCompare(false, false, false, 4, 5, condition), // gt
                    new IrOp.VfpCompare(false, false, false, 6, 7, condition), // unordered (NaN)
                    new IrOp.VfpCompare(false, true, false, 8, 0, condition),  // compare-with-zero
                    new IrOp.VfpCompare(true, false, false, 10, 12, condition));
            assertEquivalentVfp(ir, core -> {
                core.vfp().setSFloat(0, 1.0f);
                core.vfp().setSFloat(1, 1.0f);
                core.vfp().setSFloat(2, 1.0f);
                core.vfp().setSFloat(3, 2.0f);
                core.vfp().setSFloat(4, 2.0f);
                core.vfp().setSFloat(5, 1.0f);
                core.vfp().setSFloat(6, Float.NaN);
                core.vfp().setSFloat(7, 1.0f);
                core.vfp().setSFloat(8, 0.0f);
                core.vfp().setDDouble(10, 3.0);
                core.vfp().setDDouble(12, 3.0);
            });
        }
    }

    // ── 8. VfpConvert: os 10 membros de VfpConversion ───────────────────────────

    @Test
    void allConversionsIncludingNanAndSaturationMatchInterpreted() {
        // Cada conversão roda num bloco/par de cores independente (EquivalenceTestSupport):
        // evita qualquer overlap entre bancos S/D de casos diferentes.
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_F64, 1, 0, Condition.AL)),
                core -> core.vfp().setSFloat(0, 1.5f));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_F32, 2, 0, Condition.AL)),
                core -> core.vfp().setDDouble(0, 2.5));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F32, 1, 0, Condition.AL)),
                core -> core.vfp().setS(0, -7));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F64, 1, 0, Condition.AL)),
                core -> core.vfp().setS(0, -7));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F32, 1, 0, Condition.AL)),
                core -> core.vfp().setS(0, -7));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F64, 1, 0, Condition.AL)),
                core -> core.vfp().setS(0, -7));
        assertEquivalentVfp( // NaN -> 0
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_S32, 1, 0, Condition.AL)),
                core -> core.vfp().setSFloat(0, Float.NaN));
        assertEquivalentVfp( // 1e30f -> Integer.MAX_VALUE (satura)
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_S32, 1, 0, Condition.AL)),
                core -> core.vfp().setSFloat(0, 1e30f));
        assertEquivalentVfp(
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_S32, 1, 0, Condition.AL)),
                core -> core.vfp().setDDouble(0, -1.5));
        assertEquivalentVfp( // -1.5f -> 0 (sem sinal, negativo clampa para 0)
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_U32, 1, 0, Condition.AL)),
                core -> core.vfp().setSFloat(0, -1.5f));
        assertEquivalentVfp( // 4294967040.0 -> 0xFFFFFF00
                block(new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_U32, 1, 0, Condition.AL)),
                core -> core.vfp().setDDouble(0, 4294967040.0));
    }

    // ── 9. VfpLoad/VfpStore: single+double, ida e volta ─────────────────────────

    @Test
    void loadStoreRoundTripSingleAndDoubleMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpStore(false, 0, 13, -1, 0, Condition.AL),
                new IrOp.VfpLoad(false, 1, 13, -1, 0, Condition.AL),
                new IrOp.VfpStore(true, 2, 13, -1, 8, Condition.AL),
                new IrOp.VfpLoad(true, 4, 13, -1, 8, Condition.AL));
        assertEquivalentVfp(ir, core -> {
            core.setRegister(13, 0);
            core.vfp().setS(0, 0x7FC00001);
            core.vfp().setD(2, 0xFFF8_0000_0000_0001L);
        });
    }

    /// Regressão: `VLDR`/`VSTR Vd, [pc, #imm]` (literal pool `gcc`, ver
    /// `VfpDecoderTest#loadStoreWithPcBaseAppliesArmProgramCounterBias`) — `base=15` com
    /// `baseValueOverride` setado. O bytecode ASM (`AsmBlockCompiler#emitVfpLoad/emitVfpStore`)
    /// precisa emitir a CONSTANTE do override em vez de ler `R15` do register cache, senão diverge
    /// do interpretado bit-a-bit (a divergência real que motivou esta task: JIT e interpretado
    /// liam de endereços diferentes e ERRADOS, cada um com sua própria resposta incorreta).
    @Test
    void loadStorePcRelativeBaseOverrideMatchesInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpStore(true, 0, 15, 32, 0, Condition.AL),   // [override+0] = D0
                new IrOp.VfpLoad(true, 1, 15, 32, 0, Condition.AL),    // D1 = [override+0]
                new IrOp.VfpStore(false, 2, 15, 48, 4, Condition.AL),  // [override+4] = S2
                new IrOp.VfpLoad(false, 3, 15, 48, 4, Condition.AL));  // S3 = [override+4]
        assertEquivalentVfp(ir, core -> {
            core.setProgramCounter(0xDEAD_0000); // baseValueOverride não depende do PC ao vivo
            core.vfp().setD(0, 0xAABBCCDD11223344L);
            core.vfp().setS(2, 0x7FC00001);
        });
    }

    // ── 10. VfpMultipleTransfer: VLDM/VSTM IA/DB, single+double, com writeback ──

    @Test
    void multipleTransferIaAndDbSingleAndDoubleMatchInterpreted() {
        IrBlock ir = block(
                new IrOp.VfpMultipleTransfer(false, false, 13, -1, 0, 4, true, false, Condition.AL),  // VSTM IA!
                new IrOp.VfpMultipleTransfer(true, false, 13, -1, 8, 4, true, false, Condition.AL),
                new IrOp.VfpMultipleTransfer(false, true, 13, -1, 0, 2, true, true, Condition.AL),     // VPUSH
                new IrOp.VfpMultipleTransfer(true, true, 13, -1, 4, 2, true, false, Condition.AL));    // VLDM IA
        assertEquivalentVfp(ir, core -> {
            core.setRegister(13, 0); // TestAddressSpace(64) — endereços ficam em [0,32), com folga
            for (int i = 0; i < 4; i++) {
                core.vfp().setS(i, 0x3F80_0000 + i);
            }
            core.vfp().setD(0, 0x4000_0000_0000_0000L);
            core.vfp().setD(1, 0x4008_0000_0000_0000L);
        });
    }

    @Test
    void conditionalMultipleTransferMatchInterpretedAcrossAllCodes() {
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpMultipleTransfer(false, false, 13, -1, 0, 3, false, false, condition),
                    new IrOp.VfpMultipleTransfer(true, false, 13, -1, 5, 3, false, false, condition));
            assertEquivalentVfp(ir, core -> {
                core.setRegister(13, 32);
                core.vfp().setS(0, 1);
                core.vfp().setS(1, 2);
                core.vfp().setS(2, 3);
                applyFlags(core, flags);
            });
        }
    }

    // ── 11. VfpCoreTransfer: FMRS/FMSR, ambos os sentidos ───────────────────────

    @Test
    void conditionalCoreTransferBothDirectionsMatchInterpretedAcrossAllCodes() {
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpCoreTransfer(true, 0, 1, false, condition),  // Sn -> Rt
                    new IrOp.VfpCoreTransfer(false, 2, 3, false, condition)); // Rt -> Sn
            assertEquivalentVfp(ir, core -> {
                core.vfp().setS(1, 0xCAFEBABE);
                core.setRegister(2, 0xDEADBEEF);
                applyFlags(core, flags);
            });
        }
    }

    // ── 12. VfpCorePairTransfer: FMRRD/FMDRR, ambos os sentidos ─────────────────

    @Test
    void conditionalCorePairTransferBothDirectionsMatchInterpretedAcrossAllCodes() {
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpCorePairTransfer(true, 0, 1, 2, condition),  // Dm -> (armLow,armHigh)
                    new IrOp.VfpCorePairTransfer(false, 3, 4, 5, condition)); // (armLow,armHigh) -> Dm
            assertEquivalentVfp(ir, core -> {
                core.vfp().setD(2, 0x1122334455667788L);
                core.setRegister(3, 0x11223344);
                core.setRegister(4, 0x55667788);
                applyFlags(core, flags);
            });
        }
    }

    // ── 13. VfpSystemTransfer: VMSR/VMRS incl. APSR_nzcv, 14 condições ──────────

    @Test
    void conditionalSystemTransferIncludingApsrNzcvMatchInterpretedAcrossAllCodes() {
        for (int c = FIRST_COND; c <= LAST_COND; c++) {
            Condition condition = cond(c);
            final int flags = c;
            IrBlock ir = block(
                    new IrOp.VfpCompare(false, false, false, 0, 1, condition), // grava FPSCR.NZCV
                    new IrOp.VfpSystemTransfer(true, 15, condition),           // VMRS APSR_nzcv
                    new IrOp.VfpSystemTransfer(false, 2, condition),           // VMSR desde Rt
                    new IrOp.VfpSystemTransfer(true, 3, condition));           // VMRS Rt normal
            assertEquivalentVfp(ir, core -> {
                core.vfp().setSFloat(0, 2.0f);
                core.vfp().setSFloat(1, 1.0f); // gt: N=0,Z=0,C=1,V=0
                core.setRegister(2, 0); // FPSCR limpo (RMode/FZ/Len/Stride=0, aceito)
                applyFlags(core, flags);
            });
        }
    }

    // ── 14. Property test (estilo C2): 64 floats aleatórios, seed fixa,
    //        todas as VfpOperation, single/double ────────────────────────────────

    @Test
    void randomValuesAcrossAllVfpOperationsMatchInterpretedBitExactly() {
        Random random = new Random(RANDOM_SEED);
        for (int i = 0; i < RANDOM_VECTOR_COUNT; i++) {
            int vnBits = random.nextInt();
            int vmBits = random.nextInt();
            int vdBits = random.nextInt();
            long vnBitsD = random.nextLong();
            long vmBitsD = random.nextLong();
            long vdBitsD = random.nextLong();
            for (IrOp.VfpOperation op : IrOp.VfpOperation.values()) {
                IrBlock singleBlock = block(new IrOp.VfpAlu(op, false, 0, 1, 2, Condition.AL));
                assertEquivalentVfp(singleBlock, core -> {
                    core.vfp().setS(0, vdBits);
                    core.vfp().setS(1, vnBits);
                    core.vfp().setS(2, vmBits);
                });
                IrBlock doubleBlock = block(new IrOp.VfpAlu(op, true, 0, 1, 2, Condition.AL));
                assertEquivalentVfp(doubleBlock, core -> {
                    core.vfp().setD(0, vdBitsD);
                    core.vfp().setD(1, vnBitsD);
                    core.vfp().setD(2, vmBitsD);
                });
            }
        }
    }

    // ── 15. perOpFallbackOpCount() == 0 num bloco sintético com todos os 10 kinds ─

    @Test
    void syntheticVfpBlockHasZeroPerOpFallback() {
        AsmCodeEmitter perOpEmitter = new AsmCodeEmitter(
                ArmArchitecture.ARMV4T, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        IrBlock ir = block(
                new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, Condition.AL),
                new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, false, 3, 0, 1, Condition.AL),
                new IrOp.VfpMoveImmediate(false, 4, 0x3F000000L, Condition.AL),
                new IrOp.VfpCompare(false, false, false, 0, 1, Condition.AL),
                new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_F64, 6, 0, Condition.AL),
                new IrOp.VfpStore(false, 0, 13, -1, 0, Condition.AL),
                new IrOp.VfpLoad(false, 1, 13, -1, 0, Condition.AL),
                new IrOp.VfpMultipleTransfer(false, false, 13, -1, 0, 2, true, false, Condition.AL),
                new IrOp.VfpCoreTransfer(true, 0, 1, false, Condition.AL),
                new IrOp.VfpCorePairTransfer(true, 0, 1, 2, Condition.AL),
                new IrOp.VfpSystemTransfer(true, 15, Condition.AL));
        assertTrue(perOpEmitter.isNativeSupported(ir));
        perOpEmitter.emit(ir);
        assertEquals(0, perOpEmitter.perOpFallbackOpCount());
    }

    private static void applyFlags(ArmCore core, int nzcv) {
        core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
    }
}
