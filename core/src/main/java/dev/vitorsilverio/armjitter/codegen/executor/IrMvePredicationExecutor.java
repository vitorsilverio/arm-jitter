package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.core.VprRegister;
import dev.vitorsilverio.armjitter.ir.MveMoveOp;
import dev.vitorsilverio.armjitter.ir.MvePredicationOp;

/// Executa a predicação MVE (perfil M, B16, MVE/Helium) da IR interpretada: `VPST`/`VPNOT`/`VPSEL`,
/// tail-predication (`LCTP`/`VCTP`), avanço pós-instrução de `VPR`/`ECI`, `VMSR`/`VMRS` do `VPR` e as
/// comparações que gravam `VPR.P0`.
///
/// Sem estado: os records de {@link MvePredicationOp} chamam os métodos estáticos direto (task
/// E15.8), e a JVM só carrega esta classe na primeira op MVE.
public final class IrMvePredicationExecutor {
    private IrMvePredicationExecutor() {
    }

    /// Bytes de um registrador `Q` (16): largura do predicado `VPR.P0`, um bit por byte.
    private static final int MVE_VECTOR_BYTES = 16;

    /// `VPST` (perfil M, B16.2, MVE/Helium): grava `VPR.MASK01`/`MASK23` via
    /// {@link MveVptState#vpstMask} — antes, valida `ECI` (`mve_eci_check` real, roda para TODA
    /// instrução MVE beatwise): reservado faulta em vez de gravar (ver {@link IrMveSupport#faultInvstate}).
    ///
    /// @return `true` quando faultou (PC mudou) — {@link
    ///         dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} usa isto para pular o
    ///         {@link MvePredicationOp.AdvanceVpt} seguinte no mesmo bloco: o QEMU real nunca chega a
    ///         `mve_update_and_store_eci`/`mve_advance_vpt` quando `mve_eci_check` falha —
    ///         translation-time short-circuit, não comportamento de pipeline.
    public static boolean executeVpst(ArmCore core, MvePredicationOp.Vpst op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        core.vpr().setValue(MveVptState.vpstMask(core.vpr().value(), eci, op.mask()));
        return false;
    }

