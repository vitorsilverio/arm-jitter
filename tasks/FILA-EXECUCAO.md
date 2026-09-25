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

## Onde estamos (atualizado 2026-09-24, B13.24 fechada)

**`B13.24` fechada nesta rodada** — NEON FP16 AArch32: os 4 `VCVT_*_2sh` que a B13.22 tinha medido
como gap real fecham (`v7-A+NEON` 940→944, global +4 células). A remedição do Passo 2 achou que
"3-reg-same FP" e "2-reg-and-scalar FP" TAMBÉM tinham F16 `UNIMPLEMENTED` no código, mas
`IsaCoverageReport` nunca detectava (célula = 1 linha `.decode`; a forma F32 da MESMA linha já
satisfaz `✅` antes do medidor chegar a testar `sz=1`) — implementadas mesmo assim (regra máxima do
projeto: nunca presumir "não há mais F16 pendente"), zero-diff esperado e confirmado na tabela. Ver
**Resultado** na task para o achado completo sobre o ponto cego do medidor (`FILL_STRATEGIES`).

**Pegáveis a seguir** (specs já escritas, dependências satisfeitas): **`B16.15`** (épico B16
reaberto: tail-predication `WLSTP`/`DLSTP`/`LCTP`/`VCTP`, `BF`/`BFL`/`BFCSEL`/`BFX`/`BFLX`
restantes, `CLRM`, `SB`+`CRC32*` de perfil M), **`B22.7`** (épico B22 reaberto: `VRINTR`/`VRINTZ`/
`VRINTX` incondicionais, `VJCVT`, conversões FP16/BF16 do VFPv3, `ArmFeature.HALT` nos presets
ARMv8-M), **`B17.3`** em diante (fundação SVE, RFC B17.2 decidida — Opção C, VL=256) e **`B21.2`**
em diante (modelo de 26 bits, RFC B21.1 decidida — Opção c) — conferir dependências no `INDICE.md`
de cada uma antes de pegar. Também seguem pegáveis: `E14` (achado da auditoria JaCoCo da B13.23:
`IrBlockExecutor#execute`/`AsmNativePolicy` sem cobertura de teste para NENHUMA instrução NEON,
lacuna estrutural do épico B13 inteiro). `C12.5`/`C12.10` (emissão JIT nativa A64), dimensão 2 do
roadmap. `B20.9` (fechamento do épico B20) segue bloqueada no usuário — runner natural é o
`virtual-arm-box` congelado, e QEMU não tem suporte a `cortex-r82` ainda.

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
