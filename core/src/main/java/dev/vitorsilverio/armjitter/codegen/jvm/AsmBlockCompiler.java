package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/// Compila blocos IR suportados por {@link AsmNativePolicy} em bytecode JVM.
///
/// Convenção de locais do método gerado:
/// <pre>
///   0 = ArmCore (parâmetro)
///   1 = cycles acumulados (int)
///   2 = pc_changed flag (int, 0 ou 1)
///   3 = TEMP1 — uso geral (base, left, result, etc.)
///   4 = TEMP2 — uso geral (offset, right, carry, etc.)
///   5 = TEMP3 — uso interno de emitStoreRegister
///   6 = ADDR  — endereço calculado (load/store)
///   7+8 = LONG_RESULT — resultado de 64 bits (LongMultiply)
///   9+  = registradores guest cacheados (ver {@link RegCache})
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
/// Instâncias NÃO são thread-safe (estado por-compilação em {@link #cache}); cada emissor usa o
/// seu compilador numa única thread (emu ou a thread única de compilação em background).
public final class AsmBlockCompiler {
    private static final String EXECUTE = "execute";
    /// Método estático com o corpo gerado (mantém `core` no slot 0); o método de instância
    /// {@link #EXECUTE} (que implementa {@link dev.vitorsilverio.armjitter.jit.CompiledBlock})
    /// apenas delega a ele. Evita o overhead de `MethodHandle.invokeExact` por execução.
    private static final String EXECUTE_IMPL = "execute0";
    private static final String EXECUTE_DESCRIPTOR = "(L" + GuestToHostMapper.ARM_CORE + ";)I";
    private static final String COMPILED_BLOCK = "dev/vitorsilverio/armjitter/jit/CompiledBlock";
    private static final String CORE = GuestToHostMapper.ARM_CORE;
    private static final String CORE_REF = "L" + CORE + ";";
    private static final String HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmRuntimeHelpers";
    private static final String INTEGER_CLASS = "java/lang/Integer";
    /// B4.1.3 (RFC-SOFTMMU §3): exceção que `TranslatingAddressSpace` lança numa falta de tradução;
    /// capturada pelo bloco compilado inteiro (ver {@link #compile}).
    private static final String MEMORY_TRANSLATION_EXCEPTION = "dev/vitorsilverio/armjitter/memory/mmu/MemoryTranslationException";
    private static final String ENTER_MEMORY_ABORT_DESCRIPTOR =
            "(IL" + MEMORY_TRANSLATION_EXCEPTION + ";)V";

    // Slots
    private static final int CORE_LOCAL = 0;
    private static final int CYCLES_LOCAL = 1;
    private static final int PC_CHANGED_LOCAL = 2;
    private static final int TEMP1_LOCAL = 3;
    private static final int TEMP2_LOCAL = 4;
    private static final int TEMP3_LOCAL = 5;  // used internally by emitStoreRegister
    private static final int ADDR_LOCAL = 6;
    private static final int LONG_RESULT_LOCAL = 7;  // long occupies slots 7+8
    private static final int CACHE_BASE_LOCAL = 9;   // first guest-register cache slot

    // Registradores guest com papel arquitetural fixo.
    private static final int SP_REGISTER = 13;
    private static final int LR_REGISTER = 14;
    private static final int PC_REGISTER = 15;
    /// r0..r14 são cacheáveis; r15 (o PC) nunca é — só é materializado por helpers/fixup.
    private static final int CACHEABLE_REGISTERS = 15;
    /// B4.1.3: endereço da instrução dona da op corrente, atualizado a cada iteração do laço de
    /// emissão (LDC do endereço computado em tempo de COMPILAÇÃO por {@link #computeInstructionAddresses}
    /// + ISTORE) — lido pelo handler de {@link #MEMORY_TRANSLATION_EXCEPTION} para materializar o
    /// PC antes de {@code core.enterMemoryAbort}. Slot fixo acima de toda a faixa dinâmica do
    /// register cache (`CACHE_BASE_LOCAL` + até 15 registradores), nunca colide com ela.
    private static final int FAULT_PC_LOCAL = CACHE_BASE_LOCAL + CACHEABLE_REGISTERS;
    /// B4.1.3: referência da exceção capturada pelo handler (ASTORE), usada só ali.
    private static final int FAULT_EXCEPTION_LOCAL = FAULT_PC_LOCAL + 1;

    // Descritores para helpers que compartilham a mesma assinatura
    private static final String CORE_I_TO_I = "(" + CORE_REF + "I)I";
    private static final String CORE_II_TO_V = "(" + CORE_REF + "II)V";
    private static final String CORE_I_TO_V = "(" + CORE_REF + "I)V";
    private static final String CORE_IZ_TO_V = "(" + CORE_REF + "IZ)V";
    private static final String CORE_IZ_TO_Z = "(" + CORE_REF + "IZ)Z";
    private static final String CORE_IZZ_TO_Z = "(" + CORE_REF + "IZZ)Z";

    /// `true` em ARMv5T+: LDR/LDM/POP para PC interworkam pelo bit 0 do valor carregado.
    /// Decidido na construção (a partir da arquitetura do emissor); ARMv4T usa `false`.
    private final boolean loadPcInterworks;

    /// `true` sob {@link dev.vitorsilverio.armjitter.arch.ArmFeature#UNALIGNED_ACCESS} (ARMv6+):
    /// `LDR`/`STR`/`LDRH`/`STRH` com destino diferente do PC emitem os helpers "Crossed" de
    /// {@link AsmRuntimeHelpers} (acesso atravessado) em vez dos legados de alinha+rotaciona.
    /// Decidido na construção a partir da arquitetura do emissor; ARMv4T/v5TE usam `false` (G2).
    /// LDM/STM/LDRD/STRD/LDREX/STREX/SWP nunca consultam esta flag — continuam chamando os
    /// helpers legados incondicionalmente (task B1.7, item 4).
    private final boolean unalignedAccess;

    /// Executor interpretado da arquitetura deste compilador, registrado com cada op de fallback
    /// PER_OP para que ela rode com a semântica correta (ver {@link IrOpInterop}).
    private final IrBlockExecutor perOpExecutor;

    /// Cache de registradores guest do bloco em compilação (ver doc da classe). Estado
    /// por-compilação: atribuído no início de {@link #compile} e lido pelos `emitXxx`.
    private RegCache cache = RegCache.EMPTY;

    /// Mapa registrador-guest → local JVM do bloco corrente + flag estático de escrita.
    private static final class RegCache {
        static final RegCache EMPTY = new RegCache();
        final int[] slot = new int[CACHEABLE_REGISTERS];      // r0..r14; -1 = não cacheado
        final boolean[] dirty = new boolean[CACHEABLE_REGISTERS]; // o bloco PODE escrever o reg

        RegCache() {
            java.util.Arrays.fill(slot, -1);
        }

        boolean cached(int reg) {
            return reg >= 0 && reg < CACHEABLE_REGISTERS && slot[reg] >= 0;
        }
    }

    /// Cria um compilador ARMv4T (sem interworking em load->PC, sem acesso desalinhado atravessado).
    public AsmBlockCompiler() {
        this(false, new IrBlockExecutor(ArmArchitecture.ARMV4T));
    }

    /// Cria um compilador para a arquitetura informada via flag de interworking em load->PC,
    /// com um executor ARMv4T padrão para o fallback PER_OP. Sem acesso desalinhado atravessado
    /// ({@link #unalignedAccess} `false`) — use o construtor de 3 argumentos para ligá-lo.
    public AsmBlockCompiler(boolean loadPcInterworks) {
        this(loadPcInterworks, new IrBlockExecutor(ArmArchitecture.ARMV4T));
    }

    /// Cria um compilador com o executor interpretado da sua arquitetura (usado no fallback
    /// PER_OP). Sem acesso desalinhado atravessado — use o construtor de 4 argumentos para ligá-lo.
    public AsmBlockCompiler(boolean loadPcInterworks, IrBlockExecutor perOpExecutor) {
        this(loadPcInterworks, false, perOpExecutor);
    }

    /// Cria um compilador completo, incluindo a flag de {@link ArmFeature#UNALIGNED_ACCESS}
    /// (task B1.7) que decide se `LDR`/`STR`/`LDRH`/`STRH` emitem os helpers "Crossed" de
    /// {@link AsmRuntimeHelpers}.
    public AsmBlockCompiler(boolean loadPcInterworks, boolean unalignedAccess, IrBlockExecutor perOpExecutor) {
        this.loadPcInterworks = loadPcInterworks;
        this.unalignedAccess = unalignedAccess;
        this.perOpExecutor = perOpExecutor;
    }

    /// Emite `loadPcInterworks` seguido da chamada a {@code AsmRuntimeHelpers#loadToPc(core,
    /// value, interwork)} — pilha já deve ter `core, value` empilhados. Usado por TODO
    /// load-to-PC vindo de memória/pilha (LDR/LDR literal/POP inline; POP/LDM em bloco chamam o
    /// mesmo helper por dentro de `executePop`/`executeMultipleTransfer`). Passa pelo intercept
    /// do `ExceptionModel` (B7.1) — diferente de `MOV pc,...`, que chama {@code loadToPcArm4}
    /// diretamente e nunca intercepta.
    private void emitLoadToPcFromMemory(MethodVisitor method) {
        method.visitInsn(loadPcInterworks ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "loadToPc", CORE_IZ_TO_V);
    }

    /// Helper de guard especializado para uma condição (ver os `condXx` em AsmRuntimeHelpers).
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

