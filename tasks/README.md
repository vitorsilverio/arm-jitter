# tasks/ — Spec Driven Development

Cada arquivo aqui é uma task **autocontida**, escrita para ser executada por um agente
sem contexto prévio do projeto. Leia este arquivo inteiro antes de executar qualquer task.

## Protocolo de execução (obrigatório)

1. Leia a task inteira, incluindo **Armadilhas** e **Não fazer**.
2. Verifique a coluna **Depende de** — não execute uma task cujas dependências não
   estejam concluídas (status ✅ no índice abaixo).
3. Leia os arquivos-fonte citados na task ANTES de escrever código. Quando a task diz
   "espelhe o padrão de X", abra X e copie a estrutura, nomes e estilo.
4. Implemente APENAS o que está em "Inclui". Se algo parecer necessário e não estiver
   listado, PARE e pergunte ao usuário em vez de improvisar.
5. Todo comportamento observável novo precisa de teste automatizado no mesmo PR.
6. Valide com `mvn test` (JDK do projeto = JBR 25). Se não puder executar comandos,
   peça ao usuário para rodar e cole o resultado.
7. Ao concluir: atualize o status da task no índice deste README e faça um commit por
   task (mensagem em português, começando com o ID da task, ex.: `B1.2: ...`).

## Invariantes globais (NUNCA violar)

- **G1 — O interpretador é o oráculo.** `InterpretedCodeEmitter` define a semântica.
  Qualquer backend/otimização novo deve produzir estado de CPU idêntico, validado pelo
  `BlockEquivalenceHarness` (`codegen/equivalence/`).
- **G2 — GBA = ARMv4T.** NUNCA aplique instruções ou comportamentos ARMv5+ ao preset
  `ARMV4T`. Todo recurso novo de arquitetura é gateado por `ArmFeature` e habilitado
  apenas nos presets corretos. GBATEK descreve GBA+NDS juntos — cuidado ao ler.
- **G3 — Sem breaking change.** Factories, assinaturas públicas e comportamento default
  não mudam. Recurso novo entra por factory/flag/preset novo.
- **G4 — `Cycle`/`Fetch` nunca recebem guard condicional** no codegen: instrução com
  condição falsa ainda consome ciclo e fetch.
- **G5 — gbaemu e ndsemu são o gate de regressão.** Mudança no arm-jitter exige
  `mvn install` local e suites verdes nos dois consumidores (peça ao usuário se não
  puder rodar).
- **G6 — Sem números mágicos.** Constantes arquiteturais (registradores PC/LR, máscaras,
  offsets) recebem nome.
- **G7 — Javadoc `///` (markdown, Java 25) em toda API pública**, em português.

## Estrutura de uma task

`Contexto → Objetivo → Inclui/Não inclui → Especificação → Passos → Aceite → Validação → Armadilhas`

Tasks marcadas com **[REFINAR]** são especificações de alto nível que devem ser
detalhadas (nova rodada de spec) quando suas dependências concluírem — não execute
uma task [REFINAR] diretamente.

## Índice e dependências

