package dev.vitorsilverio.armjitter.codegen64;

import dev.vitorsilverio.armjitter.codegen.jvm.Jvm64BlockLoader;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64BlockCompiler;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64OpInterop; // referenciado no Javadoc (@link)
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// Emissor ASM A64 com política de fallback configurável e contadores de diagnóstico — espelho
/// estrutural de {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter} (32 bits), introduzido
/// na task B6.4 (PR1).
///
/// <p>Política padrão: {@link Asm64FallbackPolicy#WHOLE_BLOCK} — blocos com qualquer op fora de
/// {@link Ir64NativePolicy} são delegados inteiramente ao {@link InterpretedIr64CodeEmitter}.</p>
///
/// <p>Em {@link Asm64FallbackPolicy#PER_OP} (task C12.2), ops não suportadas são despachadas ao
/// interpretado inline no bytecode gerado via {@link Ir64OpInterop}, permitindo compilação parcial
/// de blocos — ver {@link Ir64BlockCompiler#compilePerOp}.</p>
public final class Asm64CodeEmitter implements Ir64CodeEmitter {
    private static final String GENERATED_PACKAGE = "dev/vitorsilverio/armjitter/codegen/generated";

    private final InterpretedIr64CodeEmitter fallback = new InterpretedIr64CodeEmitter();
    private final Jvm64BlockLoader loader = new Jvm64BlockLoader();
    private final Ir64BlockCompiler compiler;
    private final AtomicInteger blockSequence = new AtomicInteger();
    private final Asm64FallbackPolicy policy;

    private final AtomicLong nativeBlockCount = new AtomicLong();
    private final AtomicLong fallbackBlockCount = new AtomicLong();
    private final AtomicLong perOpFallbackOpCount = new AtomicLong();

    /// Cria um emissor ASM A64 com política {@link Asm64FallbackPolicy#WHOLE_BLOCK} (padrão desde
    /// B6.4 — G3: este construtor NUNCA muda de comportamento).
    public Asm64CodeEmitter() {
        this(Asm64FallbackPolicy.WHOLE_BLOCK);
    }

    /// Cria um emissor ASM A64 com a política informada.
    public Asm64CodeEmitter(Asm64FallbackPolicy policy) {
        this.policy = policy;
        this.compiler = new Ir64BlockCompiler(new Ir64BlockExecutor());
    }

    @Override
    public CompiledBlock64 emit(Ir64Block block) {
        return switch (policy) {
            case WHOLE_BLOCK -> emitWholeBlock(block);
            case PER_OP -> emitPerOp(block);
        };
    }

    private CompiledBlock64 emitWholeBlock(Ir64Block block) {
        if (!Ir64NativePolicy.supports(block)) {
            fallbackBlockCount.incrementAndGet();
            return fallback.emit(block);
        }
        nativeBlockCount.incrementAndGet();
        String internalName = GENERATED_PACKAGE + "/Aarch64Block" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.compile(internalName, block);
        return loader.load(bytecode, internalName);
    }

    private CompiledBlock64 emitPerOp(Ir64Block block) {
        long unsupportedOps = block.operations().stream()
                .filter(op -> !Ir64NativePolicy.supports(op))
                .count();
        perOpFallbackOpCount.addAndGet(unsupportedOps);
        nativeBlockCount.incrementAndGet();
        String internalName = GENERATED_PACKAGE + "/Aarch64Block" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.compilePerOp(internalName, block);
        return loader.load(bytecode, internalName);
    }

    /// `true` quando o bloco inteiro pode ser emitido em bytecode JVM sem cair no interpretado.
    public boolean isNativeSupported(Ir64Block block) {
        return Ir64NativePolicy.supports(block);
    }

    /// Política de fallback em uso.
    public Asm64FallbackPolicy policy() {
        return policy;
    }

    /// Número de blocos compilados como bytecode JVM (nativos ou PER_OP).
    public long nativeBlockCount() {
        return nativeBlockCount.get();
    }

    /// Número de blocos que caíram completamente no interpretado (modo
    /// {@link Asm64FallbackPolicy#WHOLE_BLOCK}).
    public long fallbackBlockCount() {
        return fallbackBlockCount.get();
    }

    /// Número de ops individuais despachadas ao interpretado no modo
    /// {@link Asm64FallbackPolicy#PER_OP}.
    public long perOpFallbackOpCount() {
        return perOpFallbackOpCount.get();
    }

    /// Zera todos os contadores de diagnóstico.
    public void resetCounters() {
        nativeBlockCount.set(0);
        fallbackBlockCount.set(0);
        perOpFallbackOpCount.set(0);
    }
}
