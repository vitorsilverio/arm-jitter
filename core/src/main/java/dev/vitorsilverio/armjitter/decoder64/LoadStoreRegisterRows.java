package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;

import java.util.List;

/// E15.12 (D5 do épico E15): "Load/store register" da classe Loads and Stores (`op0 = x1x0`) — literal, par,
/// registrador único (imm9 nos quatro `idx`, imm12 escalado, registrador), atômicos LSE + `LDAPR` e `LDRAA`/
/// `LDRAB`, nos lados de registrador geral (`V=0`) e SIMD&FP (`V=1`). Antes, `Aarch64Decoder#decodeLoadStoreSingle`
/// e vizinhos.
///
/// **Restrições que viraram linha e que a versão em cascata não conferia** (G8 medido pela E15.12 contra o
/// `a64.decode` do QEMU e o `objdump`): `LDPSW`/`STGP` não têm o modo "sem alocação"; `PRFUM` só existe com
/// `idx=00`; `PRFM` registrador exige `option<1>=1`; SIMD&FP não tem forma "unprivileged" (`idx=10`).
///
/// Origem das famílias: B6.1/B8.1 (GPR), B8.13 (SIMD&FP), B19.1 (LSE/`LDAPR`), B19.6 (`PRFM` literal), B19.14
/// (`STGP`), B19.15 (`LDRA`).
final class LoadStoreRegisterRows {
    private static final int SIZE_SHIFT = 30;
    private static final int SIZE_MASK = 0b11;
    private static final int OPC_SHIFT = 22;
    private static final int OPC_MASK = 0b11;
    private static final int OPC_STORE = 0b00;
    private static final int OPC_LOAD = 0b01;
    private static final int OPC_SIGNED_TO_X = 0b10;
    /// `opc<1>` do SIMD&FP único: `1` = forma `Q` (128 bits).
    private static final int FP_OPC_QUAD_BIT = 0b10;
    /// `opc<0>`: `1` = carga.
    private static final int OPC_LOAD_BIT = 0b01;
    private static final int RN_SHIFT = 5;
    private static final int RM_SHIFT = 16;
    private static final int RT2_SHIFT = 10;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int IMM9_SHIFT = 12;
    private static final int IMM9_BITS = 9;
    private static final int IMM12_SHIFT = 10;
    private static final int IMM12_MASK = 0xFFF;
    private static final int IMM7_SHIFT = 15;
    private static final int IMM7_BITS = 7;
    private static final int IMM19_SHIFT = 5;
    private static final int IMM19_BITS = 19;
    private static final int LITERAL_BYTES_PER_UNIT = 4;
    private static final int IDX_SHIFT = 10;
    private static final int IDX_MASK = 0b11;
    private static final int IDX_POST_INDEX = 0b01;
    private static final int IDX_PRE_INDEX = 0b11;
    private static final int OPTION_SHIFT = 13;
    private static final int OPTION_MASK = 0b111;
    private static final int SHIFT_FLAG_SHIFT = 12;
    private static final int PAIR_MODE_SHIFT = 23;
    private static final int PAIR_MODE_MASK = 0b11;
    private static final int PAIR_MODE_POST_INDEX = 0b01;
    private static final int PAIR_MODE_PRE_INDEX = 0b11;
    private static final int PAIR_LOAD_SHIFT = 22;
    private static final int PAIR_WIDE_SHIFT = 31;
    private static final int PAIR_WORD_SCALE_BYTES = 4;
    private static final int PAIR_DOUBLEWORD_SCALE_BYTES = 8;
    private static final int MEMORY_TAG_GRANULE_BYTES = 16;
    private static final int LITERAL_WIDE_SHIFT = 30;
    private static final int ATOMIC_ACQUIRE_SHIFT = 23;
    private static final int ATOMIC_RELEASE_SHIFT = 22;
    private static final int ATOMIC_OPC_SHIFT = 12;
    private static final int ATOMIC_OPC_MASK = 0b111;
    private static final int LDRA_S_SHIFT = 22;
    private static final int LDRA_WRITEBACK_SHIFT = 11;
    private static final int LDRA_SCALE_BYTES = 8;
    /// `rm`/`extendType`/`shiftAmount` das formas sem registrador de índice.
    private static final int NO_INDEX_REGISTER = -1;

