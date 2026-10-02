package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.BranchOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
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
///
/// **PER_OP (task C12.2)**: {@link #compilePerOp} compila o MESMO corpo, mas ops fora de
/// {@link Ir64NativePolicy} despacham ao interpretado via {@link Ir64OpInterop#executeInterpreted}
/// em vez de {@code throw}. Sem flush/reload de cache em volta da chamada — ao contrário do
/// precedente 32-bit ({@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler}), esta
/// classe não tem cache de registradores (ver Javadoc acima), então não há nada para invalidar.
public final class Ir64BlockCompiler {
    private static final String COMPILED_BLOCK_64 = "dev/vitorsilverio/armjitter/jit64/CompiledBlock64";
    private static final String AARCH64_CORE = Aarch64GuestToHostMapper.AARCH64_CORE;
    private static final String AARCH64_CORE_REF = "L" + AARCH64_CORE + ";";
    /// Prefixo do nome interno das classes do pacote `ir64` — os records vivem aninhados na
    /// sub-interface selada da sua família (`IntegerOp64$Alu64`, `FpOp64$Alu`, ...; task E15.2).
    private static final String IR64_PACKAGE = "dev/vitorsilverio/armjitter/ir64/";
    private static final String IR64_OP = IR64_PACKAGE + "Ir64Op";
    private static final String IR64_OP_REF = "L" + IR64_OP + ";";
    private static final String IR64_RUNTIME_HELPERS =
            "dev/vitorsilverio/armjitter/codegen64/jvm64/Ir64AsmRuntimeHelpers";
    /// Alvo de `INVOKESTATIC` do ramo PER_OP (task C12.2) — ver {@link Ir64OpInterop}.
    private static final String IR64_OP_INTEROP =
            "dev/vitorsilverio/armjitter/codegen64/jvm64/Ir64OpInterop";
    private static final String EXECUTE_DESCRIPTOR = "(" + AARCH64_CORE_REF + ")I";
    /// As 5 exceções de controle que {@code Ir64AsmRuntimeHelpers#executeOp}/{@link #emitFetch}
    /// podem lançar — MESMO conjunto capturado por {@code Ir64BlockExecutor#executeBlock} (G1: o
    /// interpretador é o oráculo, o bloco compilado precisa entrar na exceção do guest exatamente
    /// como ele, não deixar a exceção do HOST escapar). Achado real (sessão de retomada da F11,
    /// 2026-08-26): este `try/catch` nunca existiu aqui — ao contrário do precedente 32-bit
    /// ({@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler#compile}, que cerca o bloco
    /// inteiro desde B4.1.3), um bloco A64 promovido a nativo deixava QUALQUER falta de tradução
    /// (ou `BRK`/instrução indefinida/`HVC`/`SMC`) escapar como exceção Java não capturada em vez de
    /// entrar no handler do guest — divergência observável entre os backends JIT/INTERPRETED (JIT
    /// "trava" com uma `RuntimeException`, INTERPRETED entra na exceção e continua).
    private static final String MEMORY_TRANSLATION_EXCEPTION_64 =
            "dev/vitorsilverio/armjitter/memory/mmu/MemoryTranslationException64";
    private static final String AARCH64_BREAKPOINT_EXCEPTION =
            "dev/vitorsilverio/armjitter/core64/Aarch64BreakpointException";
    private static final String AARCH64_UNDEFINED_INSTRUCTION_EXCEPTION =
            "dev/vitorsilverio/armjitter/core64/Aarch64UndefinedInstructionException";
    private static final String AARCH64_HYPERVISOR_CALL_EXCEPTION =
            "dev/vitorsilverio/armjitter/core64/Aarch64HypervisorCallException";
    private static final String AARCH64_SECURE_MONITOR_CALL_EXCEPTION =
            "dev/vitorsilverio/armjitter/core64/Aarch64SecureMonitorCallException";
    /// Slot local do parâmetro `core` (`0` é `this`).
    private static final int LOCAL_CORE = 1;
    /// Slot local escalar de uso temporário (resultado de `AddressSpace64#accessCycles` em
    /// {@link #emitFetch}) — reusado por instrução, nunca precisa sobreviver entre chamadas.
    private static final int LOCAL_SCRATCH_INT = 2;
    /// Ciclos acumulados EM TEMPO DE EXECUÇÃO (não mais uma constante de compilação somada num
    /// `int` do compilador — precisa sobreviver a um `catch` no meio do bloco, devolvendo só os
    /// ciclos das instruções que rodaram ANTES da falta, mesma semântica de
    /// {@code Ir64BlockExecutor#executeBlock}).
    private static final int LOCAL_CYCLES = 3;
    /// Endereço da instrução dona da op corrente (`long` — ocupa os slots `4` e `5`), regravado a
    /// cada `Fetch` (constante de compilação, `LDC2_W`+`LSTORE`) — mesmo papel de `FAULT_PC_LOCAL`
    /// no precedente 32-bit, adaptado para endereço de 64 bits.
    private static final int LOCAL_FAULT_PC = 4;
    /// Referência da exceção capturada por um dos 5 handlers (`ASTORE`), usada só ali.
    private static final int LOCAL_FAULT_EXCEPTION = 6;

