package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.MemoryAbortListener;
import dev.vitorsilverio.armjitter.core.ModeChangeListener;

/// CP15 VMSA (ARM DDI 0100I, ARMv6): liga as instruções `MCR`/`MRC` que o software convencional
/// (kernel Linux `versatile_defconfig`, RFC-SOFTMMU) usa para configurar a MMU aos controles
/// expostos por {@link TranslatingAddressSpace} (B4.1.1). Composição, não herança (RFC-SOFTMMU
/// decisão 4): o hospedeiro instala `new Cp15VmsaCoprocessor(mmu, core)` via
/// {@link ArmCore#setCoprocessorBus}; quem precisar de outros registradores CP15 (TCM, cache real
/// etc.) encadeia outro {@link CoprocessorBus} decorator na frente, igual ao precedente do
/// `ArmboxCp15` do armbox.
///
/// Registradores atendidos (todos `opcode1=0`, únicos válidos para VMSA):
/// - `SCTLR` (`c1,c0,0`): só os bits `M` (bit 0, habilita a MMU) e `V` (bit 13, vetores altos) têm
///   efeito — sincronizados com {@link TranslatingAddressSpace#setMmuEnabled} e
///   {@link ArmCore#setHighVectors}; qualquer outro bit é RAZ/WI (não modelado nesta fase: cache,
///   alinhamento, etc.), como a RFC documenta.
/// - `TTBR0`/`TTBR1`/`TTBCR` (`c2,c0,{0,1,2}`): `TTBR0` liga em
///   {@link TranslatingAddressSpace#setTtbr0}; `TTBR1`/`TTBCR` só armazenados para leitura de
///   volta — o wrapper (B4.1.1) sempre faz o walk a partir de `TTBR0` (assume `TTBCR.N=0`, sem
///   split de tabelas), então escrevê-los não muda a tradução nesta fase.
/// - `DACR` (`c3,c0,0`): liga em {@link TranslatingAddressSpace#setDacr}.
/// - `DFSR`/`IFSR` (`c5,c0,{0,1}`) e `DFAR`/`IFAR` (`c6,c0,{0,2}`): leitura/escrita normal por
///   `MCR`/`MRC` (software pode reler o que um abort real gravou) E gravados de verdade a cada
///   abort via {@link MemoryAbortListener#onDataAbort}/{@link MemoryAbortListener#onPrefetchAbort}
///   (B4.1.3) — o host precisa registrar `core.setMemoryAbortListener(this)` além de
///   `core.setCoprocessorBus(this)` para que isto aconteça (dois ganchos independentes do mesmo
///   `ArmCore`, mesmo padrão aditivo).
/// - `c7` (manutenção de cache + barreiras `ISB`/`DSB`/`DMB`): NOP observável em toda escrita (sem
///   cache nem pipeline modelados) e RAZ em leitura — mesmo precedente do `IrOp.MemoryBarrier`
///   32 bits.
/// - `c8,c{5,6,7},{0,1,2}` (TLB de instrução / de dados / unificada): `TLBIALL`/`TLBIMVA`/
///   `TLBIASID`, repassados às faces correspondentes da micro-TLB do wrapper
///   ({@link TranslatingAddressSpace#invalidateInstructionTlbAll} e irmãs). As formas separadas
///   `c8,c5,*`/`c8,c6,*` NÃO são opcionais na prática: um core com TLBs separadas (ARM926EJ-S /
///   ARMv5, o `v4wbi_*` do Linux) nunca emite a forma unificada — sem elas todo `flush_tlb_*` do
///   kernel vira exceção UNDEFINED (achado real do boot do linuxbox, B4.1.5).
/// - `CONTEXTIDR` (`c13,c0,1`): os 8 bits baixos (ASID) ligam em
///   {@link TranslatingAddressSpace#setAsid}; o valor completo é guardado para leitura de volta.
/// - `FCSEIDR`/`TPIDRURW`/`TPIDRURO`/`TPIDRPRW` (`c13,c0,{0,2,3,4}`, ARMv6+ "Process ID/Thread ID
///   registers"): puro armazenamento, sem efeito colateral (nenhum modela FCSE real nem TLS de
///   verdade nesta fase) — apenas para não lançar UNDEFINED. **Achado real da F3/sessão 2**: o
///   `MCR p15,0,Rt,c13,c0,3` (`TPIDRURO`, escrita do ponteiro de TLS da thread) é uma das
///   PRIMEIRAS instruções que o kernel Linux ARMv6K real executa (bem antes de `setup_arch()`,
///   com os vetores de exceção ainda não copiados por `early_trap_init()`) — sem este suporte,
///   `raspi1` (F3, primeira validação de sistema real do `ARM11_MPCORE`/ARMv6K) trava num laço
///   infinito de PREFETCH_ABORT: UNDEFINED por `c13,c0,3` não implementado → entra no vetor
///   UNDEF → busca da PRÓPRIA rotina de vetor falha (ainda não copiada) → PREFETCH_ABORT
///   recorrente no mesmo endereço, indefinidamente.
///
/// - **Modo privilegiado vs. usuário** (B4.1.5): esta classe também implementa
///   {@link ModeChangeListener} e repassa cada troca de modo do core para
///   {@link TranslatingAddressSpace#setPrivileged}, porque os bits `AP` da tabela de páginas
///   tratam os dois casos de forma diferente. O host precisa registrar
///   `core.setModeChangeListener(this)` (terceiro gancho independente do mesmo `ArmCore`, junto
///   de `setCoprocessorBus`/`setMemoryAbortListener`) — sem ele todo acesso é privilegiado e o
///   *copy-on-write* do `fork()` do Linux nunca falta, corrompendo pai e filho (ver
///   {@link ModeChangeListener}).
public final class Cp15VmsaCoprocessor implements CoprocessorBus, MemoryAbortListener, ModeChangeListener {
    private static final int CP15 = 15;

