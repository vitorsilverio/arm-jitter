# G5/PR4 — levantamento dos 20 exemplos de `graphics/gpu`

**Item de aceite da G5**: "Relatório de fechamento com uma tabela dos 20 exemplos de
`graphics/gpu/`: quais renderizam, quais não, e o motivo de cada falha. **Isso vira o backlog do
subsistema gráfico** — não conserte os que falharem nesta task."

Levantamento feito em 2026-08-21, depois da PR4 (commit `d530382` no n3dsemu). Todos os 20
exemplos **compilam** sem modificação com o devkitARM instalado.

## Como foi medido

Modo `--report` novo (headless, sem GPU nem olho humano): roda o `.3dsx` por um orçamento de fatias
e imprime quantas chamadas de desenho chegaram ao renderer, quantos vértices, a cor de fundo lida
do *color buffer* e as texturas ligadas.

```
n3dsemu --report --slices=3000 <exemplo>.3dsx
```

Isso separa **"não desenha"** de **"desenha errado"** — distinção que uma captura de tela sozinha
não dá.

## Tabela

| Exemplo | Desenhos | Situação | Motivo / próximo passo |
|---|---:|---|---|
| `simple_tri` | 663 | ✅ **valida visualmente** | triângulo branco sobre fundo azul `0x68B0D8`, idêntico ao hardware |
| `both_screens` | 351 | 🟡 desenha (tela de cima) | a tela de baixo ainda não recebe geometria; a tela é aprendida do `DisplayTransfer`, que só chega DEPOIS do primeiro desenho |
| `multiple_buf` | 663 | 🟡 desenha | mesma contagem do `simple_tri`; não validado visualmente |
| `geoshader` | 662 | 🟡 desenha algo | usa **geometry shader**, que a G5 exclui explicitamente do escopo — o que aparece é o caminho de vertex shader, não o resultado correto |
| `2d_shapes` | 69 | 🟡 desenha | citro2d; fundo `#FFD8B0` lido corretamente |
| `stereoscopic_2d` | 339 | 🟡 desenha | sem 3D estereoscópico (fora do escopo da G5), só o olho esquerdo |
| `particles` | 60 | 🟡 desenha | fundo preto; sem *blending* configurável ainda |
| `composite_scene` | 0 | ❌ | **fs:USER corrigido pela G6.2** (sem `comando desconhecido` de `fs:USER` no trace) — `svcBreak(PANIC)` continua, causa NOVA e diferente: `APT:U` comando desconhecido `0x0044` (`GetSharedFont`)/`0x000B`/`0x0102`, não relacionada a RomFS. Candidata a task própria |
| `fragment_light` | 597 | 🟡 **desenha** (21492 vértices) | **destravado pela G6.4** (controle de fluxo: `JMPC`/`IFC`/`CALL*`/`LOOP`/`BREAK*` implementados) — não validado visualmente ainda |
| `lenny` | 568 | 🟡 **desenha** (1899960 vértices) | **destravado pela G6.4** — mesmo mecanismo de `fragment_light` |
| `loop_subdivision` | 501 | 🟡 **desenha COM TEXTURA** (`tex0=32x32`) | destravado pela B3.9; a malha sai desalinhada porque **não há teste de profundidade** (item 2 do backlog) |
| `textured_cube` | 605 | 🟡 **desenha** (21780 vértices) | **destravado pela G6.3** (`MAD` implementado) — não validado visualmente ainda |
| `cubemap` | 0 | ❌ | **fs:USER corrigido pela G6.2**: `OpenFileDirectly` self-mount do RomFS embutido lê o `.t3x` real (`skybox.t3x`, offsets confirmados no trace) — não panica mais, mas ainda 0 desenhos em 3000/6000 fatias (não investigado além disso, fora do escopo da G6.2) |
| `gpusprites` | 0 | ❌ | **fs:USER corrigido pela G6.2**: mesmo mecanismo de `cubemap`, lê `sprites.t3x` real — não panica mais, ainda 0 desenhos em 6000 fatias (não investigado além disso) |
| `immediate` | 0 | ❌ | usa **submissão imediata de vértices** (`GPUREG_FIXEDATTRIB_INDEX = 0xF`), caminho não implementado |
| `mipmap_fog` | 0 | ❌ | sem mipmap nem fog (`GPUREG_TEXUNIT*_LOD`, tabela de fog) |
| `normal_mapping` | 0 | ❌ | depende de *fragment lighting* fixo, fora do escopo da G5 |
| `proctex` | 0 | ❌ | texturas procedurais, excluídas explicitamente pela G5 |
| `toon_shading` | 0 | ❌ | depende de *fragment lighting* / LUT; avançou com a B3.9 |
| `wide_mode_3d` | 0 | ❌ | modo de tela larga (800×240), não suportado; avançou com a B3.9 |

