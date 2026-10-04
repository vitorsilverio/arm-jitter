package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64.Ir64MemoryTagMultipleOperation;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64.Ir64MemoryTagOperation;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64.Ir64MopsPhase;

import java.util.List;

/// E15.12 (D5 do épico E15): as extensões de memória do espaço `bits[29:24]=011x01` da classe Loads and Stores —
/// `CPYF*`/`CPY*`/`SET*`/`SETG*` (`FEAT_MOPS`), tags MTE (`STG`/`STZG`/`ST2G`/`STZ2G`/`LDG`/`STGM`/`STZGM`/`LDGM`) e
/// `GCSSTR`/`GCSSTTR` (`FEAT_GCS`). Antes, `Aarch64Decoder#decodeMemoryCopyAndSet`, `#decodeMemoryTagFamily` e
/// `#decodeGcsstr`.
///
/// **Restrições que viraram linha e que a versão em cascata não conferia** (G8 medido pela E15.12 contra o
/// `a64.decode` do QEMU e o `objdump`): MOPS só com `size=00`; tags MTE só com `bit26=0`; `STGM`/`STZGM`/`LDGM`
/// com `imm9=0`. `SETG*` exige MOPS **e** MTE (coluna `alsoRequires`).
///
/// Origem das famílias: B19.14 (MTE e `SETG*`), B19.16 (MOPS), B19.27 (`GCSSTR`).
final class MemoryOperationRows {
    private static final int RD_MASK = 0b1_1111;
    private static final int RN_SHIFT = 5;
    private static final int RS_SHIFT = 16;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int SET_PHASE_SHIFT = 14;
    private static final int COPY_PHASE_SHIFT = 22;
    private static final int PHASE_MASK = 0b11;
    /// `bit26` de `CPY*`: `1` = direção pela sobreposição, `0` (`CPYF*`) = sempre para a frente.
    private static final int COPY_DIRECTION_SHIFT = 26;
    private static final int TAG_SIZE_SHIFT = 22;
    private static final int TAG_SIZE_MASK = 0b11;
    /// `size<0>` de `STZG`/`STZ2G`: zera os dados do granule.
    private static final int TAG_ZERO_DATA_BIT = 0b01;
    /// `size<1>` de `ST2G`/`STZ2G`: dois granules.
    private static final int TAG_TWO_GRANULES_BIT = 0b10;
    private static final int TAG_VARIANT_SHIFT = 10;
    private static final int TAG_VARIANT_MASK = 0b11;
    private static final int TAG_VARIANT_POST_INDEX = 0b01;
    private static final int TAG_VARIANT_OFFSET = 0b10;
    private static final int TAG_IMM9_SHIFT = 12;
    private static final int TAG_IMM9_BITS = 9;
    private static final int MEMORY_TAG_GRANULE_BYTES = 16;
    /// `rm` das formas sem registrador de índice.
    private static final int NO_INDEX_REGISTER = -1;

    /// Fase de `SET*`/`CPY*` (`11` não tem linha).
    private static final Ir64MopsPhase[] PHASES = {Ir64MopsPhase.PROLOGUE, Ir64MopsPhase.MAIN, Ir64MopsPhase.EPILOGUE};

    private static final Aarch64Feature MOPS = Aarch64Feature.MEMORY_COPY_SET;
    private static final Aarch64Feature MTE = Aarch64Feature.MEMORY_TAGGING;

