# B17 — SVE / SVE2 (Scalable Vector Extension): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

O maior grupo "não se aplica a nenhum preset atual" da tabela inteira. Ver
`b13-plano-neon-a32.md` para a discussão de por que esse rótulo nunca foi decisão de escopo, e
`b11-plano-aarch64-feature-gating.md` para a correção de rumo do usuário que este épico executa:

> Sessões anteriores trataram menções a ARMv9 como "sem consumidor real pedindo isso hoje" — isso
> é exatamente o padrão que a regra máxima do `tasks/README.md` já proíbe.

SVE é o caso mais puro disso: é a extensão vetorial do ARMv8.2-A em diante e a **base obrigatória
do ARMv9-A** — sem ela, "ARMv9.0-A" no `Aarch64Architecture` é um preset que mente sobre o que a
arquitetura real oferece.

## O número (medido, `target/isa-decode/sve.decode`)

**929 encodings.** As 68 seções `###` do inventário agrupam-se assim:

| Bloco | Encodings (soma das seções) |
|---|---:|
| Inteiro predicado (aritmética binária, redução, shift por imediato, unária) | 87 |
| Inteiro não-predicado (aritmética, lógica, shift, multiply-add, misc, índice) | 45 |
| Imediato (bitwise, wide immediate predicado e não-predicado) | 108 |
| Comparação (vetores, imediato com/sem sinal, escalares) | 38 |
| Predicados (lógica, misc, partition break, contagem, element count) | 40 |
| Permutação (extract, unpredicated, predicates, interleaving, predicated, select) | 78 |
| Ponto flutuante (aritmética predicada/não-predicada, multiply-add, indexado, comparação, redução) | 186 |
| Memória (load contíguo, gather 32/64 bits, store) | 81 |
| Endereçamento / stack / contagem (compute vector address, stack allocation) | 10 |
| SVE2 inteiro (multiply, predicado, unário, saturating shift, halving, pairwise, saturating add/sub) | 143 |
| SVE2 outros (character match, histogram/LUT, crypto, matmul FP, gather/store, convert, dot-product, FP multiply-add long) | 92 |
| SVE2.1 / extras (broadcast predicate element, clamp, multi-vec contiguous load) | 23 |

## O que falta de infraestrutura (investigado, não suposto)

1. **`Aarch64Feature` não tem NENHUMA constante de SVE.** O enum tem 28 constantes
   (`RDM`…`GUARDED_CONTROL_STACK`), incluindo `SCALABLE_MATRIX_EXTENSION` — mas essa é
   placeholder: o próprio Javadoc dela diz *"Nenhum estado ZA/SVE é modelado ainda — esta feature
   só (…)"*. Não há `SVE`, não há `SVE2`.
2. **Não existe modelo de registrador vetorial escalável.** SVE precisa de:
   - `Z0-Z31` — vetores de comprimento **implementation-defined** (128 a 2048 bits, múltiplos de
     128), aliasando `V0-V31` do AdvSIMD nos 128 bits baixos (o banco que a B8.6 já estendeu);
   - `P0-P15` — registradores de **predicado** (1 bit por byte de vetor, ou seja `VL/8` bits);
   - `FFR` — First Fault Register (load tolerante a falha);
   - `ZCR_EL1`/`ZCR_EL2`/`ZCR_EL3` — controle de comprimento de vetor por nível de exceção.
3. **Comprimento de vetor agnóstico (VLA)** é o traço que define SVE: o mesmo binário roda em
   qualquer VL. O executor não pode assumir 128 bits em lugar nenhum — é uma decisão de design que
   permeia todo o pipeline, diferente de tudo que o projeto fez até aqui.

## A decisão que este épico precisa tomar primeiro (B17.2)

Escolher o **comprimento de vetor** que a implementação anuncia (`ZCR_ELx.LEN` + `ID_AA64ZFR0_EL1`)
e como o executor o representa em Java:

- **VL fixo em 128 bits** — reaproveita literalmente o banco de B8.6 (`Aarch64FpRegisters` já é de
  128 bits), toda instrução SVE vira "AdvSIMD com predicado". Barato, roda binário SVE real
  (VLA garante isso), mas é o mínimo arquitetural e não exercita o que SVE tem de próprio.
- **VL configurável** (`long[]` por registrador, dimensionado pelo preset) — fiel, permite testar
  o mesmo binário em VLs diferentes (que é como bug de VLA aparece), mas mexe em toda a IR.

A recomendação da regra máxima do projeto é a segunda — mas isso é uma decisão de usuário/RFC, não
de agente. **B17.2 é uma RFC** (formato de `docs/RFC-SOFTMMU.md`/`RFC-M-PROFILE.md`).

