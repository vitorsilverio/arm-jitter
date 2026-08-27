package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// Stage-1 dos regimes de tradução EL2 e EL3 (`ARM DDI 0487 D8.3`, tasks B10.6b/B10.6c) — MESMA
/// geometria de granule 4KiB/PA-VA de 48 bits/4 níveis L0-L3 de {@link TranslatingAddressSpace64}
/// (regime EL1&amp;0), mas com o formato de permissão mais simples que o manual real define para
/// EL2/EL3 (sem VHE modelado): esses regimes não distinguem EL0/EL1 dentro de si mesmos (EL2 e EL3
/// não têm um "EL0 companheiro" sem `HCR_EL2.E2H`, que este emulador não modela), então o
/// descritor usa só `AP[2]` (bit `[7]`, somente-leitura) — `AP[1]` (bit `[6]`) é `RES0`, não a
/// distinção EL0/privilegiado do regime EL1&amp;0 — e um único bit `XN` (bit `54`, execute-never),
/// sem o par `PXN`/`UXN` de {@link TranslatingAddressSpace64} (não existe "não-privilegiado" para
/// diferenciar).
///
/// Cada instância representa UM regime (EL2 OU EL3) — dois objetos distintos em
/// {@link Aarch64VmsaSystemRegisters}, cada um com seu próprio `TTBR0_ELx` (`Aarch64SystemRegisterId#TTBR0_EL2`/
/// `TTBR0_EL3`, tasks B10.6b/B10.6c — nenhum dos dois existia antes destas tasks). Único consumidor
/// real hoje: `AT S1E2R`/`S1E2W`/`S1E3R`/`S1E3W` via {@link Aarch64VmsaSystemRegisters#addressTranslate}
/// — nenhum acesso de dados/instrução de guest é traduzido por esta classe (mesma disciplina "só a
/// instrução AT usa isto" já documentada em {@link Stage2TranslatingAddressSpace64}).
///
/// ### Fora de escopo (não fazer)
/// `TTBR1_ELx` (EL2/EL3 não têm espaço de endereço alto separado sem VHE — um único TTBR0 cobre o
/// regime inteiro), `ASID` (EL2/EL3 não têm múltiplos espaços de endereço concorrentes modelados),
/// micro-TLB (efeito mínimo, mesmo raciocínio de {@code AT} não popular TLB já aplicado ao regime
/// EL1&amp;0 e à stage-2), `VHE`/`HCR_EL2.E2H` (regime EL2&amp;0 alternativo, não modelado).
public final class Aarch64PrivilegedStage1TranslatingAddressSpace64 {

    // ── geometria (idêntica à de TranslatingAddressSpace64/Stage2TranslatingAddressSpace64) ──────
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

    // ── layout do descritor para os regimes EL2/EL3 (ARM DDI 0487 D8.3) ─────────────────────────
    private static final long DESC_VALID_BIT = 0b1L;
    private static final long DESC_TABLE_OR_PAGE_BIT = 0b10L;

    /// `AP[2]` (bit `[7]`): `1` = somente-leitura. `AP[1]` (bit `[6]`) é `RES0` nestes regimes
    /// (sem distinção EL0/EL1 — ver javadoc da classe), por isso não há campo `AP_FIELD_MASK` de 2
    /// bits aqui, ao contrário de {@link TranslatingAddressSpace64}.
    private static final long AP_READ_ONLY_BIT = 1L << 7;

    /// `XN` único (bit `54`) — MESMA posição de {@code UXN} no regime EL1&amp;0, mas aqui é o ÚNICO
    /// bit de execute-never (sem `PXN` separado, bit `53` é `RES0` nestes regimes).
    private static final long XN_BIT = 1L << 54;

    private final AddressSpace64 physical;
    private long ttbr0Base;
    private long walkCount;

    /// @param physical barramento físico por trás da tradução — MESMO físico usado pela stage-1
    ///                  EL1&amp;0 ({@link TranslatingAddressSpace64#physicalAddressSpace()})
    public Aarch64PrivilegedStage1TranslatingAddressSpace64(AddressSpace64 physical) {
        this.physical = Objects.requireNonNull(physical, "physical");
    }

