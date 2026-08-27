package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/`SHA256SU1`/`SHA1H`/
/// `SHA1SU1`/`SHA256SU0` (B8.11b) direto no executor (interpretador = oráculo, G1) — complementa
/// {@code Aarch64CryptoShaDecoderTest} (decode).
///
/// Os valores esperados vêm de uma reimplementação INDEPENDENTE (Python) do algoritmo público do
/// FIPS PUB 180-4 §6.1.3/§6.2.2 (mesmas funções `Ch`/`Parity`/`Maj`/`Σ0`/`Σ1`/`σ0`/`σ1` — não uma
/// cópia do código Java sob teste), não round-trip interno.
class Ir64CryptoShaExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    private static long pack(int lowWord, int highWord) {
        return (lowWord & 0xFFFFFFFFL) | ((highWord & 0xFFFFFFFFL) << 32);
    }

    private static void assertWords(Aarch64FpRegisters fp, int reg, int w0, int w1, int w2, int w3) {
        assertEquals(pack(w0, w1), fp.low64(reg));
        assertEquals(pack(w2, w3), fp.high64(reg));
    }

    private Aarch64Core coreWithThreeRegisterOperands() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Rd = [1,2,3,4], Rn = [5,6,7,8] (só a palavra 0 importa para SHA1C/P/M), Rm =
        // [0x11111111,0x22222222,0x33333333,0x44444444].
        fp.setQ(0, pack(1, 2), pack(3, 4));
        fp.setQ(1, pack(5, 6), pack(7, 8));
        fp.setQ(2, pack(0x11111111, 0x22222222), pack(0x33333333, 0x44444444));
        return core;
    }

    @Test
    void sha1c() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA1C, 0, 1, 2));
        assertWords(core.fp(), 0, 0x40159415, 0x3bbc687e, 0x9111126a, 0x0444444f);
    }

    @Test
    void sha1p() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA1P, 0, 1, 2));
        assertWords(core.fp(), 0, 0x9df30b39, 0x8ccd75c9, 0xb1111262, 0xc444444e);
    }

    @Test
    void sha1m() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA1M, 0, 1, 2));
        assertWords(core.fp(), 0, 0x80139023, 0xbbbc585e, 0x5111124a, 0x0444444e);
    }

    @Test
    void sha256h() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA256H, 0, 1, 2));
        assertWords(core.fp(), 0, 0xd0183254, 0xa3565ffc, 0x02c1a7e4, 0x65b917a2);
    }

    @Test
    void sha256h2() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA256H2, 0, 1, 2));
        assertWords(core.fp(), 0, 0x67a8d5a0, 0x2145e5fc, 0xf960d01b, 0x1531119f);
    }

    @Test
    void sha256su1() {
        Aarch64Core core = coreWithThreeRegisterOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA256SU1, 0, 1, 2));
        assertWords(core.fp(), 0, 0xfff3333a, 0xaabbbbc4, 0xffc5dcd6, 0xbbc17ff9);
    }

    @Test
    void sha1su0() {
        // Rd = words [1,2,3,4], Rn = words [5,...] (só a palavra 0 importa), Rm = words [7,8,9,10].
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, pack(1, 2), pack(3, 4));
        fp.setQ(1, pack(5, 6), pack(7, 8));
        fp.setQ(2, pack(7, 8), pack(9, 10));

        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA1SU0, 0, 1, 2));

        assertEquals(0x0000000e00000005L, fp.low64(0));
        assertEquals(0x000000080000000fL, fp.high64(0));
    }

    @Test
    void sha1h() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x12345678L, 0xFFFFFFFFFFFFFFFFL); // metade alta de Rn ignorada (só word0).

        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA1H, 0, 1));

        assertWords(core.fp(), 0, 0x048d159e, 0, 0, 0);
    }

    @Test
    void sha1su1() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, pack(1, 2), pack(3, 4));
        fp.setQ(1, pack(0x11111111, 0x22222222), pack(0x33333333, 0x44444444));

        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA1SU1, 0, 1));

        assertWords(core.fp(), 0, 0x44444446, 0x66666662, 0x8888888e, 0x88888884);
    }

    @Test
    void sha256su0() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, pack(1, 2), pack(3, 4));
        fp.setQ(1, pack(0x11111111, 0x22222222), pack(0x33333333, 0x44444444));

        EXECUTOR.executeOp(core, new Ir64Op.CryptoShaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA256SU0, 0, 1));

        assertWords(core.fp(), 0, 0x04008001, 0x0600c002, 0x08010003, 0x64444448);
    }
}
