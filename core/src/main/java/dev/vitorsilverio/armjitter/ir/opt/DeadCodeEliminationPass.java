package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
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

    // ── auxiliares de vivência (liveness) ──────────────────────────────────────

    private static boolean isDead(IrOp op, int liveOut) {
        return op instanceof IrOp.Alu alu
                && !alu.setFlags()
                && alu.dst() != 15
                && (liveOut & (1 << alu.dst())) == 0;
    }

    private static int regUse(IrOp op) {
        return switch (op) {
            case IrOp.Alu a -> {
                // MOV/MVN/NEG usam só src2; todos os outros também leem src1
                boolean usesSrc1 = switch (a.opcode()) {
                    case MOV, MVN, NEG -> false;
                    default -> true;
                };
                int mask = (usesSrc1 && a.src1ValueOverride() < 0) ? (1 << a.src1()) : 0;
                mask |= operandUse(a.src2());
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
                if (m.accumulate() || m.accumulateDouble()) {
                    if (m.dstHighValueOverride() < 0) mask |= (1 << m.dstHigh());
                    if (m.dstLowValueOverride() < 0) mask |= (1 << m.dstLow());
                }
                yield mask;
            }
            case IrOp.Load l -> {
                int mask = l.baseValueOverride() < 0 ? (1 << l.base()) : 0;
                mask |= operandUse(l.offset());
                yield mask;
            }
            case IrOp.Store s -> {
                int mask = s.baseValueOverride() < 0 ? (1 << s.base()) : 0;
                if (s.srcValueOverride() < 0) mask |= (1 << s.src());
                mask |= operandUse(s.offset());
                yield mask;
            }
            case IrOp.BranchExchange bx ->
                    bx.sourceValueOverride() < 0 ? (1 << bx.sourceRegister()) : 0;
            case IrOp.Saturating sat -> (1 << sat.rm()) | (1 << sat.rn());
            case IrOp.DspMultiply dsp -> (1 << dsp.rm()) | (1 << dsp.rs()) | (1 << dsp.rn());
            case IrOp.ParallelAlu p -> (1 << p.rn()) | (1 << p.rm());
            case IrOp.Sel sel -> (1 << sel.rn()) | (1 << sel.rm());
            case IrOp.Saturate sat -> operandUse(sat.operand());
            case IrOp.AbsDiffSum usad ->
                    (1 << usad.rm()) | (1 << usad.rs()) | (usad.rn() >= 0 ? (1 << usad.rn()) : 0);
            case IrOp.LoadExclusive lex -> (1 << lex.base());
            case IrOp.StoreExclusive sex -> (1 << sex.base()) | (1 << sex.src())
                    | (sex.sizeBytes() == 8 ? (1 << (sex.src() + 1)) : 0);
            case IrOp.DoubleTransfer dt -> {
                int mask = dt.baseValueOverride() < 0 ? (1 << dt.base()) : 0;
                mask |= operandUse(dt.offset());
                if (!dt.load()) mask |= (1 << dt.first()) | (1 << dt.second()); // STRD lê o par
                yield mask;
            }
            case IrOp.MultipleTransfer mt -> {
                int mask = (1 << mt.base());
                if (!mt.load()) mask |= mt.registerMask();   // store lê todos os registradores da lista
                yield mask;
            }
            case IrOp.Push p -> (1 << 13) | p.registerMask() | (p.includeLr() ? (1 << 14) : 0);
            case IrOp.Pop ignored -> (1 << 13);              // lê SP
            case IrOp.PsrTransfer t -> {
                if (t.read()) yield 0;
                int mask = 0;
                if (!t.immediateOperand() && t.registerValueOverride() < 0) mask = (1 << t.register());
                // Escrever o campo de controle (bit 0) do CPSR pode trocar o modo da CPU, o que salva
                // o banco de registradores r8-r14 atual. Trata todos os r0-r14 como vivos para que a
                // DCE não elimine escritas em registradores bancados que parecem mortas só porque o
                // registrador de mesmo índice do novo modo é escrito depois no mesmo bloco.
                if (!t.spsr() && (t.fieldMask() & 1) != 0) mask |= 0x7F00;
                yield mask;
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
            // TBB/TBH (B2.4) leem rn/rm para calcular o endereço da tabela.
            case IrOp.TableBranch tb -> {
                int mask = tb.rnValueOverride() < 0 ? (1 << tb.rn()) : 0;
                if (tb.rmValueOverride() < 0) mask |= (1 << tb.rm());
                yield mask;
            }
            // CBZ/CBNZ (B2.4) lê rn (nunca tem value override — sempre R0-R7).
            case IrOp.CompareBranchZero cbz -> (1 << cbz.rn());
            // SBFX/UBFX/RBIT/SDIV/UDIV (B3.1) leem só seus operandos-fonte, nunca o destino.
            case IrOp.BitFieldExtract b -> (1 << b.src());
            // BFI/BFC (B3.1) também lê `dst` para preservar os bits fora do campo; BFC (src=-1)
            // não lê nenhum registrador-fonte.
            case IrOp.BitFieldInsert b -> (1 << b.dst()) | (b.src() >= 0 ? (1 << b.src()) : 0);
            case IrOp.BitReverse b -> (1 << b.src());
            case IrOp.Divide d -> (1 << d.dividend()) | (1 << d.divisor());
            // VFP (B3.4): a aritmética/comparação/conversão pura (VfpAlu/VfpMoveImmediate/
            // VfpCompare/VfpConvert) só toca o banco S/D, fora deste bitmask de registradores
            // ARM — opaca por padrão (`default -> 0` já é o comportamento correto para elas).
            // As formas que TOCAM registrador ARM precisam declarar o uso explicitamente, senão
            // a DCE poderia eliminar uma escrita ALU anterior no mesmo registrador achando-a morta.
            case IrOp.VfpLoad l -> (1 << l.base());
            case IrOp.VfpStore s -> (1 << s.base());
            case IrOp.VfpMultipleTransfer mt -> (1 << mt.base());
            case IrOp.VfpCoreTransfer t -> t.toArmRegister() ? 0 : (1 << t.armRegister());
            case IrOp.VfpCorePairTransfer t ->
                    t.toArmRegisters() ? 0 : (1 << t.armLow()) | (1 << t.armHigh());
            case IrOp.VfpSystemTransfer t -> t.read() ? 0 : (1 << t.armRegister());
            // MRS/MSR SYSm do perfil M (B7.4): `MSR` lê o registrador ARM fonte; `MRS` não lê
            // registrador ARM algum (só o registrador especial, fora deste bitmask).
            case IrOp.MProfileSystemRegister t -> t.read() ? 0 : (1 << t.armRegister());
            default -> 0;
        };
    }

    /// Registradores lidos por um operando (src2 de ALU ou offset de Load/Store). Cobre
    /// {@link IrOperand.Register} e {@link IrOperand.ShiftedRegister} (índice + registrador de
    /// shift, quando aplicável). Imediatos não leem registradores.
    ///
    /// <p>Crucial para Load/Store com offset shiftado (ex.: {@code LDRSH r,[base, r12, LSL #0]}):
    /// o registrador-índice precisa ser marcado vivo, ou a DCE elimina a instrução que o define.
    private static int operandUse(IrOperand operand) {
        return switch (operand) {
            case IrOperand.Register r when r.valueOverride() < 0 -> (1 << r.index());
            case IrOperand.ShiftedRegister s -> {
                int m = s.valueOverride() < 0 ? (1 << s.index()) : 0;
                if (s.amountRegister() >= 0 && s.amountValueOverride() < 0)
                    m |= (1 << s.amountRegister());
                yield m;
            }
            default -> 0;
        };
    }

    private static int regDef(IrOp op) {
        // Uma op predicada (executada condicionalmente) NÃO é um must-def: ela pode não rodar, então
        // não pode matar a vivência de uma escrita anterior no mesmo registrador (ex.: o par clássico
        // `ADDEQ r,..` / `ADDNE r,..` if-then-else). Trata seu conjunto def como vazio para que a
        // vivência backward permaneça conservadora e a DCE não apague a escrita complementar.
        if (op.condition() != Condition.AL) {
            return 0;
        }
        return switch (op) {
            case IrOp.Alu a -> switch (a.opcode()) {
                // Ops de comparação só atualizam o CPSR — nenhum registrador de propósito geral escrito
                case CMP, CMN, TST, TEQ -> 0;
                default -> (1 << a.dst());
            };
            case IrOp.Multiply m -> (1 << m.dst());
            case IrOp.LongMultiply m -> (1 << m.dstLow()) | (1 << m.dstHigh());
            case IrOp.Saturating sat -> (1 << sat.dst());
            case IrOp.DspMultiply dsp -> (1 << dsp.dst()) | (dsp.op2() == 2 ? (1 << dsp.rn()) : 0);
            case IrOp.ParallelAlu p -> (1 << p.dst());
            case IrOp.Sel sel -> (1 << sel.dst());
            case IrOp.Saturate sat -> (1 << sat.dst());
            case IrOp.AbsDiffSum usad -> (1 << usad.dst());
            case IrOp.LoadExclusive lex -> (1 << lex.dst())
                    | (lex.sizeBytes() == 8 ? (1 << (lex.dst() + 1)) : 0);
            case IrOp.StoreExclusive sex -> (1 << sex.dst());
            case IrOp.DoubleTransfer dt -> {
                int mask = dt.load() ? (1 << dt.first()) | (1 << dt.second()) : 0;
                if (dt.writeback()) mask |= (1 << dt.base());
                yield mask;
            }
            case IrOp.Load l -> {
                int mask = (1 << l.dst());
                if (l.writeback()) mask |= (1 << l.base());
                yield mask;
            }
            case IrOp.Store s -> s.writeback() ? (1 << s.base()) : 0;
            case IrOp.LoadLiteral l -> (1 << l.dst());
            case IrOp.Branch b -> (1 << 15) | (b.link() ? (1 << 14) : 0);
            case IrOp.BranchExchange bx -> (1 << 15) | (bx.link() ? (1 << 14) : 0);
            case IrOp.ThumbBlSuffix ignored -> (1 << 15);
            case IrOp.ThumbBlPrefix ignored -> (1 << 14);
            case IrOp.MultipleTransfer mt -> {
                int mask = mt.load() ? mt.registerMask() : 0;
                if (mt.writeback()) mask |= (1 << mt.base());
                // LDM user-mode (^ sem PC) carrega no banco USER/SYS de r8-r14, não no r8-r14
                // bancado do modo atual. Exclui r8-r14 do conjunto def para que a DCE não elimine
                // escritas no r8-r14 do modo atual que precedem essa op.
                if (mt.load() && mt.userMode() && (mt.registerMask() & (1 << 15)) == 0)
                    mask &= 0xFF;
                yield mask;
            }
            case IrOp.Push ignored -> (1 << 13);
            case IrOp.Pop p -> p.registerMask() | (1 << 13) | (p.includePc() ? (1 << 15) : 0);
            case IrOp.PsrTransfer t -> t.read() ? (1 << t.register()) : 0;
            case IrOp.Coprocessor c -> c.load() && c.register() != 15 ? (1 << c.register()) : 0;
            case IrOp.Swap s -> (1 << s.dst());
            // Ops ALU puras do B3.1: definem só `dst`, nunca flags.
            case IrOp.BitFieldExtract b -> (1 << b.dst());
            case IrOp.BitFieldInsert b -> (1 << b.dst());
            case IrOp.BitReverse b -> (1 << b.dst());
            case IrOp.Divide d -> (1 << d.dst());
            // VFP (B3.4): ver o comentário equivalente em regUse — só as formas que escrevem um
            // registrador ARM (não o banco S/D, fora deste bitmask) precisam de entrada aqui.
            case IrOp.VfpMultipleTransfer mt -> mt.writeback() ? (1 << mt.base()) : 0;
            case IrOp.VfpCoreTransfer t -> t.toArmRegister() ? (1 << t.armRegister()) : 0;
            case IrOp.VfpCorePairTransfer t ->
                    t.toArmRegisters() ? (1 << t.armLow()) | (1 << t.armHigh()) : 0;
            // VMRS Rt,FPSCR escreve Rt — exceto o caso especial APSR_nzcv (Rt=15), que escreve o
            // CPSR.NZCV em vez de R15 (ver IrOp.VfpSystemTransfer).
            case IrOp.VfpSystemTransfer t -> (t.read() && t.armRegister() != 15) ? (1 << t.armRegister()) : 0;
            // MRS/MSR SYSm do perfil M (B7.4): `MRS` escreve o registrador ARM destino; `MSR` não
            // escreve registrador ARM algum (só o registrador especial, fora deste bitmask).
            case IrOp.MProfileSystemRegister t -> t.read() ? (1 << t.armRegister()) : 0;
            default -> 0;
        };
    }
}
