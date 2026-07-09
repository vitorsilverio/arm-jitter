# C2 — Carry-out do shifter nativo em ops lógicas com S

**Trilha:** C · **Depende de:** — (sinergia com C1: se C1 já mergeou, escrever nos locals)
**Repo:** arm-jitter

## Contexto

Ops lógicas com `S` (ANDS/ORRS/EORS/BICS/MOVS/MVNS/TSTS/TEQS) setam C = **carry-out do
shifter** (não da ALU). Hoje partes desse caminho (shift por registrador + setFlags)
ainda caem em helper/fallback (`AsmNativePolicy` rejeita shift+setFlags — conferir o
estado atual no código antes de começar). Objetivo: emitir nativamente.

## Especificação — tabela normativa do carry-out do shifter

`n` = quantidade de shift. Para shift por REGISTRADOR usa-se só o byte baixo do Rs
(`n = Rs & 0xFF`); para shift IMEDIATO os casos `n==0` têm significado especial.

| Shift | n==0 (imediato) | 1..31 | n==32 | n>32 |
|-------|-----------------|-------|-------|------|
| LSL | C inalterado, res=Rm | C=bit(32−n), res=Rm<<n | C=bit0(Rm), res=0 | C=0, res=0 |
| LSR | (encode imediato n=0 significa LSR#32) | C=bit(n−1), res=Rm>>>n | C=bit31(Rm), res=0 | C=0, res=0 |
| ASR | (encode imediato n=0 significa ASR#32) | C=bit(n−1), res=Rm>>n | C=bit31, res=Rm>>31 | igual n==32 |
| ROR | (encode imediato n=0 significa RRX: res=(C<<31)\|(Rm>>>1), C=bit0(Rm)) | C=bit(n−1... = bit((n−1)&31)), res=rotr(Rm,n) | C=bit31, res=Rm | reduz: n&31; se n&31==0 → C=bit31, res=Rm |
| Reg shift n==0 | C inalterado, res=Rm (vale para TODOS os tipos com Rs&0xFF==0) | | | |

Imediato rotacionado (operand2 imediato): se rotate!=0, C = bit31 do imediato
rotacionado; se rotate==0, C inalterado.

**A fonte de verdade é o interpretador** (`codegen/executor/IrExecutionSupport` /
`IrAluExecutor` — localizar onde o carry do shifter é calculado hoje). Se a tabela
acima divergir do interpretador, **o interpretador ganha** — investigue antes de
emitir (pode ser bug da tabela OU bug real; reporte ao usuário).

## Implementação

1. Preferir o padrão helper (B1.6/ARMv5TE): extrair o cálculo `shifterOperand +
   carryOut` do executor para um helper estático que retorna os dois (empacotar em
   `long`: res nos 32 baixos, carry no bit 32) e emitir `INVOKESTATIC` + desempacote.
   Emissão de bytecode manual só se o profiling mostrar que o helper não inlina.
2. Relaxar `AsmNativePolicy` (shift+setFlags e o que mais estiver rejeitado por isso)
   SOMENTE após o caso de emissão existir.
3. N e Z das lógicas S = do resultado; V inalterado — conferir com o executor.

## Validação

1. Teste exaustivo NOVO: para cada tipo de shift × op lógica S, varrer n = 0..255 via
   registrador (property test comparando emissor ASM vs interpretado no harness) e os
   imediatos especiais (n=0 de cada tipo).
2. `mvn test` + gbaemu/ndsemu + divergence-check FireRed/JUS (usuário roda).
3. Bench + contadores: `perOpFallbackOpCount`/`fallbackBlockCount` devem cair;
   publicar antes/depois no PR.

## Armadilhas

- LSR#0 e ASR#0 imediatos NÃO são "sem shift" — são #32. Erro clássico.
- Shift por registrador consome 1 ciclo interno extra — NÃO alterar contagem de ciclos
  nesta task (só flags); o custo já está modelado no IR.
- MOVS/MVNS pc,... com S é retorno de exceção — já tratado em outro caminho; não tocar.
