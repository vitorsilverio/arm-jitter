# Manifesto de issues do backlog conhecido

Corpos redigidos na sessão de planejamento de **2026-08-15**, a partir do que já estava
documentado (memória do projeto, `FILA-EXECUCAO.md`, índice do `tasks/README.md`, READMEs).
A task **F9** posta cada um como issue e preenche a coluna **Issue**.

**Não reescreva os corpos ao postar.** Se um estiver factualmente errado, corrija o arquivo
em commit próprio antes de postar e diga o que mudou.

## Como postar

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" issue create `
  --repo vitorsilverio/<repo> `
  --title "<título desta tabela>" `
  --body-file tasks/issues/<repo>/<arquivo> `
  --label bug --label compat `
  --milestone "<milestone>"
```

O `gh` está instalado e autenticado (conta `vitorsilverio`, escopos `repo`, `read:org`,
`gist`, `admin:public_key`), mas **não está no PATH** — use o caminho completo acima, ou
acrescente `C:\Program Files\GitHub CLI` ao PATH antes.

## Tabela

| # | Repo | Arquivo | Título | Labels | Milestone | Task relacionada | Issue |
|---|------|---------|--------|--------|-----------|------------------|-------|
| 1 | gbaemu | `gbaemu/01-firered-battle-glitches.md` | FireRed: 3 glitches visuais nas batalhas | `bug`, `compat`, `gpu` | Fidelidade | `trilha-d-compat/d2-pokemon-battle-glitch-interpreted.md` | gbaemu#1 |
| 2 | gbaemu | `gbaemu/02-smw-audio-crackle.md` | Super Mario Advance 2: chiado no áudio | `bug`, `audio`, `compat` | Fidelidade | `trilha-d-compat/d3-smw-audio-crackle.md` | gbaemu#2 |
| 3 | gbaemu | `gbaemu/03-metroid-audio-melodia.md` | Metroid Fusion: melodia errada (Direct Sound/FIFO) | `bug`, `audio`, `compat` | Fidelidade | `trilha-d-compat/d4-metroid-audio-channel-accelerated.md` | gbaemu#3 |
| 4 | gbaemu | `gbaemu/04-bios-animacao-lenta.md` | Animação de boot da BIOS roda lenta demais | `bug`, `needs-design` | Fidelidade | `trilha-d-compat/d6-gbaemu-bios-lenta.md` | gbaemu#4 |
| 5 | gbaemu | `gbaemu/05-firered-recorte-afim-intro.md` | FireRed: recorte afim nos pés do rival na intro | `bug`, `gpu` | — | — | gbaemu#5 |
| 6 | ndsemu | `ndsemu/01-jus-panic-apos-logos.md` | JUS: ARM9 entra em panic do SDK depois dos logos | `bug`, `compat` | Compatibilidade | — | ndsemu#1 |
| 7 | ndsemu | `ndsemu/02-platinum-missingno-indoor.md` | Platinum: personagem corrompido em ambientes internos | `bug`, `compat`, `gpu` | Compatibilidade | — | ndsemu#2 |
| 8 | ndsemu | `ndsemu/03-mkds-item-box.md` | MKDS: caixa de item incorreta | `bug`, `compat`, `gpu` | Compatibilidade | — | ndsemu#3 |
| 9 | ndsemu | `ndsemu/04-nao-boota-em-interpretado.md` | Não boota com o backend INTERPRETED | `bug`, `needs-design` | — | — | ndsemu#4 |
| 10 | ndsemu | `ndsemu/05-savestate-perde-luz-3d.md` | Savestate perde o estado de iluminação 3D | `bug` | — | — | ndsemu#5 |
| 11 | ndsemu | `ndsemu/06-gui-backlog.md` | GUI: ROMs recentes, editor de firmware, gamepad | `feature` | — | — | ndsemu#6 |
| 12 | ndsemu | `ndsemu/07-lentidao-warmup-jit.md` | Lentidão até o JIT aquecer | `perf`, `blocked:user` | — | `trilha-c-perf/c10-jit-warmstart-ndsemu.md` | ndsemu#7 |
| 13 | arm-jitter | `arm-jitter/01-ir64-alu-sp-por-flag.md` | AArch64: `ADD`/`SUB` imediato resolve SP por flag, não por índice | `bug`, `jit` | 1.1 | — | arm-jitter#1 |
| 14 | arm-jitter | `arm-jitter/02-divergencia-asm-interp-jus.md` | Divergência ASM×interpretado no JUS (`r1` em `block@0x1ff8f44`) | `bug`, `jit`, `needs-design` | 1.1 | — | arm-jitter#2 |
| 15 | arm-jitter | `arm-jitter/03-truffle-native-image-bailout.md` | Backend Truffle sofre bailout de PE sob native-image | `bug`, `jit`, `needs-design` | — | `trilha-a-truffle/a7-native-image-revalidacao.md` | arm-jitter#3 |
| 16 | arm-jitter | `arm-jitter/04-idle-loop-skip.md` | Idle-loop skip: precisa de RFC antes de virar task | `perf`, `needs-design` | — | — | arm-jitter#4 |
| 17 | arm-jitter | `arm-jitter/05-dispatch-megamorfico-ndsemu.md` | Dispatch megamórfico remanescente (~12–14% do perfil) | `perf`, `jit`, `needs-design` | — | — | arm-jitter#5 |
| 18 | armbox | `armbox/01-corpus-busybox-thumb2.md` | Falta corpus busybox Thumb-2 (toolchain `arm-linux-*`) | `blocked:asset`, `infra` | — | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | armbox#1 |
| 19 | armbox | `armbox/02-corpus-busybox-aarch64.md` | Falta corpus busybox AArch64 | `blocked:asset`, `infra` | — | `trilha-b-arquiteturas/b6.2-*` | armbox#2 |
| 20 | armbox | `armbox/03-fase3-fork-pipes.md` | Fase 3: `fork`/`execve`/`pipe`/`wait` | `feature`, `blocked:asset` | — | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox#3 |
| 21 | virtual-arm-box | `virtual-arm-box/01-desvio-arm926-vs-arm1176-dt.md` | Desvio: ARM926/ARMv5TE + ATAGs em vez de ARM1176/ARMv6K + Device Tree | `infra`, `blocked:asset` | — | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box#1 |

## Referências cruzadas (resolvidas)

| Placeholder | Onde aparece | Resolvido para |
|-------------|--------------|------------------------|
| `#TBD-firered-battle-glitches` | `gbaemu/02-smw-audio-crackle.md` | `#1` (gbaemu#1), corpo reeditado via `gh issue edit 2` |
| `#TBD-corpus-busybox-thumb2` | `armbox/03-fase3-fork-pipes.md` | `#1` (armbox#1), corpo já correto na criação (issue 20 postada depois de 18) |
