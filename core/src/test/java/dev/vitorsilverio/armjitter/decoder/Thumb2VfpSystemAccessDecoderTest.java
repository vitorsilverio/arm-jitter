package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import org.junit.jupiter.api.Test;

/// B15.3 — `Thumb2VfpSystemAccessDecoder`: `VMSR_VMRS` (perfil M, `reg=FPSCR`) +
/// `VLDR_sysreg`/`VSTR_sysreg` (`target/isa-decode/m-nocp.decode`). Vetores calculados a partir do
/// layout de bits documentado no Javadoc da classe (mesmo bloco QEMU que `Thumb2NocpDecoderTest`
/// já cobre para `NOCP`).
class Thumb2VfpSystemAccessDecoderTest {
    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2VfpSystemAccessDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── VMSR_VMRS (reg=1/FPSCR) ──────────────────────────────────────────────────────────────

    @Test
    void vmrsReadsFpscrIntoDestinationRegister() {
        // VMRS r3, FPSCR: reg=1, rt=3, l=1.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xEEF1_3A10);
        assertEquals(InstructionKind.VFP_SYSTEM_TRANSFER, decoded.kind());
        assertEquals(3, decoded.destinationRegister());
        assertTrue(decoded.link(), "l=1 é VMRS (FPSCR -> rt)");
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    @Test
    void vmsrWritesSourceRegisterIntoFpscr() {
        // VMSR FPSCR, r5: reg=1, rt=5, l=0.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xEEE1_5A10);
        assertEquals(InstructionKind.VFP_SYSTEM_TRANSFER, decoded.kind());
        assertEquals(5, decoded.destinationRegister());
        assertTrue(!decoded.link(), "l=0 é VMSR (rt -> FPSCR)");
    }

    @Test
    void vmsrVmrsRejectsRegDifferentFromFpscr() {
        // reg=2 (FPSCR_NZCVQC, ARMv8.1-M — B15.6, ainda não implementada).
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEEF2_3A10));
    }

    @Test
    void vmsrVmrsRejectsRtEqualsProgramCounter() {
        // rt=15: UNPREDICTABLE no perfil M (diferente do aliasing APSR da A-profile).
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEEF1_FA10));
    }

    @Test
    void vmsrVmrsDoesNotDecodeOutsideMProfile() {
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xEEF1_3A10));
    }

    // ── VLDR_sysreg/VSTR_sysreg (reg=1/FPSCR) ────────────────────────────────────────────────

    @Test
    void vldrSysregOffsetFormDecodesWithPositiveOffsetNoWriteback() {
        // VLDR_sysreg {FPSCR}, [r2, #8]: reg=1, rn=2, imm7=2 (x4=8), add=1, load=1, p=1, w=0.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xED92_2F82);
        assertEquals(InstructionKind.VFP_SYSREG_LOAD, decoded.kind());
        assertEquals(-1, decoded.destinationRegister(), "destino não é um GPR");
        assertEquals(2, decoded.sourceRegister(), "Rn é a base");
        assertEquals(8, decoded.immediate());
        assertTrue(!decoded.writeback());
        assertTrue(!decoded.postIndexed());
        assertEquals(4, decoded.accessSizeBytes());
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    @Test
    void vstrSysregPreIndexedFormDecodesWithNegativeOffsetAndWriteback() {
        // VSTR_sysreg {FPSCR}, [r2, #-8]!: reg=1, rn=2, imm7=2, add=0, load=0, p=1, w=1.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xED22_2F82);
        assertEquals(InstructionKind.VFP_SYSREG_STORE, decoded.kind());
        assertEquals(2, decoded.sourceRegister());
        assertEquals(-8, decoded.immediate());
        assertTrue(decoded.writeback());
        assertTrue(!decoded.postIndexed());
    }

    @Test
    void vldrSysregPostIndexedFormAlwaysWritesBack() {
        // VLDR_sysreg {FPSCR}, [r2], #8: reg=1, rn=2, imm7=2, add=1, load=1, p=0, w=1 (forçado).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xECB2_2F82);
        assertEquals(InstructionKind.VFP_SYSREG_LOAD, decoded.kind());
        assertEquals(8, decoded.immediate());
        assertTrue(decoded.writeback());
        assertTrue(decoded.postIndexed());
    }

    @Test
    void vldrVstrSysregRejectsRegDifferentFromFpscr() {
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xED92_4F82));
    }

    @Test
    void vldrVstrSysregRejectsRnEqualsProgramCounter() {
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xED9F_2F82));
    }

    @Test
    void vldrVstrSysregRejectsPZeroWZeroAsDifferentRelatedEncoding() {
        // P=0,W=0 é "SEE Related encodings" no arquivo real — outra instrução, fora deste bloco.
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEC92_2F82));
    }

    @Test
    void vldrVstrSysregDoesNotDecodeOutsideMProfile() {
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xED92_2F82));
    }
}
