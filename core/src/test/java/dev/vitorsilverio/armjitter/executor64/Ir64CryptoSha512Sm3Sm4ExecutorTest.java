package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha512Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `SHA512H`/`SHA512H2`/`SHA512SU0`/`SHA512SU1` (`FEAT_SHA512`), `SM3PARTW1`/
/// `SM3PARTW2`/`SM3SS1`/`SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B` (`FEAT_SM3`) e `SM4E`/`SM4EKEY`
/// (`FEAT_SM4`) direto no executor (interpretador = oráculo, G1) — B19.10, complementa
/// {@code Aarch64CryptoSha512Sm3Sm4DecoderTest} (decode).
///
/// Os valores esperados de `SHA512*`/`SM3*` vêm de uma reimplementação INDEPENDENTE (Node.js/
/// `BigInt`) das mesmas fórmulas públicas do `ARM DDI 0487`/`crypto_helper.c` do QEMU — não
/// round-trip interno nem cópia do Java sob teste (mesma disciplina de
/// {@code Ir64CryptoSha3ExecutorTest}/{@code Ir64CryptoShaExecutorTest}).
///
/// `SM4E`/`SM4EKEY` ganham, ALÉM disso, um vetor de teste do PRÓPRIO padrão: a chave/expansão/
/// cifra de exemplo publicada pelo `GB/T 32907-2016` (`chave=texto claro=0123456789abcdeffedcba
/// 9876543210`, `cifra=681edf34d206965e86b3e94f536e4246`) — 8 chamadas de `SM4EKEY` (expansão de
/// chave completa) seguidas de 8 chamadas de `SM4E` (as 32 rodadas completas de cifra) devem
/// reproduzir a cifra publicada byte a byte.
class Ir64CryptoSha512Sm3Sm4ExecutorTest {
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

    // ── SHA512H/SHA512H2/SHA512SU1/SHA512SU0 ────────────────────────────────────────────────────

    private static final long D0 = 0x0102030405060708L, D1 = 0x1112131415161718L;
    private static final long N0 = 0x2122232425262728L, N1 = 0x3132333435363738L;
    private static final long M0 = 0x4142434445464748L, M1 = 0x5152535455565758L;

