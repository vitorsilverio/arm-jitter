package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.23 — semântica de `SDOT_v`/`UDOT_v`/`SDOT_vi`/`UDOT_vi` direto no executor (G1). Reusa 100%
/// o núcleo {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProduct}/
/// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProductByElement} da B19.12 — só a
/// ponte registrador↔núcleo (`signedN=signedM=true` para `SDOT`, `false` para `UDOT`) e as provas do
/// Aceite: produto escalar correto contra `soma(a_i*b_i)` e `SDOT`×`UDOT` divergindo quando o bit
/// alto de um byte importa.
class Ir64VectorArithmeticExecutorDotProductResidualTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void setByte(Aarch64FpRegisters fp, int reg, int byteIndex, int value) {
        fp.setElement(reg, byteIndex, 0, value & 0xFF);
    }

    // ── SDOT_v/UDOT_v (vetorial) ────────────────────────────────────────────────────────────────

    @Test
    void sdotVectorMatchesSumOfSignedByteProducts() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn lane0 = [1,2,3,4], Vm lane0 = [5,6,7,8] -> soma(1*5+2*6+3*7+4*8) = 5+12+21+32 = 70.
        int[] n = {1, 2, 3, 4};
        int[] m = {5, 6, 7, 8};
        for (int i = 0; i < 4; i++) {
            setByte(fp, 1, i, n[i]);
            setByte(fp, 2, i, m[i]);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, true, true, 0, 1, 2));
        assertEquals(70, (int) fp.element(0, 0, 2));
    }

    @Test
    void sdotAndUdotDifferWhenHighBitOfByteIsSet() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Byte 0x80 (128 sem sinal / -128 com sinal) nos dois operandos, resto zero.
        setByte(fp, 1, 0, 0x80);
        setByte(fp, 2, 0, 0x80);

        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, true, true, 3, 1, 2));
        // SDOT: -128 * -128 = 16384.
        assertEquals(16384, (int) fp.element(3, 0, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, false, false, 4, 1, 2));
        // UDOT: 128 * 128 = 16384 -- mesmo valor absoluto aqui, então também comparamos um caso
        // onde só um operando tem o bit alto setado (assimetria real de sinal).
        assertEquals(16384, (int) fp.element(4, 0, 2));

        setByte(fp, 1, 4, 0x80); // lane1 byte0 = 0x80
        setByte(fp, 2, 4, 1);    // lane1 byte0 do Vm = 1 (positivo nos dois sentidos)
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, true, true, 5, 1, 2));
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, false, false, 6, 1, 2));
        // SDOT lane1: -128*1 = -128 (contribui negativo); UDOT lane1: 128*1 = 128 (positivo).
        assertNotEquals(fp.element(5, 1, 2), fp.element(6, 1, 2), "SDOT != UDOT quando o sinal importa");
    }

    @Test
    void udotVectorAccumulatesWithWrapNotSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 1);
        setByte(fp, 2, 0, 2);
        fp.setElement(0, 0, 2, 0x7FFF_FFFF);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(true, false, false, 0, 1, 2));
        assertEquals(0x8000_0001L, fp.element(0, 0, 2), "wrap: 0x7FFFFFFF + 2 = 0x80000001, NUNCA satura");
    }

    @Test
    void sdotVectorDForm2sZeroesHighHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 3);
        setByte(fp, 2, 0, 4);
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProduct(false, true, true, 0, 1, 2));
        assertEquals(12L, fp.element(0, 0, 2));
        assertEquals(0L, fp.word(1), "q=false zera a metade alta de Vd");
    }

    // ── SDOT_vi/UDOT_vi (indexado) ──────────────────────────────────────────────────────────────

    @Test
    void sdotByElementReplicatesFixedGroupWithSignedOperands() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 4; i++) {
            setByte(fp, 1, i, 1);
            setByte(fp, 1, 4 + i, 2);
        }
        // Grupo fixo (índice 1) do Vm: byte0 = 0x80 (-128 com sinal).
        setByte(fp, 2, 4, 0x80);
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProductByElement(true, true, true, 0, 1, 2, 1));
        // lane0 = 1(com sinal)*-128 = -128 ; lane1 = 2*-128 = -256
        assertEquals(-128, (int) fp.element(0, 0, 2));
        assertEquals(-256, (int) fp.element(0, 1, 2));
    }

    @Test
    void udotByElementTreatsHighBitByteAsPositive() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setByte(fp, 1, 0, 1);
        setByte(fp, 2, 0, 0x80); // grupo0 byte0 = 128 sem sinal
        EXECUTOR.executeOp(core, new Ir64Op.VectorIntegerDotProductByElement(true, false, false, 0, 1, 2, 0));
        assertEquals(128, (int) fp.element(0, 0, 2), "UDOT_vi: 1 * 128 = 128 (sem sinal)");
    }
}
