package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ` (B18.3) — as 47 linhas de `### SME Misc` + `### SME Move
/// into/from Array` + `### SME Move and Zero` de `target/isa-decode/sme.decode` (linhas 22-138).
/// Vivem no prefixo `0xC0` (`bits[31:24] = 11000000`), dentro do mesmo espaço `bits[28:26] = 000`
/// que {@link Aarch64SveCounterDecoder#decodeMultiVector} (os `LD1`/`ST1` multi-vetor SVE2.1,
/// prefixos `0xA0`/`0xA1`) — discriminados por máscara/valor de 32 bits, nunca pelos 3 bits do
/// `topLevelClass` sozinhos.
///
/// **As 45 linhas de `MOVA`/`MOVAZ` viram uma linha da tabela {@link #MOVA_ROWS} cada, TRANSCRITA
/// mecanicamente** por um script à parte (nunca à mão — Armadilha 3 da task: as larguras de
/// `za`/`off` por `esz` e por `count` não seguem uma fórmula uniforme, e a posição do grupo
/// `za`/`off` troca de lado — baixo em `MOVA_*z*`/`MOVA_a*`, alto em `MOVA_z*`/`MOVA_*a*` — conforme
/// `zr` ocupa o outro lado). `rs`/`rv` (bits `14:13`, o `..` do `.decode`) não têm nome ali; são
/// extraídos à parte e somados a `+12`/`+8` (`%mova_rs`/`%mova_rv`), conferido contra
/// `translate-sme.c` (`get_tile_rowcol`/`get_zarray`).
final class Aarch64SmeDecoder {
    // ── `%mova_rs`/`%mova_rv` — bits[14:13], bases DIFERENTES (Armadilha 2 da task) ──────────────
    private static final int RS_RV_FIELD_SHIFT = 13;
    private static final int RS_RV_FIELD_MASK = 0b11;
    /// `%mova_rs = bits[14:13] + 12` (`W12`-`W15`): formas predicada e multi-vetor de tile.
    private static final int MOVA_RS_BASE = 12;
    /// `%mova_rv = bits[14:13] + 8` (`W8`-`W11`): formas array-vetor.
    private static final int MOVA_RV_BASE = 8;

    // ── `### SME Misc` ───────────────────────────────────────────────────────────────────────────
    private static final int ZERO_MASK = 0xFFFFFF00;
    private static final int ZERO_VALUE = 0xC0080000;
    private static final int ZERO_IMM_MASK = 0xFF;
    private static final int ZERO_ZT0_MASK = 0xFFFFFFFF;
    private static final int ZERO_ZT0_VALUE = 0xC0480001;

    /// Uma linha de `MOVA`/`MOVAZ`: máscara/valor de 32 bits + onde (`shift`/largura) cada campo
    /// mora NESTA linha. `-1` em `pgShift` = sem predicado; `-1` em `zaShift` = `za = 0` fixo (só 1
    /// tile neste `esz`); `-1` em `offShift` = `off = 0` fixo (só 1 slice possível, `esz = Q`).
    private record Row(int mask, int value, boolean toVector, boolean zero, boolean array, int count, int esz,
                       int pgShift, int zrShift, int zrWidth, int zaShift, int zaWidth, int offShift, int offWidth) {
        boolean matches(int word) {
            return (word & mask) == value;
        }

        int zr(int word) {
            return (word >>> zrShift) & ((1 << zrWidth) - 1);
        }

        int tile(int word) {
            return zaShift < 0 ? 0 : (word >>> zaShift) & ((1 << zaWidth) - 1);
        }

        int off(int word) {
            return offShift < 0 ? 0 : (word >>> offShift) & ((1 << offWidth) - 1);
        }

        int pg(int word) {
            return pgShift < 0 ? -1 : (word >>> pgShift) & 0b111;
        }

        boolean requiresSme2p1() {
            return zero;
        }

        /// SME2 puro quando não é a forma predicada de 1 vetor (`FEAT_SME`) nem já exige SME2.1.
        boolean requiresSme2() {
            return count > 1; // só consultado quando !zero (MOVAZ já exige SME2.1 antes)
        }
    }

    private static final int V_BIT_SHIFT = 15;

