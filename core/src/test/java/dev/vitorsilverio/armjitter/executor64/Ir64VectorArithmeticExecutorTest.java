package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica dos ops de AdvSIMD inteiro — aritmética/comparação (B8.7) direto no executor
/// (interpretador = oráculo, G1) — complementa {@code Aarch64AdvSimdIntegerDecoderTest} (decode).
class Ir64VectorArithmeticExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void addWrapsPerElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0xFF); // byte v1[0] = 0xFF
        fp.setElement(2, 0, 0, 0x02); // byte v2[0] = 0x02

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.ADD, false, 0, 0, 1, 2));

        assertEquals(0x01, fp.element(0, 0, 0), "0xFF + 0x02 trunca para byte = 0x01");
    }

    @Test
    void nonQuadWriteZeroesHighBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // "sujeira" pré-existente

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.ADD, false, 2, 0, 1, 2));

        assertEquals(0L, fp.high64(0), "forma não-quad zera os 64 bits altos (destructive write)");
    }

    @Test
    void cmgtProducesAllOnesOrZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 5); // word v1[0] = 5
        fp.setElement(2, 0, 2, 3); // word v2[0] = 3
        fp.setElement(1, 1, 2, 1); // word v1[1] = 1
        fp.setElement(2, 1, 2, 9); // word v2[1] = 9

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.CMGT, true, 2, 0, 1, 2));

        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "5 > 3: todos-1");
        assertEquals(0L, fp.element(0, 1, 2), "1 > 9 é falso: 0");
    }

    @Test
    void cmhiIsUnsignedCompare() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0xFF); // byte 0xFF: negativo se assinado, maior se não assinado
        fp.setElement(2, 0, 0, 0x01);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.CMHI, false, 0, 0, 1, 2));
        assertEquals(0xFFL, fp.element(0, 0, 0), "0xFF >u 0x01: todos-1 (CMHI não assinado)");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.CMGT, false, 0, 0, 1, 2));
        assertEquals(0L, fp.element(0, 0, 0), "0xFF (=-1) >s 0x01 é falso: 0 (CMGT assinado)");
    }

    @Test
    void shaddHalvesWithoutRoundingSigned() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -3 & 0xFF);
        fp.setElement(2, 0, 0, (byte) -2 & 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SHADD, false, 0, 0, 1, 2));

        byte result = (byte) fp.element(0, 0, 0);
        assertEquals(-3, result, "(-3+-2)>>1 = -5>>1 = -3 (arredonda para -infinito)");
    }

    @Test
    void srhaddRoundsUp() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 1);
        fp.setElement(2, 0, 0, 2);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SRHADD, false, 0, 0, 1, 2));

        assertEquals(2, fp.element(0, 0, 0), "(1+2+1)>>1 = 2, sem arredondamento seria 1");
    }

    @Test
    void sabaAccumulatesAbsoluteDifference() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 10); // Rd inicial = 10
        fp.setElement(1, 0, 0, 3);
        fp.setElement(2, 0, 0, 7);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SABA, false, 0, 0, 1, 2));

        assertEquals(14, fp.element(0, 0, 0), "10 + |3-7| = 14");
    }

    @Test
    void mlaAndMlsAccumulate() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 100);
        fp.setElement(1, 0, 0, 3);
        fp.setElement(2, 0, 0, 4);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.MLA, false, 0, 0, 1, 2));
        assertEquals(112, fp.element(0, 0, 0), "100 + 3*4 = 112");

        fp.setElement(0, 0, 0, 100);
        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.MLS, false, 0, 0, 1, 2));
        assertEquals(88, fp.element(0, 0, 0), "100 - 3*4 = 88");
    }

    @Test
    void pmulIsPolynomialMultiply() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b011);
        fp.setElement(2, 0, 0, 0b101);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.PMUL, false, 0, 0, 1, 2));

        // GF(2): 0b011 * 0b101 = (101) XOR (101<<1) = 0b101 ^ 0b1010 = 0b1111
        assertEquals(0b1111, fp.element(0, 0, 0));
    }

    @Test
    void pairwiseAddCombinesConcatenatedAdjacentElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // v1.4h = {1,2,3,4}, v2.4h = {5,6,7,8}
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 1, i + 1);
            fp.setElement(2, i, 1, i + 5);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticPairwise(
                Ir64VectorPairwiseOp.ADD, false, 1, 0, 1, 2));

        assertEquals(3, fp.element(0, 0, 1), "1+2");
        assertEquals(7, fp.element(0, 1, 1), "3+4");
        assertEquals(11, fp.element(0, 2, 1), "5+6");
        assertEquals(15, fp.element(0, 3, 1), "7+8");
    }

    @Test
    void wideningFillsFullRegisterAndSelectsHalfByQ() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 16; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 1);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SADDL, false, 0, 0, 1, 2)); // baixa: lanes 0-7
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, fp.element(0, i, 1));
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SADDL, true, 0, 0, 1, 2)); // alta ("2"): lanes 8-15
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 8 + 1, fp.element(0, i, 1));
        }
    }

    @Test
    void wideningSignExtendsForSmullButNotUmull() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -1 & 0xFF); // byte 0xFF
        fp.setElement(2, 0, 0, 2);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SMULL, false, 0, 0, 1, 2));
        assertEquals((short) -2 & 0xFFFF, fp.element(0, 0, 1), "sext(-1)*2 = -2");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.UMULL, false, 0, 0, 1, 2));
        assertEquals(0xFF * 2, fp.element(0, 0, 1), "0xFF (não assinado) * 2 = 0x1FE");
    }

    @Test
    void smlalAccumulatesIntoExistingWideElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 1000);
        fp.setElement(1, 0, 0, 3);
        fp.setElement(2, 0, 0, 4);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SMLAL, false, 0, 0, 1, 2));

        assertEquals(1012, fp.element(0, 0, 1), "1000 + 3*4 = 1012");
    }

    @Test
    void wideAddsNarrowOperandExtended() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 1000); // rn largo (halfword) v1[0] = 1000
        fp.setElement(2, 0, 0, (byte) -1 & 0xFF); // rm estreito (byte) v2[0] = 0xFF

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWide(
                Ir64VectorWideOp.SADDW, false, 0, 0, 1, 2));
        assertEquals(999, fp.element(0, 0, 1), "1000 + sext(0xFF=-1) = 999");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWide(
                Ir64VectorWideOp.UADDW, false, 0, 0, 1, 2));
        assertEquals(1000 + 0xFF, fp.element(0, 0, 1), "1000 + zext(0xFF) = 1255");
    }

    @Test
    void narrowTakesTopHalfOfWideSumAndPreservesOtherHalfWhenQ() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // v1.8h = v2.8h = {0x100,...} -> soma = 0x200 cada lane; ADDHN(byte) = (0x200)>>8 = 0x02.
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, 0x100);
            fp.setElement(2, i, 1, 0x100);
        }
        fp.setQ(0, 0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L); // "sujeira" pré-existente

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrow(
                Ir64VectorNarrowOp.ADDHN, false, 0, 0, 1, 2)); // q=false: metade baixa, zera alta
        for (int i = 0; i < 8; i++) {
            assertEquals(0x02, fp.element(0, i, 0));
        }
        assertEquals(0L, fp.high64(0), "q=false zera a metade alta (destructive write)");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrow(
                Ir64VectorNarrowOp.ADDHN, true, 0, 0, 1, 2)); // q=true (ADDHN2): metade alta
        for (int i = 0; i < 8; i++) {
            assertEquals(0x02, fp.element(0, i, 0), "metade baixa já escrita continua lá");
            assertEquals(0x02, fp.element(0, 8 + i, 0), "metade alta agora também escrita");
        }
    }

    @Test
    void raddhnRounds() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0x1FF); // soma = 0x3FE -> ADDHN puro = 0x03, RADDHN arredonda p/ 0x04
        fp.setElement(2, 0, 1, 0x1FF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrow(
                Ir64VectorNarrowOp.RADDHN, false, 0, 0, 1, 2));

        assertEquals(0x04, fp.element(0, 0, 0));
    }

    @Test
    void acrossLanesReducesAllElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i + 1); // 1..8
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorAcrossLanes(
                Ir64VectorAcrossLanesOp.ADDV, false, 0, 0, 1));
        assertEquals(36, fp.element(0, 0, 0), "soma de 1..8 = 36");

        EXECUTOR.executeOp(core, new Ir64Op.VectorAcrossLanes(
                Ir64VectorAcrossLanesOp.SMAXV, false, 0, 0, 1));
        assertEquals(8, fp.element(0, 0, 0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorAcrossLanes(
                Ir64VectorAcrossLanesOp.UMINV, false, 0, 0, 1));
        assertEquals(1, fp.element(0, 0, 0));
    }

    @Test
    void saddlvWidensResultAndZeroesHighBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -1 & 0xFF);
        fp.setElement(1, 1, 0, 2);
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorAcrossLanes(
                Ir64VectorAcrossLanesOp.SADDLV, false, 0, 0, 1));

        assertEquals((short) 1 & 0xFFFF, fp.element(0, 0, 1), "sext(-1)+2 = 1, resultado em halfword");
        assertEquals(0L, fp.high64(0));
    }

    @Test
    void absAndNeg() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -5 & 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.ABS, false, 0, 0, 1));
        assertEquals(5, fp.element(0, 0, 0));

        fp.setElement(1, 0, 0, 5);
        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.NEG, false, 0, 0, 1));
        assertEquals((byte) -5 & 0xFF, fp.element(0, 0, 0));
    }

    @Test
    void compareAgainstZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0);
        fp.setElement(1, 1, 2, (int) -1);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CMEQ0, true, 2, 0, 1));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));
        assertEquals(0L, fp.element(0, 1, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CMLT0, true, 2, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2), "0 < 0 é falso");
        assertEquals(0xFFFF_FFFFL, fp.element(0, 1, 2), "-1 < 0 é verdadeiro");
    }

    @Test
    void saddlpPairsAdjacentElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // v1.8b = {1,2,3,4,5,6,7,8}
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i + 1);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.SADDLP, false, 0, 0, 1));

        assertEquals(3, fp.element(0, 0, 1), "1+2");
        assertEquals(7, fp.element(0, 1, 1), "3+4");
        assertEquals(11, fp.element(0, 2, 1), "5+6");
        assertEquals(15, fp.element(0, 3, 1), "7+8");
    }

    @Test
    void sadalpAccumulatesIntoExistingWideElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 100);
        fp.setElement(1, 0, 0, 1);
        fp.setElement(1, 1, 0, 2);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.SADALP, false, 0, 0, 1));

        assertEquals(103, fp.element(0, 0, 1), "100 + (1+2) = 103");
    }

    @Test
    void scalarPairwiseAddReducesTwoDoublewordLanes() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, 100L);
        fp.setElement(1, 1, 3, 23L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorScalarPairwiseAdd(0, 1));

        assertEquals(123L, fp.d(0));
        assertEquals(0L, fp.high64(0), "escrita escalar zera os bits altos");
    }
}
