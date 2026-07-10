package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Contrato comum para decodificadores ARM e THUMB.
public interface InstructionDecoder {
    /// Decodifica a instrucao no `address` informado usando o barramento recebido.
    DecodedInstruction decode(AddressSpace memory, int address);
}
