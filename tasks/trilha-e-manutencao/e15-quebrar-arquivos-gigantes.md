# E15 — Simplificação estrutural: arquivos pequenos, dispatch sem `switch` gigante, decoder por tabela, JaCoCo 100%

**Trilha:** E · **Repo:** arm-jitter (+ G5 nas sub-tasks que tocam código de 32 bits) ·
**Depende de:** — · **Tipo:** épico — não executar
este arquivo; pegar a próxima sub-task ⬜ da escada.
**Status:** 🟡 replanejada em 2026-10-02 (a versão de 2026-09-27 só dividia arquivos; o usuário
pediu código mais simples, sem as cascatas de `switch`/`if` de feature, e 100% testável com JaCoCo).

## Por que o plano anterior não bastava

A E15 original proibia abstração nova ("refactor puro, arquivo muda, `.class` não"). Dividir
arquivo reduz custo de contexto, mas **não** tira nenhum `switch`, nenhum `if` de feature e nenhum
branch do relatório JaCoCo. Os três problemas têm a mesma raiz e precisam ser atacados juntos.

## Diagnóstico medido (2026-10-02)

**Tamanho** (`wc -l`, `core/src/main`): `Aarch64Decoder` 7842 · `Ir64Op` 5784 · `IrOp` 5036 ·
`AdvSimdLanes` 3677 · `AsmBlockCompiler` 2575 · `IrSystemExecutor` 2508 · `Ir64BlockExecutor` 2335 ·
`Aarch64Core` 1848. 14 arquivos acima de 1000 linhas. Maior método: `decodeAdvancedSimdInteger`,
519 linhas.

**Cobertura** (`core/target/site/jacoco/jacoco.csv`): linha 92,4% (2791 perdidas de 36817), branch
87,5% (3248 perdidos de 26075); 731 de 878 classes já estão em 100%.

**Raiz 1 — o conhecimento de cada op está espalhado.** Um `IrOp` novo exige registro em até 8
lugares: `permits` + `Kind` + `record` (`IrOp`), `IrBlockExecutor#execute` (`switch` por `Kind`),
`IrBlockExecutor#executeOp` (segundo `switch`, por tipo, com os mesmos 189 casos),
`AsmNativePolicy#supports`, `AsmBlockCompiler` (`switch` de emissão), `DeadCodeEliminationPass`
(`regUse` + `regDef`), `IrOpNodeFactory` (Truffle). Esquecer um deles só aparece em runtime
(`default -> throw`). Essas classes são só roteamento, sem lógica, e concentram 458 dos branches
perdidos: `DeadCodeEliminationPass` 203, `IrBlockExecutor` 131, `AsmNativePolicy` 124 (é o achado
da E14, generalizado). No A64 o dispatch é duplo: `executeBlock` faz `switch (kinds[i])`, cai em
`default -> executeOp`, que chama `op.kind()` virtual e faz um segundo `switch`.

**Raiz 2 — feature gating por cascata.** `Aarch64Decoder` tem 97 `architecture.has(...)`, quase
todos no formato `if (has(FEAT)) { op = tenta(word); if (op != null) return op; }`, com a ordem
justificada por comentário ("nunca colide — conferido exaustivamente"). São 4 branches por feature
e a exclusão mútua não é verificada por máquina.

**O que já existe e aponta a saída:** `SmeArrayVectorRows`/`SmeArrayIndexedRows`/
`SmeConstructiveRows` decodificam por tabela `mask`/`value` (uma linha por encoding do `.decode`);
os executores A64 de SVE/SME já são `static execute(core, op)` por família;
`JitCoverageReport#instantiate` já instancia qualquer `record` do IR por reflexão.

## Decisões de desenho

**D1 — `op.execute(...)` no lugar do `switch` de dispatch (a ideia do usuário, com um ajuste).**
Cada `record` ganha uma ponte de 1 linha para o executor da sua família; a lógica continua nos
executores (pôr a lógica dentro do `record` só mudaria o arquivo gigante de lugar):

```java
record SveIntegerUnpredicated(...) implements SveOp {
    @Override public boolean execute(Aarch64Core core) { return SveIntegerOps.execute(core, this); }
}
```

Método abstrato na interface selada: esquecer a ponte é erro de compilação, não de runtime. Os
dois `switch` de 189 casos do `IrBlockExecutor` e o de 212 do `Ir64BlockExecutor` somem.

