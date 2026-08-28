package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `EOR3`/`BCAX`/`RAX1`/`XAR` (`FEAT_SHA3`, B11.12) direto no executor (interpretador =
/// oráculo, G1) — complementa {@code Aarch64CryptoSha3DecoderTest} (decode).
///
/// Os valores esperados vêm de uma reimplementação INDEPENDENTE (Python, `rotl`/`rotr` sobre 64
/// bits) da definição pública do `ARM DDI 0487` (`EOR3`/`BCAX`/`RAX1`/`XAR` pseudocódigo) — não
/// round-trip interno nem cópia do Java sob teste.
class Ir64CryptoSha3ExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static final long N_LO = 0x1111111111111111L;
    private static final long N_HI = 0x2222222222222222L;
    private static final long M_LO = 0x3333333333333333L;
    private static final long M_HI = 0x4444444444444444L;
    private static final long A_LO = 0x0F0F0F0F0F0F0F0FL;
    private static final long A_HI = 0xF0F0F0F0F0F0F0F0L;

    private static Aarch64Core coreWithOperands() {
        TestAddressSpace raw = new TestAddressSpace(64);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(raw));
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, N_LO, N_HI);
        fp.setQ(2, M_LO, M_HI);
        fp.setQ(3, A_LO, A_HI);
        return core;
    }

    @Test
    void eor3() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3FourRegister(Ir64CryptoSha3Op.EOR3, 0, 1, 2, 3));
        assertEquals(0x2d2d2d2d2d2d2d2dL, core.fp().low64(0));
        assertEquals(0x9696969696969696L, core.fp().high64(0));
    }

    @Test
    void bcax() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3FourRegister(Ir64CryptoSha3Op.BCAX, 0, 1, 2, 3));
        assertEquals(0x2121212121212121L, core.fp().low64(0));
        assertEquals(0x2626262626262626L, core.fp().high64(0));
    }

    @Test
    void rax1() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.RAX1, 0, 1, 2, 0));
        assertEquals(0x7777777777777777L, core.fp().low64(0));
        assertEquals(0xaaaaaaaaaaaaaaaaL, core.fp().high64(0));
    }

    @Test
    void xarRotateZero() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, 0, 1, 2, 0));
        assertEquals(0x2222222222222222L, core.fp().low64(0));
        assertEquals(0x6666666666666666L, core.fp().high64(0));
    }

    @Test
    void xarRotateSeventeen() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, 0, 1, 2, 17));
        assertEquals(0x1111111111111111L, core.fp().low64(0));
        assertEquals(0x3333333333333333L, core.fp().high64(0));
    }

    @Test
    void xarRotateSixtyThree() {
        Aarch64Core core = coreWithOperands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, 0, 1, 2, 63));
        assertEquals(0x4444444444444444L, core.fp().low64(0));
        assertEquals(0xccccccccccccccccL, core.fp().high64(0));
    }
}
