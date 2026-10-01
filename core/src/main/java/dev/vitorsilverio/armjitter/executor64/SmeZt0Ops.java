package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// `ZERO` multi-vetor de `ZA`, `MOVT` e `LUTI2`/`LUTI4` (SME2, B18.6). Transcrito de `trans_ZERO_za`/`do_movt`/
/// `trans_MOVT_ztz`/`do_lut` (`translate-sme.c`) e de `helper_sme2_luti*`/`do_lut_*` (`vec_helper.c`) do QEMU.
///
/// - **`ZERO_za`** exige modo streaming E `ZA` (`sme_smza_enabled_check`); **`MOVT` entre `ZT0` e `Xt`** só `ZT0`
///   (`sme2_zt0_enabled_check`, que implica `ZA`, com a síndrome PRÓPRIA de `ZT0`); **`MOVT_ztz` e `LUTI`** exigem
///   streaming E `ZT0`.
/// - **`ZT0` é lido como 16 entradas de 32 bits** (`tsize = 32` no QEMU): a entrada `i` mora na palavra `i / 2`,
///   metade `i % 2`; `b`/`h` usam os `8`/`16` bits BAIXOS da entrada, `s` a entrada inteira.
/// - **`SVL`, nunca `VL`**, em todo comprimento de vetor.
final class SmeZt0Ops {
    private static final int WORD_BYTES = Long.BYTES;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ZT0_WORDS = Aarch64MatrixRegisters.ZT0_BITS / Long.SIZE;
    private static final int ZT0_BYTES = Aarch64MatrixRegisters.ZT0_BITS / Byte.SIZE;
    private static final int ZT0_ENTRY_BITS = 32;
    private static final int ZT0_ENTRIES_PER_WORD = Long.SIZE / ZT0_ENTRY_BITS;
    private static final long ZT0_ENTRY_MASK = 0xFFFF_FFFFL;
    private static final int LUTI2_INDEX_BITS = 2;
    private static final int LUTI4_INDEX_BITS = 4;
    private static final int STRIDE_OF_TWO_VECTORS = 8;
    private static final int STRIDE_OF_FOUR_VECTORS = 4;
    private static final int FOUR_VECTORS = 4;

    private SmeZt0Ops() {
    }

    // ── `ZERO` multi-vetor ───────────────────────────────────────────────────────────────────────

