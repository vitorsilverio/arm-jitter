package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// **E17** — misdecodes que a tabela de ISA media `✅` (a palavra decodificava, mas como OUTRA instrução) e
/// aceitações de encoding não alocado (G8), achados pela conferência de assinatura por linha. Palavras
/// conferidas no `objdump` do devkitA64.
class Aarch64DecoderE17MisdecodeTest {
    /// Primeiro preset com `FEAT_FHM` — onde `MUL_vi`/… `.s` viravam `FMLAL`.
    private static final Aarch64Decoder FHM = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);
    private static final Aarch64Decoder PAUTH = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);
    private static final Aarch64Decoder ALL = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(8);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    /// O desvio do `FMLAL_vi` só conferia `opcode & 0b0011 == 0`; com `U ≠ top` é a forma `.s` das
    /// instruções inteiras por elemento.
    @ParameterizedTest
    @CsvSource({
            "0x4f818043, MUL",     // mul v3.4s, v2.4s, v1.s[0]
            "0x6f810043, MLA",     // mla v3.4s, v2.4s, v1.s[0]
            "0x6f814043, MLS",     // mls v3.4s, v2.4s, v1.s[0]
            "0x4f81c043, SQDMULH", // sqdmulh v3.4s, v2.4s, v1.s[0]
    })
    void integerByElementWordFormIsNotFmlal(String word, Ir64VectorThreeSameOp expected) {
        Ir64Op op = decode(FHM, Integer.decode(word));
        assertEquals(expected,
                assertInstanceOf(AdvSimdIntegerOp64.ArithmeticThreeSameByElement.class, op).op());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x0f810043, 0x2f818043}) // fmlal / fmlal2 v3.2s, v2.2h, v1.h[0]
    void fmlalByElementStillDecodes(int word) {
        assertInstanceOf(AdvSimdFpOp64.FpMultiplyAddLongByElement.class, decode(FHM, word));
    }

    /// `op2=011` só é `WFI` com `CRm=0`; `PACIBSP` (#27) e `GCSB DSYNC` (#19) são NOP aqui.
    @ParameterizedTest
    @ValueSource(ints = {0xd503237f, 0xd503227f})
    void hintsWithWfiOp2ButNonZeroCrmAreNotWfi(int word) {
        assertEquals(Ir64SystemInstructionOp.NOP_HINT,
                assertInstanceOf(SystemOp64.SystemInstruction.class, decode(ALL, word)).opcode());
    }

    @Test
    void wfiStillDecodesAsWfi() {
        assertEquals(Ir64SystemInstructionOp.WFI,
                assertInstanceOf(SystemOp64.SystemInstruction.class, decode(ALL, 0xd503207f)).opcode());
    }

    @Test
    void pointerAuthZeroModifierFormRequiresRnAllOnes() {
        assertThrows(UnsupportedOperationException.class, () -> decode(PAUTH, 0xdac12822)); // Z=1, Rn=1
        IntegerOp64.PointerAuthInPlace pacdza = assertInstanceOf(IntegerOp64.PointerAuthInPlace.class,
                decode(PAUTH, 0xdac12be2)); // pacdza x2
        assertEquals(Ir64PointerAuthOp.PACDA, pacdza.op());
    }

    @Test
    void compareAndSwapPairRequiresEvenRegisters() {
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, 0x08217c43)); // Rs=1
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, 0x08227c45)); // Rt=5
        assertInstanceOf(MemoryOp64.CompareAndSwapPair.class, decode(ALL, 0x08227c44)); // casp w2, w3, w4, w5
    }

    /// `bit23=1` no espaço add/sub imediato é `ADDG`/`SUBG` (`FEAT_MTE`) ou min/max imediato
    /// (`FEAT_CSSC`) — ainda não implementados, então recusados em vez de ADD/SUB.
    @ParameterizedTest
    @ValueSource(ints = {0x11c00022, 0x91800000, 0xd1800000}) // smax w2, w1, #0 · addg · subg
    void addSubImmediateWithBit23IsRefused(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(ALL, word));
    }

    @Test
    void plainAddImmediateStillDecodes() {
        IntegerOp64.Alu64 add = assertInstanceOf(IntegerOp64.Alu64.class, decode(ALL, 0x91000422));
        assertEquals(Ir64AluOp.ADD, add.opcode());
    }
}
