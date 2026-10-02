package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;

/// `MOP4` (produto externo de quarto de tile, sem predicado) e `TMOP` (produto externo esparso) — B18.5b. Transcrito de
/// `sme_mop4`/`sme_tmop`/`sme_tmop_2way_sh`/`sme_tmop_4way_sb` (`sme_helper.c`) e `ftmopa_hb` (`fp8_helper.c`) do QEMU,
/// **exceto em dois pontos do `TMOP`, onde o QEMU diverge da ARM (DDI 0602, páginas `FTMOPA`/`STMOPA`) e segue-se a
/// ARM**: (1) o vetor de controle é `Zk` (`op3 = Z[k]`), não `Zm` como o `translate-sme.c` passa por engano; (2) o
/// segmento `index` começa no bit `index × csize` com `csize = elementos × bits de controle por elemento`
/// (`VL/8` em `sh`, `VL/4` em `sb`/`hb`) — o `sme_tmop_2way_sh`/`4way_sb` do QEMU usa `(index × VL em bytes) >> 1`, que
/// em `sh` sobrepõe segmentos e em `sb`/`hb` fica 4× abaixo. A aritmética por elemento é a de {@link SmeOuterProductOps} (o
/// `MOP4`/`TMOP` só muda QUAIS elementos se multiplicam), com todos os bits de predicado ligados.
///
/// - **`MOP4`:** 4 quadrantes `(linhaMetade, colunaMetade)`; o índice de linha/coluna é o do tile INTEIRO (`rowBase +
///   row`), a escolha `Zn`/`Zn+1` depende só da metade de COLUNA (`n`) e `Zm`/`Zm+1` só da de LINHA (`m`).
/// - **`TMOP`:** a linha vem de `Zn`/`Zn+1` selecionada por bits de `Zk` do segmento `idx`; origem não selecionada
///   vale `0`.
final class SmeMop4Ops {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int QUADRANTS = 4;
    private static final int ROW_HIGH_QUADRANT_BIT = 2;
    private static final int COLUMN_HIGH_QUADRANT_BIT = 1;
    private static final int SELECT_FIRST = 1;
    private static final int SELECT_FROM_SECOND_REGISTER = 2;
    private static final int CONTROL_CHOICES = 4;
    private static final int ELEMENT_IN_PAIR_BIT = 1;
    private static final int BYTES_PER_ROW_GROUP = 4;
    private static final int SECOND_REGISTER_CONTROL_SHIFT = 4;
    private static final int BYTE_BITS = Byte.SIZE;
    private static final int HALF_BITS = Short.SIZE;
    private static final long HALF_MASK = 0xFFFFL;
    private static final long WORD_MASK = 0xFFFF_FFFFL;

