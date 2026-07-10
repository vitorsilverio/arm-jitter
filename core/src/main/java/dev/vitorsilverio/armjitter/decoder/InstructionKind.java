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
    /// Conta zeros a esquerda em uma palavra de 32 bits.
    CLZ,
    /// Aritmética de saturação ARMv5TE (QADD/QSUB/QDADD/QDSUB).
    SATURATING,
    /// Multiplicações DSP ARMv5TE (SMUL\<xy>, SMLA\<xy>, SMLAW\<y>, SMULW\<y>, SMLAL\<xy>).
    DSP_MULTIPLY,
    /// Transferência de palavra dupla ARMv5TE (LDRD/STRD) — par de registradores Rd, Rd+1.
    DOUBLE_TRANSFER,
    /// Extensão de byte/halfword ARMv6 (SXT*/UXT*, com rotação e acumulador opcional).
    /// `sourceRegister` = Rn acumulador ou `-1` na forma sem acumulador; `immediate` empacota:
    /// bits 1:0 = rotação/8, bits 3:2 = campo (00=B16, 10=B, 11=H), bit 4 = unsigned.
    EXTEND,
    /// Inversão de bytes ARMv6. `immediate` seleciona a variante: 0=REV, 1=REV16, 2=REVSH.
    BYTE_REVERSE,
    /// Multiplicação longa unsigned ARMv6 com acumulador duplo (`UMAAL`) — mesmo layout de
    /// registradores de UMULL/UMLAL (dst=RdLo, immediate=RdHi); nunca escreve flags.
    UMAAL,
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
    SWI,
    /// Coprocessor register transfer (`MCR`/`MRC`), e.g. CP15 on the ARM9.
    COPROCESSOR
}
