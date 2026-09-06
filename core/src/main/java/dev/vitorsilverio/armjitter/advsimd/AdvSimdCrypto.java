package dev.vitorsilverio.armjitter.advsimd;

/// Núcleo COMPARTILHADO (RFC B13.2, D1) das 7 instruções "two-register" da ARMv8-A Cryptographic
/// Extension: `AESE`/`AESD`/`AESMC`/`AESIMC` (B8.11) e `SHA1H`/`SHA1SU1`/`SHA256SU0` (B8.11b).
/// Migrado de {@link dev.vitorsilverio.armjitter.executor64.Ir64CryptoExecutor} na task B13.15, que
/// lhe dá o primeiro consumidor A32 — a semântica é IDÊNTICA, só a via de acesso ao registrador
/// muda ({@link AdvSimdRegisterWords}, não mais {@code Aarch64FpRegisters} direto). As formas de
/// TRÊS registradores (`SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/`SHA256SU1`) e o resto
/// da Cryptographic Extension (`SHA3`/`SHA-512`/`SM3`/`SM4`, B11.12/B19.10) NÃO têm encoding A32 —
/// continuam só no `Ir64CryptoExecutor`.
///
/// A tabela S-box e as matrizes `MixColumns`/`InvMixColumns` NÃO são copiadas de nenhuma fonte —
/// são DERIVADAS matematicamente a partir da definição pública do AES (FIPS PUB 197, um padrão
/// aberto, não GPL), mesma técnica de `Ir64CryptoExecutor` antes desta migração.
public final class AdvSimdCrypto {
    private AdvSimdCrypto() {
    }

    /// Polinômio irredutível do `GF(2^8)` usado pelo AES (`x^8+x^4+x^3+x+1`).
    private static final int AES_GF_MODULUS = 0x11B;
    /// `log2` do tamanho de elemento "palavra" (32 bits) na convenção de {@link AdvSimdLanes#element}.
    private static final int WORD_ESZ = 2;
    /// `log2` do tamanho de elemento "byte" (8 bits).
    private static final int BYTE_ESZ = 0;
    /// Bytes/palavras num registrador `Q` (128 bits).
    private static final int BYTES_PER_Q = 16;
    private static final int WORDS_PER_Q = 4;

    private static final byte[] S_BOX = new byte[256];
    private static final byte[] INV_S_BOX = new byte[256];

    static {
        int[] inverse = new int[256];
        inverse[0] = 0; // 0 não tem inverso multiplicativo em GF(2^8); AES define S-box(0)=affine(0).
        for (int x = 1; x < 256; x++) {
            for (int y = 1; y < 256; y++) {
                if (gfMultiply(x, y) == 1) {
                    inverse[x] = y;
                    break;
                }
            }
        }
        for (int x = 0; x < 256; x++) {
            int inv = inverse[x];
            // Transformação afim (FIPS 197 §5.1.1): b'_i = b_i ^ b_(i+4) ^ b_(i+5) ^ b_(i+6) ^
            // b_(i+7) (índices mod 8) ^ c_i, c=0x63.
            int result = 0;
            for (int bit = 0; bit < 8; bit++) {
                int b = ((inv >>> bit) & 1)
                        ^ ((inv >>> ((bit + 4) % 8)) & 1)
                        ^ ((inv >>> ((bit + 5) % 8)) & 1)
                        ^ ((inv >>> ((bit + 6) % 8)) & 1)
                        ^ ((inv >>> ((bit + 7) % 8)) & 1)
                        ^ ((0x63 >>> bit) & 1);
                result |= b << bit;
            }
            S_BOX[x] = (byte) result;
            INV_S_BOX[result] = (byte) x;
        }
    }

    /// Multiplicação em `GF(2^8)` módulo {@link #AES_GF_MODULUS} (`xtime` repetido, "peasant
    /// multiplication" — mesma técnica didática do FIPS 197 §4.2.1).
    private static int gfMultiply(int a, int b) {
        int result = 0;
        int x = a;
        int y = b;
        for (int i = 0; i < 8; i++) {
            if ((y & 1) != 0) {
                result ^= x;
            }
            boolean carry = (x & 0x80) != 0;
            x = (x << 1) & 0xFF;
            if (carry) {
                x ^= AES_GF_MODULUS & 0xFF;
            }
            y >>>= 1;
        }
        return result;
    }

    /// `AESE`/`AESD`/`AESMC`/`AESIMC` — `baseRd`/`baseRn` são índices de PALAVRA (ver
    /// {@link AdvSimdRegisterWords}), sempre um registrador `Q` completo (128 bits/16 bytes).
    public static void aes(AdvSimdRegisterWords regs, AdvSimdCryptoAesOp op, int baseRd, int baseRn) {
        byte[] state = new byte[BYTES_PER_Q];
        switch (op) {
            case AESE -> {
                for (int i = 0; i < BYTES_PER_Q; i++) {
                    state[i] = (byte) (AdvSimdLanes.element(regs, baseRd, i, BYTE_ESZ)
                            ^ AdvSimdLanes.element(regs, baseRn, i, BYTE_ESZ));
                }
                subBytes(state, S_BOX);
                shiftRows(state, false);
            }
            case AESD -> {
                for (int i = 0; i < BYTES_PER_Q; i++) {
                    state[i] = (byte) (AdvSimdLanes.element(regs, baseRd, i, BYTE_ESZ)
                            ^ AdvSimdLanes.element(regs, baseRn, i, BYTE_ESZ));
                }
                subBytes(state, INV_S_BOX);
                shiftRows(state, true);
            }
            case AESMC -> {
                for (int i = 0; i < BYTES_PER_Q; i++) {
                    state[i] = (byte) AdvSimdLanes.element(regs, baseRn, i, BYTE_ESZ);
                }
                mixColumns(state, false);
            }
            case AESIMC -> {
                for (int i = 0; i < BYTES_PER_Q; i++) {
                    state[i] = (byte) AdvSimdLanes.element(regs, baseRn, i, BYTE_ESZ);
                }
                mixColumns(state, true);
            }
        }
        for (int i = 0; i < BYTES_PER_Q; i++) {
            AdvSimdLanes.setElement(regs, baseRd, i, BYTE_ESZ, state[i] & 0xFFL);
        }
    }

