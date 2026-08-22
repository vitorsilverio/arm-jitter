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
    /// Deslocamento lógico à esquerda.
    LSL,
    /// Deslocamento lógico à direita.
    LSR,
    /// Deslocamento aritmético à direita.
    ASR,
    /// Rotação à direita.
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
    /// Interrupção de software.
    SWI,
    /// Transferência de registrador de coprocessador (`MCR`/`MRC`), ex.: CP15 no ARM9.
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
    MOVE_TOP,
    /// `DMB`/`DSB`/`ISB` (ARMv7, Thumb-2 B2.5): barreira de memória. `immediate` carrega o campo
    /// `option` cru (bits 3:0) só para rastreabilidade — a semântica atual ignora o valor (NOP
    /// observável, ver {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier}).
    MEMORY_BARRIER,
    /// `IT` (Thumb-2, ARMv6T2+, B2.4): abre um IT block. `immediate` carrega o ITSTATE\[7:0\] de
    /// ENTRADA já montado (`firstcond:4 ++ mask:4`, com `firstcond==0b1111` já normalizado para
    /// `0b1110`/AL pelo decoder — ver {@link dev.vitorsilverio.armjitter.core.ItState}). O lifter
    /// (não o decoder) consome este valor para semear o estado local threaded pelas até 4
    /// instruções seguintes — ver D1 em `b2.4-thumb2-branches-it.md`.
    IT,
    /// `TBB`/`TBH` (Thumb-2, ARMv6T2+, B2.4). `sourceRegister`=Rn, `secondSourceRegister`=Rm,
    /// `immediate` bit 0 = forma halfword (`TBH`, `1`) vs byte (`TBB`, `0`).
    TABLE_BRANCH,
    /// `CBZ`/`CBNZ` (Thumb-1, ARMv6T2+, B2.4). `sourceRegister`=Rn (R0-R7), `immediate`=endereço
    /// absoluto de destino já resolvido, `link`=`true` para `CBNZ` (desvia se `rn≠0`), `false`
    /// para `CBZ` (desvia se `rn==0`). Nunca afeta NZCV.
    COMPARE_BRANCH_ZERO,
    /// `BL`/`BLX` imediato Thumb-2 (B2.6, ARM DDI 0406C A8.8.25) como instrução ÚNICA de 32
    /// bits — só decodificada assim quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#THUMB2}
    /// está ativo (sem a feature, o par continua decodificado como dois halfwords
    /// independentes, {@link #LONG_BRANCH_PREFIX} + {@link #LONG_BRANCH_SUFFIX}, G2/G3). `raw`
    /// carrega os dois halfwords combinados (`(hi<<16)|lo`), sempre negativo —
    /// `dev.vitorsilverio.armjitter.ir.StandardIrBuilder` eleva esta instrução diretamente para o
    /// MESMO par de `IrOp` (`ThumbBlPrefix`+`ThumbBlSuffix`) que o caminho legado produzia, sem
    /// semântica nova; os demais campos desta instrução não são usados (recompute a partir de
    /// `raw` no builder).
    LONG_BRANCH_32,
    /// `MLS` (ARM/Thumb-2, ARMv6T2+, B3.1): `Rd = Ra − Rn×Rm`. `destinationRegister`=Rd,
    /// `sourceRegister`/`secondSourceRegister` = os dois fatores, `immediate` = Ra (acumulador),
    /// mesmo layout de campos que `MLA` (a subtração é sinalizada só na elevação para IR).
    MLS,
    /// `SBFX`/`UBFX` (ARM/Thumb-2, ARMv6T2+, B3.1): extração de campo de bits com/sem sinal.
    /// `destinationRegister`=Rd, `sourceRegister`=Rn, `immediate` empacota bits 4:0=lsb,
    /// 9:5=width, `signedAccess`=`true` para `SBFX`.
    BIT_FIELD_EXTRACT,
    /// `BFI`/`BFC` (ARM/Thumb-2, ARMv6T2+, B3.1): inserção/limpeza de campo de bits.
    /// `destinationRegister`=Rd, `sourceRegister`=Rn (`-1` para `BFC`), `immediate` empacota
    /// bits 4:0=lsb, 9:5=width.
    BIT_FIELD_INSERT,
    /// `RBIT` (ARM/Thumb-2, ARMv6T2+, B3.1): inverte a ordem dos bits. `destinationRegister`=Rd,
    /// `sourceRegister`=Rm.
    BIT_REVERSE,
    /// `SDIV`/`UDIV` (ARM/Thumb-2, ARMv7, B3.1): divisão inteira truncada para zero, resultado 0
    /// em divisão por zero. `destinationRegister`=Rd, `sourceRegister`=dividendo (Rn),
    /// `secondSourceRegister`=divisor (Rm), `signedAccess`=`true` para `SDIV`.
    DIVIDE,

    // ── VFP (B3.5): decode do espaço coprocessador CP10/CP11 — ver `VfpDecoder`. Em todos os
    // kinds abaixo `signedAccess` é reaproveitado para carregar `doublePrecision` (exceto
    // `VFP_CONVERT`, onde a própria `IrOp.VfpConversion` já fixa a precisão de cada lado) e
    // `link` para carregar a "direção" da transferência (ver cada kind). ──

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`/`VMLA`/`VMLS`/`VNMUL`/`VNEG`/`VABS`/`VSQRT`/`VMOV` registrador
    /// (VFPv2). `destinationRegister`=Vd, `sourceRegister`=Vn (`-1` nas formas unárias
    /// NEG/ABS/SQRT/COPY), `secondSourceRegister`=Vm, `immediate`=ordinal de
    /// `IrOp.VfpOperation`, `signedAccess`=precisão dupla.
    VFP_ALU,
    /// `VMOV.F32`/`VMOV.F64 Vd,#imm` (VFPv3-d16). `destinationRegister`=Vd, `immediate`=`imm8`
    /// cru (0-255, ainda não expandido — `VFPExpandImm` roda no `StandardIrBuilder`),
    /// `signedAccess`=precisão dupla.
    VFP_MOVE_IMMEDIATE,
    /// `VCMP`/`VCMPE` (com ou sem `#0.0`). `destinationRegister`=Vd, `secondSourceRegister`=Vm
    /// (`-1` quando compara com zero), `immediate` bit 0 = compara com zero, bit 1 = `VCMPE`,
    /// `signedAccess`=precisão dupla.
    VFP_COMPARE,
    /// `VCVT` (forma default, não `VCVTR`/fixed-point). `destinationRegister`=Vd,
    /// `sourceRegister`=Vm, `immediate`=ordinal de `IrOp.VfpConversion` (já fixa a precisão de
    /// cada lado, sem precisar de `signedAccess` aqui).
    VFP_CONVERT,
    /// `VLDR`. `destinationRegister`=Vd, `sourceRegister`=base (Rn), `immediate`=offset em bytes
    /// já resolvido (±`imm8`×4), `signedAccess`=precisão dupla.
    VFP_LOAD,
    /// `VSTR` (ver {@link #VFP_LOAD}).
    VFP_STORE,
    /// `VLDM`/`VPOP` (alias de `VLDMIA SP!`). `sourceRegister`=base (Rn), `immediate` empacota:
    /// bits 5:0 = primeiro registrador (Vd combinado), bits 11:6 = quantidade de registradores
    /// consecutivos (já convertida de `imm8` para contagem real — `imm8` para dupla é o dobro da
    /// contagem), `writeback`, `blockTransferMode` (`IA` ou `DB`, só essas duas existem no VFP),
    /// `signedAccess`=precisão dupla.
    VFP_LOAD_MULTIPLE,
    /// `VSTM`/`VPUSH` (alias de `VSTMDB SP!`) (ver {@link #VFP_LOAD_MULTIPLE}).
    VFP_STORE_MULTIPLE,
    /// `VMOV Rt,Sn`/`VMOV Sn,Rt` (`FMRS`/`FMSR`). `destinationRegister`=Rt (registrador ARM),
    /// `sourceRegister`=Sn, `link`=`true` para `Sn`→`Rt` (`FMRS`).
    VFP_CORE_TRANSFER,
    /// `VMOV Rt,Rt2,Dm`/`VMOV Dm,Rt,Rt2` (`FMRRD`/`FMDRR`). `destinationRegister`=Rt (metade
    /// baixa), `sourceRegister`=Rt2 (metade alta), `secondSourceRegister`=Dm, `link`=`true` para
    /// `Dm`→`(Rt,Rt2)` (`FMRRD`).
    VFP_CORE_PAIR_TRANSFER,
    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`, só o registrador FPSCR — FPSID/FPEXC fora de escopo).
    /// `destinationRegister`=registrador ARM envolvido, `link`=`true` para `VMRS` (FPSCR→destino).
    VFP_SYSTEM_TRANSFER,

    // ── Perfil M (B7.4): MRS/MSR na forma SYSm (número de registrador especial), distinta da
    // forma A-profile CPSR/SPSR+máscara. Só produzidas quando `ArmFeature.M_PROFILE` está ativo. ──

    /// `MRS Rd, <spec>` do perfil M (`MRS_v7m`): lê um registrador especial Cortex-M para um
    /// registrador ARM. `destinationRegister`=Rd, `immediate`=campo `SYSm` (número do registrador
    /// especial — ver `MProfileExceptionModel.readSystemRegister`).
    MPROFILE_MRS,
    /// `MSR <spec>, Rn` do perfil M (`MSR_v7m`): escreve um registrador especial Cortex-M a partir
    /// de um registrador ARM. `sourceRegister`=Rn, `immediate`=campo `SYSm` (a máscara de campos do
    /// encoding não é modelada — o próprio `SYSm` já decide o que a escrita afeta, ver
    /// `MProfileExceptionModel.writeSystemRegister`).
    MPROFILE_MSR,

    /// `BKPT #imm` (B7.5, ARMv5T+): `immediate`=imediato de 8 (Thumb) ou 16 (ARM, não decodificado
    /// ainda) bits, delegado ao `BkptDispatcher` do host (semihosting) via `IrOp.Breakpoint`. Sem
    /// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#BREAKPOINT} cai no UNDEFINED de sempre.
    BREAKPOINT,
    /// `MCRR`/`MRRC` (ARMv5TE+, F3): transferência DUPLA de registrador de/para coprocessador —
    /// diferente de {@link #COPROCESSOR} (`MCR`/`MRC`, um só registrador ARM + `CRn`/`opcode2`),
    /// esta forma não tem `CRn` nem `opcode2`; em troca `opcode1` tem 4 bits (não 3) e dois
    /// registradores ARM (`Rt`/`Rt2`) são transferidos de uma vez. `destinationRegister`=Rt,
    /// `sourceRegister`=Rt2, `immediate` empacota: bits 3:0=coprocessor, bits 7:4=opcode1,
    /// bits 11:8=CRm; `link`=`true` para `MRRC` (coprocessador→registradores), `false` para `MCRR`.
    COPROCESSOR_DOUBLE,

    // VFP (B9.5): 2 formas genuinamente VFPv2/v3 encontradas pela triagem de VMOV/VCVT restantes
    // -- as demais lacunas de VMOV_to_gp/VMOV_from_gp (byte/halfword) e de VCVT (conversao de/para
    // meia precisao, BFloat16) exigem NEON/extensoes opcionais posteriores (conferido em
    // target/arm/tcg/translate-vfp.c real do QEMU) e foram para docs/isa-nao-aplicavel.tsv, nao
    // implementadas aqui.

    /// `VMOV Rt,Rt2,Sm,Sm+1` / `VMOV Sm,Sm+1,Rt,Rt2` (`VMOV_64_sp`, forma depreciada mas VFPv2
    /// genuina -- ARM DDI 0406C A8.8.346): transfere um PAR de registradores `S` CONSECUTIVOS
    /// (`Sm`/`Sm+1`) de/para dois registradores ARM. Diferente de {@link #VFP_CORE_PAIR_TRANSFER}
    /// (`VMOV_64_dp`, que usa UM registrador `D`): aqui `Sm`/`Sm+1` so coincidem com um `D`
    /// completo quando `m` e par -- para `m` impar as duas metades pertencem a `D` DIFERENTES, por
    /// isso um `Kind` proprio em vez de reaproveitar o de `D` (ver Armadilhas da task).
    /// `destinationRegister`=Rt (metade baixa, `Sm`), `sourceRegister`=Rt2 (metade alta,
    /// `Sm+1`), `secondSourceRegister`=`Sm` (o primeiro dos dois `S` consecutivos), `link`=`true`
    /// para `(Sm,Sm+1)`->`(Rt,Rt2)`.
    VFP_CORE_PAIR_TRANSFER_SINGLE,
    /// `VCVT` de/para fixed-point (`VCVT_fix_{sp,dp}`, VFPv3 -- ARM DDI 0406C A8.8.397): converte,
    /// NO MESMO registrador `Vd` (fonte e destino coincidem), entre ponto flutuante e um inteiro
    /// fixo de 16 ou 32 bits com `fractionBits` bits fracionarios. `destinationRegister`=Vd,
    /// `immediate` empacota bit0=`toFixedPoint` (`true`=float->fixo, arredonda p/ zero e satura;
    /// `false`=fixo->float, arredonda ao mais proximo), bit1=`unsignedFixedPoint`, bit2=
    /// `fixedPointIs32Bit` (`true`=32 bits, `false`=16 bits), bits 7:3=`imm` cru de 5 bits
    /// (`fractionBits` = `fixedPointIs32Bit ? 32-imm : 16-imm`, resolvido no `StandardIrBuilder`,
    /// mesmo padrao de `VFPExpandImm`); `signedAccess`=precisao dupla de `Vd` (`sp`/`dp`).
    VFP_CONVERT_FIXED
}
