package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.ArrayList;
import java.util.List;

import static dev.vitorsilverio.armjitter.decoder.A32Rows.PROGRAM_COUNTER;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.bit;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.claimed;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.condition;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.instruction;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.nibble;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.optional;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.row;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.undefined;

/// E15.16a: A32 — media instructions (ARM DDI 0406C A5.4): aritmética paralela, pack/saturate,
/// extend, byte-reverse, bit field, `RBIT`, divisão e `UDF`.
final class A32MediaRows {
    /// `ppp` (`bits[22:20]`) da aritmética paralela: S, Q, SH, U, UQ, UH — `000`/`100` são buracos.
    private static final List<String> PARALLEL_VARIANTS = List.of("001", "01.", "101", "11.");
    /// `ttt` (`bits[7:5]`): ADD16, ASX, SAX, SUB16, ADD8, SUB8 — `101`/`110` são buracos.
    private static final List<String> PARALLEL_OPERATIONS = List.of("0..", "100", "111");
    /// `Rn = 1111` marca a forma sem acumulador (`SXTB` vs `SXTAB`, `USAD8` vs `USADA8`, `BFC` vs `BFI`).
    private static final int NO_REGISTER = 0xF;
    private static final int UNSIGNED_BIT = 22;
    private static final int SHIFT_ASR_BIT = 6;
    private static final int FIVE_BITS = 0x1F;
    private static final int PKH_TB_FLAG = 1 << 5;
    private static final int SATURATE_SHIFT_IN_PACKED = 5;
    private static final int SATURATE_ASR_FLAG = 1 << 10;
    private static final int SATURATE_HALFWORDS_FLAG = 1 << 11;
    private static final int SATURATE_UNSIGNED_FLAG = 1 << 12;
    private static final int EXTEND_UNSIGNED_FLAG = 1 << 4;
    private static final int BIT_FIELD_WIDTH_SHIFT = 5;
    private static final int REV = 0;
    private static final int REV16 = 1;
    private static final int REVSH = 2;

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = rows();

