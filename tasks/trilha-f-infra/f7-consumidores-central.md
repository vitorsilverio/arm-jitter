# F7 — Consumidores passam a usar o arm-jitter 1.0.0 do Central (e largam o `asm` declarado)

**Trilha:** F (infra) · **Depende de:** F5 · **Repo:** gbaemu, ndsemu, armbox, virtual-arm-box
**Fecha a janela aberta pela F4** (em que os consumidores não compilam). Rodar logo depois da F5.

## Contexto

A F4 subiu o arm-jitter de `1.0` para `1.0.0` e a F5 publicou no Central. Os quatro
consumidores ainda pedem `dev.vitorsilverio:arm-jitter:1.0`, que deixou de existir — estão
quebrados por desenho até esta task rodar.

Dois ganhos de uma vez, os dois pedidos pelo usuário:

1. **Fim do `mvn install` manual do arm-jitter.** A dependência resolve do Central.
2. **Fim da declaração direta de `org.ow2.asm:asm`.** Verificado nesta sessão de
   planejamento: **nem gbaemu nem ndsemu importam `org.objectweb.asm` em uma única linha de
   código** (`grep -rl "org.objectweb.asm" gbaemu/src ndsemu/src` não retorna nada). O `asm`
   é usado só *dentro* do arm-jitter (`codegen.jvm`), e `core/pom.xml` já o declara em escopo
   `compile` — ou seja, chega transitivamente. As declarações nos consumidores são
   redundantes e, pior, **fixam uma versão** que pode divergir da que o arm-jitter usa.

## Objetivo

Os quatro consumidores compilam e passam nos testes **sem** nenhum `arm-jitter` no `~/.m2`
local, resolvendo do Central; e nenhum deles declara `org.ow2.asm:asm`.

## Inclui

Por repo (gbaemu, ndsemu, armbox, virtual-arm-box):
1. `<version>1.0</version>` → `1.0.0` na dependência `dev.vitorsilverio:arm-jitter`.
2. Remoção do bloco `<dependency>` de `org.ow2.asm:asm` — **só onde ele existe e só se o
   código não o importar** (ver verificação obrigatória abaixo).
3. Atualização das instruções de build no `README.md`/`AGENTS.md` que mandam rodar
   `mvn install` no arm-jitter.

## NÃO inclui (não fazer)

- Não mudar versão de nenhuma outra dependência (junit, input4j, graalvm sdk, truffle).
- Não mexer em código Java — esta task é de POM e documentação. Se remover o `asm` quebrar a
  compilação de algum repo, isso significa que a verificação abaixo foi feita errado:
  **reponha a declaração** e reporte, não "conserte" o código.
- Não tocar no arm-jitter.

## Especificação

### Estado atual, por repo (levantado em 2026-08-15 — reconfirme antes de editar)

| Repo | declara `arm-jitter` | declara `org.ow2.asm:asm` | importa `org.objectweb.asm` no código? |
|------|---------------------|--------------------------|----------------------------------------|
| gbaemu | `1.0` | ✅ sim, `9.7.1` | ❌ **não** → remover a declaração |
| ndsemu | `1.0` | ✅ sim, `9.7.1` | ❌ **não** → remover a declaração |
| armbox | `1.0` (+ `arm-jitter-truffle:1.0`) | ❌ não declara | — |
| virtual-arm-box | `1.0` | ❌ não declara | — |

**Verificação obrigatória antes de remover, em cada repo:**

```
grep -rn "org\.objectweb\.asm" src/
```

Se retornar **qualquer** linha, **não remova** a declaração naquele repo: em vez disso troque
a versão fixa por nada (deixe a versão ser gerenciada) e reporte ao usuário que aquele repo
usa ASM diretamente, ao contrário do esperado.

### Edições

`gbaemu/pom.xml` e `ndsemu/pom.xml` — apagar o bloco inteiro:

```xml
        <dependency>
            <groupId>org.ow2.asm</groupId>
            <artifactId>asm</artifactId>
            <version>9.7.1</version>
        </dependency>
```

Nos quatro POMs, `1.0` → `1.0.0` nas dependências `arm-jitter` e (no armbox)
`arm-jitter-truffle`.

### Documentação a corrigir

Frases que mandam instalar o arm-jitter localmente e deixam de ser verdade:

- `ndsemu/AGENTS.md`, seção "Build e testes": *"A `arm-jitter` precisa estar instalada no
  repositório Maven local (`mvn install` nela) para o ndsemu resolver a dependência
  `dev.vitorsilverio:arm-jitter:1.0`"* → substituir por: a dependência resolve do **Maven
  Central**; `mvn install` local no arm-jitter só é necessário quando se está **desenvolvendo
  a lib** e se quer testar a mudança antes de publicar (aí a versão local tem de ser um
  `-SNAPSHOT` e o consumidor apontar para ela temporariamente — **sem commitar** essa
  mudança).
