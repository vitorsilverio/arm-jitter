package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.13a — `Thumb2MveReduceDecoder`: `VADDV`/`VADDLV`/`VABAV_S`/`VABAV_U`/`Vimm_1r`
/// (`target/isa-decode/mve.decode`, linhas 575-597, 5 encodings). Raws montados bit a bit
/// separadamente por forma (ver Javadoc da classe para o layout exato de cada uma).
class Thumb2MveReduceDecoderTest {
    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveReduceDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    private static int addVRaw(int u, int size, int rdaloRaw, int a, int qm) {
        return (0b111 << 29)
                | (u << 28)
                | (0b1110 << 24)
                | (0b1111 << 20)
                | (size << 18)
                | (0b01 << 16)
                | (rdaloRaw << 13)
                | (0b1111 << 8)
                | (a << 5)
                | (qm << 1);
    }

    private static int addLvRaw(int u, int rdahiRaw, int rdaloRaw, int a, int qm) {
        return (0b111 << 29)
                | (u << 28)
                | (0b1110 << 24)
                | (1 << 23)
                | (rdahiRaw << 20)
                | (0b1001 << 16)
                | (rdaloRaw << 13)
                | (0b1111 << 8)
                | (a << 5)
                | (qm << 1);
    }

    private static int abavRaw(int u, int size, int qn, int rda, int qm) {
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29)
                | (u << 28)
                | (0b1110 << 24)
                | (0b10 << 22)
                | (size << 20)
                | (qnLow << 17)
                | (rda << 12)
                | (0b1111 << 8)
                | (qnHigh << 7)
                | (qmHigh << 5)
                | (qmLow << 1)
                | 1;
    }

    private static int vimmRaw(int cmode, int op, int qd, int imm8) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int immHigh = (imm8 >>> 7) & 1;
        int immMid = (imm8 >>> 4) & 0x7;
        int immLow = imm8 & 0xF;
        return (0b111 << 29)
                | (immHigh << 28)
                | (0b11111 << 23)
                | (qdHigh << 22)
                | (immMid << 16)
                | (qdLow << 13)
                | (cmode << 8)
                | (0b1 << 6)
                | (op << 5)
                | (0b1 << 4)
                | immLow;
    }

    // ── VADDV / VADDLV ──────────────────────────────────────────────────────────────────────────

    @Test
    void decodesVaddvSignedAndUnsigned() {
        IrOp.MveVectorAddAcrossVector s = assertInstanceOf(IrOp.MveVectorAddAcrossVector.class,
                tryDecode(addVRaw(0, 2, 3, 1, 5)).liftedOp());
        assertEquals(false, s.unsignedForm());
        assertEquals(true, s.accumulate());
        assertEquals(2, s.size());
        assertEquals(6, s.rda());
        assertEquals(5, s.qm());

        IrOp.MveVectorAddAcrossVector u = assertInstanceOf(IrOp.MveVectorAddAcrossVector.class,
                tryDecode(addVRaw(1, 0, 2, 0, 7)).liftedOp());
        assertEquals(true, u.unsignedForm());
        assertEquals(false, u.accumulate());
        assertEquals(4, u.rda());
    }

    @Test
    void vaddvRejectsSizeThree() {
        assertNull(tryDecode(addVRaw(0, 0b11, 3, 0, 5)));
    }

    @Test
    void decodesVaddlvBeforeVaddvOnOverlap() {
        // rdahiRaw=0b111 (rdahi=15) faz bits[23:20] coincidirem com o literal de VADDV — VADDV tem
        // prioridade textual (ver Javadoc da classe); um raw ASSIM só é VADDV, nunca VADDLV.
        int overlap = addVRaw(0, 0b10, 0, 1, 3); // bits[19:16] = "1001" via size=10,bits17:16=01
        IrOp.MveVectorAddAcrossVector asVaddv = assertInstanceOf(IrOp.MveVectorAddAcrossVector.class,
                tryDecode(overlap).liftedOp());
        assertEquals(2, asVaddv.size());
    }

    @Test
    void decodesVaddlvWhenNotOverlappingVaddv() {
        IrOp.MveVectorAddAcrossVectorLong op = assertInstanceOf(IrOp.MveVectorAddAcrossVectorLong.class,
                tryDecode(addLvRaw(1, 0b011, 2, 1, 4)).liftedOp());
        assertEquals(true, op.unsignedForm());
        assertEquals(true, op.accumulate());
        assertEquals(7, op.rdahi());
        assertEquals(4, op.rdalo());
        assertEquals(4, op.qm());
    }

    @Test
    void vaddlvRejectsRdahiThirteen() {
        assertNull(tryDecode(addLvRaw(0, 0b110, 2, 0, 3))); // rdahi = 6*2+1 = 13
    }

    @Test
    void rdahiRawSevenDecodesAsVaddvNotRejected() {
        // rdahi_raw=0b111 (rdahi=15) faz este raw coincidir com o literal de VADDV (ver Javadoc da
        // classe) — decodifica como VADDV(size=2), não como VADDLV nem como recusa.
        IrOp.MveVectorAddAcrossVector op = assertInstanceOf(IrOp.MveVectorAddAcrossVector.class,
                tryDecode(addLvRaw(0, 0b111, 2, 0, 3)).liftedOp());
        assertEquals(2, op.size());
    }

    // ── VABAV ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void decodesVabavSignedAndUnsigned() {
        IrOp.MveVectorAbsoluteDifferenceAccumulate s = assertInstanceOf(
                IrOp.MveVectorAbsoluteDifferenceAccumulate.class, tryDecode(abavRaw(0, 1, 3, 5, 6)).liftedOp());
        assertEquals(false, s.unsignedForm());
        assertEquals(1, s.size());
        assertEquals(3, s.qn());
        assertEquals(5, s.rda());
        assertEquals(6, s.qm());

        IrOp.MveVectorAbsoluteDifferenceAccumulate u = assertInstanceOf(
                IrOp.MveVectorAbsoluteDifferenceAccumulate.class, tryDecode(abavRaw(1, 0, 2, 4, 1)).liftedOp());
        assertEquals(true, u.unsignedForm());
    }

    @Test
    void vabavRejectsSizeThreeAndRdaSpOrPc() {
        assertNull(tryDecode(abavRaw(0, 0b11, 1, 2, 3)));
        assertNull(tryDecode(abavRaw(0, 0, 1, 13, 3)));
        assertNull(tryDecode(abavRaw(0, 0, 1, 15, 3)));
    }

    // ── Vimm_1r ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void decodesVorrAndVbicAndVmov() {
        // cmode=0b0001 (ímpar, <12), op=0 -> VORR (=ORR).
        IrOp.MveVectorModifiedImmediate orr = assertInstanceOf(IrOp.MveVectorModifiedImmediate.class,
                tryDecode(vimmRaw(0b0001, 0, 2, 0xAB)).liftedOp());
        assertEquals(AdvSimdModifiedImmediateOp.ORR, orr.op());
        assertEquals(2, orr.qd());

        // cmode=0b0001, op=1 -> VBIC (=BIC).
        IrOp.MveVectorModifiedImmediate bic = assertInstanceOf(IrOp.MveVectorModifiedImmediate.class,
                tryDecode(vimmRaw(0b0001, 1, 2, 0xAB)).liftedOp());
        assertEquals(AdvSimdModifiedImmediateOp.BIC, bic.op());

        // cmode=0b1100 (par, >=12) -> VMOV, independente de op.
        IrOp.MveVectorModifiedImmediate mov = assertInstanceOf(IrOp.MveVectorModifiedImmediate.class,
                tryDecode(vimmRaw(0b1100, 0, 2, 0x5A)).liftedOp());
        assertEquals(AdvSimdModifiedImmediateOp.MOV, mov.op());
    }

    @Test
    void rejectsReservedCmodeFifteenOpOne() {
        assertNull(tryDecode(vimmRaw(0b1111, 1, 2, 0)));
    }
}