    /// O que sobra de `cccc 011x … xxx1 …` (media sem instrução ou sem a feature): indefinido.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> RESIDUE = List.of(
            row(".... 011. .... .... .... .... ...1 ....", A32Rows::undefined));

    private A32MediaRows() {
    }

    private static List<DecodeRow<ArmFeature, DecodedInstruction>> rows() {
        List<DecodeRow<ArmFeature, DecodedInstruction>> rows = new ArrayList<>();
        for (String variant : PARALLEL_VARIANTS) {
            for (String operation : PARALLEL_OPERATIONS) {
                rows.add(optional(".... 0110 0" + variant + " .... .... 1111 " + operation + "1 ....",
                        ArmFeature.PARALLEL_SIMD, A32MediaRows::parallel));
            }
        }
        rows.add(optional(".... 0110 1000 .... .... 1111 1011 ....", ArmFeature.PARALLEL_SIMD, A32MediaRows::select));
        rows.add(optional(".... 0110 1000 .... .... .... ..01 ....", ArmFeature.PACK_SATURATE, A32MediaRows::pack));
        rows.add(optional(".... 0110 1.1. .... .... .... ..01 ....", ArmFeature.PACK_SATURATE, A32MediaRows::saturate));
        rows.add(optional(".... 0110 1.10 .... .... 1111 0011 ....", ArmFeature.PACK_SATURATE,
                A32MediaRows::saturateHalfwords));
        rows.add(optional(".... 0111 1000 .... .... .... 0001 ....", ArmFeature.PACK_SATURATE,
                A32MediaRows::sumAbsoluteDifferences));
        rows.add(optional(".... 0110 1.00 .... .... ..00 0111 ....", ArmFeature.EXTEND_ROTATE, A32MediaRows::extend));
        rows.add(optional(".... 0110 1.1. .... .... ..00 0111 ....", ArmFeature.EXTEND_ROTATE, A32MediaRows::extend));
        rows.add(optional(".... 0110 1011 1111 .... 1111 0011 ....", ArmFeature.BYTE_REVERSE,
                (raw, address) -> byteReverse(raw, address, REV)));
        rows.add(optional(".... 0110 1011 1111 .... 1111 1011 ....", ArmFeature.BYTE_REVERSE,
                (raw, address) -> byteReverse(raw, address, REV16)));
        rows.add(optional(".... 0110 1111 1111 .... 1111 1011 ....", ArmFeature.BYTE_REVERSE,
                (raw, address) -> byteReverse(raw, address, REVSH)));
        rows.add(claimed(".... 0111 101. .... .... .... .101 ....", ArmFeature.BIT_FIELD,
                (raw, address) -> bitFieldExtract(raw, address, true)));
        rows.add(claimed(".... 0111 111. .... .... .... .101 ....", ArmFeature.BIT_FIELD,
                (raw, address) -> bitFieldExtract(raw, address, false)));
        rows.add(claimed(".... 0111 110. .... .... .... .001 ....", ArmFeature.BIT_FIELD, A32MediaRows::bitFieldInsert));
        rows.add(claimed(".... 0110 1111 1111 .... 1111 0011 ....", ArmFeature.BIT_REVERSE, A32MediaRows::bitReverse));
        rows.add(claimed(".... 0111 0001 .... 1111 .... 0001 ....", ArmFeature.DIVIDE,
                (raw, address) -> divide(raw, address, true)));
        rows.add(claimed(".... 0111 0011 .... 1111 .... 0001 ....", ArmFeature.DIVIDE,
                (raw, address) -> divide(raw, address, false)));
        // `UDF` fixa `cond = AL`; com outra condição a palavra cai na recusa de RESIDUE.
        rows.add(row("1110 0111 1111 .... .... .... 1111 ....", A32MediaRows::permanentlyUndefined));
        return List.copyOf(rows);
    }

    /// `cccc 0110 0ppp nnnn dddd 1111 ttt1 mmmm`.
    private static DecodedInstruction parallel(int raw, long address) {
        int packed = ((raw >>> 20) & 0x7) | (((raw >>> 5) & 0x7) << 3);
        return instruction(raw, address, InstructionKind.PARALLEL_ALU, nibble(raw, 12), nibble(raw, 16),
                nibble(raw, 0), packed, false, false, false);
    }

    /// `SEL`: `cccc 0110 1000 nnnn dddd 1111 1011 mmmm`.
    private static DecodedInstruction select(int raw, long address) {
        return instruction(raw, address, InstructionKind.SEL, nibble(raw, 12), nibble(raw, 16), nibble(raw, 0), 0,
                false, false, false);
    }

    /// `PKHBT`/`PKHTB`: `cccc 0110 1000 nnnn dddd iiii it01 mmmm`.
    private static DecodedInstruction pack(int raw, long address) {
        int packed = ((raw >>> 7) & FIVE_BITS) | (bit(raw, SHIFT_ASR_BIT) ? PKH_TB_FLAG : 0);
        return instruction(raw, address, InstructionKind.PKH, nibble(raw, 12), nibble(raw, 16), nibble(raw, 0),
                packed, false, false, false);
    }

    /// `SSAT`/`USAT`: `cccc 0110 1u1s ssss dddd iiii ir01 mmmm`.
    private static DecodedInstruction saturate(int raw, long address) {
        int packed = ((raw >>> 16) & FIVE_BITS) | (((raw >>> 7) & FIVE_BITS) << SATURATE_SHIFT_IN_PACKED)
                | (bit(raw, SHIFT_ASR_BIT) ? SATURATE_ASR_FLAG : 0)
                | (bit(raw, UNSIGNED_BIT) ? SATURATE_UNSIGNED_FLAG : 0);
        return instruction(raw, address, InstructionKind.SATURATE, nibble(raw, 12), -1, nibble(raw, 0), packed,
                false, false, false);
    }

    /// `SSAT16`/`USAT16`: `cccc 0110 1u10 ssss dddd 1111 0011 mmmm`.
    private static DecodedInstruction saturateHalfwords(int raw, long address) {
        int packed = nibble(raw, 16) | SATURATE_HALFWORDS_FLAG | (bit(raw, UNSIGNED_BIT) ? SATURATE_UNSIGNED_FLAG : 0);
        return instruction(raw, address, InstructionKind.SATURATE, nibble(raw, 12), -1, nibble(raw, 0), packed,
                false, false, false);
    }

    /// `USAD8`/`USADA8`: `cccc 0111 1000 dddd nnnn ssss 0001 mmmm`.
    private static DecodedInstruction sumAbsoluteDifferences(int raw, long address) {
        int rn = nibble(raw, 12);
        return instruction(raw, address, InstructionKind.USAD8, nibble(raw, 16), nibble(raw, 0), nibble(raw, 8),
                rn == NO_REGISTER ? -1 : rn, false, false, false);
    }

    /// `SXT*`/`UXT*` e as formas com acumulador: `cccc 0110 1uff nnnn dddd rr00 0111 mmmm`.
    private static DecodedInstruction extend(int raw, long address) {
        int rn = nibble(raw, 16);
        int packed = ((raw >>> 10) & 0x3) | (((raw >>> 20) & 0x3) << 2) | (bit(raw, UNSIGNED_BIT) ? EXTEND_UNSIGNED_FLAG : 0);
        return instruction(raw, address, InstructionKind.EXTEND, nibble(raw, 12), rn == NO_REGISTER ? -1 : rn,
                nibble(raw, 0), packed, false, false, false);
    }

    private static DecodedInstruction byteReverse(int raw, long address, int variant) {
        return instruction(raw, address, InstructionKind.BYTE_REVERSE, nibble(raw, 12), nibble(raw, 0), -1, variant,
                false, false, false);
    }

    /// `SBFX`/`UBFX`: `cccc 0111 1x1 widthm1 dddd lsb 101 nnnn`.
    private static DecodedInstruction bitFieldExtract(int raw, long address, boolean signedExtract) {
        int width = ((raw >>> 16) & FIVE_BITS) + 1;
        int rd = nibble(raw, 12);
        int lsb = (raw >>> 7) & FIVE_BITS;
        int rn = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || rn == PROGRAM_COUNTER || lsb + width > Integer.SIZE) {
            return undefined(raw, address);
        }
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                InstructionKind.BIT_FIELD_EXTRACT, rd, rn, -1, lsb | (width << BIT_FIELD_WIDTH_SHIFT), false, false,
                false, 0, signedExtract);
    }

    /// `BFI`/`BFC`: `cccc 0111 110 msb dddd lsb 001 nnnn` — `Rn = 1111` é `BFC`.
    private static DecodedInstruction bitFieldInsert(int raw, long address) {
        int msb = (raw >>> 16) & FIVE_BITS;
        int rd = nibble(raw, 12);
        int lsb = (raw >>> 7) & FIVE_BITS;
        int rn = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || msb < lsb) {
            return undefined(raw, address);
        }
        int packed = lsb | ((msb - lsb + 1) << BIT_FIELD_WIDTH_SHIFT);
        return instruction(raw, address, InstructionKind.BIT_FIELD_INSERT, rd, rn == NO_REGISTER ? -1 : rn, -1,
                packed, false, false, false);
    }

    /// `RBIT`: `cccc 0110 1111 1111 dddd 1111 0011 mmmm`.
    private static DecodedInstruction bitReverse(int raw, long address) {
        int rd = nibble(raw, 12);
        int rm = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER) {
            return undefined(raw, address);
        }
        return instruction(raw, address, InstructionKind.BIT_REVERSE, rd, rm, -1, 0, false, false, false);
    }

    /// `SDIV`/`UDIV`: `cccc 0111 00x1 dddd 1111 mmmm 0001 nnnn`.
    private static DecodedInstruction divide(int raw, long address, boolean signedDivide) {
        int rd = nibble(raw, 16);
        int rm = nibble(raw, 8);
        int rn = nibble(raw, 0);
        if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER) {
            return undefined(raw, address);
        }
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw), InstructionKind.DIVIDE,
                rd, rn, rm, 0, false, false, false, 0, signedDivide);
    }

    private static DecodedInstruction permanentlyUndefined(int raw, long address) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, Condition.AL, InstructionKind.UDF,
                -1, -1, -1, 0, false, false, false);
    }
}
