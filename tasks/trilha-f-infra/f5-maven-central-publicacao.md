# F5 — Publicar `dev.vitorsilverio:arm-jitter:1.0.0` no Maven Central

**Trilha:** F (infra) · **Depende de:** F1, F4 · **Repo:** arm-jitter
**🧑 PARCIALMENTE BLOQUEADA NO USUÁRIO** — os passos 1, 2 e 3 (conta, DNS, chave GPG) só o
usuário pode fazer. O agente faz os passos 4+ e a publicação manual de verificação.

## Contexto

Hoje todo consumidor (`gbaemu`, `ndsemu`, `armbox`, `virtual-arm-box`, e o futuro `n3dsemu`)
depende de um `mvn install` local do arm-jitter na máquina do usuário. Isso impede CI em
qualquer repo e é exatamente o que o usuário quer eliminar:

> "criar um pipeline no github para atualizar sem precisar depender de ficar instalando ele
> sempre na maquina do usuario junto com subprojeto"

**Decisão do usuário (2026-08-15):** **Maven Central**, mantendo o `groupId`
**`dev.vitorsilverio`** — ele já é dono do domínio `vitorsilverio.dev`, então a verificação
de namespace é por **registro DNS TXT**, sem precisar trocar para `io.github.*`.

Bônus documentado pelo usuário: com o arm-jitter no Central, os consumidores podem largar a
declaração direta de `org.ow2.asm:asm` — ela chega transitivamente (escopo `compile` em
`core/pom.xml`). Isso é a task **F7**.

## Objetivo

`dev.vitorsilverio:arm-jitter:1.0.0` resolvível do Maven Central por qualquer máquina, sem
credencial nenhuma do lado do consumidor.

## Inclui

1. (**usuário**) Conta no Central Portal + namespace `dev.vitorsilverio` verificado por DNS.
2. (**usuário**) Par de chaves GPG, chave pública em keyserver, chave privada disponível
   para o agente/CI.
3. Metadados obrigatórios nos POMs (`url`, `scm`, `developers` — `licenses` já veio da F1).
4. Plugins de release: `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
   `central-publishing-maven-plugin`, todos num **profile `release`** (para não pesar o build
   do dia a dia).
5. `settings.xml` documentado (não versionado — leva credencial).
6. Publicação de verificação do `1.0.0`.

## NÃO inclui (não fazer)

- **Não criar o workflow do GitHub Actions** — é a task F6. Esta task prova que a publicação
  funciona **manualmente**, da máquina, uma vez. Automatizar algo que nunca funcionou à mão é
  como se depura duas coisas ao mesmo tempo.
- **Não commitar credencial nenhuma**: nem `settings.xml`, nem chave GPG, nem token. Se
  alguma aparecer no `git status`, PARE.
- Não publicar `arm-jitter-capi` (F4 já pôs `maven.deploy.skip`).
- Não publicar SNAPSHOTs (o Central não aceita; o Portal só recebe releases).
- Não mexer nos consumidores (F7).

## Especificação

### Passo 1 — 🧑 Conta e namespace (usuário)

1. Entrar em `https://central.sonatype.com` e criar conta (login por GitHub serve).
2. *Namespaces* → *Add Namespace* → `dev.vitorsilverio`.
3. O Portal mostra um **código de verificação**. Criar no DNS de `vitorsilverio.dev` um
   registro **TXT** no domínio raiz (`@`) com esse código como valor.
4. Esperar propagação (minutos a horas) e clicar em *Verify Namespace*. O namespace fica
   ✅ *Verified*.
5. *Account* → *Generate User Token*. Isso devolve um par `<username>`/`<password>` (**não**
   é o login do portal — é um token). Guardar.

### Passo 2 — 🧑 Chave GPG (usuário)

O Central exige assinatura `.asc` de todo artefato.

```bash
gpg --full-generate-key           # RSA 4096, sem expiração ou 2 anos, com passphrase
gpg --list-secret-keys --keyid-format=long     # anotar o KEYID
gpg --keyserver keys.openpgp.org --send-keys <KEYID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>   # redundância; o Portal consulta ambos
```

Para a F6 (CI) será preciso, mais tarde:
`gpg --armor --export-secret-keys <KEYID> | base64 -w0` → segredo `GPG_PRIVATE_KEY` no
GitHub. **Não gere isso agora**; a F6 pede na hora.

### Passo 3 — 🧑 `~/.m2/settings.xml` (usuário; o agente escreve o modelo, o usuário cola os valores)

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

O agente deve deixar esse modelo em `docs/PUBLICAR.md` (versionado, **com os placeholders**,
nunca com valores reais).

### Passo 4 — Metadados obrigatórios no POM pai

O Central rejeita o *deploy* se faltar qualquer um. Acrescentar ao `pom.xml` pai, junto do
`<licenses>` que a F1 já pôs:

```xml
    <url>https://github.com/vitorsilverio/arm-jitter</url>

    <developers>
        <developer>
            <id>vitorsilverio</id>
            <name>Vitor Silverio Rodrigues</name>
            <email>vitor.silverio.rodrigues@gmail.com</email>
        </developer>
    </developers>

    <scm>
        <connection>scm:git:https://github.com/vitorsilverio/arm-jitter.git</connection>
        <developerConnection>scm:git:git@github.com:vitorsilverio/arm-jitter.git</developerConnection>
        <url>https://github.com/vitorsilverio/arm-jitter</url>
        <tag>HEAD</tag>
    </scm>
```