| Task | Título | Depende de | Status |
|------|--------|-----------|--------|
| [A0](trilha-a-truffle/a0-spike-viabilidade.md) | Spike de viabilidade Truffle | — | ✅ ([relatório](trilha-a-truffle/RELATORIO-A0.md): viável; JITta no JBR 25 via Unchained; supera ASM em blocos grandes) |
| [A1](trilha-a-truffle/a1-multi-modulo-maven.md) | Build multi-módulo Maven | — | ✅ (parent + `core/` [artifactId `arm-jitter` intacto, jar idêntico] + `truffle/` vazio; gbaemu/ndsemu verdes sem mudar poms) |
| [A2](trilha-a-truffle/a2-emissor-minimo.md) | TruffleCodeEmitter mínimo (ALU) | A0, A1 | ⬜ |
| [A3](trilha-a-truffle/a3-cobertura-completa.md) | Cobertura completa de IrOp | A2 | ⬜ |
| [A4](trilha-a-truffle/a4-factory-e-bench.md) | Factory pública + bench 3 JVMs | A3 | ⬜ |
| [A5](trilha-a-truffle/a5-native-image-demo.md) | Demo native-image | A4 | ⬜ |
| [B0](trilha-b-arquiteturas/b0-rfc-ir-64bit.md) | RFC: IR de 64 bits (para AArch64) | — | ✅ ([RFC](../docs/RFC-IR-64BIT.md) **APROVADA 2026-07-10: Opção B** — IR-64 paralelo + `Aarch64Core` irmão + `AddressSpace64`; §5 em vigor p/ B1–B3, ex.: monitor LDREX/STREX de B1.4 com endereço `long`; B6/F1+ destravados quando priorizados) |
| [B1.1](trilha-b-arquiteturas/b1.1-armv6-features-preset.md) | ArmFeatures + preset ARMV6K | — | ✅ (10 features + `ARMV6K` via `extending`; zero-diff de runtime; gbaemu/ndsemu verdes) |
| [B1.2](trilha-b-arquiteturas/b1.2-armv6-extend-reverse.md) | SXT/UXT, REV, UMAAL | B1.1 | ✅ (decoder gateado + IR + interpretador; extend via ShiftedRegister ROR, UMAAL como `LongMultiply.accumulateDouble`; ASM rejeita até B1.6 — equivalência PER_OP provada; suites arm-jitter 389 + gbaemu 215 + ndsemu 175 verdes) |
| [B1.3](trilha-b-arquiteturas/b1.3-armv6-simd-media.md) | SIMD paralelo, GE flags, SAT, USAD8, PKH | B1.1 | ✅ (2 PRs: GE\[3:0\] no CPSR + `IrOp.ParallelAlu` com enums próprios; SEL/`IrOp.Sel`, PKH via `IrOp.Alu`+ShiftedRegister, SSAT/USAT(16)/`IrOp.Saturate` com Q sticky, USAD(A)8/`IrOp.AbsDiffSum`; DCE/ConstantFold/FlagMerge atualizados; ASM rejeita até B1.6 — equivalência PER_OP provada; MSR v4T/v5TE pinado por teste; suite 441 verde) |
| [B1.4](trilha-b-arquiteturas/b1.4-armv6-exclusive.md) | LDREX/STREX + monitor de exclusividade | B1.1 | ✅ (monitor no `ArmCore` com endereço `long` — §5 da RFC IR-64; `IrOp.LoadExclusive/StoreExclusive/ClearExclusive`; STREX checa o monitor ANTES de escrever; exceção/CLREX limpam o monitor; `CpuSnapshot` inclui o monitor para o harness de equivalência; ASM rejeita até B1.6 — equivalência PER_OP provada; gating EXCLUSIVE_WORD/EXCLUSIVE_SIZED e UNPREDICTABLE→UNDEFINED testados; suite 455 verde) |
| [B1.5](trilha-b-arquiteturas/b1.5-armv6-system.md) | CPS, SRS/RFE, SETEND, hints WFI/WFE | B1.1 | ✅ (decoder gateado com UNDEFINED explícito sem a feature, espelhando LDREX/STREX/CLREX; CPS reusa `ArmCore.setCpsr` — mesmo caminho de banco de MSR/exceção — e é NOP em modo User; SRS/RFE empilham/restauram LR+SPSR atuais nos 4 modos de endereçamento, UNDEFINED em User/System; SETEND expõe o bit E novo do CPSR e bloqueia acesso de dados com E=1 (`UnsupportedOperationException` central em `IrExecutionSupport`); WFI vira `core.halt()`; YIELD/WFE/SEV/hint genérico continuam sem IR nova (mask=0 já é no-op); ASM rejeita até B1.6; suíte arm-jitter 467 + gbaemu 216 + ndsemu 175 verdes) |
| [B1.6](trilha-b-arquiteturas/b1.6-armv6-asm-nativo.md) | Emissão nativa ASM das ops v6 | B1.2–B1.5 | ⬜ |
| [B2](trilha-b-arquiteturas/b2-thumb2.md) | Thumb-2 (decoder 32-bit + IT blocks) [REFINAR] | B1.6 | ⬜ |
| [B3](trilha-b-arquiteturas/b3-armv7a-vfp.md) | ARMv7-A user-level + VFP [REFINAR] | B2 | ⬜ |
| [B4.0](trilha-b-arquiteturas/b4.0-runner-user-mode.md) | Runner Linux user-mode (estilo qemu-user) | — | ✅ (repo `armbox`: hello + busybox echo/sh; sem fork/pipes) |
| [B4.1](trilha-b-arquiteturas/b4.1-mmu-softmmu.md) | MMU/softmmu full-system [REFINAR] | B3, RFC própria | ⬜ |
| [B5](trilha-b-arquiteturas/b5-3ds.md) | 3DS enablement (checklist) [REFINAR] | B1.6, VFPv2 de B3 | ⬜ |
| [B6](trilha-b-arquiteturas/b6-aarch64.md) | AArch64 (épico) [REFINAR] | B0 | ⬜ |
| [C0](trilha-c-perf/c0-superblocos-trace-jit.md) | Superblocos → loop-superbloco (alavanca nº 1) | — | ✅ ÉPICO CONCLUÍDO (C0.1–C0.4) |
| [C0.1](trilha-c-perf/c0-impl-loop-superbloco.md) | Harness lockstep de runtime | — | ✅ |
| [C0.2](trilha-c-perf/c0-impl-loop-superbloco.md) | Detector de ciclo + contadores | C0.1 | ✅ (acha os loops da medição nos jogos reais) |
| [C0.3](trilha-c-perf/c0-impl-loop-superbloco.md) | Emissor do loop-superbloco + integração | C0.1, C0.2 | ✅ (bench: MKDS +16%, SM64DS +44%, JUS +39% — os dois últimos ACIMA de realtime) |
| [C0.4](trilha-c-perf/c0-impl-loop-superbloco.md) | Validação de jogo + A/B + default (ndsemu) | C0.3 | ✅ (boots frios ×4 ON==OFF + gameplay validado na GUI; default ON no backend ASM — ndsemu cc65fab) |
| [C1](trilha-c-perf/c1-flags-nzcv-locals.md) | Flags NZCV em locals JVM (⚠️ prioridade rebaixada) | — | ⬜ |
| [C2](trilha-c-perf/c2-logic-flags-shifter-nativo.md) | Carry-out do shifter nativo em ops lógicas S | — | ✅ (helpers `shiftedOperandCarry`/`doXxxS`; policy só rejeita dst15+S nas ALU; property test 32 combos × n=0..255 × 2 carries; asmcheck JUS 800 zero divergências; JUS bench 76,5→81,7 fps +6,8%) |
| [C3](trilha-c-perf/c3-paged-address-space.md) | PagedAddressSpace O(1) | — | ⬜ |
| [C4](trilha-c-perf/c4-chain-budget-pos-boot.md) | Chain budget pós-boot (repo ndsemu) | — | ✅ FECHADA (ndsemu 85e4b36 + cf7b5c2: bench 256/64 = MKDS +6% SM64DS +7,6% JUS +11,4%; boots ×4 OK; gameplay GUI validado 2026-07-10 → 256/64 é o DEFAULT) |
| [C5](trilha-c-perf/c5-gbaemu-chaining.md) | Chaining no gbaemu (repo gbaemu) | — | ✅ (gbaemu `GbaConsole.CHAIN_CYCLE_BUDGET=32`, conservador — bem abaixo de 1 scanline/1232 ciclos; aplicado nos dois backends para manter `JitInterpreterDivergenceTest` válido; bench headless 5 jogos: +9,1% a +41,0%; gba-tests + suite 216 verdes. **PENDENTE: usuário validar gameplay ~1min/jogo + áudio/raster na GUI** — o risco documentado é IRQ/DMA de HBlank atrasado pelo chaining) |

Legenda: ⬜ pendente · 🟡 em andamento · ✅ concluída
