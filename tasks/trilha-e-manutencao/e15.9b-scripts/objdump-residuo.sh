#!/usr/bin/env bash
# E15.9b — resíduo de misdecode: palavras que o `Aarch64Decoder` ACEITA e o `objdump` do devkitA64
# chama de `undefined`. Entrada = saída do `AdvSimdPrefixOracle.java` (uma linha
# "<preset> <palavra> <resultado>"); mede só o preset `all` (todas as features menos SME).
#
# Uso: ./objdump-residuo.sh <saida-do-oraculo.txt> <dir-de-trabalho>
# Saída: <dir>/residuo.txt (contagem por prefixo/bit30/bit21/classe) e <dir>/residuo-palavras.txt.
set -euo pipefail

ORACLE_OUT="$1"
WORK="$2"
BIN="/c/devkitPro/devkitA64/bin"
mkdir -p "$WORK"

grep '^all ' "$ORACLE_OUT" | awk '$3 !~ /Exception/ {split($3, a, "["); print $2, a[1]}' > "$WORK/aceitas.txt"
{ echo ".text"; awk '{print ".inst 0x"$1}' "$WORK/aceitas.txt"; } > "$WORK/aceitas.s"
"$BIN/aarch64-none-elf-as.exe" "$WORK/aceitas.s" -o "$WORK/aceitas.o"
"$BIN/aarch64-none-elf-objdump.exe" -d "$WORK/aceitas.o" | grep -E '^\s+[0-9a-f]+:' \
        | awk -F'\t' '{print $3}' > "$WORK/objdump.txt"
paste -d' ' "$WORK/aceitas.txt" "$WORK/objdump.txt" | grep -E 'undefined|\.inst' > "$WORK/residuo-palavras.txt" || true
awk '{w = strtonum("0x"$1);
      printf "b31=%d p%d b30=%d b21=%d %s\n", and(rshift(w,31),1), and(rshift(w,24),31),
             and(rshift(w,30),1), and(rshift(w,21),1), $2}' "$WORK/residuo-palavras.txt" \
        | sort | uniq -c | sort -rn > "$WORK/residuo.txt"
echo "aceitas: $(wc -l < "$WORK/aceitas.txt") · undefined no objdump: $(wc -l < "$WORK/residuo-palavras.txt")"
