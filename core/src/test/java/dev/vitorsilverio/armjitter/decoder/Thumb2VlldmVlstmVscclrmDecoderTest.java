package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import org.junit.jupiter.api.Test;

/// B15.5 — `Thumb2VlldmVlstmVscclrmDecoder`: `VLLDM`/`VLSTM` (sempre `UNDEFINED`, sem FPU real) +
/// `VSCCLRM` (zera um intervalo de registradores FP). Vetores reproduzidos dos MESMOS raws que
/// `Thumb2NocpDecoderTest` já usa para confirmar a exclusão do espaço genérico `NOCP`.
class Thumb2VlldmVlstmVscclrmDecoderTest {
    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2VlldmVlstmVscclrmDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── VLLDM_VLSTM: sempre UNDEFINED, independente de Rn/l/op ──────────────────────────────────

    @Test
    void vlldmVlstmDecodesAsUndefinedKind() {
        // Mesmo raw de Thumb2NocpDecoderTest#vlldmVlstmEncodingSpaceIsExcludedFromForm2.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xEC2A_0A80);
        assertEquals(InstructionKind.VLLDM_VLSTM, decoded.kind());
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    @Test
    void vlldmVlstmDoesNotDecodeOutsideMProfile() {
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xEC2A_0A80));
    }

    // ── VSCCLRM dupla (size=3): D<vd>..D<vd+imm-1> ──────────────────────────────────────────────

    @Test
    void vscclrmDoubleDecodesZeroRegisterRangeAtBaseEncoding() {
        // Mesmo raw de Thumb2NocpDecoderTest#vscclrmEncodingSpaceIsExcludedFromForm2 (dupla):
        // D=0, Vd=0, imm7=0 -> vd=0, count=0, last=-1 (intervalo vazio).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xEC9F_0B00);
        assertEquals(InstructionKind.VSCCLRM, decoded.kind());
        assertTrue(decoded.link(), "size=3 (dupla) -> link=true");
        assertEquals(0, decoded.destinationRegister(), "primeiro registrador (vd)");
        assertEquals(-1, decoded.immediate(), "último registrador (vd+imm-1, imm=0 -> intervalo vazio)");
    }

    @Test
    void vscclrmDoubleDecodesNonTrivialRegisterRange() {
        // D=1, Vd=3 (vd_dp = D:Vd = 0b10011 = 19), imm7=2 (bits[7:1]) -> últimos = 19+2-1 = 20.
        // bits[23:20]=1001(D=1 no bit22 -> nibble=1101? recomputar): base 0xEC9F0B00 tem D=0,Vd=0,imm=0.
        // Construir a partir da base: D em bit22 (0x0040_0000), Vd em bits[15:12], imm7 em bits[7:1].
        int raw = 0xEC9F_0B00 | (1 << 22) | (3 << 12) | (2 << 1);
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, raw);
        assertEquals(InstructionKind.VSCCLRM, decoded.kind());
        assertTrue(decoded.link());
        assertEquals(19, decoded.destinationRegister(), "vd_dp = D:Vd = (1<<4)|3 = 19");
        assertEquals(20, decoded.immediate(), "vd+imm-1 = 19+2-1 = 20");
    }

    // ── VSCCLRM simples (size=2): S<vd>..S<vd+imm-1> ────────────────────────────────────────────

    @Test
    void vscclrmSingleDecodesZeroRegisterRangeAtBaseEncoding() {
        // Mesmo raw de Thumb2NocpDecoderTest#vscclrmEncodingSpaceIsExcludedFromForm2 (simples).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, 0xEC9F_0A00);
        assertEquals(InstructionKind.VSCCLRM, decoded.kind());
        assertFalse(decoded.link(), "size=2 (simples) -> link=false");
        assertEquals(0, decoded.destinationRegister());
        assertEquals(-1, decoded.immediate());
    }

    @Test
    void vscclrmSingleDecodesNonTrivialRegisterRange() {
        // D=1, Vd=3 (vd_sp = Vd:D = (3<<1)|1 = 7), imm8=4 (bits[7:0]) -> último = 7+4-1 = 10.
        int raw = 0xEC9F_0A00 | (1 << 22) | (3 << 12) | 4;
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV7M, raw);
        assertEquals(InstructionKind.VSCCLRM, decoded.kind());
        assertFalse(decoded.link());
        assertEquals(7, decoded.destinationRegister(), "vd_sp = Vd:D = (3<<1)|1 = 7");
        assertEquals(10, decoded.immediate(), "vd+imm-1 = 7+4-1 = 10");
    }

    @Test
    void vscclrmDoesNotDecodeOutsideMProfile() {
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xEC9F_0B00));
        assertNull(tryDecode(ArmArchitecture.ARMV7A, 0xEC9F_0A00));
    }

    // ── Espaços vizinhos que não são nenhuma das duas formas ────────────────────────────────────

    @Test
    void unrelatedEncodingsDoNotDecode() {
        // NOCP forma 1 clássica (MCR/MRC): não deve ser capturado por este decoder.
        assertNull(tryDecode(ArmArchitecture.ARMV7M, 0xEE09_1F11));
    }
}
