package dev.vitorsilverio.armjitter.memory.mpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/// B20.2: unidade direta do banco de estado, sem passar pelo `CoprocessorBus`. Cobre o que
/// `Pmsav7MpuCoprocessorTest` não exercita: validação do construtor e a leitura crua por índice
/// (`sizeRegister`/`accessControl`) que a B20.3 vai consumir — achado de cobertura JaCoCo (o
/// `Pmsav7MpuCoprocessorTest` só tinha exercitado `base(int)`, deixando as duas irmãs sem teste).
class Pmsav7MpuRegistersTest {

    @Test
    void constructorRejectsNonPositiveRegionCount() {
        assertThrows(IllegalArgumentException.class, () -> new Pmsav7MpuRegisters(0));
        assertThrows(IllegalArgumentException.class, () -> new Pmsav7MpuRegisters(-1));
    }

    @Test
    void sizeRegisterAndAccessControlReadRawValuesByIndexIndependentlyOfRgnr() {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(3);

        mpu.setRgnr(0);
        mpu.setDrsr(0x0000_0009); // EN=1, RSIZE=4
        mpu.setDracr(0x0000_0300);

        mpu.setRgnr(1);
        mpu.setDrsr(0x0000_000B);
        mpu.setDracr(0x0000_1100);

        mpu.setRgnr(2);
        mpu.setDrsr(0x0000_000D);
        mpu.setDracr(0x0000_0000);

        // Leitura crua por índice não depende do RGNR corrente (que ficou em 2).
        assertEquals(0x0000_0009, mpu.sizeRegister(0));
        assertEquals(0x0000_000B, mpu.sizeRegister(1));
        assertEquals(0x0000_000D, mpu.sizeRegister(2));
        assertEquals(0x0000_0300, mpu.accessControl(0));
        assertEquals(0x0000_1100, mpu.accessControl(1));
        assertEquals(0x0000_0000, mpu.accessControl(2));
    }
}