**O ajuste: sem parâmetro "modo JIT/interpretado".** O JIT não executa a op, ele emite bytecode uma
vez, em tempo de compilação. Um `mode` dentro de `execute` colocaria um `if` em cada op — mais
branch, não menos. São capacidades separadas: `execute` (interpretar), metadados (D2) e emissão
(D3). O Truffle fica num módulo Maven que o `core` não enxerga, então emissão não pode ser método
do `record`.

**Performance é gate, não suposição.** A C8 mediu −15,6% só mexendo em dispatch no interpretador
de 32 bits. `Cycle` e `Fetch` são 2 de cada 3 ops e hoje são inlinados pelo `switch`. Forma de
partida: `switch` mínimo só para `CYCLE`/`FETCH` + `default -> op.execute(...)` (é o que o A64 já
faz hoje, sem o segundo `switch`). Critério na E15.4/E15.5.

**D2 — metadados no `record`.** `regUse()`/`regDef()` com `default 0` em `IrOp`, sobrescritos só
pelos records que tocam GPR. `DeadCodeEliminationPass` perde os dois `switch` de ~60 casos e vira
o laço de vivência puro.

**D3 — backends: um registro de emissores por `Kind`, e a política derivada dele.** No
`AsmBlockCompiler`, tabela `emissores[Kind]` (emissor + predicado opcional, ex.: `dst != 15`).
`AsmNativePolicy.supports(op)` passa a ser "tem emissor e o predicado aceita": política e
compilador não podem mais divergir, e as 123 linhas `Xxx ignored -> false` (mais as 56
`ignored -> true`) desaparecem (ausência = interpretado). Os emissores saem para classes por família
(`AsmAluEmitter`/`AsmMemoryEmitter`/`AsmVfpEmitter`/...).

**D4 — IR por família, via sub-interfaces seladas.** `IrOp permits IntegerOp, MemoryOp, BranchOp,
SystemOp, VfpOp, NeonOp, MveOp`, cada uma no seu arquivo com seus records aninhados; idem
`Ir64Op` (`Integer`/`Fp`/`AdvSimd`/`Sve`/`Sme`). `switch` por padrão continua exaustivo pela
hierarquia selada. **Muda nome de tipo** (`IrOp.NeonThreeSame` → `NeonOp.ThreeSame`) — ver "Decisão
do usuário".

**D5 — decoder por tabela (estilo decodetree), generalizando os `Sme*Rows`.**

```java
record DecodeRow<T>(int mask, int value, Feature requires, WordDecoder<T> build)
```

A feature vira coluna da linha: a cascata de `if (has(...))` some. A tabela é indexada por bucket
dos bits de classe na inicialização (decode continua O(1) amortizado). Dois testes saem de graça
da própria tabela: **sobreposição** (duas linhas só podem casar a mesma palavra se estiverem num
grupo de prioridade explícito — substitui os comentários "conferido exaustivamente" e é o
invariante G8 verificado por máquina) e **alcance** (para cada linha, gera uma palavra que casa e
afirma que a linha responde — toda linha é exercitada por construção). Migração grupo a grupo,
com o decoder antigo como oráculo diferencial até o grupo fechar.

**D6 — JaCoCo vira gate com catraca.** `jacoco:check` no `verify`, com piso = valor medido; o
piso só sobe. Pacote que uma sub-task migra passa a exigir 100% linha+branch. `default -> throw
"unreachable"` é a causa estrutural de branch incobrível: a regra é `switch` exaustivo sobre
`enum`/selado, sem `default`.

**D7 — limite de tamanho com catraca.** Teste-guarda que falha se algum fonte de `src/main` passar
de 800 linhas fora de uma lista de exceções que só encolhe (mesmo padrão do
`JitCoverageReportGuardTest`). Sem isso os arquivos voltam a crescer.

## Escada

Ordem: rede de segurança → mover sem mudar lógica → trocar dispatch → decoder → fechar cobertura.
G5 = suites de gbaemu/ndsemu obrigatórias (regra do `tasks/README.md`).

