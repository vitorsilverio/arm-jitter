package dev.vitorsilverio.armjitter.core;

/// Gancho do hospedeiro para preencher `DFAR`/`DFSR`/`IFAR`/`IFSR` do CP15 quando o `ArmCore`
/// converte uma {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException} numa
/// entrada de exceção `DATA_ABORT`/`PREFETCH_ABORT` (B4.1.3, RFC-SOFTMMU §3) — mesmo padrão
/// aditivo de {@link dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus}/{@link BkptDispatcher}:
/// vazio por padrão ({@link #NONE}),
/// instalado pelo host via {@link ArmCore#setMemoryAbortListener} sem exigir mudança de assinatura
/// de construtor (G3). `Cp15VmsaCoprocessor` implementa esta interface.
public interface MemoryAbortListener {
    /// Sem CP15 instalado: FAR/FSR são descartados (comportamento pré-B4.1.3 preservado).
    MemoryAbortListener NONE = new MemoryAbortListener() {
        @Override public void onDataAbort(int faultAddress, int faultStatus) {
        }

        @Override public void onPrefetchAbort(int faultAddress, int faultStatus) {
        }
    };

    /// Chamado antes da entrada em `ArmException.DATA_ABORT`: deve gravar `DFAR`/`DFSR`.
    void onDataAbort(int faultAddress, int faultStatus);

    /// Chamado antes da entrada em `ArmException.PREFETCH_ABORT`: deve gravar `IFAR`/`IFSR`.
    void onPrefetchAbort(int faultAddress, int faultStatus);
}
