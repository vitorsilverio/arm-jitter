package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaOp;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.HexFormat;

/// Executa a ARMv8-A Cryptographic Extension. Sibling de {@link Ir64VectorArithmeticExecutor}: sem
/// estado próprio, métodos estáticos. É o oráculo semântico (G1) — nenhum `Kind` desta classe entra
/// em `Ir64NativePolicy`, mesma decisão de todo `Kind` novo desde B8.4/B8.6.
///
/// `AESE`/`AESD`/`AESMC`/`AESIMC` (B8.11) e `SHA1H`/`SHA1SU1`/`SHA256SU0` (B8.11b) foram MIGRADOS
/// para o núcleo COMPARTILHADO {@link AdvSimdCrypto} na task B13.15 (RFC B13.2 D1, primeiro
/// consumidor A32) — `mapAesOp`/`mapShaTwoRegisterOp` só traduzem o enum espelhado, a S-box e as
/// matrizes `MixColumns`/`InvMixColumns` vivem só lá agora. As formas de TRÊS registradores
/// continuam abaixo (sem encoding A32).
final class Ir64CryptoExecutor {
    private Ir64CryptoExecutor() {
    }

    /// Mirror 1:1 de {@link Ir64CryptoAesOp} → {@link AdvSimdCryptoAesOp} (núcleo compartilhado).
    private static AdvSimdCryptoAesOp mapAesOp(Ir64CryptoAesOp op) {
        return switch (op) {
            case AESE -> AdvSimdCryptoAesOp.AESE;
            case AESD -> AdvSimdCryptoAesOp.AESD;
            case AESMC -> AdvSimdCryptoAesOp.AESMC;
            case AESIMC -> AdvSimdCryptoAesOp.AESIMC;
        };
    }

    /// Mirror 1:1 de {@link Ir64CryptoShaTwoRegisterOp} → {@link AdvSimdCryptoShaOp} (núcleo
    /// compartilhado).
    private static AdvSimdCryptoShaOp mapShaTwoRegisterOp(Ir64CryptoShaTwoRegisterOp op) {
        return switch (op) {
            case SHA1H -> AdvSimdCryptoShaOp.SHA1H;
            case SHA1SU1 -> AdvSimdCryptoShaOp.SHA1SU1;
            case SHA256SU0 -> AdvSimdCryptoShaOp.SHA256SU0;
        };
    }