    /// `size` → largura do acesso (registrador geral).
    private static final Ir64MemSize[] SIZES = Ir64MemSize.values();
    /// `size` → largura do acesso (SIMD&FP, `opc<1>=0`).
    private static final Ir64FpMemSize[] FP_SIZES = {
            Ir64FpMemSize.BYTE, Ir64FpMemSize.HALF, Ir64FpMemSize.SINGLE, Ir64FpMemSize.DOUBLE};
    /// `opc` do par/literal SIMD&FP → largura (`11` não tem linha).
    private static final Ir64FpMemSize[] FP_PAIR_SIZES = {
            Ir64FpMemSize.SINGLE, Ir64FpMemSize.DOUBLE, Ir64FpMemSize.QUAD};
    /// `idx` → modo de endereçamento das formas imm9 (`10` = "unprivileged", mesmo endereçamento de `00`).
    private static final Ir64AddressingMode[] IMM9_MODES = {
            Ir64AddressingMode.OFFSET, Ir64AddressingMode.POST_INDEX, Ir64AddressingMode.OFFSET,
            Ir64AddressingMode.PRE_INDEX};
    /// `option` → extensão do registrador de índice (`x0x` não tem linha).
    private static final Ir64ExtendType[] EXTENDS = {
            null, null, Ir64ExtendType.UXTW, Ir64ExtendType.LSL, null, null, Ir64ExtendType.SXTW,
            Ir64ExtendType.SXTX};
    /// `opc` atômico (`o3=0`) → operação.
    private static final Ir64AtomicOp[] ATOMIC_OPERATIONS = {
            Ir64AtomicOp.ADD, Ir64AtomicOp.CLR, Ir64AtomicOp.EOR, Ir64AtomicOp.SET, Ir64AtomicOp.SMAX,
            Ir64AtomicOp.SMIN, Ir64AtomicOp.UMAX, Ir64AtomicOp.UMIN};

    private static final Aarch64Feature LSE = Aarch64Feature.LSE;
    private static final Aarch64Feature MTE = Aarch64Feature.MEMORY_TAGGING;

