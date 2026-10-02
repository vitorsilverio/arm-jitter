#!/usr/bin/env bash
# E15.5 — gera uma variante do laço (`executor_edit.py <Kind quentes...>`), empacota o jar em
# `target/e15.5/<nome>/` e roda o `GbaInterpBench` contra ele. Rodar da raiz do repo, máquina ociosa.
# Uso: variant.sh <nome> [KIND ...]
set -euo pipefail
name=$1
shift
export JAVA_HOME="C:/Users/user/.jdks/jbr-25.0.3"
export PATH="/c/Users/user/.jdks/jbr-25.0.3/bin:$PATH"
scripts=tasks/trilha-e-manutencao/e15.5-scripts
python $scripts/executor_edit.py "$@"
mvn -o -q -pl core package -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Djacoco.skip=true
mkdir -p target/e15.5/$name
cp core/target/arm-jitter-1.4.0.jar target/e15.5/$name/
sed "s#e15.5/before/#e15.5/$name/#" target/e15.5/cp-before.txt > target/e15.5/cp-$name.txt
java -cp "$(cat target/e15.5/cp-$name.txt)" $scripts/GbaInterpBench.java C:/Users/user/IdeaProjects/gbaemu/roms \
    | tee target/e15.5/bench-$name.txt
