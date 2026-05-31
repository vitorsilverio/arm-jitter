package dev.vitorsilverio.armjitter.decoder;

/// Tipo semantico inicial de uma instrucao decodificada.
public enum InstructionKind {
    /// Instrucao nao implementada pelo decoder atual.
    UNIMPLEMENTED,
    /// Move valor para registrador.
    MOV,
    /// Soma valores.
    ADD,
    /// Soma valores incluindo carry.
    ADC,
    /// Subtrai valores.
    SUB,
    /// Subtrai valores incluindo borrow invertido.
    SBC,
    /// Negacao aritmetica.
    NEG,
    /// Operacao AND bit a bit.
    AND,
    /// Operacao EOR bit a bit.
    EOR,
    /// Operacao ORR bit a bit.
    ORR,
    /// Logical shift left.
    LSL,
    /// Logical shift right.
    LSR,
    /// Arithmetic shift right.
    ASR,
    /// Rotate right.
    ROR,
    /// Multiplicacao inteira baixa.
    MUL,
    /// Bit clear.
    BIC,
    /// Move NOT.
    MVN,
    /// Testa bits como AND sem salvar resultado.
    TST,
    /// Testa bits como EOR sem salvar resultado.
    TEQ,
    /// Compara soma atualizando flags.
    CMN,
    /// Compara valores atualizando flags.
    CMP,
    /// Le palavra relativa ao PC.
    LOAD_LITERAL,
    /// Le valor da memoria.
    LOAD,
    /// Escreve valor na memoria.
    STORE,
    /// Le multiplos registradores da memoria.
    LOAD_MULTIPLE,
    /// Escreve multiplos registradores na memoria.
    STORE_MULTIPLE,
    /// Desvio relativo ou absoluto ja resolvido pelo decoder.
    BRANCH,
    /// Branch exchange: troca PC e modo ARM/THUMB pelo bit 0 do destino.
    BRANCH_EXCHANGE,
    /// Primeira halfword de um `BL` THUMB longo.
    LONG_BRANCH_PREFIX,
    /// Segunda halfword de um `BL` THUMB longo.
    LONG_BRANCH_SUFFIX,
    /// Salva registradores na pilha.
    PUSH,
    /// Restaura registradores da pilha.
    POP,
    /// Software interrupt.
    SWI
}
