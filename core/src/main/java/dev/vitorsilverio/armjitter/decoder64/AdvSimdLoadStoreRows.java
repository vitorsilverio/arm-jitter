package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.ArrayList;
import java.util.List;

/// E15.12 (D5 do épico E15): AdvSIMD load/store de estruturas — `LD1`–`LD4`/`ST1`–`ST4` múltiplo e único e
/// `LD1R`–`LD4R` (`0 Q 00110x`). Antes, `Aarch64Decoder#decodeAdvancedSimdLoadStoreMultiple`/`…Single`.
///
/// Colunas: sem pós-índice `Rm` é `00000`; `.1D` (`size=11`, `Q=0`) só existe com `selem=1`; o múltiplo exige
/// `bit21=0` — esta última a versão em cascata não conferia (G8 medido pela E15.12).
///
/// Origem: B8.6.
final class AdvSimdLoadStoreRows {
    private static final int Q_SHIFT = 30;
    private static final int POST_INDEX_SHIFT = 23;
    private static final int LOAD_SHIFT = 22;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `Rm=11111` no pós-índice: avança pelo tamanho transferido (imediato), não por um registrador.
    private static final int RM_IMMEDIATE = 0b1_1111;
    /// `rm` do record quando o pós-índice é imediato ou não há pós-índice.
    private static final int NO_REGISTER = -1;
    private static final int MULTIPLE_SIZE_SHIFT = 10;
    private static final int SIZE_MASK = 0b11;
    private static final int SELEM_HIGH_SHIFT = 13;
    private static final int SELEM_LOW_SHIFT = 21;
    private static final int INDEX_BYTE_SHIFT = 10;
    private static final int INDEX_BYTE_MASK = 0b111;
    private static final int INDEX_HALF_SHIFT = 11;
    private static final int INDEX_HALF_MASK = 0b11;
    private static final int INDEX_WORD_SHIFT = 12;
    private static final int REPLICATE_SCALE_SHIFT = 10;
    private static final int BYTE_LOG2 = 0;
    private static final int HALF_LOG2 = 1;
    private static final int WORD_LOG2 = 2;
    private static final int DOUBLEWORD_LOG2 = 3;

    /// `opcode` (`bits[15:12]`) do múltiplo → `{rpt, selem}` (`@ldst_mult`), na ordem do `a64.decode`.
    private static final String[] MULTIPLE_OPCODES = {"0000", "0010", "0100", "0110", "0111", "1000", "1010"};
    private static final int[][] MULTIPLE_SHAPES = {{1, 4}, {4, 1}, {1, 3}, {3, 1}, {1, 1}, {1, 2}, {2, 1}};

    /// As linhas: múltiplo (`0 Q 001100 p L 0 Rm opcode size Rn Rt`) e único (`0 Q 001101 p L R Rm opc S size Rn Rt`).
    static final List<DecodeRow<Ir64Op>> ROWS = rows();

    private AdvSimdLoadStoreRows() {
    }

    private static List<DecodeRow<Ir64Op>> rows() {
        List<DecodeRow<Ir64Op>> rows = new ArrayList<>();
        for (int i = 0; i < MULTIPLE_OPCODES.length; i++) {
            WordDecoder<Ir64Op> build = multiple(MULTIPLE_SHAPES[i][0], MULTIPLE_SHAPES[i][1]);
            // selem>1: `.1D` (Q=0, size=11) não existe — Q=1 com qualquer size, Q=0 com size 0x/10.
            String[] qSize = MULTIPLE_SHAPES[i][1] == 1
                    ? new String[] {". .."}
                    : new String[] {"1 ..", "0 0.", "0 10"};
            for (String shape : qSize) {
                String q = shape.substring(0, 1);
                String size = shape.substring(2);
                rows.add(row("0 " + q + " 001100 0 . 0 00000 " + MULTIPLE_OPCODES[i] + " " + size + " ..... .....", build));
                rows.add(row("0 " + q + " 001100 1 . 0 ..... " + MULTIPLE_OPCODES[i] + " " + size + " ..... .....", build));
            }
        }
        // Único — opc(15:14) = tamanho; os bits baixos são índice ou reservado conforme o tamanho.
        String[] singles = {"00 . . ..", "01 . . .0", "10 . . 00", "10 . 0 01"};
        WordDecoder<Ir64Op>[] singleBuilds = singleBuilds();
        for (int i = 0; i < singles.length; i++) {
            rows.add(row("0 . 001101 0 . . 00000 " + singles[i] + " ..... .....", singleBuilds[i]));
            rows.add(row("0 . 001101 1 . . ..... " + singles[i] + " ..... .....", singleBuilds[i]));
        }
        rows.add(row("0 . 001101 0 1 . 00000 11 . 0 .. ..... .....", AdvSimdLoadStoreRows::replicate));
        rows.add(row("0 . 001101 1 1 . ..... 11 . 0 .. ..... .....", AdvSimdLoadStoreRows::replicate));
        return List.copyOf(rows);
    }

