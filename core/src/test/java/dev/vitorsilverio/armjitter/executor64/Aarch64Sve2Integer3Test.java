package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.21b — SVE2 inteiro II, metade "Widening + Narrowing" (76 encodings): add/sub/abs-diff long (12), interleaved long
/// (3), add/sub wide (8), multiply long (8), shift left long (4), `EORBT`/`EORTB` (2), matriz de 8 bits (3), bit-permute
/// (3), extract narrow (9), shift right narrow (16) e add/sub narrow high part (8). Palavras conferidas contra
/// `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+sve2-bitperm+sve2-aes+i8mm+sme2`); registradores fixos
/// `Zd = z1`, `Zn = z2`, `Zm = z3`. O oráculo é escrito com `BigInteger`/`Long.compress`/`Long.expand`, sem repetir a
/// aritmética do executor, em `VL = 256` e `512`, com o destino pré-preenchido de lixo (prova o que é escrito e o que é
/// PRESERVADO) e com o destino igual a cada fonte (as fontes são lidas antes de escrever).
class Aarch64Sve2Integer3Test {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final int SEEDS = 5;
    private static final int PREFIX = 0x45000000;
    private static final int NARROWING_BIT = 1 << 21;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;

    /// SVE puro (com `I8MM`, sem `SVE2`): tudo isto é recusado (G8).
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_1_A;
    /// `SVE2` sem nenhuma sub-feature (e sem `I8MM`).
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_0_A,
            "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_1_A,
            "teste-tudo", Aarch64Feature.SVE2, Aarch64Feature.SVE2_1, Aarch64Feature.SVE_BITPERM,
            Aarch64Feature.SVE_PMULL128);

    private interface LongOracle {
        BigInteger apply(BigInteger n, BigInteger m, int esz);
    }

    /// Uma operação `long`/`wide`/`multiply long`: opcode do `*B`, sinal e, no `*T`, se ele soma 1 ao opcode.
    private record LongCase(String name, int opcode, int nTop, int mTop, boolean signed, LongOracle oracle) {
        @Override
        public String toString() {
            return name;
        }
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static int elementCount(Aarch64Core core, int esz) {
        return core.vectorLengthBytes() >> esz;
    }

    private static BigInteger modulus(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz));
    }

    private static BigInteger unsigned(long value, int esz) {
        return new BigInteger(Long.toUnsignedString(value)).mod(modulus(esz));
    }

    private static BigInteger signed(long value, int esz) {
        BigInteger unsigned = unsigned(value, esz);
        return unsigned.testBit(bits(esz) - 1) ? unsigned.subtract(modulus(esz)) : unsigned;
    }

    private static BigInteger extended(long value, int esz, boolean signed) {
        return signed ? signed(value, esz) : unsigned(value, esz);
    }

    private static BigInteger maxSigned(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
    }

    private static BigInteger clampSigned(BigInteger value, int esz) {
        return value.max(maxSigned(esz).negate().subtract(BigInteger.ONE)).min(maxSigned(esz));
    }

    private static BigInteger clampUnsigned(BigInteger value, int esz) {
        return value.max(BigInteger.ZERO).min(modulus(esz).subtract(BigInteger.ONE));
    }

    private static long truncate(BigInteger value, int esz) {
        return value.mod(modulus(esz)).longValue();
    }

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[elementCount(core, esz)];
        for (int i = 0; i < out.length; i++) {
            int bitOffset = i * bits(esz);
            long shifted = core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64);
            out[i] = bits(esz) == 64 ? shifted : shifted & ((1L << bits(esz)) - 1);
        }
        return out;
    }

    private static void setElements(Aarch64Core core, int reg, int esz, long[] values) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
        for (int i = 0; i < values.length; i++) {
            int bitOffset = i * bits(esz);
            long field = bits(esz) == 64 ? values[i] : values[i] & ((1L << bits(esz)) - 1);
            core.scalable().setZWord(reg, bitOffset / 64,
                    core.scalable().zWord(reg, bitOffset / 64) | (field << (bitOffset % 64)));
        }
    }

    /// Aleatórios com viés para os extremos, onde a saturação, o estouro e o arredondamento acontecem.
    private static long[] randomElements(Aarch64Core core, int esz, Random random) {
        long[] out = new long[elementCount(core, esz)];
        long max = bits(esz) == 64 ? -1L : (1L << bits(esz)) - 1;
        long half = 1L << (bits(esz) - 1);
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (random.nextInt(8)) {
                case 0 -> 0L;
                case 1 -> max;
                case 2 -> half;
                case 3 -> half - 1;
                case 4 -> 1L;
                default -> random.nextLong();
            } & max;
        }
        return out;
    }

    private static int wideWord(int esz, int opcode, int rd, int rn, int rm) {
        return PREFIX | (esz << 22) | (rm << 16) | (opcode << 10) | (rn << 5) | rd;
    }

    /// `bits[23:22]:bits[20:16]` = `tszimm` de 7 bits (`bit 21` é o `NARROWING_BIT`, à parte).
    private static int shiftWord(int narrowing, int tszimm, int opcode, int rn, int rd) {
        return PREFIX | narrowing | ((tszimm >>> 5) << 22) | ((tszimm & 0b11111) << 16) | (opcode << 10) | (rn << 5) | rd;
    }

    // ── Decoder: palavras do assembler ───────────────────────────────────────────────────────────

    static Stream<Arguments> assemblerWords() {
        return Stream.of(
                Arguments.of(0x45430041, "SADDL", 1, 0L, 0L),
                Arguments.of(0x45830041, "SADDL", 2, 0L, 0L),
                Arguments.of(0x45c30041, "SADDL", 3, 0L, 0L),
                Arguments.of(0x45430441, "SADDL", 1, 3L, 0L),
                Arguments.of(0x45830441, "SADDL", 2, 3L, 0L),
                Arguments.of(0x45c30441, "SADDL", 3, 3L, 0L),
                Arguments.of(0x45430841, "UADDL", 1, 0L, 0L),
                Arguments.of(0x45830841, "UADDL", 2, 0L, 0L),
                Arguments.of(0x45c30841, "UADDL", 3, 0L, 0L),
                Arguments.of(0x45430c41, "UADDL", 1, 3L, 0L),
                Arguments.of(0x45830c41, "UADDL", 2, 3L, 0L),
                Arguments.of(0x45c30c41, "UADDL", 3, 3L, 0L),
                Arguments.of(0x45431041, "SSUBL", 1, 0L, 0L),
                Arguments.of(0x45831041, "SSUBL", 2, 0L, 0L),
                Arguments.of(0x45c31041, "SSUBL", 3, 0L, 0L),
                Arguments.of(0x45431441, "SSUBL", 1, 3L, 0L),
                Arguments.of(0x45831441, "SSUBL", 2, 3L, 0L),
                Arguments.of(0x45c31441, "SSUBL", 3, 3L, 0L),
                Arguments.of(0x45431841, "USUBL", 1, 0L, 0L),
                Arguments.of(0x45831841, "USUBL", 2, 0L, 0L),
                Arguments.of(0x45c31841, "USUBL", 3, 0L, 0L),
                Arguments.of(0x45431c41, "USUBL", 1, 3L, 0L),
                Arguments.of(0x45831c41, "USUBL", 2, 3L, 0L),
                Arguments.of(0x45c31c41, "USUBL", 3, 3L, 0L),
                Arguments.of(0x45433041, "SABDL", 1, 0L, 0L),
                Arguments.of(0x45833041, "SABDL", 2, 0L, 0L),
                Arguments.of(0x45c33041, "SABDL", 3, 0L, 0L),
                Arguments.of(0x45433441, "SABDL", 1, 3L, 0L),
                Arguments.of(0x45833441, "SABDL", 2, 3L, 0L),
                Arguments.of(0x45c33441, "SABDL", 3, 3L, 0L),
                Arguments.of(0x45433841, "UABDL", 1, 0L, 0L),
                Arguments.of(0x45833841, "UABDL", 2, 0L, 0L),
                Arguments.of(0x45c33841, "UABDL", 3, 0L, 0L),
                Arguments.of(0x45433c41, "UABDL", 1, 3L, 0L),
                Arguments.of(0x45833c41, "UABDL", 2, 3L, 0L),
                Arguments.of(0x45c33c41, "UABDL", 3, 3L, 0L),
                Arguments.of(0x45436041, "SQDMULL", 1, 0L, 0L),
                Arguments.of(0x45836041, "SQDMULL", 2, 0L, 0L),
                Arguments.of(0x45c36041, "SQDMULL", 3, 0L, 0L),
                Arguments.of(0x45436441, "SQDMULL", 1, 3L, 0L),
                Arguments.of(0x45836441, "SQDMULL", 2, 3L, 0L),
                Arguments.of(0x45c36441, "SQDMULL", 3, 3L, 0L),
                Arguments.of(0x45437041, "SMULL", 1, 0L, 0L),
                Arguments.of(0x45837041, "SMULL", 2, 0L, 0L),
                Arguments.of(0x45c37041, "SMULL", 3, 0L, 0L),
                Arguments.of(0x45437441, "SMULL", 1, 3L, 0L),
                Arguments.of(0x45837441, "SMULL", 2, 3L, 0L),
                Arguments.of(0x45c37441, "SMULL", 3, 3L, 0L),
                Arguments.of(0x45437841, "UMULL", 1, 0L, 0L),
                Arguments.of(0x45837841, "UMULL", 2, 0L, 0L),
                Arguments.of(0x45c37841, "UMULL", 3, 0L, 0L),
                Arguments.of(0x45437c41, "UMULL", 1, 3L, 0L),
                Arguments.of(0x45837c41, "UMULL", 2, 3L, 0L),
                Arguments.of(0x45c37c41, "UMULL", 3, 3L, 0L),
                Arguments.of(0x45438041, "SADDL", 1, 2L, 0L),
                Arguments.of(0x45838041, "SADDL", 2, 2L, 0L),
                Arguments.of(0x45c38041, "SADDL", 3, 2L, 0L),
                Arguments.of(0x45438841, "SSUBL", 1, 2L, 0L),
                Arguments.of(0x45838841, "SSUBL", 2, 2L, 0L),
                Arguments.of(0x45c38841, "SSUBL", 3, 2L, 0L),
                Arguments.of(0x45438c41, "SSUBL", 1, 1L, 0L),
                Arguments.of(0x45838c41, "SSUBL", 2, 1L, 0L),
                Arguments.of(0x45c38c41, "SSUBL", 3, 1L, 0L),
                Arguments.of(0x45434041, "SADDW", 1, 0L, 0L),
                Arguments.of(0x45834041, "SADDW", 2, 0L, 0L),
                Arguments.of(0x45c34041, "SADDW", 3, 0L, 0L),
                Arguments.of(0x45434441, "SADDW", 1, 2L, 0L),
                Arguments.of(0x45834441, "SADDW", 2, 2L, 0L),
                Arguments.of(0x45c34441, "SADDW", 3, 2L, 0L),
                Arguments.of(0x45434841, "UADDW", 1, 0L, 0L),
                Arguments.of(0x45834841, "UADDW", 2, 0L, 0L),
                Arguments.of(0x45c34841, "UADDW", 3, 0L, 0L),
                Arguments.of(0x45434c41, "UADDW", 1, 2L, 0L),
                Arguments.of(0x45834c41, "UADDW", 2, 2L, 0L),
                Arguments.of(0x45c34c41, "UADDW", 3, 2L, 0L),
                Arguments.of(0x45435041, "SSUBW", 1, 0L, 0L),
                Arguments.of(0x45835041, "SSUBW", 2, 0L, 0L),
                Arguments.of(0x45c35041, "SSUBW", 3, 0L, 0L),
                Arguments.of(0x45435441, "SSUBW", 1, 2L, 0L),
                Arguments.of(0x45835441, "SSUBW", 2, 2L, 0L),
                Arguments.of(0x45c35441, "SSUBW", 3, 2L, 0L),
                Arguments.of(0x45435841, "USUBW", 1, 0L, 0L),
                Arguments.of(0x45835841, "USUBW", 2, 0L, 0L),
                Arguments.of(0x45c35841, "USUBW", 3, 0L, 0L),
                Arguments.of(0x45435c41, "USUBW", 1, 2L, 0L),
                Arguments.of(0x45835c41, "USUBW", 2, 2L, 0L),
                Arguments.of(0x45c35c41, "USUBW", 3, 2L, 0L),
                Arguments.of(0x45436841, "PMULL", 1, 0L, 0L),
                Arguments.of(0x45c36841, "PMULL", 3, 0L, 0L),
                Arguments.of(0x45036841, "PMULL", 0, 0L, 0L),
                Arguments.of(0x45436c41, "PMULL", 1, 3L, 0L),
                Arguments.of(0x45c36c41, "PMULL", 3, 3L, 0L),
                Arguments.of(0x45036c41, "PMULL", 0, 3L, 0L),
                Arguments.of(0x450ba041, "SSHLL", 1, 3L, 0L),
                Arguments.of(0x4515a041, "SSHLL", 2, 5L, 0L),
                Arguments.of(0x4551a041, "SSHLL", 3, 17L, 0L),
                Arguments.of(0x450fa441, "SSHLL", 1, 7L, 1L),
                Arguments.of(0x4510a441, "SSHLL", 2, 0L, 1L),
                Arguments.of(0x455fa441, "SSHLL", 3, 31L, 1L),
                Arguments.of(0x450ba841, "USHLL", 1, 3L, 0L),
                Arguments.of(0x4515a841, "USHLL", 2, 5L, 0L),
                Arguments.of(0x4551a841, "USHLL", 3, 17L, 0L),
                Arguments.of(0x450fac41, "USHLL", 1, 7L, 1L),
                Arguments.of(0x4510ac41, "USHLL", 2, 0L, 1L),
                Arguments.of(0x455fac41, "USHLL", 3, 31L, 1L),
                Arguments.of(0x45039041, "EORBT", 0, 2L, 0L),
                Arguments.of(0x45439041, "EORBT", 1, 2L, 0L),
                Arguments.of(0x45839041, "EORBT", 2, 2L, 0L),
                Arguments.of(0x45c39041, "EORBT", 3, 2L, 0L),
                Arguments.of(0x45039441, "EORTB", 0, 1L, 0L),
                Arguments.of(0x45439441, "EORTB", 1, 1L, 0L),
                Arguments.of(0x45839441, "EORTB", 2, 1L, 0L),
                Arguments.of(0x45c39441, "EORTB", 3, 1L, 0L),
                Arguments.of(0x4503b041, "BEXT", 0, 0L, 0L),
                Arguments.of(0x4543b041, "BEXT", 1, 0L, 0L),
                Arguments.of(0x4583b041, "BEXT", 2, 0L, 0L),
                Arguments.of(0x45c3b041, "BEXT", 3, 0L, 0L),
                Arguments.of(0x4503b441, "BDEP", 0, 0L, 0L),
                Arguments.of(0x4543b441, "BDEP", 1, 0L, 0L),
                Arguments.of(0x4583b441, "BDEP", 2, 0L, 0L),
                Arguments.of(0x45c3b441, "BDEP", 3, 0L, 0L),
                Arguments.of(0x4503b841, "BGRP", 0, 0L, 0L),
                Arguments.of(0x4543b841, "BGRP", 1, 0L, 0L),
                Arguments.of(0x4583b841, "BGRP", 2, 0L, 0L),
                Arguments.of(0x45c3b841, "BGRP", 3, 0L, 0L),
                Arguments.of(0x45039841, "SMMLA", 2, 0L, 0L),
                Arguments.of(0x45839841, "USMMLA", 2, 0L, 0L),
                Arguments.of(0x45c39841, "UMMLA", 2, 0L, 0L),
                Arguments.of(0x45284041, "SQXTN", 1, 0L, 0L),
                Arguments.of(0x45304041, "SQXTN", 2, 0L, 0L),
                Arguments.of(0x45604041, "SQXTN", 3, 0L, 0L),
                Arguments.of(0x45284441, "SQXTN", 1, 1L, 0L),
                Arguments.of(0x45304441, "SQXTN", 2, 1L, 0L),
                Arguments.of(0x45604441, "SQXTN", 3, 1L, 0L),
                Arguments.of(0x45284841, "UQXTN", 1, 0L, 0L),
                Arguments.of(0x45304841, "UQXTN", 2, 0L, 0L),
                Arguments.of(0x45604841, "UQXTN", 3, 0L, 0L),
                Arguments.of(0x45284c41, "UQXTN", 1, 1L, 0L),
                Arguments.of(0x45304c41, "UQXTN", 2, 1L, 0L),
                Arguments.of(0x45604c41, "UQXTN", 3, 1L, 0L),
                Arguments.of(0x45285041, "SQXTUN", 1, 0L, 0L),
                Arguments.of(0x45305041, "SQXTUN", 2, 0L, 0L),
                Arguments.of(0x45605041, "SQXTUN", 3, 0L, 0L),
                Arguments.of(0x45285441, "SQXTUN", 1, 1L, 0L),
                Arguments.of(0x45305441, "SQXTUN", 2, 1L, 0L),
                Arguments.of(0x45605441, "SQXTUN", 3, 1L, 0L),
                Arguments.of(0x45314081, "SQCVTN", 2, 0L, 0L),
                Arguments.of(0x453143c1, "SQCVTN", 2, 0L, 0L),
                Arguments.of(0x45314881, "UQCVTN", 2, 0L, 0L),
                Arguments.of(0x45314bc1, "UQCVTN", 2, 0L, 0L),
                Arguments.of(0x45315081, "SQCVTUN", 2, 0L, 0L),
                Arguments.of(0x453153c1, "SQCVTUN", 2, 0L, 0L),
                Arguments.of(0x452d0041, "SQSHRUN", 1, 3L, 0L),
                Arguments.of(0x453b0041, "SQSHRUN", 2, 5L, 0L),
                Arguments.of(0x456f0041, "SQSHRUN", 3, 17L, 0L),
                Arguments.of(0x452d0441, "SQSHRUN", 1, 3L, 1L),
                Arguments.of(0x453b0441, "SQSHRUN", 2, 5L, 1L),
                Arguments.of(0x456f0441, "SQSHRUN", 3, 17L, 1L),
                Arguments.of(0x452d0841, "SQRSHRUN", 1, 3L, 0L),
                Arguments.of(0x453b0841, "SQRSHRUN", 2, 5L, 0L),
                Arguments.of(0x456f0841, "SQRSHRUN", 3, 17L, 0L),
                Arguments.of(0x452d0c41, "SQRSHRUN", 1, 3L, 1L),
                Arguments.of(0x453b0c41, "SQRSHRUN", 2, 5L, 1L),
                Arguments.of(0x456f0c41, "SQRSHRUN", 3, 17L, 1L),
                Arguments.of(0x452d1041, "SHRN", 1, 3L, 0L),
                Arguments.of(0x453b1041, "SHRN", 2, 5L, 0L),
                Arguments.of(0x456f1041, "SHRN", 3, 17L, 0L),
                Arguments.of(0x452d1441, "SHRN", 1, 3L, 1L),
                Arguments.of(0x453b1441, "SHRN", 2, 5L, 1L),
                Arguments.of(0x456f1441, "SHRN", 3, 17L, 1L),
                Arguments.of(0x452d1841, "RSHRN", 1, 3L, 0L),
                Arguments.of(0x453b1841, "RSHRN", 2, 5L, 0L),
                Arguments.of(0x456f1841, "RSHRN", 3, 17L, 0L),
                Arguments.of(0x452d1c41, "RSHRN", 1, 3L, 1L),
                Arguments.of(0x453b1c41, "RSHRN", 2, 5L, 1L),
                Arguments.of(0x456f1c41, "RSHRN", 3, 17L, 1L),
                Arguments.of(0x452d2041, "SQSHRN", 1, 3L, 0L),
                Arguments.of(0x453b2041, "SQSHRN", 2, 5L, 0L),
                Arguments.of(0x456f2041, "SQSHRN", 3, 17L, 0L),
                Arguments.of(0x452d2441, "SQSHRN", 1, 3L, 1L),
                Arguments.of(0x453b2441, "SQSHRN", 2, 5L, 1L),
                Arguments.of(0x456f2441, "SQSHRN", 3, 17L, 1L),
                Arguments.of(0x452d2841, "SQRSHRN", 1, 3L, 0L),
                Arguments.of(0x453b2841, "SQRSHRN", 2, 5L, 0L),
                Arguments.of(0x456f2841, "SQRSHRN", 3, 17L, 0L),
                Arguments.of(0x452d2c41, "SQRSHRN", 1, 3L, 1L),
                Arguments.of(0x453b2c41, "SQRSHRN", 2, 5L, 1L),
                Arguments.of(0x456f2c41, "SQRSHRN", 3, 17L, 1L),
                Arguments.of(0x452d3041, "UQSHRN", 1, 3L, 0L),
                Arguments.of(0x453b3041, "UQSHRN", 2, 5L, 0L),
                Arguments.of(0x456f3041, "UQSHRN", 3, 17L, 0L),
                Arguments.of(0x452d3441, "UQSHRN", 1, 3L, 1L),
                Arguments.of(0x453b3441, "UQSHRN", 2, 5L, 1L),
                Arguments.of(0x456f3441, "UQSHRN", 3, 17L, 1L),
                Arguments.of(0x452d3841, "UQRSHRN", 1, 3L, 0L),
                Arguments.of(0x453b3841, "UQRSHRN", 2, 5L, 0L),
                Arguments.of(0x456f3841, "UQRSHRN", 3, 17L, 0L),
                Arguments.of(0x452d3c41, "UQRSHRN", 1, 3L, 1L),
                Arguments.of(0x453b3c41, "UQRSHRN", 2, 5L, 1L),
                Arguments.of(0x456f3c41, "UQRSHRN", 3, 17L, 1L),
                Arguments.of(0x45636041, "ADDHN", 1, 0L, 0L),
                Arguments.of(0x45a36041, "ADDHN", 2, 0L, 0L),
                Arguments.of(0x45e36041, "ADDHN", 3, 0L, 0L),
                Arguments.of(0x45636441, "ADDHN", 1, 1L, 0L),
                Arguments.of(0x45a36441, "ADDHN", 2, 1L, 0L),
                Arguments.of(0x45e36441, "ADDHN", 3, 1L, 0L),
                Arguments.of(0x45636841, "RADDHN", 1, 0L, 0L),
                Arguments.of(0x45a36841, "RADDHN", 2, 0L, 0L),
                Arguments.of(0x45e36841, "RADDHN", 3, 0L, 0L),
                Arguments.of(0x45636c41, "RADDHN", 1, 1L, 0L),
                Arguments.of(0x45a36c41, "RADDHN", 2, 1L, 0L),
                Arguments.of(0x45e36c41, "RADDHN", 3, 1L, 0L),
                Arguments.of(0x45637041, "SUBHN", 1, 0L, 0L),
                Arguments.of(0x45a37041, "SUBHN", 2, 0L, 0L),
                Arguments.of(0x45e37041, "SUBHN", 3, 0L, 0L),
                Arguments.of(0x45637441, "SUBHN", 1, 1L, 0L),
                Arguments.of(0x45a37441, "SUBHN", 2, 1L, 0L),
                Arguments.of(0x45e37441, "SUBHN", 3, 1L, 0L),
                Arguments.of(0x45637841, "RSUBHN", 1, 0L, 0L),
                Arguments.of(0x45a37841, "RSUBHN", 2, 0L, 0L),
                Arguments.of(0x45e37841, "RSUBHN", 3, 0L, 0L),
                Arguments.of(0x45637c41, "RSUBHN", 1, 1L, 0L),
                Arguments.of(0x45a37c41, "RSUBHN", 2, 1L, 0L),
                Arguments.of(0x45e37c41, "RSUBHN", 3, 1L, 0L));
    }

    private static final Set<String> CONVERT_PAIR = Set.of("SQCVTN", "UQCVTN", "SQCVTUN");

    @ParameterizedTest
    @MethodSource("assemblerWords")
    void assemblerWordsDecodeWithTheRightFieldsAndAreRefusedUnderPlainSve(int word, String expected, int esz, long imm,
            long imm2) {
        Ir64Op decoded = decodeOrNull(ALL, word);
        assertNotNull(decoded, "decodifica sob SVE2 + sub-features");
        Ir64Op.SveIntegerUnpredicated op = (Ir64Op.SveIntegerUnpredicated) decoded;
        assertEquals(expected, op.op().name());
        assertEquals(esz, op.esz(), "esz (tamanho do elemento largo)");
        assertEquals(imm, op.imm(), "imm (seleção B/T ou deslocamento)");
        assertEquals(imm2, op.imm2(), "imm2");
        assertEquals(Z1, op.rd());
        if (!CONVERT_PAIR.contains(expected)) {
            assertEquals(Z2, op.rn());
        }
        assertNull(decodeOrNull(SVE, word), "recusada sob SVE puro (G8)");
    }

    @Test
    void pairConvertReadsTheRegisterPairFromBits9To6() {
        Ir64Op.SveIntegerUnpredicated low = (Ir64Op.SveIntegerUnpredicated) decodeOrNull(ALL, 0x45314081);
        assertEquals(Ir64Op.SveIntegerUnpredicated.Op.SQCVTN, low.op());
        assertEquals(4, low.rn(), "z4,z5");
        Ir64Op.SveIntegerUnpredicated high = (Ir64Op.SveIntegerUnpredicated) decodeOrNull(ALL, 0x453143c1);
        assertEquals(30, high.rn(), "z30,z31");
        assertNull(decodeOrNull(ALL, 0x453140a1), "bit 5 = 1: não é SQCVTN (e SQXTNB recusa o imm3 não nulo)");
    }

    // ── Decoder: features ────────────────────────────────────────────────────────────────────────

    @Test
    void bitPermuteNeedsItsOwnFeature() {
        int[] words = {0x4503b041, 0x4543b441, 0x4583b841, 0x45c3b041};
        Aarch64Architecture without = Aarch64Architecture.extending(SVE2, "sem-BitPerm", Aarch64Feature.SVE_PMULL128);
        Aarch64Architecture with = Aarch64Architecture.extending(SVE2, "com-BitPerm", Aarch64Feature.SVE_BITPERM);
        for (int word : words) {
            assertNull(decodeOrNull(SVE2, word), "SVE2 genérico não anuncia BitPerm");
            assertNull(decodeOrNull(without, word));
            assertNotNull(decodeOrNull(with, word));
        }
    }

    @Test
    void pmull128NeedsItsOwnFeatureAndTheWordSizeDoesNotExist() {
        int pmullQ = 0x45036841; // PMULLB Z1.Q, Z2.D, Z3.D
        int pmullH = 0x45436841;
        int pmullS = 0x45836841;
        int pmullD = 0x45c36841;
        Aarch64Architecture with = Aarch64Architecture.extending(SVE2, "com-PMULL128", Aarch64Feature.SVE_PMULL128);
        assertNull(decodeOrNull(SVE2, pmullQ), ".Q exige FEAT_SVE_PMULL128");
        assertNotNull(decodeOrNull(with, pmullQ));
        assertNotNull(decodeOrNull(SVE2, pmullH));
        assertNotNull(decodeOrNull(SVE2, pmullD));
        assertNull(decodeOrNull(with, pmullS), "esz = 2 não existe em PMULL");
    }

    @Test
    void matrixMultiplyNeedsI8mm() {
        int[] words = {0x45039841, 0x45839841, 0x45c39841};
        Aarch64Architecture withI8mm = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_1_A, "com-I8MM",
                Aarch64Feature.SVE2);
        for (int word : words) {
            assertNull(decodeOrNull(SVE2, word), "sem FEAT_I8MM");
            assertNotNull(decodeOrNull(withI8mm, word));
        }
        assertNull(decodeOrNull(withI8mm, 0x45439841), "bits[23:22] = 01 não existe");
    }

    @Test
    void pairConvertNeedsSve2p1OrSme2() {
        int word = 0x45314041;
        Aarch64Architecture sve2p1 = Aarch64Architecture.extending(SVE2, "so-SVE2p1", Aarch64Feature.SVE2_1);
        Aarch64Architecture sme2 = Aarch64Architecture.extending(SVE2, "so-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
        assertNull(decodeOrNull(SVE2, word));
        assertNotNull(decodeOrNull(sve2p1, word));
        assertNotNull(decodeOrNull(sme2, word));
    }

    @Test
    void idRegisterAdvertisesTheSubFeatures() {
        Aarch64Core all = core(ALL, 256);
        assertEquals(1L | 2L << 4 | 1L << 16 | 1L << 44, all.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1));
        Aarch64Core sve2 = core(SVE2, 256);
        assertEquals(1L, sve2.readIntrinsicSystemRegister(Aarch64SystemRegisterId.ID_AA64ZFR0_EL1));
    }

    // ── Decoder: varredura contra o `sve.decode` ─────────────────────────────────────────────────

    /// `sve.decode` linhas 1677-1748 e 1791-1841, transcritas: `nome|padrão|formato` (as 76 linhas do recorte).
    private static final String DECODE_LINES = """
            SADDLB|01000101 .. 0 ..... 00 0000 ..... .....|@rd_rn_rm
            SADDLT|01000101 .. 0 ..... 00 0001 ..... .....|@rd_rn_rm
            UADDLB|01000101 .. 0 ..... 00 0010 ..... .....|@rd_rn_rm
            UADDLT|01000101 .. 0 ..... 00 0011 ..... .....|@rd_rn_rm
            SSUBLB|01000101 .. 0 ..... 00 0100 ..... .....|@rd_rn_rm
            SSUBLT|01000101 .. 0 ..... 00 0101 ..... .....|@rd_rn_rm
            USUBLB|01000101 .. 0 ..... 00 0110 ..... .....|@rd_rn_rm
            USUBLT|01000101 .. 0 ..... 00 0111 ..... .....|@rd_rn_rm
            SABDLB|01000101 .. 0 ..... 00 1100 ..... .....|@rd_rn_rm
            SABDLT|01000101 .. 0 ..... 00 1101 ..... .....|@rd_rn_rm
            UABDLB|01000101 .. 0 ..... 00 1110 ..... .....|@rd_rn_rm
            UABDLT|01000101 .. 0 ..... 00 1111 ..... .....|@rd_rn_rm
            SADDLBT|01000101 .. 0 ..... 1000 00 ..... .....|@rd_rn_rm
            SSUBLBT|01000101 .. 0 ..... 1000 10 ..... .....|@rd_rn_rm
            SSUBLTB|01000101 .. 0 ..... 1000 11 ..... .....|@rd_rn_rm
            SADDWB|01000101 .. 0 ..... 010 000 ..... .....|@rd_rn_rm
            SADDWT|01000101 .. 0 ..... 010 001 ..... .....|@rd_rn_rm
            UADDWB|01000101 .. 0 ..... 010 010 ..... .....|@rd_rn_rm
            UADDWT|01000101 .. 0 ..... 010 011 ..... .....|@rd_rn_rm
            SSUBWB|01000101 .. 0 ..... 010 100 ..... .....|@rd_rn_rm
            SSUBWT|01000101 .. 0 ..... 010 101 ..... .....|@rd_rn_rm
            USUBWB|01000101 .. 0 ..... 010 110 ..... .....|@rd_rn_rm
            USUBWT|01000101 .. 0 ..... 010 111 ..... .....|@rd_rn_rm
            SQDMULLB_zzz|01000101 .. 0 ..... 011 000 ..... .....|@rd_rn_rm
            SQDMULLT_zzz|01000101 .. 0 ..... 011 001 ..... .....|@rd_rn_rm
            PMULLB|01000101 .. 0 ..... 011 010 ..... .....|@rd_rn_rm
            PMULLT|01000101 .. 0 ..... 011 011 ..... .....|@rd_rn_rm
            SMULLB_zzz|01000101 .. 0 ..... 011 100 ..... .....|@rd_rn_rm
            SMULLT_zzz|01000101 .. 0 ..... 011 101 ..... .....|@rd_rn_rm
            UMULLB_zzz|01000101 .. 0 ..... 011 110 ..... .....|@rd_rn_rm
            UMULLT_zzz|01000101 .. 0 ..... 011 111 ..... .....|@rd_rn_rm
            SSHLLB|01000101 .. 0 ..... 1010 00 ..... .....|@rd_rn_tszimm_shl
            SSHLLT|01000101 .. 0 ..... 1010 01 ..... .....|@rd_rn_tszimm_shl
            USHLLB|01000101 .. 0 ..... 1010 10 ..... .....|@rd_rn_tszimm_shl
            USHLLT|01000101 .. 0 ..... 1010 11 ..... .....|@rd_rn_tszimm_shl
            EORBT|01000101 .. 0 ..... 10010 0 ..... .....|@rd_rn_rm
            EORTB|01000101 .. 0 ..... 10010 1 ..... .....|@rd_rn_rm
            SMMLA|01000101 00 0 ..... 10011 0 ..... .....|@rda_rn_rm_ex
            USMMLA|01000101 10 0 ..... 10011 0 ..... .....|@rda_rn_rm_ex
            UMMLA|01000101 11 0 ..... 10011 0 ..... .....|@rda_rn_rm_ex
            BEXT|01000101 .. 0 ..... 1011 00 ..... .....|@rd_rn_rm
            BDEP|01000101 .. 0 ..... 1011 01 ..... .....|@rd_rn_rm
            BGRP|01000101 .. 0 ..... 1011 10 ..... .....|@rd_rn_rm
            SQCVTN_sh|01000101 00 1 10001 010 000 ....0 .....|@rd_rnx2
            SQXTNB|01000101 .. 1 ..... 010 000 ..... .....|@rd_rn_tszimm_shl
            SQXTNT|01000101 .. 1 ..... 010 001 ..... .....|@rd_rn_tszimm_shl
            UQCVTN_sh|01000101 00 1 10001 010 010 ....0 .....|@rd_rnx2
            UQXTNB|01000101 .. 1 ..... 010 010 ..... .....|@rd_rn_tszimm_shl
            UQXTNT|01000101 .. 1 ..... 010 011 ..... .....|@rd_rn_tszimm_shl
            SQCVTUN_sh|01000101 00 1 10001 010 100 ....0 .....|@rd_rnx2
            SQXTUNB|01000101 .. 1 ..... 010 100 ..... .....|@rd_rn_tszimm_shl
            SQXTUNT|01000101 .. 1 ..... 010 101 ..... .....|@rd_rn_tszimm_shl
            SQSHRUNB|01000101 .. 1 ..... 00 0000 ..... .....|@rd_rn_tszimm_shr
            SQSHRUNT|01000101 .. 1 ..... 00 0001 ..... .....|@rd_rn_tszimm_shr
            SQRSHRUNB|01000101 .. 1 ..... 00 0010 ..... .....|@rd_rn_tszimm_shr
            SQRSHRUNT|01000101 .. 1 ..... 00 0011 ..... .....|@rd_rn_tszimm_shr
            SHRNB|01000101 .. 1 ..... 00 0100 ..... .....|@rd_rn_tszimm_shr
            SHRNT|01000101 .. 1 ..... 00 0101 ..... .....|@rd_rn_tszimm_shr
            RSHRNB|01000101 .. 1 ..... 00 0110 ..... .....|@rd_rn_tszimm_shr
            RSHRNT|01000101 .. 1 ..... 00 0111 ..... .....|@rd_rn_tszimm_shr
            SQSHRNB|01000101 .. 1 ..... 00 1000 ..... .....|@rd_rn_tszimm_shr
            SQSHRNT|01000101 .. 1 ..... 00 1001 ..... .....|@rd_rn_tszimm_shr
            SQRSHRNB|01000101 .. 1 ..... 00 1010 ..... .....|@rd_rn_tszimm_shr
            SQRSHRNT|01000101 .. 1 ..... 00 1011 ..... .....|@rd_rn_tszimm_shr
            UQSHRNB|01000101 .. 1 ..... 00 1100 ..... .....|@rd_rn_tszimm_shr
            UQSHRNT|01000101 .. 1 ..... 00 1101 ..... .....|@rd_rn_tszimm_shr
            UQRSHRNB|01000101 .. 1 ..... 00 1110 ..... .....|@rd_rn_tszimm_shr
            UQRSHRNT|01000101 .. 1 ..... 00 1111 ..... .....|@rd_rn_tszimm_shr
            ADDHNB|01000101 .. 1 ..... 011 000 ..... .....|@rd_rn_rm
            ADDHNT|01000101 .. 1 ..... 011 001 ..... .....|@rd_rn_rm
            RADDHNB|01000101 .. 1 ..... 011 010 ..... .....|@rd_rn_rm
            RADDHNT|01000101 .. 1 ..... 011 011 ..... .....|@rd_rn_rm
            SUBHNB|01000101 .. 1 ..... 011 100 ..... .....|@rd_rn_rm
            SUBHNT|01000101 .. 1 ..... 011 101 ..... .....|@rd_rn_rm
            RSUBHNB|01000101 .. 1 ..... 011 110 ..... .....|@rd_rn_rm
            RSUBHNT|01000101 .. 1 ..... 011 111 ..... .....|@rd_rn_rm
            """;

    private record Pattern(String name, int mask, int value, String format) {
    }

    private static List<Pattern> patterns() {
        List<Pattern> out = new ArrayList<>();
        for (String line : DECODE_LINES.strip().split("\n")) {
            String[] parts = line.strip().split("\\|");
            String bits = parts[1].replace(" ", "");
            int mask = 0;
            int value = 0;
            for (int i = 0; i < 32; i++) {
                char c = bits.charAt(i);
                mask = mask << 1 | (c == '.' ? 0 : 1);
                value = value << 1 | (c == '1' ? 1 : 0);
            }
            out.add(new Pattern(parts[0], mask, value, parts[2]));
        }
        return out;
    }

    private static int tsz(int word) {
        return ((word >>> 22) & 0b11) << 2 | (word >>> 19) & 0b11;
    }

    private static int tszEsz(int word) {
        return 31 - Integer.numberOfLeadingZeros(tsz(word));
    }

    /// As restrições que o `sve.decode` deixa para o tradutor (`esz` inválido, `tsz = 0`, `imm` de `SQXTN*` diferente de 0).
    private static boolean constraintsHold(Pattern pattern, int word) {
        int esz = (word >>> 22) & 0b11;
        String name = pattern.name;
        return switch (pattern.format) {
            case "@rd_rn_rm" -> switch (name) {
                case "EORBT", "EORTB", "BEXT", "BDEP", "BGRP" -> true;
                case "PMULLB", "PMULLT" -> esz != 2;
                default -> esz != 0;
            };
            case "@rd_rn_tszimm_shl" -> {
                if (tsz(word) == 0 || tszEsz(word) > 2) {
                    yield false;
                }
                boolean extract = name.startsWith("SQXTN") || name.startsWith("UQXTN") || name.startsWith("SQXTUN");
                int shift = (((word >>> 22) & 0b11) << 5 | (word >>> 16) & 0b11111) - (8 << tszEsz(word));
                yield !extract || shift == 0;
            }
            case "@rd_rn_tszimm_shr" -> tsz(word) != 0 && tszEsz(word) <= 2;
            default -> true; // @rda_rn_rm_ex e @rd_rnx2 já trazem tudo no padrão
        };
    }

    private static final Set<Ir64Op.SveIntegerUnpredicated.Op> NEW_OPS = Set.of(
            Ir64Op.SveIntegerUnpredicated.Op.SADDL, Ir64Op.SveIntegerUnpredicated.Op.UADDL,
            Ir64Op.SveIntegerUnpredicated.Op.SSUBL, Ir64Op.SveIntegerUnpredicated.Op.USUBL,
            Ir64Op.SveIntegerUnpredicated.Op.SABDL, Ir64Op.SveIntegerUnpredicated.Op.UABDL,
            Ir64Op.SveIntegerUnpredicated.Op.SADDW, Ir64Op.SveIntegerUnpredicated.Op.UADDW,
            Ir64Op.SveIntegerUnpredicated.Op.SSUBW, Ir64Op.SveIntegerUnpredicated.Op.USUBW,
            Ir64Op.SveIntegerUnpredicated.Op.SQDMULL, Ir64Op.SveIntegerUnpredicated.Op.SMULL,
            Ir64Op.SveIntegerUnpredicated.Op.UMULL, Ir64Op.SveIntegerUnpredicated.Op.PMULL,
            Ir64Op.SveIntegerUnpredicated.Op.SSHLL, Ir64Op.SveIntegerUnpredicated.Op.USHLL,
            Ir64Op.SveIntegerUnpredicated.Op.EORBT, Ir64Op.SveIntegerUnpredicated.Op.EORTB,
            Ir64Op.SveIntegerUnpredicated.Op.SMMLA, Ir64Op.SveIntegerUnpredicated.Op.USMMLA,
            Ir64Op.SveIntegerUnpredicated.Op.UMMLA, Ir64Op.SveIntegerUnpredicated.Op.BEXT,
            Ir64Op.SveIntegerUnpredicated.Op.BDEP, Ir64Op.SveIntegerUnpredicated.Op.BGRP,
            Ir64Op.SveIntegerUnpredicated.Op.SQXTN, Ir64Op.SveIntegerUnpredicated.Op.UQXTN,
            Ir64Op.SveIntegerUnpredicated.Op.SQXTUN, Ir64Op.SveIntegerUnpredicated.Op.SQCVTN,
            Ir64Op.SveIntegerUnpredicated.Op.UQCVTN, Ir64Op.SveIntegerUnpredicated.Op.SQCVTUN,
            Ir64Op.SveIntegerUnpredicated.Op.SHRN, Ir64Op.SveIntegerUnpredicated.Op.RSHRN,
            Ir64Op.SveIntegerUnpredicated.Op.SQSHRN, Ir64Op.SveIntegerUnpredicated.Op.SQRSHRN,
            Ir64Op.SveIntegerUnpredicated.Op.UQSHRN, Ir64Op.SveIntegerUnpredicated.Op.UQRSHRN,
            Ir64Op.SveIntegerUnpredicated.Op.SQSHRUN, Ir64Op.SveIntegerUnpredicated.Op.SQRSHRUN,
            Ir64Op.SveIntegerUnpredicated.Op.ADDHN, Ir64Op.SveIntegerUnpredicated.Op.RADDHN,
            Ir64Op.SveIntegerUnpredicated.Op.SUBHN, Ir64Op.SveIntegerUnpredicated.Op.RSUBHN);

    private static boolean isNewOp(Ir64Op decoded) {
        return decoded instanceof Ir64Op.SveIntegerUnpredicated u && NEW_OPS.contains(u.op());
    }

    /// Varre TODO o espaço `bits[23:16]` × `bits[15:10]` do prefixo `0x45` (16384 combinações, com `Zn` par e ímpar): uma
    /// palavra decodifica para uma das operações novas se e SÓ se algum padrão do `sve.decode` a reivindica (mais as
    /// restrições do tradutor). Tudo o que sobra tem que ser recusado ou pertencer a outra família (G8).
    @Test
    void theWholeSpaceMatchesTheDecodeFileExactly() {
        List<Pattern> patterns = patterns();
        assertEquals(76, patterns.size());
        int claimed = 0;
        for (int high = 0; high < 256; high++) {
            for (int opcode = 0; opcode < 64; opcode++) {
                for (int rn : new int[] {2, 3}) {
                    int word = PREFIX | (high << 16) | (opcode << 10) | (rn << 5) | Z1;
                    boolean expected = false;
                    for (Pattern pattern : patterns) {
                        if ((word & pattern.mask) == pattern.value && constraintsHold(pattern, word)) {
                            expected = true;
                            break;
                        }
                    }
                    Ir64Op decoded = decodeOrNull(ALL, word);
                    assertEquals(expected, isNewOp(decoded), "palavra 0x" + Integer.toHexString(word));
                    claimed += expected ? 1 : 0;
                    if (!expected) {
                        continue;
                    }
                    assertNull(decodeOrNull(SVE, word), "recusada sem SVE2: 0x" + Integer.toHexString(word));
                }
            }
        }
        assertTrue(claimed > 5000, "a varredura reivindicou " + claimed);
    }

    // ── Widening: long, wide, multiply long ──────────────────────────────────────────────────────

    private static LongCase longCase(String name, int opcode, int nTop, int mTop, boolean signed, LongOracle oracle) {
        return new LongCase(name, opcode, nTop, mTop, signed, oracle);
    }

    static Stream<LongCase> longCases() {
        List<LongCase> cases = new ArrayList<>();
        Object[][] base = {
            {"SADDL", 0b000000, true, (LongOracle) (n, m, e) -> n.add(m)},
            {"UADDL", 0b000010, false, (LongOracle) (n, m, e) -> n.add(m)},
            {"SSUBL", 0b000100, true, (LongOracle) (n, m, e) -> n.subtract(m)},
            {"USUBL", 0b000110, false, (LongOracle) (n, m, e) -> n.subtract(m)},
            {"SABDL", 0b001100, true, (LongOracle) (n, m, e) -> n.subtract(m).abs()},
            {"UABDL", 0b001110, false, (LongOracle) (n, m, e) -> n.subtract(m).abs()},
            {"SQDMULL", 0b011000, true, (LongOracle) (n, m, e) -> clampSigned(n.multiply(m).shiftLeft(1), e)},
            {"SMULL", 0b011100, true, (LongOracle) (n, m, e) -> n.multiply(m)},
            {"UMULL", 0b011110, false, (LongOracle) (n, m, e) -> n.multiply(m)},
        };
        for (Object[] row : base) {
            cases.add(longCase(row[0] + "B", (Integer) row[1], 0, 0, (Boolean) row[2], (LongOracle) row[3]));
            cases.add(longCase(row[0] + "T", (Integer) row[1] | 1, 1, 1, (Boolean) row[2], (LongOracle) row[3]));
        }
        cases.add(longCase("SADDLBT", 0b100000, 0, 1, true, (n, m, e) -> n.add(m)));
        cases.add(longCase("SSUBLBT", 0b100010, 0, 1, true, (n, m, e) -> n.subtract(m)));
        cases.add(longCase("SSUBLTB", 0b100011, 1, 0, true, (n, m, e) -> n.subtract(m)));
        return cases.stream();
    }

    /// `Zd` recebe `f(ext(Zn[2e+nTop]), ext(Zm[2e+mTop]))` no tamanho largo — testado com o destino em `Zd`, em `Zn` e em `Zm`.
    @ParameterizedTest
    @MethodSource("longCases")
    void longOperationsSelectBottomAndTopIndependentlyForEachSource(LongCase c) {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 1; esz <= 3; esz++) {
                for (int rd : new int[] {Z1, Z2, Z3}) {
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Random random = new Random(seed * 31L + esz);
                        Aarch64Core core = core(ALL, vl);
                        int source = esz - 1;
                        setElements(core, Z2, source, randomElements(core, source, random));
                        setElements(core, Z3, source, randomElements(core, source, random));
                        setElements(core, Z1, 3, randomElements(core, 3, random));
                        long[] n = elements(core, Z2, source);
                        long[] m = elements(core, Z3, source);
                        run(ALL, core, wideWord(esz, c.opcode, rd, Z2, Z3));
                        long[] actual = elements(core, rd, esz);
                        for (int e = 0; e < actual.length; e++) {
                            BigInteger expected = c.oracle.apply(extended(n[2 * e + c.nTop], source, c.signed),
                                    extended(m[2 * e + c.mTop], source, c.signed), esz);
                            assertEquals(truncate(expected, esz), actual[e],
                                    c.name + " esz=" + esz + " vl=" + vl + " rd=" + rd + " e=" + e);
                        }
                    }
                }
            }
        }
    }

    static Stream<LongCase> wideCases() {
        List<LongCase> cases = new ArrayList<>();
        Object[][] base = {
            {"SADDW", 0b010000, true, (LongOracle) (n, m, e) -> n.add(m)},
            {"UADDW", 0b010010, false, (LongOracle) (n, m, e) -> n.add(m)},
            {"SSUBW", 0b010100, true, (LongOracle) (n, m, e) -> n.subtract(m)},
            {"USUBW", 0b010110, false, (LongOracle) (n, m, e) -> n.subtract(m)},
        };
        for (Object[] row : base) {
            cases.add(longCase(row[0] + "B", (Integer) row[1], 0, 0, (Boolean) row[2], (LongOracle) row[3]));
            cases.add(longCase(row[0] + "T", (Integer) row[1] | 1, 0, 1, (Boolean) row[2], (LongOracle) row[3]));
        }
        return cases.stream();
    }

    /// `*ADDW`/`*SUBW`: `Zn` já é largo (todos os elementos); só `Zm` é estreito e escolhe `B`/`T`.
    @ParameterizedTest
    @MethodSource("wideCases")
    void wideOperationsKeepZnWideAndSelectBottomOrTopOfZm(LongCase c) {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 1; esz <= 3; esz++) {
                for (int rd : new int[] {Z1, Z2, Z3}) {
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Random random = new Random(seed * 17L + esz);
                        Aarch64Core core = core(ALL, vl);
                        int source = esz - 1;
                        setElements(core, Z2, esz, randomElements(core, esz, random));
                        setElements(core, Z3, source, randomElements(core, source, random));
                        setElements(core, Z1, 3, randomElements(core, 3, random));
                        long[] n = elements(core, Z2, esz);
                        long[] m = elements(core, Z3, source);
                        run(ALL, core, wideWord(esz, c.opcode, rd, Z2, Z3));
                        long[] actual = elements(core, rd, esz);
                        for (int e = 0; e < actual.length; e++) {
                            BigInteger expected = c.oracle.apply(unsigned(n[e], esz),
                                    extended(m[2 * e + c.mTop], source, c.signed), esz);
                            assertEquals(truncate(expected, esz), actual[e],
                                    c.name + " esz=" + esz + " vl=" + vl + " rd=" + rd + " e=" + e);
                        }
                    }
                }
            }
        }
    }

    // ── Widening: PMULL, SSHLL/USHLL, EORBT/EORTB, BitPerm, MMLA ─────────────────────────────────

    private static BigInteger carrylessMultiply(BigInteger a, BigInteger b) {
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < b.bitLength(); i++) {
            if (b.testBit(i)) {
                result = result.xor(a.shiftLeft(i));
            }
        }
        return result;
    }

    @Test
    void pmullMultipliesPolynomialsBottomAndTop() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz : new int[] {0, 1, 3}) {
                for (int top = 0; top < 2; top++) {
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Random random = new Random(seed + 7L * esz);
                        Aarch64Core core = core(ALL, vl);
                        int source = esz == 0 ? 3 : esz - 1;
                        setElements(core, Z2, source, randomElements(core, source, random));
                        setElements(core, Z3, source, randomElements(core, source, random));
                        setElements(core, Z1, 3, randomElements(core, 3, random));
                        long[] n = elements(core, Z2, source);
                        long[] m = elements(core, Z3, source);
                        run(ALL, core, wideWord(esz, 0b011010 | top, Z1, Z2, Z3));
                        if (esz == 0) {
                            long[] actual = elements(core, Z1, 3);
                            for (int s = 0; s < actual.length / 2; s++) {
                                BigInteger product = carrylessMultiply(unsigned(n[2 * s + top], 3),
                                        unsigned(m[2 * s + top], 3));
                                assertEquals(truncate(product, 3), actual[2 * s], "PMULL.Q baixo, segmento " + s);
                                assertEquals(truncate(product.shiftRight(64), 3), actual[2 * s + 1],
                                        "PMULL.Q alto, segmento " + s);
                            }
                        } else {
                            long[] actual = elements(core, Z1, esz);
                            for (int e = 0; e < actual.length; e++) {
                                BigInteger product = carrylessMultiply(unsigned(n[2 * e + top], source),
                                        unsigned(m[2 * e + top], source));
                                assertEquals(truncate(product, esz), actual[e], "PMULL esz=" + esz + " e=" + e);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void shiftLeftLongExtendsTheSelectedElementThenShifts() {
        for (int vl : VECTOR_LENGTHS) {
            for (int source = 0; source <= 2; source++) {
                for (int shift : new int[] {0, 1, bits(source) / 2, bits(source) - 1}) {
                    for (int top = 0; top < 2; top++) {
                        for (int unsignedForm = 0; unsignedForm < 2; unsignedForm++) {
                            Random random = new Random(source * 100L + shift);
                            Aarch64Core core = core(ALL, vl);
                            setElements(core, Z2, source, randomElements(core, source, random));
                            setElements(core, Z1, 3, randomElements(core, 3, random));
                            long[] n = elements(core, Z2, source);
                            int opcode = 0b101000 | unsignedForm << 1 | top;
                            run(ALL, core, shiftWord(0, (8 << source) + shift, opcode, Z2, Z1));
                            long[] actual = elements(core, Z1, source + 1);
                            for (int e = 0; e < actual.length; e++) {
                                BigInteger value = extended(n[2 * e + top], source, unsignedForm == 0);
                                assertEquals(truncate(value.shiftLeft(shift), source + 1), actual[e],
                                        "SHLL source=" + source + " shift=" + shift + " top=" + top + " e=" + e);
                            }
                        }
                    }
                }
            }
        }
    }

    /// `EORBT`/`EORTB` escrevem UM elemento do par e PRESERVAM o outro (o destino pré-preenchido prova isso).
    @Test
    void interleavedExclusiveOrWritesOneElementOfEachPairAndPreservesTheOther() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (int form = 0; form < 2; form++) { // 0 = EORBT, 1 = EORTB
                    for (int rd : new int[] {Z1, Z2, Z3}) {
                        Random random = new Random(esz * 13L + form);
                        Aarch64Core core = core(ALL, vl);
                        setElements(core, Z2, esz, randomElements(core, esz, random));
                        setElements(core, Z3, esz, randomElements(core, esz, random));
                        setElements(core, Z1, esz, randomElements(core, esz, random));
                        long[] n = elements(core, Z2, esz);
                        long[] m = elements(core, Z3, esz);
                        long[] before = elements(core, rd, esz);
                        run(ALL, core, wideWord(esz, 0b100100 | form, rd, Z2, Z3));
                        long[] actual = elements(core, rd, esz);
                        for (int e = 0; e < actual.length; e += 2) {
                            int written = form == 0 ? e : e + 1;
                            int kept = form == 0 ? e + 1 : e;
                            long expected = form == 0 ? n[e] ^ m[e + 1] : n[e + 1] ^ m[e];
                            assertEquals(expected, actual[written], "EOR" + (form == 0 ? "BT" : "TB") + " escrito");
                            assertEquals(before[kept], actual[kept], "elemento preservado do par");
                        }
                    }
                }
            }
        }
    }

    @Test
    void bitPermutesFollowTheJdkCompressAndExpand() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= 3; esz++) {
                for (int form = 0; form < 3; form++) { // BEXT, BDEP, BGRP
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Random random = new Random(seed * 5L + esz);
                        Aarch64Core core = core(ALL, vl);
                        setElements(core, Z2, esz, randomElements(core, esz, random));
                        setElements(core, Z3, esz, randomElements(core, esz, random));
                        long[] data = elements(core, Z2, esz);
                        long[] mask = elements(core, Z3, esz);
                        run(ALL, core, wideWord(esz, 0b101100 | form, Z1, Z2, Z3));
                        long[] actual = elements(core, Z1, esz);
                        long all = bits(esz) == 64 ? -1L : (1L << bits(esz)) - 1;
                        for (int e = 0; e < actual.length; e++) {
                            long expected = switch (form) {
                                case 0 -> Long.compress(data[e], mask[e]);
                                case 1 -> Long.expand(data[e], mask[e]);
                                default -> Long.compress(data[e], mask[e])
                                        | Long.compress(data[e], ~mask[e] & all) << Long.bitCount(mask[e]);
                            };
                            assertEquals(expected & all, actual[e], "form=" + form + " esz=" + esz + " e=" + e);
                        }
                    }
                }
            }
        }
    }

    @Test
    void matrixMultiplyAccumulatesTheTwoByTwoTilePerQuadword() {
        for (int vl : VECTOR_LENGTHS) {
            for (int form = 0; form < 3; form++) { // SMMLA (S,S), USMMLA (U,S), UMMLA (U,U)
                for (int rd : new int[] {Z1, Z2, Z3}) {
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Random random = new Random(seed * 3L + form);
                        Aarch64Core core = core(ALL, vl);
                        setElements(core, Z2, 0, randomElements(core, 0, random));
                        setElements(core, Z3, 0, randomElements(core, 0, random));
                        setElements(core, Z1, 2, randomElements(core, 2, random));
                        long[] n = elements(core, Z2, 0);
                        long[] m = elements(core, Z3, 0);
                        long[] accumulator = elements(core, rd, 2);
                        int esz = new int[] {0b00, 0b10, 0b11}[form];
                        run(ALL, core, wideWord(esz, 0b100110, rd, Z2, Z3));
                        long[] actual = elements(core, rd, 2);
                        for (int segment = 0; segment < vl / 128; segment++) {
                            for (int row = 0; row < 2; row++) {
                                for (int column = 0; column < 2; column++) {
                                    BigInteger sum = unsigned(accumulator[4 * segment + 2 * row + column], 2);
                                    for (int k = 0; k < 8; k++) {
                                        sum = sum.add(extended(n[16 * segment + 8 * row + k], 0, form == 0)
                                                .multiply(extended(m[16 * segment + 8 * column + k], 0, form != 2)));
                                    }
                                    assertEquals(truncate(sum, 2), actual[4 * segment + 2 * row + column],
                                            "form=" + form + " segmento " + segment + " [" + row + "][" + column + "]");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Narrowing ────────────────────────────────────────────────────────────────────────────────

    private interface NarrowOracle {
        /// `n` é o elemento largo (sem sinal, `wideEsz`); devolve o valor matemático já saturado/truncado do estreito.
        BigInteger apply(BigInteger n, int wideEsz);
    }

    /// Verifica um narrowing `*B`/`*T` de UMA fonte: `*B` escreve o slot largo inteiro (par = resultado, ímpar = 0), `*T`
    /// escreve só o ímpar e preserva o par. `rd` cobre `Zd`, `Zn`.
    private static void checkNarrow(String name, int word, int wideEsz, boolean top, int rd, NarrowOracle oracle,
            Random random, int vl) {
        Aarch64Core core = core(ALL, vl);
        int narrow = wideEsz - 1;
        setElements(core, Z2, wideEsz, randomElements(core, wideEsz, random));
        setElements(core, Z1, narrow, randomElements(core, narrow, random));
        long[] source = elements(core, Z2, wideEsz);
        long[] before = elements(core, rd, narrow);
        run(ALL, core, word);
        long[] actual = elements(core, rd, narrow);
        for (int e = 0; e < source.length; e++) {
            long value = truncate(oracle.apply(unsigned(source[e], wideEsz), wideEsz), narrow);
            if (top) {
                assertEquals(before[2 * e], actual[2 * e], name + " preserva o elemento par, e=" + e);
                assertEquals(value, actual[2 * e + 1], name + " e=" + e + " esz=" + wideEsz);
            } else {
                assertEquals(value, actual[2 * e], name + " e=" + e + " esz=" + wideEsz);
                assertEquals(0L, actual[2 * e + 1], name + " zera o elemento ímpar, e=" + e);
            }
        }
    }

    private record ExtractCase(String name, int opcode, NarrowOracle oracle) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ExtractCase> extractCases() {
        return Stream.of(
                new ExtractCase("SQXTN", 0b010000, (n, e) -> clampSigned(signedFrom(n, e), e - 1)),
                new ExtractCase("UQXTN", 0b010010, (n, e) -> n.min(modulus(e - 1).subtract(BigInteger.ONE))),
                new ExtractCase("SQXTUN", 0b010100, (n, e) -> clampUnsigned(signedFrom(n, e), e - 1)));
    }

    private static BigInteger signedFrom(BigInteger unsigned, int esz) {
        return unsigned.testBit(bits(esz) - 1) ? unsigned.subtract(modulus(esz)) : unsigned;
    }

    @ParameterizedTest
    @MethodSource("extractCases")
    void extractNarrowSaturatesAndBottomZeroesWhileTopPreserves(ExtractCase c) {
        for (int vl : VECTOR_LENGTHS) {
            for (int wide = 1; wide <= 3; wide++) {
                for (int top = 0; top < 2; top++) {
                    for (int rd : new int[] {Z1, Z2}) {
                        for (int seed = 0; seed < SEEDS; seed++) {
                            int word = shiftWord(NARROWING_BIT, 8 << (wide - 1), c.opcode | top, Z2, rd);
                            checkNarrow(c.name, word, wide, top == 1, rd, c.oracle, new Random(seed * 7L + wide), vl);
                        }
                    }
                }
            }
        }
    }

    private record ShiftCase(String name, int opcode, boolean signedSource, boolean rounding, int kind) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ShiftCase> shiftCases() {
        // kind: 0 = trunca, 1 = satura com sinal, 2 = satura sem sinal (fonte sem sinal), 3 = com sinal para sem sinal
        return Stream.of(
                new ShiftCase("SQSHRUN", 0b000000, true, false, 3), new ShiftCase("SQRSHRUN", 0b000010, true, true, 3),
                new ShiftCase("SHRN", 0b000100, false, false, 0), new ShiftCase("RSHRN", 0b000110, false, true, 0),
                new ShiftCase("SQSHRN", 0b001000, true, false, 1), new ShiftCase("SQRSHRN", 0b001010, true, true, 1),
                new ShiftCase("UQSHRN", 0b001100, false, false, 2), new ShiftCase("UQRSHRN", 0b001110, false, true, 2));
    }

    @ParameterizedTest
    @MethodSource("shiftCases")
    void shiftRightNarrowFollowsTheSaturationAndRoundingOfEachForm(ShiftCase c) {
        for (int vl : VECTOR_LENGTHS) {
            for (int wide = 1; wide <= 3; wide++) {
                int narrow = wide - 1;
                for (int shift : new int[] {1, 2, bits(narrow) / 2, bits(narrow) - 1, bits(narrow)}) {
                    for (int top = 0; top < 2; top++) {
                        for (int rd : new int[] {Z1, Z2}) {
                            int word = shiftWord(NARROWING_BIT, (16 << narrow) - shift, c.opcode | top, Z2, rd);
                            NarrowOracle oracle = (n, e) -> {
                                BigInteger value = c.signedSource ? signedFrom(n, e) : n;
                                if (c.rounding) {
                                    value = value.add(BigInteger.ONE.shiftLeft(shift - 1));
                                }
                                value = value.shiftRight(shift);
                                return switch (c.kind) {
                                    case 1 -> clampSigned(value, e - 1);
                                    case 2 -> value.min(modulus(e - 1).subtract(BigInteger.ONE));
                                    case 3 -> clampUnsigned(value, e - 1);
                                    default -> value;
                                };
                            };
                            for (int seed = 0; seed < SEEDS; seed++) {
                                checkNarrow(c.name + " #" + shift, word, wide, top == 1, rd, oracle,
                                        new Random(seed * 11L + shift), vl);
                            }
                        }
                    }
                }
            }
        }
    }

    private record HighPartCase(String name, int opcode, boolean subtract, boolean rounding) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<HighPartCase> highPartCases() {
        return Stream.of(new HighPartCase("ADDHN", 0b011000, false, false), new HighPartCase("RADDHN", 0b011010, false, true),
                new HighPartCase("SUBHN", 0b011100, true, false), new HighPartCase("RSUBHN", 0b011110, true, true));
    }

    @ParameterizedTest
    @MethodSource("highPartCases")
    void highPartNarrowTakesTheUpperHalfOfTheSumOrDifference(HighPartCase c) {
        for (int vl : VECTOR_LENGTHS) {
            for (int wide = 1; wide <= 3; wide++) {
                for (int top = 0; top < 2; top++) {
                    for (int rd : new int[] {Z1, Z2, Z3}) {
                        for (int seed = 0; seed < SEEDS; seed++) {
                            Random random = new Random(seed * 19L + wide);
                            Aarch64Core core = core(ALL, vl);
                            int narrow = wide - 1;
                            setElements(core, Z2, wide, randomElements(core, wide, random));
                            setElements(core, Z3, wide, randomElements(core, wide, random));
                            setElements(core, Z1, narrow, randomElements(core, narrow, random));
                            long[] n = elements(core, Z2, wide);
                            long[] m = elements(core, Z3, wide);
                            long[] before = elements(core, rd, narrow);
                            run(ALL, core, wideWord(wide, c.opcode | top, rd, Z2, Z3) | NARROWING_BIT);
                            long[] actual = elements(core, rd, narrow);
                            for (int e = 0; e < n.length; e++) {
                                BigInteger sum = c.subtract ? unsigned(n[e], wide).subtract(unsigned(m[e], wide))
                                        : unsigned(n[e], wide).add(unsigned(m[e], wide));
                                if (c.rounding) {
                                    sum = sum.add(BigInteger.ONE.shiftLeft(bits(narrow) - 1));
                                }
                                long expected = truncate(sum.shiftRight(bits(narrow)), narrow);
                                if (top == 1) {
                                    assertEquals(before[2 * e], actual[2 * e], c.name + " preserva o par");
                                    assertEquals(expected, actual[2 * e + 1], c.name + " e=" + e);
                                } else {
                                    assertEquals(expected, actual[2 * e], c.name + " e=" + e);
                                    assertEquals(0L, actual[2 * e + 1], c.name + " zera o ímpar");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void pairConvertSaturatesAndInterleavesTheTwoSourceRegisters() {
        for (int vl : VECTOR_LENGTHS) {
            for (int form = 0; form < 3; form++) { // SQCVTN, UQCVTN, SQCVTUN
                for (int first : new int[] {4, 30}) {
                    for (int rd : new int[] {Z1, first, first + 1}) {
                        for (int seed = 0; seed < SEEDS; seed++) {
                            Random random = new Random(seed * 23L + form);
                            Aarch64Core core = core(ALL, vl);
                            setElements(core, first, 2, randomElements(core, 2, random));
                            setElements(core, first + 1, 2, randomElements(core, 2, random));
                            long[] a = elements(core, first, 2);
                            long[] b = elements(core, first + 1, 2);
                            int word = PREFIX | NARROWING_BIT | (0b10001 << 16) | ((0b010000 | form << 1) << 10)
                                    | (first / 2 << 6) | rd;
                            run(ALL, core, word);
                            long[] actual = elements(core, rd, 1);
                            for (int i = 0; i < a.length; i++) {
                                for (int half = 0; half < 2; half++) {
                                    BigInteger value = unsigned(half == 0 ? a[i] : b[i], 2);
                                    BigInteger expected = switch (form) {
                                        case 0 -> clampSigned(signedFrom(value, 2), 1);
                                        case 1 -> value.min(modulus(1).subtract(BigInteger.ONE));
                                        default -> clampUnsigned(signedFrom(value, 2), 1);
                                    };
                                    assertEquals(truncate(expected, 1), actual[2 * i + half],
                                            "form=" + form + " rd=" + rd + " i=" + i + " metade=" + half);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Streaming e SME2 ─────────────────────────────────────────────────────────────────────────

    private static final int SMSTART_SM = 0xd503437f;

    private static Aarch64Core streamingCore(Aarch64Architecture architecture) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        return core;
    }

    private static final Aarch64Architecture STREAMING = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-streaming", Aarch64Feature.SVE2, Aarch64Feature.SVE_BITPERM, Aarch64Feature.SVE_PMULL128);

    @Test
    void wideningRunsAtTheStreamingVectorLength() {
        Aarch64Core core = streamingCore(STREAMING);
        setElements(core, Z2, 0, randomElements(core, 0, new Random(1)));
        setElements(core, Z3, 0, randomElements(core, 0, new Random(2)));
        long[] n = elements(core, Z2, 0);
        long[] m = elements(core, Z3, 0);
        run(STREAMING, core, wideWord(1, 0b000000, Z1, Z2, Z3)); // SADDLB
        long[] actual = elements(core, Z1, 1);
        assertEquals(32, actual.length, "SVL = 512 em streaming");
        for (int e = 0; e < actual.length; e++) {
            assertEquals(truncate(signed(n[2 * e], 0).add(signed(m[2 * e], 0)), 1), actual[e]);
        }
    }

    /// `BEXT`/`BDEP`/`BGRP`, `PMULLB.Q` e as matrizes de 8 bits são UNDEFINED em streaming (sem `FEAT_SME_FA64` e sem as
    /// sub-features `SSVE_*`, que não estão modeladas).
    @ParameterizedTest
    @MethodSource("nonStreamingWords")
    void bitPermuteMatrixAndPmull128AreUndefinedInStreamingMode(int word) {
        Aarch64Core core = streamingCore(STREAMING);
        setElements(core, Z1, 3, new long[] {0x1234L, 0, 0, 0, 0, 0, 0, 0});
        core.memory().write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(STREAMING).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(0L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=0: UNDEFINED");
        assertEquals(0x1234L, elements(core, Z1, 3)[0], "a instrução não executou");
    }

    static Stream<Integer> nonStreamingWords() {
        return Stream.of(wideWord(2, 0b101100, Z1, Z2, Z3), wideWord(3, 0b101101, Z1, Z2, Z3),
                wideWord(0, 0b101110, Z1, Z2, Z3), wideWord(0, 0b011010, Z1, Z2, Z3), wideWord(0, 0b100110, Z1, Z2, Z3),
                wideWord(2, 0b100110, Z1, Z2, Z3) & 0xFF3FFFFF | 0x00800000);
    }

    /// Sem `FEAT_SVE2p1`, `SQCVTN`/`UQCVTN`/`SQCVTUN` (só SME2) existem SÓ em streaming.
    @Test
    void pairConvertWithoutSve2p1IsStreamingOnly() {
        Aarch64Architecture sme2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "so-SME2",
                Aarch64Feature.SVE2, Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
        int word = PREFIX | NARROWING_BIT | (0b10001 << 16) | (0b010000 << 10) | (2 << 6) | Z1;
        Aarch64Core streaming = streamingCore(sme2);
        setElements(streaming, 4, 2, randomElements(streaming, 2, new Random(3)));
        run(sme2, streaming, word);
        assertEquals(0x4L, streaming.pc(), "executou em streaming");

        Aarch64Core normal = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), sme2, 256, 512);
        normal.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        setElements(normal, Z1, 3, new long[] {0x1234L, 0, 0, 0});
        run(sme2, normal, word);
        assertTrue(normal.pc() >= 0x400L, "trapou fora do modo streaming");
        assertEquals(0x1234L, elements(normal, Z1, 3)[0]);
    }
}
