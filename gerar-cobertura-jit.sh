#!/usr/bin/env bash
# Regenera `docs/COBERTURA-JIT.md` — a tabela de cobertura de EMISSÃO NATIVA do arm-jitter
# (dimensão 2 do `tasks/ROADMAP-100-ARM.md`), mais a coluna Truffle (dimensão 3).
#
# Diferente de `./gerar-cobertura-isa.sh`, NÃO baixa nada: a fonte é o próprio código
# (`AsmNativePolicy`, `Ir64NativePolicy`, `IrOpNodeFactory`). A ferramenta instancia cada
# `record` de IR por reflexão e pergunta a cada política se aceita emitir a op.
#
# A ferramenta vive no módulo `truffle/` (e não em `core/`, como o `IsaCoverageReport`) porque
# precisa de `IrOpNodeFactory`, que é package-private em `arm-jitter-truffle` — `core` não pode
# depender de `truffle`. Roda via `exec:java` para o Maven montar o classpath (inclui truffle-api).
#
# Uso:  JAVA_HOME=<JBR 25> ./gerar-cobertura-jit.sh
set -euo pipefail

mvn -o -q -pl core,truffle -am test-compile
mvn -o -q -pl truffle exec:java \
    -Dexec.mainClass=dev.vitorsilverio.armjitter.truffle.JitCoverageReport \
    -Dexec.classpathScope=test \
    -Dexec.args=docs/COBERTURA-JIT.md
