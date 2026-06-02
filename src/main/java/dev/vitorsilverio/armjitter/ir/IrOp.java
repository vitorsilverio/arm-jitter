package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IrOp.Alu, IrOp.Multiply, IrOp.LongMultiply, IrOp.PsrTransfer, IrOp.Load, IrOp.Store, IrOp.Swap, IrOp.LoadLiteral, IrOp.MultipleTransfer, IrOp.Branch, IrOp.BranchExchange, IrOp.ThumbBlPrefix, IrOp.ThumbBlSuffix, IrOp.Push, IrOp.Pop, IrOp.Swi, IrOp.Undefined, IrOp.Cycle, IrOp.Fetch {
    /// Operacao ALU generica.
    record Alu(
            /// Mnemonico ou identificador interno da operacao.
            String opcode,
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
            /// Registrador acumulador, ou `-1` quando nao se aplica.
            int rn,
            /// Valor fixo para `rn`, ou `-1`.
            int rnValueOverride,
            /// Indica se o acumulador deve ser somado.
            boolean accumulate,
            /// Indica se NZ deve ser atualizado.
            boolean setFlags,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
    }

    /// Operacao de multiplicacao longa, com acumulador opcional.
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
            /// Valor fixo para o registrador alto atual em acumulacao, ou `-1`.
            int dstHighValueOverride,
            /// Valor fixo para o registrador baixo atual em acumulacao, ou `-1`.
            int dstLowValueOverride,
            /// Indica multiplicacao com sinal.
            boolean signed,
            /// Indica se o par destino deve ser somado ao produto.
            boolean accumulate,
            /// Indica se NZ deve ser atualizado a partir do resultado de 64 bits.
            boolean setFlags,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
    }

    /// Transferencia entre registradores gerais e CPSR/SPSR.
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
            /// Mascara de campos PSR para MSR.
            int fieldMask,
            /// Condicao necessaria para executar a transferencia.
            Condition condition) implements IrOp {
    }

    /// Operacao de leitura de memoria.
    record Load(
            /// Registrador de destino.
            int dst,
            /// Registrador base do endereco.
            int base,
            /// Valor fixo para usar como base quando o registrador base e `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset ja normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica extensao com sinal.
            boolean signed,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica enderecamento post-index.
            boolean postIndexed,
            /// Condicao necessaria para executar a leitura.
            Condition condition) implements IrOp {
    }

    /// Operacao de escrita de memoria.
    record Store(
            /// Registrador de origem.
            int src,
            /// Valor fixo para usar como valor armazenado, ou `-1`.
            int srcValueOverride,
            /// Registrador base do endereco.
            int base,
            /// Valor fixo para usar como base quando o registrador base e `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset ja normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica enderecamento post-index.
            boolean postIndexed,
            /// Condicao necessaria para executar a escrita.
            Condition condition) implements IrOp {
    }

    /// Troca valor de memoria com registrador.
    record Swap(
            /// Registrador que recebe o valor antigo da memoria.
            int dst,
            /// Registrador base do endereco.
            int base,
            /// Valor fixo para usar como base quando o registrador base e `PC`, ou `-1`.
            int baseValueOverride,
            /// Registrador cujo valor sera escrito na memoria.
            int src,
            /// Valor fixo para usar como valor escrito, ou `-1`.
            int srcValueOverride,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Condicao necessaria para executar a troca.
            Condition condition) implements IrOp {
    }

    /// Le uma word de endereco absoluto literal.
    record LoadLiteral(
            /// Registrador de destino.
            int dst,
            /// Endereco absoluto a ler.
            int address,
            /// Condicao necessaria para executar a leitura.
            Condition condition) implements IrOp {
    }

    /// Transferencia sequencial de multiplos registradores.
    record MultipleTransfer(
            /// `true` para load, `false` para store.
            boolean load,
            /// Registrador base.
            int base,
            /// Mascara de registradores.
            int registerMask,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Valor de `PC` a armazenar quando a mascara contem r15, ou `-1`.
            int pcStoreValueOverride,
            /// Usa banco USR/SYS ou restaura CPSR pelo SPSR em `LDM ... pc^`.
            boolean userMode,
            /// Modo de enderecamento ARM/THUMB.
            BlockTransferMode mode,
            /// Indica mascara vazia em `LDM`/`STM`, caso especial do ARM7TDMI.
            boolean emptyRegisterList,
            /// Condicao necessaria para executar.
            Condition condition) implements IrOp {
    }

    /// Operacao de branch.
    record Branch(
            /// Endereco absoluto de destino quando conhecido.
            int target,
            /// Valor a gravar no link register quando `link` estiver ativo.
            int returnAddress,
            /// Indica atualizacao do link register.
            boolean link,
            /// Condicao necessaria para tomar o branch.
            Condition condition,
            /// Conjunto de instrucoes esperado apos o branch.
            InstructionSet targetSet) implements IrOp {
    }

    /// Branch exchange, usado para trocar entre ARM e THUMB.
    record BranchExchange(
            /// Registrador que contem o destino.
            int sourceRegister,
            /// Valor fixo para usar como destino, ou `-1`.
            int sourceValueOverride,
            /// Condicao necessaria para tomar o branch.
            Condition condition) implements IrOp {
    }

    /// Primeira metade de `BL` THUMB.
    record ThumbBlPrefix(
            /// Valor assinado alto ja deslocado.
            int highOffset,
            /// Endereco da instrucao.
            int address,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
    }

    /// Segunda metade de `BL` THUMB.
    record ThumbBlSuffix(
            /// Valor baixo ja deslocado.
            int lowOffset,
            /// Endereco da instrucao.
            int address,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
    }

    /// Operacao de push THUMB.
    record Push(
            /// Mascara de registradores r0-r7.
            int registerMask,
            /// Indica inclusao de LR.
            boolean includeLr,
            /// Condicao necessaria para executar.
            Condition condition) implements IrOp {
    }

    /// Operacao de pop THUMB.
    record Pop(
            /// Mascara de registradores r0-r7.
            int registerMask,
            /// Indica inclusao de PC.
            boolean includePc,
            /// Condicao necessaria para executar.
            Condition condition) implements IrOp {
    }

    /// Operacao SWI delegada ao dispatcher do host.
    record Swi(
            /// Imediato da instrucao SWI.
            int immediate,
            /// Condicao necessaria para disparar a SWI.
            Condition condition) implements IrOp {
    }

    /// Instrucao nao implementada/indefinida que deve entrar no vetor `0x04`.
    record Undefined(
            /// PC sequencial usado como endereco de retorno da excecao.
            int sequentialPc,
            /// Condicao necessaria para disparar a excecao.
            Condition condition) implements IrOp {
    }

    /// Contagem de ciclos agregada ao bloco.
    record Cycle(
            /// Quantidade de ciclos somada ao bloco.
            int count) implements IrOp {
    }

    /// Custo de fetch da instrucao original na memoria do dispositivo.
    record Fetch(
            /// Endereco da instrucao buscada.
            int address,
            /// Tamanho da instrucao em bytes.
            int sizeBytes) implements IrOp {
    }
}
