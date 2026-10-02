package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.MveFpOp;
import dev.vitorsilverio.armjitter.ir.MveIntegerOp;
import dev.vitorsilverio.armjitter.ir.MveMoveOp;
import dev.vitorsilverio.armjitter.ir.MvePredicationOp;
import dev.vitorsilverio.armjitter.ir.MveReductionOp;
import dev.vitorsilverio.armjitter.ir.NeonCryptoOp;
import dev.vitorsilverio.armjitter.ir.NeonFpOp;
import dev.vitorsilverio.armjitter.ir.NeonIntegerOp;
import dev.vitorsilverio.armjitter.ir.NeonMoveOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;
import dev.vitorsilverio.armjitter.memory.mpu.PmsaAccessException;
import dev.vitorsilverio.armjitter.memory.mpu.Pmsav8AccessException;

/// Orquestra a execução interpretada de um bloco IR.
///
/// Esta é a única implementação da semântica das instruções: tanto o caminho JIT
/// (blocos compilados) quanto o interpretador frio ({@link dev.vitorsilverio.armjitter.core.ArmInterpreter})
/// a utilizam, de modo que correções de comportamento vivem em um único lugar.
public final class IrBlockExecutor {
    private final IrAluExecutor alu;
    private final IrMemoryExecutor memory;
    private final IrBranchExecutor branch;
    private final IrTransferExecutor transfer;
    private final IrSystemExecutor system;
    private final IrCycleExecutor cycle;
    private final IrVfpExecutor vfp;
    private final IrNeonExecutor neon;

    /// Cria um executor para a arquitetura informada.
    public IrBlockExecutor(ArmArchitecture architecture) {
        IrExecutionSupport support = new IrExecutionSupport(architecture);
        this.alu = new IrAluExecutor(support);
        this.memory = new IrMemoryExecutor(support);
        this.branch = new IrBranchExecutor(support);
        this.transfer = new IrTransferExecutor(support);
        this.system = new IrSystemExecutor(support);
        this.cycle = new IrCycleExecutor();
        this.neon = new IrNeonExecutor(support);
        this.vfp = new IrVfpExecutor(support, neon);
    }