    /// As linhas, bit 31 → 0 (os campos de cada família no comentário).
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // Literal — opc 011 V 00 imm19 Rt
            row("0. 011 0 00 ................... .....", LoadStoreRegisterRows::loadLiteral),
            row("10 011 0 00 ................... .....", LoadStoreRegisterRows::loadLiteral),
            row("11 011 0 00 ................... .....", LoadStoreRegisterRows::prefetch),
            row("0. 011 1 00 ................... .....", LoadStoreRegisterRows::fpLoadLiteral),
            row("10 011 1 00 ................... .....", LoadStoreRegisterRows::fpLoadLiteral),
            // Par — opc 101 V 0 mode L imm7 Rt2 Rn Rt (LDPSW/STGP não têm mode=00)
            row(".0 101 0 0 .. . ....... ..... ..... .....", pair(false)),
            row("01 101 0 0 01 1 ....... ..... ..... .....", pair(true)),
            row("01 101 0 0 1. 1 ....... ..... ..... .....", pair(true)),
            DecodeRow.of("01 101 0 0 01 0 ....... ..... ..... .....", MTE, LoadStoreRegisterRows::storePairTag),
            DecodeRow.of("01 101 0 0 1. 0 ....... ..... ..... .....", MTE, LoadStoreRegisterRows::storePairTag),
            row("0. 101 1 0 .. . ....... ..... ..... .....", LoadStoreRegisterRows::fpPair),
            row("10 101 1 0 .. . ....... ..... ..... .....", LoadStoreRegisterRows::fpPair),
            // Único, imm9 — size 111 V 0 0 opc 0 imm9 idx Rn Rt (PRFUM só idx=00)
            row(".. 111 0 0 0 00 0 ......... .. ..... .....", LoadStoreRegisterRows::singleImm9),
            row(".. 111 0 0 0 01 0 ......... .. ..... .....", LoadStoreRegisterRows::singleImm9),
            row("0. 111 0 0 0 10 0 ......... .. ..... .....", LoadStoreRegisterRows::singleImm9),
            row("10 111 0 0 0 10 0 ......... .. ..... .....", LoadStoreRegisterRows::singleImm9),
            row("0. 111 0 0 0 11 0 ......... .. ..... .....", LoadStoreRegisterRows::singleImm9),
            row("11 111 0 0 0 10 0 ......... 00 ..... .....", LoadStoreRegisterRows::prefetch),
            // Único, imm12 escalado — size 111 V 0 1 opc imm12 Rn Rt
            row(".. 111 0 0 1 00 ............ ..... .....", LoadStoreRegisterRows::singleImm12),
            row(".. 111 0 0 1 01 ............ ..... .....", LoadStoreRegisterRows::singleImm12),
            row("0. 111 0 0 1 10 ............ ..... .....", LoadStoreRegisterRows::singleImm12),
            row("10 111 0 0 1 10 ............ ..... .....", LoadStoreRegisterRows::singleImm12),
            row("0. 111 0 0 1 11 ............ ..... .....", LoadStoreRegisterRows::singleImm12),
            row("11 111 0 0 1 10 ............ ..... .....", LoadStoreRegisterRows::prefetch),
            // Único, registrador — size 111 V 0 0 opc 1 Rm option S 10 Rn Rt (option<1>=1)
            row(".. 111 0 0 0 00 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::singleRegister),
            row(".. 111 0 0 0 01 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::singleRegister),
            row("0. 111 0 0 0 10 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::singleRegister),
            row("10 111 0 0 0 10 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::singleRegister),
            row("0. 111 0 0 0 11 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::singleRegister),
            row("11 111 0 0 0 10 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::prefetch),
            // SIMD&FP único — imm9 (sem idx=10), imm12, registrador; opc<1>=1 é Q (size=00)
            row(".. 111 1 0 0 0. 0 ......... 0. ..... .....", LoadStoreRegisterRows::fpSingleImm9),
            row(".. 111 1 0 0 0. 0 ......... 11 ..... .....", LoadStoreRegisterRows::fpSingleImm9),
            row("00 111 1 0 0 1. 0 ......... 0. ..... .....", LoadStoreRegisterRows::fpSingleImm9),
            row("00 111 1 0 0 1. 0 ......... 11 ..... .....", LoadStoreRegisterRows::fpSingleImm9),
            row(".. 111 1 0 1 0. ............ ..... .....", LoadStoreRegisterRows::fpSingleImm12),
            row("00 111 1 0 1 1. ............ ..... .....", LoadStoreRegisterRows::fpSingleImm12),
            row(".. 111 1 0 0 0. 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::fpSingleRegister),
            row("00 111 1 0 0 1. 1 ..... .1. . 10 ..... .....", LoadStoreRegisterRows::fpSingleRegister),
            // Atômicos — size 111 0 00 A R 1 Rs o3 opc 00 Rn Rt
            DecodeRow.of(".. 111 0 00 . . 1 ..... 0 ... 00 ..... .....", LSE, LoadStoreRegisterRows::atomic),
            DecodeRow.of(".. 111 0 00 . . 1 ..... 1 000 00 ..... .....", LSE, LoadStoreRegisterRows::swap),
            DecodeRow.of(".. 111 0 00 1 0 1 11111 1 100 00 ..... .....", Aarch64Feature.LRCPC,
                    LoadStoreRegisterRows::loadAcquirePc),
            // LDRAA/LDRAB — 11 111 0 00 M S 1 imm9 W 1 Rn Rt
            DecodeRow.of("11 111 0 00 . . 1 ......... . 1 ..... .....", Aarch64Feature.POINTER_AUTHENTICATION,
                    LoadStoreRegisterRows::loadAuthenticated)
    );

    private LoadStoreRegisterRows() {
    }

    private static DecodeRow<Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static Ir64Op loadLiteral(int word, long address) {
        boolean signExtend = field(word, LITERAL_WIDE_SHIFT, SIZE_MASK) == OPC_SIGNED_TO_X;
        boolean wide = signExtend || bit(word, LITERAL_WIDE_SHIFT) != 0;
        return new MemoryOp64.LoadLiteral64(rt(word), address + literalOffset(word), wide, signExtend);
    }

    private static Ir64Op fpLoadLiteral(int word, long address) {
        return new FpOp64.LoadLiteral64(rt(word), address + literalOffset(word),
                FP_PAIR_SIZES[field(word, LITERAL_WIDE_SHIFT, SIZE_MASK)]);
    }

    /// `PRFM`/`PRFUM`: `Rt` é um `prfop`, não registrador; sem cache modelada, é NOP.
    private static Ir64Op prefetch(int word, long address) {
        return new SystemOp64.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT);
    }

    /// `LDP`/`STP`/`LDNP`/`STNP` (`opc` = `x0`) e `LDPSW` (`signed`).
    private static WordDecoder<Ir64Op> pair(boolean signed) {
        return (word, address) -> {
            boolean wide = !signed && bit(word, PAIR_WIDE_SHIFT) != 0;
            int scale = wide ? PAIR_DOUBLEWORD_SCALE_BYTES : PAIR_WORD_SCALE_BYTES;
            return new MemoryOp64.LoadStorePair(bit(word, PAIR_LOAD_SHIFT) != 0, rt(word), rt2(word), rn(word), wide,
                    pairMode(word), pairImmediate(word, scale), signed);
        };
    }

    private static Ir64Op storePairTag(int word, long address) {
        return new MemoryOp64.StorePairTag(rt(word), rt2(word), rn(word), pairMode(word),
                pairImmediate(word, MEMORY_TAG_GRANULE_BYTES));
    }

    private static Ir64Op fpPair(int word, long address) {
        Ir64FpMemSize size = FP_PAIR_SIZES[field(word, SIZE_SHIFT, SIZE_MASK)];
        return new FpOp64.LoadStorePair(bit(word, PAIR_LOAD_SHIFT) != 0, rt(word), rt2(word), rn(word), size,
                pairMode(word), pairImmediate(word, size.bytes()));
    }

    private static Ir64Op singleImm9(int word, long address) {
        return single(word, IMM9_MODES[field(word, IDX_SHIFT, IDX_MASK)], imm9(word), NO_INDEX_REGISTER, null, 0);
    }

    private static Ir64Op singleImm12(int word, long address) {
        long immediate = (long) field(word, IMM12_SHIFT, IMM12_MASK) * SIZES[size(word)].bytes();
        return single(word, Ir64AddressingMode.OFFSET, immediate, NO_INDEX_REGISTER, null, 0);
    }

    private static Ir64Op singleRegister(int word, long address) {
        int shiftAmount = bit(word, SHIFT_FLAG_SHIFT) != 0 ? SIZES[size(word)].log2Bytes() : 0;
        return single(word, Ir64AddressingMode.REGISTER_OFFSET, 0, rm(word), extend(word), shiftAmount);
    }

    /// `size`+`opc` das linhas GPR → `Load64`/`Store64` (`opc=10` estende para `X`, `11` para `W`).
    private static Ir64Op single(int word, Ir64AddressingMode mode, long immediate, int rm, Ir64ExtendType extend,
            int shiftAmount) {
        Ir64MemSize size = SIZES[size(word)];
        int opc = field(word, OPC_SHIFT, OPC_MASK);
        boolean naturalWide = size == Ir64MemSize.DOUBLEWORD;
        if (opc == OPC_STORE) {
            return new MemoryOp64.Store64(rt(word), rn(word), size, naturalWide, mode, immediate, rm, extend,
                    shiftAmount);
        }
        boolean signExtend = opc != OPC_LOAD;
        boolean wide = opc == OPC_LOAD ? naturalWide : opc == OPC_SIGNED_TO_X;
        return new MemoryOp64.Load64(rt(word), rn(word), size, signExtend, wide, mode, immediate, rm, extend,
                shiftAmount);
    }

    private static Ir64Op fpSingleImm9(int word, long address) {
        return fpSingle(word, IMM9_MODES[field(word, IDX_SHIFT, IDX_MASK)], imm9(word), NO_INDEX_REGISTER, null, 0);
    }

    private static Ir64Op fpSingleImm12(int word, long address) {
        long immediate = (long) field(word, IMM12_SHIFT, IMM12_MASK) * fpSize(word).bytes();
        return fpSingle(word, Ir64AddressingMode.OFFSET, immediate, NO_INDEX_REGISTER, null, 0);
    }

    private static Ir64Op fpSingleRegister(int word, long address) {
        int shiftAmount = bit(word, SHIFT_FLAG_SHIFT) != 0 ? fpSize(word).sizeLog2() : 0;
        return fpSingle(word, Ir64AddressingMode.REGISTER_OFFSET, 0, rm(word), extend(word), shiftAmount);
    }

    private static Ir64Op fpSingle(int word, Ir64AddressingMode mode, long immediate, int rm, Ir64ExtendType extend,
            int shiftAmount) {
        Ir64FpMemSize size = fpSize(word);
        if ((field(word, OPC_SHIFT, OPC_MASK) & OPC_LOAD_BIT) == 0) {
            return new FpOp64.Store64(rt(word), rn(word), size, mode, immediate, rm, extend, shiftAmount);
        }
        return new FpOp64.Load64(rt(word), rn(word), size, mode, immediate, rm, extend, shiftAmount);
    }

    private static Ir64FpMemSize fpSize(int word) {
        return (field(word, OPC_SHIFT, OPC_MASK) & FP_OPC_QUAD_BIT) != 0 ? Ir64FpMemSize.QUAD : FP_SIZES[size(word)];
    }

    private static Ir64Op atomic(int word, long address) {
        return atomic(word, ATOMIC_OPERATIONS[field(word, ATOMIC_OPC_SHIFT, ATOMIC_OPC_MASK)]);
    }

    private static Ir64Op swap(int word, long address) {
        return atomic(word, Ir64AtomicOp.SWP);
    }

    private static Ir64Op atomic(int word, Ir64AtomicOp operation) {
        return new MemoryOp64.AtomicMemoryOp(rm(word), rt(word), rn(word), SIZES[size(word)], operation,
                bit(word, ATOMIC_ACQUIRE_SHIFT) != 0, bit(word, ATOMIC_RELEASE_SHIFT) != 0);
    }

    /// `LDAPR` registrador: mesmo `Load64` do `LDAR` (ordenação é NOP neste interpretador single-thread).
    private static Ir64Op loadAcquirePc(int word, long address) {
        Ir64MemSize size = SIZES[size(word)];
        return new MemoryOp64.Load64(rt(word), rn(word), size, false, size == Ir64MemSize.DOUBLEWORD,
                Ir64AddressingMode.OFFSET, 0L, NO_INDEX_REGISTER, null, 0);
    }

    /// `LDRAA`/`LDRAB`: sem autenticação modelada, `Xn` é o endereço-base; `S:imm9` com sinal × 8, `W` = pré-índice.
    private static Ir64Op loadAuthenticated(int word, long address) {
        int combined = bit(word, LDRA_S_SHIFT) << IMM9_BITS | field(word, IMM9_SHIFT, (1 << IMM9_BITS) - 1);
        long immediate = signExtend(combined, IMM9_BITS + 1) * LDRA_SCALE_BYTES;
        Ir64AddressingMode mode = bit(word, LDRA_WRITEBACK_SHIFT) != 0
                ? Ir64AddressingMode.PRE_INDEX : Ir64AddressingMode.OFFSET;
        return new MemoryOp64.Load64(rt(word), rn(word), Ir64MemSize.DOUBLEWORD, false, true, mode, immediate,
                NO_INDEX_REGISTER, null, 0);
    }

    private static Ir64AddressingMode pairMode(int word) {
        return switch (field(word, PAIR_MODE_SHIFT, PAIR_MODE_MASK)) {
            case PAIR_MODE_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case PAIR_MODE_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> Ir64AddressingMode.OFFSET;
        };
    }

    private static long pairImmediate(int word, int scale) {
        return signExtend(field(word, IMM7_SHIFT, (1 << IMM7_BITS) - 1), IMM7_BITS) * scale;
    }

    private static long literalOffset(int word) {
        return signExtend(field(word, IMM19_SHIFT, (1 << IMM19_BITS) - 1), IMM19_BITS) * LITERAL_BYTES_PER_UNIT;
    }

    private static long imm9(int word) {
        return signExtend(field(word, IMM9_SHIFT, (1 << IMM9_BITS) - 1), IMM9_BITS);
    }

    private static Ir64ExtendType extend(int word) {
        return EXTENDS[field(word, OPTION_SHIFT, OPTION_MASK)];
    }

    private static long signExtend(int value, int bits) {
        int unused = Integer.SIZE - bits;
        return (value << unused) >> unused;
    }

    private static int size(int word) {
        return field(word, SIZE_SHIFT, SIZE_MASK);
    }

    private static int rt(int word) {
        return word & REGISTER_MASK;
    }

    private static int rt2(int word) {
        return field(word, RT2_SHIFT, REGISTER_MASK);
    }

    private static int rn(int word) {
        return field(word, RN_SHIFT, REGISTER_MASK);
    }

    private static int rm(int word) {
        return field(word, RM_SHIFT, REGISTER_MASK);
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }
}
