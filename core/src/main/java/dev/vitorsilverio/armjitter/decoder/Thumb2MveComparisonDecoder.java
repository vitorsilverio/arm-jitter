package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.MveCompareCondition;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VCMPEQ`/`VCMPNE`/`VCMPGE`/`VCMPLT`/`VCMPGT`/`VCMPLE`/`VCMPCS`/`VCMPHI` e as formas `_fp`/
/// `_scalar`/`_fp_scalar` correspondentes (perfil M, B16.8, MVE/Helium, `target/isa-decode/mve.decode`,
/// seção "Comparisons" + 6 linhas que vivem fora dela, 34 encodings no total — as 45 que o épico
/// contava incluíam 14 da seção "2-operand FP", que são a B16.7, e 3 de `VPSEL`/`VPNOT`/`VPST`,
/// que são a B16.2):
///
/// ```
/// %mask_22_13      22:1 13:3
/// @vcmp        .... .... .. size:2 qn:3 . .... .... .... .... &vcmp qm=%qm mask=%mask_22_13
/// @vcmp_scalar .... .... .. size:2 qn:3 . .... .... .... rm:4 &vcmp_scalar mask=%mask_22_13
/// @vcmp_fp .... .... .... qn:3 . .... .... .... .... &vcmp qm=%qm size=%2op_fp_scalar_size mask=%mask_22_13
/// @vcmp_fp_scalar .... .... .... qn:3 . .... .... .... rm:4 &vcmp_scalar mask=%mask_22_13
/// ```
///
/// **Achado central: `VPT` = `VCMP` com `mask != 0`.** Não existe linha `VPT` no arquivo — o
/// comentário real (linhas 698-700) é explícito: "We expand out the conditions which are split
/// across encodings T1, T2, T3 and the fc bits. These include VPT, which is effectively 'VCMP
/// then VPST'. A plain VCMP has a mask field of zero." O `StandardIrBuilder` abre o `VPT`
/// (`IrOp.Vpst`) quando `mask != 0`, ver Javadoc de {@link IrOp.MveVectorCompare}.
///
/// **`Qn` tem 3 bits (`0`-`7`, sempre válido); `Qm` tem 4 bits** (`%qm`, precisa de
/// {@link VfpRegisters#isValidMveQuadRegister}) — comentário literal do arquivo real: "Vector
/// comparison; 4-bit Qm but 3-bit Qn".
///
/// **6 dos blocos `{}` desta seção contêm instruções de OUTRAS tasks** (B16.2: `VPSEL`/`VPNOT`/
/// `VPST`; B16.5: `VDDUP`/`VDWDUP`; B16.9, ainda não implementada: `VADD_scalar`/`VSUB_scalar`) —
/// resolvido por CONSTRUÇÃO, não por ordem de registro: as 6 formas inteiras (`@vcmp`/
/// `@vcmp_scalar`) desta classe recusam explicitamente `size == 3` (Armadilha da spec), que é
/// EXATAMENTE o valor de `size` que o arquivo real reserva para essas outras instruções — ver
/// `Thumb2MveIncrementDupDecoder` (`DOUBLEWORD_SIZE`, já comentado "B16.8, size=3 sempre") para o
/// precedente já existente na base. As formas `_fp`/`_fp_scalar` desta classe, ao contrário, FIXAM
/// `bits[21:20] = 11` como parte do próprio encoding (não é um "size" ali) — não há ambiguidade a
/// resolver, os valores de bit são literalmente distintos dos das formas inteiras.
///
/// **`@vcmp_fp_scalar` NÃO decodifica o bit 28** (comentário literal do arquivo real: "Bit 28 is a
/// 2op_fp_scalar_size bit, but we do not decode it in this format to avoid complicated
/// overlapping-instruction-groups") — cada uma das 6 linhas passa `size=1` (bloco "Comparisons",
/// bits[21:20] fixos em `11`) ou `size=2` (as 2 linhas do bloco dos `dup`, linhas 416/417, e as 4
/// do bloco "Scalar operations", linhas 499/500/505/506) fixo, nunca extraído do raw.
///
/// **`Rm ∈ {13, 15}` — achado medido contra o QEMU real, DIVERGE da leitura inicial da spec**:
/// `do_vcmp_scalar` (`target/arm/tcg/translate-mve.c`) só recusa `a->rm == 13`
/// (`UNPREDICTABLE`); `a->rm == 15` é uma forma VÁLIDA ("Encoding Rm=0b1111 means 'constant
/// zero'"), resolvida no EXECUTOR (`IrSystemExecutor#executeMveVectorCompareScalar`), não aqui.
///
/// Gate: {@link ArmFeature#MVE_INTEGER} para as inteiras, {@link ArmFeature#MVE_FLOAT} para as
/// `_fp`/`_fp_scalar` (gate POR LINHA, mesma Armadilha 1 da B16.7). Usa o escape hatch de lifting
/// ({@link IrOp.MveVectorCompare}/{@link IrOp.MveVectorCompareScalar}). Registrado ANTES de
/// {@link Thumb2NocpDecoder} em {@code ArmArchitecture#ARMV8_1M_MVE}.
public final class Thumb2MveComparisonDecoder implements DecoderExtension {
    // ── Campos comuns (`%mask_22_13`, `%qm`, `qn:3` inline, `size:2` inline, `rm:4` inline) ──────
    private static final int MASK_HIGH_BIT_SHIFT = 22;
    private static final int MASK_LOW_FIELD_SHIFT = 13;
    private static final int QM_HIGH_BIT_SHIFT = 5;
    private static final int QM_LOW_FIELD_SHIFT = 1;
    private static final int QN_SHIFT = 17;
    private static final int QN_MASK = 0x7;
    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int RM_SHIFT = 0;
    private static final int RM_MASK = 0xF;
    private static final int LOW_FIELD_MASK = 0x7;
    private static final int RESERVED_SIZE = 3;
    private static final int UNPREDICTABLE_RM = 13;

