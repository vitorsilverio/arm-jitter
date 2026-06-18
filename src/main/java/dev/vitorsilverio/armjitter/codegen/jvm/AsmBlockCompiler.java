package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
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
public final class AsmBlockCompiler {
    private static final String EXECUTE = "execute";
    private static final String EXECUTE_DESCRIPTOR = "(L" + GuestToHostMapper.ARM_CORE + ";)I";
    private static final String RUNTIME_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmRuntimeHelpers";
    private static final String UPDATE_CMP_FLAGS = "(L" + GuestToHostMapper.ARM_CORE + ";II)V";

    private static final int CORE_LOCAL = 0;
    private static final int CYCLES_LOCAL = 1;
    private static final int PC_CHANGED_LOCAL = 2;
    private static final int TEMP_LEFT_LOCAL = 3;
    private static final int TEMP_RIGHT_LOCAL = 4;
    private static final int TEMP_VALUE_LOCAL = 5;

    public byte[] compile(String internalName, IrBlock block) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                EXECUTE,
                EXECUTE_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);

        for (IrOp op : block.operations()) {
            switch (op) {
                case IrOp.Alu alu -> emitAlu(method, alu);
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

    private void emitAlu(MethodVisitor method, IrOp.Alu alu) {
        if (alu.opcode() == IrOpCode.MOV) {
            emitOperand(method, alu.src2());
            emitStoreRegister(method, alu.dst());
            return;
        }
        if (alu.opcode() == IrOpCode.CMP) {
            emitSrc1(method, alu.src1(), alu.src1ValueOverride());
            method.visitVarInsn(Opcodes.ISTORE, TEMP_LEFT_LOCAL);
            emitOperand(method, alu.src2());
            method.visitVarInsn(Opcodes.ISTORE, TEMP_RIGHT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP_LEFT_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP_RIGHT_LOCAL);
            AsmBytecode.invokeStatic(method, RUNTIME_HELPERS, "updateCmpFlags", UPDATE_CMP_FLAGS);
            return;
        }

        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        emitOperand(method, alu.src2());
        int mathOpcode = switch (alu.opcode()) {
            case ADD -> Opcodes.IADD;
            case SUB -> Opcodes.ISUB;
            case AND -> Opcodes.IAND;
            default -> throw new IllegalStateException("Unexpected ALU opcode: " + alu.opcode());
        };
        method.visitInsn(mathOpcode);
        emitStoreRegister(method, alu.dst());
    }

    private void emitSrc1(MethodVisitor method, int src1, int src1ValueOverride) {
        if (src1ValueOverride >= 0) {
            AsmBytecode.visitIntConst(method, src1ValueOverride);
            return;
        }
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, src1);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
    }

    private void emitOperand(MethodVisitor method, IrOperand operand) {
        switch (operand) {
            case IrOperand.Immediate immediate -> AsmBytecode.visitIntConst(method, immediate.value());
            case IrOperand.Register register -> {
                if (register.valueOverride() >= 0) {
                    AsmBytecode.visitIntConst(method, register.valueOverride());
                } else {
                    method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                    AsmBytecode.visitIntConst(method, register.index());
                    AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                }
            }
            case IrOperand.ShiftedRegister ignored ->
                    throw new IllegalStateException("Shifted register operand is not native-supported");
        }
    }

    private void emitStoreRegister(MethodVisitor method, int dst) {
        method.visitVarInsn(Opcodes.ISTORE, TEMP_VALUE_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, dst);
        method.visitVarInsn(Opcodes.ILOAD, TEMP_VALUE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        if (dst == 15) {
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        }
    }

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
