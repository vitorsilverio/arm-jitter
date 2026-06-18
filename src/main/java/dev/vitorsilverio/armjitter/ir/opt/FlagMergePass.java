package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;

import java.util.ArrayList;
import java.util.List;

/// Remove {@code setFlags=true} de operações ALU cujos flags NZCV nunca são lidos antes
/// da próxima escrita de flags.
///
/// Usa análise de vivência de flags para trás (backward):
/// <pre>
///   flag_live_in(i) = uses_flags(i) | (flag_live_out(i) &amp; !defs_flags(i))
/// </pre>
/// Saída do bloco: flags considerados vivos (conservador — podem ser lidos pelo dispatcher).
///
/// <p>Leitores de flags: {@code ADC}, {@code SBC}, {@code RSC} (leem o carry).
/// <br>Escritores de flags: qualquer {@link IrOp.Alu} com {@code setFlags=true}.
public final class FlagMergePass implements IrOptimizer {

    @Override
    public IrBlock optimize(IrBlock block) {
        List<IrOp> ops = block.operations();
        int n = ops.size();

        // flagLive[i] = flags vivos ANTES de ops[i]; flagLive[n] = saída do bloco
        boolean[] flagLive = new boolean[n + 1];
        flagLive[n] = true;  // conservador: flags podem ser lidos após o bloco

        for (int i = n - 1; i >= 0; i--) {
            boolean uses = flagUse(ops.get(i));
            boolean defs = flagDef(ops.get(i));
            // live_in = use | (live_out & !def)
            flagLive[i] = uses || (flagLive[i + 1] && !defs);
        }

        List<IrOp> result = new ArrayList<>(n);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            IrOp op = ops.get(i);
            if (op instanceof IrOp.Alu alu && alu.setFlags() && !flagLive[i + 1]) {
                // flags escritos aqui nunca são lidos → remover setFlags
                result.add(new IrOp.Alu(
                        alu.opcode(), alu.dst(), alu.src1(), alu.src1ValueOverride(),
                        alu.src2(), false, alu.condition()));
                changed = true;
            } else {
                result.add(op);
            }
        }
        return changed ? new IrBlock(block.startPc(), block.endPc(), result) : block;
    }

    // ── flag liveness helpers ──────────────────────────────────────────────────

    /// Retorna {@code true} quando a op lê CPSR (condição != AL ou carry em ADC/SBC/RSC).
    private static boolean flagUse(IrOp op) {
        // Any conditional instruction evaluates CPSR to decide whether to execute
        if (op.condition() != Condition.AL) return true;
        // ADC/SBC/RSC also consume the carry flag even when unconditional
        return op instanceof IrOp.Alu alu && switch (alu.opcode()) {
            case ADC, SBC, RSC -> true;
            default -> false;
        };
    }

    /// Retorna {@code true} quando a op escreve NZCV.
    private static boolean flagDef(IrOp op) {
        return op instanceof IrOp.Alu alu && alu.setFlags();
    }
}
