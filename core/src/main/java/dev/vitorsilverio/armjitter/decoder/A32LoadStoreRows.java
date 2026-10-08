package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.List;

import static dev.vitorsilverio.armjitter.decoder.A32Rows.PROGRAM_COUNTER;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.bit;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.claimed;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.condition;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.nibble;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.row;
import static dev.vitorsilverio.armjitter.decoder.A32Rows.undefined;

/// E15.16a: A32 — loads e stores: `LDR`/`STR` (ARM DDI 0406C A5.3), as formas "extra" de
/// halfword/doubleword (A5.2.8), `SWP` e acessos exclusivos/acquire-release (A5.2.10) e
/// `LDM`/`STM` (A5.5).
final class A32LoadStoreRows {
    private static final int PRE_INDEX_BIT = 24;
    private static final int ADD_OFFSET_BIT = 23;
    /// `B` no `LDR`/`STR`/`SWP`, `S` no `LDM`/`STM`, offset imediato na forma de halfword.
    private static final int BIT_22 = 22;
    private static final int WRITEBACK_BIT = 21;
    private static final int LOAD_BIT = 20;
    private static final int REGISTER_OFFSET_BIT = 25;
    private static final int WORD_BYTES = 4;
    private static final int DOUBLEWORD_BYTES = 8;
    /// `bits[6:5]` da forma de halfword: `01` = halfword sem sinal.
    private static final int TRANSFER_UNSIGNED_HALFWORD = 0b01;
    private static final int TRANSFER_SIGNED_BYTE = 0b10;
    /// Bytes do acesso exclusivo por `bits[22:21]`: word, doubleword, byte, halfword.
    private static final int[] EXCLUSIVE_SIZE_BYTES = {4, 8, 1, 2};
    /// `bits[11:8] = 1100`: acquire/release sem exclusividade (`LDA*`/`STL*`).
    private static final int ACQUIRE_RELEASE_PLAIN = 0b1100;
    private static final int LINK_REGISTER = 14;

    static final List<DecodeRow<ArmFeature, DecodedInstruction>> ROWS = List.of(
            row(".... 010. .... .... .... .... .... ....", A32LoadStoreRows::word),
            row(".... 011. .... .... .... .... ...0 ....", A32LoadStoreRows::word),
            row(".... 100. .... .... .... .... .... ....", A32LoadStoreRows::multiple),
            row(".... 000. .... .... .... .... 1011 ....", A32LoadStoreRows::halfword),
            row(".... 000. ...1 .... .... .... 11.1 ....", A32LoadStoreRows::halfword),
            claimed(".... 000. ...0 .... .... .... 11.1 ....", ArmFeature.LDRD_STRD, A32LoadStoreRows::doubleword),
            row(".... 0001 0.00 .... .... 0000 1001 ....", A32LoadStoreRows::swap),
            claimed(".... 0001 100. .... .... 1111 1001 ....", ArmFeature.EXCLUSIVE_WORD, A32LoadStoreRows::exclusive),
            claimed(".... 0001 101. .... .... 1111 1001 ....", ArmFeature.EXCLUSIVE_SIZED, A32LoadStoreRows::exclusive),
            claimed(".... 0001 11.. .... .... 1111 1001 ....", ArmFeature.EXCLUSIVE_SIZED, A32LoadStoreRows::exclusive),
            claimed(".... 0001 1... .... .... 1110 1001 ....", ArmFeature.LOAD_ACQUIRE_STORE_RELEASE,
                    A32LoadStoreRows::exclusive),
            claimed(".... 0001 1... .... .... 1100 1001 ....", ArmFeature.LOAD_ACQUIRE_STORE_RELEASE,
                    A32LoadStoreRows::exclusive),
            // Os outros 13 valores de `bits[11:8]` não têm instrução.
            row(".... 0001 1... .... .... 0... 1001 ....", A32Rows::undefined),
            row(".... 0001 1... .... .... 10.. 1001 ....", A32Rows::undefined),
            row(".... 0001 1... .... .... 1101 1001 ....", A32Rows::undefined));

