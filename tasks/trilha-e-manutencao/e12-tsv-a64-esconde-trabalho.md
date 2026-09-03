# E12 — A curadoria `A64` da TSV esconde trabalho pendente nas versões em que a feature EXISTE

**Trilha:** E · **Repo:** arm-jitter (só ferramenta de teste + `docs/`) · **Depende de:** B19.5.2
**Status:** ⬜

## Contexto

`docs/COBERTURA-ISA.md` tem **16 colunas de versão A64** desde a B11.5 (`ARMv8.0-A` … `ARMv9.5-A`).
Mas a curadoria de `docs/isa-nao-aplicavel.tsv` é anterior a isso: ela nasceu quando o A64 era **uma
coluna monolítica chamada `A64`**, e a compatibilidade com aquelas linhas foi mantida assim
(`IsaCoverageReport:242-249`):

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

### Medição (2026-09-03)

| | |
|---|---:|
| Linhas da TSV com arquitetura `A64` | **123** |
| …que citam uma versão **≥ ARMv8.1-A** na própria justificativa | **102** |
| …logo escondidas em pelo menos uma coluna onde a feature existe | **102** |

Features mais atingidas (contagem de linhas de TSV):

| Feature citada | Linhas | Constante em `Aarch64Feature`? | Introduzida em |
|---|---:|---|---|
| `FEAT_MTE2` | 16 | ✅ `MEMORY_TAGGING` | `ARMV8_5_A` |
| `FEAT_PAuth` | 10 | ✅ `POINTER_AUTHENTICATION` | `ARMV8_3_A` |
| `FEAT_FP16` | 10 | ✅ `FP16` | `ARMV8_2_A` |
| `FEAT_MOPS` | 9 | ✅ `MEMORY_COPY_SET` | `ARMV8_8_A` |
| `FEAT_FRINTTS` | 8 | ✅ `DIRECTED_ROUNDING_TO_INTEGRAL` | `ARMV8_5_A` |
| `FEAT_FHM` | 8 | ✅ `FP16_FUSED_MULTIPLY_ADD_LONG` | `ARMV8_2_A` |
| `FEAT_I8MM` | 6 | ✅ `INT8_MATRIX_MULTIPLY` | `ARMV8_6_A` |
| `FEAT_SM3` | 5 | ✅ `SM3` | `ARMV8_2_A` |
| `FEAT_CSSC` | 5 | ✅ `COMMON_SHORT_SEQUENCE_COMPRESSION` | `ARMV8_9_A` |
| `FEAT_BF16` | 5 | ✅ `BFLOAT16` | `ARMV8_6_A` |
| `FEAT_FCMA` | 4 | ✅ `COMPLEX_NUMBER_ARITHMETIC` | `ARMV8_3_A` |
| `FEAT_DotProd` | 4 | ✅ `DOT_PRODUCT` | `ARMV8_2_A` |
| **`FEAT_LSE128`** | 3 | ❌ **não existe** | — |
| **`FEAT_LRCPC2`** | 2 | ❌ **não existe** (`LRCPC` existe, `LRCPC2` não) | — |
| `FEAT_FAMINMAX` | 2 | ✅ `FP_ABSOLUTE_MAX_MIN` | `ARMV9_4_A` |
| `FEAT_SME` · `FEAT_SM4` · `FEAT_SHA512` · `FEAT_JSCVT` · `FEAT_GCS` | 1 cada | ✅ | v9.2 / v8.2 / v8.2 / v8.3 / v9.4 |

**A boa notícia**: **quase todas as features já têm constante** — só `FEAT_LSE128` (3 linhas) e
`FEAT_LRCPC2` (2 linhas) faltam. O mecanismo para expressá-las corretamente é o que a **B19.5.2**
constrói (requisito de versão por nome e por ocorrência). Esta task é a aplicação em massa daquele
mecanismo ao resto da TSV.

**Precedente direto**: a B19.5.2 já faz isso para **12** linhas (8 `FEAT_FHM` + 4 `FEAT_FP16`
across-lanes), e mede o efeito — elas passam de `·` nas 16 colunas para `·` em v8.0/v8.1 e **`❌` de
v8.2 em diante**. Esta task herda as demais.

**Leitura obrigatória**: a **B19.5.2** INTEIRA (incluindo o `## Resultado`, se já executada — é o
mecanismo e o precedente de medição), a **B9.11** (`## Resultado`: o precedente de medição honesta
que BAIXA a porcentagem), o cabeçalho de `docs/isa-nao-aplicavel.tsv`, e a regra máxima do
`tasks/README.md`. No código: `IsaCoverageReport` (em especial `isAarch64VersionColumn`,
`isExcluded`, `isApplicableToAarch64Version` e o mapa por ocorrência que a B19.5.2 acrescentou),
`arch64/Aarch64Feature`, `arch64/Aarch64Architecture`.

## Objetivo

