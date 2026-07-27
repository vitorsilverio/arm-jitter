package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Compila um {@link Ir64Block} para bytecode JVM — espelho estrutural (bem mais enxuto) de
/// {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler} (32 bits), introduzido na task
/// B6.4 (PR1).
///
/// **Decisão D-ASM (ver `b6.4-aarch64-asm-backend.md`)**: para cada operação real do bloco
/// (`Alu64`/`MoveWide`/`PcRelative`/`Branch64`/`CompareBranch64`), o bytecode gerado RECONSTRÓI o
/// record exato (campos conhecidos em tempo de compilação) e chama
/// {@link Ir64AsmRuntimeHelpers#executeOp} — o MESMO despacho usado pelo interpretador. `Cycle`
/// é somado em tempo de COMPILAÇÃO (constante — nenhuma instrução do conjunto do PR1 pula seu
/// próprio `Cycle`/`Fetch`, G4) e devolvido direto por `IRETURN`; `Fetch` emite uma chamada real
/// (o custo de acesso à memória é dinâmico). Isto NÃO inlina aritmética em locais de
/// registrador — ver a Armadilha "não confundir backend ASM funcionando com backend ASM rápido"
/// na spec: o ganho de performance de verdade fica para uma PR futura de registrador-cache.
public final class Ir64BlockCompiler {
    private static final String COMPILED_BLOCK_64 = "dev/vitorsilverio/armjitter/jit64/CompiledBlock64";
    private static final String AARCH64_CORE = Aarch64GuestToHostMapper.AARCH64_CORE;
    private static final String AARCH64_CORE_REF = "L" + AARCH64_CORE + ";";
    private static final String IR64_OP = "dev/vitorsilverio/armjitter/ir64/Ir64Op";
    private static final String IR64_OP_REF = "L" + IR64_OP + ";";
    private static final String IR64_RUNTIME_HELPERS =
            "dev/vitorsilverio/armjitter/codegen64/jvm64/Ir64AsmRuntimeHelpers";
    private static final String EXECUTE_DESCRIPTOR = "(" + AARCH64_CORE_REF + ")I";
    /// Slot local do parâmetro `core` (`0` é `this`).
    private static final int LOCAL_CORE = 1;
    /// Slot local escalar de uso temporário (resultado de `AddressSpace64#accessCycles` em
    /// {@link #emitFetch}) — reusado por instrução, nunca precisa sobreviver entre chamadas.
    private static final int LOCAL_SCRATCH_INT = 2;

