## Resumo

`Ir64BlockExecutor#executeAlu` (forma **imediata** de `ADD`/`SUB` em AArch64,
`Ir64Op.Alu64`) resolve `Rd|SP` e `Rn|SP` checando **apenas a flag booleana**
(`dstIsStackPointer` / `src1IsStackPointer`), nunca o **índice** do registrador (`== 31`).

Qualquer `ADD`/`SUB` imediato com `Rd`/`Rn` diferentes de 31 grava/lê o **SP** em vez do
registrador certo.

## Como reproduzir

`add x4, x5, #0x123` — `Rd=4`, `Rn=5`, nenhum dos dois é 31 — grava o resultado em `SP` em vez
de `X4`. Confirmado com um teste descartável durante a execução da task B6.3.1 (2026-07-25).

## Comportamento esperado

O pseudocódigo do manual ARM é por índice:

```
if n == 31 then SP[] else X[n]
if d == 31 && !setflags then SP[] = result else X[d] = result
```

`readBaseRegister`/`writeBaseRegister` (load/store, **mesmo arquivo**) já fazem a checagem
certa, por índice. A forma imediata é a exceção.

## Por que os testes não pegaram

Os testes de B6.1 coincidiam com `SP` no valor default `0`, então escrever no lugar errado
não mudava a asserção.

## Nota

O código **novo** de B6.3.1 (`Ir64Op.AluExtendedRegister`) implementa a checagem correta, por
índice — não copiou esse padrão. A correção precisa incluir a revisão dos testes de B6.1 que
não pegaram o defeito.

## Referência

`arm-jitter/tasks/README.md`, seção "Pendências que EXIGEM sessão de modelo forte", item 7.

## Labels sugeridas

`bug`, `jit`
