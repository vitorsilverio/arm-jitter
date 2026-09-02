# A10 — Truffle: fazer funcionar de verdade: épico

**Trilha:** A · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano (2026-09-02)

Documento MESTRE do épico. Nasce da medição de 2026-09-02 (pedido do usuário: *"ainda temos um grande
trabalho depois para fazer o jit de tudo e o truffle realmente funcionar"*) — a **dimensão 3** do
`tasks/ROADMAP-100-ARM.md`.

A trilha A levou o Truffle de zero (A0) a um backend com nós especializados (A6), factory pública
(A4), native-image (A5/A7/A8) e `.dll`/`.so` com API C (A9). **O que ninguém mediu foi a cobertura** —
e a medição mostra que o problema não é "faltam ops": é que **o backend quebra**.

## O número (medido)

| Item | Estado |
|---|---|
| `IrOpNodeFactory` (32 bits) | **40 de 73** Kinds; o resto cai em `default -> throw new IllegalStateException("IrOp kind desconhecido: ...")` |
| `TruffleCodeEmitter#supports` | **`return true` sempre** — o Javadoc admite: *"continua sempre `true` e o fallback continua inatingível na prática"* |
| Truffle para **AArch64** | **não existe** — zero nós de 64 bits no módulo `truffle/` |

### Os 33 Kinds que fazem o Truffle explodir

`BIT_FIELD_EXTRACT`, `BIT_FIELD_INSERT`, `BIT_REVERSE`, `DIVIDE`, os **14 de VFP** (`VFP_ALU`,
`VFP_MOVE_IMMEDIATE`, `VFP_COMPARE`, `VFP_CONVERT`, `VFP_LOAD`, `VFP_STORE`,
`VFP_MULTIPLE_TRANSFER`, `VFP_CORE_TRANSFER`, `VFP_CORE_PAIR_TRANSFER`, `VFP_SYSTEM_TRANSFER`,
`VFP_CORE_PAIR_TRANSFER_SINGLE`, `VFP_CONVERT_FIXED`, `COPROCESSOR_DOUBLE`,
`M_PROFILE_SYSTEM_REGISTER`), `BREAKPOINT`, `DSP_DUAL_MULTIPLY`, `DSP_TOP_WORD_MULTIPLY`, `HVC`,
`SMC`, `ERET`, `MRS_BANK`, `MSR_BANK` e os **7 de NEON** (`NEON_THREE_SAME`,
`NEON_LOAD_STORE_MULTIPLE`, `NEON_LOAD_STORE_SINGLE`, `NEON_LOAD_ALL_LANES`, `NEON_PAIRWISE`,
`NEON_FP_THREE_SAME`, `NEON_FP_PAIRWISE`).

**Combinação fatal**: `supports` diz "sim" para qualquer bloco, e a factory lança para 33 dos 73
Kinds. Ou seja, **qualquer binário ARM com uma instrução de ponto flutuante mata o backend Truffle
com `IllegalStateException`** — não degrada, não cai para o interpretador: quebra.

Isso é o achado que a RFC B13.2 registrou como *"pré-existente, verificado"* e que a B13.6 herdou
como pendência. Aqui ele está quantificado, e é a razão de a **A10.1** vir antes de tudo.

### Por que não apareceu antes

Os testes do módulo `truffle/` (13, verdes) e os consumidores exercitam o caminho que a A2/A3
cobriram — inteiro ARM/Thumb. O `gbaemu` roda `ARMV4T` (sem VFP), o `ndsemu` `ARMV5TE` (sem VFP), e
o `armbox`/`virtual-arm-box` não usam o backend Truffle por padrão. **A superfície quebrada nunca foi
exercitada** — é bug latente, não regressão.

## Escada

| Task | Escopo | Depende de |
|---|---|---|
| **A10.1** | **Parar de mentir** (correção, não feature): `TruffleCodeEmitter#supports` passa a consultar a factory de verdade — um `IrOpNodeFactory.supports(IrOp)` novo (o mesmo `switch`, devolvendo `boolean`), e blocos com Kind não coberto vão para o `fallback` que já existe e já está fiado. Troca **exceção** por **degradação correta**. Teste: um bloco com `VfpAlu` executa certo no backend `TRUFFLE` (via fallback) e `fallbackBlockCount` incrementa | — |
| **A10.2** | **Medir**: coluna Truffle em `docs/COBERTURA-JIT.md` (o relatório que a **C12.1** cria). Sem isso a cobertura Truffle volta a apodrecer em silêncio — foi exatamente o que aconteceu de A6 até aqui | C12.1, A10.1 |
| **A10.3** | Nós de **VFP** (14 Kinds) — `VfpOpNode` novo, delegando a `executor.vfpExecutor()`, no padrão das 7 categorias da A6 | A10.1 |
| **A10.4** | Nós de **bitfield/aritmética v7** (`BIT_FIELD_EXTRACT`/`BIT_FIELD_INSERT`/`BIT_REVERSE`/`DIVIDE`) — provavelmente cabem no `AluOpNode` existente | A10.1 |
| **A10.5** | Nós de **sistema restante** (`HVC`/`SMC`/`ERET`/`MRS_BANK`/`MSR_BANK`/`BREAKPOINT`/`M_PROFILE_SYSTEM_REGISTER`) — provavelmente cabem no `SystemOpNode` | A10.1 |
| **A10.6** | Nós de **DSP** (`DSP_DUAL_MULTIPLY`/`DSP_TOP_WORD_MULTIPLY`) — provavelmente cabem no `MultiplyOpNode` | A10.1 |
| **A10.7** `[REFINAR]` | Nós de **NEON** (7 Kinds e crescendo com B13). Mesma pergunta em aberto da **C12.6**: nó por lane vs nó que chama `AdvSimdLanes`. Decidir junto, não em separado | A10.3, C12.6 |
| **A10.8** `[REFINAR]` | **Truffle para AArch64** — não existe nada. Épico dentro do épico: espelhar `IrOpNode`/factory/emitter para `Ir64Op` (95 Kinds). Precisa de RFC própria: vale a pena, ou o backend ASM 64 (C12) já cobre o caso de uso? | A10.7, C12.9 |
| **A10.9** | **Fechamento**: remedir, atualizar `docs/VALIDACAO-ARQUITETURAS.md` | A10.1-A10.8 |

**A10.1 é a única task URGENTE do épico** — é uma correção pequena que troca um crash por
comportamento correto, e não depende de nada. Todo o resto é expansão de cobertura e pode esperar.

## Meta

O backend Truffle **nunca quebra** (degrada para o fallback quando não cobre), a cobertura é medida
em `docs/COBERTURA-JIT.md`, e a decisão sobre AArch64 está registrada com número — não por omissão.

## Invariantes específicos

- **G1**: o interpretador é o oráculo. Todo nó novo produz estado idêntico, validado pelo
  `BlockEquivalenceHarness`.
- **G3**: `TruffleJitRuntimeFactory` é API pública desde a A4 — assinaturas não mudam.
- **A10.1 não é otimização, é correção.** Não medir performance para justificá-la: hoje o caminho
  alternativo é uma exceção.
- **O `switch` da factory é INÓCUO para partial evaluation** (roda na emissão, não em runtime) — o
  Javadoc de `IrOpNodeFactory` explica. Crescer o `switch` não degrada o Graal; **não** "otimizar"
  isso com reflexão ou tabela dinâmica.

## Armadilhas conhecidas

- **O fallback existe, está fiado e nunca foi exercitado.** `TruffleCodeEmitter` já tem `fallback`,
  `fallbackBlockCount` e o `if (!supports(block))` — só nunca é `false`. A A10.1 é
  desproporcionalmente barata em relação ao que conserta; a armadilha é **assumir que ela é grande** e
  adiá-la.
- **`supports` é por BLOCO, a factory é por OP.** `supports(IrBlock)` precisa varrer as ops e
  perguntar por cada uma (`AsmNativePolicy` já faz assim — espelhar).
- **Cuidado com "provavelmente cabem no nó X"** (A10.4/A10.5/A10.6): é hipótese estrutural, não
  medição. Cada uma dessas tasks começa **conferindo qual executor a op usa de verdade**
  (`IrBlockExecutor` tem executores separados) antes de escolher o nó — enfiar um Kind no nó errado
  faz o `switch` interno do nó dar `default` em runtime, que é o mesmo crash com outro endereço.
- **Não confundir com a dimensão 2.** Truffle e ASM são backends **independentes**: uma op nativa no
  ASM (C12) continua ausente no Truffle e vice-versa. As duas colunas do `docs/COBERTURA-JIT.md`
  existem por isso.
