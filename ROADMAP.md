# ROADMAP — arm-jitter

Roadmap de evolução da biblioteca. Cada trilha é independente; cada fase é mergeável
sozinha, com testes verdes e comportamento padrão inalterado até opt-in explícito.

**Referência visual da arquitetura:** [ARQUITETURA.html](ARQUITETURA.html)
**Tasks executáveis (Spec Driven Development):** [tasks/](tasks/README.md) — cada fase
deste roadmap tem uma spec autocontida com escopo, aceite e armadilhas.

---

## Onde estamos (2026-08-15)

O pipeline `cache → decode → lift IR → otimizar → emit` está completo e em produção:

- **Backends:** `INTERPRETED_IR` (oráculo/debug) e `JVM_BYTECODE` (ASM, default via
  `JitRuntimeFactory.armThumb`), com tiered compilation (tier frio interpretado, tier
  quente compilado em pool de threads), fallback `PER_OP` inline, execução condicional
  nativa, shifted-register nativo, register cache em locals, inline cache de 32K,
  encadeamento de blocos (`setChainCycleBudget`) e superblocos de loop (C0, ndsemu).
  Backend `TRUFFLE` opcional (módulo `arm-jitter-truffle`) compila de verdade em JVM
  (JBR+Unchained); sob `native-image` o backend ASM não roda e o Truffle ainda tem
  bailout de PE sob SVM (ver trilha A).
