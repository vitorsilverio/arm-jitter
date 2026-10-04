# E17 — `COBERTURA-ISA.md`: medir **o que** decodifica, não só **se** decodifica (A64)

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** — (achado das E15.9b/E15.9c) · **G5:** não
(só `core/src/test/.../tools/`, `docs/` e scripts) · **Status:** ⬜ — spec escrita em 2026-10-03 a
pedido do usuário ("crie a task e spec dentro dessa trilha ou vai se perder").

## Contexto

`IsaCoverageReport#probeAarch64` (`core/src/test/java/dev/vitorsilverio/armjitter/tools/
IsaCoverageReport.java:1238`) monta palavras de amostra de cada linha do `a64.decode` (as
`FILL_STRATEGIES`) e marca `✅` se **alguma** delas devolve `decode(...) != null`. Nunca olha **qual**
instrução saiu. O único freio é a lista manual `AARCH64_MISDECODED` (`⚠️`), que só contém o que
alguém já achou por acaso.

Isso já escondeu misdecodes reais quatro vezes, todos medidos `✅`:

| Task | O que a tabela dizia | O que o decoder fazia |
|---|---|---|
| B19.11e | `FSCALE_h` ✅ | outra instrução FP |
| B19.24 | `FAMAX_h` ✅ | outra instrução FP |
| E15.9b | `FADDP`/`FMAXP`/`FMINP`/`FMAXNMP`/`FMINNMP` `_v` `_h` ✅ | `INS` (AdvSIMD copy) |
| E15.9b | 38 células de ARMv8.0/8.1 (`FDIV_v`/`FMUL_v`/… `_h` sem `FEAT_FP16`) ✅ | `INS` |

A E15.9c fechou o lado "aceita o que não existe" no espaço AdvSIMD/FP escalar (guarda
`AdvSimdFpResidueG8Test`: contagem + SHA-256 do conjunto aceito, conferido no `objdump`). Ela **não**
pega uma palavra válida decodificada como **outra** instrução válida — o conjunto aceito não muda,
só o record que sai. Na E15.9c o usuário escolheu a impressão do conjunto aceito e esta checagem
(alternativa "b") ficou registrada como candidata a task própria: é esta.

## Objetivo

Toda célula `✅` do A64 em `docs/COBERTURA-ISA.md` passa a significar "decodifica **como a
instrução da linha**". Misdecode vira `⚠️` (ou falha de teste) automaticamente, sem depender de
alguém achar por acaso.

## Inclui

1. **Medição primeiro (antes de qualquer desenho final).** Script em `e17-scripts/` que, para cada
   linha A64 (`a64.decode`, `sve.decode`, `sme.decode`) e cada palavra de amostra que o decoder
   aceita, grava `nome-QEMU · palavra · record devolvido (classe + campo op/enum, se houver)` e
   cruza a palavra com o `aarch64-none-elf-objdump` do devkitA64 (mesma técnica de
   `e15.9b-scripts/objdump-residuo.sh`). Saída: tabela `nome-QEMU · mnemônico do objdump · record`.
   Números no `## Resultado`: quantas linhas, quantos mnemônicos `objdump` ≠ nome-QEMU, quantos
   pares nome→record ambíguos.
2. **Assinatura esperada por linha, versionada.** Arquivo gerado + revisado
   (`docs/isa-a64-assinaturas.tsv`, nome a confirmar na execução): `nome-QEMU#ocorrência →
   record esperado` (classe e, quando o record tem enum de operação, o valor — ex.
   `FpArithmeticPairwise/MAXNM`). Gerado pelo script do item 1; cada linha aceita só depois de o
   mnemônico do `objdump` bater com o nome-QEMU. Mnemônicos e nomes de classe são fatos/nomes
   nossos — o arquivo NÃO copia o `.decode` do QEMU (GPL), mesmo cuidado de `gerar-cobertura-isa.sh`.
3. **`probeAarch64` confere a assinatura.** Palavra aceita com record diferente do esperado ⇒
   `⚠️` (`Status.FALLBACK`), não `✅`. `AARCH64_MISDECODED` vira redundante para o A64: decidir na
   execução se some ou fica só como comentário histórico.
