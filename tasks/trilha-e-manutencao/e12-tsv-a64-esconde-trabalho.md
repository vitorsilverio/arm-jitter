# E12 — A curadoria A64 da TSV esconde trabalho pendente nas versões em que a feature EXISTE

**Trilha:** E · **Repo:** arm-jitter (ferramenta de teste + `docs/` + 9 constantes novas) · **Depende de:** B19.5.2
**Status:** ✅ (2026-09-04) — spec RE-MEDIDA e corrigida antes de executar, ver `## Correção da spec`

## Contexto

`docs/COBERTURA-ISA.md` tem **16 colunas de versão A64** desde a B11.5 (`ARMv8.0-A` … `ARMv9.5-A`).
Mas a curadoria de `docs/isa-nao-aplicavel.tsv` é anterior a isso: ela nasceu quando o A64 era **uma
coluna monolítica chamada `A64`**, e a compatibilidade com aquelas linhas foi mantida assim
(`IsaCoverageReport#isAarch64VersionColumn`):

```java
/// `docs/COBERTURA-ISA.md` pré-B11.5 tratava A64 como uma única coluna chamada `A64` — vários
/// `docs/isa-nao-aplicavel.tsv` legados usam essa string. Para não reescrever essas linhas,
/// uma exclusão `A64` casa com QUALQUER coluna de versão A64 nova ...
private static boolean isAarch64VersionColumn(String column) {
    return AARCH64_ARCHITECTURES.containsKey(column);
}
```

O comentário descreve a intenção com honestidade — o problema é o efeito colateral. **Uma linha de
TSV que diz "esta instrução é ARMv8.3-A/`FEAT_PAuth`" fica `·` também em `ARMv8.3-A`, `ARMv8.4-A`, …,
`ARMv9.5-A`** — isto é, exatamente nas colunas onde a feature existe e a instrução é **trabalho
pendente de verdade**. O denominador some junto com a falta.

Isso contraria diretamente a regra máxima do `tasks/README.md`:

> **`docs/isa-nao-aplicavel.tsv` nunca é "está fora do nosso alvo".** A única entrada legítima é
> "esta versão de arquitetura/feature ainda não foi implementada, com a fonte que prova a versão real
> que a introduziu" — a instrução continua sendo trabalho PENDENTE, só reclassificado por degrau
> cronológico dentro do próprio ARM.

"Reclassificado por degrau cronológico" é precisamente o que **não** está acontecendo: a instrução é
reclassificada para *fora de todos os degraus*.

## Correção da spec (2026-09-04) — a primeira versão errou a medição em três pontos

A primeira redação desta spec (2026-09-03) foi escrita **antes** da B19.5.2 executar e mediu por
amostragem do texto das justificativas. Uma sessão de execução tentou segui-la, bateu de frente com
os números e **parou para reportar** — que é exatamente o comportamento que o `tasks/README.md` pede.
A medição foi refeita do zero (scripts em `medir.py`/`classificar.py`, contra
`docs/COBERTURA-ISA.md` e `target/isa-decode/a64.decode` na revisão fixada `2931a675e9d3…`). Os três
erros:

| # | O que a spec v1 dizia | O que a medição mostra |
|---|---|---|
| 1 | "**123** linhas `A64`" | **111** (a B19.5.2 já removeu 12) — e elas atingem **134 linhas da TABELA**, porque um mnemônico pode ter várias ocorrências (`LDAPR_i` tem 6, `FSCALE` 2, `CB_cond` 4, `STG`/MTE várias) |
| 2 | "só `FEAT_LSE128` (3) e `FEAT_LRCPC2` (2) não têm constante" | **8 features / 30 linhas** sem constante: `FEAT_FPRCVT` (12 linhas), `FEAT_LSE128` (3), `FEAT_LRCPC2` (7 linhas de tabela), `FEAT_FP8DOT2` (2), `FEAT_FP8DOT4` (2), `FEAT_FP8` (2), `FEAT_F8F16MM` (1), `FEAT_F8F32MM` (1) |
| 3 | escopo = "as linhas `A64`" | há um **segundo mecanismo de esconderijo**, não mencionado: linhas TSV com arquitetura `*` (todas as colunas) que atingem **9 linhas A64** — `CRC32`×4, `CRC32C`×4, `SEVL`×1, todas `·` em **16/16** colunas |

O erro #3 é o mais grave, e por um motivo que inverte o sinal da task: **`SEVL` JÁ É
IMPLEMENTADO no A64** (`Aarch64Decoder:4547`, "SEV/SEVL/WFET/WFIT (B6.6.7 + B8.3) … viram NOP puro").
A linha `SEVL	*	ARMv8-A (hint "send event local")` — escrita para o lado de 32 bits, onde é
correta — apaga **16 células `✅` reais**. A tabela não está só escondendo trabalho pendente: está
também **sub-reportando cobertura já conquistada**. Fechar a porta só do lado `A64` deixaria essa
metade do problema viva e a próxima sessão a recriaria com um `*`.

