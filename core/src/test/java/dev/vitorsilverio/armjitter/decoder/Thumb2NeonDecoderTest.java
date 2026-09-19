package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// `Thumb2NeonDecoder` (B13.16): transforma T32 para A32 e delega aos decoders NEON do épico B13,
/// sem reimplementar semântica. Cada caso compara o `IrOp` produzido a partir da palavra T32
/// (transformada e delegada) com o `IrOp` produzido a partir da palavra A32 correspondente
/// (decodificada diretamente pelo decoder A32 dono da seção) — a amostra cobre todas as seções do
/// épico, como pede o Aceite da task (equivalência A32↔T32 substitui testar as 297 linhas uma a
/// uma).
class Thumb2NeonDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestThumb2Neon",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final int ADDRESS = 0;

    private static IrOp a32LiftedOp(DecoderExtension decoder, int a32Word) {
        DecodedInstruction decoded = decoder.tryDecode(a32Word, ADDRESS, Condition.AL);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind(), "amostra A32 precisa decodificar de verdade");
        return decoded.liftedOp();
    }

    private static DecodedInstruction thumb2Decode(int t32Word) {
        return new Thumb2NeonDecoder(NEON_FEATURES).tryDecode(t32Word, ADDRESS, Condition.AL);
    }

    /// Confere `raw`/`InstructionSet`/`IrOp` de uma amostra T32 contra o decoder A32 dono da seção
    /// (a mesma verificação de todos os casos abaixo — ver Aceite "Equivalência A32↔T32").
    private static void assertEquivalent(DecoderExtension a32Decoder, int a32Word, int t32Word) {
        IrOp expected = a32LiftedOp(a32Decoder, a32Word);
        DecodedInstruction decoded = thumb2Decode(t32Word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertEquals(expected, decoded.liftedOp());
        assertEquals(t32Word, decoded.raw(), "raw tem que ser o T32 ORIGINAL, não a palavra transformada");
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    // ── neon-dp: "3-reg-same" (NeonDataProcessingDecoder, B13.4/B13.5/B13.6) ──
    // vadd.i32 d0,d1,d2 — A32 0xF221_0802 (bit24=`p`=0) → T32 0xEF21_0802
    @Test
    void threeSameEquivalence() {
        assertEquivalent(new NeonDataProcessingDecoder(NEON_FEATURES), 0xF221_0802, 0xEF21_0802);
    }

    // ── neon-dp: "2-reg-and-shift" por imediato (NeonShiftImmediateDecoder, B13.7/B13.8) ──
    // vshr.s8 d0,d1,#1 — A32 0xF28F_0011 (bit24=0) → T32 0xEF8F_0011
    @Test
    void shiftImmediateEquivalence() {
        assertEquivalent(new NeonShiftImmediateDecoder(NEON_FEATURES), 0xF28F_0011, 0xEF8F_0011);
    }

    // ── neon-dp: "1-reg-and-modified-immediate" (NeonModifiedImmediateDecoder, B13.9) ──
    // vmov.i32 d0,#0xFF — A32 0xF387_001F (bit24=1) → T32 0xFF87_001F
    @Test
    void modifiedImmediateEquivalence() {
        assertEquivalent(new NeonModifiedImmediateDecoder(NEON_FEATURES), 0xF387_001F, 0xFF87_001F);
    }

    // ── neon-dp: "3-reg-different-lengths" (NeonThreeRegDifferentDecoder, B13.10/B13.11) ──
    // vaddl.s8 q0,d0,d1 — A32 0xF280_0001 (bit24=0) → T32 0xEF80_0001
    @Test
    void threeRegDifferentEquivalence() {
        assertEquivalent(new NeonThreeRegDifferentDecoder(NEON_FEATURES), 0xF280_0001, 0xEF80_0001);
    }

    // ── neon-dp: "two-reg-misc" (NeonTwoRegMiscDecoder, B13.12/B13.13/B13.15) ──
    // vrev64.8 d0,d1 — A32 0xF3B0_0001 (bit24=1) → T32 0xFFB0_0001
    @Test
    void twoRegMiscEquivalence() {
        assertEquivalent(new NeonTwoRegMiscDecoder(NEON_FEATURES), 0xF3B0_0001, 0xFFB0_0001);
    }

    // ── neon-dp: "size==0b11" residual (NeonExtractTableDuplicateDecoder, B13.14) ──
    // vext.8 d0,d1,d2,#3 — A32 0xF2B1_0302 (bit24=0) → T32 0xEFB1_0302
    @Test
    void extractEquivalence() {
        assertEquivalent(new NeonExtractTableDuplicateDecoder(NEON_FEATURES), 0xF2B1_0302, 0xEFB1_0302);
    }

    // vtbl.8 d0,{d1},d2 — A32 0xF3B1_0802 (bit24=1) → T32 0xFFB1_0802
    @Test
    void tableLookupEquivalence() {
        assertEquivalent(new NeonExtractTableDuplicateDecoder(NEON_FEATURES), 0xF3B1_0802, 0xFFB1_0802);
    }

    // ── neon-ls (NeonLoadStoreDecoder, B13.3) — byte alto INTEIRO troca 0xF4→0xF9, não um bit só ──
    // VLDST_multiple (vst1.8 {d0},[r15]) — A32 0xF421_070F → T32 0xF921_070F
    @Test
    void loadStoreMultipleEquivalence() {
        assertEquivalent(new NeonLoadStoreDecoder(NEON_FEATURES), 0xF421_070F, 0xF921_070F);
    }

    // VLD_all_lanes — A32 0xF4A1_006F → T32 0xF9A1_006F
    @Test
    void loadStoreAllLanesEquivalence() {
        assertEquivalent(new NeonLoadStoreDecoder(NEON_FEATURES), 0xF4A1_006F, 0xF9A1_006F);
    }

    // ── Gate duplo (Aceite) ──

    @Test
    void withoutAdvancedSimdEveryFrameStaysNull() {
        Thumb2NeonDecoder decoder = new Thumb2NeonDecoder(ArmArchitecture.ARMV7A);
        assertNull(decoder.tryDecode(0xEF21_0802, ADDRESS, Condition.AL));
        assertNull(decoder.tryDecode(0xF921_070F, ADDRESS, Condition.AL));
    }

    // `THUMB2` já é garantido estruturalmente por `ThumbDecoder#tryDecodeThumb32` (só invoca as
    // extensões de 32 bits com a feature ligada) — não há caminho de chegar aqui sem ela, mesmo
    // padrão de todo `Thumb2*Decoder` existente (nenhum re-checa `THUMB2`).

    // ── Armadilha 1: a máscara não pode engolir Thumb-2 legítimo fora do espaço NEON ──

    @Test
    void doesNotSwallowMovwImmediate() {
        // MOVW Rd,#0 — hw1=0xF240 (T3: 11110 i 100100 imm4), hw2=0x0000. bits[27:24]=0010, não
        // `1111` — fora do frame `neon-dp` (`0xEF00_0000`) e do frame `neon-ls` (`0xF900_0000`).
        int movw = 0xF240_0000;
        assertNull(thumb2Decode(movw));
    }

    @Test
    void doesNotSwallowLdrWImmediate() {
        // LDR.W Rt,[Rn] — hw1=0xF8D0 (T3: 1111 1000 1101 nnnn), hw2=Rt:imm12=0. bits[27:24]=1000,
        // fora dos dois frames NEON.
        int ldrW = 0xF8D0_0000;
        assertNull(thumb2Decode(ldrW));
    }
}
