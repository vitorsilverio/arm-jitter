package dev.vitorsilverio.armjitter.core64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.3 — aritmética pura de {@link Aarch64MatrixTileAddressing}, independente de `MOVA`/`MOVAZ`
/// (a peça que B18.4/B18.5/B18.9+ reusam). Casos medidos contra `get_tile_rowcol`/`get_zarray` de
/// `translate-sme.c` (QEMU).
class Aarch64MatrixTileAddressingTest {
    private static final int SVL_256_BYTES = 32;
    private static final int SVL_512_BYTES = 64;

    @Test
    void sliceIndexIsModularWithoutRoundingWhenGroupIsOne() {
        // esz=0 (byte): elementsPerRow = 32. W<rs> = 40 estoura o módulo.
        assertEquals(40 % SVL_256_BYTES, Aarch64MatrixTileAddressing.resolveSliceIndex(40, 0, 0, SVL_256_BYTES, 1));
        assertEquals(0, Aarch64MatrixTileAddressing.resolveSliceIndex(SVL_256_BYTES, 0, 0, SVL_256_BYTES, 1),
                "exatamente o módulo volta a 0");
    }

    @Test
    void sliceIndexAddsTheImmediateBeforeTheModulo() {
        // esz=2 (word, 4 bytes): elementsPerRow = 32/4 = 8.
        assertEquals(5, Aarch64MatrixTileAddressing.resolveSliceIndex(2, 3, 2, SVL_256_BYTES, 1));
        assertEquals(1, Aarch64MatrixTileAddressing.resolveSliceIndex(6, 3, 2, SVL_256_BYTES, 1), "(6+3) MOD 8 = 1");
    }

    @Test
    void sliceIndexRoundsTheRegisterDownToAMultipleOfGroupBeforeAddingTheImmediate() {
        // group=2: W<rs>=5 arredonda para 4 ANTES de somar imm.
        int esz = 0;
        assertEquals(4 + 3, Aarch64MatrixTileAddressing.resolveSliceIndex(5, 3, esz, SVL_256_BYTES, 2));
        assertEquals(4 + 3, Aarch64MatrixTileAddressing.resolveSliceIndex(4, 3, esz, SVL_256_BYTES, 2),
                "W<rs>=4 já é múltiplo de 2: nenhum arredondamento visível");
        assertEquals(4 + 3, Aarch64MatrixTileAddressing.resolveSliceIndex(7, 3, esz, SVL_256_BYTES, 4),
                "group=4: 7 arredonda para baixo até 4 (múltiplo de 4) ANTES de somar o imediato");
    }

    @Test
    void rowIndexIsTilePlusSliceTimesElementSizeBytes() {
        assertEquals(5, Aarch64MatrixTileAddressing.rowIndex(5, 0, 0), "esz=0 (byte): 1 único tile por linha");
        assertEquals(1 + 3 * 2, Aarch64MatrixTileAddressing.rowIndex(1, 1, 3), "esz=1 (half, 2 bytes)");
        assertEquals(2 + 5 * 8, Aarch64MatrixTileAddressing.rowIndex(2, 3, 5), "esz=3 (doubleword, 8 bytes)");
        assertEquals(0 + 1 * 16, Aarch64MatrixTileAddressing.rowIndex(0, 4, 1), "esz=4 (quadword, 16 bytes)");
    }

    @Test
    void arrayBaseRowUsesNoRoundingAndDividesSvlByTheGroupCount() {
        // n=2: rowsPerGroup = svlBytes/2 = 16.
        assertEquals(5, Aarch64MatrixTileAddressing.resolveArrayBaseRow(5, 0, SVL_256_BYTES, 2));
        assertEquals(0, Aarch64MatrixTileAddressing.resolveArrayBaseRow(16, 0, SVL_256_BYTES, 2), "16 MOD 16 = 0");
        assertEquals(3, Aarch64MatrixTileAddressing.resolveArrayBaseRow(1, 2, SVL_256_BYTES, 2), "(1+2) MOD 16 = 3");
    }

    @Test
    void arrayBaseRowScalesWithSvl() {
        // n=4, SVL=512 bytes: rowsPerGroup = 16.
        assertEquals(0, Aarch64MatrixTileAddressing.resolveArrayBaseRow(16, 0, SVL_512_BYTES, 4));
        assertEquals(1, Aarch64MatrixTileAddressing.resolveArrayBaseRow(17, 0, SVL_512_BYTES, 4));
    }
}