    private static Aarch64Core coreWithSha512Operands() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, D0, D1);
        fp.setQ(1, N0, N1);
        fp.setQ(2, M0, M1);
        return core;
    }

    @Test
    void sha512h() {
        Aarch64Core core = coreWithSha512Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha512ThreeRegister(Ir64CryptoSha512Op.SHA512H, 0, 1, 2));
        assertEquals(0x2a9b107bded8b1dfL, core.fp().low64(0));
        assertEquals(0x94d2306eafe92765L, core.fp().high64(0));
    }

    @Test
    void sha512h2() {
        Aarch64Core core = coreWithSha512Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha512ThreeRegister(Ir64CryptoSha512Op.SHA512H2, 0, 1, 2));
        assertEquals(0xf18222aa79d532e2L, core.fp().low64(0));
        assertEquals(0x2013c030ef527e73L, core.fp().high64(0));
    }

    @Test
    void sha512su1() {
        Aarch64Core core = coreWithSha512Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha512ThreeRegister(Ir64CryptoSha512Op.SHA512SU1, 0, 1, 2));
        assertEquals(0x0fb4dbd2480d73c9L, core.fp().low64(0));
        assertEquals(0xb216bdb3a970562bL, core.fp().high64(0));
    }

    @Test
    void sha512su0() {
        Aarch64Core core = coreWithSha512Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSha512TwoRegister(0, 1));
        assertEquals(0x11bc42c33bba38bdL, core.fp().low64(0));
        assertEquals(0x4a048b0b94129115L, core.fp().high64(0));
    }

    // ── SM3PARTW1/SM3PARTW2/SM3SS1/SM3TT1A/1B/2A/2B ─────────────────────────────────────────────

    private static Aarch64Core coreWithSm3Operands() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, pack(1, 2), pack(3, 4));
        fp.setQ(1, pack(0x11111111, 0x22222222), pack(0x33333333, 0x44444444));
        fp.setQ(2, pack(0x55555555, 0x66666666), pack(0x77777777, 0x88888888));
        return core;
    }

    @Test
    void sm3partw1() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegister(Ir64CryptoSm3Op.PARTW1, 0, 1, 2));
        assertWords(core.fp(), 0, 0x22a2a223, 0x9898999b, 0x76f6f774, 0x7757f551);
    }

    @Test
    void sm3partw2() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegister(Ir64CryptoSm3Op.PARTW2, 0, 1, 2));
        assertWords(core.fp(), 0, 0xbbbbbbba, 0x11111113, 0x8888888b, 0xddddddd9);
    }

    @Test
    void sm3ss1() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Só a palavra ALTA (elemento 3) de cada operando participa da fórmula.
        fp.setQ(1, pack(0, 0), pack(0, 0x11111111));
        fp.setQ(2, pack(0, 0), pack(0, 0x55555555));
        fp.setQ(3, pack(0, 0), pack(0, 1));
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3FourRegister(0, 1, 2, 3));
        assertWords(core.fp(), 0, 0, 0, 0, 0x333333b3);
    }

    @Test
    void sm3tt1a() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT1A, 0, 1, 2, 0));
        assertWords(core.fp(), 0, 0x00000002, 0x00000600, 0x00000004, 0x9999599f);
    }

    @Test
    void sm3tt1b() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT1B, 0, 1, 2, 1));
        assertWords(core.fp(), 0, 0x00000002, 0x00000600, 0x00000004, 0xaaaa6aad);
    }

    @Test
    void sm3tt2a() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT2A, 0, 1, 2, 2));
        assertWords(core.fp(), 0, 0x00000002, 0x00180000, 0x00000004, 0xbb4f4fc1);
    }

    @Test
    void sm3tt2b() {
        Aarch64Core core = coreWithSm3Operands();
        EXECUTOR.executeOp(core, new Ir64Op.CryptoSm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT2B, 0, 1, 2, 3));
        assertWords(core.fp(), 0, 0x00000002, 0x00180000, 0x00000004, 0xcccacacf);
    }

    // ── SM4E/SM4EKEY: vetor de teste conhecido do próprio padrão (GB/T 32907-2016) ──────────────

    private static final int[] MASTER_KEY = {0x01234567, 0x89abcdef, 0xfedcba98, 0x76543210};
    private static final int[] PLAINTEXT = {0x01234567, 0x89abcdef, 0xfedcba98, 0x76543210};
    private static final int[] EXPECTED_CIPHERTEXT = {0x681edf34, 0xd206965e, 0x86b3e94f, 0x536e4246};
    private static final int[] FK = {0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc};
    private static final int[] CK = {
            0x00070e15, 0x1c232a31, 0x383f464d, 0x545b6269,
            0x70777e85, 0x8c939aa1, 0xa8afb6bd, 0xc4cbd2d9,
            0xe0e7eef5, 0xfc030a11, 0x181f262d, 0x343b4249,
            0x50575e65, 0x6c737a81, 0x888f969d, 0xa4abb2b9,
            0xc0c7ced5, 0xdce3eaf1, 0xf8ff060d, 0x141b2229,
            0x30373e45, 0x4c535a61, 0x686f767d, 0x848b9299,
            0xa0a7aeb5, 0xbcc3cad1, 0xd8dfe6ed, 0xf4fb0209,
            0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279,
    };

    @Test
    void sm4KnownAnswerTest() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();

        // Estado da chave inicial: K[0..3] = MK[0..3] XOR FK[0..3] (algoritmo padrão SM4, fora do
        // escopo das instruções — SM4EKEY assume que o software já fez este XOR).
        int[] keyState = new int[4];
        for (int i = 0; i < 4; i++) {
            keyState[i] = MASTER_KEY[i] ^ FK[i];
        }

        // 8 chamadas de SM4EKEY: cada uma expande 4 sub-chaves de rodada (32 no total).
        int[] roundKeys = new int[32];
        for (int round = 0; round < 8; round++) {
            fp.setQ(1, pack(keyState[0], keyState[1]), pack(keyState[2], keyState[3]));
            fp.setQ(2, pack(CK[round * 4], CK[round * 4 + 1]), pack(CK[round * 4 + 2], CK[round * 4 + 3]));
            EXECUTOR.executeOp(core, new Ir64Op.CryptoSm4KeyUpdate(0, 1, 2));
            keyState[0] = (int) fp.element(0, 0, 2);
            keyState[1] = (int) fp.element(0, 1, 2);
            keyState[2] = (int) fp.element(0, 2, 2);
            keyState[3] = (int) fp.element(0, 3, 2);
            System.arraycopy(keyState, 0, roundKeys, round * 4, 4);
        }
        // Sub-chave [0] publicada pelo padrão (`GB/T 32907-2016`, exemplo de expansão de chave):
        // `f12186f9`.
        assertEquals(0xf12186f9, roundKeys[0]);

        // 8 chamadas de SM4E: cada uma processa 4 rodadas de cifra usando 4 sub-chaves.
        int[] block = PLAINTEXT.clone();
        for (int round = 0; round < 8; round++) {
            fp.setQ(0, pack(block[0], block[1]), pack(block[2], block[3]));
            fp.setQ(1, pack(roundKeys[round * 4], roundKeys[round * 4 + 1]),
                    pack(roundKeys[round * 4 + 2], roundKeys[round * 4 + 3]));
            EXECUTOR.executeOp(core, new Ir64Op.CryptoSm4Encrypt(0, 1));
            block[0] = (int) fp.element(0, 0, 2);
            block[1] = (int) fp.element(0, 1, 2);
            block[2] = (int) fp.element(0, 2, 2);
            block[3] = (int) fp.element(0, 3, 2);
        }
        // O padrão SM4 lê o texto cifrado com as palavras em ordem REVERSA (`R` transform,
        // aplicado pelo software depois da última rodada, não pela instrução) — `SM4E` só executa
        // as rodadas de Feistel.
        assertEquals(EXPECTED_CIPHERTEXT[0], block[3]);
        assertEquals(EXPECTED_CIPHERTEXT[1], block[2]);
        assertEquals(EXPECTED_CIPHERTEXT[2], block[1]);
        assertEquals(EXPECTED_CIPHERTEXT[3], block[0]);
    }
}