| Task | Escopo | G5 | Status |
|---|---|---|---|
| [E15.1](e15.1-rede-de-seguranca-contrato-e-catraca.md) | Testes de contrato por `record` do IR (absorve a E14) + catraca JaCoCo + guarda de tamanho. Zero mudança em `src/main` | não | ✅ 2026-10-02 |
| [E15.2](e15.2-ir64op-por-familia.md) | `Ir64Op` em sub-interfaces seladas por família (D4) | não | ✅ 2026-10-02 |
| [E15.3](e15.3-irop-por-familia.md) | `IrOp` idem (D4) | sim | ✅ 2026-10-02 |
| [E15.4](e15.4-ir64op-execute.md) | A64: `Ir64Op#execute` (D1), remove o dispatch duplo; `Ir64BlockExecutor` dividido por família | não | ✅ 2026-10-02 |
| [E15.5](e15.5-irop-execute.md) | 32 bits: `IrOp#execute` (D1), funde `execute`+`executeOp`; **gate: `InterpretedThroughputBenchTest` (gbaemu, C8) ≥ −1%**, senão manter `switch` para os `Kind` quentes medidos | sim | ✅ 2026-10-02 (12 `Kind` quentes ficaram no laço) |
| [E15.6](e15.6-regmask-nos-records.md) | `regUse`/`regDef` nos records (D2); DCE sem `switch` | sim | ✅ 2026-10-02 |
| [E15.6b](e15.6b-dce-gpr-nao-declarados.md) | DCE: ~39 records que leem GPR sem declarar `regUse()` (bug latente do JIT) + teste-guarda | sim | ✅ 2026-10-02 |
| [E15.7](e15.7-asm-emissores-por-familia.md) | Registro de emissores ASM 32 bits + política derivada (D3); `AsmBlockCompiler`/`AsmRuntimeHelpers` por família | sim | ✅ 2026-10-03 (bytecode idêntico) |
| [E15.8](e15.8-mve-executor-por-familia.md) | `IrSystemExecutor` (2516) → sistema + 5 executores MVE por família; `IrBlockExecutor#mveMoveExecutor()` sob demanda (D8) | sim | ✅ 2026-10-03 (deslocamento puro) |
| [E15.9](e15.9-decode-table-piloto.md) | Infra `DecodeTable` (D5) + **piloto**: a cascata `bit21=0` de `decodeAdvancedSimdInteger` (FP16/FP8/FAMINMAX/FP8FMA/FP8DOT2/FP8DOT4/FCMA). Gate de go/no-go: linhas, branches e tempo de lift antes/depois | não | ✅ 2026-10-03 (go; corrigiu um G8 do FP16) |
| [E15.9b](e15.9b-advsimd-bit31-ignorado.md) | Achados da E15.9: `bit31` e `bits[23:22]` do copy não conferidos (G8); 5 `F*P_v` `_h` ausentes, medidas ✅ por misdecode | não | ✅ 2026-10-03 (achou +1 838 palavras de resíduo → E15.9c) |
| [E15.9c](e15.9c-advsimd-fp-residuo-g8.md) | Resíduo G8 do espaço AdvSIMD/FP escalar medido por oráculo + `objdump`; guarda permanente contra ✅ por misdecode | não | ✅ 2026-10-03 (resíduo 1 838 → 0; 19 412 → 0 com `Rm` enumerado) |
| [E15.10](e15.10-dp-imediato-por-tabela.md) | Classe DP-imediato → `DataProcessingImmediateRows` (28 linhas); `WordDecoder` ganha o endereço | não | ✅ 2026-10-03 (fechou 3 G8: 30 720 palavras) |
| [E15.10b](e15.10b-cache-de-decode-no-step-a64.md) | Cache `pc → Ir64Op` no `step` interpretado A64 (o decode por tabela custa +8 ns/instrução; decisão do usuário: cache em vez de gate no formato da tabela) | não | ⬜ [REFINAR] |
| E15.11–E15.15 | `Aarch64Decoder` grupo a grupo para tabela, um arquivo por grupo: branch/exceção/sistema (inclui `decodeSystemRegisterId` → encoding no próprio `Aarch64SystemRegisterId`) · load/store · DP-registrador · FP escalar · AdvSIMD | não | ⬜ [REFINAR] após E15.9 |
| E15.16 | Decoders de 32 bits (`ArmDecoder`, `VfpDecoder`, `Thumb2*`) para tabela | sim | ⬜ [REFINAR] após E15.15 |
| E15.17 | `AdvSimdLanes` (3677) por família de operação; `Aarch64Core` (1848): banco de sysreg para fora | sim | ⬜ [REFINAR] |
| E15.18–E15.21 | Fechar o resíduo semântico até 100%, um pacote por task: `advsimd` · `codegen.jvm` · `core`/`memory.mmu` · `debug` (`GdbServer` por socket de loopback) | conforme pacote | ⬜ [REFINAR] |
| E15.22 | `jacoco:check` em 100% linha+branch no `core`; lista de exceções de tamanho vazia | não | ⬜ [REFINAR] |