    /// `VPNOT` (perfil M, B16.2, MVE/Helium): inverte `VPR.P0` nas lanes correspondentes aos
    /// beats já executados — `vpr ^= eciMask` afeta só os 16 bits baixos (campo `P0`, deslocamento
    /// `0`), o MESMO idioma que {@link MveVptState#advance} usa para o `invMask`. **Não pôde ser
    /// confirmado byte a byte contra `HELPER(mve_vpnot)` do QEMU real nesta rodada de spec** (não
    /// encontrado via `WebFetch`/`WebSearch` dentro do orçamento da sessão) — derivado do idioma
    /// idêntico já usado por `mve_advance_vpt` no MESMO arquivo; ver `## Resultado` da task B16.2.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public static boolean executeVpnot(ArmCore core, MvePredicationOp.Vpnot op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int itState = core.cpsr().itState();
        core.vpr().setValue(core.vpr().value() ^ MveVptState.eciMask(itState));
        return false;
    }

    /// `VPSEL` (perfil M, B16.2, MVE/Helium): seleciona lane a lane (byte a byte — `@2op_nosz`
    /// não tem campo `size`) entre `Qn` e `Qm` conforme `VPR.P0`, escrevendo em `Qd` só nos bytes
    /// que {@link MveVptState#elementMask} marca como ativos (`LTPSIZE` corrente, ver
    /// {@link FpscrRegister#ltpsize()}). **Semântica derivada, não confirmada byte a byte
    /// contra `HELPER(mve_vpsel)` do QEMU real** (mesma limitação de {@link #executeVpnot}) — a
    /// derivação segue o pseudocódigo arquitetural (`Armv8-M ARM` B4.24): seleção sempre por byte,
    /// write-enable pelo `elementMask` corrente. Ver `## Resultado` da task B16.2.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public static boolean executeVpsel(ArmCore core, MvePredicationOp.Vpsel op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int p0 = (vpr & VprRegister.P0_MASK) >>> VprRegister.P0_SHIFT;
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        for (int byteIndex = 0; byteIndex < 16; byteIndex++) {
            if (((mask >>> byteIndex) & 1) == 0) {
                continue;
            }
            boolean selectN = ((p0 >>> byteIndex) & 1) != 0;
            long value = vfp.element(selectN ? op.qn() : op.qm(), byteIndex, 0);
            vfp.setElement(op.qd(), byteIndex, 0, value);
        }
        return false;
    }

    /// `LCTP` (perfil M, B16.15, MVE): restaura `FPSCR.LTPSIZE = 4`, a tail-predication inativa.
    /// É TUDO o que o QEMU real faz (`trans_LCTP`: sem cache de branch a limpar).
    public static void executeLctp(ArmCore core, MvePredicationOp.LoopClearTailPredication op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        core.fpscr().setLtpsize(FpscrRegister.LTPSIZE_NONE);
    }

    /// `VCTP.<size> Rn` (perfil M, B16.15, MVE, beatwise) — `trans_VCTP` + `HELPER(mve_vctp)` do
    /// QEMU: `masklen = Rn <= (16 >> size) ? Rn << size : 16` (comparação não-assinada), e
    /// `VPR.P0 = (P0 & ~eciMask) | (bits[masklen-1:0] & elementMask & eciMask)` — só os beats ainda
    /// não executados são reescritos. O avanço de `ECI`/`VPT` fica a cargo do {@code AdvanceVpt}
    /// que o lifter emite depois de toda instrução beatwise.
    ///
    /// @return `true` quando faultou (`ECI` reservado, ver {@link #executeVpst}).
    public static boolean executeVctp(ArmCore core, MvePredicationOp.Vctp op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int itState = core.cpsr().itState();
        if (MveVptState.isReservedEci(core.cpsr().eci())) {
            return IrMveSupport.faultInvstate(core);
        }
        int remaining = core.register(op.rn());
        int fullElements = MVE_VECTOR_BYTES >>> op.size();
        int maskLength = Integer.compareUnsigned(remaining, fullElements) <= 0
                ? remaining << op.size()
                : MVE_VECTOR_BYTES;
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int eciMask = MveVptState.eciMask(itState);
        int tailMask = maskLength == 0 ? 0 : (1 << maskLength) - 1;
        core.vpr().setValue((vpr & ~eciMask) | (tailMask & mask & eciMask));
        return false;
    }

    /// Avanço pós-instrução do `VPR`/`ECI` (perfil M, B16.2, MVE/Helium) — {@link
    /// MveVptState#advance}. Emitido incondicionalmente (G4) depois de toda instrução MVE
    /// beatwise; {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} pula esta
    /// chamada quando a instrução MVE anterior no MESMO bloco já mudou o PC (fault de `ECI`
    /// reservado — ver {@link #executeVpst}), nunca quando ela só zerou o `elementMask` inteiro
    /// (predicação total, que AINDA avança, ver Javadoc de {@link MvePredicationOp.AdvanceVpt}).
    public static void executeAdvanceVpt(ArmCore core, MvePredicationOp.AdvanceVpt op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        MveVptState.MveVptAdvance result = MveVptState.advance(core.vpr().value(), core.cpsr().itState());
        core.vpr().setValue(result.vpr());
        core.cpsr().setItState(result.itState());
    }

    /// `VMSR`/`VMRS` com `reg=12` (perfil M, B16.2, MVE/Helium): transfere o `VPR` bruto de/para
    /// `armRegister` — armazenamento puro, sem aliasing (ver Javadoc de {@link MvePredicationOp.VprTransfer}).
    /// NÃO avança `VPR`/`ECI` ({@link #executeAdvanceVpt}): `VMSR_VMRS` nunca é beatwise.
    public static void executeVprTransfer(ArmCore core, MvePredicationOp.VprTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.read()) {
            core.setRegister(op.armRegister(), core.vpr().value());
        } else {
            core.vpr().setValue(core.register(op.armRegister()));
        }
    }

    /// Avanço pós-instrução SÓ do `ECI` (B16.5) — {@link MveVptState#advanceEciOnly}. Usado só por
    /// {@link MveMoveOp.InterleavedLoadStore} (`VLD2`/`VLD4`/`VST2`/`VST4`), mesmo gate de
    /// `pcChanged` de {@link #executeAdvanceVpt} no chamador.
    public static void executeAdvanceEci(ArmCore core, MvePredicationOp.AdvanceEci op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        core.cpsr().setItState(MveVptState.advanceEciOnly(core.cpsr().itState()));
    }

    /// `Rm == 15` do `VCMP*_scalar`/`VCMP*_fp_scalar` (perfil M, B16.8): "constante zero" —
    /// `do_vcmp_scalar` real (`target/arm/tcg/translate-mve.c`): `if (a->rm == 15) rm =
    /// tcg_constant_i32(0);`. Resolvido aqui (execução), não no decode: `Rm == 15` É um encoding
    /// válido, ao contrário de `Rm == 13` (recusado no decode, ver
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2MveComparisonDecoder}).
    private static final int VCMP_SCALAR_ZERO_ENCODING = 15;

    /// `DO_VCMP_S`/`DO_VCMP_U` verbatim (`target/arm/tcg/mve_helper.c`): `EQ`/`NE` comparam os
    /// bits crus (zero-extendidos por {@link VfpRegisters#element}, irrelevante o sinal);
    /// `GE`/`LT`/`GT`/`LE` COM SINAL ({@link AdvSimdLanes#signExtend}); `CS`/`HI` SEM SINAL
    /// ({@link Long#compareUnsigned}).
    private static boolean evaluateMveIntCompare(
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition condition, long a, long b, int esz) {
        return switch (condition) {
            case EQ -> a == b;
            case NE -> a != b;
            case GE -> AdvSimdLanes.signExtend(a, esz) >= AdvSimdLanes.signExtend(b, esz);
            case LT -> AdvSimdLanes.signExtend(a, esz) < AdvSimdLanes.signExtend(b, esz);
            case GT -> AdvSimdLanes.signExtend(a, esz) > AdvSimdLanes.signExtend(b, esz);
            case LE -> AdvSimdLanes.signExtend(a, esz) <= AdvSimdLanes.signExtend(b, esz);
            case CS -> Long.compareUnsigned(a, b) >= 0;
            case HI -> Long.compareUnsigned(a, b) > 0;
        };
    }

    /// `vfcmpeq`/`vfcmpne`/`vfcmpge`/`vfcmplt`/`vfcmpgt`/`vfcmple` (`target/arm/tcg/mve_helper.c`)
    /// — `CS`/`HI` nunca chegam aqui (sem forma `_fp`, ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.advsimd.MveCompareCondition}).
    private static boolean evaluateMveFpCompare(
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition condition, float a, float b) {
        return switch (condition) {
            case EQ -> a == b;
            case NE -> a != b;
            case GE -> a >= b;
            case LT -> a < b;
            case GT -> a > b;
            case LE -> a <= b;
            case CS, HI -> throw new IllegalStateException(
                    "CS/HI não têm forma _fp (ver MveCompareCondition)");
        };
    }

    /// Reinterpreta os `1 << esz` bytes baixos de `bits` como ponto flutuante — bit CAST, não
    /// conversão numérica (`(TYPE)rm` do QEMU real É um cast C entre `uint16_t`/`uint32_t` e
    /// `float16`/`float32`, que na própria representação do QEMU SÃO `uint16_t`/`uint32_t` —
    /// nenhuma conversão de valor ocorre).
    private static float mveCompareFloatValue(long bits, int esz) {
        return esz == 1 ? AdvSimdLanes.halfToFloat(bits) : Float.intBitsToFloat((int) bits);
    }

    /// `DO_VCMP`/`DO_VCMP_SCALAR`/`DO_VCMP_FP`/`DO_VCMP_FP_SCALAR` verbatim, a parte comum às 4
    /// macros (`target/arm/tcg/mve_helper.c`): computa `beatpred` (1 bit por BYTE, replicado pelos
    /// `1 << esz` bytes de cada lane cuja comparação deu verdadeiro — "Comparison sets 0/1 bits
    /// for each byte in the element"), aplica `elementMask` e mescla em `VPR.P0` respeitando
    /// `eciMask` — comentário literal do arquivo real: "P0 bits for non-executed beats (where
    /// eci_mask is 0) are unchanged. P0 bits for predicated lanes in executed beats (where mask is
    /// 0) are 0. P0 bits otherwise are updated with the results of the comparisons. We must also
    /// keep unchanged the MASK fields at the top of v7m.vpr" — os bits 16-31 (`MASK01`/`MASK23`)
    /// ficam intactos porque `eciMask`/`beatpred` só ocupam os 16 bits baixos (`P0`).
    private static int mergeCompareResultIntoP0(int vpr, int itState, int elementMask, int beatpred) {
        int eciMask = MveVptState.eciMask(itState) & 0xFFFF;
        int maskedBeatpred = beatpred & elementMask;
        return (vpr & ~eciMask) | (maskedBeatpred & eciMask);
    }

    /// `VCMPEQ`/`VCMPNE`/`VCMPGE`/`VCMPLT`/`VCMPGT`/`VCMPLE`/`VCMPCS`/`VCMPHI` e as 6 formas `_fp`
    /// vetor×vetor (perfil M, B16.8, MVE/Helium): compara `Qn`/`Qm` lane a lane, escreve `VPR.P0`
    /// (ver {@link #mergeCompareResultIntoP0}). **Não chama {@link MveVptState#advance}** — o
    /// avanço é o {@link MvePredicationOp.AdvanceVpt} genérico que {@code StandardIrBuilder} sempre emite
    /// LOGO DEPOIS desta op (mesmo gancho de {@link IrMveIntegerExecutor#executeMveVector2Op}); quando `op.mask() !=
    /// 0`, um {@link MvePredicationOp.Vpst} adicional (emitido DEPOIS do `AdvanceVpt` pelo `StandardIrBuilder`)
    /// estabelece o `VPT`, reproduzindo a ordem exata do QEMU real (`do_vcmp`: o helper já chama
    /// `mve_advance_vpt` internamente; só DEPOIS, fora do helper, `gen_vpst` roda condicionado a
    /// `a->mask`) — inverter esta ordem corromperia a PRÓPRIA abertura do `VPT` (a máscara recém-
    /// -aberta seria consumida pelo avanço da MESMA instrução, em vez de só pelas seguintes).
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public static boolean executeMveVectorCompare(ArmCore core, MvePredicationOp.VectorCompare op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int elementMask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = 16 / elementBytes;
        int laneByteMask = (elementBytes == 4) ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int beatpred = 0;
        for (int lane = 0; lane < lanes; lane++) {
            long a = vfp.element(op.qn(), lane, esz);
            long b = vfp.element(op.qm(), lane, esz);
            boolean result = op.floatingPoint()
                    ? evaluateMveFpCompare(op.compareCondition(), mveCompareFloatValue(a, esz), mveCompareFloatValue(b, esz))
                    : evaluateMveIntCompare(op.compareCondition(), a, b, esz);
            if (result) {
                beatpred |= laneByteMask << (lane * elementBytes);
            }
        }
        core.vpr().setValue(mergeCompareResultIntoP0(vpr, itState, elementMask, beatpred));
        return false;
    }

    /// Forma escalar (vetor × GPR broadcast) de {@link #executeMveVectorCompare} — mesma escrita
    /// em `P0`, comparando cada lane de `Qn` contra o MESMO valor de `Rm` truncado ao tamanho do
    /// elemento (`(TYPE)rm` real). `Rm == 15` é "constante zero" (ver
    /// {@link #VCMP_SCALAR_ZERO_ENCODING}); `Rm == 13` já foi recusado no decode.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public static boolean executeMveVectorCompareScalar(ArmCore core, MvePredicationOp.VectorCompareScalar op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int elementMask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = 16 / elementBytes;
        int laneByteMask = (elementBytes == 4) ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        long rawRm = op.rm() == VCMP_SCALAR_ZERO_ENCODING ? 0L : Integer.toUnsignedLong(core.register(op.rm()));
        long rm = AdvSimdLanes.truncate(rawRm, esz);
        int beatpred = 0;
        for (int lane = 0; lane < lanes; lane++) {
            long a = vfp.element(op.qn(), lane, esz);
            boolean result = op.floatingPoint()
                    ? evaluateMveFpCompare(op.compareCondition(), mveCompareFloatValue(a, esz), mveCompareFloatValue(rm, esz))
                    : evaluateMveIntCompare(op.compareCondition(), a, rm, esz);
            if (result) {
                beatpred |= laneByteMask << (lane * elementBytes);
            }
        }
        core.vpr().setValue(mergeCompareResultIntoP0(vpr, itState, elementMask, beatpred));
        return false;
    }
}
