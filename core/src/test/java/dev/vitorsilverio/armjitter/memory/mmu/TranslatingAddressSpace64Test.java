package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B6.6.2: page tables VMSA64 (granule 4KiB, VA 48 bits, 4 níveis L0-L3) montadas à mão (sem
/// binário/kernel real) cobrindo tradução identidade, remapeamento, bloco L1 (1GiB)/L2 (2MiB),
/// página L3 (4KiB), permissão `AP` (EL0 negado) e `PXN`/`UXN`, faltas de tradução em cada um dos
/// 4 níveis, HIT de micro-TLB não refazendo o page-walk e `translationGeneration`.
///
/// ### Layout físico (mesmo `TestAddressSpace` de 16MiB para tabelas e dados)
/// - `0x0000`: tabela L0 (índice 0 = tabela L1; índice 1 deixado inválido = falta L0).
/// - `0x1000`: tabela L1 (índice 0 = tabela L2; índice 1 = BLOCO de 1GiB; índice 2 inválido).
/// - `0x2000`: tabela L2, sob L1[0] (índice 0 = tabela L3; índice 1 = BLOCO de 2MiB; índice 2
///   inválido).
/// - `0x3000`: tabela L3, sob L1[0]→L2[0] (páginas de 4KiB — índice 1 deixado inválido = falta
///   L3; demais índices ver os testes).
class TranslatingAddressSpace64Test {
    // Bits do descritor VMSA64 (ARM DDI 0487 D8.3), replicados aqui de forma independente da
    // implementação para o teste não validar contra si mesmo.
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L; // bit1: bloco(0) vs tabela/página(1)
    private static final int AP_SHIFT = 6;
    private static final long PXN_BIT = 1L << 53;
    private static final long UXN_BIT = 1L << 54;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L; // bits [47:12]

    private static final int AP_FULL_ACCESS = 0b01;   // EL0 permitido, leitura/escrita
    private static final int AP_EL1_ONLY = 0b00;       // EL0 sem acesso nenhum

    private static final long L0_BASE = 0x0000L;
    private static final long L1_BASE = 0x1000L;
    private static final long L2_BASE = 0x2000L;
    private static final long L3_BASE = 0x3000L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long blockDescriptor(long outputBase, int ap) {
        return (outputBase & OUTPUT_ADDRESS_MASK) | ((long) ap << AP_SHIFT) | DESC_VALID;
    }

    private static long pageDescriptor(long outputBase, int ap, boolean pxn, boolean uxn) {
        long d = (outputBase & OUTPUT_ADDRESS_MASK) | ((long) ap << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
        if (pxn) {
            d |= PXN_BIT;
        }
        if (uxn) {
            d |= UXN_BIT;
        }
        return d;
    }

    // VAs usados pelos testes (comentado o caminho L0→L1→L2→L3 de cada um).
    private static final long VA_IDENTITY = 0x0010_0000L;      // L1[0]→L2[0]→L3[0x100]
    private static final long VA_REMAP = 0x0011_0000L;         // L1[0]→L2[0]→L3[0x110]
    private static final long VA_EL1_ONLY = 0x0012_0000L;      // L1[0]→L2[0]→L3[0x120]
    private static final long VA_PXN = 0x0014_0000L;           // L1[0]→L2[0]→L3[0x140]
    private static final long VA_UXN = 0x0015_0000L;           // L1[0]→L2[0]→L3[0x150]
    private static final long VA_L3_FAULT = 0x0000_1000L;      // L1[0]→L2[0]→L3[1] (inválido)
    private static final long VA_L2_FAULT = 0x0040_0000L;      // L1[0]→L2[2] (inválido)
    private static final long VA_L1_FAULT = 2L << 30;  // 0x8000_0000: L0[0]→L1[2] (inválido)
    private static final long VA_L0_FAULT = 1L << 39;  // 0x80_0000_0000: L0[1] (inválido)
    private static final long VA_L1_BLOCK = 0x4000_0000L | 0x0009_0000L; // L0[0]→L1[1] (bloco 1GiB)
    private static final long VA_L2_BLOCK = 0x0020_0000L | 0x0000_1000L; // L1[0]→L2[1] (bloco 2MiB)

    private static final long PA_IDENTITY = 0x0010_0000L;
    private static final long PA_REMAP_TARGET = 0x0030_0000L;
    private static final long PA_EL1_ONLY = 0x0032_0000L;
    private static final long PA_PXN = 0x0034_0000L;
    private static final long PA_UXN = 0x0035_0000L;
    private static final long PA_L1_BLOCK_BASE = 0L; // base 0: evita alocar 1GiB no TestAddressSpace
    private static final long PA_L2_BLOCK_BASE = 0x0080_0000L; // alinhado a 2MiB

    private static AddressSpace64 newPhysical() {
        return AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000)); // 16MiB
    }

    private static TranslatingAddressSpace64 newMmu(AddressSpace64 physical) {
        physical.write64(L0_BASE, tableDescriptor(L1_BASE));
        // L0[1] deixado inválido (0) — falta de tradução no nível 0.

        physical.write64(L1_BASE, tableDescriptor(L2_BASE));
        physical.write64(L1_BASE + 1 * 8, blockDescriptor(PA_L1_BLOCK_BASE, AP_FULL_ACCESS));
        // L1[2] deixado inválido — falta de tradução no nível 1.

        physical.write64(L2_BASE, tableDescriptor(L3_BASE));
        physical.write64(L2_BASE + 1 * 8, blockDescriptor(PA_L2_BLOCK_BASE, AP_FULL_ACCESS));
        // L2[2] deixado inválido — falta de tradução no nível 2.

        physical.write64(L3_BASE + 0x100 * 8, pageDescriptor(PA_IDENTITY, AP_FULL_ACCESS, false, false));
        physical.write64(L3_BASE + 0x110 * 8, pageDescriptor(PA_REMAP_TARGET, AP_FULL_ACCESS, false, false));
        physical.write64(L3_BASE + 0x120 * 8, pageDescriptor(PA_EL1_ONLY, AP_EL1_ONLY, false, false));
        physical.write64(L3_BASE + 0x140 * 8, pageDescriptor(PA_PXN, AP_FULL_ACCESS, true, false));
        physical.write64(L3_BASE + 0x150 * 8, pageDescriptor(PA_UXN, AP_FULL_ACCESS, false, true));
        // L3[1] deixado inválido — falta de tradução no nível 3.

        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0_BASE);
        mmu.setPrivileged(true);
        return mmu;
    }

    @Test
    void traducaoIdentidade() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.write32(VA_IDENTITY, 0x1234_5678);

        assertEquals(0x1234_5678, mmu.read32(VA_IDENTITY));
        assertEquals(0x1234_5678, physical.read32(PA_IDENTITY));
    }

    @Test
    void remapeamento() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.write32(VA_REMAP, 0xCAFE_BABE);

        assertEquals(0xCAFE_BABE, physical.read32(PA_REMAP_TARGET));
        assertEquals(0, physical.read32(VA_REMAP));
        assertEquals(0xCAFE_BABE, mmu.read32(VA_REMAP));
    }

    @Test
    void blocoL1DeUmGigaResolveSemDescerNiveis() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.write32(VA_L1_BLOCK, 0x1111_2222);

        long expectedPhysical = PA_L1_BLOCK_BASE | (VA_L1_BLOCK & ((1L << 30) - 1));
        assertEquals(0x1111_2222, physical.read32(expectedPhysical));
        assertEquals(0x1111_2222, mmu.read32(VA_L1_BLOCK));
    }

    @Test
    void blocoL2DeDoisMegaResolveSemDescerNiveis() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.write32(VA_L2_BLOCK, 0x3333_4444);

        long expectedPhysical = PA_L2_BLOCK_BASE | (VA_L2_BLOCK & ((1L << 21) - 1));
        assertEquals(0x3333_4444, physical.read32(expectedPhysical));
        assertEquals(0x3333_4444, mmu.read32(VA_L2_BLOCK));
    }

    @Test
    void paginaL3DeQuatroKibCaminhoCompletoPelosQuatroNiveis() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.write32(VA_IDENTITY, 0x5555_6666);

        assertEquals(0x5555_6666, physical.read32(PA_IDENTITY));
        assertEquals(0x5555_6666, mmu.read32(VA_IDENTITY));
    }

    @Test
    void apSemAcessoEl0BloqueiaUsuarioMasPermitePrivilegiado() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        mmu.setPrivileged(true);
        mmu.write32(VA_EL1_ONLY, 0x7777_8888);
        assertEquals(0x7777_8888, mmu.read32(VA_EL1_ONLY));

        mmu.setPrivileged(false);
        MemoryTranslationException64 ex =
                assertThrows(MemoryTranslationException64.class, () -> mmu.read32(VA_EL1_ONLY));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, ex.faultStatus());
        assertEquals(VA_EL1_ONLY, ex.virtualAddress());
    }

    @Test
    void pxnBloqueiaFetchPrivilegiadoMasNaoLeituraDeDados() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        mmu.setPrivileged(true);

        // Leitura de dados não é afetada por PXN.
        assertEquals(0, mmu.read32(VA_PXN));

        MemoryTranslationException64 ex =
                assertThrows(MemoryTranslationException64.class, () -> mmu.fetch32(VA_PXN));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, ex.faultStatus());
        assertEquals(MemoryAccessType.INSTRUCTION_FETCH, ex.accessType());
    }

    @Test
    void uxnBloqueiaFetchDeUsuarioMasNaoDePrivilegiado() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.setPrivileged(true);
        mmu.fetch32(VA_UXN); // não deve lançar em modo privilegiado

        mmu.setPrivileged(false);
        MemoryTranslationException64 ex =
                assertThrows(MemoryTranslationException64.class, () -> mmu.fetch32(VA_UXN));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, ex.faultStatus());
    }

    @Test
    void tlbHitNaoRefazPageWalk() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        assertEquals(0, mmu.pageWalkCount());

        mmu.write32(VA_IDENTITY, 1);
        long afterFirstWrite = mmu.pageWalkCount();
        assertEquals(1, afterFirstWrite, "primeiro acesso à página deve andar as tabelas exatamente uma vez");

        mmu.read32(VA_IDENTITY);
        mmu.write32(VA_IDENTITY + 4, 2);
        mmu.read32(VA_IDENTITY + 0xFFC);
        assertEquals(afterFirstWrite, mmu.pageWalkCount(), "HIT de TLB não deve reler as tabelas");

        mmu.read32(VA_REMAP);
        assertEquals(afterFirstWrite + 1, mmu.pageWalkCount());
    }

    @Test
    void invalidateTlbAllForcaNovoWalk() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.read32(VA_IDENTITY);
        long afterFirst = mmu.pageWalkCount();

        mmu.read32(VA_IDENTITY);
        assertEquals(afterFirst, mmu.pageWalkCount(), "HIT antes de invalidar");

        mmu.invalidateTlbAll();
        mmu.read32(VA_IDENTITY);
        assertEquals(afterFirst + 1, mmu.pageWalkCount(), "invalidateTlbAll deve forçar novo walk");
    }

    @Test
    void invalidateTlbByMvaSoAfetaAPaginaAlvo() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        mmu.read32(VA_IDENTITY);
        mmu.read32(VA_REMAP);
        long afterBoth = mmu.pageWalkCount();

        mmu.invalidateTlbByMva(VA_IDENTITY);
        mmu.read32(VA_REMAP); // continua HIT
        assertEquals(afterBoth, mmu.pageWalkCount());

        mmu.read32(VA_IDENTITY); // MISS: foi invalidada
        assertEquals(afterBoth + 1, mmu.pageWalkCount());
    }

    @Test
    void faltaDeTraducaoEmCadaNivel() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0,
                assertThrows(MemoryTranslationException64.class, () -> mmu.read32(VA_L0_FAULT)).faultStatus());
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L1,
                assertThrows(MemoryTranslationException64.class, () -> mmu.read32(VA_L1_FAULT)).faultStatus());
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L2,
                assertThrows(MemoryTranslationException64.class, () -> mmu.read32(VA_L2_FAULT)).faultStatus());
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L3,
                assertThrows(MemoryTranslationException64.class, () -> mmu.read32(VA_L3_FAULT)).faultStatus());
    }

    // ── B10.6: translateForAddressTranslate (AT S1E1R/S1E1W/S1E0R/S1E0W) ────────────────────

    @Test
    void translateForAddressTranslateDevolvePaSemMexerNaTlb() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        long beforeAt = mmu.pageWalkCount();

        long pa = mmu.translateForAddressTranslate(VA_IDENTITY, MemoryAccessType.DATA_READ, false);

        assertEquals(PA_IDENTITY, pa);
        assertEquals(beforeAt + 1, mmu.pageWalkCount());

        // Segunda chamada idêntica: se tivesse preenchido a TLB, seria HIT (sem novo walk). AT
        // nunca deve ficar residente na TLB — cada chamada refaz o walk.
        mmu.translateForAddressTranslate(VA_IDENTITY, MemoryAccessType.DATA_READ, false);
        assertEquals(beforeAt + 2, mmu.pageWalkCount());
    }

    @Test
    void translateForAddressTranslateFalhaDeTraducaoPropagaFaultStatus() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> mmu.translateForAddressTranslate(VA_L3_FAULT, MemoryAccessType.DATA_READ, false));

        assertEquals(FaultStatus64.TRANSLATION_FAULT_L3, fault.faultStatus());
    }

    @Test
    void translateForAddressTranslateUnprivilegedChecaComoEl0IndependenteDoPrivilegedReal() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        mmu.setPrivileged(true); // como um kernel real em EL1 perguntando "isso seria acessível por EL0?"

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> mmu.translateForAddressTranslate(VA_EL1_ONLY, MemoryAccessType.DATA_READ, true));

        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, fault.faultStatus());
        // S1E1* (unprivileged=false) na MESMA página deve continuar permitido: privileged real
        // não foi alterado permanentemente pela chamada anterior (finally restaura).
        assertEquals(PA_EL1_ONLY,
                mmu.translateForAddressTranslate(VA_EL1_ONLY, MemoryAccessType.DATA_READ, false));
    }

    @Test
    void translationGenerationIncrementaSoEmSetTtbr0EInvalidateTlbAll() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        int afterSetup = mmu.translationGeneration();

        mmu.setTcr(0x1234L);
        mmu.setMair(0x5678L);
        assertEquals(afterSetup, mmu.translationGeneration(), "setTcr/setMair não devem incrementar a geração");

        mmu.invalidateTlbAll();
        assertEquals(afterSetup + 1, mmu.translationGeneration());

        mmu.setTtbr0(L0_BASE);
        assertEquals(afterSetup + 2, mmu.translationGeneration());
    }

    // ── B10.8: translateForAddressTranslateStage12 (AT S12E1R/S12E1W/S12E0R/S12E0W) ──────────
    // Tabela de stage-2 própria, num pedaço do MESMO físico não usado pelas tabelas/páginas de
    // stage-1 acima (offsets >= 0x0090_0000).
    private static final long S2_L0_BASE = 0x0090_0000L;
    private static final long S2_L1_BASE = 0x0091_0000L;
    private static final long S2_L2_BASE = 0x0092_0000L;
    private static final long S2_L3_BASE = 0x0093_0000L;
    private static final int S2AP_READ_WRITE = 0b11;
    private static final int S2AP_NONE = 0b00;

    private static long s2TableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long s2PageDescriptor(long outputBase, int s2ap) {
        return (outputBase & OUTPUT_ADDRESS_MASK) | ((long) s2ap << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    /// Monta uma stage-2 cujo mapeamento identidade cobre `PA_IDENTITY`/`PA_EL1_ONLY` (os IPAs que
    /// a stage-1 de {@link #newMmu} produz para `VA_IDENTITY`/`VA_EL1_ONLY`) — index `0x100`/`0x120`
    /// da L3 de stage-2, mesmo esquema de índice usado por {@link #newMmu}.
    private static Stage2TranslatingAddressSpace64 newStage2(AddressSpace64 physical, long finalPaForIdentity) {
        physical.write64(S2_L0_BASE, s2TableDescriptor(S2_L1_BASE));
        physical.write64(S2_L1_BASE, s2TableDescriptor(S2_L2_BASE));
        physical.write64(S2_L2_BASE, s2TableDescriptor(S2_L3_BASE)); // L2[0]: cobre PA_IDENTITY (IPA < 2MiB)
        physical.write64(S2_L2_BASE + 1 * 8, s2TableDescriptor(S2_L3_BASE)); // L2[1]: cobre PA_EL1_ONLY (IPA no 2º bloco de 2MiB)
        physical.write64(S2_L3_BASE + 0x100 * 8, s2PageDescriptor(finalPaForIdentity, S2AP_READ_WRITE));
        physical.write64(S2_L3_BASE + 0x120 * 8, s2PageDescriptor(finalPaForIdentity, S2AP_NONE));
        // demais índices inválidos — falta de tradução de stage-2.

        Stage2TranslatingAddressSpace64 stage2 = new Stage2TranslatingAddressSpace64(physical);
        stage2.setVttbr(S2_L0_BASE);
        return stage2;
    }

    @Test
    void translateForAddressTranslateStage12ComStage2HabilitadaPassaPelasDuasEtapas() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        long finalPa = 0x00A0_0000L;
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical, finalPa);

        long pa = mmu.translateForAddressTranslateStage12(VA_IDENTITY, MemoryAccessType.DATA_READ, false, stage2);

        // VA_IDENTITY → (stage-1) PA_IDENTITY, que aqui faz o papel de IPA → (stage-2) finalPa.
        assertEquals(finalPa, pa);
    }

    @Test
    void translateForAddressTranslateStage12FalhaDeStage1NaoMarcaIsStage2() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical, 0x00A0_0000L);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> mmu.translateForAddressTranslateStage12(VA_L3_FAULT, MemoryAccessType.DATA_READ, false,
                        stage2));

        assertEquals(FaultStatus64.TRANSLATION_FAULT_L3, fault.faultStatus());
        assertFalse(fault.isStage2(), "falha na stage-1 (page-walk de VA→IPA) não é uma falha de stage-2");
    }

    @Test
    void translateForAddressTranslateStage12FalhaDeStage2MarcaIsStage2() {
        AddressSpace64 physical = newPhysical();
        TranslatingAddressSpace64 mmu = newMmu(physical);
        // VA_EL1_ONLY: stage-1 produz IPA=PA_EL1_ONLY, mapeado sem acesso nenhum na stage-2.
        Stage2TranslatingAddressSpace64 stage2 = newStage2(physical, 0x00A0_0000L);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> mmu.translateForAddressTranslateStage12(VA_EL1_ONLY, MemoryAccessType.DATA_READ, false,
                        stage2));

        assertEquals(FaultStatus64.PERMISSION_FAULT_L3, fault.faultStatus());
        assertTrue(fault.isStage2(), "S2AP=00 nega o acesso na stage-2, distinto da permissão de stage-1");
    }

    @Test
    void fisicoContinuaAcessivelDiretamenteSemMmu() {
        // G3: TranslatingAddressSpace64 é um wrapper novo; o AddressSpace64 físico continua
        // funcionando sozinho para quem (armbox Aarch64LinuxMachine, B6.2) não usa MMU.
        AddressSpace64 physical = newPhysical();
        physical.write32(0, 42);
        assertEquals(42, physical.read32(0));
    }
}