    private static final int CRN_SYSTEM_CONTROL = 1;
    private static final int CRN_TRANSLATION_TABLE = 2;
    private static final int CRN_DOMAIN_ACCESS_CONTROL = 3;
    private static final int CRN_FAULT_STATUS = 5;
    private static final int CRN_FAULT_ADDRESS = 6;
    private static final int CRN_CACHE_MAINTENANCE = 7;
    private static final int CRN_TLB_MAINTENANCE = 8;
    private static final int CRN_CONTEXT = 13;

    private static final int CRM_PRIMARY = 0;

    private static final int OPCODE2_SCTLR = 0;

    private static final int OPCODE2_TTBR0 = 0;
    private static final int OPCODE2_TTBR1 = 1;
    private static final int OPCODE2_TTBCR = 2;

    private static final int OPCODE2_DACR = 0;

    private static final int OPCODE2_DFSR = 0;
    private static final int OPCODE2_IFSR = 1;

    private static final int OPCODE2_DFAR = 0;
    /// ARM DDI 0100I §B4.1.51: `IFAR` usa `opcode2=2` (não `1` — esse é `IFSR`, registrador
    /// diferente em `c6` vs `c5`).
    private static final int OPCODE2_IFAR = 2;

    /// `c8,c5,*` — TLB de INSTRUÇÃO (cores com TLBs separadas: ARM926EJ-S/ARMv5, usado pelo
    /// `v4wbi_*` do Linux).
    private static final int CRM_TLB_INSTRUCTION = 5;
    /// `c8,c6,*` — TLB de DADOS (idem).
    private static final int CRM_TLB_DATA = 6;
    /// `c8,c7,*` — TLB unificada.
    private static final int CRM_TLB_UNIFIED = 7;
    private static final int OPCODE2_TLB_INVALIDATE_ALL = 0;
    private static final int OPCODE2_TLB_INVALIDATE_BY_MVA = 1;
    /// `TLBIASID` (`opcode2=2`, ARMv6): invalidar por ASID. Sem tag de ASID por entrada capaz de
    /// varredura seletiva na micro-TLB direto-mapeada, é atendido invalidando tudo — invalidar
    /// mais do que o pedido é sempre arquiteturalmente legal.
    private static final int OPCODE2_TLB_INVALIDATE_BY_ASID = 2;

    private static final int OPCODE2_FCSEIDR = 0;
    private static final int OPCODE2_CONTEXTIDR = 1;
    /// `TPIDRURW` — Thread ID register, leitura/escrita em modo usuário (ARM DDI 0100I §B4.1.157).
    private static final int OPCODE2_TPIDRURW = 2;
    /// `TPIDRURO` — Thread ID register, só leitura em modo usuário (escrita só privilegiada).
    private static final int OPCODE2_TPIDRURO = 3;
    /// `TPIDRPRW` — Thread ID register, acessível só em modo privilegiado (PL1).
    private static final int OPCODE2_TPIDRPRW = 4;

