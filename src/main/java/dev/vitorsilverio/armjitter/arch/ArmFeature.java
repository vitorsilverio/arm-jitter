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
    MUL_PRESERVES_CARRY,
    /// `LDM` com writeback e a base presente na lista de registradores ainda faz o writeback,
    /// exceto quando a base é o registrador mais alto de uma lista com mais de um registrador
    /// (nesse caso vence o valor carregado da memória). Em ARMv4 a base na lista sempre recebe o
    /// valor carregado (writeback suprimido). ARMv5+.
    LDM_WRITEBACK_BASE_IN_LIST,
    /// `LDM`/`STM` com lista de registradores vazia não transfere registrador algum (apenas ajusta
    /// a base em ±40h). Em ARMv4 (ARM7TDMI) a lista vazia transfere `R15` em vez disso. ARMv5+.
    EMPTY_RLIST_NO_TRANSFER,
    /// `STM` com writeback e a base presente na lista sempre armazena o valor **original** da base.
    /// Em ARMv4 (ARM7TDMI) a base armazena o valor já incrementado (writeback) quando não é o
    /// primeiro registrador da lista. ARMv5+.
    STM_BASE_IN_LIST_STORES_ORIGINAL,

    // ---- Disponibilidade de instrução ARMv6/ARMv6K (gates de decoder; ainda sem decoder — B1.2–B1.5) ----
    /// Extensão de byte/halfword com rotação: `SXTB`/`SXTH`/`SXTB16`/`UXTB`/`UXTH`/`UXTB16` e as
    /// variantes com acumulador `SXTAB`/`SXTAH`/`SXTAB16`/`UXTAB`/`UXTAH`/`UXTAB16`. ARMv6+.
    EXTEND_ROTATE,
    /// Inversão de bytes: `REV`/`REV16`/`REVSH`. ARMv6+.
    BYTE_REVERSE,
    /// `UMAAL` (multiplicação longa sem sinal com acumulador duplo). ARMv6+.
    UMAAL,
    /// Aritmética paralela de 8/16 bits (`SADD8`/`SSUB16`/`UQADD8`/`SHADD16`/...), `SEL` e os
    /// flags GE\[3:0\] do CPSR que ela produz/consome. ARMv6+.
    PARALLEL_SIMD,
    /// Empacotamento e saturação: `PKHBT`/`PKHTB`, `SSAT`/`USAT`/`SSAT16`/`USAT16`,
    /// `USAD8`/`USADA8`. ARMv6+.
    PACK_SATURATE,
    /// Acesso exclusivo de palavra: `LDREX`/`STREX` (word) + monitor de exclusividade. ARMv6.
    EXCLUSIVE_WORD,
    /// Acessos exclusivos dimensionados: `LDREXB`/`LDREXH`/`LDREXD`, `STREXB`/`STREXH`/`STREXD`
    /// e `CLREX`. ARMv6K.
    EXCLUSIVE_SIZED,
    /// Instruções de mudança de modo/estado do sistema: `CPS`, `SRS`, `RFE`. ARMv6+.
    MODE_CHANGE_INSTRUCTIONS,
    /// `SETEND` e o bit E do CPSR (endianness de dados big-endian por instrução). ARMv6+.
    SETEND_BIG_ENDIAN_DATA,
    /// Hints de espera como instruções dedicadas: `WFI`/`WFE`/`SEV`/`YIELD`. ARMv6K.
    WAIT_HINTS
}
