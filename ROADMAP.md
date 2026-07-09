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
- **Arquiteturas guest:** ARMv4T (ARM7TDMI — GBA) completo e ARMv5TE (ARM9E — NDS)
  incluindo BLX, CLZ, DSP multiplies, saturating, LDRD/STRD, tudo emitido nativamente.
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
4. **O que Truffle compra de verdade:** JIT dentro de **native-image** (o backend ASM
   define classes em runtime, o que native-image não suporta — hoje um emulador
   compilado nativamente ficaria preso no `INTERPRETED_IR`), deoptimização/perfis
   automáticos e potencial simplificação do emissor (sem escrever bytecode à mão).

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
| **A5 — native-image demo** | Emulador mínimo (ou gbaemu headless) compilado com native-image usando o backend Truffle | Binário nativo roda ROM de teste com JIT ativo |

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
| 3DS (principal) | ARM11 MPCore | **ARMv6K** + VFPv2 | ⬜ B1 |
| Raspberry Pi 1 / Zero | ARM1176JZF-S | ARMv6 + VFPv2 | ⬜ B1 |
| Raspberry Pi 2, smartphones ARMv7 | Cortex-A7/A9 | **ARMv7-A** (Thumb-2, NEON) | ⬜ B2–B4 |
| Linux/Android arm64, Pi 3+ | Cortex-A5x+ | **ARMv8-A AArch64** | ⬜ B6 |

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

**B2 — Thumb-2 (ARMv6T2/v7)** — o maior salto de decoder do plano.
- Encodings Thumb de 32 bits, IT blocks (predicação em Thumb), tabela de encoding nova
  no `ThumbDecoder` (hoje só 16-bit).
- IT blocks mapeiam bem no guard condicional por-op já existente no JIT.
- **Aceite:** suíte de decode exaustiva contra assembler de referência
  (binutils/capstone como oráculo offline); harness verde.

**B3 — ARMv7-A user-level + VFP** — completa o user-mode de 32 bits.
- CP15 estendido, MOVW/MOVT, DMB/DSB/ISB, restante do v7.
- **VFPv2/v3 primeiro, NEON depois**: VFP é pré-requisito prático de userland Linux e
  do 3DS; NEON é grande e pode ser fase própria (ou inicialmente interpretado via
  fallback PER_OP — o desenho já suporta exatamente isso).
- Implica IR de FP: novos `IrOp`s de float/double + banco de registradores FP no core.
- **Aceite:** binários user-mode compilados com gcc `-mfpu=vfp` rodando corretos.

**B4 — System-level / MMU (Linux full-system 32-bit)**
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

**Ordem recomendada:** B1 → B2 → B3 → (B4 ∥ B5) → B6. B6.0 (RFC do IR 64-bit) pode
ser escrita cedo, pois influencia decisões de B1–B3.

---

## Trilha C — Perf contínua (backlog medido, ndsemu como bancada)

Itens já identificados por profiling, em ordem de expectativa de ganho:

| Item | Ideia | Nota |
|------|-------|------|
| Flags NZCV em locals JVM | Manter flags em locals dentro do bloco em vez de ler/escrever CPSR por op | Maior item restante do codegen |
| Logic-flags + shifter nativo completo | Carry-out do shifter em ops lógicas `S` sem helper | Complementa o shifted-register nativo |
| Page-table dispatch no `AddressSpace` | Despacho de memória O(1) por página em vez de if-chain no hospedeiro | Par com softmmu de B4 |
| Chain budgets pós-boot | Subir budget de chaining depois do boot (~+9% medido) | ⚠️ ARM7 ≥16 quebra boot de Platinum/SM64DS — validar boot dos 4 jogos de referência |
| Chaining no gbaemu | gbaemu ainda não usa `setChainCycleBudget` | Win barato |

---

## Riscos gerais

| Risco | Mitigação |
|-------|-----------|
| Trilhas A/B incharem o core | Multi-módulo (A1) cedo; Truffle e futuros frontends em módulos opt-in |
| Divergência semântica em backend novo | Harness + divergence-checker obrigatórios antes de virar default |
| IR 64-bit desestabilizar ARMv4T/v5TE | RFC B6.0 antes de tocar código; gbaemu/ndsemu verdes como gate |
| Escopo Android/Linux crescer sem fim | Aceites explícitos por fase (shell, busybox, kernel mínimo); periféricos sempre no hospedeiro |
