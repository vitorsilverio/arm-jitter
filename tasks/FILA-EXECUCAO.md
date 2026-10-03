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
   **Fonte citada com mais de ~1500 linhas (`Aarch64Decoder`, `IrOp`, `Ir64Op`,
   `AdvSimdLanes`, `AsmBlockCompiler`, `IrSystemExecutor`, `StandardIrBuilder`,
   `Aarch64Core`, `Aarch64DecoderCorpusTest`, ...): NUNCA `Read` o arquivo inteiro
   (2026-09-27, medido: isso sozinho já consome uma fatia grande do orçamento de contexto
   da sessão). Primeiro `Grep` pelo nome do encoding/opcode/método que a task cita para
   achar a(s) linha(s); depois `Read` só essa faixa com `offset`/`limit`. Só ler o arquivo
   inteiro se a própria task pedir isso explicitamente.
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

## Onde estamos (atualizado 2026-10-01, ADDSVL/ADDSPL/RDSVL + INVALID)

**`docs/COBERTURA-ISA.md` = 100% (29581/29581 desde a E15.9b, que tirou 38 `✅` falsos de ARMv8.0/8.1)**: `ADDSVL`/`ADDSPL`/`RDSVL` implementadas (ver **Resultado** da B17.12) e as 60 células `INVALID` passaram a medir "encoding reservado recusado" (`IsaCoverageReport`). Detalhes no **Resultado** da B17.12.

**⚠️ "tabela 100%" NÃO é o gatilho da `1.4.0`**: seguem abertos **B20** (PMSA/MPU), **B21** (ARMv1-v3) e as dimensões 2/3 do `ROADMAP-100-ARM.md` (JIT nativo, Truffle). Release continua bloqueada — decisão do usuário pendente se a tabela a 100% basta.

**Fechadas em 2026-10-02:** `E15.1` (absorveu a `E14`) — `mvn verify` agora tem piso de cobertura (`jacoco:check`) e guarda de tamanho de fonte; `E15.2` — `Ir64Op` dividido em 14 interfaces por família (quebra de nome em `ir64`, tabela no `CHANGELOG.md`); `E15.3` — `IrOp` idem (14 interfaces, quebra de nome em `ir`, G5 verde); `E15.4` — `Ir64Op#execute` substitui o `switch` de dispatch do A64 (`Ir64BlockExecutor` 2349 → 217 linhas, −24% por instrução interpretada); `E15.5` — `IrOp#execute` substitui os dois `switch` de 189 casos do 32 bits (`IrBlockExecutor` 657 → 192 linhas; 12 `Kind` quentes ficam no laço pelo gate de bench; piso de branch do JaCoCo 0,894 → 0,892 por decisão do usuário); `E15.6` — `regUse`/`regDef` nos records, DCE sem `switch` (288 → 69 linhas; branch do bundle 89,26% → 89,52%); `E15.6b` — DCE apagava escritas de GPR lidas por ~45 records sem `regUse` (CRC32, SMLALxy, NEON/MVE load/store, CPS, `LR` da tail-predication MVE), guarda por reflexão novo. **Fechadas em 2026-10-03:** `E15.7` — registro de emissores ASM de 32 bits (`AsmNativePolicy` derivada, os três `switch` por record viraram dado), `AsmBlockCompiler` 2580 → 363 e `AsmRuntimeHelpers` 1161 → 5 classes por família, bytecode gerado idêntico (oráculo de 316 800 blocos); `E15.8` — `IrSystemExecutor` 2516 → 392, MVE em 5 executores por família (4 estáticos + `IrMveMoveExecutor` sob demanda), deslocamento puro conferido por script; `E15.9` — infra `DecodeTable` + piloto (cascata `bit21=0` do AdvSIMD → 53 linhas), veredito **go**; `E15.9b` — AdvSIMD recusa `bit31=1` e copy com `bits[23:22]≠00`, `F*P_v _h` implementadas (oráculo: só mudam essas palavras); `E15.9c` — resíduo G8 do AdvSIMD/FP escalar zerado (1 838 palavras no oráculo da E15.9b, 19 412 com `Rm` enumerado), guarda = impressão SHA-256 do conjunto aceito. Ver **Resultado** de cada task.

**Pegáveis a seguir:** `B21.2` em diante, `E16` (`ERET` em `EL0` derruba o host), `C12.5`. `E15.10+` seguem `[REFINAR]`. `B20.9` bloqueada no usuário. Pendências B18.2: `FEAT_SME_FA64` em preset; `ResetSVEState` na troca AArch64↔AArch32 com `SM=1`.

**Protocolo (a pedido do usuário, sessões estourando orçamento de contexto em ~15 tasks/semana):** G5
(`tasks/README.md`) agora é condicional — pula suites de gbaemu/ndsemu quando o diff fica só em
`decoder64`/`executor64`/`ir64`/`codegen64`/`core64`/`Sve*`/`Sme*` (código que nenhum dos dois
consumidores executa); fora dessa lista, G5 continua obrigatório inteiro. JaCoCo virou passo fixo de
`Validação` no template de task (não é mais pedido manual). Regra 2 desta fila agora proíbe `Read`
de fonte >~1500 linhas inteiro — Grep+offset primeiro. O épico **`E15`** ataca a causa estrutural
(arquivos gigantes + `switch` de dispatch + cascata de feature no decoder).

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
