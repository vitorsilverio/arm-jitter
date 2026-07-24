package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Interpretador mínimo para AArch64 — fatia B6.1: SEM cache de blocos, SEM JIT, um `step()`/
/// `run()` direto sobre {@link Aarch64Core}. O pipeline tiered/compilado chega em B6.4 (ver
/// `tasks/trilha-b-arquiteturas/b6-aarch64.md`); esta classe é o oráculo de referência que aquele
/// pipeline futuro terá que igualar (mesmo papel de
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} no mundo de 32 bits — G1).
///
/// Cada {@link #step} decodifica exatamente UMA instrução de 4 bytes no PC atual, contabiliza
/// {@link Ir64Op.Fetch}/{@link Ir64Op.Cycle} (G4: incondicionais, sempre executados) e executa a
/// semântica decodificada.
public final class Ir64BlockExecutor {
    /// Ciclos internos atribuídos a cada instrução nesta fatia (sem custo de memória/pipeline
    /// modelado ainda — ver B6.4).
    private static final int CYCLES_PER_INSTRUCTION = 1;
    /// Deslocamento em bytes do registrador X30 (link register) usado por `BL`/`BLR`.
    private static final int LINK_REGISTER = 30;

    private final Aarch64Decoder decoder = new Aarch64Decoder();

    /// Executa uma única instrução no PC atual do core e avança o PC (a menos que a própria
    /// instrução já tenha alterado o PC — um desvio tomado).
    ///
    /// @param core core a executar
    /// @return ciclos internos consumidos (mesma convenção de
    ///         {@link dev.vitorsilverio.armjitter.core.ArmCore#stepReturningInternalCycles})
    public int step(Aarch64Core core) {
        long pc = core.pc();
        // G4: Fetch/Cycle nunca ganham guard condicional — são contabilizados incondicionalmente
        // antes de decodificar a semântica da instrução.
        Ir64Op.Fetch fetch = new Ir64Op.Fetch(pc, Aarch64Decoder.instructionSizeBytes());
        executeFetch(core, fetch);
        Ir64Op.Cycle cycle = new Ir64Op.Cycle(CYCLES_PER_INSTRUCTION);
        int cycles = executeCycle(cycle);
        core.addCycles(cycles);

        Ir64Op op = decoder.decode(core.memory(), pc);
        boolean pcChanged = execute(core, op);
        if (!pcChanged) {
            core.setProgramCounter(pc + Aarch64Decoder.instructionSizeBytes());
        }
        return cycles;
    }

    /// Executa `instructionCount` instruções em sequência a partir do PC atual.
    ///
    /// @param core core a executar
    /// @param instructionCount quantidade de instruções a executar
    /// @return total de ciclos internos consumidos
    public long run(Aarch64Core core, int instructionCount) {
        if (instructionCount < 0) {
            throw new IllegalArgumentException("instructionCount must be >= 0");
        }
        long total = 0;
        for (int i = 0; i < instructionCount; i++) {
            total += step(core);
        }
        return total;
    }

    private boolean execute(Aarch64Core core, Ir64Op op) {
        return switch (op.kind()) {
            case Ir64Op.Kind.ALU64 -> executeAlu(core, (Ir64Op.Alu64) op);
            case Ir64Op.Kind.MOVE_WIDE -> executeMoveWide(core, (Ir64Op.MoveWide) op);
            case Ir64Op.Kind.PC_RELATIVE -> executePcRelative(core, (Ir64Op.PcRelative) op);
            case Ir64Op.Kind.BRANCH64 -> executeBranch(core, (Ir64Op.Branch64) op);
            case Ir64Op.Kind.COMPARE_BRANCH64 -> executeCompareBranch(core, (Ir64Op.CompareBranch64) op);
            case Ir64Op.Kind.SVC -> executeSvc(core, (Ir64Op.Svc) op);
            case Ir64Op.Kind.CYCLE, Ir64Op.Kind.FETCH ->
                    throw new IllegalStateException("Cycle/Fetch não são decodificados como instrução");
            default -> throw new IllegalStateException("Ir64Op.kind desconhecido: " + op.kind());
        };
    }

