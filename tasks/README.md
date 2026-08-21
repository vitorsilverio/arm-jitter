# tasks/ — Spec Driven Development

Cada arquivo aqui é uma task **autocontida**, escrita para ser executada por um agente
sem contexto prévio do projeto. Leia este arquivo inteiro antes de executar qualquer task.

## Protocolo de execução (obrigatório)

1. Leia a task inteira, incluindo **Armadilhas** e **Não fazer**.
2. Verifique a coluna **Depende de** — não execute uma task cujas dependências não
   estejam concluídas (status ✅ no índice abaixo).
3. Leia os arquivos-fonte citados na task ANTES de escrever código. Quando a task diz
   "espelhe o padrão de X", abra X e copie a estrutura, nomes e estilo.
4. Implemente APENAS o que está em "Inclui". Se algo parecer necessário e não estiver
   listado, PARE e pergunte ao usuário em vez de improvisar.
5. Todo comportamento observável novo precisa de teste automatizado no mesmo PR.
6. Valide com `mvn test` (JDK do projeto = JBR 25). Se não puder executar comandos,
   peça ao usuário para rodar e cole o resultado.
7. Ao concluir: atualize o status da task no `INDICE.md` da trilha correspondente
   (resumo curto — emoji + data) e escreva o histórico completo (o que foi feito,
   achados, decisões) numa seção `## Resultado` no final do arquivo da própria task.
   Faça um commit por task (mensagem em português, começando com o ID da task, ex.:
   `B1.2: ...`).

## Invariantes globais (NUNCA violar)

- **G1 — O interpretador é o oráculo.** `InterpretedCodeEmitter` define a semântica.
  Qualquer backend/otimização novo deve produzir estado de CPU idêntico, validado pelo
  `BlockEquivalenceHarness` (`codegen/equivalence/`).
- **G2 — GBA = ARMv4T.** NUNCA aplique instruções ou comportamentos ARMv5+ ao preset
  `ARMV4T`. Todo recurso novo de arquitetura é gateado por `ArmFeature` e habilitado
  apenas nos presets corretos. GBATEK descreve GBA+NDS juntos — cuidado ao ler.
- **G3 — Sem breaking change.** Factories, assinaturas públicas e comportamento default
  não mudam. Recurso novo entra por factory/flag/preset novo.
- **G4 — `Cycle`/`Fetch` nunca recebem guard condicional** no codegen: instrução com
  condição falsa ainda consome ciclo e fetch.
- **G5 — gbaemu e ndsemu são o gate de regressão.** Mudança no arm-jitter exige
  `mvn install` local e suites verdes nos dois consumidores (peça ao usuário se não
  puder rodar).
- **G6 — Sem números mágicos.** Constantes arquiteturais (registradores PC/LR, máscaras,
  offsets) recebem nome.
- **G7 — Javadoc `///` (markdown, Java 25) em toda API pública**, em português.

## Estrutura de uma task

`Contexto → Objetivo → Inclui/Não inclui → Especificação → Passos → Aceite → Validação → Armadilhas`

Tasks marcadas com **[REFINAR]** são especificações de alto nível que devem ser
detalhadas (nova rodada de spec) quando suas dependências concluírem — não execute
uma task [REFINAR] diretamente.

## Issues do GitHub × `tasks/`

Os dois coexistem e não competem:

- **Issue** = um **problema ou pedido observável**, do ponto de vista de quem usa. "O
  Pokémon FireRed tem 3 glitches visuais na batalha." "O ndsemu não boota em INTERPRETED."
  "Queria ROMs recentes no menu." Uma issue descreve **sintoma, repro e evidência**; ela não
  diz como consertar e não tem prazo.
- **Task** (`tasks/*.md`) = uma **especificação executável**, do ponto de vista de quem
  implementa: escopo fechado, `Inclui`/`Não inclui`, passos, aceite, armadilhas. Uma task
  existe porque alguém já decidiu **como** atacar o problema.

Fluxo normal: issue nasce primeiro → quando vira prioridade, uma **sessão de modelo forte**
escreve a task correspondente → a task cita `Fecha: <repo>#<n>` no cabeçalho → o commit que
fecha a task usa `Closes <repo>#<n>` (ou, entre repos diferentes,
`Closes vitorsilverio/<repo>#<n>`).

Casos que **não** viram issue:
- Itens puramente internos de refactor sem sintoma externo.
- Sub-tasks de um épico já especificado (B6.3.1, B6.3.2, ...) — são decomposição de
  implementação, vivem só em `tasks/`.

Casos que **não** viram task (ainda):
- Tudo que está em "Pendências que EXIGEM sessão de modelo forte" — vira **issue** com a
  label `needs-design`, e só vira task depois que alguém desenhar a solução.

**Nunca duplique o corpo.** A issue é o sintoma; a task é a solução; cada uma referencia a
outra por link.

## Índice e dependências

O título, dependências e status de cada task vivem no `INDICE.md` de cada trilha, não
neste arquivo — o índice completo cresceu grande demais para carregar em toda task.
Abra apenas a trilha em que for trabalhar.

O **histórico de conclusão** (o que foi feito, achados, decisões) não fica no
`INDICE.md` — fica na própria task, numa seção `## Resultado` no final do arquivo.
O `INDICE.md` mostra só um status curto (emoji + data, às vezes uma palavra) com um
link "ver **Resultado** na task" quando há histórico; task sem essa seção ainda não
tem histórico registrado.

