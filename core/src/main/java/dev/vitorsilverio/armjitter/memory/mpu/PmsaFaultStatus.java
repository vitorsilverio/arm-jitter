package dev.vitorsilverio.armjitter.memory.mpu;

/// Código de status de falha (`FS`) do formato **PMSAv7**, usado para preencher `DFSR`/`IFSR`
/// quando {@link PmsaAddressSpace} nega um acesso (B20.3). **Enum irmão** de
/// {@link dev.vitorsilverio.armjitter.memory.mmu.FaultStatus} (VMSA) — nunca uma extensão dele: os
/// dois formatos são incompatíveis (PMSA não tem "translation fault" nem "domain fault", os
/// códigos `0x5`/`0x7`/`0x9`/`0xB` do VMSA não existem aqui).
///
/// Valores confirmados na tabela "PMSAv7" da base de conhecimento da SEGGER
/// (`kb.segger.com/Cortex-A/R_Fault`). Só os três códigos que {@link PmsaAddressSpace} realmente
/// produz estão modelados — a tabela completa do manual também lista `DEBUG_EVENT`/
/// `SYNC_EXTERNAL_ABORT`/`ASYNC_EXTERNAL_ABORT`/`PARITY_ERROR` (síncrono e assíncrono), nenhum dos
/// quais este projeto pode gerar hoje (sem watchpoint, sem barramento externo com falha, sem
/// paridade modelada) — omitidos em vez de virarem valores mortos nunca exercitados.
public enum PmsaFaultStatus {
    /// Nenhuma região da MPU casou com o endereço e a região de fundo (`SCTLR.BR`) não se aplica
    /// (desabilitada, ou o acesso é de modo usuário — background NUNCA vale para usuário).
    BACKGROUND(0b00000),
    /// Falha de alinhamento (`SCTLR.A`). **Nunca produzido hoje**: nenhum preset do projeto modela
    /// `SCTLR.A` (o alinhamento é tratado por `ArmFeature#UNALIGNED_ACCESS`, ortogonal a PMSA) —
    /// mantido no enum como o código real do manual, documentado como pendência nomeada (B20.3,
    /// "Não inclui") em vez de omitido.
    ALIGNMENT(0b00001),
    /// A MPU casou uma região (ou a de fundo, em modo privilegiado), mas os bits `AP`/`XN`
    /// resolvidos para o modo atual negam o tipo de acesso pedido.
    PERMISSION(0b01101);

    private final int code;

    PmsaFaultStatus(int code) {
        this.code = code;
    }

    /// Valor de 5 bits do `FS` de PMSA. **Nenhum dos três códigos modelados aqui tem o bit 4
    /// ligado** (todos `< 0b10000`), então quem compõe `DFSR`/`IFSR` (`ArmCore#enterPmsaAbort`)
    /// grava este valor inteiro em `FS[3:0]` sem precisar desdobrar o bit 4 para `DFSR[10]` (só
    /// necessário para os códigos de abort externo/paridade, que este enum não modela — ver Javadoc
    /// da classe). Se um código `>= 0b10000` for adicionado no futuro, o encoder de `DFSR`/`IFSR`
    /// PRECISA mudar para desdobrar `FS[4]` em `DFSR[10]` (ARM DDI 0406C, tabela B3-9).
    public int code() {
        return code;
    }
}
