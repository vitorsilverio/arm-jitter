package dev.vitorsilverio.armjitter.arch;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.NeonDataProcessingDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonExtractTableDuplicateDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonLoadStoreDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonModifiedImmediateDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonSharedDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonShiftImmediateDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonThreeRegDifferentDecoder;
import dev.vitorsilverio.armjitter.decoder.NeonTwoRegMiscDecoder;
import dev.vitorsilverio.armjitter.decoder.Thumb2NeonDecoder;
import dev.vitorsilverio.armjitter.decoder.Thumb2NeonSharedDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.decoder.VfpDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Fechamento do épico B13 (task B13.22): o preset público {@link ArmArchitecture#ARMV7A_NEON}
/// decodifica NEON de verdade em A32 e T32, e {@link ArmArchitecture#ARMV7A} (e os demais presets
/// existentes) continuam recusando os MESMOS encodings — G3, o preset novo nasce ao lado, nunca
/// substitui.
///
/// Os 9 encodings golden abaixo são reaproveitados de testes já verificados contra o assembler real
/// (`NeonDataProcessingDecoderTest`/`NeonShiftImmediateDecoderTest`/`NeonModifiedImmediateDecoderTest`/
/// `NeonThreeRegDifferentDecoderTest`/`NeonTwoRegMiscDecoderTest`/`NeonExtractTableDuplicateDecoderTest`/
/// `NeonLoadStoreDecoderTest`/`NeonSharedDecoderTest`) — nenhum bit novo inventado aqui. Cobrem 9 das
/// 11 seções do épico citadas no Aceite da B13.22 (faltam "2-reg-and-scalar" e "conversões"; as duas
/// são verificadas pela medição EXAUSTIVA de `docs/COBERTURA-ISA.md`, que sonda TODAS as 325 linhas
/// contra o preset real — ver `## Resultado` da task).
class ArmV7aNeonPresetTest {
    // ── Amostra A32, uma por seção do épico (exceto 2-reg-and-scalar/conversões — ver acima) ──
    private static final int VADD_I32_THREE_SAME = 0xF221_0802; // vadd.i32 d0,d1,d2 (B13.4)
    private static final int VSHR_S8_SHIFT_IMMEDIATE = 0xF28F_0011; // vshr.s8 d0,d1,#1 (B13.7)
    private static final int VMOV_I32_MODIFIED_IMMEDIATE = 0xf387_001f; // vmov.i32 d0,#0xFF (B13.9)
    private static final int VADDL_S8_THREE_REG_DIFFERENT = 0xf280_0001; // vaddl.s8 q0,d0,d1 (B13.10)
    private static final int VREV64_8_TWO_REG_MISC = 0xf3b0_0001; // vrev64.8 d0,d1 (B13.12)
    private static final int VEXT_8_PERMUTE = 0xf2b1_0302; // vext.8 d0,d1,d2,#3 (B13.14)
    private static final int VLD1_8_LOAD_STORE = 0xF421_070F; // vld1.8 {d0},[r1] (B13.3)
    private static final int AESE_8_CRYPTO = 0xf3b0_0302; // aese.8 q0,q1 (B13.15, exige CRYPTO)
    private static final int VCMLA_SHARED = 0xFC30_0800; // vcmla.f32 d0,d1,d2,#0 (B13.17, exige FCMA)

    /// Arquitetura só de teste: `ARMV7A_NEON` + TODAS as 7 features irmãs do épico — nenhum
    /// processador real do catálogo as tem juntas hoje (ver Javadoc de {@link
    /// ArmArchitecture#ARMV7A_NEON}), então isto nunca vira um preset público. Serve só para provar
    /// que a FIAÇÃO (`NeonTwoRegMiscDecoder`/`NeonSharedDecoder`/`Thumb2NeonSharedDecoder`) funciona
    /// de ponta a ponta quando as features estão presentes — o mesmo papel que
    /// `NeonTwoRegMiscDecoderTest#CRYPTO_ARCH`/`NeonSharedDecoderTest` já cumprem isoladamente.
    ///
    /// **Não pode ser um `extending(ARMV7A_NEON, ...)`** — as extensões de decoder de
    /// {@link ArmArchitecture#ARMV7A_NEON} já foram construídas fechadas sobre a instância ANTIGA
    /// (sem as features irmãs), então `extending` herdaria objetos `NeonSharedDecoder`/etc que
    /// consultam a `ArmArchitecture` ERRADA (o mesmo quebra-cabeça do ovo-e-galinha documentado em
    /// todo preset de `ArmArchitecture.java`). Por isso esta arquitetura de teste constrói suas
    /// PRÓPRIAS extensões de decoder, parametrizadas por si mesma, espelhando exatamente o padrão
    /// de {@link ArmArchitecture#ARMV7A_NEON}.
    private static final ArmArchitecture ALL_EPIC_FEATURES_BASE = ArmArchitecture.extending(
            ArmArchitecture.ARMV7A, "ARMv7-A+NEON+todas-as-features-irmãs (só teste)",
            ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32, ArmFeature.ADVANCED_SIMD_RDM,
            ArmFeature.CRYPTO, ArmFeature.COMPLEX_NUMBER_ARITHMETIC, ArmFeature.DOT_PRODUCT,
            ArmFeature.INT8_MATRIX_MULTIPLY, ArmFeature.FP16_FUSED_MULTIPLY_ADD_LONG, ArmFeature.BFLOAT16);

    private static final ArmArchitecture ALL_EPIC_FEATURES = ALL_EPIC_FEATURES_BASE
            .withDecoderExtensions(List.of(
                    new VfpDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonDataProcessingDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonShiftImmediateDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonModifiedImmediateDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonThreeRegDifferentDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonTwoRegMiscDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonExtractTableDuplicateDecoder(ALL_EPIC_FEATURES_BASE),
                    new NeonLoadStoreDecoder(ALL_EPIC_FEATURES_BASE),
                    new CoprocessorDecoder(),
                    // NeonSharedDecoder por último — mesmo motivo documentado em
                    // ArmArchitecture#ARMV7A_NEON (nunca devolve null desde a B13.21).
                    new NeonSharedDecoder(ALL_EPIC_FEATURES_BASE)))
            .withThumb32DecoderExtensions(List.of(
                    new Thumb2NeonDecoder(ALL_EPIC_FEATURES_BASE),
                    new Thumb2NeonSharedDecoder(ALL_EPIC_FEATURES_BASE)));

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    /// Converte um A32 `neon-dp`/`neon-ls` golden em T32 pela MESMA transformação que
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2NeonDecoder} desfaz — inverso do que aquele
    /// decoder faz para reconhecer o frame.
    private static int a32ToThumb32NeonDp(int a32) {
        int p = (a32 >>> 24) & 1;
        return 0xEF00_0000 | (p << 28) | (a32 & 0x00FF_FFFF);
    }

    private static int a32ToThumb32NeonLs(int a32) {
        return 0xF900_0000 | (a32 & 0x00FF_FFFF);
    }

    private static DecodedInstruction decodeThumb32(ArmArchitecture architecture, int thumb32Word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, (thumb32Word >>> 16) & 0xFFFF);
        memory.put16(2, thumb32Word & 0xFFFF);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    // ── G3: ARMV7A (e presets mais antigos) continuam recusando NEON, em A32 e T32 ──

    @Test
    void armv7aStillRejectsNeonInA32() {
        for (int word : new int[] {VADD_I32_THREE_SAME, VSHR_S8_SHIFT_IMMEDIATE, VMOV_I32_MODIFIED_IMMEDIATE,
                VADDL_S8_THREE_REG_DIFFERENT, VREV64_8_TWO_REG_MISC, VEXT_8_PERMUTE, VLD1_8_LOAD_STORE}) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, word).kind());
        }
    }

    @Test
    void armv7aStillRejectsNeonInThumb32() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ArmArchitecture.ARMV7A, a32ToThumb32NeonDp(VADD_I32_THREE_SAME)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ArmArchitecture.ARMV7A, a32ToThumb32NeonLs(VLD1_8_LOAD_STORE)).kind());
    }

    @Test
    void olderPresetsAlsoRejectNeon() {
        for (ArmArchitecture architecture : new ArmArchitecture[] {ArmArchitecture.ARMV6K,
                ArmArchitecture.ARM11_MPCORE, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV4T}) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(architecture, VADD_I32_THREE_SAME).kind());
        }
    }

    // ── ARMV7A_NEON decodifica de verdade, A32 ──

    @Test
    void armv7aNeonDecodesThreeSameShiftImmediateModifiedImmediateThreeRegDifferentTwoRegMiscAndPermute() {
        for (int word : new int[] {VADD_I32_THREE_SAME, VSHR_S8_SHIFT_IMMEDIATE, VMOV_I32_MODIFIED_IMMEDIATE,
                VADDL_S8_THREE_REG_DIFFERENT, VREV64_8_TWO_REG_MISC, VEXT_8_PERMUTE}) {
            DecodedInstruction decoded = decodeArm(ArmArchitecture.ARMV7A_NEON, word);
            assertNotEquals(InstructionKind.UNIMPLEMENTED, decoded.kind(), "word=0x" + Integer.toHexString(word));
        }
    }

    @Test
    void armv7aNeonDecodesLoadStore() {
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A_NEON, VLD1_8_LOAD_STORE).kind());
    }

    // ── ARMV7A_NEON sozinho NÃO tem as features irmãs (cripto/FCMA/...) ──

    @Test
    void armv7aNeonAloneStillRejectsSiblingFeatureEncodings() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A_NEON, AESE_8_CRYPTO).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A_NEON, VCMLA_SHARED).kind());
    }

    // ── Com as features irmãs presentes, cripto e neon-shared decodificam (prova a fiação) ──

    @Test
    void cryptoAndSharedDecodeWhenTheSiblingFeaturesArePresent() {
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ALL_EPIC_FEATURES, AESE_8_CRYPTO).kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ALL_EPIC_FEATURES, VCMLA_SHARED).kind());
    }

    // ── T32: mesmo resultado que A32, via Thumb2NeonDecoder/Thumb2NeonSharedDecoder ──

    @Test
    void armv7aNeonDecodesNeonDpAndLoadStoreInThumb32() {
        assertNotEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ArmArchitecture.ARMV7A_NEON, a32ToThumb32NeonDp(VADD_I32_THREE_SAME)).kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ArmArchitecture.ARMV7A_NEON, a32ToThumb32NeonLs(VLD1_8_LOAD_STORE)).kind());
    }

    @Test
    void sharedDecodesInThumb32WhenTheSiblingFeatureIsPresent() {
        // neon-shared: encoding IDÊNTICO entre A32 e T32 (sem transformação, ver Thumb2NeonSharedDecoder).
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ArmArchitecture.ARMV7A_NEON, VCMLA_SHARED).kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED,
                decodeThumb32(ALL_EPIC_FEATURES, VCMLA_SHARED).kind());
    }

    // ── Regressão: NeonSharedDecoder NUNCA devolve `null` desde a B13.21 (fecha o "null debt" do
    // arquivo com `unimplemented(...)` explícito) — se ele viesse ANTES de outro decoder na lista de
    // extensões, esse outro decoder nunca seria alcançado para nenhum encoding que NeonSharedDecoder
    // não reivindicasse de verdade. Achado real (não hipotético) desta task: a primeira versão do
    // preset registrava `NeonSharedDecoder`/`Thumb2NeonSharedDecoder` ANTES de
    // `CoprocessorDecoder`/`Thumb2CoprocessorDecoder`, e um `MCR`/`MRC` comum (nada relacionado a
    // NEON) virava `UNIMPLEMENTED` sob `ARMV7A_NEON` — confirmado por probe direto contra
    // `0xEE010F10` (`MCR p15,0,r0,c1,c0,0`), que decodifica `COPROCESSOR` sob `ARMV7A` e virava
    // `UNIMPLEMENTED` sob a primeira versão do preset novo. Corrigido registrando
    // `NeonSharedDecoder`/`Thumb2NeonSharedDecoder` por ÚLTIMO nas duas listas.

    /// `MCR p15,0,r0,c1,c0,0` — grava `SCTLR`, um dos usos mais comuns de `MCR` real (habilitar MMU).
    private static final int MCR_SCTLR = 0xEE01_0F10;

    @Test
    void armv7aNeonStillDecodesOrdinaryCoprocessorInstructionsInA32() {
        assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARMV7A, MCR_SCTLR).kind());
        assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARMV7A_NEON, MCR_SCTLR).kind());
    }

    @Test
    void armv7aNeonStillDecodesOrdinaryCoprocessorInstructionsInThumb32() {
        // Mesmo raw: o espaço de coprocessador Thumb-2 reusa o layout de bits do ARM clássico
        // (mesma convenção de Thumb2CoprocessorDecoder/Thumb2VfpDecoder — bits[31:28] fixo `1110`,
        // que já bate com `cond=AL` do encoding A32 acima).
        assertEquals(InstructionKind.COPROCESSOR, decodeThumb32(ArmArchitecture.ARMV7A, MCR_SCTLR).kind());
        assertEquals(InstructionKind.COPROCESSOR, decodeThumb32(ArmArchitecture.ARMV7A_NEON, MCR_SCTLR).kind());
    }

    /// `DMB SY` (Thumb-2, `Thumb2MiscDecoder`) — prova que barreiras/hints/`MSR`/`MRS`/branches
    /// largos continuam alcançáveis sob `ARMV7A_NEON` (não só o coprocessador).
    private static final int DMB_SY_T32 = 0xF3BF_8F5F;

    @Test
    void armv7aNeonStillDecodesThumb2MiscInstructions() {
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decodeThumb32(ArmArchitecture.ARMV7A, DMB_SY_T32).kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decodeThumb32(ArmArchitecture.ARMV7A_NEON, DMB_SY_T32).kind());
    }
}
