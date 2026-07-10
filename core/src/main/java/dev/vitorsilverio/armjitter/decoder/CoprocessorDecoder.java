package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica o espaço de transferência de registrador de coprocessador ARM (`MCR`/`MRC`) que ARMv5 adiciona acima
/// da base compartilhada ARMv4T. Anexado à {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture#ARMV5TE},
/// para que apenas cores ARMv5 (o ARM9 do NDS) decodifiquem estes; o decoder base não é alterado.
///
/// Codificação: `cccc 1110 ooo L NNNN dddd pppp qqq 1 MMMM` — bits 27-24 = 1110, bit 4 = 1.
/// `L` seleciona `MRC` (1, ler coprocessador) vs `MCR` (0, escrever coprocessador). `CDP`, `LDC` e
/// `STC` não são produzidos aqui (o controle CP15 usa apenas `MCR`/`MRC`); elas caem para
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
