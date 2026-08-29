# B19 — A64: fechar o gap remanescente (~174 encodings): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce da auditoria de 2026-08-29 (pedido do usuário: "escrever a spec de
todas as extensões ainda faltantes"): depois de B8 (AdvSIMD), B10 (EL2/EL3) e B11 (gating por
feature), o A64 estagnou em **82%** — e, diferente dos 7 grupos `NOT_IN_ANY_PRESET` (que têm os
épicos B13-B18), **este gap está dentro de um preset que EXISTE e é usado** (`ARMV8_0_A` é o preset
do `armbox`/`virtual-arm-box` aarch64). Era a única superfície grande do projeto sem plano escrito.

## O número (medido nesta sessão a partir de `docs/COBERTURA-ISA.md`)

| Métrica | Valor |
|---|---:|
| Células `❌` em `ARMv8.0-A` | **174** (de 970 aplicáveis) |
| Mnemônicos distintos envolvidos | **119** |
| Faixa nas demais versões | 174-180 células (o gap é achatado em todas — v8.0 a v9.5) |

Método: join da seção `## A64 — AArch64` da tabela com as linhas de `target/isa-decode/a64.decode`.
Vários mnemônicos têm 2-5 linhas de encoding e só ALGUMAS faltam (ex.: `FADD_v` tem `@qrrr_h` ❌ e
`@qrrr_sd` ✅) — por isso o épico é organizado por **linha de encoding**, não por mnemônico.

## Clusters (é a escada)

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
| **B19.4** | AdvSIMD FP vetorial "two-reg-misc" + conversões vetoriais (incluindo ponto fixo, `FCVTL`/`FCVTN`/`FCVTXN`) | ~40 | B19.3 |
| **B19.5** | **`FEAT_FP16`**: acrescentar a linha `_h` a TODA família FP que hoje só tem `_sd`, com `ArmFeature`/`Aarch64Feature` própria e gate por versão (`FEAT_FP16` é ARMv8.2-A) | 13 | B19.4 |
| **B19.6** | Diversos: `SYS` (⚠️ ver Armadilhas — pode ser medição, não gap), `NOP` restante, `PACGA` (`FEAT_PAuth`), `ABS` inteiro (`FEAT_CSSC`), `DUP_element_s`, `FMOV_xu`/`FMOV_ux`, `FMOVI_v_h`, `Vimm` | ~10 | — |
| **B19.7** | BF16 (`BFMLAL`/`BFCVTN`) e FP8 (`FCVTN_bh`/`FCVTN_bs`/`FMLAL_hb`/`FMLALL_sb`/`F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL`), com as features `FEAT_BF16`/`FEAT_FP8` e gate por versão | ~12 | B19.5 |
| **B19.8** | `FEAT_LUT` (`LUTI2`/`LUTI4`, ARMv9.5) — tabela de consulta por lane | 4 | B19.4 |
| **B19.9** | **Fechamento**: remedir, curar em `docs/isa-nao-aplicavel.tsv` o que for genuinamente posterior à versão da coluna, e registrar o A64 na matriz `docs/VALIDACAO-ARQUITETURAS.md` | 0 | B19.1-B19.8 |

## Meta

A64 sai de **82%** para ~100% em todas as 16 versões medidas — é o maior salto isolado de cobertura
global disponível hoje (~174 células × 16 colunas no denominador global).

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