    /// Interpreta um bloco IR e devolve os ciclos internos (`IrOp.Cycle`) consumidos.
    ///
    /// B4.1.3 (RFC-SOFTMMU §3): uma {@link MemoryTranslationException} lançada por um `AddressSpace`
    /// traduzido (`TranslatingAddressSpace`) no meio do laço é capturada aqui — o `try` cerca o laço
    /// inteiro sem custo no caminho quente (uma região `try` sem lançamento não paga nada na JVM; só
    /// o `throw` em si tem custo, e faltas de tradução já são raras por natureza). No `catch`, o
    /// endereço da instrução faltosa é o do PRÓXIMO `IrOp.Fetch` a partir do índice corrente — cada
    /// instrução termina SEMPRE com `Cycle`+`Fetch` (G4), então o Fetch que ainda não rodou é
    /// exatamente o desta instrução (ver {@link #ownerInstructionAddress}).
    public int execute(IrBlock block, ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;

        // Itera o array cacheado por índice (sem alocar Iterator nem checkIndex por op):
        // este loop roda milhões de vezes, é o frame mais quente do interpretador.
        IrOp[] ops = block.operationsArray();
        // Discriminador pré-resolvido na construção do bloco (task C8, candidato #1): evita a
        // chamada virtual megamórfica de `op.kind()` por op a cada execução — o bloco é imutável
        // pós-lift, então o dispatch já é conhecido e só precisa ser indexado aqui.
        int[] kinds = block.kindsArray();
        int n = ops.length;
        int i = 0;
        try {
            for (; i < n; i++) {
                // Dispatch O(1) por discriminador inteiro (tableswitch), em vez do `switch` por
                // padrão de tipo, cuja varredura linear de `instanceof` era o 2º frame mais quente.
                // O cast em cada case é garantido por IrOp.kind() (ver IrOp.Kind).
                IrOp op = ops[i];
                switch (kinds[i]) {
                case IrOp.Kind.ALU -> pcChanged |= alu.execute(core, (IntegerOp.Alu) op);
                case IrOp.Kind.MULTIPLY -> alu.executeMultiply(core, (IntegerOp.Multiply) op);
                case IrOp.Kind.LONG_MULTIPLY -> alu.executeLongMultiply(core, (IntegerOp.LongMultiply) op);
                case IrOp.Kind.SATURATING -> alu.executeSaturating(core, (IntegerOp.Saturating) op);
                case IrOp.Kind.CRC32 -> alu.executeCrc32(core, (IntegerOp.Crc32) op);
                case IrOp.Kind.DSP_MULTIPLY -> alu.executeDspMultiply(core, (IntegerOp.DspMultiply) op);
                case IrOp.Kind.PARALLEL_ALU -> alu.executeParallelAlu(core, (IntegerOp.ParallelAlu) op);
                case IrOp.Kind.SEL -> alu.executeSel(core, (IntegerOp.Sel) op);
                case IrOp.Kind.SATURATE -> alu.executeSaturate(core, (IntegerOp.Saturate) op);
                case IrOp.Kind.ABS_DIFF_SUM -> alu.executeAbsDiffSum(core, (IntegerOp.AbsDiffSum) op);
                case IrOp.Kind.PSR_TRANSFER -> system.executePsrTransfer(core, (SystemOp.PsrTransfer) op);
                case IrOp.Kind.LOAD -> pcChanged |= memory.executeLoad(core, (MemoryOp.Load) op);
                case IrOp.Kind.STORE -> memory.executeStore(core, (MemoryOp.Store) op);
                case IrOp.Kind.LOAD_EXCLUSIVE -> memory.executeLoadExclusive(core, (MemoryOp.LoadExclusive) op);
                case IrOp.Kind.STORE_EXCLUSIVE -> memory.executeStoreExclusive(core, (MemoryOp.StoreExclusive) op);
                case IrOp.Kind.CLEAR_EXCLUSIVE -> memory.executeClearExclusive(core, (MemoryOp.ClearExclusive) op);
                case IrOp.Kind.DOUBLE_TRANSFER -> pcChanged |= memory.executeDoubleTransfer(core, (MemoryOp.DoubleTransfer) op);
                case IrOp.Kind.SWAP -> pcChanged |= memory.executeSwap(core, (MemoryOp.Swap) op);
                case IrOp.Kind.LOAD_LITERAL -> pcChanged |= memory.executeLoadLiteral(core, (MemoryOp.LoadLiteral) op);
                case IrOp.Kind.MULTIPLE_TRANSFER -> pcChanged |= transfer.executeMultipleTransfer(core, (MemoryOp.MultipleTransfer) op);
                case IrOp.Kind.BRANCH -> pcChanged |= branch.executeBranch(core, (BranchOp.Branch) op);
                case IrOp.Kind.BRANCH_EXCHANGE -> pcChanged |= branch.executeBranchExchange(core, (BranchOp.BranchExchange) op);
                case IrOp.Kind.THUMB_BL_PREFIX -> branch.executeThumbBlPrefix(core, (BranchOp.ThumbBlPrefix) op);
                case IrOp.Kind.THUMB_BL_SUFFIX -> pcChanged |= branch.executeThumbBlSuffix(core, (BranchOp.ThumbBlSuffix) op);
                case IrOp.Kind.PUSH -> transfer.executePush(core, (MemoryOp.Push) op);
                case IrOp.Kind.POP -> pcChanged |= transfer.executePop(core, (MemoryOp.Pop) op);
                case IrOp.Kind.SWI -> pcChanged |= system.executeSwi(core, (SystemOp.Swi) op, block.endPc());
                case IrOp.Kind.HVC -> pcChanged |= system.executeHvc(core, (SystemOp.Hvc) op, block.endPc());
                case IrOp.Kind.SMC -> pcChanged |= system.executeSmc(core, (SystemOp.Smc) op, block.endPc());
                case IrOp.Kind.ERET -> pcChanged |= system.executeEret(core, (SystemOp.Eret) op, block.endPc());
                case IrOp.Kind.MRS_BANK -> pcChanged |= system.executeMrsBank(core, (SystemOp.MrsBank) op, block.endPc());
                case IrOp.Kind.MSR_BANK -> pcChanged |= system.executeMsrBank(core, (SystemOp.MsrBank) op, block.endPc());
                case IrOp.Kind.COPROCESSOR -> pcChanged |= system.executeCoprocessor(core, (SystemOp.Coprocessor) op);
                case IrOp.Kind.COPROCESSOR_DOUBLE -> pcChanged |= system.executeCoprocessorDouble(core, (SystemOp.CoprocessorDouble) op);
                case IrOp.Kind.UNDEFINED -> pcChanged |= system.executeUndefined(core, (SystemOp.Undefined) op);
                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);
                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);
                case IrOp.Kind.CHANGE_PROCESSOR_STATE -> system.executeChangeProcessorState(core, (SystemOp.ChangeProcessorState) op);
                case IrOp.Kind.SET_ENDIANNESS -> system.executeSetEndianness(core, (SystemOp.SetEndianness) op);
                case IrOp.Kind.STORE_RETURN_STATE -> pcChanged |= transfer.executeStoreReturnState(core, (SystemOp.StoreReturnState) op);
                case IrOp.Kind.RETURN_FROM_EXCEPTION -> pcChanged |= transfer.executeReturnFromException(core, (SystemOp.ReturnFromException) op);
                case IrOp.Kind.WAIT_FOR_INTERRUPT -> system.executeWaitForInterrupt(core, (SystemOp.WaitForInterrupt) op);
                case IrOp.Kind.MOVE_TOP -> alu.executeMoveTop(core, (IntegerOp.MoveTop) op);
                case IrOp.Kind.MEMORY_BARRIER -> system.executeMemoryBarrier(core, (SystemOp.MemoryBarrier) op);
                case IrOp.Kind.SET_IT_STATE -> system.executeSetItState(core, (SystemOp.SetItState) op);
                case IrOp.Kind.TABLE_BRANCH -> pcChanged |= branch.executeTableBranch(core, (BranchOp.TableBranch) op);
                case IrOp.Kind.COMPARE_BRANCH_ZERO -> pcChanged |= branch.executeCompareBranchZero(core, (BranchOp.CompareBranchZero) op);
                case IrOp.Kind.BIT_FIELD_EXTRACT -> alu.executeBitFieldExtract(core, (IntegerOp.BitFieldExtract) op);
                case IrOp.Kind.BIT_FIELD_INSERT -> alu.executeBitFieldInsert(core, (IntegerOp.BitFieldInsert) op);
                case IrOp.Kind.BIT_REVERSE -> alu.executeBitReverse(core, (IntegerOp.BitReverse) op);
                case IrOp.Kind.DIVIDE -> alu.executeDivide(core, (IntegerOp.Divide) op);
                case IrOp.Kind.NEON_THREE_SAME -> vfp.executeNeonThreeSame(core, (NeonIntegerOp.ThreeSame) op);
                case IrOp.Kind.NEON_LOAD_STORE_MULTIPLE -> neon.executeNeonLoadStoreMultiple(core, (NeonMoveOp.LoadStoreMultiple) op);
                case IrOp.Kind.NEON_LOAD_STORE_SINGLE -> neon.executeNeonLoadStoreSingle(core, (NeonMoveOp.LoadStoreSingle) op);
                case IrOp.Kind.NEON_LOAD_ALL_LANES -> neon.executeNeonLoadAllLanes(core, (NeonMoveOp.LoadAllLanes) op);
                case IrOp.Kind.NEON_PAIRWISE -> neon.executeNeonPairwise(core, (NeonIntegerOp.Pairwise) op);
                case IrOp.Kind.NEON_FP_THREE_SAME -> neon.executeNeonFpThreeSame(core, (NeonFpOp.FpThreeSame) op);
                case IrOp.Kind.NEON_FP_PAIRWISE -> neon.executeNeonFpPairwise(core, (NeonFpOp.FpPairwise) op);
                case IrOp.Kind.NEON_SHIFT_IMMEDIATE -> neon.executeNeonShiftImmediate(core, (NeonIntegerOp.ShiftImmediate) op);
                case IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE -> neon.executeNeonShiftNarrowImmediate(core, (NeonIntegerOp.ShiftNarrowImmediate) op);
                case IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE -> neon.executeNeonShiftWidenImmediate(core, (NeonIntegerOp.ShiftWidenImmediate) op);
                case IrOp.Kind.NEON_CONVERT_FIXED_POINT -> neon.executeNeonConvertFixedPoint(core, (NeonFpOp.ConvertFixedPoint) op);
                case IrOp.Kind.NEON_MODIFIED_IMMEDIATE -> neon.executeNeonModifiedImmediate(core, (NeonMoveOp.ModifiedImmediate) op);
                case IrOp.Kind.NEON_WIDENING -> neon.executeNeonWidening(core, (NeonIntegerOp.Widening) op);
                case IrOp.Kind.NEON_WIDE -> neon.executeNeonWide(core, (NeonIntegerOp.Wide) op);
                case IrOp.Kind.NEON_NARROW -> neon.executeNeonNarrow(core, (NeonIntegerOp.Narrow) op);
                case IrOp.Kind.NEON_THREE_SAME_BY_ELEMENT -> neon.executeNeonThreeSameByElement(core, (NeonIntegerOp.ThreeSameByElement) op);
                case IrOp.Kind.NEON_WIDENING_BY_ELEMENT -> neon.executeNeonWideningByElement(core, (NeonIntegerOp.WideningByElement) op);
                case IrOp.Kind.NEON_FP_THREE_SAME_BY_ELEMENT -> neon.executeNeonFpThreeSameByElement(core, (NeonFpOp.FpThreeSameByElement) op);
                case IrOp.Kind.NEON_UNARY -> neon.executeNeonUnary(core, (NeonIntegerOp.Unary) op);
                case IrOp.Kind.NEON_NARROW_UNARY -> neon.executeNeonNarrowUnary(core, (NeonIntegerOp.NarrowUnary) op);
                case IrOp.Kind.NEON_FP_UNARY -> neon.executeNeonFpUnary(core, (NeonFpOp.FpUnary) op);
                case IrOp.Kind.NEON_COMPLEX -> neon.executeNeonComplex(core, (NeonFpOp.Complex) op);
                case IrOp.Kind.NEON_COMPLEX_BY_ELEMENT -> neon.executeNeonComplexByElement(core, (NeonFpOp.ComplexByElement) op);
                case IrOp.Kind.NEON_DOT_PRODUCT -> neon.executeNeonDotProduct(core, (NeonIntegerOp.DotProduct) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT -> neon.executeNeonDotProductByElement(core, (NeonIntegerOp.DotProductByElement) op);
                case IrOp.Kind.NEON_SWAP_PERMUTE -> neon.executeNeonSwapPermute(core, (NeonMoveOp.SwapPermute) op);
                case IrOp.Kind.NEON_EXTRACT -> neon.executeNeonExtract(core, (NeonMoveOp.Extract) op);
                case IrOp.Kind.NEON_TABLE_LOOKUP -> neon.executeNeonTableLookup(core, (NeonMoveOp.TableLookup) op);
                case IrOp.Kind.NEON_DUPLICATE_SCALAR -> neon.executeNeonDuplicateScalar(core, (NeonMoveOp.DuplicateScalar) op);
                case IrOp.Kind.NEON_CRYPTO_AES -> neon.executeNeonCryptoAes(core, (NeonCryptoOp.Aes) op);
                case IrOp.Kind.NEON_CRYPTO_SHA -> neon.executeNeonCryptoSha(core, (NeonCryptoOp.Sha) op);
                case IrOp.Kind.NEON_CRYPTO_SHA_THREE_REGISTER -> neon.executeNeonCryptoShaThree(core, (NeonCryptoOp.ShaThree) op);
                case IrOp.Kind.NEON_FP_CONVERT_PRECISION -> neon.executeNeonFpConvertPrecision(core, (NeonFpOp.FpConvertPrecision) op);
                case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE -> neon.executeNeonMatrixMultiplyAccumulate(core, (NeonIntegerOp.MatrixMultiplyAccumulate) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG -> neon.executeNeonFusedMultiplyAddLong(core, (NeonFpOp.FusedMultiplyAddLong) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT -> neon.executeNeonFusedMultiplyAddLongByElement(core, (NeonFpOp.FusedMultiplyAddLongByElement) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BFLOAT16 -> neon.executeNeonDotProductBFloat16(core, (NeonFpOp.DotProductBFloat16) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16 -> neon.executeNeonDotProductByElementBFloat16(core, (NeonFpOp.DotProductByElementBFloat16) op);
                case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 -> neon.executeNeonMatrixMultiplyAccumulateBFloat16(core, (NeonFpOp.MatrixMultiplyAccumulateBFloat16) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16 -> neon.executeNeonFusedMultiplyAddLongBFloat16(core, (NeonFpOp.FusedMultiplyAddLongBFloat16) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 -> neon.executeNeonFusedMultiplyAddLongByElementBFloat16(core, (NeonFpOp.FusedMultiplyAddLongByElementBFloat16) op);
                case IrOp.Kind.VFP_ALU -> vfp.executeVfpAlu(core, (VfpOp.Alu) op);
                case IrOp.Kind.VFP_MOVE_IMMEDIATE -> vfp.executeVfpMoveImmediate(core, (VfpOp.MoveImmediate) op);
                case IrOp.Kind.VFP_COMPARE -> vfp.executeVfpCompare(core, (VfpOp.Compare) op);
                case IrOp.Kind.VFP_CONVERT -> vfp.executeVfpConvert(core, (VfpOp.Convert) op);
                case IrOp.Kind.VFP_LOAD -> vfp.executeVfpLoad(core, (VfpOp.Load) op);
                case IrOp.Kind.VFP_STORE -> vfp.executeVfpStore(core, (VfpOp.Store) op);
                case IrOp.Kind.VFP_MULTIPLE_TRANSFER -> vfp.executeVfpMultipleTransfer(core, (VfpOp.MultipleTransfer) op);
                case IrOp.Kind.VFP_CORE_TRANSFER -> vfp.executeVfpCoreTransfer(core, (VfpOp.CoreTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER -> vfp.executeVfpCorePairTransfer(core, (VfpOp.CorePairTransfer) op);
                case IrOp.Kind.VFP_SYSTEM_TRANSFER -> vfp.executeVfpSystemTransfer(core, (VfpOp.SystemTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE -> vfp.executeVfpCorePairTransferSingle(core, (VfpOp.CorePairTransferSingle) op);
                case IrOp.Kind.VFP_CONVERT_FIXED -> vfp.executeVfpConvertFixed(core, (VfpOp.ConvertFixed) op);
                case IrOp.Kind.VFP_SELECT -> vfp.executeVfpSelect(core, (VfpOp.Select) op);
                case IrOp.Kind.VFP_ROUND -> vfp.executeVfpRound(core, (VfpOp.Round) op);
                case IrOp.Kind.VFP_CONVERT_ROUNDED -> vfp.executeVfpConvertRounded(core, (VfpOp.ConvertRounded) op);
                case IrOp.Kind.VFP_MOVE_HALF_LANE -> vfp.executeVfpMoveHalfLane(core, (VfpOp.MoveHalfLane) op);
                case IrOp.Kind.VFP_ALU_HALF -> vfp.executeVfpAluHalf(core, (VfpOp.AluHalf) op);
                case IrOp.Kind.VFP_MOVE_IMMEDIATE_HALF -> vfp.executeVfpMoveImmediateHalf(core, (VfpOp.MoveImmediateHalf) op);
                case IrOp.Kind.VFP_COMPARE_HALF -> vfp.executeVfpCompareHalf(core, (VfpOp.CompareHalf) op);
                case IrOp.Kind.VFP_SELECT_HALF -> vfp.executeVfpSelectHalf(core, (VfpOp.SelectHalf) op);
                case IrOp.Kind.VFP_ROUND_HALF -> vfp.executeVfpRoundHalf(core, (VfpOp.RoundHalf) op);
                case IrOp.Kind.VFP_CONVERT_ROUNDED_HALF -> vfp.executeVfpConvertRoundedHalf(core, (VfpOp.ConvertRoundedHalf) op);
                case IrOp.Kind.VFP_CONVERT_FIXED_HALF -> vfp.executeVfpConvertFixedHalf(core, (VfpOp.ConvertFixedHalf) op);
                case IrOp.Kind.VFP_LOAD_HALF -> vfp.executeVfpLoadHalf(core, (VfpOp.LoadHalf) op);
                case IrOp.Kind.VFP_STORE_HALF -> vfp.executeVfpStoreHalf(core, (VfpOp.StoreHalf) op);
                case IrOp.Kind.VFP_CONVERT_HALF_PRECISION -> vfp.executeVfpConvertHalfPrecision(core, (VfpOp.ConvertHalfPrecision) op);
                case IrOp.Kind.VFP_JAVASCRIPT_CONVERT -> vfp.executeVfpJavascriptConvert(core, (VfpOp.JavascriptConvert) op);
                case IrOp.Kind.M_PROFILE_SYSTEM_REGISTER -> system.executeMProfileSystemRegister(core, (SystemOp.MProfileSystemRegister) op);
                case IrOp.Kind.BREAKPOINT -> pcChanged |= system.executeBreakpoint(core, (SystemOp.Breakpoint) op, block.endPc());
                case IrOp.Kind.DSP_DUAL_MULTIPLY -> alu.executeDspDualMultiply(core, (IntegerOp.DspDualMultiply) op);
                case IrOp.Kind.DSP_TOP_WORD_MULTIPLY -> alu.executeDspTopWordMultiply(core, (IntegerOp.DspTopWordMultiply) op);
                case IrOp.Kind.NOCP -> pcChanged |= system.executeNocp(core, (SystemOp.Nocp) op);
                case IrOp.Kind.VFP_SYSREG_MEMORY_TRANSFER -> vfp.executeVfpSysregMemoryTransfer(core, (VfpOp.SysregMemoryTransfer) op);
                case IrOp.Kind.SECURE_GATEWAY -> system.executeSecureGateway(core, (SystemOp.SecureGateway) op);
                case IrOp.Kind.SECURE_BRANCH_EXCHANGE -> pcChanged |= branch.executeSecureBranchExchange(core, (BranchOp.SecureBranchExchange) op);
                case IrOp.Kind.VLLDM_VLSTM -> pcChanged |= system.executeVlldmVlstm(core, (VfpOp.VlldmVlstm) op);
                case IrOp.Kind.VSCCLRM -> vfp.executeVscclrm(core, (VfpOp.Vscclrm) op);
                case IrOp.Kind.LOOP_START -> pcChanged |= branch.executeLoopStart(core, (BranchOp.LoopStart) op);
                case IrOp.Kind.LOOP_END -> pcChanged |= branch.executeLoopEnd(core, (BranchOp.LoopEnd) op);
                case IrOp.Kind.VPST -> pcChanged |= system.executeVpst(core, (MvePredicationOp.Vpst) op);
                case IrOp.Kind.VPNOT -> pcChanged |= system.executeVpnot(core, (MvePredicationOp.Vpnot) op);
                case IrOp.Kind.VPSEL -> pcChanged |= system.executeVpsel(core, (MvePredicationOp.Vpsel) op);
                // VCTP (B16.15): beatwise, pode faultar igual VPST/VPNOT/VPSEL.
                case IrOp.Kind.VCTP -> pcChanged |= system.executeVctp(core, (MvePredicationOp.Vctp) op);
                case IrOp.Kind.LOOP_CLEAR_TAIL_PREDICATION ->
                        system.executeLctp(core, (MvePredicationOp.LoopClearTailPredication) op);
                case IrOp.Kind.CLEAR_MULTIPLE -> system.executeClrm(core, (IntegerOp.ClearMultiple) op);
                // MVE_WIDE_SHIFT (B16.16): escalar (GPRs + APSR.Q), não beatwise, nunca troca o PC.
                case IrOp.Kind.MVE_WIDE_SHIFT -> system.executeMveWideShift(core, (MveIntegerOp.WideShift) op);
                // ADVANCE_VPT (B16.2): pulado quando a instrução MVE anterior no MESMO bloco já
                // mudou o PC (fault de ECI reservado) — ver Javadoc de IrSystemExecutor#executeAdvanceVpt.
                case IrOp.Kind.ADVANCE_VPT -> {
                    if (!pcChanged) {
                        system.executeAdvanceVpt(core, (MvePredicationOp.AdvanceVpt) op);
                    }
                }
                case IrOp.Kind.VPR_TRANSFER -> system.executeVprTransfer(core, (MvePredicationOp.VprTransfer) op);
                // MVE_LOAD_STORE (B16.3): pode faultar (ECI reservado) igual VPST/VPNOT/VPSEL —
                // mesmo pcChanged-gate para o ADVANCE_VPT seguinte.
                case IrOp.Kind.MVE_LOAD_STORE -> pcChanged |= system.executeMveLoadStore(core, (MveMoveOp.LoadStore) op);
                case IrOp.Kind.MVE_WIDENING_LOAD_STORE ->
                        pcChanged |= system.executeMveWideningLoadStore(core, (MveMoveOp.WideningLoadStore) op);
                // B16.5: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_GATHER_SCATTER_OFFSET ->
                        pcChanged |= system.executeMveGatherScatterOffset(core, (MveMoveOp.GatherScatterOffset) op);
                case IrOp.Kind.MVE_GATHER_SCATTER_IMMEDIATE ->
                        pcChanged |= system.executeMveGatherScatterImmediate(core, (MveMoveOp.GatherScatterImmediate) op);
                case IrOp.Kind.MVE_INTERLEAVED_LOAD_STORE ->
                        pcChanged |= system.executeMveInterleavedLoadStore(core, (MveMoveOp.InterleavedLoadStore) op);
                case IrOp.Kind.MVE_INCREMENT_DUP ->
                        pcChanged |= system.executeMveIncrementDup(core, (MveMoveOp.IncrementDup) op);
                case IrOp.Kind.MVE_WRAPPING_INCREMENT_DUP ->
                        pcChanged |= system.executeMveWrappingIncrementDup(core, (MveMoveOp.WrappingIncrementDup) op);
                // B16.6: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_2OP -> pcChanged |= system.executeMveVector2Op(core, (MveIntegerOp.Vector2Op) op);
                case IrOp.Kind.MVE_VECTOR_2OP_WIDENING ->
                        pcChanged |= system.executeMveVector2OpWidening(core, (MveIntegerOp.Vector2OpWidening) op);
                case IrOp.Kind.MVE_VECTOR_CARRY -> pcChanged |= system.executeMveVectorCarry(core, (MveIntegerOp.VectorCarry) op);
                case IrOp.Kind.MVE_VECTOR_COMPLEX_ADD ->
                        pcChanged |= system.executeMveVectorComplexAdd(core, (MveIntegerOp.VectorComplexAdd) op);
                // B16.7: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_ABS_ACCUMULATE ->
                        pcChanged |= system.executeMveVectorAbsAccumulate(core, (MveIntegerOp.VectorAbsAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_FP_ABS_ACCUMULATE ->
                        pcChanged |= system.executeMveVectorFpAbsAccumulate(core, (MveFpOp.VectorFpAbsAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED -> pcChanged |= system.executeMveVectorShiftWidenInterleaved(
                        core, (MveIntegerOp.VectorShiftWidenInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_NARROW_INTERLEAVED ->
                        pcChanged |= system.executeMveVectorNarrowInterleaved(core, (MveIntegerOp.VectorNarrowInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT_PRECISION ->
                        pcChanged |= system.executeMveVectorFpConvertPrecision(core, (MveFpOp.VectorFpConvertPrecision) op);
                // B16.7 sub-família 2: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY ->
                        pcChanged |= system.executeMveVectorFpComplexMultiply(core, (MveFpOp.VectorFpComplexMultiply) op);
                case IrOp.Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH ->
                        pcChanged |= system.executeMveVectorDualMultiplyAddHigh(core, (MveIntegerOp.VectorDualMultiplyAddHigh) op);
                case IrOp.Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY -> pcChanged |= system
                        .executeMveVectorDoublingWideningMultiply(core, (MveIntegerOp.VectorDoublingWideningMultiply) op);
                // B16.7 sub-família 3: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_TWO_OP ->
                        pcChanged |= system.executeMveVectorFpTwoOp(core, (MveFpOp.VectorFpTwoOp) op);
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_ADD ->
                        pcChanged |= system.executeMveVectorFpComplexAdd(core, (MveFpOp.VectorFpComplexAdd) op);
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorFpComplexMultiplyAccumulate(core, (MveFpOp.VectorFpComplexMultiplyAccumulate) op);
                // B16.8: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_COMPARE ->
                        pcChanged |= system.executeMveVectorCompare(core, (MvePredicationOp.VectorCompare) op);
                case IrOp.Kind.MVE_VECTOR_COMPARE_SCALAR ->
                        pcChanged |= system.executeMveVectorCompareScalar(core, (MvePredicationOp.VectorCompareScalar) op);
                // B16.9: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SCALAR ->
                        pcChanged |= system.executeMveVectorScalar(core, (MveIntegerOp.VectorScalar) op);
                case IrOp.Kind.MVE_VECTOR_SCALAR_WIDENING ->
                        pcChanged |= system.executeMveVectorScalarWidening(core, (MveIntegerOp.VectorScalarWidening) op);
                case IrOp.Kind.MVE_VECTOR_FP_SCALAR ->
                        pcChanged |= system.executeMveVectorFpScalar(core, (MveFpOp.VectorFpScalar) op);
                case IrOp.Kind.MVE_VECTOR_FP_SCALAR_FMA ->
                        pcChanged |= system.executeMveVectorFpScalarFma(core, (MveFpOp.VectorFpScalarFma) op);
                case IrOp.Kind.MVE_VECTOR_SCALAR_SPECIAL ->
                        pcChanged |= system.executeMveVectorScalarSpecial(core, (MveIntegerOp.VectorScalarSpecial) op);
                // B16.10: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SHIFT_IMMEDIATE ->
                        pcChanged |= system.executeMveVectorShiftImmediate(core, (MveIntegerOp.VectorShiftImmediate) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED -> pcChanged |= system
                        .executeMveVectorShiftWidenImmediateInterleaved(core,
                                (MveIntegerOp.VectorShiftWidenImmediateInterleaved) op);
                // B16.11: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED -> pcChanged |= system
                        .executeMveVectorShiftNarrowImmediateInterleaved(core,
                                (MveIntegerOp.VectorShiftNarrowImmediateInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_LEFT_CARRY ->
                        pcChanged |= system.executeMveVectorShiftLeftCarry(core, (MveIntegerOp.VectorShiftLeftCarry) op);
                // B16.12: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT ->
                        pcChanged |= system.executeMveVectorFpConvert(core, (MveFpOp.VectorFpConvert) op);
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT_FIXED ->
                        pcChanged |= system.executeMveVectorFpConvertFixed(core, (MveFpOp.VectorFpConvertFixed) op);
                // B16.13a: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_UNARY ->
                        pcChanged |= system.executeMveVectorUnary(core, (MveIntegerOp.VectorUnary) op);
                case IrOp.Kind.MVE_VECTOR_FP_UNARY ->
                        pcChanged |= system.executeMveVectorFpUnary(core, (MveFpOp.VectorFpUnary) op);
                case IrOp.Kind.MVE_VECTOR_DUP ->
                        pcChanged |= system.executeMveVectorDup(core, (MveMoveOp.VectorDup) op);
                case IrOp.Kind.MVE_MOVE_LANES_GPR ->
                        pcChanged |= system.executeMveMoveLanesGpr(core, (MveMoveOp.MoveLanesGpr) op);
                case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR ->
                        pcChanged |= system.executeMveVectorAddAcrossVector(core, (MveReductionOp.VectorAddAcrossVector) op);
                case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR_LONG -> pcChanged |= system
                        .executeMveVectorAddAcrossVectorLong(core, (MveReductionOp.VectorAddAcrossVectorLong) op);
                case IrOp.Kind.MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorAbsoluteDifferenceAccumulate(core,
                                (MveReductionOp.VectorAbsoluteDifferenceAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_MODIFIED_IMMEDIATE -> pcChanged |= system
                        .executeMveVectorModifiedImmediate(core, (MveMoveOp.VectorModifiedImmediate) op);
                // B16.13b: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorDualAccumulate(core, (MveReductionOp.VectorDualAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE_LONG -> pcChanged |= system
                        .executeMveVectorDualAccumulateLong(core, (MveReductionOp.VectorDualAccumulateLong) op);
                case IrOp.Kind.MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH -> pcChanged |= system
                        .executeMveVectorRoundingDualAccumulateHigh(core,
                                (MveReductionOp.VectorRoundingDualAccumulateHigh) op);
                case IrOp.Kind.MVE_VECTOR_MIN_MAX_ACROSS_VECTOR -> pcChanged |= system
                        .executeMveVectorMinMaxAcrossVector(core, (MveReductionOp.VectorMinMaxAcrossVector) op);
                case IrOp.Kind.MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR -> pcChanged |= system
                        .executeMveVectorFpMinMaxAcrossVector(core, (MveReductionOp.VectorFpMinMaxAcrossVector) op);
                // ADVANCE_ECI (B16.5): mesmo gate de pcChanged que ADVANCE_VPT usa (pulado quando a
                // instrução anterior no MESMO bloco já mudou o PC por fault de ECI reservado).
                case IrOp.Kind.ADVANCE_ECI -> {
                    if (!pcChanged) {
                        system.executeAdvanceEci(core, (MvePredicationOp.AdvanceEci) op);
                    }
                }
                    default -> throw new IllegalStateException("IrOp kind desconhecido: " + op.kind());
                }
            }
        } catch (MemoryTranslationException fault) {
            core.enterMemoryAbort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        } catch (PmsaAccessException fault) {
            // B20.3: mesmo tratamento acima, catch à parte (Armadilha 4 da B20.3 — classe irmã,
            // nunca subtipo de MemoryTranslationException).
            core.enterPmsaAbort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        } catch (Pmsav8AccessException fault) {
            // B20.7: mesmo tratamento acima, catch à parte (terceira classe irmã).
            core.enterPmsav8Abort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    /// Endereço da instrução dona da op no índice `faultIndex` (B4.1.3): cada instrução termina
    /// SEMPRE com `Cycle`+`Fetch` (G4, ver `StandardIrBuilder`), então o primeiro `Fetch` a partir
    /// de `faultIndex` (inclusive) é o desta instrução — só roda no caminho raro de exceção, sem
    /// custo no laço quente de {@link #execute}.
    private static int ownerInstructionAddress(IrOp[] ops, int[] kinds, int faultIndex) {
        for (int j = faultIndex; j < ops.length; j++) {
            if (kinds[j] == IrOp.Kind.FETCH) {
                return ((IrOp.Fetch) ops[j]).address();
            }
        }
        throw new IllegalStateException("bloco sem IrOp.Fetch após o índice " + faultIndex);
    }

    /// Executor de ALU/multiplicação (task A6): exposto para que o módulo `truffle/` possa
    /// despachar DIRETO a cada método de categoria (ex. `IrAluExecutor#execute`), sem passar pelo
    /// `switch` exaustivo de {@link #executeOp} — ver `AluOpNode`/`MultiplyOpNode`.
    public IrAluExecutor aluExecutor() {
        return alu;
    }

    /// Executor de memória (task A6): ver {@link #aluExecutor()}.
    public IrMemoryExecutor memoryExecutor() {
        return memory;
    }

    /// Executor de branch (task A6): ver {@link #aluExecutor()}.
    public IrBranchExecutor branchExecutor() {
        return branch;
    }

    /// Executor de LDM/STM/PUSH/POP/SRS/RFE (task A6): ver {@link #aluExecutor()}.
    public IrTransferExecutor transferExecutor() {
        return transfer;
    }

    /// Executor de PSR/SWI/coprocessador/sistema (task A6): ver {@link #aluExecutor()}.
    public IrSystemExecutor systemExecutor() {
        return system;
    }

    /// Executor de ciclo/fetch (task A6): ver {@link #aluExecutor()}.
    public IrCycleExecutor cycleExecutor() {
        return cycle;
    }

    /// Executa uma única {@link IrOp} sem o ajuste final de PC, e devolve se o PC foi alterado.
    ///
    /// Usado pela infraestrutura {@link dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy#PER_OP}
    /// para executar ops não suportadas nativamente inline no bytecode JVM gerado.
    ///
    /// @param blockEndPc PC sequencial do fim do bloco (necessário para {@link SystemOp.Swi})
    public boolean executeOp(ArmCore core, IrOp op, int blockEndPc) {
        return switch (op) {
            case IntegerOp.Alu aluOp -> alu.execute(core, aluOp);
            case IntegerOp.Multiply multiply -> { alu.executeMultiply(core, multiply); yield false; }
            case IntegerOp.LongMultiply lm -> { alu.executeLongMultiply(core, lm); yield false; }
            case IntegerOp.Saturating sat -> { alu.executeSaturating(core, sat); yield false; }
            case IntegerOp.Crc32 crc -> { alu.executeCrc32(core, crc); yield false; }
            case IntegerOp.DspMultiply dsp -> { alu.executeDspMultiply(core, dsp); yield false; }
            case IntegerOp.ParallelAlu parallel -> { alu.executeParallelAlu(core, parallel); yield false; }
            case IntegerOp.Sel sel -> { alu.executeSel(core, sel); yield false; }
            case IntegerOp.Saturate saturate -> { alu.executeSaturate(core, saturate); yield false; }
            case IntegerOp.AbsDiffSum usad -> { alu.executeAbsDiffSum(core, usad); yield false; }
            case SystemOp.PsrTransfer psr -> { system.executePsrTransfer(core, psr); yield false; }
            case MemoryOp.LoadLiteral ll -> memory.executeLoadLiteral(core, ll);
            case MemoryOp.Load load -> memory.executeLoad(core, load);
            case MemoryOp.Store store -> { memory.executeStore(core, store); yield false; }
            case MemoryOp.LoadExclusive lex -> { memory.executeLoadExclusive(core, lex); yield false; }
            case MemoryOp.StoreExclusive sex -> { memory.executeStoreExclusive(core, sex); yield false; }
            case MemoryOp.ClearExclusive clrex -> { memory.executeClearExclusive(core, clrex); yield false; }
            case MemoryOp.DoubleTransfer dt -> memory.executeDoubleTransfer(core, dt);
            case MemoryOp.Swap swap -> memory.executeSwap(core, swap);
            case MemoryOp.MultipleTransfer mt -> transfer.executeMultipleTransfer(core, mt);
            case BranchOp.Branch b -> branch.executeBranch(core, b);
            case BranchOp.BranchExchange bx -> branch.executeBranchExchange(core, bx);
            case BranchOp.ThumbBlPrefix prefix -> { branch.executeThumbBlPrefix(core, prefix); yield false; }
            case BranchOp.ThumbBlSuffix suffix -> branch.executeThumbBlSuffix(core, suffix);
            case MemoryOp.Push push -> { transfer.executePush(core, push); yield false; }
            case MemoryOp.Pop pop -> transfer.executePop(core, pop);
            case SystemOp.Swi swi -> system.executeSwi(core, swi, blockEndPc);
            case SystemOp.Hvc hvc -> system.executeHvc(core, hvc, blockEndPc);
            case SystemOp.Smc smc -> system.executeSmc(core, smc, blockEndPc);
            case SystemOp.Eret eret -> system.executeEret(core, eret, blockEndPc);
            case SystemOp.MrsBank mrsBank -> system.executeMrsBank(core, mrsBank, blockEndPc);
            case SystemOp.MsrBank msrBank -> system.executeMsrBank(core, msrBank, blockEndPc);
            case SystemOp.Coprocessor cp -> system.executeCoprocessor(core, cp);
            case SystemOp.CoprocessorDouble cp -> system.executeCoprocessorDouble(core, cp);
            case SystemOp.Undefined undef -> system.executeUndefined(core, undef);
            case IrOp.Cycle cycleOp -> { cycle.executeCycle(cycleOp); yield false; }
            case IrOp.Fetch fetch -> { cycle.executeFetch(core, fetch); yield false; }
            case SystemOp.ChangeProcessorState cps -> { system.executeChangeProcessorState(core, cps); yield false; }
            case SystemOp.SetEndianness setend -> { system.executeSetEndianness(core, setend); yield false; }
            case SystemOp.StoreReturnState srs -> transfer.executeStoreReturnState(core, srs);
            case SystemOp.ReturnFromException rfe -> transfer.executeReturnFromException(core, rfe);
            case SystemOp.WaitForInterrupt wfi -> { system.executeWaitForInterrupt(core, wfi); yield false; }
            case IntegerOp.MoveTop moveTop -> { alu.executeMoveTop(core, moveTop); yield false; }
            case SystemOp.MemoryBarrier barrier -> { system.executeMemoryBarrier(core, barrier); yield false; }
            case SystemOp.SetItState setIt -> { system.executeSetItState(core, setIt); yield false; }
            case BranchOp.TableBranch tb -> branch.executeTableBranch(core, tb);
            case BranchOp.CompareBranchZero cbz -> branch.executeCompareBranchZero(core, cbz);
            case IntegerOp.BitFieldExtract bfx -> { alu.executeBitFieldExtract(core, bfx); yield false; }
            case IntegerOp.BitFieldInsert bfi -> { alu.executeBitFieldInsert(core, bfi); yield false; }
            case IntegerOp.BitReverse rbit -> { alu.executeBitReverse(core, rbit); yield false; }
            case IntegerOp.Divide div -> { alu.executeDivide(core, div); yield false; }
            case NeonIntegerOp.ThreeSame op3 -> { vfp.executeNeonThreeSame(core, op3); yield false; }
            case NeonMoveOp.LoadStoreMultiple lsm -> { neon.executeNeonLoadStoreMultiple(core, lsm); yield false; }
            case NeonMoveOp.LoadStoreSingle lss -> { neon.executeNeonLoadStoreSingle(core, lss); yield false; }
            case NeonMoveOp.LoadAllLanes lal -> { neon.executeNeonLoadAllLanes(core, lal); yield false; }
            case NeonIntegerOp.Pairwise pw -> { neon.executeNeonPairwise(core, pw); yield false; }
            case NeonFpOp.FpThreeSame fp3 -> { neon.executeNeonFpThreeSame(core, fp3); yield false; }
            case NeonFpOp.FpPairwise fppw -> { neon.executeNeonFpPairwise(core, fppw); yield false; }
            case NeonIntegerOp.ShiftImmediate si -> { neon.executeNeonShiftImmediate(core, si); yield false; }
            case NeonIntegerOp.ShiftNarrowImmediate sni -> { neon.executeNeonShiftNarrowImmediate(core, sni); yield false; }
            case NeonIntegerOp.ShiftWidenImmediate swi -> { neon.executeNeonShiftWidenImmediate(core, swi); yield false; }
            case NeonFpOp.ConvertFixedPoint cfp -> { neon.executeNeonConvertFixedPoint(core, cfp); yield false; }
            case NeonMoveOp.ModifiedImmediate nmi -> { neon.executeNeonModifiedImmediate(core, nmi); yield false; }
            case NeonIntegerOp.Widening nw -> { neon.executeNeonWidening(core, nw); yield false; }
            case NeonIntegerOp.Wide nwide -> { neon.executeNeonWide(core, nwide); yield false; }
            case NeonIntegerOp.Narrow nn -> { neon.executeNeonNarrow(core, nn); yield false; }
            case NeonIntegerOp.ThreeSameByElement ntsbe -> { neon.executeNeonThreeSameByElement(core, ntsbe); yield false; }
            case NeonIntegerOp.WideningByElement nwbe -> { neon.executeNeonWideningByElement(core, nwbe); yield false; }
            case NeonFpOp.FpThreeSameByElement nftsbe -> { neon.executeNeonFpThreeSameByElement(core, nftsbe); yield false; }
            case NeonIntegerOp.Unary nu -> { neon.executeNeonUnary(core, nu); yield false; }
            case NeonIntegerOp.NarrowUnary nnu -> { neon.executeNeonNarrowUnary(core, nnu); yield false; }
            case NeonFpOp.FpUnary nfu -> { neon.executeNeonFpUnary(core, nfu); yield false; }
            case NeonFpOp.Complex ncx -> { neon.executeNeonComplex(core, ncx); yield false; }
            case NeonFpOp.ComplexByElement ncxbe -> { neon.executeNeonComplexByElement(core, ncxbe); yield false; }
            case NeonIntegerOp.DotProduct ndp -> { neon.executeNeonDotProduct(core, ndp); yield false; }
            case NeonIntegerOp.DotProductByElement ndpbe -> { neon.executeNeonDotProductByElement(core, ndpbe); yield false; }
            case NeonIntegerOp.MatrixMultiplyAccumulate nmma -> { neon.executeNeonMatrixMultiplyAccumulate(core, nmma); yield false; }
            case NeonFpOp.FusedMultiplyAddLong nfmal -> { neon.executeNeonFusedMultiplyAddLong(core, nfmal); yield false; }
            case NeonFpOp.FusedMultiplyAddLongByElement nfmalbe -> { neon.executeNeonFusedMultiplyAddLongByElement(core, nfmalbe); yield false; }
            case NeonFpOp.DotProductBFloat16 ndpbf16 -> { neon.executeNeonDotProductBFloat16(core, ndpbf16); yield false; }
            case NeonFpOp.DotProductByElementBFloat16 ndpbebf16 -> { neon.executeNeonDotProductByElementBFloat16(core, ndpbebf16); yield false; }
            case NeonFpOp.MatrixMultiplyAccumulateBFloat16 nmmabf16 -> { neon.executeNeonMatrixMultiplyAccumulateBFloat16(core, nmmabf16); yield false; }
            case NeonFpOp.FusedMultiplyAddLongBFloat16 nfmalbf16 -> { neon.executeNeonFusedMultiplyAddLongBFloat16(core, nfmalbf16); yield false; }
            case NeonFpOp.FusedMultiplyAddLongByElementBFloat16 nfmalbebf16 -> { neon.executeNeonFusedMultiplyAddLongByElementBFloat16(core, nfmalbebf16); yield false; }
            case NeonMoveOp.SwapPermute nsp -> { neon.executeNeonSwapPermute(core, nsp); yield false; }
            case NeonMoveOp.Extract nex -> { neon.executeNeonExtract(core, nex); yield false; }
            case NeonMoveOp.TableLookup ntl -> { neon.executeNeonTableLookup(core, ntl); yield false; }
            case NeonMoveOp.DuplicateScalar nds -> { neon.executeNeonDuplicateScalar(core, nds); yield false; }
            case NeonCryptoOp.Aes nca -> { neon.executeNeonCryptoAes(core, nca); yield false; }
            case NeonCryptoOp.Sha ncs -> { neon.executeNeonCryptoSha(core, ncs); yield false; }
            case NeonCryptoOp.ShaThree ncst -> { neon.executeNeonCryptoShaThree(core, ncst); yield false; }
            case NeonFpOp.FpConvertPrecision nfcp -> { neon.executeNeonFpConvertPrecision(core, nfcp); yield false; }
            case VfpOp.Alu vfpAlu -> { vfp.executeVfpAlu(core, vfpAlu); yield false; }
            case VfpOp.MoveImmediate vfpMovImm -> { vfp.executeVfpMoveImmediate(core, vfpMovImm); yield false; }
            case VfpOp.Compare vfpCmp -> { vfp.executeVfpCompare(core, vfpCmp); yield false; }
            case VfpOp.Convert vfpCvt -> { vfp.executeVfpConvert(core, vfpCvt); yield false; }
            case VfpOp.Load vfpLoad -> { vfp.executeVfpLoad(core, vfpLoad); yield false; }
            case VfpOp.Store vfpStore -> { vfp.executeVfpStore(core, vfpStore); yield false; }
            case VfpOp.MultipleTransfer vfpMt -> { vfp.executeVfpMultipleTransfer(core, vfpMt); yield false; }
            case VfpOp.CoreTransfer vfpCore -> { vfp.executeVfpCoreTransfer(core, vfpCore); yield false; }
            case VfpOp.CorePairTransfer vfpCorePair -> { vfp.executeVfpCorePairTransfer(core, vfpCorePair); yield false; }
            case VfpOp.SystemTransfer vfpSys -> { vfp.executeVfpSystemTransfer(core, vfpSys); yield false; }
            case VfpOp.CorePairTransferSingle vfpCorePairSingle -> { vfp.executeVfpCorePairTransferSingle(core, vfpCorePairSingle); yield false; }
            case VfpOp.ConvertFixed vfpCvtFixed -> { vfp.executeVfpConvertFixed(core, vfpCvtFixed); yield false; }
            case VfpOp.Select vfpSelect -> { vfp.executeVfpSelect(core, vfpSelect); yield false; }
            case VfpOp.Round vfpRound -> { vfp.executeVfpRound(core, vfpRound); yield false; }
            case VfpOp.ConvertRounded vfpCvtRounded -> { vfp.executeVfpConvertRounded(core, vfpCvtRounded); yield false; }
            case VfpOp.MoveHalfLane vfpMoveHalfLane -> { vfp.executeVfpMoveHalfLane(core, vfpMoveHalfLane); yield false; }
            case VfpOp.AluHalf vfpAluHalf -> { vfp.executeVfpAluHalf(core, vfpAluHalf); yield false; }
            case VfpOp.MoveImmediateHalf vfpMoveImmHalf -> { vfp.executeVfpMoveImmediateHalf(core, vfpMoveImmHalf); yield false; }
            case VfpOp.CompareHalf vfpCompareHalf -> { vfp.executeVfpCompareHalf(core, vfpCompareHalf); yield false; }
            case VfpOp.SelectHalf vfpSelectHalf -> { vfp.executeVfpSelectHalf(core, vfpSelectHalf); yield false; }
            case VfpOp.RoundHalf vfpRoundHalf -> { vfp.executeVfpRoundHalf(core, vfpRoundHalf); yield false; }
            case VfpOp.ConvertRoundedHalf vfpCvtRoundedHalf -> { vfp.executeVfpConvertRoundedHalf(core, vfpCvtRoundedHalf); yield false; }
            case VfpOp.ConvertFixedHalf vfpCvtFixedHalf -> { vfp.executeVfpConvertFixedHalf(core, vfpCvtFixedHalf); yield false; }
            case VfpOp.LoadHalf vfpLoadHalf -> { vfp.executeVfpLoadHalf(core, vfpLoadHalf); yield false; }
            case VfpOp.StoreHalf vfpStoreHalf -> { vfp.executeVfpStoreHalf(core, vfpStoreHalf); yield false; }
            case VfpOp.ConvertHalfPrecision vfpCvtHalfPrecision -> { vfp.executeVfpConvertHalfPrecision(core, vfpCvtHalfPrecision); yield false; }
            case VfpOp.JavascriptConvert vfpJsCvt -> { vfp.executeVfpJavascriptConvert(core, vfpJsCvt); yield false; }
            case SystemOp.MProfileSystemRegister m -> { system.executeMProfileSystemRegister(core, m); yield false; }
            case SystemOp.Breakpoint bkpt -> system.executeBreakpoint(core, bkpt, blockEndPc);
            case IntegerOp.DspDualMultiply dual -> { alu.executeDspDualMultiply(core, dual); yield false; }
            case IntegerOp.DspTopWordMultiply topWord -> { alu.executeDspTopWordMultiply(core, topWord); yield false; }
            case SystemOp.Nocp nocp -> system.executeNocp(core, nocp);
            case VfpOp.SysregMemoryTransfer vfpSysreg -> { vfp.executeVfpSysregMemoryTransfer(core, vfpSysreg); yield false; }
            case SystemOp.SecureGateway sg -> { system.executeSecureGateway(core, sg); yield false; }
            case BranchOp.SecureBranchExchange sbx -> branch.executeSecureBranchExchange(core, sbx);
            case VfpOp.VlldmVlstm vlldmVlstm -> system.executeVlldmVlstm(core, vlldmVlstm);
            case VfpOp.Vscclrm vscclrm -> { vfp.executeVscclrm(core, vscclrm); yield false; }
            case BranchOp.LoopStart loopStart -> branch.executeLoopStart(core, loopStart);
            case BranchOp.LoopEnd loopEnd -> branch.executeLoopEnd(core, loopEnd);
            case MvePredicationOp.Vpst vpst -> system.executeVpst(core, vpst);
            case MvePredicationOp.Vpnot vpnot -> system.executeVpnot(core, vpnot);
            case MvePredicationOp.Vpsel vpsel -> system.executeVpsel(core, vpsel);
            case MvePredicationOp.Vctp vctp -> system.executeVctp(core, vctp);
            case MvePredicationOp.LoopClearTailPredication lctp -> { system.executeLctp(core, lctp); yield false; }
            case IntegerOp.ClearMultiple clrm -> { system.executeClrm(core, clrm); yield false; }
            case MveIntegerOp.WideShift wideShift -> { system.executeMveWideShift(core, wideShift); yield false; }
            case MvePredicationOp.AdvanceVpt advanceVpt -> { system.executeAdvanceVpt(core, advanceVpt); yield false; }
            case MvePredicationOp.VprTransfer vprTransfer -> { system.executeVprTransfer(core, vprTransfer); yield false; }
            case MveMoveOp.LoadStore mveLoadStore -> system.executeMveLoadStore(core, mveLoadStore);
            case MveMoveOp.WideningLoadStore mveWideningLoadStore ->
                    system.executeMveWideningLoadStore(core, mveWideningLoadStore);
            case MveMoveOp.GatherScatterOffset gatherScatterOffset ->
                    system.executeMveGatherScatterOffset(core, gatherScatterOffset);
            case MveMoveOp.GatherScatterImmediate gatherScatterImmediate ->
                    system.executeMveGatherScatterImmediate(core, gatherScatterImmediate);
            case MveMoveOp.InterleavedLoadStore interleavedLoadStore ->
                    system.executeMveInterleavedLoadStore(core, interleavedLoadStore);
            case MveMoveOp.IncrementDup incrementDup -> system.executeMveIncrementDup(core, incrementDup);
            case MveMoveOp.WrappingIncrementDup wrappingIncrementDup ->
                    system.executeMveWrappingIncrementDup(core, wrappingIncrementDup);
            case MvePredicationOp.AdvanceEci advanceEci -> { system.executeAdvanceEci(core, advanceEci); yield false; }
            case MveIntegerOp.Vector2Op mveVector2Op -> system.executeMveVector2Op(core, mveVector2Op);
            case MveIntegerOp.Vector2OpWidening mveVector2OpWidening ->
                    system.executeMveVector2OpWidening(core, mveVector2OpWidening);
            case MveIntegerOp.VectorCarry mveVectorCarry -> system.executeMveVectorCarry(core, mveVectorCarry);
            case MveIntegerOp.VectorComplexAdd mveVectorComplexAdd ->
                    system.executeMveVectorComplexAdd(core, mveVectorComplexAdd);
            case MveIntegerOp.VectorAbsAccumulate mveVectorAbsAccumulate ->
                    system.executeMveVectorAbsAccumulate(core, mveVectorAbsAccumulate);
            case MveFpOp.VectorFpAbsAccumulate mveVectorFpAbsAccumulate ->
                    system.executeMveVectorFpAbsAccumulate(core, mveVectorFpAbsAccumulate);
            case MveIntegerOp.VectorShiftWidenInterleaved mveVectorShiftWidenInterleaved ->
                    system.executeMveVectorShiftWidenInterleaved(core, mveVectorShiftWidenInterleaved);
            case MveIntegerOp.VectorNarrowInterleaved mveVectorNarrowInterleaved ->
                    system.executeMveVectorNarrowInterleaved(core, mveVectorNarrowInterleaved);
            case MveFpOp.VectorFpConvertPrecision mveVectorFpConvertPrecision ->
                    system.executeMveVectorFpConvertPrecision(core, mveVectorFpConvertPrecision);
            case MveFpOp.VectorFpComplexMultiply mveVectorFpComplexMultiply ->
                    system.executeMveVectorFpComplexMultiply(core, mveVectorFpComplexMultiply);
            case MveIntegerOp.VectorDualMultiplyAddHigh mveVectorDualMultiplyAddHigh ->
                    system.executeMveVectorDualMultiplyAddHigh(core, mveVectorDualMultiplyAddHigh);
            case MveIntegerOp.VectorDoublingWideningMultiply mveVectorDoublingWideningMultiply ->
                    system.executeMveVectorDoublingWideningMultiply(core, mveVectorDoublingWideningMultiply);
            case MveFpOp.VectorFpTwoOp mveVectorFpTwoOp -> system.executeMveVectorFpTwoOp(core, mveVectorFpTwoOp);
            case MveFpOp.VectorFpComplexAdd mveVectorFpComplexAdd ->
                    system.executeMveVectorFpComplexAdd(core, mveVectorFpComplexAdd);
            case MveFpOp.VectorFpComplexMultiplyAccumulate mveVectorFpComplexMultiplyAccumulate -> system
                    .executeMveVectorFpComplexMultiplyAccumulate(core, mveVectorFpComplexMultiplyAccumulate);
            case MvePredicationOp.VectorCompare mveVectorCompare -> system.executeMveVectorCompare(core, mveVectorCompare);
            case MvePredicationOp.VectorCompareScalar mveVectorCompareScalar ->
                    system.executeMveVectorCompareScalar(core, mveVectorCompareScalar);
            case MveIntegerOp.VectorScalar mveVectorScalar -> system.executeMveVectorScalar(core, mveVectorScalar);
            case MveIntegerOp.VectorScalarWidening mveVectorScalarWidening ->
                    system.executeMveVectorScalarWidening(core, mveVectorScalarWidening);
            case MveFpOp.VectorFpScalar mveVectorFpScalar -> system.executeMveVectorFpScalar(core, mveVectorFpScalar);
            case MveFpOp.VectorFpScalarFma mveVectorFpScalarFma ->
                    system.executeMveVectorFpScalarFma(core, mveVectorFpScalarFma);
            case MveIntegerOp.VectorScalarSpecial mveVectorScalarSpecial ->
                    system.executeMveVectorScalarSpecial(core, mveVectorScalarSpecial);
            case MveIntegerOp.VectorShiftImmediate mveVectorShiftImmediate ->
                    system.executeMveVectorShiftImmediate(core, mveVectorShiftImmediate);
            case MveIntegerOp.VectorShiftWidenImmediateInterleaved mveVectorShiftWidenImmediateInterleaved ->
                    system.executeMveVectorShiftWidenImmediateInterleaved(core, mveVectorShiftWidenImmediateInterleaved);
            case MveIntegerOp.VectorShiftNarrowImmediateInterleaved mveVectorShiftNarrowImmediateInterleaved ->
                    system.executeMveVectorShiftNarrowImmediateInterleaved(core, mveVectorShiftNarrowImmediateInterleaved);
            case MveIntegerOp.VectorShiftLeftCarry mveVectorShiftLeftCarry ->
                    system.executeMveVectorShiftLeftCarry(core, mveVectorShiftLeftCarry);
            case MveFpOp.VectorFpConvert mveVectorFpConvert ->
                    system.executeMveVectorFpConvert(core, mveVectorFpConvert);
            case MveFpOp.VectorFpConvertFixed mveVectorFpConvertFixed ->
                    system.executeMveVectorFpConvertFixed(core, mveVectorFpConvertFixed);
            case MveIntegerOp.VectorUnary mveVectorUnary -> system.executeMveVectorUnary(core, mveVectorUnary);
            case MveFpOp.VectorFpUnary mveVectorFpUnary -> system.executeMveVectorFpUnary(core, mveVectorFpUnary);
            case MveMoveOp.VectorDup mveVectorDup -> system.executeMveVectorDup(core, mveVectorDup);
            case MveMoveOp.MoveLanesGpr mveMoveLanesGpr -> system.executeMveMoveLanesGpr(core, mveMoveLanesGpr);
            case MveReductionOp.VectorAddAcrossVector mveVectorAddAcrossVector ->
                    system.executeMveVectorAddAcrossVector(core, mveVectorAddAcrossVector);
            case MveReductionOp.VectorAddAcrossVectorLong mveVectorAddAcrossVectorLong ->
                    system.executeMveVectorAddAcrossVectorLong(core, mveVectorAddAcrossVectorLong);
            case MveReductionOp.VectorAbsoluteDifferenceAccumulate mveVectorAbsoluteDifferenceAccumulate -> system
                    .executeMveVectorAbsoluteDifferenceAccumulate(core, mveVectorAbsoluteDifferenceAccumulate);
            case MveMoveOp.VectorModifiedImmediate mveVectorModifiedImmediate ->
                    system.executeMveVectorModifiedImmediate(core, mveVectorModifiedImmediate);
            case MveReductionOp.VectorDualAccumulate mveVectorDualAccumulate ->
                    system.executeMveVectorDualAccumulate(core, mveVectorDualAccumulate);
            case MveReductionOp.VectorDualAccumulateLong mveVectorDualAccumulateLong ->
                    system.executeMveVectorDualAccumulateLong(core, mveVectorDualAccumulateLong);
            case MveReductionOp.VectorRoundingDualAccumulateHigh mveVectorRoundingDualAccumulateHigh -> system
                    .executeMveVectorRoundingDualAccumulateHigh(core, mveVectorRoundingDualAccumulateHigh);
            case MveReductionOp.VectorMinMaxAcrossVector mveVectorMinMaxAcrossVector ->
                    system.executeMveVectorMinMaxAcrossVector(core, mveVectorMinMaxAcrossVector);
            case MveReductionOp.VectorFpMinMaxAcrossVector mveVectorFpMinMaxAcrossVector ->
                    system.executeMveVectorFpMinMaxAcrossVector(core, mveVectorFpMinMaxAcrossVector);
        };
    }

    /// Executor de VFP (task B3.4): ver {@link #aluExecutor()}.
    public IrVfpExecutor vfpExecutor() {
        return vfp;
    }
}
