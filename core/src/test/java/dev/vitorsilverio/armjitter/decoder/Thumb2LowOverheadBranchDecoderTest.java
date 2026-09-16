package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import org.junit.jupiter.api.Test;

/// B15.6 — `Thumb2LowOverheadBranchDecoder`: `DLS`/`WLS`/`LE` (formas puras, sem tail-predication)
/// sob o preset `ARMV8_1M`.
class Thumb2LowOverheadBranchDecoderTest {
    private static final int ADDRESS = 0x100;

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2LowOverheadBranchDecoder(architecture).tryDecode(raw, ADDRESS, Condition.AL);
    }

    // ── DLS ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void dlsDecodesLoopStartWithoutSkipBranch() {
        // DLS R2: 0xF040E001 | (2<<16).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M, 0xF040E001 | (2 << 16));
        assertEquals(InstructionKind.LOOP_START, decoded.kind());
        assertEquals(2, decoded.sourceRegister());
        assertFalse(decoded.link(), "DLS nunca desvia (hasSkipBranch=false)");
    }

    @Test
    void dlsDoesNotDecodeWithoutLowOverheadBranch() {
        assertNull(tryDecode(ArmArchitecture.ARMV8M_MAINLINE, 0xF040E001));
    }

    // ── WLS ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void wlsDecodesLoopStartWithSkipBranchTarget() {
        // WLS R1, #6 -> alvo = address + 4 + 6.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M, 0xF040C001 | (1 << 16) | 6);
        assertEquals(InstructionKind.LOOP_START, decoded.kind());
        assertEquals(1, decoded.sourceRegister());
        assertTrue(decoded.link(), "WLS pode desviar (hasSkipBranch=true)");
        assertEquals(ADDRESS + 4 + 6, decoded.immediate());
    }

    @Test
    void wlsDoesNotDecodeWithoutLowOverheadBranch() {
        assertNull(tryDecode(ArmArchitecture.ARMV8M_MAINLINE, 0xF040C001 | 6));
    }

    // ── LE ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void leDecodesLoopEndWithBackwardTarget() {
        // LE #6 (f=0,tp=0) -> alvo = address + 4 - 6.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M, 0xF00FC001 | 6);
        assertEquals(InstructionKind.LOOP_END, decoded.kind());
        assertFalse(decoded.link(), "f=0 -> forever=false");
        assertEquals(ADDRESS + 4 - 6, decoded.immediate());
    }

    @Test
    void leForeverBitDecodesLinkTrue() {
        // LE (f=1) #6 -> forever=true.
        int raw = 0xF00FC001 | (1 << 21) | 6;
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M, raw);
        assertEquals(InstructionKind.LOOP_END, decoded.kind());
        assertTrue(decoded.link(), "f=1 -> forever=true");
        assertEquals(ADDRESS + 4 - 6, decoded.immediate());
    }

    @Test
    void leTailPredicatedFormDoesNotDecode() {
        // LETP (tp=1): mesmo espaço de bits, mas exige FEAT_MVE (ausente) -- ver Javadoc da classe.
        int raw = 0xF00FC001 | (1 << 20) | 6;
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, raw));
    }

    @Test
    void leDoesNotDecodeWithoutLowOverheadBranch() {
        assertNull(tryDecode(ArmArchitecture.ARMV8M_MAINLINE, 0xF00FC001 | 6));
    }

    // ── Espaços vizinhos (tail-predication / LCTP) não decodificam aqui ────────────────────────────

    @Test
    void dlstpAndLctpEncodingSpaceDoesNotDecodeHere() {
        // DLS_tp genérico (size=1, rn=3): 0xF000E001 | (1<<20) | (3<<16) -- fora do escopo desta task.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xF000E001 | (1 << 20) | (3 << 16)));
        // LCTP exato: 0xF00FE001.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xF00FE001));
    }

    @Test
    void unrelatedNocpEncodingDoesNotDecode() {
        // NOCP forma 1 clássica (MCR/MRC): não deve ser capturado por este decoder.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xEE09_1F11));
    }
}