`AdvSimdLanes` (E15.17) pode ser adiantada a qualquer momento depois da E15.1 — não depende das
outras.

**D8 — famílias sob demanda.** Hoje `IrBlockExecutor` instancia os oito executores no construtor e
`Aarch64Decoder` constrói os decoders de SVE/SME mesmo num preset sem essas features. Com D1 e D5
o executor/tabela de uma família só é tocado (e a classe só é carregada pela JVM) quando a
primeira op dela aparece, ou quando o preset declara a feature. Nota: cada `record` já é um
`.class` próprio carregado sob demanda — D4 melhora a leitura, o ganho de carga de classe vem
daqui.

## Decisão do usuário (2026-10-02) — exceção ao G3 em `ir`/`ir64`

**Aceita a quebra de nome**: IR em arquivos pequenos por família vale mais que preservar
`IrOp.Xxx`/`Ir64Op.Xxx`. Exceção ao G3 restrita aos tipos dos pacotes `ir` e `ir64`, na `1.4.0`
(ainda não publicada); entra no `CHANGELOG.md` com a tabela de/para. Medido nos 5 consumidores:
3 referências no total a records do IR (`IrOp.Load`, `IrOp.VfpLoad`, `Ir64Op.ShiftVariable`),
nenhuma a `Kind` — ajustadas na mesma task que renomeia. E15.2/E15.3 seguem a forma completa de
D4 (sem a alternativa de manter os records inteiro/sistema dentro de `IrOp`).

⚠️ **Correção de fato (achado da E15.2, 2026-10-02):** a `1.4.0` JÁ está no Maven Central
(`arm-jitter-1.4.0.pom` → `200`, tag `v1.4.0`). A quebra de nome sai na próxima versão.

**Decisão do usuário (2026-10-02): a versão que sair depois do refactor é a `2.0.0`** — a API
muda demais para uma minor. Com isso as quebras de nome do épico deixam de ser "exceção ao G3" e
passam a ser breaking change de major, todas listadas em `[Não lançado]` no `CHANGELOG.md` com
tabela de/para. O `pom.xml` segue em `1.4.0` enquanto o refactor estiver em andamento; o bump
para `2.0.0` é do release (procedimento F5 + F7 nos consumidores), não de uma sub-task.

## Pré-condição

Árvore limpa antes de qualquer sub-task que mova código — um refactor por cima de diff pendente
não é revisável. (O diff pendente do boot raspi3-64 foi commitado em 2026-10-02, `d8b6828`.)

## Não inclui

- Mudar semântica de instrução (G1) — toda sub-task fecha com as suites existentes verdes e
  `docs/COBERTURA-ISA.md` em 29619/29619.
- Gerar tabelas a partir dos `.decode` do QEMU (licença diferente da BSD-3 do projeto; eles seguem
  só como inventário de medição em `target/`).
- Emissão nativa nova (C12) ou nós Truffle novos (A10) — D3 só reorganiza o que já existe.
- Arquivos de teste gigantes (`Aarch64DecoderCorpusTest` 4307) — ficam para depois da E15.15,
  quando o teste de alcance da tabela tornar parte do corpus redundante.

## Armadilhas

- "Arquivo pequeno" não é o objetivo; é consequência. Dividir um `switch` de 189 casos em quatro
  de 50 não resolve nada — a sub-task só fecha se o `switch`/cascata tiver sido eliminado ou
  virado dado.
- Lambda não "esconde" cobertura: o JaCoCo conta o corpo de cada lambda como método. Tabelas devem
  usar referência de método para código que já tem teste, e o teste de alcance cobre a tabela.
- `record` não estende classe: a ponte `execute` é método de interface (`invokeinterface`). É por
  isso que a E15.5 tem gate de bench e a E15.4 não assume nada do resultado da outra.
- Sub-interface selada exige que `JitCoverageReport`, `JitCoverageReportGuardTest` e qualquer
  código com `getPermittedSubclasses()` passem a descer a hierarquia recursivamente.
- `ADVANCE_VPT`/`ADVANCE_ECI` dependem de `pcChanged` acumulado no bloco e `SWI`/`HVC`/`SMC`/`ERET`
  de `block.endPc()` — a assinatura de `IrOp#execute` precisa carregar os dois.
