package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Lifter linear de blocos A64 — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter} (32 bits), mas mais simples porque
/// {@link Aarch64Decoder#decode} já devolve {@link Ir64Op} diretamente (sem o estágio intermediário
/// `DecodedInstruction`+`IrBuilder` do mundo 32-bit). Introduzido na task B6.4 (spec, D1).
///
/// Cada instrução contribui exatamente 3 ops ao bloco, na ordem `[Fetch, Cycle, op]` — mesma
/// disciplina G4 (`Fetch`/`Cycle` nunca ganham guard condicional) já seguida por
/// {@link dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor#step}.
public final class StandardIr64BlockLifter implements Ir64BlockLifter {
    /// Ciclos internos atribuídos a cada instrução — mesmo valor de
    /// {@link dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor}.
    private static final int CYCLES_PER_INSTRUCTION = 1;

    @Override
    public Ir64Block lift(AddressSpace64 memory, long startPc, int maxInstructions) {
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException("maxInstructions must be positive");
        }
        Aarch64Decoder decoder = new Aarch64Decoder();
        Ir64Block.Builder block = Ir64Block.builder(startPc);
        long pc = startPc;
        int instructionSizeBytes = Aarch64Decoder.instructionSizeBytes();
        for (int i = 0; i < maxInstructions; i++) {
            block.add(new Ir64Op.Fetch(pc, instructionSizeBytes));
            block.add(new Ir64Op.Cycle(CYCLES_PER_INSTRUCTION));
            Ir64Op op = decoder.decode(memory, pc);
            block.add(op);
            pc += instructionSizeBytes;
            if (isTerminal(op)) {
                break;
            }
        }
        block.endPc(pc);
        return block.sealed();
    }

    /// `Branch64`/`CompareBranch64` podem trocar o PC; `Svc` pode ter efeito colateral arbitrário
    /// via {@code Aarch64SvcHandler} — mesmo precedente de `IrOp.Swi`/`IrOp.Coprocessor` no lifter
    /// 32-bit terminarem o bloco. Nenhum outro {@link Ir64Op.Kind} troca o PC (A64 não tem "MOV
    /// PC,..." genérico — só as formas de desvio dedicadas).
    private boolean isTerminal(Ir64Op op) {
        return switch (op.kind()) {
            case Ir64Op.Kind.BRANCH64, Ir64Op.Kind.COMPARE_BRANCH64, Ir64Op.Kind.SVC -> true;
            default -> false;
        };
    }
}
