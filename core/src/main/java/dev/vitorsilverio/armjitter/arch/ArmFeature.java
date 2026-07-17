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
    WAIT_HINTS,
    /// Barreiras de memória `DMB`/`DSB`/`ISB`. ARMv7. Nesta implementação (core single-thread,
    /// sem memória especulativa/reordenação real), a semântica é NOP observável — ver
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier} e
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder} para a justificativa
    /// completa. Só o decode Thumb-2 (B2.5) consome esta feature até agora; a forma ARM
    /// clássica de `DMB`/`DSB`/`ISB` fica para uma task futura.
    MEMORY_BARRIERS,
    /// Acesso de dados desalinhado "atravessado" (`LDR`/`LDRH`/`STR`/`STRH` com endereço não
    /// múltiplo do tamanho do acesso): em vez de alinhar e rotacionar (comportamento ARMv4T, ver
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport#read32Arm7}), o
    /// acesso é feito byte a byte, little-endian, na posição real — ARM DDI 0100I A2.8 / DDI
    /// 0406C A3.2.1. Modela especificamente o modo `SCTLR.U=1` (o default do Linux/userland em
    /// ARMv6+); o modo `A=1` (alignment fault, `SIGBUS`) e o `U=0` legado (que reproduz a rotação
    /// ARMv4T mesmo em um core v6+) ficam fora de escopo — não implementados. `LDM`/`STM`,
    /// `LDRD`/`STRD`, `LDREX`/`STREX`, `SWP` e um `LDR`/`LDRH` com destino no PC continuam
    /// exigindo alinhamento mesmo com esta feature ligada (task B1.7, item 4) — o hardware real
    /// trata esses casos como UNPREDICTABLE/alinhados por natureza, então não há "atravessado"
    /// para eles aqui. ARMv6+. **Nunca** habilitar em {@code ARMV4T}/{@code ARMV5TE} (G2/G3) —
    /// GBA/NDS dependem da rotação para jogos que fazem `LDR` desalinhado de propósito.
    UNALIGNED_ACCESS,
    /// Hints de preload de cache `PLD`/`PLDW`/`PLI` como NOP observável (nenhum efeito além de
    /// ciclo/fetch; o endereço nunca é acessado). `PLD` é ARMv5TE+, `PLDW` ARMv7MP+, `PLI` ARMv7+
    /// — agrupados numa única feature porque este core não modela cache (todas viram o mesmo NOP,
    /// ver {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder}/
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder}). Deliberadamente NÃO
    /// adicionada a {@code ARMV5TE} (G3, menor superfície — gbaemu/ndsemu nunca precisaram).
    PRELOAD_HINTS,

    // ---- Disponibilidade de instrução ARMv7 (gates de decoder; B3.1) ----
    /// `MOVW`/`MOVT` (imediato de 16 bits para os bits baixos/altos de um registrador). ARMv6T2+.
    /// Encoding ARM (B3.1, carve-out direto em
    /// {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} — colide com o dispatch ALU
    /// genérico, então não é uma {@link dev.vitorsilverio.armjitter.arch.DecoderExtension})
    /// e Thumb-2 (já decodificado desde B2.2, mas gateado só por {@link #THUMB2} até B3.1 —
    /// passa a exigir esta feature também, então {@code ARMV6K_THUMB2} precisa declará-la).
    MOVW_MOVT,
    /// `MLS` (multiplicação com subtração do acumulador: `Rd = Ra − Rn×Rm`). ARMv6T2+. Encoding
    /// ARM (B3.1) é um carve-out direto em
    /// {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} — colide com o dispatch de
    /// halfword-transfer genérico.
    MLS_MULTIPLY,
    /// Manipulação de campo de bits: `SBFX`/`UBFX` (extração com/sem sinal) e `BFI`/`BFC`
    /// (inserção/limpeza). ARMv6T2+. Encoding ARM (B3.1) é um carve-out direto em
    /// {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} — colide com o dispatch de
    /// LDR/STR imediato genérico.
    BIT_FIELD,
    /// `RBIT` (inversão da ordem dos bits de uma palavra de 32 bits). ARMv6T2+. Mesmo carve-out
    /// direto de {@link #BIT_FIELD} (mesma colisão).
    BIT_REVERSE,
    /// `SDIV`/`UDIV` (divisão inteira com/sem sinal; divisão por zero resulta em 0, sem exceção).
    /// ARMv7-A/R (opcional; sempre presente nos perfis emulados aqui). Mesmo carve-out direto de
    /// {@link #BIT_FIELD} (mesma colisão).
    DIVIDE
}
