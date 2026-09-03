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
| **[B19.5](b19.5-plano-fp16.md)** | **`FEAT_FP16`** (aritmética de meia precisão): a linha `_h` de TODA família FP que hoje só tem `_sd`. **84 linhas — decomposta em plano próprio (2026-08-29), escada B19.5.1-B19.5.6.** Achado: `Aarch64Feature.FP16` já é declarada em `ARMV8_2_A` e ninguém a consulta | **84** | B19.4 |
| **[B19.6](b19.6-a64-diversos.md)** | Diversos: `SYS`/`SYSL` (`op0`=1,2 — o `op0`=3 já é ✅ pela B6.6.1), `PRFM (literal)`, `PACGA` (`FEAT_PAuth`), `ABS` de registrador geral (`FEAT_CSSC`), `DUP` escalar, `FMOV` de/para `Vn.D[1]`, `FMOVI_v_h` e `Vimm` (`MOVI`/`MVNI`/`ORR`/`BIC` imediato — irmão A64 da **B13.9**, que traz o núcleo `AdvSIMDExpandImm` compartilhado) | 10 | B13.9 (só o bloco `Vimm`) |
| **[B19.7](b19.7-a64-bf16.md)** | **`FEAT_BF16`** (bfloat16) — degrau SEPARADO do FP8 pela remedição de 2026-09-03: são features/versões diferentes, e 6 das 8 linhas estão hoje INVISÍVEIS (`·`) pela curadoria grossa da TSV (⇒ **E12** antes). O JDK não tem `bfloat16` (diferente de `binary16`): a conversão é código novo | 8 | E12 |
| **[B19.8](b19.8-a64-feat-lut.md)** | `FEAT_LUT` (`LUTI2`/`LUTI4`, ARMv9.5-A) — consulta de tabela com índices EMPACOTADOS, vizinha de `TBL`/`TBX` (B8.10). Feature nova (`LOOKUP_TABLE`) | 4 | — |
| **[B19.9](b19.9-a64-fechamento.md)** | **Fechamento**: remedir as 16 colunas, enumerar o que sobrou COM DESTINO NOMEADO, `docs/VALIDACAO-ARQUITETURAS.md` | 0 | todas |
| **[B19.10](b19.10-a64-cripto-sha512-sm3-sm4.md)** 🆕 | **Cripto SHA-512 / SM3 / SM4** — 13 linhas no MESMO prefixo `0xCE` que a **B11.12** abriu para `FEAT_SHA3` e deixou pela metade. 3 features distintas, todas com constante já existente. 7 das 13 invisíveis hoje (⇒ E12) | 13 | E12 |
| **[B19.11](b19.11-a64-fp8.md)** 🆕 | **`FEAT_FP8`** — separado da B19.7. **Única das três sem constante em `Aarch64Feature`**; dois formatos (E4M3/E5M2) e `FPMR` governando as famílias de acumulação (decisão de escopo (a)/(b) na spec). ⚠️ `BF1CVTL`/`BF2CVTL` têm "BF" no nome mas são FP8 | 12 | B19.7 |
| **[B19.12](b19.12-a64-i8mm.md)** 🆕 | **`FEAT_I8MM`** — produto escalar MISTO (`USDOT`/`SUDOT`) e matriz 2×8·8×2 (`SMMLA`/`UMMLA`/`USMMLA`). Grupo que a escada original não tinha; `SDOT_v`/`UDOT_v` já são ✅ e são a alavanca. Todas as 6 invisíveis hoje (⇒ E12) | 6 | E12 |
| **[B19.13](b19.13-a64-fhm.md)** 🆕 | **`FEAT_FHM`** (`FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2`) — feature PRÓPRIA (não é `FEAT_FP16`), constante já existente, largura mista f16→f32. As 8 invisíveis hoje (⇒ B19.5.2) | 8 | B19.5.2, B19.5.4 |

## Decomposição da B19.5 → plano próprio ✅

O esboço que esta seção continha foi **substituído por um plano medido** em 2026-08-29:
**[`b19.5-plano-fp16.md`](b19.5-plano-fp16.md)**, com o inventário das 84 linhas por template, as
**3 barreiras estruturais** encontradas no decoder e a escada **B19.5.1-B19.5.6** ordenada por custo
estrutural crescente. Ler o plano, não este resumo.

Dois achados do plano que valem para o épico inteiro:

1. **`Aarch64Feature.FP16` já é declarada em `ARMV8_2_A` e nenhum ponto de `core/src/main` a
   consulta** — o preset anuncia hoje uma extensão que o decoder recusa por completo. B19.5 não é
   "acrescentar uma feature", é tornar real uma que o projeto já promete.
2. **`IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS` casa por MNEMÔNICO, e `FEAT_FP16` é por
   LINHA** (`FADD_v` tem `_h` de v8.2 e `_sd` de ISA base). É a mesma limitação que a **B9.17**
   resolveu no lado de 32 bits com a coluna `ocorrencia`. Enquanto não for resolvida, as 84 linhas
   contam como `❌` em `ARMv8.0-A`/`ARMv8.1-A`, onde a feature nem existe: **168 células com
   denominador errado**. Vira a B19.5.2 e vem ANTES do decode.

**Task irmã, fora do épico B19**: "NEON FP16 AArch32" — as formas `sz=1` do NEON A32, recusadas pela
**B13.6** (22 linhas de 3-reg-same FP) e pela **B13.8** (4 linhas de `VCVT`), com destino registrado
nas duas. Depende da MESMA fundação de **B19.5.1** e deve ser sequenciada junto, não separada — foi
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
