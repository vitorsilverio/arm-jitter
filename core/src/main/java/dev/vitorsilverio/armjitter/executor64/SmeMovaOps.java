package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica de `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ` (SME, B18.3). O endereçamento de tile/slice é todo
/// {@link Aarch64MatrixTileAddressing} (B18.4/B18.5/B18.9+ reusam); esta classe só lê/escreve
/// elementos nas posições que ele devolve.
///
/// **Elementos de até `Q` (128 bits) não cabem num `long`**: todo acesso de dado passa por
/// {@link #copyElement}, que trata `esz == ESZ_QUAD` como um PAR de palavras de 64 bits (sempre
/// alinhado — um elemento de 16 bytes começa sempre num múltiplo de 16, logo num limite de palavra
/// par) e os demais `esz` via {@link SvePredicateOps#elementOf}/{@link SvePredicateOps#setElementOf}
/// (mesmo utilitário que o resto do SVE usa para `Z`).
final class SmeMovaOps {
    private static final int WORD_BYTES = Long.BYTES;
    private static final int ESZ_QUAD = 4;
    private static final int ROWS_PER_ZERO_GROUP = 8;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;

    private SmeMovaOps() {
    }

    // ── `ZERO` ───────────────────────────────────────────────────────────────────────────────────

    /// `ZERO imm8` (`helper_sme_zero` do QEMU): a linha `i` (`0`..`SVL-1`) é zerada quando o bit
    /// `i MOD 8` de `imm8` está ligado — sempre 8 grupos, **independente de `esz`** (não há campo
    /// `esz` nesta instrução; a correspondência bit→linha é fixa, só a INTERPRETAÇÃO como tile(s)
    /// depende de `esz`, e essa interpretação não importa para `ZERO` zerar bytes).
    ///
    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, Ir64Op.SmeZero op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        int svlBytes = core.streamingVectorLengthBytes();
        int rowWords = matrix.zaRowBytes() / WORD_BYTES;
        for (int row = 0; row < svlBytes; row++) {
            if ((op.imm8() & (1 << (row % ROWS_PER_ZERO_GROUP))) == 0) {
                continue;
            }
            int base = row * rowWords;
            for (int w = 0; w < rowWords; w++) {
                matrix.setZaWord(base + w, 0L);
            }
        }
        return false;
    }

    /// `ZERO_zt0`: zera o registrador `ZT0` (512 bits) inteiro.
    static boolean execute(Aarch64Core core, Ir64Op.SmeZeroZt0 op) {
        if (!core.smeZt0EnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        for (int w = 0; w < Aarch64MatrixRegisters.ZT0_BITS / Long.SIZE; w++) {
            matrix.setZt0Word(w, 0L);
        }
        return false;
    }

    // ── `MOVA`/`MOVAZ` ───────────────────────────────────────────────────────────────────────────

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, Ir64Op.SmeMova op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        int svlBytes = core.streamingVectorLengthBytes();
        if (op.tile() < 0) {
            executeArray(core, matrix, regs, op, svlBytes);
        } else if (op.predicated()) {
            executeSingle(core, matrix, regs, op, svlBytes);
        } else {
            executeGroup(core, matrix, regs, op, svlBytes);
        }
        return false;
    }

    /// `MOVA_tz`/`MOVA_zt` — predicada, 1 vetor. Elemento com {@link Ir64Op.SmeMova#pg()} falso não
    /// é escrito (merging), nos dois sentidos e nos dois eixos.
    private static void executeSingle(Aarch64Core core, Aarch64MatrixRegisters matrix, Aarch64ScalableRegisters regs,
            Ir64Op.SmeMova op, int svlBytes) {
        int esz = op.esz();
        int elementsPerRow = svlBytes >>> esz;
        long registerValue = core.x(op.registerIndex());
        int slice = Aarch64MatrixTileAddressing.resolveSliceIndex(registerValue, op.offset(), esz, svlBytes, 1);
        if (!op.vertical()) {
            int row = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, slice);
            for (int e = 0; e < elementsPerRow; e++) {
                if (!predicateActive(regs, op.pg(), e, esz)) {
                    continue;
                }
                copyElement(matrix, regs, row, e << esz, esz, op.zr(), e, op.toVector(), false);
            }
        } else {
            int byteOffset = slice << esz;
            for (int k = 0; k < elementsPerRow; k++) {
                if (!predicateActive(regs, op.pg(), k, esz)) {
                    continue;
                }
                int row = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, k);
                copyElement(matrix, regs, row, byteOffset, esz, op.zr(), k, op.toVector(), false);
            }
        }
    }

    /// `MOVA_tz2`/`zt2`/`tz4`/`zt4` + `MOVAZ_zt`/`zt2`/`zt4` — multi-vetor de tile, sem predicado
    /// (sempre ativo). Cada um dos {@link Ir64Op.SmeMova#count()} sub-vetores resolve sua PRÓPRIA
    /// slice (`off × count + i`, {@code group = count}): no horizontal isso escolhe `count` LINHAS
    /// consecutivas (copiadas inteiras); no vertical, `count` COLUNAS consecutivas do MESMO tile.
    private static void executeGroup(Aarch64Core core, Aarch64MatrixRegisters matrix, Aarch64ScalableRegisters regs,
            Ir64Op.SmeMova op, int svlBytes) {
        int esz = op.esz();
        int elementsPerRow = svlBytes >>> esz;
        long registerValue = core.x(op.registerIndex());
        int rowWords = svlBytes / WORD_BYTES;
        for (int i = 0; i < op.count(); i++) {
            int slice = Aarch64MatrixTileAddressing.resolveSliceIndex(registerValue, op.offset() * op.count() + i, esz,
                    svlBytes, op.count());
            int zIndex = op.zr() * op.count() + i;
            if (!op.vertical()) {
                int row = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, slice);
                for (int w = 0; w < rowWords; w++) {
                    if (op.toVector()) {
                        long value = matrix.zaWord(row * rowWords + w);
                        regs.setZWord(zIndex, w, value);
                        if (op.zero()) {
                            matrix.setZaWord(row * rowWords + w, 0L);
                        }
                    } else {
                        matrix.setZaWord(row * rowWords + w, regs.zWord(zIndex, w));
                    }
                }
            } else {
                int byteOffset = slice << esz;
                for (int k = 0; k < elementsPerRow; k++) {
                    int row = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, k);
                    copyElement(matrix, regs, row, byteOffset, esz, zIndex, k, op.toVector(), op.zero());
                }
            }
        }
    }

    /// `MOVA_az2`/`az4`/`za2`/`za4` + `MOVAZ_za2`/`za4` — array-vetor: {@link Ir64Op.SmeMova#count()}
    /// LINHAS inteiras de `ZA` (sem tile/`esz`/eixo), endereçadas por `W8`-`W11`.
    private static void executeArray(Aarch64Core core, Aarch64MatrixRegisters matrix, Aarch64ScalableRegisters regs,
            Ir64Op.SmeMova op, int svlBytes) {
        long registerValue = core.x(op.registerIndex());
        int rowsPerGroup = svlBytes / op.count();
        int baseRow = Aarch64MatrixTileAddressing.resolveArrayBaseRow(registerValue, op.offset(), svlBytes, op.count());
        int rowWords = svlBytes / WORD_BYTES;
        for (int i = 0; i < op.count(); i++) {
            int row = baseRow + i * rowsPerGroup;
            int zIndex = op.zr() * op.count() + i;
            for (int w = 0; w < rowWords; w++) {
                if (op.toVector()) {
                    long value = matrix.zaWord(row * rowWords + w);
                    regs.setZWord(zIndex, w, value);
                    if (op.zero()) {
                        matrix.setZaWord(row * rowWords + w, 0L);
                    }
                } else {
                    matrix.setZaWord(row * rowWords + w, regs.zWord(zIndex, w));
                }
            }
        }
    }

    // ── Auxiliares de elemento ───────────────────────────────────────────────────────────────────

    /// Copia UM elemento (tamanho `1 << esz` bytes) entre `ZA[row][byteOffset]` e `Z<zReg>[zIndex]`.
    private static void copyElement(Aarch64MatrixRegisters matrix, Aarch64ScalableRegisters regs, int row,
            int byteOffset, int esz, int zReg, int zIndex, boolean toVector, boolean zeroSource) {
        if (esz == ESZ_QUAD) {
            if (toVector) {
                long lo = zaWordAt(matrix, row, byteOffset / WORD_BYTES);
                long hi = zaWordAt(matrix, row, byteOffset / WORD_BYTES + 1);
                regs.setZWord(zReg, zIndex * 2, lo); // índice já é o elemento de 128 bits -> 2 palavras
                regs.setZWord(zReg, zIndex * 2 + 1, hi);
                if (zeroSource) {
                    setZaWordAt(matrix, row, byteOffset / WORD_BYTES, 0L);
                    setZaWordAt(matrix, row, byteOffset / WORD_BYTES + 1, 0L);
                }
            } else {
                long lo = regs.zWord(zReg, zIndex * 2);
                long hi = regs.zWord(zReg, zIndex * 2 + 1);
                setZaWordAt(matrix, row, byteOffset / WORD_BYTES, lo);
                setZaWordAt(matrix, row, byteOffset / WORD_BYTES + 1, hi);
            }
            return;
        }
        if (toVector) {
            long value = zaElement(matrix, row, byteOffset, esz);
            SvePredicateOps.setElementOf(regs, zReg, zIndex, esz, value);
            if (zeroSource) {
                setZaElement(matrix, row, byteOffset, esz, 0L);
            }
        } else {
            long value = SvePredicateOps.elementOf(regs, zReg, zIndex, esz);
            setZaElement(matrix, row, byteOffset, esz, value);
        }
    }

    static long zaWordAt(Aarch64MatrixRegisters matrix, int row, int wordInRow) {
        int rowWords = matrix.zaRowBytes() / WORD_BYTES;
        return matrix.zaWord(row * rowWords + wordInRow);
    }

    static void setZaWordAt(Aarch64MatrixRegisters matrix, int row, int wordInRow, long value) {
        int rowWords = matrix.zaRowBytes() / WORD_BYTES;
        matrix.setZaWord(row * rowWords + wordInRow, value);
    }

    static long zaElement(Aarch64MatrixRegisters matrix, int row, int byteOffset, int esz) {
        int elementBits = Byte.SIZE << esz;
        int bitOffset = byteOffset * Byte.SIZE;
        long word = zaWordAt(matrix, row, bitOffset >>> WORD_INDEX_SHIFT);
        long shifted = word >>> (bitOffset & WORD_BIT_MASK);
        return elementBits == Long.SIZE ? shifted : shifted & ((1L << elementBits) - 1L);
    }

    static void setZaElement(Aarch64MatrixRegisters matrix, int row, int byteOffset, int esz, long value) {
        int elementBits = Byte.SIZE << esz;
        int bitOffset = byteOffset * Byte.SIZE;
        int wordInRow = bitOffset >>> WORD_INDEX_SHIFT;
        if (elementBits == Long.SIZE) {
            setZaWordAt(matrix, row, wordInRow, value);
            return;
        }
        int shift = bitOffset & WORD_BIT_MASK;
        long fieldMask = ((1L << elementBits) - 1L) << shift;
        long word = zaWordAt(matrix, row, wordInRow);
        setZaWordAt(matrix, row, wordInRow, (word & ~fieldMask) | ((value << shift) & fieldMask));
    }

    /// Bit de predicado do elemento `index` (tamanho `1 << esz` bytes): `P` guarda um bit por BYTE
    /// do vetor, no byte INICIAL do elemento (`e << esz`) — mesma convenção de
    /// {@code SveIntegerPredicatedOps}.
    static boolean predicateActive(Aarch64ScalableRegisters regs, int pg, int index, int esz) {
        int bit = index << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }
}
