package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Regra geral: as ops são suportadas para qualquer condição — o {@code AsmBlockCompiler} emite um
/// guard {@code evalCond} por op, espelhando o interpretador. As exceções abaixo são por motivos
/// NÃO-condicionais:
/// <ul>
///   <li>{@link IrOp.Swap} — raro, mantém fallback.</li>
///   <li>{@link IrOp.Alu} com {@code dst=15} e {@code setFlags=true} — restaura SPSR.</li>
///   <li>Ops ARMv6 da task B1.2 (extend/reverse/UMAAL) — nativas só na B1.6.</li>
///   <li>BLX ({@link IrOp.BranchExchange} com {@code link}, {@link IrOp.ThumbBlSuffix} com {@code exchange}).</li>
///   <li>Formas ARMv5TE com escrita em PC (o comum — Saturating/DspMultiply/LDRD/STRD sem PC —
///       é emitido nativamente).</li>
/// </ul>
///
/// <p>Desde a task C2, flags lógicos com carry-out do barrel shifter (MOVS/ANDS/... com operando
/// shifted-register) e os shifts com S (LSLS/...) também são emitidos nativamente.</p>
public final class AsmNativePolicy {
    /// Opcodes ARMv6 (B1.2) ainda sem emissão nativa — caem no interpretado até a task B1.6.
    private static final EnumSet<IrOpCode> ARMV6_ALU_OPCODES = EnumSet.of(
            IrOpCode.SXTB, IrOpCode.SXTH, IrOpCode.SXTB16,
            IrOpCode.UXTB, IrOpCode.UXTH, IrOpCode.UXTB16,
            IrOpCode.REV, IrOpCode.REV16, IrOpCode.REVSH);

    private AsmNativePolicy() {
    }

    public static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    public static boolean supports(IrOp op) {
        // Condição ≠ AL é suportada nativamente: o AsmBlockCompiler emite um guard `evalCond` por
        // op (espelhando o `if (!evalCond) return false;` do interpretador). As rejeições abaixo são
        // por motivos NÃO-CONDICIONAIS: BLX/interworking, Swap, formas com escrita em PC e as ops
        // ARMv6 de B1.2 (nativas só na B1.6).
        return switch (op) {
            case IrOp.Alu alu -> supportsAlu(alu);
            case IrOp.Multiply ignored -> true;
            // O acumulador duplo do UMAAL (ARMv6, B1.2) ainda não é emitido nativamente.
            case IrOp.LongMultiply m -> !m.accumulateDouble();
            // ARMv5TE emitidas nativamente (Mobiclip/SDK usam pesado). Só as formas com escrita
            // em PC (UNPREDICTABLE/troca de bloco) ficam no interpretado.
            case IrOp.Saturating s -> s.dst() != 15;
            case IrOp.DspMultiply d -> d.dst() != 15 && !(d.op2() == 2 && d.rn() == 15);
            // Aritmética paralela ARMv6 (B1.3) ainda não é emitida nativamente (B1.6).
            case IrOp.ParallelAlu ignored -> false;
            case IrOp.DoubleTransfer d -> d.first() + 1 <= (d.load() ? 14 : 15);
            case IrOp.Load ignored -> true;   // offsets shifted-register agora emitidos nativamente
            case IrOp.Store ignored -> true;
            case IrOp.LoadLiteral ignored -> true;
            case IrOp.MultipleTransfer ignored -> true;
            case IrOp.Branch ignored -> true;
            case IrOp.BranchExchange b -> !b.link(); // BLX -> interpret
            case IrOp.ThumbBlPrefix ignored -> true;
            case IrOp.ThumbBlSuffix s -> !s.exchange(); // BLX -> interpret
            case IrOp.Push ignored -> true;
            case IrOp.Pop ignored -> true;
            case IrOp.PsrTransfer ignored -> true;
            case IrOp.Swi ignored -> true;
            case IrOp.Coprocessor ignored -> true;
            case IrOp.Undefined ignored -> true;
            case IrOp.Swap ignored -> false;
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
        };
    }

    private static boolean supportsAlu(IrOp.Alu alu) {
        // Ops ARMv6 (extend/reverse) ficam no interpretado até B1.6.
        if (ARMV6_ALU_OPCODES.contains(alu.opcode())) return false;
        // Task C2: flags lógicos com carry-out do shifter (src2 shifted-register com S) e os
        // shifts com S agora são NATIVOS — helpers shiftedOperandCarry/doXxxS espelham o
        // interpretador. Única exceção restante:
        // dst=15 + setFlags: restores CPSR from SPSR, defer to interpreted.
        if (alu.dst() == 15 && alu.setFlags()) return false;
        return true;
    }

    /// Opcodes ALU actualmente emitidos nativamente (todos exceto os ARMv6 pendentes de B1.6 e
    /// os filtrados acima).
    public static Set<IrOpCode> supportedAluOpcodes() {
        return EnumSet.complementOf(ARMV6_ALU_OPCODES);
    }
}
