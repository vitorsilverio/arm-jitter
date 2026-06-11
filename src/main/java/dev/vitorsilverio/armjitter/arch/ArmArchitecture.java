package dev.vitorsilverio.armjitter.arch;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// An immutable description of an ARM architecture version as a **set of features**
/// (plus optional decoder extensions), rather than a version number. Decoders and the
/// execution engine query {@link #has(ArmFeature)} at the few points where ARM versions
/// differ, so adding a new version is just declaring a new feature set here (and, for
/// genuinely new instruction groups, supplying a {@link DecoderExtension}) — the shared
/// pipeline is never forked.
public final class ArmArchitecture {
    /// ARM7TDMI — the GBA CPU (also the NDS ARM7). The base set: no extra features.
    public static final ArmArchitecture ARMV4T = of("ARMv4T");

    /// ARM9 — the NDS main CPU. ARMv4T plus the ARMv5TE feature set (no Thumb-2).
    public static final ArmArchitecture ARMV5TE = of("ARMv5TE",
            ArmFeature.BLX,
            ArmFeature.CLZ,
            ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING,
            ArmFeature.LDRD_STRD,
            ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY);

    private final String name;
    private final EnumSet<ArmFeature> features;
    private final List<DecoderExtension> decoderExtensions;

    private ArmArchitecture(String name, EnumSet<ArmFeature> features, List<DecoderExtension> decoderExtensions) {
        this.name = Objects.requireNonNull(name, "name");
        this.features = features.clone();
        this.decoderExtensions = List.copyOf(decoderExtensions);
    }

    /// Builds an architecture from a name and the features it supports.
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

    /// Returns a copy of this architecture with the given decoder extensions, used to
    /// plug in instruction groups a future version adds.
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