Toda instrução A64 curada por versão passa a medir **`·` apenas nas colunas anteriores à feature que
a introduz**, e **`❌` a partir dela** — trabalho pendente visível no degrau cronológico correto, como
a regra máxima exige. **Zero decode, zero mudança em `core/src/main`.**

## ⚠️ Este é um degrau que FAZ A COBERTURA CAIR

O número de v8.2-A em diante vai baixar, possivelmente bastante (são até 102 linhas × as colunas onde
cada feature existe). **Isso é o resultado certo** e tem dois precedentes explícitos no projeto:
B9.11 ("Medição honesta: v6-M 52%→47%") e a própria B19.5.2 (v8.2+ 88%→87% por 12 linhas).

**Não maquiar a queda, e não fatiar a task para "não estragar o número".** O valor da tabela é dizer
a verdade; um 88% que esconde 102 linhas de trabalho é pior que um número menor honesto.

## Inclui

1. **Varredura das 123 linhas `A64`** da TSV, classificando cada uma em:
   - **(a) tem feature com constante** ⇒ migrar da TSV para `AARCH64_VERSION_REQUIREMENTS` (por nome)
     ou para o mapa por ocorrência (quando o mnemônico tiver linhas de versões diferentes — o caso da
     B19.5.2). **Remover a linha da TSV.**
   - **(b) tem feature SEM constante** (`FEAT_LSE128`, `FEAT_LRCPC2`) ⇒ ver decisão abaixo.
   - **(c) não é caso de versão** (se houver) ⇒ permanece na TSV, com a justificativa revista.
2. **Decisão sobre (b)**: criar as duas constantes que faltam (`FEAT_LSE128` ⇒ ARMv9.4-A;
   `FEAT_LRCPC2` ⇒ ARMv8.4-A — **conferir as versões na fonte, não neste texto**) e declará-las nas
   arquiteturas corretas, OU deixar essas 5 linhas na TSV com um comentário apontando esta task.
   **Recomendação: criar as constantes** — é aditivo, o projeto já tem 30 delas, e deixar 5 linhas no
   mecanismo errado reintroduz o problema em escala menor. Registrar a decisão no `## Resultado`.
3. **Fechar a porta**: depois da migração, `isAarch64VersionColumn` só precisa existir se ainda
   houver linha `A64` legítima. Se não sobrar nenhuma do tipo (a),
   **restringir ou remover o casamento amplo** e deixar um teste que falhe se uma linha `A64` nova
   aparecer com justificativa de versão. É o que impede o problema de voltar — mesmo papel do
   `JitCoverageReportGuardTest` da C12.1.
4. **Javadoc `///` PT (G7)** de `isAarch64VersionColumn` e do cabeçalho de
   `docs/isa-nao-aplicavel.tsv` explicando a regra nova: *curadoria de versão A64 vive no mapa de
   features, não na TSV*.
5. `docs/COBERTURA-ISA.md` regenerada, com o delta por coluna registrado.

## Não inclui (com destino explícito)

- **Implementar qualquer instrução** que passe a aparecer como `❌`. Cada bloco revelado vira (ou já
  pertence a) um degrau do épico **B19** — `FEAT_PAuth`, `FEAT_MTE2`, `FEAT_MOPS`, `FEAT_CSSC` etc.
  não têm degrau hoje: **registrar no `## Resultado` quais grupos ficaram descobertos**, para a
  próxima rodada de spec.
- **As 12 linhas da B19.5.2** (FP16/FHM) — já migradas por ela. Se a B19.5.2 ainda não tiver
  executado, **executá-la primeiro** (esta task depende dela pelo mecanismo).
- **A curadoria de 32 bits** (`v4T`…`v7-M`) — outro mecanismo, outra história (B9.12/B9.15/B9.17).
- **`sve.decode`/`sme.decode`** — grupos `NOT_IN_ANY_PRESET`, coluna monolítica `A64`, nada
  decodifica. Fora do escopo.

## Especificação

### O ponto sutil: coluna ≠ ordem de versão

`ARMv9.x` **não** estende `ARMv8.9`. Medido em `Aarch64Architecture`:

```java
ARMV9_0_A = extending(ARMV8_5_A, "ARMv9.0-A");
ARMV9_1_A = extending(ARMV8_6_A, "ARMv9.1-A");
ARMV9_2_A = extending(ARMV8_7_A, "ARMv9.2-A", SCALABLE_MATRIX_EXTENSION);
```

Logo uma feature de `ARMv8.8-A` (ex.: `FEAT_MOPS`) **não** está em `ARMv9.0-A`/`ARMv9.1-A`. Isso já
aparece na tabela atual (v8.5, v8.6, v9.0 e v9.1 têm o MESMO denominador, 1003).

**Consequência prática**: não dá para computar "a partir da coluna N" por índice. A conta certa é a
que o próprio `isApplicableToAarch64Version` já faz — `architecture.has(required)`. Quem executar
**não deve** tentar prever o número de células à mão: migrar, regenerar, e ler o delta.

