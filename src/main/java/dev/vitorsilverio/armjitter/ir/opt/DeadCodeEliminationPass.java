package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.ArrayList;
import java.util.List;

/// Remove operações {@link IrOp.Alu} cujos resultados nunca são lidos.
///
/// Usa análise de vivência de registradores para trás (backward liveness).
/// Somente {@code IrOp.Alu} com {@code setFlags=false} e {@code dst≠15} são candidatas;
/// todos os outros ops são preservados por terem efeitos colaterais (memória, CPSR, PC, exceções).
///
/// <p>Fórmula: {@code live_in(i) = use(i) | (live_out(i) & ~def(i))}
/// <br>Saída do bloco: todos os registradores são considerados vivos (conservador).
public final class DeadCodeEliminationPass implements IrOptimizer {

    @Override
    public IrBlock optimize(IrBlock block) {
        List<IrOp> ops = block.operations();
        int n = ops.size();

        // live[i] = bitmask de registradores vivos ANTES de ops[i]; live[n] = saída do bloco
        int[] live = new int[n + 1];
        live[n] = 0xFFFF;  // conservador: todos vivos ao sair do bloco

        for (int i = n - 1; i >= 0; i--) {
            // live_in(i) = use(i) | (live_out(i) & ~def(i))
            live[i] = regUse(ops.get(i)) | (live[i + 1] & ~regDef(ops.get(i)));
        }

        List<IrOp> result = new ArrayList<>(n);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (isDead(ops.get(i), live[i + 1])) {
                changed = true;
            } else {
                result.add(ops.get(i));
            }
        }
        return changed ? new IrBlock(block.startPc(), block.endPc(), result) : block;
    }

    // ── liveness helpers ───────────────────────────────────────────────────────

    private static boolean isDead(IrOp op, int liveOut) {
        return op instanceof IrOp.Alu alu
                && !alu.setFlags()
                && alu.dst() != 15
                && (liveOut & (1 << alu.dst())) == 0;
    }

    private static int regUse(IrOp op) {
        return switch (op) {
            case IrOp.Alu a -> {
                // MOV/MVN/NEG use only src2; all others also read src1
                boolean usesSrc1 = switch (a.opcode()) {
                    case MOV, MVN, NEG -> false;
                    default -> true;
                };
                int mask = (usesSrc1 && a.src1ValueOverride() < 0) ? (1 << a.src1()) : 0;
                mask |= switch (a.src2()) {
                    case IrOperand.Register r when r.valueOverride() < 0 -> (1 << r.index());
                    case IrOperand.ShiftedRegister s -> {
                        int m = s.valueOverride() < 0 ? (1 << s.index()) : 0;
                        if (s.amountRegister() >= 0 && s.amountValueOverride() < 0)
                            m |= (1 << s.amountRegister());
                        yield m;
                    }
                    default -> 0;
                };
                yield mask;
            }
            case IrOp.Multiply m -> {
                int mask = m.rmValueOverride() < 0 ? (1 << m.rm()) : 0;
                if (m.rsValueOverride() < 0) mask |= (1 << m.rs());
                if (m.accumulate() && m.rnValueOverride() < 0) mask |= (1 << m.rn());
                yield mask;
            }
            case IrOp.LongMultiply m -> {
                int mask = m.rmValueOverride() < 0 ? (1 << m.rm()) : 0;
                if (m.rsValueOverride() < 0) mask |= (1 << m.rs());
                if (m.accumulate()) {
                    if (m.dstHighValueOverride() < 0) mask |= (1 << m.dstHigh());
                    if (m.dstLowValueOverride() < 0) mask |= (1 << m.dstLow());
                }
                yield mask;
            }
            case IrOp.Load l -> {
                int mask = l.baseValueOverride() < 0 ? (1 << l.base()) : 0;
                if (l.offset() instanceof IrOperand.Register r && r.valueOverride() < 0)
                    mask |= (1 << r.index());
                yield mask;
            }
            case IrOp.Store s -> {
                int mask = s.baseValueOverride() < 0 ? (1 << s.base()) : 0;
                if (s.srcValueOverride() < 0) mask |= (1 << s.src());
                if (s.offset() instanceof IrOperand.Register r && r.valueOverride() < 0)
                    mask |= (1 << r.index());
                yield mask;
            }
            case IrOp.BranchExchange bx ->
                    bx.sourceValueOverride() < 0 ? (1 << bx.sourceRegister()) : 0;
            case IrOp.MultipleTransfer mt -> {
                int mask = (1 << mt.base());
                if (!mt.load()) mask |= mt.registerMask();   // store lê todos os registradores da lista
                yield mask;
            }
            case IrOp.Push p -> (1 << 13) | p.registerMask() | (p.includeLr() ? (1 << 14) : 0);
            case IrOp.Pop ignored -> (1 << 13);              // lê SP
            case IrOp.PsrTransfer t -> {
                if (t.read() || t.immediateOperand() || t.registerValueOverride() >= 0) yield 0;
                yield (1 << t.register());
            }
            case IrOp.Coprocessor c -> !c.load() ? (1 << c.register()) : 0;
            case IrOp.Swap s -> {
                int mask = s.baseValueOverride() < 0 ? (1 << s.base()) : 0;
                if (s.srcValueOverride() < 0) mask |= (1 << s.src());
                yield mask;
            }
            case IrOp.ThumbBlSuffix ignored -> (1 << 14);   // lê LR
            // SWI/Undefined disparam exceção — todos os registradores podem ser inspecionados
            case IrOp.Swi ignored -> 0xFFFF;
            case IrOp.Undefined ignored -> 0xFFFF;
            default -> 0;
        };
    }

    private static int regDef(IrOp op) {
        return switch (op) {
            case IrOp.Alu a -> (1 << a.dst());
            case IrOp.Multiply m -> (1 << m.dst());
            case IrOp.LongMultiply m -> (1 << m.dstLow()) | (1 << m.dstHigh());
            case IrOp.Load l -> {
                int mask = (1 << l.dst());
                if (l.writeback()) mask |= (1 << l.base());
                yield mask;
            }
            case IrOp.Store s -> s.writeback() ? (1 << s.base()) : 0;
            case IrOp.LoadLiteral l -> (1 << l.dst());
            case IrOp.Branch b -> (1 << 15) | (b.link() ? (1 << 14) : 0);
            case IrOp.BranchExchange ignored -> (1 << 15);
            case IrOp.ThumbBlSuffix ignored -> (1 << 15);
            case IrOp.ThumbBlPrefix ignored -> (1 << 14);
            case IrOp.MultipleTransfer mt -> {
                int mask = mt.load() ? mt.registerMask() : 0;
                if (mt.writeback()) mask |= (1 << mt.base());
                yield mask;
            }
            case IrOp.Push ignored -> (1 << 13);
            case IrOp.Pop p -> p.registerMask() | (1 << 13) | (p.includePc() ? (1 << 15) : 0);
            case IrOp.PsrTransfer t -> t.read() ? (1 << t.register()) : 0;
            case IrOp.Coprocessor c -> c.load() && c.register() != 15 ? (1 << c.register()) : 0;
            case IrOp.Swap s -> (1 << s.dst());
            default -> 0;
        };
    }
}
