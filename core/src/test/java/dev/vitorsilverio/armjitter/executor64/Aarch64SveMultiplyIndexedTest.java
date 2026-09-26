package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.8 — multiply SVE por elemento indexado e dot-products vetoriais (`SDOT`/`UDOT`/`USDOT`/`SUDOT`/`CDOT`,
/// `MLA`/`MLS`/`MUL`, `SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH`, as alargantes `B`/`T` e `CMLA`/`SQRDCMLAH`).
/// As palavras da tabela foram montadas com `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1+i8mm`) a partir do TEXTO de cada linha — os campos esperados (índice, `Zm`,
/// rotação, `B`/`T`) vêm do texto, nunca do decoder. O oráculo é escrito com `BigInteger` e com a formulação do
/// manual (números complexos, segmentos de 128 bits), sem repetir a aritmética do executor.
class Aarch64SveMultiplyIndexedTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 512};
    private static final int RD = 0;
    private static final int RN = 3;
    private static final int SEGMENT_BITS = 128;
    private static final int REGISTER_FIELD_MASK = 0x1F;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE_I8MM = Aarch64Architecture.extending(SVE, "teste-SVE-I8MM",
            Aarch64Feature.INT8_MATRIX_MULTIPLY);
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture SVE2P2 = Aarch64Architecture.extending(SVE2, "teste-SVE2p2",
            Aarch64Feature.SVE2_2);
    /// Tudo o que as linhas exigem, para executar qualquer uma.
    private static final Aarch64Architecture FULL = Aarch64Architecture.extending(SVE2P1, "teste-completa",
            Aarch64Feature.INT8_MATRIX_MULTIPLY);

    private record Row(int word, String asm, Ir64Op.SveMultiplyIndexed.Op op, int esz, int rm, boolean indexed,
            int index, int rot, boolean top, int ways) {
        Ir64Op.SveMultiplyIndexed expectedOp() {
            return new Ir64Op.SveMultiplyIndexed(op, esz, RD, RN, rm, indexed, index, rot, top, ways, 0L);
        }

        /// Menor arquitetura de teste que aceita a linha.
        Aarch64Architecture minimum() {
            return switch (op) {
                case SDOT, UDOT -> ways == 2 ? SVE2P1 : SVE;
                case USDOT, SUDOT -> SVE_I8MM;
                default -> SVE2;
            };
        }

        /// Uma arquitetura de teste que ainda a recusa (`null` = nenhuma além de "sem SVE").
        Aarch64Architecture refusing() {
            return switch (op) {
                case SDOT, UDOT -> ways == 2 ? SVE2 : null;
                case USDOT, SUDOT -> SVE2;
                default -> SVE;
            };
        }
    }

    private static Row row(int word, String asm, String op, int esz, int rm, boolean indexed, int index, int rot,
            boolean top, int ways) {
        return new Row(word, asm, Ir64Op.SveMultiplyIndexed.Op.valueOf(op), esz, rm, indexed, index, rot, top, ways);
    }

    private static Stream<Row> rows() {
        return Stream.of(
            row(0x44bf0060, "sdot z0.s, z3.b, z7.b[3]", "SDOT", 2, 7, true, 3, 0, false, 4),
            row(0x44a00060, "sdot z0.s, z3.b, z0.b[0]", "SDOT", 2, 0, true, 0, 0, false, 4),
            row(0x44b50460, "udot z0.s, z3.b, z5.b[2]", "UDOT", 2, 5, true, 2, 0, false, 4),
            row(0x44ff0060, "sdot z0.d, z3.h, z15.h[1]", "SDOT", 3, 15, true, 1, 0, false, 4),
            row(0x44e90060, "sdot z0.d, z3.h, z9.h[0]", "SDOT", 3, 9, true, 0, 0, false, 4),
            row(0x44f90460, "udot z0.d, z3.h, z9.h[1]", "UDOT", 3, 9, true, 1, 0, false, 4),
            row(0x449fc860, "sdot z0.s, z3.h, z7.h[3]", "SDOT", 2, 7, true, 3, 0, false, 2),
            row(0x4489c860, "sdot z0.s, z3.h, z1.h[1]", "SDOT", 2, 1, true, 1, 0, false, 2),
            row(0x4497cc60, "udot z0.s, z3.h, z7.h[2]", "UDOT", 2, 7, true, 2, 0, false, 2),
            row(0x44bf1860, "usdot z0.s, z3.b, z7.b[3]", "USDOT", 2, 7, true, 3, 0, false, 4),
            row(0x44ae1c60, "sudot z0.s, z3.b, z6.b[1]", "SUDOT", 2, 6, true, 1, 0, false, 4),
            row(0x44870060, "sdot z0.s, z3.b, z7.b", "SDOT", 2, 7, false, 0, 0, false, 4),
            row(0x449f0460, "udot z0.s, z3.b, z31.b", "UDOT", 2, 31, false, 0, 0, false, 4),
            row(0x44c90060, "sdot z0.d, z3.h, z9.h", "SDOT", 3, 9, false, 0, 0, false, 4),
            row(0x44de0460, "udot z0.d, z3.h, z30.h", "UDOT", 3, 30, false, 0, 0, false, 4),
            row(0x44871060, "cdot z0.s, z3.b, z7.b, #0", "CDOT", 2, 7, false, 0, 0, false, 0),
            row(0x44871460, "cdot z0.s, z3.b, z7.b, #90", "CDOT", 2, 7, false, 0, 1, false, 0),
            row(0x44c91860, "cdot z0.d, z3.h, z9.h, #180", "CDOT", 3, 9, false, 0, 2, false, 0),
            row(0x44c91c60, "cdot z0.d, z3.h, z9.h, #270", "CDOT", 3, 9, false, 0, 3, false, 0),
            row(0x44bf4460, "cdot z0.s, z3.b, z7.b[3], #90", "CDOT", 2, 7, true, 3, 1, false, 0),
            row(0x44a74c60, "cdot z0.s, z3.b, z7.b[0], #270", "CDOT", 2, 7, true, 0, 3, false, 0),
            row(0x44f94860, "cdot z0.d, z3.h, z9.h[1], #180", "CDOT", 3, 9, true, 1, 2, false, 0),
            row(0x44ef4060, "cdot z0.d, z3.h, z15.h[0], #0", "CDOT", 3, 15, true, 0, 0, false, 0),
            row(0x447f0860, "mla z0.h, z3.h, z7.h[7]", "MLA", 1, 7, true, 7, 0, false, 0),
            row(0x44200860, "mla z0.h, z3.h, z0.h[0]", "MLA", 1, 0, true, 0, 0, false, 0),
            row(0x446b0860, "mla z0.h, z3.h, z3.h[5]", "MLA", 1, 3, true, 5, 0, false, 0),
            row(0x44bf0860, "mla z0.s, z3.s, z7.s[3]", "MLA", 2, 7, true, 3, 0, false, 0),
            row(0x44ac0860, "mla z0.s, z3.s, z4.s[1]", "MLA", 2, 4, true, 1, 0, false, 0),
            row(0x44ff0860, "mla z0.d, z3.d, z15.d[1]", "MLA", 3, 15, true, 1, 0, false, 0),
            row(0x44e90860, "mla z0.d, z3.d, z9.d[0]", "MLA", 3, 9, true, 0, 0, false, 0),
            row(0x447f0c60, "mls z0.h, z3.h, z7.h[7]", "MLS", 1, 7, true, 7, 0, false, 0),
            row(0x44200c60, "mls z0.h, z3.h, z0.h[0]", "MLS", 1, 0, true, 0, 0, false, 0),
            row(0x446b0c60, "mls z0.h, z3.h, z3.h[5]", "MLS", 1, 3, true, 5, 0, false, 0),
            row(0x44bf0c60, "mls z0.s, z3.s, z7.s[3]", "MLS", 2, 7, true, 3, 0, false, 0),
            row(0x44ac0c60, "mls z0.s, z3.s, z4.s[1]", "MLS", 2, 4, true, 1, 0, false, 0),
            row(0x44ff0c60, "mls z0.d, z3.d, z15.d[1]", "MLS", 3, 15, true, 1, 0, false, 0),
            row(0x44e90c60, "mls z0.d, z3.d, z9.d[0]", "MLS", 3, 9, true, 0, 0, false, 0),
            row(0x447f1060, "sqrdmlah z0.h, z3.h, z7.h[7]", "SQRDMLAH", 1, 7, true, 7, 0, false, 0),
            row(0x44201060, "sqrdmlah z0.h, z3.h, z0.h[0]", "SQRDMLAH", 1, 0, true, 0, 0, false, 0),
            row(0x446b1060, "sqrdmlah z0.h, z3.h, z3.h[5]", "SQRDMLAH", 1, 3, true, 5, 0, false, 0),
            row(0x44bf1060, "sqrdmlah z0.s, z3.s, z7.s[3]", "SQRDMLAH", 2, 7, true, 3, 0, false, 0),
            row(0x44ac1060, "sqrdmlah z0.s, z3.s, z4.s[1]", "SQRDMLAH", 2, 4, true, 1, 0, false, 0),
            row(0x44ff1060, "sqrdmlah z0.d, z3.d, z15.d[1]", "SQRDMLAH", 3, 15, true, 1, 0, false, 0),
            row(0x44e91060, "sqrdmlah z0.d, z3.d, z9.d[0]", "SQRDMLAH", 3, 9, true, 0, 0, false, 0),
            row(0x447f1460, "sqrdmlsh z0.h, z3.h, z7.h[7]", "SQRDMLSH", 1, 7, true, 7, 0, false, 0),
            row(0x44201460, "sqrdmlsh z0.h, z3.h, z0.h[0]", "SQRDMLSH", 1, 0, true, 0, 0, false, 0),
            row(0x446b1460, "sqrdmlsh z0.h, z3.h, z3.h[5]", "SQRDMLSH", 1, 3, true, 5, 0, false, 0),
            row(0x44bf1460, "sqrdmlsh z0.s, z3.s, z7.s[3]", "SQRDMLSH", 2, 7, true, 3, 0, false, 0),
            row(0x44ac1460, "sqrdmlsh z0.s, z3.s, z4.s[1]", "SQRDMLSH", 2, 4, true, 1, 0, false, 0),
            row(0x44ff1460, "sqrdmlsh z0.d, z3.d, z15.d[1]", "SQRDMLSH", 3, 15, true, 1, 0, false, 0),
            row(0x44e91460, "sqrdmlsh z0.d, z3.d, z9.d[0]", "SQRDMLSH", 3, 9, true, 0, 0, false, 0),
            row(0x447ff060, "sqdmulh z0.h, z3.h, z7.h[7]", "SQDMULH", 1, 7, true, 7, 0, false, 0),
            row(0x4420f060, "sqdmulh z0.h, z3.h, z0.h[0]", "SQDMULH", 1, 0, true, 0, 0, false, 0),
            row(0x446bf060, "sqdmulh z0.h, z3.h, z3.h[5]", "SQDMULH", 1, 3, true, 5, 0, false, 0),
            row(0x44bff060, "sqdmulh z0.s, z3.s, z7.s[3]", "SQDMULH", 2, 7, true, 3, 0, false, 0),
            row(0x44acf060, "sqdmulh z0.s, z3.s, z4.s[1]", "SQDMULH", 2, 4, true, 1, 0, false, 0),
            row(0x44fff060, "sqdmulh z0.d, z3.d, z15.d[1]", "SQDMULH", 3, 15, true, 1, 0, false, 0),
            row(0x44e9f060, "sqdmulh z0.d, z3.d, z9.d[0]", "SQDMULH", 3, 9, true, 0, 0, false, 0),
            row(0x447ff460, "sqrdmulh z0.h, z3.h, z7.h[7]", "SQRDMULH", 1, 7, true, 7, 0, false, 0),
            row(0x4420f460, "sqrdmulh z0.h, z3.h, z0.h[0]", "SQRDMULH", 1, 0, true, 0, 0, false, 0),
            row(0x446bf460, "sqrdmulh z0.h, z3.h, z3.h[5]", "SQRDMULH", 1, 3, true, 5, 0, false, 0),
            row(0x44bff460, "sqrdmulh z0.s, z3.s, z7.s[3]", "SQRDMULH", 2, 7, true, 3, 0, false, 0),
            row(0x44acf460, "sqrdmulh z0.s, z3.s, z4.s[1]", "SQRDMULH", 2, 4, true, 1, 0, false, 0),
            row(0x44fff460, "sqrdmulh z0.d, z3.d, z15.d[1]", "SQRDMULH", 3, 15, true, 1, 0, false, 0),
            row(0x44e9f460, "sqrdmulh z0.d, z3.d, z9.d[0]", "SQRDMULH", 3, 9, true, 0, 0, false, 0),
            row(0x447ff860, "mul z0.h, z3.h, z7.h[7]", "MUL", 1, 7, true, 7, 0, false, 0),
            row(0x4420f860, "mul z0.h, z3.h, z0.h[0]", "MUL", 1, 0, true, 0, 0, false, 0),
            row(0x446bf860, "mul z0.h, z3.h, z3.h[5]", "MUL", 1, 3, true, 5, 0, false, 0),
            row(0x44bff860, "mul z0.s, z3.s, z7.s[3]", "MUL", 2, 7, true, 3, 0, false, 0),
            row(0x44acf860, "mul z0.s, z3.s, z4.s[1]", "MUL", 2, 4, true, 1, 0, false, 0),
            row(0x44fff860, "mul z0.d, z3.d, z15.d[1]", "MUL", 3, 15, true, 1, 0, false, 0),
            row(0x44e9f860, "mul z0.d, z3.d, z9.d[0]", "MUL", 3, 9, true, 0, 0, false, 0),
            row(0x44b72860, "sqdmlalb z0.s, z3.h, z7.h[5]", "SQDMLAL", 2, 7, true, 5, 0, false, 0),
            row(0x44a22060, "sqdmlalb z0.s, z3.h, z2.h[0]", "SQDMLAL", 2, 2, true, 0, 0, false, 0),
            row(0x44ff2860, "sqdmlalb z0.d, z3.s, z15.s[3]", "SQDMLAL", 3, 15, true, 3, 0, false, 0),
            row(0x44f92060, "sqdmlalb z0.d, z3.s, z9.s[2]", "SQDMLAL", 3, 9, true, 2, 0, false, 0),
            row(0x44b72c60, "sqdmlalt z0.s, z3.h, z7.h[5]", "SQDMLAL", 2, 7, true, 5, 0, true, 0),
            row(0x44a22460, "sqdmlalt z0.s, z3.h, z2.h[0]", "SQDMLAL", 2, 2, true, 0, 0, true, 0),
            row(0x44ff2c60, "sqdmlalt z0.d, z3.s, z15.s[3]", "SQDMLAL", 3, 15, true, 3, 0, true, 0),
            row(0x44f92460, "sqdmlalt z0.d, z3.s, z9.s[2]", "SQDMLAL", 3, 9, true, 2, 0, true, 0),
            row(0x44b73860, "sqdmlslb z0.s, z3.h, z7.h[5]", "SQDMLSL", 2, 7, true, 5, 0, false, 0),
            row(0x44a23060, "sqdmlslb z0.s, z3.h, z2.h[0]", "SQDMLSL", 2, 2, true, 0, 0, false, 0),
            row(0x44ff3860, "sqdmlslb z0.d, z3.s, z15.s[3]", "SQDMLSL", 3, 15, true, 3, 0, false, 0),
            row(0x44f93060, "sqdmlslb z0.d, z3.s, z9.s[2]", "SQDMLSL", 3, 9, true, 2, 0, false, 0),
            row(0x44b73c60, "sqdmlslt z0.s, z3.h, z7.h[5]", "SQDMLSL", 2, 7, true, 5, 0, true, 0),
            row(0x44a23460, "sqdmlslt z0.s, z3.h, z2.h[0]", "SQDMLSL", 2, 2, true, 0, 0, true, 0),
            row(0x44ff3c60, "sqdmlslt z0.d, z3.s, z15.s[3]", "SQDMLSL", 3, 15, true, 3, 0, true, 0),
            row(0x44f93460, "sqdmlslt z0.d, z3.s, z9.s[2]", "SQDMLSL", 3, 9, true, 2, 0, true, 0),
            row(0x44b78860, "smlalb z0.s, z3.h, z7.h[5]", "SMLAL", 2, 7, true, 5, 0, false, 0),
            row(0x44a28060, "smlalb z0.s, z3.h, z2.h[0]", "SMLAL", 2, 2, true, 0, 0, false, 0),
            row(0x44ff8860, "smlalb z0.d, z3.s, z15.s[3]", "SMLAL", 3, 15, true, 3, 0, false, 0),
            row(0x44f98060, "smlalb z0.d, z3.s, z9.s[2]", "SMLAL", 3, 9, true, 2, 0, false, 0),
            row(0x44b78c60, "smlalt z0.s, z3.h, z7.h[5]", "SMLAL", 2, 7, true, 5, 0, true, 0),
            row(0x44a28460, "smlalt z0.s, z3.h, z2.h[0]", "SMLAL", 2, 2, true, 0, 0, true, 0),
            row(0x44ff8c60, "smlalt z0.d, z3.s, z15.s[3]", "SMLAL", 3, 15, true, 3, 0, true, 0),
            row(0x44f98460, "smlalt z0.d, z3.s, z9.s[2]", "SMLAL", 3, 9, true, 2, 0, true, 0),
            row(0x44b79860, "umlalb z0.s, z3.h, z7.h[5]", "UMLAL", 2, 7, true, 5, 0, false, 0),
            row(0x44a29060, "umlalb z0.s, z3.h, z2.h[0]", "UMLAL", 2, 2, true, 0, 0, false, 0),
            row(0x44ff9860, "umlalb z0.d, z3.s, z15.s[3]", "UMLAL", 3, 15, true, 3, 0, false, 0),
            row(0x44f99060, "umlalb z0.d, z3.s, z9.s[2]", "UMLAL", 3, 9, true, 2, 0, false, 0),
            row(0x44b79c60, "umlalt z0.s, z3.h, z7.h[5]", "UMLAL", 2, 7, true, 5, 0, true, 0),
            row(0x44a29460, "umlalt z0.s, z3.h, z2.h[0]", "UMLAL", 2, 2, true, 0, 0, true, 0),
            row(0x44ff9c60, "umlalt z0.d, z3.s, z15.s[3]", "UMLAL", 3, 15, true, 3, 0, true, 0),
            row(0x44f99460, "umlalt z0.d, z3.s, z9.s[2]", "UMLAL", 3, 9, true, 2, 0, true, 0),
            row(0x44b7a860, "smlslb z0.s, z3.h, z7.h[5]", "SMLSL", 2, 7, true, 5, 0, false, 0),
            row(0x44a2a060, "smlslb z0.s, z3.h, z2.h[0]", "SMLSL", 2, 2, true, 0, 0, false, 0),
            row(0x44ffa860, "smlslb z0.d, z3.s, z15.s[3]", "SMLSL", 3, 15, true, 3, 0, false, 0),
            row(0x44f9a060, "smlslb z0.d, z3.s, z9.s[2]", "SMLSL", 3, 9, true, 2, 0, false, 0),
            row(0x44b7ac60, "smlslt z0.s, z3.h, z7.h[5]", "SMLSL", 2, 7, true, 5, 0, true, 0),
            row(0x44a2a460, "smlslt z0.s, z3.h, z2.h[0]", "SMLSL", 2, 2, true, 0, 0, true, 0),
            row(0x44ffac60, "smlslt z0.d, z3.s, z15.s[3]", "SMLSL", 3, 15, true, 3, 0, true, 0),
            row(0x44f9a460, "smlslt z0.d, z3.s, z9.s[2]", "SMLSL", 3, 9, true, 2, 0, true, 0),
            row(0x44b7b860, "umlslb z0.s, z3.h, z7.h[5]", "UMLSL", 2, 7, true, 5, 0, false, 0),
            row(0x44a2b060, "umlslb z0.s, z3.h, z2.h[0]", "UMLSL", 2, 2, true, 0, 0, false, 0),
            row(0x44ffb860, "umlslb z0.d, z3.s, z15.s[3]", "UMLSL", 3, 15, true, 3, 0, false, 0),
            row(0x44f9b060, "umlslb z0.d, z3.s, z9.s[2]", "UMLSL", 3, 9, true, 2, 0, false, 0),
            row(0x44b7bc60, "umlslt z0.s, z3.h, z7.h[5]", "UMLSL", 2, 7, true, 5, 0, true, 0),
            row(0x44a2b460, "umlslt z0.s, z3.h, z2.h[0]", "UMLSL", 2, 2, true, 0, 0, true, 0),
            row(0x44ffbc60, "umlslt z0.d, z3.s, z15.s[3]", "UMLSL", 3, 15, true, 3, 0, true, 0),
            row(0x44f9b460, "umlslt z0.d, z3.s, z9.s[2]", "UMLSL", 3, 9, true, 2, 0, true, 0),
            row(0x44bfc060, "smullb z0.s, z3.h, z7.h[6]", "SMULL", 2, 7, true, 6, 0, false, 0),
            row(0x44f9c860, "smullb z0.d, z3.s, z9.s[3]", "SMULL", 3, 9, true, 3, 0, false, 0),
            row(0x44efc860, "smullb z0.d, z3.s, z15.s[1]", "SMULL", 3, 15, true, 1, 0, false, 0),
            row(0x44bfc460, "smullt z0.s, z3.h, z7.h[6]", "SMULL", 2, 7, true, 6, 0, true, 0),
            row(0x44f9cc60, "smullt z0.d, z3.s, z9.s[3]", "SMULL", 3, 9, true, 3, 0, true, 0),
            row(0x44efcc60, "smullt z0.d, z3.s, z15.s[1]", "SMULL", 3, 15, true, 1, 0, true, 0),
            row(0x44bfd060, "umullb z0.s, z3.h, z7.h[6]", "UMULL", 2, 7, true, 6, 0, false, 0),
            row(0x44f9d860, "umullb z0.d, z3.s, z9.s[3]", "UMULL", 3, 9, true, 3, 0, false, 0),
            row(0x44efd860, "umullb z0.d, z3.s, z15.s[1]", "UMULL", 3, 15, true, 1, 0, false, 0),
            row(0x44bfd460, "umullt z0.s, z3.h, z7.h[6]", "UMULL", 2, 7, true, 6, 0, true, 0),
            row(0x44f9dc60, "umullt z0.d, z3.s, z9.s[3]", "UMULL", 3, 9, true, 3, 0, true, 0),
            row(0x44efdc60, "umullt z0.d, z3.s, z15.s[1]", "UMULL", 3, 15, true, 1, 0, true, 0),
            row(0x44bfe060, "sqdmullb z0.s, z3.h, z7.h[6]", "SQDMULL", 2, 7, true, 6, 0, false, 0),
            row(0x44f9e860, "sqdmullb z0.d, z3.s, z9.s[3]", "SQDMULL", 3, 9, true, 3, 0, false, 0),
            row(0x44efe860, "sqdmullb z0.d, z3.s, z15.s[1]", "SQDMULL", 3, 15, true, 1, 0, false, 0),
            row(0x44bfe460, "sqdmullt z0.s, z3.h, z7.h[6]", "SQDMULL", 2, 7, true, 6, 0, true, 0),
            row(0x44f9ec60, "sqdmullt z0.d, z3.s, z9.s[3]", "SQDMULL", 3, 9, true, 3, 0, true, 0),
            row(0x44efec60, "sqdmullt z0.d, z3.s, z15.s[1]", "SQDMULL", 3, 15, true, 1, 0, true, 0),
            row(0x44bf6060, "cmla z0.h, z3.h, z7.h[3], #0", "CMLA", 1, 7, true, 3, 0, false, 0),
            row(0x44f96060, "cmla z0.s, z3.s, z9.s[1], #0", "CMLA", 2, 9, true, 1, 0, false, 0),
            row(0x44bf6460, "cmla z0.h, z3.h, z7.h[3], #90", "CMLA", 1, 7, true, 3, 1, false, 0),
            row(0x44f96460, "cmla z0.s, z3.s, z9.s[1], #90", "CMLA", 2, 9, true, 1, 1, false, 0),
            row(0x44bf6860, "cmla z0.h, z3.h, z7.h[3], #180", "CMLA", 1, 7, true, 3, 2, false, 0),
            row(0x44f96860, "cmla z0.s, z3.s, z9.s[1], #180", "CMLA", 2, 9, true, 1, 2, false, 0),
            row(0x44bf6c60, "cmla z0.h, z3.h, z7.h[3], #270", "CMLA", 1, 7, true, 3, 3, false, 0),
            row(0x44f96c60, "cmla z0.s, z3.s, z9.s[1], #270", "CMLA", 2, 9, true, 1, 3, false, 0),
            row(0x44a16460, "cmla z0.h, z3.h, z1.h[0], #90", "CMLA", 1, 1, true, 0, 1, false, 0),
            row(0x44ef6860, "cmla z0.s, z3.s, z15.s[0], #180", "CMLA", 2, 15, true, 0, 2, false, 0),
            row(0x44bf7060, "sqrdcmlah z0.h, z3.h, z7.h[3], #0", "SQRDCMLAH", 1, 7, true, 3, 0, false, 0),
            row(0x44f97060, "sqrdcmlah z0.s, z3.s, z9.s[1], #0", "SQRDCMLAH", 2, 9, true, 1, 0, false, 0),
            row(0x44bf7460, "sqrdcmlah z0.h, z3.h, z7.h[3], #90", "SQRDCMLAH", 1, 7, true, 3, 1, false, 0),
            row(0x44f97460, "sqrdcmlah z0.s, z3.s, z9.s[1], #90", "SQRDCMLAH", 2, 9, true, 1, 1, false, 0),
            row(0x44bf7860, "sqrdcmlah z0.h, z3.h, z7.h[3], #180", "SQRDCMLAH", 1, 7, true, 3, 2, false, 0),
            row(0x44f97860, "sqrdcmlah z0.s, z3.s, z9.s[1], #180", "SQRDCMLAH", 2, 9, true, 1, 2, false, 0),
            row(0x44bf7c60, "sqrdcmlah z0.h, z3.h, z7.h[3], #270", "SQRDCMLAH", 1, 7, true, 3, 3, false, 0),
            row(0x44f97c60, "sqrdcmlah z0.s, z3.s, z9.s[1], #270", "SQRDCMLAH", 2, 9, true, 1, 3, false, 0),
            row(0x44a17460, "sqrdcmlah z0.h, z3.h, z1.h[0], #90", "SQRDCMLAH", 1, 1, true, 0, 1, false, 0),
            row(0x44ef7860, "sqrdcmlah z0.s, z3.s, z15.s[0], #180", "SQRDCMLAH", 2, 15, true, 0, 2, false, 0),
                null).filter(Objects::nonNull);
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

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static boolean refused(Aarch64Architecture architecture, int word) {
        try {
            decode(architecture, word);
            return false;
        } catch (UnsupportedOperationException refusal) {
            return true;
        }
    }

    private static long[] vector(Aarch64Core core, int reg) {
        long[] words = new long[core.scalable().wordsPerVector()];
        for (int w = 0; w < words.length; w++) {
            words[w] = core.scalable().zWord(reg, w);
        }
        return words;
    }

    private static void fillRandom(Aarch64Core core, int reg, Random random) {
        long[] patterns = {0L, -1L, 0x8000_8000_8000_8000L, 0x8000_0000_8000_0000L, Long.MIN_VALUE,
                0x7FFF_7FFF_7FFF_7FFFL, 0x8080_8080_8080_8080L};
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w,
                    random.nextInt(3) == 0 ? patterns[random.nextInt(patterns.length)] : random.nextLong());
        }
    }

    /// Elemento `index` (de `bits` bits) de um vetor, sem sinal.
    private static BigInteger unsignedElement(long[] vector, int index, int bits) {
        int offset = index * bits;
        long shifted = vector[offset / 64] >>> (offset % 64);
        long field = bits == 64 ? shifted : shifted & ((1L << bits) - 1);
        return new BigInteger(Long.toUnsignedString(field));
    }

    private static BigInteger signedElement(long[] vector, int index, int bits) {
        BigInteger value = unsignedElement(vector, index, bits);
        return value.testBit(bits - 1) ? value.subtract(BigInteger.ONE.shiftLeft(bits)) : value;
    }

    private static BigInteger saturate(BigInteger value, int bits) {
        BigInteger max = BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE);
        return value.max(max.negate().subtract(BigInteger.ONE)).min(max);
    }

    /// `((acc << bits) + 2*product + round) >> bits` saturado: a definição do manual de `SQRDMLAH` e irmãs.
    private static BigInteger doublingHigh(BigInteger acc, BigInteger product, boolean round, int bits) {
        BigInteger total = acc.shiftLeft(bits).add(product.shiftLeft(1));
        if (round) {
            total = total.add(BigInteger.ONE.shiftLeft(bits - 1));
        }
        return saturate(total.shiftRight(bits), bits);
    }

    private static long truncate(BigInteger value, int bits) {
        return value.and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)).longValue();
    }

    /// Elemento `i` esperado de `Zd`, dadas as três fontes ANTES da execução.
    private static long expectedElement(Row row, int i, long[] n, long[] m, long[] a) {
        int bits = 8 << row.esz();
        int perSegment = SEGMENT_BITS / bits;
        int segment = i / perSegment;
        BigInteger acc = unsignedElement(a, i, bits);
        BigInteger signedAcc = signedElement(a, i, bits);
        return switch (row.op()) {
            case SDOT, UDOT, USDOT, SUDOT -> dot(row, i, n, m, acc, bits, perSegment, segment);
            case CDOT -> complexDot(row, i, n, m, acc, bits, perSegment, segment);
            case MLA, MLS, MUL -> {
                BigInteger product = unsignedElement(n, i, bits)
                        .multiply(unsignedElement(m, segment * perSegment + row.index(), bits));
                yield truncate(switch (row.op()) {
                    case MLA -> acc.add(product);
                    case MLS -> acc.subtract(product);
                    default -> product;
                }, bits);
            }
            case SQDMULH, SQRDMULH, SQRDMLAH, SQRDMLSH -> {
                BigInteger product = signedElement(n, i, bits)
                        .multiply(signedElement(m, segment * perSegment + row.index(), bits));
                yield truncate(switch (row.op()) {
                    case SQDMULH -> doublingHigh(BigInteger.ZERO, product, false, bits);
                    case SQRDMULH -> doublingHigh(BigInteger.ZERO, product, true, bits);
                    case SQRDMLAH -> doublingHigh(signedAcc, product, true, bits);
                    default -> doublingHigh(signedAcc, product.negate(), true, bits);
                }, bits);
            }
            case CMLA, SQRDCMLAH -> complexMultiplyAdd(row, i, n, m, a, bits, perSegment, segment);
            default -> widening(row, i, n, m, acc, signedAcc, bits, perSegment, segment);
        };
    }

    private static long dot(Row row, int i, long[] n, long[] m, BigInteger acc, int bits, int perSegment,
            int segment) {
        int ways = row.ways();
        int sourceBits = bits / ways;
        boolean signedN = row.op() == Ir64Op.SveMultiplyIndexed.Op.SDOT
                || row.op() == Ir64Op.SveMultiplyIndexed.Op.SUDOT;
        boolean signedM = row.op() == Ir64Op.SveMultiplyIndexed.Op.SDOT
                || row.op() == Ir64Op.SveMultiplyIndexed.Op.USDOT;
        BigInteger sum = acc;
        for (int k = 0; k < ways; k++) {
            int mIndex = row.indexed() ? segment * perSegment * ways + row.index() * ways + k : i * ways + k;
            BigInteger nn = signedN ? signedElement(n, i * ways + k, sourceBits)
                    : unsignedElement(n, i * ways + k, sourceBits);
            BigInteger mm = signedM ? signedElement(m, mIndex, sourceBits) : unsignedElement(m, mIndex, sourceBits);
            sum = sum.add(nn.multiply(mm));
        }
        return truncate(sum, bits);
    }

    /// `CDOT`: cada elemento acumula 2 produtos complexos `n · m` (rot 0/1 = parte real/imaginária de `n·m`;
    /// rot 2/3 = parte real/imaginária de `n·conj(m)`).
    private static long complexDot(Row row, int i, long[] n, long[] m, BigInteger acc, int bits, int perSegment,
            int segment) {
        int sourceBits = bits / 4;
        int mBase = (row.indexed() ? segment * perSegment + row.index() : i) * 4;
        BigInteger sum = acc;
        for (int pair = 0; pair < 2; pair++) {
            BigInteger nRe = signedElement(n, i * 4 + pair * 2, sourceBits);
            BigInteger nIm = signedElement(n, i * 4 + pair * 2 + 1, sourceBits);
            BigInteger mRe = signedElement(m, mBase + pair * 2, sourceBits);
            BigInteger mIm = signedElement(m, mBase + pair * 2 + 1, sourceBits);
            sum = sum.add(switch (row.rot()) {
                case 0 -> nRe.multiply(mRe).subtract(nIm.multiply(mIm));
                case 1 -> nRe.multiply(mIm).add(nIm.multiply(mRe));
                case 2 -> nRe.multiply(mRe).add(nIm.multiply(mIm));
                default -> nRe.multiply(mIm).subtract(nIm.multiply(mRe));
            });
        }
        return truncate(sum, bits);
    }

    private static long complexMultiplyAdd(Row row, int i, long[] n, long[] m, long[] a, int bits, int perSegment,
            int segment) {
        int realIndex = i - (i % 2);
        BigInteger nRe = signedElement(n, realIndex, bits);
        BigInteger nIm = signedElement(n, realIndex + 1, bits);
        int mBase = segment * perSegment + 2 * row.index();
        BigInteger mRe = signedElement(m, mBase, bits);
        BigInteger mIm = signedElement(m, mBase + 1, bits);
        boolean imaginary = i % 2 == 1;
        BigInteger x;
        BigInteger y;
        boolean negative;
        switch (row.rot()) {
            case 0 -> {
                x = nRe;
                y = imaginary ? mIm : mRe;
                negative = false;
            }
            case 1 -> {
                x = nIm;
                y = imaginary ? mRe : mIm;
                negative = !imaginary;
            }
            case 2 -> {
                x = nRe;
                y = imaginary ? mIm : mRe;
                negative = true;
            }
            default -> {
                x = nIm;
                y = imaginary ? mRe : mIm;
                negative = imaginary;
            }
        }
        BigInteger product = negative ? x.multiply(y).negate() : x.multiply(y);
        if (row.op() == Ir64Op.SveMultiplyIndexed.Op.SQRDCMLAH) {
            return truncate(doublingHigh(signedElement(a, i, bits), product, true, bits), bits);
        }
        return truncate(unsignedElement(a, i, bits).add(product), bits);
    }

    private static long widening(Row row, int i, long[] n, long[] m, BigInteger acc, BigInteger signedAcc, int bits,
            int perSegment, int segment) {
        int narrow = bits / 2;
        boolean unsigned = row.op() == Ir64Op.SveMultiplyIndexed.Op.UMLAL
                || row.op() == Ir64Op.SveMultiplyIndexed.Op.UMLSL || row.op() == Ir64Op.SveMultiplyIndexed.Op.UMULL;
        int nIndex = 2 * i + (row.top() ? 1 : 0);
        int mIndex = segment * perSegment * 2 + row.index();
        BigInteger nn = unsigned ? unsignedElement(n, nIndex, narrow) : signedElement(n, nIndex, narrow);
        BigInteger mm = unsigned ? unsignedElement(m, mIndex, narrow) : signedElement(m, mIndex, narrow);
        BigInteger product = nn.multiply(mm);
        BigInteger doubled = saturate(product.shiftLeft(1), bits);
        return truncate(switch (row.op()) {
            case SMLAL, UMLAL -> acc.add(product);
            case SMLSL, UMLSL -> acc.subtract(product);
            case SMULL, UMULL -> product;
            case SQDMULL -> doubled;
            case SQDMLAL -> saturate(signedAcc.add(doubled), bits);
            default -> saturate(signedAcc.subtract(doubled), bits); // SQDMLSL
        }, bits);
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void decodesEveryAssembledWordToTheFieldsOfItsText(Row row) {
        assertEquals(row.expectedOp(), decode(FULL, row.word()), row.asm());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyRowNeedsExactlyItsFeatureAndNothingLess(Row row) {
        assertEquals(row.expectedOp(), decode(row.minimum(), row.word()), row.asm());
        if (row.refusing() != null) {
            assertTrue(refused(row.refusing(), row.word()), row.asm() + " deve ser recusada sem a feature");
        }
        assertTrue(refused(Aarch64Architecture.ARMV8_5_A, row.word()), "sem SVE nada disto decodifica");
    }

    @Test
    void theTwoWayDotAlsoDecodesUnderSve2p2BecauseItImpliesSve2p1() {
        Row twoWay = rows().filter(r -> r.ways() == 2).findFirst().orElseThrow();
        assertEquals(twoWay.expectedOp(), decode(SVE2P2, twoWay.word()));
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x44bf0060 & ~(3 << 22), // SDOT indexado com esz = 00: não existe
            0x44bf0060 & ~(1 << 23), // idem com esz = 01
            0x44a02060 & ~(1 << 23), // SQDMLALB com esz = 0x: alargante não existe em half
            0x44bf0060 | (0b0101 << 12), // família 0101: não alocada
            0x44bf0060 | (0b1111 << 12) | (0b11 << 10), // família 1111 com low = 11: não alocada
            0x44802060, // bit 21 = 0, família 0010: não é deste grupo
            0x44000060, // bit 21 = 0, bit 23 = 0, família 0000: não é dot vetorial
            0x44c0c860, // dot de 2 vias com esz = 11 (só existe .S)
            0x44800060 | (0b1100 << 12), // esz = 10, família 1100, bit 11 = 0: nenhum dot de 2 vias
            0x44206000, // CMLA com esz = 00: os complexos só existem em .H/.S (esz = 10/11)
            0x44606000, // CMLA com esz = 01
            0x44401060, // CDOT vetorial com esz = 01 (só .S/.D)
    })
    void refusesTheUnallocatedNeighbours(int word) {
        assertTrue(refused(FULL, word), () -> Integer.toHexString(word));
    }

    @Test
    void usdotAndSudotDoNotExistForDoubleDestinations() {
        Row usdot = rows().filter(r -> r.op() == Ir64Op.SveMultiplyIndexed.Op.USDOT).findFirst().orElseThrow();
        assertTrue(refused(FULL, usdot.word() | (1 << 22)));
    }

    // ── Execução ─────────────────────────────────────────────────────────────────────────────────

    /// `Z0` (o destino original), `Zn` (`Z3`) e `Zm`: o destino aliasado com cada fonte.
    private static int[] destinations(Row row) {
        return row.rm() == RN || row.rm() == RD ? new int[] {RD, RN} : new int[] {RD, RN, row.rm()};
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void executesEveryRowLikeTheOracleAtEveryVectorLengthEvenWhenDestinationAliasesASource(Row row) {
        int bits = 8 << row.esz();
        for (int vl : VECTOR_LENGTHS) {
            for (int destination : destinations(row)) {
                Random random = new Random(row.word() * 131L + vl * 7L + destination);
                for (int trial = 0; trial < 6; trial++) {
                    Aarch64Core core = core(FULL, vl);
                    for (int reg : new int[] {RD, RN, row.rm(), destination}) {
                        fillRandom(core, reg, random);
                    }
                    int word = (row.word() & ~REGISTER_FIELD_MASK) | destination;
                    long[] n = vector(core, RN);
                    long[] m = vector(core, row.rm());
                    long[] a = vector(core, destination);
                    run(FULL, core, word);
                    long[] result = vector(core, destination);
                    for (int i = 0; i < vl / bits; i++) {
                        assertEquals(expectedElement(row, i, n, m, a), truncate(unsignedElement(result, i, bits), bits),
                                row.asm() + " VL=" + vl + " Zd=z" + destination + " e" + i);
                    }
                    assertEquals(4L, core.pc());
                    if (destination != RN) {
                        assertEquals(Arrays.toString(n), Arrays.toString(vector(core, RN)), "Zn não muda");
                    }
                    if (destination != row.rm()) {
                        assertEquals(Arrays.toString(m), Arrays.toString(vector(core, row.rm())), "Zm não muda");
                    }
                }
            }
        }
    }

    @Test
    void theIndexIsPerSegmentNotPerVector() {
        // mul z0.s, z3.s, z7.s[1] em VL = 256: o segmento 1 usa o elemento 5 de Zm (= 4 + 1), não o 1.
        Row mul = rows().filter(r -> r.op() == Ir64Op.SveMultiplyIndexed.Op.MUL && r.esz() == 2).findFirst()
                .orElseThrow();
        Aarch64Core core = core(FULL, 256);
        for (int e = 0; e < 8; e++) {
            core.scalable().setZWord(RN, e / 2, core.scalable().zWord(RN, e / 2) | (1L << (32 * (e % 2))));
            core.scalable().setZWord(mul.rm(), e / 2,
                    core.scalable().zWord(mul.rm(), e / 2) | ((long) (10 + e) << (32 * (e % 2))));
        }
        run(FULL, core, mul.word());
        long[] out = vector(core, RD);
        for (int e = 0; e < 8; e++) {
            long expected = 10 + (e / 4) * 4 + mul.index();
            assertEquals(expected, unsignedElement(out, e, 32).longValue(), "elemento " + e);
        }
    }

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return 0L;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(Row row) {
        Aarch64Core core = core(FULL, 256);
        core.setSystemRegisterBus(new Cpacr());
        core.scalable().setZWord(RD, 0, 0x1234L);
        run(FULL, core, row.word());
        assertEquals(HANDLER, core.pc(), row.asm());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, core.scalable().zWord(RD, 0), "a instrução não executou");
    }

    @Test
    void theSameWordRunsThroughTheLifterAndTheBlockExecutor() {
        Row row = rows().findFirst().orElseThrow();
        Aarch64Core core = core(FULL, 256);
        core.memory().write32(0, row.word());
        Ir64Block block = new StandardIr64BlockLifter(FULL).lift(core.memory(), 0, 1);
        assertNotNull(block);
        new Ir64BlockExecutor(FULL).executeBlock(core, block);
        assertEquals(4L, core.pc());
    }
}
