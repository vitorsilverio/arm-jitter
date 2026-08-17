# Fila de execução (2026-07-15; reestruturada 2026-07-17) — para agentes com contexto limitado

**Regras de sessão (obrigatórias, existem para o agente NÃO se perder):**

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a
   próxima task na mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes
   G1-G7) e depois SÓ o arquivo da task + os fontes que ela cita. Não explorar o
   repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao
   usuário — não improvisar.
4. Nunca pegar itens da seção "Pendências que EXIGEM modelo forte" do
   `tasks/README.md`, nem da seção "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25 + gbaemu +
   ndsemu), status atualizado no índice do `tasks/README.md`, 1 commit começando
   com o ID (`B3.5: ...`).
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale
   só entre repos DIFERENTES (arm-jitter ∥ gbaemu ∥ armbox). Duas sessões no
   mesmo working tree misturam WIP uma da outra (já aconteceu em 2026-07-16:
   um `git add -A` de uma sessão de docs varreu os arquivos half-done da A6
   para dentro do commit errado — desfeito, mas é exatamente o acidente que
   esta regra evita). Pelo mesmo motivo: **commits sempre com paths explícitos
   (`git add <arquivos da SUA task>`), nunca `git add -A`.**

**Prompt de kickoff (copiar/colar, trocando o ID):**

> Leia `arm-jitter/tasks/README.md` inteiro. Depois execute APENAS a task
> **<ID>** (arquivo `<caminho>`), seguindo o protocolo do README. Leia os
> arquivos-fonte citados na task antes de escrever código. Implemente somente o
> que está em "Inclui"; respeite as decisões já tomadas (não reavaliar). Ao
> final: suites verdes, índice atualizado, um commit `"<ID>: ..."`.

---

## Disciplina de custo (obrigatória, adicionada 2026-08-17)

Sessões de investigação longa (ex.: F3) já consumiram rodadas caras demais. Regras novas,
valem para QUALQUER sessão futura desta fila:

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão.** Ao corrigir um bug no
   arm-jitter, rode primeiro só a suíte do próprio arm-jitter + o consumidor que exercita o
   bug (ex.: `virtual-arm-box` para CP15/MMU). Só rode a sequência completa
   arm-jitter+gbaemu+ndsemu+armbox+virtual-arm-box UMA vez, pouco antes do commit final da
   sessão — não a cada fix intermediário.
2. **Backend INTERPRETED em boot de sistema real é caro (já levou >30min) — rode-o só
   quando o backend JIT já confirmar o marco.** Nunca rode os dois "por rotina" a cada
   sessão. Se INTERPRETED não terminar num orçamento razoável (~10-15min), documente como
   "não concluído nesta sessão" e siga — não é bloqueador.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação".**
   Um processo de shell solto (fora da ferramenta `Agent`) não avisa ninguém quando termina.
   Rode de forma BLOQUEANTE numa única chamada, com timeout alto; se precisar de mais tempo
   que um timeout permite, faça polling DENTRO da mesma chamada (loop com `sleep`) até ter o
   resultado real, em vez de retornar e reentrar em rodadas novas só para checar.
4. **Orçamento de tool-calls por sessão de investigação aberta (~60-80).** Se a causa raiz
   não foi isolada dentro disso, pare, documente o que foi descartado/aprendido e devolva —
   não é obrigatório fechar tudo numa sessão só (histórico da F3 mostra isso sendo normal).
5. **Ao fechar uma task ou sessão, mova o relato minucioso para `tasks/FILA-HISTORICO.md`**
   e deixe aqui só um resumo de poucas linhas (o que fechou, o achado principal, o próximo
   passo). O arquivo ativo deve continuar pequeno — ele é lido INTEIRO por todo agente novo
   como parte do protocolo, então seu tamanho é custo pago em toda sessão futura.
---
## Onda 3 — fila ATUAL (executar de cima para baixo)

Mesmas regras de sempre: 1 sessão = 1 task (ou 1 PR); **ordem dentro do mesmo
repo é obrigatória**; a coluna Repo mostra o que pode andar em paralelo (repos
diferentes apenas).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| P1 | **B4.0.5** — armbox fase 3: fork/execve/pipes/wait | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox | B4.0.3 | ainda bloqueada — B4.0.3 fechou parcial, falta o busybox thumb2 (ver 🧑 abaixo) que essa task precisa como corpus |

## Onda 4 — priorizada pelo usuário em 2026-08-15 (executar de cima para baixo)

Cinco frentes pedidas pelo usuário: rename do `virtual-arm-box` (ex-`linuxbox`), emulador de 3DS, licença BSD,
issues no GitHub, e o 1.0 do arm-jitter no Maven. **Ordem escolhida pelo usuário:
infra primeiro, n3dsemu depois** — o n3dsemu é a única frente multi-sessão longa, e começar
por ela deixaria o resto parado.

Decisões já tomadas (**não reabrir**): BSD **3-Clause** · Maven **Central** mantendo o
`groupId` `dev.vitorsilverio` (domínio `vitorsilverio.dev` é do usuário, verificação por DNS
TXT) · gráficos do n3dsemu em **Vulkan/LWJGL 3 com janela GLFW própria**, sem backend de
software · `armbox` e `virtual-arm-box` seguem **repos separados** · n3dsemu começa por
**`.3dsx` homebrew**, ROM comercial é [REFINAR].