4. **Guarda de consistência** (teste): toda linha A64 do inventário fixado (`QEMU_REV`) tem
   assinatura no TSV (ou motivo explícito de ausência); entrada órfã no TSV quebra o teste.
5. Cada misdecode que a medição achar: **não** corrigir aqui — vira `⚠️` na tabela e entra numa
   task de correção por família (mesma disciplina da E15.9b → E15.9c), salvo correção trivial de uma
   linha que o usuário aprovar na hora.

## Não inclui

- Colunas de 32 bits (`ArmDecoder`/`Thumb*Decoder`/VFP/NEON/MVE): mesmo problema, task irmã depois
  desta (o devkitARM tem `arm-none-eabi-objdump` para a mesma técnica).
- Conferir operandos/campos (registradores, imediatos) — só a identidade da instrução. Campo errado
  com instrução certa é trabalho dos testes de decoder por família.
- Semântica (executor) — a tabela continua não provando semântica (ver "O que ✅ NÃO significa" no
  cabeçalho do `COBERTURA-ISA.md`).

## Especificação / decisões a tomar na execução

- **Granularidade da assinatura:** só a classe do record é fraca (`FpArithmeticThreeSame` cobre
  `FADD`/`FSUB`/`FMUL`/…); classe + enum `op` resolve a maioria. Records sem enum (ex.
  `AdvSimdMoveOp64.Extract`) ficam só com a classe. Medir no item 1 quantos ficam ambíguos antes de
  fixar o formato.
- **Aliases do `objdump`:** o `objdump` imprime o alias preferido (`mov` para `ORR`/`DUP`/`INS`/
  `UMOV`, `cmp` para `SUBS`, `lsl` para `UBFM`…). O cruzamento nome-QEMU × mnemônico precisa de uma
  tabela de aliases pequena e explícita; o que não casar vai para revisão manual, nunca para
  "ignorado".
- **Várias estratégias de preenchimento:** hoje basta UMA palavra aceitar. Com a assinatura, a
  regra proposta é: toda palavra aceita tem de bater a assinatura (uma que decodifica como outra
  coisa é misdecode mesmo que outra estratégia acerte).
- **Preset:** a assinatura vale por linha, independente da coluna — mas uma linha cuja feature não
  está no preset da coluna pode cair noutra instrução real (foi o caso das 38 células da E15.9b): é
  exatamente o que a checagem precisa pegar.

## Passos

1. Ler `IsaCoverageReport` (`probeAarch64`, `FILL_STRATEGIES`, `encode`, `AARCH64_MISDECODED`,
   `requireFirst`), `gerar-cobertura-isa.sh` e os scripts `e15.9b-scripts/`/`e15.9c-scripts/`.
2. Item 1 (medição) → registrar números → **parar e mostrar ao usuário** antes de fixar o formato do
   TSV (o tamanho do resíduo decide se a correção cabe nesta task ou vira tasks por família).
3. Itens 2–4.
4. Regenerar `docs/COBERTURA-ISA.md`; toda célula que sair de `✅` vai listada no `## Resultado`
   com o record que saiu e o mnemônico real.

## Aceite

- `./gerar-cobertura-isa.sh` usa a assinatura; diff de `docs/COBERTURA-ISA.md` explicado célula a
  célula.
- Teste de consistência do TSV verde; regressão: as palavras de `FSCALE_h`/`FAMAX_h`/`F*P_v _h`
  decodificadas com o código ANTERIOR às correções delas (record errado) seriam `⚠️` — conferir com um
  teste que injeta assinatura trocada.
- JaCoCo nas classes tocadas (regra do `tasks/README.md`).

## Armadilhas

- `FEAT_SME` embrulha AdvSIMD em `StreamingRestricted` — desembrulhar antes de comparar a classe.
- Formas `_h` vs `_sd` e escalar vs vetorial reusam o mesmo record com `esz`/`scalar` diferentes:
  se a assinatura não incluir esses campos, `FADDP_s` e `FADDP_v` ficam indistinguíveis — decidir
  conscientemente (item "granularidade").