    // ── `### SME Memory` (B18.4) — prefixo `0xE0`/`0xE1` ───────────────────────────────────────────
    /// `LDST1` com `esz = 0`..`3`: `bits[31:24] = 11100000`, `bit 4 = 0`; `bits[23:22] = esz`.
    private static final int LDST1_MASK = 0xFF000010;
    private static final int LDST1_VALUE = 0xE0000000;
    /// `LDST1` com `esz = 4` (`LD1Q`/`ST1Q`): `bits[31:22] = 1110000111`, `bit 4 = 0` — `bit 24 = 1`, não `0`.
    private static final int LDST1_Q_MASK = 0xFFC00010;
    private static final int LDST1_Q_VALUE = 0xE1C00000;
    private static final int LDST1_ESZ_SHIFT = 22;
    private static final int LDST1_ESZ_MASK = 0b11;
    private static final int ESZ_QUAD = 4;
    /// `za:esz` + `off:(4 - esz)` ocupam sempre os 4 bits baixos.
    private static final int LDST1_SPAN_BITS = 4;
    private static final int STORE_BIT_SHIFT = 21;
    private static final int RM_SHIFT = 16;
    private static final int REGISTER_MASK = 0b11111;
    private static final int PG_SHIFT = 10;
    private static final int PG_MASK = 0b111;
    private static final int RN_SHIFT = 5;
    /// `LDR`/`STR` de vetor de `ZA`: `bits[31:22] = 1110000100`, `bits[20:15] = 0`, `bits[12:10] = 0`,
    /// `bit 4 = 0`; `bit 21` = `st`.
    private static final int LDR_ZA_MASK = 0xFFDF9C10;
    private static final int LDR_ZA_VALUE = 0xE1000000;
    private static final int LDR_ZA_IMM_MASK = 0b1111;
    /// `LDR`/`STR` de `ZT0`: `bits[20:15] = 111111`, `bits[14:10] = 0`, `bits[4:0] = 0`.
    private static final int LDR_ZT0_MASK = 0xFFDFFC1F;
    private static final int LDR_ZT0_VALUE = 0xE11F8000;

