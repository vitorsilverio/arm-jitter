package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.List;

import static dev.vitorsilverio.armjitter.decoder.A32Rows.bit;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.claimed;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.nibble;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.unconditional;

/// E15.16a: A32 — o espaço incondicional (`cond = 1111`, ARM DDI 0406C A5.7; E6). Todas as linhas
/// gravam `AL`, e todas ficam com o espaço mesmo sem a feature.
final class A32UnconditionalRows {
    private static final int BRANCH_OFFSET_BITS = 24;
    /// O PC lido por uma instrução ARM está duas instruções à frente.
    private static final int ARM_PC_OFFSET = 8;
    private static final int THUMB_BIT = 1;
    private static final int SETEND_ENDIAN_BIT = 9;
    private static final int MODE_MASK = 0x1F;
    private static final int CPS_IMOD_SHIFT = 18;
    private static final int CPS_MODE_CHANGE_BIT = 17;
    private static final int CPS_A_BIT = 8;
    private static final int CPS_I_BIT = 7;
    private static final int CPS_F_BIT = 6;
    private static final int PRE_INDEX_BIT = 24;
    private static final int ADD_OFFSET_BIT = 23;
    private static final int WRITEBACK_BIT = 21;

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = List.of(
            claimed("1111 101. .... .... .... .... .... ....", ArmFeature.BLX_IMMEDIATE,
                    A32UnconditionalRows::branchLinkExchange),
            claimed("1111 0001 0000 0001 0000 00.0 0000 0000", ArmFeature.SETEND_BIG_ENDIAN_DATA,
                    A32UnconditionalRows::setEndianness),
            claimed("1111 0001 0000 ...0 0000 000. ..0. ....", ArmFeature.MODE_CHANGE_INSTRUCTIONS,
                    A32UnconditionalRows::changeProcessorState),
            claimed("1111 0101 0111 1111 1111 0000 0001 1111", ArmFeature.EXCLUSIVE_SIZED,
                    (raw, address) -> unconditional(raw, address, InstructionKind.CLEAR_EXCLUSIVE, -1, 0, false, false)),
            // PLD / PLDW / PLI, imediato-literal e registrador.
            claimed("1111 0101 .101 .... 1111 .... .... ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            claimed("1111 0101 .001 .... 1111 .... .... ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            claimed("1111 0100 .101 .... 1111 .... .... ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            claimed("1111 0111 .101 .... 1111 .... ...0 ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            claimed("1111 0111 .001 .... 1111 .... ...0 ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            claimed("1111 0110 .101 .... 1111 .... ...0 ....", ArmFeature.PRELOAD_HINTS, A32UnconditionalRows::preload),
            // DSB / DMB / ISB: `option` (bits 3:0) vai no imediato.
            claimed("1111 0101 0111 1111 1111 0000 0100 ....", ArmFeature.MEMORY_BARRIERS, A32UnconditionalRows::barrier),
            claimed("1111 0101 0111 1111 1111 0000 0101 ....", ArmFeature.MEMORY_BARRIERS, A32UnconditionalRows::barrier),
            claimed("1111 0101 0111 1111 1111 0000 0110 ....", ArmFeature.MEMORY_BARRIERS, A32UnconditionalRows::barrier),
            claimed("1111 0101 0111 1111 1111 0000 0111 0000", ArmFeature.SPECULATION_BARRIER,
                    A32UnconditionalRows::barrier),
            claimed("1111 100. .1.0 1101 0000 0101 000. ....", ArmFeature.MODE_CHANGE_INSTRUCTIONS,
                    A32UnconditionalRows::storeReturnState),
            claimed("1111 100. .0.1 .... 0000 1010 0000 0000", ArmFeature.MODE_CHANGE_INSTRUCTIONS,
                    A32UnconditionalRows::returnFromException));

    private A32UnconditionalRows() {
    }

    /// `BLX` imediato: `1111 101H imm24` — sempre linka e troca para Thumb (bit 0 do alvo ligado).
    private static DecodedInstruction branchLinkExchange(int raw, long address) {
        int shift = Integer.SIZE - BRANCH_OFFSET_BITS;
        int offset = (((raw << shift) >> shift) << 2) + ((bit(raw, BRANCH_OFFSET_BITS) ? 1 : 0) << 1);
        int target = (int) address + ARM_PC_OFFSET + offset;
        return unconditional(raw, address, InstructionKind.BRANCH_EXCHANGE, -1, target | THUMB_BIT, false, true);
    }

    /// `SETEND`: `1111 0001 0000 0001 0000 00E0 0000 0000`.
    private static DecodedInstruction setEndianness(int raw, long address) {
        return unconditional(raw, address, InstructionKind.SETEND, -1, bit(raw, SETEND_ENDIAN_BIT) ? 1 : 0, false,
                false);
    }

    /// `CPS`: `1111 0001 0000 imod M 0 0000000 A I F 0 mode`, empacotado em `imod | M<<2 | A<<3 | I<<4 | F<<5 | mode<<6`.
    private static DecodedInstruction changeProcessorState(int raw, long address) {
        int packed = ((raw >>> CPS_IMOD_SHIFT) & 0x3) | (flag(raw, CPS_MODE_CHANGE_BIT) << 2)
                | (flag(raw, CPS_A_BIT) << 3) | (flag(raw, CPS_I_BIT) << 4) | (flag(raw, CPS_F_BIT) << 5)
                | ((raw & MODE_MASK) << 6);
        return unconditional(raw, address, InstructionKind.CPS, -1, packed, false, false);
    }

    private static int flag(int raw, int index) {
        return (raw >>> index) & 1;
    }

    /// Hints de preload: sem efeito observável — saem como `MSR` imediato de máscara de campo vazia.
    private static DecodedInstruction preload(int raw, long address) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, Condition.AL, InstructionKind.MSR,
                0, -1, -1, 0, true, false, false);
    }

    private static DecodedInstruction barrier(int raw, long address) {
        return unconditional(raw, address, InstructionKind.MEMORY_BARRIER, -1, nibble(raw, 0), false, false);
    }

    /// `SRS`: `1111 100P U1W0 1101 0000 0101 000 mode`.
    private static DecodedInstruction storeReturnState(int raw, long address) {
        return blockTransfer(raw, address, InstructionKind.STORE_RETURN_STATE, -1, raw & MODE_MASK);
    }

    /// `RFE`: `1111 100P U0W1 nnnn 0000 1010 0000 0000`.
    private static DecodedInstruction returnFromException(int raw, long address) {
        return blockTransfer(raw, address, InstructionKind.RETURN_FROM_EXCEPTION, nibble(raw, 16), 0);
    }

    private static DecodedInstruction blockTransfer(int raw, long address, InstructionKind kind, int rn,
            int immediate) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, Condition.AL, kind, -1, rn, -1,
                immediate, false, false, false, 0, false, bit(raw, WRITEBACK_BIT), false,
                BlockTransferMode.fromArmBits(bit(raw, PRE_INDEX_BIT), bit(raw, ADD_OFFSET_BIT)));
    }
}
