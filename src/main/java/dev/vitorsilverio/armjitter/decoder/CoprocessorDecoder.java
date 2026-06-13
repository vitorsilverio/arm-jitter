package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodes the ARM coprocessor register-transfer space (`MCR`/`MRC`) that ARMv5 adds on top
/// of the shared ARMv4T base. Attached to {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture#ARMV5TE},
/// so only ARMv5 cores (the NDS ARM9) decode these; the base decoder is untouched.
///
/// Encoding: `cccc 1110 ooo L NNNN dddd pppp qqq 1 MMMM` — bits 27-24 = 1110, bit 4 = 1.
/// `L` selects `MRC` (1, read coprocessor) vs `MCR` (0, write coprocessor). `CDP`, `LDC` and
/// `STC` are not produced here (CP15 control uses only `MCR`/`MRC`); they fall through to
/// UNIMPLEMENTED.
public final class CoprocessorDecoder implements DecoderExtension {
    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if ((raw & 0x0F00_0010) != 0x0E00_0010) {
            return null;
        }
        boolean load = (raw & (1 << 20)) != 0; // L: 1 = MRC, 0 = MCR
        int opcode1 = (raw >>> 21) & 0x7;
        int crn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 12) & 0xF;
        int coprocessor = (raw >>> 8) & 0xF;
        int opcode2 = (raw >>> 5) & 0x7;
        int crm = raw & 0xF;
        int packed = (coprocessor & 0xF) | ((opcode1 & 0x7) << 4) | ((opcode2 & 0x7) << 8);
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                InstructionKind.COPROCESSOR, rd, crn, crm, packed, false, false, load);
    }
}
