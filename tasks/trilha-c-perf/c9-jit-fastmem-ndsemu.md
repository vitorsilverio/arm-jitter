# C9 — Fast-path de RAM no bytecode JIT ("fastmem" por página) — bancada ndsemu

**Trilha:** C · **Depende de:** C7 (ndsemu em `PagedAddressSpace`) · **Repos:** arm-jitter + ndsemu (bench)

## Contexto

Hoje TODO load/store do bloco compilado chama helper → `AddressSpace` (interface
virtual do hospedeiro). Com C7, o hospedeiro passa a descrever a memória como
páginas — o que habilita o passo clássico seguinte de emuladores JIT: **o
bytecode do bloco faz o acesso direto no array da página** quando a página é RAM
pura, com fallback para o helper quando não é. Alvo: ndsemu (é quem usa JIT em
produção; o gbaemu é INTERPRETED por decisão de fidelidade e fica FORA desta task).

## Especificação

1. `PagedAddressSpace` expõe (API nova, arm-jitter): para um endereço, "esta
   página é RAM direta sem listener/waitstate-especial?" e o par (array, offset
   base) — de forma que o `AsmBlockCompiler`/`MemAccessEmitter` consiga emitir:
   `idx = (addr >>> pageShift)`; load do array de páginas (campo `@Stable`-like,
   referência constante por runtime); se a entrada é RAM → `IALOAD`/`BALOAD`
   direto com o offset; senão → caminho helper atual (branch raro).
2. **Invalidação/`setContainsCode` é o ponto perigoso**: página de RAM com código
   NÃO pode ir pro fast-path de STORE (a escrita precisa disparar o listener que
   invalida o `BlockCache`) — stores só têm fast-path em página sem código;
   loads podem sempre. Se um `setContainsCode` chegar DEPOIS de blocos compilados
   com fast-path de store naquela página, esses blocos precisam ser invalidados
   (usar o mesmo mecanismo de invalidação por página que o `BlockCache` já tem).
3. Ciclos de acesso (`addMemoryCycles`): manter a contagem atual — o fast-path
   também soma os ciclos da página (lookup barato por página, C3 já suporta
   waitstates por página).
4. Gate: flag no runtime (`setFastMemoryEnabled(paged)`, default OFF até validar),
   ligada pelo ndsemu após C7. Divergence-check (`asmcheck`) precisa rodar com a
   flag ON.

## Aceite

1. `asmcheck` JUS 800 frames zero divergências com fastmem ON.
2. Boot dos 4 jogos de referência (JUS/MKDS/SM64DS/Platinum) OK com ON.
3. Bench `Main <frames> bench` nos 3 jogos: publicar tabela; expectativa honesta
   +5-15% (loads/stores são ~30% das ops; o helper atual já é barato) — se der
   <3% agregado, registrar e NÃO ligar por default (complexidade não paga).
4. Suites arm-jitter/gbaemu/ndsemu verdes; gbaemu intocado (não usa a flag).
5. Gameplay validado pelo usuário (MKDS corrida) antes de default ON.

## Armadilhas

- ITCM/DTCM/VRAM remapeáveis (C7) mudam o array da página em runtime — o bytecode
  NÃO pode cachear a referência da página entre execuções do bloco; cachear só a
  tabela de páginas (estável) e indexar a cada acesso. Remap troca a entrada da
  tabela → próximo acesso já vê a nova.
- Endereço desalinhado no fast-path: reproduzir EXATAMENTE o que o helper faz
  (rotação v4T/v5TE — e B1.7 se um dia o ndsemu... não: ARM7/ARM9 do NDS são
  v4T/v5TE, rotação sempre). Property test de offsets 0-3 contra o helper.
- Este é o tipo de task onde o harness de equivalência é a rede: rodar
  `BlockEquivalenceHarness` com um `PagedAddressSpace` real, não só o fake de RAM.
