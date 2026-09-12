package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.11b — `FMLAL_hb_v`/`FMLALL_sb_v`/`FMLAL_hb_vi`/`FMLALL_sb_vi` no executor: a ponte
/// registrador↔núcleo (núcleo já testado exaustivamente em
/// {@code AdvSimdLanesFp8FusedMultiplyAddTest}) E o consumo de verdade de `FPMR` — mesma
/// disciplina de {@code Ir64VectorFpArithmeticExecutorFp8Test} (B19.11), mas com os campos
/// PRÓPRIOS da multiplicação (`OSM`/`LSCALE` sem máscara para `FMLALL_sb`), nunca os de
/// conversão.
class Ir64VectorFpArithmeticExecutorFp8FusedMultiplyAddTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final boolean E4M3 = true;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void writeFpmr(Aarch64Core core, long value) {
        core.setX(0, value);
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPMR, 0));
    }

    private static final long FPMR_F8S1_E4M3 = 0b001L;
    private static final long FPMR_F8S2_E4M3 = 0b001L << 3;
    private static final int FPMR_LSCALE_SHIFT = 16;
    private static final int FPMR_OSM_BIT = 14;

    private static int fp8(float value) {
        return AdvSimdLanes.floatToFp8(value, E4M3, false);
    }

    // ── FMLAL_hb_v (destino binary16) ───────────────────────────────────────────────────────────

    @Test
    void fmlalHbVMultipliesFullWidthAndAccumulatesInHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, 2 * i, 0, fp8(1.0f + i));
            fp.setElement(2, 2 * i, 0, fp8(2.0f));
            fp.setElement(0, i, 1, AdvSimdLanes.halfBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 0, 0, 1, 2));
        for (int i = 0; i < 8; i++) {
            assertEquals(2.0f * (1.0f + i), AdvSimdLanes.halfToFloat(fp.element(0, i, 1)));
        }
    }

    @Test
    void fmlalHbVSourceByteSelectPicksParity() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        // Byte par = 10.0, byte ímpar = 20.0 — `sourceByteSelect` escolhe qual metade é lida.
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, 2 * i, 0, fp8(10.0f));
            fp.setElement(1, 2 * i + 1, 0, fp8(20.0f));
            fp.setElement(2, 2 * i, 0, fp8(1.0f));
            fp.setElement(2, 2 * i + 1, 0, fp8(1.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 0, 0, 1, 2));
        assertEquals(10.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));

        for (int i = 0; i < 8; i++) {
            fp.setElement(0, i, 1, AdvSimdLanes.halfBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 1, 0, 1, 2));
        assertEquals(20.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    @Test
    void fmlalHbVUsesLscaleLow4BitsOnly() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // LSCALE = 0x50 (bits[6:4]=101): os 4 bits BAIXOS consumidos por FMLAL_hb são 0x0, então
        // o escalonamento efetivo é 2^-0 = 1 -- prova que só [3:0] entra, não o campo de 7 bits
        // inteiro (ao contrário de FMLALL_sb, testado abaixo).
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3 | (0x50L << FPMR_LSCALE_SHIFT));
        fp.setElement(1, 0, 0, fp8(4.0f));
        fp.setElement(2, 0, 0, fp8(1.0f));
        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 0, 0, 1, 2));
        assertEquals(4.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    // ── FMLALL_sb_v (destino binary32) ──────────────────────────────────────────────────────────

    @Test
    void fmlallSbVMultipliesFullWidthAndAccumulatesInSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, 4 * i, 0, fp8(1.0f + i));
            fp.setElement(2, 4 * i, 0, fp8(2.0f));
            fp.setElement(0, i, 2, AdvSimdLanes.floatBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(true, 0, 0, 1, 2));
        for (int i = 0; i < 4; i++) {
            assertEquals(2.0f * (1.0f + i), Float.intBitsToFloat((int) fp.element(0, i, 2)));
        }
    }

    @Test
    void fmlallSbVUsesFullSevenBitLscaleUnmasked() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // LSCALE = 0x50 = 0b1010000 (7 bits inteiros): 4.0 * 1.0 * 2^-0x50 -- um valor bem menor
        // que 4.0, provando que os 3 bits ALTOS (perdidos pela máscara de 4 bits de FMLAL_hb)
        // também entram para FMLALL_sb.
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3 | (0x50L << FPMR_LSCALE_SHIFT));
        fp.setElement(1, 0, 0, fp8(4.0f));
        fp.setElement(2, 0, 0, fp8(1.0f));
        fp.setElement(0, 0, 2, AdvSimdLanes.floatBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(true, 0, 0, 1, 2));
        float result = Float.intBitsToFloat((int) fp.element(0, 0, 2));
        assertNotEquals(4.0f, result);
        assertEquals(Math.scalb(4.0, -0x50), result, 0.0);
    }

    @Test
    void fmlallSbVSourceByteSelectPicksPhase() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int phase = 0; phase < 4; phase++) {
            fp.setElement(1, phase, 0, fp8(10.0f + phase));
            fp.setElement(2, phase, 0, fp8(1.0f));
        }
        for (int select = 0; select < 4; select++) {
            fp.setElement(0, 0, 2, AdvSimdLanes.floatBits(0.0f));
            EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(true, select, 0, 1, 2));
            assertEquals(10.0f + select, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        }
    }

    // ── `OSM` (só a multiplicação — diferente de `OSC`, testado em `Ir64VectorFpArithmeticExecutorFp8Test`) ──

    @Test
    void osmSaturatesOverflowToMaxNormalInsteadOfInfinity() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3 | (1L << FPMR_OSM_BIT));
        fp.setElement(1, 0, 0, fp8(448.0f));
        fp.setElement(2, 0, 0, fp8(448.0f));
        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 0, 0, 1, 2));
        assertEquals(65504f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    // ── FDOT_hb_v continua ausente (não escopo desta task) ──────────────────────────────────────
    // Ver Aarch64AdvSimdFp8FusedMultiplyAddDecoderTest para a regressão negativa de decode.

    // ── Aliasing (E10) ───────────────────────────────────────────────────────────────────────────

    @Test
    void rdSameAsRnAndRmIsSafeForHalfDestination() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 8; i++) {
            fp.setElement(0, 2 * i, 0, fp8(1.0f + i)); // Rd também é Rn (largura mista, mesmo reg)
        }
        fp.setElement(2, 0, 0, fp8(1.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLong(false, 0, 0, 0, 2));
        assertEquals(1.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    @Test
    void rdSameAsRnIsSafeForSingleDestinationIndexed() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 4; i++) {
            fp.setElement(0, 4 * i, 0, fp8(2.0f + i));
        }
        fp.setElement(3, 0, 0, fp8(3.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLongByElement(true, 0, 0, 0, 3, 0));
        assertEquals(6.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    // ── FMLAL_hb_vi / FMLALL_sb_vi ───────────────────────────────────────────────────────────────

    @Test
    void fmlalHbViBroadcastsSingleRmByte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, 2 * i, 0, fp8(1.0f + i));
            fp.setElement(0, i, 1, AdvSimdLanes.halfBits(0.0f));
        }
        fp.setElement(3, 5, 0, fp8(10.0f)); // índice 5, resto do registrador irrelevante
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLongByElement(false, 0, 0, 1, 3, 5));
        for (int i = 0; i < 8; i++) {
            assertEquals(10.0f * (1.0f + i), AdvSimdLanes.halfToFloat(fp.element(0, i, 1)));
        }
    }

    @Test
    void fmlallSbViBroadcastsSingleRmByte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, 4 * i, 0, fp8(1.0f + i));
            fp.setElement(0, i, 2, AdvSimdLanes.floatBits(0.0f));
        }
        fp.setElement(2, 0, 0, fp8(5.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8FusedMultiplyAddLongByElement(true, 0, 0, 1, 2, 0));
        for (int i = 0; i < 4; i++) {
            assertEquals(5.0f * (1.0f + i), Float.intBitsToFloat((int) fp.element(0, i, 2)));
        }
    }
}
