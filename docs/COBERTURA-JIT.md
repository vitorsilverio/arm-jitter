# Cobertura de emissão JIT do arm-jitter

Tabela **gerada por medição**, não escrita à mão: cada `record` de IR vira uma
instância representativa (construtor canônico, valores default) que é passada para a
política de emissão nativa de cada backend. Regenerar com `./gerar-cobertura-jit.sh`
(ver o Javadoc de `dev.vitorsilverio.armjitter.truffle.JitCoverageReport`).

Esta é a **dimensão 2** (emissão JIT nativa) e a **dimensão 3** (Truffle) do
[`tasks/ROADMAP-100-ARM.md`](../tasks/ROADMAP-100-ARM.md). **Não substitui nem
duplica** `docs/COBERTURA-ISA.md`, que mede a dimensão 1 (decode + execução
interpretada): uma op pode estar `✅` lá e `❌` aqui — decodifica e roda no
interpretador, mas nenhum backend a compila.

| | significado |
|---|---|
| ✅ | a política emite bytecode nativo para a op (no caminho comum) |
| ⚠️ | nativa no caminho comum, recusada num caso específico e documentado (ver "Condicionais do lado 32 bits") — quando esse caso ocorre, o bloco INTEIRO cai no interpretador |
| ❌ | a política recusa a op: o bloco que a contém roda inteiro no interpretador |

**Política `WHOLE_BLOCK` nos dois pipelines.** `AsmNativePolicy.supports(IrBlock)` e
`Ir64NativePolicy.supports(Ir64Block)` fazem `for (op : block) if (!supports(op))
return false;` — **uma única op `❌` (ou `⚠️` no caso adverso) derruba o bloco
inteiro** para o interpretador. Não é degradação proporcional.

**O que ✅ NÃO significa:** que o bytecode emitido está correto. Isso é o
`BlockEquivalenceHarness` (invariante G1). Esta tabela mede só se a política ACEITA
emitir.

**Coluna "Truffle (64 bits)" inteira `❌`** — o módulo `truffle/` não tem NENHUM nó
de 64 bits; a coluna existe para tornar a ausência visível (task A10.8).

## Progresso

> **ASM 32 bits: 57 de 189** operações emitidas nativamente (mais 9 condicionais).
> **Truffle 32 bits: 66 de 189** operações com nó especializado.
> **ASM 64 bits: 53 de 212** `Kind` emitidos nativamente.
> **Truffle 64 bits: 0 de 212** — o backend não existe (A10.8).

A escada que fecha cada gap: `tasks/trilha-c-perf/c12-plano-jit-nativo.md` (ASM, C12.2-C12.8) e `tasks/trilha-a-truffle/a10-plano-truffle-completo.md` (Truffle, A10.3-A10.8).

> Conciliação com a medição do `ROADMAP-100-ARM.md` (2026-09-02): o `66/73` de ASM 32 bits daquele documento = as `57` `✅` incondicionais **mais** as `9` `⚠️` (nativas no caminho comum); as `116` linhas a mais aqui são os `Kind` de NEON por imediato de B13.7/B13.8 (todas `❌`). ASM 64 bits seguia `24/95`; `VECTOR_FP_CONVERT_PRECISION` (B19.4) levou o denominador a 96, ainda `❌` — daí `53/96`.

## Tabela A — pipeline de 32 bits

Linhas = os 189 `record` de `IrOp`, na ordem do `Kind`.