## Escada

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B17.1** | `Aarch64Feature.SVE` + `SVE2` + preset: `ARMV9_0_A` passa a declarar `SVE` (é obrigatória em ARMv9), `ARMV8_2_A`+ ganham como opcional. SEM decode — só estrutura e gate, espelho de B11.1 | 0 | — |
| **B17.2** | **RFC**: comprimento de vetor (fixo 128 vs configurável), representação em Java, e como `Z`/`P`/`FFR` convivem com o banco `V` de 128 bits de B8.6 | 0 | B17.1 |
| **B17.3** | Fundação de estado: `Z0-Z31`, `P0-P15`, `FFR`, `ZCR_EL1/2/3` no `Aarch64Core` + `MRS`/`MSR` deles + `ID_AA64ZFR0_EL1`; save/restore. SEM decode de instrução SVE | 0 | B17.2 |
| **B17.4** | Predicados — lógica, misc, partition break, contagem, element count (`PTRUE`/`PFALSE`/`WHILE*`/`CNTP`/`BRKA`/…). É o pré-requisito de quase tudo: sem predicado não há SVE | 40 | B17.3 |
| **B17.5** | Inteiro não-predicado: aritmética, lógica, shift, multiply-add, misc, índice (`INDEX`) | 45 | B17.4 |
| **B17.6** | Inteiro predicado: aritmética binária, unária, shift por imediato | 68 | B17.4 |
| **B17.7** | Reduções inteiras (`SADDV`/`UADDV`/`SMAXV`/`ANDV`/…) | 19 | B17.6 |
| **B17.8** | Imediato: bitwise immediate + wide immediate (predicado e não-predicado) — inclui o bloco de 99 linhas, que é majoritariamente expansão de decodetree | 108 | B17.5 |
| **B17.9** | Comparação: vetores, imediato com/sem sinal, escalares | 38 | B17.4 |
| **B17.10** | Permutação I: extract, unpredicated, interleaving (`ZIP`/`UZP`/`TRN`/`DUP`/`EXT`) | 42 | B17.5 |
| **B17.11** | Permutação II: predicates, predicated, select (`SEL`/`COMPACT`/`SPLICE`/`LAST*`) | 36 | B17.10 |
| **B17.12** | Endereçamento: compute vector address, stack allocation (`ADDVL`/`ADDPL`/`RDVL`) | 10 | B17.5 |
| **B17.13** | FP I: aritmética não-predicada e predicada | 30 | B17.4 |
| **B17.14** | FP II: multiply-add, multiply-add indexado, multiply indexado | 20 | B17.13 |
| **B17.15** | FP III: comparação (vetores e com zero), reduções (fast, recursive quadword, accumulating) | 38 | B17.13 |
| **B17.16** | FP IV: operações unárias predicadas (bloco de 105 linhas — conversões, `FSQRT`, `FRINT*`, `FRECPX`) | 105 | B17.13 |
| **B17.17** | Memória I: load contíguo (`LD1*`/`LDNF1*`/`LDFF1*`) + `FFR` de verdade | 31 | B17.4 |
| **B17.18** | Memória II: store (`ST1*`/`STNT1*`) | 37 | B17.17 |
| **B17.19** | Memória III: gather 32 e 64 bits (`LD1*` com índice vetorial) | 20 | B17.17 |
| **B17.20** | SVE2 inteiro I: multiply unpredicated, predicado, unário, pairwise | 21 | B17.6 |
| **B17.21** | SVE2 inteiro II: saturating/rounding shift, halving add/sub, saturating add/sub (bloco de 102) | 122 | B17.20 |
| **B17.22** | SVE2 misc: character match, histogram/LUT, broadcast predicate element, clamp | 42 | B17.20 |
| **B17.23** | SVE2 FP: convert precision odd elements, convert to integer, multiply-add long (vetores e indexado), dot-product (e indexado), matmul FP | 46 | B17.16 |
| **B17.24** | SVE2 cripto (`AESE`/`AESD`/`SM4E`/`RAX1` vetoriais) — semântica já existe de B8.11/B8.11b/B11.12, só o encoding SVE é novo | 7 | B17.5 |
| **B17.25** | SVE2 memória (gather load, store) + SVE2.1 multi-vec contiguous load | 20 | B17.19 |
| **B17.26** | **Fechamento**: `IsaCoverageReport` troca `NOT_IN_ANY_PRESET` do grupo `sve.decode` por um `Applicability` de `SVE`, com coluna por versão (mesma mecânica de B11.5); `Aarch64Processor` ganha os Neoverse/Cortex com SVE de verdade | 0 | B17.25 |

## Meta

O grupo `SVE/SVE2 — vetor escalável` sai de "não se aplica a nenhum preset atual" e passa a medir
929 células. Sem isso, os presets `ARMV9_*_A` do `Aarch64Architecture` (B11.1) anunciam uma
arquitetura que a implementação não tem.

## Armadilhas

- **VLA é uma disciplina, não uma feature**: qualquer `if (length == 128)` escondido no executor
  torna o backend incorreto para outro VL. Se a RFC B17.2 escolher VL fixo, isso tem que ser uma
  CONSTANTE nomeada e única (G6), não 128 espalhado.
- **`Z` alias `V`**: escrever `Z0` altera `V0` (os 128 bits baixos) e vice-versa. O banco de B8.6
  precisa virar a vista baixa do banco `Z`, não um banco irmão — mesmo achado que o MVE terá com
  `Q0-Q7` (ver `b16-plano-mve-helium.md`).
- **Load tolerante a falha (`LDFF1`/`LDNF1`) escreve o `FFR`** em vez de levantar aborto: é o
  oposto do modelo de aborto preciso que a B6.6.4 construiu. Não tratar como caso especial de
  `Load` — é uma classe própria.
- O medidor conta encodings de decodetree: blocos como "Integer Wide Immediate - Unpredicated"
  (99) e "FP Unary Operations Predicated" (105) são majoritariamente expansão mecânica de campos,
  não 200 semânticas distintas (lição de B8.11b).