- Mesmo tratamento em qualquer `README.md`/`AGENTS.md` dos outros três repos que diga o
  mesmo. Levante com `grep -rn "mvn install" --include="*.md"` em cada repo.

### O fluxo de desenvolvimento da lib, escrito uma vez

Acrescentar em `arm-jitter/README.md` (seção nova `## Desenvolvendo a lib junto com um
consumidor`) — os consumidores linkam para ela em vez de repetir:

```markdown
Enquanto uma mudança da lib não está publicada, o consumidor precisa da versão local:

1. No arm-jitter: `<version>1.0.1-SNAPSHOT</version>` + `mvn -o install`.
2. No consumidor: apontar a dependência para `1.0.1-SNAPSHOT` **sem commitar**.
3. Ao publicar (tag `v1.0.1`, ver `docs/PUBLICAR.md`), voltar os dois para a versão final e
   aí sim commitar.

Nunca commite um consumidor apontando para um `-SNAPSHOT`: o CI não resolve SNAPSHOT do
Central e o build quebra para todo mundo.
```

## Passos

1. Reconfirmar a tabela acima com o `grep` em cada repo.
2. Editar os 4 POMs.
3. **Prova de resolução do Central** — o aceite que importa:
   ```
   mv ~/.m2/repository/dev/vitorsilverio ~/.m2/repository/dev/vitorsilverio.bak
   ```
   (no Windows/PowerShell: `Rename-Item`) e então `mvn test` em cada um dos 4 repos. Tem de
   baixar do Central e passar. Restaurar a pasta depois.
4. Corrigir a documentação nos 4 repos + a seção nova no arm-jitter.
5. Um commit por repo: `F7: consome arm-jitter 1.0.0 do Maven Central`.

## Aceite

- [ ] Nenhum dos 4 POMs contém `org.ow2.asm`.
- [ ] Os 4 POMs pedem `arm-jitter` (e `arm-jitter-truffle`, no armbox) na versão `1.0.0`.
- [ ] Com `~/.m2/repository/dev/vitorsilverio` **renomeada**, `mvn test` passa nos 4 repos
      (gbaemu, ndsemu, armbox, virtual-arm-box). Colar no relatório a contagem de testes de
      cada um.
- [ ] `mvn dependency:tree` do gbaemu mostra `org.ow2.asm:asm` como dependência
      **transitiva** de `arm-jitter` (prova de que a remoção não perdeu nada).
- [ ] Nenhuma documentação ainda manda rodar `mvn install` no arm-jitter como pré-requisito
      normal de build.
- [ ] Índice do `tasks/README.md` atualizado (F7 ✅).

## Validação

`mvn test` nos 4 repos, com a pasta local do `dev.vitorsilverio` renomeada. **Este é o G5
completo** desta onda — os dois emuladores verdes são o gate de regressão.

## Armadilhas

- **Renomear a pasta do `~/.m2` é destrutivo se você esquecer de restaurar.** Faça
  `Rename-Item`, não `Remove-Item`, e restaure no mesmo passo do script.
- Se o `arm-jitter:1.0.0` ainda não estiver **visível** no `repo1.maven.org` (leva 15–30 min
  depois do *Publish*), o passo 3 vai falhar com "could not resolve". Espere e repita; não
  conclua que a F5 falhou.
- O ndsemu tem dezenas de arquivos `.java` soltos na **raiz** do repo (probes de depuração:
  `NavProbe.java`, `IndoorProbe.java`, ...). Eles não são compilados pelo Maven (estão fora
  de `src/`). Não os apague, não os mova, e use paths explícitos no `git add`.
- O armbox depende **também** de `arm-jitter-truffle` e de `org.graalvm.sdk:nativeimage` —
  esse último **não** sai, é dependência legítima e direta dele.

## Resultado

**Rodada 1 (2026-08-15)**: task executada como especificada acima, contra a `1.0.0`. Ver
`tasks/trilha-f-infra/INDICE.md` (linha F7) para o relato original.

**Rodada 2 (2026-08-23)**: a `1.1.0` foi publicada no Central pela B8.5 (commit `dcb5ca1`, mesmo
dia da B8.5, antes da B9.7 começar) — mesma janela que a F4 tinha aberto da primeira vez, agora
reaberta. Confirmado nos 5 `pom.xml` dos consumidores (`gbaemu`/`ndsemu`/`armbox`/
`virtual-arm-box`/`n3dsemu` — o `n3dsemu` é novo desde a rodada 1, não existia em 2026-08-15):
todos continuavam pedindo `1.0.0`.

**Diferença de escopo desta rodada**: `org.ow2.asm:asm` já não estava declarado em nenhum dos 5
POMs (`grep -n "org.ow2.asm"` vazio nos 5) — a rodada 1 já tinha feito essa parte, nada a remover
de novo. Trabalho real: bump de versão + docs.

1. `gbaemu/pom.xml`, `ndsemu/pom.xml`, `virtual-arm-box/pom.xml`, `n3dsemu/pom.xml`: `arm-jitter`
   `1.0.0` → `1.1.0`.
