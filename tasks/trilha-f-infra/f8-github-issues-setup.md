# F8 — Issues no GitHub: fronteira com `tasks/`, labels, milestones e templates

**Trilha:** F (infra) · **Depende de:** — · **Repo:** arm-jitter, gbaemu, ndsemu, armbox
**Desbloqueada:** o `gh` **já está instalado e autenticado** (confirmado em 2026-08-15 —
conta `vitorsilverio`, escopos `repo`, `read:org`, `gist`, `admin:public_key`). Ele **não está
no PATH**: o executável é `C:\Program Files\GitHub CLI\gh.exe`. Use o caminho completo, ou
acrescente `C:\Program Files\GitHub CLI` ao PATH no início da sessão.

## Contexto

Hoje **todo** o planejamento vive em `arm-jitter/tasks/*.md`, incluindo bugs de gameplay de
outros repos (D2 FireRed, D3 SMW, D5 Platinum...) e "pendências que exigem modelo forte". O
usuário quer começar a registrar os problemas como issues no GitHub. Sem uma fronteira
escrita, os dois sistemas vão divergir em uma semana: a mesma coisa em dois lugares, com
estados diferentes.

## Objetivo

Uma regra escrita de o que é issue e o que é task; e os 4 repos preparados (labels,
milestones, templates) para as issues nascerem organizadas — antes de a F9 criar as ~21
issues do backlog atual.

## Inclui

1. Seção `## Issues do GitHub × `tasks/`` no `arm-jitter/tasks/README.md`.
2. Conjunto único de labels, criado nos 4 repos.
3. Milestones nos repos que precisam.
4. `.github/ISSUE_TEMPLATE/` nos 4 repos.

## NÃO inclui (não fazer)

- **Não criar as issues** — é a F9.
- **Não migrar as tasks existentes para issues.** As specs de `tasks/` continuam onde estão.
  A regra abaixo vale daqui para frente, e para o backlog que a F9 registra.
- Nada no `virtual-arm-box` (sem repo remoto, decisão do usuário).

## Especificação

### 1. A fronteira (texto para o `tasks/README.md`)

```markdown
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
```

### 2. Labels (idênticas nos 4 repos)

| Label | Cor | Uso |
|-------|-----|-----|
| `bug` | `d73a4a` | comportamento errado observável |
| `perf` | `fbca04` | lento, mas correto |
| `compat` | `0e8a16` | jogo/binário específico que não funciona |
| `feature` | `a2eeef` | pedido novo |
| `infra` | `c5def5` | build, CI, release, licença, documentação |
| `needs-design` | `d4c5f9` | precisa de RFC/sessão de modelo forte antes de virar task |
| `blocked:asset` | `e99695` | bloqueado em ROM/BIOS/kernel/toolchain que não temos |
| `blocked:user` | `e99695` | precisa de decisão ou validação humana |
| `jit` | `bfd4f2` | toca o pipeline de compilação (só arm-jitter) |
| `gpu` | `bfd4f2` | vídeo/rasterização (gbaemu, ndsemu, n3dsemu) |
| `audio` | `bfd4f2` | som |

Criar com:

```bash
gh label create bug --repo vitorsilverio/<repo> --color d73a4a --description "comportamento errado observável" --force
```

`--force` torna o comando idempotente (atualiza se já existir — `bug` já vem por padrão em
todo repo novo, com descrição diferente).

Escrever um script `tasks/issues/criar-labels.sh` que faz os 4 repos × 11 labels em laço, e
versioná-lo — a F9 e os repos futuros (n3dsemu) reusam.

### 3. Milestones

Só onde há um agrupamento real hoje. Criar com `gh api` ou `gh milestone` (dependendo da
versão do `gh`; se o subcomando não existir, use
`gh api repos/vitorsilverio/<repo>/milestones -f title=... -f description=...`):

| Repo | Milestone | Descrição |
|------|-----------|-----------|
| arm-jitter | `1.1` | correções e melhorias sem quebra de API depois do 1.0.0 |
| gbaemu | `Fidelidade` | os bugs de áudio/vídeo conhecidos (D2, D3, D4, D6) |
| ndsemu | `Compatibilidade` | JUS, Platinum, MKDS |

Não criar milestone vazia em repo que não tem agrupamento (armbox).

### 4. Templates `.github/ISSUE_TEMPLATE/`

Dois formulários YAML (`issue forms`) + desligar issue em branco.

`bug.yml` — campos: **Resumo** (input, obrigatório), **Como reproduzir** (textarea,
obrigatório — inclui ROM/binário e backend usados), **Comportamento esperado** (textarea,
obrigatório), **Evidência** (textarea: log, hash de commit, print, savestate),
**Backend** (dropdown: `JIT/ASM`, `INTERPRETED`, `ambos`, `não sei`), **Já era assim antes?**
(dropdown: `regressão nova`, `sempre foi assim`, `não sei`) — este último existe porque a
história do projeto tem pelo menos dois casos em que uma suspeita de regressão do JIT era
bug pré-existente (memória `gba-c6-gameplay-findings`, e a revogação de 2026-07-16 no
`tasks/README.md`).

`feature.yml` — campos: **O que** (obrigatório), **Por quê** (obrigatório), **Referência**
(como outro emulador/hardware real faz).

`config.yml`:
```yaml
blank_issues_enabled: false
```

Cada template aplica a label correspondente por padrão (`labels: [bug]` / `labels: [feature]`).

## Passos

1. Escrever a seção da fronteira no `tasks/README.md`.
2. Escrever os templates nos 4 repos.
3. Escrever `tasks/issues/criar-labels.sh`.
4. Rodar o script de labels; criar as 3 milestones.
5. Commits: um por repo para os templates; um no arm-jitter para a seção + script.

## Aceite

- [ ] `tasks/README.md` tem a seção da fronteira.
- [ ] `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,config.yml}` nos 4 repos.
- [ ] `gh label list --repo vitorsilverio/<repo>` mostra as 11 labels nos 4 repos.
- [ ] As 3 milestones existem.
- [ ] Abrir a página "New issue" de um dos repos e ver os dois formulários, sem opção de
      issue em branco.
- [ ] Índice do `tasks/README.md` atualizado (F8 ✅).

## Validação

Nenhum build é afetado. Validação é visual (página de nova issue) + `gh label list`.

## Armadilhas

- `gh label create` falha se a label existir; use sempre `--force`.
- A label `bug` **já existe** em todo repo do GitHub com cor/descrição padrão — `--force`
  cuida disso; não a apague antes.
- Templates YAML de *issue forms* falham silenciosamente se o YAML for inválido: o GitHub
  simplesmente não mostra o formulário. Depois do push, **abra a página de nova issue** e
  confirme — não confie no arquivo.
- Não habilite Discussions, Projects ou Wiki. Fora de escopo.