        cache = buildRegCache(block, perOpFallback);
        emitCachePrologue(method);

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
        method.visitTryCatchBlock(tryStart, tryEnd, abortHandler, MEMORY_TRANSLATION_EXCEPTION);
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
            if (perOpFallback && !AsmNativePolicy.supports(op)) {
                // O interpretado lê/escreve registradores no core: flush antes, reload depois.
                emitCacheFlush(method);
                emitPerOpFallback(method, op, block.endPc());
                emitCacheReload(method);
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
                AsmBytecode.invokeStatic(method, HELPERS, condHelperName(cond), "(" + CORE_REF + ")Z");
                method.visitJumpInsn(Opcodes.IFEQ, condSkip);
            }
            switch (op) {
                case IrOp.Alu alu -> emitAlu(method, alu);
                case IrOp.Multiply mul -> emitMultiply(method, mul);
                case IrOp.LongMultiply mul -> emitLongMultiply(method, mul);
                case IrOp.Saturating sat -> emitSaturating(method, sat);
                case IrOp.DspMultiply dsp -> emitDspMultiply(method, dsp);
                case IrOp.DoubleTransfer dt -> emitDoubleTransfer(method, dt);
                case IrOp.ParallelAlu pa -> emitParallelAlu(method, pa);
                case IrOp.Sel sel -> emitSel(method, sel);
                case IrOp.Saturate sat2 -> emitSaturate(method, sat2);
                case IrOp.AbsDiffSum ads -> emitAbsDiffSum(method, ads);
                case IrOp.LoadExclusive ldrex -> emitLoadExclusive(method, ldrex);
                case IrOp.StoreExclusive strex -> emitStoreExclusive(method, strex);
                case IrOp.ClearExclusive clrex -> emitClearExclusive(method, clrex);
                case IrOp.Load load -> emitLoad(method, load);
                case IrOp.Store store -> emitStore(method, store);
                case IrOp.LoadLiteral lit -> emitLoadLiteral(method, lit);
                // LDM/STM/PUSH/POP: o caso comum é DESENROLADO inline (a lista de registradores é
                // constante de compilação) integrado ao register cache; só as formas raras
                // (user-mode, lista vazia, PC na lista) caem no helper, com flush + reload.
                case IrOp.MultipleTransfer mt -> {
                    if (canInlineMultipleTransfer(mt)) {
                        emitMultipleTransferInline(method, mt);
                    } else {
                        emitSpilled(method, () -> emitMultipleTransfer(method, mt));
                    }
                }
                case IrOp.Branch b -> emitBranch(method, b);
                case IrOp.BranchExchange bx -> emitBranchExchange(method, bx);
                case IrOp.ThumbBlPrefix prefix -> emitThumbBlPrefix(method, prefix);
                case IrOp.ThumbBlSuffix suffix -> emitThumbBlSuffix(method, suffix);
                case IrOp.Push push -> emitPushInline(method, push);
                case IrOp.Pop pop -> emitPopInline(method, pop);
                case IrOp.PsrTransfer psr -> emitSpilled(method, () -> emitPsrTransfer(method, psr));
                case IrOp.Swi swi -> emitSpilled(method, () -> emitSwi(method, swi, block.endPc()));
                case IrOp.Coprocessor cp -> emitSpilled(method, () -> emitCoprocessor(method, cp));
                case IrOp.CoprocessorDouble cp -> emitSpilled(method, () -> emitCoprocessorDouble(method, cp));
                case IrOp.Undefined undef -> emitSpilled(method, () -> emitUndefined(method, undef));
                case IrOp.Cycle cycle -> emitCycle(method, cycle);
                case IrOp.Fetch fetch -> emitFetch(method, fetch);
                case IrOp.BitFieldExtract bfx -> emitBitFieldExtract(method, bfx);
                case IrOp.BitFieldInsert bfi -> emitBitFieldInsert(method, bfi);
                case IrOp.BitReverse rbit -> emitBitReverse(method, rbit);
                case IrOp.Divide div -> emitDivide(method, div);
                case IrOp.MoveTop movt -> emitMoveTop(method, movt);
                case IrOp.MemoryBarrier ignored -> {
                    // NOP observável (ver IrOp.MemoryBarrier) — nenhum bytecode além do
                    // Cycle/Fetch já emitidos separadamente para esta instrução.
                }
                // VFP (B3.6, PR2): VfpAlu/VfpMoveImmediate/VfpLoad/VfpStore/VfpCoreTransfer são
                // bytecode direto (caminho quente); VfpCompare/VfpConvert chamam um helper (sem
                // tocar registrador ARM algum, sem spill); VfpMultipleTransfer/VfpCorePairTransfer/
                // VfpSystemTransfer chamam um helper que toca registrador(es) ARM DIRETAMENTE no
                // core (fora do register cache) — cercados por emitSpilled, mesmo tratamento de
                // PsrTransfer/Coprocessor acima.
                case IrOp.VfpAlu vfpAlu -> emitVfpAlu(method, vfpAlu);
                case IrOp.VfpMoveImmediate vfpMovImm -> emitVfpMoveImmediate(method, vfpMovImm);
                case IrOp.VfpCompare vfpCmp -> emitVfpCompare(method, vfpCmp);
                case IrOp.VfpConvert vfpCvt -> emitVfpConvert(method, vfpCvt);
                case IrOp.VfpLoad vfpLoad -> emitVfpLoad(method, vfpLoad);
                case IrOp.VfpStore vfpStore -> emitVfpStore(method, vfpStore);
                case IrOp.VfpMultipleTransfer vfpMt -> emitSpilled(method, () -> emitVfpMultipleTransfer(method, vfpMt));
                case IrOp.VfpCoreTransfer vfpCoreT -> emitVfpCoreTransfer(method, vfpCoreT);
                case IrOp.VfpCorePairTransfer vfpPair -> emitSpilled(method, () -> emitVfpCorePairTransfer(method, vfpPair));
                case IrOp.VfpSystemTransfer vfpSys -> emitSpilled(method, () -> emitVfpSystemTransfer(method, vfpSys));
                default -> throw new IllegalStateException("Unsupported IR op in native compile: " + op);
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

        emitCacheFlush(method);
        emitProgramCounterFixup(method, block.endPc());
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        method.visitInsn(Opcodes.IRETURN);

        // B4.1.3: handler da falta de tradução — flush do cache (os locais de registrador
        // sobrevivem ao unwind DENTRO do mesmo frame JVM, então registradores já escritos antes da
        // falta, ex. no meio de um LDM desenrolado, chegam ao core: semântica base-restored do
        // RFC §3 cai de graça, já que o writeback da base sempre é emitido DEPOIS do laço de
        // registradores em emitMultipleTransferInline, então nunca roda se a falta interrompeu o
        // laço) + core.enterMemoryAbort(FAULT_PC_LOCAL, exceção) + retorno com os ciclos parciais.
        method.visitLabel(abortHandler);
        method.visitVarInsn(Opcodes.ASTORE, FAULT_EXCEPTION_LOCAL);
        emitCacheFlush(method);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, FAULT_PC_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, FAULT_EXCEPTION_LOCAL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CORE, "enterMemoryAbort", ENTER_MEMORY_ABORT_DESCRIPTOR, false);
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        method.visitInsn(Opcodes.IRETURN);

        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        cache = RegCache.EMPTY;
        return writer.toByteArray();
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
    private static RegCache buildRegCache(IrBlock block, boolean perOpFallback) {
        int[] accesses = new int[15];
        boolean[] writes = new boolean[15];
        for (IrOp op : block.operations()) {
            if (perOpFallback && !AsmNativePolicy.supports(op)) {
                continue; // vai pelo interpretado (flush/reload em volta)
            }
            countAccesses(op, accesses, writes);
        }
        RegCache cache = new RegCache();
        int next = CACHE_BASE_LOCAL;
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (accesses[reg] >= 2) {
                cache.slot[reg] = next++;
                cache.dirty[reg] = writes[reg];
            }
        }
        return cache;
    }

    private static void countAccesses(IrOp op, int[] accesses, boolean[] writes) {
        switch (op) {
            case IrOp.Alu alu -> {
                if (aluUsesSrc1(alu.opcode()) && alu.src1ValueOverride() == -1) {
                    countRead(accesses, alu.src1());
                }
                countOperand(accesses, alu.src2());
                if (aluWritesDst(alu.opcode())) {
                    countWrite(accesses, writes, alu.dst());
                }
            }
            case IrOp.Multiply mul -> {
                if (mul.rmValueOverride() == -1) countRead(accesses, mul.rm());
                if (mul.rsValueOverride() == -1) countRead(accesses, mul.rs());
                if (mul.accumulate() && mul.rnValueOverride() == -1) countRead(accesses, mul.rn());
                countWrite(accesses, writes, mul.dst());
            }
            case IrOp.LongMultiply mul -> {
                if (mul.rmValueOverride() == -1) countRead(accesses, mul.rm());
                if (mul.rsValueOverride() == -1) countRead(accesses, mul.rs());
                if (mul.accumulate() || mul.accumulateDouble()) {
                    if (mul.dstLowValueOverride() == -1) countRead(accesses, mul.dstLow());
                    if (mul.dstHighValueOverride() == -1) countRead(accesses, mul.dstHigh());
                }
                countWrite(accesses, writes, mul.dstLow());
                countWrite(accesses, writes, mul.dstHigh());
            }
            case IrOp.Load load -> {
                if (load.baseValueOverride() == -1) countRead(accesses, load.base());
                countOperand(accesses, load.offset());
                countWrite(accesses, writes, load.dst());
                if (load.writeback() && load.base() != load.dst()) {
                    countWrite(accesses, writes, load.base());
                }
            }
            case IrOp.Store store -> {
                if (store.baseValueOverride() == -1) countRead(accesses, store.base());
                countOperand(accesses, store.offset());
                if (store.srcValueOverride() == -1) countRead(accesses, store.src());
                if (store.writeback()) {
                    countWrite(accesses, writes, store.base());
                }
            }
            case IrOp.LoadLiteral lit -> countWrite(accesses, writes, lit.dst());
            case IrOp.Branch b -> {
                if (b.link()) countWrite(accesses, writes, LR_REGISTER);
            }
            case IrOp.BranchExchange bx -> {
                if (bx.sourceValueOverride() == -1) countRead(accesses, bx.sourceRegister());
            }
            case IrOp.ThumbBlPrefix ignored -> countWrite(accesses, writes, LR_REGISTER);
            case IrOp.ThumbBlSuffix ignored -> {
                countRead(accesses, LR_REGISTER);
                countWrite(accesses, writes, LR_REGISTER);
            }
            case IrOp.MultipleTransfer mt -> {
                if (canInlineMultipleTransfer(mt)) { // as formas raras vão pelo helper (spill)
                    countRead(accesses, mt.base());
                    if (mt.writeback()) {
                        countWrite(accesses, writes, mt.base());
                    }
                    for (int reg = 0; reg < PC_REGISTER; reg++) {
                        if ((mt.registerMask() & (1 << reg)) != 0) {
                            if (mt.load()) {
                                countWrite(accesses, writes, reg);
                            } else {
                                countRead(accesses, reg);
                            }
                        }
                    }
                }
            }
            case IrOp.Saturating sat -> {
                countRead(accesses, sat.rm());
                countRead(accesses, sat.rn());
                countWrite(accesses, writes, sat.dst());
            }
            case IrOp.DspMultiply dsp -> {
                countRead(accesses, dsp.rm());
                countRead(accesses, dsp.rs());
                if (dsp.op2() == 0 || (dsp.op2() == 1 && dsp.x() == 0)) {
                    countRead(accesses, dsp.rn());
                }
                if (dsp.op2() == 2) { // SMLAL: lê e escreve o par RdLo/RdHi
                    countRead(accesses, dsp.rn());
                    countRead(accesses, dsp.dst());
                    countWrite(accesses, writes, dsp.rn());
                }
                countWrite(accesses, writes, dsp.dst());
            }
            case IrOp.ParallelAlu pa -> {
                countRead(accesses, pa.rn());
                countRead(accesses, pa.rm());
                countWrite(accesses, writes, pa.dst());
            }
            case IrOp.Sel sel -> {
                countRead(accesses, sel.rn());
                countRead(accesses, sel.rm());
                countWrite(accesses, writes, sel.dst());
            }
            case IrOp.Saturate sat -> {
                countOperand(accesses, sat.operand());
                countWrite(accesses, writes, sat.dst());
            }
            case IrOp.AbsDiffSum ads -> {
                countRead(accesses, ads.rm());
                countRead(accesses, ads.rs());
                if (ads.rn() >= 0) {
                    countRead(accesses, ads.rn());
                }
                countWrite(accesses, writes, ads.dst());
            }
            case IrOp.LoadExclusive ldrex -> {
                countRead(accesses, ldrex.base());
                countWrite(accesses, writes, ldrex.dst());
                if (ldrex.sizeBytes() == 8) {
                    countWrite(accesses, writes, ldrex.dst() + 1);
                }
            }
            case IrOp.StoreExclusive strex -> {
                countRead(accesses, strex.base());
                countRead(accesses, strex.src());
                if (strex.sizeBytes() == 8) {
                    countRead(accesses, strex.src() + 1);
                }
                countWrite(accesses, writes, strex.dst());
            }
            case IrOp.DoubleTransfer dt -> {
                if (dt.baseValueOverride() == -1) {
                    countRead(accesses, dt.base());
                }
                countOperand(accesses, dt.offset());
                if (dt.load()) {
                    countWrite(accesses, writes, dt.first());
                    countWrite(accesses, writes, dt.first() + 1);
                } else {
                    countRead(accesses, dt.first());
                    countRead(accesses, dt.first() + 1);
                }
                if (dt.writeback()) {
                    countWrite(accesses, writes, dt.base());
                }
            }
            case IrOp.Push push -> {
                countRead(accesses, SP_REGISTER);
                countWrite(accesses, writes, SP_REGISTER);
                for (int reg = 0; reg <= 7; reg++) {
                    if ((push.registerMask() & (1 << reg)) != 0) {
                        countRead(accesses, reg);
                    }
                }
                if (push.includeLr()) {
                    countRead(accesses, LR_REGISTER);
                }
            }
            case IrOp.Pop pop -> {
                countRead(accesses, SP_REGISTER);
                countWrite(accesses, writes, SP_REGISTER);
                for (int reg = 0; reg <= 7; reg++) {
                    if ((pop.registerMask() & (1 << reg)) != 0) {
                        countWrite(accesses, writes, reg);
                    }
                }
            }
            case IrOp.BitFieldExtract bfx -> {
                countRead(accesses, bfx.src());
                countWrite(accesses, writes, bfx.dst());
            }
            case IrOp.BitFieldInsert bfi -> {
                countRead(accesses, bfi.dst()); // preserva os bits fora do campo
                if (bfi.src() >= 0) {
                    countRead(accesses, bfi.src());
                }
                countWrite(accesses, writes, bfi.dst());
            }
            case IrOp.BitReverse rbit -> {
                countRead(accesses, rbit.src());
                countWrite(accesses, writes, rbit.dst());
            }
            case IrOp.Divide div -> {
                countRead(accesses, div.dividend());
                countRead(accesses, div.divisor());
                countWrite(accesses, writes, div.dst());
            }
            case IrOp.MoveTop movt -> {
                countRead(accesses, movt.dst()); // preserva a metade baixa existente
                countWrite(accesses, writes, movt.dst());
            }
            // VFP (B3.6, PR2): só as formas de bytecode direto que tocam um registrador ARM
            // entram aqui — VfpMultipleTransfer/VfpCorePairTransfer/VfpSystemTransfer tocam o(s)
            // seu(s) via helper cercado por emitSpilled (flush antes, reload depois), então não
            // contam para o register cache (mesmo motivo de PsrTransfer/Coprocessor, caem no
            // `default` abaixo).
            case IrOp.VfpLoad vfpLoad -> {
                if (vfpLoad.baseValueOverride() == -1) countRead(accesses, vfpLoad.base());
            }
            case IrOp.VfpStore vfpStore -> {
                if (vfpStore.baseValueOverride() == -1) countRead(accesses, vfpStore.base());
            }
            case IrOp.VfpCoreTransfer vfpCoreT -> {
                if (vfpCoreT.toArmRegister()) {
                    countWrite(accesses, writes, vfpCoreT.armRegister());
                } else {
                    countRead(accesses, vfpCoreT.armRegister());
                }
            }
            default -> {
            }
        }
    }

    /// Conta as leituras de registrador de um operando (Register ou ShiftedRegister).
    private static void countOperand(int[] accesses, IrOperand operand) {
        if (operand instanceof IrOperand.Register reg && reg.valueOverride() < 0) {
            countRead(accesses, reg.index());
        } else if (operand instanceof IrOperand.ShiftedRegister sr) {
            if (sr.valueOverride() == -1) {
                countRead(accesses, sr.index());
            }
            if (sr.amountRegister() >= 0 && sr.amountValueOverride() == -1) {
                countRead(accesses, sr.amountRegister());
            }
        }
    }

    private static void countRead(int[] accesses, int reg) {
        if (reg >= 0 && reg < CACHEABLE_REGISTERS) {
            accesses[reg]++;
        }
    }

    private static void countWrite(int[] accesses, boolean[] writes, int reg) {
        if (reg >= 0 && reg < CACHEABLE_REGISTERS) {
            accesses[reg]++;
            writes[reg] = true;
        }
    }

    /// Opcodes ALU que leem src1 (espelha os `emitAluXxx` que chamam `emitSrc1`).
    private static boolean aluUsesSrc1(IrOpCode opcode) {
        return switch (opcode) {
            case MOV, MVN, NEG, CLZ -> false;
            default -> true;
        };
    }

    /// Opcodes ALU que escrevem dst (todos menos os de comparação/teste).
    private static boolean aluWritesDst(IrOpCode opcode) {
        return switch (opcode) {
            case CMP, CMN, TST, TEQ -> false;
            default -> true;
        };
    }

    /// Prólogo: carrega cada registrador cacheado do core para o seu local.
    private void emitCachePrologue(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (cache.slot[reg] >= 0) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                method.visitVarInsn(Opcodes.ISTORE, cache.slot[reg]);
            }
        }
    }

    /// Descarrega os registradores cacheados possivelmente escritos de volta ao core.
    private void emitCacheFlush(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (cache.slot[reg] >= 0 && cache.dirty[reg]) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                method.visitVarInsn(Opcodes.ILOAD, cache.slot[reg]);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
            }
        }
    }

    /// Recarrega todos os registradores cacheados do core (após um helper que pode tê-los mudado).
    private void emitCacheReload(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (cache.slot[reg] >= 0) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                method.visitVarInsn(Opcodes.ISTORE, cache.slot[reg]);
            }
        }
    }

    /// Emite um op "spilled": flush do cache, corpo, reload — o helper vê e deixa o core coerente.
    private void emitSpilled(MethodVisitor method, Runnable body) {
        emitCacheFlush(method);
        body.run();
        emitCacheReload(method);
    }

    // ── ARMv5TE (saturação / DSP / LDRD-STRD) ──────────────────────────────────

    /// QADD/QSUB/QDADD/QDSUB via helper por-valor (o bit Q sticky é o único efeito no core).
    private void emitSaturating(MethodVisitor method, IrOp.Saturating sat) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, sat.rm());
        emitReadRegister(method, sat.rn());
        AsmBytecode.visitIntConst(method, sat.op());
        AsmBytecode.invokeStatic(method, HELPERS, "saturating", "(" + CORE_REF + "III)I");
        emitStoreRegister(method, sat.dst());
    }

    /// Lê a metade de 16 bits (com sinal) de um registrador para a pilha: baixa (sel 0) ou alta.
    private void emitReadHalf(MethodVisitor method, int register, int sel) {
        emitReadRegister(method, register);
        if (sel != 0) {
            AsmBytecode.visitIntConst(method, 16);
            method.visitInsn(Opcodes.ISHR);
        }
        method.visitInsn(Opcodes.I2S);
    }

    /// SMLAxy / SMLAWy / SMULWy / SMLALxy / SMULxy (espelha IrAluExecutor.executeDspMultiply).
    private void emitDspMultiply(MethodVisitor method, IrOp.DspMultiply dsp) {
        switch (dsp.op2()) {
            case 0 -> { // SMLAxy: (Rm.x * Rs.y) + Rn, Q em overflow
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                emitReadRegister(method, dsp.rn());
                AsmBytecode.invokeStatic(method, HELPERS, "dspSmla", "(" + CORE_REF + "III)I");
                emitStoreRegister(method, dsp.dst());
            }
            case 1 -> {
                if (dsp.x() == 0) { // SMLAWy
                    method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                    emitReadRegister(method, dsp.rm());
                    emitReadHalf(method, dsp.rs(), dsp.y());
                    emitReadRegister(method, dsp.rn());
                    AsmBytecode.invokeStatic(method, HELPERS, "dspSmlaw", "(" + CORE_REF + "III)I");
                } else { // SMULWy
                    emitReadRegister(method, dsp.rm());
                    emitReadHalf(method, dsp.rs(), dsp.y());
                    AsmBytecode.invokeStatic(method, HELPERS, "dspSmulw", "(II)I");
                }
                emitStoreRegister(method, dsp.dst());
            }
            case 2 -> { // SMLALxy: {RdHi:RdLo} += Rm.x * Rs.y
                emitReadRegister(method, dsp.dst()); // RdHi
                emitReadRegister(method, dsp.rn());  // RdLo
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                AsmBytecode.invokeStatic(method, HELPERS, "dspSmlal", "(IIII)J");
                method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
                method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
                method.visitInsn(Opcodes.L2I);
                emitStoreRegister(method, dsp.rn());  // RdLo primeiro, como no interpretador
                method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
                AsmBytecode.visitIntConst(method, 32);
                method.visitInsn(Opcodes.LUSHR);
                method.visitInsn(Opcodes.L2I);
                emitStoreRegister(method, dsp.dst()); // RdHi
            }
            default -> { // SMULxy: Rm.x * Rs.y (puro)
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                method.visitInsn(Opcodes.IMUL);
                emitStoreRegister(method, dsp.dst());
            }
        }
    }

    /// LDRD/STRD (espelha IrMemoryExecutor.executeDoubleTransfer): dois acessos de 32 bits, um
    /// cálculo de endereço/writeback. PC no par é rejeitado pela policy (fica no interpretado).
    private void emitDoubleTransfer(MethodVisitor method, IrOp.DoubleTransfer dt) {
        emitOperand(method, dt.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset
        if (dt.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, dt.baseValueOverride());
        } else {
            emitReadRegister(method, dt.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!dt.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        int second = dt.second();
        if (dt.load()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, dt.first());
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, second);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, dt.first());
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            emitReadRegister(method, second);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
        }
        // Writeback (não quando um load clobra a base — UNPREDICTABLE, como no interpretador).
        if (dt.writeback() && (!dt.load() || (dt.base() != dt.first() && dt.base() != second))) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, dt.base());
        }
    }

    // ── LDM/STM inline ─────────────────────────────────────────────────────────

    /// O caso comum de LDM/STM que pode ser desenrolado inline: sem user-mode, sem lista vazia e
    /// sem PC na lista (LDM→PC troca de bloco/interworka; STM de PC leria o r15 do core — ambos
    /// ficam no helper).
    private static boolean canInlineMultipleTransfer(IrOp.MultipleTransfer mt) {
        return !mt.userMode()
                && !mt.emptyRegisterList()
                && mt.registerMask() != 0
                && (mt.registerMask() & (1 << PC_REGISTER)) == 0;
    }

    /// Offset compile-time do primeiro endereço acessado em relação à base (LDM/STM acessa sempre
    /// ascendente a partir do menor endereço).
    private static int startOffset(BlockTransferMode mode, int count) {
        return switch (mode) {
            case IA -> 0;
            case IB -> 4;
            case DA -> -(count - 1) * 4;
            case DB -> -count * 4;
        };
    }

    /// Offset compile-time do writeback em relação à base.
    private static int writebackOffset(BlockTransferMode mode, int count) {
        return switch (mode) {
            case IA, IB -> count * 4;
            case DA, DB -> -count * 4;
        };
    }

    /// LDM/STM desenrolado: espelha AsmRuntimeHelpers.executeMultipleTransfer para o caso comum,
    /// registrador a registrador, lendo/escrevendo pelo register cache (sem flush/reload).
    private void emitMultipleTransferInline(MethodVisitor method, IrOp.MultipleTransfer mt) {
        int mask = mt.registerMask();
        int count = Integer.bitCount(mask);
        int base = mt.base();
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        boolean baseInMask = (mask & (1 << base)) != 0;
        boolean needWriteback = mt.writeback();

        // ADDR = (baseValue + startOffset) & ~3 — o endereço corrente, incrementado por transferência.
        emitReadRegister(method, base);
        int start = startOffset(mt.mode(), count);
        if (start != 0) {
            AsmBytecode.visitIntConst(method, start);
            method.visitInsn(Opcodes.IADD);
        }
        AsmBytecode.visitIntConst(method, ~3);
        method.visitInsn(Opcodes.IAND);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        // TEMP2 = endereço de writeback (também é o valor gravado por STM quando a base está na
        // lista além da primeira posição).
        if (needWriteback) {
            emitReadRegister(method, base);
            AsmBytecode.visitIntConst(method, writebackOffset(mt.mode(), count));
            method.visitInsn(Opcodes.IADD);
            method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        }
        for (int reg = 0; reg < PC_REGISTER; reg++) {
            if ((mask & (1 << reg)) == 0) {
                continue;
            }
            if (mt.load()) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
                AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
                emitStoreRegister(method, reg);
            } else {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
                if (needWriteback && reg == base && reg != firstRegister) {
                    method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL); // STM da base pós-writeback
                } else {
                    emitReadRegister(method, reg);
                }
                AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
            }
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (needWriteback && !(mt.load() && baseInMask)) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            emitStoreRegister(method, base);
        }
    }

    /// PUSH desenrolado (THUMB): stores ascendentes a partir de sp-4n, depois sp = sp-4n.
    private void emitPushInline(MethodVisitor method, IrOp.Push push) {
        int count = Integer.bitCount(push.registerMask()) + (push.includeLr() ? 1 : 0);
        emitReadRegister(method, SP_REGISTER);
        AsmBytecode.visitIntConst(method, count * 4);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL); // novo sp = primeiro endereço
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        for (int reg = 0; reg <= 7; reg++) {
            if ((push.registerMask() & (1 << reg)) == 0) {
                continue;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, reg);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (push.includeLr()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, LR_REGISTER);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
        }
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        emitStoreRegister(method, SP_REGISTER);
    }

    /// POP desenrolado (THUMB): loads ascendentes a partir de sp; POP {..,pc} carrega o PC pelo
    /// helper de interworking da arquitetura e encerra o bloco (pc_changed).
    private void emitPopInline(MethodVisitor method, IrOp.Pop pop) {
        emitReadRegister(method, SP_REGISTER);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        for (int reg = 0; reg <= 7; reg++) {
            if ((pop.registerMask() & (1 << reg)) == 0) {
                continue;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, reg);
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (pop.includePc()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitLoadToPcFromMemory(method);
            method.visitIincInsn(ADDR_LOCAL, 4);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        }
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, SP_REGISTER);
    }

    /// Lê um registrador guest para a pilha: do cache se cacheado, senão do core.
    private void emitReadRegister(MethodVisitor method, int reg) {
        if (cache.cached(reg)) {
            method.visitVarInsn(Opcodes.ILOAD, cache.slot[reg]);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, reg);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
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

    /// Emite bytecode que delega uma op não suportada ao interpretado via {@link IrOpInterop}.
    private void emitPerOpFallback(MethodVisitor method, IrOp op, int blockEndPc) {
        int opId = IrOpInterop.register(op, perOpExecutor);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, opId);
        AsmBytecode.visitIntConst(method, blockEndPc);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/vitorsilverio/armjitter/codegen/jvm/IrOpInterop",
                "executeInterpreted",
                "(" + CORE_REF + "II)Z",
                false);
        emitConditionalSetPcChanged(method);
    }

    // ── ALU ────────────────────────────────────────────────────────────────────

    private void emitAlu(MethodVisitor method, IrOp.Alu alu) {
        switch (alu.opcode()) {
            case MOV -> emitAluMov(method, alu);
            case MVN -> emitAluMvn(method, alu);
            case ADD -> emitAluAdd(method, alu);
            case ADC -> emitAluAdc(method, alu);
            case SUB -> emitAluSub(method, alu);
            case SBC -> emitAluSbc(method, alu);
            case RSB -> emitAluRsb(method, alu);
            case RSC -> emitAluRsc(method, alu);
            case NEG -> emitAluNeg(method, alu);
            case CMP -> emitAluCmp(method, alu);
            case CMN -> emitAluCmn(method, alu);
            case AND -> emitAluLogic(method, alu, Opcodes.IAND);
            case EOR -> emitAluLogic(method, alu, Opcodes.IXOR);
            case ORR -> emitAluLogic(method, alu, Opcodes.IOR);
            case BIC -> emitAluBic(method, alu);
            case TST -> emitAluTest(method, alu, Opcodes.IAND);
            case TEQ -> emitAluTest(method, alu, Opcodes.IXOR);
            case CLZ -> emitAluClz(method, alu);
            case LSL -> emitAluShift(method, alu, "doLsl");
            case LSR -> emitAluShift(method, alu, "doLsr");
            case ASR -> emitAluShift(method, alu, "doAsr");
            case ROR -> emitAluShift(method, alu, "doRor");
            case SXTB, SXTH, UXTB, UXTH -> emitAluExtend(method, alu);
            case SXTB16, UXTB16 -> emitAluExtendByte16(method, alu);
            case REV, REV16, REVSH -> emitAluReverse(method, alu);
            case PKHBT, PKHTB -> emitAluPack(method, alu);
            default -> throw new IllegalStateException("Unexpected ALU opcode: " + alu.opcode());
        }
    }

    private void emitAluMov(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluMvn(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitInsn(Opcodes.IXOR);   // ~value = value ^ -1
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluAdd(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);                      // result
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);     // save result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    private void emitAluAdc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        emitCpsrCarryAsInt(method);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // carryIn
        // result = left + right + carryIn
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.IADD);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);  // result (slot 7 used as int here)
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAdcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluSub(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.ISUB);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluSbc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // left
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right
        // borrow = carry ? 0 : 1 → 1 - carry
        method.visitInsn(Opcodes.ICONST_1);
        emitCpsrCarryAsInt(method);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // borrow
        // result = left - right - borrow
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluRsb(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // src1 (subtrahend)
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // src2 (minuend)
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitInsn(Opcodes.ISUB);                      // src2 - src1
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);    // left = src2
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);    // right = src1
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluRsc(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // src1 (subtrahend)
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // src2 (minuend)
        // borrow = carry ? 0 : 1
        method.visitInsn(Opcodes.ICONST_1);
        emitCpsrCarryAsInt(method);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // borrow
        // result = src2 - src1 - borrow
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitInsn(Opcodes.ISUB);
        if (!alu.setFlags()) {
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluNeg(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        if (!alu.setFlags()) {
            method.visitInsn(Opcodes.INEG);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // right = src2
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ISUB);                      // result = 0 - src2
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(Opcodes.ICONST_0);                  // left = 0
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);    // right
        method.visitInsn(Opcodes.ICONST_0);                  // borrow = 0
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateSbcFlags", "(" + CORE_REF + "IIII)V");
    }

    private void emitAluCmp(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateCmpFlags", "(" + CORE_REF + "II)V");
    }

    private void emitAluCmn(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result (discarded, only flags matter)
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateAddFlags", "(" + CORE_REF + "III)V");
    }

    private void emitAluLogic(MethodVisitor method, IrOp.Alu alu, int jvmOpcode) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(jvmOpcode);
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        // O carry do shifter relê os registradores do operando: calcule-o ANTES do store em dst
        // (dst pode ser o próprio Rm/Rs do shift), como o interpretador faz.
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);  // carry
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(jvmOpcode);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluBic(MethodVisitor method, IrOp.Alu alu) {
        // BIC = AND NOT: dst = src1 & ~src2
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        if (!alu.setFlags()) {
            emitOperand(method, alu.src2());
            method.visitInsn(Opcodes.ICONST_M1);
            method.visitInsn(Opcodes.IXOR);   // ~src2
            method.visitInsn(Opcodes.IAND);   // src1 & ~src2
            emitStoreRegister(method, alu.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        // Carry do shifter ANTES do store em dst — ver emitAluLogic.
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitInsn(Opcodes.IXOR);
        method.visitInsn(Opcodes.IAND);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, alu.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluTest(MethodVisitor method, IrOp.Alu alu, int jvmOpcode) {
        // TST/TEQ: same as AND/EOR but no register write, always sets flags.
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
        emitOperand(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitInsn(jvmOpcode);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);    // result
        emitLogicFlagsCarry(method, alu.src2());
        method.visitVarInsn(Opcodes.ISTORE, LONG_RESULT_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, LONG_RESULT_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateLogicFlags", "(" + CORE_REF + "IZ)V");
    }

    private void emitAluClz(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        AsmBytecode.invokeStatic(method, INTEGER_CLASS, "numberOfLeadingZeros", "(I)I");
        emitStoreRegister(method, alu.dst());
    }

    private void emitAluShift(MethodVisitor method, IrOp.Alu alu, String helperName) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // value
        emitOperand(method, alu.src2());
        AsmBytecode.visitIntConst(method, 0xFF);
        method.visitInsn(Opcodes.IAND);                      // amount = right & 0xFF
        if (!alu.setFlags()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            // stack: amount, value — but doLsl(value, amount) expects (value, amount)
            // we have [value] [amount] on stack in wrong order; swap:
            method.visitInsn(Opcodes.SWAP);
            AsmBytecode.invokeStatic(method, HELPERS, helperName, "(II)I");
            emitStoreRegister(method, alu.dst());
            return;
        }
        // Com S (task C2): doXxxS(core, value, amount) calcula resultado + N/Z + carry do
        // shifter (V inalterado; amount 0 mantém o C atual) e devolve o resultado.
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // amount
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, helperName + "S", "(" + CORE_REF + "II)I");
        emitStoreRegister(method, alu.dst());
    }

    // ── ARMv6 (B1.2): extend/reverse ─────────────────────────────────────────────

    /// SXTB/SXTH/UXTB/UXTH: `right` já vem rotacionado pelo operando (ShiftedRegister ROR, nativo
    /// desde a task C2); soma o acumulador src1 (forma sem acumulador = src1ValueOverride 0).
    private void emitAluExtend(MethodVisitor method, IrOp.Alu alu) {
        emitOperand(method, alu.src2());
        switch (alu.opcode()) {
            case SXTB -> method.visitInsn(Opcodes.I2B);
            case SXTH -> method.visitInsn(Opcodes.I2S);
            case UXTB -> {
                AsmBytecode.visitIntConst(method, 0xFF);
                method.visitInsn(Opcodes.IAND);
            }
            default -> { // UXTH
                AsmBytecode.visitIntConst(method, 0xFFFF);
                method.visitInsn(Opcodes.IAND);
            }
        }
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        method.visitInsn(Opcodes.IADD);
        emitStoreRegister(method, alu.dst());
    }

    /// SXTB16/UXTB16 via helper (duas lanes independentes — ver {@code AsmRuntimeHelpers.extendByte16}).
    private void emitAluExtendByte16(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        emitOperand(method, alu.src2());
        method.visitInsn(alu.opcode() == IrOpCode.SXTB16 ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "extendByte16", "(IIZ)I");
        emitStoreRegister(method, alu.dst());
    }

    /// REV/REV16/REVSH. REV usa {@code Integer.reverseBytes} direto; as outras vão por helper.
    private void emitAluReverse(MethodVisitor method, IrOp.Alu alu) {
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        switch (alu.opcode()) {
            case REV -> AsmBytecode.invokeStatic(method, INTEGER_CLASS, "reverseBytes", "(I)I");
            case REV16 -> AsmBytecode.invokeStatic(method, HELPERS, "reverseHalfwords", "(I)I");
            default -> AsmBytecode.invokeStatic(method, HELPERS, "reverseSignedHalfword", "(I)I"); // REVSH
        }
        emitStoreRegister(method, alu.dst());
    }

    /// PKHBT/PKHTB (ARMv6): `right` já vem shiftado pelo operando; monta o resultado com um
    /// halfword de cada fonte. Nunca escreve flags.
    private void emitAluPack(MethodVisitor method, IrOp.Alu alu) {
        boolean bt = alu.opcode() == IrOpCode.PKHBT;
        emitSrc1(method, alu.src1(), alu.src1ValueOverride());
        AsmBytecode.visitIntConst(method, bt ? 0x0000_FFFF : 0xFFFF_0000);
        method.visitInsn(Opcodes.IAND);
        emitOperand(method, alu.src2());
        AsmBytecode.visitIntConst(method, bt ? 0xFFFF_0000 : 0x0000_FFFF);
        method.visitInsn(Opcodes.IAND);
        method.visitInsn(Opcodes.IOR);
        emitStoreRegister(method, alu.dst());
    }

    // ── ARMv6 (B1.3): paralelas / SEL / saturação / USAD ─────────────────────────

    /// Aritmética paralela (SADD16/UQSUB8/SHASX/...) via helper por-valor; a variante decide
    /// dentro do helper se GE é escrito no core (ver AsmRuntimeHelpers.parallelAlu).
    private void emitParallelAlu(MethodVisitor method, IrOp.ParallelAlu op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, op.rn());
        emitReadRegister(method, op.rm());
        AsmBytecode.visitIntConst(method, op.op().ordinal());
        AsmBytecode.visitIntConst(method, op.variant().ordinal());
        AsmBytecode.invokeStatic(method, HELPERS, "parallelAlu", "(" + CORE_REF + "IIII)I");
        emitStoreRegister(method, op.dst());
    }

    private void emitSel(MethodVisitor method, IrOp.Sel op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, op.rn());
        emitReadRegister(method, op.rm());
        AsmBytecode.invokeStatic(method, HELPERS, "sel", "(" + CORE_REF + "II)I");
        emitStoreRegister(method, op.dst());
    }

    private void emitSaturate(MethodVisitor method, IrOp.Saturate op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitOperand(method, op.operand());
        AsmBytecode.visitIntConst(method, op.saturateBits());
        method.visitInsn(op.unsignedRange() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.halfwords() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "saturate", "(" + CORE_REF + "IIZZ)I");
        emitStoreRegister(method, op.dst());
    }

    /// `rn=-1` (forma sem acumulador, USAD8) empilha `hasAccumulator=false` e um valor
    /// dummy — o helper ignora o valor quando a flag é falsa.
    private void emitAbsDiffSum(MethodVisitor method, IrOp.AbsDiffSum op) {
        emitReadRegister(method, op.rm());
        emitReadRegister(method, op.rs());
        boolean hasAccumulator = op.rn() >= 0;
        method.visitInsn(hasAccumulator ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        if (hasAccumulator) {
            emitReadRegister(method, op.rn());
        } else {
            method.visitInsn(Opcodes.ICONST_0);
        }
        AsmBytecode.invokeStatic(method, HELPERS, "absDiffSum", "(IIZI)I");
        emitStoreRegister(method, op.dst());
    }

    // ── ARMv6/v6K (B1.4): acessos exclusivos ──────────────────────────────────────

    /// LDREX{,B,H}: helper por-valor marca o monitor e lê. LDREXD (sizeBytes=8) não cabe no
    /// helper (dois registradores de destino) — emite `markExclusive` + dois `loadWord` inline,
    /// espelhando `IrMemoryExecutor.executeLoadExclusive`. `offset` só é não-nulo para o `LDREX`
    /// word de 32 bits Thumb-2 (B2.7 PR3).
    private void emitLoadExclusive(MethodVisitor method, IrOp.LoadExclusive load) {
        emitReadRegister(method, load.base());
        if (load.offset() != 0) {
            AsmBytecode.visitIntConst(method, load.offset());
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        if (load.sizeBytes() == 8) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 8);
            AsmBytecode.invokeStatic(method, HELPERS, "markExclusive", "(" + CORE_REF + "II)V");
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, load.dst());
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, load.dst() + 1);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, load.sizeBytes());
            AsmBytecode.invokeStatic(method, HELPERS, "loadExclusive", "(" + CORE_REF + "II)I");
            emitStoreRegister(method, load.dst());
        }
    }

    /// STREX{,B,H,D}: checa o monitor ANTES de qualquer escrita (mesma ordem do interpretador) —
    /// falha não toca a memória. Sucesso escreve, zera `dst` e consome o monitor. `offset` só é
    /// não-nulo para o `STREX` word de 32 bits Thumb-2 (B2.7 PR3).
    private void emitStoreExclusive(MethodVisitor method, IrOp.StoreExclusive store) {
        emitReadRegister(method, store.base());
        if (store.offset() != 0) {
            AsmBytecode.visitIntConst(method, store.offset());
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.visitIntConst(method, store.sizeBytes());
        AsmBytecode.invokeStatic(method, HELPERS, "exclusiveMonitorCovers", "(" + CORE_REF + "II)Z");
        Label fail = new Label();
        Label end = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, fail);
        if (store.sizeBytes() == 8) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, store.src());
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            emitReadRegister(method, store.src() + 1);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
        } else {
            String helperName = switch (store.sizeBytes()) {
                case 1 -> "storeByte";
                case 2 -> "storeHalf";
                default -> "storeWord";
            };
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, store.src());
            AsmBytecode.invokeStatic(method, HELPERS, helperName, CORE_II_TO_V);
        }
        AsmBytecode.visitIntConst(method, 0);
        emitStoreRegister(method, store.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "clearExclusiveMonitor", "(" + CORE_REF + ")V");
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(fail);
        AsmBytecode.visitIntConst(method, 1);
        emitStoreRegister(method, store.dst());
        method.visitLabel(end);
    }

    private void emitClearExclusive(MethodVisitor method, IrOp.ClearExclusive clear) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "clearExclusiveMonitor", "(" + CORE_REF + ")V");
    }

    // ── multiply ────────────────────────────────────────────────────────────────

    private void emitMultiply(MethodVisitor method, IrOp.Multiply mul) {
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        method.visitInsn(Opcodes.IMUL);
        if (mul.accumulate()) {
            emitSrc1(method, mul.rn(), mul.rnValueOverride());
            if (mul.subtractFromAccumulator()) {
                // MLS (B3.1): Rd = Ra - Rm*Rs. Pilha tem [produto, acumulador] — SWAP para
                // subtrair na ordem certa (acumulador - produto).
                method.visitInsn(Opcodes.SWAP);
                method.visitInsn(Opcodes.ISUB);
            } else {
                method.visitInsn(Opcodes.IADD);
            }
        }
        if (!mul.setFlags()) {
            emitStoreRegister(method, mul.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // result
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, mul.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.invokeStatic(method, HELPERS, "updateNzFlags", CORE_I_TO_V);
    }

    private void emitLongMultiply(MethodVisitor method, IrOp.LongMultiply mul) {
        // Carrega rm como long
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitAsLong(method, mul.signed());
        // Carrega rs como long
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        emitAsLong(method, mul.signed());
        method.visitInsn(Opcodes.LMUL);

        if (mul.accumulate()) {
            // current = Integer.toUnsignedLong(dstHigh) << 32 | Integer.toUnsignedLong(dstLow)
            emitSrc1(method, mul.dstHigh(), mul.dstHighValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            AsmBytecode.visitIntConst(method, 32);
            method.visitInsn(Opcodes.LSHL);
            emitSrc1(method, mul.dstLow(), mul.dstLowValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LOR);
            method.visitInsn(Opcodes.LADD);
        }
        if (mul.accumulateDouble()) {
            // UMAAL (ARMv6): RdLo e RdHi somam ao produto como DUAS parcelas de 32 bits sem sinal
            // independentes — não como um par 64-bit (accumulate regular). O caso máximo
            // 0xFFFFFFFF² + 2×0xFFFFFFFF nunca estoura 64 bits.
            emitSrc1(method, mul.dstLow(), mul.dstLowValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LADD);
            emitSrc1(method, mul.dstHigh(), mul.dstHighValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LADD);
        }
        method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);  // slots 7+8

        if (mul.setFlags()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "updateLongNzFlags", "(" + CORE_REF + "J)V");
        }
        // store low half: (int) result
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstLow());
        // store high half: (int)(result >>> 32)
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        AsmBytecode.visitIntConst(method, 32);
        method.visitInsn(Opcodes.LUSHR);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstHigh());
    }

    // ── inteiro ARMv7 (B3.1, emissão nativa B3.6/PR1) ────────────────────────────

    /// SBFX/UBFX: move o campo para os bits altos com `ISHL` e desloca de volta com sinal
    /// (`ISHR`) ou sem sinal (`IUSHR`) — mesmo truque do interpretado (`executeBitFieldExtract`).
    private void emitBitFieldExtract(MethodVisitor method, IrOp.BitFieldExtract op) {
        emitReadRegister(method, op.src());
        AsmBytecode.visitIntConst(method, 32 - op.lsb() - op.width());
        method.visitInsn(Opcodes.ISHL);
        AsmBytecode.visitIntConst(method, 32 - op.width());
        method.visitInsn(op.signedExtract() ? Opcodes.ISHR : Opcodes.IUSHR);
        emitStoreRegister(method, op.dst());
    }

    /// BFI/BFC: a máscara do campo é uma constante pré-computada no emit (não em tempo de
    /// execução). `BFC` (`src == -1`) só aplica a máscara de preservação — inserir um valor
    /// zero via OR seria um no-op, então o passo de inserção é pulado inteiramente.
    private void emitBitFieldInsert(MethodVisitor method, IrOp.BitFieldInsert op) {
        int mask = op.width() == 32 ? -1 : (((1 << op.width()) - 1) << op.lsb());
        emitReadRegister(method, op.dst());
        AsmBytecode.visitIntConst(method, ~mask);
        method.visitInsn(Opcodes.IAND);
        if (op.src() >= 0) {
            emitReadRegister(method, op.src());
            AsmBytecode.visitIntConst(method, op.lsb());
            method.visitInsn(Opcodes.ISHL);
            AsmBytecode.visitIntConst(method, mask);
            method.visitInsn(Opcodes.IAND);
            method.visitInsn(Opcodes.IOR);
        }
        emitStoreRegister(method, op.dst());
    }

    /// RBIT: `Integer.reverse` (intrínseco JIT), igual ao REV/`Integer.reverseBytes` de B1.6.
    private void emitBitReverse(MethodVisitor method, IrOp.BitReverse op) {
        emitReadRegister(method, op.src());
        AsmBytecode.invokeStatic(method, INTEGER_CLASS, "reverse", "(I)I");
        emitStoreRegister(method, op.dst());
    }

    /// SDIV/UDIV: guarda o divisor 0 ANTES do `IDIV` (a ordem importa — ver Armadilhas da task
    /// B3.6). `Integer.MIN_VALUE / -1` não precisa de guard: a divisão inteira da JVM já devolve
    /// `MIN_VALUE` sem lançar, igual ao hardware.
    private void emitDivide(MethodVisitor method, IrOp.Divide op) {
        emitReadRegister(method, op.dividend());
        emitReadRegister(method, op.divisor());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // divisor
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // dividendo
        Label divByZero = new Label();
        Label done = new Label();
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitJumpInsn(Opcodes.IFEQ, divByZero);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        if (op.signedDivide()) {
            method.visitInsn(Opcodes.IDIV);
        } else {
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "divideUnsigned", "(II)I");
        }
        method.visitJumpInsn(Opcodes.GOTO, done);
        method.visitLabel(divByZero);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitLabel(done);
        emitStoreRegister(method, op.dst());
    }

    /// MOVT: preserva os 16 bits baixos existentes de `dst` (AND) e insere o imediato nos 16
    /// bits altos (OR) — nunca toca flags, sem operando shiftado.
    private void emitMoveTop(MethodVisitor method, IrOp.MoveTop op) {
        emitReadRegister(method, op.dst());
        AsmBytecode.visitIntConst(method, 0xFFFF);
        method.visitInsn(Opcodes.IAND);
        AsmBytecode.visitIntConst(method, op.immediate16() << 16);
        method.visitInsn(Opcodes.IOR);
        emitStoreRegister(method, op.dst());
    }

    // ── VFP (B3.6, PR2) ──────────────────────────────────────────────────────────
    // VfpAlu/VfpMoveImmediate/VfpLoad/VfpStore/VfpCoreTransfer são bytecode direto (caminho
    // quente, decisão da task B3.6). VfpCompare/VfpConvert/VfpMultipleTransfer/
    // VfpCorePairTransfer/VfpSystemTransfer chamam um helper estático em AsmRuntimeHelpers.

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`/`VNEG`/`VABS`/`VMOV` registrador (bytecode direto);
    /// `VMLA`/`VMLS`/`VNMLA`/`VNMLS`/`VNMUL`/`VSQRT` (mais raras) chamam
    /// {@code AsmRuntimeHelpers#vfpAluCold}.
    private void emitVfpAlu(MethodVisitor method, IrOp.VfpAlu op) {
        switch (op.op()) {
            case ADD, SUB, MUL, DIV -> emitVfpArith(method, op);
            case NEG -> emitVfpSignBit(method, op, true);
            case ABS -> emitVfpSignBit(method, op, false);
            case COPY -> emitVfpCopy(method, op);
            case MLA, MLS, NMLA, NMLS, NMUL, SQRT -> {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, op.op().ordinal());
                method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                AsmBytecode.visitIntConst(method, op.vd());
                AsmBytecode.visitIntConst(method, op.vn());
                AsmBytecode.visitIntConst(method, op.vm());
                AsmBytecode.invokeStatic(method, HELPERS, "vfpAluCold", "(" + CORE_REF + "IZIII)V");
            }
        }
    }

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`: converte os bits crus para `float`/`double` (view de
    /// {@code VfpRegisters}), aplica o opcode JVM nativo e grava de volta via
    /// {@code setSFloat}/{@code setDDouble} — que já usa `floatToRawIntBits`/`doubleToRawLongBits`
    /// por dentro (nunca a forma não-raw, que canonicalizaria NaN — Armadilha da task B3.6).
    private void emitVfpArith(MethodVisitor method, IrOp.VfpAlu op) {
        if (op.doublePrecision()) {
            emitVfpRead(method, GuestToHostMapper.vfpDDouble(), op.vn());
            emitVfpRead(method, GuestToHostMapper.vfpDDouble(), op.vm());
            method.visitInsn(doubleArithOpcode(op.op()));
            method.visitVarInsn(Opcodes.DSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.DLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetDDouble());
        } else {
            emitVfpRead(method, GuestToHostMapper.vfpSFloat(), op.vn());
            emitVfpRead(method, GuestToHostMapper.vfpSFloat(), op.vm());
            method.visitInsn(singleArithOpcode(op.op()));
            method.visitVarInsn(Opcodes.FSTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.FLOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetSFloat());
        }
    }

    /// Empilha `core.vfp().<accessor>(index)` (recebedor + índice já resolvidos).
    private void emitVfpRead(MethodVisitor method, HostMethodBinding accessor, int index) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, index);
        AsmBytecode.invokeVirtual(method, accessor);
    }

    private static int singleArithOpcode(IrOp.VfpOperation op) {
        return switch (op) {
            case ADD -> Opcodes.FADD;
            case SUB -> Opcodes.FSUB;
            case MUL -> Opcodes.FMUL;
            case DIV -> Opcodes.FDIV;
            default -> throw new IllegalStateException("emitVfpArith: op inesperado " + op);
        };
    }

    private static int doubleArithOpcode(IrOp.VfpOperation op) {
        return switch (op) {
            case ADD -> Opcodes.DADD;
            case SUB -> Opcodes.DSUB;
            case MUL -> Opcodes.DMUL;
            case DIV -> Opcodes.DDIV;
            default -> throw new IllegalStateException("emitVfpArith: op inesperado " + op);
        };
    }

    /// `VNEG`/`VABS`: manipula só o bit de sinal via XOR/AND com uma constante crua (NUNCA `0-x`/
    /// `Math.abs`, que canonicalizariam NaN e quebrariam em `-0.0` — mesma armadilha de
    /// `IrVfpExecutor`, aqui em bytecode).
    private void emitVfpSignBit(MethodVisitor method, IrOp.VfpAlu op, boolean negate) {
        if (op.doublePrecision()) {
            emitVfpRead(method, GuestToHostMapper.vfpD(), op.vm());
            method.visitLdcInsn(negate ? Long.MIN_VALUE : Long.MAX_VALUE);
            method.visitInsn(negate ? Opcodes.LXOR : Opcodes.LAND);
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            emitVfpRead(method, GuestToHostMapper.vfpS(), op.vm());
            AsmBytecode.visitIntConst(method, negate ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            method.visitInsn(negate ? Opcodes.IXOR : Opcodes.IAND);
            method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VMOV` registrador-a-registrador: cópia bit a bit crua (sem conversão de tipo).
    private void emitVfpCopy(MethodVisitor method, IrOp.VfpAlu op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, op.vd());
        emitVfpRead(method, op.doublePrecision() ? GuestToHostMapper.vfpD() : GuestToHostMapper.vfpS(), op.vm());
        AsmBytecode.invokeVirtual(method, op.doublePrecision() ? GuestToHostMapper.vfpSetD() : GuestToHostMapper.vfpSetS());
    }

    /// `VMOV.F32`/`VMOV.F64 Vd, #imm`: grava o imediato já expandido pelo decoder/lifter.
    private void emitVfpMoveImmediate(MethodVisitor method, IrOp.VfpMoveImmediate op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, op.vd());
        if (op.doublePrecision()) {
            method.visitLdcInsn(op.immediateBits());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            AsmBytecode.visitIntConst(method, (int) op.immediateBits());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VCMP`/`VCMPE`: sem registrador ARM envolvido (só `FPSCR`) — chamado direto, sem
    /// {@code emitSpilled} (o register cache de r0-r14 nunca fica stale por isto).
    private void emitVfpCompare(MethodVisitor method, IrOp.VfpCompare op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.compareWithZero() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.vd());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, HELPERS, "executeVfpCompare", "(" + CORE_REF + "ZZII)V");
    }

    /// `VCVT` (forma default): sem registrador ARM envolvido — mesma observação de {@link #emitVfpCompare}.
    private void emitVfpConvert(MethodVisitor method, IrOp.VfpConvert op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, op.conversion().ordinal());
        AsmBytecode.visitIntConst(method, op.vd());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, HELPERS, "executeVfpConvert", "(" + CORE_REF + "III)V");
    }

    /// `VLDR`: dupla precisão lê 2 words little-endian consecutivas via {@code loadWord}
    /// (metade baixa no endereço menor); `base` é lido pelo register cache.
    private void emitVfpLoad(MethodVisitor method, IrOp.VfpLoad load) {
        // `baseValueOverride` (`Vd, [pc, #imm]` — literal pool de `double`/`float` do `gcc`): sem
        // isso, `emitReadRegister` leria `R15` do register cache/core AO VIVO, que não tem o viés
        // `+8` do `PC` arquitetural nesta janela (ver o javadoc de {@link IrOp.VfpLoad#baseValueOverride}).
        if (load.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, load.baseValueOverride());
        } else {
            emitReadRegister(method, load.base());
        }
        AsmBytecode.visitIntConst(method, load.offsetBytes());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        if (load.doublePrecision()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            AsmBytecode.invokeStatic(method, HELPERS, "packDoubleWords", "(II)J");
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, load.vd());
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadWord", CORE_I_TO_I);
            method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, load.vd());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VSTR`: ver {@link #emitVfpLoad}.
    private void emitVfpStore(MethodVisitor method, IrOp.VfpStore store) {
        // Ver {@link #emitVfpLoad} — mesmo tratamento de `baseValueOverride`.
        if (store.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.baseValueOverride());
        } else {
            emitReadRegister(method, store.base());
        }
        AsmBytecode.visitIntConst(method, store.offsetBytes());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        if (store.doublePrecision()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, store.vd());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpD());
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);

            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            method.visitInsn(Opcodes.L2I);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);

            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.visitIntConst(method, 32);
            method.visitInsn(Opcodes.LUSHR);
            method.visitInsn(Opcodes.L2I);
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, store.vd());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpS());
            AsmBytecode.invokeStatic(method, HELPERS, "storeWord", CORE_II_TO_V);
        }
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: sempre via helper — cercado por {@code emitSpilled} no ponto
    /// de despacho (toca `base` diretamente no core, fora do register cache).
    private void emitVfpMultipleTransfer(MethodVisitor method, IrOp.VfpMultipleTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.base());
        AsmBytecode.visitIntConst(method, op.baseValueOverride());
        AsmBytecode.visitIntConst(method, op.firstRegister());
        AsmBytecode.visitIntConst(method, op.count());
        method.visitInsn(op.writeback() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.decrementBefore() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executeVfpMultipleTransfer",
                "(" + CORE_REF + "ZZIIIIZZ)V");
    }

    /// `VMOV Rt,Sn` / `VMOV Sn,Rt` (`FMRS`/`FMSR`): bytecode direto — `armRegister` é lido/escrito
    /// pelo register cache via {@link #emitReadRegister}/{@link #emitStoreRegister} (um único
    /// registrador, sem tocar o core por fora do cache, então sem necessidade de spill).
    private void emitVfpCoreTransfer(MethodVisitor method, IrOp.VfpCoreTransfer op) {
        if (op.toArmRegister()) {
            emitVfpRead(method, GuestToHostMapper.vfpS(), op.vn());
            emitStoreRegister(method, op.armRegister());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vn());
            emitReadRegister(method, op.armRegister());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VMOV Rt,Rt2,Dm` / `VMOV Dm,Rt,Rt2` (`FMRRD`/`FMDRR`): sempre via helper — cercado por
    /// {@code emitSpilled} no ponto de despacho (toca `armLow`/`armHigh` diretamente no core).
    private void emitVfpCorePairTransfer(MethodVisitor method, IrOp.VfpCorePairTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.toArmRegisters() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.armLow());
        AsmBytecode.visitIntConst(method, op.armHigh());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, HELPERS, "executeVfpCorePairTransfer", "(" + CORE_REF + "ZIII)V");
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`): sempre via helper — cercado por {@code emitSpilled} no
    /// ponto de despacho (toca `armRegister` diretamente no core, incl. o caso `APSR_nzcv`).
    private void emitVfpSystemTransfer(MethodVisitor method, IrOp.VfpSystemTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.read() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.armRegister());
        AsmBytecode.invokeStatic(method, HELPERS, "executeVfpSystemTransfer", "(" + CORE_REF + "ZI)V");
    }

    // ── memory ─────────────────────────────────────────────────────────────────

    private void emitLoad(MethodVisitor method, IrOp.Load load) {
        // base value
        if (load.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, load.baseValueOverride());
        } else {
            emitReadRegister(method, load.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        // offset value
        emitOperand(method, load.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!load.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // read
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        // Acesso atravessado (UNALIGNED_ACCESS) só se aplica com destino diferente do PC —
        // LDR/LDRH pc,... continuam exigindo o alinhamento legado (task B1.7, item 4).
        boolean crossed = unalignedAccess && load.dst() != PC_REGISTER;
        String readHelper = switch (load.sizeBytes()) {
            case 1 -> "loadByte";
            case 2 -> crossed ? (load.signed() ? "loadHalfSignedCrossed" : "loadHalfCrossed")
                    : (load.signed() ? "loadHalfSigned" : "loadHalf");
            default -> crossed ? "loadWordCrossed" : "loadWord";
        };
        AsmBytecode.invokeStatic(method, HELPERS, readHelper, CORE_I_TO_I);
        // sign-extend byte if needed (loadByte returns 0–255)
        if (load.sizeBytes() == 1 && load.signed()) {
            method.visitInsn(Opcodes.I2B);
        }

        // store to dst
        if (load.dst() == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            emitLoadToPcFromMemory(method); // LDR pc: interworka em ARMv5
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, load.dst());
        }

        // writeback: base register = base + offset (only when base != dst)
        if (load.writeback() && load.base() != load.dst()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, load.base());
        }
    }

    private void emitStore(MethodVisitor method, IrOp.Store store) {
        // base value
        if (store.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.baseValueOverride());
        } else {
            emitReadRegister(method, store.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        emitOperand(method, store.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!store.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // src value
        if (store.srcValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.srcValueOverride());
        } else {
            emitReadRegister(method, store.src());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);   // value

        // write
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
        String writeHelper = switch (store.sizeBytes()) {
            case 1 -> "storeByte";
            case 2 -> unalignedAccess ? "storeHalfCrossed" : "storeHalf";
            default -> unalignedAccess ? "storeWordCrossed" : "storeWord";
        };
        AsmBytecode.invokeStatic(method, HELPERS, writeHelper, CORE_II_TO_V);

        // writeback
        if (store.writeback()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, store.base());
        }
    }

    private void emitLoadLiteral(MethodVisitor method, IrOp.LoadLiteral lit) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, lit.address());
        String readHelper = switch (lit.sizeBytes()) {
            case 1 -> "loadByte";
            case 2 -> lit.signed() ? "loadHalfSigned" : "loadHalf";
            default -> "loadWord";
        };
        AsmBytecode.invokeStatic(method, HELPERS, readHelper, CORE_I_TO_I);
        if (lit.sizeBytes() == 1 && lit.signed()) {
            method.visitInsn(Opcodes.I2B);
        }
        if (lit.dst() == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            emitLoadToPcFromMemory(method); // LDR pc,=lit: interworka em ARMv5
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, lit.dst());
        }
    }

    // ── LDM/STM/PUSH/POP ───────────────────────────────────────────────────────

    private void emitMultipleTransfer(MethodVisitor method, IrOp.MultipleTransfer mt) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(mt.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.base());
        AsmBytecode.visitIntConst(method, mt.registerMask());
        method.visitInsn(mt.writeback() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.userMode() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.emptyRegisterList() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.mode().ordinal());
        method.visitInsn(loadPcInterworks ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executeMultipleTransfer",
                "(" + CORE_REF + "ZIIZZZIZ)Z");
        emitConditionalSetPcChanged(method);
    }

    private void emitPush(MethodVisitor method, IrOp.Push push) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, push.registerMask());
        method.visitInsn(push.includeLr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executePush", CORE_IZ_TO_V);
    }

    private void emitPop(MethodVisitor method, IrOp.Pop pop) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, pop.registerMask());
        method.visitInsn(pop.includePc() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(loadPcInterworks ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, HELPERS, "executePop", CORE_IZZ_TO_Z);
        emitConditionalSetPcChanged(method);
    }

    // ── branches ───────────────────────────────────────────────────────────────

    private void emitBranch(MethodVisitor method, IrOp.Branch branch) {
        if (branch.link()) {
            AsmBytecode.visitIntConst(method, branch.returnAddress());
            emitStoreRegister(method, LR_REGISTER);
        }
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, branch.target());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitBranchExchange(MethodVisitor method, IrOp.BranchExchange bx) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        if (bx.sourceValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, bx.sourceValueOverride());
        } else {
            emitReadRegister(method, bx.sourceRegister());
        }
        AsmBytecode.invokeStatic(method, HELPERS, "branchExchange", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitThumbBlPrefix(MethodVisitor method, IrOp.ThumbBlPrefix prefix) {
        // LR = address + 4 + highOffset (no PC change)
        AsmBytecode.visitIntConst(method, prefix.address() + 4 + prefix.highOffset());
        emitStoreRegister(method, LR_REGISTER);
    }

    private void emitThumbBlSuffix(MethodVisitor method, IrOp.ThumbBlSuffix suffix) {
        // oldLR = register(14)
        emitReadRegister(method, LR_REGISTER);
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // oldLR
        // LR = (address + 2) | 1
        AsmBytecode.visitIntConst(method, (suffix.address() + 2) | 1);
        emitStoreRegister(method, LR_REGISTER);
        // PC = oldLR + lowOffset
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.visitIntConst(method, suffix.lowOffset());
        method.visitInsn(Opcodes.IADD);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── PSR ────────────────────────────────────────────────────────────────────

    private void emitPsrTransfer(MethodVisitor method, IrOp.PsrTransfer psr) {
        if (psr.read()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            AsmBytecode.visitIntConst(method, psr.register());
            AsmBytecode.invokeStatic(method, HELPERS, "executePsrRead", "(" + CORE_REF + "ZI)V");
        } else {
            int value;
            boolean needRuntime = false;
            if (psr.immediateOperand()) {
                value = psr.immediate();
            } else if (psr.registerValueOverride() != -1) {
                value = psr.registerValueOverride();
            } else {
                needRuntime = true;
                value = 0;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            if (needRuntime) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, psr.register());
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
            } else {
                AsmBytecode.visitIntConst(method, value);
            }
            AsmBytecode.visitIntConst(method, psr.fieldMask());
            AsmBytecode.invokeStatic(method, HELPERS, "executePsrWrite", "(" + CORE_REF + "ZII)V");
        }
    }

    // ── SWI / coprocessor / undefined ──────────────────────────────────────────

    private void emitSwi(MethodVisitor method, IrOp.Swi swi, int blockEndPc) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, swi.immediate());
        AsmBytecode.visitIntConst(method, blockEndPc);
        AsmBytecode.invokeStatic(method, HELPERS, "executeSwi", "(" + CORE_REF + "II)Z");
        // always returns true
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    private void emitCoprocessor(MethodVisitor method, IrOp.Coprocessor cp) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(cp.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, cp.coprocessor());
        AsmBytecode.visitIntConst(method, cp.opcode1());
        AsmBytecode.visitIntConst(method, cp.crn());
        AsmBytecode.visitIntConst(method, cp.crm());
        AsmBytecode.visitIntConst(method, cp.opcode2());
        AsmBytecode.visitIntConst(method, cp.register());
        AsmBytecode.visitIntConst(method, cp.sequentialPc());
        AsmBytecode.invokeStatic(method, HELPERS, "executeCoprocessor",
                "(" + CORE_REF + "ZIIIIIII)Z");
        emitConditionalSetPcChanged(method);
    }

    private void emitCoprocessorDouble(MethodVisitor method, IrOp.CoprocessorDouble cp) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(cp.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, cp.coprocessor());
        AsmBytecode.visitIntConst(method, cp.opcode1());
        AsmBytecode.visitIntConst(method, cp.crm());
        AsmBytecode.visitIntConst(method, cp.rt());
        AsmBytecode.visitIntConst(method, cp.rt2());
        AsmBytecode.visitIntConst(method, cp.sequentialPc());
        AsmBytecode.invokeStatic(method, HELPERS, "executeCoprocessorDouble",
                "(" + CORE_REF + "ZIIIIII)Z");
        emitConditionalSetPcChanged(method);
    }

    private void emitUndefined(MethodVisitor method, IrOp.Undefined undef) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, undef.sequentialPc());
        AsmBytecode.invokeStatic(method, HELPERS, "executeUndefined", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── cycle / fetch ──────────────────────────────────────────────────────────

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

    // ── helpers ────────────────────────────────────────────────────────────────

    private void emitSrc1(MethodVisitor method, int register, int valueOverride) {
        if (valueOverride != -1) {
            AsmBytecode.visitIntConst(method, valueOverride);
            return;
        }
        emitReadRegister(method, register);
    }

    private void emitOperand(MethodVisitor method, IrOperand operand) {
        switch (operand) {
            case IrOperand.Immediate imm -> AsmBytecode.visitIntConst(method, imm.value());
            case IrOperand.Register reg -> {
                if (reg.valueOverride() >= 0) {
                    AsmBytecode.visitIntConst(method, reg.valueOverride());
                } else {
                    emitReadRegister(method, reg.index());
                }
            }
            case IrOperand.ShiftedRegister sr -> {
                // shiftedOperand(core, value, shiftType, amount, regSpecified, rrx) espelha o
                // interpretador; o VALOR e a QUANTIDADE fluem pelo register cache.
                emitShiftedOperandArgs(method, sr);
                AsmBytecode.invokeStatic(method, HELPERS, "shiftedOperand", "(" + CORE_REF + "IIIZZ)I");
                if (sr.negated()) {
                    method.visitInsn(Opcodes.INEG);
                }
            }
        }
    }

    /// Empilha os argumentos `(core, value, shiftType, amount, regSpecified, rrx)` compartilhados
    /// por {@code shiftedOperand} (valor) e {@code shiftedOperandCarry} (carry do shifter).
    private void emitShiftedOperandArgs(MethodVisitor method, IrOperand.ShiftedRegister sr) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        if (sr.valueOverride() != -1) {
            AsmBytecode.visitIntConst(method, sr.valueOverride());
        } else {
            emitReadRegister(method, sr.index());
        }
        AsmBytecode.visitIntConst(method, sr.shiftType().ordinal());
        boolean regSpecified = sr.amountRegister() >= 0;
        if (regSpecified) {
            if (sr.amountValueOverride() != -1) {
                AsmBytecode.visitIntConst(method, sr.amountValueOverride() & 0xFF);
            } else {
                emitReadRegister(method, sr.amountRegister());
                AsmBytecode.visitIntConst(method, 0xFF);
                method.visitInsn(Opcodes.IAND);
            }
        } else {
            AsmBytecode.visitIntConst(method, sr.amount());
        }
        method.visitInsn(regSpecified ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(sr.rrx() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
    }

    private void emitStoreRegister(MethodVisitor method, int dst) {
        if (dst != PC_REGISTER && cache.cached(dst)) {
            method.visitVarInsn(Opcodes.ISTORE, cache.slot[dst]);
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
        if (dst == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeStatic(method, HELPERS, "loadToPcArm4", CORE_I_TO_V);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, dst);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        }
    }

    /// Emite bytecode que desempilha um resultado booleano e seta PC_CHANGED_LOCAL para 1 se verdadeiro.
    private void emitConditionalSetPcChanged(MethodVisitor method) {
        Label skip = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, skip);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        method.visitLabel(skip);
    }

    /// Emite bytecode que carrega o flag de carry do CPSR como int (0 ou 1) na pilha.
    private void emitCpsrCarryAsInt(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsr());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.cpsrCarry());
        // carry() devolve Z (boolean), que já é int 0/1 em bytecode — sem conversão necessária
    }

    /// Empilha o carry (0/1) que os flags LÓGICOS devem receber para o operando `src2`:
    /// imediato com carry conhecido = constante; shifted-register = carry-out do barrel shifter
    /// (task C2 — deve ser emitido ANTES da escrita em dst, pois relê os registradores do shift);
    /// registrador puro/imediato sem rotação = C atual.
    private void emitLogicFlagsCarry(MethodVisitor method, IrOperand src2) {
        if (src2 instanceof IrOperand.Immediate imm && imm.carryOutKnown()) {
            method.visitInsn(imm.carryOut() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (src2 instanceof IrOperand.ShiftedRegister sr) {
            emitShiftedOperandArgs(method, sr);
            AsmBytecode.invokeStatic(method, HELPERS, "shiftedOperandCarry", "(" + CORE_REF + "IIIZZ)Z");
        } else {
            emitCpsrCarryAsInt(method);
        }
    }

    /// Converte o int no topo da pilha para long. Usa I2L (com sinal) ou Integer.toUnsignedLong (sem sinal).
    private void emitAsLong(MethodVisitor method, boolean signed) {
        if (signed) {
            method.visitInsn(Opcodes.I2L);
        } else {
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
        }
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
