package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `AESE`/`AESD`/`AESMC`/`AESIMC`/`PMULL`/`PMULL2` (B8.11) direto no executor
/// (interpretador = oráculo, G1) — complementa {@code Aarch64CryptoDecoderTest} (decode).
///
/// A S-box/`MixColumns` são DERIVADAS matematicamente (ver javadoc de {@link Ir64CryptoExecutor}),
/// então estes testes usam fatos PÚBLICOS e independentes do FIPS PUB 197 para verificar o
/// resultado (`S-box(0)=0x63`/`InvS-box(0)=0x52`, e a primeira coluna real da matriz
/// `MixColumns`/`InvMixColumns`), não só round-trip interno.
class Ir64CryptoExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void aeseWithZeroInputProducesUniformSBoxOfZero() {
        // Rd=0, Rn=0 -> XOR=0 -> SubBytes(0) = 0x63 em todo byte (FIPS 197, S-box(0x00)=0x63) ->
        // ShiftRows de um valor uniforme não muda nada.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0L, 0L);
        fp.setQ(1, 0L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESE, 0, 1));

        assertEquals(0x6363636363636363L, fp.low64(0));
        assertEquals(0x6363636363636363L, fp.high64(0));
    }

    @Test
    void aesdWithZeroInputProducesUniformInverseSBoxOfZero() {
        // FIPS 197, InvS-box(0x00)=0x52.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(2, 0L, 0L);
        fp.setQ(3, 0L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESD, 2, 3));

        assertEquals(0x5252525252525252L, fp.low64(2));
        assertEquals(0x5252525252525252L, fp.high64(2));
    }

    @Test
    void aesdInvertsAeseWhenBothOperandsAreZeroKey() {
        // AESD(AESE(x,0),0) == x sempre (InvSubBytes/InvShiftRows são inversas exatas de
        // SubBytes/ShiftRows, e SubBytes comuta com a permutação de ShiftRows).
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x0706050403020100L, 0x0F0E0D0C0B0A0908L);
        fp.setQ(1, 0L, 0L); // "round key" zero

        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESE, 0, 1));
        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESD, 0, 1));

        assertEquals(0x0706050403020100L, fp.low64(0));
        assertEquals(0x0F0E0D0C0B0A0908L, fp.high64(0));
    }

    @Test
    void aesmcAppliesFirstColumnOfRealMixColumnsMatrix() {
        // Coluna 0 de Rn = (0x01,0,0,0); MixColumns real (FIPS 197 §5.1.3) multiplica pela matriz
        // {02,03,01,01; 01,02,03,01; 01,01,02,03; 03,01,01,02} -> coluna resultante = (02,01,01,03).
        // Demais colunas (entrada zero) permanecem zero.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(5, 0x0000000000000001L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESMC, 4, 5));

        assertEquals(0x0000000003010102L, fp.low64(4));
        assertEquals(0L, fp.high64(4));
    }

    @Test
    void aesimcInvertsAesmc() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(5, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);

        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESMC, 4, 5));
        EXECUTOR.executeOp(core, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESIMC, 6, 4));

        assertEquals(0x1122334455667788L, fp.low64(6));
        assertEquals(0x99AABBCCDDEEFF00L, fp.high64(6));
    }

    @Test
    void pmullP8MultipliesEachByteLaneWithoutCarry() {
        // Lane 0: a=0x03 (x+1), b=0x05 (x^2+1) -> (x+1)(x^2+1) = x^3+x^2+x+1 = 0x0F, sem redução.
        // Demais lanes zero.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(9, 0x0000000000000003L, 0L);
        fp.setQ(10, 0x0000000000000005L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorPolynomialMultiplyLong(false, false, 8, 9, 10));

        assertEquals(0x000000000000000FL, fp.low64(8));
        assertEquals(0L, fp.high64(8));
    }

    @Test
    void pmull2P8UsesUpperHalfOfSourceRegisters() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Metade baixa lixo (não deve ser usada); metade alta byte0 = 0x03/0x05.
        fp.setQ(9, 0xFFFFFFFFFFFFFFFFL, 0x0000000000000003L);
        fp.setQ(10, 0xFFFFFFFFFFFFFFFFL, 0x0000000000000005L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorPolynomialMultiplyLong(false, true, 8, 9, 10));

        assertEquals(0x000000000000000FL, fp.low64(8));
        assertEquals(0L, fp.high64(8));
    }

    @Test
    void pmullP64MultipliesSimpleBitPattern() {
        // a=x (0b10), b=x (0b10) -> x*x = x^2 (0b100 = 4), sem carry.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(15, 2L, 0L);
        fp.setQ(16, 2L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorPolynomialMultiplyLong(true, false, 14, 15, 16));

        assertEquals(4L, fp.low64(14));
        assertEquals(0L, fp.high64(14));
    }

    @Test
    void pmullP64CarriesIntoHighHalfPastBit63() {
        // a=1, b=(1<<63) -> resultado = a deslocado 63 bits, cruza para a metade alta dos 128 bits.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(15, 1L, 0L);
        fp.setQ(16, Long.MIN_VALUE /* 1L << 63 */, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorPolynomialMultiplyLong(true, false, 14, 15, 16));

        assertEquals(Long.MIN_VALUE, fp.low64(14));
        assertEquals(0L, fp.high64(14));
    }

    @Test
    void pmull2P64UsesUpperHalfOfSourceRegisters() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(15, 0xFFFFFFFFFFFFFFFFL, 2L);
        fp.setQ(16, 0xFFFFFFFFFFFFFFFFL, 2L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorPolynomialMultiplyLong(true, true, 14, 15, 16));

        assertEquals(4L, fp.low64(14));
        assertEquals(0L, fp.high64(14));
    }
}
