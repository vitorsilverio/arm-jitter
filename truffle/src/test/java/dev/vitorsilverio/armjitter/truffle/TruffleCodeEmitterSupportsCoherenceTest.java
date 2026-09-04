package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ParallelAluOp;
import dev.vitorsilverio.armjitter.ir.ParallelAluVariant;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A10.1 — trava o contrato factory↔supports: {@link IrOpNodeFactory#supports} e
/// {@link IrOpNodeFactory#create} nunca podem divergir. Sem este teste a correção da A10.1 se
/// reintroduz sozinha quando uma task futura acrescentar um `Kind` só num dos dois lugares.
///
/// Para cada `IrOp.Kind` (78 desde B13.9), monta um `IrOp` representativo (só o `kind()` importa —
/// `create` nunca inspeciona outro campo para escolher o nó) e verifica:
/// <ul>
///   <li>{@code supports(op) == true}  ⇒ {@code create(op, executor)} NÃO lança;</li>
///   <li>{@code supports(op) == false} ⇒ {@code create(op, executor)} lança
///       {@link IllegalStateException} (a rede de segurança G8 do `default`).</li>
/// </ul>
class TruffleCodeEmitterSupportsCoherenceTest {
    private final IrBlockExecutor executor = new IrBlockExecutor(ArmArchitecture.ARMV7A);

    @Test
    void everyKindHasCoherentSupportsAndCreate() {
        List<Integer> kinds = allKindConstants();
        assertEquals(81, kinds.size(), "IrOp.Kind deve ter 81 constantes contíguas");

        for (int kind : kinds) {
            IrOp op = sampleOp(kind);
            assertEquals(kind, op.kind(), "sampleOp devolveu um op de kind errado");

            if (IrOpNodeFactory.supports(op)) {
                assertDoesNotThrow(() -> IrOpNodeFactory.create(op, executor),
                        "supports=true mas create lançou para kind=" + kind);
            } else {
                assertThrows(IllegalStateException.class, () -> IrOpNodeFactory.create(op, executor),
                        "supports=false mas create NÃO lançou para kind=" + kind);
            }
        }
    }

    @Test
    void theUncoveredKindsAreExactlyTheKnownList() {
        List<Integer> uncovered = new ArrayList<>();
        for (int kind : allKindConstants()) {
            if (!IrOpNodeFactory.supports(sampleOp(kind))) {
                uncovered.add(kind);
            }
        }
        // Os Kinds sem nó Truffle (VFP/coprocessador, NEON, DSP dual/top-word,
        // HVC/SMC/ERET/MRS_bank/MSR_bank, bitfield/RBIT/SDIV, BKPT, sysreg do perfil M). Eram 33 na
        // A10.1; B13.7 acrescentou NEON_SHIFT_IMMEDIATE; B13.8 acrescentou NEON_SHIFT_NARROW_IMMEDIATE,
        // NEON_SHIFT_WIDEN_IMMEDIATE, NEON_CONVERT_FIXED_POINT; B13.9 acrescentou
        // NEON_MODIFIED_IMMEDIATE; B13.10 acrescentou NEON_WIDENING, NEON_WIDE, NEON_NARROW (NEON
        // também não tem nó Truffle).
        assertEquals(41, uncovered.size(), "Kinds descobertos: " + uncovered);
        assertTrue(uncovered.containsAll(List.of(
                        IrOp.Kind.BIT_FIELD_EXTRACT, IrOp.Kind.BIT_FIELD_INSERT, IrOp.Kind.BIT_REVERSE,
                        IrOp.Kind.DIVIDE, IrOp.Kind.VFP_ALU, IrOp.Kind.VFP_MOVE_IMMEDIATE, IrOp.Kind.VFP_COMPARE,
                        IrOp.Kind.VFP_CONVERT, IrOp.Kind.VFP_LOAD, IrOp.Kind.VFP_STORE,
                        IrOp.Kind.VFP_MULTIPLE_TRANSFER, IrOp.Kind.VFP_CORE_TRANSFER,
                        IrOp.Kind.VFP_CORE_PAIR_TRANSFER, IrOp.Kind.VFP_SYSTEM_TRANSFER,
                        IrOp.Kind.M_PROFILE_SYSTEM_REGISTER, IrOp.Kind.BREAKPOINT, IrOp.Kind.COPROCESSOR_DOUBLE,
                        IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE, IrOp.Kind.VFP_CONVERT_FIXED,
                        IrOp.Kind.DSP_DUAL_MULTIPLY, IrOp.Kind.DSP_TOP_WORD_MULTIPLY, IrOp.Kind.HVC, IrOp.Kind.SMC,
                        IrOp.Kind.ERET, IrOp.Kind.MRS_BANK, IrOp.Kind.MSR_BANK, IrOp.Kind.NEON_THREE_SAME,
                        IrOp.Kind.NEON_LOAD_STORE_MULTIPLE, IrOp.Kind.NEON_LOAD_STORE_SINGLE,
                        IrOp.Kind.NEON_LOAD_ALL_LANES, IrOp.Kind.NEON_PAIRWISE, IrOp.Kind.NEON_FP_THREE_SAME,
                        IrOp.Kind.NEON_FP_PAIRWISE, IrOp.Kind.NEON_SHIFT_IMMEDIATE,
                        IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE, IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE,
                        IrOp.Kind.NEON_CONVERT_FIXED_POINT, IrOp.Kind.NEON_MODIFIED_IMMEDIATE,
                        IrOp.Kind.NEON_WIDENING, IrOp.Kind.NEON_WIDE, IrOp.Kind.NEON_NARROW)),
                "lista dos Kinds descobertos mudou: " + uncovered);
    }

    private static List<Integer> allKindConstants() {
        List<Integer> kinds = new ArrayList<>();
        for (Field field : IrOp.Kind.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                try {
                    kinds.add(field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return kinds;
    }

    /// Um `IrOp` por `Kind`. Valores dos campos são irrelevantes — `IrOpNodeFactory` só olha
    /// `op.kind()` (e `op.condition()`, sempre {@link Condition#AL} aqui) — mas os construtores
    /// dos records validam aridade/tipo, então são preenchidos com valores plausíveis.
    private static IrOp sampleOp(int kind) {
        Condition c = Condition.AL;
        IrOperand imm = new IrOperand.Immediate(0);
        return switch (kind) {
            case IrOp.Kind.ALU -> new IrOp.Alu(IrOpCode.MOV, 0, -1, -1, imm, false, c);
            case IrOp.Kind.MULTIPLY -> new IrOp.Multiply(0, 1, -1, 2, -1, -1, -1, false, false, false, c);
            case IrOp.Kind.LONG_MULTIPLY -> new IrOp.LongMultiply(0, 1, 2, -1, 3, -1, -1, -1, false, false, false, c);
            case IrOp.Kind.SATURATING -> new IrOp.Saturating(0, 1, 2, 0, c);
            case IrOp.Kind.DSP_MULTIPLY -> new IrOp.DspMultiply(0, 1, 2, 3, 0, 0, 0, c);
            case IrOp.Kind.PSR_TRANSFER -> new IrOp.PsrTransfer(true, false, 0, -1, 0, false, 0, c);
            case IrOp.Kind.LOAD -> new IrOp.Load(0, 1, -1, imm, 4, false, false, false, false, c);
            case IrOp.Kind.STORE -> new IrOp.Store(0, -1, 1, -1, imm, 4, false, false, false, c);
            case IrOp.Kind.DOUBLE_TRANSFER -> new IrOp.DoubleTransfer(true, 0, 1, 2, -1, imm, false, false, c);
            case IrOp.Kind.SWAP -> new IrOp.Swap(0, 1, -1, 2, -1, 4, c);
            case IrOp.Kind.LOAD_LITERAL -> new IrOp.LoadLiteral(0, 0, c);
            case IrOp.Kind.MULTIPLE_TRANSFER ->
                    new IrOp.MultipleTransfer(true, 0, 1, false, -1, false, BlockTransferMode.IA, false, c);
            case IrOp.Kind.BRANCH -> new IrOp.Branch(0, 0, false, c, InstructionSet.ARM);
            case IrOp.Kind.BRANCH_EXCHANGE -> new IrOp.BranchExchange(0, -1, false, 0, c);
            case IrOp.Kind.THUMB_BL_PREFIX -> new IrOp.ThumbBlPrefix(0, 0, c);
            case IrOp.Kind.THUMB_BL_SUFFIX -> new IrOp.ThumbBlSuffix(0, 0, false, c);
            case IrOp.Kind.PUSH -> new IrOp.Push(0, false, c);
            case IrOp.Kind.POP -> new IrOp.Pop(0, false, c);
            case IrOp.Kind.SWI -> new IrOp.Swi(0, c);
            case IrOp.Kind.COPROCESSOR -> new IrOp.Coprocessor(true, 15, 0, 0, 0, 0, 0, 0, c);
            case IrOp.Kind.UNDEFINED -> new IrOp.Undefined(0, c);
            case IrOp.Kind.CYCLE -> new IrOp.Cycle(0);
            case IrOp.Kind.FETCH -> new IrOp.Fetch(0, 4);
            case IrOp.Kind.PARALLEL_ALU ->
                    new IrOp.ParallelAlu(ParallelAluOp.ADD16, ParallelAluVariant.SIGNED, 0, 1, 2, c);
            case IrOp.Kind.SEL -> new IrOp.Sel(0, 1, 2, c);
            case IrOp.Kind.SATURATE -> new IrOp.Saturate(0, 8, false, false, imm, c);
            case IrOp.Kind.ABS_DIFF_SUM -> new IrOp.AbsDiffSum(0, 1, 2, -1, c);
            case IrOp.Kind.LOAD_EXCLUSIVE -> new IrOp.LoadExclusive(0, 1, 0, 4, c);
            case IrOp.Kind.STORE_EXCLUSIVE -> new IrOp.StoreExclusive(0, 1, 2, 0, 4, c);
            case IrOp.Kind.CLEAR_EXCLUSIVE -> new IrOp.ClearExclusive(c);
            case IrOp.Kind.CHANGE_PROCESSOR_STATE ->
                    new IrOp.ChangeProcessorState(false, 0, false, false, false, false, false, c);
            case IrOp.Kind.SET_ENDIANNESS -> new IrOp.SetEndianness(false, c);
            case IrOp.Kind.STORE_RETURN_STATE -> new IrOp.StoreReturnState(0x13, BlockTransferMode.DB, false, 0, c);
            case IrOp.Kind.RETURN_FROM_EXCEPTION -> new IrOp.ReturnFromException(0, BlockTransferMode.IA, false, 0, c);
            case IrOp.Kind.WAIT_FOR_INTERRUPT -> new IrOp.WaitForInterrupt(c);
            case IrOp.Kind.MOVE_TOP -> new IrOp.MoveTop(0, 0, c);
            case IrOp.Kind.MEMORY_BARRIER -> new IrOp.MemoryBarrier(c);
            case IrOp.Kind.SET_IT_STATE -> new IrOp.SetItState(0, c);
            case IrOp.Kind.TABLE_BRANCH -> new IrOp.TableBranch(0, -1, 1, -1, 0, false, c);
            case IrOp.Kind.COMPARE_BRANCH_ZERO -> new IrOp.CompareBranchZero(0, 0, false, c);
            case IrOp.Kind.BIT_FIELD_EXTRACT -> new IrOp.BitFieldExtract(0, 1, 0, 8, false, c);
            case IrOp.Kind.BIT_FIELD_INSERT -> new IrOp.BitFieldInsert(0, 1, 0, 8, c);
            case IrOp.Kind.BIT_REVERSE -> new IrOp.BitReverse(0, 1, c);
            case IrOp.Kind.DIVIDE -> new IrOp.Divide(0, 1, 2, false, c);
            case IrOp.Kind.VFP_ALU -> new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 0, 1, 2, c);
            case IrOp.Kind.VFP_MOVE_IMMEDIATE -> new IrOp.VfpMoveImmediate(false, 0, 0L, c);
            case IrOp.Kind.VFP_COMPARE -> new IrOp.VfpCompare(false, false, false, 0, 1, c);
            case IrOp.Kind.VFP_CONVERT -> new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_F64, 0, 1, c);
            case IrOp.Kind.VFP_LOAD -> new IrOp.VfpLoad(false, 0, 1, -1, 0, c);
            case IrOp.Kind.VFP_STORE -> new IrOp.VfpStore(false, 0, 1, -1, 0, c);
            case IrOp.Kind.VFP_MULTIPLE_TRANSFER ->
                    new IrOp.VfpMultipleTransfer(true, false, 0, -1, 0, 1, false, false, c);
            case IrOp.Kind.VFP_CORE_TRANSFER -> new IrOp.VfpCoreTransfer(true, 0, 0, false, c);
            case IrOp.Kind.VFP_CORE_PAIR_TRANSFER -> new IrOp.VfpCorePairTransfer(true, 0, 1, 0, c);
            case IrOp.Kind.VFP_SYSTEM_TRANSFER -> new IrOp.VfpSystemTransfer(true, 0, c);
            case IrOp.Kind.M_PROFILE_SYSTEM_REGISTER -> new IrOp.MProfileSystemRegister(true, 0, 0, c);
            case IrOp.Kind.BREAKPOINT -> new IrOp.Breakpoint(0);
            case IrOp.Kind.COPROCESSOR_DOUBLE -> new IrOp.CoprocessorDouble(true, 15, 0, 0, 0, 1, 0, c);
            case IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE -> new IrOp.VfpCorePairTransferSingle(true, 0, 1, 0, c);
            case IrOp.Kind.VFP_CONVERT_FIXED -> new IrOp.VfpConvertFixed(false, false, false, true, 0, 0, c);
            case IrOp.Kind.DSP_DUAL_MULTIPLY -> new IrOp.DspDualMultiply(0, 1, 2, 15, false, false, false, c);
            case IrOp.Kind.DSP_TOP_WORD_MULTIPLY -> new IrOp.DspTopWordMultiply(0, 1, 2, 15, false, false, c);
            case IrOp.Kind.HVC -> new IrOp.Hvc(0, c);
            case IrOp.Kind.SMC -> new IrOp.Smc(0, c);
            case IrOp.Kind.ERET -> new IrOp.Eret(c);
            case IrOp.Kind.MRS_BANK -> new IrOp.MrsBank(0, CpuMode.FIQ, 8, false, false, c);
            case IrOp.Kind.MSR_BANK -> new IrOp.MsrBank(0, CpuMode.FIQ, 8, false, false, c);
            case IrOp.Kind.NEON_THREE_SAME -> new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 0, 0, 1, 2);
            case IrOp.Kind.NEON_LOAD_STORE_MULTIPLE -> new IrOp.NeonLoadStoreMultiple(true, 0, 1, 15, 2, 1, 1, 1);
            case IrOp.Kind.NEON_LOAD_STORE_SINGLE -> new IrOp.NeonLoadStoreSingle(true, 0, 1, 15, 2, 1, 1, 0);
            case IrOp.Kind.NEON_LOAD_ALL_LANES -> new IrOp.NeonLoadAllLanes(0, 1, 15, 2, 1, 1, false);
            case IrOp.Kind.NEON_PAIRWISE -> new IrOp.NeonPairwise(AdvSimdPairwiseOp.ADD, 2, 0, 1, 2);
            case IrOp.Kind.NEON_FP_THREE_SAME -> new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.ADD, false, 2, 0, 1, 2);
            case IrOp.Kind.NEON_FP_PAIRWISE -> new IrOp.NeonFpPairwise(AdvSimdFpPairwiseOp.ADD, 2, 0, 1, 2);
            case IrOp.Kind.NEON_SHIFT_IMMEDIATE ->
                    new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SSHR, false, 0, 1, 0, 1);
            case IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE ->
                    new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SHRN, 0, 1, 0, 2);
            case IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE ->
                    new IrOp.NeonShiftWidenImmediate(AdvSimdShiftWidenOp.SSHLL, 0, 0, 0, 2);
            case IrOp.Kind.NEON_CONVERT_FIXED_POINT ->
                    new IrOp.NeonConvertFixedPoint(false, 2, 1, true, true, 0, 1);
            case IrOp.Kind.NEON_MODIFIED_IMMEDIATE ->
                    new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xFFL, 0);
            case IrOp.Kind.NEON_WIDENING -> new IrOp.NeonWidening(AdvSimdWideningOp.SADDL, 0, 0, 1, 2);
            case IrOp.Kind.NEON_WIDE -> new IrOp.NeonWide(AdvSimdWideOp.SADDW, 0, 0, 2, 1);
            case IrOp.Kind.NEON_NARROW -> new IrOp.NeonNarrow(AdvSimdNarrowOp.ADDHN, 0, 0, 2, 4);
            default -> throw new AssertionError("kind sem sampleOp: " + kind);
        };
    }
}
