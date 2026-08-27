package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B10.6b/B10.6c: page-walk isolado de {@link Aarch64PrivilegedStage1TranslatingAddressSpace64}
/// (stage-1 pura dos regimes EL2/EL3), montado à mão sobre o MESMO layout de descritor de 64 bits
/// de {@link TranslatingAddressSpace64Test}/{@link Stage2TranslatingAddressSpace64Test} — só o
/// campo de permissão muda (`AP[2]`/`XN` único, sem `AP[1]`/`PXN`/`UXN`).
class Aarch64PrivilegedStage1TranslatingAddressSpace64Test {
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final long AP_READ_ONLY_BIT = 1L << 7;
    private static final long XN_BIT = 1L << 54;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;

    private static final long L0_BASE = 0x0000L;
    private static final long L1_BASE = 0x1000L;
    private static final long L2_BASE = 0x2000L;
    private static final long L3_BASE = 0x3000L;

    private static final long VA_RW = 0x0010_0000L;          // L1[0]→L2[0]→L3[0x100], leitura+escrita
    private static final long VA_READ_ONLY = 0x0011_0000L;   // L3[0x110], só leitura
    private static final long VA_XN = 0x0012_0000L;          // L3[0x120], execute-never
    private static final long VA_FAULT = 0x0000_1000L;       // L3[1], inválido

    private static final long PA_RW = 0x0030_0000L;
    private static final long PA_READ_ONLY = 0x0031_0000L;
    private static final long PA_XN = 0x0032_0000L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long leafDescriptor(long outputBase, boolean readOnly, boolean xn) {
        long d = (outputBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
        if (readOnly) {
            d |= AP_READ_ONLY_BIT;
        }
        if (xn) {
            d |= XN_BIT;
        }
        return d;
    }

    private static Aarch64PrivilegedStage1TranslatingAddressSpace64 newElx(AddressSpace64 physical) {
        physical.write64(L0_BASE, tableDescriptor(L1_BASE));
        physical.write64(L1_BASE, tableDescriptor(L2_BASE));
        physical.write64(L2_BASE, tableDescriptor(L3_BASE));
        physical.write64(L3_BASE + 0x100 * 8, leafDescriptor(PA_RW, false, false));
        physical.write64(L3_BASE + 0x110 * 8, leafDescriptor(PA_READ_ONLY, true, false));
        physical.write64(L3_BASE + 0x120 * 8, leafDescriptor(PA_XN, false, true));
        // L3[1] deixado inválido — falta de tradução.

        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx =
                new Aarch64PrivilegedStage1TranslatingAddressSpace64(physical);
        elx.setTtbr0(L0_BASE);
        return elx;
    }

    @Test
    void traduzLeituraEEscritaQuandoNaoSomenteLeitura() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx = newElx(physical);

        long offset = 0x42;
        assertEquals(PA_RW | offset, elx.translate(VA_RW | offset, MemoryAccessType.DATA_READ));
        assertEquals(PA_RW | offset, elx.translate(VA_RW | offset, MemoryAccessType.DATA_WRITE));
    }

    @Test
    void apReadOnlyBloqueiaEscritaMasNaoLeitura() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx = newElx(physical);

        assertEquals(PA_READ_ONLY, elx.translate(VA_READ_ONLY, MemoryAccessType.DATA_READ));
        assertThrows(MemoryTranslationException64.class,
                () -> elx.translate(VA_READ_ONLY, MemoryAccessType.DATA_WRITE));
    }

    @Test
    void xnBloqueiaFetchMasNaoLeituraOuEscrita() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx = newElx(physical);

        assertEquals(PA_XN, elx.translate(VA_XN, MemoryAccessType.DATA_READ));
        assertEquals(PA_XN, elx.translate(VA_XN, MemoryAccessType.DATA_WRITE));
        assertThrows(MemoryTranslationException64.class,
                () -> elx.translate(VA_XN, MemoryAccessType.INSTRUCTION_FETCH));
    }

    @Test
    void faltaDeDescritorValidoPropagaFaultStatus() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx = newElx(physical);

        MemoryTranslationException64 ex = assertThrows(MemoryTranslationException64.class,
                () -> elx.translate(VA_FAULT, MemoryAccessType.DATA_READ));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L3, ex.faultStatus());
        assertEquals(false, ex.isStage2(), "não é uma falha de stage-2 — regime EL2/EL3 é stage-1 pura");
    }

    @Test
    void semMicroTlbCadaChamadaRefazOWalk() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        Aarch64PrivilegedStage1TranslatingAddressSpace64 elx = newElx(physical);

        elx.translate(VA_RW, MemoryAccessType.DATA_READ);
        elx.translate(VA_RW, MemoryAccessType.DATA_READ);

        assertEquals(2L, elx.pageWalkCount(), "AT não deve ter efeito colateral em nenhuma TLB");
    }
}
