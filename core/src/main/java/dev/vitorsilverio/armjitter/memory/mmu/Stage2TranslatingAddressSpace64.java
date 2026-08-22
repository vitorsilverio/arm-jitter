package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// Stage-2 (IPA→PA, `ARM DDI 0487 D8.5`, task B10.8): traduz o Intermediate Physical Address que a
/// stage-1 (regime EL1&amp;0, {@link TranslatingAddressSpace64}) produz quando `HCR_EL2.VM=1`, para
/// as formas combinadas de `AT` (`S12E1R`/`S12E1W`/`S12E0R`/`S12E0W`, `Aarch64AddressTranslateForm`)
/// — único consumidor real hoje, ver {@link TranslatingAddressSpace64#translateForAddressTranslateStage12}.
/// MESMA geometria de granule 4KiB/48 bits/4 níveis L0-L3 de {@link TranslatingAddressSpace64}
/// (config mínima real, D2 do precedente), mas a tabela é apontada por `VTTBR_EL2` em vez de
/// `TTBR0_EL1`.
///
/// ### Simplificação explícita desta task (documentada, não é bug)
/// Os acessos às PRÓPRIAS tabelas de stage-1 (leitura dos descritores L0-L3 durante um walk de
/// {@link TranslatingAddressSpace64}) continuam lidos diretamente da memória física, SEM passar por
/// esta classe — no hardware real, esses acessos também seriam traduzidos por stage-2 (as tabelas
/// de stage-1 vivem em espaço de IPA sob um hypervisor real). Aqui, só o endereço de DADOS final
/// que a stage-1 resolve (que semanticamente É um IPA quando `HCR_EL2.VM=1`) passa por esta classe
/// — ver javadoc de {@link TranslatingAddressSpace64#translateForAddressTranslateStage12}. Mesma
/// disciplina de "escopo mínimo documentado" já aplicada a `MAIR`/granule/`TCR` em toda essa árvore
/// de classes; nenhum consumidor real deste emulador (`virtual-arm-box`/F11) roda um guest sob
/// hypervisor emulado hoje, então a distinção não é observável ainda.
///
/// ### Atributos modelados
/// `S2AP[1:0]` (bits `[7:6]`: bit baixo = leitura permitida, bit alto = escrita permitida — MESMA
/// posição de bits do `AP` de stage-1, mas semântica diferente de permissão RWX em vez de
/// EL0/somente-leitura, `ARM DDI 0487 D8.5.6`) e `XN` (bit `54`, execute-never ÚNICO — stage-2 não
/// distingue privilegiado/não-privilegiado como o `PXN`/`UXN` de stage-1, porque a IPA não carrega
/// EL de quem originou o acesso).
///
/// ### Fora de escopo (não fazer)
/// `S1E2*`/`S1E3*` (B10.6b/B10.6c, tabelas de EL2/EL3 próprias, natureza diferente de stage-2),
/// `VTTBR_EL2.VMID` (lido por {@link TranslatingAddressSpace64}/`Aarch64VmsaSystemRegisters` mas
/// ignorado aqui — um único contexto de stage-2 modelado, sem per-VM), micro-TLB (efeito mínimo,
/// mesmo raciocínio de `AT` não popular TLB nenhuma — B10.6), e qualquer acesso de dados/instrução
/// REAL do guest sob stage-2 — só a instrução `AT` usa esta classe hoje, nenhum `AddressSpace64`
/// real de um hospedeiro é composto com ela.
public final class Stage2TranslatingAddressSpace64 {

    // ── geometria (idêntica à de TranslatingAddressSpace64 — granule 4KiB, IPA/PA 48 bits, 4
    // ── níveis L0-L3) ────────────────────────────────────────────────────────────────────────
    private static final int PAGE_BITS = 12;
    private static final int LEVEL_INDEX_BITS = 9;
    private static final long LEVEL_INDEX_MASK = (1L << LEVEL_INDEX_BITS) - 1;
    private static final int DESCRIPTOR_SIZE_BYTES = 8;
    private static final int OUTPUT_ADDRESS_HIGH_BIT = 47;

    private static final int L0_SHIFT = PAGE_BITS + 3 * LEVEL_INDEX_BITS; // 39
    private static final int L1_SHIFT = PAGE_BITS + 2 * LEVEL_INDEX_BITS; // 30
    private static final int L2_SHIFT = PAGE_BITS + LEVEL_INDEX_BITS;     // 21
    private static final int L3_SHIFT = PAGE_BITS;                       // 12

    private static final int LEVEL_L0 = 0;
    private static final int LEVEL_L1 = 1;
    private static final int LEVEL_L2 = 2;
    private static final int LEVEL_L3 = 3;

    private static final long TABLE_OR_PAGE_ADDRESS_MASK = outputAddressMask(PAGE_BITS);
    private static final long BLOCK_L1_ADDRESS_MASK = outputAddressMask(L1_SHIFT);
    private static final long BLOCK_L2_ADDRESS_MASK = outputAddressMask(L2_SHIFT);
    private static final long BLOCK_L1_OFFSET_MASK = (1L << L1_SHIFT) - 1;
    private static final long BLOCK_L2_OFFSET_MASK = (1L << L2_SHIFT) - 1;
    private static final long PAGE_OFFSET_MASK = (1L << PAGE_BITS) - 1;

