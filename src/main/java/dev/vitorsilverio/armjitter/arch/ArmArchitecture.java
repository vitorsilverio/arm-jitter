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
            ArmFeature.LDM_WRITEBACK_BASE_IN_LIST)
            .withDecoderExtensions(List.of(new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()));

    private final String name;
    private final EnumSet<ArmFeature> features;
    private final List<DecoderExtension> decoderExtensions;

    private ArmArchitecture(String name, EnumSet<ArmFeature> features, List<DecoderExtension> decoderExtensions) {
        this.name = Objects.requireNonNull(name, "name");
        this.features = features.clone();
        this.decoderExtensions = List.copyOf(decoderExtensions);
    }

    /// Constrói uma arquitetura a partir de um nome e das features que ela suporta.
    public static ArmArchitecture of(String name, ArmFeature... features) {
        EnumSet<ArmFeature> set = EnumSet.noneOf(ArmFeature.class);
        Collections.addAll(set, features);
        return new ArmArchitecture(name, set, List.of());
    }

    public boolean has(ArmFeature feature) {
        return features.contains(feature);
    }

    public List<DecoderExtension> decoderExtensions() {
        return decoderExtensions;
    }

    /// Retorna uma cópia desta arquitetura com as extensões de decoder fornecidas, usadas para
    /// plugar grupos de instruções que uma versão futura adiciona.
    public ArmArchitecture withDecoderExtensions(List<DecoderExtension> extensions) {
        return new ArmArchitecture(name, features, extensions);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