**Atualização 2026-08-15 (tarde)**: `virtual-arm-box` **agora TEM repositório no GitHub**
(decisão anterior revertida a pedido do usuário) — `https://github.com/vitorsilverio/virtual-arm-box`,
público, criado e vinculado como `origin` do checkout local `linuxbox/` (na época; renomeado
localmente para `virtual-arm-box/` pela F2, histórico git preservado via `git mv`), push
inicial feito (5 commits, incl. `F1: licença BSD 3-Clause`). **F8 e F9 retroativamente
completadas para este repo** (mesma sessão): 11 labels + `.github/ISSUE_TEMPLATE/{bug,feature,config}.yml`
(commit `3215f29` no `virtual-arm-box`, `criar-labels.sh` do arm-jitter estendido com o 5º repo),
issue `virtual-arm-box#1` postada (manifesto e `Fecha:` de F3 atualizados). Sem milestone (mesmo
padrão do armbox — sem agrupamento definido ainda).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| ~~P2~~ | ~~**F1**~~ ✅ fechada 2026-08-15 — licença BSD 3-Clause nos 5 repos | `trilha-f-infra/f1-licenca-bsd.md` | todos | — | `LICENSE`+`<licenses>`+README nos 5 repos, `mvn -o validate` verde, 1 commit por repo; destravou a F5 |
| ~~P3~~ | ~~**F8**~~ ✅ fechada 2026-08-15 — labels/milestones/templates + fronteira issues×`tasks/` | `trilha-f-infra/f8-github-issues-setup.md` | 4 repos | — | seção no `tasks/README.md`, 11 labels × 4 repos (`tasks/issues/criar-labels.sh`), 3 milestones (arm-jitter 1.1, gbaemu Fidelidade, ndsemu Compatibilidade), templates `bug.yml`/`feature.yml`/`config.yml` nos 4 repos; destrava a F9 |
| ~~P4~~ | ~~**F9**~~ ✅ fechada 2026-08-15 — 20 issues postadas | `trilha-f-infra/f9-github-issues-criacao.md` | 4 repos | F8 | gbaemu#1-5, ndsemu#1-7, arm-jitter#1-5, armbox#1-3; manifesto preenchido, placeholders `#TBD-*` resolvidos, `Fecha:` nas 8 tasks relacionadas; `virtual-arm-box/01` pendente (sem remote); destrava P5 |
| ~~P5~~ | ~~**F2**~~ ✅ fechada 2026-08-15 — rename `linuxbox` → `virtual-arm-box` + abstração `Machine` | `trilha-f-infra/f2-rename-virtual-arm-box.md` | virtual-arm-box | F1 | diretório+pacote+artefato renomeados, `Machine`/`--machine=versatilepb` novo, `README.md`/`ROADMAP.md` novos, docs do arm-jitter atualizadas; `mvn -o test` verde (23 testes); destrava P15/P16 |
| ~~P6~~ | ~~**F4**~~ ✅ fechada 2026-08-15 — arm-jitter 1.0.0 preparado | `trilha-f-infra/f4-arm-jitter-1.0.0-escopo.md` | arm-jitter | F1 | bump `1.0`→`1.0.0` nos 4 POMs (`grep` de confirmação vazio); `maven.deploy.skip=true` no `capi/pom.xml` (jar da lib nativa não serve como dependência); `CHANGELOG.md` novo (Keep a Changelog PT-BR, entrada 1.0.0 resumida por capacidade a partir do `ROADMAP.md`/README); seção `## Versionamento` no README (semver, API pública = pacotes `arch`/`core`/`core64`/`memory`/`jit`/`codegen`/`coprocessor`/`swi`/`debug`/`ir`/`ir64` + factories padrão de `JitRuntimeFactory`); corrigida a linha "MMU / full-system 32-bit" do README (era 🟡 citando `PREFETCH_ABORT` recursivo no `linuxbox` — desatualizada desde que B4.1.5 fechou o épico por completo em 2026-08-14 com shell `busybox` interativo; virou ✅ citando `virtual-arm-box`) + o parágrafo B4 inteiro e a data do cabeçalho do `ROADMAP.md` (mesmo ajuste, "Onde estamos" 2026-07-31→2026-08-15); coordenadas Maven de exemplo no README também atualizadas para `1.0.0`. `mvn -o test` verde (core 1327 + truffle 13, JBR 25 `C:\Users\user\.jdks\jbr-25.0.3`) e `mvn -o install` verde, `dev.vitorsilverio:arm-jitter:1.0.0` instalado em `~/.m2` (o `1.0` antigo preservado como rede de segurança, conforme a spec pede). **G5 suspenso por desenho nesta task**: gbaemu/ndsemu/armbox/virtual-arm-box não foram tocados e ficam quebrados (pedem `arm-jitter:1.0`) até a F7 rodar — ⚠️ agende F7 logo em seguida, janela deveria ser curta |
| ~~P7~~ | ~~**F5**~~ ✅ fechada 2026-08-15 — publicado no Maven Central | `trilha-f-infra/f5-maven-central-publicacao.md` | arm-jitter | F4 | `dev.vitorsilverio:arm-jitter:1.0.0`+`arm-jitter-truffle:1.0.0` no ar (`repo1.maven.org` confirmado por `mvn dependency:resolve` sem instalação local); 2 bugs corrigidos (`capi` vazando pro bundle, plugin `0.7.0`→`0.11.0`); ver índice do `tasks/README.md` para o detalhe. Destrava P8/P9 |
| ~~P8~~ | ~~**F7**~~ ✅ fechada 2026-08-15 — consumidores no Central, sem `asm` declarado | `trilha-f-infra/f7-consumidores-central.md` | 4 consumidores | F5 | 4 POMs em `1.0.0`, `org.ow2.asm` removido de gbaemu/ndsemu (transitivo, confirmado); `mvn test` com `~/.m2/repository/dev/vitorsilverio` renomeada passou nos 4 (gbaemu 240/ndsemu 183/armbox 41/virtual-arm-box 23); docs corrigidas; commit por repo. Fecha a janela aberta pela F4/F6 |
| ~~P9~~ | ~~**F6**~~ ✅ fechada 2026-08-15 — CI verde nos 4 repos + release.yml escrito | `trilha-f-infra/f6-github-actions-pipeline.md` | 4 repos | F5 | 5 testes do gbaemu sem guarda (falhariam, não pulariam) ganharam `assumeTrue`; ndsemu/armbox não precisaram de nada; 4 execuções reais observadas verdes na primeira tentativa; `release.yml` escrito mas não testado ponta a ponta (decisão da spec — sem release vazia só pra provar); usuário ainda precisa cadastrar os 4 segredos; ver índice do `tasks/README.md` para o detalhe |
| ~~P10~~ | ~~**G1**~~ ✅ fechada 2026-08-15 — `n3dsemu`: esqueleto + loader `.3dsx` + primeira `svc` | `trilha-g-3ds/g1-esqueleto-n3dsemu.md` | n3dsemu (novo) | F7 | repo novo criado e commitado localmente; loader+memória+CP15/c13+SvcTable+`ARM11_MPCORE`; achado real documentado (convenção de imediato de `SVC` GBA/NDS vs. Horizon, sem tocar no decoder compartilhado); `n3dsemu testdata/application.3dsx --trace-svc` chega a `0x21 svcCreateAddressArbiter` idêntico nos 3 backends; `mvn -o test` verde (15); ver índice do `tasks/README.md` para o detalhe completo; destrava G2 |
| ~~P11~~ | ~~**G2**~~ 🟡 PARCIAL (2026-08-15, PR1+PR2; investigação 2026-08-16 confirmou desbloqueio + achou/corrigiu 1 bug novo) — kernel Horizon em HLE | `trilha-g-3ds/g2-kernel-hle-svc.md` | n3dsemu | G1 | PR2 fechou threads/sincronização/`AddressArbiter`/começo de IPC (ver índice do `tasks/README.md`); aceite objetivo "roda até `svcExitProcess`" ainda **NÃO alcançado**, mas o bloqueio original (FPSCR) está resolvido — ver nota abaixo, "Investigação 2026-08-16" |
| P12 | **G3** — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2 | `C:\devkitPro\libctru` só tem os headers instalados localmente, **NÃO o código-fonte** (achado da investigação 2026-08-16 — `find`/`ls` em `C:\devkitPro\libctru` mostra só `include/`+`lib/*.a`, sem `source/`; a task G3 presume fonte disponível, revisar antes de executar) — 3dbrew + o próprio `.3dsx` desmontado (`arm-none-eabi-objdump`) seguem como oráculo; **bloqueio FPSCR CONFIRMADO RESOLVIDO 2026-08-16** pela B3.8 (ver nota abaixo); **os 2 gaps de G2 achados na investigação 2026-08-16 foram FECHADOS numa sessão de continuação de G2 no mesmo dia** (SVC `0x39`/`svcGetResourceLimitLimitValues` implementado + bug real de endereços do heap geral/linear trocados corrigido — `n3dsemu testdata/application.3dsx` não panica mais, ver índice do `tasks/README.md`); **AINDA NÃO PRONTA PARA EXECUTAR** — a mesma sessão achou um blocker NOVO e diferente: com os dois heaps commitados, o backend JIT entra num laço indefinido chamando `svcCreateAddressArbiter` (`0x21`) sempre no mesmo PC (confirmado até 200 mil fatias sem sair sozinho) — nenhum `svcConnectToPort`/serviço de verdade é alcançado por trás desse laço. Provavelmente precisa de sincronização/escalonador cooperativo reagindo de verdade a esse padrão (possível G2.2) antes de G3 fazer sentido; INTERPRETED/CHECK progridem bem mais devagar por fatia que o JIT dentro do mesmo orçamento, então o aceite "JIT e `--interp` idênticos" da G2 também não foi revalidado ponta a ponta |
| P13 | **G4** — janela Vulkan apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3 | primeira imagem na tela |
| P14 | **G5** — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4 | LONGA, 3 PRs; aceite é **só** o `simple_tri` |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | 🟡 PARCIAL (2026-08-17, sessão de decode MCRR/MRRC, ver detalhe abaixo) — **M1 continua fechado**; o Oops de `v6_clear_user_highpage_aliasing` (`MCRR p15,0,r2,r3,c6` caindo em UNDEFINED) foi CORRIGIDO (decode novo no `arm-jitter` + repasse `handlesDouble`/`readDouble`/`writeDouble` pela cadeia de decorators CP15/CP14, que faltava e mascarava o fix); **M2 segue com status DESCONHECIDO** — a re-execução do teste JIT não concluiu em ~40min nesta sessão (abortada manualmente, `@Timeout` do JUnit não preempte laço apertado) — pode ter fechado, pode ter achado bloqueio novo, pode só ser lento aqui; próxima sessão precisa repetir com harness de progresso observável em vez do `@Test` cru; LONGA, 3 marcos |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | LONGA, 3 PRs; **mesmo repo que P15 — serializar com a F3**, nunca em paralelo (regra 6) |

