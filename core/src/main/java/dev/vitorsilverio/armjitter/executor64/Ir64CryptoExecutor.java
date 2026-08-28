package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
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

    /// `Ch`/`Parity`/`Maj` do SHA1 (FIPS PUB 180-4 §4.1.1) — mesmos nomes de função do padrão.
    private static int sha1Choose(int x, int y, int z) {
        return (x & (y ^ z)) ^ z;
    }

    private static int sha1Parity(int x, int y, int z) {
        return x ^ y ^ z;
    }

    private static int sha1Majority(int x, int y, int z) {
        return (x & y) | ((x | y) & z);
    }

    /// `Σ0`/`Σ1`/`σ0`/`σ1` do SHA256 (FIPS PUB 180-4 §4.1.2).
    private static int sha256BigSigma0(int x) {
        return Integer.rotateRight(x, 2) ^ Integer.rotateRight(x, 13) ^ Integer.rotateRight(x, 22);
    }

    private static int sha256BigSigma1(int x) {
        return Integer.rotateRight(x, 6) ^ Integer.rotateRight(x, 11) ^ Integer.rotateRight(x, 25);
    }

    private static int sha256SmallSigma0(int x) {
        return Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
    }

    private static int sha256SmallSigma1(int x) {
        return Integer.rotateRight(x, 17) ^ Integer.rotateRight(x, 19) ^ (x >>> 10);
    }

    /// Lê os 4 elementos de 32 bits (`words[0]` = 32 bits mais baixos) de `V<reg>` — mesma ordem
    /// little-endian do `union CRYPTO_STATE` real do QEMU (`crypto_helper.c`).
    private static int[] readWords(Aarch64FpRegisters fp, int reg) {
        int[] words = new int[4];
        for (int i = 0; i < 4; i++) {
            words[i] = (int) fp.element(reg, i, WORD_SIZE_LOG2);
        }
        return words;
    }

    private static void writeWords(Aarch64FpRegisters fp, int reg, int[] words) {
        for (int i = 0; i < 4; i++) {
            fp.setElement(reg, i, WORD_SIZE_LOG2, words[i] & 0xFFFFFFFFL);
        }
    }

    /// `log2` do tamanho de elemento "palavra" (32 bits) na convenção de {@link Aarch64FpRegisters}.
    private static final int WORD_SIZE_LOG2 = 2;

    static boolean executeShaThreeRegister(Aarch64Core core, Ir64Op.CryptoShaThreeRegister op) {
        Aarch64FpRegisters fp = core.fp();
        switch (op.op()) {
            case SHA1C, SHA1P, SHA1M -> {
                int[] d = readWords(fp, op.rd());
                int n0 = (int) fp.element(op.rn(), 0, WORD_SIZE_LOG2);
                int[] m = readWords(fp, op.rm());
                for (int i = 0; i < 4; i++) {
                    int t = switch (op.op()) {
                        case SHA1C -> sha1Choose(d[1], d[2], d[3]);
                        case SHA1P -> sha1Parity(d[1], d[2], d[3]);
                        default -> sha1Majority(d[1], d[2], d[3]);
                    };
                    t += Integer.rotateLeft(d[0], 5) + n0 + m[i];
                    n0 = d[3];
                    d[3] = d[2];
                    d[2] = Integer.rotateRight(d[1], 2);
                    d[1] = d[0];
                    d[0] = t;
                }
                writeWords(fp, op.rd(), d);
            }
            case SHA1SU0 -> {
                long dLow = fp.low64(op.rd());
                long dHigh = fp.high64(op.rd());
                long nLow = fp.low64(op.rn());
                long mLow = fp.low64(op.rm());
                long mHigh = fp.high64(op.rm());
                fp.setQ(op.rd(), dHigh ^ dLow ^ mLow, nLow ^ dHigh ^ mHigh);
            }
            case SHA256H, SHA256H2 -> {
                int[] d = readWords(fp, op.rd());
                int[] n = readWords(fp, op.rn());
                int[] m = readWords(fp, op.rm());
                boolean h2 = op.op() == Ir64CryptoShaThreeRegisterOp.SHA256H2;
                for (int i = 0; i < 4; i++) {
                    if (h2) {
                        int t = sha1Choose(d[0], d[1], d[2]) + d[3] + sha256BigSigma1(d[0]) + m[i];
                        d[3] = d[2];
                        d[2] = d[1];
                        d[1] = d[0];
                        d[0] = n[3 - i] + t;
                    } else {
                        int t = sha1Choose(n[0], n[1], n[2]) + n[3] + sha256BigSigma1(n[0]) + m[i];
                        n[3] = n[2];
                        n[2] = n[1];
                        n[1] = n[0];
                        n[0] = d[3] + t;
                        t += sha1Majority(d[0], d[1], d[2]) + sha256BigSigma0(d[0]);
                        d[3] = d[2];
                        d[2] = d[1];
                        d[1] = d[0];
                        d[0] = t;
                    }
                }
                writeWords(fp, op.rd(), d);
            }
            case SHA256SU1 -> {
                int[] d = readWords(fp, op.rd());
                int[] n = readWords(fp, op.rn());
                int[] m = readWords(fp, op.rm());
                d[0] += sha256SmallSigma1(m[2]) + n[1];
                d[1] += sha256SmallSigma1(m[3]) + n[2];
                d[2] += sha256SmallSigma1(d[0]) + n[3];
                d[3] += sha256SmallSigma1(d[1]) + m[0];
                writeWords(fp, op.rd(), d);
            }
        }
        return false;
    }

    static boolean executeShaTwoRegister(Aarch64Core core, Ir64Op.CryptoShaTwoRegister op) {
        Aarch64FpRegisters fp = core.fp();
        switch (op.op()) {
            case SHA1H -> {
                int m0 = (int) fp.element(op.rn(), 0, WORD_SIZE_LOG2);
                writeWords(fp, op.rd(), new int[] {Integer.rotateRight(m0, 2), 0, 0, 0});
            }
            case SHA1SU1 -> {
                int[] d = readWords(fp, op.rd());
                int[] m = readWords(fp, op.rn());
                int d0 = Integer.rotateLeft(d[0] ^ m[1], 1);
                int d1 = Integer.rotateLeft(d[1] ^ m[2], 1);
                int d2 = Integer.rotateLeft(d[2] ^ m[3], 1);
                int d3 = Integer.rotateLeft(d[3] ^ d0, 1);
                writeWords(fp, op.rd(), new int[] {d0, d1, d2, d3});
            }
            case SHA256SU0 -> {
                int[] d = readWords(fp, op.rd());
                int[] m = readWords(fp, op.rn());
                d[0] += sha256SmallSigma0(d[1]);
                d[1] += sha256SmallSigma0(d[2]);
                d[2] += sha256SmallSigma0(d[3]);
                d[3] += sha256SmallSigma0(m[0]);
                writeWords(fp, op.rd(), d);
            }
        }
        return false;
    }

    /// Quantidade de rotação à ESQUERDA fixa do `RAX1` (sem campo de imediato no encoding real —
    /// ver {@link Ir64Op.CryptoSha3TwoSourceRotate} javadoc).
    private static final int RAX1_FIXED_ROTATE_LEFT = 1;

    static boolean executeSha3FourRegister(Aarch64Core core, Ir64Op.CryptoSha3FourRegister op) {
        Aarch64FpRegisters fp = core.fp();
        long nLo = fp.low64(op.rn());
        long nHi = fp.high64(op.rn());
        long mLo = fp.low64(op.rm());
        long mHi = fp.high64(op.rm());
        long aLo = fp.low64(op.ra());
        long aHi = fp.high64(op.ra());
        long resultLo;
        long resultHi;
        switch (op.op()) {
            case EOR3 -> {
                resultLo = nLo ^ mLo ^ aLo;
                resultHi = nHi ^ mHi ^ aHi;
            }
            case BCAX -> {
                resultLo = nLo ^ (mLo & ~aLo);
                resultHi = nHi ^ (mHi & ~aHi);
            }
            default -> throw new IllegalStateException("CryptoSha3FourRegister.op inesperado: " + op.op());
        }
        fp.setQ(op.rd(), resultLo, resultHi);
        return false;
    }

    static boolean executeSha3TwoSourceRotate(Aarch64Core core, Ir64Op.CryptoSha3TwoSourceRotate op) {
        Aarch64FpRegisters fp = core.fp();
        long nLo = fp.low64(op.rn());
        long nHi = fp.high64(op.rn());
        long mLo = fp.low64(op.rm());
        long mHi = fp.high64(op.rm());
        long resultLo;
        long resultHi;
        switch (op.op()) {
            case RAX1 -> {
                resultLo = nLo ^ Long.rotateLeft(mLo, RAX1_FIXED_ROTATE_LEFT);
                resultHi = nHi ^ Long.rotateLeft(mHi, RAX1_FIXED_ROTATE_LEFT);
            }
            case XAR -> {
                resultLo = Long.rotateRight(nLo ^ mLo, op.rotateAmount());
                resultHi = Long.rotateRight(nHi ^ mHi, op.rotateAmount());
            }
            default -> throw new IllegalStateException("CryptoSha3TwoSourceRotate.op inesperado: " + op.op());
        }
        fp.setQ(op.rd(), resultLo, resultHi);
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
