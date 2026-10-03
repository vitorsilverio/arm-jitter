package dev.vitorsilverio.armjitter.codegen.jvm;

import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CACHEABLE_REGISTERS;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CACHE_BASE_LOCAL;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CORE;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CORE_LOCAL;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CORE_REF;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.CYCLES_LOCAL;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.FLAG_HELPERS;
import static dev.vitorsilverio.armjitter.codegen.jvm.AsmEmitterBase.PC_CHANGED_LOCAL;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/// Compila blocos IR suportados por {@link AsmNativePolicy} em bytecode JVM.
///
/// Cada op é emitida pela {@link AsmEmission} do seu `record` no {@link AsmEmitterRegistry}; os
/// emissores ficam nas classes de família (`AsmAluEmitter`, `AsmIntegerEmitter`, `AsmMemoryEmitter`,
/// `AsmControlEmitter`, `AsmVfpEmitter`). Esta classe é o laço: guard condicional por op, spill,
/// fallback por op, register cache e os handlers de abort de memória.
///
/// Convenção de locais do método gerado (constantes em {@link AsmEmitterBase}):
/// <pre>
///   0 = ArmCore (parâmetro)
///   1 = cycles acumulados (int)
///   2 = pc_changed flag (int, 0 ou 1)
///   3 = TEMP1 — uso geral (base, left, result, etc.)
///   4 = TEMP2 — uso geral (offset, right, carry, etc.)
///   5 = TEMP3 — uso interno de emitStoreRegister
///   6 = ADDR  — endereço calculado (load/store)
///   7+8 = LONG_RESULT — resultado de 64 bits (LongMultiply)
///   9+  = registradores guest cacheados (ver {@link AsmRegCache})
/// </pre>
///
/// **Register cache:** registradores guest (r0–r14) acessados ≥2 vezes pelo bloco vivem em locais
/// JVM: carregados uma vez no prólogo, lidos/escritos como locais pelos ops "simples"
/// (ALU/Load/Store/Branch/BX/Thumb-BL/Multiply), e descarregados (`flush`) de volta ao core no fim
/// do bloco. Ops cujos HELPERS leem/escrevem registradores no core diretamente (LDM/STM, Push/Pop,
/// SWI, MSR/MRS, coprocessador, Undefined e o fallback PER_OP interpretado) são cercados por
/// flush + reload — o cache nunca fica stale através deles. r15 nunca é cacheado (o PC só é
/// materializado por helpers/fixup). O guard condicional não afeta o invariante: um op pulado não
/// toca os locais, então `local == core.register(r)` continua valendo nos dois ramos do merge.
///
/// Instâncias NÃO são thread-safe (estado por-compilação em {@link AsmEmitState}); cada emissor usa
/// o seu compilador numa única thread (emu ou a thread única de compilação em background).
public final class AsmBlockCompiler {
    private static final String EXECUTE = "execute";
    /// Método estático com o corpo gerado (mantém `core` no slot 0); o método de instância
    /// {@link #EXECUTE} (que implementa {@link dev.vitorsilverio.armjitter.jit.CompiledBlock})
    /// apenas delega a ele. Evita o overhead de `MethodHandle.invokeExact` por execução.
    private static final String EXECUTE_IMPL = "execute0";
    private static final String EXECUTE_DESCRIPTOR = "(L" + GuestToHostMapper.ARM_CORE + ";)I";
    private static final String COMPILED_BLOCK = "dev/vitorsilverio/armjitter/jit/CompiledBlock";
    /// B4.1.3 (RFC-SOFTMMU §3): exceção que `TranslatingAddressSpace` lança numa falta de tradução;
    /// capturada pelo bloco compilado inteiro (ver {@link #compile}).
    private static final String MEMORY_TRANSLATION_EXCEPTION = "dev/vitorsilverio/armjitter/memory/mmu/MemoryTranslationException";
    private static final String ENTER_MEMORY_ABORT_DESCRIPTOR =
            "(IL" + MEMORY_TRANSLATION_EXCEPTION + ";)V";
    /// B20.3: exceção que `PmsaAddressSpace` lança numa falta de permissão/background PMSAv7 —
    /// classe irmã de {@link #MEMORY_TRANSLATION_EXCEPTION}, catch à parte no bloco compilado
    /// (Armadilha 4 da B20.3, mesmo padrão da B4.1.3, ver {@link #compile}).
    private static final String PMSA_ACCESS_EXCEPTION = "dev/vitorsilverio/armjitter/memory/mpu/PmsaAccessException";
    private static final String ENTER_PMSA_ABORT_DESCRIPTOR =
            "(IL" + PMSA_ACCESS_EXCEPTION + ";)V";
    /// B20.7: exceção que `Pmsav8AddressSpace` lança numa falta de permissão PMSAv8-32 — terceira
    /// classe irmã, catch à parte no bloco compilado (mesmo padrão de {@link #PMSA_ACCESS_EXCEPTION}).
    private static final String PMSAV8_ACCESS_EXCEPTION = "dev/vitorsilverio/armjitter/memory/mpu/Pmsav8AccessException";
    private static final String ENTER_PMSAV8_ABORT_DESCRIPTOR =
            "(IL" + PMSAV8_ACCESS_EXCEPTION + ";)V";

