# C5 — Habilitar chaining no gbaemu (repo: gbaemu)

**Trilha:** C · **Depende de:** — · **Repo:** **gbaemu**

## Contexto

O encadeamento de blocos (`JitRuntime.setChainCycleBudget`) foi implementado para o
ndsemu e rendeu ganho mensurável; o gbaemu ainda não o usa — é o "win barato" da
trilha. O GBA tem UMA CPU (sem o risco de handshake cross-CPU do ndsemu), mas o
chaining ainda interage com timing de IRQ/DMA: o budget limita quantos ciclos a CPU
avança sem devolver o controle ao loop de hardware do hospedeiro.

## Especificação

1. Localizar onde o gbaemu cria o `JitRuntime` (`GbaConsole` — conferir) e o tamanho
   do slice de execução usado por frame.
2. Chamar `setChainCycleBudget(N)` no runtime JIT. O budget NÃO pode ultrapassar a
   granularidade de hardware do gbaemu: identificar o menor intervalo em ciclos em que
   o hospedeiro precisa processar timers/DMA/IRQ entre execuções (ler o loop principal)
   e escolher N abaixo disso. Começar conservador: N=32, subir medindo (64/96).
3. Tornar configurável (constante nomeada ou setting existente do gbaemu), default no
   valor validado.

## Protocolo de validação

1. Suite gbaemu verde.
2. Boot frio + gameplay (~1min) dos **5 jogos de referência**: Pokémon FireRed
   (incluindo a intro do Oak — sensível a timing de DMA!), Super Mario World Advance 2,
   Castlevania A2CE, Metroid Fusion (criar novo save — sensível a DMA desalinhado),
   Mario Kart. Áudio sem estouros, input responsivo.
3. ROMs de teste gba-tests que hoje passam continuam passando (ver memória/CI do
   gbaemu para a lista).
4. Bench headless antes/depois dos 5 jogos; publicar tabela. Meta: qualquer ganho ≥2%
   sem regressão. Sem ganho = registrar resultado e fechar.

## Armadilhas

- **IRQ latency:** chaining adia a checagem de interrupção até o fim do budget. Se o
  gbaemu checa IRQ por slice (não por instrução), budget > slice efetivamente atrasa
  IRQs — sintomas: raster effects quebrados (barras/tremido em telas com HBlank IRQ),
  áudio com glitch. A intro do FireRed e menus com efeito de janela são bons canários.
- O gbaemu historicamente usou runtime interpretado como default na GUI por um problema
  de GC/classloading (ver memória do projeto) — confirmar qual runtime a GUI usa HOJE
  antes de medir; habilitar chaining no runtime errado não medirá nada.

## Resultado

**✅ Concluída.** `GbaConsole.CHAIN_CYCLE_BUDGET=32` (conservador, bem abaixo de
1 scanline/1232 ciclos), aplicado nos dois backends para manter
`JitInterpreterDivergenceTest` válido. Bench headless dos 5 jogos: **+9,1% a +41,0%**.
gba-tests + suite 216 verdes.

**Validação de gameplay (2026-07-11, usuário, 3 jogos)**: a travadinha de compilação
que o ASM dava antes SUMIU com chaining — confirmado.

**Achados novos, registrados a pedido do usuário, SEM investigação (escolha dele, não
falta de tempo)**: (1) Pokémon FireRed tem glitches gráficos em batalha rodando
ASM+chaining — possível regressão do caminho ASM, escopo desta task; (2) animação da
BIOS + áudio lentos — usuário confirmou que acontece TAMBÉM em `INTERPRETED`, logo NÃO
é regressão desta task nem do ASM; bug pré-existente mais amplo, versão de origem
desconhecida.

**Decisão do usuário: `INTERPRETED` segue DEFAULT do gbaemu** (já era assim desde
2026-06-21 por causa do corte de input do JIT — reforçado agora também por precisão);
ASM fica reservado para quando precisar de mais desempenho, não como default.
