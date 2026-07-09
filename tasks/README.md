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
| [A0](trilha-a-truffle/a0-spike-viabilidade.md) | Spike de viabilidade Truffle | — | ⬜ |
| [A1](trilha-a-truffle/a1-multi-modulo-maven.md) | Build multi-módulo Maven | — | ⬜ |
| [A2](trilha-a-truffle/a2-emissor-minimo.md) | TruffleCodeEmitter mínimo (ALU) | A0, A1 | ⬜ |
| [A3](trilha-a-truffle/a3-cobertura-completa.md) | Cobertura completa de IrOp | A2 | ⬜ |
| [A4](trilha-a-truffle/a4-factory-e-bench.md) | Factory pública + bench 3 JVMs | A3 | ⬜ |
| [A5](trilha-a-truffle/a5-native-image-demo.md) | Demo native-image | A4 | ⬜ |
| [B0](trilha-b-arquiteturas/b0-rfc-ir-64bit.md) | RFC: IR de 64 bits (para AArch64) | — | ⬜ |
| [B1.1](trilha-b-arquiteturas/b1.1-armv6-features-preset.md) | ArmFeatures + preset ARMV6K | — | ⬜ |
| [B1.2](trilha-b-arquiteturas/b1.2-armv6-extend-reverse.md) | SXT/UXT, REV, UMAAL | B1.1 | ⬜ |
| [B1.3](trilha-b-arquiteturas/b1.3-armv6-simd-media.md) | SIMD paralelo, GE flags, SAT, USAD8, PKH | B1.1 | ⬜ |
| [B1.4](trilha-b-arquiteturas/b1.4-armv6-exclusive.md) | LDREX/STREX + monitor de exclusividade | B1.1 | ⬜ |
| [B1.5](trilha-b-arquiteturas/b1.5-armv6-system.md) | CPS, SRS/RFE, SETEND, hints WFI/WFE | B1.1 | ⬜ |
| [B1.6](trilha-b-arquiteturas/b1.6-armv6-asm-nativo.md) | Emissão nativa ASM das ops v6 | B1.2–B1.5 | ⬜ |
| [B2](trilha-b-arquiteturas/b2-thumb2.md) | Thumb-2 (decoder 32-bit + IT blocks) [REFINAR] | B1.6 | ⬜ |
| [B3](trilha-b-arquiteturas/b3-armv7a-vfp.md) | ARMv7-A user-level + VFP [REFINAR] | B2 | ⬜ |
| [B4.0](trilha-b-arquiteturas/b4.0-runner-user-mode.md) | Runner Linux user-mode (estilo qemu-user) | — | 🟡 (repo `armbox`) |
| [B4.1](trilha-b-arquiteturas/b4.1-mmu-softmmu.md) | MMU/softmmu full-system [REFINAR] | B3, RFC própria | ⬜ |
| [B5](trilha-b-arquiteturas/b5-3ds.md) | 3DS enablement (checklist) [REFINAR] | B1.6, VFPv2 de B3 | ⬜ |
| [B6](trilha-b-arquiteturas/b6-aarch64.md) | AArch64 (épico) [REFINAR] | B0 | ⬜ |
| [C0](trilha-c-perf/c0-superblocos-trace-jit.md) | Superblocos/trace-JIT (alavanca nº 1) [REFINAR] | — | ⬜ |
| [C1](trilha-c-perf/c1-flags-nzcv-locals.md) | Flags NZCV em locals JVM (⚠️ prioridade rebaixada) | — | ⬜ |
| [C2](trilha-c-perf/c2-logic-flags-shifter-nativo.md) | Carry-out do shifter nativo em ops lógicas S | — | ⬜ |
| [C3](trilha-c-perf/c3-paged-address-space.md) | PagedAddressSpace O(1) | — | ⬜ |
| [C4](trilha-c-perf/c4-chain-budget-pos-boot.md) | Chain budget pós-boot (repo ndsemu) | — | ⬜ |
| [C5](trilha-c-perf/c5-gbaemu-chaining.md) | Chaining no gbaemu (repo gbaemu) | — | ⬜ |

Legenda: ⬜ pendente · 🟡 em andamento · ✅ concluída
