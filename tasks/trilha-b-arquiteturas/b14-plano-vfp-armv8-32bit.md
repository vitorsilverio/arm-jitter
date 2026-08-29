# B14 — VFP incondicional (ARMv8-A de 32 bits): épico curto

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Um dos 7 grupos que `docs/COBERTURA-ISA.md` marca "não se aplica a nenhum preset atual" — ver
`b13-plano-neon-a32.md` para a discussão de por que isso nunca foi decisão de escopo. Este é o
**menor** deles (17 encodings) e o mais barato de fechar: é ponto flutuante ESCALAR, cuja
infraestrutura (`VfpRegisters`, `IrOp`s de FP, `VfpDecoder`, executor) já está pronta desde B3.3-B3.6
— falta o espaço de encoding incondicional e um preset ARMv8-A de 32 bits.

## O número (medido, `target/isa-decode/vfp-uncond.decode`)

17 encodings, todos no espaço `1111 1110 ...` (incondicional — `cond=0b1111`), em 4 famílias:

| Família | Encodings | Feature real |
|---|---:|---|
| `VSEL` (`sp`/`dp`/`hp`, condição em `cc:2`) | 3 | ARMv8-A (FPARMv8) |
| `VMAXNM`/`VMINNM` (`sp`/`dp`/`hp`) | 6 | ARMv8-A (FPARMv8) — máx/mín com semântica IEEE-754 de NaN |
| `VRINT{A,N,P,M}` + `VCVT{A,N,P,M}` (`rm:2` escolhe o modo, `sp`/`dp`/`hp`) | 6 | ARMv8-A (FPARMv8) |
| `VMOVX`/`VINS` (metade alta de um `S` como meia precisão) | 2 | `FEAT_FP16` (ARMv8.2-A) |

## Por que ainda não está feito

1. **Não existe preset ARMv8-A de 32 bits.** `ArmArchitecture` vai até `ARMV7A`; `ARMv8-A`
   aparece hoje só como motivo de EXCLUSÃO em `docs/isa-nao-aplicavel.tsv` (as ~14 linhas
   `LDA`/`STL`/`LDAEX`/`STLEX`/`CRC32*` dizem "ARMv8-A; não existe em ARMv4T..ARMv7-A"). Sem
   preset, o `IsaCoverageReport` só sabe dizer `NOT_IN_ANY_PRESET`.
2. **B12.6 já bateu nisso**: `Cortex-A32` ficou FORA do catálogo de processadores porque
   `ARMv8-A` torna `LDA`/`STL` obrigatórios e nenhum tem decoder — "mapear para `ARMV7A` seria
   entrada factualmente errada". Este épico e o B12 se destravam mutuamente.
3. O espaço incondicional de 32 bits é historicamente o ponto fraco do `ArmDecoder` (achado E6:
   `0xF2000000` decodificando como `AND`) — mexer aqui exige o cuidado de G8 que a B13 também vai
   precisar.

## Escada

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B14.1** | `ArmFeature.ARMV8_FP` (FPARMv8) + `ArmFeature.LOAD_ACQUIRE_STORE_RELEASE` + `ArmFeature.CRC32` novos e preset `ARMV8A_32` (`extending(ARMV7A, ...)`) — SEM decode novo, só a estrutura e o gate, espelho de B11.1/B1.1 | 0 | — |
| **B14.2** | `LDA`/`LDAB`/`LDAH`/`LDAEX*`/`STL`/`STLB`/`STLH`/`STLEX*` (A32 + T32) — as ~14 linhas hoje excluídas do tsv como "ARMv8-A"; **remove essas exclusões** e destrava `Cortex-A32` (B12.6) | ~14 (em `a32`/`t32`) | B14.1 |
| **B14.3** | `CRC32`/`CRC32C` (A32 + T32) — idem, sai do tsv | ~8 | B14.1 |
| **B14.4** | `VSEL` + `VMAXNM`/`VMINNM` (`sp`/`dp`; formas `_hp` ficam para B14.6) — decode do espaço incondicional `1111 1110`, com fechamento G8 do que sobra | 6 | B14.1 |
| **B14.5** | `VRINT{A,N,P,M}` + `VCVT{A,N,P,M}` (`sp`/`dp`) — semântica de arredondamento já existe desde B3.8 (`FPSCR.RMode` de verdade); aqui o modo vem do campo `rm:2` da instrução, não do FPSCR | 4 | B14.4 |
| **B14.6** | `FEAT_FP16` de 32 bits: `ArmFeature.FP16_ARITHMETIC` novo, `VMOVX`/`VINS` + as formas `_hp` de B14.4/B14.5 — **também fecha as ~10 células `_hp` hoje excluídas** de `vfp.decode` (`VADD_hp`/`VMUL_hp`/`VCMP_hp`/...) | 7 (+~10) | B14.5 |
| **B14.7** | **Fechamento**: `IsaCoverageReport` troca `NOT_IN_ANY_PRESET` por um `Applicability` de `ARMV8_FP` no grupo `vfp-uncond.decode`; entradas de `ArmProcessor` que dependiam de ARMv8-A 32 bits (`Cortex-A32`, o lado 32-bit dos Cortex-A53/A57/A72…) | 0 | B14.6, B14.3 |

## Meta

O grupo `VFP — formas incondicionais (ARMv8-A)` sai de "não se aplica a nenhum preset atual" e
passa a medir 17/17 contra `ARMV8A_32`. De quebra, ~22 exclusões de `isa-nao-aplicavel.tsv`
(`LDA`/`STL`/`CRC32`) deixam de ser exclusão e viram cobertura real — que é exatamente o que a
regra máxima do `tasks/README.md` prevê ("quando alguma trilha chegar perto de 'tudo ✅' numa
arquitetura, o próximo degrau natural é a versão SEGUINTE do ARM").

## Armadilhas

- `VMAXNM`/`VMINNM` **não são** `VMAX`/`VMIN` com outro nome: a diferença está no tratamento de
  NaN (retornam o operando numérico quando o outro é NaN). Implementar com `Math.max` produz o
  resultado errado para NaN e para `+0.0`/`-0.0` — mesma armadilha que o A64 teve em B8.4
  (`FMAXNM`).
- `VSEL` usa `cc:2` (2 bits), não o nibble de condição de 4 bits: os 4 valores são `EQ`/`VS`/`GE`/
  `GT` (ARM DDI 0487, `VSEL`), **não** os 4 primeiros da tabela de condições.
- `VRINT` no espaço incondicional pega o modo de `rm:2` da própria instrução; `VRINTR`/`VRINTZ`/
  `VRINTX` (que estão em `vfp.decode`, condicionais, e continuam `❌`) pegam do `FPSCR`. São
  famílias diferentes com nome parecido — não unificar.
- O espaço `1111 1110` colide visualmente com `MCR`/`CDP` de coprocessador (mesmo `1110` nos bits
  27:24 quando `cond=1111`). O `IsaCoverageReport` já sinaliza esse tipo de erro como `⚠️`
  (`FALLBACK`) em vez de `✅` — usar isso como teste de que o fechamento G8 funcionou.
