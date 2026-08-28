package dev.vitorsilverio.armjitter.arch64;

/// Catálogo de processadores AArch64 reais nomeados, cada um resolvendo para a
/// {@link Aarch64Architecture} que ele implementa (B12.1, ver
/// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`) — conveniência para o cliente
/// da biblioteca escolher por nome comercial (`Cortex-A53`, `Neoverse N1`, ...) em vez de montar a
/// `Aarch64Architecture` na mão.
///
/// **Escopo B12.1**: família Cortex-A/X/Neoverse `ARMv8.0-A`→`ARMv8.2-A`. **Escopo B12.2**: o
/// restante `ARMv8.4-A`→`ARMv9.5-A` (Neoverse V1/N2/V2/N3/V3, Cortex-A510+/X2+/A320+, C-Series) —
/// mais 8 núcleos `ARMv8.2-A` A64-only (`Cortex-A65`/`A65AE`/`A76`/`A76AE`/`A77`/`A78`/`A78AE`/
/// `A78C`) que estavam na mesma linha da tabela de origem do catálogo de B12.1 mas ficaram de fora
/// dele (achado real ao revisar a tabela para B12.2 — fechados aqui, mesmo preset `ARMV8_2_A` já
/// usado, zero trabalho de arquitetura novo). O catálogo 32-bit/T32 (`arch.ArmProcessor`) é B12.3+.
///
/// **Decisão de precisão (B12.2)**: a Wikipedia usa `"ARMv9-A"` genérico (sem sub-revisão) para
/// Cortex-A510/A710/A715/X2/X3 e Neoverse N2/V2 — mapeados aqui para o preset mais conservador,
/// {@link Aarch64Architecture#ARMV9_0_A}, conforme a "Armadilha de precisão" documentada no plano
/// mestre B12 (`tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`); não confirmado
/// contra o TRM real de cada núcleo.
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
    NEOVERSE_E1("Neoverse E1", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` (B12.2: gap-fill de B12.1, ver o Javadoc da classe).
    CORTEX_A65("Cortex-A65", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — variante com Application Extension (B12.2: gap-fill de B12.1).
    CORTEX_A65AE("Cortex-A65AE", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` (B12.2: gap-fill de B12.1).
    CORTEX_A76("Cortex-A76", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — variante com Application Extension (B12.2: gap-fill de B12.1).
    CORTEX_A76AE("Cortex-A76AE", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` (B12.2: gap-fill de B12.1).
    CORTEX_A77("Cortex-A77", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` (B12.2: gap-fill de B12.1).
    CORTEX_A78("Cortex-A78", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — variante com Application Extension (B12.2: gap-fill de B12.1).
    CORTEX_A78AE("Cortex-A78AE", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.2-A` — variante voltada a Chromebooks (B12.2: gap-fill de B12.1).
    CORTEX_A78C("Cortex-A78C", Aarch64Architecture.ARMV8_2_A),

    /// A64-only, `ARMv8.4-A` — núcleo de servidor.
    NEOVERSE_V1("Neoverse V1", Aarch64Architecture.ARMV8_4_A),

    /// A64-only, `"ARMv9-A"` genérico na Wikipedia — mapeado para {@link Aarch64Architecture#ARMV9_0_A}
    /// (aproximação conservadora, ver o Javadoc da classe).
    CORTEX_A510("Cortex-A510", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A}.
    CORTEX_A710("Cortex-A710", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A}.
    CORTEX_A715("Cortex-A715", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A}.
    CORTEX_X2("Cortex-X2", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A}.
    CORTEX_X3("Cortex-X3", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A} — núcleo
    /// de servidor.
    NEOVERSE_N2("Neoverse N2", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `"ARMv9-A"` genérico — mapeado para {@link Aarch64Architecture#ARMV9_0_A} — núcleo
    /// de servidor.
    NEOVERSE_V2("Neoverse V2", Aarch64Architecture.ARMV9_0_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_A320("Cortex-A320", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_A520("Cortex-A520", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_A720("Cortex-A720", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_A725("Cortex-A725", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_X4("Cortex-X4", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A`.
    CORTEX_X925("Cortex-X925", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A` — núcleo de servidor.
    NEOVERSE_N3("Neoverse N3", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.2-A` — núcleo de servidor.
    NEOVERSE_V3("Neoverse V3", Aarch64Architecture.ARMV9_2_A),

    /// A64-only, `ARMv9.3-A` — branding C-Series (pós-2025), variante de alto desempenho.
    C1_ULTRA("C1-Ultra", Aarch64Architecture.ARMV9_3_A),

    /// A64-only, `ARMv9.3-A` — branding C-Series (pós-2025).
    C1_PREMIUM("C1-Premium", Aarch64Architecture.ARMV9_3_A),

    /// A64-only, `ARMv9.3-A` — branding C-Series (pós-2025).
    C1_PRO("C1-Pro", Aarch64Architecture.ARMV9_3_A),

    /// A64-only, `ARMv9.3-A` — branding C-Series (pós-2025), variante de baixo consumo.
    C1_NANO("C1-Nano", Aarch64Architecture.ARMV9_3_A);

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
