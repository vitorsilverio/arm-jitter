package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Utilitários de emissão ASM compartilhados.
final class AsmBytecode {
    private AsmBytecode() {
    }

    static void visitIntConst(MethodVisitor method, int value) {
        switch (value) {
            case -1 -> method.visitInsn(Opcodes.ICONST_M1);
            case 0 -> method.visitInsn(Opcodes.ICONST_0);
            case 1 -> method.visitInsn(Opcodes.ICONST_1);
            case 2 -> method.visitInsn(Opcodes.ICONST_2);
            case 3 -> method.visitInsn(Opcodes.ICONST_3);
            case 4 -> method.visitInsn(Opcodes.ICONST_4);
            case 5 -> method.visitInsn(Opcodes.ICONST_5);
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    method.visitIntInsn(Opcodes.BIPUSH, value);
                } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                    method.visitIntInsn(Opcodes.SIPUSH, value);
                } else {
                    method.visitLdcInsn(value);
                }
            }
        }
    }

    static void visitMemoryAccessType(MethodVisitor method, MemoryAccessType type) {
        method.visitFieldInsn(
                Opcodes.GETSTATIC,
                GuestToHostMapper.MEMORY_ACCESS_TYPE,
                type.name(),
                "L" + GuestToHostMapper.MEMORY_ACCESS_TYPE + ";");
    }

    static void invokeVirtual(MethodVisitor method, HostMethodBinding binding) {
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                binding.ownerInternalName(),
                binding.name(),
                binding.descriptor(),
                false);
    }

    static void invokeStatic(MethodVisitor method, String owner, String name, String descriptor) {
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    }
}
