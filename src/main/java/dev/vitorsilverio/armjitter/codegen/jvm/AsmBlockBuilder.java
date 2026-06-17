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

    private AsmBlockBuilder() {
    }

    /// Gera uma classe com {@code static int execute(ArmCore core)} que retorna {@code 0}.
    public static byte[] buildEmptyExecuteMethod(String internalName) {
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
                "execute",
                EXECUTE_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