    private A32LoadStoreRows() {
    }

    /// `LDR`/`STR`/`LDRB`/`STRB`: `cccc 01IP UBWL nnnn dddd offset`. `P=0` com `W=1` é a forma
    /// "unprivileged" (`LDRT`/`STRT`…), com a mesma semântica de registrador do post-index.
    private static DecodedInstruction word(int raw, long address) {
        boolean immediateOffset = !bit(raw, REGISTER_OFFSET_BIT);
        boolean preIndexed = bit(raw, PRE_INDEX_BIT);
        boolean writeback = bit(raw, WRITEBACK_BIT);
        int offset = immediateOffset ? raw & 0xFFF : nibble(raw, 0);
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                bit(raw, LOAD_BIT) ? InstructionKind.LOAD : InstructionKind.STORE,
                nibble(raw, 12), nibble(raw, 16), immediateOffset ? -1 : offset,
                bit(raw, ADD_OFFSET_BIT) ? offset : -offset, immediateOffset, false, false,
                bit(raw, BIT_22) ? 1 : WORD_BYTES, false, writeback || !preIndexed, !preIndexed,
                !preIndexed && writeback);
    }

    /// `LDM`/`STM`: `cccc 100P USWL nnnn lista`.
    private static DecodedInstruction multiple(int raw, long address) {
        int mask = raw & 0xFFFF;
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                bit(raw, LOAD_BIT) ? InstructionKind.LOAD_MULTIPLE : InstructionKind.STORE_MULTIPLE,
                -1, nibble(raw, 16), -1, mask, true, false, bit(raw, BIT_22), WORD_BYTES, false,
                bit(raw, WRITEBACK_BIT), false,
                BlockTransferMode.fromArmBits(bit(raw, PRE_INDEX_BIT), bit(raw, ADD_OFFSET_BIT)), mask == 0, false);
    }

    /// Offset da forma de halfword/doubleword: `imm4H:imm4L` ou `Rm`.
    private static int extraOffset(int raw) {
        return bit(raw, BIT_22) ? ((raw >>> 4) & 0xF0) | nibble(raw, 0) : nibble(raw, 0);
    }

    /// `STRH`/`LDRH`/`LDRSB`/`LDRSH`: `cccc 000P UIWL nnnn dddd imm4H 1SH1 imm4L`.
    private static DecodedInstruction halfword(int raw, long address) {
        boolean immediateOffset = bit(raw, BIT_22);
        if (!immediateOffset && nibble(raw, 8) != 0) {
            return undefined(raw, address);
        }
        boolean preIndexed = bit(raw, PRE_INDEX_BIT);
        boolean writeback = bit(raw, WRITEBACK_BIT);
        int transferKind = (raw >>> 5) & 0x3;
        int offset = extraOffset(raw);
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                bit(raw, LOAD_BIT) ? InstructionKind.LOAD : InstructionKind.STORE,
                nibble(raw, 12), nibble(raw, 16), immediateOffset ? -1 : offset,
                bit(raw, ADD_OFFSET_BIT) ? offset : -offset, immediateOffset, false, false,
                transferKind == TRANSFER_SIGNED_BYTE ? 1 : 2, transferKind != TRANSFER_UNSIGNED_HALFWORD,
                writeback || !preIndexed, !preIndexed, !preIndexed && writeback);
    }

    /// `LDRD` (`bits[6:5] = 10`) / `STRD` (`11`), ARMv5TE.
    private static DecodedInstruction doubleword(int raw, long address) {
        boolean immediateOffset = bit(raw, BIT_22);
        boolean preIndexed = bit(raw, PRE_INDEX_BIT);
        int offset = extraOffset(raw);
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                InstructionKind.DOUBLE_TRANSFER, nibble(raw, 12), nibble(raw, 16), immediateOffset ? -1 : offset,
                bit(raw, ADD_OFFSET_BIT) ? offset : -offset, immediateOffset, false, !bit(raw, 5),
                DOUBLEWORD_BYTES, false, bit(raw, WRITEBACK_BIT) || !preIndexed, !preIndexed);
    }

    /// `SWP`/`SWPB`: `cccc 0001 0B00 nnnn dddd 0000 1001 mmmm`.
    private static DecodedInstruction swap(int raw, long address) {
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw), InstructionKind.SWAP,
                nibble(raw, 12), nibble(raw, 16), nibble(raw, 0), 0, false, false, false,
                bit(raw, BIT_22) ? 1 : WORD_BYTES, false);
    }

    /// Exclusivos e acquire/release: `cccc 0001 1szL nnnn dddd XXXX 1001 mmmm`. `XXXX = 1111`
    /// (`LDREX*`/`STREX*`) e `1110` (`LDAEX*`/`STLEX*`) tocam o monitor; `1100` (`LDA*`/`STL*`)
    /// é carga/escrita simples em `[Rn]` — a ordenação é NOP neste interpretador.
    private static DecodedInstruction exclusive(int raw, long address) {
        boolean plain = nibble(raw, 8) == ACQUIRE_RELEASE_PLAIN;
        boolean load = bit(raw, LOAD_BIT);
        int sizeBytes = EXCLUSIVE_SIZE_BYTES[(raw >>> WRITEBACK_BIT) & 0x3];
        int rn = nibble(raw, 16);
        int rd = nibble(raw, 12);
        int rm = nibble(raw, 0);
        // Load tem `bits[3:0] = 1111`; `STL*` tem `bits[15:12] = 1111` (não há registrador de status).
        boolean formValid = load ? rm == PROGRAM_COUNTER : !plain || rd == PROGRAM_COUNTER;
        if (!formValid || (plain && sizeBytes == DOUBLEWORD_BYTES)) {
            return undefined(raw, address);
        }
        if (!plain) {
            if (!exclusiveRegistersValid(load, sizeBytes, rd, rn, rm)) {
                return undefined(raw, address);
            }
            return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                    load ? InstructionKind.LOAD_EXCLUSIVE : InstructionKind.STORE_EXCLUSIVE, rd, rn,
                    load ? -1 : rm, 0, false, false, false, sizeBytes, false);
        }
        int data = load ? rd : rm;
        if (rn == PROGRAM_COUNTER || data == PROGRAM_COUNTER) {
            return undefined(raw, address);
        }
        return new DecodedInstruction((int) address, raw, InstructionSet.ARM, condition(raw),
                load ? InstructionKind.LOAD : InstructionKind.STORE, data, rn, -1, 0, true, false, false,
                sizeBytes, false, false, false, false);
    }

    /// Restrições de registrador dos acessos exclusivos (UNPREDICTABLE no hardware → aqui
    /// indefinido): PC em qualquer campo; nas formas doubleword o primeiro registrador do par é
    /// par e diferente de r14; no `STREX` o status `Rd` não coincide com a base nem com o dado.
    private static boolean exclusiveRegistersValid(boolean load, int sizeBytes, int rd, int rn, int rm) {
        if (rn == PROGRAM_COUNTER || rd == PROGRAM_COUNTER) {
            return false;
        }
        boolean pair = sizeBytes == DOUBLEWORD_BYTES;
        if (load) {
            return !pair || (rd % 2 == 0 && rd != LINK_REGISTER);
        }
        if (rm == PROGRAM_COUNTER) {
            return false;
        }
        if (pair && (rm % 2 != 0 || rm == LINK_REGISTER)) {
            return false;
        }
        if (rd == rn || rd == rm) {
            return false;
        }
        return !pair || rd != rm + 1;
    }
}
