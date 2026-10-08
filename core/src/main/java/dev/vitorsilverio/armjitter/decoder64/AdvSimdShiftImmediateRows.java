package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15f: o AdvSIMD "shift by immediate" (vetorial, prefixo `01111`; escalar, `11111` com `bit30=1`) e o
/// "modified immediate" (`MOVI`/`MVNI`/`ORR`/`BIC`/`FMOV`, o `immh=0000` do vetorial). Antes eram
/// `decodeAdvancedSimdShiftByImmediate` e `decodeAdvSimdModifiedImmediate` no `Aarch64Decoder` (B8.8, B19.3,
/// B19.4, B19.5.3, B19.6, E15.9c).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) 0 immh(22:19) immb(18:16) opcode(15:11) 1 Rn Rd`.
///
/// O tamanho do elemento vem do bit mais alto de `immh` (`0001` = `B`, `001x` = `H`, `01xx` = `S`, `1xxx` = `D`);
/// o deslocamento sai de `immh:immb` (`esize = 8 << esz`): à direita `2*esize - immh:immb`, à esquerda
/// `immh:immb - esize` (ARM DDI 0487, "shift amount"). O `D` é escrito em duas linhas (`10xx` e `11xx`), e o
/// `op` do modified immediate em duas (`0` e `1`), para `bit21` e `bit29` continuarem fixos em toda linha: a
/// chave de balde da `advSimdTable` é a interseção das máscaras de todas as linhas (E15.15e).
///
/// Tamanhos (conferidos no `a64.decode` do QEMU e no `objdump`):
///
/// - vetorial: `B`/`H`/`S` com qualquer `Q`, `D` só com `Q=1` (não existe `.1d`);
/// - escalar: só `D`, menos `SQSHL`/`UQSHL`/`SQSHLU` (todos os tamanhos);
/// - estreitando (`SHRN`…`UQRSHRN`) e `SSHLL`/`USHLL`: o elemento estreito é `B`/`H`/`S`; `SHRN`/`RSHRN`/`SSHLL`/
///   `USHLL` não têm forma escalar;
/// - conversão FP↔ponto fixo (`SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` com `#fbits`): `H` (`FEAT_FP16`), `S`, `D`.
final class AdvSimdShiftImmediateRows {
    private static final int Q_SHIFT = 30;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `immh:immb` (`bits[22:16]`).
    private static final int SHIFT_FIELD_SHIFT = 16;
    private static final int SHIFT_FIELD_MASK = 0b111_1111;
    /// Largura de `immb` (`bits[18:16]`), abaixo de `immh` no campo `immh:immb`.
    private static final int IMMB_BITS = 3;
    private static final String VECTOR_PREFIX = "01111";
    /// Escalar: prefixo `11111` com `bit30=1` (o `Q` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11111";
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int BITS_PER_BYTE = 8;

    // ── modified immediate: `0 Q op 0111100000 a b c cmode(15:12) o2(11) 1 d e f g h Rd` ──
    /// `abc` (`bits[18:16]`), o topo de `imm8`.
    private static final int IMM8_TOP_SHIFT = 16;
    private static final int IMM8_TOP_MASK = 0b111;
    /// `defgh` (`bits[9:5]`), a base de `imm8`.
    private static final int IMM8_BOTTOM_SHIFT = 5;
    private static final int IMM8_BOTTOM_MASK = 0b1_1111;
    private static final int IMM8_TOP_POSITION = 5;
    private static final int CMODE_SHIFT = 12;
    private static final int CMODE_MASK = 0b1111;
    /// `op` (`bit29`, a posição do `U`).
    private static final int OP_SHIFT = 29;
    private static final int HALF_BITS = 16;

