package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Construtor de blocos IR a partir de memoria do dispositivo.
public interface IrBlockLifter {
    /// Decodifica e eleva instrucoes a partir de `startPc` ate um terminal ou limite.
    IrBlock lift(AddressSpace memory, int startPc, int maxInstructions);
}
