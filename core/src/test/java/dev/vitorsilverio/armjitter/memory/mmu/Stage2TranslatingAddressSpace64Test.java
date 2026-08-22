package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B10.8: page-walk de stage-2 (IPA→PA), montado à mão sobre o mesmo layout de descritor de 64
/// bits de {@link TranslatingAddressSpace64Test} — só o campo de permissão muda (`S2AP`/`XN` em
/// vez de `AP`/`PXN`/`UXN`).
class Stage2TranslatingAddressSpace64Test {
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final int S2AP_SHIFT = 6;
    private static final long XN_BIT = 1L << 54;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;

    private static final int S2AP_NONE = 0b00;
    private static final int S2AP_READ_ONLY = 0b01;
    private static final int S2AP_READ_WRITE = 0b11;

    private static final long L0_BASE = 0x0000L;
    private static final long L1_BASE = 0x1000L;
    private static final long L2_BASE = 0x2000L;
    private static final long L3_BASE = 0x3000L;

    private static final long IPA_RW = 0x0010_0000L;         // L1[0]→L2[0]→L3[0x100], leitura+escrita
    private static final long IPA_READ_ONLY = 0x0011_0000L;  // L3[0x110], só leitura
    private static final long IPA_NO_ACCESS = 0x0012_0000L;  // L3[0x120], sem acesso nenhum
    private static final long IPA_XN = 0x0013_0000L;         // L3[0x130], execute-never
    private static final long IPA_FAULT = 0x0000_1000L;      // L3[1], inválido

    private static final long PA_RW = 0x0030_0000L;
    private static final long PA_READ_ONLY = 0x0031_0000L;
    private static final long PA_NO_ACCESS = 0x0032_0000L;
    private static final long PA_XN = 0x0033_0000L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long pageDescriptor(long outputBase, int s2ap, boolean xn) {
        long d = (outputBase & OUTPUT_ADDRESS_MASK) | ((long) s2ap << S2AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
        return xn ? d | XN_BIT : d;
    }

    private static Stage2TranslatingAddressSpace64 newStage2(AddressSpace64 physical) {
        physical.write64(L0_BASE, tableDescriptor(L1_BASE));
        physical.write64(L1_BASE, tableDescriptor(L2_BASE));
        physical.write64(L2_BASE, tableDescriptor(L3_BASE));
        physical.write64(L3_BASE + 0x100 * 8, pageDescriptor(PA_RW, S2AP_READ_WRITE, false));
        physical.write64(L3_BASE + 0x110 * 8, pageDescriptor(PA_READ_ONLY, S2AP_READ_ONLY, false));
        physical.write64(L3_BASE + 0x120 * 8, pageDescriptor(PA_NO_ACCESS, S2AP_NONE, false));
        physical.write64(L3_BASE + 0x130 * 8, pageDescriptor(PA_XN, S2AP_READ_WRITE, true));
        // L3[1] deixado inválido — falta de tradução.

        Stage2TranslatingAddressSpace64 stage2 = new Stage2TranslatingAddressSpace64(physical);
        stage2.setVttbr(L0_BASE);
        return stage2;
    }

    @Test
    void traduzLeituraEEscritaQuandoS2apPermiteOsDois() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);

        long offset = 0x42;
        assertEquals(PA_RW | offset, stage2.translate(IPA_RW | offset, MemoryAccessType.DATA_READ));
        assertEquals(PA_RW | offset, stage2.translate(IPA_RW | offset, MemoryAccessType.DATA_WRITE));
    }

    @Test
    void s2apSomenteLeituraBloqueiaEscrita() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);

        assertEquals(PA_READ_ONLY, stage2.translate(IPA_READ_ONLY, MemoryAccessType.DATA_READ));

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> stage2.translate(IPA_READ_ONLY, MemoryAccessType.DATA_WRITE));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, fault.faultStatus());
        assertTrue(fault.isStage2(), "falha originada em Stage2TranslatingAddressSpace64 deve marcar isStage2()");
    }

    @Test
    void s2apSemAcessoBloqueiaLeituraEEscrita() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);

        assertThrows(MemoryTranslationException64.class,
                () -> stage2.translate(IPA_NO_ACCESS, MemoryAccessType.DATA_READ));
        assertThrows(MemoryTranslationException64.class,
                () -> stage2.translate(IPA_NO_ACCESS, MemoryAccessType.DATA_WRITE));
    }

    @Test
    void xnBloqueiaFetchMasNaoLeituraDeDados() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);

        assertEquals(PA_XN, stage2.translate(IPA_XN, MemoryAccessType.DATA_READ));

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> stage2.translate(IPA_XN, MemoryAccessType.INSTRUCTION_FETCH));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, fault.faultStatus());
    }

    @Test
    void faltaDeTraducaoPropagaFaultStatusMarcadoComoStage2() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> stage2.translate(IPA_FAULT, MemoryAccessType.DATA_READ));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L3, fault.faultStatus());
        assertTrue(fault.isStage2());
    }

    @Test
    void pageWalkCountIncrementaACadaChamadaSemTlb() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical);
        long before = stage2.pageWalkCount();

        stage2.translate(IPA_RW, MemoryAccessType.DATA_READ);
        stage2.translate(IPA_RW, MemoryAccessType.DATA_READ);

        assertEquals(before + 2, stage2.pageWalkCount(), "sem micro-TLB (B10.8): cada chamada refaz o walk");
    }
}
