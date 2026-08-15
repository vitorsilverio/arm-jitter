# F4 — Definir e preparar o arm-jitter **1.0.0** (o "ponto bom" para publicar)

**Trilha:** F (infra) · **Depende de:** F1 · **Repo:** arm-jitter
**Esta task NÃO publica nada** (isso é F5/F6). Ela define o escopo do 1.0.0 e deixa o repo
em condições de ser publicado.

## Contexto

O usuário pediu: *"definir um ponto bom para arm-jitter ter sua versão 1.0 publicada no
maven"*. A resposta desta task é: **o ponto bom é agora**, e o motivo é objetivo — os épicos
que definem a identidade da biblioteca já fecharam:

| Épico | Estado |
|-------|--------|
| Pipeline `cache → decode → lift IR → otimizar → emit`, backends INTERPRETED_IR + JVM_BYTECODE (ASM) + TRUFFLE | ✅ produção |
| B1 (ARMv6K), B2 (Thumb-2), B3 (ARMv7-A + VFPv2), B5 (ARM11 MPCore), B7 (perfil M completo) | ✅ |
| B4.1 (MMU/softmmu 32-bit) — page-walk, CP15 VMSA, aborts precisos nos 3 motores | ✅ (fechado por B4.1.5 em 2026-08-14: kernel Linux real até shell) |
| B6.1–B6.6.5 (AArch64: decoder, core EL0/EL1, MMU v8, backend ASM `jit64`) | ✅ |
| Consumidores em produção: gbaemu (5 jogos jogáveis), ndsemu (JUS ~99% realtime) | ✅ |
| Trilha A: `armbox` sob native-image com PGO+O3; `arm_jitter.dll` com API C (`capi/`) | ✅ |

O que **falta** não bloqueia um 1.0.0 — é hospedeiro, não biblioteca: B6.6.6 (host `virt64`)
e A9 PR2 (Truffle dentro de native-image) dependem de assets/ambiente externos, e B4.0.5 é
do armbox. Nenhum deles muda a API pública do `arm-jitter`.

**Portanto:** 1.0.0 = "as arquiteturas ARM de 32 bits estão completas e validadas com
binários reais; AArch64 está funcional mas o host full-system ainda não fechou". Isso é
publicável, e o versionamento semântico cuida do resto.

## Objetivo

O repo arm-jitter fica pronto para ser publicado: versão `1.0.0`, política de versionamento
escrita, documentação sem afirmação desatualizada, e a decisão de quais módulos vão ao
Central registrada em código (POM).

## Inclui

1. Bump `1.0` → `1.0.0` no POM pai e nas referências de versão.
2. `maven.deploy.skip` no módulo `capi/`.
3. `CHANGELOG.md` novo com a entrada do 1.0.0.
4. Seção `## Versionamento` no `README.md` (política semver + o que é API pública).
5. Correção das afirmações desatualizadas do `README.md`/`ROADMAP.md`.

## NÃO inclui (não fazer)

- **Não publicar** (F5), **não criar workflow** (F6), **não mexer nos consumidores** (F7).
- **Não mudar nenhuma assinatura pública, nome de classe ou comportamento default.** Se você
  achar uma API feia, **não conserte agora** — anote como issue (task F9) e deixe para um
  2.0. Publicar uma API imperfeita é reversível; publicar e depois quebrar não é.
- Não adicionar `module-info.java` (JPMS). Fica para depois; não é requisito do Central.
- Não mexer no `groupId`: continua **`dev.vitorsilverio`** (o usuário é dono do domínio
  `vitorsilverio.dev` e vai verificá-lo por DNS na F5).

## Especificação

### 1. Versão `1.0` → `1.0.0`

O Central aceita `1.0`, mas semver de três componentes é o que os consumidores esperam e o
que permite `1.0.1` de correção sem parecer uma versão menor nova.

Arquivos: `pom.xml` (pai — `<version>` e o `<parent><version>` referenciado por `core/`,
`truffle/`, `capi/`). Rodar `mvn -o -N versions:set -DnewVersion=1.0.0` **não** é
confiável aqui (offline, e o plugin pode não estar em cache) — edite os 4 POMs à mão e
confira com `grep -rn "1\.0<" --include=pom.xml .`.

Os consumidores (`gbaemu`, `ndsemu`, `armbox`, `virtual-arm-box`) declaram
`<version>1.0</version>` e vão **quebrar** ao rodar `mvn install` no arm-jitter depois deste
bump. Isso é esperado e é a task **F7** que os migra. Deixe registrado no fim da sessão:
"arm-jitter agora é 1.0.0; consumidores só voltam a compilar depois da F7."

### 2. `capi/` fora do Central

O módulo `capi` produz uma biblioteca compartilhada nativa (`.dll`/`.so`) por
`native-image` — um `.jar` dele no Maven não serve para ninguém. Acrescentar em
`capi/pom.xml`, dentro de `<properties>`:

```xml
        <!-- Este módulo produz uma biblioteca NATIVA (.dll/.so) via native-image; o jar
             correspondente não tem uso como dependência Maven. Publicamos só `arm-jitter`
             (core) e `arm-jitter-truffle` no Central — ver task F5. -->
        <maven.deploy.skip>true</maven.deploy.skip>
```

Módulos publicados: `arm-jitter-parent` (POM), `arm-jitter` (core), `arm-jitter-truffle`.

### 3. `CHANGELOG.md` (arquivo novo, raiz)

Formato *Keep a Changelog*, em português. A entrada do 1.0.0 é um **resumo por capacidade**,
não a lista de tasks — quem lê o changelog não conhece os IDs internos:

