package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `SPSel`/`PAN`/`UAO`/`DIT`/`SSBS`/`TCO`/`ALLINT` via `MRS`/`MSR` (B8.17) — completa a varredura
/// dos campos `PSTATE` que a forma `MSR (immediate)` (B8.3) já tratava como NOP puro (nenhum
/// consumidor modelado: sem MMU checando `PAN`/`UAO`, sem banking real de `SP_EL0`/`SP_EL1`
/// distinto por `SPSel`, sem telemetria de `DIT`, sem mitigação Spectre de `SSBS`, sem MTE que
/// `TCO` afetaria, sem NMI que `ALLINT` mascararia). A forma registrador precisa de armazenamento
/// de verdade (um `MRS` tem que devolver ALGO) — mesmo padrão de {@code FPCR}/{@code FPSR} (B8.15).
/// Corpus real via `aarch64-none-elf-as -march=armv8.5-a`/`objdump` (devkitA64).
class Aarch64PstateFieldRegistersTest {
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
    void spselDecodes() {
        // d5384200: mrs x0, spsel / d5184200: msr spsel, x0
        assertEquals(Aarch64SystemRegisterId.SPSEL, decode(0xd5384200).register());
        assertEquals(Aarch64SystemRegisterId.SPSEL, decode(0xd5184200).register());
    }

    @Test
    void uaoDecodes() {
        // d5384281: mrs x1, uao
        assertEquals(Aarch64SystemRegisterId.UAO, decode(0xd5384281).register());
    }

    @Test
    void panDecodes() {
        // d5384262: mrs x2, pan
        assertEquals(Aarch64SystemRegisterId.PAN, decode(0xd5384262).register());
    }

    @Test
    void ditDecodes() {
        // d53b42a3: mrs x3, dit
        assertEquals(Aarch64SystemRegisterId.DIT, decode(0xd53b42a3).register());
    }

    @Test
    void ssbsDecodes() {
        // d53b42c4: mrs x4, ssbs
        assertEquals(Aarch64SystemRegisterId.SSBS, decode(0xd53b42c4).register());
    }

    @Test
    void tcoDecodes() {
        // d53b42e5: mrs x5, tco
        assertEquals(Aarch64SystemRegisterId.TCO, decode(0xd53b42e5).register());
    }

    @Test
    void allintDecodes() {
        // d5384306: mrs x6, allint / d5184306: msr allint, x6
        assertEquals(Aarch64SystemRegisterId.ALLINT, decode(0xd5384306).register());
        assertEquals(Aarch64SystemRegisterId.ALLINT, decode(0xd5184306).register());
    }

    @Test
    void allSevenRoundTripIndependentlyThroughExecutor() {
        Aarch64Core core = newCore();
        Aarch64SystemRegisterId[] registers = {
                Aarch64SystemRegisterId.SPSEL, Aarch64SystemRegisterId.PAN,
                Aarch64SystemRegisterId.UAO, Aarch64SystemRegisterId.DIT,
                Aarch64SystemRegisterId.SSBS, Aarch64SystemRegisterId.TCO,
                Aarch64SystemRegisterId.ALLINT,
        };
        for (int i = 0; i < registers.length; i++) {
            core.setX(i, 0x1000L + i);
            EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, registers[i], i));
        }
        for (int i = 0; i < registers.length; i++) {
            EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, registers[i], 20));
            assertEquals(0x1000L + i, core.x(20), "registro " + registers[i] + " vazou pra outro escaninho");
        }
    }
}
