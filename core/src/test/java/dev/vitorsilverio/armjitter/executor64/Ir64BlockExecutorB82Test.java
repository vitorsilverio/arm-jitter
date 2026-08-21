package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64FlagConversionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Semântica dos ops novos da B8.2 (`ADC`/`SBC`, `EXTR`, "Data-processing (1 source)",
/// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/`SMULH`/`UMULH`, `RMIF`/`SETF8`/`SETF16`/`CFINV`/
/// `XAFLAG`/`AXFLAG`) direto no executor (interpretador = oráculo, G1). Complementa
/// {@code Aarch64DecoderCorpusTest} (decode).
class Ir64BlockExecutorB82Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(16);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void adcAddsCarryIn() {
        Aarch64Core core = newCore();
        core.setX(1, 5L);
        core.setX(2, 10L);
        core.pstate().setNzcv(false, false, true, false); // C=1
        EXECUTOR.executeOp(core, new Ir64Op.AluWithCarry(false, 0, 1, 2, true, false));
        assertEquals(16L, core.x(0), "5 + 10 + carry(1) = 16");
    }

    @Test
    void adcsSetsCarryOutOnOverflow() {
        Aarch64Core core = newCore();
        core.setX(1, -1L); // todos os bits setados
        core.setX(2, 0L);
        core.pstate().setNzcv(false, false, true, false); // C=1
        EXECUTOR.executeOp(core, new Ir64Op.AluWithCarry(false, 0, 1, 2, true, true));
        assertEquals(0L, core.x(0), "-1 + 0 + 1 estoura para 0");
        assertTrue(core.pstate().carry(), "estouro unsigned de 64 bits seta C");
        assertTrue(core.pstate().zero());
    }

    @Test
    void sbcUsesCurrentCarryAsNotBorrow() {
        Aarch64Core core = newCore();
        core.setX(1, 10L);
        core.setX(2, 3L);
        core.pstate().setNzcv(false, false, true, false); // C=1 => sem borrow
        EXECUTOR.executeOp(core, new Ir64Op.AluWithCarry(true, 0, 1, 2, true, false));
        assertEquals(7L, core.x(0), "C=1: SBC é subtração normal (10-3)");
    }

    @Test
    void sbcWithBorrowSubtractsOneExtra() {
        Aarch64Core core = newCore();
        core.setX(1, 10L);
        core.setX(2, 3L);
        core.pstate().setNzcv(false, false, false, false); // C=0 => há borrow pendente
        EXECUTOR.executeOp(core, new Ir64Op.AluWithCarry(true, 0, 1, 2, true, false));
        assertEquals(6L, core.x(0), "C=0: subtrai 1 a mais (10-3-1)");
    }

    @Test
    void sbcsMinValueMinusMinusOnePlusCarryDoesNotOverflow() {
        // Contra-exemplo que invalidou a composição ingênua de 2 somas encadeadas (ver Javadoc de
        // Ir64BlockExecutor#addWithCarryFlags): MIN_VALUE - (-1) - 0 (C=1, sem borrow) = MIN_VALUE
        // + 1, dentro do range — NÃO deve sinalizar overflow.
        Aarch64Core core = newCore();
        core.setX(1, Long.MIN_VALUE);
        core.setX(2, -1L);
        core.pstate().setNzcv(false, false, true, false); // C=1
        EXECUTOR.executeOp(core, new Ir64Op.AluWithCarry(true, 0, 1, 2, true, true));
        assertEquals(Long.MIN_VALUE + 1, core.x(0));
        assertFalse(core.pstate().overflow(), "MIN_VALUE+1 cabe em 64 bits assinados");
    }

    @Test
    void extractConcatenatesHighAndLow() {
        Aarch64Core core = newCore();
        core.setX(1, 0x1111_1111_1111_1111L); // Rn (alta)
        core.setX(2, 0x2222_2222_2222_2222L); // Rm (baixa)
        EXECUTOR.executeOp(core, new Ir64Op.Extract(0, 1, 2, 4, true));
        // concat = Rn:Rm (128 bits) >> 4, baixos 64 bits.
        long expected = (0x2222_2222_2222_2222L >>> 4) | (0x1111_1111_1111_1111L << 60);
        assertEquals(expected, core.x(0));
    }

    @Test
    void extractWithLsbZeroIsJustLow() {
        Aarch64Core core = newCore();
        core.setX(1, 0x1111_1111_1111_1111L);
        core.setX(2, 0x2222_2222_2222_2222L);
        EXECUTOR.executeOp(core, new Ir64Op.Extract(0, 1, 2, 0, true));
        assertEquals(0x2222_2222_2222_2222L, core.x(0));
    }

    @Test
    void extractRorAliasWithSameOperand() {
        // ROR Xd,Xs,#8 == EXTR Xd,Xs,Xs,#8
        Aarch64Core core = newCore();
        core.setX(1, 0x0102_0304_0506_0708L);
        EXECUTOR.executeOp(core, new Ir64Op.Extract(0, 1, 1, 8, true));
        assertEquals(Long.rotateRight(0x0102_0304_0506_0708L, 8), core.x(0));
    }

    @Test
    void rbitReversesAllBits() {
        Aarch64Core core = newCore();
        core.setX(1, 1L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.RBIT, 0, 1, true));
        assertEquals(Long.MIN_VALUE, core.x(0), "bit 0 vira bit 63");
    }

    @Test
    void rev16SwapsBytesWithinEachHalfword() {
        Aarch64Core core = newCore();
        core.setX(1, 0x0102_0304_0506_0708L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV16, 0, 1, true));
        assertEquals(0x0201_0403_0605_0807L, core.x(0));
    }

    @Test
    void rev32SwapsBytesWithinEachWordKeepingWordOrder() {
        Aarch64Core core = newCore();
        core.setX(1, 0x0102_0304_0506_0708L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV32, 0, 1, true));
        assertEquals(0x0403_0201_0807_0605L, core.x(0));
    }

    @Test
    void revNarrowReversesTheSingleWord() {
        Aarch64Core core = newCore();
        core.setX(1, 0x0102_0304L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV32, 0, 1, false));
        assertEquals(0x0403_0201L, core.x(0));
    }

    @Test
    void rev64ReversesAllEightBytes() {
        Aarch64Core core = newCore();
        core.setX(1, 0x0102_0304_0506_0708L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV64, 0, 1, true));
        assertEquals(0x0807_0605_0403_0201L, core.x(0));
    }

    @Test
    void clzCountsLeadingZeros() {
        Aarch64Core core = newCore();
        core.setX(1, 1L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLZ, 0, 1, true));
        assertEquals(63L, core.x(0));
    }

    @Test
    void clsAllZerosIsWidthMinusOne() {
        Aarch64Core core = newCore();
        core.setX(1, 0L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLS, 0, 1, true));
        assertEquals(63L, core.x(0));
    }

    @Test
    void clsFirstDivergingBitAfterSign() {
        Aarch64Core core = newCore();
        core.setX(1, 0x4000_0000_0000_0000L); // bit62 setado, sinal(63)=0
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLS, 0, 1, true));
        assertEquals(0L, core.x(0), "bit62 já difere do sinal — 0 bits de sinal à esquerda");
    }

    @Test
    void cntCountsSetBits() {
        Aarch64Core core = newCore();
        core.setX(1, 0b1011L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CNT, 0, 1, true));
        assertEquals(3L, core.x(0));
    }

    @Test
    void smaddlSignExtendsBeforeMultiplying() {
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFF_FFFFL); // -1 em W
        core.setX(2, 5L);
        core.setX(3, 100L); // acumulador
        EXECUTOR.executeOp(core, new Ir64Op.MultiplyAccumulateLong(false, true, 0, 1, 2, 3));
        assertEquals(95L, core.x(0), "100 + (-1 * 5) = 95");
    }

    @Test
    void umaddlZeroExtendsBeforeMultiplying() {
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFF_FFFFL); // 2^32-1 em W sem sinal
        core.setX(2, 2L);
        core.setX(3, 0L);
        EXECUTOR.executeOp(core, new Ir64Op.MultiplyAccumulateLong(false, false, 0, 1, 2, 3));
        assertEquals(0xFFFF_FFFFL * 2L, core.x(0));
    }

    @Test
    void umsublSubtractsProductFromAccumulator() {
        Aarch64Core core = newCore();
        core.setX(1, 10L);
        core.setX(2, 3L);
        core.setX(3, 100L);
        EXECUTOR.executeOp(core, new Ir64Op.MultiplyAccumulateLong(true, false, 0, 1, 2, 3));
        assertEquals(70L, core.x(0), "100 - (10*3) = 70");
    }

    @Test
    void umulhComputesHigh64BitsOfProduct() {
        Aarch64Core core = newCore();
        core.setX(1, -1L); // 2^64-1 sem sinal
        core.setX(2, 2L);
        EXECUTOR.executeOp(core, new Ir64Op.MultiplyHigh(false, 0, 1, 2));
        assertEquals(1L, core.x(0), "(2^64-1)*2 = 2^65-2, bits altos = 1");
    }

    @Test
    void smulhComputesSignedHigh64BitsOfProduct() {
        Aarch64Core core = newCore();
        core.setX(1, -1L); // -1 assinado
        core.setX(2, 1L);
        EXECUTOR.executeOp(core, new Ir64Op.MultiplyHigh(true, 0, 1, 2));
        assertEquals(-1L, core.x(0), "-1 * 1 = -1, bits altos de -1 (assinado) = -1");
    }

    @Test
    void setf8DetectsSignAndZero() {
        Aarch64Core core = newCore();
        core.setX(1, 0x80L); // byte baixo = -128 assinado
        core.pstate().setNzcv(false, false, true, false); // C=1, preservado
        EXECUTOR.executeOp(core, new Ir64Op.EvaluateIntoFlags(1, 8));
        assertTrue(core.pstate().negative());
        assertFalse(core.pstate().zero());
        assertTrue(core.pstate().carry(), "C nunca muda em SETF8/SETF16");
    }

    @Test
    void setf16ZeroFieldSetsZeroFlag() {
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFF_0000L); // halfword baixo = 0
        EXECUTOR.executeOp(core, new Ir64Op.EvaluateIntoFlags(1, 16));
        assertTrue(core.pstate().zero());
        assertFalse(core.pstate().negative());
    }

    @Test
    void rmifUpdatesOnlySelectedFlags() {
        Aarch64Core core = newCore();
        // candidato = 0b1010 (N=1,Z=0,C=1,V=0); máscara só libera N e V (bits 3 e 0).
        core.setX(1, 0b1010L);
        core.pstate().setNzcv(false, true, false, true); // N=0,Z=1,C=0,V=1 antes
        EXECUTOR.executeOp(core, new Ir64Op.RotateIntoFlags(1, 0, 0b1001));
        assertTrue(core.pstate().negative(), "N atualizado pela máscara");
        assertTrue(core.pstate().zero(), "Z NÃO estava na máscara — preserva valor antigo (1)");
        assertFalse(core.pstate().carry(), "C NÃO estava na máscara — preserva valor antigo (0)");
        assertFalse(core.pstate().overflow(), "V atualizado pela máscara (era 1, candidato tem V=0)");
    }

    @Test
    void cfinvInvertsOnlyCarry() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(true, false, true, true);
        EXECUTOR.executeOp(core, new Ir64Op.ConvertFlags(Ir64FlagConversionOp.INVERT_CARRY));
        assertTrue(core.pstate().negative());
        assertFalse(core.pstate().zero());
        assertFalse(core.pstate().carry(), "C invertido");
        assertTrue(core.pstate().overflow());
    }

    @Test
    void xaflagConvertsExternalToArmFlags() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(true, true, true, true); // Z=1,C=1
        EXECUTOR.executeOp(core, new Ir64Op.ConvertFlags(Ir64FlagConversionOp.EXTERNAL_TO_ARM));
        assertFalse(core.pstate().negative(), "N sempre 0");
        assertTrue(core.pstate().zero(), "Z=oldZ AND oldC = 1 AND 1");
        assertFalse(core.pstate().carry(), "C=oldC AND NOT(oldZ) = 1 AND NOT(1) = 0");
        assertFalse(core.pstate().overflow(), "V sempre 0");
    }

    @Test
    void axflagConvertsArmToExternalFlags() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(true, false, true, true); // Z=0,C=1,V=1
        EXECUTOR.executeOp(core, new Ir64Op.ConvertFlags(Ir64FlagConversionOp.ARM_TO_EXTERNAL));
        assertFalse(core.pstate().negative());
        assertTrue(core.pstate().zero(), "Z=oldZ OR oldV = 0 OR 1");
        assertFalse(core.pstate().carry(), "C=oldC AND NOT(oldV) = 1 AND NOT(1) = 0");
        assertFalse(core.pstate().overflow());
    }
}