    // Gerada por script a partir de `target/isa-decode/sme.decode` linhas 27-138 (ver javadoc da
    // classe) — NÃO editar à mão sem regerar e reconferir.
    private static final Row[] MOVA_ROWS = {
            new Row(0xFFFF0010, 0xC0000000, false, false, false, 1, 0, 10, 5, 5, -1, 0, 0, 4), // MOVA_tz esz=0
            new Row(0xFFFF0010, 0xC0400000, false, false, false, 1, 1, 10, 5, 5, 3, 1, 0, 3), // MOVA_tz esz=1
            new Row(0xFFFF0010, 0xC0800000, false, false, false, 1, 2, 10, 5, 5, 2, 2, 0, 2), // MOVA_tz esz=2
            new Row(0xFFFF0010, 0xC0C00000, false, false, false, 1, 3, 10, 5, 5, 1, 3, 0, 1), // MOVA_tz esz=3
            new Row(0xFFFF0010, 0xC0C10000, false, false, false, 1, 4, 10, 5, 5, 0, 4, -1, 0), // MOVA_tz esz=4
            new Row(0xFFFF0200, 0xC0020000, true, false, false, 1, 0, 10, 0, 5, -1, 0, 5, 4), // MOVA_zt esz=0
            new Row(0xFFFF0200, 0xC0420000, true, false, false, 1, 1, 10, 0, 5, 8, 1, 5, 3), // MOVA_zt esz=1
            new Row(0xFFFF0200, 0xC0820000, true, false, false, 1, 2, 10, 0, 5, 7, 2, 5, 2), // MOVA_zt esz=2
            new Row(0xFFFF0200, 0xC0C20000, true, false, false, 1, 3, 10, 0, 5, 6, 3, 5, 1), // MOVA_zt esz=3
            new Row(0xFFFF0200, 0xC0C30000, true, false, false, 1, 4, 10, 0, 5, 5, 4, -1, 0), // MOVA_zt esz=4
            new Row(0xFFFF1C38, 0xC0040000, false, false, false, 2, 0, -1, 6, 4, -1, 0, 0, 3), // MOVA_tz2 esz=0
            new Row(0xFFFF1C38, 0xC0440000, false, false, false, 2, 1, -1, 6, 4, 2, 1, 0, 2), // MOVA_tz2 esz=1
            new Row(0xFFFF1C38, 0xC0840000, false, false, false, 2, 2, -1, 6, 4, 1, 2, 0, 1), // MOVA_tz2 esz=2
            new Row(0xFFFF1C38, 0xC0C40000, false, false, false, 2, 3, -1, 6, 4, 0, 3, -1, 0), // MOVA_tz2 esz=3
            new Row(0xFFFF1F01, 0xC0060000, true, false, false, 2, 0, -1, 1, 4, -1, 0, 5, 3), // MOVA_zt2 esz=0
            new Row(0xFFFF1F01, 0xC0460000, true, false, false, 2, 1, -1, 1, 4, 7, 1, 5, 2), // MOVA_zt2 esz=1
            new Row(0xFFFF1F01, 0xC0860000, true, false, false, 2, 2, -1, 1, 4, 6, 2, 5, 1), // MOVA_zt2 esz=2
            new Row(0xFFFF1F01, 0xC0C60000, true, false, false, 2, 3, -1, 1, 4, 5, 3, -1, 0), // MOVA_zt2 esz=3
            new Row(0xFFFF1C7C, 0xC0040400, false, false, false, 4, 0, -1, 7, 3, -1, 0, 0, 2), // MOVA_tz4 esz=0
            new Row(0xFFFF1C7C, 0xC0440400, false, false, false, 4, 1, -1, 7, 3, 1, 1, 0, 1), // MOVA_tz4 esz=1
            new Row(0xFFFF1C7C, 0xC0840400, false, false, false, 4, 2, -1, 7, 3, 0, 2, -1, 0), // MOVA_tz4 esz=2
            new Row(0xFFFF1C78, 0xC0C40400, false, false, false, 4, 3, -1, 7, 3, 0, 3, -1, 0), // MOVA_tz4 esz=3
            new Row(0xFFFF1F83, 0xC0060400, true, false, false, 4, 0, -1, 2, 3, -1, 0, 5, 2), // MOVA_zt4 esz=0
            new Row(0xFFFF1F83, 0xC0460400, true, false, false, 4, 1, -1, 2, 3, 6, 1, 5, 1), // MOVA_zt4 esz=1
            new Row(0xFFFF1F83, 0xC0860400, true, false, false, 4, 2, -1, 2, 3, 5, 2, -1, 0), // MOVA_zt4 esz=2
            new Row(0xFFFF1F03, 0xC0C60400, true, false, false, 4, 3, -1, 2, 3, 5, 3, -1, 0), // MOVA_zt4 esz=3
            new Row(0xFFFF9C38, 0xC0040800, false, false, true, 2, -1, -1, 6, 4, -1, 0, 0, 3), // MOVA_az2
            new Row(0xFFFF9C78, 0xC0040C00, false, false, true, 4, -1, -1, 7, 3, -1, 0, 0, 3), // MOVA_az4
            new Row(0xFFFF9F01, 0xC0060800, true, false, true, 2, -1, -1, 1, 4, -1, 0, 5, 3), // MOVA_za2
            new Row(0xFFFF9F03, 0xC0060C00, true, false, true, 4, -1, -1, 2, 3, -1, 0, 5, 3), // MOVA_za4
            new Row(0xFFFF9F01, 0xC0060A00, true, true, true, 2, -1, -1, 1, 4, -1, 0, 5, 3), // MOVAZ_za2
            new Row(0xFFFF9F03, 0xC0060E00, true, true, true, 4, -1, -1, 2, 3, -1, 0, 5, 3), // MOVAZ_za4
            new Row(0xFFFF1E00, 0xC0020200, true, true, false, 1, 0, -1, 0, 5, -1, 0, 5, 4), // MOVAZ_zt esz=0
            new Row(0xFFFF1E00, 0xC0420200, true, true, false, 1, 1, -1, 0, 5, 8, 1, 5, 3), // MOVAZ_zt esz=1
            new Row(0xFFFF1E00, 0xC0820200, true, true, false, 1, 2, -1, 0, 5, 7, 2, 5, 2), // MOVAZ_zt esz=2
            new Row(0xFFFF1E00, 0xC0C20200, true, true, false, 1, 3, -1, 0, 5, 6, 3, 5, 1), // MOVAZ_zt esz=3
            new Row(0xFFFF1E00, 0xC0C30200, true, true, false, 1, 4, -1, 0, 5, 5, 4, -1, 0), // MOVAZ_zt esz=4
            new Row(0xFFFF1F01, 0xC0060200, true, true, false, 2, 0, -1, 1, 4, -1, 0, 5, 3), // MOVAZ_zt2 esz=0
            new Row(0xFFFF1F01, 0xC0460200, true, true, false, 2, 1, -1, 1, 4, 7, 1, 5, 2), // MOVAZ_zt2 esz=1
            new Row(0xFFFF1F01, 0xC0860200, true, true, false, 2, 2, -1, 1, 4, 6, 2, 5, 1), // MOVAZ_zt2 esz=2
            new Row(0xFFFF1F01, 0xC0C60200, true, true, false, 2, 3, -1, 1, 4, 5, 3, -1, 0), // MOVAZ_zt2 esz=3
            new Row(0xFFFF1F83, 0xC0060600, true, true, false, 4, 0, -1, 2, 3, -1, 0, 5, 2), // MOVAZ_zt4 esz=0
            new Row(0xFFFF1F83, 0xC0460600, true, true, false, 4, 1, -1, 2, 3, 6, 1, 5, 1), // MOVAZ_zt4 esz=1
            new Row(0xFFFF1F83, 0xC0860600, true, true, false, 4, 2, -1, 2, 3, 5, 2, -1, 0), // MOVAZ_zt4 esz=2
            new Row(0xFFFF1F03, 0xC0C60600, true, true, false, 4, 3, -1, 2, 3, 5, 3, -1, 0), // MOVAZ_zt4 esz=3
    };

