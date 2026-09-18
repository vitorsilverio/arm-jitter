package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.11 — deslocamentos estreitantes (`VSHRNB/T`, `VRSHRNB/T`, `VQSHRNB/T_S`, `VQSHRNB/T_U`,
/// `VQSHRUNB/T`, `VQRSHRNB/T_S`, `VQRSHRNB/T_U`, `VQRSHRUNB/T`) e `VSHLC`, fim-a-fim (decode + lift +
/// executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Foco nos achados que corrigem
/// suposições ingênuas: indexação INTERCALADA no DESTINO com preservação da metade NÃO escrita
/// (`B`/`T`), as 3 formas de saturação distintas, arredondamento truncar-vs-round, `QC` só em lanes
/// ativas, e `VSHLC` round-trip (incluindo `imm==0` = "desloca por 32").
class MveVectorShiftNarrowAndCarryExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    /// Bit `INVSTATE` do `UFSR` — `bit[1]` do `UFSR`, byte alto do `CFSR` (mesma constante de
    /// `MvePredicationTest`).
    private static final int CFSR_UFSR_INVSTATE_BIT = 1 << 17;
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;

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

    /// MESMO layout do Javadoc de `Thumb2MveNarrowingShiftDecoder`/
    /// `Thumb2MveNarrowingShiftDecoderTest`. `rawShiftField` é o valor CRU (não `N - raw`).
    private static int rawNarrow(int u, int esz, int rawShiftField, int top, int bit7, int bit0, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        int prefix = esz == 0 ? (0b001 << 3) | (rawShiftField & 0x7) : (0b01 << 4) | (rawShiftField & 0xF);
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (prefix << 16)
                | (qdLow << 13) | (top << 12) | (0b1111 << 8) | (bit7 << 7) | (1 << 6) | (qmHigh << 5)
                | (qmLow << 1) | bit0;
    }

    private static int rawVshlc(int imm, int qd, int rdm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111 << 29) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (1 << 21) | ((imm & 0x1F) << 16)
                | (qdLow << 13) | (0b1111 << 8) | (0b1100 << 4) | (rdm & 0xF);
    }

    // ── B/T: metade NÃO escrita é PRESERVADA (Armadilha 2 da task) ─────────────────────────────────

    @Test
    void shrnBottomWritesEvenBytesPreservingOddOnesUnchanged() {
        ArmCore core = newCore();
        // Qm halfwords fonte: [0]=0x0203 ([1]=0x0405 ignorado, forma B só lê pares).
        core.vfp().setQ(3, 0x0000_0000_0405_0203L, 0L);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // Qd todo-1 (sentinela).
        // VSHRNB: U=0,byte-dest,shift cru=1(N=8->shift=7),top=0(B),bit7=1,bit0=1,Qd=1,Qm=3.
        int r = rawNarrow(0, 0, 1, 0, 1, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        // le=0: wide=0x0203>>7 = 0x0004 (byte baixo=0x04) -> destLane=0.
        assertEquals(0x04, core.vfp().element(1, 0, 0), "le=0 -> destLane=0 recebe 0x0203>>7 truncado a byte");
        assertEquals(0xFF, core.vfp().element(1, 1, 0), "destLane=1 (ímpar, forma T) PRESERVADO intocado");
    }

    @Test
    void shrnTopWritesOddBytesPreservingEvenOnesUnchanged() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0000_0405_0203L, 0L); // halfword[0]=0x0203, halfword[1]=0x0405.
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
        // VSHRNT: top=1(T), lê a MESMA lane larga le=0 (halfword[0]=0x0203), escreve em destLane=1.
        int r = rawNarrow(0, 0, 1, 1, 1, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFF, core.vfp().element(1, 0, 0), "destLane=0 (par, forma B) PRESERVADO intocado");
        assertEquals(0x04, core.vfp().element(1, 1, 0), "destLane=1 recebe 0x0203>>7 truncado a byte");
    }

    // ── Contagem N - shift nas 2 larguras (reuso da B16.10, teste próprio) ──────────────────────────

    @Test
    void byteWidthShiftIsEightMinusRawField() {
        ArmCore core = newCore();
        // campo cru (3 bits) = 1 -> shift efetivo = 8-1 = 7; wide=0x0080 (bit7 setado) -> >>7 = 1.
        core.vfp().setQ(3, 0x0080L, 0L);
        int r = rawNarrow(0, 0, 1, 0, 1, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.vfp().element(1, 0, 0), "N=8: shift = 8-1 = 7 -> 0x0080>>7 = 1");
    }

    @Test
    void halfwordWidthShiftIsSixteenMinusRawField() {
        ArmCore core = newCore();
        // campo cru (4 bits) = 1 -> shift efetivo = 16-1 = 15; wide=0x00008000 (bit15) -> >>15 = 1.
        core.vfp().setQ(3, 0x8000L, 0L);
        int r = rawNarrow(0, 1, 1, 0, 1, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.vfp().element(1, 0, 1), "N=16: shift = 16-1 = 15 -> 0x8000>>15 = 1");
    }

    // ── As 3 formas de saturação ─────────────────────────────────────────────────────────────────

    @Test
    void vqshrnSSaturatesToSignedRangeAndSetsQc() {
        ArmCore core = newCore();
        // wide halfword[0] = 0x7FFF (32767, MAX signed 16); shift=1 (campo cru=7) -> 16383, ainda
        // não cabe em signed8 [-128,127].
        core.vfp().setQ(3, 0x7FFFL, 0L);
        // VQSHRNB_S: U=0,bit7=0,bit0=0,shift cru=7(shift efetivo=1).
        int r = rawNarrow(0, 0, 7, 0, 0, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x7F, core.vfp().element(1, 0, 0), "satura no MAX signed8 = 127");
        assertTrue(core.fpscr().qc());
    }

    @Test
    void vqshrnUSaturatesToUnsignedRangeAndSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x3FFL, 0L); // 1023 >> 1 = 511, não cabe em unsigned8 [0,255].
        // VQSHRNB_U: U=1,bit7=0,bit0=0,shift efetivo=1.
        int r = rawNarrow(1, 0, 7, 0, 0, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFF, core.vfp().element(1, 0, 0), "satura no MAX unsigned8 = 255");
        assertTrue(core.fpscr().qc());
    }

    @Test
    void vqshrunSaturatesSignedSourceToUnsignedRangeAndSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0xFFFFL, 0L); // wide halfword[0] = -1 (signed); qualquer shift continua -1.
        // VQSHRUNB: U=0,bit7=1,bit0=0,shift efetivo=1.
        int r = rawNarrow(0, 0, 7, 0, 1, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x00, core.vfp().element(1, 0, 0), "-1 satura para 0 (MIN unsigned8)");
        assertTrue(core.fpscr().qc());
    }

    @Test
    void shrnNeverSaturatesEvenWithOverflowingValue() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0xFFFFL, 0L); // wide halfword[0] = 0xFFFF, shift efetivo=1.
        // VSHRNB: bit7=1,bit0=1 (sem saturação) — trunca puro, nunca satura.
        int r = rawNarrow(0, 0, 7, 0, 1, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFF, core.vfp().element(1, 0, 0), "0xFFFF>>1=0x7FFF, byte baixo 0xFF, sem saturar");
        assertFalse(core.fpscr().qc());
    }

    // ── Arredondamento: VRSHRNB arredonda, VSHRNB trunca (mesmo operando) ───────────────────────────

    @Test
    void rshrnRoundsWhileShrnTruncatesForTheSameOperand() {
        // wide halfword[0] = 0x0003 (3), shift = 1: truncar dá 1 (3>>1); arredondar dá 2 ((3+1)>>1).
        ArmCore truncCore = newCore();
        truncCore.vfp().setQ(3, 3L, 0L);
        int shrn = rawNarrow(0, 0, 7, 0, 1, 1, 1, 3); // shift cru=7 -> shift efetivo=1.
        put32(truncCore, CODE_BASE, shrn);
        truncCore.step();
        assertEquals(1, truncCore.vfp().element(1, 0, 0), "VSHRNB trunca: 3>>1 = 1");

        ArmCore roundCore = newCore();
        roundCore.vfp().setQ(3, 3L, 0L);
        int rshrn = rawNarrow(1, 0, 7, 0, 1, 1, 1, 3); // U=1 -> VRSHRNB.
        put32(roundCore, CODE_BASE, rshrn);
        roundCore.step();
        assertEquals(2, roundCore.vfp().element(1, 0, 0), "VRSHRNB arredonda: (3+1)>>1 = 2");
    }

    // ── QC só para lanes ATIVAS ──────────────────────────────────────────────────────────────────

    @Test
    void inactiveLanePreservesDestinationAndNeverSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x7FFFL, 0L); // satura se ativo.
        core.vfp().setQ(1, 0xAAAA_AAAAL, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0); // todas as lanes desligadas.
        int r = rawNarrow(0, 0, 7, 0, 0, 0, 1, 3); // VQSHRNB_S, satura se ativo.
        put32(core, CODE_BASE, r);

        core.step();

        assertFalse(core.fpscr().qc(), "lane mascarada nunca satura");
        assertEquals(0xAA, core.vfp().element(1, 0, 0), "Qd preservado (byte baixo da sentinela)");
    }

    // ── VSHLC ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void vshlcShiftsWholeVectorLeftInjectingRdmLowBitsAndReturningCarriedOutBits() {
        ArmCore core = newCore();
        // Qd words: [0]=0x00000001,[1]=0x00000002,[2]=0,[3]=0.
        core.vfp().setQ(1, 0x0000_0002_0000_0001L, 0L);
        core.setRegister(0, 0xF); // Rdm baixo (4 bits) injetado no elemento 0.
        // VSHLC: imm=4, Qd=1, Rdm=R0.
        int r = rawVshlc(4, 1, 0);
        put32(core, CODE_BASE, r);

        core.step();

        // elemento0: (1<<4)|(0xF & 0xF) = 0x1F; elemento0 velho (0x00000001) >> (32-4) = 0 -> novo
        // rdm intermediário = 0. elemento1: (2<<4)|0 = 0x20; rdm final = elemento1 velho(2)>>28 = 0.
        assertEquals(0x1F, core.vfp().element(1, 0, 2));
        assertEquals(0x20, core.vfp().element(1, 1, 2));
        assertEquals(0, core.register(0), "bits que saíram pelo topo do último elemento ATIVO");
    }

    @Test
    void vshlcImmZeroMeansShiftByThirtyTwo() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x1234_5678L, 0L); // elemento0 = 0x12345678, elementos 1-3 = 0.
        core.setRegister(0, 0xDEADBEEF);
        // Predica só o elemento0 ATIVO (bytes 0-3), para isolar a semântica de "shift=32" de um
        // único elemento sem o ruído dos elementos 1-3 (que ficariam com Rdm intermediário=0 e
        // sobrescreveriam o Rdm final se também ativos).
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F);
        int r = rawVshlc(0, 1, 0); // imm=0 -> desloca por 32.
        put32(core, CODE_BASE, r);

        core.step();

        // Com shift=32, elemento0 recebe o Rdm ANTIGO inteiro; Rdm novo recebe o elemento0 ANTIGO.
        assertEquals(0xDEADBEEF, (int) core.vfp().element(1, 0, 2));
        assertEquals(0x1234_5678, core.register(0));
    }

    @Test
    void vshlcInactiveLanesPreserveDestinationContentUnchanged() {
        ArmCore core = newCore();
        // Qd words: [0]=1 (ATIVO), [1..3]=sentinelas não-zero que NÃO podem mudar se mascaradas.
        core.vfp().setQ(1, 0xDEADBEEF_00000001L, 0x12345678_CAFEBABEL);
        core.setRegister(0, 0xF);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só elemento0 (beat A0) ativo.
        // VSHLC: imm=4, Qd=1, Rdm=R0.
        int r = rawVshlc(4, 1, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x1F, (int) core.vfp().element(1, 0, 2), "elemento0 ATIVO: (1<<4)|(0xF&0xF)");
        assertEquals(0xDEADBEEF, (int) core.vfp().element(1, 1, 2), "elemento1 mascarado: PRESERVADO");
        assertEquals(0xCAFEBABE, (int) core.vfp().element(1, 2, 2), "elemento2 mascarado: PRESERVADO");
        assertEquals(0x12345678, (int) core.vfp().element(1, 3, 2), "elemento3 mascarado: PRESERVADO");
    }

    // ── Rounding também se aplica às 3 formas SATURANTES que arredondam (gap: só SHRN/RSHRN tinham
    // teste de execução de arredondamento; VQRSHRN_S/_U/VQRSHRUN só tinham teste de DECODE) ────────

    @Test
    void vqrshrnSRoundsDifferentlyThanVqshrnSForSameOperand() {
        // wide halfword[0] = 3, shift efetivo = 1 (campo cru = 7): truncar (VQSHRN_S) dá 1 (3>>1);
        // arredondar (VQRSHRN_S) dá 2 ((3+1)>>1) — nenhum dos dois satura, isola só o arredondamento.
        ArmCore truncCore = newCore();
        truncCore.vfp().setQ(3, 3L, 0L);
        int vqshrnS = rawNarrow(0, 0, 7, 0, 0, 0, 1, 3); // U=0,bit7=0,bit0=0 -> VQSHRN_S.
        put32(truncCore, CODE_BASE, vqshrnS);
        truncCore.step();
        assertEquals(1, truncCore.vfp().element(1, 0, 0), "VQSHRN_S trunca: 3>>1 = 1");
        assertFalse(truncCore.fpscr().qc());

        ArmCore roundCore = newCore();
        roundCore.vfp().setQ(3, 3L, 0L);
        int vqrshrnS = rawNarrow(0, 0, 7, 0, 0, 1, 1, 3); // U=0,bit7=0,bit0=1 -> VQRSHRN_S.
        put32(roundCore, CODE_BASE, vqrshrnS);
        roundCore.step();
        assertEquals(2, roundCore.vfp().element(1, 0, 0), "VQRSHRN_S arredonda: (3+1)>>1 = 2");
        assertFalse(roundCore.fpscr().qc());
    }

    @Test
    void vqrshrnURoundsDifferentlyThanVqshrnUForSameOperand() {
        // Mesmo operando/deslocamento da forma S, agora U=1 (unsigned): VQSHRN_U trunca, VQRSHRN_U
        // arredonda — confirma que o mapeamento de bits também alcança UQSHRN/UQRSHRN, não só os
        // signed.
        ArmCore truncCore = newCore();
        truncCore.vfp().setQ(3, 3L, 0L);
        int vqshrnU = rawNarrow(1, 0, 7, 0, 0, 0, 1, 3); // U=1,bit7=0,bit0=0 -> VQSHRN_U.
        put32(truncCore, CODE_BASE, vqshrnU);
        truncCore.step();
        assertEquals(1, truncCore.vfp().element(1, 0, 0), "VQSHRN_U trunca: 3>>1 = 1");

        ArmCore roundCore = newCore();
        roundCore.vfp().setQ(3, 3L, 0L);
        int vqrshrnU = rawNarrow(1, 0, 7, 0, 0, 1, 1, 3); // U=1,bit7=0,bit0=1 -> VQRSHRN_U.
        put32(roundCore, CODE_BASE, vqrshrnU);
        roundCore.step();
        assertEquals(2, roundCore.vfp().element(1, 0, 0), "VQRSHRN_U arredonda: (3+1)>>1 = 2");
    }

    @Test
    void vqrshrunRoundsDifferentlyThanVqshrunForSameOperand() {
        // wide halfword[0] = 1 (signed, positivo), shift efetivo = 1: truncar (VQSHRUN) dá 0 (1>>1);
        // arredondar (VQRSHRUN) dá 1 ((1+1)>>1) — confirma que o arredondamento chega à ÚNICA forma
        // signed->unsigned que também arredonda.
        ArmCore truncCore = newCore();
        truncCore.vfp().setQ(3, 1L, 0L);
        int vqshrun = rawNarrow(0, 0, 7, 0, 1, 0, 1, 3); // U=0,bit7=1,bit0=0 -> VQSHRUN.
        put32(truncCore, CODE_BASE, vqshrun);
        truncCore.step();
        assertEquals(0, truncCore.vfp().element(1, 0, 0), "VQSHRUN trunca: 1>>1 = 0");

        ArmCore roundCore = newCore();
        roundCore.vfp().setQ(3, 1L, 0L);
        int vqrshrun = rawNarrow(1, 0, 7, 0, 1, 0, 1, 3); // U=1,bit7=1,bit0=0 -> VQRSHRUN.
        put32(roundCore, CODE_BASE, vqrshrun);
        roundCore.step();
        assertEquals(1, roundCore.vfp().element(1, 0, 0), "VQRSHRUN arredonda: (1+1)>>1 = 1");
    }

    // ── ECI reservado: USAGE_FAULT com UFSR.INVSTATE (via IrOp direto — inalcançável pelo decode
    // real deste emulador, mesmo padrão de `MvePredicationTest#reservedEciEntersUsageFaultWithInvstateSet`) ─

    @Test
    void reservedEciEntersUsageFaultWithInvstateSetForNarrowingFamily() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(3); // valor reservado.
        int pcBefore = core.programCounter();
        IrOp.MveVectorShiftNarrowImmediateInterleaved op = new IrOp.MveVectorShiftNarrowImmediateInterleaved(
                AdvSimdShiftNarrowOp.SHRN, 0, 1, false, 1, 3, Condition.AL);

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE).executeOp(core, op, pcBefore);

        assertTrue(pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_INVSTATE_BIT, model.cfsr() & CFSR_UFSR_INVSTATE_BIT);
    }

    @Test
    void reservedEciEntersUsageFaultWithInvstateSetForVshlc() {
        ArmCore core = newCore();
        ((TestAddressSpace) core.memory()).put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(6); // outro valor reservado (3/6/7-15).
        int pcBefore = core.programCounter();
        IrOp.MveVectorShiftLeftCarry op = new IrOp.MveVectorShiftLeftCarry(4, 1, 0, Condition.AL);

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE).executeOp(core, op, pcBefore);

        assertTrue(pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_INVSTATE_BIT, model.cfsr() & CFSR_UFSR_INVSTATE_BIT);
    }
}
