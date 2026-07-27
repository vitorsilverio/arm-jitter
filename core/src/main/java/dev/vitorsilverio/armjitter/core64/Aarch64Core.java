package dev.vitorsilverio.armjitter.core64;

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
        setProgramCounter(exceptionState.vbar1() + SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET);
    }

    private static void checkRegisterIndex(int index) {
        if (index < 0 || index > SPECIAL_REGISTER_ENCODING) {
            throw new IndexOutOfBoundsException(
                    "AArch64 register encoding index must be between 0 and 31: " + index);
        }
    }
}
