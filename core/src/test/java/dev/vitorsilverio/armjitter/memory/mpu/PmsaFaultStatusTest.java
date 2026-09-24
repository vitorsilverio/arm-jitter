package dev.vitorsilverio.armjitter.memory.mpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B20.3: valores de `FS` confirmados na tabela "PMSAv7" da SEGGER (`kb.segger.com/Cortex-A/R_Fault`).
class PmsaFaultStatusTest {
    @Test
    void codeMatchesConfirmedPmsav7FaultStatusTable() {
        assertEquals(0b00000, PmsaFaultStatus.BACKGROUND.code());
        assertEquals(0b00001, PmsaFaultStatus.ALIGNMENT.code());
        assertEquals(0b01101, PmsaFaultStatus.PERMISSION.code());
    }
}
