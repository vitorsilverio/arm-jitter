package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64SystemExecutor;

/// Operações A64 de sistema: geração e retorno de exceção, acesso a registrador de sistema,
/// instruções de sistema (`SYS`/`AT`/hints), máscara de interrupção e controle do modo streaming.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SystemOp64 extends Ir64Op permits SystemOp64.Svc, SystemOp64.SystemRegister,
        SystemOp64.SystemInstruction, SystemOp64.ExceptionReturn, SystemOp64.PrivilegedCall,
        SystemOp64.AddressTranslate, SystemOp64.InterruptMask, SystemOp64.Breakpoint,
        SystemOp64.UndefinedInstructionTrap, SystemOp64.StreamingModeControl {

    /// `SVC` (`ARM DDI 0487 C6.2.311`): chamada de sistema delegada ao dispatcher do host — mesmo
    /// papel de {@link dev.vitorsilverio.armjitter.ir.SystemOp.Swi} no IR de 32 bits, mas sem campo
    /// de condição (A64 não tem `SVC` condicional).
    record Svc(
            /// Imediato de 16 bits da instrução `SVC`.
            int immediate) implements SystemOp64 {
        @Override public int kind() { return Kind.SVC; }
        @Override public boolean execute(Aarch64Core core) { return Ir64SystemExecutor.executeSvc(core, this); }
    }

    /// `MRS`/`MSR (register)` (`ARM DDI 0487 C5.2.3`, B6.6.1) — leitura/escrita de um registrador
    /// de sistema nomeado. O registrador é identificado pela 5-upla `op0:op1:CRn:CRm:op2` do
    /// encoding, já resolvida pelo DECODER em {@link Aarch64SystemRegisterId} (nunca pelo
    /// executor a partir dos bits crus). Não existe forma `W`: o bit mais alto da instrução é
    /// parte do prefixo fixo do encoding (não um `sf`), então `Rt` é sempre o registrador `X`
    /// completo — `31` em {@link #rt} é `XZR` (`MRS` descarta a escrita; `MSR` lê `0`).
    record SystemRegister(
            /// `true` para `MRS` (leitura, `L=1`); `false` para `MSR` (escrita, `L=0`).
            boolean read,
            /// Registrador de sistema identificado pelo decoder.
            Aarch64SystemRegisterId register,
            /// Registrador geral envolvido: destino em `MRS`, origem em `MSR` (índice `0`-`31`;
            /// `31` é `XZR`).
            int rt) implements SystemOp64 {
        @Override public int kind() { return Kind.SYSTEM_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeSystemRegister(core, this);
        }
    }

    /// `SYS`/`SYS(L)` (`ARM DDI 0487 C5.2.3`, task B6.6.3) — subconjunto mínimo reconhecido:
    /// `TLBI VMALLE1`/`TLBI VMALLE1IS` e as barreiras `DSB`/`ISB`/`DMB`. Diferente de
    /// {@link SystemRegister}: não carrega registrador geral nenhum (`TLBI VMALLE1`/barreiras não
    /// leem/escrevem `Rt` — o campo existe no encoding só porque compartilha o formato de `SYS`,
    /// mas o decoder não precisou dele para o subconjunto coberto aqui).
    record SystemInstruction(
            /// Sub-operação identificada pelo decoder.
            Ir64SystemInstructionOp opcode,
            /// Registrador de origem do operando (`Xt`, `31` = `XZR`) para as sub-operações que o
            /// leem (`IC IVAU`); {@link #NO_REGISTER} nas demais.
            int rt) implements SystemOp64 {
        /// Valor de {@link #rt} das sub-operações que não leem registrador nenhum.
        public static final int NO_REGISTER = -1;

        /// Sub-operação sem operando de registrador.
        public SystemInstruction(Ir64SystemInstructionOp opcode) {
            this(opcode, NO_REGISTER);
        }

        @Override public int kind() { return Kind.SYSTEM_INSTRUCTION; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeSystemInstruction(core, this);
        }
    }

    /// `ERET` (`ARM DDI 0487 C6.2.111`, task B6.6.4) — retorna de EL1 para EL0:
    /// `PC←ELR_EL1`, `PSTATE.{N,Z,C,V}←SPSR_EL1`, sai de EL1. Record dedicado (não reaproveita
    /// {@link SystemInstruction}, decisão registrada na task): a semântica muda `PC` e `PSTATE`
    /// como um desvio tomado, MUITO diferente de `TLBI`/barreira (NOPs observáveis do ponto de
    /// vista do fluxo de controle) — misturar os dois no mesmo tipo confundiria o executor (teria
    /// que devolver `true`/`false` de `boolean` dependendo do sub-opcode). Sem operandos: o
    /// encoding fixa `Rn=31` (não lido, `ARM DDI 0487` pseudocódigo de `ERET`).
    record ExceptionReturn() implements SystemOp64 {
        @Override public int kind() { return Kind.EXCEPTION_RETURN; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeExceptionReturn(core, this);
        }
    }

    /// `HVC`/`SMC` (`ARM DDI 0487 C6.2.148/C6.2.294`, task B6.6.7; `HVC` real desde B10.4, `SMC`
    /// real desde B10.5). `HVC` entra em EL2 de verdade via
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#enterHypervisorCall}; `SMC` entra em
    /// EL3 de verdade via
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#enterSecureMonitorCall} — ver
    /// `Ir64SystemExecutor#executePrivilegedCall`. Sem campo de imediato: o `imm16` do encoding só
    /// teria sentido para um handler em EL2/EL3 que leia a própria instrução, que este emulador não
    /// modela (ver Armadilhas das tasks B10.4/B10.5).
    ///
    /// @param isHvc `true` para `HVC` (entra em EL2, B10.4), `false` para `SMC` (entra em EL3,
    ///              B10.5)
    record PrivilegedCall(boolean isHvc) implements SystemOp64 {
        @Override public int kind() { return Kind.PRIVILEGED_CALL; }
        @Override public boolean execute(Aarch64Core core) { return Ir64SystemExecutor.executePrivilegedCall(this); }
    }

    /// `AT` (`ARM DDI 0487 C6.2.23`, task B10.6) — traduz `Xt` (VA) pelo regime real (EL1&0, EL2
    /// puro, EL3 puro, ou EL1&0+stage-2 combinado, conforme a forma) e escreve `PAR_EL1`, SEM gerar
    /// acesso de memória nem exceção síncrona para o guest (falha vira `PAR_EL1.F=1`, nunca um
    /// abort) — ver
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters#addressTranslate}.
    ///
    /// @param form forma decodificada (`S1E1R`/`S1E1W`/`S1E0R`/`S1E0W`/`S1E2R`/`S1E2W`/`S1E3R`/
    ///             `S1E3W`/`S12E1R`/`S12E1W`/`S12E0R`/`S12E0W`)
    /// @param rt   registrador de origem do VA (índice `0`-`31`; `31` é `XZR`, mesma convenção de
    ///             {@link SystemRegister#rt})
    record AddressTranslate(Aarch64AddressTranslateForm form, int rt) implements SystemOp64 {
        @Override public int kind() { return Kind.ADDRESS_TRANSLATE; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeAddressTranslate(core, this);
        }
    }

    /// `MSR (immediate) DAIFSet`/`DAIFClr` (`ARM DDI 0487 C6.2.149/C6.2.150`, B8.3, subgrupo
    /// "MSR (immediate)" da classe System) — únicas duas formas de `MSR (immediate)` desta task
    /// com efeito observável real: as demais (`UAO`/`PAN`/`SPSel`/`SBSS`/`DIT`/`TCO`) viram
    /// {@link SystemInstruction} com {@link Ir64SystemInstructionOp#PSTATE_FIELD_NOP} porque este
    /// emulador não modela os campos correspondentes de `PSTATE` (ver javadoc daquele valor). O bit
    /// `I` de `DAIF` (mascaramento de IRQ) É modelado
    /// ({@link dev.vitorsilverio.armjitter.core64.PstateRegister#irqDisabled()}, B6.6.7) — por
    /// isso `DAIFSet`/`DAIFClr` ganham um record próprio em vez de virarem NOP como o resto do
    /// grupo.
    record InterruptMask(
            /// `true` para `DAIFSet` (seta os bits de `imm` em `DAIF`); `false` para `DAIFClr`
            /// (limpa).
            boolean set,
            /// Máscara de 4 bits do encoding (`imm[3:0]`, ordem `D:A:I:F` do manual — só o bit `I`
            /// (posição 1) tem efeito neste emulador; `D`/`A`/`F` são ignorados, mesma decisão já
            /// tomada para o resto de `DAIF` em B6.6.7).
            int mask) implements SystemOp64 {
        @Override public int kind() { return Kind.INTERRUPT_MASK; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeInterruptMask(core, this);
        }
    }

    /// `BRK` (`ARM DDI 0487 C6.2.29`, B8.3) — gera uma exceção síncrona de "Breakpoint Instruction"
    /// (`ESR_EL1.EC=0x3C`) incondicionalmente, independente de estado de debug (ao contrário de
    /// `HLT`, ver {@link UndefinedInstructionTrap}) — é assim que o Linux usa `BRK` para
    /// `BUG()`/`WARN_ON()`/UBSAN em builds de kernel normais, sem depender de nenhum debugger
    /// externo conectado. Executor lança
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException}, capturada no mesmo
    /// ponto que {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64}
    /// (`Ir64BlockExecutor#step`/`#executeBlock`) — o endereço da própria instrução `BRK` (ELR_EL1)
    /// vem do rastreamento de `Fetch` já existente ali, não deste record.
    record Breakpoint(
            /// Imediato de 16 bits do encoding — vira `ESR_EL1.ISS[15:0]` (`comment` do `BRK`,
            /// convenção do Linux/GDB para identificar o motivo do trap).
            int immediate) implements SystemOp64 {
        @Override public int kind() { return Kind.BREAKPOINT; }
        @Override public boolean execute(Aarch64Core core) { return Ir64SystemExecutor.executeBreakpoint(this); }
    }

    /// `HLT` (`ARM DDI 0487 C6.2.148`, B8.3) — instrução de "Halting debug": sem estado de debug
    /// externo modelado neste emulador (mesma decisão já registrada para os registradores de debug
    /// em `Aarch64Core#ID_AA64DFR0_EL1_VALUE`), o pseudocódigo real do manual cai no caminho
    /// `UNDEFINED` (`Halting_instruction`, "Otherwise, treat as UNDEFINED") — mesmo tratamento
    /// arquitetural de um encoding reservado, só que agora um encoding REAL e nomeado, não uma
    /// combinação de bits arbitrária. Sem operando: o imediato de 16 bits do encoding só teria
    /// sentido para o host de debug externo, que não existe aqui (mesmo raciocínio do `imm16` de
    /// `HVC`/`SMC` descartado em {@link PrivilegedCall}).
    record UndefinedInstructionTrap() implements SystemOp64 {
        @Override public int kind() { return Kind.UNDEFINED_INSTRUCTION_TRAP; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeUndefinedInstructionTrap();
        }
    }

    /// `MSR SVCRSM`/`SVCRZA`/`SVCRSMZA, #imm` (`FEAT_SME`, B18.2) — os aliases `SMSTART`/`SMSTOP` do
    /// manual. Liga (`enable = true`, `SMSTART`) ou desliga (`SMSTOP`) `PSTATE.SM` e/ou `PSTATE.ZA`, com
    /// os efeitos destrutivos de {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#setSvcr}. Precede
    /// a checagem de acesso SME (`CheckSMEAccess`): sem `CPACR_EL1.SMEN` etc. a instrução trapa em vez de
    /// executar. **Terminal de bloco** (o `VL` efetivo das instruções seguintes muda).
    record StreamingModeControl(
            /// `true` para `SMSTART` (`imm = 1`), `false` para `SMSTOP`.
            boolean enable,
            /// `true` quando o alias afeta `PSTATE.SM` (`SVCRSM` e `SVCRSMZA`).
            boolean streamingMode,
            /// `true` quando o alias afeta `PSTATE.ZA` (`SVCRZA` e `SVCRSMZA`).
            boolean za,
            /// Endereço da própria instrução — a exceção de acesso SME (`EC=0x1D`) precisa dele, e o
            /// executor não conhece o PC da instrução (mesmo precedente de {@link IntegerOp64.PcRelative}).
            long instructionAddress) implements SystemOp64 {
        @Override public int kind() { return Kind.STREAMING_MODE_CONTROL; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeStreamingModeControl(core, this);
        }
    }
}
