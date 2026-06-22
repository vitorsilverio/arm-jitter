package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

import java.util.concurrent.CopyOnWriteArrayList;

/// Registro global de {@link IrOp} para fallback por-op no bytecode JVM gerado.
///
/// <p>Em modo {@link dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy#PER_OP}, ops não suportadas
/// nativamente são registradas aqui em tempo de compilação (via {@link #register}) e invocadas
/// pelo bytecode gerado por meio de {@link #executeInterpreted}.</p>
///
/// <p>O registro é permanente — entradas não são removidas. Na prática o crescimento é limitado
/// ao número de ops não-nativas em blocos únicos compilados no modo {@code PER_OP}.</p>
///
/// <p><b>Limitação:</b> {@link #EXECUTOR} é estático; a última instância de
/// {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter} a ser construída define o executor.
/// Para uso com múltiplas arquiteturas simultâneas, crie apenas um emissor ativo por vez.</p>
public final class IrOpInterop {
    /// Lista append-only indexada pelo id (denso/sequencial). Substitui um {@code Map<Integer,IrOp>}
    /// para evitar o boxing do `int opId` + lookup de hash a CADA op interpretada no caminho quente
    /// (o `get(id)` aqui é só um acesso de array no snapshot volátil — sem boxing). A escrita
    /// (compilação, fria) é serializada para garantir `id == índice`.
    private static final CopyOnWriteArrayList<IrOp> REGISTRY = new CopyOnWriteArrayList<>();

    private static volatile IrBlockExecutor EXECUTOR;

    /// Define o executor interpretado. Chamado pelo construtor de
    /// {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
    public static void setExecutor(IrBlockExecutor executor) {
        EXECUTOR = executor;
    }

    private IrOpInterop() {
    }

    /// Registra uma op e devolve o id a ser emitido como constante no bytecode gerado.
    /// Serializado para que o id devolvido seja exatamente o índice na lista.
    static int register(IrOp op) {
        synchronized (REGISTRY) {
            int id = REGISTRY.size();
            REGISTRY.add(op);
            return id;
        }
    }

    /// Chamado pelo bytecode JVM gerado para executar uma op registrada via interpretador.
    ///
    /// @param core       núcleo ARM em execução
    /// @param opId       id retornado por {@link #register} em tempo de compilação
    /// @param blockEndPc PC sequencial do fim do bloco (usado por {@link IrOp.Swi})
    /// @return {@code true} se a op alterou o PC
    public static boolean executeInterpreted(ArmCore core, int opId, int blockEndPc) {
        return EXECUTOR.executeOp(core, REGISTRY.get(opId), blockEndPc);
    }
}
