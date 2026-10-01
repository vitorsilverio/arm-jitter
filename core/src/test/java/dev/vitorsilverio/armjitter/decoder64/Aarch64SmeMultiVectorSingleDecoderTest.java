package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// SME2 multi-vetor "multiple-and-single" destrutivo — B18.7. **Toda palavra do teste parametrizado foi conferida
/// contra `aarch64-none-elf-as -march=armv9.5-a+sme2+fp8` (devkitA64)**: o assembly de cada linha foi escrito
/// primeiro, a palavra extraída com `objdump`, e os campos esperados (operação, `esz`, `count`, `zdn`, `zm`) saem do
/// TEXTO do assembly, nunca do decoder. O comentário de cada caso é o assembly.
class Aarch64SmeMultiVectorSingleDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-mvs-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2_FP8 = Aarch64Architecture.extending(SME2,
            "teste-mvs-dec-SME2-FP8", Aarch64Feature.FP8);
    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    /// `palavra, operação, esz, count, zdn, zm`.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC124A002, SMAX, 0, 2, 2, 4",   // smax {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129A804, SMAX, 0, 4, 4, 9",   // smax {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A002, SMAX, 1, 2, 2, 4",   // smax {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169A804, SMAX, 1, 4, 4, 9",   // smax {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A002, SMAX, 2, 2, 2, 4",   // smax {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9A804, SMAX, 2, 4, 4, 9",   // smax {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A002, SMAX, 3, 2, 2, 4",   // smax {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9A804, SMAX, 3, 4, 4, 9",   // smax {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A003, UMAX, 0, 2, 2, 4",   // umax {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129A805, UMAX, 0, 4, 4, 9",   // umax {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A003, UMAX, 1, 2, 2, 4",   // umax {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169A805, UMAX, 1, 4, 4, 9",   // umax {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A003, UMAX, 2, 2, 2, 4",   // umax {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9A805, UMAX, 2, 4, 4, 9",   // umax {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A003, UMAX, 3, 2, 2, 4",   // umax {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9A805, UMAX, 3, 4, 4, 9",   // umax {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A022, SMIN, 0, 2, 2, 4",   // smin {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129A824, SMIN, 0, 4, 4, 9",   // smin {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A022, SMIN, 1, 2, 2, 4",   // smin {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169A824, SMIN, 1, 4, 4, 9",   // smin {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A022, SMIN, 2, 2, 2, 4",   // smin {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9A824, SMIN, 2, 4, 4, 9",   // smin {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A022, SMIN, 3, 2, 2, 4",   // smin {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9A824, SMIN, 3, 4, 4, 9",   // smin {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A023, UMIN, 0, 2, 2, 4",   // umin {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129A825, UMIN, 0, 4, 4, 9",   // umin {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A023, UMIN, 1, 2, 2, 4",   // umin {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169A825, UMIN, 1, 4, 4, 9",   // umin {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A023, UMIN, 2, 2, 2, 4",   // umin {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9A825, UMIN, 2, 4, 4, 9",   // umin {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A023, UMIN, 3, 2, 2, 4",   // umin {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9A825, UMIN, 3, 4, 4, 9",   // umin {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A222, SRSHL, 0, 2, 2, 4",   // srshl {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129AA24, SRSHL, 0, 4, 4, 9",   // srshl {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A222, SRSHL, 1, 2, 2, 4",   // srshl {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169AA24, SRSHL, 1, 4, 4, 9",   // srshl {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A222, SRSHL, 2, 2, 2, 4",   // srshl {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9AA24, SRSHL, 2, 4, 4, 9",   // srshl {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A222, SRSHL, 3, 2, 2, 4",   // srshl {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9AA24, SRSHL, 3, 4, 4, 9",   // srshl {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A223, URSHL, 0, 2, 2, 4",   // urshl {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129AA25, URSHL, 0, 4, 4, 9",   // urshl {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A223, URSHL, 1, 2, 2, 4",   // urshl {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169AA25, URSHL, 1, 4, 4, 9",   // urshl {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A223, URSHL, 2, 2, 2, 4",   // urshl {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9AA25, URSHL, 2, 4, 4, 9",   // urshl {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A223, URSHL, 3, 2, 2, 4",   // urshl {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9AA25, URSHL, 3, 4, 4, 9",   // urshl {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A302, ADD, 0, 2, 2, 4",   // add {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129AB04, ADD, 0, 4, 4, 9",   // add {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A302, ADD, 1, 2, 2, 4",   // add {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169AB04, ADD, 1, 4, 4, 9",   // add {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A302, ADD, 2, 2, 2, 4",   // add {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9AB04, ADD, 2, 4, 4, 9",   // add {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A302, ADD, 3, 2, 2, 4",   // add {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9AB04, ADD, 3, 4, 4, 9",   // add {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC124A402, SQDMULH, 0, 2, 2, 4",   // sqdmulh {z2.b-z3.b}, {z2.b-z3.b}, z4.b
            "0xC129AC04, SQDMULH, 0, 4, 4, 9",   // sqdmulh {z4.b-z7.b}, {z4.b-z7.b}, z9.b
            "0xC164A402, SQDMULH, 1, 2, 2, 4",   // sqdmulh {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC169AC04, SQDMULH, 1, 4, 4, 9",   // sqdmulh {z4.h-z7.h}, {z4.h-z7.h}, z9.h
            "0xC1A4A402, SQDMULH, 2, 2, 2, 4",   // sqdmulh {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1A9AC04, SQDMULH, 2, 4, 4, 9",   // sqdmulh {z4.s-z7.s}, {z4.s-z7.s}, z9.s
            "0xC1E4A402, SQDMULH, 3, 2, 2, 4",   // sqdmulh {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1E9AC04, SQDMULH, 3, 4, 4, 9",   // sqdmulh {z4.d-z7.d}, {z4.d-z7.d}, z9.d
            "0xC164A102, FMAX, 1, 2, 2, 4",   // fmax {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC16FA91C, FMAX, 1, 4, 28, 15",   // fmax {z28.h-z31.h}, {z28.h-z31.h}, z15.h
            "0xC1A4A102, FMAX, 2, 2, 2, 4",   // fmax {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1AFA91C, FMAX, 2, 4, 28, 15",   // fmax {z28.s-z31.s}, {z28.s-z31.s}, z15.s
            "0xC1E4A102, FMAX, 3, 2, 2, 4",   // fmax {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1EFA91C, FMAX, 3, 4, 28, 15",   // fmax {z28.d-z31.d}, {z28.d-z31.d}, z15.d
            "0xC164A103, FMIN, 1, 2, 2, 4",   // fmin {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC16FA91D, FMIN, 1, 4, 28, 15",   // fmin {z28.h-z31.h}, {z28.h-z31.h}, z15.h
            "0xC1A4A103, FMIN, 2, 2, 2, 4",   // fmin {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1AFA91D, FMIN, 2, 4, 28, 15",   // fmin {z28.s-z31.s}, {z28.s-z31.s}, z15.s
            "0xC1E4A103, FMIN, 3, 2, 2, 4",   // fmin {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1EFA91D, FMIN, 3, 4, 28, 15",   // fmin {z28.d-z31.d}, {z28.d-z31.d}, z15.d
            "0xC164A122, FMAXNM, 1, 2, 2, 4",   // fmaxnm {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC16FA93C, FMAXNM, 1, 4, 28, 15",   // fmaxnm {z28.h-z31.h}, {z28.h-z31.h}, z15.h
            "0xC1A4A122, FMAXNM, 2, 2, 2, 4",   // fmaxnm {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1AFA93C, FMAXNM, 2, 4, 28, 15",   // fmaxnm {z28.s-z31.s}, {z28.s-z31.s}, z15.s
            "0xC1E4A122, FMAXNM, 3, 2, 2, 4",   // fmaxnm {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1EFA93C, FMAXNM, 3, 4, 28, 15",   // fmaxnm {z28.d-z31.d}, {z28.d-z31.d}, z15.d
            "0xC164A123, FMINNM, 1, 2, 2, 4",   // fminnm {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC16FA93D, FMINNM, 1, 4, 28, 15",   // fminnm {z28.h-z31.h}, {z28.h-z31.h}, z15.h
            "0xC1A4A123, FMINNM, 2, 2, 2, 4",   // fminnm {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1AFA93D, FMINNM, 2, 4, 28, 15",   // fminnm {z28.s-z31.s}, {z28.s-z31.s}, z15.s
            "0xC1E4A123, FMINNM, 3, 2, 2, 4",   // fminnm {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1EFA93D, FMINNM, 3, 4, 28, 15",   // fminnm {z28.d-z31.d}, {z28.d-z31.d}, z15.d
            "0xC164A182, FSCALE, 1, 2, 2, 4",   // fscale {z2.h-z3.h}, {z2.h-z3.h}, z4.h
            "0xC16FA99C, FSCALE, 1, 4, 28, 15",   // fscale {z28.h-z31.h}, {z28.h-z31.h}, z15.h
            "0xC1A4A182, FSCALE, 2, 2, 2, 4",   // fscale {z2.s-z3.s}, {z2.s-z3.s}, z4.s
            "0xC1AFA99C, FSCALE, 2, 4, 28, 15",   // fscale {z28.s-z31.s}, {z28.s-z31.s}, z15.s
            "0xC1E4A182, FSCALE, 3, 2, 2, 4",   // fscale {z2.d-z3.d}, {z2.d-z3.d}, z4.d
            "0xC1EFA99C, FSCALE, 3, 4, 28, 15",   // fscale {z28.d-z31.d}, {z28.d-z31.d}, z15.d
    })
    void decodesEveryEncodingFromTheAssembler(String hex, Ir64Op.SmeMultiVectorSingle.Op expectedOp, int esz, int count,
            int zdn, int zm) {
        Ir64Op.SmeMultiVectorSingle op = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2_FP8, (int) Long.decode(hex).longValue()));
        assertEquals(expectedOp, op.op());
        assertEquals(esz, op.esz());
        assertEquals(count, op.count());
        assertEquals(zdn, op.zdn(), "zdn é o campo × count (%zd_ax2/%zd_ax4), nunca o campo cru");
        assertEquals(zm, op.zm());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @Test
    void groupBaseMultipliesTheFieldByTheCount() {
        // %zd_ax2 = bits[4:1] × 2: campo 0b0011 endereça Z6, não Z3.
        assertEquals(6, Aarch64SmeDecoder.groupBase(0b0011 << 1, 1, 2));
        // %zd_ax4 = bits[4:2] × 4: campo 0b101 endereça Z20, não Z5.
        assertEquals(20, Aarch64SmeDecoder.groupBase(0b101 << 2, 2, 4));
        // As outras bases da família B18.8-B18.12 (Armadilha 1): %zm_ax2 = bits[20:17], %zm_ax4 = bits[20:18],
        // %zn_ax2 = bits[9:6], %zn_ax4 = bits[9:7].
        assertEquals(12, Aarch64SmeDecoder.groupBase(0b0110 << 17, 17, 2));
        assertEquals(28, Aarch64SmeDecoder.groupBase(0b111 << 18, 18, 4));
        assertEquals(30, Aarch64SmeDecoder.groupBase(0b1111 << 6, 6, 2));
        assertEquals(16, Aarch64SmeDecoder.groupBase(0b100 << 7, 7, 4));
        // Bits fora do campo não vazam para o registrador.
        assertEquals(6, Aarch64SmeDecoder.groupBase(0xFFFFFFE0 | 0b0011 << 1, 1, 2));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "0xC129A806", // smax x4 com o bit 1 ligado (@z2z_4x1 fixa `...0 .`)
            "0xC124A303", // add com U = 1 (só existe ADD_n1, U = 0)
            "0xC124A403", // sqdmulh com U = 1
            "0xC164A183", // fscale com U = 1
            "0xC124A042", // opcode 00010 sem linha no .decode
            "0xC124A102", // fmax .b: o espaço esz = 0 de ponto flutuante é de BFMAX_n1 (outro gate)
            "0xC124A122", // fmaxnm .b
            "0xC124A182", // fscale .b
            "0xC134A002", // bit 20 ligado (zm:4 não alcança Z16): [21:20] = 11 não é desta família
            "0xC104A002", // bits[21:20] = 00
            "0xC324A002", // bit 25 ligado: fora do prefixo 1100000
            "0xC124B002", // bit 12 ligado: fora de 1010
    })
    void neighbourEncodingsStayUnimplemented(String hex) {
        int word = (int) Long.decode(hex).longValue();
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2_FP8, word), hex);
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture sme = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-mvs-dec-SME",
                Aarch64Feature.FP8);
        for (int word : new int[] {0xC124A002, 0xC129A804, 0xC164A222, 0xC1A4A402, 0xC1A4A102, 0xC164A182}) {
            assertThrows(UnsupportedOperationException.class, () -> decode(sme, word), Integer.toHexString(word));
        }
    }

    @Test
    void fscaleAlsoNeedsFp8ButTheOtherTwelveDoNot() {
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, 0xC164A182));
        assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class, decode(SME2, 0xC164A102));
        assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class, decode(SME2_FP8, 0xC164A182));
    }
}
