package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.util.Arrays;
import java.util.Objects;

/// Estado basico de uma CPU ARM usado pelo interpretador e pelo JIT.
public final class ArmCore {
    private static final int REGISTER_COUNT = 16;
    private static final int PC = 15;
    private static final int SP = 13;
    private static final int LR = 14;
    private static final int RESET_CPSR = CpuMode.SUPERVISOR.bits()
            | CpsrRegister.IRQ_DISABLE_FLAG
            | CpsrRegister.FIQ_DISABLE_FLAG;

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
    private final CpsrRegister cpsr = new CpsrRegister();
    private final AddressSpace memory;
    private final SwiDispatcher swiDispatcher;
    private final ArmInterpreter interpreter;
    private long cycles;
    private boolean interruptLine;
    private CpuMode activeMode = CpuMode.SUPERVISOR;
    private ArmTraceListener traceListener = ArmTraceListener.none();
    private CpuSleepState sleepState = CpuSleepState.RUNNING;

    /// Cria um core conectado a uma memoria e a um dispatcher de SWI.
    ///
    /// O estado inicial segue o reset de um ARM7TDMI: modo Supervisor, ARM state,
    /// IRQ/FIQ mascaradas e `PC = 0`.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher) {
        this(memory, swiDispatcher, new ArmInterpreter());
    }

    /// Cria um core conectado a uma memoria, SWI e interpretador customizado.
    ///
    /// O estado inicial segue o reset de um ARM7TDMI: modo Supervisor, ARM state,
    /// IRQ/FIQ mascaradas e `PC = 0`.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher, ArmInterpreter interpreter) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.swiDispatcher = Objects.requireNonNull(swiDispatcher, "swiDispatcher");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        cpsr.set(RESET_CPSR);
    }

    /// Retorna uma copia dos registradores r0-r15.
    public int[] registersSnapshot() {
        return Arrays.copyOf(registers, registers.length);
    }

    /// Le um registrador pelo indice ARM, de 0 a 15.
    public int register(int index) {
        checkRegister(index);
        return registers[index];
    }

    /// Atualiza um registrador pelo indice ARM, de 0 a 15.
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

    /// Retorna o CPSR mutavel associado ao core.
    public CpsrRegister cpsr() {
        return cpsr;
    }

    /// Retorna o modo atual da CPU.
    public CpuMode mode() {
        synchronizeModeFromCpsr();
        return activeMode;
    }

    /// Substitui o CPSR e sincroniza o banco de registradores com o modo codificado.
    ///
    /// Este metodo e preferivel a alterar `cpsr().set(...)` diretamente quando o valor
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
            return;
        }
        cpsr.set(value);
    }

    /// Configura somente o conjunto de instrucoes atual no CPSR.
    ///
    /// @param instructionSet conjunto ARM ou THUMB desejado
    public void setInstructionSet(InstructionSet instructionSet) {
        cpsr.setThumbMode(Objects.requireNonNull(instructionSet, "instructionSet") == InstructionSet.THUMB);
    }

    /// Configura PC e CPSR em uma unica chamada, util para skip BIOS e snapshots.
    ///
    /// @param pc novo program counter
    /// @param cpsrValue valor bruto de 32 bits para o CPSR
    public void configureExecutionState(int pc, int cpsrValue) {
        setCpsr(cpsrValue);
        setProgramCounter(pc);
    }

    /// Configura PC, modo, ARM/THUMB e mascaras de interrupcao para handoff de boot.
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

    /// Instala um listener de trace para observar instrucao e bloco executados.
    ///
    /// @param traceListener novo listener
    public void setTraceListener(ArmTraceListener traceListener) {
        this.traceListener = Objects.requireNonNull(traceListener, "traceListener");
    }

    /// Retorna o barramento de memoria conectado ao core.
    public AddressSpace memory() {
        return memory;
    }

    /// Retorna o dispatcher de SWI configurado para o core.
    public SwiDispatcher swiDispatcher() {
        return swiDispatcher;
    }

    /// Soma ciclos consumidos por interpretacao ou bloco compilado.
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

    /// Soma os ciclos extras informados pelo barramento para um acesso de memoria.
    ///
    /// @param address endereco acessado
    /// @param sizeBytes tamanho em bytes
    /// @param type tipo de acesso
    public void addMemoryCycles(int address, int sizeBytes, MemoryAccessType type) {
        int memoryCycles = memory.accessCycles(address, sizeBytes, Objects.requireNonNull(type, "type"));
        if (memoryCycles < 0) {
            throw new IllegalArgumentException("memory access cycles must be >= 0");
        }
        addCycles(memoryCycles);
    }

    /// Atualiza a linha externa de interrupcao.
    public void setInterruptLine(boolean asserted) {
        interruptLine = asserted;
        if (asserted) {
            sleepState = CpuSleepState.RUNNING;
        }
    }

    /// Retorna `true` quando a linha externa de interrupcao esta ativa.
    public boolean interruptLine() {
        return interruptLine;
    }

    /// Coloca a CPU em HALT ate uma interrupcao acordar o core.
    public void halt() {
        sleepState = CpuSleepState.HALTED;
    }

    /// Coloca a CPU em STOP ate uma interrupcao acordar o core.
    public void stop() {
        sleepState = CpuSleepState.STOPPED;
    }

    /// Acorda a CPU manualmente, sem alterar linhas de interrupcao.
    public void wake() {
        sleepState = CpuSleepState.RUNNING;
    }

    /// Retorna o estado de espera atual da CPU.
    ///
    /// @return estado de espera atual
    public CpuSleepState sleepState() {
        return sleepState;
    }

    /// Retorna `true` quando a CPU esta em HALT.
    ///
    /// @return `true` quando em HALT
    public boolean halted() {
        return sleepState == CpuSleepState.HALTED;
    }

    /// Retorna `true` quando a CPU esta em STOP.
    ///
    /// @return `true` quando em STOP
    public boolean stopped() {
        return sleepState == CpuSleepState.STOPPED;
    }

    /// Executa uma unica instrucao quando um interpretador estiver conectado.
    public DecodedInstruction step() {
        synchronizeModeFromCpsr();
        int pc = programCounter();
        InstructionSet instructionSet = currentInstructionSet();
        traceListener.beforeInstruction(this, pc, instructionSet);
        if (sleepState != CpuSleepState.RUNNING) {
            addCycles(1);
            DecodedInstruction instruction = DecodedInstruction.unimplemented(pc, 0, instructionSet, Condition.AL);
            traceListener.afterInstruction(this, instruction);
            return instruction;
        }
        if (servicePendingIrq()) {
            addCycles(1);
            DecodedInstruction instruction = DecodedInstruction.unimplemented(pc, 0, instructionSet, Condition.AL);
            traceListener.afterInstruction(this, instruction);
            return instruction;
        }
        DecodedInstruction instruction = interpreter.step(this);
        traceListener.afterInstruction(this, instruction);
        return instruction;
    }

    /// Executa ate `instructionCount` instrucoes pelo interpretador frio.
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

    /// Processa uma excecao ARM futura.
    public void handleException(ArmException exception) {
        requestException(exception);
    }

    /// Solicita entrada em uma excecao ARM usando o PC atual como base de retorno.
    public void requestException(ArmException exception) {
        Objects.requireNonNull(exception, "exception");
        int returnAddress = exceptionReturnAddress(exception);
        enterException(exception, returnAddress);
    }

    /// Le um registrador bancado para o modo informado.
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
        int[] bank = spLrBank(mode);
        bank[register - SP] = value;
    }

    /// Le o SPSR associado a um modo privilegiado.
    public int spsr(CpuMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SUPERVISOR -> supervisorSpsr;
            case IRQ -> irqSpsr;
            case FIQ -> fiqSpsr;
            case ABORT -> abortSpsr;
            case UNDEFINED -> undefinedSpsr;
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
            case USER, SYSTEM -> throw new IllegalArgumentException("Mode has no SPSR: " + mode);
        }
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
    }

    /// Exporta um snapshot imutavel usado por handlers de SWI.
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
            enterException(ArmException.IRQ, programCounter() + (cpsr.isThumbMode() ? 4 : 8));
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

    private void enterException(ArmException exception, int returnAddress) {
        CpuMode targetMode = exceptionMode(exception);
        int vector = exceptionVector(exception);
        int oldCpsr = cpsr.get();
        switchMode(targetMode);
        setSpsr(targetMode, oldCpsr);
        registers[LR] = returnAddress;
        cpsr.setThumbMode(false);
        cpsr.setIrqDisabled(true);
        if (exception == ArmException.RESET || exception == ArmException.FIQ) {
            cpsr.setFiqDisabled(true);
        }
        setProgramCounter(vector);
    }

    private int exceptionReturnAddress(ArmException exception) {
        return switch (exception) {
            case SWI, UNDEFINED -> programCounter();
            case IRQ, FIQ -> programCounter() + 4;
            case PREFETCH_ABORT -> programCounter() + 4;
            case DATA_ABORT -> programCounter() + 8;
            case RESET -> 0;
        };
    }

    private CpuMode exceptionMode(ArmException exception) {
        return switch (exception) {
            case RESET, SWI -> CpuMode.SUPERVISOR;
            case UNDEFINED -> CpuMode.UNDEFINED;
            case PREFETCH_ABORT, DATA_ABORT -> CpuMode.ABORT;
            case IRQ -> CpuMode.IRQ;
            case FIQ -> CpuMode.FIQ;
        };
    }

    private int exceptionVector(ArmException exception) {
        return switch (exception) {
            case RESET -> 0x00;
            case UNDEFINED -> 0x04;
            case SWI -> 0x08;
            case PREFETCH_ABORT -> 0x0C;
            case DATA_ABORT -> 0x10;
            case IRQ -> 0x18;
            case FIQ -> 0x1C;
        };
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
        };
    }

    private static void checkRegister(int index) {
        if (index < 0 || index >= REGISTER_COUNT) {
            throw new IndexOutOfBoundsException("ARM register index must be between 0 and 15: " + index);
        }
    }
}
