package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
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
                Ir64VectorThreeSameOp.ADD, false, false, 0, 0, 1, 2));

        assertEquals(0x01, fp.element(0, 0, 0), "0xFF + 0x02 trunca para byte = 0x01");
    }

    @Test
    void nonQuadWriteZeroesHighBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // "sujeira" pré-existente

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.ADD, false, false, 2, 0, 1, 2));

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
                Ir64VectorThreeSameOp.CMGT, false, true, 2, 0, 1, 2));

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
                Ir64VectorThreeSameOp.CMHI, false, false, 0, 0, 1, 2));
        assertEquals(0xFFL, fp.element(0, 0, 0), "0xFF >u 0x01: todos-1 (CMHI não assinado)");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.CMGT, false, false, 0, 0, 1, 2));
        assertEquals(0L, fp.element(0, 0, 0), "0xFF (=-1) >s 0x01 é falso: 0 (CMGT assinado)");
    }

    @Test
    void shaddHalvesWithoutRoundingSigned() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -3 & 0xFF);
        fp.setElement(2, 0, 0, (byte) -2 & 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SHADD, false, false, 0, 0, 1, 2));

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
                Ir64VectorThreeSameOp.SRHADD, false, false, 0, 0, 1, 2));

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
                Ir64VectorThreeSameOp.SABA, false, false, 0, 0, 1, 2));

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
                Ir64VectorThreeSameOp.MLA, false, false, 0, 0, 1, 2));
        assertEquals(112, fp.element(0, 0, 0), "100 + 3*4 = 112");

        fp.setElement(0, 0, 0, 100);
        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.MLS, false, false, 0, 0, 1, 2));
        assertEquals(88, fp.element(0, 0, 0), "100 - 3*4 = 88");
    }

    @Test
    void pmulIsPolynomialMultiply() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b011);
        fp.setElement(2, 0, 0, 0b101);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.PMUL, false, false, 0, 0, 1, 2));

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
                Ir64VectorUnaryOp.ABS, false, false, 0, 0, 1));
        assertEquals(5, fp.element(0, 0, 0));

        fp.setElement(1, 0, 0, 5);
        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.NEG, false, false, 0, 0, 1));
        assertEquals((byte) -5 & 0xFF, fp.element(0, 0, 0));
    }

    @Test
    void compareAgainstZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0);
        fp.setElement(1, 1, 2, (int) -1);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CMEQ0, false, true, 2, 0, 1));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));
        assertEquals(0L, fp.element(0, 1, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CMLT0, false, true, 2, 0, 1));
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
                Ir64VectorUnaryOp.SADDLP, false, false, 0, 0, 1));

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
                Ir64VectorUnaryOp.SADALP, false, false, 0, 0, 1));

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

    // ── B8.8: saturação/deslocamento/estreitamento ─────────────────────────────────────────────

    @Test
    void sqaddSaturatesAtSignedMax() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 100);
        fp.setElement(2, 0, 0, 100);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQADD, false, false, 0, 0, 1, 2));

        assertEquals(127, fp.element(0, 0, 0), "100+100=200 satura em 127 (byte assinado)");
    }

    @Test
    void uqaddSaturatesAtUnsignedMax() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 200);
        fp.setElement(2, 0, 0, 100);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.UQADD, false, false, 0, 0, 1, 2));

        assertEquals(255, fp.element(0, 0, 0), "200+100=300 satura em 255 (byte não assinado)");
    }

    @Test
    void sqsubSaturatesAtSignedMin() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -100 & 0xFF);
        fp.setElement(2, 0, 0, 100);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQSUB, false, false, 0, 0, 1, 2));

        assertEquals(0x80, fp.element(0, 0, 0), "-100-100=-200 satura em -128 (0x80)");
    }

    @Test
    void uqsubSaturatesAtZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 1);
        fp.setElement(2, 0, 0, 5);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.UQSUB, false, false, 0, 0, 1, 2));

        assertEquals(0, fp.element(0, 0, 0), "1-5 satura em 0 (não assinado)");
    }

    @Test
    void sshlByRegisterShiftsLeftOrRightBySign() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 3);
        fp.setElement(2, 0, 0, 2); // >=0: desloca à esquerda
        fp.setElement(1, 1, 0, (byte) -8 & 0xFF);
        fp.setElement(2, 1, 0, (byte) -1 & 0xFF); // <0: desloca à direita pela MAGNITUDE

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SSHL, false, false, 0, 0, 1, 2));

        assertEquals(12, fp.element(0, 0, 0), "3<<2 = 12");
        assertEquals((byte) -4 & 0xFF, fp.element(0, 1, 0), "-8>>1 = -4 (aritmético)");
    }

    @Test
    void sqshlByRegisterSaturatesOnlyOnLeftShift() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 100);
        fp.setElement(2, 0, 0, 2); // esquerda: satura
        fp.setElement(1, 1, 0, 100);
        fp.setElement(2, 1, 0, (byte) -1 & 0xFF); // direita: NÃO satura, truncamento comum

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQSHL, false, false, 0, 0, 1, 2));

        assertEquals(127, fp.element(0, 0, 0), "100<<2=400 satura em 127");
        assertEquals(50, fp.element(0, 1, 0), "100>>1=50, sem saturar");
    }

    @Test
    void sqrshlRoundsOnRightShiftUnlikeSqshl() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 5);
        fp.setElement(2, 0, 0, (byte) -1 & 0xFF); // desloca 1 à direita, arredondando

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRSHL, false, false, 0, 0, 1, 2));

        assertEquals(3, fp.element(0, 0, 0), "(5+1)>>1 = 3 (SQSHL sem arredondar daria 2)");
    }

    @Test
    void sqdmulhSaturatesAtInt16MinSelfProduct() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0x8000);
        fp.setElement(2, 0, 1, 0x8000);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQDMULH, false, false, 1, 0, 1, 2));

        assertEquals(0x7FFF, fp.element(0, 0, 1), "2*(-32768)^2 >> 16 = 32768, satura em 32767");
    }

    @Test
    void sqrdmulhRoundsBeforeShifting() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 16384);
        fp.setElement(2, 0, 1, 3);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQDMULH, false, false, 1, 0, 1, 2));
        long withoutRounding = fp.element(0, 0, 1);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRDMULH, false, false, 1, 0, 1, 2));
        long withRounding = fp.element(0, 0, 1);

        assertEquals(1, withoutRounding, "(2*16384*3)>>16 = 1 (trunca)");
        assertEquals(2, withRounding, "com arredondamento (+2^15 antes do shift) = 2");
    }

    @Test
    void sqrdmlahAccumulatesRoundingDoublingMultiplyHigh() {
        // B11.4 (`FEAT_RDM`): MESMO produto de {@link #sqrdmulhRoundsBeforeShifting} (16384*3,
        // arredondado = 2), acumulado sobre um `Rd` inicial em vez de sobrescrever.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 5); // halfword v0[0] = 5 (acumulador)
        fp.setElement(1, 0, 1, 16384);
        fp.setElement(2, 0, 1, 3);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRDMLAH, false, false, 1, 0, 1, 2));

        assertEquals(7, fp.element(0, 0, 1), "5 + round(2*16384*3 >> 16) = 5 + 2 = 7");
    }

    @Test
    void sqrdmlshSubtractsRoundingDoublingMultiplyHigh() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 5);
        fp.setElement(1, 0, 1, 16384);
        fp.setElement(2, 0, 1, 3);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRDMLSH, false, false, 1, 0, 1, 2));

        assertEquals(3, fp.element(0, 0, 1), "5 - round(2*16384*3 >> 16) = 5 - 2 = 3");
    }

    @Test
    void sqrdmlahSaturatesOnAccumulateAtInt16Max() {
        // Prova que a SEGUNDA saturação (soma sobre `Rd`) dispara de verdade, não só a de
        // `SQRDMULH` embutida no produto — `Rd` já no limite positivo de halfword.
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, 0x7FFF); // halfword v0[0] = INT16_MAX
        fp.setElement(1, 0, 1, 16384);
        fp.setElement(2, 0, 1, 3); // produto arredondado = 2 (ver sqrdmulhRoundsBeforeShifting)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRDMLAH, false, false, 1, 0, 1, 2));

        assertEquals(0x7FFF, fp.element(0, 0, 1), "32767 + 2 satura em 32767 (INT16_MAX)");
    }

    @Test
    void sqrdmlshSaturatesOnAccumulateAtInt16Min() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, (short) 0x8000 & 0xFFFF); // halfword v0[0] = INT16_MIN
        fp.setElement(1, 0, 1, 16384);
        fp.setElement(2, 0, 1, 3);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.SQRDMLSH, false, false, 1, 0, 1, 2));

        assertEquals(0x8000, fp.element(0, 0, 1), "-32768 - 2 satura em -32768 (INT16_MIN)");
    }

    @Test
    void sqdmullWidensAndSaturatesAtInt32Max() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0x8000);
        fp.setElement(2, 0, 1, 0x8000);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SQDMULL, false, 1, 0, 1, 2));

        assertEquals(0x7FFF_FFFFL, fp.element(0, 0, 2), "2*(-32768)^2 = 2^31, satura em 2^31-1");
    }

    @Test
    void sqdmlalAccumulatesWithDoubleSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 2, 10L); // Rd atual (word) = 10
        fp.setElement(1, 0, 1, 100);
        fp.setElement(2, 0, 1, 100);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticWidening(
                Ir64VectorWideningOp.SQDMLAL, false, 1, 0, 1, 2));

        assertEquals(20010L, fp.element(0, 0, 2), "10 + 2*100*100 = 20010");
    }

    @Test
    void suqaddSaturatesSignedAccumulator() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 100); // Rd (assinado) = 100
        fp.setElement(1, 0, 0, 50);  // Rn (não assinado) = 50

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.SUQADD, false, false, 0, 0, 1));

        assertEquals(127, fp.element(0, 0, 0), "100+50=150 satura em 127 (Rd assinado)");
    }

    @Test
    void usqaddSaturatesAtZeroWhenOperandNegative() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 10); // Rd (não assinado) = 10
        fp.setElement(1, 0, 0, (byte) -20 & 0xFF); // Rn (assinado) = -20

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.USQADD, false, false, 0, 0, 1));

        assertEquals(0, fp.element(0, 0, 0), "10-20=-10 satura em 0 (Rd não assinado)");
    }

    @Test
    void sqxtnNarrowsWithSignedSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 200); // halfword 200 excede byte assinado

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrowUnary(
                Ir64VectorNarrowUnaryOp.SQXTN, false, false, 0, 0, 1));

        assertEquals(127, fp.element(0, 0, 0));
        assertEquals(0L, fp.high64(0), "escrita destrutiva zera os bits altos");
    }

    @Test
    void sqxtunSaturatesNegativeSourceToZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0xFFFB); // halfword -5 (assinado)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrowUnary(
                Ir64VectorNarrowUnaryOp.SQXTUN, false, false, 0, 0, 1));

        assertEquals(0, fp.element(0, 0, 0), "fonte assinada negativa satura em 0 (destino não assinado)");
    }

    @Test
    void uqxtnNarrowsWithUnsignedSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 300);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrowUnary(
                Ir64VectorNarrowUnaryOp.UQXTN, false, false, 0, 0, 1));

        assertEquals(255, fp.element(0, 0, 0));
    }

    @Test
    void narrowUnaryScalarProcessesOnlyLaneZeroAndZeroesLow64Above() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujeira pré-existente
        fp.setElement(1, 0, 1, 50);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticNarrowUnary(
                Ir64VectorNarrowUnaryOp.SQXTN, true, false, 0, 0, 1));

        assertEquals(50, fp.element(0, 0, 0));
        assertEquals(0L, fp.high64(0));
        assertEquals(50L, fp.low64(0), "escalar zera TUDO acima do elemento, mesmo dentro do low64");
    }

    @Test
    void sshrArithmeticShiftRight() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0xFFFF_FFE0L); // word -32

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SSHR, false, false, 2, 4, 0, 1));

        assertEquals(0xFFFF_FFFEL, fp.element(0, 0, 2), "-32>>4 = -2");
    }

    @Test
    void usraAccumulatesLogicalShiftRight() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 10);
        fp.setElement(1, 0, 0, 6);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.USRA, false, false, 0, 1, 0, 1));

        assertEquals(13, fp.element(0, 0, 0), "10 + (6>>>1) = 13");
    }

    @Test
    void sliInsertsShiftedLowBitsPreservingHigh() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 0xAB);
        fp.setElement(1, 0, 0, 0x01);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SLI, false, false, 0, 4, 0, 1));

        assertEquals(0x1B, fp.element(0, 0, 0), "baixo(0xB) preservado de current, alto(0x1) do shift");
    }

    @Test
    void sriInsertsShiftedHighBitsPreservingLow() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 0xAB);
        fp.setElement(1, 0, 0, 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SRI, false, false, 0, 4, 0, 1));

        assertEquals(0xAF, fp.element(0, 0, 0), "alto(0xA) preservado de current, baixo(0xF) do shift");
    }

    @Test
    void sqshlImmediateSaturates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 100);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SQSHL, false, false, 0, 2, 0, 1));

        assertEquals(127, fp.element(0, 0, 0), "100<<2=400 satura em 127");
    }

    @Test
    void sqshluSaturatesSignedSourceToUnsignedZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -1 & 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SQSHLU, false, false, 0, 1, 0, 1));

        assertEquals(0, fp.element(0, 0, 0), "fonte assinada negativa satura em 0 (saída não assinada)");
    }

    @Test
    void shiftImmediateScalarProcessesOnlyLaneZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
        fp.setElement(1, 0, 0, 50);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftImmediate(
                Ir64VectorShiftOp.SQSHL, true, false, 0, 1, 0, 1));

        assertEquals(100, fp.element(0, 0, 0), "50<<1=100, dentro do intervalo, sem saturar");
        assertEquals(0L, fp.high64(0));
        assertEquals(100L, fp.low64(0), "escalar zera acima do elemento, mesmo no low64");
    }

    @Test
    void shrnTruncatesWithoutRoundingOrSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 496); // halfword

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftNarrowImmediate(
                Ir64VectorShiftNarrowOp.SHRN, false, false, 0, 4, 0, 1));

        assertEquals(31, fp.element(0, 0, 0), "496>>>4 = 31");
    }

    @Test
    void rshrnRoundsUnlikeShrn() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 5);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftNarrowImmediate(
                Ir64VectorShiftNarrowOp.RSHRN, false, false, 0, 1, 0, 1));

        assertEquals(3, fp.element(0, 0, 0), "(5+1)>>1 = 3 (SHRN sem arredondar daria 2)");
    }

    @Test
    void sqshrnSaturatesSignedNarrow() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 300);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftNarrowImmediate(
                Ir64VectorShiftNarrowOp.SQSHRN, false, false, 0, 1, 0, 1));

        assertEquals(127, fp.element(0, 0, 0), "300>>1=150 satura em 127 (byte assinado)");
    }

    @Test
    void uqshrnSaturatesUnsignedNarrow() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 1000);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftNarrowImmediate(
                Ir64VectorShiftNarrowOp.UQSHRN, false, false, 0, 1, 0, 1));

        assertEquals(255, fp.element(0, 0, 0), "1000>>1=500 satura em 255");
    }

    @Test
    void sqshrunSaturatesNegativeSignedSourceToZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, (short) -100 & 0xFFFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftNarrowImmediate(
                Ir64VectorShiftNarrowOp.SQSHRUN, false, false, 0, 1, 0, 1));

        assertEquals(0, fp.element(0, 0, 0), "-100>>1=-50 satura em 0 (saída não assinada)");
    }

    @Test
    void sshllSignExtendsThenShifts() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, (byte) -1 & 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftWidenImmediate(
                Ir64VectorShiftWidenOp.SSHLL, false, 0, 2, 0, 1));

        assertEquals(0xFFFCL, fp.element(0, 0, 1), "sext(-1)<<2 = -4, halfword 0xFFFC");
    }

    @Test
    void ushllZeroExtendsInsteadOfSignExtending() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0xFF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorShiftWidenImmediate(
                Ir64VectorShiftWidenOp.USHLL, false, 0, 0, 0, 1));

        assertEquals(0x00FFL, fp.element(0, 0, 1), "zext(0xFF)<<0 = 0x00FF, NÃO 0xFFFF (sem sinal)");
    }

    // ── B8.18: AdvSIMD "three same" lógico ──────────────────────────────────────────────────────

    @Test
    void andOrrEorBitwise() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b1100);
        fp.setElement(2, 0, 0, 0b1010);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.AND, false, false, 0, 0, 1, 2));
        assertEquals(0b1000, fp.element(0, 0, 0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.ORR, false, false, 0, 0, 1, 2));
        assertEquals(0b1110, fp.element(0, 0, 0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.EOR, false, false, 0, 0, 1, 2));
        assertEquals(0b0110, fp.element(0, 0, 0));
    }

    @Test
    void bicAndOrnClearOrSetBitsFromComplement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0xFF);
        fp.setElement(2, 0, 0, 0x0F);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.BIC, false, false, 0, 0, 1, 2));
        assertEquals(0xF0, fp.element(0, 0, 0), "0xFF & ~0x0F = 0xF0");

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.ORN, false, false, 0, 0, 1, 2));
        assertEquals(0xFF, fp.element(0, 0, 0), "0xFF | ~0x0F trunca para 0xFF");
    }

    @Test
    void bslSelectsByCurrentRdMask() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 0b1100_0011); // Rd = máscara de controle
        fp.setElement(1, 0, 0, 0b1111_0000); // Rn
        fp.setElement(2, 0, 0, 0b0000_1111); // Rm

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.BSL, false, false, 0, 0, 1, 2));

        // bit=1 na máscara -> vem de Rn; bit=0 -> vem de Rm: 1100_0011 -> Rn(1111_0000) nos bits
        // 7,6,1,0 + Rm(0000_1111) nos bits 5,4,3,2 = 1100_1100
        assertEquals(0b1100_1100, fp.element(0, 0, 0));
    }

    @Test
    void bitInsertsWhereRmMaskIsTrue() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 0b1111_1111); // Rd atual
        fp.setElement(1, 0, 0, 0b0000_0000); // Rn
        fp.setElement(2, 0, 0, 0b1111_0000); // Rm = máscara

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.BIT, false, false, 0, 0, 1, 2));

        // onde Rm=1: vem de Rn (0); onde Rm=0: preserva Rd (1) -> 0000_1111
        assertEquals(0b0000_1111, fp.element(0, 0, 0));
    }

    @Test
    void bifInsertsWhereRmMaskIsFalse() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 0, 0b1111_1111); // Rd atual
        fp.setElement(1, 0, 0, 0b0000_0000); // Rn
        fp.setElement(2, 0, 0, 0b1111_0000); // Rm = máscara

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticThreeSame(
                Ir64VectorThreeSameOp.BIF, false, false, 0, 0, 1, 2));

        // onde Rm=0: vem de Rn (0); onde Rm=1: preserva Rd (1) -> 1111_0000
        assertEquals(0b1111_0000, fp.element(0, 0, 0));
    }

    // ── B8.18: resto de "two-register miscellaneous" inteiro ───────────────────────────────────

    @Test
    void sqabsSaturatesAtIntMin() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0x80); // byte -128 (Byte.MIN_VALUE)

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.SQABS, false, false, 0, 0, 1));

        assertEquals(0x7F, fp.element(0, 0, 0), "|(-128)| satura em 127, não vira -128 de novo");
    }

    @Test
    void sqnegSaturatesAtIntMin() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0x80); // byte -128

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.SQNEG, false, false, 0, 0, 1));

        assertEquals(0x7F, fp.element(0, 0, 0), "-(-128) satura em 127");
    }

    @Test
    void clzCountsLeadingZerosPerElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0x0000_0001); // word, só bit0 setado

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CLZ, false, false, 2, 0, 1));

        assertEquals(31, fp.element(0, 0, 2));
    }

    @Test
    void clzOfZeroIsElementWidth() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0); // halfword zero

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CLZ, false, false, 1, 0, 1));

        assertEquals(16, fp.element(0, 0, 1));
    }

    @Test
    void clsCountsBitsMatchingSignExcludingSignBit() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, 0b0111_1111_1111_1110); // halfword positivo, 1 bit não-sinal difere

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CLS, false, false, 1, 0, 1));

        assertEquals(0, fp.element(0, 0, 1), "bit logo após o sinal já difere: 0 bits repetidos");
    }

    @Test
    void clsOfAllZerosIsWidthMinusOne() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0); // byte zero

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CLS, false, false, 0, 0, 1));

        assertEquals(7, fp.element(0, 0, 0), "todos os 7 bits não-sinal repetem o sinal (0)");
    }

    @Test
    void cntPopulationCountPerByte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b1011_0110);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.CNT, false, false, 0, 0, 1));

        assertEquals(5, fp.element(0, 0, 0));
    }

    @Test
    void notComplementsEveryBit() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b1111_0000);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.NOT, false, false, 0, 0, 1));

        assertEquals(0b0000_1111, fp.element(0, 0, 0));
    }

    @Test
    void rbitReversesBitOrderWithinByte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b1000_0001);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.RBIT, false, false, 0, 0, 1));

        assertEquals(0b1000_0001, fp.element(0, 0, 0), "palíndromo, reversão não muda");
    }

    @Test
    void rbitOfAsymmetricByte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 0, 0b0000_0001);

        EXECUTOR.executeOp(core, new Ir64Op.VectorArithmeticUnary(
                Ir64VectorUnaryOp.RBIT, false, false, 0, 0, 1));

        assertEquals(0b1000_0000, fp.element(0, 0, 0));
    }
}