    private static long outputAddressMask(int lowBitInclusive) {
        long width = OUTPUT_ADDRESS_HIGH_BIT - lowBitInclusive + 1;
        return ((1L << width) - 1) << lowBitInclusive;
    }

    // ── layout do descritor de stage-2 (ARM DDI 0487 D8.5) ──────────────────────────────────
    private static final long DESC_VALID_BIT = 0b1L;
    private static final long DESC_TABLE_OR_PAGE_BIT = 0b10L;

    private static final int S2AP_FIELD_SHIFT = 6; // bits[7:6]
    private static final long S2AP_FIELD_MASK = 0b11L;
    private static final int S2AP_READ_BIT = 0b01;  // S2AP[0]: leitura permitida
    private static final int S2AP_WRITE_BIT = 0b10; // S2AP[1]: escrita permitida

    private static final long XN_BIT = 1L << 54; // execute-never único (sem PXN/UXN em stage-2)

    private final AddressSpace64 physical;
    private long vttbrBase;
    private long walkCount;

    /// @param physical barramento físico por trás da tradução — MESMO físico usado pela stage-1
    ///                  ({@link TranslatingAddressSpace64#physicalAddressSpace()}), nunca alterado
    public Stage2TranslatingAddressSpace64(AddressSpace64 physical) {
        this.physical = Objects.requireNonNull(physical, "physical");
    }

    /// Define `VTTBR_EL2`: base da tabela de stage-2 (mascarada para `[47:12]`, alinhamento de
    /// 4KiB). `VMID` em `[63:48]` é IGNORADO (sem per-VM modelado — ver "Fora de escopo" no javadoc
    /// da classe).
    public void setVttbr(long vttbr) {
        this.vttbrBase = vttbr & TABLE_OR_PAGE_ADDRESS_MASK;
    }

    /// Quantidade de page-walks de stage-2 executados desde a criação.
    public long pageWalkCount() {
        return walkCount;
    }

    /// Traduz `ipa` (Intermediate Physical Address, produzido pela stage-1) para o endereço físico
    /// final. Único chamador real: {@link TranslatingAddressSpace64#translateForAddressTranslateStage12}.
    ///
    /// @throws MemoryTranslationException64 com {@link MemoryTranslationException64#isStage2()}
    ///         {@code true} em falha de tradução ou permissão de stage-2
    public long translate(long ipa, MemoryAccessType type) {
        walkCount++;
        long tableBase = vttbrBase;
        for (int level = LEVEL_L0; level <= LEVEL_L3; level++) {
            int index = (int) ((ipa >>> indexShift(level)) & LEVEL_INDEX_MASK);
            long descriptor = physical.read64(tableBase + index * (long) DESCRIPTOR_SIZE_BYTES);
            if ((descriptor & DESC_VALID_BIT) == 0) {
                throw stage2Fault(ipa, type, FaultStatus64.translationFault(level));
            }
            boolean tableOrPage = (descriptor & DESC_TABLE_OR_PAGE_BIT) != 0;
            if (level == LEVEL_L3) {
                if (!tableOrPage) {
                    throw stage2Fault(ipa, type, FaultStatus64.translationFault(LEVEL_L3));
                }
                return leaf(ipa, type, descriptor, LEVEL_L3, TABLE_OR_PAGE_ADDRESS_MASK, PAGE_OFFSET_MASK);
            }
            if (!tableOrPage) {
                if (level == LEVEL_L0) {
                    throw stage2Fault(ipa, type, FaultStatus64.translationFault(LEVEL_L0));
                }
                long blockAddressMask = level == LEVEL_L1 ? BLOCK_L1_ADDRESS_MASK : BLOCK_L2_ADDRESS_MASK;
                long blockOffsetMask = level == LEVEL_L1 ? BLOCK_L1_OFFSET_MASK : BLOCK_L2_OFFSET_MASK;
                return leaf(ipa, type, descriptor, level, blockAddressMask, blockOffsetMask);
            }
            tableBase = descriptor & TABLE_OR_PAGE_ADDRESS_MASK;
        }
        throw new IllegalStateException("page-walk de stage-2 não terminou em nenhum nível (bug interno)");
    }

    private long leaf(long ipa, MemoryAccessType type, long descriptor, int level, long addressMask,
            long offsetMask) {
        int s2ap = (int) ((descriptor >>> S2AP_FIELD_SHIFT) & S2AP_FIELD_MASK);
        boolean xn = (descriptor & XN_BIT) != 0;
        checkAccess(ipa, type, s2ap, xn, level);
        return (descriptor & addressMask) | (ipa & offsetMask);
    }

    private void checkAccess(long ipa, MemoryAccessType type, int s2ap, boolean xn, int level) {
        boolean readAllowed = (s2ap & S2AP_READ_BIT) != 0;
        boolean writeAllowed = (s2ap & S2AP_WRITE_BIT) != 0;
        boolean allowed = switch (type) {
            case INSTRUCTION_FETCH -> readAllowed && !xn;
            case DATA_READ -> readAllowed;
            case DATA_WRITE -> writeAllowed;
        };
        if (!allowed) {
            throw stage2Fault(ipa, type, FaultStatus64.permissionFault(level));
        }
    }

    private static MemoryTranslationException64 stage2Fault(long ipa, MemoryAccessType type, FaultStatus64 status) {
        return new MemoryTranslationException64(ipa, type, status, true);
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
}
