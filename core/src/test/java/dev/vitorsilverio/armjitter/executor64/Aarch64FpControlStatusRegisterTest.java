package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `FPCR`/`FPSR` (B8.15) — pendência explícita desde B6.6.1 (D3)/B6.5.1: armazenamento puro, sem
/// efeito semântico real (arredondamento continua fixo em round-to-nearest-even). Decode via
/// corpus real (`aarch64-none-elf-as`/`objdump`, devkitA64) + round-trip no executor.
class Aarch64FpControlStatusRegisterTest {
    private static final dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder DECODER =
            new dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder();
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(8);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    private static Ir64Op.SystemRegister decode(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsFpcrDecodes() {
        // d53b4400: mrs x0, fpcr
        Ir64Op.SystemRegister op = decode(0xd53b4400);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.FPCR, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrFpcrDecodes() {
        // d51b4400: msr fpcr, x0
        Ir64Op.SystemRegister op = decode(0xd51b4400);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.FPCR, op.register());
    }

    @Test
    void mrsFpsrDecodes() {
        // d53b4421: mrs x1, fpsr
        Ir64Op.SystemRegister op = decode(0xd53b4421);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.FPSR, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void msrFpsrDecodes() {
        // d51b4421: msr fpsr, x1
        Ir64Op.SystemRegister op = decode(0xd51b4421);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.FPSR, op.register());
    }

    @Test
    void fpcrAndFpsrRoundTripIndependently() {
        Aarch64Core core = newCore();
        core.setX(0, 0x0000_0000_0180_0000L); // FPCR: RMode=01 (positivo), só pra provar storage
        core.setX(1, 0x0000_0000_0000_0001L); // FPSR: IOC setado pelo guest

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPCR, 0));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPSR, 1));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.FPCR, 2));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.FPSR, 3));

        assertEquals(0x0000_0000_0180_0000L, core.x(2));
        assertEquals(0x0000_0000_0000_0001L, core.x(3));
    }
}