    /// Compila `block` para uma classe que implementa {@link CompiledBlock64}.
    ///
    /// @param internalName nome interno (formato ASM, `a/b/C`) da classe gerada
    /// @param block bloco IR a compilar — TODAS as ops devem passar
    ///              {@link Ir64NativePolicy#supports(Ir64Block)} (não verificado aqui; é
    ///              responsabilidade do chamador, mesma disciplina do 32-bit)
    /// @return bytecode da classe gerada
    public byte[] compile(String internalName, Ir64Block block) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                new String[]{COMPILED_BLOCK_64});

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "execute", EXECUTE_DESCRIPTOR, null, null);
        method.visitCode();
        emitBody(method, block);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitBody(MethodVisitor mv, Ir64Block block) {
        int totalCycles = 0;
        long lastFetchAddress = -1L;
        int lastFetchSizeBytes = 0;
        for (Ir64Op op : block.operations()) {
            switch (op.kind()) {
                case Ir64Op.Kind.CYCLE -> totalCycles += ((Ir64Op.Cycle) op).count();
                case Ir64Op.Kind.FETCH -> {
                    Ir64Op.Fetch fetch = (Ir64Op.Fetch) op;
                    emitFetch(mv, fetch);
                    lastFetchAddress = fetch.address();
                    lastFetchSizeBytes = fetch.sizeBytes();
                }
                default -> emitOp(mv, op, lastFetchAddress + lastFetchSizeBytes);
            }
        }
        mv.visitLdcInsn(totalCycles);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /// `core.memory().accessCycles(address, sizeBytes, INSTRUCTION_FETCH)`; se `> 0`,
    /// `core.addCycles(extra)` — espelha {@code Ir64BlockExecutor#executeFetch} bit a bit.
    private void emitFetch(MethodVisitor mv, Ir64Op.Fetch fetch) {
        var memoryBinding = Aarch64GuestToHostMapper.memory();
        var accessCyclesBinding = Aarch64GuestToHostMapper.memoryAccessCycles();

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, memoryBinding.ownerInternalName(),
                memoryBinding.name(), memoryBinding.descriptor(), false);
        mv.visitLdcInsn(fetch.address());
        mv.visitLdcInsn(fetch.sizeBytes());
        mv.visitFieldInsn(Opcodes.GETSTATIC, Aarch64GuestToHostMapper.MEMORY_ACCESS_TYPE,
                "INSTRUCTION_FETCH", "L" + Aarch64GuestToHostMapper.MEMORY_ACCESS_TYPE + ";");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, accessCyclesBinding.ownerInternalName(),
                accessCyclesBinding.name(), accessCyclesBinding.descriptor(), true);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_SCRATCH_INT);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_SCRATCH_INT);
        Label skip = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, skip);
        var addCyclesBinding = Aarch64GuestToHostMapper.addCycles();
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_SCRATCH_INT);
        mv.visitInsn(Opcodes.I2L);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, addCyclesBinding.ownerInternalName(),
                addCyclesBinding.name(), addCyclesBinding.descriptor(), false);
        mv.visitLabel(skip);
    }

    /// `Ir64AsmRuntimeHelpers.executeOp(core, <op reconstruído>)`; se `false` (PC não mudou),
    /// `core.setProgramCounter(nextPc)` — `nextPc` já é uma constante de compilação (D2 da spec).
    private void emitOp(MethodVisitor mv, Ir64Op op, long nextPc) {
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        constructOp(mv, op);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IR64_RUNTIME_HELPERS, "executeOp",
                "(" + AARCH64_CORE_REF + IR64_OP_REF + ")Z", false);
        Label afterSetPc = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, afterSetPc);
        var setProgramCounterBinding = Aarch64GuestToHostMapper.setProgramCounter();
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitLdcInsn(nextPc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, setProgramCounterBinding.ownerInternalName(),
                setProgramCounterBinding.name(), setProgramCounterBinding.descriptor(), false);
        mv.visitLabel(afterSetPc);
    }

    /// Empilha uma nova instância do record `Ir64Op` concreto, campo a campo, todos constantes de
    /// compilação — cobre exatamente o conjunto suportado por {@link Ir64NativePolicy} no PR1.
    private void constructOp(MethodVisitor mv, Ir64Op op) {
        switch (op) {
            case Ir64Op.Alu64 alu -> constructAlu64(mv, alu);
            case Ir64Op.MoveWide moveWide -> constructMoveWide(mv, moveWide);
            case Ir64Op.PcRelative pcRelative -> constructPcRelative(mv, pcRelative);
            case Ir64Op.Branch64 branch -> constructBranch64(mv, branch);
            case Ir64Op.CompareBranch64 compareBranch -> constructCompareBranch64(mv, compareBranch);
            default -> throw new IllegalStateException(
                    "Ir64BlockCompiler (PR1) não suporta " + op.getClass().getSimpleName()
                            + " — verifique Ir64NativePolicy.supports antes de compilar");
        }
    }

    private static final String IR64_ALU_OP = "dev/vitorsilverio/armjitter/ir64/Ir64AluOp";
    private static final String IR64_MOVE_WIDE_OP = "dev/vitorsilverio/armjitter/ir64/Ir64MoveWideOp";
    private static final String IR64_BRANCH_FORM = "dev/vitorsilverio/armjitter/ir64/Ir64BranchForm";
    private static final String IR64_CONDITION = "dev/vitorsilverio/armjitter/ir64/Ir64Condition";
    private static final String IR64_COMPARE_BRANCH_FORM =
            "dev/vitorsilverio/armjitter/ir64/Ir64CompareBranchForm";

    private void constructAlu64(MethodVisitor mv, Ir64Op.Alu64 op) {
        String type = IR64_OP + "$Alu64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ALU_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.immediate());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.setFlags());
        emitBoolean(mv, op.dstIsStackPointer());
        emitBoolean(mv, op.src1IsStackPointer());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ALU_OP + ";IIJZZZZ)V", false);
    }

    private void constructMoveWide(MethodVisitor mv, Ir64Op.MoveWide op) {
        String type = IR64_OP + "$MoveWide";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_MOVE_WIDE_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.immediate16());
        mv.visitLdcInsn(op.shift());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_MOVE_WIDE_OP + ";IIIZ)V", false);
    }

    private void constructPcRelative(MethodVisitor mv, Ir64Op.PcRelative op) {
        String type = IR64_OP + "$PcRelative";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.instructionAddress());
        mv.visitLdcInsn(op.immediate());
        emitBoolean(mv, op.page());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IJJZ)V", false);
    }

    private void constructBranch64(MethodVisitor mv, Ir64Op.Branch64 op) {
        String type = IR64_OP + "$Branch64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_BRANCH_FORM, op.form().name());
        mv.visitLdcInsn(op.instructionAddress());
        mv.visitLdcInsn(op.target());
        mv.visitLdcInsn(op.registerOperand());
        emitBoolean(mv, op.link());
        emitEnumConstant(mv, IR64_CONDITION, op.condition().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_BRANCH_FORM + ";JJIZL" + IR64_CONDITION + ";)V", false);
    }

    private void constructCompareBranch64(MethodVisitor mv, Ir64Op.CompareBranch64 op) {
        String type = IR64_OP + "$CompareBranch64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_COMPARE_BRANCH_FORM, op.form().name());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        mv.visitLdcInsn(op.bitPosition());
        emitBoolean(mv, op.branchIfNonZero());
        mv.visitLdcInsn(op.target());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_COMPARE_BRANCH_FORM + ";IZIZJ)V", false);
    }

    private void emitEnumConstant(MethodVisitor mv, String enumInternalName, String constantName) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, enumInternalName, constantName, "L" + enumInternalName + ";");
    }

    private void emitBoolean(MethodVisitor mv, boolean value) {
        mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
    }
}
