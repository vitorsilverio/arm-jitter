package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
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
                case IrOp.Kind.ALU -> pcChanged |= alu.execute(core, (IrOp.Alu) op);
                case IrOp.Kind.MULTIPLY -> alu.executeMultiply(core, (IrOp.Multiply) op);
                case IrOp.Kind.LONG_MULTIPLY -> alu.executeLongMultiply(core, (IrOp.LongMultiply) op);
                case IrOp.Kind.SATURATING -> alu.executeSaturating(core, (IrOp.Saturating) op);
                case IrOp.Kind.CRC32 -> alu.executeCrc32(core, (IrOp.Crc32) op);
                case IrOp.Kind.DSP_MULTIPLY -> alu.executeDspMultiply(core, (IrOp.DspMultiply) op);
                case IrOp.Kind.PARALLEL_ALU -> alu.executeParallelAlu(core, (IrOp.ParallelAlu) op);
                case IrOp.Kind.SEL -> alu.executeSel(core, (IrOp.Sel) op);
                case IrOp.Kind.SATURATE -> alu.executeSaturate(core, (IrOp.Saturate) op);
                case IrOp.Kind.ABS_DIFF_SUM -> alu.executeAbsDiffSum(core, (IrOp.AbsDiffSum) op);
                case IrOp.Kind.PSR_TRANSFER -> system.executePsrTransfer(core, (IrOp.PsrTransfer) op);
                case IrOp.Kind.LOAD -> pcChanged |= memory.executeLoad(core, (IrOp.Load) op);
                case IrOp.Kind.STORE -> memory.executeStore(core, (IrOp.Store) op);
                case IrOp.Kind.LOAD_EXCLUSIVE -> memory.executeLoadExclusive(core, (IrOp.LoadExclusive) op);
                case IrOp.Kind.STORE_EXCLUSIVE -> memory.executeStoreExclusive(core, (IrOp.StoreExclusive) op);
                case IrOp.Kind.CLEAR_EXCLUSIVE -> memory.executeClearExclusive(core, (IrOp.ClearExclusive) op);
                case IrOp.Kind.DOUBLE_TRANSFER -> pcChanged |= memory.executeDoubleTransfer(core, (IrOp.DoubleTransfer) op);
                case IrOp.Kind.SWAP -> pcChanged |= memory.executeSwap(core, (IrOp.Swap) op);
                case IrOp.Kind.LOAD_LITERAL -> pcChanged |= memory.executeLoadLiteral(core, (IrOp.LoadLiteral) op);
                case IrOp.Kind.MULTIPLE_TRANSFER -> pcChanged |= transfer.executeMultipleTransfer(core, (IrOp.MultipleTransfer) op);
                case IrOp.Kind.BRANCH -> pcChanged |= branch.executeBranch(core, (IrOp.Branch) op);
                case IrOp.Kind.BRANCH_EXCHANGE -> pcChanged |= branch.executeBranchExchange(core, (IrOp.BranchExchange) op);
                case IrOp.Kind.THUMB_BL_PREFIX -> branch.executeThumbBlPrefix(core, (IrOp.ThumbBlPrefix) op);
                case IrOp.Kind.THUMB_BL_SUFFIX -> pcChanged |= branch.executeThumbBlSuffix(core, (IrOp.ThumbBlSuffix) op);
                case IrOp.Kind.PUSH -> transfer.executePush(core, (IrOp.Push) op);
                case IrOp.Kind.POP -> pcChanged |= transfer.executePop(core, (IrOp.Pop) op);
                case IrOp.Kind.SWI -> pcChanged |= system.executeSwi(core, (IrOp.Swi) op, block.endPc());
                case IrOp.Kind.HVC -> pcChanged |= system.executeHvc(core, (IrOp.Hvc) op, block.endPc());
                case IrOp.Kind.SMC -> pcChanged |= system.executeSmc(core, (IrOp.Smc) op, block.endPc());
                case IrOp.Kind.ERET -> pcChanged |= system.executeEret(core, (IrOp.Eret) op, block.endPc());
                case IrOp.Kind.MRS_BANK -> pcChanged |= system.executeMrsBank(core, (IrOp.MrsBank) op, block.endPc());
                case IrOp.Kind.MSR_BANK -> pcChanged |= system.executeMsrBank(core, (IrOp.MsrBank) op, block.endPc());
                case IrOp.Kind.COPROCESSOR -> pcChanged |= system.executeCoprocessor(core, (IrOp.Coprocessor) op);
                case IrOp.Kind.COPROCESSOR_DOUBLE -> pcChanged |= system.executeCoprocessorDouble(core, (IrOp.CoprocessorDouble) op);
                case IrOp.Kind.UNDEFINED -> pcChanged |= system.executeUndefined(core, (IrOp.Undefined) op);
                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);
                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);
                case IrOp.Kind.CHANGE_PROCESSOR_STATE -> system.executeChangeProcessorState(core, (IrOp.ChangeProcessorState) op);
                case IrOp.Kind.SET_ENDIANNESS -> system.executeSetEndianness(core, (IrOp.SetEndianness) op);
                case IrOp.Kind.STORE_RETURN_STATE -> pcChanged |= transfer.executeStoreReturnState(core, (IrOp.StoreReturnState) op);
                case IrOp.Kind.RETURN_FROM_EXCEPTION -> pcChanged |= transfer.executeReturnFromException(core, (IrOp.ReturnFromException) op);
                case IrOp.Kind.WAIT_FOR_INTERRUPT -> system.executeWaitForInterrupt(core, (IrOp.WaitForInterrupt) op);
                case IrOp.Kind.MOVE_TOP -> alu.executeMoveTop(core, (IrOp.MoveTop) op);
                case IrOp.Kind.MEMORY_BARRIER -> system.executeMemoryBarrier(core, (IrOp.MemoryBarrier) op);
                case IrOp.Kind.SET_IT_STATE -> system.executeSetItState(core, (IrOp.SetItState) op);
                case IrOp.Kind.TABLE_BRANCH -> pcChanged |= branch.executeTableBranch(core, (IrOp.TableBranch) op);
                case IrOp.Kind.COMPARE_BRANCH_ZERO -> pcChanged |= branch.executeCompareBranchZero(core, (IrOp.CompareBranchZero) op);
                case IrOp.Kind.BIT_FIELD_EXTRACT -> alu.executeBitFieldExtract(core, (IrOp.BitFieldExtract) op);
                case IrOp.Kind.BIT_FIELD_INSERT -> alu.executeBitFieldInsert(core, (IrOp.BitFieldInsert) op);
                case IrOp.Kind.BIT_REVERSE -> alu.executeBitReverse(core, (IrOp.BitReverse) op);
                case IrOp.Kind.DIVIDE -> alu.executeDivide(core, (IrOp.Divide) op);
                case IrOp.Kind.NEON_THREE_SAME -> vfp.executeNeonThreeSame(core, (IrOp.NeonThreeSame) op);
                case IrOp.Kind.NEON_LOAD_STORE_MULTIPLE -> neon.executeNeonLoadStoreMultiple(core, (IrOp.NeonLoadStoreMultiple) op);
                case IrOp.Kind.NEON_LOAD_STORE_SINGLE -> neon.executeNeonLoadStoreSingle(core, (IrOp.NeonLoadStoreSingle) op);
                case IrOp.Kind.NEON_LOAD_ALL_LANES -> neon.executeNeonLoadAllLanes(core, (IrOp.NeonLoadAllLanes) op);
                case IrOp.Kind.NEON_PAIRWISE -> neon.executeNeonPairwise(core, (IrOp.NeonPairwise) op);
                case IrOp.Kind.NEON_FP_THREE_SAME -> neon.executeNeonFpThreeSame(core, (IrOp.NeonFpThreeSame) op);
                case IrOp.Kind.NEON_FP_PAIRWISE -> neon.executeNeonFpPairwise(core, (IrOp.NeonFpPairwise) op);
                case IrOp.Kind.NEON_SHIFT_IMMEDIATE -> neon.executeNeonShiftImmediate(core, (IrOp.NeonShiftImmediate) op);
                case IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE -> neon.executeNeonShiftNarrowImmediate(core, (IrOp.NeonShiftNarrowImmediate) op);
                case IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE -> neon.executeNeonShiftWidenImmediate(core, (IrOp.NeonShiftWidenImmediate) op);
                case IrOp.Kind.NEON_CONVERT_FIXED_POINT -> neon.executeNeonConvertFixedPoint(core, (IrOp.NeonConvertFixedPoint) op);
                case IrOp.Kind.NEON_MODIFIED_IMMEDIATE -> neon.executeNeonModifiedImmediate(core, (IrOp.NeonModifiedImmediate) op);
                case IrOp.Kind.NEON_WIDENING -> neon.executeNeonWidening(core, (IrOp.NeonWidening) op);
                case IrOp.Kind.NEON_WIDE -> neon.executeNeonWide(core, (IrOp.NeonWide) op);
                case IrOp.Kind.NEON_NARROW -> neon.executeNeonNarrow(core, (IrOp.NeonNarrow) op);
                case IrOp.Kind.NEON_THREE_SAME_BY_ELEMENT -> neon.executeNeonThreeSameByElement(core, (IrOp.NeonThreeSameByElement) op);
                case IrOp.Kind.NEON_WIDENING_BY_ELEMENT -> neon.executeNeonWideningByElement(core, (IrOp.NeonWideningByElement) op);
                case IrOp.Kind.NEON_FP_THREE_SAME_BY_ELEMENT -> neon.executeNeonFpThreeSameByElement(core, (IrOp.NeonFpThreeSameByElement) op);
                case IrOp.Kind.NEON_UNARY -> neon.executeNeonUnary(core, (IrOp.NeonUnary) op);
                case IrOp.Kind.NEON_NARROW_UNARY -> neon.executeNeonNarrowUnary(core, (IrOp.NeonNarrowUnary) op);
                case IrOp.Kind.NEON_FP_UNARY -> neon.executeNeonFpUnary(core, (IrOp.NeonFpUnary) op);
                case IrOp.Kind.NEON_COMPLEX -> neon.executeNeonComplex(core, (IrOp.NeonComplex) op);
                case IrOp.Kind.NEON_COMPLEX_BY_ELEMENT -> neon.executeNeonComplexByElement(core, (IrOp.NeonComplexByElement) op);
                case IrOp.Kind.NEON_DOT_PRODUCT -> neon.executeNeonDotProduct(core, (IrOp.NeonDotProduct) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT -> neon.executeNeonDotProductByElement(core, (IrOp.NeonDotProductByElement) op);
                case IrOp.Kind.NEON_SWAP_PERMUTE -> neon.executeNeonSwapPermute(core, (IrOp.NeonSwapPermute) op);
                case IrOp.Kind.NEON_EXTRACT -> neon.executeNeonExtract(core, (IrOp.NeonExtract) op);
                case IrOp.Kind.NEON_TABLE_LOOKUP -> neon.executeNeonTableLookup(core, (IrOp.NeonTableLookup) op);
                case IrOp.Kind.NEON_DUPLICATE_SCALAR -> neon.executeNeonDuplicateScalar(core, (IrOp.NeonDuplicateScalar) op);
                case IrOp.Kind.NEON_CRYPTO_AES -> neon.executeNeonCryptoAes(core, (IrOp.NeonCryptoAes) op);
                case IrOp.Kind.NEON_CRYPTO_SHA -> neon.executeNeonCryptoSha(core, (IrOp.NeonCryptoSha) op);
                case IrOp.Kind.NEON_CRYPTO_SHA_THREE_REGISTER -> neon.executeNeonCryptoShaThree(core, (IrOp.NeonCryptoShaThree) op);
                case IrOp.Kind.NEON_FP_CONVERT_PRECISION -> neon.executeNeonFpConvertPrecision(core, (IrOp.NeonFpConvertPrecision) op);
                case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE -> neon.executeNeonMatrixMultiplyAccumulate(core, (IrOp.NeonMatrixMultiplyAccumulate) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG -> neon.executeNeonFusedMultiplyAddLong(core, (IrOp.NeonFusedMultiplyAddLong) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT -> neon.executeNeonFusedMultiplyAddLongByElement(core, (IrOp.NeonFusedMultiplyAddLongByElement) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BFLOAT16 -> neon.executeNeonDotProductBFloat16(core, (IrOp.NeonDotProductBFloat16) op);
                case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16 -> neon.executeNeonDotProductByElementBFloat16(core, (IrOp.NeonDotProductByElementBFloat16) op);
                case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 -> neon.executeNeonMatrixMultiplyAccumulateBFloat16(core, (IrOp.NeonMatrixMultiplyAccumulateBFloat16) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16 -> neon.executeNeonFusedMultiplyAddLongBFloat16(core, (IrOp.NeonFusedMultiplyAddLongBFloat16) op);
                case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 -> neon.executeNeonFusedMultiplyAddLongByElementBFloat16(core, (IrOp.NeonFusedMultiplyAddLongByElementBFloat16) op);
                case IrOp.Kind.VFP_ALU -> vfp.executeVfpAlu(core, (IrOp.VfpAlu) op);
                case IrOp.Kind.VFP_MOVE_IMMEDIATE -> vfp.executeVfpMoveImmediate(core, (IrOp.VfpMoveImmediate) op);
                case IrOp.Kind.VFP_COMPARE -> vfp.executeVfpCompare(core, (IrOp.VfpCompare) op);
                case IrOp.Kind.VFP_CONVERT -> vfp.executeVfpConvert(core, (IrOp.VfpConvert) op);
                case IrOp.Kind.VFP_LOAD -> vfp.executeVfpLoad(core, (IrOp.VfpLoad) op);
                case IrOp.Kind.VFP_STORE -> vfp.executeVfpStore(core, (IrOp.VfpStore) op);
                case IrOp.Kind.VFP_MULTIPLE_TRANSFER -> vfp.executeVfpMultipleTransfer(core, (IrOp.VfpMultipleTransfer) op);
                case IrOp.Kind.VFP_CORE_TRANSFER -> vfp.executeVfpCoreTransfer(core, (IrOp.VfpCoreTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER -> vfp.executeVfpCorePairTransfer(core, (IrOp.VfpCorePairTransfer) op);
                case IrOp.Kind.VFP_SYSTEM_TRANSFER -> vfp.executeVfpSystemTransfer(core, (IrOp.VfpSystemTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE -> vfp.executeVfpCorePairTransferSingle(core, (IrOp.VfpCorePairTransferSingle) op);
                case IrOp.Kind.VFP_CONVERT_FIXED -> vfp.executeVfpConvertFixed(core, (IrOp.VfpConvertFixed) op);
                case IrOp.Kind.VFP_SELECT -> vfp.executeVfpSelect(core, (IrOp.VfpSelect) op);
                case IrOp.Kind.VFP_ROUND -> vfp.executeVfpRound(core, (IrOp.VfpRound) op);
                case IrOp.Kind.VFP_CONVERT_ROUNDED -> vfp.executeVfpConvertRounded(core, (IrOp.VfpConvertRounded) op);
                case IrOp.Kind.VFP_MOVE_HALF_LANE -> vfp.executeVfpMoveHalfLane(core, (IrOp.VfpMoveHalfLane) op);
                case IrOp.Kind.VFP_ALU_HALF -> vfp.executeVfpAluHalf(core, (IrOp.VfpAluHalf) op);
                case IrOp.Kind.VFP_MOVE_IMMEDIATE_HALF -> vfp.executeVfpMoveImmediateHalf(core, (IrOp.VfpMoveImmediateHalf) op);
                case IrOp.Kind.VFP_COMPARE_HALF -> vfp.executeVfpCompareHalf(core, (IrOp.VfpCompareHalf) op);
                case IrOp.Kind.VFP_SELECT_HALF -> vfp.executeVfpSelectHalf(core, (IrOp.VfpSelectHalf) op);
                case IrOp.Kind.VFP_ROUND_HALF -> vfp.executeVfpRoundHalf(core, (IrOp.VfpRoundHalf) op);
                case IrOp.Kind.VFP_CONVERT_ROUNDED_HALF -> vfp.executeVfpConvertRoundedHalf(core, (IrOp.VfpConvertRoundedHalf) op);
                case IrOp.Kind.VFP_CONVERT_FIXED_HALF -> vfp.executeVfpConvertFixedHalf(core, (IrOp.VfpConvertFixedHalf) op);
                case IrOp.Kind.VFP_LOAD_HALF -> vfp.executeVfpLoadHalf(core, (IrOp.VfpLoadHalf) op);
                case IrOp.Kind.VFP_STORE_HALF -> vfp.executeVfpStoreHalf(core, (IrOp.VfpStoreHalf) op);
                case IrOp.Kind.M_PROFILE_SYSTEM_REGISTER -> system.executeMProfileSystemRegister(core, (IrOp.MProfileSystemRegister) op);
                case IrOp.Kind.BREAKPOINT -> pcChanged |= system.executeBreakpoint(core, (IrOp.Breakpoint) op, block.endPc());
                case IrOp.Kind.DSP_DUAL_MULTIPLY -> alu.executeDspDualMultiply(core, (IrOp.DspDualMultiply) op);
                case IrOp.Kind.DSP_TOP_WORD_MULTIPLY -> alu.executeDspTopWordMultiply(core, (IrOp.DspTopWordMultiply) op);
                case IrOp.Kind.NOCP -> pcChanged |= system.executeNocp(core, (IrOp.Nocp) op);
                case IrOp.Kind.VFP_SYSREG_MEMORY_TRANSFER -> vfp.executeVfpSysregMemoryTransfer(core, (IrOp.VfpSysregMemoryTransfer) op);
                case IrOp.Kind.SECURE_GATEWAY -> system.executeSecureGateway(core, (IrOp.SecureGateway) op);
                case IrOp.Kind.SECURE_BRANCH_EXCHANGE -> pcChanged |= branch.executeSecureBranchExchange(core, (IrOp.SecureBranchExchange) op);
                case IrOp.Kind.VLLDM_VLSTM -> pcChanged |= system.executeVlldmVlstm(core, (IrOp.VlldmVlstm) op);
                case IrOp.Kind.VSCCLRM -> vfp.executeVscclrm(core, (IrOp.Vscclrm) op);
                case IrOp.Kind.LOOP_START -> pcChanged |= branch.executeLoopStart(core, (IrOp.LoopStart) op);
                case IrOp.Kind.LOOP_END -> pcChanged |= branch.executeLoopEnd(core, (IrOp.LoopEnd) op);
                case IrOp.Kind.VPST -> pcChanged |= system.executeVpst(core, (IrOp.Vpst) op);
                case IrOp.Kind.VPNOT -> pcChanged |= system.executeVpnot(core, (IrOp.Vpnot) op);
                case IrOp.Kind.VPSEL -> pcChanged |= system.executeVpsel(core, (IrOp.Vpsel) op);
                // ADVANCE_VPT (B16.2): pulado quando a instrução MVE anterior no MESMO bloco já
                // mudou o PC (fault de ECI reservado) — ver Javadoc de IrSystemExecutor#executeAdvanceVpt.
                case IrOp.Kind.ADVANCE_VPT -> {
                    if (!pcChanged) {
                        system.executeAdvanceVpt(core, (IrOp.AdvanceVpt) op);
                    }
                }
                case IrOp.Kind.VPR_TRANSFER -> system.executeVprTransfer(core, (IrOp.VprTransfer) op);
                // MVE_LOAD_STORE (B16.3): pode faultar (ECI reservado) igual VPST/VPNOT/VPSEL —
                // mesmo pcChanged-gate para o ADVANCE_VPT seguinte.
                case IrOp.Kind.MVE_LOAD_STORE -> pcChanged |= system.executeMveLoadStore(core, (IrOp.MveLoadStore) op);
                case IrOp.Kind.MVE_WIDENING_LOAD_STORE ->
                        pcChanged |= system.executeMveWideningLoadStore(core, (IrOp.MveWideningLoadStore) op);
                // B16.5: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_GATHER_SCATTER_OFFSET ->
                        pcChanged |= system.executeMveGatherScatterOffset(core, (IrOp.MveGatherScatterOffset) op);
                case IrOp.Kind.MVE_GATHER_SCATTER_IMMEDIATE ->
                        pcChanged |= system.executeMveGatherScatterImmediate(core, (IrOp.MveGatherScatterImmediate) op);
                case IrOp.Kind.MVE_INTERLEAVED_LOAD_STORE ->
                        pcChanged |= system.executeMveInterleavedLoadStore(core, (IrOp.MveInterleavedLoadStore) op);
                case IrOp.Kind.MVE_INCREMENT_DUP ->
                        pcChanged |= system.executeMveIncrementDup(core, (IrOp.MveIncrementDup) op);
                case IrOp.Kind.MVE_WRAPPING_INCREMENT_DUP ->
                        pcChanged |= system.executeMveWrappingIncrementDup(core, (IrOp.MveWrappingIncrementDup) op);
                // B16.6: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_2OP -> pcChanged |= system.executeMveVector2Op(core, (IrOp.MveVector2Op) op);
                case IrOp.Kind.MVE_VECTOR_2OP_WIDENING ->
                        pcChanged |= system.executeMveVector2OpWidening(core, (IrOp.MveVector2OpWidening) op);
                case IrOp.Kind.MVE_VECTOR_CARRY -> pcChanged |= system.executeMveVectorCarry(core, (IrOp.MveVectorCarry) op);
                case IrOp.Kind.MVE_VECTOR_COMPLEX_ADD ->
                        pcChanged |= system.executeMveVectorComplexAdd(core, (IrOp.MveVectorComplexAdd) op);
                // B16.7: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_ABS_ACCUMULATE ->
                        pcChanged |= system.executeMveVectorAbsAccumulate(core, (IrOp.MveVectorAbsAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_FP_ABS_ACCUMULATE ->
                        pcChanged |= system.executeMveVectorFpAbsAccumulate(core, (IrOp.MveVectorFpAbsAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED -> pcChanged |= system.executeMveVectorShiftWidenInterleaved(
                        core, (IrOp.MveVectorShiftWidenInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_NARROW_INTERLEAVED ->
                        pcChanged |= system.executeMveVectorNarrowInterleaved(core, (IrOp.MveVectorNarrowInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT_PRECISION ->
                        pcChanged |= system.executeMveVectorFpConvertPrecision(core, (IrOp.MveVectorFpConvertPrecision) op);
                // B16.7 sub-família 2: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY ->
                        pcChanged |= system.executeMveVectorFpComplexMultiply(core, (IrOp.MveVectorFpComplexMultiply) op);
                case IrOp.Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH ->
                        pcChanged |= system.executeMveVectorDualMultiplyAddHigh(core, (IrOp.MveVectorDualMultiplyAddHigh) op);
                case IrOp.Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY -> pcChanged |= system
                        .executeMveVectorDoublingWideningMultiply(core, (IrOp.MveVectorDoublingWideningMultiply) op);
                // B16.7 sub-família 3: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_TWO_OP ->
                        pcChanged |= system.executeMveVectorFpTwoOp(core, (IrOp.MveVectorFpTwoOp) op);
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_ADD ->
                        pcChanged |= system.executeMveVectorFpComplexAdd(core, (IrOp.MveVectorFpComplexAdd) op);
                case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorFpComplexMultiplyAccumulate(core, (IrOp.MveVectorFpComplexMultiplyAccumulate) op);
                // B16.8: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_COMPARE ->
                        pcChanged |= system.executeMveVectorCompare(core, (IrOp.MveVectorCompare) op);
                case IrOp.Kind.MVE_VECTOR_COMPARE_SCALAR ->
                        pcChanged |= system.executeMveVectorCompareScalar(core, (IrOp.MveVectorCompareScalar) op);
                // B16.9: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SCALAR ->
                        pcChanged |= system.executeMveVectorScalar(core, (IrOp.MveVectorScalar) op);
                case IrOp.Kind.MVE_VECTOR_SCALAR_WIDENING ->
                        pcChanged |= system.executeMveVectorScalarWidening(core, (IrOp.MveVectorScalarWidening) op);
                case IrOp.Kind.MVE_VECTOR_FP_SCALAR ->
                        pcChanged |= system.executeMveVectorFpScalar(core, (IrOp.MveVectorFpScalar) op);
                case IrOp.Kind.MVE_VECTOR_FP_SCALAR_FMA ->
                        pcChanged |= system.executeMveVectorFpScalarFma(core, (IrOp.MveVectorFpScalarFma) op);
                case IrOp.Kind.MVE_VECTOR_SCALAR_SPECIAL ->
                        pcChanged |= system.executeMveVectorScalarSpecial(core, (IrOp.MveVectorScalarSpecial) op);
                // B16.10: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SHIFT_IMMEDIATE ->
                        pcChanged |= system.executeMveVectorShiftImmediate(core, (IrOp.MveVectorShiftImmediate) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED -> pcChanged |= system
                        .executeMveVectorShiftWidenImmediateInterleaved(core,
                                (IrOp.MveVectorShiftWidenImmediateInterleaved) op);
                // B16.11: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED -> pcChanged |= system
                        .executeMveVectorShiftNarrowImmediateInterleaved(core,
                                (IrOp.MveVectorShiftNarrowImmediateInterleaved) op);
                case IrOp.Kind.MVE_VECTOR_SHIFT_LEFT_CARRY ->
                        pcChanged |= system.executeMveVectorShiftLeftCarry(core, (IrOp.MveVectorShiftLeftCarry) op);
                // B16.12: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT ->
                        pcChanged |= system.executeMveVectorFpConvert(core, (IrOp.MveVectorFpConvert) op);
                case IrOp.Kind.MVE_VECTOR_FP_CONVERT_FIXED ->
                        pcChanged |= system.executeMveVectorFpConvertFixed(core, (IrOp.MveVectorFpConvertFixed) op);
                // B16.13a: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_UNARY ->
                        pcChanged |= system.executeMveVectorUnary(core, (IrOp.MveVectorUnary) op);
                case IrOp.Kind.MVE_VECTOR_FP_UNARY ->
                        pcChanged |= system.executeMveVectorFpUnary(core, (IrOp.MveVectorFpUnary) op);
                case IrOp.Kind.MVE_VECTOR_DUP ->
                        pcChanged |= system.executeMveVectorDup(core, (IrOp.MveVectorDup) op);
                case IrOp.Kind.MVE_MOVE_LANES_GPR ->
                        pcChanged |= system.executeMveMoveLanesGpr(core, (IrOp.MveMoveLanesGpr) op);
                case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR ->
                        pcChanged |= system.executeMveVectorAddAcrossVector(core, (IrOp.MveVectorAddAcrossVector) op);
                case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR_LONG -> pcChanged |= system
                        .executeMveVectorAddAcrossVectorLong(core, (IrOp.MveVectorAddAcrossVectorLong) op);
                case IrOp.Kind.MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorAbsoluteDifferenceAccumulate(core,
                                (IrOp.MveVectorAbsoluteDifferenceAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_MODIFIED_IMMEDIATE -> pcChanged |= system
                        .executeMveVectorModifiedImmediate(core, (IrOp.MveVectorModifiedImmediate) op);
                // B16.13b: mesmo pcChanged-gate acima (podem faultar em ECI reservado).
                case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE -> pcChanged |= system
                        .executeMveVectorDualAccumulate(core, (IrOp.MveVectorDualAccumulate) op);
                case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE_LONG -> pcChanged |= system
                        .executeMveVectorDualAccumulateLong(core, (IrOp.MveVectorDualAccumulateLong) op);
                case IrOp.Kind.MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH -> pcChanged |= system
                        .executeMveVectorRoundingDualAccumulateHigh(core,
                                (IrOp.MveVectorRoundingDualAccumulateHigh) op);
                case IrOp.Kind.MVE_VECTOR_MIN_MAX_ACROSS_VECTOR -> pcChanged |= system
                        .executeMveVectorMinMaxAcrossVector(core, (IrOp.MveVectorMinMaxAcrossVector) op);
                case IrOp.Kind.MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR -> pcChanged |= system
                        .executeMveVectorFpMinMaxAcrossVector(core, (IrOp.MveVectorFpMinMaxAcrossVector) op);
                // ADVANCE_ECI (B16.5): mesmo gate de pcChanged que ADVANCE_VPT usa (pulado quando a
                // instrução anterior no MESMO bloco já mudou o PC por fault de ECI reservado).
                case IrOp.Kind.ADVANCE_ECI -> {
                    if (!pcChanged) {
                        system.executeAdvanceEci(core, (IrOp.AdvanceEci) op);
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
    /// @param blockEndPc PC sequencial do fim do bloco (necessário para {@link IrOp.Swi})
    public boolean executeOp(ArmCore core, IrOp op, int blockEndPc) {
        return switch (op) {
            case IrOp.Alu aluOp -> alu.execute(core, aluOp);
            case IrOp.Multiply multiply -> { alu.executeMultiply(core, multiply); yield false; }
            case IrOp.LongMultiply lm -> { alu.executeLongMultiply(core, lm); yield false; }
            case IrOp.Saturating sat -> { alu.executeSaturating(core, sat); yield false; }
            case IrOp.Crc32 crc -> { alu.executeCrc32(core, crc); yield false; }
            case IrOp.DspMultiply dsp -> { alu.executeDspMultiply(core, dsp); yield false; }
            case IrOp.ParallelAlu parallel -> { alu.executeParallelAlu(core, parallel); yield false; }
            case IrOp.Sel sel -> { alu.executeSel(core, sel); yield false; }
            case IrOp.Saturate saturate -> { alu.executeSaturate(core, saturate); yield false; }
            case IrOp.AbsDiffSum usad -> { alu.executeAbsDiffSum(core, usad); yield false; }
            case IrOp.PsrTransfer psr -> { system.executePsrTransfer(core, psr); yield false; }
            case IrOp.LoadLiteral ll -> memory.executeLoadLiteral(core, ll);
            case IrOp.Load load -> memory.executeLoad(core, load);
            case IrOp.Store store -> { memory.executeStore(core, store); yield false; }
            case IrOp.LoadExclusive lex -> { memory.executeLoadExclusive(core, lex); yield false; }
            case IrOp.StoreExclusive sex -> { memory.executeStoreExclusive(core, sex); yield false; }
            case IrOp.ClearExclusive clrex -> { memory.executeClearExclusive(core, clrex); yield false; }
            case IrOp.DoubleTransfer dt -> memory.executeDoubleTransfer(core, dt);
            case IrOp.Swap swap -> memory.executeSwap(core, swap);
            case IrOp.MultipleTransfer mt -> transfer.executeMultipleTransfer(core, mt);
            case IrOp.Branch b -> branch.executeBranch(core, b);
            case IrOp.BranchExchange bx -> branch.executeBranchExchange(core, bx);
            case IrOp.ThumbBlPrefix prefix -> { branch.executeThumbBlPrefix(core, prefix); yield false; }
            case IrOp.ThumbBlSuffix suffix -> branch.executeThumbBlSuffix(core, suffix);
            case IrOp.Push push -> { transfer.executePush(core, push); yield false; }
            case IrOp.Pop pop -> transfer.executePop(core, pop);
            case IrOp.Swi swi -> system.executeSwi(core, swi, blockEndPc);
            case IrOp.Hvc hvc -> system.executeHvc(core, hvc, blockEndPc);
            case IrOp.Smc smc -> system.executeSmc(core, smc, blockEndPc);
            case IrOp.Eret eret -> system.executeEret(core, eret, blockEndPc);
            case IrOp.MrsBank mrsBank -> system.executeMrsBank(core, mrsBank, blockEndPc);
            case IrOp.MsrBank msrBank -> system.executeMsrBank(core, msrBank, blockEndPc);
            case IrOp.Coprocessor cp -> system.executeCoprocessor(core, cp);
            case IrOp.CoprocessorDouble cp -> system.executeCoprocessorDouble(core, cp);
            case IrOp.Undefined undef -> system.executeUndefined(core, undef);
            case IrOp.Cycle cycleOp -> { cycle.executeCycle(cycleOp); yield false; }
            case IrOp.Fetch fetch -> { cycle.executeFetch(core, fetch); yield false; }
            case IrOp.ChangeProcessorState cps -> { system.executeChangeProcessorState(core, cps); yield false; }
            case IrOp.SetEndianness setend -> { system.executeSetEndianness(core, setend); yield false; }
            case IrOp.StoreReturnState srs -> transfer.executeStoreReturnState(core, srs);
            case IrOp.ReturnFromException rfe -> transfer.executeReturnFromException(core, rfe);
            case IrOp.WaitForInterrupt wfi -> { system.executeWaitForInterrupt(core, wfi); yield false; }
            case IrOp.MoveTop moveTop -> { alu.executeMoveTop(core, moveTop); yield false; }
            case IrOp.MemoryBarrier barrier -> { system.executeMemoryBarrier(core, barrier); yield false; }
            case IrOp.SetItState setIt -> { system.executeSetItState(core, setIt); yield false; }
            case IrOp.TableBranch tb -> branch.executeTableBranch(core, tb);
            case IrOp.CompareBranchZero cbz -> branch.executeCompareBranchZero(core, cbz);
            case IrOp.BitFieldExtract bfx -> { alu.executeBitFieldExtract(core, bfx); yield false; }
            case IrOp.BitFieldInsert bfi -> { alu.executeBitFieldInsert(core, bfi); yield false; }
            case IrOp.BitReverse rbit -> { alu.executeBitReverse(core, rbit); yield false; }
            case IrOp.Divide div -> { alu.executeDivide(core, div); yield false; }
            case IrOp.NeonThreeSame op3 -> { vfp.executeNeonThreeSame(core, op3); yield false; }
            case IrOp.NeonLoadStoreMultiple lsm -> { neon.executeNeonLoadStoreMultiple(core, lsm); yield false; }
            case IrOp.NeonLoadStoreSingle lss -> { neon.executeNeonLoadStoreSingle(core, lss); yield false; }
            case IrOp.NeonLoadAllLanes lal -> { neon.executeNeonLoadAllLanes(core, lal); yield false; }
            case IrOp.NeonPairwise pw -> { neon.executeNeonPairwise(core, pw); yield false; }
            case IrOp.NeonFpThreeSame fp3 -> { neon.executeNeonFpThreeSame(core, fp3); yield false; }
            case IrOp.NeonFpPairwise fppw -> { neon.executeNeonFpPairwise(core, fppw); yield false; }
            case IrOp.NeonShiftImmediate si -> { neon.executeNeonShiftImmediate(core, si); yield false; }
            case IrOp.NeonShiftNarrowImmediate sni -> { neon.executeNeonShiftNarrowImmediate(core, sni); yield false; }
            case IrOp.NeonShiftWidenImmediate swi -> { neon.executeNeonShiftWidenImmediate(core, swi); yield false; }
            case IrOp.NeonConvertFixedPoint cfp -> { neon.executeNeonConvertFixedPoint(core, cfp); yield false; }
            case IrOp.NeonModifiedImmediate nmi -> { neon.executeNeonModifiedImmediate(core, nmi); yield false; }
            case IrOp.NeonWidening nw -> { neon.executeNeonWidening(core, nw); yield false; }
            case IrOp.NeonWide nwide -> { neon.executeNeonWide(core, nwide); yield false; }
            case IrOp.NeonNarrow nn -> { neon.executeNeonNarrow(core, nn); yield false; }
            case IrOp.NeonThreeSameByElement ntsbe -> { neon.executeNeonThreeSameByElement(core, ntsbe); yield false; }
            case IrOp.NeonWideningByElement nwbe -> { neon.executeNeonWideningByElement(core, nwbe); yield false; }
            case IrOp.NeonFpThreeSameByElement nftsbe -> { neon.executeNeonFpThreeSameByElement(core, nftsbe); yield false; }
            case IrOp.NeonUnary nu -> { neon.executeNeonUnary(core, nu); yield false; }
            case IrOp.NeonNarrowUnary nnu -> { neon.executeNeonNarrowUnary(core, nnu); yield false; }
            case IrOp.NeonFpUnary nfu -> { neon.executeNeonFpUnary(core, nfu); yield false; }
            case IrOp.NeonComplex ncx -> { neon.executeNeonComplex(core, ncx); yield false; }
            case IrOp.NeonComplexByElement ncxbe -> { neon.executeNeonComplexByElement(core, ncxbe); yield false; }
            case IrOp.NeonDotProduct ndp -> { neon.executeNeonDotProduct(core, ndp); yield false; }
            case IrOp.NeonDotProductByElement ndpbe -> { neon.executeNeonDotProductByElement(core, ndpbe); yield false; }
            case IrOp.NeonMatrixMultiplyAccumulate nmma -> { neon.executeNeonMatrixMultiplyAccumulate(core, nmma); yield false; }
            case IrOp.NeonFusedMultiplyAddLong nfmal -> { neon.executeNeonFusedMultiplyAddLong(core, nfmal); yield false; }
            case IrOp.NeonFusedMultiplyAddLongByElement nfmalbe -> { neon.executeNeonFusedMultiplyAddLongByElement(core, nfmalbe); yield false; }
            case IrOp.NeonDotProductBFloat16 ndpbf16 -> { neon.executeNeonDotProductBFloat16(core, ndpbf16); yield false; }
            case IrOp.NeonDotProductByElementBFloat16 ndpbebf16 -> { neon.executeNeonDotProductByElementBFloat16(core, ndpbebf16); yield false; }
            case IrOp.NeonMatrixMultiplyAccumulateBFloat16 nmmabf16 -> { neon.executeNeonMatrixMultiplyAccumulateBFloat16(core, nmmabf16); yield false; }
            case IrOp.NeonFusedMultiplyAddLongBFloat16 nfmalbf16 -> { neon.executeNeonFusedMultiplyAddLongBFloat16(core, nfmalbf16); yield false; }
            case IrOp.NeonFusedMultiplyAddLongByElementBFloat16 nfmalbebf16 -> { neon.executeNeonFusedMultiplyAddLongByElementBFloat16(core, nfmalbebf16); yield false; }
            case IrOp.NeonSwapPermute nsp -> { neon.executeNeonSwapPermute(core, nsp); yield false; }
            case IrOp.NeonExtract nex -> { neon.executeNeonExtract(core, nex); yield false; }
            case IrOp.NeonTableLookup ntl -> { neon.executeNeonTableLookup(core, ntl); yield false; }
            case IrOp.NeonDuplicateScalar nds -> { neon.executeNeonDuplicateScalar(core, nds); yield false; }
            case IrOp.NeonCryptoAes nca -> { neon.executeNeonCryptoAes(core, nca); yield false; }
            case IrOp.NeonCryptoSha ncs -> { neon.executeNeonCryptoSha(core, ncs); yield false; }
            case IrOp.NeonCryptoShaThree ncst -> { neon.executeNeonCryptoShaThree(core, ncst); yield false; }
            case IrOp.NeonFpConvertPrecision nfcp -> { neon.executeNeonFpConvertPrecision(core, nfcp); yield false; }
            case IrOp.VfpAlu vfpAlu -> { vfp.executeVfpAlu(core, vfpAlu); yield false; }
            case IrOp.VfpMoveImmediate vfpMovImm -> { vfp.executeVfpMoveImmediate(core, vfpMovImm); yield false; }
            case IrOp.VfpCompare vfpCmp -> { vfp.executeVfpCompare(core, vfpCmp); yield false; }
            case IrOp.VfpConvert vfpCvt -> { vfp.executeVfpConvert(core, vfpCvt); yield false; }
            case IrOp.VfpLoad vfpLoad -> { vfp.executeVfpLoad(core, vfpLoad); yield false; }
            case IrOp.VfpStore vfpStore -> { vfp.executeVfpStore(core, vfpStore); yield false; }
            case IrOp.VfpMultipleTransfer vfpMt -> { vfp.executeVfpMultipleTransfer(core, vfpMt); yield false; }
            case IrOp.VfpCoreTransfer vfpCore -> { vfp.executeVfpCoreTransfer(core, vfpCore); yield false; }
            case IrOp.VfpCorePairTransfer vfpCorePair -> { vfp.executeVfpCorePairTransfer(core, vfpCorePair); yield false; }
            case IrOp.VfpSystemTransfer vfpSys -> { vfp.executeVfpSystemTransfer(core, vfpSys); yield false; }
            case IrOp.VfpCorePairTransferSingle vfpCorePairSingle -> { vfp.executeVfpCorePairTransferSingle(core, vfpCorePairSingle); yield false; }
            case IrOp.VfpConvertFixed vfpCvtFixed -> { vfp.executeVfpConvertFixed(core, vfpCvtFixed); yield false; }
            case IrOp.VfpSelect vfpSelect -> { vfp.executeVfpSelect(core, vfpSelect); yield false; }
            case IrOp.VfpRound vfpRound -> { vfp.executeVfpRound(core, vfpRound); yield false; }
            case IrOp.VfpConvertRounded vfpCvtRounded -> { vfp.executeVfpConvertRounded(core, vfpCvtRounded); yield false; }
            case IrOp.VfpMoveHalfLane vfpMoveHalfLane -> { vfp.executeVfpMoveHalfLane(core, vfpMoveHalfLane); yield false; }
            case IrOp.VfpAluHalf vfpAluHalf -> { vfp.executeVfpAluHalf(core, vfpAluHalf); yield false; }
            case IrOp.VfpMoveImmediateHalf vfpMoveImmHalf -> { vfp.executeVfpMoveImmediateHalf(core, vfpMoveImmHalf); yield false; }
            case IrOp.VfpCompareHalf vfpCompareHalf -> { vfp.executeVfpCompareHalf(core, vfpCompareHalf); yield false; }
            case IrOp.VfpSelectHalf vfpSelectHalf -> { vfp.executeVfpSelectHalf(core, vfpSelectHalf); yield false; }
            case IrOp.VfpRoundHalf vfpRoundHalf -> { vfp.executeVfpRoundHalf(core, vfpRoundHalf); yield false; }
            case IrOp.VfpConvertRoundedHalf vfpCvtRoundedHalf -> { vfp.executeVfpConvertRoundedHalf(core, vfpCvtRoundedHalf); yield false; }
            case IrOp.VfpConvertFixedHalf vfpCvtFixedHalf -> { vfp.executeVfpConvertFixedHalf(core, vfpCvtFixedHalf); yield false; }
            case IrOp.VfpLoadHalf vfpLoadHalf -> { vfp.executeVfpLoadHalf(core, vfpLoadHalf); yield false; }
            case IrOp.VfpStoreHalf vfpStoreHalf -> { vfp.executeVfpStoreHalf(core, vfpStoreHalf); yield false; }
            case IrOp.MProfileSystemRegister m -> { system.executeMProfileSystemRegister(core, m); yield false; }
            case IrOp.Breakpoint bkpt -> system.executeBreakpoint(core, bkpt, blockEndPc);
            case IrOp.DspDualMultiply dual -> { alu.executeDspDualMultiply(core, dual); yield false; }
            case IrOp.DspTopWordMultiply topWord -> { alu.executeDspTopWordMultiply(core, topWord); yield false; }
            case IrOp.Nocp nocp -> system.executeNocp(core, nocp);
            case IrOp.VfpSysregMemoryTransfer vfpSysreg -> { vfp.executeVfpSysregMemoryTransfer(core, vfpSysreg); yield false; }
            case IrOp.SecureGateway sg -> { system.executeSecureGateway(core, sg); yield false; }
            case IrOp.SecureBranchExchange sbx -> branch.executeSecureBranchExchange(core, sbx);
            case IrOp.VlldmVlstm vlldmVlstm -> system.executeVlldmVlstm(core, vlldmVlstm);
            case IrOp.Vscclrm vscclrm -> { vfp.executeVscclrm(core, vscclrm); yield false; }
            case IrOp.LoopStart loopStart -> branch.executeLoopStart(core, loopStart);
            case IrOp.LoopEnd loopEnd -> branch.executeLoopEnd(core, loopEnd);
            case IrOp.Vpst vpst -> system.executeVpst(core, vpst);
            case IrOp.Vpnot vpnot -> system.executeVpnot(core, vpnot);
            case IrOp.Vpsel vpsel -> system.executeVpsel(core, vpsel);
            case IrOp.AdvanceVpt advanceVpt -> { system.executeAdvanceVpt(core, advanceVpt); yield false; }
            case IrOp.VprTransfer vprTransfer -> { system.executeVprTransfer(core, vprTransfer); yield false; }
            case IrOp.MveLoadStore mveLoadStore -> system.executeMveLoadStore(core, mveLoadStore);
            case IrOp.MveWideningLoadStore mveWideningLoadStore ->
                    system.executeMveWideningLoadStore(core, mveWideningLoadStore);
            case IrOp.MveGatherScatterOffset gatherScatterOffset ->
                    system.executeMveGatherScatterOffset(core, gatherScatterOffset);
            case IrOp.MveGatherScatterImmediate gatherScatterImmediate ->
                    system.executeMveGatherScatterImmediate(core, gatherScatterImmediate);
            case IrOp.MveInterleavedLoadStore interleavedLoadStore ->
                    system.executeMveInterleavedLoadStore(core, interleavedLoadStore);
            case IrOp.MveIncrementDup incrementDup -> system.executeMveIncrementDup(core, incrementDup);
            case IrOp.MveWrappingIncrementDup wrappingIncrementDup ->
                    system.executeMveWrappingIncrementDup(core, wrappingIncrementDup);
            case IrOp.AdvanceEci advanceEci -> { system.executeAdvanceEci(core, advanceEci); yield false; }
            case IrOp.MveVector2Op mveVector2Op -> system.executeMveVector2Op(core, mveVector2Op);
            case IrOp.MveVector2OpWidening mveVector2OpWidening ->
                    system.executeMveVector2OpWidening(core, mveVector2OpWidening);
            case IrOp.MveVectorCarry mveVectorCarry -> system.executeMveVectorCarry(core, mveVectorCarry);
            case IrOp.MveVectorComplexAdd mveVectorComplexAdd ->
                    system.executeMveVectorComplexAdd(core, mveVectorComplexAdd);
            case IrOp.MveVectorAbsAccumulate mveVectorAbsAccumulate ->
                    system.executeMveVectorAbsAccumulate(core, mveVectorAbsAccumulate);
            case IrOp.MveVectorFpAbsAccumulate mveVectorFpAbsAccumulate ->
                    system.executeMveVectorFpAbsAccumulate(core, mveVectorFpAbsAccumulate);
            case IrOp.MveVectorShiftWidenInterleaved mveVectorShiftWidenInterleaved ->
                    system.executeMveVectorShiftWidenInterleaved(core, mveVectorShiftWidenInterleaved);
            case IrOp.MveVectorNarrowInterleaved mveVectorNarrowInterleaved ->
                    system.executeMveVectorNarrowInterleaved(core, mveVectorNarrowInterleaved);
            case IrOp.MveVectorFpConvertPrecision mveVectorFpConvertPrecision ->
                    system.executeMveVectorFpConvertPrecision(core, mveVectorFpConvertPrecision);
            case IrOp.MveVectorFpComplexMultiply mveVectorFpComplexMultiply ->
                    system.executeMveVectorFpComplexMultiply(core, mveVectorFpComplexMultiply);
            case IrOp.MveVectorDualMultiplyAddHigh mveVectorDualMultiplyAddHigh ->
                    system.executeMveVectorDualMultiplyAddHigh(core, mveVectorDualMultiplyAddHigh);
            case IrOp.MveVectorDoublingWideningMultiply mveVectorDoublingWideningMultiply ->
                    system.executeMveVectorDoublingWideningMultiply(core, mveVectorDoublingWideningMultiply);
            case IrOp.MveVectorFpTwoOp mveVectorFpTwoOp -> system.executeMveVectorFpTwoOp(core, mveVectorFpTwoOp);
            case IrOp.MveVectorFpComplexAdd mveVectorFpComplexAdd ->
                    system.executeMveVectorFpComplexAdd(core, mveVectorFpComplexAdd);
            case IrOp.MveVectorFpComplexMultiplyAccumulate mveVectorFpComplexMultiplyAccumulate -> system
                    .executeMveVectorFpComplexMultiplyAccumulate(core, mveVectorFpComplexMultiplyAccumulate);
            case IrOp.MveVectorCompare mveVectorCompare -> system.executeMveVectorCompare(core, mveVectorCompare);
            case IrOp.MveVectorCompareScalar mveVectorCompareScalar ->
                    system.executeMveVectorCompareScalar(core, mveVectorCompareScalar);
            case IrOp.MveVectorScalar mveVectorScalar -> system.executeMveVectorScalar(core, mveVectorScalar);
            case IrOp.MveVectorScalarWidening mveVectorScalarWidening ->
                    system.executeMveVectorScalarWidening(core, mveVectorScalarWidening);
            case IrOp.MveVectorFpScalar mveVectorFpScalar -> system.executeMveVectorFpScalar(core, mveVectorFpScalar);
            case IrOp.MveVectorFpScalarFma mveVectorFpScalarFma ->
                    system.executeMveVectorFpScalarFma(core, mveVectorFpScalarFma);
            case IrOp.MveVectorScalarSpecial mveVectorScalarSpecial ->
                    system.executeMveVectorScalarSpecial(core, mveVectorScalarSpecial);
            case IrOp.MveVectorShiftImmediate mveVectorShiftImmediate ->
                    system.executeMveVectorShiftImmediate(core, mveVectorShiftImmediate);
            case IrOp.MveVectorShiftWidenImmediateInterleaved mveVectorShiftWidenImmediateInterleaved ->
                    system.executeMveVectorShiftWidenImmediateInterleaved(core, mveVectorShiftWidenImmediateInterleaved);
            case IrOp.MveVectorShiftNarrowImmediateInterleaved mveVectorShiftNarrowImmediateInterleaved ->
                    system.executeMveVectorShiftNarrowImmediateInterleaved(core, mveVectorShiftNarrowImmediateInterleaved);
            case IrOp.MveVectorShiftLeftCarry mveVectorShiftLeftCarry ->
                    system.executeMveVectorShiftLeftCarry(core, mveVectorShiftLeftCarry);
            case IrOp.MveVectorFpConvert mveVectorFpConvert ->
                    system.executeMveVectorFpConvert(core, mveVectorFpConvert);
            case IrOp.MveVectorFpConvertFixed mveVectorFpConvertFixed ->
                    system.executeMveVectorFpConvertFixed(core, mveVectorFpConvertFixed);
            case IrOp.MveVectorUnary mveVectorUnary -> system.executeMveVectorUnary(core, mveVectorUnary);
            case IrOp.MveVectorFpUnary mveVectorFpUnary -> system.executeMveVectorFpUnary(core, mveVectorFpUnary);
            case IrOp.MveVectorDup mveVectorDup -> system.executeMveVectorDup(core, mveVectorDup);
            case IrOp.MveMoveLanesGpr mveMoveLanesGpr -> system.executeMveMoveLanesGpr(core, mveMoveLanesGpr);
            case IrOp.MveVectorAddAcrossVector mveVectorAddAcrossVector ->
                    system.executeMveVectorAddAcrossVector(core, mveVectorAddAcrossVector);
            case IrOp.MveVectorAddAcrossVectorLong mveVectorAddAcrossVectorLong ->
                    system.executeMveVectorAddAcrossVectorLong(core, mveVectorAddAcrossVectorLong);
            case IrOp.MveVectorAbsoluteDifferenceAccumulate mveVectorAbsoluteDifferenceAccumulate -> system
                    .executeMveVectorAbsoluteDifferenceAccumulate(core, mveVectorAbsoluteDifferenceAccumulate);
            case IrOp.MveVectorModifiedImmediate mveVectorModifiedImmediate ->
                    system.executeMveVectorModifiedImmediate(core, mveVectorModifiedImmediate);
            case IrOp.MveVectorDualAccumulate mveVectorDualAccumulate ->
                    system.executeMveVectorDualAccumulate(core, mveVectorDualAccumulate);
            case IrOp.MveVectorDualAccumulateLong mveVectorDualAccumulateLong ->
                    system.executeMveVectorDualAccumulateLong(core, mveVectorDualAccumulateLong);
            case IrOp.MveVectorRoundingDualAccumulateHigh mveVectorRoundingDualAccumulateHigh -> system
                    .executeMveVectorRoundingDualAccumulateHigh(core, mveVectorRoundingDualAccumulateHigh);
            case IrOp.MveVectorMinMaxAcrossVector mveVectorMinMaxAcrossVector ->
                    system.executeMveVectorMinMaxAcrossVector(core, mveVectorMinMaxAcrossVector);
            case IrOp.MveVectorFpMinMaxAcrossVector mveVectorFpMinMaxAcrossVector ->
                    system.executeMveVectorFpMinMaxAcrossVector(core, mveVectorFpMinMaxAcrossVector);
        };
    }

    /// Executor de VFP (task B3.4): ver {@link #aluExecutor()}.
    public IrVfpExecutor vfpExecutor() {
        return vfp;
    }
}
