package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// A10.3 — os 14 `Kind` de VFP/coprocessador duplo/sysreg do perfil M (`VfpOpNode`, sem nó Truffle
/// antes desta task). Como o nó delega ao MESMO {@link dev.vitorsilverio.armjitter.codegen.executor.IrVfpExecutor}/
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrSystemExecutor} que o interpretador usa
/// (G1 — o interpretador é o oráculo), a equivalência é estrutural; estes testes confirmam que
/// cada `Kind` compila nativamente (sem cair no fallback) e produz o MESMO estado de `ArmCore` que
/// o interpretador.
class TruffleCodeEmitterVfpEquivalenceTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);

    private void assertVfpBlockEquivalent(ArmArchitecture architecture, List<IrOp> ops, Consumer<ArmCore> setup) {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(architecture);
        IrBlock block = new IrBlock(0, ops.size() * 4, ops);
        CodeEmitter reference = new InterpretedCodeEmitter(architecture);
        harness.assertEquivalent(reference, emitter, block, () -> {
            ArmCore referenceCore = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty(), architecture);
            ArmCore candidateCore = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty(), architecture);
            setup.accept(referenceCore);
            setup.accept(candidateCore);
            return new EquivalencePair(referenceCore, candidateCore);
        });
        assertEquals(1L, emitter.nativeBlockCount(), "bloco de VFP deve compilar nativamente (A10.3)");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    private void assertVfpBlockEquivalent(List<IrOp> ops, Consumer<ArmCore> setup) {
        assertVfpBlockEquivalent(ArmArchitecture.ARMV7A, ops, setup);
    }

    private static List<IrOp> withTail(IrOp op) {
        return List.of(op, new IrOp.Cycle(1), new IrOp.Fetch(0, 4));
    }

    @Test
    void vfpAluAddMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 0, 1, 2, Condition.AL)),
                core -> {
                    core.vfp().setSFloat(1, 2.5f);
                    core.vfp().setSFloat(2, 1.5f);
                });
    }

    @Test
    void vfpMoveImmediateMatchesInterpreter() {
        assertVfpBlockEquivalent(
                withTail(new IrOp.VfpMoveImmediate(false, 0, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL,
                        Condition.AL)),
                core -> {
                });
    }

    @Test
    void vfpCompareMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpCompare(false, false, false, 0, 1, Condition.AL)),
                core -> {
                    core.vfp().setSFloat(0, 3.0f);
                    core.vfp().setSFloat(1, 2.0f);
                });
    }

    @Test
    void vfpConvertMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F32, 0, 1, Condition.AL)),
                core -> core.vfp().setS(1, 42));
    }

    @Test
    void vfpLoadMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpLoad(false, 0, 1, -1, 0, Condition.AL)),
                core -> {
                    core.setRegister(1, 0x40);
                    core.memory().write32(0x40, Float.floatToRawIntBits(7.5f));
                });
    }

    @Test
    void vfpStoreMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpStore(false, 0, 1, -1, 0, Condition.AL)),
                core -> {
                    core.setRegister(1, 0x40);
                    core.vfp().setSFloat(0, 9.25f);
                });
    }

    @Test
    void vfpMultipleTransferMatchesInterpreter() {
        assertVfpBlockEquivalent(
                withTail(new IrOp.VfpMultipleTransfer(true, false, 0, -1, 4, 2, false, false, Condition.AL)),
                core -> {
                    core.setRegister(0, 0x40);
                    core.memory().write32(0x40, Float.floatToRawIntBits(1.0f));
                    core.memory().write32(0x44, Float.floatToRawIntBits(2.0f));
                });
    }

    @Test
    void vfpCoreTransferMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpCoreTransfer(true, 3, 0, false, Condition.AL)),
                core -> core.vfp().setS(0, 0x3F80_0000));
    }

    @Test
    void vfpCorePairTransferMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpCorePairTransfer(true, 3, 4, 0, Condition.AL)),
                core -> core.vfp().setD(0, 0x1234_5678_9ABC_DEF0L));
    }

    @Test
    void vfpSystemTransferMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpSystemTransfer(true, 3, Condition.AL)),
                core -> core.fpscr().setValue(0x0800_0000));
    }

    @Test
    void vfpCorePairTransferSingleMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpCorePairTransferSingle(true, 3, 4, 0, Condition.AL)),
                core -> {
                    core.vfp().setS(0, 111);
                    core.vfp().setS(1, 222);
                });
    }

    @Test
    void vfpConvertFixedMatchesInterpreter() {
        assertVfpBlockEquivalent(withTail(new IrOp.VfpConvertFixed(false, false, false, true, 8, 0, Condition.AL)),
                core -> core.vfp().setS(0, 256));
    }

    @Test
    void coprocessorDoubleWithoutHandlerRequestsUndefinedMatchesInterpreter() {
        // Sem CoprocessorBus configurado, MCRR/MRRC cai no mesmo caminho UNDEFINED nos dois
        // backends (mesma chamada a IrSystemExecutor#executeCoprocessorDouble).
        assertVfpBlockEquivalent(withTail(new IrOp.CoprocessorDouble(true, 15, 0, 0, 0, 1, 4, Condition.AL)),
                core -> {
                });
    }

    @Test
    void mProfileSystemRegisterMatchesInterpreter() {
        assertVfpBlockEquivalent(ArmArchitecture.ARMV7M,
                withTail(new IrOp.MProfileSystemRegister(false, 3, 0, Condition.AL)),
                core -> {
                    core.setExceptionModel(new MProfileExceptionModel());
                    core.setRegister(3, 0x1000);
                });
    }
}
