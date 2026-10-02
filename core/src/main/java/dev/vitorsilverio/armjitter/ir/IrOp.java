package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IntegerOp, MemoryOp, BranchOp, SystemOp, VfpOp, NeonOp, MveOp,
        IrOp.Cycle, IrOp.Fetch {
    /// Valor do campo empacotado `immediate` (decoder → builder) que significa "usar o modo de
    /// arredondamento CORRENTE de `FPSCR.RMode`" em {@link VfpOp.Round}/{@link VfpOp.ConvertRounded}/
    /// {@link VfpOp.RoundHalf}/{@link VfpOp.ConvertRoundedHalf} — vira `direction == null` no IR (B22.7).
    /// Os 5 ordinais de `AdvSimdLanes.RoundingMode` ocupam `0..4`; `7` é a maior codificação de
    /// 3 bits, dentro da mesma máscara `0b111` que o campo já usa.
    int FPSCR_ROUNDING_DIRECTION_FIELD = 0b111;

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
        /// `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` — ver {@link NeonIntegerOp.ThreeSameByElement}.
        public static final int NEON_THREE_SAME_BY_ELEMENT = 81;
        /// B13.11: `VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL` (`2-regs-plus-scalar`,
        /// alargando) — ver {@link NeonIntegerOp.WideningByElement}.
        public static final int NEON_WIDENING_BY_ELEMENT = 82;
        /// B13.11: `VMLA_F`/`VMLS_F`/`VMUL_F` (`2-regs-plus-scalar`, ponto flutuante F32, NÃO
        /// fundido) — ver {@link NeonFpOp.FpThreeSameByElement}.
        public static final int NEON_FP_THREE_SAME_BY_ELEMENT = 83;
        /// B13.12: `VREV64`/`VREV32`/`VREV16`/`VPADDL`/`VPADAL`/`VCLS`/`VCLZ`/`VCNT`/`VMVN`/
        /// `VQABS`/`VQNEG`/as 5 comparações-com-zero inteiras/`VABS`/`VNEG`/`VRECPE`/`VRSQRTE`
        /// (dois-registradores-misc, `size==0b11`) — ver {@link NeonIntegerOp.Unary}.
        public static final int NEON_UNARY = 84;
        /// B13.12: `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U` — ver {@link NeonIntegerOp.NarrowUnary}.
        public static final int NEON_NARROW_UNARY = 85;
        /// B13.12: `VABS_F`/`VNEG_F`/as 5 comparações-com-zero FP/`VRECPE_F`/`VRSQRTE_F` — ver
        /// {@link NeonFpOp.FpUnary}.
        public static final int NEON_FP_UNARY = 86;
        /// B13.17: `VCMLA`/`VCADD` (`neon-shared`, `FEAT_FCMA`) — ver {@link NeonFpOp.Complex}.
        public static final int NEON_COMPLEX = 87;
        /// B13.17: `VCMLA_scalar` (`neon-shared`, `FEAT_FCMA`) — ver {@link NeonFpOp.ComplexByElement}.
        public static final int NEON_COMPLEX_BY_ELEMENT = 88;
        /// B13.18: `VSDOT`/`VUDOT`/`VUSDOT` (`neon-shared`, `FEAT_DotProd`/`FEAT_I8MM`) — ver
        /// {@link NeonIntegerOp.DotProduct}.
        public static final int NEON_DOT_PRODUCT = 89;
        /// B13.18: `VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar` — ver
        /// {@link NeonIntegerOp.DotProductByElement}.
        public static final int NEON_DOT_PRODUCT_BY_ELEMENT = 90;
        /// B13.14: `VSWP`/`VTRN`/`VUZP`/`VZIP` — ver {@link NeonMoveOp.SwapPermute}.
        public static final int NEON_SWAP_PERMUTE = 91;
        /// B13.14: `VEXT` — ver {@link NeonMoveOp.Extract}.
        public static final int NEON_EXTRACT = 92;
        /// B13.14: `VTBL`/`VTBX` — ver {@link NeonMoveOp.TableLookup}.
        public static final int NEON_TABLE_LOOKUP = 93;
        /// B13.14: `VDUP` escalar (NEON, `Vd = Vm[index]` replicado) — ver {@link NeonMoveOp.DuplicateScalar}.
        public static final int NEON_DUPLICATE_SCALAR = 94;
        /// B13.15: `AESE`/`AESD`/`AESMC`/`AESIMC` — ver {@link NeonCryptoOp.Aes}.
        public static final int NEON_CRYPTO_AES = 95;
        /// B13.15: `SHA1H`/`SHA1SU1`/`SHA256SU0` — ver {@link NeonCryptoOp.Sha}.
        public static final int NEON_CRYPTO_SHA = 96;
        /// B13.13: `VCVT_F16_F32`/`VCVT_B16_F32`/`VCVT_F32_F16` (conversão de precisão) — ver
        /// {@link NeonFpOp.FpConvertPrecision}.
        public static final int NEON_FP_CONVERT_PRECISION = 97;
        /// B13.19: `VSMMLA`/`VUMMLA`/`VUSMMLA` (`neon-shared`, `FEAT_I8MM`) — ver
        /// {@link NeonIntegerOp.MatrixMultiplyAccumulate}.
        public static final int NEON_MATRIX_MULTIPLY_ACCUMULATE = 98;
        /// B13.20: `VFML`/`VFMSL` (`neon-shared`, `FEAT_FHM`) — ver
        /// {@link NeonFpOp.FusedMultiplyAddLong}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG = 99;
        /// B13.20: `VFML_scalar`/`VFMSL_scalar` (`neon-shared`, `FEAT_FHM`) — ver
        /// {@link NeonFpOp.FusedMultiplyAddLongByElement}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT = 100;
        /// B13.21: `VDOT_b16` (`neon-shared`, `FEAT_BF16`) — ver {@link NeonFpOp.DotProductBFloat16}.
        public static final int NEON_DOT_PRODUCT_BFLOAT16 = 101;
        /// B13.21: `VDOT_b16_scal` — ver {@link NeonFpOp.DotProductByElementBFloat16}.
        public static final int NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16 = 102;
        /// B13.21: `VMMLA_b16` (`neon-shared`, `FEAT_BF16`) — ver
        /// {@link NeonFpOp.MatrixMultiplyAccumulateBFloat16}.
        public static final int NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 = 103;
        /// B13.21: `VFMA_b16` (`VFMAB`/`VFMAT`, `neon-shared`, `FEAT_BF16`) — ver
        /// {@link NeonFpOp.FusedMultiplyAddLongBFloat16}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16 = 104;
        /// B13.21: `VFMA_b16_scal` — ver {@link NeonFpOp.FusedMultiplyAddLongByElementBFloat16}.
        public static final int NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 = 105;
        /// B15.2: `NOCP`/`NOCP_8_1` (perfil M) — ver {@link SystemOp.Nocp}.
        public static final int NOCP = 106;
        /// B15.3: `VLDR_sysreg`/`VSTR_sysreg` (perfil M) — ver {@link VfpOp.SysregMemoryTransfer}.
        public static final int VFP_SYSREG_MEMORY_TRANSFER = 107;
        /// B15.4: `SG` (perfil M, Security Extension) — ver {@link SystemOp.SecureGateway}.
        public static final int SECURE_GATEWAY = 108;
        /// B15.4: `BXNS`/`BLXNS` (perfil M, Security Extension) — ver {@link BranchOp.SecureBranchExchange}.
        public static final int SECURE_BRANCH_EXCHANGE = 109;
        /// B15.5: `VLLDM`/`VLSTM` (perfil M, `m-nocp.decode`) — ver {@link VfpOp.VlldmVlstm}.
        public static final int VLLDM_VLSTM = 110;
        /// B15.5: `VSCCLRM` (perfil M, `m-nocp.decode`) — ver {@link VfpOp.Vscclrm}.
        public static final int VSCCLRM = 111;
        /// B15.6: `DLS`/`WLS` (perfil M, Low Overhead Branch, `t32.decode`) — ver {@link BranchOp.LoopStart}.
        public static final int LOOP_START = 112;
        /// B15.6: `LE` (perfil M, Low Overhead Branch, `t32.decode`) — ver {@link BranchOp.LoopEnd}.
        public static final int LOOP_END = 113;
        /// B16.2: avanço pós-instrução do `VPR`/`ECI` (MVE/Helium) — ver {@link MvePredicationOp.AdvanceVpt}.
        public static final int ADVANCE_VPT = 114;
        /// B16.2: `VPST` (perfil M, MVE/Helium) — ver {@link MvePredicationOp.Vpst}.
        public static final int VPST = 115;
        /// B16.2: `VPNOT` (perfil M, MVE/Helium) — ver {@link MvePredicationOp.Vpnot}.
        public static final int VPNOT = 116;
        /// B16.2: `VPSEL` (perfil M, MVE/Helium) — ver {@link MvePredicationOp.Vpsel}.
        public static final int VPSEL = 117;
        /// B16.2: `VMSR_VMRS` com `reg=12` (`VPR`, perfil M, MVE/Helium) — ver {@link MvePredicationOp.VprTransfer}.
        public static final int VPR_TRANSFER = 118;
        /// B16.3: `VLDR_VSTR` contíguo não-alargante (perfil M, MVE/Helium) — ver {@link MveMoveOp.LoadStore}.
        public static final int MVE_LOAD_STORE = 119;
        /// B16.4: `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (load alargante/store estreitante, perfil M,
        /// MVE/Helium) — ver {@link MveMoveOp.WideningLoadStore}.
        public static final int MVE_WIDENING_LOAD_STORE = 120;
        /// B16.5: `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (gather/scatter por vetor de offsets, perfil M,
        /// MVE/Helium) — ver {@link MveMoveOp.GatherScatterOffset}.
        public static final int MVE_GATHER_SCATTER_OFFSET = 121;
        /// B16.5: `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (gather/scatter com
        /// base vetorial e imediato, perfil M, MVE/Helium) — ver {@link MveMoveOp.GatherScatterImmediate}.
        public static final int MVE_GATHER_SCATTER_IMMEDIATE = 122;
        /// B16.5: `VLD2`/`VLD4`/`VST2`/`VST4` (desentrelaçamento, perfil M, MVE/Helium) — ver
        /// {@link MveMoveOp.InterleavedLoadStore}.
        public static final int MVE_INTERLEAVED_LOAD_STORE = 123;
        /// B16.5: `VIDUP`/`VDDUP` (perfil M, MVE/Helium) — ver {@link MveMoveOp.IncrementDup}.
        public static final int MVE_INCREMENT_DUP = 124;
        /// B16.5: `VIWDUP`/`VDWDUP` (perfil M, MVE/Helium) — ver {@link MveMoveOp.WrappingIncrementDup}.
        public static final int MVE_WRAPPING_INCREMENT_DUP = 125;
        /// B16.5: avanço pós-instrução SÓ do `ECI` (`mve_update_and_store_eci`), sem tocar o `VPR`
        /// (perfil M, MVE/Helium) — ver {@link MvePredicationOp.AdvanceEci}.
        public static final int ADVANCE_ECI = 126;
        /// B16.6: vector 2-op inteiro reusando {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp}
        /// (perfil M, MVE/Helium) — ver {@link MveIntegerOp.Vector2Op}.
        public static final int MVE_VECTOR_2OP = 127;
        /// B16.6: `VMULLP_B`/`VMULLP_T`/`VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (alargante,
        /// perfil M, MVE/Helium) — ver {@link MveIntegerOp.Vector2OpWidening}.
        public static final int MVE_VECTOR_2OP_WIDENING = 128;
        /// B16.6: `VADC`/`VADCI`/`VSBC`/`VSBCI` (carry encadeado por `FPSCR.C`, perfil M,
        /// MVE/Helium) — ver {@link MveIntegerOp.VectorCarry}.
        public static final int MVE_VECTOR_CARRY = 129;
        /// B16.6: `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (soma complexa inteira, perfil M,
        /// MVE/Helium) — ver {@link MveIntegerOp.VectorComplexAdd}.
        public static final int MVE_VECTOR_COMPLEX_ADD = 130;
        /// B16.7: `VMAXA`/`VMINA` (acumula `|sext(Qm)|` em `Qd`, inteiro, perfil M, MVE/Helium) —
        /// ver {@link MveIntegerOp.VectorAbsAccumulate}.
        public static final int MVE_VECTOR_ABS_ACCUMULATE = 131;
        /// B16.7: `VMAXNMA`/`VMINNMA` (acumula `|Qm|` em `Qd`, ponto flutuante, `FEAT_MVE_FP`, perfil
        /// M, MVE/Helium) — ver {@link MveFpOp.VectorFpAbsAccumulate}.
        public static final int MVE_VECTOR_FP_ABS_ACCUMULATE = 132;
        /// B16.7: `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma T2 (`shift == esize`, perfil M,
        /// MVE/Helium) — ver {@link MveIntegerOp.VectorShiftWidenInterleaved}.
        public static final int MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED = 133;
        /// B16.7: `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M,
        /// MVE/Helium) — ver {@link MveIntegerOp.VectorNarrowInterleaved}.
        public static final int MVE_VECTOR_NARROW_INTERLEAVED = 134;
        /// B16.7: `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (conversão binary16↔binary32
        /// "bottom"/"top", `FEAT_MVE_FP`, perfil M, MVE/Helium) — ver {@link MveFpOp.VectorFpConvertPrecision}.
        public static final int MVE_VECTOR_FP_CONVERT_PRECISION = 135;
        /// B16.7 sub-família 2: `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveFpOp.VectorFpComplexMultiply}.
        public static final int MVE_VECTOR_FP_COMPLEX_MULTIPLY = 136;
        /// B16.7 sub-família 2: `VQDMLADH`/`VQDMLSDH` e variantes `X`/`R` (perfil M, MVE/Helium) —
        /// ver {@link MveIntegerOp.VectorDualMultiplyAddHigh}.
        public static final int MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH = 137;
        /// B16.7 sub-família 2: `VQDMULLB`/`VQDMULLT` (perfil M, MVE/Helium) — ver
        /// {@link MveIntegerOp.VectorDoublingWideningMultiply}.
        public static final int MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY = 138;
        /// B16.7 sub-família 3: `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/
        /// `VFMS` (`FEAT_MVE_FP`, perfil M, MVE/Helium) — ver {@link MveFpOp.VectorFpTwoOp}.
        public static final int MVE_VECTOR_FP_TWO_OP = 139;
        /// B16.7 sub-família 3: `VCADD90_fp`/`VCADD270_fp` (`FEAT_MVE_FP`, perfil M, MVE/Helium) —
        /// ver {@link MveFpOp.VectorFpComplexAdd}.
        public static final int MVE_VECTOR_FP_COMPLEX_ADD = 140;
        /// B16.7 sub-família 3: `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveFpOp.VectorFpComplexMultiplyAccumulate}.
        public static final int MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE = 141;
        /// B16.8: `VCMP*`/`VCMP*_fp` vetor×vetor (perfil M, MVE/Helium) — ver {@link MvePredicationOp.VectorCompare}.
        public static final int MVE_VECTOR_COMPARE = 142;
        /// B16.8: `VCMP*_scalar`/`VCMP*_fp_scalar` vetor×GPR (perfil M, MVE/Helium) — ver
        /// {@link MvePredicationOp.VectorCompareScalar}.
        public static final int MVE_VECTOR_COMPARE_SCALAR = 143;
        /// B16.9: `VADD_scalar`…`VQRDMULH_scalar`/`VMLA` (`@2scalar`) e `VSHL_S_scalar`…
        /// `VQRSHL_U_scalar` (`@shl_scalar`), reusando
        /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} com o segundo
        /// operando vindo de um GPR (perfil M, MVE/Helium) — ver {@link MveIntegerOp.VectorScalar}.
        public static final int MVE_VECTOR_SCALAR = 144;
        /// B16.9: `VQDMULLB_scalar`/`VQDMULLT_scalar` (alargante, escalar, perfil M, MVE/Helium) —
        /// ver {@link MveIntegerOp.VectorScalarWidening}.
        public static final int MVE_VECTOR_SCALAR_WIDENING = 145;
        /// B16.9: `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar` (`FEAT_MVE_FP`, perfil M,
        /// MVE/Helium) — ver {@link MveFpOp.VectorFpScalar}.
        public static final int MVE_VECTOR_FP_SCALAR = 146;
        /// B16.9: `VFMA_scalar`/`VFMAS_scalar` (fundido, `FEAT_MVE_FP`, perfil M, MVE/Helium) — ver
        /// {@link MveFpOp.VectorFpScalarFma}.
        public static final int MVE_VECTOR_FP_SCALAR_FMA = 147;
        /// B16.9: `VBRSR`/`VMLAS`/`VQDMLAH`/`VQRDMLAH`/`VQDMLASH`/`VQRDMLASH` (formas escalares SEM
        /// análogo em {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp}, perfil M,
        /// MVE/Helium) — ver
        /// {@link MveIntegerOp.VectorScalarSpecial}.
        public static final int MVE_VECTOR_SCALAR_SPECIAL = 148;
        /// B16.10: `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/
        /// `VSRI`/`VSLI` (deslocamento por imediato + shift-and-insert, perfil M, MVE/Helium) — ver
        /// {@link MveIntegerOp.VectorShiftImmediate}.
        public static final int MVE_VECTOR_SHIFT_IMMEDIATE = 149;
        /// B16.10: `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T1** (`shift < esize`, inclui
        /// `VMOVL` = `shift == 0`, perfil M, MVE/Helium) — ver
        /// {@link MveIntegerOp.VectorShiftWidenImmediateInterleaved}.
        public static final int MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED = 150;
        /// B16.11: `VSHRNB`/`VSHRNT`/`VRSHRNB`/`VRSHRNT`/`VQSHRNB_S`/`VQSHRNT_S`/`VQSHRNB_U`/
        /// `VQSHRNT_U`/`VQSHRUNB`/`VQSHRUNT`/`VQRSHRNB_S`/`VQRSHRNT_S`/`VQRSHRNB_U`/`VQRSHRNT_U`/
        /// `VQRSHRUNB`/`VQRSHRUNT` (deslocamento estreitante, só `b`/`h`, perfil M, MVE/Helium) —
        /// ver {@link MveIntegerOp.VectorShiftNarrowImmediateInterleaved}.
        public static final int MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED = 151;
        /// B16.11: `VSHLC` (deslocamento à esquerda do vetor INTEIRO com carry em GPR, perfil M,
        /// MVE/Helium) — ver {@link MveIntegerOp.VectorShiftLeftCarry}.
        public static final int MVE_VECTOR_SHIFT_LEFT_CARRY = 152;
        /// B16.12: `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU`/`VCVTA{S,U}`/`VCVTN{S,U}`/`VCVTP{S,U}`/
        /// `VCVTM{S,U}`/`VRINTN`/`VRINTX`/`VRINTA`/`VRINTZ`/`VRINTM`/`VRINTP` (`FEAT_MVE_FP`, perfil
        /// M, MVE/Helium) — ver {@link MveFpOp.VectorFpConvert}.
        public static final int MVE_VECTOR_FP_CONVERT = 153;
        /// B16.12: `VCVT_SH_fixed`/`VCVT_UH_fixed`/`VCVT_HS_fixed`/`VCVT_HU_fixed`/`VCVT_SF_fixed`/
        /// `VCVT_UF_fixed`/`VCVT_FS_fixed`/`VCVT_FU_fixed` (`FEAT_MVE_FP`, perfil M, MVE/Helium) —
        /// ver {@link MveFpOp.VectorFpConvertFixed}.
        public static final int MVE_VECTOR_FP_CONVERT_FIXED = 154;
        /// B16.13a: `VCLS`/`VCLZ`/`VREV16`/`VREV32`/`VREV64`/`VMVN`/`VABS`/`VNEG`/`VQABS`/`VQNEG`
        /// (inteiro, perfil M, MVE/Helium) — ver {@link MveIntegerOp.VectorUnary}.
        public static final int MVE_VECTOR_UNARY = 155;
        /// B16.13a: `VABS_fp`/`VNEG_fp` (`FEAT_MVE_FP`, perfil M, MVE/Helium) — ver
        /// {@link MveFpOp.VectorFpUnary}.
        public static final int MVE_VECTOR_FP_UNARY = 156;
        /// B16.13a: `VDUP` (broadcast de `Rt` para todas as lanes ATIVAS de `Qd`, perfil M,
        /// MVE/Helium) — ver {@link MveMoveOp.VectorDup}.
        public static final int MVE_VECTOR_DUP = 157;
        /// B16.13a: `VMOV_to_2gp`/`VMOV_from_2gp` (perfil M, MVE/Helium) — ver
        /// {@link MveMoveOp.MoveLanesGpr}.
        public static final int MVE_MOVE_LANES_GPR = 158;
        /// B16.13a: `VADDV` (perfil M, MVE/Helium) — ver {@link MveReductionOp.VectorAddAcrossVector}.
        public static final int MVE_VECTOR_ADD_ACROSS_VECTOR = 159;
        /// B16.13a: `VADDLV` (perfil M, MVE/Helium) — ver {@link MveReductionOp.VectorAddAcrossVectorLong}.
        public static final int MVE_VECTOR_ADD_ACROSS_VECTOR_LONG = 160;
        /// B16.13a: `VABAV_S`/`VABAV_U` (perfil M, MVE/Helium) — ver
        /// {@link MveReductionOp.VectorAbsoluteDifferenceAccumulate}.
        public static final int MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE = 161;
        /// B16.13a: `Vimm_1r` (`VORR`/`VBIC`/`VMOV`/`VMVN` imediato, decidido no executor por
        /// `cmode`/`op`, perfil M, MVE/Helium) — ver {@link MveMoveOp.VectorModifiedImmediate}.
        public static final int MVE_VECTOR_MODIFIED_IMMEDIATE = 162;
        /// B16.13b: `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV` (perfil M, MVE/Helium) — ver
        /// {@link MveReductionOp.VectorDualAccumulate}.
        public static final int MVE_VECTOR_DUAL_ACCUMULATE = 163;
        /// B16.13b: `VMLALDAV_S`/`VMLALDAV_U`/`VMLSLDAV` (perfil M, MVE/Helium) — ver
        /// {@link MveReductionOp.VectorDualAccumulateLong}.
        public static final int MVE_VECTOR_DUAL_ACCUMULATE_LONG = 164;
        /// B16.13b: `VRMLALDAVH_S`/`VRMLALDAVH_U`/`VRMLSLDAVH` (perfil M, MVE/Helium) — ver
        /// {@link MveReductionOp.VectorRoundingDualAccumulateHigh}.
        public static final int MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH = 165;
        /// B16.13b: `VMAXV_S`/`VMAXV_U`/`VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV` (perfil M, MVE/Helium)
        /// — ver {@link MveReductionOp.VectorMinMaxAcrossVector}.
        public static final int MVE_VECTOR_MIN_MAX_ACROSS_VECTOR = 166;
        /// B16.13b: `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` (`FEAT_MVE_FP`, perfil M, MVE/Helium)
        /// — ver {@link MveReductionOp.VectorFpMinMaxAcrossVector}.
        public static final int MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR = 167;
        /// B14.3: `CRC32{B,H,W}`/`CRC32C{B,H,W}` (A32+T32, ARMv8-A) — ver {@link IntegerOp.Crc32}.
        public static final int CRC32 = 168;
        /// B14.4: `VSEL` (`sp`/`dp`, ARMv8-A, espaço VFP incondicional) — ver {@link VfpOp.Select}.
        public static final int VFP_SELECT = 169;
        /// B14.5: `VRINT{A,N,P,M}` (`sp`/`dp`, ARMv8-A, espaço VFP incondicional) — ver
        /// {@link VfpOp.Round}.
        public static final int VFP_ROUND = 170;
        /// B14.5: `VCVT{A,N,P,M}{S,U}` (`sp`/`dp`, ARMv8-A, espaço VFP incondicional) — ver
        /// {@link VfpOp.ConvertRounded}.
        public static final int VFP_CONVERT_ROUNDED = 171;
        /// B14.6: `VMOVX`/`VINS` (ARMv8-A, `FEAT_FP16`, espaço VFP incondicional) — ver
        /// {@link VfpOp.MoveHalfLane}.
        public static final int VFP_MOVE_HALF_LANE = 172;
        /// B14.6b: aritmética/unárias `_hp` (`VADD_hp`…`VFNMA_hp`/`VABS_hp`/`VNEG_hp`/`VSQRT_hp`,
        /// espaço condicional) + `VMAXNM_hp`/`VMINNM_hp` (espaço incondicional) — ver
        /// {@link VfpOp.AluHalf}. Kind ÚNICO para os dois espaços, mesma economia de {@link #VFP_ALU}.
        public static final int VFP_ALU_HALF = 173;
        /// B14.6b: `VMOV.F16 Vd,#imm` — ver {@link VfpOp.MoveImmediateHalf}.
        public static final int VFP_MOVE_IMMEDIATE_HALF = 174;
        /// B14.6b: `VCMP_hp`/`VCMPE_hp` — ver {@link VfpOp.CompareHalf}.
        public static final int VFP_COMPARE_HALF = 175;
        /// B14.6b: `VSEL_hp` (espaço incondicional) — ver {@link VfpOp.SelectHalf}.
        public static final int VFP_SELECT_HALF = 176;
        /// B14.6b: `VRINT{A,N,P,M}_hp` (espaço incondicional) — ver {@link VfpOp.RoundHalf}.
        public static final int VFP_ROUND_HALF = 177;
        /// B14.6b: `VCVT{A,N,P,M}{S,U}_hp` (espaço incondicional) — ver {@link VfpOp.ConvertRoundedHalf}.
        public static final int VFP_CONVERT_ROUNDED_HALF = 178;
        /// B14.6b: `VCVT_fix_hp` — ver {@link VfpOp.ConvertFixedHalf}.
        public static final int VFP_CONVERT_FIXED_HALF = 179;
        /// B14.6b: `VLDR_hp` — ver {@link VfpOp.LoadHalf}.
        public static final int VFP_LOAD_HALF = 180;
        /// B14.6b: `VSTR_hp` — ver {@link VfpOp.StoreHalf}.
        public static final int VFP_STORE_HALF = 181;

        /// `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/`SHA256SU1` — ver
        /// {@link NeonCryptoOp.ShaThree} (B13.23).
        public static final int NEON_CRYPTO_SHA_THREE_REGISTER = 182;
        /// B22.7: `VCVTB`/`VCVTT` entre meia precisão e simples/dupla + `VCVT_b16_f32` — ver
        /// {@link VfpOp.ConvertHalfPrecision}.
        public static final int VFP_CONVERT_HALF_PRECISION = 183;
        /// B22.7: `VJCVT` (`FEAT_JSCVT`) — ver {@link VfpOp.JavascriptConvert}.
        public static final int VFP_JAVASCRIPT_CONVERT = 184;
        /// B16.15: `LCTP` (restaura `FPSCR.LTPSIZE`) — ver {@link MvePredicationOp.LoopClearTailPredication}.
        public static final int LOOP_CLEAR_TAIL_PREDICATION = 185;
        /// B16.15: `VCTP` (cria o predicado de cauda em `VPR.P0`) — ver {@link MvePredicationOp.Vctp}.
        public static final int VCTP = 186;
        /// B16.15: `CLRM` (zera registradores/APSR) — ver {@link IntegerOp.ClearMultiple}.
        public static final int CLEAR_MULTIPLE = 187;
        /// B16.16: MVE "long shift" sobre GPR (`LSLL`/`UQSHL`/`SQRSHR`/...) — ver {@link MveIntegerOp.WideShift}.
        public static final int MVE_WIDE_SHIFT = 188;
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
}
