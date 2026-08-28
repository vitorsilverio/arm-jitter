package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `TPIDR_EL0`/`TPIDRRO_EL0` (B8.14) — achado real tentando rodar `busybox` aarch64 (musl) no
/// armbox: `MSR TPIDR_EL0, x0` é a PRIMEIRA coisa que qualquer `crt0` real grava (bloco TLS) antes
/// de `main()`. Decode via corpus real (`aarch64-none-elf-as`/`objdump`, devkitA64) + semântica no
/// executor (armazenamento puro, mesmo padrão de {@code Aarch64IrqAndCpuIdentityTest} p/ `TPIDR_EL1`).
class Aarch64ThreadPointerEl0Test {
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
    void msrTpidrEl0Decodes() {
        // d51bd040: msr tpidr_el0, x0
        Ir64Op.SystemRegister op = decode(0xd51bd040);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.TPIDR_EL0, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void mrsTpidrEl0Decodes() {
        // d53bd041: mrs x1, tpidr_el0
        Ir64Op.SystemRegister op = decode(0xd53bd041);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.TPIDR_EL0, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void msrTpidrroEl0Decodes() {
        // d51bd062: msr tpidrro_el0, x2
        Ir64Op.SystemRegister op = decode(0xd51bd062);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.TPIDRRO_EL0, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void mrsTpidrroEl0Decodes() {
        // d53bd063: mrs x3, tpidrro_el0
        Ir64Op.SystemRegister op = decode(0xd53bd063);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.TPIDRRO_EL0, op.register());
        assertEquals(3, op.rt());
    }

    @Test
    void tpidrEl0RoundTripsThroughExecutor() {
        Aarch64Core core = newCore();
        core.setX(5, 0x1234_5678_9ABC_DEF0L);

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.TPIDR_EL0, 5));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.TPIDR_EL0, 6));

        assertEquals(0x1234_5678_9ABC_DEF0L, core.x(6));
    }

    @Test
    void tpidrroEl0RoundTripsThroughExecutorAndIsIndependentOfTpidrEl0() {
        Aarch64Core core = newCore();
        core.setX(0, 0x1111L);
        core.setX(1, 0x2222L);

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.TPIDR_EL0, 0));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.TPIDRRO_EL0, 1));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.TPIDR_EL0, 2));
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.TPIDRRO_EL0, 3));

        assertEquals(0x1111L, core.x(2));
        assertEquals(0x2222L, core.x(3));
    }
}
