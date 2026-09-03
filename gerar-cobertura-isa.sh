#!/usr/bin/env bash
# Regenera `docs/COBERTURA-ISA.md` — a tabela de cobertura de decode do arm-jitter.
#
# O INVENTÁRIO de instruções vem dos arquivos `decodetree` do QEMU. O QEMU é GPL e este
# repositório é BSD-3-Clause, então eles NÃO são versionados aqui: são baixados para
# `target/isa-decode/` (ignorado pelo git). O que fica versionado é só a tabela gerada —
# mnemônicos e status, fatos do manual da ARM.
#
# ── REVISÃO FIXADA (E11) ────────────────────────────────────────────────────────────────────
# `QEMU_REV` abaixo é um **SHA de commit** do QEMU, nunca um branch. Antes da E11 o script
# baixava de `master` e só baixava o arquivo que faltasse — então o resultado de
# `./gerar-cobertura-isa.sh` dependia de QUANDO cada máquina baixou os `.decode` pela primeira
# vez (o QEMU cresce, o `master` é alvo móvel, `target/` é gitignored). Fixar a revisão torna a
# tabela reproduzível: qualquer máquina, a qualquer momento, gera `docs/COBERTURA-ISA.md`
# byte a byte igual a partir do mesmo commit do arm-jitter.
#
# O cache local não pode mais esconder a divergência: a revisão baixada fica gravada em
# `target/isa-decode/.rev`; se não bater com `QEMU_REV`, o script apaga e rebaixa TODOS os
# `.decode` antes de medir.
#
# ── COMO BUMPAR `QEMU_REV` (rito deliberado, nunca automático) ──────────────────────────────
#   1. troque `QEMU_REV` por um SHA novo, escolhido de propósito;
#   2. rode `./gerar-cobertura-isa.sh`;
#   3. leia o diff de `docs/COBERTURA-ISA.md` LINHA A LINHA — cada `❌` novo é uma instrução
#      que o ARM ganhou e o arm-jitter ainda não tem: vira task, NUNCA vira exclusão sem fonte
#      em `docs/isa-nao-aplicavel.tsv` (regra máxima do `tasks/README.md`);
#   4. commit separado, só com o bump de `QEMU_REV` e a tabela regenerada.
#
# Uso:  ./gerar-cobertura-isa.sh
set -euo pipefail

# QEMU commit `2931a675e9d3fcddedf673509fe9759955fc616d` (2026-08-21, "target/arm: Make Thumb
# T1 hint space UNDEF before v6T2"). Escolhido pela E11 (2026-09-03) como primeira revisão
# fixada — é o commit que introduz `MAYBE_UNDEF_T1_HINT` em `t16.decode`, a única linha nova
# desta janela com efeito semântico. Bumpar seguindo o rito acima.
QEMU_REV="2931a675e9d3fcddedf673509fe9759955fc616d"

DECODE_DIR="target/isa-decode"
REV_FILE="$DECODE_DIR/.rev"
BASE_URL="https://raw.githubusercontent.com/qemu/qemu/${QEMU_REV}/target/arm/tcg"
FILES="a32.decode t32.decode t16.decode a64.decode vfp.decode vfp-uncond.decode
       neon-dp.decode neon-ls.decode neon-shared.decode m-nocp.decode mve.decode
       sme.decode sve.decode"

mkdir -p "$DECODE_DIR"

cached_rev=""
[ -f "$REV_FILE" ] && cached_rev="$(cat "$REV_FILE")"

if [ "$cached_rev" != "$QEMU_REV" ]; then
    if [ -n "$cached_rev" ]; then
        echo "cache do inventário é da revisão $cached_rev, esperada $QEMU_REV — rebaixando tudo"
    fi
    rm -f "$DECODE_DIR"/*.decode
fi

for file in $FILES; do
    if [ ! -f "$DECODE_DIR/$file" ]; then
        echo "baixando $file"
        curl -sfL "$BASE_URL/$file" -o "$DECODE_DIR/$file"
    fi
done
echo "$QEMU_REV" > "$REV_FILE"

mvn -o -q -pl core test-compile
java -cp "core/target/classes;core/target/test-classes" \
     dev.vitorsilverio.armjitter.tools.IsaCoverageReport "$DECODE_DIR" docs/COBERTURA-ISA.md docs/isa-nao-aplicavel.tsv "$QEMU_REV"
