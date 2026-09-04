package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B9.3 — as instruções T16 genuínas de ARMv6 (16 bits, fora do espaço Thumb-2): `SETEND`,
/// `CPS` A/R-profile, `REV`/`REV16`/`REVSH` e `SXTH`/`SXTB`/`UXTH`/`UXTB` (achado de triagem: NÃO
/// `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB` — ver javadoc das constantes em `ThumbDecoder`). Todos os 13
/// encodings abaixo vieram de corpus real (`arm-none-eabi-as -mcpu=arm1176jzf-s` + `objdump`,
/// devkitARM), não derivados só do manual/QEMU.
class ThumbV6GenuineDecoderTest {
    private static DecodedInstruction decode(ArmArchitecture architecture, int raw) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, raw);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    // ── SETEND: `setend le`=0xb650, `setend be`=0xb658 ──────────────────────────────────────

    @Test
    void setendLeDecodesWithImmediateZero() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB650);
        assertEquals(InstructionKind.SETEND, instruction.kind());
        assertEquals(0, instruction.immediate());
    }

    @Test
    void setendBeDecodesWithImmediateOne() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB658);
        assertEquals(InstructionKind.SETEND, instruction.kind());
        assertEquals(1, instruction.immediate());
    }

    // ── CPS A/R-profile: `cpsie i`=0xb662, `cpsid i`=0xb672, `cpsie f`=0xb661, ─────────────
    // `cpsid aif`=0xb677 ─────────────────────────────────────────────────────────────────────

    @Test
    void cpsieIDecodesAsChangeProcessorState() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB662);
        assertEquals(InstructionKind.CPS, instruction.kind());
        int packed = instruction.immediate();
        assertEquals(0, packed & 1, "imod baixo = 0 (IE)");
        assertEquals(1, (packed >>> 1) & 1, "changeFlags sempre 1 neste T1");
        assertEquals(0, (packed >>> 3) & 1, "changeA=0 (só 'i')");
        assertEquals(1, (packed >>> 4) & 1, "changeI=1");
        assertEquals(0, (packed >>> 5) & 1, "changeF=0");
    }

    @Test
    void cpsidIDecodesWithDisableBit() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB672);
        assertEquals(InstructionKind.CPS, instruction.kind());
        int packed = instruction.immediate();
        assertEquals(1, packed & 1, "imod baixo = 1 (ID)");
        assertEquals(1, (packed >>> 4) & 1, "changeI=1");
    }

    @Test
    void cpsieFDecodesChangingOnlyF() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB661);
        assertEquals(InstructionKind.CPS, instruction.kind());
        int packed = instruction.immediate();
        assertEquals(0, (packed >>> 3) & 1, "changeA=0");
        assertEquals(0, (packed >>> 4) & 1, "changeI=0");
        assertEquals(1, (packed >>> 5) & 1, "changeF=1");
    }

    @Test
    void cpsidAifDecodesChangingAllThreeFlags() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB677);
        assertEquals(InstructionKind.CPS, instruction.kind());
        int packed = instruction.immediate();
        assertEquals(1, packed & 1, "imod baixo = 1 (ID)");
        assertEquals(1, (packed >>> 3) & 1, "changeA=1");
        assertEquals(1, (packed >>> 4) & 1, "changeI=1");
        assertEquals(1, (packed >>> 5) & 1, "changeF=1");
    }

    // ── REV/REV16/REVSH: `rev r0,r1`=0xba08, `rev16 r2,r3`=0xba5a, `revsh r4,r5`=0xbaec ─────

    @Test
    void revDecodesWithVariantZero() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBA08);
        assertEquals(InstructionKind.BYTE_REVERSE, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(1, instruction.sourceRegister());
        assertEquals(0, instruction.immediate());
    }

    @Test
    void rev16DecodesWithVariantOne() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBA5A);
        assertEquals(InstructionKind.BYTE_REVERSE, instruction.kind());
        assertEquals(2, instruction.destinationRegister());
        assertEquals(3, instruction.sourceRegister());
        assertEquals(1, instruction.immediate());
    }

    @Test
    void revshDecodesWithVariantTwo() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBAEC);
        assertEquals(InstructionKind.BYTE_REVERSE, instruction.kind());
        assertEquals(4, instruction.destinationRegister());
        assertEquals(5, instruction.sourceRegister());
        assertEquals(2, instruction.immediate());
    }

    // ── SXTH/SXTB/UXTH/UXTB: sem acumulador (achado de triagem) ─────────────────────────────

    @Test
    void sxthDecodesWithoutAccumulator() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB208); // sxth r0,r1
        assertEquals(InstructionKind.EXTEND, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(-1, instruction.sourceRegister(), "sem acumulador");
        assertEquals(1, instruction.secondSourceRegister());
    }

    @Test
    void sxtbDecodesWithoutAccumulator() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB25A); // sxtb r2,r3
        assertEquals(InstructionKind.EXTEND, instruction.kind());
        assertEquals(2, instruction.destinationRegister());
        assertEquals(-1, instruction.sourceRegister());
        assertEquals(3, instruction.secondSourceRegister());
    }

    @Test
    void uxthDecodesWithoutAccumulator() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB2AC); // uxth r4,r5
        assertEquals(InstructionKind.EXTEND, instruction.kind());
        assertEquals(4, instruction.destinationRegister());
        assertEquals(-1, instruction.sourceRegister());
        assertEquals(5, instruction.secondSourceRegister());
    }

    @Test
    void uxtbDecodesWithoutAccumulator() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB2FE); // uxtb r6,r7
        assertEquals(InstructionKind.EXTEND, instruction.kind());
        assertEquals(6, instruction.destinationRegister());
        assertEquals(-1, instruction.sourceRegister());
        assertEquals(7, instruction.secondSourceRegister());
    }

    // ── Gating G2: ARMv4T/ARMv5TE mantêm UNDEFINED (nunca aplicar ARMv6 a presets anteriores) ─

    @Test
    void allSevenEncodingsStayUndefinedOnArmv4tAndArmv5te() {
        int[] encodings = {0xB650, 0xB658, 0xB662, 0xB672, 0xBA08, 0xBA5A, 0xBAEC, 0xB208, 0xB25A, 0xB2AC, 0xB2FE};
        for (int raw : encodings) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV4T, raw).kind(),
                    () -> "ARMv4T deve manter UNDEFINED: 0x" + Integer.toHexString(raw));
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV5TE, raw).kind(),
                    () -> "ARMv5TE deve manter UNDEFINED: 0x" + Integer.toHexString(raw));
        }
    }

    // ── Precedência: M-profile continua reivindicando o MESMO prefixo de CPS (B7.4, zero-diff) ─

    @Test
    void mProfileCpsEncodingIsUnaffectedByThisTask() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV7M, 0xB662); // CPSIE i (M-profile)
        assertEquals(InstructionKind.CPS, instruction.kind());
        // M-profile empacota diferente (sem changeA): bit 3 continua reservado ali, não "A".
        int packed = instruction.immediate();
        assertEquals(1, (packed >>> 4) & 1, "changeI=1, mesma semântica de sempre");
    }

    @Test
    void aProfileReservedBitKeepsEncodingUndefined() {
        // Bit 3 (SBZ) setado: nem M-profile nem A/R-profile reconhecem — UNDEFINED nos dois.
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xB662 | (1 << 3));
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── E13 (reverte B9.14): o espaço de hint T1 (mask==0000) inteiro só existe a partir do
    // ── Thumb-2 em perfil A (QEMU 2931a675e9d3, trans_MAYBE_UNDEF_T1_HINT, catch-all checado
    // ── ANTES dos hints nomeados) — a leitura da B9.14 ("basta WAIT_HINTS/ARMv6K") valia para a
    // ── forma A32 dos hints (espaço da MSR imediata), não para esta forma T16 de 16 bits.
    // ── `ARMV6K`/`ARM11_MPCORE` (sem Thumb-2) voltam a UNIMPLEMENTED nos 5 hints. `IT` e `CBZ`
    // ── continuam exigindo THUMB2 de verdade (ARMv6T2+, curados em isa-nao-aplicavel.tsv). ──────

    @Test
    void nopStaysUndefinedOnArmv6kWithoutThumb2() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBF00); // NOP
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void yieldStaysUndefinedOnArmv6kWithoutThumb2() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBF10); // YIELD
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void wfeStaysUndefinedOnArmv6kWithoutThumb2() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBF20); // WFE
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void wfiStaysUndefinedOnArmv6kWithoutThumb2() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBF30); // WFI
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void sevStaysUndefinedOnArmv6kWithoutThumb2() {
        DecodedInstruction instruction = decode(ArmArchitecture.ARMV6K, 0xBF40); // SEV
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void hintsStayUndefinedOnArm11MpCoreWithoutThumb2() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, 0xBF00).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, 0xBF10).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, 0xBF20).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, 0xBF30).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, 0xBF40).kind());
    }

    @Test
    void hintsKeepDecodingOnMProfilePresetsWithoutRegression() {
        // Regressão negativa (E13, Aceite): perfil M sempre teve o espaço de hint T1 (QEMU
        // trans_MAYBE_UNDEF_T1_HINT: `arm_dc_feature(s, ARM_FEATURE_M)` cai direto, sem checar
        // Thumb-2). ARMV6M/ARMV7M continuam decodificando os 5 hints sem mudança.
        assertEquals(InstructionKind.MSR, decode(ArmArchitecture.ARMV6M, 0xBF00).kind()); // NOP
        assertEquals(InstructionKind.MSR, decode(ArmArchitecture.ARMV6M, 0xBF10).kind()); // YIELD
        assertEquals(InstructionKind.MSR, decode(ArmArchitecture.ARMV6M, 0xBF20).kind()); // WFE
        assertEquals(InstructionKind.WAIT_FOR_INTERRUPT, decode(ArmArchitecture.ARMV6M, 0xBF30).kind()); // WFI
        assertEquals(InstructionKind.MSR, decode(ArmArchitecture.ARMV6M, 0xBF40).kind()); // SEV
        assertEquals(InstructionKind.MSR, decode(ArmArchitecture.ARMV7M, 0xBF00).kind());
        assertEquals(InstructionKind.WAIT_FOR_INTERRUPT, decode(ArmArchitecture.ARMV7M, 0xBF30).kind());
    }

    @Test
    void hintsStayUndefinedOnArmv4tAndArmv5teWithoutWaitHints() {
        int[] encodings = {0xBF00, 0xBF10, 0xBF20, 0xBF30, 0xBF40};
        for (int raw : encodings) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV4T, raw).kind(),
                    () -> "ARMv4T deve manter UNDEFINED: 0x" + Integer.toHexString(raw));
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV5TE, raw).kind(),
                    () -> "ARMv5TE deve manter UNDEFINED: 0x" + Integer.toHexString(raw));
        }
    }

    @Test
    void itStaysUndefinedOnArmv6kAndMpCoreWithoutThumb2() {
        int itEncoding = 0xBF18; // IT EQ (firstcond=0b1110? mask!=0000 — qualquer mask!=0)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV6K, itEncoding).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, itEncoding).kind());
    }

    @Test
    void cbzStaysUndefinedOnArmv6kAndMpCoreWithoutThumb2() {
        int cbzEncoding = 0xB100; // CBZ r0, +0
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV6K, cbzEncoding).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, cbzEncoding).kind());
    }

    @Test
    void hintsAndItAndCbzKeepWorkingOnArchitecturesWithThumb2() {
        DecodedInstruction nop = decode(ArmArchitecture.ARMV7A, 0xBF00);
        assertEquals(InstructionKind.MSR, nop.kind());
        DecodedInstruction it = decode(ArmArchitecture.ARMV7A, 0xBF18);
        assertEquals(InstructionKind.IT, it.kind());
        DecodedInstruction cbz = decode(ArmArchitecture.ARMV7A, 0xB100);
        assertEquals(InstructionKind.COMPARE_BRANCH_ZERO, cbz.kind());
    }
}
