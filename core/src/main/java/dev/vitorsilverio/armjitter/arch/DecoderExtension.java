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

    /// Distingue, para um `raw` em que {@link #tryDecode} devolveu `null`, "não é meu espaço"
    /// (`false`, o padrão) de "é estruturalmente meu espaço [um prefixo de bits fixo que esta
    /// extensão reconhece], mas esse sub-encoding específico é reservado/não implementado"
    /// (`true`) — B2.2.2.
    ///
    /// **Obsoleto para `ThumbDecoder#tryDecodeThumb32` desde B2.6**: a ambiguidade que motivou este
    /// método (o "fantasma" formado ao reler o segundo halfword de um `BL`/`BLX` legado como se
    /// fosse um novo prefixo Thumb-2) deixou de existir — B2.6 decodifica `BL`/`BLX` imediato como
    /// instrução única de 32 bits, então `decode()` nunca mais chama `tryDecodeThumb32` no endereço
    /// de um sufixo em código são. `ThumbDecoder` não chama mais este método; ele permanece na
    /// interface por G3 (compat) e para uso futuro por outra extensão que precise da mesma
    /// distinção "meu espaço, mas reservado" vs. "não é meu espaço".
    default boolean claimsEncodingSpace(int raw) {
        return false;
    }
}