    /// As linhas, bit 31 → 0 (os campos de cada família no comentário).
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // SET*/SETG* — 00 011 G 01110 Rs phase nontemp unpriv 01 Rn Rd
            DecodeRow.of("00 011 0 01110 ..... 0. .. 01 ..... .....", MOPS, MemoryOperationRows::memorySet),
            DecodeRow.of("00 011 0 01110 ..... 10 .. 01 ..... .....", MOPS, MemoryOperationRows::memorySet),
            DecodeRow.of("00 011 1 01110 ..... 0. .. 01 ..... .....", MOPS, MTE, MemoryOperationRows::memorySetTagged),
            DecodeRow.of("00 011 1 01110 ..... 10 .. 01 ..... .....", MOPS, MTE, MemoryOperationRows::memorySetTagged),
            // CPYF*/CPY* — 00 011 D 01 phase 0 Rs options 01 Rn Rd
            DecodeRow.of("00 011 . 01 0. 0 ..... .... 01 ..... .....", MOPS, MemoryOperationRows::memoryCopy),
            DecodeRow.of("00 011 . 01 10 0 ..... .... 01 ..... .....", MOPS, MemoryOperationRows::memoryCopy),
            // Tags MTE — 11011001 size 1 imm9 variant Rn Rt (variant=00: forma múltipla com imm9=0, ou LDG)
            DecodeRow.of("11011001 00 1 000000000 00 ..... .....", MTE,
                    multiple(Ir64MemoryTagMultipleOperation.STORE_ZERO_DATA_TAGS)),
            DecodeRow.of("11011001 01 1 ......... 00 ..... .....", MTE, MemoryOperationRows::loadTag),
            DecodeRow.of("11011001 10 1 000000000 00 ..... .....", MTE, multiple(Ir64MemoryTagMultipleOperation.STORE_TAGS)),
            DecodeRow.of("11011001 11 1 000000000 00 ..... .....", MTE, multiple(Ir64MemoryTagMultipleOperation.LOAD_TAGS)),
            DecodeRow.of("11011001 .. 1 ......... 01 ..... .....", MTE, MemoryOperationRows::storeTag),
            DecodeRow.of("11011001 .. 1 ......... 1. ..... .....", MTE, MemoryOperationRows::storeTag),
            // GCSSTR/GCSSTTR — 11011001 000 11111 000 unpriv 11 Rn Rt
            DecodeRow.of("11011001 000 11111 000 . 11 ..... .....", Aarch64Feature.GUARDED_CONTROL_STACK,
                    MemoryOperationRows::guardedControlStackStore)
    );

    private MemoryOperationRows() {
    }

    /// `nontemp`/`unpriv` não são modelados (sem cache nem MMU de permissão para eles afetarem).
    private static Ir64Op memorySet(int word, long address) {
        return new MemoryOp64.MemorySet(PHASES[field(word, SET_PHASE_SHIFT, PHASE_MASK)], rd(word), rn(word), rs(word));
    }

    private static Ir64Op memorySetTagged(int word, long address) {
        return new MemoryOp64.MemorySetTagged(PHASES[field(word, SET_PHASE_SHIFT, PHASE_MASK)], rd(word), rn(word),
                rs(word));
    }

    /// `options` (não-temporal/unprivileged de origem e destino) não são modelados.
    private static Ir64Op memoryCopy(int word, long address) {
        boolean forwardOnly = field(word, COPY_DIRECTION_SHIFT, 1) == 0;
        return new MemoryOp64.MemoryCopy(PHASES[field(word, COPY_PHASE_SHIFT, PHASE_MASK)], forwardOnly, rd(word),
                rs(word), rn(word));
    }

    private static WordDecoder<Ir64Op> multiple(Ir64MemoryTagMultipleOperation operation) {
        return (word, address) -> new MemoryOp64.MemoryTagMultiple(operation, rd(word), rn(word));
    }

    private static Ir64Op loadTag(int word, long address) {
        return new MemoryOp64.MemoryTag(Ir64MemoryTagOperation.LOAD, false, 1, rd(word), rn(word),
                Ir64AddressingMode.OFFSET, tagImmediate(word));
    }

    /// `STG`/`STZG`/`ST2G`/`STZ2G`: `variant=01` é PÓS-índice (o imediato só vai para `Rn`), `10` offset, `11`
    /// pré-índice — invertido em relação ao `p`/`w` do `LDR`, conferido no `do_STG` do QEMU (B19.14).
    private static Ir64Op storeTag(int word, long address) {
        Ir64AddressingMode mode = switch (field(word, TAG_VARIANT_SHIFT, TAG_VARIANT_MASK)) {
            case TAG_VARIANT_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case TAG_VARIANT_OFFSET -> Ir64AddressingMode.OFFSET;
            default -> Ir64AddressingMode.PRE_INDEX;
        };
        int size = field(word, TAG_SIZE_SHIFT, TAG_SIZE_MASK);
        int granules = (size & TAG_TWO_GRANULES_BIT) != 0 ? 2 : 1;
        return new MemoryOp64.MemoryTag(Ir64MemoryTagOperation.STORE, (size & TAG_ZERO_DATA_BIT) != 0, granules,
                rd(word), rn(word), mode, tagImmediate(word));
    }

    /// `GCSSTR`/`GCSSTTR`: só o armazenamento de `Xt` em `[Xn]`; o resto da Guarded Control Stack não é modelado
    /// (B19.27). `unpriv` é NOP observável neste espaço de endereço único.
    private static Ir64Op guardedControlStackStore(int word, long address) {
        return new MemoryOp64.Store64(rd(word), rn(word), Ir64MemSize.DOUBLEWORD, true, Ir64AddressingMode.OFFSET,
                0L, NO_INDEX_REGISTER, null, 0);
    }

    private static long tagImmediate(int word) {
        int unused = Integer.SIZE - TAG_IMM9_BITS;
        long imm9 = (word << (unused - TAG_IMM9_SHIFT)) >> unused;
        return imm9 * MEMORY_TAG_GRANULE_BYTES;
    }

    private static int rd(int word) {
        return word & RD_MASK;
    }

    private static int rn(int word) {
        return field(word, RN_SHIFT, REGISTER_MASK);
    }

    private static int rs(int word) {
        return field(word, RS_SHIFT, REGISTER_MASK);
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
