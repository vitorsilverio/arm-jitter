package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.List;

import static dev.vitorsilverio.armjitter.decoder.A32Rows.PROGRAM_COUNTER;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.bit;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.claimed;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.instruction;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.nibble;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.optional;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.row;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.undefined;

/// E15.16a: A32 — data processing (ARM DDI 0406C A5.2): o ALU de 16 opcodes, `MOVW`/`MOVT`, `CLZ`,
/// aritmética saturante e `CRC32`.
final class A32DataProcessingRows {
    /// Opcode `bits[24:21]` → instrução.
    private static final InstructionKind[] ALU_KINDS = {
            InstructionKind.AND, InstructionKind.EOR, InstructionKind.SUB, InstructionKind.RSB,
            InstructionKind.ADD, InstructionKind.ADC, InstructionKind.SBC, InstructionKind.RSC,
            InstructionKind.TST, InstructionKind.TEQ, InstructionKind.CMP, InstructionKind.CMN,
            InstructionKind.ORR, InstructionKind.MOV, InstructionKind.BIC, InstructionKind.MVN};
    private static final int ALU_OPCODE_SHIFT = 21;
    /// `10xx`: `TST`/`TEQ`/`CMP`/`CMN`, que sempre atualizam as flags.
    private static final int ALU_COMPARE_MASK = 0b1100;
    private static final int ALU_COMPARE_VALUE = 0b1000;
    /// `11x1`: `MOV`/`MVN`, que não leem `Rn`.
    private static final int ALU_MOVE_MASK = 0b1101;
    private static final int ALU_IMMEDIATE_BIT = 25;
    private static final int SET_FLAGS_BIT = 20;
    /// `ss` = `11` não existe no `CRC32`.
    private static final int CRC32_SIZE_RESERVED = 0b11;
    private static final int CRC32_CASTAGNOLI_BIT = 9;
    private static final int CRC32_CASTAGNOLI_FLAG = 0x4;

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = List.of(
            claimed(".... 0011 0000 .... .... .... .... ....", ArmFeature.MOVW_MOVT,
                    (raw, address) -> moveWide(raw, address, InstructionKind.MOV)),
            claimed(".... 0011 0100 .... .... .... .... ....", ArmFeature.MOVW_MOVT,
                    (raw, address) -> moveWide(raw, address, InstructionKind.MOVE_TOP)),
            claimed(".... 0001 0110 1111 .... 1111 0001 ....", ArmFeature.CLZ, A32DataProcessingRows::countLeadingZeros),
            optional(".... 0001 0..0 .... .... 0000 0101 ....", ArmFeature.SATURATING, A32DataProcessingRows::saturating),
            optional(".... 0001 0..0 .... .... 00.0 0100 ....", ArmFeature.CRC32, A32DataProcessingRows::crc32));

    /// O ALU genérico: imediato, registrador com shift imediato e registrador com shift por
    /// registrador. Vive na última camada — os encodings de {@link #ROWS} e os de sistema são
    /// recortes dele.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> GENERIC = List.of(
            row(".... 001. .... .... .... .... .... ....", A32DataProcessingRows::alu),
            row(".... 000. .... .... .... .... ...0 ....", A32DataProcessingRows::alu),
            row(".... 000. .... .... .... .... 0..1 ....", A32DataProcessingRows::alu));

    private A32DataProcessingRows() {
    }

    private static DecodedInstruction alu(int raw, long address) {
        boolean immediate = bit(raw, ALU_IMMEDIATE_BIT);
        int opcode = nibble(raw, ALU_OPCODE_SHIFT);
        boolean compare = (opcode & ALU_COMPARE_MASK) == ALU_COMPARE_VALUE;
        boolean move = (opcode & ALU_MOVE_MASK) == ALU_MOVE_MASK;
        int operand = immediate ? Integer.rotateRight(raw & 0xFF, nibble(raw, 8) * 2) : nibble(raw, 0);
        return instruction(raw, address, ALU_KINDS[opcode], nibble(raw, 12), move ? -1 : nibble(raw, 16),
                immediate ? -1 : operand, operand, immediate, compare || bit(raw, SET_FLAGS_BIT), false);
    }

    /// `MOVW`/`MOVT`: `cccc 0011 0x00 imm4 Rd imm12`.
    private static DecodedInstruction moveWide(int raw, long address, InstructionKind kind) {
        int rd = nibble(raw, 12);
        if (rd == PROGRAM_COUNTER) {
            return undefined(raw, address);
        }
        int imm16 = (nibble(raw, 16) << 12) | (raw & 0xFFF);
        return instruction(raw, address, kind, rd, -1, -1, imm16, true, false, false);
    }

    private static DecodedInstruction countLeadingZeros(int raw, long address) {
        return instruction(raw, address, InstructionKind.CLZ, nibble(raw, 12), nibble(raw, 0), -1, 0, false, false,
                false);
    }

    /// `QADD`/`QSUB`/`QDADD`/`QDSUB`: `cccc 0001 0PP0 nnnn dddd 0000 0101 mmmm`.
    private static DecodedInstruction saturating(int raw, long address) {
        return instruction(raw, address, InstructionKind.SATURATING, nibble(raw, 12), nibble(raw, 0),
                nibble(raw, 16), (raw >>> 21) & 0x3, false, false, false);
    }

    /// `CRC32{B,H,W}`/`CRC32C{B,H,W}`: `cccc 0001 0ss0 nnnn dddd 00c0 0100 mmmm`; o imediato leva
    /// `ss` (0=B, 1=H, 2=W) e o polinômio.
    private static DecodedInstruction crc32(int raw, long address) {
        int size = (raw >>> 21) & 0x3;
        if (size == CRC32_SIZE_RESERVED) {
            return undefined(raw, address);
        }
        int packed = size | (bit(raw, CRC32_CASTAGNOLI_BIT) ? CRC32_CASTAGNOLI_FLAG : 0);
        return instruction(raw, address, InstructionKind.CRC32, nibble(raw, 12), nibble(raw, 0), nibble(raw, 16),
                packed, false, false, false);
    }
}
