# B7 — Perfil M (Cortex-M, ARMv6-M/v7-M/v8-M) **[REFINAR]**

**Trilha:** B · **Depende de:** B2 (Thumb-2, ✅) e B2.6 (fechamento do preset) ·
**Repo:** arm-jitter (+ possivelmente um novo repo hospedeiro, estilo gbaemu/ndsemu/armbox)

## Contexto

**Objetivo declarado do projeto: emular qualquer device ARM, de qualquer arquitetura
ou combinação de features — não só a família A-profile (aplicação) já coberta.**
Todo o trabalho de arm-jitter até aqui (`ArmCore`, `CpuMode`, `ArmException`, os
decoders ARM/Thumb) modela implicitamente o **perfil A/R clássico**: modos de CPU
bancados (User/FIQ/IRQ/SVC/Abort/Undef/System) selecionados por 5 bits do CPSR,
vetor de exceção fixo em `0x00000000`/`0xFFFF0000`, interworking ARM↔Thumb via `BX`/
`BLX`. Isso cobre GBA, NDS, 3DS, Raspberry Pi, Android/Linux — mas **não cobre
microcontroladores Cortex-M** (STM32, nRF, a maioria das placas Arduino baseadas em
ARM, alvos bare-metal comuns), que são uma família arquitetural genuinamente
diferente: **perfil M**.

Cortex-M **não tem modo ARM de 32 bits** — é Thumb/Thumb-2 puro desde o reset (a
tentativa de entrar em modo ARM é `UsageFault`). Isso por si só já é uma boa notícia
para o arm-jitter (o decoder ARM de 32 bits simplesmente não é exercitado), mas o
resto do modelo de execução é diferente o bastante para não caber nos tipos atuais
sem investigação:

- **Sem modos bancados CPSR-style.** Só dois estados: **Thread mode** (código normal)
  e **Handler mode** (dentro de uma exceção) — não há FIQ/IRQ/SVC/Abort/Undef como
  modos de registrador banco. `CpuMode`/os bancos de registrador de `ArmCore`
  provavelmente não se aplicam diretamente.
- **Duas pilhas**: MSP (Main Stack Pointer) e PSP (Process Stack Pointer), escolhidas
  por um bit de `CONTROL` — não pelo modo do CPSR.
- **PSR combinado diferente**: `xPSR` = APSR (flags NZCV/Q, igual ao CPSR clássico) +
  IPSR (número da exceção ativa, substituindo os "modos") + EPSR (só o bit T de
  Thumb — sempre 1 — e o ITSTATE do IT block, que este projeto JÁ modela em
  `CpsrRegister`/`ItState` desde B2.4! Reaproveitável.).
- **NVIC (Nested Vectored Interrupt Controller)**: não é uma linha IRQ/FIQ genérica
  como hoje (`ArmException.IRQ`/`FIQ`) — é um periférico memory-mapped com até 240+
  linhas de interrupção priorizadas, preempção aninhada, e um **vetor de exceção
  relocável** (`VTOR`, tabela de ponteiros em RAM/flash, não um endereço fixo com
  código).
- **Entrada/saída de exceção automática**: o hardware empilha 8 registradores
  (R0-R3,R12,LR,PC,xPSR) sozinho na pilha ativa, e o retorno usa um valor mágico de
  `LR` (`EXC_RETURN`, ex. `0xFFFFFFF9`) em vez de uma instrução de retorno de exceção
  dedicada como `RFE`/`SUBS PC,LR,#4` do perfil A/R.
- **SysTick**: timer padrão memory-mapped, não um periférico do hospedeiro como hoje.

## Por que isto é [REFINAR] e não uma spec executável direto

O tamanho da investigação necessária é comparável ao que B6 (AArch64) exigiu antes de
virar sub-tasks — não é "adicionar um preset", é decidir se o modelo de execução atual
(`ArmCore`+`CpuMode`+`ArmException`) pode ser **estendido** para cobrir perfil M ou se
precisa de um tipo irmão (mesma pergunta que a RFC B0/IR-64-bit resolveu para AArch64:
"opção A generalizar vs opção B implementação paralela"). Antes de refinar em
sub-tasks executáveis, alguém precisa:

1. Ler o ARM ARM (ARMv7-M Architecture Reference Manual) capítulo B1 (modelo de
   exceção) e B3 (NVIC/SCS — System Control Space) com a mesma disciplina que B2.4
   usou para o QEMU (`target/arm/tcg/` tem suporte a M-profile também —
   `m-nocp.decode`, `helper.c` tem `arm_v7m_...` — usar como oráculo, igual às rodadas
   anteriores).
2. Decidir se `ArmCore` ganha um modo M-profile (flag/feature) ou se nasce um
   `ArmCoreM`/`CortexMCore` irmão — critério análogo ao da RFC B0: quanto do banco de
   registradores/pipeline de exceção realmente é compartilhável sem `if`s espalhados
   pelo core inteiro.
3. Escolher o alvo de validação inicial — provavelmente um **runner bare-metal
   simples** (não o runner Linux user-mode de B4.0, que pressupõe ELF+syscalls; aqui
   não há SO) rodando um `.bin`/`.elf` bare-metal real (ex. um firmware trivial
   compilado com `arm-none-eabi-gcc -mcpu=cortex-m3 -mthumb`), analisando o vetor de
   reset da posição 0 da imagem (SP inicial) e 4 (reset handler) — sem MMU, sem
   syscalls, útil por si só como B4.0 foi para o perfil A.
