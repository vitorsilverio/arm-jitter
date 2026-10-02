package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `SP_EL0` acessível de EL1 via `MSR`/`MRS` — achado real da F11 (Raspberry Pi 3, `kernel8.img`):
/// `__primary_switched` do Linux/arm64 faz `msr sp_el0, x4` para guardar o ponteiro `current`.
/// Decode confirmado com `aarch64-none-elf-as`/`objdump` (devkitA64).
class Aarch64SpEl0SystemRegisterTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(8)));
    }

    private static Ir64Op.SystemRegister decode(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void msrSpEl0Decodes() {
        // d5184104: msr sp_el0, x4
        Ir64Op.SystemRegister op = decode(0xd5184104);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.SP_EL0, op.register());
        assertEquals(4, op.rt());
    }

    @Test
    void mrsSpEl0Decodes() {
        // d5384105: mrs x5, sp_el0
        Ir64Op.SystemRegister op = decode(0xd5384105);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.SP_EL0, op.register());
        assertEquals(5, op.rt());
    }

    @Test
    void encodingsVizinhosDeSpEl0NaoViramSpEl0() {
        // mesmo CRn=4/op1=0, mas op2=1 (d5384125) ou CRm=4 (d5384405): não são SP_EL0.
        for (int word : new int[] {0xd5384125, 0xd5384405}) {
            TestAddressSpace raw = new TestAddressSpace(4);
            raw.put32(0, word);
            try {
                Object op = DECODER.decode(AddressSpace64.wrapping(raw), 0);
                assertTrue(!(op instanceof Ir64Op.SystemRegister sr) || sr.register() != Aarch64SystemRegisterId.SP_EL0);
            } catch (UnsupportedOperationException expected) {
                // recusado: também correto
            }
        }
    }

    @Test
    void spEl0IsHandledIntrinsicallyByTheCore() {
        assertTrue(newCore().handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.SP_EL0));
    }

    @Test
    void spEl0RoundTripsAndIsIndependentOfTheActiveEl1Stack() {
        Aarch64Core core = newCore();
        core.exceptionState().setInEl1(true);
        core.setSp(0x8000_1000L);
        core.setX(4, 0xFFFF_FFC0_8123_4560L);

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.SP_EL0, 4));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.SP_EL0, 5));

        assertEquals(0xFFFF_FFC0_8123_4560L, core.x(5));
        assertEquals(0x8000_1000L, core.sp(), "SP ativo em EL1 (SP_EL1) não pode ser tocado por SP_EL0");
        core.exceptionState().setInEl1(false);
        assertEquals(0xFFFF_FFC0_8123_4560L, core.sp(), "em EL0 o SP ativo é o SP_EL0 gravado");
    }

    @Test
    void cntkctlEl1DecodesAndRoundTrips() {
        // d538e113: mrs x19, cntkctl_el1; d518e113: msr cntkctl_el1, x19
        Ir64Op.SystemRegister read = decode(0xd538e113);
        assertEquals(true, read.read());
        assertEquals(Aarch64SystemRegisterId.CNTKCTL_EL1, read.register());
        assertEquals(19, read.rt());
        Ir64Op.SystemRegister write = decode(0xd518e113);
        assertEquals(false, write.read());
        assertEquals(Aarch64SystemRegisterId.CNTKCTL_EL1, write.register());

        Aarch64Core core = newCore();
        assertTrue(core.handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.CNTKCTL_EL1));
        core.setX(19, 0x3);
        EXECUTOR.executeOp(core, write);
        core.setX(19, 0);
        EXECUTOR.executeOp(core, read);
        assertEquals(0x3L, core.x(19));
    }

    @Test
    void cntkctlNeighboursAreNotCntkctl() {
        // mesmo CRn=14/op1=0 com CRm=2 (d538e213) ou op2=1 (d538e133)
        for (int word : new int[] {0xd538e213, 0xd538e133}) {
            try {
                Object op = DECODER.decode(AddressSpace64.wrapping(rawWith(word)), 0);
                assertTrue(!(op instanceof Ir64Op.SystemRegister sr)
                        || sr.register() != Aarch64SystemRegisterId.CNTKCTL_EL1);
            } catch (UnsupportedOperationException expected) {
                // recusado: correto
            }
        }
    }

    private static TestAddressSpace rawWith(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return raw;
    }
}
