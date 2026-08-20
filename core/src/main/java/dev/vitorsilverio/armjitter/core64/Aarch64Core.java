package dev.vitorsilverio.armjitter.core64;

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

    /// Deslocamento em bytes, dentro da tabela de vetores apontada por `VBAR_EL1`, da entrada
    /// "Synchronous, exceção de um nível INFERIOR usando AArch64" (`ARM DDI 0487 D1.10`, tabela de
    /// 16 entradas de {@link #VECTOR_TABLE_ENTRY_SIZE_BYTES} cada — 4 grupos de origem × 4 tipos).
    /// É a ÚNICA entrada usada nesta task: o abort de memória (só origem modelada) é sempre
    /// síncrono e sempre entra em EL1 vindo de EL0 (uma exceção de nível inferior, em AArch64 —
    /// este core não tem estado AArch32) — as outras 15 entradas (IRQ/FIQ/SError, "current EL"
    /// com SP0/SPx, "lower EL AArch32") não têm consumidor nesta fatia (`SVC`/`IRQ`/`FIQ`/`SError`
    /// ficam fora, ver task B6.6.4 "Não inclui").
    private static final long SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET = 0x400L;
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
    /// Deslocamento em bytes, dentro da tabela de vetores apontada por `VBAR_EL1`, da entrada
    /// "IRQ, exceção de um nível INFERIOR usando AArch64" (`ARM DDI 0487 D1.10`, B6.6.7) — mesma
    /// tabela de 16 entradas de {@link #VECTOR_TABLE_ENTRY_SIZE_BYTES} de
    /// {@link #SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET}, próxima entrada do mesmo grupo de
    /// origem (sync=`0x400`, IRQ=`0x480`, FIQ=`0x500`, SError=`0x580` — só IRQ tem consumidor
    /// nesta task).
    private static final long IRQ_LOWER_EL_AARCH64_VECTOR_OFFSET = 0x480L;

    // ── B6.6.7: registradores de identidade da CPU, constantes fixas (sem hospedeiro plugável —
    // ── ver javadoc de Aarch64SystemRegisterId). Valores documentados registrador a registrador.
    /// `CurrentEL` quando em EL0 (`[3:2]=0b00`, ver {@link Aarch64SystemRegisterId#CURRENT_EL}).
    private static final long CURRENT_EL_VALUE_EL0 = 0L;
    /// `CurrentEL` quando em EL1 (`[3:2]=0b01`).
    private static final long CURRENT_EL_VALUE_EL1 = 0b01L << 2;
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
    /// Linha de IRQ nível-sensível controlada pelo hospedeiro (mesmo papel de
    /// {@code ArmCore#interruptLine}, 32-bit) — B6.6.7. `true` = interrupção pendente até o
    /// hospedeiro desassertar; sem GIC modelado, cabe ao hospedeiro decidir quando assertar/
    /// desassertar (ver a task, "Não inclui").
    private boolean interruptLine;
    /// Estado de espera do core (`WFI`, B6.6.7) — reaproveita {@link CpuSleepState} diretamente
    /// (genérico o bastante, mesma disciplina de `ExecutionThreshold` em B6.4 PR1); só
    /// {@code RUNNING}/{@code HALTED} têm consumidor aqui (sem "parada profunda" modelada).
    private CpuSleepState sleepState = CpuSleepState.RUNNING;

    /// Cria um core conectado a uma memória de 64 bits. Estado inicial: todos os registradores
    /// zerados, `PC = 0`, `PSTATE` zerado.
    public Aarch64Core(AddressSpace64 memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
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

    /// Retorna o stack pointer ATIVO: `SP_EL0` quando o core está em EL0 (comportamento de
    /// sempre, pré-B6.6.4), ou `SP_EL1` quando {@link #exceptionState()} indica
    /// {@link Aarch64ExceptionState#inEl1()} (dentro de um handler de abort, B6.6.4) — resolução
    /// automática que preserva TODO código existente do executor (`readBaseRegister`/ALU `SP`)
    /// sem precisar checar o nível explicitamente em cada uso.
    public long sp() {
        return exceptionState.inEl1() ? exceptionState.sp1() : spEl0;
    }

    /// Atualiza o stack pointer ATIVO (`SP_EL0` ou `SP_EL1`, mesma resolução de {@link #sp()}).
    public void setSp(long value) {
        if (exceptionState.inEl1()) {
            exceptionState.setSp1(value);
        } else {
            spEl0 = value;
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
            case CURRENT_EL, MPIDR_EL1, MIDR_EL1, ID_AA64PFR0_EL1, ID_AA64ISAR0_EL1,
                 ID_AA64MMFR0_EL1, ID_AA64DFR0_EL1, TPIDR_EL1, CTR_EL0, DCZID_EL0 -> true;
            default -> false;
        };
    }

    /// `MRS` de uma identidade da CPU (B6.6.7, {@link #handlesSystemRegisterIntrinsically} deve
    /// ser checado antes pelo chamador). `CurrentEL` é o único campo dinâmico (reflete
    /// {@link Aarch64ExceptionState#inEl1()}); os demais são constantes fixas deste core.
    public long readIntrinsicSystemRegister(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CURRENT_EL -> exceptionState.inEl1() ? CURRENT_EL_VALUE_EL1 : CURRENT_EL_VALUE_EL0;
            case MPIDR_EL1 -> MPIDR_EL1_VALUE;
            case MIDR_EL1 -> MIDR_EL1_VALUE;
            case ID_AA64PFR0_EL1 -> ID_AA64PFR0_EL1_VALUE;
            case ID_AA64ISAR0_EL1 -> ID_AA64ISAR0_EL1_VALUE;
            case ID_AA64MMFR0_EL1 -> ID_AA64MMFR0_EL1_VALUE;
            case ID_AA64DFR0_EL1 -> ID_AA64DFR0_EL1_VALUE;
            case TPIDR_EL1 -> tpidrEl1;
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
        if (register == Aarch64SystemRegisterId.TPIDR_EL1) {
            tpidrEl1 = value;
            return;
        }
        throw new UnsupportedOperationException(
                "AArch64: registrador de identidade é somente leitura: " + register);
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

    /// Entrada de exceção EL0→EL1 por IRQ (B6.6.7) — espelho de {@link #enterMemoryAbort}, mas SEM
    /// tocar `ESR_EL1`/`FAR_EL1` (uma IRQ não tem síndrome de falta associada; o hardware real
    /// deixa esses registradores com o valor anterior). `ELR_EL1` recebe o PC ATUAL (endereço da
    /// PRÓXIMA instrução que executaria — IRQ é assíncrona, ao contrário de um abort síncrono, que
    /// salva o endereço da instrução FALTOSA). `PSTATE.I` é forçado a `1` na entrada (mascara IRQ
    /// aninhada dentro do handler — `ERET` restaura o valor salvo em `SPSR_EL1`).
    public void enterIrq() {
        exceptionState.setElr1(pc);
        exceptionState.setSpsr1(pstate.toSpsrFormat());
        exceptionState.setInEl1(true);
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar1() + IRQ_LOWER_EL_AARCH64_VECTOR_OFFSET);
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
    /// (`Ir64BlockExecutor`, B6.6.4) numa entrada de exceção síncrona EL0→EL1 — mesmo contrato do
    /// precedente {@link dev.vitorsilverio.armjitter.core.ArmCore#enterMemoryAbort}:
    /// {@code instructionAddress} é o endereço da PRÓPRIA instrução faltosa (fetch ou load/store),
    /// não o sequencial seguinte.
    ///
    /// Preenche `ESR_EL1` (`EC`+`IL`+`ISS[5:0]`, `ARM DDI 0487 D17.2.30`) e `FAR_EL1`, salva
    /// `ELR_EL1←instructionAddress` e `SPSR_EL1←PSTATE` atual, abre o monitor de exclusividade
    /// (mesma disciplina de {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} — um
    /// `STXR`/`STLXR` após o retorno deve falhar e refazer o par `LDXR`/`STXR`, fecha a pendência
    /// de B6.3.4), entra em EL1 e salta para `VBAR_EL1 +`
    /// {@link #SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET} (única entrada da tabela de vetores
    /// usada nesta task — ver a constante).
    ///
    /// @param instructionAddress endereço da instrução que causou a falta
    /// @param fault falta de tradução capturada
    public void enterMemoryAbort(long instructionAddress, MemoryTranslationException64 fault) {
        boolean isInstructionFetch = fault.accessType() == MemoryAccessType.INSTRUCTION_FETCH;
        long ec = isInstructionFetch ? ESR_EC_INSTRUCTION_ABORT_LOWER_EL : ESR_EC_DATA_ABORT_LOWER_EL;
        long faultStatusCode = fault.faultStatus().code() & ESR_ISS_FAULT_STATUS_MASK;
        long esr = (ec << ESR_EC_SHIFT) | ESR_IL_BIT | faultStatusCode;

        exceptionState.setEsr1(esr);
        exceptionState.setFar1(fault.virtualAddress());
        exceptionState.setElr1(instructionAddress);
        exceptionState.setSpsr1(pstate.toSpsrFormat());
        clearExclusiveMonitor();
        exceptionState.setInEl1(true);
        // B6.6.7: qualquer entrada de exceção mascara IRQ (`ARM DDI 0487` pseudocódigo
        // `AArch64.TakeException` seta `PSTATE.{D,A,I,F}=1`) — `spsr1` acima já capturou o valor
        // ANTIGO da máscara (o que `ERET` deve restaurar), então esta linha só afeta o `PSTATE`
        // ATIVO durante o handler em si.
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar1() + SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET);
    }

    private static void checkRegisterIndex(int index) {
        if (index < 0 || index > SPECIAL_REGISTER_ENCODING) {
            throw new IndexOutOfBoundsException(
                    "AArch64 register encoding index must be between 0 and 31: " + index);
        }
    }
}
