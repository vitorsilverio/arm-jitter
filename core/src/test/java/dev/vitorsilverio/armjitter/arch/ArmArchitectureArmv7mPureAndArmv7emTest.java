package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B15.1 — `ARMV7M_PURE` (Cortex-M3/SC300, sem DSP) e `ARMV7EM` (alias correto do `ARMV7M`
/// existente, que já é um ARMv7E-M desde a B9.16). Ver Javadoc de {@link ArmArchitecture#ARMV7M_PURE}.
class ArmArchitectureArmv7mPureAndArmv7emTest {

    private static DecodedInstruction decode32(ArmArchitecture architecture, int hi, int lo) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hi);
        memory.put16(2, lo);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    // ── ARMV7EM é o MESMO objeto que ARMV7M (identidade, não cópia) ─────────────────────────
    @Test
    void armv7emIsTheSameReferenceAsArmv7m() {
        assertSame(ArmArchitecture.ARMV7M, ArmArchitecture.ARMV7EM,
                "ARMV7EM é o nome arquiteturalmente correto do preset ARMV7M existente, não uma cópia");
    }

    // ── ARMV7M_PURE não tem a extensão DSP; ARMV7M/ARMV7EM têm ──────────────────────────────
    @Test
    void armv7mPureRejectsDspButArmv7mAndArmv7emAccept() {
        // SADD8 r0,r1,r2 = 0xFA81 0xF002 (Thumb-2 "register data processing", family=0x8, op=0x0).
        int hi = 0xFA81;
        int lo = 0xF002;

        assertEquals(InstructionKind.UNIMPLEMENTED, decode32(ArmArchitecture.ARMV7M_PURE, hi, lo).kind(),
                "Cortex-M3 (ARMv7-M puro) não tem SADD8/DSP");
        assertEquals(InstructionKind.PARALLEL_ALU, decode32(ArmArchitecture.ARMV7M, hi, lo).kind(),
                "ARMV7M (= ARMv7E-M) decodifica SADD8");
        assertEquals(InstructionKind.PARALLEL_ALU, decode32(ArmArchitecture.ARMV7EM, hi, lo).kind(),
                "ARMV7EM decodifica SADD8");
    }

    // ── ARMV7M_PURE continua com o resto do Thumb-2 largo do perfil M ───────────────────────
    @Test
    void armv7mPureStillHasNonDspWideThumb2() {
        // LDR.W r0,[r1] = 0xF8D1 0x0000.
        assertEquals(InstructionKind.LOAD, decode32(ArmArchitecture.ARMV7M_PURE, 0xF8D1, 0x0000).kind(),
                "ARMv7-M puro decodifica LDR.W");
        // ADD.W r0,r1,#0 = 0xF101 0x0000.
        assertEquals(InstructionKind.ADD, decode32(ArmArchitecture.ARMV7M_PURE, 0xF101, 0x0000).kind(),
                "ARMv7-M puro decodifica ADD.W");
        // MRS r0, BASEPRI(17) = 0xF3EF 0x8011 — precisa de M_FAULT_MASKING, que ARMV7M_PURE tem.
        assertEquals(InstructionKind.MPROFILE_MRS, decode32(ArmArchitecture.ARMV7M_PURE, 0xF3EF, 0x8011).kind(),
                "ARMv7-M puro tem BASEPRI (M_FAULT_MASKING, independente de DSP)");
    }

    // ── composição de features ───────────────────────────────────────────────────────────────
    @Test
    void armv7mPureHasExpectedFeatureComposition() {
        assertFalse(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.PACK_SATURATE));
        assertFalse(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.PARALLEL_SIMD));
        assertFalse(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.SIGNED_MULTIPLY_MEDIA));
        assertFalse(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.DSP_MULTIPLY));
        assertFalse(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.UMAAL));

        assertTrue(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.M_PROFILE));
        assertTrue(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.M_FAULT_MASKING));
        assertTrue(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.DIVIDE));
        assertTrue(ArmArchitecture.ARMV7M_PURE.has(ArmFeature.CLZ));

        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.PACK_SATURATE), "ARMV7M continua intocado (G3)");
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.UMAAL), "ARMV7M continua intocado (G3)");
    }
}
