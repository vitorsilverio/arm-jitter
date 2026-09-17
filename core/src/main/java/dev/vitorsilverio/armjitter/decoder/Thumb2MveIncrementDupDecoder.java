package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VIDUP`/`VDDUP`/`VIWDUP`/`VDWDUP` (geradores de vetor incremental/decremental, perfil M, B16.5,
/// MVE/Helium) — as 4 linhas de `target/isa-decode/mve.decode` (confirmadas bit a bit via leitura
/// direta do arquivo real nesta rodada):
///
/// ```
/// &vidup qd rn size imm
/// &viwdup qd rn rm size imm
///
/// %imm_vidup 7:1 0:1 !function=vidup_imm   # 1 << (immh:imml)
/// %vidup_rm 1:3 !function=times_2_plus_1   # Rm = bits[3:1]*2 + 1 (sempre ÍMPAR)
/// %vidup_rn 17:3 !function=times_2         # Rn = bits[19:17]*2 (sempre PAR)
///
/// @vidup           .... .... . . size:2 .... .... .... .... .... qd=%qd imm=%imm_vidup rn=%vidup_rn &vidup
/// @viwdup          .... .... . . size:2 .... .... .... .... .... qd=%qd imm=%imm_vidup rm=%vidup_rm rn=%vidup_rn &viwdup
/// {
///   VIDUP          1110 1110 0 . .. ... 1 ... 0 1111 . 110 111 . @vidup
///   VIWDUP         1110 1110 0 . .. ... 1 ... 0 1111 . 110 ... . @viwdup
/// }
/// {
///   VCMPGT_fp_scalar 1110 1110 0 . 11 ... 1 ... 1 1111  0110 ....  # B16.8, size=3 sempre
///   VCMPLE_fp_scalar 1110 1110 0 . 11 ... 1 ... 1 1111  1110 ....  # B16.8, size=3 sempre
///   VDDUP            1110 1110 0 . .. ... 1 ... 1 1111 . 110 111 . @vidup
///   VDWDUP           1110 1110 0 . .. ... 1 ... 1 1111 . 110 ... . @viwdup
/// }
/// ```
///
/// **Bit a bit confirmado por contagem de tokens (32 bits)**: `bits[31:24]=1110 1110`/`bit23=0`
/// fixos; `bit22`=`Qd` alto; `bits[21:20]=size` (`3` é "outro encoding" — `VCMPGT_fp_scalar`/
/// `VCMPLE_fp_scalar`, B16.8, Armadilha 4 — excluídos automaticamente por esta task nunca aceitar
/// `size==3`, nenhuma máscara extra necessária); `bits[19:17]`=`Rn` cru (`Rn = cru * 2`, SEMPRE
/// PAR); `bit16=1` fixo; `bits[15:13]`=`Qd` baixo; `bit12`: `0`=`VIDUP`/`VIWDUP`, `1`=`VDDUP`/
/// `VDWDUP` (ÚNICA diferença entre as duas metades do par); `bits[11:8]=1111`/`bits[6:4]=110`
/// fixos; `bit7`=`imm` alto; `bits[3:1]`: literal `111` seleciona `VIDUP`/`VDDUP` (sem `Rm`,
/// decodetree prioriza o padrão mais específico), qualquer outro valor de 3 bits é `Rm` cru
/// (`Rm = cru * 2 + 1`, SEMPRE ÍMPAR) de `VIWDUP`/`VDWDUP`; `bit0`=`imm` baixo.
///
/// **`imm = 1 << (bit7 ++ bit0)`** (`vidup_imm` real, `target/arm/tcg/translate-mve.c`) — passo
/// sempre `1`/`2`/`4`/`8`. `VDDUP` NEGA o `imm` no `trans_VDDUP` real (`a->imm = -a->imm`) antes de
/// chamar o mesmo helper de `VIDUP` — replicado aqui no decode (campo `imm` já com sinal).
/// `VIWDUP`/`VDWDUP` NÃO negam (a direção vem do campo `decrement` do `IrOp`, não do sinal —
/// `do_add_wrap`/`do_sub_wrap` reais são funções distintas, não uma inversão de sinal).
///
/// `Rm ∈ {13, 15}` é UNPREDICTABLE (`do_viwdup` real: `a->rm == 13 || a->rm == 15`) — Armadilha 2
/// da task: `%vidup_rm` só produz valores ÍMPARES (`1,3,5,7,9,11,13,15`), então `13`/`15` SÃO
/// alcançáveis e precisam de recusa explícita (ao contrário de `Rn`, sempre PAR, que nunca alcança
/// `13`/`15`). `size == 3` (doubleword) não existe em nenhuma forma (`fns[3] == NULL` real).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Não usa o escape hatch de lifting — os campos cabem nos
/// `IrOp` novos ({@link IrOp.MveIncrementDup}/{@link IrOp.MveWrappingIncrementDup}), decodificados
/// diretamente para eles via {@link DecodedInstruction#lifted} do mesmo jeito, por simplicidade de
/// não precisar de um `InstructionKind` novo (mesmo precedente das outras tasks da escada B16.5).
public final class Thumb2MveIncrementDupDecoder implements DecoderExtension {
    private static final int TOP8_SHIFT = 24;
    private static final int TOP8_MASK = 0xFF;
    private static final int TOP8_VALUE = 0b1110_1110;
    private static final int FIXED_BIT23 = 23;
    private static final int QD_HIGH_BIT = 22;
    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int RN_RAW_SHIFT = 17;
    private static final int RN_RAW_MASK = 0x7;
    private static final int ONE_LITERAL_BIT = 16;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int DECREMENT_BIT = 12;
    private static final int MID4_SHIFT = 8;
    private static final int MID4_MASK = 0xF;
    private static final int MID4_VALUE = 0b1111;
    private static final int IMM_HIGH_BIT = 7;
    private static final int LOW3_SHIFT = 4;
    private static final int LOW3_MASK = 0x7;
    private static final int LOW3_VALUE = 0b110;
    private static final int RM_RAW_SHIFT = 1;
    private static final int RM_RAW_MASK = 0x7;
    private static final int RM_RAW_NO_WRAP = 0b111;
    private static final int IMM_LOW_BIT = 0;