`<name>` e `<description>` já existem nos três POMs — conferir que **todo** módulo publicado
tem os dois preenchidos (o Central valida por artefato, não só no pai).

### Passo 5 — Profile `release` no POM pai

```xml
    <profiles>
        <profile>
            <id>release</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.3.1</version>
                        <executions>
                            <execution>
                                <id>attach-sources</id>
                                <goals><goal>jar-no-fork</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.11.2</version>
                        <executions>
                            <execution>
                                <id>attach-javadocs</id>
                                <goals><goal>jar</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.7</version>
                        <executions>
                            <execution>
                                <id>sign-artifacts</id>
                                <phase>verify</phase>
                                <goals><goal>sign</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.7.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <autoPublish>false</autoPublish>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

`autoPublish=false` **de propósito na primeira vez**: o *deployment* fica *VALIDATED* no
portal e o usuário aperta *Publish* à mão, depois de conferir o conteúdo. Depois que o
primeiro release estiver no ar, a F6 pode ligar `autoPublish=true`.

O `maven-source-plugin`/`maven-javadoc-plugin` **já aparecem** hoje em `core/pom.xml` sem
versão (herdando do `pluginManagement`). Conferir o `pluginManagement` do pai e **não
duplicar** — se já houver versão gerenciada lá, o bloco do profile pode omitir `<version>`.

### Passo 6 — Javadoc tem que compilar

O `maven-javadoc-plugin` é o que mais reprova release. O projeto usa Javadoc `///`
(markdown, Java 25) em português, em centenas de classes. **Rode `mvn -o -Prelease
javadoc:jar` antes de tentar publicar** e corrija os erros que aparecerem. Se houver muitos
avisos de `@param`/`@return` faltando, configurar `<doclint>none</doclint>` no plugin é
aceitável — mas **erros** (não avisos) precisam ser corrigidos de verdade.

### Passo 7 — Publicar

```bash
mvn -Prelease clean deploy
```

(Sem `-o`: o *deploy* precisa de rede. Continua exigindo JBR 25 no `JAVA_HOME`.)

Depois: `https://central.sonatype.com/publishing/deployments` → o *deployment* deve estar
*VALIDATED* → o **usuário** clica *Publish*. Aparece em
`https://repo1.maven.org/maven2/dev/vitorsilverio/arm-jitter/1.0.0/` em ~15–30 min, e no
índice de busca em algumas horas.

## Aceite

- [ ] Namespace `dev.vitorsilverio` **Verified** no Central Portal.
- [ ] `mvn -Prelease clean deploy` conclui sem erro e o deployment fica *VALIDATED*.
- [ ] Artefatos publicados: `arm-jitter-parent` (pom), `arm-jitter`, `arm-jitter-truffle` —
      cada um com `.jar`, `-sources.jar`, `-javadoc.jar` e os `.asc` correspondentes.
      **`arm-jitter-capi` NÃO aparece.**
- [ ] Prova de resolução: numa pasta temporária, um projeto Maven mínimo com só a
      dependência `dev.vitorsilverio:arm-jitter:1.0.0` e **sem** `~/.m2/repository/dev/`
      (renomeie a pasta temporariamente) roda `mvn dependency:resolve` com sucesso, baixando
      do Central. **Este é o aceite que prova que o `mvn install` manual acabou.**
- [ ] `docs/PUBLICAR.md` versionado, com o passo a passo e os placeholders (sem segredo).
- [ ] `git status` limpo de credenciais.
- [ ] Índice do `tasks/README.md` atualizado (F5 ✅).

## Validação

Além do aceite acima: `mvn -o test` continua verde (o profile `release` não pode afetar o
build padrão — confirme que `mvn -o test` **não** ativa GPG nem javadoc).

## Armadilhas

- **`maven-gpg-plugin` em terminal não interativo** pede passphrase e trava. Com GnuPG 2.1+
  use `<gpg.passphrase>` no `settings.xml` (como acima) e, se ainda travar, acrescente
  `--pinentry-mode loopback` via `<gpgArguments>`. Um `mvn deploy` "pendurado" sem saída é
  quase sempre isso.
- **O Central rejeita re-publicação da mesma versão.** Se o `1.0.0` for publicado com defeito,
  não dá para substituir: só resta `1.0.1`. Por isso `autoPublish=false` na primeira vez —
  confira a lista de arquivos no portal **antes** de publicar.
- **`-o` (offline) não funciona no deploy.** Boa parte dos comandos do projeto usa `-o` por
  hábito; aqui não pode.
- O `groupId` publicado é `dev.vitorsilverio`, que precisa **casar** com o domínio
  verificado `vitorsilverio.dev` (ordem invertida). Se a verificação DNS falhar, confira que
  o TXT está no domínio **raiz**, não em `www.` nem num subdomínio.
- Se o usuário não tiver acesso ao DNS agora, **PARE** e reporte. Não tente contornar
  trocando o `groupId` para `io.github.vitorsilverio` — a decisão de manter
  `dev.vitorsilverio` foi tomada explicitamente pelo usuário em 2026-08-15.
