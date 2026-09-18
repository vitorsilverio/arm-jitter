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

## Onde estamos (atualizado 2026-09-18, após B16.13b fechar sub-família 3 — B16.13 100% fechada)

**B16.13 FECHADA (50/50 encodings)** — B16.13a (2026-09-18, sub-famílias 1/2/4, 22/50) +
B16.13b (2026-09-18, sub-família 3 — `VMLADAV`/`VMLSDAV`/`VMLALDAV`/`VMLSLDAV`/`VRMLALDAVH`/
`VRMLSLDAVH`/`VMAXV`/`VMINV`/`VMAXAV`/`VMINAV`/`VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV`, 28/50).
Decoder novo `Thumb2MveDualAccumulateDecoder` (registrado após `Thumb2MveReduceDecoder`, sem
colisão de bits entre os dois, verificado por script). Achados reais (verbatim contra QEMU via
`curl`): `VMLADAV_S`/`VMLADAV_U` (forma byte) têm DUAS codificações reais que produzem o mesmo
`IrOp` (mesma classe do achado `VADDV`/`VADDLV` da B16.13a); um raw com `rdahi=15` sempre decodifica
como `VMLADAV_S`, nunca chega a "VMLALDAV_S rejeitado"; os grupos aninhados `[NMAV/NMV]`/`[V/AV]`
colidem de verdade — sob preset com `MVE_FLOAT`, "`VMAXV_S` com `size=3`" não existe na prática
(a arquitetura reaproveita esse espaço de bits para `VMAXNMV_S`); `VMLSDAV`/`VRMLSLDAVH` usam o
prefixo "unsigned" mas são sempre assinados; `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` usam o bit
que normalmente escolhe sinal para escolher PRECISÃO (binary16 vs binary32); binary16 zera os 16
bits altos de `Rda`. 5 `IrOp` novos (`Kind` 163-167), todos interpretados apenas (mesmo padrão da
B16.13a). **Auditoria JaCoCo pós-fechamento inicial achou gaps reais** (8/28 encodings nunca
alcançados em teste, `accumulate=true`/lanes mascaradas sem cobertura nos 5 executores novos) **e 2
bugs reais no decoder** (`sizeFromField` invertido em 5 pontos de despacho) — corrigidos, decoder
100% linha/branch, executores sem gap além do baseline pré-existente do projeto (`evalCond=false`).
`mvn -o test` verde (core 4483 + truffle 73, coherence test 163→168 `Kind`).
`docs/COBERTURA-ISA.md` zero-diff (mesma Armadilha 7 — `mve.decode` só sai de `NOT_IN_ANY_PRESET`
na B16.14). `docs/COBERTURA-JIT.md` atualizado (163→168). G5 verde nos 5 consumidores. Ver
`## Resultado` de `B16.13` (trilha B, duas seções: B16.13a e B16.13b).

**Pegáveis a seguir**: `B16.14` (fechamento do épico B16 — troca `NOT_IN_ANY_PRESET` por
`Applicability`, acrescenta `ARMV8_1M_MVE` às arquiteturas sondadas, catálogo `Cortex-M52`/`M55`/
`M85`) agora é pegável — suas duas dependências (B16.13, B15.7) estão ✅. `C12.5`/`C12.10` (emissão
JIT nativa A64) seguem pegáveis, dimensão 2 do roadmap.

**Duas decisões de RFC ainda pendentes do usuário** (specs downstream já escritas assumindo a
recomendação — ver `tasks/README.md`): `B17.2` (comprimento de vetor SVE, recomendação: VL
configurável 256 bits default) e `B21.1` (modelo 26-bit ARM, recomendação: `R15` como view composta).

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