| Operação | `Kind` | ASM (`AsmNativePolicy`) | Truffle (`IrOpNodeFactory`) |
|---|---|---|---|
| `IntegerOp.Alu` | `ALU` | ⚠️ | ✅ |
| `IntegerOp.Multiply` | `MULTIPLY` | ✅ | ✅ |
| `IntegerOp.LongMultiply` | `LONG_MULTIPLY` | ✅ | ✅ |
| `IntegerOp.Saturating` | `SATURATING` | ⚠️ | ✅ |
| `IntegerOp.DspMultiply` | `DSP_MULTIPLY` | ⚠️ | ✅ |
| `SystemOp.PsrTransfer` | `PSR_TRANSFER` | ✅ | ✅ |
| `MemoryOp.Load` | `LOAD` | ⚠️ | ✅ |
| `MemoryOp.Store` | `STORE` | ⚠️ | ✅ |
| `MemoryOp.DoubleTransfer` | `DOUBLE_TRANSFER` | ⚠️ | ✅ |
| `MemoryOp.Swap` | `SWAP` | ✅ | ✅ |
| `MemoryOp.LoadLiteral` | `LOAD_LITERAL` | ✅ | ✅ |
| `MemoryOp.MultipleTransfer` | `MULTIPLE_TRANSFER` | ✅ | ✅ |
| `BranchOp.Branch` | `BRANCH` | ✅ | ✅ |
| `BranchOp.BranchExchange` | `BRANCH_EXCHANGE` | ⚠️ | ✅ |
| `BranchOp.ThumbBlPrefix` | `THUMB_BL_PREFIX` | ✅ | ✅ |
| `BranchOp.ThumbBlSuffix` | `THUMB_BL_SUFFIX` | ⚠️ | ✅ |
| `MemoryOp.Push` | `PUSH` | ✅ | ✅ |
| `MemoryOp.Pop` | `POP` | ✅ | ✅ |
| `SystemOp.Swi` | `SWI` | ✅ | ✅ |
| `SystemOp.Coprocessor` | `COPROCESSOR` | ✅ | ✅ |
| `SystemOp.Undefined` | `UNDEFINED` | ✅ | ✅ |
| `IrOp.Cycle` | `CYCLE` | ✅ | ✅ |
| `IrOp.Fetch` | `FETCH` | ✅ | ✅ |
| `IntegerOp.ParallelAlu` | `PARALLEL_ALU` | ✅ | ✅ |
| `IntegerOp.Sel` | `SEL` | ✅ | ✅ |
| `IntegerOp.Saturate` | `SATURATE` | ✅ | ✅ |
| `IntegerOp.AbsDiffSum` | `ABS_DIFF_SUM` | ✅ | ✅ |
| `MemoryOp.LoadExclusive` | `LOAD_EXCLUSIVE` | ✅ | ✅ |
| `MemoryOp.StoreExclusive` | `STORE_EXCLUSIVE` | ✅ | ✅ |
| `MemoryOp.ClearExclusive` | `CLEAR_EXCLUSIVE` | ✅ | ✅ |
| `SystemOp.ChangeProcessorState` | `CHANGE_PROCESSOR_STATE` | ✅ | ✅ |
| `SystemOp.SetEndianness` | `SET_ENDIANNESS` | ✅ | ✅ |
| `SystemOp.StoreReturnState` | `STORE_RETURN_STATE` | ✅ | ✅ |
| `SystemOp.ReturnFromException` | `RETURN_FROM_EXCEPTION` | ✅ | ✅ |
| `SystemOp.WaitForInterrupt` | `WAIT_FOR_INTERRUPT` | ✅ | ✅ |
| `IntegerOp.MoveTop` | `MOVE_TOP` | ✅ | ✅ |
| `SystemOp.MemoryBarrier` | `MEMORY_BARRIER` | ✅ | ✅ |
| `SystemOp.SetItState` | `SET_IT_STATE` | ✅ | ✅ |
| `BranchOp.TableBranch` | `TABLE_BRANCH` | ✅ | ✅ |
| `BranchOp.CompareBranchZero` | `COMPARE_BRANCH_ZERO` | ✅ | ✅ |
| `IntegerOp.BitFieldExtract` | `BIT_FIELD_EXTRACT` | ✅ | ✅ |
| `IntegerOp.BitFieldInsert` | `BIT_FIELD_INSERT` | ✅ | ✅ |
| `IntegerOp.BitReverse` | `BIT_REVERSE` | ✅ | ✅ |
| `IntegerOp.Divide` | `DIVIDE` | ✅ | ✅ |
| `VfpOp.Alu` | `VFP_ALU` | ✅ | ✅ |
| `VfpOp.MoveImmediate` | `VFP_MOVE_IMMEDIATE` | ✅ | ✅ |
| `VfpOp.Compare` | `VFP_COMPARE` | ✅ | ✅ |
| `VfpOp.Convert` | `VFP_CONVERT` | ✅ | ✅ |
| `VfpOp.Load` | `VFP_LOAD` | ✅ | ✅ |
| `VfpOp.Store` | `VFP_STORE` | ✅ | ✅ |
| `VfpOp.MultipleTransfer` | `VFP_MULTIPLE_TRANSFER` | ✅ | ✅ |
| `VfpOp.CoreTransfer` | `VFP_CORE_TRANSFER` | ⚠️ | ✅ |
| `VfpOp.CorePairTransfer` | `VFP_CORE_PAIR_TRANSFER` | ✅ | ✅ |
| `VfpOp.SystemTransfer` | `VFP_SYSTEM_TRANSFER` | ✅ | ✅ |
| `SystemOp.MProfileSystemRegister` | `M_PROFILE_SYSTEM_REGISTER` | ✅ | ✅ |
| `SystemOp.Breakpoint` | `BREAKPOINT` | ✅ | ✅ |
| `SystemOp.CoprocessorDouble` | `COPROCESSOR_DOUBLE` | ✅ | ✅ |
| `VfpOp.CorePairTransferSingle` | `VFP_CORE_PAIR_TRANSFER_SINGLE` | ✅ | ✅ |
| `VfpOp.ConvertFixed` | `VFP_CONVERT_FIXED` | ✅ | ✅ |
| `IntegerOp.DspDualMultiply` | `DSP_DUAL_MULTIPLY` | ✅ | ✅ |
| `IntegerOp.DspTopWordMultiply` | `DSP_TOP_WORD_MULTIPLY` | ✅ | ✅ |
| `SystemOp.Hvc` | `HVC` | ✅ | ✅ |
| `SystemOp.Smc` | `SMC` | ✅ | ✅ |
| `SystemOp.Eret` | `ERET` | ✅ | ✅ |
| `SystemOp.MrsBank` | `MRS_BANK` | ✅ | ✅ |
| `SystemOp.MsrBank` | `MSR_BANK` | ✅ | ✅ |
| `NeonIntegerOp.ThreeSame` | `NEON_THREE_SAME` | ❌ | ❌ |
| `NeonMoveOp.LoadStoreMultiple` | `NEON_LOAD_STORE_MULTIPLE` | ❌ | ❌ |
| `NeonMoveOp.LoadStoreSingle` | `NEON_LOAD_STORE_SINGLE` | ❌ | ❌ |
| `NeonMoveOp.LoadAllLanes` | `NEON_LOAD_ALL_LANES` | ❌ | ❌ |
| `NeonIntegerOp.Pairwise` | `NEON_PAIRWISE` | ❌ | ❌ |
| `NeonFpOp.FpThreeSame` | `NEON_FP_THREE_SAME` | ❌ | ❌ |
| `NeonFpOp.FpPairwise` | `NEON_FP_PAIRWISE` | ❌ | ❌ |
| `NeonIntegerOp.ShiftImmediate` | `NEON_SHIFT_IMMEDIATE` | ❌ | ❌ |
| `NeonIntegerOp.ShiftNarrowImmediate` | `NEON_SHIFT_NARROW_IMMEDIATE` | ❌ | ❌ |
| `NeonIntegerOp.ShiftWidenImmediate` | `NEON_SHIFT_WIDEN_IMMEDIATE` | ❌ | ❌ |
| `NeonFpOp.ConvertFixedPoint` | `NEON_CONVERT_FIXED_POINT` | ❌ | ❌ |
| `NeonMoveOp.ModifiedImmediate` | `NEON_MODIFIED_IMMEDIATE` | ❌ | ❌ |
| `NeonIntegerOp.Widening` | `NEON_WIDENING` | ❌ | ❌ |
| `NeonIntegerOp.Wide` | `NEON_WIDE` | ❌ | ❌ |
| `NeonIntegerOp.Narrow` | `NEON_NARROW` | ❌ | ❌ |
| `NeonIntegerOp.ThreeSameByElement` | `NEON_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `NeonIntegerOp.WideningByElement` | `NEON_WIDENING_BY_ELEMENT` | ❌ | ❌ |
| `NeonFpOp.FpThreeSameByElement` | `NEON_FP_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `NeonIntegerOp.Unary` | `NEON_UNARY` | ❌ | ❌ |
| `NeonIntegerOp.NarrowUnary` | `NEON_NARROW_UNARY` | ❌ | ❌ |
| `NeonFpOp.FpUnary` | `NEON_FP_UNARY` | ❌ | ❌ |
| `NeonFpOp.Complex` | `NEON_COMPLEX` | ❌ | ❌ |
| `NeonFpOp.ComplexByElement` | `NEON_COMPLEX_BY_ELEMENT` | ❌ | ❌ |
| `NeonIntegerOp.DotProduct` | `NEON_DOT_PRODUCT` | ❌ | ❌ |
| `NeonIntegerOp.DotProductByElement` | `NEON_DOT_PRODUCT_BY_ELEMENT` | ❌ | ❌ |
| `NeonMoveOp.SwapPermute` | `NEON_SWAP_PERMUTE` | ❌ | ❌ |
| `NeonMoveOp.Extract` | `NEON_EXTRACT` | ❌ | ❌ |
| `NeonMoveOp.TableLookup` | `NEON_TABLE_LOOKUP` | ❌ | ❌ |
| `NeonMoveOp.DuplicateScalar` | `NEON_DUPLICATE_SCALAR` | ❌ | ❌ |
| `NeonCryptoOp.Aes` | `NEON_CRYPTO_AES` | ❌ | ❌ |
| `NeonCryptoOp.Sha` | `NEON_CRYPTO_SHA` | ❌ | ❌ |
| `NeonFpOp.FpConvertPrecision` | `NEON_FP_CONVERT_PRECISION` | ❌ | ❌ |
| `NeonIntegerOp.MatrixMultiplyAccumulate` | `NEON_MATRIX_MULTIPLY_ACCUMULATE` | ❌ | ❌ |
| `NeonFpOp.FusedMultiplyAddLong` | `NEON_FUSED_MULTIPLY_ADD_LONG` | ❌ | ❌ |
| `NeonFpOp.FusedMultiplyAddLongByElement` | `NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT` | ❌ | ❌ |
| `NeonFpOp.DotProductBFloat16` | `NEON_DOT_PRODUCT_BFLOAT16` | ❌ | ❌ |
| `NeonFpOp.DotProductByElementBFloat16` | `NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16` | ❌ | ❌ |
| `NeonFpOp.MatrixMultiplyAccumulateBFloat16` | `NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16` | ❌ | ❌ |
| `NeonFpOp.FusedMultiplyAddLongBFloat16` | `NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16` | ❌ | ❌ |
| `NeonFpOp.FusedMultiplyAddLongByElementBFloat16` | `NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16` | ❌ | ❌ |
| `SystemOp.Nocp` | `NOCP` | ❌ | ❌ |
| `VfpOp.SysregMemoryTransfer` | `VFP_SYSREG_MEMORY_TRANSFER` | ❌ | ❌ |
| `SystemOp.SecureGateway` | `SECURE_GATEWAY` | ❌ | ❌ |
| `BranchOp.SecureBranchExchange` | `SECURE_BRANCH_EXCHANGE` | ❌ | ❌ |
| `VfpOp.VlldmVlstm` | `VLLDM_VLSTM` | ❌ | ❌ |
| `VfpOp.Vscclrm` | `VSCCLRM` | ❌ | ❌ |
| `BranchOp.LoopStart` | `LOOP_START` | ❌ | ❌ |
| `BranchOp.LoopEnd` | `LOOP_END` | ❌ | ❌ |
| `MvePredicationOp.AdvanceVpt` | `ADVANCE_VPT` | ❌ | ❌ |
| `MvePredicationOp.Vpst` | `VPST` | ❌ | ❌ |
| `MvePredicationOp.Vpnot` | `VPNOT` | ❌ | ❌ |
| `MvePredicationOp.Vpsel` | `VPSEL` | ❌ | ❌ |
| `MvePredicationOp.VprTransfer` | `VPR_TRANSFER` | ❌ | ❌ |
| `MveMoveOp.LoadStore` | `MVE_LOAD_STORE` | ❌ | ❌ |
| `MveMoveOp.WideningLoadStore` | `MVE_WIDENING_LOAD_STORE` | ❌ | ❌ |
| `MveMoveOp.GatherScatterOffset` | `MVE_GATHER_SCATTER_OFFSET` | ❌ | ❌ |
| `MveMoveOp.GatherScatterImmediate` | `MVE_GATHER_SCATTER_IMMEDIATE` | ❌ | ❌ |
| `MveMoveOp.InterleavedLoadStore` | `MVE_INTERLEAVED_LOAD_STORE` | ❌ | ❌ |
| `MveMoveOp.IncrementDup` | `MVE_INCREMENT_DUP` | ❌ | ❌ |
| `MveMoveOp.WrappingIncrementDup` | `MVE_WRAPPING_INCREMENT_DUP` | ❌ | ❌ |
| `MvePredicationOp.AdvanceEci` | `ADVANCE_ECI` | ❌ | ❌ |
| `MveIntegerOp.Vector2Op` | `MVE_VECTOR_2OP` | ❌ | ❌ |
| `MveIntegerOp.Vector2OpWidening` | `MVE_VECTOR_2OP_WIDENING` | ❌ | ❌ |
| `MveIntegerOp.VectorCarry` | `MVE_VECTOR_CARRY` | ❌ | ❌ |
| `MveIntegerOp.VectorComplexAdd` | `MVE_VECTOR_COMPLEX_ADD` | ❌ | ❌ |
| `MveIntegerOp.VectorAbsAccumulate` | `MVE_VECTOR_ABS_ACCUMULATE` | ❌ | ❌ |
| `MveFpOp.VectorFpAbsAccumulate` | `MVE_VECTOR_FP_ABS_ACCUMULATE` | ❌ | ❌ |
| `MveIntegerOp.VectorShiftWidenInterleaved` | `MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED` | ❌ | ❌ |
| `MveIntegerOp.VectorNarrowInterleaved` | `MVE_VECTOR_NARROW_INTERLEAVED` | ❌ | ❌ |
| `MveFpOp.VectorFpConvertPrecision` | `MVE_VECTOR_FP_CONVERT_PRECISION` | ❌ | ❌ |
| `MveFpOp.VectorFpComplexMultiply` | `MVE_VECTOR_FP_COMPLEX_MULTIPLY` | ❌ | ❌ |
| `MveIntegerOp.VectorDualMultiplyAddHigh` | `MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH` | ❌ | ❌ |
| `MveIntegerOp.VectorDoublingWideningMultiply` | `MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY` | ❌ | ❌ |
| `MveFpOp.VectorFpTwoOp` | `MVE_VECTOR_FP_TWO_OP` | ❌ | ❌ |
| `MveFpOp.VectorFpComplexAdd` | `MVE_VECTOR_FP_COMPLEX_ADD` | ❌ | ❌ |
| `MveFpOp.VectorFpComplexMultiplyAccumulate` | `MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE` | ❌ | ❌ |
| `MvePredicationOp.VectorCompare` | `MVE_VECTOR_COMPARE` | ❌ | ❌ |
| `MvePredicationOp.VectorCompareScalar` | `MVE_VECTOR_COMPARE_SCALAR` | ❌ | ❌ |
| `MveIntegerOp.VectorScalar` | `MVE_VECTOR_SCALAR` | ❌ | ❌ |
| `MveIntegerOp.VectorScalarWidening` | `MVE_VECTOR_SCALAR_WIDENING` | ❌ | ❌ |
| `MveFpOp.VectorFpScalar` | `MVE_VECTOR_FP_SCALAR` | ❌ | ❌ |
| `MveFpOp.VectorFpScalarFma` | `MVE_VECTOR_FP_SCALAR_FMA` | ❌ | ❌ |
| `MveIntegerOp.VectorScalarSpecial` | `MVE_VECTOR_SCALAR_SPECIAL` | ❌ | ❌ |
| `MveIntegerOp.VectorShiftImmediate` | `MVE_VECTOR_SHIFT_IMMEDIATE` | ❌ | ❌ |
| `MveIntegerOp.VectorShiftWidenImmediateInterleaved` | `MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED` | ❌ | ❌ |
| `MveIntegerOp.VectorShiftNarrowImmediateInterleaved` | `MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED` | ❌ | ❌ |
| `MveIntegerOp.VectorShiftLeftCarry` | `MVE_VECTOR_SHIFT_LEFT_CARRY` | ❌ | ❌ |
| `MveFpOp.VectorFpConvert` | `MVE_VECTOR_FP_CONVERT` | ❌ | ❌ |
| `MveFpOp.VectorFpConvertFixed` | `MVE_VECTOR_FP_CONVERT_FIXED` | ❌ | ❌ |
| `MveIntegerOp.VectorUnary` | `MVE_VECTOR_UNARY` | ❌ | ❌ |
| `MveFpOp.VectorFpUnary` | `MVE_VECTOR_FP_UNARY` | ❌ | ❌ |
| `MveMoveOp.VectorDup` | `MVE_VECTOR_DUP` | ❌ | ❌ |
| `MveMoveOp.MoveLanesGpr` | `MVE_MOVE_LANES_GPR` | ❌ | ❌ |
| `MveReductionOp.VectorAddAcrossVector` | `MVE_VECTOR_ADD_ACROSS_VECTOR` | ❌ | ❌ |
| `MveReductionOp.VectorAddAcrossVectorLong` | `MVE_VECTOR_ADD_ACROSS_VECTOR_LONG` | ❌ | ❌ |
| `MveReductionOp.VectorAbsoluteDifferenceAccumulate` | `MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE` | ❌ | ❌ |
| `MveMoveOp.VectorModifiedImmediate` | `MVE_VECTOR_MODIFIED_IMMEDIATE` | ❌ | ❌ |
| `MveReductionOp.VectorDualAccumulate` | `MVE_VECTOR_DUAL_ACCUMULATE` | ❌ | ❌ |
| `MveReductionOp.VectorDualAccumulateLong` | `MVE_VECTOR_DUAL_ACCUMULATE_LONG` | ❌ | ❌ |
| `MveReductionOp.VectorRoundingDualAccumulateHigh` | `MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH` | ❌ | ❌ |
| `MveReductionOp.VectorMinMaxAcrossVector` | `MVE_VECTOR_MIN_MAX_ACROSS_VECTOR` | ❌ | ❌ |
| `MveReductionOp.VectorFpMinMaxAcrossVector` | `MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR` | ❌ | ❌ |
| `IntegerOp.Crc32` | `CRC32` | ❌ | ❌ |
| `VfpOp.Select` | `VFP_SELECT` | ❌ | ❌ |
| `VfpOp.Round` | `VFP_ROUND` | ❌ | ❌ |
| `VfpOp.ConvertRounded` | `VFP_CONVERT_ROUNDED` | ❌ | ❌ |
| `VfpOp.MoveHalfLane` | `VFP_MOVE_HALF_LANE` | ❌ | ❌ |
| `VfpOp.AluHalf` | `VFP_ALU_HALF` | ❌ | ❌ |
| `VfpOp.MoveImmediateHalf` | `VFP_MOVE_IMMEDIATE_HALF` | ❌ | ❌ |
| `VfpOp.CompareHalf` | `VFP_COMPARE_HALF` | ❌ | ❌ |
| `VfpOp.SelectHalf` | `VFP_SELECT_HALF` | ❌ | ❌ |
| `VfpOp.RoundHalf` | `VFP_ROUND_HALF` | ❌ | ❌ |
| `VfpOp.ConvertRoundedHalf` | `VFP_CONVERT_ROUNDED_HALF` | ❌ | ❌ |
| `VfpOp.ConvertFixedHalf` | `VFP_CONVERT_FIXED_HALF` | ❌ | ❌ |
| `VfpOp.LoadHalf` | `VFP_LOAD_HALF` | ❌ | ❌ |
| `VfpOp.StoreHalf` | `VFP_STORE_HALF` | ❌ | ❌ |
| `NeonCryptoOp.ShaThree` | `NEON_CRYPTO_SHA_THREE_REGISTER` | ❌ | ❌ |
| `VfpOp.ConvertHalfPrecision` | `VFP_CONVERT_HALF_PRECISION` | ❌ | ❌ |
| `VfpOp.JavascriptConvert` | `VFP_JAVASCRIPT_CONVERT` | ❌ | ❌ |
| `MvePredicationOp.LoopClearTailPredication` | `LOOP_CLEAR_TAIL_PREDICATION` | ❌ | ❌ |
| `MvePredicationOp.Vctp` | `VCTP` | ❌ | ❌ |
| `IntegerOp.ClearMultiple` | `CLEAR_MULTIPLE` | ❌ | ❌ |
| `MveIntegerOp.WideShift` | `MVE_WIDE_SHIFT` | ❌ | ❌ |

