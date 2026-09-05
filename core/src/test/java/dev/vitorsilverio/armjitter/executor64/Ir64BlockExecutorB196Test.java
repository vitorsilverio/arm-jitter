package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.6 — semântica dos 10 encodings avulsos direto no executor (interpretador = oráculo, G1).
/// Blocos A/B (NOP puro) e C (`PACGA`, placeholder) já cobertos trivialmente pelo decoder test;
/// aqui só os blocos com estado real de verdade a verificar (D/E/F/G).
class Ir64BlockExecutorB196Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── Bloco D: ABS ────────────────────────────────────────────────────────────────────────────

    @Test
    void absPositiveNegativeAndIntMinDoesNotSaturate() {
        Aarch64Core core = newCore();
        core.setX(1, 5L);
        EXECUTOR.executeOp(core, new Ir64Op.AbsGeneral(0, 1, true));
        assertEquals(5L, core.x(0));

        core.setX(1, -5L);
        EXECUTOR.executeOp(core, new Ir64Op.AbsGeneral(0, 1, true));
        assertEquals(5L, core.x(0));

        core.setX(1, Long.MIN_VALUE);
        EXECUTOR.executeOp(core, new Ir64Op.AbsGeneral(0, 1, true));
        assertEquals(Long.MIN_VALUE, core.x(0), "ABS de MIN_VALUE não satura, complemento de dois");
    }

    @Test
    void absNarrowIntMinDoesNotSaturate() {
        Aarch64Core core = newCore();
        core.setX(1, Integer.MIN_VALUE & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.AbsGeneral(0, 1, false));
        assertEquals(Integer.MIN_VALUE & 0xFFFF_FFFFL, core.x(0));
    }

    // ── Bloco E: DUP escalar ────────────────────────────────────────────────────────────────────

    @Test
    void dupScalarZeroesRestOfRegister() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x1122_3344_5566_7788L, 0xAABB_CCDD_EEFF_0011L);
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujar Vd antes
        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateElementScalar(0, 0, 1, 7));
        long byte7OfV1 = 0x11L; // byte mais significativo de lo=0x1122334455667788
        assertEquals(byte7OfV1, fp.word(0));
        assertEquals(0L, fp.word(1), "resto de V0 (incl. metade alta) fica zerado");
    }

    @Test
    void dupScalarDoublewordHighHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateElementScalar(3, 0, 1, 1));
        assertEquals(0x2222_2222_2222_2222L, fp.word(0));
        assertEquals(0L, fp.word(1));
    }

    // ── Bloco F: FMOV Vn.D[1] ───────────────────────────────────────────────────────────────────

    @Test
    void fmovHighHalfToGpReadsUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64HighHalfMove(false, 1, 0));
        assertEquals(0x2222_2222_2222_2222L, core.x(0));
    }

    @Test
    void fmovHighHalfToFloatPreservesLowerHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x1111_1111_1111_1111L, 0x0L);
        core.setX(1, 0x3333_3333_3333_3333L);
        EXECUTOR.executeOp(core, new Ir64Op.Fp64HighHalfMove(true, 0, 1));
        assertEquals(0x1111_1111_1111_1111L, fp.word(0), "metade BAIXA preservada");
        assertEquals(0x3333_3333_3333_3333L, fp.word(1), "metade ALTA recebe Xn");
    }

    // ── Bloco G: Vimm/FMOVI_v_h ─────────────────────────────────────────────────────────────────

    @Test
    void movWithQFalseZeroesUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // sujar a metade alta antes
        EXECUTOR.executeOp(core, new Ir64Op.AdvSimdModifiedImmediate64(
                AdvSimdModifiedImmediateOp.MOV, false, 0, 0x1234_5678_9ABC_DEF0L));
        assertEquals(0x1234_5678_9ABC_DEF0L, fp.word(0));
        assertEquals(0L, fp.word(1), "!q zera a metade alta");
    }

    @Test
    void movWithQTrueAppliesSameImmediateToBothHalves() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        EXECUTOR.executeOp(core, new Ir64Op.AdvSimdModifiedImmediate64(
                AdvSimdModifiedImmediateOp.MOV, true, 0, 0xABCDL));
        assertEquals(0xABCDL, fp.word(0));
        assertEquals(0xABCDL, fp.word(1));
    }

    @Test
    void orrAndBicReadModifyWritePerHalfIndependently() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x0F0FL, 0xF0F0L);
        EXECUTOR.executeOp(core, new Ir64Op.AdvSimdModifiedImmediate64(
                AdvSimdModifiedImmediateOp.ORR, true, 0, 0x00FFL));
        assertEquals(0x0FFFL, fp.word(0));
        assertEquals(0xF0FFL, fp.word(1));

        fp.setQ(0, 0xFFFFL, 0xFFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.AdvSimdModifiedImmediate64(
                AdvSimdModifiedImmediateOp.BIC, false, 0, 0x00FFL));
        assertEquals(0xFF00L, fp.word(0));
        assertEquals(0L, fp.word(1), "!q zera a metade alta mesmo em BIC");
    }

    @Test
    void mvnInvertsImmediateAtExecutionNotAtDecode() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        EXECUTOR.executeOp(core, new Ir64Op.AdvSimdModifiedImmediate64(
                AdvSimdModifiedImmediateOp.MVN, false, 0, 0x0000_0000_0000_00FFL));
        assertEquals(~0x0000_0000_0000_00FFL, fp.word(0));
    }
}
