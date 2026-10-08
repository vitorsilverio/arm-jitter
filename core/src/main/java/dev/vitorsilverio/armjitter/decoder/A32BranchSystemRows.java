package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.List;

import static dev.vitorsilverio.armjitter.decoder.A32Rows.PROGRAM_COUNTER;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.bit;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.claimed;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.instruction;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.nibble;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.row;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.undefined;

/// E15.16a: A32 — branches, chamadas de sistema e acesso ao PSR (ARM DDI 0406C A5.2.12
/// "Miscellaneous instructions", A5.5 e A5.6).
final class A32BranchSystemRows {
    private static final int BRANCH_OFFSET_BITS = 24;
    /// O PC lido por uma instrução ARM está duas instruções à frente.
    private static final int ARM_PC_OFFSET = 8;
    private static final int SWI_COMMENT_MASK = 0x00FF_FFFF;
    private static final int SWI_NUMBER_SHIFT = 16;
    private static final int SPSR_BIT = 22;
    /// Marca, no campo empacotado do `MSR`, que o alvo é o `SPSR`.
    private static final int MSR_SPSR_FLAG = 0x10;
    private static final int BANKED_SYSM_HIGH_BIT = 8;
    private static final int BANKED_SYSM_HIGH_SHIFT_INTO_SYSM = 4;

    /// `MSR` registrador com `bit25=1`: a máscara da cascata antiga (`0x0DB0_FFF0`) deixava o
    /// `bit25` livre, então um `MSR` IMEDIATO com rotação zero e `imm8 < 16` sai como `MSR`
    /// registrador. Preservado (a E15.16a é refactor puro); a correção é a task `E23`.
    static final DecodeRow<ArmFeature, DecodedInstruction> MSR_REGISTER_SHADOW =
            row(".... 0011 0.10 .... 1111 0000 0000 ....", A32BranchSystemRows::msrRegister);

    /// `MSR` imediato: `cccc 0011 0R10 mask 1111 rot imm8`.
    static final DecodeRow<ArmFeature, DecodedInstruction> MSR_IMMEDIATE =
            row(".... 0011 0.10 .... 1111 .... .... ....", A32BranchSystemRows::msrImmediate);

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = List.of(
            row(".... 1111 .... .... .... .... .... ....", A32BranchSystemRows::softwareInterrupt),
            row(".... 101. .... .... .... .... .... ....", A32BranchSystemRows::branch),
            row(".... 0001 0010 1111 1111 1111 0001 ....", (raw, address) -> branchExchange(raw, address, false)),
            claimed(".... 0001 0010 1111 1111 1111 0011 ....", ArmFeature.BLX,
                    (raw, address) -> branchExchange(raw, address, true)),
            claimed(".... 0001 0100 .... .... .... 0111 ....", ArmFeature.HYPERVISOR_CALL,
                    (raw, address) -> call16(raw, address, InstructionKind.HVC)),
            claimed(".... 0001 0000 .... .... .... 0111 ....", ArmFeature.HALT,
                    (raw, address) -> call16(raw, address, InstructionKind.HALT)),
            claimed(".... 0001 0110 0000 0000 0000 0111 ....", ArmFeature.SECURE_MONITOR_CALL,
                    A32BranchSystemRows::secureMonitorCall),
            claimed(".... 0001 0110 0000 0000 0000 0110 1110", ArmFeature.VIRTUALIZATION_EXTENSIONS,
                    (raw, address) -> noOperand(raw, address, InstructionKind.ERET)),
            claimed(".... 0011 0010 0000 1111 0000 0000 0011", ArmFeature.WAIT_HINTS,
                    (raw, address) -> noOperand(raw, address, InstructionKind.WAIT_FOR_INTERRUPT)),
            claimed(".... 0001 0.00 .... .... 001. 0000 0000", ArmFeature.VIRTUALIZATION_EXTENSIONS,
                    A32BranchSystemRows::mrsBanked),
            claimed(".... 0001 0.10 .... 1111 001. 0000 ....", ArmFeature.VIRTUALIZATION_EXTENSIONS,
                    A32BranchSystemRows::msrBanked),
            row(".... 0001 0.00 1111 .... 0000 0000 0000", A32BranchSystemRows::mrs),
            row(".... 0001 0.10 .... 1111 0000 0000 ....", A32BranchSystemRows::msrRegister));

