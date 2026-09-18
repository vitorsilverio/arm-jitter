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
   linha) + "Pegáveis a seguir". Se ao editar você notar mais de uma `## Onde estamos` no arquivo,
   é sinal de manutenção atrasada — colapse tudo em uma só antes de continuar (o histórico removido
   não se perde: já está no `## Resultado` da task e no `git log` deste arquivo).

## Disciplina de custo

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão**, pouco antes do commit final.
2. **Backend INTERPRETED em boot de sistema real é caro** — rode só quando o JIT já confirmar o
   marco; se não terminar em ~10-15min, documente "não concluído" e siga.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação"** — rode
   bloqueante com timeout alto, ou faça polling dentro da mesma chamada.
4. **Orçamento de ~60-80 tool-calls por sessão de investigação aberta.** Se a causa raiz não foi
   isolada, pare, documente o que foi descartado/aprendido e devolva.

## 🔒 Congelamento de subprojetos até 100% de cobertura (decisão do usuário, 2026-08-27)

**Nenhuma task de `armbox`/`gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` deve ser pega** — nem
investigação, nem feature, nem bugfix — **enquanto `docs/COBERTURA-ISA.md` não mostrar cobertura
completa das arquiteturas/perfis/features/modos ARM alvo.** Só trabalho de cobertura de ISA no
`arm-jitter` é elegível agora. Ver `tasks/README.md` e a memória do agente
`feedback-100-cobertura-antes-subprojetos`. `1.4.0` fica reservada para 100% — ver `tasks/README.md`
para as regras de release (suspensas até lá).

## Onde estamos (atualizado 2026-09-18, após B16.14 fechar o épico B16 — MVE/Helium 100% medido)

**B16.14 FECHADA — épico B16 (MVE/Helium) FECHADO.** `IsaCoverageReport` troca a `Applicability`
de `mve.decode` de `NOT_IN_ANY_PRESET` para `MVE_INTEGER`, e `ARMV8_1M_MVE` entra como coluna nova
(`ARMv8.1-M+MVE`) em `docs/COBERTURA-ISA.md`. Grupo `mve.decode` mede **352/352 ✅, zero `⚠️`**
contra a coluna nova. Dois achados reais corrigidos nesta task: (1) 3 linhas de
`isa-nao-aplicavel.tsv` (`VDUP`/`VRINTZ*`/`VRINTX*`) sem coluna `grupo` apagavam células MVE reais
por engano (mnemônico homônimo em `vfp.decode`) — escopadas ao arquivo certo; (2) 12 linhas de
curadoria "ausência estrutural de perfil M" (`ERET`/`MRS_bank`/`MSR_bank`/`SMC`/`HVC`/`RFE`/`SRS`/
`BXJ`/`BLX_i`/`SUB_rri`/`SETEND`/`BLX_suffix`) precisaram da coluna nova na lista de arquiteturas
(mesma razão já valia para v7-M). As 7 colunas antigas (v4T..v7-M) ficaram byte-a-byte inalteradas
(conferido). `ArmProcessor.CORTEX_M52`/`M55`/`M85` passam a resolver para `ARMV8_1M_MVE` (antes
`ARMV8_1M` sem Helium) — MVE-I/MVE-F são `IMPLEMENTATION DEFINED` mesmo nesses núcleos (WebSearch
confirmou), catálogo assume a variante mais capaz, mesma simplificação de SKU do TrustZone em
M23/M33/M35P. **34 gaps genuínos documentados, não implementados** (fora do escopo desta task):
19 encodings "MVE long shift" GPR-pair (`t32.decode`, nenhum decoder MVE existente cobre esse
espaço — candidata a task nova), `BF` 3/4 formas (`BFL`/`BFCSEL`/`BFX`/`BFLX`), tail-predication
`WLSTP`/`DLSTP`/`LCTP`/`VCTP` (documentado desde B15.6 como bloqueado no banco `VPR` — **agora
desbloqueado**, é o achado mais acionável), `CLRM`, e `SB`/`CRC32*` (7, extensões opcionais não
implementadas em NENHUM preset de 32 bits, achado independente de MVE). `mvn -o test` verde (core
4483 + truffle 73) + G5 verde nos 5 consumidores (zero-diff funcional). Release não publicado
(suspenso até 100% global). Ver `## Resultado` de `B16.14`.

**Pegáveis a seguir**: `C12.5`/`C12.10` (emissão JIT nativa A64) seguem pegáveis, dimensão 2 do
roadmap. Candidatas novas gap-driven da B16.14 (specs ainda não escritas): decoder MVE "long
shift" GPR-pair (19 encodings), tail-predication `WLSTP`/`DLSTP`/`LCTP`/`VCTP` (4, desbloqueada
pelo fechamento do B16), `BF` 3 formas restantes, `CLRM`, `SB`/`CRC32*` de 32 bits.

**Duas decisões de RFC ainda pendentes do usuário** (specs downstream já escritas assumindo a
recomendação — ver `tasks/README.md`): `B17.2` (comprimento de vetor SVE, recomendação: VL
configurável 256 bits default) e `B21.1` (modelo 26-bit ARM, recomendação: `R15` como view composta).

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
