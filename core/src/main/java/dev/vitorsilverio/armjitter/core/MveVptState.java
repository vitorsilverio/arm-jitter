package dev.vitorsilverio.armjitter.core;

/// Algoritmo de predicação por beat do MVE/Helium (ARMv8.1-M, B16.2 — "o coração da extensão").
/// Transcrição das quatro funções centrais do QEMU real (`target/arm/tcg/mve_helper.c` +
/// `translate-mve.c`, obtidas via `WebFetch` na rodada de spec): {@code mve_eci_mask},
/// {@code mve_element_mask}, {@code mve_advance_vpt} e {@code gen_vpst}.
///
/// Todo método aqui opera sobre o MESMO byte de {@link CpsrRegister#itState()} (formato
/// `condexec_bits` do QEMU: bits\[3:0\] = resto do `ITSTATE` quando um bloco `IT` Thumb-2 (B2.4)
/// está ativo, bits\[7:4\] = `ECI` quando não está — os dois nunca são significativos ao mesmo
/// tempo, mesma sobreposição arquitetural que {@link CpsrRegister#eci()} documenta) e sobre o
/// `VPR` bruto de {@link VprRegister}.
public final class MveVptState {
    private MveVptState() {
    }

    // ── ECI (`enum MVEECIState`, target/arm/internals.h) — valores NÃO contíguos: 3, 6 e 7 são
    // reservados (ver {@link #isReservedEci}). ──────────────────────────────────────────────────
    /// Nenhum beat completado.
    public static final int ECI_NONE = 0;
    /// Beat A0 completado.
    public static final int ECI_A0 = 1;
    /// Beats A0, A1 completados.
    public static final int ECI_A0A1 = 2;
    /// Beats A0, A1, A2 completados.
    public static final int ECI_A0A1A2 = 4;
    /// Beats A0, A1, A2, B0 completados.
    public static final int ECI_A0A1A2B0 = 5;

    /// Deslocamento do nibble de `ECI` dentro do byte de `itState()`/`condexec_bits`.
    private static final int ECI_SHIFT = 4;
    /// Máscara do nibble de `ECI` (4 bits), já no deslocamento correto.
    private static final int ECI_NIBBLE_MASK = 0xF << ECI_SHIFT;
    /// Máscara do resto do `ITSTATE` (bits\[3:0\] do byte) — não-zero quando um bloco `IT` Thumb-2
    /// está em andamento, caso em que `ECI` não vale nada (`mve_eci_mask` devolve "sem máscara").
    private static final int IT_REST_MASK = 0xF;

    /// `mve_eci_mask` com `ECI_NONE`/fora de `IT`: nenhum beat foi executado ainda, todas as 16
    /// lanes (por byte) estão ativas.
    private static final int NO_MASKING = 0xFFFF;
    /// Depois do beat A0: os 4 bytes baixos (lane 0) já foram executados, ficam fora da máscara.
    private static final int MASK_AFTER_A0 = 0xFFF0;
    /// Depois dos beats A0+A1: os 8 bytes baixos (lanes 0-1) já foram executados.
    private static final int MASK_AFTER_A0A1 = 0xFF00;
    /// Depois dos beats A0+A1+A2 (ou A0+A1+A2+B0): os 12 bytes baixos (lanes 0-2) já executados —
    /// `ECI_A0A1A2` e `ECI_A0A1A2B0` produzem a MESMA máscara (só divergem em `advance`).
    private static final int MASK_AFTER_A0A1A2 = 0xF000;

    /// Metade baixa de um `P0`/máscara de 16 bits (byte 0-1, lanes 0-1 de um `Q` visto como bytes).
    private static final int LOW_HALF_MASK = 0x00FF;
    /// Metade alta (byte 2-3, lanes 2-3).
    private static final int HIGH_HALF_MASK = 0xFF00;
    /// Acima deste valor de `MASK01`/`MASK23` (4 bits, contagem de beats já decrementada — ver
    /// `mve_advance_vpt` real), a metade correspondente de `P0` NÃO deve ser invertida no avanço.
    private static final int NO_INVERT_THRESHOLD = 8;

    /// Resultado de {@link #advance}: o par (`VPR` novo, byte de `itState()` novo) que
    /// `mve_advance_vpt` produz — Java não tem tupla, ver "Inclui" item 1 da spec da task.
    public record MveVptAdvance(int vpr, int itState) {
    }