    private final Aarch64Architecture architecture;

    Aarch64SmeDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    private boolean hasSme() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
    }

    private boolean hasSme2() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    }

    private boolean hasSme2p1() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);
    }

    /// `null` = não é deste grupo, ou é uma forma cuja feature está ausente (G8 trata como recusa).
    Ir64Op decode(int word, long address) {
        if (!hasSme()) {
            return null;
        }
        if ((word & ZERO_MASK) == ZERO_VALUE) {
            return new Ir64Op.SmeZero(word & ZERO_IMM_MASK, address);
        }
        if ((word & ZERO_ZT0_MASK) == ZERO_ZT0_VALUE) {
            return hasSme2() ? new Ir64Op.SmeZeroZt0(address) : null;
        }
        Ir64Op memory = decodeMemory(word, address);
        if (memory != null) {
            return memory;
        }
        for (Row row : MOVA_ROWS) {
            if (!row.matches(word)) {
                continue;
            }
            if (row.requiresSme2p1() ? !hasSme2p1() : row.requiresSme2() && !hasSme2()) {
                return null;
            }
            int registerBase = row.array() ? MOVA_RV_BASE : MOVA_RS_BASE;
            int registerIndex = registerBase + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
            boolean vertical = !row.array() && ((word >>> V_BIT_SHIFT) & 1) != 0;
            boolean predicated = row.pgShift() >= 0;
            return new Ir64Op.SmeMova(row.toVector(), row.zero(), predicated, row.pg(word), row.count(), row.esz(),
                    row.array() ? -1 : row.tile(word), vertical, row.zr(word), registerIndex, row.off(word), address);
        }
        return null;
    }

    /// `LD1`/`ST1` de tile, `LDR`/`STR` de `ZA` e de `ZT0` (B18.4). `null` = não é desta família (ou
    /// `LDR`/`STR ZT0` sem `FEAT_SME2`).
    private Ir64Op decodeMemory(int word, long address) {
        boolean store = ((word >>> STORE_BIT_SHIFT) & 1) != 0;
        int rn = (word >>> RN_SHIFT) & REGISTER_MASK;
        int registerIndex = MOVA_RS_BASE + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
        if ((word & LDR_ZT0_MASK) == LDR_ZT0_VALUE) {
            return hasSme2() ? new Ir64Op.SmeZt0LoadStore(store, rn, address) : null;
        }
        if ((word & LDR_ZA_MASK) == LDR_ZA_VALUE) {
            return new Ir64Op.SmeArrayLoadStore(store, rn, registerIndex, word & LDR_ZA_IMM_MASK, address);
        }
        int esz;
        if ((word & LDST1_Q_MASK) == LDST1_Q_VALUE) {
            esz = ESZ_QUAD;
        } else if ((word & LDST1_MASK) == LDST1_VALUE) {
            esz = (word >>> LDST1_ESZ_SHIFT) & LDST1_ESZ_MASK;
        } else {
            return null;
        }
        int offsetWidth = LDST1_SPAN_BITS - esz;
        int tile = (word >>> offsetWidth) & ((1 << esz) - 1);
        int offset = word & ((1 << offsetWidth) - 1);
        return new Ir64Op.SmeTileLoadStore(store, esz, tile, ((word >>> V_BIT_SHIFT) & 1) != 0,
                (word >>> PG_SHIFT) & PG_MASK, rn, (word >>> RM_SHIFT) & REGISTER_MASK, registerIndex, offset,
                address);
    }
}