    /// B4.1.3: endereço da instrução dona da op corrente, atualizado a cada iteração do laço de
    /// emissão (LDC do endereço computado em tempo de COMPILAÇÃO por {@link #computeInstructionAddresses}
    /// + ISTORE) — lido pelo handler de {@link #MEMORY_TRANSLATION_EXCEPTION} para materializar o
    /// PC antes de {@code core.enterMemoryAbort}. Slot fixo acima de toda a faixa dinâmica do
    /// register cache (`CACHE_BASE_LOCAL` + até 15 registradores), nunca colide com ela.
    private static final int FAULT_PC_LOCAL = CACHE_BASE_LOCAL + CACHEABLE_REGISTERS;
    /// B4.1.3: referência da exceção capturada pelo handler (ASTORE), usada só ali.
    private static final int FAULT_EXCEPTION_LOCAL = FAULT_PC_LOCAL + 1;

    /// Flags da arquitetura + estado por compilação, compartilhados com os emissores.
    private final AsmEmitState state;
    private final AsmEmitters emitters;

    /// Cria um compilador ARMv4T (sem interworking em load->PC, sem acesso desalinhado atravessado).
    public AsmBlockCompiler() {
        this(false, new IrBlockExecutor(ArmArchitecture.ARMV4T));
    }

    /// Cria um compilador para a arquitetura informada via flag de interworking em load->PC,
    /// com um executor ARMv4T padrão para o fallback PER_OP. Sem acesso desalinhado atravessado —
    /// use o construtor de 3 argumentos para ligá-lo.
    public AsmBlockCompiler(boolean loadPcInterworks) {
        this(loadPcInterworks, new IrBlockExecutor(ArmArchitecture.ARMV4T));
    }

    /// Cria um compilador com o executor interpretado da sua arquitetura (usado no fallback
    /// PER_OP). Sem acesso desalinhado atravessado — use o construtor de 3 argumentos para ligá-lo.
    public AsmBlockCompiler(boolean loadPcInterworks, IrBlockExecutor perOpExecutor) {
        this(loadPcInterworks, false, perOpExecutor);
    }

    /// Cria um compilador completo, incluindo a flag de {@link ArmFeature#UNALIGNED_ACCESS}
    /// (task B1.7) que decide se `LDR`/`STR`/`LDRH`/`STRH` emitem os helpers de acesso
    /// desalinhado atravessado ("Crossed").
    public AsmBlockCompiler(boolean loadPcInterworks, boolean unalignedAccess, IrBlockExecutor perOpExecutor) {
        this.state = new AsmEmitState(loadPcInterworks, unalignedAccess, perOpExecutor);
        this.emitters = AsmEmitters.of(state);
    }

