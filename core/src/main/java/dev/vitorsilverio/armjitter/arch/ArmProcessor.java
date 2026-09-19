package dev.vitorsilverio.armjitter.arch;

/// Catálogo de processadores ARM32/T32 reais nomeados, cada um resolvendo para a
/// {@link ArmArchitecture} que ele implementa (B12.3, ver
/// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`) — conveniência para o cliente
/// da biblioteca escolher por nome comercial (`ARM7TDMI`, `Cortex-A9`, ...) em vez de montar a
/// `ArmArchitecture` na mão.
///
/// **Escopo de B12.3**: ARM clássico + linha Cortex-A 32-bit já cobertos por preset existente
/// (`ARMV4T`/`ARMV5TE`/`ARM11_MPCORE`/`ARMV7A`) — nenhuma feature/arquitetura nova, só a tabela de
/// resolução. Núcleos sem preset hoje (ARMv1/v2/v2a/v3, ARMv6/ARMv6T2/ARMv6Z puros, `Cortex-A32`
/// AArch32-only) ficam para B12.5/B12.6; perfil R fica para um épico próprio (nunca modelado
/// neste projeto).
///
/// **Escopo de B12.5** (núcleos clássicos sem preset): {@link #ARM1136J_S}/{@link #ARM1156T2_S}/
/// {@link #ARM1176JZ_S} resolvem para os presets NOVOS {@link ArmArchitecture#ARMV6}/
/// {@link ArmArchitecture#ARMV6T2}/{@link ArmArchitecture#ARMV6Z} (aditivo — zero decoder/feature
/// novo, só combinações inéditas de features já existentes; ver o Javadoc de cada preset). O item
/// "ARMv5TEJ com Jazelle" da escada do épico já estava fechado por B12.3 ({@link #ARM7EJ_S}/
/// {@link #ARM926EJ_S}/{@link #ARM1026EJ_S}, aproximação para `ARMV5TE`) — nenhum trabalho novo
/// aqui. **`ARMv1`/`ARMv2`/`ARMv2a`/`ARMv3` (ARM1/ARM2/ARM250/ARM60..710a) ficam de fora,
/// deliberadamente**: essas versões usam um modelo de CPU fundamentalmente diferente do que este
/// projeto assume desde `ARMV4T` — PC de 26 bits com os flags de condição empacotados nos bits
/// altos do próprio R15 (sem CPSR/SPSR separados antes da ARMv3), endereçamento de 32 bits só
/// opcional na ARMv3 (obrigatório na ARMv3G) — não é uma questão de "feature faltando" que um
/// `EnumSet<ArmFeature>` resolva (o padrão deste catálogo, `ArmArchitecture.of`/`extending`), é um
/// modelo de registrador/exceção diferente, que exigiria mudanças no núcleo (`ArmCore`,
/// `AProfileExceptionModel`, decoders de PC/flags) antes de existir qualquer preset para catalogar.
/// Fora do orçamento de B12 (catalogação pura, sem decode novo — ver a Meta do épico); candidato a
/// um épico próprio de "modelo de registrador pré-ARMv3", nunca "fora de escopo para sempre"
/// (regra máxima do projeto, `tasks/README.md`).
///
/// **Escopo de B12.6, RESOLVIDO pela B14.7** (`Cortex-A32`, `ARMv8-A` AArch32-only): a B12.6
/// investigou e deixou `Cortex-A32` de fora do catálogo porque a base `ARMv8-A` inclui
/// `LDA`/`LDAB`/`LDAH`/`LDAEX*` (load-acquire) e `STL`/`STLB`/`STLH`/`STLEX*` (store-release) como
/// **obrigatórias**, não opcionais (ARM DDI 0487, A32/T32 baseline v8), mais `CRC32` (opcional em
/// `ARMv8.0-A`) — nenhuma tinha decoder/executor neste projeto na época (ver
/// `docs/isa-nao-aplicavel.tsv`, entradas `LDA`/`STL`), e mapear `Cortex-A32` para
/// {@link ArmArchitecture#ARMV7A} teria sido uma entrada de catálogo factualmente ERRADA pelo
/// mesmo motivo que excluiu `SC300`/`Cortex-M3` em B12.4: o núcleo real aceita essas instruções,
/// aquele preset as rejeitaria como `UNDEFINED`. A escada **B14.1-B14.3** (`ArmFeature` novo para
/// load-acquire/store-release + `CRC32`) fechou exatamente essa lacuna, e {@link #CORTEX_A32}
/// agora resolve para o preset {@link ArmArchitecture#ARMV8A_32} (composto sobre `ARMV7A`) que
/// nasceu dela — ver `tasks/trilha-b-arquiteturas/b14.7-fechamento-coluna-v8a32.md`.
///
/// **Escopo de B12.4** (perfil M, primeiro degrau): só o `ARMv6-M` puro (`SC000`/`Cortex-M0`/
/// `M0+`/`M1`) resolvia para preset existente (`ARMV6M`) sem ressalva na época. `SecurCore SC300`/
/// `Cortex-M3` (`ARMv7-M` real, **sem** a extensão DSP) ficaram de fora do catálogo naquela sessão:
/// o preset `ARMV7M` deste projeto (B7.4) inclui {@link ArmFeature#SATURATING} (`QADD`/`QSUB`/
/// `QDADD`/`QDSUB`, parte da extensão DSP opcional que só existe de fato em `ARMv7E-M`), então
/// mapear `Cortex-M3` para `ARMV7M` seria uma entrada de catálogo factualmente ERRADA
/// (superconjunto, não aproximação conservadora — o núcleo real rejeitaria `QADD` como
/// `UNDEFINED`, este preset aceitaria) — diferente da aproximação documentada de {@link #ARM7EJ_S}
/// (que é um subconjunto conservador, Jazelle nunca modelado).
///
/// **Escopo de B15.7** (fechamento do épico B15, perfil M moderno): os 10 núcleos que a B12.4
/// deixou pendentes agora têm preset e entram no catálogo — {@link #SC300}/{@link #CORTEX_M3}
/// (`ARMV7M_PURE`, B15.1), {@link #CORTEX_M4}/{@link #CORTEX_M7} (`ARMV7EM`, alias de `ARMV7M`,
/// B15.1), {@link #CORTEX_M23} (`ARMV8M_BASELINE`, B15.4), {@link #CORTEX_M33}/
/// {@link #CORTEX_M35P} (`ARMV8M_MAINLINE`, B15.4) e {@link #CORTEX_M52}/{@link #CORTEX_M55}/
/// {@link #CORTEX_M85} (`ARMV8_1M`, B15.6 — na época **mapeamento PARCIAL**, sem Helium/MVE;
/// completado pela **B16.14** para {@link ArmArchitecture#ARMV8_1M_MVE} assim que o épico B16
/// fechou o decode/execução de Helium). `Cortex-M23`/`M33`/`M35P` resolvem para a variante COM Security Extension (TrustZone) —
/// este projeto não modela um preset "sem TrustZone" separado, mesma simplificação de SKU que
/// `Cortex-A5`..`A17` já aplicam do lado A-profile (uma constante por núcleo canônico).
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

    /// `ARMv6` pura (B12.5) — variante sem VFP do núcleo (a Wikipedia lista `ARM1136J(F)-S`
    /// cobrindo as duas; a variante `ARM1136JF-S`, com VFP, fica de fora, ver
    /// {@link ArmArchitecture#ARMV6}).
    ARM1136J_S("ARM1136J-S", ArmArchitecture.ARMV6),

    /// `ARMv6T2` pura (B12.5) — variante sem VFP do núcleo (a Wikipedia lista `ARM1156T2(F)-S`
    /// cobrindo as duas; a variante `ARM1156T2F-S`, com VFP, fica de fora, ver
    /// {@link ArmArchitecture#ARMV6T2}).
    ARM1156T2_S("ARM1156T2-S", ArmArchitecture.ARMV6T2),

    /// `ARMv6Z` pura (B12.5) — variante sem VFP do núcleo (a Wikipedia lista `ARM1176JZ(F)-S`
    /// cobrindo as duas; a variante `ARM1176JZF-S`, com VFP, fica de fora, ver
    /// {@link ArmArchitecture#ARMV6Z}).
    ARM1176JZ_S("ARM1176JZ-S", ArmArchitecture.ARMV6Z),

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
    CORTEX_A17("Cortex-A17", ArmArchitecture.ARMV7A),

    /// `ARMv8-A` AArch32-only (B14.7 — resolve a pendência de B12.6, ver Javadoc da classe):
    /// `LDA`/`STL`/`CRC32` (B14.1-B14.3) fecham a lacuna que impedia mapear este núcleo para
    /// {@link ArmArchitecture#ARMV7A}.
    CORTEX_A32("Cortex-A32", ArmArchitecture.ARMV8A_32),

    /// SecurCore `SC000` — `ARMv6-M` (perfil M, T32-only), o único SecurCore junto de {@link #SC100}
    /// que este catálogo cobre por ora (B12.4; `SC300` fica de fora, ver Javadoc da classe).
    SC000("SecurCore SC000", ArmArchitecture.ARMV6M),

    /// `ARMv6-M` (perfil M, T32-only) — o núcleo mais simples da linha Cortex-M (B12.4).
    CORTEX_M0("Cortex-M0", ArmArchitecture.ARMV6M),

    /// `ARMv6-M`, mesma família do Cortex-M0 (variante de baixo consumo, mesmo conjunto de
    /// instruções).
    CORTEX_M0PLUS("Cortex-M0+", ArmArchitecture.ARMV6M),

    /// `ARMv6-M`, mesma família do Cortex-M0.
    CORTEX_M1("Cortex-M1", ArmArchitecture.ARMV6M),

    /// SecurCore `SC300` — `ARMv7-M` **sem** a extensão DSP (B15.1/B15.7), o núcleo que motivou o
    /// épico B15 inteiro (ver Javadoc da classe): {@link ArmArchitecture#ARMV7M} (o preset chamado
    /// "ARMv7-M" desde a B7.4) na verdade já inclui DSP (é um ARMv7E-M real), então mapear `SC300`
    /// nele seria entrada factualmente errada. Resolve para {@link ArmArchitecture#ARMV7M_PURE}.
    SC300("SecurCore SC300", ArmArchitecture.ARMV7M_PURE),

    /// `ARMv7-M` sem DSP (B15.1/B15.7) — mesma família do {@link #SC300}, resolve para
    /// {@link ArmArchitecture#ARMV7M_PURE}.
    CORTEX_M3("Cortex-M3", ArmArchitecture.ARMV7M_PURE),

    /// `ARMv7E-M` (com DSP; FPU opcional, não modelada com granularidade de variante — este
    /// projeto assume a variante SEM FPU dedicada, o denominador comum entre `Cortex-M4`/`M4F`)
    /// (B15.1/B15.7). Resolve para {@link ArmArchitecture#ARMV7EM} (alias por identidade de
    /// {@link ArmArchitecture#ARMV7M}, que já inclui DSP desde a B9.16).
    CORTEX_M4("Cortex-M4", ArmArchitecture.ARMV7EM),

    /// `ARMv7E-M` (com DSP; FPU/cache opcionais, mesma aproximação do {@link #CORTEX_M4}) (B15.1/
    /// B15.7). Resolve para {@link ArmArchitecture#ARMV7EM}.
    CORTEX_M7("Cortex-M7", ArmArchitecture.ARMV7EM),

    /// `ARMv8-M Baseline` (B15.4/B15.7) — a Security Extension (TrustZone) é opcional na
    /// arquitetura real, mas este catálogo assume a variante COM TrustZone (a mais capaz; este
    /// projeto ainda não modela um preset "ARMv8-M Baseline sem Security Extension" separado —
    /// mesma simplificação de granularidade de SKU que B12.1-B12.6 já aplicaram ao lado A-profile,
    /// ex. `Cortex-A5`..`A17` não distinguem variantes com/sem NEON). Resolve para
    /// {@link ArmArchitecture#ARMV8M_BASELINE}.
    CORTEX_M23("Cortex-M23", ArmArchitecture.ARMV8M_BASELINE),

    /// `ARMv8-M Mainline` (B15.4/B15.7) — mesma aproximação do {@link #CORTEX_M23} (variante COM
    /// TrustZone; FPU/DSP opcionais também não modelados com granularidade de variante). Resolve
    /// para {@link ArmArchitecture#ARMV8M_MAINLINE}.
    CORTEX_M33("Cortex-M33", ArmArchitecture.ARMV8M_MAINLINE),

    /// `ARMv8-M Mainline` com proteção física adicional (não modelada — mesma aproximação do
    /// {@link #CORTEX_M23}/{@link #CORTEX_M33}, proteção física é ortogonal ao conjunto de
    /// instruções). Resolve para {@link ArmArchitecture#ARMV8M_MAINLINE}.
    CORTEX_M35P("Cortex-M35P", ArmArchitecture.ARMV8M_MAINLINE),

    /// `ARMv8.1-M Mainline` — o `Cortex-M52` é o núcleo de entrada da linha Helium (Arm, "Cortex-M55
    /// Processor Devices Generic User Guide", 101273: MVE-I/MVE-F são features de configuração
    /// IMPLEMENTATION DEFINED do `M-profile Vector Extension`, opcionais mesmo nos núcleos com
    /// Helium — nenhum dos três é garantido "sempre com MVE-F" pela arquitetura). Mesma
    /// simplificação de granularidade de SKU que {@link #CORTEX_M23}/{@link #CORTEX_M33} já aplicam
    /// para TrustZone: este catálogo assume a variante MAIS capaz (MVE-I + MVE-F). Resolve para
    /// {@link ArmArchitecture#ARMV8_1M_MVE} (B16.14 — antes da B16 fechar decode/execução de
    /// Helium/MVE, apontava para {@link ArmArchitecture#ARMV8_1M} sem MVE, mapeamento PARCIAL; ver
    /// B15.6/B15.7).
    CORTEX_M52("Cortex-M52", ArmArchitecture.ARMV8_1M_MVE),

    /// `ARMv8.1-M Mainline` — mesma simplificação do {@link #CORTEX_M52} (MVE-I/MVE-F são
    /// IMPLEMENTATION DEFINED mesmo no `Cortex-M55`, catálogo assume a variante mais capaz).
    /// Resolve para {@link ArmArchitecture#ARMV8_1M_MVE} (B16.14, mesmo fechamento do
    /// {@link #CORTEX_M52}).
    CORTEX_M55("Cortex-M55", ArmArchitecture.ARMV8_1M_MVE),

    /// `ARMv8.1-M Mainline` — mesma simplificação do {@link #CORTEX_M52}/{@link #CORTEX_M55}
    /// (MVE-I/MVE-F IMPLEMENTATION DEFINED, catálogo assume a variante mais capaz). Resolve para
    /// {@link ArmArchitecture#ARMV8_1M_MVE} (B16.14, mesmo fechamento dos dois anteriores);
    /// **PACBTI (Pointer Authentication/Branch Target Identification) continua não modelado**
    /// (extensão própria, fora do escopo dos épicos B15/B16).
    CORTEX_M85("Cortex-M85", ArmArchitecture.ARMV8_1M_MVE);

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