    /// Tamanhos aceitos: os pares `Q immh` de cada linha (`Q` = `bit30`, `immh` = `bits[22:19]`).
    private enum Sizes {
        /// Vetorial, todos os tamanhos (`D` só com `Q=1`).
        VECTOR_BHSD(". 0001", ". 001.", ". 01..", "1 10..", "1 11.."),
        /// Vetorial, `B`/`H`/`S` (o elemento estreito do grupo estreitando/alargando).
        VECTOR_BHS(". 0001", ". 001.", ". 01.."),
        /// Vetorial `H` (`FEAT_FP16`).
        VECTOR_H(". 001."),
        /// Vetorial `S`/`D`.
        VECTOR_SD(". 01..", "1 10..", "1 11.."),
        /// Escalar, todos os tamanhos.
        SCALAR_BHSD("1 0001", "1 001.", "1 01..", "1 10..", "1 11.."),
        /// Escalar `B`/`H`/`S`.
        SCALAR_BHS("1 0001", "1 001.", "1 01.."),
        /// Escalar `H` (`FEAT_FP16`).
        SCALAR_H("1 001."),
        /// Escalar `S`/`D`.
        SCALAR_SD("1 01..", "1 10..", "1 11.."),
        /// Escalar `D`.
        SCALAR_D("1 10..", "1 11..");

        private final List<String> qImmh;

        Sizes(String... qImmh) {
            this.qImmh = List.of(qImmh);
        }
    }

    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = rows();

    private AdvSimdShiftImmediateRows() {
    }

