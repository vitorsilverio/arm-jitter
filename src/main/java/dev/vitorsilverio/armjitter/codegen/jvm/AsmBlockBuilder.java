package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Gera bytecode JVM mínimo para blocos executáveis.
public final class AsmBlockBuilder {
    private static final String ARM_CORE = "dev/vitorsilverio/armjitter/core/ArmCore";
    private static final String EXECUTE_DESCRIPTOR = "(L" + ARM_CORE + ";)I";
    private static final String COMPILED_BLOCK = "dev/vitorsilverio/armjitter/jit/CompiledBlock";

    private AsmBlockBuilder() {
    }

    /// Gera uma classe que implementa {@link CompiledBlock} com `int execute(ArmCore)` retornando
    /// {@code 0} — instanciável e chamável por dispatch virtual direto (sem MethodHandle).
    public static byte[] buildEmptyExecuteMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                new String[]{COMPILED_BLOCK});

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "execute",
                EXECUTE_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
