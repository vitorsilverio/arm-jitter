package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.codegen.jvm.JvmBlockLoader;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/// Emissor ASM com suporte inicial a ALU simples (Fase 4); blocos não suportados usam fallback interpretado.
public final class AsmCodeEmitter implements CodeEmitter {
    private static final String GENERATED_PACKAGE = "dev/vitorsilverio/armjitter/codegen/generated";

    private final InterpretedCodeEmitter fallback;
    private final JvmBlockLoader loader;
    private final AsmBlockCompiler compiler;
    private final AtomicInteger blockSequence;

    /// Cria um emissor ASM com fallback interpretado ARMv4T.
    public AsmCodeEmitter() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Cria um emissor ASM ligado à arquitetura informada (fallback interpretado).
    public AsmCodeEmitter(ArmArchitecture architecture) {
        this.fallback = new InterpretedCodeEmitter(architecture);
        this.loader = new JvmBlockLoader();
        this.compiler = new AsmBlockCompiler();
        this.blockSequence = new AtomicInteger();
    }

    /// Cria um emissor com componentes customizados (útil em testes).
    AsmCodeEmitter(
            InterpretedCodeEmitter fallback,
            JvmBlockLoader loader,
            AsmBlockCompiler compiler,
            AtomicInteger blockSequence) {
        this.fallback = fallback;
        this.loader = loader;
        this.compiler = compiler;
        this.blockSequence = blockSequence;
    }

    @Override
    public CompiledBlock emit(IrBlock block) {
        if (!AsmNativePolicy.supports(block)) {
            return fallback.emit(block);
        }
        String internalName = GENERATED_PACKAGE + "/AluBlock" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.compile(internalName, block);
        return loader.load(bytecode, internalName);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.JVM_BYTECODE;
    }

    /// Retorna {@code true} quando o bloco será emitido em bytecode JVM (sem fallback).
    public boolean isNativeSupported(IrBlock block) {
        return AsmNativePolicy.supports(block);
    }

    /// Opcodes ALU atualmente emitidos nativamente.
    public static Set<IrOpCode> supportedAluOpcodes() {
        return AsmNativePolicy.supportedAluOpcodes();
    }
}
