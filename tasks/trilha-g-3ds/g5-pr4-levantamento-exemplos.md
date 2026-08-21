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
| `composite_scene` | — | ❌ | não termina o orçamento depois da B3.9; próxima parada não isolada |
| `fragment_light` | 0 | ❌ | avançou com a B3.9 (já executa o `MemoryFill`, cor de fundo lida) mas ainda não desenha |
| `lenny` | 0 | ❌ | idem |
| `loop_subdivision` | 501 | 🟡 **desenha COM TEXTURA** (`tex0=32x32`) | destravado pela B3.9; a malha sai desalinhada porque **não há teste de profundidade** (item 2 do backlog) |
| `textured_cube` | 0 | ❌ | idem; a validação visual do caminho de textura acabou vindo do `loop_subdivision` |
| `cubemap` | — | ❌ | não termina o orçamento (timeout); não investigado |
| `gpusprites` | — | ❌ | idem |
| `immediate` | 0 | ❌ | usa **submissão imediata de vértices** (`GPUREG_FIXEDATTRIB_INDEX = 0xF`), caminho não implementado |
| `mipmap_fog` | 0 | ❌ | sem mipmap nem fog (`GPUREG_TEXUNIT*_LOD`, tabela de fog) |
| `normal_mapping` | 0 | ❌ | depende de *fragment lighting* fixo, fora do escopo da G5 |
| `proctex` | 0 | ❌ | texturas procedurais, excluídas explicitamente pela G5 |
| `toon_shading` | 0 | ❌ | depende de *fragment lighting* / LUT; avançou com a B3.9 |
| `wide_mode_3d` | 0 | ❌ | modo de tela larga (800×240), não suportado; avançou com a B3.9 |

**Placar: 8 de 20 produzem geometria; 2 validados visualmente** — `simple_tri` e, depois da B3.9,
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

## Backlog gráfico derivado (ordem sugerida)

1. ~~Dessincronia da fila GX~~ — **hipótese refutada**; a causa real era o gap de VFP, fechado pela
   B3.9. Restam 4 exemplos parando mais adiante: repetir a técnica (`pc`/`sp` na parada +
   desmontagem em `lr-4`) para cada um.
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