## F3 (`virtual-arm-box --machine=raspi1`) — histórico condensado

M1 (banner de boot) ✅ fechado. Sessões 1/3 até a sessão de fechamento do CPSR.E
(10 sessões, 2026-08-15/16/17) corrigiram, em sequência: TLB de instrução/dados separada do
ARMv6K + sincronização de privilégio user/priv (CP15), 2 lacunas de decode/gating de CP15
(`CR_XP` no SCTLR, `CPACR`), IRQ do timer roteada para o comparador certo, um bug real de
alinhamento em `UNALIGNED_ACCESS` (leitura/escrita alinhada sendo decomposta byte a byte
sem necessidade — corrompia MMIO), `fetch16/32`/`translationGeneration` não encaminhados
pelos decoradores de invalidação, e o achado raiz do "abort storm": `CPSR.E`
não era reprogramado para `SCTLR.EE` na entrada de exceção (ARM ARM B1.8.3), causando leitura
big-endian de uma tabela de branch quando uma IRQ interrompia um `SETEND BE` legítimo do
kernel. **Detalhe completo de cada uma dessas 10 sessões: `tasks/FILA-HISTORICO.md`.**

As 2 sessões mais recentes (que fecharam o decode de `MCRR`/`MRRC` e avançaram até `/init`
rodar) ficam abaixo, na íntegra, por serem o contexto mais provável de ser necessário na
próxima sessão.