    private static List<DecodeRow<Aarch64Feature, Ir64Op>> rows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>();
        // deslocamento — 0 Q U 01111 0 immh immb opcode 1 Rn Rd; escalar só D
        shift(rows, "00000", Ir64VectorShiftOp.SSHR, Ir64VectorShiftOp.USHR);
        shift(rows, "00010", Ir64VectorShiftOp.SSRA, Ir64VectorShiftOp.USRA);
        shift(rows, "00100", Ir64VectorShiftOp.SRSHR, Ir64VectorShiftOp.URSHR);
        shift(rows, "00110", Ir64VectorShiftOp.SRSRA, Ir64VectorShiftOp.URSRA);
        shift(rows, "01000", "1", Ir64VectorShiftOp.SRI); // U=0 não alocado
        shift(rows, "01010", Ir64VectorShiftOp.SHL, Ir64VectorShiftOp.SLI);
        // saturante à esquerda: escalar em todos os tamanhos
        saturatingShiftLeft(rows, "01100", "1", Ir64VectorShiftOp.SQSHLU);
        saturatingShiftLeft(rows, "01110", "0", Ir64VectorShiftOp.SQSHL);
        saturatingShiftLeft(rows, "01110", "1", Ir64VectorShiftOp.UQSHL);
        // estreitando: SHRN/RSHRN sem forma escalar
        narrow(rows, "10000", Ir64VectorShiftNarrowOp.SHRN, Ir64VectorShiftNarrowOp.SQSHRUN);
        narrow(rows, "10001", Ir64VectorShiftNarrowOp.RSHRN, Ir64VectorShiftNarrowOp.SQRSHRUN);
        narrow(rows, "10010", Ir64VectorShiftNarrowOp.SQSHRN, Ir64VectorShiftNarrowOp.UQSHRN);
        narrow(rows, "10011", Ir64VectorShiftNarrowOp.SQRSHRN, Ir64VectorShiftNarrowOp.UQRSHRN);
        // alargando: só vetorial
        widen(rows, "0", Ir64VectorShiftWidenOp.SSHLL);
        widen(rows, "1", Ir64VectorShiftWidenOp.USHLL);
        // FP↔ponto fixo (#fbits = deslocamento à direita)
        fixedPoint(rows, "11100", true);
        fixedPoint(rows, "11111", false);
        modifiedImmediate(rows);
        return List.copyOf(rows);
    }

    /// Uma linha por par `Q immh` de `sizes`: `0 Q U prefixo 0 immh immb opcode 1 Rn Rd`.
    private static void add(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String prefix, String u, String opcode, Sizes sizes,
            Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        for (String qImmh : sizes.qImmh) {
            rows.add(DecodeRow.of("0 " + qImmh.charAt(0) + " " + u + " " + prefix + " 0 " + qImmh.substring(2)
                    + " ... " + opcode + " 1 ..... .....", requires, build));
        }
    }

    /// `signed` é a forma `U=0`, `unsigned` a `U=1`.
    private static void shift(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorShiftOp signed,
            Ir64VectorShiftOp unsigned) {
        shift(rows, opcode, "0", signed);
        shift(rows, opcode, "1", unsigned);
    }

    private static void shift(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, String u, Ir64VectorShiftOp op) {
        vectorShift(rows, opcode, u, op);
        scalarShift(rows, opcode, u, op, Sizes.SCALAR_D);
    }

    private static void saturatingShiftLeft(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, String u,
            Ir64VectorShiftOp op) {
        vectorShift(rows, opcode, u, op);
        scalarShift(rows, opcode, u, op, Sizes.SCALAR_BHSD);
    }

    private static void vectorShift(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, String u, Ir64VectorShiftOp op) {
        boolean left = isLeftShift(op);
        add(rows, VECTOR_PREFIX, u, opcode, Sizes.VECTOR_BHSD, null, (word, address) ->
                new AdvSimdIntegerOp64.ShiftImmediate(op, false, q(word), esz(word), shiftAmount(left, word), rd(word),
                        rn(word)));
    }

    private static void scalarShift(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, String u, Ir64VectorShiftOp op,
            Sizes sizes) {
        boolean left = isLeftShift(op);
        add(rows, SCALAR_PREFIX, u, opcode, sizes, null, (word, address) ->
                new AdvSimdIntegerOp64.ShiftImmediate(op, true, false, esz(word), shiftAmount(left, word), rd(word),
                        rn(word)));
    }

    /// `signed` é a forma `U=0`, `unsigned` a `U=1`; `SHRN`/`RSHRN` não têm forma escalar.
    private static void narrow(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorShiftNarrowOp signed,
            Ir64VectorShiftNarrowOp unsigned) {
        for (Ir64VectorShiftNarrowOp op : new Ir64VectorShiftNarrowOp[] {signed, unsigned}) {
            String u = op == signed ? "0" : "1";
            add(rows, VECTOR_PREFIX, u, opcode, Sizes.VECTOR_BHS, null, (word, address) ->
                    new AdvSimdIntegerOp64.ShiftNarrowImmediate(op, false, q(word), esz(word), rightShift(word),
                            rd(word), rn(word)));
            if (op != Ir64VectorShiftNarrowOp.SHRN && op != Ir64VectorShiftNarrowOp.RSHRN) {
                add(rows, SCALAR_PREFIX, u, opcode, Sizes.SCALAR_BHS, null, (word, address) ->
                        new AdvSimdIntegerOp64.ShiftNarrowImmediate(op, true, false, esz(word), rightShift(word),
                                rd(word), rn(word)));
            }
        }
    }

    private static void widen(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, Ir64VectorShiftWidenOp op) {
        add(rows, VECTOR_PREFIX, u, "10100", Sizes.VECTOR_BHS, null, (word, address) ->
                new AdvSimdIntegerOp64.ShiftWidenImmediate(op, q(word), esz(word), leftShift(word), rd(word), rn(word)));
    }

    /// `U=0` é a forma com sinal (`SCVTF`/`FCVTZS`); a meia precisão exige `FEAT_FP16`.
    private static void fixedPoint(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, boolean toFloat) {
        for (String u : new String[] {"0", "1"}) {
            boolean signed = u.equals("0");
            DecodeRow.WordDecoder<Ir64Op> vector = (word, address) ->
                    new AdvSimdFpOp64.FpConvertFixedPoint(false, q(word), esz(word), rightShift(word), toFloat, signed,
                            rd(word), rn(word));
            DecodeRow.WordDecoder<Ir64Op> scalar = (word, address) ->
                    new AdvSimdFpOp64.FpConvertFixedPoint(true, false, esz(word), rightShift(word), toFloat, signed,
                            rd(word), rn(word));
            add(rows, VECTOR_PREFIX, u, opcode, Sizes.VECTOR_H, Aarch64Feature.FP16, vector);
            add(rows, VECTOR_PREFIX, u, opcode, Sizes.VECTOR_SD, null, vector);
            add(rows, SCALAR_PREFIX, u, opcode, Sizes.SCALAR_H, Aarch64Feature.FP16, scalar);
            add(rows, SCALAR_PREFIX, u, opcode, Sizes.SCALAR_SD, null, scalar);
        }
    }

    /// `MOVI`/`MVNI`/`ORR`/`BIC` (`cmode` menos `1111`, `o2=0`), `FMOV` simples (`cmode=1111`, `op=0`), `FMOV`
    /// dupla (`cmode=1111`, `op=1`, só `.2d`) e `FMOV` meia precisão (`cmode=1111`, `o2=1`, `op=0`, `FEAT_FP16`).
    private static void modifiedImmediate(List<DecodeRow<Aarch64Feature, Ir64Op>> rows) {
        DecodeRow.WordDecoder<Ir64Op> expand = (word, address) -> {
            AdvSimdModifiedImmediate.Expanded expanded =
                    AdvSimdModifiedImmediate.expand(imm8(word), cmode(word), (word >>> OP_SHIFT) & 1);
            return new AdvSimdMoveOp64.ModifiedImmediate64(expanded.op(), q(word), rd(word), expanded.imm64());
        };
        // `op` escrito nas duas linhas (e não `.`) para o `bit29` continuar fixo em toda linha (chave de balde).
        for (String cmode : new String[] {"0...", "10..", "110.", "1110"}) {
            modifiedImmediate(rows, ".", "0", cmode, "0", null, expand);
            modifiedImmediate(rows, ".", "1", cmode, "0", null, expand);
        }
        modifiedImmediate(rows, ".", "0", "1111", "0", null, expand);
        modifiedImmediate(rows, "1", "1", "1111", "0", null, (word, address) ->
                new AdvSimdMoveOp64.ModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, true, rd(word),
                        Aarch64Decoder.expandFpImmediate(imm8(word), true)));
        modifiedImmediate(rows, ".", "0", "1111", "1", Aarch64Feature.FP16, (word, address) -> {
            long half = Aarch64Decoder.expandFpImmediateHalf(imm8(word));
            long imm64 = half | (half << HALF_BITS) | (half << 2 * HALF_BITS) | (half << 3 * HALF_BITS);
            return new AdvSimdMoveOp64.ModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, q(word), rd(word), imm64);
        });
    }

    private static void modifiedImmediate(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String q, String op, String cmode, String o2,
            Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        rows.add(DecodeRow.of("0 " + q + " " + op + " " + VECTOR_PREFIX + " 0 0000 ... " + cmode + " " + o2
                + " 1 ..... .....", requires, build));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    /// Posição do bit mais alto de `immh` — as linhas garantem `immh≠0000`.
    private static int esz(int word) {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(immhImmb(word)) - IMMB_BITS;
    }

    private static int immhImmb(int word) {
        return (word >>> SHIFT_FIELD_SHIFT) & SHIFT_FIELD_MASK;
    }

    private static int esize(int word) {
        return BITS_PER_BYTE << esz(word);
    }

    private static int rightShift(int word) {
        return 2 * esize(word) - immhImmb(word);
    }

    private static int leftShift(int word) {
        return immhImmb(word) - esize(word);
    }

    /// `SHL`/`SLI`/`SQSHL`/`UQSHL`/`SQSHLU` deslocam à esquerda; o resto, à direita.
    private static boolean isLeftShift(Ir64VectorShiftOp op) {
        return switch (op) {
            case SHL, SLI, SQSHL, UQSHL, SQSHLU -> true;
            case SSHR, USHR, SRSHR, URSHR, SSRA, USRA, SRSRA, URSRA, SRI -> false;
        };
    }

    private static int shiftAmount(boolean left, int word) {
        return left ? leftShift(word) : rightShift(word);
    }

    private static int imm8(int word) {
        return (((word >>> IMM8_TOP_SHIFT) & IMM8_TOP_MASK) << IMM8_TOP_POSITION)
                | ((word >>> IMM8_BOTTOM_SHIFT) & IMM8_BOTTOM_MASK);
    }

    private static int cmode(int word) {
        return (word >>> CMODE_SHIFT) & CMODE_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
