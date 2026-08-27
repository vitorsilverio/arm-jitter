package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/`SHA256SU1`/`SHA1H`/`SHA1SU1`/
/// `SHA256SU0` (B8.11b, ARMv8-A Cryptographic Extension — mesma família de
/// {@link Aarch64CryptoDecoderTest}). Palavras vêm de `aarch64-none-elf-as`/`objdump` reais
/// (devkitA64 disponível nesta sessão, `.arch armv8-a+crypto`) — corpus real, não fórmula.
class Aarch64CryptoShaDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void sha1c() {
        // `sha1c q10, s11, v12.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e0c016a);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA1C, op.op());
        assertEquals(10, op.rd());
        assertEquals(11, op.rn());
        assertEquals(12, op.rm());
    }

    @Test
    void sha1p() {
        // `sha1p q20, s21, v22.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e1612b4);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA1P, op.op());
        assertEquals(20, op.rd());
        assertEquals(21, op.rn());
        assertEquals(22, op.rm());
    }

    @Test
    void sha1m() {
        // `sha1m q0, s31, v0.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e0023e0);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA1M, op.op());
        assertEquals(0, op.rd());
        assertEquals(31, op.rn());
        assertEquals(0, op.rm());
    }

    @Test
    void sha1su0() {
        // `sha1su0 v25.4s, v26.4s, v27.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e1b3359);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA1SU0, op.op());
        assertEquals(25, op.rd());
        assertEquals(26, op.rn());
        assertEquals(27, op.rm());
    }

    @Test
    void sha256h() {
        // `sha256h q28, q29, v30.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e1e43bc);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA256H, op.op());
        assertEquals(28, op.rd());
        assertEquals(29, op.rn());
        assertEquals(30, op.rm());
    }

    @Test
    void sha256h2() {
        // `sha256h2 q1, q2, v3.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e035041);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA256H2, op.op());
        assertEquals(1, op.rd());
        assertEquals(2, op.rn());
        assertEquals(3, op.rm());
    }

    @Test
    void sha256su1() {
        // `sha256su1 v4.4s, v5.4s, v6.4s`
        Ir64Op.CryptoShaThreeRegister op = (Ir64Op.CryptoShaThreeRegister) decodeWord(0x5e0660a4);
        assertEquals(Ir64CryptoShaThreeRegisterOp.SHA256SU1, op.op());
        assertEquals(4, op.rd());
        assertEquals(5, op.rn());
        assertEquals(6, op.rm());
    }

    @Test
    void sha1h() {
        // `sha1h s2, s3`
        Ir64Op.CryptoShaTwoRegister op = (Ir64Op.CryptoShaTwoRegister) decodeWord(0x5e280862);
        assertEquals(Ir64CryptoShaTwoRegisterOp.SHA1H, op.op());
        assertEquals(2, op.rd());
        assertEquals(3, op.rn());
    }

    @Test
    void sha1su1() {
        // `sha1su1 v7.4s, v8.4s`
        Ir64Op.CryptoShaTwoRegister op = (Ir64Op.CryptoShaTwoRegister) decodeWord(0x5e281907);
        assertEquals(Ir64CryptoShaTwoRegisterOp.SHA1SU1, op.op());
        assertEquals(7, op.rd());
        assertEquals(8, op.rn());
    }

    @Test
    void sha256su0() {
        // `sha256su0 v9.4s, v10.4s`
        Ir64Op.CryptoShaTwoRegister op = (Ir64Op.CryptoShaTwoRegister) decodeWord(0x5e282949);
        assertEquals(Ir64CryptoShaTwoRegisterOp.SHA256SU0, op.op());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
    }
}
