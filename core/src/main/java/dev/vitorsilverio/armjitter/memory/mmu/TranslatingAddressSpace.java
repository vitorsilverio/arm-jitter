package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace` que traduz endereço virtual (VA) para físico (PA) via page-walk
/// short-descriptor ARMv6 (ARM DDI 0100I §B4), envolvendo um `AddressSpace` físico existente sem
/// alterá-lo (RFC-SOFTMMU decisão 1: tradução em wrapper, não inline no JIT).
///
/// ### Formato das tabelas de página (short-descriptor, sem extensões de segurança/PXN)
/// - **L1** (`TTBR0`, 4096 entradas de 4 bytes = 16KiB, indexado por `VA[31:20]`): cada entrada é
///   *fault*, um ponteiro para uma tabela L1 *coarse* (256 entradas, 1MiB cobertos), ou uma
///   **seção** de 1MiB traduzida diretamente.
/// - **L2** (tabela *coarse*, 256 entradas de 4 bytes = 1KiB, indexado por `VA[19:12]`): cada
///   entrada é *fault*, uma **página grande** de 64KiB ou uma **página pequena** de 4KiB.
/// - Sem *subpages* (removidas no ARMv6: cada página tem um único par de bits AP, não quatro).
/// - `XN`/`APX`/`TEX`/`C`/`B`/`S`/`nG` não são decodificados nesta fase (sem cache modelado, sem
///   *execute-never*, sem entradas globais de TLB) — apenas tipo de descritor, domínio e AP
///   clássico de 2 bits, que é o que a B4.1.1 pede. Ficam para quando um consumidor real
///   precisar (B4.1.5 ou além).
///
/// ### Micro-TLB
/// Duas tabelas diretas de 256 entradas (instrução/dados separadas, RFC-SOFTMMU decisão 1), cada
/// entrada cacheando a tradução de uma página de 4KiB (mesmo quando a entrada de origem é uma
/// seção de 1MiB ou página grande de 64KiB — só o recorte de 4KiB efetivamente acessado entra na
/// TLB). Um HIT reaproveita física+AP+domínio sem reler as tabelas de página; a checagem de
/// permissão (que depende do `DACR` e do modo atual) é refeita a cada acesso, hit ou miss, para
/// que uma mudança de `DACR` ou de modo tenha efeito imediato sem exigir invalidação de TLB
/// (comportamento real de hardware: só o resultado do walk é cacheado, não a política).
///
/// ### Fora de escopo (não fazer — ver README das tasks)
/// CP15 (`setTtbr0`/`setDacr`/`setPrivileged`/`invalidateTlb*` são chamados diretamente pelo
/// teste nesta fase; a B4.1.2 liga isso a `MCR`/`MRC`), captura de {@link MemoryTranslationException}
/// pelos motores e geração de entrada de abort com `DFSR`/`DFAR` (B4.1.3). {@link #translationGeneration}
/// (B4.1.4) já é exposto aqui via {@link #translationGeneration()} — o `JitRuntime` é quem o
/// consome no `BlockKey`/inline cache, não este wrapper.
public final class TranslatingAddressSpace implements AddressSpace {

    // ── layout L1 (seção, 4096 entradas de 1MiB) ────────────────────────────────────
    private static final int L1_ENTRY_COUNT = 4096;
    private static final int L1_INDEX_SHIFT = 20;
    private static final int L1_INDEX_MASK = L1_ENTRY_COUNT - 1;
    private static final int L1_BASE_ALIGN_BITS = 14; // TTBR0: tabela L1 alinhada a 16KiB
    private static final int TTBR0_BASE_MASK = ~((1 << L1_BASE_ALIGN_BITS) - 1);

    private static final int DESCRIPTOR_TYPE_MASK = 0b11;
    private static final int L1_TYPE_FAULT = 0b00;
    private static final int L1_TYPE_PAGE_TABLE = 0b01;
    private static final int L1_TYPE_SECTION = 0b10;
    private static final int L1_TYPE_RESERVED = 0b11;

    private static final int DOMAIN_SHIFT = 5;
    private static final int DOMAIN_MASK = 0xF;

    private static final int SECTION_AP_SHIFT = 10;
    private static final int AP_FIELD_MASK = 0b11;
    private static final int SECTION_BASE_MASK = 0xFFF0_0000; // bits[31:20], seção de 1MiB
    private static final int SECTION_OFFSET_MASK = 0x000F_FFFF;

    // ── layout L2 coarse (256 entradas de 4KiB por tabela de 1MiB) ──────────────────
    private static final int COARSE_ENTRY_COUNT = 256;
    private static final int L2_INDEX_SHIFT = 12;
    private static final int L2_INDEX_MASK = COARSE_ENTRY_COUNT - 1;
    private static final int COARSE_BASE_MASK = 0xFFFF_FC00; // bits[31:10], tabela L2 de 1KiB

    private static final int L2_TYPE_FAULT = 0b00;
    private static final int L2_TYPE_LARGE_PAGE = 0b01;
    // 0b10 e 0b11 (small page, bit0 = XN não decodificado) tratados juntos como página pequena.

    private static final int PAGE_AP_SHIFT = 4; // AP[1:0], compartilhado por small/large page

    private static final int LARGE_PAGE_BASE_MASK = 0xFFFF_0000; // bits[31:16], página de 64KiB
    private static final int LARGE_PAGE_OFFSET_MASK = 0x0000_FFFF;
    private static final int SMALL_PAGE_BASE_MASK = 0xFFFF_F000; // bits[31:12], página de 4KiB
    private static final int SMALL_PAGE_OFFSET_MASK = 0x0000_0FFF;

    // ── AP clássico de 2 bits (ARM DDI 0100I, tabela B4-4, AFE=0/APX não usado) ─────
    private static final int AP_NO_ACCESS = 0b00;
    private static final int AP_PRIVILEGED_ONLY = 0b01;
    private static final int AP_USER_READ_ONLY = 0b10;
    private static final int AP_FULL_ACCESS = 0b11;

    // ── micro-TLB: granularidade fixa de 4KiB, direto-mapeada, 256 entradas ─────────
    private static final int TLB_PAGE_SHIFT = 12;
    private static final int TLB_PAGE_MASK = (1 << TLB_PAGE_SHIFT) - 1;

    private final AddressSpace physical;
    private final MicroTlb dataTlb = new MicroTlb();
    private final MicroTlb instructionTlb = new MicroTlb();

    private int ttbr0Base;
    private int dacr;
    private int asid;
    private boolean privileged = true;
    private boolean mmuEnabled = true;
    private long walkCount;
    /// Geração de tradução (RFC-SOFTMMU §5, B4.1.4) — ver {@link AddressSpace#translationGeneration()}.
    /// Incrementada só em {@link #setTtbr0}, {@link #setAsid} e {@link #invalidateTlbAll}: as três
    /// operações que trocam QUAL tradução um VA já resolvido antes passa a produzir (troca de
    /// tabela de páginas, troca de ASID, ou descarte total da TLB). {@link #setDacr}/
    /// {@link #setPrivileged}/{@link #invalidateTlbByMva} não bumpam — mudam a política de
    /// checagem ou descartam só uma página, sem invalidar a identidade "mesmo VA, mesmo mapeamento"
    /// que o `JitRuntime` precisa para decidir se um bloco compilado continua válido.
    private int translationGeneration;

    /// @param physical barramento físico por trás da tradução — nunca alterado, continua
    ///                  utilizável diretamente por quem não quiser MMU (G3)
    public TranslatingAddressSpace(AddressSpace physical) {
        this.physical = Objects.requireNonNull(physical, "physical");
    }

    // ── configuração (espelha os registradores CP15 que a B4.1.2 vai expor) ────────

    /// Define `TTBR0`: endereço-base da tabela L1, alinhado a 16KiB. Bits abaixo do
    /// alinhamento (atributos de cache do walk em hardware real) são ignorados aqui — não há
    /// cache de page-walk modelado nesta fase.
    public void setTtbr0(int ttbr0) {
        this.ttbr0Base = ttbr0 & TTBR0_BASE_MASK;
        translationGeneration++;
    }

    /// Define o ASID (`CONTEXTIDR[7:0]`) usado para taguear novas entradas de TLB e para casar
    /// entradas existentes num lookup. Entradas preenchidas sob outro ASID somem naturalmente do
    /// lookup (miss) sem precisar de invalidação explícita — mesma técnica de tag usada por
    /// TLBs reais com suporte a múltiplos processos.
    public void setAsid(int asid) {
        this.asid = asid;
        translationGeneration++;
    }

    /// Define o `DACR` (Domain Access Control Register): 16 campos de 2 bits, um por domínio
    /// (domínio `d` ocupa os bits `[2d+1:2d]`). Ver {@link DomainAccess}.
    public void setDacr(int dacr) {
        this.dacr = dacr;
    }

    /// Define se os acessos seguintes são feitos em modo privilegiado (qualquer modo exceto
    /// `USER`) ou usuário — os bits AP tratam os dois casos de forma diferente. Corresponde ao
    /// que a B4.1.2 vai sincronizar a partir do `CPSR` do core a cada troca de modo.
    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    /// Liga/desliga a tradução (`SCTLR.M`, B4.1.2). Desligada, todo acesso vira passthrough
    /// identidade para o físico — sem walk, sem TLB, sem checagem de permissão/domínio — igual ao
    /// comportamento real de hardware antes da MMU ser habilitada pelo software. Default `true`
    /// (G3: o teste da B4.1.1 usa o wrapper direto, sem CP15, e espera tradução sempre ativa;
    /// quem simula o reset real de hardware, o {@code Cp15VmsaCoprocessor}, desliga explicitamente
    /// no construtor).
    public void setMmuEnabled(boolean mmuEnabled) {
        this.mmuEnabled = mmuEnabled;
    }

    /// Estado atual de {@link #setMmuEnabled}, para o `Cp15VmsaCoprocessor` reconstruir a leitura
    /// de `SCTLR.M` sem guardar uma cópia própria do bit.
    public boolean mmuEnabled() {
        return mmuEnabled;
    }

    /// Invalida toda a micro-TLB (instrução e dados) — equivalente a `TLBIALL` (TLB unificada).
    public void invalidateTlbAll() {
        invalidateDataTlbAll();
        invalidateInstructionTlbAll();
    }

    /// Invalida, se presente, a entrada de TLB (instrução e dados) cuja página cobre `mva` —
    /// equivalente a `TLBIMVA` (TLB unificada).
    public void invalidateTlbByMva(int mva) {
        invalidateDataTlbByMva(mva);
        invalidateInstructionTlbByMva(mva);
    }

    /// Invalida toda a micro-TLB de INSTRUÇÃO — equivalente a `ITLBIALL` (`c8,c5,0`). Cores com
    /// TLBs separadas (ARM926EJ-S/ARMv5, `v4wbi_*` do Linux) invalidam I e D em instruções
    /// distintas, e é a face de instrução que muda o mapeamento VA→PA de CÓDIGO — por isso é
    /// esta (e não {@link #invalidateDataTlbAll}) que bumpa {@link #translationGeneration()},
    /// invalidando blocos já compilados (RFC-SOFTMMU §5).
    public void invalidateInstructionTlbAll() {
        instructionTlb.invalidateAll();
        translationGeneration++;
    }

    /// Invalida toda a micro-TLB de DADOS — equivalente a `DTLBIALL` (`c8,c6,0`). Não bumpa a
    /// geração de tradução: mapeamento de dados não muda a identidade "mesmo VA de código, mesmo
    /// bloco compilado" (ver {@link #invalidateInstructionTlbAll}).
    public void invalidateDataTlbAll() {
        dataTlb.invalidateAll();
    }

    /// Invalida a entrada da micro-TLB de INSTRUÇÃO que cobre `mva` — `ITLBIMVA` (`c8,c5,1`).
    public void invalidateInstructionTlbByMva(int mva) {
        instructionTlb.invalidateEntry(mva >>> TLB_PAGE_SHIFT);
    }

    /// Invalida a entrada da micro-TLB de DADOS que cobre `mva` — `DTLBIMVA` (`c8,c6,1`).
    public void invalidateDataTlbByMva(int mva) {
        dataTlb.invalidateEntry(mva >>> TLB_PAGE_SHIFT);
    }

    /// Quantidade de page-walks (leituras de tabela L1/L2) executados desde a criação —
    /// incrementado só em MISS de TLB, nunca em HIT. Existe para o teste provar que um HIT não
    /// refaz o walk.
    public long pageWalkCount() {
        return walkCount;
    }

    /// Ver {@link AddressSpace#translationGeneration()}.
    @Override
    public int translationGeneration() {
        return translationGeneration;
    }

    // ── fetch de instrução (TLB de instrução, separada da de dados) ────────────────

    /// Busca uma halfword (THUMB) traduzindo `va` pela TLB de instrução.
    @Override
    public int fetch16(int va) {
        int offset = va & TLB_PAGE_MASK;
        if (offset + 1 < (1 << TLB_PAGE_SHIFT)) {
            return physical.read16(translateFetch(va));
        }
        return (physical.read8(translateFetch(va)) & 0xFF)
                | ((physical.read8(translateFetch(va + 1)) & 0xFF) << 8);
    }

    /// Busca uma word (ARM) traduzindo `va` pela TLB de instrução.
    @Override
    public int fetch32(int va) {
        int offset = va & TLB_PAGE_MASK;
        if (offset + 3 < (1 << TLB_PAGE_SHIFT)) {
            return physical.read32(translateFetch(va));
        }
        return (physical.read8(translateFetch(va)) & 0xFF)
                | ((physical.read8(translateFetch(va + 1)) & 0xFF) << 8)
                | ((physical.read8(translateFetch(va + 2)) & 0xFF) << 16)
                | ((physical.read8(translateFetch(va + 3)) & 0xFF) << 24);
    }

    // ── AddressSpace (caminho de dados) ─────────────────────────────────────────────

    @Override
    public int read8(int address) {
        return physical.read8(translateData(address, MemoryAccessType.DATA_READ));
    }

    @Override
    public int read16(int address) {
        int offset = address & TLB_PAGE_MASK;
        if (offset + 1 < (1 << TLB_PAGE_SHIFT)) {
            return physical.read16(translateData(address, MemoryAccessType.DATA_READ));
        }
        return (read8(address) & 0xFF) | ((read8(address + 1) & 0xFF) << 8);
    }

    @Override
    public int read32(int address) {
        int offset = address & TLB_PAGE_MASK;
        if (offset + 3 < (1 << TLB_PAGE_SHIFT)) {
            return physical.read32(translateData(address, MemoryAccessType.DATA_READ));
        }
        return (read8(address) & 0xFF)
                | ((read8(address + 1) & 0xFF) << 8)
                | ((read8(address + 2) & 0xFF) << 16)
                | ((read8(address + 3) & 0xFF) << 24);
    }

    @Override
    public void write8(int address, int value) {
        physical.write8(translateData(address, MemoryAccessType.DATA_WRITE), value);
    }

    @Override
    public void write16(int address, int value) {
        int offset = address & TLB_PAGE_MASK;
        if (offset + 1 < (1 << TLB_PAGE_SHIFT)) {
            physical.write16(translateData(address, MemoryAccessType.DATA_WRITE), value);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        int offset = address & TLB_PAGE_MASK;
        if (offset + 3 < (1 << TLB_PAGE_SHIFT)) {
            physical.write32(translateData(address, MemoryAccessType.DATA_WRITE), value);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        int pa = type == MemoryAccessType.INSTRUCTION_FETCH
                ? translateFetch(address)
                : translateData(address, type);
        return physical.accessCycles(pa, sizeBytes, type);
    }

    @Override
    public boolean providesAccessCycles() {
        return physical.providesAccessCycles();
    }

    @Override
    public void notifyWrite(int address) {
        physical.notifyWrite(translateData(address, MemoryAccessType.DATA_WRITE));
    }

    // ── tradução ─────────────────────────────────────────────────────────────────

    private int translateFetch(int va) {
        return mmuEnabled ? translate(instructionTlb, va, MemoryAccessType.INSTRUCTION_FETCH) : va;
    }

    private int translateData(int va, MemoryAccessType type) {
        return mmuEnabled ? translate(dataTlb, va, type) : va;
    }

    private int translate(MicroTlb tlb, int va, MemoryAccessType type) {
        int vpn = va >>> TLB_PAGE_SHIFT;
        int idx = tlb.lookup(vpn, asid);
        if (idx == MicroTlb.MISS) {
            WalkResult result = walk(va, type);
            idx = tlb.fill(vpn, asid, result.physicalAddress() >>> TLB_PAGE_SHIFT, result.ap(), result.domain(),
                    result.section());
        } else {
            checkAccess(va, type, tlb.ap(idx), tlb.domain(idx), tlb.section(idx));
        }
        return (tlb.ppn(idx) << TLB_PAGE_SHIFT) | (va & TLB_PAGE_MASK);
    }

    private WalkResult walk(int va, MemoryAccessType type) {
        walkCount++;
        int l1Index = (va >>> L1_INDEX_SHIFT) & L1_INDEX_MASK;
        int l1Descriptor = physical.read32(ttbr0Base + l1Index * 4);
        int l1Type = l1Descriptor & DESCRIPTOR_TYPE_MASK;

        return switch (l1Type) {
            case L1_TYPE_FAULT, L1_TYPE_RESERVED ->
                    throw new MemoryTranslationException(va, type, FaultStatus.SECTION_TRANSLATION);
            case L1_TYPE_SECTION -> walkSection(va, type, l1Descriptor);
            case L1_TYPE_PAGE_TABLE -> walkCoarsePage(va, type, l1Descriptor);
            default -> throw new IllegalStateException("tipo L1 inválido: " + l1Type);
        };
    }

    private WalkResult walkSection(int va, MemoryAccessType type, int l1Descriptor) {
        int domain = (l1Descriptor >>> DOMAIN_SHIFT) & DOMAIN_MASK;
        int ap = (l1Descriptor >>> SECTION_AP_SHIFT) & AP_FIELD_MASK;
        checkAccess(va, type, ap, domain, true);
        int base = l1Descriptor & SECTION_BASE_MASK;
        int physicalAddress = base | (va & SECTION_OFFSET_MASK);
        return new WalkResult(physicalAddress, ap, domain, true);
    }

    private WalkResult walkCoarsePage(int va, MemoryAccessType type, int l1Descriptor) {
        int domain = (l1Descriptor >>> DOMAIN_SHIFT) & DOMAIN_MASK;
        int l2Base = l1Descriptor & COARSE_BASE_MASK;
        int l2Index = (va >>> L2_INDEX_SHIFT) & L2_INDEX_MASK;
        int l2Descriptor = physical.read32(l2Base + l2Index * 4);
        int l2Type = l2Descriptor & DESCRIPTOR_TYPE_MASK;

        if (l2Type == L2_TYPE_FAULT) {
            throw new MemoryTranslationException(va, type, FaultStatus.PAGE_TRANSLATION);
        }
        int ap = (l2Descriptor >>> PAGE_AP_SHIFT) & AP_FIELD_MASK;
        checkAccess(va, type, ap, domain, false);
        int base;
        int physicalAddress;
        if (l2Type == L2_TYPE_LARGE_PAGE) {
            base = l2Descriptor & LARGE_PAGE_BASE_MASK;
            physicalAddress = base | (va & LARGE_PAGE_OFFSET_MASK);
        } else {
            base = l2Descriptor & SMALL_PAGE_BASE_MASK;
            physicalAddress = base | (va & SMALL_PAGE_OFFSET_MASK);
        }
        return new WalkResult(physicalAddress, ap, domain, false);
    }

    private void checkAccess(int va, MemoryAccessType type, int ap, int domain, boolean section) {
        DomainAccess access = DomainAccess.fromBits((dacr >>> (domain * 2)) & 0b11);
        if (access == DomainAccess.MANAGER) {
            return;
        }
        if (access == DomainAccess.CLIENT) {
            if (!apAllows(ap, type)) {
                throw new MemoryTranslationException(va, type,
                        section ? FaultStatus.SECTION_PERMISSION : FaultStatus.PAGE_PERMISSION);
            }
            return;
        }
        // NO_ACCESS ou RESERVED (tratado como NO_ACCESS, ver DomainAccess).
        throw new MemoryTranslationException(va, type,
                section ? FaultStatus.SECTION_DOMAIN : FaultStatus.PAGE_DOMAIN);
    }

    private boolean apAllows(int ap, MemoryAccessType type) {
        boolean write = type == MemoryAccessType.DATA_WRITE;
        return switch (ap) {
            case AP_NO_ACCESS -> false;
            case AP_PRIVILEGED_ONLY -> privileged;
            case AP_USER_READ_ONLY -> privileged || !write;
            case AP_FULL_ACCESS -> true;
            default -> throw new IllegalStateException("AP inválido: " + ap);
        };
    }

    /// Resultado de um page-walk: endereço físico já resolvido para o VA pedido, mais os
    /// atributos (AP/domínio/nível) que a micro-TLB guarda para reavaliar permissão em HITs
    /// futuros sem reler as tabelas de página.
    private record WalkResult(int physicalAddress, int ap, int domain, boolean section) {
    }

    /// Micro-TLB direto-mapeada de 256 entradas, granularidade fixa de 4KiB (RFC-SOFTMMU
    /// decisão 1). Reaproveitada duas vezes (instrução/dados) por `TranslatingAddressSpace`.
    private static final class MicroTlb {
        static final int MISS = -1;
        private static final int ENTRIES = 256;
        private static final int INDEX_MASK = ENTRIES - 1;
        private static final int INVALID_TAG = -1; // vpn nunca é negativo (>>> sempre limpa o bit de sinal)

        private final int[] tagVpn = new int[ENTRIES];
        private final int[] tagAsid = new int[ENTRIES];
        private final int[] ppn = new int[ENTRIES];
        private final int[] ap = new int[ENTRIES];
        private final int[] domain = new int[ENTRIES];
        private final boolean[] section = new boolean[ENTRIES];

        MicroTlb() {
            java.util.Arrays.fill(tagVpn, INVALID_TAG);
        }

        int lookup(int vpn, int currentAsid) {
            int idx = vpn & INDEX_MASK;
            if (tagVpn[idx] == vpn && tagAsid[idx] == currentAsid) {
                return idx;
            }
            return MISS;
        }

        int fill(int vpn, int currentAsid, int ppnValue, int apValue, int domainValue, boolean sectionValue) {
            int idx = vpn & INDEX_MASK;
            tagVpn[idx] = vpn;
            tagAsid[idx] = currentAsid;
            ppn[idx] = ppnValue;
            ap[idx] = apValue;
            domain[idx] = domainValue;
            section[idx] = sectionValue;
            return idx;
        }

        int ppn(int idx) {
            return ppn[idx];
        }

        int ap(int idx) {
            return ap[idx];
        }

        int domain(int idx) {
            return domain[idx];
        }

        boolean section(int idx) {
            return section[idx];
        }

        void invalidateAll() {
            java.util.Arrays.fill(tagVpn, INVALID_TAG);
        }

        void invalidateEntry(int vpn) {
            int idx = vpn & INDEX_MASK;
            if (tagVpn[idx] == vpn) {
                tagVpn[idx] = INVALID_TAG;
            }
        }
    }
}
