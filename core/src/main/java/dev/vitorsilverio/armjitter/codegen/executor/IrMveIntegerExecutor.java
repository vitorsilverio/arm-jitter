package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.MveWideShifts;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.MveIntegerOp;

/// Executa as operações MVE inteiras (perfil M, B16, MVE/Helium) da IR interpretada: vetor × vetor,
/// vetor × escalar, alargantes, estreitantes, deslocamentos, unárias e os deslocamentos longos em GPR.
///
/// Sem estado: os records de {@link MveIntegerOp} chamam os métodos estáticos direto (task E15.8).
public final class IrMveIntegerExecutor {
    private IrMveIntegerExecutor() {
    }

    /// MVE "long shift" sobre GPR (perfil M, B16.16) — `do_mve_shl_ri`/`do_mve_shl_rr`/`do_mve_sh_ri`/
    /// `do_mve_sh_rr` do QEMU. Escalar: não lê `VPR`/`ECI` nem toca `Q0`-`Q7`. Nas formas por registrador
    /// a quantidade é o BYTE baixo de `Rm` COM SINAL (`(int8_t)`), negado nas operações à direita
    /// (`SQRSHR*`/`ASRL`); a saturação seta `APSR.Q` (sticky), nunca `FPSCR.QC`.
    public static void executeMveWideShift(ArmCore core, MveIntegerOp.WideShift op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        MveIntegerOp.WideShiftOperation operation = op.operation();
        int amount = operation.register() ? (byte) core.register(op.rm()) : op.shim();
        int shift = operation.right() ? -amount : amount;
        boolean sat = operation.saturating();
        MveWideShifts.Result result;
        if (!operation.wide()) {
            int source = core.register(op.rdaLo());
            result = operation.signed()
                    ? MveWideShifts.signedShift32(source, shift, operation.round(), sat)
                    : MveWideShifts.unsignedShift32(source, shift, operation.round(), sat);
            core.setRegister(op.rdaLo(), (int) result.value());
        } else {
            long source = ((long) core.register(op.rdaHi()) << Integer.SIZE)
                    | (core.register(op.rdaLo()) & 0xFFFF_FFFFL);
            if (operation.bits() == NARROW_WIDE_SHIFT_BITS) {
                result = operation.signed()
                        ? MveWideShifts.signedShift48(source, shift)
                        : MveWideShifts.unsignedShift48(source, shift);
            } else {
                result = operation.signed()
                        ? MveWideShifts.signedShift64(source, shift, operation.round(), sat)
                        : MveWideShifts.unsignedShift64(source, shift, operation.round(), sat);
            }
            core.setRegister(op.rdaLo(), (int) result.value());
            core.setRegister(op.rdaHi(), (int) (result.value() >>> Integer.SIZE));
        }
        if (result.saturated()) {
            core.cpsr().setSaturation(true);
        }
    }

    /// Largura de saturação de `UQRSHLL48`/`SQRSHRL48` (B16.16).
    private static final int NARROW_WIDE_SHIFT_BITS = 48;

