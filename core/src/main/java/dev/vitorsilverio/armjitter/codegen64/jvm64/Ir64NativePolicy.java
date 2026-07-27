package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decide se um {@link Ir64Block} pode ser emitido nativamente pelo
/// {@code Asm64CodeEmitter} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy} (32 bits), introduzido na task
/// B6.4 (PR1).
///
/// PR1 cobre só o conjunto de ops da B6.1 (reta + desvios):
/// {@link Ir64Op.Kind#ALU64}/{@link Ir64Op.Kind#MOVE_WIDE}/{@link Ir64Op.Kind#PC_RELATIVE}/
/// {@link Ir64Op.Kind#BRANCH64}/{@link Ir64Op.Kind#COMPARE_BRANCH64}/
/// {@link Ir64Op.Kind#CYCLE}/{@link Ir64Op.Kind#FETCH}. Todo o resto (`Svc`, loads/stores da
/// B6.2, o conjunto B6.3.1-B6.3.4) permanece fora até PR2/PR3 (ver
/// `b6.4-aarch64-asm-backend.md`) — um bloco com qualquer op fora dessa lista cai inteiro no
/// {@link dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter} (política
/// `WHOLE_BLOCK`, único modo desta task — sem `PER_OP`/`FAIL_FAST` ainda).
public final class Ir64NativePolicy {
    private Ir64NativePolicy() {
    }

    /// `true` quando TODAS as ops do bloco são suportadas nativamente.
    public static boolean supports(Ir64Block block) {
        for (Ir64Op op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    /// `true` quando a operação é suportada nativamente pelo `Ir64BlockCompiler` do PR1.
    public static boolean supports(Ir64Op op) {
        return switch (op.kind()) {
            case Ir64Op.Kind.ALU64,
                 Ir64Op.Kind.MOVE_WIDE,
                 Ir64Op.Kind.PC_RELATIVE,
                 Ir64Op.Kind.BRANCH64,
                 Ir64Op.Kind.COMPARE_BRANCH64,
                 Ir64Op.Kind.CYCLE,
                 Ir64Op.Kind.FETCH -> true;
            default -> false;
        };
    }
}