### Condicionais do lado 32 bits

Cada `⚠️` acima recusa a emissão nativa só no caso listado; no resto é `✅`.

| Operação | Condição |
|---|---|
| `IntegerOp.Alu` | nativa exceto `dst=PC` com `setFlags` (restaura o CPSR a partir do SPSR) e `opcode=ORN` (Thumb-2, sem emissão nativa ainda) |
| `IntegerOp.Saturating` | nativa exceto `dst=PC` (`UNPREDICTABLE`/troca de bloco) |
| `IntegerOp.DspMultiply` | nativa exceto `dst=PC`, ou `SMLAWx`/`SMULWx` (`op2=2`) com `Rn=PC` |
| `MemoryOp.Load` | nativa exceto `LDRxT` (`unprivileged`: precisa de `AddressSpace#withUnprivilegedAccess`) |
| `MemoryOp.Store` | nativa exceto `STRxT` (`unprivileged`) |
| `MemoryOp.DoubleTransfer` | STRD (só lê registradores) sempre nativa; LDRD nativa exceto com `PC` no par carregado (sem tratamento de interworking no emissor) |
| `BranchOp.BranchExchange` | nativa exceto `BLX` (`link`: interworking + link register) |
| `BranchOp.ThumbBlSuffix` | nativa exceto a forma `BLX` (`exchange`: alinha o destino e troca para ARM) |
| `VfpOp.CoreTransfer` | nativa exceto `VMOV.F16` (`halfWidth`: transferência de 16 bits, sem preset com `HALF_PRECISION_FP` hoje) |