**Placar: 10 de 20 produzem geometria; 2 validados visualmente** — `simple_tri` e, depois da B3.9,
`loop_subdivision`. Este último com textura, o que fecha a validação visual do caminho de textura da
PR4 (que até então só tinha teste automatizado).

> As contagens da coluna "Desenhos" são de execuções com orçamentos de fatias diferentes e servem
> para separar "não desenha" de "desenha" — não são comparáveis entre si como medida de desempenho.

## O achado que mais valeu — e a hipótese errada que ele corrigiu

Cinco exemplos (`composite_scene`, `fragment_light`, `lenny`, `loop_subdivision`, `textured_cube`)
morriam **exatamente do mesmo jeito**: varredura de ~49 mil leituras de memória a partir de `0x0` em
passos de 4 bytes, depois um laço infinito de `svcCreateAddressArbiter`.

A primeira versão deste relatório registrou uma hipótese de causa raiz: **dessincronia da contagem
de interrupções da fila GX** — o padrão da varredura batia campo a campo com o laço de
`gxCmdQueueDoCommands` do `libctru` com `curQueue->entries == NULL`. Era plausível. **Estava
errada.**

O que resolveu foi **medir em vez de deduzir**:

1. Instrumentar a fila GX mostrou correspondência 1:1 perfeita no `simple_tri`
   (`MemoryFill`→`PSC0`, `ProcessCommandList`→`P3D`, `DisplayTransfer`→`PPF`) e **zero atividade de
   fila** no `textured_cube` — que portanto morre ANTES de submeter qualquer comando, o que sozinho
   já derrubava a hipótese.
2. Capturar os registradores do core no momento da parada deu `pc=0x4`, `sp=0x0`: **vetor de
   instrução indefinida**, com o SP bancado do modo UND nunca inicializado.

Com `LR_und = PC_falho + 4`, a desmontagem dos 5 binários em `lr-4` mostrou todos parando na MESMA
instrução: **`VNMLS`** — um gap de decode do `arm-jitter` documentado explicitamente desde a B3.5.
Corrigido pela **B3.9** (`tasks/trilha-b-arquiteturas/b3.9-vfp-vnmla-vnmls.md`).

**Lição a carregar**: capturar `pc`/`sp` na parada custa uma medição e resolve em minutos o que a
dedução por padrão de sintoma apontou para o lugar errado. Um relatório pode registrar hipóteses —
mas elas têm que estar rotuladas como hipótese, não como achado.

## G6.1 (2026-08-24) — causa real dos 6 exemplos que ainda não desenhavam

Ver `g6.1-exemplos-restantes.md` para a task completa. Achado: **não era mais preciso usar a
técnica `pc`/`sp`+`lr-4`** (essa era para instrução indefinida sem vetor — a assinatura da B3.9).
Os 6 exemplos-alvo terminam sozinhos dentro de 3000 fatias com uma exceção/`svcBreak` explícitos —
bastou capturar o log de "comando desconhecido" (`AbstractService#respondUnknown`, já existente
desde a G3) e a mensagem de exceção do `VertexShaderInterpreter`. Duas causas reais, cada uma
batendo em **3 exemplos**:

1. **`fs:USER OpenArchive`(0x080C)/`OpenFileDirectly`(0x0803) não implementados** — `composite_scene`,
   `cubemap`, `gpusprites` carregam ativos via RomFS; sem esses dois comandos, `FSUSER_OpenArchive`
   falha e o próprio exemplo chama `svcBreak(PANIC)` (padrão comum dos exemplos do devkitPro: checar
   `Result` e abortar se falhar). Nomes confirmados via `3dbrew.org/wiki/Filesystem_services`
   (`0x08030204`=`OpenFileDirectly`, `0x080C00C2`=`OpenArchive`) — cabeçalho reconstruído a partir de
   `normais`/`traduzidos` logados (`(normal<<6)|translate`) e batido contra a tabela real.