    private A32BranchSystemRows() {
    }

    private static DecodedInstruction softwareInterrupt(int raw, long address) {
        return instruction(raw, address, InstructionKind.SWI, -1, -1, -1,
                (raw & SWI_COMMENT_MASK) >> SWI_NUMBER_SHIFT, true, false, false);
    }

    private static DecodedInstruction branch(int raw, long address) {
        int shift = Integer.SIZE - BRANCH_OFFSET_BITS;
        int offset = ((raw << shift) >> shift) << 2;
        return instruction(raw, address, InstructionKind.BRANCH, -1, -1, -1,
                (int) address + ARM_PC_OFFSET + offset, true, false, bit(raw, BRANCH_OFFSET_BITS));
    }

    /// `BX` / `BLX` (registrador): `cccc 0001 0010 1111 1111 1111 00L1 mmmm`.
    private static DecodedInstruction branchExchange(int raw, long address, boolean link) {
        return instruction(raw, address, InstructionKind.BRANCH_EXCHANGE, -1, nibble(raw, 0), -1, 0, false, false,
                link);
    }

    /// `HVC` / `HLT`: `imm16` = `bits[19:8] << 4 | bits[3:0]`.
    private static DecodedInstruction call16(int raw, long address, InstructionKind kind) {
        int imm16 = (((raw >>> 8) & 0xFFF) << 4) | nibble(raw, 0);
        return instruction(raw, address, kind, -1, -1, -1, imm16, false, false, false);
    }

    private static DecodedInstruction secureMonitorCall(int raw, long address) {
        return instruction(raw, address, InstructionKind.SMC, -1, -1, -1, nibble(raw, 0), false, false, false);
    }

    private static DecodedInstruction noOperand(int raw, long address, InstructionKind kind) {
        return instruction(raw, address, kind, -1, -1, -1, 0, false, false, false);
    }

    /// `sysm` de 5 bits = `bit8 << 4 | bits[19:16]`, resolvido com `r` = `bit22`; negativo = reservado.
    private static int bankedRegister(int raw) {
        int sysm = ((bit(raw, BANKED_SYSM_HIGH_BIT) ? 1 : 0) << BANKED_SYSM_HIGH_SHIFT_INTO_SYSM) | nibble(raw, 16);
        return BankedRegisterSysm.resolve(bit(raw, SPSR_BIT), sysm);
    }

    /// `MRS` bancado: `cccc 0001 0 r 00 mmmm dddd 001m 0000 0000`.
    private static DecodedInstruction mrsBanked(int raw, long address) {
        int rd = nibble(raw, 12);
        int packed = bankedRegister(raw);
        if (rd == PROGRAM_COUNTER || packed < 0) {
            return undefined(raw, address);
        }
        return instruction(raw, address, InstructionKind.MRS_BANK, rd, -1, -1, packed, false, false, false);
    }

    /// `MSR` bancado: `cccc 0001 0 r 10 mmmm 1111 001m 0000 nnnn`.
    private static DecodedInstruction msrBanked(int raw, long address) {
        int rn = nibble(raw, 0);
        int packed = bankedRegister(raw);
        if (rn == PROGRAM_COUNTER || packed < 0) {
            return undefined(raw, address);
        }
        return instruction(raw, address, InstructionKind.MSR_BANK, -1, rn, -1, packed, false, false, false);
    }

    private static DecodedInstruction mrs(int raw, long address) {
        return instruction(raw, address, InstructionKind.MRS, nibble(raw, 12), -1, -1, bit(raw, SPSR_BIT) ? 1 : 0,
                true, false, false);
    }

    private static int msrFields(int raw) {
        return (bit(raw, SPSR_BIT) ? MSR_SPSR_FLAG : 0) | nibble(raw, 16);
    }

    private static DecodedInstruction msrRegister(int raw, long address) {
        return instruction(raw, address, InstructionKind.MSR, -1, nibble(raw, 0), -1, msrFields(raw), false, false,
                false);
    }

    private static DecodedInstruction msrImmediate(int raw, long address) {
        int value = Integer.rotateRight(raw & 0xFF, nibble(raw, 8) * 2);
        return instruction(raw, address, InstructionKind.MSR, msrFields(raw), -1, -1, value, true, false, false);
    }
}
