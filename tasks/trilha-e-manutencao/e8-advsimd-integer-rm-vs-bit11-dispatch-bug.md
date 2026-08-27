# E8 — `decodeAdvancedSimdInteger`: dispatch usava `Rm` em vez de bit11, colidindo com "three different"

**Trilha:** E (manutenção) · **Depende de:** — · **Repo:** arm-jitter

Sem spec formal prévia — achada na `B8.11` (documentada como regressão de teste intencional, não
corrigida lá) e escolhida pelo usuário em 2026-08-26 entre 4 candidatas (esta vs. `B8.11b` vs.
AdvSIMD copy vs. `G6.2`/`G6.3` do n3dsemu).

## Contexto

A `B8.11` (`trilha-b-arquiteturas/b8.11-a64-extensoes-opcionais-triagem.md`, seção "Achado de bug
PRÉ-EXISTENTE") documentou que `Aarch64Decoder#decodeAdvancedSimdInteger` usa "bit4 de `Rm` setado"
como heurística para desviar para "AdvSIMD across lanes" dentro do espaço de encoding compartilhado
por "two-register miscellaneous"/"across lanes"/"three different" (prefixo vetorial `01110`,
`bit21=1`, `bit10=0`). Essa heurística é válida SÓ para as formas onde `Rm` não é um registrador de
verdade (é um opcode disfarçado), mas ERRADA para "three different" (`SMULL`/`UMULL`/`SQDMULL`/
`PMULL`/...), onde `Rm` É um registrador livre `0`-`31` — herdado de B8.7/B8.8, nunca corrigido até
agora.

## Achado real desta sessão: o bug é mais amplo do que a B8.11 documentou

A B8.11 só citou o caso `Rm>=16` (o bit4 setado). Investigação com corpus real (devkitA64,
`aarch64-none-elf-as`/`objdump`) mostrou que a colisão acontece para QUALQUER valor de `Rm` que bata
com um padrão fixo conhecido, não só `Rm>=16`:

- `smull v0.8h,v1.8b,v0.8b` (`Rm=0b00000`) colide com o `Rm` fixo de `ABS`/two-register-misc.
- `smull v0.8h,v1.8b,v1.8b` (`Rm=0b00001`) colide com o `Rm` fixo de `SQXTN`/narrow-unário.
- `smull v0.8h,v1.8b,v16.8b` (`Rm=0b10000`) colide com o `Rm` de `SADDLV`/across-lanes.
- `smull v0.8h,v1.8b,v17.8b` (`Rm=0b10001`) colide com o `Rm` de `ADDV`/across-lanes.

O discriminador REAL não é o valor de `Rm` — é o **bit11 do word** (LSB do campo `opcode` de 5 bits
já lido em `decodeAdvancedSimdInteger`, bits[15:11]). No encoding real (`a64.decode`/manual ARM), o
campo opcode de "three different" tem só 4 bits (bits[15:12]), com bit11 sempre fixo em `0`; todas as
formas que reaproveitam `Rm` como opcode disfarçado (two-reg-misc/narrow-unário/across-lanes/AES/
`ADDP_s` escalar) sempre têm bit11=`1`. Confirmado bit a bit contra o corpus real: `smull` com
qualquer `Rm` tem sempre bit11=0; `addv`/`saddlv`/`abs`/`sqabs`/`aese` têm sempre bit11=1.

## Inclui

- `Aarch64Decoder#decodeAdvancedSimdInteger`: dispatch reordenado para checar `(opcode & 1)` (bit11)
  ANTES de qualquer checagem de `Rm`. `bit11=0` → vai direto para "three different" (Rm sempre
  livre, com a exceção já existente de `PMULL_p64`/`esz=3`). `bit11=1` → segue o dispatch por `Rm`
  de antes (AES/two-reg-misc/narrow-unário/across-lanes), mas agora com um `throw unsupported` final
  explícito para `Rm` que não bate com NENHUM padrão conhecido (antes essa combinação — bit11=1 com
  `Rm` fora dos padrões — não tinha um caminho de rejeição dedicado; G8).
- `ADVSIMD_INT_RM_ACROSS_LANES_BIT` (checagem de bit solto, `rm & 0b10000 != 0`, que aceitava
  `Rm=16..31` inteiro) substituída por `ADVSIMD_INT_RM_ACROSS_LANES_MASK`/`_PATTERN`
  (`(rm & 0b11110) == 0b10000`, só `Rm=16`/`17`) — mais estreita e exata, elimina uma segunda fonte
  de ambiguidade (antes, `Rm=24..31` também entrava incorretamente no branch de across-lanes).
- Testes de regressão novos: `Aarch64AdvSimdIntegerDecoderTest#smullRmCollidingWithFixedOpcodePatternsDecodesAsThreeDifferent`
  (6 casos: `Rm=0/1/16/17/24/30`, corpus real) e `Aarch64CryptoDecoderTest#pmullP64WithHighRmNowDecodesCorrectly`
  (substituiu o teste da B8.11 que documentava o bug como `assertThrows` — agora decodifica certo).

## Não inclui

- Nenhuma mudança de escopo de instrução (nenhum `Kind`/`Ir64Op` novo) — só correção de decode.
- `B8.11b` (SHA1/SHA256), AdvSIMD copy (`DUP`/`INS`/`SMOV`/`UMOV`) — candidatas independentes, não
  tocadas aqui.

## Validação

`mvn -o test` verde (core + truffle, suíte completa) + `mvn -o install`. G5 completo nos 5
consumidores: gbaemu ✅, ndsemu ✅, n3dsemu ✅, virtual-arm-box ✅, armbox ✅ (roda de verdade,
sessão de GDB observada nos dois backends 32/64-bit).

Sem mudança em `docs/COBERTURA-ISA.md` (o encoding já contava como ✅ para o medidor — este era um
bug de CORREÇÃO de decode, não de cobertura; nenhuma nova entrada na tabela). Sem marco de release
por cobertura; publicação não avaliada nesta sessão por não haver mudança de superfície.

## Armadilhas

- O `opcode` de 5 bits já extraído em `decodeAdvancedSimdInteger` (`bits[15:11]`) tem bit11 como seu
  bit menos significativo — `(opcode & 1)` é exatamente bit11 do word, não precisa reler o word.
- A checagem antiga por `Rm` sobrevive DENTRO do branch `bit11=1` (não foi removida) — ela ainda é
  necessária para diferenciar two-reg-misc/narrow-unário/AES/across-lanes entre si; só deixou de ser
  usada para decidir "é three-different ou não".
