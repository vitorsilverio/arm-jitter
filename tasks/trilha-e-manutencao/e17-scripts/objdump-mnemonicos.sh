#!/usr/bin/env bash
# E17 item 1 — acrescenta o mnemônico do `objdump` do devkitA64 a cada linha da saída do
# `A64SignatureProbe.java` (coluna 3 = palavra). Mesma técnica de `e15.9b-scripts/objdump-residuo.sh`.
#
# Uso: ./objdump-mnemonicos.sh <assinaturas.tsv> <dir-de-trabalho>
# Saída: <dir>/cruzado.tsv = as colunas da entrada + mnemônico + texto completo do objdump.
set -euo pipefail

IN="$1"
WORK="$2"
BIN="/c/devkitPro/devkitA64/bin"
mkdir -p "$WORK"

tr -d '\r' < "$IN" | cut -f3 | sort -u > "$WORK/palavras.txt"
{ echo ".text"; awk '{print ".inst 0x"$1}' "$WORK/palavras.txt"; } > "$WORK/palavras.s"
"$BIN/aarch64-none-elf-as.exe" "$WORK/palavras.s" -o "$WORK/palavras.o"
"$BIN/aarch64-none-elf-objdump.exe" -d "$WORK/palavras.o" | tr -d '\r' | grep -E '^\s+[0-9a-f]+:' \
        | awk -F'\t' '{print $3 "\t" $4}' > "$WORK/objdump.txt"
paste "$WORK/palavras.txt" "$WORK/objdump.txt" > "$WORK/palavra-mnemonico.tsv"
awk -F'\t' 'NR == FNR {mn[$1] = $2; txt[$1] = $2 " " $3; next}
            {print $0 "\t" mn[$3] "\t" txt[$3]}' "$WORK/palavra-mnemonico.tsv" <(tr -d '\r' < "$IN") \
        > "$WORK/cruzado.tsv"
echo "linhas: $(wc -l < "$WORK/cruzado.tsv") · palavras distintas: $(wc -l < "$WORK/palavras.txt")"