    /// Helper de guard especializado para uma condição (ver os `condXx` dos helpers de runtime).
    private static String condHelperName(Condition cond) {
        return switch (cond) {
            case EQ -> "condEq";
            case NE -> "condNe";
            case CS -> "condCs";
            case CC -> "condCc";
            case MI -> "condMi";
            case PL -> "condPl";
            case VS -> "condVs";
            case VC -> "condVc";
            case HI -> "condHi";
            case LS -> "condLs";
            case GE -> "condGe";
            case LT -> "condLt";
            case GT -> "condGt";
            case LE -> "condLe";
            case AL -> throw new IllegalStateException("condição AL nunca é guardada");
        };
    }

    /// Compila o bloco em bytecode JVM. Todos os ops devem ser suportados por {@link AsmNativePolicy};
    /// ops não suportadas lançam {@link IllegalStateException}.
    public byte[] compile(String internalName, IrBlock block) {
        return compile(internalName, block, false);
    }

    /// Compila o bloco em bytecode JVM no modo PER_OP: ops não suportadas por {@link AsmNativePolicy}
    /// são despachadas ao interpretado via {@link IrOpInterop#executeInterpreted} inline no bytecode.
    public byte[] compilePerOp(String internalName, IrBlock block) {
        return compile(internalName, block, true);
    }