    static boolean executeAes(Aarch64Core core, Ir64Op.CryptoAes op) {
        Aarch64FpRegisters fp = core.fp();
        AdvSimdCrypto.aes(fp, mapAesOp(op.op()),
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
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

    /// `Σ0`/`Σ1`/`σ1` do SHA256 (FIPS PUB 180-4 §4.1.2). `σ0` migrou para
    /// {@link AdvSimdCrypto#shaTwoRegister} (B13.15, único consumidor era `SHA256SU0` de dois
    /// registradores).
    private static int sha256BigSigma0(int x) {
        return Integer.rotateRight(x, 2) ^ Integer.rotateRight(x, 13) ^ Integer.rotateRight(x, 22);
    }

    private static int sha256BigSigma1(int x) {
        return Integer.rotateRight(x, 6) ^ Integer.rotateRight(x, 11) ^ Integer.rotateRight(x, 25);
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
        AdvSimdCrypto.shaTwoRegister(fp, mapShaTwoRegisterOp(op.op()),
                op.rd() * Aarch64FpRegisters.WORDS_PER_REGISTER,
                op.rn() * Aarch64FpRegisters.WORDS_PER_REGISTER);
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

    // ── B19.10 (`FEAT_SHA512`): `Σ0`/`Σ1`/`σ0`/`σ1`/`Ch`/`Maj` de 64 bits (FIPS PUB 180-4 §4.1.3) —
    // ── MESMAS fórmulas de {@link #sha256BigSigma0}/etc, só a largura muda (SHA-512 opera em
    // ── palavras de 64 bits, não 32 — Armadilha 1 da task). `Ch`/`Maj` de 64 bits são bit a bit
    // ── IDÊNTICAS a {@link #sha1Choose}/{@link #sha1Majority} (a fórmula não depende da largura),
    // ── mas com assinatura `long` própria para não obrigar cast no chamador.
    private static long sha512BigSigma0(long x) {
        return Long.rotateRight(x, 28) ^ Long.rotateRight(x, 34) ^ Long.rotateRight(x, 39);
    }

    private static long sha512BigSigma1(long x) {
        return Long.rotateRight(x, 14) ^ Long.rotateRight(x, 18) ^ Long.rotateRight(x, 41);
    }

    private static long sha512SmallSigma0(long x) {
        return Long.rotateRight(x, 1) ^ Long.rotateRight(x, 8) ^ (x >>> 7);
    }

    private static long sha512SmallSigma1(long x) {
        return Long.rotateRight(x, 19) ^ Long.rotateRight(x, 61) ^ (x >>> 6);
    }

    private static long sha512Choose(long x, long y, long z) {
        return (x & (y ^ z)) ^ z;
    }

    private static long sha512Majority(long x, long y, long z) {
        return (x & y) | ((x | y) & z);
    }

    /// `SHA512H`/`SHA512H2`/`SHA512SU1` (`FEAT_SHA512`, B19.10) — fórmulas transcritas do helper
    /// `crypto_sha512h`/`crypto_sha512h2`/`crypto_sha512su1` do QEMU (`target/arm/tcg/crypto_helper.c`,
    /// a mesma "fonte real" já citada por B8.11/B8.11b/B11.12 para esta extensão), que por sua vez
    /// implementa a pseudocódigo do `ARM DDI 0487`. `SHA512H`/`H2` leem `Rd` como estado corrente
    /// (`{a,b}`/`{c,d}` do algoritmo, ver `## Resultado` da task) e o atualizam; `Rn`/`Rm` nunca são
    /// escritos.
    static boolean executeSha512ThreeRegister(Aarch64Core core, Ir64Op.CryptoSha512ThreeRegister op) {
        Aarch64FpRegisters fp = core.fp();
        long rn0 = fp.low64(op.rn());
        long rn1 = fp.high64(op.rn());
        long rm0 = fp.low64(op.rm());
        long rm1 = fp.high64(op.rm());
        long d0 = fp.low64(op.rd());
        long d1 = fp.high64(op.rd());
        switch (op.op()) {
            case SHA512H -> {
                d1 += sha512BigSigma1(rm1) + sha512Choose(rm1, rn0, rn1);
                d0 += sha512BigSigma1(d1 + rm0) + sha512Choose(d1 + rm0, rm1, rn0);
            }
            case SHA512H2 -> {
                d1 += sha512BigSigma0(rm0) + sha512Majority(rn0, rm1, rm0);
                d0 += sha512BigSigma0(d1) + sha512Majority(d1, rm0, rm1);
            }
            case SHA512SU1 -> {
                d0 += sha512SmallSigma1(rn0) + rm0;
                d1 += sha512SmallSigma1(rn1) + rm1;
            }
        }
        fp.setQ(op.rd(), d0, d1);
        return false;
    }

    /// `SHA512SU0` (`FEAT_SHA512`, B19.10) — `crypto_sha512su0` do QEMU. As duas metades usam os
    /// valores ORIGINAIS de `Rd`/`Rn` (nenhuma depende da outra já atualizada, ao contrário de
    /// {@link #executeSha512ThreeRegister}).
    static boolean executeSha512TwoRegister(Aarch64Core core, Ir64Op.CryptoSha512TwoRegister op) {
        Aarch64FpRegisters fp = core.fp();
        long d0 = fp.low64(op.rd());
        long d1 = fp.high64(op.rd());
        long n0 = fp.low64(op.rn());
        long newD0 = d0 + sha512SmallSigma0(d1);
        long newD1 = d1 + sha512SmallSigma0(n0);
        fp.setQ(op.rd(), newD0, newD1);
        return false;
    }

    /// `SM3PARTW1`/`SM3PARTW2` (`FEAT_SM3`, B19.10) — fórmulas transcritas do helper
    /// `crypto_sm3partw1`/`crypto_sm3partw2` do QEMU. `PARTW1` encadeia (a última palavra usa a
    /// PRIMEIRA já atualizada nesta mesma execução — mutação em sequência, não paralela); `PARTW2`
    /// não encadeia (só reusa a variável `t` local).
    static boolean executeSm3ThreeRegister(Aarch64Core core, Ir64Op.CryptoSm3ThreeRegister op) {
        Aarch64FpRegisters fp = core.fp();
        int[] d = readWords(fp, op.rd());
        int[] n = readWords(fp, op.rn());
        int[] m = readWords(fp, op.rm());
        switch (op.op()) {
            case PARTW1 -> {
                int t = d[0] ^ n[0] ^ Integer.rotateRight(m[1], 17);
                d[0] = t ^ Integer.rotateRight(t, 17) ^ Integer.rotateRight(t, 9);
                t = d[1] ^ n[1] ^ Integer.rotateRight(m[2], 17);
                d[1] = t ^ Integer.rotateRight(t, 17) ^ Integer.rotateRight(t, 9);
                t = d[2] ^ n[2] ^ Integer.rotateRight(m[3], 17);
                d[2] = t ^ Integer.rotateRight(t, 17) ^ Integer.rotateRight(t, 9);
                t = d[3] ^ n[3] ^ Integer.rotateRight(d[0], 17);
                d[3] = t ^ Integer.rotateRight(t, 17) ^ Integer.rotateRight(t, 9);
            }
            case PARTW2 -> {
                int t = n[0] ^ Integer.rotateRight(m[0], 25);
                d[0] ^= t;
                d[1] ^= n[1] ^ Integer.rotateRight(m[1], 25);
                d[2] ^= n[2] ^ Integer.rotateRight(m[2], 25);
                d[3] ^= n[3] ^ Integer.rotateRight(m[3], 25) ^ Integer.rotateRight(t, 17)
                        ^ Integer.rotateRight(t, 2) ^ Integer.rotateRight(t, 26);
            }
        }
        writeWords(fp, op.rd(), d);
        return false;
    }

    /// `SM3SS1` (`FEAT_SM3`, B19.10) — `ARM DDI 0487`: `result = ROL(ROL(Vn[127:96],12) +
    /// Vm[127:96] + Va[127:96], 7)`, escrito só na palavra ALTA de `Rd` (elemento `3`); as 3
    /// palavras baixas são zeradas (função pura — `Rd` atual nunca é lido).
    static boolean executeSm3FourRegister(Aarch64Core core, Ir64Op.CryptoSm3FourRegister op) {
        Aarch64FpRegisters fp = core.fp();
        int n3 = (int) fp.element(op.rn(), 3, WORD_SIZE_LOG2);
        int m3 = (int) fp.element(op.rm(), 3, WORD_SIZE_LOG2);
        int a3 = (int) fp.element(op.ra(), 3, WORD_SIZE_LOG2);
        int result = Integer.rotateLeft(Integer.rotateLeft(n3, 12) + m3 + a3, 7);
        writeWords(fp, op.rd(), new int[] {0, 0, 0, result});
        return false;
    }

    /// `SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B` (`FEAT_SM3`, B19.10) — fórmula transcrita de
    /// `crypto_sm3tt` do QEMU (função única parametrizada por `opcode`, aqui despachada pelo
    /// `enum`). `Par`/`Maj`/`Cho` reusam {@link #sha1Parity}/{@link #sha1Majority}/
    /// {@link #sha1Choose} (mesmas fórmulas do FIPS 180-4, a largura não muda). Mutação em
    /// SEQUÊNCIA (`d[0]`/`d[1]`/`d[2]`/`d[3]`, nesta ordem) — trocar a ordem lê valor já sobrescrito.
    static boolean executeSm3ThreeRegisterImm2(Aarch64Core core, Ir64Op.CryptoSm3ThreeRegisterImm2 op) {
        Aarch64FpRegisters fp = core.fp();
        int[] d = readWords(fp, op.rd());
        int[] n = readWords(fp, op.rn());
        int[] m = readWords(fp, op.rm());
        boolean variant1 = op.op() == Ir64CryptoSm3TtOp.TT1A || op.op() == Ir64CryptoSm3TtOp.TT1B;
        int t = switch (op.op()) {
            case TT1A, TT2A -> sha1Parity(d[3], d[2], d[1]);
            case TT1B -> sha1Majority(d[3], d[2], d[1]);
            case TT2B -> sha1Choose(d[3], d[2], d[1]);
        };
        t += d[0] + m[op.imm2()];
        d[0] = d[1];
        if (variant1) {
            t += n[3] ^ Integer.rotateRight(d[3], 20);
            d[1] = Integer.rotateRight(d[2], 23);
        } else {
            t += n[3];
            t ^= Integer.rotateLeft(t, 9) ^ Integer.rotateLeft(t, 17);
            d[1] = Integer.rotateRight(d[2], 13);
        }
        d[2] = d[3];
        d[3] = t;
        writeWords(fp, op.rd(), d);
        return false;
    }

    /// S-box do SM4 (GB/T 32907-2016) — os 256 bytes públicos do padrão, transcritos como um único
    /// literal hexadecimal (verificável byte a byte pela contagem: `HexFormat` recusa qualquer
    /// comprimento diferente de 512 caracteres/256 bytes) em vez de 256 literais `(byte)0x..`
    /// separados, reduzindo a chance de erro de transcrição silencioso citada pela Armadilha 5 da
    /// task — o vetor de teste de `SM4E`/`SM4EKEY` (round-trip completo contra o vetor de exemplo
    /// oficial do padrão) é a verificação independente.
    private static final byte[] SM4_SBOX = HexFormat.of().parseHex(
            "d690e9fecce13db716b614c228fb2c052b679a762abe04c3aa441326498606999c4250f491ef987a33540b43edcfac62"
            + "e4b31ca9c908e89580df94fa758f3fa64707a7fcf37317ba83593c19e6854fa8686b81b27164da8bf8eb0f4b70569d35"
            + "1e240e5e6358d1a225227c3b01217887d40046579fd327524c3602e7a0c4c89eeabf8ad240c738b5a3f7f2cef96115a1"
            + "e0ae5da49b341a55ad933230f58cb1e31df6e22e8266ca60c02923ab0d534e6fd5db3745defd8e2f03ff6a726d6c5b51"
            + "8d1baf92bbddbc7f11d95c411f105ad80ac13188a5cd7bbd2d74d012b8e5b4b08969974a0c96777e65b9f109c56ec684"
            + "18f07dec3adc4d2079ee5f3ed7cb3948");

    /// `sm4_subword` do QEMU (`include/crypto/sm4.h`): substituição byte a byte via {@link #SM4_SBOX}.
    private static int sm4SubWord(int word) {
        return (SM4_SBOX[word & 0xFF] & 0xFF)
                | ((SM4_SBOX[(word >>> 8) & 0xFF] & 0xFF) << 8)
                | ((SM4_SBOX[(word >>> 16) & 0xFF] & 0xFF) << 16)
                | ((SM4_SBOX[(word >>> 24) & 0xFF] & 0xFF) << 24);
    }

    /// `SM4E` (`FEAT_SM4`, B19.10) — fórmula transcrita de `do_crypto_sm4e` do QEMU: processa 4
    /// rodadas de cifra de uma vez. {@link Ir64Op.CryptoSm4Encrypt#rd} é o estado ATUAL do bloco
    /// (lido E escrito, mesmo padrão destrutivo de {@link #executeAes} `AESE`/`AESD`);
    /// {@link Ir64Op.CryptoSm4Encrypt#rn} carrega as 4 subchaves de rodada desta chamada.
    static boolean executeSm4Encrypt(Aarch64Core core, Ir64Op.CryptoSm4Encrypt op) {
        Aarch64FpRegisters fp = core.fp();
        int[] d = readWords(fp, op.rd());
        int[] roundKeys = readWords(fp, op.rn());
        for (int i = 0; i < 4; i++) {
            int t = d[(i + 1) % 4] ^ d[(i + 2) % 4] ^ d[(i + 3) % 4] ^ roundKeys[i];
            t = sm4SubWord(t);
            d[i] ^= t ^ Integer.rotateLeft(t, 2) ^ Integer.rotateLeft(t, 10)
                    ^ Integer.rotateLeft(t, 18) ^ Integer.rotateLeft(t, 24);
        }
        writeWords(fp, op.rd(), d);
        return false;
    }

    /// `SM4EKEY` (`FEAT_SM4`, B19.10) — fórmula transcrita de `do_crypto_sm4ekey` do QEMU: expansão
    /// de chave, 4 rodadas de uma vez. Função PURA de {@link Ir64Op.CryptoSm4KeyUpdate#rn} (estado
    /// atual da chave) e {@link Ir64Op.CryptoSm4KeyUpdate#rm} (constantes de rodada `CK`) — `Rd`
    /// atual NUNCA é lido (diferente de {@link #executeSm4Encrypt}), mesma rotação de 13/23 (não
    /// 2/10/18/24 de `SM4E` — algoritmos de expansão de chave e de cifra usam transformações
    /// lineares `L`/`L'` diferentes por design do padrão SM4).
    static boolean executeSm4KeyUpdate(Aarch64Core core, Ir64Op.CryptoSm4KeyUpdate op) {
        Aarch64FpRegisters fp = core.fp();
        int[] d = readWords(fp, op.rn());
        int[] roundConstants = readWords(fp, op.rm());
        for (int i = 0; i < 4; i++) {
            int t = d[(i + 1) % 4] ^ d[(i + 2) % 4] ^ d[(i + 3) % 4] ^ roundConstants[i];
            t = sm4SubWord(t);
            d[i] ^= t ^ Integer.rotateLeft(t, 13) ^ Integer.rotateLeft(t, 23);
        }
        writeWords(fp, op.rd(), d);
        return false;
    }

}
