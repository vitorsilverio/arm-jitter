# E6 — `ArmDecoder`: espaço incondicional (`cond==0b1111`) tem que virar `UNIMPLEMENTED`, não cair no dispatch condicional

**Depende de**: — (bug pré-existente no decoder base, achado da `E5` ao gerar `docs/COBERTURA-ISA.md`)

**Fecha**: invariante **G8** (`tasks/README.md`) — "instrução não implementada tem que ser
recusada, não silenciosamente confundida com outra". Referenciada como "Q1, primeiro da Onda 5"
em `tasks/FILA-EXECUCAO.md` e como achado "além da tabela" em
`trilha-b-arquiteturas/b7-plano-cobertura-isa.md`.

## Contexto

`ArmDecoder#decode` calcula `condition` a partir de `raw >>> 28`, mas boa parte dos blocos de
dispatch mais abaixo (`branch`, `BX`, `CLZ`, saturação, multiplicação DSP, aritmética paralela,
`PKH`/`SAT`/`USAD8`/`EXTEND`/`REV`, `MRS`/`MSR`, `SWP`, `MLS`/`UMAAL`/multiplicação, transferência
de halfword/`LDRD`/`STRD`, `SBFX`/`UBFX`/`BFI`/`RBIT`/`SDIV`/`UDIV`, `LDR`/`STR` imediato,
`LDM`/`STM`, `MOVW`/`MOVT`, ALU genérico) usam máscaras que **não olham `bits[31:28]`** — batem
para qualquer valor de `cond`, inclusive `0b1111`.

`cond==0b1111` não é "condição 1111": desde o ARMv5 é um **espaço de encoding à parte** (NEON,
`PLD`/`PLI`, `BLX` imediato, `CPS`, `SETEND`, `RFE`, `SRS`, barreiras `DMB`/`DSB`/`ISB`, `CLREX`),
com instruções e semântica **diferentes** das que os mesmos bits formariam sob uma condição real.
O decoder já reconhecia oito desses grupos com carve-outs explícitos espalhados pelo método
(`BLX` imediato, `SETEND`, `CPS`, `CLREX`, `PLD`/`PLDW`/`PLI`, `DMB`/`DSB`/`ISB`, `SRS`, `RFE`) —
mas, para qualquer encoding do espaço incondicional que NÃO batesse com um desses oito, a execução
simplesmente continuava para os blocos genéricos abaixo, que ignoram `cond` e decodificam como se
fosse uma instrução condicional normal.

A `E5` (tabela de cobertura de ISA) mediu o efeito concreto: `0xF2000000` — um `VHADD` de NEON, com
`cond=1111` — decodifica hoje como `AND` com condição `AL`. Isso é **pior** que "não suportado": o
hardware real levantaria uma exceção de instrução indefinida ali (o mesmo sinal que a `B3.9`
mostrou resolver bugs de decode em minutos); o `ArmDecoder`, em vez disso, executa silenciosamente
uma instrução completamente diferente.

## Objetivo

Fazer `ArmDecoder` reconhecer `cond==0b1111` como espaço próprio e devolver `UNIMPLEMENTED` para
todo encoding desse espaço que não seja um dos grupos explicitamente tratados — nunca cair no
dispatch condicional genérico.

## Inclui

1. Rotear `cond==0b1111` para um método dedicado (`decodeUnconditional`) logo no topo de `decode`,
   antes até do carve-out de `SWI` (que, sob `cond=1111`, também não é `SWI` de verdade no espaço
   incondicional real — mesma classe de bug).
2. Mover os oito carve-outs já existentes (`BLX` imediato, `SETEND`, `CPS`, `CLREX`,
   `PLD`/`PLDW`/`PLI`, `DMB`/`DSB`/`ISB`, `SRS`, `RFE`) para dentro desse método, sem mudar
   nenhuma condição de bits/feature-gate — só a localização.
3. No fim do método, tentar as extensões de arquitetura (`architecture.decoderExtensions()`) antes
   de devolver `UNIMPLEMENTED` — preserva o comportamento já correto de `CoprocessorDecoder`
   (`STC2`/`LDC2`/`CDP2`/`MCR2`/`MRC2`/`MCRR2`/`MRRC2`, que são instruções REAIS do espaço
   incondicional desde o ARMv5, com a mesma semântica das formas condicionais) e de `VfpDecoder`
   (nenhum dos dois olha `bits[31:28]`, então já funcionavam corretamente aqui antes desta task).
4. Teste de regressão: `0xF2000000` (o `VHADD` da `E5`) decodifica como `UNIMPLEMENTED`, nunca como
   `AND`.

## Não inclui (não fazer)

- Implementar NEON ou qualquer instrução nova do espaço incondicional — isso é trabalho da `B8`
  (AdvSIMD) e companhia; esta task só fecha o vazamento de decodificação incorreta.
