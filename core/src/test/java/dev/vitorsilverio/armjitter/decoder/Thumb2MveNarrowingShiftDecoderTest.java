package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.11 — `Thumb2MveNarrowingShiftDecoder`: deslocamentos estreitantes (só `b`/`h`, 32 encodings)
/// + `VSHLC` (1 encoding), `target/isa-decode/mve.decode`, linhas 656-696. Raws construídos bit a
/// bit com o MESMO layout do Javadoc da classe; cobertura dos 32 encodings estreitantes é EXAUSTIVA
/// (todas as combinações de `(U, bit7, bit0)` × `{b,h}` × `{B,T}`, não amostragem — Armadilha 1/Não
/// fazer da task).
class Thumb2MveNarrowingShiftDecoderTest {
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALFWORD = 1;

    /// `bit21=0` — os 32 encodings estreitantes. `rawShiftField` já é o valor CRU do campo (não
    /// `N - raw`; o decoder resolve isso).
    private static int rawNarrow(int u, int esz, int rawShiftField, int top, int bit7, int bit0, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        int prefix = esz == ESZ_BYTE ? (0b001 << 3) | (rawShiftField & 0x7) : (0b01 << 4) | (rawShiftField & 0xF);
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (prefix << 16)
                | (qdLow << 13) | (top << 12) | (0b1111 << 8) | (bit7 << 7) | (1 << 6) | (qmHigh << 5)
                | (qmLow << 1) | bit0;
    }

