# RFC — Generalização do IR para 64 bits (pré-requisito do AArch64 / B6)

**Status:** **APROVADA em 2026-07-10 (Opção B — IR-64 paralelo)** pelo usuário. A fase F0 do
plano de migração (§6) está cumprida; F1+ entram quando forem priorizadas. As decisões da §5
("Decisões que B1–B3 devem respeitar desde já") estão em vigor a partir de agora — em
particular, o monitor de exclusividade de B1.4 nasce com endereço `long`.
**Task de origem:** [B0](../tasks/trilha-b-arquiteturas/b0-rfc-ir-64bit.md).
**Escopo:** só análise/decisão de desenho. Nenhum código de produção muda com este documento.

---

## 1. Inventário do acoplamento a 32 bits (estado 2026-07-10)

Levantado por leitura das interfaces públicas de `ir/`, `memory/`, `core/`, `codegen/`, `jit/` e `debug/`:

| Ponto | Acoplamento | Observação |
|-------|-------------|------------|
| `ir/IrOp` (23 subtipos selados) | Todos os valores/endereços/imediatos são `int`: `LoadLiteral.address`, `Branch.target/returnAddress`, `Fetch.address`, `Coprocessor.sequentialPc`, etc. | Os campos `*ValueOverride` usam **`-1` como sentinela** de "sem override" — seguro hoje porque só são preenchidos quando a origem é o PC (endereço alinhado, nunca `0xFFFFFFFF`). Em 64 bits o padrão sentinela teria que ser repensado. |
| `ir/IrOp.Kind` | `tableswitch` do interpretador sobre 23 kinds contíguos | Qualquer conjunto de ops novo precisa da MESMA propriedade (kinds contíguos a partir de 0) para manter o dispatch O(1). |
| `memory/AddressSpace` | `int address` em read/write/accessCycles/notifyWrite | Implementada por **todos os hospedeiros** (gbaemu, ndsemu, armbox) e decorada por `InvalidationAwareAddressSpace`. Mudar a assinatura quebra todo mundo (viola G3). |
| `core/ArmCore` | `int register(int)`, `int programCounter()`, banco banked por `CpuMode`, `CpsrRegister` | O modelo de banking (FIQ/IRQ/SVC...) e o PC-como-registrador são ARM 32. AArch64 não tem banking de GPRs, não tem PC como GPR, tem SP por EL e `PSTATE` em vez de CPSR/SPSR. |
| `codegen/jvm/GuestToHostMapper` + `AsmBlockCompiler` | Register cache em locals JVM de **1 slot** (`int`) | `long` ocupa 2 slots; toda a alocação de locals, flush/reload e os helpers (`AsmRuntimeHelpers`) assumem `int`. |
| `jit/BlockKey` | `record BlockKey(int pc, InstructionSet)` | O inline cache empacota `tag = pc | instructionSet.ordinal() << 32` num `long` — **incompatível com PC de 64 bits** (não há bits sobrando). |
| `jit/CompiledBlock` | `int execute(ArmCore core)` | Assinatura amarrada ao `ArmCore`. |
| `codegen/equivalence/CpuSnapshot` + harness | `int[] registers, int cpsr` | Pequeno e barato de duplicar. |
| `debug/GdbServer` | Layout `g` legado ARM (r0-r15 + CPSR) | O protocolo GDB exige um layout de registradores POR arquitetura (A64: X0-X30, SP, PC, PSTATE + target.xml próprio). |
| `ir/ConstantFoldPass` etc. | Aritmética de fold em `int` | Semântica de wrap/carry/shift muda em 64 bits — não é só trocar o tipo. |
| `swi/CpuState` | `int pc` | Interface do dispatcher SWI/syscall (armbox usa para syscalls Linux). |

Conclusão do inventário: o `int` não é um detalhe localizado — é o **contrato de ponta a ponta** do pipeline 32-bit em produção (2 emuladores + armbox). Qualquer opção que toque esse contrato carrega risco direto para ARMv4T/v5TE.

## 2. As duas opções

### Opção A — IR parametrizado por largura

Um único conjunto de `IrOp` com largura 32/64 (campo `width` ou generics), executores e emissores tratando as duas larguras.

**Prós:** um só otimizador/interpretador/emissor para manter; sem duplicação de infraestrutura.

