package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Compila blocos IR suportados por {@link AsmNativePolicy} em bytecode JVM.
///
/// Convenção de locais do método gerado:
/// <pre>
///   0 = ArmCore (parâmetro)
///   1 = cycles acumulados (int)
///   2 = pc_changed flag (int, 0 ou 1)
///   3 = TEMP1 — uso geral (base, left, result, etc.)
///   4 = TEMP2 — uso geral (offset, right, carry, etc.)
///   5 = TEMP3 — uso interno de emitStoreRegister
///   6 = ADDR  — endereço calculado (load/store)
///   7+8 = LONG_RESULT — resultado de 64 bits (LongMultiply)
/// </pre>
public final class AsmBlockCompiler {
    private static final String EXECUTE = "execute";
    private static final String EXECUTE_DESCRIPTOR = "(L" + GuestToHostMapper.ARM_CORE + ";)I";
    private static final String CORE = GuestToHostMapper.ARM_CORE;
    private static final String CORE_REF = "L" + CORE + ";";
    private static final String HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmRuntimeHelpers";
    private static final String INTEGER_CLASS = "java/lang/Integer";

    // Slots
    private static final int CORE_LOCAL = 0;
    private static final int CYCLES_LOCAL = 1;
    private static final int PC_CHANGED_LOCAL = 2;
    private static final int TEMP1_LOCAL = 3;
    private static final int TEMP2_LOCAL = 4;
    private static final int TEMP3_LOCAL = 5;  // used internally by emitStoreRegister
    private static final int ADDR_LOCAL = 6;
    private static final int LONG_RESULT_LOCAL = 7;  // long occupies slots 7+8

    // Descriptors for helpers that share the same signature
    private static final String CORE_I_TO_I = "(" + CORE_REF + "I)I";
    private static final String CORE_II_TO_V = "(" + CORE_REF + "II)V";
    private static final String CORE_I_TO_V = "(" + CORE_REF + "I)V";
    private static final String CORE_IZ_TO_V = "(" + CORE_REF + "IZ)V";
    private static final String CORE_IZ_TO_Z = "(" + CORE_REF + "IZ)Z";

