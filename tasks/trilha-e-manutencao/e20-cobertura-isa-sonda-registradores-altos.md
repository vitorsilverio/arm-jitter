# E20 — `IsaCoverageReport`: sondar registradores altos (16–31) antes de marcar a célula A64 ✅

**Trilha:** E · **Repo:** arm-jitter (só `core/src/test` + `docs/`; G5 não se aplica) · **Depende de:** E17 ✅
**Status:** ⬜ — spec escrita em 2026-10-06 pela E15.15a (achado, regra 9 do `tasks/README.md`).

## Contexto

A E15.15a achou que `EOR3`/`BCAX`/`SM3SS1` liam `Ra` com 4 bits e recusavam toda palavra com `Ra ≥ 16` (5 112
palavras da amostra; o `aarch64-none-elf-as` monta `eor3 v0.16b, v1.16b, v2.16b, v20.16b` = `0xce025020`), e
`docs/COBERTURA-ISA.md` media as três ✅ desde a B11.12/B19.10. Corrigido na própria E15.15a.

Causa no medidor: `IsaCoverageReport#FILL_STRATEGIES` preenche campos de registrador só com valores ≤ 8
(`{1, 2, 3, 4}`, `{0, …}`, `{2, 4, 6, 8}`), e a célula é ✅ se **qualquer** estratégia decodifica. Um decoder
que lê um campo de registrador com bits a menos, ou exige `0` no bit alto, passa sempre. É a mesma família do
achado da E18 (a célula prova "uma forma decodifica", não "todas"), só que no eixo dos registradores em vez do
tamanho de elemento.

## Objetivo

Para toda linha A64 já ✅ (ou ⚠️), uma sonda extra com cada campo de registrador do `.decode` no valor ALTO
(`31` e um valor `16`–`30`) — célula vira `🟡` (o status parcial da E18) se a palavra de registrador alto é
recusada ou decodifica com a assinatura errada, a menos que o registrador alto seja reservado de verdade
(lista explícita conferida no `objdump`, ex.: campos de 4 bits do QEMU como `rm:4` já não chegam a 16).

## Inclui

- `probeAarch64`: a sonda de registrador alto, depois do sucesso das estratégias atuais (não pode tirar ✅ de
  célula que só decodifica com registrador baixo por motivo legítimo sem passar pela lista de exceções).
- Lista de exceções com fonte (palavra + `objdump`) para cada registrador alto legitimamente reservado.
- Regenerar `docs/COBERTURA-ISA.md`; cada `🟡` novo vira task (regra 9).

## Não inclui

- AArch32/T32 (os campos são de 4 bits; `r13`/`r15` são `UNPREDICTABLE` por instrução — outra lógica).
- Corrigir o decoder (cada `🟡` achado é task própria).

## Aceite

- Com a correção da E15.15a revertida localmente (`Ra` de 4 bits), `EOR3`/`BCAX`/`SM3SS1` caem para `🟡`;
  com ela, voltam a ✅.
- `mvn -o -pl core verify` verde; tabela regenerada sem `🟡` sem task.

## Armadilhas

- `31` é `SP` ou `ZR` conforme a instrução — os dois são válidos no encoding; o que muda é a assinatura do
  record (a E17 compara classe + enum de operação, não o número do registrador, então não deve confundir).
- Pares de registradores (`Rt2`, `Rs` de `CASP` exige par par) e listas (`LD4`, `TBL` com 4 registradores que
  "dão a volta" em `V31`) têm regras próprias — conferir no `objdump` antes de pôr na lista de exceções.
- Depende do status `🟡` da E18; se a E20 for pega antes, criar o status aqui e a E18 reaproveita.
