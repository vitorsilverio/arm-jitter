# RFC — Núcleo vetorial compartilhado (NEON de 32 bits × AdvSIMD do A64)

**Status: APROVADA COM PROTÓTIPO MEDIDO, 2026-08-29** (sessão de modelo forte, task `B13.2`).
Destrava B13.3+ e, por tabela, B16 (MVE). Se o usuário discordar de qualquer decisão abaixo,
reabrir ANTES de B13.3 — depois dela o custo de mudar de rumo cresce a cada família implementada.

## Pergunta

O projeto já implementou quase toda a superfície AdvSIMD do **A64** (B8.6-B8.20: ~500 encodings,
`ir64` + `executor64`). O épico B13 traz a MESMA semântica vetorial com outro encoding para o lado
de **32 bits** (325 encodings NEON A32/T32), e B16 (MVE, 352 encodings) vem logo atrás. Duas
saídas, e escolher errado custa o épico inteiro:

- **(a) espelhar** — `IrOp`s vetoriais próprios no pipeline de 32 bits, semântica reescrita.
  Isolado, mas duplica ~500 encodings de comportamento já testado e cria DUAS fontes de verdade
  para a mesma regra ARM (exatamente a divergência que o invariante G1 existe para evitar).
- **(b) extrair o núcleo vetorial** — a semântica de lane vira um módulo compartilhado que os dois
  pipelines chamam, com `IrOp`/`Ir64Op` seguindo separados.

## Decisão D1 — **(b), extração; e o substrato compartilhado é a PALAVRA de 64 bits**

Adotada a opção (b). O ponto não óbvio — e o motivo de esta RFC ter exigido protótipo em vez de
argumento — é **em que nível a extração acontece**. Três candidatos foram avaliados com o código na
mão:

| Candidato | Por que NÃO / SIM |
|---|---|
| Superclasse comum dos dois bancos | ❌ viola a disciplina do B6 (nunca misturar os dois mundos) e a própria B13.1, que decidiu "espelhar a interface, não extrair superclasse" |
| Interface por REGISTRADOR (`element(reg, lane, esz)`) implementada pelos dois bancos | ❌ **não expressa o operando NEON de 64 bits em `D` ÍMPAR**: no A64 o `element` é indexado por `V<n>` de 128 bits, e a B13.1 espelhou isso indexando por `Q<n>` — mas NEON endereça `D0`-`D31` livremente (`VADD.I16 D9, D5, D7` é legal), e `D5` é a metade ALTA de `Q2`. A assimetria não é de nomenclatura, é estrutural |
| **Vista PLANA em palavras de 64 bits** | ✅ adotada |

`AdvSimdRegisterWords` expõe qualquer banco vetorial como um vetor de palavras de 64 bits:

- `Aarch64FpRegisters`: `V<n>` = palavras `2n` (bits 63:0) e `2n+1` (bits 127:64) — 64 palavras.
- `VfpRegisters`: `D<n>` = palavra `n`; `Q<n>` = palavras `2n`/`2n+1` — 32 palavras (o banco de 32
  bits já É um `long[32]` desde a B13.1, então a vista é literalmente o array).

Com isso, "operando de 64 bits em `D5`" é `baseWord = 5` e nada mais precisa saber de qual mundo o
banco veio. Nenhum elemento AdvSIMD cruza a fronteira de 64 bits (8/16/32/64 dividem 64), então a
vista é exata, não uma aproximação.

O núcleo (`AdvSimdLanes`) recebe `(banco, operação, esz, quantidade de lanes, baseRd, baseRn,
baseRm)` e **não conhece registrador, condição, nem escrita destrutiva**. O que fica com cada
pipeline é só o que realmente difere:

| Fica no pipeline | A64 | A32/NEON |
|---|---|---|
| Encoding + IR | `Aarch64Decoder` → `Ir64Op` | `NeonDataProcessingDecoder` → `IrOp` |
| Registrador → palavra | `V<n>` → `2n` | `D<n>` → `n`, `Q<n>` → `2n` |
| Escrita do destino | "SIMD&FP destructive write" (zera 127:64 quando o arranjo tem 64 bits) | nada a zerar (VFP32 nunca escreve fora do registrador nomeado) |

Essa última linha é a razão de o núcleo NÃO escrever "o registrador inteiro": ele escreve LANES, e
a política destrutiva é do chamador. Foi o único ponto em que espelhar cegamente o A64 produziria
bug silencioso.

**Migração incremental, sem duplicação em nenhum momento**: cada operação existe em exatamente UM
lugar por vez. `Ir64VectorArithmeticExecutor` consulta `sharedThreeSameOp(...)`; o que já migrou vai
para o núcleo, o que não migrou continua no `switch` local. `ADD`/`SUB` migraram nesta RFC (as 2723
asserções da suíte, incluindo as de `ADD_v`/`SUB_v` de B8.7, passam sem alteração — é a prova de
zero-diff que a extração precisava). As demais migram junto com a família correspondente de B13.4+.

## Decisão D2 — **escape hatch de lifting**: o decoder vetorial entrega o `IrOp` pronto

Achado do protótipo, e a razão pela qual espelhar seria ainda mais caro do que a tabela de
encodings sugere: **o front-end de 32 bits tem um estágio a mais que o de 64**. No A64 o decoder já
devolve `Ir64Op`; no A32/T32 ele devolve `DecodedInstruction` — um record NEUTRO de campos fixos
(`destinationRegister`, `immediate`, `signedAccess`, ...) que o `StandardIrBuilder` traduz depois.

