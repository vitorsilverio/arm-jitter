package dev.vitorsilverio.armjitter.core;

/// Gancho do hospedeiro para forçar `CPSR.E` na entrada de qualquer exceção — mesmo padrão
/// aditivo de {@link ModeChangeListener}/{@link MemoryAbortListener}: vazio por padrão
/// ({@link #NONE}), instalado via {@link ArmCore#setExceptionEndiannessPolicy} sem mudar nenhuma
/// assinatura de construtor (G3).
///
/// Existe por causa de um achado real do boot do Linux no `virtual-arm-box` (task F3,
/// `--machine=raspi1`): o ARM ARM (DDI 0406C B1.8.3) exige que hardware real reprograme
/// `CPSR.E` para o valor de `SCTLR.EE` em TODA entrada de exceção, **independente** do que o
/// código interrompido tinha configurado via `SETEND` — isso garante que o handler de exceção
/// rode numa endianness conhecida mesmo interrompendo um trecho legitimamente rodando com
/// `SETEND BE` (confirmado ao vivo: o kernel Linux real usa `SETEND BE`/`SETEND LE` em pares ao
/// redor de uma rotina perto do laço ocioso, e uma IRQ de timer pode chegar bem no meio). Sem
/// este gancho, `AProfileExceptionModel#enterException` deixava `CPSR.E` como estava — herdado
/// do contexto interrompido —, então o próprio `vector_stub` de IRQ (que busca sua tabela de
/// branch com um `LDR` de dados comum) lia o endereço de destino com os 4 bytes invertidos,
/// pulando para lixo e travando o boot num laço de aborts.
///
/// Cores sem MMU/CP15 instalado (GBA/NDS/user-mode `armbox`) preservam o comportamento antigo
/// (`CPSR.E` nunca tocado pela entrada de exceção) — `Cp15VmsaCoprocessor` é quem implementa esta
/// interface, forçando `SCTLR.EE`.
public interface ExceptionEndiannessPolicy {
    /// Sem host interessado: `CPSR.E` não é tocado na entrada de exceção (comportamento anterior
    /// a esta interface).
    ExceptionEndiannessPolicy NONE = cpsr -> {
    };

    /// Chamado por {@link AProfileExceptionModel#enterException} depois que o `CPSR` antigo já
    /// foi capturado em `SPSR` (que preserva o `E` real do contexto interrompido) mas antes do PC
    /// saltar para o vetor — pode reprogramar `CPSR.E` livremente.
    ///
    /// @param cpsr CPSR já no modo de destino da exceção, ainda não commitado ao vetor
    void applyOnExceptionEntry(CpsrRegister cpsr);
}
