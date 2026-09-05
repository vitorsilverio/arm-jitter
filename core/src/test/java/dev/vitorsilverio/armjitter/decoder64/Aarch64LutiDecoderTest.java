package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `LUTI2`/`LUTI4` (`FEAT_LUT`, `ARMv9.5-A`, B19.8) — as 4 linhas vivem no MESMO espaço `bit21=0`/
/// `u=0`/`bit10=0`/`bit15=0` de `EXT`/permute/`TBL` (ver {@link Aarch64Decoder}), sem colisão hoje
/// (bits[23:22] das 4 formas nunca são `00`, então caem no ramo `TBL` reservado e seguem
/// `UnsupportedOperationException` sem a feature — prova de G3/G8, mesmo padrão de B11.4 para
/// `FEAT_RDM`). O assembler do devkitA64 disponível nesta sessão (binutils 2.46) não reconhece a
/// sintaxe `LUTI2`/`LUTI4` de AdvSIMD (rejeita "expected an SVE vector register" para a forma sem
/// `Z`) — as palavras abaixo foram montadas À MÃO a partir do encoding real
/// (`target/arm/tcg/a64.decode` do QEMU, confirmado contra o commit
/// `5fbdd62ee22f929400a623b4a1725dea83b6da70`, "target/arm: Implement LUTI2, LUTI4 for AdvSIMD").
class Aarch64LutiDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder PRE_LUT_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_4_A);
    private static final Aarch64Decoder LUT_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Corpus (montado à mão, ver javadoc da classe) ──────────────────────────────────────────────
    private static final int LUTI2_1B_IDX3 = 0x4e827020; // rd=0  rn=1  rm=2  idx=3
    private static final int LUTI2_1B_IDX0 = 0x4e8e11ac; // rd=12 rn=13 rm=14 idx=0
    private static final int LUTI2_1H_IDX7 = 0x4ec57083; // rd=3  rn=4  rm=5  idx=7
    private static final int LUTI2_1H_IDX2 = 0x4ed1220f; // rd=15 rn=16 rm=17 idx=2
    private static final int LUTI4_1B_IDX1 = 0x4e4860e6; // rd=6  rn=7  rm=8  idx=1
    private static final int LUTI4_1B_IDX0 = 0x4e542272; // rd=18 rn=19 rm=20 idx=0
    private static final int LUTI4_2H_IDX3 = 0x4e4b7149; // rd=9  rn=10 rm=11 idx=3
    private static final int LUTI4_2H_IDX1 = 0x4e5732d5; // rd=21 rn=22 rm=23 idx=1
    /// Padrão `LUTI4` (bits[23:21]="010") com bits[14:10] que não batem NEM `_1b` (`1000`) NEM
    /// `_2h` (`x100` com bit12=1) — reservado, precisa continuar `unsupported` mesmo COM a feature.
    private static final int LUTI4_RESERVED = 0x4e410040;

    private static final int[] ALL_WORDS = {
            LUTI2_1B_IDX3, LUTI2_1B_IDX0, LUTI2_1H_IDX7, LUTI2_1H_IDX2,
            LUTI4_1B_IDX1, LUTI4_1B_IDX0, LUTI4_2H_IDX3, LUTI4_2H_IDX1,
    };

    @Test
    void rejectedByDefaultArchitectureWithoutFeature() {
        for (int word : ALL_WORDS) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria continuar unsupported sem FEAT_LUT");
        }
    }

    @Test
    void rejectedByArmv94AOneVersionBeforeTheFeature() {
        for (int word : ALL_WORDS) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(PRE_LUT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria continuar unsupported em ARMv9.4-A");
        }
    }

    @Test
    void tblTbxStillDecode() {
        // tbl v0.8b, {v1.16b}, v2.8b (len=0, tbx=0, q=0) — regressão da B8.10, mesmo espaço de
        // encoding que este gate agora intercepta primeiro.
        Ir64Op op = decodeWord(LUT_DECODER, 0x0e020020);
        assertTrue(op instanceof Ir64Op.VectorTableLookup);
    }

    @Test
    void reservedLuti4PatternStaysUnsupportedEvenWithFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(LUT_DECODER, LUTI4_RESERVED));
    }

    @Test
    void luti2_1b() {
        Ir64Op.VectorLookupTable op = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI2_1B_IDX3);
        assertFalse(op.four());
        assertEquals(0, op.esz());
        assertEquals(3, op.idx());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());

        Ir64Op.VectorLookupTable op0 = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI2_1B_IDX0);
        assertEquals(0, op0.idx());
        assertEquals(12, op0.rd());
        assertEquals(13, op0.rn());
        assertEquals(14, op0.rm());
    }

    @Test
    void luti2_1h() {
        Ir64Op.VectorLookupTable op = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI2_1H_IDX7);
        assertFalse(op.four());
        assertEquals(1, op.esz());
        assertEquals(7, op.idx());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());

        Ir64Op.VectorLookupTable op2 = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI2_1H_IDX2);
        assertEquals(2, op2.idx());
        assertEquals(15, op2.rd());
        assertEquals(16, op2.rn());
        assertEquals(17, op2.rm());
    }

    @Test
    void luti4_1b() {
        Ir64Op.VectorLookupTable op = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI4_1B_IDX1);
        assertTrue(op.four());
        assertEquals(0, op.esz());
        assertEquals(1, op.idx());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());

        Ir64Op.VectorLookupTable op0 = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI4_1B_IDX0);
        assertEquals(0, op0.idx());
        assertEquals(18, op0.rd());
        assertEquals(19, op0.rn());
        assertEquals(20, op0.rm());
    }

    @Test
    void luti4_2h() {
        // Armadilha 1 da task: bits[23:22] são "01" (idênticos a LUTI4_1b) — distinguir por
        // bits[14:10], nunca pelo campo de tamanho.
        Ir64Op.VectorLookupTable op = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI4_2H_IDX3);
        assertTrue(op.four());
        assertEquals(1, op.esz());
        assertEquals(3, op.idx());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());

        Ir64Op.VectorLookupTable op1 = (Ir64Op.VectorLookupTable) decodeWord(LUT_DECODER, LUTI4_2H_IDX1);
        assertEquals(1, op1.idx());
        assertEquals(21, op1.rd());
        assertEquals(22, op1.rn());
        assertEquals(23, op1.rm());
    }
}