E um erro de FATO na própria TSV (armadilha 4 da spec v1, confirmada na prática): a linha de
`FEAT_LSE128` diz **`ARMv8.9-A`**, e a versão real é **Armv9.4-A**.

### O que a medição também mostrou, e que SIMPLIFICA a task

**Migrar as 111 linhas POR NOME é seguro** — medido: nenhuma delas tem uma irmã de mesmo nome com
célula `✅` que seria apagada, e nenhuma usa a coluna `ocorrencia`. A armadilha central da B19.5.2
(marcar por nome apagaria 84 células `✅`) **não se repete aqui**. O mapa por ocorrência
(`AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE`) continua existindo para as linhas FP16 da B19.5.2, mas
esta task **não precisa acrescentar nada nele** — só entradas no mapa por nome, que já existe.

Ou seja: a task ficou **menor no mecanismo** (zero código novo de medição) e **maior na curadoria**
(9 constantes em vez de 2, e 2 linhas `*` a reescopar).

## Medição (2026-09-04, revisão do QEMU `2931a675e9d3…`)

| | |
|---|---:|
| Linhas da TSV com arquitetura exatamente `A64` | **111** |
| Linhas da TABELA A64 que elas escondem | **134** |
| Linhas TSV órfãs (nenhum mnemônico casa) | **0** |
| Linhas TSV `A64` que exigiriam `ocorrencia` p/ não apagar um `✅` | **0** |
| Linhas da TABELA A64 escondidas por linhas TSV com arquitetura `*` | **9** |
| Linhas A64 já corretamente curadas pelo mapa de versão (B19.5.2 + B11.5) | 142 |

### Por feature citada (linhas TSV → linhas da tabela)

| Feature citada | TSV | Tabela | Constante em `Aarch64Feature` |
|---|---:|---:|---|
| `FEAT_MTE2` | 16 | 26 | ✅ `MEMORY_TAGGING` |
| `FEAT_FPRCVT` | 12 | 12 | ❌ **não existe** |
| `FEAT_PAuth` | 10 | 10 | ✅ `POINTER_AUTHENTICATION` |
| `FEAT_MOPS` | 9 | 9 | ✅ `MEMORY_COPY_SET` |
| `FEAT_FRINTTS` | 8 | 8 | ✅ `DIRECTED_ROUNDING_TO_INTEGRAL` |
| `FEAT_LRCPC2` | 2 | 7 | ❌ **não existe** (`LRCPC` existe, `LRCPC2` não) |
| `FEAT_FP16` | 6 | 6 | ✅ `FP16` |
| `FEAT_FCMA` | 4 | 6 | ✅ `COMPLEX_NUMBER_ARITHMETIC` |
| `FEAT_I8MM` | 6 | 6 | ✅ `INT8_MATRIX_MULTIPLY` |
| `FEAT_CSSC` | 5 | 5 | ✅ `COMMON_SHORT_SEQUENCE_COMPRESSION` |
| `FEAT_CMPBR` | 2 | 5 | ✅ `COMPARE_AND_BRANCH` |
| `FEAT_BF16` | 5 | 5 | ✅ `BFLOAT16` |
| `FEAT_SM3` | 5 | 5 | ✅ `SM3` |
| `FEAT_FAMINMAX` | 2 | 4 | ✅ `FP_ABSOLUTE_MAX_MIN` |
| `FEAT_DotProd` | 4 | 4 | ✅ `DOT_PRODUCT` |
| `FEAT_LSE128` | 3 | 3 | ❌ **não existe** |
| `FEAT_FP8` (`FSCALE`) | 1 | 2 | ❌ **não existe** |
| `FEAT_F8DP2` → real `FEAT_FP8DOT2` | 2 | 2 | ❌ **não existe** |
| `FEAT_F8DP4` → real `FEAT_FP8DOT4` | 2 | 2 | ❌ **não existe** |
| `FEAT_SME` · `FEAT_JSCVT` · `FEAT_SHA512` · `FEAT_SM4` · `FEAT_GCS` | 1 cada | 1 cada | ✅ |
| `FEAT_F8MM8` → real `FEAT_F8F16MM` | 1 | 1 | ❌ **não existe** |
| `FEAT_F8MM4` → real `FEAT_F8F32MM` | 1 | 1 | ❌ **não existe** |
| **TOTAL** | **111** | **134** | 104 com constante · **30 sem** |

