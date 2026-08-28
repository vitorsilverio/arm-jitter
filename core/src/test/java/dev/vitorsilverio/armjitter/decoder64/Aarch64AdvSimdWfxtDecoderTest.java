package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `WFET`/`WFIT` (`FEAT_WFxT`, `ARMv8.7-A`, B11.6) — segundo gate real de
/// {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (o primeiro foi `FEAT_RDM`, B11.4).
/// Diferente de `FEAT_RDM`, estas 2 instruções já decodificavam com SUCESSO incondicionalmente
/// desde B8.3 — o gate agora faz o decoder DEFAULT (`ARMv8.0-A`) passar a rejeitá-las (comportamento
/// correto: um Cortex-A53 real não tem `FEAT_WFxT`), continuando a aceitar só com `ARMv8.7-A`+.
/// Corpus REAL já existente em `src/test/resources/aarch64/corpus.bin`
/// (`corpus.objdump.txt` linhas 377-378: `wfet x0`/`wfit x1`, offsets `0x5b4`/`0x5b8`).
class Aarch64AdvSimdWfxtDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder WFXT_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_7_A);

    private static final int WFET_X0 = 0xd5031000;
    private static final int WFIT_X1 = 0xd5031021;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutWfxt() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, WFET_X0));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, WFIT_X1));
    }

    @Test
    void wfetDecodesAsNopHintWithWfxt() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) decodeWord(WFXT_DECODER, WFET_X0);
        assertEquals(Ir64SystemInstructionOp.NOP_HINT, op.opcode());
    }

    @Test
    void wfitDecodesAsWfiWithWfxt() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) decodeWord(WFXT_DECODER, WFIT_X1);
        assertEquals(Ir64SystemInstructionOp.WFI, op.opcode());
    }
}
