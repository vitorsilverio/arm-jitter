package dev.vitorsilverio.armjitter.ir64;

/// Forma de {@link Ir64Op.Branch64}: distingue o destino imediato (`B`/`BL`/`B.cond`) do destino
/// em registrador (`BR`/`BLR`/`RET`) — os dois compartilham o mesmo `IrOp` porque ambos são "só
/// desviar, com link opcional", mas o campo relevante do destino muda.
public enum Ir64BranchForm {
    /// `B`/`BL`/`B.cond`: destino é um endereço absoluto já resolvido pelo decoder
    /// ({@link Ir64Op.Branch64#target()}).
    IMMEDIATE,
    /// `BR`/`BLR`/`RET`: destino é o valor de um registrador
    /// ({@link Ir64Op.Branch64#registerOperand()}), lido em tempo de execução.
    REGISTER
}
