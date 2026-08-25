package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace64` que traduz endereço virtual (VA) para físico (PA) via page-walk VMSA64 (ARM
/// DDI 0487 D8, "The AArch64 Virtual Memory System Architecture"), envolvendo um `AddressSpace64`
/// físico existente sem alterá-lo — sibling ESTRUTURAL de `TranslatingAddressSpace` (32-bit,
/// short-descriptor ARMv6), não uma generalização (RFC-IR-64BIT.md §3.1, mesmo precedente de toda
/// sub-task A64 anterior).
///
/// ### Configuração coberta (escopo mínimo, ver task B6.6.2 decisão D2)
/// **Só granule de 4KiB, VA de 48 bits, 4 níveis de tabela (L0-L3)** — a configuração mínima real
/// usada por kernels arm64 modernos. Granules de 16KiB/64KiB, VA de 39/42/52 bits, TrustZone
/// (`NS`) e tabelas EL2/EL3 ficam fora (mesma disciplina de "config mínima" que o precedente
/// 32-bit aplicou ao short-descriptor sem sub-pages/XN/TEX).
///
/// ### Formato do descritor de 64 bits
/// Cada nível indexa 9 bits do VA (`VA[47:39]`=L0, `VA[38:30]`=L1, `VA[29:21]`=L2,
/// `VA[20:12]`=L3, `VA[11:0]`=offset dentro da página de 4KiB) — 512 entradas por tabela
/// (`512×8B=4KiB`, cada tabela cabe numa página). Bit0 = válido; bit1 = tipo — em L0-L2, `0` é
/// **bloco** (só permitido em L1/L2: 1GiB e 2MiB respectivamente — bloco em L0 é tratado como
/// falha de tradução, já que 4KiB/48-bit não suporta bloco de 512GiB) e `1` é **tabela** (aponta
/// para o próximo nível); em L3 só existe **página** (bit1 sempre `1`, terminal — não há L4).
///
/// ### Atributos modelados (escopo mínimo, D2)
/// `AP[2:1]` (bits `[7:6]`: bit baixo = acesso permitido em EL0, bit alto = somente-leitura) e
/// `PXN`/`UXN` (bits 53/54: execute-never privilegiado/não-privilegiado). **Sem domínios** (não
/// existem em VMSA64 — permissão é só `AP`+`PXN`/`UXN`) e **sem `MAIR`/cacheability reais**
/// (`AttrIndx` é decodificado mas não usado — `setMair` fica como armazenamento sem efeito
/// observável, mesmo tratamento RAZ/WI-like que `c7` recebeu no precedente 32-bit).
///
/// ### Micro-TLB
/// Mesma estrutura do precedente 32-bit (direto-mapeada, 256 entradas, granularidade fixa de
/// 4KiB — só o recorte de 4KiB efetivamente acessado entra na TLB, mesmo quando a entrada de
/// origem é um bloco de 1GiB/2MiB), mas com chave de VA de 64 bits.
///
/// ### Fora de escopo (não fazer — ver README das tasks)
/// Bridge com `MRS`/`MSR` guest (B6.6.3, que instala este wrapper como `AddressSpace64` real de
/// um hospedeiro), aborts precisos/captura pelos motores (B6.6.4), consumo de
/// {@link #translationGeneration()} por `BlockKey64`/`JitRuntime64` (B6.6.5 — o campo nasce aqui,
/// mas não é lido em lugar nenhum ainda).
public final class TranslatingAddressSpace64 implements AddressSpace64 {

    // ── geometria VMSA64 (granule 4KiB, VA 48 bits, 4 níveis L0-L3) ─────────────────
    private static final int PAGE_BITS = 12; // granule de 4KiB
    private static final int LEVEL_INDEX_BITS = 9; // 512 entradas por tabela (512×8B = 4KiB)
    private static final long LEVEL_INDEX_MASK = (1L << LEVEL_INDEX_BITS) - 1;
    private static final int DESCRIPTOR_SIZE_BYTES = 8;
    private static final int OUTPUT_ADDRESS_HIGH_BIT = 47; // VA/PA de 48 bits: bits [47:0]

    private static final int L0_SHIFT = PAGE_BITS + 3 * LEVEL_INDEX_BITS; // 39: VA[47:39]
    private static final int L1_SHIFT = PAGE_BITS + 2 * LEVEL_INDEX_BITS; // 30: VA[38:30]
    private static final int L2_SHIFT = PAGE_BITS + LEVEL_INDEX_BITS;     // 21: VA[29:21]
    private static final int L3_SHIFT = PAGE_BITS;                       // 12: VA[20:12]

    private static final int LEVEL_L0 = 0;
    private static final int LEVEL_L1 = 1;
    private static final int LEVEL_L2 = 2;
    private static final int LEVEL_L3 = 3;

    private static final long TABLE_OR_PAGE_ADDRESS_MASK = outputAddressMask(PAGE_BITS); // [47:12]
    private static final long BLOCK_L1_ADDRESS_MASK = outputAddressMask(L1_SHIFT);       // [47:30], 1GiB
    private static final long BLOCK_L2_ADDRESS_MASK = outputAddressMask(L2_SHIFT);       // [47:21], 2MiB
    private static final long BLOCK_L1_OFFSET_MASK = (1L << L1_SHIFT) - 1;
    private static final long BLOCK_L2_OFFSET_MASK = (1L << L2_SHIFT) - 1;
    private static final long PAGE_OFFSET_MASK = (1L << PAGE_BITS) - 1;

    private static long outputAddressMask(int lowBitInclusive) {
        long width = OUTPUT_ADDRESS_HIGH_BIT - lowBitInclusive + 1;
        return ((1L << width) - 1) << lowBitInclusive;
    }

    // ── layout do descritor (ARM DDI 0487 D8.3) ─────────────────────────────────────
    private static final long DESC_VALID_BIT = 0b1L;
    private static final long DESC_TABLE_OR_PAGE_BIT = 0b10L; // bit1: bloco(0)/tabela-ou-página(1)

    private static final int AP_FIELD_SHIFT = 6; // bits[7:6], AP[2:1]
    private static final long AP_FIELD_MASK = 0b11L;
    private static final int AP_EL0_ACCESS_BIT = 0b01; // AP[1]: acesso permitido em EL0
    private static final int AP_READ_ONLY_BIT = 0b10;  // AP[2]: somente-leitura

    private static final long PXN_BIT = 1L << 53; // execute-never privilegiado
    private static final long UXN_BIT = 1L << 54; // execute-never não-privilegiado (EL0)

    // ── TTBR0_EL1: ASID embutido em [63:48] (config genérica de 16 bits; ver task D5) ──
    private static final int ASID_SHIFT = 48;
    private static final long ASID_MASK = 0xFFFFL;

    // ── seleção TTBR0/TTBR1 (achado real da F11/B6.13): bit 55 do VA, não bit 63 ──────
    private static final int VA_TTBR_SELECT_BIT = 55;

    // ── micro-TLB: granularidade fixa de 4KiB, direto-mapeada, 256 entradas ─────────
    private static final int TLB_ENTRIES = 256;
    private static final long TLB_INDEX_MASK = TLB_ENTRIES - 1;

    private final AddressSpace64 physical;
    private final MicroTlb64 dataTlb = new MicroTlb64();
    private final MicroTlb64 instructionTlb = new MicroTlb64();

    private long ttbr0Base;
    /// `TTBR1_EL1` (achado real da F11, confirmado pela investigação de B6.13 contra
    /// `aa64_va_parameters` real do QEMU): segunda tabela-base para o espaço de endereço alto do
    /// kernel — {@link #walk} escolhe entre esta e {@link #ttbr0Base} olhando SÓ o bit 55 do VA
    /// (não o bit 63, como a hipótese original errada de B6.13 presumia). Sem ASID próprio (D2,
    /// mesma simplificação de {@link #asid} — `TCR_EL1.A1` não é lido, o ASID efetivo continua
    /// vindo sempre de {@link #ttbr0Base}, coerente com o default de hardware `TCR_EL1.A1=0`).
    private long ttbr1Base;
    private int asid;
    private long tcr;
    private long mair;
    private boolean privileged = true;
    private boolean mmuEnabled = true;
    private long walkCount;
    /// Geração de tradução (mesmo papel de `AddressSpace#translationGeneration()`, B4.1.4) — ver
    /// {@link #translationGeneration()}. Incrementada só em {@link #setTtbr0} e
    /// {@link #invalidateTlbAll} (troca de tabela de páginas/ASID — o ASID de A64 vem embutido no
    /// próprio `TTBR0_EL1`, então trocar de processo já passa por `setTtbr0` — ou descarte total
    /// da TLB); {@link #setTcr}/{@link #setMair}/{@link #invalidateTlbByMva} não bumpam, mesma
    /// lógica exata do precedente 32-bit.
    private int translationGeneration;

    /// @param physical barramento físico por trás da tradução — nunca alterado, continua
    ///                  utilizável diretamente por quem não quiser MMU (G3)
    public TranslatingAddressSpace64(AddressSpace64 physical) {
        this.physical = Objects.requireNonNull(physical, "physical");
    }

    // ── configuração (espelha os registradores de sistema que a B6.6.3 vai expor) ──

    /// Define `TTBR0_EL1`: base da tabela L0 (mascarada para bits `[47:12]`, alinhamento de
    /// 4KiB) mais o ASID embutido em `[63:48]` (ver {@link #translationGeneration}).
    public void setTtbr0(long ttbr0) {
        this.ttbr0Base = ttbr0 & TABLE_OR_PAGE_ADDRESS_MASK;
        this.asid = (int) ((ttbr0 >>> ASID_SHIFT) & ASID_MASK);
        translationGeneration++;
    }

    /// Define `TTBR1_EL1`: base da tabela L0 do espaço de endereço alto (mascarada para bits
    /// `[47:12]`, mesmo alinhamento de {@link #setTtbr0}). Não muda {@link #asid} (D2, ver javadoc
    /// de {@link #ttbr1Base}); bumpa {@link #translationGeneration} pela mesma razão de
    /// {@link #setTtbr0} (troca de tabela de páginas do kernel).
    public void setTtbr1(long ttbr1) {
        this.ttbr1Base = ttbr1 & TABLE_OR_PAGE_ADDRESS_MASK;
        translationGeneration++;
    }

    /// Define `TCR_EL1`. Armazenamento simples (D2): esta task fixa granule 4KiB/VA 48 bits, sem
    /// ler os campos de tamanho/granule do valor recebido.
    public void setTcr(long tcr) {
        this.tcr = tcr;
    }

    /// Define `MAIR_EL1`. Armazenamento simples sem efeito observável (D2): `AttrIndx` é
    /// decodificado do descritor mas não usado — sem cacheability real modelada, mesmo
    /// tratamento RAZ/WI-like que `c7` recebeu no precedente 32-bit.
    public void setMair(long mair) {
        this.mair = mair;
    }

    /// Define se os acessos seguintes são feitos em EL1 (privilegiado) ou EL0 (usuário) — os
    /// bits `AP`/`PXN`/`UXN` tratam os dois casos de forma diferente.
    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    /// Liga/desliga a tradução (`SCTLR_EL1.M`, B6.6.3). Desligada, todo acesso vira passthrough
    /// identidade para o físico. Default `true` (mesma justificativa G3 do precedente 32-bit).
    public void setMmuEnabled(boolean mmuEnabled) {
        this.mmuEnabled = mmuEnabled;
    }

    /// Estado atual de {@link #setMmuEnabled}.
    public boolean mmuEnabled() {
        return mmuEnabled;
    }

    /// Invalida toda a micro-TLB (instrução e dados) — equivalente a `TLBI VMALLE1`.
    public void invalidateTlbAll() {
        dataTlb.invalidateAll();
        instructionTlb.invalidateAll();
        translationGeneration++;
    }

    /// Invalida, se presente, a entrada de TLB (instrução e dados) cuja página cobre `mva` —
    /// equivalente a `TLBI VAE1`.
    public void invalidateTlbByMva(long mva) {
        long vpn = mva >>> PAGE_BITS;
        dataTlb.invalidateEntry(vpn);
        instructionTlb.invalidateEntry(vpn);
    }

    /// Quantidade de page-walks executados desde a criação — incrementado só em MISS de TLB.
    public long pageWalkCount() {
        return walkCount;
    }

    /// Ver {@link AddressSpace64#translationGeneration()}.
    @Override
    public int translationGeneration() {
        return translationGeneration;
    }

    // ── fetch de instrução (TLB de instrução, separada da de dados) ────────────────

    /// Busca uma word de 32 bits traduzindo `va` pela TLB de instrução. A64 sempre busca
    /// instruções de 4 bytes alinhadas — não há `fetch16` equivalente a Thumb (simplificação
    /// real em relação ao precedente 32-bit).
    public int fetch32(long va) {
        return physical.read32(translateFetch(va));
    }

    // ── AddressSpace64 (caminho de dados) ───────────────────────────────────────────

    @Override
    public int read8(long address) {
        return physical.read8(translateData(address, MemoryAccessType.DATA_READ));
    }

    @Override
    public int read16(long address) {
        long offset = address & PAGE_OFFSET_MASK;
        if (offset + 1 < (1L << PAGE_BITS)) {
            return physical.read16(translateData(address, MemoryAccessType.DATA_READ));
        }
        return (read8(address) & 0xFF) | ((read8(address + 1) & 0xFF) << 8);
    }

    @Override
    public int read32(long address) {
        long offset = address & PAGE_OFFSET_MASK;
        if (offset + 3 < (1L << PAGE_BITS)) {
            return physical.read32(translateData(address, MemoryAccessType.DATA_READ));
        }
        return (read8(address) & 0xFF)
                | ((read8(address + 1) & 0xFF) << 8)
                | ((read8(address + 2) & 0xFF) << 16)
                | ((read8(address + 3) & 0xFF) << 24);
    }

    @Override
    public void write8(long address, int value) {
        physical.write8(translateData(address, MemoryAccessType.DATA_WRITE), value);
    }

    @Override
    public void write16(long address, int value) {
        long offset = address & PAGE_OFFSET_MASK;
        if (offset + 1 < (1L << PAGE_BITS)) {
            physical.write16(translateData(address, MemoryAccessType.DATA_WRITE), value);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(long address, int value) {
        long offset = address & PAGE_OFFSET_MASK;
        if (offset + 3 < (1L << PAGE_BITS)) {
            physical.write32(translateData(address, MemoryAccessType.DATA_WRITE), value);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    @Override
    public int accessCycles(long address, int sizeBytes, MemoryAccessType type) {
        long pa = type == MemoryAccessType.INSTRUCTION_FETCH
                ? translateFetch(address)
                : translateData(address, type);
        return physical.accessCycles(pa, sizeBytes, type);
    }

    @Override
    public boolean providesAccessCycles() {
        return physical.providesAccessCycles();
    }

    @Override
    public void notifyWrite(long address) {
        physical.notifyWrite(translateData(address, MemoryAccessType.DATA_WRITE));
    }

    // ── tradução ─────────────────────────────────────────────────────────────────

    private long translateFetch(long va) {
        return mmuEnabled ? translate(instructionTlb, va, MemoryAccessType.INSTRUCTION_FETCH) : va;
    }

    private long translateData(long va, MemoryAccessType type) {
        return mmuEnabled ? translate(dataTlb, va, type) : va;
    }

    private long translate(MicroTlb64 tlb, long va, MemoryAccessType type) {
        long vpn = va >>> PAGE_BITS;
        int idx = tlb.lookup(vpn, asid);
        if (idx == MicroTlb64.MISS) {
            WalkResult result = walk(va, type);
            idx = tlb.fill(vpn, asid, result.physicalAddress() >>> PAGE_BITS, result.ap(), result.pxn(),
                    result.uxn(), result.level());
        } else {
            checkAccess(va, type, tlb.ap(idx), tlb.pxn(idx), tlb.uxn(idx), tlb.level(idx));
        }
        return (tlb.ppn(idx) << PAGE_BITS) | (va & PAGE_OFFSET_MASK);
    }

    /// `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (B10.6): mesmo page-walk de {@link #walk}, mas SEM
    /// consultar/preencher a micro-TLB (`AT` não deve ter efeito colateral na TLB — mesmo raciocínio
    /// de "efeito mínimo" já aplicado ao precedente 32-bit) e com o `privileged` usado na checagem
    /// de acesso decidido pelo CHAMADOR (`unprivileged`), não pelo {@link #privileged} real do
    /// wrapper — `AT S1E0*` sempre traduz "como EL0" mesmo executando a partir de EL1. O valor
    /// original de {@link #privileged} é restaurado no `finally`, nunca vaza para acessos normais
    /// depois desta chamada.
    ///
    /// @param unprivileged {@code true} para `S1E0*` (checa acesso como EL0); {@code false} para
    ///                     `S1E1*` (checa acesso como EL1)
    /// @return endereço físico completo em sucesso
    /// @throws MemoryTranslationException64 em falha de tradução ou permissão — capturada por quem
    ///         chama (`Aarch64VmsaSystemRegisters#addressTranslate`), NUNCA propagada como abort
    ///         real (`AT` não gera exceção síncrona para o guest)
    public long translateForAddressTranslate(long va, MemoryAccessType type, boolean unprivileged) {
        boolean saved = this.privileged;
        this.privileged = !unprivileged;
        try {
            return walk(va, type).physicalAddress();
        } finally {
            this.privileged = saved;
        }
    }

    /// `AT S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` (B10.8, formas combinadas): mesmo walk de stage-1 de
    /// {@link #translateForAddressTranslate}, seguido de {@link Stage2TranslatingAddressSpace64#translate}
    /// no resultado — o VA passa pela stage-1 (produzindo o que semanticamente é um IPA quando
    /// `HCR_EL2.VM=1`), depois esse IPA passa pela stage-2 até o PA final.
    ///
    /// **Simplificação explícita** (mesma disciplina "escopo mínimo documentado" do resto desta
    /// classe): os acessos às PRÓPRIAS tabelas de stage-1 (leitura dos descritores L0-L3 dentro do
    /// walk de stage-1) continuam lendo `physical` diretamente, SEM passar pela stage-2 — no
    /// hardware real, essas leituras também seriam traduzidas (as tabelas de stage-1 vivem em IPA
    /// sob um hypervisor real). Só o endereço de DADOS final que a stage-1 resolve passa pela
    /// stage-2 aqui. Ver javadoc de {@link Stage2TranslatingAddressSpace64} para o raciocínio
    /// completo — nenhum consumidor real deste emulador roda um guest sob hypervisor emulado hoje,
    /// então a distinção não é observável por enquanto.
    ///
    /// @param stage2 tradutor de stage-2 (`VTTBR_EL2`) — chamador decide se {@code HCR_EL2.VM=1}
    ///               antes de passar um valor não-nulo; se a stage-2 estiver desligada, o chamador
    ///               deve usar {@link #translateForAddressTranslate} em vez deste método
    /// @throws MemoryTranslationException64 em falha de stage-1 (`isStage2()==false`) ou stage-2
    ///         (`isStage2()==true`) — capturada por quem chama, nunca propagada como abort real
    public long translateForAddressTranslateStage12(long va, MemoryAccessType type, boolean unprivileged,
            Stage2TranslatingAddressSpace64 stage2) {
        long ipa = translateForAddressTranslate(va, type, unprivileged);
        return stage2.translate(ipa, type);
    }

    /// Barramento físico por trás desta tradução — usado por
    /// {@link Aarch64VmsaSystemRegisters} para construir o {@link Stage2TranslatingAddressSpace64}
    /// (B10.8), que traduz sobre o MESMO físico, sem envolver este wrapper de stage-1.
    public AddressSpace64 physicalAddressSpace() {
        return physical;
    }

    private static int indexShift(int level) {
        return switch (level) {
            case LEVEL_L0 -> L0_SHIFT;
            case LEVEL_L1 -> L1_SHIFT;
            case LEVEL_L2 -> L2_SHIFT;
            case LEVEL_L3 -> L3_SHIFT;
            default -> throw new IllegalArgumentException("nível de page-walk inválido: " + level);
        };
    }

    private WalkResult walk(long va, MemoryAccessType type) {
        walkCount++;
        long tableBase = ((va >>> VA_TTBR_SELECT_BIT) & 1L) != 0 ? ttbr1Base : ttbr0Base;
        for (int level = LEVEL_L0; level <= LEVEL_L3; level++) {
            int index = (int) ((va >>> indexShift(level)) & LEVEL_INDEX_MASK);
            long descriptor = physical.read64(tableBase + index * (long) DESCRIPTOR_SIZE_BYTES);
            if ((descriptor & DESC_VALID_BIT) == 0) {
                throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(level));
            }
            boolean tableOrPage = (descriptor & DESC_TABLE_OR_PAGE_BIT) != 0;
            if (level == LEVEL_L3) {
                if (!tableOrPage) {
                    // Bit1=0 em L3 não é um tipo de descritor válido (só existe página terminal).
                    throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(LEVEL_L3));
                }
                return leaf(va, type, descriptor, LEVEL_L3, TABLE_OR_PAGE_ADDRESS_MASK, PAGE_OFFSET_MASK);
            }
            if (!tableOrPage) {
                if (level == LEVEL_L0) {
                    // Bloco não é permitido em L0 para granule 4KiB (cobriria 512GiB) — reservado.
                    throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(LEVEL_L0));
                }
                long blockAddressMask = level == LEVEL_L1 ? BLOCK_L1_ADDRESS_MASK : BLOCK_L2_ADDRESS_MASK;
                long blockOffsetMask = level == LEVEL_L1 ? BLOCK_L1_OFFSET_MASK : BLOCK_L2_OFFSET_MASK;
                return leaf(va, type, descriptor, level, blockAddressMask, blockOffsetMask);
            }
            tableBase = descriptor & TABLE_OR_PAGE_ADDRESS_MASK;
        }
        throw new IllegalStateException("page-walk não terminou em nenhum nível (bug interno)");
    }

    private WalkResult leaf(long va, MemoryAccessType type, long descriptor, int level, long addressMask,
            long offsetMask) {
        int ap = (int) ((descriptor >>> AP_FIELD_SHIFT) & AP_FIELD_MASK);
        boolean pxn = (descriptor & PXN_BIT) != 0;
        boolean uxn = (descriptor & UXN_BIT) != 0;
        checkAccess(va, type, ap, pxn, uxn, level);
        long physicalAddress = (descriptor & addressMask) | (va & offsetMask);
        return new WalkResult(physicalAddress, ap, pxn, uxn, level);
    }

    private void checkAccess(long va, MemoryAccessType type, int ap, boolean pxn, boolean uxn, int level) {
        boolean elZeroAllowed = (ap & AP_EL0_ACCESS_BIT) != 0;
        boolean readOnly = (ap & AP_READ_ONLY_BIT) != 0;
        boolean allowed = switch (type) {
            case INSTRUCTION_FETCH -> privileged ? !pxn : (elZeroAllowed && !uxn);
            case DATA_READ -> privileged || elZeroAllowed;
            case DATA_WRITE -> !readOnly && (privileged || elZeroAllowed);
        };
        if (!allowed) {
            throw new MemoryTranslationException64(va, type, FaultStatus64.permissionFault(level));
        }
    }

    /// Resultado de um page-walk: endereço físico já resolvido para o VA pedido, mais os
    /// atributos (`AP`/`PXN`/`UXN`/nível) que a micro-TLB guarda para reavaliar permissão em
    /// HITs futuros sem reler as tabelas de página.
    private record WalkResult(long physicalAddress, int ap, boolean pxn, boolean uxn, int level) {
    }

    /// Micro-TLB direto-mapeada de 256 entradas, granularidade fixa de 4KiB — mesma estrutura do
    /// precedente 32-bit (`TranslatingAddressSpace.MicroTlb`), mas com chave de VA de 64 bits e
    /// atributos `AP`/`PXN`/`UXN`/nível em vez de `AP`/domínio/seção (VMSA64 não tem domínios).
    private static final class MicroTlb64 {
        static final int MISS = -1;
        private static final long INVALID_TAG = -1L; // vpn nunca é negativo (VA de 48 bits, top bits zero)

        private final long[] tagVpn = new long[TLB_ENTRIES];
        private final int[] tagAsid = new int[TLB_ENTRIES];
        private final long[] ppn = new long[TLB_ENTRIES];
        private final int[] ap = new int[TLB_ENTRIES];
        private final boolean[] pxn = new boolean[TLB_ENTRIES];
        private final boolean[] uxn = new boolean[TLB_ENTRIES];
        private final int[] level = new int[TLB_ENTRIES];

        MicroTlb64() {
            java.util.Arrays.fill(tagVpn, INVALID_TAG);
        }

        int lookup(long vpn, int currentAsid) {
            int idx = (int) (vpn & TLB_INDEX_MASK);
            if (tagVpn[idx] == vpn && tagAsid[idx] == currentAsid) {
                return idx;
            }
            return MISS;
        }

        int fill(long vpn, int currentAsid, long ppnValue, int apValue, boolean pxnValue, boolean uxnValue,
                int levelValue) {
            int idx = (int) (vpn & TLB_INDEX_MASK);
            tagVpn[idx] = vpn;
            tagAsid[idx] = currentAsid;
            ppn[idx] = ppnValue;
            ap[idx] = apValue;
            pxn[idx] = pxnValue;
            uxn[idx] = uxnValue;
            level[idx] = levelValue;
            return idx;
        }

        long ppn(int idx) {
            return ppn[idx];
        }

        int ap(int idx) {
            return ap[idx];
        }

        boolean pxn(int idx) {
            return pxn[idx];
        }

        boolean uxn(int idx) {
            return uxn[idx];
        }

        int level(int idx) {
            return level[idx];
        }

        void invalidateAll() {
            java.util.Arrays.fill(tagVpn, INVALID_TAG);
        }

        void invalidateEntry(long vpn) {
            int idx = (int) (vpn & TLB_INDEX_MASK);
            if (tagVpn[idx] == vpn) {
                tagVpn[idx] = INVALID_TAG;
            }
        }
    }
}
