package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B15.2 — `Thumb2NocpDecoder`: `NOCP`/`NOCP_8_1` (`target/isa-decode/m-nocp.decode`), o espaço de
/// coprocessador ausente do perfil M. Cobre as 3 formas do arquivo real (forma 1 `hi∈{0xEE,0xFE}`,
/// forma 2 `hi∈{0xEC,0xED,0xFC,0xFD}`, `NOCP_8_1` `hi∈{0xEF,0xFF}`), o gate por
/// `ArmFeature#M_PROFILE`, e a substituição de `Thumb2CoprocessorDecoder` nos presets M-profile
/// (Armadilha 1 da spec: MCR/MRC de coprocessador genérico não existe em perfil M real).
class Thumb2NocpDecoderTest {
    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2NocpDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── Forma 1 (MCR/MRC clássico): bits[31:29]=111, bits[27:24]=1110, cp em bits[11:8] ────────

    @Test
    void form1DecodesUnderEitherTopNibble() {
        // top=0xE (1110): 0xEE + cp=5 em bits[11:8].
        DecodedInstruction e = tryDecode(ArmArchitecture.ARMV7M, 0xEE000500);
        assertEquals(InstructionKind.NOCP, e.kind());
        assertEquals(5, e.immediate(), "immediate carrega cp");

        // top=0xF (1111, ainda != NOCP_8_1 porque bits[27:24]=1110, não 1111): 0xFE + cp=9.
        DecodedInstruction f = tryDecode(ArmArchitecture.ARMV7M, 0xFE000900);
        assertEquals(InstructionKind.NOCP, f.kind());
        assertEquals(9, f.immediate());
    }

    // ── Forma 2 (extension register load/store, coprocessador 64 bits): bits[27:25]=110 ────────

    @Test
    void form2DecodesForAllFourTopByteCombinations() {
        // nibble[27:24] = 0xC (110-, bit24=0), top = 0xE ou 0xF.
        assertEquals(InstructionKind.NOCP, tryDecode(ArmArchitecture.ARMV7M, 0xEC000300).kind());
        assertEquals(InstructionKind.NOCP, tryDecode(ArmArchitecture.ARMV7M, 0xFC000300).kind());
        // nibble[27:24] = 0xD (110-, bit24=1), top = 0xE ou 0xF.
        DecodedInstruction d = tryDecode(ArmArchitecture.ARMV7M, 0xED000700);
        assertEquals(InstructionKind.NOCP, d.kind());
        assertEquals(7, d.immediate());
        assertEquals(InstructionKind.NOCP, tryDecode(ArmArchitecture.ARMV7M, 0xFD000700).kind());
    }

    // ── NOCP_8_1 (ARMv8.1-M): bits[27:24]=1111 fixo, cp=10 fixo (não extraído dos bits) ─────────

    @Test
    void nocp81FixesCoprocessorFieldToTenRegardlessOfBits() {
        DecodedInstruction e = tryDecode(ArmArchitecture.ARMV7M, 0xEF00_1234);
        assertEquals(InstructionKind.NOCP, e.kind());
        assertEquals(10, e.immediate(), "NOCP_8_1 sempre reporta cp=10 (fixo na gramática real)");

        DecodedInstruction f = tryDecode(ArmArchitecture.ARMV7M, 0xFF00_0000);
        assertEquals(InstructionKind.NOCP, f.kind());
        assertEquals(10, f.immediate());
    }

    // ── VLLDM_VLSTM/VSCCLRM (B15.5): vivem DENTRO do espaço da forma 2, mas o QEMU real prioriza
    // esses padrões específicos antes do NOCP genérico — devem continuar `null` (UNIMPLEMENTED
    // honesto) até B15.5 implementá-los, nunca virar NOCP por engano (G8) ─────────────────────

    @Test
    void vlldmVlstmEncodingSpaceIsExcludedFromForm2() {
        // 1110 1100 001 l rn 0000 1010 op 000 0000, l=1,rn=0xA,op=1 -> 0xEC2A_0A80.
        int vlldmVlstm = 0xEC2A_0A80;
        assertNull(tryDecode(ArmArchitecture.ARMV7M, vlldmVlstm),
                "VLLDM/VLSTM não deve virar NOCP (a arquitetura real nunca gera NOCP para eles)");
    }

    @Test
    void vscclrmEncodingSpaceIsExcludedFromForm2() {
        // 1110 1100 1.01 1111 .... 1011 imm:7 0, bit22=0,rd=0000,imm7=0 -> 0xEC9F_0B00 (dupla).
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEC9F_0B00), "VSCCLRM dupla não deve virar NOCP");
        // 1110 1100 1.01 1111 .... 1010 imm:8, mesma base com bit11:8=1010 (simples).
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEC9F_0A00), "VSCCLRM simples não deve virar NOCP");
    }

    // ── Espaços vizinhos que NÃO são NOCP ────────────────────────────────────────────────────

    @Test
    void bitsOutsideTheThreeFormsDoNotDecode() {
        // bits[31:29] != 111 (top nibble 0xD = 1101): nenhuma das 3 formas bate.
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xDE000500));
        // bits[27:24] = 0xA (1010): nem forma 1 (1110), nem forma 2 (110-), nem NOCP_8_1 (1111).
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEA000500));
        // Tudo zero: bits[31:29] = 000, não bate.
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0x00000000));
    }

    // ── Gate: só sob ArmFeature#M_PROFILE ────────────────────────────────────────────────────

    @Test
    void doesNotDecodeUnderAProfilePresets() {
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xEE000500));
        assertNull(tryDecode(ArmArchitecture.ARMV6K_THUMB2, 0xEE000500));
    }

    // ── Armadilha 1: substitui Thumb2CoprocessorDecoder em ARMV7M/ARMV7M_PURE/ARMV6M — o mesmo
    // raw que decodificava MCR Thumb-2 antes desta task agora decodifica NOCP ────────────────

    @Test
    void mcrThumb2EncodingSpaceNowDecodesAsNocpUnderMProfilePresets() {
        // MCR p15,0,r1,c9,c1,0 Thumb-2 (mesmo raw de Thumb2CoprocessorDecoderTest): hi=0xEE,
        // opc1=0,L=0,crn=9,rt=1,cp=15,opc2=0,bit4=1,crm=1 -> 0xEE09_1F11.
        int mcrThumb2 = 0xEE09_1F11;
        for (ArmArchitecture arch : new ArmArchitecture[]{
                ArmArchitecture.ARMV7M, ArmArchitecture.ARMV7M_PURE, ArmArchitecture.ARMV6M}) {
            DecodedInstruction decoded = decodeThumb32(arch, mcrThumb2);
            assertEquals(InstructionKind.NOCP, decoded.kind(),
                    arch + ": espaço de MCR/MRC genérico deve virar NOCP (perfil M não tem coprocessador)");
        }
    }

    @Test
    void mcrThumb2StillDecodesAsCoprocessorUnderClassicThumb2Preset() {
        // G3: ARMV6K_THUMB2 (A-profile) não muda — Thumb2CoprocessorDecoderTest já cobre isto em
        // detalhe, este teste só confirma que a substituição não vazou para fora do perfil M.
        int mcrThumb2 = 0xEE09_1F11;
        assertEquals(InstructionKind.COPROCESSOR, decodeThumb32(ArmArchitecture.ARMV6K_THUMB2, mcrThumb2).kind());
    }

    private static DecodedInstruction decodeThumb32(ArmArchitecture architecture, int raw32) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }
}
