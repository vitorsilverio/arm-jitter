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

## Onde estamos (atualizado 2026-08-28, após B9.17)

`B9.17` (`trilha-b-arquiteturas/b9.17-vfp-scalar-mov-byte-halfword-ocorrencia.md`) fechou o
candidato "VFP condicional em MPCore/v7-A" citado abaixo: as 4 células `❌` eram `VMOV_to_gp`/
`VMOV_from_gp` byte/halfword — NEON de verdade (fora de escopo, decisão já tomada pela B9.5), mas
a ferramenta não conseguia excluir só essas 2 das 3 linhas de cada mnemônico porque todas moram no
MESMO arquivo `vfp.decode` (a coluna `grupo` da B9.15 só resolve duplicação ENTRE arquivos
diferentes). `IsaCoverageReport` ganhou uma coluna `ocorrencia` opcional (5ª coluna TSV, requer
`grupo`) que casa pela posição 1-based da linha entre as de mesmo nome+arquivo. VFP MPCore
90%→98%, v7-A 92%→98% — a célula que sobra em cada coluna (`VMOV_half`, `⚠️`) é achado
pré-existente não relacionado. Zero mudança em `core/src/main`, G5 verde.



`B9.16` (`trilha-b-arquiteturas/b9.16-t32-v7m-dsp-gate.md`) fechou a maior lacuna absoluta de 32
bits (T32 `v7-M`, era 52%): não era curadoria, era gate real — `ARMV7M_FEATURES` nunca ganhou os 8
`ArmFeature`s da extensão DSP + base (`CLZ`/`LDRD_STRD`/`PRELOAD_HINTS`/`PACK_SATURATE`/
`PARALLEL_SIMD`/`SIGNED_MULTIPLY_MEDIA`/`DSP_MULTIPLY`/`UMAAL`) desde que o preset foi criado (B7.4,
2026-07-23), embora os decoders Thumb-2 já tivessem decode+IR+executor completos havia sessões
(B1.3/B1.4/B2.7/B3.1/B3.2/B9.1/B9.7) — zero decode novo, só gating + curadoria TSV dos 14
mnemônicos genuinamente N/A a M-profile. T32 v7-M 52%→94%, global 82%→83%. `ERET`/`HVC`/`SMC`/
`MRS_bank`/`MSR_bank` continuam `❌` por decisão do usuário (mesmos 5 de sempre, não excluídos).

**Próxima sessão de cobertura de ISA**: candidatos abertos que restam medidos — A64 com ~18-19% de
gap achatado em todas as versões (ARMv8.0-A 82%, 796/970); v6-M T32 ainda em 72% (8/11, só
`ERET`/`MRS_bank`/`MSR_bank`, mesmos 3 de sempre — não é mais candidato de verdade, só sobra a
mesma pendência intencional); VFP condicional em `MPCore`/`v7-A` também não é mais candidato — só
sobra `VMOV_half` (`⚠️`, achado pré-existente não relacionado, ver B9.17). Nenhum dos restantes
foi triado ainda.

`B9.15` (`trilha-b-arquiteturas/b9.15-t32-v6m-curadoria-nome-por-grupo.md`) achou e resolveu a
causa raiz de por que `T32 v6-M` ainda estava em 9% (8/82) mesmo depois da B9.10: não era falta de
triagem, era uma LIMITAÇÃO DA FERRAMENTA — `isa-nao-aplicavel.tsv` casava exclusão só por nome do
mnemônico, e ~48 nomes existem em `t16.decode` E `t32.decode` como encodings distintos (`REV`/
`NOP`/`UDF`/`B_cond_thumb`/`ADD_rri`/...), então excluí-los apagaria cobertura real de 16 bits —
a B9.10 tinha deixado esses de fora deliberadamente por esse motivo, documentado no próprio tsv.
`IsaCoverageReport` ganhou uma coluna `grupo` opcional (4ª coluna TSV, retrocompatível) que escopa
a exclusão a um `.decode` específico; as 48 linhas entraram escopadas a `t32.decode`. T32 v6-M
9%→72% (só `ERET`/`MRS_bank`/`MSR_bank`, gaps reais não relacionados, continuam `❌`); T16 v6-M
inalterado (zero colateral); v6-M global 47%→80%; global 82%→83%. Zero mudança em
`core/src/main` (só ferramenta de teste + docs), G5 verde.

`B9.13` (`trilha-b-arquiteturas/b9.13-mcr-mrc-armv4t.md`) fechou o achado colateral da B9.12: `MCR`/
`MRC` sob `ARMV4T` era gap real (ARMv3+, não curadoria) — `CoprocessorDecoder` só estava anexado a
`ARMV5TE`+. Extraída `CoprocessorRegisterDecoder` (só o espaço simples `MCR`/`MRC`) e anexada a
`ARMV4T`, mantendo `MCRR`/`MRRC` (ARMv5TE) fora dela (G2). v4T 93%→94%, global 82% (+2 células).
Zero mudança de runtime; G5 (gbaemu/ndsemu/armbox) verde.


