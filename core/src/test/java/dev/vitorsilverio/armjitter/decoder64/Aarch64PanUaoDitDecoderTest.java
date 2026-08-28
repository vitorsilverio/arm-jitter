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

/// `PAN` (`FEAT_PAN`, `ARMv8.1-A`) + `UAO` (`FEAT_UAO`, `ARMv8.2-A`) + `DIT` (`FEAT_DIT`,
/// `ARMv8.4-A`), formas `MSR (immediate)` (B8.3) e `MRS`/`MSR (register)` (B8.17) — sexto gate real
/// de {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (B11.10; os anteriores foram
/// `FEAT_RDM`/B11.4, `FEAT_WFxT`/B11.6, `FEAT_FlagM`/B11.7, `FEAT_NMI`/B11.8, `FEAT_FlagM2`+
/// `CFINV`/B11.9). As 3 features decodificavam com SUCESSO incondicionalmente desde B8.3/B8.17 —
/// aqui passam a exigir a arquitetura correta, mesmo padrão G8 já estabelecido. Corpus REAL já
/// existente em `src/test/resources/aarch64/corpus.bin` (offsets `0x5cc`/`0x5d0`/`0x5e0`, mesmas
/// palavras que `Aarch64DecoderCorpusTest#msrUao`/`#msrPan`/`#msrDit` validavam antes desta task);
/// palavras da forma registrador conferidas contra `aarch64-none-elf-as -march=armv8.5-a` real
/// (mesmo corpus de `Aarch64PstateFieldRegistersTest`, B8.17).
class Aarch64PanUaoDitDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder PAN_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);
    private static final Aarch64Decoder UAO_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);
    private static final Aarch64Decoder DIT_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_4_A);

    private static final int MSR_I_UAO_WORD = 0xd500417f;
    private static final int MSR_I_PAN_WORD = 0xd500419f;
    private static final int MSR_I_DIT_WORD = 0xd503415f;
    private static final int MRS_PAN_WORD = 0xd5384262;
    private static final int MSR_REG_PAN_WORD = 0xd5184262;
    private static final int MRS_UAO_WORD = 0xd5384281;
    private static final int MSR_REG_UAO_WORD = 0xd5184281;
    private static final int MRS_DIT_WORD = 0xd53b42a3;
    private static final int MSR_REG_DIT_WORD = 0xd51b42a3;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitecture() {
        int[] words = {
                MSR_I_UAO_WORD, MSR_I_PAN_WORD, MSR_I_DIT_WORD,
                MRS_PAN_WORD, MSR_REG_PAN_WORD, MRS_UAO_WORD, MSR_REG_UAO_WORD,
                MRS_DIT_WORD, MSR_REG_DIT_WORD,
        };
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, word));
        }
    }

    @Test
    void armv81AcceptsPanButRejectsUaoAndDit() {
        Ir64Op.SystemInstruction imm = (Ir64Op.SystemInstruction) decodeWord(PAN_DECODER, MSR_I_PAN_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, imm.opcode());
        assertEquals(Aarch64SystemRegisterId.PAN, ((Ir64Op.SystemRegister) decodeWord(PAN_DECODER, MRS_PAN_WORD)).register());
        assertEquals(Aarch64SystemRegisterId.PAN, ((Ir64Op.SystemRegister) decodeWord(PAN_DECODER, MSR_REG_PAN_WORD)).register());

        assertThrows(UnsupportedOperationException.class, () -> decodeWord(PAN_DECODER, MSR_I_UAO_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(PAN_DECODER, MRS_UAO_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(PAN_DECODER, MSR_I_DIT_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(PAN_DECODER, MRS_DIT_WORD));
    }

    @Test
    void armv82AcceptsPanAndUaoButRejectsDit() {
        Ir64Op.SystemInstruction immPan = (Ir64Op.SystemInstruction) decodeWord(UAO_DECODER, MSR_I_PAN_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, immPan.opcode());
        Ir64Op.SystemInstruction immUao = (Ir64Op.SystemInstruction) decodeWord(UAO_DECODER, MSR_I_UAO_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, immUao.opcode());
        assertEquals(Aarch64SystemRegisterId.UAO, ((Ir64Op.SystemRegister) decodeWord(UAO_DECODER, MRS_UAO_WORD)).register());
        assertEquals(Aarch64SystemRegisterId.UAO, ((Ir64Op.SystemRegister) decodeWord(UAO_DECODER, MSR_REG_UAO_WORD)).register());

        assertThrows(UnsupportedOperationException.class, () -> decodeWord(UAO_DECODER, MSR_I_DIT_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(UAO_DECODER, MRS_DIT_WORD));
    }

    @Test
    void armv84AcceptsAllThree() {
        Ir64Op.SystemInstruction immPan = (Ir64Op.SystemInstruction) decodeWord(DIT_DECODER, MSR_I_PAN_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, immPan.opcode());
        Ir64Op.SystemInstruction immUao = (Ir64Op.SystemInstruction) decodeWord(DIT_DECODER, MSR_I_UAO_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, immUao.opcode());
        Ir64Op.SystemInstruction immDit = (Ir64Op.SystemInstruction) decodeWord(DIT_DECODER, MSR_I_DIT_WORD);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, immDit.opcode());

        assertEquals(Aarch64SystemRegisterId.PAN, ((Ir64Op.SystemRegister) decodeWord(DIT_DECODER, MRS_PAN_WORD)).register());
        assertEquals(Aarch64SystemRegisterId.UAO, ((Ir64Op.SystemRegister) decodeWord(DIT_DECODER, MRS_UAO_WORD)).register());
        assertEquals(Aarch64SystemRegisterId.DIT, ((Ir64Op.SystemRegister) decodeWord(DIT_DECODER, MRS_DIT_WORD)).register());
        assertEquals(Aarch64SystemRegisterId.DIT, ((Ir64Op.SystemRegister) decodeWord(DIT_DECODER, MSR_REG_DIT_WORD)).register());
    }
}