    // ── `%2op_fp_scalar_size`/`%2op_fp_size` (bit 28): `1` = binary16, `2` = binary32 ───────────
    private static final int FP_SIZE_BIT = 28;
    private static final int ESZ_BINARY16 = 1;
    private static final int ESZ_BINARY32 = 2;

    // ── Grupo `{702,703}`..`{727,728}` (vetor×vetor, `@vcmp_fp` antes de `@vcmp` — a FP fixa
    // `bits[21:20]=11`, achado medido: essa é exatamente a codificação "size==3" que a forma
    // inteira do MESMO grupo recusaria de qualquer forma, então a ORDEM de checagem aqui só
    // importa por clareza, nunca por correção). Máscara comum aos 12 (bits fixos fora de
    // size/qn/qm/mask, medida bit a bit contra o arquivo real). ──────────────────────────────────
    private static final int VECTOR_FP_MASK = 0xEFB11FD1;
    private static final int VECTOR_INT_MASK = 0xFF811FD1;

    private static final int VALUE_VCMPEQ_FP = 0xEE310F00;
    private static final int VALUE_VCMPEQ = 0xFE010F00;
    private static final int VALUE_VCMPNE_FP = 0xEE310F80;
    private static final int VALUE_VCMPNE = 0xFE010F80;
    private static final int VALUE_VCMPGE_FP = 0xEE311F00;
    private static final int VALUE_VCMPGE = 0xFE011F00;
    private static final int VALUE_VCMPLT_FP = 0xEE311F80;
    private static final int VALUE_VCMPLT = 0xFE011F80;
    private static final int VALUE_VCMPGT_FP = 0xEE311F01;
    private static final int VALUE_VCMPGT = 0xFE011F01;
    private static final int VALUE_VCMPLE_FP = 0xEE311F81;
    private static final int VALUE_VCMPLE = 0xFE011F81;
    private static final int VALUE_VCMPCS = 0xFE010F01;
    private static final int VALUE_VCMPHI = 0xFE010F81;

    // ── Grupo `{740,741}`..`{764,765}` + as duas standalone `768`/`769` (vetor×escalar). Máscara
    // comum medida bit a bit. FP fixa `size=1` literal (nunca o bit 28). ──────────────────────────
    private static final int SCALAR_FP_MASK = 0xFFB11FF0;
    private static final int SCALAR_INT_MASK = 0xFF811FF0;

    private static final int VALUE_VCMPEQ_FP_SCALAR = 0xFE310F40;
    private static final int VALUE_VCMPEQ_SCALAR = 0xFE010F40;
    private static final int VALUE_VCMPNE_FP_SCALAR = 0xFE310FC0;
    private static final int VALUE_VCMPNE_SCALAR = 0xFE010FC0;
    private static final int VALUE_VCMPGT_FP_SCALAR = 0xFE311F60;
    private static final int VALUE_VCMPGT_SCALAR = 0xFE011F60;
    private static final int VALUE_VCMPLE_FP_SCALAR = 0xFE311FE0;
    private static final int VALUE_VCMPLE_SCALAR = 0xFE011FE0;
    private static final int VALUE_VCMPGE_FP_SCALAR = 0xFE311F40;
    private static final int VALUE_VCMPGE_SCALAR = 0xFE011F40;
    private static final int VALUE_VCMPLT_FP_SCALAR = 0xFE311FC0;
    private static final int VALUE_VCMPLT_SCALAR = 0xFE011FC0;
    private static final int VALUE_VCMPCS_SCALAR = 0xFE010F60;
    private static final int VALUE_VCMPHI_SCALAR = 0xFE010FE0;