- **Arquiteturas guest 32-bit:** ARMv4T (GBA) e ARMv5TE (NDS) em produção; ARMv6K,
  Thumb-2 e ARMv7-A+VFPv2 (épicos B1/B2/B3) completos e validados com binários reais
  no `armbox`; perfil M/Cortex-M (épico B7) completo (MSP/PSP/NVIC/SysTick/semihosting);
  MMU/softmmu 32-bit (épico B4.1) ✅ completo (2026-08-14): page-walk+aborts precisos
  na lib, host `virtual-arm-box` chega a um shell `busybox` interativo.
  Ver [Arquiteturas e features](README.md#arquiteturas-e-features) para a tabela atual.
- **AArch64 (épico B6):** decoder A64 completo (base ISA inteira + FP/SIMD escalar +
  exclusivos), `Aarch64Core` com EL0/EL1 e aborts precisos, MMU v8, backend ASM nativo
  (`jit64`) sem lacunas de `Ir64Op.Kind` — falta só o hospedeiro `virt64` até shell
  (B6.6.6, bloqueado em kernel/toolchain `aarch64-linux-*` reais).
  `armbox --arch=aarch64` roda "hello world" bare-metal hoje.
- **native-image / biblioteca nativa (trilha A):** `armbox` compila e roda sob
  GraalVM native-image (perfil `native`, PGO+`-O3` como default, task A8); biblioteca
  compartilhada `arm_jitter.dll`/`.so` com API C (`capi/`, task A9 PR1) embutível por
  qualquer linguagem com FFI, backend `INTERPRETED_IR`. Backend Truffle sob
  native-image segue com bailout de PE sem causa raiz diagnosticada (A7); A9 PR2
  (Truffle na lib nativa) fica bloqueada até isso fechar.
- **Consumidores em produção:** gbaemu (5 jogos comerciais jogáveis, INTERPRETED
  default por fidelidade, ≥2× realtime headless) e ndsemu (JUS ~99% realtime com
  superblocos, MKDS 92–97%, SM64DS in-game 72%).
- **Debug:** `GdbServer` (stub GDB remote serial), trace listener, runtime de
  divergência (`divergenceCheckingArmThumb`) e harness de equivalência entre emissores
  (32 e 64 bits).

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
| **A6 — Especialização de nós Truffle** | Árvore de nós por categoria de `IrOp` (delegando aos executores, G1) para o PE conseguir podar | ✅ (2026-07-23) — `opt done` em blocos reais na JVM (JBR+Unchained); spec em `tasks/trilha-a-truffle/a6-*.md` |
| **A7 — Revalidação native-image** | Repetir diagnósticos de A5 pós-A6 (medição pura) | 🔴 medida (2026-07-27) — resultado MISTO: JBR+Unchained agora compila de verdade (1812 `opt done`), mas native-image reproduz o MESMO bailout de PE da A5 byte a byte (0 `opt done`); nenhum ambiente bate `--truffle` > `--interp` em wall-time. A5 permanece 🟡, causa raiz do bailout SVM fica para sessão de modelo forte dedicada |
| **A8 — Otimizações native-image** | PGO/-O3/GC/march, tabela startup+RSS+throughput | ✅ (2026-07-31) — PGO+`-O3` venceu as 4 métricas (startup/throughput truffle/throughput interp/RSS) e virou default do perfil `native` do armbox |
| **A9 — Biblioteca nativa (.dll/.so) com API C** | `native-image --shared` + módulo `capi/` (`@CEntryPoint aj_*`: create/map_ram/mmio-callbacks/registers/run_cycles/save-state) — arm-jitter embutível por QUALQUER linguagem com FFI | PR1 ✅ (2026-07-31) — smoke test em C (19 checagens) passa com backend interpretado; PR2 (Truffle na lib) segue bloqueada em A7 (bailout SVM não fechou) |

**Risco principal:** Bytecode DSL ainda é relativamente novo; se instável, cair para
AST clássica (mais verbosa, igualmente suportada). **Kill criterion do A0:** se em
nenhuma JVM disponível o Truffle chegar a ~50% do ASM em bloco quente, a trilha vira
só "native-image" (prioridade menor).

---

## Trilha B — Novas arquiteturas guest

Objetivo: abrir a cadeia ARMv6 → ARMv7 → AArch64 para 3DS, Raspberry Pi, Linux
moderno (distros genéricas, não só o kernel Raspberry Pi bare-metal da F11) e
Android. **Atualizado 2026-08-24 (decisão do usuário)**: ARMv9 passa a ser alvo real,
não "de graça depois" — o `virtual-arm-box` precisa rodar Linux moderno de forma
mais ampla que só Raspberry Pi (candidato natural: reviver a rota `virt64`/QEMU
`virt` genérico, B6.6.6, hoje [REFINAR]/em espera atrás da F11). Consequência direta
para a Trilha B7 (cobertura de ISA): extensões ARMv8.1+/ARMv9.x antes excluídas de
`docs/isa-nao-aplicavel.tsv` por "posterior ao Cortex-A53" precisam de nova triagem
sob este alvo mais amplo — ver nota em `b7-plano-cobertura-isa.md`.

### Mapa dispositivo → core → arquitetura

| Alvo | Core | Arquitetura | Status |
|------|------|-------------|--------|
| GBA | ARM7TDMI | ARMv4T | ✅ produção |
| NDS | ARM7TDMI + ARM946E-S | ARMv4T + ARMv5TE | ✅ produção |
| 3DS (lado DS/segurança) | ARM946E-S | ARMv5TE | ✅ já coberto |
| 3DS (principal) | ARM11 MPCore | **ARMv6K** + VFPv2 | ✅ preset `ArmArchitecture.ARM11_MPCORE` fechado (B5.2, 2026-07-23) — ARMv6K (B1) + VFPv2 (B3), sem Thumb-2; falta só o emulador hospedeiro (periféricos/segundo core, fora do arm-jitter) |
| Raspberry Pi 1 / Zero | ARM1176JZF-S | ARMv6 + VFPv2 | ✅ ARMv6K (B1) + VFPv2 (B3) completos, mesmo preset-base do 3DS acima |
| Raspberry Pi 2, smartphones ARMv7 | Cortex-A7/A9 | **ARMv7-A** (Thumb-2, NEON) | ✅ épico B3 fechado (B3.7): preset `ARMV7A` completo — Thumb-2 (B2), inteiro v7 (B3.1/B3.2) e VFPv2 (B3.3-B3.6) juntos, validado com torture ELF + binário `gcc` hard-float real; user-level only (MMU é B4.1); NEON fora de escopo |
| Linux/Android arm64, Pi 3+ | Cortex-A5x+ | **ARMv8-A AArch64** | 🟡 épico B6 quase completo (B6.1-B6.6.5 ✅, 2026-07-24/27) — decoder+core+MMU v8+backend ASM prontos; falta só B6.6.6 (hospedeiro `virt64`, bloqueado em toolchain/kernel real) |
| Microcontroladores (STM32, nRF, Arduino ARM) | Cortex-M0/M3/M4/M7 | **ARMv6-M/v7-M/v8-M** (perfil M, Thumb-only) | ✅ épico B7 fechado (B7.1-B7.5, 2026-07-23) — `ExceptionModel` plugável, NVIC/SysTick, presets `ARMV6M`/`ARMV7M`, `armbox --machine=cortex-m` validado com firmware real |

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
**✅ ÉPICO FECHADO (B4.1.1-B4.1.5, 2026-08-14)**: `TranslatingAddressSpace` (page-walk VMSA
completo, AP/domains, micro-TLB), `Cp15VmsaCoprocessor` (`MCR`/`MRC` ligados ao
wrapper), aborts precisos (`ArmCore.enterMemoryAbort`, FAR/FSR reais) nos 3 motores
(interpretador, `IrBlockExecutor` e JIT ASM nativo via `visitTryCatchBlock`),
`translationGeneration` no `BlockKey`/`JitRuntime` (troca de `TTBR0` vira miss natural
no cache de blocos) — tudo isso em B4.1.1-B4.1.4 (2026-07-26). **B4.1.5 (2026-08-14,
2 sessões)**: repositório novo `virtual-arm-box` (hospedeiro versatilepb-like com
`Pl011Uart`/`Sp804DualTimer`/`Pl190Vic`) roda um kernel Debian real
(`vmlinuz-3.2.0-4-versatile`) + `busybox-armv5l` até um shell `busybox` interativo,
nos backends INTERPRETED e JIT — 4 causas raiz corrigidas na 2ª sessão (TLBs de
instrução/dados separadas do ARMv5, sincronização de privilégio USER/PRIV com o modo
do core para o copy-on-write do `fork()` funcionar, propagação de falta de tradução no
lift adiantado de bloco, e VFPv2 opcional no core para o busybox hard-float não
travar em `VPUSH`). Ver [tasks/README.md](tasks/README.md) para o detalhe completo.
- VMSA: page tables, TLB emulado, domains, aborts precisos — ✅ feito acima.
- `BlockCache` ciente de geração de tradução — ✅ feito acima (não é ASID pleno, mas
  cobre o caso prático de troca de mapeamento).
- Interrupt controller/timers/UART ficam no emulador hospedeiro (`virtual-arm-box`); arm-jitter
  entrega os hooks (linha IRQ/FIQ já existe).
- **Aceite:** kernel Linux ARMv5TE bootando até shell `busybox` interativo no
  `virtual-arm-box` — ✅ alcançado (desvio documentado: kernel Debian pré-compilado +
  ATAGs em vez de `versatile_defconfig` mainline com Device Tree, e ARM926EJ-S/ARMv5TE
  em vez do ARM1176/ARMv6K original da RFC — falta de toolchain `arm-linux-gnueabihf-*`
  nesta máquina, mesmo bloqueio de B4.0.3/B6.2/B6.6.6).

**B5 — 3DS enablement** — ✅ lado arm-jitter completo: B1 (ARMv6K) + B3 (VFPv2) + B5.1
(monitor de exclusividade global) + **B5.2 ✅ (2026-07-23)** — preset
`ArmArchitecture.ARM11_MPCORE` público. Falta só o emulador hospedeiro (periféricos,
timing MPCore, segundo core — novo projeto irmão, como gbaemu/ndsemu).

**B6 — AArch64 (épico separado)** — não é uma extensão, é um segundo frontend.
**Decisão B6.0 tomada: IR-64 paralelo** (`ir64/Ir64Op`, não parametrização do IR
existente) — espelho estrutural do `IrOp` 32-bit mas sem `condition()` universal (A64
não tem predicação geral); zero mudança no pipeline 32-bit (G2/G3 preservados em toda
sub-task). **Progresso (2026-07-24 a 27, 21 sub-tasks fechadas — épico quase 100%)**:
- **B6.1 ✅** — esqueleto: `Ir64Op`, `Aarch64Core`/`PstateRegister`, `AddressSpace64`,
  `Aarch64Decoder` (ADR/ADRP, ADD/SUB imm, MOVZ/MOVN/MOVK, branches/CBZ/CBNZ/TBZ/TBNZ),
  `Ir64BlockExecutor` interpretado.
- **B6.2 🟡 parcial** — loads/stores completos (`LDP`/`STP`, todos os modos de
  endereçamento) + `armbox --arch=aarch64`; aceite #1 (`hello-aarch64.elf` bare-metal)
  ✅, aceite #2 (busybox aarch64 real) bloqueado em toolchain/binário (ver 🧑 na fila).
- **B6.3 ✅ (4 sub-tasks)** — base ISA inteira restante: lógica/ALU por registrador
  (B6.3.1), `CSEL`/bitfield (B6.3.2), `MADD`/`MSUB`/`SDIV`/`UDIV` (B6.3.3),
  `LDXR`/`STXR`+`Aarch64ExclusiveMonitor` (B6.3.4).
- **B6.4 ✅ (3 PRs)** — backend ASM nativo (`jit64/`, `JitRuntime64`/`BlockCache64`,
  sem IC/chaining/tiering ainda por decisão de escopo) cobrindo TODO `Ir64Op.Kind`
  existente; bench "busybox ≥3× interpretador" segue bloqueado no mesmo toolchain
  de B6.2.
- **B6.5 ✅ (4 sub-tasks)** — FP/SIMD escalar: registradores `V0`-`V31` (B6.5.1),
  `Ir64Op`s de FP + executor interpretado (B6.5.2), decoder "Data Processing — Scalar
  FP" (B6.5.3), emissão ASM nativa de FP (B6.5.4).
- **B6.6 🟡 (5/6 sub-tasks)** — MMU v8: `Aarch64SystemRegisterBus`+`MRS`/`MSR` (B6.6.1),
  `TranslatingAddressSpace64` (B6.6.2), `Aarch64VmsaSystemRegisters` (B6.6.3), modelo de
  exceção EL0→EL1 + aborts precisos + `ERET` (B6.6.4), `translationGeneration` em
  `jit64` (B6.6.5). Falta só **B6.6.6** (hospedeiro `virt64` até shell — kernel arm64
  real + GICv2/v3/PSCI/DTB, bloqueado em toolchain, ver 🧑 na fila).
- Android real exige muito mais que CPU (binder, GPU, HALs) — o objetivo honesto do
  B6 continua sendo **Linux arm64 user-mode → full-system**; Android fica como norte
  distante.
- Ver `tasks/trilha-b-arquiteturas/b6-aarch64.md` e `tasks/FILA-EXECUCAO.md` para o
  detalhe de cada sub-task.

**B7 — Perfil M / Cortex-M (ARMv6-M/v7-M/v8-M)** — ✅ **ÉPICO FECHADO (B7.1-B7.5,
2026-07-23)**. Família arquitetural à parte, não uma extensão do perfil A/R: sem modo
ARM de 32 bits, Thread/Handler mode em vez de CPSR bancado, NVIC/vetor relocável
(VTOR), `EXC_RETURN`. Decisão de arquitetura em `docs/RFC-M-PROFILE.md` (`ArmCore`
único + `ExceptionModel` plugável). Entregue: `MProfileExceptionModel` (MSP/PSP/xPSR/
stacking/EXC_RETURN), `MProfileSystemControl` (SCS/NVIC/VTOR/SysTick memory-mapped,
prioridade/preempção), `MRS`/`MSR` SYSm + `CPS` de 16 bits, presets `ARMV6M`/`ARMV7M`
(feature `M_FAULT_MASKING`), `armbox --machine=cortex-m` + semihosting (`BKPT`)
validado com firmware torture m0/m3 + `hello-cortexm.c` (gcc real, sem CRT).

**Status da ordem recomendada de 2026-07-15**: as três frentes (B3 VFP/ARMv7-A, B7
Cortex-M, B4.1 MMU/Linux) rodaram e as três **fecharam por completo** (B4.1 por
último, em 2026-08-14, com o épico inteiro incluindo o hospedeiro `virtual-arm-box`).
B5 (3DS) e B6.1-6.5 (AArch64) também fecharam depois disso. **O que resta hoje é, em
cada caso, o ÚLTIMO degrau de um épico ainda aberto — não mais decoder/IR/executor,
mas um hospedeiro batendo em kernel/toolchain real** — e os três compartilham o mesmo
tipo de bloqueio (toolchain `arm-linux-*`/`aarch64-linux-*` ou kernel/binário real
indisponível neste ambiente Windows/MSYS2 sem WSL configurado):
- **B4.0.3 item 3 / B4.0.5** (armbox: busybox estático Thumb-2, depois fork/pipes)
- **B6.2 aceite #2 / B6.4 bench** (armbox: busybox estático aarch64 real)
- **B6.6.6** (hospedeiro `virt64`: kernel arm64 real + GICv2/v3/PSCI/DTB)

Todas as decisões de desenho pendentes foram fechadas em 2026-07-15
(`docs/RFC-M-PROFILE.md`, `docs/RFC-SOFTMMU.md`, specs B3.x); a matriz objetiva de
"funciona de verdade?" por arquitetura está em `docs/VALIDACAO-ARQUITETURAS.md`. Ver
`tasks/FILA-EXECUCAO.md`, seção "🧑 Bloqueadas no usuário", para o que cada um
precisa especificamente.

---

## Trilha C — Perf contínua (backlog medido, ndsemu como bancada)

Itens já identificados por profiling, em ordem de expectativa de ganho
(re-profile 2026-07-08: dispatch megamórfico entre blocos = custo nº 1 em gameplay):

| Item | Ideia | Nota |
|------|-------|------|
| Superblocos / trace-JIT | Fundir sequências encadeadas quentes em um método compilado (N chamadas megamórficas → 1) | ✅ C0 (2026-07-11, épico C0.1-C0.4) — bench MKDS +16%, SM64DS +44%, JUS +39% (2 últimos acima de realtime); default ON no backend ASM do ndsemu, validado na GUI |
| Flags NZCV em locals JVM | Manter flags em locals dentro do bloco em vez de ler/escrever CPSR por op | ❌ C1 fechada sem implementar (2026-07-11) — reavaliada com JFR pós-superblocos: folhas de flag/condição ficam em ~0,4% das amostras, sem sinal suficiente para o risco de corretude; reabrir só com profile novo mostrando cenário diferente |
| Logic-flags + shifter nativo completo | Carry-out do shifter em ops lógicas `S` sem helper | ✅ C2 — JUS 76,5→81,7 fps (+6,8%) |
| Page-table dispatch no `AddressSpace` | Utilitário `PagedAddressSpace` (C3 ✅) + adoção por hospedeiro | ✅ C3 (utilitário, ~19-26% mais rápido que if-chain realista em microbench) + ✅ **C6** (gbaemu, `GbaBus` sobre `PagedAddressSpace`, bench -8% a -29% nos 5 jogos headless, gameplay validado) + ⬜ **C7** (ndsemu) bloqueada em validação de gameplay do usuário (boot dos 4 jogos de referência) |
| Chain budgets pós-boot | ✅ C4 (256/64 default no ndsemu) | ⚠️ ARM7 ≥16 quebra boot de Platinum/SM64DS — validar boot dos 4 jogos de referência ao mexer |
| Chaining no gbaemu | ✅ C5 (budget 32 nos 2 backends) | INTERPRETED segue default do gbaemu (decisão do usuário); achado registrado sem investigação: glitch de batalha do Pokémon (FireRed) — ver trilha D |
| Dispatch megamórfico remanescente | `JitRuntime.execute` ~12-14% pós-superblocos | SEM spec — exige sessão de modelo forte com profiling novo (ver tasks/README.md, seção "Pendências") |
| Perf do INTERPRETADO (C8) | O caminho de produção do gbaemu nunca foi medido/otimizado | ✅ **FECHADA (2026-07-17)** — dispatch por `int[] kindsArray` em vez de `IrOp.kind()` virtual (−15,6% agregado) + tabela endereço→dono pré-computada no `GbaBus` de I/O (−6,6%); meta ≥15% batida. Usuário validou: FireRed sem regressão de velocidade/fidelidade. **Contexto de produto: gbaemu continua INTERPRETED default** (ASM não dá ganho no GBA, decisão do usuário) |
| Fastmem no JIT (C9) | Load/store direto no array da página no bytecode (pós-C7) | ⬜ ndsemu only; gbaemu fora (interpretado); bloqueada em C7 |
| Warm-start do JIT (C10) | Persistir PCs quentes por ROM + pré-compilar no load | 🟡 implementado (2026-07-17) — `BlockCache#hotKeys`/`JitRuntime#precompile` (arm-jitter) + `HotBlockStore` (ndsemu, `.hotpcs` versionado com hash CRC32); **aceites #1 (fps MKDS antes/depois) e #2 (asmcheck JUS + boot dos 4 jogos)** pendentes de medição do usuário com ROM real |
| Idle-loop skip | Detectar busy-wait e avançar relógio | SEM spec — RFC própria antes (risco de timing); ver "Pendências" |

---

## Riscos gerais

| Risco | Mitigação |
|-------|-----------|
| Trilhas A/B incharem o core | Multi-módulo (A1) cedo; Truffle e futuros frontends em módulos opt-in |
| Divergência semântica em backend novo | Harness + divergence-checker obrigatórios antes de virar default |
| IR 64-bit desestabilizar ARMv4T/v5TE | RFC B6.0 antes de tocar código; gbaemu/ndsemu verdes como gate |
| Escopo Android/Linux crescer sem fim | Aceites explícitos por fase (shell, busybox, kernel mínimo); periféricos sempre no hospedeiro |
