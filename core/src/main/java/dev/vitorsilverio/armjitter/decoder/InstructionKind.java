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
    /// Aritmética paralela ARMv6 (SADD16/UQSUB8/...). `immediate` empacota: bits 2:0 = variante
    /// (bits 22:20 do encoding — 001=S, 010=Q, 011=SH, 101=U, 110=UQ, 111=UH), bits 5:3 = operação
    /// (bits 7:5 do encoding — 000=ADD16, 001=ASX, 010=SAX, 011=SUB16, 100=ADD8, 111=SUB8).
    PARALLEL_ALU,
    /// `SEL` (ARMv6): seleciona bytes de Rn/Rm pelos flags GE do CPSR.
    SEL,
    /// `PKHBT`/`PKHTB` (ARMv6). `immediate` empacota: bits 4:0 = shift imm5,
    /// bit 5 = forma TB (1) ou BT (0).
    PKH,
    /// `SSAT`/`USAT`/`SSAT16`/`USAT16` (ARMv6). `immediate` empacota: bits 4:0 = sat_imm cru do
    /// encoding, bits 9:5 = shift imm5 (formas word), bit 10 = shift ASR (0=LSL),
    /// bit 11 = forma halfword (SSAT16/USAT16), bit 12 = sem sinal (USAT*).
    SATURATE,
    /// `USAD8`/`USADA8` (ARMv6): soma de diferenças absolutas de bytes. `immediate` = Rn
    /// acumulador, ou `-1` na forma sem acumulador.
    USAD8,
    /// `LDREX{,B,H,D}` (ARMv6/v6K): lê a memória e marca o monitor de exclusividade.
    /// `accessSizeBytes` distingue as variantes (4, 1, 2, 8).
    LOAD_EXCLUSIVE,
    /// `STREX{,B,H,D}` (ARMv6/v6K): escreve a memória apenas se o monitor cobre o endereço;
    /// `destinationRegister` recebe 0 (sucesso) ou 1 (falha).
    STORE_EXCLUSIVE,
    /// `CLREX` (ARMv6K): abre o monitor de exclusividade.
    CLEAR_EXCLUSIVE,
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
    COPROCESSOR,
    /// `CPS`/`CPSIE`/`CPSID` (ARMv6): altera os bits A/I/F e/ou o modo do CPSR. `immediate`
    /// empacota: bits 1:0 = `imod`, bit 2 = `M` (mode change), bit 3 = `A`, bit 4 = `I`,
    /// bit 5 = `F`, bits 10:6 = campo de modo cru (5 bits, válido só quando `M`=1).
    CPS,
    /// `SETEND` (ARMv6): seta o bit E (endianness de dados) do CPSR. `immediate` = valor do
    /// bit E (0=LE, 1=BE).
    SETEND,
    /// `SRS` (ARMv6): empilha LR e SPSR atuais na pilha de um modo alvo. `immediate` = campo de
    /// modo alvo cru (5 bits); `writeback`/`blockTransferMode` como em `LDM`/`STM`.
    STORE_RETURN_STATE,
    /// `RFE` (ARMv6): carrega PC e CPSR da pilha apontada por `sourceRegister` (Rn).
    /// `writeback`/`blockTransferMode` como em `LDM`/`STM`.
    RETURN_FROM_EXCEPTION,
    /// `WFI` (ARMv6K, hint): coloca o core em HALT até uma interrupção. Disfarçado de
    /// `MSR`(registrador) com máscara de campo vazia — ver `ArmDecoder`.
    WAIT_FOR_INTERRUPT,
    /// `ORN` (Thumb-2, B2.2): `Rd = Rn | ~operando`. Sem equivalente ARM classico.
    ORN,
    /// `MOVT` (Thumb-2, B2.2): escreve um imediato de 16 bits em bits[31:16] de `dst`,
    /// preservando bits[15:0]. `immediate` carrega o imediato já expandido.
    MOVE_TOP
}
