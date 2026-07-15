package dev.vitorsilverio.armjitter.core;

/// Algoritmo de expansão/avanço do ITSTATE\[7:0\] de um Thumb-2 IT block (B2.4, ARMv6T2+).
///
/// Implementa a mesma matemática do shift-register do QEMU (`target/arm/tcg/translate.c`,
/// `trans_IT`/per-instruction advance) descrita na spec da task, mas expressa sobre o valor
/// ITSTATE\[7:0\] JÁ COMBINADO (o mesmo formato de {@link CpsrRegister#itState()}: byte baixo do
/// encoding da instrução `IT`, `firstcond:4 ++ mask:4`) em vez das duas variáveis locais `cond`/
/// `mask` do QEMU — produz a MESMA sequência de condições (verificado no
/// `ItStateExpansionTest` contra uma transcrição literal do algoritmo de duas variáveis do QEMU,
/// usada como oráculo secundário).
///
/// Derivação (ARM DDI 0406C A2.5.2, `CurrentCond()`/`ITAdvance()`):
/// <ul>
///   <li>{@code ITSTATE<7:4>} é a condição da instrução ATUAL (o nibble alto é exatamente
///       `firstcond` na entrada do bloco, e evolui a cada instrução).</li>
///   <li>{@code ITSTATE<2:0> == 0} identifica a ÚLTIMA instrução do bloco: depois dela o estado
///       inteiro zera (fora de IT block).</li>
///   <li>Caso contrário, só os 5 bits baixos ({@code ITSTATE<4:0>}) deslizam um bit à esquerda
///       (descartando o bit que transborda); os 3 bits altos ({@code ITSTATE<7:5>}, o topo fixo de
///       `firstcond`) nunca mudam durante o bloco.</li>
/// </ul>
public final class ItState {
    private ItState() {
    }

    /// Máscara/deslocamento do nibble alto (condição corrente) dentro do ITSTATE de 8 bits.
    private static final int CONDITION_NIBBLE_SHIFT = 4;
    private static final int CONDITION_NIBBLE_MASK = 0xF;
    /// `cond>=0xE` (14=AL, 15=NV-remapeado-para-AL na entrada) sempre executa — mesmo guard textual
    /// da spec ("pular se cond<0xe e a condição falhar — 0xe/0xf sempre executam"); indexar
    /// {@link Condition#values()} diretamente quebraria para 15 (o enum não tem `NV`).
    private static final int ALWAYS_EXECUTES_THRESHOLD = 0xE;
    /// Preserva os 3 bits altos (`ITSTATE<7:5>`, topo fixo de `firstcond`) através do avanço.
    private static final int TOP3_MASK = 0xE0;
    /// Os 5 bits baixos que deslizam a cada instrução (`ITSTATE<4:0>`).
    private static final int LOW5_MASK = 0x1F;
    /// `ITSTATE<2:0> == 0` identifica a última instrução do bloco (o bloco termina após ela).
    private static final int LAST_INSTRUCTION_MASK = 0x7;

    /// `true` quando `itState` indica um IT block em andamento (ITSTATE≠0).
    public static boolean inProgress(int itState) {
        return itState != 0;
    }

    /// Condição da instrução GOVERNADA pelo ITSTATE atual, ou {@link Condition#AL} fora de um IT
    /// block (ITSTATE=0) ou quando o nibble de condição é 0xE/0xF (sempre executa).
    public static Condition condition(int itState) {
        if (itState == 0) {
            return Condition.AL;
        }
        int condBits = (itState >>> CONDITION_NIBBLE_SHIFT) & CONDITION_NIBBLE_MASK;
        if (condBits >= ALWAYS_EXECUTES_THRESHOLD) {
            return Condition.AL;
        }
        return Condition.values()[condBits];
    }

    /// `ITAdvance()`: o ITSTATE que passa a valer depois que a instrução GOVERNADA pelo `itState`
    /// atual se retira — executada ou pulada, o avanço é INCONDICIONAL (mesmo no hardware real).
    /// Devolve `0` quando o bloco termina (última instrução) ou quando já estava fora de um IT
    /// block.
    public static int advance(int itState) {
        if (itState == 0 || (itState & LAST_INSTRUCTION_MASK) == 0) {
            return 0;
        }
        int low5 = (itState & LOW5_MASK) << 1 & LOW5_MASK;
        return (itState & TOP3_MASK) | low5;
    }

    /// Mapeia o campo `firstcond` de 4 bits da instrução `IT` para o valor a usar na montagem do
    /// ITSTATE de entrada: `firstcond==0b1111` (NV) é CONSTRAINED UNPREDICTABLE e mapeado para
    /// `0b1110` (AL) — mesmo tratamento do QEMU (o enum {@link Condition} deste projeto não tem
    /// valor NV).
    public static int normalizeFirstCond(int firstCond4Bits) {
        return firstCond4Bits == 0xF ? 0xE : firstCond4Bits;
    }

    /// Monta o ITSTATE de entrada (`firstcond:4 ++ mask:4`) a partir dos campos já normalizados
    /// da instrução `IT`.
    public static int entryState(int normalizedFirstCond4Bits, int mask4Bits) {
        return ((normalizedFirstCond4Bits & 0xF) << CONDITION_NIBBLE_SHIFT) | (mask4Bits & 0xF);
    }
}