    /// Zera `ngrp × nvec` linhas de `ZA`: base `((W & ~(nvec - 1)) + off) MOD (SVL_B / ngrp)` e grupo `r` a
    /// `r × (SVL_B / ngrp)` linhas dela (`get_zarray`).
    ///
    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, Ir64Op.SmeZeroArray op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        int rowsPerGroup = core.streamingVectorLengthBytes() / op.ngrp();
        int base = Aarch64MatrixTileAddressing.resolveSliceIndex(core.x(op.registerIndex()), op.off(), 0,
                rowsPerGroup, op.nvec());
        int rowWords = matrix.zaRowBytes() / WORD_BYTES;
        for (int group = 0; group < op.ngrp(); group++) {
            for (int vector = 0; vector < op.nvec(); vector++) {
                int row = base + group * rowsPerGroup + vector;
                for (int w = 0; w < rowWords; w++) {
                    SmeMovaOps.setZaWordAt(matrix, row, w, 0L);
                }
            }
        }
        return false;
    }

    // ── `MOVT` ───────────────────────────────────────────────────────────────────────────────────

    static boolean execute(Aarch64Core core, Ir64Op.SmeMovt op) {
        if (op.form() == Ir64Op.SmeMovt.Form.VECTOR_TO_ZT) {
            return executeVectorToZt0(core, op);
        }
        if (!core.smeZt0EnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        if (op.form() == Ir64Op.SmeMovt.Form.ZT_TO_X) {
            core.setX(op.rt(), matrix.zt0Word(op.off()));
        } else {
            matrix.setZt0Word(op.off(), core.x(op.rt()));
        }
        return false;
    }

    /// `MOVT ZT0, Zt` (`FEAT_SME_LUTv2`): copia `MIN(SVL_B, 64)` bytes de `Z<rt>` para o segmento `off MOD (64 / tsize)`
    /// de `ZT0`; com `off` zerando o segmento, o RESTO de `ZT0` é zerado (`maxsz = offset ? tsize : 64` no QEMU).
    private static boolean executeVectorToZt0(Aarch64Core core, Ir64Op.SmeMovt op) {
        if (!core.smeStreamingEnabledCheck(op.instructionAddress())
                || !core.smeZt0EnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        int segmentBytes = Math.min(core.streamingVectorLengthBytes(), ZT0_BYTES);
        int segmentOffset = (op.off() % (ZT0_BYTES / segmentBytes)) * segmentBytes;
        int firstWord = segmentOffset / WORD_BYTES;
        int segmentWords = segmentBytes / WORD_BYTES;
        long[] copy = new long[segmentWords];
        for (int w = 0; w < segmentWords; w++) {
            copy[w] = regs.zWord(op.rt(), w);
        }
        for (int w = 0; w < segmentWords; w++) {
            matrix.setZt0Word(firstWord + w, copy[w]);
        }
        if (segmentOffset == 0) {
            for (int w = segmentWords; w < ZT0_WORDS; w++) {
                matrix.setZt0Word(w, 0L);
            }
        }
        return false;
    }

    // ── `LUTI2` / `LUTI4` ────────────────────────────────────────────────────────────────────────

    static boolean execute(Aarch64Core core, Ir64Op.SmeLut op) {
        if (!core.smeStreamingEnabledCheck(op.instructionAddress())
                || !core.smeZt0EnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        int svlBytes = core.streamingVectorLengthBytes();
        int svlWords = svlBytes / WORD_BYTES;
        int indexBits = op.fourBit() ? LUTI4_INDEX_BITS : LUTI2_INDEX_BITS;
        int elementBits = Byte.SIZE << op.esz();
        int elements = svlBytes >>> op.esz();
        int segments = Math.max(1, elementBits / (indexBits * op.count()));
        int segment = op.index() & (segments - 1);
        int stride = !op.strided() ? 1 : op.count() == FOUR_VECTORS ? STRIDE_OF_FOUR_VECTORS : STRIDE_OF_TWO_VECTORS;
        boolean pairSource = op.fourBit() && op.esz() == 0 && op.count() == FOUR_VECTORS;
        int sourceVectors = pairSource ? 2 : 1;

        // Tudo é lido ANTES de qualquer escrita: o destino pode se sobrepor à origem.
        long[] indexes = new long[sourceVectors * svlWords];
        for (int v = 0; v < sourceVectors; v++) {
            for (int w = 0; w < svlWords; w++) {
                indexes[v * svlWords + w] = regs.zWord(op.zn() + v, w);
            }
        }
        long[] table = new long[ZT0_WORDS];
        for (int w = 0; w < ZT0_WORDS; w++) {
            table[w] = matrix.zt0Word(w);
        }
        long[][] results = new long[op.count()][elements];
        long indexMask = (1L << indexBits) - 1L;
        long elementMask = elementBits == Integer.SIZE ? ZT0_ENTRY_MASK : (1L << elementBits) - 1L;
        for (int r = 0; r < op.count(); r++) {
            int firstElement = segment * op.count() * elements + r * elements;
            for (int e = 0; e < elements; e++) {
                int bit = (firstElement + e) * indexBits;
                int index = (int) ((indexes[bit >>> WORD_INDEX_SHIFT] >>> (bit & WORD_BIT_MASK)) & indexMask);
                long entry = (table[index / ZT0_ENTRIES_PER_WORD]
                        >>> ((index % ZT0_ENTRIES_PER_WORD) * ZT0_ENTRY_BITS)) & ZT0_ENTRY_MASK;
                results[r][e] = entry & elementMask;
            }
        }
        for (int r = 0; r < op.count(); r++) {
            for (int e = 0; e < elements; e++) {
                SvePredicateOps.setElementOf(regs, op.zd() + r * stride, e, op.esz(), results[r][e]);
            }
        }
        return false;
    }
}