    /// `true` quando `eci` (nibble de 4 bits) é um valor RESERVADO (`3`, `6` ou `7`..`15`) —
    /// `mve_eci_check` real: produz `USAGE_FAULT` com `UFSR.INVSTATE`, nunca `UNDEFINED`. Chamar
    /// SEMPRE antes de {@link #eciMask}/{@link #advance}/{@link #vpstMask} quando `eci` vem de
    /// entrada não confiável (o decoder já extraiu o campo cru do CPSR).
    public static boolean isReservedEci(int eci) {
        return eci != ECI_NONE && eci != ECI_A0 && eci != ECI_A0A1
                && eci != ECI_A0A1A2 && eci != ECI_A0A1A2B0;
    }

    /// `mve_eci_mask` verbatim: máscara de 16 bits (1 bit por byte de um `Q`) das lanes que
    /// correspondem a beats AINDA NÃO executados (1 = a executar, 0 = já executado, ECI diz para
    /// pular). Dentro de um bloco `IT` Thumb-2 ativo (`itState & 0xF != 0`), `ECI` não vale nada —
    /// devolve "sem máscara" (mesma regra do QEMU real: os dois estados nunca coexistem de fato,
    /// ver Javadoc da classe).
    ///
    /// @throws IllegalStateException se o nibble de `ECI` for um valor reservado — o chamador deve
    ///         ter checado {@link #isReservedEci} antes (via `mve_eci_check`) e nunca chegar aqui.
    public static int eciMask(int itState) {
        if ((itState & IT_REST_MASK) != 0) {
            return NO_MASKING;
        }
        int eci = (itState & ECI_NIBBLE_MASK) >>> ECI_SHIFT;
        return switch (eci) {
            case ECI_NONE -> NO_MASKING;
            case ECI_A0 -> MASK_AFTER_A0;
            case ECI_A0A1 -> MASK_AFTER_A0A1;
            case ECI_A0A1A2, ECI_A0A1A2B0 -> MASK_AFTER_A0A1A2;
            default -> throw new IllegalStateException(
                    "ECI reservado (" + eci + "): chamador deveria ter checado isReservedEci antes");
        };
    }

    /// `mve_element_mask` verbatim: máscara de 16 bits (1 bit por byte) combinando `VPR.P0`
    /// (predicação `VPT`, só relevante quando `MASK01`/`MASK23` estão ativos), tail predication
    /// (`ltpsize`/`lr` — B15.6/`LOW_OVERHEAD_BRANCH`; passar `ltpsize=4` desliga esta etapa quando a
    /// feature não existir no preset, ver "Não inclui" da task) e {@link #eciMask}. `1` = a
    /// instrução deve escrever esta lane; `0` = deve preservar o valor atual do destino ali.
    ///
    /// @param vpr     valor bruto do {@code VPR} ({@link VprRegister#value()}).
    /// @param itState byte de {@link CpsrRegister#itState()} (para {@link #eciMask}).
    /// @param ltpsize `LTPSIZE` (log2 do tamanho de elemento da tail-predication, `0`-`3`; `4` =
    ///                tail predication inativa/inexistente, valor neutro que desliga a etapa).
    /// @param lr      `LR` corrente (contador de loop consumido só quando `ltpsize < 4`).
    public static int elementMask(int vpr, int itState, int ltpsize, int lr) {
        int mask = (vpr & VprRegister.P0_MASK) >>> VprRegister.P0_SHIFT;
        if ((vpr & VprRegister.MASK01_MASK) == 0) {
            mask |= LOW_HALF_MASK;
        }
        if ((vpr & VprRegister.MASK23_MASK) == 0) {
            mask |= HIGH_HALF_MASK;
        }
        if (ltpsize < 4 && Integer.compareUnsigned(lr, 1 << (4 - ltpsize)) <= 0) {
            int maskLength = lr << ltpsize;
            int ltpMask = maskLength == 0 ? 0 : ((1 << maskLength) - 1);
            mask &= ltpMask;
        }
        mask &= eciMask(itState);
        return mask & 0xFFFF;
    }

