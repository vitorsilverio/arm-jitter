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
    /// Existe só para o caso Thumb-2 de 32 bits (`ArmArchitecture#thumb32DecoderExtensions`):
    /// `ThumbDecoder` usa esta distinção para decidir, quando nenhuma extensão reivindica
    /// (`tryDecode` devolveu `null`) um candidato `top5 == 0b11101`/`0b11111`, entre UNDEFINED
    /// controlado (alguma extensão reconhece o prefixo, mas não esse sub-encoding — o mesmo
    /// tratamento que `top5 == 0b11110` já recebe) e delegar ao caminho legado ARMv4T/v5T de
    /// `BL`/`BLX` em dois halfwords (nenhuma extensão reconhece nada ali — a ambiguidade real com
    /// o sufixo `BL`/`BLX`, que não pode ser resolvida só com os bits deste halfword). Extensões
    /// que não têm essa ambiguidade (ex. decoders ARM clássico, como {@link
    /// dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder}) não precisam sobrepor este método —
    /// o padrão `false` preserva o contrato anterior (um único `null` para os dois casos).
    default boolean claimsEncodingSpace(int raw) {
        return false;
    }
}