    /// Define `TTBR0_EL2` ou `TTBR0_EL3` (conforme o regime desta instância): base da tabela L0,
    /// mascarada para bits `[47:12]` (alinhamento de 4KiB). Sem `ASID` embutido (EL2/EL3 não têm
    /// múltiplos espaços de endereço concorrentes modelados — diferente de
    /// {@link TranslatingAddressSpace64#setTtbr0}).
    public void setTtbr0(long ttbr0) {
        this.ttbr0Base = ttbr0 & TABLE_OR_PAGE_ADDRESS_MASK;
    }

    /// Quantidade de page-walks executados desde a criação (sem micro-TLB, sobe a cada chamada de
    /// {@link #translate}).
    public long pageWalkCount() {
        return walkCount;
    }

    /// Traduz `va` (endereço virtual do regime EL2 ou EL3, conforme a instância) para o endereço
    /// físico final. Único chamador real: {@link Aarch64VmsaSystemRegisters#addressTranslate} via
    /// `AT S1E2R`/`S1E2W`/`S1E3R`/`S1E3W`.
    ///
    /// @throws MemoryTranslationException64 em falha de tradução ou permissão — capturada por quem
    ///         chama, NUNCA propagada como abort real (`AT` não gera exceção síncrona para o guest)
    public long translate(long va, MemoryAccessType type) {
        walkCount++;
        long tableBase = ttbr0Base;
        for (int level = LEVEL_L0; level <= LEVEL_L3; level++) {
            int index = (int) ((va >>> indexShift(level)) & LEVEL_INDEX_MASK);
            long descriptor = physical.read64(tableBase + index * (long) DESCRIPTOR_SIZE_BYTES);
            if ((descriptor & DESC_VALID_BIT) == 0) {
                throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(level));
            }
            boolean tableOrPage = (descriptor & DESC_TABLE_OR_PAGE_BIT) != 0;
            if (level == LEVEL_L3) {
                if (!tableOrPage) {
                    throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(LEVEL_L3));
                }
                return leaf(va, type, descriptor, LEVEL_L3, TABLE_OR_PAGE_ADDRESS_MASK, PAGE_OFFSET_MASK);
            }
            if (!tableOrPage) {
                if (level == LEVEL_L0) {
                    throw new MemoryTranslationException64(va, type, FaultStatus64.translationFault(LEVEL_L0));
                }
                long blockAddressMask = level == LEVEL_L1 ? BLOCK_L1_ADDRESS_MASK : BLOCK_L2_ADDRESS_MASK;
                long blockOffsetMask = level == LEVEL_L1 ? BLOCK_L1_OFFSET_MASK : BLOCK_L2_OFFSET_MASK;
                return leaf(va, type, descriptor, level, blockAddressMask, blockOffsetMask);
            }
            tableBase = descriptor & TABLE_OR_PAGE_ADDRESS_MASK;
        }
        throw new IllegalStateException("page-walk EL2/EL3 não terminou em nenhum nível (bug interno)");
    }

    private long leaf(long va, MemoryAccessType type, long descriptor, int level, long addressMask,
            long offsetMask) {
        boolean readOnly = (descriptor & AP_READ_ONLY_BIT) != 0;
        boolean xn = (descriptor & XN_BIT) != 0;
        checkAccess(va, type, readOnly, xn, level);
        return (descriptor & addressMask) | (va & offsetMask);
    }

    /// Permissão dos regimes EL2/EL3: leitura sempre permitida (não há EL0 a excluir), escrita
    /// negada por `readOnly`, fetch de instrução negado por `xn` — sem `PXN`/`UXN` separados (ver
    /// javadoc da classe).
    private void checkAccess(long va, MemoryAccessType type, boolean readOnly, boolean xn, int level) {
        boolean allowed = switch (type) {
            case INSTRUCTION_FETCH -> !xn;
            case DATA_READ -> true;
            case DATA_WRITE -> !readOnly;
        };
        if (!allowed) {
            throw new MemoryTranslationException64(va, type, FaultStatus64.permissionFault(level));
        }
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
