# Fila de execução (2026-07-15; reestruturada 2026-07-17) — para agentes com contexto limitado

**Regras de sessão (obrigatórias, existem para o agente NÃO se perder):**

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a
   próxima task na mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes
   G1-G7) e depois SÓ o arquivo da task + os fontes que ela cita. Não explorar o
   repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao
   usuário — não improvisar.
4. Nunca pegar itens da seção "Pendências que EXIGEM modelo forte" do
   `tasks/README.md`, nem da seção "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25 + gbaemu +
   ndsemu), status atualizado no índice do `tasks/README.md`, 1 commit começando
   com o ID (`B3.5: ...`).
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale
   só entre repos DIFERENTES (arm-jitter ∥ gbaemu ∥ armbox). Duas sessões no
   mesmo working tree misturam WIP uma da outra (já aconteceu em 2026-07-16:
   um `git add -A` de uma sessão de docs varreu os arquivos half-done da A6
   para dentro do commit errado — desfeito, mas é exatamente o acidente que
   esta regra evita). Pelo mesmo motivo: **commits sempre com paths explícitos
   (`git add <arquivos da SUA task>`), nunca `git add -A`.**

**Prompt de kickoff (copiar/colar, trocando o ID):**

> Leia `arm-jitter/tasks/README.md` inteiro. Depois execute APENAS a task
> **<ID>** (arquivo `<caminho>`), seguindo o protocolo do README. Leia os
> arquivos-fonte citados na task antes de escrever código. Implemente somente o
> que está em "Inclui"; respeite as decisões já tomadas (não reavaliar). Ao
> final: suites verdes, índice atualizado, um commit `"<ID>: ..."`.

---

## Disciplina de custo (obrigatória, adicionada 2026-08-17)

Sessões de investigação longa (ex.: F3) já consumiram rodadas caras demais. Regras novas,
valem para QUALQUER sessão futura desta fila:

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão.** Ao corrigir um bug no
   arm-jitter, rode primeiro só a suíte do próprio arm-jitter + o consumidor que exercita o
   bug (ex.: `virtual-arm-box` para CP15/MMU). Só rode a sequência completa
   arm-jitter+gbaemu+ndsemu+armbox+virtual-arm-box UMA vez, pouco antes do commit final da
   sessão — não a cada fix intermediário.
2. **Backend INTERPRETED em boot de sistema real é caro (já levou >30min) — rode-o só
   quando o backend JIT já confirmar o marco.** Nunca rode os dois "por rotina" a cada
   sessão. Se INTERPRETED não terminar num orçamento razoável (~10-15min), documente como
   "não concluído nesta sessão" e siga — não é bloqueador.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação".**
   Um processo de shell solto (fora da ferramenta `Agent`) não avisa ninguém quando termina.
   Rode de forma BLOQUEANTE numa única chamada, com timeout alto; se precisar de mais tempo
   que um timeout permite, faça polling DENTRO da mesma chamada (loop com `sleep`) até ter o
   resultado real, em vez de retornar e reentrar em rodadas novas só para checar.
4. **Orçamento de tool-calls por sessão de investigação aberta (~60-80).** Se a causa raiz
   não foi isolada dentro disso, pare, documente o que foi descartado/aprendido e devolva —
   não é obrigatório fechar tudo numa sessão só (histórico da F3 mostra isso sendo normal).
5. **Ao fechar uma task ou sessão, mova o relato minucioso para `tasks/FILA-HISTORICO.md`**
   e deixe aqui só um resumo de poucas linhas (o que fechou, o achado principal, o próximo
   passo). O arquivo ativo deve continuar pequeno — ele é lido INTEIRO por todo agente novo
   como parte do protocolo, então seu tamanho é custo pago em toda sessão futura.
---
## Onda 5 — cobertura de ISA (priorizada pelo usuário em 2026-08-21) 🔝 TOPO DA FILA

Frente pedida pelo usuário depois de ver a `docs/COBERTURA-ISA.md`: **completar as instruções das
arquiteturas que os emuladores usam, mínimo 80% por arquitetura, alvo final tudo ✅** — sem presumir
que alguma instrução "nunca vai ser usada" (o precedente é EL1/EL2, descartado como desnecessário e
depois exigido inteiro pelo `virtual-arm-box`).

**Plano mestre: `trilha-b-arquiteturas/b7-plano-cobertura-isa.md`** — leia ANTES de pegar qualquer
`B7.x`/`B8.x`. Ele traz a regra de triagem (o inventário do QEMU mistura versões de arquitetura) e
o protocolo por task.

**Estado dos repositórios (2026-08-21)**: os 6 estão sincronizados com o GitHub, `ahead=0`.
O `n3dsemu` ganhou repositório nesta sessão (`https://github.com/vitorsilverio/n3dsemu`, público,
21 commits) — ele era o único que nunca teve `origin`. ⚠️ **Ainda falta nele o que a F8/F9 fizeram
nos outros**: labels, `ISSUE_TEMPLATE`, e a CI da F6 (`.github/workflows`). Candidato a task curta.

**Duas regras novas do `tasks/README.md` valem para TODA task, não só desta onda:**
- **push obrigatório** ao fechar (os repos estavam só locais — `arm-jitter` chegou a 76 commits à
  frente do `origin`);
- **marco de cobertura → release no Maven Central**: global +5 pp ou qualquer arquitetura +10 pp
  desde o último release. Baseline 2026-08-21: global **53%**, A64 **18%**.

Diagnóstico medido: no 32 bits já estamos em 82-83% (o que derrubava os emuladores era a cauda
longa, não "quase nada implementado"); **em A64 estamos em 18%, e é lá que está ~90% do trabalho.**

## ✅ B10 — EL2/EL3 completo — FECHADA 2026-08-22, e ✅ `B10.6b`/`B10.6c` (as duas últimas células,
## `AT S1E2*`/`S1E3*`) fechadas 2026-08-27 — a escada B10 inteira está ✅ agora, sem pendência
## conhecida. Volta ao usuário decidir o próximo item da fila (Onda 5 restante, Q5+, ou outra
## prioridade) — nada aqui deve ser pego automaticamente sem essa decisão.

## 🔝🔝 B10 — EL2/EL3 completo (priorizado pelo usuário em 2026-08-21, à FRENTE do resto da Onda 5)

Ao fechar B8.3, o usuário reagiu ao adiamento documentado de `AT`/registradores de debug: **nenhuma
instrução ARM real fica de fora por parecer grande demais** — se depende de EL2/EL3 completos,
implementa-se EL2/EL3. Ver `feedback-nunca-excluir-instrucao-arm` na memória do agente e o plano
mestre `trilha-b-arquiteturas/b10-plano-el2-el3.md` (leia ANTES de pegar qualquer `B10.x` — traz a
escada completa, fatos de referência do `ARM DDI 0487` e a ordem sugerida). Isto passa NA FRENTE de
Q5+ abaixo: só volte para B9.x/B8.4+ depois que a escada B10 fechar (ou o usuário repriorizar).