2. **`VertexShaderInterpreter` não implementa `CMP` (opcodes `0x2E`-`0x2F`) nem `MAD`
   (opcodes `0x30`-`0x3F`)** — confirmado via `3dbrew.org/wiki/Shader_Instruction_Set` (tabela real
   do PICA200). `fragment_light`/`lenny` usam `CMP` (`0x2F`); `textured_cube` usa `MAD` (`0x3C`).
   `MAD` é uma instrução fundamental do PICA200 (multiply-add, formato de 3 operandos) — gap real
   com potencial de bloquear outros exemplos futuros também, não só este.

Nenhuma correção aplicada nesta sessão (RomFS e `CMP`/`MAD` não são "pequenos e óbvios" — cada um é
um subsistema/família de opcodes novo, mesmo critério que separou a B3.9 de G2.2/G3.3). Candidatas a
task própria: **G6.2** (RomFS mínimo em `fs:USER` — `OpenArchive`+`OpenFileDirectly`+`ReadFile`) e
**G6.3** (`VertexShaderInterpreter`: `CMP`+`MAD`, mesmo padrão de investigação-com-corpus-real da
B3.9). Nenhuma pega automaticamente — mesma regra de sempre.

**✅ G6.2 fechada** (`g6.2-fs-user-romfs-self-mount.md`) — causa 1 corrigida. Achado real que
simplificou a implementação: para um `.3dsx`, `romfsInit()` é `romfsMountSelf`, que abre o
PRÓPRIO arquivo via `ARCHIVE_SDMC` e lê nele em offsets absolutos — o parsing da estrutura RomFS
é feito inteiramente pelo GUEST, não precisou de parser de RomFS em Java. `composite_scene` não
panica mais por `fs:USER`, mas revela uma causa NOVA e não relacionada (`APT:U` comando
desconhecido `0x0044`/`0x000B`/`0x0102`); `cubemap`/`gpusprites` leem seus `.t3x` reais do RomFS
embutido (confirmado pelos offsets de leitura no trace) e não panicam mais, mas ainda não
produzem desenho dentro do orçamento testado — não investigado além disso, fora do escopo da
G6.2. Ver **Resultado** na task para o detalhe completo.

**✅ G6.4 fechada** (`g6.4-vertex-shader-controle-de-fluxo.md`) — controle de fluxo completo do
`VertexShaderInterpreter` (`JMPC`/`JMPU`/`IFC`/`IFU`/`CALL`/`CALLC`/`CALLU`/`LOOP`/`BREAK`/`BREAKC`).
Layout de bits do nihstro + semântica de pilha (3 pilhas: `IF`/`CALL`/`LOOP`) transcrita fielmente do
interpretador de referência real do Citra (fork `lime3ds`, já que `citra-emu/citra` não existe mais
nesse caminho). `fragment_light` (597 desenhos, 21492 vértices) e `lenny` (568 desenhos, 1899960
vértices) destravados por completo — não morrem mais em `JMPC`. Não validado visualmente ainda. Ver
**Resultado** na task para o detalhe completo.

## Backlog gráfico derivado (ordem sugerida)

1. ~~Dessincronia da fila GX~~ — **hipótese refutada**; a causa real era o gap de VFP, fechado pela
   B3.9. ~~Restam 4 exemplos parando mais adiante~~ — **fechado pela G6.1** (ver seção acima: RomFS +
   `CMP`/`MAD` do vertex shader).
2. **Teste de profundidade** — é o que faz o `loop_subdivision` sair desalinhado; hoje é o defeito
   visual mais evidente do renderizador.
   Não há *depth buffer* no render pass de geometria: qualquer cena 3D com faces sobrepostas
   desenha na ordem de submissão.
3. **Blending / `GPUREG_BLEND_FUNC`** — `particles` e qualquer transparência dependem disso.
4. `DisplayTransfer` real (cópia de pixels), que devolveria o framebuffer do guest como fonte da
   verdade e removeria o desvio documentado em `VulkanRenderer#presentScreen`.
5. Formatos comprimidos `ETC1`/`ETC1A4`.
6. *Fragment lighting* fixo + LUTs (`toon_shading`, `normal_mapping`, `fragment_light`).
7. Geometry shader (`geoshader`), texturas procedurais (`proctex`), estéreo, tela larga — cada um
   uma task própria, todos fora do escopo da G5 por decisão da spec.
