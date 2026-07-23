package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Casca fina que reusa {@link VfpDecoder} para o espaço Thumb-2 de 32 bits (B3.5): o `raw32` das
/// instruções VFP Thumb-2 tem o MESMO layout de bits que o encoding ARM clássico que
/// {@link VfpDecoder} já decodifica (ARM DDI 0406C A5.3, hw1 fixo `1110 110x`/`1110 1110`,
/// confirmado no QEMU `target/arm/tcg/vfp.decode`: "Encodings for the conditional VFP instructions
/// ... and T32 1110 110. .... .... .... 101. .... .... / 1110 1110 .... .... .... 101. .... ....")
/// — exatamente o mesmo espaço `bits[27:24] ∈ {1100,1101,1110}` que {@link VfpDecoder} já checa a
/// partir de `bits[27:24]` para baixo, nunca olhando `bits[31:28]` (que no ARM é a condição real e
/// no Thumb-2 é sempre o prefixo fixo `1110` do primeiro halfword) — mesmo raciocínio de
/// {@link Thumb2CoprocessorDecoder} reusando {@link CoprocessorDecoder}.
///
/// A única correção necessária é a marca de {@link InstructionSet}: {@link VfpDecoder} sempre
/// devolve {@link InstructionSet#ARM} — esta casca reescreve para {@link InstructionSet#THUMB} via
/// {@link DecodedInstruction#withInstructionSet}, necessário para que
/// `StandardIrBlockLifter#instructionWidth`/`isThumb32` classifiquem a instrução corretamente.
/// Anexado via {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando
/// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#THUMB2} está habilitado (o chamador,
/// {@link ThumbDecoder}, só invoca as extensões de 32 bits com essa feature ligada).
public final class Thumb2VfpDecoder implements DecoderExtension {
    private final VfpDecoder delegate;

    public Thumb2VfpDecoder(ArmArchitecture architecture) {
        this.delegate = new VfpDecoder(architecture);
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        DecodedInstruction decoded = delegate.tryDecode(raw, address, condition);
        if (decoded == null) {
            return null;
        }
        return decoded.withInstructionSet(InstructionSet.THUMB);
    }

    @Override
    public boolean claimsEncodingSpace(int raw) {
        return delegate.claimsEncodingSpace(raw);
    }
}
