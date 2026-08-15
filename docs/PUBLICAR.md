# Publicar no Maven Central

Passo a passo para publicar `dev.vitorsilverio:arm-jitter:<versão>` (+ `arm-jitter-truffle`) no
Maven Central. `arm-jitter-capi` **não** é publicado (`maven.deploy.skip=true` — é uma
biblioteca nativa, o jar comum não serve como dependência Maven).

Referência: task `tasks/trilha-f-infra/f5-maven-central-publicacao.md`.

## 1. Conta e namespace (uma vez só, feito pelo usuário)

1. Criar conta em <https://central.sonatype.com> (login por GitHub serve).
2. *Namespaces* → *Add Namespace* → `dev.vitorsilverio`.
3. O Portal mostra um código de verificação. Criar um registro **TXT** no domínio **raiz**
   (`@`) de `vitorsilverio.dev` com esse código.
4. Esperar a propagação do DNS e clicar em *Verify Namespace*.
5. *Account* → *Generate User Token* → guardar o par `<username>`/`<password>` (é um token,
   não o login do portal).

## 2. Chave GPG (uma vez só, feito pelo usuário)

```bash
gpg --full-generate-key           # RSA 4096, sem expiração ou 2 anos, com passphrase
gpg --list-secret-keys --keyid-format=long     # anotar o KEYID
gpg --keyserver keys.openpgp.org --send-keys <KEYID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
```

Para automação por CI (GitHub Actions, task F6) será preciso, na hora:
`gpg --armor --export-secret-keys <KEYID> | base64 -w0` → segredo `GPG_PRIVATE_KEY`.

## 3. `~/.m2/settings.xml` (por máquina; NUNCA versionar este arquivo com valores reais)

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME_DO_PORTAL</username>
      <password>TOKEN_PASSWORD_DO_PORTAL</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.keyname>SEU_KEYID</gpg.keyname>
        <gpg.passphrase>SUA_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>gpg</activeProfile></activeProfiles>
</settings>
```

Se o `gpg-plugin` travar pedindo passphrase (GnuPG 2.1+ em terminal não interativo), acrescente
`--pinentry-mode loopback` via `<gpgArguments>` no plugin do `pom.xml`.

## 4. Conferir o javadoc antes de tentar publicar

```bash
mvn -Prelease -DskipTests javadoc:jar
```

O `maven-javadoc-plugin` é o que mais reprova release. Corrigir **erros** (não avisos).

## 5. Publicar

```bash
mvn -Prelease clean deploy
```

(Sem `-o` — o deploy precisa de rede. Continua exigindo JBR 25 no `JAVA_HOME`.)

**Armadilha real encontrada (2026-08-15)**: o `central-publishing-maven-plugin` **não** respeita
`maven.deploy.skip` (é um plugin diferente do `maven-deploy-plugin`) — sem `<skipPublishing>true`
explícito no `capi/pom.xml`, o jar comum do `arm-jitter-capi` (que não serve como dependência,
só o `.dll`/`.so` nativo importa) entra no bundle publicado. Além disso a versão `0.7.0` do
plugin quebra (`UnrecognizedPropertyException: "warnings"`) ao consultar o status de um
*deployment* na API atual do Central — usar `0.11.0` (ou mais nova) resolve. A versão do plugin
precisa estar em `<pluginManagement>` (não só dentro do profile `release`), senão `capi/pom.xml`
não consegue resolvê-la num `mvn test` comum (que não ativa o profile).

Depois, em <https://central.sonatype.com/publishing/deployments>: o *deployment* deve ficar
*VALIDATED*. Conferir a lista de artefatos (jar/sources/javadoc/asc de `arm-jitter` e
`arm-jitter-truffle`, **sem** `arm-jitter-capi`) e só então clicar **Publish** — o
`autoPublish` do profile `release` é `false` de propósito na primeira vez, porque o Central
**não permite republicar a mesma versão** se algo sair errado.

Aparece em `https://repo1.maven.org/maven2/dev/vitorsilverio/arm-jitter/` em ~15–30 min, e no
índice de busca em algumas horas.

## 6. Provar que funcionou

Numa pasta temporária, um projeto Maven mínimo com só a dependência
`dev.vitorsilverio:arm-jitter:<versão>` e **sem** `~/.m2/repository/dev/` (renomear a pasta
temporariamente) deve resolver via `mvn dependency:resolve` sem `mvn install` local nenhum.

## 7. Release automatizado (F6): segredos do repositório GitHub

Desde a F6, `git tag v<versão> && git push --tags` dispara `.github/workflows/release.yml`, que
repete os passos 4-5 acima sem intervenção manual (o `autoPublish` do profile `release` virou
`true` no `pom.xml` depois de confirmar o `1.0.0` no ar — sem isso o *deployment* sobe mas fica
esperando alguém clicar **Publish** no portal, o que anula a automação).

Quem cadastra os segredos é o **usuário**, em
`https://github.com/vitorsilverio/arm-jitter/settings/secrets/actions`:

| Segredo | Como obter |
|---------|-----------|
| `MAVEN_CENTRAL_USERNAME` | *Generate User Token* no Central Portal (passo 1.5 acima) — a parte usuário do token |
| `MAVEN_CENTRAL_PASSWORD` | idem — a parte senha do token |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <KEYID>` (passo 2) — o bloco ASCII **inteiro**, incluindo as linhas `-----BEGIN PGP PRIVATE KEY BLOCK-----`/`-----END...-----` |
| `MAVEN_GPG_PASSPHRASE` | a passphrase escolhida ao gerar a chave (passo 2) |

**Armadilha**: não fazer push da tag antes de `release.yml` já estar no branch — a Action é lida
da ref da própria tag, então uma tag criada antes do arquivo existir no histórico não dispara
nada e a versão fica queimada (o Central não permite republicar a mesma versão).
