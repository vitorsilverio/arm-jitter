package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.MProfileException;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B7.4 — presets `ARMV6M`/`ARMV7M`: restrição de decode (v6-M não tem Thumb-2 largo), gating de
/// SYSm/`CPS f` por `M_FAULT_MASKING`, entrada de `SVC` pela exceção M e a invariante G2/G3 (os
/// presets A-profile não ganharam `M_PROFILE`/`M_FAULT_MASKING`).
class ArmArchitectureMProfilePresetsTest {

    private static DecodedInstruction decode16(ArmArchitecture architecture, int raw) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, raw);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decode32(ArmArchitecture architecture, int hi, int lo) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi);
        memory.put16(2, lo);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    // ── v6-M rejeita Thumb-2 largo; v7-M aceita ─────────────────────────────────────────────

    @Test
    void armv6mRejectsWideThumb2ThatArmv7mAccepts() {
        // LDR.W r0,[r1] = 0xF8D1 0000 ; ADD.W r0,r1,#0 = 0xF101 0000
        assertEquals(InstructionKind.UNIMPLEMENTED, decode32(ArmArchitecture.ARMV6M, 0xF8D1, 0x0000).kind(),
                "ARMv6-M não tem LDR.W");
        assertEquals(InstructionKind.UNIMPLEMENTED, decode32(ArmArchitecture.ARMV6M, 0xF101, 0x0000).kind(),
                "ARMv6-M não tem ADD.W");
        assertEquals(InstructionKind.LOAD, decode32(ArmArchitecture.ARMV7M, 0xF8D1, 0x0000).kind(),
                "ARMv7-M decodifica LDR.W");
        assertEquals(InstructionKind.ADD, decode32(ArmArchitecture.ARMV7M, 0xF101, 0x0000).kind(),
                "ARMv7-M decodifica ADD.W");
    }

    // ── MRS/MSR e CPS de 16 bits só sob M_PROFILE; BASEPRI/FAULTMASK/CPS f só sob M_FAULT_MASKING ─

    @Test
    void mrsSysmDecodesUnderMProfileButNotAProfile() {
        // MRS r0, PRIMASK(16) = 0xF3EF 0x8010
        assertEquals(InstructionKind.MPROFILE_MRS, decode32(ArmArchitecture.ARMV6M, 0xF3EF, 0x8010).kind());
        assertEquals(InstructionKind.MPROFILE_MRS, decode32(ArmArchitecture.ARMV7M, 0xF3EF, 0x8010).kind());
    }

    @Test
    void basepriSysmNeedsFaultMaskingFeature() {
        // MRS r0, BASEPRI(17) = 0xF3EF 0x8011
        assertEquals(InstructionKind.UNIMPLEMENTED, decode32(ArmArchitecture.ARMV6M, 0xF3EF, 0x8011).kind(),
                "ARMv6-M não tem BASEPRI");
        assertEquals(InstructionKind.MPROFILE_MRS, decode32(ArmArchitecture.ARMV7M, 0xF3EF, 0x8011).kind(),
                "ARMv7-M tem BASEPRI");
    }

    @Test
    void unsupportedSysmIsUndefined() {
        // SYSm 4 (reservado, entre EAPSR/XPSR e IPSR) → UNDEFINED em ambos.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode32(ArmArchitecture.ARMV7M, 0xF3EF, 0x8004).kind());
    }

    @Test
    void cps16OnlyUnderMProfileAndCpsFNeedsFaultMasking() {
        assertEquals(InstructionKind.CPS, decode16(ArmArchitecture.ARMV6M, 0xB672).kind(), "CPSID i no v6-M");
        // CPSID f (0xB671) precisa de M_FAULT_MASKING → UNDEFINED no v6-M, CPS no v7-M.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode16(ArmArchitecture.ARMV6M, 0xB671).kind());
        assertEquals(InstructionKind.CPS, decode16(ArmArchitecture.ARMV7M, 0xB671).kind());
        // ⚠️ B9.3: o mesmo prefixo de 11 bits (0xB660-0xB67F) é reaproveitado pelo `CPS` A/R-profile
        // genuíno de 16 bits (ARMv6 T1, ARM DDI 0406C A8.8.27) — ANTES desta task, o A-profile não
        // decodificava nada ali (gap real, não invariante G2/G3: a instrução pertence à
        // arquitetura-alvo, ver `b7-plano-cobertura-isa.md`). Sob A-profile 0xB672 agora É `CPS`
        // (`CPSID i`), só que empacotado com o campo `A` (ver `ThumbDecoder`), não o layout
        // exclusivo do perfil M — a precedência (`!M_PROFILE` no decoder) garante que os dois
        // presets nunca competem pelo mesmo encoding.
        assertEquals(InstructionKind.CPS, decode16(ArmArchitecture.ARMV6K_THUMB2, 0xB672).kind());
    }

    // ── SVC em ARMV6M entra pela exceção M (não pelo SwiDispatcher) ──────────────────────────

    @Test
    void svcOnArmv6mEntersMProfileException() {
        TestAddressSpace memory = new TestAddressSpace(1024);
        int handler = 0x200;
        memory.put32(4 * MProfileException.SVCALL.number(), handler | 1); // vetor Thumb
        memory.put16(0x100, 0xDF00); // SVC #0
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6M);
        core.setExceptionModel(new MProfileExceptionModel());
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x300);
        core.setProgramCounter(0x100);

        core.step();

        assertEquals(MProfileException.SVCALL.number(),
                ((MProfileExceptionModel) core.exceptionModel()).currentException(),
                "SVC deve entrar na exceção SVCall do perfil M");
        assertEquals(handler, core.programCounter());
    }

    // ── G2/G3: presets A-profile inalterados (não ganharam as features novas do perfil M) ────

    @Test
    void existingAProfilePresetsDoNotGainMProfileFeatures() {
        ArmArchitecture[] aProfile = {
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K,
                ArmArchitecture.ARMV6K_THUMB2, ArmArchitecture.ARMV7A, ArmArchitecture.ARM11_MPCORE
        };
        for (ArmArchitecture arch : aProfile) {
            assertFalse(arch.has(ArmFeature.M_PROFILE), arch + " não deve ter M_PROFILE");
            assertFalse(arch.has(ArmFeature.M_FAULT_MASKING), arch + " não deve ter M_FAULT_MASKING");
        }
        // Amostras de que as features antigas seguem intactas.
        assertTrue(ArmArchitecture.ARMV5TE.has(ArmFeature.BLX));
        assertTrue(ArmArchitecture.ARMV6K.has(ArmFeature.WAIT_HINTS));
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.DIVIDE));
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.VFPV2));
    }

    @Test
    void mProfilePresetsHaveExpectedFeatureComposition() {
        assertTrue(ArmArchitecture.ARMV6M.has(ArmFeature.M_PROFILE));
        assertTrue(ArmArchitecture.ARMV6M.has(ArmFeature.THUMB2));
        assertFalse(ArmArchitecture.ARMV6M.has(ArmFeature.M_FAULT_MASKING), "v6-M não tem BASEPRI/FAULTMASK");
        assertFalse(ArmArchitecture.ARMV6M.has(ArmFeature.VFPV2), "sem VFP");

        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.M_PROFILE));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.M_FAULT_MASKING));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.DIVIDE));
        assertFalse(ArmArchitecture.ARMV7M.has(ArmFeature.VFPV2), "Cortex-M3 sem FP (fora de escopo B7.4)");
    }
}
