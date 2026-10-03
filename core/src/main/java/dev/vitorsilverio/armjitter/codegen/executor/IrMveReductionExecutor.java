package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.MveReductionOp;

/// Executa as reduções MVE (perfil M, B16, MVE/Helium) da IR interpretada: somas, acumulações duplas
/// e mínimo/máximo através do vetor.
///
/// Sem estado: os records de {@link MveReductionOp} chamam os métodos estáticos direto (task E15.8).
public final class IrMveReductionExecutor {
    private IrMveReductionExecutor() {
    }

    /// `VADDV` (perfil M, B16.13a, MVE/Helium): soma horizontal das lanes ATIVAS de `Qm` em `Rda`
    /// (ver Javadoc de {@link MveReductionOp.VectorAddAcrossVector} para a semântica de
    /// `accumulate`/máscara-toda-zero).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorAddAcrossVector(ArmCore core, MveReductionOp.VectorAddAcrossVector op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.size();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int lanes = 16 / elementBytes;
        long acc = op.accumulate() ? (core.register(op.rda()) & 0xFFFF_FFFFL) : 0L;
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            long raw = vfp.element(op.qm(), lane, esz);
            long value = op.unsignedForm() ? raw : signExtendMveElement(raw, esz);
            acc += value;
        }
        core.setRegister(op.rda(), (int) acc);
        return false;
    }

    /// `VADDLV` (perfil M, B16.13a, MVE/Helium): como {@link #executeMveVectorAddAcrossVector}, mas
    /// elementos SEMPRE de 32 bits e acumulador de 64 bits em `RdaHi:RdaLo` (ver Javadoc de
    /// {@link MveReductionOp.VectorAddAcrossVectorLong}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorAddAcrossVectorLong(ArmCore core, MveReductionOp.VectorAddAcrossVectorLong op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        final int WORD_ESZ = 2;
        final int WORD_BYTES = 4;
        final int WORD_BYTE_MASK = 0xF;
        final int LANES = 4;
        long acc = op.accumulate()
                ? ((core.register(op.rdalo()) & 0xFFFF_FFFFL) | ((long) core.register(op.rdahi()) << 32))
                : 0L;
        for (int lane = 0; lane < LANES; lane++) {
            int laneMask = (byteMask >>> (lane * WORD_BYTES)) & WORD_BYTE_MASK;
            if (laneMask == 0) {
                continue;
            }
            long raw = vfp.element(op.qm(), lane, WORD_ESZ);
            long value = op.unsignedForm() ? (raw & 0xFFFF_FFFFL) : (int) raw;
            acc += value;
        }
        core.setRegister(op.rdalo(), (int) acc);
        core.setRegister(op.rdahi(), (int) (acc >>> 32));
        return false;
    }

    /// `VABAV_S`/`VABAV_U` (perfil M, B16.13a, MVE/Helium): soma horizontal, nas lanes ATIVAS, de
    /// `|Qn[i] - Qm[i]|` em `Rda` — SEMPRE acumula (sem bit `a`, ver Javadoc de
    /// {@link MveReductionOp.VectorAbsoluteDifferenceAccumulate}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorAbsoluteDifferenceAccumulate(ArmCore core,
            MveReductionOp.VectorAbsoluteDifferenceAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.size();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int lanes = 16 / elementBytes;
        long acc = core.register(op.rda()) & 0xFFFF_FFFFL;
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            long n = vfp.element(op.qn(), lane, esz);
            long m = vfp.element(op.qm(), lane, esz);
            long sn = op.unsignedForm() ? n : signExtendMveElement(n, esz);
            long sm = op.unsignedForm() ? m : signExtendMveElement(m, esz);
            acc += sn >= sm ? (sn - sm) : (sm - sn);
        }
        core.setRegister(op.rda(), (int) acc);
        return false;
    }

    /// `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV` (perfil M, B16.13b, MVE/Helium): produto-soma horizontal
    /// das lanes ATIVAS em `Rda` (ver Javadoc de {@link MveReductionOp.VectorDualAccumulate}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorDualAccumulate(ArmCore core, MveReductionOp.VectorDualAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.size();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int lanes = 16 / elementBytes;
        long acc = op.accumulate() ? (core.register(op.rda()) & 0xFFFF_FFFFL) : 0L;
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            boolean odd = (lane & 1) != 0;
            int nLane = op.exchange() ? (odd ? lane - 1 : lane + 1) : lane;
            long nRaw = vfp.element(op.qn(), nLane, esz);
            long mRaw = vfp.element(op.qm(), lane, esz);
            long n = op.unsignedForm() ? nRaw : signExtendMveElement(nRaw, esz);
            long m = op.unsignedForm() ? mRaw : signExtendMveElement(mRaw, esz);
            long product = n * m;
            acc = (odd && op.subtract()) ? (acc - product) : (acc + product);
        }
        core.setRegister(op.rda(), (int) acc);
        return false;
    }

    /// `VMLALDAV_S`/`VMLALDAV_U`/`VMLSLDAV` (perfil M, B16.13b, MVE/Helium): como
    /// {@link #executeMveVectorDualAccumulate}, mas acumulador de 64 bits em `RdaHi:RdaLo` (ver
    /// Javadoc de {@link MveReductionOp.VectorDualAccumulateLong}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorDualAccumulateLong(ArmCore core, MveReductionOp.VectorDualAccumulateLong op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.size();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : 0x3;
        int lanes = 16 / elementBytes;
        long acc = op.accumulate()
                ? ((core.register(op.rdalo()) & 0xFFFF_FFFFL) | ((long) core.register(op.rdahi()) << 32))
                : 0L;
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            boolean odd = (lane & 1) != 0;
            int nLane = op.exchange() ? (odd ? lane - 1 : lane + 1) : lane;
            long nRaw = vfp.element(op.qn(), nLane, esz);
            long mRaw = vfp.element(op.qm(), lane, esz);
            long n = op.unsignedForm() ? nRaw : signExtendMveElement(nRaw, esz);
            long m = op.unsignedForm() ? mRaw : signExtendMveElement(mRaw, esz);
            long product = n * m;
            acc = (odd && op.subtract()) ? (acc - product) : (acc + product);
        }
        core.setRegister(op.rdalo(), (int) acc);
        core.setRegister(op.rdahi(), (int) (acc >>> 32));
        return false;
    }

    /// `VRMLALDAVH_S`/`VRMLALDAVH_U`/`VRMLSLDAVH` (perfil M, B16.13b, MVE/Helium): produto-soma
    /// horizontal ARREDONDADO de elementos de 32 bits, acumulador de 64 bits em `RdaHi:RdaLo` (ver
    /// Javadoc de {@link MveReductionOp.VectorRoundingDualAccumulateHigh}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorRoundingDualAccumulateHigh(ArmCore core,
            MveReductionOp.VectorRoundingDualAccumulateHigh op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        final int WORD_ESZ = 2;
        final int WORD_BYTES = 4;
        final int WORD_BYTE_MASK = 0xF;
        final int LANES = 4;
        final int ROUND_SHIFT = 8;
        final int ROUND_BIT_SHIFT = 7;
        long acc = op.accumulate()
                ? ((core.register(op.rdalo()) & 0xFFFF_FFFFL) | ((long) core.register(op.rdahi()) << 32))
                : 0L;
        for (int lane = 0; lane < LANES; lane++) {
            int laneMask = (byteMask >>> (lane * WORD_BYTES)) & WORD_BYTE_MASK;
            if (laneMask == 0) {
                continue;
            }
            boolean odd = (lane & 1) != 0;
            int nLane = op.exchange() ? (odd ? lane - 1 : lane + 1) : lane;
            long nRaw = vfp.element(op.qn(), nLane, WORD_ESZ);
            long mRaw = vfp.element(op.qm(), lane, WORD_ESZ);
            long n = op.unsignedForm() ? (nRaw & 0xFFFF_FFFFL) : (int) nRaw;
            long m = op.unsignedForm() ? (mRaw & 0xFFFF_FFFFL) : (int) mRaw;
            long product = n * m;
            if (odd && op.subtract()) {
                product = -product;
            }
            long rounded = (product >> ROUND_SHIFT) + ((product >> ROUND_BIT_SHIFT) & 1);
            acc += rounded;
        }
        core.setRegister(op.rdalo(), (int) acc);
        core.setRegister(op.rdahi(), (int) (acc >>> 32));
        return false;
    }

    /// `VMAXV_S`/`VMAXV_U`/`VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV` (perfil M, B16.13b, MVE/Helium):
    /// min/max horizontal das lanes ATIVAS de `Qm` contra `Rda` ATUAL (ver Javadoc de
    /// {@link MveReductionOp.VectorMinMaxAcrossVector}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorMinMaxAcrossVector(ArmCore core, MveReductionOp.VectorMinMaxAcrossVector op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.size();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int lanes = 16 / elementBytes;
        long ra = op.unsignedForm() || op.absoluteForm()
                ? (core.register(op.rda()) & 0xFFFF_FFFFL)
                : core.register(op.rda());
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            long raw = vfp.element(op.qm(), lane, esz);
            long m;
            if (op.absoluteForm()) {
                m = Math.abs(signExtendMveElement(raw, esz));
            } else {
                m = op.unsignedForm() ? raw : signExtendMveElement(raw, esz);
            }
            ra = op.max() ? Math.max(ra, m) : Math.min(ra, m);
        }
        core.setRegister(op.rda(), (int) ra);
        return false;
    }

    /// `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` (perfil M, B16.13b, MVE/Helium, `FEAT_MVE_FP`):
    /// como {@link #executeMveVectorMinMaxAcrossVector}, mas ponto flutuante (ver Javadoc de
    /// {@link MveReductionOp.VectorFpMinMaxAcrossVector}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorFpMinMaxAcrossVector(ArmCore core, MveReductionOp.VectorFpMinMaxAcrossVector op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : 0x3;
        int lanes = 16 / elementBytes;
        if (esz == 1) {
            float ra = AdvSimdLanes.halfToFloat(core.register(op.rda()) & 0xFFFFL);
            for (int lane = 0; lane < lanes; lane++) {
                int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
                if (laneMask == 0) {
                    continue;
                }
                float v = AdvSimdLanes.halfToFloat(vfp.element(op.qm(), lane, esz));
                if (op.absoluteForm()) {
                    v = Math.abs(v);
                }
                ra = op.max() ? AdvSimdLanes.maxNum(ra, v) : AdvSimdLanes.minNum(ra, v);
            }
            core.setRegister(op.rda(), (int) (AdvSimdLanes.halfBits(ra) & 0xFFFFL));
        } else {
            float ra = Float.intBitsToFloat(core.register(op.rda()));
            for (int lane = 0; lane < lanes; lane++) {
                int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
                if (laneMask == 0) {
                    continue;
                }
                float v = Float.intBitsToFloat((int) vfp.element(op.qm(), lane, esz));
                if (op.absoluteForm()) {
                    v = Math.abs(v);
                }
                ra = op.max() ? AdvSimdLanes.maxNum(ra, v) : AdvSimdLanes.minNum(ra, v);
            }
            core.setRegister(op.rda(), Float.floatToRawIntBits(ra));
        }
        return false;
    }

    /// Sign-extend de um elemento MVE de `1 << esz` bytes (`0`=byte/`1`=halfword/`2`=word) para
    /// `long`, usado pelas reduções (`VADDV`/`VABAV`) quando `unsignedForm=false`.
    private static long signExtendMveElement(long raw, int esz) {
        return switch (esz) {
            case 0 -> (byte) raw;
            case 1 -> (short) raw;
            default -> (int) raw;
        };
    }
}
