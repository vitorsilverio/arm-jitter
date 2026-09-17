package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (gather/scatter por vetor de offsets) e `VLDRW_sg_imm`/
/// `VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (gather/scatter com base vetorial e imediato)
/// (perfil M, B16.5, MVE/Helium) — 7 linhas de `target/isa-decode/mve.decode` (confirmadas bit a
/// bit via leitura direta do arquivo real nesta rodada):
///
/// ```
/// %qd 22:1 13:3
/// %qm 5:1 1:3
/// %qn 7:1 17:3
/// %sg_msize 6:1 4:1
///
/// @vldst_sg .... .... .... rn:4 .... ... size:2 ... ... os:1 &vldst_sg
///           qd=%qd qm=%qm msize=%sg_msize
/// VLDR_S_sg        111 0 1100 1 . 01 .... ... 0 111 . .... .... @vldst_sg
/// VLDR_U_sg        111 1 1100 1 . 01 .... ... 0 111 . .... .... @vldst_sg
/// VSTR_sg          111 0 1100 1 . 00 .... ... 0 111 . .... .... @vldst_sg
///
/// # Qm is in the fields usually labeled Qn
/// @vldst_sg_imm .... .... a:1 . w:1 . .... .... .... . imm:7 &vldst_sg_imm
///               qd=%qd qm=%qn
/// VLDRW_sg_imm     111 1 1101 ... 1 ... 0 ... 1 1110 .... .... @vldst_sg_imm
/// VLDRD_sg_imm     111 1 1101 ... 1 ... 0 ... 1 1111 .... .... @vldst_sg_imm
/// VSTRW_sg_imm     111 1 1101 ... 0 ... 0 ... 1 1110 .... .... @vldst_sg_imm
/// VSTRD_sg_imm     111 1 1101 ... 0 ... 0 ... 1 1111 .... .... @vldst_sg_imm
/// ```
///
/// **Bit a bit confirmado por contagem de tokens (32 bits cada)**: para `@vldst_sg`, `bit28`
/// seleciona `S`(`0`)/`U`(`1`) nos loads (irrelevante — sempre `0` — no store); `bits[21:20]`
/// discriminam `load`(`01`)/`store`(`00`); `rn` em `bits[19:16]`; `qd` = `bit22`(alto) ++
/// `bits[15:13]`(baixo); `bits[12]=0`/`bits[11:9]=111` fixos; `size` (largura NO REGISTRADOR) em
/// `bits[8:7]`; `msize` (largura em MEMÓRIA) = `bit6`(alto) ++ `bit4`(baixo) (`%sg_msize`); `qm` =
/// `bit5`(alto) ++ `bits[3:1]`(baixo); `os` = `bit0`. Para `@vldst_sg_imm`: `a`=`bit23`, `w`=`bit21`,
/// `L`(load/store) = `bit20`; `qm` vem do campo NORMALMENTE rotulado `Qn` (`bit7`(alto) ++
/// `bits[19:17]`(baixo), comentário literal do arquivo — **Armadilha 1** da task, reusar `%qm`
/// aqui dá o registrador errado); `qd` = `bit22` ++ `bits[15:13]`; `bit16=0`/`bit12=1` fixos;
/// `bits[11:8]` marca o tamanho (`1110`=`W`→`sizeLog2=2`, `1111`=`D`→`sizeLog2=3`); `imm7` em
/// `bits[6:0]`.
///
/// **Combinações `(msize, size, os)` válidas** (`fns[os][msize][size]` reais de `trans_VLDR_S_sg`/
/// `trans_VLDR_U_sg`/`trans_VSTR_sg`, `target/arm/tcg/translate-mve.c`, medidas via leitura direta):
/// `VLDR_S_sg` só alarga (nunca `msize == size`): `os=0` → `(0,1)`/`(0,2)`/`(1,2)`; `os=1` →
/// `(1,2)`. `VLDR_U_sg`/`VSTR_sg` (mesma forma `fns`): `os=0` → `(0,0)`/`(0,1)`/`(0,2)`/`(1,1)`/
/// `(1,2)`/`(2,2)`/`(3,3)`; `os=1` → `(1,1)`/`(1,2)`/`(2,2)`/`(3,3)`. Qualquer outra combinação não
/// decodifica (G8) — mesma disciplina de "related encoding" da B16.3/B16.4.
///
/// **`Qd == Qm` é UNPREDICTABLE nos gathers de offsets** (`trans_VLDR_S_sg`/`_U_sg`: `if (a->qd ==
/// a->qm) return false;`) e também nas 4 formas `*_sg_imm` (`trans_VLDRW_sg_imm`/`VLDRD_sg_imm`:
/// mesma checagem — só `VSTRW_sg_imm`/`VSTRD_sg_imm`, que não reescrevem `Qd`, dispensam a
/// checagem no QEMU real, mas a recusamos aqui por simetria/segurança — nenhum encoding legítimo
/// a produz de qualquer forma quando `writeback` está ativo, e sem writeback a checagem é inócua).
/// `Rn == 15` é UNPREDICTABLE na forma de offsets (`do_ldst_sg`); a forma imediata não tem `Rn`.
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Usa o escape hatch de lifting
/// ({@link DecodedInstruction#lifted}), mesmo precedente de {@link Thumb2MveLoadStoreDecoder}.
public final class Thumb2MveGatherScatterDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;

    // ── @vldst_sg (gather/scatter por vetor de offsets) ───────────────────────────────────────
    private static final int OFFSET_U_BIT = 28;
    private static final int OFFSET_MID4_SHIFT = 24;
    private static final int OFFSET_MID4_MASK = 0xF;
    private static final int OFFSET_MID4_VALUE = 0b1100;
    private static final int OFFSET_FIXED_BIT23 = 23;
    private static final int OFFSET_QD_HIGH_BIT = 22;
    private static final int OFFSET_L_MARKER_SHIFT = 20;
    private static final int OFFSET_L_MARKER_MASK = 0x3;
    private static final int OFFSET_L_MARKER_LOAD = 0b01;
    private static final int OFFSET_L_MARKER_STORE = 0b00;
    private static final int OFFSET_RN_SHIFT = 16;
    private static final int OFFSET_RN_MASK = 0xF;
    private static final int OFFSET_QD_LOW_SHIFT = 13;
    private static final int OFFSET_QD_LOW_MASK = 0x7;
    private static final int OFFSET_ZERO_LITERAL_BIT = 12;
    private static final int OFFSET_LOW3_SHIFT = 9;
    private static final int OFFSET_LOW3_MASK = 0x7;
    private static final int OFFSET_LOW3_VALUE = 0b111;
    private static final int OFFSET_SIZE_SHIFT = 7;
    private static final int OFFSET_SIZE_MASK = 0x3;
    private static final int OFFSET_MSIZE_HIGH_BIT = 6;
    private static final int OFFSET_QM_HIGH_BIT = 5;
    private static final int OFFSET_MSIZE_LOW_BIT = 4;
    private static final int OFFSET_QM_LOW_SHIFT = 1;
    private static final int OFFSET_QM_LOW_MASK = 0x7;
    private static final int OFFSET_OS_BIT = 0;

    // ── @vldst_sg_imm (gather/scatter com base vetorial e imediato) ───────────────────────────
    private static final int IMM_MID4_SHIFT = 24;
    private static final int IMM_MID4_MASK = 0xF;
    private static final int IMM_MID4_VALUE = 0b1101;
    private static final int IMM_A_BIT = 23;
    private static final int IMM_QD_HIGH_BIT = 22;
    private static final int IMM_W_BIT = 21;
    private static final int IMM_L_BIT = 20;
    private static final int IMM_QM_LOW_SHIFT = 17;
    private static final int IMM_QM_LOW_MASK = 0x7;
    private static final int IMM_ZERO_LITERAL_BIT = 16;
    private static final int IMM_QD_LOW_SHIFT = 13;
    private static final int IMM_QD_LOW_MASK = 0x7;
    private static final int IMM_ONE_LITERAL_BIT = 12;
    private static final int IMM_SIZE_MARKER_SHIFT = 8;
    private static final int IMM_SIZE_MARKER_MASK = 0xF;
    private static final int IMM_SIZE_MARKER_WORD = 0b1110;
    private static final int IMM_SIZE_MARKER_DOUBLEWORD = 0b1111;
    private static final int IMM_QM_HIGH_BIT = 7;
    private static final int IMM_IMM7_MASK = 0x7F;

    private static final int WORD_SIZE_LOG2 = 2;
    private static final int DOUBLEWORD_SIZE_LOG2 = 3;
    private static final int PROGRAM_COUNTER = 15;

    private final ArmArchitecture architecture;

    public Thumb2MveGatherScatterDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE) {
            return null;
        }
        int mid4 = (raw >>> OFFSET_MID4_SHIFT) & OFFSET_MID4_MASK;
        if (mid4 == OFFSET_MID4_VALUE) {
            DecodedInstruction offsetForm = tryDecodeOffsetForm(raw, address, condition);
            if (offsetForm != null) {
                return offsetForm;
            }
        }
        if (((raw >>> IMM_MID4_SHIFT) & IMM_MID4_MASK) == IMM_MID4_VALUE) {
            return tryDecodeImmediateForm(raw, address, condition);
        }
        return null;
    }

    private DecodedInstruction tryDecodeOffsetForm(int raw, int address, Condition condition) {
        if (((raw >>> OFFSET_FIXED_BIT23) & 1) == 0
                || ((raw >>> OFFSET_ZERO_LITERAL_BIT) & 1) != 0
                || ((raw >>> OFFSET_LOW3_SHIFT) & OFFSET_LOW3_MASK) != OFFSET_LOW3_VALUE) {
            return null;
        }
        int lMarker = (raw >>> OFFSET_L_MARKER_SHIFT) & OFFSET_L_MARKER_MASK;
        if (lMarker != OFFSET_L_MARKER_LOAD && lMarker != OFFSET_L_MARKER_STORE) {
            return null;
        }
        boolean load = lMarker == OFFSET_L_MARKER_LOAD;
        boolean unsignedForm = ((raw >>> OFFSET_U_BIT) & 1) != 0;
        if (!load && unsignedForm) {
            return null; // VSTR_sg tem bit28 sempre 0 (mesma forma "U" que VLDR_U_sg)
        }
        int rn = (raw >>> OFFSET_RN_SHIFT) & OFFSET_RN_MASK;
        if (rn == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE real (do_ldst_sg: a->rn == 15)
        }
        int qd = ((raw >>> OFFSET_QD_HIGH_BIT) & 1) << 3 | ((raw >>> OFFSET_QD_LOW_SHIFT) & OFFSET_QD_LOW_MASK);
        int qm = ((raw >>> OFFSET_QM_HIGH_BIT) & 1) << 3 | ((raw >>> OFFSET_QM_LOW_SHIFT) & OFFSET_QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        if (qd == qm) {
            return null; // UNPREDICTABLE real (trans_VLDR_S_sg/_U_sg: a->qd == a->qm)
        }
        int size = (raw >>> OFFSET_SIZE_SHIFT) & OFFSET_SIZE_MASK;
        int msize = ((raw >>> OFFSET_MSIZE_HIGH_BIT) & 1) << 1 | ((raw >>> OFFSET_MSIZE_LOW_BIT) & 1);
        boolean os = ((raw >>> OFFSET_OS_BIT) & 1) != 0;
        boolean signedForm = load && !unsignedForm;
        if (!isValidGatherScatterCombo(signedForm, msize, size, os)) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveGatherScatterOffset(qd, qm, rn, msize, size, signedForm, os, load, condition));
    }

    /// `fns[os][msize][size]` reais de `trans_VLDR_S_sg`/`trans_VLDR_U_sg`/`trans_VSTR_sg`
    /// (`target/arm/tcg/translate-mve.c`) — `VLDR_U_sg`/`VSTR_sg` compartilham a MESMA forma.
    private static boolean isValidGatherScatterCombo(boolean signedForm, int msize, int size, boolean os) {
        if (signedForm) {
            if (!os) {
                return (msize == 0 && (size == 1 || size == 2)) || (msize == 1 && size == 2);
            }
            return msize == 1 && size == 2;
        }
        if (!os) {
            return switch (msize) {
                case 0 -> size == 0 || size == 1 || size == 2;
                case 1 -> size == 1 || size == 2;
                case 2 -> size == 2;
                case 3 -> size == 3;
                default -> false;
            };
        }
        return switch (msize) {
            case 1 -> size == 1 || size == 2;
            case 2 -> size == 2;
            case 3 -> size == 3;
            default -> false;
        };
    }

    private DecodedInstruction tryDecodeImmediateForm(int raw, int address, Condition condition) {
        if (((raw >>> IMM_ZERO_LITERAL_BIT) & 1) != 0 || ((raw >>> IMM_ONE_LITERAL_BIT) & 1) == 0) {
            return null;
        }
        int sizeMarker = (raw >>> IMM_SIZE_MARKER_SHIFT) & IMM_SIZE_MARKER_MASK;
        int sizeLog2 = switch (sizeMarker) {
            case IMM_SIZE_MARKER_WORD -> WORD_SIZE_LOG2;
            case IMM_SIZE_MARKER_DOUBLEWORD -> DOUBLEWORD_SIZE_LOG2;
            default -> -1;
        };
        if (sizeLog2 < 0) {
            return null;
        }
        int qd = ((raw >>> IMM_QD_HIGH_BIT) & 1) << 3 | ((raw >>> IMM_QD_LOW_SHIFT) & IMM_QD_LOW_MASK);
        // Qm vem do campo normalmente rotulado Qn (comentário literal do arquivo — Armadilha 1).
        int qm = ((raw >>> IMM_QM_HIGH_BIT) & 1) << 3 | ((raw >>> IMM_QM_LOW_SHIFT) & IMM_QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        boolean load = ((raw >>> IMM_L_BIT) & 1) != 0;
        if (load && qd == qm) {
            return null; // UNPREDICTABLE real (trans_VLDRW_sg_imm/VLDRD_sg_imm: a->qd == a->qm)
        }
        boolean add = ((raw >>> IMM_A_BIT) & 1) != 0;
        boolean writeback = ((raw >>> IMM_W_BIT) & 1) != 0;
        int imm7 = raw & IMM_IMM7_MASK;
        int scaledOffset = imm7 << sizeLog2;
        int signedOffset = add ? scaledOffset : -scaledOffset;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveGatherScatterImmediate(qd, qm, signedOffset, sizeLog2, writeback, load, condition));
    }
}