| # | Task | Arquivo | Repo | Depende de | Nota |
|---|------|---------|------|-----------|------|
| ~~R1~~ | ~~**B10.1**~~ ✅ fechada 2026-08-21 — generalizar estado de exceção para EL0-EL3 | `trilha-b-arquiteturas/b10.1-el2-el3-estado-generalizado.md` | arm-jitter | — | `Aarch64ExceptionLevel` novo (4 níveis, `M[3:0]` real de `SPSR_ELx`); `Aarch64ExceptionState` generalizado por nível (conveniências EL1 preservadas, comportamento EL0↔EL1 idêntico); vetor de exceção agora escolhe o grupo certo ("mesmo nível" vs "nível inferior"); `ERET` lê o alvo real de `SPSR_ELx.M` em vez de sempre "EL0". `EL2`/`EL3` ainda não roteados de verdade (B10.4/B10.5) — só a infraestrutura. G5 completo (armbox 40/41 pré-existente). Ver **Resultado** na task. **Destrava B10.2/B10.3** |
| ~~R2~~ | ~~**B10.2**~~ ✅ fechada 2026-08-21 — registradores de sistema EL2 | `trilha-b-arquiteturas/b10.2-el2-registradores-sistema.md` | arm-jitter | B10.1 ✅ | 13 registradores (`HCR_EL2`/`SCTLR_EL2`/`MDCR_EL2`/`CPTR_EL2`/`TCR_EL2`/`VTTBR_EL2`/`VTCR_EL2`/`SPSR_EL2`/`ELR_EL2`/`FAR_EL2`/`ESR_EL2`/`CNTHCTL_EL2`/`VBAR_EL2`) decodificados (`Aarch64Decoder`, novo branch `op1=4`) e armazenados em `Aarch64VmsaSystemRegisters` (5 delegam ao banco por nível já criado em B10.1; 8 são armazenamento puro novo) — SEM side effect funcional ainda (nenhum código roda em EL2, isso é B10.4+). `mvn -o test` verde + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). ⚠️ Achado: toolchain devkitA64 indisponível nesta sessão — encodings derivados por fórmula (conferida contra 3 opcodes reais já no repo), não corpus real; documentado para regeneração futura. Ver **Resultado** na task |
| ~~R3~~ | ~~**B10.3**~~ ✅ fechada 2026-08-21 — registradores de sistema EL3 | `trilha-b-arquiteturas/b10.3-el3-registradores-sistema.md` | arm-jitter | B10.1 ✅ | 7 registradores (`SCTLR_EL3`/`SCR_EL3`/`MDCR_EL3`/`CPTR_EL3`/`SPSR_EL3`/`ELR_EL3`/`VBAR_EL3`) decodificados (`Aarch64Decoder`, novo branch `op1=6`) e armazenados em `Aarch64VmsaSystemRegisters` (3 delegam ao banco por nível já criado em B10.1; 4 são armazenamento puro novo) — SEM side effect funcional ainda, mesma disciplina de B10.2. `ESR_EL3`/`FAR_EL3` deliberadamente FORA (não listados no plano mestre para esta task). `mvn -o test` verde + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). Mesmo achado de B10.2: encodings derivados por fórmula (toolchain devkitA64 indisponível). Ver **Resultado** na task |
| ~~R4~~ | ~~**B10.4**~~ ✅ fechada 2026-08-21 — `HVC` real (entra em EL2) | `trilha-b-arquiteturas/b10.4-hvc-real.md` | arm-jitter | B10.1 ✅, B10.2 ✅ | Árvore de decisão real (`ARM DDI 0487 C6.2.83`): EL0→UNDEFINED(EL1); EL1→EL2 (ou UNDEFINED self-trap se `HCR_EL2.HCD`); EL2→EL2 (auto-chamada real do manual); EL3→UNDEFINED self-trap em EL3 (nunca reduz pra EL1 — achado de design corrigido na mesma sessão, `enterUndefinedInstructionException` tem alvo fixo EL1 e não serve para o caso EL3). `Ir64Op.PrivilegedCall` ganhou `isHvc()`; `Aarch64HypervisorCallException` nova, capturada em `step()`/`executeBlock()` (mesmo padrão de `Aarch64BreakpointException`). `SMC` inalterado (stub, B10.5). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). Sem mudança em `docs/COBERTURA-ISA.md` (sem side effect observável em consumidor real hoje — F11 pula GIC/PSCI). Task spec escrita nesta sessão (só existia como linha no plano mestre). **Destrava nada diretamente, mas próximo natural é B10.5 (`SMC`, mesmo raciocínio com `SCR_EL3`) ou B10.7 (registradores de debug, independente)** |
| ~~R5~~ | ~~**B10.5**~~ ✅ fechada 2026-08-21 — `SMC` real (entra em EL3) | `trilha-b-arquiteturas/b10.5-smc-real.md` | arm-jitter | B10.1 ✅, B10.3 ✅ | Mesma árvore de decisão de B10.4 (`HVC`), com `SCR_EL3.SMD` no lugar de `HCR_EL2.HCD`: `EL0`→`UNDEFINED(EL1)`; `EL1`/`EL2`/`EL3`→self-trap se `SMD`, senão `EL3` (auto-chamada de `EL3`). Stub `PSCI_RET_NOT_SUPPORTED` REMOVIDO (não só para `SMC`: `Ir64BlockExecutor#executePrivilegedCall` agora lança nos dois casos). Task spec escrita nesta sessão (só existia como linha no plano mestre). `mvn -o test` verde (1697) + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente não relacionada). Ver **Resultado** na task. **Próximos sem dependência pendente: B10.6 (`AT`), B10.7 (debug, independente) ou B10.9 (`TLBI` EL2/EL3)**
| ~~R6~~ | ~~**B10.6**~~ ✅ fechada 2026-08-21 — `AT`, formas EL1&0 | `trilha-b-arquiteturas/b10.6-at-address-translation.md` | arm-jitter | B10.1 ✅, B10.2 ✅ | `S1E0R`/`S1E0W`/`S1E1R`/`S1E1W` reais: `PAR_EL1` novo (armazenamento), `Ir64Op.AddressTranslate`/`Aarch64AddressTranslateForm` novos, decoder/executor/`Aarch64SystemRegisterBus#addressTranslate`, `TranslatingAddressSpace64#translateForAddressTranslate` (reaproveita `walk()` sem tocar TLB, `privileged` trocado temporariamente), `Aarch64ParEncoder` novo (`memory.mmu`). **2 bugs de decode corrigidos (G8)**: `AT` caía em `CACHE_MAINTENANCE_NOP` (mesmo `CRn=0b0111` da manutenção de cache, `CRm` não checado); um primeiro fix só cobria `op1==0`, deixando `S1E2R`/`S1E3R` caírem no mesmo bug — corrigido excluindo `CRm=0b1000` do bucket de NOP para QUALQUER `op1`. `mvn -o test` verde (1724) + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). `S1E2*`/`S1E3*`/`S12E*` viraram tasks explícitas no plano mestre (**B10.6b**/**B10.6c**, dependem de `TTBR0_EL2`/`TTBR0_EL3`+`TCR_EL3` NOVOS que não existem; `S12E*` seguem em **B10.8**). Ver **Resultado** na task |
| ~~R6b/R6c~~ | ~~**B10.6b/B10.6c**~~ ✅ fechada 2026-08-27 — `AT`, formas EL2/EL3 puras (`S1E2R`/`S1E2W`/`S1E3R`/`S1E3W`) | `trilha-b-arquiteturas/b10.6b-b10.6c-at-el2-el3-stage1.md` | arm-jitter | B10.2 ✅, B10.3 ✅, B10.6 ✅ | `TTBR0_EL2`/`TTBR0_EL3`/`TCR_EL3` novos + `Aarch64PrivilegedStage1TranslatingAddressSpace64` novo (regime próprio de EL2/EL3, só `AP[2]`/`XN` único, sem `AP[1]`/`PXN`/`UXN`); `addressTranslate` refatorado para `switch` exaustivo. **Fecha a escada B10 por completo.** `mvn -o test` verde (core 2352) + `install`; G5 nos 5 repos ✅ (armbox 43/43, sem falha pré-existente reproduzida). `docs/COBERTURA-ISA.md` regenerado e DESCARTADO (mostrou regressão não relacionada em 5 células A32/T32 já implementadas por B9.8.x — instabilidade do medidor, não investigada). Ver **Resultado**/histórico completo em `tasks/FILA-HISTORICO.md` |
| ~~R7~~ | ~~**B10.7**~~ ✅ fechada 2026-08-21 — registradores de debug (`op0=2`) | `trilha-b-arquiteturas/b10.7-aarch64-debug-registers.md` | arm-jitter | — | `MDSCR_EL1`/`OSLAR_EL1`/`OSLSR_EL1`/`DBGBVR0_EL1`/`DBGBCR0_EL1`/`DBGWVR0_EL1`/`DBGWCR0_EL1` decodificados (`Aarch64Decoder`, novo branch `op0==2` checado ANTES do `op0!=3` early-return) e armazenados em `Aarch64VmsaSystemRegisters` — armazenamento puro, SEM enforcement de `RO`/`WO` (decisão explícita: `OSLAR_EL1`/`OSLSR_EL1` são `WO`/`RO` no hardware real, aqui aceitam os dois sentidos para não travar o guest sem debugger conectado). Encodings conferidos contra `target/arm/debug_helper.c` real do QEMU via `WebFetch` antes de codificar. Escopo: só `n=0` de `DBGBVR`/`DBGBCR`/`DBGWVR`/`DBGWCR` (consistente com `ID_AA64DFR0_EL1.BRPs=WRPs=0` já anunciado por `Aarch64Core`, B6.6.7/B6.10) — task escrita nesta sessão (só existia como linha no plano mestre). `mvn -o test` verde (core+truffle, +9) + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente, `Armv7TortureTest`/`VfpRegisters`, não relacionada). Sem mudança em `docs/COBERTURA-ISA.md` (sem side effect observável em consumidor real hoje). **Próximos sem dependência pendente da escada B10**: `B10.8` (stage-2) ou `B10.9` (`TLBI` EL2/EL3) |
| ~~R8~~ | ~~**B10.8**~~ ✅ fechada 2026-08-22 — stage-2 (`IPA→PA`), `AT S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` reais | `trilha-b-arquiteturas/b10.8-at-stage2.md` | arm-jitter | B10.2 ✅, B10.6 ✅ | `Stage2TranslatingAddressSpace64` novo (`memory.mmu`, walk IPA→PA independente, mesma geometria 4KiB/48-bit/4-níveis da stage-1, permissão via `S2AP`/`XN` único, sem micro-TLB). Achado real (conferido contra `cpregs-at.c` do QEMU): `AT S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` usam o MESMO `op1=0b100` de `AT S1E2R`/`S1E2W` — só `op2` (4-7 vs. 0-1) distingue; `S1E2*` continua `unsupported` (B10.6b). `TranslatingAddressSpace64#translateForAddressTranslateStage12` encadeia stage-1+stage-2 — simplificação documentada: as PRÓPRIAS tabelas de stage-1 continuam lidas do físico direto, só o endereço de dados final passa por stage-2. `PAR_EL1.S` (bit `9`) novo em `Aarch64ParEncoder` distingue falha de stage-1/stage-2 (MESMO `FST` por nível nos dois). `HCR_EL2.VM=0` faz `S12E*` se comportar como `S1E*` (stage-2 desligada). `mvn -o test` verde no `core` (1756) + `truffle` (13), `mvn -o install`. G5: gbaemu ✅, ndsemu ✅, virtual-arm-box ✅ (único consumidor A64, sem mudança observável), n3dsemu ✅, armbox 40/41 (pré-existente, não relacionada). **Fecha a escada B10** (só `B10.6b`/`B10.6c` seguem fora, bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos — sem consumidor real hoje). Ver **Resultado** na task |
| ~~R9~~ | ~~**B10.9**~~ ✅ fechada 2026-08-22 — `TLBI` EL2/EL3 + stage-2 (decode) | `trilha-b-arquiteturas/b10.9-tlbi-el2-el3.md` | arm-jitter | B10.1 ✅ | `op1=0b100`(EL2, cobre também stage-2 `IPAS2E1*`/`IPAS2LE1*` e combinado `ALLE1*`/`VMALLS12E1*` — MESMO `op1` no hardware real, conferido contra `target/arm/tcg/tlb-insns.c` real do QEMU)/`0b110`(EL3) passam a ser aceitos em `decodeSystemInstructionSys` (novo helper `isTlbiRegime`), mapeando para `Ir64SystemInstructionOp.TLBI_ALL` já existente — sem mudança no executor, mesma simplificação "invalidar tudo" já aplicada ao regime EL1&0 (nenhuma TLB modelada em EL1/EL2/EL3). Toolchain devkitA64 estava DISPONÍVEL nesta sessão (ao contrário do achado de B10.2/B10.3) — 7 testes novos com encodings reais via `aarch64-none-elf-as`/`objdump`. `mvn -o test` verde (core 1735 + truffle 13) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). Task spec escrita nesta sessão. Ver **Resultado** na task. **Único item restante da escada B10: `B10.8` (stage-2 de verdade — o mais arriscado, deixado por último de propósito); `B10.6b`/`B10.6c` seguem bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` inexistentes** |

## Onda 5 — cobertura de ISA restante (retomar só depois de B10 fechar)

| # | Task | Arquivo | Repo | Depende de | Nota |
|---|------|---------|------|-----------|------|
| ~~Q1~~ | ~~**E6**~~ ✅ fechada 2026-08-21 — espaço incondicional (`cond==0b1111`) agora vira `UNIMPLEMENTED` | `trilha-e-manutencao/e6-espaco-incondicional-undefined.md` | arm-jitter | — | `decodeUnconditional` novo roteia `cond=1111` antes de qualquer dispatch condicional (inclusive antes do `SWI`, que tinha o mesmo vazamento); os 8 carve-outs já existentes movidos sem mudar bits; `CoprocessorDecoder`/`VfpDecoder` preservados via `decoderExtensions()`. Teste de regressão: `0xF2000000` (`VHADD`) não vira mais `AND`. `mvn -o test` verde + G5 nos 4 consumidores (armbox com a falha pré-existente já documentada). Sem mudança na tabela de cobertura (o script já contava esses casos como ❌) — sem marco de release. Ver índice da trilha E para o detalhe completo. **Destrava Q2 (B8.1)** |
| ~~Q2~~ | ~~**B8.1**~~ ✅ fechada 2026-08-21 — A64 load/store escalar | `trilha-b-arquiteturas/b8.1-a64-load-store-escalar.md` | arm-jitter | E6 ✅ | `STNP`/`LDNP`/`LDPSW`/`PRFM`/`LDTR`/`STTR`/`LDXP`/`STXP`/`LDAR`/`STLR`/`CAS`/`CASP` implementados. 2 bugs reais de decode corrigidos (G8): `idx=10` sem checar bit21 confundia `LDTR`/`STTR` com registrador-offset; `PRFM` (`sz=DOUBLEWORD`,`opc=10`) caía em "reservado" por engano (achava que era a forma SIMD 128-bit, que exige `V=1`). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente não relacionada). `docs/COBERTURA-ISA.md`: A64 18%→20%, global 53%→53% — sem marco de release ainda. **⚠️ Achado + CORRIGIDO na mesma sessão de fechamento**: `b7-plano-cobertura-isa.md` reusava os IDs `B7.1`-`B7.5`, que já pertenciam ao épico M-profile/Cortex-M (fechado 2026-07-23) — renumerado para `B9.1`-`B9.7` (nenhuma das tasks tinha sido executada ainda, sem retrabalho). Ver **Resultado** na task. **Destrava B8.2**|
| ~~Q3~~ | ~~**B8.2**~~ ✅ fechada 2026-08-21 — A64 inteiro restante | `trilha-b-arquiteturas/b8.2-a64-inteiro-restante.md` | arm-jitter | B8.1 ✅ | `ADC`/`SBC`/`EXTR`/`RBIT`/`REV*`/`CLZ`/`CLS`/`CNT`/`SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/`SMULH`/`UMULH`/`RMIF`/`SETF8`/`SETF16`/`CFINV`/`XAFLAG`/`AXFLAG`; bug real corrigido (`REV32`/`REV64` colidiam com `SDIV`/`UDIV`, `opc2` não checado). A64 20%→22%, global 53%→54%. Sem marco de release. **Destrava Q4 (B8.3)** |
| ~~Q4~~ | ~~**B8.3**~~ ✅ fechada 2026-08-21 — A64 branch/system | `trilha-b-arquiteturas/b8.3-a64-branch-system.md` | arm-jitter | B8.2 ✅ | `WFET`/`WFIT`/`CLREX`/`DSB(nXS)`/`SB`/`BRK`/`HLT`/`MSR (immediate)` restantes (`UAO`/`PAN`/`SPSel`/`SBSS`/`DIT`/`TCO`/`ALLINT`/`DAIFSet`/`DAIFClr`) implementados; `SYS` (TLBI/cache) ampliado para "qualquer variante = NOP" (sem TLB/cache modelada, seguro); `CB<cc>`/branches `PAuth` reais/`SVCR` excluídos em `isa-nao-aplicavel.tsv` (posteriores ao Cortex-A53). Bug real corrigido (G8): `MSR SBSS`/`DIT`/`ALLINT` já ✅ por engano — colidiam com `XAFLAG`/`AXFLAG`/`CFINV` por falta de checar `op1`. `AT`/registradores de debug (`op0=2`) deliberadamente deferidos (documentado, não presumidos desnecessários — precedente EL1/EL2 do plano mestre). A64 22%→24%, global 54%→55%. Sem marco de release. G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente). Ver **Resultado** na task |
| ~~Q5~~ | ~~**B9.5**~~ ✅ fechada 2026-08-22 — VFP `VMOV_to_gp`/`VMOV_from_gp` (word), `VMOV_64_sp`, `VCVT_fix_{sp,dp}` | `trilha-b-arquiteturas/b9.5-vfp-vcvt-vmov.md` | arm-jitter | — | Triagem real (QEMU `translate-vfp.c`) refutou o plano mestre: só 5 das 15 células eram VFPv2/v3 genuínas — as outras 10 exigem NEON (byte/halfword de `VMOV_to_gp`/`from_gp`, sem Kind novo, não excluídas na TSV por colisão de nome com a forma word já implementada) ou extensões opcionais posteriores (`VCVT_f16*`/`VCVT_b16_f32`/`VCVT_hp_int`, agora em `docs/isa-nao-aplicavel.tsv`). 2 `Kind`/`IrOp` novos (`VFP_CORE_PAIR_TRANSFER_SINGLE`, `VFP_CONVERT_FIXED`), sem suporte ASM nativo (cai no interpretado). `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente, confirmada via `git stash` nesta sessão). MPCore 82%→85%, v7-A 83%→85%, global 55%→55% — sem marco de release. Ver **Resultado** na task |
| ~~Q6~~ | ~~**B9.1**~~ ✅ fechada 2026-08-22 — A32 DSP/media (`SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/`SMMLA{R}`/`SMMLS{R}` + `UDF`) | `trilha-b-arquiteturas/b9.1-a32-dsp-media.md` | arm-jitter | — | Confirmado ARMv6 genuíno (`op_smlad`/`op_smlald`/`op_smmla`, `ENABLE_ARCH_6` real do QEMU) — excluído de `v4T`/`v5TE` na TSV. `Ra=15` é o alias sem acumulador (`SMUAD`/`SMUSD`/`SMMUL`), mesmo encoding. `UDF` reconhecida explicitamente (mesmo `IrOp.Undefined` de `UNIMPLEMENTED`) — achado real: precisou estender o corte de fronteira de bloco do `StandardIrBlockLifter` para `UDF` também, senão 23 testes que usavam `0xE7F0_00F0` como sentinela genérico de "fim do bloco" quebravam (ganhavam um op a mais). 17 encodings conferidos com corpus real (`arm-none-eabi-as`/`objdump`, devkitARM). `mvn -o test` verde (core 1767 + truffle 13) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente não relacionada). MPCore 85%→88%, v7-A 85%→87%, global 55%→56% — sem marco de release. Ver **Resultado** na task |
| ~~Q7~~ | ~~**B9.3**~~ ✅ fechada 2026-08-22 (commit `09aeb5c`, já no `origin`) — T16 ARMv6 genuínos: `SETEND`, `CPS` A/R-profile, `REV`/`REV16`/`REVSH`, `SXTH`/`SXTB`/`UXTH`/`UXTB` | `trilha-b-arquiteturas/b9.3-t16-armv6.md` | arm-jitter | — | ⚠️ Índice desta fila estava desatualizado — a task já tinha `## Resultado` e status ✅ no `INDICE.md` da trilha B, corrigido nesta sessão sem reexecutar nada. Achado principal: plano mestre citava `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB` (com acumulador) para T16, mas a forma real de 16 bits é `SXTH`/`SXTB`/`UXTH`/`UXTB` (sem acumulador, `Rn` fixo em 15 no formato `@extend`). v6K 90%→93%, MPCore 88%→91%, v7-A 87%→88%, v7-M 54%→56%, global 56%→57% — sem marco de release. Ver **Resultado** na task |
| ~~Q8~~ | ~~**B8.4**~~ ✅ fechada 2026-08-23 — A64 FP escalar aritmética (`FNMUL`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FSQRT`/`FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`) | `trilha-b-arquiteturas/b8.4-a64-fp-escalar-aritmetica.md` | arm-jitter | B8.3 ✅ | `FADD`/`FSUB`/`FMUL`/`FDIV`/`FABS`/`FNEG`/`FMOV` já existiam desde B6.5.2/B6.5.3 — escopo real era só o resto de "2 source" (opcodes 4-8 antes `unsupported`), `FSQRT` ("1 source") e a classe própria "3 source" (nunca decodificada). **2 colisões de decode reais achadas rodando o corpus** (G8): "3 source" e "Advanced SIMD scalar x indexed element/shift by immediate" compartilham `bits[28:24]`, só `bit30` separa; e o MESMO problema já existia ADORMECIDO desde B6.5.3 entre 2/1-source/imediato/compare e "scalar two-register misc"/cripto — corrigido alargando os prefixos para incluir `bit30`, com efeito colateral de corrigir 9 falsos-positivos pré-existentes (`SHA1H`/`SUQADD_s`/`SQDMULL_si`/etc. eram misdecodificados como `Fp64*` em silêncio). `mvn -o test` verde (core+truffle) + `install`. `Fp64MultiplyAdd` fica FORA de `Ir64NativePolicy` de propósito (cai no interpretado, mesmo padrão de B6.8/B6.9/B8.2/B8.3 para `Kind`s novos); os 6 ops novos de `Fp64Alu` (`NMUL`/`SQRT`/`MAX`/`MIN`/`MAXNM`/`MINNM`) JÁ são nativos (kind `FP64_ALU` reconstrói genericamente, sem mudança no compilador). A64 24%→24% (274→275/1135, +1 líquido), global 57%→57% (2287→2288/3948) — sem marco de release. Ver **Resultado** na task |
| ~~Q9~~ | ~~**B8.5**~~ ✅ fechada 2026-08-23 — A64 FP escalar comparação/seleção/conversão (`FCSEL`/`FCCMP(E)`/`FRINTx`/`SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU`/`FMOV` registrador-geral) | `trilha-b-arquiteturas/b8.5-a64-fp-escalar-comparacao-conversao.md` | arm-jitter | B8.4 ✅ | 5 `Kind`s novos (`Fp64ConditionalSelect`/`Fp64ConditionalCompare`/`Fp64Round`/`Fp64IntegerConvert`/`Fp64GeneralRegisterMove`); nenhuma colisão de decode nova (diferente de B8.4) — os 4 valores de `bits[11:10]` particionam exatamente `FCSEL`/`FCCMP`/2-source/compare-1-source sem ambiguidade. 24 exclusões novas em `isa-nao-aplicavel.tsv` (`FEAT_FP16`/`FEAT_BF16`/`FEAT_FRINTTS`/`FEAT_JSCVT`/`FEAT_FPRCVT`, todas conferidas contra `translate-a64.c` real); `FMOV_xu`/`FMOV_ux`(metade-alta de 128 bits)/`FCVTXN_s`(espaço NEON escalar) ficam de fora por limite de armazenamento/classe, não TSV. `mvn -o test` verde (+75) + `install`; G5 não se aplica (nenhum arquivo 32-bit tocado). Corpus real estendido (`aarch64-none-elf-as`/`objdump`, offsets `0x658`-`0x6fc`). A64 24%→27% (275/1135→308/1111), global 57%→59% (2288/3948→2321/3924). **🔔 MARCO DE RELEASE CRUZADO desde o `1.0.0`** (baseline global 53%/A64 18%; regra do `tasks/README.md`: publicar sempre que global suba ≥5pp — já subiu +6pp): global **59%** e A64 **27%** hoje. **Publicação de `1.1.0` no Maven Central (F5) NÃO feita nesta sessão** — ação externa/irreversível, decisão consciente de deixar para sessão dedicada (com F7 em seguida para os 4 consumidores, mesma janela que a F4 abriu). ✅ **Publicada depois, mesmo dia, por outra sessão** (`F5`, commit `dcb5ca1`, tag `v1.1.0`, confirmada no `repo1.maven.org`) — nova baseline de release passa a ser v7-A 88%/global 59% (ver Q10/B9.7 abaixo). Ver **Resultado** na task |
| ~~Q10~~ | ~~**B9.7**~~ ✅ fechada 2026-08-23 — T32 (Thumb-2), 58 lacunas | `trilha-b-arquiteturas/b9.7-t32-thumb2.md` | arm-jitter | — | `PKH`/DSP-media(10)/`BXJ`/`UDF.W`/`SUBS PC,LR,#imm`/`RFE`/`SRS` implementados (decode-reuse puro, G1); `LDM.W`/`STM.W` já estavam certos — falso negativo do MEDIDOR corrigido (`IsaCoverageReport.FILL_STRATEGIES`, campo `list:16` nunca tinha ≥2 bits nas estratégias antigas). **Bug real corrigido (G8)**: `SMLALD{X}` caía silenciosamente em `SMLAL<x><y>` (mesmos `x`/`y`, instrução ERRADA sem lançar). **⚠️ Achado do usuário em tempo real**: rascunho inicial excluiu `MRS_bank`/`MSR_bank`/`ERET`/`SMC`/`HVC` como "Hyp mode não modelado" — revertido, são instruções REAIS do ARMv7VE/Security Extensions (mesma versão v7-A, não posterior); ficam `❌` como trabalho pendente (+ `LDRxT`/`STRxT`, mesmo critério — 13 células ao todo, candidatas a 2 escadas futuras: Hyp/Monitor mode de 32 bits, e acesso não-privilegiado no MMU). `mvn -o test` verde (core 1901) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: T32 v7-A 80%→95%, v7-A geral 88%→95%. **Sem marco de release**: a `1.1.0` já tinha sido publicada por outra sessão (`F5`, commit `dcb5ca1`, mesmo dia) ANTES desta task começar — a baseline correta é a do `1.1.0` (v7-A 88%/global 59%), não a do `1.0.0`; delta real desta task é v7-A +7pp/global +1pp, abaixo dos dois gatilhos (+10pp arquitetura/+5pp global). Ver **Resultado** na task |
| ~~Q11~~ | ~~**B9.2 / B9.4 / B9.6**~~ ✅ fechada 2026-08-24 — triagem do resto do 32 bits | `trilha-b-arquiteturas/b9.2-b9.4-b9.6-triagem-resto-32bit.md` | arm-jitter | — | **B9.2**: `MOVW`/`MOVT`/`MLS`/`SBFX`/`UBFX`/`BFC`/`BFI`/`RBIT`/`SDIV`/`UDIV` já implementadas em `v7-A` desde B3.1 — confirmado real (`ENABLE_ARCH_6T2`/`aa32_arm_div` do QEMU) que são POSTERIORES a `v6K`/`MPCore`; 9 linhas novas na TSV, zero código (já implementado onde pertence). **B9.4**: quase tudo já implementado (`IT`/`CBZ`/`B`/`BLX_r`); 2 achados reais — `B_cond_thumb` era falso negativo da FERRAMENTA de medição (`cond=AL` force-preenchido caía no encoding reservado de `UDF`, corrigido em `IsaCoverageReport`), `UDF` T16 (`0xDE00`) ganhou `InstructionKind.UDF` explícito (era `UNIMPLEMENTED` genérico, mesmo padrão B9.1/B9.7); `HLT` T16 deixado pendente (depende de semihosting, não implementado no projeto, fora do escopo desta task). **B9.6**: `VFMA`/`VFMS`/`VFNMA`/`VFNMS` NOVAS em `v7-A` (`ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE`, `IrOp.VfpOperation` + `Math.fma` de verdade via `DirectedFpRounding.exactFma` novo, decoder reaproveitando 100% da extração de bits de `VMLA`/`VDIV`, ASM nativo no caminho "cold"); confirmado real (`translate-vfp.c`, "Present in VFPv4 only") que MPCore é cronologicamente ANTERIOR à VFPv4 — 8 linhas na TSV. `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`SQRT` lendo `vn=-1`, confirmada não relacionada ao código novo). `docs/COBERTURA-ISA.md`: A32 v6K/MPCore 96%→100%; v6K 94%→97%, MPCore 92%→96%, v7-A 95%→97%; global 59%→61% (+2pp). Sem marco de release (abaixo dos dois gatilhos). Ver **Resultado** na task |
| ~~Q12~~ | ~~**B8.6**~~ ✅ fechada 2026-08-24 — AdvSIMD load/store estruturado (`LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R`) | `trilha-b-arquiteturas/b8.6-a64-advsimd-load-store-estruturado.md` | arm-jitter | B8.5 ✅ | **Reabriu B6.5.1 D4 com decisão explícita do usuário**: `Aarch64FpRegisters` estendida de 64→128 bits reais (pré-requisito de toda a escada AdvSIMD B8.6-B8.10, não só desta task) — API pré-existente preservada (G3), `element`/`setElement`/`replicateElement`/`setQ` novos. Decoder: `V=1` dentro de `Loads-and-Stores` (antes sempre `unsupported`) ganhou 2 sub-rotas reais (`multiple`/`single structures`, fatos conferidos contra `a64.decode`/`translate-a64.c` reais do QEMU); load/store escalar SIMD&FP continua fora (não pedido). 3 `Ir64Op`/`Kind` novos (53-55), caem no interpretado (G1, sem ASM nativo ainda). Testes com corpus PRÓPRIO via devkitA64 real (30 decode + 13 executor). `mvn -o test` verde (arm-jitter completo) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters` de 32 bits, confirmada não relacionada). Sem marco de release (`docs/COBERTURA-ISA.md` não regenerado nesta sessão). Ver **Resultado** na task. **Próximo da escada: `B8.7` (AdvSIMD inteiro — aritmética/comparação), já com o banco `V` pronto** |
| ~~Q13~~ | ~~**B8.7**~~ ✅ fechada 2026-08-24 — AdvSIMD inteiro (aritmética/comparação) | `trilha-b-arquiteturas/b8.7-a64-advsimd-inteiro-aritmetica-comparacao.md` | arm-jitter | B8.6 ✅ | "Three same"/"three same pairwise"/"three different" (alargando/largo+estreito/estreitando)/"across lanes"/"two-register miscellaneous" + formas escalares D-only (reaproveitam o record vetorial com `esz=3`/`q=false`, mesmo truque de prefixo já usado por B8.4 para separar `decodeFpTwoSource` de "scalar two-register miscellaneous"). 8 `Kind`s novos, todos caem no interpretado (G1). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 27%→38%, global 59%→64%. **🔔 MARCO DE RELEASE CRUZADO** (arquitetura +10pp E global +5pp desde `1.1.0`) — publicação no Maven Central NÃO feita nesta sessão (mesma decisão consciente de B8.5), fica para sessão dedicada (F5 + F7 em seguida). Ver **Resultado** na task. **Próximo da escada: `B8.8` (deslocamento/saturação/estreitamento, ~140), já com a mesma cautela sobre "three different"/`Rm` documentada nas Armadilhas** |
| ~~Q14~~ | ~~**B8.8**~~ ✅ fechada 2026-08-24 — AdvSIMD deslocamento/saturação/estreitamento | `trilha-b-arquiteturas/b8.8-a64-advsimd-deslocamento-saturacao-estreitamento.md` | arm-jitter | B8.7 ✅ | `SQADD`/`UQADD`/`SQSUB`/`UQSUB`/`SSHL`/`USHL`/`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL`/`SQDMULH`/`SQRDMULH` (reaproveitam "three same"); `SUQADD`/`USQADD` (reaproveitam "two-register misc"); `SQDMULL`/`SQDMLAL`/`SQDMLSL` (reaproveitam "three different"); 4 `Kind`s novos (64-67: `SQXTN`/`SQXTUN`/`UQXTN` narrow unário, e o espaço de encoding PRÓPRIO "shift by immediate" — não-largo/não-estreito, estreitando, alargando). **Achado real de G8/G1**: reaproveitar os records vetoriais de B8.7 para as novas formas escalares de tamanho VARIÁVEL (`SQADD_s` aceita B/H/S/D, ao contrário de `ADD_s`, D-only) quebrava o truque "esz=3/q=false implica escalar" — `sqadd v0.8b` e `sqadd b0` colidiam no mesmo `(q=false,esz=0)`; corrigido com um `boolean scalar` explícito novo em `VectorArithmeticThreeSame`/`VectorArithmeticUnary`/`VectorShiftImmediate`/`VectorShiftNarrowImmediate` (breaking change de construtor, sem impacto público — pacote `ir64` interno) + `finishScalarAwareWrite` no executor (zera TUDO acima do elemento, não só os 64 bits altos). Também corrigido bug latente de B8.7 (esz forçado a `3` mesmo quando os bits reais não eram `11`, mascarando encodings reservados). `SQRDMLAH`/`SQRDMLSH` excluídos (`ARMv8.1`/`FEAT_RDM`, `isa-nao-aplicavel.tsv`); formas indexadas (`SQDMULL_vi`/etc, "vector x indexed element") fora de escopo (classe de encoding distinta, não coberta pelo título "deslocamento/saturação/estreitamento"). Sem toolchain devkitA64 nesta sessão — encodings derivados por fórmula (bit a bit, conferidos contra um encoding-base já validado no repo), mesmo fallback de B10.2/B10.3. `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 38%→54%, global 64%→69%. **🔔 MARCO DE RELEASE CRUZADO** (A64 +15pp, global +5pp desde `1.1.0`) — publicação NÃO feita nesta sessão (mesma decisão consciente das tasks anteriores). Ver **Resultado** na task. **Próximo da escada: `B8.9` (AdvSIMD FP vetorial, ~120)** |
| ~~Q16~~ | ~~**B8.10**~~ ✅ fechada 2026-08-24 — AdvSIMD permutação/redução/tabela | `trilha-b-arquiteturas/b8.10-a64-advsimd-permutacao-reducao-tabela.md` | arm-jitter | B8.7 ✅ | `EXT`/`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`/`TBL`/`TBX` novos (sub-dispatch inédito no espaço vetorial `bit21=0`, nunca examinado por B8.7-B8.9) + `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (fallback no slot "across lanes" já existente). Corpus REAL via devkitA64 (disponível nesta sessão, diferente de B8.8/B8.9). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 59%→62%, global 70%→71% — marco isolado abaixo dos gatilhos, mas o marco CUMULATIVO desde `1.1.0` (A64 27%→62%=+35pp, global 59%→71%=+12pp) continua cruzado — release não feito nesta sessão (mesma decisão consciente de B8.5/B8.7/B8.8/B8.9). **Achado**: `DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy) vivem no MESMO prefixo `bit21=0`, discriminados só por `bit15=1` — fora do título desta task, candidata a task própria. Ver **Resultado** na task |
| ~~Q15~~ | ~~**B8.9**~~ ✅ fechada 2026-08-24 — AdvSIMD FP vetorial | `trilha-b-arquiteturas/b8.9-a64-advsimd-fp-vetorial.md` | arm-jitter | B8.7 ✅ | "Three same"/"three same pairwise"/"two-register miscellaneous" de ponto flutuante, só formas simples/dupla (`FADD_v`/`FSUB_v`/`FMUL_v`/`FDIV_v`/`FMAX_v`/`FMIN_v`/`FMAXNM_v`/`FMINNM_v`/`FMULX_v`/`FMLA_v`/`FMLS_v`/`FCM**_v`/`FACG*_v`/`FABD_v`/`FRECPS_v`/`FRSQRTS_v`, `F**P_v` pareado, `FABS_v`/`FNEG_v`/`FSQRT_v`/`FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`FCM**0_v`/`SCVTF_vi`/`UCVTF_vi`/`FCVTxS_vi`/`FCVTxU_vi`). 3 `Kind`s novos (68-70), sem campo `scalar` (só vetorial — forma AdvSIMD-escalar desta família, real e distinta de `FADD_s`/etc. já feito em B8.4/B8.5, fica fora, candidata a task própria). **Achado real**: `bits[23:22]` do encoding "three same"/"two-register misc" NÃO é tamanho de elemento puro nas formas FP (só o bit baixo, `sz`, é tamanho; o bit alto, `a`, é mais um discriminador de opcode) — diferente de todo o resto da escada AdvSIMD (B8.7/B8.8), onde os mesmos 2 bits sempre foram `esz` livre. **Achado real 2**: "two-register misc (FP)" vive PARTIDO entre os 2 slots `Rm=00000`(`FABS`/`FNEG`/comparação-contra-zero)/`Rm=00001`(`FSQRT`/`FRINTx`/`FRECPE`/`FRSQRTE`/conversão inteiro↔float) que o inteiro já ocupava (B8.7 "two-register misc"/B8.8 "narrow unário") — sem colisão de opcode, conferido exaustivamente. Reaproveita as tabelas de arredondamento/saturação/`FPMaxNum` de `Ir64FpExecutor` (B8.5, promovidas de `private` para package-private). 15 exclusões novas em `isa-nao-aplicavel.tsv` (`FEAT_FHM`/`FEAT_FCMA`/`FEAT_FRINTTS`/`FEAT_FAMINMAX`/extensão recente de `FSCALE`); meia-precisão (`FEAT_FP16`) deliberadamente SEM entrada na TSV (mesmo mnemônico reaproveitado pela linha "sd" implementada — TSV casa por nome, excluir suprimiria as duas linhas). Sem toolchain devkitA64 nesta sessão — encodings derivados por fórmula. `mvn -o test` verde (core+truffle, 2120 testes) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente, mesmo stack trace `VfpRegisters.s` já documentado). `docs/COBERTURA-ISA.md`: A64 54%→59%, global 69%→70% (denominador mudou por causa das 15 exclusões novas). Marco isolado desta sessão abaixo dos gatilhos (+5pp/+1pp), mas o marco CUMULATIVO desde `1.1.0` (A64 27%→59%=+32pp, global 59%→70%=+11pp) continua cruzado — publicação no Maven Central (F5) NÃO feita nesta sessão (mesma decisão consciente de B8.5/B8.7/B8.8). Ver **Resultado** na task. **Próximo da escada: `B8.10` (permutação/redução/tabela: `EXT`/`UZP`/`TRN`/`ZIP`/`TBL`/`*V`, ~40)** |

