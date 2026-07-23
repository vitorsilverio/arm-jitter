package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;

/// Decodifica o grupo residual "Branches and miscellaneous control" Thumb-2 de 32 bits (B2.5)
/// tratado por esta task: hints (`NOP`/`YIELD`/`WFE`/`WFI`/`SEV`), barreiras de memória
/// (`DMB`/`DSB`/`ISB`) e `MRS`/`MSR` (forma registrador). Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando
/// {@link ArmFeature#THUMB2} está habilitado (o chamador, {@link ThumbDecoder}, só invoca as
/// extensões de 32 bits com essa feature ligada).
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`, hi =
/// primeiro halfword nos bits altos), igual a {@link Thumb2DataProcessingDecoder}. Encodings
/// confirmados contra o QEMU `target/arm/tcg/t32.decode` (seção "Hints, and CPS" / "Miscellaneous
/// control" / `MRS_reg`/`MSR_reg`), que reproduz a ARM DDI 0406C A5.3.
///
/// <p><b>Fora de escopo desta task</b> (deliberado — ver Armadilhas de b2.5-thumb2-misc.md):
/// `SB` (mesmo espaço de "Miscellaneous control" das barreiras/`CLREX`, mas não pedido no
/// Objetivo desta task) e as formas banked de `MRS`/`MSR` (extensão de virtualização ARMv7VE,
/// fora do escopo do GBA/NDS).
///
/// <p><b>B2.7 PR3</b>: `CPS` de 32 bits (antes fora de escopo — mesmo prefixo de hi =
/// {@code 0xF3AF} desta classe, distinguido de hints por `imod`/`M` ≠ 0, ver
/// {@link #decodeHintsOrCps}) e `CLREX` de 32 bits (antes fora de escopo — mesmo espaço
/// "Miscellaneous control" das barreiras, ver {@link #decodeMiscControl}) agora decodificados,
/// reusando {@code IrOp.ChangeProcessorState}/{@code IrOp.ClearExclusive} de B1.4/B1.5 sem
/// duplicar semântica — `LDREX`/`STREX` de 32 bits ficam em {@link Thumb2LoadStoreDecoder} (mesmo
/// prefixo de 7 bits de `LDRD`/`STRD`, não este).
public final class Thumb2MiscDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar {@link ArmFeature#WAIT_HINTS}
    /// (gate fino de `WFI`, dentro do `THUMB2` mais amplo já garantido pelo chamador),
    /// {@link ArmFeature#MEMORY_BARRIERS} (gate de `DMB`/`DSB`/`ISB`),
    /// {@link ArmFeature#EXCLUSIVE_SIZED} (gate de `CLREX`, B2.7 PR3 — MESMA feature que o
    /// ARM clássico usa para `CLREX`/`LDREX*B/H/D`/`STREX*B/H/D`) e
    /// {@link ArmFeature#MODE_CHANGE_INSTRUCTIONS} (gate de `CPS.W`, B2.7 PR3 — MESMA feature do
    /// `CPS` ARM clássico), então guarda a arquitetura — mesmo padrão de
    /// {@link ArmDecoder}/{@link ThumbDecoder}, diferente de {@link Thumb2DataProcessingDecoder}
    /// (que não precisa de gate mais fino que `THUMB2`).
    public Thumb2MiscDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// `hi` fixo do subgrupo "Hints, and CPS" (ARM DDI 0406C A5.3.5) — `1111 0011 1010 1111`.
    private static final int HINTS_AND_CPS_HI = 0xF3AF;
    /// `lo[15:8]` fixo (`1000 0000`) que, dentro do subgrupo acima, seleciona a forma "hint"
    /// (`imod==00 && M==0`, ver `HINTS_TOP_BYTE_MASK`) em vez de `CPS`.
    private static final int HINTS_TOP_BYTE = 0x80;
    private static final int TOP_BYTE_SHIFT = 8;
    private static final int TOP_BYTE_MASK = 0xFF;
    /// `lo[7:0]` seleciona o hint específico dentro do subgrupo. Qualquer valor fora da tabela
    /// abaixo é "reserved hint, behaves as nop" (mesmo comentário do QEMU t32.decode).
    private static final int HINT_SELECTOR_MASK = 0xFF;
    private static final int HINT_NOP = 0x00;
    private static final int HINT_YIELD = 0x01;
    private static final int HINT_WFE = 0x02;
    private static final int HINT_WFI = 0x03;
    private static final int HINT_SEV = 0x04;

    /// `hi` fixo do subgrupo "Miscellaneous control" (barreiras + `CLREX`/`SB`) — `1111 0011 1011
    /// 1111`.
    private static final int MISC_CONTROL_HI = 0xF3BF;
    /// `lo[15:8]` fixo (`1000 1111`) de todo o subgrupo "Miscellaneous control".
    private static final int MISC_CONTROL_TOP_BYTE = 0x8F;
    /// `lo[7:4]` distingue a barreira; `lo[3:0]` é o campo `option` (ignorado — ver
    /// {@code IrOp.MemoryBarrier}).
    private static final int BARRIER_OP_SHIFT = 4;
    private static final int BARRIER_OP_MASK = 0xF;
    private static final int BARRIER_OPTION_MASK = 0xF;
    private static final int BARRIER_OP_DSB = 0x4;
    private static final int BARRIER_OP_DMB = 0x5;
    private static final int BARRIER_OP_ISB = 0x6;

    /// `hi` do subgrupo `MRS_reg` com `r=0` (CPSR); `r=1` (SPSR) soma {@link #MRS_R_BIT_IN_HI}.
    /// `r` é o bit menos significativo do terceiro nibble de `hi` (bits 7:4 = `111r`) — bit 4,
    /// não bit 8 (esse é parte do prefixo fixo `0011` do segundo nibble, bits 11:8).
    private static final int MRS_HI_BASE = 0xF3EF;
    private static final int MRS_R_BIT_IN_HI = 1 << 4;
    /// `lo[15:12]` fixo (`1000`) e `lo[7:0]` fixo (`0000 0000`) de `MRS_reg`; `lo[11:8]` = `rd`.
    private static final int MRS_LO_FIXED_MASK = 0xF0FF;
    private static final int MRS_LO_FIXED_VALUE = 0x8000;
    private static final int MRS_RD_SHIFT = 8;
    private static final int MRS_RD_MASK = 0xF;

    /// `hi[8:4]` fixo (`1000 0` com `r` no bit 4) de `MSR_reg`; `hi[3:0]` = `rn` (registrador
    /// geral cujo valor é escrito no PSR).
    private static final int MSR_HI_MASK = 0xFFE0;
    private static final int MSR_HI_VALUE = 0xF380;
    private static final int MSR_R_BIT_IN_HI = 1 << 4;
    private static final int MSR_RN_MASK = 0xF;
    /// `lo[15:12]` fixo (`1000`) e `lo[7:0]` fixo (`0000 0000`) de `MSR_reg`; `lo[11:8]` =
    /// `mask` (campo de campos PSR, `_fsxc`).
    private static final int MSR_LO_FIXED_MASK = 0xF0FF;
    private static final int MSR_LO_FIXED_VALUE = 0x8000;
    private static final int MSR_FIELD_MASK_SHIFT = 8;
    private static final int MSR_FIELD_MASK_MASK = 0xF;

    /// Bit empacotado em {@code DecodedInstruction.immediate()}/{@code destinationRegister()} das
    /// formas `MRS`/`MSR` (mesma convenção do {@code ArmDecoder} clássico): bit 4 = SPSR (vs CPSR).
    private static final int PSR_SPSR_BIT = 0x10;
    private static final int PSR_FIELD_MASK_BITS = 0xF;

    /// `CPS` de 32 bits (ARM DDI 0406C A5.3.5, `&cps`): dentro do subgrupo "Hints, and CPS" (mesmo
    /// `hi` de {@link #HINTS_AND_CPS_HI}), `lo = 1000 0 imod:2 M:1 A:1 I:1 F:1 mode:5` — os campos
    /// vivem em posições DIFERENTES de `MRS`/`MSR` (que usam `lo[15:12]`/`lo[11:8]` fixos), então
    /// precisam de shifts próprios.
    private static final int CPS_IMOD_SHIFT = 9;
    private static final int CPS_IMOD_MASK = 0x3;
    private static final int CPS_MODE_CHANGE_BIT = 8;
    private static final int CPS_A_BIT = 7;
    private static final int CPS_I_BIT = 6;
    private static final int CPS_F_BIT = 5;
    private static final int CPS_MODE_MASK = 0x1F;

    /// Empacotamento de `DecodedInstruction.immediate()` para `InstructionKind.CPS` — MESMA
    /// convenção que `ArmDecoder` usa para o `CPS` ARM clássico (`StandardIrBuilder` decodifica os
    /// dois encodings com o mesmo código): `imod` nos 2 bits baixos, depois `M`/`A`/`I`/`F`/`mode`.
    private static final int CPS_PACKED_MODE_CHANGE_SHIFT = 2;
    private static final int CPS_PACKED_A_SHIFT = 3;
    private static final int CPS_PACKED_I_SHIFT = 4;
    private static final int CPS_PACKED_F_SHIFT = 5;
    private static final int CPS_PACKED_MODE_SHIFT = 6;

    /// `MRS_v7m`/`MSR_v7m` (perfil M, B7.4): compartilham o `hi` de `MRS_reg`/`MSR_reg`, mas `lo`
    /// muda de significado — `lo[15:12]=1000` fixo, `lo[11:8]`=Rd (MRS) ou máscara de campos (MSR,
    /// não modelada) e `lo[7:0]`=SYSm (número do registrador especial, não precisa ser zero como no
    /// A-profile). Só interpretado assim quando {@link ArmFeature#M_PROFILE} está ativo.
    private static final int V7M_LO_FIXED_MASK = 0xF000;
    private static final int V7M_LO_FIXED_VALUE = 0x8000;
    private static final int V7M_SYSM_MASK = 0xFF;

    /// `CLREX` de 32 bits (ARM DDI 0406C A8.8.30): encoding TOTALMENTE fixo (nenhum campo de
    /// registrador) dentro do subgrupo "Miscellaneous control" — `lo = 1000 1111 0010 1111`, o
    /// MESMO `hi` de {@link #MISC_CONTROL_HI} que as barreiras `DMB`/`DSB`/`ISB` já usam.
    private static final int CLREX_LO = 0x8F2F;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        int hi = raw >>> 16;
        int lo = raw & 0xFFFF;

        if (hi == HINTS_AND_CPS_HI) {
            return decodeHintsOrCps(raw, address, condition, lo);
        }
        if (hi == MISC_CONTROL_HI) {
            return decodeMiscControl(raw, address, condition, lo);
        }
        if (hi == MRS_HI_BASE || hi == (MRS_HI_BASE | MRS_R_BIT_IN_HI)) {
            return decodeMrs(raw, address, condition, hi, lo);
        }
        if ((hi & MSR_HI_MASK) == MSR_HI_VALUE) {
            return decodeMsr(raw, address, condition, hi, lo);
        }
        return null;
    }

    // ── Hints (NOP/YIELD/WFE/WFI/SEV) — A5.3.5, "Hints, and CPS" ────────────────────────────

    private DecodedInstruction decodeHintsOrCps(int raw, int address, Condition condition, int lo) {
        if (((lo >>> TOP_BYTE_SHIFT) & TOP_BYTE_MASK) != HINTS_TOP_BYTE) {
            // imod!=00 ou M=1: é CPS de 32 bits (B2.7 PR3).
            return decodeCps32(raw, address, condition, lo);
        }
        int selector = lo & HINT_SELECTOR_MASK;
        return switch (selector) {
            case HINT_WFI -> decodeWaitForInterrupt(raw, address, condition);
            // YIELD/SEV (e qualquer seletor reservado, incl. NOP canônico) não têm efeito
            // observável neste core: mesmo tratamento que YIELD/SEV/NOP já recebem em ARM
            // clássico (B1.5) — lá eles nunca ganharam decode próprio porque o encoding ARM já é,
            // por acidente, um MSR(registrador)->CPSR com máscara de campo vazia. Aqui o encoding
            // Thumb-2 é dedicado (não alias de MSR), então reproduzimos o mesmo no-op explicitamente
            // via o mesmo IrOp.PsrTransfer (sem duplicar semântica, só o decode é novo).
            default -> noOpHint(raw, address, condition);
        };
    }

    /// `WFI`: exige {@link ArmFeature#WAIT_HINTS} para virar `WAIT_FOR_INTERRUPT` de verdade —
    /// espelha exatamente a regra do `WFI` ARM clássico em `ArmDecoder` (B1.5): sem a feature,
    /// UNDEFINED explícito em vez de cair no no-op genérico, por consistência de gating com o
    /// resto do decoder (mesmo raciocínio usado para LDREX/STREX/CLREX).
    private DecodedInstruction decodeWaitForInterrupt(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.WAIT_HINTS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.WAIT_FOR_INTERRUPT, -1, -1, -1, 0, false, false, false);
    }

    /// `CPS`/`CPSIE`/`CPSID` de 32 bits (B2.7 PR3): MESMO empacotamento de `immediate` que
    /// `ArmDecoder` usa para o `CPS` ARM clássico (`imod | (M&lt;&lt;2) | (A&lt;&lt;3) |
    /// (I&lt;&lt;4) | (F&lt;&lt;5) | (mode&lt;&lt;6)`) — `StandardIrBuilder` já sabe interpretar
    /// esse formato para os dois encodings, sem duplicar semântica (`IrOp.ChangeProcessorState`,
    /// B1.5). Gate {@link ArmFeature#MODE_CHANGE_INSTRUCTIONS}, igual ao ARM clássico.
    private DecodedInstruction decodeCps32(int raw, int address, Condition condition, int lo) {
        if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int imod = (lo >>> CPS_IMOD_SHIFT) & CPS_IMOD_MASK;
        int modeChange = (lo >>> CPS_MODE_CHANGE_BIT) & 1;
        int a = (lo >>> CPS_A_BIT) & 1;
        int i = (lo >>> CPS_I_BIT) & 1;
        int f = (lo >>> CPS_F_BIT) & 1;
        int mode = lo & CPS_MODE_MASK;
        int packed = imod
                | (modeChange << CPS_PACKED_MODE_CHANGE_SHIFT)
                | (a << CPS_PACKED_A_SHIFT)
                | (i << CPS_PACKED_I_SHIFT)
                | (f << CPS_PACKED_F_SHIFT)
                | (mode << CPS_PACKED_MODE_SHIFT);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.CPS, -1, -1, -1, packed, false, false, false);
    }

    private DecodedInstruction noOpHint(int raw, int address, Condition condition) {
        // MSR(imediato)->CPSR com máscara de campo vazia: nenhum campo do PSR é escrito,
        // idêntico ao caminho que NOP/YIELD/SEV já usam implicitamente em ARM clássico.
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR,
                0, -1, -1, 0, true, false, false);
    }

    // ── Barreiras de memória: DMB/DSB/ISB — A5.3.5, "Miscellaneous control" ─────────────────

    private DecodedInstruction decodeMiscControl(int raw, int address, Condition condition, int lo) {
        if (((lo >>> TOP_BYTE_SHIFT) & TOP_BYTE_MASK) != MISC_CONTROL_TOP_BYTE) {
            return null;
        }
        if (lo == CLREX_LO) {
            return decodeClearExclusive(raw, address, condition);
        }
        int barrierOp = (lo >>> BARRIER_OP_SHIFT) & BARRIER_OP_MASK;
        boolean isBarrier = barrierOp == BARRIER_OP_DSB || barrierOp == BARRIER_OP_DMB || barrierOp == BARRIER_OP_ISB;
        if (!isBarrier) {
            // SB (mesmo subgrupo) ou reservado: fora do escopo desta task, ver javadoc.
            return null;
        }
        if (!architecture.has(ArmFeature.MEMORY_BARRIERS)) {
            // Mesma convenção de gating explícito do WFI/LDREX/STREX/CLREX: UNDEFINED direto em
            // vez de deixar a chamada cair silenciosamente no fallback do ThumbDecoder.
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int option = lo & BARRIER_OPTION_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MEMORY_BARRIER, -1, -1, -1, option, false, false, false);
    }

    /// `CLREX` de 32 bits (B2.7 PR3): abre o monitor de exclusividade — MESMA `IrOp.ClearExclusive`
    /// (B1.4) que o `CLREX` ARM clássico usa, sem duplicar semântica. Gate
    /// {@link ArmFeature#EXCLUSIVE_SIZED}, igual ao ARM clássico (`LDREX*B/H/D`/`STREX*B/H/D`
    /// compartilham a mesma feature — ver `Thumb2LoadStoreDecoder`).
    private DecodedInstruction decodeClearExclusive(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.EXCLUSIVE_SIZED)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.CLEAR_EXCLUSIVE, -1, -1, -1, 0, false, false, false);
    }

    // ── MRS (forma registrador) — A5.3.5 `MRS_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMrs(int raw, int address, Condition condition, int hi, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE)) {
            return decodeMrsMProfile(raw, address, condition, lo);
        }
        if ((lo & MRS_LO_FIXED_MASK) != MRS_LO_FIXED_VALUE) {
            return null; // MRS_bank/MRS_v7m compartilham o mesmo hi, mas lo diferente.
        }
        boolean spsr = (hi & MRS_R_BIT_IN_HI) != 0;
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MRS,
                rd, -1, -1, spsr ? 1 : 0, true, false, false);
    }

    /// `MRS Rd, <SYSm>` do perfil M (B7.4): `lo[15:12]=1000` fixo, `lo[11:8]`=Rd, `lo[7:0]`=SYSm.
    private DecodedInstruction decodeMrsMProfile(int raw, int address, Condition condition, int lo) {
        if ((lo & V7M_LO_FIXED_MASK) != V7M_LO_FIXED_VALUE) {
            return null;
        }
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        int sysm = lo & V7M_SYSM_MASK;
        if (!isSupportedSysm(sysm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MPROFILE_MRS, rd, -1, -1, sysm, true, false, false);
    }

    /// SYSm implementados nesta task (ARMv7-M ARM B5.1.1). BASEPRI/BASEPRI_MAX/FAULTMASK exigem o
    /// perfil M **completo** ({@link ArmFeature#M_FAULT_MASKING}, ausente no ARMv6-M) — sem ela, o
    /// SYSm vira UNDEFINED direto no decode (mesma convenção de WFI/CLREX). Os demais SYSm (4,
    /// 10-15, 21+) não existem → UNDEFINED.
    private boolean isSupportedSysm(int sysm) {
        return switch (sysm) {
            case MProfileExceptionModel.SYSM_APSR, MProfileExceptionModel.SYSM_IAPSR,
                 MProfileExceptionModel.SYSM_EAPSR, MProfileExceptionModel.SYSM_XPSR,
                 MProfileExceptionModel.SYSM_IPSR, MProfileExceptionModel.SYSM_EPSR,
                 MProfileExceptionModel.SYSM_IEPSR, MProfileExceptionModel.SYSM_MSP,
                 MProfileExceptionModel.SYSM_PSP, MProfileExceptionModel.SYSM_PRIMASK,
                 MProfileExceptionModel.SYSM_CONTROL -> true;
            case MProfileExceptionModel.SYSM_BASEPRI, MProfileExceptionModel.SYSM_BASEPRI_MAX,
                 MProfileExceptionModel.SYSM_FAULTMASK -> architecture.has(ArmFeature.M_FAULT_MASKING);
            default -> false;
        };
    }

    // ── MSR (forma registrador) — A5.3.5 `MSR_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMsr(int raw, int address, Condition condition, int hi, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE)) {
            return decodeMsrMProfile(raw, address, condition, hi, lo);
        }
        if ((lo & MSR_LO_FIXED_MASK) != MSR_LO_FIXED_VALUE) {
            return null; // MSR_bank/MSR_v7m compartilham o mesmo hi, mas lo diferente.
        }
        boolean spsr = (hi & MSR_R_BIT_IN_HI) != 0;
        int sourceRegister = hi & MSR_RN_MASK;
        int fieldMask = (lo >>> MSR_FIELD_MASK_SHIFT) & MSR_FIELD_MASK_MASK;
        int packed = (spsr ? PSR_SPSR_BIT : 0) | (fieldMask & PSR_FIELD_MASK_BITS);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR,
                -1, sourceRegister, -1, packed, false, false, false);
    }

    /// `MSR <SYSm>, Rn` do perfil M (B7.4): `lo[15:12]=1000` fixo, `lo[7:0]`=SYSm, `hi[3:0]`=Rn. A
    /// máscara de campos (`lo[11:8]`) não é modelada — o próprio SYSm já decide o efeito da escrita.
    private DecodedInstruction decodeMsrMProfile(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & V7M_LO_FIXED_MASK) != V7M_LO_FIXED_VALUE) {
            return null;
        }
        int rn = hi & MSR_RN_MASK;
        int sysm = lo & V7M_SYSM_MASK;
        if (!isSupportedSysm(sysm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MPROFILE_MSR, -1, rn, -1, sysm, false, false, false);
    }
}
