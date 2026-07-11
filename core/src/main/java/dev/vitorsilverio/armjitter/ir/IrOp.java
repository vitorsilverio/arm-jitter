package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IrOp.Alu, IrOp.Multiply, IrOp.LongMultiply, IrOp.Saturating, IrOp.DspMultiply, IrOp.ParallelAlu, IrOp.Sel, IrOp.Saturate, IrOp.AbsDiffSum, IrOp.PsrTransfer, IrOp.Load, IrOp.Store, IrOp.LoadExclusive, IrOp.StoreExclusive, IrOp.ClearExclusive, IrOp.DoubleTransfer, IrOp.Swap, IrOp.LoadLiteral, IrOp.MultipleTransfer, IrOp.Branch, IrOp.BranchExchange, IrOp.ThumbBlPrefix, IrOp.ThumbBlSuffix, IrOp.Push, IrOp.Pop, IrOp.Swi, IrOp.Coprocessor, IrOp.Undefined, IrOp.Cycle, IrOp.Fetch, IrOp.ChangeProcessorState, IrOp.SetEndianness, IrOp.StoreReturnState, IrOp.ReturnFromException, IrOp.WaitForInterrupt, IrOp.MoveTop, IrOp.MemoryBarrier {
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
            /// Condição necessária para executar a escrita.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.STORE; }
    }

    /// `LDREX{,B,H,D}` (ARMv6/v6K): lê a memória no endereço da base (sem offset) e marca o
    /// monitor de exclusividade do core. A forma doubleword (`sizeBytes=8`) carrega o par
    /// `dst`, `dst+1`. Formas com PC não passam pelo decoder (UNPREDICTABLE).
    record LoadExclusive(
            /// Registrador de destino (primeiro do par na forma doubleword).
            int dst,
            /// Registrador base do endereço (Rn, sem offset).
            int base,
            /// Tamanho do acesso em bytes (1, 2, 4 ou 8).
            int sizeBytes,
            /// Condição necessária para executar a leitura.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.LOAD_EXCLUSIVE; }
    }

    /// `STREX{,B,H,D}` (ARMv6/v6K): escreve a memória APENAS se o monitor de exclusividade
    /// cobre o endereço/tamanho; `dst` recebe 0 (sucesso, monitor consumido) ou 1 (falha, a
    /// memória fica intacta). A forma doubleword armazena o par `src`, `src+1`.
    record StoreExclusive(
            /// Registrador de status (0 = sucesso, 1 = falha).
            int dst,
            /// Registrador com o valor armazenado (primeiro do par na forma doubleword).
            int src,
            /// Registrador base do endereço (Rn, sem offset).
            int base,
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

    /// Operação SWI delegada ao dispatcher do host.
    record Swi(
            /// Imediato da instrução SWI.
            int immediate,
            /// Condição necessária para disparar a SWI.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.SWI; }
    }

    /// Coprocessor register transfer (`MCR`/`MRC`), delegated to the core's coprocessor bus.
    record Coprocessor(
            /// `true` for `MRC` (coprocessor -> ARM register), `false` for `MCR`.
            boolean load,
            /// Coprocessor number (15 for CP15).
            int coprocessor,
            /// Primary opcode (instruction bits 23-21).
            int opcode1,
            /// Primary coprocessor register (CRn).
            int crn,
            /// Secondary coprocessor register (CRm).
            int crm,
            /// Secondary opcode (instruction bits 7-5).
            int opcode2,
            /// ARM register (Rd) read for `MCR` or written for `MRC`.
            int register,
            /// Sequential PC used as the return address if the transfer is undefined.
            int sequentialPc,
            /// Condição necessária para executar a transferência.
            Condition condition) implements IrOp {
        @Override public int kind() { return Kind.COPROCESSOR; }
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
}
