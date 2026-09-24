package dev.vitorsilverio.armjitter.memory.mpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// B20.7: unidade direta do banco de estado PMSAv8-32, sem passar pelo `CoprocessorBus` — mesmo
/// papel de `Pmsav7MpuRegistersTest` para o épico anterior.
class Pmsav8MpuRegistersTest {

    @Test
    void constructorRejectsNonPositiveRegionCounts() {
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8MpuRegisters(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8MpuRegisters(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8MpuRegisters(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav8MpuRegisters(4, -1));
    }

    @Test
    void baseAndLimitReadRawValuesByIndexIndependentlyOfPrselr() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(3, 4);

        mpu.setPrselr(0);
        mpu.setPrbar(0x0000_1000);
        mpu.setPrlar(0x0000_10C1);

        mpu.setPrselr(1);
        mpu.setPrbar(0x0000_2000);
        mpu.setPrlar(0x0000_20C0);

        mpu.setPrselr(2);
        mpu.setPrbar(0x0000_3000);
        mpu.setPrlar(0x0000_30C0);

        // Leitura crua por índice não depende do PRSELR corrente (que ficou em 2).
        assertEquals(0x0000_1000, mpu.base(0));
        assertEquals(0x0000_2000, mpu.base(1));
        assertEquals(0x0000_3000, mpu.base(2));
        assertEquals(0x0000_10C1, mpu.limit(0));
        assertEquals(0x0000_20C0, mpu.limit(1));
        assertEquals(0x0000_30C0, mpu.limit(2));
    }

    @Test
    void enabledReflectsPrlarBit0() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);

        mpu.setPrselr(0);
        mpu.setPrlar(0x0000_0000);
        assertFalse(mpu.enabled(0));

        mpu.setPrlar(0x0000_0001);
        assertTrue(mpu.enabled(0));
    }

    @Test
    void setPrselrOutOfRangeIsIgnoredSilently() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 2);
        mpu.setPrselr(1);

        mpu.setPrselr(2); // fora de faixa (regionCount=2, índices válidos 0-1)
        assertEquals(1, mpu.prselr(), "PRSELR mantém o valor anterior, não corrompe");

        mpu.setPrselr(-1);
        assertEquals(1, mpu.prselr());
    }

    @Test
    void setHprselrOutOfRangeIsIgnoredSilently() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 2);
        mpu.setHprselr(1);

        mpu.setHprselr(2); // fora de faixa (hRegionCount=2, índices válidos 0-1)
        assertEquals(1, mpu.hprselr(), "HPRSELR mantém o valor anterior, não corrompe");

        mpu.setHprselr(-1);
        assertEquals(1, mpu.hprselr());
    }

    @Test
    void hprbarHprlarHprselrAreIndependentFromEl1Bank() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 2);

        mpu.setPrselr(0);
        mpu.setPrbar(0x1111_1100);
        mpu.setHprselr(0);
        mpu.setHprbar(0x2222_2200);

        assertEquals(0x1111_1100, mpu.prbar(), "banco EL1 intocado pela escrita EL2");
        assertEquals(0x2222_2200, mpu.hprbar());

        mpu.setHprselr(1);
        mpu.setHprlar(0x3333_3301);
        assertEquals(0x3333_3301, mpu.hprlar());
        assertEquals(0, mpu.hprbar(), "região 1 do banco EL2 continua zerada");
    }

    @Test
    void prenrAndHprenrAreStoredButIndependent() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setPrenr(0xABCD);
        mpu.setHprenr(0x1234);

        assertEquals(0xABCD, mpu.prenr());
        assertEquals(0x1234, mpu.hprenr());
    }

    @Test
    void mpuEnabledAndBackgroundRegionEnabledDefaultToFalse() {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        assertFalse(mpu.mpuEnabled());
        assertFalse(mpu.backgroundRegionEnabled());

        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        assertTrue(mpu.mpuEnabled());
        assertTrue(mpu.backgroundRegionEnabled());
    }
}
