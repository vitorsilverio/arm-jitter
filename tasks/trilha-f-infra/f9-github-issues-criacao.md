# F9 — Criar no GitHub as issues do backlog conhecido

**Trilha:** F (infra) · **Depende de:** F8 · **Repo:** arm-jitter, gbaemu, ndsemu, armbox
**Desbloqueada:** `gh` instalado e autenticado (confirmado em 2026-08-15 — conta
`vitorsilverio`). **Não está no PATH**: use `C:\Program Files\GitHub CLI\gh.exe`.

## Contexto

Os corpos das issues **já estão escritos e versionados** em `arm-jitter/tasks/issues/`,
redigidos na sessão de planejamento de 2026-08-15 a partir do que já estava documentado
(memória do projeto, `FILA-EXECUCAO.md`, índice do `tasks/README.md`, READMEs). Esta task é
mecânica: postar cada arquivo como issue no repo certo, com título, labels e milestone certos.

**Não reescreva os corpos.** Eles foram redigidos com o contexto completo dos projetos, que
uma sessão de execução não tem. Se um corpo estiver factualmente errado, **corrija o arquivo
no git** (commit próprio) antes de postar, e diga o que mudou no relatório.

## Objetivo

Todo problema conhecido tem uma issue rastreável, e `tasks/issues/MANIFEST.md` registra o
número de cada uma.

## Inclui

1. Criar as issues listadas em `tasks/issues/MANIFEST.md`.
2. Preencher a coluna `Issue` do manifesto com o número atribuído por repo.
3. Adicionar `Fecha: <repo>#<n>` no cabeçalho das tasks de `tasks/` que já correspondem a
   uma issue (a coluna "Task relacionada" do manifesto diz quais).

## NÃO inclui (não fazer)

- Não criar issues para coisas fora do manifesto. Se você achar um problema novo durante a
  execução, anote no relatório — não abra issue por conta própria.
- Não fechar nenhuma issue.
- Não abrir issue para as tasks da trilha F ou G (elas são planejamento, não problema).
- Nada no `virtual-arm-box` (sem repo remoto). As duas entradas de manifesto marcadas
  `virtual-arm-box` ficam **pendentes**, documentadas como tal — não as poste em outro repo.

## Especificação

Para cada linha do manifesto:

```bash
gh issue create \
  --repo vitorsilverio/<repo> \
  --title "<título do manifesto>" \
  --body-file tasks/issues/<repo>/<arquivo>.md \
  --label "<label1>" --label "<label2>" \
  --milestone "<milestone>"     # só quando o manifesto indicar
```

O comando devolve a URL da issue criada — dela sai o número, que vai para o manifesto.

**Ordem importa** para as issues que se referenciam entre si (o manifesto marca quais):
poste primeiro as referenciadas, depois edite o corpo das que citam, trocando o placeholder
`#TBD-<slug>` pelo número real, com `gh issue edit <n> --repo ... --body-file ...`.

## Passos

1. `& "C:\Program Files\GitHub CLI\gh.exe" auth status` — confirme `Logged in to github.com`.
2. Conferir que as labels da F8 existem nos 4 repos (`gh label list`).
3. Postar as issues sem dependência entre si.
4. Preencher os números no manifesto.
5. Resolver os placeholders `#TBD-*` e reeditar os corpos afetados.
6. Adicionar `Fecha: <repo>#<n>` nas tasks correspondentes.
7. Commit no arm-jitter: `F9: registra o backlog conhecido como issues no GitHub`.

## Aceite

- [ ] Toda linha do manifesto marcada como postável tem um número de issue preenchido.
- [ ] `gh issue list --repo vitorsilverio/<repo>` mostra as issues com as labels certas nos
      4 repos.
- [ ] Nenhum placeholder `#TBD-` sobrou em nenhum corpo publicado.
- [ ] As tasks citadas na coluna "Task relacionada" têm a linha `Fecha: <repo>#<n>`.
- [ ] Índice do `tasks/README.md` atualizado (F9 ✅).

## Validação

Não há build. Validação é `gh issue list` nos 4 repos + leitura de duas ou três issues
criadas, conferindo que a formatação Markdown ficou correta (tabelas, blocos de código).

## Armadilhas

- `gh issue create --milestone` exige o **título exato** da milestone; se ela não existir, o
  comando falha e a issue **não** é criada. Rode a F8 antes.
- Corpo com crase tripla e tabela sobrevive bem ao `--body-file`; corpo passado por `--body`
  com aspas na PowerShell **não**. Use sempre `--body-file`.
- Se uma issue for criada por engano/duplicada, **feche-a** com um comentário explicando; não
  apague (apagar issue exige permissão de admin e some com o número, o que confunde
  referências futuras).
- O `gh` usa o repo do diretório corrente quando `--repo` é omitido. Como as issues vão para
  4 repos diferentes a partir do checkout do arm-jitter, **sempre passe `--repo`**.