```markdown
# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/);
o projeto segue [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [1.0.0] — 2026-XX-XX

Primeira versão publicada. Consolida o que já estava em produção nos emuladores
`gbaemu` e `ndsemu`.

### Adicionado
- Pipeline `cache → decode → lift IR → otimizar → emit` com três backends: ...
- Arquiteturas guest de 32 bits: ARMv4T, ARMv5TE, ARMv6K, Thumb-2, ARMv7-A + VFPv2,
  ARM11 MPCore, perfil M (ARMv6-M/ARMv7-M) ...
- MMU/softmmu de 32 bits: ...
- AArch64: ...
- Depuração: `GdbServer`, trace listener, runtime de divergência, harness de equivalência.

### Conhecido / fora de escopo desta versão
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`).
- Backend Truffle sob `native-image` ainda não compila blocos (`A7`/`A9 PR2`).
- Sem NEON/SIMD avançado; sem virtualização (EL2), TrustZone (EL3) ou LPAE.
```

Preencher as reticências a partir da seção "Onde estamos" do `ROADMAP.md` e da tabela
"Arquiteturas e features" do `README.md`, que já têm o conteúdo correto. A data é a do dia
em que a task for executada.

### 4. Política de versionamento no `README.md`

Seção nova `## Versionamento`, antes de `## Licença`:

```markdown
## Versionamento

[Versionamento Semântico](https://semver.org). O que conta como **API pública** (e portanto
só quebra em uma versão MAIOR):

- Os pacotes `arch`, `core`, `core64`, `memory`, `jit`, `codegen`, `coprocessor`, `swi`,
  `debug` e `ir`/`ir64` — tipos e assinaturas públicas.
- O comportamento padrão das factories de `JitRuntimeFactory`.

**Não** é API pública (pode mudar em versão MENOR): os pacotes `*.internal`, detalhes de
emissão de bytecode, a forma exata da IR otimizada, e o módulo `arm-jitter-truffle`, que é
experimental enquanto o backend Truffle não fechar sob `native-image`.
```

### 5. Correções de documentação (obrigatórias — hoje o README mente)

- `README.md`, linha da tabela **"MMU / full-system 32-bit"**: diz que o `linuxbox` "trava
  num `PREFETCH_ABORT` recursivo perto da página de vetores altos antes de alcançar shell".
  **Está desatualizado** — B4.1.5 fechou em 2026-08-14 e o kernel real chega ao shell
  interativo nos dois backends. Trocar por ✅, citando `virtual-arm-box` (o nome já vem
  renomeado pela F2, que roda antes desta na fila).
- `ROADMAP.md`, seção "Onde estamos": mesmo ajuste na linha do épico B4.1, e atualizar a data
  do cabeçalho (hoje "2026-07-31").
- Varrer `README.md`/`ROADMAP.md` por outras frases com 🟡 que já viraram ✅ segundo o índice
  do `tasks/README.md`. **Regra:** o índice do `tasks/README.md` é a fonte da verdade; onde
  README/ROADMAP divergirem dele, o índice vence.

## Passos

1. Bump de versão nos 4 POMs; `grep` de confirmação.
2. `maven.deploy.skip` no `capi/pom.xml`.
3. `CHANGELOG.md`.
4. Seções `## Versionamento` no README.
5. Correções de documentação (item 5).
6. `mvn -o test` + `mvn -o install` no arm-jitter.
7. Commit `F4: prepara a versão 1.0.0 (changelog, política de versionamento, docs)`.

## Aceite

- [ ] `grep -rn "<version>1.0</version>" --include=pom.xml .` no arm-jitter não retorna nada.
- [ ] `mvn -o install` instala `dev.vitorsilverio:arm-jitter:1.0.0` no `~/.m2`.
- [ ] `capi/pom.xml` tem `maven.deploy.skip=true`.
- [ ] `CHANGELOG.md` existe com a entrada 1.0.0 preenchida (sem reticências sobrando).
- [ ] README tem `## Versionamento`; a linha de MMU/full-system está ✅ e cita
      `virtual-arm-box`.
- [ ] `mvn -o test` verde (core + truffle).
- [ ] Índice do `tasks/README.md` atualizado (F4 ✅) **com o aviso** de que
      gbaemu/ndsemu/armbox/virtual-arm-box ficam quebrados até a F7.

## Validação

`mvn -o test` + `mvn -o install` no arm-jitter, com JBR 25.
**G5 fica suspenso nesta task**, por desenho: os consumidores não compilam entre F4 e F7
(pedem `arm-jitter:1.0`, que deixou de ser produzido). Isso é esperado — não "conserte"
os consumidores aqui, é a F7. Se precisar rodar algo deles no meio do caminho, o
`arm-jitter:1.0` antigo continua no `~/.m2` até alguém apagá-lo.

## Armadilhas

- **F4 e F7 deveriam rodar próximas no tempo**, para a janela de "consumidores quebrados"
  ser curta. Se você é o agente executor e vê que a F7 não está agendada, avise o usuário ao
  fechar a sessão.
- Não apague `~/.m2/repository/dev/vitorsilverio/arm-jitter/1.0/` — é a rede de segurança
  enquanto a F7 não roda.
- Não mudar o `artifactId` `arm-jitter` (core). Ele já está certo e é o nome que os
  consumidores usam.
- Ao mexer no README/ROADMAP, **não invente estado**. Se uma linha estiver ambígua, cheque no
  índice do `tasks/README.md` e, na dúvida, deixe como está e reporte.