2. `armbox/pom.xml`: `arm-jitter` e `arm-jitter-truffle`, ambos `1.0.0` → `1.1.0`.
3. Docs com a versão de exemplo escrita literalmente (`README.md`/`AGENTS.md`): `gbaemu/README.md`,
   `ndsemu/README.md`+`AGENTS.md`, `armbox/README.md`, `n3dsemu/README.md`+`AGENTS.md`,
   `arm-jitter/README.md` ("Coordenadas Maven atuais"). `virtual-arm-box` não cita a versão em
   prosa, nada a mudar lá. A frase histórica do `arm-jitter/README.md` ("Desde a publicação... 1.0.0,
   task F5") foi deixada como está — é um registro do evento original, não uma instrução de versão
   atual.

**Prova de resolução do Central** (`~/.m2/repository/dev/vitorsilverio` renomeada para
`.bak` e restaurada ao final, `mvn test` com JBR 25 em cada repo, sem `-o`):

| Repo | Resultado |
|------|-----------|
| gbaemu | 240 testes, 17 skipped, BUILD SUCCESS |
| ndsemu | 183 testes, 0 skipped, BUILD SUCCESS |
| armbox | 40/41 — 1 erro em `Armv7TortureTest` (`ArrayIndexOutOfBounds` em `VfpRegisters`), a mesma
  falha pré-existente já documentada em várias tasks anteriores (B10.x, B9.x), não relacionada a
  esta mudança |
| virtual-arm-box | 87 testes, 5 skipped, BUILD SUCCESS |
| n3dsemu | 199 testes, 0 skipped, BUILD SUCCESS |

`mvn -o dependency:tree` do gbaemu (com o `.m2` já restaurado) confirma `org.ow2.asm:asm:9.7.1`
chegando transitivo do `arm-jitter`.

`mvn test` do arm-jitter em si não precisou rodar — nenhum arquivo de código tocado, só POMs e
Markdown dos consumidores + `arm-jitter/README.md`.

Commit por repo (`F7: atualiza arm-jitter [/arm-jitter-truffle] para 1.1.0 (Maven Central)`), push
feito nos 5 consumidores + no arm-jitter.

**Rodada 3 (2026-08-27)**: a `1.3.0` foi publicada no Central em 2026-08-27 (B8.11/B8.11b/B8.12 +
E7/E8) — a `F7` desta versão tinha sido adiada a pedido do usuário por causa da demora incomum de
sincronização do Central. Estado antes desta rodada: `armbox` em `1.2.0` (a `1.2.0` fora publicada
por sessão sem registro na fila, ver `tasks/README.md`); `gbaemu`/`ndsemu`/`virtual-arm-box`/
`n3dsemu` ainda em `1.1.0`. Confirmado primeiro que `1.3.0` está resolvível
(`https://repo1.maven.org/maven2/dev/vitorsilverio/arm-jitter/1.3.0/arm-jitter-1.3.0.pom` → `200`).

1. `gbaemu/pom.xml`, `ndsemu/pom.xml`, `virtual-arm-box/pom.xml`, `n3dsemu/pom.xml`: `arm-jitter`
   → `1.3.0`.
2. `armbox/pom.xml`: `arm-jitter` e `arm-jitter-truffle`, ambos → `1.3.0`.
3. Docs com a versão de exemplo escrita literalmente: `gbaemu/README.md`, `ndsemu/README.md`+
   `AGENTS.md`, `armbox/README.md`, `n3dsemu/README.md`+`AGENTS.md` corrigidas para `1.3.0`.
   `virtual-arm-box` não cita a versão em prosa (nada a mudar). `arm-jitter/README.md` já estava em
   `1.3.0` (atualizado por sessão anterior, sem retrabalho).

**Prova de resolução do Central** (`~/.m2/repository/dev/vitorsilverio` renomeada para `.bak`,
restaurada por merge ao final — a suíte baixou artefatos novos durante o teste, então a restauração
foi um merge com `robocopy` em vez de um simples `Rename-Item` de volta, para não perder nem os
artefatos antigos nem os novos):

| Repo | Resultado |
|------|-----------|
| gbaemu | 240 testes, 17 skipped, BUILD SUCCESS |
| ndsemu | 183 testes, 0 skipped, BUILD SUCCESS |
| virtual-arm-box | 87 testes, 5 skipped, BUILD SUCCESS |
| n3dsemu | 221 testes, 0 skipped, BUILD SUCCESS |
| armbox | 43/43, BUILD SUCCESS (a falha pré-existente de `Armv7TortureTest`/`VfpRegisters` não
  reproduziu nesta rodada, mesmo achado ocasional já visto em sessões anteriores) |

`mvn -o dependency:tree` do gbaemu confirma `org.ow2.asm:asm:9.7.1` chegando transitivo do
arm-jitter. Commit por repo (`F7: atualiza arm-jitter [/arm-jitter-truffle] para 1.3.0 (Maven
Central)`), push feito nos 5 consumidores.
