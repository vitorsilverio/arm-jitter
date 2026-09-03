# C12 — Emissão JIT nativa: fechar a cobertura dos dois backends ASM: épico

**Trilha:** C · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano (2026-09-02)

Documento MESTRE do épico. Nasce da medição de 2026-09-02 (pedido do usuário: *"ainda temos um grande
trabalho depois para fazer o jit de tudo e o truffle realmente funcionar"*), que mediu pela primeira
vez a **dimensão 2** do `tasks/ROADMAP-100-ARM.md`: quantas operações do IR o backend ASM realmente
emite em bytecode, em vez de delegar ao interpretador.

**Ninguém media isso.** `docs/COBERTURA-ISA.md` mede decode+execução interpretada; uma op pode estar
✅ na tabela e nunca ter sido compilada nativamente.

## O número (medido)

| Pipeline | Política | Nativos | Total | Interpretados |
|---|---|---:|---:|---:|
| 32 bits | `codegen/jvm/AsmNativePolicy` (casa por **record**) | **46** | 73 | 27 + ~8 carve-outs condicionais |
| 64 bits | `codegen64/jvm64/Ir64NativePolicy` (casa por **`Kind`**) | **24** | 95 | **71** |

### Por que isso importa mais do que parece: a política é `WHOLE_BLOCK`

`AsmNativePolicy.supports(IrBlock)` e `Ir64NativePolicy.supports(Ir64Block)` fazem
`for (op : block) if (!supports(op)) return false;` — **uma única op não suportada derruba o BLOCO
INTEIRO** para o interpretador. Não é degradação proporcional: um bloco AArch64 de 40 instruções com
uma única `FADD` vetorial roda inteiro interpretado.

### O achado central: o Javadoc do lado 64 bits está desatualizado desde a B6.5.4

`Ir64NativePolicy` afirma, textualmente:

> *"com isso, TODO {@link Ir64Op.Kind} existente hoje é suportado nativamente; um bloco só cai no
> InterpretedIr64CodeEmitter ... se um `Ir64Op.Kind` futuro ainda não tiver `case` aqui."*

Era **verdade** quando a B6.5.4 fechou (24 Kinds existiam). Desde então entraram:

| Origem | Kinds acrescentados | Nativos? |
|---|---:|---|
| B6.6.x (MMU/EL1), B6.8-B6.14 | ~12 | ❌ |
| **B8.x — toda a AdvSIMD** (B8.6-B8.20) | ~35 | ❌ |
| B10 (EL2/EL3), B11 (gating) | ~8 | ❌ |
| B19.1-B19.3 | 2 | ❌ |

**Os 71 Kinds interpretados incluem 100% do SIMD/FP vetorial do AArch64.** A `Ir64Op.Kind` está em 95
e cresce a cada task de cobertura; a política não foi tocada desde a 24ª. **Cada task futura de
decode aumenta o gap** — é o motivo de este épico existir agora e não "depois da cobertura".

## Escada

Ordenada por **razão custo/benefício medida**, não por número de Kind. Os dois primeiros degraus não
emitem uma linha de bytecode novo — são instrumentação e verdade.

| Task | Escopo | Emite código? | Depende de |
|---|---|---|---|
| **C12.1** | **Medir e não mentir**: relatório de cobertura de emissão nativa (`docs/COBERTURA-JIT.md`, gerado por medição como o `IsaCoverageReport` faz para ISA — 3 colunas: interpretado / ASM 32 / ASM 64 / Truffle). Corrigir o Javadoc de `Ir64NativePolicy`. **Sem isso, todo degrau seguinte é adivinhação** | não | — |
| **C12.2** | **Política `PER_OP` no pipeline de 64 bits**: hoje é `WHOLE_BLOCK` (uma op derruba o bloco). O lado 32 bits já tem o precedente. Ganho imediato SEM emitir nada novo: blocos mistos param de cair inteiros | não | C12.1 |
| **[C12.3](c12.3-a64-inteiro-nativo.md)** | A64 — o **inteiro** ainda interpretado. **Remedido 2026-09-03: são 16 `Kind`**, não a lista do plano: os 13 previstos MAIS `EVALUATE_INTO_FLAGS`/`ROTATE_INTO_FLAGS`/`CONVERT_FLAGS` (`FEAT_FlagM`/`FlagM2`), que não estavam listados | sim | C12.1 |
| **[C12.4](c12.4-a64-fp-escalar-nativo.md)** | A64 — **FP escalar** restante (6 `Kind`). A B6.5.4 já emitiu 4; extensão direta do padrão dela | sim | C12.3 |
| **[C12.5](c12.5-a64-loadstore-fp-simd-nativo.md)** | A64 — **load/store FP/SIMD** (7 `Kind`). Os 4 escalares por emissão real; as 3 formas estruturadas (`LD1`-`LD4`) por helper, como o precedente de `LDM`/`STM` de 32 bits | sim | C12.4 |
| **[C12.6](c12.6-a64-advsimd-nativo-rfc.md)** `[RFC]` | A64 — **AdvSIMD aritmético**: **35 `Kind`** medidos (quase metade dos 72 que faltam). RFC com protótipo medido entre 3 rotas: lane a lane × chamada a `AdvSimdLanes` × não emitir. **Decide junto com a A10.7** | talvez | C12.5, C12.1 |
| **[C12.7](c12.7-32bits-records-restantes.md)** | 32 bits — os records `❌` **sem NEON**: **20** medidos (o plano dizia 27; 31 `❌` menos 11 de NEON). ⚠️ único pipeline com cache de registradores E com `gbaemu`/`ndsemu` em produção | sim | C12.1 |
| **[C12.8](c12.8-32bits-neon-nativo.md)** | 32 bits — os `Kind` de **NEON** (11 hoje, crescendo com B13). Aplica a decisão da C12.6; sem B13.22 não há preset com NEON e nada a validar com guest | talvez | C12.6, C12.7, B13.22 |
| **[C12.9](c12.9-fechamento.md)** | **Fechamento**: remedir as 4 colunas, decidir os defaults de política com número e workload real, enumerar o que sobrou. Coordena com a **A10.9** (mesmo arquivo) | não | C12.2-C12.8, C12.10 |
| **[C12.10](c12.10-a64-sistema-nativo.md)** 🆕 | A64 — os **8 `Kind` de sistema** que a escada original não tinha: `SYSTEM_REGISTER`/`SYSTEM_INSTRUCTION`/`EXCEPTION_RETURN`/`PRIVILEGED_CALL`/`INTERRUPT_MASK`/`BREAKPOINT`/`UNDEFINED_INSTRUCTION_TRAP`/`ADDRESS_TRANSLATE`. Kernel os executa em caminho quente; 4 deles transferem controle por exceção (cuidado da **E7**) | sim | C12.3 |

## A pergunta em aberto (RFC dentro da C12.6)

**Vale emitir SIMD nativamente?** Uma op vetorial de 128 bits vira, em bytecode JVM, um laço de 2-16
lanes com leitura/escrita no banco — muito maior que uma op inteira, e o `Ir64BlockCompiler` teria de
emitir tudo isso inline. Há três saídas, e **escolher errado custa o degrau inteiro**:

1. **Emitir lane a lane** — máximo controle, maior volume de bytecode, risco de estourar o limite de
   64 KB por método e de piorar o inline do JIT da JVM.
2. **Emitir uma CHAMADA ao núcleo compartilhado** (`AdvSimdLanes.threeSame(...)`) — o bytecode fica
   minúsculo, o C2/Graal inlina e vetoriza o laço do núcleo, e a semântica continua com **uma fonte
   de verdade** (G1). É o que a RFC B13.2 tornou possível ao extrair `AdvSimdLanes`.
3. **Não emitir** e assumir SIMD interpretado, contando com a `PER_OP` (C12.2) para o resto do bloco
   continuar nativo.

A opção (2) parece a certa e ficou **muito mais barata depois da RFC B13.2** — mas é hipótese, não
medição. **C12.6 começa com protótipo de UMA família e benchmark**, no formato da RFC B13.2 (que
mediu antes de decidir e economizou o épico inteiro).

## Meta

`docs/COBERTURA-JIT.md` mostrando emissão nativa completa nos dois pipelines — ou, onde a medição
mostrar que não paga, a decisão **registrada com número**, nunca por omissão.

## Invariantes específicos

- **G1 acima de tudo.** O interpretador é o oráculo; toda op nova emitida nativamente passa pelo
  `BlockEquivalenceHarness` (`codegen/equivalence/`). Emissão nativa é a classe de mudança que mais
  produziu bug silencioso na história deste projeto — ver a memória `gba-jit-optimizer-bugs` (3 bugs
  de otimizador causando corrupção só no JIT) e o `JitInterpreterDivergenceTest`.
- **G4**: `Cycle`/`Fetch` nunca recebem guard condicional.
- **G5**: gbaemu e ndsemu são o gate. Mudança de emissão nativa muda o caminho quente dos dois — e
  a memória `arm-jitter-perf-plan` registra que os orçamentos de chaining são sensíveis
  (`ARM7=8`; `≥16` QUEBRA o boot de Platinum/SM64DS). Validar boot dos 4 jogos de referência ao
  mexer. ⚠️ Isso **colide com o congelamento de subprojetos** (`tasks/README.md`): rodar as SUÍTES é
  G5 normal, mas investigar um jogo que quebrou é task de subprojeto. Se um degrau quebrar gameplay,
  **PARE e reporte** em vez de investigar.
- **Medir antes e depois.** Todo degrau que emite código reporta o delta de fps/tempo num benchmark
  reproduzível (`Main <frames> bench` do ndsemu, `armbox --check`), não "deve ficar mais rápido".

## Armadilhas conhecidas

- **`WHOLE_BLOCK` esconde o ganho.** Emitir 10 Kinds novos pode dar **zero** de ganho medido se os
  blocos reais contiverem um 11º não suportado. É por isso que a C12.2 (`PER_OP`) vem antes dos
  degraus que emitem código — sem ela, a medição de cada degrau seguinte é ruído.
- **O Javadoc mente hoje.** Qualquer sessão que leia `Ir64NativePolicy` e acredite no comentário vai
  concluir que não há trabalho aqui. A C12.1 corrige isso *primeiro*, e é metade do valor dela.
- **`AsmNativePolicy` casa por RECORD, `Ir64NativePolicy` por `Kind`.** Medir os dois com o mesmo
  script dá `0/73` no lado 32 bits (aconteceu nesta medição, e o número errado quase entrou no
  roadmap). O gerador da C12.1 tem de tratar as duas formas.
- **Carve-outs condicionais não são "não suportado".** No lado 32 bits, `Load`/`Store`
  `unprivileged`, `BranchExchange` com `link`, `Alu` com `dst=15 && setFlags`, `DoubleTransfer` com
  PC etc. são recusas **por caso**, com motivo registrado no Javadoc. Contá-los como Kinds faltantes
  infla o gap; ignorá-los esconde trabalho real. A C12.1 tem de distinguir as duas categorias.
- **Limite de 64 KB por método JVM.** Blocos grandes com SIMD emitido lane a lane podem estourar —
  risco real da opção (1) da C12.6, e um motivo a mais para preferir a (2).
