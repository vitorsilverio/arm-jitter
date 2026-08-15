#!/usr/bin/env bash
# Cria (ou atualiza, via --force) o conjunto único de labels nos 4 repos.
# Uso: ./criar-labels.sh
# Requer: gh autenticado. No Windows, gh não está no PATH por padrão —
# ajuste GH abaixo ou exporte PATH antes de rodar.
set -euo pipefail

GH="${GH:-gh}"
OWNER="vitorsilverio"
REPOS=(arm-jitter armbox gbaemu ndsemu virtual-arm-box)

declare -A LABELS=(
  [bug]="d73a4a|comportamento errado observável"
  [perf]="fbca04|lento, mas correto"
  [compat]="0e8a16|jogo/binário específico que não funciona"
  [feature]="a2eeef|pedido novo"
  [infra]="c5def5|build, CI, release, licença, documentação"
  [needs-design]="d4c5f9|precisa de RFC/sessão de modelo forte antes de virar task"
  [blocked:asset]="e99695|bloqueado em ROM/BIOS/kernel/toolchain que não temos"
  [blocked:user]="e99695|precisa de decisão ou validação humana"
  [jit]="bfd4f2|toca o pipeline de compilação (só arm-jitter)"
  [gpu]="bfd4f2|vídeo/rasterização (gbaemu, ndsemu, n3dsemu)"
  [audio]="bfd4f2|som"
)

for repo in "${REPOS[@]}"; do
  echo "== $repo =="
  for name in "${!LABELS[@]}"; do
    IFS='|' read -r color desc <<< "${LABELS[$name]}"
    "$GH" label create "$name" --repo "$OWNER/$repo" --color "$color" --description "$desc" --force
  done
done