4. Decidir o escopo da primeira fase: ARMv6-M (Cortex-M0/M0+/M1 — Thumb-1 quase puro,
   subconjunto minúsculo de Thumb-2 só para `BL`/`MRS`/`MSR`/`ISB`) é uma fatia MUITO
   menor que ARMv7-M (Cortex-M3/M4/M7 — Thumb-2 quase completo, `SDIV`/`UDIV`, campos
   de bit `SBFX`/`UBFX`/`BFI`/`BFC`, já parcialmente previstos em B3.1 para o perfil
   A). Recomendação a validar no refinamento: começar por ARMv6-M (menor risco,
   valida o modelo de exceção primeiro) e crescer para ARMv7-M reaproveitando o
   Thumb-2 que B2/B2.6 já entregam.

## O que já está pronto para reaproveitar (não precisa refazer)

- **Todo o decoder/IR/interpretador/ASM Thumb-1 e Thumb-2** (B1-B2.6) — a CPU em si
  (ALU, load/store, branches, IT block) é a MESMA arquitetura de instruções; o que
  muda é o modelo de exceção/PSR/periféricos ao redor.
- **`ItState`/ITSTATE no CPSR** (B2.4) — o EPSR do perfil M usa exatamente o mesmo
  campo.
- **O padrão de `ArmFeature`/`ArmArchitecture.extending(...)`** para expressar
  variantes ARMv6-M vs ARMv7-M vs ARMv8-M como conjuntos de features, uma vez que o
  core em si esteja resolvido.
- **`SwiDispatcher`/`CoprocessorBus`-like hooks** como precedente de "o hospedeiro
  implementa o periférico, arm-jitter só expõe o ponto de extensão" — SysTick/NVIC
  provavelmente seguem o mesmo padrão de design.

## Escopo previsto (a confirmar/dividir no refinamento)

| Sub | Escopo (rascunho, não fechado) |
|-----|------|
| B7.0 | RFC: `ArmCore` estendido vs core irmão para perfil M — mesma pergunta de desenho da RFC B0, decisão registrada antes de qualquer código |
| B7.1 | Modelo de exceção M-profile: Thread/Handler mode, MSP/PSP, `CONTROL`, xPSR (APSR+IPSR+EPSR), entrada/saída automática de exceção + `EXC_RETURN` |
| B7.2 | NVIC/SCS mínimo: vetor relocável via VTOR, prioridades, pelo menos as exceções fixas (Reset/NMI/HardFault/SVCall/PendSV/SysTick) — periféricos específicos do fabricante ficam para o hospedeiro |
| B7.3 | Preset ARMv6-M (Thumb-1 + subconjunto mínimo de Thumb-2) — reaproveita decoder existente, sem trabalho de decode novo esperado |
| B7.4 | Preset ARMv7-M (Thumb-2 quase completo + `SDIV`/`UDIV`/bitfield) — depende de B2.6 e do que B3.1 (perfil A) já cobrir de sobreposição |
| B7.5 | Runner bare-metal mínimo (novo repo ou extensão de `armbox`) — vetor de reset, `.bin` ou ELF sem SO, validação com firmware real |

## Aceite (do épico, provisório)

- Firmware bare-metal real (`arm-none-eabi-gcc -mcpu=cortex-m0 -mthumb`, sem libc
  pesada) roda no runner, incluindo pelo menos uma exceção real disparada e
  retornada corretamente (ex. `SVC`/PendSV sintético).
- Harness de equivalência cobrindo o novo modelo de exceção (entrada/saída
  automática, `EXC_RETURN`) com a mesma disciplina G1 (interpretador como oráculo).
- G2/G3/G5 intactos: nada de perfil M vaza para os presets A-profile existentes
  (GBA/NDS/3DS/etc.) nem quebra gbaemu/ndsemu.

## Armadilhas antecipadas (a validar no refinamento, não fechadas)

- **Não presumir que basta "não permitir modo ARM"** — o modelo de exceção inteiro
  (bancos, retorno, PSR) é diferente, não só a ausência do modo ARM de 32 bits.
- **`CpuMode`/`ArmException` atuais são nomeados para o perfil A/R** — decidir cedo se
  viram uma interface/abstração compartilhada ou se o perfil M ganha seus próprios
  tipos (`M profile != especialização do CpuMode existente` é a hipótese mais provável
  dado o tamanho da diferença, mas precisa ser confirmada por quem for refinar,
  igual à RFC B0 fez para AArch64 antes de qualquer implementação).
- **ARMv8-M (TrustZone-M, `SAU`/estados Secure/Non-secure)** é uma camada adicional
  grande por cima de ARMv7-M — explicitamente FORA do escopo desta primeira rodada;
  registrar como extensão futura, não tentar encaixar de uma vez.
- Pin de versão: qualquer trecho de referência do QEMU (`target/arm/tcg/m-nocp.decode`
  etc.) citado numa spec futura deve ser reconferido contra a fonte na hora de
  implementar, mesmo padrão já seguido em B1-B2 (ver Armadilhas de B2.4).