**F3 — sessão de extensão do `FdtPatcher` (2026-08-17) — `linux,initrd-start`/`linux,initrd-end`
IMPLEMENTADOS, M2 ainda NÃO fecha (bloqueio novo, bem mais tardio no boot, causa raiz NARROWED a
um opcode específico)**:

1. Seguiu o próximo passo recomendado pela sessão anterior: `FdtPatcher` (`virtual-arm-box`) ganhou
   `withInitrdRange`/`withNewProperty` — diferente de `withBootargs`/`withMemorySize` (que só
   sobrescrevem propriedades JÁ existentes), o método novo CRIA `/chosen/linux,initrd-start`/
   `linux,initrd-end` (ausentes no `.dtb` cru), reaproveitando o nome no bloco de strings se já
   ocorrer lá ou anexando um novo. `Bcm2835Machine#create` aplica o patch com
   `INITRD_LOAD_ADDR`/`INITRD_LOAD_ADDR + initramfs.length`. 2 testes novos em `FdtPatcherTest`.
2. **Efeito confirmado no boot JIT**: o `Kernel panic - VFS: Unable to mount root fs` da sessão
   anterior desaparece por completo — o kernel monta o initramfs e chega a executar `/init` de
   verdade (`Run /init as init process` no console, ~8min reais) — o mais longe que este
   repositório já chegou no boot do raspi1.
3. **M2 ainda NÃO fecha — bloqueio NOVO, mais tardio que qualquer sessão anterior**: logo depois de
   `Run /init as init process`, `Internal error: Oops - undefined instruction` em
   `v6_clear_user_highpage_aliasing+0x58/0x104` (chamado por `handle_mm_fault` tratando uma falta
   de página do `execve()` do `/init`) mata o processo `init` (`Attempted to kill init!`). Opcode
   decodificado a partir do dump `Code:` do Oops (`ec432f06`): `MCRR p15,0,r2,r3,c6` (encoding A1
   padrão de `MCRR`/`MRRC`, cond=AL, L=0→MCRR, Rt2=r3, Rt=r2, coproc=p15, opc1=0, CRm=c6) — uma
   transferência DUPLA de registrador para coprocessador, diferente do `MCR`/`MRC` de registrador
   único que `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` já tratam. Provável lacuna de DECODE no
   `arm-jitter` A32 (não só de despacho do `CoprocessorBus`) — nem gbaemu (ARMv4T), nem ndsemu
   (ARMv5TE), nem armbox (user-mode) jamais exercitaram `MCRR`/`MRRC`. Não investigado além da
   decodificação do opcode (fora do orçamento desta sessão).
4. **Achado colateral, não fatal**: duas ocorrências de `Division by zero in kernel` em
   `pl011_set_termios` (`div64_u64`) ao abrir `/dev/console`, bem antes do Oops de `init` — o
   kernel trata como exceção não fatal e o boot segue normalmente logo depois. Possível causa: um
   campo de clock/baud que `Pl011Uart`/mailbox devolve como `0` onde o driver espera um divisor
   não-zero — só observado, não investigado.
5. **Próximo passo recomendado**: (a) confirmar no decoder A32 do `arm-jitter` se `MCRR`/`MRRC` têm
   `case` próprio ou caem em UNDEFINED por ausência de decode (mais provável); se for lacuna de
   decode, é uma task nova do `arm-jitter` (mesmo precedente de escopo do BE8/B1.8); (b) só depois
   de `MCRR`/`MRRC` decodificarem, repetir o boot e ver se `execve("/init")` conclui; (c)
   investigar a divisão por zero do PL011 se voltar a aparecer de forma fatal. Backend INTERPRETED
   não foi re-executado nesta sessão (orçamento).
6. `mvn -o test` verde no `virtual-arm-box` (68 testes, 4 skipped = M2×2+M3×2, motivo do
   `@Disabled` atualizado); `VersatilePbBootTest` continua verde. Nenhum arquivo do `arm-jitter`
   tocado nesta sessão. F3 permanece 🟡 na fila "ATUAL".

**F3 — sessão de decode MCRR/MRRC (2026-08-17) — bug real CORRIGIDO (2 commits, arm-jitter +
virtual-arm-box), M2 re-teste INCONCLUSIVO (sessão interrompida por rate-limit + custo)**:

