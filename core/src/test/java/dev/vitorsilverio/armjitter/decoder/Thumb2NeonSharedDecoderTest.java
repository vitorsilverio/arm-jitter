package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// `Thumb2NeonSharedDecoder` (B13.22): casca fina que reusa {@link NeonSharedDecoder} para o
/// espaço Thumb-2 sem transformação (`neon-shared.decode` tem encoding idêntico A32/T32). Espelha
/// {@link Thumb2VfpDecoder} — ver o Javadoc da classe.
class Thumb2NeonSharedDecoderTest {
    private static final ArmArchitecture FEATURES = ArmArchitecture.extending(ArmArchitecture.ARMV7A,
            "ARMv7-TestThumb2NeonShared", ArmFeature.COMPLEX_NUMBER_ARITHMETIC);

    private static final Thumb2NeonSharedDecoder DECODER = new Thumb2NeonSharedDecoder(FEATURES);

    /// `vcmla.f32 d0,d1,d2,#0` — mesmo encoding golden de `NeonSharedDecoderTest#vcmlaVector`.
    private static final int VCMLA = 0xFC30_0800;

    @Test
    void relabelsTheDelegateResultAsThumb() {
        DecodedInstruction decoded = DECODER.tryDecode(VCMLA, 0, Condition.AL);
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    @Test
    void returnsTheSameKindTheDelegateWould() {
        DecodedInstruction viaWrapper = DECODER.tryDecode(VCMLA, 0, Condition.AL);
        DecodedInstruction viaDelegate = new NeonSharedDecoder(FEATURES).tryDecode(VCMLA, 0, Condition.AL);
        assertSame(viaDelegate.kind(), viaWrapper.kind());
    }

    /// **Achado real desta task (B13.22), não hipotético**: ao contrário de todo outro
    /// `DecoderExtension` do projeto (que devolve `null` para "não é o meu espaço"),
    /// {@link NeonSharedDecoder} NUNCA devolve `null` — a B13.21 fechou o "null debt" do arquivo
    /// fazendo QUALQUER `raw` que não bata em nenhuma das 23 linhas cair em `unimplemented(...)`
    /// explícito (G8), sem checar primeiro se o `raw` sequer está dentro do frame `neon-shared`. A
    /// casca herda esse contrato: propaga o que quer que o delegate devolva, nunca `null` também.
    /// **Consequência para quem registra este decoder numa `ArmArchitecture`**: ele TEM que vir
    /// por ÚLTIMO na lista de `DecoderExtension`, senão nenhum decoder depois dele é alcançado —
    /// ver o Javadoc de {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture#ARMV7A_NEON}, achado
    /// confirmado por probe direto contra um `MCR` comum que virava `UNIMPLEMENTED` antes da correção
    /// de ordem.
    @Test
    void neverReturnsNullEvenForAWordOutsideTheNeonSharedFrame() {
        assertNotNull(DECODER.tryDecode(0x0000_0000, 0, Condition.AL));
        assertEquals(InstructionKind.UNIMPLEMENTED, DECODER.tryDecode(0x0000_0000, 0, Condition.AL).kind());
    }
}