**Contras (decisivos):**
- **Risco de regressão máximo:** reescreve o caminho quente EM PRODUÇÃO (interpretador `tableswitch`, `AsmBlockCompiler`, register cache) por causa de uma arquitetura futura. Viola o espírito de G3 e o histórico do projeto (lições das Tasks 3+4: mexer em caminho quente sem ganho medido é custo puro).
- **Perf 32-bit paga a conta:** ou tudo vira `long` com máscara para 32 (2 slots por local, aritmética mais cara, register cache dobra de tamanho), ou cada op ganha um branch por largura — ambos pioram exatamente o código que hoje roda a 99–115% de realtime.
- **A parametrização é uma ilusão de reuso:** A64 não é ARM32 "mais largo". Não tem predicação por condição em quase nada, não tem LDM/STM (tem LDP/STP), flags via `PSTATE`, W/X como vistas do mesmo registrador, endereçamento próprio. Os records atuais (condition em tudo, `MultipleTransfer`, `Push/Pop` THUMB, `ThumbBlPrefix`...) simplesmente não descrevem A64. Parametrizar largura não evita escrever ops novos — só contamina os existentes.
- **Sentinelas `-1` deixam de ser óbvias** e a legibilidade dos 23 records piora para os dois públicos.

### Opção B — IR-64 paralelo (frontend irmão)

O frontend A64 ganha seu próprio conjunto selado de ops (`ir64/A64Op` ou similar), interpretador-oráculo, otimizador e emissor, **compartilhando a infraestrutura genérica**: `BlockCache`/tiering/chaining/superblocos (generalizados por interface onde preciso), o padrão do harness de equivalência, o `GdbServer` (com layout por arquitetura) e o desenho geral do pipeline.

**Prós:**
- **Risco zero para ARMv4T/v5TE:** o pipeline 32-bit não é tocado. gbaemu/ndsemu nem recompilam diferente.
- Cada IR descreve sua arquitetura idiomaticamente (o precedente interno é exatamente este: ARM e THUMB já são dois decoders que convergem no mesmo IR **porque têm a mesma semântica** — A64 não tem, logo merece IR próprio).
- O custo real de A64 está no decoder + semântica de ops + emissor de qualquer forma; a duplicação extra (loop do interpretador, passes de fold) é pequena e trivial comparada a isso.

**Contras:** alguma duplicação de infraestrutura (executor loop, passes, harness) e dois lugares para corrigir bugs de infraestrutura — mitigável extraindo as partes genuinamente neutras (cache/tiering/dispatch) para tipos parametrizados ANTES de escrever o frontend.

## 3. Recomendação

**Opção B — IR-64 paralelo, com extração prévia da infraestrutura neutra de runtime.** Complementos de desenho:

1. **`Aarch64Core` irmão, não extensão do `ArmCore`.** Banco `long[31]` + SP/EL + `PSTATE`; sem banking de GPR; PC fora do banco. `ArmCore` e `Aarch64Core` implementam uma interface mínima comum apenas onde a infraestrutura neutra precisa (ciclos, trace, halt) — não forçar um super-tipo gordo.
2. **`AddressSpace64` nova interface (`long address`), NÃO sobrecarga na existente.** Sobrecarregar `AddressSpace` com métodos `long` forçaria todos os hospedeiros 32-bit a carregar métodos mortos e abriria porta para chamar a variante errada. Um adapter `AddressSpace64.wrapping(AddressSpace)` cobre reuso de RAMs simples em testes. O utilitário C3 (`PagedAddressSpace`), quando for feito, deve nascer com o miolo (fatiamento de páginas) compartilhável entre as duas interfaces.
3. **Chave de bloco e inline cache próprios do runtime A64.** `BlockKey64(long pc)` (A64 não tem dualidade ARM/THUMB — a chave é só o PC) e um esquema de tag de IC que não dependa de empacotar PC em 32 bits. `BlockCache`/`JitRuntime` são generalizados por parâmetro de tipo (chave/bloco/core) preservando as classes públicas atuais como especializações (G3).
4. **Emissor ASM A64 com locals de 2 slots** desde o desenho do register cache (o mapeamento guest→local vira "índice de slot", não "índice de local").
5. **`CpuSnapshot64` + harness A64 próprios**, espelhando o padrão atual (é pequeno; duplicar é mais barato que parametrizar arrays).
6. **`GdbServer` ganha uma abstração de layout de registradores por arquitetura** (target description) — o stub de protocolo é reaproveitado, o layout não.
7. **Fold/otimizador A64 em `long` em passes próprios** — a semântica de flags/shift de A64 é outra; não há o que reusar do fold 32-bit além do padrão.

