# A1 — Build multi-módulo Maven

**Trilha:** A · **Depende de:** — (pode rodar em paralelo com A0) · **Repo:** arm-jitter

## Contexto

O backend Truffle trará dependências pesadas (truffle-api, runtime Graal) que NÃO podem
entrar no classpath do core — hoje o core depende só de `org.ow2.asm:asm`. A solução é
multi-módulo Maven com o Truffle em módulo opcional.

## Objetivo

Reestruturar o build em parent + 2 módulos **sem alterar as coordenadas Maven que os
consumidores usam**.

## Especificação

Estrutura final:

```
arm-jitter/                  (raiz do repo)
  pom.xml                    → groupId dev.vitorsilverio, artifactId arm-jitter-parent,
                               version 1.0, packaging pom, <modules>core, truffle</modules>
  core/
    pom.xml                  → artifactId arm-jitter  ← CRÍTICO: MESMO artifactId de hoje
    src/...                  → todo o src/ atual movido para cá (git mv, preservar histórico)
  truffle/
    pom.xml                  → artifactId arm-jitter-truffle, depende de arm-jitter
    src/main/java/dev/vitorsilverio/armjitter/truffle/   (vazio nesta task — só um
                               package-info.java com javadoc do propósito)
```

Regras:

- Config compartilhada (Java 25, encoding, plugins, versão do surefire) sobe para o
  parent via `<pluginManagement>`/`<properties>`; o `core/pom.xml` fica mínimo.
- A dependência `org.ow2.asm:asm` fica SÓ no core. Nenhuma dependência Truffle é
  adicionada nesta task (isso é A2).
- `AGENTS.md`, `README.md`, `ROADMAP.md`, `ARQUITETURA.html` e `tasks/` permanecem na
  raiz do repo. Se algum deles citar caminhos `src/main/java/...`, atualizar para
  `core/src/main/java/...`.

## Não fazer

- NÃO renomear pacotes Java nem mover classes entre pacotes.
- NÃO mudar groupId/artifactId/version do artefato `arm-jitter`.
- NÃO adicionar dependências novas.

## Aceite

1. `mvn install` na raiz publica `dev.vitorsilverio:arm-jitter:1.0` (idêntico a antes)
   e `dev.vitorsilverio:arm-jitter-truffle:1.0` no repositório local.
2. Todos os testes existentes passam (eles se movem junto para `core/`).
3. gbaemu e ndsemu compilam e passam testes **sem nenhuma alteração nos poms deles**
   (peça ao usuário para rodar se não puder).

## Armadilhas

- Usar `git mv` para o histórico seguir os arquivos.
- O jar final do core deve ter o MESMO conteúdo de antes — compare `jar tf` antes/depois.
- Plugins declarados hoje no pom único (ex.: surefire, javadoc) precisam continuar
  valendo para o core após a mudança; rode `mvn -pl core test` para conferir.