- `objdump` desmonta com todas as features ligadas e pode não conhecer extensões recentes (FP8/LUT/
  SME2p1) — conferir no `a64.decode` antes de chamar divergência de bug.
- `awk` do Git Bash com saída Java CRLF: `tr -d '\r'` antes de comparar (armadilha da E15.9c).

## Resultado

### Item 1 — medição (2026-10-03)

Scripts em `e17-scripts/`: `A64SignatureProbe.java` (linha × coluna × estratégia → palavra aceita e
assinatura = classe do record sem `StreamingRestricted` + todo componente `enum`; aplicabilidade igual à
do `appendGroup`), `objdump-mnemonicos.sh` (cruza cada palavra com o `objdump` do devkitA64) e
`SignatureStats.java` (números). Roda em segundos.

| Medida | Valor |
|---|---|
| pares (linha, palavra, assinatura) | 11 419 (11 398 palavras distintas) |
| linhas com palavra aceita | 2 203 |
| linhas com palavra aceita que o `objdump` chama de `undefined` | 24 (24 palavras) |
| linhas com mnemônico do `objdump` ≠ base do nome-QEMU | 514 — 401 pares nome→mnemônico distintos, todos convenção de nome (`LDR` do QEMU cobre `ldrsh`/`ldtr`/`ldur`…) ou alias (`mov`, `cmp`…); nenhum misdecode apareceu por aqui |
| linhas com mais de uma assinatura | 71 — 67 são enum de OPERANDO (tamanho, shift, extend, condição) que varia com a estratégia; 4 são misdecode por coluna (abaixo) |
| assinaturas distintas | 1 138 · 433 compartilhadas por >1 linha · 99 por mnemônicos-base diferentes |

**Conclusão de desenho:** o cruzamento nome-QEMU × mnemônico não acha misdecode (o `objdump` diz `mul`,
o nome diz `MUL`; só o record está errado). O que acha é **assinatura → conjunto de mnemônicos do
`objdump`**: um record com intruso de outra família (`FpMultiplyAddLongByElement` ← `mul`) salta à vista.
Enum de operando não serve na assinatura de identidade (muda com a estratégia de preenchimento).

**Misdecodes achados** (palavra válida → record de OUTRA instrução; todos medem `✅` hoje):

| Linha(s) | Record devolvido | Colunas | Causa |
|---|---|---|---|
| `MUL_vi#2`/`MLA_vi#2`/`MLS_vi#2`/`SQDMULH_vi#2` (forma `.s`) | `FpMultiplyAddLongByElement` (`FMLAL`) | ARMv8.2-A+ (presets com `FEAT_FHM`) | o desvio do `FMLAL_vi` (`Aarch64Decoder` ~5520) confere `opcode & 0b0011 == 0` mas não `U == top` |
| `PACIBSP#1`, `GCSB#1` (e todo hint com `op2=011`, `CRm≠0`) | `SystemInstruction/WFI` | todas | `decodeSystemInstruction` testa só `op2 == 011`, sem `CRm == 0` — um `PACIBSP` num preset sem PAuth DORME até IRQ em vez de NOP |
| `SMAX_i`/`SMIN_i`/`UMAX_i`/`UMIN_i` (`FEAT_CSSC`) | `Alu64/ADD` | todas (inclusive ARMv8.0-A: falta o requisito de versão no mapa) | add/sub imediato não confere `bit23` |
| `ADDG_i`/`SUBG_i` (`FEAT_MTE`) | `Alu64/ADD` / `Alu64/SUB` | todas | idem |

