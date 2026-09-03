package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IrOp.Alu, IrOp.Multiply, IrOp.LongMultiply, IrOp.Saturating, IrOp.DspMultiply, IrOp.ParallelAlu, IrOp.Sel, IrOp.Saturate, IrOp.AbsDiffSum, IrOp.PsrTransfer, IrOp.Load, IrOp.Store, IrOp.LoadExclusive, IrOp.StoreExclusive, IrOp.ClearExclusive, IrOp.DoubleTransfer, IrOp.Swap, IrOp.LoadLiteral, IrOp.MultipleTransfer, IrOp.Branch, IrOp.BranchExchange, IrOp.ThumbBlPrefix, IrOp.ThumbBlSuffix, IrOp.Push, IrOp.Pop, IrOp.Swi, IrOp.Coprocessor, IrOp.Undefined, IrOp.Cycle, IrOp.Fetch, IrOp.ChangeProcessorState, IrOp.SetEndianness, IrOp.StoreReturnState, IrOp.ReturnFromException, IrOp.WaitForInterrupt, IrOp.MoveTop, IrOp.MemoryBarrier, IrOp.SetItState, IrOp.TableBranch, IrOp.CompareBranchZero, IrOp.BitFieldExtract, IrOp.BitFieldInsert, IrOp.BitReverse, IrOp.Divide, IrOp.VfpAlu, IrOp.VfpMoveImmediate, IrOp.VfpCompare, IrOp.VfpConvert, IrOp.VfpLoad, IrOp.VfpStore, IrOp.VfpMultipleTransfer, IrOp.VfpCoreTransfer, IrOp.VfpCorePairTransfer, IrOp.VfpSystemTransfer, IrOp.MProfileSystemRegister, IrOp.Breakpoint, IrOp.CoprocessorDouble, IrOp.VfpCorePairTransferSingle, IrOp.VfpConvertFixed, IrOp.DspDualMultiply, IrOp.DspTopWordMultiply, IrOp.Hvc, IrOp.Smc, IrOp.Eret, IrOp.MrsBank, IrOp.MsrBank, IrOp.NeonThreeSame, IrOp.NeonLoadStoreMultiple, IrOp.NeonLoadStoreSingle,
        IrOp.NeonLoadAllLanes, IrOp.NeonPairwise, IrOp.NeonFpThreeSame, IrOp.NeonFpPairwise,
        IrOp.NeonShiftImmediate, IrOp.NeonShiftNarrowImmediate, IrOp.NeonShiftWidenImmediate,
        IrOp.NeonConvertFixedPoint {
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
}
