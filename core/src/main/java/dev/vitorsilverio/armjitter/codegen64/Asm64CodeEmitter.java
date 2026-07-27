package dev.vitorsilverio.armjitter.codegen64;

import dev.vitorsilverio.armjitter.codegen.jvm.Jvm64BlockLoader;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64BlockCompiler;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;

import java.util.concurrent.atomic.AtomicInteger;

/// Emissor ASM A64 — espelho estrutural (só a política `WHOLE_BLOCK`, ver D3 da spec) de
/// {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter} (32 bits), introduzido na task B6.4
/// (PR1). Blocos totalmente suportados por {@link Ir64NativePolicy} compilam nativamente
/// ({@link Ir64BlockCompiler}); qualquer bloco com uma op fora do conjunto do PR1 cai inteiro no
/// {@link InterpretedIr64CodeEmitter} — sem compilação parcial (`PER_OP`) ainda.
public final class Asm64CodeEmitter implements Ir64CodeEmitter {
    private static final String GENERATED_PACKAGE = "dev/vitorsilverio/armjitter/codegen/generated";

    private final InterpretedIr64CodeEmitter fallback = new InterpretedIr64CodeEmitter();
    private final Jvm64BlockLoader loader = new Jvm64BlockLoader();
    private final Ir64BlockCompiler compiler = new Ir64BlockCompiler();
    private final AtomicInteger blockSequence = new AtomicInteger();

    @Override
    public CompiledBlock64 emit(Ir64Block block) {
        if (!Ir64NativePolicy.supports(block)) {
            return fallback.emit(block);
        }
        String internalName = GENERATED_PACKAGE + "/Aarch64Block" + blockSequence.getAndIncrement();
        byte[] bytecode = compiler.compile(internalName, block);
        return loader.load(bytecode, internalName);
    }

    /// `true` quando o bloco inteiro pode ser emitido em bytecode JVM sem cair no interpretado.
    public boolean isNativeSupported(Ir64Block block) {
        return Ir64NativePolicy.supports(block);
    }
}