**Nomes inventados**: `FEAT_F8DP2`/`FEAT_F8DP4`/`FEAT_F8MM8`/`FEAT_F8MM4` **não existem no ARM** —
foram escritos por uma sessão anterior a partir do mnemônico. Os nomes reais (confirmados no
`docs/system/arm/emulation.rst` do QEMU na revisão fixada) são `FEAT_FP8DOT2`, `FEAT_FP8DOT4`,
`FEAT_F8F16MM` ("matrix multiply-accumulate to half-precision" ⇒ `FMMLA_hb`) e `FEAT_F8F32MM`
("…to single-precision" ⇒ `FMMLA_sb`).

### As 9 linhas escondidas pelo mecanismo `*` (achado novo)

| Linha TSV | Padrão | Linhas da tabela A64 | Hoje | Correto |
|---|---|---|---|---|
| `:46` | `CRC32*` | `CRC32`×4 + `CRC32C`×4 | `·` em 16/16 | `·` só em `ARMv8.0-A`; `❌` de `ARMv8.1-A` em diante |
| `:62` | `SEVL` | `SEVL`×1 | `·` em 16/16 | **`✅` nas 16** (já implementado) |

As duas linhas são **legítimas para as 7 colunas de 32 bits** (`CRC32` e `SEVL` realmente não existem
em ARMv4T..ARMv7-A) — o defeito é só o `*` alcançar as colunas A64. A correção é trocar `*` pela
lista explícita das 7 colunas de 32 bits, **sem mexer no que elas fazem hoje do lado de 32 bits**.

## As versões reais (conferidas na fonte, 2026-09-04)

Fontes: páginas "The Armv9.x architecture extension" de `developer.arm.com/documentation/109697`
(a página em que a ARM documenta a feature = a versão em que ela é introduzida — mesma convenção que
o projeto já usa para `FEAT_FAMINMAX`⇒`ARMV9_4_A`), a tabela FEAT→versão de `arm-cpusysregs`, e o
`docs/system/arm/emulation.rst` do QEMU **na revisão fixada** (para o nome real de cada feature).

| Constante nova | `FEAT_*` real | Versão | Preset onde declarar | Nota da fonte |
|---|---|---|---|---|
| `CRC32` | `FEAT_CRC32` | **Armv8.1-A** | `ARMV8_1_A` | opcional em Armv8.0, obrigatória em Armv8.1 — mesmo padrão de `FEAT_LSE`, já declarada em `ARMV8_1_A` |
| `LRCPC2` | `FEAT_LRCPC2` | **Armv8.4-A** | `ARMV8_4_A` | opcional Armv8.2, obrigatória Armv8.4 (a TSV já dizia v8.4 ✔) |
| `LSE128` | `FEAT_LSE128` | **Armv9.4-A** | `ARMV9_4_A` | página da extensão Armv9.4, "OPTIONAL from Armv9.3" — **a TSV dizia `ARMv8.9-A`, ERRADO** |
| `FP8` | `FEAT_FP8` | **Armv9.5-A** | `ARMV9_5_A` | página da extensão Armv9.5, "OPTIONAL from Armv9.2" |
| `FP8_DOT_PRODUCT_2WAY` | `FEAT_FP8DOT2` | **Armv9.5-A** | `ARMV9_5_A` | página da extensão Armv9.5 |
| `FP8_DOT_PRODUCT_4WAY` | `FEAT_FP8DOT4` | **Armv9.5-A** | `ARMV9_5_A` | página da extensão Armv9.5 |
| `FP_INTEGER_CONVERT_SCALAR` | `FEAT_FPRCVT` | **Armv9.6-A** | *(nenhum — ver abaixo)* | página da extensão Armv9.6, "OPTIONAL from Armv9.5" |
| `FP8_MATRIX_MULTIPLY_FP16` | `FEAT_F8F16MM` | **Armv9.6-A** | *(nenhum)* | Armv9.6-A |
| `FP8_MATRIX_MULTIPLY_FP32` | `FEAT_F8F32MM` | **Armv9.6-A** | *(nenhum)* | Armv9.6-A |

### Três features ficam declaradas por NENHUMA arquitetura — de propósito

A tabela vai só até `ARMv9.5-A`. `FEAT_FPRCVT`/`FEAT_F8F16MM`/`FEAT_F8F32MM` são **Armv9.6-A**, e
**criar uma coluna `ARMv9.6-A` é outra task** (muda o denominador global e exige auditar TODAS as
features contra a v9.6). Declarar a constante sem nenhum preset a oferecer produz
`architecture.has(f) == false` nas 16 colunas ⇒ `·` em 16/16 — **o mesmo resultado visual de hoje,
pelo mecanismo certo**: no dia em que existir uma coluna `ARMv9.6-A`, as 14 linhas viram `❌`
sozinhas, sem ninguém precisar lembrar de editar a TSV.

