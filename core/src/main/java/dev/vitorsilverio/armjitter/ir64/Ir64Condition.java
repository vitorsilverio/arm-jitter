package dev.vitorsilverio.armjitter.ir64;

/// Condição de 4 bits do AArch64 (`ARM DDI 0487 C1.2.4`), avaliada contra `PSTATE.{N,Z,C,V}`.
///
/// Deliberadamente SEPARADA de {@link dev.vitorsilverio.armjitter.core.Condition} (a mesma
/// decisão de `PstateRegister` não reusar `CpsrRegister` — ver RFC-IR-64BIT.md §3.1): embora os
/// 14 códigos "reais" tenham a mesma semântica NZCV que o ARM 32-bit, o A64 usa um campo de 4 bits
/// que também codifica `NV` (valor 15), um alias reservado de `AL` que não existe no encoding
/// ARM/THUMB de 32 bits — misturar os dois mundos violaria G2/G3 (o IR-32 nunca deve saber que
/// A64 existe, e vice-versa).
public enum Ir64Condition {
    /// Igual: Z setado.
    EQ,
    /// Diferente: Z limpo.
    NE,
    /// Carry setado (também `HS`, sem sinal maior-ou-igual).
    CS,
    /// Carry limpo (também `LO`, sem sinal menor).
    CC,
    /// Negativo.
    MI,
    /// Positivo/não-negativo.
    PL,
    /// Overflow setado.
    VS,
    /// Overflow limpo.
    VC,
    /// Maior, sem sinal.
    HI,
    /// Menor ou igual, sem sinal.
    LS,
    /// Maior ou igual, com sinal.
    GE,
    /// Menor que, com sinal.
    LT,
    /// Maior que, com sinal.
    GT,
    /// Menor ou igual, com sinal.
    LE,
    /// Sempre.
    AL,
    /// Sempre (alias reservado de {@link #AL} — encoding `1111`, nunca produzido por um
    /// assembler correto fora do caso reservado do manual, mas decodificável).
    NV;

    /// Decodifica o campo `cond` de 4 bits (`0`-`15`) de uma instrução A64 no valor do enum
    /// correspondente, na MESMA ordem da tabela do manual (`ARM DDI 0487 C1.2.4`).
    ///
    /// @param cond campo de 4 bits, `0`-`15`
    /// @return condição correspondente
    public static Ir64Condition decode(int cond) {
        return switch (cond & 0xF) {
            case 0 -> EQ;
            case 1 -> NE;
            case 2 -> CS;
            case 3 -> CC;
            case 4 -> MI;
            case 5 -> PL;
            case 6 -> VS;
            case 7 -> VC;
            case 8 -> HI;
            case 9 -> LS;
            case 10 -> GE;
            case 11 -> LT;
            case 12 -> GT;
            case 13 -> LE;
            case 14 -> AL;
            case 15 -> NV;
            default -> throw new IllegalStateException("unreachable");
        };
    }
}
