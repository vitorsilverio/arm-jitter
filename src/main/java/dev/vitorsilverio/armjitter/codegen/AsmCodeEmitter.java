package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.codegen.jvm.IrOpInterop;
import dev.vitorsilverio.armjitter.codegen.jvm.JvmBlockLoader;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

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
    private final AsmBlockCompiler compiler;
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
        this.compiler = new AsmBlockCompiler(architecture.has(ArmFeature.LOAD_PC_INTERWORKING));
        this.blockSequence = new AtomicInteger();
        this.policy = policy;
        this.optimizer = optimizer;
        IrOpInterop.setExecutor(new IrBlockExecutor(architecture));
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
        this.compiler = compiler;
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
        byte[] bytecode = compiler.compilePerOp(internalName, block);
        return loader.load(bytecode, internalName);
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
        byte[] bytecode = compiler.compile(internalName, block);
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

    /// Conjunto de subtipos de {@link IrOp} que podem ser emitidos nativamente (condição {@code AL}).
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
