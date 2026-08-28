package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `ALLINT` (`FEAT_NMI`, `ARMv8.8-A`, B11.8) — quarto gate real de
/// {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (RDM=B11.4, WFxT=B11.6, FlagM=B11.7).
/// Diferente das 3 anteriores, `ALLINT` tem DUAS formas de encoding gateadas pela MESMA feature:
/// `MSR (immediate)` (`decodeFlagOrPstateImmediate`) e `MRS`/`MSR (register)`
/// (`decodeSystemRegister`). `MSR (immediate) ALLINT` já decodificava com sucesso desde B8.3; a
/// forma registrador desde B8.17. Palavra imediata do corpus REAL (`corpus.bin` offset `0x5e4`,
/// mesma que `Aarch64DecoderCorpusTest#msrAllint` validava antes desta task); palavras registrador
/// sintéticas (mesmas de `Aarch64PstateFieldRegistersTest#allintDecodes`, não presentes no corpus).
class Aarch64NmiDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder NMI_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_8_A);

    private static final int MSR_IMMEDIATE_ALLINT_WORD = 0xd501411f;
    private static final int MRS_ALLINT_WORD = 0xd5384306;
    private static final int MSR_REGISTER_ALLINT_WORD = 0xd5184306;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutNmi() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(DEFAULT_DECODER, MSR_IMMEDIATE_ALLINT_WORD));
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(DEFAULT_DECODER, MRS_ALLINT_WORD));
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(DEFAULT_DECODER, MSR_REGISTER_ALLINT_WORD));
    }

    @Test
    void msrImmediateAllintDecodesWithNmi() {
        Ir64Op.SystemInstruction op =
                (Ir64Op.SystemInstruction) decodeWord(NMI_DECODER, MSR_IMMEDIATE_ALLINT_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void mrsAllintDecodesWithNmi() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) decodeWord(NMI_DECODER, MRS_ALLINT_WORD);
        assertEquals(Aarch64SystemRegisterId.ALLINT, op.register());
    }

    @Test
    void msrRegisterAllintDecodesWithNmi() {
        Ir64Op.SystemRegister op =
                (Ir64Op.SystemRegister) decodeWord(NMI_DECODER, MSR_REGISTER_ALLINT_WORD);
        assertEquals(Aarch64SystemRegisterId.ALLINT, op.register());
    }
}
