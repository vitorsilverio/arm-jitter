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

> **ASM 32 bits: 57 de 95** operações emitidas nativamente (mais 9 condicionais).
> **Truffle 32 bits: 66 de 95** operações com nó especializado.
> **ASM 64 bits: 46 de 118** `Kind` emitidos nativamente.
> **Truffle 64 bits: 0 de 118** — o backend não existe (A10.8).

A escada que fecha cada gap: `tasks/trilha-c-perf/c12-plano-jit-nativo.md` (ASM, C12.2-C12.8) e `tasks/trilha-a-truffle/a10-plano-truffle-completo.md` (Truffle, A10.3-A10.8).

> Conciliação com a medição do `ROADMAP-100-ARM.md` (2026-09-02): o `66/73` de ASM 32 bits daquele documento = as `57` `✅` incondicionais **mais** as `9` `⚠️` (nativas no caminho comum); as `22` linhas a mais aqui são os `Kind` de NEON por imediato de B13.7/B13.8 (todas `❌`). ASM 64 bits seguia `24/95`; `VECTOR_FP_CONVERT_PRECISION` (B19.4) levou o denominador a 96, ainda `❌` — daí `46/96`.

## Tabela A — pipeline de 32 bits

Linhas = os 95 `record` de `IrOp`, na ordem do `Kind`.

