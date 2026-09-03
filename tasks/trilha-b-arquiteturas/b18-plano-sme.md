# B18 — SME / SME2 (Scalable Matrix Extension): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano
**Depende do épico:** `b17-plano-sve.md` (SME roda em *streaming SVE mode*; sem SVE não há SME)

Último dos 7 grupos "não se aplica a nenhum preset atual". Ver `b13-plano-neon-a32.md` para a
discussão do rótulo.

## O número (medido, `target/isa-decode/sme.decode`)

**623 encodings** — contra a revisão do QEMU fixada pela E11 (`QEMU_REV` em
`gerar-cobertura-isa.sh`, hoje `2931a675e9d3…`). Uma rodada de spec que baixou `master` em
2026-09-03 viu 651; ao fixar a revisão o número volta a 623. Se um bump de `QEMU_REV` mexer neste
total, refazer a escada contra o novo inventário. Em 18 seções `###`:

| Bloco | Encodings |
|---|---:|
| SME misc + move into/from array + move and zero + move into/from ZT0 | ~110 |
| SME memória (`LD1`/`ST1` de tile) | 28 |
| SME add vector to array + outer product (`FMOPA`/`SMOPA`/…) | ~44 |
| SME2 multi-vector: multiple-and-single SVE destructive / multiple-vectors SVE destructive | ~87 |
| SME2 multi-vector: multiple-and-single array vectors / multiple array vectors | ~315 |
| SME2 multi-vector indexed | ~206 |
| SME2 add/sub array accumulators + SVE constructive unary/binary + select | ~110 |
| SME multiple zero + lookup table read | ~40 |

(as somas por seção ultrapassam 623 porque várias seções compartilham linhas de grupo `{}` no
decodetree — o total medido pelo relatório é o número autoritativo)

## O que falta de infraestrutura

1. **`Aarch64Feature.SCALABLE_MATRIX_EXTENSION` existe, mas é um placeholder confesso.** O Javadoc
   da constante diz, com todas as letras: *"`FEAT_SME` — Scalable Matrix Extension (estado
   ZA/streaming-SVE, controlado por `SVCR` via `MSR (immediate)`). ARMv9.2-A. **Nenhum estado
   ZA/SVE é modelado ainda** — esta feature só (…)"*. Ou seja: o gate existe porque a B8.3
   precisou reconhecer `MSR SVCR`, mas não há nada atrás dele.
2. **Todo o estado de matriz falta**: `ZA` (array de tiles de `VL×VL` bits), `ZT0` (lookup table de
   512 bits, SME2), `SVCR` (`SM`/`ZA` bits — modo streaming e habilitação do array), `SMCR_EL1/2/3`
   (comprimento de vetor em modo streaming, análogo a `ZCR`), `ID_AA64SMFR0_EL1`.
3. **Modo streaming muda o comportamento de instruções que já existem**: em *streaming SVE mode*,
   parte do AdvSIMD/SVE fica indisponível ou muda de VL (o VL efetivo passa a ser `SVL`, de
   `SMCR`). Isso é estado global que o decoder/executor precisam consultar — é o tipo de
   acoplamento que a RFC B17.2 tem que já prever.

## Escada

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B18.1** | Estado: `SVCR` real (`SM`/`ZA`), `SMCR_EL1/2/3`, `ID_AA64SMFR0_EL1`, banco `ZA` e `ZT0` no `Aarch64Core` + save/restore. Substitui o placeholder da B8.3. SEM decode de instrução SME | 0 | B17.3 |
| **B18.2** | Modo streaming: efeito de `SVCR.SM` sobre VL efetivo (`SVL`) e sobre a disponibilidade de AdvSIMD/SVE; `SMSTART`/`SMSTOP` de verdade (hoje só o `MSR (immediate)` de B8.3) | ~6 | B18.1 |
| **B18.3** | SME misc + move into/from array (`MOVA`) + move and zero | ~80 | B18.2 |
| **B18.4** | SME memória: `LD1`/`ST1` de tile (`LDR ZA`/`STR ZA`) | 28 | B18.3 |
| **B18.5** | SME outer product: `FMOPA`/`FMOPS`/`SMOPA`/`UMOPA`/`BFMOPA`/… + add vector to array | ~44 | B18.3 |
| **B18.6** | SME multiple zero (`ZERO`) + lookup table read (`LUTI`) + move into/from `ZT0` | ~40 | B18.3 |
| **B18.7** | SME2 multi-vector I: multiple-and-single SVE destructive | ~44 | B18.5 |
| **B18.8** | SME2 multi-vector II: multiple-vectors SVE destructive | ~43 | B18.7 |
| **B18.9** | SME2 multi-vector III: multiple-and-single array vectors (maior bloco do inventário) | ~158 | B18.7 |
| **B18.10** | SME2 multi-vector IV: multiple array vectors | ~157 | B18.9 |
| **B18.11** | SME2 multi-vector indexed | ~206 | B18.9 |
| **B18.12** | SME2 add/sub array accumulators + SVE constructive unary/binary + select | ~110 | B18.9 |
| **B18.13** | **Fechamento**: `IsaCoverageReport` troca `NOT_IN_ANY_PRESET` do grupo `sme.decode` por um `Applicability` de `SCALABLE_MATRIX_EXTENSION`; `Aarch64Processor` ganha os núcleos com SME real | 0 | B18.12 |

## Meta

O grupo `SME — extensão matricial` sai de "não se aplica a nenhum preset atual". Com B13, B14,
B15, B16, B17 e B18 fechados, **`docs/COBERTURA-ISA.md` não tem mais nenhum grupo
`NOT_IN_ANY_PRESET`** — que é o alvo que o usuário definiu em 2026-08-28 e o pré-requisito real
do release `1.4.0` (`tasks/README.md`: "`1.4.0` fica RESERVADA para quando `docs/COBERTURA-ISA.md`
mostrar 100% de cobertura de TODA a arquitetura ARM alvo").

## Armadilhas

- **`SCALABLE_MATRIX_EXTENSION` já é declarada por `ARMV9_2_A`+** (B8.3/B11.x). Se B18.1 puser
  estado real atrás dela sem cuidado, presets que hoje só reconheciam `MSR SVCR` passam a
  prometer SME inteiro — o gate tem que ser dividido (`SME` vs `SME2`) antes de crescer.
- **`ZA` é grande**: `VL×VL` bits — com VL de 512 bits são 32 KiB por CPU. Alocar isso
  incondicionalmente no `Aarch64Core` penaliza todo consumidor que não usa SME (incluindo
  `virtual-arm-box`); alocação preguiçosa é requisito, não otimização.
- Ordem de dependência real: **B17 (SVE) inteiro antes de B18.7+**. As instruções "SME2
  multi-vector … SVE destructive" operam sobre registradores `Z` com predicado — sem o modelo de
  B17.3/B17.4 elas não têm onde escrever.
