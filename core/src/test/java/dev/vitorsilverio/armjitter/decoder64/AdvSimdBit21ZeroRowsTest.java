package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.9: as linhas do piloto {@link AdvSimdBit21ZeroRows} — invariantes da tabela, roteamento
/// pelo {@link Aarch64Decoder} e a regressão do bug G8 do FP16 (`bit14` ignorado).
class AdvSimdBit21ZeroRowsTest {
    /// Só as 8 features do piloto: com `FEAT_SME` o `decode` embrulharia o op AdvSIMD em
    /// `StreamingRestricted`.
    private static final Aarch64Architecture ALL_FEATURES = Aarch64Architecture.of("piloto E15.9",
            Aarch64Feature.RDM, Aarch64Feature.FP16, Aarch64Feature.FP8, Aarch64Feature.FP_ABSOLUTE_MAX_MIN,
            Aarch64Feature.FP8_FUSED_MULTIPLY_ADD, Aarch64Feature.FP8_DOT_PRODUCT_2WAY,
            Aarch64Feature.FP8_DOT_PRODUCT_4WAY, Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC);
    /// O chamador ainda não filtra o `bit31` (E15.9b) — o sorteio o mantém em `0`, o valor real.
    private static final int BIT31 = 1 << 31;
    private static final int BIT14 = 1 << 14;
    private static final int ROW_COUNT = 53;

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
        DecodeTableInvariants.assertReachable(table, BIT31, 0xE159L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_FEATURES);
        SplittableRandom random = new SplittableRandom(0xE159L);
        for (DecodeRow<Ir64Op> row : AdvSimdBit21ZeroRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask() & ~BIT31);
            assertEquals(row.build().decode(word), decodeWord(decoder, word), Integer.toHexString(word));
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
}
