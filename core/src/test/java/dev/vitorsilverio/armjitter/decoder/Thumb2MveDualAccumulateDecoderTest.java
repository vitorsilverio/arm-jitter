package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.13b — `Thumb2MveDualAccumulateDecoder`: `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV`/`VMLALDAV_S`/
/// `VMLALDAV_U`/`VMLSLDAV`/`VRMLALDAVH_S`/`VRMLALDAVH_U`/`VRMLSLDAVH`/`VMAXV_S`/`VMAXV_U`/
/// `VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV`/`VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV`
/// (`target/isa-decode/mve.decode`, linhas 422-494, 28 encodings). Os literais de cada forma
/// espelham as constantes `MASK`/`VALUE` do decoder (derivadas bit a bit do arquivo real via
/// `curl` + script de overlap, ver Javadoc da classe).
class Thumb2MveDualAccumulateDecoderTest {
    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveDualAccumulateDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    private static int embedQn(int qn) {
        return (((qn >>> 3) & 1) << 7) | ((qn & 0x7) << 17);
    }

    private static int embedQm(int qm) {
        return (qm & 0x7) << 1;
    }

    // ── VMLADAV_S / VMLALDAV_S (forma geral, 1º bloco `{}`) ────────────────────────────────────────

    private static int mladavSGeneralRaw(int size16, int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (0 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (size16 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm);
    }

    private static int mlaldavSRaw(int size16, int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b111 << 29) | (0 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn)
                | (size16 << 16) | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm);
    }

    @Test
    void decodesVmladavSAndVmlaldavS() {
        IrOp.MveVectorDualAccumulate op = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mladavSGeneralRaw(1, 3, 1, 1, 5, 2)).liftedOp());
        assertFalse(op.unsignedForm());
        assertFalse(op.subtract());
        assertTrue(op.exchange());
        assertTrue(op.accumulate());
        assertEquals(2, op.size());
        assertEquals(3, op.qn());
        assertEquals(5, op.qm());
        assertEquals(4, op.rda());

        IrOp.MveVectorDualAccumulateLong lv = assertInstanceOf(IrOp.MveVectorDualAccumulateLong.class,
                tryDecode(mlaldavSRaw(0, 6, 0, 0, 2, 0b011, 4)).liftedOp());
        assertFalse(lv.unsignedForm());
        assertFalse(lv.subtract());
        assertFalse(lv.exchange());
        assertFalse(lv.accumulate());
        assertEquals(1, lv.size());
        assertEquals(6, lv.qn());
        assertEquals(2, lv.qm());
        assertEquals(7, lv.rdahi());
        assertEquals(8, lv.rdalo());
    }

    @Test
    void mlaldavSRejectsRdahiThirteen() {
        assertNull(tryDecode(mlaldavSRaw(0, 6, 0, 0, 2, 0b110, 4))); // rdahi = 13
    }

    @Test
    void rdahiRawSevenDecodesAsVmladavSNotRejected() {
        // rdahi_raw=0b111 (rdahi=15) faz bits[23:20] coincidirem com o literal de VMLADAV_S (mesma
        // classe de achado do VADDV/VADDLV na B16.13a): o primeiro `{}` do arquivo real lista
        // VMLADAV_S ANTES de VMLALDAV_S, então este raw decodifica como VMLADAV_S (32 bits), nunca
        // chega a ser rejeitado como VMLALDAV_S com rdahi=15.
        IrOp.MveVectorDualAccumulate op = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mlaldavSRaw(0, 6, 0, 0, 2, 0b111, 4)).liftedOp());
        assertEquals(8, op.rda());
    }

    // ── VMLADAV_U / VMLALDAV_U (2º bloco `{}`) — sem forma exchange ────────────────────────────────

    private static int mladavUGeneralRaw(int size16, int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (1 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (size16 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm);
    }

    private static int mlaldavURaw(int size16, int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b111 << 29) | (1 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn)
                | (size16 << 16) | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm);
    }

    @Test
    void decodesVmladavUAndVmlaldavU() {
        IrOp.MveVectorDualAccumulate op = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mladavUGeneralRaw(0, 2, 0, 1, 3, 1)).liftedOp());
        assertTrue(op.unsignedForm());
        assertFalse(op.subtract());
        assertFalse(op.exchange());

        IrOp.MveVectorDualAccumulateLong lv = assertInstanceOf(IrOp.MveVectorDualAccumulateLong.class,
                tryDecode(mlaldavURaw(1, 1, 0, 1, 4, 0b001, 3)).liftedOp());
        assertTrue(lv.unsignedForm());
        assertEquals(3, lv.rdahi());
    }

    @Test
    void vmladavUAndVmlaldavURejectExchange() {
        assertNull(tryDecode(mladavUGeneralRaw(0, 2, 1, 1, 3, 1)));
        assertNull(tryDecode(mlaldavURaw(1, 1, 1, 1, 4, 0b001, 3)));
    }

    // ── VMLSDAV / VMLSLDAV (3º bloco `{}`, general) — sempre assinado ──────────────────────────────

    private static int mlsdavGeneralRaw(int size16, int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (0 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (size16 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    private static int mlsldavRaw(int size16, int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b111 << 29) | (0 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn)
                | (size16 << 16) | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    @Test
    void decodesVmlsdavAndVmlsldav() {
        IrOp.MveVectorDualAccumulate op = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mlsdavGeneralRaw(1, 2, 1, 1, 3, 2)).liftedOp());
        assertFalse(op.unsignedForm());
        assertTrue(op.subtract());
        assertTrue(op.exchange());

        IrOp.MveVectorDualAccumulateLong lv = assertInstanceOf(IrOp.MveVectorDualAccumulateLong.class,
                tryDecode(mlsldavRaw(0, 1, 1, 0, 2, 0b010, 3)).liftedOp());
        assertTrue(lv.subtract());
        assertTrue(lv.exchange());
    }

    // ── VMLSDAV (nosz, 4º bloco `{}`) / VRMLSLDAVH — top byte "1111 1110" mas SEMPRE assinado ──────

    private static int mlsdavNoszRaw(int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (1 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (0 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    private static int vrmlsldavhRaw(int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b111 << 29) | (1 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn)
                | (0 << 16) | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    @Test
    void decodesVmlsdavNoszAndVrmlsldavhAlwaysSigned() {
        IrOp.MveVectorDualAccumulate op = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mlsdavNoszRaw(4, 1, 1, 2, 1)).liftedOp());
        assertFalse(op.unsignedForm(), "VMLSDAV é sempre assinado mesmo com top byte 1111 1110");
        assertTrue(op.subtract());
        assertEquals(0, op.size());

        IrOp.MveVectorRoundingDualAccumulateHigh rh = assertInstanceOf(
                IrOp.MveVectorRoundingDualAccumulateHigh.class,
                tryDecode(vrmlsldavhRaw(3, 1, 0, 5, 0b001, 2)).liftedOp());
        assertFalse(rh.unsignedForm(), "VRMLSLDAVH é sempre assinado mesmo com top byte 1111 1110");
        assertTrue(rh.subtract());
        assertTrue(rh.exchange());
        assertEquals(3, rh.rdahi());
        assertEquals(4, rh.rdalo());
    }

    // ── VMLADAV_S / VMLADAV_U "soltas" (byte-only, bit0=1) ──────────────────────────────────────────

    private static int mladavSLooseRaw(int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (0 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (0 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1111 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    private static int mladavULooseRaw(int qn, int x, int a, int qm, int rdaloRaw) {
        return (0b111 << 29) | (1 << 28) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (0 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1111 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    @Test
    void decodesLooseByteFormsOfVmladav() {
        IrOp.MveVectorDualAccumulate s = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mladavSLooseRaw(2, 0, 1, 3, 1)).liftedOp());
        assertFalse(s.unsignedForm());
        assertEquals(0, s.size());

        IrOp.MveVectorDualAccumulate u = assertInstanceOf(IrOp.MveVectorDualAccumulate.class,
                tryDecode(mladavULooseRaw(2, 0, 1, 3, 1)).liftedOp());
        assertTrue(u.unsignedForm());
    }

    // ── VMAXV/VMINV/VMAXAV/VMINAV/VMAXNMV/VMINNMV/VMAXNMAV/VMINNMAV (blocos S/U) ────────────────────

    private static int embedQmVmaxv(int qm) {
        return (((qm >>> 3) & 1) << 5) | ((qm & 0x7) << 1);
    }

    private static int intMinMaxSRaw(int selector, int size, int rda, int qm) {
        return (0b1110 << 28) | (0b1110 << 24) | (0b1110 << 20) | (selector << 16) | (size << 18)
                | (rda << 12) | (0b1111 << 8) | embedQmVmaxv(qm);
    }

    private static int intMinMaxURaw(int selector, int size, int rda, int qm) {
        return (0b1111 << 28) | (0b1110 << 24) | (0b1110 << 20) | (selector << 16) | (size << 18)
                | (rda << 12) | (0b1111 << 8) | embedQmVmaxv(qm);
    }

    private static int fpMinMaxSRaw(int bits19_16, int rda, int qm) {
        return (0b1110 << 28) | (0b1110 << 24) | (0b1110 << 20) | (bits19_16 << 16) | (rda << 12)
                | (0b1111 << 8) | embedQmVmaxv(qm);
    }

    private static int fpMinMaxURaw(int bits19_16, int rda, int qm) {
        return (0b1111 << 28) | (0b1110 << 24) | (0b1110 << 20) | (bits19_16 << 16) | (rda << 12)
                | (0b1111 << 8) | embedQmVmaxv(qm);
    }

    @Test
    void decodesVmaxvAndVminvSignedAndUnsigned() {
        // bits[17:16]=10 -> VMAXV_S/VMINV_S; bit7 (dentro do byte 0x0F ou 0x8F) seleciona max/min.
        IrOp.MveVectorMinMaxAcrossVector maxS = assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                tryDecode(intMinMaxSRaw(0b10, 1, 5, 3)).liftedOp());
        assertTrue(maxS.max());
        assertFalse(maxS.unsignedForm());
        assertFalse(maxS.absoluteForm());
        assertEquals(1, maxS.size());
        assertEquals(5, maxS.rda());
        assertEquals(3, maxS.qm());

        IrOp.MveVectorMinMaxAcrossVector minS = assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                tryDecode(intMinMaxSRaw(0b10, 2, 6, 4) | (1 << 7)).liftedOp());
        assertFalse(minS.max());

        IrOp.MveVectorMinMaxAcrossVector maxU = assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                tryDecode(intMinMaxURaw(0b10, 0, 2, 1)).liftedOp());
        assertTrue(maxU.unsignedForm());
    }

    @Test
    void decodesVmaxavAndVminav() {
        // bits[17:16]=00 -> VMAXAV/VMINAV, absoluteForm=true, sem unsignedForm próprio.
        IrOp.MveVectorMinMaxAcrossVector maxav = assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                tryDecode(intMinMaxSRaw(0b00, 2, 3, 6)).liftedOp());
        assertTrue(maxav.max());
        assertTrue(maxav.absoluteForm());
        assertFalse(maxav.unsignedForm());
    }

    @Test
    void intMinMaxRejectsRdaSpOrPc() {
        assertNull(tryDecode(intMinMaxSRaw(0b10, 1, 13, 2)));
        assertNull(tryDecode(intMinMaxSRaw(0b10, 1, 15, 2)));
    }

    @Test
    void intMinMaxSizeThreeCoincidesWithFpFormUnderFullFeatureSet() {
        // bits[19:16]="size ++ 10" com size=0b11 vira "1110", o MESMO literal de VMAXNMV_S — sob um
        // preset com MVE_FLOAT (como ARMV8_1M_MVE), este raw decodifica como VMAXNMV_S, nunca chega
        // a ser "VMAXV_S com size=3": não existe combinação de bits capaz de pedir size=3 para
        // VMAXV_S/VMAXAV sem coincidir com um dos 8 literais FP do mesmo bloco (achado real,
        // verificado bit a bit, mesma classe de VADDV/VADDLV na B16.13a).
        IrOp.MveVectorFpMinMaxAcrossVector fp = assertInstanceOf(IrOp.MveVectorFpMinMaxAcrossVector.class,
                tryDecode(intMinMaxSRaw(0b10, 0b11, 3, 2)).liftedOp());
        assertTrue(fp.max());
        assertEquals(2, fp.esz());
    }

    @Test
    void intMinMaxSizeThreeIsRejectedWhenMveFloatAbsent() {
        // Sem MVE_FLOAT, a colisão acima não é reivindicada por nenhuma forma FP — o raw chega a
        // tryIntMinMax de verdade e é corretamente recusado por size==3 (G8).
        ArmArchitecture integerOnly = ArmArchitecture.extending(ArmArchitecture.ARMV8_1M, "test-mve-integer-only",
                ArmFeature.MVE_INTEGER);
        int r = intMinMaxSRaw(0b10, 0b11, 3, 2);
        assertNull(new Thumb2MveDualAccumulateDecoder(integerOnly).tryDecode(r, 0, Condition.AL));
    }

    @Test
    void decodesFpMinMaxWithCorrectPrecisionPerBlock() {
        // Bloco S (top byte EE): VMAXNMV/VMINNMV/VMAXNMAV/VMINNMAV com esz=2 (binary32).
        IrOp.MveVectorFpMinMaxAcrossVector maxnmvS = assertInstanceOf(IrOp.MveVectorFpMinMaxAcrossVector.class,
                tryDecode(fpMinMaxSRaw(0b1110, 4, 2)).liftedOp());
        assertTrue(maxnmvS.max());
        assertFalse(maxnmvS.absoluteForm());
        assertEquals(2, maxnmvS.esz());

        IrOp.MveVectorFpMinMaxAcrossVector maxnmavS = assertInstanceOf(IrOp.MveVectorFpMinMaxAcrossVector.class,
                tryDecode(fpMinMaxSRaw(0b1100, 4, 2)).liftedOp());
        assertTrue(maxnmavS.absoluteForm());

        // Bloco U (top byte FE): mesmas 4 formas com esz=1 (binary16) — o bit que normalmente
        // distingue S/U aqui escolhe PRECISÃO, não sinal.
        IrOp.MveVectorFpMinMaxAcrossVector maxnmvU = assertInstanceOf(IrOp.MveVectorFpMinMaxAcrossVector.class,
                tryDecode(fpMinMaxURaw(0b1110, 4, 2)).liftedOp());
        assertEquals(1, maxnmvU.esz());
    }

    @Test
    void fpMinMaxRequiresMveFloat() {
        ArmArchitecture integerOnly = ArmArchitecture.extending(ArmArchitecture.ARMV8_1M, "test-mve-integer-only",
                ArmFeature.MVE_INTEGER);
        int r = fpMinMaxSRaw(0b1110, 4, 2);
        assertNull(new Thumb2MveDualAccumulateDecoder(integerOnly).tryDecode(r, 0, Condition.AL));
        // A forma inteira correspondente (VMAXV/VMINV etc.) continua decodificando sob MVE_INTEGER.
        assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                new Thumb2MveDualAccumulateDecoder(integerOnly).tryDecode(intMinMaxSRaw(0b10, 1, 5, 3), 0,
                        Condition.AL).liftedOp());
    }

    // ── Ordem dos grupos aninhados `[...]` (Armadilha 3) ────────────────────────────────────────────

    @Test
    void nestedGroupOrderPicksMostSpecificFirst() {
        // bits[19:16]=1110 satisfaz VMAXNMV/VMINNMV (mais específico) — TEM que decodificar como
        // FpMinMax, nunca cair no catch-all VMLADAV_S/VRMLALDAVH_S (achatar a ordem roubaria isto).
        assertInstanceOf(IrOp.MveVectorFpMinMaxAcrossVector.class, tryDecode(fpMinMaxSRaw(0b1110, 4, 2)).liftedOp());
        // bits[17:16]=10, bits[19:18] livres -> VMAXV_S/VMINV_S (2º grupo), não o catch-all.
        assertInstanceOf(IrOp.MveVectorMinMaxAcrossVector.class,
                tryDecode(intMinMaxSRaw(0b10, 1, 5, 3)).liftedOp());
    }

    @Test
    void requiresMveIntegerFeature() {
        int r = mladavSGeneralRaw(1, 3, 0, 1, 5, 2);
        assertNull(new Thumb2MveDualAccumulateDecoder(ArmArchitecture.ARMV7A).tryDecode(r, 0, Condition.AL));
    }

    @Test
    void rejectsQnInHighBank() {
        assertNull(tryDecode(mladavSGeneralRaw(1, 8, 0, 1, 5, 2)));
    }
}
