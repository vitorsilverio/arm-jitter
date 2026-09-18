# A7 — Revalidação native-image pós-A6 (fechar o aceite #2 da A5)

**Trilha:** A · **Depende de:** A6 · **Repos:** arm-jitter (leitura) + armbox
**Pré-requisito de ambiente:** GraalVM 25 (`E:\graalvm-jdk-25.0.3+9.1`) + MSVC
(`vcvars64.bat`) — receita de build completa e já validada em
`tasks/trilha-a-truffle/RELATORIO-A5.md`.
**Fecha:** arm-jitter#3

## Contexto

A5 entregou o binário nativo do armbox, mas com **0 blocos Truffle compilados**
(bailouts de PE nos dois ambientes). A6 ataca a causa raiz (especialização de nós).
Esta task é a contra-prova: repetir EXATAMENTE os diagnósticos da A5 e registrar o
antes/depois. É uma task de medição — nenhum código novo esperado além de rebuild
(se A6 exigir ajuste, a correção pertence a A6/PR follow-up, não aqui).

## Passos (todos os comandos estão no RELATORIO-A5.md — reusar literalmente)

1. `mvn install` do arm-jitter (com A6 mergeada) + rebuild do `armbox.exe` com o
   perfil `native`.
2. **JBR 25 + Truffle Unchained**: rodar o workload de referência da A5
   (`busybox sh -c 'i=0; while [ $i -lt 2000 ]; do i=$((i+1)); done'`) com
   `-Dpolyglot.engine.TraceCompilation=true` → contar `opt done` vs `opt failed`.
3. **Binário nativo**: mesmo workload, mesma flag → mesmos contadores.
4. Tempo: `--truffle` vs `--interp` no loop de 2000 iterações (era 2,76s vs 1,77s
   — o Truffle PERDIA), nos dois ambientes, best-of-5.
5. Rodar também `hello.elf` e `busybox-armv5l` (aceite #1 da A5) para confirmar
   zero regressão de corretude (stdout/exit idênticos à JVM).

## Aceite

- `opt done > 0` nos DOIS ambientes com blocos do workload real (o aceite #2 que
  A5 não alcançou), e zero `opt failed` por bailout de PE (`FrameWithoutBoxing`/
  `tooDeepInlining` sumiram).
- `--truffle` mais rápido que `--interp` no loop de referência nos dois ambientes.
- `RELATORIO-A5.md` ganha uma seção "A7 (data): resultado pós-A6" com a tabela
  antes/depois; status da A5 no índice `tasks/README.md` promovido de 🟡 para ✅
  (com nota "fechada via A6+A7").
- Se algum aceite FALHAR: registrar números + `TraceCompilation` completo no
  relatório e parar — a análise é sessão de modelo forte (anotar isso no índice).

## Armadilhas

- Não misturar JVMs: o bench JBR usa JBR 25 + Unchained; o nativo usa GraalVM 25.
  Conferir `Truffle.getRuntime().getName()` impresso antes de cada medição (A4/A5
  já fazem isso).
- Rebuild do nativo SEM `mvn install` novo do arm-jitter = medir o jar velho.

## Resultado

🔴 medida (2026-07-27, [relatório](RELATORIO-A5.md#a7-data-resultado-pós-a6)) —
rebuild completo com A6 mergeada (`mvn -o install` JBR 25 + `armbox.exe` via GraalVM
25.0.3+MSVC) confirmado, aceite #1 (corretude `hello.elf`/`busybox-armv5l`) ✅
mantido nos dois ambientes/backends.

**Aceite #2 (TraceCompilation) misto**: JBR 25+Unchained agora COMPILA de verdade
(1812 `opt done`, 0 `opt failed` — A6 confirmada, não é artefato pontual da sessão
dela); native-image reproduz o MESMO bailout `FrameWithoutBoxing should not be
materialized` da A5 byte a byte (0 `opt done`, 903 `opt failed`) — a especialização de
nós de A6 resolveu o pipeline JVMCI mas não teve nenhum efeito sobre o pipeline
SVM/Enterprise Truffle Compiler, causa raiz sob SVM ainda sem diagnóstico.

**Aceite de ganho de tempo 🔴 falha nos DOIS ambientes** (loop de 2000 iterações,
best-of-5): native-image `--truffle` 3307ms vs `--interp` 2164ms; JBR+Unchained
`--truffle` 3713ms vs `--interp` 2508ms (JBR mais lento apesar de compilar de verdade
— wall-time de processo curto dominado pelo custo fixo de bootstrap dos módulos
Unchained, não comparável ao ganho estrutural).

**Nenhum dos dois critérios de aceite fechou nos dois ambientes simultaneamente → A5
NÃO promovida a ✅, permanece 🟡.** `mvn -o test` verde (1322 core + 13 truffle, JBR
25). Análise da causa raiz SVM e do custo de bootstrap Unchained ficam para sessão de
modelo forte dedicada (fora do escopo de medição desta task, conforme a própria task
instrui).
