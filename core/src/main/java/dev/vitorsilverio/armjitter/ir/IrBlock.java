package dev.vitorsilverio.armjitter.ir;

import java.util.ArrayList;
import java.util.List;

/// Bloco imutável de IR associado a um intervalo de PC `[startPc, endPc)` (início inclusivo,
/// fim exclusivo). Representa um trecho linear de código otimizado/compilado como uma unidade.
///
/// As operações são mantidas tanto como {@link List} imutável (API pública usada pelos
/// otimizadores e pelo emissor ASM em tempo de compilação) quanto como um `IrOp[]` cacheado
/// uma única vez na construção, para o caminho quente do interpretador
/// ({@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor}): iterar o array por
/// índice evita alocar um `Iterator` por execução de bloco e o `Objects.checkIndex` por
/// operação — ambos custos relevantes quando o bloco é executado milhões de vezes.
public final class IrBlock {
    private final int startPc;
    private final int endPc;
    private final List<IrOp> operations;
    private final IrOp[] operationsArray;

    /// Cria um bloco copiando a lista de operações para preservar imutabilidade.
    public IrBlock(int startPc, int endPc, List<IrOp> operations) {
        this.startPc = startPc;
        this.endPc = endPc;
        this.operations = List.copyOf(operations);
        this.operationsArray = this.operations.toArray(new IrOp[0]);
    }

    /// Endereço inicial (inclusivo) do bloco.
    public int startPc() {
        return startPc;
    }

    /// Endereço final (exclusivo) do bloco.
    public int endPc() {
        return endPc;
    }

    /// Operações IR do bloco como lista imutável.
    public List<IrOp> operations() {
        return operations;
    }

    /// Operações IR como array, para iteração por índice no caminho quente.
    ///
    /// **Uso interno:** o array respalda o bloco imutável e NÃO deve ser modificado;
    /// é compartilhado, não copiado, justamente para evitar custo por execução.
    public IrOp[] operationsArray() {
        return operationsArray;
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
