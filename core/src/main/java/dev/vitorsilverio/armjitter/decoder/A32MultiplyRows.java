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

/// E15.16a: A32 — multiplicações (ARM DDI 0406C A5.2.5), as de halfword do ARMv5TE (A5.2.7) e as
/// "signed multiply" do espaço media do ARMv6 (A5.4.4).
final class A32MultiplyRows {
    /// `bit22`/`bit21` da multiplicação longa → instrução.
    private static final InstructionKind[] LONG_KINDS = {
            InstructionKind.UMULL, InstructionKind.UMLAL, InstructionKind.SMULL, InstructionKind.SMLAL};
    private static final int ACCUMULATE_BIT = 21;
    private static final int SET_FLAGS_BIT = 20;
    private static final int SUBTRACT_BIT = 6;
    private static final int EXCHANGE_OR_ROUND_BIT = 5;
    private static final int DUAL_LONG_BIT = 22;
    private static final int PACKED_SUBTRACT = 1 << 4;
    private static final int PACKED_EXCHANGE_OR_ROUND = 1 << 5;
    private static final int PACKED_LONG = 1 << 6;

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = List.of(
            row(".... 0000 00.. .... .... .... 1001 ....", A32MultiplyRows::multiply),
            row(".... 0000 1... .... .... .... 1001 ....", A32MultiplyRows::multiplyLong),
            optional(".... 0000 0100 .... .... .... 1001 ....", ArmFeature.UMAAL, A32MultiplyRows::umaal),
            claimed(".... 0000 0110 .... .... .... 1001 ....", ArmFeature.MLS_MULTIPLY, A32MultiplyRows::mls),
            optional(".... 0001 0..0 .... .... .... 1..0 ....", ArmFeature.DSP_MULTIPLY, A32MultiplyRows::halfword),
            optional(".... 0111 0.00 .... .... .... 0..1 ....", ArmFeature.SIGNED_MULTIPLY_MEDIA, A32MultiplyRows::dual),
            optional(".... 0111 0101 .... .... .... ...1 ....", ArmFeature.SIGNED_MULTIPLY_MEDIA,
                    A32MultiplyRows::topWord));

    /// O que sobra de `cccc 000x … 1001 …` (o `UMAAL` sem a feature e os opcodes sem instrução): indefinido.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> RESIDUE = List.of(
            row(".... 000. .... .... .... .... 1001 ....", A32Rows::undefined));

    private A32MultiplyRows() {
    }

    /// `MUL`/`MLA`: `cccc 0000 00AS dddd nnnn ssss 1001 mmmm`.
    private static DecodedInstruction multiply(int raw, long address) {
        return instruction(raw, address, bit(raw, ACCUMULATE_BIT) ? InstructionKind.MLA : InstructionKind.MUL,
                nibble(raw, 16), nibble(raw, 0), nibble(raw, 8), nibble(raw, 12), false, bit(raw, SET_FLAGS_BIT),
                false);
    }

    /// `UMULL`/`UMLAL`/`SMULL`/`SMLAL`: `cccc 0000 1UAS hhhh llll ssss 1001 mmmm`.
    private static DecodedInstruction multiplyLong(int raw, long address) {
        return instruction(raw, address, LONG_KINDS[(raw >>> ACCUMULATE_BIT) & 0x3], nibble(raw, 12), nibble(raw, 0),
                nibble(raw, 8), nibble(raw, 16), false, bit(raw, SET_FLAGS_BIT), false);
    }

    /// `UMAAL`: `cccc 0000 0100 hhhh llll ssss 1001 mmmm`.
    private static DecodedInstruction umaal(int raw, long address) {
        return instruction(raw, address, InstructionKind.UMAAL, nibble(raw, 12), nibble(raw, 0), nibble(raw, 8),
                nibble(raw, 16), false, false, false);
    }

    /// `MLS`: `cccc 0000 0110 dddd aaaa mmmm 1001 nnnn` — `Rd = Ra − Rn×Rm`.
    private static DecodedInstruction mls(int raw, long address) {
        int rd = nibble(raw, 16);
        int ra = nibble(raw, 12);
        int rm = nibble(raw, 8);
        int rn = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || ra == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER) {
            return A32Rows.undefined(raw, address);
        }
        return instruction(raw, address, InstructionKind.MLS, rd, rn, rm, ra, false, false, false);
    }

    /// `SMLAxy`/`SMULxy`/`SMLAWy`/`SMULWy`/`SMLALxy`: `cccc 0001 0PP0 dddd nnnn ssss 1yx0 mmmm`; as
    /// metades de 16 bits e o acumulador vão empacotados no imediato.
    private static DecodedInstruction halfword(int raw, long address) {
        int packed = nibble(raw, 12) | (((raw >>> 21) & 0x3) << 4) | (((raw >>> 5) & 0x3) << 6);
        return instruction(raw, address, InstructionKind.DSP_MULTIPLY, nibble(raw, 16), nibble(raw, 0),
                nibble(raw, 8), packed, false, false, false);
    }

    /// `SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`: `cccc 0111 0L00 dddd aaaa mmmm 0sM1 nnnn`.
    private static DecodedInstruction dual(int raw, long address) {
        int rd = nibble(raw, 16);
        int ra = nibble(raw, 12);
        int rm = nibble(raw, 8);
        int rn = nibble(raw, 0);
        boolean longForm = bit(raw, DUAL_LONG_BIT);
        if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER
                || (longForm && ra == PROGRAM_COUNTER)) {
            return A32Rows.undefined(raw, address);
        }
        int packed = ra | (bit(raw, SUBTRACT_BIT) ? PACKED_SUBTRACT : 0)
                | (bit(raw, EXCHANGE_OR_ROUND_BIT) ? PACKED_EXCHANGE_OR_ROUND : 0) | (longForm ? PACKED_LONG : 0);
        return instruction(raw, address, InstructionKind.DSP_DUAL_MULTIPLY, rd, rm, rn, packed, false, false, false);
    }

    /// `SMMLA{R}`/`SMMLS{R}`: `cccc 0111 0101 dddd aaaa mmmm sr01 nnnn`.
    private static DecodedInstruction topWord(int raw, long address) {
        int rd = nibble(raw, 16);
        int rm = nibble(raw, 8);
        int rn = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER) {
            return A32Rows.undefined(raw, address);
        }
        int packed = nibble(raw, 12) | (bit(raw, SUBTRACT_BIT) ? PACKED_SUBTRACT : 0)
                | (bit(raw, EXCHANGE_OR_ROUND_BIT) ? PACKED_EXCHANGE_OR_ROUND : 0);
        return instruction(raw, address, InstructionKind.DSP_TOP_WORD_MULTIPLY, rd, rn, rm, packed, false, false,
                false);
    }
}
