package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Emissão nativa de {@link IntegerOp.Alu} (todos os opcodes de `IrOpCode` menos `ORN`).
///
/// Os records e as condições em que cada um é nativo estão em {@link #register}; o laço de
/// compilação e o register cache, em {@link AsmBlockCompiler}.
final class AsmAluEmitter extends AsmEmitterBase {
    AsmAluEmitter(AsmEmitState state) {
        super(state);
    }

    static void register(AsmEmitterRegistry.Builder registry) {
        registry.add(AsmEmission.of(IntegerOp.Alu.class, AsmEmitters::alu, AsmAluEmitter::emitAlu)
                .accepting(AsmAluEmitter::accepts)
                .counting(AsmAluEmitter::countAccesses));
    }

    /// Task C2: flags lógicos com carry-out do shifter (src2 shifted-register com S) e os shifts
    /// com S são NATIVOS — helpers shiftedOperandCarry/doXxxS espelham o interpretador. Exceções:
    /// `dst=15` + `setFlags` restaura CPSR a partir do SPSR (delega ao interpretado); ORN
    /// (Thumb-2, B2.2) não tem emissão nativa.
    static boolean accepts(IntegerOp.Alu alu) {
        return (alu.dst() != PC_REGISTER || !alu.setFlags()) && alu.opcode() != IrOpCode.ORN;
    }

    static void countAccesses(IntegerOp.Alu alu, AsmAccessCounter counter) {
        if (aluUsesSrc1(alu.opcode()) && alu.src1ValueOverride() == -1) {
            counter.read(alu.src1());
        }
        counter.operand(alu.src2());
        if (aluWritesDst(alu.opcode())) {
            counter.write(alu.dst());
        }
    }

    /// Opcodes ALU que leem src1 (espelha os `emitAluXxx` que chamam `emitSrc1`).
    static boolean aluUsesSrc1(IrOpCode opcode) {
        return switch (opcode) {
            case MOV, MVN, NEG, CLZ -> false;
            default -> true;
        };
    }

    /// Opcodes ALU que escrevem dst (todos menos os de comparação/teste).
    static boolean aluWritesDst(IrOpCode opcode) {
        return switch (opcode) {
            case CMP, CMN, TST, TEQ -> false;
            default -> true;
        };
    }

    // ── ALU ────────────────────────────────────────────────────────────────────

