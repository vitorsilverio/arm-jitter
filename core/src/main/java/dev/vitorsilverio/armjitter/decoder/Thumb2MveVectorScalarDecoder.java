package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Operações escalares (vetor × GPR broadcast, perfil M, B16.9, MVE/Helium, `target/isa-decode/
/// mve.decode`, seção "Scalar operations", linhas 496-573, 35 encodings) — TODAS as formas
/// `@2scalar`/`@2scalar_nosz`/`@shl_scalar`/`@2op_fp_scalar` desta seção, EXCETO as 4
/// `VCMP*_fp_scalar` (linhas 499/500/505/506, que pertencem à {@link Thumb2MveComparisonDecoder},
/// B16.8):
///
/// ```
/// { VCMPEQ_fp_scalar (B16.8) / VCMPNE_fp_scalar (B16.8) / VADD_scalar  1110 1110 0 . .. ... 1 ... 0 1111 . 100 .... @2scalar }
/// { VCMPLT_fp_scalar (B16.8) / VCMPGE_fp_scalar (B16.8) / VSUB_scalar  1110 1110 0 . .. ... 1 ... 1 1111 . 100 .... @2scalar }
/// { VSHL_S_scalar / VRSHL_S_scalar / VQSHL_S_scalar / VQRSHL_S_scalar / VMUL_scalar }
/// { VSHL_U_scalar / VRSHL_U_scalar / VQSHL_U_scalar / VQRSHL_U_scalar / VBRSR }
/// { VADD_fp_scalar / VHADD_S_scalar / VHADD_U_scalar }
/// { VSUB_fp_scalar / VHSUB_S_scalar / VHSUB_U_scalar }
/// { VQADD_S_scalar / VQADD_U_scalar / VQDMULLB_scalar (@2scalar_nosz size=%size_28) }
/// { VQSUB_S_scalar / VQSUB_U_scalar / VQDMULLT_scalar (@2scalar_nosz size=%size_28) }
/// { VMUL_fp_scalar / VQDMULH_scalar / VQRDMULH_scalar }
/// { VFMA_scalar  / VMLA   111 - 1110 0 . .. ... 1 ... 0 1110 . 100 .... @2scalar }
/// { VFMAS_scalar / VMLAS  111 - 1110 0 . .. ... 1 ... 1 1110 . 100 .... @2scalar }
/// VQRDMLAH / VQRDMLASH / VQDMLAH / VQDMLASH
/// ```
///
/// **Achado central (medido bit a bit com um derivador de máscara/valor, mesma disciplina da
/// B16.8)**: `bits[27:24]` é `1110` FIXO em TODA a seção (diferente de {@link
/// Thumb2MveVector2opDecoder}, onde variava entre `1110`/`1111`) — a discriminação inteira acontece
/// via `bit28` (`U`, quando existe), `bit16`, `bits[11:8]`, `bit12`, `bits[6:4]` e `bit7`
/// (normalmente `Qn` alto, MAS repropositado como o bit "saturante" dentro do bloco `@shl_scalar`,
/// onde não existe campo `Qn`).
///
/// **Colisão real, resolvida por especificidade (mesma regra de prioridade da B16.6/B16.7 para
/// `VMULLP`/`VCVTB_SH`)**: dentro do slot `nib2=1110,b16=1,b12=1,nib3=110`, `bits[21:20]==0b11`
/// FIXO seleciona `@shl_scalar` (`VSHL_*_scalar`/`VRSHL_*_scalar`/`VQSHL_*_scalar`/
/// `VQRSHL_*_scalar`, tamanho real em `bits[19:18]`), e `bits[21:20]` LIVRE (`0`-`2`) seleciona
/// `VMUL_scalar`(`U=0`)/`VBRSR`(`U=1`). O mesmo padrão "`bits[21:20]==0b11` fixo = forma mais
/// específica" resolve `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar`/`VFMA_scalar`/
/// `VFMAS_scalar`/`VQDMULLB_scalar`/`VQDMULLT_scalar` (todos com `bits[21:20]` fixo `11`) contra os
/// vizinhos de `size` livre (`VHADD_S/U`, `VHSUB_S/U`, `VQADD_S/U`, `VQSUB_S/U`, `VQDMULH`/
/// `VQRDMULH`, `VMLA`/`VMLAS`) no MESMO `{}`.
///
/// **`VMLA`/`VMLAS` são as ÚNICAS linhas com `111 -` (bit 28 don't-care)**: o decoder não lê `U`
/// para produzi-las — os dois valores de `bit28` decodificam a MESMA instrução.
///
/// **`@shl_scalar` não tem `Qn`** (`&shl_scalar qda rm size`, só 3 campos): `Qd`={@code qda},
/// `Qn`={@code qda} (mesmo valor) na {@link IrOp.MveVectorScalar} resultante — `Rm` é a CONTAGEM de
/// deslocamento, não um valor replicado por lane (mesmo núcleo funciona porque só o byte baixo de
/// `Rm` importa nessas 8 formas).
///
/// **`VQDMULLB_scalar`/`VQDMULLT_scalar` usam `esz` = `bit28` DIRETO (via `%size_28`, que aqui
/// COINCIDE numericamente com o `esz` real da fonte — diferente da Armadilha 2/7 da B16.6/B16.7,
/// onde `%size_28` NÃO coincidia)**: `bit28=0` → halfword (`esz=1`), `bit28=1` → word (`esz=2`)
/// — confirmado contra `fns[a->size]` real (`target/arm/tcg/translate-mve.c`, `trans_VQDMULLB_scalar`),
/// onde `a->size` (`%size_28`) indexa DIRETO um array `[NULL, h-variant, w-variant, NULL]`. `Qd ==
/// Qn` com `esz` word é UNPREDICTABLE ("choose to undef" no QEMU real), recusado aqui.
///
/// `Rm ∈ {13,15}` é UNPREDICTABLE em TODAS as 35 formas (`do_2op_scalar`/`do_2shift_scalar` reais
/// recusam os dois — diferente de {@link Thumb2MveComparisonDecoder}, onde `Rm==15` é "constante
/// zero" válida).
///
/// Gate por LINHA: {@link ArmFeature#MVE_FLOAT} para `VADD_fp_scalar`/`VSUB_fp_scalar`/
/// `VMUL_fp_scalar`/`VFMA_scalar`/`VFMAS_scalar`; {@link ArmFeature#MVE_INTEGER} para as 30 restantes.
/// Usa o escape hatch de lifting (mesmo precedente de {@link Thumb2MveVector2opDecoder}).
public final class Thumb2MveVectorScalarDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VALUE = 0b1110;
    private static final int BIT23 = 23;

    private static final int U_BIT = 28;
    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QN_HIGH_BIT = 7;
    private static final int QN_LOW_SHIFT = 17;
    private static final int QN_LOW_MASK = 0x7;

    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int SIZE_INVALID = 3;
    private static final int SHL_SIZE_SHIFT = 18;
    private static final int SHL_SIZE_MASK = 0x3;
    private static final int ROUND_BIT = 17;
    private static final int SAT_BIT = 7;

    private static final int BIT16 = 16;
    private static final int BIT12 = 12;
    private static final int OPCODE_NIBBLE_SHIFT = 8;
    private static final int OPCODE_NIBBLE_MASK = 0xF;
    private static final int OPCODE_NIBBLE_MUL = 0b1110;
    private static final int OPCODE_NIBBLE_ADD = 0b1111;
    private static final int NIBBLE3_SHIFT = 4;
    private static final int NIBBLE3_MASK = 0x7;
    private static final int NIBBLE3_ACC = 0b100;
    private static final int NIBBLE3_SAT = 0b110;

    private static final int RM_MASK = 0xF;
    private static final int RM_SP = 13;
    private static final int RM_PC = 15;

    private final ArmArchitecture architecture;

    public Thumb2MveVectorScalarDecoder(ArmArchitecture architecture) {
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
        if (((raw >>> TOP24_SHIFT) & TOP24_MASK) != TOP24_VALUE) {
            return null;
        }
        if (((raw >>> BIT23) & 1) != 0) {
            return null;
        }
        int rm = raw & RM_MASK;
        if (rm == RM_SP || rm == RM_PC) {
            return null; // UNPREDICTABLE em TODAS as 35 formas (do_2op_scalar/do_2shift_scalar reais).
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        boolean u = ((raw >>> U_BIT) & 1) != 0;
        boolean bit16 = ((raw >>> BIT16) & 1) != 0;
        boolean bit12 = ((raw >>> BIT12) & 1) != 0;
        int nibble = (raw >>> OPCODE_NIBBLE_SHIFT) & OPCODE_NIBBLE_MASK;
        int nibble3 = (raw >>> NIBBLE3_SHIFT) & NIBBLE3_MASK;

        if (nibble == OPCODE_NIBBLE_MUL) {
            return bit16 ? decodeMulSlot(raw, address, condition, qd, u, bit12, nibble3, rm)
                    : decodeDoublingAccumulateSlot(raw, address, condition, qd, u, bit12, nibble3, rm);
        }
        if (nibble == OPCODE_NIBBLE_ADD) {
            return bit16 ? decodeAddSubSlot(raw, address, condition, qd, bit12, nibble3, rm)
                    : decodeHalvingSlot(raw, address, condition, qd, u, bit12, nibble3, rm);
        }
        return null;
    }

    /// `bits[11:8]=1110`, `bit16=1`: `VMUL_scalar`/`VBRSR` (`bits[21:20]` livre) OU o bloco
    /// `@shl_scalar` inteiro (`bits[21:20]==0b11` fixo, mais específico — checado primeiro) quando
    /// `nibble3==110`; `VMLA`/`VFMA_scalar`/`VMLAS`/`VFMAS_scalar` quando `nibble3==100`; `VQDMULH`/
    /// `VQRDMULH`/`VMUL_fp_scalar` quando `nibble3==110` e `bit12=0` (mas isso já é coberto pelo
    /// primeiro ramo — ver a árvore completa abaixo).
    private DecodedInstruction decodeMulSlot(int raw, int address, Condition condition, int qd, boolean u,
            boolean bit12, int nibble3, int rm) {
        int size2120 = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (nibble3 == NIBBLE3_SAT) {
            if (bit12) {
                // bits[6:4]=110, bit12=1: @shl_scalar (size2120==3, mais específico) OU
                // VMUL_scalar(U=0)/VBRSR(U=1) (size2120 0-2).
                if (size2120 == SIZE_INVALID) {
                    return decodeShiftScalar(raw, address, condition, qd, u, rm);
                }
                if (!u) {
                    int qn = extractQn(raw);
                    return lifted(address, raw, condition,
                            new IrOp.MveVectorScalar(AdvSimdThreeSameOp.MUL, size2120, qd, qn, rm, condition));
                }
                return decodeVbrsr(raw, address, condition, qd, size2120, rm);
            }
            // bits[6:4]=110, bit12=0: VMUL_fp_scalar (size2120==3) ou VQDMULH/VQRDMULH (size livre).
            if (size2120 == SIZE_INVALID) {
                if (!architecture.has(ArmFeature.MVE_FLOAT)) {
                    return null;
                }
                int qn = extractQn(raw);
                int esz = u ? 1 : 2;
                return lifted(address, raw, condition,
                        new IrOp.MveVectorFpScalar(AdvSimdFpThreeSameOp.MUL, esz, qd, qn, rm, condition));
            }
            AdvSimdThreeSameOp op = u ? AdvSimdThreeSameOp.SQRDMULH : AdvSimdThreeSameOp.SQDMULH;
            int qn = extractQn(raw);
            return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size2120, qd, qn, rm, condition));
        }
        if (nibble3 == NIBBLE3_ACC) {
            // VMLA/VFMA_scalar (bit12=0) ou VMLAS/VFMAS_scalar (bit12=1) — "111 -": bit28 NÃO
            // participa da decisão para a forma inteira.
            if (size2120 == SIZE_INVALID) {
                if (!architecture.has(ArmFeature.MVE_FLOAT)) {
                    return null;
                }
                int qn = extractQn(raw);
                int esz = u ? 1 : 2;
                return lifted(address, raw, condition,
                        new IrOp.MveVectorFpScalarFma(bit12, esz, qd, qn, rm, condition));
            }
            int qn = extractQn(raw);
            AdvSimdThreeSameOp op = AdvSimdThreeSameOp.MLA;
            if (bit12) {
                return lifted(address, raw, condition, new IrOp.MveVectorScalarSpecial(
                        IrOp.MveVectorScalarSpecial.SpecialOp.VMLAS, size2120, qd, qn, rm, condition));
            }
            return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size2120, qd, qn, rm, condition));
        }
        return null;
    }

    /// `bits[11:8]=1110`, `bit16=0`, `size==%size_28`? Não — este ramo é `VQRDMLAH`/`VQRDMLASH`
    /// (`nibble3=100`) e `VQDMLAH`/`VQDMLASH` (`nibble3=110`), todas com `U` FIXO em `0` (não há
    /// forma "unsigned" real).
    private DecodedInstruction decodeDoublingAccumulateSlot(int raw, int address, Condition condition, int qd,
            boolean u, boolean bit12, int nibble3, int rm) {
        if (u) {
            return null; // Nenhuma das 4 formas tem U=1 real.
        }
        if (nibble3 != NIBBLE3_ACC && nibble3 != NIBBLE3_SAT) {
            return null;
        }
        int size2120 = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size2120 == SIZE_INVALID) {
            return null;
        }
        int qn = extractQn(raw);
        boolean swap = bit12;
        boolean rounding = nibble3 == NIBBLE3_ACC;
        IrOp.MveVectorScalarSpecial.SpecialOp op = rounding
                ? (swap ? IrOp.MveVectorScalarSpecial.SpecialOp.VQRDMLASH
                        : IrOp.MveVectorScalarSpecial.SpecialOp.VQRDMLAH)
                : (swap ? IrOp.MveVectorScalarSpecial.SpecialOp.VQDMLASH
                        : IrOp.MveVectorScalarSpecial.SpecialOp.VQDMLAH);
        return lifted(address, raw, condition, new IrOp.MveVectorScalarSpecial(op, size2120, qd, qn, rm, condition));
    }

    /// `bits[11:8]=1111`, `bit16=1`: `VADD_scalar`(`bit12=0`)/`VSUB_scalar`(`bit12=1`) — `U` FIXO em
    /// `0` (não há forma "unsigned" real; qualquer `U=1` aqui é espaço não alocado).
    private DecodedInstruction decodeAddSubSlot(int raw, int address, Condition condition, int qd, boolean bit12,
            int nibble3, int rm) {
        if (((raw >>> U_BIT) & 1) != 0) {
            return null;
        }
        if (nibble3 != NIBBLE3_ACC) {
            return null;
        }
        int size2120 = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size2120 == SIZE_INVALID) {
            return null;
        }
        int qn = extractQn(raw);
        AdvSimdThreeSameOp op = bit12 ? AdvSimdThreeSameOp.SUB : AdvSimdThreeSameOp.ADD;
        return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size2120, qd, qn, rm, condition));
    }

    /// `bits[11:8]=1111`, `bit16=0`: `bits[21:20]==0b11` (mais específico) seleciona a forma FP
    /// (`VADD_fp_scalar`/`VSUB_fp_scalar`, `MVE_FLOAT`) ou alargante (`VQDMULLB_scalar`/
    /// `VQDMULLT_scalar`, `MVE_INTEGER`), conforme `nibble3`; senão (`size` livre 0-2) seleciona
    /// `VHADD_S/U`/`VHSUB_S/U` (`nibble3=100`) ou `VQADD_S/U`/`VQSUB_S/U` (`nibble3=110`).
    private DecodedInstruction decodeHalvingSlot(int raw, int address, Condition condition, int qd, boolean u,
            boolean bit12, int nibble3, int rm) {
        int size2120 = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size2120 == SIZE_INVALID) {
            if (nibble3 == NIBBLE3_ACC) {
                if (!architecture.has(ArmFeature.MVE_FLOAT)) {
                    return null;
                }
                int qn = extractQn(raw);
                int esz = u ? 1 : 2;
                AdvSimdFpThreeSameOp op = bit12 ? AdvSimdFpThreeSameOp.SUB : AdvSimdFpThreeSameOp.ADD;
                return lifted(address, raw, condition, new IrOp.MveVectorFpScalar(op, esz, qd, qn, rm, condition));
            }
            if (nibble3 == NIBBLE3_SAT) {
                // VQDMULLB_scalar (bit12=0) / VQDMULLT_scalar (bit12=1): esz = bit28 direto.
                if (qd == extractQn(raw) && u) {
                    return null; // UNPREDICTABLE: Qd==Qn com esz word (u=1 -> esz=2).
                }
                int esz = u ? 2 : 1;
                int qn = extractQn(raw);
                return lifted(address, raw, condition,
                        new IrOp.MveVectorScalarWidening(esz, bit12, qd, qn, rm, condition));
            }
            return null;
        }
        int qn = extractQn(raw);
        if (nibble3 == NIBBLE3_ACC) {
            AdvSimdThreeSameOp op = bit12 ? (u ? AdvSimdThreeSameOp.UHSUB : AdvSimdThreeSameOp.SHSUB)
                    : (u ? AdvSimdThreeSameOp.UHADD : AdvSimdThreeSameOp.SHADD);
            return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size2120, qd, qn, rm, condition));
        }
        if (nibble3 == NIBBLE3_SAT) {
            AdvSimdThreeSameOp op = bit12 ? (u ? AdvSimdThreeSameOp.UQSUB : AdvSimdThreeSameOp.SQSUB)
                    : (u ? AdvSimdThreeSameOp.UQADD : AdvSimdThreeSameOp.SQADD);
            return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size2120, qd, qn, rm, condition));
        }
        return null;
    }

    /// `@shl_scalar` (`bits[21:20]==0b11` fixo dentro do slot `nib2=1110,b16=1,b12=1,nib3=110`):
    /// `size` REAL em `bits[19:18]` (Armadilha 4 da task — NÃO onde `@2scalar` o coloca),
    /// `round`=`bit17`, `sat`=`bit7` (repropositado: `@shl_scalar` não tem `Qn`, então `Qd`={@code
    /// qda} também vira {@link IrOp.MveVectorScalar#qn()}).
    private DecodedInstruction decodeShiftScalar(int raw, int address, Condition condition, int qd, boolean u,
            int rm) {
        int size = (raw >>> SHL_SIZE_SHIFT) & SHL_SIZE_MASK;
        if (size == SIZE_INVALID) {
            return null;
        }
        boolean round = ((raw >>> ROUND_BIT) & 1) != 0;
        boolean sat = ((raw >>> SAT_BIT) & 1) != 0;
        AdvSimdThreeSameOp op;
        if (u) {
            op = sat ? (round ? AdvSimdThreeSameOp.UQRSHL : AdvSimdThreeSameOp.UQSHL)
                    : (round ? AdvSimdThreeSameOp.URSHL : AdvSimdThreeSameOp.USHL);
        } else {
            op = sat ? (round ? AdvSimdThreeSameOp.SQRSHL : AdvSimdThreeSameOp.SQSHL)
                    : (round ? AdvSimdThreeSameOp.SRSHL : AdvSimdThreeSameOp.SSHL);
        }
        return lifted(address, raw, condition, new IrOp.MveVectorScalar(op, size, qd, qd, rm, condition));
    }

    /// `VBRSR` (`nib2=1110,b16=1,b12=1,nib3=110`, `U=1`, `size` livre 0-2).
    private DecodedInstruction decodeVbrsr(int raw, int address, Condition condition, int qd, int size, int rm) {
        int qn = extractQn(raw);
        return lifted(address, raw, condition, new IrOp.MveVectorScalarSpecial(
                IrOp.MveVectorScalarSpecial.SpecialOp.VBRSR, size, qd, qn, rm, condition));
    }

    private int extractQn(int raw) {
        return ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW_SHIFT) & QN_LOW_MASK);
    }

    private DecodedInstruction lifted(int address, int raw, Condition condition, IrOp op) {
        int qn = switch (op) {
            case IrOp.MveVectorScalar s -> s.qn();
            case IrOp.MveVectorScalarWidening w -> w.qn();
            case IrOp.MveVectorFpScalar f -> f.qn();
            case IrOp.MveVectorFpScalarFma f -> f.qn();
            case IrOp.MveVectorScalarSpecial s -> s.qn();
            default -> -1;
        };
        if (qn >= 0 && !VfpRegisters.isValidMveQuadRegister(architecture, qn)) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition, op);
    }
}
