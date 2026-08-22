package dev.vitorsilverio.armjitter.memory.mmu;

/// Codifica o valor de 64 bits de `PAR_EL1` (`ARM DDI 0487 D19.2.97`) para a instrução `AT`
/// (task B10.6) — sibling de {@link FaultStatus64}, mesma disciplina de "constante nomeada, não
/// mágica" (G6). Só o formato de 64 bits (sem `FEAT_D128`/`FEAT_RME`, não modelados por este
/// emulador — ver javadoc de {@link TranslatingAddressSpace64}).
///
/// **Pin de versão**: layout de bits conferido contra a especificação do registrador (`ARM DDI
/// 0487`), não contra corpus real (mesma ressalva de sempre — devkitA64 indisponível nesta sessão,
/// ver B10.2/B10.3).
public final class Aarch64ParEncoder {
    private Aarch64ParEncoder() {
    }

    /// `F` (bit `0`): `0` = tradução bem-sucedida, `1` = falha (`ARM DDI 0487` pseudocódigo de
    /// `AArch64.TranslateAddress`).
    private static final long F_BIT = 1L << 0;

    // ── layout de sucesso (F=0) ──────────────────────────────────────────────────────
    // `PA` ocupa os MESMOS bits [47:12] do endereço físico em si (sem deslocar) — o software
    // reconstrói o PA completo como `(PAR_EL1 & PA_MASK) | (VA & 0xFFF)`.
    private static final long SUCCESS_PA_MASK = 0x0000_FFFF_FFFF_F000L;

    // ── layout de falha (F=1) ────────────────────────────────────────────────────────
    /// `FST` (`[6:1]`): MESMA codificação de 6 bits de `ESR_EL1.ISS.DFSC`/`IFSC`
    /// ({@link FaultStatus64#code()}) — confirmado no layout real do registrador.
    private static final int FAULT_FST_SHIFT = 1;
    private static final long FAULT_FST_MASK = 0b11_1111L;

    /// `S` (bit `9`, "stage"): `0` = falha de stage 1, `1` = falha de stage 2 (B10.8) — o `FST`
    /// usa os MESMOS códigos por nível nas duas etapas (confirmado contra `arm_fi_to_lfsc` real do
    /// QEMU: a distinção de estágio não entra no valor de `FST`, só neste bit separado do layout de
    /// `PAR_EL1`).
    private static final long S_STAGE_BIT = 1L << 9;

    /// Codifica sucesso: `F=0`, `PA` nos bits `[47:12]`. `ATTR`/`SH`/`NS` ficam `0` (sem `MAIR`
    /// real modelado — mesma disciplina "storage-only" de {@link TranslatingAddressSpace64}).
    public static long success(long physicalAddress) {
        return physicalAddress & SUCCESS_PA_MASK;
    }

    /// Codifica falha de stage 1 (`S=0`): `F=1`, `FST` nos bits `[6:1]` a partir de
    /// {@link FaultStatus64#code()}. `PTW` fica `0` (não modelado, ver {@link #fault(FaultStatus64,
    /// boolean)}).
    public static long fault(FaultStatus64 status) {
        return fault(status, false);
    }

    /// Codifica falha (B10.8): `F=1`, `FST` nos bits `[6:1]`, `S` no bit `9` conforme
    /// {@code stage2} ({@code true} = falha veio de {@link Stage2TranslatingAddressSpace64}, ver
    /// {@link MemoryTranslationException64#isStage2()}). `PTW` (bit `8`, "falha durante o walk de
    /// stage-1 dentro de uma tradução stage-2") fica `0` — não modelado (B10.8 simplifica os
    /// acessos às tabelas de stage-1 como leitura física direta, ver javadoc de
    /// {@link Stage2TranslatingAddressSpace64}, então essa distinção nunca se aplicaria aqui).
    public static long fault(FaultStatus64 status, boolean stage2) {
        long value = F_BIT | ((status.code() & FAULT_FST_MASK) << FAULT_FST_SHIFT);
        return stage2 ? value | S_STAGE_BIT : value;
    }
}