1. Confirmado o palpite da sessão anterior: `CoprocessorDecoder` (`arm-jitter`) não tinha `case`
   para `MCRR`/`MRRC` (bits[27:21]=`1100010`, espaço distinto de `MCR`/`MRC`) — caía direto em
   UNDEFINED por ausência de decode, não por rejeição deliberada. Decodificado com `IrOp`/
   `InstructionKind.COPROCESSOR_DOUBLE` novos, seguindo o padrão de `MCR`/`MRC` já existente
   (`Rt`/`Rt2` empacotados, `L` seleciona `MCRR`(0)/`MRRC`(1)). Registrador `c6` (o que
   `discard_old_kernel_data`/`copypage-v6.c` usa via `MCRR p15,0,Rt,Rt2,c6` para invalidar D-cache
   por faixa `[Rt,Rt2]` antes do `execve()` popular uma página) tratado em `Cp15VmsaCoprocessor`
   como RAZ/WI, mesmo precedente de `c7`.
2. **Segundo bug real achado ao integrar** (não estava na spec, mas sem ele o fix #1 não teria
   efeito nenhum no boot real): a cadeia de decorators `CoprocessorBus` do `virtual-arm-box`
   (`Bcm2835Cp14Extras` → `Bcm2835Cp15Extras` → `Cp15VmsaCoprocessor`, mesmo padrão em
   `VersatileCp15Extras` do versatilepb) não repassava `handlesDouble`/`readDouble`/`writeDouble`
   — o default de `CoprocessorBus#handlesDouble` é `false` e **não** delega automaticamente
   (decisão deliberada da própria interface, evita que um bus que só implementa `MCR`/`MRC`
   reivindique `MCRR`/`MRRC` por acidente) — então uma chamada de `MCRR` real nunca alcançava o
   fim da cadeia onde o fix #1 vive, mesmo com os testes de unidade de `Cp15VmsaCoprocessor`
   isolado passando. Corrigido nos 3 decorators (2 do BCM2835 + o do versatilepb, por simetria,
   mesmo sem uso real hoje).
3. `mvn -o test` verde no arm-jitter (1378 testes) + `mvn -o install`; G5 completo revalidado
   (sessão atual, não a anterior): gbaemu verde, ndsemu verde, armbox 40/41 (mesma falha
   pré-existente `Armv7TortureTest`/`VfpRegisters` documentada em toda sessão anterior da F3, não
   é regressão), `virtual-arm-box` verde (68 testes, mesmos 4 skipped).
4. **M2 JIT re-testado, resultado INCONCLUSIVO**: `reachesFreeingKernelMemoryAcceiteM2Jit` foi
   reativado e rodado de forma bloqueante — passou de ~40 minutos sem concluir (heap da JVM ainda
   crescendo lentamente, não travado num laço óbvio) e foi abortado manualmente. Isso NÃO é uma
   falha confirmada nem um sucesso confirmado — é desconhecido se o fix fecha M2, se há um
   bloqueio novo mais tardio, ou se o boot real deste ponto em diante é só mais lento que 40min
   (o histórico da F3 já viu boots de dezenas de minutos antes). Teste voltou a `@Disabled` com o
   achado documentado no Javadoc da classe.
5. **Causa da interrupção, registrada para a disciplina de custo**: a sessão original que fez #1/#2
   rodou em um agente de background que bateu o limite de sessão da conta (rate limit) no meio da
   tentativa de reexecutar o teste M2 — 3 rodadas de resume foram gastas só tentando esperar um
   processo de shell em background "acordar" o agente (não funciona; só filhos da ferramenta
   `Agent` notificam). Depois disso a continuação foi feita diretamente nesta sessão (sem
   subagente), que por sua vez também não conseguiu um resultado definitivo de M2 dentro de um
   orçamento razoável — daí a nova seção "Disciplina de custo" no topo deste arquivo.
6. **Próximo passo recomendado, concreto**: NÃO repetir o `@Test` cru de novo às cegas. Escrever
   um harness temporário (mesmo padrão `Raspi1DiagTempTest` de sessões anteriores, removido antes
   do commit) que imprime progresso periódico (PC, contagem de fatias, trecho do console) a cada
   N fatias, para ter visibilidade de "ainda progredindo" vs. "travado" sem esperar o teste inteiro
   terminar às cegas — só então decidir se vale a pena deixar rodar até o fim ou se há um bloqueio
   novo para investigar. Este é o real motivo de custo desta sessão: um teste opaco de longa
   duração sem visibilidade intermediária é caro de esperar E caro de interromper sem saber o que
   se perdeu.
7. Commits: arm-jitter (`bc4baf8`, decode MCRR/MRRC), virtual-arm-box (`fca8a38`, decorators +
   `@Disabled` do M2 atualizado). F3 permanece 🟡 na fila "ATUAL".

**Paralelismo permitido nesta onda** (regra 6: repos diferentes, nunca o mesmo checkout):
`P3/P4` (GitHub) ∥ `P2/P5` no começo; depois de P8, `P9` (4 repos) ∥ `P10+` (n3dsemu) ∥
`P15` (virtual-arm-box).

