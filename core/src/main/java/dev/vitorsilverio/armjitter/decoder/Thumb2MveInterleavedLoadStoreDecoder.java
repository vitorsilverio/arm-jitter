package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VLD2`/`VLD4`/`VST2`/`VST4` (desentrelaçamento/entrelaçamento, perfil M, B16.5, MVE/Helium) —
/// as 4 linhas de `target/isa-decode/mve.decode` (confirmadas bit a bit via leitura direta do
/// arquivo real nesta rodada):
///
/// ```
/// &vldst_il qd rn size pat w
///
/// @vldst_il .... .... .. w:1 . rn:4 .... ... size:2 pat:2 ..... &vldst_il qd=%qd
///
/// VLD2             1111 1100 1 .. 1 .... ... 1 111 .. .. 00000 @vldst_il
/// VLD4             1111 1100 1 .. 1 .... ... 1 111 .. .. 00001 @vldst_il
/// VST2             1111 1100 1 .. 0 .... ... 1 111 .. .. 00000 @vldst_il
/// VST4             1111 1100 1 .. 0 .... ... 1 111 .. .. 00001 @vldst_il
/// ```
///
/// **Bit a bit confirmado por contagem de tokens (32 bits)**: `bits[31:24]=1111 1100` fixo;
/// `bit23=1` fixo; `bit22`=`Qd` alto ++ `bit21`=`w` (par `..` do `@format`); `bit20`=`L`
/// (`1`=load/`0`=store — literal por instrução, não nomeado no `&vldst_il`); `bits[19:16]=rn`;
/// `bits[15:13]`=`Qd` baixo; `bit12=1`/`bits[11:9]=111` fixos; `bits[8:7]=size`; `bits[6:5]=pat`;
/// `bits[4:0]`: `00000`=grupo `2` (`VLD2`/`VST2`), `00001`=grupo `4` (`VLD4`/`VST4`).
///
/// **`Qd` de saturação de banco** (`trans_VLD2`/`VLD4`/`VST2`/`VST4` reais,
/// `target/arm/tcg/translate-mve.c`): `VLD2`/`VST2` recusam `Qd > 6` (grupo `Qd..Qd+1`); `VLD4`/
/// `VST4` recusam `Qd > 4` (grupo `Qd..Qd+3`) — Armadilha 5 da task, checar `Qd + grupo - 1 <= 7`,
/// não só `Qd <= 7`. `size == 3` (doubleword) não existe em nenhuma forma (`fns[...][3] == NULL`
/// nas 4 tabelas reais). `VLD2`/`VST2` só aceitam `pat ∈ {0, 1}` (`fns[2]`/`fns[3]` são `NULL` nas
/// tabelas de `trans_VLD2`/`trans_VST2` reais); `VLD4`/`VST4` aceitam `pat ∈ {0, 1, 2, 3}`. `Rn ==
/// 15` sempre UNDEF; `Rn == 13` com `w` UNDEF (`do_vldst_il` real).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Usa o escape hatch de lifting
/// ({@link DecodedInstruction#lifted}), mesmo precedente de {@link Thumb2MveLoadStoreDecoder}.
public final class Thumb2MveInterleavedLoadStoreDecoder implements DecoderExtension {
    private static final int TOP8_SHIFT = 24;
    private static final int TOP8_MASK = 0xFF;
    private static final int TOP8_VALUE = 0b1111_1100;
    private static final int FIXED_BIT23 = 23;
    private static final int QD_HIGH_BIT = 22;
    private static final int W_BIT = 21;
    private static final int L_BIT = 20;
    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int ONE_LITERAL_BIT = 12;
    private static final int LOW3_SHIFT = 9;
    private static final int LOW3_MASK = 0x7;
    private static final int LOW3_VALUE = 0b111;
    private static final int SIZE_SHIFT = 7;
    private static final int SIZE_MASK = 0x3;
    private static final int PAT_SHIFT = 5;
    private static final int PAT_MASK = 0x3;
    private static final int GROUP_MARKER_MASK = 0x1F;
    private static final int GROUP_MARKER_2 = 0b00000;
    private static final int GROUP_MARKER_4 = 0b00001;

    private static final int DOUBLEWORD_SIZE = 3;
    private static final int GROUP_SIZE_2 = 2;
    private static final int GROUP_SIZE_4 = 4;
    private static final int GROUP_2_MAX_QD = 6;
    private static final int GROUP_4_MAX_QD = 4;
    private static final int PROGRAM_COUNTER = 15;
    private static final int STACK_POINTER = 13;

    private final ArmArchitecture architecture;

    public Thumb2MveInterleavedLoadStoreDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP8_SHIFT) & TOP8_MASK) != TOP8_VALUE || ((raw >>> FIXED_BIT23) & 1) == 0) {
            return null;
        }
        if (((raw >>> ONE_LITERAL_BIT) & 1) == 0
                || ((raw >>> LOW3_SHIFT) & LOW3_MASK) != LOW3_VALUE) {
            return null;
        }
        int groupMarker = raw & GROUP_MARKER_MASK;
        int groupSize = switch (groupMarker) {
            case GROUP_MARKER_2 -> GROUP_SIZE_2;
            case GROUP_MARKER_4 -> GROUP_SIZE_4;
            default -> -1;
        };
        if (groupSize < 0) {
            return null;
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == DOUBLEWORD_SIZE) {
            return null;
        }
        int pat = (raw >>> PAT_SHIFT) & PAT_MASK;
        if (groupSize == GROUP_SIZE_2 && pat > 1) {
            return null; // VLD2/VST2 só definem pat 0/1 (fns[2]/fns[3] == NULL reais)
        }
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        boolean writeback = ((raw >>> W_BIT) & 1) != 0;
        if (rn == PROGRAM_COUNTER || (rn == STACK_POINTER && writeback)) {
            return null; // do_vldst_il real: a->rn == 15 || (a->rn == 13 && a->w)
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int maxQd = groupSize == GROUP_SIZE_4 ? GROUP_4_MAX_QD : GROUP_2_MAX_QD;
        if (qd > maxQd) {
            return null; // Qd + grupo - 1 <= 7 (Armadilha 5)
        }
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveInterleavedLoadStore(qd, rn, groupSize, size, pat, load, writeback, condition));
    }
}
