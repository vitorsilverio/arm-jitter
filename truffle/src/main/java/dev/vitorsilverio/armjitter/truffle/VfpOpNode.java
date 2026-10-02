package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrSystemExecutor;
import dev.vitorsilverio.armjitter.codegen.executor.IrVfpExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;

/// Nó Truffle para a categoria de VFP (task A10.3): `VfpOp.Alu`, `VfpOp.MoveImmediate`, `VfpOp.Compare`,
/// `VfpOp.Convert`, `VfpOp.Load`, `VfpOp.Store`, `VfpOp.MultipleTransfer`, `VfpOp.CoreTransfer`,
/// `VfpOp.CorePairTransfer`, `VfpOp.SystemTransfer`, `VfpOp.CorePairTransferSingle`, `VfpOp.ConvertFixed`,
/// `CoprocessorDouble`, `MProfileSystemRegister` — o maior bloco descoberto sem nó especializado
/// (14 `Kind`): qualquer binário ARM com uma instrução de ponto flutuante usava o fallback do
/// bloco inteiro. Delega DIRETO a {@link IrVfpExecutor} (nenhuma semântica de FPSCR/arredondamento
/// reimplementada, G1) — exceto `CoprocessorDouble` e `MProfileSystemRegister`, que vivem em
/// {@link IrSystemExecutor} por agrupamento do plano da A10.3 (o primeiro não é estritamente VFP,
/// é o espaço `MCRR`/`MRRC`; o segundo é do perfil M, não VFP).
///
/// <p>Só `VfpOp.Load`/`VfpOp.Store`/`VfpOp.MultipleTransfer` (acessam `AddressSpace`, implementação opaca
/// do hospedeiro) e `CoprocessorDouble` (acessa `CoprocessorBus`, idem) escapam para código do
/// hospedeiro — só esses ficam atrás de {@link TruffleBoundary} (item 3 da especificação da A6,
/// mesmo critério de {@link SystemOpNode}). O resto só lê/escreve `VfpRegisters`/`FPSCR`/CPSR/
/// registradores ARM — aberto ao partial evaluation.</p>
final class VfpOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrVfpExecutor vfpExecutor;
    private final IrSystemExecutor systemExecutor;

    VfpOpNode(IrOp op, IrVfpExecutor vfpExecutor, IrSystemExecutor systemExecutor) {
        super(op.condition());
        this.op = op;
        this.vfpExecutor = vfpExecutor;
        this.systemExecutor = systemExecutor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return switch (op) {
            case VfpOp.Alu vfpAlu -> {
                vfpExecutor.executeVfpAlu(core, vfpAlu);
                yield false;
            }
            case VfpOp.MoveImmediate vfpMoveImmediate -> {
                vfpExecutor.executeVfpMoveImmediate(core, vfpMoveImmediate);
                yield false;
            }
            case VfpOp.Compare vfpCompare -> {
                vfpExecutor.executeVfpCompare(core, vfpCompare);
                yield false;
            }
            case VfpOp.Convert vfpConvert -> {
                vfpExecutor.executeVfpConvert(core, vfpConvert);
                yield false;
            }
            case VfpOp.Load vfpLoad -> executeVfpLoadAtBoundary(vfpLoad, core);
            case VfpOp.Store vfpStore -> executeVfpStoreAtBoundary(vfpStore, core);
            case VfpOp.MultipleTransfer vfpMultipleTransfer ->
                    executeVfpMultipleTransferAtBoundary(vfpMultipleTransfer, core);
            case VfpOp.CoreTransfer vfpCoreTransfer -> {
                vfpExecutor.executeVfpCoreTransfer(core, vfpCoreTransfer);
                yield false;
            }
            case VfpOp.CorePairTransfer vfpCorePairTransfer -> {
                vfpExecutor.executeVfpCorePairTransfer(core, vfpCorePairTransfer);
                yield false;
            }
            case VfpOp.SystemTransfer vfpSystemTransfer -> {
                vfpExecutor.executeVfpSystemTransfer(core, vfpSystemTransfer);
                yield false;
            }
            case VfpOp.CorePairTransferSingle vfpCorePairTransferSingle -> {
                vfpExecutor.executeVfpCorePairTransferSingle(core, vfpCorePairTransferSingle);
                yield false;
            }
            case VfpOp.ConvertFixed vfpConvertFixed -> {
                vfpExecutor.executeVfpConvertFixed(core, vfpConvertFixed);
                yield false;
            }
            case SystemOp.CoprocessorDouble coprocessorDouble -> executeCoprocessorDoubleAtBoundary(coprocessorDouble, core);
            case SystemOp.MProfileSystemRegister mProfileSystemRegister -> {
                systemExecutor.executeMProfileSystemRegister(core, mProfileSystemRegister);
                yield false;
            }
            default -> throw new IllegalStateException("VfpOpNode não cobre: " + op);
        };
    }

    @TruffleBoundary
    private boolean executeVfpLoadAtBoundary(VfpOp.Load vfpLoad, ArmCore core) {
        vfpExecutor.executeVfpLoad(core, vfpLoad);
        return false;
    }

    @TruffleBoundary
    private boolean executeVfpStoreAtBoundary(VfpOp.Store vfpStore, ArmCore core) {
        vfpExecutor.executeVfpStore(core, vfpStore);
        return false;
    }

    @TruffleBoundary
    private boolean executeVfpMultipleTransferAtBoundary(VfpOp.MultipleTransfer vfpMultipleTransfer, ArmCore core) {
        vfpExecutor.executeVfpMultipleTransfer(core, vfpMultipleTransfer);
        return false;
    }

    @TruffleBoundary
    private boolean executeCoprocessorDoubleAtBoundary(SystemOp.CoprocessorDouble coprocessorDouble, ArmCore core) {
        return systemExecutor.executeCoprocessorDouble(core, coprocessorDouble);
    }
}
