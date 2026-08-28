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
/// **Escopo de B12.6** (`Cortex-A32`, `ARMv8-A` AArch32-only): investigado e deliberadamente
/// deixado de fora do catálogo. `Cortex-A32` é um núcleo real `ARMv8-A` — ao contrário do
/// `Cortex-A5`..`A17` (`ARMv7-A` puro, já resolvidos para {@link ArmArchitecture#ARMV7A} acima),
/// a base `ARMv8-A` inclui `LDA`/`LDAB`/`LDAH`/`LDAEX*` (load-acquire) e
/// `STL`/`STLB`/`STLH`/`STLEX*` (store-release) como **obrigatórias**, não opcionais (ARM DDI
/// 0487, A32/T32 baseline v8) — nenhuma delas tem decoder/executor neste projeto hoje (ver
/// `docs/isa-nao-aplicavel.tsv`, entradas `LDA`/`STL`). Mapear `Cortex-A32` para
/// {@link ArmArchitecture#ARMV7A} seria uma entrada de catálogo factualmente ERRADA pelo mesmo
/// motivo que excluiu `SC300`/`Cortex-M3` em B12.4: o núcleo real aceita essas instruções, este
/// preset as rejeitaria como `UNDEFINED`. `CRC32` (opcional em `ARMv8.0-A`) também está ausente.
/// Como B12 é catalogação pura — **nunca implementa decode novo** (ver o corpo do épico,
/// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`) —, `Cortex-A32` fica pendente
/// de uma task própria de decode (`ArmFeature` novo para load-acquire/store-release + `CRC32`,
/// depois um preset `ARMv8-A AArch32` composto sobre {@link ArmArchitecture#ARMV7A}), candidata
/// futura na trilha B (regra máxima do projeto — nunca "fora de escopo para sempre").
///
/// **Escopo de B12.4** (perfil M): só o `ARMv6-M` puro (`SC000`/`Cortex-M0`/`M0+`/`M1`) resolve
/// para preset existente (`ARMV6M`) sem ressalva. `SecurCore SC300`/`Cortex-M3` (`ARMv7-M` real,
/// **sem** a extensão DSP) ficam de fora do catálogo: o preset `ARMV7M` deste projeto (B7.4) inclui
/// {@link ArmFeature#SATURATING} (`QADD`/`QSUB`/`QDADD`/`QDSUB`, parte da extensão DSP opcional que
/// só existe de fato em `ARMv7E-M`), então mapear `Cortex-M3` para `ARMV7M` seria uma entrada de
/// catálogo factualmente ERRADA (superconjunto, não aproximação conservadora — o núcleo real
/// rejeitaria `QADD` como `UNDEFINED`, este preset aceitaria) — diferente da aproximação
/// documentada de {@link #ARM7EJ_S} (que é um subconjunto conservador, Jazelle nunca modelado).
/// `Cortex-M4`/`M7` (`ARMv7E-M`), `Cortex-M23` (`ARMv8-M Baseline`), `Cortex-M33`/`M35P`
/// (`ARMv8-M Mainline`) e `Cortex-M52`/`M55`/`M85` (`ARMv8.1-M Mainline`) também ficam de fora:
/// nenhuma dessas versões tem preset ainda. Todos ficam documentados como pendentes (regra máxima
/// do projeto, `tasks/README.md` — nunca "fora de escopo para sempre"), candidatos a uma sub-task
/// que primeiro resolva o preset `ARMv7-M` puro (sem `SATURATING`) e depois crie os presets
/// `ARMv7E-M`/`ARMv8-M`/`ARMv8.1-M`.
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

    /// SecurCore `SC000` — `ARMv6-M` (perfil M, T32-only), o único SecurCore junto de {@link #SC100}
    /// que este catálogo cobre por ora (B12.4; `SC300` fica de fora, ver Javadoc da classe).
    SC000("SecurCore SC000", ArmArchitecture.ARMV6M),

    /// `ARMv6-M` (perfil M, T32-only) — o núcleo mais simples da linha Cortex-M (B12.4).
    CORTEX_M0("Cortex-M0", ArmArchitecture.ARMV6M),

    /// `ARMv6-M`, mesma família do Cortex-M0 (variante de baixo consumo, mesmo conjunto de
    /// instruções).
    CORTEX_M0PLUS("Cortex-M0+", ArmArchitecture.ARMV6M),

    /// `ARMv6-M`, mesma família do Cortex-M0.
    CORTEX_M1("Cortex-M1", ArmArchitecture.ARMV6M);

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
