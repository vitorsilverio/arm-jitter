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
    DIVIDE,

    // ---- VFP (épico B3; B3.3 só cria o estado — banco S/D + FPSCR, ver
    // {@link dev.vitorsilverio.armjitter.core.VfpRegisters}/{@link dev.vitorsilverio.armjitter.core.FpscrRegister}) ----
    /// VFPv2 (banco de 32 registradores `S`/16 `D`, `FPSCR`) + `VMOV` imediato do v3-d16
    /// (decisão do épico `b3-armv7a-vfp.md`). Nenhum preset habilita ainda (B3.7); nenhuma
    /// instrução decodifica ainda (B3.4/B3.5).
    VFPV2,

    // ---- Perfil M (épico B7; B7.2 só cria o modelo de exceção, ver
    // {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}) ----
    /// Marca um core Cortex-M (perfil M): {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel}
    /// instalado em vez de {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel}, sem
    /// modo ARM (só Thumb/Thumb-2), MSP/PSP em vez de bancos de modo A/R. Nenhum preset habilita
    /// ainda (B7.4, junto com `ARMV6M`/`ARMV7M`); nenhum decoder a consome ainda — o
    /// comportamento da B7.2 (bypass do `SwiDispatcher` em `SVC`, `UNDEFINED` controlado em
    /// `BX`/`BLX` para bit 0 = 0) é hoje inteiramente dirigido por qual {@code ExceptionModel}
    /// está instalado no {@link dev.vitorsilverio.armjitter.core.ArmCore}, não por esta feature.
    M_PROFILE,
    /// Registradores de mascaramento de falha do perfil M **completo** (ARMv7-M): `BASEPRI`,
    /// `BASEPRI_MAX` e `FAULTMASK`, acessíveis via `MRS`/`MSR` (SYSm 17/18/19) e, no caso de
    /// `FAULTMASK`, via `CPSID f`/`CPSIE f` (B7.4). Presente só no preset `ARMV7M` — o ARMv6-M
    /// (Cortex-M0/M0+/M1) NÃO tem esses registradores (só `PRIMASK`), então o decoder trata esses
    /// SYSm/`CPS f` como UNDEFINED sem esta feature. Ver a task B7.4.
    M_FAULT_MASKING,

    // ---- B7.5 (fecha o épico B7) ----
    /// `BKPT #imm` (ARMv5T+ em todo perfil — A/R e M). Ausente do {@code ARMV4T} (o ARM7TDMI do
    /// GBA/NDS não tem BKPT — G2), presente em todos os demais presets. Sem esta feature o
    /// encoding cai no `UNDEFINED` controlado de sempre (comportamento pré-B7.5, preservado).
    BREAKPOINT,

    // ---- Onda 5, B9.1 (cobertura de ISA) ----
    /// Multiplicações/multiplicações-acumulação "media" com sinal (`SMLAD`/`SMLSD`/`SMLALD`/
    /// `SMLSLD` e formas `X`, `SMMLA`/`SMMLAR`/`SMMLS`/`SMMLSR` — ARM DDI 0406C A5.2.6). ARMv6+
    /// (confirmado contra `op_smlad`/`op_smlald`/`op_smmla` reais em `target/arm/tcg/translate.c`
    /// do QEMU, todos gateados por `ENABLE_ARCH_6`) — NÃO existe em ARMv4T/ARMv5TE.
    SIGNED_MULTIPLY_MEDIA,

    // ---- Onda 5, B9.6 (cobertura de ISA) ----
    /// `VFMA`/`VFMS`/`VFNMA`/`VFNMS` (multiplicação-acumulação VFP FUNDIDA: um único passo de
    /// arredondamento para o produto+soma, ao contrário de `VMLA`/`VMLS`/`VNMLA`/`VNMLS` — ver
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.VfpOperation}). **VFPv4, não VFPv2** — confirmado
    /// contra `target/arm/tcg/translate-vfp.c` real do QEMU (`do_vfm_sp`/`do_vfm_dp`, comentário
    /// literal "Present in VFPv4 only", gate `dc_isar_feature(aa32_simdfmac, s)`) e
    /// cronologicamente: a especificação VFPv4 (ARM Cortex-A15/A7, ~2010) é POSTERIOR à geração
    /// ARM11 (ARM1176/MPCore, ~2002-2003, VFPv2 no máximo) — o ARM11 MPCore do 3DS não pode tê-la
    /// por construção, não só "improvável" (ver a task B9.6 para a triagem completa). Presente só
    /// no preset {@code ARMV7A} (não em {@code ARM11_MPCORE}/{@code ARMV6K}). Encoding ARM/Thumb-2
    /// (B9.6) é decodificado dentro de {@link dev.vitorsilverio.armjitter.decoder.VfpDecoder}/
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder} (mesmo espaço de 3 registradores
    /// de `VMLA`/`VDIV`, `op1` `0b110`/`0b101` — antes desta feature caíam em `default -> null`,
    /// nunca reivindicados por nenhum outro dispatch, então viravam `UNDEFINED` sem risco de
    /// misdecode, G8).
    VFP_FUSED_MULTIPLY_ACCUMULATE,

    // ---- Onda 5, B9.8 (Hyp mode + Monitor mode 32-bit, ARMv7VE/Security Extensions) ----
    /// `HVC` (ARM DDI 0406C A8.8.65, formas A32 e T32): entra em Hyp mode. Confirmado contra
    /// `target/arm/tcg/translate.c` real do QEMU (`trans_HVC`): gate real é só `ENABLE_ARCH_7`
    /// (ARMv7 base, QUALQUER perfil A/R) `&amp;&amp; !ARM_FEATURE_M` — **não** exige
    /// `ARM_FEATURE_V7VE` (Virtualization Extensions), ao contrário do que se poderia supor por
    /// analogia com `ERET`. Presente só no preset {@code ARMV7A} (não em {@code ARMV7M}, perfil M
    /// — nem em presets pré-v7).
    HYPERVISOR_CALL,

    /// `SMC` (B9.8.3, ARM DDI 0406C A8.8.20, formas A32 e T32): entra em Monitor mode. Confirmado
    /// contra `target/arm/tcg/translate.c` real do QEMU (`trans_SMC`): gate real é
    /// `ENABLE_ARCH_6K &amp;&amp; !ARM_FEATURE_M` — **mais antigo que `HYPERVISOR_CALL`**
    /// (`ENABLE_ARCH_7`). Habilitada já em {@code ARMV6K} (herdada por {@code ARM11_MPCORE}/
    /// {@code ARMV6K_THUMB2}/{@code ARMV7A}), ao contrário de `HYPERVISOR_CALL`, restrita a
    /// {@code ARMV7A}.
    SECURE_MONITOR_CALL,

    /// `ERET` A32 (B9.8.4, ARM DDI 0406C B9.3.3): retorna de exceção lendo `ELR_hyp` em Hyp mode
    /// (em vez de `LR`). Confirmado contra `target/arm/tcg/{translate.c,a32.decode}` reais do QEMU
    /// (`trans_ERET`): gate real é `ARM_FEATURE_V7VE` (Virtualization Extensions) — MAIS ESTRITO
    /// que {@link #HYPERVISOR_CALL} (`ENABLE_ARCH_7`) e {@link #SECURE_MONITOR_CALL}
    /// (`ENABLE_ARCH_6K`), ao contrário do que a analogia com as duas sugeriria. Nenhum preset deste
    /// projeto modela `V7VE` como conceito à parte de `ARMV7A` base hoje — esta feature NÃO é
    /// habilitada em preset nenhum (aditivo puro, sem consumidor real ainda, mesmo padrão de
    /// `TTBR0_EL2`/`TTBR0_EL3` na escada `B10.6b`/`B10.6c`); a instrução decodifica e executa
    /// corretamente assim que algum preset futuro ligar esta feature.
    VIRTUALIZATION_EXTENSIONS,

    // ---- Onda 5, B9.11 (achado colateral da B9.10 — auditoria de Thumb2MiscDecoder sob v6-M) ----
    /// Subconjunto de 32 bits do grupo Thumb-2 "Hints, and CPS"/"Miscellaneous control
    /// instructions" (ARM DDI 0406C A5.3.5) reservado ao perfil M: hints largos (`NOP.W`/
    /// `YIELD.W`/`WFE.W`/`WFI.W`/`SEV.W`/`ESB`), `CPS.W` (forma A/R de 32 bits, com `imod`/`mode`)
    /// e `UDF.W`. Confirmado ausente do `ARMv6-M Architecture Reference Manual` (ARM DDI 0419C)
    /// A3.3.1 — a lista fechada de encodings de 32 bits do v6-M é só `BL`/`DMB`/`DSB`/`ISB`/
    /// `MRS`/`MSR` (mesma fonte que a B9.10 usou); v6-M só tem as formas T1 de 16 bits desses
    /// hints/`CPS`/`UDF`. Presente em `ARMV7M` (`Thumb2MiscDecoder` não distinguia v6-M de v7-M
    /// para este subgrupo antes desta task — G8, mesma categoria do achado principal da B9.10 para
    /// `B.W`/`TBB`/`TBH`). Não confundir com {@link #WAIT_HINTS}/{@link #MODE_CHANGE_INSTRUCTIONS}
    /// (essas continuam controlando as formas de 16 bits equivalentes, que v6-M TEM).
    M_PROFILE_WIDE_MISC_CONTROL
}
