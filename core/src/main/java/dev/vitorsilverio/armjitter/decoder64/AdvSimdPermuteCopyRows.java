package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;

import java.util.List;

/// E15.15a: as formas sem feature do espaço AdvSIMD `bit21=0` — "extract" (`EXT`), "table lookup"
/// (`TBL`/`TBX`), "permute" (`UZP*`/`TRN*`/`ZIP*`), "copy" (`DUP`/`INS`/`SMOV`/`UMOV`) e "scalar copy"
/// (`DUP` escalar). Antes eram `decodeAdvancedSimdExtractPermuteTable`/`decodeAdvancedSimdCopy`/
/// `decodeAdvancedSimdScalarDuplicateElement` do `Aarch64Decoder` (B8.10, B8.12, B19.6), com as
/// restrições de campo em `if`; aqui cada combinação válida é uma linha.
///
/// Layout (bit 31 → 0): `b31 Q U prefixo(28:24) size(23:22) b21 Rm(20:16) b15 imm4(14:11) b10 Rn Rd`.
///
/// **Copy:** `imm5` (a posição de `Rm`) codifica tamanho e índice — `esz` = posição do bit 1 mais baixo,
/// `index = imm5 >>> (esz + 1)`. Por isso há uma linha por `esz` (`....1`, `...10`, `..100`, `.1000`);
/// `imm5 = 00000` e `10000` não casam nenhuma (reservados). Restrições por instrução, conferidas no
/// `a64.decode` do QEMU e no `objdump`:
///
/// - `DUP` (elemento e geral): `.1d` não existe — `esz=3` só com `Q=1`;
/// - `INS` (geral e elemento): `Q=1` fixo;
/// - `SMOV`: sem `esz=3`; `esz=2` só para `Xd` (`Q=1`);
/// - `UMOV`: `Q=1` se e só se `esz=3`.
///
/// **Permute:** `bits[11:10]=10`, `size=11` só com `Q=1`; os opcodes `000`/`100` de `bits[14:12]` são
/// reservados. **TBL/TBX:** `size=00`, `bits[11:10]=00`, `len` em `bits[14:13]`. **EXT:** `size=00`, e a
/// forma de 64 bits fixa `bit14=0` (`imm3`).
final class AdvSimdPermuteCopyRows {
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int IMM5_SHIFT = 16;
    private static final int IMM4_SHIFT = 11;
    private static final int IMM4_MASK = 0b1111;
    private static final int TBL_LEN_SHIFT = 13;
    private static final int TBL_LEN_MASK = 0b11;
    private static final int TBX_SHIFT = 12;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;

