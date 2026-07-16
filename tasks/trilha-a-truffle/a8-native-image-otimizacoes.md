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
