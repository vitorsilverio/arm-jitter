#!/usr/bin/env bash
# E15.15a — passa pelo `objdump` do devkitA64 as palavras de um preset do oráculo, aceitas ou recusadas.
# Generaliza o `e15.9b-scripts/objdump-residuo.sh` (que só olhava as aceitas do preset `all`).
#
# Uso: ./objdump-oraculo.sh <saida-do-oraculo.txt> <preset> <aceitas|recusadas> <dir-de-trabalho>
# Saída: <dir>/<preset>-<modo>.txt — "<palavra> <resultado do decoder> <texto do objdump>" por linha.
# As aceitas que o objdump chama de `undefined`/`.inst` são G8; as recusadas que ele decodifica são lacuna.
set -euo pipefail

ORACLE_OUT="$1"
PRESET="$2"
MODE="$3"
WORK="$4"
BIN="/c/devkitPro/devkitA64/bin"
mkdir -p "$WORK"
BASE="$WORK/$PRESET-$MODE"

if [ "$MODE" = "aceitas" ]; then
    grep "^$PRESET " "$ORACLE_OUT" | tr -d '\r' | awk '$3 !~ /Exception$/ {split($3, a, "["); print $2, a[1]}' > "$BASE.in"
else
    grep "^$PRESET " "$ORACLE_OUT" | tr -d '\r' | awk '$3 ~ /Exception$/ {print $2, $3}' > "$BASE.in"
fi
{ echo ".text"; awk '{print ".inst 0x"$1}' "$BASE.in"; } > "$BASE.s"
"$BIN/aarch64-none-elf-as.exe" "$BASE.s" -o "$BASE.o"
"$BIN/aarch64-none-elf-objdump.exe" -d -M no-aliases "$BASE.o" | grep -E '^\s+[0-9a-f]+:' \
        | awk -F'\t' '{print $3, $4}' > "$BASE.objdump"
paste -d' ' "$BASE.in" "$BASE.objdump" > "$BASE.txt"
rm -f "$BASE.s" "$BASE.o" "$BASE.in" "$BASE.objdump"
echo "$PRESET $MODE: $(wc -l < "$BASE.txt") palavras · undefined/.inst no objdump: $(grep -cE 'undefined|\.inst' "$BASE.txt" || true)"
