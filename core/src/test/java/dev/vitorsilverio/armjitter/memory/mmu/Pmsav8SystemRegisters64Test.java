package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B20.8: round-trip cru de `MRS`/`MSR` para os 5 registradores de MPU de EL1 (`MPUIR_EL1`
/// só-leitura, `PRSELR_EL1`/`PRBAR_EL1`/`PRLAR_EL1`/`PRENR_EL1`) + o bit `M` de `SCTLR_EL1` com
/// semântica PMSA (habilita a MPU) — mirror de `Pmsav8MpuRegistersTest` (32-bit) para o barramento
/// A64. Testa {@link Pmsav8SystemRegisters64} isolado, sem decoder/core rodando instruções reais
/// (isso é `Aarch64Pmsav8AbortTest`).
class Pmsav8SystemRegisters64Test {
    private static Pmsav8SystemRegisters64 newBus(int regionCount) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        return new Pmsav8SystemRegisters64(core, regionCount);
    }

    @Test
    void regionCountMustBePositive() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8SystemRegisters64(core, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8SystemRegisters64(core, -1));
    }

    @Test
    void handlesTheFiveMpuRegistersAndSctlr() {
        Pmsav8SystemRegisters64 bus = newBus(4);
        assertTrue(bus.handles(Aarch64SystemRegisterId.SCTLR_EL1));
        assertTrue(bus.handles(Aarch64SystemRegisterId.MPUIR_EL1));
        assertTrue(bus.handles(Aarch64SystemRegisterId.PRSELR_EL1));
        assertTrue(bus.handles(Aarch64SystemRegisterId.PRBAR_EL1));
        assertTrue(bus.handles(Aarch64SystemRegisterId.PRLAR_EL1));
        assertTrue(bus.handles(Aarch64SystemRegisterId.PRENR_EL1));
        assertFalse(bus.handles(Aarch64SystemRegisterId.TTBR0_EL1), "PMSA não tem TTBR (sem tradução)");
    }

    @Test
    void mpuirIsReadOnlyAndReportsRegionCount() {
        Pmsav8SystemRegisters64 bus = newBus(6);
        assertEquals(6L, bus.read(Aarch64SystemRegisterId.MPUIR_EL1));

        bus.write(Aarch64SystemRegisterId.MPUIR_EL1, 99); // ignorada (só leitura)

        assertEquals(6L, bus.read(Aarch64SystemRegisterId.MPUIR_EL1));
    }

    @Test
    void prselrOutOfRangeWriteIsSilentlyIgnored() {
        Pmsav8SystemRegisters64 bus = newBus(2);
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 1);
        assertEquals(1L, bus.read(Aarch64SystemRegisterId.PRSELR_EL1));

        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 5); // fora de faixa (regionCount=2)

        assertEquals(1L, bus.read(Aarch64SystemRegisterId.PRSELR_EL1), "PRSELR_EL1 não deve mudar");
    }

    @Test
    void prselrNegativeWriteIsSilentlyIgnored() {
        Pmsav8SystemRegisters64 bus = newBus(2);
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 1);

        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, -1);

        assertEquals(1L, bus.read(Aarch64SystemRegisterId.PRSELR_EL1), "PRSELR_EL1 não deve mudar");
    }

    @Test
    void prbarPrlarPrenrRoundTripPerSelectedRegion() {
        Pmsav8SystemRegisters64 bus = newBus(2);
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 0);
        bus.write(Aarch64SystemRegisterId.PRBAR_EL1, 0x1234_5678_9AL);
        bus.write(Aarch64SystemRegisterId.PRLAR_EL1, 0xFEDC_BA98_76L);
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 1);
        bus.write(Aarch64SystemRegisterId.PRBAR_EL1, 0x1111L);
        bus.write(Aarch64SystemRegisterId.PRLAR_EL1, 0x2222L);

        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 0);
        assertEquals(0x1234_5678_9AL, bus.read(Aarch64SystemRegisterId.PRBAR_EL1));
        assertEquals(0xFEDC_BA98_76L, bus.read(Aarch64SystemRegisterId.PRLAR_EL1));
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 1);
        assertEquals(0x1111L, bus.read(Aarch64SystemRegisterId.PRBAR_EL1));
        assertEquals(0x2222L, bus.read(Aarch64SystemRegisterId.PRLAR_EL1));

        bus.write(Aarch64SystemRegisterId.PRENR_EL1, 0x3L);
        assertEquals(0x3L, bus.read(Aarch64SystemRegisterId.PRENR_EL1));
    }

    @Test
    void sctlrMBitRoundTripsThroughMpuEnabled() {
        Pmsav8SystemRegisters64 bus = newBus(1);
        assertEquals(0L, bus.read(Aarch64SystemRegisterId.SCTLR_EL1), "MPU desligada no reset");

        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 1L);

        assertEquals(1L, bus.read(Aarch64SystemRegisterId.SCTLR_EL1) & 1L);
    }

    /// Cobre o ramo `BR=1` de `sctlrValue()` (o ramo `BR=0` já é exercitado pela leitura no reset em
    /// {@link #sctlrMBitRoundTripsThroughMpuEnabled}) — achado da auditoria JaCoCo: nenhum teste
    /// lia `SCTLR_EL1` de volta depois de ligar `BR`.
    @Test
    void sctlrBrBitRoundTripsThroughBackgroundRegionEnabled() {
        Pmsav8SystemRegisters64 bus = newBus(1);

        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 1L << 17);

        assertEquals(1L << 17, bus.read(Aarch64SystemRegisterId.SCTLR_EL1) & (1L << 17));
    }

    /// `ESR_EL1`/`FAR_EL1`/`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1` delegam para
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState} — achado da auditoria
    /// JaCoCo: nenhum teste desta classe exercitava os 5 ramos de `read`/`write` correspondentes
    /// (só `Aarch64Pmsav8AbortTest`, indiretamente, via `Aarch64Core#enterMemoryAbort`).
    @Test
    void exceptionStateRegistersRoundTripThroughTheBus() {
        Pmsav8SystemRegisters64 bus = newBus(1);

        bus.write(Aarch64SystemRegisterId.ESR_EL1, 0x1234L);
        bus.write(Aarch64SystemRegisterId.FAR_EL1, 0x5000L);
        bus.write(Aarch64SystemRegisterId.VBAR_EL1, 0x8000L);
        bus.write(Aarch64SystemRegisterId.ELR_EL1, 0x40L);
        bus.write(Aarch64SystemRegisterId.SPSR_EL1, 0x3C5L);

        assertEquals(0x1234L, bus.read(Aarch64SystemRegisterId.ESR_EL1));
        assertEquals(0x5000L, bus.read(Aarch64SystemRegisterId.FAR_EL1));
        assertEquals(0x8000L, bus.read(Aarch64SystemRegisterId.VBAR_EL1));
        assertEquals(0x40L, bus.read(Aarch64SystemRegisterId.ELR_EL1));
        assertEquals(0x3C5L, bus.read(Aarch64SystemRegisterId.SPSR_EL1));
    }

    /// `read`/`write` para um registrador que `handles` recusa lançam `UnsupportedOperationException`
    /// diretamente (defesa redundante ao contrato do chamador, mesmo padrão de
    /// `Aarch64VmsaSystemRegisters`) — achado da auditoria JaCoCo: os dois `default` do `switch`
    /// nunca eram exercitados.
    @Test
    void readAndWriteOfAnUnhandledRegisterThrow() {
        Pmsav8SystemRegisters64 bus = newBus(1);

        assertThrows(UnsupportedOperationException.class,
                () -> bus.read(Aarch64SystemRegisterId.TTBR0_EL1));
        assertThrows(UnsupportedOperationException.class,
                () -> bus.write(Aarch64SystemRegisterId.TTBR0_EL1, 0L));
    }
}
