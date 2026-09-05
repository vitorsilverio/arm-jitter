package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
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
    private static final String IR64_OP = "dev/vitorsilverio/armjitter/ir64/Ir64Op";
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
                    if (perOpFallback && !Ir64NativePolicy.supports(op)) {
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
            case Ir64Op.Fp64Alu fp64Alu -> constructFp64Alu(mv, fp64Alu);
            case Ir64Op.Fp64MoveImmediate fp64MoveImmediate -> constructFp64MoveImmediate(mv, fp64MoveImmediate);
            case Ir64Op.Fp64Compare fp64Compare -> constructFp64Compare(mv, fp64Compare);
            case Ir64Op.Fp64Convert fp64Convert -> constructFp64Convert(mv, fp64Convert);
            case Ir64Op.ConditionalCompare conditionalCompare -> constructConditionalCompare(mv, conditionalCompare);
            case Ir64Op.LogicalShiftedRegister logicalShiftedRegister ->
                    constructLogicalShiftedRegister(mv, logicalShiftedRegister);
            case Ir64Op.ShiftVariable shiftVariable -> constructShiftVariable(mv, shiftVariable);
            case Ir64Op.AluWithCarry aluWithCarry -> constructAluWithCarry(mv, aluWithCarry);
            case Ir64Op.Extract extract -> constructExtract(mv, extract);
            case Ir64Op.DataProcessing1Source dataProcessing1Source ->
                    constructDataProcessing1Source(mv, dataProcessing1Source);
            case Ir64Op.MultiplyAccumulateLong multiplyAccumulateLong ->
                    constructMultiplyAccumulateLong(mv, multiplyAccumulateLong);
            case Ir64Op.MultiplyHigh multiplyHigh -> constructMultiplyHigh(mv, multiplyHigh);
            case Ir64Op.CompareAndSwap compareAndSwap -> constructCompareAndSwap(mv, compareAndSwap);
            case Ir64Op.CompareAndSwapPair compareAndSwapPair -> constructCompareAndSwapPair(mv, compareAndSwapPair);
            case Ir64Op.LoadExclusivePair loadExclusivePair -> constructLoadExclusivePair(mv, loadExclusivePair);
            case Ir64Op.StoreExclusivePair storeExclusivePair -> constructStoreExclusivePair(mv, storeExclusivePair);
            case Ir64Op.AtomicMemoryOp atomicMemoryOp -> constructAtomicMemoryOp(mv, atomicMemoryOp);
            case Ir64Op.EvaluateIntoFlags evaluateIntoFlags -> constructEvaluateIntoFlags(mv, evaluateIntoFlags);
            case Ir64Op.RotateIntoFlags rotateIntoFlags -> constructRotateIntoFlags(mv, rotateIntoFlags);
            case Ir64Op.ConvertFlags convertFlags -> constructConvertFlags(mv, convertFlags);
            case Ir64Op.Fp64MultiplyAdd fp64MultiplyAdd -> constructFp64MultiplyAdd(mv, fp64MultiplyAdd);
            case Ir64Op.Fp64ConditionalSelect fp64ConditionalSelect ->
                    constructFp64ConditionalSelect(mv, fp64ConditionalSelect);
            case Ir64Op.Fp64ConditionalCompare fp64ConditionalCompare ->
                    constructFp64ConditionalCompare(mv, fp64ConditionalCompare);
            case Ir64Op.Fp64Round fp64Round -> constructFp64Round(mv, fp64Round);
            case Ir64Op.Fp64IntegerConvert fp64IntegerConvert -> constructFp64IntegerConvert(mv, fp64IntegerConvert);
            case Ir64Op.Fp64GeneralRegisterMove fp64GeneralRegisterMove ->
                    constructFp64GeneralRegisterMove(mv, fp64GeneralRegisterMove);
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
    private static final String IR64_FP64_OPERATION = IR64_OP + "$Fp64Operation";
    private static final String IR64_FP64_CONVERSION = IR64_OP + "$Fp64Conversion";
    private static final String IR64_FP64_ROUNDING_DIRECTION = IR64_OP + "$Fp64RoundingDirection";
    private static final String IR64_LOGICAL_SHIFT_TYPE = "dev/vitorsilverio/armjitter/ir64/Ir64LogicalShiftType";
    private static final String IR64_ONE_SOURCE_OP = "dev/vitorsilverio/armjitter/ir64/Ir64OneSourceOp";
    private static final String IR64_ATOMIC_OP = "dev/vitorsilverio/armjitter/ir64/Ir64AtomicOp";
    private static final String IR64_FLAG_CONVERSION_OP = "dev/vitorsilverio/armjitter/ir64/Ir64FlagConversionOp";

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
        emitBoolean(mv, op.signExtend());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(ZIIIZL" + IR64_ADDRESSING_MODE + ";JZ)V", false);
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

    private void constructFp64Alu(MethodVisitor mv, Ir64Op.Fp64Alu op) {
        String type = IR64_OP + "$Fp64Alu";
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

    private void constructFp64MoveImmediate(MethodVisitor mv, Ir64Op.Fp64MoveImmediate op) {
        String type = IR64_OP + "$Fp64MoveImmediate";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.immediateBits());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIJ)V", false);
    }

    private void constructFp64Compare(MethodVisitor mv, Ir64Op.Fp64Compare op) {
        String type = IR64_OP + "$Fp64Compare";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.doublePrecision());
        emitBoolean(mv, op.compareWithZero());
        emitBoolean(mv, op.signalOnQuietNaN());
        mv.visitLdcInsn(op.vn());
        mv.visitLdcInsn(op.vm());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZZII)V", false);
    }

    private void constructFp64Convert(MethodVisitor mv, Ir64Op.Fp64Convert op) {
        String type = IR64_OP + "$Fp64Convert";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FP64_CONVERSION, op.conversion().name());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vm());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FP64_CONVERSION + ";II)V", false);
    }

    private void constructConditionalCompare(MethodVisitor mv, Ir64Op.ConditionalCompare op) {
        String type = IR64_OP + "$ConditionalCompare";
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

    private void constructLogicalShiftedRegister(MethodVisitor mv, Ir64Op.LogicalShiftedRegister op) {
        String type = IR64_OP + "$LogicalShiftedRegister";
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

    private void constructShiftVariable(MethodVisitor mv, Ir64Op.ShiftVariable op) {
        String type = IR64_OP + "$ShiftVariable";
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

    private void constructAluWithCarry(MethodVisitor mv, Ir64Op.AluWithCarry op) {
        String type = IR64_OP + "$AluWithCarry";
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

    private void constructExtract(MethodVisitor mv, Ir64Op.Extract op) {
        String type = IR64_OP + "$Extract";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitLdcInsn(op.lsb());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIIZ)V", false);
    }

    private void constructDataProcessing1Source(MethodVisitor mv, Ir64Op.DataProcessing1Source op) {
        String type = IR64_OP + "$DataProcessing1Source";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_ONE_SOURCE_OP, op.opcode().name());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_ONE_SOURCE_OP + ";IIZ)V", false);
    }

    private void constructMultiplyAccumulateLong(MethodVisitor mv, Ir64Op.MultiplyAccumulateLong op) {
        String type = IR64_OP + "$MultiplyAccumulateLong";
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

    private void constructMultiplyHigh(MethodVisitor mv, Ir64Op.MultiplyHigh op) {
        String type = IR64_OP + "$MultiplyHigh";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.signed());
        mv.visitLdcInsn(op.dst());
        mv.visitLdcInsn(op.src1());
        mv.visitLdcInsn(op.src2());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZIII)V", false);
    }

    private void constructCompareAndSwap(MethodVisitor mv, Ir64Op.CompareAndSwap op) {
        String type = IR64_OP + "$CompareAndSwap";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitEnumConstant(mv, IR64_MEM_SIZE, op.size().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(III" + "L" + IR64_MEM_SIZE + ";)V", false);
    }

    private void constructCompareAndSwapPair(MethodVisitor mv, Ir64Op.CompareAndSwapPair op) {
        String type = IR64_OP + "$CompareAndSwapPair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rs());
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIZ)V", false);
    }

    private void constructLoadExclusivePair(MethodVisitor mv, Ir64Op.LoadExclusivePair op) {
        String type = IR64_OP + "$LoadExclusivePair";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rt());
        mv.visitLdcInsn(op.rt2());
        mv.visitLdcInsn(op.rn());
        emitBoolean(mv, op.wide());
        emitBoolean(mv, op.acquireRelease());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(IIIZZ)V", false);
    }

    private void constructStoreExclusivePair(MethodVisitor mv, Ir64Op.StoreExclusivePair op) {
        String type = IR64_OP + "$StoreExclusivePair";
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

    private void constructAtomicMemoryOp(MethodVisitor mv, Ir64Op.AtomicMemoryOp op) {
        String type = IR64_OP + "$AtomicMemoryOp";
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

    private void constructEvaluateIntoFlags(MethodVisitor mv, Ir64Op.EvaluateIntoFlags op) {
        String type = IR64_OP + "$EvaluateIntoFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.sizeBits());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(II)V", false);
    }

    private void constructRotateIntoFlags(MethodVisitor mv, Ir64Op.RotateIntoFlags op) {
        String type = IR64_OP + "$RotateIntoFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(op.rn());
        mv.visitLdcInsn(op.shift());
        mv.visitLdcInsn(op.mask());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(III)V", false);
    }

    private void constructConvertFlags(MethodVisitor mv, Ir64Op.ConvertFlags op) {
        String type = IR64_OP + "$ConvertFlags";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FLAG_CONVERSION_OP, op.opcode().name());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FLAG_CONVERSION_OP + ";)V", false);
    }

    private void constructFp64MultiplyAdd(MethodVisitor mv, Ir64Op.Fp64MultiplyAdd op) {
        String type = IR64_OP + "$Fp64MultiplyAdd";
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

    private void constructFp64ConditionalSelect(MethodVisitor mv, Ir64Op.Fp64ConditionalSelect op) {
        String type = IR64_OP + "$Fp64ConditionalSelect";
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

    private void constructFp64ConditionalCompare(MethodVisitor mv, Ir64Op.Fp64ConditionalCompare op) {
        String type = IR64_OP + "$Fp64ConditionalCompare";
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

    private void constructFp64Round(MethodVisitor mv, Ir64Op.Fp64Round op) {
        String type = IR64_OP + "$Fp64Round";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitEnumConstant(mv, IR64_FP64_ROUNDING_DIRECTION, op.direction().name());
        emitBoolean(mv, op.doublePrecision());
        mv.visitLdcInsn(op.vd());
        mv.visitLdcInsn(op.vn());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>",
                "(L" + IR64_FP64_ROUNDING_DIRECTION + ";ZII)V", false);
    }

    private void constructFp64IntegerConvert(MethodVisitor mv, Ir64Op.Fp64IntegerConvert op) {
        String type = IR64_OP + "$Fp64IntegerConvert";
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

    private void constructFp64GeneralRegisterMove(MethodVisitor mv, Ir64Op.Fp64GeneralRegisterMove op) {
        String type = IR64_OP + "$Fp64GeneralRegisterMove";
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        emitBoolean(mv, op.toFloat());
        emitBoolean(mv, op.wide());
        mv.visitLdcInsn(op.fpReg());
        mv.visitLdcInsn(op.gpReg());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "(ZZII)V", false);
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