### Método sugerido

1. Extrair as 123 linhas `A64` da TSV com sua justificativa.
2. Para cada uma, identificar o `FEAT_*` citado e mapear para a constante de `Aarch64Feature`
   (a tabela do Contexto dá o mapeamento medido; **reconferir**, não copiar cegamente).
3. Migrar em lotes por feature (todas as `FEAT_MTE2` juntas, etc.) — assim o diff da tabela é legível
   e um erro fica localizado.
4. Depois de cada lote, regenerar e conferir que **nenhuma célula `✅` mudou** (o teste de
   não-vazamento da B19.5.2 serve, e deve ser reusado).

### Um mnemônico pode precisar de ocorrência

Vale a mesma armadilha da B19.5.2: se um mnemônico tem uma linha da feature e outra de ISA base, o
requisito **tem** que ser por ocorrência, senão a linha base vira `·` e a cobertura real é apagada. A
B19.5.2 mediu isso para FP16 (84 das 96 tinham irmã `✅`). **Repetir a verificação por lote.**

## Passos

1. Ler "Leitura obrigatória". Confirmar que a **B19.5.2 está ✅**; se não, parar e executá-la antes.
2. Guardar `docs/COBERTURA-ISA.md` ANTES.
3. Varredura + classificação das 123 linhas (a)/(b)/(c). Registrar a tabela no `## Resultado`.
4. Decisão sobre `FEAT_LSE128`/`FEAT_LRCPC2`.
5. Migração por lotes de feature, regenerando e conferindo não-vazamento a cada lote.
6. Fechar a porta (item 3 de "Inclui") + teste de guarda.
7. Javadoc (G7).
8. `mvn -o test` (JBR 25) + G5 nos 5 consumidores.
9. `INDICE.md` da trilha E, `## Resultado`, commit `E12: …`, `git push`.

## Aceite

- **As 123 linhas classificadas**, com a tabela (a)/(b)/(c) no `## Resultado`, e as do tipo (a)
  removidas da TSV e presentes no mecanismo de versão.
- **Nenhuma célula `✅` virou `·` ou `❌`** — teste de não-vazamento (reusar o da B19.5.2).
- **Nenhuma instrução mede `·` numa coluna cuja arquitetura tem a feature** — este é o teste que
  define a task: varrer a tabela e falhar se existir uma linha `·` numa coluna em que
  `architecture.has(featureCurada)` é verdadeiro.
- **Teste de guarda** que falha quando uma linha de TSV nova usa `A64` com justificativa de versão
  (a porta fechada do item 3).
- `docs/COBERTURA-ISA.md` regenerada, **com o delta por coluna registrado no `## Resultado`** —
  incluindo a queda, sem maquiagem, e a explicação de que ela é revelação de trabalho, não regressão.
- **Lista dos grupos revelados que NÃO têm degrau no épico B19** (`FEAT_PAuth`, `FEAT_MTE2`,
  `FEAT_MOPS`, `FEAT_CSSC`, `FEAT_FRINTTS`, …), para a próxima rodada de spec.
- **Zero mudança em `core/src/main`**, exceto as 2 constantes novas de `Aarch64Feature` se a decisão
  do item 2 for criá-las (aditivo, G3 preservado).
- `mvn -o test` verde + G5 nos 5 consumidores (diff vazio esperado).

## Validação

`mvn -o test` no arm-jitter (JBR 25), `mvn -o install`, `mvn -o test` nos 5 consumidores,
`./gerar-cobertura-isa.sh`.

## Armadilhas

1. **A cobertura vai CAIR e isso é o certo.** Precedentes: B9.11, B19.5.2. Não fatiar a task nem
   parar no meio para "preservar o número".
2. **`ARMv9.x` não estende `ARMv8.9`** — não computar colunas por índice.
3. **Migrar por nome quando o mnemônico tem linha base apaga cobertura real** — usar ocorrência,
   como a B19.5.2. Verificar por lote.
4. **A justificativa da TSV pode estar errada.** Ela é texto livre escrito por sessões anteriores;
   ao migrar, a versão vira comportamento de verdade. **Conferir a versão de cada feature na fonte**
   (ARM ARM / lista de `FEAT_*`), não só no texto da TSV.
5. **Não apagar linha de TSV sem pôr o requisito equivalente** — a instrução voltaria a contar `❌`
   até em `ARMv8.0-A`, o que é tão errado quanto o problema atual, só do outro lado.
6. **`sve.decode`/`sme.decode` usam a coluna monolítica `A64`** de propósito. Se a porta do item 3
   for fechada de forma ampla demais, esses dois grupos quebram. Conferir.

## Não fazer

- Não implementar instrução nenhuma.
- Não maquiar a queda de cobertura.
- Não migrar as 12 linhas da B19.5.2 de novo.
- Não mexer na curadoria de 32 bits.
- Não remover `isAarch64VersionColumn` sem conferir `sve`/`sme`.
