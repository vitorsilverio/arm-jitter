# E22 — Conferir os CAMPOS decodificados (`Rd`/`Rn`/`Rm`/índice/imediato) contra o `objdump`, no espaço A64 inteiro

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** E15.15g ✅ (achado) · **G5:** não (só ferramenta e testes)
**Status:** ⬜ — spec escrita em 2026-10-07 (achado da E15.15g, registrado como task pela regra 9 do `tasks/README.md`).

## Contexto

A E17 fez a célula A64 conferir O QUE decodifica (record + enum de operação), e os oráculos das E15.9c–E15.15 conferem
O QUE é aceito (`objdump` em toda palavra aceita e recusada). Nenhum dos dois olha os CAMPOS do record. A E15.15g rodou
um conferidor de campos (`e15.15g-scripts/field_check.py`: junta o record do oráculo com o texto do `objdump` e compara
`rd`/`rn`/`rm`/`index`) só no "× indexed element" e achou QUATRO misdecodes de campo que estavam no código desde as
tasks que criaram as formas, todos com o `M` (bit alto de `Rm`) descartado:

| Forma | Erro | Palavras da amostra | Desde |
|---|---|---|---|
| `BFDOT_vi` | `Rm` de 4 bits e índice só `L` (era `H:L`) | 768 de 1 024 | B19.7 |
| `USDOT_vi` / `SUDOT_vi` | `Rm` de 4 bits | 512 + 512 de 2 048 | B19.12 |
| `FCMLA_vi` `.h` | `Rm` de 4 bits | 1 535 de 4 095 | B19.20 |

Os javadocs dessas tasks diziam "medido contra corpus real" — o corpus só tinha `Rm<16` e `H=0`. Corrigidos na
E15.15g; o resto do espaço A64 nunca passou por essa conferência.

## Objetivo

Um conferidor de campos permanente: para cada palavra aceita de uma amostra do espaço A64 inteiro, os registradores,
índices e imediatos do record batem com o texto do `objdump`.

## Inclui

- Generalizar `field_check.py` (ou reescrever em Java, no padrão dos oráculos) para os operandos que o `objdump` imprime:
  registradores (`x`/`w`/`v`/`z`/`p`/`za`, com `sp`/`zr`), elemento `vN.T[i]`, imediatos `#n` (com deslocamento),
  rótulos de desvio (endereço absoluto), registrador de sistema. Mapa record → campos por convenção de nome
  (`rd`/`rn`/`rm`/`ra`/`rt`/`rt2`/`index`/`imm…`); record cujo campo não tem par no texto entra numa lista explícita.
- Rodar sobre as amostras dos oráculos existentes (E15.9c–E15.15g, E15.10–E15.14) e sobre SVE/SME; cada divergência
  vira correção (se for o decoder) ou entrada da lista de exceções (se for convenção de impressão, ex.: alias).
- Guarda CI-safe no mesmo espírito do `IsaA64SignatureGuardTest`: por linha de `docs/isa-a64-assinaturas.tsv`, uma
  palavra com registradores ALTOS (`≥16`, `M=1`, `H=1`) e campos conferidos — o que faltou no corpus das B19.7/B19.12/
  B19.20 (ver também a [E20](e20-cobertura-isa-sonda-registradores-altos.md), que é a mesma lacuna do lado do medidor).

## Não inclui

- Semântica de execução (o conferidor é de decode).

## Aceite

- Conferidor roda no espaço A64 inteiro com 0 divergência fora da lista de exceções (cada exceção com motivo).
- Todo misdecode achado corrigido com teste de campo (palavra do `objdump`) ou com task própria criada na hora.
- `mvn -o -pl core verify` verde.

## Armadilhas

- `-M no-aliases` no `objdump`, senão `MOV`/`CMP`/`LSL` reescrevem operandos.
- Escalar AdvSIMD imprime `s0`/`d1`/`h2` (prefixo de tamanho, não `v`); SVE imprime `z0.s`; registrador 31 é `sp`
  ou `zr`/`wzr` conforme a forma.
- Linhas do `objdump-oraculo.sh` são `palavra record-sem-campos mnemônico operandos` — o record completo está só na
  saída do oráculo (juntar pela palavra, como o `field_check.py`).
