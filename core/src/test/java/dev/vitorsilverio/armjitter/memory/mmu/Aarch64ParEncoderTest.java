package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// B10.6: layout de bits de `PAR_EL1` (`ARM DDI 0487 D19.2.97`, formato de 64 bits).
class Aarch64ParEncoderTest {
    private static final long F_BIT = 1L;
    private static final int FST_SHIFT = 1;
    private static final long FST_MASK = 0b11_1111L;
    private static final long PA_MASK = 0x0000_FFFF_FFFF_F000L;

    @Test
    void successCarregaPaNosBitsCorretosComFZero() {
        long par = Aarch64ParEncoder.success(0x0000_0012_3456_7000L);

        assertEquals(0, par & F_BIT, "F deve ser 0 em sucesso");
        assertEquals(0x0000_0012_3456_7000L, par & PA_MASK, "PA deve ocupar [47:12], mesmos bits do endereço");
    }

    @Test
    void successDescartaOffsetDaPaginaEBitsForaDeAlcance() {
        // O offset dentro da página (bits [11:0]) e qualquer bit fora de [47:12] não fazem parte
        // do campo PA de PAR_EL1 — devem ser mascarados, não vazar para outros campos.
        long par = Aarch64ParEncoder.success(0xFFFF_0012_3456_7FFFL);

        assertEquals(0x0000_0012_3456_7000L, par);
    }

    @Test
    void faultCarregaFstDeTranslationFaultComFUm() {
        long par = Aarch64ParEncoder.fault(FaultStatus64.TRANSLATION_FAULT_L2);

        assertEquals(1, par & F_BIT, "F deve ser 1 em falha");
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L2.code(), (par >>> FST_SHIFT) & FST_MASK);
    }

    @Test
    void faultCarregaFstDePermissionFaultComFUm() {
        long par = Aarch64ParEncoder.fault(FaultStatus64.PERMISSION_FAULT_L3);

        assertEquals(1, par & F_BIT);
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3.code(), (par >>> FST_SHIFT) & FST_MASK);
    }
}
