package dev.vitorsilverio.armjitter.ir;

import java.util.ArrayList;
import java.util.List;

/// Bloco imutavel de IR associado a um intervalo de PC.
public record IrBlock(
        /// Endereco inicial do bloco.
        int startPc,
        /// Endereco exclusivo do fim do bloco.
        int endPc,
        /// Operacoes IR na ordem de execucao.
        List<IrOp> operations) {

    /// Cria um bloco copiando a lista de operacoes para preservar imutabilidade.
    public IrBlock {
        operations = List.copyOf(operations);
    }

    /// Cria um builder mutavel para montar um bloco antes de sela-lo.
    public static Builder builder(int startPc) {
        return new Builder(startPc);
    }

    /// Builder mutavel usado pelo `IrBuilder`.
    public static final class Builder {
        private final int startPc;
        private final List<IrOp> operations = new ArrayList<>();
        private int endPc;

        private Builder(int startPc) {
            this.startPc = startPc;
            this.endPc = startPc;
        }

        /// Adiciona uma operacao IR ao bloco em construcao.
        public Builder add(IrOp op) {
            operations.add(op);
            return this;
        }

        /// Retorna `true` quando nenhuma operacao foi adicionada ainda.
        public boolean isEmpty() {
            return operations.isEmpty();
        }

        /// Define o endereco exclusivo de fim do bloco.
        public Builder endPc(int endPc) {
            this.endPc = endPc;
            return this;
        }

        /// Sela o bloco e devolve uma instancia imutavel.
        public IrBlock sealed() {
            return new IrBlock(startPc, endPc, operations);
        }
    }
}
