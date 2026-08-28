package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `EOR3`/`BCAX`/`RAX1`/`XAR` (`FEAT_SHA3`, ARMv8.2-A, B11.12). Palavras vêm de
/// `aarch64-linux-gnu-as`/`objdump` reais (WSL2 Ubuntu, `.arch armv8.2-a+sha3`) — corpus real, não
/// fórmula. O decoder DEFAULT (`ARMv8.0-A`) rejeita as 4 (sem `FEAT_SHA3`); um decoder
/// `Aarch64Architecture.ARMV8_2_A` aceita.
class Aarch64CryptoSha3DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SHA3_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void eor3() {
        // `eor3 v0.16b, v1.16b, v2.16b, v3.16b`
        Ir64Op.CryptoSha3FourRegister op =
                (Ir64Op.CryptoSha3FourRegister) decodeWord(SHA3_DECODER, 0xce020c20);
        assertEquals(Ir64CryptoSha3Op.EOR3, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.ra());
    }

    @Test
    void eor3SecondForm() {
        // `eor3 v10.16b, v11.16b, v12.16b, v13.16b`
        Ir64Op.CryptoSha3FourRegister op =
                (Ir64Op.CryptoSha3FourRegister) decodeWord(SHA3_DECODER, 0xce0c356a);
        assertEquals(Ir64CryptoSha3Op.EOR3, op.op());
        assertEquals(10, op.rd());
        assertEquals(11, op.rn());
        assertEquals(12, op.rm());
        assertEquals(13, op.ra());
    }

    @Test
    void bcax() {
        // `bcax v0.16b, v1.16b, v2.16b, v3.16b`
        Ir64Op.CryptoSha3FourRegister op =
                (Ir64Op.CryptoSha3FourRegister) decodeWord(SHA3_DECODER, 0xce220c20);
        assertEquals(Ir64CryptoSha3Op.BCAX, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.ra());
    }

    @Test
    void bcaxSecondForm() {
        // `bcax v14.16b, v15.16b, v16.16b, v1.16b`
        Ir64Op.CryptoSha3FourRegister op =
                (Ir64Op.CryptoSha3FourRegister) decodeWord(SHA3_DECODER, 0xce3005ee);
        assertEquals(Ir64CryptoSha3Op.BCAX, op.op());
        assertEquals(14, op.rd());
        assertEquals(15, op.rn());
        assertEquals(16, op.rm());
        assertEquals(1, op.ra());
    }

    @Test
    void rax1() {
        // `rax1 v0.2d, v1.2d, v2.2d`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(SHA3_DECODER, 0xce628c20);
        assertEquals(Ir64CryptoSha3Op.RAX1, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void rax1SecondForm() {
        // `rax1 v20.2d, v21.2d, v22.2d`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(SHA3_DECODER, 0xce768eb4);
        assertEquals(Ir64CryptoSha3Op.RAX1, op.op());
        assertEquals(20, op.rd());
        assertEquals(21, op.rn());
        assertEquals(22, op.rm());
    }

    @Test
    void xarImmediateZero() {
        // `xar v0.2d, v1.2d, v2.2d, #0`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(SHA3_DECODER, 0xce820020);
        assertEquals(Ir64CryptoSha3Op.XAR, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(0, op.rotateAmount());
    }

    @Test
    void xarImmediateSeventeen() {
        // `xar v3.2d, v4.2d, v5.2d, #17`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(SHA3_DECODER, 0xce854483);
        assertEquals(Ir64CryptoSha3Op.XAR, op.op());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
        assertEquals(17, op.rotateAmount());
    }

    @Test
    void xarImmediateSixtyThree() {
        // `xar v6.2d, v7.2d, v8.2d, #63`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(SHA3_DECODER, 0xce88fce6);
        assertEquals(Ir64CryptoSha3Op.XAR, op.op());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
        assertEquals(63, op.rotateAmount());
    }

    @Test
    void defaultArchitectureRejectsEor3() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce020c20));
    }

    @Test
    void defaultArchitectureRejectsBcax() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce220c20));
    }

    @Test
    void defaultArchitectureRejectsRax1() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce628c20));
    }

    @Test
    void defaultArchitectureRejectsXar() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce820020));
    }
}
