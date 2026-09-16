package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.2 — `Thumb2MvePredicationDecoder`: `VPST`/`VPNOT`/`VPSEL` (`target/isa-decode/mve.decode`,
/// linhas 732-739). Raws construídos a partir das máscaras/valores reais do arquivo (ver Javadoc
/// da classe) — não reproduzidos de um assembler externo (toolchain MVE não confirmada nesta
/// sessão, ver `## Resultado` da task).
class Thumb2MvePredicationDecoderTest {
    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MvePredicationDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── VPNOT: totalmente fixo ─────────────────────────────────────────────────────────────────

    @Test
    void vpnotDecodesWithNoSignificantFields() {
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xFE31_0F4D);
        assertEquals(InstructionKind.VPNOT, decoded.kind());
        assertEquals(InstructionSet.THUMB, decoded.instructionSet());
    }

    @Test
    void vpnotDoesNotDecodeWithoutMveInteger() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xFE31_0F4D));
    }

    // ── VPST: mask=%mask_22_13 (bit22 ++ bits[15:13]) ─────────────────────────────────────────────

    @Test
    void vpstDecodesTheFourBitMaskField() {
        // mask=0b1010: bit22=1 (high), bits[15:13]=010 (low3).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xFE71_4F4D);
        assertEquals(InstructionKind.VPST, decoded.kind());
        assertEquals(0b1010, decoded.immediate());
    }

    @Test
    void vpstWithZeroMaskIsNeverReachedBecauseVpnotMatchesFirst() {
        // mask=0 é EXATAMENTE o encoding de VPNOT (achado da spec) — o decoder checa VPNOT
        // primeiro, então este raw nunca produz VPST.
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xFE31_0F4D);
        assertEquals(InstructionKind.VPNOT, decoded.kind());
    }

    @Test
    void vpstDoesNotDecodeWithoutMveInteger() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xFE71_4F4D));
    }

    // ── VPSEL: qd=%qd 22:1 13:3, qn=%qn 7:1 17:3, qm=%qm 5:1 1:3 ─────────────────────────────────

    @Test
    void vpselDecodesQdQnQmFields() {
        // qd=3 (bit22=0,bits15-13=011), qn=2 (bit7=0,bits19-17=010), qm=1 (bit5=0,bits3-1=001).
        DecodedInstruction decoded = tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xFE35_6F03);
        assertEquals(InstructionKind.VPSEL, decoded.kind());
        assertEquals(3, decoded.destinationRegister(), "qd");
        assertEquals(2, decoded.sourceRegister(), "qn");
        assertEquals(1, decoded.secondSourceRegister(), "qm");
    }

    @Test
    void vpselRejectsQuadRegisterOutsideQ0ToQ7() {
        // qd=8 (bit22=1, bits15-13=000): fora do banco MVE real (Q0-Q7) -> null, G8 (cai para
        // Thumb2NocpDecoder, que recusa nomeadamente em vez de decodificar um registrador errado).
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xFE71_0F01));
    }

    @Test
    void vpselDoesNotDecodeWithoutMveInteger() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, 0xFE35_6F03));
    }

    // ── Espaços vizinhos que não são nenhum dos 3 padrões ─────────────────────────────────────────

    @Test
    void unrelatedEncodingsDoNotDecode() {
        // NOCP forma 1 clássica (MCR/MRC): não deve ser capturado por este decoder.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, 0xEE09_1F11));
    }

    // ── Pipeline completo: nenhuma das 3 vira NOCP sob ARMV8_1M_MVE (Armadilha 1/Aceite) ─────────

    @Test
    void noneOfTheThreeDecodeAsNocpThroughTheFullThumbPipeline() {
        assertEquals(InstructionKind.VPNOT, decodeThumb32(0xFE31_0F4D).kind());
        assertEquals(InstructionKind.VPST, decodeThumb32(0xFE71_4F4D).kind());
        assertEquals(InstructionKind.VPSEL, decodeThumb32(0xFE35_6F03).kind());
    }

    private static DecodedInstruction decodeThumb32(int raw32) {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, raw32 >>> 16);
        memory.put16(2, raw32 & 0xFFFF);
        return new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
    }
}
