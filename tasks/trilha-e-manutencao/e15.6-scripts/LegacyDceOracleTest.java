package dev.vitorsilverio.armjitter.ir.opt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/// E15.6 — oráculo diferencial TEMPORÁRIO: a DCE de `70b049e` (copiada verbatim abaixo) contra
/// `IrOp#regUse`/`IrOp#regDef`. Fica em `src/test` só durante a task.
class LegacyDceOracleTest {
    private static final int ITERATIONS = 4000;

    @Test
    void newMasksMatchTheLegacySwitches() {
        Random random = new Random(0xE156);
        int compared = 0;
        int skipped = 0;
        for (Class<? extends IrOp> recordClass : IrOpSamples.recordsOf(IrOp.class)) {
            for (int i = 0; i < ITERATIONS; i++) {
                Map<String, Object> fields = new HashMap<>();
                for (RecordComponent c : recordClass.getRecordComponents()) {
                    Object v = randomValue(c.getType(), random);
                    if (v != null) fields.put(c.getName(), v);
                }
                IrOp op;
                try {
                    op = IrOpSamples.sample(recordClass, fields);
                } catch (IllegalStateException e) {
                    skipped++;
                    continue;
                }
                assertEquals(regUse(op), op.regUse(), () -> "regUse " + op);
                int newDef = op.condition() == Condition.AL ? op.regDef() : 0;
                assertEquals(regDef(op), newDef, () -> "regDef " + op);
                compared++;
            }
        }
        System.out.println("[e15.6-oracle] comparadas=" + compared + " puladas=" + skipped);
        assertTrue(compared > 189 * ITERATIONS / 2);
    }

    private static Object randomValue(Class<?> type, Random r) {
        if (type == int.class) return r.nextInt(18) - 1;
        if (type == long.class) return r.nextLong();
        if (type == boolean.class) return r.nextBoolean();
        if (type == IrOperand.class) return randomOperand(r);
        if (type.isEnum()) {
            Object[] values = type.getEnumConstants();
            return values[r.nextInt(values.length)];
        }
        return null;
    }

    private static IrOperand randomOperand(Random r) {
        return switch (r.nextInt(3)) {
            case 0 -> new IrOperand.Immediate(r.nextInt());
            case 1 -> new IrOperand.Register(r.nextInt(16), r.nextInt(3) - 1);
            default -> new IrOperand.ShiftedRegister(r.nextInt(16), ShiftType.values()[r.nextInt(ShiftType.values().length)],
                    r.nextInt(32), r.nextInt(17) - 1, r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextBoolean(), r.nextBoolean());
        };
    }