`B9.12` (`trilha-b-arquiteturas/b9.12-v4t-v5te-curadoria-denominador.md`) fechou: v4T e v5TE eram os
piores números do 32 bits depois de v6-M (64%/70%) pelo mesmo motivo já visto na B9.10 — dezenas de
células `❌` eram instruções ARMv5T/ARMv5TE/ARMv6/ARMv6K/ARMv6T2/ARMv7 genuínas (todas posteriores
ao próprio ARMv4T/ARMv5TE), só faltava a curadoria em `isa-nao-aplicavel.tsv`. Medição: v4T 64%→93%,
v5TE 70%→95%, global 81%→82% (marco de release continua suspenso). Zero mudança de código de
produção — G5 (gbaemu/ndsemu/armbox) verde. Achados colaterais NÃO resolvidos, documentados na task:
`MCR`/`MRC` sob `v4T` (gap real de decode, ARMv3+ deveria decodificar) e os hints T16 (`YIELD`/`WFE`/
`WFI`/`SEV`/`NOP`/`IT`/`CBZ`, que também mostram `❌` em `v6K`/`MPCore` — gap real, não denominador).
Ambos candidatos a task própria.

`B9.10` (`trilha-b-arquiteturas/b9.10-t32-armv6m-triagem.md`) fechou: `v6-M` era a pior cobertura do
projeto (24%) por ter um denominador errado (211 células que a arquitetura real não tem) mais 2
gaps/bugs reais pequenos (`REV`-family faltando, `B.W`/`TBB`/`TBH` decodificando indevidamente) —
denominador correto ajustado (383→172).

`B9.11` (`trilha-b-arquiteturas/b9.11-armv6m-thumb2miscdecoder-auditoria.md`) fechou o achado
colateral que a B9.10 tinha deixado pendente: auditoria completa de `Thumb2MiscDecoder` sob
`ARMV6M` achou 10 falsos-positivos (não os 4 estimados originalmente) — hints largos
(`NOP.W`/`YIELD.W`/`WFE.W`/`WFI.W`/`SEV.W`/`ESB`), `CPS.W`, `UDF.W` e o alias de exception-return
`SUBS PC,LR,#imm`/`ERET` decodificavam sob `ARMV6M` sem gate nenhum. `ArmFeature.M_PROFILE_WIDE_MISC_CONTROL`
novo (só em `ARMV7M`) fecha os hints/`UDF.W`; o alias `ERET`/`SUB_rri` foi gateado por `M_PROFILE`
puro (não existe em NENHUM perfil M, nem v7-M — 2 células a menos também em v7-M, 61%→60%). Medição
honesta: v6-M 52%→47%, global 81%→81% (sem marco cruzado, regra de release continua suspensa).
`TT` (mencionado pela B9.10 como possível bug) já estava correto, confirmado por medição — a
trilha B (arquiteturas 32-bit A/M-profile) não tem mais nenhum achado colateral pendente
registrado. Ver `INDICE.md` da trilha B, linhas B9.10/B9.11.


A escada `B11` (`trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`, torna o A64 componível
por versão/feature) está com B11.1-B11.12 ✅ — **completa, as 9 features de B11.5 todas gateadas** —
ver `INDICE.md` da trilha B, linha B11, para o resumo de cada uma. B11.12 revelou que a premissa
"`SHA3` só precisa de gate" estava errada (`EOR3`/`BCAX`/`RAX1`/`XAR` nunca tinham decoder/executor —
implementados do zero nesta sessão; ver `b11.12-aarch64-feature-sha3.md`).

A escada `B12` (`trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`, catálogo de processadores
ARM nomeados) está com B12.1-B12.6 ✅ — **todos os núcleos catalogáveis sem decode novo estão
cobertos** (B12.5 fechou os 3 últimos aditivos possíveis: `ARMV6`/`ARMV6T2`/`ARMV6Z` puros). Restam
só pendências que exigem trabalho fora do escopo de catalogação pura de B12 (ver `INDICE.md` da
trilha B, linha B12, para o detalhe): `Cortex-A32` (precisa `LDA`/`STL`/`CRC32` novos, task de
decode própria), perfil R inteiro (nunca modelado, épico próprio) e `ARMv1`/`ARMv2`/`ARMv2a`/`ARMv3`
(modelo de registrador/exceção pré-ARMv3 diferente do resto do projeto, épico próprio). **Nenhuma
candidata de catálogo elegível agora sem abrir um desses épicos maiores primeiro** — usuário
prioriza qual abrir.
- Lacunas A64 pequenas remanescentes: já fechadas por B8.20 em 2026-08-28 — conferir `INDICE.md` da
  trilha B antes de supor que ainda há alguma pendente aqui.
- `B4.0.5` (armbox fork/pipes) — **bloqueada pelo congelamento acima**, não pegar até 100% de ISA.

## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) — **também bloqueada pelo congelamento de subprojetos** | **C9** |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real — **também bloqueada pelo congelamento** | fecha C10 |
| ~~B6.6.6~~ **EM ESPERA** — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Toolchain resolvido (WSL2+Ubuntu); falta kernel arm64 mainline real + o gap `LDR`/`STR` SIMD&FP reg-imediato do B6.2 se o initramfs precisar dele | fecha o épico B6.6 |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2) | Gap real de decode A64 (`LDR`/`STR` SIMD&FP reg-imediato) — verificar se já foi fechado por B8.13/B8.20 antes de reabrir | fecha B6.2 |

## Fila de BUGS de compat (trilha D) — sessões separadas, **bloqueadas pelo congelamento de subprojetos** até 100% de ISA

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida (gbaemu) | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| (sem task) Platinum billboard do char invisível — divergência de alocação de VRAM de textura | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi** (multi-sessão) — Fase 1 shipped, falta Fase 2+ (handshake WM ARM9↔ARM7) | ndsemu | ⚠️ MODELO FORTE |
| Platinum não boota em INTERPRETED — race de boot cross-CPU | ndsemu | ⚠️ MODELO FORTE |
| Divergência ASM×interp no JUS | ver pendência 6 do `tasks/README.md` | ⚠️ MODELO FORTE |
