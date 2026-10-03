# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/);
o projeto segue [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

Próxima versão: **`2.0.0`** — o refactor estrutural (épico `E15`) quebra nomes de tipo da API pública.

### Adicionado
- **`IrOp#regUse()`/`IrOp#regDef()` e `IrOperand#regUse()`** (`E15.6`): cada operação do IR de 32 bits declara os
  registradores `r0..r15` que lê e escreve (bitmask; default `0` = não toca GPR). `DeadCodeEliminationPass` perdeu os
  três `switch` por tipo e virou só o laço de vivência; a predicação (`condition() != AL` não mata vivência) continua
  sendo política da passagem. Comportamento idêntico, conferido por oráculo diferencial contra a DCE anterior.
- **`IrOp#execute(IrBlockExecutor, ArmCore, int blockEndPc)`** (`E15.5`): cada operação do IR de 32 bits se roteia sozinha
  para o executor da sua família, numa ponte de uma linha; devolve se o PC mudou. Os dois `switch` de 189 casos de
  `IrBlockExecutor` saíram: `executeOp` equivale a `op.execute(this, core, blockEndPc)`, e o laço de `execute` mantém caso
  próprio só para `Cycle`/`Fetch` e para os 12 `Kind` do núcleo ARMv4T medidos como quentes (roteá-los pela ponte custava
  até 15% de throughput interpretado no gbaemu). Esquecer a ponte num record novo é erro de compilação; o
  `IllegalStateException("IrOp kind desconhecido")` deixou de existir. `IrBlockExecutor#neonExecutor()` é novo, ao lado
  dos accessors das outras famílias.
- **`Ir64Op#execute(Aarch64Core)`** (`E15.4`): cada operação do IR A64 executa a si mesma, delegando numa linha para o
  executor da sua família. É o único dispatch do interpretador A64 — o `switch` de 212 casos de `Ir64BlockExecutor` saiu —
  e esquecer a ponte num record novo é erro de compilação, não `IllegalStateException` em runtime. `Ir64Op.Cycle` e
  `Ir64Op.Fetch` lançam `IllegalStateException` (não são instrução). `Ir64BlockExecutor#executeOp` continua existindo e
  equivale a `op.execute(core)`; `step`/`run`/`executeBlock` não mudam.
- **Executores A64 por família, públicos** (`E15.4`): a semântica que vivia em métodos privados de `Ir64BlockExecutor`
  (2349 → 217 linhas) passou para `Ir64IntegerExecutor`, `Ir64MemoryExecutor`, `Ir64BranchExecutor`, `Ir64SystemExecutor`,
  `Ir64FpMemoryExecutor` e `Ir64VectorMemoryExecutor`. Essas seis classes e as 39 que já existiam no pacote `executor64`
  como package-private (`Ir64FpExecutor`, `Ir64Vector*Executor`, `Ir64CryptoExecutor`, `Sve*Ops`, `Sme*Ops`) agora são
  `public final`, com o método de entrada de cada record `public static` — é o que a ponte em `ir64` chama. São detalhe de
  execução: o ponto de entrada estável continua sendo `Ir64Op#execute`/`Ir64BlockExecutor`.

### Alterado
- **Quebra de nome no pacote `ir64`** (`E15.2`, exceção ao G3 aceita pelo usuário em 2026-10-02): os 209 records de instrução
  e os 7 enums que viviam aninhados em `Ir64Op` (5784 linhas) passaram para sub-interfaces seladas por família, um arquivo
  cada. `Ir64Op.Xxx` vira `<Família>.Yyy` conforme a tabela (o record perde o prefixo da família; sem seta = só mudou de
  interface). `Ir64Op.Kind` e seus valores, `Ir64Op.Cycle`, `Ir64Op.Fetch` e `Ir64Op.StreamingRestricted` não mudam;
  componentes, ordem e semântica de todo record são os mesmos. `switch` por padrão sobre `Ir64Op` continua exaustivo.

  | Interface | Records e enums (`Ir64Op.<antes>` → `<Interface>.<depois>`) |
  |---|---|
  | `IntegerOp64` | `Alu64` · `MoveWide` · `PcRelative` · `AluShiftedRegister` · `AluExtendedRegister` · `LogicalShiftedRegister` · `ShiftVariable` · `ConditionalSelect` · `Bitfield` · `MultiplyAccumulate` · `Divide` · `ConditionalCompare` · `AluWithCarry` · `Extract` · `DataProcessing1Source` · `MultiplyAccumulateLong` · `MultiplyHigh` · `EvaluateIntoFlags` · `RotateIntoFlags` · `ConvertFlags` · `PointerAuthGeneric` · `PointerAuthInPlace` · `AbsGeneral` · `Crc32` · `SubtractPointer` · `InsertRandomTag` · `TagMaskInsert` · `MinMaxGeneral` |
  | `MemoryOp64` | `Load64` · `Store64` · `LoadStorePair` · `LoadLiteral64` · `LoadExclusive` · `StoreExclusive` · `LoadExclusivePair` · `StoreExclusivePair` · `CompareAndSwap` · `CompareAndSwapPair` · `AtomicMemoryOp` · `AtomicMemoryOpPair` · `Ir64MopsPhase` · `MemorySet` · `MemoryCopy` · `Ir64MemoryTagOperation` · `MemoryTag` · `Ir64MemoryTagMultipleOperation` · `MemoryTagMultiple` · `StorePairTag` · `MemorySetTagged` |
  | `BranchOp64` | `Branch64` · `CompareBranch64` · `CompareAndBranchRegister` · `CompareAndBranchImmediate` |
  | `SystemOp64` | `Svc` · `SystemRegister` · `SystemInstruction` · `ExceptionReturn` · `PrivilegedCall` · `AddressTranslate` · `InterruptMask` · `Breakpoint` · `UndefinedInstructionTrap` · `StreamingModeControl` |
  | `FpOp64` | `FpLoad64` → `Load64` · `FpStore64` → `Store64` · `FpLoadStorePair` → `LoadStorePair` · `FpLoadLiteral64` → `LoadLiteral64` · `Fp64Operation` · `Fp64Alu` → `Alu` · `Fp64MoveImmediate` → `MoveImmediate` · `Fp64Compare` → `Compare` · `Fp64Conversion` · `Fp64Convert` → `Convert` · `Fp64MultiplyAdd` → `MultiplyAdd` · `Fp64ConditionalSelect` → `ConditionalSelect` · `Fp64ConditionalCompare` → `ConditionalCompare` · `Fp64RoundingDirection` · `Fp64Round` → `Round` · `Fp64RoundRangeLimited` → `RoundRangeLimited` · `Fp64IntegerConvert` → `IntegerConvert` · `Fp64GeneralRegisterMove` → `GeneralRegisterMove` · `Fp64JavascriptConvert` → `JavascriptConvert` · `Fp64HighHalfMove` → `HighHalfMove` · `Fp64ConvertToBf16` → `ConvertToBf16` · `Fp64HalfPrecisionGeneralRegisterMove` → `HalfPrecisionGeneralRegisterMove` · `Fp64HalfPrecisionConversion` · `Fp64ConvertHalfPrecision` → `ConvertHalfPrecision` |
  | `AdvSimdIntegerOp64` (⊂ `AdvSimdOp64`) | `VectorArithmeticThreeSame` → `ArithmeticThreeSame` · `VectorArithmeticThreeSameByElement` → `ArithmeticThreeSameByElement` · `VectorArithmeticPairwise` → `ArithmeticPairwise` · `VectorArithmeticWidening` → `ArithmeticWidening` · `VectorArithmeticWideningByElement` → `ArithmeticWideningByElement` · `VectorArithmeticWide` → `ArithmeticWide` · `VectorArithmeticNarrow` → `ArithmeticNarrow` · `VectorAcrossLanes` → `AcrossLanes` · `VectorArithmeticUnary` → `ArithmeticUnary` · `VectorScalarPairwiseAdd` → `ScalarPairwiseAdd` · `VectorArithmeticNarrowUnary` → `ArithmeticNarrowUnary` · `VectorShiftImmediate` → `ShiftImmediate` · `VectorShiftNarrowImmediate` → `ShiftNarrowImmediate` · `VectorShiftWidenImmediate` → `ShiftWidenImmediate` · `VectorPolynomialMultiplyLong` → `PolynomialMultiplyLong` · `VectorIntegerDotProduct` → `IntegerDotProduct` · `VectorIntegerDotProductByElement` → `IntegerDotProductByElement` · `VectorIntegerMatrixMultiplyAccumulate` → `IntegerMatrixMultiplyAccumulate` |
  | `AdvSimdFpOp64` (⊂ `AdvSimdOp64`) | `VectorFpArithmeticThreeSame` → `FpArithmeticThreeSame` · `VectorFpArithmeticThreeSameByElement` → `FpArithmeticThreeSameByElement` · `VectorFpComplexAdd` → `FpComplexAdd` · `VectorFpScaleByInt` → `FpScaleByInt` · `VectorFpAbsoluteMaxMin` → `FpAbsoluteMaxMin` · `VectorFp8FusedMultiplyAddLong` → `Fp8FusedMultiplyAddLong` · `VectorFp8FusedMultiplyAddLongByElement` → `Fp8FusedMultiplyAddLongByElement` · `VectorFp8DotProduct` → `Fp8DotProduct` · `VectorFp8DotProductByElement` → `Fp8DotProductByElement` · `VectorFpComplexMultiplyAccumulate` → `FpComplexMultiplyAccumulate` · `VectorFpComplexMultiplyAccumulateByElement` → `FpComplexMultiplyAccumulateByElement` · `VectorFpArithmeticPairwise` → `FpArithmeticPairwise` · `VectorFpArithmeticUnary` → `FpArithmeticUnary` · `VectorFpConvertFixedPoint` → `FpConvertFixedPoint` · `VectorFpConvertPrecision` → `FpConvertPrecision` · `VectorFpConvertToFp8` → `FpConvertToFp8` · `VectorFpConvertFromFp8` → `FpConvertFromFp8` · `VectorFpAcrossLanes` → `FpAcrossLanes` · `VectorFpDotProductBFloat16` → `FpDotProductBFloat16` · `VectorFpDotProductBFloat16ByElement` → `FpDotProductBFloat16ByElement` · `VectorFpMultiplyAddLongBFloat16` → `FpMultiplyAddLongBFloat16` · `VectorFpMultiplyAddLongBFloat16ByElement` → `FpMultiplyAddLongBFloat16ByElement` · `VectorFpMultiplyAddLong` → `FpMultiplyAddLong` · `VectorFpMultiplyAddLongByElement` → `FpMultiplyAddLongByElement` · `VectorFpMatrixMultiplyAccumulateBFloat16` → `FpMatrixMultiplyAccumulateBFloat16` |
  | `AdvSimdMoveOp64` (⊂ `AdvSimdOp64`) | `VectorLoadStoreMultiple` → `LoadStoreMultiple` · `VectorLoadStoreSingle` → `LoadStoreSingle` · `VectorLoadSingleReplicate` → `LoadSingleReplicate` · `VectorExtract` → `Extract` · `VectorPermute` → `Permute` · `VectorTableLookup` → `TableLookup` · `VectorDuplicateElement` → `DuplicateElement` · `VectorDuplicateGeneral` → `DuplicateGeneral` · `VectorInsertGeneral` → `InsertGeneral` · `VectorInsertElement` → `InsertElement` · `VectorMoveElement` → `MoveElement` · `VectorDuplicateElementScalar` → `DuplicateElementScalar` · `AdvSimdModifiedImmediate64` → `ModifiedImmediate64` · `VectorLookupTable` → `LookupTable` |
  | `CryptoOp64` (⊂ `AdvSimdOp64`) | `CryptoAes` → `Aes` · `CryptoShaThreeRegister` → `ShaThreeRegister` · `CryptoShaTwoRegister` → `ShaTwoRegister` · `CryptoSha3FourRegister` → `Sha3FourRegister` · `CryptoSha3TwoSourceRotate` → `Sha3TwoSourceRotate` · `CryptoSha512ThreeRegister` → `Sha512ThreeRegister` · `CryptoSha512TwoRegister` → `Sha512TwoRegister` · `CryptoSm3ThreeRegister` → `Sm3ThreeRegister` · `CryptoSm3FourRegister` → `Sm3FourRegister` · `CryptoSm3ThreeRegisterImm2` → `Sm3ThreeRegisterImm2` · `CryptoSm4Encrypt` → `Sm4Encrypt` · `CryptoSm4KeyUpdate` → `Sm4KeyUpdate` |
  | `SvePredicateOp64` (⊂ `SveOp64`) | `SvePredicateLogical` → `PredicateLogical` · `SvePredicateMisc` → `PredicateMisc` · `SvePartitionBreak` → `PartitionBreak` · `SvePredicateCount` → `PredicateCount` · `SveCompare` → `Compare` · `SveScalarCompare` → `ScalarCompare` · `SveCounterPredicate` → `CounterPredicate` · `SveMatch` → `Match` · `SvePredicateSelect` → `PredicateSelect` |
  | `SveIntegerOp64` (⊂ `SveOp64`) | `SveElementCount` → `ElementCount` · `SveIntegerUnpredicated` → `IntegerUnpredicated` · `SveIntegerPredicated` → `IntegerPredicated` · `SveIntegerReduction` → `IntegerReduction` · `SveImmediate` → `Immediate` · `SveMultiplyIndexed` → `MultiplyIndexed` · `SveAddress` → `Address` · `SvePermute` → `Permute` · `SvePermutePredicated` → `PermutePredicated` · `SveHistogram` → `Histogram` · `SveLookupTable` → `LookupTable` · `SveClamp` → `Clamp` · `SveCryptoAes` → `CryptoAes` · `SveCryptoSm4Encrypt` → `CryptoSm4Encrypt` · `SveCryptoSm4KeyUpdate` → `CryptoSm4KeyUpdate` · `SveCryptoRax1` → `CryptoRax1` |
  | `SveFpOp64` (⊂ `SveOp64`) | `SveFpArithmetic` → `FpArithmetic` · `SveFpMultiplyAdd` → `FpMultiplyAdd` · `SveFpCompareReduce` → `FpCompareReduce` · `SveFpUnary` → `FpUnary` · `SveFpConvertFp8` → `FpConvertFp8` · `SveFpConvertToFp8` → `FpConvertToFp8` · `SveFpPairwise` → `FpPairwise` · `SveFpMatrixMultiply` → `FpMatrixMultiply` · `SveFpConvertOddElements` → `FpConvertOddElements` · `SveFpLogB` → `FpLogB` · `SveFp8FusedMultiplyAddLong` → `Fp8FusedMultiplyAddLong` · `SveFp8DotProduct` → `Fp8DotProduct` · `SveFpMultiplyAddLongWiden` → `FpMultiplyAddLongWiden` · `SveFpMultiplyAddLongWidenBFloat16` → `FpMultiplyAddLongWidenBFloat16` · `SveFpDotProductWiden` → `FpDotProductWiden` · `SveFpDotProductWidenBFloat16` → `FpDotProductWidenBFloat16` |
  | `SveMemoryOp64` (⊂ `SveOp64`) | `SveLoad` → `Load` · `SveStore` → `Store` · `SveGather` → `Gather` · `SveMultiVectorMemory` → `MultiVectorMemory` |
  | `SmeOp64` | `SmeZero` → `Zero` · `SmeZeroZt0` → `ZeroZt0` · `SmeMova` → `Mova` · `SmeTileLoadStore` → `TileLoadStore` · `SmeArrayLoadStore` → `ArrayLoadStore` · `SmeZt0LoadStore` → `Zt0LoadStore` · `SmeOuterProduct` → `OuterProduct` · `SmeMop4` → `Mop4` · `SmeTmop` → `Tmop` · `SmeZeroArray` → `ZeroArray` · `SmeMovt` → `Movt` · `SmeLut` → `Lut` · `SmeMultiVectorSingle` → `MultiVectorSingle` · `SmeArrayMultiVector` → `ArrayMultiVector` · `SmeConstructive` → `Constructive` |
- **Quebra de nome no pacote `ir`** (`E15.3`): o mesmo corte da `E15.2`, agora no IR de 32 bits. Os 187 records de instrução
  e os 4 enums que viviam aninhados em `IrOp` (5036 linhas) passaram para sub-interfaces seladas por família, um arquivo
  cada. `IrOp.Xxx` vira `<Família>.Yyy` conforme a tabela (o record perde o prefixo `Vfp`/`Neon`/`NeonCrypto`/`Mve`; sem seta =
  só mudou de interface; os enums não mudam de nome). `IrOp.Kind` e seus valores, `IrOp.FPSCR_ROUNDING_DIRECTION_FIELD`,
  `IrOp.Cycle` e `IrOp.Fetch` não mudam; componentes, ordem e semântica de todo record são os mesmos. `switch` por padrão
  sobre `IrOp` continua exaustivo. A coluna "Operação" de `docs/COBERTURA-JIT.md` passa a mostrar `Família.Record`.

  | Interface | Records e enums (`IrOp.<antes>` → `<Interface>.<depois>`) |
  |---|---|
  | `IntegerOp` | `Alu` · `Multiply` · `LongMultiply` · `Saturating` · `Crc32` · `DspMultiply` · `DspDualMultiply` · `DspTopWordMultiply` · `ParallelAlu` · `Sel` · `Saturate` · `AbsDiffSum` · `MoveTop` · `BitFieldExtract` · `BitFieldInsert` · `BitReverse` · `Divide` · `ClearMultiple` |
  | `MemoryOp` | `Load` · `Store` · `LoadExclusive` · `StoreExclusive` · `ClearExclusive` · `DoubleTransfer` · `Swap` · `LoadLiteral` · `MultipleTransfer` · `Push` · `Pop` |
  | `BranchOp` | `Branch` · `BranchExchange` · `ThumbBlPrefix` · `ThumbBlSuffix` · `TableBranch` · `CompareBranchZero` · `SecureBranchExchange` · `LoopStart` · `LoopEnd` |
  | `SystemOp` | `PsrTransfer` · `Hvc` · `Smc` · `Eret` · `MrsBank` · `MsrBank` · `Swi` · `Breakpoint` · `Coprocessor` · `CoprocessorDouble` · `Undefined` · `ChangeProcessorState` · `SetEndianness` · `StoreReturnState` · `ReturnFromException` · `WaitForInterrupt` · `MemoryBarrier` · `SetItState` · `MProfileSystemRegister` · `Nocp` · `SecureGateway` |
  | `VfpOp` | `VfpOperation` · `VfpAlu` → `Alu` · `VfpSelect` → `Select` · `VfpRound` → `Round` · `VfpConvertRounded` → `ConvertRounded` · `VfpMoveHalfLane` → `MoveHalfLane` · `VfpAluHalf` → `AluHalf` · `VfpMoveImmediateHalf` → `MoveImmediateHalf` · `VfpCompareHalf` → `CompareHalf` · `VfpSelectHalf` → `SelectHalf` · `VfpRoundHalf` → `RoundHalf` · `VfpConvertRoundedHalf` → `ConvertRoundedHalf` · `VfpConvertFixedHalf` → `ConvertFixedHalf` · `VfpLoadHalf` → `LoadHalf` · `VfpStoreHalf` → `StoreHalf` · `HalfPrecisionConversion` · `VfpConvertHalfPrecision` → `ConvertHalfPrecision` · `VfpJavascriptConvert` → `JavascriptConvert` · `VfpMoveImmediate` → `MoveImmediate` · `VfpCompare` → `Compare` · `VfpConversion` · `VfpConvert` → `Convert` · `VfpLoad` → `Load` · `VfpStore` → `Store` · `VfpMultipleTransfer` → `MultipleTransfer` · `VfpCoreTransfer` → `CoreTransfer` · `VfpCorePairTransfer` → `CorePairTransfer` · `VfpSystemTransfer` → `SystemTransfer` · `VfpCorePairTransferSingle` → `CorePairTransferSingle` · `VfpConvertFixed` → `ConvertFixed` · `VfpSysregMemoryTransfer` → `SysregMemoryTransfer` · `VlldmVlstm` · `Vscclrm` |
  | `NeonIntegerOp` (⊂ `NeonOp`) | `NeonThreeSame` → `ThreeSame` · `NeonPairwise` → `Pairwise` · `NeonShiftImmediate` → `ShiftImmediate` · `NeonShiftNarrowImmediate` → `ShiftNarrowImmediate` · `NeonShiftWidenImmediate` → `ShiftWidenImmediate` · `NeonWidening` → `Widening` · `NeonWide` → `Wide` · `NeonNarrow` → `Narrow` · `NeonThreeSameByElement` → `ThreeSameByElement` · `NeonWideningByElement` → `WideningByElement` · `NeonUnary` → `Unary` · `NeonNarrowUnary` → `NarrowUnary` · `NeonDotProduct` → `DotProduct` · `NeonDotProductByElement` → `DotProductByElement` · `NeonMatrixMultiplyAccumulate` → `MatrixMultiplyAccumulate` |
  | `NeonFpOp` (⊂ `NeonOp`) | `NeonFpThreeSame` → `FpThreeSame` · `NeonFpPairwise` → `FpPairwise` · `NeonConvertFixedPoint` → `ConvertFixedPoint` · `NeonFpThreeSameByElement` → `FpThreeSameByElement` · `NeonFpUnary` → `FpUnary` · `NeonFpConvertPrecision` → `FpConvertPrecision` · `NeonComplex` → `Complex` · `NeonComplexByElement` → `ComplexByElement` · `NeonFusedMultiplyAddLong` → `FusedMultiplyAddLong` · `NeonFusedMultiplyAddLongByElement` → `FusedMultiplyAddLongByElement` · `NeonDotProductBFloat16` → `DotProductBFloat16` · `NeonDotProductByElementBFloat16` → `DotProductByElementBFloat16` · `NeonMatrixMultiplyAccumulateBFloat16` → `MatrixMultiplyAccumulateBFloat16` · `NeonFusedMultiplyAddLongBFloat16` → `FusedMultiplyAddLongBFloat16` · `NeonFusedMultiplyAddLongByElementBFloat16` → `FusedMultiplyAddLongByElementBFloat16` |
  | `NeonMoveOp` (⊂ `NeonOp`) | `NeonLoadStoreMultiple` → `LoadStoreMultiple` · `NeonLoadStoreSingle` → `LoadStoreSingle` · `NeonLoadAllLanes` → `LoadAllLanes` · `NeonModifiedImmediate` → `ModifiedImmediate` · `NeonSwapPermute` → `SwapPermute` · `NeonExtract` → `Extract` · `NeonTableLookup` → `TableLookup` · `NeonDuplicateScalar` → `DuplicateScalar` |
  | `NeonCryptoOp` (⊂ `NeonOp`) | `NeonCryptoAes` → `Aes` · `NeonCryptoSha` → `Sha` · `NeonCryptoShaThree` → `ShaThree` |
  | `MvePredicationOp` (⊂ `MveOp`) | `LoopClearTailPredication` · `Vctp` · `AdvanceVpt` · `Vpst` · `Vpnot` · `Vpsel` · `VprTransfer` · `AdvanceEci` · `MveVectorCompare` → `VectorCompare` · `MveVectorCompareScalar` → `VectorCompareScalar` |
  | `MveMoveOp` (⊂ `MveOp`) | `MveLoadStore` → `LoadStore` · `MveWideningLoadStore` → `WideningLoadStore` · `MveGatherScatterOffset` → `GatherScatterOffset` · `MveGatherScatterImmediate` → `GatherScatterImmediate` · `MveInterleavedLoadStore` → `InterleavedLoadStore` · `MveIncrementDup` → `IncrementDup` · `MveWrappingIncrementDup` → `WrappingIncrementDup` · `MveVectorDup` → `VectorDup` · `MveMoveLanesGpr` → `MoveLanesGpr` · `MveVectorModifiedImmediate` → `VectorModifiedImmediate` |
  | `MveIntegerOp` (⊂ `MveOp`) | `WideShiftOperation` · `MveWideShift` → `WideShift` · `MveVector2Op` → `Vector2Op` · `MveVector2OpWidening` → `Vector2OpWidening` · `MveVectorCarry` → `VectorCarry` · `MveVectorComplexAdd` → `VectorComplexAdd` · `MveVectorAbsAccumulate` → `VectorAbsAccumulate` · `MveVectorShiftWidenInterleaved` → `VectorShiftWidenInterleaved` · `MveVectorNarrowInterleaved` → `VectorNarrowInterleaved` · `MveVectorDualMultiplyAddHigh` → `VectorDualMultiplyAddHigh` · `MveVectorDoublingWideningMultiply` → `VectorDoublingWideningMultiply` · `MveVectorScalar` → `VectorScalar` · `MveVectorScalarWidening` → `VectorScalarWidening` · `MveVectorScalarSpecial` → `VectorScalarSpecial` · `MveVectorShiftImmediate` → `VectorShiftImmediate` · `MveVectorShiftWidenImmediateInterleaved` → `VectorShiftWidenImmediateInterleaved` · `MveVectorShiftNarrowImmediateInterleaved` → `VectorShiftNarrowImmediateInterleaved` · `MveVectorShiftLeftCarry` → `VectorShiftLeftCarry` · `MveVectorUnary` → `VectorUnary` |
  | `MveFpOp` (⊂ `MveOp`) | `MveVectorFpAbsAccumulate` → `VectorFpAbsAccumulate` · `MveVectorFpConvertPrecision` → `VectorFpConvertPrecision` · `MveVectorFpComplexMultiply` → `VectorFpComplexMultiply` · `MveVectorFpTwoOp` → `VectorFpTwoOp` · `MveVectorFpComplexAdd` → `VectorFpComplexAdd` · `MveVectorFpComplexMultiplyAccumulate` → `VectorFpComplexMultiplyAccumulate` · `MveVectorFpScalar` → `VectorFpScalar` · `MveVectorFpScalarFma` → `VectorFpScalarFma` · `MveVectorFpConvert` → `VectorFpConvert` · `MveVectorFpConvertFixed` → `VectorFpConvertFixed` · `MveVectorFpUnary` → `VectorFpUnary` |
  | `MveReductionOp` (⊂ `MveOp`) | `MveVectorAddAcrossVector` → `VectorAddAcrossVector` · `MveVectorAddAcrossVectorLong` → `VectorAddAcrossVectorLong` · `MveVectorAbsoluteDifferenceAccumulate` → `VectorAbsoluteDifferenceAccumulate` · `MveVectorDualAccumulate` → `VectorDualAccumulate` · `MveVectorDualAccumulateLong` → `VectorDualAccumulateLong` · `MveVectorRoundingDualAccumulateHigh` → `VectorRoundingDualAccumulateHigh` · `MveVectorMinMaxAcrossVector` → `VectorMinMaxAcrossVector` · `MveVectorFpMinMaxAcrossVector` → `VectorFpMinMaxAcrossVector` |
- **`AsmRuntimeHelpers` dividida por família** (`E15.7`): a classe pública de helpers chamados pelo bytecode ASM de 32 bits
  (1161 linhas) deixa de existir. Os métodos mantêm nome, assinatura e semântica e passam para cinco classes públicas do
  mesmo pacote (`codegen.jvm`), por seção:

  | Classe nova | Seções de `AsmRuntimeHelpers` |
  |---|---|
  | `AsmFlagHelpers` | condição (`evalCond`, `condXx`, `superblockKeepRunning`) · flags ALU (`updateXxxFlags`) · shifts (`doLsl`/... e `doLslS`/...) · operando shifted-register (`shiftedOperand`, `shiftedOperandCarry`) |
  | `AsmMemoryHelpers` | memória (`loadXxx`/`storeXxx`, `*Crossed`, BE8) · LDM/STM (`executeMultipleTransfer`; `executePush`/`executePop` saíram — nenhum emissor os chamava desde que PUSH/POP são desenrolados inline) · exclusivos (`loadExclusive`, `markExclusive`, `exclusiveMonitorCovers`, `clearExclusiveMonitor`) |
  | `AsmIntegerHelpers` | ARMv5TE (`saturating`, `dspXxx`) · ARMv6 (`extendByte16`, `reverseHalfwords`, `reverseSignedHalfword`, `parallelAlu`, `sel`, `saturate`, `absDiffSum`) |
  | `AsmSystemHelpers` | branches (`branchExchange`, `loadToPc`, `loadToPcArm4`, `loadToPcArm5`) · PSR · SWI · coprocessador · undefined |
  | `AsmVfpHelpers` | VFP (`packDoubleWords`, `vfpAluCold`, `executeVfpXxx`) |
- **`AsmNativePolicy` derivada do registro de emissores** (`E15.7`): o `switch` de 189 casos deu lugar a um registro
  `record` → emissor (+ predicado, contagem de acessos ao register cache, spill), declarado por família
  (`AsmAluEmitter`/`AsmIntegerEmitter`/`AsmMemoryEmitter`/`AsmControlEmitter`/`AsmVfpEmitter`, package-private). Política e
  compilador não podem mais divergir; `supports(IrOp)`, `supports(IrBlock)` e `supportedAluOpcodes()` mantêm assinatura e
  resultado (o bytecode gerado pelo `AsmBlockCompiler` é byte a byte o mesmo).
- **MVE sai de `IrSystemExecutor`** (`E15.8`): os 57 métodos públicos `execute*` do MVE (2516 → 392 linhas no
  executor de sistema) passam, com o mesmo nome, assinatura e semântica, para cinco classes públicas de
  `codegen.executor`, uma por família do IR. Quatro são estáticas (sem estado: a JVM só as carrega na primeira op MVE);
  `IrMveMoveExecutor` usa os acessos à memória da arquitetura e vem de `IrBlockExecutor#mveMoveExecutor()` (novo,
  criado na primeira chamada). `executeClrm` (`IntegerOp.ClearMultiple`) e o resto do sistema ficam em
  `IrSystemExecutor`.

  | Classe nova | Métodos (`execute…`) que saíram de `IrSystemExecutor` |
  |---|---|
  | `IrMvePredicationExecutor` (estática) | `Vpst` · `Vpnot` · `Vpsel` · `Lctp` · `Vctp` · `AdvanceVpt` · `AdvanceEci` · `VprTransfer` · `MveVectorCompare` · `MveVectorCompareScalar` |
  | `IrMveMoveExecutor` (instância) | `MveLoadStore` · `MveWideningLoadStore` · `MveGatherScatterOffset` · `MveGatherScatterImmediate` · `MveInterleavedLoadStore` · `MveIncrementDup` · `MveWrappingIncrementDup` · `MveVectorDup` · `MveMoveLanesGpr` · `MveVectorModifiedImmediate` |
  | `IrMveIntegerExecutor` (estática) | `MveWideShift` · `MveVector2Op` · `MveVector2OpWidening` · `MveVectorCarry` · `MveVectorComplexAdd` · `MveVectorAbsAccumulate` · `MveVectorShiftWidenInterleaved` · `MveVectorShiftImmediate` · `MveVectorShiftWidenImmediateInterleaved` · `MveVectorShiftNarrowImmediateInterleaved` · `MveVectorShiftLeftCarry` · `MveVectorUnary` · `MveVectorNarrowInterleaved` · `MveVectorDualMultiplyAddHigh` · `MveVectorDoublingWideningMultiply` · `MveVectorScalar` · `MveVectorScalarWidening` · `MveVectorScalarSpecial` |
  | `IrMveFpExecutor` (estática) | `MveVectorFpAbsAccumulate` · `MveVectorFpConvert` · `MveVectorFpConvertFixed` · `MveVectorFpUnary` · `MveVectorFpConvertPrecision` · `MveVectorFpComplexMultiply` · `MveVectorFpTwoOp` · `MveVectorFpComplexAdd` · `MveVectorFpComplexMultiplyAccumulate` · `MveVectorFpScalar` · `MveVectorFpScalarFma` |
  | `IrMveReductionExecutor` (estática) | `MveVectorAddAcrossVector` · `MveVectorAddAcrossVectorLong` · `MveVectorAbsoluteDifferenceAccumulate` · `MveVectorDualAccumulate` · `MveVectorDualAccumulateLong` · `MveVectorRoundingDualAccumulateHigh` · `MveVectorMinMaxAcrossVector` · `MveVectorFpMinMaxAcrossVector` |

### Corrigido
- **`DeadCodeEliminationPass` apagava escritas de registrador ainda lidas** (`E15.6b`, só no JIT de 32 bits — ASM e
  Truffle; o interpretador acertava): dezenas de records de `IrOp` liam GPR sem declarar em `regUse()`, e a DCE removia a
  `IntegerOp.Alu` anterior que escrevia aquele registrador quando ele era sobrescrito logo depois. Afetados, entre outros:
  `CRC32*` (`rn`/`rm`), `SMLALxy` (RdHi), `VLDn`/`VSTn` (base e `Rm` de pós-índice), `CPS` com troca de modo (banco
  `r8`-`r14`), `SRS` (`LR`/`SP`), `VLDR_hp`/`VSTR_hp`, `VMOV` de dois GPR para `S`, os laços `DLS`/`WLS`/`LE`, `BXNS`/`BLXNS`,
  `SG`, `ERET`, `MRS`/`MSR` bancados, `RFE` e quase todo o MVE: os escalares (`Rn`/`Rm`/`Rt`/`Rda`) e — sem componente
  nenhum no record — o `LR`, contador de tail-predication que toda op MVE predicada lê (`MveOp#regUse()` passa a
  devolver o `LR` por default). Um teste-guarda por reflexão (`IrOpRegisterUseGuardTest`) faz um record novo com
  componente de nome de GPR e sem `regUse()` falhar no build.
- **AdvSIMD `FEAT_FP16` "three same" aceitava `opcode=01ooo`** (`E15.9`): o decoder A64 ignorava o `bit14` e devolvia
  `FADD`/`FMUL`/`FCMEQ`/... de meia precisão para palavras que o ARM não aloca (ex.: `0x4e405400`, `undefined` no
  `objdump`). Agora essas palavras não decodificam como FP16. O espaço AdvSIMD `bit21=0` com feature (RDM, FP16, FP8,
  FAMINMAX, FP8FMA, FP8DOT2/4, FCMA) passou a ser uma tabela de encodings filtrada pelo preset (`DecodeTable`, interno);
  o resto do comportamento é idêntico, conferido por oráculo sobre o subespaço inteiro × 256 combinações de features.

## [1.4.0] — 2026-10-01

Cobertura de ISA completa: `docs/COBERTURA-ISA.md` mede 100% (29619/29619 células aplicáveis decodificam).
A tabela prova que o decoder reconhece os encodings, não que a semântica está correta; PMSA/MPU (B20), ARMv1-v3 (B21),
JIT nativo e Truffle seguem como trabalho aberto no `ROADMAP-100-ARM.md`.

### Corrigido
- **SVE, gate `SVE2` indevido + falso-negativo do medidor** (`B17.29`): `SMMLA`/`USMMLA`/`UMMLA` (só exigem `SVE`+`FEAT_I8MM`) não dependem mais de `FEAT_SVE2` em `Aarch64Sve2WideningDecoder`/`Aarch64Sve2IntegerDecoder`. `DecodeTreeSpec` (ferramenta de medição) ganhou campo sintético para bits só alcançáveis via `%extrator` do QEMU, antes sempre `0` — triados os ~30 `❌` restantes de `sve.decode`, nenhum gap real. `docs/COBERTURA-ISA.md`: global 98%→99%, A64 `ARMv9.0-A`-`ARMv9.5-A` 95%→99%, SVE/SVE2 84-85%→96-97%.

### Adicionado
- **SVE2 inteiro II, metade 21b** (`B17.21b`): `#### SVE2 Widening Integer Arithmetic` (add/sub/abs-diff long, interleaved long, add/sub wide, multiply long incl. `PMULL`, `SSHLL`/`USHLL`, `EORBT`/`EORTB`, `SMMLA`/`USMMLA`/`UMMLA`, `BEXT`/`BDEP`/`BGRP`) e `#### SVE2 Narrowing` (extract narrow, shift right narrow, add/sub narrow high part, `SQCVTN`/`UQCVTN`/`SQCVTUN`) — 76 encodings; features novas `SVE_BITPERM` e `SVE_PMULL128`, `ID_AA64ZFR0_EL1` anuncia `AES`/`BitPerm`/`I8MM`. Tabela de ISA inalterada.
- **SVE2 inteiro II, metade 21a** (`B17.21a`): shift por vetor saturante/arredondado (`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL` e as formas reversas), halving (`SHADD`/`SRHADD`/`SHSUB` e sem sinal), saturating add/sub (incl. `SUQADD`/`USQADD`) e `#### SVE2 Accumulate` (`CADD`/`SQCADD`, `SABAL*`/`UABAL*`, `ADCL*`/`SBCL*`, `SSRA`/`USRA`/`SRSRA`/`URSRA`, `SRI`/`SLI`, `SABA`/`UABA`) — 46 encodings sob `FEAT_SVE2`. Tabela de ISA inalterada.
- **SVE2 inteiro I** (`B17.20`): `MUL`/`SMULH`/`UMULH`/`PMUL`/`SQDMULH`/`SQRDMULH` não-predicados, `SADALP`/`UADALP`, `URECPE`/`URSQRTE`/`SQABS`/`SQNEG` (`_m` sob SVE2, `_z` sob SVE2p2) e as pairwise predicadas `ADDP`/`SMAXP`/`UMAXP`/`SMINP`/`UMINP` (21 encodings, `Aarch64Sve2IntegerDecoder`). Tabela de ISA inalterada.
- **SVE2.1, predicado-como-contador** (`B17.28`): o modelo do contador (`PNn`, `n = 8..15` = os 16 bits baixos de `Pn` lidos como contagem + `invert` + tamanho de elemento, `SveCounterOps.Counter`/`encode`) e os 24 encodings que o usam — `PTRUE`/`CNTP` (forma contador), `PEXT` (1 e 2 registradores), `WHILELT`/`LO`/`GE`/`HI`/… com destino `PNn` sobre 2 ou 4 vetores e os 16 `LD1`/`ST1` multi-vetor contíguos (2 e 4 registradores, escalar+escalar e escalar+imediato, consecutivos e `_stride`). `FEAT_SVE2p1` ou `FEAT_SME2`; as 8 formas `_stride` são SME2 puras e sempre exigem modo streaming. Os `LD1`/`ST1` vivem no espaço `bits[28:26] = 000` (o da SME): `Aarch64Decoder` ganhou essa entrada. `Ir64Op.SveCounterPredicate` (Kind 174) + `SveMultiVectorMemory` (Kind 175) + `SveCounterOps`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, gather loads** (`B17.19`): `LD1_zprz` (escalar + vetor de deslocamentos: 32 bits com `UXTW`/`SXTW`, 64 bits desempacotado e com deslocamento de 64 bits, escalado ou não), `LD1_zpiz` (vetor de endereços + imediato) e `LD1Q` (`FEAT_SVE2p1`, vetor + escalar), cada um nas duas formas `LD1*` e first-fault `LDFF1*` (o bit `ff` dobra cada linha), mais os 3 `PRF_ns` de 64 bits (hint) — as 17 linhas (`Ir64Op.SveGather`, Kind 173; `SveGatherOps`, reusando o `FFR` da `SveLoadOps`). Elemento inativo não acessa memória; num `LD1*` o aborto é o do elemento de menor índice e o destino fica intacto. Todo gather é ilegal em modo streaming. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, stores e scatter** (`B17.18`): `ST1` contíguo (todos os pares `msz`/`esz` com `msz <= esz`, escalar+escalar e escalar+imediato), `STNT1` (= `ST1`, sem modelo de cache), `ST2`/`ST3`/`ST4` entrelaçados, `ST1W`/`ST1D` e `ST[234]Q` de elemento de 128 bits (`FEAT_SVE2p1`), `STR` de vetor e de predicado e o scatter `ST1_zprz` (32 bits com `UXTW`/`SXTW`, 64 bits desempacotado e com deslocamento de 64 bits), `ST1_zpiz` (vetor + imediato) e `ST1Q` (base vetorial + deslocamento escalar) — as 37 linhas (`Ir64Op.SveStore`, Kind 172; `SveStoreOps`). Elemento inativo NUNCA escreve; no scatter, com endereços repetidos, vence o elemento de índice maior. Scatter e `ST1Q` são ilegais em modo streaming. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, loads contíguos e o `FFR`** (`B17.17`): `LD1` (as 16 combinações de `dtype`, com extensão de sinal), `LD2`/`LD3`/`LD4` desentrelaçados (registradores consecutivos módulo 32), `LDNT1` contíguo, `LD1W`/`LD1D` e `LD[234]Q` de elemento de 128 bits (`FEAT_SVE2p1`), `LD1R*`, `LD1RQ`/`LD1RO` (`FEAT_F64MM`, `VL ≥ 256`), `LDR` de vetor e de predicado e os quatro `PRF*` (hint, no-op) — as 27 linhas — mais **`LDFF1`/`LDNF1`**, a classe própria que registra a falha no `FFR` em vez de abortar (`Ir64Op.SveLoad`, Kind 171; `SveLoadOps`). Elemento inativo não acessa memória; todo load grava os registradores só depois de ler tudo (aborto preciso). `docs/COBERTURA-ISA.md` inalterada.
- **SVE, unárias de ponto flutuante predicadas** (`B17.16`): as 105 linhas de `### SVE FP Unary Operations Predicated Group` — `FCVT`/`FCVTX`/`BFCVT` (conversão de precisão), `FCVTZS`/`FCVTZU` (FP→inteiro, saturante), `SCVTF`/`UCVTF`, `FRINTN`/`P`/`M`/`Z`/`A`/`X`/`I`, `FRINT32/64{X,Z}`, `FRECPX` e `FSQRT`, nas formas merging (`_m`) e zeroing (`_z`, `FEAT_SVE2p2`); `Ir64Op.SveFpUnary` (Kind 170), tabela de decodificação gerada do `.decode`. Achados: as 16 `FRINT32/64` são SVE2p2 (não FRINTTS); `FPUnpackCV` zera `FZ16`; `FRINTA`/`FCVTX` ganharam modos de arredondamento internos (`docs/COBERTURA-ISA.md` inalterada)
- **SVE, comparação e reduções de ponto flutuante** (`B17.15`): `FCMGE`/`FCMGT`/`FCMEQ`/`FCMNE`/`FCMUO`/`FACGE`/`FACGT` (vetor×vetor), `FCMGE`/`FCMGT`/`FCMLT`/`FCMLE`/`FCMEQ`/`FCMNE` com zero, reduções rápidas em árvore (`FADDV`/`FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV`), as cinco `*QV` por segmento de 128 bits (`FEAT_SVE2p1`) e `FADDA` serial — as 24 linhas, em meia/simples/dupla. A comparação FP não altera `NZCV` (só pode sujar o `FPSR`); `FADDA` é ilegal em streaming. `Ir64Op.SveFpCompareReduce` (Kind 169). `docs/COBERTURA-ISA.md` inalterada.
- **SVE, multiply-add de ponto flutuante e aritmética complexa** (`B17.14`): `FMLA`/`FMLS`/`FNMLA`/`FNMLS` predicados (as duas ordens de operando: `FMAD`/`FMSB`/`FNMAD`/`FNMSB`), `FMLA`/`FMLS`/`FMUL` por elemento indexado (índice por segmento de 128 bits), `FCADD`, `FCMLA` e `FCMLA` indexado — 21 das 24 linhas, em meia/simples/dupla, com multiplicação-acumulação FUNDIDA sobre o `SveFloat`. As 3 linhas indexadas de `esz = 0` (e a face `esz = 0` das predicadas) são BFloat16 (`FEAT_SVE_B16B16`) e ficam recusadas até a `B17.27`. `Ir64Op.SveFpMultiplyAdd` (Kind 168). `docs/COBERTURA-ISA.md` inalterada.
- **SVE, aritmética de ponto flutuante** (`B17.13`): `FADD`/`FSUB`/`FMUL` (não predicadas, predicadas e com imediato de 1 bit), `FSUBR`, `FDIV`/`FDIVR`, `FMAXNM`/`FMINNM`/`FMAX`/`FMIN`, `FABD`, `FSCALE`, `FMULX`, `FAMAX`/`FAMIN` (`FEAT_FAMINMAX`), `FTSMUL`, `FTMAD`, `FRECPS`/`FRSQRTS` e as estimativas `FRECPE`/`FRSQRTE` — 32 encodings, em meia/simples/dupla precisão. `SveFloat` traz ponto flutuante IEEE exato com `FPCR.RMode`, `FZ`/`FZ16`, `DN` e as flags cumulativas do `FPSR` (o A64 escalar/AdvSIMD ainda não as modela). `Ir64Op.SveFpArithmetic` (Kind 167). `docs/COBERTURA-ISA.md` inalterada.
- **SVE, permutação de predicado e predicada** (`B17.11`): `ZIP`/`UZP`/`TRN`/`REV`/`PUNPKLO`/`PUNPKHI` de predicado, `COMPACT`, `EXPAND`, `SPLICE` (destrutivo e SVE2), `LASTA`/`LASTB` e `CLASTA`/`CLASTB` nos destinos `Z`/`V`/`X`, `CPY` merging de `Vn`/`Xn|SP`, `REVB`/`REVH`/`REVW`/`RBIT`/`REVD` (`_m` e `_z`) e `SEL` (`P0`-`P15`) — 36 encodings. `EXPAND`, as formas `_z` e `COMPACT` de byte/halfword exigem `FEAT_SVE2p2`; `REVD_m`, `FEAT_SVE2p1`. `Ir64Op.SvePermutePredicated` (Kind 166) + `SvePermutePredicatedOps`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, comparações** (`B17.9`): as 26 comparações inteiras que produzem predicado (vetor×vetor `CMPHS`/`HI`/`GE`/`GT`/`EQ`/`NE`, as 10 de elemento
  largo com `LT`/`LE`/`LO`/`LS` reais, imediato com sinal `imm:s5` e sem sinal `imm:7`) e, dos escalares, `CTERM`, `WHILELT`/`LE`/`LO`/`LS`
  (SVE), `WHILEGE`/`GT`/`HS`/`HI` e `WHILERW`/`WHILEWR` (SVE2) e as duas formas de par `WHILE*` `{Pd, Pd+1}` (SVE2.1) — 32 encodings. Toda comparação
  seta `NZCV` pela mesma `PredTest` da B17.4. `WHILE_lt|gt_cnt2|cnt4` e `PEXT` (predicado-como-contador) seguem recusadas, pendência nomeada.
  `Ir64Op.SveCompare` (Kind 164) + `SveScalarCompare` (Kind 165) + `SveCompareOps`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, permutação não predicada** (`B17.10`): `EXT`/`EXT_sve2`, `DUP` (de `Xn|SP` e indexado, com quadword), `DUPQ`, `EXTQ`, `INSR` (de `Xm` e de `Vm`),
  `REV`, `PMOV` (predicado↔vetor), `TBL`/`TBL_sve2`/`TBX`/`TBLQ`/`TBXQ`, `SUNPK*`/`UUNPK*` e as três granularidades de `ZIP`/`UZP`/`TRN` (vetor inteiro,
  elemento de 128 bits e dentro do segmento) — 42 encodings. As 6 formas `_q` exigem a nova `Aarch64Feature.F64MM` (não SVE2, como a spec supunha),
  `VL >= 256` e não-streaming; `ZIPQ*`/`UZPQ*`/`TBLQ`/`EXTQ`/`DUPQ`/`PMOV`/`TBXQ`, `FEAT_SVE2p1`. `Ir64Op.SvePermute` (Kind 163) + `SvePermuteOps`.
  `docs/COBERTURA-ISA.md` inalterada.
- **SVE, endereçamento** (`B17.12`): `ADDVL`/`ADDPL`/`RDVL` (`SP`-capazes; fatores `VL/8` e `VL/64` lidos do core) e as 4 formas de `ADR` vetorial
  (`S32`/`U32`/`P32`/`P64`) — 7 encodings; `ADDSVL`/`ADDSPL`/`RDSVL` (SME) seguem recusadas até a B18. `Ir64Op.SveAddress` (Kind 162) + `SveAddressOps`.
  `docs/COBERTURA-ISA.md` inalterada.
- **SVE, imediato e multiply indexado** (`B17.8`): `ORR`/`EOR`/`AND`/`DUPM` com bitmask (reusa `Aarch64LogicalImmediate`), `CPY`/`FCPY`/`DUP`/`FDUP`,
  `ADD`/`SUB`/`SUBR`/`SQADD`/`UQADD`/`SQSUB`/`UQSUB`, `SMAX`/`UMAX`/`SMIN`/`UMIN`/`MUL` com imediato (os 10 padrões `INVALID` são recusados) e o
  multiply por elemento indexado (`SDOT`/`UDOT`/`USDOT`/`SUDOT`/`CDOT`, `MLA`/`MLS`/`MUL`, `SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH`, as alargantes `B`/`T`,
  `CMLA`/`SQRDCMLAH`; índice por segmento de 128 bits) — 104 encodings. `Ir64Op.SveImmediate` (Kind 160) e `SveMultiplyIndexed` (Kind 161).
  `docs/COBERTURA-ISA.md` inalterada.
- **SVE, reduções inteiras** (`B17.7`): `ORV`/`EORV`/`ANDV`/`SADDV`/`UADDV`/`SMAXV`/`UMAXV`/`SMINV`/`UMINV` (escrevem `V<d>`; só `SADDV`/`UADDV`
  em 64 bits), as 8 reduções por segmento de 128 bits `*QV` (atrás da nova `Aarch64Feature.SVE2_1`; `SVE2_2` a implica) e o `MOVPRFX`
  predicado `_z`/`_m` (19 encodings). `Ir64Op.SveIntegerReduction` (Kind 159) + `SveIntegerReductionOps`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, inteiro predicado** (`B17.6`): aritmética binária (`ADD`/`SUB`/`SUBR`, lógica, min/max, `SABD`/`UABD`, `MUL`/`SMULH`/`UMULH`,
  `SDIV`/`UDIV` e reversas), shifts (imediato, vetor com as 3 reversas, elemento largo, `ASRD` e as 5 SVE2 `SQSHL`/`UQSHL`/`SRSHR`/`URSHR`/`SQSHLU`)
  e unárias (`CLS`/`CLZ`/`CNT`/`CNOT`/`NOT`/`FABS`/`FNEG`/`ABS`/`NEG`/extensões) — 68 encodings, todos *merging*; as 15 unárias `_z`
  (zeroing) atrás de `Aarch64Feature.SVE2_2`. `Ir64Op.SveIntegerPredicated` (Kind 158) + `SveIntegerPredicatedOps`. Pendência nomeada:
  `FPCR.AH` no `FABS`/`FNEG`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, inteiro sem predicado** (`B17.5`): `ADD`/`SUB`/`SQADD`/`UQADD`/`SQSUB`/`UQSUB`, lógica de vetor (`AND`/`ORR`/`EOR`/`BIC`),
  shifts por imediato e por elemento largo, `MLA`/`MLS`/`MAD`/`MSB` (predicados), `MOVPRFX` (executado como `MOV`, como o QEMU),
  `FEXPA`/`FTSSEL` (tabelas do manual) e `INDEX` (4 formas), mais as 7 operações SVE2 (`XAR`/`EOR3`/`BCAX`/`BSL`/`BSL1N`/`BSL2N`/`NBSL`,
  atrás de `Aarch64Feature.SVE2`). `Ir64Op.SveIntegerUnpredicated` (Kind 157) + `SveIntegerOps`; sempre no `VL` efetivo.
  Pendência nomeada: `FPCR.AH` (FEAT_AFP) no `FTSSEL`. `docs/COBERTURA-ISA.md` inalterada.
- **SVE, predicados** (`B17.4`): lógica de predicado (`AND`/`BIC`/`EOR`/`SEL`/`ORR`/`ORN`/`NOR`/`NAND`, com sufixo `S`), `PTEST`,
  `PTRUE`/`PTRUES` (32 padrões `pat:5`), `PFALSE`, `SETFFR`/`RDFFR`/`WRFFR`, `PFIRST`/`PNEXT`, partition break
  (`BRKA`/`BRKB`/`BRKPA`/`BRKPB`/`BRKN`), contagem por predicado (`CNTP`, `INCP`/`DECP`, `SQINCP`/`UQINCP`…, e `FIRSTP`/`LASTP`
  sob `Aarch64Feature.SVE2_2`) e contagem de elementos (`CNTB`/`INCB`/`SQINCB`… com padrão e multiplicador). Decoder
  `Aarch64SveDecoder` (gate `FEAT_SVE`), substrato `SvePredicateOps` (`PredTest` = `NZCV` de `iter_predtest_fwd`,
  `DecodePredCount`), sempre no `VL` efetivo (em streaming, o `SVL`). Pendência nomeada: `PTRUE`/`CNTP` de
  predicado-como-contador (SVE2.1). `docs/COBERTURA-ISA.md` inalterada (`sve.decode` segue fora de preset até a B17.26).
- **SME, modo streaming** (`B18.2`): `SMSTART`/`SMSTOP` (`MSR SVCRSM/SVCRZA/SVCRSMZA, #imm`) com efeito real — `PSTATE.SM`/`PSTATE.ZA`,
  zeramento de `Z`/`P`/`FFR`/`FPSR`/`FPMR` ao atravessar a fronteira de streaming e de `ZA`/`ZT0` ao habilitar,
  `Aarch64Core.vectorLengthBits()` = `SVL` em streaming, banco escalável por `max(VL, SVL)` e instruções AdvSIMD
  ilegais em streaming recusadas (`Ir64Op.StreamingRestricted`, `Aarch64Feature.SME_FA64`). **`docs/COBERTURA-ISA.md`:
  23523/23523 (100%)** — `MSR_i_SVCR` era a última célula `❌`.
- **VFP ARMv8-A de 32 bits** (`B22.7`): `VRINTR`/`VRINTZ`/`VRINTX` (`sp`/`dp`/`hp`), `VCVTR` (`rz=0`),
  `VCVTB`/`VCVTT` entre meia precisão e simples/dupla, `VCVTB`/`VCVTT.BF16.F32` (`FEAT_BF16`) e `VJCVT`
  (`FEAT_JSCVT`). Preset novo `ArmArchitecture.ARMV8_6A_32`; `ArmFeature.JAVASCRIPT_CONVERT`;
  `ArmFeature.HALT` declarada nos presets ARMv8-M (`HLT`).

### Corrigido
- **A64 `FJCVTZS`** (`FEAT_JSCVT`): overflow devolvia `0` em vez de reduzir módulo 2³² (`ToInt32` do
  ECMAScript), e `-0.0` marcava `PSTATE.Z` como exato (o QEMU real o trata como inexato).

## [1.3.0] — 2026-08-27

Cobertura de ISA (`docs/COBERTURA-ISA.md`) desde o `1.2.0`: **global 71% → 73%**, **A64 61% → 68%**
— publicada a pedido explícito do usuário mesmo abaixo dos gatilhos de release normais
(`tasks/README.md`: global ≥5pp OU arquitetura ≥10pp).

### Adicionado
- **A64 — Cryptographic Extension** (`B8.11`/`B8.11b`): `AESE`/`AESD`/`AESMC`/`AESIMC`/`PMULL`/
  `PMULL2` e `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA1H`/`SHA1SU1`/`SHA256H`/`SHA256H2`/`SHA256SU0`/
  `SHA256SU1` (Cortex-A53 tem a Crypto Extension base).
- **A64 — AdvSIMD copy** (`B8.12`): `DUP`(elemento/geral)/`INS`(geral/elemento)/`SMOV`/`UMOV`.

### Corrigido
- **JIT A64** (`E7`): exceções de guest (ex. `TRANSLATION_FAULT_L3`) escapavam para o host em vez
  de serem tratadas — `Ir64BlockCompiler` não cercava o bloco nativo com `try/catch`, e
  `JitRuntime64#execute` não protegia o `lift()` de um bloco quente.
- **Decode A64** (`E8`): `decodeAdvancedSimdInteger` confundia "AdvSIMD across lanes" com "three
  different" sempre que `Rm` caía em certas faixas (`0`/`1`/`≥16`) — discriminador real é `bit11`,
  não `Rm`.

## [1.2.0] — 2026-08-26

### Adicionado
- **`Gdb64Server`**: stub do protocolo de série remota GDB para {@code Aarch64Core} — irmão A64
  do `GdbServer` (ARM32) já existente, mesma capacidade (ler/escrever registradores e memória,
  breakpoints em PC, watchpoints de escrita, step/continue), layout de registrador `g`/`p`/`P`
  no formato AArch64 real (`x0`-`x30`/`sp`/`pc`/`cpsr`) e endereços de 64 bits em `m`/`M`/`Z`/`z`.
  Classe nova, não uma generalização do `GdbServer` existente — os dois mundos de 32/64 bits já
  são independentes por desenho no resto do arm-jitter.

### Corrigido
- `GdbServer`/`Gdb64Server`: um acesso de memória (`m`/`M`) a um endereço fora da faixa mapeada
  do hospedeiro agora responde `E01` ao gdb em vez de deixar a exceção do hospedeiro (ex. um
  segfault simulado de guest) atravessar e derrubar a sessão de depuração inteira.
- **`VSQRT` (interpretado, `IrVfpExecutor`)**: `computeSingleArithmetic`/`computeDoubleArithmetic`
  liam `vfp.sFloat(op.vn())`/`dDouble(op.vn())` incondicionalmente antes do `switch`, mas `SQRT`
  é unário (só `Vm`) e o decoder grava `vn=-1` (sentinel "sem `Vn`") para essa forma — qualquer
  `VSQRT` real lançava `ArrayIndexOutOfBoundsException`. Achado pelo `armbox`
  (`Armv7TortureTest`, binário `gcc` real com `vsqrt.f32`); o backend ASM nativo não tinha o
  bug (já lia `vn` só dentro dos `case`s que o usam).

## [1.1.0] — 2026-08-23

Marco de cobertura de ISA (`docs/COBERTURA-ISA.md`): **global 53% → 59%**, **A64 18% → 27%**
desde o `1.0.0` — dispara release conforme a regra do `tasks/README.md`.

### Adicionado
- **EL2/EL3 completos** (épico B10): estado de exceção generalizado para os 4 níveis
  (`Aarch64ExceptionLevel`), registradores de sistema de EL2 e EL3, `HVC` (entra em EL2) e
  `SMC` (entra em EL3) reais com a árvore de decisão do manual, `AT` (`S1E0*`/`S1E1*` e
  stage-2 `S12E*`, com `Stage2TranslatingAddressSpace64` novo), `TLBI` EL2/EL3 (decode) e
  registradores de debug (`op0=2`, armazenamento). `S1E2*`/`S1E3*` (formas EL2/EL3 puras de
  `AT`) ficam de fora, bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos sem consumidor real hoje.
- **A64 — inteiro e branch/system**: load/store escalar restante (`STNP`/`LDNP`/`LDPSW`/
  `PRFM`/`LDTR`/`STTR`/`LDXP`/`STXP`/`LDAR`/`STLR`/`CAS`/`CASP`), aritmética/bit restante
  (`ADC`/`SBC`/`EXTR`/`RBIT`/`REV*`/`CLZ`/`CLS`/`CNT`/`SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/
  `SMULH`/`UMULH`/`RMIF`/`SETF8`/`SETF16`/`CFINV`/`XAFLAG`/`AXFLAG`), e
  `WFET`/`WFIT`/`CLREX`/`DSB(nXS)`/`SB`/`BRK`/`HLT`/`MSR` (imediato) restantes.
- **A64 — FP escalar**: aritmética restante (`FNMUL`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FSQRT`/
  `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`) e comparação/seleção/conversão (`FCSEL`/`FCCMP(E)`/
  `FRINTx`/`SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU`/`FMOV` registrador-geral).
- **A32/T16 (ARMv6)**: DSP/media (`SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/`SMMLA{R}`/
  `SMMLS{R}` + `UDF`) e T16 genuínos (`SETEND`, `CPS` A/R-profile, `REV`/`REV16`/`REVSH`,
  `SXTH`/`SXTB`/`UXTH`/`UXTB`).
- **VFP**: `VNMLA`/`VNMLS`, `VMOV_to_gp`/`VMOV_from_gp` (word), `VMOV_64_sp`,
  `VCVT_fix_{sp,dp}`.
- Espaço incondicional (`cond==0b1111`) agora recusa (`UNIMPLEMENTED`) em vez de colidir
  silenciosamente com o dispatch condicional (invariante **G8** novo).
- `docs/COBERTURA-ISA.md`: tabela de cobertura de ISA gerada por medição (`decodetree` do
  QEMU sondado contra o decoder real), regenerável via `./gerar-cobertura-isa.sh`.

### Corrigido
- Múltiplas colisões de decode reais que faziam encodings desconhecidos serem confundidos
  silenciosamente com outra instrução em vez de recusados (ver `docs/COBERTURA-ISA.md` e as
  tasks `E6`/`B8.1`-`B8.5`/`B9.1`/`B10.6` para o detalhe de cada uma).

### Conhecido / fora de escopo desta versão
- `B10.6b`/`B10.6c` (`AT` formas EL2/EL3 puras) — bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos.
- T32 (Thumb-2) ainda com 58 lacunas conhecidas (`B9.7`).
- AdvSIMD A64 (NEON) ainda em 0% — maior bloco restante (~690 células).
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`) — bloqueado em
  toolchain/kernel `aarch64-linux-*` reais.

## [1.0.0] — 2026-08-15

Primeira versão publicada. Consolida o que já estava em produção nos emuladores
`gbaemu` e `ndsemu`.

### Adicionado
- Pipeline `cache → decode → lift IR → otimizar → emit` com três backends:
  `INTERPRETED_IR` (oráculo/debug), `JVM_BYTECODE` (ASM, default recomendado,
  tiered com tier frio interpretado + tier quente compilado, fallback `PER_OP`,
  compilação em pool de threads, execução condicional nativa, shifted-register
  nativo, register cache em locals, inline cache de 32K, encadeamento de blocos
  e superblocos de loop) e `TRUFFLE` (módulo opcional `arm-jitter-truffle`,
  compila de verdade em JVM sob JBR+Unchained/GraalVM).
- Arquiteturas guest de 32 bits: ARMv4T (GBA, produção), ARMv5TE (NDS, produção),
  ARMv6K, `ARMV6K_THUMB2` (Thumb-2), ARMv7-A + VFPv2 e o perfil M
  (ARMv6-M/ARMv7-M, `ExceptionModel` plugável com NVIC/VTOR/SysTick e
  semihosting) — todos completos e validados com binários ELF reais (torture
  handwritten e `gcc` real) no `armbox`.
- MMU/softmmu de 32 bits (épico B4.1): page-walk short-descriptor VMSA,
  domínios/AP, aborts precisos (FAR/FSR) nos três motores de execução, geração
  de tradução ciente do `BlockCache`/inline cache; validado com um kernel Linux
  ARMv5TE real (Debian) e busybox estáticos até um shell interativo no
  `virtual-arm-box`.
- AArch64 (épico B6): decoder A64 completo (base ISA inteira, FP/SIMD escalar,
  exclusivos), `Aarch64Core` com EL0/EL1 e aborts precisos, MMU v8
  (`TranslatingAddressSpace64`), backend ASM nativo (`jit64`) cobrindo todo
  `Ir64Op.Kind`; `armbox --arch=aarch64` roda binários ELF64 bare-metal.
- Biblioteca nativa (`arm_jitter.dll`/`.so`) com API C (`capi/`, `native-image
  --shared`), embutível por qualquer linguagem com FFI, backend
  `INTERPRETED_IR`.
- Depuração: `GdbServer` (stub GDB remote serial), trace listener, runtime de
  divergência (`divergenceCheckingArmThumb`) e harness de equivalência entre
  emissores (32 e 64 bits).

### Conhecido / fora de escopo desta versão
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`) —
  bloqueado em toolchain/kernel `aarch64-linux-*` reais.
- Backend Truffle sob `native-image` ainda não compila blocos de verdade
  (bailout de partial evaluation sob SVM, `A7`/`A9 PR2`); `native-image`
  (perfil `native` do `armbox`) roda hoje só com o backend `INTERPRETED_IR`.
- Sem NEON/SIMD avançado; sem virtualização (EL2), TrustZone (EL3) ou LPAE.
