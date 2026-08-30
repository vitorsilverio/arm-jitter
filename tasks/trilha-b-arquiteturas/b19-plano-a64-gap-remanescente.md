# B19 — A64: fechar o gap remanescente (~174 encodings): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce da auditoria de 2026-08-29 (pedido do usuário: "escrever a spec de
todas as extensões ainda faltantes"): depois de B8 (AdvSIMD), B10 (EL2/EL3) e B11 (gating por
feature), o A64 estagnou em **82%** — e, diferente dos 7 grupos `NOT_IN_ANY_PRESET` (que têm os
épicos B13-B18), **este gap está dentro de um preset que EXISTE e é usado** (`ARMV8_0_A` é o preset
do `armbox`/`virtual-arm-box` aarch64). Era a única superfície grande do projeto sem plano escrito.

## O número (medido nesta sessão a partir de `docs/COBERTURA-ISA.md`)

| Métrica | Valor (medição original, 2026-08-29) |
|---|---:|
| Células `❌` em `ARMv8.0-A` | **174** (de 970 aplicáveis) |
| Mnemônicos distintos envolvidos | **119** |
| Faixa nas demais versões | 174-180 células (o gap é achatado em todas — v8.0 a v9.5) |

Método: join da seção `## A64 — AArch64` da tabela com as linhas de `target/isa-decode/a64.decode`.
Vários mnemônicos têm 2-5 linhas de encoding e só ALGUMAS faltam (ex.: `FADD_v` tem `@qrrr_h` ❌ e
`@qrrr_sd` ✅) — por isso o épico é organizado por **linha de encoding**, não por mnemônico.

### ⚠️ Remedição de 2026-08-29 (rodada de spec pós-B19.3) — a escada original estava mal dimensionada

Depois de B19.1-B19.3 fecharem 53 linhas, o join foi refeito **classificando cada célula `❌` pelo
TEMPLATE da sua linha de encoding** (`@qrr_h` vs `@qrr_sd`, `@icvt_h` vs `@icvt_sd`, …), o que a
medição original não fez. Resultado (121 células `❌` restantes em `ARMv8.0-A`):

| Degrau | Linhas `❌` REAIS | Estimativa original | Por quê a diferença |
|---|---:|---:|---|
| **B19.4** | **11** | ~40 | A **B8.9 já implementou todas as formas `_sd` vetoriais** de "two-reg-misc"; o que restava nesses mnemônicos é a linha `_h` (⇒ B19.5). Sobram só `FCVTL_v`/`FCVTN_v`/`FCVTXN_v` + 8 de ponto fixo vetorial |
| **B19.5** (`FEAT_FP16`) | **84** | 13 | É o degrau **MAIOR** do épico, não o menor: acumulou as `_h` herdadas de B8.9 (vetoriais), B19.2 (14 escalares), B19.3 (16) e B19.4 (4). Precisa de decomposição própria — ver abaixo |
| B19.6 (diversos) | 10 | ~10 | ✔ |
| B19.7 (BF16/FP8) | 12 | ~12 | ✔ |
| B19.8 (`FEAT_LUT`) | 4 | 4 | ✔ |

**Consequência de sequenciamento**: B19.5 deixa de ser "um degrau" e passa a exigir **rodada de spec
própria** que a decomponha (esboço abaixo, marcado `[REFINAR]`). B19.4 virou o degrau barato e é o
próximo pegável.

## Clusters (é a escada)

> ⚠️ **Os números desta tabela são a medição ORIGINAL de 2026-08-29, antes da remedição por
> template** (ver seção anterior). Os clusters "FP vetorial" e "`FEAT_FP16`" estão dimensionados
> errado aqui — use a tabela de remedição e a escada abaixo, não esta.

