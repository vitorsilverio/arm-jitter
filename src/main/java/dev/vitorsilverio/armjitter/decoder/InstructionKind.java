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
    /// Subtrai reverso: segundo operando menos primeiro operando.
    RSB,
    /// Subtrai valores incluindo borrow invertido.
    SBC,
    /// Subtrai reverso incluindo borrow invertido.
    RSC,
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
    /// Multiplicacao inteira baixa com acumulador.
    MLA,
    /// Multiplicacao longa unsigned.
    UMULL,
    /// Multiplicacao longa unsigned com acumulador.
    UMLAL,
    /// Multiplicacao longa signed.
    SMULL,
    /// Multiplicacao longa signed com acumulador.
    SMLAL,
    /// Bit clear.
    BIC,
    /// Move NOT.
    MVN,
    /// Move PSR para registrador geral.
    MRS,
    /// Move registrador/imediato para PSR.
    MSR,
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
    /// Troca atomica simples entre registrador e memoria.
    SWAP,
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
