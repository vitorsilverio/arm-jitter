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
| ~~P11.5~~ | ~~**G2.2**~~ ✅ fechada 2026-08-18 — causa raiz do "laço" achada + fix pequeno aplicado | `trilha-g-3ds/g2.2-address-arbiter-loop.md` | n3dsemu | G2 | NÃO era uma SVC retentando: trace de duas fases (`ArmCore#step()` puro, sem JIT — achado vale para os 2 backends) mostrou o boot inteiro reiniciando do zero a cada 271.058 instruções porque `srvInit` executa `MCR p15,0,r0,c7,c10,{5}` (`DMB`, ARMv6K) que `N3dsCp15` não reconhecia → `ArmException.UNDEFINED` → sem vetor de exceção configurado, PC caía em 0x4 e andava até tropeçar de volta em `0x100000`. Fix: `DSB`/`DMB` como no-op em `N3dsCp15`. Boot agora alcança `svcSendSyncRequest` (fronteira real com G3) nos 3 backends. `mvn -o test` verde (94 testes). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G3** |
| ~~P12~~ | ~~**G3**~~ 🟡 PARCIAL (2026-08-18) — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2.2 | codec IPC + `ServiceRegistry` + 7 serviços implementados, `svcSendSyncRequest` despacha de verdade. 2 achados reais corrigidos: `Loader3dsx` não alinhava segmentos a página (bloqueava TODO `srv:GetServiceHandle`) + fila de interrupção do `gsp::Gpu` precisa ser populada, não só o evento sinalizado. `mvn -o test` verde (118). **Aceite NÃO fechado**: `read-controls.3dsx` não sai sozinho via `--script`+START (trava no loop `ClearEvent`/`WaitSynchronization` da thread de relay do gsp, prioridade mais alta nunca devolve controle); raiz não isolada — candidato a **G3.2**. Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.5~~ | ~~**G3.2**~~ 🟡 PARCIAL (2026-08-18) — investigar a inanição do loop de `read-controls.3dsx` (thread de relay do `gsp::Gpu`) | `trilha-g-3ds/g3.2-gsp-relay-starvation.md` | n3dsemu | G3 | causa raiz do sintoma ORIGINAL achada e corrigida (bug real: `RegisterInterruptRelayQueue` devolvia o "GSP module thread index" errado, `1` em vez de `0` — guest lia/escrevia no bloco de fila ERRADO da memória compartilhada); revelou bloqueio NOVO mais profundo (main thread nunca reescalonada depois de criar a thread de relay, mesmo com o relay drenando certo) — virou **G3.3** (ver linha abaixo: causa raiz achada, não é o `Scheduler`). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.6~~ | ~~**G3.3**~~ ✅ fechada 2026-08-18 — causa raiz do não-reescalonamento achada + corrigida | `trilha-g-3ds/g3.3-main-thread-never-rescheduled.md` | n3dsemu | G3.2 | trace com `LR` (não só `PC`) localizou o chamador real: `T1` bloqueava em `svcArbitrateAddress` esperando `gspEvents[2]` (`GSPGPU_EVENT_VBlank0`, endereço `0x0011D26C = 0x11D25C + 2*8`, aritmética fechada via desmontagem de `gspWaitForEvent`/`gspEventThreadMain` cruzada com `WebFetch` no libctru real). **Causa raiz não era um evento faltando** — era `GspGpuService#pushInterrupt` escrevendo a entrada nova no CURSOR DE LEITURA da fila (campo que pertence só ao cliente, confirmado contra 3dbrew + `popInterrupt()` real) e avançando esse mesmo campo, em vez de derivar o índice como `(readCursor+count)%CAPACITY` — a 1ª interrupção real (`PDC0`=2) acabava sinalizando `gspEvents[0]` em vez de `gspEvents[2]`. Corrigido em `GspGpuService.java`; teste de regressão unitário novo; confirmado ao vivo (`--slices=200`) que a main thread agora reescalona a cada VBlank e entra no laço normal de `read-controls` (não sai sozinho ainda — sem input real, fora do escopo). `mvn -o test` verde (124). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G4** |
| ~~P13~~ | ~~**G4**~~ 🟡 PARCIAL (2026-08-19) — janela Vulkan (LWJGL 3 + GLFW) apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3.3 ✅ | implementado por completo: POM com LWJGL 3 (BOM + profiles de natives por SO, `lwjgl-vulkan` sem classifier em Windows/Linux); `gpu/` novo (`Screen`/`PixelFormat`/`PicaRenderer`/`RecordingRenderer`/`FrameBufferCodec`/`FrameBufferState`/`GuestFrameBufferReader`); `GspGpuService` agora INTERPRETA `SetBufferSwap` de verdade (antes descartava, G3: "descarta tudo") e expõe `FrameBufferState`; `gpu/vulkan/VulkanRenderer` com pipeline Vulkan completo (instância/dispositivo/swapchain/render pass/pipeline único de quad-cheio/2 texturas/upload via staging buffer/sync 2 quadros em voo), shaders GLSL como recursos do jar compilados via `lwjgl-shaderc` em runtime (`present.vert`/`present.frag` — rotação retrato→paisagem em UMA linha de GLSL, conforme a task pede); `Main` com janela como DEFAULT + `--headless` preservando o comportamento da G3; teclado (mapeamento fixo) e mouse (touch) ligados via GLFW. **Bug real encontrado e corrigido nesta sessão**: `VulkanRenderer#close()` chamava `vkDestroySampler` DENTRO do laço por tela (2 texturas = destruía o mesmo sampler compartilhado duas vezes) — Vulkan não valida isso sem validation layers, e o double-destroy corrompia o heap nativo (`STATUS_HEAP_CORRUPTION`, processo morria sem stack trace/hs_err; isolado por bisecção com prints de progresso rodando a JVM fora do Surefire). `mvn -o test` verde (inclui `VulkanRendererSmokeTest`, que roda de verdade contra o driver Vulkan real desta máquina — não só `RecordingRenderer` — e prova que inicializar/apresentar um quadro sintético/fechar não lança; guarda com `Assumptions`/`LinkageError` para CI sem GPU, RFC D4). Smoke-run manual confirmado: `n3dsemu testdata/hello-world.3dsx` roda 10s em janela sem crashar/exceção nenhuma; `--headless` continua idêntico à G3. **Não fechado por completo**: falta só o passo HUMANO explícito do Aceite ("anexe uma captura de tela" — RFC D4: "nenhuma task da trilha G pode ter como aceite automatizado 'o triângulo apareceu'", validação é sempre visual/do usuário) — esta sessão não tem como capturar/julgar a imagem. Usuário: rode `n3dsemu testdata/hello-world.3dsx` e confirme visualmente (texto legível, orientação correta nas duas telas) para fechar G4 de vez e destravar G5 |

