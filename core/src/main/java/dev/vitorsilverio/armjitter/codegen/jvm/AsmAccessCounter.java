package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrOperand;

/// Contagem de acessos a GPR feitos em bytecode pelos ops de um bloco — decide quais registradores
/// viram locais JVM (ver {@link AsmBlockCompiler}). Cada {@link AsmEmission} com emissão direta
/// declara os seus acessos; ops que tocam registradores só dentro de helper (spilled) não contam.
final class AsmAccessCounter {
    final int[] accesses = new int[AsmEmitterBase.CACHEABLE_REGISTERS];
    final boolean[] writes = new boolean[AsmEmitterBase.CACHEABLE_REGISTERS];

    void read(int reg) {
        if (reg >= 0 && reg < AsmEmitterBase.CACHEABLE_REGISTERS) {
            accesses[reg]++;
        }
    }

    void write(int reg) {
        if (reg >= 0 && reg < AsmEmitterBase.CACHEABLE_REGISTERS) {
            accesses[reg]++;
            writes[reg] = true;
        }
    }

    /// Conta as leituras de registrador de um operando (Register ou ShiftedRegister).
    void operand(IrOperand operand) {
        if (operand instanceof IrOperand.Register reg && reg.valueOverride() < 0) {
            read(reg.index());
        } else if (operand instanceof IrOperand.ShiftedRegister sr) {
            if (sr.valueOverride() == -1) {
                read(sr.index());
            }
            if (sr.amountRegister() >= 0 && sr.amountValueOverride() == -1) {
                read(sr.amountRegister());
            }
        }
    }
}