    void emitAlu(MethodVisitor method, IntegerOp.Alu alu) {
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
            case SXTB, SXTH, UXTB, UXTH -> emitAluExtend(method, alu);
            case SXTB16, UXTB16 -> emitAluExtendByte16(method, alu);
            case REV, REV16, REVSH -> emitAluReverse(method, alu);
            case PKHBT, PKHTB -> emitAluPack(method, alu);
            default -> throw new IllegalStateException("Unexpected ALU opcode: " + alu.opcode());
        }
    }

    void emitAluMov(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    void emitAluMvn(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    void emitAluAdd(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    void emitAluAdc(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateAdcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluSub(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluSbc(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluRsb(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluRsc(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluNeg(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    void emitAluCmp(MethodVisitor method, IntegerOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateCmpFlags", "(" + CORE_REF + "II)V");
    }

    void emitAluCmn(MethodVisitor method, IntegerOp.Alu alu) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    void emitAluLogic(MethodVisitor method, IntegerOp.Alu alu, int jvmOpcode) {
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
        // O carry do shifter relê os registradores do operando: calcule-o ANTES do store em dst
        // (dst pode ser o próprio Rm/Rs do shift), como o interpretador faz.
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);  // carry
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(jvmOpcode);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    void emitAluBic(MethodVisitor method, IntegerOp.Alu alu) {
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
        // Carry do shifter ANTES do store em dst — ver emitAluLogic.
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitInsn(Opcodes.IXOR);
        method.visitInsn(Opcodes.IAND);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    void emitAluTest(MethodVisitor method, IntegerOp.Alu alu, int jvmOpcode) {
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
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    void emitAluClz(MethodVisitor method, IntegerOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        AsmBytecode.invokeStatic(method, INTEGER_CLASS, "numberOfLeadingZeros", "(I)I");
        emitStoreRegister(method, alu.dst());
    }

    void emitAluShift(MethodVisitor method, IntegerOp.Alu alu, String helperName) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // value
        emitOperand(method, alu.src2());
        AsmBytecode.visitIntConst(method, 0xFF);
        method.visitInsn(Opcodes.IAND);                      // amount = right & 0xFF
        if (!alu.setFlags()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            // stack: amount, value — but doLsl(value, amount) expects (value, amount)
            // we have [value] [amount] on stack in wrong order; swap:
            method.visitInsn(Opcodes.SWAP);
            AsmBytecode.invokeStatic(method, FLAG_HELPERS, helperName, "(II)I");
            emitStoreRegister(method, alu.dst());
            return;
        }
        // Com S (task C2): doXxxS(core, value, amount) calcula resultado + N/Z + carry do
        // shifter (V inalterado; amount 0 mantém o C atual) e devolve o resultado.
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // amount
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, helperName + "S", "(" + CORE_REF + "II)I");
        emitStoreRegister(method, alu.dst());
    }

    // ── ARMv6 (B1.2): extend/reverse ─────────────────────────────────────────────

    /// SXTB/SXTH/UXTB/UXTH: `right` já vem rotacionado pelo operando (ShiftedRegister ROR, nativo
    /// desde a task C2); soma o acumulador src1 (forma sem acumulador = src1ValueOverride 0).
    void emitAluExtend(MethodVisitor method, IntegerOp.Alu alu) {
        emitOperand(method, alu.src2());
        switch (alu.opcode()) {
            case SXTB -> method.visitInsn(Opcodes.I2B);
            case SXTH -> method.visitInsn(Opcodes.I2S);
            case UXTB -> {
                AsmBytecode.visitIntConst(method, 0xFF);
                method.visitInsn(Opcodes.IAND);
            }
            default -> { // UXTH
                AsmBytecode.visitIntConst(method, 0xFFFF);
                method.visitInsn(Opcodes.IAND);
            }
        }
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitInsn(Opcodes.IADD);
        emitStoreRegister(method, alu.dst());
    }

    /// SXTB16/UXTB16 via helper (duas lanes independentes — ver {@code AsmIntegerHelpers.extendByte16}).
    void emitAluExtendByte16(MethodVisitor method, IntegerOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        emitOperand(method, alu.src2());
        method.visitInsn(alu.opcode() == IrOpCode.SXTB16 ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "extendByte16", "(IIZ)I");
        emitStoreRegister(method, alu.dst());
    }

    /// REV/REV16/REVSH. REV usa {@code Integer.reverseBytes} direto; as outras vão por helper.
    void emitAluReverse(MethodVisitor method, IntegerOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        switch (alu.opcode()) {
            case REV -> AsmBytecode.invokeStatic(method, INTEGER_CLASS, "reverseBytes", "(I)I");
            case REV16 -> AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "reverseHalfwords", "(I)I");
            default -> AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "reverseSignedHalfword", "(I)I"); // REVSH
        }
        emitStoreRegister(method, alu.dst());
    }

    /// PKHBT/PKHTB (ARMv6): `right` já vem shiftado pelo operando; monta o resultado com um
    /// halfword de cada fonte. Nunca escreve flags.
    void emitAluPack(MethodVisitor method, IntegerOp.Alu alu) {
        boolean bt = alu.opcode() == IrOpCode.PKHBT;
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        AsmBytecode.visitIntConst(method, bt ? 0x0000_FFFF : 0xFFFF_0000);
        method.visitInsn(Opcodes.IAND);
        emitOperand(method, alu.src2());
        AsmBytecode.visitIntConst(method, bt ? 0xFFFF_0000 : 0x0000_FFFF);
        method.visitInsn(Opcodes.IAND);
        method.visitInsn(Opcodes.IOR);
        emitStoreRegister(method, alu.dst());
    }

    /// Emite bytecode que carrega o flag de carry do CPSR como int (0 ou 1) na pilha.
    void emitCpsrCarryAsInt(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsr());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsrCarry());
        // carry() devolve Z (boolean), que já é int 0/1 em bytecode — sem conversão necessária
    }

    /// Empilha o carry (0/1) que os flags LÓGICOS devem receber para o operando `src2`:
    /// imediato com carry conhecido = constante; shifted-register = carry-out do barrel shifter
    /// (task C2 — deve ser emitido ANTES da escrita em dst, pois relê os registradores do shift);
    /// registrador puro/imediato sem rotação = C atual.
    void emitLogicFlagsCarry(MethodVisitor method, IrOperand src2) {
        if (src2 instanceof IrOperand.Immediate imm && imm.carryOutKnown()) {
            method.visitInsn(imm.carryOut() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (src2 instanceof IrOperand.ShiftedRegister sr) {
            emitShiftedOperandArgs(method, sr);
            AsmBytecode.invokeStatic(method, FLAG_HELPERS, "shiftedOperandCarry", "(" + CORE_REF + "IIIZZ)Z");
        } else {
            emitCpsrCarryAsInt(method);
        }
    }
}