- Mudar a tabela `docs/COBERTURA-ISA.md`: o script já compara o decode do encoding original com o
  mesmo encoding com o nibble de condição trocado por `AL` para detectar "campo `cond` ignorado" —
  um `VHADD` sem implementação NEON já contava como ❌ nos dois casos (antes: "decodifica como outra
  coisa" via essa comparação; depois: `UNIMPLEMENTED` direto). Rodar `./gerar-cobertura-isa.sh`
  para confirmar (não regenerar por regenerar — só como evidência de que os números não mudam).

## Passos

1. Ler `core/src/main/java/dev/vitorsilverio/armjitter/decoder/ArmDecoder.java` inteiro antes de
   editar — os oito carve-outs citados usam nomes de máscara/constantes já definidos no topo da
   classe (`PLD_IMM_MASK`, `DMB_DSB_ISB_MASK`, etc.), reusar sem duplicar.
2. Inserir o roteamento `if ((raw >>> 28) == 0xF) return decodeUnconditional(...)` logo após montar
   `condition`, antes do carve-out de `SWI`.
3. Extrair os oito blocos para `decodeUnconditional`, na mesma ordem em que apareciam.
4. Adicionar, no fim de `decodeUnconditional`, o mesmo laço de `decoderExtensions()` + fallback
   `UNIMPLEMENTED` que `decode` já tinha no fim.
5. Teste novo em `ArmDecoderTest` (`0xF2000000` → `UNIMPLEMENTED`) + teste confirmando que os
   carve-outs continuam funcionando depois da extração (`BLX` imediato, por exemplo).

## Aceite

- [x] `cond==0b1111` sem carve-out explícito devolve `UNIMPLEMENTED`, nunca outra `InstructionKind`.
- [x] Os oito carve-outs (`BLX` imediato, `SETEND`, `CPS`, `CLREX`, `PLD`/`PLDW`/`PLI`,
      `DMB`/`DSB`/`ISB`, `SRS`, `RFE`) continuam decodificando exatamente como antes.
- [x] `CoprocessorDecoder`/`VfpDecoder` continuam alcançáveis sob `cond=1111` (formas `2` do
      coprocessor, ARMv5+).
- [x] `mvn -o test` verde (core + truffle) com o teste de regressão novo.
- [x] G5: `gbaemu`/`ndsemu`/`armbox`/`virtual-arm-box` continuam verdes (mesma falha pré-existente
      documentada em `armbox`, não-regressão).

## Validação

`mvn -o test` no `arm-jitter` (core + truffle), depois `mvn -o install` local e `mvn -o test` nos
quatro consumidores (G5).

## Armadilhas

- O carve-out de `SWI` (`(raw & 0x0F00_0000) == 0x0F00_0000`) também não olha `bits[31:28]` — sob
  `cond=1111` ele também é um vazamento da mesma classe (o espaço incondicional real não tem `SWI`
  ali). Por isso o roteamento para `decodeUnconditional` precisa vir **antes** dele, não depois.
- `WFI` (`0x0320_F003`, sem máscara nos bits de condição) fica de fora de `decodeUnconditional` de
  propósito — é uma instrução condicional legítima (parte do espaço de hints/`MSR`), não pertence
  ao espaço incondicional; nenhum teste existente exercitava `WFI` com `cond=1111`, então isso não
  é uma mudança de comportamento observável coberta por suíte alguma antes desta task.
- Os oito carve-outs já usavam `Condition.AL` explícito no `DecodedInstruction` retornado (nunca a
  variável `condition` do escopo externo) — por isso movê-los para um método novo, recebendo
  `condition` só para o fallback `UNIMPLEMENTED`/extensões, não muda nenhum resultado.

## Resultado

✅ **FECHADA 2026-08-21.**

Implementado exatamente como especificado: `decode()` agora roteia `(raw >>> 28) == 0xF` para
`decodeUnconditional(address, raw, condition)` logo após computar `condition`, antes até do
carve-out de `SWI` (que também não olhava os bits de condição — mesma classe de vazamento,
documentado como armadilha e fechado junto). Os oito carve-outs (`BLX` imediato, `SETEND`, `CPS`,
`CLREX`, `PLD`/`PLDW`/`PLI`, `DMB`/`DSB`/`ISB`, `SRS`, `RFE`) foram movidos, sem alterar nenhuma
condição de bits/feature-gate, para dentro do método novo, que termina com o mesmo laço de
`architecture.decoderExtensions()` + fallback `UNIMPLEMENTED` que `decode()` já tinha — preservando
`CoprocessorDecoder` (formas `2` do coprocessor, ARMv5+) e `VfpDecoder` (espaço VFP), que nunca
olharam `bits[31:28]` e por isso já decodificavam corretamente sob `cond=1111` antes desta task.

Dois testes novos em `ArmDecoderTest`: `0xF2000000` (o `VHADD` de NEON da `E5`) agora decodifica
como `UNIMPLEMENTED`, nunca mais como `AND`; e `0xFA000001` (`BLX` imediato) confirma que os
carve-outs sobrevivem à extração intactos.

`./gerar-cobertura-isa.sh` rodado de novo como evidência (não como parte do fix): `git diff
docs/COBERTURA-ISA.md` vazio — os números não mudam, como esperado ("Não inclui" acima) — a tabela
já contava esses encodings como ❌ antes (decodificavam como outra coisa) e depois (`UNIMPLEMENTED`
direto); a diferença desta task é comportamento em tempo de execução (exceção de instrução
indefinida vs. corrupção silenciosa de registrador), não uma métrica de decode. Sem marco de
cobertura → sem release do Maven Central para esta task.

`mvn -o test` verde no `arm-jitter` (1506 testes core + 13 truffle). G5 revalidado nos 4
consumidores: `gbaemu` verde (240 testes), `ndsemu` verde (183 testes), `virtual-arm-box` verde (87
testes), `armbox` com a mesma falha pré-existente já documentada (`Armv7TortureTest`, `VFP`
`ArrayIndexOutOfBounds`, ver `E2`/`B1.8`) — não-regressão, `armbox` não foi tocado por esta task.
