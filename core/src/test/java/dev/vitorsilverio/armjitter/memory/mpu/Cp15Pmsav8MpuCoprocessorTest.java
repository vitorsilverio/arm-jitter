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

/// B20.7: liga `MCR`/`MRC` ao banco {@link Pmsav8MpuRegisters} — mesmo papel de
/// `Pmsav7MpuCoprocessorTest` para o épico anterior, cobrindo a dimensão nova (`opc1` distinguindo
/// EL1 de EL2/Hyp, registradores no layout base+limite).
class Cp15Pmsav8MpuCoprocessorTest {

    @Test
    void mpuirAndHmpuirReportRegionCountsIndependentlyAndAreReadOnly() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(16, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        assertTrue(cp15.handles(15, 0, 0, 0, 4), "MPUIR (opc1=0, c0,c0,4)");
        assertTrue(cp15.handles(15, 4, 0, 0, 4), "HMPUIR (opc1=4, c0,c0,4)");
        assertEquals(16 << 8, cp15.read(15, 0, 0, 0, 4), "MPUIR.DREGION");
        assertEquals(4 << 8, cp15.read(15, 4, 0, 0, 4), "HMPUIR região EL2, contagem independente");

        cp15.write(15, 0, 0, 0, 4, 0xFFFF_FFFF);
        cp15.write(15, 4, 0, 0, 4, 0xFFFF_FFFF);
        assertEquals(16 << 8, cp15.read(15, 0, 0, 0, 4), "MPUIR não muda por escrita");
        assertEquals(4 << 8, cp15.read(15, 4, 0, 0, 4), "HMPUIR não muda por escrita");
    }

    @Test
    void prselrSelectsIndependentRegionSlotsWithoutAliasingEl1() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(4, 2);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        cp15.write(15, 0, 6, 2, 1, 0); // PRSELR=0
        cp15.write(15, 0, 6, 3, 0, 0x1000_0000); // PRBAR
        cp15.write(15, 0, 6, 3, 1, 0x1000_00C1); // PRLAR (EN=1)

        cp15.write(15, 0, 6, 2, 1, 1); // PRSELR=1
        cp15.write(15, 0, 6, 3, 0, 0x2000_0000);
        cp15.write(15, 0, 6, 3, 1, 0x2000_00C1);

        cp15.write(15, 0, 6, 2, 1, 0); // volta pra 0
        assertEquals(0x1000_0000, cp15.read(15, 0, 6, 3, 0), "PRBAR[0] não deve ter sido pisado");
        assertEquals(0x1000_00C1, cp15.read(15, 0, 6, 3, 1), "PRLAR[0]");

        cp15.write(15, 0, 6, 2, 1, 1);
        assertEquals(0x2000_0000, cp15.read(15, 0, 6, 3, 0), "PRBAR[1]");

