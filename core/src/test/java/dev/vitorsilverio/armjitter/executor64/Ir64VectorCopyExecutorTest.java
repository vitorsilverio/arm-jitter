package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `DUP`/`INS`/`SMOV`/`UMOV` (B8.12, AdvSIMD copy).
class Ir64VectorCopyExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void dupElementReplicatesByteAcrossQuadword() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x0706050403020100L, 0L); // byte i = i

        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateElement(true, 0, 0, 1, 3));

        assertEquals(0x0303030303030303L, fp.low64(0));
        assertEquals(0x0303030303030303L, fp.high64(0));
    }

    @Test
    void dupElementDFormZeroesHighBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L); // lixo prévio nos bits altos
        fp.setQ(1, 0x1122334455667788L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateElement(false, 2, 0, 1, 1));

        // esz=2 (word), index=1: elemento = bits[63:32] de Rn = 0x11223344.
        assertEquals(0x1122334411223344L, fp.low64(0));
        assertEquals(0L, fp.high64(0));
    }

    @Test
    void dupGeneralReplicatesWordFromWRegister() {
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFFFFFF_ABCD1234L); // W1 = 0xABCD1234 (bits altos ignorados)

        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateGeneral(true, 2, 0, 1));

        Aarch64FpRegisters fp = core.fp();
        assertEquals(0xABCD1234ABCD1234L, fp.low64(0));
        assertEquals(0xABCD1234ABCD1234L, fp.high64(0));
    }

    @Test
    void dupGeneralDoublewordReadsXRegister() {
        Aarch64Core core = newCore();
        core.setX(1, 0x0011223344556677L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorDuplicateGeneral(true, 3, 0, 1));

        Aarch64FpRegisters fp = core.fp();
        assertEquals(0x0011223344556677L, fp.low64(0));
        assertEquals(0x0011223344556677L, fp.high64(0));
    }

    @Test
    void insGeneralWritesSingleElementWithoutTouchingRest() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x1111111111111111L, 0x2222222222222222L);
        core.setX(1, 0xFFFFFFFF_000000AAL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorInsertGeneral(0, 0, 1, 3));

        assertEquals(0x11111111AA111111L, fp.low64(0));
        assertEquals(0x2222222222222222L, fp.high64(0));
    }

    @Test
    void insElementCopiesElementWithoutTouchingRest() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x1111111111111111L, 0x2222222222222222L);
        fp.setQ(1, 0x33333333333333CCL, 0L); // elemento[0] byte = 0xCC

        EXECUTOR.executeOp(core, new Ir64Op.VectorInsertElement(0, 0, 1, 4, 0));

        assertEquals(0x111111CC11111111L, fp.low64(0));
        assertEquals(0x2222222222222222L, fp.high64(0));
    }

    @Test
    void smovSignExtendsNegativeByteToX() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x00000000000000FFL, 0L); // elemento[0] byte = 0xFF (-1 assinado)

        EXECUTOR.executeOp(core, new Ir64Op.VectorMoveElement(true, true, 0, 0, 1, 0));

        assertEquals(-1L, core.x(0));
    }

    @Test
    void smovSignExtendsNegativeByteToW() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x00000000000000FFL, 0L);
        core.setX(0, -1L); // bits altos preexistentes, devem ser zerados por escrita W

        EXECUTOR.executeOp(core, new Ir64Op.VectorMoveElement(true, false, 0, 0, 1, 0));

        assertEquals(0xFFFFFFFFL, core.x(0)); // -1 (32-bit) zero-estendido no X
    }

    @Test
    void umovZeroExtendsByteToW() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x00000000000000FFL, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorMoveElement(false, false, 0, 0, 1, 0));

        assertEquals(0xFFL, core.x(0));
    }

    @Test
    void umovDoublewordCopiesFullElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x0011223344556677L, 0x8899AABBCCDDEEFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorMoveElement(false, true, 3, 0, 1, 1));

        assertEquals(0x8899AABBCCDDEEFFL, core.x(0));
    }
}