**G8 (aceita o que não existe)**, `objdump` = `undefined`: `PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/
`AUTDA`/`AUTDB` com `Z=1` e `Rn≠31` (8 linhas); `CASP` com `Rs` ímpar. As outras 15 palavras `undefined`
são registradores sobrepostos (`LDP`/`LDPSW` com `Rt==Rt2`, `CPY*`/`SET*` com `Rd=Rs=Rn`) — CONSTRAINED
UNPREDICTABLE, artefato da estratégia `{0,0,0,0}`, não bug.

**Aproximações deliberadas e documentadas** (mesmo record para instruções diferentes, por decisão de
task anterior — a assinatura precisa aceitá-las explicitamente, não esconder): `FRINTX`/`FRINTI` como
`Round/NEAREST_TIES_EVEN`; `WFIT` = `WFI`, `WFET` = NOP; `GCSSTR`/`GCSSTTR` = `Store64`; `LDRAA`/`LDRAB` =
`Load64` (B19.15, rota b); `BRAA`/`RETAA`/`ERETAA`… = forma sem PAC; hints PAC (`PACIASP`, `AUTIA1716`…)
= `NOP_HINT`. Este último foi decidido na B6.6.7, ANTES de existir `FEAT_PAuth` real
(`Aarch64Feature#POINTER_AUTHENTICATION`): num preset v8.3+ o `PACIA` explícito assina de verdade e o
`AUTIASP` é NOP — mistura `pacia x30, sp` + `autiasp` deixa o PAC no `LR`. Semântica, fora da E17;
candidata a task própria.

### Decisões do usuário (2026-10-03, depois da medição)

1. Assinatura = **classe + enum de operação**. Enums de operando (`Ir64MemSize`, `Ir64FpMemSize`,
   `Ir64ShiftType`, `Ir64LogicalShiftType`, `Ir64ExtendType`, `Ir64AluExtendType`, `Ir64Condition`,
   `Ir64CompareBranchCondition`, `Aarch64SystemRegisterId`) ficam de fora. Com isso as linhas com várias
   assinaturas caíram de 71 para 6, todas legítimas (a linha do QEMU cobre duas instruções: `CCMP`/`CCMN`,
   `CSEL`/`CSNEG`, `Vimm` `MOVI`/`BIC`, `UNPK`, `DOT_zzzz`, `LDRA` offset/pré-índice).
2. **Corrigir aqui os misdecodes triviais**; o que exige implementação vira task (`B19.30`).
3. Teto do `Aarch64Decoder` no `TamanhoDeFonteGuardTest`: subir na medida (7485 → 7510).

### Itens 2–4 — o que foi feito

- **`A64InstructionSignature`** (`tools/`, teste): a assinatura. **`docs/isa-a64-assinaturas.tsv`**: 2 197
  linhas `grupo · nome#ocorrência · assinaturas · amostra(<coluna>:<palavra>)`, gerado e revisado (abaixo).
- **`IsaCoverageReport#probeAarch64`** tenta TODAS as estratégias e confere cada palavra aceita: assinatura
  fora do conjunto, ou linha sem entrada ⇒ `⚠️`; as divergências são listadas no fim da execução.
  `-Disa.assinaturas.gravar=true` (= `./gerar-cobertura-isa.sh --assinaturas`) regrava o TSV a partir do
  decoder atual para revisão. A sonda "máxima" do SVE (`column == null`) não registra nada — não é célula.
- **`AARCH64_MISDECODED` saiu** (estava vazia desde a B19.24) junto com os dois testes dela no
  `IsaCoverageReportA64CurationGuardTest`; o mecanismo novo cobre os dois casos.
- **`IsaA64SignatureGuardTest`** (CI-safe, só arquivos versionados): (a) cada amostra do TSV decodifica
  com uma assinatura da sua linha — é o **oráculo por linha das E15.10–E15.15** (migrar um grupo do decoder
  para tabela e trocar a instrução de qualquer linha implementada quebra o `mvn test`); (b) TSV × linhas
  `✅`/`⚠️` do `COBERTURA-ISA.md` batem nos dois sentidos (sem faltante, sem órfã); (c) regressão do aceite:
  com a assinatura trocada pelo record que o código anterior à E15.9b devolvia para `fmaxnmp v28.8h`
  (`AdvSimdMoveOp64.InsertElement`), a célula mede `⚠️`; com a certa, `✅`; sem entrada, `⚠️`.

**Revisão do TSV:** a medição foi refeita com a assinatura oficial (`A64SignatureProbe` passou a chamá-la
por reflexão) e cruzada com o `objdump`: em todas as 257 assinaturas com mais de um mnemônico, os
mnemônicos são variantes da mesma instrução (campo booleano/tamanho, `2`, `s`, `al`…) ou uma das
aproximações documentadas acima. Nenhum intruso de outra família sobrou. `undefined` no `objdump`: 24 → 15
palavras, todas registradores sobrepostos (CONSTRAINED UNPREDICTABLE).

