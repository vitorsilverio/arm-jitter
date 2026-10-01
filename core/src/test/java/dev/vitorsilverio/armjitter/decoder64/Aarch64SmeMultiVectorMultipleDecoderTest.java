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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// SME2 multi-vetor "multiple vectors" (grupo × grupo) destrutivo — B18.8. **Toda palavra do teste parametrizado foi
/// conferida contra `aarch64-none-elf-as -march=armv9.5-a+sme2+fp8+faminmax` (devkitA64)**: o assembly de cada linha
/// foi escrito primeiro, a palavra extraída com `objdump`, e os campos esperados (operação, `esz`, `count`, `zdn`,
/// `zm`) saem do TEXTO do assembly, nunca do decoder. O comentário de cada caso é o assembly.
class Aarch64SmeMultiVectorMultipleDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-mvm-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2_FP8_FAMINMAX = Aarch64Architecture.extending(SME2,
            "teste-mvm-dec-SME2-FP8-FAMINMAX", Aarch64Feature.FP8, Aarch64Feature.FP_ABSOLUTE_MAX_MIN);
    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    /// `palavra, operação, esz, count, zdn, zm`.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "0xC124B002, SMAX, 0, 2, 2, 4",   // smax {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB01E, SMAX, 0, 2, 30, 14",   // smax {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128B804, SMAX, 0, 4, 4, 8",   // smax {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138B81C, SMAX, 0, 4, 28, 24",   // smax {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B002, SMAX, 1, 2, 2, 4",   // smax {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB01E, SMAX, 1, 2, 30, 14",   // smax {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B804, SMAX, 1, 4, 4, 8",   // smax {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B81C, SMAX, 1, 4, 28, 24",   // smax {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B002, SMAX, 2, 2, 2, 4",   // smax {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB01E, SMAX, 2, 2, 30, 14",   // smax {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B804, SMAX, 2, 4, 4, 8",   // smax {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B81C, SMAX, 2, 4, 28, 24",   // smax {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B002, SMAX, 3, 2, 2, 4",   // smax {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB01E, SMAX, 3, 2, 30, 14",   // smax {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B804, SMAX, 3, 4, 4, 8",   // smax {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B81C, SMAX, 3, 4, 28, 24",   // smax {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B003, UMAX, 0, 2, 2, 4",   // umax {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB01F, UMAX, 0, 2, 30, 14",   // umax {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128B805, UMAX, 0, 4, 4, 8",   // umax {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138B81D, UMAX, 0, 4, 28, 24",   // umax {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B003, UMAX, 1, 2, 2, 4",   // umax {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB01F, UMAX, 1, 2, 30, 14",   // umax {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B805, UMAX, 1, 4, 4, 8",   // umax {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B81D, UMAX, 1, 4, 28, 24",   // umax {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B003, UMAX, 2, 2, 2, 4",   // umax {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB01F, UMAX, 2, 2, 30, 14",   // umax {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B805, UMAX, 2, 4, 4, 8",   // umax {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B81D, UMAX, 2, 4, 28, 24",   // umax {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B003, UMAX, 3, 2, 2, 4",   // umax {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB01F, UMAX, 3, 2, 30, 14",   // umax {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B805, UMAX, 3, 4, 4, 8",   // umax {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B81D, UMAX, 3, 4, 28, 24",   // umax {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B022, SMIN, 0, 2, 2, 4",   // smin {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB03E, SMIN, 0, 2, 30, 14",   // smin {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128B824, SMIN, 0, 4, 4, 8",   // smin {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138B83C, SMIN, 0, 4, 28, 24",   // smin {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B022, SMIN, 1, 2, 2, 4",   // smin {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB03E, SMIN, 1, 2, 30, 14",   // smin {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B824, SMIN, 1, 4, 4, 8",   // smin {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B83C, SMIN, 1, 4, 28, 24",   // smin {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B022, SMIN, 2, 2, 2, 4",   // smin {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB03E, SMIN, 2, 2, 30, 14",   // smin {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B824, SMIN, 2, 4, 4, 8",   // smin {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B83C, SMIN, 2, 4, 28, 24",   // smin {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B022, SMIN, 3, 2, 2, 4",   // smin {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB03E, SMIN, 3, 2, 30, 14",   // smin {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B824, SMIN, 3, 4, 4, 8",   // smin {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B83C, SMIN, 3, 4, 28, 24",   // smin {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B023, UMIN, 0, 2, 2, 4",   // umin {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB03F, UMIN, 0, 2, 30, 14",   // umin {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128B825, UMIN, 0, 4, 4, 8",   // umin {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138B83D, UMIN, 0, 4, 28, 24",   // umin {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B023, UMIN, 1, 2, 2, 4",   // umin {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB03F, UMIN, 1, 2, 30, 14",   // umin {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B825, UMIN, 1, 4, 4, 8",   // umin {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B83D, UMIN, 1, 4, 28, 24",   // umin {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B023, UMIN, 2, 2, 2, 4",   // umin {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB03F, UMIN, 2, 2, 30, 14",   // umin {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B825, UMIN, 2, 4, 4, 8",   // umin {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B83D, UMIN, 2, 4, 28, 24",   // umin {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B023, UMIN, 3, 2, 2, 4",   // umin {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB03F, UMIN, 3, 2, 30, 14",   // umin {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B825, UMIN, 3, 4, 4, 8",   // umin {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B83D, UMIN, 3, 4, 28, 24",   // umin {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B222, SRSHL, 0, 2, 2, 4",   // srshl {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB23E, SRSHL, 0, 2, 30, 14",   // srshl {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128BA24, SRSHL, 0, 4, 4, 8",   // srshl {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138BA3C, SRSHL, 0, 4, 28, 24",   // srshl {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B222, SRSHL, 1, 2, 2, 4",   // srshl {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB23E, SRSHL, 1, 2, 30, 14",   // srshl {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168BA24, SRSHL, 1, 4, 4, 8",   // srshl {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178BA3C, SRSHL, 1, 4, 28, 24",   // srshl {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B222, SRSHL, 2, 2, 2, 4",   // srshl {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB23E, SRSHL, 2, 2, 30, 14",   // srshl {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8BA24, SRSHL, 2, 4, 4, 8",   // srshl {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8BA3C, SRSHL, 2, 4, 28, 24",   // srshl {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B222, SRSHL, 3, 2, 2, 4",   // srshl {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB23E, SRSHL, 3, 2, 30, 14",   // srshl {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8BA24, SRSHL, 3, 4, 4, 8",   // srshl {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8BA3C, SRSHL, 3, 4, 28, 24",   // srshl {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B223, URSHL, 0, 2, 2, 4",   // urshl {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB23F, URSHL, 0, 2, 30, 14",   // urshl {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128BA25, URSHL, 0, 4, 4, 8",   // urshl {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138BA3D, URSHL, 0, 4, 28, 24",   // urshl {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B223, URSHL, 1, 2, 2, 4",   // urshl {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB23F, URSHL, 1, 2, 30, 14",   // urshl {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168BA25, URSHL, 1, 4, 4, 8",   // urshl {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178BA3D, URSHL, 1, 4, 28, 24",   // urshl {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B223, URSHL, 2, 2, 2, 4",   // urshl {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB23F, URSHL, 2, 2, 30, 14",   // urshl {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8BA25, URSHL, 2, 4, 4, 8",   // urshl {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8BA3D, URSHL, 2, 4, 28, 24",   // urshl {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B223, URSHL, 3, 2, 2, 4",   // urshl {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB23F, URSHL, 3, 2, 30, 14",   // urshl {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8BA25, URSHL, 3, 4, 4, 8",   // urshl {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8BA3D, URSHL, 3, 4, 28, 24",   // urshl {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC124B402, SQDMULH, 0, 2, 2, 4",   // sqdmulh {z2.b-z3.b}, {z2.b-z3.b}, {z4.b-z5.b}
            "0xC12EB41E, SQDMULH, 0, 2, 30, 14",   // sqdmulh {z30.b-z31.b}, {z30.b-z31.b}, {z14.b-z15.b}
            "0xC128BC04, SQDMULH, 0, 4, 4, 8",   // sqdmulh {z4.b-z7.b}, {z4.b-z7.b}, {z8.b-z11.b}
            "0xC138BC1C, SQDMULH, 0, 4, 28, 24",   // sqdmulh {z28.b-z31.b}, {z28.b-z31.b}, {z24.b-z27.b}
            "0xC164B402, SQDMULH, 1, 2, 2, 4",   // sqdmulh {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB41E, SQDMULH, 1, 2, 30, 14",   // sqdmulh {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168BC04, SQDMULH, 1, 4, 4, 8",   // sqdmulh {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178BC1C, SQDMULH, 1, 4, 28, 24",   // sqdmulh {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B402, SQDMULH, 2, 2, 2, 4",   // sqdmulh {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB41E, SQDMULH, 2, 2, 30, 14",   // sqdmulh {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8BC04, SQDMULH, 2, 4, 4, 8",   // sqdmulh {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8BC1C, SQDMULH, 2, 4, 28, 24",   // sqdmulh {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B402, SQDMULH, 3, 2, 2, 4",   // sqdmulh {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB41E, SQDMULH, 3, 2, 30, 14",   // sqdmulh {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8BC04, SQDMULH, 3, 4, 4, 8",   // sqdmulh {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8BC1C, SQDMULH, 3, 4, 28, 24",   // sqdmulh {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B102, FMAX, 1, 2, 2, 4",   // fmax {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB11E, FMAX, 1, 2, 30, 14",   // fmax {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B904, FMAX, 1, 4, 4, 8",   // fmax {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B91C, FMAX, 1, 4, 28, 24",   // fmax {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B102, FMAX, 2, 2, 2, 4",   // fmax {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB11E, FMAX, 2, 2, 30, 14",   // fmax {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B904, FMAX, 2, 4, 4, 8",   // fmax {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B91C, FMAX, 2, 4, 28, 24",   // fmax {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B102, FMAX, 3, 2, 2, 4",   // fmax {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB11E, FMAX, 3, 2, 30, 14",   // fmax {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B904, FMAX, 3, 4, 4, 8",   // fmax {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B91C, FMAX, 3, 4, 28, 24",   // fmax {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B103, FMIN, 1, 2, 2, 4",   // fmin {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB11F, FMIN, 1, 2, 30, 14",   // fmin {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B905, FMIN, 1, 4, 4, 8",   // fmin {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B91D, FMIN, 1, 4, 28, 24",   // fmin {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B103, FMIN, 2, 2, 2, 4",   // fmin {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB11F, FMIN, 2, 2, 30, 14",   // fmin {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B905, FMIN, 2, 4, 4, 8",   // fmin {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B91D, FMIN, 2, 4, 28, 24",   // fmin {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B103, FMIN, 3, 2, 2, 4",   // fmin {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB11F, FMIN, 3, 2, 30, 14",   // fmin {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B905, FMIN, 3, 4, 4, 8",   // fmin {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B91D, FMIN, 3, 4, 28, 24",   // fmin {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B122, FMAXNM, 1, 2, 2, 4",   // fmaxnm {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB13E, FMAXNM, 1, 2, 30, 14",   // fmaxnm {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B924, FMAXNM, 1, 4, 4, 8",   // fmaxnm {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B93C, FMAXNM, 1, 4, 28, 24",   // fmaxnm {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B122, FMAXNM, 2, 2, 2, 4",   // fmaxnm {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB13E, FMAXNM, 2, 2, 30, 14",   // fmaxnm {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B924, FMAXNM, 2, 4, 4, 8",   // fmaxnm {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B93C, FMAXNM, 2, 4, 28, 24",   // fmaxnm {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B122, FMAXNM, 3, 2, 2, 4",   // fmaxnm {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB13E, FMAXNM, 3, 2, 30, 14",   // fmaxnm {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B924, FMAXNM, 3, 4, 4, 8",   // fmaxnm {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B93C, FMAXNM, 3, 4, 28, 24",   // fmaxnm {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B123, FMINNM, 1, 2, 2, 4",   // fminnm {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB13F, FMINNM, 1, 2, 30, 14",   // fminnm {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B925, FMINNM, 1, 4, 4, 8",   // fminnm {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B93D, FMINNM, 1, 4, 28, 24",   // fminnm {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B123, FMINNM, 2, 2, 2, 4",   // fminnm {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB13F, FMINNM, 2, 2, 30, 14",   // fminnm {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B925, FMINNM, 2, 4, 4, 8",   // fminnm {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B93D, FMINNM, 2, 4, 28, 24",   // fminnm {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B123, FMINNM, 3, 2, 2, 4",   // fminnm {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB13F, FMINNM, 3, 2, 30, 14",   // fminnm {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B925, FMINNM, 3, 4, 4, 8",   // fminnm {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B93D, FMINNM, 3, 4, 28, 24",   // fminnm {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B182, FSCALE, 1, 2, 2, 4",   // fscale {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB19E, FSCALE, 1, 2, 30, 14",   // fscale {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B984, FSCALE, 1, 4, 4, 8",   // fscale {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B99C, FSCALE, 1, 4, 28, 24",   // fscale {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B182, FSCALE, 2, 2, 2, 4",   // fscale {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB19E, FSCALE, 2, 2, 30, 14",   // fscale {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B984, FSCALE, 2, 4, 4, 8",   // fscale {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B99C, FSCALE, 2, 4, 28, 24",   // fscale {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B182, FSCALE, 3, 2, 2, 4",   // fscale {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB19E, FSCALE, 3, 2, 30, 14",   // fscale {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B984, FSCALE, 3, 4, 4, 8",   // fscale {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B99C, FSCALE, 3, 4, 28, 24",   // fscale {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B142, FAMAX, 1, 2, 2, 4",   // famax {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB15E, FAMAX, 1, 2, 30, 14",   // famax {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B944, FAMAX, 1, 4, 4, 8",   // famax {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B95C, FAMAX, 1, 4, 28, 24",   // famax {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B142, FAMAX, 2, 2, 2, 4",   // famax {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB15E, FAMAX, 2, 2, 30, 14",   // famax {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B944, FAMAX, 2, 4, 4, 8",   // famax {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B95C, FAMAX, 2, 4, 28, 24",   // famax {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B142, FAMAX, 3, 2, 2, 4",   // famax {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB15E, FAMAX, 3, 2, 30, 14",   // famax {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B944, FAMAX, 3, 4, 4, 8",   // famax {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B95C, FAMAX, 3, 4, 28, 24",   // famax {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
            "0xC164B143, FAMIN, 1, 2, 2, 4",   // famin {z2.h-z3.h}, {z2.h-z3.h}, {z4.h-z5.h}
            "0xC16EB15F, FAMIN, 1, 2, 30, 14",   // famin {z30.h-z31.h}, {z30.h-z31.h}, {z14.h-z15.h}
            "0xC168B945, FAMIN, 1, 4, 4, 8",   // famin {z4.h-z7.h}, {z4.h-z7.h}, {z8.h-z11.h}
            "0xC178B95D, FAMIN, 1, 4, 28, 24",   // famin {z28.h-z31.h}, {z28.h-z31.h}, {z24.h-z27.h}
            "0xC1A4B143, FAMIN, 2, 2, 2, 4",   // famin {z2.s-z3.s}, {z2.s-z3.s}, {z4.s-z5.s}
            "0xC1AEB15F, FAMIN, 2, 2, 30, 14",   // famin {z30.s-z31.s}, {z30.s-z31.s}, {z14.s-z15.s}
            "0xC1A8B945, FAMIN, 2, 4, 4, 8",   // famin {z4.s-z7.s}, {z4.s-z7.s}, {z8.s-z11.s}
            "0xC1B8B95D, FAMIN, 2, 4, 28, 24",   // famin {z28.s-z31.s}, {z28.s-z31.s}, {z24.s-z27.s}
            "0xC1E4B143, FAMIN, 3, 2, 2, 4",   // famin {z2.d-z3.d}, {z2.d-z3.d}, {z4.d-z5.d}
            "0xC1EEB15F, FAMIN, 3, 2, 30, 14",   // famin {z30.d-z31.d}, {z30.d-z31.d}, {z14.d-z15.d}
            "0xC1E8B945, FAMIN, 3, 4, 4, 8",   // famin {z4.d-z7.d}, {z4.d-z7.d}, {z8.d-z11.d}
            "0xC1F8B95D, FAMIN, 3, 4, 28, 24",   // famin {z28.d-z31.d}, {z28.d-z31.d}, {z24.d-z27.d}
    })
    void decodesEveryEncodingFromTheAssembler(String hex, Ir64Op.SmeMultiVectorSingle.Op expectedOp, int esz, int count,
            int zdn, int zm) {
        Ir64Op.SmeMultiVectorSingle op = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2_FP8_FAMINMAX, (int) Long.decode(hex).longValue()));
        assertEquals(expectedOp, op.op());
        assertEquals(esz, op.esz());
        assertEquals(count, op.count());
        assertEquals(zdn, op.zdn(), "zdn é o campo × count (%zd_ax2/%zd_ax4), nunca o campo cru");
        assertEquals(zm, op.zm(), "zm é o campo × count (%zm_ax2/%zm_ax4), nunca o campo cru");
        assertTrue(op.zmIsGroup());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @Test
    void zmExtractorsUseTheirOwnBitBases() {
        // %zm_ax2 = bits[20:17] × 2: campo 0b0011 endereça Z6; %zm_ax4 = bits[20:18] × 4: campo 0b011 endereça Z12.
        Ir64Op.SmeMultiVectorSingle x2 = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2, 0xC120B000 | 0b0011 << 17));
        assertEquals(6, x2.zm());
        Ir64Op.SmeMultiVectorSingle x4 = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2, 0xC120B800 | 0b011 << 18));
        assertEquals(12, x4.zm());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "0xC12DB002", // smax x2 com o bit 16 ligado (@z2z_2x2 fixa `....0` em [20:16])
            "0xC12DB804", // smax x4 com o bit 16 ligado (@z2z_4x4 fixa `...00`)
            "0xC12AB804", // smax x4 com o bit 17 ligado
            "0xC128B806", // smax x4 com o bit 1 ligado (@z2z_4x4 fixa `...0 .`)
            "0xC124B302", // add_nn não existe (ADD só em _n1)
            "0xC124B303", // add_nn com U = 1
            "0xC124B403", // sqdmulh com U = 1
            "0xC164B183", // fscale com U = 1
            "0xC124B042", // opcode 00010 sem linha no .decode
            "0xC124B102", // fmax .b: o espaço esz = 0 de ponto flutuante é de BFMAX_nn (outro gate)
            "0xC124B142", // famax .b
            "0xC124B182", // fscale .b
            "0xC104B002", // bit 21 desligado: fora desta família
            "0xC324B002", // bit 25 ligado: fora do prefixo 1100000
            "0xC124C002", // bits[15:12] = 1100: nenhuma das duas famílias
    })
    void neighbourEncodingsStayUnimplemented(String hex) {
        int word = (int) Long.decode(hex).longValue();
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2_FP8_FAMINMAX, word), hex);
    }

    @Test
    void singleFormIsNotConfusedWithMultipleOne() {
        // 1010 (n1, Zm avulso) x 1011 (nn, grupo): a palavra só difere no bit 12.
        Ir64Op.SmeMultiVectorSingle single = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2_FP8_FAMINMAX, 0xC124A002));
        Ir64Op.SmeMultiVectorSingle multiple = assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class,
                decode(SME2_FP8_FAMINMAX, 0xC124B002));
        assertEquals(false, single.zmIsGroup());
        assertEquals(true, multiple.zmIsGroup());
    }

    @Test
    void famaxOnlyExistsAsMultipleVectorsAndNeedsFaminmax() {
        int famaxX2 = 0xC164B142;
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, famaxX2));
        assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class, decode(SME2_FP8_FAMINMAX, famaxX2));
        // FAMAX_n1 não existe: o mesmo opcode com o prefixo 1010 é recusado mesmo com as duas features.
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2_FP8_FAMINMAX, famaxX2 & ~0x1000));
    }

    @Test
    void everythingStaysUnimplementedWithoutSme2() {
        Aarch64Architecture sme = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-mvm-dec-SME",
                Aarch64Feature.FP8, Aarch64Feature.FP_ABSOLUTE_MAX_MIN);
        for (int word : new int[] {0xC124B002, 0xC128B804, 0xC164B222, 0xC1A4B402, 0xC1A4B102, 0xC164B182}) {
            assertThrows(UnsupportedOperationException.class, () -> decode(sme, word), Integer.toHexString(word));
        }
    }

    @Test
    void fscaleAlsoNeedsFp8ButTheOtherOnesDoNot() {
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2, 0xC164B182));
        assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class, decode(SME2, 0xC164B102));
        assertInstanceOf(Ir64Op.SmeMultiVectorSingle.class, decode(SME2_FP8_FAMINMAX, 0xC164B182));
    }
}