| Operação | `Kind` | ASM (`AsmNativePolicy`) | Truffle (`IrOpNodeFactory`) |
|---|---|---|---|
| `Alu` | `ALU` | ⚠️ | ✅ |
| `Multiply` | `MULTIPLY` | ✅ | ✅ |
| `LongMultiply` | `LONG_MULTIPLY` | ✅ | ✅ |
| `Saturating` | `SATURATING` | ⚠️ | ✅ |
| `DspMultiply` | `DSP_MULTIPLY` | ⚠️ | ✅ |
| `PsrTransfer` | `PSR_TRANSFER` | ✅ | ✅ |
| `Load` | `LOAD` | ⚠️ | ✅ |
| `Store` | `STORE` | ⚠️ | ✅ |
| `DoubleTransfer` | `DOUBLE_TRANSFER` | ⚠️ | ✅ |
| `Swap` | `SWAP` | ✅ | ✅ |
| `LoadLiteral` | `LOAD_LITERAL` | ✅ | ✅ |
| `MultipleTransfer` | `MULTIPLE_TRANSFER` | ✅ | ✅ |
| `Branch` | `BRANCH` | ✅ | ✅ |
| `BranchExchange` | `BRANCH_EXCHANGE` | ⚠️ | ✅ |
| `ThumbBlPrefix` | `THUMB_BL_PREFIX` | ✅ | ✅ |
| `ThumbBlSuffix` | `THUMB_BL_SUFFIX` | ⚠️ | ✅ |
| `Push` | `PUSH` | ✅ | ✅ |
| `Pop` | `POP` | ✅ | ✅ |
| `Swi` | `SWI` | ✅ | ✅ |
| `Coprocessor` | `COPROCESSOR` | ✅ | ✅ |
| `Undefined` | `UNDEFINED` | ✅ | ✅ |
| `Cycle` | `CYCLE` | ✅ | ✅ |
| `Fetch` | `FETCH` | ✅ | ✅ |
| `ParallelAlu` | `PARALLEL_ALU` | ✅ | ✅ |
| `Sel` | `SEL` | ✅ | ✅ |
| `Saturate` | `SATURATE` | ✅ | ✅ |
| `AbsDiffSum` | `ABS_DIFF_SUM` | ✅ | ✅ |
| `LoadExclusive` | `LOAD_EXCLUSIVE` | ✅ | ✅ |
| `StoreExclusive` | `STORE_EXCLUSIVE` | ✅ | ✅ |
| `ClearExclusive` | `CLEAR_EXCLUSIVE` | ✅ | ✅ |
| `ChangeProcessorState` | `CHANGE_PROCESSOR_STATE` | ✅ | ✅ |
| `SetEndianness` | `SET_ENDIANNESS` | ✅ | ✅ |
| `StoreReturnState` | `STORE_RETURN_STATE` | ✅ | ✅ |
| `ReturnFromException` | `RETURN_FROM_EXCEPTION` | ✅ | ✅ |
| `WaitForInterrupt` | `WAIT_FOR_INTERRUPT` | ✅ | ✅ |
| `MoveTop` | `MOVE_TOP` | ✅ | ✅ |
| `MemoryBarrier` | `MEMORY_BARRIER` | ✅ | ✅ |
| `SetItState` | `SET_IT_STATE` | ✅ | ✅ |
| `TableBranch` | `TABLE_BRANCH` | ✅ | ✅ |
| `CompareBranchZero` | `COMPARE_BRANCH_ZERO` | ✅ | ✅ |
| `BitFieldExtract` | `BIT_FIELD_EXTRACT` | ✅ | ✅ |
| `BitFieldInsert` | `BIT_FIELD_INSERT` | ✅ | ✅ |
| `BitReverse` | `BIT_REVERSE` | ✅ | ✅ |
| `Divide` | `DIVIDE` | ✅ | ✅ |
| `VfpAlu` | `VFP_ALU` | ✅ | ✅ |
| `VfpMoveImmediate` | `VFP_MOVE_IMMEDIATE` | ✅ | ✅ |
| `VfpCompare` | `VFP_COMPARE` | ✅ | ✅ |
| `VfpConvert` | `VFP_CONVERT` | ✅ | ✅ |
| `VfpLoad` | `VFP_LOAD` | ✅ | ✅ |
| `VfpStore` | `VFP_STORE` | ✅ | ✅ |
| `VfpMultipleTransfer` | `VFP_MULTIPLE_TRANSFER` | ✅ | ✅ |
| `VfpCoreTransfer` | `VFP_CORE_TRANSFER` | ⚠️ | ✅ |
| `VfpCorePairTransfer` | `VFP_CORE_PAIR_TRANSFER` | ✅ | ✅ |
| `VfpSystemTransfer` | `VFP_SYSTEM_TRANSFER` | ✅ | ✅ |
| `MProfileSystemRegister` | `M_PROFILE_SYSTEM_REGISTER` | ✅ | ✅ |
| `Breakpoint` | `BREAKPOINT` | ✅ | ✅ |
| `CoprocessorDouble` | `COPROCESSOR_DOUBLE` | ✅ | ✅ |
| `VfpCorePairTransferSingle` | `VFP_CORE_PAIR_TRANSFER_SINGLE` | ✅ | ✅ |
| `VfpConvertFixed` | `VFP_CONVERT_FIXED` | ✅ | ✅ |
| `DspDualMultiply` | `DSP_DUAL_MULTIPLY` | ✅ | ✅ |
| `DspTopWordMultiply` | `DSP_TOP_WORD_MULTIPLY` | ✅ | ✅ |
| `Hvc` | `HVC` | ✅ | ✅ |
| `Smc` | `SMC` | ✅ | ✅ |
| `Eret` | `ERET` | ✅ | ✅ |
| `MrsBank` | `MRS_BANK` | ✅ | ✅ |
| `MsrBank` | `MSR_BANK` | ✅ | ✅ |
| `NeonThreeSame` | `NEON_THREE_SAME` | ❌ | ❌ |
| `NeonLoadStoreMultiple` | `NEON_LOAD_STORE_MULTIPLE` | ❌ | ❌ |
| `NeonLoadStoreSingle` | `NEON_LOAD_STORE_SINGLE` | ❌ | ❌ |
| `NeonLoadAllLanes` | `NEON_LOAD_ALL_LANES` | ❌ | ❌ |
| `NeonPairwise` | `NEON_PAIRWISE` | ❌ | ❌ |
| `NeonFpThreeSame` | `NEON_FP_THREE_SAME` | ❌ | ❌ |
| `NeonFpPairwise` | `NEON_FP_PAIRWISE` | ❌ | ❌ |
| `NeonShiftImmediate` | `NEON_SHIFT_IMMEDIATE` | ❌ | ❌ |
| `NeonShiftNarrowImmediate` | `NEON_SHIFT_NARROW_IMMEDIATE` | ❌ | ❌ |
| `NeonShiftWidenImmediate` | `NEON_SHIFT_WIDEN_IMMEDIATE` | ❌ | ❌ |
| `NeonConvertFixedPoint` | `NEON_CONVERT_FIXED_POINT` | ❌ | ❌ |
| `NeonModifiedImmediate` | `NEON_MODIFIED_IMMEDIATE` | ❌ | ❌ |
| `NeonWidening` | `NEON_WIDENING` | ❌ | ❌ |
| `NeonWide` | `NEON_WIDE` | ❌ | ❌ |
| `NeonNarrow` | `NEON_NARROW` | ❌ | ❌ |
| `NeonThreeSameByElement` | `NEON_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `NeonWideningByElement` | `NEON_WIDENING_BY_ELEMENT` | ❌ | ❌ |
| `NeonFpThreeSameByElement` | `NEON_FP_THREE_SAME_BY_ELEMENT` | ❌ | ❌ |
| `NeonUnary` | `NEON_UNARY` | ❌ | ❌ |
| `NeonNarrowUnary` | `NEON_NARROW_UNARY` | ❌ | ❌ |
| `NeonFpUnary` | `NEON_FP_UNARY` | ❌ | ❌ |
| `NeonComplex` | `NEON_COMPLEX` | ❌ | ❌ |
| `NeonComplexByElement` | `NEON_COMPLEX_BY_ELEMENT` | ❌ | ❌ |
| `NeonDotProduct` | `NEON_DOT_PRODUCT` | ❌ | ❌ |
| `NeonDotProductByElement` | `NEON_DOT_PRODUCT_BY_ELEMENT` | ❌ | ❌ |
| `NeonSwapPermute` | `NEON_SWAP_PERMUTE` | ❌ | ❌ |
| `NeonExtract` | `NEON_EXTRACT` | ❌ | ❌ |
| `NeonTableLookup` | `NEON_TABLE_LOOKUP` | ❌ | ❌ |
| `NeonDuplicateScalar` | `NEON_DUPLICATE_SCALAR` | ❌ | ❌ |

### Condicionais do lado 32 bits

Cada `⚠️` acima recusa a emissão nativa só no caso listado; no resto é `✅`.

| Operação | Condição |
|---|---|
| `Alu` | nativa exceto `dst=PC` com `setFlags` (restaura o CPSR a partir do SPSR) e `opcode=ORN` (Thumb-2, sem emissão nativa ainda) |
| `Saturating` | nativa exceto `dst=PC` (`UNPREDICTABLE`/troca de bloco) |
| `DspMultiply` | nativa exceto `dst=PC`, ou `SMLAWx`/`SMULWx` (`op2=2`) com `Rn=PC` |
| `Load` | nativa exceto `LDRxT` (`unprivileged`: precisa de `AddressSpace#withUnprivilegedAccess`) |
| `Store` | nativa exceto `STRxT` (`unprivileged`) |
| `DoubleTransfer` | STRD (só lê registradores) sempre nativa; LDRD nativa exceto com `PC` no par carregado (sem tratamento de interworking no emissor) |
| `BranchExchange` | nativa exceto `BLX` (`link`: interworking + link register) |
| `ThumbBlSuffix` | nativa exceto a forma `BLX` (`exchange`: alinha o destino e troca para ARM) |
| `VfpCoreTransfer` | nativa exceto `VMOV.F16` (`halfWidth`: transferência de 16 bits, sem preset com `HALF_PRECISION_FP` hoje) |

## Tabela B — pipeline de 64 bits

Linhas = os 118 `Ir64Op.Kind`. `Ir64NativePolicy` casa por `Kind` e **não tem carve-outs condicionais** (sem `⚠️` deste lado). A coluna Truffle é inteira `❌` (A10.8).

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
| `VECTOR_LOAD_STORE_MULTIPLE` | ❌ | ❌ |
| `VECTOR_LOAD_STORE_SINGLE` | ❌ | ❌ |
| `VECTOR_LOAD_SINGLE_REPLICATE` | ❌ | ❌ |
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
| `FP_LOAD64` | ❌ | ❌ |
| `FP_STORE64` | ❌ | ❌ |
| `FP_LOAD_STORE_PAIR` | ❌ | ❌ |
| `FP_LOAD_LITERAL64` | ❌ | ❌ |
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
- `VECTOR_LOAD_STORE_MULTIPLE`
- `VECTOR_LOAD_STORE_SINGLE`
- `VECTOR_LOAD_SINGLE_REPLICATE`
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
- `FP_LOAD64`
- `FP_STORE64`
- `FP_LOAD_STORE_PAIR`
- `FP_LOAD_LITERAL64`
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

