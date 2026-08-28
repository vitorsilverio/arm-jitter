package dev.vitorsilverio.armjitter.arch64;

/// Catálogo de processadores AArch64 reais nomeados, cada um resolvendo para a
/// {@link Aarch64Architecture} que ele implementa (B12.1, ver
/// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`) — conveniência para o cliente
/// da biblioteca escolher por nome comercial (`Cortex-A53`, `Neoverse N1`, ...) em vez de montar a
/// `Aarch64Architecture` na mão.
///
/// **Escopo desta task**: família Cortex-A/X/Neoverse `ARMv8.0-A`→`ARMv8.2-A` — a maior massa de
/// núcleos reais já coberta por preset existente (nenhuma feature/arquitetura nova, só a tabela de
/// resolução). O restante das versões (`ARMv8.4-A`→`ARMv9.5-A`) fica para B12.2; o catálogo
/// 32-bit/T32 (`arch.ArmProcessor`) fica para B12.3+.
///
/// **Sem uso ainda em `Aarch64Core`** (G3): este catálogo não muda nenhuma factory/API pública
/// existente. Quem quiser usar hoje faz
/// `new Aarch64Core(memory, Aarch64Processor.CORTEX_A53.architecture())` manualmente.
///
/// Fonte: [List of ARM processors](https://en.wikipedia.org/wiki/List_of_ARM_processors)
/// (Wikipedia, consultada 2026-08-28) para a versão de arquitetura de cada núcleo.
public enum Aarch64Processor {
    /// Núcleo dual-mode A32/A64, `ARMv8-A` — o núcleo de referência do `virtual-arm-box`/raspi3-64
    /// deste projeto, confirmado `ARMv8.0-A` pela ficha técnica real.
    CORTEX_A34("Cortex-A34", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8-A`.
    CORTEX_A35("Cortex-A35", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8-A` — o núcleo de referência do `virtual-arm-box`/raspi3-64 (a
    /// primeira entrada óbvia do catálogo, ver o plano mestre B12).
    CORTEX_A53("Cortex-A53", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8-A`.
    CORTEX_A57("Cortex-A57", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8-A`.
    CORTEX_A72("Cortex-A72", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8-A`.
    CORTEX_A73("Cortex-A73", Aarch64Architecture.ARMV8_0_A),

    /// Dual-mode A32/A64, `ARMv8.2-A`.
    CORTEX_A55("Cortex-A55", Aarch64Architecture.ARMV8_2_A),

    /// Dual-mode A32/A64, `ARMv8.2-A`.
    CORTEX_A75("Cortex-A75", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A`.
    CORTEX_X1("Cortex-X1", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — núcleo de servidor.
    NEOVERSE_N1("Neoverse N1", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — núcleo de servidor de baixo custo/eficiência.
    NEOVERSE_E1("Neoverse E1", Aarch64Architecture.ARMV8_2_A);

    private final String displayName;
    private final Aarch64Architecture architecture;

    Aarch64Processor(String displayName, Aarch64Architecture architecture) {
        this.displayName = displayName;
        this.architecture = architecture;
    }

    /// A arquitetura AArch64 real que este processador implementa.
    public Aarch64Architecture architecture() {
        return architecture;
    }

    /// O nome comercial do processador (ex. `"Cortex-A53"`).
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
