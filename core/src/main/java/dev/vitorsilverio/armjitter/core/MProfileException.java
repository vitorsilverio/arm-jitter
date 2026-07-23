package dev.vitorsilverio.armjitter.core;

/// Números de exceção arquiteturais fixos do perfil M (ARMv7-M ARM, tabela B1-4). As externas
/// (IRQ0+, número 16 em diante) não têm constante própria aqui — chegam por
/// {@link MProfileExceptionModel#enterException(ArmCore, MProfileException)} indiretamente só
/// quando o NVIC (B7.3) as disparar; nenhum caminho desta task (B7.2) as produz.
public enum MProfileException {
    /// Reset. Não modelado por {@link MProfileExceptionModel#enterException(ArmCore, MProfileException)}
    /// nesta task — entrada de reset não empilha frame algum, carrega MSP/PC direto dos 2
    /// primeiros words do vetor (B7.5/boot).
    RESET(1),
    /// Non-maskable interrupt.
    NMI(2),
    /// Hard fault (fallback de qualquer falha sem handler próprio habilitado).
    HARD_FAULT(3),
    /// Falha de MPU (não modelada — sem MPU neste emulador).
    MEM_MANAGE(4),
    /// Falha de barramento.
    BUS_FAULT(5),
    /// Falha de uso: instrução indefinida, tentativa de estado ARM, `UNALIGNED`, `DIVBYZERO`, etc.
    USAGE_FAULT(6),
    /// `SVC` (supervisor call), equivalente M-profile do `SWI` clássico.
    SVCALL(11),
    /// Monitor de debug.
    DEBUG_MONITOR(12),
    /// `PendSV`, disparado por software para trocas de contexto adiadas.
    PENDSV(14),
    /// SysTick.
    SYSTICK(15);

    private final int number;

    MProfileException(int number) {
        this.number = number;
    }

    /// Número arquitetural da exceção (índice na tabela de vetores, `IPSR` quando ativa).
    public int number() {
        return number;
    }
}
