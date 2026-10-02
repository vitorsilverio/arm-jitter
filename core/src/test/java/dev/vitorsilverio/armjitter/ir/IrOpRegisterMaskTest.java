package dev.vitorsilverio.armjitter.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.support.IrOpSamples;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/// {@link IrOp#regUse()}/{@link IrOp#regDef()} por `record` e {@link IrOperand#regUse()} (task E15.6):
/// uma linha por caminho de cada máscara, com o valor esperado escrito à mão. O
/// `IrOpContractTest` só garante que a `DeadCodeEliminationPass` aceita toda op; aqui fica o
/// conteúdo das máscaras que ela usa.
class IrOpRegisterMaskTest {
    private static final int SP = 1 << 13;
    private static final int LR = 1 << 14;
    private static final int PC = 1 << 15;
    private static final int PC_INDEX = 15;
    private static final int ALL = 0xFFFF;
    /// Valor fixado pelo builder: o registrador não é lido.
    private static final int OVERRIDDEN = 0;
    /// Sem valor fixado: o registrador é lido.
    private static final int NO_OVERRIDE = -1;
    private static final int WORD_BYTES = 4;
    private static final int DOUBLEWORD_BYTES = 8;
    /// `op2` de `SMLALxy` em {@link IntegerOp.DspMultiply}.
    private static final int SMLAL_XY = 2;
    /// `op2` de `SMLAxy` em {@link IntegerOp.DspMultiply}.
    private static final int SMLA_XY = 0;
    /// Campo de controle (`c`) e de flags (`f`) da máscara de `MSR`.
    private static final int CONTROL_FIELD = 0b0001;
    private static final int FLAGS_FIELD = 0b1000;
    private static final int LOW_REGISTERS = 0x000F;
    /// `r8..r14`: o que `MSR CPSR_c` marca como lido (a troca de modo salva o banco atual).
    private static final int BANKED_R8_R14 = 0x7F00;
    private static final int BANKED_AND_R0 = 0x7F01;
    private static final int PC_AND_R0 = 0x8001;

    @ParameterizedTest
    @MethodSource("ops")
    void masksMatchTheRegistersTheOpTouches(IrOp op, int expectedUse, int expectedDef) {
        assertEquals(expectedUse, op.regUse(), "regUse");
        assertEquals(expectedDef, op.regDef(), "regDef");
    }

    @ParameterizedTest
    @MethodSource("operands")
    void operandUseMatchesTheRegistersItReads(IrOperand operand, int expectedUse) {
        assertEquals(expectedUse, operand.regUse());
    }

    static Stream<Arguments> operands() {
        return Stream.of(
                operand("Register lido", new IrOperand.Register(3, NO_OVERRIDE), bit(3)),
                operand("Register fixado", new IrOperand.Register(3, OVERRIDDEN), 0),
                operand("Immediate", new IrOperand.Immediate(7), 0),
                operand("ShiftedRegister por registrador", shifted(NO_OVERRIDE, 5, NO_OVERRIDE), bit(3) | bit(5)),
                operand("ShiftedRegister por imediato", shifted(NO_OVERRIDE, -1, NO_OVERRIDE), bit(3)),
                operand("ShiftedRegister com os dois fixados", shifted(OVERRIDDEN, 5, OVERRIDDEN), 0));
    }

