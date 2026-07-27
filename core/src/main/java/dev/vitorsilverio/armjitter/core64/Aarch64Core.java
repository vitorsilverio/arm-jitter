package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.Objects;

/// Estado de uma CPU AArch64 (ARMv8-A), EL0 apenas — irmão de
/// {@link dev.vitorsilverio.armjitter.core.ArmCore}, NÃO uma extensão dele (RFC-IR-64BIT.md §3.1:
/// A64 não tem banking de registrador geral por modo, não tem PC como registrador geral, e usa
/// `PSTATE` em vez de CPSR/SPSR — um supertipo comum "gordo" com o `ArmCore` de 32 bits só
/// contaminaria as duas classes).
///
/// Escopo desta task (B6.1): só `EL0` (nível de privilégio de aplicação), sem MMU, sem exceções
/// síncronas/assíncronas, sem múltiplos níveis de `SP` (`SP_EL0` é o único `SP` que existe aqui).
/// Esses recursos entram em tasks futuras do épico B6 (ver `b6-aarch64.md`, escopo fechado de
/// B6.3-B6.6).
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

    private final long[] x = new long[GENERAL_REGISTER_COUNT];
    private long sp;
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

    /// Retorna o stack pointer (`SP_EL0` — o único `SP` que existe neste core EL0-apenas).
    public long sp() {
        return sp;
    }

    /// Atualiza o stack pointer.
    public void setSp(long value) {
        sp = value;
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

    /// Abre (limpa) o monitor de exclusividade deste core. Sem consumidor de entrada de exceção
    /// nesta fatia (B6.3.4) — `Aarch64Core` ainda não tem modelo de exceção síncrona/assíncrona
    /// (EL0-only, B6.1); pendência explícita para quando esse gancho existir.
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

    private static void checkRegisterIndex(int index) {
        if (index < 0 || index > SPECIAL_REGISTER_ENCODING) {
            throw new IndexOutOfBoundsException(
                    "AArch64 register encoding index must be between 0 and 31: " + index);
        }
    }
}
