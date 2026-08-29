# B16 — MVE / Helium (M-profile Vector Extension, ARMv8.1-M): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano
**Depende do épico:** `b15-plano-armv8m.md` (ARMv8.1-M não existe como preset hoje)

Um dos 7 grupos "não se aplica a nenhum preset atual" — o maior do lado 32 bits.

## O número (medido, `target/isa-decode/mve.decode`)

**352 encodings.** Famílias medidas no próprio inventário:

| Família | Encodings |
|---|---:|
| Load/store não-alargantes (`VLDR`/`VSTR` vetoriais, P/W) | 17 |
| Load/store alargantes/estreitantes (`VLDSTB_H` e parentes) | 6 |
| Vector 2-op + `VSHLL` T2 (o maior bloco único) | 92 |
| Comparações (condições expandidas) | 45 |
| Operações escalares (vetor × registrador geral) | 39 |
| Deslocamentos por imediato + shift-and-insert + `VMOVL` | 38 |
| Estreitamento (`b`/`h` só) | 33 |
| Load/store com base em `Rn[3:1]` (gather/scatter) | 34 |
| Conversões `VCVT` (int↔fp, meia↔simples, com modo de arredondamento) | 26 |
| Misc / `VADDV` / movimentos entre lanes e registradores gerais / imediato modificado | 22 |

## Por que é diferente de NEON (B13) e de SVE (B17)

MVE tem **8 registradores Q de 128 bits** (`Q0-Q7`, aliasando o banco FP do perfil M) e, o traço
que define a extensão, **predicação por `VPR`** com *beat-wise execution*: uma instrução vetorial
é executada em 4 "beats", e o `VPR` (Vector Predication Register) mascara lanes por beat. Os blocos
`VPT`/`VPST` são o análogo vetorial do bloco `IT` do Thumb-2 (que a B2.4 já implementou — mesmo
tipo de estado de decode que sobrevive entre instruções, mesma classe de risco).

Isso significa que MVE **não é** "NEON num Cortex-M": compartilha mnemônicos e semântica de lane
com NEON/AdvSIMD, mas o modelo de predicação e o banco de registradores são próprios. A decisão
de reuso da RFC **B13.2** (núcleo vetorial compartilhado vs espelhado) vale aqui e deve ser
respeitada — se B13.2 escolher extrair o núcleo, MVE é o terceiro cliente dele.

## Escada

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B16.1** | Fundação: banco `Q0-Q7` de 128 bits para perfil M (reaproveitando o banco estendido de B13.1) + `VPR` (`ArmFeature.MVE_INTEGER` novo) + preset `ARMV8_1M_MVE`. SEM decode | 0 | B15.6, B13.1 |
| **B16.2** | Predicação: blocos `VPT`/`VPST`, atualização de `VPR` por beat, e o efeito de máscara no executor — o coração da extensão, espelho conceitual do bloco `IT` (B2.4) | ~8 | B16.1 |
| **B16.3** | Load/store contíguo: `VLDR`/`VSTR` vetoriais não-alargantes | 17 | B16.2 |
| **B16.4** | Load/store alargante/estreitante (`VLDSTB_H` e família) | 6 | B16.3 |
| **B16.5** | Load/store com base em `Rn[3:1]` — gather/scatter | 34 | B16.3 |
| **B16.6** | Vector 2-op inteiro: aritmética/lógica/saturação (parte do bloco de 92) | ~45 | B16.2 |
| **B16.7** | Vector 2-op ponto flutuante + `VSHLL` T2 (resto do bloco de 92) | ~47 | B16.6 |
| **B16.8** | Comparações (`VCMP` com as condições expandidas) — alimentam `VPR`, portanto dependem de B16.2 | 45 | B16.2 |
| **B16.9** | Operações escalares: vetor × registrador geral (`VADD`/`VMUL`/... com `Rm` escalar) | 39 | B16.6 |
| **B16.10** | Deslocamentos por imediato + shift-and-insert + `VMOVL` (`VSHLL` com contagem zero) | 38 | B16.6 |
| **B16.11** | Estreitamento (`b`/`h` só) | 33 | B16.10 |
| **B16.12** | Conversões `VCVT` (int↔fp, `hp`↔`sp`, modo de arredondamento explícito) | 26 | B16.7 |
| **B16.13** | Misc: `VADDV`/redução, movimentos entre 2 lanes e 2 registradores gerais, imediato modificado (`Vimm_1r` do lado MVE) | 22 | B16.6 |
| **B16.14** | **Fechamento**: `IsaCoverageReport` troca `NOT_IN_ANY_PRESET` do grupo `mve.decode` por um `Applicability` de `MVE_INTEGER`; entradas de `ArmProcessor` para `Cortex-M52`/`M55`/`M85` (que têm Helium) | 0 | B16.13 |

## Meta

O grupo `MVE (Helium) — ARMv8.1-M` sai de "não se aplica a nenhum preset atual" e passa a medir
352 células contra `ARMV8_1M_MVE`.

## Armadilhas

- **Beat-wise execution não é detalhe de implementação**: uma exceção pode chegar NO MEIO de uma
  instrução vetorial MVE, e a arquitetura define retomada por beat (`ECI` no `EPSR`). Ignorar isso
  funciona até o primeiro firmware com interrupção durante laço vetorial — é o mesmo tipo de
  armadilha que o G4 do projeto já registra para ciclo/fetch.
- **`VPT` altera o significado das instruções seguintes**, como o `IT`. O `ThumbDecoder` já tem a
  máquina de estado de `IT` (B2.4) — reusar o padrão, não inventar outro.
- `Q0-Q7` do MVE **aliasam** o banco FP (`S`/`D`) do perfil M: escrever `Q0` altera `S0-S3`. Se
  B13.1 modelar o banco como um array de `D` com vistas, isso sai de graça; se modelar como bancos
  separados, MVE quebra silenciosamente.
- O inventário conta 352 **encodings**, não mnemônicos — o bloco de "comparações" tem 45 linhas
  porque as condições foram expandidas no decodetree. O trabalho real de semântica é bem menor que
  o número sugere (mesma lição que B8.11b já registrou: "o medidor conta células de decode-tree,
  não mnemônicos").