| Cluster | O que | Linhas `❌` |
|---|---|---:|
| Atômicos LSE + RCPC | `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` (todos `@atomic`) + `LDAPR` | 10 |
| AdvSIMD FP **escalar** — 3-same e pairwise | `FMULX_s`/`FCMEQ_s`/`FCMGE_s`/`FCMGT_s`/`FACGE_s`/`FACGT_s`/`FABD_s`/`FRECPS_s`/`FRSQRTS_s` + `FADDP_s`/`FMAXP_s`/`FMINP_s`/`FMAXNMP_s`/`FMINNMP_s` (todos `_h` E `_sd`) | 28 |
| AdvSIMD FP **escalar** — 2-reg-misc e conversões | `FCMGT0_s`/`FCMGE0_s`/`FCMEQ0_s`/`FCMLE0_s`/`FCMLT0_s`/`FRECPE_s`/`FRECPX_s`/`FRSQRTE_s`/`FCVTXN_s` + `SCVTF_f`/`UCVTF_f`/`FCVT{N,P,M,Z,A}{S,U}_f` (formas `@icvt_*` E ponto fixo `@fcvt_fixed_*`) | ~45 |
| AdvSIMD FP **vetorial** — 2-reg-misc e conversões | `FABS_v`/`FNEG_v`/`FSQRT_v`/`FRINT{N,M,P,Z,A,X,I}_v`/`FCM**0_v`/`FRECPE_v`/`FRSQRTE_v`/`FCVTL_v`/`FCVTN_v`/`FCVTXN_v` + `SCVTF_vi`/`UCVTF_vi`/`FCVT**_vi` + ponto fixo `SCVTF_vf`/`UCVTF_vf`/`FCVTZS_vf`/`FCVTZU_vf` | ~40 |
| **FEAT_FP16** (só a linha `_h`) | `FADD_v`/`FSUB_v`/`FMAX_v`/`FMIN_v`/`FCMEQ_v` + indexadas `FMUL_si`/`FMLA_si`/`FMLS_si`/`FMULX_si`/`FMUL_vi`/`FMLA_vi`/`FMLS_vi`/`FMULX_vi` | 13 |
| BF16 / FP8 | `BFMLAL_v`/`BFCVTN_v`/`FCVTN_bh`/`FCVTN_bs`/`FMLAL_hb_v`/`FMLALL_sb_v` (+ formas `_vi`) e `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL` | ~12 |
| `FEAT_LUT` (ARMv9.5) | `LUTI2_1b`/`LUTI2_1h`/`LUTI4_1b`/`LUTI4_2h` | 4 |
| Diversos | `SYS` (2 das 3 linhas), `NOP` (1 das 5), `PACGA`, `ABS` (`@rr_sf`), `DUP_element_s`, `FMOV_xu`/`FMOV_ux`, `FMOVI_v_h`, `Vimm` | ~10 |

## Escada (refinar em spec própria quando cada degrau for pego)

