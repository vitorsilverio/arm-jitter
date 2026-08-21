#!/usr/bin/env bash
# Regenera `docs/COBERTURA-ISA.md` — a tabela de cobertura de decode do arm-jitter.
#
# O INVENTÁRIO de instruções vem dos arquivos `decodetree` do QEMU. O QEMU é GPL e este
# repositório é BSD-3-Clause, então eles NÃO são versionados aqui: são baixados para
# `target/isa-decode/` (ignorado pelo git) a cada execução. O que fica versionado é só a tabela
# gerada — mnemônicos e status, fatos do manual da ARM.
#
# Uso:  ./gerar-cobertura-isa.sh
set -euo pipefail

DECODE_DIR="target/isa-decode"
BASE_URL="https://raw.githubusercontent.com/qemu/qemu/master/target/arm/tcg"
FILES="a32.decode t32.decode t16.decode a64.decode vfp.decode vfp-uncond.decode
       neon-dp.decode neon-ls.decode neon-shared.decode m-nocp.decode mve.decode
       sme.decode sve.decode"

mkdir -p "$DECODE_DIR"
for file in $FILES; do
    if [ ! -f "$DECODE_DIR/$file" ]; then
        echo "baixando $file"
        curl -sfL "$BASE_URL/$file" -o "$DECODE_DIR/$file"
    fi
done

mvn -o -q -pl core test-compile
java -cp "core/target/classes;core/target/test-classes" \
     dev.vitorsilverio.armjitter.tools.IsaCoverageReport "$DECODE_DIR" docs/COBERTURA-ISA.md
