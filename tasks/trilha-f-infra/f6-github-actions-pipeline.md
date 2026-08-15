# F6 — Pipeline no GitHub Actions: CI em todos os repos + release automatizado do arm-jitter

**Trilha:** F (infra) · **Depende de:** F5 · **Repo:** arm-jitter, gbaemu, ndsemu, armbox
**Nota:** `virtual-arm-box` fica de fora — o usuário decidiu (2026-08-15) não publicá-lo no
GitHub por enquanto, então não há onde rodar Actions.

## Contexto

Com o 1.0.0 no Central (F5), qualquer máquina resolve o arm-jitter — inclusive um runner do
GitHub. É o que torna CI possível pela primeira vez neste conjunto de projetos.

Dois workflows, com papéis distintos:
- **`ci.yml`** (nos 4 repos): roda `mvn test` a cada push/PR. É a automação do G5 (o
  invariante "gbaemu e ndsemu são o gate de regressão"), que hoje depende de o agente lembrar
  de rodar as suítes à mão.
- **`release.yml`** (só arm-jitter): dispara em tag `v*` e publica no Central, repetindo o
  que a F5 fez à mão.

## Objetivo

`git tag v1.0.1 && git push --tags` no arm-jitter publica no Central sozinho; e todo push nos
4 repos roda a suíte.

## Inclui

1. `.github/workflows/ci.yml` em arm-jitter, gbaemu, ndsemu, armbox.
2. `.github/workflows/release.yml` no arm-jitter.
3. Segredos do repositório arm-jitter documentados em `docs/PUBLICAR.md`.
4. Badge de status no README dos 4 repos.

## NÃO inclui (não fazer)

- Sem workflow no `virtual-arm-box` (sem repo remoto) nem no `n3dsemu` (ainda não existe; a
  task G1 já nasce com o `ci.yml` incluído).
- Sem matriz de sistemas operacionais. **Só `ubuntu-latest`** — o projeto é Java puro e
  multiplicar por Windows/macOS triplica o custo e o tempo de depuração do primeiro CI. Se
  algum teste falhar só no Linux, isso é um achado valioso (o desenvolvimento é todo em
  Windows), não um motivo para desligar o CI.
- Sem `native-image`/GraalVM no CI (o perfil `native` do armbox precisa de MSVC/toolchain
  pesado). Se o `armbox` tiver teste que exija native-image, exclua-o por perfil e anote.
- Sem publicação automática de SNAPSHOT a cada push. Só release por tag.
- Sem `dependabot`, sem análise estática, sem cobertura. Fora de escopo.

## Especificação

### JDK no CI

O projeto **exige Java 25** (`<release>25</release>`). O usuário compila com JBR 25 na
máquina dele, mas o JBR não está no `setup-java`; use **Temurin 25**, que é a distribuição
padrão e serve (a exigência de JBR é do ambiente do usuário, não do código).

Se algum teste falhar no Temurin e passar no JBR, **não force**: registre no README qual
teste e reporte. Só o backend Truffle tem dependência real de JVM específica (JBR+Unchained)
— se os testes do módulo `truffle` falharem no Temurin, exclua **esse módulo** do CI com
`-pl '!truffle'` e documente o motivo no comentário do workflow.

### `ci.yml` (nos 4 repos — idêntico, mudando só o nome)

```yaml
name: CI

on:
  push:
    branches: ['**']
  pull_request:
  workflow_dispatch:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: maven

      - name: mvn test
        run: mvn --batch-mode --no-transfer-progress test

      - name: Publica os relatórios de teste
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: '**/target/surefire-reports/**'
```

**Atenção por repo — assets que o CI não tem:**

- **gbaemu**: os testes precisam de `gba_bios.bin` e ROMs comerciais? Antes de escrever o
  workflow, rode `mvn -o test` localmente com esses arquivos **renomeados** e veja o que
  quebra. Todo teste que dependa de asset não versionável tem que ser marcado com
  `@Disabled`-condicional (`@EnabledIf`/`Assumptions.assumeTrue(Files.exists(...))`) para ser
  **pulado**, não falhar, quando o arquivo não existir. Essa adaptação faz parte desta task.
- **ndsemu**: idem para `firmware/biosnds9.rom`, `biosnds7.rom`, `firmware.bin` e as ROMs de
  `roms/`.
- **armbox** e **arm-jitter**: `testdata/` é versionado (binários pequenos), deve rodar
  direto. Confirme.

Isso é o trabalho real desta task — o YAML é a parte fácil.

### `release.yml` (só arm-jitter)

```yaml
name: Release

on:
  push:
    tags: ['v*']
  workflow_dispatch:

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: maven
          server-id: central
          server-username: MAVEN_CENTRAL_USERNAME
          server-password: MAVEN_CENTRAL_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: MAVEN_GPG_PASSPHRASE

      - name: Confere que a tag casa com a versão do POM
        run: |
          POM_VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
          TAG_VERSION="${GITHUB_REF_NAME#v}"
          test "$POM_VERSION" = "$TAG_VERSION" \
            || { echo "tag $TAG_VERSION != versão do POM $POM_VERSION"; exit 1; }

      - name: Publica no Central
        run: mvn --batch-mode --no-transfer-progress -Prelease clean deploy
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
```

