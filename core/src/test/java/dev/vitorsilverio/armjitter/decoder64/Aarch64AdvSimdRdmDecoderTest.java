package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `SQRDMLAH`/`SQRDMLSH` (`FEAT_RDM`, `ARMv8.1-A`, B11.4) — primeiro gate real de
/// {@link Aarch64Architecture#has} no {@link Aarch64Decoder}: as 24 formas abaixo (vetorial não-
/// indexado, escalar não-indexado, vetorial indexado, escalar indexado) já caíam em
/// `UnsupportedOperationException` antes desta task (sem colisão de decode com `EXT`/permute/TBL/
/// copy/SHA no mesmo espaço `bit21=0`, ver a task) — continuam caindo assim no decoder DEFAULT
/// (`ARMv8.0-A`, prova de G3), e passam a decodificar corretamente só com `ARMv8.1-A`+. Corpus REAL
/// via `aarch64-none-elf-as -march=armv8.1-a`/`objdump` (devkitA64).
class Aarch64AdvSimdRdmDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder RDM_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);

    /// Todas as 24 palavras do corpus desta task (não-indexado vetorial ×8, não-indexado escalar
    /// ×4, indexado vetorial ×8, indexado escalar ×4).
    private static final int[] ALL_WORDS = {
            0x2e428420, 0x6e458483, 0x2e8884e6, 0x6e8b8549,
            0x2e428c20, 0x6e458c83, 0x2e888ce6, 0x6e8b8d49,
            0x7e428420, 0x7e858483, 0x7e428c20, 0x7e858c83,
            0x2f72d020, 0x6f75d883, 0x2fa8d0e6, 0x6fabd949,
            0x2f72f020, 0x6f75f883, 0x2fa8f0e6, 0x6fabf949,
            0x7f72d020, 0x7fa5d083, 0x7f72f020, 0x7fa5f083,
    };

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutRdm() {
        for (int word : ALL_WORDS) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, word),
                    "0x" + Integer.toHexString(word) + " deveria continuar unsupported sem FEAT_RDM");
        }
    }

    // ── Vetorial não-indexado ───────────────────────────────────────────────────────────────────

    @Test
    void sqrdmlahVector4h() {
        // 2e428420: sqrdmlah v0.4h, v1.4h, v2.4h
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x2e428420);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sqrdmlahVector8h() {
        // 6e458483: sqrdmlah v3.8h, v4.8h, v5.8h
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x6e458483);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void sqrdmlahVector2s() {
        // 2e8884e6: sqrdmlah v6.2s, v7.2s, v8.2s
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x2e8884e6);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    @Test
    void sqrdmlahVector4s() {
        // 6e8b8549: sqrdmlah v9.4s, v10.4s, v11.4s
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x6e8b8549);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());
    }

    @Test
    void sqrdmlshVector4h() {
        // 2e428c20: sqrdmlsh v0.4h, v1.4h, v2.4h
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x2e428c20);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sqrdmlshVector4s() {
        // 6e8b8d49: sqrdmlsh v9.4s, v10.4s, v11.4s
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x6e8b8d49);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());
    }

    // ── Escalar não-indexado ────────────────────────────────────────────────────────────────────

    @Test
    void sqrdmlahScalarHalfword() {
        // 7e428420: sqrdmlah h0, h1, h2
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x7e428420);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sqrdmlahScalarWord() {
        // 7e858483: sqrdmlah s3, s4, s5
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x7e858483);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void sqrdmlshScalarHalfword() {
        // 7e428c20: sqrdmlsh h0, h1, h2
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x7e428c20);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
    }

    @Test
    void sqrdmlshScalarWord() {
        // 7e858c83: sqrdmlsh s3, s4, s5
        Ir64Op.VectorArithmeticThreeSame op =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(RDM_DECODER, 0x7e858c83);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
    }

    // ── Vetorial indexado ───────────────────────────────────────────────────────────────────────

    @Test
    void sqrdmlahIndexedVector4h() {
        // 2f72d020: sqrdmlah v0.4h, v1.4h, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x2f72d020);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void sqrdmlahIndexedVector8h() {
        // 6f75d883: sqrdmlah v3.8h, v4.8h, v5.h[7]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x6f75d883);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
        assertEquals(7, op.index());
    }

    @Test
    void sqrdmlahIndexedVector2s() {
        // 2fa8d0e6: sqrdmlah v6.2s, v7.2s, v8.s[1]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x2fa8d0e6);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
        assertEquals(1, op.index());
    }

    @Test
    void sqrdmlahIndexedVector4s() {
        // 6fabd949: sqrdmlah v9.4s, v10.4s, v11.s[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x6fabd949);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void sqrdmlshIndexedVector4h() {
        // 2f72f020: sqrdmlsh v0.4h, v1.4h, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x2f72f020);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void sqrdmlshIndexedVector4s() {
        // 6fabf949: sqrdmlsh v9.4s, v10.4s, v11.s[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x6fabf949);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());
        assertEquals(3, op.index());
    }

    // ── Escalar indexado ────────────────────────────────────────────────────────────────────────

    @Test
    void sqrdmlahIndexedScalarHalfword() {
        // 7f72d020: sqrdmlah h0, h1, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x7f72d020);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void sqrdmlahIndexedScalarWord() {
        // 7fa5d083: sqrdmlah s3, s4, v5.s[1]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x7fa5d083);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
        assertEquals(1, op.index());
    }

    @Test
    void sqrdmlshIndexedScalarHalfword() {
        // 7f72f020: sqrdmlsh h0, h1, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x7f72f020);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void sqrdmlshIndexedScalarWord() {
        // 7fa5f083: sqrdmlsh s3, s4, v5.s[1]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(RDM_DECODER, 0x7fa5f083);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
        assertEquals(1, op.index());
    }
}
