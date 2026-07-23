# ROADMAP — arm-jitter

Roadmap de evolução da biblioteca. Cada trilha é independente; cada fase é mergeável
sozinha, com testes verdes e comportamento padrão inalterado até opt-in explícito.

**Referência visual da arquitetura:** [ARQUITETURA.html](ARQUITETURA.html)
**Tasks executáveis (Spec Driven Development):** [tasks/](tasks/README.md) — cada fase
deste roadmap tem uma spec autocontida com escopo, aceite e armadilhas.

---

## Onde estamos (2026-07)

O pipeline `cache → decode → lift IR → otimizar → emit` está completo e em produção:

- **Backends:** `INTERPRETED_IR` (oráculo/debug) e `JVM_BYTECODE` (ASM, default via
  `JitRuntimeFactory.armThumb`), com tiered compilation (tier frio interpretado, tier
  quente compilado em pool de threads), fallback `PER_OP` inline, execução condicional
  nativa, shifted-register nativo, register cache em locals, inline cache de 32K e
  encadeamento de blocos (`setChainCycleBudget`).
- **Arquiteturas guest:** ARMv4T (ARM7TDMI — GBA) e ARMv5TE (ARM9E — NDS) completos e em
  produção; ARMv6K completo (decoder+IR+interp+ASM nativo, trilha B1); Thumb-2 decode
  completo (B2.1-B2.5, incl. branches/IT — trilha B2), mas o preset público ainda
  não pluga todas as extensões (B2.6, pendente). Ver
  [Arquiteturas e features](README.md#arquiteturas-e-features) para a tabela atual.
- **Consumidores em produção:** gbaemu (5 jogos comerciais jogáveis, ≥2× realtime
  headless) e ndsemu (JUS ~99% realtime, MKDS 92–97%, SM64DS 72%).
- **Debug:** `GdbServer` (stub GDB remote serial), trace listener, runtime de
  divergência (`divergenceCheckingArmThumb`) e harness de equivalência entre emissores.

### Planos concluídos (removidos deste repositório)

| Plano | Conclusão | Resultado |
|-------|-----------|-----------|
| Codegen JVM fases 0–8 (antigo conteúdo deste `ROADMAP.md`) | 2026-06-18 | `armThumb` default ASM + otimizador (fold/DCE/flag-merge), fallback policy, harness de equivalência |
| `JIT_CONDITIONAL_EXECUTION_PLAN.md` | 2026-06-22 | Guard condicional por-op + `PER_OP` default; JUS de +6% para ~2× o interpretador |
| Perf 2026-07 (sessões 02–03) | 2026-07-03 | Register cache, IC 32K, chaining, guards especializados, LDM/STM inline, ARMv5TE nativo, shifted-register nativo, pool de compilação |

Detalhes históricos: `git log` e os arquivos correspondentes no histórico do git.

---

## Princípios (valem para todas as trilhas)

1. **Semântica única** — `InterpretedCodeEmitter` permanece a referência de corretude;
   todo backend novo passa no `BlockEquivalenceHarness` e num run longo de
   divergence-checking com ROM real.
2. **Compatibilidade retroativa** — `JitRuntime`, `CompiledBlock`, factories existentes
   não quebram; recursos novos entram por factory/flag novos.
3. **gbaemu e ndsemu verdes** — as suítes dos dois emuladores são o guarda de regressão
   externo de qualquer mudança aqui.
4. **IR próprio é o contrato central** — decoders produzem IR; otimizador, interpretador,
   harness, GDB e todos os backends consomem IR. Backends novos não introduzem uma
   segunda fonte de semântica.

---

## Trilha A — Backend GraalVM/Truffle

### Decisão de arquitetura: Truffle é um TERCEIRO backend, não substitui o IR próprio

A dúvida era se compensaria trocar o IR próprio por Truffle para simplificar. **Não
compensa.** Razões:

1. **O IR alimenta muito mais que o codegen** — interpretador-oráculo, otimizador,
   harness de equivalência, divergence checker e GdbServer todos operam sobre `IrOp`.
   Trocar por árvores Truffle acoplaria tudo isso ao runtime GraalVM.
2. **O backend ASM continua sendo o mais rápido em HotSpot puro** — ele emite bytecode
   que o C2 compila direto. Truffle em JVM sem o compilador Graal roda em modo
   interpretado (lento); o ganho de Truffle só existe com Graal (via GraalVM ou
   "Truffle Unchained" em OpenJDK 21+ com JVMCI). gbaemu/ndsemu rodam em JBR 25 —
   o caminho ASM atual não pode regredir.
3. **Dependência pesada** — truffle-api + polyglot são grandes; o core hoje só depende
   de `org.ow2.asm`. Truffle deve ficar em módulo Maven separado e opcional.
4. **O que Truffle compra de verdade — e o ÚNICO motivo real da trilha A:** JIT dentro
   de **native-image** (o backend ASM define classes em runtime, o que native-image não
   suporta — hoje um binário nativo ficaria preso no `INTERPRETED_IR`). Deoptimização/
   perfis automáticos e a simplificação do emissor (sem escrever bytecode à mão) são
   subprodutos, não a motivação — em JVM normal (sem native-image) o backend ASM
   continua sendo a escolha, não há razão para trocar.

**Alvo do native-image (A5): `armbox`, não gbaemu/ndsemu.** Decisão explícita do
usuário (2026-07-11): sem interesse em dar suporte nativo a gbaemu/ndsemu por ora —
`armbox` é o hospedeiro mais simples (sem GUI/Swing/áudio) e o candidato natural para a
demo. **A4 (bench em GraalVM CE de verdade) e A5 (native-image) ficam PENDENTES até o
usuário instalar o GraalVM 25 LTS** para testar num dos projetos — A0-A3 (spike,
módulo, emissor, cobertura completa) já foram feitas via "Truffle Unchained" no JBR 25
(JVMCI), que NÃO precisa de GraalVM instalado; A4/A5 são as primeiras fases que
precisam de uma distribuição GraalVM real (bench "GraalVM CE" da tabela e o próprio
`native-image` do A5). Não avançar A4/A5 sem isso disponível.

**Desenho alvo:** `CodegenBackend.TRUFFLE` + `TruffleCodeEmitter` que converte
`IrBlock` (o mesmo IR, pós-otimizador) em nós Truffle / Bytecode DSL, em módulo
`arm-jitter-truffle` separado.

### Fases

| Fase | Entrega | Aceite |
|------|---------|--------|
| **A0 — Spike** (1 sessão) | Protótipo descartável: 1 bloco ALU IR→Truffle rodando; avaliar Bytecode DSL vs AST clássica; testar "Truffle Unchained" no JBR 25 (JVMCI) vs GraalVM CE; medir bloco quente vs ASM | Relatório de viabilidade: em qual JVM Truffle JITta, ordem de grandeza de perf, escolha DSL/AST |
| **A1 — Módulo** | Converter build em multi-módulo Maven (`arm-jitter-core`, `arm-jitter-truffle`); core sem dependência Truffle; coordenadas atuais continuam válidas | `mvn install` publica os dois; gbaemu/ndsemu compilam sem mudança |
| **A2 — Emissor mínimo** | `TruffleCodeEmitter` cobrindo ALU/Cycle/Fetch com fallback interpretado (mesmo desenho da fase 4 do codegen JVM) | `BlockEquivalenceHarness` verde para os blocos suportados |
| **A3 — Cobertura completa** | Todas as categorias de `IrOp` (reusar a ordem 5a–5f que funcionou no ASM: memória → branches → multiply → LDM/STM → PSR/SWI → resto) | Divergence-check longo com ROM real (JUS/FireRed) zero divergências |
| **A4 — Bench honesto** | `JitRuntimeFactory.truffleArmThumb(...)`; bench nos 3 cenários: HotSpot puro, OpenJDK+Graal, GraalVM | Tabela de perf vs ASM publicada no README; decidir default por ambiente |
| **A5 — native-image demo** | `armbox` compilado com native-image usando o backend Truffle | 🟡 PARCIAL: binário roda correto, mas 0 blocos compilam (bailout de PE — causa raiz em A2/A3, ver RELATORIO-A5) |
| **A6 — Especialização de nós Truffle** | Árvore de nós por categoria de `IrOp` (delegando aos executores, G1) para o PE conseguir podar | `opt done` em blocos reais; spec fechada em `tasks/trilha-a-truffle/a6-*.md` |
| **A7 — Revalidação native-image** | Repetir diagnósticos de A5 pós-A6 (medição pura) | `--truffle` > `--interp` nos 2 ambientes; fecha o aceite #2 de A5 |
| **A8 — Otimizações native-image** | PGO/-O3/GC/march, tabela startup+RSS+throughput | Variante vencedora vira default do perfil `native` do armbox |
| **A9 — Biblioteca nativa (.dll/.so) com API C** | `native-image --shared` + módulo `capi/` (`@CEntryPoint aj_*`: create/map_ram/mmio-callbacks/registers/run_cycles/save-state) — arm-jitter embutível por QUALQUER linguagem com FFI | PR1: smoke test em C passa com backend interpretado; PR2 (pós-A7): backend Truffle mais rápido que interpretado dentro da lib |

**Risco principal:** Bytecode DSL ainda é relativamente novo; se instável, cair para
AST clássica (mais verbosa, igualmente suportada). **Kill criterion do A0:** se em
nenhuma JVM disponível o Truffle chegar a ~50% do ASM em bloco quente, a trilha vira
só "native-image" (prioridade menor).

---

## Trilha B — Novas arquiteturas guest

Objetivo: abrir a cadeia ARMv6 → ARMv7 → AArch64 para 3DS, Raspberry Pi, Linux e
Android. Nota de nomenclatura: **ARMv9 é um superset do ARMv8-A** (SVE2, MTE); o alvo
prático de 64 bits é ARMv8-A/AArch64 — v9 vem de graça depois, por feature flag.

### Mapa dispositivo → core → arquitetura

| Alvo | Core | Arquitetura | Status |
|------|------|-------------|--------|
| GBA | ARM7TDMI | ARMv4T | ✅ produção |
| NDS | ARM7TDMI + ARM946E-S | ARMv4T + ARMv5TE | ✅ produção |
| 3DS (lado DS/segurança) | ARM946E-S | ARMv5TE | ✅ já coberto |
| 3DS (principal) | ARM11 MPCore | **ARMv6K** + VFPv2 | 🟡 ARMv6K ✅ (B1.1-B1.6); falta VFPv2 (B3) |
| Raspberry Pi 1 / Zero | ARM1176JZF-S | ARMv6 + VFPv2 | 🟡 ARMv6K ✅; falta VFPv2 (B3) |
| Raspberry Pi 2, smartphones ARMv7 | Cortex-A7/A9 | **ARMv7-A** (Thumb-2, NEON) | ✅ épico B3 fechado (B3.7): preset `ARMV7A` completo — Thumb-2 (B2), inteiro v7 (B3.1/B3.2) e VFPv2 (B3.3-B3.6) juntos, validado com torture ELF + binário `gcc` hard-float real; user-level only (MMU é B4.1); NEON fora de escopo |
| Linux/Android arm64, Pi 3+ | Cortex-A5x+ | **ARMv8-A AArch64** | ⬜ B6 |
| Microcontroladores (STM32, nRF, Arduino ARM) | Cortex-M0/M3/M4/M7 | **ARMv6-M/v7-M/v8-M** (perfil M, Thumb-only) | ⬜ B7 [REFINAR] |

### Fases

**B1 — ARMv6/ARMv6K (user-level)** — o degrau mais barato: mesmo modelo de 32 bits,
mesmo IR.
- Novos `ArmFeature`s + preset `ArmArchitecture.ARMV6K`.
- Decoder+IR+interpretador: media/SIMD de 32 bits (SADD8/USAD8/PKH/SSAT/USAT...),
  REV/REV16/REVSH, SXT/UXT, UMAAL, LDREX/STREX (+ B/H/D no v6K), CPS/SRS/RFE, SETEND,
  hints WFI/WFE/NOP.
- Monitor de exclusividade (LDREX/STREX) no core — necessário desde já, é a base de
  atomics de qualquer SO.
- Emissão nativa ASM na sequência (mesmo padrão de fases do ARMv5TE).
- **Aceite:** harness + testes unitários por instrução; ROM de teste ARM11 (homebrew
  3DS ou bare-metal Pi) executando no interpretador.

**B2 — Thumb-2 (ARMv6T2/v7)** — o maior salto de decoder do plano. ✅ decode completo
(B2.1-B2.5: infra, data-processing, load-store, misc, branches+IT block).
- Encodings Thumb de 32 bits, IT blocks (predicação em Thumb), tabela de encoding nova
  no `ThumbDecoder` (hoje só 16-bit).
- IT blocks mapeiam bem no guard condicional por-op já existente no JIT — ITSTATE
  vive em `CpsrRegister`, entra na chave do `BlockCache`/tag do inline cache.
- **Aceite:** suíte de decode exaustiva contra assembler de referência
  (binutils/capstone como oráculo offline); harness verde.
- **B2.6 (pendente, spec fechada 2026-07-15)**: o "fantasma" de `BL`/`BLX` é
  indecidível por bits — o fix decidido é decodificar `BL`/`BLX` como instrução
  ÚNICA de 32 bits sob `THUMB2` (fiel ao ARMv6T2+, revoga a decisão D2 de B2.4) e
  então plugar as 4 extensões. Pré-requisito prático para qualquer binário Thumb-2
  real; validação de compilador na sequência em B4.0.3.

**B3 — ARMv7-A user-level + VFP** — completa o user-mode de 32 bits. **Refinado
2026-07-15 em B3.1–B3.7 executáveis** (`tasks/trilha-b-arquiteturas/b3*.md`), com
as decisões de FP fechadas (IEEE Java, FPSCR restrito, VMOV-imm do v3-d16, flags
cumulativas não calculadas). **✅ ÉPICO FECHADO (B3.7)**: preset `ARMArchitecture.ARMV7A`
público, validado com torture ELF handwritten + binário `gcc` hard-float real (série de
Leibniz em `double`) nos 3 backends do `armbox`.
- CP15 estendido, MOVW/MOVT, DMB/DSB/ISB, restante do v7.
- **VFPv2/v3 primeiro, NEON depois**: VFP é pré-requisito prático de userland Linux e
  do 3DS; NEON é grande e pode ser fase própria (ou inicialmente interpretado via
  fallback PER_OP — o desenho já suporta exatamente isso).
- Implica IR de FP: novos `IrOp`s de float/double + banco de registradores FP no core.
- **Aceite:** binários user-mode compilados com gcc `-mfpu=vfp` rodando corretos.

**B4 — System-level / MMU (Linux full-system 32-bit)** — decisões fechadas em
`docs/RFC-SOFTMMU.md` (2026-07-15): wrapper de tradução, hospedeiro
versatilepb+ARM1176 (**ARMv6K — não precisa de B3/VFP**), aborts base-restored,
CP15 VMSA na lib, gerações no BlockCache; fases B4.1.1–B4.1.5 no épico refinado.
- VMSA: page tables, TLB emulado, domains, aborts precisos (data/prefetch abort com
  FSR/FAR), integração da tradução no caminho de memória do JIT (softmmu — hoje o
  `AddressSpace` é físico/flat).
- `BlockCache` ciente de ASID/contexto (chave de bloco por espaço de endereçamento).
- Interrupt controller/timers/UART ficam no emulador hospedeiro; arm-jitter entrega os
  hooks (linha IRQ/FIQ já existe).
- **Aceite:** kernel Linux ARMv6/v7 mínimo bootando até shell em um hospedeiro de
  referência (novo repositório consumidor, ex. `armbox`).
- **Atalho recomendado antes do full-system:** um runner **user-mode estilo qemu-user**
  (ELF loader + tradução de syscalls via `SwiDispatcher`, memória flat) — valida B1–B3
  com binários Linux reais sem custar MMU, e é útil por si só.

**B5 — 3DS enablement** — na prática B1 + VFPv2 (de B3); periféricos, timing MPCore e
o segundo core ficam no emulador hospedeiro (novo projeto irmão, como gbaemu/ndsemu).
Do lado do arm-jitter basta ARMv6K + VFPv2 corretos.

**B6 — AArch64 (épico separado)** — não é uma extensão, é um segundo frontend.
- **B6.0 — generalizar o IR para 64 bits primeiro**: hoje valores/registradores são
  `int` de ponta a ponta (IR, executores, `GuestToHostMapper`). Decisão de desenho a
  tomar em RFC própria: IR parametrizado por largura vs IR-64 paralelo. Sem isso não
  há AArch64.
- B6.1 decoder A64 (encoding totalmente novo, 31×X-regs + SP/ZR, sem predicação, sem
  LDM/STM), B6.2 core (EL0/EL1, novo modelo de exceções), B6.3 interpretador,
  B6.4 backend ASM (`long` locals), B6.5 MMU v8 + user-mode runner arm64.
- Android real exige muito mais que CPU (binder, GPU, HALs) — o objetivo honesto do
  B6 é **Linux arm64 user-mode → full-system**; Android fica como norte distante.
- **Aceite incremental:** binários estáticos arm64 "hello world" → busybox no runner
  user-mode → kernel arm64 mínimo.

**B7 — Perfil M / Cortex-M (ARMv6-M/v7-M/v8-M)** — família arquitetural à parte, não
uma extensão do perfil A/R: sem modo ARM de 32 bits, Thread/Handler mode em vez de
CPSR bancado, NVIC/vetor relocável (VTOR), `EXC_RETURN`. Cobre microcontroladores
(STM32, nRF, a maioria das placas ARM tipo Arduino) — objetivo do projeto é cobrir
qualquer device ARM, não só a linha de aplicação já mapeada acima. **Refinado
2026-07-15**: decisão de arquitetura fechada em `docs/RFC-M-PROFILE.md` (`ArmCore`
único + `ExceptionModel` plugável); sub-tasks executáveis B7.1–B7.5 em
`tasks/trilha-b-arquiteturas/`.

**Ordem recomendada (revisada 2026-07-15):** B2.6 → **B2.7 (paridade Thumb-2 —
auditoria achou que MUL.W/UMULL/extend/exclusivos.W nunca foram decodificados;
sem eles nenhum binário Thumb-2 de compilador roda)**, com B1.7 (acesso
desalinhado v6+, corrupção silenciosa confirmada no fonte) e B2.8 (PLD/PLI) na
mesma leva. Depois, TRÊS frentes independentes que podem andar em paralelo:
- **B3.x** (ARMv7-A+VFP → 3DS/B5 e binários hard-float);
- **B7.x** (Cortex-M — só precisa de B2.6; B7.4 pleno usa B3.2);
- **B4.1.x** (MMU/Linux — **não depende mais de B3**: a RFC-SOFTMMU fixou o
  hospedeiro em versatilepb+ARM1176/ARMv6K, que já está pronto; kernel soft-float).
B6 (AArch64) quando priorizado — B6.1/B6.2 já são executáveis.
Todas as decisões de desenho pendentes foram fechadas em 2026-07-15
(`docs/RFC-M-PROFILE.md`, `docs/RFC-SOFTMMU.md`, specs B3.x); a matriz objetiva de
"funciona de verdade?" por arquitetura está em `docs/VALIDACAO-ARQUITETURAS.md`.

---

## Trilha C — Perf contínua (backlog medido, ndsemu como bancada)

Itens já identificados por profiling, em ordem de expectativa de ganho
(re-profile 2026-07-08: dispatch megamórfico entre blocos = custo nº 1 em gameplay):

| Item | Ideia | Nota |
|------|-------|------|
| Superblocos / trace-JIT | Fundir sequências encadeadas quentes em um método compilado (N chamadas megamórficas → 1) | A alavanca grande (~26–36% do CPU em gameplay); projeto multi-sessão |
| Flags NZCV em locals JVM | Manter flags em locals dentro do bloco em vez de ler/escrever CPSR por op | ⚠️ Rebaixado: ganho ≈0 medido em gameplay isolado; vira subproduto dos superblocos |
| Logic-flags + shifter nativo completo | Carry-out do shifter em ops lógicas `S` sem helper | Complementa o shifted-register nativo |
| Page-table dispatch no `AddressSpace` | ✅ utilitário pronto (C3, `PagedAddressSpace`); **adoção pendente**: C6 (gbaemu) e C7 (ndsemu), specs em `tasks/trilha-c-perf/` | ~19-26% no dispatch em microbench; ganho real diluído — bench por hospedeiro decide |
| Chain budgets pós-boot | ✅ C4 (256/64 default no ndsemu) | ⚠️ ARM7 ≥16 quebra boot de Platinum/SM64DS — validar boot dos 4 jogos de referência ao mexer |
| Chaining no gbaemu | ✅ C5 (budget 32 nos 2 backends) | INTERPRETED segue default do gbaemu (decisão do usuário) |
| Dispatch megamórfico remanescente | `JitRuntime.execute` ~12-14% pós-superblocos | SEM spec — exige sessão de modelo forte com profiling novo (ver tasks/README.md, seção "Pendências") |
| Perf do INTERPRETADO (C8) | O caminho de produção do gbaemu nunca foi medido/otimizado | **Contexto de produto: gbaemu é INTERPRETED default** (decisão do usuário; ASM não dá ganho no GBA — velocidade igual medida). ⚠️ CORREÇÃO 2026-07-16: a alegação de 2026-07-15 de que "JIT de bloco quebra Pokémon em batalha" caiu — os bugs visuais são backend-independentes (tasks D2/D3/D4/D6). Restrição da task permanece: nenhuma otimização pode mudar a granularidade observável de IRQ/ciclo |
| Fastmem no JIT (C9) | Load/store direto no array da página no bytecode (pós-C7) | ndsemu only; gbaemu fora (interpretado) |
| Warm-start do JIT (C10) | Persistir PCs quentes por ROM + pré-compilar no load | Ataca o "demora a esquentar" do MKDS |
| Idle-loop skip | Detectar busy-wait e avançar relógio | SEM spec — RFC própria antes (risco de timing); ver "Pendências" |

---

## Riscos gerais

| Risco | Mitigação |
|-------|-----------|
| Trilhas A/B incharem o core | Multi-módulo (A1) cedo; Truffle e futuros frontends em módulos opt-in |
| Divergência semântica em backend novo | Harness + divergence-checker obrigatórios antes de virar default |
| IR 64-bit desestabilizar ARMv4T/v5TE | RFC B6.0 antes de tocar código; gbaemu/ndsemu verdes como gate |
| Escopo Android/Linux crescer sem fim | Aceites explícitos por fase (shell, busybox, kernel mínimo); periféricos sempre no hospedeiro |
