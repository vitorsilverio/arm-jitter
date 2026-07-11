package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.ArrayList;
import java.util.List;

/// Remove {@code setFlags=true} de operações ALU cujos flags nunca são lidos antes de serem
/// reescritos.
///
/// Usa análise de vivência de flags para trás (backward), <b>por flag</b> (N, Z, C, V):
/// <pre>
///   flag_live_in(i) = uses_flags(i) | (flag_live_out(i) &amp; ~defs_flags(i))
/// </pre>
/// Saída do bloco: todos os flags considerados vivos (conservador — podem ser lidos pelo
/// dispatcher ou pelo bloco seguinte).
///
/// <p><b>Por que por-flag importa.</b> Nem toda op {@code setFlags=true} escreve os quatro
/// flags. As ops aritméticas (ADD/SUB/CMP/...) escrevem NZCV; os shifts (LSL/LSR/ASR/ROR)
/// escrevem N, Z e C (carry do shifter), mas não V; as ops lógicas/mov (MOV/AND/ORR/TST/...)
/// escrevem apenas N e Z (e C só quando o operando é um {@link IrOperand.ShiftedRegister}).
/// Tratar todas como se redefinissem NZCV faria o backward "matar" indevidamente o carry de
/// um shift anterior só porque um MOV intermediário tem {@code setFlags=true} — removendo o
/// {@code setFlags} do shift e deixando o C do bloco incorreto.
public final class FlagMergePass implements IrOptimizer {

    private static final int N = 1;
    private static final int Z = 2;
    private static final int C = 4;
    private static final int V = 8;
    private static final int NZCV = N | Z | C | V;

    @Override
    public IrBlock optimize(IrBlock block) {
        List<IrOp> ops = block.operations();
        int n = ops.size();

        // flagLive[i] = máscara de flags vivos ANTES de ops[i]; flagLive[n] = saída do bloco
        int[] flagLive = new int[n + 1];
        flagLive[n] = NZCV;  // conservador: qualquer flag pode ser lido após o bloco

        for (int i = n - 1; i >= 0; i--) {
            int uses = flagUseMask(ops.get(i));
            // Liveness kill uses the MUST-DEF set: a predicated op is not a must-def, so it cannot
            // kill an earlier flag write (the `..EQ`/`..NE` complementary pair).
            int mustDef = mustDefMask(ops.get(i));
            flagLive[i] = uses | (flagLive[i + 1] & ~mustDef);
        }

        List<IrOp> result = new ArrayList<>(n);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            IrOp op = ops.get(i);
            // Só podemos remover setFlags se NENHUM flag que esta op POSSA escrever (ignorando a
            // condição: quando ela executa, escreve) estiver vivo depois.
            if (op instanceof IrOp.Alu alu && alu.setFlags()
                    && (potentialWriteMask(alu) & flagLive[i + 1]) == 0) {
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

    // ── flag liveness helpers (por-flag) ────────────────────────────────────────

    /// Máscara de flags que a op pode LER: os flags testados pela condição, mais o carry
    /// para ADC/SBC/RSC (que o consomem mesmo incondicionalmente).
    private static int flagUseMask(IrOp op) {
        int mask = conditionMask(op.condition());
        if (op instanceof IrOp.Alu alu) {
            mask |= switch (alu.opcode()) {
                case ADC, SBC, RSC -> C;
                default -> 0;
            };
        }
        return mask;
    }

    /// Flags que a op DEFINITIVAMENTE escreve (must-def), para matar vivência para trás. Uma op
    /// predicada (condição != AL) pode não executar → must-def vazio (mirrors the DCE guard).
    /// Subestimar é seguro (mantém {@code setFlags}); superestimar mataria flags vivos indevidamente.
    private static int mustDefMask(IrOp op) {
        if (op instanceof IrOp.Alu alu && alu.condition() != Condition.AL) {
            return 0;
        }
        return potentialWriteMask(op);
    }

    /// Flags que a op escreve QUANDO executa (ignorando a condição). Usado para decidir se o
    /// {@code setFlags} desta op pode ser removido: só se nenhum desses flags estiver vivo depois.
    private static int potentialWriteMask(IrOp op) {
        if (!(op instanceof IrOp.Alu alu) || !alu.setFlags()) {
            return 0;
        }
        return switch (alu.opcode()) {
            // Aritméticas: escrevem NZCV.
            case ADD, ADC, SUB, SBC, RSB, RSC, CMP, CMN, NEG -> NZCV;
            // Shifts: N, Z e C (carry do shifter); V inalterado.
            case LSL, LSR, ASR, ROR -> N | Z | C;
            // Lógicas/mov: N e Z; C só se houver carry do shifter (operando shiftado).
            case MOV, MVN, AND, EOR, ORR, ORN, BIC, TST, TEQ ->
                    N | Z | (alu.src2() instanceof IrOperand.ShiftedRegister ? C : 0);
            // CLZ e as ops ARMv6 de extensão/inversão/pack não afetam flags.
            case CLZ, SXTB, SXTH, SXTB16, UXTB, UXTH, UXTB16, REV, REV16, REVSH, PKHBT, PKHTB -> 0;
        };
    }

    /// Flags que uma {@link Condition} avalia (vazio para {@code AL}).
    private static int conditionMask(Condition condition) {
        return switch (condition) {
            case EQ, NE -> Z;
            case CS, CC -> C;
            case MI, PL -> N;
            case VS, VC -> V;
            case HI, LS -> C | Z;
            case GE, LT -> N | V;
            case GT, LE -> N | V | Z;
            case AL -> 0;
        };
    }
}