    private static final int DOUBLEWORD_SIZE = 3;
    private static final int STACK_POINTER = 13;
    private static final int PROGRAM_COUNTER = 15;

    private final ArmArchitecture architecture;

    public Thumb2MveIncrementDupDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP8_SHIFT) & TOP8_MASK) != TOP8_VALUE || ((raw >>> FIXED_BIT23) & 1) != 0) {
            return null;
        }
        if (((raw >>> ONE_LITERAL_BIT) & 1) == 0
                || ((raw >>> MID4_SHIFT) & MID4_MASK) != MID4_VALUE
                || ((raw >>> LOW3_SHIFT) & LOW3_MASK) != LOW3_VALUE) {
            return null;
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == DOUBLEWORD_SIZE) {
            return null; // "outro encoding" real (VCMPGT_fp_scalar/VCMPLE_fp_scalar, B16.8)
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int rn = ((raw >>> RN_RAW_SHIFT) & RN_RAW_MASK) * 2;
        boolean decrement = ((raw >>> DECREMENT_BIT) & 1) != 0;
        int rawImm = ((raw >>> IMM_HIGH_BIT) & 1) << 1 | ((raw >>> IMM_LOW_BIT) & 1);
        int imm = 1 << rawImm;

        int rmRaw = (raw >>> RM_RAW_SHIFT) & RM_RAW_MASK;
        if (rmRaw == RM_RAW_NO_WRAP) {
            int signedImm = decrement ? -imm : imm;
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveIncrementDup(qd, rn, size, signedImm, condition));
        }
        int rm = rmRaw * 2 + 1;
        if (rm == STACK_POINTER || rm == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE real (do_viwdup: a->rm == 13 || a->rm == 15)
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveWrappingIncrementDup(qd, rn, rm, size, imm, decrement, condition));
    }
}
