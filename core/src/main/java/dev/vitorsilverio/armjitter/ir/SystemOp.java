package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;

/// Operações A32/T32 de sistema: geração e retorno de exceção, acesso a `CPSR`/`SPSR` e a
/// registradores bancados, coprocessador, estado de execução (`CPS`/`SETEND`/`IT`), hints e os
/// registradores especiais do perfil M.
///
/// Sub-interface selada de {@link IrOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface SystemOp extends IrOp permits SystemOp.PsrTransfer, SystemOp.Hvc,
        SystemOp.Smc, SystemOp.Eret, SystemOp.MrsBank, SystemOp.MsrBank, SystemOp.Swi,
        SystemOp.Breakpoint, SystemOp.Coprocessor, SystemOp.CoprocessorDouble, SystemOp.Undefined,
        SystemOp.ChangeProcessorState, SystemOp.SetEndianness, SystemOp.StoreReturnState,
        SystemOp.ReturnFromException, SystemOp.WaitForInterrupt, SystemOp.MemoryBarrier,
        SystemOp.SetItState, SystemOp.MProfileSystemRegister, SystemOp.Nocp,
        SystemOp.SecureGateway {

    /// Transferência entre registradores gerais e CPSR/SPSR.
    record PsrTransfer(
            /// `true` para MRS, `false` para MSR.
            boolean read,
            /// `true` para SPSR, `false` para CPSR.
            boolean spsr,
            /// Registrador geral de destino/origem.
            int register,
            /// Valor fixo para usar na escrita por registrador, ou `-1`.
            int registerValueOverride,
            /// Imediato expandido para `MSR #imm`.
            int immediate,
            /// Indica que `immediate` deve ser usado no lugar de `register`.
            boolean immediateOperand,
            /// Máscara de campos PSR para MSR.
            int fieldMask,
            /// Condição necessária para executar a transferência.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.PSR_TRANSFER; }
    }

    /// `HVC` (B9.8.2, ARM DDI 0406C A8.8.65): entra em Hyp mode via `ArmException#HVC` — ao
    /// contrário de {@link Swi}, não delega a nenhum dispatcher do host, é semântica pura do core
    /// (mesma categoria de {@link Undefined}, sem colaborador externo).
    record Hvc(
            /// `imm16` da instrução, sem uso funcional hoje (fidelidade de trace/debug).
            int immediate,
            /// Condição necessária para executar (encoding real, checado normalmente — ao
            /// contrário de {@link Breakpoint}, que é incondicional).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.HVC; }
    }

    /// `SMC` (B9.8.3, ARM DDI 0406C A8.8.20): entra em Monitor mode via `ArmException#SMC` —
    /// mesma categoria de {@link Hvc} (semântica pura do core, sem colaborador externo). `LR` é
    /// bancado normalmente em Monitor mode (`LR_mon`, B9.8.1), ao contrário de {@link Hvc}/
    /// `ELR_hyp`.
    record Smc(
            /// `imm4` da instrução, sem uso funcional hoje (fidelidade de trace/debug).
            int immediate,
            /// Condição necessária para executar (encoding real, checado normalmente — mesmo
            /// espaço condicional de {@link Hvc}/{@link Swi}).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.SMC; }
    }

    /// `ERET` (B9.8.4, A32, ARM DDI 0406C B9.3.3): retorna de exceção — `PC`←`ELR_hyp` (Hyp mode)
    /// ou `LR` do banco ativo (qualquer outro modo privilegiado), `CPSR`←SPSR do modo ativo.
    /// `UNDEFINED` em modo `USER`. Sem operandos de registrador (`Rn` fixo em `1111` no encoding,
    /// não lido) — mesma categoria de {@link Hvc}/{@link Smc} (semântica pura do core), mas SEM
    /// `ArmException` própria: é uma instrução de RETORNO pura, mesmo tratamento de
    /// {@link ReturnFromException}/`RFE`, não de {@link Hvc}/{@link Smc}.
    record Eret(
            /// Condição necessária para executar (encoding real, checado normalmente — mesmo
            /// espaço condicional de {@link Hvc}/{@link Smc}/{@link Swi}).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.ERET; }
    }

    /// `MRS` (forma bancada, B9.8.5, ARM DDI 0406C A8.8.64): lê um registrador geral ou `SPSR` de
    /// outro modo (não o ativo) — `sysm`/`r` já resolvidos em `(modo, registrador)` em tempo de
    /// DECODE (`BankedRegisterSysm`), então esta op é semântica pura do core, sem re-decodificar
    /// nada. `UNDEFINED` em modo `USER` (checado em tempo de EXECUÇÃO, mesma convenção de
    /// {@link Hvc}/{@link Smc}/{@link Eret}). Sem checagem de Secure/Monitor state (simplificação
    /// documentada em `b9.8-plano-hyp-monitor-32bit.md`).
    record MrsBank(
            /// Registrador geral de destino (`Rd`).
            int armRegister,
            /// Modo do registrador bancado alvo (não necessariamente o modo ativo).
            CpuMode targetMode,
            /// Índice do registrador bancado (8-14), significativo só quando {@link #elrHyp()} e
            /// {@link #spsr()} são ambos `false`.
            int bankedRegister,
            /// `true` quando o alvo é `ELR_hyp` — registrador à parte, fora de R0-R15 (Hyp mode
            /// não banca `LR`, ver `ArmCore#elrHyp`).
            boolean elrHyp,
            /// `true` quando o alvo é o `SPSR` do modo (em vez de um registrador geral).
            boolean spsr,
            /// Condição necessária para executar.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.MRS_BANK; }
    }

    /// `MSR` (forma bancada, B9.8.5): escreve um registrador geral num registrador geral ou `SPSR`
    /// de outro modo — mesma convenção de {@link MrsBank}.
    record MsrBank(
            /// Registrador geral de origem (`Rn`).
            int armRegister,
            /// Modo do registrador bancado alvo.
            CpuMode targetMode,
            /// Índice do registrador bancado (8-14), significativo só quando {@link #elrHyp()} e
            /// {@link #spsr()} são ambos `false`.
            int bankedRegister,
            /// `true` quando o alvo é `ELR_hyp`.
            boolean elrHyp,
            /// `true` quando o alvo é o `SPSR` do modo.
            boolean spsr,
            /// Condição necessária para executar.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.MSR_BANK; }
    }

    /// Operação SWI delegada ao dispatcher do host.
    record Swi(
            /// Imediato da instrução SWI.
            int immediate,
            /// Condição necessária para disparar a SWI.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.SWI; }
    }

    /// `BKPT` (B7.5, ARMv5T+) **e** `HLT` (B14.1b, ARMv8-A de 32 bits) — imediato delegado ao
    /// {@link dev.vitorsilverio.armjitter.core.BkptDispatcher} do host (mesmo padrão de
    /// {@link Swi}), ou {@code ArmException.UNDEFINED} quando não há handler registrado para o
    /// imediato. As duas instruções têm nomes/encodings diferentes (`BKPT` imediato de 8 Thumb/16
    /// ARM bits; `HLT` de 6 Thumb/16 ARM bits) mas o MESMO contrato de execução observável neste
    /// projeto — decisão explícita da B14.1b, não uma coincidência a desfazer depois. Sempre
    /// incondicional (nenhum dos encodings tem campo de condição; {@link #condition()} retorna
    /// {@link Condition#AL} pelo default da interface).
    record Breakpoint(
            /// Imediato da instrução BKPT/HLT.
            int immediate) implements SystemOp {
        @Override public int kind() { return Kind.BREAKPOINT; }
    }

    /// Transferência de registrador de coprocessador (`MCR`/`MRC`), delegada ao barramento de coprocessador do core.
    record Coprocessor(
            /// `true` para `MRC` (coprocessador -> registrador ARM), `false` para `MCR`.
            boolean load,
            /// Número do coprocessador (15 para CP15).
            int coprocessor,
            /// Opcode primário (bits 23-21 da instrução).
            int opcode1,
            /// Registrador primário de coprocessador (CRn).
            int crn,
            /// Registrador secundário de coprocessador (CRm).
            int crm,
            /// Opcode secundário (bits 7-5 da instrução).
            int opcode2,
            /// Registrador ARM (Rd) lido para `MCR` ou escrito para `MRC`.
            int register,
            /// PC sequencial usado como endereço de retorno se a transferência for indefinida.
            int sequentialPc,
            /// Condição necessária para executar a transferência.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.COPROCESSOR; }
    }

    /// Transferência DUPLA de registrador de coprocessador (`MCRR`/`MRRC`, F3), delegada ao
    /// barramento de coprocessador do core. Diferente de {@link Coprocessor} (`MCR`/`MRC`): não há
    /// `CRn` nem `opcode2` — dois registradores ARM são transferidos de uma vez e `opcode1` tem 4
    /// bits (não 3).
    record CoprocessorDouble(
            /// `true` para `MRRC` (coprocessador -> registradores ARM), `false` para `MCRR`.
            boolean load,
            /// Número do coprocessador (15 para CP15).
            int coprocessor,
            /// Opcode (4 bits, distinto do `opcode1` de 3 bits de {@link Coprocessor}).
            int opcode1,
            /// Registrador de coprocessador (CRm).
            int crm,
            /// Primeiro registrador ARM (Rt) — metade baixa da faixa/valor transferido.
            int rt,
            /// Segundo registrador ARM (Rt2) — metade alta.
            int rt2,
            /// PC sequencial usado como endereço de retorno se a transferência for indefinida.
            int sequentialPc,
            /// Condição necessária para executar a transferência.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.COPROCESSOR_DOUBLE; }
    }

    /// Instrução não implementada/indefinida que deve entrar no vetor `0x04`.
    record Undefined(
            /// PC sequencial usado como endereço de retorno da exceção.
            int sequentialPc,
            /// Condição necessária para disparar a exceção.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.UNDEFINED; }
    }

    /// `CPS`/`CPSIE`/`CPSID` (ARMv6): altera os bits A/I/F e/ou o modo do CPSR pelo mesmo
    /// caminho de troca de banco usado por `MSR`/entrada de exceção ({@code ArmCore#setCpsr}).
    /// UNPREDICTABLE em modo User: tratado como NOP (ver `IrSystemExecutor`).
    record ChangeProcessorState(
            /// `true` quando `mode` deve substituir os 5 bits de modo do CPSR.
            boolean changeMode,
            /// Campo de modo cru (5 bits), válido só quando `changeMode`. Convertido para
            /// `CpuMode` em tempo de EXECUÇÃO (não no lift) — mantém o mesmo risco de
            /// `IllegalArgumentException` para bits de modo inválidos que `MSR` já tem hoje.
            int mode,
            /// `true` quando A/I/F selecionados devem ser alterados (`imod` = IE ou ID).
            boolean changeFlags,
            /// `true` para IE (habilita, limpa os bits selecionados); `false` para ID (desabilita, seta).
            boolean enable,
            /// Altera o bit A (abort imprecisa).
            boolean changeA,
            /// Altera o bit I (IRQ).
            boolean changeI,
            /// Altera o bit F (FIQ).
            boolean changeF,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.CHANGE_PROCESSOR_STATE; }
    }

    /// `SETEND` (ARMv6): seta o bit E (endianness de dados) do CPSR.
    record SetEndianness(
            /// `true` para big-endian, `false` para little-endian.
            boolean bigEndian,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.SET_ENDIANNESS; }
    }

    /// `SRS` (ARMv6): empilha LR e SPSR ATUAIS na pilha (`R13`) de um modo alvo.
    record StoreReturnState(
            /// Campo de modo alvo cru (5 bits); convertido para `CpuMode` em tempo de execução.
            int targetMode,
            /// Modo de endereçamento (IA/IB/DA/DB).
            BlockTransferMode addressingMode,
            /// Indica writeback no `R13` do modo alvo.
            boolean writeback,
            /// PC sequencial usado como retorno se o modo atual for User/System (UNPREDICTABLE → UNDEFINED).
            int sequentialPc,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.STORE_RETURN_STATE; }
    }

    /// `RFE` (ARMv6): carrega PC e CPSR da pilha apontada por `base` (Rn).
    record ReturnFromException(
            /// Registrador base (Rn).
            int base,
            /// Modo de endereçamento (IA/IB/DA/DB).
            BlockTransferMode addressingMode,
            /// Indica writeback em `base`.
            boolean writeback,
            /// PC sequencial usado como retorno se o modo atual for User/System (UNPREDICTABLE → UNDEFINED).
            int sequentialPc,
            /// Condição necessária para executar (espaço incondicional → sempre AL).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.RETURN_FROM_EXCEPTION; }
    }

    /// `WFI` (ARMv6K hint): coloca o core em HALT até uma interrupção.
    record WaitForInterrupt(
            /// Condição necessária para executar (disfarçada de MSR — pode ser condicional).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.WAIT_FOR_INTERRUPT; }
    }

    /// `DMB`/`DSB`/`ISB` (ARMv7, Thumb-2 — B2.5): barreira de memória.
    ///
    /// Premissa explícita (ver `ArmFeature#MEMORY_BARRIERS`): esta implementação executa um único
    /// core sem reordenação especulativa de memória e sem múltiplos cores observando o mesmo
    /// espaço de endereço concorrentemente — logo, toda barreira já está satisfeita antes mesmo de
    /// ser emitida, e a única ação correta é NOP observável (nenhum registrador, flag ou memória
    /// muda). Isso deixa de valer no dia em que a trilha B6/AArch64 ou um modelo multi-core
    /// entrarem em cena; nesse ponto esta simplificação precisa ser revisitada.
    record MemoryBarrier(
            /// Condição necessária para executar (espaço incondicional em Thumb-2 → sempre AL).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.MEMORY_BARRIER; }
    }

    /// Grava o ITSTATE\[7:0\] do CPSR (Thumb-2 IT block, B2.4 — ver
    /// {@link dev.vitorsilverio.armjitter.core.ItState}). Duas origens, ambas emitidas pelo
    /// lifter (não pelo decoder isoladamente — ver `StandardIrBlockLifter`):
    /// <ol>
    ///   <li>A própria instrução `IT`: grava o ITSTATE de entrada (`firstcond:mask`); condição =
    ///       a da instrução `IT` em si (normalmente AL; só difere quando o `IT` está — de forma
    ///       CONSTRAINED UNPREDICTABLE, ver Armadilhas de B2.4 — aninhado dentro de outro IT
    ///       block, caso em que herda a condição do bloco externo).</li>
    ///   <li>O "avanço" (`ItState#advance`) emitido pelo lifter logo após CADA instrução coberta
    ///       por um IT block ativo: sempre com condição {@link Condition#AL} — o avanço do
    ///       ITSTATE é incondicional no hardware real, independente de a instrução coberta ter
    ///       sido de fato executada (guard verdadeiro) ou pulada (guard falso).</li>
    /// </ol>
    record SetItState(
            /// Novo valor ITSTATE\[7:0\] a gravar no CPSR (`0` = fora de IT block).
            int itState,
            /// Condição necessária para executar esta gravação.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.SET_IT_STATE; }
    }

    /// `MRS`/`MSR` na forma SYSm do perfil M (B7.4): transfere um registrador especial Cortex-M
    /// (número `sysm`) de/para um registrador ARM de propósito geral. Distinta de
    /// {@link PsrTransfer} (perfil A, que carrega semântica de CPSR/SPSR + máscara de campos `_fsxc`
    /// que não se aplica aqui): o alvo é um dos registradores especiais do perfil M
    /// (APSR/IPSR/XPSR/MSP/PSP/PRIMASK/BASEPRI/FAULTMASK/CONTROL...), resolvido pelo
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}. Só produzida pelo decoder
    /// quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#M_PROFILE} está ativo, portanto o
    /// executor pode assumir que o `ExceptionModel` instalado é um `MProfileExceptionModel`.
    record MProfileSystemRegister(
            /// `true` para `MRS` (registrador especial → registrador ARM); `false` para `MSR`
            /// (registrador ARM → registrador especial).
            boolean read,
            /// Registrador ARM de propósito geral: destino do `MRS`, fonte do `MSR`.
            int armRegister,
            /// Número do registrador especial (campo `SYSm` do encoding).
            int sysm,
            /// Condição necessária para executar a transferência.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.M_PROFILE_SYSTEM_REGISTER; }
    }

    /// `NOCP`/`NOCP_8_1` (perfil M, B15.2, `target/isa-decode/m-nocp.decode`): tentativa de acessar
    /// um coprocessador ausente/desabilitado — seta `UFSR.NOCP` (`CFSR` em `0xE000ED28`) e entra em
    /// {@link dev.vitorsilverio.armjitter.core.MProfileException#USAGE_FAULT} via
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}. Só produzida pelo decoder
    /// quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#M_PROFILE} está ativo, portanto o
    /// executor pode assumir que o `ExceptionModel` instalado é um `MProfileExceptionModel` — mesmo
    /// contrato de {@link MProfileSystemRegister}.
    record Nocp(
            /// Coprocessador-alvo (`cp`, bits\[11:8\] do encoding; fixo em `10` para `NOCP_8_1`) —
            /// sem uso funcional hoje, carregado só por fidelidade de trace/debug (mesmo padrão de
            /// {@link Hvc}/{@link Smc}).
            int coprocessor,
            /// Condição necessária para executar a exceção.
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.NOCP; }
    }

    /// `SG` (Secure Gateway, perfil M, B15.4): entra em estado Secure e limpa o `bit0` de `LR` —
    /// via {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#secureGateway}. Não
    /// muda o PC (a execução continua na instrução seguinte); marcada terminal no lifter mesmo
    /// assim, mesma categoria de {@link Nocp}/`COPROCESSOR` (muda estado observável da CPU).
    record SecureGateway(
            /// Condição necessária para executar (sempre {@link Condition#AL} em Thumb comum).
            Condition condition) implements SystemOp {
        @Override public int kind() { return Kind.SECURE_GATEWAY; }
    }
}