✅ **`1.1.0` já publicada no Maven Central** (`F5`, commit `dcb5ca1`, tag `v1.1.0`, confirmada em
`repo1.maven.org`) — publicada por outra sessão no mesmo dia, ENTRE o fechamento da B8.5 e o início
da B9.7. Nova baseline de release: v7-A **88%**, global **59%**. A B9.7 mediu contra essa baseline
(não a do `1.0.0`) e ficou abaixo dos dois gatilhos (v7-A +7pp, global +1pp) — **sem marco de
release pendente no momento**.

✅ **F7 rodada 2 fechada (2026-08-23)** — os 5 consumidores (gbaemu/ndsemu/armbox/virtual-arm-box/
n3dsemu) subiram de `1.0.0` para `1.1.0`; `org.ow2.asm` já não estava mais declarado em nenhum
(nada a remover). Aceite reconfirmado com `~/.m2/repository/dev/vitorsilverio` renomeada: gbaemu
240, ndsemu 183, armbox 40/41 (falha pré-existente, não relacionada), virtual-arm-box 87, n3dsemu
199 — todos resolvendo do Central. Commit+push nos 5 consumidores e no arm-jitter (docs). Ver
**Resultado** em `trilha-f-infra/f7-consumidores-central.md`. Janela F4/F7 fechada de novo.

