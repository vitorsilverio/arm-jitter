package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.util.Arrays;
import java.util.Objects;

/// Estado básico de uma CPU ARM usado pelo interpretador e pelo JIT.
public final class ArmCore {
    private static final int REGISTER_COUNT = 16;
    private static final int PC = 15;
    /// Índice do stack pointer (visível no pacote para {@link MProfileExceptionModel}, que
    /// mantém MSP/PSP como sombra do R13 ativo — B7.2).
    static final int SP = 13;
    /// Índice do link register (visível no pacote para {@link AProfileExceptionModel}).
    static final int LR = 14;
    /// Versão do formato de {@link #saveState}/{@link #loadState}: `1` = formato anterior à
    /// B3.3 (sem banco VFP/FPSCR), `2` = inclui o banco VFP completo, `3` = inclui o banco
    /// Hyp/Monitor (B9.8.1). Lida por este core, não pelos consumidores (`GbaConsole`/
    /// `NdsConsole` têm o próprio versionamento por cima, que já rejeita formatos incompatíveis
    /// por inteiro — esta versão interna existe para que `ArmCore.loadState` sozinho degrade
    /// sem exceção quando alimentado por um stream antigo, ex. em teste).
    private static final int STATE_VERSION = 3;
    private static final int RESET_CPSR = CpuMode.SUPERVISOR.bits()
            | CpsrRegister.IRQ_DISABLE_FLAG
            | CpsrRegister.FIQ_DISABLE_FLAG;
    /// `DFSR[11]` (`WnR`, ARM DDI 0406C B3.13.4): `1` se o Data Abort foi causado por uma
    /// instrução de escrita, `0` se foi leitura. Achado real da F3 (`virtual-arm-box`):
    /// {@link #enterMemoryAbort} nunca preenchia este bit (só o `FS[3:0]` de
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.FaultStatus#code()} chegava ao `DFSR`),
    /// mesmo com {@link MemoryTranslationException#accessType()} já disponível ali — o guest
    /// (Linux `do_page_fault`, que lê este bit para decidir `FAULT_FLAG_WRITE`) sempre via `WnR=0`
    /// e tratava toda falta de escrita como leitura, deixando de marcar a PTE como `dirty`/gravável
    /// mesmo depois de corrigir o `AP` de hardware — confirmado por dump de PTE real
    /// (`pin_page_for_write()` do kernel preso para sempre com `DIRTY=0`/`RDONLY=1`).
    private static final int DFSR_WNR_BIT = 1 << 11;

    private final int[] registers = new int[REGISTER_COUNT];
    private final int[] commonR8ToR12 = new int[5];
    private final int[] userSystemSpLr = new int[2];
    private final int[] fiqR8ToR14 = new int[7];
    private final int[] irqSpLr = new int[2];
    private final int[] supervisorSpLr = new int[2];
    private final int[] abortSpLr = new int[2];
    private final int[] undefinedSpLr = new int[2];
    private int supervisorSpsr;
    private int irqSpsr;
    private int fiqSpsr;
    private int abortSpsr;
    private int undefinedSpsr;
    /// `SP_hyp` (B9.8.1) — único registrador de propósito geral realmente bancado por Hyp mode.
    /// `LR` em Hyp mode NÃO é bancado (é o mesmo `LR_usr`/`LR_sys` compartilhado, ver
    /// {@link #saveSpLr}/{@link #restoreSpLr}); o registrador de retorno real do modo é
    /// {@link #elrHyp}, separado da numeração R0-R15.
    private int hypSp;
    /// `ELR_hyp` (B9.8.1) — registrador arquitetural À PARTE (fora de R0-R15, "regno 17" na
    /// nomenclatura do QEMU), usado por `ERET` executado em Hyp mode em vez de `LR`.
    private int elrHyp;
    private int hypSpsr;
    /// `SP_mon`/`LR_mon` (B9.8.1) — banco normal, mesmo padrão de `supervisorSpLr`/`abortSpLr`.
    private final int[] monitorSpLr = new int[2];
    private int monitorSpsr;
    private final CpsrRegister cpsr = new CpsrRegister();
    private final VfpRegisters vfp = new VfpRegisters();
    private final FpscrRegister fpscr = new FpscrRegister();
    private final AddressSpace memory;
    /// Cache de {@link AddressSpace#providesAccessCycles()} do barramento (capacidade estática):
    /// quando `false`, {@link #addMemoryCycles} retorna sem a chamada virtual por acesso.
    private final boolean memoryProvidesAccessCycles;
    private final SwiDispatcher swiDispatcher;
    /// Dispatcher de `BKPT` (B7.5, semihosting) — mesmo padrão aditivo de {@link #coprocessorBus}:
    /// vazio por padrão ({@link BkptDispatcher#empty()}), instalado pelo host via
    /// {@link #setBkptDispatcher} sem exigir mudança de assinatura de construtor (G3).
    private BkptDispatcher bkptDispatcher = BkptDispatcher.empty();
    private CoprocessorBus coprocessorBus = CoprocessorBus.none();
    /// Gancho de preenchimento de FAR/FSR em abort (B4.1.3): vazio por padrão ({@link MemoryAbortListener#NONE}),
    /// mesmo padrão aditivo de {@link #coprocessorBus} — ver {@link #setMemoryAbortListener}.
    private MemoryAbortListener memoryAbortListener = MemoryAbortListener.NONE;
    /// Gancho de troca de modo (B4.1.5): vazio por padrão ({@link ModeChangeListener#NONE}),
    /// mesmo padrão aditivo de {@link #memoryAbortListener} — ver {@link #setModeChangeListener}.
    private ModeChangeListener modeChangeListener = ModeChangeListener.NONE;
    /// Gancho de `CPSR.E` na entrada de exceção (task F3): vazio por padrão
    /// ({@link ExceptionEndiannessPolicy#NONE}), mesmo padrão aditivo de {@link #modeChangeListener}
    /// — ver {@link #setExceptionEndiannessPolicy}.
    private ExceptionEndiannessPolicy exceptionEndiannessPolicy = ExceptionEndiannessPolicy.NONE;
    private boolean highVectors;
    private final ArmInterpreter interpreter;
    private long cycles;
    private boolean interruptLine;
    private CpuMode activeMode = CpuMode.SUPERVISOR;
    private ArmTraceListener traceListener = ArmTraceListener.none();
    private CpuSleepState sleepState = CpuSleepState.RUNNING;