    @SuppressWarnings("unchecked")
    private static WordDecoder<Ir64Op>[] singleBuilds() {
        return new WordDecoder[] {
                single(BYTE_LOG2), single(HALF_LOG2), single(WORD_LOG2), single(DOUBLEWORD_LOG2)};
    }

    private static DecodeRow<Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static WordDecoder<Ir64Op> multiple(int rpt, int selem) {
        return (word, address) -> new AdvSimdMoveOp64.LoadStoreMultiple(bit(word, LOAD_SHIFT) != 0, rt(word),
                rn(word), rm(word), bit(word, Q_SHIFT) != 0, bit(word, POST_INDEX_SHIFT) != 0,
                (word >>> MULTIPLE_SIZE_SHIFT) & SIZE_MASK, rpt, selem);
    }

    /// Índice da lane: `Q` é o bit alto e o resto vem de bits baixos que dependem do tamanho (no doubleword o
    /// próprio `Q` é o índice — só há duas lanes).
    private static WordDecoder<Ir64Op> single(int elementSizeLog2) {
        return (word, address) -> {
            int q = bit(word, Q_SHIFT);
            int index = switch (elementSizeLog2) {
                case BYTE_LOG2 -> q << 3 | (word >>> INDEX_BYTE_SHIFT) & INDEX_BYTE_MASK;
                case HALF_LOG2 -> q << 2 | (word >>> INDEX_HALF_SHIFT) & INDEX_HALF_MASK;
                case WORD_LOG2 -> q << 1 | bit(word, INDEX_WORD_SHIFT);
                default -> q;
            };
            return new AdvSimdMoveOp64.LoadStoreSingle(bit(word, LOAD_SHIFT) != 0, rt(word), rn(word), rm(word),
                    bit(word, POST_INDEX_SHIFT) != 0, elementSizeLog2, selem(word), index);
        };
    }

    private static Ir64Op replicate(int word, long address) {
        return new AdvSimdMoveOp64.LoadSingleReplicate(rt(word), rn(word), rm(word), bit(word, Q_SHIFT) != 0,
                bit(word, POST_INDEX_SHIFT) != 0, (word >>> REPLICATE_SCALE_SHIFT) & SIZE_MASK, selem(word));
    }

    /// `selem = (bit13:bit21) + 1`.
    private static int selem(int word) {
        return (bit(word, SELEM_HIGH_SHIFT) << 1 | bit(word, SELEM_LOW_SHIFT)) + 1;
    }

    /// `Rm` do pós-índice por registrador, ou {@link #NO_REGISTER} (imediato ou sem pós-índice — nesse caso a
    /// linha já fixou `Rm=00000`).
    private static int rm(int word) {
        int rm = (word >>> RM_SHIFT) & REGISTER_MASK;
        return bit(word, POST_INDEX_SHIFT) != 0 && rm != RM_IMMEDIATE ? rm : NO_REGISTER;
    }

    private static int rt(int word) {
        return word & REGISTER_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }
}