    private byte[] compile(String internalName, IrBlock block, boolean perOpFallback) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        // A classe implementa CompiledBlock: o runtime a executa por chamada virtual direta
        // (block.execute(core)), sem MethodHandle.
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", new String[]{COMPILED_BLOCK});
        emitConstructor(writer);
        emitExecuteBridge(writer, internalName);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, EXECUTE_IMPL, EXECUTE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);

        AsmEmitterBase base = emitters.base();
        state.cache = buildRegCache(block, perOpFallback);
        state.blockEndPc = block.endPc();
        base.emitCachePrologue(method);

        // B4.1.3 (RFC-SOFTMMU §3): o bloco inteiro é cercado por um try/catch de
        // MemoryTranslationException — uma região `try` sem lançamento não custa nada em bytecode
        // JVM executado (só o `throw` em si tem custo, e faltas de tradução são raras por
        // natureza), então isto não afeta o caminho quente sem MMU. `instructionAddresses[i]`
        // (computado em tempo de COMPILAÇÃO, não de execução) é gravado em FAULT_PC_LOCAL a cada
        // op — 2 bytecodes (LDC+ISTORE) por op, também sem custo de chamada.
        int[] instructionAddresses = computeInstructionAddresses(block);
        List<IrOp> blockOps = block.operations();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label abortHandler = new Label();
        Label pmsaAbortHandler = new Label();
        Label pmsav8AbortHandler = new Label();
        method.visitTryCatchBlock(tryStart, tryEnd, abortHandler, MEMORY_TRANSLATION_EXCEPTION);
        method.visitTryCatchBlock(tryStart, tryEnd, pmsaAbortHandler, PMSA_ACCESS_EXCEPTION);
        method.visitTryCatchBlock(tryStart, tryEnd, pmsav8AbortHandler, PMSAV8_ACCESS_EXCEPTION);
        // FAULT_PC_LOCAL precisa de um valor ANTES de `tryStart`: o verificador da JVM trata o
        // handler como alcançável a partir de QUALQUER bytecode dentro do range protegido,
        // inclusive o primeiro — sem este ISTORE aqui fora, o slot chegaria como `top` no merge
        // do handler (mesmo a op#0 já regravando o slot logo depois de `tryStart`).
        AsmBytecode.visitIntConst(method, block.startPc());
        method.visitVarInsn(Opcodes.ISTORE, FAULT_PC_LOCAL);
        method.visitLabel(tryStart);
        for (int opIndex = 0; opIndex < blockOps.size(); opIndex++) {
            IrOp op = blockOps.get(opIndex);
            AsmBytecode.visitIntConst(method, instructionAddresses[opIndex]);
            method.visitVarInsn(Opcodes.ISTORE, FAULT_PC_LOCAL);
            AsmEmission<?> emission = AsmEmitterRegistry.lookup(op);
            if (perOpFallback && (emission == null || !emission.accepts(op))) {
                // O interpretado lê/escreve registradores no core: flush antes, reload depois.
                base.emitCacheFlush(method);
                base.emitPerOpFallback(method, op, block.endPc());
                base.emitCacheReload(method);
                continue;
            }
            // Guard condicional por-op: espelha o `if (!evalCond(op.condition())) return false;` no
            // topo de cada executor interpretado. `Cycle`/`Fetch` têm condição AL (default) e NUNCA
            // são guardados — uma instrução de condição falsa ainda consome o ciclo S + o fetch (ops
            // AL separados, rodados incondicionalmente como no interpretador). Ops não-suportadas do
            // caminho PER_OP já checam a condição dentro do interpretado e saíram pelo `continue`.
            Condition cond = op.condition();
            Label condSkip = null;
            if (cond != Condition.AL) {
                condSkip = new Label();
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                // Guard ESPECIALIZADO pela condição em tempo de compilação (condEq/condNe/...):
                // elimina o switch-por-execução de evalCond; inlina a um teste de bits do CPSR.
                AsmBytecode.invokeStatic(method, FLAG_HELPERS, condHelperName(cond), "(" + CORE_REF + ")Z");
                method.visitJumpInsn(Opcodes.IFEQ, condSkip);
            }
            // Sem PER_OP a op é emitida mesmo que o predicado da entrada a recuse (quem chama checa
            // a política antes); só a ausência de entrada é erro.
            if (emission == null) {
                throw new IllegalStateException("Unsupported IR op in native compile: " + op);
            }
            if (emission.spill()) {
                base.emitSpilled(method, () -> emission.emit(emitters, method, op));
            } else {
                emission.emit(emitters, method, op);
            }
            // Op pulado (condição falsa) cai aqui sem tocar PC_CHANGED — `emitProgramCounterFixup`
            // põe PC=endPc (sequencial), idêntico ao `return false` do executor interpretado.
            // Cada `emitXxx` termina com a pilha JVM vazia, então o merge no label é consistente
            // (o ClassWriter usa COMPUTE_FRAMES; locais escritos só em um ramo viram TOP, mas todo
            // temp é escrito-antes-de-ler dentro de cada `emitXxx`, nunca lido através do merge).
            if (condSkip != null) {
                method.visitLabel(condSkip);
            }
        }
        method.visitLabel(tryEnd);

        base.emitCacheFlush(method);
        emitProgramCounterFixup(method, block.endPc());
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        method.visitInsn(Opcodes.IRETURN);

        // B4.1.3: handler da falta de tradução — flush do cache (os locais de registrador
        // sobrevivem ao unwind DENTRO do mesmo frame JVM, então registradores já escritos antes da
        // falta, ex. no meio de um LDM desenrolado, chegam ao core: semântica base-restored do
        // RFC §3 cai de graça, já que o writeback da base sempre é emitido DEPOIS do laço de
        // registradores em emitMultipleTransferInline, então nunca roda se a falta interrompeu o
        // laço) + core.enterMemoryAbort(FAULT_PC_LOCAL, exceção) + retorno com os ciclos parciais.
        emitAbortHandler(method, abortHandler, "enterMemoryAbort", ENTER_MEMORY_ABORT_DESCRIPTOR);
        // B20.3: handler irmão do acima, para PmsaAccessException (Armadilha 4 da B20.3) — mesma
        // semântica base-restored (o cache já foi flushado até onde o laço chegou antes da falta).
        emitAbortHandler(method, pmsaAbortHandler, "enterPmsaAbort", ENTER_PMSA_ABORT_DESCRIPTOR);
        // B20.7: handler irmão dos dois acima, para Pmsav8AccessException (mesma semântica
        // base-restored).
        emitAbortHandler(method, pmsav8AbortHandler, "enterPmsav8Abort", ENTER_PMSAV8_ABORT_DESCRIPTOR);

        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        state.cache = AsmRegCache.EMPTY;
        return writer.toByteArray();
    }

    /// Handler de uma exceção de abort de memória: flush do cache, `core.<enterAbort>(FAULT_PC,
    /// exceção)` e retorno com os ciclos parciais.
    private void emitAbortHandler(MethodVisitor method, Label handler, String enterAbort, String descriptor) {
        method.visitLabel(handler);
        method.visitVarInsn(Opcodes.ASTORE, FAULT_EXCEPTION_LOCAL);
        emitters.base().emitCacheFlush(method);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, FAULT_PC_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, FAULT_EXCEPTION_LOCAL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CORE, enterAbort, descriptor, false);
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        method.visitInsn(Opcodes.IRETURN);
    }

    /// B4.1.3: endereço da instrução dona de cada op do bloco, indexado igual a
    /// {@code block.operations()}. Cada instrução termina SEMPRE com {@code Cycle}+{@code Fetch}
    /// (G4, ver `StandardIrBuilder`), então uma varredura de trás para frente propaga o endereço
    /// do próximo `Fetch` (inclusive ele mesmo) para toda op anterior a ele — computado UMA vez
    /// por compilação, não por execução do bloco compilado.
    private static int[] computeInstructionAddresses(IrBlock block) {
        List<IrOp> ops = block.operations();
        int[] addresses = new int[ops.size()];
        int currentAddress = block.endPc();
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (ops.get(i) instanceof IrOp.Fetch fetch) {
                currentAddress = fetch.address();
            }
            addresses[i] = currentAddress;
        }
        return addresses;
    }

    // ── register cache ─────────────────────────────────────────────────────────

    /// Analisa o bloco e decide quais registradores guest viram locais JVM: os com ≥2 acessos
    /// pelos ops de emissão direta (um único acesso não paga o prólogo). Acessos feitos DENTRO de
    /// helpers (LDM/STM/Push/Pop/SWI/PSR/coprocessador/Undefined/fallback PER_OP) não contam —
    /// esses ops são cercados por flush/reload e continuam lendo o core diretamente.
    private static AsmRegCache buildRegCache(IrBlock block, boolean perOpFallback) {
        AsmAccessCounter counter = new AsmAccessCounter();
        for (IrOp op : block.operations()) {
            AsmEmission<?> emission = AsmEmitterRegistry.lookup(op);
            if (emission == null || (perOpFallback && !emission.accepts(op))) {
                continue; // vai pelo interpretado (flush/reload em volta)
            }
            emission.countAccesses(op, counter);
        }
        AsmRegCache cache = new AsmRegCache();
        int next = CACHE_BASE_LOCAL;
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (counter.accesses[reg] >= 2) {
                cache.slot[reg] = next++;
                cache.dirty[reg] = counter.writes[reg];
            }
        }
        return cache;
    }

    /// Construtor público sem-arg (o {@link JvmBlockLoader} instancia a classe).
    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    /// Método de instância `int execute(ArmCore)` que implementa {@link CompiledBlock}, delegando
    /// ao estático {@link #EXECUTE_IMPL} (locais: this=0, core=1).
    private static void emitExecuteBridge(ClassWriter writer, String internalName) {
        MethodVisitor bridge = writer.visitMethod(Opcodes.ACC_PUBLIC, EXECUTE, EXECUTE_DESCRIPTOR, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, EXECUTE_IMPL, EXECUTE_DESCRIPTOR, false);
        bridge.visitInsn(Opcodes.IRETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
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