| Task | Escopo | Linhas | Depende de |
|---|---|---:|---|
| **B19.1** | Atômicos `FEAT_LSE` (`LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP`, com as 4 combinações `A`/`R` de aquisição/liberação e as larguras `B`/`H`/`W`/`X`) + `LDAPR` (`FEAT_LRCPC`). `CAS`/`CASP` JÁ existem — este degrau completa a extensão pela metade | 10 | — |
| **B19.2** | AdvSIMD FP escalar "three same" + pairwise escalar — espelho do que `Ir64VectorFpArithmeticExecutor` já faz na forma VETORIAL; a forma escalar reaproveita os mesmos records (padrão de B8.8) | 28 | — |
| **B19.3** | AdvSIMD FP escalar "two-reg-misc" + conversões escalares int↔FP (incluindo ponto fixo) | ~45 | B19.2 |
| **[B19.4](b19.4-a64-advsimd-fp-vetorial-convert.md)** | Conversões de PRECISÃO vetoriais (`FCVTL_v`/`FCVTN_v`/`FCVTXN_v`) + conversões vetoriais FP↔ponto fixo (`SCVTF_vf`/`UCVTF_vf`/`FCVTZS_vf`/`FCVTZU_vf`, formas `s`/`d`). **Spec escrita 2026-08-29.** Introduz o formato `binary16` (só CONVERSÃO — `Float.float16ToFloat`/`floatToFloat16` do JDK 20+), que é ISA base ARMv8.0-A e não `FEAT_FP16` | **11** | B19.3 |
| **B19.5** `[REFINAR]` | **`FEAT_FP16`** (aritmética de meia precisão): a linha `_h` de TODA família FP que hoje só tem `_sd`, com `Aarch64Feature` própria e gate por versão (ARMv8.2-A). **84 linhas — precisa de rodada de spec que decomponha** (esboço abaixo) | **84** | B19.4 |
| **B19.6** | Diversos: `SYS` (⚠️ ver Armadilhas — pode ser medição, não gap), `NOP` restante, `PACGA` (`FEAT_PAuth`), `ABS` inteiro (`FEAT_CSSC`), `DUP_element_s`, `FMOV_xu`/`FMOV_ux`, `FMOVI_v_h`, `Vimm` | ~10 | — |
| **B19.7** | BF16 (`BFMLAL`/`BFCVTN`) e FP8 (`FCVTN_bh`/`FCVTN_bs`/`FMLAL_hb`/`FMLALL_sb`/`F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL`), com as features `FEAT_BF16`/`FEAT_FP8` e gate por versão | ~12 | B19.5 |
| **B19.8** | `FEAT_LUT` (`LUTI2`/`LUTI4`, ARMv9.5) — tabela de consulta por lane | 4 | B19.4 |
| **B19.9** | **Fechamento**: remedir, curar em `docs/isa-nao-aplicavel.tsv` o que for genuinamente posterior à versão da coluna, e registrar o A64 na matriz `docs/VALIDACAO-ARQUITETURAS.md` | 0 | B19.1-B19.8 |

## Esboço de decomposição da B19.5 `[REFINAR]` (escrito na remedição de 2026-08-29)

Não executar direto — é material para a próxima rodada de spec. As 84 linhas `_h` se agrupam por
**onde moram no decoder**, e todas compartilham UM pré-requisito: um caminho de meia precisão no
núcleo vetorial (`advsimd/AdvSimdLanes`) e no `Ir64VectorFp*Executor`.

| Sub | Escopo provável | Linhas |
|---|---|---:|
| **B19.5.1** | **Fundação, sem decode novo**: `Aarch64Feature.FEAT_FP16` (ARMv8.2-A) + caminho `esz=1` em `AdvSimdLanes.fpThreeSame`/`fpPairwise`/`fpCombinePair` e no executor unário, sobre `Float.float16ToFloat`/`floatToFloat16` (que a **B19.4** já terá trazido para o projeto). Zero-diff | 0 |
| **B19.5.2** | `_h` de "three same" + pairwise, **vetorial e escalar** (`FADD_v`/`FSUB_v`/`FMAX_v`/`FMIN_v`/`FCMEQ_v` + as 14 escalares que a B19.2 recusou) | ~19 |
| **B19.5.3** | `_h` de "two-reg-misc" **vetorial e escalar** (`FABS_v`/`FNEG_v`/`FSQRT_v`/`FRINTx_v`/`FCM**0_v`/`FRECPE_v`/`FRSQRTE_v` + as 8 escalares de B19.3) | ~25 |
| **B19.5.4** | `_h` das conversões: `@icvt_h` (vetorial `_vi` + escalar `_f`), `@fcvt_fixed_h` (escalar) e `@fcvtq_h` (vetorial) | ~28 |
| **B19.5.5** | `_h` das formas INDEXADAS (`@qrrx_h`: `FMUL_si`/`FMLA_si`/`FMLS_si`/`FMULX_si` + `_vi`) | ~8 |

