package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

import java.util.Objects;

/// Estado de uma CPU AArch64 (ARMv8-A), EL0 apenas — irmão de
/// {@link dev.vitorsilverio.armjitter.core.ArmCore}, NÃO uma extensão dele (RFC-IR-64BIT.md §3.1:
/// A64 não tem banking de registrador geral por modo, não tem PC como registrador geral, e usa
/// `PSTATE` em vez de CPSR/SPSR — um supertipo comum "gordo" com o `ArmCore` de 32 bits só
/// contaminaria as duas classes).
///
/// Escopo original (B6.1): só `EL0` (nível de privilégio de aplicação), sem MMU, sem exceções
/// síncronas/assíncronas, sem múltiplos níveis de `SP` (`SP_EL0` era o único `SP`). Esses recursos
/// entraram em tasks futuras do épico B6 (ver `b6-aarch64.md`, escopo fechado de B6.3-B6.6):
/// B6.6.4 acrescenta o PRIMEIRO estado de EL1 real (mínimo, só para abort de memória — ver
/// {@link #enterMemoryAbort} e {@link Aarch64ExceptionState} — `SVC`/`IRQ`/`FIQ`/`SError`
/// continuam fora, não é um modelo de exceção A64 completo).
public final class Aarch64Core {
    /// Quantidade de registradores de propósito geral endereçáveis (`X0`-`X30`, 31 registradores).
    /// O registrador de número 31 do encoding NUNCA é armazenado neste array — ver {@link #x} e
    /// {@link #setX}.
    private static final int GENERAL_REGISTER_COUNT = 31;
    /// Índice de encoding (`Rn`/`Rd`/`Rt` = `31`) que designa `XZR` ou `SP`, conforme a
    /// instrução — nunca um `X0`-`X30` de verdade.
    private static final int SPECIAL_REGISTER_ENCODING = 31;
    /// Máscara para a metade baixa de 32 bits (visão `W` de um registrador `X`).
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;

    /// Base em bytes, dentro da tabela de vetores apontada por `VBAR_ELx`, do grupo "exceção de um
    /// nível INFERIOR usando AArch64" (`ARM DDI 0487 D1.10`, tabela de 16 entradas de
    /// {@link #VECTOR_TABLE_ENTRY_SIZE_BYTES} cada — 4 grupos de origem × 4 tipos: `Synchronous`/
    /// `IRQ`/`FIQ`/`SError`, offset dentro do grupo por
    /// {@link #VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP}/{@link #VECTOR_IRQ_OFFSET_WITHIN_GROUP}).
    private static final long VECTOR_GROUP_LOWER_EL_AARCH64_BASE = 0x400L;
    /// Base em bytes do grupo "exceção do MESMO nível, `SP_ELx` bancado" (`ARM DDI 0487 D1.10`) —
    /// B10.1: usada quando uma exceção síncrona/IRQ ocorre já dentro de `EL1`-`EL3` (ex.: `BRK`
    /// dentro do próprio handler de EL1, ou de EL2/EL3 quando B10.2+/B10.4+/B10.5+ chegarem) — este
    /// emulador só modela a forma "h" (`SP_ELx` bancado, nunca `SP_EL0` dentro de `ELx`), então o
    /// grupo "mesmo nível com `SP_EL0`" (base `0x000`) nunca é usado.
    private static final long VECTOR_GROUP_CURRENT_EL_SPX_BASE = 0x200L;
    /// Deslocamento do tipo "Synchronous" dentro de QUALQUER grupo de origem (sempre a primeira
    /// das 4 entradas).
    private static final long VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP = 0x00L;
    /// Deslocamento do tipo "IRQ" dentro de QUALQUER grupo de origem (segunda das 4 entradas).
    private static final long VECTOR_IRQ_OFFSET_WITHIN_GROUP = 0x80L;
    /// Tamanho em bytes de cada entrada da tabela de vetores de exceção A64 (`ARM DDI 0487 D1.10`)
    /// — MUITO diferente do vetor único de 8 posições de 4 bytes do ARM32.
    private static final long VECTOR_TABLE_ENTRY_SIZE_BYTES = 0x80L;
    /// `EC` (`ESR_EL1[31:26]`) de um abort de instrução vindo de um nível de exceção inferior
    /// (`ARM DDI 0487 D17.2.30`) — CONFERIDO contra o manual nesta rodada (não `0x21`, que é
    /// "Instruction Abort taken without a change in Exception level").
    private static final long ESR_EC_INSTRUCTION_ABORT_LOWER_EL = 0x20L;
    /// `EC` (`ESR_EL1[31:26]`) de um abort de dados vindo de um nível de exceção inferior
    /// (`ARM DDI 0487 D17.2.30`).
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;
    private static final int ESR_EC_SHIFT = 26;
    /// `IL` (`ESR_EL1[25]`): sempre `1` para uma instrução de 32 bits — A64 não tem instrução
    /// curta (Thumb) que zeraria este bit, ao contrário do ARM32.
    private static final long ESR_IL_BIT = 1L << 25;
    /// Máscara do campo `ISS[5:0]` (`DFSC`/`IFSC`) usado por esta task — mesmo código de
    /// {@link FaultStatus64#code()}, sem os demais bits de `ISS` (`WnR`/`FnV`/... fora de escopo).
    private static final long ESR_ISS_FAULT_STATUS_MASK = 0x3FL;
    /// `EC` (`ESR_EL1[31:26]`) de `BRK` (`ARM DDI 0487 D17.2.30`, B8.3) — mesma classe usada pelo
    /// Linux (`do_debug_exception`/`brk_handler`) e por GDB para reconhecer um breakpoint software.
    private static final long ESR_EC_BREAKPOINT = 0x3CL;
    /// `EC` de "Unknown reason" (`ARM DDI 0487 D17.2.30`, B8.3) — usado por `HLT` sem estado de
    /// debug (ver {@link #enterUndefinedInstructionException}).
    private static final long ESR_EC_UNKNOWN_REASON = 0x00L;
    /// Máscara do imediato de 16 bits de `BRK`, que vira `ESR_EL1.ISS[15:0]` por inteiro (ao
    /// contrário do `ISS` de abort de memória, que só usa os 6 bits baixos).
    private static final long ESR_ISS_BRK_IMMEDIATE_MASK = 0xFFFFL;
    /// `EC` (`ESR_EL2[31:26]`) de `HVC` executado em AArch64 (`ARM DDI 0487 D17.2.30`, B10.4) —
    /// diferente de `0x12` (`HVC` em AArch32, fora de escopo, este emulador é A64-only).
    private static final long ESR_EC_HVC_AARCH64 = 0x16L;
    /// `HCR_EL2.HCD` (bit 29, "Hypervisor Call Disable", `ARM DDI 0487 D19.2.53`, B10.4) — só se
    /// aplica à rota `EL1`→`EL2`; em `EL2`, `HVC` sempre entra (auto-chamada), ver
    /// {@link #enterHypervisorCall} e a task, "Armadilhas".
    private static final long HCR_EL2_HCD_BIT = 1L << 29;
    /// `EC` (`ESR_EL3[31:26]`) de `SMC` executado em AArch64 (`ARM DDI 0487 D17.2.30`, B10.5) —
    /// diferente de `0x16` (`HVC`, B10.4) e de `0x13` (`SMC` em AArch32, fora de escopo).
    private static final long ESR_EC_SMC_AARCH64 = 0x17L;
    /// `SCR_EL3.SMD` (bit 7, "Secure Monitor Call Disable", `ARM DDI 0487 D19.2.101`, B10.5) —
    /// desabilita a rota para `EL3` a partir de `EL1`/`EL2`/`EL3`, mesmo raciocínio de
    /// {@link #HCR_EL2_HCD_BIT} para `HVC`; ver {@link #enterSecureMonitorCall}.
    private static final long SCR_EL3_SMD_BIT = 1L << 7;

