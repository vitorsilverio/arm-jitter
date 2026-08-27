package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Executa `AESE`/`AESD`/`AESMC`/`AESIMC` (B8.11, ARMv8-A Cryptographic Extension). Sibling de
/// {@link Ir64VectorArithmeticExecutor}: sem estado próprio, métodos estáticos. É o oráculo
/// semântico (G1) — `CRYPTO_AES` não entra em `Ir64NativePolicy`, mesma decisão de todo `Kind`
/// novo desde B8.4/B8.6.
///
/// A tabela S-box e as matrizes `MixColumns`/`InvMixColumns` NÃO são copiadas de nenhuma fonte —
/// são DERIVADAS matematicamente a partir da definição pública do AES (FIPS PUB 197, um padrão
/// aberto, não GPL): inverso multiplicativo em `GF(2^8)` (módulo `x^8+x^4+x^3+x+1`, `0x11B`)
/// seguido da transformação afim (§5.1.1 do FIPS 197). Isso evita transcrever à mão uma tabela de
/// 256 bytes (risco de erro) e é auto-verificável (o `S-box`/`InvS-box` gerados aqui batem com
/// qualquer implementação de referência do AES).
final class Ir64CryptoExecutor {
    private Ir64CryptoExecutor() {
    }

    /// Polinômio irredutível do `GF(2^8)` usado pelo AES (`x^8+x^4+x^3+x+1`).
    private static final int AES_GF_MODULUS = 0x11B;

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

    static boolean executeAes(Aarch64Core core, Ir64Op.CryptoAes op) {
        Aarch64FpRegisters fp = core.fp();
        byte[] state = new byte[16];
        switch (op.op()) {
            case AESE -> {
                for (int i = 0; i < 16; i++) {
                    state[i] = (byte) (fp.element(op.rd(), i, 0) ^ fp.element(op.rn(), i, 0));
                }
                subBytes(state, S_BOX);
                shiftRows(state, false);
            }
            case AESD -> {
                for (int i = 0; i < 16; i++) {
                    state[i] = (byte) (fp.element(op.rd(), i, 0) ^ fp.element(op.rn(), i, 0));
                }
                invSubBytes(state);
                shiftRows(state, true);
            }
            case AESMC -> {
                for (int i = 0; i < 16; i++) {
                    state[i] = (byte) fp.element(op.rn(), i, 0);
                }
                mixColumns(state, false);
            }
            case AESIMC -> {
                for (int i = 0; i < 16; i++) {
                    state[i] = (byte) fp.element(op.rn(), i, 0);
                }
                mixColumns(state, true);
            }
        }
        writeState(fp, op.rd(), state);
        return false;
    }

    private static void writeState(Aarch64FpRegisters fp, int rd, byte[] state) {
        long lo = 0L;
        long hi = 0L;
        for (int i = 0; i < 16; i++) {
            long b = state[i] & 0xFFL;
            if (i < 8) {
                lo |= b << (i * 8);
            } else {
                hi |= b << ((i - 8) * 8);
            }
        }
        fp.setQ(rd, lo, hi);
    }

    private static void subBytes(byte[] state, byte[] table) {
        for (int i = 0; i < 16; i++) {
            state[i] = table[state[i] & 0xFF];
        }
    }

    private static void invSubBytes(byte[] state) {
        subBytes(state, INV_S_BOX);
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
