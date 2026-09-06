package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.12 — semântica de `FEAT_I8MM` direto no executor (interpretador = oráculo, G1). Núcleo
/// (produto escalar de sinal misto/matriz) reusa {@link AdvSimdLanes#dotProduct}/
/// {@link AdvSimdLanes#matrixMultiplyAccumulate} (o primeiro já existia desde a B13.18, o segundo é
/// NOVO desta task); aqui só a ponte registrador↔núcleo das 6 linhas + as provas do Aceite:
/// acumulação com WRAP (nunca satura), matriz de teste ASSIMÉTRICA, e sinal-por-operando distinguindo
/// `USDOT` do que `SDOT`/`UDOT` produziriam nos MESMOS bits (nenhum dos dois tem decoder A64 ainda,
/// B13.18 — comparado direto contra o núcleo {@link AdvSimdLanes#dotProduct}).
class Ir64VectorArithmeticExecutorI8mmTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void setByte(Aarch64FpRegisters fp, int reg, int byteIndex, int value) {
        fp.setElement(reg, byteIndex, 0, value & 0xFF);
    }

    // ── USDOT (vetorial) ────────────────────────────────────────────────────────────────────────

    @Test
    void usdotSignPerOperandDiffersFromHypotheticalSdotAndUdot() {
        // Byte com bit7 setado nos dois operandos: 0x80 (128 sem sinal / -128 com sinal).
        Aarch64FpRegisters raw = new Aarch64FpRegisters();
        setByte(raw, 1, 0, 0x80); // Vn lane0 byte0
        setByte(raw, 2, 0, 0x80); // Vm lane0 byte0
        int wordsPerReg = Aarch64FpRegisters.WORDS_PER_REGISTER;

        // USDOT real: Rn sem sinal (128), Rm com sinal (-128) -> 128 * -128 = -16384.
        AdvSimdLanes.dotProduct(raw, false, true, 1, 3 * wordsPerReg, 1 * wordsPerReg, 2 * wordsPerReg);
        assertEquals(-16384, (int) raw.element(3, 0, 2));

        // Hipotético SDOT (Rn/Rm com sinal): -128 * -128 = 16384 — DIFERENTE do USDOT acima.
        AdvSimdLanes.dotProduct(raw, true, true, 1, 4 * wordsPerReg, 1 * wordsPerReg, 2 * wordsPerReg);
        assertEquals(16384, (int) raw.element(4, 0, 2));

        // Hipotético UDOT (Rn/Rm sem sinal): 128 * 128 = 16384 — também DIFERENTE do USDOT.
        AdvSimdLanes.dotProduct(raw, false, false, 1, 5 * wordsPerReg, 1 * wordsPerReg, 2 * wordsPerReg);
        assertEquals(16384, (int) raw.element(5, 0, 2));

        assertNotEquals(raw.element(3, 0, 2), raw.element(4, 0, 2), "USDOT != SDOT nos mesmos bits");
        assertNotEquals(raw.element(3, 0, 2), raw.element(5, 0, 2), "USDOT != UDOT nos mesmos bits");
    }

    @Test
    void usdotVectorViaExecutorAccumulatesWithWrapNotSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 1); // Vn lane0 byte0 = 1 (sem sinal)
        setByte(fp, 2, 0, 2); // Vm lane0 byte0 = 2 (com sinal, positivo)
        // dot = 1*2 = 2. Acumulador pré-existente perto do limite de `int32` -> soma estoura com WRAP.
        fp.setElement(0, 0, 2, 0x7FFF_FFFF);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, false, true, 0, 1, 2));
        assertEquals(0x8000_0001L, fp.element(0, 0, 2), "wrap: 0x7FFFFFFF + 2 = 0x80000001, NUNCA satura");
    }

    @Test
    void usdotVectorDForm2sZeroesHighHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 3);
        setByte(fp, 2, 0, 4);
        // Acumulador (lane baixa) LIMPO; só a metade ALTA fica suja, para provar que a escrita
        // destrutiva a zera (sem contaminar o resultado do acúmulo, que lê a lane baixa).
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(false, false, true, 0, 1, 2));
        assertEquals(12L, fp.element(0, 0, 2));
        assertEquals(0L, fp.word(1), "q=false zera a metade alta de Vd");
    }

    // ── USDOT_vi/SUDOT_vi (indexado) ────────────────────────────────────────────────────────────

    @Test
    void usdotByElementReplicatesFixedGroupAndKeepsSignPerOperand() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn.16B: lane0 = [1,1,1,1] (grupo de 4 bytes), lane1 = [2,2,2,2].
        for (int i = 0; i < 4; i++) {
            setByte(fp, 1, i, 1);
            setByte(fp, 1, 4 + i, 2);
        }
        // Vm: grupo fixo no índice 1 = [0x80,0,0,0] (só byte0 tem bit7 setado, com sinal -> -128).
        setByte(fp, 2, 4, 0x80);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProductByElement(true, false, true, 0, 1, 2, 1));
        // lane0 = 1(sem sinal)*-128 = -128 ; lane1 = 2*-128 = -256
        assertEquals(-128, (int) fp.element(0, 0, 2));
        assertEquals(-256, (int) fp.element(0, 1, 2));
    }

    @Test
    void sudotByElementHasOppositeSignConventionOfUsdot() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 0x80); // Vn lane0 byte0 = -128 (SUDOT: Rn COM sinal)
        setByte(fp, 2, 0, 2);    // Vm grupo0 byte0 = 2 (SUDOT: Rm SEM sinal)
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProductByElement(true, true, false, 0, 1, 2, 0));
        assertEquals(-256, (int) fp.element(0, 0, 2), "-128 * 2 = -256 (Rn assinado, Rm sem sinal)");
    }

    // ── SMMLA/UMMLA/USMMLA (matricial) ──────────────────────────────────────────────────────────

    @Test
    void smmlaAsymmetricMatrixAccumulatesElementByElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn: linha0 = [1,2,3,4,5,6,7,8], linha1 = [8,7,6,5,4,3,2,1] (2x8 assimétrica).
        int[] rows = {1, 2, 3, 4, 5, 6, 7, 8, 8, 7, 6, 5, 4, 3, 2, 1};
        // Vm: coluna0 = [1,0,0,0,0,0,0,0], coluna1 = [0,1,0,0,0,0,0,2] (8x2 assimétrica).
        int[] cols = {1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 2};
        for (int i = 0; i < 16; i++) {
            setByte(fp, 1, i, rows[i]);
            setByte(fp, 2, i, cols[i]);
        }
        fp.setElement(0, 0, 2, 100); // Vd[0][0] pré-existente
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(true, true, 0, 1, 2));
        // dot(row0,col0) = 1*1 = 1 ; dot(row0,col1) = 2*1 + 8*2 = 18
        // dot(row1,col0) = 8*1 = 8 ; dot(row1,col1) = 7*1 + 1*2 = 9
        assertEquals(101, (int) fp.element(0, 0, 2), "100 (pré-existente) + dot(row0,col0)");
        assertEquals(18, (int) fp.element(0, 1, 2));
        assertEquals(8, (int) fp.element(0, 2, 2));
        assertEquals(9, (int) fp.element(0, 3, 2));
    }

    @Test
    void ummlaAndUsmmlaDifferFromSmmlaAndFromEachOtherBySign() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Só byte0 de Vn/Vm não-zero, com bit7 setado nos dois -> sinal importa.
        setByte(fp, 1, 0, 0x80);
        setByte(fp, 2, 0, 0x80);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(true, true, 0, 1, 2)); // SMMLA
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(false, false, 3, 1, 2)); // UMMLA
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(false, true, 4, 1, 2)); // USMMLA
        long smmla = fp.element(0, 0, 2); // (-128)*(-128) = 16384
        long ummla = fp.element(3, 0, 2); // 128*128 = 16384
        long usmmla = fp.element(4, 0, 2); // 128*(-128) = -16384
        assertEquals(16384, (int) smmla);
        assertEquals(16384, (int) ummla);
        assertEquals(-16384, (int) usmmla);
        assertNotEquals(smmla, usmmla);
        assertNotEquals(ummla, usmmla);
    }

    @Test
    void matrixMultiplyAccumulateWrapsInsteadOfSaturating() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 2);
        setByte(fp, 2, 0, 3);
        fp.setElement(0, 0, 2, 0x7FFF_FFFF); // acumulador pré-existente perto do limite
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(false, false, 0, 1, 2));
        // dot(row0,col0) = 2*3 = 6 ; 0x7FFFFFFF + 6 = 0x80000005 (WRAP, nunca satura).
        assertEquals(0x8000_0005L, fp.element(0, 0, 2));
    }
}
