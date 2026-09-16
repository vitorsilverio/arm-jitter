package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B16.1 — round-trip dos 3 campos do `VPR` (`P0`/`MASK01`/`MASK23`), sem nenhuma lógica de
/// predicação (isso é a B16.2). Nenhuma célula de decode é tocada aqui.
class VprRegisterTest {

    @Test
    void p0RoundTripsWithoutTouchingOtherFields() {
        VprRegister vpr = new VprRegister();
        vpr.setMask01(0xF);
        vpr.setMask23(0xF);

        vpr.setP0(0xABCD);

        assertEquals(0xABCD, vpr.p0());
        assertEquals(0xF, vpr.mask01(), "MASK01 não deve ser afetado por setP0");
        assertEquals(0xF, vpr.mask23(), "MASK23 não deve ser afetado por setP0");
    }

    @Test
    void mask01RoundTripsWithoutTouchingOtherFields() {
        VprRegister vpr = new VprRegister();
        vpr.setP0(0xFFFF);
        vpr.setMask23(0xF);

        vpr.setMask01(0b1010);

        assertEquals(0b1010, vpr.mask01());
        assertEquals(0xFFFF, vpr.p0(), "P0 não deve ser afetado por setMask01");
        assertEquals(0xF, vpr.mask23(), "MASK23 não deve ser afetado por setMask01");
    }

    @Test
    void mask23RoundTripsWithoutTouchingOtherFields() {
        VprRegister vpr = new VprRegister();
        vpr.setP0(0xFFFF);
        vpr.setMask01(0xF);

        vpr.setMask23(0b0101);

        assertEquals(0b0101, vpr.mask23());
        assertEquals(0xFFFF, vpr.p0(), "P0 não deve ser afetado por setMask23");
        assertEquals(0xF, vpr.mask01(), "MASK01 não deve ser afetado por setMask23");
    }

    @Test
    void overflowingValuesAreTruncatedNeverBleedingIntoNeighborFields() {
        VprRegister vpr = new VprRegister();

        // P0 tem 16 bits: gravar um valor de 17 bits não pode vazar para MASK01.
        vpr.setP0(0x1FFFF);
        assertEquals(0xFFFF, vpr.p0());
        assertEquals(0, vpr.mask01());

        // MASK01/MASK23 têm 4 bits: gravar um valor de 5 bits não pode vazar para o vizinho.
        vpr.setMask01(0x1F);
        assertEquals(0xF, vpr.mask01());
        assertEquals(0xFFFF, vpr.p0(), "overflow de MASK01 não pode voltar a afetar P0");
        assertEquals(0, vpr.mask23(), "overflow de MASK01 não pode vazar para MASK23");

        vpr.setMask23(0x1F);
        assertEquals(0xF, vpr.mask23());
        assertEquals(0xF, vpr.mask01(), "overflow de MASK23 não pode afetar MASK01");
    }

    @Test
    void valueRoundTripsRawBits() {
        VprRegister vpr = new VprRegister();
        vpr.setValue(0xF0F0_1234);

        assertEquals(0xF0F0_1234, vpr.value());
        assertEquals(0x1234, vpr.p0());
        assertEquals(0x0, vpr.mask01());
        assertEquals(0xF, vpr.mask23());
    }

    @Test
    void saveStateAndLoadStateRoundTrip() throws IOException {
        VprRegister vpr = new VprRegister();
        vpr.setP0(0xBEEF);
        vpr.setMask01(0b0110);
        vpr.setMask23(0b1001);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        vpr.saveState(new DataOutputStream(buffer));

        VprRegister restored = new VprRegister();
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(vpr.value(), restored.value());
        assertEquals(0xBEEF, restored.p0());
        assertEquals(0b0110, restored.mask01());
        assertEquals(0b1001, restored.mask23());
    }

    @Test
    void resetZeroesTheWholeRegister() {
        VprRegister vpr = new VprRegister();
        vpr.setValue(-1);

        vpr.reset();

        assertEquals(0, vpr.value());
        assertEquals(0, vpr.p0());
        assertEquals(0, vpr.mask01());
        assertEquals(0, vpr.mask23());
    }
}
