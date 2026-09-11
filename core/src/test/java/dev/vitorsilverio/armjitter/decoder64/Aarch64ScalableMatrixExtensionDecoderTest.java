package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `MSR SVCR<mask>, #imm` (`FEAT_SME`, ARMv9.2-A, B19.28) — ao contrário das outras features
/// residuais do épico B19, esta NUNCA decodifica com sucesso: nenhum estado ZA/streaming-SVE é
/// modelado ({@link dev.vitorsilverio.armjitter.arch64.Aarch64Feature#SCALABLE_MATRIX_EXTENSION}).
/// O que a task garante é que a recusa vira DUAS formas distintas de
/// {@link UnsupportedOperationException}: (1) sem `FEAT_SME` na arquitetura, o mesmo `unsupported`
/// genérico de qualquer outra feature ausente; (2) COM `FEAT_SME` (`ARMV9_2_A`), uma mensagem
/// NOMEADA identificando "SVCR reconhecida, mas FEAT_SME não implementado" — G8: rastreável, não
/// indistinguível de um encoding realmente desconhecido.
class Aarch64ScalableMatrixExtensionDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);

    /// `msr svcrsm, #1` (mask=0b01, imm=1) — confirmado byte a byte contra
    /// `aarch64-linux-gnu-as -march=armv9-a+sme` (WSL); o assembler mostra como alias `smstart sm`.
    private static final int MSR_SVCRSM_1_WORD = 0xd503437f;

    private static Ir64OpAndMessage decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> decoder.decode(AddressSpace64.wrapping(raw), 0));
        return new Ir64OpAndMessage(exception.getMessage());
    }

    private record Ir64OpAndMessage(String message) {
    }

    @Test
    void rejectedGenericallyWithoutSme() {
        String message = decode(DEFAULT_DECODER, MSR_SVCRSM_1_WORD).message();
        assertTrue(message.contains("fora da fatia B6.1"),
                "esperava a mensagem genérica de feature ausente, obteve: " + message);
    }

    @Test
    void rejectedByNameWithSme() {
        String message = decode(SME_DECODER, MSR_SVCRSM_1_WORD).message();
        assertTrue(message.contains("SVCR"), "mensagem deveria citar SVCR: " + message);
        assertTrue(message.contains("FEAT_SME"), "mensagem deveria citar FEAT_SME: " + message);
        assertTrue(message.contains("mask=0b1"), "mensagem deveria citar o mask decodificado: " + message);
        assertTrue(message.contains("imm=1"), "mensagem deveria citar o imm decodificado: " + message);
    }
}