    /// `Σ0`/`Σ1`/`σ0`/`σ1` do SHA256 (FIPS PUB 180-4 §4.1.2) — só {@code σ0} é usado por
    /// {@link #shaTwoRegister} ({@code SHA256SU0}); as outras três moram só em
    /// {@code Ir64CryptoExecutor} (formas de três registradores, sem encoding A32).
    private static int sha256SmallSigma0(int x) {
        return Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
    }

    private static int[] readWords(AdvSimdRegisterWords regs, int base) {
        int[] words = new int[WORDS_PER_Q];
        for (int i = 0; i < WORDS_PER_Q; i++) {
            words[i] = (int) AdvSimdLanes.element(regs, base, i, WORD_ESZ);
        }
        return words;
    }

    private static void writeWords(AdvSimdRegisterWords regs, int base, int[] words) {
        for (int i = 0; i < WORDS_PER_Q; i++) {
            AdvSimdLanes.setElement(regs, base, i, WORD_ESZ, words[i] & 0xFFFF_FFFFL);
        }
    }

    /// `SHA1H`/`SHA1SU1`/`SHA256SU0` — `baseRd`/`baseRn` são índices de PALAVRA, sempre um
    /// registrador `Q` completo.
    public static void shaTwoRegister(AdvSimdRegisterWords regs, AdvSimdCryptoShaOp op, int baseRd, int baseRn) {
        switch (op) {
            case SHA1H -> {
                int m0 = (int) AdvSimdLanes.element(regs, baseRn, 0, WORD_ESZ);
                writeWords(regs, baseRd, new int[] {Integer.rotateRight(m0, 2), 0, 0, 0});
            }
            case SHA1SU1 -> {
                int[] d = readWords(regs, baseRd);
                int[] m = readWords(regs, baseRn);
                int d0 = Integer.rotateLeft(d[0] ^ m[1], 1);
                int d1 = Integer.rotateLeft(d[1] ^ m[2], 1);
                int d2 = Integer.rotateLeft(d[2] ^ m[3], 1);
                int d3 = Integer.rotateLeft(d[3] ^ d0, 1);
                writeWords(regs, baseRd, new int[] {d0, d1, d2, d3});
            }
            case SHA256SU0 -> {
                int[] d = readWords(regs, baseRd);
                int[] m = readWords(regs, baseRn);
                d[0] += sha256SmallSigma0(d[1]);
                d[1] += sha256SmallSigma0(d[2]);
                d[2] += sha256SmallSigma0(d[3]);
                d[3] += sha256SmallSigma0(m[0]);
                writeWords(regs, baseRd, d);
            }
        }
    }

    private static void subBytes(byte[] state, byte[] table) {
        for (int i = 0; i < BYTES_PER_Q; i++) {
            state[i] = table[state[i] & 0xFF];
        }
    }

    /// `ShiftRows`/`InvShiftRows` — a linha `r` (`0`-`3`) é deslocada `r` posições à esquerda
    /// (`ShiftRows`) ou à direita (`InvShiftRows`); estado organizado por COLUNA
    /// (`state[r][c] = bytes[r + 4*c]`, convenção padrão do AES).
    private static void shiftRows(byte[] state, boolean inverse) {
        byte[] copy = state.clone();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int srcCol = inverse ? (col - row + 4) % 4 : (col + row) % 4;
                state[row + 4 * col] = copy[row + 4 * srcCol];
            }
        }
    }

    /// `MixColumns`/`InvMixColumns` — cada coluna de 4 bytes é multiplicada pela matriz `GF(2^8)`
    /// padrão do AES (FIPS 197 §5.1.3/§5.3.3).
    private static void mixColumns(byte[] state, boolean inverse) {
        int[][] matrix = inverse
                ? new int[][] {{14, 11, 13, 9}, {9, 14, 11, 13}, {13, 9, 14, 11}, {11, 13, 9, 14}}
                : new int[][] {{2, 3, 1, 1}, {1, 2, 3, 1}, {1, 1, 2, 3}, {3, 1, 1, 2}};
        for (int col = 0; col < 4; col++) {
            int a0 = state[4 * col] & 0xFF;
            int a1 = state[4 * col + 1] & 0xFF;
            int a2 = state[4 * col + 2] & 0xFF;
            int a3 = state[4 * col + 3] & 0xFF;
            int[] a = {a0, a1, a2, a3};
            for (int row = 0; row < 4; row++) {
                int sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum ^= gfMultiply(matrix[row][k], a[k]);
                }
                state[4 * col + row] = (byte) sum;
            }
        }
    }
}