    // ── B6.6.7: registradores de identidade da CPU, constantes fixas (sem hospedeiro plugável —
    // ── ver javadoc de Aarch64SystemRegisterId). Valores documentados registrador a registrador.
    /// `CurrentEL` quando em EL0 (`[3:2]=0b00`, ver {@link Aarch64SystemRegisterId#CURRENT_EL}).
    private static final long CURRENT_EL_VALUE_EL0 = 0L;
    /// `CurrentEL` quando em EL1 (`[3:2]=0b01`).
    private static final long CURRENT_EL_VALUE_EL1 = 0b01L << 2;
    /// `CurrentEL` quando em EL2 (`[3:2]=0b10`) — B10.1.
    private static final long CURRENT_EL_VALUE_EL2 = 0b10L << 2;
    /// `CurrentEL` quando em EL3 (`[3:2]=0b11`) — B10.1.
    private static final long CURRENT_EL_VALUE_EL3 = 0b11L << 2;
    /// `MPIDR_EL1` constante: `RES1`(31) + `U`(30, uniprocessador) setados, `Aff0`-`Aff3=0` (core
    /// único emulado, `ARM DDI 0487 D19.2.87`).
    private static final long MPIDR_EL1_VALUE = 0x8000_0000L | (1L << 30);
    /// `MIDR_EL1` constante: Cortex-A53 real do Raspberry Pi 3 (`Implementer=0x41`, `Variant=0`,
    /// `Architecture=0xF`, `PartNum=0xD03`, `Revision=4` — valor de referência publicado, alvo
    /// primário desta task via a F11 do `virtual-arm-box`).
    private static final long MIDR_EL1_VALUE = 0x410F_D034L;
    /// `ID_AA64PFR0_EL1` constante mínima: `EL0`/`EL1=0b0001` (só AArch64), demais campos `0`
    /// (ver javadoc de {@link Aarch64SystemRegisterId#ID_AA64PFR0_EL1}).
    private static final long ID_AA64PFR0_EL1_VALUE = 0x11L;
    /// `ID_AA64ISAR0_EL1` constante: `0` (nenhuma extensão opcional implementada).
    private static final long ID_AA64ISAR0_EL1_VALUE = 0L;
    /// `ID_AA64MMFR0_EL1` constante: `PARange[3:0]=0b0101` (48 bits, casando com
    /// `TranslatingAddressSpace64`), `TGran4[31:28]=0b0000` (4KiB suportado).
    private static final long ID_AA64MMFR0_EL1_VALUE = 0x5L;
    /// `ID_AA64MMFR1_EL1` constante: `0` (nenhuma extensão opcional implementada — `VHE`/`HPDS`/
    /// `LOR`/`PAN`/`VMIDBits`/... ausentes, mesma disciplina de {@link #ID_AA64ISAR0_EL1_VALUE}).
    private static final long ID_AA64MMFR1_EL1_VALUE = 0L;
    /// `ID_AA64MMFR2_EL1`/`ID_AA64MMFR3_EL1`/`ID_AA64MMFR4_EL1`/`ID_AA64PFR1_EL1`/
    /// `ID_AA64ZFR0_EL1`/`ID_AA64DFR1_EL1`/`ID_AA64ISAR1_EL1`/`ID_AA64ISAR2_EL1`/`REVIDR_EL1`
    /// constantes: `0` (nenhuma extensão opcional implementada / sem revisão específica de
    /// implementador), mesma disciplina de {@link #ID_AA64MMFR1_EL1_VALUE} — achados reais da F11
    /// (`kernel8.img` real sonda todo este grupo em sequência em `head.S`/`cpufeature.c`).
    private static final long ID_AA64MMFR2_EL1_VALUE = 0L;
    private static final long ID_AA64MMFR3_EL1_VALUE = 0L;
    private static final long ID_AA64MMFR4_EL1_VALUE = 0L;
    private static final long ID_AA64PFR1_EL1_VALUE = 0L;
    private static final long ID_AA64ZFR0_EL1_VALUE = 0L;
    private static final long ID_AA64DFR1_EL1_VALUE = 0L;
    private static final long ID_AA64ISAR1_EL1_VALUE = 0L;
    private static final long ID_AA64ISAR2_EL1_VALUE = 0L;
    private static final long REVIDR_EL1_VALUE = 0L;
    /// `ID_AA64DFR0_EL1` constante: `DebugVer[3:0]=0b0110` (ARMv8, valor de referência — nenhum
    /// registrador de debug implementado, só o campo de versão).
    private static final long ID_AA64DFR0_EL1_VALUE = 0x6L;
    /// `CTR_EL0` constante (B6.10): Cache Type Register real do Cortex-A53 (`0x84448004`, mesmo
    /// alvo de {@link #MIDR_EL1_VALUE}, valor de referência publicado pelo QEMU).
    private static final long CTR_EL0_VALUE = 0x8444_8004L;
    /// `DCZID_EL0` constante (B6.10): só o bit `DZP`(4) setado — este emulador não implementa
    /// `DC ZVA`, então anunciar o acesso como desabilitado é o valor correto (ver javadoc de
    /// {@link Aarch64SystemRegisterId#DCZID_EL0}).
    private static final long DCZID_EL0_VALUE = 0x10L;

