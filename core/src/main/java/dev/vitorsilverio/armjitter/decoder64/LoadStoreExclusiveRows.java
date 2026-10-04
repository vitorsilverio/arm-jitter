package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;

import java.util.List;

/// E15.12 (D5 do épico E15): load/store exclusivo, ordenado e de comparar-e-trocar (`sz 001000`), atômicos de
/// 128 bits (`FEAT_LSE128`) e `LDAPR`/`STLR` com imediato (`FEAT_LRCPC2`). Antes, `Aarch64Decoder#decodeExclusive`,
/// `#decodeAtomic128` e `#decodeLoadAcquireOrStoreReleaseUnscaledImmediate`.
///
/// **Restrições que viraram linha e que a versão em cascata não conferia** (G8 medido pela E15.12 contra o
/// `a64.decode` do QEMU e o `objdump`): `bit24=0` no espaço exclusivo (com `bit24=1` moram outras extensões, ex.
/// `FEAT_LSUI`); `Rs`/`Rt2`=`11111` em `STLR`/`LDAR` e `Rt2`=`11111` em `CAS`/`CASP`; `size=00` e `bit26=0` nos
/// atômicos de 128 bits; `bit26=0` em `LDAPR`/`STLR` imediato.
///
/// Origem das famílias: B6.3.4 (`LDXR`/`STXR`), B8.1 (`LDAR`/`STLR`/pares/`CAS`), B11.11 (gate LSE), B19.19
/// (`LDAPR_i`), B19.25 (LSE128).
final class LoadStoreExclusiveRows {
    private static final int SIZE_SHIFT = 30;
    private static final int SIZE_MASK = 0b11;
    private static final int OPC_SHIFT = 22;
    private static final int OPC_MASK = 0b11;
    private static final int OPC_STORE = 0b00;
    private static final int OPC_LOAD = 0b01;
    private static final int OPC_SIGNED_TO_X = 0b10;
    private static final int RS_SHIFT = 16;
    private static final int RT2_SHIFT = 10;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int ACQUIRE_RELEASE_SHIFT = 15;
    private static final int PAIR_WIDE_SHIFT = 30;
    private static final int ATOMIC128_ACQUIRE_SHIFT = 23;
    private static final int ATOMIC128_RELEASE_SHIFT = 22;
    /// `XZR`: o par de 128 bits precisa de dois registradores reais.
    private static final int ZERO_REGISTER = 0b1_1111;
    private static final int IMM9_SHIFT = 12;
    private static final int IMM9_BITS = 9;
    /// `rm` das formas sem registrador de índice.
    private static final int NO_INDEX_REGISTER = -1;

    private static final Ir64MemSize[] SIZES = Ir64MemSize.values();

    private static final Aarch64Feature LSE = Aarch64Feature.LSE;
    private static final Aarch64Feature LSE128 = Aarch64Feature.LSE128;
    private static final Aarch64Feature LRCPC2 = Aarch64Feature.LRCPC2;