O passo de conferência de tag×POM existe porque publicar `1.0.1` sob a tag `v1.0.2` é
irreversível no Central (não dá para republicar versão).

`setup-java` com `server-id: central` gera o `settings.xml` do runner e importa a chave GPG —
não escreva `settings.xml` à mão no workflow.

### Segredos do repositório `vitorsilverio/arm-jitter`

Documentar em `docs/PUBLICAR.md` (o arquivo que a F5 criou), **valores nunca versionados**:

| Segredo | Como obter |
|---------|-----------|
| `MAVEN_CENTRAL_USERNAME` | *Generate User Token* no Central Portal (parte usuário) |
| `MAVEN_CENTRAL_PASSWORD` | idem (parte senha) |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <KEYID>` — o bloco ASCII **inteiro**, com as linhas `BEGIN`/`END` |
| `MAVEN_GPG_PASSPHRASE` | a passphrase da chave |

Quem cadastra é o **usuário** (Settings → Secrets and variables → Actions). O agente
documenta e avisa.

### `autoPublish`

Depois que o `1.0.0` da F5 estiver de fato no `repo1.maven.org`, trocar para
`<autoPublish>true</autoPublish>` no `central-publishing-maven-plugin` — sem isso o
`release.yml` sobe o deployment mas fica esperando alguém clicar, o que anula a automação.
**Só fazer essa troca depois de confirmar o 1.0.0 no ar.**

### Badges

No topo do README de cada repo:

```markdown
[![CI](https://github.com/vitorsilverio/<repo>/actions/workflows/ci.yml/badge.svg)](https://github.com/vitorsilverio/<repo>/actions/workflows/ci.yml)
```

E, só no arm-jitter, também:

```markdown
[![Maven Central](https://img.shields.io/maven-central/v/dev.vitorsilverio/arm-jitter)](https://central.sonatype.com/artifact/dev.vitorsilverio/arm-jitter)
```

## Passos

1. Para cada consumidor (gbaemu, ndsemu, armbox): rodar a suíte com os assets locais
   escondidos, listar os testes que quebram, e convertê-los para **pular** por
   `Assumptions.assumeTrue(...)`. Commit próprio por repo:
   `F6: testes que dependem de asset local passam a ser pulados quando o asset falta`.
2. Escrever os `ci.yml` nos 4 repos; **push e observar a execução real** (não confie no YAML
   sem ver verde).
3. Escrever `release.yml`; pedir ao usuário para cadastrar os 4 segredos.
4. Trocar `autoPublish` para `true` (só depois do 1.0.0 confirmado no Central).
5. Testar o release de ponta a ponta com uma versão de correção real: se houver qualquer
   ajuste pendente, publicá-lo como `1.0.1` via tag `v1.0.1`. Se não houver nada a corrigir,
   **não** publique uma versão vazia só para testar — deixe o `release.yml` provado na
   primeira release real e registre isso no aceite.
6. Badges nos READMEs.

## Aceite

- [ ] `ci.yml` verde nos 4 repos, com execução real observada no GitHub (colar a URL da
      execução no relatório de fechamento).
- [ ] Nenhum teste falha por falta de asset local; os que dependem de asset aparecem como
      *skipped* no relatório do CI.
- [ ] `release.yml` existe e o passo de conferência tag×POM está presente.
- [ ] `docs/PUBLICAR.md` lista os 4 segredos e quem os cadastra.
- [ ] Badges nos READMEs.
- [ ] Índice do `tasks/README.md` atualizado (F6 ✅, anotando se o release ficou provado ou
      só escrito).

## Validação

A execução real do CI **é** a validação. Localmente, `mvn -o test` continua verde nos 4
repos.

## Armadilhas

- **Não faça push da tag antes de o `release.yml` estar no branch.** A Action é lida da
  ref da tag; uma tag criada antes do arquivo existir não dispara nada e a versão fica
  queimada.
- Runner do GitHub é Linux: caminhos com `\`, `C:\...` e comparação de caminho sensível a
  maiúsculas quebram lá e não aqui. É um achado legítimo — conserte o **teste**, não o CI.
- `actions/setup-java@v4` com `java-version: '25'` pode não ter Temurin 25 disponível na
  época da execução. Se não tiver, use `'24'` **apenas se** o projeto compilar (ele exige
  `release 25`, então provavelmente não) — caso contrário, PARE e reporte ao usuário: sem
  JDK 25 no runner, não há CI possível.
- O gbaemu tem arquivos grandes versionados na raiz (`pokefirered.gba`, `smw.gba`,
  `gba_bios.bin`). O `checkout` vai baixá-los a cada execução — é lento mas funciona. **Não**
  remova esses arquivos do repo nesta task; é decisão do usuário.
