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
            ArmFeature.WAIT_HINTS,
            ArmFeature.UNALIGNED_ACCESS);

    /// Preset Thumb-2 completo do épico B2 (B2.1-B2.6): as 4 extensões de decoder de 32 bits
    /// (`Thumb2DataProcessingDecoder`, `Thumb2LoadStoreDecoder`, `Thumb2BranchDecoder`,
    /// `Thumb2MiscDecoder`) plugadas juntas. Até B2.6 só 2 das 4 estavam plugadas: `BL`/`BLX`
    /// imediato, decodificado como dois halfwords independentes (`LONG_BRANCH_PREFIX`+
    /// `LONG_BRANCH_SUFFIX`), fazia `ThumbDecoder#decode` ser chamado de novo no endereço do
    /// SEGUNDO halfword — que coincide, byte a byte, com o formato de um prefixo Thumb-2 de 32
    /// bits genuíno (o "fantasma"); `Thumb2LoadStoreDecoder`/`Thumb2MiscDecoder` reivindicavam
    /// esse fantasma e engoliam o sufixo real. **B2.6 fecha isso pela raiz**: com
    /// {@link ArmFeature#THUMB2} ativo, `BL`/`BLX` imediato é decodificado como instrução ÚNICA de
    /// 32 bits (`InstructionKind#LONG_BRANCH_32`, ARM DDI 0406C A8.8.25 — fiel ao hardware real
    /// desde ARMv6T2: a execução "meio a meio" só é arquitetural até ARMv6) — `decode()` nunca mais
    /// é chamado no endereço de um sufixo em código são, o fantasma deixa de existir, e as 4
    /// extensões podem reivindicar seus espaços livremente sem colisão. `MEMORY_BARRIERS` (ARMv7)
    /// entra aqui porque `Thumb2MiscDecoder` gateia `DMB`/`DSB`/`ISB` por ela (sem a feature elas
    /// virariam UNDEFINED — no preset "fechado" devem funcionar); `WAIT_HINTS` já vem herdado de
    /// `ARMV6K`. Ainda NÃO é o ARMv7-A completo da task B3 (sem VFP, sem SDIV/UDIV, sem os demais
    /// encodings de paridade v7 — ver B2.7).
    /// Só as FEATURES de {@link #ARMV6K_THUMB2} (sem as extensões de decoder ainda) — existe
    /// separadamente porque `Thumb2LoadStoreDecoder`/`Thumb2MiscDecoder` recebem uma
    /// `ArmArchitecture` no construtor (para gatear `LDRD_STRD`/`WAIT_HINTS`/`MEMORY_BARRIERS` em
    /// tempo de decode) e `ARMV6K_THUMB2` ainda não existe no ponto em que essas extensões
    /// precisam ser construídas — quebra-cabeça do ovo e da galinha resolvido construindo as
    /// features primeiro (idêntico ao padrão já usado pelos testes de B2.3/B2.5, ex.
    /// `Thumb2MiscDecoderTest`).
    private static final ArmArchitecture ARMV6K_THUMB2_FEATURES = extending(ARMV6K, "ARMv6K+Thumb2",
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS);

    public static final ArmArchitecture ARMV6K_THUMB2 = ARMV6K_THUMB2_FEATURES
            .withThumb32DecoderExtensions(
                    List.of(new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6K_THUMB2_FEATURES)));

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
