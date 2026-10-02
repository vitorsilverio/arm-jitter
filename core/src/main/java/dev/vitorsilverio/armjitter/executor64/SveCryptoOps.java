package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;

/// Semântica das 7 instruções "SVE2 Crypto Extensions" (B17.24): `AESE`/`AESD`/`AESMC`/`AESIMC` e `SM4E`/
/// `SM4EKEY` operam POR SEGMENTO de 128 bits (`VL/128` blocos independentes); `RAX1` opera elemento a elemento de
/// 64 bits por toda a largura de `VL`, sem interação entre elementos (Achado 4 da task — `esz` do formato é `0`
/// mas a operação real é em doubleword).
///
/// **Nenhuma tabela criptográfica nova**: `AESE`/`AESD`/`AESMC`/`AESIMC` reusam
/// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#aes} (mesma S-box do A64/A32, via
/// {@link SegmentView} — uma vista de {@link AdvSimdRegisterWords} sobre UM segmento de `Z`, mesma convenção `V<n>`
/// = palavras `2n`/`2n+1` de {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}); `SM4E`/`SM4EKEY`
/// reusam {@link Ir64CryptoExecutor#sm4SubWord}/{@link Ir64CryptoExecutor#SM4_SBOX}.
///
/// Todas recusadas em modo streaming (`SvePredicateOps#requireNonStreaming`): `AESE`/`AESD`/`AESMC`/`AESIMC`/
/// `RAX1` liberariam um subconjunto sob `FEAT_SSVE_AES`/`FEAT_SME2p1` no hardware real (`TRANS_FEAT_STREAMING_IF`
/// do QEMU), não modelado ainda — **pendência nomeada**; `SM4E`/`SM4EKEY` são sempre não-streaming mesmo no
/// hardware real (`TRANS_FEAT_NONSTREAMING`, sem exceção SME).
final class SveCryptoOps {
    /// Palavras de 64 bits por segmento de 128 bits — mesma convenção `V<n>` = `2n`/`2n+1` de
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#WORDS_PER_REGISTER}.
    private static final int WORDS_PER_SEGMENT = 2;
    private static final int BYTES_PER_SEGMENT = 16;
    /// `log2` do tamanho de elemento "palavra" (32 bits) na convenção de {@link SveIntegerOps#get}.
    private static final int WORD_ESZ = 2;
    /// `log2` do tamanho de elemento "palavra dupla" (64 bits).
    private static final int DOUBLEWORD_ESZ = 3;
    private static final int WORDS_PER_SM4_BLOCK = 4;
    private static final int RAX1_ROTATE_LEFT = 1;

    private SveCryptoOps() {
    }

    /// Mirror 1:1 de {@link Ir64CryptoAesOp} → {@link AdvSimdCryptoAesOp} (núcleo compartilhado) — mesma tradução
    /// de {@code Ir64CryptoExecutor#mapAesOp}, repetida aqui porque aquela é `private` (o mirror em si não é
    /// tabela nenhuma, é só nomeação de enum).
    private static AdvSimdCryptoAesOp mapAesOp(Ir64CryptoAesOp op) {
        return switch (op) {
            case AESE -> AdvSimdCryptoAesOp.AESE;
            case AESD -> AdvSimdCryptoAesOp.AESD;
            case AESMC -> AdvSimdCryptoAesOp.AESMC;
            case AESIMC -> AdvSimdCryptoAesOp.AESIMC;
        };
    }

    static boolean executeAes(Aarch64Core core, SveIntegerOp64.CryptoAes op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        AdvSimdCryptoAesOp mapped = mapAesOp(op.op());
        int segments = core.vectorLengthBytes() / BYTES_PER_SEGMENT;
        for (int segment = 0; segment < segments; segment++) {
            AdvSimdRegisterWords view = new SegmentView(regs, segment);
            AdvSimdCrypto.aes(view, mapped, op.rd() * WORDS_PER_SEGMENT, op.rn() * WORDS_PER_SEGMENT);
        }
        return false;
    }

