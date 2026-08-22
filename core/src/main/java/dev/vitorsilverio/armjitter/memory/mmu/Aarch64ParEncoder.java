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

    /// Codifica sucesso: `F=0`, `PA` nos bits `[47:12]`. `ATTR`/`SH`/`NS` ficam `0` (sem `MAIR`
    /// real modelado — mesma disciplina "storage-only" de {@link TranslatingAddressSpace64}).
    public static long success(long physicalAddress) {
        return physicalAddress & SUCCESS_PA_MASK;
    }

    /// Codifica falha: `F=1`, `FST` nos bits `[6:1]` a partir de {@link FaultStatus64#code()}.
    /// `S`(stage)/`PTW` ficam `0` (só stage 1 é traduzido, ver B10.8 para stage 2).
    public static long fault(FaultStatus64 status) {
        return F_BIT | ((status.code() & FAULT_FST_MASK) << FAULT_FST_SHIFT);
    }
}