**Bloqueio novo descoberto na G2 (2026-08-15, sessão PR2) — RESOLVIDO 2026-08-15 pela B3.8 do
arm-jitter** (sessão separada, ver índice do `tasks/README.md`): com `svcCreateAddressArbiter`
implementado, `n3dsemu testdata/application.3dsx` progride além da primeira `svc` e travava no
`crt0`/newlib configurando a VFP com um FPSCR que `arm-jitter` rejeitava de propósito
(`FpscrRegister`, decisão nº 3 do épico B3: só IEEE round-to-nearest, sem `RMode`/`FZ`/`LEN`/
`STRIDE`). O usuário escolheu o caminho (1) do leque abaixo (revisitar a decisão de verdade, não
só aceitar-e-ignorar): `FpscrRegister` agora aceita os 32 bits sem lançar, com `RMode` (os 4
modos de arredondamento IEEE 754, via `DirectedFpRounding`) e `FZ` (flush-to-zero) IMPLEMENTADOS
de verdade nos executores VFP interpretados (o oráculo, G1); `LEN`/`STRIDE` são aceitos e
armazenados mas SEM semântica de vetor executada (decisão de escopo explícita da B3.8 — nenhum
consumidor real conhecido usa modo vetor de propósito). `mvn -o install` local do arm-jitter +
`mvn -o test` verde em gbaemu/ndsemu (G5). **Pendente**: uma sessão do n3dsemu precisa confirmar
que `templates/application` progride de verdade além do `crt0` agora — não testado nesta sessão
(fora do repo arm-jitter). Histórico da decisão (não mais em aberto, mantido por rastreabilidade):
três caminhos possíveis haviam sido levantados — (1) revisitar a decisão nº 3 do B3 no arm-jitter
suportando `LEN`/`STRIDE`/`FZ`/`RMode` de verdade — **ESCOLHIDO**; (2) task cirúrgica só para
ACEITAR (ignorar) esses bits sem lançar; (3) mudar o `crt0` do libctru/newlib para não gravar
esses bits — descartado, binário já compilado.

**Investigação 2026-08-16 (sessão n3dsemu, só reconhecimento — não implementou G3):**
confirmado com `mvn -o test`/execução direta de `n3dsemu testdata/application.3dsx
--trace-svc` (arm-jitter `1.0.0` local pós-B3.8, `mvn -o install` já refletido no `.m2`) que o
bloqueio de FPSCR está de fato resolvido — o boot passa inteiro pelo `crt0`/newlib configurando
a VFP sem lançar. **Achou e corrigiu 1 bug real do n3dsemu, não do arm-jitter**: as constantes
`HandleTable.CURRENT_PROCESS_HANDLE`/`CURRENT_THREAD_HANDLE` estavam com os valores
`0xFFFF8000`/`0xFFFF8001` TROCADOS em relação ao header real (`libctru/include/3ds/svc.h`:
`CUR_PROCESS_HANDLE=0xFFFF8001`, `CUR_THREAD_HANDLE=0xFFFF8000`), confirmado também por
`arm-none-eabi-objdump` no `.3dsx` real (`__system_allocateHeaps` passa literalmente
`0xFFFF8001` para `svcGetResourceLimit`, que só aceita handle de processo). Sem a correção,
isso fazia `svcGetResourceLimit` devolver `INVALID_HANDLE` e o guest reagia com
`svcBreak(PANIC)` sozinho, logo após passar da VFP — o que por um instante pareceu "o fix do
B3.8 não bastou", mas na verdade era um bug independente e pré-existente, só exposto porque a
execução finalmente chegou lá. Corrigido em `HandleTable.java` (commit `c939549`), `mvn -o test`
verde (84 testes), `Application3dsxTest` reescrito para documentar a cadeia completa.

**Novo limite real encontrado nesta investigação — FECHADO na sessão de continuação de G2 do
mesmo dia (2026-08-16)**: com o bug de handle corrigido, o boot avançava até `svc 0x39`
(`svcGetResourceLimitLimitValues`), que **não está na lista de SVCs da própria task G2** (só
lista `0x38`/`0x3A`) — mesmo padrão já visto com `svcCreateAddressArbiter` na PR2 (um SVC vizinho
que o crt0 usa e a spec original não previu). O `Main` real finge sucesso e segue (convenção já
estabelecida), então o guest continuava até um `svcControlMemory(MEMOP_ALLOC, addr=0x08000000,
...)` de verdade que devolvia falha para os parâmetros que o crt0 usa — e o guest chamava
`svcBreak(PANIC)` de novo. **Os dois foram corrigidos na sessão de continuação**: (1)
`svcGetResourceLimitLimitValues` implementado com valores de `COMMIT` plausíveis; (2) a causa
raiz real do `ALLOC` falhando não era o teto (embora zero também bastasse) — era um bug
arquitetural preexistente: `MemoryMap.LINEAR_HEAP_BASE`/`NEW_HEAP_BASE` deste projeto tinham os
endereços `0x08000000`/`0x14000000` TROCADOS em relação ao 3dbrew real (`Memory_layout`: o heap
GERAL, sem a flag `LINEAR`, é `0x08000000`; o heap LINEAR de verdade é `0x14000000`), confirmado
via `WebFetch` na wiki antes de corrigir. `n3dsemu testdata/application.3dsx` não panica mais.
Ver índice do `tasks/README.md` (linha G2) para o detalhe completo, incl. o blocker NOVO
encontrado depois (laço de `svcCreateAddressArbiter`). Também achado: `C:\devkitPro\libctru` só
tem `include/`+`lib/*.a` instalados localmente — **sem o código-fonte** que a spec da G3 presume
disponível como oráculo; 3dbrew + desmontagem do `.3dsx` seguem funcionando como substituto, mas
vale avisar quem for executar G3.