    static boolean executeSm4Encrypt(Aarch64Core core, SveIntegerOp64.CryptoSm4Encrypt op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int segments = core.vectorLengthBytes() / BYTES_PER_SEGMENT;
        for (int segment = 0; segment < segments; segment++) {
            int base = segment * WORDS_PER_SM4_BLOCK;
            int[] d = new int[WORDS_PER_SM4_BLOCK];
            int[] roundKeys = new int[WORDS_PER_SM4_BLOCK];
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                d[i] = (int) SveIntegerOps.get(regs, op.rd(), base + i, WORD_ESZ);
                roundKeys[i] = (int) SveIntegerOps.get(regs, op.rn(), base + i, WORD_ESZ);
            }
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                int t = d[(i + 1) % WORDS_PER_SM4_BLOCK] ^ d[(i + 2) % WORDS_PER_SM4_BLOCK]
                        ^ d[(i + 3) % WORDS_PER_SM4_BLOCK] ^ roundKeys[i];
                t = Ir64CryptoExecutor.sm4SubWord(t);
                d[i] ^= t ^ Integer.rotateLeft(t, 2) ^ Integer.rotateLeft(t, 10)
                        ^ Integer.rotateLeft(t, 18) ^ Integer.rotateLeft(t, 24);
            }
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                SveIntegerOps.set(regs, op.rd(), base + i, WORD_ESZ, d[i] & 0xFFFF_FFFFL);
            }
        }
        return false;
    }

    static boolean executeSm4KeyUpdate(Aarch64Core core, SveIntegerOp64.CryptoSm4KeyUpdate op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int segments = core.vectorLengthBytes() / BYTES_PER_SEGMENT;
        for (int segment = 0; segment < segments; segment++) {
            int base = segment * WORDS_PER_SM4_BLOCK;
            int[] d = new int[WORDS_PER_SM4_BLOCK];
            int[] roundConstants = new int[WORDS_PER_SM4_BLOCK];
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                d[i] = (int) SveIntegerOps.get(regs, op.rn(), base + i, WORD_ESZ);
                roundConstants[i] = (int) SveIntegerOps.get(regs, op.rm(), base + i, WORD_ESZ);
            }
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                int t = d[(i + 1) % WORDS_PER_SM4_BLOCK] ^ d[(i + 2) % WORDS_PER_SM4_BLOCK]
                        ^ d[(i + 3) % WORDS_PER_SM4_BLOCK] ^ roundConstants[i];
                t = Ir64CryptoExecutor.sm4SubWord(t);
                d[i] ^= t ^ Integer.rotateLeft(t, 13) ^ Integer.rotateLeft(t, 23);
            }
            for (int i = 0; i < WORDS_PER_SM4_BLOCK; i++) {
                SveIntegerOps.set(regs, op.rd(), base + i, WORD_ESZ, d[i] & 0xFFFF_FFFFL);
            }
        }
        return false;
    }

    /// `RAX1`: `Zd = Zn XOR rotateLeft(Zm, 1)`, elemento a elemento de 64 bits por TODA a largura de `VL` — sem
    /// segmentação (Achado 4 da task: `esz` do formato é `0`, a operação real é doubleword, e não há interação
    /// entre elementos vizinhos, ao contrário de `AES`/`SM4`).
    static boolean executeRax1(Aarch64Core core, SveIntegerOp64.CryptoRax1 op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() / (1 << DOUBLEWORD_ESZ);
        for (int i = 0; i < elements; i++) {
            long n = SveIntegerOps.get(regs, op.rn(), i, DOUBLEWORD_ESZ);
            long m = SveIntegerOps.get(regs, op.rm(), i, DOUBLEWORD_ESZ);
            SveIntegerOps.set(regs, op.rd(), i, DOUBLEWORD_ESZ, n ^ Long.rotateLeft(m, RAX1_ROTATE_LEFT));
        }
        return false;
    }

    /// Vista de {@link AdvSimdRegisterWords} sobre UM segmento de 128 bits do banco `Z`: `word(index)` endereça
    /// `Z<index/2>[64*(index%2 + 1) - 1 : 64*(index%2)]` DENTRO do segmento — a mesma convenção
    /// "`V<n>` = palavras `2n`/`2n+1`" de {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}, só que a
    /// palavra física é deslocada por {@code segment * 2} em {@link Aarch64ScalableRegisters#zWord}. É o que deixa
    /// {@link AdvSimdCrypto#aes} operar num segmento só sem saber que `Z` é mais largo que 128 bits.
    private static final class SegmentView implements AdvSimdRegisterWords {
        private final Aarch64ScalableRegisters regs;
        private final int segment;

        SegmentView(Aarch64ScalableRegisters regs, int segment) {
            this.regs = regs;
            this.segment = segment;
        }

        @Override
        public long word(int index) {
            int reg = index / WORDS_PER_SEGMENT;
            int wordInSegment = index % WORDS_PER_SEGMENT;
            return regs.zWord(reg, segment * WORDS_PER_SEGMENT + wordInSegment);
        }

        @Override
        public void setWord(int index, long value) {
            int reg = index / WORDS_PER_SEGMENT;
            int wordInSegment = index % WORDS_PER_SEGMENT;
            regs.setZWord(reg, segment * WORDS_PER_SEGMENT + wordInSegment, value);
        }
    }
}
