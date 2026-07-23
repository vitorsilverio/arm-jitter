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
            ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.PRELOAD_HINTS);

    /// Preset Thumb-2 do épico B2 (B2.1-B2.6) mais a paridade de encodings de B2.7: as extensões
    /// de decoder de 32 bits (`Thumb2DataProcessingDecoder`, `Thumb2RegisterDataProcessingDecoder`
    /// [B2.7 PR1], `Thumb2MultiplyDecoder` [B2.7 PR2], `Thumb2LoadStoreDecoder`,
    /// `Thumb2BranchDecoder`, `Thumb2MiscDecoder`, `Thumb2CoprocessorDecoder` [B2.7 PR3])
    /// plugadas juntas. Até B2.6 só 2 delas estavam plugadas: `BL`/`BLX`
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
    /// `MOVW_MOVT` (B3.1) entra aqui pelo mesmo motivo de `MEMORY_BARRIERS`: `MOVW`/`MOVT` Thumb-2
    /// já eram decodificados desde B2.2, mas só gateados por `THUMB2` — B3.1 passou a exigir
    /// também esta feature (mesma exigida pelo encoding ARM em
    /// {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder}, B3.1), então o
    /// preset "fechado" precisa declará-la para continuar funcionando.
    private static final ArmArchitecture ARMV6K_THUMB2_FEATURES = extending(ARMV6K, "ARMv6K+Thumb2",
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS, ArmFeature.MOVW_MOVT);

    public static final ArmArchitecture ARMV6K_THUMB2 = ARMV6K_THUMB2_FEATURES
            .withThumb32DecoderExtensions(
                    List.of(new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv7-A **user-level** — fecha o épico B3 (task B3.7). `MOVW_MOVT`/`MEMORY_BARRIERS`
    /// já são herdadas de {@link #ARMV6K_THUMB2} (declaradas em
    /// {@link #ARMV6K_THUMB2_FEATURES} desde B3.1/B2.6, repeti-las aqui seria redundante,
    /// embora inofensivo por `EnumSet` ser idempotente) — mas `MLS_MULTIPLY`/`BIT_FIELD`/
    /// `BIT_REVERSE`/`DIVIDE` (as demais features do "inteiro v7" de B3.1) **não** são
    /// herdadas (só `ARMV7A` as habilita, por decisão do épico — nenhum preset anterior
    /// precisa delas), então precisam ser declaradas aqui explicitamente, junto com
    /// {@link ArmFeature#VFPV2}.
    ///
    /// **Desvio do plano original da task, documentado (mesmo padrão de honestidade de
    /// B3.1/B3.2)**: a spec previa `.withDecoderExtensions(List.of(new VfpDecoder(), new
    /// CoprocessorDecoder(), new ArmV7MediaDecoder()))` — mas `ArmV7MediaDecoder` NUNCA foi
    /// criada. B3.1 descobriu que as 13 instruções "media" v7 (`MOVW`/`MOVT`/`MLS`/`SBFX`/
    /// `UBFX`/`BFI`/`BFC`/`RBIT`/`SDIV`/`UDIV`/`DMB`/`DSB`/`ISB`) colidem com dispatches
    /// genéricos do {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} que retornam
    /// ANTES do loop de extensões — viraram carve-outs diretos no próprio `ArmDecoder`,
    /// gateados pelas features acima (mesmo padrão de `BLX`/`CLZ`/`UMAAL`). Este preset só
    /// precisa, portanto, das features certas (já herdadas) mais {@link VfpDecoder}/
    /// {@link dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder} como extensões de
    /// decoder — sem nenhuma classe de "media decoder" plugável.
    ///
    /// `withThumb32DecoderExtensions` SUBSTITUI a lista (não soma à da base) — por isso as
    /// 7 extensões de {@link #ARMV6K_THUMB2} são listadas de novo aqui, mais
    /// {@link dev.vitorsilverio.armjitter.decoder.VfpDecoder} (regra da B3.5: `VfpDecoder`
    /// deve vir ANTES de qualquer decoder de coprocessador genérico na ordem de registro,
    /// senão `Thumb2CoprocessorDecoder` captura o encoding CP10/11 primeiro).
    ///
    /// **User-level only**: sem MMU/CP15-VMSA (páginas, TLB, modos privilegiados de
    /// sistema completo — isso é a task B4.1). **Sem NEON** (fora do escopo do épico B3,
    /// nenhum `IrOp`/decoder SIMD de 64/128 bits existe). `SDIV`/`UDIV` habilitados (nota
    /// v7VE da decisão nº 5 do épico `b3-armv7a-vfp.md`: nem todo core ARMv7-A tem divisão
    /// inteira em hardware — é uma extensão opcional "virtualization extensions" —, mas
    /// este preset emula um core que a possui, como o Cortex-A15/A7).
    /// Só as FEATURES de {@link #ARMV7A} — existe separadamente pelo mesmo quebra-cabeça do
    /// ovo e da galinha de {@link #ARMV6K_THUMB2_FEATURES}: {@link
    /// dev.vitorsilverio.armjitter.decoder.VfpDecoder}/{@link
    /// dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder} recebem uma {@code
    /// ArmArchitecture} no construtor (para gatear {@link ArmFeature#VFPV2} em tempo de
    /// decode) e {@code ARMV7A} ainda não existe no ponto em que essas extensões
    /// precisam ser construídas.
    private static final ArmArchitecture ARMV7A_FEATURES = extending(ARMV6K_THUMB2, "ARMv7-A",
            ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE,
            ArmFeature.DIVIDE, ArmFeature.VFPV2);

    public static final ArmArchitecture ARMV7A = ARMV7A_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV6K_THUMB2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV6K_THUMB2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6K_THUMB2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

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