    /// As linhas, bit 31 → 0 (os campos de cada família no comentário).
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // Exclusivo/ordenado/CAS — sz 001000 o2 L o1 Rs o0 Rt2 Rn Rt
            row(".. 001000 0 0 0 ..... . ..... ..... .....", LoadStoreExclusiveRows::storeExclusive),
            row(".. 001000 0 1 0 ..... . ..... ..... .....", LoadStoreExclusiveRows::loadExclusive),
            row(".. 001000 1 0 0 11111 . 11111 ..... .....", ordered(true)),
            row(".. 001000 1 1 0 11111 . 11111 ..... .....", ordered(false)),
            row("1. 001000 0 0 1 ..... . ..... ..... .....", LoadStoreExclusiveRows::storeExclusivePair),
            row("1. 001000 0 1 1 ..... . ..... ..... .....", LoadStoreExclusiveRows::loadExclusivePair),
            // CASP: Rs e Rt pares (bit16 e bit0 = 0)
            DecodeRow.of("0. 001000 0 . 1 ....0 . 11111 ..... ....0", LSE, LoadStoreExclusiveRows::compareAndSwapPair),
            DecodeRow.of(".. 001000 1 . 1 ..... . 11111 ..... .....", LSE, LoadStoreExclusiveRows::compareAndSwap),
            // Atômicos de 128 bits — 00011001 A R 1 Rt2 opc Rn Rt
            DecodeRow.of("00 011001 . . 1 ..... 000100 ..... .....", LSE128, atomicPair(Ir64AtomicOp.CLR)),
            DecodeRow.of("00 011001 . . 1 ..... 001100 ..... .....", LSE128, atomicPair(Ir64AtomicOp.SET)),
            DecodeRow.of("00 011001 . . 1 ..... 100000 ..... .....", LSE128, atomicPair(Ir64AtomicOp.SWP)),
            // LDAPR/STLR imediato — sz 011001 opc 0 imm9 00 Rn Rt
            DecodeRow.of(".. 011001 00 0 ......... 00 ..... .....", LRCPC2, LoadStoreExclusiveRows::orderedImmediate),
            DecodeRow.of(".. 011001 01 0 ......... 00 ..... .....", LRCPC2, LoadStoreExclusiveRows::orderedImmediate),
            DecodeRow.of("0. 011001 10 0 ......... 00 ..... .....", LRCPC2, LoadStoreExclusiveRows::orderedImmediate),
            DecodeRow.of("10 011001 10 0 ......... 00 ..... .....", LRCPC2, LoadStoreExclusiveRows::orderedImmediate),
            DecodeRow.of("0. 011001 11 0 ......... 00 ..... .....", LRCPC2, LoadStoreExclusiveRows::orderedImmediate)
    );

    private LoadStoreExclusiveRows() {
    }

    private static DecodeRow<Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static Ir64Op storeExclusive(int word, long address) {
        return new MemoryOp64.StoreExclusive(rs(word), rt(word), rn(word), SIZES[size(word)], acquireRelease(word));
    }

    private static Ir64Op loadExclusive(int word, long address) {
        return new MemoryOp64.LoadExclusive(rt(word), rn(word), SIZES[size(word)], acquireRelease(word));
    }

    /// `STLR`/`STLLR` (`store`) e `LDAR`/`LDLAR`: `[Rn]` sem deslocamento; a ordenação é NOP neste interpretador
    /// single-thread, então reaproveitam `Load64`/`Store64`.
    private static WordDecoder<Ir64Op> ordered(boolean store) {
        return (word, address) -> {
            Ir64MemSize size = SIZES[size(word)];
            boolean wide = size == Ir64MemSize.DOUBLEWORD;
            return store
                    ? new MemoryOp64.Store64(rt(word), rn(word), size, wide, Ir64AddressingMode.OFFSET, 0L,
                            NO_INDEX_REGISTER, null, 0)
                    : new MemoryOp64.Load64(rt(word), rn(word), size, false, wide, Ir64AddressingMode.OFFSET, 0L,
                            NO_INDEX_REGISTER, null, 0);
        };
    }

    private static Ir64Op storeExclusivePair(int word, long address) {
        return new MemoryOp64.StoreExclusivePair(rs(word), rt(word), rt2(word), rn(word), pairWide(word),
                acquireRelease(word));
    }

    private static Ir64Op loadExclusivePair(int word, long address) {
        return new MemoryOp64.LoadExclusivePair(rt(word), rt2(word), rn(word), pairWide(word), acquireRelease(word));
    }

    private static Ir64Op compareAndSwapPair(int word, long address) {
        return new MemoryOp64.CompareAndSwapPair(rs(word), rt(word), rn(word), pairWide(word));
    }

    private static Ir64Op compareAndSwap(int word, long address) {
        return new MemoryOp64.CompareAndSwap(rs(word), rt(word), rn(word), SIZES[size(word)]);
    }

    /// `LDCLRP`/`LDSETP`/`SWPP`: `Rt2` está em `bits[20:16]`; `Rt`/`Rt2` = `XZR` ou iguais é UNDEFINED
    /// (restrição de valor, não cabe na máscara).
    private static WordDecoder<Ir64Op> atomicPair(Ir64AtomicOp operation) {
        return (word, address) -> {
            int rt = rt(word);
            int rt2 = rs(word);
            if (rt == ZERO_REGISTER || rt2 == ZERO_REGISTER || rt == rt2) {
                throw new UnsupportedOperationException(String.format(
                        "AArch64: par de 128 bits inválido 0x%08x em 0x%x", word, address));
            }
            return new MemoryOp64.AtomicMemoryOpPair(rt, rt2, rn(word), operation,
                    bit(word, ATOMIC128_ACQUIRE_SHIFT) != 0, bit(word, ATOMIC128_RELEASE_SHIFT) != 0);
        };
    }

    /// `STLR`/`LDAPR` com imediato de 9 bits com sinal: mesmo `size`×`opc` do `LDUR`/`STUR`.
    private static Ir64Op orderedImmediate(int word, long address) {
        Ir64MemSize size = SIZES[size(word)];
        int opc = (word >>> OPC_SHIFT) & OPC_MASK;
        int unused = Integer.SIZE - IMM9_BITS;
        long immediate = (word << (unused - IMM9_SHIFT)) >> unused;
        boolean naturalWide = size == Ir64MemSize.DOUBLEWORD;
        if (opc == OPC_STORE) {
            return new MemoryOp64.Store64(rt(word), rn(word), size, naturalWide, Ir64AddressingMode.OFFSET,
                    immediate, NO_INDEX_REGISTER, null, 0);
        }
        boolean wide = opc == OPC_LOAD ? naturalWide : opc == OPC_SIGNED_TO_X;
        return new MemoryOp64.Load64(rt(word), rn(word), size, opc != OPC_LOAD, wide, Ir64AddressingMode.OFFSET,
                immediate, NO_INDEX_REGISTER, null, 0);
    }

    private static boolean pairWide(int word) {
        return bit(word, PAIR_WIDE_SHIFT) != 0;
    }

    private static boolean acquireRelease(int word) {
        return bit(word, ACQUIRE_RELEASE_SHIFT) != 0;
    }

    private static int size(int word) {
        return (word >>> SIZE_SHIFT) & SIZE_MASK;
    }

    private static int rt(int word) {
        return word & REGISTER_MASK;
    }

    private static int rt2(int word) {
        return (word >>> RT2_SHIFT) & REGISTER_MASK;
    }

    private static int rs(int word) {
        return (word >>> RS_SHIFT) & REGISTER_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }
}