Isso é exatamente o estado `NOT_IN_ANY_PRESET` que o `tasks/README.md` já descreve e abençoa
("diagnóstico de lacuna de infraestrutura, não exclusão"), agora aplicado a features individuais em
vez de a grupos inteiros. **Registrar no `## Resultado`** que essas 3 (14 linhas) ficam sem degrau e
pertencem à próxima rodada de spec do B19.

## Objetivo

Toda instrução A64 curada por versão passa a medir **`·` apenas nas colunas anteriores à feature que
a introduz**, e **`❌` (ou `✅`) a partir dela** — trabalho pendente no degrau cronológico correto,
como a regra máxima exige. A curadoria de versão A64 deixa de existir em `docs/isa-nao-aplicavel.tsv`
e passa a viver inteiramente no mapa de features.

**Zero decode. Zero mudança de comportamento do decoder.** A única mudança em `core/src/main` são as
**9 constantes novas** de `Aarch64Feature` e as 6 declarações em `Aarch64Architecture` (aditivo, G3
preservado — nenhum decoder consulta essas features).

## ⚠️ Este é um degrau que FAZ A COBERTURA CAIR (e, em um ponto, subir)

O número de v8.1-A em diante vai baixar — são 134 linhas × as colunas onde cada feature existe, mais
as 8 de `CRC32`/`CRC32C`. **Isso é o resultado certo**, com dois precedentes explícitos: B9.11
("Medição honesta: v6-M 52%→47%") e a própria B19.5.2 (v8.2+ 88%→87%).

Na direção oposta, `SEVL` ganha **16 células `✅`** que a TSV escondia. As duas correções são a mesma
coisa: a tabela passa a dizer a verdade.

**Não maquiar a queda, e não fatiar a task para "não estragar o número".**

## Inclui

1. **9 constantes novas** em `arch64/Aarch64Feature`, com Javadoc `///` PT (G7) citando o `FEAT_*`
   real, a versão e a fonte. Nomes e versões na tabela acima.
2. **6 declarações** em `arch64/Aarch64Architecture` (`ARMV8_1_A`, `ARMV8_4_A`, `ARMV9_4_A`,
   `ARMV9_5_A`×3). As 3 features Armv9.6-A **não** são declaradas em preset nenhum — Javadoc explica.
3. **Migração das 111 linhas `A64`** da TSV para `AARCH64_VERSION_REQUIREMENTS` (**por nome** — a
   medição provou que é seguro). **Remover as 111 linhas da TSV**, reescrevendo (não apagando) os
   blocos de comentário ao redor para apontar para o mapa de features.
4. **Reescopar as 2 linhas `*`**: `CRC32*` e `SEVL` passam de `*` para
   `v4T,v5TE,v6K,MPCore,v7-A,v6-M,v7-M` (as 7 colunas de 32 bits, comportamento idêntico ao de hoje
   naquele lado). `CRC32`/`CRC32C` ganham requisito `Aarch64Feature.CRC32`; `SEVL` **não ganha
   requisito nenhum** — é ISA base A64 e volta a medir o `✅` que já merecia.
5. **Fechar a porta**: com zero linhas `A64` restantes, remover a cláusula de casamento amplo de
   `Exclusion#matches` e o método `isAarch64VersionColumn`. Uma linha `A64` passa a casar **apenas** a
   coluna monolítica literal `A64` (a de `sve.decode`/`sme.decode`) — que é o que ela sempre deveria
   ter significado.
6. **Teste de guarda** que falha se QUALQUER linha da TSV — por `A64`, por `*` ou por nome de coluna —
   excluir um mnemônico de `a64.decode` numa das 16 colunas de versão. É o teste que impede o
   problema de voltar por qualquer um dos dois mecanismos (mesmo papel do
   `JitCoverageReportGuardTest` da C12.1).
7. **Javadoc `///` PT (G7)** de `Exclusion#matches` e do cabeçalho de `docs/isa-nao-aplicavel.tsv`
   explicando a regra nova: *curadoria de versão A64 vive no mapa de features, não na TSV*.
8. `docs/COBERTURA-ISA.md` regenerada, com o delta por coluna registrado.

## Não inclui (com destino explícito)

- **Implementar qualquer instrução** que passe a aparecer como `❌`. Cada bloco revelado vira (ou já
  pertence a) um degrau do épico **B19** — `FEAT_PAuth`, `FEAT_MTE2`, `FEAT_MOPS`, `FEAT_CSSC`,
  `FEAT_FRINTTS`, `FEAT_CRC32` etc. não têm degrau hoje: **registrar no `## Resultado` quais grupos
  ficaram descobertos**, para a próxima rodada de spec.
