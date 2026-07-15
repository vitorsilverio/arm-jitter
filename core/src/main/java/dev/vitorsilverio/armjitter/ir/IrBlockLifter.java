package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Construtor de blocos IR a partir de memoria do dispositivo.
public interface IrBlockLifter {
    /// Decodifica e eleva instrucoes a partir de `startPc` ate um terminal ou limite.
    ///
    /// Equivalente a {@link #lift(AddressSpace, int, int, int)} com `itStateAtEntry = 0` (fora de
    /// um Thumb-2 IT block) — preserva o comportamento/assinatura anteriores à task B2.4 (G3).
    default IrBlock lift(AddressSpace memory, int startPc, int maxInstructions) {
        return lift(memory, startPc, maxInstructions, 0);
    }

    /// Decodifica e eleva instrucoes a partir de `startPc` ate um terminal ou limite, semeando o
    /// estado local do Thumb-2 IT block (B2.4) com o ITSTATE\[7:0\] ATUAL no momento do lift (`0`
    /// quando `startPc` não cai no meio de um IT block ativo) — ver D1 em
    /// `b2.4-thumb2-branches-it.md`: o chamador (ex. `JitRuntime`) deve ler
    /// `core.cpsr().itState()` e passá-lo aqui, e também incluí-lo na chave de cache do bloco
    /// resultante quando não-zero.
    IrBlock lift(AddressSpace memory, int startPc, int maxInstructions, int itStateAtEntry);
}
