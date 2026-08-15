package dev.vitorsilverio.armjitter.core;

/// Gancho do hospedeiro para reagir a toda troca de modo do processador (`CPSR.M`) — mesmo padrão
/// aditivo de {@link MemoryAbortListener}/{@link dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus}:
/// vazio por padrão ({@link #NONE}), instalado via {@link ArmCore#setModeChangeListener} sem mudar
/// nenhuma assinatura de construtor (G3).
///
/// Existe por causa de um achado real do boot do Linux no `linuxbox` (B4.1.5): os bits `AP` da
/// tabela de páginas distinguem acesso privilegiado de acesso de usuário, então
/// {@link dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace#setPrivileged} precisa
/// acompanhar o modo corrente. Sem isso TODO acesso é tratado como privilegiado e uma escrita de
/// usuário numa página somente-leitura (o mecanismo de *copy-on-write* do `fork()`) passa em vez
/// de gerar o `DATA_ABORT` que o kernel espera — pai e filho continuam compartilhando fisicamente
/// a mesma pilha e se corrompem mutuamente.
///
/// `Cp15VmsaCoprocessor` implementa esta interface.
public interface ModeChangeListener {
    /// Sem host interessado: trocas de modo não são observadas (comportamento pré-B4.1.5).
    ModeChangeListener NONE = mode -> {
    };

    /// Chamado sempre que {@link ArmCore} passa a executar num modo diferente do anterior — por
    /// entrada de exceção, `MSR`/`CPS`, `SUBS PC,LR`, ou configuração explícita do host.
    ///
    /// @param mode modo novo, já ativo (bancos de registradores já trocados)
    void onModeChanged(CpuMode mode);
}
