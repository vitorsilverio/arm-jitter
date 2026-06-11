package dev.vitorsilverio.armjitter.arch;

/// A single architectural capability or behaviour that can differ between ARM
/// architecture versions. An {@link ArmArchitecture} is defined by the set of features
/// it has, so a new version is just a different feature set — not a new code path.
///
/// Grouped loosely as instruction-availability features and behaviour policies; see
/// the gbaemu `gba-vs-nds-rules` notes for what each implies on ARMv4T vs ARMv5.
public enum ArmFeature {
    // ---- Instruction availability (decoder gates) ----
    /// `BLX` (branch with link and exchange), immediate and register forms. ARMv5+.
    BLX,
    /// `CLZ` (count leading zeros). ARMv5+.
    CLZ,
    /// DSP multiplies (SMUL\<x>\<y>, SMLA\<x>\<y>, SMLAW, SMULW, SMLAL\<x>\<y>). ARMv5TE+.
    DSP_MULTIPLY,
    /// Saturating arithmetic (QADD/QSUB/QDADD/QDSUB). ARMv5TE+.
    SATURATING,
    /// Double-word load/store (`LDRD`/`STRD`). ARMv5TE+.
    LDRD_STRD,
    /// Thumb-2 32-bit Thumb encodings. ARMv6T2/ARMv7+.
    THUMB2,

    // ---- Behaviour policies (execution forks) ----
    /// Loads into PC (`LDR`/`LDM`/`POP {PC}`) switch ARM/Thumb state from bit 0 of the
    /// loaded value. ARMv5+. On ARMv4T such loads ignore bit 0 and stay in the current
    /// state (only `BX` switches).
    LOAD_PC_INTERWORKING,
    /// Integer multiplies leave the carry flag unchanged. On ARMv4 the carry is left
    /// UNPREDICTABLE by `MUL`/`MLA`, so emulating it as "unchanged" is acceptable there.
    MUL_PRESERVES_CARRY
}
