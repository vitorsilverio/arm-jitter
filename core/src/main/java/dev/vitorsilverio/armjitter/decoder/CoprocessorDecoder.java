package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica o espaço de transferência de registrador de coprocessador ARM (`MCR`/`MRC`/`MCRR`/`MRRC`) que ARMv5
/// adiciona acima da base compartilhada ARMv4T. Anexado à {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture#ARMV5TE},
/// para que apenas cores ARMv5+ (o ARM9 do NDS, o ARM11 do raspi1/F3) decodifiquem estes; o decoder base não é alterado.
///
/// `MCR`/`MRC` — codificação: `cccc 1110 ooo L NNNN dddd pppp qqq 1 MMMM` — bits 27-24 = 1110, bit 4 = 1.
/// `L` seleciona `MRC` (1, ler coprocessador) vs `MCR` (0, escrever coprocessador). `CDP`, `LDC` e
/// `STC` não são produzidos aqui (o controle CP15 usa apenas `MCR`/`MRC`); elas caem para
/// UNIMPLEMENTED.
///
/// `MCRR`/`MRRC` (F3, `virtual-arm-box` raspi1) — codificação: `cccc 1100 010 L RRRR tttt pppp
/// oooo MMMM` — bits 27-21 = 1100010, distinto do espaço `MCR`/`MRC` acima (bits 27-25 = 111 vs
/// 110 aqui). `L` seleciona `MRRC` (1) vs `MCRR` (0). Diferente de `MCR`/`MRC`, não há `CRn` nem
/// `opcode2`: dois registradores ARM (`Rt`/`Rt2`) são transferidos de uma vez, e `opcode1` tem 4
/// bits (não 3). Achado real: o kernel Linux ARMv6K real usa `MCRR p15,0,Rt,Rt2,c6` em
/// `discard_old_kernel_data` (`arch/arm/mm/copypage-v6.c`) para invalidar a faixa de D-cache
/// `[Rt,Rt2]` antes de `execve()` popular uma página nova — sem este decode, a instrução caía em
/// UNDEFINED (nenhum consumidor pré-F3 — gbaemu/ARMv4T, ndsemu/ARMv5TE, armbox user-mode —
/// jamais exercitou `MCRR`/`MRRC`).
public final class CoprocessorDecoder implements DecoderExtension {
    /// Máscara/valor do espaço `MCRR`/`MRRC`: bits 27-21 fixos em `1100010`, verificado ANTES do
    /// espaço `MCR`/`MRC` (que também teria bit 4 = 1 aqui, mas bits 27-24 = 1100 ≠ 1110 já
    /// desambiguam sem precisar checar o bit 4).
    private static final int DOUBLE_MASK = 0x0FE0_0000;
    private static final int DOUBLE_VALUE = 0x0C40_0000;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if ((raw & DOUBLE_MASK) == DOUBLE_VALUE) {
            boolean load = (raw & (1 << 20)) != 0; // L: 1 = MRRC, 0 = MCRR
            int rt2 = (raw >>> 16) & 0xF;
            int rt = (raw >>> 12) & 0xF;
            int coprocessor = (raw >>> 8) & 0xF;
            int opcode1 = (raw >>> 4) & 0xF;
            int crm = raw & 0xF;
            int packed = (coprocessor & 0xF) | ((opcode1 & 0xF) << 4) | ((crm & 0xF) << 8);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    InstructionKind.COPROCESSOR_DOUBLE, rt, rt2, -1, packed, false, false, load);
        }
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
