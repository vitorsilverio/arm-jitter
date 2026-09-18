# A8 — Otimizações de native-image: PGO, -O3, GC e tabela de startup/RSS

**Trilha:** A · **Depende de:** A7 (aceites verdes) · **Repo:** armbox
**Ambiente:** GraalVM 25 Oracle (`E:\graalvm-jdk-25.0.3+9.1`) — PGO é feature
Oracle GraalVM (não existia no CE); MSVC via `vcvars64.bat`.

## Contexto

Com o backend Truffle compilando de verdade (A6+A7), extrair o máximo do binário
nativo. Task mecânica de build+medição: cada variante é uma configuração do
`native-maven-plugin` (perfil `native` do pom do armbox), medida com o MESMO
protocolo. Nenhuma mudança de código de emulação.

## Variantes a construir e medir (uma coluna cada)

| # | Variante | Flags (`buildArgs` do plugin) |
|---|----------|-------------------------------|
| 1 | Baseline A7 | (as atuais) |
| 2 | `-O3` | `-O3` |
| 3 | March nativo | `-O3 -march=native` |
| 4 | G1 | `-O3 --gc=G1` — **se não suportado no Windows nesta versão (erro claro do native-image), registrar e pular** |
| 5 | PGO | build 1: `--pgo-instrument` → rodar workload (gera `default.iprof`) → build 2: `--pgo=default.iprof -O3` |

Workloads de medição (best-of-5 cada, tabela markdown no README do armbox):

- **Startup**: `armbox hello.elf` (tempo total do processo — dominado por boot).
- **Throughput JIT**: o loop busybox de 2000 iterações de A5/A7, `--truffle`.
- **Throughput interp**: o mesmo, `--interp` (PGO/-O3 ajudam o interpretador
  também — é o dado que interessa para hosts sem Truffle).
- **RSS máximo**: via `Get-Process` no fim (PowerShell:
  `(Get-Process -Id $pid).PeakWorkingSet64`) ou Measure-Command wrapper — fixar o
  método e documentá-lo na tabela.
- Corretude por variante: `hello.elf` + `busybox echo hi` stdout/exit corretos
  (barato, roda sempre).

Para o PGO: o profile é gerado com o workload de throughput `--truffle` + o
`hello.elf` (cobre boot e loop quente).

## Aceite

- Tabela completa (5 variantes × 4 métricas) no README do armbox, com a variante
  vencedora promovida a default do perfil `native` (commit separado).
- Nenhuma variante regride corretude.
- Registrar variantes que falharem no build (ex. G1/Windows) com a mensagem exata
  — é resultado válido, não bloqueio.

## Armadilhas

- PGO: o binário instrumentado é LENTO — não comparar tempos dele; ele só existe
  para gerar o `.iprof`.
- `-march=native` gera binário não-portável — ok para a máquina do usuário; anotar
  na tabela.
- Medir sempre com a mesma energia/plano do Windows e sem outras cargas (ruído >
  diferenças de single-digit %); best-of-5 já mitiga.

## Resultado

✅ (2026-07-31, armbox `b1411f6`) — 5 variantes medidas (baseline/`-O3`/
`-O3 -march=native`/`--gc=G1`/`--pgo=<profile> -O3`), mesma máquina/sessão, best-of-5
para startup+throughput, `PeakWorkingSet64` por polling para RSS, corretude
`hello.elf`+`busybox echo hi` nos 2 backends. `--gc=G1` falhou no build com erro claro
(`only supported on Linux AMD64 and AArch64` — resultado válido, não bloqueio,
registrado).

**PGO+`-O3` venceu (ou empatou) as 4 métricas simultaneamente** — startup 33,0ms
(empate técnico com `-march=native` 33,5ms), throughput truffle 2101ms (melhor, vs
2175ms de `-march=native`), throughput interp 1466ms (melhor, vs 1536ms), RSS 97,3MB
(ÚNICA variante abaixo do baseline 101,8MB) — promovido a default do perfil `native`
do armbox (`pom.xml`), perfil comitado em `armbox/native-profile/default.iprof` com
receita de regeneração no README. `-march=native` descartado apesar de próximo
(binário não-portável, não venceu PGO em nenhuma métrica). Tabela completa + comandos
reproduzíveis no README do armbox.

`mvn -o test` verde (arm-jitter + armbox, JBR 25); G5 não se aplica (nenhum arquivo
Java/IR tocado, só `pom.xml`/build config do armbox e README).