## 4. Impactos respondidos (checklist da task)

- **`IrBlock`/`BlockKey` (PC 64):** não mudam — o mundo 32-bit os mantém; A64 tem `A64Block`/`BlockKey64` (ver §3.3).
- **`CpuSnapshot`:** intocado; `CpuSnapshot64` paralelo.
- **Harness de equivalência:** o padrão (oráculo interpretado vs candidato + snapshot diff) é replicado; a classe atual não muda.
- **`GdbServer`:** ganha o seam de layout (§3.6) quando a trilha B precisar (VFP de B3 já vai exigir — ver §5).
- **Otimizador:** passes A64 próprios (§3.7).

## 5. Decisões que B1–B3 devem respeitar desde já

1. **B1–B3 NÃO alargam o IR.** Todo o user-level 32-bit (v6K, Thumb-2, v7) continua no `IrOp` de `int` — inclusive VFP (B3), que deve entrar como **grupo de ops próprio com banco FP separado no core** (mesmo padrão banco-paralelo que o A64 seguirá), não como widening dos ops inteiros.
2. **Interfaces públicas NOVAS que A64 vá reusar nascem neutras.** Exemplo concreto: o **monitor de exclusividade de B1.4 (LDREX/STREX)** — A64 usa o mesmo conceito (LDXR/STXR); a interface do monitor deve aceitar `long address` (ou ser parametrizada) desde o dia 1, mesmo que o chamador 32-bit passe `int` estendido. Custa zero agora e evita a segunda versão depois.
3. **Não introduzir novos empacotamentos "PC cabe em 32 bits"** (como a tag do IC atual) em código de infraestrutura nova; se precisar de tag, isolar a estratégia de tag por runtime.
4. **B2 (Thumb-2/IT blocks) não deve assumir que "todo op tem condição útil"** em APIs novas de infraestrutura — em A64 a condição não existe na maioria dos ops; o guard condicional é detalhe do frontend 32-bit.
5. **Quando B3 tocar o `GdbServer`** (registradores VFP no layout), implementar já o seam de layout por arquitetura (§3.6) em vez de estender o layout fixo.

## 6. Plano de migração em fases (cada uma mergeável, gbaemu/ndsemu verdes — G5)

| Fase | Entrega | Gate |
|------|---------|------|
| **F0** | Este RFC aprovado | Aprovação do usuário |
| **F1** | Generalizar `BlockCache`/tiering/dispatch por parâmetros de tipo, mantendo as classes públicas atuais como aliases/especializações (zero-diff de comportamento e de bytecode quente — validar com bench A/B) | Suites arm-jitter/gbaemu/ndsemu + bench sem regressão |
| **F2** | `Aarch64Core` + `AddressSpace64` + decoder A64 mínimo + `A64Op` (ALU/branch/load/store) + interpretador-oráculo + harness A64 | Testes unitários por instrução |
| **F3** | Cobertura A64 user-level completa (ordem: memória → branches → multiply → LDP/STP → sistema) | binário estático arm64 "hello world" no runner user-mode (armbox64) |
| **F4** | Emissor ASM A64 (locals 2 slots, register cache por slot) | Harness + divergence-check com busybox arm64 |
| **F5** | busybox arm64 completo no runner; depois MMU v8 (B6.5, RFC própria) | Aceite incremental do B6 |

F1 é a única fase que toca código 32-bit em produção — por isso vem sozinha, cedo, com bench A/B obrigatório antes/depois (lição registrada: não confiar em "deveria ser neutro").

---

*Referências: `ROADMAP.md` §Trilha B (B6), `tasks/trilha-b-arquiteturas/b0-rfc-ir-64bit.md`, precedente interno decoder-ARM/THUMB→IR único, memória do projeto sobre lições de perf (Tasks 3+4, VarHandle neutro).*