| Trilha | Tema | Tasks | Índice completo |
|--------|------|-------|------------------|
| A | Truffle | 10 | [trilha-a-truffle/INDICE.md](trilha-a-truffle/INDICE.md) |
| B | Arquiteturas | 66 | [trilha-b-arquiteturas/INDICE.md](trilha-b-arquiteturas/INDICE.md) |
| C | Performance | 16 | [trilha-c-perf/INDICE.md](trilha-c-perf/INDICE.md) |
| D | Compatibilidade | 6 | [trilha-d-compat/INDICE.md](trilha-d-compat/INDICE.md) |
| E | Manutenção | 3 | [trilha-e-manutencao/INDICE.md](trilha-e-manutencao/INDICE.md) |
| F | Infra | 11 | [trilha-f-infra/INDICE.md](trilha-f-infra/INDICE.md) |
| G | 3DS | 12 | [trilha-g-3ds/INDICE.md](trilha-g-3ds/INDICE.md) |

Antes de pegar uma task, abra o `INDICE.md` da trilha correspondente e confira a coluna
**Depende de** — não execute uma task cujas dependências não estejam concluídas (✅).

Ao concluir uma task, atualize o status no `INDICE.md` da própria trilha (não aqui);
se o status passar de uma frase curta, escreva o histórico completo numa seção
`## Resultado` no final do arquivo da task, e deixe no `INDICE.md` só um resumo curto
apontando para lá (emoji + data + "ver **Resultado** na task").

Legenda: ⬜ pendente · 🟡 em andamento · ✅ concluída

## Matriz de validação por arquitetura

"A arquitetura X funciona?" tem resposta objetiva em
[docs/VALIDACAO-ARQUITETURAS.md](../docs/VALIDACAO-ARQUITETURAS.md) (níveis N1-N4,
comandos e status). Toda task que mude uma célula da matriz cita o arquivo no Aceite.

## Pendências que EXIGEM sessão de modelo forte (não pegar como task comum)

Registradas para não virarem tasks vagas; um agente comum NÃO deve tentá-las:

1. ~~Glitches do FireRed = granularidade de bloco do ASM~~ **REVOGADO 2026-07-16**:
   o usuário re-testou e os bugs de batalha acontecem IGUAIS nos dois backends
   (e a velocidade é igual) — a atribuição ao JIT de 2026-07-15 estava errada.
   Os bugs agora são tasks concretas com hipóteses: **D2** (3 bugs visuais de
   batalha), **D3** (SMW chiado), **D4** (Metroid melodia), **D6** (BIOS lenta).
   `INTERPRETED` segue default do gbaemu (decisão de produto mantida — mas pela
   simplicidade/fidelidade estrutural, não mais por "ASM causa glitch").
2. ~~Animação da BIOS lenta~~ → virou a task **D6** (com hipóteses; segue sendo
   sessão de modelo forte).
3. **Dispatch megamórfico remanescente do ndsemu** (`JitRuntime.execute` ~12-14% do
   perfil pós-superblocos, medição de 2026-07-11 em C1) — precisa de profiling novo
   e desenho; não há spec.
4. **Idle-loop skip** (detectar busy-wait em IO e avançar o relógio até o próximo
   evento) — potencialmente o maior ganho para jogos CPU-bound do ndsemu (teto
   ~50fps do MKDS), mas o desenho é arriscado (falso positivo = travamento/timing
   quebrado); precisa de RFC própria antes de virar task.
5. **Rodadas de spec futuras**: B4.1.x em arquivos próprios quando B4.1.1 começar;
   B6.3+ quando B6.2 fechar (escopos já fixados nos épicos).
6. **Divergência ASM×interpretador no JUS** (achada durante a re-medição da C11 fase 2,
   2026-07-16: `asmcheck` a partir de `roms/JUS.ss`, ~300 chunks, diverge em `r1` no
   `block@0x1ff8f44` — diferença de 0x38 num registrador só, resto idêntico). NÃO é
   causada pelo Fix C (`ASM_CHECK` não liga loop-superblocos; `JitRuntime.reset()` se
   comporta byte-a-byte como o `blockCache().clear()` antigo nesse backend) — pré-
   existente, versão de origem desconhecida, não investigada.
7. **Bug real achado na sessão de B6.3.1 (2026-07-25), NÃO corrigido (fora do
   "Inclui" daquela task)**: `Ir64BlockExecutor#executeAlu` (B6.1, `Ir64Op.Alu64`
   — forma imediata de `ADD`/`SUB`) resolve `Rd|SP`/`Rn|SP` checando SÓ a flag
   booleira (`dstIsStackPointer`/`src1IsStackPointer`), nunca o ÍNDICE do
   registrador (`== 31`) — o pseudocódigo real do manual (`if n == 31 then SP[]
   else X[n]`; `if d == 31 && !setflags then SP[] = result else X[d] = result`)
   e o próprio `readBaseRegister`/`writeBaseRegister` (load/store, mesmo
   arquivo) checam o índice. Confirmado com um teste descartável (não commitado)
   que `add x4, x5, #0x123` (`Rd=4`, `Rn=5`, nenhum dos dois é `31`) grava o
   resultado em `SP` em vez de `X4` — qualquer `ADD`/`SUB` (imediato) real com
   `Rd`/`Rn` != 31 está quebrado hoje. O código NOVO de B6.3.1
   (`Ir64Op.AluExtendedRegister`) implementa a checagem CORRETA (por índice),
   não copia esse padrão. Precisa de uma task de correção dedicada (fix +
   revisão dos testes de B6.1 que não pegaram isso porque coincidiam com `SP`
   default `0`).
