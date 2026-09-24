package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.memory.mpu.PmsaAccessException;
import dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace;
import dev.vitorsilverio.armjitter.memory.mpu.Pmsav7MpuRegisters;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B20.4, "Aceite": interação MPU×TCM travada por teste, conforme a decisão da Armadilha 2 — ver
/// Javadoc de {@link TcmAddressSpace}. Fonte: ARM DDI 0460D (Cortex-R5 TRM) §4.3.21 e §8.4.1 — "the
/// MPU determines the access permissions for all accesses to memory, including the TCMs".
/// Composição: `PmsaAddressSpace` por FORA, `TcmAddressSpace` por DENTRO.
class PmsaTcmInteractionTest {
    private static final int SIXTY_FOUR_KB = 64 * 1024;
    private static final int DRACR_FULL_ACCESS = 0b011 << 8; // AP=3: RWX plena, priv. e usuário
    private static final int DRACR_NO_ACCESS = 0;

    private static int drsrEnabled(int rawRsize) {
        return 0b1 | (rawRsize << 1);
    }

    private static TcmAddressSpace newTcmSpaceWithAtcmEnabledAt(int base) {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0x1234_5678);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(base, true);
        return tcm;
    }

    @Test
    void mpuEnabledWithoutRegionCoveringTcmDeniesAccessEvenThoughTcmIsProgrammed() {
        TcmAddressSpace tcm = newTcmSpaceWithAtcmEnabledAt(0);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        // Nenhuma região MPU programada cobrindo a faixa da TCM — TRM real: "you must ensure that
        // the memory regions in the MPU are programmed to cover the complete TCM address space".
        PmsaAddressSpace space = new PmsaAddressSpace(tcm, mpu);

        assertThrows(PmsaAccessException.class, () -> space.read32(0),
                "MPU decide permissão para TODO endereço, TCM incluída — sem região cobrindo, nega");
    }

    @Test
    void mpuRegionCoveringTcmGrantsAccessAndReadsTcmContentNotPhysicalBus() {
        TcmAddressSpace tcm = newTcmSpaceWithAtcmEnabledAt(0);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(drsrEnabled(11)); // 4KiB, cobre a base da TCM
        mpu.setDracr(DRACR_FULL_ACCESS);
        PmsaAddressSpace space = new PmsaAddressSpace(tcm, mpu);

        assertEquals(0x1234_5678, space.read32(0), "permissão da MPU concedida, dado real vem da TCM");
    }

    @Test
    void mpuRegionCoveringTcmWithNoAccessDeniesEvenThoughTcmIsEnabled() {
        TcmAddressSpace tcm = newTcmSpaceWithAtcmEnabledAt(0);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(drsrEnabled(11));
        mpu.setDracr(DRACR_NO_ACCESS);
        PmsaAddressSpace space = new PmsaAddressSpace(tcm, mpu);

        assertThrows(PmsaAccessException.class, () -> space.read32(0),
                "MPU nega mesmo a TCM estando corretamente programada — a MPU decide permissão, não a TCM");
    }
}