### Correções no `Aarch64Decoder`

| Achado | Checagem | Teste |
|---|---|---|
| `MUL`/`MLA`/`MLS`/`SQDMULH_vi` `.s` → `FMLAL` | `U == top` no desvio do `FMLAL_vi` | `Aarch64DecoderE17MisdecodeTest#integerByElementWordFormIsNotFmlal` |
| hint `op2=011`, `CRm≠0` → `WFI` | `CRm == 0` para `WFI` | `#hintsWithWfiOp2ButNonZeroCrmAreNotWfi` |
| `PAC*`/`AUT*` `Z=1`, `Rn≠31` aceito | `Z ⇒ Rn = 11111` | `#pointerAuthZeroModifierFormRequiresRnAllOnes` |
| `CASP` `Rs`/`Rt` ímpar aceito | `(Rs | Rt) & 1 == 0` | `#compareAndSwapPairRequiresEvenRegisters` |
| add/sub imediato `bit23=1` → ADD/SUB | recusa (→ `B19.30`) | `#addSubImmediateWithBit23IsRefused` |

Sem as correções (`git stash` do `src/main`) 11 dos 15 casos falham. `Aarch64DecoderCorpusTest#
exclusiveAtomicFormSpaceFullyDecodedByB81` usava `CASP` com `Rs=31` (o `objdump` diz `undefined`): passou a
usar `Rs=30`. `SMAX_i`/`SMIN_i`/`UMAX_i`/`UMIN_i` e `ADDG_i`/`SUBG_i` ganharam requisito de versão
(`FEAT_CSSC`/`FEAT_MTE`) — não tinham, por isso mediam desde ARMv8.0-A.

### Diff de `docs/COBERTURA-ISA.md`

Total 29581/29581 → **29485/29519 (99%)**. Só 6 linhas mudam, célula a célula:

| Linha | Antes | Depois | O que saía antes |
|---|---|---|---|
| `ADDG_i`, `SUBG_i` | ✅ ×16 | `·` ARMv8.0–8.4, `❌` ARMv8.5+ (11 colunas) | `Alu64/ADD`, `Alu64/SUB` |
| `SMAX_i`, `SMIN_i`, `UMAX_i`, `UMIN_i` | ✅ ×16 | `·`, `❌` em ARMv8.9-A/9.4-A/9.5-A | `Alu64/ADD` |

Denominador −62 (células antes da feature), numerador −96 (62 + 34 `❌`). Os outros misdecodes não mudam
célula: uma das estratégias já saía como a instrução certa e a regra antiga ("basta uma") marcava `✅`; com
a regra nova eles mediriam `⚠️`, mas foram corrigidos na mesma task. Modo conferência depois de gravar o
TSV: zero divergências, tabela idêntica.

### Validação

- `mvn -o -pl core verify` (JBR 25): 15 647 testes verdes, `jacoco:check` ok.
- G5: não se aplica — `src/main` só em `decoder64/` (lista A64-only do `tasks/README.md`); o resto é teste,
  `docs/` e scripts.
- JaCoCo: todas as linhas novas/alteradas do `Aarch64Decoder` com 0 linha e 0 branch perdidos
  (`jacoco.xml`, conferido linha a linha pelo diff).

### Para as E15.10+

- Migrar um grupo do decoder para tabela: `mvn test` (o `IsaA64SignatureGuardTest` decodifica as amostras)
  + `./gerar-cobertura-isa.sh` sem diff e sem divergência listada. Uma amostra por linha não cobre todas as
  estratégias — o gerador cobre.
- Instrução nova: `./gerar-cobertura-isa.sh --assinaturas` e revisar o diff do TSV com
  `e17-scripts/` (`A64SignatureProbe` → `objdump-mnemonicos.sh` → lista assinatura → mnemônicos).
- Pendente (fora da E17): a mesma checagem nas colunas de 32 bits (task irmã, `arm-none-eabi-objdump`); a
  incoerência hint-PAC NOP × `PACIA` real em preset v8.3+.
