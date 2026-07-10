package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockBuilder;
import dev.vitorsilverio.armjitter.codegen.jvm.JvmBlockLoader;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.concurrent.atomic.AtomicInteger;

/// Emissor de fumaça que gera bytecode JVM vazio (retorna {@code 0} ciclos internos).
///
/// Ignora o conteúdo do {@link IrBlock}; serve para validar a infraestrutura ASM antes da
/// Fase 4 do `ROADMAP.md`.
public final class EmptyAsmCodeEmitter implements CodeEmitter {
    private static final String GENERATED_PACKAGE = "dev/vitorsilverio/armjitter/codegen/generated";

    private final JvmBlockLoader loader;
    private final AtomicInteger blockSequence = new AtomicInteger();

    /// Cria um emissor com loader JVM padrão.
    public EmptyAsmCodeEmitter() {
        this(new JvmBlockLoader());
    }

    /// Cria um emissor com loader JVM customizado (útil em testes).
    EmptyAsmCodeEmitter(JvmBlockLoader loader) {
        this.loader = loader;
    }

    @Override
    public CompiledBlock emit(IrBlock block) {
        String internalName = GENERATED_PACKAGE + "/EmptyBlock" + blockSequence.getAndIncrement();
        byte[] bytecode = AsmBlockBuilder.buildEmptyExecuteMethod(internalName);
        return loader.load(bytecode, internalName);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.JVM_BYTECODE;
    }
}
