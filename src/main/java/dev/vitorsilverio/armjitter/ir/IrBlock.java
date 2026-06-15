package dev.vitorsilverio.armjitter.ir;

import java.util.ArrayList;
import java.util.List;

/// Bloco imutável de IR associado a um intervalo de PC.
/// Cada bloco representa um trecho linear de código que pode ser otimizado e compilado como uma unidade.
/// O endereço de início é inclusivo e o endereço de fim é exclusivo, formando um intervalo [startPc, endPc).
/// As operações IR são mantidas em uma lista imutável para garantir a integridade do bloco após a sua criação.
public record IrBlock(
        int startPc,
        int endPc,
        List<IrOp> operations) {

    /// Cria um bloco copiando a lista de operações para preservar imutabilidade.
    public IrBlock {
        operations = List.copyOf(operations);
    }

    /// Cria um builder mutável para montar um bloco antes de selá-lo.
    public static Builder builder(int startPc) {
        return new Builder(startPc);
    }

    /// Builder mutável usado pelo `IrBuilder`.
    public static final class Builder {
        private final int startPc;
        private final List<IrOp> operations = new ArrayList<>();
        private int endPc;

        private Builder(int startPc) {
            this.startPc = startPc;
            this.endPc = startPc;
        }

        /// Adiciona uma operação IR ao bloco em construção.
        public Builder add(IrOp op) {
            operations.add(op);
            return this;
        }

        /// Retorna `true` quando nenhuma operação foi adicionada ainda.
        public boolean isEmpty() {
            return operations.isEmpty();
        }

        /// Define o endereço exclusivo de fim do bloco.
        public Builder endPc(int endPc) {
            this.endPc = endPc;
            return this;
        }

        /// Sela o bloco e devolve uma instância imutável.
        public IrBlock sealed() {
            return new IrBlock(startPc, endPc, operations);
        }
    }
}
