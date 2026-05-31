package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.util.Arrays;
import java.util.Objects;

/// Estado basico de uma CPU ARM usado pelo interpretador e pelo JIT.
public final class ArmCore {
    private static final int REGISTER_COUNT = 16;
    private static final int PC = 15;

    private final int[] registers = new int[REGISTER_COUNT];
    private final CpsrRegister cpsr = new CpsrRegister();
    private final AddressSpace memory;
    private final SwiDispatcher swiDispatcher;
    private final ArmInterpreter interpreter;
    private long cycles;
    private boolean interruptLine;

    /// Cria um core conectado a uma memoria e a um dispatcher de SWI.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher) {
        this(memory, swiDispatcher, new ArmInterpreter());
    }

    /// Cria um core conectado a uma memoria, SWI e interpretador customizado.
    public ArmCore(AddressSpace memory, SwiDispatcher swiDispatcher, ArmInterpreter interpreter) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.swiDispatcher = Objects.requireNonNull(swiDispatcher, "swiDispatcher");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
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

    /// Atualiza a linha externa de interrupcao.
    public void setInterruptLine(boolean asserted) {
        interruptLine = asserted;
    }

    /// Retorna `true` quando a linha externa de interrupcao esta ativa.
    public boolean interruptLine() {
        return interruptLine;
    }

    /// Executa uma unica instrucao quando um interpretador estiver conectado.
    public DecodedInstruction step() {
        return interpreter.step(this);
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
        return runtime.execute(programCounter(), this);
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
        throw new UnsupportedOperationException("Exception handling is not implemented yet: " + exception);
    }

    /// Exporta um snapshot imutavel usado por handlers de SWI.
    public CpuState toCpuState() {
        return new CpuState(registers[0], registers[1], registers[2], registers[3],
                registers[13], registers[14], registers[15], cpsr.get());
    }

    /// Aplica um snapshot retornado por handler de SWI.
    public void apply(CpuState state) {
        registers[0] = state.r0();
        registers[1] = state.r1();
        registers[2] = state.r2();
        registers[3] = state.r3();
        registers[13] = state.sp();
        registers[14] = state.lr();
        registers[15] = state.pc();
        cpsr.set(state.cpsr());
    }

    private static void checkRegister(int index) {
        if (index < 0 || index >= REGISTER_COUNT) {
            throw new IndexOutOfBoundsException("ARM register index must be between 0 and 15: " + index);
        }
    }
}
