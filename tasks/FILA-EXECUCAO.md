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
   linha) + "Pegáveis a seguir". Se ao editar você notar mais de uma seção dessas, consolide numa só.

## Onde estamos (atualizado 2026-09-25, B22.9 + B22.10 + B17.1 fechadas)

**`B22.9` (curadoria de denominador: 232 células) e `B22.10` (`SB` A32/T32, `VMOV` NEON 8/16 bits, `VMOV_half` sob
FP16) fechadas** — `docs/COBERTURA-ISA.md`: **23522/23523**. **Única célula `❌` restante: `MSR_i_SVCR` em `ARMv9.2-A`**
(exige o modo streaming/`ZA` da B18.2, que depende de B17 — SVE). Ver **Resultado** nas tasks.

**⚠️ "tabela 100%" NÃO é o gatilho da `1.4.0`**: a regra reservada exige 100% de TODA a arquitetura ARM alvo. Seguem
abertos: **B17** (SVE/SVE2, `sve.decode` 929 encodings), **B18** (SME, 623), **B20** (perfil R: PMSA/MPU), **B21**
(ARMv1-v3, 26 bits) e as dimensões 2/3 do `ROADMAP-100-ARM.md` (JIT nativo, Truffle). Nenhum desses entra no
denominador da tabela hoje (`NOT_IN_ANY_PRESET`).

**Pegáveis a seguir** (specs já escritas, dependências satisfeitas): **`B17.3`** (B17.1 ✅ 2026-09-25: `SVE` nos 6 `ARMV9_*_A`, `SVE2` em nenhum) em diante (SVE, Opção C, VL=256),
**`B21.2`** em diante (modelo de 26 bits, Opção c), `E14`, `C12.5`/`C12.10`. `B20.9` segue bloqueada no usuário.
Conferir dependências no `INDICE.md` antes de pegar.

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
