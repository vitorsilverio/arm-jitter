# Fila de execução — para agentes com contexto limitado

**Este arquivo é enxuto de propósito** (limpo em 2026-08-28 — estava com 1113 linhas de narrativa
histórica, causando sessões novas perderem tempo tentando pegar tasks já fechadas). Status de cada
task vive no `INDICE.md` da trilha correspondente (`tasks/README.md` tem a tabela de trilhas); o
histórico narrativo completo (o que foi feito, achados, decisões) vive na própria task, seção
`## Resultado`, ou em `tasks/FILA-HISTORICO.md` para sessões antigas sem task própria. **Antes de
pegar qualquer coisa, confira o `INDICE.md` da trilha — não confie em texto solto sobre "o que falta"
sem checar o status real ali.**

## Regras de sessão (obrigatórias)

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a próxima task na
   mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes G1-G8), depois o
   `INDICE.md` da trilha (confirma que a task está ⬜/não pega uma já ✅), depois SÓ o arquivo da
   task + os fontes que ela cita. Não explorar o repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao usuário.
4. Nunca pegar itens de "Pendências que EXIGEM modelo forte" (`tasks/README.md`) nem da seção
   "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25) + G5 nos consumidores relevantes,
   status atualizado no `INDICE.md` da trilha, seção `## Resultado` na própria task, 1 commit
   começando com o ID (`B11.x: ...`), `git push`.
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale só entre repos
   DIFERENTES. Commits sempre com paths explícitos (`git add <arquivos da SUA task>`), nunca
   `git add -A`.
7. **Ao fechar uma task, não reescreva a narrativa aqui** — só atualize o `INDICE.md` da trilha (o
   `## Resultado` da própria task já é o histórico). Este arquivo só muda quando o estado descrito
   abaixo ("Onde estamos") muda de verdade.
8. **"Onde estamos" é UMA seção só — SUBSTITUA o conteúdo dela, nunca empilhe outra `## Onde
   estamos` acima/abaixo.** A narrativa completa (achados, decisões, números) já vive no `##
   Resultado` da task fechada — aqui entra só o ponteiro mínimo: task(s) fechada(s) nesta rodada (1
   linha) + "Pegáveis a seguir". Se ao editar você notar mais de uma `## Onde estamos (atualizado 2026-09-25, B22.8 fechada)

**`B22.8` fechada nesta rodada** — `SB`/`CRC32*` NÃO existem no perfil M (ARM DDI 0553B.y, `ID_ISAR5` só tem
`PACBTI`): 7 células `❌`→`·`, coluna `ARMv8.1-M+MVE` **100%** (735/735), global 23525/23776. Ver **Resultado** na task.

**Pegáveis a seguir** (specs já escritas, dependências satisfeitas): **`B22.9`** (a escrever: `SB` T32 em
`v8-A/32`/`v8.6-A/32` + `VMOV_half`/`VMOV_to_gp` 8/16 + curadoria `LDA`/`STL`/`SMC`/`HVC`/`ERET`/`CLRM`/`CRC32`/`SB`
em colunas ARMv7), **`B17.3`** em diante (SVE, Opção C, VL=256), **`B21.2`** em diante (modelo de 26 bits, Opção c),
`E14`, `C12.5`/`C12.10`. `B20.9` segue bloqueada no usuário. Conferir dependências no `INDICE.md` antes de pegar.

**Achados da B22.7 ainda sem task** (candidatos a uma "B22.8 — curadoria + resíduos de decode"):
`VMOV_half` mede `❌` em `v8-A/32` (`decodeVmovHalf` exige `HALF_PRECISION_FP`, que `ARMV8A_32` não
declara); `VMOV_to_gp`/`VMOV_from_gp` 8/16 bits (só NEON) medem `❌` em `v8-A/32` (deveria ser `·`) e em
`v7-A+NEON` (gap real); instruções ARMv8 contadas como `❌` em colunas ARMv7 (`LDA`/`STL`/`CRC32`/
`SMC`/`HVC`/`ERET`/`SB`/`CLRM` em `v7-A+NEON`/`v7-R`/`v8-R`) pedem curadoria de versão no tsv.

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
