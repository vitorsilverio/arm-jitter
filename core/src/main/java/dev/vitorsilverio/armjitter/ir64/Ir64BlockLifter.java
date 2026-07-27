package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Construtor de {@link Ir64Block}s a partir da memória do dispositivo — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.ir.IrBlockLifter} (32 bits), introduzido na task B6.4.
public interface Ir64BlockLifter {
    /// Decodifica e eleva instruções a partir de `startPc` até um terminal (`Branch64`/
    /// `CompareBranch64`/`Svc`) ou até `maxInstructions`.
    Ir64Block lift(AddressSpace64 memory, long startPc, int maxInstructions);
}
