package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.MveFpOp;

/// Executa as operações MVE de ponto flutuante (perfil M, B16, MVE/Helium) da IR interpretada.
///
/// Sem estado: os records de {@link MveFpOp} chamam os métodos estáticos direto (task E15.8).
public final class IrMveFpExecutor {
    private IrMveFpExecutor() {
    }

    /// `VMAXNMA`/`VMINNMA` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#fpAbsAccumulateMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpAbsAccumulate(ArmCore core, MveFpOp.VectorFpAbsAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpAbsAccumulateMasked(vfp, op.max(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU`/`VCVTA{S,U}`/`VCVTN{S,U}`/`VCVTP{S,U}`/`VCVTM{S,U}`/
    /// `VRINTN`/`VRINTX`/`VRINTA`/`VRINTZ`/`VRINTM`/`VRINTP` (perfil M, B16.12, MVE/Helium): delega
    /// ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpUnaryMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpConvert(ArmCore core, MveFpOp.VectorFpConvert op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpUnaryMasked(vfp, op.op(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VCVT_SH_fixed`/`VCVT_UH_fixed`/`VCVT_HS_fixed`/`VCVT_HU_fixed`/`VCVT_SF_fixed`/
    /// `VCVT_UF_fixed`/`VCVT_FS_fixed`/`VCVT_FU_fixed` (perfil M, B16.12, MVE/Helium): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#convertFixedPointMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpConvertFixed(ArmCore core, MveFpOp.VectorFpConvertFixed op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.convertFixedPointMasked(vfp, esz, op.fractionBits(), op.toFloat(), op.signed(), lanes, baseRd,
                baseRm, mask);
        return false;
    }

    /// `VABS_fp`/`VNEG_fp` (perfil M, B16.13a, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpUnaryMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpUnary(ArmCore core, MveFpOp.VectorFpUnary op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpUnaryMasked(vfp, op.op(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpNarrowPrecisionInterleavedMasked}/
    /// {@link AdvSimdLanes#fpWidenPrecisionInterleavedMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpConvertPrecision(ArmCore core, MveFpOp.VectorFpConvertPrecision op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        if (op.widen()) {
            AdvSimdLanes.fpWidenPrecisionInterleavedMasked(vfp, op.top(), baseRd, baseRm, mask);
        } else {
            AdvSimdLanes.fpNarrowPrecisionInterleavedMasked(vfp, op.top(), baseRd, baseRm, mask);
        }
        return false;
    }

    /// `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`): delega
    /// ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpComplexMultiplyMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpComplexMultiply(ArmCore core, MveFpOp.VectorFpComplexMultiply op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpComplexMultiplyMasked(vfp, esz, lanes, baseRd, baseRn, baseRm, op.rotation(), mask);
        return false;
    }

    /// `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/`VFMS` (perfil M, B16.7
    /// sub-família 3, MVE/Helium, `FEAT_MVE_FP`): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpThreeSameMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpTwoOp(ArmCore core, MveFpOp.VectorFpTwoOp op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpThreeSameMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, baseRm, mask);
        return false;
    }

    /// `VCADD90_fp`/`VCADD270_fp` (perfil M, B16.7 sub-família 3, MVE/Helium, `FEAT_MVE_FP`): delega
    /// ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpComplexAddMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpComplexAdd(ArmCore core, MveFpOp.VectorFpComplexAdd op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        int rotation = op.rotate90() ? AdvSimdLanes.COMPLEX_ROTATE_90 : AdvSimdLanes.COMPLEX_ROTATE_270;
        AdvSimdLanes.fpComplexAddMasked(vfp, esz, lanes, baseRd, baseRn, baseRm, rotation, mask);
        return false;
    }

    /// `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (perfil M, B16.7 sub-família 3, MVE/Helium,
    /// `FEAT_MVE_FP`): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpComplexMultiplyAccumulateMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpComplexMultiplyAccumulate(ArmCore core,
            MveFpOp.VectorFpComplexMultiplyAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpComplexMultiplyAccumulateMasked(vfp, esz, lanes, baseRd, baseRn, baseRm, op.rotation(), mask);
        return false;
    }

    /// `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar` (perfil M, B16.9, MVE/Helium): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#fpThreeSameScalarMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpScalar(ArmCore core, MveFpOp.VectorFpScalar op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        long rm = Integer.toUnsignedLong(core.register(op.rm()));
        AdvSimdLanes.fpThreeSameScalarMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, rm, mask);
        return false;
    }

    /// `VFMA_scalar`/`VFMAS_scalar` (perfil M, B16.9, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpFusedMultiplyAddScalarMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpScalarFma(ArmCore core, MveFpOp.VectorFpScalarFma op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        long rm = Integer.toUnsignedLong(core.register(op.rm()));
        AdvSimdLanes.fpFusedMultiplyAddScalarMasked(vfp, op.swapAccumulator(), esz, lanes, baseRd, baseRn, rm, mask);
        return false;
    }
}