    /// `bit21=1`, `bit28=0` fixo — `VSHLC`.
    private static int rawVshlc(int imm, int qd, int rdm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111 << 29) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (1 << 21) | ((imm & 0x1F) << 16)
                | (qdLow << 13) | (0b1111 << 8) | (0b1100 << 4) | (rdm & 0xF);
    }

    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveNarrowingShiftDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    private static IrOp.MveVectorShiftNarrowImmediateInterleaved decodeNarrow(int raw) {
        return assertInstanceOf(IrOp.MveVectorShiftNarrowImmediateInterleaved.class, tryDecode(raw).liftedOp());
    }

    private static IrOp.MveVectorShiftLeftCarry decodeVshlc(int raw) {
        return assertInstanceOf(IrOp.MveVectorShiftLeftCarry.class, tryDecode(raw).liftedOp());
    }

    /// Um caso por combinação `(mnemônico, largura, B/T)` — 16 × 2 × ... na verdade a tabela abaixo
    /// já lista as 16 formas `B`; o teste parametrizado cobre `B`/`T` e `b`/`h` para cada uma, dando
    /// os 32 encodings completos (16 × 2 larguras; `top` é testado à parte para não duplicar a
    /// combinatória, ver {@link #topBitSelectsTForm()}).
    private record NarrowCase(String name, int u, int bit7, int bit0, AdvSimdShiftNarrowOp op) {
    }

    private static final List<NarrowCase> NARROW_CASES = List.of(
            new NarrowCase("VSHRN", 0, 1, 1, AdvSimdShiftNarrowOp.SHRN),
            new NarrowCase("VRSHRN", 1, 1, 1, AdvSimdShiftNarrowOp.RSHRN),
            new NarrowCase("VQSHRN_S", 0, 0, 0, AdvSimdShiftNarrowOp.SQSHRN),
            new NarrowCase("VQSHRN_U", 1, 0, 0, AdvSimdShiftNarrowOp.UQSHRN),
            new NarrowCase("VQSHRUN", 0, 1, 0, AdvSimdShiftNarrowOp.SQSHRUN),
            new NarrowCase("VQRSHRUN", 1, 1, 0, AdvSimdShiftNarrowOp.SQRSHRUN),
            new NarrowCase("VQRSHRN_S", 0, 0, 1, AdvSimdShiftNarrowOp.SQRSHRN),
            new NarrowCase("VQRSHRN_U", 1, 0, 1, AdvSimdShiftNarrowOp.UQRSHRN));

    // ── Cobertura EXAUSTIVA dos 32 encodings estreitantes (16 mnemônicos × 2 larguras) ────────────

    @Test
    void decodesAllThirtyTwoNarrowingEncodings() {
        int rawShiftField = 1;
        int tested = 0;
        for (NarrowCase c : NARROW_CASES) {
            for (int top : new int[] {0, 1}) {
                for (int esz : new int[] {ESZ_BYTE, ESZ_HALFWORD}) {
                    int raw = rawNarrow(c.u, esz, rawShiftField, top, c.bit7, c.bit0, 2, 5);
                    IrOp.MveVectorShiftNarrowImmediateInterleaved op = decodeNarrow(raw);
                    assertEquals(c.op, op.op(), c.name + " esz=" + esz + " top=" + top);
                    assertEquals(esz, op.esz());
                    assertEquals(top == 1, op.top());
                    assertEquals((8 << esz) - rawShiftField, op.shift());
                    assertEquals(2, op.qd());
                    assertEquals(5, op.qm());
                    tested++;
                }
            }
        }
        assertEquals(16, tested / 2, "16 mnemônicos (8 operações × B/T) cobertos");
        assertEquals(32, tested, "32 encodings estreitantes cobertos (16 mnemônicos × 2 larguras)");
    }

    // ── Eixo B/T (bit12) ─────────────────────────────────────────────────────────────────────────

    @Test
    void topBitSelectsTForm() {
        int rawB = rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 0, 1);
        int rawT = rawNarrow(0, ESZ_BYTE, 1, 1, 1, 1, 0, 1);
        assertEquals(false, decodeNarrow(rawB).top());
        assertEquals(true, decodeNarrow(rawT).top());
    }

    // ── Achado 3 (reuso B16.10): largura por PREFIXO, N - shift ────────────────────────────────────

    @Test
    void byteWidthComputesNMinusShiftWithNEight() {
        IrOp.MveVectorShiftNarrowImmediateInterleaved op = decodeNarrow(rawNarrow(0, ESZ_BYTE, 3, 0, 1, 1, 0, 1));
        assertEquals(0, op.esz());
        assertEquals(5, op.shift()); // 8 - 3.
    }

    @Test
    void halfwordWidthComputesNMinusShiftWithNSixteen() {
        IrOp.MveVectorShiftNarrowImmediateInterleaved op =
                decodeNarrow(rawNarrow(0, ESZ_HALFWORD, 5, 0, 1, 1, 0, 1));
        assertEquals(1, op.esz());
        assertEquals(11, op.shift()); // 16 - 5.
    }

    // ── G8: gate/prefixo/Q>7 ─────────────────────────────────────────────────────────────────────

    @Test
    void rejectsWordPrefixBit21One() {
        // bit21=1 pertence a VSHLC/VSHLL, não a esta família (ver tryDecode). bit7=0/bit0=0 aqui
        // garante que bits[7:4] != 0b1100 (VSHLC_NIBBLE_LOW), então isto também NÃO bate
        // acidentalmente no encoding de VSHLC (diferente de bit7=1/qmHigh=0/bit4=0, que bateria) —
        // exercita genuinamente "bit21=1 nunca cai no ramo de estreitamento", não um acidente de bits.
        int raw = rawNarrow(0, ESZ_BYTE, 1, 0, 0, 0, 0, 1) | (1 << 21);
        assertNull(tryDecode(raw));
    }

    @Test
    void rejectsNibbleMismatch() {
        int raw = rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 0, 1) & ~(0b1111 << 8);
        assertNull(tryDecode(raw));
    }

    @Test
    void rejectsBit6ZeroAndBit4One() {
        int base = rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 0, 1);
        assertNull(tryDecode(base & ~(1 << 6)));
        assertNull(tryDecode(base | (1 << 4)));
    }

    @Test
    void rejectsQdGreaterThanSeven() {
        assertNull(tryDecode(rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 8, 1)));
    }

    @Test
    void rejectsQmGreaterThanSeven() {
        assertNull(tryDecode(rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 0, 9)));
    }

    // ── VSHLC ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void decodesVshlcBasicFields() {
        IrOp.MveVectorShiftLeftCarry op = decodeVshlc(rawVshlc(5, 2, 3));
        assertEquals(5, op.imm());
        assertEquals(2, op.qd());
        assertEquals(3, op.rdm());
    }

    @Test
    void vshlcImmZeroIsAcceptedNotUndef() {
        // achado: imm==0 significa "desloca por 32" no helper real, não UNDEF/no-op.
        IrOp.MveVectorShiftLeftCarry op = decodeVshlc(rawVshlc(0, 2, 3));
        assertEquals(0, op.imm());
    }

    @Test
    void vshlcRejectsRdmSpAndPc() {
        assertNull(tryDecode(rawVshlc(5, 2, 13)));
        assertNull(tryDecode(rawVshlc(5, 2, 15)));
    }

    @Test
    void vshlcRejectsQdGreaterThanSeven() {
        assertNull(tryDecode(rawVshlc(5, 8, 3)));
    }

    @Test
    void vshlcDoesNotCollideWithVshllSpace() {
        // VSHLL vive no MESMO bit21=1, mas com bit7=0 — bits[7:4] de VSHLC (0b1100) nunca bate no
        // bits[7:4] real de VSHLL (bit7=0, então nunca 0b11xx).
        int vshllLike = (0b111 << 29) | (0b1110 << 24) | (1 << 23) | (1 << 21) | (0b1111 << 8) | (1 << 6);
        assertNull(tryDecode(vshllLike));
    }

    // ── Pipeline completo: nunca vira NOCP ──────────────────────────────────────────────────────

    @Test
    void fullPipelineNeverDecodesAsNocpForNarrowing() {
        int r = rawNarrow(0, ESZ_BYTE, 1, 0, 1, 1, 0, 1);
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertInstanceOf(IrOp.MveVectorShiftNarrowImmediateInterleaved.class, decoded.liftedOp());
    }

    @Test
    void fullPipelineNeverDecodesAsNocpForVshlc() {
        int r = rawVshlc(5, 2, 3);
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertInstanceOf(IrOp.MveVectorShiftLeftCarry.class, decoded.liftedOp());
    }
}
