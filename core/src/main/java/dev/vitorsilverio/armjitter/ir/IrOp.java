package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IrOp.Alu, IrOp.Multiply, IrOp.LongMultiply, IrOp.Saturating, IrOp.DspMultiply, IrOp.ParallelAlu, IrOp.Sel, IrOp.Saturate, IrOp.AbsDiffSum, IrOp.PsrTransfer, IrOp.Load, IrOp.Store, IrOp.LoadExclusive, IrOp.StoreExclusive, IrOp.ClearExclusive, IrOp.DoubleTransfer, IrOp.Swap, IrOp.LoadLiteral, IrOp.MultipleTransfer, IrOp.Branch, IrOp.BranchExchange, IrOp.ThumbBlPrefix, IrOp.ThumbBlSuffix, IrOp.Push, IrOp.Pop, IrOp.Swi, IrOp.Coprocessor, IrOp.Undefined, IrOp.Cycle, IrOp.Fetch, IrOp.ChangeProcessorState, IrOp.SetEndianness, IrOp.StoreReturnState, IrOp.ReturnFromException, IrOp.WaitForInterrupt, IrOp.MoveTop, IrOp.MemoryBarrier, IrOp.SetItState, IrOp.TableBranch, IrOp.CompareBranchZero, IrOp.BitFieldExtract, IrOp.BitFieldInsert, IrOp.BitReverse, IrOp.Divide, IrOp.VfpAlu, IrOp.VfpMoveImmediate, IrOp.VfpCompare, IrOp.VfpConvert, IrOp.VfpLoad, IrOp.VfpStore, IrOp.VfpMultipleTransfer, IrOp.VfpCoreTransfer, IrOp.VfpCorePairTransfer, IrOp.VfpSystemTransfer, IrOp.MProfileSystemRegister, IrOp.Breakpoint, IrOp.CoprocessorDouble, IrOp.VfpCorePairTransferSingle, IrOp.VfpConvertFixed, IrOp.DspDualMultiply, IrOp.DspTopWordMultiply, IrOp.Hvc, IrOp.Smc, IrOp.Eret, IrOp.MrsBank, IrOp.MsrBank, IrOp.NeonThreeSame, IrOp.NeonLoadStoreMultiple, IrOp.NeonLoadStoreSingle,
        IrOp.NeonLoadAllLanes, IrOp.NeonPairwise, IrOp.NeonFpThreeSame, IrOp.NeonFpPairwise,
        IrOp.NeonShiftImmediate, IrOp.NeonShiftNarrowImmediate, IrOp.NeonShiftWidenImmediate,
        IrOp.NeonConvertFixedPoint, IrOp.NeonModifiedImmediate, IrOp.NeonWidening, IrOp.NeonWide,
        IrOp.NeonNarrow, IrOp.NeonThreeSameByElement, IrOp.NeonWideningByElement,
        IrOp.NeonFpThreeSameByElement, IrOp.NeonUnary, IrOp.NeonNarrowUnary, IrOp.NeonFpUnary,
        IrOp.NeonComplex, IrOp.NeonComplexByElement, IrOp.NeonDotProduct, IrOp.NeonDotProductByElement,
        IrOp.NeonSwapPermute, IrOp.NeonExtract, IrOp.NeonTableLookup, IrOp.NeonDuplicateScalar,
        IrOp.NeonCryptoAes, IrOp.NeonCryptoSha, IrOp.NeonFpConvertPrecision,
        IrOp.NeonMatrixMultiplyAccumulate, IrOp.NeonFusedMultiplyAddLong,
        IrOp.NeonFusedMultiplyAddLongByElement, IrOp.NeonDotProductBFloat16,
        IrOp.NeonDotProductByElementBFloat16, IrOp.NeonMatrixMultiplyAccumulateBFloat16,
        IrOp.NeonFusedMultiplyAddLongBFloat16, IrOp.NeonFusedMultiplyAddLongByElementBFloat16,
        IrOp.Nocp, IrOp.VfpSysregMemoryTransfer, IrOp.SecureGateway, IrOp.SecureBranchExchange,
        IrOp.VlldmVlstm, IrOp.Vscclrm, IrOp.LoopStart, IrOp.LoopEnd,
        IrOp.AdvanceVpt, IrOp.Vpst, IrOp.Vpnot, IrOp.Vpsel, IrOp.VprTransfer, IrOp.MveLoadStore,
        IrOp.MveWideningLoadStore, IrOp.MveGatherScatterOffset, IrOp.MveGatherScatterImmediate,
        IrOp.MveInterleavedLoadStore, IrOp.MveIncrementDup, IrOp.MveWrappingIncrementDup,
        IrOp.AdvanceEci, IrOp.MveVector2Op, IrOp.MveVector2OpWidening, IrOp.MveVectorCarry,
        IrOp.MveVectorComplexAdd, IrOp.MveVectorAbsAccumulate, IrOp.MveVectorFpAbsAccumulate,
        IrOp.MveVectorShiftWidenInterleaved, IrOp.MveVectorNarrowInterleaved, IrOp.MveVectorFpConvertPrecision,
        IrOp.MveVectorFpComplexMultiply, IrOp.MveVectorDualMultiplyAddHigh, IrOp.MveVectorDoublingWideningMultiply,
        IrOp.MveVectorFpTwoOp, IrOp.MveVectorFpComplexAdd, IrOp.MveVectorFpComplexMultiplyAccumulate,
        IrOp.MveVectorCompare, IrOp.MveVectorCompareScalar, IrOp.MveVectorScalar, IrOp.MveVectorScalarWidening,
        IrOp.MveVectorFpScalar, IrOp.MveVectorFpScalarFma, IrOp.MveVectorScalarSpecial,
        IrOp.MveVectorShiftImmediate, IrOp.MveVectorShiftWidenImmediateInterleaved,
        IrOp.MveVectorShiftNarrowImmediateInterleaved, IrOp.MveVectorShiftLeftCarry,
        IrOp.MveVectorFpConvert, IrOp.MveVectorFpConvertFixed {
    /// Retorna a condição de execução da operação.
    /// {@link IrOp.Cycle} e {@link IrOp.Fetch} não possuem condição: retornam {@link Condition#AL}.
    default Condition condition() { return Condition.AL; }

    /// Discriminador de tipo para dispatch O(1) no interpretador (constantes em {@link Kind}).
    ///
    /// Permite ao {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} usar um
    /// `switch` inteiro (`tableswitch`) em vez do `switch` por padrão de tipo, cuja varredura
    /// linear de `instanceof` (via `SwitchBootstraps.typeSwitch`) custava ~13% do tempo no
    /// loop quente do interpretador.
    int kind();

    /// Constantes de {@link IrOp#kind()} — uma por subtipo selado, contíguas a partir de 0
    /// para que o `switch` do interpretador compile como `tableswitch`.
    final class Kind {
        private Kind() {
        }

        public static final int ALU = 0;
        public static final int MULTIPLY = 1;
        public static final int LONG_MULTIPLY = 2;
        public static final int SATURATING = 3;
        public static final int DSP_MULTIPLY = 4;
        public static final int PSR_TRANSFER = 5;
        public static final int LOAD = 6;
        public static final int STORE = 7;
        public static final int DOUBLE_TRANSFER = 8;
        public static final int SWAP = 9;
        public static final int LOAD_LITERAL = 10;
        public static final int MULTIPLE_TRANSFER = 11;
        public static final int BRANCH = 12;
        public static final int BRANCH_EXCHANGE = 13;
        public static final int THUMB_BL_PREFIX = 14;
        public static final int THUMB_BL_SUFFIX = 15;
        public static final int PUSH = 16;
        public static final int POP = 17;
        public static final int SWI = 18;
        public static final int COPROCESSOR = 19;
        public static final int UNDEFINED = 20;
        public static final int CYCLE = 21;
        public static final int FETCH = 22;
        public static final int PARALLEL_ALU = 23;
        public static final int SEL = 24;
        public static final int SATURATE = 25;
        public static final int ABS_DIFF_SUM = 26;
        public static final int LOAD_EXCLUSIVE = 27;
        public static final int STORE_EXCLUSIVE = 28;
        public static final int CLEAR_EXCLUSIVE = 29;
        public static final int CHANGE_PROCESSOR_STATE = 30;
        public static final int SET_ENDIANNESS = 31;
        public static final int STORE_RETURN_STATE = 32;
        public static final int RETURN_FROM_EXCEPTION = 33;
        public static final int WAIT_FOR_INTERRUPT = 34;
        public static final int MOVE_TOP = 35;
        public static final int MEMORY_BARRIER = 36;
        public static final int SET_IT_STATE = 37;
        public static final int TABLE_BRANCH = 38;
        public static final int COMPARE_BRANCH_ZERO = 39;
        public static final int BIT_FIELD_EXTRACT = 40;
        public static final int BIT_FIELD_INSERT = 41;
        public static final int BIT_REVERSE = 42;
        public static final int DIVIDE = 43;
        public static final int VFP_ALU = 44;
        public static final int VFP_MOVE_IMMEDIATE = 45;
        public static final int VFP_COMPARE = 46;
        public static final int VFP_CONVERT = 47;
        public static final int VFP_LOAD = 48;
        public static final int VFP_STORE = 49;
        public static final int VFP_MULTIPLE_TRANSFER = 50;
        public static final int VFP_CORE_TRANSFER = 51;
        public static final int VFP_CORE_PAIR_TRANSFER = 52;
        public static final int VFP_SYSTEM_TRANSFER = 53;
        public static final int M_PROFILE_SYSTEM_REGISTER = 54;
        public static final int BREAKPOINT = 55;
        public static final int COPROCESSOR_DOUBLE = 56;
        public static final int VFP_CORE_PAIR_TRANSFER_SINGLE = 57;
        public static final int VFP_CONVERT_FIXED = 58;
        public static final int DSP_DUAL_MULTIPLY = 59;
        public static final int DSP_TOP_WORD_MULTIPLY = 60;
        public static final int HVC = 61;
        public static final int SMC = 62;
        public static final int ERET = 63;
        public static final int MRS_BANK = 64;
        public static final int MSR_BANK = 65;
        public static final int NEON_THREE_SAME = 66;
        public static final int NEON_LOAD_STORE_MULTIPLE = 67;
        public static final int NEON_LOAD_STORE_SINGLE = 68;
        public static final int NEON_LOAD_ALL_LANES = 69;
        public static final int NEON_PAIRWISE = 70;
        public static final int NEON_FP_THREE_SAME = 71;
        public static final int NEON_FP_PAIRWISE = 72;
        public static final int NEON_SHIFT_IMMEDIATE = 73;
        public static final int NEON_SHIFT_NARROW_IMMEDIATE = 74;
        public static final int NEON_SHIFT_WIDEN_IMMEDIATE = 75;
        public static final int NEON_CONVERT_FIXED_POINT = 76;
        public static final int NEON_MODIFIED_IMMEDIATE = 77;
        public static final int NEON_WIDENING = 78;
        public static final int NEON_WIDE = 79;
        public static final int NEON_NARROW = 80;
        /// B13.11: `VMLA`/`VMLS`/`VMUL` (inteiro, `2-regs-plus-scalar`, mesma largura) e
        /// `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` — ver {@link NeonThreeSameByElement}.
        public static final int NEON_THREE_SAME_BY_ELEMENT = 81;
        /// B13.11: `VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL` (`2-regs-plus-scalar`,
        /// alargando) — ver {@link NeonWideningByElement}.
        public static final int NEON_WIDENING_BY_ELEMENT = 82;
        /// B13.11: `VMLA_F`/`VMLS_F`/`VMUL_F` (`2-regs-plus-scalar`, ponto flutuante F32, NÃO
        /// fundido) — ver {@link NeonFpThreeSameByElement}.
        public static final int NEON_FP_THREE_SAME_BY_ELEMENT = 83;
        /// B13.12: `VREV64`/`VREV32`/`VREV16`/`VPADDL`/`VPADAL`/`VCLS`/`VCLZ`/`VCNT`/`VMVN`/
        /// `VQABS`/`VQNEG`/as 5 comparações-com-zero inteiras/`VABS`/`VNEG`/`VRECPE`/`VRSQRTE`
        /// (dois-registradores-misc, `size==0b11`) — ver {@link NeonUnary}.
        public static final int NEON_UNARY = 84;
        /// B13.12: `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U` — ver {@link NeonNarrowUnary}.
        public static final int NEON_NARROW_UNARY = 85;
        /// B13.12: `VABS_F`/`VNEG_F`/as 5 comparações-com-zero FP/`VRECPE_F`/`VRSQRTE_F` — ver
        /// {@link NeonFpUnary}.
        public static final int NEON_FP_UNARY = 86;
        /// B13.17: `VCMLA`/`VCADD` (`neon-shared`, `FEAT_FCMA`) — ver {@link NeonComplex}.
        public static final int NEON_COMPLEX = 87;
        /// B13.17: `VCMLA_scalar` (`neon-shared`, `FEAT_FCMA`) — ver {@link NeonComplexByElement}.
        public static final int NEON_COMPLEX_BY_ELEMENT = 88;
        /// B13.18: `VSDOT`/`VUDOT`/`VUSDOT` (`neon-shared`, `FEAT_DotProd`/`FEAT_I8MM`) — ver
        /// {@link NeonDotProduct}.
        public static final int NEON_DOT_PRODUCT = 89;
        /// B13.18: `VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar` — ver
        /// {@link NeonDotProductByElement}.
        public static final int NEON_DOT_PRODUCT_BY_ELEMENT = 90;
        /// B13.14: `VSWP`/`VTRN`/`VUZP`/`VZIP` — ver {@link NeonSwapPermute}.
        public static final int NEON_SWAP_PERMUTE = 91;
        /// B13.14: `VEXT` — ver {@link NeonExtract}.
        public static final int NEON_EXTRACT = 92;
        /// B13.14: `VTBL`/`VTBX` — ver {@link NeonTableLookup}.
        public static final int NEON_TABLE_LOOKUP = 93;
        /// B13.14: `VDUP` escalar (NEON, `Vd = Vm[index]` replicado) — ver {@link NeonDuplicateScalar}.
        public static final int NEON_DUPLICATE_SCALAR = 94;
        /// B13.15: `AESE`/`AESD`/`AESMC`/`AESIMC` — ver {@link NeonCryptoAes}.
        public static final int NEON_CRYPTO_AES = 95;
        /// B13.15: `SHA1H`/`SHA1SU1`/`SHA256SU0` — ver {@link NeonCryptoSha}.
        public static final int NEON_CRYPTO_SHA = 96;
        /// B13.13: `VCVT_F16_F32`/`VCVT_B16_F32`/`VCVT_F32_F16` (conversão de precisão) — ver
        /// {@link NeonFpConvertPrecision}.
        public static final int NEON_FP_CONVERT_PRECISION = 97;
        /// B13.19: `VSMMLA`/`VUMMLA`/`VUSMMLA` (`neon-shared`, `FEAT_I8MM`) — ver
        /// {@link NeonMatrixMultiplyAccumulate}.
        public static final int NEON_MATRIX_MULTIPLY_ACCUMULATE = 98;
        /// B13.20: `VFML`/`VFMSL` (`neon-shared`, `FEAT_FHM`) — ver
        /// {@link NeonFusedMultiplyAddLong}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG = 99;
        /// B13.20: `VFML_scalar`/`VFMSL_scalar` (`neon-shared`, `FEAT_FHM`) — ver
        /// {@link NeonFusedMultiplyAddLongByElement}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT = 100;
        /// B13.21: `VDOT_b16` (`neon-shared`, `FEAT_BF16`) — ver {@link NeonDotProductBFloat16}.
        public static final int NEON_DOT_PRODUCT_BFLOAT16 = 101;
        /// B13.21: `VDOT_b16_scal` — ver {@link NeonDotProductByElementBFloat16}.
        public static final int NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16 = 102;
        /// B13.21: `VMMLA_b16` (`neon-shared`, `FEAT_BF16`) — ver
        /// {@link NeonMatrixMultiplyAccumulateBFloat16}.
        public static final int NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 = 103;
        /// B13.21: `VFMA_b16` (`VFMAB`/`VFMAT`, `neon-shared`, `FEAT_BF16`) — ver
        /// {@link NeonFusedMultiplyAddLongBFloat16}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16 = 104;
        /// B13.21: `VFMA_b16_scal` — ver {@link NeonFusedMultiplyAddLongByElementBFloat16}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 = 105;
        /// B15.2: `NOCP`/`NOCP_8_1` (perfil M) — ver {@link Nocp}.
        public static final int NOCP = 106;
        /// B15.3: `VLDR_sysreg`/`VSTR_sysreg` (perfil M) — ver {@link VfpSysregMemoryTransfer}.
        public static final int VFP_SYSREG_MEMORY_TRANSFER = 107;
        /// B15.4: `SG` (perfil M, Security Extension) — ver {@link SecureGateway}.
        public static final int SECURE_GATEWAY = 108;
        /// B15.4: `BXNS`/`BLXNS` (perfil M, Security Extension) — ver {@link SecureBranchExchange}.
        public static final int SECURE_BRANCH_EXCHANGE = 109;
        /// B15.5: `VLLDM`/`VLSTM` (perfil M, `m-nocp.decode`) — ver {@link VlldmVlstm}.
        public static final int VLLDM_VLSTM = 110;
        /// B15.5: `VSCCLRM` (perfil M, `m-nocp.decode`) — ver {@link Vscclrm}.
        public static final int VSCCLRM = 111;
        /// B15.6: `DLS`/`WLS` (perfil M, Low Overhead Branch, `t32.decode`) — ver {@link LoopStart}.
        public static final int LOOP_START = 112;
        /// B15.6: `LE` (perfil M, Low Overhead Branch, `t32.decode`) — ver {@link LoopEnd}.
        public static final int LOOP_END = 113;
        /// B16.2: avanço pós-instrução do `VPR`/`ECI` (MVE/Helium) — ver {@link AdvanceVpt}.
        public static final int ADVANCE_VPT = 114;
        /// B16.2: `VPST` (perfil M, MVE/Helium) — ver {@link Vpst}.
        public static final int VPST = 115;
        /// B16.2: `VPNOT` (perfil M, MVE/Helium) — ver {@link Vpnot}.
        public static final int VPNOT = 116;
        /// B16.2: `VPSEL` (perfil M, MVE/Helium) — ver {@link Vpsel}.
        public static final int VPSEL = 117;
        /// B16.2: `VMSR_VMRS` com `reg=12` (`VPR`, perfil M, MVE/Helium) — ver {@link VprTransfer}.
        public static final int VPR_TRANSFER = 118;
        /// B16.3: `VLDR_VSTR` contíguo não-alargante (perfil M, MVE/Helium) — ver {@link MveLoadStore}.
        public static final int MVE_LOAD_STORE = 119;
        /// B16.4: `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (load alargante/store estreitante, perfil M,
        /// MVE/Helium) — ver {@link MveWideningLoadStore}.
        public static final int MVE_WIDENING_LOAD_STORE = 120;
        /// B16.5: `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (gather/scatter por vetor de offsets, perfil M,
        /// MVE/Helium) — ver {@link MveGatherScatterOffset}.
        public static final int MVE_GATHER_SCATTER_OFFSET = 121;
        /// B16.5: `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (gather/scatter com
        /// base vetorial e imediato, perfil M, MVE/Helium) — ver {@link MveGatherScatterImmediate}.
        public static final int MVE_GATHER_SCATTER_IMMEDIATE = 122;
        /// B16.5: `VLD2`/`VLD4`/`VST2`/`VST4` (desentrelaçamento, perfil M, MVE/Helium) — ver
        /// {@link MveInterleavedLoadStore}.
        public static final int MVE_INTERLEAVED_LOAD_STORE = 123;
        /// B16.5: `VIDUP`/`VDDUP` (perfil M, MVE/Helium) — ver {@link MveIncrementDup}.
        public static final int MVE_INCREMENT_DUP = 124;
        /// B16.5: `VIWDUP`/`VDWDUP` (perfil M, MVE/Helium) — ver {@link MveWrappingIncrementDup}.
        public static final int MVE_WRAPPING_INCREMENT_DUP = 125;
        /// B16.5: avanço pós-instrução SÓ do `ECI` (`mve_update_and_store_eci`), sem tocar o `VPR`
        /// (perfil M, MVE/Helium) — ver {@link AdvanceEci}.
        public static final int ADVANCE_ECI = 126;
        /// B16.6: vector 2-op inteiro reusando {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp}
        /// (perfil M, MVE/Helium) — ver {@link MveVector2Op}.
        public static final int MVE_VECTOR_2OP = 127;
        /// B16.6: `VMULLP_B`/`VMULLP_T`/`VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (alargante,
        /// perfil M, MVE/Helium) — ver {@link MveVector2OpWidening}.
        public static final int MVE_VECTOR_2OP_WIDENING = 128;
        /// B16.6: `VADC`/`VADCI`/`VSBC`/`VSBCI` (carry encadeado por `FPSCR.C`, perfil M,
        /// MVE/Helium) — ver {@link MveVectorCarry}.
        public static final int MVE_VECTOR_CARRY = 129;
        /// B16.6: `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (soma complexa inteira, perfil M,
        /// MVE/Helium) — ver {@link MveVectorComplexAdd}.
        public static final int MVE_VECTOR_COMPLEX_ADD = 130;
        /// B16.7: `VMAXA`/`VMINA` (acumula `|sext(Qm)|` em `Qd`, inteiro, perfil M, MVE/Helium) —
        /// ver {@link MveVectorAbsAccumulate}.
        public static final int MVE_VECTOR_ABS_ACCUMULATE = 131;
        /// B16.7: `VMAXNMA`/`VMINNMA` (acumula `|Qm|` em `Qd`, ponto flutuante, `FEAT_MVE_FP`, perfil
        /// M, MVE/Helium) — ver {@link MveVectorFpAbsAccumulate}.
        public static final int MVE_VECTOR_FP_ABS_ACCUMULATE = 132;
        /// B16.7: `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma T2 (`shift == esize`, perfil M,
        /// MVE/Helium) — ver {@link MveVectorShiftWidenInterleaved}.
        public static final int MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED = 133;
        /// B16.7: `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M,
        /// MVE/Helium) — ver {@link MveVectorNarrowInterleaved}.
        public static final int MVE_VECTOR_NARROW_INTERLEAVED = 134;
        /// B16.7: `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (conversão binary16↔binary32
        /// "bottom"/"top", `FEAT_MVE_FP`, perfil M, MVE/Helium) — ver {@link MveVectorFpConvertPrecision}.
        public static final int MVE_VECTOR_FP_CONVERT_PRECISION = 135;
        /// B16.7 sub-família 2: `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveVectorFpComplexMultiply}.
        public static final int MVE_VECTOR_FP_COMPLEX_MULTIPLY = 136;
        /// B16.7 sub-família 2: `VQDMLADH`/`VQDMLSDH` e variantes `X`/`R` (perfil M, MVE/Helium) —
        /// ver {@link MveVectorDualMultiplyAddHigh}.
        public static final int MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH = 137;
        /// B16.7 sub-família 2: `VQDMULLB`/`VQDMULLT` (perfil M, MVE/Helium) — ver
        /// {@link MveVectorDoublingWideningMultiply}.
        public static final int MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY = 138;
        /// B16.7 sub-família 3: `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/
        /// `VFMS` (`FEAT_MVE_FP`, perfil M, MVE/Helium) — ver {@link MveVectorFpTwoOp}.
        public static final int MVE_VECTOR_FP_TWO_OP = 139;
        /// B16.7 sub-família 3: `VCADD90_fp`/`VCADD270_fp` (`FEAT_MVE_FP`, perfil M, MVE/Helium) —
        /// ver {@link MveVectorFpComplexAdd}.
        public static final int MVE_VECTOR_FP_COMPLEX_ADD = 140;
        /// B16.7 sub-família 3: `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveVectorFpComplexMultiplyAccumulate}.
        public static final int MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE = 141;
        /// B16.8: `VCMP*`/`VCMP*_fp` vetor×vetor (perfil M, MVE/Helium) — ver {@link MveVectorCompare}.
        public static final int MVE_VECTOR_COMPARE = 142;
        /// B16.8: `VCMP*_scalar`/`VCMP*_fp_scalar` vetor×GPR (perfil M, MVE/Helium) — ver
        /// {@link MveVectorCompareScalar}.
        public static final int MVE_VECTOR_COMPARE_SCALAR = 143;
        /// B16.9: `VADD_scalar`…`VQRDMULH_scalar`/`VMLA` (`@2scalar`) e `VSHL_S_scalar`…
        /// `VQRSHL_U_scalar` (`@shl_scalar`), reusando
        /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} com o segundo
        /// operando vindo de um GPR (perfil M, MVE/Helium) — ver {@link MveVectorScalar}.
        public static final int MVE_VECTOR_SCALAR = 144;
        /// B16.9: `VQDMULLB_scalar`/`VQDMULLT_scalar` (alargante, escalar, perfil M, MVE/Helium) —
        /// ver {@link MveVectorScalarWidening}.
        public static final int MVE_VECTOR_SCALAR_WIDENING = 145;
        /// B16.9: `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveVectorFpScalar}.
        public static final int MVE_VECTOR_FP_SCALAR = 146;
        /// B16.9: `VFMA_scalar`/`VFMAS_scalar` (fundido, `FEAT_MVE_FP`, perfil M, MVE/Helium) — ver
        /// {@link MveVectorFpScalarFma}.
        public static final int MVE_VECTOR_FP_SCALAR_FMA = 147;
        /// B16.9: `VBRSR`/`VMLAS`/`VQDMLAH`/`VQRDMLAH`/`VQDMLASH`/`VQRDMLASH` (formas escalares SEM
        /// análogo em {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp}, perfil M,
        /// MVE/Helium) — ver
        /// {@link MveVectorScalarSpecial}.
        public static final int MVE_VECTOR_SCALAR_SPECIAL = 148;
        /// B16.10: `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/
        /// `VSRI`/`VSLI` (deslocamento por imediato + shift-and-insert, perfil M, MVE/Helium) — ver
        /// {@link MveVectorShiftImmediate}.
        public static final int MVE_VECTOR_SHIFT_IMMEDIATE = 149;
        /// B16.10: `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T1** (`shift < esize`, inclui
        /// `VMOVL` = `shift == 0`, perfil M, MVE/Helium) — ver
        /// {@link MveVectorShiftWidenImmediateInterleaved}.
        public static final int MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED = 150;
        /// B16.11: `VSHRNB`/`VSHRNT`/`VRSHRNB`/`VRSHRNT`/`VQSHRNB_S`/`VQSHRNT_S`/`VQSHRNB_U`/
        /// `VQSHRNT_U`/`VQSHRUNB`/`VQSHRUNT`/`VQRSHRNB_S`/`VQRSHRNT_S`/`VQRSHRNB_U`/`VQRSHRNT_U`/
        /// `VQRSHRUNB`/`VQRSHRUNT` (deslocamento estreitante, só `b`/`h`, perfil M, MVE/Helium) —
        /// ver {@link MveVectorShiftNarrowImmediateInterleaved}.
        public static final int MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED = 151;
        /// B16.11: `VSHLC` (deslocamento à esquerda do vetor INTEIRO com carry em GPR, perfil M,
        /// MVE/Helium) — ver {@link MveVectorShiftLeftCarry}.
        public static final int MVE_VECTOR_SHIFT_LEFT_CARRY = 152;
        /// B16.12: `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU`/`VCVTA{S,U}`/`VCVTN{S,U}`/`VCVTP{S,U}`/
        /// `VCVTM{S,U}`/`VRINTN`/`VRINTX`/`VRINTA`/`VRINTZ`/`VRINTM`/`VRINTP` (`FEAT_MVE_FP`, perfil
        /// M, MVE/Helium) — ver {@link MveVectorFpConvert}.
        public static final int MVE_VECTOR_FP_CONVERT = 153;
        /// B16.12: `VCVT_SH_fixed`/`VCVT_UH_fixed`/`VCVT_HS_fixed`/`VCVT_HU_fixed`/`VCVT_SF_fixed`/
        /// `VCVT_UF_fixed`/`VCVT_FS_fixed`/`VCVT_FU_fixed` (`FEAT_MVE_FP`, perfil M, MVE/Helium) —
        /// ver {@link MveVectorFpConvertFixed}.
        public static final int MVE_VECTOR_FP_CONVERT_FIXED = 154;
    }

    /// Operacao ALU generica.
    record Alu(
            /// Mnemonico ou identificador interno da operacao.
            IrOpCode opcode,
            /// Registrador de destino.
            int dst,
            /// Primeiro registrador de origem.
            int src1,
            /// Valor fixo para usar no lugar de `src1`, ou `-1`.
            int src1ValueOverride,
            /// Segundo operando, que pode ser registrador ou imediato.
            IrOperand src2,
            /// Indica se NZCV deve ser atualizado.
            boolean setFlags,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.ALU; }
    }

    /// Operacao de multiplicacao baixa, com acumulador opcional.
    record Multiply(
            /// Registrador de destino.
            int dst,
            /// Primeiro fator.
            int rm,
            /// Valor fixo para `rm`, ou `-1`.
            int rmValueOverride,
            /// Segundo fator.
            int rs,
            /// Valor fixo para `rs`, ou `-1`.
            int rsValueOverride,
            /// Registrador acumulador, ou `-1` quando não se aplica.
            int rn,
            /// Valor fixo para `rn`, ou `-1`.
            int rnValueOverride,
            /// Indica se o acumulador deve ser somado.
            boolean accumulate,
            /// `true` para `MLS` (ARMv6T2+, B3.1): `Rd = Ra − Rm×Rs` em vez de `Rd = Ra + Rm×Rs`.
            /// Só válido quando {@code accumulate} também é `true`; `MUL`/`MLA` sempre passam `false`.
            boolean subtractFromAccumulator,
            /// Indica se NZ deve ser atualizado.
            boolean setFlags,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MULTIPLY; }
    }

    /// Operação de multiplicação longa, com acumulador opcional.
    record LongMultiply(
            /// Registrador que recebe os 32 bits baixos.
            int dstLow,
            /// Registrador que recebe os 32 bits altos.
            int dstHigh,
            /// Primeiro fator.
            int rm,
            /// Valor fixo para `rm`, ou `-1`.
            int rmValueOverride,
            /// Segundo fator.
            int rs,
            /// Valor fixo para `rs`, ou `-1`.
            int rsValueOverride,
            /// Valor fixo para o registrador alto atual em acumulação, ou `-1`.
            int dstHighValueOverride,
            /// Valor fixo para o registrador baixo atual em acumulação, ou `-1`.
            int dstLowValueOverride,
            /// Indica multiplicação com sinal.
            boolean signed,
            /// Indica se o par destino (como valor único de 64 bits) deve ser somado ao produto.
            boolean accumulate,
            /// Acumulador duplo do `UMAAL` (ARMv6): soma RdLo e RdHi ao produto como duas parcelas
            /// de 32 bits sem sinal independentes — não como um par de 64 bits.
            boolean accumulateDouble,
            /// Indica se NZ deve ser atualizado a partir do resultado de 64 bits.
            boolean setFlags,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        /// Construtor de compatibilidade (pré-ARMv6), sem o acumulador duplo do `UMAAL`.
        public LongMultiply(int dstLow, int dstHigh, int rm, int rmValueOverride, int rs,
                int rsValueOverride, int dstHighValueOverride, int dstLowValueOverride,
                boolean signed, boolean accumulate, boolean setFlags, Condition condition) {
            this(dstLow, dstHigh, rm, rmValueOverride, rs, rsValueOverride, dstHighValueOverride,
                    dstLowValueOverride, signed, accumulate, false, setFlags, condition);
        }

        @Override public int kind() { return Kind.LONG_MULTIPLY; }
    }

    /// Transferência entre registradores gerais e CPSR/SPSR.
    record PsrTransfer(
            /// `true` para MRS, `false` para MSR.
            boolean read,
            /// `true` para SPSR, `false` para CPSR.
            boolean spsr,
            /// Registrador geral de destino/origem.
            int register,
            /// Valor fixo para usar na escrita por registrador, ou `-1`.
            int registerValueOverride,
            /// Imediato expandido para `MSR #imm`.
            int immediate,
            /// Indica que `immediate` deve ser usado no lugar de `register`.
            boolean immediateOperand,
            /// Máscara de campos PSR para MSR.
            int fieldMask,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.PSR_TRANSFER; }
    }

    /// Operação de leitura de memória.
    record Load(
            /// Registrador de destino.
            int dst,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica extensão com sinal.
            boolean signed,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// `LDRxT` (B9.9): quando `true`, o acesso à memória usa a permissão de modo `USER`
            /// mesmo que o CPU esteja em modo privilegiado — ver
            /// {@link dev.vitorsilverio.armjitter.memory.AddressSpace#withUnprivilegedAccess}.
            boolean unprivileged,
            /// Condição necessária para executar a leitura.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.LOAD; }
    }

    /// Operação de escrita de memória.
    record Store(
            /// Registrador de origem.
            int src,
            /// Valor fixo para usar como valor armazenado, ou `-1`.
            int srcValueOverride,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// `STRxT` (B9.9): ver {@link Load#unprivileged}.
            boolean unprivileged,
            /// Condição necessária para executar a escrita.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.STORE; }
    }

    /// `LDREX{,B,H,D}` (ARMv6/v6K) e `LDREX` de 32 bits Thumb-2 (B2.7 PR3): lê a memória no
    /// endereço `base+offset` e marca o monitor de exclusividade do core. A forma doubleword
    /// (`sizeBytes=8`) carrega o par `dst`, `dst+1`. Formas com PC não passam pelo decoder
    /// (UNPREDICTABLE).
    record LoadExclusive(
            /// Registrador de destino (primeiro do par na forma doubleword).
            int dst,
            /// Registrador base do endereço (Rn).
            int base,
            /// Offset com sinal somado a `base`. Só o `LDREX` word de 32 bits Thumb-2 tem offset
            /// não-nulo (`imm8×4` — ver `Thumb2LoadStoreDecoder`); ARM clássico e as formas
            /// `B`/`H`/`D` (ARM ou Thumb-2) sempre passam `0` aqui, igual ao endereço exato `[Rn]`
            /// que a arquitetura exige para elas.
            int offset,
            /// Tamanho do acesso em bytes (1, 2, 4 ou 8).
            int sizeBytes,
            /// Condição necessária para executar a leitura.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.LOAD_EXCLUSIVE; }
    }

    /// `STREX{,B,H,D}` (ARMv6/v6K) e `STREX` de 32 bits Thumb-2 (B2.7 PR3): escreve a memória em
    /// `base+offset` APENAS se o monitor de exclusividade cobre o endereço/tamanho; `dst` recebe
    /// 0 (sucesso, monitor consumido) ou 1 (falha, a memória fica intacta). A forma doubleword
    /// armazena o par `src`, `src+1`.
    record StoreExclusive(
            /// Registrador de status (0 = sucesso, 1 = falha).
            int dst,
            /// Registrador com o valor armazenado (primeiro do par na forma doubleword).
            int src,
            /// Registrador base do endereço (Rn).
            int base,
            /// Offset com sinal somado a `base`. Ver {@link LoadExclusive#offset}: só o `STREX`
            /// word de 32 bits Thumb-2 tem offset não-nulo.
            int offset,
            /// Tamanho do acesso em bytes (1, 2, 4 ou 8).
            int sizeBytes,
            /// Condição necessária para executar a escrita.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.STORE_EXCLUSIVE; }
    }

    /// `CLREX` (ARMv6K): abre o monitor de exclusividade do core.
    record ClearExclusive(
            /// Condição necessária para executar (CLREX vive no espaço incondicional → AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.CLEAR_EXCLUSIVE; }
    }

    /// Transferência de palavra dupla (LDRD/STRD): dois acessos de 32 bits consecutivos a
    /// `first` e `second`, com um único cálculo de endereço/writeback.
    record DoubleTransfer(
            /// `true` para LDRD (load), `false` para STRD (store).
            boolean load,
            /// Primeiro registrador do par (Rt).
            int first,
            /// Segundo registrador do par (Rt2). No ARM clássico (ARMv5TE) o encoding só tem um
            /// campo Rd, então `second` é sempre `first + 1`; no Thumb-2 (B2.3) `Rt`/`Rt2` são
            /// campos independentes no encoding e podem ser um par arbitrário (não-adjacente).
            int second,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo da base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.DOUBLE_TRANSFER; }
    }

    /// Troca valor de memória com registrador.
    record Swap(
            /// Registrador que recebe o valor antigo da memória.
            int dst,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Registrador cujo valor será escrito na memória.
            int src,
            /// Valor fixo para usar como valor escrito, ou `-1`.
            int srcValueOverride,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Condição necessária para executar a troca.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SWAP; }
    }

    /// Lê um valor de endereço absoluto literal (pool de constantes relativo ao PC).
    record LoadLiteral(
            /// Registrador de destino.
            int dst,
            /// Endereço absoluto a ler (já alinhado/resolvido pelo decoder — ver `ADR`/`LDR
            /// Rt,[PC,#imm]`).
            int address,
            /// Tamanho do acesso em bytes (1, 2 ou 4). Thumb-1 só tinha a forma word (4); as formas
            /// Thumb-2 `LDRB`/`LDRH`/`LDRSB`/`LDRSH` literais (B2.3) reusam este mesmo IrOp.
            int sizeBytes,
            /// Indica extensão com sinal (`LDRSB`/`LDRSH` literais, B2.3).
            boolean signed,
            /// Condição necessária para executar a leitura.
            Condition condition) implements IrOp {
        /// Cria uma leitura literal de word sem sinal (forma clássica Thumb-1).
        public LoadLiteral(int dst, int address, Condition condition) {
            this(dst, address, 4, false, condition);
        }

        @Override public int kind() { return Kind.LOAD_LITERAL; }
    }

    /// Transferência sequencial de múltiplos registradores.
    record MultipleTransfer(
            /// `true` para load, `false` para store.
            boolean load,
            /// Registrador base.
            int base,
            /// Máscara de registradores.
            int registerMask,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Valor de `PC` a armazenar quando a máscara contém r15, ou `-1`.
            int pcStoreValueOverride,
            /// Usa banco USR/SYS ou restaura CPSR pelo SPSR em `LDM ... pc^`.
            boolean userMode,
            /// Modo de endereçamento ARM/THUMB.
            BlockTransferMode mode,
            /// Indica máscara vazia em `LDM`/`STM`, caso especial do ARM7TDMI.
            boolean emptyRegisterList,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MULTIPLE_TRANSFER; }
    }

    /// Operação de branch.
    record Branch(
            /// Endereço absoluto de destino quando conhecido.
            int target,
            /// Valor a gravar no link register quando `link` estiver ativo.
            int returnAddress,
            /// Indica atualização do link register.
            boolean link,
            /// Condição necessária para tomar o branch.
            Condition condition,
            /// Conjunto de instruções esperado após o branch.
            InstructionSet targetSet) implements IrOp {
        @Override public int kind() { return Kind.BRANCH; }
    }

    /// Aritmética de saturação ARMv5TE (QADD/QSUB/QDADD/QDSUB). `op`: 0=QADD, 1=QSUB,
    /// 2=QDADD, 3=QDSUB. Satura em 32 bits com sinal e ativa o bit Q em overflow.
    record Saturating(
            /// Registrador de destino.
            int dst,
            /// Operando somado/subtraído (Rm).
            int rm,
            /// Operando "n" (Rn), dobrado nas formas QD*.
            int rn,
            /// Seleciona a operação (0..3).
            int op,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SATURATING; }
    }

    /// Multiplicações DSP ARMv5TE. `op2`: 0=SMLAxy, 1=SMLAW(x=0)/SMULW(x=1), 2=SMLALxy, 3=SMULxy.
    /// `x`/`y` selecionam a metade (baixa/alta) de Rm/Rs (em SMLAW/SMULW, `x` escolhe acumular).
    /// Em SMLAL, `dst` é RdHi e `rn` é RdLo.
    record DspMultiply(
            /// Registrador de destino (RdHi em SMLAL).
            int dst,
            /// Acumulador Rn (RdLo em SMLAL).
            int rn,
            /// Primeiro fator (Rm).
            int rm,
            /// Segundo fator (Rs).
            int rs,
            /// Subtipo (0..3).
            int op2,
            /// Seleção de metade de Rm (ou seletor SMLAW/SMULW quando op2=1).
            int x,
            /// Seleção de metade de Rs.
            int y,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.DSP_MULTIPLY; }
    }

    /// `SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}` (B9.1, ARMv6). Ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#DSP_DUAL_MULTIPLY}.
    record DspDualMultiply(
            /// Registrador de destino (RdHi na forma longa).
            int dst,
            /// Primeiro operando do produto (Rm, bits 11:8).
            int rm,
            /// Segundo operando do produto (Rn, bits 3:0).
            int rn,
            /// Acumulador Ra (RdLo na forma longa); `15` = sem acumulador (`SMUAD`/`SMUSD`).
            int ra,
            /// `true`: produto1 − produto2 (`SMLSD*`); `false`: produto1 + produto2 (`SMLAD*`).
            boolean subtract,
            /// `true`: forma `X` — troca as metades de `rn` antes de multiplicar.
            boolean exchange,
            /// `true`: acumula em 64 bits `Ra:Rd`, sem flag Q (`SMLALD*`/`SMLSLD*`).
            boolean longForm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.DSP_DUAL_MULTIPLY; }
    }

    /// `SMMLA{R}`/`SMMLS{R}` (B9.1, ARMv6). Ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#DSP_TOP_WORD_MULTIPLY}.
    record DspTopWordMultiply(
            /// Registrador de destino.
            int dst,
            /// Primeiro operando do produto (Rn, bits 3:0).
            int rn,
            /// Segundo operando do produto (Rm, bits 11:8).
            int rm,
            /// Acumulador Ra; `15` = sem acumulador (`SMMUL`/`SMMLS` sem Ra).
            int ra,
            /// `true`: `SMMLS*` (`Ra<<32 − Rn×Rm`); `false`: `SMMLA*` (`Ra<<32 + Rn×Rm`).
            boolean subtract,
            /// `true`: soma `0x8000_0000` antes de truncar (`SMMLAR`/`SMMLSR`, arredonda).
            boolean round,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.DSP_TOP_WORD_MULTIPLY; }
    }

    /// Aritmética paralela ARMv6 em lanes de 8/16 bits (SADD16/UQSUB8/SHASX/...). A operação-base
    /// define as lanes e o cruzamento (ASX/SAX); a variante define sinal, saturação, halving e a
    /// escrita dos flags GE. Formas com PC em qualquer registrador são UNPREDICTABLE no hardware e
    /// não passam pelo decoder.
    record ParallelAlu(
            /// Operação-base (lanes somadas/subtraídas e largura).
            ParallelAluOp op,
            /// Variante de prefixo (S/Q/SH/U/UQ/UH).
            ParallelAluVariant variant,
            /// Registrador de destino.
            int dst,
            /// Primeiro operando (Rn).
            int rn,
            /// Segundo operando (Rm).
            int rm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.PARALLEL_ALU; }
    }

    /// `SEL` (ARMv6): seleciona cada byte do resultado de Rn ou Rm conforme o flag GE
    /// correspondente do CPSR (GE\[i\]=1 → byte de Rn; 0 → byte de Rm).
    record Sel(
            /// Registrador de destino.
            int dst,
            /// Fonte escolhida quando o GE da lane está setado.
            int rn,
            /// Fonte escolhida quando o GE da lane está limpo.
            int rm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SEL; }
    }

    /// Saturação ARMv6 (`SSAT`/`USAT`/`SSAT16`/`USAT16`): satura o operando (possivelmente
    /// shiftado nas formas word) para `saturateBits` bits, com ou sem sinal, por word inteira
    /// ou por halfword. Seta o flag Q sticky quando alguma lane satura.
    record Saturate(
            /// Registrador de destino.
            int dst,
            /// Largura da saturação em bits: `SSAT`/`SSAT16` usam sat_imm+1 (1..32/1..16);
            /// `USAT`/`USAT16` usam sat_imm puro (0..31/0..15).
            int saturateBits,
            /// `true` para faixa sem sinal (`USAT`/`USAT16`: \[0, 2^n−1\]);
            /// `false` para com sinal (`SSAT`/`SSAT16`: \[−2^(n−1), 2^(n−1)−1\]).
            boolean unsignedRange,
            /// `true` para as formas de halfword (`SSAT16`/`USAT16`), que saturam cada
            /// halfword (estendido por sinal) de forma independente.
            boolean halfwords,
            /// Operando de entrada: Rm puro ou Rm shiftado (LSL imm / ASR imm; ASR #32 nas
            /// formas word com imm=0).
            IrOperand operand,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SATURATE; }
    }

    /// `USAD8`/`USADA8` (ARMv6): soma das diferenças absolutas dos quatro bytes (sem sinal)
    /// de Rm e Rs, com acumulador opcional Rn.
    record AbsDiffSum(
            /// Registrador de destino.
            int dst,
            /// Primeiro operando (Rm).
            int rm,
            /// Segundo operando (Rs).
            int rs,
            /// Acumulador (Rn), ou `-1` na forma sem acumulador (`USAD8`).
            int rn,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.ABS_DIFF_SUM; }
    }

    /// Branch exchange, usado para trocar entre ARM e THUMB (e BLX quando `link`).
    record BranchExchange(
            /// Registrador que contém o destino.
            int sourceRegister,
            /// Valor fixo para usar como destino, ou `-1`.
            int sourceValueOverride,
            /// Indica gravação do endereço de retorno no link register (BLX).
            boolean link,
            /// Valor a gravar no link register quando `link` estiver ativo.
            int returnAddress,
            /// Condição necessária para tomar o branch.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.BRANCH_EXCHANGE; }
    }

    /// Primeira metade de `BL` THUMB.
    record ThumbBlPrefix(
            /// Valor assinado alto já deslocado.
            int highOffset,
            /// Endereço da instrução.
            int address,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.THUMB_BL_PREFIX; }
    }

    /// Segunda metade de `BL`/`BLX` THUMB.
    record ThumbBlSuffix(
            /// Valor baixo já deslocado.
            int lowOffset,
            /// Endereço da instrução.
            int address,
            /// `true` para a forma BLX (alinha o destino e troca para ARM).
            boolean exchange,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.THUMB_BL_SUFFIX; }
    }

    /// Operação de push THUMB.
    record Push(
            /// Máscara de registradores r0-r7.
            int registerMask,
            /// Indica inclusão de LR.
            boolean includeLr,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.PUSH; }
    }

    /// Operação de pop THUMB.
    record Pop(
            /// Máscara de registradores r0-r7.
            int registerMask,
            /// Indica inclusão de PC.
            boolean includePc,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.POP; }
    }

    /// `HVC` (B9.8.2, ARM DDI 0406C A8.8.65): entra em Hyp mode via `ArmException#HVC` — ao
    /// contrário de {@link Swi}, não delega a nenhum dispatcher do host, é semântica pura do core
    /// (mesma categoria de {@link Undefined}, sem colaborador externo).
    record Hvc(
            /// `imm16` da instrução, sem uso funcional hoje (fidelidade de trace/debug).
            int immediate,
            /// Condição necessária para executar (encoding real, checado normalmente — ao
            /// contrário de {@link Breakpoint}, que é incondicional).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.HVC; }
    }

    /// `SMC` (B9.8.3, ARM DDI 0406C A8.8.20): entra em Monitor mode via `ArmException#SMC` —
    /// mesma categoria de {@link Hvc} (semântica pura do core, sem colaborador externo). `LR` é
    /// bancado normalmente em Monitor mode (`LR_mon`, B9.8.1), ao contrário de {@link Hvc}/
    /// `ELR_hyp`.
    record Smc(
            /// `imm4` da instrução, sem uso funcional hoje (fidelidade de trace/debug).
            int immediate,
            /// Condição necessária para executar (encoding real, checado normalmente — mesmo
            /// espaço condicional de {@link Hvc}/{@link Swi}).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SMC; }
    }

    /// `ERET` (B9.8.4, A32, ARM DDI 0406C B9.3.3): retorna de exceção — `PC`←`ELR_hyp` (Hyp mode)
    /// ou `LR` do banco ativo (qualquer outro modo privilegiado), `CPSR`←SPSR do modo ativo.
    /// `UNDEFINED` em modo `USER`. Sem operandos de registrador (`Rn` fixo em `1111` no encoding,
    /// não lido) — mesma categoria de {@link Hvc}/{@link Smc} (semântica pura do core), mas SEM
    /// `ArmException` própria: é uma instrução de RETORNO pura, mesmo tratamento de
    /// {@link ReturnFromException}/`RFE`, não de {@link Hvc}/{@link Smc}.
    record Eret(
            /// Condição necessária para executar (encoding real, checado normalmente — mesmo
            /// espaço condicional de {@link Hvc}/{@link Smc}/{@link Swi}).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.ERET; }
    }

    /// `MRS` (forma bancada, B9.8.5, ARM DDI 0406C A8.8.64): lê um registrador geral ou `SPSR` de
    /// outro modo (não o ativo) — `sysm`/`r` já resolvidos em `(modo, registrador)` em tempo de
    /// DECODE (`BankedRegisterSysm`), então esta op é semântica pura do core, sem re-decodificar
    /// nada. `UNDEFINED` em modo `USER` (checado em tempo de EXECUÇÃO, mesma convenção de
    /// {@link Hvc}/{@link Smc}/{@link Eret}). Sem checagem de Secure/Monitor state (simplificação
    /// documentada em `b9.8-plano-hyp-monitor-32bit.md`).
    record MrsBank(
            /// Registrador geral de destino (`Rd`).
            int armRegister,
            /// Modo do registrador bancado alvo (não necessariamente o modo ativo).
            CpuMode targetMode,
            /// Índice do registrador bancado (8-14), significativo só quando {@link #elrHyp()} e
            /// {@link #spsr()} são ambos `false`.
            int bankedRegister,
            /// `true` quando o alvo é `ELR_hyp` — registrador à parte, fora de R0-R15 (Hyp mode
            /// não banca `LR`, ver `ArmCore#elrHyp`).
            boolean elrHyp,
            /// `true` quando o alvo é o `SPSR` do modo (em vez de um registrador geral).
            boolean spsr,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MRS_BANK; }
    }

    /// `MSR` (forma bancada, B9.8.5): escreve um registrador geral num registrador geral ou `SPSR`
    /// de outro modo — mesma convenção de {@link MrsBank}.
    record MsrBank(
            /// Registrador geral de origem (`Rn`).
            int armRegister,
            /// Modo do registrador bancado alvo.
            CpuMode targetMode,
            /// Índice do registrador bancado (8-14), significativo só quando {@link #elrHyp()} e
            /// {@link #spsr()} são ambos `false`.
            int bankedRegister,
            /// `true` quando o alvo é `ELR_hyp`.
            boolean elrHyp,
            /// `true` quando o alvo é o `SPSR` do modo.
            boolean spsr,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MSR_BANK; }
    }

    /// Operação SWI delegada ao dispatcher do host.
    record Swi(
            /// Imediato da instrução SWI.
            int immediate,
            /// Condição necessária para disparar a SWI.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SWI; }
    }

    /// `BKPT` (B7.5, ARMv5T+): imediato de 8 (Thumb) ou 16 (ARM) bits delegado ao
    /// {@link dev.vitorsilverio.armjitter.core.BkptDispatcher} do host — mesmo padrão de
    /// {@link Swi}, mas sempre incondicional (o encoding não tem campo de condição em nenhum
    /// dos dois modos; {@link #condition()} retorna {@link Condition#AL} pelo default da
    /// interface).
    record Breakpoint(
            /// Imediato da instrução BKPT.
            int immediate) implements IrOp {
        @Override public int kind() { return Kind.BREAKPOINT; }
    }

    /// Transferência de registrador de coprocessador (`MCR`/`MRC`), delegada ao barramento de coprocessador do core.
    record Coprocessor(
            /// `true` para `MRC` (coprocessador -> registrador ARM), `false` para `MCR`.
            boolean load,
            /// Número do coprocessador (15 para CP15).
            int coprocessor,
            /// Opcode primário (bits 23-21 da instrução).
            int opcode1,
            /// Registrador primário de coprocessador (CRn).
            int crn,
            /// Registrador secundário de coprocessador (CRm).
            int crm,
            /// Opcode secundário (bits 7-5 da instrução).
            int opcode2,
            /// Registrador ARM (Rd) lido para `MCR` ou escrito para `MRC`.
            int register,
            /// PC sequencial usado como endereço de retorno se a transferência for indefinida.
            int sequentialPc,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.COPROCESSOR; }
    }

    /// Transferência DUPLA de registrador de coprocessador (`MCRR`/`MRRC`, F3), delegada ao
    /// barramento de coprocessador do core. Diferente de {@link Coprocessor} (`MCR`/`MRC`): não há
    /// `CRn` nem `opcode2` — dois registradores ARM são transferidos de uma vez e `opcode1` tem 4
    /// bits (não 3).
    record CoprocessorDouble(
            /// `true` para `MRRC` (coprocessador -> registradores ARM), `false` para `MCRR`.
            boolean load,
            /// Número do coprocessador (15 para CP15).
            int coprocessor,
            /// Opcode (4 bits, distinto do `opcode1` de 3 bits de {@link Coprocessor}).
            int opcode1,
            /// Registrador de coprocessador (CRm).
            int crm,
            /// Primeiro registrador ARM (Rt) — metade baixa da faixa/valor transferido.
            int rt,
            /// Segundo registrador ARM (Rt2) — metade alta.
            int rt2,
            /// PC sequencial usado como endereço de retorno se a transferência for indefinida.
            int sequentialPc,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.COPROCESSOR_DOUBLE; }
    }

    /// Instrução não implementada/indefinida que deve entrar no vetor `0x04`.
    record Undefined(
            /// PC sequencial usado como endereço de retorno da exceção.
            int sequentialPc,
            /// Condição necessária para disparar a exceção.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.UNDEFINED; }
    }

    /// Contagem de ciclos agregada ao bloco.
    record Cycle(
            /// Quantidade de ciclos somada ao bloco.
            int count) implements IrOp {
        @Override public int kind() { return Kind.CYCLE; }
    }

    /// Custo de fetch da instrução original na memória do dispositivo.
    record Fetch(
            /// Endereço da instrução buscada.
            int address,
            /// Tamanho da instrução em bytes.
            int sizeBytes) implements IrOp {
        @Override public int kind() { return Kind.FETCH; }
    }

    /// `CPS`/`CPSIE`/`CPSID` (ARMv6): altera os bits A/I/F e/ou o modo do CPSR pelo mesmo
    /// caminho de troca de banco usado por `MSR`/entrada de exceção ({@code ArmCore#setCpsr}).
    /// UNPREDICTABLE em modo User: tratado como NOP (ver `IrSystemExecutor`).
    record ChangeProcessorState(
            /// `true` quando `mode` deve substituir os 5 bits de modo do CPSR.
            boolean changeMode,
            /// Campo de modo cru (5 bits), válido só quando `changeMode`. Convertido para
            /// `CpuMode` em tempo de EXECUÇÃO (não no lift) — mantém o mesmo risco de
            /// `IllegalArgumentException` para bits de modo inválidos que `MSR` já tem hoje.
            int mode,
            /// `true` quando A/I/F selecionados devem ser alterados (`imod` = IE ou ID).
            boolean changeFlags,
            /// `true` para IE (habilita, limpa os bits selecionados); `false` para ID (desabilita, seta).
            boolean enable,
            /// Altera o bit A (abort imprecisa).
            boolean changeA,
            /// Altera o bit I (IRQ).
            boolean changeI,
            /// Altera o bit F (FIQ).
            boolean changeF,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.CHANGE_PROCESSOR_STATE; }
    }

    /// `SETEND` (ARMv6): seta o bit E (endianness de dados) do CPSR.
    record SetEndianness(
            /// `true` para big-endian, `false` para little-endian.
            boolean bigEndian,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SET_ENDIANNESS; }
    }

    /// `SRS` (ARMv6): empilha LR e SPSR ATUAIS na pilha (`R13`) de um modo alvo.
    record StoreReturnState(
            /// Campo de modo alvo cru (5 bits); convertido para `CpuMode` em tempo de execução.
            int targetMode,
            /// Modo de endereçamento (IA/IB/DA/DB).
            BlockTransferMode addressingMode,
            /// Indica writeback no `R13` do modo alvo.
            boolean writeback,
            /// PC sequencial usado como retorno se o modo atual for User/System (UNPREDICTABLE → UNDEFINED).
            int sequentialPc,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.STORE_RETURN_STATE; }
    }

    /// `RFE` (ARMv6): carrega PC e CPSR da pilha apontada por `base` (Rn).
    record ReturnFromException(
            /// Registrador base (Rn).
            int base,
            /// Modo de endereçamento (IA/IB/DA/DB).
            BlockTransferMode addressingMode,
            /// Indica writeback em `base`.
            boolean writeback,
            /// PC sequencial usado como retorno se o modo atual for User/System (UNPREDICTABLE → UNDEFINED).
            int sequentialPc,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.RETURN_FROM_EXCEPTION; }
    }

    /// `WFI` (ARMv6K hint): coloca o core em HALT até uma interrupção.
    record WaitForInterrupt(
            /// Condição necessária para executar (disfarçada de MSR — pode ser condicional).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.WAIT_FOR_INTERRUPT; }
    }

    /// `MOVT` (Thumb-2, B2.2): escreve um imediato de 16 bits na metade ALTA de `dst`,
    /// preservando a metade baixa existente. Nunca escreve flags; sem operando shiftado.
    record MoveTop(
            /// Registrador de destino.
            int dst,
            /// Imediato de 16 bits a escrever em bits[31:16].
            int immediate16,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MOVE_TOP; }
    }

    /// `DMB`/`DSB`/`ISB` (ARMv7, Thumb-2 — B2.5): barreira de memória.
    ///
    /// Premissa explícita (ver `ArmFeature#MEMORY_BARRIERS`): esta implementação executa um único
    /// core sem reordenação especulativa de memória e sem múltiplos cores observando o mesmo
    /// espaço de endereço concorrentemente — logo, toda barreira já está satisfeita antes mesmo de
    /// ser emitida, e a única ação correta é NOP observável (nenhum registrador, flag ou memória
    /// muda). Isso deixa de valer no dia em que a trilha B6/AArch64 ou um modelo multi-core
    /// entrarem em cena; nesse ponto esta simplificação precisa ser revisitada.
    record MemoryBarrier(
            /// Condição necessária para executar (espaço incondicional em Thumb-2 → sempre AL).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MEMORY_BARRIER; }
    }

    /// Grava o ITSTATE\[7:0\] do CPSR (Thumb-2 IT block, B2.4 — ver
    /// {@link dev.vitorsilverio.armjitter.core.ItState}). Duas origens, ambas emitidas pelo
    /// lifter (não pelo decoder isoladamente — ver `StandardIrBlockLifter`):
    /// <ol>
    ///   <li>A própria instrução `IT`: grava o ITSTATE de entrada (`firstcond:mask`); condição =
    ///       a da instrução `IT` em si (normalmente AL; só difere quando o `IT` está — de forma
    ///       CONSTRAINED UNPREDICTABLE, ver Armadilhas de B2.4 — aninhado dentro de outro IT
    ///       block, caso em que herda a condição do bloco externo).</li>
    ///   <li>O "avanço" (`ItState#advance`) emitido pelo lifter logo após CADA instrução coberta
    ///       por um IT block ativo: sempre com condição {@link Condition#AL} — o avanço do
    ///       ITSTATE é incondicional no hardware real, independente de a instrução coberta ter
    ///       sido de fato executada (guard verdadeiro) ou pulada (guard falso).</li>
    /// </ol>
    record SetItState(
            /// Novo valor ITSTATE\[7:0\] a gravar no CPSR (`0` = fora de IT block).
            int itState,
            /// Condição necessária para executar esta gravação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SET_IT_STATE; }
    }

    /// `TBB`/`TBH` (Thumb-2, ARMv6T2+, B2.4): lê um byte (`TBB`) ou halfword (`TBH`) sem sinal de
    /// uma tabela em memória indexada por `rm` a partir de `rn`, e desvia para
    /// `PC_da_instrução + 4 + 2 * valor_lido`. `IrOp` dedicado em vez de compor `Load`+`Branch`
    /// genéricos — ver a decisão D3 registrada em `b2.4-thumb2-branches-it.md`: o valor lido da
    /// tabela nunca é observável em nenhum `Rd` (não há registrador-escrátel arquitetural para
    /// modelar), o destino é `PC_base + 2*tabela` (uma composição que nenhum `IrOp` de branch
    /// existente expressa) e a instrução nunca troca de instruction-set (sempre permanece Thumb).
    record TableBranch(
            /// Registrador base da tabela (Rn); pode ser PC.
            int rn,
            /// Valor fixo para usar como base quando `rn` é PC, ou `-1`.
            int rnValueOverride,
            /// Registrador de índice (Rm).
            int rm,
            /// Valor fixo para usar como índice quando `rm` é PC, ou `-1` (PC como índice é
            /// incomum mas não rejeitado pelo decoder — mesma convenção de override do resto do
            /// IR).
            int rmValueOverride,
            /// `PC` LIDO da própria instrução `TBB`/`TBH` (endereço da instrução + 4, resolvido em
            /// tempo de decode) — a base do DESVIO (`target = pcBase + 2*tabela`). Deliberadamente
            /// SEPARADO de `rn`/`rnValueOverride` (a base da TABELA, usada só para o endereço de
            /// leitura): quando `Rn≠PC` (tabela em endereço computado por `ADR` antes), a leitura e
            /// o desvio usam bases DIFERENTES — reaproveitar `rn` para as duas coisas seria
            /// correto só no caso comum `TBB [PC,Rm]`, mas incorreto em geral.
            int pcBase,
            /// `true` para `TBH` (halfword, índice em unidades de 2 bytes); `false` para `TBB`
            /// (byte, índice em unidades de 1 byte).
            boolean halfword,
            /// Condição necessária para executar o desvio.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.TABLE_BRANCH; }
    }

    /// `CBZ`/`CBNZ` (Thumb-1, ARMv6T2+, B2.4): desvia para `target` (já resolvido pelo decoder)
    /// quando `rn` (sempre R0-R7) é zero (`CBZ`) ou não-zero (`CBNZ`) — NUNCA afeta NZCV, ao
    /// contrário de um `CMP`+`Branch` equivalente, e por isso não reaproveita `IrOp.Alu`. `rn`
    /// nunca é PC/SP (restrito a 3 bits no encoding), então não precisa de value override.
    record CompareBranchZero(
            /// Registrador testado (R0-R7).
            int rn,
            /// Endereço absoluto de destino quando o branch é tomado.
            int target,
            /// `true` para `CBNZ` (desvia quando `rn≠0`); `false` para `CBZ` (desvia quando
            /// `rn==0`).
            boolean branchIfNonZero,
            /// Condição necessária para executar (normalmente AL; ver Armadilhas de B2.4 — CBZ/
            /// CBNZ dentro de um IT block é UNPREDICTABLE no ARM ARM, mas o lifter aplica o mesmo
            /// mecanismo uniforme de override de condição usado para toda instrução dentro de um
            /// IT block, então este campo pode carregar uma condição não-AL nesse caso raro).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.COMPARE_BRANCH_ZERO; }
    }

    /// `SBFX`/`UBFX` (ARM/Thumb-2, ARMv6T2+, B3.1): extrai `width` bits de `src` a partir do bit
    /// `lsb` e estende para 32 bits (com ou sem sinal). Nunca toca flags.
    record BitFieldExtract(
            /// Registrador de destino.
            int dst,
            /// Registrador de origem (Rn).
            int src,
            /// Posição do bit menos significativo do campo (0..31).
            int lsb,
            /// Largura do campo em bits (1..32, com `lsb + width <= 32`).
            int width,
            /// `true` para `SBFX` (extensão com sinal); `false` para `UBFX` (com zero).
            boolean signedExtract,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.BIT_FIELD_EXTRACT; }
    }

    /// `BFI`/`BFC` (ARM/Thumb-2, ARMv6T2+, B3.1): substitui `width` bits de `dst` a partir do bit
    /// `lsb` pelos bits baixos de `src` (`BFI`) ou por zeros (`BFC`, quando {@code src == -1}),
    /// preservando os demais bits de `dst`. Nunca toca flags.
    record BitFieldInsert(
            /// Registrador de destino (também lido, para preservar os bits fora do campo).
            int dst,
            /// Registrador de origem (Rn), ou `-1` para `BFC` (insere zeros).
            int src,
            /// Posição do bit menos significativo do campo (0..31).
            int lsb,
            /// Largura do campo em bits (1..32, com `lsb + width <= 32`).
            int width,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.BIT_FIELD_INSERT; }
    }

    /// `RBIT` (ARM/Thumb-2, ARMv6T2+, B3.1): inverte a ordem dos 32 bits de `src`. Nunca toca flags.
    record BitReverse(
            /// Registrador de destino.
            int dst,
            /// Registrador de origem (Rm).
            int src,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.BIT_REVERSE; }
    }

    /// `SDIV`/`UDIV` (ARM/Thumb-2, ARMv7, B3.1): divisão inteira truncada para zero. Divisão por
    /// zero resulta em `0` (sem exceção); `Integer.MIN_VALUE / -1` resulta em `Integer.MIN_VALUE`
    /// (overflow silencioso, igual ao hardware — ARM DDI 0406C A8.8.165). Nunca toca flags.
    record Divide(
            /// Registrador de destino.
            int dst,
            /// Registrador dividendo (Rn).
            int dividend,
            /// Registrador divisor (Rm).
            int divisor,
            /// `true` para `SDIV` (com sinal); `false` para `UDIV` (sem sinal).
            boolean signedDivide,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.DIVIDE; }
    }

    // ── VFP (B3.4): decode fica em B3.5, ASM nativo em B3.6 — aqui só IR + interpretador. ──

    /// Operação aritmética/unária VFP (`op` seleciona o comportamento; ver {@link VfpAlu}).
    enum VfpOperation {
        /// `VADD`: `vd = vn + vm`.
        ADD,
        /// `VSUB`: `vd = vn - vm`.
        SUB,
        /// `VMUL`: `vd = vn * vm`.
        MUL,
        /// `VDIV`: `vd = vn / vm`.
        DIV,
        /// `VMLA`: `vd = vd + (vn * vm)`, NÃO fundido (duas operações arredondadas separadamente).
        MLA,
        /// `VMLS`: `vd = vd - (vn * vm)`, NÃO fundido.
        MLS,
        /// `VNMLA`: `vd = -vd - (vn * vm)`, NÃO fundido (ARM ARM A8.8.337: "-fd + -(fn * fm)").
        NMLA,
        /// `VNMLS`: `vd = -vd + (vn * vm)`, NÃO fundido (ARM ARM A8.8.337: "-fd + (fn * fm)").
        /// **Não** é `VMLS` com o sinal trocado: quem é negado é o ACUMULADOR, não o produto.
        NMLS,
        /// `VNMUL`: `vd = -(vn * vm)`.
        NMUL,
        /// `VNEG` (unária, usa só `vm`): inverte o bit de sinal.
        NEG,
        /// `VABS` (unária, usa só `vm`): zera o bit de sinal.
        ABS,
        /// `VSQRT` (unária, usa só `vm`): raiz quadrada corretamente arredondada.
        SQRT,
        /// `VMOV` registrador-a-registrador (unária, usa só `vm`): cópia bit a bit.
        COPY,
        /// `VFMA` (B9.6, VFPv4): `vd = vd + (vn * vm)`, FUNDIDO — um único passo de arredondamento
        /// para o produto-e-soma inteiro (`Math.fma`), ao contrário de {@link #MLA}. Mesma
        /// convenção de sinal de {@link #MLA}, só muda o arredondamento.
        FMA,
        /// `VFMS` (B9.6, VFPv4): `vd = vd - (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #MLS} (produto negado, não o acumulador).
        FMS,
        /// `VFNMA` (B9.6, VFPv4): `vd = -vd - (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #NMLA} (confirmado contra `MAKE_ONE_VFM_TRANS_FN`/`do_vfm_sp` reais do QEMU:
        /// `neg_n=true, neg_d=true` → `fma(-vd, -vn, vm)` = `-(vd + vn·vm)`).
        FNMA,
        /// `VFNMS` (B9.6, VFPv4): `vd = -vd + (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #NMLS} (`neg_n=false, neg_d=true` → `fma(-vd, vn, vm)` = `-vd + vn·vm`).
        FNMS
    }

    /// Operação aritmética/unária VFP (`VADD`/`VSUB`/`VMUL`/`VDIV`/`VMLA`/`VMLS`/`VNMUL`/`VNEG`/
    /// `VABS`/`VSQRT`/`VMOV` registrador, mais `VFMA`/`VFMS`/`VFNMA`/`VFNMS`, B9.6). `VMLA`/`VMLS`/
    /// `VNMUL`/`VNMLA`/`VNMLS` NUNCA usam `Math.fma` — o VFPv2 real não funde a multiplicação com a
    /// soma/subtração (ver Armadilhas de B3.4). Só `FMA`/`FMS`/`FNMA`/`FNMS` (VFPv4, B9.6) usam
    /// `Math.fma` de verdade — é literalmente a diferença arquitetural entre as duas famílias
    /// (fundida vs não-fundida), não uma escolha de implementação. As formas unárias (`NEG`/`ABS`/
    /// `SQRT`/`COPY`) usam somente `vm`; `MLA`/`MLS`/`FMA`/`FMS` também leem o `vd` atual como
    /// acumulador.
    record VfpAlu(
            /// Operação a executar.
            VfpOperation op,
            /// `true` para precisão dupla (registradores `D`), `false` para simples (`S`).
            boolean doublePrecision,
            /// Registrador de destino (também acumulador de entrada para `MLA`/`MLS`).
            int vd,
            /// Primeiro registrador de origem (ignorado pelas formas unárias).
            int vn,
            /// Segundo registrador de origem (único operando das formas unárias).
            int vm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_ALU; }
    }

    /// `VMOV.F32`/`VMOV.F64 Vd, #imm` (VFPv3-d16): grava um imediato de ponto flutuante já
    /// expandido pelo decoder/lifter (decode fica em B3.5).
    record VfpMoveImmediate(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Bits crus do imediato: 32 bits baixos usados quando `!doublePrecision`, os 64 bits
            /// completos quando `doublePrecision`.
            long immediateBits,
            /// Condição necessária para executar a operação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_MOVE_IMMEDIATE; }
    }

    /// `VCMP`/`VCMPE` (com ou sem `VCMPE`/`VCMP` `#0.0`): compara `vd` com `vm` (ou com zero) e
    /// grava APENAS `FPSCR.NZCV` (ARM DDI 0406C A2.9.1) — nunca o CPSR; só `VMRS APSR_nzcv` (ver
    /// {@link VfpSystemTransfer}) move o resultado para lá. Tabela exata: eq→N=0,Z=1,C=1,V=0;
    /// lt→N=1,Z=0,C=0,V=0; gt→N=0,Z=0,C=1,V=0; unordered (algum operando é NaN)→N=0,Z=0,C=1,V=1.
    record VfpCompare(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` para as formas `VCMP(E) Vd, #0.0` (compara com zero em vez de `vm`).
            boolean compareWithZero,
            /// `true` para `VCMPE` (bit E: sinaliza operação inválida também para NaN silencioso,
            /// não só sinalizador — sem efeito observável adicional neste core, que não modela
            /// traps de exceção de ponto flutuante; mantido para fidelidade ao encoding).
            boolean signalOnQuietNaN,
            /// Registrador comparado.
            int vd,
            /// Segundo operando da comparação (ignorado quando `compareWithZero`).
            int vm,
            /// Condição necessária para executar a comparação.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_COMPARE; }
    }

    /// Direção/tipos de uma conversão `VCVT` (forma default, arredondamento round-toward-zero para
    /// inteiro — ver {@link VfpConvert}).
    enum VfpConversion {
        /// `VCVT.F64.F32`: simples → dupla (exata).
        F32_TO_F64,
        /// `VCVT.F32.F64`: dupla → simples (arredondada).
        F64_TO_F32,
        /// `VCVT.F32.S32`: inteiro com sinal → simples.
        S32_TO_F32,
        /// `VCVT.F64.S32`: inteiro com sinal → dupla (exata).
        S32_TO_F64,
        /// `VCVT.F32.U32`: inteiro sem sinal → simples.
        U32_TO_F32,
        /// `VCVT.F64.U32`: inteiro sem sinal → dupla (exata).
        U32_TO_F64,
        /// `VCVT.S32.F32`: simples → inteiro com sinal (round-toward-zero, satura, NaN→0).
        F32_TO_S32,
        /// `VCVT.S32.F64`: dupla → inteiro com sinal (round-toward-zero, satura, NaN→0).
        F64_TO_S32,
        /// `VCVT.U32.F32`: simples → inteiro sem sinal (round-toward-zero, satura em `[0, 2³²-1]`, NaN→0).
        F32_TO_U32,
        /// `VCVT.U32.F64`: dupla → inteiro sem sinal (round-toward-zero, satura em `[0, 2³²-1]`, NaN→0).
        F64_TO_U32
    }

    /// `VCVT` na forma default (não `VCVTR`, que usaria `FPSCR.RMode` — fora de escopo, RMode≠RN
    /// já é rejeitado por {@link dev.vitorsilverio.armjitter.core.FpscrRegister}). Cada membro de
    /// {@link VfpConversion} já fixa qual banco (`S` ou `D`) origem/destino usam — não há campo
    /// `doublePrecision` separado porque a direção da conversão determina isso sozinha.
    record VfpConvert(
            /// Conversão a executar.
            VfpConversion conversion,
            /// Registrador de destino (banco determinado por `conversion`).
            int vd,
            /// Registrador de origem (banco determinado por `conversion`).
            int vm,
            /// Condição necessária para executar a conversão.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_CONVERT; }
    }

    /// `VLDR`: carrega `Vd` de `[base + offsetBytes]` (sempre `P=1,W=0` — VFP não tem writeback
    /// em load/store simples, ao contrário de `LDR`/`LDRD`).
    record VfpLoad(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC` (`Vd, [pc, #imm]`,
            /// o idioma padrão de literal pool do `gcc` para constantes `double`/`float`), ou `-1`
            /// — mesmo mecanismo de {@link Load#baseValueOverride}. Sem ele, `base` seria lido AO
            /// VIVO de `core.register(15)` em tempo de execução, que NÃO tem o viés `+8` do `PC`
            /// arquitetural do ARM: o bloco só grava `registers[PC]` no fim ({@link
            /// dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor#execute}), então
            /// durante a execução deste op `PC` ainda vale o endereço da instrução atual, não
            /// `+8` — sem o override, `VLDR`/`VSTR Vx, [pc, #imm]` lia do endereço errado (e
            /// interpretado/JIT convergiam no MESMO endereço errado, G1 preservado mas ambos
            /// incorretos).
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4), já resolvido pelo decoder/lifter.
            int offsetBytes,
            /// Condição necessária para executar o load.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_LOAD; }
    }

    /// `VSTR`: grava `Vd` em `[base + offsetBytes]` (ver {@link VfpLoad}).
    record VfpStore(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de origem.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1` — ver
            /// {@link VfpLoad#baseValueOverride}.
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4), já resolvido pelo decoder/lifter.
            int offsetBytes,
            /// Condição necessária para executar o store.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_STORE; }
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: transfere `count` registradores consecutivos
    /// (`firstRegister`..`firstRegister+count-1`) entre memória e o banco VFP. Só as formas `IA`
    /// e `DB` existem no VFP (ao contrário do `LDM`/`STM` ARM genérico, que também tem `IB`/`DA`);
    /// `VPUSH`/`VPOP` são aliases de `DB`/`IA` com `writeback=true` e `base=SP` — sem `IrOp`
    /// dedicado, testados via este record diretamente.
    record VfpMultipleTransfer(
            /// `true` para `VLDM` (load), `false` para `VSTM` (store).
            boolean load,
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1` — ver
            /// {@link VfpLoad#baseValueOverride} (mesmo idioma de literal pool, aqui para
            /// `VLDM`/`VSTM Rn=pc`, ainda que raro comparado a `VLDR`/`VSTR`).
            int baseValueOverride,
            /// Primeiro registrador da lista.
            int firstRegister,
            /// Quantidade de registradores consecutivos.
            int count,
            /// Indica writeback no registrador base (sempre `true` para `VPUSH`/`VPOP`).
            boolean writeback,
            /// `true` para `DB` (decrementa antes — `VPUSH`); `false` para `IA` (`VPOP`/`VLDM`/`VSTM` padrão).
            boolean decrementBefore,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_MULTIPLE_TRANSFER; }
    }

    /// `VMOV Rt, Sn` / `VMOV Sn, Rt` (`FMRS`/`FMSR`): transfere um único registrador `S` de/para
    /// um registrador ARM de propósito geral, bits crus (sem conversão de tipo).
    record VfpCoreTransfer(
            /// `true` para `Sn` → `Rt` (`FMRS`); `false` para `Rt` → `Sn` (`FMSR`).
            boolean toArmRegister,
            /// Registrador ARM de propósito geral envolvido.
            int armRegister,
            /// Registrador `S` envolvido.
            int vn,
            /// `VMOV_half` (B22.2, `ArmFeature.HALF_PRECISION_FP`): transferência de **16 bits**.
            /// `toArmRegister` → `Rt = ZeroExtend(Sn[15:0], 32)`; senão → `Sn[15:0] = Rt[15:0]`
            /// e `Sn[31:16]` fica **inalterado** (ao contrário da forma de 32 bits, que escreve o
            /// `S` inteiro). `false` para `VMOV_single`/`VMOV_to_gp`/`VMOV_from_gp` (32 bits).
            boolean halfWidth,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_CORE_TRANSFER; }
    }

    /// `VMOV Rt, Rt2, Dm` / `VMOV Dm, Rt, Rt2` (`FMRRD`/`FMDRR`): transfere um registrador `D`
    /// inteiro de/para um par de registradores ARM (`armLow` = metade baixa, `armHigh` = metade
    /// alta — mesmo layout little-endian de {@link VfpLoad}/{@link VfpStore} na memória).
    record VfpCorePairTransfer(
            /// `true` para `Dm` → `(armLow,armHigh)` (`FMRRD`); `false` para o sentido inverso (`FMDRR`).
            boolean toArmRegisters,
            /// Registrador ARM que recebe/fornece a metade BAIXA.
            int armLow,
            /// Registrador ARM que recebe/fornece a metade ALTA.
            int armHigh,
            /// Registrador `D` envolvido.
            int vm,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_CORE_PAIR_TRANSFER; }
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`): transfere o FPSCR completo de/para um registrador ARM.
    /// Caso especial obrigatório (decisão nº 4 do épico B3): `VMRS APSR_nzcv, FPSCR`
    /// (`read=true, armRegister=15`) NÃO escreve `R15` — copia só `FPSCR.NZCV` para `CPSR.NZCV`,
    /// preservando Q/GE/IT/modo/todo o resto do CPSR.
    record VfpSystemTransfer(
            /// `true` para `VMRS` (FPSCR → destino); `false` para `VMSR` (origem → FPSCR).
            boolean read,
            /// Registrador ARM envolvido; `15` em `read=true` é o caso especial `APSR_nzcv`.
            int armRegister,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_SYSTEM_TRANSFER; }
    }

    // -- VFP (B9.5): VMOV_64_sp (par de S consecutivos) e VCVT_fix (fixed-point). --

    /// `VMOV_64_sp` (ARM DDI 0406C A8.8.346, forma depreciada mas VFPv2 genuina): transfere `Sm`
    /// (metade baixa) e `Sm+1` (metade alta, calculado em tempo de execucao a partir de `vm`) de/
    /// para dois registradores ARM. NAO reaproveita {@link VfpCorePairTransfer} porque `Sm`/`Sm+1`
    /// so coincidem com um registrador `D` inteiro quando `m` e par -- para `m` impar as duas
    /// metades pertencem a `D` diferentes, e o acesso precisa ser via `S` diretamente.
    record VfpCorePairTransferSingle(
            /// `true` para `(Sm,Sm+1)` -> `(armLow,armHigh)`; `false` para o sentido inverso.
            boolean toArmRegisters,
            /// Registrador ARM que recebe/fornece a metade BAIXA (`Sm`).
            int armLow,
            /// Registrador ARM que recebe/fornece a metade ALTA (`Sm+1`).
            int armHigh,
            /// Primeiro registrador `S` do par consecutivo (o segundo e `vm+1`).
            int vm,
            /// Condicao necessaria para executar a transferencia.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_CORE_PAIR_TRANSFER_SINGLE; }
    }

    /// `VCVT_fix_{sp,dp}` (ARM DDI 0406C A8.8.397, VFPv3): converte, no MESMO registrador `vd`
    /// (fonte e destino coincidem), entre ponto flutuante e um inteiro fixo empacotado nos bits
    /// baixos do registrador. Fixo -> float sempre arredonda ao mais proximo (par); float -> fixo
    /// sempre trunca para zero e satura na largura do inteiro (16 ou 32 bits, com/sem sinal
    /// conforme `unsignedFixedPoint`) -- QEMU `vfp_helper.c` `VFP_CONV_FIX*`, conferido antes de
    /// implementar (aritmetica de conversao fixo<->float NUNCA e so um deslocamento de bits).
    record VfpConvertFixed(
            /// `true` para precisao dupla do lado float (`vd` e um registrador `D`), `false` simples (`S`).
            boolean doublePrecision,
            /// `true`: float -> fixo (arredonda p/ zero, satura). `false`: fixo -> float (arred. p/ perto).
            boolean toFixedPoint,
            /// `true`: inteiro fixo SEM sinal. `false`: COM sinal.
            boolean unsignedFixedPoint,
            /// `true`: inteiro fixo de 32 bits. `false`: 16 bits.
            boolean fixedPointIs32Bit,
            /// Quantidade de bits fracionarios, ja resolvida (`fixedPointIs32Bit ? 32-imm : 16-imm`).
            int fractionBits,
            /// Registrador `vd`: fonte E destino (mesma posicao nos dois sentidos).
            int vd,
            /// Condicao necessaria para executar a conversao.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_CONVERT_FIXED; }
    }

    /// `MRS`/`MSR` na forma SYSm do perfil M (B7.4): transfere um registrador especial Cortex-M
    /// (número `sysm`) de/para um registrador ARM de propósito geral. Distinta de
    /// {@link PsrTransfer} (perfil A, que carrega semântica de CPSR/SPSR + máscara de campos `_fsxc`
    /// que não se aplica aqui): o alvo é um dos registradores especiais do perfil M
    /// (APSR/IPSR/XPSR/MSP/PSP/PRIMASK/BASEPRI/FAULTMASK/CONTROL...), resolvido pelo
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}. Só produzida pelo decoder
    /// quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#M_PROFILE} está ativo, portanto o
    /// executor pode assumir que o `ExceptionModel` instalado é um `MProfileExceptionModel`.
    record MProfileSystemRegister(
            /// `true` para `MRS` (registrador especial → registrador ARM); `false` para `MSR`
            /// (registrador ARM → registrador especial).
            boolean read,
            /// Registrador ARM de propósito geral: destino do `MRS`, fonte do `MSR`.
            int armRegister,
            /// Número do registrador especial (campo `SYSm` do encoding).
            int sysm,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.M_PROFILE_SYSTEM_REGISTER; }
    }

    /// `NOCP`/`NOCP_8_1` (perfil M, B15.2, `target/isa-decode/m-nocp.decode`): tentativa de acessar
    /// um coprocessador ausente/desabilitado — seta `UFSR.NOCP` (`CFSR` em `0xE000ED28`) e entra em
    /// {@link dev.vitorsilverio.armjitter.core.MProfileException#USAGE_FAULT} via
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}. Só produzida pelo decoder
    /// quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#M_PROFILE} está ativo, portanto o
    /// executor pode assumir que o `ExceptionModel` instalado é um `MProfileExceptionModel` — mesmo
    /// contrato de {@link MProfileSystemRegister}.
    record Nocp(
            /// Coprocessador-alvo (`cp`, bits\[11:8\] do encoding; fixo em `10` para `NOCP_8_1`) —
            /// sem uso funcional hoje, carregado só por fidelidade de trace/debug (mesmo padrão de
            /// {@link Hvc}/{@link Smc}).
            int coprocessor,
            /// Condição necessária para executar a exceção.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.NOCP; }
    }

    /// `VLDR_sysreg`/`VSTR_sysreg` (perfil M, B15.3, `target/isa-decode/m-nocp.decode`): move o
    /// valor bruto de `ArmCore.fpscr()` de/para `[base {+,-}offsetBytes]`, com pré/pós-indexação e
    /// writeback opcionais — mesma semântica de endereço de {@link Load}/{@link Store} genérico
    /// ({@code postIndexed ? base : base + offsetBytes}, seguido de {@code base + offsetBytes}
    /// quando {@link #writeback}), só que o destino/origem não é um GPR. Único `reg` implementado
    /// nesta task é `FPSCR` (ver `Thumb2VfpSystemAccessDecoder`); os demais valores reais da
    /// arquitetura (`FPSCR_NZCVQC`/`VPR`,`P0`/`FPCXT_NS`/`FPCXT_S`) ainda não são decodificados.
    record VfpSysregMemoryTransfer(
            /// `true` para `VLDR_sysreg` (memória -> `FPSCR`); `false` para `VSTR_sysreg`.
            boolean load,
            /// Registrador base do endereço (`Rn`).
            int base,
            /// Offset em bytes (±`imm7`×4), já resolvido pelo decoder.
            int offsetBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index (`P=0,W=1` forçado no encoding real).
            boolean postIndexed,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VFP_SYSREG_MEMORY_TRANSFER; }
    }

    /// `VLLDM`/`VLSTM` (perfil M, B15.5, `target/isa-decode/m-nocp.decode`): salva/restaura o banco
    /// FP completo via lazy state preservation em hardware real — mas "lazy" é uma otimização de
    /// HARDWARE (economiza ciclos quando o handler de exceção nunca toca FP), sem semântica
    /// observável que este emulador precise reproduzir (ver Javadoc de
    /// {@code Thumb2VlldmVlstmVscclrmDecoder}). Sem FPU real no perfil M, o `.decode` real prioriza
    /// estas 2 formas ANTES do `NOCP` genérico e as trata como `UNDEFINED` explícito ("these are the
    /// two UNDEFs that must take precedence over NOCP") — via
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#setUsageFaultUndefinstr()}
    /// (bit `UNDEFINSTR` do `UFSR`, diferente do bit `NOCP` que {@link Nocp} seta) seguido de
    /// `USAGE_FAULT`. Só produzida sob {@code ArmFeature.M_PROFILE}, mesmo contrato de {@link Nocp}.
    /// Nenhum campo além da condição é significativo — o resultado (UNDEF) não depende de `Rn`/`l`/
    /// `op` do encoding.
    record VlldmVlstm(
            /// Condição necessária para disparar a exceção.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VLLDM_VLSTM; }
    }

    /// `VSCCLRM` (perfil M, B15.5, `target/isa-decode/m-nocp.decode`): zera um intervalo contíguo
    /// de registradores FP existentes como armazenamento puro desde a B3.3 (`ArmCore.vfp()`) — usado
    /// pelo software para limpar informação residual de FP na transição Non-secure→Secure. Sem
    /// dependência de FPU real (zerar não é operação aritmética), ao contrário de {@link VlldmVlstm}.
    /// `firstRegister`/`lastRegister` (inclusive) já vêm resolvidos pelo decoder a partir de
    /// `Vd`/`imm`/`D`; `lastRegister` pode exceder o banco real (encoding `UNPREDICTABLE` com `imm`
    /// grande) — o executor recorta defensivamente, nunca lança.
    record Vscclrm(
            /// `true` para a forma de precisão dupla (`size=3`, registradores `D`); `false` para a
            /// forma de precisão simples (`size=2`, registradores `S`).
            boolean doublePrecision,
            /// Primeiro registrador do intervalo (`D<n>` ou `S<n>` conforme {@link #doublePrecision}).
            int firstRegister,
            /// Último registrador do intervalo, inclusive (não recortado ao tamanho real do banco).
            int lastRegister,
            /// Condição necessária para executar a limpeza.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VSCCLRM; }
    }

    /// NEON/Advanced SIMD de 32 bits, forma "three same" (B13.2/B13.4): `Vd[i] = op(Vn[i], Vm[i])`
    /// para cada lane de `1 << esz` bytes do arranjo. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticThreeSame} no ENCODING/IR, mas
    /// a SEMÂNTICA de lane é a mesma dos dois lados: ambos os executores chamam
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSame} (RFC B13.2, D1).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`), então a condição é sempre
    /// {@link Condition#AL} — não há forma condicional desta instrução.
    record NeonThreeSame(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`, bit `Q` do encoding),
            /// `false` para o de 64 bits (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword.
            int esz,
            /// Registrador de destino, SEMPRE em índice de `D` (`0`-`31`) — na forma `quad` é o
            /// `D` par que inicia o `Q` (é assim que o encoding NEON nomeia os registradores).
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_THREE_SAME; }
    }

    /// `VLD1`-`VLD4`/`VST1`-`VST4` NEON A32, forma "multiple structures" (B13.3) — transfere
    /// {@link #nregs} repetições de {@link #interleave} registradores `D` para/de memória
    /// CONSECUTIVA, com os elementos de uma estrutura ENTRELAÇADOS quando `interleave > 1`
    /// ("array of structures"). Cada registrador tocado tem `8 >> esz` elementos de `1 << esz`
    /// bytes; o registrador `D` acessado é `vd + reg + stride * xs` (`reg` em `0..nregs`, `xs` em
    /// `0..interleave`), exatamente o `tt` de `trans_VLDST_multiple` do QEMU real
    /// (`target/arm/tcg/translate-neon.c`).
    ///
    /// Espelho estrutural de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorLoadStoreMultiple}, mas com
    /// diferenças reais: NEON de 32 bits tem `stride` ("double spacing", inexistente no A64), NÃO
    /// faz wrap-around módulo 32 (registrador além de `D31` é UNDEFINED, recusado no decoder) e
    /// nunca escreve destrutivamente fora do `D` nomeado (VFP32 não zera bits altos).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonLoadStoreMultiple(
            /// `true` para `VLD1`-`VLD4`, `false` para `VST1`-`VST4`.
            boolean load,
            /// Primeiro registrador `D` transferido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding: `15` = sem escrita de volta; `13` = escrita de volta
            /// IMEDIATA (`Rn += nregs * interleave * 8` bytes); qualquer outro valor = registrador
            /// ARM cujo conteúdo é somado a `Rn` depois da transferência.
            int rm,
            /// `log2` do tamanho de cada elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword (só válido quando `interleave == 1 && stride == 1`).
            int esz,
            /// Quantas vezes o grupo de {@link #interleave} registradores se repete (`1`-`4`).
            int nregs,
            /// Quantos registradores `D` compõem UMA estrutura entrelaçada (`1`=`VLD1`/`VST1`, ...,
            /// `4`=`VLD4`/`VST4`).
            int interleave,
            /// Espaçamento entre registradores `D` de uma estrutura (`1` = consecutivos, `2` =
            /// "double spacing", `D<n>`, `D<n+2>`, ...).
            int stride) implements IrOp {
        @Override public int kind() { return Kind.NEON_LOAD_STORE_MULTIPLE; }
    }

    /// `VLD1`-`VLD4`/`VST1`-`VST4` NEON A32, forma "single structure to one lane" (B13.3) —
    /// transfere UM elemento de `1 << esz` bytes para/de a lane {@link #index} de cada um dos
    /// {@link #selem} registradores `vd + stride * xs` (`xs` em `0..selem`), SEM afetar nenhum
    /// outro bit desses registradores. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorLoadStoreSingle} (mesmas diferenças
    /// que {@link NeonLoadStoreMultiple}). {@link #condition()} sempre {@link Condition#AL}.
    record NeonLoadStoreSingle(
            /// `true` para `VLD1`-`VLD4`, `false` para `VST1`-`VST4`.
            boolean load,
            /// Primeiro registrador `D` transferido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding, mesma convenção de {@link NeonLoadStoreMultiple#rm}
            /// (escrita de volta imediata avança `selem << esz` bytes).
            int rm,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; não há forma doubleword de lane
            /// única).
            int esz,
            /// Quantos registradores `D` consecutivos (por {@link #stride}) recebem/fornecem o
            /// elemento (`1`=`VLD1`/`VST1`, ..., `4`=`VLD4`/`VST4`).
            int selem,
            /// Espaçamento entre registradores `D` (`1` ou `2`), ver
            /// {@link NeonLoadStoreMultiple#stride}.
            int stride,
            /// Índice da lane que recebe/fornece o elemento (faixa depende de `esz`: `0`-`7`
            /// byte, `0`-`3` halfword, `0`-`1` word).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_LOAD_STORE_SINGLE; }
    }

    /// `VLD1R`-`VLD4R` NEON A32, forma "single structure to all lanes" (B13.3) — lê UM elemento
    /// de `1 << esz` bytes por registrador (mesmo padrão de endereçamento de
    /// {@link NeonLoadStoreSingle}, `selem` registradores por {@link #stride}) e REPLICA esse
    /// valor por todas as lanes do `D`; quando {@link #quad}, replica também no `D` seguinte do
    /// par (`selem` é sempre `1` nesse caso). Não existe forma `VST`. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorLoadSingleReplicate}.
    /// {@link #condition()} sempre {@link Condition#AL}.
    record NeonLoadAllLanes(
            /// Primeiro registrador `D` preenchido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding, mesma convenção de {@link NeonLoadStoreMultiple#rm}
            /// (escrita de volta imediata avança `selem << esz` bytes).
            int rm,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Quantos registradores `D` são preenchidos (`1`=`VLD1R`, ..., `4`=`VLD4R`).
            int selem,
            /// Espaçamento entre registradores `D` (`1` ou `2`), ver
            /// {@link NeonLoadStoreMultiple#stride}.
            int stride,
            /// `true` (só possível quando `selem == 1`) para replicar também no `D` seguinte
            /// (`bit t` do encoding, arranjo de 128 bits nomeado por DOIS `D`).
            boolean quad) implements IrOp {
        @Override public int kind() { return Kind.NEON_LOAD_ALL_LANES; }
    }

    /// NEON/Advanced SIMD de 32 bits, forma "pairwise" (B13.4): `VPADD`/`VPMAX`/`VPMIN`. Concatena
    /// `Vn:Vm` (`Vn` primeiro), combina pares de elementos ADJACENTES nessa sequência de
    /// `2 * (8 >> esz)` elementos e grava `8 >> esz` resultados em `Vd` (metade baixa vinda de
    /// `Vn`, metade alta de `Vm`). Só forma `D` no encoding A32 (`@3same_q0`), por isso não há
    /// campo `quad`.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticPairwise} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#pairwise}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonPairwise(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp op,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word.
            int esz,
            /// Registrador de destino, índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (metade baixa do resultado), índice de `D`.
            int vn,
            /// Registrador fonte 2 (metade alta do resultado), índice de `D`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_PAIRWISE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "3-reg-same" de PONTO FLUTUANTE (B13.6): `VADD.F32`/
    /// `VSUB.F32`/`VMUL.F32`/`VMLA.F32`/`VMLS.F32`/`VFMA.F32`/`VFMS.F32`/`VABD.F32`/`VMAX.F32`/
    /// `VMIN.F32`/`VMAXNM.F32`/`VMINNM.F32`/`VCEQ.F32`/`VCGE.F32`/`VCGT.F32`/`VACGE.F32`/
    /// `VACGT.F32`/`VRECPS.F32`/`VRSQRTS.F32`. Só a forma F32 (`esz=2`) — F16 (`FEAT_FP16`) é
    /// recusada no decoder (task futura).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorFpArithmeticThreeSame} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSame}), RFC B13.2 D1. A
    /// distinção fundido × NÃO fundido do multiply-accumulate (`VFMA` vs `VMLA`) está na
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp} escolhida pelo decoder.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFpThreeSame(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`, bit `Q` do encoding),
            /// `false` para o de 64 bits (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: sempre `2` (F32) nesta task.
            int esz,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FP_THREE_SAME; }
    }

    /// NEON/Advanced SIMD de 32 bits, "pairwise" de PONTO FLUTUANTE (B13.6): `VPADD.F32`/
    /// `VPMAX.F32`/`VPMIN.F32`. Concatena `Vn:Vm` (`Vn` primeiro), combina pares de elementos
    /// ADJACENTES nessa sequência de `2 * (8 >> esz)` elementos e grava `8 >> esz` resultados em
    /// `Vd` (metade baixa vinda de `Vn`, metade alta de `Vm`). Só forma `D` no encoding A32
    /// (`@3same_fp_q0`), por isso não há campo `quad`.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorFpArithmeticPairwise} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpPairwise}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFpPairwise(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp op,
            /// `log2` do tamanho do elemento em bytes: sempre `2` (F32) nesta task.
            int esz,
            /// Registrador de destino, índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (metade baixa do resultado), índice de `D`.
            int vn,
            /// Registrador fonte 2 (metade alta do resultado), índice de `D`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FP_PAIRWISE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por IMEDIATO (B13.7):
    /// `VSHR`/`VSRA`/`VRSHR`/`VRSRA`/`VSRI`/`VSHL`/`VSLI`/`VQSHL`/`VQSHLU` (as 14 famílias, `esz`
    /// `0`-`3`). `Vd[i] = op(Vm[i], #shift)` — e, para `VSRA`/`VRSRA`/`VSRI`/`VSLI`, ACUMULA ou
    /// INSERE no `Vd[i]` ATUAL (o campo {@link #vd} é destino E fonte nessas famílias). O
    /// deslocamento já vem resolvido do encoding (`immh:immb`), NUNCA recalculado no executor.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorShiftImmediate} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftImmediate}), RFC B13.2 D1.
    /// **Não há `Vn`** — é forma de 2 registradores: {@link #vm} é a FONTE do valor deslocado.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonShiftImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`, bit `Q` do encoding), `false`
            /// para o de 64 bits (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword.
            int esz,
            /// Quantidade de deslocamento já resolvida (`1..esize` para os à direita, `0..esize-1`
            /// para os à esquerda).
            int shift,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE nas famílias que acumulam/inserem
            /// (`VSRA`/`VRSRA`/`VSRI`/`VSLI`).
            int vd,
            /// Registrador fonte do valor deslocado, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_SHIFT_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por imediato ESTREITANTE
    /// (B13.8): `VSHRN`/`VRSHRN`/`VQSHRUN`/`VQRSHRUN`/`VQSHRN`/`VQRSHRN` (as 8 famílias). A fonte é
    /// um `Q` (128 bits, elementos de `esz + 1` bytes), o destino é um `D` (64 bits, elementos de
    /// `esz` bytes) — `Vd[i] = narrow(op(Vm[i], #shift))`, deslocamento à direita já resolvido do
    /// encoding. **Sem campo `quad`**: a fonte é sempre `Q` e o destino sempre `D`; o bit `Q` do
    /// encoding faz parte do OPCODE (escolhe entre `VSHRN`/`VRSHRN`, etc.), não da largura.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorShiftNarrowImmediate} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftNarrowImmediate}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonShiftNarrowImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp op,
            /// `log2` do tamanho do elemento de DESTINO (lado ESTREITO) em bytes: `0`=byte,
            /// `1`=halfword, `2`=word. A fonte tem elementos de `esz + 1`.
            int esz,
            /// Quantidade de deslocamento à direita já resolvida (`1..8<<esz`).
            int shift,
            /// Registrador de destino (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_SHIFT_NARROW_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por imediato ALARGANTE
    /// (B13.8): `VSHLL` (fonte assinada → `SSHLL`, fonte não assinada → `USHLL`). A fonte é um `D`
    /// (64 bits, elementos de `esz` bytes), o destino é um `Q` (128 bits, elementos de `esz + 1`
    /// bytes) — `Vd[i] = ext(Vm[i]) << #shift`, nunca satura. **Sem campo `quad`** pelo mesmo
    /// motivo de {@link NeonShiftNarrowImmediate}: fonte `D`, destino `Q` fixos; o bit `Q` do
    /// encoding faz parte do OPCODE.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorShiftWidenImmediate} no
    /// ENCODING/IR; a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftWidenImmediate}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonShiftWidenImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp op,
            /// `log2` do tamanho do elemento de FONTE (lado ESTREITO) em bytes: `0`=byte,
            /// `1`=halfword, `2`=word. O destino tem elementos de `esz + 1`.
            int esz,
            /// Quantidade de deslocamento à esquerda já resolvida (`0..(8<<esz)-1`).
            int shift,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_SHIFT_WIDEN_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" `VCVT` fixo↔float F32 (B13.8):
    /// `VCVT.F32.S32`/`VCVT.F32.U32` (`toFloat`, inteiro fixo `* 2^-fractionBits` → F32) e
    /// `VCVT.S32.F32`/`VCVT.U32.F32` (`!toFloat`, F32 `* 2^fractionBits`, arredonda para zero,
    /// satura → inteiro). Elementos de 32 bits nos dois lados (mesma largura), `4` ou `2` lanes
    /// conforme {@link #quad}.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorFpConvertFixedPoint} no
    /// ENCODING/IR (menos o campo `scalar`, que não existe em NEON A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPoint}),
    /// RFC B13.2 D1. A forma de MEIA PRECISÃO (`VCVT` F16) é task irmã (depende de B19.5.1).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonConvertFixedPoint(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`, bit `Q` do encoding), `false` para
            /// o de 64 bits (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: sempre `2` (F32/32 bits) nesta task.
            int esz,
            /// Número de bits fracionários (`#fbits` do encoding, `1..32`). Fator de escala
            /// `2^fractionBits`.
            int fractionBits,
            /// `true` → `SCVTF`/`UCVTF` (inteiro → FP, depois `/ 2^fbits`); `false` → `FCVTZS`/
            /// `FCVTZU` (FP `* 2^fbits`, arredonda para zero, satura → inteiro).
            boolean toFloat,
            /// `true` para as variantes assinadas (`.S32`), `false` para as não assinadas (`.U32`).
            boolean signed,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_CONVERT_FIXED_POINT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "1-reg-and-modified-immediate" (B13.9): `VMOV`/`VMVN`/`VORR`/
    /// `VBIC` imediato — `cmode`/`op` discriminam as 4 famílias na função de trans (uma linha de
    /// decodetree, `Vimm_1r`). **Não há `Vm`/`Vn`**: bits[3:0] são metade do imediato, não um
    /// registrador.
    ///
    /// {@link #imm64} já vem EXPANDIDO pelo decoder (núcleo COMPARTILHADO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate#expand}, RFC B13.2 D1,
    /// aplicada ANTES da duplicação — o lado A64/`Vimm` da B19.6 reusa o MESMO núcleo). `MVN`
    /// carrega o MESMO `imm64` de `MOV` (a inversão acontece na EXECUÇÃO — Decisão 2 da B13.9, não
    /// dobrar `MVN` em `MOV` invertido).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonModifiedImmediate(
            /// Operação a executar (`MOV`/`MVN`/`ORR`/`BIC`, já classificada pelo decoder).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`, bit `Q` do encoding), `false` para o de
            /// 64 bits (`D<d>`).
            boolean quad,
            /// Imediato de 64 bits já EXPANDIDO (ver acima) — nunca recalculado no executor.
            long imm64,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `ORR`/`BIC` (leem `Vd` atual).
            int vd) implements IrOp {
        @Override public int kind() { return Kind.NEON_MODIFIED_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two registers, or three registers of different lengths",
    /// SUBGRUPO "three-reg-different-lengths" — forma **Long** ALARGANDO (B13.10): `VADDL`/`VSUBL`/
    /// `VABAL`/`VABDL`/`VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8`. `Vn`/`Vm` são
    /// `D` (elementos de {@link #esz} bytes), `Vd` é `Q` (elementos de `esz+1`, DOBRO — nomeado pelo
    /// `D` par que inicia o `Q`, como o NEON encoda operandos de 128 bits). **Sem campo `quad`**: o
    /// destino é SEMPRE `Q` nesta forma (não há "3-reg-different" com destino `D`).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticWidening} no
    /// ENCODING/IR (menos `scalar`/`q`, que não existem nesta seção do A32 — sem forma "2", sem
    /// forma escalar real); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#widening}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonWidening(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vn`/`Vm`) em bytes — `0`-`2` (byte/half/
            /// word). `Vd` usa `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vn,
            /// Registrador fonte 2 (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_WIDENING; }
    }

    /// NEON/Advanced SIMD de 32 bits, "three-reg-different-lengths", forma **Wide** (B13.10):
    /// `VADDW`/`VSUBW`. `Vd`/`Vn` são `Q` (elementos de `esz+1`), `Vm` é `D` (elementos de
    /// {@link #esz}). **Sem campo `quad`**: `Vd`/`Vn` são SEMPRE `Q`, `Vm` SEMPRE `D` nesta forma.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticWide} no
    /// ENCODING/IR (menos `q`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wide}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonWide(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vm`) em bytes — `0`-`2`. `Vd`/`Vn` usam
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`Q`, 128 bits, já LARGO), em índice de `D` par que inicia o `Q`.
            int vn,
            /// Registrador fonte 2 (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_WIDE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "three-reg-different-lengths", forma **Narrow**/"half
    /// narrowing" (B13.10): `VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN`. `Vn`/`Vm` são `Q` (elementos de
    /// `esz+1`), `Vd` é `D` (elementos de {@link #esz}, a metade ALTA da soma/diferença larga).
    /// **Sem campo `quad`**: `Vn`/`Vm` são SEMPRE `Q`, `Vd` SEMPRE `D` nesta forma (A32 não tem
    /// forma "2" — `laneOffset` é sempre `0` no executor).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticNarrow} no
    /// ENCODING/IR (menos `q`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#narrow}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonNarrow(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vd`) em bytes — `0`-`2`. `Vn`/`Vm` usam
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vn,
            /// Registrador fonte 2 (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_NARROW; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar", forma **mesma largura**/"doubling high
    /// half" (B13.11): `VMLA`/`VMLS`/`VMUL` inteiro (sem variante de sinal — mesmo padrão do
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#MUL} "three same") e
    /// `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` (`VQRDMLAH`/`VQRDMLSH` só sob
    /// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#ADVANCED_SIMD_RDM}). `Vd`/`Vn` são `D` ou
    /// `Q` conforme {@link #quad}; {@link #vm} é o registrador do ESCALAR já restrito à faixa REAL
    /// do encoding A32 (`D0`-`D7` halfword / `D0`-`D15` word — diferente do índice `H:L:M` do A64,
    /// que estreita `Rm` a `V0`-`V15`), e {@link #index} já extraído (`M:Vm[3]` halfword / `M`
    /// word).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticThreeSameByElement}
    /// no ENCODING/IR (índice montado diferente, sem `scalar`, que não existe nesta seção do A32); a
    /// SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameByElement}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonThreeSameByElement(
            /// Operação a executar (núcleo compartilhado) — só `MUL`/`MLA`/`MLS`/`SQDMULH`/
            /// `SQRDMULH`/`SQRDMLAH`/`SQRDMLSH` são válidas aqui (G8: o decoder nunca produz outro
            /// valor).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes — `1` (halfword) ou `2` (word); `0`/`3` não
            /// existem nesta classe (G8 no decoder).
            int esz,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `MLA`/`MLS`/`SQRDMLAH`/`SQRDMLSH` (leem `Vd` atual).
            int vd,
            /// Registrador fonte 1, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vn,
            /// Registrador do ESCALAR (`D0`-`D7` halfword / `D0`-`D15` word — já restrito pelo
            /// decoder, nunca um índice de `D` de 5 bits completo).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (`M:Vm[3]`, 2 bits, halfword / `M`, 1 bit,
            /// word — já extraído pelo decoder).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_THREE_SAME_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar", forma **alargando** (B13.11):
    /// `VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`. `Vd` é sempre `Q`, `Vn` é sempre `D`
    /// (mesma disciplina de {@link NeonWidening} — sem forma "2", sem forma escalar real); {@link
    /// #vm} é o registrador do ESCALAR já restrito à faixa real e {@link #index} já extraído (mesma
    /// convenção de {@link NeonThreeSameByElement}).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticWideningByElement}
    /// no ENCODING/IR (índice montado diferente, sem `scalar`/`q`); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wideningByElement}),
    /// RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonWideningByElement(
            /// Operação a executar (núcleo compartilhado) — só `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/
            /// `SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL` são válidas aqui (G8).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vn`/escalar) em bytes — `1` ou `2`. `Vd` usa
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vn,
            /// Registrador do ESCALAR (`D0`-`D7` halfword / `D0`-`D15` word — já restrito pelo
            /// decoder).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (`M:Vm[3]` halfword / `M` word — já
            /// extraído pelo decoder).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_WIDENING_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar" de PONTO FLUTUANTE F32 (B13.11):
    /// `VMLA_F`/`VMLS_F`/`VMUL_F` — `MLA`/`MLS` NÃO fundidos (decisão 3 da B13.6: reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp#MLA}/{@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp#MLS}, nunca `FMLA`/`FMLS`). `Vd`/
    /// `Vn` são `D` ou `Q` conforme {@link #quad}; {@link #vm} é o registrador do ESCALAR
    /// (`D0`-`D15`, sempre F32 — a forma F16/`size==0b01` é recusada no decoder, task irmã "NEON
    /// FP16 AArch32") e {@link #index} (`M`, 1 bit) já extraídos.
    ///
    /// Espelho de {@link
    /// dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorFpArithmeticThreeSameByElement} no ENCODING/IR
    /// (sem `scalar`/`esz`, que não existem nesta seção do A32 — sempre F32/vetorial); a SEMÂNTICA
    /// vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameByElement}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFpThreeSameByElement(
            /// Operação a executar (núcleo compartilhado) — só `MUL`/`MLA`/`MLS` são válidas aqui
            /// (G8: o decoder nunca produz `MULX`/`FMLA`/`FMLS`/etc. nesta forma).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `MLA`/`MLS` (lê `Vd` atual).
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vn,
            /// Registrador do ESCALAR (`D0`-`D15` — já restrito pelo decoder).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (`M`, 1 bit — já extraído pelo decoder).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_FP_THREE_SAME_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" INTEIRA, sub-grupo `size==0b11`
    /// (B13.12): `VREV64`/`VREV32`/`VREV16` (reversão de bytes), `VPADDL`/`VPADAL` (pareamento
    /// largo, `S`/`U`, o segundo ACUMULA), `VCLS`/`VCLZ`/`VCNT`/`VMVN`, `VQABS`/`VQNEG`, as 5
    /// comparações-com-zero (`VCGT0`/`VCGE0`/`VCEQ0`/`VCLE0`/`VCLT0`), `VABS`/`VNEG` e `VRECPE`/
    /// `VRSQRTE` inteiros (estimativas puro-inteiras).
    ///
    /// **Layout PRÓPRIO deste sub-grupo** (diferente de B13.4-B13.11): `size` = bits[19:18]
    /// (elemento de {@link #esz}), `opc1` = bits[17:16], `opc2` = bits[10:7], `q` = bit6 —
    /// {@link #esz} neste record é o CAMPO `size`, não uma largura fixa por forma.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticUnary} no
    /// ENCODING/IR (sem `scalar`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonUnary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`). Também é FONTE em `VPADAL` (lê `Vd` atual, já em `esz+1`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word; `3` não existe
            /// neste sub-grupo, G8 no decoder). Para `VPADDL`/`VPADAL`, é o tamanho ESTREITO
            /// (`Vd` usa `esz+1`).
            int esz,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_UNARY; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous", sub-grupo `size==0b11`
    /// (B13.12): `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U` — narrow unário. `Vm` é `Q` (elementos de
    /// `esz+1`), `Vd` é `D` (elementos de {@link #esz}). **Sem campo `quad`**: fonte SEMPRE `Q`,
    /// destino SEMPRE `D` (a forma "2-reg-misc" força `q=0` no encoding real, ver `neon-dp.decode`).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorArithmeticNarrowUnary} no
    /// ENCODING/IR (sem `scalar`/`q`); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#narrowUnary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonNarrowUnary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vd`) em bytes — `0`-`2`. `Vm` usa `esz+1`.
            int esz,
            /// Registrador de destino (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_NARROW_UNARY; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" de PONTO FLUTUANTE, sub-grupo
    /// `size==0b11` (B13.12): `VABS_F`/`VNEG_F`, as 5 comparações-com-zero FP (`VCGT0_F`/`VCGE0_F`/
    /// `VCEQ0_F`/`VCLE0_F`/`VCLT0_F`) e `VRECPE_F`/`VRSQRTE_F`. **Sem campo `esz`**: só F32
    /// (`esz=2`) existe neste sub-grupo em A32 (F16 é `FEAT_FP16`, task futura irmã da B19.5) —
    /// mesma convenção de {@link NeonFpThreeSameByElement}, que também fixa `esz=2` internamente.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorFpArithmeticUnary} no
    /// ENCODING/IR (sem `scalar`/`esz`); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFpUnary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FP_UNARY; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" de PONTO FLUTUANTE, conversão de
    /// PRECISÃO, sub-grupo `size==0b11` (B13.13): `VCVT_F16_F32`/`VCVT_B16_F32` (estreita, 4 lanes
    /// F32 de `Vm` → 4 lanes F16/`bf16` de `Vd`) e `VCVT_F32_F16` (alarga, 4 lanes F16 de `Vm` → 4
    /// lanes F32 de `Vd`). **Sem campo `quad`** (diferente de {@link NeonFpUnary}): o encoding real
    /// é `@2misc_q0` — um dos dois lados é sempre `D` e o outro sempre `Q`, nunca as duas formas
    /// D/D ou Q/Q.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpConvertPrecision}), RFC B13.2 D1
    /// — reaproveita `Float.floatToFloat16`/`float16ToFloat` (B19.4) e `bf16Bits`/`bf16ToFloat`
    /// (B19.7) sem escrever conversão nova.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFpConvertPrecision(
            /// Direção/formato da conversão.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpConvertPrecisionOp op,
            /// Registrador de destino, em índice de `D` (`0`-`31`); nas formas estreitas é o `D`
            /// único de saída, na forma larga (`WIDEN_F16`) é o `D` par que inicia o `Q` de saída.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); nas formas estreitas é o `D` par que
            /// inicia o `Q` de entrada, na forma larga (`WIDEN_F16`) é o `D` único de entrada.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FP_CONVERT_PRECISION; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VCMLA`/`VCADD` (B13.17, `FEAT_FCMA`): trata
    /// pares de lanes ADJACENTES (par = parte real, ímpar = parte imaginária) como um número
    /// complexo. `VCMLA` acumula em `vd` (lê e escreve); `VCADD` só escreve. Mesmo encoding em A32
    /// e T32 (`neon-shared.decode`, cabeçalho do arquivo) — nenhuma task T32 própria necessária.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd}/
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate}), RFC
    /// B13.2 D1 — **exceção do épico B13**: não há semântica A64 prévia para migrar (`FCMLA`/
    /// `FCADD` do A64 também não existem ainda), a semântica nasce aqui para o A64 reusar depois.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonComplex(
            /// `true` para `VCMLA` (multiply-accumulate, FUNDIDO, lê e escreve `vd`), `false` para
            /// `VCADD` (só soma, só escreve `vd`).
            boolean multiplyAccumulate,
            /// Rotação em graus: `0`/`90`/`180`/`270` para `VCMLA`; só `90`/`270` para `VCADD`
            /// (já convertido do campo cru de 1/2 bits do encoding — nunca recalculado aqui).
            int rotation,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `1`=F16 (`FEAT_FP16`), `2`=F32.
            int esz,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`a` = real/imaginária), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (`b` = real/imaginária, rotacionado por {@link #rotation}), em
            /// índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_COMPLEX; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VCMLA_scalar` (B13.17, `FEAT_FCMA`): como
    /// {@link NeonComplex} com `multiplyAccumulate=true`, mas o operando `b` é um único número
    /// complexo FIXO lido de `vm` no par de lanes `index`/`index+1` e replicado para cada par de
    /// `vn`. **Não existe `VCADD_scalar`** (só `VCMLA` tem forma indexada).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulateByElement}),
    /// RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonComplexByElement(
            /// Rotação em graus: `0`/`90`/`180`/`270`.
            int rotation,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `1`=F16 (`índice` de 1 bit, 2 complexos por
            /// `D`), `2`=F32 (`índice` sempre `0`, 1 complexo ocupa o `D` inteiro).
            int esz,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`a`, varia por par), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (`b`, FIXO, lido uma vez), em índice de `D` (`0`-`31`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice do par complexo dentro de {@link #vm}: `0`-`1` para F16, sempre `0` para F32.
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_COMPLEX_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSDOT`/`VUDOT`/`VUSDOT` (B13.18,
    /// `FEAT_DotProd`/`FEAT_I8MM`): cada lane de 32 bits de {@link #vd} ACUMULA (wrap) a soma dos 4
    /// produtos de byte de {@link #vn}/{@link #vm} na mesma lane. **Não existe `VSUDOT` vetorial**
    /// (só `_scalar`) — este record nunca representa essa combinação de sinais na forma vetorial.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProduct}),
    /// RFC B13.2 D1 — **exceção do épico**: nem `SDOT_v`/`UDOT_v` nem `USDOT`/`SUDOT` do A64 têm
    /// decoder ainda, então não há semântica prévia a migrar; a semântica nasce aqui para a B19.12
    /// (a task irmã A64 das formas mistas) reusar.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonDotProduct(
            /// `true` se {@link #vn} é lido como assinado (`VSDOT`/`VSUDOT`), `false` se sem sinal
            /// (`VUDOT`/`VUSDOT`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSDOT`/`VUSDOT`), `false` se sem sinal
            /// (`VUDOT`/`VSUDOT`).
            boolean signedM,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSDOT_scalar`/`VUDOT_scalar`/
    /// `VUSDOT_scalar`/`VSUDOT_scalar` (B13.18): como {@link NeonDotProduct}, mas o operando `b` é
    /// uma única lane de 32 bits FIXA lida de {@link #vm} no {@link #index}, replicada para cada
    /// lane de {@link #vn}. `VSUDOT` só existe nesta forma (não há `VSUDOT` vetorial).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProductByElement}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonDotProductByElement(
            /// `true` se {@link #vn} é lido como assinado (`VSDOT_scalar`/`VSUDOT_scalar`), `false`
            /// se sem sinal (`VUDOT_scalar`/`VUSDOT_scalar`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSDOT_scalar`/`VUSDOT_scalar`), `false`
            /// se sem sinal (`VUDOT_scalar`/`VSUDOT_scalar`).
            boolean signedM,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (varia por lane), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez), em índice de `D` (`0`-`15`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice da lane de 32 bits dentro de {@link #vm}: `0`-`1` (um `D` guarda 2 lanes).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSMMLA`/`VUMMLA`/`VUSMMLA` (B13.19,
    /// `FEAT_I8MM`, mesma feature de {@link NeonDotProduct}/{@link NeonDotProductByElement} para as
    /// formas mistas): multiplicação de matriz `2×8 · 8×2` de inteiros de 8 bits, acumulando em
    /// `int32` COM WRAP (nunca satura). **Sempre 128 bits** — não existe forma `D` (índice de
    /// registrador ímpar em {@link #vd}/{@link #vn}/{@link #vm} é UNDEFINED, mesma disciplina das
    /// formas `quad` das siblings deste arquivo, mas aqui sem campo `quad`: a forma `D` não existe).
    /// **Não existe `VSUMMLA`** — a assimetria (`VUSMMLA` = `Vn` sem sinal/`Vm` assinado) é
    /// intencional, espelhando o A64.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#matrixMultiplyAccumulate}), criado
    /// pela **B19.12** (a task irmã A64 de `SMMLA`/`UMMLA`/`USMMLA`) — reusado sem alteração.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonMatrixMultiplyAccumulate(
            /// `true` se {@link #vn} é lido como assinado (`VSMMLA`/`VUSMMLA`), `false` se sem sinal
            /// (`VUMMLA`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSMMLA`), `false` se sem sinal
            /// (`VUMMLA`/`VUSMMLA`).
            boolean signedM,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`) — o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (duas linhas de 8 bytes), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (duas colunas de 8 bytes), em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFML`/`VFMSL` (B13.20, `FEAT_FHM`, forma
    /// vetorial): multiplica lanes de MEIA precisão de {@link #vn}/{@link #vm} e acumula (FUNDIDO,
    /// um único arredondamento) em lanes de precisão SIMPLES de {@link #vd} (lido e escrito) —
    /// largura mista, destino do dobro de lanes largas que fontes. **`quad=false`**: {@link #vn}/
    /// {@link #vm} são registradores `S` (`0`-`31`, vista de 32 bits = 2 lanes f16), {@link #vd} é
    /// `D` (2 lanes f32). **`quad=true`**: {@link #vn}/{@link #vm} são `D` (4 lanes f16), {@link
    /// #vd} é o `D` par que inicia o `Q` (4 lanes f32) — índice ÍMPAR é UNDEFINED (mesma disciplina
    /// das siblings deste arquivo). Ao contrário do `2`/laneOffset do A64 (`FMLAL2`/`FMLSL2`), o
    /// NEON de 32 bits NÃO tem forma de metade alta — o executor sempre lê o registrador FONTE
    /// inteiro (nenhum `laneOffset` além de `0`).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddLong}) — nasce
    /// aqui (B13.20) porque nem esta nem a task irmã A64 (**B19.13**, `FMLAL`/`FMLSL`/`FMLAL2`/
    /// `FMLSL2`) tinham semântica prévia; quem rodar primeiro põe no núcleo, a outra reusa.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFusedMultiplyAddLong(
            /// `false` para `VFML`/`VFMAL` (soma), `true` para `VFMSL` (subtração — acumula `-a*b`).
            boolean subtract,
            /// `true` para a forma `Q0,D,D` (fontes `D`, destino `Q`), `false` para `D,S,S` (fontes
            /// `S`, destino `D`) — ver Javadoc da classe.
            boolean quad,
            /// Registrador de destino/acumulador: índice de `D` (`quad=false`) ou o `D` par que
            /// inicia o `Q` (`quad=true`).
            int vd,
            /// Registrador fonte 1: índice de `S` (`0`-`31`, `quad=false`) ou de `D` (`quad=true`).
            int vn,
            /// Registrador fonte 2: índice de `S` (`0`-`31`, `quad=false`) ou de `D` (`quad=true`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFML_scalar`/`VFMSL_scalar` (B13.20,
    /// `FEAT_FHM`): como {@link NeonFusedMultiplyAddLong}, mas o operando `b` é uma única lane f16
    /// FIXA, replicada para cada lane de {@link #vn}. **Os extratores de {@link #rm}/{@link #index}
    /// diferem entre as duas formas** (bits espalhados, ver `NeonSharedDecoder`): `quad=false`:
    /// {@link #rm} é um `S` de 4 bits (`S0`-`S15`, SEM bit de extensão) e {@link #index} (`0`-`1`)
    /// escolhe qual das 2 lanes f16 de {@link #rm}; `quad=true`: {@link #rm} é um `D` de 3 bits
    /// (`D0`-`D7`) e {@link #index} (`0`-`3`) escolhe qual das 4 lanes f16.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddLongByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFusedMultiplyAddLongByElement(
            /// `false` para `VFML`/`VFMAL` (soma), `true` para `VFMSL` (subtração — acumula `-a*b`).
            boolean subtract,
            /// `true` para a forma `Q0,D,D` (fonte `vn` `D`, destino `Q`), `false` para `D,S,S`
            /// (fonte `vn` `S`, destino `D`) — ver Javadoc da classe.
            boolean quad,
            /// Registrador de destino/acumulador: índice de `D` (`quad=false`) ou o `D` par que
            /// inicia o `Q` (`quad=true`).
            int vd,
            /// Registrador fonte 1 (varia por lane): índice de `S` (`0`-`31`, `quad=false`) ou de
            /// `D` (`quad=true`).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez): `S0`-`S15` (`quad=false`) ou `D0`-`D7`
            /// (`quad=true`) — ver Javadoc da classe.
            int rm,
            /// Índice da lane f16 dentro de {@link #rm}: `0`-`1` (`quad=false`) ou `0`-`3`
            /// (`quad=true`).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VDOT_b16` (B13.21, `FEAT_BF16`): produto
    /// escalar de PARES `bf16`, acumulando em `f32`. Sibling FP de {@link NeonDotProduct} (que é
    /// inteiro) — sem campos de sinal, o formato `bf16` não tem variante assinada/sem sinal.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProduct}),
    /// criado pela B19.7 (a task irmã A64 de `BFDOT`) — reusado sem nenhuma mudança.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonDotProductBFloat16(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BFLOAT16; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VDOT_b16_scal` (B13.21): como
    /// {@link NeonDotProductBFloat16}, mas o operando `b` é um único par `bf16` de 32 bits FIXO
    /// lido de {@link #vm} no {@link #index}, replicado para cada lane de {@link #vn}.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProductByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonDotProductByElementBFloat16(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (varia por lane), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez), em índice de `D` (`0`-`15`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice do par `bf16` de 32 bits dentro de {@link #vm}: `0`-`1` (um `D` guarda 2
            /// pares).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VMMLA_b16` (B13.21, `FEAT_BF16`):
    /// multiplicação de matriz `2×4 · 4×2` de pares `bf16`, acumulando em `f32`. Irmã de ponto
    /// flutuante de {@link NeonMatrixMultiplyAccumulate} (`K=4` em vez de `K=8`, sem campos de
    /// sinal). **Sempre 128 bits** — não existe forma `D` (índice de registrador ímpar em
    /// {@link #vd}/{@link #vn}/{@link #vm} é UNDEFINED, mesma disciplina de
    /// {@link NeonMatrixMultiplyAccumulate}).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMatrixMultiplyAccumulate}),
    /// criado pela B19.7 (a task irmã A64 de `BFMMLA`) — reusado sem nenhuma mudança.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonMatrixMultiplyAccumulateBFloat16(
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`) — o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (duas linhas de 4 pares `bf16`), em índice de `D` (ver
            /// {@link #vd}).
            int vn,
            /// Registrador fonte 2 (duas colunas de 4 pares `bf16`), em índice de `D` (ver
            /// {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFMA_b16` (B13.21, `FEAT_BF16`, forma
    /// vetorial: mnemônicos `VFMAB`/`VFMAT`): multiply-accumulate LONG (soma simples, NÃO fundida)
    /// — para cada uma das 4 lanes `f32` de {@link #vd} (`Vd.4S`, sempre 128 bits), lê o elemento
    /// `bf16` de índice `2e+top` de {@link #vn}/{@link #vm} (SEMPRE `Q`, 8 elementos `bf16` cada),
    /// multiplica em `binary32` e acumula. **Diferente do irmão inteiro/meia-precisão
    /// {@link NeonFusedMultiplyAddLong}**: aqui NÃO existe forma `D,S,S` — confirmado que GAS
    /// recusa a forma não-`Q` (`invalid instruction shape`) e que o bit nomeado `q` no `.decode`
    /// é, na prática, o seletor BOTTOM/TOP (`VFMAB`=`0`/`VFMAT`=`1`), estrutura IDÊNTICA à do A64
    /// `BFMLALB`/`BFMLALT` — medido byte a byte contra `arm-linux-gnueabihf-as -march=armv8.2-a+bf16`
    /// (`vfmab.bf16 q0,q1,q2`=`0xFC320814`, `vfmat.bf16 q0,q1,q2`=`0xFC320854`, só o bit6 muda).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMultiplyAddLong}), criado pela
    /// B19.7 (a task irmã A64 de `BFMLALB`/`BFMLALT`) — reusado sem nenhuma mudança (a mesma
    /// interleave par/ímpar de `top` já assume fonte de 8 elementos, exatamente o que `Q` fornece
    /// aqui).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFusedMultiplyAddLongBFloat16(
            /// `false`=`VFMAB` (elementos PARES de {@link #vn}/{@link #vm}, índice `2e`),
            /// `true`=`VFMAT` (ÍMPARES, índice `2e+1`).
            boolean top,
            /// Registrador de destino/acumulador: o `D` par que inicia o `Q` (`Vd.4S`).
            int vd,
            /// Registrador fonte 1: o `D` par que inicia o `Q` (`Vn.8H`, lido elemento a elemento).
            int vn,
            /// Registrador fonte 2: o `D` par que inicia o `Q` (`Vm.8H`, lido elemento a elemento).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFMA_b16_scal` (B13.21, mnemônicos
    /// `VFMAB`/`VFMAT` indexados): como {@link NeonFusedMultiplyAddLongBFloat16}, mas {@link #vm}
    /// sempre contribui o MESMO elemento `bf16` {@link #index}, restrito a `D0`-`D7` (3 bits, SEM
    /// bit de extensão — nunca `D8`-`D31`), diferente de {@link #vn} (sempre `Q` completo).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMultiplyAddLongByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonFusedMultiplyAddLongByElementBFloat16(
            /// Ver {@link NeonFusedMultiplyAddLongBFloat16#top}.
            boolean top,
            /// Registrador de destino/acumulador: o `D` par que inicia o `Q` (`Vd.4S`).
            int vd,
            /// Registrador fonte 1: o `D` par que inicia o `Q` (`Vn.8H`, lido elemento a elemento).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez): `D0`-`D7`.
            int vm,
            /// Índice do elemento `bf16` de {@link #vm} usado em TODA a operação (`0`-`3`).
            int index) implements IrOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14, "2-reg-misc grouping"
    /// `opc1=0b10` `opc2` `0000`-`0011` — MESMO frame/decoder de {@link NeonUnary} e companhia,
    /// B13.12). **Exceção do épico**: sem equivalente A64 (`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`
    /// são SEIS instruções de UM destino, não a mesma semântica — ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp}). {@link #vd}/{@link #vm}
    /// são FONTE **e** DESTINO (troca no lugar) — núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#swapPermute}) usa buffer (E10).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonSwapPermute(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word; `3` é reservado,
            /// recusado pelo decoder). Ignorado por {@link #op}=`SWAP` (troca completa).
            int esz,
            /// Registrador FONTE e DESTINO 1, em índice de `D` (`0`-`31`); na forma `quad` é o `D`
            /// par que inicia o `Q`.
            int vd,
            /// Registrador FONTE e DESTINO 2, em índice de `D` (`0`-`31`); na forma `quad` é o `D`
            /// par que inicia o `Q`.
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_SWAP_PERMUTE; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VEXT` (B13.14, fora do sub-layout "2-reg-misc": bit24=0
    /// distingue do resto de `size==0b11`). Concatena `Vm:Vn` (`Vn` nos bytes BAIXOS) e extrai uma
    /// janela de {@code datasize} bytes começando em {@link #imm} bytes — puramente reorganização de
    /// bytes, sem aritmética. Migração D1 do MESMO algoritmo de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorExtract} (B8.10) — núcleo COMPARTILHADO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#extract}.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonExtract(
            /// `true` para arranjo de 128 bits (`imm` até `15`), `false` para 64 bits (`imm` até
            /// `7`).
            boolean quad,
            /// Deslocamento em BYTES (não bits) dentro da janela concatenada.
            int imm,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (metade BAIXA da concatenação), em índice de `D` (ver
            /// {@link #vd}).
            int vn,
            /// Registrador fonte 2 (metade ALTA da concatenação), em índice de `D` (ver
            /// {@link #vd}).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_EXTRACT; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VTBL`/`VTBX` (B13.14, fora do sub-layout "2-reg-misc":
    /// bit11=1 distingue do resto de `size==0b11`). Trata `Vn`, `Vn+1`, ..., `Vn+len` ({@link #len}
    /// registradores `D` consecutivos, `Vn+len` não pode passar de `D31` — checado pelo decoder,
    /// G8) como UMA tabela contígua de bytes, e substitui cada byte de {@link #vm} pelo byte da
    /// tabela no índice que ele contém — índice fora da tabela produz `0` (`VTBL`) ou preserva o
    /// byte ATUAL de {@link #vd} (`VTBX`, {@link #tbx}). Migração D1 do MESMO algoritmo de
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.VectorTableLookup} (B8.10) — núcleo
    /// COMPARTILHADO {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#tableLookup}.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonTableLookup(
            /// `true` para `VTBX` (índice fora da tabela preserva `Vd`), `false` para `VTBL`
            /// (produz `0`).
            boolean tbx,
            /// Quantos registradores ALÉM de {@link #vn} compõem a tabela, MENOS `1` (`0`=`1`
            /// registrador `D`, ..., `3`=`4` registradores) — nome espelha o campo `len` do encoding
            /// real.
            int len,
            /// Registrador de destino, em índice de `D` (`0`-`31`).
            int vd,
            /// Primeiro registrador `D` da tabela (`0`-`31`); os demais são `(vn+1)`...`(vn+len)`.
            int vn,
            /// Registrador `D` com os índices (um por byte).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_TABLE_LOOKUP; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VDUP` escalar (B13.14, `VDUP_scalar`, fora do sub-layout
    /// "2-reg-misc": bit11=1 distingue do resto de `size==0b11`, MESMO espaço de `VTBL`/`VTBX`).
    /// Replica o elemento {@link #index} de {@link #vm} (tamanho {@link #esz}) por todas as lanes de
    /// {@link #vd}. **3 linhas de encoding, não campo `size` livre**: o tamanho vem do PADRÃO de
    /// bits do imediato (posição do bit `1` mais baixo em `imm4`), decodificado ANTES de construir
    /// este record — ver Javadoc do decoder.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonDuplicateScalar(
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word), vindo do PADRÃO de
            /// bits do imediato, não de um campo `size` livre.
            int esz,
            /// Índice do elemento dentro de {@link #vm} a replicar.
            int index,
            /// `true` para o arranjo de 128 bits (`Q<d>`), `false` para o de 64 bits (`D<d>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_DUPLICATE_SCALAR; }
    }

    /// NEON/Advanced SIMD de 32 bits — `AESE`/`AESD`/`AESMC`/`AESIMC` (B13.15, ARMv8-A Cryptographic
    /// Extension, `neon-dp.decode` "2-reg-misc" `opc1=0b00`/`opc2` `0110`/`0111`, `size` fixo em
    /// `0b00`, `Q` fixo — sempre 128 bits). Gate: {@link
    /// dev.vitorsilverio.armjitter.arch.ArmFeature#CRYPTO}, SEPARADO de `ADVANCED_SIMD` (um núcleo
    /// pode ter NEON sem a extensão cripto opcional).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.CryptoAes} no ENCODING/IR; a
    /// SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#aes}), RFC B13.2 D1 — migração completa em
    /// B13.15 (o A64 passou a delegar também).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonCryptoAes(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp op,
            /// Registrador de destino (e, para `AESE`/`AESD`, primeiro operando), em índice de `D`
            /// PAR que inicia o `Q` (`0`-`31`).
            int vd,
            /// Registrador fonte, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_CRYPTO_AES; }
    }

    /// NEON/Advanced SIMD de 32 bits — `SHA1H`/`SHA1SU1`/`SHA256SU0` ("Cryptographic two-register
    /// SHA", B13.15, MESMA extensão de {@link NeonCryptoAes}, `neon-dp.decode` "2-reg-misc"
    /// `opc1=0b01`/`opc2=0b0101` ou `opc1=0b10`/`opc2=0b0111`, `size` fixo em `0b10`, `Q` fixo).
    /// Gate: {@link dev.vitorsilverio.armjitter.arch.ArmFeature#CRYPTO}.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.CryptoShaTwoRegister} no
    /// ENCODING/IR; a SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#shaTwoRegister}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NeonCryptoSha(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaOp op,
            /// Registrador de destino, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vd,
            /// Registrador fonte, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vm) implements IrOp {
        @Override public int kind() { return Kind.NEON_CRYPTO_SHA; }
    }

    /// `SG` (Secure Gateway, perfil M, B15.4): entra em estado Secure e limpa o `bit0` de `LR` —
    /// via {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#secureGateway}. Não
    /// muda o PC (a execução continua na instrução seguinte); marcada terminal no lifter mesmo
    /// assim, mesma categoria de {@link Nocp}/`COPROCESSOR` (muda estado observável da CPU).
    record SecureGateway(
            /// Condição necessária para executar (sempre {@link Condition#AL} em Thumb comum).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SECURE_GATEWAY; }
    }

    /// `BXNS`/`BLXNS` (perfil M, B15.4): branch-exchange com troca de estado Secure/Non-secure —
    /// mesmos campos de {@link BranchExchange}, mas despachado para
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#secureBranchExchange}
    /// em vez da troca ARM/THUMB genérica (perfil M não tem estado ARM).
    record SecureBranchExchange(
            /// Registrador que contém o destino (`Rm`) — sempre um registrador real (`BXNS`/
            /// `BLXNS` não têm forma imediata).
            int sourceRegister,
            /// Valor fixo para usar como destino, ou `-1`.
            int sourceValueOverride,
            /// Indica gravação de retorno (`BLXNS`); `MProfileExceptionModel` decide sozinho ONDE
            /// esse retorno vai (LR direto quando não troca de estado, pilha Secure + `LR` mágico
            /// de `FNC_RETURN` quando troca — ver Javadoc do método).
            boolean link,
            /// Endereço de retorno (da instrução seguinte, com `bit0` setado) a usar quando `link`.
            int returnAddress,
            /// Condição necessária para tomar o branch.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SECURE_BRANCH_EXCHANGE; }
    }

    /// `DLS`/`WLS` (perfil M, B15.6, Low Overhead Branch Extension): grava `rn` em `LR` (contador
    /// de loop); `WLS` (`hasSkipBranch=true`) desvia para `target` quando `rn==0` (loop "while",
    /// pode nunca executar) — `DLS` (`hasSkipBranch=false`) nunca desvia, só inicializa `LR`. Ver
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2LowOverheadBranchDecoder} para o achado
    /// sobre por que `LCTP`/`WLSTP`/`DLSTP` (tail-predication) não produzem este `IrOp`.
    record LoopStart(
            /// Registrador cujo valor inicializa o contador de loop (`LR`).
            int rn,
            /// Endereço absoluto de destino quando o branch é tomado (`WLS` com `rn==0`);
            /// irrelevante quando `hasSkipBranch` é `false`.
            int target,
            /// `true` para `WLS` (pode desviar); `false` para `DLS` (nunca desvia).
            boolean hasSkipBranch,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.LOOP_START; }
    }

    /// `LE` (perfil M, B15.6, Low Overhead Branch Extension), forma pura (sem tail-predication —
    /// ver {@link LoopStart}). **Achado medido contra `trans_LE` do QEMU real** (não deduzido do
    /// nome do bit `f`): `forever=true` (`f=1`) desvia INCONDICIONALMENTE para `target` sem tocar
    /// `LR`; `forever=false` decrementa `LR` e desvia de volta só se `LR` (não-assinado) era `> 1`
    /// ANTES do decremento (a checagem ocorre antes de subtrair, não depois).
    record LoopEnd(
            /// Endereço absoluto de destino do desvio (para trás, início do corpo do loop).
            int target,
            /// `true` para a forma "loop-forever" (`f=1`, desvio incondicional, `LR` intocado).
            boolean forever,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.LOOP_END; }
    }

    /// Avanço pós-instrução do `VPR`/`ECI` (perfil M, B16.2, MVE/Helium) — transcrição de
    /// `mve_advance_vpt` via {@link dev.vitorsilverio.armjitter.core.MveVptState#advance}. Emitido
    /// por `StandardIrBuilder#lift` depois de QUALQUER {@code InstructionKind} beatwise (ver
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#isMveBeatwise()}), mesmo padrão
    /// de {@link SetItState} (avanço do `IT`) — sempre com {@link Condition#AL}: o avanço é
    /// INCONDICIONAL (G4), mesmo quando a instrução governada estava totalmente predicada.
    record AdvanceVpt(
            /// Condição necessária para executar — sempre {@link Condition#AL} na prática (o
            /// lifter nunca emite este `IrOp` sob outra condição, mesmo padrão de `SetItState`).
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.ADVANCE_VPT; }
    }

    /// `VPST` (perfil M, B16.2, MVE/Helium, `target/isa-decode/mve.decode`): grava `mask` em
    /// `VPR.MASK01`/`MASK23` via {@link dev.vitorsilverio.armjitter.core.MveVptState#vpstMask}
    /// (o `eci` corrente decide se `MASK01` também é atualizado, ver Javadoc de `vpstMask`).
    record Vpst(
            /// Campo `mask` de 4 bits (`%mask_22_13`, bit 22 ++ bits\[15:13\]).
            int mask,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VPST; }
    }

    /// `VPNOT` (perfil M, B16.2, MVE/Helium): inverte `VPR.P0` nas lanes correspondentes aos beats
    /// já executados (via {@link dev.vitorsilverio.armjitter.core.MveVptState#eciMask} — o mesmo
    /// idioma que `mve_advance_vpt` usa para o "invMask" antes de deslocar `MASK01`/`MASK23`).
    /// Nenhum campo neutro além da condição: o encoding é totalmente fixo (`VPST` com `mask=0`).
    record Vpnot(
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VPNOT; }
    }

    /// `VPSEL` (perfil M, B16.2, MVE/Helium): seleciona lane a lane (byte a byte, `@2op_nosz` —
    /// sem campo `size`, a arquitetura real não distingue largura de elemento aqui) entre `qn` e
    /// `qm` conforme `VPR.P0`, escrevendo em `qd`, mascarado pelo `elementMask` corrente (beat/
    /// tail/ECI) — ver Javadoc do executor para a derivação da semântica exata (não pôde ser
    /// confirmada byte a byte contra `HELPER(mve_vpsel)` do QEMU real nesta rodada de spec, ver
    /// `## Resultado` da task).
    record Vpsel(
            /// `Qd` (`0`-`7` depois de validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Qn`.
            int qn,
            /// `Qm`.
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VPSEL; }
    }

    /// `VMSR`/`VMRS` com `reg=12` (perfil M, B16.2, MVE/Helium): transfere o `VPR` bruto de/para
    /// `armRegister` — MESMO layout de {@link VfpSystemTransfer}, sem o caso especial de aliasing
    /// `APSR_nzcv` (`VPR` não tem equivalente) e sem chamar {@link
    /// dev.vitorsilverio.armjitter.core.MveVptState#advance} (`VMSR_VMRS` nunca é beatwise, ao
    /// contrário de {@link Vpst}/{@link Vpnot}/{@link Vpsel} — o QEMU real não chama
    /// `mve_advance_vpt` aqui).
    record VprTransfer(
            /// `true` para `VMRS` (`VPR` → `armRegister`); `false` para `VMSR` (`armRegister` →
            /// `VPR`).
            boolean read,
            /// Registrador ARM envolvido (`Rt`, nunca `15` — recusado no decode).
            int armRegister,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.VPR_TRANSFER; }
    }

    /// `VLDR_VSTR` (perfil M, B16.3, MVE/Helium, `target/isa-decode/mve.decode`): move os 128 bits
    /// de `Qd` de/para memória, byte a byte, respeitando o `elementMask` corrente (lane mascarada
    /// num load preserva o valor atual do registrador; lane mascarada num store preserva o byte de
    /// memória — Armadilha 5 da task, predicação é por BYTE, não "tudo ou nada"). Beatwise (mesmo
    /// gancho de {@link AdvanceVpt} que {@link Vpst}/{@link Vpnot}/{@link Vpsel} usam — instalado
    /// manualmente por {@code StandardIrBuilder#lift}, já que este `IrOp` chega via o escape hatch
    /// {@code DecodedInstruction#liftedOp}, fora do switch de `InstructionKind`).
    record MveLoadStore(
            /// `Qd` (`0`-`7`, já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Rn` (base, já recusado se `15`, ou `13` com writeback — UNDEF no decode).
            int rn,
            /// Offset com sinal, JÁ escalado pelo tamanho do elemento (`imm7 << size`, nunca `<< 2`
            /// fixo — Armadilha 2 da task).
            int offset,
            /// `true` para `VLDR` (memória → `Qd`); `false` para `VSTR` (`Qd` → memória).
            boolean load,
            /// `true` quando `Rn` recebe o endereço pós-offset (writeback SEMPRE incondicional —
            /// G4, nunca predicado por `elementMask`, mesmo com a instrução totalmente mascarada).
            boolean writeback,
            /// `true` para pós-index (endereço de acesso = `Rn` ANTES do offset; `P=0`, `W`
            /// forçado); `false` para pré-index/offset (endereço de acesso = `Rn ± offset`; `P=1`).
            boolean postIndexed,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_LOAD_STORE; }
    }

    /// `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (perfil M, B16.4, MVE/Helium, `target/isa-decode/mve.decode`):
    /// load que ALARGA (lê `1 << memorySizeLog2` bytes da memória, estende para
    /// `1 << registerSizeLog2` bytes na lane de `Qd`) ou store que ESTREITA (trunca cada lane de
    /// `Qd` para `1 << memorySizeLog2` bytes na memória). Predicação por ELEMENTO (não por byte,
    /// diferente de {@link MveLoadStore}): a máscara é indexada em passos de
    /// `1 << registerSizeLog2` (o tamanho do REGISTRADOR — verbatim de `DO_VLDR`/`DO_VSTR`,
    /// `target/arm/tcg/mve_helper.c`, `for (b = 0, e = 0; b < 16; b += ESIZE, e++)` com
    /// `ESIZE` = tamanho do registrador), enquanto o ENDEREÇO avança em passos de
    /// `1 << memorySizeLog2` (`addr += MSIZE`). Beatwise (mesmo gancho de {@link AdvanceVpt} que
    /// {@link MveLoadStore} usa).
    ///
    /// No load, um elemento cujo beat já foi abandonado (bit de `eciMask` desligado) não é tocado
    /// (comportamento UNKNOWN permitido pelo hardware real, "R_SXTM" — implementado como
    /// preservar o valor atual da lane); um elemento cujo beat está ativo mas falha o predicado de
    /// `VPT` (bit de `eciMask` ligado, bit da máscara cheia desligado) grava ZERO na lane — as duas
    /// máscaras são DISTINTAS aqui (diferente de {@link MveLoadStore}, que usa uma única máscara
    /// fundida). No store, só a máscara cheia importa: elemento fora dela não é escrito na memória.
    record MveWideningLoadStore(
            /// `Qd` (`0`-`7` por construção — `@vldst_wn` extrai só 3 bits, "no D bit").
            int qd,
            /// `Rn` (base, `0`-`7` por construção — nunca `13`/`15`, checagem da B16.3 vacuamente
            /// satisfeita aqui, ver Armadilha 1/item 1 da task).
            int rn,
            /// Offset com sinal, JÁ escalado pelo tamanho em MEMÓRIA (`imm7 << memorySizeLog2` —
            /// achado medido contra `do_ldst` real: é o `msize` da macro `DO_VLDST_WIDE_NARROW`
            /// que escala, não o tamanho do registrador).
            int offset,
            /// Log2 do tamanho do elemento NA MEMÓRIA (`0`=byte, `1`=halfword).
            int memorySizeLog2,
            /// Log2 do tamanho do elemento NO REGISTRADOR (`1`=halfword, `2`=word) — sempre maior
            /// que {@link #memorySizeLog2} (é sempre um alargamento/estreitamento real).
            int registerSizeLog2,
            /// `true` para `VLDR*` (memória → `Qd`, com extensão); `false` para `VSTR*` (`Qd` →
            /// memória, com truncamento). Só `load` pode ter {@link #signed} `false` (`u=1`); um
            /// store sempre tem `U=0` (recusado no decode, campo aqui é sempre irrelevante).
            boolean load,
            /// `true` = estende com SINAL (`u=0`); `false` = estende com ZERO (`u=1`). Ignorado
            /// quando {@link #load} é `false` (store trunca, não estende).
            boolean signed,
            /// `true` quando `Rn` recebe o endereço pós-offset (writeback SEMPRE incondicional —
            /// G4, mesma regra de {@link MveLoadStore}).
            boolean writeback,
            /// `true` para pós-index (`P=0`, `W` forçado); `false` para pré-index/offset (`P=1`).
            boolean postIndexed,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_WIDENING_LOAD_STORE; }
    }

    /// `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (perfil M, B16.5, MVE/Helium, `target/isa-decode/mve.decode`):
    /// gather load / scatter store por vetor de OFFSETS em `qm` — cada lane de `qm` (largura
    /// {@link #registerSizeLog2}) é somada a `Rn` (mais o escalonamento de {@link #offsetScaled},
    /// pelo tamanho em MEMÓRIA) para formar um endereço INDEPENDENTE por lane (verbatim de
    /// `DO_VLDR_SG`/`DO_VSTR_SG`, `target/arm/tcg/mve_helper.c`). Duas máscaras distintas no load
    /// (mesmo padrão de {@link MveWideningLoadStore}): `eciMask` decide se a lane é tocada (beat
    /// abandonado preserva); `elementMask` decide entre carregar de verdade ou gravar ZERO. No
    /// store só `elementMask` importa. **Sem writeback** (`@vldst_sg` não tem campo `w`).
    record MveGatherScatterOffset(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`), o vetor de offsets — recusado no decode quando `Qd == Qm`
            /// (UNPREDICTABLE real: o registrador de offsets seria sobrescrito no meio da operação).
            int qm,
            /// `Rn` (base escalar; `15` recusado no decode — UNPREDICTABLE).
            int rn,
            /// Log2 do tamanho do elemento NA MEMÓRIA (`msize`, `0`-`3`).
            int memorySizeLog2,
            /// Log2 do tamanho do elemento NO REGISTRADOR (`size`, `0`-`3`) — também a largura em
            /// que `qm` é lido lane a lane. `3` (doubleword) usa o par de acessos de 32 bits
            /// verbatim de `DO_VLDR64_SG`/`DO_VSTR64_SG` (offset lido só das lanes PARES de `qm`).
            int registerSizeLog2,
            /// `true` estende com SINAL (`VLDR_S_sg`); `false` estende com ZERO (`VLDR_U_sg`/
            /// `VSTR_sg`, campo irrelevante no store).
            boolean signedLoad,
            /// `os`: `true` escala o offset lido de `qm` por `1 << memorySizeLog2` antes de somar a
            /// `Rn` (`ADDR_ADD_OSH`/`OSW`/`OSD`); `false` soma sem escalar (`ADDR_ADD`).
            boolean offsetScaled,
            /// `true` para `VLDR_S_sg`/`VLDR_U_sg` (memória → `Qd`); `false` para `VSTR_sg`.
            boolean load,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_GATHER_SCATTER_OFFSET; }
    }

    /// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (perfil M, B16.5, MVE/Helium):
    /// gather/scatter com base VETORIAL (`qm`, cada lane já contém um ENDEREÇO completo — não um
    /// offset) mais um imediato ESCALAR somado a todas as lanes (`do_ldst_sg_imm` real: os
    /// parâmetros "base"/"offset" trocam de papel em relação a {@link MveGatherScatterOffset}, mas
    /// a fórmula de endereço é a MESMA soma). **`Qm` vem do campo normalmente rotulado `Qn`**
    /// (`@vldst_sg_imm qm=%qn` — comentário literal do arquivo, Armadilha 1 da task). Writeback é
    /// POR LANE (`w=1`: cada lane de `qm` recebe seu próprio endereço calculado, não um único `Rn`
    /// escalar) e roda sempre que a lane está ativa por `ECI` (independente do `elementMask` de
    /// `VPT` — verbatim do `if (WB) { m[e] = addr; }` dentro do `if (eci_mask)` de `DO_VLDR_SG`).
    record MveGatherScatterImmediate(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, extraído via `%qn` — Armadilha 1), o vetor de endereços base.
            int qm,
            /// Offset com sinal (`imm7 << sizeLog2`, `a=0` nega — mesma convenção de
            /// {@link MveLoadStore#offset}).
            int offset,
            /// Log2 do tamanho do elemento (`2`=`W`, `3`=`D` — memória e registrador SEMPRE do
            /// mesmo tamanho aqui, sem alargamento).
            int sizeLog2,
            /// `true` quando cada lane ativa de `qm` recebe de volta seu endereço calculado.
            boolean writeback,
            /// `true` para `VLDRW_sg_imm`/`VLDRD_sg_imm`; `false` para `VSTRW_sg_imm`/`VSTRD_sg_imm`.
            boolean load,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_GATHER_SCATTER_IMMEDIATE; }
    }

    /// `VLD2`/`VLD4`/`VST2`/`VST4` (perfil M, B16.5, MVE/Helium): desentrelaçamento/entrelaçamento
    /// de um grupo de {@link #groupSize} registradores `Q` consecutivos a partir de `Qd`, em 4
    /// "beats" de 32 bits cada (verbatim de `DO_VLD2*`/`DO_VLD4*`/`DO_VST2*`/`DO_VST4*`,
    /// `target/arm/tcg/mve_helper.c` — tabelas `off[]` por {@link #pat} transcritas em
    /// `IrSystemExecutor`). **Beatwise mas NÃO predicado** (comentário literal do QEMU real): só
    /// `eciMask` gate cada beat, `elementMask`/`VPT` nunca é consultado — por isso usa
    /// {@link AdvanceEci} (não {@link AdvanceVpt}) como gancho pós-instrução, já que
    /// `mve_update_and_store_eci` NUNCA toca `VPR.MASK01`/`MASK23`, ao contrário de
    /// `mve_advance_vpt`. Writeback (quando presente) é incondicional, soma `groupSize * 16` bytes
    /// a `Rn` (`do_vldst_il` real: `addrinc` = `32` para grupo 2, `64` para grupo 4).
    record MveInterleavedLoadStore(
            /// `Qd`, primeiro registrador do grupo (`VLD2`/`VST2`: `Qd <= 6`; `VLD4`/`VST4`:
            /// `Qd <= 4` — recusado no decode senão, `Qd+groupSize-1` estouraria `Q7`).
            int qd,
            /// `Rn` (base; `15` sempre recusado, `13` recusado quando {@link #writeback}).
            int rn,
            /// `2` (`VLD2`/`VST2`) ou `4` (`VLD4`/`VST4`) — quantos `Q` consecutivos o grupo cobre.
            int groupSize,
            /// Log2 do tamanho do elemento (`0`=byte, `1`=halfword, `2`=word).
            int sizeLog2,
            /// `pat` (`0`-`3`): qual "fatia" do grupo esta instrução move — a arquitetura real
            /// decompõe um `VLD4.8 {Qd-Qd+3}` em 4 instruções `VLD4` consecutivas, uma por `pat`,
            /// cada uma cobrindo 4 estruturas via `off[]` (Armadilha 6 da task: `pat` não é "qual
            /// registrador").
            int pat,
            /// `true` para `VLD2`/`VLD4` (memória → grupo); `false` para `VST2`/`VST4`.
            boolean load,
            /// `true` quando `Rn` recebe o endereço pós-incremento (incondicional — G4).
            boolean writeback,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_INTERLEAVED_LOAD_STORE; }
    }

    /// `VIDUP`/`VDDUP` (perfil M, B16.5, MVE/Helium): preenche `Qd` com `Rn, Rn+passo, Rn+2·passo,
    /// ...` (verbatim de `DO_VIDUP`, `target/arm/tcg/mve_helper.c`) e escreve de volta em `Rn` o
    /// valor de continuação (SEM truncar ao tamanho do elemento — só a gravação em `Qd` trunca,
    /// `Rn` acumula em 32 bits cheios para sempre). `VDDUP` é `VIDUP` com {@link #imm} já NEGADO
    /// pelo decoder (`a->imm = -a->imm`, `trans_VDDUP` real) — mesmo `IrOp`, sem campo de direção.
    /// Predicado por `elementMask` (lane mascarada preserva o valor atual de `Qd`, via
    /// `mergemask`); beatwise ({@link AdvanceVpt}, a própria `HELPER` chama `mve_advance_vpt`).
    record MveIncrementDup(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Rn` (sempre PAR por construção do encoding — `%vidup_rn`, nunca `13`/`15`).
            int rn,
            /// Log2 do tamanho do elemento (`0`-`2`; `size==3` é "outro encoding", recusado no
            /// decode).
            int sizeLog2,
            /// Passo somado a cada lane sucessiva — já com o sinal aplicado (`VDDUP` chega aqui com
            /// `imm` negativo).
            int imm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_INCREMENT_DUP; }
    }

    /// `VIWDUP`/`VDWDUP` (perfil M, B16.5, MVE/Helium): como {@link MveIncrementDup}, mas o
    /// contador ENVOLVE (wrap) em `Rm` — `VIWDUP`: `offset+=imm; if (offset==Rm) offset=0`
    /// (`do_add_wrap` real); `VDWDUP`: `if (offset==0) offset=Rm; offset-=imm` (`do_sub_wrap`
    /// real) — comportamentos DIFERENTES o suficiente para não caberem no mesmo `imm` com sinal
    /// trocado (ao contrário de {@link MveIncrementDup}, por isso um campo {@link #decrement}
    /// explícito em vez de negar `imm`).
    record MveWrappingIncrementDup(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Rn` (sempre PAR por construção — `%vidup_rn`).
            int rn,
            /// `Rm` (sempre ÍMPAR por construção — `%vidup_rm`; `13`/`15` recusados no decode —
            /// UNPREDICTABLE).
            int rm,
            /// Log2 do tamanho do elemento (`0`-`2`; `size==3` recusado no decode).
            int sizeLog2,
            /// Passo (sempre não-negativo — a direção vem de {@link #decrement}, não do sinal).
            int imm,
            /// `true` para `VDWDUP` (`do_sub_wrap`); `false` para `VIWDUP` (`do_add_wrap`).
            boolean decrement,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_WRAPPING_INCREMENT_DUP; }
    }

    /// Avanço pós-instrução SÓ do `ECI` (perfil M, B16.5, MVE/Helium) — `mve_update_and_store_eci`
    /// verbatim: cicla o nibble de `ECI` exatamente como
    /// {@link dev.vitorsilverio.armjitter.core.MveVptState#advance} faz, mas
    /// **nunca** toca `VPR.MASK01`/`MASK23`/`P0` (ao contrário de {@link AdvanceVpt}). Usado só por
    /// {@link MveInterleavedLoadStore} (`VLD2`/`VLD4`/`VST2`/`VST4`) — instruções "beatwise mas não
    /// predicadas" que não participam da máquina `VPT` (ver Javadoc de {@link MveInterleavedLoadStore}).
    record AdvanceEci(
            /// Condição necessária para executar — sempre {@link Condition#AL} na prática, mesmo
            /// padrão de {@link AdvanceVpt}.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.ADVANCE_ECI; }
    }

    /// Vector 2-op inteiro (perfil M, B16.6, MVE/Helium, `target/isa-decode/mve.decode`, seção
    /// "Vector 2-op"): `Qd[i] = op(Qn[i], Qm[i])` para cada lane de `1 << esz` bytes de `Q0`-`Q7`,
    /// PREDICADO por elemento — diferente de {@link dev.vitorsilverio.armjitter.ir.IrOp.NeonThreeSame}
    /// (NEON de 32 bits, sem predicação), reusa o MESMO {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} (RFC B13.2 D1) via {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameMasked}. Cobre as 34 linhas
    /// (lógica/aritmética base, min/max/abd/halving/saturantes/deslocamento por vetor, `VRHADD`) que
    /// já existem no núcleo compartilhado — ver `## Resultado` da task para o inventário completo.
    /// Beatwise (gancho manual de {@link AdvanceVpt} em `StandardIrBuilder`, mesmo padrão de
    /// {@link MveLoadStore} desde a B16.3 — chega via o escape hatch {@code
    /// DecodedInstruction#liftedOp}).
    record MveVector2Op(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` é recusado no decoder, MVE não
            /// tem elemento de 64 bits nesta forma).
            int esz,
            /// `Qd` (`0`-`7`, já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Qn` — na forma `@2op_rev` (deslocamento por vetor) já vem TROCADO com `Qm` pelo
            /// decoder (achado da task: "Vn e Vm invertidos de propósito" no `mve.decode` real).
            int qn,
            /// `Qm` — ver {@link #qn}.
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_2OP; }
    }

    /// `VMULLP_B`/`VMULLP_T` (polinomial) e `VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (inteiro,
    /// perfil M, B16.6, MVE/Helium): ALARGA — lê `8 >> esz` elementos de `1 << esz` bytes de `Qn`/
    /// `Qm` e escreve `Qd` INTEIRO com elementos de `1 << (esz+1)` bytes (dobro da largura) — reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wideningInterleavedMasked}.
    /// **Achado real que corrige a suposição inicial**: {@link #top} `false`/`true` (`_B`/`_T`) NÃO
    /// seleciona metade contígua baixa/alta (padrão `SMULL2`/`UMULL2` do A64) — seleciona lanes
    /// PARES/ÍMPARES intercaladas da fonte (`le*2 + top`, verbatim de `DO_2OP_L`,
    /// `target/arm/tcg/mve_helper.c`, confirmado via `WebFetch`). `VMULLP_*` usa {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#PMULL} — o núcleo precisou generalizar
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#polynomialMultiply8} para largura
    /// VARIÁVEL (`esz` `0`/`1`, byte/halfword — QEMU nomeia as duas formas `vmullpbh`/`vmullpbw`:
    /// fonte BYTE produz resultado HALFWORD, fonte HALFWORD produz resultado WORD; MVE não tem forma
    /// de fonte WORD para `VMULLP`, ao contrário do `VMULL` inteiro), ver
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#polynomialMultiply}. Beatwise (mesmo
    /// gancho de {@link MveVector2Op}), predicado por byte na granularidade da lane LARGA
    /// (`mergemask` real também se aplica a esta forma — outro achado que corrige a suposição
    /// inicial de que só `threeSame` precisaria de máscara).
    record MveVector2OpWidening(
            /// Operação alargante a executar — só {@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#SMULL}/{@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#UMULL}/{@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#PMULL} nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento FONTE (`Qn`/`Qm`) em bytes — `0`/`1` (byte/halfword)
            /// para `VMULLP_*` (`%size_28` decodifica `bit28+1` em `1`/`2`; o esz FONTE real é
            /// `bit28` diretamente — ver Javadoc da classe); `0`-`2` para `VMULL_*S`/`VMULL_*U`
            /// (`3` recusado no decoder).
            int esz,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES) —
            /// discriminado por `bit12` no encoding real. Ver Javadoc da classe (não é metade
            /// contígua).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (fonte, `0`-`7`).
            int qn,
            /// `Qm` (fonte, `0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_2OP_WIDENING; }
    }

    /// `VADC`/`VADCI`/`VSBC`/`VSBCI` (perfil M, B16.6, MVE/Helium): soma/subtração com CARRY
    /// encadeado por `FPSCR.C` (não `APSR`/`CPSR`) através dos 4 elementos de 32 bits de `Qn`/`Qm`
    /// (ESIZE fixo em 4 — `size` do encoding é decorativo, sempre `0` nesta forma `@2op_nosz`),
    /// verbatim de `do_vadc`/`HELPER(mve_vadc)`/`HELPER(mve_vadci)`/`HELPER(mve_vsbc)`/
    /// `HELPER(mve_vsbci)` (`target/arm/tcg/mve_helper.c`): o carry de SAÍDA de um elemento vira o
    /// carry de ENTRADA do elemento seguinte, DENTRO da mesma instrução — só elementos ATIVOS
    /// (bit de `elementMask`) atualizam a cadeia; ao final, `FPSCR.C` recebe o carry final sempre
    /// que ALGUM elemento estava ativo (`mask & 0x1111`, verificado mesmo quando `updateFlags` seria
    /// `false` pelo chamador — achado real do QEMU: a checagem FORÇA `true`). Formas `I`
    /// (`VADCI`/`VSBCI`) ignoram o `FPSCR.C` de ENTRADA (`VADCI` usa `0`; `VSBCI` usa `1`, convenção
    /// SBC padrão de "sem empréstimo"), mas ainda ESCREVEM o carry de saída. `VSBC`/`VSBCI` invertem
    /// `Qm` bit a bit antes de somar (`n + ~m + carry_in`, complemento de dois). Beatwise (mesmo
    /// gancho de {@link MveVector2Op}).
    record MveVectorCarry(
            /// `true` para `VADC`/`VADCI` (`Qm` não invertido); `false` para `VSBC`/`VSBCI`
            /// (`Qm` invertido bit a bit).
            boolean add,
            /// `true` para as formas `I` (`VADCI`/`VSBCI` — carry de entrada IGNORADO, `0`/`1` fixo
            /// conforme {@link #add}); `false` para `VADC`/`VSBC` (carry de entrada = `FPSCR.C`
            /// atual).
            boolean immediateCarry,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_CARRY; }
    }

    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (perfil M, B16.6, MVE/Helium): soma complexa
    /// INTEIRA entre lanes ADJACENTES de `Qn`/`Qm` — verbatim de `DO_VCADD`/`DO_VCADD_ALL`
    /// (`target/arm/tcg/mve_helper.c`): para cada par `(2i, 2i+1)`, a lane PAR do destino combina
    /// `Qn[2i]` com `Qm[2i+1]` e a lane ÍMPAR combina `Qn[2i+1]` com `Qm[2i]` — o SINAL da combinação
    /// (soma/subtração) e QUAL lane usa qual sinal dependem de {@link #rotate90}: `90` faz
    /// PAR=SUBTRAI/ÍMPAR=SOMA, `270` faz PAR=SOMA/ÍMPAR=SUBTRAI (`DO_VCADD_ALL(vcadd90, DO_SUB,
    /// DO_ADD)`/`DO_VCADD_ALL(vcadd270, DO_ADD, DO_SUB)` reais). {@link #halving} (`VHCADD*`) troca
    /// soma/subtração planas por {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#SHADD}/
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#SHSUB} (SEMPRE assinado — não
    /// existe forma `VCADD`/`VHCADD` não assinada, diferente de {@link MveVector2Op}). **Não
    /// confundir com `VCADD90_fp`/`VCADD270_fp` (B16.7, encodings DISTINTOS, núcleo FP separado
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd} — Armadilha 4 da
    /// task).** Beatwise (mesmo gancho de {@link MveVector2Op}).
    record MveVectorComplexAdd(
            /// `true` para `VHCADD90`/`VCADD90`; `false` para `VHCADD270`/`VCADD270` — ver Javadoc
            /// da classe para qual lane (par/ímpar) soma ou subtrai em cada caso.
            boolean rotate90,
            /// `true` para `VHCADD90`/`VHCADD270` (halving, sempre assinado); `false` para
            /// `VCADD90`/`VCADD270` (soma/subtração plana, sem halving).
            boolean halving,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPLEX_ADD; }
    }

    /// `VMAXA`/`VMINA` (perfil M, B16.7, MVE/Helium, `target/isa-decode/mve.decode` `@1op`, verbatim
    /// de `DO_VMAXMINA`, `target/arm/tcg/mve_helper.c`): `Qd[i] = max/min(Qd[i], |sext(Qm[i])|)`
    /// — comparação NÃO ASSINADA (`Qd` é `unsigned`, `Qm` é `signed` e seu valor absoluto é tomado
    /// primeiro; ver Javadoc de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#absAccumulateMasked}).
    /// `Qd` é FONTE e DESTINO ao mesmo tempo — não tem `Qn` separado. Beatwise (mesmo gancho de
    /// {@link MveVector2Op}). Nunca satura.
    record MveVectorAbsAccumulate(
            /// `true` para `VMAXA`; `false` para `VMINA`.
            boolean max,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ABS_ACCUMULATE; }
    }

    /// `VMAXNMA`/`VMINNMA` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode`
    /// `@vmaxnma` — "Qd and Qn share a field"): `Qd[i] = maxNum/minNum(|Qd[i]|, |Qm[i]|)`, ponto
    /// flutuante, verbatim de `DO_2OP_FP` instanciado com `float16_maxnuma`/`minnuma`/
    /// `float32_maxnuma`/`minnuma` (`target/arm/tcg/mve_helper.c`). `Qd` é FONTE e DESTINO. Beatwise
    /// (mesmo gancho de {@link MveVector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações
    /// FP MVE nesta task).
    record MveVectorFpAbsAccumulate(
            /// `true` para `VMAXNMA`; `false` para `VMINNMA`.
            boolean max,
            /// `1` = binary16, `2` = binary32 (campo `size` do encoding real, literal por bloco —
            /// nunca `0`/`3`).
            int esz,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_ABS_ACCUMULATE; }
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T2** (`shift == esize`, perfil M, B16.7,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2_shll_esize_b`/`@2_shll_esize_h` — comentário do
    /// arquivo real: "not a @2op pattern, but is here because it overlaps what would be size=0b11
    /// VMULH/VRMULH"): ALARGA — lê `8 >> esz` elementos de `1 << esz` bytes da lane INTERCALADA
    /// `le*2 + (top?1:0)` de `Qm` (mesmo padrão de {@link MveVector2OpWidening}) e escreve `Qd`
    /// INTEIRO com elementos de `1 << (esz+1)` bytes (dobro da largura), sinal/zero-estendidos e
    /// deslocados à esquerda por `esize` bits. **Não confundir com `VSHLL` forma T1 (B16.10,
    /// encoding DISTINTO com o MESMO mnemônico).** Beatwise (mesmo gancho de {@link MveVector2Op}).
    /// Nunca satura.
    record MveVectorShiftWidenInterleaved(
            /// `true` = sinal-estende (`VSHLL_*S`); `false` = zero-estende (`VSHLL_*U`).
            boolean signed,
            /// `log2` do tamanho do elemento FONTE em bytes — `0`(byte, `shift=8`) ou `1`(halfword,
            /// `shift=16`); a T2 nunca tem fonte WORD.
            int esz,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED; }
    }

    /// `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M, B16.7, MVE/Helium,
    /// `target/isa-decode/mve.decode` `@1op`, verbatim de `DO_VMOVN`/`DO_VMOVN_SAT`,
    /// `target/arm/tcg/mve_helper.c`): ESTREITA — lê `8 >> esz` elementos de `1 << (esz+1)` bytes de
    /// `Qm` e escreve a lane ESTREITA INTERCALADA `le*2 + (top?1:0)` de `Qd` (oposto de
    /// {@link MveVector2OpWidening}: aqui a intercalação é no DESTINO). Reusa {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp} (mesmo mapeamento do A64/NEON:
    /// `XTN`=`VMOVN`, `SQXTN`=`VQMOVN_*S`, `SQXTUN`=`VQMOVUN*`, `UQXTN`=`VQMOVN_*U`). Beatwise (mesmo
    /// gancho de {@link MveVector2Op}). `FPSCR.QC` só para as 3 formas saturantes.
    record MveVectorNarrowInterleaved(
            /// Operação de estreitamento a executar.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp op,
            /// `log2` do tamanho do elemento ESTREITO (saída) em bytes — `0`(byte) ou `1`(halfword);
            /// nunca `2`/`3` (MVE não tem forma de saída WORD aqui).
            int esz,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_NARROW_INTERLEAVED; }
    }

    /// `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`,
    /// `target/isa-decode/mve.decode` `@1op_nosz`, verbatim de `do_vcvt_sh`/`do_vcvt_hs`,
    /// `target/arm/tcg/mve_helper.c`): conversão binary16↔binary32 "bottom"/"top" — não confundir
    /// com `VCVT` fp↔int/ponto-fixo (B16.12, encodings distintos). {@link #widen} `true` (`_HS`):
    /// lê `Qm` INTERCALADO (halfword, lane `i*2+top`), escreve `Qd` word (lane `i`, `0`-`3`).
    /// {@link #widen} `false` (`_SH`): lê `Qm` word (lane `i`), escreve `Qd` INTERCALADO (halfword,
    /// lane `i*2+top`). Beatwise (mesmo gancho de {@link MveVector2Op}). Nunca satura.
    record MveVectorFpConvertPrecision(
            /// `true` = half→single (`_HS`, ALARGANDO); `false` = single→half (`_SH`, ESTREITANDO).
            boolean widen,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT_PRECISION; }
    }

    /// `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` (perfil M, B16.7 sub-família 2, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_sz28` — "note that in this format bit 28
    /// is size, not U"): delega a {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyMasked}
    /// — ver Javadoc de lá para a diferença real com `VCMLA`/`FCMLA`. Beatwise (mesmo gancho de
    /// {@link MveVector2Op}). Nunca satura.
    record MveVectorFpComplexMultiply(
            /// `0`=`VCMUL0`, `1`=`VCMUL90`, `2`=`VCMUL180`, `3`=`VCMUL270` — `(bit16<<1)|bit0` no
            /// encoding real, mesma convenção `ROT` de `DO_VCMLA`.
            int rotation,
            /// `1` = binary16, `2` = binary32 (`%size_28`, `bit28+1`; nunca `0`/`3`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY; }
    }

    /// `VQDMLADH`/`VQDMLSDH` e variantes `X` (exchange)/`R` (rounded) (perfil M, B16.7 sub-família 2,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2op` — o `{}` sobreposto com `VCMUL*` onde
    /// `bits[21:20]` é `size` real, não o literal `11` que `VCMUL*` reivindica): delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dualMultiplyAddHighMasked} — ver
    /// Javadoc de lá para o achado real de que só METADE das lanes é escrita por instância. `size`
    /// (`bits[21:20]`) é real (`0`-`2`; `3` reservado para `VCMUL*` pela prioridade textual do `{}`).
    /// Beatwise (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` só quando alguma lane ATIVA
    /// (da metade escrita) satura.
    record MveVectorDualMultiplyAddHigh(
            /// `true` para `VQDMLADH*` (soma os dois produtos); `false` para `VQDMLSDH*` (subtrai) —
            /// `bit28` (`U`) no encoding real.
            boolean add,
            /// `true` para as formas `X` (exchange, escreve lanes ÍMPARES); `false` para as formas
            /// sem sufixo (escreve lanes PARES) — `bit16` no encoding real.
            boolean exchange,
            /// `true` para as formas `R` (rounded, `VQRDMLADH*`/`VQRDMLSDH*`); `false` para as sem
            /// `R` — `bit0` no encoding real.
            boolean rounded,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` reservado, ver Javadoc da classe).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH; }
    }

    /// `VQDMULLB`/`VQDMULLT` (perfil M, B16.7 sub-família 2, MVE/Helium, `target/isa-decode/mve.decode`
    /// `@2op_sz28`, verbatim de `DO_2OP_SAT_L`/`do_qdmullh`/`do_qdmullw`, `target/arm/tcg/mve_helper.c`):
    /// ALARGA saturando — delega a {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingWideningInterleavedMasked}
    /// (mesmo padrão de indexação intercalada `le*2+top` de {@link MveVector2OpWidening}). Beatwise
    /// (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` quando alguma lane ATIVA satura — a
    /// PRIMEIRA forma alargante intercalada que satura (diferente de {@link MveVector2OpWidening},
    /// B16.6, que nunca satura).
    record MveVectorDoublingWideningMultiply(
            /// `log2` do tamanho do elemento FONTE em bytes — `1`(halfword) ou `2`(word), `%size_28`
            /// (`bit28+1`).
            int esz,
            /// `true` para a forma `T` (lanes ÍMPARES da fonte); `false` para `B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`, fonte).
            int qn,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY; }
    }

    /// `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/`VFMS` (perfil M, B16.7
    /// sub-família 3, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_fp`, seção
    /// "2-operand FP"): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameMasked}) — a MESMA função de
    /// operação ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp}) que
    /// `NeonFpThreeSame`/A64 já usam via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSame}
    /// para o caminho NÃO predicado (RFC B13.2 D1) — zero código novo de aritmética, só o gancho
    /// PREDICADO. `VFMA`/`VFMS` reusam {@code FMLA}/{@code FMLS} (FUNDIDO, um arredondamento —
    /// `DO_VFMA`/`mve_helper.c` real). `size` (`%2op_fp_size`, bit20 DIRETO: `1`=binary16,
    /// `0`=binary32 — convenção NEON FP, não confundir com {@link MveVectorFpComplexAdd}/
    /// {@link MveVectorFpComplexMultiplyAccumulate} abaixo, que usam a forma REVERSA). Beatwise
    /// (mesmo gancho de {@link MveVector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações FP
    /// MVE, mesmo precedente de {@link MveVectorFpAbsAccumulate}/{@link MveVectorFpConvertPrecision}).
    record MveVectorFpTwoOp(
            /// Operação a executar (núcleo compartilhado) — só `ADD`/`SUB`/`MUL`/`ABD`/`MAXNM`/
            /// `MINNM`/`FMLA`/`FMLS` nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size`, bit20 DIRETO).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_TWO_OP; }
    }

    /// `VCADD90_fp`/`VCADD270_fp` (perfil M, B16.7 sub-família 3, MVE/Helium, `FEAT_MVE_FP`,
    /// `target/isa-decode/mve.decode` `@2op_fp_size_rev` — "VCADD is an exception, where bit 20 is 0
    /// for 16 bit and 1 for 32 bit"): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAddMasked}) — MESMA fórmula
    /// `FComplexAddImpl` de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd}
    /// (`FEAT_FCMA`/NEON), só que PREDICADA por par (real/imaginário). **Não confundir com
    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` INTEIROS (B16.6, {@link MveVectorComplexAdd},
    /// encodings DISTINTOS, núcleo separado).** Beatwise (mesmo gancho de {@link MveVector2Op}).
    /// Nunca satura.
    record MveVectorFpComplexAdd(
            /// `true` para `VCADD90_fp` (rotação `90°`); `false` para `VCADD270_fp` (`270°`).
            boolean rotate90,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size_rev`, `bit20+1` — forma REVERSA, ver
            /// Javadoc da classe).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_ADD; }
    }

    /// `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (perfil M, B16.7 sub-família 3, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_fp_size_rev`): delega ao núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulateMasked})
    /// — MESMA fórmula `FComplexMulAdd` de {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate} (`FEAT_FCMA`/
    /// NEON `VCMLA`/`FCMLA`), só que PREDICADA por par. **Não confundir com `VCMUL0`/`VCMUL90`/
    /// `VCMUL180`/`VCMUL270` (B16.7 sub-família 2, {@link MveVectorFpComplexMultiply}, "real ×
    /// complexo" sem acumular — encodings e núcleo DISTINTOS, achado da sub-família 2).** Beatwise
    /// (mesmo gancho de {@link MveVector2Op}). Nunca satura.
    record MveVectorFpComplexMultiplyAccumulate(
            /// `0`/`90`/`180`/`270` — ver a tabela de contribuição de cada rotação no Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate}.
            int rotation,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size_rev`, `bit20+1`).
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE; }
    }

    /// `VCMPEQ`/`VCMPNE`/`VCMPGE`/`VCMPLT`/`VCMPGT`/`VCMPLE`/`VCMPCS`/`VCMPHI` e as 6 formas `_fp`
    /// correspondentes (perfil M, B16.8, MVE/Helium, `target/isa-decode/mve.decode`, seção
    /// "Comparisons", vetor×vetor): compara `Qn`/`Qm` lane a lane e escreve o resultado em
    /// `VPR.P0` (**não** um registrador vetorial — diferente de `CMEQ`/`CMGT` do NEON/A64,
    /// Armadilha 2 da task), replicando o bit de cada lane pelos `1 << esz` bytes do elemento
    /// (verbatim de `DO_VCMP`, `target/arm/tcg/mve_helper.c`: "Comparison sets 0/1 bits for each
    /// byte in the element"). Bits de `P0` para beats AINDA NÃO executados (`eciMask == 0`)
    /// ficam INTOCADOS; para lanes predicadas-fora em beats executados (`elementMask == 0`)
    /// ficam ZERADOS; do contrário recebem o resultado da comparação (comentário literal do QEMU
    /// real, citado verbatim no Javadoc do executor). Quando `mask` (`%mask_22_13`) é diferente de
    /// zero, a instrução é uma `VPT` (`VCMP` seguida de `VPST`, achado da B16.2) — o
    /// `StandardIrBuilder` emite um {@link Vpst} adicional LOGO APÓS o {@link AdvanceVpt} desta
    /// instrução, reproduzindo a ordem real do QEMU (`do_vcmp`: helper que já chama
    /// `mve_advance_vpt` interno, DEPOIS `gen_vpst` se `a->mask`). Beatwise ({@link AdvanceVpt}
    /// sempre roda depois) e TERMINAL (`DISAS_UPDATE_NOCHAIN` real — ver
    /// {@link dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter}).
    record MveVectorCompare(
            /// Condição de comparação (`EQ`/`NE`/`GE`/`LT`/`GT`/`LE`/`CS`/`HI`) — `CS`/`HI` só
            /// existem nas formas inteiras, nunca em {@link #floatingPoint}.
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition compareCondition,
            /// `true` para as formas `_fp` (`@vcmp_fp`); `false` para as inteiras (`@vcmp`).
            boolean floatingPoint,
            /// Tamanho do elemento: inteiras `0`/`1`/`2` (byte/halfword/word, extraído do campo
            /// `size` — `size==3` já recusado no decode); FP `1`/`2` (binary16/binary32, bit 28,
            /// `%2op_fp_scalar_size`, MESMA convenção `neon_3same_fp_size` de
            /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2MveVector2opFpDecoder}).
            int esz,
            /// `Qn` (campo inline de 3 bits em `@vcmp`/`@vcmp_fp` — sempre `0`-`7`, nunca precisa
            /// de validação de faixa).
            int qn,
            /// `Qm` (`%qm`, 4 bits — já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qm,
            /// Campo `mask` de 4 bits (`%mask_22_13`) — `VCMP` pura tem `mask=0`; `!= 0` estabelece
            /// `VPT` (ver Javadoc da classe).
            int mask,
            /// Condição ARM necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPARE; }
    }

    /// Forma escalar (vetor × GPR broadcast) das mesmas 8 condições de {@link MveVectorCompare}
    /// (`@vcmp_scalar`/`@vcmp_fp_scalar`) — MESMA semântica de escrita em `P0`, comparando cada
    /// lane de `Qn` contra o MESMO valor de `Rm` (broadcast). **Achado medido contra o QEMU real
    /// nesta task, que DIVERGE do que a spec citava**: `do_vcmp_scalar` só recusa `Rm == 13`
    /// (`a->rm == 13` → `false`, UNPREDICTABLE); `Rm == 15` é uma forma VÁLIDA, "constante zero"
    /// (`if (a->rm == 15) rm = tcg_constant_i32(0);`), não UNPREDICTABLE — resolvido no
    /// EXECUTOR, não no decode. `@vcmp_fp_scalar` **não decodifica o bit 28** (comentário literal
    /// do arquivo real: "we do not decode it in this format to avoid complicated
    /// overlapping-instruction-groups") — cada linha passa `size=1` ou `size=2` fixo.
    record MveVectorCompareScalar(
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition compareCondition,
            boolean floatingPoint,
            /// Inteiras: `0`/`1`/`2` (campo `size`, `size==3` recusado no decode). FP: `1`/`2`
            /// literal por linha (bit 28 NÃO decodificado, ver Javadoc da classe).
            int esz,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`, `13` já recusado no decode; `15` = "constante zero", resolvido no
            /// executor).
            int rm,
            /// Campo `mask` de 4 bits — MESMA semântica de {@link MveVectorCompare#mask}.
            int mask,
            /// Condição ARM necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPARE_SCALAR; }
    }

    /// Operações escalares (vetor × GPR broadcast, perfil M, B16.9, MVE/Helium, `target/isa-decode/
    /// mve.decode`, seção "Scalar operations", 22 encodings): reusa o MESMO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} de {@link MveVector2Op}
    /// (RFC B13.2 D1) — via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameScalarMasked}
    /// — mas o segundo operando é um valor ÚNICO lido de `Rm` (`0`-`15`, nunca `13`/`15`,
    /// UNPREDICTABLE em ambos, recusados no decode) e replicado por toda a operação, não uma lane de
    /// `Qm`. Cobre DUAS formas de encoding com o MESMO núcleo: `@2scalar` (`VADD_scalar`…
    /// `VQRDMULH_scalar`/`VMLA` — `qn`≠`qd`, `Rm` é o VALOR replicado) e `@shl_scalar`
    /// (`VSHL_S_scalar`…`VQRSHL_U_scalar` — `qn`={@link #qd}, o `&shl_scalar` real só tem `qda`;
    /// `Rm` é a CONTAGEM de deslocamento, mas {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameScalarMasked} funciona igual porque
    /// só o BYTE BAIXO de `Rm` importa nas formas `SSHL`/`USHL`/`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/
    /// `SQRSHL`/`UQRSHL`, preservado por qualquer truncamento `esz>=0`). **`VMLA` é a ÚNICA linha
    /// com `111 -` (bit 28 don't-care)** — o decoder não lê `U` para produzir `MLA`, único caso do
    /// arquivo. Beatwise (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` só para lanes ATIVAS nas
    /// 10 formas saturantes.
    record MveVectorScalar(
            /// Operação a executar (núcleo compartilhado) — qualquer valor de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} usado por
            /// {@link MveVector2Op} EXCETO os exclusivos de widening/carry/complexo.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — para `@shl_scalar`, é o MESMO valor de {@link #qn} (`Qda`).
            int qd,
            /// `Qn` (`0`-`7`) — para `@shl_scalar`, é o MESMO valor de {@link #qd} (`Qda`, fonte E
            /// destino).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR; }
    }

    /// `VQDMULLB_scalar`/`VQDMULLT_scalar` (perfil M, B16.9, MVE/Helium, verbatim de
    /// `DO_2OP_SAT_SCALAR_L`, `target/arm/tcg/mve_helper.c`): ALARGA — lê `8 >> esz` elementos de
    /// `1 << esz` bytes de `Qn` na indexação INTERCALADA `le*2 + (top?1:0)` (mesmo padrão de
    /// {@link MveVector2OpWidening}/{@link MveVectorDoublingWideningMultiply}) e multiplica cada um
    /// pelo MESMO valor de `Rm` (truncado a `esz`), saturando ao DOBRO da largura — delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingWideningScalarInterleavedMasked}.
    /// **`esz` vem do bit 28 DIRETAMENTE** (`%size_28`, que aqui coincide numericamente com o `esz`
    /// real da fonte: `0`→halfword(`1`), `1`→word(`2`) — ver Javadoc do decoder para a diferença com
    /// a Armadilha 2/7 da B16.7/B16.6, onde `%size_28` NÃO coincidia). `Qd == Qn` com `esz` word é
    /// UNPREDICTABLE (`a->qd == a->qn && a->size == MO_32` no QEMU real — "choose to undef"),
    /// recusado no decoder. Beatwise (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` quando
    /// alguma lane ATIVA satura.
    record MveVectorScalarWidening(
            /// `log2` do tamanho do elemento FONTE (`Qn`) em bytes — `1` (halfword) ou `2` (word);
            /// nunca `0`/`3` (ver Javadoc da classe).
            int esz,
            /// `true` para a forma `T` (lanes ÍMPARES da fonte); `false` para `B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`, fonte) — `Qd == Qn` com {@link #esz} `2` (word) já recusado no decoder.
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR_WIDENING; }
    }

    /// `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar` (perfil M, B16.9, MVE/Helium,
    /// `FEAT_MVE_FP`, verbatim de `DO_2OP_FP_SCALAR_ALL`, `target/arm/tcg/mve_helper.c`): delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameScalarMasked} — o segundo
    /// operando é o valor ÚNICO de `Rm` (binary16/binary32 conforme {@link #esz}), replicado por
    /// toda a operação, em vez de uma lane de `Qm`. Beatwise (mesmo gancho de {@link MveVector2Op}).
    /// Nunca satura (nenhuma operação FP de MVE seta `FPSCR.QC`).
    record MveVectorFpScalar(
            /// Só `ADD`/`SUB`/`MUL` de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp} nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_scalar_size`, bit 28: `1`→16 bits,
            /// `0`→32 bits).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_SCALAR; }
    }

    /// `VFMA_scalar`/`VFMAS_scalar` (perfil M, B16.9, MVE/Helium, `FEAT_MVE_FP`, verbatim de
    /// `DO_2OP_FP_ACC_SCALAR`/`DO_VFMAS_SCALARH`/`DO_VFMAS_SCALARS`, `target/arm/tcg/mve_helper.c`):
    /// multiply-accumulate FUNDIDO (arredondamento único) com escalar — delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddScalarMasked}.
    /// **Achado real, confirmado nos DOIS comentários literais do arquivo (não adivinhado)**:
    /// `VFMA_scalar` ("vector * scalar + vector") calcula `Qd[i] = fma(Qn[i], Rm, Qd[i])`;
    /// `VFMAS_scalar` ("vector * vector + scalar, so swap op2 and op3") calcula
    /// `Qd[i] = fma(Qn[i], Qd[i], Rm)` — MESMA troca de papéis de `VMLA`/`VMLAS` (ver
    /// {@link MveVectorScalarSpecial}), só que fundido/ponto-flutuante. Beatwise (mesmo gancho de
    /// {@link MveVector2Op}). Nunca satura.
    record MveVectorFpScalarFma(
            /// `false` = `VFMA_scalar` (`fma(Qn,Rm,Qd)`); `true` = `VFMAS_scalar`
            /// (`fma(Qn,Qd,Rm)`) — ver Javadoc da classe.
            boolean swapAccumulator,
            /// `1` = binary16, `2` = binary32.
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_SCALAR_FMA; }
    }

    /// `VBRSR`/`VMLAS`/`VQDMLAH`/`VQRDMLAH`/`VQDMLASH`/`VQRDMLASH` (perfil M, B16.9, MVE/Helium):
    /// as 6 formas escalares SEM análogo em
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} — cada uma delega a um método
    /// dedicado de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes} (ver Javadoc de cada
    /// {@link SpecialOp} para a semântica verbatim do `mve_helper.c` real). `VBRSR` (bit reverse and
    /// shift right) não tem análogo em NEON/A64. `VMLAS` inverte os papéis de `Qd`/`Rm` em relação a
    /// `VMLA` ({@link MveVectorScalar}) — "vector * vector + scalar". `VQDMLAH`/`VQRDMLASH`/
    /// `VQDMLASH`/`VQRDMLASH` são multiplicação dobrada saturante com acumulador, saturada numa
    /// ÚNICA operação de largura dupla (arquiteturalmente distinta de `SQRDMLAH` do A64/NEON).
    /// Beatwise (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` só para lanes ATIVAS nas 4 formas
    /// `VQ*DMLA*H` (`VMLAS`/`VBRSR` nunca saturam).
    record MveVectorScalarSpecial(
            /// Qual das 6 operações executar.
            SpecialOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador, exceto `VBRSR`) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR_SPECIAL; }

        /// As 6 operações servidas por {@link MveVectorScalarSpecial} — nenhuma cabe em
        /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} sem alterar o `switch`
        /// exaustivo compartilhado por A64/NEON/`MveVector2Op` (RFC B13.2 D1: reuso não vale a pena
        /// quando a semântica REAL diverge, aqui confirmado contra o QEMU verbatim).
        public enum SpecialOp {
            /// `Qd[i] = do_vbrsr(Qn[i], Rm)` — ver Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bitReverseShiftRightMasked}.
            VBRSR,
            /// `Qd[i] = Qn[i] * Qd[i] + Rm` — ver Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#multiplyAccumulateSwapScalarMasked}.
            VMLAS,
            /// `Qd[i] = round(2*Qn[i]*Rm) + Qd[i]`, saturado — `swapAccumulatorAndScalar=false`,
            /// `rounding=false` em
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingMultiplyAccumulateScalarMasked}.
            VQDMLAH,
            /// Como {@link #VQDMLAH}, com arredondamento (`rounding=true`).
            VQRDMLAH,
            /// `Qd[i] = round(2*Qn[i]*Qd[i]) + Rm`, saturado — `swapAccumulatorAndScalar=true`,
            /// `rounding=false`.
            VQDMLASH,
            /// Como {@link #VQDMLASH}, com arredondamento (`rounding=true`).
            VQRDMLASH
        }
    }

    /// `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/`VSRI`/`VSLI`
    /// (perfil M, B16.10, MVE/Helium, `target/isa-decode/mve.decode`, `@2_shl_*`/`@2_shr_*`): reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp} — MESMO núcleo do A64/NEON
    /// (RFC B13.2 D1) — via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftImmediateMasked}. `shift` já vem
    /// resolvido do decoder (`N - shift` nas formas `_shr`/`VSRI`, valor cru nas formas `_shl`/
    /// `VSLI`/`VSHLUI`). Beatwise (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` só para as 3
    /// formas saturantes (`SQSHL`/`UQSHL`/`SQSHLU`).
    record MveVectorShiftImmediate(
            /// Operação a executar (núcleo compartilhado com A64/NEON).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; determinado pelo PREFIXO de bits do
            /// encoding, não por um campo `size` — ver Javadoc do decoder).
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder.
            int shift,
            /// `Qd` (`0`-`7`) — fonte (RMW nas formas `SRI`/`SLI`) E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_IMMEDIATE; }
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T1** (`shift < esize`, perfil M, B16.10,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2_shll_b`/`@2_shll_h`): ALARGA — mesmo padrão de
    /// indexação INTERCALADA `le*2 + (top?1:0)` de {@link MveVectorShiftWidenInterleaved} (T2), via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftWidenInterleavedMasked} — a
    /// ÚNICA diferença real entre as duas formas é que aqui `shift` é um campo do encoding (`0` a
    /// `esize-1`) em vez de fixo em `esize`. **`VMOVL` é esta forma com `shift == 0`** (comentário
    /// literal do arquivo real: "we implement it that way rather than special-casing it in the
    /// decode") — não tem `Kind` próprio. **Não confundir com a forma T2 (B16.7, encoding
    /// DISTINTO).** Beatwise (mesmo gancho de {@link MveVector2Op}). Nunca satura.
    record MveVectorShiftWidenImmediateInterleaved(
            /// `true` = sinal-estende (`VSHLL_*S`); `false` = zero-estende (`VSHLL_*U`).
            boolean signed,
            /// `log2` do tamanho do elemento FONTE em bytes — `0`(byte) ou `1`(halfword); a T1 nunca
            /// tem fonte WORD.
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder (`0..esize-1`; `0` = `VMOVL`).
            int shift,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED; }
    }

    /// `VSHRNB`/`VSHRNT`/`VRSHRNB`/`VRSHRNT`/`VQSHRNB_S`/`VQSHRNT_S`/`VQSHRNB_U`/`VQSHRNT_U`/
    /// `VQSHRUNB`/`VQSHRUNT`/`VQRSHRNB_S`/`VQRSHRNT_S`/`VQRSHRNB_U`/`VQRSHRNT_U`/`VQRSHRUNB`/
    /// `VQRSHRUNT` (perfil M, B16.11, MVE/Helium, `target/isa-decode/mve.decode`, só `b`/`h`):
    /// ESTREITA com deslocamento — mesmo padrão de indexação INTERCALADA `le*2 + (top?1:0)` de
    /// {@link MveVectorNarrowInterleaved}, via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftNarrowInterleavedMasked}.
    /// Reusa {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp} (MESMO enum/mapeamento
    /// do A64/NEON: `SHRN`=`VSHRN`, `RSHRN`=`VRSHRN`, `SQSHRN`/`UQSHRN`=`VQSHRN_S`/`_U`,
    /// `SQSHRUN`=`VQSHRUN`, `SQRSHRN`/`UQRSHRN`=`VQRSHRN_S`/`_U`, `SQRSHRUN`=`VQRSHRUN`). Beatwise
    /// (mesmo gancho de {@link MveVector2Op}). `FPSCR.QC` só para as 6 formas saturantes (`SHRN`/
    /// `RSHRN` nunca saturam).
    record MveVectorShiftNarrowImmediateInterleaved(
            /// Operação de deslocamento estreitante a executar.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp op,
            /// `log2` do tamanho do elemento ESTREITO (saída) em bytes — `0`(byte) ou `1`(halfword);
            /// nunca `2`/`3` (a família só suporta `b`/`h`, daí o título da seção real).
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder (`N - raw`, `%rshift_i3/i4`).
            int shift,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED; }
    }

    /// `VSHLC` (perfil M, B16.11, MVE/Helium, `target/isa-decode/mve.decode`): "Whole Vector Left
    /// Shift with Carry" — desloca os 128 bits de `Qd` à esquerda por `imm` bits (contagem `1`-`32`;
    /// `imm == 0` no encoding significa "desloca por 32", NÃO é `UNDEF`/no-op — confirmado verbatim
    /// contra `trans_VSHLC`/`HELPER(mve_vshlc)`, `target/arm/tcg/{translate,mve_helper}.c`),
    /// injetando os bits BAIXOS de `Rdm` na base e devolvendo em `Rdm` os bits que saíram pelo topo.
    /// **NÃO é estreitante** (está na mesma seção do arquivo real por adjacência de encoding, não por
    /// família — ver Javadoc do decoder) e **NÃO é predicada lane-a-lane**: opera em 4 elementos de
    /// 32 bits (granularidade de BEAT), cada um checado contra UM bit da máscara MVE (`mask & 1` por
    /// beat, não por byte). Beatwise (mesmo gancho de {@link MveVector2Op} para `AdvanceVpt`/`ECI`).
    /// Nunca satura, sem `FPSCR.QC`.
    record MveVectorShiftLeftCarry(
            /// Campo `imm:5` cru do encoding (`0`-`31`; `0` significa "desloca por 32" — NUNCA
            /// pré-resolvido pelo decoder, ao contrário de {@link MveVectorShiftImmediate}, porque o
            /// helper real trata `shift == 0` como caso especial, não como "sem deslocamento").
            int imm,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Rdm` (GPR que fornece os bits que entram e recebe os que saem; `13`/`15` são `UNDEF`,
            /// recusados pelo decoder).
            int rdm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_LEFT_CARRY; }
    }

    /// `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU`, `VCVTAS`/`VCVTAU`/`VCVTNS`/`VCVTNU`/`VCVTPS`/
    /// `VCVTPU`/`VCVTMS`/`VCVTMU` e `VRINTN`/`VRINTX`/`VRINTA`/`VRINTZ`/`VRINTM`/`VRINTP` (perfil M,
    /// B16.12, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@1op`, linhas 810-832 —
    /// 18 dos 26 encodings da task; os outros 8 são {@link MveVectorFpConvertFixed}): delega ao
    /// núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnaryMasked})
    /// — a MESMA função de operação ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp})
    /// que `NeonFpUnary`/A64 já usam via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnary}
    /// para o caminho NÃO predicado — zero código novo de aritmética/arredondamento, só o gancho
    /// PREDICADO. `VCVT_FS`/`VCVT_FU` (`FCVTZS`/`FCVTZU`) SEMPRE arredondam para zero, IGNORANDO
    /// `FPSCR.RMode` (confirmado verbatim contra `DO_VCVT`/`mve_helper.c` real: usa os helpers
    /// `*_round_to_zero`, não o `FPSCR` corrente — o MESMO comportamento que o NEON de 32 bits já
    /// tinha para `VCVT` sem sufixo de modo). `VCVTA`/`N`/`P`/`M` carregam o modo de arredondamento
    /// no PRÓPRIO encoding (não consultam `FPSCR.RMode`). Beatwise (mesmo gancho de
    /// {@link MveVector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações FP MVE, mesmo
    /// precedente de {@link MveVectorFpTwoOp}).
    record MveVectorFpConvert(
            /// Operação a executar (núcleo compartilhado) — só as 18 formas de conversão/
            /// arredondamento desta task (nunca `ABS`/`NEG`/`RECPE`/`RSQRTE`/comparações-com-zero,
            /// que não têm encoding nesta família MVE).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp op,
            /// `1` = binary16, `2` = binary32 (`size`, `bits[19:18]` do `@1op`; MVE nunca tem `3`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT; }
    }

    /// `VCVT_SH_fixed`/`VCVT_UH_fixed`/`VCVT_HS_fixed`/`VCVT_HU_fixed`/`VCVT_SF_fixed`/
    /// `VCVT_UF_fixed`/`VCVT_FS_fixed`/`VCVT_FU_fixed` (perfil M, B16.12, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@vcvt`/`@vcvt_f16`, linhas 793-808 — os 8
    /// encodings de ponto fixo↔ponto flutuante da task): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPointMasked}) — a MESMA função
    /// que o A64 (`@fcvt_fixed`) e o NEON de 32 bits (`VCVT` fixo↔float F32) já usam via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPoint} (RFC B13.2 D1) —
    /// zero aritmética nova, só o gancho PREDICADO + meia precisão (`esz=1`, que o NEON de 32 bits
    /// não tinha). `toFloat=true` (`VCVT_{S,U}{H,F}_fixed`) SEMPRE arredonda pelo cast Java padrão
    /// (mais-próximo); `toFloat=false` (`VCVT_{H,F}{S,U}_fixed`) SEMPRE trunca para zero — NENHUMA
    /// das 8 consulta `FPSCR.RMode` (confirmado verbatim: `DO_VCVT` chama os MESMOS helpers
    /// `*_round_to_zero`/sem-sufixo que {@link MveVectorFpConvert} usa para `VCVT_FS`/`VCVT_SF` com
    /// `shift=0` — a forma "fixa" e a forma "simples" são LITERALMENTE a mesma operação, só o fator
    /// de escala muda). `shift`/`fractionBits` já resolvido pelo decoder (`N - raw`, mesma
    /// convenção `%rshift_i4`/`%rshift_i5` de B16.10/B16.11). Beatwise (mesmo gancho de
    /// {@link MveVector2Op}). Nunca satura.
    record MveVectorFpConvertFixed(
            /// `true` para `VCVT_{S,U}{H,F}_fixed` (inteiro → ponto flutuante); `false` para
            /// `VCVT_{H,F}{S,U}_fixed` (ponto flutuante → inteiro, sempre truncado).
            boolean toFloat,
            /// `true` para as formas `S` (assinado); `false` para `U` (sem sinal).
            boolean signed,
            /// `1` = binary16 (`@vcvt_f16`), `2` = binary32 (`@vcvt`); nunca `3`.
            int esz,
            /// Quantidade de bits fracionários, já resolvida pelo decoder (`N - raw`, `N` = `8 <<
            /// esz`).
            int fractionBits,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT_FIXED; }
    }
}
