package dev.vitorsilverio.armjitter.arch;

/// Catálogo de processadores ARM32/T32 reais nomeados, cada um resolvendo para a
/// {@link ArmArchitecture} que ele implementa (B12.3, ver
/// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`) — conveniência para o cliente
/// da biblioteca escolher por nome comercial (`ARM7TDMI`, `Cortex-A9`, ...) em vez de montar a
/// `ArmArchitecture` na mão.
///
/// **Escopo desta task**: ARM clássico + linha Cortex-A 32-bit já cobertos por preset existente
/// (`ARMV4T`/`ARMV5TE`/`ARM11_MPCORE`/`ARMV7A`) — nenhuma feature/arquitetura nova, só a tabela de
/// resolução. Núcleos sem preset hoje (ARMv1/v2/v2a/v3, ARMv6/ARMv6T2/ARMv6Z puros, `Cortex-A32`
/// AArch32-only) ficam para B12.5/B12.6; perfil M (`Cortex-M`/`SecurCore SC000`/`SC300`) fica para
/// B12.4; perfil R fica para um épico próprio (nunca modelado neste projeto).
///
/// **Sem uso ainda em `ArmCore`** (G3): este catálogo não muda nenhuma factory/API pública
/// existente. Quem quiser usar hoje faz `new ArmCore(memory, ArmProcessor.ARM7TDMI.architecture())`
/// manualmente.
///
/// Fonte: [List of ARM processors](https://en.wikipedia.org/wiki/List_of_ARM_processors)
/// (Wikipedia, consultada 2026-08-28) para a versão de arquitetura de cada núcleo.
public enum ArmProcessor {
    /// A CPU do GBA (também o ARM7 do NDS) — `ARMv4T`, o núcleo de referência do preset.
    ARM7TDMI("ARM7TDMI", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM7TDMI (variante com MMU/cache, sem TCM).
    ARM710T("ARM710T", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM7TDMI (variante com TCM).
    ARM720T("ARM720T", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM7TDMI (variante embarcada, sem MMU).
    ARM740T("ARM740T", ArmArchitecture.ARMV4T),

    /// `ARMv4T` — a CPU principal do NDS antes do ARM9E (linha ARM9TDMI, mesmo conjunto do
    /// ARM7TDMI, pipeline de 5 estágios em vez de 3).
    ARM9TDMI("ARM9TDMI", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM9TDMI (variante com MMU/cache).
    ARM920T("ARM920T", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM9TDMI (variante com MPU em vez de MMU completa).
    ARM922T("ARM922T", ArmArchitecture.ARMV4T),

    /// `ARMv4T`, mesma família do ARM9TDMI (variante embarcada, sem MMU).
    ARM940T("ARM940T", ArmArchitecture.ARMV4T),

    /// SecurCore `SC100` — único núcleo SecurCore fora do perfil M (`ARMv4T`, mesmo conjunto do
    /// ARM7TDMI com extensões de segurança físicas não modeladas por este projeto); os demais
    /// SecurCore (`SC000`/`SC300`) são perfil M e ficam em B12.4.
    SC100("SecurCore SC100", ArmArchitecture.ARMV4T),

    /// `ARMv5TE` — a CPU principal do NDS (ARM9, família ARM946E-S).
    ARM946E_S("ARM946E-S", ArmArchitecture.ARMV5TE),

    /// `ARMv5TE`, mesma família do ARM946E-S.
    ARM966E_S("ARM966E-S", ArmArchitecture.ARMV5TE),

    /// `ARMv5TE`, mesma família do ARM946E-S.
    ARM968E_S("ARM968E-S", ArmArchitecture.ARMV5TE),

    /// `ARMv5TE`, mesma família do ARM946E-S.
    ARM996HS("ARM996HS", ArmArchitecture.ARMV5TE),

    /// `ARMv5TE`, núcleo de maior desempenho da geração E (cache maior, sem Jazelle).
    ARM1020E("ARM1020E", ArmArchitecture.ARMV5TE),

    /// `ARMv5TE`, mesma família do ARM1020E.
    ARM1022E("ARM1022E", ArmArchitecture.ARMV5TE),

    /// A Wikipedia lista `ARMv5TEJ` (Jazelle, execução acelerada de bytecode Java) para este
    /// núcleo — **aproximação documentada**: nenhum modo Jazelle é modelado por este projeto (não
    /// existe `ArmFeature` para isso), então o conjunto de instruções ARM/Thumb visível ao
    /// decoder/executor é idêntico ao `ARMv5TE` puro; `ARMV5TE` é usado como resolução.
    ARM7EJ_S("ARM7EJ-S", ArmArchitecture.ARMV5TE),

    /// `ARMv5TEJ` na Wikipedia — mesma aproximação documentada do {@link #ARM7EJ_S} (Jazelle não
    /// modelado, conjunto ARM/Thumb idêntico ao `ARMv5TE`).
    ARM926EJ_S("ARM926EJ-S", ArmArchitecture.ARMV5TE),

    /// `ARMv5TEJ` na Wikipedia — mesma aproximação documentada do {@link #ARM7EJ_S}.
    ARM1026EJ_S("ARM1026EJ-S", ArmArchitecture.ARMV5TE),

    /// `ARMv6K` — o núcleo principal do 3DS (2 cores, ver `Aarch64Processor`/B5 para o monitor de
    /// exclusividade) e do Raspberry Pi 1/Zero.
    ARM11_MPCORE("ARM11 MPCore", ArmArchitecture.ARM11_MPCORE),

    /// `ARMv7-A`.
    CORTEX_A5("Cortex-A5", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A7("Cortex-A7", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A8("Cortex-A8", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A9("Cortex-A9", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A12("Cortex-A12", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A15("Cortex-A15", ArmArchitecture.ARMV7A),

    /// `ARMv7-A`.
    CORTEX_A17("Cortex-A17", ArmArchitecture.ARMV7A);

    private final String displayName;
    private final ArmArchitecture architecture;

    ArmProcessor(String displayName, ArmArchitecture architecture) {
        this.displayName = displayName;
        this.architecture = architecture;
    }

    /// A arquitetura ARM32/T32 real que este processador implementa.
    public ArmArchitecture architecture() {
        return architecture;
    }

    /// O nome comercial do processador (ex. `"Cortex-A9"`).
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
