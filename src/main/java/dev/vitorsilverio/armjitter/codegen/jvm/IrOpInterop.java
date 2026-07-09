package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

import java.util.concurrent.CopyOnWriteArrayList;

/// Registro global de {@link IrOp} para fallback por-op no bytecode JVM gerado.
///
/// Em modo {@link dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy#PER_OP}, ops não suportadas
/// nativamente são registradas aqui em tempo de compilação (via {@link #register}) e invocadas
/// pelo bytecode gerado por meio de {@link #executeInterpreted}.
///
/// O registro é permanente — entradas não são removidas. Na prática o crescimento é limitado
/// ao número de ops não-nativas em blocos únicos compilados no modo {@code PER_OP}.
///
/// **Multiarquitetura:** cada op é registrada COM o {@link IrBlockExecutor} da arquitetura
/// que a compilou (lista {@link #EXECUTORS} paralela a {@link #REGISTRY}). Assim dois runtimes
/// simultâneos de arquiteturas diferentes (ex.: NDS ARM9 ARMv5TE + ARM7 ARMv4T) nunca compartilham
/// um executor. Antes o executor era um singleton estático e o ÚLTIMO emissor construído vencia,
/// então as ops de fallback ARMv5 do ARM9 (BLX/CLZ/DSP/sat/LDRD) rodavam com semântica ARMv4T
/// assim que um bloco compilava em background — corrupção só-com-cache-quente e não-determinística.
public final class IrOpInterop {
    /// Lista append-only indexada pelo id (denso/sequencial). Substitui um {@code Map<Integer,IrOp>}
    /// para evitar o boxing do `int opId` + lookup de hash a CADA op interpretada no caminho quente
    /// (o `get(id)` aqui é só um acesso de array no snapshot volátil — sem boxing). A escrita
    /// (compilação, fria) é serializada para garantir `id == índice`.
    private static final CopyOnWriteArrayList<IrOp> REGISTRY = new CopyOnWriteArrayList<>();

    /// Executor por-op, alinhado por índice a {@link #REGISTRY} — o da arquitetura que registrou a op.
    private static final CopyOnWriteArrayList<IrBlockExecutor> EXECUTORS = new CopyOnWriteArrayList<>();

    private IrOpInterop() {
    }

    /// Registra uma op + o executor da sua arquitetura e devolve o id a ser emitido como constante
    /// no bytecode gerado. Serializado para que o id devolvido seja exatamente o índice nas listas.
    static int register(IrOp op, IrBlockExecutor executor) {
        synchronized (REGISTRY) {
            int id = REGISTRY.size();
            REGISTRY.add(op);
            EXECUTORS.add(executor);
            return id;
        }
    }

    /// Chamado pelo bytecode JVM gerado para executar uma op registrada via interpretador, usando
    /// o executor da arquitetura que compilou a op.
    ///
    /// @param core       núcleo ARM em execução
    /// @param opId       id retornado por {@link #register} em tempo de compilação
    /// @param blockEndPc PC sequencial do fim do bloco (usado por {@link IrOp.Swi})
    /// @return {@code true} se a op alterou o PC
    public static boolean executeInterpreted(ArmCore core, int opId, int blockEndPc) {
        return EXECUTORS.get(opId).executeOp(core, REGISTRY.get(opId), blockEndPc);
    }
}
