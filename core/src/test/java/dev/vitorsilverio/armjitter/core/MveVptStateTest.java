package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MveVptStateTest {

    // ── eciMask ────────────────────────────────────────────────────────────────────────────────

    @Test
    void eciMaskIsUnmaskedOutsideAnyEciWindow() {
        assertEquals(0xFFFF, MveVptState.eciMask(MveVptState.ECI_NONE << 4));
    }

    @Test
    void eciMaskAfterA0MasksTheLowestLane() {
        assertEquals(0xFFF0, MveVptState.eciMask(MveVptState.ECI_A0 << 4));
    }

    @Test
    void eciMaskAfterA0A1MasksTheTwoLowestLanes() {
        assertEquals(0xFF00, MveVptState.eciMask(MveVptState.ECI_A0A1 << 4));
    }

    @Test
    void eciMaskAfterA0A1A2AndA0A1A2B0MaskTheThreeLowestLanes() {
        assertEquals(0xF000, MveVptState.eciMask(MveVptState.ECI_A0A1A2 << 4));
        assertEquals(0xF000, MveVptState.eciMask(MveVptState.ECI_A0A1A2B0 << 4));
    }

    @Test
    void eciMaskIgnoresEciWhenAnItBlockIsActive() {
        // Nibble baixo != 0 (resto de ITSTATE) faz o ECI (nibble alto) não valer nada.
        int itStateWithActiveItAndStaleEci = (MveVptState.ECI_A0A1A2 << 4) | 0x3;

        assertEquals(0xFFFF, MveVptState.eciMask(itStateWithActiveItAndStaleEci));
    }

    @Test
    void eciMaskThrowsOnReservedEci() {
        assertThrows(IllegalStateException.class, () -> MveVptState.eciMask(3 << 4));
        assertThrows(IllegalStateException.class, () -> MveVptState.eciMask(6 << 4));
        assertThrows(IllegalStateException.class, () -> MveVptState.eciMask(7 << 4));
    }

    // ── isReservedEci ──────────────────────────────────────────────────────────────────────────

    @Test
    void validEciValuesAreNeverReserved() {
        assertFalse(MveVptState.isReservedEci(MveVptState.ECI_NONE));
        assertFalse(MveVptState.isReservedEci(MveVptState.ECI_A0));
        assertFalse(MveVptState.isReservedEci(MveVptState.ECI_A0A1));
        assertFalse(MveVptState.isReservedEci(MveVptState.ECI_A0A1A2));
        assertFalse(MveVptState.isReservedEci(MveVptState.ECI_A0A1A2B0));
    }

    @Test
    void gapAndOutOfRangeValuesAreReserved() {
        assertTrue(MveVptState.isReservedEci(3));
        assertTrue(MveVptState.isReservedEci(6));
        assertTrue(MveVptState.isReservedEci(7));
        assertTrue(MveVptState.isReservedEci(15));
    }

    // ── elementMask ────────────────────────────────────────────────────────────────────────────

    private static final int NO_TAIL_PREDICATION = 4;

    @Test
    void elementMaskIsFullyOpenWhenVptIsNotActive() {
        int vprWithoutMasks = 0; // MASK01=0, MASK23=0 -> VPT inativo -> P0 ignorado.
        int mask = MveVptState.elementMask(vprWithoutMasks, MveVptState.ECI_NONE << 4, NO_TAIL_PREDICATION, 0);

        assertEquals(0xFFFF, mask);
    }

    @Test
    void elementMaskHonoursP0WhenVptIsActive() {
        int p0 = 0x00F0; // só as lanes de byte 4-7 ativas.
        int vpr = (p0 << VprRegister.P0_SHIFT)
                | (1 << VprRegister.MASK01_SHIFT) // MASK01 != 0 -> VPT ativo na metade baixa
                | (1 << VprRegister.MASK23_SHIFT); // MASK23 != 0 -> VPT ativo na metade alta

        int mask = MveVptState.elementMask(vpr, MveVptState.ECI_NONE << 4, NO_TAIL_PREDICATION, 0);

        assertEquals(p0, mask);
    }

    @Test
    void elementMaskCombinesEciMaskingWithP0() {
        int p0 = 0xFFFF;
        int vpr = (p0 << VprRegister.P0_SHIFT)
                | (1 << VprRegister.MASK01_SHIFT)
                | (1 << VprRegister.MASK23_SHIFT);

        int mask = MveVptState.elementMask(vpr, MveVptState.ECI_A0 << 4, NO_TAIL_PREDICATION, 0);

        assertEquals(0xFFF0, mask);
    }

    @Test
    void elementMaskAppliesTailPredicationWhenLtpsizeIsActive() {
        int vprWithoutMasks = 0;
        // ltpsize=0 (elemento de 1 byte), lr=3 elementos restantes -> 3 lanes baixas ativas.
        int mask = MveVptState.elementMask(vprWithoutMasks, MveVptState.ECI_NONE << 4, 0, 3);

        assertEquals(0x0007, mask);
    }

    @Test
    void elementMaskIgnoresTailPredicationWhenLrExceedsRemainingIterations() {
        int vprWithoutMasks = 0;
        // ltpsize=0, lr=17 > 1<<(4-0)=16 -> a checagem de tail predication não se aplica.
        int mask = MveVptState.elementMask(vprWithoutMasks, MveVptState.ECI_NONE << 4, 0, 17);

        assertEquals(0xFFFF, mask);
    }

    // ── advance ────────────────────────────────────────────────────────────────────────────────

    @Test
    void advanceWrapsEciFromA0A1A2B0ToA0() {
        MveVptState.MveVptAdvance result = MveVptState.advance(0, MveVptState.ECI_A0A1A2B0 << 4);

        assertEquals(MveVptState.ECI_A0 << 4, result.itState());
    }

    @Test
    void advanceResetsAnyOtherEciToNone() {
        MveVptState.MveVptAdvance result = MveVptState.advance(0, MveVptState.ECI_A0A1 << 4);

        assertEquals(MveVptState.ECI_NONE << 4, result.itState());
    }

    @Test
    void advanceLeavesItStateUntouchedWhenAnItBlockIsActive() {
        int itStateWithActiveIt = (MveVptState.ECI_A0A1 << 4) | 0x5;

        MveVptState.MveVptAdvance result = MveVptState.advance(0, itStateWithActiveIt);

        assertEquals(itStateWithActiveIt, result.itState());
    }

    @Test
    void advanceLeavesVprUntouchedWhenVptIsNotActive() {
        int vpr = 0x1234; // MASK01/MASK23 zerados -> VPT inativo.
        MveVptState.MveVptAdvance result = MveVptState.advance(vpr, MveVptState.ECI_NONE << 4);

        assertEquals(vpr, result.vpr());
    }

    @Test
    void advanceSuppressesP0InvertOnBothHalvesWhenMasksAreAtOrBelowThreshold() {
        // mask01<=8 e mask23<=8 -> "não inverter" as duas metades (QEMU real: `if (mask01 <= 8)
        // inv_mask &= ~0xff;` — a checagem SUPRIME o invert, não o contrário).
        int p0 = 0x0000;
        int mask01 = 0x1;
        int mask23 = 0x1;
        int vpr = (p0 << VprRegister.P0_SHIFT)
                | (mask01 << VprRegister.MASK01_SHIFT)
                | (mask23 << VprRegister.MASK23_SHIFT);

        MveVptState.MveVptAdvance result = MveVptState.advance(vpr, MveVptState.ECI_NONE << 4);

        assertEquals(0, (result.vpr() & VprRegister.P0_MASK) >>> VprRegister.P0_SHIFT,
                "as duas metades de P0 suprimidas -> invMask inteiro zerado -> P0 intocado");
        assertEquals(mask01 << 1, (result.vpr() & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(mask23 << 1, (result.vpr() & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }

    @Test
    void advanceInvertsBothP0HalvesWhenMasksAreAboveThreshold() {
        // mask01>8 e mask23>8 -> a supressão NÃO se aplica -> invMask = eciMask pleno (ECI_NONE
        // = 0xFFFF) -> P0 (0 antes) vira 0xFFFF.
        int mask01 = 9;
        int mask23 = 9;
        int vpr = (mask01 << VprRegister.MASK01_SHIFT) | (mask23 << VprRegister.MASK23_SHIFT);

        MveVptState.MveVptAdvance result = MveVptState.advance(vpr, MveVptState.ECI_NONE << 4);

        assertEquals(0xFFFF, (result.vpr() & VprRegister.P0_MASK) >>> VprRegister.P0_SHIFT);
    }

    @Test
    void advanceUpdatesMask01WhenBeatOneIsAmongTheExecutingBeats() {
        // ECI_A0 (beat A0 já feito ANTES desta chamada): eciMask=0xFFF0 -> bits[7:4]=0xF0
        // setados (beat 1 está entre os beats que executam AGORA) -> MASK01 atualiza.
        int mask01 = 2;
        int mask23 = 2;
        int vpr = (mask01 << VprRegister.MASK01_SHIFT) | (mask23 << VprRegister.MASK23_SHIFT);

        MveVptState.MveVptAdvance result = MveVptState.advance(vpr, MveVptState.ECI_A0 << 4);

        assertEquals(mask01 << 1, (result.vpr() & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(mask23 << 1, (result.vpr() & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }

    @Test
    void advanceDoesNotUpdateMask01WhenBeatOneHasNotExecutedYet() {
        // ECI_A0A1A2: eciMask=0xF000 -> bits[7:4]=0 -> "beat 1 ainda não rodou" -> MASK01 congelado.
        int mask01 = 2;
        int mask23 = 2;
        int vpr = (mask01 << VprRegister.MASK01_SHIFT) | (mask23 << VprRegister.MASK23_SHIFT);

        MveVptState.MveVptAdvance result = MveVptState.advance(vpr, MveVptState.ECI_A0A1A2 << 4);

        assertEquals(mask01, (result.vpr() & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(mask23 << 1, (result.vpr() & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }

    // ── vpstMask ───────────────────────────────────────────────────────────────────────────────

    @Test
    void vpstMaskUpdatesBothFieldsWhenEciIsNoneOrA0() {
        int result = MveVptState.vpstMask(0, MveVptState.ECI_NONE, 0b1010);

        assertEquals(0b1010, (result & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(0b1010, (result & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);

        int resultA0 = MveVptState.vpstMask(0, MveVptState.ECI_A0, 0b0101);
        assertEquals(0b0101, (resultA0 & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(0b0101, (resultA0 & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }

    @Test
    void vpstMaskUpdatesOnlyMask23WhenResumingMidVpt() {
        int existingMask01 = 0b1100;
        int vpr = existingMask01 << VprRegister.MASK01_SHIFT;

        int result = MveVptState.vpstMask(vpr, MveVptState.ECI_A0A1, 0b0011);

        assertEquals(existingMask01, (result & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(0b0011, (result & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
    }
}
