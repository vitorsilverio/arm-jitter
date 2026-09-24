package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmsaAccessExceptionTest {
    @Test
    void exposesAddressAccessTypeAndFaultStatusPassedToConstructor() {
        PmsaAccessException exception =
                new PmsaAccessException(0x1000, MemoryAccessType.DATA_WRITE, PmsaFaultStatus.PERMISSION);

        assertEquals(0x1000, exception.virtualAddress());
        assertEquals(MemoryAccessType.DATA_WRITE, exception.accessType());
        assertEquals(PmsaFaultStatus.PERMISSION, exception.faultStatus());
        assertTrue(exception.getMessage().contains("1000"));
        assertEquals(0, exception.getStackTrace().length, "sem stack trace (falta PMSA é esperada/frequente)");
    }
}