    private static int regUse(IrOp op) {
        return switch (op) {
            case IntegerOp.Alu a -> {
                // MOV/MVN/NEG usam só src2; todos os outros também leem src1
                boolean usesSrc1 = switch (a.opcode()) {
                    case MOV, MVN, NEG -> false;
                    default -> true;
                };
                int mask = (usesSrc1 && a.src1ValueOverride() < 0) ? (1 << a.src1()) : 0;
                mask |= operandUse(a.src2());
                yield mask;
            }
            case IntegerOp.Multiply m -> {
                int mask = m.rmValueOverride() < 0 ? (1 << m.rm()) : 0;
                if (m.rsValueOverride() < 0) mask |= (1 << m.rs());
                if (m.accumulate() && m.rnValueOverride() < 0) mask |= (1 << m.rn());
                yield mask;
            }
            case IntegerOp.LongMultiply m -> {
                int mask = m.rmValueOverride() < 0 ? (1 << m.rm()) : 0;
                if (m.rsValueOverride() < 0) mask |= (1 << m.rs());
                if (m.accumulate() || m.accumulateDouble()) {
                    if (m.dstHighValueOverride() < 0) mask |= (1 << m.dstHigh());
                    if (m.dstLowValueOverride() < 0) mask |= (1 << m.dstLow());
                }
                yield mask;
            }
            case MemoryOp.Load l -> {
                int mask = l.baseValueOverride() < 0 ? (1 << l.base()) : 0;
                mask |= operandUse(l.offset());
                yield mask;
            }
            case MemoryOp.Store s -> {
                int mask = s.baseValueOverride() < 0 ? (1 << s.base()) : 0;
                if (s.srcValueOverride() < 0) mask |= (1 << s.src());
                mask |= operandUse(s.offset());
                yield mask;
            }
            case BranchOp.BranchExchange bx ->
                    bx.sourceValueOverride() < 0 ? (1 << bx.sourceRegister()) : 0;
            case IntegerOp.Saturating sat -> (1 << sat.rm()) | (1 << sat.rn());
            case IntegerOp.DspMultiply dsp -> (1 << dsp.rm()) | (1 << dsp.rs()) | (1 << dsp.rn());
            case IntegerOp.DspDualMultiply dsp -> (1 << dsp.rm()) | (1 << dsp.rn()) | (1 << dsp.ra());
            case IntegerOp.DspTopWordMultiply dsp -> (1 << dsp.rn()) | (1 << dsp.rm()) | (1 << dsp.ra());
            case IntegerOp.ParallelAlu p -> (1 << p.rn()) | (1 << p.rm());
            case IntegerOp.Sel sel -> (1 << sel.rn()) | (1 << sel.rm());
            case IntegerOp.Saturate sat -> operandUse(sat.operand());
            case IntegerOp.AbsDiffSum usad ->
                    (1 << usad.rm()) | (1 << usad.rs()) | (usad.rn() >= 0 ? (1 << usad.rn()) : 0);
            case MemoryOp.LoadExclusive lex -> (1 << lex.base());
            case MemoryOp.StoreExclusive sex -> (1 << sex.base()) | (1 << sex.src())
                    | (sex.sizeBytes() == 8 ? (1 << (sex.src() + 1)) : 0);
            case MemoryOp.DoubleTransfer dt -> {
                int mask = dt.baseValueOverride() < 0 ? (1 << dt.base()) : 0;
                mask |= operandUse(dt.offset());
                if (!dt.load()) mask |= (1 << dt.first()) | (1 << dt.second()); // STRD lê o par
                yield mask;
            }
            case MemoryOp.MultipleTransfer mt -> {
                int mask = (1 << mt.base());
                if (!mt.load()) mask |= mt.registerMask();   // store lê todos os registradores da lista
                yield mask;
            }
            case MemoryOp.Push p -> (1 << 13) | p.registerMask() | (p.includeLr() ? (1 << 14) : 0);
            case MemoryOp.Pop ignored -> (1 << 13);              // lê SP
            case SystemOp.PsrTransfer t -> {
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
            case SystemOp.Coprocessor c -> !c.load() ? (1 << c.register()) : 0;
            // MCRR (F3): lê os dois registradores ARM (Rt/Rt2); MRRC não lê nenhum.
            case SystemOp.CoprocessorDouble c -> !c.load() ? (1 << c.rt()) | (1 << c.rt2()) : 0;
            case MemoryOp.Swap s -> {
                int mask = s.baseValueOverride() < 0 ? (1 << s.base()) : 0;
                if (s.srcValueOverride() < 0) mask |= (1 << s.src());
                yield mask;
            }
            case BranchOp.ThumbBlSuffix ignored -> (1 << 14);   // lê LR
            // SWI/Undefined disparam exceção — todos os registradores podem ser inspecionados
            case SystemOp.Swi ignored -> 0xFFFF;
            case SystemOp.Undefined ignored -> 0xFFFF;
            // TBB/TBH (B2.4) leem rn/rm para calcular o endereço da tabela.
            case BranchOp.TableBranch tb -> {
                int mask = tb.rnValueOverride() < 0 ? (1 << tb.rn()) : 0;
                if (tb.rmValueOverride() < 0) mask |= (1 << tb.rm());
                yield mask;
            }
            // CBZ/CBNZ (B2.4) lê rn (nunca tem value override — sempre R0-R7).
            case BranchOp.CompareBranchZero cbz -> (1 << cbz.rn());
            // SBFX/UBFX/RBIT/SDIV/UDIV (B3.1) leem só seus operandos-fonte, nunca o destino.
            case IntegerOp.BitFieldExtract b -> (1 << b.src());
            // BFI/BFC (B3.1) também lê `dst` para preservar os bits fora do campo; BFC (src=-1)
            // não lê nenhum registrador-fonte.
            case IntegerOp.BitFieldInsert b -> (1 << b.dst()) | (b.src() >= 0 ? (1 << b.src()) : 0);
            case IntegerOp.BitReverse b -> (1 << b.src());
            case IntegerOp.Divide d -> (1 << d.dividend()) | (1 << d.divisor());
            // VFP (B3.4): a aritmética/comparação/conversão pura (VfpOp.Alu/VfpOp.MoveImmediate/
            // VfpOp.Compare/VfpOp.Convert) só toca o banco S/D, fora deste bitmask de registradores
            // ARM — opaca por padrão (`default -> 0` já é o comportamento correto para elas).
            // As formas que TOCAM registrador ARM precisam declarar o uso explicitamente, senão
            // a DCE poderia eliminar uma escrita ALU anterior no mesmo registrador achando-a morta.
            case VfpOp.Load l -> (1 << l.base());
            case VfpOp.Store s -> (1 << s.base());
            case VfpOp.MultipleTransfer mt -> (1 << mt.base());
            case VfpOp.CoreTransfer t -> t.toArmRegister() ? 0 : (1 << t.armRegister());
            case VfpOp.CorePairTransfer t ->
                    t.toArmRegisters() ? 0 : (1 << t.armLow()) | (1 << t.armHigh());
            case VfpOp.SystemTransfer t -> t.read() ? 0 : (1 << t.armRegister());
            // MRS/MSR SYSm do perfil M (B7.4): `MSR` lê o registrador ARM fonte; `MRS` não lê
            // registrador ARM algum (só o registrador especial, fora deste bitmask).
            case SystemOp.MProfileSystemRegister t -> t.read() ? 0 : (1 << t.armRegister());
            // VLDR_sysreg/VSTR_sysreg (B15.3): `base` sempre lido para calcular o endereço,
            // independente da direção (destino/origem é FPSCR, fora deste bitmask).
            case VfpOp.SysregMemoryTransfer t -> 1 << t.base();
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
            case IntegerOp.Alu a -> switch (a.opcode()) {
                // Ops de comparação só atualizam o CPSR — nenhum registrador de propósito geral escrito
                case CMP, CMN, TST, TEQ -> 0;
                default -> (1 << a.dst());
            };
            case IntegerOp.Multiply m -> (1 << m.dst());
            case IntegerOp.LongMultiply m -> (1 << m.dstLow()) | (1 << m.dstHigh());
            case IntegerOp.Saturating sat -> (1 << sat.dst());
            case IntegerOp.DspMultiply dsp -> (1 << dsp.dst()) | (dsp.op2() == 2 ? (1 << dsp.rn()) : 0);
            case IntegerOp.DspDualMultiply dsp -> (1 << dsp.dst()) | (dsp.longForm() ? (1 << dsp.ra()) : 0);
            case IntegerOp.DspTopWordMultiply dsp -> (1 << dsp.dst());
            case IntegerOp.ParallelAlu p -> (1 << p.dst());
            case IntegerOp.Sel sel -> (1 << sel.dst());
            case IntegerOp.Saturate sat -> (1 << sat.dst());
            case IntegerOp.AbsDiffSum usad -> (1 << usad.dst());
            case MemoryOp.LoadExclusive lex -> (1 << lex.dst())
                    | (lex.sizeBytes() == 8 ? (1 << (lex.dst() + 1)) : 0);
            case MemoryOp.StoreExclusive sex -> (1 << sex.dst());
            case MemoryOp.DoubleTransfer dt -> {
                int mask = dt.load() ? (1 << dt.first()) | (1 << dt.second()) : 0;
                if (dt.writeback()) mask |= (1 << dt.base());
                yield mask;
            }
            case MemoryOp.Load l -> {
                int mask = (1 << l.dst());
                if (l.writeback()) mask |= (1 << l.base());
                yield mask;
            }
            case MemoryOp.Store s -> s.writeback() ? (1 << s.base()) : 0;
            case MemoryOp.LoadLiteral l -> (1 << l.dst());
            case BranchOp.Branch b -> (1 << 15) | (b.link() ? (1 << 14) : 0);
            case BranchOp.BranchExchange bx -> (1 << 15) | (bx.link() ? (1 << 14) : 0);
            case BranchOp.ThumbBlSuffix ignored -> (1 << 15);
            case BranchOp.ThumbBlPrefix ignored -> (1 << 14);
            case MemoryOp.MultipleTransfer mt -> {
                int mask = mt.load() ? mt.registerMask() : 0;
                if (mt.writeback()) mask |= (1 << mt.base());
                // LDM user-mode (^ sem PC) carrega no banco USER/SYS de r8-r14, não no r8-r14
                // bancado do modo atual. Exclui r8-r14 do conjunto def para que a DCE não elimine
                // escritas no r8-r14 do modo atual que precedem essa op.
                if (mt.load() && mt.userMode() && (mt.registerMask() & (1 << 15)) == 0)
                    mask &= 0xFF;
                yield mask;
            }
            case MemoryOp.Push ignored -> (1 << 13);
            case MemoryOp.Pop p -> p.registerMask() | (1 << 13) | (p.includePc() ? (1 << 15) : 0);
            case SystemOp.PsrTransfer t -> t.read() ? (1 << t.register()) : 0;
            case SystemOp.Coprocessor c -> c.load() && c.register() != 15 ? (1 << c.register()) : 0;
            // MRRC (F3): escreve os dois registradores ARM (Rt/Rt2); MCRR não escreve nenhum.
            case SystemOp.CoprocessorDouble c -> c.load() ? (1 << c.rt()) | (1 << c.rt2()) : 0;
            case MemoryOp.Swap s -> (1 << s.dst());
            // Ops ALU puras do B3.1: definem só `dst`, nunca flags.
            case IntegerOp.BitFieldExtract b -> (1 << b.dst());
            case IntegerOp.BitFieldInsert b -> (1 << b.dst());
            case IntegerOp.BitReverse b -> (1 << b.dst());
            case IntegerOp.Divide d -> (1 << d.dst());
            // VFP (B3.4): ver o comentário equivalente em regUse — só as formas que escrevem um
            // registrador ARM (não o banco S/D, fora deste bitmask) precisam de entrada aqui.
            case VfpOp.MultipleTransfer mt -> mt.writeback() ? (1 << mt.base()) : 0;
            case VfpOp.CoreTransfer t -> t.toArmRegister() ? (1 << t.armRegister()) : 0;
            case VfpOp.CorePairTransfer t ->
                    t.toArmRegisters() ? (1 << t.armLow()) | (1 << t.armHigh()) : 0;
            // VMRS Rt,FPSCR escreve Rt — exceto o caso especial APSR_nzcv (Rt=15), que escreve o
            // CPSR.NZCV em vez de R15 (ver VfpOp.SystemTransfer).
            case VfpOp.SystemTransfer t -> (t.read() && t.armRegister() != 15) ? (1 << t.armRegister()) : 0;
            // MRS/MSR SYSm do perfil M (B7.4): `MRS` escreve o registrador ARM destino; `MSR` não
            // escreve registrador ARM algum (só o registrador especial, fora deste bitmask).
            case SystemOp.MProfileSystemRegister t -> t.read() ? (1 << t.armRegister()) : 0;
            // VLDR_sysreg/VSTR_sysreg (B15.3): só escreve um GPR (`base`) com writeback; o destino
            // real do valor movido é FPSCR, fora deste bitmask.
            case VfpOp.SysregMemoryTransfer t -> t.writeback() ? (1 << t.base()) : 0;
            default -> 0;
        };
    }
}