## Tabela B — pipeline de 64 bits

Linhas = os 212 `Ir64Op.Kind`. `Ir64NativePolicy` casa por `Kind` e **não tem carve-outs condicionais** (sem `⚠️` deste lado). A coluna Truffle é inteira `❌` (A10.8).

| `Kind` | ASM (`Ir64NativePolicy`) | Truffle |
|---|---|---|
| `ALU64` | ✅ | ❌ |
| `MOVE_WIDE` | ✅ | ❌ |
| `PC_RELATIVE` | ✅ | ❌ |
| `BRANCH64` | ✅ | ❌ |
| `COMPARE_BRANCH64` | ✅ | ❌ |
| `SVC` | ✅ | ❌ |
| `CYCLE` | ✅ | ❌ |
| `FETCH` | ✅ | ❌ |
| `LOAD64` | ✅ | ❌ |
| `STORE64` | ✅ | ❌ |
| `LOAD_STORE_PAIR` | ✅ | ❌ |
| `LOAD_LITERAL64` | ✅ | ❌ |
| `ALU_SHIFTED_REGISTER` | ✅ | ❌ |
| `ALU_EXTENDED_REGISTER` | ✅ | ❌ |
| `CONDITIONAL_SELECT` | ✅ | ❌ |
| `BITFIELD` | ✅ | ❌ |
| `MULTIPLY_ACCUMULATE` | ✅ | ❌ |
| `DIVIDE` | ✅ | ❌ |
| `LOAD_EXCLUSIVE` | ✅ | ❌ |
| `STORE_EXCLUSIVE` | ✅ | ❌ |
| `SYSTEM_REGISTER` | ❌ | ❌ |
| `SYSTEM_INSTRUCTION` | ❌ | ❌ |
| `EXCEPTION_RETURN` | ❌ | ❌ |
| `FP64_ALU` | ✅ | ❌ |
| `FP64_MOVE_IMMEDIATE` | ✅ | ❌ |
| `FP64_COMPARE` | ✅ | ❌ |
| `FP64_CONVERT` | ✅ | ❌ |
| `PRIVILEGED_CALL` | ❌ | ❌ |
| `CONDITIONAL_COMPARE` | ✅ | ❌ |
| `LOGICAL_SHIFTED_REGISTER` | ✅ | ❌ |
| `SHIFT_VARIABLE` | ✅ | ❌ |
| `LOAD_EXCLUSIVE_PAIR` | ✅ | ❌ |
| `STORE_EXCLUSIVE_PAIR` | ✅ | ❌ |
| `COMPARE_AND_SWAP` | ✅ | ❌ |
| `COMPARE_AND_SWAP_PAIR` | ✅ | ❌ |
| `ALU_WITH_CARRY` | ✅ | ❌ |
| `EXTRACT` | ✅ | ❌ |
| `DATA_PROCESSING_1_SOURCE` | ✅ | ❌ |
| `MULTIPLY_ACCUMULATE_LONG` | ✅ | ❌ |
| `MULTIPLY_HIGH` | ✅ | ❌ |
| `EVALUATE_INTO_FLAGS` | ✅ | ❌ |
| `ROTATE_INTO_FLAGS` | ✅ | ❌ |
| `CONVERT_FLAGS` | ✅ | ❌ |
| `INTERRUPT_MASK` | ❌ | ❌ |
| `BREAKPOINT` | ❌ | ❌ |
| `UNDEFINED_INSTRUCTION_TRAP` | ❌ | ❌ |
| `ADDRESS_TRANSLATE` | ❌ | ❌ |
| `FP64_MULTIPLY_ADD` | ✅ | ❌ |
| `FP64_CONDITIONAL_SELECT` | ✅ | ❌ |
| `FP64_CONDITIONAL_COMPARE` | ✅ | ❌ |
| `FP64_ROUND` | ✅ | ❌ |
| `FP64_INTEGER_CONVERT` | ✅ | ❌ |
| `FP64_GENERAL_REGISTER_MOVE` | ✅ | ❌ |
| `VECTOR_LOAD_STORE_MULTIPLE` | ✅ | ❌ |
| `VECTOR_LOAD_STORE_SINGLE` | ✅ | ❌ |
| `VECTOR_LOAD_SINGLE_REPLICATE` | ✅ | ❌ |
| `VECTOR_ARITHMETIC_THREE_SAME` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_PAIRWISE` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_WIDENING` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_WIDE` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_NARROW` | ❌ | ❌ |
| `VECTOR_ACROSS_LANES` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_UNARY` | ❌ | ❌ |
| `VECTOR_SCALAR_PAIRWISE_ADD` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_NARROW_UNARY` | ❌ | ❌ |
| `VECTOR_SHIFT_IMMEDIATE` | ❌ | ❌ |
| `VECTOR_SHIFT_NARROW_IMMEDIATE` | ❌ | ❌ |
| `VECTOR_SHIFT_WIDEN_IMMEDIATE` | ❌ | ❌ |
| `VECTOR_FP_ARITHMETIC_THREE_SAME` | ❌ | ❌ |
| `VECTOR_FP_ARITHMETIC_PAIRWISE` | ❌ | ❌ |
| `VECTOR_FP_ARITHMETIC_UNARY` | ❌ | ❌ |
| `VECTOR_EXTRACT` | ❌ | ❌ |
| `VECTOR_PERMUTE` | ❌ | ❌ |
| `VECTOR_TABLE_LOOKUP` | ❌ | ❌ |
| `VECTOR_FP_ACROSS_LANES` | ❌ | ❌ |
| `CRYPTO_AES` | ❌ | ❌ |
| `VECTOR_POLYNOMIAL_MULTIPLY_LONG` | ❌ | ❌ |
| `CRYPTO_SHA_THREE_REGISTER` | ❌ | ❌ |
| `CRYPTO_SHA_TWO_REGISTER` | ❌ | ❌ |
| `VECTOR_DUPLICATE_ELEMENT` | ❌ | ❌ |
| `VECTOR_DUPLICATE_GENERAL` | ❌ | ❌ |
| `VECTOR_INSERT_GENERAL` | ❌ | ❌ |
| `VECTOR_INSERT_ELEMENT` | ❌ | ❌ |
| `VECTOR_MOVE_ELEMENT` | ❌ | ❌ |
| `FP_LOAD64` | ✅ | ❌ |
| `FP_STORE64` | ✅ | ❌ |
| `FP_LOAD_STORE_PAIR` | ✅ | ❌ |
| `FP_LOAD_LITERAL64` | ✅ | ❌ |
| `VECTOR_ARITHMETIC_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_ARITHMETIC_WIDENING_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_FP_ARITHMETIC_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `CRYPTO_SHA3_FOUR_REGISTER` | ❌ | ❌ |
| `CRYPTO_SHA3_TWO_SOURCE_ROTATE` | ❌ | ❌ |
| `ATOMIC_MEMORY_OP` | ✅ | ❌ |
| `VECTOR_FP_CONVERT_FIXED_POINT` | ❌ | ❌ |
| `VECTOR_FP_CONVERT_PRECISION` | ❌ | ❌ |
| `POINTER_AUTH_GENERIC` | ❌ | ❌ |
| `ABS_GENERAL` | ❌ | ❌ |
| `VECTOR_DUPLICATE_ELEMENT_SCALAR` | ❌ | ❌ |
| `FP64_HIGH_HALF_MOVE` | ❌ | ❌ |
| `ADV_SIMD_MODIFIED_IMMEDIATE_64` | ❌ | ❌ |
| `VECTOR_LOOKUP_TABLE` | ❌ | ❌ |
| `FP64_CONVERT_TO_BF16` | ❌ | ❌ |
| `VECTOR_FP_DOT_PRODUCT_BFLOAT16` | ❌ | ❌ |
| `VECTOR_FP_DOT_PRODUCT_BFLOAT16_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16` | ❌ | ❌ |
| `VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_FP_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16` | ❌ | ❌ |
| `VECTOR_INTEGER_DOT_PRODUCT` | ❌ | ❌ |
| `VECTOR_INTEGER_DOT_PRODUCT_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_INTEGER_MATRIX_MULTIPLY_ACCUMULATE` | ❌ | ❌ |
| `CRYPTO_SHA512_THREE_REGISTER` | ❌ | ❌ |
| `CRYPTO_SHA512_TWO_REGISTER` | ❌ | ❌ |
| `CRYPTO_SM3_THREE_REGISTER` | ❌ | ❌ |
| `CRYPTO_SM3_FOUR_REGISTER` | ❌ | ❌ |
| `CRYPTO_SM3_THREE_REGISTER_IMM2` | ❌ | ❌ |
| `CRYPTO_SM4_ENCRYPT` | ❌ | ❌ |
| `CRYPTO_SM4_KEY_UPDATE` | ❌ | ❌ |
| `VECTOR_FP_MULTIPLY_ADD_LONG` | ❌ | ❌ |
| `VECTOR_FP_MULTIPLY_ADD_LONG_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_FP_CONVERT_TO_FP8` | ❌ | ❌ |
| `VECTOR_FP_CONVERT_FROM_FP8` | ❌ | ❌ |
| `CRC32` | ❌ | ❌ |
| `FP64_JAVASCRIPT_CONVERT` | ❌ | ❌ |
| `MEMORY_SET` | ❌ | ❌ |
| `MEMORY_COPY` | ❌ | ❌ |
| `ATOMIC_MEMORY_OP_PAIR` | ❌ | ❌ |
| `FP64_HALF_PRECISION_GENERAL_REGISTER_MOVE` | ❌ | ❌ |
| `FP64_CONVERT_HALF_PRECISION` | ❌ | ❌ |
| `MEMORY_TAG` | ❌ | ❌ |
| `MEMORY_TAG_MULTIPLE` | ❌ | ❌ |
| `STORE_PAIR_TAG` | ❌ | ❌ |
| `SUBTRACT_POINTER` | ❌ | ❌ |
| `INSERT_RANDOM_TAG` | ❌ | ❌ |
| `TAG_MASK_INSERT` | ❌ | ❌ |
| `MEMORY_SET_TAGGED` | ❌ | ❌ |
| `MIN_MAX_GENERAL` | ❌ | ❌ |
| `POINTER_AUTH_IN_PLACE` | ❌ | ❌ |
| `VECTOR_FP_COMPLEX_ADD` | ❌ | ❌ |
| `VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE` | ❌ | ❌ |
| `VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE_BY_ELEMENT` | ❌ | ❌ |
| `FP64_ROUND_RANGE_LIMITED` | ❌ | ❌ |
| `COMPARE_AND_BRANCH_REGISTER` | ❌ | ❌ |
| `COMPARE_AND_BRANCH_IMMEDIATE` | ❌ | ❌ |
| `VECTOR_FP_SCALE_BY_INT` | ❌ | ❌ |
| `VECTOR_FP_ABSOLUTE_MAX_MIN` | ❌ | ❌ |
| `VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG` | ❌ | ❌ |
| `VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT` | ❌ | ❌ |
| `VECTOR_FP8_DOT_PRODUCT` | ❌ | ❌ |
| `VECTOR_FP8_DOT_PRODUCT_BY_ELEMENT` | ❌ | ❌ |
| `STREAMING_MODE_CONTROL` | ❌ | ❌ |
| `STREAMING_RESTRICTED` | ❌ | ❌ |
| `SVE_PREDICATE_LOGICAL` | ❌ | ❌ |
| `SVE_PREDICATE_MISC` | ❌ | ❌ |
| `SVE_PARTITION_BREAK` | ❌ | ❌ |
| `SVE_PREDICATE_COUNT` | ❌ | ❌ |
| `SVE_ELEMENT_COUNT` | ❌ | ❌ |
| `SVE_INTEGER_UNPREDICATED` | ❌ | ❌ |
| `SVE_INTEGER_PREDICATED` | ❌ | ❌ |
| `SVE_INTEGER_REDUCTION` | ❌ | ❌ |
| `SVE_IMMEDIATE` | ❌ | ❌ |
| `SVE_MULTIPLY_INDEXED` | ❌ | ❌ |
| `SVE_ADDRESS` | ❌ | ❌ |
| `SVE_PERMUTE` | ❌ | ❌ |
| `SVE_COMPARE` | ❌ | ❌ |
| `SVE_SCALAR_COMPARE` | ❌ | ❌ |
| `SVE_PERMUTE_PREDICATED` | ❌ | ❌ |
| `SVE_FP_ARITHMETIC` | ❌ | ❌ |
| `SVE_FP_MULTIPLY_ADD` | ❌ | ❌ |
| `SVE_FP_COMPARE_REDUCE` | ❌ | ❌ |
| `SVE_FP_UNARY` | ❌ | ❌ |
| `SVE_LOAD` | ❌ | ❌ |
| `SVE_STORE` | ❌ | ❌ |
| `SVE_GATHER` | ❌ | ❌ |
| `SVE_COUNTER_PREDICATE` | ❌ | ❌ |
| `SVE_MULTI_VECTOR_MEMORY` | ❌ | ❌ |
| `SVE_MATCH` | ❌ | ❌ |
| `SVE_HISTOGRAM` | ❌ | ❌ |
| `SVE_LOOKUP_TABLE` | ❌ | ❌ |
| `SVE_PREDICATE_SELECT` | ❌ | ❌ |
| `SVE_CLAMP` | ❌ | ❌ |
| `SVE_FP_CONVERT_FP8` | ❌ | ❌ |
| `SVE_FP_CONVERT_TO_FP8` | ❌ | ❌ |
| `SVE_FP_PAIRWISE` | ❌ | ❌ |
| `SVE_FP_MATRIX_MULTIPLY` | ❌ | ❌ |
| `SVE_FP_CONVERT_ODD_ELEMENTS` | ❌ | ❌ |
| `SVE_FP_LOGB` | ❌ | ❌ |
| `SVE_FP8_FUSED_MULTIPLY_ADD_LONG` | ❌ | ❌ |
| `SVE_FP8_DOT_PRODUCT` | ❌ | ❌ |
| `SVE_FP_MULTIPLY_ADD_LONG_WIDEN` | ❌ | ❌ |
| `SVE_FP_MULTIPLY_ADD_LONG_WIDEN_BFLOAT16` | ❌ | ❌ |
| `SVE_FP_DOT_PRODUCT_WIDEN` | ❌ | ❌ |
| `SVE_FP_DOT_PRODUCT_WIDEN_BFLOAT16` | ❌ | ❌ |
| `SVE_CRYPTO_AES` | ❌ | ❌ |
| `SVE_CRYPTO_SM4_ENCRYPT` | ❌ | ❌ |
| `SVE_CRYPTO_SM4_KEY_UPDATE` | ❌ | ❌ |
| `SVE_CRYPTO_RAX1` | ❌ | ❌ |
| `SME_ZERO` | ❌ | ❌ |
| `SME_ZERO_ZT0` | ❌ | ❌ |
| `SME_MOVA` | ❌ | ❌ |
| `SME_TILE_LOAD_STORE` | ❌ | ❌ |
| `SME_ARRAY_LOAD_STORE` | ❌ | ❌ |
| `SME_ZT0_LOAD_STORE` | ❌ | ❌ |
| `SME_OUTER_PRODUCT` | ❌ | ❌ |
| `SME_MOP4` | ❌ | ❌ |
| `SME_TMOP` | ❌ | ❌ |
| `SME_ZERO_ARRAY` | ❌ | ❌ |
| `SME_MOVT` | ❌ | ❌ |
| `SME_LUT` | ❌ | ❌ |
| `SME_MULTI_VECTOR_SINGLE` | ❌ | ❌ |
| `SME_ARRAY_MULTI_VECTOR` | ❌ | ❌ |
| `SME_CONSTRUCTIVE` | ❌ | ❌ |

### `Kind` de 64 bits ainda interpretados

Entrada da escada C12.3-C12.6.

- `SYSTEM_REGISTER`
- `SYSTEM_INSTRUCTION`
- `EXCEPTION_RETURN`
- `PRIVILEGED_CALL`
- `INTERRUPT_MASK`
- `BREAKPOINT`
- `UNDEFINED_INSTRUCTION_TRAP`
- `ADDRESS_TRANSLATE`
- `VECTOR_ARITHMETIC_THREE_SAME`
- `VECTOR_ARITHMETIC_PAIRWISE`
- `VECTOR_ARITHMETIC_WIDENING`
- `VECTOR_ARITHMETIC_WIDE`
- `VECTOR_ARITHMETIC_NARROW`
- `VECTOR_ACROSS_LANES`
- `VECTOR_ARITHMETIC_UNARY`
- `VECTOR_SCALAR_PAIRWISE_ADD`
- `VECTOR_ARITHMETIC_NARROW_UNARY`
- `VECTOR_SHIFT_IMMEDIATE`
- `VECTOR_SHIFT_NARROW_IMMEDIATE`
- `VECTOR_SHIFT_WIDEN_IMMEDIATE`
- `VECTOR_FP_ARITHMETIC_THREE_SAME`
- `VECTOR_FP_ARITHMETIC_PAIRWISE`
- `VECTOR_FP_ARITHMETIC_UNARY`
- `VECTOR_EXTRACT`
- `VECTOR_PERMUTE`
- `VECTOR_TABLE_LOOKUP`
- `VECTOR_FP_ACROSS_LANES`
- `CRYPTO_AES`
- `VECTOR_POLYNOMIAL_MULTIPLY_LONG`
- `CRYPTO_SHA_THREE_REGISTER`
- `CRYPTO_SHA_TWO_REGISTER`
- `VECTOR_DUPLICATE_ELEMENT`
- `VECTOR_DUPLICATE_GENERAL`
- `VECTOR_INSERT_GENERAL`
- `VECTOR_INSERT_ELEMENT`
- `VECTOR_MOVE_ELEMENT`
- `VECTOR_ARITHMETIC_THREE_SAME_BY_ELEMENT`
- `VECTOR_ARITHMETIC_WIDENING_BY_ELEMENT`
- `VECTOR_FP_ARITHMETIC_THREE_SAME_BY_ELEMENT`
- `CRYPTO_SHA3_FOUR_REGISTER`
- `CRYPTO_SHA3_TWO_SOURCE_ROTATE`
- `VECTOR_FP_CONVERT_FIXED_POINT`
- `VECTOR_FP_CONVERT_PRECISION`
- `POINTER_AUTH_GENERIC`
- `ABS_GENERAL`
- `VECTOR_DUPLICATE_ELEMENT_SCALAR`
- `FP64_HIGH_HALF_MOVE`
- `ADV_SIMD_MODIFIED_IMMEDIATE_64`
- `VECTOR_LOOKUP_TABLE`
- `FP64_CONVERT_TO_BF16`
- `VECTOR_FP_DOT_PRODUCT_BFLOAT16`
- `VECTOR_FP_DOT_PRODUCT_BFLOAT16_BY_ELEMENT`
- `VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16`
- `VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16_BY_ELEMENT`
- `VECTOR_FP_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16`
- `VECTOR_INTEGER_DOT_PRODUCT`
- `VECTOR_INTEGER_DOT_PRODUCT_BY_ELEMENT`
- `VECTOR_INTEGER_MATRIX_MULTIPLY_ACCUMULATE`
- `CRYPTO_SHA512_THREE_REGISTER`
- `CRYPTO_SHA512_TWO_REGISTER`
- `CRYPTO_SM3_THREE_REGISTER`
- `CRYPTO_SM3_FOUR_REGISTER`
- `CRYPTO_SM3_THREE_REGISTER_IMM2`
- `CRYPTO_SM4_ENCRYPT`
- `CRYPTO_SM4_KEY_UPDATE`
- `VECTOR_FP_MULTIPLY_ADD_LONG`
- `VECTOR_FP_MULTIPLY_ADD_LONG_BY_ELEMENT`
- `VECTOR_FP_CONVERT_TO_FP8`
- `VECTOR_FP_CONVERT_FROM_FP8`
- `CRC32`
- `FP64_JAVASCRIPT_CONVERT`
- `MEMORY_SET`
- `MEMORY_COPY`
- `ATOMIC_MEMORY_OP_PAIR`
- `FP64_HALF_PRECISION_GENERAL_REGISTER_MOVE`
- `FP64_CONVERT_HALF_PRECISION`
- `MEMORY_TAG`
- `MEMORY_TAG_MULTIPLE`
- `STORE_PAIR_TAG`
- `SUBTRACT_POINTER`
- `INSERT_RANDOM_TAG`
- `TAG_MASK_INSERT`
- `MEMORY_SET_TAGGED`
- `MIN_MAX_GENERAL`
- `POINTER_AUTH_IN_PLACE`
- `VECTOR_FP_COMPLEX_ADD`
- `VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE`
- `VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE_BY_ELEMENT`
- `FP64_ROUND_RANGE_LIMITED`
- `COMPARE_AND_BRANCH_REGISTER`
- `COMPARE_AND_BRANCH_IMMEDIATE`
- `VECTOR_FP_SCALE_BY_INT`
- `VECTOR_FP_ABSOLUTE_MAX_MIN`
- `VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG`
- `VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT`
- `VECTOR_FP8_DOT_PRODUCT`
- `VECTOR_FP8_DOT_PRODUCT_BY_ELEMENT`
- `STREAMING_MODE_CONTROL`
- `STREAMING_RESTRICTED`
- `SVE_PREDICATE_LOGICAL`
- `SVE_PREDICATE_MISC`
- `SVE_PARTITION_BREAK`
- `SVE_PREDICATE_COUNT`
- `SVE_ELEMENT_COUNT`
- `SVE_INTEGER_UNPREDICATED`
- `SVE_INTEGER_PREDICATED`
- `SVE_INTEGER_REDUCTION`
- `SVE_IMMEDIATE`
- `SVE_MULTIPLY_INDEXED`
- `SVE_ADDRESS`
- `SVE_PERMUTE`
- `SVE_COMPARE`
- `SVE_SCALAR_COMPARE`
- `SVE_PERMUTE_PREDICATED`
- `SVE_FP_ARITHMETIC`
- `SVE_FP_MULTIPLY_ADD`
- `SVE_FP_COMPARE_REDUCE`
- `SVE_FP_UNARY`
- `SVE_LOAD`
- `SVE_STORE`
- `SVE_GATHER`
- `SVE_COUNTER_PREDICATE`
- `SVE_MULTI_VECTOR_MEMORY`
- `SVE_MATCH`
- `SVE_HISTOGRAM`
- `SVE_LOOKUP_TABLE`
- `SVE_PREDICATE_SELECT`
- `SVE_CLAMP`
- `SVE_FP_CONVERT_FP8`
- `SVE_FP_CONVERT_TO_FP8`
- `SVE_FP_PAIRWISE`
- `SVE_FP_MATRIX_MULTIPLY`
- `SVE_FP_CONVERT_ODD_ELEMENTS`
- `SVE_FP_LOGB`
- `SVE_FP8_FUSED_MULTIPLY_ADD_LONG`
- `SVE_FP8_DOT_PRODUCT`
- `SVE_FP_MULTIPLY_ADD_LONG_WIDEN`
- `SVE_FP_MULTIPLY_ADD_LONG_WIDEN_BFLOAT16`
- `SVE_FP_DOT_PRODUCT_WIDEN`
- `SVE_FP_DOT_PRODUCT_WIDEN_BFLOAT16`
- `SVE_CRYPTO_AES`
- `SVE_CRYPTO_SM4_ENCRYPT`
- `SVE_CRYPTO_SM4_KEY_UPDATE`
- `SVE_CRYPTO_RAX1`
- `SME_ZERO`
- `SME_ZERO_ZT0`
- `SME_MOVA`
- `SME_TILE_LOAD_STORE`
- `SME_ARRAY_LOAD_STORE`
- `SME_ZT0_LOAD_STORE`
- `SME_OUTER_PRODUCT`
- `SME_MOP4`
- `SME_TMOP`
- `SME_ZERO_ARRAY`
- `SME_MOVT`
- `SME_LUT`
- `SME_MULTI_VECTOR_SINGLE`
- `SME_ARRAY_MULTI_VECTOR`
- `SME_CONSTRUCTIVE`