    /// Vector 2-op inteiro (perfil M, B16.6, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#threeSameMasked}) — a MESMA função que `IrNeonExecutor`/executor A64
    /// chamam via {@link AdvSimdLanes#threeSame} para o caminho NÃO predicado (RFC B13.2 D1). As 12
    /// formas saturantes setam `FPSCR.QC` só quando alguma lane ATIVA saturou de verdade (ver
    /// Javadoc de {@link dev.vitorsilverio.armjitter.core.FpscrRegister#QC_FLAG}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVector2Op(ArmCore core, MveIntegerOp.Vector2Op op) {
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
        boolean saturated = AdvSimdLanes.threeSameMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VMULLP_B`/`VMULLP_T`/`VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (perfil M, B16.6,
    /// MVE/Helium): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#wideningInterleavedMasked}).
    /// Nenhuma destas seis satura (não estão entre os 10 `op` saturantes de {@link
    /// AdvSimdLanes#threeSameMasked} — `SMULL`/`UMULL`/`PMULL` nunca saturam por construção), sem
    /// `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVector2OpWidening(ArmCore core, MveIntegerOp.Vector2OpWidening op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.wideningInterleavedMasked(vfp, op.op(), esz, outputElements, op.top(), baseRd, baseRn, baseRm,
                mask);
        return false;
    }

    /// `VADC`/`VADCI`/`VSBC`/`VSBCI` (perfil M, B16.6, MVE/Helium): soma/subtração com carry
    /// encadeado por `FPSCR.C` através dos 4 elementos de 32 bits de `Qn`/`Qm` — verbatim de
    /// `do_vadc` (`target/arm/tcg/mve_helper.c`, ver Javadoc de {@link MveIntegerOp.VectorCarry}). ESIZE
    /// fixo em 4 bytes (não usa {@link AdvSimdLanes#threeSameMasked}: o carry ENCADEADO entre
    /// lanes, que só avança em lanes ATIVAS, não é uma operação "three same" independente por lane).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorCarry(ArmCore core, MveIntegerOp.VectorCarry op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        final int esz = 2; // ESIZE fixo em 4 bytes (word) — `size` do encoding é decorativo aqui.
        final int elementBytes = 1 << esz;
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        FpscrRegister fpscr = core.fpscr();
        boolean carry = op.immediateCarry() ? !op.add() : fpscr.c();
        boolean anyActive = false;
        int invert = op.add() ? 0 : -1;
        for (int i = 0; i < 4; i++) {
            int laneMask = (mask >>> (i * elementBytes)) & 0xF;
            long n = AdvSimdLanes.element(vfp, baseRn, i, esz);
            long m = AdvSimdLanes.element(vfp, baseRm, i, esz);
            long sum = (carry ? 1L : 0L) + (n & 0xFFFF_FFFFL) + ((m ^ invert) & 0xFFFF_FFFFL);
            if (laneMask != 0) {
                anyActive = true;
                carry = ((sum >>> 32) & 1) != 0;
            }
            long current = AdvSimdLanes.element(vfp, baseRd, i, esz);
            long merged = mergeMveCarryLane(current, sum, laneMask, elementBytes);
            AdvSimdLanes.setElement(vfp, baseRd, i, esz, merged);
        }
        if (anyActive) {
            fpscr.setNzcv(carry ? FpscrRegister.CARRY_FLAG : 0);
        }
        return false;
    }

    /// Mescla `result` (32 bits crus, só os 4 bytes baixos usados) em `current` byte a byte —
    /// mesma convenção de `mergemask`/{@link AdvSimdLanes#threeSameMasked} usada por
    /// {@link #executeMveVectorCarry} (duplicado aqui em vez de reusar `mergeLaneBytes`, privado ao
    /// núcleo — G6, sem número mágico: `0xFFL << (byteIndex*8)` é a mesma fórmula documentada lá).
    private static long mergeMveCarryLane(long current, long result, int laneMask, int elementBytes) {
        long merged = current;
        for (int byteIndex = 0; byteIndex < elementBytes; byteIndex++) {
            if (((laneMask >>> byteIndex) & 1) != 0) {
                long byteMask = 0xFFL << (byteIndex * 8);
                merged = (merged & ~byteMask) | (result & byteMask);
            }
        }
        return merged;
    }

    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (perfil M, B16.6, MVE/Helium): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#complexAddMasked}). Nunca satura, sem `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorComplexAdd(ArmCore core, MveIntegerOp.VectorComplexAdd op) {
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
        AdvSimdLanes.complexAddMasked(vfp, op.rotate90(), op.halving(), esz, lanes, baseRd, baseRn, baseRm, mask);
        return false;
    }

    /// `VMAXA`/`VMINA` (perfil M, B16.7, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#absAccumulateMasked}). Nunca satura, sem `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorAbsAccumulate(ArmCore core, MveIntegerOp.VectorAbsAccumulate op) {
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
        AdvSimdLanes.absAccumulateMasked(vfp, op.max(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma T2 (perfil M, B16.7, MVE/Helium): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#shiftWidenInterleavedMasked}). `shift` é sempre
    /// `8 << esz` (T2 — "shift == esize"). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorShiftWidenInterleaved(ArmCore core, MveIntegerOp.VectorShiftWidenInterleaved op) {
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
        int shift = 8 << esz;
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.shiftWidenInterleavedMasked(vfp, op.signed(), esz, shift, outputElements, op.top(), baseRd,
                baseRm, mask);
        return false;
    }

    /// `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/`VSRI`/`VSLI`
    /// (perfil M, B16.10, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftImmediateMasked}) — a MESMA função que `IrNeonExecutor`/executor A64
    /// chamam via {@link AdvSimdLanes#shiftImmediate} para o caminho NÃO predicado (RFC B13.2 D1). As
    /// 3 formas saturantes setam `FPSCR.QC` só quando alguma lane ATIVA saturou de verdade.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorShiftImmediate(ArmCore core, MveIntegerOp.VectorShiftImmediate op) {
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
        boolean saturated =
                AdvSimdLanes.shiftImmediateMasked(vfp, op.op(), esz, op.shift(), lanes, baseRd, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma T1 (perfil M, B16.10, MVE/Helium, inclui
    /// `VMOVL` = `shift == 0`, sem `Kind` próprio): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftWidenInterleavedMasked}) — MESMA função que
    /// {@link #executeMveVectorShiftWidenInterleaved} (T2) chama, só que aqui `shift` vem do encoding
    /// em vez de fixo em `esize`. Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorShiftWidenImmediateInterleaved(ArmCore core,
            MveIntegerOp.VectorShiftWidenImmediateInterleaved op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.shiftWidenInterleavedMasked(vfp, op.signed(), esz, op.shift(), outputElements, op.top(), baseRd,
                baseRm, mask);
        return false;
    }

    /// `VSHRNB`/`VSHRNT`/`VRSHRNB`/`VRSHRNT`/`VQSHRNB_S/T_S`/`VQSHRNB_U/T_U`/`VQSHRUNB/T`/
    /// `VQRSHRNB_S/T_S`/`VQRSHRNB_U/T_U`/`VQRSHRUNB/T` (perfil M, B16.11, MVE/Helium): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#shiftNarrowInterleavedMasked}). `FPSCR.QC` só para
    /// as 6 formas saturantes ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp#SHRN}/
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp#RSHRN} nunca saturam).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorShiftNarrowImmediateInterleaved(ArmCore core,
            MveIntegerOp.VectorShiftNarrowImmediateInterleaved op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.shiftNarrowInterleavedMasked(vfp, op.op(), esz, op.shift(), outputElements,
                op.top(), baseRd, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VSHLC` (perfil M, B16.11, MVE/Helium, verbatim de `HELPER(mve_vshlc)`,
    /// `target/arm/tcg/mve_helper.c`): desloca os 128 bits de `Qd` à esquerda por `shift` bits
    /// (`shift == 0` do encoding significa "desloca por 32", tratado explicitamente — ver Javadoc de
    /// {@link MveIntegerOp.VectorShiftLeftCarry}), injetando os bits BAIXOS de `Rdm` na base de cada
    /// elemento de 32 bits e atualizando `Rdm` com os bits que saíram pelo topo do último elemento
    /// ATIVO (granularidade de BEAT — `mask & 1` por elemento de 32 bits, não por byte). Nunca
    /// satura.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorShiftLeftCarry(ArmCore core, MveIntegerOp.VectorShiftLeftCarry op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        final int esz = 2; // ESIZE fixo em 4 bytes (word) — VSHLC sempre opera em elementos de 32 bits.
        final int elementBytes = 1 << esz;
        final int elementCount = 4; // 128 bits / 32 bits.
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int shift = op.imm() == 0 ? 32 : op.imm();
        long rdm = Integer.toUnsignedLong(core.register(op.rdm()));
        for (int e = 0; e < elementCount; e++) {
            int laneMask = (mask >>> (e * elementBytes)) & 0xF;
            long current = AdvSimdLanes.element(vfp, baseRd, e, esz);
            long result;
            long nextRdm = rdm;
            if (shift == 32) {
                result = rdm;
                if (laneMask != 0) {
                    nextRdm = current;
                }
            } else {
                long shiftMask = (1L << shift) - 1;
                result = ((current << shift) | (rdm & shiftMask)) & 0xFFFF_FFFFL;
                if (laneMask != 0) {
                    nextRdm = current >>> (32 - shift);
                }
            }
            long merged = mergeMveCarryLane(current, result, laneMask, elementBytes);
            AdvSimdLanes.setElement(vfp, baseRd, e, esz, merged);
            rdm = nextRdm & 0xFFFF_FFFFL;
        }
        core.setRegister(op.rdm(), (int) rdm);
        return false;
    }

    /// `VCLS`/`VCLZ`/`VREV16`/`VREV32`/`VREV64`/`VMVN`/`VABS`/`VNEG`/`VQABS`/`VQNEG` (perfil M,
    /// B16.13a, MVE/Helium): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#unaryMasked}).
    /// `FPSCR.QC` só quando `SQABS`/`SQNEG` saturam numa lane ATIVA.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorUnary(ArmCore core, MveIntegerOp.VectorUnary op) {
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
        int baseRn = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.unaryMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M, B16.7, MVE/Helium):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#narrowInterleavedMasked}). `FPSCR.QC` só
    /// para as 3 formas saturantes ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp#XTN}
    /// nunca satura).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorNarrowInterleaved(ArmCore core, MveIntegerOp.VectorNarrowInterleaved op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.narrowInterleavedMasked(vfp, op.op(), esz, outputElements, op.top(), baseRd,
                baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VQDMLADH`/`VQDMLSDH` e variantes `X`/`R` (perfil M, B16.7, MVE/Helium): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#dualMultiplyAddHighMasked}) — só METADE das lanes é escrita
    /// (ver Javadoc de lá).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorDualMultiplyAddHigh(ArmCore core, MveIntegerOp.VectorDualMultiplyAddHigh op) {
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
        boolean saturated = AdvSimdLanes.dualMultiplyAddHighMasked(vfp, op.add(), op.exchange(), op.rounded(), esz,
                lanes, baseRd, baseRn, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VQDMULLB`/`VQDMULLT` (perfil M, B16.7, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#doublingWideningInterleavedMasked}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorDoublingWideningMultiply(ArmCore core, MveIntegerOp.VectorDoublingWideningMultiply op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.doublingWideningInterleavedMasked(vfp, esz, outputElements, op.top(), baseRd,
                baseRn, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VADD_scalar`…`VQRDMULH_scalar`/`VMLA` (`@2scalar`) e `VSHL_S_scalar`…`VQRSHL_U_scalar`
    /// (`@shl_scalar`, perfil M, B16.9, MVE/Helium): delega ao núcleo COMPARTILHADO ({@link
    /// AdvSimdLanes#threeSameScalarMasked}) — `Rm` é lido UMA vez do GPR (zero-estendido de 32
    /// bits) e replicado por toda a operação. As formas saturantes setam `FPSCR.QC` só quando
    /// alguma lane ATIVA saturou.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorScalar(ArmCore core, MveIntegerOp.VectorScalar op) {
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
        boolean saturated = AdvSimdLanes.threeSameScalarMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, rm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VQDMULLB_scalar`/`VQDMULLT_scalar` (perfil M, B16.9, MVE/Helium): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#doublingWideningScalarInterleavedMasked}).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorScalarWidening(ArmCore core, MveIntegerOp.VectorScalarWidening op) {
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
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        long rm = Integer.toUnsignedLong(core.register(op.rm()));
        boolean saturated = AdvSimdLanes.doublingWideningScalarInterleavedMasked(vfp, esz, outputElements, op.top(),
                baseRd, baseRn, rm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VBRSR`/`VMLAS`/`VQDMLAH`/`VQRDMLAH`/`VQDMLASH`/`VQRDMLASH` (perfil M, B16.9, MVE/Helium):
    /// delega ao método dedicado de {@link AdvSimdLanes} correspondente a {@link
    /// MveIntegerOp.VectorScalarSpecial.SpecialOp}. As 4 formas `VQ*DMLA*H` setam `FPSCR.QC` só quando
    /// alguma lane ATIVA saturou; `VBRSR`/`VMLAS` nunca saturam.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public static boolean executeMveVectorScalarSpecial(ArmCore core, MveIntegerOp.VectorScalarSpecial op) {
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
        switch (op.op()) {
            case VBRSR -> AdvSimdLanes.bitReverseShiftRightMasked(vfp, esz, lanes, baseRd, baseRn, rm, mask);
            case VMLAS -> AdvSimdLanes.multiplyAccumulateSwapScalarMasked(vfp, esz, lanes, baseRd, baseRn, rm, mask);
            case VQDMLAH -> {
                if (AdvSimdLanes.doublingMultiplyAccumulateScalarMasked(vfp, esz, lanes, baseRd, baseRn, rm, false,
                        false, mask)) {
                    core.fpscr().orQc();
                }
            }
            case VQRDMLAH -> {
                if (AdvSimdLanes.doublingMultiplyAccumulateScalarMasked(vfp, esz, lanes, baseRd, baseRn, rm, false,
                        true, mask)) {
                    core.fpscr().orQc();
                }
            }
            case VQDMLASH -> {
                if (AdvSimdLanes.doublingMultiplyAccumulateScalarMasked(vfp, esz, lanes, baseRd, baseRn, rm, true,
                        false, mask)) {
                    core.fpscr().orQc();
                }
            }
            case VQRDMLASH -> {
                if (AdvSimdLanes.doublingMultiplyAccumulateScalarMasked(vfp, esz, lanes, baseRd, baseRn, rm, true,
                        true, mask)) {
                    core.fpscr().orQc();
                }
            }
        }
        return false;
    }
}
