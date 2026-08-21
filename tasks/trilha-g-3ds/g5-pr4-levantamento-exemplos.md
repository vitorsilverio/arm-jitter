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
| `composite_scene` | 0 | ❌ | **família NULL/`gxCmdQueue`** — ver abaixo |
| `fragment_light` | 0 | ❌ | idem |
| `lenny` | 0 | ❌ | idem |
| `loop_subdivision` | 0 | ❌ | idem |
| `textured_cube` | 0 | ❌ | idem — **é o exemplo-alvo das texturas**, então o caminho de textura da PR4 está implementado e testado por unidade, mas ainda sem validação visual |
| `cubemap` | — | ❌ | não termina o orçamento (timeout); não investigado |
| `gpusprites` | — | ❌ | idem |
| `immediate` | 0 | ❌ | usa **submissão imediata de vértices** (`GPUREG_FIXEDATTRIB_INDEX = 0xF`), caminho não implementado |
| `mipmap_fog` | 0 | ❌ | sem mipmap nem fog (`GPUREG_TEXUNIT*_LOD`, tabela de fog) |
| `normal_mapping` | 0 | ❌ | depende de *fragment lighting* fixo, fora do escopo da G5 |
| `proctex` | 0 | ❌ | texturas procedurais, excluídas explicitamente pela G5 |
| `toon_shading` | 0 | ❌ | depende de *fragment lighting* / LUT |
| `wide_mode_3d` | 0 | ❌ | modo de tela larga (800×240), não suportado |

**Placar: 7 de 20 produzem geometria; 1 validado visualmente ponta a ponta.**

## O achado que mais vale: a "família NULL/`gxCmdQueue`"

Cinco exemplos (`composite_scene`, `fragment_light`, `lenny`, `loop_subdivision`, `textured_cube`)
morrem **exatamente do mesmo jeito**, o que sugere **uma única causa raiz**:

1. o boot corre normal — as mesmas duas `svcControlMemory` do `simple_tri`, nenhuma falha de
   alocação, nenhum serviço faltando;
2. logo depois de um `svcGetSystemTick` vindo de `gxCmdQueueWait`, o guest começa a **varrer
   memória a partir do endereço `0x0` em passos de 4 bytes** (~49 mil leituras em barramento aberto);
3. em seguida entra num laço infinito de `svcCreateAddressArbiter`.

O passo 2 bate campo a campo com `gxCmdQueueDoCommands` do `libctru`:

```c
gxCmdEntry_s* entry = &curQueue->entries[curQueue->curEntry++];
gspSubmitGxCommand(entry->data);   // lê 8 words a partir de entries[i]
```

com `curQueue->entries == NULL`. Ou seja: **a fila de comandos do lado do cliente ficou com um
ponteiro nulo**, e a suspeita mais forte é dessincronia de contagem — `gxCmdQueueInterrupt` conta
uma unidade por interrupção não-VBlank, então entregar interrupções a mais ou a menos do que o
número de comandos submetidos corrompe `lastEntry`/`isRunning`.

**Candidato a task própria (`G6.1`?)**, e provavelmente o item de maior alavancagem do backlog
gráfico: destrava 5 exemplos de uma vez, incluindo o `textured_cube`, que é o que falta para validar
visualmente as texturas da PR4.

## Backlog gráfico derivado (ordem sugerida)

1. **Dessincronia da fila GX** (acima) — destrava 5 exemplos, incl. a validação visual de textura.
2. **Teste de profundidade** — não há *depth buffer* no render pass de geometria; qualquer cena 3D
   com faces sobrepostas vai desenhar na ordem errada.
3. **Blending / `GPUREG_BLEND_FUNC`** — `particles` e qualquer transparência dependem disso.
4. `DisplayTransfer` real (cópia de pixels), que devolveria o framebuffer do guest como fonte da
   verdade e removeria o desvio documentado em `VulkanRenderer#presentScreen`.
5. Formatos comprimidos `ETC1`/`ETC1A4`.
6. *Fragment lighting* fixo + LUTs (`toon_shading`, `normal_mapping`, `fragment_light`).
7. Geometry shader (`geoshader`), texturas procedurais (`proctex`), estéreo, tela larga — cada um
   uma task própria, todos fora do escopo da G5 por decisão da spec.
