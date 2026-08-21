package dev.vitorsilverio.armjitter.core64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B10.1: decodificação do campo `M[3:0]` de `SPSR_ELx` — base de {@code ERET} para os 4 níveis
/// (era hardcoded "sempre volta para EL0" antes desta task).
class Aarch64ExceptionLevelTest {
    @Test
    void spsrModeRoundTripsForAllFourLevels() {
        for (Aarch64ExceptionLevel level : Aarch64ExceptionLevel.values()) {
            long spsrValue = level.spsrMode(); // demais bits (NZCV/I) irrelevantes pra este teste
            assertEquals(level, Aarch64ExceptionLevel.fromSpsrValue(spsrValue));
        }
    }

    @Test
    void fromSpsrValueIgnoresNzcvAndIBits() {
        // N=Z=C=V=1 (bits[31:28]) + I=1 (bit 7) + M=EL2h — só M importa para o nível.
        long spsrValue = (0xFL << 28) | (1 << 7) | Aarch64ExceptionLevel.EL2.spsrMode();
        assertEquals(Aarch64ExceptionLevel.EL2, Aarch64ExceptionLevel.fromSpsrValue(spsrValue));
    }
}
