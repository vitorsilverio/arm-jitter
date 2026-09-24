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

## Onde estamos (atualizado 2026-09-24, após fechamento da B20.8)

**B20.8 FECHADA** (`Aarch64Architecture.ARMV8_R_64` + `Aarch64Processor.CORTEX_R82`, PMSAv8-64 no
lado A64 — `Pmsav8SystemRegisters64`/`Pmsav8AddressSpace64`, 5 registradores de MPU de EL1). ARM DDI
0600A.d lido de ponta a ponta via `curl` (não parafraseado). **Achado real que corrige a suposição
do épico**: sobreposição de regiões em PMSAv8-64 é `Translation fault` (Table C1-4), NÃO
`Permission fault` como no PMSAv8-32 (B20.7) — as duas versões de PMSA divergem nesse ponto, não são
espelho perfeito. Reusa `MemoryTranslationException64`/`FaultStatus64` de VMSA64 diretamente (o
manual confirma "reuses IFSC and DFSC fault encodings"), só um código novo (`PERMISSION_FAULT_L0`,
nível 0, que VMSA64 nunca gera). QEMU não implementa `cortex-r82` ainda (confirmado por busca no
código-fonte real) — região de fundo é decisão de implementação documentada, não fonte normativa
(o DDI 0600A.d deixa o mapa padrão IMPLEMENTATION DEFINED). Coluna nova em `docs/COBERTURA-ISA.md`
ADIADA para a B20.9 (Armadilha 6 da spec) — `./gerar-cobertura-isa.sh` rodado, zero-diff. `mvn -o
test` verde (core) + `mvn -o install` local + G5 verde em `gbaemu`/`ndsemu`/`armbox`
(`virtual-arm-box`/`n3dsemu` continuam congelados/pausados). Ver `## Resultado` da task
(`b20.8-armv8r-aarch64.md`).

**Pegáveis a seguir**: `B20.9` (validação N1-N4 + fechamento do épico B20, decide se a coluna `v8-R
(AArch64)` de `docs/COBERTURA-ISA.md` entra aqui, adiada pela B20.8) segue bloqueada no usuário —
runner natural é o `virtual-arm-box` congelado, e QEMU não tem suporte a `cortex-r82` ainda (achado
real da B20.8). `C12.5`/`C12.10` (emissão JIT nativa A64) seguem pegáveis, dimensão 2 do roadmap.
Candidata nova da
B13.22: "NEON SHA de 3 registradores A32" (`SHA1C_3s`/`SHA1P_3s`/`SHA1M_3s`/`SHA1SU0_3s`/
`SHA256H_3s`/`SHA256H2_3s`/`SHA256SU1_3s`, semântica já existe no núcleo A64 via
`Ir64CryptoShaThreeRegisterOp`, migração D1 da RFC B13.2, sem spec escrita ainda). Candidatas
gap-driven da B16.14 (specs ainda não escritas): decoder MVE "long shift" GPR-pair (19 encodings),
tail-predication `WLSTP`/`DLSTP`/`LCTP`/`VCTP` (4), `BF` 3 formas restantes, `CLRM`, `SB` de 32
bits. Candidatas novas da B14.7 (specs ainda não escritas): `VRINTR`/`VRINTZ`/`VRINTX`/`VJCVT` de
32 bits, conversões FP16 do VFPv3 (`VCVT_f32_f16`&cia), `ArmFeature.HALT` nos presets ARMv8-M
modernos. Candidata nova da B13.8/B13.22: "NEON FP16 AArch32" (as 4 `VCVT_xx_2sh` + as formas F16
que B13.6/B13.11/B13.13 também adiaram), depende de B19.5.1.

**Duas decisões de RFC ainda pendentes do usuário** (specs downstream já escritas assumindo a
recomendação — ver `tasks/README.md`): `B17.2` (comprimento de vetor SVE, recomendação: VL
configurável 256 bits default) e `B21.1` (modelo 26-bit ARM, recomendação: `R15` como view composta).

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
