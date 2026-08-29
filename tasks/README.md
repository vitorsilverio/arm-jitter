# tasks/ — Spec Driven Development

Cada arquivo aqui é uma task **autocontida**, escrita para ser executada por um agente
sem contexto prévio do projeto. Leia este arquivo inteiro antes de executar qualquer task.

## 🎯 Regra máxima do projeto (decisão do usuário, 2026-08-24 — NUNCA reabrir sem ele)

**Este emulador vai emular ARM 100%. Se alguma arquitetura ARM existe — v4T até a mais recente
AArch64/ARMv9.x, qualquer perfil (A/R/M), qualquer extensão opcional real (`FEAT_*`) — ela é alvo
deste projeto, cedo ou tarde. Nenhuma instrução, nenhum registrador, nenhuma feature ARM real fica
de fora por parecer "grande demais", "rara demais", "posterior ao hardware que mirávamos hoje" ou
"nenhum consumidor usa isso agora". "Alvo atual" (Cortex-A53/raspi3, por exemplo) é sobre ORDEM de
trabalho, nunca sobre ESCOPO final — o escopo final é ARM inteiro.**

Consequências práticas, obrigatórias para toda sessão:

- **`docs/isa-nao-aplicavel.tsv` nunca é "está fora do nosso alvo".** A única entrada legítima é
  "esta versão de arquitetura/feature ainda não foi implementada, com a fonte que prova a versão
  real que a introduziu" — a instrução continua sendo trabalho PENDENTE, só reclassificado por
  degrau cronológico dentro do próprio ARM, nunca descartado. Quando alguma trilha (B7/B7-plano)
  chegar perto de "tudo ✅" numa arquitetura, o próximo degrau natural é a versão SEGUINTE do ARM,
  não parar.
- **Nenhuma task decide "isso está fora de escopo para sempre".** Uma task pode legitimamente dizer
  "fora do orçamento desta sessão, candidata a task própria" — isso é sequenciamento, não exclusão.
  Nunca "não vamos implementar isso".
