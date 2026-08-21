# E5 — tabela de cobertura de ISA gerada por medição

**Trilha:** E (manutenção) · **Repo:** arm-jitter · **Status:** ✅ **FECHADA 2026-08-21**

## O problema que resolve

Toda instrução ARM faltante vinha sendo descoberta do jeito caro: um guest travava, alguém
investigava por horas, e no fim era *"de novo a CPU não suporta X"*. Só nas últimas semanas:
**B6.8** (`CCMP`/`CCMN`), **B6.9** (`Logical shifted register`), **B6.10** (`CTR_EL0`/`DCZID_EL0`),
**B6.11** (`LSLV`/`LSRV`/`ASRV`/`RORV`), **B6.12** (`IC`/`DC`), **B3.9** (`VNMLA`/`VNMLS`) — seis
tasks, uma instrução por vez, cada uma precedida de uma investigação.

Pedido do usuário: uma tabela de todas as instruções por arquitetura, com status, **sem presumir
que alguma nunca será usada** — o precedente citado foi o EL1/EL2, descartado como desnecessário e
depois exigido por inteiro pelo `virtual-arm-box`.

## Como funciona

Não é uma tabela escrita à mão (que envelheceria em uma semana): é **medida a cada execução**.

1. **Inventário**: os arquivos `decodetree` do QEMU (`target/arm/tcg/*.decode`) — a32, t16, t32,
   vfp, vfp-uncond, neon-dp/ls/shared, m-nocp, mve, a64, sve, sme. `DecodeTreeSpec` os lê e extrai,
   por instrução, o mnemônico e **quais bits do encoding são fixos** (resolvendo as referências a
   `@formato`).
2. **Encoding representativo**: os bits fixos, mais os campos preenchidos com valores plausíveis
   (registradores baixos e distintos para não cair em `r15`, `cond`=`AL`). Quatro estratégias de
   preenchimento são tentadas — um valor específico pode cair num `UNPREDICTABLE` que o decoder
   rejeita com razão; basta UMA decodificar.
3. **Sondagem**: esse encoding vai para o decoder REAL (`ArmDecoder`/`ThumbDecoder`/
   `Aarch64Decoder`), uma vez por preset de `ArmArchitecture`.

Três estados por célula: ✅ decodifica · ❌ `UNIMPLEMENTED` · ⚠️ **decodifica como outra coisa**.

Regenerar: `./gerar-cobertura-isa.sh`.

### Licença

Os `.decode` do QEMU são **GPL**; este repositório é **BSD-3-Clause**. Eles **não são versionados
aqui** — o script os baixa para `target/isa-decode/` (ignorado pelo git). O que fica versionado é só
a tabela gerada: mnemônicos e status, fatos do manual da ARM.

## Dois falsos positivos que a própria ferramenta teve que aprender a evitar

A primeira versão media "o decoder devolveu algo diferente de `UNIMPLEMENTED`" e produziu números
absurdos (NEON 97%, MVE 100% em ARMv5TE). Ambos vinham do mesmo erro conceitual: **decodificar
alguma coisa não é decodificar AQUELA coisa.**

1. **Thumb-2 lido como Thumb-1**: sem Thumb-2, o `ThumbDecoder` consome só a primeira *halfword* e
   devolve uma instrução de 16 bits perfeitamente válida. Corrigido exigindo
   `decoded.raw() == word`.
2. **Espaço incondicional lido como condicional**: ver abaixo — virou um achado por si só.
   Corrigido comparando o decode do encoding original com o do mesmo encoding com o nibble de
   condição trocado por `AL`: se dá o mesmo `kind`, o decoder ignorou o campo.

## 🔴 Achado real na primeira execução: o espaço incondicional é mal decodificado

`ArmDecoder` decodifica `0xF2000000` (um `VHADD` de NEON) como **`AND` com condição `AL`**.

`cond == 0b1111` não é "condição 1111": desde o ARMv5 é o **espaço de instruções incondicionais**
(NEON, `PLD`, `BLX` imediato, `CPS`, `RFE`, `SRS`, `SETEND`, barreiras). O `ArmDecoder` trata alguns
casos explicitamente (`BLX` imediato, `CPS`, `CLREX`, `PLD`, `DMB`/`DSB`/`ISB`), mas **não rejeita o
resto**: o encoding cai no caminho condicional comum e vira uma instrução completamente diferente.

Isso é pior do que "não suportado" — é **execução silenciosa de outra instrução** onde o hardware
real levantaria uma exceção de instrução indefinida. Exatamente a classe de bug que custa dias: um
guest que use qualquer coisa de NEON não trava com diagnóstico, ele corrompe registradores em
silêncio. E a B3.9 mostrou (com `pc=0x4`) que a exceção de instrução indefinida é justamente o sinal
que permite achar o problema em minutos.

**Candidato a task própria (`E6`?)**: fazer o `ArmDecoder` reconhecer `cond==0b1111` como espaço
próprio e devolver `UNIMPLEMENTED` para tudo que não esteja explicitamente tratado ali.

## Panorama medido (2026-08-21)

| Grupo | Instruções | Destaque |
|---|---:|---|
| A32 | 266 | 57% v4T · 63% v5TE · 86% v6K/MPCore · **89% v7-A** |
| T16 | 86 | 69% v4T · 81% v7-A · 82% v7-M |
| T32 (Thumb-2) | 310 | 0% até v6K (correto: sem Thumb-2) · **75% v7-A** · 45% v7-M |
| VFP | 101 | 0% até v6K · **44% MPCore/v7-A** |
| VFP incondicional (ARMv8) | 17 | 0% |
| NEON (dp+ls+shared) | 325 | **0%** — e hoje mal decodificado, ver achado acima |
| MVE (Helium) | 352 | 0% |
| A64 | 1161 | **18%** |
| SVE/SVE2 | 929 | 0% |
| SME | 623 | 0% |

Validação cruzada contra verdades conhecidas: `VNMLS_sp`/`_dp` aparecem ✅ em MPCore/v7-A (B3.9, de
hoje) e `_hp` ❌ (meia precisão nunca implementada); `VMLA_sp`/`_dp` ✅ (B3.4); `LSLV`/`ASRV`/`CCMP`
✅ em A64 (B6.8/B6.11). A tabela concorda com o histórico das tasks.

## Limite honesto

Mede **decode**, não semântica. `STREX` (E3) e `LDR/STR` alinhado (F3) decodificavam e estavam
errados. O valor é eliminar "não suporta" da lista de suspeitos e permitir planejar por varredura —
não provar correção.

## Como usar no dia a dia

Antes de abrir uma investigação de "guest travou": procurar na tabela a instrução onde parou
(técnica da B3.9: `pc`/`sp` na parada + desmontagem em `lr-4`). E, para planejar, filtrar a coluna
da arquitetura-alvo por ❌ — a lista de trabalho sai pronta, sem adivinhação.
