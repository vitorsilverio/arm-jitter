package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

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
/// `CPS` de 32 bits (mesmo prefixo de hi = {@code 0xF3AF} desta classe, mas com `imod`/`M` ≠ 0 —
/// detectado e devolvido `null` explicitamente abaixo, então cai no UNDEFINED controlado do
/// {@code ThumbDecoder} para `top5=0b11110`), `CLREX` e `SB` (ambos vivem no mesmo espaço de
/// "Miscellaneous control" das barreiras, mas não foram pedidos no Objetivo desta task) e as
/// formas banked de `MRS`/`MSR` (extensão de virtualização ARMv7VE, fora do escopo do GBA/NDS).
public final class Thumb2MiscDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar {@link ArmFeature#WAIT_HINTS}
    /// (gate fino de `WFI`, dentro do `THUMB2` mais amplo já garantido pelo chamador) e
    /// {@link ArmFeature#MEMORY_BARRIERS} (gate de `DMB`/`DSB`/`ISB`), então guarda a arquitetura —
    /// mesmo padrão de {@link ArmDecoder}/{@link ThumbDecoder}, diferente de
    /// {@link Thumb2DataProcessingDecoder} (que não precisa de gate mais fino que `THUMB2`).
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
            // imod!=00 ou M=1: é CPS de 32 bits, fora do escopo desta task (ver javadoc da classe).
            return null;
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
        int barrierOp = (lo >>> BARRIER_OP_SHIFT) & BARRIER_OP_MASK;
        boolean isBarrier = barrierOp == BARRIER_OP_DSB || barrierOp == BARRIER_OP_DMB || barrierOp == BARRIER_OP_ISB;
        if (!isBarrier) {
            // CLREX/SB (mesmo subgrupo) ou reservado: fora do escopo desta task, ver javadoc.
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

    // ── MRS (forma registrador) — A5.3.5 `MRS_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMrs(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & MRS_LO_FIXED_MASK) != MRS_LO_FIXED_VALUE) {
            return null; // MRS_bank/MRS_v7m compartilham o mesmo hi, mas lo diferente.
        }
        boolean spsr = (hi & MRS_R_BIT_IN_HI) != 0;
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MRS,
                rd, -1, -1, spsr ? 1 : 0, true, false, false);
    }

    // ── MSR (forma registrador) — A5.3.5 `MSR_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMsr(int raw, int address, Condition condition, int hi, int lo) {
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
}
