package dev.vitorsilverio.armjitter.arch;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;

/// Decodifica encodings de instruções que uma arquitetura adiciona além do conjunto base compartilhado ARMv4T
/// (ex. o espaço ARMv5 BLX/DSP, ou um futuro grupo Thumb-2). O decoder base consulta
/// as extensões da {@link ArmArchitecture} atual antes de recorrer a UNIMPLEMENTED,
/// para que novos grupos de instruções sejam conectados aqui sem tocar no decoder compartilhado.
@FunctionalInterface
public interface DecoderExtension {
    /// Tenta decodificar a palavra ARM já buscada. Retorna {@code null} se esta
    /// extensão não manipula o encoding (para que o decoder base continue tentando).
    DecodedInstruction tryDecode(int raw, int address, Condition condition);
}