**Task irmã, fora do épico B19**: "NEON FP16 AArch32 / `FEAT_FP16`" — registrada pela **B13.6** no
`## Resultado` dela (as formas `sz=1` das 22 linhas de 3-reg-same FP A32 viram `UNIMPLEMENTED` hoje).
Ela depende da MESMA fundação de B19.5.1 e deve ser sequenciada junto, não separada — foi
exatamente para não criar assimetria entre os dois lados que a B13.6 recusou F16.

## Meta

A64 sai de **82%** para ~100% em todas as 16 versões medidas — é o maior salto isolado de cobertura
global disponível hoje (~174 células × 16 colunas no denominador global).

**Progresso real** (medido): 174 → **121** células `❌` depois de B19.1 (10), B19.2 (28) e B19.3 (29).
A64 `ARMv8.0-A` 82% → **87%**; global 83% → **88%**.

## Invariantes específicos deste épico

- **G1**: toda família nova é oráculo-interpretada primeiro; `Ir64NativePolicy` não recebe `Kind`
  vetorial novo (mesma decisão de B8.4 em diante).
- **G8**: cada degrau fecha o seu espaço de encoding — o que sobra cai em `UNIMPLEMENTED`.
- **Gating por versão** (B11): `FEAT_LSE` é ARMv8.1, `FEAT_LRCPC` 8.3, `FEAT_FP16` 8.2, `FEAT_BF16`
  8.6, `FEAT_CSSC` 8.9, `FEAT_LUT` 9.5 — nenhuma feature entra num preset anterior à versão que a
  introduziu (é exatamente o que a escada B11 construiu).

## Armadilhas conhecidas

- **`SYS` pode ser artefato de medição, não gap.** As 3 linhas de `a64.decode` chamadas `SYS`
  cobrem `op0=1` (SYS/SYSL: `DC`/`IC`/`AT`/`TLBI`) e `op0=2`/`op0=3` (movimentos de registrador de
  sistema). A terceira mede ✅ e as duas primeiras ❌, mas o projeto JÁ implementa manutenção de
  cache/TLB e sysregs por caminhos próprios (`Ir64SystemInstructionOp`, `Aarch64SystemRegisterId`,
  B10/B11). **B19.6 tem que primeiro DECIDIR se é gap real ou limitação do medidor** (mesma classe
  de achado de B9.15/B9.17, que resolveram com as colunas `grupo`/`ocorrencia` do TSV) — implementar
  antes de medir aqui é retrabalho garantido.
- **`ABS` mede `❌` até em `ARMv8.0-A`**, onde a instrução nem existe (`FEAT_CSSC` é ARMv8.9) — parte
  do trabalho de B19.6/B19.9 é curadoria de denominador, não decode.
- **`_h` não é "mais um tamanho".** As formas de meia precisão têm o bit alto de `size` usado como
  opcode (armadilha que B8.9 já pagou no A64 e que o épico B13 registra de novo) — o encoding `_h`
  mora em outro ponto do espaço, não é o `_sd` com `esz` menor.
- **Escalar reaproveita record vetorial, mas a ESCRITA muda**: a forma escalar zera tudo acima de
  `esz` bits, inclusive dentro do `low64` (`finishScalarAwareWrite`) — bug real já documentado em
  `Ir64VectorArithmeticExecutor`.
- **Ponto fixo (`@fcvt_fixed_*`) tem campo `scale`** que as formas `@icvt_*` não têm; são linhas de
  encoding distintas do MESMO mnemônico (`SCVTF_f` tem 5 linhas, todas ❌ hoje).
- **Atômicos LSE são RMW no barramento**: o executor precisa ler-modificar-escrever com a mesma
  disciplina de `LDXR`/`STXR` (monitor de exclusividade não se aplica, mas `notifyOrdinaryWrite`
  sim, ou código automodificável deixa de invalidar bloco de JIT).
