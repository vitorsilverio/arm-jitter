# E10 — Aliasing `Rd`==`Rn`/`Rm` nas famílias AdvSIMD de LARGURA MISTA (A64)

**Trilha**: E (manutenção) · **Depende de**: — · **Origem**: achado registrado no `## Resultado` da
[B19.4](../trilha-b-arquiteturas/b19.4-a64-advsimd-fp-vetorial-convert.md) (Armadilha 4), que provou
o bug em `executeShiftWidenImmediate` e deixou explicitamente a correção para "task própria".

## Contexto

Toda instrução AdvSIMD de **largura mista** (alargando ou estreitando) lê elementos de um tamanho e
escreve elementos de outro. O pseudocódigo do ARM (DDI 0487) lê **todos** os operandos para variáveis
locais antes de escrever `V[d]` — logo `Rd == Rn` (ou `Rd == Rm`) é perfeitamente legal e produz o
resultado "como se" a fonte tivesse sido lida inteira antes.

Os executores interpretados do A64 em `executor64/Ir64VectorArithmeticExecutor` fazem
`ler lane i → escrever lane i` **no mesmo laço**. Como os lados têm larguras diferentes, uma escrita
cobre bytes que ainda serão lidos:

- **Alargando** com `q == 0` (`SSHLL`, `SMULL`, `SADDL`, …): escrever a lane larga `i` cobre as
  lanes estreitas `2i` e `2i+1` da fonte. Com `Rd == Rn`, a lane estreita `1` já está corrompida
  quando `i == 1`. (Na forma `2`/`q == 1` a fonte é a metade ALTA e a escrita nunca a alcança — ver
  `## Resultado`.)
- **Estreitando** com `q == 1` (`SHRN2`, `ADDHN2`, `XTN2`, …): `laneOffset = elements`, então a
  escrita da lane estreita `elements + i` cobre a lane larga `(elements + i) / 2`, que é
  **estritamente maior que `i`** para todo `i < elements` — ou seja, sempre uma lane ainda não lida.
  (Com `q == 0` o estreitamento é seguro em lugar: `i / 2 <= i`.)
- **`executeWide`** (`SADDW`/`UADDW`/`SSUBW`/`USUBW`) lê `Rn` largo na MESMA lane que escreve
  (seguro) mas lê `Rm` estreito: com `Rd == Rm` e `q == 0`, escrever a lane larga `i` cobre as
  estreitas `2i`/`2i+1`, ainda não lidas.

Nenhum teste cobre `Rd == Rn`/`Rd == Rm` em nenhuma dessas famílias — o bug é **real e latente**.
O precedente correto já existe no próprio projeto: `executeConvertPrecision` (B19.4),
`Ir64VectorArithmeticExecutor#executeUnary` (ramo `SADDLP`/`SADALP`), `executePermute` e
`advsimd/AdvSimdLanes#pairwise` **já** calculam num `long[]` antes de qualquer `setElement`.

## Objetivo

Dar às 7 funções de largura mista do A64 o mesmo padrão "computa tudo num `long[]`, depois escreve",
com teste de aliasing para cada família.

## Inclui

Em `executor64/Ir64VectorArithmeticExecutor`:

1. `executeShiftWidenImmediate` (`SSHLL`/`USHLL`/`SHLL`) — o achado original da B19.4.
2. `executeWidening` (`SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SADDL`/`UADDL`/`SSUBL`/
   `USUBL`/`SABAL`/`UABAL`/`SABDL`/`UABDL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL`) — aliasing com `Rn` **e**
   com `Rm`.
3. `executeWideningByElement` (formas `_vi`/`_si`) — aliasing com `Rn` (o elemento de `Rm` já é lido
   uma única vez fora do laço, logo não sofre).
4. `executeWide` (`SADDW`/`UADDW`/`SSUBW`/`USUBW`) — aliasing com `Rm`.
5. `executeNarrow` (`ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN`) — aliasing com `Rn`/`Rm` na forma `q == 1`.
6. `executeNarrowUnary` (`XTN`/`SQXTN`/`SQXTUN`/`UQXTN`) — aliasing com `Rn` na forma `q == 1`.
7. `executeShiftNarrowImmediate` (`SHRN`/`RSHRN`/`SQSHRN`/`UQSHRN`/`SQSHRUN`/`SQRSHRN`/`UQRSHRN`/
   `SQRSHRUN`) — aliasing com `Rn` na forma `q == 1`.