        assertEquals(0x1000_0000, mpu.base(0), "leitura crua, API do consumidor Pmsav8AddressSpace");
        assertEquals(0x2000_0000, mpu.base(1));
    }

    @Test
    void hprselrHprbarHprlarUseOpc1FourAndAreIndependentFromEl1() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 2);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        cp15.write(15, 0, 6, 2, 1, 0); // PRSELR (EL1) = 0
        cp15.write(15, 0, 6, 3, 0, 0xAAAA_0000); // PRBAR (EL1)

        cp15.write(15, 4, 6, 2, 1, 0); // HPRSELR (EL2) = 0
        cp15.write(15, 4, 6, 3, 0, 0xBBBB_0000); // HPRBAR (EL2), MESMO crn/crm/opc2, opc1 diferente

        assertEquals(0xAAAA_0000, cp15.read(15, 0, 6, 3, 0), "banco EL1 intocado pela escrita EL2");
        assertEquals(0xBBBB_0000, cp15.read(15, 4, 6, 3, 0));
        assertEquals(0xAAAA_0000, mpu.base(0));
        assertEquals(0xBBBB_0000, mpu.hprbar());
    }

    @Test
    void prenrAndHprenrAreStoredIndependentlyByOpc1() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 2);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        assertTrue(cp15.handles(15, 0, 6, 1, 1), "PRENR (opc1=0, inferido por simetria)");
        assertTrue(cp15.handles(15, 4, 6, 1, 1), "HPRENR (opc1=4, confirmado no QEMU real)");

        cp15.write(15, 0, 6, 1, 1, 0xFF);
        cp15.write(15, 4, 6, 1, 1, 0x0F);
        assertEquals(0xFF, cp15.read(15, 0, 6, 1, 1));
        assertEquals(0x0F, cp15.read(15, 4, 6, 1, 1));
    }

    @Test
    void sctlrMBitTogglesMpuEnabled() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        assertFalse(mpu.mpuEnabled(), "reset real de hardware: MPU começa desligada");
        cp15.write(15, 0, 1, 0, 0, 1); // SCTLR.M=1
        assertTrue(mpu.mpuEnabled());
        cp15.write(15, 0, 1, 0, 0, 0);
        assertFalse(mpu.mpuEnabled());
    }

    @Test
    void sctlrBrBitTogglesBackgroundRegionEnabled() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        cp15.write(15, 0, 1, 0, 0, 1 << 17);
        assertTrue(mpu.backgroundRegionEnabled());
        assertEquals(1 << 17, cp15.read(15, 0, 1, 0, 0));
    }

    @Test
    void sctlrVBitTogglesHighVectorsOnCore() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        ArmCore core = coreWithoutCode();
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, core, ArmArchitecture.ARMV8R_32);

        cp15.write(15, 0, 1, 0, 0, 1 << 13);
        assertTrue(core.highVectors());
    }

    @Test
    void sctlrIsNotHandledUnderOpc1Four() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        assertFalse(cp15.handles(15, 4, 1, 0, 0), "SCTLR só existe sob opc1=0 (EL2 não tem HSCTLR modelado aqui)");
    }

    @Test
    void constructorRejectsArchitectureWithoutPmsaFeature() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        assertThrows(IllegalArgumentException.class,
                () -> new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV7A),
                "ARMV7A não declara ArmFeature.PMSA");
    }

    @Test
    void unhandledEncodingIsRejectedByExecutorBeforeReachingCoprocessor() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        assertFalse(cp15.handles(15, 0, 2, 0, 0), "c2 (TTBR do VMSA) não existe em PMSA");
        assertThrows(IllegalStateException.class, () -> cp15.read(15, 0, 2, 0, 0));
        assertThrows(IllegalStateException.class, () -> cp15.write(15, 0, 2, 0, 0, 0x1234));
    }

    @Test
    void directRegionAliasesAreNotHandled() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(8, 4);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        // PRBAR0 real seria crm=0b1000, fora do CRM_REGION_CONFIG(3) que esta classe atende.
        assertFalse(cp15.handles(15, 0, 6, 0b1000, 0), "alias direto PRBAR0 fora do orçamento da B20.7");
    }

    @Test
    void canonicalMpuConfigurationSequenceIsFullyHandled() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(4, 2);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, coreWithoutCode(), ArmArchitecture.ARMV8R_32);

        int regionCount = cp15.read(15, 0, 0, 0, 4) >>> 8;
        for (int region = 0; region < regionCount; region++) {
            assertTrue(cp15.handles(15, 0, 6, 2, 1), "MCR PRSELR");
            cp15.write(15, 0, 6, 2, 1, region);
            assertTrue(cp15.handles(15, 0, 6, 3, 0), "MCR PRBAR");
            cp15.write(15, 0, 6, 3, 0, 0x1000_0000 + region * 0x1_0000);
            assertTrue(cp15.handles(15, 0, 6, 3, 1), "MCR PRLAR");
            cp15.write(15, 0, 6, 3, 1, 0x0000_00C1); // EN=1
        }

        assertTrue(cp15.handles(15, 0, 1, 0, 0), "MCR SCTLR");
        cp15.write(15, 0, 1, 0, 0, 1);
        assertTrue(mpu.mpuEnabled());
    }

    private static ArmCore coreWithoutCode() {
        ArmCore core = new ArmCore(new TestAddressSpace(0x100), SwiDispatcher.empty(), ArmArchitecture.ARMV8R_32);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);
        return core;
    }
}
