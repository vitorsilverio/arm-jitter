package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrMemoryExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria de memória (task A6): `Load`, `Store`, `LoadLiteral`,
/// `DoubleTransfer`, `Swap`, `LoadExclusive`, `StoreExclusive`, `ClearExclusive`. Delega DIRETO a
/// {@link IrMemoryExecutor} — nenhum cálculo de endereço/alinhamento reimplementado (G1).
///
/// <p>TODA op desta categoria (exceto `ClearExclusive`, que só mexe no monitor do core) acaba
/// chamando `AddressSpace#read*`/`write*` por baixo — implementação do hospedeiro, opaca para o
/// Graal. Por isso o dispatch inteiro roda atrás de {@link TruffleBoundary} (item 3 da
/// especificação: "chamadas a AddressSpace... e SÓ neles"); como `IrMemoryExecutor` é do módulo
/// `core/` e não pode depender de `truffle-api` (G3/A1), o ponto de fronteira mais fino possível
/// SEM tocar o módulo core é a chamada inteira a partir daqui, não o `read`/`write` individual —
/// um recorte mais grosso do que o item 3 sugere literalmente, mas o único compatível com a
/// separação de módulos já estabelecida.</p>
final class MemoryOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrMemoryExecutor executor;

    MemoryOpNode(IrOp op, IrMemoryExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return executeAtBoundary(core);
    }

    @TruffleBoundary
    private boolean executeAtBoundary(ArmCore core) {
        return switch (op) {
            case IrOp.Load load -> executor.executeLoad(core, load);
            case IrOp.Store store -> {
                executor.executeStore(core, store);
                yield false;
            }
            case IrOp.LoadLiteral loadLiteral -> executor.executeLoadLiteral(core, loadLiteral);
            case IrOp.DoubleTransfer doubleTransfer -> executor.executeDoubleTransfer(core, doubleTransfer);
            case IrOp.Swap swap -> executor.executeSwap(core, swap);
            case IrOp.LoadExclusive loadExclusive -> {
                executor.executeLoadExclusive(core, loadExclusive);
                yield false;
            }
            case IrOp.StoreExclusive storeExclusive -> {
                executor.executeStoreExclusive(core, storeExclusive);
                yield false;
            }
            case IrOp.ClearExclusive clearExclusive -> {
                executor.executeClearExclusive(core, clearExclusive);
                yield false;
            }
            default -> throw new IllegalStateException("MemoryOpNode não cobre: " + op);
        };
    }
}
