package dev.vitorsilverio.armjitter.arch;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;

/// Decodes instruction encodings an architecture adds beyond the shared ARMv4T base set
/// (e.g. the ARMv5 BLX/DSP space, or a future Thumb-2 group). The base decoder consults
/// the current {@link ArmArchitecture}'s extensions before falling back to UNIMPLEMENTED,
/// so new instruction groups plug in here without touching the shared decoder.
@FunctionalInterface
public interface DecoderExtension {
    /// Tries to decode the already-fetched ARM word. Returns {@code null} if this
    /// extension does not handle the encoding (so the base decoder keeps trying).
    DecodedInstruction tryDecode(int raw, int address, Condition condition);
}