    /// Colunas: `b31 Q U prefixo size b21 imm5 b15 imm4 b10 Rn Rd`.
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // EXT: imm3 (Q=0) / imm4 (Q=1)
            row("0 0 1 01110 00 0 ..... 0 0... 0 ..... .....", AdvSimdPermuteCopyRows::extract),
            row("0 1 1 01110 00 0 ..... 0 .... 0 ..... .....", AdvSimdPermuteCopyRows::extract),
            // TBL/TBX: len(14:13) tbx(12)
            row("0 . 0 01110 00 0 ..... 0 ...0 0 ..... .....", AdvSimdPermuteCopyRows::tableLookup),
            // permute: size=11 só com Q=1
            row("0 . 0 01110 0. 0 ..... 0 0011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP1)),
            row("0 . 0 01110 10 0 ..... 0 0011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP1)),
            row("0 1 0 01110 11 0 ..... 0 0011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP1)),
            row("0 . 0 01110 0. 0 ..... 0 1011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP2)),
            row("0 . 0 01110 10 0 ..... 0 1011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP2)),
            row("0 1 0 01110 11 0 ..... 0 1011 0 ..... .....", permute(Ir64VectorPermuteOp.UZP2)),
            row("0 . 0 01110 0. 0 ..... 0 0101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN1)),
            row("0 . 0 01110 10 0 ..... 0 0101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN1)),
            row("0 1 0 01110 11 0 ..... 0 0101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN1)),
            row("0 . 0 01110 0. 0 ..... 0 1101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN2)),
            row("0 . 0 01110 10 0 ..... 0 1101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN2)),
            row("0 1 0 01110 11 0 ..... 0 1101 0 ..... .....", permute(Ir64VectorPermuteOp.TRN2)),
            row("0 . 0 01110 0. 0 ..... 0 0111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP1)),
            row("0 . 0 01110 10 0 ..... 0 0111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP1)),
            row("0 1 0 01110 11 0 ..... 0 0111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP1)),
            row("0 . 0 01110 0. 0 ..... 0 1111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP2)),
            row("0 . 0 01110 10 0 ..... 0 1111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP2)),
            row("0 1 0 01110 11 0 ..... 0 1111 0 ..... .....", permute(Ir64VectorPermuteOp.ZIP2)),
            // DUP (elemento): esz=3 só com Q=1
            row("0 . 0 01110 00 0 ....1 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElement),
            row("0 . 0 01110 00 0 ...10 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElement),
            row("0 . 0 01110 00 0 ..100 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElement),
            row("0 1 0 01110 00 0 .1000 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElement),
            // DUP (geral): esz=3 só com Q=1
            row("0 . 0 01110 00 0 ....1 0 0001 1 ..... .....", AdvSimdPermuteCopyRows::duplicateGeneral),
            row("0 . 0 01110 00 0 ...10 0 0001 1 ..... .....", AdvSimdPermuteCopyRows::duplicateGeneral),
            row("0 . 0 01110 00 0 ..100 0 0001 1 ..... .....", AdvSimdPermuteCopyRows::duplicateGeneral),
            row("0 1 0 01110 00 0 .1000 0 0001 1 ..... .....", AdvSimdPermuteCopyRows::duplicateGeneral),
            // INS (geral): Q=1 fixo
            row("0 1 0 01110 00 0 ....1 0 0011 1 ..... .....", AdvSimdPermuteCopyRows::insertGeneral),
            row("0 1 0 01110 00 0 ...10 0 0011 1 ..... .....", AdvSimdPermuteCopyRows::insertGeneral),
            row("0 1 0 01110 00 0 ..100 0 0011 1 ..... .....", AdvSimdPermuteCopyRows::insertGeneral),
            row("0 1 0 01110 00 0 .1000 0 0011 1 ..... .....", AdvSimdPermuteCopyRows::insertGeneral),
            // SMOV: sem esz=3; esz=2 só com Q=1
            row("0 . 0 01110 00 0 ....1 0 0101 1 ..... .....", moveElement(true)),
            row("0 . 0 01110 00 0 ...10 0 0101 1 ..... .....", moveElement(true)),
            row("0 1 0 01110 00 0 ..100 0 0101 1 ..... .....", moveElement(true)),
            // UMOV: Q=1 se e só se esz=3
            row("0 0 0 01110 00 0 ....1 0 0111 1 ..... .....", moveElement(false)),
            row("0 0 0 01110 00 0 ...10 0 0111 1 ..... .....", moveElement(false)),
            row("0 0 0 01110 00 0 ..100 0 0111 1 ..... .....", moveElement(false)),
            row("0 1 0 01110 00 0 .1000 0 0111 1 ..... .....", moveElement(false)),
            // INS (elemento): Q=1 fixo, si em bits[14:11]
            row("0 1 1 01110 00 0 ....1 0 .... 1 ..... .....", AdvSimdPermuteCopyRows::insertElement),
            row("0 1 1 01110 00 0 ...10 0 .... 1 ..... .....", AdvSimdPermuteCopyRows::insertElement),
            row("0 1 1 01110 00 0 ..100 0 .... 1 ..... .....", AdvSimdPermuteCopyRows::insertElement),
            row("0 1 1 01110 00 0 .1000 0 .... 1 ..... .....", AdvSimdPermuteCopyRows::insertElement),
            // DUP escalar (elemento): todo esz
            row("0 1 0 11110 00 0 ....1 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElementScalar),
            row("0 1 0 11110 00 0 ...10 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElementScalar),
            row("0 1 0 11110 00 0 ..100 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElementScalar),
            row("0 1 0 11110 00 0 .1000 0 0000 1 ..... .....", AdvSimdPermuteCopyRows::duplicateElementScalar)
    );

    private AdvSimdPermuteCopyRows() {
    }

    /// Construtor de op desta tabela: nenhuma forma do espaço guarda o endereço da instrução.
    @FunctionalInterface
    private interface AddressFree {
        Ir64Op decode(int word);
    }

    private static DecodeRow<Ir64Op> row(String pattern, AddressFree build) {
        return DecodeRow.of(pattern, null, (word, address) -> build.decode(word));
    }

    private static Ir64Op extract(int word) {
        return new AdvSimdMoveOp64.Extract(q(word), imm4(word), rd(word), rn(word), rm(word));
    }

    private static Ir64Op tableLookup(int word) {
        int len = (word >>> TBL_LEN_SHIFT) & TBL_LEN_MASK;
        boolean tbx = ((word >>> TBX_SHIFT) & 1) != 0;
        return new AdvSimdMoveOp64.TableLookup(tbx, len, q(word), rd(word), rn(word), rm(word));
    }

    private static AddressFree permute(Ir64VectorPermuteOp op) {
        return word -> new AdvSimdMoveOp64.Permute(op, q(word), (word >>> SIZE_SHIFT) & SIZE_MASK,
                rd(word), rn(word), rm(word));
    }

    private static Ir64Op duplicateElement(int word) {
        return new AdvSimdMoveOp64.DuplicateElement(q(word), copyEsz(word), rd(word), rn(word), copyIndex(word));
    }

    private static Ir64Op duplicateGeneral(int word) {
        return new AdvSimdMoveOp64.DuplicateGeneral(q(word), copyEsz(word), rd(word), rn(word));
    }

    private static Ir64Op insertGeneral(int word) {
        return new AdvSimdMoveOp64.InsertGeneral(copyEsz(word), rd(word), rn(word), copyIndex(word));
    }

    private static AddressFree moveElement(boolean signed) {
        return word -> new AdvSimdMoveOp64.MoveElement(signed, q(word), copyEsz(word), rd(word), rn(word),
                copyIndex(word));
    }

    /// `INS` (elemento): o índice de origem é `imm4 >>> esz` (os bits baixos de `imm4` são ignorados).
    private static Ir64Op insertElement(int word) {
        int esz = copyEsz(word);
        return new AdvSimdMoveOp64.InsertElement(esz, rd(word), rn(word), copyIndex(word), imm4(word) >>> esz);
    }

    private static Ir64Op duplicateElementScalar(int word) {
        return new AdvSimdMoveOp64.DuplicateElementScalar(copyEsz(word), rd(word), rn(word), copyIndex(word));
    }

    private static int imm5(int word) {
        return (word >>> IMM5_SHIFT) & REGISTER_MASK;
    }

    /// Tamanho do elemento do copy: posição do bit 1 mais baixo de `imm5` (a linha garante `0`–`3`).
    private static int copyEsz(int word) {
        return Integer.numberOfTrailingZeros(imm5(word));
    }

    private static int copyIndex(int word) {
        return imm5(word) >>> (copyEsz(word) + 1);
    }

    private static int imm4(int word) {
        return (word >>> IMM4_SHIFT) & IMM4_MASK;
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    private static int rm(int word) {
        return (word >>> IMM5_SHIFT) & REGISTER_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