    private SmeMop4Ops() {
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, SmeOp64.Mop4 op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        SmeOp64.OuterProduct.Op kind = outerProductOf(op.op());
        int esz = op.op().accumulatorEsz();
        int halfElements = (core.streamingVectorLengthBytes() >>> 1) >>> esz;
        SmeOuterProductOps.Context context = SmeOuterProductOps.contextFor(core, kind);
        for (int quadrant = 0; quadrant < QUADRANTS; quadrant++) {
            boolean rowHigh = (quadrant & ROW_HIGH_QUADRANT_BIT) != 0;
            boolean columnHigh = (quadrant & COLUMN_HIGH_QUADRANT_BIT) != 0;
            int rowBase = rowHigh ? halfElements : 0;
            int columnBase = columnHigh ? halfElements : 0;
            int rowSource = op.zn() + (columnHigh && op.nPair() ? 1 : 0);
            int columnSource = op.zm() + (rowHigh && op.mPair() ? 1 : 0);
            for (int row = 0; row < halfElements; row++) {
                int rowIndex = rowBase + row;
                long n = SvePredicateOps.elementOf(regs, rowSource, rowIndex, esz);
                int zaRow = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, rowIndex);
                for (int column = 0; column < halfElements; column++) {
                    int columnIndex = columnBase + column;
                    long m = SvePredicateOps.elementOf(regs, columnSource, columnIndex, esz);
                    long accumulator = SmeMovaOps.zaElement(matrix, zaRow, columnIndex << esz, esz);
                    long result = SmeOuterProductOps.combineUnpredicated(kind, context, op.subtract(), n, m,
                            accumulator);
                    SmeMovaOps.setZaElement(matrix, zaRow, columnIndex << esz, esz, result);
                }
            }
        }
        return false;
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, SmeOp64.Tmop op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        SmeOp64.OuterProduct.Op kind = outerProductOf(op.op());
        int esz = op.op().accumulatorEsz();
        int vectorBytes = core.streamingVectorLengthBytes();
        int elements = vectorBytes >>> esz;
        SmeOuterProductOps.Context context = SmeOuterProductOps.contextFor(core, kind);
        Selection selection = selectionOf(op.op());
        int controlBits = selection.bitsPerElement;
        // `ctrl = op3[index*:csize]` com `csize = elementos × bits por elemento` (ARM DDI 0602, `FTMOPA`/`STMOPA`).
        int controlBase = op.index() * elements * controlBits;
        for (int row = 0; row < elements; row++) {
            int zaRow = Aarch64MatrixTileAddressing.rowIndex(op.tile(), esz, row);
            for (int column = 0; column < elements; column++) {
                long control = controlBits(regs, op.zk(), controlBase + column * controlBits, controlBits);
                long n = gather(selection, regs, op.zn(), row, control, esz);
                long m = SvePredicateOps.elementOf(regs, op.zm(), column, esz);
                long accumulator = SmeMovaOps.zaElement(matrix, zaRow, column << esz, esz);
                long result = SmeOuterProductOps.combineUnpredicated(kind, context, false, n, m, accumulator);
                SmeMovaOps.setZaElement(matrix, zaRow, column << esz, esz, result);
            }
        }
        return false;
    }

    /// `extractn(zk, start, length)`: `length` bits do vetor `Zk` a partir do bit `start` (nunca cruza palavra: o
    /// início é múltiplo de `length`).
    private static long controlBits(Aarch64ScalableRegisters regs, int zk, int start, int length) {
        long word = regs.zWord(zk, start >>> WORD_INDEX_SHIFT);
        return (word >>> (start & WORD_BIT_MASK)) & ((1L << length) - 1L);
    }

    /// Como os bits de controle de um elemento escolhem o operando de linha de um `TMOP`.
    private enum Selection {
        /// `sme_tmop` (`hh`/`ss`): 2 bits — `Zn[row]` se o bit 0, senão `Zn+1[row]` se o bit 1, senão 0.
        NON_WIDENING(2),
        /// `sme_tmop_2way_sh`: 4 bits escolhem 2 meias-palavras dentre `{Zn[2r], Zn[2r+1], Zn+1[2r], Zn+1[2r+1]}`.
        TWO_WAY_HALVES(4),
        /// `ftmopa_hb`: 4 bits escolhem 2 bytes da mesma forma.
        TWO_WAY_BYTES(4),
        /// `sme_tmop_4way_sb`: 8 bits — 4 por registrador — escolhem até 2 bytes de cada um.
        FOUR_WAY_BYTES(8);

        private final int bitsPerElement;

        Selection(int bitsPerElement) {
            this.bitsPerElement = bitsPerElement;
        }
    }

    private static long gather(Selection selection, Aarch64ScalableRegisters regs, int zn, int row, long control,
            int esz) {
        return switch (selection) {
            case NON_WIDENING -> control == 0 ? 0L
                    : SvePredicateOps.elementOf(regs, (control & SELECT_FIRST) != 0 ? zn : zn + 1, row, esz);
            case TWO_WAY_HALVES -> gatherPair(regs, zn, row, control, ESZ_HALF, HALF_BITS, WORD_MASK);
            case TWO_WAY_BYTES -> gatherPair(regs, zn, row, control, ESZ_BYTE, BYTE_BITS, HALF_MASK);
            case FOUR_WAY_BYTES -> {
                long low = 0L;
                long high = 0L;
                for (int e = BYTES_PER_ROW_GROUP - 1; e >= 0; e--) {
                    if ((control & (1L << e)) != 0) {
                        low = ((low << BYTE_BITS) | SvePredicateOps.elementOf(regs, zn,
                                BYTES_PER_ROW_GROUP * row + e, ESZ_BYTE)) & HALF_MASK;
                    }
                    if ((control & (1L << (SECOND_REGISTER_CONTROL_SHIFT + e))) != 0) {
                        high = ((high << BYTE_BITS) | SvePredicateOps.elementOf(regs, zn + 1,
                                BYTES_PER_ROW_GROUP * row + e, ESZ_BYTE)) & HALF_MASK;
                    }
                }
                yield (high << HALF_BITS) | low;
            }
        };
    }

    /// 2 de 4: o bit `i` do controle escolhe o elemento `i & 1` do par `2*row` de `Zn` (`i & 2 == 0`) ou de `Zn+1`;
    /// montados do bit mais alto para o mais baixo, o ÚLTIMO selecionado fica nos bits baixos e o excedente sai pelo
    /// topo (`e1` é truncado à largura do par).
    private static long gatherPair(Aarch64ScalableRegisters regs, int zn, int row, long control, int elementEsz,
            int elementBits, long pairMask) {
        long assembled = 0L;
        for (int i = CONTROL_CHOICES - 1; i >= 0; i--) {
            if ((control & (1L << i)) != 0) {
                int register = zn + ((i & SELECT_FROM_SECOND_REGISTER) != 0 ? 1 : 0);
                int element = 2 * row + (i & ELEMENT_IN_PAIR_BIT);
                assembled = ((assembled << elementBits) | SvePredicateOps.elementOf(regs, register, element,
                        elementEsz)) & pairMask;
            }
        }
        return assembled;
    }

    private static Selection selectionOf(SmeOp64.Tmop.Op op) {
        return switch (op) {
            case BFTMOPA_HH, FTMOPA_HH, FTMOPA_SS -> Selection.NON_WIDENING;
            case BFTMOPA_SH, FTMOPA_SH, STMOPA_SH, UTMOPA_SH -> Selection.TWO_WAY_HALVES;
            case FTMOPA_HB -> Selection.TWO_WAY_BYTES;
            case FTMOPA_SB, STMOPA_SB, SUTMOPA_SB, USTMOPA_SB, UTMOPA_SB -> Selection.FOUR_WAY_BYTES;
        };
    }

    /// A aritmética de cada `MOP4` é a do produto externo clássico de mesmo formato.
    private static SmeOp64.OuterProduct.Op outerProductOf(SmeOp64.Mop4.Op op) {
        return switch (op) {
            case FMOP4_HH -> SmeOp64.OuterProduct.Op.FMOPA_H;
            case BFMOP4_HH -> SmeOp64.OuterProduct.Op.BFMOPA;
            case FMOP4_SS -> SmeOp64.OuterProduct.Op.FMOPA_S;
            case FMOP4_DD -> SmeOp64.OuterProduct.Op.FMOPA_D;
            case BFMOP4_SH -> SmeOp64.OuterProduct.Op.BFMOPA_W;
            case FMOP4_SH -> SmeOp64.OuterProduct.Op.FMOPA_W_H;
            case FMOP4A_SB -> SmeOp64.OuterProduct.Op.FMOPA_SB;
            case FMOP4A_HB -> SmeOp64.OuterProduct.Op.FMOPA_HB;
            case SMOP4_SH -> SmeOp64.OuterProduct.Op.SMOPA2_S;
            case UMOP4_SH -> SmeOp64.OuterProduct.Op.UMOPA2_S;
            case SMOP4_SB -> SmeOp64.OuterProduct.Op.SMOPA_S;
            case SUMOP4_SB -> SmeOp64.OuterProduct.Op.SUMOPA_S;
            case UMOP4_SB -> SmeOp64.OuterProduct.Op.UMOPA_S;
            case USMOP4_SB -> SmeOp64.OuterProduct.Op.USMOPA_S;
            case SMOP4_DH -> SmeOp64.OuterProduct.Op.SMOPA_D;
            case SUMOP4_DH -> SmeOp64.OuterProduct.Op.SUMOPA_D;
            case UMOP4_DH -> SmeOp64.OuterProduct.Op.UMOPA_D;
            case USMOP4_DH -> SmeOp64.OuterProduct.Op.USMOPA_D;
        };
    }

    private static SmeOp64.OuterProduct.Op outerProductOf(SmeOp64.Tmop.Op op) {
        return switch (op) {
            case BFTMOPA_HH -> SmeOp64.OuterProduct.Op.BFMOPA;
            case FTMOPA_HH -> SmeOp64.OuterProduct.Op.FMOPA_H;
            case FTMOPA_SS -> SmeOp64.OuterProduct.Op.FMOPA_S;
            case BFTMOPA_SH -> SmeOp64.OuterProduct.Op.BFMOPA_W;
            case FTMOPA_SH -> SmeOp64.OuterProduct.Op.FMOPA_W_H;
            case FTMOPA_HB -> SmeOp64.OuterProduct.Op.FMOPA_HB;
            case FTMOPA_SB -> SmeOp64.OuterProduct.Op.FMOPA_SB;
            case STMOPA_SH -> SmeOp64.OuterProduct.Op.SMOPA2_S;
            case UTMOPA_SH -> SmeOp64.OuterProduct.Op.UMOPA2_S;
            case STMOPA_SB -> SmeOp64.OuterProduct.Op.SMOPA_S;
            case SUTMOPA_SB -> SmeOp64.OuterProduct.Op.SUMOPA_S;
            case USTMOPA_SB -> SmeOp64.OuterProduct.Op.USMOPA_S;
            case UTMOPA_SB -> SmeOp64.OuterProduct.Op.UMOPA_S;
        };
    }
}
