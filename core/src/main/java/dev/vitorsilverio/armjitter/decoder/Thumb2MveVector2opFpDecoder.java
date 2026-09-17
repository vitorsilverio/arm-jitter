package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Sub-família 3 da B16.7 (perfil M, MVE/Helium, `target/isa-decode/mve.decode`, seção "2-operand
/// FP", linhas 771-789, 14 encodings) — a última sub-família da task, transcrita verbatim do
/// arquivo real:
///
/// ```
/// VADD_fp      1110 1111 0 . 0 . ... 0 ... 0 1101 . 1 . 0 ... 0 @2op_fp
/// VSUB_fp      1110 1111 0 . 1 . ... 0 ... 0 1101 . 1 . 0 ... 0 @2op_fp
/// VMUL_fp      1111 1111 0 . 0 . ... 0 ... 0 1101 . 1 . 1 ... 0 @2op_fp
/// VABD_fp      1111 1111 0 . 1 . ... 0 ... 0 1101 . 1 . 0 ... 0 @2op_fp
/// VMAXNM       1111 1111 0 . 0 . ... 0 ... 0 1111 . 1 . 1 ... 0 @2op_fp
/// VMINNM       1111 1111 0 . 1 . ... 0 ... 0 1111 . 1 . 1 ... 0 @2op_fp
/// VCADD90_fp   1111 1100 1 . 0 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// VCADD270_fp  1111 1101 1 . 0 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// VFMA         1110 1111 0 . 0 . ... 0 ... 0 1100 . 1 . 1 ... 0 @2op_fp
/// VFMS         1110 1111 0 . 1 . ... 0 ... 0 1100 . 1 . 1 ... 0 @2op_fp
/// VCMLA0       1111 110 00 . 1 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// VCMLA90      1111 110 01 . 1 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// VCMLA180     1111 110 10 . 1 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// VCMLA270     1111 110 11 . 1 . ... 0 ... 0 1000 . 1 . 0 ... 0 @2op_fp_size_rev
/// ```
///
/// `@2op_fp`/`@2op_fp_size_rev` (linhas 137-141 do arquivo real) compartilham o MESMO layout de
/// campo (`qd=%qd`/`qn=%qn`/`qm=%qm`, `%qd=22:1,13:3`/`%qn=7:1,17:3`/`%qm=5:1,1:3`) e a MESMA posição
/// de `size` (bit 20) — só a FUNÇÃO que interpreta o bit difere: `%2op_fp_size` (`@2op_fp`, direto:
/// `bit20=1`→binary16, `bit20=0`→binary32, "like Neon FP insns") vs `%2op_fp_size_rev`
/// (`@2op_fp_size_rev`, `!function=plus_1`: `bit20+1`, ou seja INVERTIDO — comentário literal do
/// arquivo real: "VCADD is an exception, where bit 20 is 0 for 16 bit and 1 for 32 bit"). Nenhuma
/// das 14 linhas tem `{}` sobreposto entre si (ao contrário das sub-famílias 1/2) — cada padrão de
/// 32 bits, com os campos de registrador/`size` zerados, é ÚNICO e a mesma máscara de bits fixos
/// (`0xFFA11F51`, medida bit a bit contra o arquivo real) serve para as 14, então o decode é uma
/// tabela direta `(raw & MASK) == valor`, sem prioridade a resolver.
///
/// Todas as 14 exigem {@link ArmFeature#MVE_FLOAT} (`FEAT_MVE_FP`) — ao contrário das sub-famílias
/// 1/2, que misturavam `MVE_INTEGER`/`MVE_FLOAT` linha a linha, esta sub-família é FP pura.
///
/// **Reuso total do núcleo**: `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/
/// `VFMS` mapeiam 1:1 para {@link AdvSimdFpThreeSameOp#ADD}/{@code SUB}/{@code MUL}/{@code ABD}/
/// {@code MAXNM}/{@code MINNM}/{@code FMLA}/{@code FMLS} — as MESMAS operações que
/// `NeonFpThreeSame`/A64 já usam via {@link AdvSimdLanes#fpThreeSame}; esta task só precisou do
/// gancho PREDICADO ({@link AdvSimdLanes#fpThreeSameMasked}), zero aritmética nova. `VCADD90_fp`/
/// `VCADD270_fp`/`VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` reusam a MESMA fórmula
/// `FComplexAddImpl`/`FComplexMulAdd` que `FEAT_FCMA`/NEON já usam via
/// {@link AdvSimdLanes#fpComplexAdd}/{@link AdvSimdLanes#fpComplexMultiplyAccumulate} — só
/// precisaram do gancho predicado por PAR ({@link AdvSimdLanes#fpComplexAddMasked}/
/// {@link AdvSimdLanes#fpComplexMultiplyAccumulateMasked}).
///
/// Usa o escape hatch de lifting (mesmo precedente de {@link Thumb2MveVector2opDecoder}). Registrado
/// ANTES de `Thumb2NocpDecoder` em {@code ArmArchitecture#ARMV8_1M_MVE} (mesma Armadilha 1 do resto
/// da escada B16 — `bits[31:29]=111` cobre este espaço também).
public final class Thumb2MveVector2opFpDecoder implements DecoderExtension {
    /// Máscara comum às 14 linhas (bits fixos fora dos campos `qd`/`qn`/`qm`/`size`), medida bit a
    /// bit contra o `.decode` real — ver Javadoc da classe.
    private static final int MASK = 0xFFA11F51;

    private static final int VALUE_VADD_FP = 0xEF000D40;
    private static final int VALUE_VSUB_FP = 0xEF200D40;
    private static final int VALUE_VMUL_FP = 0xFF000D50;
    private static final int VALUE_VABD_FP = 0xFF200D40;
    private static final int VALUE_VMAXNM = 0xFF000F50;
    private static final int VALUE_VMINNM = 0xFF200F50;
    private static final int VALUE_VFMA = 0xEF000C50;
    private static final int VALUE_VFMS = 0xEF200C50;
    private static final int VALUE_VCADD90_FP = 0xFC800840;
    private static final int VALUE_VCADD270_FP = 0xFD800840;
    private static final int VALUE_VCMLA0 = 0xFC200840;
    private static final int VALUE_VCMLA90 = 0xFCA00840;
    private static final int VALUE_VCMLA180 = 0xFD200840;
    private static final int VALUE_VCMLA270 = 0xFDA00840;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QN_HIGH_BIT = 7;
    private static final int QN_LOW_SHIFT = 17;
    private static final int QN_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int SIZE_BIT = 20;

    /// `%2op_fp_size` (`@2op_fp`): direto — `bit20=1`→binary16 (`1`), `bit20=0`→binary32 (`2`).
    private static final int ESZ_BINARY16 = 1;
    private static final int ESZ_BINARY32 = 2;

    private final ArmArchitecture architecture;

    public Thumb2MveVector2opFpDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int matched = raw & MASK;
        AdvSimdFpThreeSameOp threeSameOp = switch (matched) {
            case VALUE_VADD_FP -> AdvSimdFpThreeSameOp.ADD;
            case VALUE_VSUB_FP -> AdvSimdFpThreeSameOp.SUB;
            case VALUE_VMUL_FP -> AdvSimdFpThreeSameOp.MUL;
            case VALUE_VABD_FP -> AdvSimdFpThreeSameOp.ABD;
            case VALUE_VMAXNM -> AdvSimdFpThreeSameOp.MAXNM;
            case VALUE_VMINNM -> AdvSimdFpThreeSameOp.MINNM;
            case VALUE_VFMA -> AdvSimdFpThreeSameOp.FMLA;
            case VALUE_VFMS -> AdvSimdFpThreeSameOp.FMLS;
            default -> null;
        };
        if (threeSameOp != null) {
            return decodeTwoOp(raw, address, condition, threeSameOp);
        }
        return switch (matched) {
            case VALUE_VCADD90_FP -> decodeComplexAdd(raw, address, condition, true);
            case VALUE_VCADD270_FP -> decodeComplexAdd(raw, address, condition, false);
            case VALUE_VCMLA0 -> decodeComplexMultiplyAccumulate(raw, address, condition, AdvSimdLanes.COMPLEX_ROTATE_0);
            case VALUE_VCMLA90 -> decodeComplexMultiplyAccumulate(raw, address, condition, AdvSimdLanes.COMPLEX_ROTATE_90);
            case VALUE_VCMLA180 ->
                    decodeComplexMultiplyAccumulate(raw, address, condition, AdvSimdLanes.COMPLEX_ROTATE_180);
            case VALUE_VCMLA270 ->
                    decodeComplexMultiplyAccumulate(raw, address, condition, AdvSimdLanes.COMPLEX_ROTATE_270);
            default -> null;
        };
    }

    /// `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/`VFMS` (`@2op_fp`, `size`
    /// DIRETO — ver Javadoc da classe).
    private DecodedInstruction decodeTwoOp(int raw, int address, Condition condition, AdvSimdFpThreeSameOp op) {
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qn = ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW_SHIFT) & QN_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int esz = ((raw >>> SIZE_BIT) & 1) != 0 ? ESZ_BINARY16 : ESZ_BINARY32;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpTwoOp(op, esz, qd, qn, qm, condition));
    }

    /// `VCADD90_fp`/`VCADD270_fp` (`@2op_fp_size_rev`, `size = bit20+1` — ver Javadoc da classe).
    private DecodedInstruction decodeComplexAdd(int raw, int address, Condition condition, boolean rotate90) {
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qn = ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW_SHIFT) & QN_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int esz = ((raw >>> SIZE_BIT) & 1) + ESZ_BINARY16;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpComplexAdd(rotate90, esz, qd, qn, qm, condition));
    }

    /// `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (`@2op_fp_size_rev`, `size = bit20+1`).
    private DecodedInstruction decodeComplexMultiplyAccumulate(int raw, int address, Condition condition,
            int rotation) {
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qn = ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW_SHIFT) & QN_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int esz = ((raw >>> SIZE_BIT) & 1) + ESZ_BINARY16;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpComplexMultiplyAccumulate(rotation, esz, qd, qn, qm, condition));
    }
}