    /// Executor registrado com cada op de fallback PER_OP (task C12.2) — ver {@link Ir64OpInterop}
    /// e a Armadilha 3 da spec (multiarquitetura: nunca um executor estático global).
    private final Ir64BlockExecutor perOpExecutor;

    /// Cria um compilador cujo fallback PER_OP roda sob {@link Ir64BlockExecutor#Ir64BlockExecutor()}
    /// (arquitetura padrão) — irrelevante hoje para {@link #compile}/{@link #compilePerOp}, que não
    /// consultam arquitetura nenhuma, mas espelha o construtor multiarquitetura do precedente
    /// 32-bit ({@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler}).
    public Ir64BlockCompiler() {
        this(new Ir64BlockExecutor());
    }

    /// Cria um compilador cujo fallback PER_OP usa o executor informado — ver {@link Ir64OpInterop}.
    public Ir64BlockCompiler(Ir64BlockExecutor perOpExecutor) {
        this.perOpExecutor = perOpExecutor;
    }

    /// Compila `block` para uma classe que implementa {@link CompiledBlock64}. Todas as ops devem
    /// passar {@link Ir64NativePolicy#supports(Ir64Block)} (não verificado aqui; é responsabilidade
    /// do chamador, mesma disciplina do 32-bit) — use {@link #compilePerOp} quando isso não vale.
    ///
    /// @param internalName nome interno (formato ASM, `a/b/C`) da classe gerada
    /// @param block bloco IR a compilar
    /// @return bytecode da classe gerada
    public byte[] compile(String internalName, Ir64Block block) {
        return compile(internalName, block, false);
    }

    /// Compila `block` no modo PER_OP (task C12.2): ops fora de {@link Ir64NativePolicy} são
    /// despachadas ao interpretado via {@link Ir64OpInterop#executeInterpreted} inline no bytecode,
    /// em vez de exigir que TODAS as ops do bloco sejam nativas.
    ///
    /// @param internalName nome interno (formato ASM, `a/b/C`) da classe gerada
    /// @param block bloco IR a compilar — pode conter ops fora de {@link Ir64NativePolicy}
    /// @return bytecode da classe gerada
    public byte[] compilePerOp(String internalName, Ir64Block block) {
        return compile(internalName, block, true);
    }

    private byte[] compile(String internalName, Ir64Block block, boolean perOpFallback) {
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
        emitBody(method, block, perOpFallback);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitBody(MethodVisitor mv, Ir64Block block, boolean perOpFallback) {
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_CYCLES);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label translationFaultHandler = new Label();
        Label breakpointHandler = new Label();
        Label undefinedHandler = new Label();
        Label hypervisorCallHandler = new Label();
        Label secureMonitorCallHandler = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, translationFaultHandler, MEMORY_TRANSLATION_EXCEPTION_64);
        mv.visitTryCatchBlock(tryStart, tryEnd, breakpointHandler, AARCH64_BREAKPOINT_EXCEPTION);
        mv.visitTryCatchBlock(tryStart, tryEnd, undefinedHandler, AARCH64_UNDEFINED_INSTRUCTION_EXCEPTION);
        mv.visitTryCatchBlock(tryStart, tryEnd, hypervisorCallHandler, AARCH64_HYPERVISOR_CALL_EXCEPTION);
        mv.visitTryCatchBlock(tryStart, tryEnd, secureMonitorCallHandler, AARCH64_SECURE_MONITOR_CALL_EXCEPTION);