    private final long[] x = new long[GENERAL_REGISTER_COUNT];
    /// `SP_EL0` — pilha de EL0. Só usada por {@link #sp()}/{@link #setSp(long)} quando
    /// {@code !exceptionState.inEl1()}; dentro de um handler de abort (B6.6.4), as duas leem/
    /// escrevem {@link Aarch64ExceptionState#sp1()} (`SP_EL1`) em vez deste campo.
    private long spEl0;
    private long pc;
    private final PstateRegister pstate = new PstateRegister();
    private final AddressSpace64 memory;
    private long cycles;
    private Aarch64SvcHandler svcHandler = Aarch64SvcHandler.none();
    /// Monitor de exclusividade `LDXR`/`LDAXR`/`STXR`/`STLXR` (B6.3.4) — próprio deste core por
    /// padrão (comportamento single-core), substituível por {@link #setExclusiveMonitor} caso este
    /// core algum dia precise compartilhar reservas com outro (mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore}, B5.1).
    private Aarch64ExclusiveMonitor exclusiveMonitor = new Aarch64ExclusiveMonitor();
    /// Banco de registradores FP escalar `V0`-`V31` (B6.5.1) — sempre alocado, mesmo padrão de
    /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters} em
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore} (sem flag de presença; quem gateia é o
    /// decoder por feature quando B6.5.3 chegar).
    private final Aarch64FpRegisters fp = new Aarch64FpRegisters();
    /// Barramento de registrador de sistema `MRS`/`MSR` (B6.6.1) — sem hospedeiro por padrão (ver
    /// {@link Aarch64SystemRegisterBus#none()}); B6.6.3 instala um real de MMU.
    private Aarch64SystemRegisterBus systemRegisterBus = Aarch64SystemRegisterBus.none();
    /// Estado mínimo de exceção EL0→EL1 (B6.6.4) — sempre presente (não pluggable como
    /// {@link #svcHandler}/{@link #systemRegisterBus}: `SP_EL1`/`inEl1` não têm um "sem
    /// hospedeiro" sensato, já que {@link #enterMemoryAbort} precisa deles mesmo sem nenhuma MMU
    /// instalada). Única fonte de verdade de `ELR_EL1`/`SPSR_EL1`/`ESR_EL1`/`FAR_EL1`/`VBAR_EL1`
    /// — ver javadoc de {@link Aarch64ExceptionState}.
    private final Aarch64ExceptionState exceptionState = new Aarch64ExceptionState();
    /// `TPIDR_EL1` (B6.6.7) — escaninho de 64 bits do guest (ponteiro de dados de thread do
    /// kernel), armazenamento puro sem host plugável (ver javadoc de
    /// {@link Aarch64SystemRegisterId#TPIDR_EL1}).
    private long tpidrEl1;
    /// `TPIDR_EL0`/`TPIDRRO_EL0` (B8.14) — mesmos escaninhos de {@link #tpidrEl1}, mas o par
    /// acessível de EL0 (o TLS base que TODO `crt0` real grava antes de `main()` — ver javadoc de
    /// {@link Aarch64SystemRegisterId#TPIDR_EL0}).
    private long tpidrEl0;
    private long tpidrRoEl0;
    /// `FPCR`/`FPSR` (B8.15) — armazenamento puro, sem efeito semântico real ainda (ver javadoc de
    /// {@link Aarch64SystemRegisterId#FPCR}/{@link Aarch64SystemRegisterId#FPSR}).
    private long fpcr;
    private long fpsr;
    /// `DIT`/`SSBS`/`TCO`/`SPSel`/`PAN`/`UAO`/`ALLINT` (B8.17) — armazenamento puro, sem efeito
    /// real (ver javadoc de cada constante em {@link Aarch64SystemRegisterId}).
    private long dit;
    private long ssbs;
    private long tco;
    private long spsel;
    private long pan;
    private long uao;
    private long allint;
    /// Linha de IRQ nível-sensível controlada pelo hospedeiro (mesmo papel de
    /// {@code ArmCore#interruptLine}, 32-bit) — B6.6.7. `true` = interrupção pendente até o
    /// hospedeiro desassertar; sem GIC modelado, cabe ao hospedeiro decidir quando assertar/
    /// desassertar (ver a task, "Não inclui").
    private boolean interruptLine;
    /// Estado de espera do core (`WFI`, B6.6.7) — reaproveita {@link CpuSleepState} diretamente
    /// (genérico o bastante, mesma disciplina de `ExecutionThreshold` em B6.4 PR1); só
    /// {@code RUNNING}/{@code HALTED} têm consumidor aqui (sem "parada profunda" modelada).
    private CpuSleepState sleepState = CpuSleepState.RUNNING;
    /// Arquitetura A64 deste core (B11.2) — ainda sem efeito observável (nenhum decoder/executor
    /// consulta {@link #architecture} de dentro deste core; quem precisa dela hoje é o dono do
    /// {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder}, construído separadamente com a
    /// MESMA arquitetura, ver `tasks/trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`).
    /// Exposta para esse dono conseguir configurar o decoder certo sem duplicar a escolha de
    /// arquitetura em dois lugares.
    private final Aarch64Architecture architecture;

    /// Cria um core conectado a uma memória de 64 bits, para {@link Aarch64Architecture#ARMV8_0_A}
    /// — equivalente ao comportamento deste core antes de B11.2. Estado inicial: todos os
    /// registradores zerados, `PC = 0`, `PSTATE` zerado.
    public Aarch64Core(AddressSpace64 memory) {
        this(memory, Aarch64Architecture.ARMV8_0_A);
    }

    /// Cria um core conectado a uma memória de 64 bits, para a arquitetura informada (B11.2). Sem
    /// efeito observável ainda — ver o Javadoc de {@link #architecture}. Estado inicial: todos os
    /// registradores zerados, `PC = 0`, `PSTATE` zerado.
    public Aarch64Core(AddressSpace64 memory, Aarch64Architecture architecture) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.architecture = Objects.requireNonNull(architecture, "architecture");
    }

    /// Retorna a arquitetura configurada para este core (B11.2).
    public Aarch64Architecture architecture() {
        return architecture;
    }

    /// Lê um registrador geral pelo índice de encoding (`0`-`31`). O índice `31` é sempre `XZR`
    /// neste método — sempre lê `0`, independente da instrução (para a leitura de `SP`, use
    /// {@link #sp()} explicitamente; a escolha entre os dois é feita pelo EXECUTOR a partir do
    /// campo `spVariant` do `Ir64Op`, nunca aqui).
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @return valor de 64 bits do registrador, ou `0` se `index == 31` (`XZR`)
    public long x(int index) {
        checkRegisterIndex(index);
        return index == SPECIAL_REGISTER_ENCODING ? 0L : x[index];
    }

    /// Escreve um registrador geral pelo índice de encoding (`0`-`31`). Escrever em `31` é
    /// descartada silenciosamente (`XZR`) — ver {@link #x(int)}.
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param value novo valor de 64 bits
    public void setX(int index, long value) {
        checkRegisterIndex(index);
        if (index == SPECIAL_REGISTER_ENCODING) {
            return;
        }
        x[index] = value;
    }

    /// Lê um registrador geral na largura indicada: `X` (64 bits) ou `W` (32 bits, zero-estendido
    /// para o valor de retorno `long`).
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param wide `true` para ler a visão `X` completa; `false` para a visão `W` (32 bits baixos)
    /// @return valor lido, já na largura pedida
    public long xForWidth(int index, boolean wide) {
        long value = x(index);
        return wide ? value : (value & LOW_32_BITS_MASK);
    }

    /// Escreve um registrador geral na largura indicada. Na largura `W` (`wide == false`), os 32
    /// bits ALTOS do registrador de 64 bits são SEMPRE zerados — comportamento arquitetural do
    /// A64 (nenhuma instrução `W` preserva os bits altos do `X` correspondente; ver Armadilhas do
    /// épico B6 em `b6-aarch64.md`), não uma opção.
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param value valor a escrever (só os bits relevantes à largura são usados)
    /// @param wide `true` para escrever a visão `X` completa; `false` para a visão `W`
    ///             (zera os 32 bits altos)
    public void setXForWidth(int index, long value, boolean wide) {
        setX(index, wide ? value : (value & LOW_32_BITS_MASK));
    }

    /// Retorna o stack pointer ATIVO: `SP_EL0` quando o core está em `EL0` (comportamento de
    /// sempre, pré-B6.6.4), ou `SP_ELx` do nível atual (`EL1`-`EL3`, generalizado em B10.1 — era
    /// só `EL1` até então) — resolução automática que preserva TODO código existente do executor
    /// (`readBaseRegister`/ALU `SP`) sem precisar checar o nível explicitamente em cada uso.
    public long sp() {
        Aarch64ExceptionLevel level = exceptionState.currentEl();
        return level == Aarch64ExceptionLevel.EL0 ? spEl0 : exceptionState.sp(level);
    }

    /// Atualiza o stack pointer ATIVO (`SP_EL0` ou `SP_ELx` do nível atual, mesma resolução de
    /// {@link #sp()}).
    public void setSp(long value) {
        Aarch64ExceptionLevel level = exceptionState.currentEl();
        if (level == Aarch64ExceptionLevel.EL0) {
            spEl0 = value;
        } else {
            exceptionState.setSp(level, value);
        }
    }

    /// Retorna o program counter atual.
    public long pc() {
        return pc;
    }

    /// Atualiza o program counter.
    public void setProgramCounter(long value) {
        pc = value;
    }

    /// Retorna o `PSTATE` mutável associado ao core (só `N`/`Z`/`C`/`V` nesta task).
    public PstateRegister pstate() {
        return pstate;
    }

    /// Retorna o banco de registradores FP escalar (`V0`-`V31`, B6.5.1).
    public Aarch64FpRegisters fp() {
        return fp;
    }

    /// Retorna o barramento de memória conectado ao core.
    public AddressSpace64 memory() {
        return memory;
    }

    /// Retorna o handler de `SVC` instalado (padrão: {@link Aarch64SvcHandler#none()}).
    public Aarch64SvcHandler svcHandler() {
        return svcHandler;
    }

    /// Instala o handler de `SVC` usado pelo host (ex. tradução de syscalls no armbox, B6.2).
    public void setSvcHandler(Aarch64SvcHandler svcHandler) {
        this.svcHandler = Objects.requireNonNull(svcHandler, "svcHandler");
    }

    /// Soma ciclos consumidos pelo interpretador.
    public void addCycles(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cycles must be positive");
        }
        cycles += amount;
    }

    /// Retorna o total de ciclos acumulados.
    public long cycles() {
        return cycles;
    }

    /// Instala um monitor de exclusividade COMPARTILHADO (mesmo papel de
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore#setExclusiveMonitor} no mundo de 32 bits).
    /// Default é um monitor próprio deste core.
    public void setExclusiveMonitor(Aarch64ExclusiveMonitor exclusiveMonitor) {
        this.exclusiveMonitor = Objects.requireNonNull(exclusiveMonitor, "exclusiveMonitor");
    }

    /// Marca o monitor de exclusividade (próprio ou compartilhado) para o endereço/tamanho de um
    /// `LDXR`/`LDAXR`, com este core como dono da reserva.
    ///
    /// @param address endereço do acesso, sem sinal
    /// @param sizeBytes tamanho do acesso (1, 2, 4 ou 8)
    public void markExclusiveMonitor(long address, int sizeBytes) {
        exclusiveMonitor.markExclusive(this, address, sizeBytes);
    }

    /// Consulta um `STXR`/`STLXR` deste core contra o monitor de exclusividade. Exige marcação
    /// exata (mesmo endereço e mesmo tamanho); a reserva é SEMPRE consumida quando a região bate
    /// (mesmo se o dono for outro core, quando o monitor é compartilhado), mas só devolve `true`
    /// quando este core é o dono.
    public boolean exclusiveMonitorCovers(long address, int sizeBytes) {
        return exclusiveMonitor.consumeIfCovered(this, address, sizeBytes);
    }

    /// Abre (limpa) o monitor de exclusividade deste core. Chamado por {@link #enterMemoryAbort}
    /// (B6.6.4, fecha a pendência registrada em B6.3.4) — um `STXR`/`STLXR` após uma entrada de
    /// exceção deve falhar e refazer o par `LDXR`/`STXR`, mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} (32-bit).
    public void clearExclusiveMonitor() {
        exclusiveMonitor.clear(this);
    }

    /// Notifica o monitor de exclusividade de uma escrita comum (`STR`/`STP`, não-exclusiva) deste
    /// core. Se sobrepuser uma reserva pendente, abre a reserva, como no hardware real.
    public void notifyOrdinaryWrite(long address, int sizeBytes) {
        if (exclusiveMonitor.isArmed()) {
            exclusiveMonitor.notifyOrdinaryWrite(address, sizeBytes);
        }
    }

    /// Endereço marcado no monitor de exclusividade, ou `-1` quando aberto. Exposto para o harness
    /// de equivalência (`CpuSnapshot`) futuro detectar divergência de backend.
    public long exclusiveMonitorAddress() {
        return exclusiveMonitor.address(this);
    }

    /// Tamanho em bytes marcado no monitor de exclusividade (0 quando aberto).
    public int exclusiveMonitorSizeBytes() {
        return exclusiveMonitor.sizeBytes(this);
    }

    /// Retorna o barramento de registrador de sistema instalado (padrão:
    /// {@link Aarch64SystemRegisterBus#none()}).
    public Aarch64SystemRegisterBus systemRegisterBus() {
        return systemRegisterBus;
    }

    /// Instala o barramento de registrador de sistema usado pelo host (ex. MMU v8, B6.6.3).
    public void setSystemRegisterBus(Aarch64SystemRegisterBus systemRegisterBus) {
        this.systemRegisterBus = Objects.requireNonNull(systemRegisterBus, "systemRegisterBus");
    }

    /// `true` quando {@code register} é uma identidade da CPU resolvida DIRETO por este core
    /// (B6.6.7) — ver javadoc de {@link Aarch64SystemRegisterId}. `false` para qualquer outro
    /// registrador (inclui o timer genérico, que continua indo para
    /// {@link #systemRegisterBus()}), delegado ao executor decidir a rota.
    public boolean handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CURRENT_EL, MPIDR_EL1, MIDR_EL1, ID_AA64PFR0_EL1, ID_AA64PFR1_EL1,
                 ID_AA64ISAR0_EL1, ID_AA64ISAR1_EL1, ID_AA64ISAR2_EL1, ID_AA64MMFR0_EL1,
                 ID_AA64MMFR1_EL1, ID_AA64MMFR2_EL1, ID_AA64MMFR3_EL1, ID_AA64MMFR4_EL1,
                 ID_AA64ZFR0_EL1, ID_AA64DFR0_EL1, ID_AA64DFR1_EL1, REVIDR_EL1, TPIDR_EL1,
                 TPIDR_EL0, TPIDRRO_EL0, FPCR, FPSR, NZCV, DAIF, DIT, SSBS, TCO, SPSEL, PAN, UAO,
                 ALLINT, CTR_EL0, DCZID_EL0 -> true;
            default -> false;
        };
    }

    /// `MRS` de uma identidade da CPU (B6.6.7, {@link #handlesSystemRegisterIntrinsically} deve
    /// ser checado antes pelo chamador). `CurrentEL` é o único campo dinâmico (reflete
    /// {@link Aarch64ExceptionState#currentEl()}, generalizado para os 4 níveis em B10.1); os
    /// demais são constantes fixas deste core.
    public long readIntrinsicSystemRegister(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CURRENT_EL -> switch (exceptionState.currentEl()) {
                case EL0 -> CURRENT_EL_VALUE_EL0;
                case EL1 -> CURRENT_EL_VALUE_EL1;
                case EL2 -> CURRENT_EL_VALUE_EL2;
                case EL3 -> CURRENT_EL_VALUE_EL3;
            };
            case MPIDR_EL1 -> MPIDR_EL1_VALUE;
            case MIDR_EL1 -> MIDR_EL1_VALUE;
            case ID_AA64PFR0_EL1 -> ID_AA64PFR0_EL1_VALUE;
            case ID_AA64ISAR0_EL1 -> ID_AA64ISAR0_EL1_VALUE;
            case ID_AA64MMFR0_EL1 -> ID_AA64MMFR0_EL1_VALUE;
            case ID_AA64MMFR1_EL1 -> ID_AA64MMFR1_EL1_VALUE;
            case ID_AA64MMFR2_EL1 -> ID_AA64MMFR2_EL1_VALUE;
            case ID_AA64MMFR3_EL1 -> ID_AA64MMFR3_EL1_VALUE;
            case ID_AA64MMFR4_EL1 -> ID_AA64MMFR4_EL1_VALUE;
            case ID_AA64PFR1_EL1 -> ID_AA64PFR1_EL1_VALUE;
            case ID_AA64ZFR0_EL1 -> ID_AA64ZFR0_EL1_VALUE;
            case ID_AA64DFR1_EL1 -> ID_AA64DFR1_EL1_VALUE;
            case ID_AA64ISAR1_EL1 -> ID_AA64ISAR1_EL1_VALUE;
            case ID_AA64ISAR2_EL1 -> ID_AA64ISAR2_EL1_VALUE;
            case REVIDR_EL1 -> REVIDR_EL1_VALUE;
            case ID_AA64DFR0_EL1 -> ID_AA64DFR0_EL1_VALUE;
            case TPIDR_EL1 -> tpidrEl1;
            case TPIDR_EL0 -> tpidrEl0;
            case TPIDRRO_EL0 -> tpidrRoEl0;
            case FPCR -> fpcr;
            case FPSR -> fpsr;
            case NZCV -> pstate.toNzcvRegisterFormat();
            case DAIF -> pstate.toDaifRegisterFormat();
            case DIT -> dit;
            case SSBS -> ssbs;
            case TCO -> tco;
            case SPSEL -> spsel;
            case PAN -> pan;
            case UAO -> uao;
            case ALLINT -> allint;
            case CTR_EL0 -> CTR_EL0_VALUE;
            case DCZID_EL0 -> DCZID_EL0_VALUE;
            default -> throw new IllegalArgumentException(
                    "Não é uma identidade intrínseca: " + register);
        };
    }

    /// `MSR` de uma identidade da CPU (B6.6.7). Só {@link Aarch64SystemRegisterId#TPIDR_EL1} é
    /// realmente gravável pelo guest (escaninho de thread); os demais são `RO`/`WI` de hardware —
    /// escrita lança (nenhuma instrução real gerada por um compilador visa `MSR` para eles, `WI`
    /// silencioso esconderia um bug de decodificação/uso incorreto em vez de sinalizar).
    public void writeIntrinsicSystemRegister(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case TPIDR_EL1 -> tpidrEl1 = value;
            case TPIDR_EL0 -> tpidrEl0 = value;
            // TPIDRRO_EL0 é RO de EL0/RW de EL1 no hardware real — este emulador não modela essa
            // distinção de privilégio (mesma simplificação de B10.7), aceita escrita dos 2 lados.
            case TPIDRRO_EL0 -> tpidrRoEl0 = value;
            case FPCR -> fpcr = value;
            case FPSR -> fpsr = value;
            case NZCV -> pstate.setFromNzcvRegisterFormat(value);
            case DAIF -> pstate.setFromDaifRegisterFormat(value);
            case DIT -> dit = value;
            case SSBS -> ssbs = value;
            case TCO -> tco = value;
            case SPSEL -> spsel = value;
            case PAN -> pan = value;
            case UAO -> uao = value;
            case ALLINT -> allint = value;
            default -> throw new UnsupportedOperationException(
                    "AArch64: registrador de identidade é somente leitura: " + register);
        }
    }

    /// Linha de IRQ nível-sensível (B6.6.7) — ver javadoc do campo {@link #interruptLine}.
    public boolean interruptLine() {
        return interruptLine;
    }

    /// Assert/desassert da linha de IRQ pelo hospedeiro.
    public void setInterruptLine(boolean interruptLine) {
        this.interruptLine = interruptLine;
    }

    /// Estado de espera do core (B6.6.7, `WFI`).
    public CpuSleepState sleepState() {
        return sleepState;
    }

    /// Força o estado de espera do core — usado pelo executor (`WFI`) e por
    /// {@link #enterIrq}/{@link #enterMemoryAbort} (uma exceção sempre acorda o core).
    public void setSleepState(CpuSleepState sleepState) {
        this.sleepState = Objects.requireNonNull(sleepState, "sleepState");
    }

    /// Checa e, se pendente e não mascarada, entrega uma IRQ — chamado pelo executor ANTES de
    /// buscar/decodificar a próxima instrução (mesmo ponto de verificação de
    /// {@code ArmCore#servicePendingIrq}, 32-bit). Também acorda o core de `WFI` mesmo quando a
    /// IRQ está mascarada (`ARM DDI 0487` pseudocódigo de `WFI`: uma interrupção pendente acorda o
    /// core mesmo mascarada — ela só não é ENTREGUE enquanto mascarada, mas a execução retoma).
    ///
    /// @return `true` quando uma IRQ foi entregue nesta chamada (o chamador não deve prosseguir
    ///         para fetch/decode desta rodada — o PC já foi redirecionado para o handler)
    public boolean servicePendingIrq() {
        if (!interruptLine) {
            return false;
        }
        if (sleepState != CpuSleepState.RUNNING) {
            sleepState = CpuSleepState.RUNNING;
        }
        if (pstate.irqDisabled()) {
            return false;
        }
        enterIrq();
        return true;
    }

    /// Entrada de exceção por IRQ (B6.6.7; alvo generalizado em B10.1) — espelho de
    /// {@link #enterMemoryAbort}, mas SEM tocar `ESR_ELx`/`FAR_ELx` (uma IRQ não tem síndrome de
    /// falta associada; o hardware real deixa esses registradores com o valor anterior). `ELR_ELx`
    /// recebe o PC ATUAL (endereço da PRÓXIMA instrução que executaria — IRQ é assíncrona, ao
    /// contrário de um abort síncrono, que salva o endereço da instrução FALTOSA). `PSTATE.I` é
    /// forçado a `1` na entrada (mascara IRQ aninhada dentro do handler — `ERET` restaura o valor
    /// salvo em `SPSR_ELx`).
    ///
    /// Alvo fixo em `EL1` até B10.4/B10.5 chegarem (nenhum roteamento por `HCR_EL2.IMO`/
    /// `SCR_EL3.IRQ` ainda — comportamento IDÊNTICO ao de antes de B10.1, só implementado sobre o
    /// armazenamento genérico por nível agora).
    public void enterIrq() {
        Aarch64ExceptionLevel target = Aarch64ExceptionLevel.EL1;
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        exceptionState.setElr(target, pc);
        exceptionState.setSpsr(target, pstate.toSpsrFormat() | source.spsrMode());
        exceptionState.setCurrentEl(target);
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar(target) + vectorGroupBase(source, target)
                + VECTOR_IRQ_OFFSET_WITHIN_GROUP);
    }

    /// Retorna o estado de exceção EL0→EL1 (B6.6.4) — `ELR_EL1`/`SPSR_EL1`/`ESR_EL1`/`FAR_EL1`/
    /// `VBAR_EL1`/`SP_EL1`/nível atual. Exposto para o host configurar `VBAR_EL1` diretamente
    /// (setup de teste sem MMU) e para
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters}-equivalentes
    /// delegarem `MRS`/`MSR` desses registradores aqui em vez de duplicar armazenamento.
    public Aarch64ExceptionState exceptionState() {
        return exceptionState;
    }

    /// Converte uma {@link MemoryTranslationException64} capturada pelo executor
    /// (`Ir64BlockExecutor`, B6.6.4) numa entrada de exceção síncrona — mesmo contrato do
    /// precedente {@link dev.vitorsilverio.armjitter.core.ArmCore#enterMemoryAbort}:
    /// {@code instructionAddress} é o endereço da PRÓPRIA instrução faltosa (fetch ou load/store),
    /// não o sequencial seguinte.
    ///
    /// Preenche `ESR_ELx` (`EC`+`IL`+`ISS[5:0]`, `ARM DDI 0487 D17.2.30`) e `FAR_ELx`, salva
    /// `ELR_ELx←instructionAddress` e `SPSR_ELx←PSTATE` atual, abre o monitor de exclusividade
    /// (mesma disciplina de {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} — um
    /// `STXR`/`STLXR` após o retorno deve falhar e refazer o par `LDXR`/`STXR`, fecha a pendência
    /// de B6.3.4) e salta pro vetor certo (`ARM DDI 0487 D1.10`).
    ///
    /// Alvo fixo em `EL1` até B10.4/B10.5 chegarem (nenhum roteamento por `HCR_EL2`/`SCR_EL3`
    /// ainda — comportamento IDÊNTICO ao de antes de B10.1).
    ///
    /// @param instructionAddress endereço da instrução que causou a falta
    /// @param fault falta de tradução capturada
    public void enterMemoryAbort(long instructionAddress, MemoryTranslationException64 fault) {
        Aarch64ExceptionLevel target = Aarch64ExceptionLevel.EL1;
        boolean isInstructionFetch = fault.accessType() == MemoryAccessType.INSTRUCTION_FETCH;
        long ec = isInstructionFetch ? ESR_EC_INSTRUCTION_ABORT_LOWER_EL : ESR_EC_DATA_ABORT_LOWER_EL;
        long faultStatusCode = fault.faultStatus().code() & ESR_ISS_FAULT_STATUS_MASK;
        long esr = (ec << ESR_EC_SHIFT) | ESR_IL_BIT | faultStatusCode;
        exceptionState.setFar(target, fault.virtualAddress());
        enterSynchronousException(target, instructionAddress, esr);
    }

    /// `BRK` (`ARM DDI 0487 C6.2.29`, B8.3) — mesma entrada de exceção síncrona de
    /// {@link #enterMemoryAbort}, mas SEM tocar `FAR_ELx` (`BRK` não tem endereço de falta —
    /// `ARM DDI 0487` deixa o registrador com o valor anterior, mesma disciplina já aplicada a
    /// {@link #enterIrq}). `ESR_ELx.ISS[15:0]` recebe o imediato de 16 bits da própria instrução
    /// (convenção do Linux/GDB para identificar o motivo do trap sem reler a instrução). Alvo fixo
    /// em `EL1`, mesma ressalva de {@link #enterMemoryAbort}.
    ///
    /// @param instructionAddress endereço da própria instrução `BRK` (`ELR_ELx`)
    /// @param immediate imediato de 16 bits do encoding
    public void enterBreakpointException(long instructionAddress, int immediate) {
        long esr = (ESR_EC_BREAKPOINT << ESR_EC_SHIFT) | ESR_IL_BIT
                | (immediate & ESR_ISS_BRK_IMMEDIATE_MASK);
        enterSynchronousException(Aarch64ExceptionLevel.EL1, instructionAddress, esr);
    }

    /// `HLT` sem estado de debug externo modelado (B8.3) — pseudocódigo real do manual cai no
    /// caminho `UNDEFINED` (`Halting_instruction`), mesma classe (`EC=0x00`, "Unknown reason") de
    /// qualquer encoding reservado que um `Aarch64Decoder` real rejeitasse. Mesmo contrato de
    /// {@link #enterBreakpointException}, sem imediato (`ISS=0`, "Unknown reason" não carrega
    /// síndrome). Alvo fixo em `EL1`, mesma ressalva de {@link #enterMemoryAbort}.
    ///
    /// @param instructionAddress endereço da própria instrução `HLT` (`ELR_ELx`)
    public void enterUndefinedInstructionException(long instructionAddress) {
        long esr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL1, instructionAddress, esr);
    }

    /// `HVC` real (`ARM DDI 0487 C6.2.83`, B10.4) — árvore de decisão do pseudocódigo real da
    /// instrução (não "sempre EL2"): `EL0`/`EL3` não têm `HVC` definida (cai em `UNDEFINED`, mesmo
    /// caminho de {@link #enterUndefinedInstructionException} — `EL3` chama `SMC` para o monitor,
    /// não `HVC`, ver B10.5); em `EL1`, `HCR_EL2.HCD` desabilita a rota (mesmo destino `UNDEFINED`);
    /// em `EL2`, `HVC` sempre entra (auto-chamada, "mesmo nível" — caso real do manual, usado por
    /// virtualização aninhada, `HCD` não se aplica aqui). Sem hospedeiro de `HCR_EL2` instalado
    /// (`systemRegisterBus` "none"), `HCD` é tratado como `0` (não desabilitado — mesmo padrão
    /// "registrador sem hospedeiro = zerado" já usado alhures).
    ///
    /// @param instructionAddress endereço da própria instrução `HVC` (`ELR_ELx` quando aplicável)
    public void enterHypervisorCall(long instructionAddress) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        if (source == Aarch64ExceptionLevel.EL0) {
            // enterUndefinedInstructionException() tem alvo fixo em EL1, correto aqui: EL0 nunca
            // trata sua própria exceção (não bancado), EL1 é o único alvo real de um UNDEFINED
            // vindo de EL0.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        if (source == Aarch64ExceptionLevel.EL3) {
            // NÃO reusa enterUndefinedInstructionException() aqui: seu alvo fixo em EL1 seria uma
            // redução de privilégio (EL3->EL1), que enterSynchronousException recusa (G8, ver
            // vectorGroupBase). UNDEFINED em EL3 se auto-trata (mesmo nível), mesmo raciocínio do
            // ramo EL1 abaixo.
            long esr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
            enterSynchronousException(Aarch64ExceptionLevel.EL3, instructionAddress, esr);
            return;
        }
        if (source == Aarch64ExceptionLevel.EL1 && hypervisorCallDisabled()) {
            // Alvo fixo em EL1 de enterUndefinedInstructionException() colide com `source` aqui
            // (EL1->EL1, "mesmo nível") — correto, mesmo raciocínio do ramo EL3 acima.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        long esr = (ESR_EC_HVC_AARCH64 << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL2, instructionAddress, esr);
    }

    /// Leitura de `HCR_EL2.HCD` para {@link #enterHypervisorCall} — via {@link #systemRegisterBus}
    /// em vez de armazenamento próprio deste core (mesma disciplina de todo registrador de sistema
    /// EL2/EL3 desde B10.2/B10.3: a fonte única é o barramento instalado pelo hospedeiro, `Aarch64Core`
    /// não duplica estado).
    private boolean hypervisorCallDisabled() {
        return systemRegisterBus.handles(Aarch64SystemRegisterId.HCR_EL2)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.HCR_EL2) & HCR_EL2_HCD_BIT) != 0;
    }

    /// `SMC` real (`ARM DDI 0487 C6.2.294`, B10.5) — árvore de decisão do pseudocódigo real da
    /// instrução, SIMPLIFICADA (sem o ramo `HCR_EL2.TSC` que roteia `EL1`→`EL2`, deliberadamente
    /// fora de escopo, ver a task "Não inclui" — mesma simplificação que B10.4 aplicou a `HVC` para
    /// `HCR_EL2.TGE`): `EL0` não tem `SMC` definida (cai em `UNDEFINED`, mesmo caminho de
    /// {@link #enterUndefinedInstructionException}); em `EL1`/`EL2`/`EL3`, `SCR_EL3.SMD` desabilita
    /// a rota (self-trap `UNDEFINED` no PRÓPRIO nível de origem — nunca reduz nem aumenta
    /// privilégio); senão entra em `EL3` (auto-chamada quando a origem já é `EL3`, "mesmo nível" —
    /// mesmo caso real do manual usado pelo ramo `EL2→EL2` de {@link #enterHypervisorCall}). Sem
    /// hospedeiro de `SCR_EL3` instalado (`systemRegisterBus` "none"), `SMD` é tratado como `0`
    /// (não desabilitado, mesmo padrão "registrador sem hospedeiro = zerado" de {@link #hypervisorCallDisabled}).
    ///
    /// @param instructionAddress endereço da própria instrução `SMC` (`ELR_ELx` quando aplicável)
    public void enterSecureMonitorCall(long instructionAddress) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        if (source == Aarch64ExceptionLevel.EL0) {
            // Mesmo raciocínio do ramo EL0 de enterHypervisorCall: EL1 é o único alvo real de um
            // UNDEFINED vindo de EL0 (não bancado), alvo fixo do método de conveniência é correto
            // aqui.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        if (secureMonitorCallDisabled()) {
            if (source == Aarch64ExceptionLevel.EL1) {
                // Alvo fixo EL1 de enterUndefinedInstructionException() coincide com `source`
                // aqui (EL1->EL1, "mesmo nível") — correto, mesmo raciocínio do ramo EL1 de
                // enterHypervisorCall.
                enterUndefinedInstructionException(instructionAddress);
                return;
            }
            // EL2/EL3 com SMD setado: NÃO reusa enterUndefinedInstructionException() (alvo fixo
            // EL1 seria uma redução de privilégio, que enterSynchronousException recusa via
            // vectorGroupBase, G8 — mesmo achado registrado nas Armadilhas de B10.4 para o ramo
            // EL3 de HVC). UNDEFINED se auto-trata no próprio nível de origem.
            long undefinedEsr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
            enterSynchronousException(source, instructionAddress, undefinedEsr);
            return;
        }
        long esr = (ESR_EC_SMC_AARCH64 << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL3, instructionAddress, esr);
    }

    /// Leitura de `SCR_EL3.SMD` para {@link #enterSecureMonitorCall} — via
    /// {@link #systemRegisterBus}, mesma disciplina de {@link #hypervisorCallDisabled}.
    private boolean secureMonitorCallDisabled() {
        return systemRegisterBus.handles(Aarch64SystemRegisterId.SCR_EL3)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.SCR_EL3) & SCR_EL3_SMD_BIT) != 0;
    }

    /// Núcleo comum de toda exceção síncrona (`ARM DDI 0487` pseudocódigo `AArch64.TakeException`,
    /// extraído de {@link #enterMemoryAbort} em B8.3, generalizado para nível-alvo explícito em
    /// B10.1) — reaproveitado por {@link #enterBreakpointException}/
    /// {@link #enterUndefinedInstructionException} e por `HVC`/`SMC` reais ({@link #enterHypervisorCall}/
    /// {@link #enterSecureMonitorCall}, B10.4/B10.5): todas compartilham `ELR_ELx←instructionAddress`, `SPSR_ELx←PSTATE` atual (com o
    /// nível de ORIGEM codificado no campo `M[3:0]`, para `ERET` saber pra onde voltar — ver
    /// {@link Aarch64ExceptionLevel}), fecham o monitor de exclusividade, entram no nível-alvo e
    /// saltam pro vetor certo (grupo "mesmo nível" ou "nível inferior", conforme
    /// {@link #vectorGroupBase} — só `ESR_ELx`/`FAR_ELx` mudam por tipo de falta, e `FAR_ELx` é
    /// preenchido pelo CHAMADOR antes de vir aqui quando aplicável).
    ///
    /// @param target nível de exceção que vai tratar esta exceção (nunca `EL0`, nunca abaixo do
    ///               nível atual — ver {@link #vectorGroupBase})
    private void enterSynchronousException(Aarch64ExceptionLevel target, long instructionAddress, long esr) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        exceptionState.setEsr(target, esr);
        exceptionState.setElr(target, instructionAddress);
        exceptionState.setSpsr(target, pstate.toSpsrFormat() | source.spsrMode());
        clearExclusiveMonitor();
        exceptionState.setCurrentEl(target);
        // B6.6.7: qualquer entrada de exceção mascara IRQ (`ARM DDI 0487` pseudocódigo
        // `AArch64.TakeException` seta `PSTATE.{D,A,I,F}=1`) — `spsr` acima já capturou o valor
        // ANTIGO da máscara (o que `ERET` deve restaurar), então esta linha só afeta o `PSTATE`
        // ATIVO durante o handler em si.
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar(target) + vectorGroupBase(source, target)
                + VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP);
    }

    /// Base do grupo de origem certo na tabela de vetores (`ARM DDI 0487 D1.10`, B10.1): "mesmo
    /// nível, `SP_ELx`" quando a exceção ocorre já dentro do nível-alvo (ex.: `BRK` dentro do
    /// próprio handler de EL1), "nível inferior usando AArch64" quando entra vindo de um nível
    /// mais baixo (o caso de toda entrada EL0→EL1 até B10.1). Uma exceção NUNCA reduz o nível de
    /// privilégio — `target` abaixo de `source` é um bug de chamador, não um caso arquitetural
    /// real, por isso lança em vez de silenciosamente escolher um grupo errado (G8).
    private static long vectorGroupBase(Aarch64ExceptionLevel source, Aarch64ExceptionLevel target) {
        if (target.ordinal() < source.ordinal()) {
            throw new IllegalStateException(
                    "entrada de exceção não pode reduzir o nível de privilégio: " + source + " -> " + target);
        }
        return target == source ? VECTOR_GROUP_CURRENT_EL_SPX_BASE : VECTOR_GROUP_LOWER_EL_AARCH64_BASE;
    }

    private static void checkRegisterIndex(int index) {
        if (index < 0 || index > SPECIAL_REGISTER_ENCODING) {
            throw new IndexOutOfBoundsException(
                    "AArch64 register encoding index must be between 0 and 31: " + index);
        }
    }
}