    public byte[] compile(String internalName, IrBlock block) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, EXECUTE, EXECUTE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);

        for (IrOp op : block.operations()) {
            switch (op) {
                case IrOp.Alu alu -> emitAlu(method, alu);
                case IrOp.Multiply mul -> emitMultiply(method, mul);
                case IrOp.LongMultiply mul -> emitLongMultiply(method, mul);
                case IrOp.Load load -> emitLoad(method, load);
                case IrOp.Store store -> emitStore(method, store);
                case IrOp.LoadLiteral lit -> emitLoadLiteral(method, lit);
                case IrOp.MultipleTransfer mt -> emitMultipleTransfer(method, mt);
                case IrOp.Branch b -> emitBranch(method, b);
                case IrOp.BranchExchange bx -> emitBranchExchange(method, bx);
                case IrOp.ThumbBlPrefix prefix -> emitThumbBlPrefix(method, prefix);
                case IrOp.ThumbBlSuffix suffix -> emitThumbBlSuffix(method, suffix);
                case IrOp.Push push -> emitPush(method, push);
                case IrOp.Pop pop -> emitPop(method, pop);
                case IrOp.PsrTransfer psr -> emitPsrTransfer(method, psr);
                case IrOp.Swi swi -> emitSwi(method, swi, block.endPc());
                case IrOp.Coprocessor cp -> emitCoprocessor(method, cp);
                case IrOp.Undefined undef -> emitUndefined(method, undef);
                case IrOp.Cycle cycle -> emitCycle(method, cycle);
                case IrOp.Fetch fetch -> emitFetch(method, fetch);
                default -> throw new IllegalStateException("Unsupported IR op in native compile: " + op);
            }
        }

        emitProgramCounterFixup(method, block.endPc());
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // ── ALU ────────────────────────────────────────────────────────────────────

    private void emitAlu(MethodVisitor method, IrOp.Alu alu) {
        switch (alu.opcode()) {
            case MOV -> emitAluMov(method, alu);
            case MVN -> emitAluMvn(method, alu);
            case ADD -> emitAluAdd(method, alu);
            case ADC -> emitAluAdc(method, alu);
            case SUB -> emitAluSub(method, alu);
            case SBC -> emitAluSbc(method, alu);
            case RSB -> emitAluRsb(method, alu);
            case RSC -> emitAluRsc(method, alu);
            case NEG -> emitAluNeg(method, alu);
            case CMP -> emitAluCmp(method, alu);
            case CMN -> emitAluCmn(method, alu);
            case AND -> emitAluLogic(method, alu, Opcodes.IAND);
            case EOR -> emitAluLogic(method, alu, Opcodes.IXOR);
            case ORR -> emitAluLogic(method, alu, Opcodes.IOR);
            case BIC -> emitAluBic(method, alu);
            case TST -> emitAluTest(method, alu, Opcodes.IAND);
            case TEQ -> emitAluTest(method, alu, Opcodes.IXOR);
            case CLZ -> emitAluClz(method, alu);
            case LSL -> emitAluShift(method, alu, "doLsl");
            case LSR -> emitAluShift(method, alu, "doLsr");
            case ASR -> emitAluShift(method, alu, "doAsr");
            case ROR -> emitAluShift(method, alu, "doRor");
            default -> throw new IllegalStateException("Unexpected ALU opcode: " + alu.opcode());
        }
    }

    private void emitAluMov(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluMvn(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitInsn(Opcodes.IXOR);   // ~value = value ^ -1
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluAdd(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);                      // result
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);     // save result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    private void emitAluAdc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        emitCpsrCarryAsInt(method);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // carryIn
        // result = left + right + carryIn
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.IADD);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);  // result (slot 7 used as int here)
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAdcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluSub(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.ISUB);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluSbc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        // borrow = carry ? 0 : 1 → 1 - carry
        method.visitInsn(Opcodes.ICONST_1);
        emitCpsrCarryAsInt(method);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // borrow
        // result = left - right - borrow
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluRsb(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // src1 (subtrahend)
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // src2 (minuend)
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitInsn(Opcodes.ISUB);                      // src2 - src1
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);    // left = src2
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);    // right = src1
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluRsc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // src1 (subtrahend)
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // src2 (minuend)
        // borrow = carry ? 0 : 1
        method.visitInsn(Opcodes.ICONST_1);
        emitCpsrCarryAsInt(method);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // borrow
        // result = src2 - src1 - borrow
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluNeg(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        if (!alu.setFlags()) {
            method.visitInsn(Opcodes.INEG);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right = src2
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);                      // result = 0 - src2
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);                  // left = 0
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);    // right
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluCmp(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateCmpFlags", "(" + CORE_REF + "II)V");
    }

    private void emitAluCmn(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result (discarded, only flags matter)
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    private void emitAluLogic(MethodVisitor method, IrOp.Alu alu, int jvmOpcode) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(jvmOpcode);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(jvmOpcode);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);  // carry
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluBic(MethodVisitor method, IrOp.Alu alu) {
        // BIC = AND NOT: dst = src1 & ~src2
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.ICONST_M1);
            method.visitInsn(Opcodes.IXOR);   // ~src2
            method.visitInsn(Opcodes.IAND);   // src1 & ~src2
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitInsn(Opcodes.IXOR);
        method.visitInsn(Opcodes.IAND);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluTest(MethodVisitor method, IrOp.Alu alu, int jvmOpcode) {
        // TST/TEQ: same as AND/EOR but no register write, always sets flags.
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(jvmOpcode);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluClz(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        AsmBytecode.invokeStatic(method, INTEGER_CLASS, "numberOfLeadingZeros", "(I)I");
        emitStoreRegister(method, alu.dst());
    }

    private void emitAluShift(MethodVisitor method, IrOp.Alu alu, String helperName) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // value
        emitOperand(method, alu.src2());
        AsmBytecode.visitIntConst(method, 0xFF);
        method.visitInsn(Opcodes.IAND);                      // amount = right & 0xFF
        // setFlags is rejected by policy for shifts, so never true here
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        // stack: amount, value — but doLsl(value, amount) expects (value, amount)
        // we have [value] [amount] on stack in wrong order; swap:
        method.visitInsn(Opcodes.SWAP);
        AsmBytecode.invokeStatic(method, HELPERS, helperName, "(II)I");
        emitStoreRegister(method, alu.dst());
    }

    // ── multiply ────────────────────────────────────────────────────────────────

    private void emitMultiply(MethodVisitor method, IrOp.Multiply mul) {
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        method.visitInsn(Opcodes.IMUL);
        if (mul.accumulate()) {
            emitSrc1(method, mul.rn(), mul.rnValueOverride());
            method.visitInsn(Opcodes.IADD);
        }
        if (!mul.setFlags()) {
            emitStoreRegister(method, mul.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // result
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, mul.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateNzFlags", CORE_I_TO_V);
    }

    private void emitLongMultiply(MethodVisitor method, IrOp.LongMultiply mul) {
        // Load rm as long
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitAsLong(method, mul.signed());
        // Load rs as long
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        emitAsLong(method, mul.signed());
        method.visitInsn(Opcodes.LMUL);

        if (mul.accumulate()) {
            // current = Integer.toUnsignedLong(dstHigh) << 32 | Integer.toUnsignedLong(dstLow)
            emitSrc1(method, mul.dstHigh(), mul.dstHighValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            AsmBytecode.visitIntConst(method, 32);
            method.visitInsn(Opcodes.LSHL);
            emitSrc1(method, mul.dstLow(), mul.dstLowValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LOR);
            method.visitInsn(Opcodes.LADD);
        }
        method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);  // slots 7+8

        if (mul.setFlags()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "updateLongNzFlags", "(" + CORE_REF + "J)V");
        }
        // store low half: (int) result
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstLow());
        // store high half: (int)(result >>> 32)
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        AsmBytecode.visitIntConst(method, 32);
        method.visitInsn(Opcodes.LUSHR);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstHigh());
    }

    // ── memory ─────────────────────────────────────────────────────────────────

    private void emitLoad(MethodVisitor method, IrOp.Load load) {
        // base value
        if (load.baseValueOverride() >= 0) {
            AsmBytecode.visitIntConst(method, load.baseValueOverride());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, load.base());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        // offset value
        emitOperand(method, load.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!load.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // read
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        String readHelper = switch (load.sizeBytes()) {
            case 1 -> "loadByte";
            case 2 -> load.signed() ? "loadHalfSigned" : "loadHalf";
            default -> "loadWord";
        };
        AsmBytecode.invokeStatic(method, HELPERS, readHelper, CORE_I_TO_I);
        // sign-extend byte if needed (loadByte returns 0–255)
        if (load.sizeBytes() == 1 && load.signed()) {
            method.visitInsn(Opcodes.I2B);
        }

        // store to dst
        if (load.dst() == 15) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadToPcArm4", CORE_I_TO_V);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, load.dst());
        }

        // writeback: base register = base + offset (only when base != dst)
        if (load.writeback() && load.base() != load.dst()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, load.base());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        }
    }

    private void emitStore(MethodVisitor method, IrOp.Store store) {
        // base value
        if (store.baseValueOverride() >= 0) {
            AsmBytecode.visitIntConst(method, store.baseValueOverride());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, store.base());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        emitOperand(method, store.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!store.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // src value
        if (store.srcValueOverride() >= 0) {
            AsmBytecode.visitIntConst(method, store.srcValueOverride());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, store.src());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);   // value

        // write
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
        String writeHelper = switch (store.sizeBytes()) {
            case 1 -> "storeByte";
            case 2 -> "storeHalf";
            default -> "storeWord";
        };
        AsmBytecode.invokeStatic(method, HELPERS, writeHelper, CORE_II_TO_V);

        // writeback
        if (store.writeback()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, store.base());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        }
    }

    private void emitLoadLiteral(MethodVisitor method, IrOp.LoadLiteral lit) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, lit.address());
        AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
        if (lit.dst() == 15) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadToPcArm4", CORE_I_TO_V);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, lit.dst());
        }
    }

    // ── LDM/STM/PUSH/POP ───────────────────────────────────────────────────────

    private void emitMultipleTransfer(MethodVisitor method, IrOp.MultipleTransfer mt) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(mt.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.base());
        AsmBytecode.visitIntConst(method, mt.registerMask());
        method.visitInsn(mt.writeback() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.userMode() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.emptyRegisterList() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.mode().ordinal());
        AsmBytecode.invokeStatic(method, HELPERS, "executeMultipleTransfer",
                "(" + CORE_REF + "ZIIZZZI)Z");
        emitConditionalSetPcChanged(method);
    }

    private void emitPush(MethodVisitor method, IrOp.Push push) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, push.registerMask());
        method.visitInsn(push.includeLr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executePush", CORE_IZ_TO_V);
    }

    private void emitPop(MethodVisitor method, IrOp.Pop pop) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, pop.registerMask());
        method.visitInsn(pop.includePc() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executePop", CORE_IZ_TO_Z);
        emitConditionalSetPcChanged(method);
    }

    // ── branches ───────────────────────────────────────────────────────────────

    private void emitBranch(MethodVisitor method, IrOp.Branch branch) {
        if (branch.link()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, 14);
            AsmBytecode.visitIntConst(method, branch.returnAddress());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        }
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, branch.target());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitBranchExchange(MethodVisitor method, IrOp.BranchExchange bx) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        if (bx.sourceValueOverride() >= 0) {
            AsmBytecode.visitIntConst(method, bx.sourceValueOverride());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, bx.sourceRegister());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
        AsmBytecode.invokeStatic(method, HELPERS, "branchExchange", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitThumbBlPrefix(MethodVisitor method, IrOp.ThumbBlPrefix prefix) {
        // LR = address + 4 + highOffset (no PC change)
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, 14);
        AsmBytecode.visitIntConst(method, prefix.address() + 4 + prefix.highOffset());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
    }

    private void emitThumbBlSuffix(MethodVisitor method, IrOp.ThumbBlSuffix suffix) {
        // oldLR = register(14)
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, 14);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // oldLR
        // LR = (address + 2) | 1
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, 14);
        AsmBytecode.visitIntConst(method, (suffix.address() + 2) | 1);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        // PC = oldLR + lowOffset
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.visitIntConst(method, suffix.lowOffset());
        method.visitInsn(Opcodes.IADD);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── PSR ────────────────────────────────────────────────────────────────────

    private void emitPsrTransfer(MethodVisitor method, IrOp.PsrTransfer psr) {
        if (psr.read()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            AsmBytecode.visitIntConst(method, psr.register());
            AsmBytecode.invokeStatic(method, HELPERS, "executePsrRead", "(" + CORE_REF + "ZI)V");
        } else {
            int value;
            boolean needRuntime = false;
            if (psr.immediateOperand()) {
                value = psr.immediate();
            } else if (psr.registerValueOverride() >= 0) {
                value = psr.registerValueOverride();
            } else {
                needRuntime = true;
                value = 0;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            if (needRuntime) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, psr.register());
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
            } else {
                AsmBytecode.visitIntConst(method, value);
            }
            AsmBytecode.visitIntConst(method, psr.fieldMask());
            AsmBytecode.invokeStatic(method, HELPERS, "executePsrWrite", "(" + CORE_REF + "ZII)V");
        }
    }

    // ── SWI / coprocessor / undefined ──────────────────────────────────────────

    private void emitSwi(MethodVisitor method, IrOp.Swi swi, int blockEndPc) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, swi.immediate());
        AsmBytecode.visitIntConst(method, blockEndPc);
        AsmBytecode.invokeStatic(method, HELPERS, "executeSwi", "(" + CORE_REF + "II)Z");
        // always returns true
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitCoprocessor(MethodVisitor method, IrOp.Coprocessor cp) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(cp.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, cp.coprocessor());
        AsmBytecode.visitIntConst(method, cp.opcode1());
        AsmBytecode.visitIntConst(method, cp.crn());
        AsmBytecode.visitIntConst(method, cp.crm());
        AsmBytecode.visitIntConst(method, cp.opcode2());
        AsmBytecode.visitIntConst(method, cp.register());
        AsmBytecode.visitIntConst(method, cp.sequentialPc());
        AsmBytecode.invokeStatic(method, HELPERS, "executeCoprocessor",
                "(" + CORE_REF + "ZIIIIIII)Z");
        emitConditionalSetPcChanged(method);
    }

    private void emitUndefined(MethodVisitor method, IrOp.Undefined undef) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, undef.sequentialPc());
        AsmBytecode.invokeStatic(method, HELPERS, "executeUndefined", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── cycle / fetch ──────────────────────────────────────────────────────────

    private void emitCycle(MethodVisitor method, IrOp.Cycle cycle) {
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        AsmBytecode.visitIntConst(method, cycle.count());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
    }

    private void emitFetch(MethodVisitor method, IrOp.Fetch fetch) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, fetch.address());
        AsmBytecode.visitIntConst(method, fetch.sizeBytes());
        AsmBytecode.visitMemoryAccessType(method, MemoryAccessType.INSTRUCTION_FETCH);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.addMemoryCycles());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void emitSrc1(MethodVisitor method, int register, int valueOverride) {
        if (valueOverride >= 0) {
            AsmBytecode.visitIntConst(method, valueOverride);
            return;
        }
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, register);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
    }

    private void emitOperand(MethodVisitor method, IrOperand operand) {
        switch (operand) {
            case IrOperand.Immediate imm -> AsmBytecode.visitIntConst(method, imm.value());
            case IrOperand.Register reg -> {
                if (reg.valueOverride() >= 0) {
                    AsmBytecode.visitIntConst(method, reg.valueOverride());
                } else {
                    method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                    AsmBytecode.visitIntConst(method, reg.index());
                    AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                }
            }
            case IrOperand.ShiftedRegister ignored ->
                    throw new IllegalStateException("ShiftedRegister operand is not native-supported");
        }
    }

    private void emitStoreRegister(MethodVisitor method, int dst) {
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, dst);
        method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        if (dst == 15) {
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        }
    }

    /// Emits bytecode that pops a boolean result and sets PC_CHANGED_LOCAL to 1 if true.
    private void emitConditionalSetPcChanged(MethodVisitor method) {
        Label skip = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, skip);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        method.visitLabel(skip);
    }

    /// Emits bytecode to load the CPSR carry flag as int (0 or 1) onto the stack.
    private void emitCpsrCarryAsInt(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsr());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsrCarry());
        // carry() returns Z (boolean), which is int 0/1 in bytecode — no conversion needed
    }

    /// Emits bytecode to push the logic carry-out for a src2 operand:
    /// - Immediate with known carry: compile-time constant
    /// - Immediate without known carry / Register: runtime cpsr().carry()
    private void emitLogicFlagsCarry(MethodVisitor method, IrOperand src2) {
        if (src2 instanceof IrOperand.Immediate imm && imm.carryOutKnown()) {
            method.visitInsn(imm.carryOut() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else {
            emitCpsrCarryAsInt(method);
        }
    }

    /// Converts the int on top of stack to a long. Uses I2L (signed) or Integer.toUnsignedLong (unsigned).
    private void emitAsLong(MethodVisitor method, boolean signed) {
        if (signed) {
            method.visitInsn(Opcodes.I2L);
        } else {
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
        }
    }

    private void emitProgramCounterFixup(MethodVisitor method, int endPc) {
        Label skip = new Label();
        method.visitVarInsn(Opcodes.ILOAD, PC_CHANGED_LOCAL);
        method.visitJumpInsn(Opcodes.IFNE, skip);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, endPc);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitLabel(skip);
    }
}