        // LOCAL_FAULT_PC precisa de um valor ANTES de tryStart (mesmo motivo do precedente
        // 32-bit, `AsmBlockCompiler#compile`): o verificador da JVM trata os 5 handlers como
        // alcançáveis a partir de QUALQUER bytecode dentro do range protegido, inclusive o
        // primeiro `Fetch` (que já pode lançar `MemoryTranslationException64`, ver a Javadoc de
        // `Ir64BlockExecutor#step`).
        mv.visitLdcInsn(block.startPc());
        mv.visitVarInsn(Opcodes.LSTORE, LOCAL_FAULT_PC);
        mv.visitLabel(tryStart);
        long lastFetchAddress = -1L;
        int lastFetchSizeBytes = 0;
        for (Ir64Op op : block.operations()) {
            switch (op.kind()) {
                case Ir64Op.Kind.CYCLE -> emitCycle(mv, (Ir64Op.Cycle) op);
                case Ir64Op.Kind.FETCH -> {
                    Ir64Op.Fetch fetch = (Ir64Op.Fetch) op;
                    mv.visitLdcInsn(fetch.address());
                    mv.visitVarInsn(Opcodes.LSTORE, LOCAL_FAULT_PC);
                    emitFetch(mv, fetch);
                    lastFetchAddress = fetch.address();
                    lastFetchSizeBytes = fetch.sizeBytes();
                }
                default -> {
                    long nextPc = lastFetchAddress + lastFetchSizeBytes;
                    if (Ir64NativePolicy.isSystemViaHelper(op)
                            || (perOpFallback && !Ir64NativePolicy.supports(op))) {
                        emitPerOpFallback(mv, op, nextPc);
                    } else {
                        emitOp(mv, op, nextPc);
                    }
                }
            }
        }
        mv.visitLabel(tryEnd);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);

        // Os 5 handlers espelham `Ir64BlockExecutor#executeBlock` ops a op: materializam a
        // exceção do GUEST no `core` (`enterMemoryAbort`/`enterBreakpointException`/etc, usando
        // `LOCAL_FAULT_PC` como endereço da instrução faltosa) e devolvem os ciclos PARCIAIS já
        // acumulados em `LOCAL_CYCLES` (instruções executadas antes da falta) — nunca os do bloco
        // inteiro, que não terminou de rodar.
        mv.visitLabel(translationFaultHandler);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_FAULT_EXCEPTION);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.LLOAD, LOCAL_FAULT_PC);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_FAULT_EXCEPTION);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_CORE, "enterMemoryAbort",
                "(JL" + MEMORY_TRANSLATION_EXCEPTION_64 + ";)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(breakpointHandler);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_FAULT_EXCEPTION);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.LLOAD, LOCAL_FAULT_PC);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_FAULT_EXCEPTION);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_BREAKPOINT_EXCEPTION, "immediate", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_CORE, "enterBreakpointException", "(JI)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(undefinedHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.LLOAD, LOCAL_FAULT_PC);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_CORE, "enterUndefinedInstructionException", "(J)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(hypervisorCallHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.LLOAD, LOCAL_FAULT_PC);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_CORE, "enterHypervisorCall", "(J)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(secureMonitorCallHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitVarInsn(Opcodes.LLOAD, LOCAL_FAULT_PC);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AARCH64_CORE, "enterSecureMonitorCall", "(J)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /// `LOCAL_CYCLES += op.count()` — acumulado em TEMPO DE EXECUÇÃO (não mais uma constante de
    /// compilação somada num `int` do compilador, ver a Javadoc de {@link #LOCAL_CYCLES}).
    private void emitCycle(MethodVisitor mv, Ir64Op.Cycle op) {
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CYCLES);
        mv.visitLdcInsn(op.count());
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_CYCLES);
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
        emitSetProgramCounterUnlessChanged(mv, nextPc);
    }

    /// Task C12.2: `Ir64OpInterop.executeInterpreted(core, opId)` para uma op fora de
    /// {@link Ir64NativePolicy} — mesmo contrato de saída de {@link #emitOp} (nativo): se `false`
    /// (PC não mudou), `core.setProgramCounter(nextPc)`. Registra a op com {@link #perOpExecutor}
    /// (Armadilha 3: nunca um executor estático global — ver {@link Ir64OpInterop}).
    private void emitPerOpFallback(MethodVisitor mv, Ir64Op op, long nextPc) {
        int opId = Ir64OpInterop.register(op, perOpExecutor);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CORE);
        mv.visitLdcInsn(opId);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IR64_OP_INTEROP, "executeInterpreted",
                "(" + AARCH64_CORE_REF + "I)Z", false);
        emitSetProgramCounterUnlessChanged(mv, nextPc);
    }

    /// Desempilha o `boolean pcChanged` deixado por {@link #emitOp}/{@link #emitPerOpFallback} e,
    /// se falso, materializa `core.setProgramCounter(nextPc)` — mesmo contrato do interpretador
    /// (`Ir64BlockExecutor#executeBlock`, ramo `default`, `:237-238`).
    private void emitSetProgramCounterUnlessChanged(MethodVisitor mv, long nextPc) {
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
            case IntegerOp64.Alu64 alu -> constructAlu64(mv, alu);
            case IntegerOp64.MoveWide moveWide -> constructMoveWide(mv, moveWide);
            case IntegerOp64.PcRelative pcRelative -> constructPcRelative(mv, pcRelative);
            case BranchOp64.Branch64 branch -> constructBranch64(mv, branch);
            case BranchOp64.CompareBranch64 compareBranch -> constructCompareBranch64(mv, compareBranch);
            case MemoryOp64.Load64 load -> constructLoad64(mv, load);
            case MemoryOp64.Store64 store -> constructStore64(mv, store);
            case MemoryOp64.LoadStorePair pair -> constructLoadStorePair(mv, pair);
            case MemoryOp64.LoadLiteral64 loadLiteral -> constructLoadLiteral64(mv, loadLiteral);
            case SystemOp64.Svc svc -> constructSvc(mv, svc);
            case IntegerOp64.AluShiftedRegister aluShifted -> constructAluShiftedRegister(mv, aluShifted);
            case IntegerOp64.AluExtendedRegister aluExtended -> constructAluExtendedRegister(mv, aluExtended);
            case IntegerOp64.ConditionalSelect conditionalSelect -> constructConditionalSelect(mv, conditionalSelect);
            case IntegerOp64.Bitfield bitfield -> constructBitfield(mv, bitfield);
            case IntegerOp64.MultiplyAccumulate multiplyAccumulate -> constructMultiplyAccumulate(mv, multiplyAccumulate);
            case IntegerOp64.Divide divide -> constructDivide(mv, divide);
            case MemoryOp64.LoadExclusive loadExclusive -> constructLoadExclusive(mv, loadExclusive);
            case MemoryOp64.StoreExclusive storeExclusive -> constructStoreExclusive(mv, storeExclusive);
            case FpOp64.Alu fp64Alu -> constructFp64Alu(mv, fp64Alu);
            case FpOp64.MoveImmediate fp64MoveImmediate -> constructFp64MoveImmediate(mv, fp64MoveImmediate);
            case FpOp64.Compare fp64Compare -> constructFp64Compare(mv, fp64Compare);
            case FpOp64.Convert fp64Convert -> constructFp64Convert(mv, fp64Convert);
            case IntegerOp64.ConditionalCompare conditionalCompare -> constructConditionalCompare(mv, conditionalCompare);
            case IntegerOp64.LogicalShiftedRegister logicalShiftedRegister ->
                    constructLogicalShiftedRegister(mv, logicalShiftedRegister);
            case IntegerOp64.ShiftVariable shiftVariable -> constructShiftVariable(mv, shiftVariable);
            case IntegerOp64.AluWithCarry aluWithCarry -> constructAluWithCarry(mv, aluWithCarry);
            case IntegerOp64.Extract extract -> constructExtract(mv, extract);
            case IntegerOp64.DataProcessing1Source dataProcessing1Source ->
                    constructDataProcessing1Source(mv, dataProcessing1Source);
            case IntegerOp64.MultiplyAccumulateLong multiplyAccumulateLong ->
                    constructMultiplyAccumulateLong(mv, multiplyAccumulateLong);
            case IntegerOp64.MultiplyHigh multiplyHigh -> constructMultiplyHigh(mv, multiplyHigh);
            case MemoryOp64.CompareAndSwap compareAndSwap -> constructCompareAndSwap(mv, compareAndSwap);
            case MemoryOp64.CompareAndSwapPair compareAndSwapPair -> constructCompareAndSwapPair(mv, compareAndSwapPair);
            case MemoryOp64.LoadExclusivePair loadExclusivePair -> constructLoadExclusivePair(mv, loadExclusivePair);
            case MemoryOp64.StoreExclusivePair storeExclusivePair -> constructStoreExclusivePair(mv, storeExclusivePair);
            case MemoryOp64.AtomicMemoryOp atomicMemoryOp -> constructAtomicMemoryOp(mv, atomicMemoryOp);
            case IntegerOp64.EvaluateIntoFlags evaluateIntoFlags -> constructEvaluateIntoFlags(mv, evaluateIntoFlags);
            case IntegerOp64.RotateIntoFlags rotateIntoFlags -> constructRotateIntoFlags(mv, rotateIntoFlags);
            case IntegerOp64.ConvertFlags convertFlags -> constructConvertFlags(mv, convertFlags);
            case FpOp64.MultiplyAdd fp64MultiplyAdd -> constructFp64MultiplyAdd(mv, fp64MultiplyAdd);
            case FpOp64.ConditionalSelect fp64ConditionalSelect ->
                    constructFp64ConditionalSelect(mv, fp64ConditionalSelect);
            case FpOp64.ConditionalCompare fp64ConditionalCompare ->
                    constructFp64ConditionalCompare(mv, fp64ConditionalCompare);
            case FpOp64.Round fp64Round -> constructFp64Round(mv, fp64Round);
            case FpOp64.IntegerConvert fp64IntegerConvert -> constructFp64IntegerConvert(mv, fp64IntegerConvert);
            case FpOp64.GeneralRegisterMove fp64GeneralRegisterMove ->
                    constructFp64GeneralRegisterMove(mv, fp64GeneralRegisterMove);
            case FpOp64.Load64 fpLoad64 -> constructFpLoad64(mv, fpLoad64);
            case FpOp64.Store64 fpStore64 -> constructFpStore64(mv, fpStore64);
            case FpOp64.LoadStorePair fpLoadStorePair -> constructFpLoadStorePair(mv, fpLoadStorePair);
            case FpOp64.LoadLiteral64 fpLoadLiteral64 -> constructFpLoadLiteral64(mv, fpLoadLiteral64);
            case AdvSimdMoveOp64.LoadStoreMultiple vectorLoadStoreMultiple ->
                    constructVectorLoadStoreMultiple(mv, vectorLoadStoreMultiple);
            case AdvSimdMoveOp64.LoadStoreSingle vectorLoadStoreSingle ->
                    constructVectorLoadStoreSingle(mv, vectorLoadStoreSingle);
            case AdvSimdMoveOp64.LoadSingleReplicate vectorLoadSingleReplicate ->
                    constructVectorLoadSingleReplicate(mv, vectorLoadSingleReplicate);
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
    private static final String IR64_FP_MEM_SIZE = "dev/vitorsilverio/armjitter/ir64/Ir64FpMemSize";
    private static final String IR64_ADDRESSING_MODE =
            "dev/vitorsilverio/armjitter/ir64/Ir64AddressingMode";
    private static final String IR64_EXTEND_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64ExtendType";
    private static final String IR64_SHIFT_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64ShiftType";
    private static final String IR64_ALU_EXTEND_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64AluExtendType";
    private static final String IR64_CONDITIONAL_SELECT_OP =
            "dev/vitorsilverio/armjitter/ir64/Ir64ConditionalSelectOp";
    private static final String IR64_BITFIELD_OP = "dev/vitorsilverio/armjitter/ir64/Ir64BitfieldOp";
    private static final String IR64_FP64_OPERATION = IR64_PACKAGE + "FpOp64$Fp64Operation";
    private static final String IR64_FP64_CONVERSION = IR64_PACKAGE + "FpOp64$Fp64Conversion";
    private static final String IR64_FP64_ROUNDING_DIRECTION = IR64_PACKAGE + "FpOp64$Fp64RoundingDirection";
    private static final String IR64_LOGICAL_SHIFT_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64LogicalShiftType";
    private static final String IR64_ONE_SOURCE_OP = "dev/vitorsilverio/armjitter/ir64/Ir64OneSourceOp";
    private static final String IR64_ATOMIC_OP = "dev/vitorsilverio/armjitter/ir64/Ir64AtomicOp";
    private static final String IR64_FLAG_CONVERSION_OP = "dev/vitorsilverio/armjitter/ir64/Ir64FlagConversionOp";

    private void constructAlu64(MethodVisitor mv, IntegerOp64.Alu64 op) {
        String type = IR64_PACKAGE + "IntegerOp64$Alu64";
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

    private void constructMoveWide(MethodVisitor mv, IntegerOp64.MoveWide op) {
        String type = IR64_PACKAGE + "IntegerOp64$MoveWide";
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

    private void constructPcRelative(MethodVisitor mv, IntegerOp64.PcRelative op) {
        String type = IR64_PACKAGE + "IntegerOp64$PcRelative";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.instructionAddress());
        mv.visitLdcInsn(op.immediate());
        emitBoolean(mv, op.page());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IJJZ)V", false);
    }

    private void constructBranch64(MethodVisitor mv, BranchOp64.Branch64 op) {
        String type = IR64_PACKAGE + "BranchOp64$Branch64";
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

    private void constructCompareBranch64(MethodVisitor mv, BranchOp64.CompareBranch64 op) {
        String type = IR64_PACKAGE + "BranchOp64$CompareBranch64";
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
    /// é `null` (ver o javadoc de {@link MemoryOp64.Load64#extendType}), tratado por
    /// {@link #emitEnumConstantOrNull}.
    private void constructLoad64(MethodVisitor mv, MemoryOp64.Load64 op) {
        String type = IR64_PACKAGE + "MemoryOp64$Load64";
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

    private void constructStore64(MethodVisitor mv, MemoryOp64.Store64 op) {
        String type = IR64_PACKAGE + "MemoryOp64$Store64";
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
    /// {@link MemoryOp64.LoadStorePair}), então não carrega `rm`/`extendType`/`shiftAmount`.
    private void constructLoadStorePair(MethodVisitor mv, MemoryOp64.LoadStorePair op) {
        String type = IR64_PACKAGE + "MemoryOp64$LoadStorePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.load());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rt2());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        emitBoolean(mv, op.signExtend());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZIIIZL" + IR64_ADDRESSING_MODE + ";JZ)V", false);
    }

    private void constructLoadLiteral64(MethodVisitor mv, MemoryOp64.LoadLiteral64 op) {
        String type = IR64_PACKAGE + "MemoryOp64$LoadLiteral64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.address());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.signExtend());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IJZZ)V", false);
    }

    private void constructSvc(MethodVisitor mv, SystemOp64.Svc op) {
        String type = IR64_PACKAGE + "SystemOp64$Svc";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.immediate());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(I)V", false);
    }

    private void constructAluShiftedRegister(MethodVisitor mv, IntegerOp64.AluShiftedRegister op) {
        String type = IR64_PACKAGE + "IntegerOp64$AluShiftedRegister";
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

    private void constructAluExtendedRegister(MethodVisitor mv, IntegerOp64.AluExtendedRegister op) {
        String type = IR64_PACKAGE + "IntegerOp64$AluExtendedRegister";
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

    private void constructConditionalSelect(MethodVisitor mv, IntegerOp64.ConditionalSelect op) {
        String type = IR64_PACKAGE + "IntegerOp64$ConditionalSelect";
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

    private void constructBitfield(MethodVisitor mv, IntegerOp64.Bitfield op) {
        String type = IR64_PACKAGE + "IntegerOp64$Bitfield";
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

    private void constructMultiplyAccumulate(MethodVisitor mv, IntegerOp64.MultiplyAccumulate op) {
        String type = IR64_PACKAGE + "IntegerOp64$MultiplyAccumulate";
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

    private void constructDivide(MethodVisitor mv, IntegerOp64.Divide op) {
        String type = IR64_PACKAGE + "IntegerOp64$Divide";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.signed());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIZ)V", false);
    }

    private void constructLoadExclusive(MethodVisitor mv, MemoryOp64.LoadExclusive op) {
        String type = IR64_PACKAGE + "MemoryOp64$LoadExclusive";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_MEM_SIZE + ";Z)V", false);
    }

    private void constructStoreExclusive(MethodVisitor mv, MemoryOp64.StoreExclusive op) {
        String type = IR64_PACKAGE + "MemoryOp64$StoreExclusive";
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

    private void constructFp64Alu(MethodVisitor mv, FpOp64.Alu op) {
        String type = IR64_PACKAGE + "FpOp64$Alu";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FP64_OPERATION, op.op().name());
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FP64_OPERATION + ";ZIII)V", false);
    }

    private void constructFp64MoveImmediate(MethodVisitor mv, FpOp64.MoveImmediate op) {
        String type = IR64_PACKAGE + "FpOp64$MoveImmediate";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.immediateBits());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIJ)V", false);
    }

    private void constructFp64Compare(MethodVisitor mv, FpOp64.Compare op) {
        String type = IR64_PACKAGE + "FpOp64$Compare";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        emitBoolean(mv, op.compareWithZero());
        emitBoolean(mv, op.signalOnQuietNaN());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZZII)V", false);
    }

    private void constructFp64Convert(MethodVisitor mv, FpOp64.Convert op) {
        String type = IR64_PACKAGE + "FpOp64$Convert";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FP64_CONVERSION, op.conversion().name());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vm());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FP64_CONVERSION + ";II)V", false);
    }

    private void constructConditionalCompare(MethodVisitor mv, IntegerOp64.ConditionalCompare op) {
        String type = IR64_PACKAGE + "IntegerOp64$ConditionalCompare";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ALU_OP, op.opcode().name());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.immediateForm());
        mv.visitLdcInsn(op.rm());
        mv.visitLdcInsn(op.immediate());
        emitBoolean(mv, op.wide());
        emitEnumConstant(mv, IR64_CONDITION, op.condition().name());
        mv.visitLdcInsn(op.nzcv());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ALU_OP + ";IZIIZL" + IR64_CONDITION + ";I)V", false);
    }

    private void constructLogicalShiftedRegister(MethodVisitor mv, IntegerOp64.LogicalShiftedRegister op) {
        String type = IR64_PACKAGE + "IntegerOp64$LogicalShiftedRegister";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ALU_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitEnumConstant(mv, IR64_LOGICAL_SHIFT_TYPE, op.shiftType().name());
        mv.visitLdcInsn(op.shiftAmount());
        emitBoolean(mv, op.invert());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.setFlags());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ALU_OP + ";IIIL" + IR64_LOGICAL_SHIFT_TYPE + ";IZZZ)V", false);
    }

    private void constructShiftVariable(MethodVisitor mv, IntegerOp64.ShiftVariable op) {
        String type = IR64_PACKAGE + "IntegerOp64$ShiftVariable";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitEnumConstant(mv, IR64_LOGICAL_SHIFT_TYPE, op.shiftType().name());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(III" + "L" + IR64_LOGICAL_SHIFT_TYPE + ";Z)V", false);
    }

    private void constructAluWithCarry(MethodVisitor mv, IntegerOp64.AluWithCarry op) {
        String type = IR64_PACKAGE + "IntegerOp64$AluWithCarry";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.subtract());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.setFlags());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIZZ)V", false);
    }

    private void constructExtract(MethodVisitor mv, IntegerOp64.Extract op) {
        String type = IR64_PACKAGE + "IntegerOp64$Extract";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitLdcInsn(op.lsb());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIIZ)V", false);
    }

    private void constructDataProcessing1Source(MethodVisitor mv, IntegerOp64.DataProcessing1Source op) {
        String type = IR64_PACKAGE + "IntegerOp64$DataProcessing1Source";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ONE_SOURCE_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ONE_SOURCE_OP + ";IIZ)V", false);
    }

    private void constructMultiplyAccumulateLong(MethodVisitor mv, IntegerOp64.MultiplyAccumulateLong op) {
        String type = IR64_PACKAGE + "IntegerOp64$MultiplyAccumulateLong";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.subtract());
        emitBoolean(mv, op.signed());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitLdcInsn(op.accumulator());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZIIII)V", false);
    }

    private void constructMultiplyHigh(MethodVisitor mv, IntegerOp64.MultiplyHigh op) {
        String type = IR64_PACKAGE + "IntegerOp64$MultiplyHigh";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.signed());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIII)V", false);
    }

    private void constructCompareAndSwap(MethodVisitor mv, MemoryOp64.CompareAndSwap op) {
        String type = IR64_PACKAGE + "MemoryOp64$CompareAndSwap";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(III" + "L" + IR64_MEM_SIZE + ";)V", false);
    }

    private void constructCompareAndSwapPair(MethodVisitor mv, MemoryOp64.CompareAndSwapPair op) {
        String type = IR64_PACKAGE + "MemoryOp64$CompareAndSwapPair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIZ)V", false);
    }

    private void constructLoadExclusivePair(MethodVisitor mv, MemoryOp64.LoadExclusivePair op) {
        String type = IR64_PACKAGE + "MemoryOp64$LoadExclusivePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rt2());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIZZ)V", false);
    }

    private void constructStoreExclusivePair(MethodVisitor mv, MemoryOp64.StoreExclusivePair op) {
        String type = IR64_PACKAGE + "MemoryOp64$StoreExclusivePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rt2());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIIZZ)V", false);
    }

    private void constructAtomicMemoryOp(MethodVisitor mv, MemoryOp64.AtomicMemoryOp op) {
        String type = IR64_PACKAGE + "MemoryOp64$AtomicMemoryOp";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        emitEnumConstant(mv, IR64_ATOMIC_OP, op.operation().name());
        emitBoolean(mv, op.acquire());
        emitBoolean(mv, op.release());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(III" + "L" + IR64_MEM_SIZE + ";L" + IR64_ATOMIC_OP + ";ZZ)V", false);
    }

    private void constructEvaluateIntoFlags(MethodVisitor mv, IntegerOp64.EvaluateIntoFlags op) {
        String type = IR64_PACKAGE + "IntegerOp64$EvaluateIntoFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.sizeBits());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(II)V", false);
    }

    private void constructRotateIntoFlags(MethodVisitor mv, IntegerOp64.RotateIntoFlags op) {
        String type = IR64_PACKAGE + "IntegerOp64$RotateIntoFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.shift());
        mv.visitLdcInsn(op.mask());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(III)V", false);
    }

    private void constructConvertFlags(MethodVisitor mv, IntegerOp64.ConvertFlags op) {
        String type = IR64_PACKAGE + "IntegerOp64$ConvertFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FLAG_CONVERSION_OP, op.opcode().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FLAG_CONVERSION_OP + ";)V", false);
    }

    private void constructFp64MultiplyAdd(MethodVisitor mv, FpOp64.MultiplyAdd op) {
        String type = IR64_PACKAGE + "FpOp64$MultiplyAdd";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        emitBoolean(mv, op.negateAddend());
        emitBoolean(mv, op.negateProduct());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        mv.visitLdcInsn(op.va());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZZIIII)V", false);
    }

    private void constructFp64ConditionalSelect(MethodVisitor mv, FpOp64.ConditionalSelect op) {
        String type = IR64_PACKAGE + "FpOp64$ConditionalSelect";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        emitEnumConstant(mv, IR64_CONDITION, op.condition().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZIIIL" + IR64_CONDITION + ";)V", false);
    }

    private void constructFp64ConditionalCompare(MethodVisitor mv, FpOp64.ConditionalCompare op) {
        String type = IR64_PACKAGE + "FpOp64$ConditionalCompare";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        emitBoolean(mv, op.signalOnQuietNaN());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        emitEnumConstant(mv, IR64_CONDITION, op.condition().name());
        mv.visitLdcInsn(op.nzcv());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZZIIL" + IR64_CONDITION + ";I)V", false);
    }

    private void constructFp64Round(MethodVisitor mv, FpOp64.Round op) {
        String type = IR64_PACKAGE + "FpOp64$Round";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FP64_ROUNDING_DIRECTION, op.direction().name());
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vn());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FP64_ROUNDING_DIRECTION + ";ZII)V", false);
    }

    private void constructFp64IntegerConvert(MethodVisitor mv, FpOp64.IntegerConvert op) {
        String type = IR64_PACKAGE + "FpOp64$IntegerConvert";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.toFloat());
        emitBoolean(mv, op.signed());
        emitEnumConstant(mv, IR64_FP64_ROUNDING_DIRECTION, op.rounding().name());
        emitBoolean(mv, op.doublePrecision());
        emitBoolean(mv, op.wide());
        mv.visitLdcInsn(op.fixedPointFractionBits());
        mv.visitLdcInsn(op.fpReg());
        mv.visitLdcInsn(op.gpReg());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZZL" + IR64_FP64_ROUNDING_DIRECTION + ";ZZIII)V", false);
    }

    private void constructFp64GeneralRegisterMove(MethodVisitor mv, FpOp64.GeneralRegisterMove op) {
        String type = IR64_PACKAGE + "FpOp64$GeneralRegisterMove";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.toFloat());
        emitBoolean(mv, op.wide());
        mv.visitLdcInsn(op.fpReg());
        mv.visitLdcInsn(op.gpReg());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZII)V", false);
    }

    /// `FpOp64.Load64`/`FpOp64.Store64` (C12.5, B8.13) — mesmo layout de campos de {@link #constructLoad64},
    /// trocando {@link #IR64_MEM_SIZE} por {@link #IR64_FP_MEM_SIZE} (tamanho `QUAD` extra) e sem
    /// `signExtend`/`wide` (SIMD&FP não tem forma com sinal nem eixo `W`/`X` — Armadilha 2 da spec:
    /// a disciplina de escrita destrutiva/zeragem vive inteira em
    /// {@code Ir64FpMemoryExecutor#executeFpLoad}, intocada aqui).
    private void constructFpLoad64(MethodVisitor mv, FpOp64.Load64 op) {
        String type = IR64_PACKAGE + "FpOp64$Load64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.vt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_FP_MEM_SIZE, op.size().name());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitLdcInsn(op.rm());
        emitEnumConstantOrNull(mv, IR64_EXTEND_TYPE, op.extendType());
        mv.visitLdcInsn(op.shiftAmount());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_FP_MEM_SIZE + ";L" + IR64_ADDRESSING_MODE + ";JIL" + IR64_EXTEND_TYPE + ";I)V",
                false);
    }

    private void constructFpStore64(MethodVisitor mv, FpOp64.Store64 op) {
        String type = IR64_PACKAGE + "FpOp64$Store64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.vt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_FP_MEM_SIZE, op.size().name());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitLdcInsn(op.rm());
        emitEnumConstantOrNull(mv, IR64_EXTEND_TYPE, op.extendType());
        mv.visitLdcInsn(op.shiftAmount());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(IIL" + IR64_FP_MEM_SIZE + ";L" + IR64_ADDRESSING_MODE + ";JIL" + IR64_EXTEND_TYPE + ";I)V",
                false);
    }

    /// `LDP`/`STP` SIMD&FP (C12.5, B8.13) — nunca tem forma `REGISTER_OFFSET` (mesma restrição de
    /// {@link #constructLoadStorePair}) e sem `signExtend` (não existe `LDPSW` SIMD&FP).
    private void constructFpLoadStorePair(MethodVisitor mv, FpOp64.LoadStorePair op) {
        String type = IR64_PACKAGE + "FpOp64$LoadStorePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.load());
        mv.visitLdcInsn(op.vt());
        mv.visitLdcInsn(op.vt2());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_FP_MEM_SIZE, op.size().name());
        emitEnumConstant(mv, IR64_ADDRESSING_MODE, op.addressingMode().name());
        mv.visitLdcInsn(op.immediate());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZIIIL" + IR64_FP_MEM_SIZE + ";L" + IR64_ADDRESSING_MODE + ";J)V", false);
    }

    /// `LDR (literal)` SIMD&FP (C12.5, B8.13) — Armadilha 6 da spec: {@link FpOp64.LoadLiteral64#address}
    /// já é o endereço ABSOLUTO resolvido pelo decoder a partir do PC da PRÓPRIA instrução (nunca o
    /// PC do bloco) — mesma convenção de {@link #constructLoadLiteral64}, campo constante de
    /// compilação, nenhum cálculo de PC acontece aqui.
    private void constructFpLoadLiteral64(MethodVisitor mv, FpOp64.LoadLiteral64 op) {
        String type = IR64_PACKAGE + "FpOp64$LoadLiteral64";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.vt());
        mv.visitLdcInsn(op.address());
        emitEnumConstant(mv, IR64_FP_MEM_SIZE, op.size().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IJL" + IR64_FP_MEM_SIZE + ";)V", false);
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store MULTIPLE structures, C12.5, B8.6) — Armadilha 4
    /// da spec: reconstrói só o record (campos constantes de compilação) e delega a
    /// {@code Ir64AsmRuntimeHelpers#executeOp}/{@code Ir64VectorMemoryExecutor#executeVectorLoadStoreMultiple}
    /// — os laços de `rpt`/`selem`/elementos (com a passada separada de zeragem dos bits altos,
    /// Armadilha 2) NÃO são reimplementados em bytecode, mesmo padrão de "chamar helper" que a
    /// C12.3 usou para atomicidade.
    private void constructVectorLoadStoreMultiple(MethodVisitor mv, AdvSimdMoveOp64.LoadStoreMultiple op) {
        String type = IR64_PACKAGE + "AdvSimdMoveOp64$LoadStoreMultiple";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.load());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.rm());
        emitBoolean(mv, op.q());
        emitBoolean(mv, op.postIndex());
        mv.visitLdcInsn(op.elementSizeLog2());
        mv.visitLdcInsn(op.rpt());
        mv.visitLdcInsn(op.selem());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIZZIII)V", false);
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` de lane única (AdvSIMD load/store SINGLE structure, C12.5, B8.6) —
    /// mesma rota "helper" de {@link #constructVectorLoadStoreMultiple} (Armadilha 4): o laço de
    /// `selem` registradores e a escrita de UMA lane sem tocar o resto do registro (Armadilha 2,
    /// disciplina "preserva") vivem só em {@code Ir64VectorMemoryExecutor#executeVectorLoadStoreSingle}.
    private void constructVectorLoadStoreSingle(MethodVisitor mv, AdvSimdMoveOp64.LoadStoreSingle op) {
        String type = IR64_PACKAGE + "AdvSimdMoveOp64$LoadStoreSingle";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.load());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.rm());
        emitBoolean(mv, op.postIndex());
        mv.visitLdcInsn(op.elementSizeLog2());
        mv.visitLdcInsn(op.selem());
        mv.visitLdcInsn(op.index());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIIIZIII)V", false);
    }

    /// `LD1R`-`LD4R` (C12.5, B8.6) — mesma rota "helper" (Armadilha 4); sem `load` (só existe forma
    /// `LD`, nunca `ST`, ver javadoc de {@link AdvSimdMoveOp64.LoadSingleReplicate}).
    private void constructVectorLoadSingleReplicate(MethodVisitor mv, AdvSimdMoveOp64.LoadSingleReplicate op) {
        String type = IR64_PACKAGE + "AdvSimdMoveOp64$LoadSingleReplicate";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.rm());
        emitBoolean(mv, op.q());
        emitBoolean(mv, op.postIndex());
        mv.visitLdcInsn(op.elementSizeLog2());
        mv.visitLdcInsn(op.selem());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIZZII)V", false);
    }

    private void emitEnumConstant(MethodVisitor mv, String enumInternalName, String constantName) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, enumInternalName, constantName, "L" + enumInternalName + ";");
    }

    /// Igual a {@link #emitEnumConstant}, mas aceita `enumConstant == null` (caso de
    /// {@link MemoryOp64.Load64#extendType()}/{@link MemoryOp64.Store64#extendType()} fora do modo de
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
