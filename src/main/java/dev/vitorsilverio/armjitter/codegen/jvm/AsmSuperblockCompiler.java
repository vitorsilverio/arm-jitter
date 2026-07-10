package dev.vitorsilverio.armjitter.codegen.jvm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Gera a classe JVM de um LOOP-SUPERBLOCO (task C0.3, spec
/// `tasks/trilha-c-perf/c0-impl-loop-superbloco.md`): um método que executa um ciclo
/// fechado de blocos já compilados como UM loop interno, eliminando o round-trip de
/// dispatch por bloco (chamada megamórfica + `core.mode()` + lookup de IC por salto).
///
/// **Composição, não re-emissão (S5 por construção):** cada membro já é uma classe
/// gerada pelo [AsmBlockCompiler] com `public static int execute0(ArmCore)`; o
/// superbloco chama os membros por `INVOKESTATIC` direto (monomórfico, inlinável pelo
/// C2). A semântica de cada segmento É o bloco compilado do membro.
///
/// Estrutura do método gerado — o análogo exato do chain loop de `JitRuntime.execute`
/// restrito aos membros do ciclo (invariantes S1–S4 da spec):
///
/// ```
/// budget = context.chainCycleBudget(); gen0 = context.generation()
/// cycles = M0.execute0(core)                       // head sempre executa (S3)
/// guard:  cycles >= budget?            -> return   // mesma ordem do while do chain loop
///         sleep/interrupt?             -> return   // AsmRuntimeHelpers.superblockKeepRunning
///         context.generation() != gen0 -> return   // SMC durante a corrente
///         core.mode()                              // re-banking por iteração (S2)
///         pc = core.programCounter()
///         pc == startPc(Mi)? -> cycles += Mi.execute0(core); goto guard
///         return cycles                            // saiu do ciclo
/// ```
///
/// A classe implementa [dev.vitorsilverio.armjitter.jit.CompiledBlock] e o marcador
/// [dev.vitorsilverio.armjitter.jit.LoopSuperblock] (o chain loop não encadeia para
/// dentro dela), e tem um campo público `context` ([SuperblockContext]) injetado pelo
/// emissor após a instanciação.
public final class AsmSuperblockCompiler {
    private static final String CORE = GuestToHostMapper.ARM_CORE;
    private static final String CORE_REF = "L" + CORE + ";";
    private static final String COMPILED_BLOCK = "dev/vitorsilverio/armjitter/jit/CompiledBlock";
    private static final String LOOP_SUPERBLOCK = "dev/vitorsilverio/armjitter/jit/LoopSuperblock";
    private static final String CONTEXT = "dev/vitorsilverio/armjitter/codegen/jvm/SuperblockContext";
    private static final String CONTEXT_REF = "L" + CONTEXT + ";";
    private static final String HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmRuntimeHelpers";
    private static final String CPU_MODE_REF = "Ldev/vitorsilverio/armjitter/core/CpuMode;";
    private static final String EXECUTE_IMPL = "execute0";
    private static final String MEMBER_DESCRIPTOR = "(" + CORE_REF + ")I";
    private static final String IMPL_DESCRIPTOR = "(" + CORE_REF + CONTEXT_REF + ")I";
    /// Nome do campo público injetado pelo emissor (ver `AsmCodeEmitter.emitLoopSuperblock`).
    public static final String CONTEXT_FIELD = "context";

    // Slots do método estático: core=0, context=1.
    private static final int CORE_LOCAL = 0;
    private static final int CONTEXT_LOCAL = 1;
    private static final int CYCLES_LOCAL = 2;
    private static final int BUDGET_LOCAL = 3;
    private static final int GENERATION_LOCAL = 4; // long: ocupa 4 e 5
    private static final int PC_LOCAL = 6;

    /// Gera o bytecode da classe. `memberInternalNames[i]` é a classe compilada do membro
    /// cujo bloco começa em `memberStartPcs[i]`; o índice 0 é o head.
    public byte[] compile(String internalName, int[] memberStartPcs, String[] memberInternalNames) {
        if (memberStartPcs.length == 0 || memberStartPcs.length != memberInternalNames.length) {
            throw new IllegalArgumentException("membros inconsistentes");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object",
                new String[]{COMPILED_BLOCK, LOOP_SUPERBLOCK});
        writer.visitField(Opcodes.ACC_PUBLIC, CONTEXT_FIELD, CONTEXT_REF, null, null).visitEnd();
        emitConstructor(writer);
        emitExecuteBridge(writer, internalName);
        emitExecuteImpl(writer, memberStartPcs, memberInternalNames);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    /// `int execute(ArmCore)` de instância: delega ao estático com o `context` do campo.
    private static void emitExecuteBridge(ClassWriter writer, String internalName) {
        MethodVisitor bridge = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "execute", MEMBER_DESCRIPTOR, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitFieldInsn(Opcodes.GETFIELD, internalName, CONTEXT_FIELD, CONTEXT_REF);
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, EXECUTE_IMPL, IMPL_DESCRIPTOR, false);
        bridge.visitInsn(Opcodes.IRETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
    }

    private static void emitExecuteImpl(ClassWriter writer, int[] startPcs, String[] members) {
        MethodVisitor m = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, EXECUTE_IMPL, IMPL_DESCRIPTOR, null, null);
        m.visitCode();

        // budget = context.chainCycleBudget(); gen0 = context.generation()
        m.visitVarInsn(Opcodes.ALOAD, CONTEXT_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT, "chainCycleBudget", "()I", true);
        m.visitVarInsn(Opcodes.ISTORE, BUDGET_LOCAL);
        m.visitVarInsn(Opcodes.ALOAD, CONTEXT_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT, "generation", "()J", true);
        m.visitVarInsn(Opcodes.LSTORE, GENERATION_LOCAL);

        // cycles = M0.execute0(core) — o head sempre executa (S3).
        m.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, members[0], EXECUTE_IMPL, MEMBER_DESCRIPTOR, false);
        m.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);

        Label guard = new Label();
        Label exit = new Label();
        Label[] memberLabels = new Label[members.length];
        for (int i = 0; i < members.length; i++) {
            memberLabels[i] = new Label();
        }

        // ── Guards de iteração, na MESMA ordem do while do chain loop (S1) ──
        m.visitLabel(guard);
        m.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        m.visitVarInsn(Opcodes.ILOAD, BUDGET_LOCAL);
        m.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
        m.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, HELPERS, "superblockKeepRunning",
                "(" + CORE_REF + ")Z", false);
        m.visitJumpInsn(Opcodes.IFEQ, exit);
        m.visitVarInsn(Opcodes.ALOAD, CONTEXT_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT, "generation", "()J", true);
        m.visitVarInsn(Opcodes.LLOAD, GENERATION_LOCAL);
        m.visitInsn(Opcodes.LCMP);
        m.visitJumpInsn(Opcodes.IFNE, exit);

        // core.mode() re-sincroniza o banking (S2); pc = core.programCounter().
        m.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CORE, "mode", "()" + CPU_MODE_REF, false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CORE, "programCounter", "()I", false);
        m.visitVarInsn(Opcodes.ISTORE, PC_LOCAL);

        // Dispatch: pc == startPc(Mi) → executa Mi; senão saiu do ciclo.
        for (int i = 0; i < members.length; i++) {
            m.visitVarInsn(Opcodes.ILOAD, PC_LOCAL);
            AsmBytecode.visitIntConst(m, startPcs[i]);
            m.visitJumpInsn(Opcodes.IF_ICMPEQ, memberLabels[i]);
        }
        m.visitJumpInsn(Opcodes.GOTO, exit);

        for (int i = 0; i < members.length; i++) {
            m.visitLabel(memberLabels[i]);
            m.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
            m.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, members[i], EXECUTE_IMPL, MEMBER_DESCRIPTOR, false);
            m.visitInsn(Opcodes.IADD);
            m.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
            m.visitJumpInsn(Opcodes.GOTO, guard);
        }

        m.visitLabel(exit);
        m.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