- **Criar a coluna `ARMv9.6-A`** ⇒ task própria (ver "Três features ficam declaradas por nenhuma
  arquitetura").
- **As 12 linhas da B19.5.2** (FP16/FHM) — já migradas por ela, e para o mapa por OCORRÊNCIA.
- **A curadoria de 32 bits** (`v4T`…`v7-M`) — outro mecanismo, outra história (B9.12/B9.15/B9.17).
  Reescopar `CRC32*`/`SEVL` **preserva** o comportamento de 32 bits, não o revisa.
- **`sve.decode`/`sme.decode`** — grupos `NOT_IN_ANY_PRESET`, coluna monolítica `A64`. Continuam
  intocados (e o item 5 preserva o casamento literal de que dependem).
- **Gatear qualquer decoder** pelas 9 features novas.

## Especificação

### O ponto sutil: coluna ≠ ordem de versão

`ARMv9.x` **não** estende `ARMv8.9`. Medido em `Aarch64Architecture`:

```java
ARMV9_0_A = extending(ARMV8_5_A, "ARMv9.0-A");
ARMV9_1_A = extending(ARMV8_6_A, "ARMv9.1-A");
ARMV9_2_A = extending(ARMV8_7_A, "ARMv9.2-A", SCALABLE_MATRIX_EXTENSION);
```

Logo uma feature de `ARMv8.8-A` (ex.: `FEAT_MOPS`) **não** está em `ARMv9.0-A`/`ARMv9.1-A`. Isso já
aparece na tabela atual (v8.5, v8.6, v9.0 e v9.1 têm o MESMO denominador).

**Consequência prática**: não dá para computar "a partir da coluna N" por índice. A conta certa é a
que `isApplicableToAarch64Version` já faz — `architecture.has(required)`. Quem executar **não deve**
tentar prever o número de células à mão: migrar, regenerar, e ler o delta.

⚠️ Caso concreto desta task: `FEAT_LSE128` é Armv9.4-A, e `ARMV9_4_A` estende `ARMV8_9_A`. As 3
linhas ficam `·` de v8.0 a v9.3 e `❌` só em v9.4/v9.5 — **não** em v8.9, apesar de a TSV afirmar
"ARMv8.9-A".

### Método

1. Extrair as 111 linhas `A64` com sua justificativa (`awk -F'\t' '$2=="A64"'`).
2. Para cada uma, mapear o `FEAT_*` citado para a constante (tabela do Contexto; **reconferir**).
3. Migrar **em lotes por feature** (todas as `FEAT_MTE2` juntas, etc.) — o diff da tabela fica
   legível e um erro fica localizado.
4. Depois de cada lote, regenerar e conferir que **nenhuma célula `✅` mudou** (exceto os 16 `✅`
   NOVOS de `SEVL`, que são o achado).

## Passos

1. Ler "Leitura obrigatória". Confirmar que a **B19.5.2 está ✅**.
2. Guardar `docs/COBERTURA-ISA.md` ANTES.
3. Reproduzir a medição (111/134/0/0/9). **Se algum número não bater, PARE e reporte.**
4. As 9 constantes + as 6 declarações (+ atualizar `Aarch64ArchitectureTest#armv81aAddsRdmLseAndPan`,
   que hoje afirma que ARMv8.1-A tem SÓ `RDM`/`LSE`/`PAN` e passa a incluir `CRC32`).
5. Migração por lotes de feature das 111 linhas; remover da TSV.
6. Reescopar `CRC32*` e `SEVL`.
7. Fechar a porta (item 5 de "Inclui") + teste de guarda.
8. Javadoc (G7). Testes (ver Aceite).
9. `mvn -o test` (JBR 25) + `./gerar-cobertura-isa.sh`; registrar o delta REAL.
10. `INDICE.md` da trilha E, `## Resultado`, commit `E12: …`, `git push`.

## Aceite

- **As 111 linhas migradas** e removidas da TSV; **0 linhas com arquitetura `A64`** restantes.
- **`isAarch64VersionColumn` removido** e `sve.decode`/`sme.decode` intocados (a coluna literal `A64`
  continua casando).
- **Nenhuma instrução A64 mede `·` numa coluna cuja arquitetura TEM a feature curada** — teste que
  varre a tabela e falha se existir.
- **Nenhuma célula `✅` virou `·`/`❌`** — teste de não-vazamento. As transições permitidas são
  `· → ❌` (trabalho revelado), `· → ✅` (**só `SEVL`, 16 células**) e nada mais.
- **Teste de guarda**: falha se qualquer linha de TSV (por `A64`, por `*`, ou por coluna nomeada)
  excluir um mnemônico de `a64.decode` numa das 16 colunas de versão.
- `docs/COBERTURA-ISA.md` regenerada, **com o delta por coluna no `## Resultado`** — incluindo a
  queda, sem maquiagem, e a explicação de que é revelação de trabalho, não regressão.
- **Lista dos grupos revelados sem degrau no B19**, mais as **3 features Armv9.6-A sem preset**.
- **`core/src/main` muda SÓ** em `Aarch64Feature` (9 constantes) e `Aarch64Architecture` (6
  declarações) — `git diff --stat` prova; ambos aditivos, G3 preservado.
- `mvn -o test` verde + G5 nos consumidores (diff vazio esperado).

## Validação

`mvn -o test` no arm-jitter (JBR 25), `mvn -o install`, `mvn -o test` nos consumidores,
`./gerar-cobertura-isa.sh`.

## Armadilhas

1. **A cobertura vai CAIR e isso é o certo.** Precedentes: B9.11, B19.5.2. Não fatiar a task nem
   parar no meio para "preservar o número".
2. **`ARMv9.x` não estende `ARMv8.9`** — não computar colunas por índice. Ver o caso `FEAT_LSE128`.
3. **A justificativa da TSV pode estar errada** — e nesta task ESTÁ, duas vezes: `FEAT_LSE128` diz
   `ARMv8.9-A` (real: Armv9.4-A) e quatro features FP8 têm nomes inventados. Ao migrar, a versão vira
   comportamento: **conferir cada feature na fonte**, não no texto da TSV.
4. **Não apagar linha de TSV sem pôr o requisito equivalente** — a instrução voltaria a contar `❌`
   até em `ARMv8.0-A`, tão errado quanto o problema atual, só do outro lado.
5. **`sve.decode`/`sme.decode` usam a coluna monolítica `A64`** de propósito. Ao remover o casamento
   amplo, garantir que `architectures.contains(column)` com `column == "A64"` continua valendo.
6. **`CRC32*` e `SEVL` não podem ser simplesmente removidas** — elas são corretas para as 7 colunas de
   32 bits. Trocar `*` pela lista explícita, e conferir que o lado de 32 bits fica byte a byte igual.
7. **`SEVL` vira `✅`, não `❌`.** Se ficar `❌`, o reescopo atingiu o decoder errado — investigar, não
   "curar" de volta.
8. **Migrar por nome é seguro AQUI** (medido: 0 conflitos), mas isso é um fato desta lista, não uma
   regra geral — a B19.5.2 precisou de ocorrência. Se a lista mudar, remedir.

## Não fazer

- Não implementar instrução nenhuma.
- Não maquiar a queda de cobertura.
- Não migrar as 12 linhas da B19.5.2 de novo.
- Não mexer na curadoria de 32 bits (além de reescopar `CRC32*`/`SEVL` preservando o comportamento).
- Não criar a coluna `ARMv9.6-A`.
- Não remover o casamento literal da coluna `A64` sem conferir `sve`/`sme`.

## Leitura obrigatória

A **B19.5.2** INTEIRA (mecanismo e precedente de medição), a **B9.11** (`## Resultado`: precedente de
medição honesta que BAIXA a porcentagem), o cabeçalho de `docs/isa-nao-aplicavel.tsv`, e a regra
máxima do `tasks/README.md`. No código: `IsaCoverageReport` (em especial `isAarch64VersionColumn`,
`Exclusion#matches`, `isExcluded`, `isApplicableToAarch64Version` e os dois mapas de requisito),
`arch64/Aarch64Feature`, `arch64/Aarch64Architecture`, `Aarch64ArchitectureTest`.

## Resultado

Executada 2026-09-04 (JBR 25), depois de a spec ser **re-medida e corrigida** (ver
`## Correção da spec` — uma sessão anterior tentou seguir a v1, bateu de frente com os números e
parou para reportar, que é o comportamento certo).

### Medição reproduzida

| Checkpoint | Medido |
|---|---:|
| Linhas TSV com arquitetura exatamente `A64` | **111** |
| Linhas da TABELA A64 que elas escondiam | **134** |
| Linhas TSV órfãs (nenhum mnemônico casa) | **0** |
| Linhas TSV `A64` que exigiriam `ocorrencia` para não apagar um `✅` | **0** |
| Linhas da TABELA A64 escondidas por linhas TSV com arquitetura `*` | **9** |
| Features citadas SEM constante em `Aarch64Feature` | **8** (30 linhas) |

### As 9 constantes novas (`core/src/main`, aditivo — G3)

`CRC32` (ARMv8.1-A) · `LRCPC2` (ARMv8.4-A) · `LSE128` (Armv9.4-A) · `FP8`,
`FP8_DOT_PRODUCT_2WAY`, `FP8_DOT_PRODUCT_4WAY` (Armv9.5-A) · `FP_INTEGER_CONVERT_SCALAR`,
`FP8_MATRIX_MULTIPLY_FP16`, `FP8_MATRIX_MULTIPLY_FP32` (Armv9.6-A, **sem preset** — a tabela vai só
até ARMv9.5-A; medem `·` nas 16 colunas pelo mecanismo certo e viram `❌` sozinhas quando existir uma
coluna ARMv9.6-A).

**Dois erros de FATO na TSV, corrigidos contra a fonte:**

1. `FEAT_LSE128` estava como `ARMv8.9-A`; a versão real é **Armv9.4-A**. Observável, porque
   `ARMV9_4_A` estende `ARMV8_9_A` — as 3 linhas ficam `·` em v8.9 e `❌` só em v9.4/v9.5.
2. `FEAT_F8DP2`/`F8DP4`/`F8MM8`/`F8MM4` **não existem no ARM** — nomes inventados a partir do
   mnemônico. Reais: `FEAT_FP8DOT2`/`FEAT_FP8DOT4`/`FEAT_F8F16MM`/`FEAT_F8F32MM`.

### Migração

106 entradas novas em `AARCH64_VERSION_REQUIREMENTS` (por NOME — medido seguro), via um helper
`require(feature, names…)` que **recusa** registrar o mesmo nome com features diferentes. As outras 7
(`SHA512SU0`, `SM3SS1`, `SM3TT1A`, `SM3TT1B`, `SM3TT2A`, `SM3TT2B`, `SM4E`) já estavam no mapa: a
linha TSV era pura redundância, bastou removê-la. 106 + 7 = 113 = 111 da TSV + `CRC32`/`CRC32C`.

`docs/isa-nao-aplicavel.tsv`: **111 linhas removidas** (0 linhas `A64` restantes), `CRC32*` e `SEVL`
reescopadas de `*` para as 7 colunas de 32 bits, cabeçalho ganhou a regra nova.

### ⚠️ O achado que mudou o desenho da task: 66 células seriam `✅` FALSO

Ao migrar, 82 células passariam de `·` a `✅`. A sondagem direta (mesmo encoder do relatório) mostrou
que **só 16 eram cobertura real**:

| Mnemônico | O `Aarch64Decoder` devolve | Veredito |
|---|---|---|
| `SEVL` | `SystemInstruction[NOP_HINT]` | ✅ **correto** — hint sem efeito observável neste emulador |
| `LDRA` | `SystemInstruction[NOP_HINT]` | ⚠️ misdecode |
| `SETGP`/`SETGM`/`SETGE`/`CPYP`/`CPYM`/`CPYE` | `FpLoadLiteral64` | ⚠️ misdecode |
| `FAMAX`/`FAMIN`/`FSCALE` (occ=1) | `VectorInsertGeneral`/`VectorInsertElement` | ⚠️ misdecode |

Publicar 66 `✅` falsos seria **pior** que o `·` anterior: afirmaria trabalho concluído. Usei o
símbolo que o próprio relatório já define para isto desde a E5 — `⚠️` ("decodifica como OUTRA coisa …
não é suporte, é o decoder não sabendo recusar") — que conta no denominador e **não** no numerador.
O probe A64 nunca tinha usado `⚠️`; agora consulta `AARCH64_MISDECODED` (10 entradas, chave
`NOME#ocorrência`, cada uma citando a classe errada devolvida).

**Não é exclusão disfarçada**: `everyKnownMisdecodedLineStillProducesAWarningCell` falha quando
alguém consertar o decoder, obrigando a REMOVER a entrada.

Os `CPY*`/`SETG*` são a **mesma classe de bug que a B11.3 corrigiu** para o `LDR (literal)` INTEIRO
(`LITERAL_SUBCLASS_RESERVED_BIT_SHIFT`) — sobrou o caminho de literal de PONTO FLUTUANTE. ⇒ **task
nova** (ver "O que fica aberto").

### Delta na tabela (medido linha a linha)

- **1158 `· → ❌`** — trabalho revelado no degrau cronológico certo
- **66 `· → ⚠️`** — dívida G8, antes invisível
- **16 `· → ✅`** — `SEVL`, cobertura real que a TSV apagava
- **0 células `✅` viraram outra coisa** (teste de não-vazamento)
- **32 bits byte a byte inalterado** (v4T/v5TE/v6K/MPCore/v7-A 100%, v6-M 88%, v7-M 96%) — o
  reescopo de `CRC32*`/`SEVL` preservou aquele lado, como a armadilha 6 exigia

| Coluna | Antes | Depois |
|---|---|---|
| `ARMv8.0-A` | 97% (850/876) | **97%** (851/877) |
| `ARMv8.1-A` | 97% (874/900) | **96%** (875/909) |
| `ARMv8.2-A` | 87% (879/1007) | **85%** (880/1033) |
| `ARMv8.3-A` | 87% (880/1008) | **83%** (881/1051) |
| `ARMv8.4-A` | 87% (885/1013) | **83%** (886/1063) |
| `ARMv8.5-A` · `ARMv9.0-A` | 87% (887/1015) | **80%** (888/1099) |
| `ARMv8.6-A` · `ARMv9.1-A` | 87% (887/1015) | **80%** (888/1110) |
| `ARMv8.7-A` | 87% (889/1017) | **80%** (890/1112) |
| `ARMv8.8-A` · `ARMv9.3-A` | 87% (890/1018) | **79%** (891/1122) |
| `ARMv8.9-A` | 87% (890/1018) | **79%** (891/1127) |
| `ARMv9.2-A` | 87% (889/1017) | **79%** (890/1113) |
| `ARMv9.4-A` | 87% (890/1018) | **78%** (891/1135) |
| `ARMv9.5-A` | 87% (890/1018) | **77%** (891/1146) |
| **Global** | **89%** (16303/18169) | **84%** (16319/19409) |

O denominador global cresceu **1240 células** — é exatamente o tamanho do trabalho que a TSV
escondia. **A queda de 89% para 84% não é regressão: é a tabela parando de mentir.** Precedentes:
B9.11 (v6-M 52%→47%), B19.5.2 (v8.2+ 88%→87%). Marco de release segue suspenso — nada publicado.

### Fechar a porta

`isAarch64VersionColumn` **removido**, e a cláusula de casamento amplo saiu de `Exclusion#matches`.
Uma linha `A64` agora casa apenas a coluna monolítica LITERAL `A64` — a de `sve.decode`/`sme.decode`,
que continuam intocados (são `NOT_IN_ANY_PRESET` e medem `·` sem depender da TSV).

`IsaCoverageReportA64CurationGuardTest` (8 testes, CI-safe — lê só arquivos versionados):

1. `noTsvLineMayExcludeAnA64MnemonicFromAVersionedColumn` — a porta, cobrindo **os dois mecanismos**
   (`A64` e `*`) de uma vez, porque fechar só o primeiro deixaria a próxima sessão recriar o problema.
2. `noTsvLineUsesTheLegacyA64Architecture` — **confirmado falhando** com uma linha `STG A64` injetada.
3. `noInstructionIsNotApplicableInAColumnWhoseArchitectureHasItsFeature` — o teste que define a task.
4. `noInstructionIsMeasuredInAColumnBeforeItsFeatureExists` — o inverso (não inflar denominador).
5. `everyKnownMisdecodedLineIsMarkedFallbackNeverSupported` — nenhum `✅` falso.
6. `everyKnownMisdecodedLineStillProducesAWarningCell` — impede a lista virar exclusão permanente.
7. `sevlIsSupportedInAllSixteenA64Columns` · 8. `crc32IsPendingWorkFromArmv81aOnwards`.

Mais 3 testes novos em `Aarch64ArchitectureTest` (LSE128 em v9.4 e não v8.9; família FP8 em v9.5; as
3 features Armv9.6-A declaradas por nenhum preset) e a atualização de `armv81aAddsRdmLsePanAndCrc32`.

### Validação

`mvn -o test` verde — **2971 + 21** testes, 0 falhas. `mvn -o install`. G5: `gbaemu` 240 (17
skipped), `ndsemu` 183, `armbox` 47 — **BUILD SUCCESS** nos três, diff vazio como esperado.
`git diff --stat -- core/src/main` = só `Aarch64Feature` e `Aarch64Architecture`.

### O que fica aberto (para a próxima rodada de spec)

**Grupos revelados sem degrau no B19** — agora visíveis e contáveis: `FEAT_MTE2` (26 linhas),
`FEAT_PAuth` (10), `FEAT_MOPS` (9), `FEAT_FRINTTS` (8), `FEAT_CSSC` (5), `FEAT_CRC32` (8, novo),
`FEAT_I8MM` (6), `FEAT_BF16` (5), `FEAT_FCMA` (6), `FEAT_DotProd` (4), `FEAT_LRCPC2` (7),
`FEAT_LSE128` (3), família FP8 (7), `FEAT_CMPBR` (5), `FEAT_SME`/`FEAT_GCS`/`FEAT_JSCVT` (1 cada).

**Duas tasks novas que esta sessão mediu e NÃO executou (fora do escopo — zero decode):**

1. **Misdecode de `CPY*`/`SETG*` como `FpLoadLiteral64`** — mesma classe que a B11.3 corrigiu para o
   literal INTEIRO; falta o caminho de ponto flutuante. Mais `LDRA` caindo no catch-all de
   hint-space e `FAMAX`/`FAMIN`/`FSCALE` colidindo com o espaço de `INS`/`MOV` vetorial. 10 linhas,
   66 células `⚠️`, todas com repro determinístico em `AARCH64_MISDECODED`. É dívida do **G8**.
2. **Coluna `ARMv9.6-A`** — 3 features já existem como constante (`FEAT_FPRCVT`, `FEAT_F8F16MM`,
   `FEAT_F8F32MM`, 14 linhas) e nenhum preset as declara. Criar a coluna exige auditar TODAS as
   features contra a v9.6 e muda o denominador global.