Essa forma neutra não comporta um operando vetorial: NEON precisa de operação + tamanho de elemento
+ largura do arranjo + índice de lane + deslocamento SIMULTANEAMENTE. O VFP só coube lá porque
empacota um ordinal de enum dentro de `immediate` — um truque que não escala para as 22 sub-tasks
de B13 (e menos ainda para a predicação por beat de MVE).

Decisão: `DecodedInstruction` ganha um componente `IrOp liftedOp` (`null` em tudo que existia) e um
`InstructionKind.LIFTED_IR_OP`; o `StandardIrBuilder` só adiciona a op ao bloco. Aditivo e sem
breaking change (G3): a lista de campos anterior continua existindo como construtor próprio, então
**nenhum chamador precisou mudar** — verificado compilando os 6 repositórios.

Alternativas descartadas: espremer nos campos neutros (funciona para "three same", quebra em
by-element/shift/load-store múltiplo — adiaria o problema por 2 sub-tasks); um segundo record
paralelo (mesma informação em dois formatos, e o lifter passaria a ter dois caminhos).

## Decisão D3 — a API `Q`-indexada da B13.1 fica, como conveniência

`element`/`setElement`/`setScalar`/`replicateElement` de `VfpRegisters` continuam `Q`-indexados
(B13.1). Não são a interface que a escada B13.4+ vai consumir para operandos NEON — quem faz isso é
a vista de palavras —, mas servem load/store e formas escalares, e removê-las seria breaking change
por nada.

## Custo medido (protótipo `VADD`/`VSUB` inteiro, A32, ponta a ponta)

Núcleo compartilhado + uma família completa, do encoding à execução:

| Item | Linhas |
|---|---:|
| `advsimd/` (3 arquivos novos: vista de palavras, enum, núcleo de lane) | 129 |
| `decoder/NeonDataProcessingDecoder` (novo) | 87 |
| Edições em 11 arquivos existentes (soma dos `+` do diff, Javadoc incluído) | 188 |
| Testes novos (2 arquivos) | 225 |

Das 188 linhas de edição, **~150 são a fundação que não se repete** (o escape hatch em
`DecodedInstruction`, a vista de palavras nos dois bancos, a delegação do executor A64). O custo
recorrente por família nova, daqui em diante, é: uma entrada no núcleo compartilhado, um record de
`IrOp`, um `case` em 4 lugares (`IrOp.Kind`, os dois `switch` de `IrBlockExecutor`,
`AsmNativePolicy`) e o decode em si.

**Escada afetada**: B13.4-B13.15 continuam como estão; a estimativa de esforço cai porque a
semântica de lane já existe testada do lado A64 — cada uma vira "migrar as operações da família
para `AdvSimdThreeSameOp`/vizinhos + decodificar o encoding A32". B16 (MVE) herda o mesmo núcleo,
com o `VPR`/predicação por beat entrando como parâmetro do chamador, não do núcleo.

## Achados abertos (não resolvidos aqui, candidatos a task própria)

1. **O backend Truffle quebra com QUALQUER op de VFP — pré-existente, não introduzido aqui.**
   `TruffleCodeEmitter#supports` devolve `true` sempre ("`IrOpNodeFactory` cobre exaustivamente
   todo `IrOp.Kind`"), mas `IrOpNodeFactory` não tem caso nenhum de VFP e cai no
   `default -> throw`. Verificado nesta sessão: emitir um bloco com `IrOp.VfpAlu` levanta
   `IllegalStateException: IrOp kind desconhecido: 44`. Consequência para B13: **todo `Kind`
   vetorial novo tem que entrar no `IrOpNodeFactory`** (ou o `supports` voltar a ser honesto) —
   senão o backend Truffle passa a quebrar também com NEON, na primeira arquitetura que declarar
   `ADVANCED_SIMD`. Task própria (trilha A ou E).
2. **Emissão nativa (ASM/JIT) das ops vetoriais de 32 bits**: `AsmNativePolicy` recusa
   `NeonThreeSame` (interpretado), mesma decisão de todo `Kind` vetorial do lado A64 desde B8.4.
   Vira task quando a escada B13 fechar e houver medição pedindo.
3. **Save-state**: `saveState` de `VfpRegisters` continua no formato legado (`D0`-`D15`). Quando um
   consumidor executar NEON de verdade, `D16`-`D31` precisam entrar no `.ss` dele —
   `saveStateExtended` já existe desde B13.1, falta o consumidor bumpar a própria versão.

## O que esta RFC NÃO decide

- Como as famílias com estado extra (`FPSCR.QC` de saturação NEON, arredondamento) chegam ao
  núcleo — hoje nenhuma operação compartilhada precisa disso; a primeira que precisar (`VQADD`,
  B13.5) decide se passa um contexto ou devolve um flag.
- Nada sobre T32 (B13.16): o encoding Thumb-2 é transformação do A32, e o núcleo é indiferente a
  isso.
- Nada sobre SVE/SME (B17/B18): comprimento de vetor variável é outra decisão, com RFC própria
  (`B17.2`). A vista de palavras é compatível com vetores maiores por construção (mais palavras por
  registrador), mas isso é observação, não compromisso.
