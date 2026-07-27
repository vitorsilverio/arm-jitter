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
/// (`Alu64`/`MoveWide`/`PcRelative`/`Branch64`/`CompareBranch64` do PR1; `Load64`/`Store64`/
/// `LoadStorePair`/`LoadLiteral64`/`Svc` do PR2; `AluShiftedRegister`/`AluExtendedRegister`/
/// `ConditionalSelect`/`Bitfield`/`MultiplyAccumulate`/`Divide`/`LoadExclusive`/`StoreExclusive`
/// do PR3, fechando `Ir64Op.Kind` por completo), o bytecode gerado RECONSTRÓI o record exato
/// (campos conhecidos em tempo de compilação) e chama {@link Ir64AsmRuntimeHelpers#executeOp} —
/// o MESMO despacho usado pelo interpretador. Loads/stores/`Svc` não precisam de nenhum binding
/// novo de `Aarch64GuestToHostMapper`: o acesso à memória e ao {@code Aarch64SvcHandler}
/// acontece inteiramente DENTRO de {@code Ir64BlockExecutor#execute} (o mesmo caminho que o
/// interpretador chama), então reconstruir o record e delegar já é suficiente — nenhuma
/// instrução de memória nova é emitida por este compilador. `Cycle`
/// é somado em tempo de COMPILAÇÃO (constante — nenhuma instrução dos conjuntos do PR1/PR2/PR3
/// pula seu próprio `Cycle`/`Fetch`, G4) e devolvido direto por `IRETURN`; `Fetch` emite uma
/// chamada real (o custo de acesso à memória é dinâmico). Isto NÃO inlina aritmética em locais de
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
            case Ir64Op.Load64 load -> constructLoad64(mv, load);
            case Ir64Op.Store64 store -> constructStore64(mv, store);
            case Ir64Op.LoadStorePair pair -> constructLoadStorePair(mv, pair);
            case Ir64Op.LoadLiteral64 loadLiteral -> constructLoadLiteral64(mv, loadLiteral);
            case Ir64Op.Svc svc -> constructSvc(mv, svc);
            case Ir64Op.AluShiftedRegister aluShifted -> constructAluShiftedRegister(mv, aluShifted);
            case Ir64Op.AluExtendedRegister aluExtended -> constructAluExtendedRegister(mv, aluExtended);
            case Ir64Op.ConditionalSelect conditionalSelect -> constructConditionalSelect(mv, conditionalSelect);
            case Ir64Op.Bitfield bitfield -> constructBitfield(mv, bitfield);
            case Ir64Op.MultiplyAccumulate multiplyAccumulate -> constructMultiplyAccumulate(mv, multiplyAccumulate);
            case Ir64Op.Divide divide -> constructDivide(mv, divide);
            case Ir64Op.LoadExclusive loadExclusive -> constructLoadExclusive(mv, loadExclusive);
            case Ir64Op.StoreExclusive storeExclusive -> constructStoreExclusive(mv, storeExclusive);
            default -> throw new IllegalStateException(
                    "Ir64BlockCompiler não suporta " + op.getClass().getSimpleName()
                            + " — verifique Ir64NativePolicy.supports antes de compilar");
        }
    }

    private static final String IR64_ALU_OP = "dev/vitorsilverio/armjitter/ir64/Ir64AluOp";
    private static final String IR64_MOVE_WIDE_OP = "dev/vitorsilverio/armjitter/ir64/Ir64MoveWideOp";
    private static final String IR64_BRANCH_FORM = "dev/vitorsilverio/armjitter/ir64/Ir64BranchForm";
    private static final String IR64_CONDITION = "dev/vitorsilverio/armjitter/ir64/Ir64Condition";
    private static final String IR64_COMPARE_BRANCH_FORM =
            "dev/vitorsilverio/armjitter/ir64/Ir64CompareBranchForm";
    private static final String IR64_MEM_SIZE = "dev/vitorsilverio/armjitter/ir64/Ir64MemSize";
    private static final String IR64_ADDRESSING_MODE =
            "dev/vitorsilverio/armjitter/ir64/Ir64AddressingMode";
    private static final String IR64_EXTEND_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64ExtendType";
    private static final String IR64_SHIFT_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64ShiftType";
    private static final String IR64_ALU_EXTEND_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64AluExtendType";
    private static final String IR64_CONDITIONAL_SELECT_OP =
            "dev/vitorsilverio/armjitter/ir64/Ir64ConditionalSelectOp";
    private static final String IR64_BITFIELD_OP = "dev/vitorsilverio/armjitter/ir64/Ir64BitfieldOp";

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

    /// `Load64`/`Store64` (`Ir64AddressingMode#REGISTER_OFFSET`) — `rn` é `SP`, `rm`/`extendType`
    /// só têm sentido quando o modo é `REGISTER_OFFSET`; nos demais modos {@link Ir64ExtendType}
    /// é `null` (ver o javadoc de {@link Ir64Op.Load64#extendType}), tratado por
    /// {@link #emitEnumConstantOrNull}.
    private void constructLoad64(MethodVisitor mv, Ir64Op.Load64 op) {
        String type = IR64_OP + "$Load64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitBoolean(mv, op.signExtend());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitLdcInsn(op.rm());
        emitEnumConstantOrNull(mv, IR64_EXTEND_TYPE, op.extendType());
        mv.visitLdcInsn(op.shiftAmount());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_MEM_SIZE + ";ZZL" + IR64_ADDRESSING_MODE + ";JIL" + IR64_EXTEND_TYPE + ";I)V",
                false);
    }

    private void constructStore64(MethodVisitor mv, Ir64Op.Store64 op) {
        String type = IR64_OP + "$Store64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitLdcInsn(op.rm());
        emitEnumConstantOrNull(mv, IR64_EXTEND_TYPE, op.extendType());
        mv.visitLdcInsn(op.shiftAmount());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_MEM_SIZE + ";ZL" + IR64_ADDRESSING_MODE + ";JIL" + IR64_EXTEND_TYPE + ";I)V",
                false);
    }

    /// `LDP`/`STP` — nunca tem forma `REGISTER_OFFSET` (ver javadoc de
    /// {@link Ir64Op.LoadStorePair}), então não carrega `rm`/`extendType`/`shiftAmount`.
    private void constructLoadStorePair(MethodVisitor mv, Ir64Op.LoadStorePair op) {
        String type = IR64_OP + "$LoadStorePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.load());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rt2());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZIIIZL" + IR64_ADDRESSING_MODE + ";J)V", false);
    }

    private void constructLoadLiteral64(MethodVisitor mv, Ir64Op.LoadLiteral64 op) {
        String type = IR64_OP + "$LoadLiteral64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.address());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.signExtend());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IJZZ)V", false);
    }

    private void constructSvc(MethodVisitor mv, Ir64Op.Svc op) {
        String type = IR64_OP + "$Svc";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.immediate());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(I)V", false);
    }

    private void constructAluShiftedRegister(MethodVisitor mv, Ir64Op.AluShiftedRegister op) {
        String type = IR64_OP + "$AluShiftedRegister";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ALU_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitEnumConstant(mv, IR64_SHIFT_TYPE, op.shiftType().name());
        mv.visitLdcInsn(op.shiftAmount());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.setFlags());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ALU_OP + ";IIIL" + IR64_SHIFT_TYPE + ";IZZ)V", false);
    }

    private void constructAluExtendedRegister(MethodVisitor mv, Ir64Op.AluExtendedRegister op) {
        String type = IR64_OP + "$AluExtendedRegister";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ALU_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitEnumConstant(mv, IR64_ALU_EXTEND_TYPE, op.extendType().name());
        mv.visitLdcInsn(op.shiftAmount());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.setFlags());
        emitBoolean(mv, op.dstIsStackPointer());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ALU_OP + ";IIIL" + IR64_ALU_EXTEND_TYPE + ";IZZZ)V", false);
    }

    private void constructConditionalSelect(MethodVisitor mv, Ir64Op.ConditionalSelect op) {
        String type = IR64_OP + "$ConditionalSelect";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_CONDITIONAL_SELECT_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_CONDITION, op.condition().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_CONDITIONAL_SELECT_OP + ";IIIZL" + IR64_CONDITION + ";)V", false);
    }

    private void constructBitfield(MethodVisitor mv, Ir64Op.Bitfield op) {
        String type = IR64_OP + "$Bitfield";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_BITFIELD_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src());
        mv.visitLdcInsn(op.immr());
        mv.visitLdcInsn(op.imms());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_BITFIELD_OP + ";IIIIZ)V", false);
    }

    private void constructMultiplyAccumulate(MethodVisitor mv, Ir64Op.MultiplyAccumulate op) {
        String type = IR64_OP + "$MultiplyAccumulate";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.subtract());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitLdcInsn(op.accumulator());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIIZ)V", false);
    }

    private void constructDivide(MethodVisitor mv, Ir64Op.Divide op) {
        String type = IR64_OP + "$Divide";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.signed());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIZ)V", false);
    }

    private void constructLoadExclusive(MethodVisitor mv, Ir64Op.LoadExclusive op) {
        String type = IR64_OP + "$LoadExclusive";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_MEM_SIZE + ";Z)V", false);
    }

    private void constructStoreExclusive(MethodVisitor mv, Ir64Op.StoreExclusive op) {
        String type = IR64_OP + "$StoreExclusive";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIIL" + IR64_MEM_SIZE + ";Z)V", false);
    }

    private void emitEnumConstant(MethodVisitor mv, String enumInternalName, String constantName) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, enumInternalName, constantName, "L" + enumInternalName + ";");
    }

    /// Igual a {@link #emitEnumConstant}, mas aceita `enumConstant == null` (caso de
    /// {@link Ir64Op.Load64#extendType()}/{@link Ir64Op.Store64#extendType()} fora do modo de
    /// endereçamento {@code REGISTER_OFFSET}) — empilha `ACONST_NULL` nesse caso.
    private void emitEnumConstantOrNull(MethodVisitor mv, String enumInternalName, Enum<?> enumConstant) {
        if (enumConstant == null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            emitEnumConstant(mv, enumInternalName, enumConstant.name());
        }
    }

    private void emitBoolean(MethodVisitor mv, boolean value) {
        mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
    }
}