**Sessão de validação 2026-08-19 (usuário tentou confirmar G4 visualmente — ainda preto, 2 bugs
reais corrigidos, 2 achados novos viram task)**: `hello-world.3dsx` abria em janela preta, sem
texto algum. Corrigidos 2 bugs reais do n3dsemu (commit `f36a6aa`): (1) `aptInit` real abre 3
sessões (`APT:U`/`APT:S`/`APT:A`) para o mesmo serviço — só `APT:U` estava registrado, `APT:S`
falhava e abortava o boot antes de qualquer desenho (`ServiceRegistry` ganhou `registerAlias`);
(2) o libctru moderno (2.7.0) não chama mais `GSPGPU:SetBufferSwap` por IPC a cada quadro, escreve
direto no `FrameBufferUpdate` da memória compartilhada do `gsp` (3dbrew) — só o caminho antigo por
IPC estava implementado; `GspGpuService#onVBlank` agora lê esse bloco. Mesmo assim a tela continua
preta: rastreado até `APT:U GetSharedFont` (cmd `0x44`) não implementado — sem fonte mapeada o
console não tem o que desenhar (framebuffer troca de endereço certo a cada quadro, mas fica
100% zerado, confirmado varrendo o buffer inteiro). Virou task **G4.1** (ver tabela acima). Achado
colateral mais sério: com os 2 fixes, o boot avança mais longe e `mvn -o test` do n3dsemu expõe um
bug PRÉ-EXISTENTE do `arm-jitter` (confirmado via `git stash`, não introduzido por este commit) —
divergência real ASM×interpretador em `StoreExclusive` (STREX), padrão de spinlock/retry sob
`ARM11_MPCORE`. Virou task **E3** no `arm-jitter`. `mvn -o test` do n3dsemu: 2 falhas (a
divergência STREX), não corrigidas nesta sessão — commit feito mesmo assim a pedido do usuário
(os 2 fixes são corretos e uma melhoria real, mesmo sem fechar a suíte). |
| P13.5 | **G4.1** — `APT:GetSharedFont` (fonte do sistema para o console) | `trilha-g-3ds/g4.1-apt-shared-font.md` | n3dsemu | G4 | ⏸️ **PARADA (sessão 2026-08-19, passo "Inclui" 1 — confirmar a hipótese): HIPÓTESE REFUTADA por evidência, não implementado.** Ver nota abaixo ("Sessão 2026-08-19 (G4.1) — hipótese de GetSharedFont refutada") — usuário decidiu o próximo passo: task nova **G4.2** (ver linha abaixo), não é a causa da tela preta |
**Sessão 2026-08-19 (G4.1) — hipótese de `GetSharedFont` REFUTADA (passo 1 do "Inclui": "confirmar a
hipótese... não presumir")**: a task pedia confirmar, com trace, que `APT:GetSharedFont` era chamado
e falhava antes de implementar o fix. Rodando `n3dsemu testdata/hello-world.3dsx --headless
--trace-svc` com orçamento de fatias bem maior que o da sessão anterior (200→3000, chega a executar
o laço principal do app em regime estacionário por milhares de iterações, log completo capturado, não
só as últimas 32 chamadas do ring buffer) o comando `0x0044` (`GetSharedFont`, confirmado via
`WebFetch` na 3dbrew: `NS_and_APT_Services`, cabeçalho `0x00440000`) **nunca aparece nem uma vez** —
nenhum log `[APT:U] comando desconhecido 0x0044`. Cruzando com o fonte real do libctru
(`libctru/source/console.c` via `WebFetch` no GitHub `devkitPro/libctru`): `consoleInit`/
`consoleDrawChar` usam uma fonte **embutida no próprio binário** (`default_font_bin`, bitmap 8×8
compilado estaticamente), *não* a fonte do sistema via `APT_GetSharedFont` — essa API só é usada
pelo módulo de fontes do `citro2d`/`citro3d` (texto TTF-like), que o `hello-world` (baseado em
`console.c` puro) não usa. **Confirmado também no fonte do próprio exemplo**
(`C:\devkitPro\examples\3ds\graphics\printing\hello-world\source\main.c`): `printf` roda antes do
laço principal; nenhuma chamada relacionada a fonte aparece em lugar nenhum. Conclusão: a tela preta
**não tem relação com fonte nenhuma** — a causa real está em algum outro lugar do caminho
`consoleInit`→`printf`(escreve direto no framebuffer via ponteiro de `gfxGetFramebuffer`)→
`gfxSwapBuffers`→`GspGpuService`/`FrameBufferState`→`Main#presentFrame`. **Não implementado**
(rodar o toolset `JayFoxRox/3ds-font` teria sido trabalho desperdiçado sobre uma hipótese falsa) —
devolvida ao usuário: precisa de uma sessão nova de investigação (candidata a task **G4.2**) para
achar a causa real, provavelmente rastreando byte a byte o que `consoleDrawChar` escreve no
framebuffer do guest vs. o que `GuestFrameBufferReader`/`presentFrame` de fato lê (mesma técnica de
dump de memória já usada em outras investigações desta fila). `mvn -o test` não rodado nesta sessão
(nenhum código tocado). Nenhum commit desta sessão além da atualização deste arquivo.

| ~~P13.6~~ | ~~**E3**~~ ✅ fechada 2026-08-19 — causa raiz achada + corrigida: bug do harness, não de LDREX/STREX | `trilha-e-manutencao/e3-strex-asm-interp-divergence.md` | arm-jitter | — | causa raiz NÃO era semântica de STREX em nenhum backend — era `DivergenceCheckingCodeEmitter` restaurando o `scratchCore` do candidato via `ArmCore#loadState` a CADA bloco, que limpa o monitor de exclusividade de propósito (correto p/ save-state real, errado aqui); um `STREX` no bloco seguinte a um `LDREX` (padrão spinlock/retry) sempre via o monitor aberto no candidato. Fix aditivo: transfere a reserva do core real pro scratch após o restore. `mvn -o test` verde (arm-jitter core+truffle) + `mvn -o install`; G5: gbaemu/ndsemu/n3dsemu verdes (os 2 testes do n3dsemu citados na task voltam a passar), armbox 40/41 (falha pré-existente não relacionada). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P13.7~~ | ~~**G4.2**~~ ✅ fechada 2026-08-19 — causa raiz real achada e corrigida: `descriptorCount` ausente no `VkWriteDescriptorSet` | `trilha-g-3ds/g4.2-tela-preta-sem-fonte.md` | n3dsemu | G4.1 (hipótese refutada) | Passo 1 (dump de memória) refutou a premissa da própria task: `hello-world.3dsx` escreve pixels reais em `Screen.TOP` e a cadeia até `GuestFrameBufferReader` está correta (teste `HelloWorldFramebufferTest`). Passo 2/3: usuário confirmou a janela AINDA preta rodando de verdade — instrumentação (System.err temporário) provou milhares de `uploadPending`/`renderFrame` reais com dados de textura corretos, ainda preto; **achado real**: `-Dn3dsemu.vulkan.validation=true` (validation layers, já existiam no código) acusou `VUID-vkCmdDraw-None-02699` — o descriptor set nunca foi atualizado. Causa: `createScreenTexture` monta o `VkWriteDescriptorSet` via `calloc` (zerado) e nunca chama `.descriptorCount(1)` — `vkUpdateDescriptorSets` virava um no-op silencioso, sem erro de API (só a validation layer acusa, e só no `vkCmdDraw` seguinte, não no próprio update — por isso passou despercebido nas sessões G4/G4.1 sem validation ligada). Fix de 1 linha (`VulkanRenderer.java`), usuário confirmou visualmente ("agora imprimiu"). `mvn -o test` verde (136). **Destrava G5** |
| P14 | **G5** — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4.2 ✅ | LONGA, 3 PRs; aceite é **só** o `simple_tri`; a cadeia de apresentação 2D (framebuffer→codec→Vulkan) está confirmada funcionando ponta a ponta (G4.2) |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA a pedido do usuário em 2026-08-18** — 6 sessões de diagnóstico sem fechar M3 (ver resumo abaixo); a sessão 6 tentou usar `qemu-system-arm -M raspi1ap` como oráculo e o PRÓPRIO QEMU travou aos 2,5s com este kernel/DTB (lacuna do modelo `raspi1ap`, não bug nosso) — sinal de que este `kernel.img` real (6.18.33) pode estar puxando periféricos/caminhos de código bem além do que emuladores minimalistas cobrem sem esforço desproporcional. **Não pegar automaticamente** — só retomar se o usuário priorizar de novo, possivelmente reavaliando o kernel/DTB alvo em vez de insistir no mesmo |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA junto com F3** (mesmo repo, regra 6 de serialização — sem sentido priorizar isolada enquanto F3 está parada) |
| ~~P18~~ | ~~**B6.6.7**~~ ✅ fechada 2026-08-18 — AArch64: superfície mínima de EL1 para kernel real | `trilha-b-arquiteturas/b6.6.7-aarch64-el1-kernel-surface.md` | arm-jitter | B6.6.4 | priorizada pelo usuário em resposta direta ao bloqueio achado pela F11; estendeu `Aarch64SystemRegisterId` (identidade da CPU resolvida intrinsecamente + timer genérico host-pluggável), decodificou `WFI`/`WFE`/hints/`HVC`/`SMC`, confirmou `ERET` já existente (B6.6.4, sem mudança), e deu a `Aarch64Core` um mecanismo mínimo de IRQ (`interruptLine`/`enterIrq`, espelho de `enterMemoryAbort`) + `PstateRegister.irqDisabled` (bit `I` de `DAIF`) — GIC/PSCI ficam fora, são responsabilidade do hospedeiro. `mvn -o test` verde (core 1380 + truffle 13), `mvn -o install` local feito, G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava F11 e B6.6.6** |
| ~~P17~~ | ~~**F11**~~ 🟡 PARCIAL (2026-08-18, sessão 2) — `Raspi364Machine` implementada, boot bloqueado num gap de decode real (`CCMP`/`CCMN`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.6.7 ✅ | Itens 3-5 do "Inclui" fecharam: `Machine64`/`RunnableMachine` (interface nova, `Machine` existente é 32-bit-only), `device/bcm2836/` (`Bcm2836GenericTimer`+`Bcm2836LocalIntc`), `boot/CompositeSystemRegisterBus`, `Raspi364Machine` completa, registrada no `Main` (`--machine=raspi3-64`). Máquina/DTB/carga provados corretos byte-a-byte, mas a PRIMEIRA instrução real do `kernel8.img` (`ccmp x18,#0,#0xd,pl`, truque polyglot EFI "MZ" — presente em praticamente todo `kernel8.img` distribuído) esbarra num gap de FEATURE real do `arm-jitter`: `CCMP`/`CCMN` nunca foram implementados em nenhuma sub-task de B6.3. Fora do "Inclui" desta task (não é bug); precisa de sub-task nova no arm-jitter para o usuário priorizar. `Raspi364BootTest` pina o achado como regressão; 3 testes de marco textual ficam `@Disabled`. `mvn -o test` verde no virtual-arm-box; G5 não se aplica (task não pede, nenhum arquivo arm-jitter tocado). Ver índice do `tasks/README.md` para o detalhe completo. **Retomar exige uma sub-task `CCMP`/`CCMN` no arm-jitter primeiro** |

## F3 (`virtual-arm-box --machine=raspi1`) — resumo (histórico minucioso movido para `tasks/FILA-HISTORICO.md`)

M1 e M2 ✅ fechados (JIT e INTERPRETED). M3 (shell interativo): a sessão de
`FdtPatcher#withNodeDisabled` (2026-08-17) fechou DOIS bloqueios reais — retry infinito de
`mmc0`/`sdhost` e uma espera síncrona silenciosa de `usb`/`dwc_otg` — desabilitando os nós
`mmc@7e202000`/`usb@7e980000` no `.dtb` via `status = "disabled"`. Com os dois fechados, o boot
chega de novo a `"Run /init as init process"`, mas revelou um TERCEIRO bloqueio.

**Sessão de diagnóstico 1/2 (2026-08-18)**: amostragem barata (sem trace completo) descartou `WFI`
sem IRQ e tempestade de IRQ — a CPU fica `RUNNING`/`SUPERVISOR` o tempo todo. Histograma de PC
mostrou o console parando de crescer bem cedo enquanto a CPU segue ativa presa em ~20 PCs
estáveis. Desmontagem estática levantou a hipótese (NÃO confirmada) de bug de `LDREX`/`STREX`/DACR
no `arm-jitter`.

**Sessão de diagnóstico 2/2 (2026-08-18)**: trace instrução-a-instrução (fast-forward JIT +
`ArmCore#step()`) **DESCARTOU** essa hipótese com evidência dinâmica: o loop real é um corpo
determinístico de 157 instruções em `0xc05b1750`-`0xc05b18c4` (não o código hipotetizado por
disassembly estático) onde TODOS os registradores amostrados (`r0`-`r4`/`r6`/`r9`/`r13`/`r14`)
voltam bit-a-bit idênticos a cada período (20+ repetições); DACR faz round-trip correto
(`0x55`→`0x55`) e `LDREX`/`STREX` sucedem de primeira em 2 call-sites distintos, sem retry — não é
bug de `arm-jitter`. Timer ainda entrega IRQ nesta janela (27 em 100k `runSlice`). Achado colateral:
o "Division by zero" já documentado (`pl011_set_termios`) acontece 2x ao abrir `/dev/console`,
não-fatal; o travamento real é logo depois, dentro do `execve("/init")`, antes de qualquer saída do
PID 1. Próximo passo: identificar a 3ª sub-rotina chamada pelo loop (`0xc02529b4`, ainda não
identificada) e inspecionar CONTEÚDO DE MEMÓRIA (não só registradores) — a condição de saída não
depende de nenhum registrador de propósito geral observado. Ver Javadoc de `Raspi1BootTest`
(`virtual-arm-box`) para o achado completo. `mvn -o test` verde no `virtual-arm-box`; nenhum
arquivo do `arm-jitter` tocado (hipótese de bug real ali agora descartada, não só não confirmada).

**Sessão de diagnóstico 3 (2026-08-18)**: seguiu o passo (b) — inspeção de CONTEÚDO DE MEMÓRIA, não
só registradores, em `[0x0014622d]` (alvo do `strb` de prova) e `[0xc1558c2c]` (contagem do
`rw_semaphore`). Rodando com um orçamento de estagnação maior, o console progrediu MAIS do que
qualquer sessão anterior tinha visto (`thermal thermal_zone0: ...` em kernel time 699s, muito além
do "silêncio" pós-`Run /init` documentado antes em 482s) antes de travar de vez no mesmo loop de 157
instruções. Nos 12 períodos observados (1805 passos), as DUAS memórias vigiadas ficaram **bit-a-bit
estáticas** (`0x00002d00`/`0x00000100`) — fecha a lacuna da sessão anterior: não há progresso
invisível em memória nesses dois pontos. `rw_semaphore` travado com exatamente 1 leitor
(`0x100` = `RWSEM_READER_BIAS`), nunca liberado. Hipótese refinada: o bug provavelmente está no
CHAMADOR do loop (que deveria avançar um contador/endereço entre chamadas e não avança), não na
subrotina chamada em si. Próximo passo recomendado: ler `thread_info->flags`/`preempt_count` (via
`lr = *(TPIDRURO+0x520)`) para checar `TIF_NEED_RESCHED`, ou tracear o PRIMEIRO período (não os
últimos) para ver o valor inicial do "laço externo". `mvn -o test` verde no `virtual-arm-box`;
harness temporário não commitado; nenhum arquivo do `arm-jitter` tocado. F3 segue 🟡 na fila
"ATUAL".

**Sessão de diagnóstico 4 (2026-08-18, `0a745ff`)**: rotina IDENTIFICADA por correspondência
byte-a-byte contra o fonte real do kernel (`arch/arm/lib/uaccess_with_memcpy.c`, baixado via `curl`
direto ao GitHub — `WebFetch`/Bootlin não serve fonte cru) — o loop de 157 passos é
`__copy_to_user_memcpy()`/`pin_page_for_write()`, chamado por `arm_copy_to_user()` durante
`execve("/init")` copiando `argv`/`envp`. Instrumentação de `onMemoryAbort` durante ~5,1M fatias
mostrou **exatamente 1 abort em todo o boot**, no `strb` esperado (`0xc05b189c`) e no endereço
esperado (`0x0014622d`) — prova que o `AP`/DACR do `arm-jitter` FUNCIONA (falta entregue, corrigida,
escrita nunca mais falta), mas `pin_page_for_write()` continua falhando para sempre porque os bits
de contabilidade SOFTWARE da PTE (`pte_young`/`pte_dirty`/`pte_write`) nunca refletem o conserto —
descarta de vez a hipótese de bug genérico de permissão/DACR (3 sessões já tinham testado isso).
Próximo passo: dump da palavra de PTE de `0x0014622d` antes/depois do abort, comparado bit a bit
contra `arch/arm/include/asm/pgtable-2level.h` (mesma técnica de `curl` desta sessão). Ver Javadoc de
`Raspi1BootTest` para o detalhe completo. `mvn -o test` verde (78, 2 skipped); nenhum arquivo do
`arm-jitter` tocado.

**Sessão de diagnóstico 6 (2026-08-18) — tentativa de oráculo QEMU, sem sucesso (achado
negativo)**: antes de partir para a arqueologia de `mm_struct`/maple-tree recomendada pela sessão
5, tentou-se o atalho mais barato: bootar os MESMOS `kernel.img`/`bcm2708-rpi-b.dtb`/
`initramfs.cpio.gz` desta task no `qemu-system-arm -M raspi1ap` (QEMU 8.0.0 instalado) para
comparar. **Não deu para comparar**: o próprio QEMU trava aos 2,5s de tempo de kernel — bem antes
do bloqueio desta task (que só acontece no `execve("/init")`, depois de `mmc`/`usb`) — com um
`external abort on non-linefetch` real dentro do `bcm2835_power_probe` (driver de clock/power),
lacuna própria do modelo `raspi1ap` do QEMU para este kernel 6.18.33, não um travamento do Linux.
Achado negativo registrado (ver Javadoc de `Raspi1BootTest`) para não repetir essa tentativa.
Próximo passo segue sendo o da sessão 5 (dump de VMA via `TPIDRURO`=`current`, confirmado como o
mecanismo certo pelo fonte real do kernel), com uma heurística nova sugerida (procurar
`vm_flags`≈`0x875` por padrão de busca perto do `mm_struct`, em vez de reconstruir a struct campo
a campo sem `vmlinux`/BTF). `mvn -o test` verde; nenhum arquivo do `arm-jitter` tocado.

**Sessão de diagnóstico 5 (2026-08-18, `7355868` arm-jitter + `3ba61f0` virtual-arm-box) — BUG REAL
ISOLADO E CORRIGIDO no `arm-jitter`, M3 ainda NÃO fecha (bloqueio mais estreito revelado logo
depois)**: dump direto da palavra de PTE (`TranslatingAddressSpace#setMmuEnabled(false)`+`read32` =
leitura física crua, walk manual de L1/L2 a partir de `TTBR0`) confirmou `DIRTY=0`/`RDONLY=1` no
endereço travado — bate com a checagem de `pin_page_for_write()`. **Causa raiz**:
`ArmCore#enterMemoryAbort` nunca preenchia `DFSR[11]` (`WnR`, ARM DDI 0406C B3.13.4) — toda falta de
DADOS chegava ao guest indistinguível de uma leitura, mesmo com `accessType()` já disponível no
ponto de chamada. O Linux real usa esse bit para decidir `FAULT_FLAG_WRITE`; sem ele, a falta de
ESCRITA que causou o único abort do boot era tratada como leitura, corrigindo o `AP` de hardware mas
nunca marcando a PTE como `dirty`. **Corrigido** (`ArmCore.java`, aditivo/G3, 2 testes de regressão
novos); `mvn -o test` verde (1369 core+truffle) + `mvn -o install`; G5 revalidado (gbaemu verde,
ndsemu verde, armbox 40/41 — a 1 falha é a mesma pré-existente `Armv7TortureTest`/`VfpRegisters`, não
relacionada). **Efeito medido**: a mesma PTE relida após o fix mostra `DIRTY=1` (fix funcionou) mas
`RDONLY` continua `1` — `pin_page_for_write()` ainda falha, por um motivo mais estreito e ainda não
isolado (candidatos: outro bug real, ou o kernel corretamente recusando escrita numa VMA que ele não
considera `VM_WRITE`, apontando para um problema no setup da pilha do `execve()` em vez do
`arm-jitter`). Ver Javadoc de `Raspi1BootTest` para o próximo passo recomendado (dump da VMA
correspondente). F3 segue 🟡 na fila "ATUAL".

<!-- Histórico minucioso completo da F3 (todas as sessões, começando com o abort storm ARMv6K)
     está em tasks/FILA-HISTORICO.md, seção "F3 (...) — histórico condensado movido de
     FILA-EXECUCAO.md". -->

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
histórico acima), **épico quase 100%**; só falta B6.6.6 (hospedeiro `virt64`), que nasceu
bloqueada no usuário (kernel/toolchain `aarch64-linux-*`). **Atualização 2026-08-18 (a pedido do
usuário)**: esse bloqueio de ambiente tem uma rota alternativa mais barata — o repositório
`raspberrypi/firmware` (o mesmo que já deu os assets da F3) publica `kernel8.img`, um kernel
AArch64 pré-compilado real, sem precisar de toolchain nem de GIC/PSCI (o Pi 3 real não usa GIC).
Nova task **F11** (`trilha-f-infra/f11-raspi3-aarch64-machine.md`, repo virtual-arm-box) ataca o
degrau aarch64 por essa rota; **B6.6.6 fica formalmente em espera** (não cancelada) enquanto a
F11 não se esgotar — ver seção 🧑 abaixo (entrada ajustada) e a tabela de Onda 4 para F11.
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
| ~~B6.6.6~~ **EM ESPERA** (não cancelada) desde 2026-08-18 — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Kernel arm64 mainline real (pré-compilado ou toolchain para buildar) + idealmente um initramfs busybox aarch64 real — mesmo bloqueio de toolchain/binário de B6.2 aceite #2/B4.0.3 item 3, um só ambiente resolve os três; adicionalmente, GICv2/GICv3/PSCI/DTB são substancialmente mais complexos que os periféricos versatilepb do precedente B4.1.5, reservar tempo de sessão maior. O bloqueio de FEATURE do `arm-jitter` que B6.6.6 e F11 convergiam (registrador de sistema/exceção/IRQ que qualquer kernel EL1 real precisaria) **foi FECHADO pela B6.6.7 em 2026-08-18** (ver índice do `tasks/README.md`) — só resta o bloqueio de ambiente original (kernel/toolchain aarch64 real) | fecha o épico B6.6 por completo (depende de B6.6.1-B6.6.5, rodada de spec 2026-07-26, ver `b6-aarch64.md`) — feature-completo desde B6.6.7; só falta kernel/toolchain real |

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