**Armazenamento (decidido 2026-08-15):** o `virtual-arm-box` usa **disco virtual em formato
padrão, compatível com outras VMs** — `raw` e **QCOW2**, ambos com leitura e escrita; VDI,
VMDK e VHD/VHDX são atendidos por `qemu-img convert`, sem código nosso. Primeiro controlador:
**PL181 MMCI (SD/MMC)** no `versatilepb`. Task **F10** (P16).

**Achado de ambiente 2026-08-15 — o QEMU 8.0.0 está instalado** (`C:\Program Files\qemu\`,
só binários, sem fonte): `qemu-img` (incl. **`check`**), `qemu-io` e `qemu-system-arm`. É
oráculo externo direto para a F10 (validar imagem QCOW2 que nós escrevemos) e para a F3
(bootar a mesma placa/kernel e comparar log serial). O **código-fonte** do QEMU continua
ausente — quem for transcrever periférico precisa buscá-lo no repositório público.

**[REFINAR] desta onda** (não executar; viram spec nova quando a dependência fechar):
**G6** (ROMs comerciais `.cia`/`.3ds`, bloqueada em dump de `boot9.bin`) e **G7** (trazer o
núcleo Vulkan para o ndsemu — condicional a a G5 dar certo).

- **Nota de ambiente (2026-07-31)**: esta máquina passou a ter GraalVM 25
  (`E:\graalvm-jdk-25.0.3+9.1`) + Visual Studio 2022 com MSVC (`vcvars64.bat`)
  disponíveis — o mesmo ambiente que várias tasks desta fila estavam
  bloqueadas esperando. **A9 PR1** (lib nativa `.dll` com API C, backend
  interpretado) foi executada e fechou ✅ nesta sessão (ver índice do
  `tasks/README.md`) — movida para fora da tabela 🧑 abaixo. **A8**
  (otimizações native-image: PGO/-O3/-march=native/G1, tabela startup/RSS)
  também foi executada e fechou ✅ na MESMA sessão (dependia só de A7, que já
  tinha fechado — a entrada "A7" na tabela 🧑 abaixo estava desatualizada,
  A7 não bloqueia mais nada além de A9 PR2): PGO+`-O3` venceu as 4 métricas e
  virou default do perfil `native` do armbox — ver índice do `tasks/README.md`
  e o README do armbox para a tabela completa. As DEMAIS tasks 🧑 (C7, A9 PR2,
  C10, B4.0.3 item 3, B6.2 aceite #2, B6.6.6) continuam bloqueadas por motivos
  DIFERENTES do ambiente GraalVM+MSVC (validação de gameplay, o bailout SVM do
  Truffle em si — não falta de ambiente —, medição com ROM real, toolchains
  `arm-linux-*`/`aarch64-linux-*`/kernel real) — não presumir que esta nota as
  destrava, conferir a coluna "O que precisa do usuário" de cada uma antes de
  pegar. **Fila automática volta a ficar vazia após A8/A9 PR1** — nenhuma task
  elegível sem prioridade do usuário.

## Épico B6 (AArch64) — histórico condensado

B6.1 até B6.6.5 fecharam entre 2026-07-24 e 2026-07-27 (decoder A64 completo, MMU VMSA64,
modelo de exceção EL0↔EL1, FP/SIMD escalar interpretado+ASM nativo, `translationGeneration`
em `jit64`). **Detalhe completo de cada sub-task: `tasks/FILA-HISTORICO.md`.** Resumo do que
falta:
Backlog sem prioridade definida (não pegar sem o usuário priorizar): B6.4 (backend ASM 64-bit) fechou os 3 PRs (ver histórico abaixo) — só resta,
como pendência EXPLÍCITA fora do escopo de qualquer PR (registrador-cache sem consumidor A64
medido ainda, D0/D-ASM) ou bloqueada no ambiente (bench busybox-aarch64), ver a seção 🧑 abaixo.
B6.5 (FP/SIMD escalar) decomposta em B6.5.1-B6.5.4 (2026-07-26) — **B6.5.1 ✅ fechada
(2026-07-26, ver histórico abaixo)**; **B6.5.2 ✅ fechada (2026-07-27, priorizada pelo usuário,
ver histórico abaixo)** — `Ir64Op`s de FP + executor interpretado; **B6.5.3 ✅ fechada (2026-07-27,
ver histórico acima)** — decoder da classe "Data Processing — Scalar FP" (`bit26=1`); **B6.5.4 ✅
fechada (2026-07-27, ver histórico acima) — fecha o épico B6.5 por completo** (emissão ASM
nativa de FP, `Ir64NativePolicy`/`Ir64BlockCompiler` estendidos, sem meta de performance por
decisão D1). B6.6 (MMU v8 +
hospedeiro `virt64`) decomposta em B6.6.1-B6.6.6 (2026-07-26) — B6.6.1-B6.6.5 já fecharam (ver
histórico acima), **épico quase 100%**; só falta B6.6.6 (hospedeiro `virt64`), que já nasce
bloqueada no usuário, ver seção 🧑 abaixo. Ver `b6-aarch64.md` para o detalhe completo de cada
sub-task.
## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) | **C9** (fastmem ndsemu, `trilha-c-perf/c9-jit-fastmem-ndsemu.md`) |
| ~~A7~~ ✅ fechada 2026-07-27 (medição concluída, resultado misto — ver índice do `tasks/README.md`); entrada mantida só para registrar que **A9 PR2** segue bloqueada pelo PRÓPRIO resultado da A7 (bailout SVM não fechou), não por falta de ambiente | `trilha-a-truffle/a7-native-image-revalidacao.md` | — (fechada; causa raiz do bailout SVM precisa de sessão de modelo forte dedicada) | **A9 PR2** só quando o bailout SVM for corrigido |
| ~~A9 PR1~~ ✅ fechada 2026-07-31 (ambiente GraalVM+MSVC ficou disponível nesta máquina — ver nota abaixo) | `trilha-a-truffle/a9-native-shared-library.md` | — | A9 PR2 segue bloqueada em A7 (bailout SVM do Truffle não fechou) |
| ~~A8~~ ✅ fechada 2026-07-31 (mesma sessão desta nota de ambiente — task mecânica de build+medição, não precisava de validação humana além do ambiente GraalVM+MSVC já confirmado disponível) | `trilha-a-truffle/a8-native-image-otimizacoes.md` | — | PGO+`-O3` promovido a default do perfil `native` do armbox — ver índice do `tasks/README.md` |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real | fecha de vez a C10 |
| **B4.0.3 item 3** — busybox estático Thumb-2 (armbox) | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | Toolchain `arm-linux-*` real (musl/glibc) — ex. WSL com distro configurada + build tools, ou um cross-toolchain Windows-hosted; o musl.cc é ELF Linux (não roda em MSYS2) e o devkitARM instalado é bare-metal | fecha B4.0.3 por completo e destrava **B4.0.5** |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2, item 4) | Fonte confiável de busybox estático arm64/aarch64 real (busybox.net só publica `armv8l`, que é ARM 32-bit — ISA errada) OU um toolchain `aarch64-linux-*` (musl/glibc) para compilar da fonte, já que o devkitA64 instalado é bare-metal (`aarch64-none-elf`) | fecha B6.2 por completo (aceite #1, `hello-aarch64.elf`, já fechado 2026-07-24), **o aceite agregado do épico B6.3** ("`busybox sh -c` completo no armbox64", já com as 4 sub-tasks B6.3.1-B6.3.4 fechadas) **e o bench "busybox ≥3× interpretador" do PR3 de B6.4** (codegen fechado 2026-07-26, só falta medir) — mesmo bloqueio, um só ambiente resolve os três |
| **B6.6.6** — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Kernel arm64 mainline real (pré-compilado ou toolchain para buildar) + idealmente um initramfs busybox aarch64 real — mesmo bloqueio de toolchain/binário de B6.2 aceite #2/B4.0.3 item 3, um só ambiente resolve os três; adicionalmente, GICv2/GICv3/PSCI/DTB são substancialmente mais complexos que os periféricos versatilepb do precedente B4.1.5, reservar tempo de sessão maior | fecha o épico B6.6 por completo (depende de B6.6.1-B6.6.5, rodada de spec 2026-07-26, ver `b6-aarch64.md`) |

## Fila de BUGS de compat (trilha D) — sessões separadas da fila principal

Backend-independentes (confirmado 2026-07-16: INTERPRETED e ASM idênticos em
sintoma e velocidade no GBA — a atribuição antiga ao ASM está REVOGADA).
D2/D3/D4 já fechadas (ver índice).

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| ~~D5~~ 🟡 fix implementado 2026-07-17 (ndsemu `e9bebfd`: fim de canal one-shot do SPU agora corre no tempo emulado — o título esperava a sequência one-shot "terminar") — **falta só validação do usuário na GUI** (título→menu→novo jogo) | — | 🧑 usuário |
| ~~D5/Buneary~~ ✅ FECHADA 2026-07-18 (arm-jitter `65a9a66`, **user-validada na GUI**): bloco JIT stale da troca de overlay — invalidação só olhava o endereço-base da escrita, e um bloco THUMB de 1 instrução em X≡2 (mod 4) escapava da cópia em words; o `b` do overlay 77 (título) executava dentro do overlay 73 e pulava o init da animação. Fix = invalidação por intervalo da escrita; A/B no ROM real + validação live. Diagnósticos removidos do ndsemu (`2295157`) | — | — |
| (sem task, ORÁCULO APLICADO 2026-07-19) Platinum billboard do char invisível = DIVERGÊNCIA DE ALOCAÇÃO de VRAM de textura (byte-diff bank A ndsemu×melonDS: 0x0-0x5000 idêntico, 0x5000-0xB800 diverge = texturas do char só no melonDS onde o billboard lê, 0x10000+ = lixo só no ndsemu = textura obsoleta não liberada). Upstream tex-manager, não render nem fix de fase (6f72757 correto). Multi-sessão: tracear o alocador de textura do jogo. Detalhes na memória `ndsemu-game-compat` | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi (multi-sessão, pedido do usuário 2026-07-22)** — Fase 1 (fundação de hardware `wifi/WifiController`) SHIPPED (ndsemu `0c7d17e`): register file + BB/RF + WiFi RAM + timer; self-test do NitroWM passa, init de HW roda até o fim. Continue do Platinum AINDA erra (falta handshake WM ARM9↔ARM7 "ready"). Roadmap (Fase 2 ready→Continue funciona; Fase 3-5 RX/TX + AP falso + bridge de rede real p/ GTS via DNS alternativo) na memória `ndsemu-wifi-stack` | ndsemu | ⚠️ MODELO FORTE |
| (sem task, NOVA 2026-07-17) Platinum NÃO boota em INTERPRETED — ARM9 preso no handshake IPCSYNC do `PXI_Init` (`0x020C640C`) desde o frame 0; em ASM boota. Achado colateral da D5 | ndsemu; race de boot cross-CPU backend-dependente | ⚠️ MODELO FORTE |
| (sem task) Divergência ASM×interp no JUS | ver pendência 6 do tasks/README | ⚠️ MODELO FORTE |

