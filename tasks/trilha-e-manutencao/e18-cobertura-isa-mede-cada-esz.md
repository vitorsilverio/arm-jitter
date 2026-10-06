# E18 — `IsaCoverageReport`: a célula A64 só é ✅ se TODOS os tamanhos de elemento decodificam

**Trilha:** E · **Repo:** arm-jitter (só `core/src/test` + `docs/`; G5 não se aplica) · **Depende de:** E17 ✅
**Status:** ⬜ — spec escrita em 2026-10-06 pela E15.14 (achado).

## Contexto

A E15.14 achou que a aritmética FP escalar de meia precisão (`type=11`, `FEAT_FP16`) **nunca foi implementada**
e mesmo assim `docs/COBERTURA-ISA.md` mede `FADD_s`, `FCMP`, `FCSEL`, `FMADD`, `FCVTZS_g`… ✅ em todas as colunas
(→ [B19.34](../trilha-b-arquiteturas/b19.34-a64-fp16-fp-escalar.md)).

Causa, em `IsaCoverageReport#probeAarch64`: a célula percorre `FILL_STRATEGIES` e fica ✅ se **alguma**
estratégia de preenchimento decodifica (com a assinatura certa, desde a E17). Um padrão como
`FADD_s 0001 1110 ..1 ..... 0010 10 ..... ..... @rrr_hsd` tem `type` livre (`esz=%esz_hsd`): basta a estratégia
"zeros" (`type=00`, S) decodificar. O mesmo vale para qualquer padrão cujo tamanho de elemento é campo livre —
o tamanho H/B/Q que falta fica invisível. A E17 resolveu "decodifica a instrução CERTA"; esta resolve "decodifica
TODAS as formas da linha".

## Objetivo

Para cada linha A64 cujo tamanho de elemento é campo livre do `.decode` (`%esz_hsd`, `%esz_sd`, `%esz_hs`,
`esz:2`/`size:2` nomeados), a célula só é ✅ quando cada valor VÁLIDO do campo decodifica com a assinatura
esperada; se só parte decodifica, a célula mostra o novo status `🟡` (parcial), contado como não coberto no
progresso.

## Inclui

- `DecodeTreeSpec`: expor, por instrução, o campo de tamanho e os valores válidos (para `%esz_hsd`: `type`
  ∈ {`00`, `01`, `11`}, `10` reservado; para `@rr_sd`/`%esz_sd`: `bit22`; campos `esz:2` nomeados: os 4 valores,
  menos os que o `.decode` fixa).
- `probeAarch64`: para essas linhas, sondar cada valor (mesmas `FILL_STRATEGIES` nos outros campos) e devolver
  ✅/🟡/❌; o valor que exige feature própria (H = `FEAT_FP16`) entra pelo mecanismo `require(...)` já usado
  por coluna — sem a feature na coluna, o valor H não é exigido.
- Legenda e totais de `docs/COBERTURA-ISA.md` com `🟡`; `IsaA64SignatureGuardTest`/assinaturas por valor de
  tamanho, se a E17 precisar.
- Regenerar a tabela e listar no **Resultado** cada linha que caiu para `🟡` — cada família nova vira task (regra
  do `tasks/README.md`).

## Não inclui

- Implementar as formas que faltam (B19.34 e as tasks que este levantamento criar).
- AArch32/T32 (mesma ideia vale lá; task própria se o levantamento A64 achar muita coisa).

## Aceite

- `FADD_s`/`FCMP`/`FCSEL`/`FMADD`/`FCVTZS_g`… caem para `🟡` nas colunas ARMv8.2+ enquanto a B19.34 não fechar
  (antes de 8.2, sem `FEAT_FP16`, continuam ✅).
- Todo `🟡` novo tem task (existente ou criada nesta task).
- `mvn -o -pl core verify` verde.

## Armadilhas

- `%esz_hsd` é `xor_2` sobre `type`: `00`→S, `01`→D, `11`→H, `10`→reservado (o `trans_` recusa) — reservado
  NÃO é exigido.
- Linhas que já fixam o tamanho no padrão (`FCVT_s_hs`, `FMOV_xh`) não têm campo livre — não mudam.
- O preset "máximo" das células SVE (`probeScalableApplicability`) passa por `probeAarch64` com `column=null`.