    // ── Linhas 416/417 (bloco dos `dup`, `size=2` literal) e 499/500/505/506 ("Scalar operations",
    // `size=2` literal) — MESMA máscara de campos que o grupo escalar acima (`SCALAR_FP_MASK`),
    // valores distintos por causa do nibble alto `1110` (em vez de `1111`). ──────────────────────
    private static final int VALUE_VCMPGT_FP_SCALAR_SIZE2 = 0xEE311F60;
    private static final int VALUE_VCMPLE_FP_SCALAR_SIZE2 = 0xEE311FE0;
    private static final int VALUE_VCMPEQ_FP_SCALAR_SIZE2 = 0xEE310F40;
    private static final int VALUE_VCMPNE_FP_SCALAR_SIZE2 = 0xEE310FC0;
    private static final int VALUE_VCMPLT_FP_SCALAR_SIZE2 = 0xEE311FC0;
    private static final int VALUE_VCMPGE_FP_SCALAR_SIZE2 = 0xEE311F40;

    private final ArmArchitecture architecture;

    public Thumb2MveComparisonDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        DecodedInstruction vectorFp = tryDecodeVectorFp(raw, address, condition);
        if (vectorFp != null) {
            return vectorFp;
        }
        DecodedInstruction vectorInt = tryDecodeVectorInt(raw, address, condition);
        if (vectorInt != null) {
            return vectorInt;
        }
        DecodedInstruction scalarFpSize1 = tryDecodeScalarFpFixedSize(raw, address, condition, SCALAR_FP_MASK,
                ESZ_BINARY16);
        if (scalarFpSize1 != null) {
            return scalarFpSize1;
        }
        DecodedInstruction scalarInt = tryDecodeScalarInt(raw, address, condition);
        if (scalarInt != null) {
            return scalarInt;
        }
        return tryDecodeScalarFpFixedSize(raw, address, condition, SCALAR_FP_MASK, ESZ_BINARY32);
    }

    /// Grupo vetor×vetor `_fp` (6 pares) — `@vcmp_fp`, `size` do bit 28 (`%2op_fp_scalar_size`).
    private DecodedInstruction tryDecodeVectorFp(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int matched = raw & VECTOR_FP_MASK;
        MveCompareCondition compareCondition = switch (matched) {
            case VALUE_VCMPEQ_FP -> MveCompareCondition.EQ;
            case VALUE_VCMPNE_FP -> MveCompareCondition.NE;
            case VALUE_VCMPGE_FP -> MveCompareCondition.GE;
            case VALUE_VCMPLT_FP -> MveCompareCondition.LT;
            case VALUE_VCMPGT_FP -> MveCompareCondition.GT;
            case VALUE_VCMPLE_FP -> MveCompareCondition.LE;
            default -> null;
        };
        if (compareCondition == null) {
            return null;
        }
        int qn = (raw >>> QN_SHIFT) & QN_MASK;
        int qm = quadField(raw);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int esz = ((raw >>> FP_SIZE_BIT) & 1) != 0 ? ESZ_BINARY16 : ESZ_BINARY32;
        int mask = maskField(raw);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorCompare(compareCondition, true, esz, qn, qm, mask, condition));
    }

    /// Grupo vetor×vetor inteiro (6 pares + `VCMPCS`/`VCMPHI`) — `@vcmp`, `size` extraído
    /// (`size == 3` recusado, ver Javadoc da classe).
    private DecodedInstruction tryDecodeVectorInt(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int matched = raw & VECTOR_INT_MASK;
        MveCompareCondition compareCondition = switch (matched) {
            case VALUE_VCMPEQ -> MveCompareCondition.EQ;
            case VALUE_VCMPNE -> MveCompareCondition.NE;
            case VALUE_VCMPGE -> MveCompareCondition.GE;
            case VALUE_VCMPLT -> MveCompareCondition.LT;
            case VALUE_VCMPGT -> MveCompareCondition.GT;
            case VALUE_VCMPLE -> MveCompareCondition.LE;
            case VALUE_VCMPCS -> MveCompareCondition.CS;
            case VALUE_VCMPHI -> MveCompareCondition.HI;
            default -> null;
        };
        if (compareCondition == null) {
            return null;
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == RESERVED_SIZE) {
            return null;
        }
        int qn = (raw >>> QN_SHIFT) & QN_MASK;
        int qm = quadField(raw);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int mask = maskField(raw);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorCompare(compareCondition, false, size, qn, qm, mask, condition));
    }

    /// Grupo vetor×escalar inteiro (6 pares + `VCMPCS_scalar`/`VCMPHI_scalar`) — `@vcmp_scalar`,
    /// `size` extraído (`size == 3` recusado).
    private DecodedInstruction tryDecodeScalarInt(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int matched = raw & SCALAR_INT_MASK;
        MveCompareCondition compareCondition = switch (matched) {
            case VALUE_VCMPEQ_SCALAR -> MveCompareCondition.EQ;
            case VALUE_VCMPNE_SCALAR -> MveCompareCondition.NE;
            case VALUE_VCMPGE_SCALAR -> MveCompareCondition.GE;
            case VALUE_VCMPLT_SCALAR -> MveCompareCondition.LT;
            case VALUE_VCMPGT_SCALAR -> MveCompareCondition.GT;
            case VALUE_VCMPLE_SCALAR -> MveCompareCondition.LE;
            case VALUE_VCMPCS_SCALAR -> MveCompareCondition.CS;
            case VALUE_VCMPHI_SCALAR -> MveCompareCondition.HI;
            default -> null;
        };
        if (compareCondition == null) {
            return null;
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == RESERVED_SIZE) {
            return null;
        }
        int rm = (raw >>> RM_SHIFT) & RM_MASK;
        if (rm == UNPREDICTABLE_RM) {
            return null;
        }
        int qn = (raw >>> QN_SHIFT) & QN_MASK;
        int mask = maskField(raw);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorCompareScalar(compareCondition, false, size, qn, rm, mask, condition));
    }

    /// Grupo vetor×escalar `_fp` (6 formas, `@vcmp_fp_scalar`) — bit 28 NÃO decodificado, `esz`
    /// literal por chamador (`1` para o bloco "Comparisons"/768-769; `2` para as linhas 416/417 e
    /// 499/500/505/506, ver Javadoc da classe).
    private DecodedInstruction tryDecodeScalarFpFixedSize(int raw, int address, Condition condition, int fieldMask,
            int esz) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int matched = raw & fieldMask;
        MveCompareCondition compareCondition = esz == ESZ_BINARY16
                ? switch (matched) {
                    case VALUE_VCMPEQ_FP_SCALAR -> MveCompareCondition.EQ;
                    case VALUE_VCMPNE_FP_SCALAR -> MveCompareCondition.NE;
                    case VALUE_VCMPGE_FP_SCALAR -> MveCompareCondition.GE;
                    case VALUE_VCMPLT_FP_SCALAR -> MveCompareCondition.LT;
                    case VALUE_VCMPGT_FP_SCALAR -> MveCompareCondition.GT;
                    case VALUE_VCMPLE_FP_SCALAR -> MveCompareCondition.LE;
                    default -> null;
                }
                : switch (matched) {
                    case VALUE_VCMPEQ_FP_SCALAR_SIZE2 -> MveCompareCondition.EQ;
                    case VALUE_VCMPNE_FP_SCALAR_SIZE2 -> MveCompareCondition.NE;
                    case VALUE_VCMPGE_FP_SCALAR_SIZE2 -> MveCompareCondition.GE;
                    case VALUE_VCMPLT_FP_SCALAR_SIZE2 -> MveCompareCondition.LT;
                    case VALUE_VCMPGT_FP_SCALAR_SIZE2 -> MveCompareCondition.GT;
                    case VALUE_VCMPLE_FP_SCALAR_SIZE2 -> MveCompareCondition.LE;
                    default -> null;
                };
        if (compareCondition == null) {
            return null;
        }
        int rm = (raw >>> RM_SHIFT) & RM_MASK;
        if (rm == UNPREDICTABLE_RM) {
            return null;
        }
        int qn = (raw >>> QN_SHIFT) & QN_MASK;
        int mask = maskField(raw);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorCompareScalar(compareCondition, true, esz, qn, rm, mask, condition));
    }

    /// `%qm 5:1 1:3` (bit alto concatenado com 3 bits baixos) — MESMA convenção de
    /// {@link Thumb2MvePredicationDecoder#quadField}.
    private static int quadField(int raw) {
        int high = (raw >>> QM_HIGH_BIT_SHIFT) & 1;
        int low = (raw >>> QM_LOW_FIELD_SHIFT) & LOW_FIELD_MASK;
        return (high << 3) | low;
    }

    /// `%mask_22_13 22:1 13:3`.
    private static int maskField(int raw) {
        int high = (raw >>> MASK_HIGH_BIT_SHIFT) & 1;
        int low = (raw >>> MASK_LOW_FIELD_SHIFT) & LOW_FIELD_MASK;
        return (high << 3) | low;
    }
}
