package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VMOV_to_2gp`/`VMOV_from_2gp` — sub-família 1 da B16.13a (perfil M, MVE/Helium,
/// `target/isa-decode/mve.decode`, linhas 206-208, 2 encodings, bit a bit contra o arquivo real):
///
/// ```
/// VMOV_to_2gp      1110 1100 0 . 00 rt2:4 ... 0 1111 000 idx:1 rt:4 qd=%qd
/// VMOV_from_2gp    1110 1100 0 . 01 rt2:4 ... 0 1111 000 idx:1 rt:4 qd=%qd
/// ```
///
/// **Achado real que CORRIGE a task**: o QEMU real (`translate-mve.c`,
/// `trans_VMOV_to_2gp`/`trans_VMOV_from_2gp`) só recusa `Rt == Rt2` em `VMOV_to_2gp` — a condição de
/// `VMOV_from_2gp` NÃO tem esse termo (a task citava uma checagem genérica única para as duas, que
/// não é o que o código real faz). `Rt`/`Rt2` `∈ {13,15}` são recusados nos dois sentidos.
///
/// **Não predicado por `VPR`** (confirmado verbatim): as duas funções só checam `mve_eci_check`,
/// nunca chamam `mve_element_mask` — categoria "beatwise mas não predicado" (mesma de
/// {@link Thumb2MveInterleavedLoadStoreDecoder}, ver Javadoc de {@link IrOp.MveMoveLanesGpr}).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. `bits[31:23]=1110_1100_0` colide com o espaço de
/// extension-register load/store do VFP (`VfpDecoder`, que não checa `bits[31:28]` — bug G8 da
/// B14.4); registrar este decoder ANTES de `VfpDecoder`/`Thumb2NocpDecoder` no preset.
public final class Thumb2MveMoveLanesGprDecoder implements DecoderExtension {
    private static final int TOP9_SHIFT = 23;
    private static final int TOP9_MASK = 0x1FF;
    private static final int TOP9_VALUE = 0b111011000;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;

    private static final int VARIANT_SHIFT = 20;
    private static final int VARIANT_MASK = 0x3;
    private static final int VARIANT_TO_GPR = 0b00;
    private static final int VARIANT_FROM_GPR = 0b01;

    private static final int RT2_SHIFT = 16;
    private static final int RT2_MASK = 0xF;
    private static final int BIT12 = 12;
    private static final int MIDDLE_NIBBLE_SHIFT = 8;
    private static final int MIDDLE_NIBBLE_MASK = 0xF;
    private static final int MIDDLE_NIBBLE_VALUE = 0b1111;
    private static final int BITS_7_5_SHIFT = 5;
    private static final int BITS_7_5_MASK = 0x7;
    private static final int BITS_7_5_VALUE = 0b000;
    private static final int IDX_BIT = 4;
    private static final int RT_SHIFT = 0;
    private static final int RT_MASK = 0xF;

    private static final int GPR_PC = 15;
    private static final int GPR_SP = 13;

    private final ArmArchitecture architecture;

    public Thumb2MveMoveLanesGprDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP9_SHIFT) & TOP9_MASK) != TOP9_VALUE) {
            return null;
        }
        if (((raw >>> BIT12) & 1) != 0) {
            return null;
        }
        if (((raw >>> MIDDLE_NIBBLE_SHIFT) & MIDDLE_NIBBLE_MASK) != MIDDLE_NIBBLE_VALUE) {
            return null;
        }
        if (((raw >>> BITS_7_5_SHIFT) & BITS_7_5_MASK) != BITS_7_5_VALUE) {
            return null;
        }
        int variant = (raw >>> VARIANT_SHIFT) & VARIANT_MASK;
        boolean toGpr;
        if (variant == VARIANT_TO_GPR) {
            toGpr = true;
        } else if (variant == VARIANT_FROM_GPR) {
            toGpr = false;
        } else {
            return null;
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int rt2 = (raw >>> RT2_SHIFT) & RT2_MASK;
        int rt = (raw >>> RT_SHIFT) & RT_MASK;
        if (rt == GPR_SP || rt == GPR_PC || rt2 == GPR_SP || rt2 == GPR_PC) {
            return null;
        }
        if (toGpr && rt == rt2) {
            return null; // Só VMOV_to_2gp recusa Rt==Rt2 no QEMU real — ver Javadoc da classe.
        }
        int idx = (raw >>> IDX_BIT) & 1;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveMoveLanesGpr(toGpr, qd, idx, rt, rt2, condition));
    }
}