    private boolean executeAlu(Aarch64Core core, Ir64Op.Alu64 op) {
        long operand1 = op.src1IsStackPointer() ? core.sp() : core.xForWidth(op.src1(), op.wide());
        long operand2 = op.immediate();
        AluResult result = switch (op.opcode()) {
            case ADD -> addWithFlags(operand1, operand2, op.wide());
            case SUB -> subWithFlags(operand1, operand2, op.wide());
            case AND -> logicalWithFlags(operand1 & operand2, op.wide());
            case ORR -> logicalWithFlags(operand1 | operand2, op.wide());
            case EOR -> logicalWithFlags(operand1 ^ operand2, op.wide());
        };
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        if (op.dstIsStackPointer()) {
            core.setSp(op.wide() ? result.value : (result.value & 0xFFFF_FFFFL));
        } else {
            core.setXForWidth(op.dst(), result.value, op.wide());
        }
        return false;
    }

    private boolean executeMoveWide(Aarch64Core core, Ir64Op.MoveWide op) {
        long shiftedImmediate = ((long) op.immediate16() & 0xFFFFL) << op.shift();
        long result = switch (op.opcode()) {
            case MOVZ -> shiftedImmediate;
            case MOVN -> ~shiftedImmediate;
            case MOVK -> {
                long mask = 0xFFFFL << op.shift();
                long previous = core.xForWidth(op.dst(), op.wide());
                yield (previous & ~mask) | (shiftedImmediate & mask);
            }
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    private boolean executePcRelative(Aarch64Core core, Ir64Op.PcRelative op) {
        long base = op.page() ? (op.instructionAddress() & ~0xFFFL) : op.instructionAddress();
        core.setX(op.dst(), base + op.immediate());
        return false;
    }

    private boolean executeBranch(Aarch64Core core, Ir64Op.Branch64 op) {
        if (!core.pstate().evalCond(op.condition())) {
            return false;
        }
        long target = switch (op.form()) {
            case IMMEDIATE -> op.target();
            case REGISTER -> core.x(op.registerOperand());
        };
        if (op.link()) {
            core.setX(LINK_REGISTER, op.instructionAddress() + Aarch64Decoder.instructionSizeBytes());
        }
        core.setProgramCounter(target);
        return true;
    }

    private boolean executeCompareBranch(Aarch64Core core, Ir64Op.CompareBranch64 op) {
        boolean conditionMet = switch (op.form()) {
            case CBZ_CBNZ -> {
                long value = core.xForWidth(op.rn(), op.wide());
                yield op.branchIfNonZero() ? value != 0 : value == 0;
            }
            case TBZ_TBNZ -> {
                long bit = (core.x(op.rn()) >>> op.bitPosition()) & 1L;
                yield op.branchIfNonZero() ? bit != 0 : bit == 0;
            }
        };
        if (!conditionMet) {
            return false;
        }
        core.setProgramCounter(op.target());
        return true;
    }

    private boolean executeSvc(Aarch64Core core, Ir64Op.Svc op) {
        core.svcHandler().handle(core, op.immediate());
        return false;
    }

    private void executeFetch(Aarch64Core core, Ir64Op.Fetch op) {
        int extra = core.memory().accessCycles(op.address(), op.sizeBytes(),
                MemoryAccessType.INSTRUCTION_FETCH);
        if (extra > 0) {
            core.addCycles(extra);
        }
    }

    private int executeCycle(Ir64Op.Cycle op) {
        return op.count();
    }

    private static AluResult addWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a + b;
            boolean carry = Long.compareUnsigned(result, a) < 0;
            boolean overflow = (((a ^ result) & (b ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai + bi;
        boolean carry = Integer.compareUnsigned(resulti, ai) < 0;
        boolean overflow = (((ai ^ resulti) & (bi ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    private static AluResult subWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a - b;
            boolean carry = Long.compareUnsigned(a, b) >= 0;
            boolean overflow = (((a ^ b) & (a ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai - bi;
        boolean carry = Integer.compareUnsigned(ai, bi) >= 0;
        boolean overflow = (((ai ^ bi) & (ai ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    /// `AND`/`ORR`/`EOR` (imediato) NUNCA atualizam C/V (`ARM DDI 0487 C6.2.9`, `ANDS`
    /// imediato): diferente do barrel shifter clássico de 32 bits, que podia produzir carry a
    /// partir do próprio shift do imediato — A64 não tem esse mecanismo para a forma imediata.
    private static AluResult logicalWithFlags(long result, boolean wide) {
        long masked = wide ? result : (result & 0xFFFF_FFFFL);
        boolean negative = wide ? masked < 0 : (masked & 0x8000_0000L) != 0;
        return new AluResult(masked, negative, masked == 0, false, false);
    }

    private record AluResult(long value, boolean negative, boolean zero, boolean carry, boolean overflow) {
    }
}
