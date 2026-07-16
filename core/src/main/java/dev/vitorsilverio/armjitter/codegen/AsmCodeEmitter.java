package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmSuperblockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.IrOpInterop; // referenciado no Javadoc (@link)
import dev.vitorsilverio.armjitter.codegen.jvm.JvmBlockLoader;
import dev.vitorsilverio.armjitter.codegen.jvm.SuperblockContext;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// Emissor JVM com política de fallback configurável e contadores de diagnóstico.
///
/// <p>Política padrão: {@link AsmFallbackPolicy#WHOLE_BLOCK} — o comportamento das Fases 4–6:
/// blocos com qualquer op não suportada são delegados inteiramente ao interpretado.</p>
///
/// <p>Em {@link AsmFallbackPolicy#PER_OP}, ops não suportadas são despachadas ao interpretado
/// inline no bytecode gerado via {@link IrOpInterop}, permitindo compilação parcial de blocos.</p>
///
/// <p>O {@link IrOptimizer} (se fornecido) é aplicado antes de qualquer decisão de emissão.</p>
public final class AsmCodeEmitter implements CodeEmitter {
    private static final String GENERATED_PACKAGE = "dev/vitorsilverio/armjitter/codegen/generated";

    private final InterpretedCodeEmitter fallback;
    private final JvmBlockLoader loader;
    // Per-thread compiler: an AsmBlockCompiler holds per-compilation mutable state (the register
    // cache) so it is NOT thread-safe, but the JIT compiles on a POOL of background threads — each
    // gets its own instance here while the loader + blockSequence (unique class names) are shared and
    // thread-safe. See JitRuntime's compile executor.
    private final ThreadLocal<AsmBlockCompiler> compiler;
    private final AtomicInteger blockSequence;
    private final AsmFallbackPolicy policy;
    private final IrOptimizer optimizer;

    private final AtomicLong nativeBlockCount = new AtomicLong();
    private final AtomicLong fallbackBlockCount = new AtomicLong();
    private final AtomicLong perOpFallbackOpCount = new AtomicLong();

    /// Cria um emissor ASM com política {@link AsmFallbackPolicy#WHOLE_BLOCK} e sem otimizador.
    public AsmCodeEmitter() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Cria um emissor ASM ligado à arquitetura informada com política {@code WHOLE_BLOCK}.
    public AsmCodeEmitter(ArmArchitecture architecture) {
        this(architecture, AsmFallbackPolicy.WHOLE_BLOCK, IrOptimizer.identity());
    }

    /// Cria um emissor ASM com a política informada e sem otimizador.
    public AsmCodeEmitter(AsmFallbackPolicy policy) {
        this(ArmArchitecture.ARMV4T, policy, IrOptimizer.identity());
    }

    /// Cria um emissor ASM com a política e o otimizador informados.
    public AsmCodeEmitter(AsmFallbackPolicy policy, IrOptimizer optimizer) {
        this(ArmArchitecture.ARMV4T, policy, optimizer);
    }

    /// Cria um emissor ASM completo.
    public AsmCodeEmitter(ArmArchitecture architecture, AsmFallbackPolicy policy, IrOptimizer optimizer) {
        this.fallback = new InterpretedCodeEmitter(architecture);
        this.loader = new JvmBlockLoader();
        boolean interworks = architecture.has(ArmFeature.LOAD_PC_INTERWORKING);
        boolean unalignedAccess = architecture.has(ArmFeature.UNALIGNED_ACCESS);
        // Bind THIS architecture's interpreter to the PER_OP fallback (registered per-op in
        // IrOpInterop). Two runtimes of different architectures (NDS ARM9 ARMv5TE + ARM7 ARMv4T) then
        // never share one executor — previously a static singleton, so whichever emitter was built
        // last won and the ARM9's ARMv5 fallback ops (BLX/CLZ/DSP/sat/LDRD) silently ran with ARMv4T
        // semantics once a block compiled in the background (warm-cache-only, non-deterministic corruption).
        IrBlockExecutor perOpExecutor = new IrBlockExecutor(architecture);
        this.compiler = ThreadLocal.withInitial(
                () -> new AsmBlockCompiler(interworks, unalignedAccess, perOpExecutor));
        this.blockSequence = new AtomicInteger();
        this.policy = policy;
        this.optimizer = optimizer;
    }

    /// Cria um emissor com componentes customizados (útil em testes).
    AsmCodeEmitter(
            InterpretedCodeEmitter fallback,
            JvmBlockLoader loader,
            AsmBlockCompiler compiler,
            AtomicInteger blockSequence) {
        this(fallback, loader, compiler, blockSequence, AsmFallbackPolicy.WHOLE_BLOCK, IrOptimizer.identity());
    }

    AsmCodeEmitter(
            InterpretedCodeEmitter fallback,
            JvmBlockLoader loader,
            AsmBlockCompiler compiler,
            AtomicInteger blockSequence,
            AsmFallbackPolicy policy,
            IrOptimizer optimizer) {
        this.fallback = fallback;
        this.loader = loader;
        // Test path: a specific compiler instance is supplied and reused (tests emit single-threaded).
        this.compiler = ThreadLocal.withInitial(() -> compiler);
        this.blockSequence = blockSequence;
        this.policy = policy;
        this.optimizer = optimizer;
    }

