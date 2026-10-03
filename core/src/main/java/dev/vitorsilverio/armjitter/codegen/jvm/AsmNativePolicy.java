package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Derivada do registro de emissores ({@code AsmEmitterRegistry}, task E15.7): uma op é nativa se o
/// seu `record` tem emissor e o predicado da entrada a aceita. Política e compilador não podem
/// divergir. Record sem emissor (NEON, MVE, `_hp`, CRC32, Security Extension, ...) roda no
/// interpretado — o bloco inteiro em `WHOLE_BLOCK`, só a op em `PER_OP`.
///
/// As ops são suportadas para qualquer condição: o {@code AsmBlockCompiler} emite um guard
/// `evalCond` por op, espelhando o interpretador. Os predicados recusam por motivos
/// NÃO-condicionais (escrita em PC, BLX, `unprivileged`, opcodes sem emissor). As regras de cada
/// família estão no `register` do seu emissor (`AsmAluEmitter`, `AsmIntegerEmitter`,
/// `AsmMemoryEmitter`, `AsmControlEmitter`, `AsmVfpEmitter`).
public final class AsmNativePolicy {
    private AsmNativePolicy() {
    }

    /// `true` se todas as ops do bloco são nativas.
    public static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    /// `true` se `op` tem emissão nativa nesta instância (ver a classe).
    public static boolean supports(IrOp op) {
        return AsmEmitterRegistry.supports(op);
    }

    /// Opcodes ALU atualmente emitidos nativamente. Desde a task B1.6, todos os opcodes ALU
    /// (incl. os ARMv6 de extend/reverse/pack de B1.2-B1.3) são suportados, com duas rejeições:
    /// dst=15+setFlags (por instância) e {@link IrOpCode#ORN} (Thumb-2, B2.2 — opcode novo sem
    /// emissão nativa ainda).
    public static Set<IrOpCode> supportedAluOpcodes() {
        EnumSet<IrOpCode> supported = EnumSet.allOf(IrOpCode.class);
        supported.remove(IrOpCode.ORN);
        return supported;
    }
}
