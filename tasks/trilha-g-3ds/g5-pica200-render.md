# G5 — PICA200: listas de comando, vertex shader interpretado e TEV → SPIR-V (marco M5)

**Trilha:** G · **Depende de:** G4 · **Repo:** n3dsemu
**Leia a RFC-N3DSEMU.md, em especial D4 e D5, antes.**
**Task LONGA (3+ sessões).** Fatie em PRs: PR1 = parser + registradores; PR2 = vertex shader
interpretado + geometria na Vulkan; PR3 = texturas + TEV.

## Contexto

A G4 mostra framebuffers desenhados pela CPU do guest. Esta task faz o emulador entender a
GPU: interpretar as listas de comando que o `gsp::Gpu` recebe e desenhar de verdade.

O PICA200 (DMP) **não é** uma GPU moderna: o pipeline de vértice é programável numa ISA
própria, e o de fragmento é **fixed-function** (6 estágios TEV). As decisões D5 da RFC
tratam disso: **vertex shader interpretado na CPU**, **TEV traduzido para SPIR-V com cache**.

Referências (transcrever, não inventar): `https://www.3dbrew.org/wiki/GPU/Internal_Registers`,
`https://www.3dbrew.org/wiki/GPU/Shader_Instruction_Set`, e o código do Citra
(`video_core/`). O fonte do `libctru` e os exemplos de `examples/3ds/graphics/gpu/` (20
exemplos!) são o lado do cliente e servem de oráculo do que é emitido.

## Objetivo (marco M5)

`examples/3ds/graphics/gpu/simple_tri` desenha o triângulo na tela superior, com as cores
corretas.

## Inclui

1. `gpu/CommandListParser` — decodifica a lista de comandos e aplica nos registradores.
2. `gpu/PicaRegisters` — a máquina de estados (formato de vértice, atributos, estado de
   rasterização, TEV, texturas, framebuffer de destino).
3. `gpu/shader/` — carregador de `.shbin` (código + descritores de operando + uniforms) e
   **interpretador** da ISA de vertex shader.
4. `gpu/tev/` — tradução da configuração dos 6 estágios TEV para GLSL → SPIR-V, com cache.
5. Extensão de `PicaRenderer` com o necessário para desenhar; `RecordingRenderer` grava tudo.
6. `gpu/vulkan/` — pipelines dinâmicos, buffers de vértice, texturas, render target.

## NÃO inclui (não fazer)

- **Sem JIT de vertex shader** (a interpretação é a decisão D5; recompilar `.shbin` → SPIR-V é
  otimização futura, task própria).
- Sem geometry shader (é um modo separado do PICA200 — o exemplo `geoshader` fica de fora).
- Sem procedural textures (`proctex`), sem light/fragment lighting fixo, sem shadow maps.
  Esses são exemplos específicos e cada um vira task futura se o usuário quiser.
- Sem 3D estereoscópico.
- Sem otimização de desempenho. **Correção primeiro.**

## Especificação

### Listas de comando

Uma lista é uma sequência de pares `(cabeçalho, dados)`. O cabeçalho traz o índice do
registrador de destino, uma máscara de escrita por byte, um contador de palavras extras e um
bit de "modo consecutivo" (incrementa o índice a cada palavra). O parser aplica cada escrita
em `PicaRegisters`.

Registrador `0x0010` = `Trigger draw` (e `0x00E0`/`0x00E1` são os *finalize*). São eles que
disparam o desenho.

**Teste unitário direto:** monte listas à mão, rode o parser, afirme o estado de
`PicaRegisters`. Não precisa de GPU nem de guest. **A maior parte do valor de teste desta
task está aqui** — aproveite (RFC D4: não há renderização automatizável).

### Formato de vértice

Registradores `0x0200`–`0x0227`: endereço base do array de atributos, até 12 atributos com
tipo (`byte`/`ubyte`/`short`/`float`) e contagem de componentes, e até 12 *loaders* que
mapeiam intervalos de memória para índices de atributo. Há dois modos de desenho: por array
(`DrawArrays`) e por índice (`DrawElements`, índices de 8 ou 16 bits) — implemente os dois,
o libctru usa ambos.

Os atributos podem vir de VRAM (`0x18000000`) ou da FCRAM linear. Leia sempre pelo
`AddressSpace` do host, nunca por um cache próprio.

### Vertex shader (`.shbin`)

Formato DVLB/DVLP/DVLE: um contêiner com o binário do programa (32 bits por instrução), a
tabela de *operand descriptors*, e uma ou mais entradas executáveis com `main` offset, mapa
de saídas e constantes.

