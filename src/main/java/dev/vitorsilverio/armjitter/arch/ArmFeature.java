package dev.vitorsilverio.armjitter.arch;

/// Uma capacidade arquitetônica única ou comportamento que pode diferir entre versões de
/// arquitetura ARM. Uma {@link ArmArchitecture} é definida pelo conjunto de features que
/// ela possui, então uma nova versão é apenas um conjunto de features diferente — não um novo caminho de código.
///
/// Agrupadas livremente como features de disponibilidade de instrução e políticas de comportamento; veja
/// as notas `gba-vs-nds-rules` do gbaemu para entender o que cada uma implica em ARMv4T vs ARMv5.
public enum ArmFeature {
    // ---- Disponibilidade de instrução (gates de decoder) ----
    /// `BLX` (branch with link and exchange), formas imediata e por registrador. ARMv5+.
    BLX,
    /// `CLZ` (count leading zeros). ARMv5+.
    CLZ,
    /// Multiplicações DSP (SMUL\<x>\<y>, SMLA\<x>\<y>, SMLAW, SMULW, SMLAL\<x>\<y>). ARMv5TE+.
    DSP_MULTIPLY,
    /// Aritmética de saturação (QADD/QSUB/QDADD/QDSUB). ARMv5TE+.
    SATURATING,
    /// Carregamento/armazenamento de palavra dupla (`LDRD`/`STRD`). ARMv5TE+.
    LDRD_STRD,
    /// Encodings Thumb-2 de 32-bit Thumb. ARMv6T2/ARMv7+.
    THUMB2,

    // ---- Políticas de comportamento (forks de execução) ----
    /// Carregamentos em PC (`LDR`/`LDM`/`POP {PC}`) trocam o estado ARM/Thumb a partir do bit 0 do valor
    /// carregado. ARMv5+. Em ARMv4T tais carregamentos ignoram o bit 0 e permanecem no estado atual
    /// (apenas `BX` troca).
    LOAD_PC_INTERWORKING,
    /// Multiplicações inteiras deixam o carry flag inalterado. Em ARMv4 o carry é deixado
    /// UNPREDICTABLE por `MUL`/`MLA`, então emulá-lo como "inalterado" é aceitável lá.
    MUL_PRESERVES_CARRY
}