    /// Monitor de exclusividade LDREX/STREX (ARMv6). Por padrão é próprio deste core
    /// (comportamento single-core da B1.4); {@link #setExclusiveMonitor} instala um monitor
    /// COMPARTILHADO entre múltiplos cores (task B5.1, 3DS: os 2 ARM11 do MPCore dividem
    /// memória). É `long` internamente porque o §5 da RFC IR-64 exige que estado novo de
    /// endereçamento já nasça com largura de 64 bits (endereços de 32 bits entram sem sinal).
    private ExclusiveMonitor exclusiveMonitor = new ExclusiveMonitor();

    /// Modelo de entrada de exceção (B7.1). Default é o perfil A/R clássico; um perfil M
    /// (B7.2+) troca este campo antes de começar a executar (ver {@link #setExceptionModel}).
    private ExceptionModel exceptionModel = new AProfileExceptionModel();

    /// Cria um core conectado a uma memória e a um dispatcher de SWI.
    ///
    /// O estado inicial segue o reset de um ARM7TDMI: modo Supervisor, ARM state,
    /// IRQ/FIQ mascaradas e `PC = 0`.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher) {
        this(memory, swiDispatcher, new ArmInterpreter());
    }

    /// Cria um core para uma arquitetura ARM específica (ex.: ARMv4T para o GBA,
    /// ARMv5TE para o ARM9 do NDS). O interpretador interno é construído com ela.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher,
                   dev.vitorsilverio.armjitter.arch.ArmArchitecture architecture) {
        this(memory, swiDispatcher, new ArmInterpreter(architecture));
    }

    /// Cria um core conectado a uma memória, SWI e interpretador customizado.
    ///
    /// O estado inicial segue o reset de um ARM7TDMI: modo Supervisor, ARM state,
    /// IRQ/FIQ mascaradas e `PC = 0`.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher, ArmInterpreter interpreter) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.memoryProvidesAccessCycles = memory.providesAccessCycles();
        this.swiDispatcher = Objects.requireNonNull(swiDispatcher, "swiDispatcher");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        cpsr.set(RESET_CPSR);
    }

    /// Retorna uma cópia dos registradores r0-r15.
    public int[] registersSnapshot() {
        return Arrays.copyOf(registers, registers.length);
    }

    /// Serializa o estado completo da CPU (todos os bancos, SPSRs, CPSR, modo e flags)
    /// para um save state. Restaurado por {@link #loadState}.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(STATE_VERSION);
        writeInts(out, registers);
        writeInts(out, commonR8ToR12);
        writeInts(out, userSystemSpLr);
        writeInts(out, fiqR8ToR14);
        writeInts(out, irqSpLr);
        writeInts(out, supervisorSpLr);
        writeInts(out, abortSpLr);
        writeInts(out, undefinedSpLr);
        out.writeInt(supervisorSpsr);
        out.writeInt(irqSpsr);
        out.writeInt(fiqSpsr);
        out.writeInt(abortSpsr);
        out.writeInt(undefinedSpsr);
        out.writeInt(hypSp);
        out.writeInt(elrHyp);
        out.writeInt(hypSpsr);
        writeInts(out, monitorSpLr);
        out.writeInt(monitorSpsr);
        out.writeInt(cpsr.get());
        out.writeLong(cycles);
        out.writeBoolean(interruptLine);
        out.writeInt(activeMode.ordinal());
        out.writeInt(sleepState.ordinal());
        vfp.saveState(out);
        fpscr.saveState(out);
    }

    /// Restaura o estado completo da CPU gravado por {@link #saveState}. Streams de uma versão
    /// anterior à B3.3 (sem banco VFP/FPSCR) carregam com o banco VFP zerado.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        int version = in.readInt();
        readInts(in, registers);
        readInts(in, commonR8ToR12);
        readInts(in, userSystemSpLr);
        readInts(in, fiqR8ToR14);
        readInts(in, irqSpLr);
        readInts(in, supervisorSpLr);
        readInts(in, abortSpLr);
        readInts(in, undefinedSpLr);
        supervisorSpsr = in.readInt();
        irqSpsr = in.readInt();
        fiqSpsr = in.readInt();
        abortSpsr = in.readInt();
        undefinedSpsr = in.readInt();
        if (version >= 3) {
            hypSp = in.readInt();
            elrHyp = in.readInt();
            hypSpsr = in.readInt();
            readInts(in, monitorSpLr);
            monitorSpsr = in.readInt();
        } else {
            hypSp = 0;
            elrHyp = 0;
            hypSpsr = 0;
            java.util.Arrays.fill(monitorSpLr, 0);
            monitorSpsr = 0;
        }
        cpsr.set(in.readInt());
        cycles = in.readLong();
        interruptLine = in.readBoolean();
        activeMode = CpuMode.values()[in.readInt()];
        sleepState = CpuSleepState.values()[in.readInt()];
        // O monitor de exclusividade não é persistido (formato de save state inalterado):
        // abrir o monitor ao restaurar é uma falha espúria de STREX permitida pela arquitetura.
        clearExclusiveMonitor();
        if (version >= 2) {
            vfp.loadState(in);
            fpscr.loadState(in);
        } else {
            vfp.reset();
            fpscr.reset();
        }
    }

    private static void writeInts(java.io.DataOutputStream out, int[] values) throws java.io.IOException {
        for (int value : values) {
            out.writeInt(value);
        }
    }

    private static void readInts(java.io.DataInputStream in, int[] values) throws java.io.IOException {
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readInt();
        }
    }

    /// Lê um registrador pelo índice ARM, de 0 a 15.
    public int register(int index) {
        checkRegister(index);
        return registers[index];
    }

    /// Atualiza um registrador pelo índice ARM, de 0 a 15.
    public void setRegister(int index, int value) {
        checkRegister(index);
        registers[index] = value;
    }

    /// Retorna o program counter atual.
    public int programCounter() {
        return registers[PC];
    }

    /// Atualiza o program counter.
    public void setProgramCounter(int value) {
        registers[PC] = value;
    }

    /// Retorna o CPSR mutável associado ao core.
    public CpsrRegister cpsr() {
        return cpsr;
    }

    /// Retorna o banco de registradores VFP (S/D) associado ao core (B3.3 — só o estado,
    /// nenhuma instrução usa este banco ainda).
    public VfpRegisters vfp() {
        return vfp;
    }

    /// Retorna o FPSCR mutável associado ao core (B3.3 — só o estado, nenhuma instrução usa
    /// este registrador ainda).
    public FpscrRegister fpscr() {
        return fpscr;
    }

    /// Retorna o modo atual da CPU.
    public CpuMode mode() {
        synchronizeModeFromCpsr();
        return activeMode;
    }

    /// Substitui o CPSR e sincroniza o banco de registradores com o modo codificado.
    ///
    /// Este método e preferível a alterar `cpsr().set(...)` diretamente quando o valor
    /// novo troca o modo da CPU, como em handoff de boot ou skip BIOS.
    ///
    /// @param value valor bruto de 32 bits para o CPSR
    public void setCpsr(int value) {
        CpuMode nextMode = CpuMode.fromBits(value);
        if (nextMode != activeMode) {
            saveBank(activeMode);
            activeMode = nextMode;
            cpsr.set(value);
            restoreBank(nextMode);
            modeChangeListener.onModeChanged(nextMode);
            return;
        }
        cpsr.set(value);
    }

    /// Configura somente o conjunto de instruções atual no CPSR.
    ///
    /// @param instructionSet conjunto ARM ou THUMB desejado
    public void setInstructionSet(InstructionSet instructionSet) {
        cpsr.setThumbMode(Objects.requireNonNull(instructionSet, "instructionSet") == InstructionSet.THUMB);
    }

    /// Configura PC e CPSR em uma única chamada, útil para skip BIOS e snapshots.
    ///
    /// @param pc novo program counter
    /// @param cpsrValue valor bruto de 32 bits para o CPSR
    public void configureExecutionState(int pc, int cpsrValue) {
        setCpsr(cpsrValue);
        setProgramCounter(pc);
    }

    /// Configura PC, modo, ARM/THUMB e máscaras de interrupção para handoff de boot.
    ///
    /// @param pc novo program counter
    /// @param mode modo de CPU desejado
    /// @param instructionSet conjunto ARM ou THUMB desejado
    /// @param irqDisabled `true` para setar o bit I do CPSR
    /// @param fiqDisabled `true` para setar o bit F do CPSR
    public void configureExecutionState(
            int pc,
            CpuMode mode,
            InstructionSet instructionSet,
            boolean irqDisabled,
            boolean fiqDisabled) {
        int value = Objects.requireNonNull(mode, "mode").bits();
        if (Objects.requireNonNull(instructionSet, "instructionSet") == InstructionSet.THUMB) {
            value |= CpsrRegister.THUMB_FLAG;
        }
        if (irqDisabled) {
            value |= CpsrRegister.IRQ_DISABLE_FLAG;
        }
        if (fiqDisabled) {
            value |= CpsrRegister.FIQ_DISABLE_FLAG;
        }
        configureExecutionState(pc, value);
    }

    /// Retorna o listener de trace instalado.
    ///
    /// @return listener atual
    public ArmTraceListener traceListener() {
        return traceListener;
    }

    /// Instala um listener de trace para observar instrução e bloco executados.
    ///
    /// @param traceListener novo listener
    public void setTraceListener(ArmTraceListener traceListener) {
        this.traceListener = Objects.requireNonNull(traceListener, "traceListener");
    }

    /// Retorna o barramento de memória conectado ao core.
    public AddressSpace memory() {
        return memory;
    }

    /// Retorna o dispatcher de SWI configurado para o core.
    public SwiDispatcher swiDispatcher() {
        return swiDispatcher;
    }

    /// Retorna o dispatcher de `BKPT` (B7.5, semihosting). Padrão é {@link BkptDispatcher#empty()}
    /// (nenhum imediato tratado — `BKPT` vira `UNDEFINED`) até que {@link #setBkptDispatcher} instale um.
    public BkptDispatcher bkptDispatcher() {
        return bkptDispatcher;
    }

    /// Instala o dispatcher de `BKPT` usado pelo host para semihosting (ex. armbox `--machine=cortex-m`).
    public void setBkptDispatcher(BkptDispatcher bkptDispatcher) {
        this.bkptDispatcher = Objects.requireNonNull(bkptDispatcher, "bkptDispatcher");
    }

    /// Retorna o barramento do coprocessador que atende `MCR`/`MRC` (ex. CP15 do ARM9). Padrão é
    /// {@link CoprocessorBus#none()} até que {@link #setCoprocessorBus} instale um.
    public CoprocessorBus coprocessorBus() {
        return coprocessorBus;
    }

    /// Instala o barramento de coprocessador usado pelas instruções `MCR`/`MRC` (ex. CP15 do ARM9).
    public void setCoprocessorBus(CoprocessorBus coprocessorBus) {
        this.coprocessorBus = Objects.requireNonNull(coprocessorBus, "coprocessorBus");
    }

    /// Retorna o gancho de FAR/FSR em abort (B4.1.3). Padrão é {@link MemoryAbortListener#NONE}
    /// até que {@link #setMemoryAbortListener} instale um (ex. `Cp15VmsaCoprocessor`).
    public MemoryAbortListener memoryAbortListener() {
        return memoryAbortListener;
    }

    /// Instala o gancho de FAR/FSR usado por {@link #enterMemoryAbort} (ex. CP15 VMSA do ARM9).
    public void setMemoryAbortListener(MemoryAbortListener memoryAbortListener) {
        this.memoryAbortListener = Objects.requireNonNull(memoryAbortListener, "memoryAbortListener");
    }

    /// Define se as exceções vetorizam para a base alta `0xFFFF0000` (ARM9 com o bit V do CP15 c1
    /// ativado) em vez de `0x00000000`. O GBA e o NDS ARM7 mantêm isto desativado.
    public boolean highVectors() {
        return highVectors;
    }

    /// Seleciona a base de vetor de exceção alta (`0xFFFF0000`), controlada pelo CP15 c1\[V\] do ARM9.
    public void setHighVectors(boolean highVectors) {
        this.highVectors = highVectors;
    }

    /// Soma ciclos consumidos por interpretação ou bloco compilado.
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

    /// Sobrescreve o total de ciclos acumulados diretamente, sem a checagem "só soma" de
    /// {@link #addCycles} — para hospedeiros que precisam reafirmar o relógio virtual
    /// COMPARTILHADO depois de {@link #loadState}, que restaura {@code cycles} como parte do
    /// snapshot de UM contexto (correto para save-state real de CPU única; errado quando este
    /// `ArmCore` é multiplexado entre vários contextos cooperativos que devem enxergar o MESMO
    /// relógio — ver o consumidor real em `n3dsemu Scheduler#switchTo`, onde restaurar o
    /// snapshot antigo de uma thread sem isto faz o relógio virtual andar para trás a cada troca
    /// de contexto).
    public void setCycles(long cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be non-negative");
        }
        this.cycles = cycles;
    }

    /// Soma os ciclos extras informados pelo barramento para um acesso de memória.
    ///
    /// @param address endereço acessado
    /// @param sizeBytes tamanho em bytes
    /// @param type tipo de acesso
    public void addMemoryCycles(int address, int sizeBytes, MemoryAccessType type) {
        // Barramentos sem waitstates (ex.: NDS) reportam providesAccessCycles()==false:
        // pula a chamada virtual por acesso (chamada por fetch e por load/store).
        if (!memoryProvidesAccessCycles) {
            return;
        }
        int memoryCycles = memory.accessCycles(address, sizeBytes, Objects.requireNonNull(type, "type"));
        if (memoryCycles < 0) {
            throw new IllegalArgumentException("memory access cycles must be >= 0");
        }
        addCycles(memoryCycles);
    }

    /// Atualiza a linha externa de interrupção.
    public void setInterruptLine(boolean asserted) {
        interruptLine = asserted;
        if (asserted) {
            sleepState = CpuSleepState.RUNNING;
        }
    }

    /// Retorna `true` quando a linha externa de interrupção está ativa.
    public boolean interruptLine() {
        return interruptLine;
    }

    /// Coloca a CPU em HALT até uma interrupção acordar o core.
    public void halt() {
        sleepState = CpuSleepState.HALTED;
    }

    /// Coloca a CPU em STOP até uma interrupção acordar o core.
    public void stop() {
        sleepState = CpuSleepState.STOPPED;
    }

    /// Acorda a CPU manualmente, sem alterar linhas de interrupção.
    public void wake() {
        sleepState = CpuSleepState.RUNNING;
    }

    /// Retorna o estado de espera atual da CPU.
    ///
    /// @return estado de espera atual
    public CpuSleepState sleepState() {
        return sleepState;
    }

    /// Retorna `true` quando a CPU está em HALT.
    ///
    /// @return `true` quando em HALT
    public boolean halted() {
        return sleepState == CpuSleepState.HALTED;
    }

    /// Retorna `true` quando a CPU está em STOP.
    ///
    /// @return `true` quando em STOP
    public boolean stopped() {
        return sleepState == CpuSleepState.STOPPED;
    }

    /// Executa uma única instrução quando um interpretador estiver conectado.
    public DecodedInstruction step() {
        return executeSingleInstruction().instruction();
    }

    /// Executa uma instrução no caminho frio e retorna apenas os ciclos internos (`IrOp.Cycle`).
    ///
    /// Fetch e waitstates de memória continuam sendo somados em {@link #cycles()}, mas não
    /// entram no retorno — o mesmo contrato de {@link dev.vitorsilverio.armjitter.jit.JitRuntime#execute}.
    public int stepReturningInternalCycles() {
        return executeSingleInstruction().internalCycles();
    }

    private SingleInstructionExecution executeSingleInstruction() {
        synchronizeModeFromCpsr();
        int pc = programCounter();
        InstructionSet instructionSet = currentInstructionSet();
        traceListener.beforeInstruction(this, pc, instructionSet);
        if (sleepState != CpuSleepState.RUNNING) {
            addCycles(1);
            DecodedInstruction instruction = DecodedInstruction.unimplemented(pc, 0, instructionSet, Condition.AL);
            traceListener.afterInstruction(this, instruction);
            return new SingleInstructionExecution(instruction, 1);
        }
        if (servicePendingIrq()) {
            addCycles(1);
            DecodedInstruction instruction = DecodedInstruction.unimplemented(pc, 0, instructionSet, Condition.AL);
            traceListener.afterInstruction(this, instruction);
            return new SingleInstructionExecution(instruction, 1);
        }
        ArmInterpreter.StepResult result;
        try {
            result = interpreter.stepWithResult(this);
        } catch (MemoryTranslationException fault) {
            // pc é o endereço da PRÓPRIA instrução (capturado antes do decode/lift/execute acima),
            // correto tanto para uma falta na busca da instrução (decode) quanto numa falta de
            // dados (Load/Store/MultipleTransfer executados pelo bloco IR de uma única instrução).
            enterMemoryAbort(pc, fault);
            DecodedInstruction instruction = DecodedInstruction.unimplemented(pc, 0, instructionSet, Condition.AL);
            traceListener.afterInstruction(this, instruction);
            return new SingleInstructionExecution(instruction, 1);
        }
        traceListener.afterInstruction(this, result.instruction());
        return new SingleInstructionExecution(result.instruction(), result.internalCycles());
    }

    private record SingleInstructionExecution(DecodedInstruction instruction, int internalCycles) {
    }

    /// Executa até `instructionCount` instruções pelo interpretador frio.
    public int step(int instructionCount) {
        if (instructionCount < 0) {
            throw new IllegalArgumentException("instructionCount must be >= 0");
        }
        for (int i = 0; i < instructionCount; i++) {
            step();
        }
        return instructionCount;
    }

    /// Executa um bloco quando o runtime JIT estiver conectado.
    public int runBlock() {
        throw new UnsupportedOperationException("JIT block execution is not implemented yet");
    }

    /// Executa um bloco pelo runtime JIT informado.
    public int runBlock(JitRuntime runtime) {
        synchronizeModeFromCpsr();
        int pc = programCounter();
        InstructionSet instructionSet = currentInstructionSet();
        traceListener.beforeBlock(this, pc, instructionSet);
        if (sleepState != CpuSleepState.RUNNING) {
            addCycles(1);
            traceListener.afterBlock(this, pc, instructionSet, 1);
            return 1;
        }
        if (servicePendingIrq()) {
            addCycles(1);
            traceListener.afterBlock(this, pc, instructionSet, 1);
            return 1;
        }
        int cycles = runtime.execute(pc, this);
        traceListener.afterBlock(this, pc, instructionSet, cycles);
        return cycles;
    }

    /// Executa ate `blockCount` blocos pelo runtime JIT informado.
    public long runBlocks(JitRuntime runtime, int blockCount) {
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must be >= 0");
        }
        long consumed = 0;
        for (int i = 0; i < blockCount; i++) {
            consumed += runBlock(runtime);
        }
        return consumed;
    }

    /// Processa uma exceção ARM futura.
    public void handleException(ArmException exception) {
        requestException(exception);
    }

    /// Converte uma falha de tradução de endereço (MMU) na entrada de exceção ARM correspondente
    /// (B4.1.3, RFC-SOFTMMU §3): `PREFETCH_ABORT` para busca de instrução, `DATA_ABORT` para
    /// leitura/escrita de dados. Preenche FAR/FSR via {@link #memoryAbortListener} ANTES de entrar
    /// na exceção — `AProfileExceptionModel#exceptionReturnAddress` soma o deslocamento (+4/+8)
    /// correto a partir de {@link #programCounter()}, então `instructionAddress` deve ser o
    /// endereço da PRÓPRIA instrução faltosa (não o sequencial seguinte, ao contrário de SWI).
    ///
    /// @param instructionAddress endereço da instrução que causou a falta
    /// @param fault falha capturada de {@link AddressSpace#read32}/`write32`/etc. (via
    ///              `TranslatingAddressSpace`)
    public void enterMemoryAbort(int instructionAddress, MemoryTranslationException fault) {
        traceListener.onMemoryAbort(this, instructionAddress, fault);
        boolean isInstructionFetch = fault.accessType() == MemoryAccessType.INSTRUCTION_FETCH;
        int faultStatusCode = fault.faultStatus().code();
        if (isInstructionFetch) {
            memoryAbortListener.onPrefetchAbort(fault.virtualAddress(), faultStatusCode);
        } else {
            boolean isWrite = fault.accessType() == MemoryAccessType.DATA_WRITE;
            int dataFaultStatus = isWrite ? (faultStatusCode | DFSR_WNR_BIT) : faultStatusCode;
            memoryAbortListener.onDataAbort(fault.virtualAddress(), dataFaultStatus);
        }
        setProgramCounter(instructionAddress);
        requestException(isInstructionFetch ? ArmException.PREFETCH_ABORT : ArmException.DATA_ABORT);
    }

    /// Solicita entrada numa exceção ARM usando o PC atual como base de retorno.
    public void requestException(ArmException exception) {
        Objects.requireNonNull(exception, "exception");
        exceptionModel.enterException(this, exception);
    }

    /// Retorna o modelo de entrada de exceção instalado (perfil A/R por padrão).
    public ExceptionModel exceptionModel() {
        return exceptionModel;
    }

    /// Instala um modelo de entrada de exceção alternativo (B7.2+: perfil M). Deve ser chamado
    /// antes de começar a executar — trocar o modelo em meio à execução não é suportado.
    public void setExceptionModel(ExceptionModel exceptionModel) {
        this.exceptionModel = Objects.requireNonNull(exceptionModel, "exceptionModel");
    }

    /// Lê um registrador bancado para o modo informado.
    public int bankedRegister(CpuMode mode, int register) {
        Objects.requireNonNull(mode, "mode");
        checkRegister(register);
        if (register <= 7 || register == PC) {
            return registers[register];
        }
        if (mode == activeMode) {
            return registers[register];
        }
        if (register >= 8 && register <= 12) {
            if (mode == CpuMode.FIQ) {
                return fiqR8ToR14[register - 8];
            }
            return activeMode == CpuMode.FIQ ? commonR8ToR12[register - 8] : registers[register];
        }
        if (mode == CpuMode.FIQ) {
            return fiqR8ToR14[register - 8];
        }
        if (mode == CpuMode.HYP) {
            // SP_hyp é banco próprio; LR em Hyp mode É o LR_usr/LR_sys compartilhado (não há
            // banco Hyp para ele) — ver javadoc de #hypSp.
            return register == SP ? hypSp : userSystemSpLr[1];
        }
        int[] bank = spLrBank(mode);
        return bank[register - SP];
    }

    /// Atualiza um registrador bancado para o modo informado.
    public void setBankedRegister(CpuMode mode, int register, int value) {
        Objects.requireNonNull(mode, "mode");
        checkRegister(register);
        if (mode == activeMode || register <= 7 || register == PC) {
            registers[register] = value;
            return;
        }
        if (register >= 8 && register <= 12) {
            if (mode == CpuMode.FIQ) {
                fiqR8ToR14[register - 8] = value;
            } else if (activeMode == CpuMode.FIQ) {
                commonR8ToR12[register - 8] = value;
            } else {
                registers[register] = value;
            }
            return;
        }
        if (mode == CpuMode.FIQ) {
            fiqR8ToR14[register - 8] = value;
            return;
        }
        if (mode == CpuMode.HYP) {
            if (register == SP) {
                hypSp = value;
            } else {
                userSystemSpLr[1] = value;
            }
            return;
        }
        int[] bank = spLrBank(mode);
        bank[register - SP] = value;
    }

    /// Lê o SPSR associado a um modo privilegiado.
    public int spsr(CpuMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SUPERVISOR -> supervisorSpsr;
            case IRQ -> irqSpsr;
            case FIQ -> fiqSpsr;
            case ABORT -> abortSpsr;
            case UNDEFINED -> undefinedSpsr;
            case HYP -> hypSpsr;
            case MONITOR -> monitorSpsr;
            case USER, SYSTEM -> throw new IllegalArgumentException("Mode has no SPSR: " + mode);
        };
    }

    /// Atualiza o SPSR associado a um modo privilegiado.
    public void setSpsr(CpuMode mode, int value) {
        switch (Objects.requireNonNull(mode, "mode")) {
            case SUPERVISOR -> supervisorSpsr = value;
            case IRQ -> irqSpsr = value;
            case FIQ -> fiqSpsr = value;
            case ABORT -> abortSpsr = value;
            case UNDEFINED -> undefinedSpsr = value;
            case HYP -> hypSpsr = value;
            case MONITOR -> monitorSpsr = value;
            case USER, SYSTEM -> throw new IllegalArgumentException("Mode has no SPSR: " + mode);
        }
    }

    /// Lê `ELR_hyp` (B9.8.1) — registrador de retorno de Hyp mode, usado por `ERET` quando
    /// executado nesse modo em vez de `LR`. Registrador arquitetural À PARTE, fora da numeração
    /// R0-R15 (não confundir com `LR`/registrador 14, que em Hyp mode é o `LR_usr` compartilhado
    /// — ver {@link #saveSpLr}).
    public int elrHyp() {
        return elrHyp;
    }

    /// Atualiza `ELR_hyp` (B9.8.1).
    public void setElrHyp(int value) {
        elrHyp = value;
    }

    /// Troca o modo atual salvando e restaurando bancos de registradores.
    public void switchMode(CpuMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode == activeMode) {
            cpsr.setMode(mode);
            return;
        }
        saveBank(activeMode);
        activeMode = mode;
        cpsr.setMode(mode);
        restoreBank(mode);
        modeChangeListener.onModeChanged(mode);
    }

    /// Instala o gancho de troca de modo (B4.1.5). Ver {@link ModeChangeListener} para por que um
    /// hospedeiro com MMU precisa dele.
    public void setModeChangeListener(ModeChangeListener modeChangeListener) {
        this.modeChangeListener = Objects.requireNonNull(modeChangeListener, "modeChangeListener");
        modeChangeListener.onModeChanged(activeMode);
    }

    /// Instala o gancho de `CPSR.E` na entrada de exceção (task F3). Ver
    /// {@link ExceptionEndiannessPolicy} para por que um hospedeiro com CP15/`SCTLR.EE` precisa
    /// dele.
    public void setExceptionEndiannessPolicy(ExceptionEndiannessPolicy exceptionEndiannessPolicy) {
        this.exceptionEndiannessPolicy =
                Objects.requireNonNull(exceptionEndiannessPolicy, "exceptionEndiannessPolicy");
    }

    /// Chamado por {@link AProfileExceptionModel#enterException} para aplicar o gancho de
    /// `CPSR.E` instalado (ou nada, sob {@link ExceptionEndiannessPolicy#NONE}).
    void applyExceptionEndiannessPolicy() {
        exceptionEndiannessPolicy.applyOnExceptionEntry(cpsr);
    }

    /// Instala um monitor de exclusividade COMPARTILHADO entre múltiplos cores (task B5.1, 3DS:
    /// os 2 ARM11 do MPCore sobre a mesma memória; um `STREX` de um core precisa poder derrubar
    /// a reserva feita por outro). Default é um monitor próprio deste core, zero-diff com a B1.4.
    public void setExclusiveMonitor(ExclusiveMonitor exclusiveMonitor) {
        this.exclusiveMonitor = Objects.requireNonNull(exclusiveMonitor, "exclusiveMonitor");
    }

    /// Marca o monitor de exclusividade (próprio ou compartilhado) para o endereço/tamanho de um
    /// `LDREX{,B,H,D}`, com este core como dono da reserva.
    ///
    /// @param address endereço do acesso, sem sinal (endereços de 32 bits devem chegar via
    ///                `Integer.toUnsignedLong`)
    /// @param sizeBytes tamanho do acesso (1, 2, 4 ou 8)
    public void markExclusive(long address, int sizeBytes) {
        exclusiveMonitor.markExclusive(this, address, sizeBytes);
    }

    /// Consulta um `STREX{,B,H,D}` deste core contra o monitor de exclusividade. Exige marcação
    /// exata (mesmo endereço e mesmo tamanho — o hardware permite falhas espúrias, então a
    /// granularidade exata é uma escolha válida). Quando a região bate, a reserva é SEMPRE
    /// consumida (mesmo se o dono for outro core, compartilhando o monitor via
    /// {@link #setExclusiveMonitor} — um STREX perdedor também derruba a reserva de quem a fez);
    /// só devolve `true` quando este core é o dono.
    public boolean exclusiveMonitorCovers(long address, int sizeBytes) {
        return exclusiveMonitor.consumeIfCovered(this, address, sizeBytes);
    }

    /// Abre (limpa) o monitor de exclusividade: `CLREX`, entrada de exceção e STREX bem-sucedido.
    public void clearExclusiveMonitor() {
        exclusiveMonitor.clear(this);
    }

    /// Notifica o monitor de exclusividade de uma escrita comum (`STR`/`STRH`/`STRB`) deste core.
    /// Se sobrepuser uma reserva pendente — deste core ou de outro, quando o monitor é
    /// compartilhado (B5.1) — abre a reserva, como no hardware real.
    public void notifyOrdinaryWrite(int address, int sizeBytes) {
        if (exclusiveMonitor.isArmed()) {
            exclusiveMonitor.notifyOrdinaryWrite(Integer.toUnsignedLong(address), sizeBytes);
        }
    }

    /// Endereço marcado no monitor de exclusividade, ou `-1` quando aberto. Exposto para o
    /// harness de equivalência ({@code CpuSnapshot}) detectar divergência de backend.
    public long exclusiveMonitorAddress() {
        return exclusiveMonitor.address(this);
    }

    /// Tamanho em bytes marcado no monitor de exclusividade (0 quando aberto).
    public int exclusiveMonitorSizeBytes() {
        return exclusiveMonitor.sizeBytes(this);
    }

    /// Exporta um snapshot imutável usado por handlers de SWI.
    public CpuState toCpuState() {
        return new CpuState(registers[0], registers[1], registers[2], registers[3],
                registers[13], registers[14], registers[15], cpsr.get());
    }

    /// Aplica um snapshot retornado por handler de SWI.
    public void apply(CpuState state) {
        setCpsr(state.cpsr());
        registers[0] = state.r0();
        registers[1] = state.r1();
        registers[2] = state.r2();
        registers[3] = state.r3();
        registers[13] = state.sp();
        registers[14] = state.lr();
        registers[15] = state.pc();
    }

    private InstructionSet currentInstructionSet() {
        return cpsr.isThumbMode() ? InstructionSet.THUMB : InstructionSet.ARM;
    }

    private boolean servicePendingIrq() {
        if (interruptLine && !cpsr.irqDisabled()) {
            sleepState = CpuSleepState.RUNNING;
            exceptionModel.enterException(this, ArmException.IRQ);
            return true;
        }
        // Perfil M (B7.3+): NVIC/SysTick em vez da linha de IRQ única do perfil A. Método
        // default `false` no perfil A (mesma técnica de `interceptsBranch`, já no caminho quente
        // de todo branch) — ver javadoc de `ExceptionModel#hasPendingException`.
        if (exceptionModel.hasPendingException()) {
            sleepState = CpuSleepState.RUNNING;
            exceptionModel.enterPendingException(this);
            return true;
        }
        return false;
    }

    private void synchronizeModeFromCpsr() {
        CpuMode cpsrMode = cpsr.mode();
        if (cpsrMode != activeMode) {
            switchMode(cpsrMode);
        }
    }

    private void saveBank(CpuMode mode) {
        saveSpLr(mode);
        if (mode == CpuMode.FIQ) {
            System.arraycopy(registers, 8, fiqR8ToR14, 0, 5);
        } else {
            System.arraycopy(registers, 8, commonR8ToR12, 0, 5);
        }
    }

    private void restoreBank(CpuMode mode) {
        restoreSpLr(mode);
        if (mode == CpuMode.FIQ) {
            System.arraycopy(fiqR8ToR14, 0, registers, 8, 5);
        } else {
            System.arraycopy(commonR8ToR12, 0, registers, 8, 5);
        }
    }

    private void saveSpLr(CpuMode mode) {
        if (mode == CpuMode.FIQ) {
            fiqR8ToR14[SP - 8] = registers[SP];
            fiqR8ToR14[LR - 8] = registers[LR];
            return;
        }
        if (mode == CpuMode.HYP) {
            // LR em Hyp mode é o MESMO registrador físico que LR_usr/LR_sys (não bancado) —
            // ao sair de Hyp mode, o valor corrente precisa ser escrito de volta no banco
            // usr/sys compartilhado, senão uma escrita feita durante a execução em Hyp mode
            // "some" ao trocar de modo. Ver javadoc de #hypSp.
            hypSp = registers[SP];
            userSystemSpLr[1] = registers[LR];
            return;
        }
        int[] spLr = spLrBank(mode);
        spLr[0] = registers[SP];
        spLr[1] = registers[LR];
    }

    private void restoreSpLr(CpuMode mode) {
        if (mode == CpuMode.FIQ) {
            registers[SP] = fiqR8ToR14[SP - 8];
            registers[LR] = fiqR8ToR14[LR - 8];
            return;
        }
        if (mode == CpuMode.HYP) {
            registers[SP] = hypSp;
            registers[LR] = userSystemSpLr[1];
            return;
        }
        int[] spLr = spLrBank(mode);
        registers[SP] = spLr[0];
        registers[LR] = spLr[1];
    }

    private int[] spLrBank(CpuMode mode) {
        return switch (mode) {
            case USER, SYSTEM -> userSystemSpLr;
            case FIQ -> throw new IllegalArgumentException("FIQ SP/LR are stored in the FIQ r8-r14 bank");
            case IRQ -> irqSpLr;
            case SUPERVISOR -> supervisorSpLr;
            case ABORT -> abortSpLr;
            case UNDEFINED -> undefinedSpLr;
            case HYP -> throw new IllegalArgumentException(
                    "HYP SP/LR are not a peer bank — SP is hypSp, LR is the shared userSystemSpLr[1]");
            case MONITOR -> monitorSpLr;
        };
    }

    private static void checkRegister(int index) {
        if (index < 0 || index >= REGISTER_COUNT) {
            throw new IndexOutOfBoundsException("ARM register index must be between 0 and 15: " + index);
        }
    }
}
