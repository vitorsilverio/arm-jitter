package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.concurrent.CopyOnWriteArrayList;

/// Registro global de {@link Ir64Op} para fallback por-op no bytecode JVM A64 gerado — espelho
/// estrutural de {@link dev.vitorsilverio.armjitter.codegen.jvm.IrOpInterop} (32 bits), introduzido
/// na task C12.2.
///
/// Em modo {@link dev.vitorsilverio.armjitter.codegen64.Asm64FallbackPolicy#PER_OP}, ops não
/// suportadas nativamente por {@link Ir64NativePolicy} são registradas aqui em tempo de compilação
/// (via {@link #register}) e invocadas pelo bytecode gerado por meio de {@link #executeInterpreted}.
///
/// O registro é permanente — entradas não são removidas. Na prática o crescimento é limitado
/// ao número de ops não-nativas em blocos ÚNICOS compilados no modo {@code PER_OP}.
///
/// **Multiarquitetura:** cada op é registrada COM o {@link Ir64BlockExecutor} que a compilou (lista
/// {@link #EXECUTORS} paralela a {@link #REGISTRY}), mesma disciplina do precedente 32-bit — nunca
/// um executor estático global compartilhado entre dois {@code Asm64CodeEmitter} de arquiteturas
/// diferentes (ver o Javadoc de {@code IrOpInterop} sobre a corrupção não-determinística que um
/// singleton causou antes).
public final class Ir64OpInterop {
    /// Lista append-only indexada pelo id (denso/sequencial) — mesma disciplina de
    /// {@code IrOpInterop#REGISTRY} (evita boxing/hash no caminho quente).
    private static final CopyOnWriteArrayList<Ir64Op> REGISTRY = new CopyOnWriteArrayList<>();

    /// Executor por-op, alinhado por índice a {@link #REGISTRY} — o que registrou a op.
    private static final CopyOnWriteArrayList<Ir64BlockExecutor> EXECUTORS = new CopyOnWriteArrayList<>();

    private Ir64OpInterop() {
    }

    /// Registra uma op + o executor que a compilou e devolve o id a ser emitido como constante
    /// no bytecode gerado. Serializado para que o id devolvido seja exatamente o índice nas listas.
    static int register(Ir64Op op, Ir64BlockExecutor executor) {
        synchronized (REGISTRY) {
            int id = REGISTRY.size();
            REGISTRY.add(op);
            EXECUTORS.add(executor);
            return id;
        }
    }

    /// Chamado pelo bytecode JVM gerado para executar uma op registrada via interpretador, usando
    /// o executor que compilou a op.
    ///
    /// @param core  núcleo AArch64 em execução
    /// @param opId  id retornado por {@link #register} em tempo de compilação
    /// @return {@code true} se a própria operação já alterou o PC (desvio tomado)
    public static boolean executeInterpreted(Aarch64Core core, int opId) {
        return EXECUTORS.get(opId).executeOp(core, REGISTRY.get(opId));
    }
}
