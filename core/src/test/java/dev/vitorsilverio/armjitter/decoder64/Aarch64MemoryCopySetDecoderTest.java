package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.16 — `SETP`/`SETM`/`SETE` + `CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE` (`FEAT_MOPS`).
/// Vetores golden conferidos com `aarch64-linux-gnu-as`/`objdump` (WSL Ubuntu, `-march=armv8.8-a`,
/// `setp/setm/sete [x0]!, x1!, x2` e `cpyfp/cpyfm/cpyfe/cpyp/cpym/cpye [x0]!, [x1]!, x2!`).
class Aarch64MemoryCopySetDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder MOPS_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_8_A);

    private static final int SETP = 0x19C20420;
    private static final int SETM = 0x19C24420;
    private static final int SETE = 0x19C28420;
    private static final int CPYFP = 0x19010440;
    private static final int CPYFM = 0x19410440;
    private static final int CPYFE = 0x19810440;
    private static final int CPYP = 0x1D010440;
    private static final int CPYM = 0x1D410440;
    private static final int CPYE = 0x1D810440;

    // ── golden: `setgp/setgm/setge [x0]!, x1!, x2` (`.arch_extension memtag`) — B19.14, fora do ──
    // ── escopo desta task; regressão de que a B19.16 não passa a misdecodificar/aceitar estas. ────
    private static final int SETGP = 0x1DC20420;
    private static final int SETGM = 0x1DC24420;
    private static final int SETGE = 0x1DC28420;

    // ── golden: `ldapur x0,[x1,#8]` / `ldapurb w0,[x1,#8]` / `stlur x0,[x1,#8]` (`FEAT_LRCPC2`, ────
    // ── `-march=armv8.4-a`) — regressão do achado real desta task: mesmo prefixo `011001` de ───────
    // ── bits[29:24] que `SETP`/`CPYFx` (bit26=0), só distinguível por bits[11:10] ("00" aqui, ──────
    // ── "01" em MOPS). B19.19, ainda não implementada — tem que continuar `unsupported`.
    private static final int LDAPUR_X = 0xD9408020;
    private static final int LDAPURB_W = 0x19408020;
    private static final int STLUR_X = 0xD9008020;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByMemoryCopySetFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SETP));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CPYFP));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CPYP));
    }

    @Test
    void decodesSetPhasesWithCorrectRegisters() {
        Ir64Op.MemorySet p = (Ir64Op.MemorySet) decode(MOPS_DECODER, SETP);
        assertEquals(Ir64Op.Ir64MopsPhase.PROLOGUE, p.phase());
        assertEquals(0, p.rd());
        assertEquals(1, p.rn());
        assertEquals(2, p.rs());

        Ir64Op.MemorySet m = (Ir64Op.MemorySet) decode(MOPS_DECODER, SETM);
        assertEquals(Ir64Op.Ir64MopsPhase.MAIN, m.phase());

        Ir64Op.MemorySet e = (Ir64Op.MemorySet) decode(MOPS_DECODER, SETE);
        assertEquals(Ir64Op.Ir64MopsPhase.EPILOGUE, e.phase());
    }

    @Test
    void decodesForwardOnlyCopyPhasesWithCorrectRegisters() {
        Ir64Op.MemoryCopy p = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYFP);
        assertEquals(Ir64Op.Ir64MopsPhase.PROLOGUE, p.phase());
        assertTrue(p.forwardOnly());
        assertEquals(0, p.rd());
        assertEquals(1, p.rs());
        assertEquals(2, p.rn());

        Ir64Op.MemoryCopy m = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYFM);
        assertEquals(Ir64Op.Ir64MopsPhase.MAIN, m.phase());
        assertTrue(m.forwardOnly());

        Ir64Op.MemoryCopy e = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYFE);
        assertEquals(Ir64Op.Ir64MopsPhase.EPILOGUE, e.phase());
        assertTrue(e.forwardOnly());
    }

    @Test
    void decodesGenericDirectionCopyPhasesWithCorrectRegisters() {
        Ir64Op.MemoryCopy p = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYP);
        assertEquals(Ir64Op.Ir64MopsPhase.PROLOGUE, p.phase());
        assertFalse(p.forwardOnly());
        assertEquals(0, p.rd());
        assertEquals(1, p.rs());
        assertEquals(2, p.rn());

        Ir64Op.MemoryCopy m = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYM);
        assertEquals(Ir64Op.Ir64MopsPhase.MAIN, m.phase());
        assertFalse(m.forwardOnly());

        Ir64Op.MemoryCopy e = (Ir64Op.MemoryCopy) decode(MOPS_DECODER, CPYE);
        assertEquals(Ir64Op.Ir64MopsPhase.EPILOGUE, e.phase());
        assertFalse(e.forwardOnly());
    }

    @Test
    void setgFamilyWithTagStaysUnsupportedEvenUnderMopsFeature() {
        // B19.14 (variantes com tag) não é implementada por esta task — bit26=1 na família SET*
        // (mesmo bit que discrimina CPYF/CPY) tem que continuar recusando, não decodificar como
        // SETP/SETM/SETE por engano nem cair de volta no misdecode antigo (FpLoadLiteral64).
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, SETGP));
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, SETGM));
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, SETGE));
    }

    @Test
    void ldaprImmediateFamilyStaysUnsupportedDespiteSharedPrefixWithMops() {
        // LDAPUR/LDAPURB/STLUR (FEAT_LRCPC2, B19.19) compartilham o prefixo de 6 bits `011001`
        // (bits[29:24]) com SETP/CPYFx (bit26=0) e só divergem em bits[11:10] — sem checar esse
        // campo, LDAPURB (opc=01) misdecodificaria como CPYFM (achado real desta task).
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, LDAPUR_X));
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, LDAPURB_W));
        assertThrows(UnsupportedOperationException.class, () -> decode(MOPS_DECODER, STLUR_X));
    }
}
