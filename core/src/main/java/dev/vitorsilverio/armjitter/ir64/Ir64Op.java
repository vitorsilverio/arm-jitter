package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64SystemExecutor;

/// Operação de representação intermediária para AArch64 (A64) — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.ir.IrOp}, mas um frontend IRMÃO e independente (Opção B da
/// RFC-IR-64BIT.md, aprovada 2026-07-10): registradores de 64 bits (`X0`-`X30` + `SP`/`XZR`),
/// operandos `long`, PACOTE NOVO. NENHUM tipo deste pacote é usado pelo pipeline ARMv4T/v5TE/v6/
/// v7 existente, e vice-versa (G2/G3) — os dois mundos não compartilham op, executor, nem core.
///
/// Diferença deliberada de {@link dev.vitorsilverio.armjitter.ir.IrOp}: aqui NÃO há um método
/// `condition()` universal. A64 não tem predicação geral (RFC §5.4) — só {@link BranchOp64.Branch64} (via
/// `B.cond`) carrega uma condição de fato; o resto da ISA A64 executa sempre, e fingir uma
/// condição `AL` universal em todo op seria copiar um conceito do frontend 32-bit que não existe
/// aqui.
///
/// Convenção de registrador (RFC §"XZR/SP", decisão da task B6.1): todo campo `int` de índice de
/// registrador usa `0`-`30` para `X0`-`X30` e `31` para o registrador especial de número 31 do
/// encoding — que é `XZR` (zero register, lê `0`/descarta escritas) OU `SP` (stack pointer),
/// dependendo da instrução. O DECODER nunca resolve essa ambiguidade: ele só copia o campo de 5
/// bits do encoding para o índice. Quando o manual distingue as dumas formas (`Rd|SP` vs `Rd`),
/// o record carrega um `boolean` companheiro (`dstIsStackPointer`/`src1IsStackPointer` em
/// {@link IntegerOp64.Alu64}) setado pelo DECODER a partir do próprio encoding — nunca inferido depois. A
/// resolução final (ler `0`, descartar escrita, ou redirecionar para `SP`) acontece só no
/// EXECUTOR (pacote `executor64`), nunca no decoder.
public sealed interface Ir64Op permits IntegerOp64, MemoryOp64, BranchOp64, SystemOp64, FpOp64,
        AdvSimdOp64, SveOp64, SmeOp64, Ir64Op.Cycle, Ir64Op.Fetch, Ir64Op.StreamingRestricted {

    /// Discriminador de tipo para dispatch O(1) no interpretador — mesma técnica de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp#kind()} (constantes contíguas a partir de `0`
    /// em {@link Kind}, permitindo `tableswitch` no executor). Desde a E15.4 o interpretador só o usa
    /// para separar `Fetch`/`Cycle` das instruções; o dispatch por op é {@link #execute}.
    int kind();

    /// Executa a semântica desta operação sobre o core — o único dispatch do interpretador A64
    /// (task E15.4): cada record delega, numa linha, para o método estático do executor da sua
    /// família em `executor64`, e esquecer a ponte num record novo é erro de compilação. Não há
    /// parâmetro de "modo": o backend ASM não executa a op, ele emite bytecode (ou chama este mesmo
    /// método como fallback por-op).
    ///
    /// {@link Cycle} e {@link Fetch} não são instrução — quem percorre o bloco os contabiliza à
    /// parte (G4) — e lançam {@link IllegalStateException}.
    ///
    /// @param core core a executar
    /// @return `true` se a própria operação já alterou o PC (desvio tomado)
    boolean execute(Aarch64Core core);

    private static IllegalStateException notAnInstruction() {
        return new IllegalStateException("Cycle/Fetch não são decodificados como instrução");
    }

    /// Constantes de {@link Ir64Op#kind()} — uma por subtipo selado, contíguas a partir de `0`.
    final class Kind {
        private Kind() {
        }

        public static final int ALU64 = 0;
        public static final int MOVE_WIDE = 1;
        public static final int PC_RELATIVE = 2;
        public static final int BRANCH64 = 3;
        public static final int COMPARE_BRANCH64 = 4;
        public static final int SVC = 5;
        public static final int CYCLE = 6;
        public static final int FETCH = 7;
        public static final int LOAD64 = 8;
        public static final int STORE64 = 9;
        public static final int LOAD_STORE_PAIR = 10;
        public static final int LOAD_LITERAL64 = 11;
        public static final int ALU_SHIFTED_REGISTER = 12;
        public static final int ALU_EXTENDED_REGISTER = 13;
        public static final int CONDITIONAL_SELECT = 14;
        public static final int BITFIELD = 15;
        public static final int MULTIPLY_ACCUMULATE = 16;
        public static final int DIVIDE = 17;
        public static final int LOAD_EXCLUSIVE = 18;
        public static final int STORE_EXCLUSIVE = 19;
        public static final int SYSTEM_REGISTER = 20;
        public static final int SYSTEM_INSTRUCTION = 21;
        public static final int EXCEPTION_RETURN = 22;
        /// B6.5.2: contíguo a partir de `23` — os `Kind`s `0`-`22` já estavam ocupados quando esta
        /// task foi escrita (a spec original previa `20`, desatualizada pelas tasks B6.6.1-B6.6.4
        /// intermediárias, que já tinham reivindicado `20`-`22`).
        public static final int FP64_ALU = 23;
        public static final int FP64_MOVE_IMMEDIATE = 24;
        public static final int FP64_COMPARE = 25;
        public static final int FP64_CONVERT = 26;
        /// B6.6.7: `HVC`/`SMC` — ver {@link SystemOp64.PrivilegedCall}.
        public static final int PRIVILEGED_CALL = 27;
        /// B6.8: `CCMP`/`CCMN` — ver {@link IntegerOp64.ConditionalCompare}.
        public static final int CONDITIONAL_COMPARE = 28;
        /// B6.9: `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` (registrador deslocado) — ver
        /// {@link IntegerOp64.LogicalShiftedRegister}.
        public static final int LOGICAL_SHIFTED_REGISTER = 29;
        /// B6.11: `LSLV`/`LSRV`/`ASRV`/`RORV` (deslocamento variável, quantidade em `Rm`) — ver
        /// {@link IntegerOp64.ShiftVariable}.
        public static final int SHIFT_VARIABLE = 30;
        /// B8.1: `LDXP`/`LDAXP` — ver {@link MemoryOp64.LoadExclusivePair}.
        public static final int LOAD_EXCLUSIVE_PAIR = 31;
        /// B8.1: `STXP`/`STLXP` — ver {@link MemoryOp64.StoreExclusivePair}.
        public static final int STORE_EXCLUSIVE_PAIR = 32;
        /// B8.1: `CAS`/`CASA`/`CASL`/`CASAL` — ver {@link MemoryOp64.CompareAndSwap}.
        public static final int COMPARE_AND_SWAP = 33;
        /// B8.1: `CASP`/`CASPA`/`CASPL`/`CASPAL` — ver {@link MemoryOp64.CompareAndSwapPair}.
        public static final int COMPARE_AND_SWAP_PAIR = 34;
        /// B8.2: `ADC`/`ADCS`/`SBC`/`SBCS` — ver {@link IntegerOp64.AluWithCarry}.
        public static final int ALU_WITH_CARRY = 35;
        /// B8.2: `EXTR` — ver {@link IntegerOp64.Extract}.
        public static final int EXTRACT = 36;
        /// B8.2: `RBIT`/`REV16`/`CLZ`/`CLS`/`CNT` — ver {@link IntegerOp64.DataProcessing1Source}.
        public static final int DATA_PROCESSING_1_SOURCE = 37;
        /// B8.2: `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` — ver {@link IntegerOp64.MultiplyAccumulateLong}.
        public static final int MULTIPLY_ACCUMULATE_LONG = 38;
        /// B8.2: `SMULH`/`UMULH` — ver {@link IntegerOp64.MultiplyHigh}.
        public static final int MULTIPLY_HIGH = 39;
        /// B8.2: `SETF8`/`SETF16` — ver {@link IntegerOp64.EvaluateIntoFlags}.
        public static final int EVALUATE_INTO_FLAGS = 40;
        /// B8.2: `RMIF` — ver {@link IntegerOp64.RotateIntoFlags}.
        public static final int ROTATE_INTO_FLAGS = 41;
        /// B8.2: `CFINV`/`XAFLAG`/`AXFLAG` — ver {@link IntegerOp64.ConvertFlags}.
        public static final int CONVERT_FLAGS = 42;
        /// B8.3: `MSR (immediate) DAIFSet`/`DAIFClr` — ver {@link SystemOp64.InterruptMask}.
        public static final int INTERRUPT_MASK = 43;
        /// B8.3: `BRK` — ver {@link SystemOp64.Breakpoint}.
        public static final int BREAKPOINT = 44;
        /// B8.3: `HLT` — ver {@link SystemOp64.UndefinedInstructionTrap}.
        public static final int UNDEFINED_INSTRUCTION_TRAP = 45;
        /// B10.6: `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` — ver {@link SystemOp64.AddressTranslate}.
        public static final int ADDRESS_TRANSLATE = 46;
        /// B8.4: `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB` (Floating-point data-processing, 3 source) —
        /// ver {@link FpOp64.MultiplyAdd}.
        public static final int FP64_MULTIPLY_ADD = 47;
        /// B8.5: `FCSEL` — ver {@link FpOp64.ConditionalSelect}.
        public static final int FP64_CONDITIONAL_SELECT = 48;
        /// B8.5: `FCCMP`/`FCCMPE` — ver {@link FpOp64.ConditionalCompare}.
        public static final int FP64_CONDITIONAL_COMPARE = 49;
        /// B8.5: `FRINTN`/`FRINTP`/`FRINTM`/`FRINTZ`/`FRINTA`/`FRINTX`/`FRINTI` — ver
        /// {@link FpOp64.Round}.
        public static final int FP64_ROUND = 50;
        /// B8.5: `SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU` (forma registrador-geral, inteira e ponto
        /// fixo) — ver {@link FpOp64.IntegerConvert}.
        public static final int FP64_INTEGER_CONVERT = 51;
        /// B8.5: `FMOV` entre registrador geral e FP escalar (cópia crua de bits) — ver
        /// {@link FpOp64.GeneralRegisterMove}.
        public static final int FP64_GENERAL_REGISTER_MOVE = 52;
        /// B8.6: `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store multiple structures) — ver
        /// {@link AdvSimdMoveOp64.LoadStoreMultiple}.
        public static final int VECTOR_LOAD_STORE_MULTIPLE = 53;
        /// B8.6: `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store single structure, sem replicar) — ver
        /// {@link AdvSimdMoveOp64.LoadStoreSingle}.
        public static final int VECTOR_LOAD_STORE_SINGLE = 54;
        /// B8.6: `LD1R`-`LD4R` (AdvSIMD load single structure and replicate) — ver
        /// {@link AdvSimdMoveOp64.LoadSingleReplicate}.
        public static final int VECTOR_LOAD_SINGLE_REPLICATE = 55;
        /// B8.7: `ADD`/`SUB`/`CM**`/`SHADD`/`SMAX`/`SABA`/`MUL`/`MLA`/... (AdvSIMD "three same" e
        /// escalar D-only) — ver {@link AdvSimdIntegerOp64.ArithmeticThreeSame}.
        public static final int VECTOR_ARITHMETIC_THREE_SAME = 56;
        /// B8.7: `ADDP_v`/`SMAXP_v`/`SMINP_v`/`UMAXP_v`/`UMINP_v` — ver
        /// {@link AdvSimdIntegerOp64.ArithmeticPairwise}.
        public static final int VECTOR_ARITHMETIC_PAIRWISE = 57;
        /// B8.7: `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SADDL`/`UADDL`/`SSUBL`/`USUBL`/
        /// `SABAL`/`UABAL`/`SABDL`/`UABDL` — ver {@link AdvSimdIntegerOp64.ArithmeticWidening}.
        public static final int VECTOR_ARITHMETIC_WIDENING = 58;
        /// B8.7: `SADDW`/`UADDW`/`SSUBW`/`USUBW` — ver {@link AdvSimdIntegerOp64.ArithmeticWide}.
        public static final int VECTOR_ARITHMETIC_WIDE = 59;
        /// B8.7: `ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN` — ver {@link AdvSimdIntegerOp64.ArithmeticNarrow}.
        public static final int VECTOR_ARITHMETIC_NARROW = 60;
        /// B8.7: `ADDV`/`SADDLV`/`UADDLV`/`SMAXV`/`UMAXV`/`SMINV`/`UMINV` — ver
        /// {@link AdvSimdIntegerOp64.AcrossLanes}.
        public static final int VECTOR_ACROSS_LANES = 61;
        /// B8.7: `ABS`/`NEG`/`CM**0`/`SADDLP`/`UADDLP`/`SADALP`/`UADALP` (AdvSIMD "two-register
        /// miscellaneous" e escalar D-only) — ver {@link AdvSimdIntegerOp64.ArithmeticUnary}.
        public static final int VECTOR_ARITHMETIC_UNARY = 62;
        /// B8.7: `ADDP_s` (pareamento escalar D, único mnemônico desta forma) — ver
        /// {@link AdvSimdIntegerOp64.ScalarPairwiseAdd}.
        public static final int VECTOR_SCALAR_PAIRWISE_ADD = 63;
        /// B8.8: `SQXTN`/`SQXTUN`/`UQXTN` (AdvSIMD "narrow unary" saturante, vetorial e escalar) —
        /// ver {@link AdvSimdIntegerOp64.ArithmeticNarrowUnary}.
        public static final int VECTOR_ARITHMETIC_NARROW_UNARY = 64;
        /// B8.8: `SSHR`/`USHR`/`SRSHR`/`URSHR`/`SSRA`/`USRA`/`SRSRA`/`URSRA`/`SRI`/`SHL`/`SLI`/
        /// `SQSHL`/`UQSHL`/`SQSHLU` (AdvSIMD "shift by immediate", não-largo/não-estreito) — ver
        /// {@link AdvSimdIntegerOp64.ShiftImmediate}.
        public static final int VECTOR_SHIFT_IMMEDIATE = 65;
        /// B8.8: `SHRN`/`RSHRN`/`SQSHRN`/`UQSHRN`/`SQSHRUN`/`SQRSHRN`/`UQRSHRN`/`SQRSHRUN`
        /// (AdvSIMD "shift by immediate" estreitando) — ver {@link AdvSimdIntegerOp64.ShiftNarrowImmediate}.
        public static final int VECTOR_SHIFT_NARROW_IMMEDIATE = 66;
        /// B8.8: `SSHLL`/`USHLL` (AdvSIMD "shift by immediate" alargando) — ver
        /// {@link AdvSimdIntegerOp64.ShiftWidenImmediate}.
        public static final int VECTOR_SHIFT_WIDEN_IMMEDIATE = 67;
        /// B8.9: `FADD_v`/`FSUB_v`/`FMUL_v`/`FDIV_v`/`FMAX_v`/`FMIN_v`/`FMAXNM_v`/`FMINNM_v`/
        /// `FMULX_v`/`FMLA_v`/`FMLS_v`/`FCMEQ_v`/`FCMGE_v`/`FCMGT_v`/`FACGE_v`/`FACGT_v`/`FABD_v`/
        /// `FRECPS_v`/`FRSQRTS_v` (AdvSIMD "three same" de ponto flutuante, só simples/dupla) — ver
        /// {@link AdvSimdFpOp64.FpArithmeticThreeSame}.
        public static final int VECTOR_FP_ARITHMETIC_THREE_SAME = 68;
        /// B8.9: `FADDP_v`/`FMAXP_v`/`FMINP_v`/`FMAXNMP_v`/`FMINNMP_v` — ver
        /// {@link AdvSimdFpOp64.FpArithmeticPairwise}.
        public static final int VECTOR_FP_ARITHMETIC_PAIRWISE = 69;
        /// B8.9: `FABS_v`/`FNEG_v`/`FSQRT_v`/`FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`FCM**0_v`/
        /// `SCVTF_vi`/`UCVTF_vi`/`FCVTxS_vi`/`FCVTxU_vi` (AdvSIMD "two-register miscellaneous" de
        /// ponto flutuante) — ver {@link AdvSimdFpOp64.FpArithmeticUnary}.
        public static final int VECTOR_FP_ARITHMETIC_UNARY = 70;
        /// B8.10: `EXT` — ver {@link AdvSimdMoveOp64.Extract}.
        public static final int VECTOR_EXTRACT = 71;
        /// B8.10: `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2` — ver {@link AdvSimdMoveOp64.Permute}.
        public static final int VECTOR_PERMUTE = 72;
        /// B8.10: `TBL`/`TBX` — ver {@link AdvSimdMoveOp64.TableLookup}.
        public static final int VECTOR_TABLE_LOOKUP = 73;
        /// B8.10: `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` — ver {@link AdvSimdFpOp64.FpAcrossLanes}.
        public static final int VECTOR_FP_ACROSS_LANES = 74;
        /// B8.11: `AESE`/`AESD`/`AESMC`/`AESIMC` (ARMv8-A Cryptographic Extension, AES) — ver
        /// {@link CryptoOp64.Aes}.
        public static final int CRYPTO_AES = 75;
        /// B8.11: `PMULL`/`PMULL2` formas `p8`/`p64` (multiplicação polinomial alargando,
        /// Cryptographic Extension) — ver {@link AdvSimdIntegerOp64.PolynomialMultiplyLong}.
        public static final int VECTOR_POLYNOMIAL_MULTIPLY_LONG = 76;
        /// B8.11b: "Cryptographic three-register SHA" (`SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/
        /// `SHA256H`/`SHA256H2`/`SHA256SU1`) — ver {@link CryptoOp64.ShaThreeRegister}.
        public static final int CRYPTO_SHA_THREE_REGISTER = 77;
        /// B8.11b: "Cryptographic two-register SHA" (`SHA1H`/`SHA1SU1`/`SHA256SU0`) — ver
        /// {@link CryptoOp64.ShaTwoRegister}.
        public static final int CRYPTO_SHA_TWO_REGISTER = 78;
        /// B8.12: `DUP` (elemento vetorial) — ver {@link AdvSimdMoveOp64.DuplicateElement}.
        public static final int VECTOR_DUPLICATE_ELEMENT = 79;
        /// B8.12: `DUP` (registrador geral) — ver {@link AdvSimdMoveOp64.DuplicateGeneral}.
        public static final int VECTOR_DUPLICATE_GENERAL = 80;
        /// B8.12: `INS` (registrador geral) — ver {@link AdvSimdMoveOp64.InsertGeneral}.
        public static final int VECTOR_INSERT_GENERAL = 81;
        /// B8.12: `INS` (elemento vetorial) — ver {@link AdvSimdMoveOp64.InsertElement}.
        public static final int VECTOR_INSERT_ELEMENT = 82;
        /// B8.12: `SMOV`/`UMOV` — ver {@link AdvSimdMoveOp64.MoveElement}.
        public static final int VECTOR_MOVE_ELEMENT = 83;
        /// B8.13: `LDR`/`STR` SIMD&FP registrador-imediato — ver {@link FpOp64.Load64}/{@link FpOp64.Store64}.
        public static final int FP_LOAD64 = 84;
        public static final int FP_STORE64 = 85;
        /// B8.13: `LDP`/`STP` SIMD&FP — ver {@link FpOp64.LoadStorePair}.
        public static final int FP_LOAD_STORE_PAIR = 86;
        /// B8.13: `LDR (literal)` SIMD&FP — ver {@link FpOp64.LoadLiteral64}.
        public static final int FP_LOAD_LITERAL64 = 87;
        /// B8.19: `MUL_vi`/`MLA_vi`/`MLS_vi`/`SQDMULH_{vi,si}`/`SQRDMULH_{vi,si}` (AdvSIMD "vector/
        /// scalar × indexed element", subconjunto não-alargante) — ver
        /// {@link AdvSimdIntegerOp64.ArithmeticThreeSameByElement}.
        public static final int VECTOR_ARITHMETIC_THREE_SAME_BY_ELEMENT = 88;
        /// B8.19: `SMULL_vi`/`UMULL_vi`/`SMLAL_vi`/`UMLAL_vi`/`SMLSL_vi`/`UMLSL_vi`/
        /// `SQDMULL_{vi,si}`/`SQDMLAL_{vi,si}`/`SQDMLSL_{vi,si}` (AdvSIMD "vector/scalar × indexed
        /// element", subconjunto alargante) — ver {@link AdvSimdIntegerOp64.ArithmeticWideningByElement}.
        public static final int VECTOR_ARITHMETIC_WIDENING_BY_ELEMENT = 89;
        /// B8.19: `FMUL_{vi,si}`/`FMLA_{vi,si}`/`FMLS_{vi,si}`/`FMULX_{vi,si}` (AdvSIMD "vector/
        /// scalar × indexed element" de ponto flutuante, só simples/dupla) — ver
        /// {@link AdvSimdFpOp64.FpArithmeticThreeSameByElement}.
        public static final int VECTOR_FP_ARITHMETIC_THREE_SAME_BY_ELEMENT = 90;
        /// B11.12: `EOR3`/`BCAX` (`FEAT_SHA3`, "Cryptographic four-register") — ver
        /// {@link CryptoOp64.Sha3FourRegister}.
        public static final int CRYPTO_SHA3_FOUR_REGISTER = 91;
        /// B11.12: `RAX1`/`XAR` (`FEAT_SHA3`, "Cryptographic three-register, imm2") — ver
        /// {@link CryptoOp64.Sha3TwoSourceRotate}.
        public static final int CRYPTO_SHA3_TWO_SOURCE_ROTATE = 92;
        /// B19.1: `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP`
        /// (`FEAT_LSE`) — ver {@link MemoryOp64.AtomicMemoryOp}. `LDAPR` (`FEAT_LRCPC`) reaproveita
        /// {@link MemoryOp64.Load64}, sem `Kind` próprio.
        public static final int ATOMIC_MEMORY_OP = 93;
        /// B19.3: `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` na forma AdvSIMD FP↔ponto fixo (`@fcvt_fixed`,
        /// com campo `#fbits`) — ver {@link AdvSimdFpOp64.FpConvertFixedPoint}. Escalar nesta task; a forma
        /// vetorial (`_vf`) chega em B19.4 reaproveitando o record.
        public static final int VECTOR_FP_CONVERT_FIXED_POINT = 94;
        /// B19.4: `FCVTL`/`FCVTN`/`FCVTXN` (AdvSIMD conversão de PRECISÃO vetorial, entre `f16`/`f32`/
        /// `f64`) — ver {@link AdvSimdFpOp64.FpConvertPrecision}.
        public static final int VECTOR_FP_CONVERT_PRECISION = 95;
        /// B19.6 bloco C: `PACGA` (`FEAT_PAuth`) — ver {@link IntegerOp64.PointerAuthGeneric}.
        public static final int POINTER_AUTH_GENERIC = 96;
        /// B19.6 bloco D: `ABS` de registrador geral (`FEAT_CSSC`) — ver {@link IntegerOp64.AbsGeneral}.
        public static final int ABS_GENERAL = 97;
        /// B19.6 bloco E: `DUP` escalar (`DUP_element_s`) — ver {@link AdvSimdMoveOp64.DuplicateElementScalar}.
        public static final int VECTOR_DUPLICATE_ELEMENT_SCALAR = 98;
        /// B19.6 bloco F: `FMOV` entre registrador geral e `Vn.D[1]` (metade ALTA) — ver
        /// {@link FpOp64.HighHalfMove}.
        public static final int FP64_HIGH_HALF_MOVE = 99;
        /// B19.6 bloco G: `MOVI`/`MVNI`/`ORR`/`BIC`/`FMOV` imediato AdvSIMD (`Vimm`/`FMOVI_v_h`) —
        /// ver {@link AdvSimdMoveOp64.ModifiedImmediate64}.
        public static final int ADV_SIMD_MODIFIED_IMMEDIATE_64 = 100;
        /// B19.8: `LUTI2`/`LUTI4` (`FEAT_LUT`, consulta de tabela por lane com índices empacotados)
        /// — ver {@link AdvSimdMoveOp64.LookupTable}.
        public static final int VECTOR_LOOKUP_TABLE = 101;
        /// B19.7: `BFCVT` (`FEAT_BF16`, escalar `f32`→`bf16`) — ver {@link FpOp64.ConvertToBf16}.
        public static final int FP64_CONVERT_TO_BF16 = 102;
        /// B19.7: `BFDOT` vetorial (`FEAT_BF16`) — ver {@link AdvSimdFpOp64.FpDotProductBFloat16}.
        public static final int VECTOR_FP_DOT_PRODUCT_BFLOAT16 = 103;
        /// B19.7: `BFDOT` indexado (`FEAT_BF16`) — ver {@link AdvSimdFpOp64.FpDotProductBFloat16ByElement}.
        public static final int VECTOR_FP_DOT_PRODUCT_BFLOAT16_BY_ELEMENT = 104;
        /// B19.7: `BFMLALB`/`BFMLALT` (`FEAT_BF16`) — ver {@link AdvSimdFpOp64.FpMultiplyAddLongBFloat16}.
        public static final int VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16 = 105;
        /// B19.7: `BFMLALB`/`BFMLALT` indexado (`FEAT_BF16`) — ver
        /// {@link AdvSimdFpOp64.FpMultiplyAddLongBFloat16ByElement}.
        public static final int VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16_BY_ELEMENT = 106;
        /// B19.7: `BFMMLA` (`FEAT_BF16`) — ver {@link AdvSimdFpOp64.FpMatrixMultiplyAccumulateBFloat16}.
        public static final int VECTOR_FP_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 = 107;
        /// B19.12: `USDOT_v` (`FEAT_I8MM`, produto escalar MISTO vetorial) — ver
        /// {@link AdvSimdIntegerOp64.IntegerDotProduct}.
        public static final int VECTOR_INTEGER_DOT_PRODUCT = 108;
        /// B19.12: `USDOT_vi`/`SUDOT_vi` (`FEAT_I8MM`, produto escalar MISTO indexado) — ver
        /// {@link AdvSimdIntegerOp64.IntegerDotProductByElement}.
        public static final int VECTOR_INTEGER_DOT_PRODUCT_BY_ELEMENT = 109;
        /// B19.12: `SMMLA`/`UMMLA`/`USMMLA` (`FEAT_I8MM`) — ver
        /// {@link AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate}.
        public static final int VECTOR_INTEGER_MATRIX_MULTIPLY_ACCUMULATE = 110;
        /// B19.10: `SHA512H`/`SHA512H2`/`SHA512SU1` (`FEAT_SHA512`) — ver
        /// {@link CryptoOp64.Sha512ThreeRegister}.
        public static final int CRYPTO_SHA512_THREE_REGISTER = 111;
        /// B19.10: `SHA512SU0` (`FEAT_SHA512`) — ver {@link CryptoOp64.Sha512TwoRegister}.
        public static final int CRYPTO_SHA512_TWO_REGISTER = 112;
        /// B19.10: `SM3PARTW1`/`SM3PARTW2` (`FEAT_SM3`) — ver {@link CryptoOp64.Sm3ThreeRegister}.
        public static final int CRYPTO_SM3_THREE_REGISTER = 113;
        /// B19.10: `SM3SS1` (`FEAT_SM3`) — ver {@link CryptoOp64.Sm3FourRegister}.
        public static final int CRYPTO_SM3_FOUR_REGISTER = 114;
        /// B19.10: `SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B` (`FEAT_SM3`) — ver
        /// {@link CryptoOp64.Sm3ThreeRegisterImm2}.
        public static final int CRYPTO_SM3_THREE_REGISTER_IMM2 = 115;
        /// B19.10: `SM4E` (`FEAT_SM4`) — ver {@link CryptoOp64.Sm4Encrypt}.
        public static final int CRYPTO_SM4_ENCRYPT = 116;
        /// B19.10: `SM4EKEY` (`FEAT_SM4`) — ver {@link CryptoOp64.Sm4KeyUpdate}.
        public static final int CRYPTO_SM4_KEY_UPDATE = 117;
        /// B19.13: `FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2` vetorial (`FEAT_FHM`) — ver
        /// {@link AdvSimdFpOp64.FpMultiplyAddLong}.
        public static final int VECTOR_FP_MULTIPLY_ADD_LONG = 118;
        /// B19.13: `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` indexado (`FEAT_FHM`) — ver
        /// {@link AdvSimdFpOp64.FpMultiplyAddLongByElement}.
        public static final int VECTOR_FP_MULTIPLY_ADD_LONG_BY_ELEMENT = 119;
        /// B19.11: `FCVTN_bh`/`FCVTN_bs` (`FEAT_FP8`) — ver {@link AdvSimdFpOp64.FpConvertToFp8}.
        public static final int VECTOR_FP_CONVERT_TO_FP8 = 120;
        /// B19.11: `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL` (`FEAT_FP8`) — ver
        /// {@link AdvSimdFpOp64.FpConvertFromFp8}.
        public static final int VECTOR_FP_CONVERT_FROM_FP8 = 121;
        /// B19.17: `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (`FEAT_CRC32`) — ver {@link IntegerOp64.Crc32}.
        public static final int CRC32 = 122;
        /// B19.29: `FJCVTZS` (`FEAT_JSCVT`) — ver {@link FpOp64.JavascriptConvert}.
        public static final int FP64_JAVASCRIPT_CONVERT = 123;
        /// B19.16: `SETP`/`SETM`/`SETE` (`FEAT_MOPS`) — ver {@link MemoryOp64.MemorySet}.
        public static final int MEMORY_SET = 124;
        /// B19.16: `CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE` (`FEAT_MOPS`) — ver {@link MemoryOp64.MemoryCopy}.
        public static final int MEMORY_COPY = 125;
        /// B19.25: `LDCLRP`/`LDSETP`/`SWPP` (`FEAT_LSE128`) — ver {@link MemoryOp64.AtomicMemoryOpPair}.
        public static final int ATOMIC_MEMORY_OP_PAIR = 126;
        /// B19.26: `FMOV_hx`/`FMOV_xh` (`FEAT_FP16` residual) — ver
        /// {@link FpOp64.HalfPrecisionGeneralRegisterMove}.
        public static final int FP64_HALF_PRECISION_GENERAL_REGISTER_MOVE = 127;
        /// B19.26: `FCVT_s_hs`/`FCVT_s_hd`/`FCVT_s_sh`/`FCVT_s_dh` (`FEAT_FP16` residual) — ver
        /// {@link FpOp64.ConvertHalfPrecision}.
        public static final int FP64_CONVERT_HALF_PRECISION = 128;
        /// B19.14: `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G` (`FEAT_MTE2`) — ver {@link MemoryOp64.MemoryTag}.
        public static final int MEMORY_TAG = 129;
        /// B19.14: `STGM`/`LDGM`/`STZGM` (`FEAT_MTE2`) — ver {@link MemoryOp64.MemoryTagMultiple}.
        public static final int MEMORY_TAG_MULTIPLE = 130;
        /// B19.14: `STGP` (`FEAT_MTE2`) — ver {@link MemoryOp64.StorePairTag}.
        public static final int STORE_PAIR_TAG = 131;
        /// B19.14: `SUBP`/`SUBPS` (`FEAT_MTE2`) — ver {@link IntegerOp64.SubtractPointer}.
        public static final int SUBTRACT_POINTER = 132;
        /// B19.14: `IRG` (`FEAT_MTE2`) — ver {@link IntegerOp64.InsertRandomTag}.
        public static final int INSERT_RANDOM_TAG = 133;
        /// B19.14: `GMI` (`FEAT_MTE2`) — ver {@link IntegerOp64.TagMaskInsert}.
        public static final int TAG_MASK_INSERT = 134;
        /// B19.14: `SETGP`/`SETGM`/`SETGE` (`FEAT_MTE2`+`FEAT_MOPS`) — ver {@link MemoryOp64.MemorySetTagged}.
        public static final int MEMORY_SET_TAGGED = 135;
        /// B19.21: `SMAX`/`SMIN`/`UMAX`/`UMIN` de registrador geral (`FEAT_CSSC`) — ver
        /// {@link IntegerOp64.MinMaxGeneral}.
        public static final int MIN_MAX_GENERAL = 136;
        /// B19.15: `PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDA`/`AUTDB`/`XPACI`/`XPACD`
        /// (`FEAT_PAuth`, formas de propósito geral) — ver {@link IntegerOp64.PointerAuthInPlace}.
        public static final int POINTER_AUTH_IN_PLACE = 137;
        /// B19.20: `FCADD_90`/`FCADD_270` (`FEAT_FCMA`) — ver {@link AdvSimdFpOp64.FpComplexAdd}.
        public static final int VECTOR_FP_COMPLEX_ADD = 138;
        /// B19.20: `FCMLA_v` (`FEAT_FCMA`) — ver {@link AdvSimdFpOp64.FpComplexMultiplyAccumulate}.
        public static final int VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE = 139;
        /// B19.20: `FCMLA_vi` (`FEAT_FCMA`) — ver {@link AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement}.
        public static final int VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE_BY_ELEMENT = 140;

        public static final int FP64_ROUND_RANGE_LIMITED = 141;
        /// B19.22: `CB_cond` (`FEAT_CMPBR`) — ver {@link BranchOp64.CompareAndBranchRegister}.
        public static final int COMPARE_AND_BRANCH_REGISTER = 142;
        /// B19.22: `CB_cond_imm` (`FEAT_CMPBR`) — ver {@link BranchOp64.CompareAndBranchImmediate}.
        public static final int COMPARE_AND_BRANCH_IMMEDIATE = 143;
        /// B19.11e: `FSCALE` (`FEAT_FP8`) — ver {@link AdvSimdFpOp64.FpScaleByInt}.
        public static final int VECTOR_FP_SCALE_BY_INT = 144;
        /// B19.24: `FAMAX`/`FAMIN` (`FEAT_FAMINMAX`) — ver {@link AdvSimdFpOp64.FpAbsoluteMaxMin}.
        public static final int VECTOR_FP_ABSOLUTE_MAX_MIN = 145;
        /// B19.11b: `FMLAL_hb_v`/`FMLALL_sb_v` (`FEAT_FP8FMA`) — ver
        /// {@link AdvSimdFpOp64.Fp8FusedMultiplyAddLong}.
        public static final int VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG = 146;
        /// B19.11b: `FMLAL_hb_vi`/`FMLALL_sb_vi` (`FEAT_FP8FMA`) — ver
        /// {@link AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement}.
        public static final int VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT = 147;
        /// B19.11c/B19.11d: `FDOT_hb_v`/`FDOT_sb_v` (`FEAT_FP8DOT2`/`FEAT_FP8DOT4`) — ver
        /// {@link AdvSimdFpOp64.Fp8DotProduct}.
        public static final int VECTOR_FP8_DOT_PRODUCT = 148;
        /// B19.11c/B19.11d: `FDOT_hb_vi`/`FDOT_sb_vi` — ver {@link AdvSimdFpOp64.Fp8DotProductByElement}.
        public static final int VECTOR_FP8_DOT_PRODUCT_BY_ELEMENT = 149;
        /// B18.2: `MSR SVCRSM/SVCRZA/SVCRSMZA, #imm` (`SMSTART`/`SMSTOP`) — ver {@link SystemOp64.StreamingModeControl}.
        public static final int STREAMING_MODE_CONTROL = 150;
        /// B18.2: instrução ilegal em modo streaming — ver {@link StreamingRestricted}.
        public static final int STREAMING_RESTRICTED = 151;
        /// B17.4: predicados SVE — ver {@link SvePredicateOp64.PredicateLogical}.
        public static final int SVE_PREDICATE_LOGICAL = 152;
        /// B17.4: `PTEST`/`PTRUE`/`PFALSE`/`FFR`/`PFIRST`/`PNEXT` — ver {@link SvePredicateOp64.PredicateMisc}.
        public static final int SVE_PREDICATE_MISC = 153;
        /// B17.4: `BRKA`/`BRKB`/`BRKPA`/`BRKPB`/`BRKN` — ver {@link SvePredicateOp64.PartitionBreak}.
        public static final int SVE_PARTITION_BREAK = 154;
        /// B17.4: `CNTP`/`INCP`/`SQINCP`… — ver {@link SvePredicateOp64.PredicateCount}.
        public static final int SVE_PREDICATE_COUNT = 155;
        /// B17.4: `CNTB`/`INCB`/`SQINCB`… — ver {@link SveIntegerOp64.ElementCount}.
        public static final int SVE_ELEMENT_COUNT = 156;
        /// B17.5: inteiro SVE sem predicado governante (`ADD`/`AND`/`XAR`/`INDEX`…) — ver {@link SveIntegerOp64.IntegerUnpredicated}.
        public static final int SVE_INTEGER_UNPREDICATED = 157;
        /// B17.6: inteiro SVE predicado (aritmética binária, shifts, unárias) — ver {@link SveIntegerOp64.IntegerPredicated}.
        public static final int SVE_INTEGER_PREDICATED = 158;
        /// B17.7: redução inteira SVE (`SADDV`/`ORV`/`SMAXV`/… e as 8 `*QV` por segmento) — ver {@link SveIntegerOp64.IntegerReduction}.
        public static final int SVE_INTEGER_REDUCTION = 159;
        /// B17.8: inteiro SVE com imediato (bitmask, cópia/broadcast, aritmética e min/max/`MUL` com imediato) — ver {@link SveIntegerOp64.Immediate}.
        public static final int SVE_IMMEDIATE = 160;
        /// B17.8: dot-product vetorial/indexado, multiply-add/long/saturante indexado e complexos — ver {@link SveIntegerOp64.MultiplyIndexed}.
        public static final int SVE_MULTIPLY_INDEXED = 161;
        /// B17.12: `ADDVL`/`ADDPL`/`RDVL` e `ADR` vetorial — ver {@link SveIntegerOp64.Address}.
        public static final int SVE_ADDRESS = 162;
        /// B17.10: permutação SVE não predicada (`EXT`/`DUP`/`INSR`/`REV`/`TBL`/`UNPK`/`ZIP`/`UZP`/`TRN`/`PMOV`…) — ver {@link SveIntegerOp64.Permute}.
        public static final int SVE_PERMUTE = 163;
        /// B17.9: comparação SVE inteira que produz predicado (vetor×vetor, elemento largo, imediato) — ver {@link SvePredicateOp64.Compare}.
        public static final int SVE_COMPARE = 164;
        /// B17.9: `WHILE*`/`CTERM` (comparação de escalares: contagem-limite e terminação de laço) — ver {@link SvePredicateOp64.ScalarCompare}.
        public static final int SVE_SCALAR_COMPARE = 165;
        /// B17.11: permutação de predicado, permutação predicada (`COMPACT`/`LAST*`/`CLAST*`/`REV*`/`SPLICE`/`EXPAND`) e `SEL` — ver {@link SveIntegerOp64.PermutePredicated}.
        public static final int SVE_PERMUTE_PREDICATED = 166;
        /// B17.13: aritmética de ponto flutuante SVE (não predicada, predicada, com imediato de 1 bit, `FTMAD`, `FRECPE`/`FRSQRTE`) — ver {@link SveFpOp64.FpArithmetic}.
        public static final int SVE_FP_ARITHMETIC = 167;
        /// B17.14: multiply-add FP SVE (predicado, indexado), `FMUL` indexado e aritmética complexa `FCADD`/`FCMLA` — ver {@link SveFpOp64.FpMultiplyAdd}.
        public static final int SVE_FP_MULTIPLY_ADD = 168;
        /// B17.15: comparação FP SVE (vetores e com zero) que produz predicado e reduções FP (rápida, por quadword, acumulativa) — ver {@link SveFpOp64.FpCompareReduce}.
        public static final int SVE_FP_COMPARE_REDUCE = 169;
        /// B17.16: unárias FP predicadas SVE (conversões de precisão e FP↔inteiro, `FRINT*`, `FRINT32/64`, `FRECPX`, `FSQRT`) — ver {@link SveFpOp64.FpUnary}.
        public static final int SVE_FP_UNARY = 170;
        /// B17.17: load contíguo SVE (`LD1`/`LD[234]`/`LDNT1`, `LD1R*`, `LD1RQ`/`LD1RO`), first-fault/non-fault (`LDFF1`/`LDNF1`), `LDR` de vetor e de predicado e `PRF*` — ver {@link SveMemoryOp64.Load}.
        public static final int SVE_LOAD = 171;
        /// B17.18: store SVE (`ST1`/`ST[234]`/`STNT1` contíguos, `STR` de vetor e de predicado, scatter `ST1_zprz`/`ST1_zpiz` e `ST1Q`) — ver {@link SveMemoryOp64.Store}.
        public static final int SVE_STORE = 172;
        /// B17.19: gather load SVE (`LD1_zprz`, `LD1_zpiz`, `LD1Q` e as formas first-fault `LDFF1`) — ver {@link SveMemoryOp64.Gather}.
        public static final int SVE_GATHER = 173;
        /// B17.28: predicado-como-contador SVE2.1 (`PTRUE`/`CNTP`/`PEXT` sobre `PN8`-`PN15`) — ver {@link SvePredicateOp64.CounterPredicate}.
        public static final int SVE_COUNTER_PREDICATE = 174;
        /// B17.28: `LD1`/`ST1` multi-vetor contíguo (2 ou 4 registradores) governado por predicado-como-contador — ver {@link SveMemoryOp64.MultiVectorMemory}.
        public static final int SVE_MULTI_VECTOR_MEMORY = 175;
        /// B17.22: `MATCH`/`NMATCH` (busca de caractere vetorial, por segmento de 128 bits) — ver {@link SvePredicateOp64.Match}.
        public static final int SVE_MATCH = 176;
        /// B17.22: `HISTCNT` (histograma prefixo, VETOR INTEIRO)/`HISTSEG` (por segmento de 128 bits) — ver {@link SveIntegerOp64.Histogram}.
        public static final int SVE_HISTOGRAM = 177;
        /// B17.22: `LUTI2`/`LUTI4` (`FEAT_LUT`) — ver {@link SveIntegerOp64.LookupTable}.
        public static final int SVE_LOOKUP_TABLE = 178;
        /// B17.22: `PSEL` — ver {@link SvePredicateOp64.PredicateSelect}.
        public static final int SVE_PREDICATE_SELECT = 179;
        /// B17.22: `SCLAMP`/`UCLAMP`/`FCLAMP` — ver {@link SveIntegerOp64.Clamp}.
        public static final int SVE_CLAMP = 180;
        /// B17.23: `F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/`BF2CVTLT` — ver
        /// {@link SveFpOp64.FpConvertFp8}.
        public static final int SVE_FP_CONVERT_FP8 = 181;
        /// B17.23: `FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT` (não predicadas) — ver {@link SveFpOp64.FpConvertToFp8}.
        public static final int SVE_FP_CONVERT_TO_FP8 = 182;
        /// B17.23: `FADDP`/`FMAXNMP`/`FMINNMP`/`FMAXP`/`FMINP` — ver {@link SveFpOp64.FpPairwise}.
        public static final int SVE_FP_PAIRWISE = 183;
        /// B17.23: `BFMMLA`/`FMMLA_s`/`FMMLA_d`/`FMMLA_sb`/`FMMLA_hb` — ver {@link SveFpOp64.FpMatrixMultiply}.
        public static final int SVE_FP_MATRIX_MULTIPLY = 184;
        /// B17.23: `FCVTNT_sh`/`FCVTLT_hs`/`FCVTNT_ds`/`FCVTLT_sd`/`FCVTXNT_ds`/`BFCVTNT` (`_m`/`_z`) — ver
        /// {@link SveFpOp64.FpConvertOddElements}.
        public static final int SVE_FP_CONVERT_ODD_ELEMENTS = 185;
        /// B17.23: `FLOGB` (`_m`/`_z`) — ver {@link SveFpOp64.FpLogB}.
        public static final int SVE_FP_LOGB = 186;
        /// B17.23: `FMLAL_hb`/`FMLALL_sb` (vetorial e indexado) — ver {@link SveFpOp64.Fp8FusedMultiplyAddLong}.
        public static final int SVE_FP8_FUSED_MULTIPLY_ADD_LONG = 187;
        /// B17.23: `FDOT_hb`/`FDOT_sb` (vetorial e indexado) — ver {@link SveFpOp64.Fp8DotProduct}.
        public static final int SVE_FP8_DOT_PRODUCT = 188;
        /// B17.23: `FMLALB`/`FMLALT`/`FMLSLB`/`FMLSLT` (`_zzzw`/`_zzxw`) — ver {@link SveFpOp64.FpMultiplyAddLongWiden}.
        public static final int SVE_FP_MULTIPLY_ADD_LONG_WIDEN = 189;
        /// B17.23: `BFMLALB`/`BFMLALT`/`BFMLSLB`/`BFMLSLT` (`_zzzw`/`_zzxw`) — ver
        /// {@link SveFpOp64.FpMultiplyAddLongWidenBFloat16}.
        public static final int SVE_FP_MULTIPLY_ADD_LONG_WIDEN_BFLOAT16 = 190;
        /// B17.23: `FDOT_zzzz`/`FDOT_zzxz` — ver {@link SveFpOp64.FpDotProductWiden}.
        public static final int SVE_FP_DOT_PRODUCT_WIDEN = 191;
        /// B17.23: `BFDOT_zzzz`/`BFDOT_zzxz` — ver {@link SveFpOp64.FpDotProductWidenBFloat16}.
        public static final int SVE_FP_DOT_PRODUCT_WIDEN_BFLOAT16 = 192;
        /// B17.24: `AESE`/`AESD`/`AESMC`/`AESIMC` vetoriais (`FEAT_SVE_AES`) — ver {@link SveIntegerOp64.CryptoAes}.
        public static final int SVE_CRYPTO_AES = 193;
        /// B17.24: `SM4E` vetorial (`FEAT_SVE_SM4`) — ver {@link SveIntegerOp64.CryptoSm4Encrypt}.
        public static final int SVE_CRYPTO_SM4_ENCRYPT = 194;
        /// B17.24: `SM4EKEY` vetorial (`FEAT_SVE_SM4`) — ver {@link SveIntegerOp64.CryptoSm4KeyUpdate}.
        public static final int SVE_CRYPTO_SM4_KEY_UPDATE = 195;
        /// B17.24: `RAX1` vetorial (`FEAT_SVE_SHA3`) — ver {@link SveIntegerOp64.CryptoRax1}.
        public static final int SVE_CRYPTO_RAX1 = 196;
        /// B18.3: `ZERO` (`FEAT_SME`) — ver {@link SmeOp64.Zero}.
        public static final int SME_ZERO = 197;
        /// B18.3: `ZERO_zt0` (`FEAT_SME2`) — ver {@link SmeOp64.ZeroZt0}.
        public static final int SME_ZERO_ZT0 = 198;
        /// B18.3: `MOVA`/`MOVAZ` (`FEAT_SME`/`FEAT_SME2`/`FEAT_SME2p1`) — ver {@link SmeOp64.Mova}.
        public static final int SME_MOVA = 199;
        /// B18.4: `LD1`/`ST1` de slice de tile (`FEAT_SME`) — ver {@link SmeOp64.TileLoadStore}.
        public static final int SME_TILE_LOAD_STORE = 200;
        /// B18.4: `LDR`/`STR` de vetor do array `ZA` (`FEAT_SME`) — ver {@link SmeOp64.ArrayLoadStore}.
        public static final int SME_ARRAY_LOAD_STORE = 201;
        /// B18.4: `LDR`/`STR` de `ZT0` (`FEAT_SME2`) — ver {@link SmeOp64.Zt0LoadStore}.
        public static final int SME_ZT0_LOAD_STORE = 202;
        /// B18.5: `ADDHA`/`ADDVA` e produto externo (`FMOPA`/`SMOPA`/`BMOPA`/…) sobre um tile de `ZA` — ver
        /// {@link SmeOp64.OuterProduct}.
        public static final int SME_OUTER_PRODUCT = 203;
        /// B18.5b: produto externo de quarto de tile (`MOP4`, `FEAT_SME_MOP4`) — ver {@link SmeOp64.Mop4}.
        public static final int SME_MOP4 = 204;
        /// B18.5b: produto externo esparso (`TMOP`, `FEAT_SME_TMOP`) — ver {@link SmeOp64.Tmop}.
        public static final int SME_TMOP = 205;
        /// B18.6: `ZERO` multi-vetor de `ZA` (`FEAT_SME2p1`) — ver {@link SmeOp64.ZeroArray}.
        public static final int SME_ZERO_ARRAY = 206;
        /// B18.6: `MOVT` de/para `ZT0` (`FEAT_SME2`/`FEAT_SME_LUTv2`) — ver {@link SmeOp64.Movt}.
        public static final int SME_MOVT = 207;
        /// B18.6: `LUTI2`/`LUTI4` (`FEAT_SME2`/`SME2p1`/`SME_LUTv2`) — ver {@link SmeOp64.Lut}.
        public static final int SME_LUT = 208;
        /// B18.7: SME2 multi-vetor "multiple-and-single" destrutivo (`SMAX_n1`/`FMAX_n1`/`ADD_n1`/…) — ver
        /// {@link SmeOp64.MultiVectorSingle}.
        public static final int SME_MULTI_VECTOR_SINGLE = 209;
        /// B18.9: SME2 multi-vetor com resultado em vetores do array `ZA` (`ADD_azz_n1`/`FDOT_n1`/`SMLALL_n1`/…) — ver
        /// {@link SmeOp64.ArrayMultiVector}.
        public static final int SME_ARRAY_MULTI_VECTOR = 210;
        /// B18.12: SME2 multi-vetor SVE "constructive" (conversões, estreitamento/alargamento, `ZIP`/`UZP`, `*CLAMP`,
        /// `SEL`) com resultado em registradores `Z` — ver {@link SmeOp64.Constructive}.
        public static final int SME_CONSTRUCTIVE = 211;
    }

    /// Contagem de ciclos agregada ao passo/bloco — mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.Cycle} (G4: nunca ganha guard condicional,
    /// já que A64 nem tem predicação geral para guardar).
    record Cycle(
            /// Quantidade de ciclos somada.
            int count) implements Ir64Op {
        @Override public int kind() { return Kind.CYCLE; }
        @Override public boolean execute(Aarch64Core core) { throw notAnInstruction(); }
    }

    /// Custo de busca da instrução original na memória do dispositivo — mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.Fetch} (G4).
    record Fetch(
            /// Endereço da instrução buscada.
            long address,
            /// Tamanho da instrução em bytes (sempre `4` em A64 — não há forma "curta" como
            /// Thumb; o campo existe para espelhar {@code IrOp.Fetch} e por uniformidade com o
            /// resto do executor).
            int sizeBytes) implements Ir64Op {
        @Override public int kind() { return Kind.FETCH; }
        @Override public boolean execute(Aarch64Core core) { throw notAnInstruction(); }
    }

    /// Instrução que `PSTATE.SM = 1` torna `UNDEFINED` quando `FEAT_SME_FA64` não está efetivo (B18.2):
    /// AdvSIMD vetorial, estruturas `LDn`/`STn`, cripto e `FJCVTZS` (as listas `FAIL` de
    /// `sme-fa64.decode` do QEMU, Apêndice E1.1 do DDI0616). O decoder embrulha a operação real em
    /// {@code inner} porque a decisão depende de `SM`, que só existe na EXECUÇÃO (o bloco é decodificado
    /// uma vez e pode rodar nos dois modos). Só é produzida em presets com `FEAT_SME` (G3).
    record StreamingRestricted(
            /// A operação que executa quando a restrição não se aplica.
            Ir64Op inner) implements Ir64Op {
        @Override public int kind() { return Kind.STREAMING_RESTRICTED; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeStreamingRestricted(core, this);
        }
    }
}
