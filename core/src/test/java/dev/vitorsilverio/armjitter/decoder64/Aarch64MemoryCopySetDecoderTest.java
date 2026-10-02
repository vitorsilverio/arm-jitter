package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
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

    // ── golden: `setgp/setgm/setge [x0]!, x1!, x2` (`.arch_extension memtag`) — B19.14 IMPLEMENTA
    // ── estas 3 (ver Aarch64MemoryTagDecoderTest para a cobertura completa); aqui só a regressão
    // ── de que continuam SEPARADAS de SETP/SETM/SETE (nunca confundidas) e gateadas por
    // ── FEAT_MTE2 além de FEAT_MOPS.
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
        MemoryOp64.MemorySet p = (MemoryOp64.MemorySet) decode(MOPS_DECODER, SETP);
        assertEquals(MemoryOp64.Ir64MopsPhase.PROLOGUE, p.phase());
        assertEquals(0, p.rd());
        assertEquals(1, p.rn());
        assertEquals(2, p.rs());

        MemoryOp64.MemorySet m = (MemoryOp64.MemorySet) decode(MOPS_DECODER, SETM);
        assertEquals(MemoryOp64.Ir64MopsPhase.MAIN, m.phase());

        MemoryOp64.MemorySet e = (MemoryOp64.MemorySet) decode(MOPS_DECODER, SETE);
        assertEquals(MemoryOp64.Ir64MopsPhase.EPILOGUE, e.phase());
    }

    @Test
    void decodesForwardOnlyCopyPhasesWithCorrectRegisters() {
        MemoryOp64.MemoryCopy p = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYFP);
        assertEquals(MemoryOp64.Ir64MopsPhase.PROLOGUE, p.phase());
        assertTrue(p.forwardOnly());
        assertEquals(0, p.rd());
        assertEquals(1, p.rs());
        assertEquals(2, p.rn());

        MemoryOp64.MemoryCopy m = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYFM);
        assertEquals(MemoryOp64.Ir64MopsPhase.MAIN, m.phase());
        assertTrue(m.forwardOnly());

        MemoryOp64.MemoryCopy e = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYFE);
        assertEquals(MemoryOp64.Ir64MopsPhase.EPILOGUE, e.phase());
        assertTrue(e.forwardOnly());
    }

    @Test
    void decodesGenericDirectionCopyPhasesWithCorrectRegisters() {
        MemoryOp64.MemoryCopy p = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYP);
        assertEquals(MemoryOp64.Ir64MopsPhase.PROLOGUE, p.phase());
        assertFalse(p.forwardOnly());
        assertEquals(0, p.rd());
        assertEquals(1, p.rs());
        assertEquals(2, p.rn());

        MemoryOp64.MemoryCopy m = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYM);
        assertEquals(MemoryOp64.Ir64MopsPhase.MAIN, m.phase());
        assertFalse(m.forwardOnly());

        MemoryOp64.MemoryCopy e = (MemoryOp64.MemoryCopy) decode(MOPS_DECODER, CPYE);
        assertEquals(MemoryOp64.Ir64MopsPhase.EPILOGUE, e.phase());
        assertFalse(e.forwardOnly());
    }

    @Test
    void setgFamilyStaysUnsupportedWithoutFeatures() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SETGP));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SETGM));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SETGE));
    }

    @Test
    void setgFamilyDecodesSeparatelyFromSet() {
        // MOPS_DECODER (ARMV8_8_A) já herda FEAT_MTE2 (ARMv8.5-A é ancestral na cadeia de presets
        // deste projeto) — SETGP/SETGM/SETGE (B19.14) decodificam de verdade, sempre SEPARADAS de
        // SETP/SETM/SETE (bit26 as discrimina), nunca confundidas nem caindo no misdecode antigo
        // (FpOp64.LoadLiteral64) que a B19.16 já corrigiu para a família sem tag.
        MemoryOp64.MemorySetTagged p = (MemoryOp64.MemorySetTagged) decode(MOPS_DECODER, SETGP);
        assertEquals(MemoryOp64.Ir64MopsPhase.PROLOGUE, p.phase());
        assertEquals(0, p.rd());
        assertEquals(1, p.rn());
        assertEquals(2, p.rs());

        MemoryOp64.MemorySetTagged m = (MemoryOp64.MemorySetTagged) decode(MOPS_DECODER, SETGM);
        assertEquals(MemoryOp64.Ir64MopsPhase.MAIN, m.phase());

        MemoryOp64.MemorySetTagged e = (MemoryOp64.MemorySetTagged) decode(MOPS_DECODER, SETGE);
        assertEquals(MemoryOp64.Ir64MopsPhase.EPILOGUE, e.phase());
    }

    @Test
    void ldaprImmediateFamilyDecodesInsteadOfBeingConfusedWithMops() {
        // LDAPUR/LDAPURB/STLUR (FEAT_LRCPC2, B19.19) compartilham o prefixo de 6 bits `011001`
        // (bits[29:24]) com SETP/CPYFx (bit26=0) e só divergem em bits[11:10] — sem checar esse
        // campo, LDAPURB (opc=01) misdecodificaria como CPYFM (achado real da B19.16). `MOPS_DECODER`
        // (`ARMV8_8_A`) inclui `LRCPC2` (`ARMv8.4-A`) por composição, então os 3 decodificam de
        // verdade aqui — ver `Aarch64Lrcpc2DecoderTest` para a cobertura completa de B19.19.
        MemoryOp64.Load64 x = (MemoryOp64.Load64) decode(MOPS_DECODER, LDAPUR_X);
        assertEquals(0, x.rt());
        assertEquals(1, x.rn());
        assertEquals(8L, x.immediate());

        MemoryOp64.Load64 b = (MemoryOp64.Load64) decode(MOPS_DECODER, LDAPURB_W);
        assertEquals(0, b.rt());
        assertEquals(1, b.rn());

        MemoryOp64.Store64 s = (MemoryOp64.Store64) decode(MOPS_DECODER, STLUR_X);
        assertEquals(0, s.rt());
        assertEquals(1, s.rn());
        assertEquals(8L, s.immediate());
    }
}
