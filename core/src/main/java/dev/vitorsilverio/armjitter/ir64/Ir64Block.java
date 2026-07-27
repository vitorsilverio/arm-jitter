package dev.vitorsilverio.armjitter.ir64;

import java.util.ArrayList;
import java.util.List;

/// Bloco imutável de IR de 64 bits associado a um intervalo de PC `[startPc, endPc)` (início
/// inclusivo, fim exclusivo) — espelho estrutural de {@link dev.vitorsilverio.armjitter.ir.IrBlock}
/// (32 bits), mas com `startPc`/`endPc` em `long` (endereços A64 não cabem em `int`, RFC-IR-64BIT.md
/// §3.1). Introduzido na task B6.4 (backend ASM 64-bit): B6.1-B6.3.4 não precisaram de um "bloco"
/// porque {@link dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor#step} decodifica e executa
/// UMA instrução por vez, sem lifting em lote.
public final class Ir64Block {
    private final long startPc;
    private final long endPc;
    private final List<Ir64Op> operations;
    private final Ir64Op[] operationsArray;
    private final int[] kindsArray;

    /// Cria um bloco copiando a lista de operações para preservar imutabilidade.
    public Ir64Block(long startPc, long endPc, List<Ir64Op> operations) {
        this.startPc = startPc;
        this.endPc = endPc;
        this.operations = List.copyOf(operations);
        this.operationsArray = this.operations.toArray(new Ir64Op[0]);
        this.kindsArray = new int[this.operationsArray.length];
        for (int i = 0; i < this.operationsArray.length; i++) {
            this.kindsArray[i] = this.operationsArray[i].kind();
        }
    }

    /// Endereço inicial (inclusivo) do bloco.
    public long startPc() {
        return startPc;
    }

    /// Endereço final (exclusivo) do bloco.
    public long endPc() {
        return endPc;
    }

    /// Operações IR do bloco como lista imutável.
    public List<Ir64Op> operations() {
        return operations;
    }

    /// Operações IR como array, para iteração por índice no caminho quente (mesma técnica de
    /// {@link dev.vitorsilverio.armjitter.ir.IrBlock#operationsArray()}).
    ///
    /// **Uso interno:** o array respalda o bloco imutável e NÃO deve ser modificado.
    public Ir64Op[] operationsArray() {
        return operationsArray;
    }

    /// {@link Ir64Op#kind()} de cada operação, resolvido uma única vez na construção — evita a
    /// chamada virtual megamórfica de `kind()` por op a cada execução do bloco.
    ///
    /// **Uso interno:** paralelo a {@link #operationsArray()}, mesma regra de imutabilidade.
    public int[] kindsArray() {
        return kindsArray;
    }

    /// Cria um builder mutável para montar um bloco antes de selá-lo.
    public static Builder builder(long startPc) {
        return new Builder(startPc);
    }

    /// Builder mutável usado pelo lifter.
    public static final class Builder {
        private final long startPc;
        private final List<Ir64Op> operations = new ArrayList<>();
        private long endPc;

        private Builder(long startPc) {
            this.startPc = startPc;
            this.endPc = startPc;
        }

        /// Adiciona uma operação IR ao bloco em construção.
        public Builder add(Ir64Op op) {
            operations.add(op);
            return this;
        }

        /// Retorna `true` quando nenhuma operação foi adicionada ainda.
        public boolean isEmpty() {
            return operations.isEmpty();
        }

        /// Define o endereço exclusivo de fim do bloco.
        public Builder endPc(long endPc) {
            this.endPc = endPc;
            return this;
        }

        /// Sela o bloco e devolve uma instância imutável.
        public Ir64Block sealed() {
            return new Ir64Block(startPc, endPc, operations);
        }
    }
}
