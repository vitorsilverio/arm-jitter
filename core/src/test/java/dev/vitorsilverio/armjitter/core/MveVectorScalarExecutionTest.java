package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.9 — operações escalares (vetor × GPR broadcast), fim-a-fim (decode + lift + executor
/// interpretado) sobre o preset real `ARMV8_1M_MVE`. Cobre os itens do Aceite que exigem execução:
/// os dois valores de `bit28` de `VMLA`/`VMLAS` produzindo a MESMA instrução, `@shl_scalar` usando
/// `Qda` como fonte E destino com `Rm` como CONTAGEM, broadcast do escalar nas 3 larguras (incluindo
/// truncamento de um `Rm` de 32 bits), `FPSCR.QC` só em lane ATIVA, e a ordem de operandos REAL
/// (medida no QEMU) de `VMLA`/`VMLAS`/`VFMA_scalar`/`VFMAS_scalar`/`VQDMLAH`/`VQDMLASH`.
class MveVectorScalarExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int raw2scalar(int u, int size, int qd, int qn, int bit16, int bit12, int nibble, int nibble3,
            int rm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (size << 20) | (qnLow << 17)
                | (bit16 << 16) | (qdLow << 13) | (bit12 << 12) | (nibble << 8) | (qnHigh << 7) | (nibble3 << 4)
                | rm;
    }

    private static int rawShl(int u, int size, int qd, int round, int sat, int rm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (0b11 << 20) | (size << 18)
                | (round << 17) | (1 << 16) | (qdLow << 13) | (1 << 12) | (0b1110 << 8) | (sat << 7) | (0b110 << 4)
                | rm;
    }

    // ── Broadcast do escalar (Aceite bullet: replicado por lane conforme size) ──────────────────

    @Test
    void broadcastsRmAcrossAllByteLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0101_0101_0101_0101L, 0L); // Qn: 1 em todo byte.
        core.setRegister(3, 5); // Rm = 5.
        // VADD_scalar, esz=0 (byte), Qd=1, Qn=2, Rm=3.
        int r = raw2scalar(0, 0, 1, 2, 1, 0, 0b1111, 0b100, 3);
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 8; i++) {
            assertEquals(6, core.vfp().element(1, i, 0), "byte " + i + ": 1+5=6, MESMO Rm replicado");
        }
    }

    @Test
    void truncatesA32BitRmToTheElementWidth() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0L, 0L); // Qn = 0 em toda lane.
        core.setRegister(3, 0x1234_5678); // Rm de 32 bits; só o halfword baixo (0x5678) deve valer.
        // VADD_scalar, esz=1 (halfword), Qd=1, Qn=2, Rm=3.
        int r = raw2scalar(0, 1, 1, 2, 1, 0, 0b1111, 0b100, 3);
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(0x5678, core.vfp().element(1, i, 1), "halfword " + i + ": Rm truncado a 16 bits");
        }
    }

    // ── @shl_scalar: Qda fonte+destino, Rm é a CONTAGEM (não um valor replicado) ────────────────

    @Test
    void vshlScalarShiftsQdaByTheRmCountNotAReplicatedValue() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x11L, 0L); // Qda inicial (byte 0 = 0x11).
        core.setRegister(5, 2); // Rm = contagem de deslocamento (2), NÃO um valor a somar/etc.
        // VSHL_S_scalar, esz=0 (byte), Qda=3, Rm=5.
        int r = rawShl(0, 0, 3, 0, 0, 5);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals((0x11 << 2) & 0xFF, core.vfp().element(3, 0, 0),
                "Qda deslocado por Rm=2 (contagem), diferente de VSHL_S por vetor (B16.6)");
    }

    // ── VMLA / VMLAS: "111 -" e a ordem REAL de operandos ───────────────────────────────────────

    @Test
    void vmlaAndVmlasProduceDifferentResultsFromTheSameInputsDueToOperandOrder() {
        // VMLA: Qd = Qd + Qn*Rm. VMLAS: Qd = Qn*Qd + Rm (papéis de Qd/Rm trocados).
        ArmCore coreMla = newCore();
        coreMla.vfp().setQ(1, 10L, 0L); // Qd inicial = 10.
        coreMla.vfp().setQ(2, 3L, 0L); // Qn = 3.
        coreMla.setRegister(4, 7); // Rm = 7.
        int rMla = raw2scalar(0, 0, 1, 2, 1, 0, 0b1110, 0b100, 4); // VMLA, esz=0, Qd=1, Qn=2, Rm=4.
        put32(coreMla, CODE_BASE, rMla);
        coreMla.step();
        assertEquals((10 + 3 * 7) & 0xFF, coreMla.vfp().element(1, 0, 0), "VMLA: Qd + Qn*Rm");

        ArmCore coreMlas = newCore();
        coreMlas.vfp().setQ(1, 10L, 0L); // Qd inicial (o MESMO valor é o multiplicador aqui).
        coreMlas.vfp().setQ(2, 3L, 0L);
        coreMlas.setRegister(4, 7);
        int rMlas = raw2scalar(0, 0, 1, 2, 1, 1, 0b1110, 0b100, 4); // VMLAS, mesmo layout, bit12=1.
        put32(coreMlas, CODE_BASE, rMlas);
        coreMlas.step();
        assertEquals((3 * 10 + 7) & 0xFF, coreMlas.vfp().element(1, 0, 0), "VMLAS: Qn*Qd + Rm");
    }

    @Test
    void vmlaDecodesTheSameForBothValuesOfBit28AndExecutesIdentically() {
        ArmCore coreU0 = newCore();
        coreU0.vfp().setQ(1, 2L, 0L);
        coreU0.vfp().setQ(2, 3L, 0L);
        coreU0.setRegister(4, 5);
        put32(coreU0, CODE_BASE, raw2scalar(0, 0, 1, 2, 1, 0, 0b1110, 0b100, 4));
        coreU0.step();

        ArmCore coreU1 = newCore();
        coreU1.vfp().setQ(1, 2L, 0L);
        coreU1.vfp().setQ(2, 3L, 0L);
        coreU1.setRegister(4, 5);
        put32(coreU1, CODE_BASE, raw2scalar(1, 0, 1, 2, 1, 0, 0b1110, 0b100, 4));
        coreU1.step();

        assertEquals(coreU0.vfp().element(1, 0, 0), coreU1.vfp().element(1, 0, 0));
        assertEquals((2 + 3 * 5) & 0xFF, coreU0.vfp().element(1, 0, 0));
    }

    // ── VFMA_scalar / VFMAS_scalar: MESMA troca de papéis, em ponto flutuante ───────────────────

    @Test
    void vfmaScalarComputesQnTimesRmPlusQd() {
        ArmCore core = newCore();
        core.vfp().setQ(1, Integer.toUnsignedLong(Float.floatToRawIntBits(2.0f)), 0L); // Qd = 2.0f.
        core.vfp().setQ(2, Integer.toUnsignedLong(Float.floatToRawIntBits(3.0f)), 0L); // Qn = 3.0f.
        core.setRegister(4, Float.floatToRawIntBits(4.0f)); // Rm = 4.0f.
        // VFMA_scalar, esz binary32 (bit28=0), Qd=1, Qn=2, Rm=4.
        int r = raw2scalar(0, 3, 1, 2, 1, 0, 0b1110, 0b100, 4);
        put32(core, CODE_BASE, r);

        core.step();

        float result = Float.intBitsToFloat((int) core.vfp().element(1, 0, 2));
        assertEquals(3.0f * 4.0f + 2.0f, result, 0.0f, "VFMA_scalar: Qn*Rm + Qd");
    }

    @Test
    void vfmasScalarComputesQnTimesQdPlusRm() {
        ArmCore core = newCore();
        core.vfp().setQ(1, Integer.toUnsignedLong(Float.floatToRawIntBits(2.0f)), 0L); // Qd = 2.0f.
        core.vfp().setQ(2, Integer.toUnsignedLong(Float.floatToRawIntBits(3.0f)), 0L); // Qn = 3.0f.
        core.setRegister(4, Float.floatToRawIntBits(4.0f)); // Rm = 4.0f.
        // VFMAS_scalar, esz binary32 (bit28=0), Qd=1, Qn=2, Rm=4, bit12=1.
        int r = raw2scalar(0, 3, 1, 2, 1, 1, 0b1110, 0b100, 4);
        put32(core, CODE_BASE, r);

        core.step();

        float result = Float.intBitsToFloat((int) core.vfp().element(1, 0, 2));
        assertEquals(3.0f * 2.0f + 4.0f, result, 0.0f, "VFMAS_scalar: Qn*Qd + Rm");
    }

    // ── VBRSR: bit reverse and shift right (sem análogo em NEON/A64) ────────────────────────────

    @Test
    void vbrsrReversesBitsAndShiftsRightByTheCount() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0001L, 0L); // Qn byte 0 = 0b0000_0001.
        core.setRegister(4, 8); // count == largura (8 bits): mantém totalmente invertido.
        // VBRSR, esz=0 (byte), Qd=1, Qn=2, Rm=4 (U=1).
        int r = raw2scalar(1, 0, 1, 2, 1, 1, 0b1110, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x80, core.vfp().element(1, 0, 0), "revbit8(0b00000001) = 0b10000000");
    }

    @Test
    void vbrsrWithZeroCountProducesZero() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0xFFL, 0L);
        core.setRegister(4, 0);
        int r = raw2scalar(1, 0, 1, 2, 1, 1, 0b1110, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.vfp().element(1, 0, 0));
    }

    // ── VQDMLAH / VQDMLASH: acumulador vs escalar trocados, saturante ───────────────────────────

    @Test
    void vqdmlahAndVqdmlashDifferInWhichOperandIsMultipliedByQn() {
        // Fórmula real (do_vqdmlah_b): result = sat16(2*a*b + (c<<8) + (round<<7)) >> 8.
        // VQDMLAH: a=Qn, b=Rm, c=Qd ATUAL. VQDMLASH: a=Qn, b=Qd ATUAL, c=Rm.
        ArmCore coreDmlah = newCore();
        coreDmlah.vfp().setQ(1, 0L, 0L); // Qd inicial = 0 (== c, acumulador neutro para VQDMLAH).
        coreDmlah.vfp().setQ(2, 64L, 0L); // Qn = 64 (== a).
        coreDmlah.setRegister(4, 64); // Rm = 64 (== b).
        int rDmlah = raw2scalar(0, 0, 1, 2, 0, 0, 0b1110, 0b110, 4); // VQDMLAH, esz=0, Qd=1, Qn=2, Rm=4.
        put32(coreDmlah, CODE_BASE, rDmlah);
        coreDmlah.step();
        // (2*64*64 + (0<<8)) >> 8 = 8192 >> 8 = 32.
        assertEquals(32, signExtendByte(coreDmlah.vfp().element(1, 0, 0)));

        ArmCore coreDmlash = newCore();
        coreDmlash.vfp().setQ(1, 64L, 0L); // Qd inicial = 64 (agora é o SEGUNDO fator, == b).
        coreDmlash.vfp().setQ(2, 64L, 0L); // Qn = 64 (== a).
        coreDmlash.setRegister(4, 10); // Rm = 10 (agora é o TERCEIRO operando, == c).
        int rDmlash = raw2scalar(0, 0, 1, 2, 0, 1, 0b1110, 0b110, 4); // VQDMLASH, bit12=1.
        put32(coreDmlash, CODE_BASE, rDmlash);
        coreDmlash.step();
        // (2*64*64 + (10<<8)) >> 8 = (8192+2560) >> 8 = 10752 >> 8 = 42 — DIFERENTE de 32 acima.
        assertEquals(42, signExtendByte(coreDmlash.vfp().element(1, 0, 0)));
    }

    private static int signExtendByte(long value) {
        return (byte) value;
    }

    // ── VQRDMLAH: mesmo núcleo de VQDMLAH, mas com o arredondamento (+1<<(bits-1)) somado ANTES
    // ── de saturar/deslocar — só decode-testado até aqui; prova que a constante de arredondamento
    // ── de fato muda o resultado (não é um parâmetro morto no núcleo compartilhado).

    @Test
    void vqrdmlahRoundingConstantChangesTheResultComparedToVqdmlah() {
        // Fórmula real: result = sat16(2*a*b + (c<<8) + (round ? 1<<7 : 0)) >> 8.
        // a=Qn=100, b=Rm=1, c=Qd=0 -> 2*100*1 = 200 (sat16 não estoura, 200 < 32767).
        // Sem round: 200 >> 8 = 0. Com round: (200 + 128) >> 8 = 328 >> 8 = 1.
        ArmCore coreDmlah = newCore();
        coreDmlah.vfp().setQ(1, 0L, 0L); // Qd inicial = 0 (== c).
        coreDmlah.vfp().setQ(2, 100L, 0L); // Qn = 100 (== a).
        coreDmlah.setRegister(4, 1); // Rm = 1 (== b).
        int rDmlah = raw2scalar(0, 0, 1, 2, 0, 0, 0b1110, 0b110, 4); // VQDMLAH (nibble3=110, sem round).
        put32(coreDmlah, CODE_BASE, rDmlah);
        coreDmlah.step();
        assertEquals(0, signExtendByte(coreDmlah.vfp().element(1, 0, 0)), "sem round: 200 >> 8 = 0");

        ArmCore coreDrmlah = newCore();
        coreDrmlah.vfp().setQ(1, 0L, 0L);
        coreDrmlah.vfp().setQ(2, 100L, 0L);
        coreDrmlah.setRegister(4, 1);
        int rDrmlah = raw2scalar(0, 0, 1, 2, 0, 0, 0b1110, 0b100, 4); // VQRDMLAH (nibble3=100, com round).
        put32(coreDrmlah, CODE_BASE, rDrmlah);
        coreDrmlah.step();
        assertEquals(1, signExtendByte(coreDrmlah.vfp().element(1, 0, 0)),
                "com round: (200 + 128) >> 8 = 1 — a constante de arredondamento realmente é somada");
    }

    @Test
    void qdmlahSetsQcOnlyWhenTheActiveLaneSaturates() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x7F | (0x7FL << 8), 0L); // Qd (== c): bytes 0 e 1 = 127.
        core.vfp().setQ(2, 0x7F | (0x7FL << 8), 0L); // Qn (== a): idem.
        core.setRegister(4, 127); // Rm (== b) = 127 -> 2*127*127+(127<<8) = 64770 > INT16_MAX.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0001); // só o byte 0 (lane 0) ativo.
        // VQDMLAH, esz=0 (byte), Qd=1, Qn=2, Rm=4.
        int r = raw2scalar(0, 0, 1, 2, 0, 0, 0b1110, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertTrue(core.fpscr().qc(), "lane 0 ATIVA saturou (64770 > INT16_MAX)");
        assertEquals(127, signExtendByte(core.vfp().element(1, 0, 0)), "saturado no topo (INT16_MAX >> 8 = 127)");
    }

    @Test
    void qdmlahNeverSetsQcWhenNoActiveLaneSaturates() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0L, 0L);
        core.vfp().setQ(2, 1L, 0L);
        core.setRegister(4, 1);
        int r = raw2scalar(0, 0, 1, 2, 0, 0, 0b1110, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertFalse(core.fpscr().qc());
    }

    // ── VQDMULLB_scalar / VQDMULLT_scalar: indexação intercalada da FONTE (Qn) ──────────────────

    @Test
    void vqdmullbScalarReadsEvenInterleavedLanesOfQn() {
        ArmCore core = newCore();
        // Qn halfwords: [0]=2, [1]=99 (ímpar, NÃO deve ser lido por VQDMULLB_scalar).
        core.vfp().setQ(2, 2L | (99L << 16), 0L);
        core.setRegister(4, 3); // Rm = 3.
        // VQDMULLB_scalar: bit28=0 (esz=1, halfword fonte), Qd=1, Qn=2, Rm=4, top=false (bit12=0).
        int r = raw2scalar(0, 3, 1, 2, 0, 0, 0b1111, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        // Word 0 do destino = 2*(Qn[0]=2)*(Rm=3) = 12.
        assertEquals(12, core.vfp().element(1, 0, 2));
    }

    @Test
    void vqdmulltScalarReadsOddInterleavedLanesOfQn() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 2L | (99L << 16), 0L); // [0]=2 (par, ignorado por _T), [1]=99 (ímpar).
        core.setRegister(4, 3);
        // VQDMULLT_scalar: bit28=0, Qd=1, Qn=2, Rm=4, top=true (bit12=1).
        int r = raw2scalar(0, 3, 1, 2, 0, 1, 0b1111, 0b110, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(2L * 99 * 3, core.vfp().element(1, 0, 2));
    }

    // ── Predicação nas 3 larguras (broadcast + máscara) ─────────────────────────────────────────

    @Test
    void predicationPreservesInactiveHalfwordsWithScalarBroadcast() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0005_0005_0005_0005L, 0L);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L);
        core.setRegister(4, 3);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0003); // só o halfword 0 ativo.
        // VADD_scalar, esz=1 (halfword), Qd=1, Qn=2, Rm=4.
        int r = raw2scalar(0, 1, 1, 2, 1, 0, 0b1111, 0b100, 4);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(8, core.vfp().element(1, 0, 1), "halfword 0 ativo: 5+3=8");
        assertEquals(0xFFFF, core.vfp().element(1, 1, 1), "halfword 1 mascarado: preserva 0xFFFF");
    }
}