Testes novos em `Ir64VectorArithmeticExecutorTest` cobrindo `Rd == Rn` (e `Rd == Rm` onde se aplica),
um por família, **confirmados falhando antes do fix**.

## Não inclui

- Mudar semântica de qualquer op, saturação, arredondamento ou escrita destrutiva
  (`finishScalarAwareWrite`/`finishDestructiveWrite` ficam exatamente onde estão).
- `Ir64VectorFpArithmeticExecutor#executeConvertPrecision` — já corrigido pela B19.4.
- `advsimd/AdvSimdLanes` — já usa buffer (`threeSame` é de largura única, `pairwise` já bufferiza).
- Emissão nativa/ASM: nenhuma dessas famílias tem caso nativo (interpretado desde B8.4+); nenhum
  emissor referencia esses `Kind`s (verificado por `grep`).
- O lado de 32 bits (B13.7/B13.8 ainda não implementaram largura mista no `IrNeonExecutor`) — quando
  implementarem, o padrão já estará estabelecido aqui.

## Especificação

Padrão único, o mesmo de `executeConvertPrecision` (B19.4):

```java
long[] results = new long[elements];
for (int i = 0; i < elements; i++) {
    results[i] = /* ... leitura de Rn/Rm/Rd e cálculo, exatamente como hoje ... */;
}
for (int i = 0; i < elements; i++) {
    fp.setElement(op.rd(), <índice de hoje>, <esz de hoje>, truncate(results[i], <esz>));
}
```

Ler `Rd` para os acumuladores (`current`, em `SMLAL`/`SABAL`/`SQDMLAL`/`SUQADD`-likes) continua
DENTRO do primeiro laço: com o buffer, todas as leituras acontecem antes de qualquer escrita, que é
exatamente o que o pseudocódigo do ARM manda (e o que hoje, com `Rd == Rn`, também falha).

## Passos

1. Escrever os testes de aliasing primeiro; confirmar que falham (colar a saída no `## Resultado`).
2. Aplicar o buffer nas 7 funções, sem tocar em mais nada.
3. `mvn -o test` (JBR 25) + `mvn -o install`; G5 nos 5 consumidores.
4. `INDICE.md` da trilha E + `## Resultado` nesta task + commit `E10: ...` + `git push`.

## Aceite

- Os testes novos falham antes e passam depois.
- `Ir64VectorArithmeticExecutorTest` e as `Aarch64AdvSimd*DecoderTest` existentes passam **sem
  edição** (o comportamento sem aliasing não muda — é zero-diff funcional).
- `docs/COBERTURA-ISA.md` **não muda** (nenhum decode novo).
- `mvn -o test` verde no arm-jitter + suítes verdes nos 5 consumidores (G5).

## Armadilhas

1. **Estreitar com `q == 0` já era seguro** — o buffer não pode alterar o resultado desses casos.
   Mantenha `laneOffset`/`finishScalarAwareWrite` idênticos.
2. **`executeWide` lê `Rn` largo na mesma lane que escreve** — não é aliasing; só `Rm` precisa da
   proteção. Ainda assim o buffer cobre os dois de graça.
3. **Formas escalares** (`scalar == true`) têm 1 elemento: o buffer é inócuo, mas não mude
   `elements`/`laneOffset` para elas.
4. **Não trocar `truncate(...)` de lugar** — ele fica na escrita (ou no cálculo), desde que o valor
   gravado seja byte-a-byte o mesmo de hoje.

## Resultado

✅ **2026-09-02.** Bug confirmado em **6 das 7** funções auditadas e corrigido nas 7 (a sétima ganhou
o buffer por uniformidade, ver abaixo).

### Medição — os 7 testes novos falhando ANTES do fix

`Ir64VectorArithmeticExecutorTest` com os 8 testes de aliasing novos, antes de tocar no executor:

```
[ERROR] Tests run: 92, Failures: 7, Errors: 0, Skipped: 0
  ushllWithDestinationAliasingSource:1273           lane larga 1 ==> expected: <2> but was: <0>
  wideningWithDestinationAliasingEitherSource:1303  Rd==Rn: lane larga 1 ==> expected: <2> but was: <0>
  wideningByElementWithDestinationAliasingSource    lane larga 1 ==> expected: <4> but was: <0>
  wideWithDestinationAliasingNarrowSource:1345      lane larga 1 ==> expected: <258> but was: <257>
  narrowHighHalfWithDestinationAliasingSource:1365  byte alto 4 ==> expected: <5> but was: <2>
  narrowUnaryHighHalfWithDestinationAliasingSource  byte alto 4 ==> expected: <5> but was: <1>
  shiftNarrowHighHalfWithDestinationAliasingSource  byte alto 4 ==> expected: <5> but was: <32>
```

Depois do fix: `Tests run: 92, Failures: 0`.

### O 8º teste PASSOU antes do fix — e a spec precisou ser corrigida

`ushll2WithDestinationAliasingSource` (`USHLL2`, `q == 1`) **já passava**. A conta prova por quê: ao
alargar com `laneOffset = outputElements`, a escrita da lane larga `i` toca as lanes estreitas
`2i`/`2i+1`, e a leitura da iteração `i` é a lane estreita `outputElements + i`; como
`2i < outputElements + i` para todo `i < outputElements`, a escrita **nunca** alcança uma fonte não
lida. Ou seja: **alargar só é inseguro na forma sem `2` (`q == 0`)** — o inverso exato do
estreitamento, inseguro só na forma `2` (`q == 1`). O teste foi mantido como guarda de regressão.

Resumo da auditoria (a coluna "inseguro quando" é o que os testes provaram):

| Função | Aliasing | Inseguro quando |
|---|---|---|
| `executeShiftWidenImmediate` (`SSHLL`/`USHLL`/`SHLL`) | `Rd`==`Rn` | `q == 0` |
| `executeWidening` (`SMULL`…`SQDMLSL`) | `Rd`==`Rn` **e** `Rd`==`Rm` | `q == 0` |
| `executeWideningByElement` (`_vi`/`_si`) | `Rd`==`Rn` (o elemento de `Rm` é lido fora do laço) | `q == 0` |
| `executeWide` (`SADDW`/`UADDW`/`SSUBW`/`USUBW`) | `Rd`==`Rm` (o estreito; `Rd`==`Rn` sempre foi seguro) | `q == 0` |
| `executeNarrow` (`ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN`) | `Rd`==`Rn`/`Rm` | `q == 1` |
| `executeNarrowUnary` (`XTN`/`SQXTN`/`SQXTUN`/`UQXTN`) | `Rd`==`Rn` | `q == 1` |
| `executeShiftNarrowImmediate` (`SHRN`…`SQRSHRUN`) | `Rd`==`Rn` | `q == 1` |

### Fix

O padrão único de `executeConvertPrecision` (B19.4) nas 7: `long[] results` preenchido no laço de
leitura, escrito num segundo laço. Nada mais mudou — `laneOffset`, `finishScalarAwareWrite`/
`finishDestructiveWrite`, saturação e `truncate` produzem os mesmos bytes de antes. Nas famílias que
estreitam, `truncate(...)` migrou da chamada de `setElement` para o preenchimento do buffer (mesmo
valor, só antecipado). A leitura de `Rd` para os acumuladores (`current`, em `SMLAL`/`SABAL`/
`SQDMLAL`/…) continua no primeiro laço: com o buffer, TODA leitura acontece antes de TODA escrita —
que é exatamente o que o pseudocódigo do ARM manda, e que com `Rd == Rn` também estava errado antes.

### Validação

- `mvn -o test` (JBR 25): **2914** testes core + **18** truffle, verdes.
- `mvn -o install` OK.
- **G5 verde nos 5 consumidores**: gbaemu, ndsemu (183), armbox (47 — inclusive o `Armv7TortureTest`
  historicamente vermelho), virtual-arm-box, n3dsemu (221). Nenhuma regressão.
- `docs/COBERTURA-ISA.md` **não muda**: zero alteração de decode/gating nesta task (só executor).

### Herança

O achado da B19.4 fica **fechado**. Quando a **B13.8** trouxer estreitamento/alargamento NEON para o
lado de 32 bits (e quando alguma família de largura mista descer para `advsimd/AdvSimdLanes`), o
padrão obrigatório já está estabelecido aqui e no `AdvSimdLanes#pairwise`: **buffer antes de
escrever, sempre** — o custo é um `long[]` de no máximo 16 posições e elimina a classe inteira de
bug por construção, sem depender de provar caso a caso qual metade é segura.