✅ **Q11 (B9.2/B9.4/B9.6, triagem do resto do 32 bits) fechada 2026-08-24** e ✅ **Q12 (B8.6,
AdvSIMD load/store estruturado) fechada 2026-08-24** — ver as duas linhas na tabela acima. B8.6 é
o primeiro degrau da escada AdvSIMD e estendeu o banco `V` para 128 bits reais (decisão do
usuário). ✅ **B8.7 (AdvSIMD inteiro — aritmética/comparação) fechada 2026-08-24** — priorizada pelo
usuário entre 3 opções (B8.7 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q13 acima.
✅ **B8.8 (deslocamento/saturação/estreitamento) fechada 2026-08-24** — mesma priorização de 3
opções (B8.8 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q14 acima.
✅ **B8.9 (AdvSIMD FP vetorial) fechada 2026-08-24** — ver linha Q15 acima.
✅ **B8.10 (AdvSIMD permutação/redução/tabela) fechada 2026-08-24** — priorizada pelo usuário entre
3 opções (B8.10 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q16 acima. Achado novo:
`DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy) ficaram de fora, candidata a task própria.
✅ **B8.11 fechada 2026-08-26** (priorizada pelo usuário entre B8.11/G6.2/G6.3/AdvSIMD-copy) —
`AESE`/`AESD`/`AESMC`/`AESIMC`/`PMULL`/`PMULL2` implementados (achado: o Cortex-A53 do raspi3 TEM a
Crypto Extension base, ao contrário do que o plano mestre presumia); 66 exclusões TSV reais
(PAC/MTE/`CPY*`/dot-matmul/SHA512/SM3/SM4, todas ARMv8.2-9.x confirmadas); `SHA1*`/`SHA256*` ficaram
de fora por orçamento (candidata **B8.11b**, mesma Crypto Extension ARMv8.0). A64 61%→66%, global
71%→72%; marco cumulativo desde `1.1.0` continua cruzado, release não feito nesta sessão (mesma
decisão consciente de B8.7-B8.10). **Achado de bug PRÉ-EXISTENTE** (B8.7+, não desta task):
`decodeAdvancedSimdInteger` confunde "AdvSIMD across lanes" com "three different" genuíno sempre que
`Rm` é um registrador `16`-`31` (heurística de bit4 inválida nesse caso) — documentado com teste de
regressão, não corrigido, candidata a task própria. Ver **Resultado** em
`trilha-b-arquiteturas/b8.11-a64-extensoes-opcionais-triagem.md`. **Candidatas seguintes, não pegas
automaticamente**: `B8.11b` (SHA1/SHA256), o bug de dispatch across-lanes×three-different, `DUP`/
`INS`/`SMOV`/`UMOV` (achado de B8.10), `G6.2`/`G6.3` (n3dsemu).

✅ **E8 fechada 2026-08-26** (`trilha-e-manutencao/e8-advsimd-integer-rm-vs-bit11-dispatch-bug.md`) —
priorizada pelo usuário entre as 4 candidatas acima; corrigiu o bug de dispatch across-lanes×
three-different achado (não corrigido) pela B8.11. Achado mais amplo que o documentado: a colisão
por `Rm` também acontecia com `Rm=0`/`Rm=1` (não só `Rm>=16`). Discriminador real = bit11 do word
(fixo em `0` só em "three different"), corpus real via devkitA64. `mvn -o test` verde + `install`;
G5 completo nos 5 consumidores + armbox ✅. Candidatas restantes, não pegas automaticamente:
`B8.11b`, `DUP`/`INS`/`SMOV`/`UMOV`, `G6.2`/`G6.3`.

✅ **B8.11b fechada 2026-08-26** (`trilha-b-arquiteturas/b8.11b-a64-sha1-sha256.md`, priorizada pelo
usuário entre B8.11b/DUP-INS-SMOV-UMOV/G6.2/G6.3) — `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/
`SHA256H2`/`SHA256SU1`/`SHA1H`/`SHA1SU1`/`SHA256SU0` implementados (resto da Crypto Extension da
B8.11). 2 espaços de encoding novos achados: three-register SHA no prefixo escalar `bit21=0`
(nunca examinado antes); two-register SHA no mesmo `Rm`/`esz`/`U` de `AESE` mas exigindo checagem
própria `scalar && ...` (a checagem `q && ...` de AES nunca dispara para formas escalares, porque
`q` é forçado a `false` ali). Corpus real via devkitA64; valores esperados do executor vieram de
reimplementação Python independente do FIPS PUB 180-4. `mvn -o test` verde + `install`; G5 completo
nos 5 consumidores ✅ (armbox 43/43, falha pré-existente não reproduziu, mesmo achado da B8.11). A64
66%→66%, global 72%→72% (medidor conta células de decode-tree, não mnemônicos) — sem marco de
release isolado, mas o cumulativo desde `1.1.0` continua cruzado (release não feito nesta sessão).
Ver **Resultado** na task. **Candidatas restantes, não pegas automaticamente**: `DUP`/`INS`/`SMOV`/
`UMOV` (achado de B8.10), `G6.2`/`G6.3` (n3dsemu).

✅ **B8.12 fechada 2026-08-26** (`trilha-b-arquiteturas/b8.12-a64-advsimd-copy.md`, priorizada pelo
usuário entre B8.12/G6.2/G6.3) — `DUP`(elemento/geral)/`INS`(geral/elemento)/`SMOV`/`UMOV`
implementados. Achado real: o discriminador desta família dentro do sub-dispatch da B8.10 é
`bit10` (não `bit15`, como a nota "Não inclui" da B8.10 sugeria) — as 5 instruções têm `bit10=1`
fixo, oposto de `EXT`/permute/`TBL`/`TBX`, então o gate `if(bit10) return null` do topo já
descartava a família inteira antes de chegar ao `if(bit15)`; método novo `decodeAdvancedSimdCopy`
despachado a partir desse gate. `mvn -o test` verde (2260) + `install`; G5 completo nos 5
consumidores ✅ (armbox 43/43, falha pré-existente não reproduziu). A64 66%→68%, global 72%→73%;
marco cumulativo desde `1.1.0` continua cruzado (release não feito nesta sessão). **Candidatas
restantes, não pegas automaticamente**: `G6.2`/`G6.3` (n3dsemu).

✅ **G6.1 fechada 2026-08-24** (`arm-jitter/tasks/trilha-g-3ds/g6.1-exemplos-restantes.md`, escrita
e executada na mesma sessão, priorizada pelo usuário entre B8.11/G6.1/B6.14/AdvSIMD-copy) — os 6
exemplos `graphics/gpu` que ainda não desenhavam (`composite_scene`/`fragment_light`/`lenny`/
`textured_cube`/`cubemap`/`gpusprites`) têm causa real diagnosticada, sem correção aplicada (não são
"pequenas e óbvias"): 3 morrem por `fs:USER` sem RomFS (`OpenArchive`/`OpenFileDirectly` não
implementados → `svcBreak(PANIC)` do guest), os outros 3 por `VertexShaderInterpreter` sem `CMP`
(opcodes `0x2E`-`0x2F`) nem `MAD` (`0x30`-`0x3F`, instrução multiply-add fundamental do PICA200).
Só documentação tocada (`tasks/`), nenhum código de produção — G5-invariante não se aplica. Ver
**Resultado** na task. **Candidatas novas, não pegas automaticamente**: `G6.2` (RomFS mínimo em
`fs:USER`) e `G6.3` (`VertexShaderInterpreter`: `CMP`+`MAD`).

✅ **G6.2 fechada 2026-08-26** (`trilha-g-3ds/g6.2-fs-user-romfs-self-mount.md`, priorizada pelo
usuário entre release Maven Central+F7/G6.2/G6.3) — `fs:USER OpenArchive`/`OpenFileDirectly` +
`FSFILE::Read`/`GetSize`/`Close` implementados no n3dsemu. Achado real que simplificou a task: para
um `.3dsx`, `romfsInit()` é `romfsMountSelf`, que abre o PRÓPRIO arquivo via `ARCHIVE_SDMC` e lê nele
em offsets absolutos — o parsing da estrutura RomFS é feito inteiramente pelo GUEST, sem precisar de
parser de RomFS em Java (bastou servir os bytes brutos do `.3dsx` já carregado via uma sessão de
arquivo sintética por abertura). `mvn -o test` verde no n3dsemu (206, +7); G5 não se aplica (nenhum
arquivo do arm-jitter tocado). `cubemap`/`gpusprites` leem seus `.t3x` reais do RomFS embutido e não
panicam mais (ainda 0 desenhos no `--report`, não investigado além disso); `composite_scene` não
panica mais por `fs:USER`, mas revela causa NOVA e não relacionada em `APT:U` (`0x0044`/`0x000B`/
`0x0102`), candidata a task própria. Ver **Resultado** na task. **Candidatas restantes, não pegas
automaticamente**: `G6.3` (n3dsemu, `VertexShaderInterpreter` `CMP`+`MAD`), o `APT:U` achado acima.
~~A publicação pendente do release Maven Central~~ — **ver nota logo abaixo: já havia sido feita
(`1.2.0`, 2026-08-26) por sessão não refletida aqui, e uma `1.3.0` nova foi publicada em seguida.**

✅ **`1.2.0` publicada no Maven Central em 2026-08-26** (sessão sem registro nesta fila até agora —
achado só na sessão da `1.3.0` abaixo) — inclui todo o trabalho de cobertura B8.6-B8.10 (AdvSIMD:
load/store estruturado, inteiro, deslocamento/saturação, FP vetorial, permutação/redução/tabela) +
`Gdb64Server` (stub GDB para AArch64) + fix de `VSQRT` interpretado (`vn=-1`). `armbox` já estava em
`1.2.0`; `gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` ficaram para trás em `1.1.0` (F7 incompleta).

✅ **`1.3.0` publicada no Maven Central em 2026-08-27** (a pedido explícito do usuário, mesmo com o
delta desde `1.2.0` abaixo dos dois gatilhos normais de release — A64 61%→68%=+7pp, global
71%→73%=+2pp) — `B8.11`/`B8.11b` (Crypto Extension: `AES*`/`PMULL*`/`SHA1*`/`SHA256*`), `B8.12`
(AdvSIMD copy: `DUP`/`INS`/`SMOV`/`UMOV`), `E7` (JIT A64: exceções de guest escapando pro host) e
`E8` (bug de dispatch `decodeAdvancedSimdInteger`/`Rm`). Publicado via CI (`release.yml`, tag
`v1.3.0`, commit `eaa979f`) — a publicação manual (`mvn -Prelease clean deploy`) tinha travado no
`maven-gpg-plugin` pedindo pinentry interativo (sem passphrase salva em `settings.xml`, decisão do
usuário). O workflow do GitHub Actions terminou ✅, mas a sincronização com `repo1.maven.org` ficou
MUITO mais lenta que o normal (>1h30, portal mostrando `PUBLISHING` — usuário mencionou que a conta
está perto do limite mensal de releases do Central, possível causa). **F7 (subir os 5 consumidores
para `1.3.0`) foi ADIADA a pedido do usuário** — não pegar automaticamente ainda; retomar quando o
usuário confirmar que `1.3.0` sincronizou (checar
`https://repo1.maven.org/maven2/dev/vitorsilverio/arm-jitter/1.3.0/arm-jitter-1.3.0.pom`). Estado
dos consumidores nesta pausa: `armbox` em `1.2.0`; `gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu`
ainda em `1.1.0`. **Considerar, antes de publicar a próxima versão**: mirror de artefatos (jar/pom
direto) e GitHub Pages como réplica do Central foram avaliados e EXPLICITAMENTE RECUSADOS pelo
usuário (2026-08-27) — nada de mirror, só o Central mesmo, mesmo com o limite mensal apertado.

✅ **G6.3 fechada 2026-08-27** (`trilha-g-3ds/g6.3-vertex-shader-cmp-mad.md`, escrita e executada na
mesma sessão, priorizada pelo usuário entre G6.3/investigar-APT:U/checar-sync-1.3.0) — `CMP`
(formato 1c) e `MAD`/`MADI` (formato 5/5i) implementados no `VertexShaderInterpreter` do n3dsemu.
Layout de bits validado contra o código-fonte real do nihstro (`shader_bytecode.h`, via `curl` — o
`WebFetch` sobre o wiki do 3dbrew deu 403, e sobre o próprio `shader_bytecode.h` resumiu em vez de
citar os `BitField`s). Achado real de decode: os bits "ignorados" na identificação de
`CMP`/`MAD`/`MADI` (LSB/3-bits-baixos) são dados reais (`cmp.x`/parte de `dest`) — resolvido
despachando por FAIXA de opcode antes do `switch` do formato 1. 3 testes sintéticos novos
(`ShaderBinary`s montados à mão, sem `.shbin` real disponível que use essas instruções). `mvn -o
test` verde no n3dsemu (209, +3); G5 não se aplica (nenhum arquivo do arm-jitter tocado). **Efeito
real**: `textured_cube` destravado por completo (`desenhos=605 vertices=21780`, antes 0);
`fragment_light`/`lenny` não morrem mais no `CMP`, mas revelam bloqueio NOVO em `JMPC` (`0x2C`,
controle de fluxo condicional) — candidata própria, não pega automaticamente. Ver **Resultado** na
task. **Candidatas restantes, não pegas automaticamente**: controle de fluxo do vertex shader
(`JMPC`/`JMPU`/`IFC`/`IFU`/`CALL*`/`LOOP`/`BREAK*`), o achado de `APT:U` da G6.2, e retomar a F7
quando `1.3.0` sincronizar no Maven Central.

✅ **G6.4 fechada 2026-08-27** (`trilha-g-3ds/g6.4-vertex-shader-controle-de-fluxo.md`, escrita e
executada na mesma sessão, priorizada pelo usuário entre G6.4/investigar-APT:U/checar-sync-1.3.0) —
controle de fluxo completo do `VertexShaderInterpreter` (`JMPC`/`JMPU`/`IFC`/`IFU`/`CALL`/`CALLC`/
`CALLU`/`LOOP`/`BREAK`/`BREAKC`). Layout de bits do nihstro + semântica de 3 pilhas (`IF`/`CALL`/
`LOOP`) transcrita fielmente do interpretador de referência real do Citra (`shader_interpreter.cpp`,
fork `lime3ds/lime3ds` via `curl` — o `citra-emu/citra` original não existe mais nesse caminho).
`fragment_light` (597 desenhos, 21492 vértices) e `lenny` (568 desenhos, 1899960 vértices)
destravados por completo; sem regressão em `textured_cube`/`simple_tri`. `mvn -o test` verde no
n3dsemu (215, +6); G5-invariante não se aplica (nenhum arquivo do arm-jitter tocado). Não validado
visualmente (RFC D4). Ver **Resultado** na task.

✅ **G6.5 fechada 2026-08-27** (`trilha-g-3ds/g6.5-apt-inquire-checknew3ds-sharedfont.md`, escrita e
executada na mesma sessão, priorizada pelo usuário para investigar o achado de `APT:U` da G6.2) —
`InquireNotification`/`CheckNew3DS` triviais; `GetSharedFont` (a causa REAL do `svcBreak(PANIC)`
em `composite_scene` — `C2D_TextParse`/citro2d exige fonte mapeada) implementado com
`SharedFontGenerator` novo, gerando um BCFNT/CFNU real via `java.awt.Font` (Noto Sans, SIL OFL
1.1, embutida em `src/main/resources/fonts/`). **Achado que revogou parte da decisão da G4.1**: o
toolset `JayFoxRox/3ds-font` (clonado nesta sessão) mostrou ser um stub Python2/pycairo
não-funcional (nunca serializou BCFNT de verdade) — substituído por um serializador próprio
escrito contra a especificação pública do 3dbrew. `composite_scene` não panica mais e desenha de
verdade pela 1ª vez (`desenhos=834 vertices=105084`). `mvn -o test` verde no n3dsemu (219, +4);
G5-invariante não se aplica (nenhum arquivo do arm-jitter tocado). Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `ptm:sysm` (sessão sem serviço registrado,
achado colateral da G6.5, não bloqueador), e retomar a F7 quando `1.3.0` sincronizar no Maven
Central (checado nesta sessão: ainda 404 em `repo1.maven.org`).

✅ **G6.6 já estava fechada 2026-08-27** (`trilha-g-3ds/g6.6-ptm-sysm.md`, priorizada pelo usuário
nesta sessão entre G6.6/B9.8-spec/LDRxT-STRxT-spec — mas ao abrir a task, `PtmSysmService`/
`PtmSysmServiceTest` já existiam commitados e pushados (`d2bf926`) e o `INDICE.md` da trilha G já
mostrava ✅; esta `FILA-EXECUCAO.md` só estava desatualizada, mesmo padrão de dessincronia já visto
com a `1.2.0`). Nada reexecutado; só esta entrada de bookkeeping foi escrita/pushada. **Candidatas
restantes, não pegas automaticamente**: `B9.8` (Hyp/Monitor mode 32-bit, precisa spec), `LDRxT`/
`STRxT` (precisa spec), retomar F7 quando `1.3.0` sincronizar (ainda 404 em 2026-08-27), `B10.6b`/
`B10.6c` (bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos, sem consumidor real).

✅ **B9.8.1 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.1-hyp-monitor-estado-generalizado.md`,
priorizada pelo usuário entre B9.8/LDRxT-STRxT — escolheu B9.8) — plano mestre novo
`b9.8-plano-hyp-monitor-32bit.md` escrito nesta sessão (mesmo padrão do `b10-plano-el2-el3.md`,
fatos conferidos via QEMU real: `target/arm/tcg/{translate.c,a32.decode}`, `curl` direto). B9.8.1 é
a fundação (fecha o achado 6 da B9.7): `CpuMode.HYP`/`CpuMode.MONITOR` novos + banking real em
`ArmCore` — `SP_hyp` banco próprio, `LR` em Hyp mode é o `LR_usr`/`LR_sys` COMPARTILHADO (achado
real do QEMU: Hyp mode não banca LR), `ELR_hyp` registrador à parte fora de R0-R15, `SP_mon`/
`LR_mon`/`SPSR_mon` banco normal. Corrige bug latente (`CpuMode.fromBits` lançava para esses 2 bits
de modo). Puramente aditivo, sem decode de instrução nova ainda. `mvn -o test` verde (core +
suíte completa) + `install`; G5 completo nos 5 consumidores ✅ (zero-diff, nenhum usa Hyp/Monitor
ainda). Ver **Resultado** na task. **Próximas da escada B9.8, qualquer ordem**: B9.8.2 (`HVC`),
B9.8.3 (`SMC`), B9.8.4 (`ERET` A32), B9.8.5 (`MRS_bank`/`MSR_bank`).

✅ **B9.8.2 (`HVC` real) e B9.8.3 (`SMC` real) fechadas 2026-08-27** — sessão sem registro nesta fila
até agora (mesmo padrão de dessincronia já visto com `1.2.0`/G6.6), achado só na sessão da B9.8.4
abaixo. Ver **Resultado** em `b9.8.2-hvc-real.md`/`b9.8.3-smc-real.md`.

✅ **B9.8.4 (`ERET` real A32) fechada 2026-08-27** (task spec escrita e executada na mesma sessão,
só existia como linha no plano mestre) — `ArmFeature.VIRTUALIZATION_EXTENSIONS` novo, sem preset
habilitando ainda (nenhum consumidor modela V7VE hoje, aditivo puro); `UNDEFINED` em `USER`; em
qualquer outro modo, `PC`=`ELR_hyp` (Hyp mode) ou `LR` do banco ativo, `CPSR`=SPSR do modo ativo —
instrução de RETORNO pura (`IrOp.Eret`), SEM `ArmException` nova (diferente de `HVC`/`SMC`, mesma
categoria de `RFE`). Confirmado que o caminho genérico do ALU (`SUBS PC,LR` e o alias T32 de B9.7)
continua lendo `LR` mesmo em Hyp mode — simplificação deliberada já documentada, não alterada por
esta task. `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5 consumidores ✅. Sem
marco de release. Ver **Resultado** em `b9.8.4-eret-real.md`. **Falta só `B9.8.5`
(`MRS_bank`/`MSR_bank`) para fechar a escada B9.8 por completo.**

**Trabalho real pendente aberto pela B9.7 (13 células T32, NÃO excluídas — ver a task)**: (1) Hyp
mode + Monitor mode de 32 bits, para `MRS_bank`/`MSR_bank`/`ERET`/`SMC`/`HVC` — épico comparável à
escada EL2/EL3 do AArch64 (B10), candidato a `B9.8`/spec própria; (2) acesso de memória
não-privilegiado (`LDRxT`/`STRxT`, 8 instruções) — precisa de `IrOp` novo + parâmetro de privilégio
efetivo na tradução MMU, candidato a task própria. Nenhum dos dois pego automaticamente — são
épicos, não sessões.

✅ **B9.8.5 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.5-mrs-msr-bank.md`, spec escrita e
executada na mesma sessão — próximo item natural da escada B9.8 já aberta, sem precisar de nova
priorização do usuário) — `MRS`/`MSR` bancado (A32 e T32): `BankedRegisterSysm` novo resolve `(r,
sysm)` em `(modo,registro|ELR_hyp|SPSR)`, reaproveita `bankedRegister`/`setBankedRegister`/`spsr`/
`setSpsr`/`elrHyp` de B9.8.1; `UNDEFINED` em modo `USER`. Achado real: o dispatch T32 de `MRS` em
`Thumb2MiscDecoder` usava igualdade exata (só reconhecia a forma registrador) — generalizado para
máscara, mesmo padrão que `MSR` já usava. Encodings reais via devkitARM antes de codificar. `mvn -o
test` verde + `install`; G5 completo nos 5 consumidores ✅ (armbox 43/43 desta vez, a falha
pré-existente não reproduziu). Sem marco de release. **Fecha a escada B9.8 (Hyp/Monitor 32-bit) por
completo — B9.8.1 até B9.8.5.** Ver **Resultado** na task. Candidatas restantes, não pegas
automaticamente: `LDRxT`/`STRxT` (precisa spec), `B10.6b`/`B10.6c` (bloqueadas), retomar F7 quando
`1.3.0` sincronizar no Maven Central.

✅ **B9.9 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.9-ldrxt-strxt-unprivileged-access.md`, spec
escrita e executada na mesma sessão, priorizada pelo usuário entre B9.9/B10.6b-B10.6c — `1.3.0`
confirmada sincronizada no Maven Central `repo1.maven.org` durante a mesma checagem, então F7 também
ficou desbloqueada, mas não foi executada nesta sessão) — `LDRxT`/`STRxT` (acesso "unprivileged", 8
formas A32+T32) reais: `AddressSpace#withUnprivilegedAccess` novo (`TranslatingAddressSpace` alterna
`privileged=false` no escopo, `try/finally`, mesmo padrão de
`TranslatingAddressSpace64#translateForAddressTranslate`/B10.6); `DecodedInstruction`/`IrOp.Load`/
`Store` ganham o campo `unprivileged`; A32 usa o mesmo bit `W` que já existia (`!preIndexed&&writeback`,
sem bit novo), T32 usa o ramo `p&&u` do `decodeT4` que antes devolvia `null`; `AsmNativePolicy` cai
no interpretado quando `unprivileged()`. Teste ponta-a-ponta novo (`LdrxtStrxtPrivilegeTest`) prova
que um `STRT` em modo privilegiado aborta como `USER` numa página `AP_USER_READ_ONLY`, sob `step()`
E sob bloco JIT compilado. `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5
consumidores ✅. Sem marco de release (mudança pequena). **Fecha as 13 células que a B9.7 deixou `❌`
de propósito por completo** (5 pela escada B9.8, 8 por esta task). Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `F7` (subir os 4 consumidores para `1.3.0`,
agora desbloqueada — `armbox` já em `1.2.0`, os outros 4 em `1.1.0`), `B10.6b`/`B10.6c`
(bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos, sem consumidor real).

✅ **F7 fechada 2026-08-27** (`trilha-f-infra/f7-consumidores-central.md`, "Rodada 3" — única
candidata não bloqueada da lista acima, sem precisar de nova priorização do usuário) — `1.3.0`
confirmada resolvível no Central antes de começar; os 5 consumidores (`gbaemu`/`ndsemu`/`armbox`/
`virtual-arm-box`/`n3dsemu`) bump para `1.3.0` (armbox vinha de `1.2.0`, os outros 4 de `1.1.0`) +
docs corrigidas. Aceite com `~/.m2/repository/dev/vitorsilverio` renomeada: gbaemu 240, ndsemu 183,
virtual-arm-box 87, n3dsemu 221, armbox 43/43 (falha pré-existente não reproduziu) — todos BUILD
SUCCESS resolvendo do Central; `org.ow2.asm:asm:9.7.1` confirmado transitivo. Commit por repo +
push. Ver **Resultado** na task.

✅ **B10.6b/B10.6c fechadas 2026-08-27** (`trilha-b-arquiteturas/b10.6b-b10.6c-at-el2-el3-stage1.md`,
task spec escrita e executada na mesma sessão — única candidata não bloqueada da lista acima, sem
precisar de nova priorização do usuário) — `AT S1E2R`/`S1E2W`/`S1E3R`/`S1E3W` (stage-1 pura dos
regimes EL2/EL3): `TTBR0_EL2`/`TTBR0_EL3`/`TCR_EL3` novos + `Aarch64PrivilegedStage1TranslatingAddressSpace64`
novo. **Fecha a escada B10 (EL2/EL3) por completo** — sem pendência conhecida na trilha. `mvn -o
test` verde (core 2352) + `install`; G5 nos 5 repos ✅ (armbox 43/43). `docs/COBERTURA-ISA.md`
regenerado e descartado nesta sessão (regressão não relacionada em 5 células A32/T32, não
investigada — ver a task). Ver **Resultado**/histórico completo em `tasks/FILA-HISTORICO.md`.
**Fila automática vazia de novo** — próxima priorização cabe ao usuário (Onda 5 restante, Q5+,
investigar o desvio de `docs/COBERTURA-ISA.md`, ou outra prioridade).

✅ **E9 fechada 2026-08-27** (`trilha-e-manutencao/e9-cobertura-isa-mrs-bank-eret-regressao.md`,
priorizada pelo usuário entre "investigar COBERTURA-ISA" / n3dsemu / aarch64-virt64) — a
"regressão" de `MRS_bank`/`MSR_bank`/`ERET`/`HVC`/`SMC` (A32) achada pela B10.6b/B10.6c NÃO era
regressão nem instabilidade do medidor: era um falso positivo antigo (G8, misdecode silencioso —
`a32.decode` tem `simd=false`, então o medidor nunca rebaixava esses encodings pra `FALLBACK`)
corrigido de verdade por `B9.8.2`-`B9.8.5`, que deram decode dedicado + gate real por `ArmFeature`
a essas 5 instruções — nenhum preset declara `VIRTUALIZATION_EXTENSIONS` ainda (decisão explícita
da B9.8.4, "aditivo puro"), então `❌` é o valor CORRETO hoje. `docs/COBERTURA-ISA.md` regenerado
e COMMITADO (a sessão B10.6b/c tinha descartado): global 73%→73% (2774→2771/3777, sem gatilho de
release — T32 subiu mais do que A32 "caiu"). Nenhum código de produção tocado, G5 não se aplica.
Ver **Resultado** na task. **Candidata registrada, não pega automaticamente**: preset novo com
Virtualization Extensions (Hyp/Monitor 32-bit real) para essas 5 células ficarem `✅` de verdade —
sem consumidor real pedindo isso hoje. **Fila automática vazia de novo.**

✅ **Levantamento geral 2026-08-27** (a pedido do usuário: "verificar o que tem de tarefas em
aberto nas várias frentes") — cruzado `FILA-EXECUCAO.md` com o `INDICE.md` de cada trilha
(vários estavam desatualizados, mesmo padrão de dessincronia já visto). Resultado completo
entregue ao usuário no chat; resumo: só **G7** estava livre de bloqueio real, o resto é
🧑 bloqueado (C7/C9, C10, B4.0.3 item 3, B6.2 aceite #2, B6.6.6, A9 PR2, G6 comercial) ou
"exige modelo forte" (D6, dispatch megamórfico ndsemu, idle-loop skip, JUS ASM×interp,
WiFi fases 3-5, Platinum INTERPRETED, Platinum billboard/VRAM). Usuário escolheu G7.

🔴 **G7 refinada 2026-08-27** (`trilha-g-3ds/g7-backport-vulkan-ndsemu.md`) — a pergunta gating
do próprio `[REFINAR]` ("vale a pena mover a rasterização pro Vulkan?") foi respondida por
MEDIÇÃO real (instrumentação temporária revertida, `mvn exec:java ... bench asm` em MKDS e
SM64DS): a rasterização 3D já roda numa thread própria overlapada com a CPU do próximo quadro
(`RenderPipeline`, ping-pong de 2 slots) e o tempo de render (10,5-11,3ms) fica folgadamente
abaixo do tempo de CPU por quadro (13,5-21,3ms nos dois jogos) — CPU é o teto, não a
rasterização, confirmando por medição direta o que `ndsemu-perf-plan` já suspeitava por outro
caminho. **G7 não avança como proposta de ganho de fps** — decisão registrada na task, não é
"nunca fazer" (só sem motivo hoje). Nenhum código de produção alterado (instrumentação
revertida via `git checkout`, nada commitado no ndsemu). Ver **Resultado** na task.
**Fila automática vazia de novo** — restam só os itens bloqueados/modelo-forte do levantamento
acima; próxima priorização cabe ao usuário.

## Onda 3 — fila ATUAL (executar de cima para baixo)

Mesmas regras de sempre: 1 sessão = 1 task (ou 1 PR); **ordem dentro do mesmo
repo é obrigatória**; a coluna Repo mostra o que pode andar em paralelo (repos
diferentes apenas).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| P1 | **B4.0.5** — armbox fase 3: fork/execve/pipes/wait | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox | B4.0.3 | ainda bloqueada — B4.0.3 fechou parcial, falta o busybox thumb2 (ver 🧑 abaixo) que essa task precisa como corpus |

## Onda 4 — priorizada pelo usuário em 2026-08-15 (executar de cima para baixo)

Cinco frentes pedidas pelo usuário: rename do `virtual-arm-box` (ex-`linuxbox`), emulador de 3DS, licença BSD,
issues no GitHub, e o 1.0 do arm-jitter no Maven. **Ordem escolhida pelo usuário:
infra primeiro, n3dsemu depois** — o n3dsemu é a única frente multi-sessão longa, e começar
por ela deixaria o resto parado.

Decisões já tomadas (**não reabrir**): BSD **3-Clause** · Maven **Central** mantendo o
`groupId` `dev.vitorsilverio` (domínio `vitorsilverio.dev` é do usuário, verificação por DNS
TXT) · gráficos do n3dsemu em **Vulkan/LWJGL 3 com janela GLFW própria**, sem backend de
software · `armbox` e `virtual-arm-box` seguem **repos separados** · n3dsemu começa por
**`.3dsx` homebrew**, ROM comercial é [REFINAR].

**Atualização 2026-08-15 (tarde)**: `virtual-arm-box` **agora TEM repositório no GitHub**
(decisão anterior revertida a pedido do usuário) — `https://github.com/vitorsilverio/virtual-arm-box`,
público, criado e vinculado como `origin` do checkout local `linuxbox/` (na época; renomeado
localmente para `virtual-arm-box/` pela F2, histórico git preservado via `git mv`), push
inicial feito (5 commits, incl. `F1: licença BSD 3-Clause`). **F8 e F9 retroativamente
completadas para este repo** (mesma sessão): 11 labels + `.github/ISSUE_TEMPLATE/{bug,feature,config}.yml`
(commit `3215f29` no `virtual-arm-box`, `criar-labels.sh` do arm-jitter estendido com o 5º repo),
issue `virtual-arm-box#1` postada (manifesto e `Fecha:` de F3 atualizados). Sem milestone (mesmo
padrão do armbox — sem agrupamento definido ainda).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| ~~P2~~ | ~~**F1**~~ ✅ fechada 2026-08-15 — licença BSD 3-Clause nos 5 repos | `trilha-f-infra/f1-licenca-bsd.md` | todos | — | `LICENSE`+`<licenses>`+README nos 5 repos, `mvn -o validate` verde, 1 commit por repo; destravou a F5 |
| ~~P3~~ | ~~**F8**~~ ✅ fechada 2026-08-15 — labels/milestones/templates + fronteira issues×`tasks/` | `trilha-f-infra/f8-github-issues-setup.md` | 4 repos | — | seção no `tasks/README.md`, 11 labels × 4 repos (`tasks/issues/criar-labels.sh`), 3 milestones (arm-jitter 1.1, gbaemu Fidelidade, ndsemu Compatibilidade), templates `bug.yml`/`feature.yml`/`config.yml` nos 4 repos; destrava a F9 |
| ~~P4~~ | ~~**F9**~~ ✅ fechada 2026-08-15 — 20 issues postadas | `trilha-f-infra/f9-github-issues-criacao.md` | 4 repos | F8 | gbaemu#1-5, ndsemu#1-7, arm-jitter#1-5, armbox#1-3; manifesto preenchido, placeholders `#TBD-*` resolvidos, `Fecha:` nas 8 tasks relacionadas; `virtual-arm-box/01` pendente (sem remote); destrava P5 |
| ~~P5~~ | ~~**F2**~~ ✅ fechada 2026-08-15 — rename `linuxbox` → `virtual-arm-box` + abstração `Machine` | `trilha-f-infra/f2-rename-virtual-arm-box.md` | virtual-arm-box | F1 | diretório+pacote+artefato renomeados, `Machine`/`--machine=versatilepb` novo, `README.md`/`ROADMAP.md` novos, docs do arm-jitter atualizadas; `mvn -o test` verde (23 testes); destrava P15/P16 |
| ~~P6~~ | ~~**F4**~~ ✅ fechada 2026-08-15 — arm-jitter 1.0.0 preparado | `trilha-f-infra/f4-arm-jitter-1.0.0-escopo.md` | arm-jitter | F1 | bump `1.0`→`1.0.0` nos 4 POMs (`grep` de confirmação vazio); `maven.deploy.skip=true` no `capi/pom.xml` (jar da lib nativa não serve como dependência); `CHANGELOG.md` novo (Keep a Changelog PT-BR, entrada 1.0.0 resumida por capacidade a partir do `ROADMAP.md`/README); seção `## Versionamento` no README (semver, API pública = pacotes `arch`/`core`/`core64`/`memory`/`jit`/`codegen`/`coprocessor`/`swi`/`debug`/`ir`/`ir64` + factories padrão de `JitRuntimeFactory`); corrigida a linha "MMU / full-system 32-bit" do README (era 🟡 citando `PREFETCH_ABORT` recursivo no `linuxbox` — desatualizada desde que B4.1.5 fechou o épico por completo em 2026-08-14 com shell `busybox` interativo; virou ✅ citando `virtual-arm-box`) + o parágrafo B4 inteiro e a data do cabeçalho do `ROADMAP.md` (mesmo ajuste, "Onde estamos" 2026-07-31→2026-08-15); coordenadas Maven de exemplo no README também atualizadas para `1.0.0`. `mvn -o test` verde (core 1327 + truffle 13, JBR 25 `C:\Users\user\.jdks\jbr-25.0.3`) e `mvn -o install` verde, `dev.vitorsilverio:arm-jitter:1.0.0` instalado em `~/.m2` (o `1.0` antigo preservado como rede de segurança, conforme a spec pede). **G5 suspenso por desenho nesta task**: gbaemu/ndsemu/armbox/virtual-arm-box não foram tocados e ficam quebrados (pedem `arm-jitter:1.0`) até a F7 rodar — ⚠️ agende F7 logo em seguida, janela deveria ser curta |
| ~~P7~~ | ~~**F5**~~ ✅ fechada 2026-08-15 — publicado no Maven Central | `trilha-f-infra/f5-maven-central-publicacao.md` | arm-jitter | F4 | `dev.vitorsilverio:arm-jitter:1.0.0`+`arm-jitter-truffle:1.0.0` no ar (`repo1.maven.org` confirmado por `mvn dependency:resolve` sem instalação local); 2 bugs corrigidos (`capi` vazando pro bundle, plugin `0.7.0`→`0.11.0`); ver índice do `tasks/README.md` para o detalhe. Destrava P8/P9 |
| ~~P8~~ | ~~**F7**~~ ✅ fechada 2026-08-15 — consumidores no Central, sem `asm` declarado | `trilha-f-infra/f7-consumidores-central.md` | 4 consumidores | F5 | 4 POMs em `1.0.0`, `org.ow2.asm` removido de gbaemu/ndsemu (transitivo, confirmado); `mvn test` com `~/.m2/repository/dev/vitorsilverio` renomeada passou nos 4 (gbaemu 240/ndsemu 183/armbox 41/virtual-arm-box 23); docs corrigidas; commit por repo. Fecha a janela aberta pela F4/F6 |
| ~~P9~~ | ~~**F6**~~ ✅ fechada 2026-08-15 — CI verde nos 4 repos + release.yml escrito | `trilha-f-infra/f6-github-actions-pipeline.md` | 4 repos | F5 | 5 testes do gbaemu sem guarda (falhariam, não pulariam) ganharam `assumeTrue`; ndsemu/armbox não precisaram de nada; 4 execuções reais observadas verdes na primeira tentativa; `release.yml` escrito mas não testado ponta a ponta (decisão da spec — sem release vazia só pra provar); usuário ainda precisa cadastrar os 4 segredos; ver índice do `tasks/README.md` para o detalhe |
| ~~P10~~ | ~~**G1**~~ ✅ fechada 2026-08-15 — `n3dsemu`: esqueleto + loader `.3dsx` + primeira `svc` | `trilha-g-3ds/g1-esqueleto-n3dsemu.md` | n3dsemu (novo) | F7 | repo novo criado e commitado localmente; loader+memória+CP15/c13+SvcTable+`ARM11_MPCORE`; achado real documentado (convenção de imediato de `SVC` GBA/NDS vs. Horizon, sem tocar no decoder compartilhado); `n3dsemu testdata/application.3dsx --trace-svc` chega a `0x21 svcCreateAddressArbiter` idêntico nos 3 backends; `mvn -o test` verde (15); ver índice do `tasks/README.md` para o detalhe completo; destrava G2 |
| ~~P11~~ | ~~**G2**~~ 🟡 PARCIAL (2026-08-15, PR1+PR2; investigação 2026-08-16 confirmou desbloqueio + achou/corrigiu 1 bug novo) — kernel Horizon em HLE | `trilha-g-3ds/g2-kernel-hle-svc.md` | n3dsemu | G1 | PR2 fechou threads/sincronização/`AddressArbiter`/começo de IPC (ver índice do `tasks/README.md`); aceite objetivo "roda até `svcExitProcess`" ainda **NÃO alcançado**, mas o bloqueio original (FPSCR) está resolvido — ver nota abaixo, "Investigação 2026-08-16" |
| ~~P11.5~~ | ~~**G2.2**~~ ✅ fechada 2026-08-18 — causa raiz do "laço" achada + fix pequeno aplicado | `trilha-g-3ds/g2.2-address-arbiter-loop.md` | n3dsemu | G2 | NÃO era uma SVC retentando: trace de duas fases (`ArmCore#step()` puro, sem JIT — achado vale para os 2 backends) mostrou o boot inteiro reiniciando do zero a cada 271.058 instruções porque `srvInit` executa `MCR p15,0,r0,c7,c10,{5}` (`DMB`, ARMv6K) que `N3dsCp15` não reconhecia → `ArmException.UNDEFINED` → sem vetor de exceção configurado, PC caía em 0x4 e andava até tropeçar de volta em `0x100000`. Fix: `DSB`/`DMB` como no-op em `N3dsCp15`. Boot agora alcança `svcSendSyncRequest` (fronteira real com G3) nos 3 backends. `mvn -o test` verde (94 testes). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G3** |
| ~~P12~~ | ~~**G3**~~ 🟡 PARCIAL (2026-08-18) — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2.2 | codec IPC + `ServiceRegistry` + 7 serviços implementados, `svcSendSyncRequest` despacha de verdade. 2 achados reais corrigidos: `Loader3dsx` não alinhava segmentos a página (bloqueava TODO `srv:GetServiceHandle`) + fila de interrupção do `gsp::Gpu` precisa ser populada, não só o evento sinalizado. `mvn -o test` verde (118). **Aceite NÃO fechado**: `read-controls.3dsx` não sai sozinho via `--script`+START (trava no loop `ClearEvent`/`WaitSynchronization` da thread de relay do gsp, prioridade mais alta nunca devolve controle); raiz não isolada — candidato a **G3.2**. Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.5~~ | ~~**G3.2**~~ 🟡 PARCIAL (2026-08-18) — investigar a inanição do loop de `read-controls.3dsx` (thread de relay do `gsp::Gpu`) | `trilha-g-3ds/g3.2-gsp-relay-starvation.md` | n3dsemu | G3 | causa raiz do sintoma ORIGINAL achada e corrigida (bug real: `RegisterInterruptRelayQueue` devolvia o "GSP module thread index" errado, `1` em vez de `0` — guest lia/escrevia no bloco de fila ERRADO da memória compartilhada); revelou bloqueio NOVO mais profundo (main thread nunca reescalonada depois de criar a thread de relay, mesmo com o relay drenando certo) — virou **G3.3** (ver linha abaixo: causa raiz achada, não é o `Scheduler`). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.6~~ | ~~**G3.3**~~ ✅ fechada 2026-08-18 — causa raiz do não-reescalonamento achada + corrigida | `trilha-g-3ds/g3.3-main-thread-never-rescheduled.md` | n3dsemu | G3.2 | trace com `LR` (não só `PC`) localizou o chamador real: `T1` bloqueava em `svcArbitrateAddress` esperando `gspEvents[2]` (`GSPGPU_EVENT_VBlank0`, endereço `0x0011D26C = 0x11D25C + 2*8`, aritmética fechada via desmontagem de `gspWaitForEvent`/`gspEventThreadMain` cruzada com `WebFetch` no libctru real). **Causa raiz não era um evento faltando** — era `GspGpuService#pushInterrupt` escrevendo a entrada nova no CURSOR DE LEITURA da fila (campo que pertence só ao cliente, confirmado contra 3dbrew + `popInterrupt()` real) e avançando esse mesmo campo, em vez de derivar o índice como `(readCursor+count)%CAPACITY` — a 1ª interrupção real (`PDC0`=2) acabava sinalizando `gspEvents[0]` em vez de `gspEvents[2]`. Corrigido em `GspGpuService.java`; teste de regressão unitário novo; confirmado ao vivo (`--slices=200`) que a main thread agora reescalona a cada VBlank e entra no laço normal de `read-controls` (não sai sozinho ainda — sem input real, fora do escopo). `mvn -o test` verde (124). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G4** |
| ~~P13~~ | ~~**G4**~~ 🟡 PARCIAL (2026-08-19) — janela Vulkan (LWJGL 3 + GLFW) apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3.3 ✅ | implementado por completo: POM com LWJGL 3 (BOM + profiles de natives por SO, `lwjgl-vulkan` sem classifier em Windows/Linux); `gpu/` novo (`Screen`/`PixelFormat`/`PicaRenderer`/`RecordingRenderer`/`FrameBufferCodec`/`FrameBufferState`/`GuestFrameBufferReader`); `GspGpuService` agora INTERPRETA `SetBufferSwap` de verdade (antes descartava, G3: "descarta tudo") e expõe `FrameBufferState`; `gpu/vulkan/VulkanRenderer` com pipeline Vulkan completo (instância/dispositivo/swapchain/render pass/pipeline único de quad-cheio/2 texturas/upload via staging buffer/sync 2 quadros em voo), shaders GLSL como recursos do jar compilados via `lwjgl-shaderc` em runtime (`present.vert`/`present.frag` — rotação retrato→paisagem em UMA linha de GLSL, conforme a task pede); `Main` com janela como DEFAULT + `--headless` preservando o comportamento da G3; teclado (mapeamento fixo) e mouse (touch) ligados via GLFW. **Bug real encontrado e corrigido nesta sessão**: `VulkanRenderer#close()` chamava `vkDestroySampler` DENTRO do laço por tela (2 texturas = destruía o mesmo sampler compartilhado duas vezes) — Vulkan não valida isso sem validation layers, e o double-destroy corrompia o heap nativo (`STATUS_HEAP_CORRUPTION`, processo morria sem stack trace/hs_err; isolado por bisecção com prints de progresso rodando a JVM fora do Surefire). `mvn -o test` verde (inclui `VulkanRendererSmokeTest`, que roda de verdade contra o driver Vulkan real desta máquina — não só `RecordingRenderer` — e prova que inicializar/apresentar um quadro sintético/fechar não lança; guarda com `Assumptions`/`LinkageError` para CI sem GPU, RFC D4). Smoke-run manual confirmado: `n3dsemu testdata/hello-world.3dsx` roda 10s em janela sem crashar/exceção nenhuma; `--headless` continua idêntico à G3. **Não fechado por completo**: falta só o passo HUMANO explícito do Aceite ("anexe uma captura de tela" — RFC D4: "nenhuma task da trilha G pode ter como aceite automatizado 'o triângulo apareceu'", validação é sempre visual/do usuário) — esta sessão não tem como capturar/julgar a imagem. Usuário: rode `n3dsemu testdata/hello-world.3dsx` e confirme visualmente (texto legível, orientação correta nas duas telas) para fechar G4 de vez e destravar G5 |

**Sessão de validação 2026-08-19 (usuário tentou confirmar G4 visualmente — ainda preto, 2 bugs
reais corrigidos, 2 achados novos viram task)**: `hello-world.3dsx` abria em janela preta, sem
texto algum. Corrigidos 2 bugs reais do n3dsemu (commit `f36a6aa`): (1) `aptInit` real abre 3
sessões (`APT:U`/`APT:S`/`APT:A`) para o mesmo serviço — só `APT:U` estava registrado, `APT:S`
falhava e abortava o boot antes de qualquer desenho (`ServiceRegistry` ganhou `registerAlias`);
(2) o libctru moderno (2.7.0) não chama mais `GSPGPU:SetBufferSwap` por IPC a cada quadro, escreve
direto no `FrameBufferUpdate` da memória compartilhada do `gsp` (3dbrew) — só o caminho antigo por
IPC estava implementado; `GspGpuService#onVBlank` agora lê esse bloco. Mesmo assim a tela continua
preta: rastreado até `APT:U GetSharedFont` (cmd `0x44`) não implementado — sem fonte mapeada o
console não tem o que desenhar (framebuffer troca de endereço certo a cada quadro, mas fica
100% zerado, confirmado varrendo o buffer inteiro). Virou task **G4.1** (ver tabela acima). Achado
colateral mais sério: com os 2 fixes, o boot avança mais longe e `mvn -o test` do n3dsemu expõe um
bug PRÉ-EXISTENTE do `arm-jitter` (confirmado via `git stash`, não introduzido por este commit) —
divergência real ASM×interpretador em `StoreExclusive` (STREX), padrão de spinlock/retry sob
`ARM11_MPCORE`. Virou task **E3** no `arm-jitter`. `mvn -o test` do n3dsemu: 2 falhas (a
divergência STREX), não corrigidas nesta sessão — commit feito mesmo assim a pedido do usuário
(os 2 fixes são corretos e uma melhoria real, mesmo sem fechar a suíte). |
| P13.5 | **G4.1** — `APT:GetSharedFont` (fonte do sistema para o console) | `trilha-g-3ds/g4.1-apt-shared-font.md` | n3dsemu | G4 | ⏸️ **PARADA (sessão 2026-08-19, passo "Inclui" 1 — confirmar a hipótese): HIPÓTESE REFUTADA por evidência, não implementado.** Ver nota abaixo ("Sessão 2026-08-19 (G4.1) — hipótese de GetSharedFont refutada") — usuário decidiu o próximo passo: task nova **G4.2** (ver linha abaixo), não é a causa da tela preta |
**Sessão 2026-08-19 (G4.1) — hipótese de `GetSharedFont` REFUTADA (passo 1 do "Inclui": "confirmar a
hipótese... não presumir")**: a task pedia confirmar, com trace, que `APT:GetSharedFont` era chamado
e falhava antes de implementar o fix. Rodando `n3dsemu testdata/hello-world.3dsx --headless
--trace-svc` com orçamento de fatias bem maior que o da sessão anterior (200→3000, chega a executar
o laço principal do app em regime estacionário por milhares de iterações, log completo capturado, não
só as últimas 32 chamadas do ring buffer) o comando `0x0044` (`GetSharedFont`, confirmado via
`WebFetch` na 3dbrew: `NS_and_APT_Services`, cabeçalho `0x00440000`) **nunca aparece nem uma vez** —
nenhum log `[APT:U] comando desconhecido 0x0044`. Cruzando com o fonte real do libctru
(`libctru/source/console.c` via `WebFetch` no GitHub `devkitPro/libctru`): `consoleInit`/
`consoleDrawChar` usam uma fonte **embutida no próprio binário** (`default_font_bin`, bitmap 8×8
compilado estaticamente), *não* a fonte do sistema via `APT_GetSharedFont` — essa API só é usada
pelo módulo de fontes do `citro2d`/`citro3d` (texto TTF-like), que o `hello-world` (baseado em
`console.c` puro) não usa. **Confirmado também no fonte do próprio exemplo**
(`C:\devkitPro\examples\3ds\graphics\printing\hello-world\source\main.c`): `printf` roda antes do
laço principal; nenhuma chamada relacionada a fonte aparece em lugar nenhum. Conclusão: a tela preta
**não tem relação com fonte nenhuma** — a causa real está em algum outro lugar do caminho
`consoleInit`→`printf`(escreve direto no framebuffer via ponteiro de `gfxGetFramebuffer`)→
`gfxSwapBuffers`→`GspGpuService`/`FrameBufferState`→`Main#presentFrame`. **Não implementado**
(rodar o toolset `JayFoxRox/3ds-font` teria sido trabalho desperdiçado sobre uma hipótese falsa) —
devolvida ao usuário: precisa de uma sessão nova de investigação (candidata a task **G4.2**) para
achar a causa real, provavelmente rastreando byte a byte o que `consoleDrawChar` escreve no
framebuffer do guest vs. o que `GuestFrameBufferReader`/`presentFrame` de fato lê (mesma técnica de
dump de memória já usada em outras investigações desta fila). `mvn -o test` não rodado nesta sessão
(nenhum código tocado). Nenhum commit desta sessão além da atualização deste arquivo.

| ~~P13.6~~ | ~~**E3**~~ ✅ fechada 2026-08-19 — causa raiz achada + corrigida: bug do harness, não de LDREX/STREX | `trilha-e-manutencao/e3-strex-asm-interp-divergence.md` | arm-jitter | — | causa raiz NÃO era semântica de STREX em nenhum backend — era `DivergenceCheckingCodeEmitter` restaurando o `scratchCore` do candidato via `ArmCore#loadState` a CADA bloco, que limpa o monitor de exclusividade de propósito (correto p/ save-state real, errado aqui); um `STREX` no bloco seguinte a um `LDREX` (padrão spinlock/retry) sempre via o monitor aberto no candidato. Fix aditivo: transfere a reserva do core real pro scratch após o restore. `mvn -o test` verde (arm-jitter core+truffle) + `mvn -o install`; G5: gbaemu/ndsemu/n3dsemu verdes (os 2 testes do n3dsemu citados na task voltam a passar), armbox 40/41 (falha pré-existente não relacionada). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P13.7~~ | ~~**G4.2**~~ ✅ fechada 2026-08-19 — causa raiz real achada e corrigida: `descriptorCount` ausente no `VkWriteDescriptorSet` | `trilha-g-3ds/g4.2-tela-preta-sem-fonte.md` | n3dsemu | G4.1 (hipótese refutada) | Passo 1 (dump de memória) refutou a premissa da própria task: `hello-world.3dsx` escreve pixels reais em `Screen.TOP` e a cadeia até `GuestFrameBufferReader` está correta (teste `HelloWorldFramebufferTest`). Passo 2/3: usuário confirmou a janela AINDA preta rodando de verdade — instrumentação (System.err temporário) provou milhares de `uploadPending`/`renderFrame` reais com dados de textura corretos, ainda preto; **achado real**: `-Dn3dsemu.vulkan.validation=true` (validation layers, já existiam no código) acusou `VUID-vkCmdDraw-None-02699` — o descriptor set nunca foi atualizado. Causa: `createScreenTexture` monta o `VkWriteDescriptorSet` via `calloc` (zerado) e nunca chama `.descriptorCount(1)` — `vkUpdateDescriptorSets` virava um no-op silencioso, sem erro de API (só a validation layer acusa, e só no `vkCmdDraw` seguinte, não no próprio update — por isso passou despercebido nas sessões G4/G4.1 sem validation ligada). Fix de 1 linha (`VulkanRenderer.java`), usuário confirmou visualmente ("agora imprimiu"). `mvn -o test` verde (136). **Destrava G5** |
| ~~P14~~ | ~~**G5**~~ ✅ fechada 2026-08-21 (PR1-PR4; aceite visual alcançado pela G5.2/G5.3, PR4 e levantamento fechados) — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4.2 ✅ | LONGA, 3 PRs; aceite é **só** o `simple_tri` desenhar visualmente, validação do usuário (RFC D4). PR1/PR2: parser+registradores+shader interpretado+`VertexPipeline`, ver `FILA-HISTORICO.md`. **PR3 fechada nesta sessão**: `gpu/tev/TevConfig`+`TevGlslGenerator` (decodifica os 6 estágios TEV dos registradores reais, gera GLSL — testado, mas AINDA não usado pelo pipeline Vulkan, que continua com o shader estático equivalente ao caso `simple_tri`); `gpu/Texture` (formatos não-comprimidos + deswizzle Morton 8×8, testado; não usado pelo Vulkan ainda — task permite adiar p/ PR4); e **o item que faltava para `simple_tri` desenhar de verdade**: `GSPGPU_TriggerCmdReqQueue` agora lê a fila GX real (`gpu/GxCommandQueue`, layout confirmado via 3dbrew), `gpu/shader/ShaderUpload` captura o upload de vertex shader por registrador-FIFO (código/opdescs/uniforms — o caminho real do hardware, não mais só `.shbin`-arquivo), e `GspGpuService` desenha de verdade no `VulkanRenderer` quando um `DrawArrays`/`DrawElements` real dispara (`Main`/`N3dsMachine` injetam o renderer real no modo janela). Teste de integração ponta-a-ponta novo (`GspGpuServiceTest`) monta a fila GX real e confirma 3 vértices corretos chegando ao renderer. `mvn -o test` verde (**180**, +17). Fumaça manual (`--headless`) de `simple_tri.3dsx` não crasha, mesmo padrão de espera de VBlank do `hello-world.3dsx`. **Simplificações documentadas** (Javadoc de `ShaderUpload`): convenção fixa `o0`=posição/`o1`=cor (sem decodificar `SH_OUTMAP`), só uniform float32 (float24 lança exceção), sempre `Screen.TOP`. G5-invariante não se aplica. Detalhe completo em `FILA-HISTORICO.md`. **Falta**: usuário rodar `n3dsemu <path>/simple_tri.3dsx` (janela) e confirmar visualmente; se OK, PR4 (sampler Vulkan de textura + wiring do TEV no pipeline + float24 + `SH_OUTMAP` granular) fecha a trilha G de vez |
| ~~P14.5~~ | ~~**G5.1**~~ 🟡 encerrada 2026-08-21 pela **G5.2** (que achou a causa raiz que faltava — ver linha abaixo) — 3 sessões — tela preta em `simple_tri.3dsx`: 1 bug real corrigido, causa raiz do travamento bem mais estreita mas ainda não fechada | `trilha-g-3ds/g5.1-simple-tri-tela-preta.md` | n3dsemu (+ 1 fix aditivo no arm-jitter) | G5 | **Sessão 1**: `onVBlank` dispara e `WaitSynchronization` bloqueia de verdade (2 hipóteses da validação de 2026-08-20 refutadas). **Bug real corrigido** (arm-jitter, aditivo/G3): `Scheduler#switchTo` restaurava `ArmCore.cycles` por thread via `loadState`, fazendo o relógio virtual COMPARTILHADO andar para trás a cada troca de contexto; `ArmCore.setCycles(long)` novo + `Scheduler` reafirma o relógio depois do restore. **Sessão 2**: LR+`objdump` de `simple_tri.elf` (técnica da G3.3) mostrou que `0x1140A4`/`0x1140CC` são um mailbox single-slot de `gspWaitForAnyEvent` (não `gspEvents[]`); instrumentação dinâmica confirmou que `TriggerCmdReqQueue` nunca é chamado E que o loop de vsync do `C3D_FrameBegin` FUNCIONA CORRETAMENTE. **Sessão 3**: usuário reproduziu o triângulo brevemente 2x pela linha de comando (nunca pelo IntelliJ, mesmo JDK/`Rebuild`/`target/` apagado — diferença não explicada, provável timing real sensível a carga de CPU); a janela fica preta e ABERTA (não fecha) depois — hipótese "evento P3D mal entregue pro guest" testada e REFUTADA por um teste novo (`GspGpuServiceTest`, confirma entrega correta: fila de interrupção recebe id `5` certinho). Travamento real ainda não isolado — candidata **G5.2** com alvo preciso (capturar `LR` no retorno de `C3D_FrameBegin` / desmontar `C3D_FrameDrawOn`/`main()` do `simple_tri`). `mvn -o test` verde (arm-jitter 1502+13, n3dsemu 180), G5 revalidado (gbaemu 240, ndsemu 183). Ver **Resultado** na task |
| ~~P14.6~~ | ~~**G5.2**~~ ✅ fechada 2026-08-21 — **o triângulo do `simple_tri` APARECE** (validado por captura de tela) | `trilha-g-3ds/g5.2-simple-tri-causas-reais.md` | n3dsemu | G5.1 | Investigação dirigida a pedido do usuário ("achar TODAS as causas de uma vez, não uma por sessão"): eram **7 bloqueios INDEPENDENTES em série** — por isso as 3 sessões da G5.1 pareciam "andar e voltar", cada fix era real e destravava exatamente um passo. (1) só `PDC0` era gerado, mas `C3D_FrameSync` do citro3d exige que os DOIS contadores de VBlank avancem (`|| `, não `&&`) — a fila GX ficava 100% zerada para sempre; a G5.1 tinha olhado só `frameCounter[0]`. (2) entradas da fila GX começam em `0x20`, não `0x8`. (3) o byte 1 do cabeçalho é CONTAGEM de pendentes e o GSP tem que decrementá-la, senão o cliente nunca mais dispara `TriggerCmdReqQueue`. (4) `CommandListParser` não pulava a palavra de padding de comandos com nº ÍMPAR de extras. (5) endereços da GPU são FÍSICOS e os da CPU VIRTUAIS — VRAM `0x1F000000` nem estava mapeada e o heap linear não espelhava o FCRAM, todo atributo saía zerado (resolvido com `mapMirror`). (6) `GPUREG_VS_ENTRYPOINT` sem máscara (shader nunca rodava) + ordem de componentes de uniform float INVERTIDA (`w,z,y,x`, projeção com `w=0` → todo vértice `NaN`) + float24 empacotado não implementado. (7) atributos FIXOS (`GPUREG_FIXEDATTRIB_*`, classe `FixedAttributes` nova) inexistentes — a cor do `simple_tri` é fixa, o triângulo saía preto sobre preto — e permutação aplicada aos 12 slots em vez de `max_attribute_index+1`, zerando `v0`. Extra: `VulkanRenderer` não descarta mais a geometria e ignora o framebuffer zerado do guest — era o "flash" relatado. `mvn -o test` verde (**184**, +4). Nenhum arquivo do arm-jitter tocado, G5-invariante não se aplica. **G5.3, mesma sessão** (commit `3ad9634` no n3dsemu): `GX_MemoryFill` implementado de verdade (respeita `GX_FILL_TRIGGER` e a largura 16/24/32 bits nos 2 buffers) + `ColorBufferFormat` nova (⚠️ NÃO é o mesmo enum de `PixelFormat` — códigos 2/3 trocados) + `PicaRenderer#setClearColor`: o fundo agora é o azul `0x68B0D8` do app, tela idêntica ao hardware real. `mvn -o test` verde (**188**). **Pendências → PR4**: `DisplayTransfer` real, sampler/TEV, `SH_OUTMAP` |
| ~~P14.7~~ | ~~**G5/PR4**~~ ✅ fechada 2026-08-21 — TEV real no pipeline, texturas, `SH_OUTMAP`, e o levantamento dos 20 exemplos | `trilha-g-3ds/g5-pr4-levantamento-exemplos.md` (relatório) · `g5-pica200-render.md` (spec) | n3dsemu | G5.3 | commit `d530382`. **TEV ligado de verdade**: o GLSL agora é GERADO da configuração real e compilado em runtime, com pipeline+módulo cacheados por `TevConfig` — que deixou de usar arrays porque um record com array compara por IDENTIDADE e o cache erraria sempre (a spec avisa que é o erro clássico). O gerador ganhou os **operandos** por fonte (a armadilha explícita da spec), cor constante por estágio, buffer de cor combinada, saturação `[0,1]` por estágio e teste de alpha como `discard`. **Texturas**: `TextureUnits` novo (3 unidades, offsets irregulares) + desembaralho de Morton + 4 bindings de amostrador no Vulkan, descritor por chamada com pool por frame-in-flight. **`SH_OUTMAP` granular** (`OutputMap` novo): saída distribuída por SEMÂNTICA em vez de presumir `o0`=posição/`o1`=cor — é o que dá acesso às coordenadas de textura. **2 simplificações a menos**: `drawTriangles` ACUMULA por tela (um quadro real emite dezenas) com render pass `LOAD_OP_LOAD` nas subsequentes, e `Screen.TOP` fixo virou tela APRENDIDA do `GX_DisplayTransfer` (`both_screens` passou a desenhar). **`--report` novo** (headless, conta desenhos/vértices/fundo/texturas) é como o levantamento foi feito sem GPU. `mvn -o test` verde (**199**, +6). **Levantamento: 7 dos 20 exemplos produzem geometria, 1 validado visualmente.** Maior achado: 5 exemplos (incl. `textured_cube`) morrem da MESMA causa — `curQueue->entries` NULL em `gxCmdQueueDoCommands`, suspeita de dessincronia de contagem de interrupções da fila GX; **candidato a `G6.1`, destrava 5 de uma vez**. Ver o relatório para a tabela completa e o backlog gráfico derivado |
| ~~P14.8~~ | ~~**docs PICA200**~~ ✅ 2026-08-21 — `n3dsemu/docs/PICA200.md` | — | n3dsemu | — | A pedido do usuário, que perguntou se valeria um repo separado só de tradução PICA200→Vulkan. **Recomendação registrada: NÃO** — o `arm-jitter` tem 5 consumidores reais (foi isso que pagou o split), a PICA200 teria exatamente um, para sempre; e as 7 causas da G5.2 cruzaram a fronteira o tempo todo (3 delas estariam do lado do n3dsemu). Além disso `VertexPipeline`/`GxCommandQueue` leem memória pelo `AddressSpace` do arm-jitter, então um repo "sem relação com ARM" dependeria do arm-jitter mesmo assim. **O benefício de documentação, que era o argumento real, foi entregue sem custo arquitetural**: `docs/PICA200.md` reúne fila GX, virtual×físico, lista de comandos, shader/float24/outmap, atributos fixos, TEV e texturas — e é a fronteira que um módulo Maven interno usaria, se um dia o usuário quiser a barreira imposta pelo compilador (meio-termo sugerido, task própria) |
| ~~P14.9~~ | ~~**B3.9**~~ ✅ fechada 2026-08-21 — VFP `VNMLA`/`VNMLS`: gap de decode que derrubava 5 exemplos do n3dsemu | `trilha-b-arquiteturas/b3.9-vfp-vnmla-vnmls.md` | arm-jitter | B3.5 ✅ | Veio do levantamento da G5/PR4, que registrou uma **hipótese ERRADA** (dessincronia da fila GX). O que resolveu foi MEDIR: instrumentar a fila mostrou 1:1 perfeito no `simple_tri` e ZERO atividade no `textured_cube` (morre antes de submeter qualquer comando), e capturar os registradores na parada deu **`pc=0x4, sp=0x0`** — vetor de instrução indefinida com o SP bancado do modo UND nunca inicializado. Com `LR_und = PC+4`, a desmontagem dos 5 binários em `lr-4` mostrou todos na MESMA instrução: `VNMLS` (`.f64` em 4, `.f32` no `loop_subdivision`), gap documentado explicitamente no `VfpDecoder` desde a B3.5. Implementado nos 2 backends. ⚠️ Duas armadilhas fixadas por teste: **`VNMLS` não é `VMLS` com sinal trocado** (nega o ACUMULADOR, não o produto) e **`bit6` tem polaridade INVERTIDA** em `op1==0b001` vs. os vizinhos. Não fundidas, como MLA/MLS. `mvn -o test` verde (**1506**, +4) + `install`. **G5 revalidado**: gbaemu 240 ✅, ndsemu 183 ✅, n3dsemu 199 ✅, virtual-arm-box 87 ✅, armbox 40/41 (falha `Armv7TortureTest` PRÉ-EXISTENTE, confirmada com `git stash` nesta sessão). **Efeito**: `loop_subdivision` 0 → 501 desenhos / 334 mil vértices / textura 32×32 — primeiro exemplo com textura real a renderizar, fecha a validação visual do caminho de textura da PR4; levantamento passou a **8 de 20**. Próximo: mesma técnica nos 4 que agora param mais adiante |
| ~~P14.10~~ | ~~**E5**~~ ✅ fechada 2026-08-21 — `docs/COBERTURA-ISA.md`: tabela de cobertura de ISA GERADA POR MEDIÇÃO | `trilha-e-manutencao/e5-cobertura-isa.md` | arm-jitter | — | Pedido do usuário para parar de descobrir instrução faltante uma por vez (B6.8/B6.9/B6.10/B6.11/B6.12/B3.9 = 6 tasks, 6 investigações). **Não é tabela escrita à mão**: o inventário vem dos `decodetree` do QEMU, cada instrução vira um encoding representativo e é sondada no decoder REAL, por preset de `ArmArchitecture`. 3 estados: ✅ / ❌ / ⚠️ (decodifica como OUTRA coisa). Regenera com `./gerar-cobertura-isa.sh`. ⚠️ **Licença**: os `.decode` do QEMU são GPL e este repo é BSD — NÃO são versionados, o script baixa para `target/` (gitignored); só a tabela gerada fica no repo. **🔴 ACHADO na 1ª execução**: `ArmDecoder` decodifica `0xF2000000` (`VHADD` de NEON) como **`AND` cond=`AL`** — o espaço INCONDICIONAL (`cond==0b1111`: NEON, `PLD`, `BLX` imediato, `CPS`, `SETEND`, barreiras) não é reconhecido como espaço próprio, e tudo que não está explicitamente tratado vira outra instrução em SILÊNCIO, onde o hardware levantaria instrução indefinida. Pior que "não suporta" — **candidato a `E6`**. Panorama: A32 89% v7-A · T32 75% v7-A · VFP 44% MPCore · A64 **18%** · NEON/MVE/SVE/SME **0%**. Validado contra verdades conhecidas (`VNMLS` ✅ pós-B3.9, `_hp` ❌; `CCMP`/`LSLV` ✅ em A64). Mede decode, NÃO semântica |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA a pedido do usuário em 2026-08-18** — 6 sessões de diagnóstico sem fechar M3 (ver resumo abaixo); a sessão 6 tentou usar `qemu-system-arm -M raspi1ap` como oráculo e o PRÓPRIO QEMU travou aos 2,5s com este kernel/DTB (lacuna do modelo `raspi1ap`, não bug nosso) — sinal de que este `kernel.img` real (6.18.33) pode estar puxando periféricos/caminhos de código bem além do que emuladores minimalistas cobrem sem esforço desproporcional. **Não pegar automaticamente** — só retomar se o usuário priorizar de novo, possivelmente reavaliando o kernel/DTB alvo em vez de insistir no mesmo |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA junto com F3** (mesmo repo, regra 6 de serialização — sem sentido priorizar isolada enquanto F3 está parada) |
| ~~P18~~ | ~~**B6.6.7**~~ ✅ fechada 2026-08-18 — AArch64: superfície mínima de EL1 para kernel real | `trilha-b-arquiteturas/b6.6.7-aarch64-el1-kernel-surface.md` | arm-jitter | B6.6.4 | priorizada pelo usuário em resposta direta ao bloqueio achado pela F11; estendeu `Aarch64SystemRegisterId` (identidade da CPU resolvida intrinsecamente + timer genérico host-pluggável), decodificou `WFI`/`WFE`/hints/`HVC`/`SMC`, confirmou `ERET` já existente (B6.6.4, sem mudança), e deu a `Aarch64Core` um mecanismo mínimo de IRQ (`interruptLine`/`enterIrq`, espelho de `enterMemoryAbort`) + `PstateRegister.irqDisabled` (bit `I` de `DAIF`) — GIC/PSCI ficam fora, são responsabilidade do hospedeiro. `mvn -o test` verde (core 1380 + truffle 13), `mvn -o install` local feito, G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava F11 e B6.6.6** |
| ~~P17b~~ | ~~**B6.8**~~ ✅ fechada 2026-08-20 — AArch64: `CCMP`/`CCMN` (decode gap achado pela F11) | `trilha-b-arquiteturas/b6.8-aarch64-conditional-compare.md` | arm-jitter | B6.3.1 ✅ | Encoding CONFERIDO contra `a64.decode`/`translate-a64.c` reais do QEMU antes de codificar (os "Fatos de referência" recordados de memória na spec bateram campo a campo com a fonte real, inclusive `S=1` fixo e `Rn` nunca `SP`). `case` novo em `decodeDataProcessingRegister` + record único `Ir64Op.ConditionalCompare` (cobre registrador e imediato, D1) + executor reaproveitando `addWithFlags`/`subWithFlags` já existentes (D2, sem duplicar cálculo de flags). Corpus real estendido com as 4 combinações (`CCMP`/`CCMN`)×(registrador/imediato) em `W`/`X`, incl. o vetor literal `ccmp x18,#0,#0xd,pl` da F11. `mvn -o test` verde (core 1421 + truffle 13), `mvn -o install` local feito. G5 não se aplica (nenhum arquivo 32-bit tocado; backend ASM A64 não ganhou suporte a este `Kind`, cai no interpretado). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** (retomar `Raspi364Machine`, sessão própria, outro repo — pode haver mais gaps de decode além deste) |
| ~~P17~~ | ~~**F11**~~ 🟡 PARCIAL (2026-08-18, sessão 2) — `Raspi364Machine` implementada, boot bloqueado num gap de decode real (`CCMP`/`CCMN`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.6.7 ✅ | Itens 1-5 do "Inclui" fecharam (máquina/periféricos/DTB completos). Relato minucioso em `FILA-HISTORICO.md` |
| P17c | **F11** 🟡 PARCIAL (2026-08-20, sessão 3) — retomada após B6.8, SEGUNDO gap de decode achado (`Logical (shifted register)`, incl. alias `MOV` de registrador) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.8 ✅ | Com `CCMP`/`CCMN` disponíveis o boot avança até `0x13ba9e8` (`0xaa0003f5` = `ORR X21,XZR,X0` = alias `MOV`), onde bate num gap documentado desde B6.3.1 (`Aarch64Decoder#decodeDataProcessingRegister`, comentário explícito "Logical (shifted register): fora do escopo fechado do épico") nunca implementado. Não é bug, fora do "Inclui" desta task — nenhuma mudança no arm-jitter. `Raspi364BootTest` atualizado (novo endereço/encoding pinado, `@Disabled` reescritos); `mvn -o test` verde no virtual-arm-box (87, 5 skipped), sem regressão em `Raspi1BootTest`/`VersatilePbBootTest`. G5 não se aplica. Relato minucioso em `FILA-HISTORICO.md`. **Sugestão ao usuário**: sub-task nova no arm-jitter (ex. `B6.9`) para "Logical (shifted register)", mesmo rigor de B6.8 — provavelmente destrava mais boot que CCMP sozinho, dado que `MOV` de registrador é onipresente; esperar possíveis gaps adicionais na mesma classe de instruções antes de presumir boot completo |
| P17f | **F11** 🟡 PARCIAL (2026-08-20, sessão 4) — retomada após B6.9, TERCEIRO gap de decode achado (`MRS CTR_EL0`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.9 ✅ | Com "Logical shifted register" disponível o boot avança bem mais longe (`0x13ba9e8`→`0x38fc4`) até `MRS X3, CTR_EL0` (Cache Type Register), fora do subconjunto de registradores de sistema que `Aarch64Decoder#decodeSystemRegisterId` resolve (só EL1 geral + timer genérico `CRn=14`, não o grupo de identificação EL0 `CRn=0`). Não é bug, fora do "Inclui" desta task — nenhuma mudança no arm-jitter. `Raspi364BootTest` atualizado (novo endereço/encoding pinado); `mvn -o test` verde no virtual-arm-box. **Sugestão ao usuário**: sub-task nova no arm-jitter (candidata `B6.10`) para estender `Aarch64SystemRegisterId`/`decodeSystemRegisterId` com `CTR_EL0` (e possivelmente `DCZID_EL0`, mesma família) — esperar mais gaps na mesma classe "System register access" antes de presumir boot completo, mesmo padrão iterativo de B6.8→B6.9 |
| ~~P17g~~ | ~~**B6.10**~~ ✅ fechada 2026-08-20 — AArch64: `CTR_EL0`/`DCZID_EL0` (decode gap achado pela F11) | `trilha-b-arquiteturas/b6.10-aarch64-ctr-el0-dczid-el0.md` | arm-jitter | B6.6.7 ✅ | Fatos de referência CONFERIDOS contra `target/arm/helper.c`+`cpu64.c` reais do QEMU: `CTR_EL0`/`DCZID_EL0` vivem no MESMO grupo `op1=3` do timer genérico, distinguidos por `CRn` (`0`=cache, `14`=timer) — `decodeSystemRegisterId` ganhou um `if` irmão do despacho ao timer. Dois registradores novos resolvidos intrinsecamente pelo `Aarch64Core` (mesmo padrão de `MIDR_EL1`): `CTR_EL0=0x8444_8004` (Cortex-A53 real), `DCZID_EL0=0x10` (só `DZP` setado — `DC ZVA` não implementado, anunciado como desabilitado em vez de expor um gap de decode diferente). Corpus real estendido, 4 testes novos. `mvn -o test` verde (core 1473 + truffle 13), `mvn -o install` local feito. G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** (retomar `Raspi364Machine`, sessão própria, outro repo) |
| ~~P17d~~ | ~~**B6.9**~~ ✅ fechada 2026-08-20 — AArch64: `Logical (shifted register)` (`AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` + alias `MOV`/`MVN`), SEGUNDO gap achado pela F11 | `trilha-b-arquiteturas/b6.9-aarch64-logical-shifted-register.md` | arm-jitter | B6.3.1 ✅ | Spec escrita e executada na MESMA sessão (fatos CONFERIDOS contra `a64.decode`/`translate-a64.c` reais do QEMU via `curl`, não recordados de memória — ao contrário do aviso deixado pela B6.8). Achado real que mudou o escopo: `MOV`/`MVN` (registrador) são alias de disassembly PURO no QEMU — o caminho geral `ORR`/`ORN` com `Rn=XZR` já produz o resultado correto, nenhum decode/executor dedicado foi necessário. Record novo `Ir64Op.LogicalShiftedRegister` + enum `Ir64LogicalShiftType` (não reaproveita `AluShiftedRegister`/`Ir64ShiftType` — precisa de `ROR`, reservado naquele). `ANDS`/`BICS` reaproveitam `Ir64AluOp.AND`+`logicalWithFlags` já existentes (D2, mesma decisão de B6.3.1). Corpus real estendido (offsets `0x3a8`-`0x444`, cobre as 4 combinações de opcode × `n` × os 4 shifts + `W` + os 4 aliases). 48 testes novos (41 decode + 7 executor em arquivo dedicado). `mvn -o test` verde (core 1469 + truffle 13), `mvn -o install` local feito. G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** — próxima sessão deve esperar mais gaps na mesma classe "Data Processing — Register" antes de presumir boot completo |
| ~~P17h~~ | ~~**B6.11**~~ ✅ fechada 2026-08-20 — AArch64: `LSLV`/`LSRV`/`ASRV`/`RORV` (deslocamento variável), QUARTO gap achado pela F11 | `trilha-b-arquiteturas/b6.11-aarch64-shift-variable.md` [ainda não escrita como spec formal — implementada direto, mesmo padrão ad-hoc de B6.9] | arm-jitter | B6.3.1 ✅ | Mesmo subgrupo "Data-processing (2 source)" de `SDIV`/`UDIV` (`decodeDataProcessingRegister`, comentário já existente desde B6.3.3 apontando o gap). Encoding CONFERIDO contra `a64.decode` real do QEMU antes de codificar: `opcode(15:11)=00100`(`LSLV`/`LSRV`)/`00101`(`ASRV`/`RORV`), bit10 distingue dentro do par — os bits `[11:10]` batem exatamente com a ordem de `Ir64LogicalShiftType` (B6.9), reaproveitado em vez de um enum próprio. Record novo `Ir64Op.ShiftVariable`; executor reaproveita `applyLogicalShift` já existente, mas a quantidade vem de `Rm` EM TEMPO DE EXECUÇÃO (`mod` largura), não de um campo resolvido pelo decoder — diferença central vs. `LogicalShiftedRegister`. Backend ASM A64 não ganhou suporte a este `Kind` (cai no interpretado, mesma decisão de B6.9). Corpus real estendido (offsets `0x450`-`0x470`, incl. o vetor literal `lsl x2, x2, x3` = `0x9ac32042` achado em `0x38fd4` do `kernel8.img` real). `mvn -o test` verde (core 1487 + truffle 13), `mvn -o install` local feito. G5 não se aplica. **Destrava a continuação da F11** |
| P17i | **F11** 🟡 PARCIAL (2026-08-20, sessão 5) — retomada após B6.10, fechou B6.11 NA MESMA sessão, QUINTO gap achado: primeira instrução de uma FAMÍLIA nova (`SYS`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.11 ✅ | Com `CTR_EL0` disponível (B6.10) o boot avançou `0x38fc4`→`0x38fd4` até `LSL X2,X2,X3` (alias `LSLV`) — fechado NA MESMA sessão pela B6.11 acima (não precisou de sessão separada). Com `LSLV`/`LSRV`/`ASRV`/`RORV` disponíveis, o boot avança `0x38fd4`→`0x39000` até `0xd5087620`, confirmado via `aarch64-none-elf-as`/`objdump` (G1, mesmo oráculo real de sempre) como `DC IVAC, X0` (Data Cache Invalidate by VA to PoC). **Diferença de escopo desta vez**: não é uma instrução isolada — é a PRIMEIRA instrução da classe inteira "System instructions" (`SYS`/`SYSL` genérico, `op0=1` no encoding, `Aarch64Decoder` hoje só resolve `MRS`/`MSR` de registrador nomeado via `decodeSystemRegisterId`, nunca viu esse `op0`); `DC`/`IC`/`AT`/`TLBI` são todas variantes do MESMO encoding, distinguidas só por `CRn`/`CRm`/`op1`/`op2` — esperado que mais variantes apareçam à frente antes de qualquer marco de boot (cache maintenance é onipresente em `head.S`/`cache.S`). Não é bug, fora do "Inclui" desta task — nenhuma mudança adicional no arm-jitter nesta sessão além do commit separado de B6.11. `Raspi364BootTest` atualizado (novo endereço/encoding pinado, Javadoc com o histórico completo das 5 sessões); `mvn -o test` verde no virtual-arm-box (88). **Sugestão ao usuário**: sub-task nova no arm-jitter (candidata `B6.12`), desta vez dimensionada para a família `SYS`/`DC`/`IC` inteira de uma vez (não uma instrução por sessão como B6.8-B6.11) — considerar se `DC`/`IC`/`TLBI` podem virar NO-OP aceito dado que a MMU/TLB do `arm-jitter` já são "sempre coerentes" por construção, em vez de reimplementar semântica de cache real (CONFERIR antes de presumir) |
| ~~P17j~~ | ~~**B6.12**~~ ✅ fechada 2026-08-20 — AArch64: manutenção de cache `IC`/`DC` (NOP), QUINTO gap achado pela F11 | `trilha-b-arquiteturas/b6.12-aarch64-cache-maintenance.md` | arm-jitter | B6.6.3 ✅ | Hipótese da F11 CONFIRMADA contra `helper.c` real do QEMU antes de codificar: as 10 operações `IC`/`DC` de manutenção de cache (`IC IALLUIS`/`IALLU`/`IVAU`, `DC IVAC`/`ISW`/`CVAC`/`CSW`/`CVAU`/`CIVAC`/`CISW`) são TODAS `ARM_CP_NOP` no próprio QEMU ("Cache ops: all NOPs since we don't emulate caches"). `Aarch64Decoder#decodeSystemInstructionSys` ganhou uma tabela `{op1,crm,op2}` (`SYSTEM_INSTRUCTION_CACHE_OPS`) cobrindo as 10 combinações reais; `Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP` nova, mesmo ramo vazio de `BARRIER`/`NOP_HINT` no executor. **`DC ZVA` deliberadamente EXCLUÍDA** (única do grupo com efeito observável real — zera memória — já anunciada como indisponível via `DCZID_EL0.DZP=1`, B6.10); continua lançando `unsupported`, testado como regressão negativa com o vetor real `0xd50b7420`. `AT`/`TLBI` per-VA ficam fora (nenhum uso encontrado até este ponto). Corpus real estendido (offsets `0x474`-`0x498`, incl. o vetor literal `DC IVAC, X0`=`0xd5087620` da F11). `mvn -o test` verde (core 1498 + truffle 13, +11), `mvn -o install` local feito. G5 não se aplica (nenhum arquivo 32-bit tocado). **Confirmado nesta sessão** (`mvn -o test` no `virtual-arm-box`, sem editar nada lá): o `Raspi364BootTest` pinado antigo agora FALHA porque a exceção esperada em `DC IVAC` não ocorre mais — prova que o gap fechou e o boot avança além dele; achar o próximo gap e atualizar aquele teste fica para uma sessão própria no `virtual-arm-box` (repo diferente, regra 6). **Destrava a continuação da F11** |
| ~~P17k~~ | ~~**E4**~~ ✅ fechada 2026-08-20 — `AddressSpace64.Wrapping` rejeitava endereços válidos `[0x8000_0000,0xFFFF_FFFF]`, SEXTO achado pela F11 (bug real, não gap de feature) | — (commit direto, sem spec formal — mesmo padrão do fix `ArmCore#enterMemoryAbort` na F3 sessão 5) | arm-jitter | — | Retomando F11 após B6.12, o boot avançava ~157s/1.456.350 fatias e lançava `ArithmeticException: integer overflow` em `AddressSpace64.Wrapping.read32` (`Math.toIntExact`). Isolado com harness de debug temporário (descartado): o endereço SEMPRE caía dentro de `[0x8000_0000,0xFFFF_FFFF]` — dentro dos "4 GiB baixos" que o próprio Javadoc da classe promete suportar, só interpretados incorretamente como `long` positivo `> Integer.MAX_VALUE`. Corrigido truncando com `(int)` (preserva o padrão de bits, convenção "endereço = int sem sinal" do resto de `AddressSpace`) em vez de `Math.toIntExact`; só lança de verdade acima de 4 GiB. Teste de regressão novo `AddressSpace64WrappingTest` (core). `mvn -o test` verde (core+truffle), `mvn -o install` local feito. G5 revalidado nesta sessão: gbaemu ✅, ndsemu ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`, não relacionada), virtual-arm-box ✅ |
| ~~P17l~~ | ~~**F11** sessão 6~~ — achou e fechou o SEXTO gap (bug `Wrapping`, ver E4 acima), levantou a hipótese `TTBR1_EL1` para o SÉTIMO bloqueio (ver B6.13 abaixo, hipótese REFUTADA) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | E4 ✅ | Com o fix da E4 instalado localmente, o boot avança ainda mais longe e um `write64` (`STP`, `executeLoadStorePair`) tenta traduzir para um PA genuinamente acima de 4 GiB (`0x1_0000_0882`) — desta vez uma rejeição correta do `Wrapping`. Hipótese registrada (não confirmada nesta sessão por orçamento): ausência de `TTBR1_EL1`. `Raspi364BootTest` atualizado, `mvn -o test` verde no virtual-arm-box. Ver **B6.13** abaixo — a hipótese foi investigada na sessão seguinte e REFUTADA com instrumentação real |
| ~~P17m~~ | ~~**B6.13**~~ ✅ fechada 2026-08-20 (por REFUTAÇÃO, não implementação) — hipótese `TTBR1_EL1` da F11 sessão 6 é FALSA | `trilha-b-arquiteturas/b6.13-aarch64-ttbr1-el1.md` | arm-jitter | — | Spec escrita e investigada na MESMA sessão (mesmo padrão de B6.9/B6.12). Instrumentação temporária em 3 pontos (`TranslatingAddressSpace64.leaf`/`translateData`, `Ir64BlockExecutor.executeLoadStorePair`) provou que o `write64` problemático (`STP` em `pc=0x13b8200`, PA `0x100000882`) ocorre com **`mmuEnabled=false`** — a MMU está desligada neste ponto do boot, então NENHUM page-walk acontece (nem TTBR0 nem um TTBR1 hipotético); `SP` (`X31`) já contém `0x100000872` (acima de 4 GiB) ANTES do `STP`, e nem `bit55` nem `bit63` do endereço estão setados (refutando também o detalhe "VA alto/bit 63" da hipótese original). **Causa raiz real**: conteúdo de `SP` corrompido/inesperado nesse ponto — bug de aritmética/carga de registrador anterior, não de tradução de endereço. Fatos de referência do QEMU real conferidos mesmo assim (`target/arm/helper.c`: `TTBR1_EL1` = mesmo `CRn`/`CRm` de `TTBR0_EL1`, só `op2=1`; `aa64_va_parameters`: seleção real usa **bit 55**, não bit 63 como a hipótese da F11 citava) — registrados na spec para uma eventual implementação futura de `TTBR1_EL1` por outro motivo. **`TTBR1_EL1` NÃO foi implementado** (implementar sobre uma causa raiz errada seria trabalho desperdiçado, mesmo precedente de G4.1/`n3dsemu`). `git diff` limpo em ambos os repos ao final (instrumentação toda revertida). `mvn -o test` não precisou rodar de novo (nenhuma mudança de código). **Sugestão ao usuário**: task nova (candidata `B6.14`) para rastrear a origem do valor de `SP` em `pc=0x13b8200` (trace reverso de qual instrução escreveu esse valor) — ver a spec da B6.13 para o "próximo passo" completo |
| P17n | ~~**B6.14**~~ ✅ fechada 2026-08-24 — causa raiz real achada e corrigida: `ADD`/`SUB` (immediate) resolvia `Rd\|SP`/`Rn\|SP` pela FLAG, não pelo ÍNDICE | `trilha-b-arquiteturas/b6.14-aarch64-alu-immediate-sp-bug.md` | arm-jitter | B6.1 ✅ | Investigação por LEITURA DE CÓDIGO (sem reinstrumentar o boot de 1,4M+ fatias da F11) confirmou a causa raiz sugerida pela B6.13 e fechou também a pendência #7 do `tasks/README.md` (achada em 2026-07-25, nunca corrigida): `Ir64BlockExecutor#executeAlu` (forma imediata de `ADD`/`SUB`) checava só a flag `dstIsStackPointer`/`src1IsStackPointer` do decoder, nunca o índice do registrador (`==31`) — diferente de `executeAluExtendedRegister` (B6.3.1), que já fazia a checagem dupla certa. Como `src1IsStackPointer` é sempre `true` nesta forma (classificação correta do encoding — `Rn` é sempre `Rn\|SP`), `operand1` sempre lia `SP`, ignorando `Rn`; e como `dstIsStackPointer=!setFlags`, qualquer `ADD`/`SUB` imediato sem `S` (o mais comum) gravava em `SP` em vez de `Rd` — exatamente o bug que corrompe `SP` ao longo do boot até o `STP` em `pc=0x13b8200` (B6.13). Fix: mesma checagem dupla (`&& op.dst()/op.src1() == ALU_STACK_POINTER_ENCODING`) que `executeAluExtendedRegister` já tinha; cobre os dois backends (interpretado + ASM nativo, que delega ao mesmo executor via D-ASM) sem tocar codegen. 3 testes de regressão novos (encodings conferidos com `aarch64-none-elf-as`/`objdump` real). `mvn -o test` verde (arm-jitter completo) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). **Não** re-executou o boot completo da F11 (fora do orçamento desta sessão) — retomar a F11 com este fix instalado fica para uma sessão própria no `virtual-arm-box` |

| P17o | ~~**E7**~~ ✅ fechada 2026-08-26 — A64 JIT: 2 bugs reais faziam exceções de guest escaparem para o host (retomando a F11 pós-B6.14) | `trilha-e-manutencao/e7-a64-jit-guest-exceptions-escaping-to-host.md` | arm-jitter | — | `Ir64BlockCompiler` nunca cercava o bloco nativo com `try/catch` (precedente 32-bit `AsmBlockCompiler` tem desde B4.1.3); `JitRuntime64#execute` nunca protegia o `lift()` de um bloco quente (precedente 32-bit `LIFT_FAULT_CYCLES` desde B4.1.5) — este era a causa REAL do `TRANSLATION_FAULT_L3 em 0x200` que travava o boot JIT da F11 sessão 7. 2 testes de regressão novos, confirmados falhando sem o fix. `mvn -o test` verde + `install`; G5 nos 5 consumidores ✅. Sem release publicado. **Efeito no F11 (sessão 8, medido localmente)**: JIT deixa de lançar QUALQUER exceção e roda o orçamento completo (114s) — mas não alcança o marco, mesmo bloqueio "lento vs. preso" do INTERPRETED. `virtual-arm-box` segue em `1.1.0` (Central) — reabilitar `reachesEarlyconBannerJit` só depois que este fix for publicado (F5) e consumido (F7). Ver **Resultado** na task |

✅ **B9.8.2 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.2-hvc-real.md`, priorizada como próximo
item da escada B9.8, sem dependência pendente — B9.8.1 já fechada) — `HVC` real (A32 e T32):
`ArmFeature.HYPERVISOR_CALL` novo (só `ARMV7A`, gate real confirmado no QEMU: `ENABLE_ARCH_7 &&
!M_PROFILE`, NÃO exige V7VE); `UNDEFINED` em modo `USER`, entra em Hyp mode (`ELR_hyp`=retorno, não
`LR` — B9.8.1 já tinha o banking; `SPSR_hyp`=CPSR antigo; vetor fixo `0x14`) em qualquer outro modo.
Encodings reais via `arm-none-eabi-as -march=armv7ve`. Achado real (corrigido ainda na escrita dos
testes, nunca chegou a produção): `LR` em modo `SUPERVISOR` tem banco PRÓPRIO, diferente do
`LR_usr`/`LR_sys` compartilhado com Hyp mode — testar a preservação de `LR` exige escrevê-lo a
partir de `USER`/`SYSTEM`, não de `SUPERVISOR`. `mvn -o test` verde (2276, +8) + `install`; G5
completo nos 5 consumidores ✅ (virtual-arm-box é o único consumidor A32 real, sem regressão em
nenhum boot). Sem marco de release. Ver **Resultado** na task. **Próximas da escada, qualquer
ordem**: `B9.8.3` (`SMC`), `B9.8.4` (`ERET` A32), `B9.8.5` (`MRS_bank`/`MSR_bank`).

✅ **B9.8.3 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.3-smc-real.md`, spec escrita e
executada nesta sessão — próximo item sem dependência pendente da escada B9.8) — `SMC` real (A32 e
T32): `ArmFeature.SECURE_MONITOR_CALL` novo, gate `ARMv6K` (mais antigo que `HVC`/`ARMv7`, herdado
já por `ARM11_MPCORE`); entra em Monitor mode via `LR_mon` (banco normal, SEM branch especial em
`enterException` — ao contrário de `HVC`/`ELR_hyp`), vetor fixo `0x08` (colide com `SWI` no esquema
sem `MVBAR`, documentado, inofensivo hoje). **Bug real de dispatch T32 corrigido (G8)**: `SMC` e
`UDF.W` compartilham o mesmo prefixo de `hi` (`0xF7Fx`) — `decodeUdf` engolia todo `SMC` em `null`
sem chance de outro branch reconhecê-lo; corrigido com fallback para `decodeSmc` quando o `lo` de
`UDF` não bate. Encodings reais via `arm-none-eabi-as -march=armv7ve`. `mvn -o test` verde (+11) +
`install`; G5 completo nos 5 consumidores ✅. Sem marco de release. Ver **Resultado** na task.
**Próximas da escada, qualquer ordem**: `B9.8.4` (`ERET` A32), `B9.8.5` (`MRS_bank`/`MSR_bank`).

## F3 (`virtual-arm-box --machine=raspi1`) — resumo (histórico minucioso movido para `tasks/FILA-HISTORICO.md`)

M1 e M2 ✅ fechados (JIT e INTERPRETED). M3 (shell interativo): a sessão de
`FdtPatcher#withNodeDisabled` (2026-08-17) fechou DOIS bloqueios reais — retry infinito de
`mmc0`/`sdhost` e uma espera síncrona silenciosa de `usb`/`dwc_otg` — desabilitando os nós
`mmc@7e202000`/`usb@7e980000` no `.dtb` via `status = "disabled"`. Com os dois fechados, o boot
chega de novo a `"Run /init as init process"`, mas revelou um TERCEIRO bloqueio.

**Sessão de diagnóstico 1/2 (2026-08-18)**: amostragem barata (sem trace completo) descartou `WFI`
sem IRQ e tempestade de IRQ — a CPU fica `RUNNING`/`SUPERVISOR` o tempo todo. Histograma de PC
mostrou o console parando de crescer bem cedo enquanto a CPU segue ativa presa em ~20 PCs
estáveis. Desmontagem estática levantou a hipótese (NÃO confirmada) de bug de `LDREX`/`STREX`/DACR
no `arm-jitter`.

**Sessão de diagnóstico 2/2 (2026-08-18)**: trace instrução-a-instrução (fast-forward JIT +
`ArmCore#step()`) **DESCARTOU** essa hipótese com evidência dinâmica: o loop real é um corpo
determinístico de 157 instruções em `0xc05b1750`-`0xc05b18c4` (não o código hipotetizado por
disassembly estático) onde TODOS os registradores amostrados (`r0`-`r4`/`r6`/`r9`/`r13`/`r14`)
voltam bit-a-bit idênticos a cada período (20+ repetições); DACR faz round-trip correto
(`0x55`→`0x55`) e `LDREX`/`STREX` sucedem de primeira em 2 call-sites distintos, sem retry — não é
bug de `arm-jitter`. Timer ainda entrega IRQ nesta janela (27 em 100k `runSlice`). Achado colateral:
o "Division by zero" já documentado (`pl011_set_termios`) acontece 2x ao abrir `/dev/console`,
não-fatal; o travamento real é logo depois, dentro do `execve("/init")`, antes de qualquer saída do
PID 1. Próximo passo: identificar a 3ª sub-rotina chamada pelo loop (`0xc02529b4`, ainda não
identificada) e inspecionar CONTEÚDO DE MEMÓRIA (não só registradores) — a condição de saída não
depende de nenhum registrador de propósito geral observado. Ver Javadoc de `Raspi1BootTest`
(`virtual-arm-box`) para o achado completo. `mvn -o test` verde no `virtual-arm-box`; nenhum
arquivo do `arm-jitter` tocado (hipótese de bug real ali agora descartada, não só não confirmada).

**Sessão de diagnóstico 3 (2026-08-18)**: seguiu o passo (b) — inspeção de CONTEÚDO DE MEMÓRIA, não
só registradores, em `[0x0014622d]` (alvo do `strb` de prova) e `[0xc1558c2c]` (contagem do
`rw_semaphore`). Rodando com um orçamento de estagnação maior, o console progrediu MAIS do que
qualquer sessão anterior tinha visto (`thermal thermal_zone0: ...` em kernel time 699s, muito além
do "silêncio" pós-`Run /init` documentado antes em 482s) antes de travar de vez no mesmo loop de 157
instruções. Nos 12 períodos observados (1805 passos), as DUAS memórias vigiadas ficaram **bit-a-bit
estáticas** (`0x00002d00`/`0x00000100`) — fecha a lacuna da sessão anterior: não há progresso
invisível em memória nesses dois pontos. `rw_semaphore` travado com exatamente 1 leitor
(`0x100` = `RWSEM_READER_BIAS`), nunca liberado. Hipótese refinada: o bug provavelmente está no
CHAMADOR do loop (que deveria avançar um contador/endereço entre chamadas e não avança), não na
subrotina chamada em si. Próximo passo recomendado: ler `thread_info->flags`/`preempt_count` (via
`lr = *(TPIDRURO+0x520)`) para checar `TIF_NEED_RESCHED`, ou tracear o PRIMEIRO período (não os
últimos) para ver o valor inicial do "laço externo". `mvn -o test` verde no `virtual-arm-box`;
harness temporário não commitado; nenhum arquivo do `arm-jitter` tocado. F3 segue 🟡 na fila
"ATUAL".

**Sessão de diagnóstico 4 (2026-08-18, `0a745ff`)**: rotina IDENTIFICADA por correspondência
byte-a-byte contra o fonte real do kernel (`arch/arm/lib/uaccess_with_memcpy.c`, baixado via `curl`
direto ao GitHub — `WebFetch`/Bootlin não serve fonte cru) — o loop de 157 passos é
`__copy_to_user_memcpy()`/`pin_page_for_write()`, chamado por `arm_copy_to_user()` durante
`execve("/init")` copiando `argv`/`envp`. Instrumentação de `onMemoryAbort` durante ~5,1M fatias
mostrou **exatamente 1 abort em todo o boot**, no `strb` esperado (`0xc05b189c`) e no endereço
esperado (`0x0014622d`) — prova que o `AP`/DACR do `arm-jitter` FUNCIONA (falta entregue, corrigida,
escrita nunca mais falta), mas `pin_page_for_write()` continua falhando para sempre porque os bits
de contabilidade SOFTWARE da PTE (`pte_young`/`pte_dirty`/`pte_write`) nunca refletem o conserto —
descarta de vez a hipótese de bug genérico de permissão/DACR (3 sessões já tinham testado isso).
Próximo passo: dump da palavra de PTE de `0x0014622d` antes/depois do abort, comparado bit a bit
contra `arch/arm/include/asm/pgtable-2level.h` (mesma técnica de `curl` desta sessão). Ver Javadoc de
`Raspi1BootTest` para o detalhe completo. `mvn -o test` verde (78, 2 skipped); nenhum arquivo do
`arm-jitter` tocado.

**Sessão de diagnóstico 6 (2026-08-18) — tentativa de oráculo QEMU, sem sucesso (achado
negativo)**: antes de partir para a arqueologia de `mm_struct`/maple-tree recomendada pela sessão
5, tentou-se o atalho mais barato: bootar os MESMOS `kernel.img`/`bcm2708-rpi-b.dtb`/
`initramfs.cpio.gz` desta task no `qemu-system-arm -M raspi1ap` (QEMU 8.0.0 instalado) para
comparar. **Não deu para comparar**: o próprio QEMU trava aos 2,5s de tempo de kernel — bem antes
do bloqueio desta task (que só acontece no `execve("/init")`, depois de `mmc`/`usb`) — com um
`external abort on non-linefetch` real dentro do `bcm2835_power_probe` (driver de clock/power),
lacuna própria do modelo `raspi1ap` do QEMU para este kernel 6.18.33, não um travamento do Linux.
Achado negativo registrado (ver Javadoc de `Raspi1BootTest`) para não repetir essa tentativa.
Próximo passo segue sendo o da sessão 5 (dump de VMA via `TPIDRURO`=`current`, confirmado como o
mecanismo certo pelo fonte real do kernel), com uma heurística nova sugerida (procurar
`vm_flags`≈`0x875` por padrão de busca perto do `mm_struct`, em vez de reconstruir a struct campo
a campo sem `vmlinux`/BTF). `mvn -o test` verde; nenhum arquivo do `arm-jitter` tocado.

**Sessão de diagnóstico 5 (2026-08-18, `7355868` arm-jitter + `3ba61f0` virtual-arm-box) — BUG REAL
ISOLADO E CORRIGIDO no `arm-jitter`, M3 ainda NÃO fecha (bloqueio mais estreito revelado logo
depois)**: dump direto da palavra de PTE (`TranslatingAddressSpace#setMmuEnabled(false)`+`read32` =
leitura física crua, walk manual de L1/L2 a partir de `TTBR0`) confirmou `DIRTY=0`/`RDONLY=1` no
endereço travado — bate com a checagem de `pin_page_for_write()`. **Causa raiz**:
`ArmCore#enterMemoryAbort` nunca preenchia `DFSR[11]` (`WnR`, ARM DDI 0406C B3.13.4) — toda falta de
DADOS chegava ao guest indistinguível de uma leitura, mesmo com `accessType()` já disponível no
ponto de chamada. O Linux real usa esse bit para decidir `FAULT_FLAG_WRITE`; sem ele, a falta de
ESCRITA que causou o único abort do boot era tratada como leitura, corrigindo o `AP` de hardware mas
nunca marcando a PTE como `dirty`. **Corrigido** (`ArmCore.java`, aditivo/G3, 2 testes de regressão
novos); `mvn -o test` verde (1369 core+truffle) + `mvn -o install`; G5 revalidado (gbaemu verde,
ndsemu verde, armbox 40/41 — a 1 falha é a mesma pré-existente `Armv7TortureTest`/`VfpRegisters`, não
relacionada). **Efeito medido**: a mesma PTE relida após o fix mostra `DIRTY=1` (fix funcionou) mas
`RDONLY` continua `1` — `pin_page_for_write()` ainda falha, por um motivo mais estreito e ainda não
isolado (candidatos: outro bug real, ou o kernel corretamente recusando escrita numa VMA que ele não
considera `VM_WRITE`, apontando para um problema no setup da pilha do `execve()` em vez do
`arm-jitter`). Ver Javadoc de `Raspi1BootTest` para o próximo passo recomendado (dump da VMA
correspondente). F3 segue 🟡 na fila "ATUAL".

<!-- Histórico minucioso completo da F3 (todas as sessões, começando com o abort storm ARMv6K)
     está em tasks/FILA-HISTORICO.md, seção "F3 (...) — histórico condensado movido de
     FILA-EXECUCAO.md". -->

**Paralelismo permitido nesta onda** (regra 6: repos diferentes, nunca o mesmo checkout):
`P3/P4` (GitHub) ∥ `P2/P5` no começo; depois de P8, `P9` (4 repos) ∥ `P10+` (n3dsemu) ∥
`P15` (virtual-arm-box).

**Bloqueio novo descoberto na G2 (2026-08-15, sessão PR2) — RESOLVIDO 2026-08-15 pela B3.8 do
arm-jitter** (sessão separada, ver índice do `tasks/README.md`): com `svcCreateAddressArbiter`
implementado, `n3dsemu testdata/application.3dsx` progride além da primeira `svc` e travava no
`crt0`/newlib configurando a VFP com um FPSCR que `arm-jitter` rejeitava de propósito
(`FpscrRegister`, decisão nº 3 do épico B3: só IEEE round-to-nearest, sem `RMode`/`FZ`/`LEN`/
`STRIDE`). O usuário escolheu o caminho (1) do leque abaixo (revisitar a decisão de verdade, não
só aceitar-e-ignorar): `FpscrRegister` agora aceita os 32 bits sem lançar, com `RMode` (os 4
modos de arredondamento IEEE 754, via `DirectedFpRounding`) e `FZ` (flush-to-zero) IMPLEMENTADOS
de verdade nos executores VFP interpretados (o oráculo, G1); `LEN`/`STRIDE` são aceitos e
armazenados mas SEM semântica de vetor executada (decisão de escopo explícita da B3.8 — nenhum
consumidor real conhecido usa modo vetor de propósito). `mvn -o install` local do arm-jitter +
`mvn -o test` verde em gbaemu/ndsemu (G5). **Pendente**: uma sessão do n3dsemu precisa confirmar
que `templates/application` progride de verdade além do `crt0` agora — não testado nesta sessão
(fora do repo arm-jitter). Histórico da decisão (não mais em aberto, mantido por rastreabilidade):
três caminhos possíveis haviam sido levantados — (1) revisitar a decisão nº 3 do B3 no arm-jitter
suportando `LEN`/`STRIDE`/`FZ`/`RMode` de verdade — **ESCOLHIDO**; (2) task cirúrgica só para
ACEITAR (ignorar) esses bits sem lançar; (3) mudar o `crt0` do libctru/newlib para não gravar
esses bits — descartado, binário já compilado.

**Investigação 2026-08-16 (sessão n3dsemu, só reconhecimento — não implementou G3):**
confirmado com `mvn -o test`/execução direta de `n3dsemu testdata/application.3dsx
--trace-svc` (arm-jitter `1.0.0` local pós-B3.8, `mvn -o install` já refletido no `.m2`) que o
bloqueio de FPSCR está de fato resolvido — o boot passa inteiro pelo `crt0`/newlib configurando
a VFP sem lançar. **Achou e corrigiu 1 bug real do n3dsemu, não do arm-jitter**: as constantes
`HandleTable.CURRENT_PROCESS_HANDLE`/`CURRENT_THREAD_HANDLE` estavam com os valores
`0xFFFF8000`/`0xFFFF8001` TROCADOS em relação ao header real (`libctru/include/3ds/svc.h`:
`CUR_PROCESS_HANDLE=0xFFFF8001`, `CUR_THREAD_HANDLE=0xFFFF8000`), confirmado também por
`arm-none-eabi-objdump` no `.3dsx` real (`__system_allocateHeaps` passa literalmente
`0xFFFF8001` para `svcGetResourceLimit`, que só aceita handle de processo). Sem a correção,
isso fazia `svcGetResourceLimit` devolver `INVALID_HANDLE` e o guest reagia com
`svcBreak(PANIC)` sozinho, logo após passar da VFP — o que por um instante pareceu "o fix do
B3.8 não bastou", mas na verdade era um bug independente e pré-existente, só exposto porque a
execução finalmente chegou lá. Corrigido em `HandleTable.java` (commit `c939549`), `mvn -o test`
verde (84 testes), `Application3dsxTest` reescrito para documentar a cadeia completa.

**Novo limite real encontrado nesta investigação — FECHADO na sessão de continuação de G2 do
mesmo dia (2026-08-16)**: com o bug de handle corrigido, o boot avançava até `svc 0x39`
(`svcGetResourceLimitLimitValues`), que **não está na lista de SVCs da própria task G2** (só
lista `0x38`/`0x3A`) — mesmo padrão já visto com `svcCreateAddressArbiter` na PR2 (um SVC vizinho
que o crt0 usa e a spec original não previu). O `Main` real finge sucesso e segue (convenção já
estabelecida), então o guest continuava até um `svcControlMemory(MEMOP_ALLOC, addr=0x08000000,
...)` de verdade que devolvia falha para os parâmetros que o crt0 usa — e o guest chamava
`svcBreak(PANIC)` de novo. **Os dois foram corrigidos na sessão de continuação**: (1)
`svcGetResourceLimitLimitValues` implementado com valores de `COMMIT` plausíveis; (2) a causa
raiz real do `ALLOC` falhando não era o teto (embora zero também bastasse) — era um bug
arquitetural preexistente: `MemoryMap.LINEAR_HEAP_BASE`/`NEW_HEAP_BASE` deste projeto tinham os
endereços `0x08000000`/`0x14000000` TROCADOS em relação ao 3dbrew real (`Memory_layout`: o heap
GERAL, sem a flag `LINEAR`, é `0x08000000`; o heap LINEAR de verdade é `0x14000000`), confirmado
via `WebFetch` na wiki antes de corrigir. `n3dsemu testdata/application.3dsx` não panica mais.
Ver índice do `tasks/README.md` (linha G2) para o detalhe completo, incl. o blocker NOVO
encontrado depois (laço de `svcCreateAddressArbiter`). Também achado: `C:\devkitPro\libctru` só
tem `include/`+`lib/*.a` instalados localmente — **sem o código-fonte** que a spec da G3 presume
disponível como oráculo; 3dbrew + desmontagem do `.3dsx` seguem funcionando como substituto, mas
vale avisar quem for executar G3.

**Armazenamento (decidido 2026-08-15):** o `virtual-arm-box` usa **disco virtual em formato
padrão, compatível com outras VMs** — `raw` e **QCOW2**, ambos com leitura e escrita; VDI,
VMDK e VHD/VHDX são atendidos por `qemu-img convert`, sem código nosso. Primeiro controlador:
**PL181 MMCI (SD/MMC)** no `versatilepb`. Task **F10** (P16).

**Achado de ambiente 2026-08-15 — o QEMU 8.0.0 está instalado** (`C:\Program Files\qemu\`,
só binários, sem fonte): `qemu-img` (incl. **`check`**), `qemu-io` e `qemu-system-arm`. É
oráculo externo direto para a F10 (validar imagem QCOW2 que nós escrevemos) e para a F3
(bootar a mesma placa/kernel e comparar log serial). O **código-fonte** do QEMU continua
ausente — quem for transcrever periférico precisa buscá-lo no repositório público.

**[REFINAR] desta onda** (não executar; viram spec nova quando a dependência fechar):
**G6** (ROMs comerciais `.cia`/`.3ds`, bloqueada em dump de `boot9.bin`) e **G7** (trazer o
núcleo Vulkan para o ndsemu — condicional a a G5 dar certo).

- **Nota de ambiente (2026-07-31)**: esta máquina passou a ter GraalVM 25
  (`E:\graalvm-jdk-25.0.3+9.1`) + Visual Studio 2022 com MSVC (`vcvars64.bat`)
  disponíveis — o mesmo ambiente que várias tasks desta fila estavam
  bloqueadas esperando. **A9 PR1** (lib nativa `.dll` com API C, backend
  interpretado) foi executada e fechou ✅ nesta sessão (ver índice do
  `tasks/README.md`) — movida para fora da tabela 🧑 abaixo. **A8**
  (otimizações native-image: PGO/-O3/-march=native/G1, tabela startup/RSS)
  também foi executada e fechou ✅ na MESMA sessão (dependia só de A7, que já
  tinha fechado — a entrada "A7" na tabela 🧑 abaixo estava desatualizada,
  A7 não bloqueia mais nada além de A9 PR2): PGO+`-O3` venceu as 4 métricas e
  virou default do perfil `native` do armbox — ver índice do `tasks/README.md`
  e o README do armbox para a tabela completa. As DEMAIS tasks 🧑 (C7, A9 PR2,
  C10, B4.0.3 item 3, B6.2 aceite #2, B6.6.6) continuam bloqueadas por motivos
  DIFERENTES do ambiente GraalVM+MSVC (validação de gameplay, o bailout SVM do
  Truffle em si — não falta de ambiente —, medição com ROM real, toolchains
  `arm-linux-*`/`aarch64-linux-*`/kernel real) — não presumir que esta nota as
  destrava, conferir a coluna "O que precisa do usuário" de cada uma antes de
  pegar. **Fila automática volta a ficar vazia após A8/A9 PR1** — nenhuma task
  elegível sem prioridade do usuário.

## Épico B6 (AArch64) — histórico condensado

B6.1 até B6.6.5 fecharam entre 2026-07-24 e 2026-07-27 (decoder A64 completo, MMU VMSA64,
modelo de exceção EL0↔EL1, FP/SIMD escalar interpretado+ASM nativo, `translationGeneration`
em `jit64`). **Detalhe completo de cada sub-task: `tasks/FILA-HISTORICO.md`.** Resumo do que
falta:
Backlog sem prioridade definida (não pegar sem o usuário priorizar): B6.4 (backend ASM 64-bit) fechou os 3 PRs (ver histórico abaixo) — só resta,
como pendência EXPLÍCITA fora do escopo de qualquer PR (registrador-cache sem consumidor A64
medido ainda, D0/D-ASM) ou bloqueada no ambiente (bench busybox-aarch64), ver a seção 🧑 abaixo.
B6.5 (FP/SIMD escalar) decomposta em B6.5.1-B6.5.4 (2026-07-26) — **B6.5.1 ✅ fechada
(2026-07-26, ver histórico abaixo)**; **B6.5.2 ✅ fechada (2026-07-27, priorizada pelo usuário,
ver histórico abaixo)** — `Ir64Op`s de FP + executor interpretado; **B6.5.3 ✅ fechada (2026-07-27,
ver histórico acima)** — decoder da classe "Data Processing — Scalar FP" (`bit26=1`); **B6.5.4 ✅
fechada (2026-07-27, ver histórico acima) — fecha o épico B6.5 por completo** (emissão ASM
nativa de FP, `Ir64NativePolicy`/`Ir64BlockCompiler` estendidos, sem meta de performance por
decisão D1). B6.6 (MMU v8 +
hospedeiro `virt64`) decomposta em B6.6.1-B6.6.6 (2026-07-26) — B6.6.1-B6.6.5 já fecharam (ver
histórico acima), **épico quase 100%**; só falta B6.6.6 (hospedeiro `virt64`), que nasceu
bloqueada no usuário (kernel/toolchain `aarch64-linux-*`). **Atualização 2026-08-18 (a pedido do
usuário)**: esse bloqueio de ambiente tem uma rota alternativa mais barata — o repositório
`raspberrypi/firmware` (o mesmo que já deu os assets da F3) publica `kernel8.img`, um kernel
AArch64 pré-compilado real, sem precisar de toolchain nem de GIC/PSCI (o Pi 3 real não usa GIC).
Nova task **F11** (`trilha-f-infra/f11-raspi3-aarch64-machine.md`, repo virtual-arm-box) ataca o
degrau aarch64 por essa rota; **B6.6.6 fica formalmente em espera** (não cancelada) enquanto a
F11 não se esgotar — ver seção 🧑 abaixo (entrada ajustada) e a tabela de Onda 4 para F11.
## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) | **C9** (fastmem ndsemu, `trilha-c-perf/c9-jit-fastmem-ndsemu.md`) |
| ~~A7~~ ✅ fechada 2026-07-27 (medição concluída, resultado misto — ver índice do `tasks/README.md`); entrada mantida só para registrar que **A9 PR2** segue bloqueada pelo PRÓPRIO resultado da A7 (bailout SVM não fechou), não por falta de ambiente | `trilha-a-truffle/a7-native-image-revalidacao.md` | — (fechada; causa raiz do bailout SVM precisa de sessão de modelo forte dedicada) | **A9 PR2** só quando o bailout SVM for corrigido |
| ~~A9 PR1~~ ✅ fechada 2026-07-31 (ambiente GraalVM+MSVC ficou disponível nesta máquina — ver nota abaixo) | `trilha-a-truffle/a9-native-shared-library.md` | — | A9 PR2 segue bloqueada em A7 (bailout SVM do Truffle não fechou) |
| ~~A8~~ ✅ fechada 2026-07-31 (mesma sessão desta nota de ambiente — task mecânica de build+medição, não precisava de validação humana além do ambiente GraalVM+MSVC já confirmado disponível) | `trilha-a-truffle/a8-native-image-otimizacoes.md` | — | PGO+`-O3` promovido a default do perfil `native` do armbox — ver índice do `tasks/README.md` |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real | fecha de vez a C10 |
| **B4.0.3 item 3** — busybox estático Thumb-2 (armbox) | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | Toolchain `arm-linux-*` real (musl/glibc) — ex. WSL com distro configurada + build tools, ou um cross-toolchain Windows-hosted; o musl.cc é ELF Linux (não roda em MSYS2) e o devkitARM instalado é bare-metal | fecha B4.0.3 por completo e destrava **B4.0.5** |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2, item 4) | Fonte confiável de busybox estático arm64/aarch64 real (busybox.net só publica `armv8l`, que é ARM 32-bit — ISA errada) OU um toolchain `aarch64-linux-*` (musl/glibc) para compilar da fonte, já que o devkitA64 instalado é bare-metal (`aarch64-none-elf`) | fecha B6.2 por completo (aceite #1, `hello-aarch64.elf`, já fechado 2026-07-24), **o aceite agregado do épico B6.3** ("`busybox sh -c` completo no armbox64", já com as 4 sub-tasks B6.3.1-B6.3.4 fechadas) **e o bench "busybox ≥3× interpretador" do PR3 de B6.4** (codegen fechado 2026-07-26, só falta medir) — mesmo bloqueio, um só ambiente resolve os três |
| ~~B6.6.6~~ **EM ESPERA** (não cancelada) desde 2026-08-18 — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Kernel arm64 mainline real (pré-compilado ou toolchain para buildar) + idealmente um initramfs busybox aarch64 real — mesmo bloqueio de toolchain/binário de B6.2 aceite #2/B4.0.3 item 3, um só ambiente resolve os três; adicionalmente, GICv2/GICv3/PSCI/DTB são substancialmente mais complexos que os periféricos versatilepb do precedente B4.1.5, reservar tempo de sessão maior. O bloqueio de FEATURE do `arm-jitter` que B6.6.6 e F11 convergiam (registrador de sistema/exceção/IRQ que qualquer kernel EL1 real precisaria) **foi FECHADO pela B6.6.7 em 2026-08-18** (ver índice do `tasks/README.md`) — só resta o bloqueio de ambiente original (kernel/toolchain aarch64 real) | fecha o épico B6.6 por completo (depende de B6.6.1-B6.6.5, rodada de spec 2026-07-26, ver `b6-aarch64.md`) — feature-completo desde B6.6.7; só falta kernel/toolchain real |

## Fila de BUGS de compat (trilha D) — sessões separadas da fila principal

Backend-independentes (confirmado 2026-07-16: INTERPRETED e ASM idênticos em
sintoma e velocidade no GBA — a atribuição antiga ao ASM está REVOGADA).
D2/D3/D4 já fechadas (ver índice).

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| ~~D5~~ 🟡 fix implementado 2026-07-17 (ndsemu `e9bebfd`: fim de canal one-shot do SPU agora corre no tempo emulado — o título esperava a sequência one-shot "terminar") — **falta só validação do usuário na GUI** (título→menu→novo jogo) | — | 🧑 usuário |
| ~~D5/Buneary~~ ✅ FECHADA 2026-07-18 (arm-jitter `65a9a66`, **user-validada na GUI**): bloco JIT stale da troca de overlay — invalidação só olhava o endereço-base da escrita, e um bloco THUMB de 1 instrução em X≡2 (mod 4) escapava da cópia em words; o `b` do overlay 77 (título) executava dentro do overlay 73 e pulava o init da animação. Fix = invalidação por intervalo da escrita; A/B no ROM real + validação live. Diagnósticos removidos do ndsemu (`2295157`) | — | — |
| (sem task, ORÁCULO APLICADO 2026-07-19) Platinum billboard do char invisível = DIVERGÊNCIA DE ALOCAÇÃO de VRAM de textura (byte-diff bank A ndsemu×melonDS: 0x0-0x5000 idêntico, 0x5000-0xB800 diverge = texturas do char só no melonDS onde o billboard lê, 0x10000+ = lixo só no ndsemu = textura obsoleta não liberada). Upstream tex-manager, não render nem fix de fase (6f72757 correto). Multi-sessão: tracear o alocador de textura do jogo. Detalhes na memória `ndsemu-game-compat` | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi (multi-sessão, pedido do usuário 2026-07-22)** — Fase 1 (fundação de hardware `wifi/WifiController`) SHIPPED (ndsemu `0c7d17e`): register file + BB/RF + WiFi RAM + timer; self-test do NitroWM passa, init de HW roda até o fim. Continue do Platinum AINDA erra (falta handshake WM ARM9↔ARM7 "ready"). Roadmap (Fase 2 ready→Continue funciona; Fase 3-5 RX/TX + AP falso + bridge de rede real p/ GTS via DNS alternativo) na memória `ndsemu-wifi-stack` | ndsemu | ⚠️ MODELO FORTE |
| (sem task, NOVA 2026-07-17) Platinum NÃO boota em INTERPRETED — ARM9 preso no handshake IPCSYNC do `PXI_Init` (`0x020C640C`) desde o frame 0; em ASM boota. Achado colateral da D5 | ndsemu; race de boot cross-CPU backend-dependente | ⚠️ MODELO FORTE |
| (sem task) Divergência ASM×interp no JUS | ver pendência 6 do tasks/README | ⚠️ MODELO FORTE |