    static Stream<Arguments> ops() {
        return Stream.of(
                // IntegerOp
                alu(IrOpCode.MOV, new IrOperand.Register(3), bit(3), bit(1)),
                alu(IrOpCode.MVN, new IrOperand.Immediate(0), 0, bit(1)),
                alu(IrOpCode.NEG, new IrOperand.Immediate(0), 0, bit(1)),
                alu(IrOpCode.ADD, new IrOperand.Immediate(0), bit(2), bit(1)),
                c("Alu ADD src1 fixado", op(IntegerOp.Alu.class, "opcode", IrOpCode.ADD, "dst", 1, "src1", 2,
                        "src1ValueOverride", OVERRIDDEN), 0, bit(1)),
                alu(IrOpCode.CMP, new IrOperand.Register(4), bit(2) | bit(4), 0),
                alu(IrOpCode.CMN, new IrOperand.Immediate(0), bit(2), 0),
                alu(IrOpCode.TST, new IrOperand.Immediate(0), bit(2), 0),
                alu(IrOpCode.TEQ, new IrOperand.Immediate(0), bit(2), 0),
                c("Multiply MLA", op(IntegerOp.Multiply.class, "dst", 1, "rm", 2, "rmValueOverride", NO_OVERRIDE,
                        "rs", 3, "rsValueOverride", NO_OVERRIDE, "rn", 4, "rnValueOverride", NO_OVERRIDE,
                        "accumulate", true), bit(2) | bit(3) | bit(4), bit(1)),
                c("Multiply MLA acumulador fixado", op(IntegerOp.Multiply.class, "dst", 1, "rm", 2,
                        "rmValueOverride", NO_OVERRIDE, "rs", 3, "rsValueOverride", NO_OVERRIDE, "rn", 4,
                        "accumulate", true), bit(2) | bit(3), bit(1)),
                c("Multiply MUL fixado", op(IntegerOp.Multiply.class, "dst", 1, "rm", 2, "rs", 3, "rn", 4),
                        0, bit(1)),
                c("LongMultiply UMLAL", op(IntegerOp.LongMultiply.class, "dstLow", 1, "dstHigh", 2, "rm", 3,
                        "rmValueOverride", NO_OVERRIDE, "rs", 4, "rsValueOverride", NO_OVERRIDE,
                        "dstHighValueOverride", NO_OVERRIDE, "dstLowValueOverride", NO_OVERRIDE, "accumulate", true),
                        bit(1) | bit(2) | bit(3) | bit(4), bit(1) | bit(2)),
                c("LongMultiply UMAAL fixado", op(IntegerOp.LongMultiply.class, "dstLow", 1, "dstHigh", 2, "rm", 3,
                        "rs", 4, "accumulateDouble", true), 0, bit(1) | bit(2)),
                c("LongMultiply UMULL", op(IntegerOp.LongMultiply.class, "dstLow", 1, "dstHigh", 2, "rm", 3,
                        "rmValueOverride", NO_OVERRIDE, "rs", 4, "rsValueOverride", NO_OVERRIDE,
                        "dstHighValueOverride", NO_OVERRIDE, "dstLowValueOverride", NO_OVERRIDE),
                        bit(3) | bit(4), bit(1) | bit(2)),
                c("Saturating", op(IntegerOp.Saturating.class, "dst", 1, "rm", 2, "rn", 3), bit(2) | bit(3), bit(1)),
                c("DspMultiply SMLALxy", op(IntegerOp.DspMultiply.class, "dst", 1, "rn", 2, "rm", 3, "rs", 4,
                        "op2", SMLAL_XY), bit(2) | bit(3) | bit(4), bit(1) | bit(2)),
                c("DspMultiply SMLAxy", op(IntegerOp.DspMultiply.class, "dst", 1, "rn", 2, "rm", 3, "rs", 4,
                        "op2", SMLA_XY), bit(2) | bit(3) | bit(4), bit(1)),
                c("DspDualMultiply longa", op(IntegerOp.DspDualMultiply.class, "dst", 1, "rm", 2, "rn", 3, "ra", 4,
                        "longForm", true), bit(2) | bit(3) | bit(4), bit(1) | bit(4)),
                c("DspDualMultiply curta", op(IntegerOp.DspDualMultiply.class, "dst", 1, "rm", 2, "rn", 3, "ra", 4),
                        bit(2) | bit(3) | bit(4), bit(1)),
                c("DspTopWordMultiply", op(IntegerOp.DspTopWordMultiply.class, "dst", 1, "rn", 2, "rm", 3, "ra", 4),
                        bit(2) | bit(3) | bit(4), bit(1)),
                c("ParallelAlu", op(IntegerOp.ParallelAlu.class, "dst", 1, "rn", 2, "rm", 3), bit(2) | bit(3), bit(1)),
                c("Sel", op(IntegerOp.Sel.class, "dst", 1, "rn", 2, "rm", 3), bit(2) | bit(3), bit(1)),
                c("Saturate", op(IntegerOp.Saturate.class, "dst", 1, "operand", new IrOperand.Register(2)),
                        bit(2), bit(1)),
                c("AbsDiffSum USADA8", op(IntegerOp.AbsDiffSum.class, "dst", 1, "rm", 2, "rs", 3, "rn", 4),
                        bit(2) | bit(3) | bit(4), bit(1)),
                c("AbsDiffSum USAD8", op(IntegerOp.AbsDiffSum.class, "dst", 1, "rm", 2, "rs", 3, "rn", -1),
                        bit(2) | bit(3), bit(1)),
                c("BitFieldExtract", op(IntegerOp.BitFieldExtract.class, "dst", 1, "src", 2), bit(2), bit(1)),
                c("BitFieldInsert BFI", op(IntegerOp.BitFieldInsert.class, "dst", 1, "src", 2), bit(1) | bit(2), bit(1)),
                c("BitFieldInsert BFC", op(IntegerOp.BitFieldInsert.class, "dst", 1, "src", -1), bit(1), bit(1)),
                c("BitReverse", op(IntegerOp.BitReverse.class, "dst", 1, "src", 2), bit(2), bit(1)),
                c("Divide", op(IntegerOp.Divide.class, "dst", 1, "dividend", 2, "divisor", 3), bit(2) | bit(3), bit(1)),
                // MemoryOp
                c("Load com writeback", op(MemoryOp.Load.class, "dst", 1, "base", 2, "baseValueOverride", NO_OVERRIDE,
                        "offset", new IrOperand.Register(3), "writeback", true), bit(2) | bit(3), bit(1) | bit(2)),
                c("Load base fixada", op(MemoryOp.Load.class, "dst", 1, "base", 2), 0, bit(1)),
                c("Store com writeback", op(MemoryOp.Store.class, "src", 1, "srcValueOverride", NO_OVERRIDE, "base", 2,
                        "baseValueOverride", NO_OVERRIDE, "offset", new IrOperand.Register(3), "writeback", true),
                        bit(1) | bit(2) | bit(3), bit(2)),
                c("Store fixado", op(MemoryOp.Store.class, "src", 1, "base", 2), 0, 0),
                c("LoadExclusive LDREXD", op(MemoryOp.LoadExclusive.class, "dst", 4, "base", 2,
                        "sizeBytes", DOUBLEWORD_BYTES), bit(2), bit(4) | bit(5)),
                c("LoadExclusive LDREX", op(MemoryOp.LoadExclusive.class, "dst", 4, "base", 2, "sizeBytes", WORD_BYTES),
                        bit(2), bit(4)),
                c("StoreExclusive STREXD", op(MemoryOp.StoreExclusive.class, "dst", 1, "src", 2, "base", 5,
                        "sizeBytes", DOUBLEWORD_BYTES), bit(2) | bit(3) | bit(5), bit(1)),
                c("StoreExclusive STREX", op(MemoryOp.StoreExclusive.class, "dst", 1, "src", 2, "base", 5,
                        "sizeBytes", WORD_BYTES), bit(2) | bit(5), bit(1)),
                c("DoubleTransfer STRD com writeback", op(MemoryOp.DoubleTransfer.class, "first", 1, "second", 2,
                        "base", 3, "baseValueOverride", NO_OVERRIDE, "offset", new IrOperand.Register(4), "writeback", true),
                        bit(1) | bit(2) | bit(3) | bit(4), bit(3)),
                c("DoubleTransfer LDRD fixado", op(MemoryOp.DoubleTransfer.class, "load", true, "first", 1,
                        "second", 2, "base", 3), 0, bit(1) | bit(2)),
                c("Swap", op(MemoryOp.Swap.class, "dst", 1, "base", 2, "baseValueOverride", NO_OVERRIDE, "src", 3,
                        "srcValueOverride", NO_OVERRIDE), bit(2) | bit(3), bit(1)),
                c("Swap fixado", op(MemoryOp.Swap.class, "dst", 1, "base", 2, "src", 3), 0, bit(1)),
                c("LoadLiteral", op(MemoryOp.LoadLiteral.class, "dst", 1), 0, bit(1)),
                c("MultipleTransfer STM com writeback", op(MemoryOp.MultipleTransfer.class, "base", 6,
                        "registerMask", LOW_REGISTERS, "writeback", true), bit(6) | LOW_REGISTERS, bit(6)),
                c("MultipleTransfer LDM^ sem PC", op(MemoryOp.MultipleTransfer.class, "load", true, "base", 6,
                        "registerMask", BANKED_AND_R0, "userMode", true), bit(6), bit(0)),
                c("MultipleTransfer LDM^ com PC", op(MemoryOp.MultipleTransfer.class, "load", true, "base", 6,
                        "registerMask", PC_AND_R0, "userMode", true), bit(6), PC_AND_R0),
                c("MultipleTransfer LDM", op(MemoryOp.MultipleTransfer.class, "load", true, "base", 6,
                        "registerMask", BANKED_AND_R0), bit(6), BANKED_AND_R0),
                c("Push com LR", op(MemoryOp.Push.class, "registerMask", LOW_REGISTERS, "includeLr", true),
                        SP | LOW_REGISTERS | LR, SP),
                c("Push", op(MemoryOp.Push.class, "registerMask", LOW_REGISTERS), SP | LOW_REGISTERS, SP),
                c("Pop com PC", op(MemoryOp.Pop.class, "registerMask", LOW_REGISTERS, "includePc", true),
                        SP, LOW_REGISTERS | SP | PC),
                c("Pop", op(MemoryOp.Pop.class, "registerMask", LOW_REGISTERS), SP, LOW_REGISTERS | SP),
                // BranchOp
                c("Branch BL", op(BranchOp.Branch.class, "link", true), 0, PC | LR),
                c("Branch B", op(BranchOp.Branch.class), 0, PC),
                c("BranchExchange BLX", op(BranchOp.BranchExchange.class, "sourceRegister", 3,
                        "sourceValueOverride", NO_OVERRIDE, "link", true), bit(3), PC | LR),
                c("BranchExchange BX fixado", op(BranchOp.BranchExchange.class, "sourceRegister", 3), 0, PC),
                c("ThumbBlPrefix", op(BranchOp.ThumbBlPrefix.class), 0, LR),
                c("ThumbBlSuffix", op(BranchOp.ThumbBlSuffix.class), LR, PC),
                c("TableBranch", op(BranchOp.TableBranch.class, "rn", 1, "rnValueOverride", NO_OVERRIDE, "rm", 2,
                        "rmValueOverride", NO_OVERRIDE), bit(1) | bit(2), 0),
                c("TableBranch fixado", op(BranchOp.TableBranch.class, "rn", 1, "rm", 2), 0, 0),
                c("CompareBranchZero", op(BranchOp.CompareBranchZero.class, "rn", 3), bit(3), 0),
                // SystemOp
                c("PsrTransfer MRS", op(SystemOp.PsrTransfer.class, "read", true, "register", 1), 0, bit(1)),
                c("PsrTransfer MSR CPSR_c", op(SystemOp.PsrTransfer.class, "register", 2,
                        "registerValueOverride", NO_OVERRIDE, "fieldMask", CONTROL_FIELD),
                        bit(2) | BANKED_R8_R14, 0),
                c("PsrTransfer MSR SPSR_c imediato", op(SystemOp.PsrTransfer.class, "spsr", true,
                        "immediateOperand", true, "fieldMask", CONTROL_FIELD), 0, 0),
                c("PsrTransfer MSR CPSR_f fixado", op(SystemOp.PsrTransfer.class, "register", 2,
                        "fieldMask", FLAGS_FIELD), 0, 0),
                c("Coprocessor MCR", op(SystemOp.Coprocessor.class, "register", 2), bit(2), 0),
                c("Coprocessor MRC", op(SystemOp.Coprocessor.class, "load", true, "register", 2), 0, bit(2)),
                c("Coprocessor MRC APSR_nzcv", op(SystemOp.Coprocessor.class, "load", true, "register", PC_INDEX), 0, 0),
                c("CoprocessorDouble MCRR", op(SystemOp.CoprocessorDouble.class, "rt", 1, "rt2", 2), bit(1) | bit(2), 0),
                c("CoprocessorDouble MRRC", op(SystemOp.CoprocessorDouble.class, "load", true, "rt", 1, "rt2", 2),
                        0, bit(1) | bit(2)),
                c("Swi", op(SystemOp.Swi.class), ALL, 0),
                c("Undefined", op(SystemOp.Undefined.class), ALL, 0),
                c("MProfileSystemRegister MSR", op(SystemOp.MProfileSystemRegister.class, "armRegister", 2), bit(2), 0),
                c("MProfileSystemRegister MRS", op(SystemOp.MProfileSystemRegister.class, "read", true,
                        "armRegister", 2), 0, bit(2)),
                // VfpOp
                c("VfpOp.Load", op(VfpOp.Load.class, "base", 2), bit(2), 0),
                c("VfpOp.Store", op(VfpOp.Store.class, "base", 2), bit(2), 0),
                c("VfpOp.MultipleTransfer com writeback", op(VfpOp.MultipleTransfer.class, "base", 2, "writeback", true),
                        bit(2), bit(2)),
                c("VfpOp.MultipleTransfer", op(VfpOp.MultipleTransfer.class, "base", 2), bit(2), 0),
                c("VfpOp.CoreTransfer para ARM", op(VfpOp.CoreTransfer.class, "toArmRegister", true, "armRegister", 2),
                        0, bit(2)),
                c("VfpOp.CoreTransfer de ARM", op(VfpOp.CoreTransfer.class, "armRegister", 2), bit(2), 0),
                c("VfpOp.CorePairTransfer para ARM", op(VfpOp.CorePairTransfer.class, "toArmRegisters", true,
                        "armLow", 1, "armHigh", 2), 0, bit(1) | bit(2)),
                c("VfpOp.CorePairTransfer de ARM", op(VfpOp.CorePairTransfer.class, "armLow", 1, "armHigh", 2),
                        bit(1) | bit(2), 0),
                c("VfpOp.SystemTransfer VMRS", op(VfpOp.SystemTransfer.class, "read", true, "armRegister", 2), 0, bit(2)),
                c("VfpOp.SystemTransfer VMRS APSR_nzcv", op(VfpOp.SystemTransfer.class, "read", true,
                        "armRegister", PC_INDEX), 0, 0),
                c("VfpOp.SystemTransfer VMSR", op(VfpOp.SystemTransfer.class, "armRegister", 2), bit(2), 0),
                c("VfpOp.SysregMemoryTransfer com writeback", op(VfpOp.SysregMemoryTransfer.class, "base", 2,
                        "writeback", true), bit(2), bit(2)),
                c("VfpOp.SysregMemoryTransfer", op(VfpOp.SysregMemoryTransfer.class, "base", 2), bit(2), 0),
                // Default da interface: op que não toca GPR.
                c("Cycle", new IrOp.Cycle(1), 0, 0));
    }

    private static Arguments alu(IrOpCode opcode, IrOperand src2, int use, int def) {
        return c("Alu " + opcode, op(IntegerOp.Alu.class, "opcode", opcode, "dst", 1, "src1", 2,
                "src1ValueOverride", NO_OVERRIDE, "src2", src2), use, def);
    }

    private static IrOperand.ShiftedRegister shifted(int valueOverride, int amountRegister, int amountValueOverride) {
        return new IrOperand.ShiftedRegister(3, ShiftType.LSL, 0, amountRegister, valueOverride, amountValueOverride,
                false, false);
    }

    private static Arguments c(String name, IrOp op, int use, int def) {
        return Arguments.of(Named.of(name, op), use, def);
    }

    private static Arguments operand(String name, IrOperand operand, int use) {
        return Arguments.of(Named.of(name, operand), use);
    }

    private static IrOp op(Class<? extends IrOp> recordClass, Object... nameValuePairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.put((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return IrOpSamples.sample(recordClass, fields);
    }

    private static int bit(int register) {
        return 1 << register;
    }
}