    /// `mve_advance_vpt` verbatim: avança o `ECI`/`ITSTATE` (assumindo o padrão A0A1A2B0→A0,
    /// senão volta a `NONE` — só quando NÃO há bloco `IT` ativo) e, se `VPT` estiver habilitado
    /// (`MASK01`/`MASK23` != 0), inverte as metades de `P0` correspondentes a beats já executados
    /// (via {@link #eciMask} calculado ANTES do avanço) e desloca `MASK01`/`MASK23` um bit à
    /// esquerda — `MASK01` só quando o beat 1 já rodou (`eciMask & 0xf0`); `MASK23` sempre.
    /// **Roda depois de TODA instrução MVE beatwise, executada ou totalmente predicada (G4: o
    /// avanço é incondicional).**
    public static MveVptAdvance advance(int vpr, int itState) {
        int eciMask = eciMask(itState);

        int newItState = itState;
        if ((itState & IT_REST_MASK) == 0) {
            int eci = (itState & ECI_NIBBLE_MASK) >>> ECI_SHIFT;
            int nextEci = eci == ECI_A0A1A2B0 ? ECI_A0 : ECI_NONE;
            newItState = nextEci << ECI_SHIFT;
        }

        int newVpr = vpr;
        if ((vpr & (VprRegister.MASK01_MASK | VprRegister.MASK23_MASK)) != 0) {
            int mask01 = (vpr & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT;
            int mask23 = (vpr & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT;
            int invMask = eciMask;
            if (mask01 <= NO_INVERT_THRESHOLD) {
                invMask &= ~LOW_HALF_MASK;
            }
            if (mask23 <= NO_INVERT_THRESHOLD) {
                invMask &= ~HIGH_HALF_MASK;
            }
            newVpr ^= invMask;
            if ((eciMask & 0xF0) != 0) {
                newVpr = (newVpr & ~VprRegister.MASK01_MASK)
                        | (((mask01 << 1) << VprRegister.MASK01_SHIFT) & VprRegister.MASK01_MASK);
            }
            newVpr = (newVpr & ~VprRegister.MASK23_MASK)
                    | (((mask23 << 1) << VprRegister.MASK23_SHIFT) & VprRegister.MASK23_MASK);
        }
        return new MveVptAdvance(newVpr, newItState);
    }

    /// `mve_update_and_store_eci` verbatim (B16.5): cicla só o nibble de `ECI` — MESMA regra de
    /// `A0A1A2B0`→`A0`, senão `NONE`, só quando NÃO há bloco `IT` ativo — mas, ao contrário de
    /// {@link #advance}, NUNCA toca `MASK01`/`MASK23`/`P0` do `VPR` (usado por instruções
    /// "beatwise mas não predicadas", `VLD2`/`VLD4`/`VST2`/`VST4`, que não participam da máquina
    /// `VPT`). Quando `ECI` já é `NONE` dentro de um bloco `IT`, devolve o `itState` intocado
    /// (mesmo curto-circuito `if (s->eci)` do QEMU real).
    public static int advanceEciOnly(int itState) {
        if ((itState & IT_REST_MASK) != 0) {
            return itState;
        }
        int eci = (itState & ECI_NIBBLE_MASK) >>> ECI_SHIFT;
        if (eci == ECI_NONE) {
            return itState;
        }
        int nextEci = eci == ECI_A0A1A2B0 ? ECI_A0 : ECI_NONE;
        return nextEci << ECI_SHIFT;
    }

    /// `gen_vpst` verbatim: grava `mask` (4 bits, `%mask_22_13`) em `MASK01` e, quando `eci` indica
    /// que o beat 1 ainda não rodou (`ECI_NONE`/`ECI_A0`), TAMBÉM em `MASK23` (os dois campos ficam
    /// idênticos na entrada de um `VPT`/`VPST` novo); caso contrário (`ECI_A0A1`/`ECI_A0A1A2`/
    /// `ECI_A0A1A2B0` — retomando um `VPT` no meio, depois de uma exceção que interrompeu no meio
    /// do beat 1+), só `MASK23` é atualizado, preservando o `MASK01` da retomada.
    ///
    /// @param eci nibble de `ECI` corrente (`0`-`5`, chamador já validou via {@link #isReservedEci}).
    public static int vpstMask(int vpr, int eci, int mask4Bits) {
        int mask = mask4Bits & 0xF;
        if (eci == ECI_NONE || eci == ECI_A0) {
            int fieldMask = VprRegister.MASK01_MASK | VprRegister.MASK23_MASK;
            int combined = mask | (mask << 4);
            return (vpr & ~fieldMask) | ((combined << VprRegister.MASK01_SHIFT) & fieldMask);
        }
        return (vpr & ~VprRegister.MASK23_MASK) | ((mask << VprRegister.MASK23_SHIFT) & VprRegister.MASK23_MASK);
    }
}
