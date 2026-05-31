package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operacao de representacao intermediaria usada antes da emissao de codigo.
public sealed interface IrOp permits IrOp.Alu, IrOp.Load, IrOp.Store, IrOp.LoadLiteral, IrOp.MultipleTransfer, IrOp.Branch, IrOp.BranchExchange, IrOp.ThumbBlPrefix, IrOp.ThumbBlSuffix, IrOp.Push, IrOp.Pop, IrOp.Swi, IrOp.Cycle {
    /// Operacao ALU generica.
    record Alu(
            /// Mnemonico ou identificador interno da operacao.
            String opcode,
            /// Registrador de destino.
            int dst,
            /// Primeiro registrador de origem.
            int src1,
            /// Segundo operando, que pode ser registrador ou imediato.
            IrOperand src2,
            /// Indica se NZCV deve ser atualizado.
            boolean setFlags,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IrOp {
    }

    /// Operacao de leitura de memoria.
    record Load(
            /// Registrador de destino.
            int dst,
            /// Registrador base do endereco.
            int base,
            /// Offset ja normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica extensao com sinal.
            boolean signed,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Condicao necessaria para executar a leitura.
            Condition condition) implements IrOp {
    }

    /// Operacao de escrita de memoria.
    record Store(
            /// Registrador de origem.
            int src,
            /// Registrador base do endereco.
            int base,
            /// Offset ja normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Condicao necessaria para executar a escrita.
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

    /// Contagem de ciclos agregada ao bloco.
    record Cycle(
            /// Quantidade de ciclos somada ao bloco.
            int count) implements IrOp {
    }
}
