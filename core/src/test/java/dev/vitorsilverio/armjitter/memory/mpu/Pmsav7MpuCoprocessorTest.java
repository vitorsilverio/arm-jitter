package dev.vitorsilverio.armjitter.memory.mpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B20.2: liga `MCR`/`MRC` ao banco {@link Pmsav7MpuRegisters}. Prova a sequência canônica de
/// configuração de MPU do Aceite da task (ler `MPUIR`, laço `RGNR`/`DRBAR`/`DRACR`/`DRSR`, ligar
/// `SCTLR.M`) sem `UNDEFINED`, round-trip fiel, e as decisões das Armadilhas 1-5.
class Pmsav7MpuCoprocessorTest {

    @Test
    void mpuirReportsConfiguredRegionCountAndIsReadOnly() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(16);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertTrue(cp15.handles(15, 0, 0, 0, 4), "MPUIR (c0,c0,4)");
        assertEquals(16 << 8, cp15.read(15, 0, 0, 0, 4), "DREGION em bits[15:8]");

        cp15.write(15, 0, 0, 0, 4, 0xFFFF_FFFF); // escrita em registrador read-only: ignorada
        assertEquals(16 << 8, cp15.read(15, 0, 0, 0, 4), "MPUIR não muda por escrita");
    }

    @Test
    void rgnrSelectsIndependentRegionSlotsWithoutAliasing() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(4);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        cp15.write(15, 0, 6, 2, 0, 0); // RGNR=0
        cp15.write(15, 0, 6, 1, 0, 0x1000_0000); // DRBAR
        cp15.write(15, 0, 6, 1, 2, 0x0000_0009); // DRSR: EN=1, RSIZE=4 (32 bytes)
        cp15.write(15, 0, 6, 1, 4, 0x0000_0300); // DRACR: AP=full

        cp15.write(15, 0, 6, 2, 0, 1); // RGNR=1
        cp15.write(15, 0, 6, 1, 0, 0x2000_0000);
        cp15.write(15, 0, 6, 1, 2, 0x0000_000B);
        cp15.write(15, 0, 6, 1, 4, 0x0000_0100);

        cp15.write(15, 0, 6, 2, 0, 2); // RGNR=2
        cp15.write(15, 0, 6, 1, 0, 0x3000_0000);
        cp15.write(15, 0, 6, 1, 2, 0x0000_000D);
        cp15.write(15, 0, 6, 1, 4, 0x0000_0000);

        cp15.write(15, 0, 6, 2, 0, 0); // volta pra RGNR=0
        assertEquals(0x1000_0000, cp15.read(15, 0, 6, 1, 0), "DRBAR[0] não deve ter sido pisado");
        assertEquals(0x0000_0009, cp15.read(15, 0, 6, 1, 2), "DRSR[0]");
        assertEquals(0x0000_0300, cp15.read(15, 0, 6, 1, 4), "DRACR[0]");

        cp15.write(15, 0, 6, 2, 0, 1);
        assertEquals(0x2000_0000, cp15.read(15, 0, 6, 1, 0), "DRBAR[1]");

        cp15.write(15, 0, 6, 2, 0, 2);
        assertEquals(0x3000_0000, cp15.read(15, 0, 6, 1, 0), "DRBAR[2]");

        // Leitura crua por índice, a API pensada para o consumidor da B20.3.
        assertEquals(0x1000_0000, mpu.base(0));
        assertEquals(0x2000_0000, mpu.base(1));
        assertEquals(0x3000_0000, mpu.base(2));
    }

    /// Armadilha 3 da B20.2: `RGNR` fora de faixa é ignorado silenciosamente (mesmo comportamento
    /// do QEMU real, `pmsav7_rgnr_write` em `target/arm/helper.c` — loga guest error e retorna sem
    /// escrever), nunca `ArrayIndexOutOfBoundsException` atravessando o core.
    @Test
    void rgnrOutOfRangeWriteIsIgnored() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(4);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        cp15.write(15, 0, 6, 2, 0, 2); // RGNR=2 (válido)
        cp15.write(15, 0, 6, 2, 0, 4); // fora de faixa (regionCount=4, índices 0-3): ignorado
        assertEquals(2, cp15.read(15, 0, 6, 2, 0), "RGNR mantém o último valor válido");

        cp15.write(15, 0, 6, 2, 0, -1); // fora de faixa pelo lado negativo
        assertEquals(2, cp15.read(15, 0, 6, 2, 0));
    }

    @Test
    void sctlrMBitTogglesMpuEnabled() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertFalse(mpu.mpuEnabled(), "reset real de hardware: MPU começa desligada");

        cp15.write(15, 0, 1, 0, 0, 1); // SCTLR.M=1
        assertTrue(mpu.mpuEnabled());
        assertEquals(1, cp15.read(15, 0, 1, 0, 0));

        cp15.write(15, 0, 1, 0, 0, 0); // SCTLR.M=0
        assertFalse(mpu.mpuEnabled());
    }

    /// Armadilha 1 da B20.2: `SCTLR.BR` no bit 17 (única confirmação = QEMU `SCTLR_BR (1U << 17)`).
    @Test
    void sctlrBrBitTogglesBackgroundRegionEnabled() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertFalse(mpu.backgroundRegionEnabled());

        cp15.write(15, 0, 1, 0, 0, 1 << 17); // SCTLR.BR=1
        assertTrue(mpu.backgroundRegionEnabled());
        assertEquals(1 << 17, cp15.read(15, 0, 1, 0, 0));

        cp15.write(15, 0, 1, 0, 0, 0);
        assertFalse(mpu.backgroundRegionEnabled());
    }

    @Test
    void sctlrVBitTogglesHighVectorsOnCore() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        ArmCore core = coreWithoutCode();
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);

        cp15.write(15, 0, 1, 0, 0, 1 << 13); // SCTLR.V=1
        assertTrue(core.highVectors());
        assertEquals(1 << 13, cp15.read(15, 0, 1, 0, 0));
    }

    /// Round-trip fiel: bits sem efeito colateral modelado sobrevivem à leitura (mesma lição da
    /// F3/`Cp15VmsaCoprocessor`), sem interferir em `M`/`BR`/`V`.
    @Test
    void sctlrUnmodeledBitsRoundTripOnRead() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        int unmodeledBit = 1 << 20;
        int mBit = 1;
        cp15.write(15, 0, 1, 0, 0, unmodeledBit | mBit);

        assertEquals(unmodeledBit | mBit, cp15.read(15, 0, 1, 0, 0));
        assertTrue(mpu.mpuEnabled());
    }

    /// Aceite explícito da B20.2: a sequência canônica de configuração de MPU (ler `MPUIR`, laço
    /// `RGNR`/`DRBAR`/`DRACR`/`DRSR` para N regiões, ligar `SCTLR.M`) não bate em `UNDEFINED` —
    /// aqui provado por `handles()` em cada passo, já que este teste é de unidade direta.
    @Test
    void canonicalMpuConfigurationSequenceIsFullyHandled() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(4);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertTrue(cp15.handles(15, 0, 0, 0, 4), "MRC MPUIR");
        int regionCount = cp15.read(15, 0, 0, 0, 4) >>> 8;

        for (int region = 0; region < regionCount; region++) {
            assertTrue(cp15.handles(15, 0, 6, 2, 0), "MCR RGNR");
            cp15.write(15, 0, 6, 2, 0, region);
            assertTrue(cp15.handles(15, 0, 6, 1, 0), "MCR DRBAR");
            cp15.write(15, 0, 6, 1, 0, 0x1000_0000 + region * 0x1000);
            assertTrue(cp15.handles(15, 0, 6, 1, 4), "MCR DRACR");
            cp15.write(15, 0, 6, 1, 4, 0x0000_0300);
            assertTrue(cp15.handles(15, 0, 6, 1, 2), "MCR DRSR");
            cp15.write(15, 0, 6, 1, 2, 0x0000_0009); // EN=1, RSIZE=4
        }

        assertTrue(cp15.handles(15, 0, 1, 0, 0), "MCR SCTLR");
        cp15.write(15, 0, 1, 0, 0, 1); // SCTLR.M=1
        assertTrue(mpu.mpuEnabled());
    }

    @Test
    void constructorRejectsArchitectureWithoutPmsaFeature() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        assertThrows(IllegalArgumentException.class,
                () -> new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7A),
                "ARMV7A não declara ArmFeature.PMSA");
    }

    @Test
    void unhandledEncodingIsRejectedByExecutorBeforeReachingCoprocessor() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(8);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertFalse(cp15.handles(15, 0, 2, 0, 0), "c2 (TTBR do VMSA) não existe em PMSA");
        assertThrows(IllegalStateException.class, () -> cp15.read(15, 0, 2, 0, 0),
                "bug do executor: read chamado sem consultar handles fino primeiro");
    }

    private static ArmCore coreWithoutCode() {
        ArmCore core = new ArmCore(new TestAddressSpace(0x100), SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);
        return core;
    }
}
