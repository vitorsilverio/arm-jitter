package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B4.1.1: page tables short-descriptor ARMv6 montadas à mão (sem binário/kernel real) cobrindo
/// tradução identidade, remapeamento, permissão User/Privileged (AP) e domínio (no-access e
/// manager) — e prova de que um HIT de micro-TLB não refaz o page-walk.
///
/// Layout físico usado pelos testes (todos os endereços físicos vivem no mesmo `TestAddressSpace`):
/// - `0x0000_0000`: tabela L1 (16KiB, 4096 entradas de 1MiB, indexada por `TTBR0`).
/// - `0x0000_5000`: tabela L2 *coarse* usada pelo teste de permissão (1KiB, 256 entradas de 4KiB).
/// - `0x0010_0000..0x0060_0000`: dados de cada seção/página mapeada pelos testes abaixo.
class TranslatingAddressSpaceTest {
    // Bits do formato short-descriptor ARMv6 (ARM DDI 0100I §B4), replicados aqui de forma
    // independente da implementação para o teste não validar contra si mesmo.
    private static final int TYPE_FAULT = 0b00;
    private static final int TYPE_COARSE_PAGE_TABLE = 0b01;
    private static final int TYPE_SECTION = 0b10;
    private static final int TYPE_SMALL_PAGE = 0b10;

    private static final int AP_NO_ACCESS = 0b00;
    private static final int AP_USER_READ_ONLY = 0b10;
    private static final int AP_FULL_ACCESS = 0b11;

    private static final int L1_BASE = 0x0000_0000;
    private static final int L2_BASE = 0x0000_5000;

    private static int sectionDescriptor(int physicalBase, int domain, int ap) {
        return (physicalBase & 0xFFF0_0000) | (ap << 10) | (domain << 5) | TYPE_SECTION;
    }

    private static int coarsePageTableDescriptor(int l2Base, int domain) {
        return (l2Base & 0xFFFF_FC00) | (domain << 5) | TYPE_COARSE_PAGE_TABLE;
    }

    private static int smallPageDescriptor(int physicalBase, int ap) {
        return (physicalBase & 0xFFFF_F000) | (ap << 4) | TYPE_SMALL_PAGE;
    }

    private static int dacrField(int domain, DomainAccess access) {
        return access.bits() << (domain * 2);
    }

    private static int l1IndexOf(int va) {
        return va >>> 20;
    }

    /// Monta um `TranslatingAddressSpace` com:
    /// - VA `0x0010_0000` (seção): tradução IDENTIDADE (PA == VA), domínio 0 = CLIENT, AP full.
    /// - VA `0x0020_0000` (seção): REMAPEADA para PA `0x0030_0000`, domínio 0 = CLIENT, AP full.
    /// - VA `0x0040_0000` (página, via L2 em `0x0000_5000`): PA `0x0035_0000`, domínio 0 = CLIENT,
    ///   AP `USER_READ_ONLY` — privilegiado lê/escreve, usuário só lê.
    /// - VA `0x0050_0000` (seção): domínio 2 = NO_ACCESS (DACR), AP full (ignorado pelo domínio).
    /// - VA `0x0060_0000` (seção): domínio 3 = MANAGER (DACR), AP `NO_ACCESS` (ignorado pelo
    ///   domínio manager).
    private static TranslatingAddressSpace newMmu(TestAddressSpace physical) {
        // L1[0x001] -> seção identidade em 0x0010_0000, domínio 0.
        physical.put32(L1_BASE + l1IndexOf(0x0010_0000) * 4,
                sectionDescriptor(0x0010_0000, 0, AP_FULL_ACCESS));
        // L1[0x002] -> seção remapeada: VA 0x0020_0000 aponta para PA 0x0030_0000, domínio 0.
        physical.put32(L1_BASE + l1IndexOf(0x0020_0000) * 4,
                sectionDescriptor(0x0030_0000, 0, AP_FULL_ACCESS));
        // L1[0x004] -> tabela de páginas coarse em L2_BASE, domínio 0.
        physical.put32(L1_BASE + l1IndexOf(0x0040_0000) * 4,
                coarsePageTableDescriptor(L2_BASE, 0));
        // L2[0] dessa tabela -> página pequena em PA 0x0035_0000, AP usuário-somente-leitura.
        physical.put32(L2_BASE, smallPageDescriptor(0x0035_0000, AP_USER_READ_ONLY));
        // L1[0x005] -> seção com domínio 2 (será marcado NO_ACCESS no DACR).
        physical.put32(L1_BASE + l1IndexOf(0x0050_0000) * 4,
                sectionDescriptor(0x0050_0000, 2, AP_FULL_ACCESS));
        // L1[0x006] -> seção com domínio 3 (será marcado MANAGER no DACR), AP nega tudo.
        physical.put32(L1_BASE + l1IndexOf(0x0060_0000) * 4,
                sectionDescriptor(0x0060_0000, 3, AP_NO_ACCESS));

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setTtbr0(L1_BASE);
        mmu.setDacr(dacrField(0, DomainAccess.CLIENT)
                | dacrField(2, DomainAccess.NO_ACCESS)
                | dacrField(3, DomainAccess.MANAGER));
        mmu.setPrivileged(true);
        return mmu;
    }

