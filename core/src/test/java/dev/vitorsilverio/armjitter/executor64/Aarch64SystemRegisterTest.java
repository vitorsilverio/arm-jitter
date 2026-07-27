package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `MRS`/`MSR (register)` (B6.6.1): mecanismo de acesso a registrador de sistema — decode em si
/// já coberto por {@code Aarch64DecoderCorpusTest} (offsets 0x26c+, corpus real). Aqui: o
/// {@link Aarch64SystemRegisterBus}, o comportamento "sem hospedeiro" e o executor
/// ({@code Ir64BlockExecutor#executeOp}), usando um bus FALSO só para os testes desta task (D2 —
/// a instalação REAL de um bus de MMU é B6.6.3, fora do escopo aqui).
class Aarch64SystemRegisterTest {
    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(16);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    /// Bus falso, só para teste: guarda os valores escritos e "atende" só o subconjunto de
    /// registradores passado no construtor.
    private static final class FakeSystemRegisterBus implements Aarch64SystemRegisterBus {
        private final Set<Aarch64SystemRegisterId> handled;
        private final Map<Aarch64SystemRegisterId, Long> values = new EnumMap<>(Aarch64SystemRegisterId.class);

        FakeSystemRegisterBus(Set<Aarch64SystemRegisterId> handled) {
            this.handled = handled;
        }

        @Override public boolean handles(Aarch64SystemRegisterId register) {
            return handled.contains(register);
        }

        @Override public long read(Aarch64SystemRegisterId register) {
            return values.getOrDefault(register, 0L);
        }

        @Override public void write(Aarch64SystemRegisterId register, long value) {
            values.put(register, value);
        }
    }

    @Test
    void mrsWithoutBusInstalledThrows() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister mrs = new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.SCTLR_EL1, 0);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, mrs));
    }

    @Test
    void msrWithoutBusInstalledThrows() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister msr = new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.SCTLR_EL1, 1);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, msr));
    }

    @Test
    void registerNotHandledByInstalledBusThrows() {
        // Bus instalado, mas só atende TTBR0_EL1 — MRS a SCTLR_EL1 continua sem hospedeiro.
        Aarch64Core core = newCore();
        core.setSystemRegisterBus(new FakeSystemRegisterBus(EnumSet.of(Aarch64SystemRegisterId.TTBR0_EL1)));
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister mrs = new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.SCTLR_EL1, 0);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, mrs));
    }

    @Test
    void msrThenMrsRoundTripsThroughTheInstalledBus() {
        Aarch64Core core = newCore();
        core.setSystemRegisterBus(new FakeSystemRegisterBus(EnumSet.of(Aarch64SystemRegisterId.SCTLR_EL1)));
        core.setX(1, 0x123L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        Ir64Op.SystemRegister msr = new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.SCTLR_EL1, 1);
        executor.executeOp(core, msr);

        Ir64Op.SystemRegister mrs = new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.SCTLR_EL1, 2);
        executor.executeOp(core, mrs);

        assertEquals(0x123L, core.x(2), "MRS deve ler de volta o mesmo valor escrito por MSR");
    }

    @Test
    void mrsWithXzrDestinationDiscardsTheWrite() {
        Aarch64Core core = newCore();
        core.setSystemRegisterBus(new FakeSystemRegisterBus(EnumSet.of(Aarch64SystemRegisterId.VBAR_EL1)));
        core.setX(0, 0xDEADBEEFL); // valor a ser escrito via MSR antes do MRS a XZR
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.VBAR_EL1, 0));

        // mrs xzr, vbar_el1 (rt=31): a leitura acontece (chama bus.read), mas a escrita em XZR é
        // descartada — Aarch64Core#setX(31,...) já é NOP.
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.VBAR_EL1, 31));
        assertEquals(0L, core.x(31));
    }

    @Test
    void msrWithXzrSourceWritesZero() {
        Aarch64Core core = newCore();
        FakeSystemRegisterBus bus = new FakeSystemRegisterBus(EnumSet.of(Aarch64SystemRegisterId.VBAR_EL1));
        core.setSystemRegisterBus(bus);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        // msr vbar_el1, xzr (rt=31): Aarch64Core#x(31) já devolve 0.
        executor.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.VBAR_EL1, 31));
        assertEquals(0L, bus.read(Aarch64SystemRegisterId.VBAR_EL1));
    }
}
