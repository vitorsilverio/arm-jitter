package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.22 — `CB_cond`/`CB_cond_imm` (`FEAT_CMPBR`, ARMv9.5-A). Vetores golden montados com
/// `aarch64-linux-gnu-as -march=armv9.5-a+cmpbr` (WSL/Ubuntu, `binutils 2.46` — a extensão É aceita
/// por um assembler estável, ao contrário do que a task antecipava como risco). Layout de bits
/// confirmado byte a byte contra a disassembly do próprio `objdump`: prefixo comum de 6 bits
/// `0b111010` (bits[30:25], vizinho de `CBZ`=`0b011010`/`TBZ`=`0b011011`, nunca colide); `bit24`
/// distingue forma registrador (`0`) de forma imediata (`1`).
class Aarch64CompareAndBranchConditionalDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder CMPBR_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);

    // ── forma registrador, X (esz=doubleword), offset +0x1c ────────────────────────────────────
    private static final int CBGT_X1_X2 = 0xf40200e1;
    private static final int CBGE_X1_X2 = 0xf42200c1;
    private static final int CBHI_X1_X2 = 0xf44200a1;
    private static final int CBHS_X1_X2 = 0xf4620081;
    private static final int CBEQ_X1_X2 = 0xf4c20061;
    private static final int CBNE_X1_X2 = 0xf4e20041;
    // forma registrador, W (esz=word)
    private static final int CBGT_W3_W4 = 0x74040023;
    // reservado: cc=4 (mesmo CBGT_X1_X2, byte cc+Rm trocado de 0x02 p/ 0x82)
    private static final int CB_COND_RESERVED_CC4 = 0xf48200e1;
    // reservado: eszField=01 (mesmo CBGT_X1_X2, byte do meio 0x00 -> 0x40)
    private static final int CB_COND_RESERVED_ESZ01 = 0xf40240e1;

    // ── forma registrador, CBB (byte)/CBH (halfword), offset +0x20 ─────────────────────────────
    private static final int CBBGT_W1_W2 = 0x74028101;
    private static final int CBBGE_W1_W2 = 0x742280e1;
    private static final int CBBHI_W1_W2 = 0x744280c1;
    private static final int CBBHS_W1_W2 = 0x746280a1;
    private static final int CBBEQ_W1_W2 = 0x74c28081;
    private static final int CBBNE_W1_W2 = 0x74e28061;
    private static final int CBHGT_W1_W2 = 0x7402c041;
    private static final int CBHNE_W1_W2 = 0x74e2c021;

    // ── forma imediata, X (sf=1), #5, offset +0x1c ──────────────────────────────────────────────
    private static final int CBGT_IMM_X1_5 = 0xf50280e1;
    private static final int CBLT_IMM_X1_5 = 0xf52280c1;
    private static final int CBHI_IMM_X1_5 = 0xf54280a1;
    private static final int CBLO_IMM_X1_5 = 0xf5628081;
    private static final int CBEQ_IMM_X1_5 = 0xf5c28061;
    private static final int CBNE_IMM_X1_5 = 0xf5e28041;
    // forma imediata, W (sf=0), #63 (máximo de imm6)
    private static final int CBGT_IMM_W3_63 = 0x751f8023;
    // reservado: cc=4 (mesmo CBGT_IMM_X1_5, byte cc+imm6-alto trocado de 0x02 p/ 0x82)
    private static final int CB_COND_IMM_RESERVED_CC4 = 0xf58280e1;
    // reservado: bit[14]=1 (mesmo CBGT_IMM_X1_5, byte do meio 0x80 -> 0xc0)
    private static final int CB_COND_IMM_RESERVED_BIT14 = 0xf502c0e1;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── gate de feature ─────────────────────────────────────────────────────────────────────────

    @Test
    void gatedByCompareAndBranchFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CBGT_X1_X2));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CBGT_IMM_X1_5));
    }

    // ── forma registrador ───────────────────────────────────────────────────────────────────────

    @Test
    void decodesAllSixConditionsRegisterForm() {
        // Vetores gerados por 6 instruções SEGUIDAS num `.s` só, todas desviando para o MESMO
        // `lbl` absoluto — como o helper `decode` sempre decodifica a partir do endereço `0`, o
        // TARGET esperado aqui é o próprio deslocamento (`imm9`), que difere por instrução (cada
        // uma tinha um endereço de origem diferente no `.s` original).
        record Case(int word, Ir64CompareBranchCondition condition, long target) {
        }
        Case[] cases = {
                new Case(CBGT_X1_X2, Ir64CompareBranchCondition.GREATER_THAN, 0x1cL),
                new Case(CBGE_X1_X2, Ir64CompareBranchCondition.GREATER_OR_EQUAL, 0x18L),
                new Case(CBHI_X1_X2, Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 0x14L),
                new Case(CBHS_X1_X2, Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED, 0x10L),
                new Case(CBEQ_X1_X2, Ir64CompareBranchCondition.EQUAL, 0xcL),
                new Case(CBNE_X1_X2, Ir64CompareBranchCondition.NOT_EQUAL, 0x8L),
        };
        for (Case c : cases) {
            Ir64Op.CompareAndBranchRegister op =
                    (Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, c.word());
            String label = "word=0x" + Integer.toHexString(c.word());
            assertEquals(c.condition(), op.condition(), label);
            assertEquals(1, op.rt(), label);
            assertEquals(2, op.rm(), label);
            assertEquals(Ir64MemSize.DOUBLEWORD, op.size(), label);
            assertEquals(c.target(), op.target(), label);
        }
    }

    @Test
    void registerFormNarrowIsWord() {
        Ir64Op.CompareAndBranchRegister op = (Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBGT_W3_W4);
        assertEquals(Ir64MemSize.WORD, op.size());
        assertEquals(3, op.rt());
        assertEquals(4, op.rm());
        assertEquals(0x4L, op.target());
    }

    @Test
    void registerFormCbbIsByte() {
        Ir64Op.CompareAndBranchRegister gt = (Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBGT_W1_W2);
        assertEquals(Ir64MemSize.BYTE, gt.size());
        assertEquals(Ir64CompareBranchCondition.GREATER_THAN, gt.condition());
        assertEquals(1, gt.rt());
        assertEquals(2, gt.rm());
        assertEquals(0x20L, gt.target());

        assertEquals(Ir64CompareBranchCondition.GREATER_OR_EQUAL,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBGE_W1_W2)).condition());
        assertEquals(Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBHI_W1_W2)).condition());
        assertEquals(Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBHS_W1_W2)).condition());
        assertEquals(Ir64CompareBranchCondition.EQUAL,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBEQ_W1_W2)).condition());
        assertEquals(Ir64CompareBranchCondition.NOT_EQUAL,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBBNE_W1_W2)).condition());
    }

    @Test
    void registerFormCbhIsHalfword() {
        Ir64Op.CompareAndBranchRegister gt = (Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBHGT_W1_W2);
        assertEquals(Ir64MemSize.HALF, gt.size());
        assertEquals(Ir64CompareBranchCondition.GREATER_THAN, gt.condition());

        assertEquals(Ir64CompareBranchCondition.NOT_EQUAL,
                ((Ir64Op.CompareAndBranchRegister) decode(CMPBR_DECODER, CBHNE_W1_W2)).condition());
    }

    @Test
    void registerFormReservedConditionIsRejected() {
        assertThrows(UnsupportedOperationException.class, () -> decode(CMPBR_DECODER, CB_COND_RESERVED_CC4));
    }

    @Test
    void registerFormReservedEszFieldIsRejected() {
        assertThrows(UnsupportedOperationException.class, () -> decode(CMPBR_DECODER, CB_COND_RESERVED_ESZ01));
    }

    // ── forma imediata ──────────────────────────────────────────────────────────────────────────

    @Test
    void decodesAllSixConditionsImmediateForm() {
        // Mesma disciplina de `decodesAllSixConditionsRegisterForm`: TARGET esperado é o próprio
        // deslocamento, não um endereço absoluto fixo (o helper `decode` decodifica sempre a
        // partir do endereço `0`, mas cada instrução do `.s` original tinha um endereço diferente).
        record Case(int word, Ir64CompareBranchCondition condition, long target) {
        }
        Case[] cases = {
                new Case(CBGT_IMM_X1_5, Ir64CompareBranchCondition.GREATER_THAN, 0x1cL),
                new Case(CBLT_IMM_X1_5, Ir64CompareBranchCondition.LESS_THAN, 0x18L),
                new Case(CBHI_IMM_X1_5, Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 0x14L),
                new Case(CBLO_IMM_X1_5, Ir64CompareBranchCondition.LESS_THAN_UNSIGNED, 0x10L),
                new Case(CBEQ_IMM_X1_5, Ir64CompareBranchCondition.EQUAL, 0xcL),
                new Case(CBNE_IMM_X1_5, Ir64CompareBranchCondition.NOT_EQUAL, 0x8L),
        };
        for (Case c : cases) {
            Ir64Op.CompareAndBranchImmediate op =
                    (Ir64Op.CompareAndBranchImmediate) decode(CMPBR_DECODER, c.word());
            String label = "word=0x" + Integer.toHexString(c.word());
            assertEquals(c.condition(), op.condition(), label);
            assertEquals(1, op.rt(), label);
            assertTrue(op.wide(), label);
            assertEquals(5, op.immediate(), label);
            assertEquals(c.target(), op.target(), label);
        }
    }

    @Test
    void immediateFormNarrowAndMaxImmediate() {
        Ir64Op.CompareAndBranchImmediate op =
                (Ir64Op.CompareAndBranchImmediate) decode(CMPBR_DECODER, CBGT_IMM_W3_63);
        assertEquals(3, op.rt());
        assertEquals(63, op.immediate(), "imm6 é UInt, nunca estendido com sinal");
        assertEquals(0x4L, op.target());
        assertEquals(Ir64CompareBranchCondition.GREATER_THAN, op.condition());
        assertEquals(false, op.wide());
    }

    @Test
    void immediateFormReservedConditionIsRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> decode(CMPBR_DECODER, CB_COND_IMM_RESERVED_CC4));
    }

    @Test
    void immediateFormReservedBit14IsRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> decode(CMPBR_DECODER, CB_COND_IMM_RESERVED_BIT14));
    }
}