- Precedentes que já geraram este princípio e continuam valendo: `feedback-nunca-excluir-instrucao-arm`
  (memória do agente, sobre EL1/EL2/EL3 — "se existe no ARM, emular, mesmo que exija EL2/EL3
  completos") e a decisão de 2026-08-24 sobre ARMv9 ser alvo real do `virtual-arm-box` (não "vem de
  graça depois", ver `ROADMAP.md` Trilha B) — este bloco generaliza os dois para TODA a superfície
  do ARM, não só os casos que já apareceram.
- Se uma sessão encontrar uma instrução/feature/versão de arquitetura que parece implicar trabalho
  enorme (ex.: um perfil inteiro, um modo de exceção completo, uma extensão SIMD grande) — a reação
  correta é **quebrar em tasks menores e enfileirar**, nunca excluir. Documentar o tamanho é
  informação; decidir não fazer não é uma opção disponível para o agente sozinho.
- **"Não se aplica a nenhum preset atual" NÃO é uma exclusão** (regra explicitada em 2026-08-28,
  depois de o usuário cobrar por que NEON/MVE/SVE/SME apareciam assim na tabela). Esse rótulo é o
  `NOT_IN_ANY_PRESET` de `IsaCoverageReport` e significa apenas *"nenhum `ArmArchitecture`/
  `Aarch64Architecture` declara esta extensão AINDA"* — é diagnóstico de lacuna de
  infraestrutura, exatamente como o B11 concluiu para o A64. Todo grupo nesse estado tem que ter
  um épico com escada; hoje todos têm: **B13** (NEON 32 bits), **B14** (VFP incondicional
  ARMv8-A), **B15** (perfil M moderno), **B16** (MVE/Helium), **B17** (SVE/SVE2), **B18**
  (SME/SME2). Nenhum grupo pode voltar a ficar sem épico correspondente.
- **A biblioteca não tem "consumidor único".** O `arm-jitter` está publicado no Maven Central:
  qualquer pessoa pode construir sobre ele. "Nenhum projeto deste workspace usa X hoje" é fato
  sobre QUAIS REGRESSÕES TESTAR (G5) — nunca argumento para adiar ou reduzir escopo.

## 🔒 Congelamento de subprojetos até 100% de cobertura (decisão do usuário, 2026-08-27 — NUNCA reabrir sem ele)

**Nenhuma task de `armbox`/`gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` deve ser pega — nem
investigação, nem feature, nem bugfix de compatibilidade — enquanto `docs/COBERTURA-ISA.md` não
mostrar cobertura completa das arquiteturas/perfis/features/modos ARM alvo.** Só trabalho de
cobertura de ISA no `arm-jitter` é elegível agora.

Motivo: sessão após sessão, um gap de decode só era descoberto ao rodar um binário real num
subprojeto ("achado real: instrução X não implementada") — o custo de debugar e isolar cada gap
um a um acabou sendo maior e mais frustrante do que teria sido cobrir a ISA inteira desde o
início. Isso não é uma mudança da Regra Máxima acima (que já dizia "ARM 100% é o escopo final") —
é uma ordem de PRIORIDADE: cobertura primeiro, tudo mais depois. Se uma sessão de subprojeto
revelar um gap novo, ele vira task de cobertura no `arm-jitter`, não motivo para aprofundar no
subprojeto. Ver memória do agente `feedback-100-cobertura-antes-subprojetos`.

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
8. **`git push` em TODO repositório que a task tocou** — ver "Push obrigatório" abaixo.

## Push obrigatório (regra nova, 2026-08-21)

Os repositórios estavam acumulando dezenas de commits **só localmente** (o `arm-jitter` chegou a 76
commits à frente do `origin`, o `virtual-arm-box` a 34). Isso anula o backup, a CI do GitHub (F6) e
qualquer possibilidade de outra pessoa ver o trabalho.

**Toda task termina com `git push` em cada repositório que ela tocou**, depois das suítes verdes e
do commit. Se o push falhar (rejeitado por divergência), resolver na hora — não deixar para depois.

Um repositório sem `origin` é um problema a reportar ao usuário, não a ignorar.

## Marcos de cobertura de ISA → release no Maven Central (regra nova, 2026-08-21; **suspensa em
## 2026-08-27, ver abaixo — NUNCA reabrir sem o usuário**)

🔒 **`1.4.0` fica RESERVADA para quando `docs/COBERTURA-ISA.md` mostrar 100% de cobertura de TODA a
arquitetura ARM alvo** (decisão do usuário, 2026-08-27) — a conta está perto do limite MENSAL de
releases do Maven Central (já mencionado na publicação da `1.3.0`), e cada publicação consome esse
orçamento crítico. **Os dois gatilhos abaixo (+5pp global / +10pp arquitetura) ficam SUSPENSOS**:
não publicar nenhuma versão nova só por tê-los cruzado — B8.19 já cruzou o gatilho de arquitetura
(A64 +12pp) e não foi publicada, decisão agora formalizada aqui. Continuar medindo e registrando o
delta em cada task (é informação útil), mas a AÇÃO de publicar fica bloqueada até 100%.

A frente de cobertura de ISA (`trilha-b-arquiteturas/b7-plano-cobertura-isa.md`) mede progresso em
`docs/COBERTURA-ISA.md`, seção "Progresso global". Gatilhos históricos (suspensos, ver acima) —
**publicar uma versão nova do `arm-jitter` no Maven Central sempre que**, desde o último release:

- o **global** subir **≥ 5 pontos percentuais**, OU
- qualquer **arquitetura** subir **≥ 10 pontos percentuais**.

Baseline do primeiro marco (2026-08-21, versão `1.0.0` publicada): **global 53%** — v4T 62% ·
v5TE 67% · v6K 86% · MPCore 82% · v7-A 83% · v6-M 22% · v7-M 54% · **A64 18%**.

**Histórico de releases** (baseline = cobertura no momento de cada publicação):

| Versão | Data | Global | A64 | Nota |
|---|---|---|---|---|
| `1.0.0` | 2026-08-21 | 53% | 18% | primeiro release |
| `1.1.0` | 2026-08-23 | 59% | 27% | marco B10 (EL2/EL3) + B8.1-B8.5 |
| `1.2.0` | 2026-08-26 | 71% | 61% | marco B8.6-B8.10 (AdvSIMD) — publicada por sessão sem registro na `FILA-EXECUCAO.md` na hora |
| `1.3.0` | 2026-08-27 | 73% | 68% | B8.11/B8.11b/B8.12 + E7/E8 — publicada a pedido do usuário **abaixo dos dois gatilhos** (delta desde `1.2.0`: global +2pp, A64 +7pp); via CI (`release.yml`), sincronização com `repo1.maven.org` mais lenta que o normal |

✅ **F7 (subir os consumidores) fechada em 2026-08-27** — `1.3.0` confirmada resolvível
(`repo1.maven.org/.../arm-jitter-1.3.0.pom` → `200`); os 5 consumidores (`gbaemu`/`ndsemu`/`armbox`/
`virtual-arm-box`/`n3dsemu`) agora pedem `1.3.0`. Ver `trilha-f-infra/f7-consumidores-central.md`
("Rodada 3") para o detalhe.

Regras do release:

- Versão **minor** (`1.1.0`, `1.2.0`, ...): a frente é aditiva, e o invariante **G3** proíbe
  breaking change.
- Segue o procedimento já validado pela **F5** (`trilha-f-infra/f5-maven-central-publicacao.md`).
- `CHANGELOG.md` ganha a entrada, citando a tabela de progresso antes e depois.
- Depois de publicar, **F7**: subir os 4 consumidores para a versão nova
  (`trilha-f-infra/f7-consumidores-central.md`) — a F4 já mostrou que deixar essa janela aberta
  quebra todo mundo.

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
- **G8 — Instrução não implementada TEM que ser recusada, não silenciosamente confundida
  com outra.** Um decoder que devolve a instrução errada para um encoding desconhecido troca um
  diagnóstico (exceção de instrução indefinida, que a B3.9 mostrou resolver em minutos) por
  corrupção silenciosa. Ao acrescentar um espaço de encoding, garantir que o que sobra cai em
  `UNIMPLEMENTED`. Ver a task `E6`.

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
| B | Arquiteturas | 73 | [trilha-b-arquiteturas/INDICE.md](trilha-b-arquiteturas/INDICE.md) |
| C | Performance | 16 | [trilha-c-perf/INDICE.md](trilha-c-perf/INDICE.md) |
| D | Compatibilidade | 6 | [trilha-d-compat/INDICE.md](trilha-d-compat/INDICE.md) |
| E | Manutenção | 4 | [trilha-e-manutencao/INDICE.md](trilha-e-manutencao/INDICE.md) |
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
5b. **RFC B13.2** (reuso do núcleo vetorial A64 pelo pipeline de 32 bits vs espelhamento) e
   **RFC B17.2** (comprimento de vetor SVE: fixo em 128 bits vs configurável). São as duas
   decisões que definem o custo total de B13/B16 e de B17/B18 — escolher errado custa o épico
   inteiro, e nenhuma sub-task depois delas deve ser pega antes de fecharem.
6. **Divergência ASM×interpretador no JUS** (achada durante a re-medição da C11 fase 2,
   2026-07-16: `asmcheck` a partir de `roms/JUS.ss`, ~300 chunks, diverge em `r1` no
   `block@0x1ff8f44` — diferença de 0x38 num registrador só, resto idêntico). NÃO é
   causada pelo Fix C (`ASM_CHECK` não liga loop-superblocos; `JitRuntime.reset()` se
   comporta byte-a-byte como o `blockCache().clear()` antigo nesse backend) — pré-
   existente, versão de origem desconhecida, não investigada.
7. ~~Bug real achado na sessão de B6.3.1~~ ✅ **CORRIGIDO pela `B6.14`, 2026-08-24** —
   `Ir64BlockExecutor#executeAlu` resolvia `Rd|SP`/`Rn|SP` só pela flag do decoder, nunca pelo
   índice; ganhou a mesma checagem dupla de `executeAluExtendedRegister`. Ver
   `trilha-b-arquiteturas/b6.14-aarch64-alu-immediate-sp-bug.md`.