    private static final int SCTLR_M_BIT = 1;
    private static final int SCTLR_V_BIT = 1 << 13;
    private static final int CONTEXTIDR_ASID_MASK = 0xFF;

    private final TranslatingAddressSpace mmu;
    private final ArmCore core;

    private int ttbr0;
    private int ttbr1;
    private int ttbcr;
    private int dacr;
    private int dfsr;
    private int ifsr;
    private int dfar;
    private int ifar;
    private int contextIdr;
    private int fcseidr;
    private int tpidrurw;
    private int tpidruro;
    private int tpidrprw;

    /// @param mmu  wrapper (B4.1.1) que este coprocessador controla
    /// @param core core cujo `SCTLR.V` (vetores altos) este coprocessador sincroniza
    public Cp15VmsaCoprocessor(TranslatingAddressSpace mmu, ArmCore core) {
        this.mmu = mmu;
        this.core = core;
        // Reset real de hardware: MMU desligada (SCTLR.M=0) até o software habilitar.
        mmu.setMmuEnabled(false);
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor != CP15) {
            return false;
        }
        return switch (crn) {
            case CRN_SYSTEM_CONTROL -> crm == CRM_PRIMARY && opcode2 == OPCODE2_SCTLR;
            case CRN_TRANSLATION_TABLE -> crm == CRM_PRIMARY
                    && (opcode2 == OPCODE2_TTBR0 || opcode2 == OPCODE2_TTBR1 || opcode2 == OPCODE2_TTBCR);
            case CRN_DOMAIN_ACCESS_CONTROL -> crm == CRM_PRIMARY && opcode2 == OPCODE2_DACR;
            case CRN_FAULT_STATUS -> crm == CRM_PRIMARY && (opcode2 == OPCODE2_DFSR || opcode2 == OPCODE2_IFSR);
            case CRN_FAULT_ADDRESS -> crm == CRM_PRIMARY && (opcode2 == OPCODE2_DFAR || opcode2 == OPCODE2_IFAR);
            case CRN_CACHE_MAINTENANCE -> true;
            case CRN_TLB_MAINTENANCE ->
                    (crm == CRM_TLB_UNIFIED || crm == CRM_TLB_INSTRUCTION || crm == CRM_TLB_DATA)
                            && (opcode2 == OPCODE2_TLB_INVALIDATE_ALL
                            || opcode2 == OPCODE2_TLB_INVALIDATE_BY_MVA
                            || opcode2 == OPCODE2_TLB_INVALIDATE_BY_ASID);
            case CRN_CONTEXT -> crm == CRM_PRIMARY
                    && (opcode2 == OPCODE2_FCSEIDR || opcode2 == OPCODE2_CONTEXTIDR
                    || opcode2 == OPCODE2_TPIDRURW || opcode2 == OPCODE2_TPIDRURO
                    || opcode2 == OPCODE2_TPIDRPRW);
            default -> false;
        };
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return switch (crn) {
            case CRN_SYSTEM_CONTROL -> sctlrValue();
            case CRN_TRANSLATION_TABLE -> switch (opcode2) {
                case OPCODE2_TTBR0 -> ttbr0;
                case OPCODE2_TTBR1 -> ttbr1;
                default -> ttbcr;
            };
            case CRN_DOMAIN_ACCESS_CONTROL -> dacr;
            case CRN_FAULT_STATUS -> opcode2 == OPCODE2_DFSR ? dfsr : ifsr;
            case CRN_FAULT_ADDRESS -> opcode2 == OPCODE2_DFAR ? dfar : ifar;
            case CRN_CACHE_MAINTENANCE -> 0; // RAZ: manutenção de cache/barreira não tem estado legível.
            case CRN_TLB_MAINTENANCE -> 0; // RAZ: operações de TLB são write-only em hardware real.
            case CRN_CONTEXT -> switch (opcode2) {
                case OPCODE2_FCSEIDR -> fcseidr;
                case OPCODE2_TPIDRURW -> tpidrurw;
                case OPCODE2_TPIDRURO -> tpidruro;
                case OPCODE2_TPIDRPRW -> tpidrprw;
                default -> contextIdr;
            };
            default -> throw unsupported(crn, crm, opcode2);
        };
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        switch (crn) {
            case CRN_SYSTEM_CONTROL -> applySctlr(value);
            case CRN_TRANSLATION_TABLE -> writeTranslationTable(opcode2, value);
            case CRN_DOMAIN_ACCESS_CONTROL -> {
                dacr = value;
                mmu.setDacr(value);
            }
            case CRN_FAULT_STATUS -> {
                if (opcode2 == OPCODE2_DFSR) {
                    dfsr = value;
                } else {
                    ifsr = value;
                }
            }
            case CRN_FAULT_ADDRESS -> {
                if (opcode2 == OPCODE2_DFAR) {
                    dfar = value;
                } else {
                    ifar = value;
                }
            }
            case CRN_CACHE_MAINTENANCE -> {
                // NOP observável: sem cache nem pipeline modelados, toda manutenção de cache e
                // toda barreira (ISB/DSB/DMB, mesmo subgrupo c7) já é satisfeita por não fazer nada.
            }
            case CRN_TLB_MAINTENANCE -> invalidateTlb(crm, opcode2, value);
            case CRN_CONTEXT -> {
                switch (opcode2) {
                    case OPCODE2_FCSEIDR -> fcseidr = value;
                    case OPCODE2_TPIDRURW -> tpidrurw = value;
                    case OPCODE2_TPIDRURO -> tpidruro = value;
                    case OPCODE2_TPIDRPRW -> tpidrprw = value;
                    default -> {
                        contextIdr = value;
                        mmu.setAsid(value & CONTEXTIDR_ASID_MASK);
                    }
                }
            }
            default -> throw unsupported(crn, crm, opcode2);
        }
    }

    /// Despacha `c8,c{5,6,7},{0,1,2}` para a face certa da micro-TLB do wrapper. `TLBIMVA`
    /// (`opcode2=1`) recebe a MVA em `value`; `TLBIALL`/`TLBIASID` ignoram o valor escrito.
    private void invalidateTlb(int crm, int opcode2, int value) {
        boolean byMva = opcode2 == OPCODE2_TLB_INVALIDATE_BY_MVA;
        switch (crm) {
            case CRM_TLB_INSTRUCTION -> {
                if (byMva) {
                    mmu.invalidateInstructionTlbByMva(value);
                } else {
                    mmu.invalidateInstructionTlbAll();
                }
            }
            case CRM_TLB_DATA -> {
                if (byMva) {
                    mmu.invalidateDataTlbByMva(value);
                } else {
                    mmu.invalidateDataTlbAll();
                }
            }
            default -> {
                if (byMva) {
                    mmu.invalidateTlbByMva(value);
                } else {
                    mmu.invalidateTlbAll();
                }
            }
        }
    }

    private void writeTranslationTable(int opcode2, int value) {
        switch (opcode2) {
            case OPCODE2_TTBR0 -> {
                ttbr0 = value;
                mmu.setTtbr0(value);
            }
            case OPCODE2_TTBR1 -> ttbr1 = value;
            default -> ttbcr = value;
        }
    }

    private void applySctlr(int value) {
        mmu.setMmuEnabled((value & SCTLR_M_BIT) != 0);
        core.setHighVectors((value & SCTLR_V_BIT) != 0);
    }

    private int sctlrValue() {
        int value = 0;
        if (mmu.mmuEnabled()) {
            value |= SCTLR_M_BIT;
        }
        if (core.highVectors()) {
            value |= SCTLR_V_BIT;
        }
        return value;
    }

    @Override
    public void onModeChanged(CpuMode mode) {
        mmu.setPrivileged(mode != CpuMode.USER);
    }

    @Override
    public void onDataAbort(int faultAddress, int faultStatus) {
        dfar = faultAddress;
        dfsr = faultStatus;
    }

    @Override
    public void onPrefetchAbort(int faultAddress, int faultStatus) {
        ifar = faultAddress;
        ifsr = faultStatus;
    }

    private static IllegalStateException unsupported(int crn, int crm, int opcode2) {
        return new IllegalStateException(
                "bug: executor não consultou handles fino: crn=c%d crm=c%d opcode2=%d".formatted(crn, crm, opcode2));
    }
}