ISA: ~30 instruções (`add`, `dp3`, `dp4`, `mul`, `max`, `min`, `rcp`, `rsq`, `mov`, `mova`,
`cmp`, `call`/`callu`/`callc`, `ifu`/`ifc`, `loop`, `jmp`, `end`, e os *mad* de formato
diferente). Registradores: 16 de entrada `v0-v15`, 16 temporários `r0-r15`, 96 constantes
float `c0-c95`, 4 booleanas, 3 inteiras, 2 de endereço + o registrador de laço.

**Interprete.** Um `switch` sobre o opcode, `float[4]` por registrador. Estrutura o código
para que a substituição futura por um compilador SPIR-V troque só a implementação.

Testes: rode shaders `.shbin` reais (montados pelo `picasso` a partir dos `.v.pica` dos
exemplos) com entradas conhecidas e compare a saída contra valores calculados à mão. É
determinístico e não precisa de GPU.

Saída do interpretador: posição em *clip space* + os atributos variáveis. Isso vai para a
Vulkan como buffer de vértice, e o **shader de vértice do host é trivial** (passa adiante).

### TEV → SPIR-V

6 estágios. Cada um: 3 fontes de cor + 3 de alpha (com modificadores), uma operação de
combinação (`Replace`, `Modulate`, `Add`, `AddSigned`, `Interpolate`, `Subtract`, `Dot3`,
`MultAdd`, `AddMult`), e um multiplicador de escala. Mais: buffer de cor combinada, teste
alpha, blending, teste de profundidade/stencil.

Gerar **GLSL** a partir da configuração e compilar com `shaderc` (já disponível desde a G4),
com **cache em memória chaveado pela configuração completa dos 6 estágios + teste alpha**.
Compilar o mesmo shader duas vezes é o erro clássico aqui — um `HashMap<TevConfig,
Long /*VkShaderModule*/>` resolve.

Escreva o gerador de GLSL como função pura `TevConfig → String`, e teste-o comparando a
string gerada com uma esperada para 2–3 configurações conhecidas (a do `simple_tri`, entre
elas). Assim o gerador tem teste automatizado mesmo sem GPU.

### Texturas

Formatos: `RGBA8`, `RGB8`, `RGBA5551`, `RGB565`, `RGBA4`, `IA8`, `RG8`, `I8`, `A8`, `IA4`,
`I4`, `A4`, e os comprimidos `ETC1`/`ETC1A4`. **Implemente os não comprimidos primeiro**; o
`simple_tri` não usa textura nenhuma, então texturas só são necessárias a partir de
`textured_cube`. Se o PR3 ficar grande, texturas podem virar PR4.

**Armadilha grande:** as texturas do 3DS são armazenadas em **ordem "swizzled" de blocos
8×8 em curva de Morton**, não linear. Descompactar errado dá uma imagem de aparência
"embaralhada em quadradinhos" — sintoma característico, reconheça-o.

### Vulkan

Pipeline por combinação de (shader TEV, estado de rasterização, formato de destino), com
cache. Render target off-screen no formato do framebuffer de destino do guest, depois o
mesmo caminho de apresentação da G4.

## Aceite

- [ ] `mvn -o test` verde, **sem GPU** (todos os testes novos usam `RecordingRenderer` ou são
      puros: parser, interpretador de shader, gerador de GLSL, deswizzle de textura).
- [ ] `simple_tri.3dsx` desenha o triângulo, com as cores certas. **Validação visual —
      captura de tela no relatório.**
- [ ] `hello-world.3dsx` (M4) continua funcionando.
- [ ] Relatório de fechamento com uma tabela dos 20 exemplos de `graphics/gpu/`: quais
      renderizam, quais não, e o motivo de cada falha. **Isso vira o backlog do subsistema
      gráfico** — não conserte os que falharem nesta task.
- [ ] Índice do `tasks/README.md` atualizado (G5 ✅).

## Armadilhas

- **Não tente fazer os 20 exemplos funcionarem.** O aceite é `simple_tri`; o resto é
  levantamento. Ampliar o escopo aqui é como esta task nunca fecha.
- Ordem de bits do 3DS em cores: as conversões `RGB565`/`RGBA5551` erram fácil o canal alpha.
  Teste com valores conhecidos.
- O `picasso` (assembler de shader) está em `C:\devkitPro\tools\bin\picasso.exe` — use-o para
  gerar `.shbin` de teste sob medida, não dependa só dos exemplos.
- O interpretador de shader tem que respeitar os *operand descriptors* (negação e swizzle por
  operando). Ignorá-los faz tudo "quase funcionar" e nada ficar certo.
- Não misture os registradores **internos** da GPU (`0x0000`–`0x0FFF`, escritos por lista de
  comando) com os **externos** (`0x1EF00000`, escritos por `gsp::WriteHWRegs`). São dois
  espaços distintos; os externos controlam framebuffer/LCD e já foram tratados na G3/G4.
