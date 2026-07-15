package dev.vitorsilverio.armjitter.arch;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// Uma descrição imutável de uma versão de arquitetura ARM como um **conjunto de features**
/// (mais extensões de decoder opcionais), em vez de um número de versão. Decoders e o
/// mecanismo de execução consultam {@link #has(ArmFeature)} nos poucos pontos onde versões ARM
/// diferem, então adicionar uma nova versão é apenas declarar um novo conjunto de features aqui (e, para
/// grupos de instruções realmente novos, fornecer uma {@link DecoderExtension}) — o pipeline compartilhado
/// nunca é bifurcado.
public final class ArmArchitecture {
    /// ARM7TDMI — a CPU do GBA (também o ARM7 do NDS). O conjunto base: sem features extras.
    public static final ArmArchitecture ARMV4T = of("ARMv4T");

    /// ARM9 — a CPU principal do NDS. ARMv4T mais o conjunto de features ARMv5TE (sem Thumb-2). O
    /// decoder de coprocessador é anexado aqui para que apenas cores ARMv5 decodifiquem `MCR`/`MRC` (CP15).
    public static final ArmArchitecture ARMV5TE = of("ARMv5TE",
            ArmFeature.BLX,
            ArmFeature.CLZ,
            ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING,
            ArmFeature.LDRD_STRD,
            ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY,
            ArmFeature.LDM_WRITEBACK_BASE_IN_LIST,
            ArmFeature.EMPTY_RLIST_NO_TRANSFER,
            ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL)
            .withDecoderExtensions(List.of(new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()));

    /// ARM11 (MPCore/ARM1176) — 3DS principal e Raspberry Pi 1/Zero. ARMv5TE mais o conjunto
    /// user-level do ARMv6/v6K (sem Thumb-2, que é ARMv6T2+). Extend/reverse/UMAAL já são
    /// decodificados e interpretados (B1.2); os demais grupos chegam nas tasks B1.3–B1.5 e a
    /// emissão nativa ASM na B1.6.
    public static final ArmArchitecture ARMV6K = extending(ARMV5TE, "ARMv6K",
            ArmFeature.EXTEND_ROTATE,
            ArmFeature.BYTE_REVERSE,
            ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD,
            ArmFeature.PACK_SATURATE,
            ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED,
            ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA,
            ArmFeature.WAIT_HINTS);

    /// Subconjunto Thumb-2 até B2.4 (infra, data-processing e branches/IT block de 32 bits),
    /// promovido a preset público pela task B4.0.2. **Decisão explícita desta task (B2.4, item 7
    /// da Especificação)**: `Thumb2BranchDecoder` (novo nesta task — B.W/TBB/TBH) entra aqui
    /// junto de `Thumb2DataProcessingDecoder` (já presente desde B2.2); `Thumb2LoadStoreDecoder`
    /// (B2.3) e `Thumb2MiscDecoder` (B2.5) permanecem DELIBERADAMENTE FORA — a task tentou ligar
    /// as quatro extensões juntas e encontrou uma colisão real: `Thumb2LoadStoreDecoder`
    /// reivindica greedily (via `top8==0xF8`, formato `STRB`/`LDRB` T3) o candidato de 32 bits
    /// "fantasma" formado ao reler o SEGUNDO halfword de um par `BL`/`BLX` legado como se fosse um
    /// NOVO prefixo Thumb-2 (a ambiguidade documentada em `ThumbDecoder#tryDecodeThumb32`, hoje só
    /// resolvida corretamente quando NENHUMA extensão reivindica o "fantasma") — quebrando o
    /// caminho legado de `BL`/`BLX` quando os bytes seguintes ao par são zero/dados quaisquer.
    /// Investigar e corrigir esse gap (provavelmente exigindo que `Thumb2LoadStoreDecoder`/
    /// `Thumb2MiscDecoder` também validem alguma condição adicional para não reivindicar esses
    /// "fantasmas", espelhando o fix já aplicado a `Thumb2DataProcessingDecoder` nesta mesma task
    /// para `lo[15]`) fica para uma task de fechamento de preset separada — registrado aqui
    /// explicitamente, não deduzido pelo próximo agente. Nome deliberadamente NÃO diz "THUMB2"
    /// sozinho nem "ARMV7" — ainda não é o ARMv7-A completo da task B3 (sem VFP, sem SDIV/UDIV).
    public static final ArmArchitecture ARMV6K_THUMB2 = extending(ARMV6K, "ARMv6K+Thumb2(B2.1-B2.2+B2.4-branches)",
            ArmFeature.THUMB2)
            .withThumb32DecoderExtensions(
                    List.of(new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder()));

    private final String name;
    private final EnumSet<ArmFeature> features;
    private final List<DecoderExtension> decoderExtensions;
    private final List<DecoderExtension> thumb32DecoderExtensions;

    private ArmArchitecture(String name, EnumSet<ArmFeature> features, List<DecoderExtension> decoderExtensions,
            List<DecoderExtension> thumb32DecoderExtensions) {
        this.name = Objects.requireNonNull(name, "name");
        this.features = features.clone();
        this.decoderExtensions = List.copyOf(decoderExtensions);
        this.thumb32DecoderExtensions = List.copyOf(thumb32DecoderExtensions);
    }

    /// Constrói uma arquitetura a partir de um nome e das features que ela suporta.
    public static ArmArchitecture of(String name, ArmFeature... features) {
        EnumSet<ArmFeature> set = EnumSet.noneOf(ArmFeature.class);
        Collections.addAll(set, features);
        return new ArmArchitecture(name, set, List.of(), List.of());
    }

    /// Constrói uma arquitetura que estende uma base: herda todas as features **e** as extensões
    /// de decoder (ARM e Thumb-2) da base, acrescentando as features extras. É como versões novas
    /// compõem sobre as anteriores (ex. ARMv6K sobre ARMv5TE) sem repetir a lista da base.
    public static ArmArchitecture extending(ArmArchitecture base, String name, ArmFeature... extraFeatures) {
        EnumSet<ArmFeature> set = base.features.clone();
        Collections.addAll(set, extraFeatures);
        return new ArmArchitecture(name, set, base.decoderExtensions, base.thumb32DecoderExtensions);
    }

    public boolean has(ArmFeature feature) {
        return features.contains(feature);
    }

    public List<DecoderExtension> decoderExtensions() {
        return decoderExtensions;
    }

    /// Extensões que decodificam o segundo halfword de uma instrução Thumb de 32 bits
    /// (`raw` recebido pela extensão é os dois halfwords combinados, primeiro halfword nos bits
    /// altos). Vazio até B2.2 registrar a primeira categoria (data processing); até lá todo
    /// candidato de 32 bits Thumb-2 cai em UNDEFINED controlado — ver {@link
    /// dev.vitorsilverio.armjitter.decoder.ThumbDecoder}.
    public List<DecoderExtension> thumb32DecoderExtensions() {
        return thumb32DecoderExtensions;
    }

    /// Retorna uma cópia desta arquitetura com as extensões de decoder ARM fornecidas, usadas para
    /// plugar grupos de instruções que uma versão futura adiciona.
    public ArmArchitecture withDecoderExtensions(List<DecoderExtension> extensions) {
        return new ArmArchitecture(name, features, extensions, thumb32DecoderExtensions);
    }

    /// Retorna uma cópia desta arquitetura com as extensões de decoder Thumb-2 (32-bit) fornecidas.
    public ArmArchitecture withThumb32DecoderExtensions(List<DecoderExtension> extensions) {
        return new ArmArchitecture(name, features, decoderExtensions, extensions);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
