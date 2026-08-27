package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.CpuMode;

/// Resolve o par `(r, sysm)` de `MRS`/`MSR` bancado (B9.8.5, ARM DDI 0487 seção F5.2.3 — `r=0`
/// seleciona registrador geral do modo alvo, `r=1` seleciona o `SPSR` do modo alvo) para o
/// modo/registrador alvo, e empacota o resultado num único `int` guardado em
/// `DecodedInstruction#immediate()` — layout compartilhado entre {@link ArmDecoder} (A32) e
/// {@link Thumb2MiscDecoder} (T32) na hora de decodificar, e desempacotado por
/// `dev.vitorsilverio.armjitter.ir.StandardIrBuilder` na hora de montar `IrOp.MrsBank`/`MsrBank`.
///
/// Tabela conferida contra `target/arm/tcg/translate.c` real do QEMU
/// (`msr_banked_access_decode`) — ver `b9.8-plano-hyp-monitor-32bit.md` para a tabela completa e a
/// fonte. `ELR_hyp` (`sysm=0x1e`, `r=0`) é um caso especial: não é um `r14` bancado (Hyp mode não
/// banca `LR` — ver `ArmCore#elrHyp`), então ganha um bit próprio em vez de um índice de
/// registrador.
public final class BankedRegisterSysm {
    private BankedRegisterSysm() {
    }

    /// Bits\[3:0\] do valor empacotado: `CpuMode#ordinal()` do modo alvo.
    public static final int MODE_MASK = 0xF;
    /// Deslocamento/máscara do índice do registrador bancado (8-14), quando nem
    /// {@link #ELR_HYP_BIT} nem {@link #SPSR_BIT} estão setados.
    public static final int REGISTER_SHIFT = 4;
    public static final int REGISTER_MASK = 0xF;
    /// Alvo é `ELR_hyp` (registrador à parte, fora de R0-R15) em vez de um registrador bancado comum.
    public static final int ELR_HYP_BIT = 1 << 8;
    /// Alvo é o `SPSR` do modo, em vez de um registrador geral.
    public static final int SPSR_BIT = 1 << 9;

    /// Resolve `(r, sysm)` para o valor empacotado, ou `-1` quando o `sysm` não é alocado (nenhuma
    /// entrada real da tabela do ARM DDI 0487 F5.2.3).
    public static int resolve(boolean r, int sysm) {
        return r ? resolveSpsr(sysm) : resolveGeneralRegister(sysm);
    }

    private static int resolveSpsr(int sysm) {
        CpuMode mode = switch (sysm) {
            case 0xe -> CpuMode.FIQ;
            case 0x10 -> CpuMode.IRQ;
            case 0x12 -> CpuMode.SUPERVISOR;
            case 0x14 -> CpuMode.ABORT;
            case 0x16 -> CpuMode.UNDEFINED;
            case 0x1c -> CpuMode.MONITOR;
            case 0x1e -> CpuMode.HYP;
            default -> null;
        };
        return mode == null ? -1 : (mode.ordinal() | SPSR_BIT);
    }

    private static int resolveGeneralRegister(int sysm) {
        return switch (sysm) {
            case 0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6 ->
                    CpuMode.USER.ordinal() | ((sysm + 8) << REGISTER_SHIFT);
            case 0x8, 0x9, 0xa, 0xb, 0xc, 0xd, 0xe ->
                    CpuMode.FIQ.ordinal() | (sysm << REGISTER_SHIFT);
            case 0x10 -> CpuMode.IRQ.ordinal() | (14 << REGISTER_SHIFT);
            case 0x11 -> CpuMode.IRQ.ordinal() | (13 << REGISTER_SHIFT);
            case 0x12 -> CpuMode.SUPERVISOR.ordinal() | (14 << REGISTER_SHIFT);
            case 0x13 -> CpuMode.SUPERVISOR.ordinal() | (13 << REGISTER_SHIFT);
            case 0x14 -> CpuMode.ABORT.ordinal() | (14 << REGISTER_SHIFT);
            case 0x15 -> CpuMode.ABORT.ordinal() | (13 << REGISTER_SHIFT);
            case 0x16 -> CpuMode.UNDEFINED.ordinal() | (14 << REGISTER_SHIFT);
            case 0x17 -> CpuMode.UNDEFINED.ordinal() | (13 << REGISTER_SHIFT);
            case 0x1c -> CpuMode.MONITOR.ordinal() | (14 << REGISTER_SHIFT);
            case 0x1d -> CpuMode.MONITOR.ordinal() | (13 << REGISTER_SHIFT);
            case 0x1e -> CpuMode.HYP.ordinal() | ELR_HYP_BIT;
            case 0x1f -> CpuMode.HYP.ordinal() | (13 << REGISTER_SHIFT);
            default -> -1;
        };
    }
}
