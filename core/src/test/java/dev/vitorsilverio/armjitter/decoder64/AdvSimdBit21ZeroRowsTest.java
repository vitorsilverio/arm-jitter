package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.9: as linhas do piloto {@link AdvSimdBit21ZeroRows} — invariantes da tabela, roteamento
/// pelo {@link Aarch64Decoder} e a regressão do bug G8 do FP16 (`bit14` ignorado). E15.9b: `bit31`,
/// `bits[23:22]` do copy e as pareadas `_h`.
class AdvSimdBit21ZeroRowsTest {
    /// Só as 8 features do piloto: com `FEAT_SME` o `decode` embrulharia o op AdvSIMD em
    /// `StreamingRestricted`.
    private static final Aarch64Architecture ALL_FEATURES = Aarch64Architecture.of("piloto E15.9",
            Aarch64Feature.RDM, Aarch64Feature.FP16, Aarch64Feature.FP8, Aarch64Feature.FP_ABSOLUTE_MAX_MIN,
            Aarch64Feature.FP8_FUSED_MULTIPLY_ADD, Aarch64Feature.FP8_DOT_PRODUCT_2WAY,
            Aarch64Feature.FP8_DOT_PRODUCT_4WAY, Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC);
    private static final int BIT31 = 1 << 31;
    private static final int BIT14 = 1 << 14;
    private static final int ROW_COUNT = 58;
    private static final int ESZ_HALFWORD = 1;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdBit21ZeroRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdBit21ZeroRows.ROWS);
    }

    @Test
    void everyRowIsReachableThroughTheTable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(AdvSimdBit21ZeroRows.ROWS, ALL_FEATURES);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE159L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        SplittableRandom random = new SplittableRandom(0xE159L);
        for (DecodeRow<Ir64Op> row : AdvSimdBit21ZeroRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void everyRowFixesBit31ToZero() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        for (DecodeRow<Ir64Op> row : AdvSimdBit21ZeroRows.ROWS) {
            assertEquals(BIT31, row.mask() & BIT31, Integer.toHexString(row.value()));
            int word = row.value() | BIT31;
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, word),
                    Integer.toHexString(word));
        }
    }

    @Test
    void presetWithoutTheFeaturesKeepsNoRow() {
        assertEquals(0, DecodeTable.forArchitecture(AdvSimdBit21ZeroRows.ROWS, Aarch64Architecture.ARMV8_0_A)
                .rows().size());
    }

    @Test
    void fp16ThreeSameWithBit14SetIsNotFp16() {
        // 0x4e405400 — `FADD v0.8h` (0x4e401400) com bit14=1: `undefined` no objdump do devkitA64;
        // a cascata antiga ignorava o bit14 e devolvia FADD.
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0x4e405400));
        for (DecodeRow<Ir64Op> row : AdvSimdBit21ZeroRows.ROWS) {
            if (row.requires() != Aarch64Feature.FP16) {
                continue;
            }
            int word = row.value() | BIT14;
            Ir64Op op;
            try {
                op = decodeWord(decoder, word);
            } catch (UnsupportedOperationException expected) {
                continue;
            }
            assertFalse(op instanceof AdvSimdFpOp64.FpArithmeticThreeSame, Integer.toHexString(word) + " → " + op);
        }
    }

    @Test
    void bit31SetIsUndefinedInEveryAdvSimdBranch() {
        // Palavras do `objdump` do devkitA64 (`undefined`) e formas válidas com `bit31` ligado, uma
        // por ramo de `decodeAdvancedSimdInteger`: antes saíam como FADD/EXT/SSHR/MUL/ADD/... .
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        int[] undefinedWords = {0x8e401400, 0xae401400, 0x8e000000};
        int[] validWords = {
                0x4f3d0420, // sshr v0.4s, v1.4s, #3          (shift by immediate)
                0x4fa28020, // mul v0.4s, v1.4s, v2.s[1]      (vector x indexed)
                0x5ee28420, // add d0, d1, d2                 (escalar three same)
                0x5fa2c020, // sqdmulh s0, s1, v2.s[1]        (escalar x indexed)
                0x6e021820, // ext v0.16b, v1.16b, v2.16b, #3 (bit21=0)
                0x6e0c0420, // mov v0.s[1], v1.s[0]           (copy)
                0x4f000420, // movi v0.4s, #1                 (modified immediate)
        };
        for (int word : undefinedWords) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, word),
                    Integer.toHexString(word));
        }
        for (int word : validWords) {
            decodeWord(decoder, word);
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, word | BIT31),
                    Integer.toHexString(word | BIT31));
        }
    }

    @Test
    void copyRejectsNonZeroBits23To22() {
        // 0x6e5c5ce7: `undefined` no objdump; antes da E15.9 saía como FADD `_h` (bit14 ignorado),
        // depois dela como `INS` (o copy lia `imm5` sem olhar bits[23:22]).
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0x6e5c5ce7));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0x6e9f0400));
        // Sem as features da tabela (com FP16, `0x6e4c0420` é FMAXNMP_h legítimo): só o copy
        // disputa estas palavras.
        Aarch64Decoder base = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        int[] copyWords = {
                0x6e0c0420, // mov v0.s[1], v1.s[0]  (INS element)
                0x4e040c20, // dup v0.4s, w1         (DUP general)
                0x4e0c1c20, // mov v0.s[1], w1       (INS general)
                0x0e0c3c20, // mov w0, v1.s[1]       (UMOV)
        };
        for (int copy : copyWords) {
            decodeWord(base, copy);
            for (int bits23To22 = 1; bits23To22 < 4; bits23To22++) {
                int word = copy | (bits23To22 << 22);
                assertThrows(UnsupportedOperationException.class, () -> decodeWord(base, word),
                        Integer.toHexString(word));
            }
        }
    }

    @Test
    void halfPrecisionVectorPairwiseDecodes() {
        // Palavras do `as` do devkitA64 (`-march=armv8.2-a+fp16`); as duas FMAXNMP são as do
        // oráculo da E15.9 que saíam como `InsertElement`.
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.ADD, false, 1, 2, 3), decodeWord(decoder, 0x2e431441));
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.MAX, true, 1, 2, 3), decodeWord(decoder, 0x6e433441));
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.MIN, false, 1, 2, 3), decodeWord(decoder, 0x2ec33441));
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.MAXNM, true, 28, 0, 23), decodeWord(decoder, 0x6e57041c));
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.MAXNM, true, 14, 19, 18), decodeWord(decoder, 0x6e52066e));
        assertEquals(pairwise(Ir64VectorFpPairwiseOp.MINNM, true, 14, 19, 18), decodeWord(decoder, 0x6ed2066e));
        // Sem FEAT_FP16 a forma `_h` não existe — e não pode cair no copy.
        Aarch64Decoder withoutFp16 = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(withoutFp16, 0x6e57041c));
    }

    private static AdvSimdFpOp64.FpArithmeticPairwise pairwise(Ir64VectorFpPairwiseOp op, boolean q,
            int rd, int rn, int rm) {
        return new AdvSimdFpOp64.FpArithmeticPairwise(op, false, q, ESZ_HALFWORD, rd, rn, rm);
    }
}
