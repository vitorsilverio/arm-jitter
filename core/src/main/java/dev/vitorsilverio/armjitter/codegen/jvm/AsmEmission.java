package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrOp;
import org.objectweb.asm.MethodVisitor;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/// Uma linha do {@link AsmEmitterRegistry}: como o {@link AsmBlockCompiler} emite um `record` do IR.
///
/// - `emitter` — o bytecode da op (sempre emitido quando a op aparece no bloco);
/// - `acceptance` — em que instâncias a op é **nativa** para a {@link AsmNativePolicy} (ex.: sem
///   escrita em PC); o laço de compilação não olha este predicado, só a política;
/// - `counter` — os acessos a GPR que o bytecode faz pelo register cache;
/// - `spill` — a op chama um helper que lê/escreve registradores no core: o compilador cerca a
///   emissão de flush + reload do cache.
///
/// @param <T> o `record` do IR
record AsmEmission<T extends IrOp>(
        Class<T> type,
        Emitter<T> emitter,
        Predicate<? super T> acceptance,
        BiConsumer<? super T, AsmAccessCounter> counter,
        boolean spill) {

    /// Emissão de uma op já com o tipo do record.
    @FunctionalInterface
    interface Emitter<T> {
        void emit(AsmEmitters emitters, MethodVisitor method, T op);
    }

    /// Método de emissão de uma família (`AsmAluEmitter::emitAlu`).
    @FunctionalInterface
    interface FamilyEmitter<E, T> {
        void emit(E family, MethodVisitor method, T op);
    }

    /// Op emitida pelo método `emitter` da família escolhida por `family`; nativa em toda instância,
    /// sem acesso pelo register cache, sem spill (os modificadores abaixo mudam cada um).
    static <T extends IrOp, E extends AsmEmitterBase> AsmEmission<T> of(
            Class<T> type, Function<AsmEmitters, E> family, FamilyEmitter<? super E, ? super T> emitter) {
        return new AsmEmission<>(type, (emitters, method, op) -> emitter.emit(family.apply(emitters), method, op),
                AsmEmission::always, AsmEmission::noAccesses, false);
    }

    /// Op emitida como chamada ao interpretado ({@link IrOpInterop}), cercada de flush/reload: estado
    /// global (modo/banco/CPSR/IT/exceção de guest) ou rara demais para bytecode dedicado — o ganho é
    /// não derrubar o BLOCO inteiro para o interpretado (task C12.7).
    static <T extends IrOp> AsmEmission<T> interop(Class<T> type) {
        return of(type, AsmEmitters::base, AsmEmitterBase::emitInterop).spilled();
    }

    AsmEmission<T> accepting(Predicate<? super T> predicate) {
        return new AsmEmission<>(type, emitter, predicate, counter, spill);
    }

    AsmEmission<T> counting(BiConsumer<? super T, AsmAccessCounter> accessCounter) {
        return new AsmEmission<>(type, emitter, acceptance, accessCounter, spill);
    }

    AsmEmission<T> spilled() {
        return new AsmEmission<>(type, emitter, acceptance, counter, true);
    }

    boolean accepts(IrOp op) {
        return acceptance.test(type.cast(op));
    }

    void emit(AsmEmitters emitters, MethodVisitor method, IrOp op) {
        emitter.emit(emitters, method, type.cast(op));
    }

    void countAccesses(IrOp op, AsmAccessCounter accessCounter) {
        counter.accept(type.cast(op), accessCounter);
    }

    private static boolean always(IrOp op) {
        return true;
    }

    private static void noAccesses(IrOp op, AsmAccessCounter accessCounter) {
    }
}