    @Override
    public CompiledBlock emit(IrBlock block) {
        block = optimizer.optimize(block);
        return switch (policy) {
            case WHOLE_BLOCK -> emitWholeBlock(block);
            case PER_OP -> emitPerOp(block);
            case FAIL_FAST -> emitFailFast(block);
        };
    }

    private CompiledBlock emitWholeBlock(IrBlock block) {
        if (!AsmNativePolicy.supports(block)) {
            fallbackBlockCount.incrementAndGet();
            return fallback.emit(block);
        }
        nativeBlockCount.incrementAndGet();
        return compileNative(block);
    }

    private CompiledBlock emitPerOp(IrBlock block) {
        long unsupportedOps = block.operations().stream()
                .filter(op -> !AsmNativePolicy.supports(op))
                .count();
        perOpFallbackOpCount.addAndGet(unsupportedOps);
        nativeBlockCount.incrementAndGet();
        String internalName = GENERATED_PACKAGE + "/AluBlock" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.get().compilePerOp(internalName, block);
        return loader.load(bytecode, internalName);
    }

    /// Compõe um loop-superbloco (task C0.3) por `INVOKESTATIC` direto nos `execute0`
    /// dos membros — possível apenas quando TODOS os membros são classes geradas por
    /// este emissor (mesmo class loader); senão devolve `null` e o chamador segue sem.
    @Override
    public CompiledBlock emitLoopSuperblock(
            List<CompiledBlock> members, int[] memberStartPcs, SuperblockContext context) {
        String[] memberNames = new String[members.size()];
        for (int i = 0; i < members.size(); i++) {
            Class<?> type = members.get(i).getClass();
            if (!loader.defined(type) || !hasStaticExecute0(type)) {
                return null; // membro não-ASM (ex.: fallback interpretado de bloco inteiro)
            }
            memberNames[i] = type.getName().replace('.', '/');
        }
        String internalName = GENERATED_PACKAGE + "/LoopSuperblock" + blockSequence.getAndIncrement();
        byte[] bytecode = new AsmSuperblockCompiler().compile(internalName, memberStartPcs, memberNames);
        CompiledBlock superblock = loader.load(bytecode, internalName);
        try {
            superblock.getClass().getField(AsmSuperblockCompiler.CONTEXT_FIELD).set(superblock, context);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to inject superblock context", failure);
        }
        return superblock;
    }

    private static boolean hasStaticExecute0(Class<?> type) {
        try {
            return java.lang.reflect.Modifier.isStatic(
                    type.getMethod("execute0", ArmCore.class).getModifiers());
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private CompiledBlock emitFailFast(IrBlock block) {
        if (!AsmNativePolicy.supports(block)) {
            throw new UnsupportedOperationException(
                    "Block at PC 0x" + Integer.toHexString(block.startPc()) +
                    " contains ops not supported natively (FAIL_FAST policy)");
        }
        nativeBlockCount.incrementAndGet();
        return compileNative(block);
    }

    private CompiledBlock compileNative(IrBlock block) {
        String internalName = GENERATED_PACKAGE + "/AluBlock" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.get().compile(internalName, block);
        return loader.load(bytecode, internalName);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.JVM_BYTECODE;
    }

    /// Retorna {@code true} quando o bloco inteiro pode ser emitido em bytecode JVM sem fallback.
    public boolean isNativeSupported(IrBlock block) {
        return AsmNativePolicy.supports(block);
    }

    /// Política de fallback em uso.
    public AsmFallbackPolicy policy() {
        return policy;
    }

    /// Número de blocos compilados como bytecode JVM (nativos ou PER_OP).
    public long nativeBlockCount() {
        return nativeBlockCount.get();
    }

    /// Número de blocos que caíram completamente no interpretado (modo {@link AsmFallbackPolicy#WHOLE_BLOCK}).
    public long fallbackBlockCount() {
        return fallbackBlockCount.get();
    }

    /// Número de ops individuais despachadas ao interpretado no modo {@link AsmFallbackPolicy#PER_OP}.
    public long perOpFallbackOpCount() {
        return perOpFallbackOpCount.get();
    }

    /// Zera todos os contadores de diagnóstico.
    public void resetCounters() {
        nativeBlockCount.set(0);
        fallbackBlockCount.set(0);
        perOpFallbackOpCount.set(0);
    }

    /// Conjunto de subtipos de {@link IrOp} que podem ser emitidos nativamente (qualquer condição —
    /// o compilador emite um guard {@code evalCond} por op).
    /// {@link IrOp.Swap} não está incluído (sempre delegado ao interpretado).
    @SuppressWarnings("unchecked")
    public static Set<Class<? extends IrOp>> supportedOps() {
        return Set.of(
                IrOp.Alu.class, IrOp.Multiply.class, IrOp.LongMultiply.class,
                IrOp.Load.class, IrOp.Store.class, IrOp.LoadLiteral.class,
                IrOp.MultipleTransfer.class, IrOp.Branch.class, IrOp.BranchExchange.class,
                IrOp.ThumbBlPrefix.class, IrOp.ThumbBlSuffix.class,
                IrOp.Push.class, IrOp.Pop.class, IrOp.PsrTransfer.class,
                IrOp.Swi.class, IrOp.Coprocessor.class, IrOp.Undefined.class,
                IrOp.Cycle.class, IrOp.Fetch.class
        );
    }

    /// Opcodes ALU actualmente emitidos nativamente.
    public static Set<IrOpCode> supportedAluOpcodes() {
        return AsmNativePolicy.supportedAluOpcodes();
    }
}