    @Test
    void traducaoIdentidade() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        mmu.write32(0x0010_0000, 0x1234_5678);

        assertEquals(0x1234_5678, mmu.read32(0x0010_0000));
        assertEquals(0x1234_5678, physical.read32(0x0010_0000));
    }

    @Test
    void remapeamento() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        mmu.write32(0x0020_0000, 0xCAFE_BABE);

        // A escrita pela VA foi parar na PA remapeada, não no mesmo endereço numérico.
        assertEquals(0xCAFE_BABE, physical.read32(0x0030_0000));
        assertEquals(0, physical.read32(0x0020_0000));
        assertEquals(0xCAFE_BABE, mmu.read32(0x0020_0000));
    }

    @Test
    void permissaoPrivilegiadaPermiteEscritaEmPaginaUserReadOnly() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);
        mmu.setPrivileged(true);

        mmu.write32(0x0040_0000, 0x1111_2222);

        assertEquals(0x1111_2222, mmu.read32(0x0040_0000));
    }

    @Test
    void permissaoUsuarioSoLeituraNaPaginaApUserReadOnly() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);
        // Escreve o valor esperado primeiro, ainda em modo privilegiado.
        mmu.setPrivileged(true);
        mmu.write32(0x0040_0000, 0x7777_8888);

        mmu.setPrivileged(false);
        assertEquals(0x7777_8888, mmu.read32(0x0040_0000));

        MemoryTranslationException ex =
                assertThrows(MemoryTranslationException.class, () -> mmu.write32(0x0040_0000, 0x9999_0000));
        assertEquals(FaultStatus.PAGE_PERMISSION, ex.faultStatus());
        assertEquals(0x0040_0000, ex.virtualAddress());
    }

    /// `LDRxT`/`STRxT` (B9.9): {@link TranslatingAddressSpace#withUnprivilegedAccess} deve tratar
    /// o acesso como `USER` mesmo com o `AddressSpace` em modo privilegiado, e restaurar
    /// `privileged` ao original depois — mesmo padrão de "efeito mínimo" que
    /// {@link #permissaoUsuarioSoLeituraNaPaginaApUserReadOnly} já prova para {@code setPrivileged}
    /// explícito, mas sem o CPU nunca sair de fato do modo privilegiado.
    @Test
    void withUnprivilegedAccessChecksPermissionAsUserThenRestoresPrivileged() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);
        mmu.setPrivileged(true);
        mmu.write32(0x0040_0000, 0x7777_8888); // ainda privilegiado: grava o valor esperado

        MemoryTranslationException ex = assertThrows(MemoryTranslationException.class,
                () -> mmu.withUnprivilegedAccess(() -> mmu.write32(0x0040_0000, 0x9999_0000)));
        assertEquals(FaultStatus.PAGE_PERMISSION, ex.faultStatus());

        // privilégio real restaurado: a MESMA escrita que acabou de falhar sob withUnprivilegedAccess
        // funciona de novo fora dele.
        mmu.write32(0x0040_0000, 0xABCD_EF01);
        assertEquals(0xABCD_EF01, mmu.read32(0x0040_0000));
    }

    @Test
    void withUnprivilegedAccessRestoresPrivilegedEvenWhenActionThrows() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);
        mmu.setPrivileged(true);

        assertThrows(MemoryTranslationException.class,
                () -> mmu.withUnprivilegedAccess(() -> mmu.write32(0x0040_0000, 0)));

        // Sem restauração no finally, a checagem seguinte ainda estaria em modo usuário e esta
        // escrita privilegiada legítima falharia também.
        mmu.write32(0x0040_0000, 0x1234);
        assertEquals(0x1234, mmu.read32(0x0040_0000));
    }

    @Test
    void dominioNoAccessBloqueiaMesmoComApFullAccess() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        MemoryTranslationException ex =
                assertThrows(MemoryTranslationException.class, () -> mmu.read32(0x0050_0000));
        assertEquals(FaultStatus.SECTION_DOMAIN, ex.faultStatus());
    }

    @Test
    void dominioManagerIgnoraApNoAccess() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        // AP=NO_ACCESS normalmente bloquearia tudo, mas o domínio 3 é MANAGER.
        mmu.write32(0x0060_0000, 0xABCD_EF01);
        assertEquals(0xABCD_EF01, mmu.read32(0x0060_0000));
    }

    @Test
    void tlbHitNaoRefazPageWalk() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        assertEquals(0, mmu.pageWalkCount());

        mmu.write32(0x0010_0000, 1);
        long afterFirstWrite = mmu.pageWalkCount();
        assertEquals(1, afterFirstWrite, "primeiro acesso à página deve andar as tabelas exatamente uma vez");

        // Mesma página (mesmo VPN de 4KiB): leituras/escritas seguintes devem ser HIT de TLB.
        mmu.read32(0x0010_0000);
        mmu.write32(0x0010_0004, 2);
        mmu.read32(0x0010_0FFC);
        assertEquals(afterFirstWrite, mmu.pageWalkCount(), "HIT de TLB não deve reler L1/L2");

        // Uma página DIFERENTE (VPN diferente) deve gerar um walk novo.
        mmu.read32(0x0020_0000);
        assertEquals(afterFirstWrite + 1, mmu.pageWalkCount());
    }

    @Test
    void invalidateTlbAllForcaNovoWalk() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        mmu.read32(0x0010_0000);
        long afterFirst = mmu.pageWalkCount();

        mmu.read32(0x0010_0000);
        assertEquals(afterFirst, mmu.pageWalkCount(), "HIT antes de invalidar");

        mmu.invalidateTlbAll();
        mmu.read32(0x0010_0000);
        assertEquals(afterFirst + 1, mmu.pageWalkCount(), "invalidateTlbAll deve forçar novo walk");
    }

    /// B4.1.5: cores ARMv5 com TLBs separadas invalidam instrução e dados por instruções
    /// distintas (`c8,c5,*` e `c8,c6,*`) — as duas faces precisam ser independentes de verdade, e
    /// só a de INSTRUÇÃO bumpa a geração de tradução (mapeamento de dados não invalida bloco
    /// compilado; RFC-SOFTMMU §5).
    @Test
    void facesDeInstrucaoEDadosDaTlbSaoIndependentes() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        mmu.read32(0x0010_0000);
        mmu.fetch32(0x0010_0000);
        long afterBoth = mmu.pageWalkCount();
        int generationAfterBoth = mmu.translationGeneration();

        mmu.invalidateDataTlbAll();
        mmu.fetch32(0x0010_0000);
        assertEquals(afterBoth, mmu.pageWalkCount(), "invalidar dados não pode invalidar instrução");
        assertEquals(generationAfterBoth, mmu.translationGeneration(),
                "TLB de dados não muda a tradução de CÓDIGO: sem bump de geração");
        mmu.read32(0x0010_0000);
        assertEquals(afterBoth + 1, mmu.pageWalkCount(), "a face de dados foi invalidada");

        long afterDataRefill = mmu.pageWalkCount();
        mmu.invalidateInstructionTlbAll();
        assertEquals(generationAfterBoth + 1, mmu.translationGeneration(),
                "invalidar a TLB de instrução bumpa a geração de tradução");
        mmu.read32(0x0010_0000);
        assertEquals(afterDataRefill, mmu.pageWalkCount(), "invalidar instrução não pode invalidar dados");
        mmu.fetch32(0x0010_0000);
        assertEquals(afterDataRefill + 1, mmu.pageWalkCount(), "a face de instrução foi invalidada");
    }

    @Test
    void invalidateTlbByMvaSoAfetaAPaginaAlvo() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        mmu.read32(0x0010_0000);
        mmu.read32(0x0020_0000);
        long afterBoth = mmu.pageWalkCount();

        mmu.invalidateTlbByMva(0x0010_0000);
        mmu.read32(0x0020_0000); // continua HIT
        assertEquals(afterBoth, mmu.pageWalkCount());

        mmu.read32(0x0010_0000); // MISS: foi invalidada
        assertEquals(afterBoth + 1, mmu.pageWalkCount());
    }

    @Test
    void faltaDeTraducaoLancaSectionTranslationFault() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        TranslatingAddressSpace mmu = newMmu(physical);

        MemoryTranslationException ex =
                assertThrows(MemoryTranslationException.class, () -> mmu.read32(0x00F0_0000));
        assertEquals(FaultStatus.SECTION_TRANSLATION, ex.faultStatus());
    }

    @Test
    void fisicoContinuaAcessivelDiretamenteSemMmu() {
        // G3: TranslatingAddressSpace é um wrapper novo; o AddressSpace físico continua
        // funcionando sozinho para quem (gbaemu/ndsemu) não usa MMU.
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        AddressSpace plain = physical;
        plain.write32(0, 42);
        assertEquals(42, plain.read32(0));
    }
}
