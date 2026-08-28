# B11 — `Aarch64Feature`/`Aarch64Architecture`: A64 personalizável por versão/feature, igual ao 32 bits

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce de uma pergunta direta do usuário (2026-08-27): por que o A64 tem
"um processador só" em vez de ser componível por versão/feature como o lado 32 bits (`ArmFeature`/
`ArmArchitecture`, presets `ARMV4T`/`ARMV5TE`/`ARMV6K`/.../`ARMV7A`)? Resposta honesta: **isso nunca
foi uma escolha de escopo do usuário — é uma lacuna de infraestrutura**. A regra máxima do
`tasks/README.md` já diz que o alvo final é "ARM inteiro, toda versão, todo perfil, toda extensão
opcional real" e que "alvo atual" é só ORDEM de trabalho, nunca escopo — isso vale para A64 tanto
quanto para A32/T32. O que faltou foi construir o mecanismo de composição equivalente.

## Por que isso aconteceu

`B6` (AArch64) nasceu como "um segundo frontend" (`ROADMAP.md`), estruturalmente independente do
pipeline 32-bit — pacotes próprios (`decoder64`/`executor64`/`ir64`/`core64`), sem reaproveitar
`ArmFeature`/`ArmArchitecture` (que são do pacote `arch`, usado só pelo `ArmCore`/`decoder`
32-bit). Como só existe UM consumidor A64 até hoje (`virtual-arm-box`, raspi3-64/F11), nenhuma task
precisou até agora escolher entre duas configurações A64 diferentes — cada gap fechado (B8.x, B10.x)
foi simplesmente adicionado ao decoder/executor de forma incondicional. Resultado, confirmado por
investigação de código nesta sessão:

- `Aarch64Core` tem um construtor só, sem parâmetro de versão/feature:
  `public Aarch64Core(AddressSpace64 memory)`.
- `Aarch64Decoder` é uma classe monolítica sem nenhum `if (features.has(X))` — o que não está
  implementado (SVE, SME, `FEAT_FP16`, `FEAT_RDM`, `FEAT_DotProd`, ...) não tem caminho de decode
  nenhum (cai em `UNDEFINED`), não é uma feature "desligada" que poderia ligar.
- Não existe `Aarch64Feature`/`Aarch64Architecture`/nada equivalente — `docs/COBERTURA-ISA.md` trata
  "A64" como UMA coluna monolítica, ao contrário de A32/T32, que já são separados por versão
  (v4T/v5TE/v6K/MPCore/v7-A/v6-M/v7-M).
- Não há noção de perfil (A/R/M) nem de "qual Cortex" para A64.

Isso é uma dívida técnica real: sem essa infraestrutura, "100% de A64" (a meta que trava a próxima
publicação no Maven Central, ver `tasks/README.md`) fica mal definido — hoje a tabela mede "tudo que
o Cortex-A53/ARMv8.0 tem implementado", não "ARMv8.0 completo E ARMv8.1 completo E ... E ARMv9.x
completo", que é o que a regra máxima realmente pede.

**Correção de rumo explícita do usuário (2026-08-27), para não se repetir**: o `arm-jitter` é
publicado no Maven Central como biblioteca — **pode ter consumidores além dos que este workspace
constrói hoje**. "Só o `virtual-arm-box` usa A64 hoje" é um fato sobre REGRESSÃO A TESTAR (G5), NUNCA
um argumento para adiar ou descartar ARMv9/SVE/SME/qualquer extensão opcional real. Sessões
anteriores trataram menções a ARMv9 como "sem consumidor real pedindo isso hoje" — isso é
exatamente o padrão que a regra máxima do `tasks/README.md` já proíbe ("nenhuma instrução/feature
ARM real fica de fora por... 'nenhum consumidor usa isso agora'") e que este épico existe para
parar de repetir. Uma extensão ARM real não precisa de um consumidor interno pedindo — precisa só
de existir no ARM.

## Meta

1. Um `Aarch64Feature` (enum) + `Aarch64Architecture` (presets componíveis, mesmo padrão
   `extending(BASE, nome, features...)` de `ArmArchitecture`) — pacote novo `arch64` (mirror de
   `arch`, mesma disciplina de B6 de nunca misturar os dois mundos).
2. `Aarch64Core`/`Aarch64Decoder`/o resto do pipeline A64 passam a CONSULTAR essa arquitetura nos
   pontos onde versões/extensões ARM real divergem — sem bifurcar o pipeline compartilhado (mesmo
   princípio do `ArmArchitecture` 32-bit: "decoders consultam `has(Feature)` nos poucos pontos onde
   REALMENTE differ").
3. `docs/COBERTURA-ISA.md`/`gerar-cobertura-isa.sh` passam a medir A64 por VERSÃO
   (ARMv8.0/8.1/.../9.x), não uma coluna só — só assim "100% de A64" fica bem definido para a regra
   do `1.4.0` (`tasks/README.md`).
4. `G3` (sem breaking change): construtor atual de `Aarch64Core(AddressSpace64)` continua existindo,
   default para a arquitetura "tudo que já está implementado hoje" (equivalente ao `ARMV8_0_A`
   completo, já que é o que o Cortex-A53/`virtual-arm-box` sempre pediu) — arquitetura nova entra por
   overload/parâmetro novo, nunca trocando o comportamento default.

## Escada (proposta inicial — refinar em spec própria quando cada item for pego)

| Task | Escopo | Depende de |
|---|---|---|
| ~~**B11.1**~~ ✅ fechada 2026-08-27 | `arch64.Aarch64Feature` (enum) + `arch64.Aarch64Architecture` (presets `ARMV8_0_A`...`ARMV8_x_A`, `ARMV9_0_A`...), mesmo padrão de `EnumSet<Aarch64Feature>`/`has()`/`extending()` do lado 32-bit. SEM plugar em lugar nenhum ainda — só a estrutura, testada isoladamente (mesmo padrão de B10.1: fundação primeiro) | — |
| ~~**B11.2**~~ ✅ fechada 2026-08-28 — overload `Aarch64Core(AddressSpace64, Aarch64Architecture)` + threading da arquitetura para `Aarch64Decoder`/`Ir64BlockExecutor`/`StandardIr64BlockLifter` | B11.1 ✅ |
| ~~**B11.3**~~ ✅ fechada 2026-08-28 — auditoria de versão dos mnemônicos A64 já ✅ (ver `trilha-b-arquiteturas/b11.3-auditoria-versao-a64.md`) — achado principal: 10 falsos positivos por bug de decode real (`CPYFP`/`CPYFM`/`CPYFE`/`SETP`/`SETM`/`SETE`/`LDCLRP`/`LDSETP`/`SWPP`/`LDAPR_i`/`STLR_i` misdecodificados como `LDR (literal)`; `LDRA` como `STR`/`STUR`), corrigido (G8) + ~15 mnemônicos não-baseline catalogados (`FEAT_LSE`/`FlagM`/`FlagM2`/`WFxT`/`PAN`/`UAO`/`DIT`/`SSBS`/`NMI`/`SHA512`/`SM3`/`SM4`/`SHA3`) | B11.1 ✅ |
| **B11.4** | Primeiro gate real: escolher 1-2 features já mapeadas em B11.3 que tenham decode isolado o bastante para gatear sem tocar o resto (candidato natural: `FEAT_RDM`/`SQRDMLAH`-`SQRDMLSH`, já isolado desde B8.8/B8.19) — prova de conceito ponta a ponta (decode recusa se `!has(FEATURE)`, aceita se `has`) | B11.2, B11.3 |
| **B11.5** | `gerar-cobertura-isa.sh`/`IsaCoverageReport`: A64 passa a ter uma linha por versão ARM (mesma UX de v4T/v5TE/... hoje), usando o mapeamento de B11.3 | B11.3 |
| **B11.x** | Implementar de fato o restante de `docs/isa-nao-aplicavel.tsv` (SVE, SME, FP16, DotProd, FHM, FCMA, I8MM, BF16, PAC, MTE, ...) — cada extensão vira uma ou mais tasks próprias, AGORA gateadas por `Aarch64Feature` em vez de simplesmente "implementado incondicionalmente" | B11.4 (padrão estabelecido) |

**Ordem sugerida**: B11.1 (fundação) → B11.2 (fiação no core/decoder, ainda sem gatear nada de
verdade — zero-diff comportamental) → B11.3 (auditoria, pode rodar em paralelo com B11.2) → B11.4
(prova de conceito com 1 feature isolada) → B11.5 (medidor por versão) → daí em diante, toda nova
extensão A64 (SVE/SME/FP16/etc.) já nasce gateada, não mais "implementada e pronto".

## Fatos de referência

- Padrão a espelhar: `core/src/main/java/dev/vitorsilverio/armjitter/arch/ArmFeature.java` +
  `arch/ArmArchitecture.java` (`EnumSet<ArmFeature>`, `has(ArmFeature)`, `extending(BASE, nome,
  features...)`, `DecoderExtension` para grupos de instrução inteiros).
- Estado atual do A64 (confirmado por investigação de código, 2026-08-27): `Aarch64Core` só tem
  `public Aarch64Core(AddressSpace64 memory)`; `Aarch64Decoder` é `final class` sem nenhum campo de
  configuração; `Aarch64Decoder` é instanciado em `Ir64BlockExecutor` (`private final Aarch64Decoder
  decoder = new Aarch64Decoder();`) e em `StandardIr64BlockLifter`.
- ARMv9 já foi declarado alvo real (`ROADMAP.md`, decisão 2026-08-24) — este épico é o que torna
  isso EXECUTÁVEL (hoje não há onde "pendurar" um preset ARMv9 diferente de ARMv8.0).
- `docs/isa-nao-aplicavel.tsv` já documenta, para cada instrução excluída, a versão/feature ARM real
  que a introduz (ex. `ARMv8.1-A/FEAT_RDM`, `ARMv8.6-A/FEAT_BF16`) — é o ponto de partida direto para
  B11.3, não precisa remedir do zero.

## Consumidores a revalidar (G5, toda task desta escada)

`virtual-arm-box` é o único consumidor A64 hoje (raspi3-64) — mas G5 continua cobrindo os 5 repos
(gbaemu/ndsemu/n3dsemu/armbox são 32-bit, não afetados diretamente, mas compartilham `core`). Push
obrigatório em toda task — ver `tasks/README.md`. **G3 é crítico nesta escada inteira**: B11.2 em
particular NUNCA pode mudar o comportamento observável de `virtual-arm-box` (o preset default tem
que ser bit-a-bit idêntico ao "tudo incondicional" de hoje).

## Resultado — B11.1 (2026-08-27)

Pacote novo `arch64` (mirror exato de `arch`, mesma disciplina de nunca misturar os dois mundos):

- `Aarch64Feature` (enum, 19 valores): cada uma é uma extensão `FEAT_*` real ARMv8.1-A..ARMv9.5-A
  já catalogada em `docs/isa-nao-aplicavel.tsv` (RDM, FP16, DOT_PRODUCT,
  FP16_FUSED_MULTIPLY_ADD_LONG, SHA512, SM3, SM4, JAVASCRIPT_CONVERT, COMPLEX_NUMBER_ARITHMETIC,
  POINTER_AUTHENTICATION, DIRECTED_ROUNDING_TO_INTEGRAL, MEMORY_TAGGING, BFLOAT16,
  INT8_MATRIX_MULTIPLY, MEMORY_COPY_SET, COMMON_SHORT_SEQUENCE_COMPRESSION,
  SCALABLE_MATRIX_EXTENSION, FP_ABSOLUTE_MAX_MIN, GUARDED_CONTROL_STACK, COMPARE_AND_BRANCH).
  Não é a auditoria completa (isso é B11.3) — é o conjunto que já tinha fonte real catalogada,
  suficiente para provar a estrutura. `SVE` propriamente dito (mandatório em ARMv9.0-A real) fica
  de fora de propósito: nenhuma instrução SVE decodifica hoje, então não há o que gatear ainda —
  candidata a task própria quando o decode SVE começar.
- `Aarch64Architecture` (mesmos métodos `of`/`extending`/`has`/`name`/`toString` de
  `ArmArchitecture`, SEM `DecoderExtension`/`decoderExtensions()` — esse mecanismo não existe no
  pipeline A64 ainda, fica para quando/se B11.2+ precisar dele): presets `ARMV8_0_A` (baseline,
  zero features extras — representa o estado 100% incondicional de hoje) até `ARMV8_9_A`, e
  `ARMV9_0_A` até `ARMV9_5_A`. **Achado de projeto, confirmado no manual ARM (introdução da
  arquitetura ARMv9-A, ARM DDI 0487)**: toda ARMv9.x-A tem como baseline mandatório (fora
  SVE/SME) exatamente o conjunto de features da ARMv8.(x+4)-A correspondente — por isso
  `ARMV9_0_A` estende `ARMV8_5_A`, `ARMV9_1_A` estende `ARMV8_6_A`, etc., em vez de serem
  declarados do zero.
- **Zero wiring**: nenhum decoder/executor A64 consulta `Aarch64Architecture`/`has()` ainda —
  `Aarch64Core`/`Aarch64Decoder` continuam exatamente como estavam (G3, comportamento idêntico).
  Isso é B11.2 (thread da arquitetura no `Aarch64Core`/`Aarch64Decoder`) em diante.
- `Aarch64ArchitectureTest` novo (10 testes): cobre herança de features através da cadeia
  ARMv8.x, a correspondência de baseline ARMv9.x↔ARMv8.(x+4), imutabilidade da base em
  `extending`/`of`, e que `ARMV8_0_A` não liga nenhuma feature.
- `mvn -o test` verde (core 2513, +10) + `mvn -o install`; G5 completo nos 5 consumidores
  (gbaemu 5 jogos ✅, ndsemu ✅, virtual-arm-box ✅, n3dsemu ✅, armbox 47/47 ✅ — nenhuma falha
  pré-existente reproduzida desta vez, `Armv7TortureTest`/`Thumb2TortureTest` passam limpos).
  `docs/COBERTURA-ISA.md` não regenerado (sem mudança de decode observável, nada a medir).
  **Próximo da escada**: B11.2 (overload `Aarch64Core(AddressSpace64, Aarch64Architecture)` +
  fiação no `Aarch64Decoder`, ainda sem gatear nada de verdade) ou B11.3 (auditoria de versão de
  cada instrução A64 já implementada), que podem rodar em paralelo — cabe ao usuário priorizar.

## Resultado — B11.2 (2026-08-28)

Fiação pura, sem nenhum gate de decode real (isso continua sendo B11.4) — mesmo padrão dos
decoders 32-bit que recebem `ArmArchitecture` no construtor (`Thumb2DataProcessingDecoder`, etc.):

- `Aarch64Decoder` ganhou um campo `architecture` (`Aarch64Architecture`) + construtor
  `Aarch64Decoder(Aarch64Architecture)` + accessor `architecture()`. O construtor sem argumento
  (preservado) delega para `this(Aarch64Architecture.ARMV8_0_A)` — G3: comportamento de decode
  IDÊNTICO, provado por teste (`decodeIsIdenticalRegardlessOfArchitecture`).
- `Aarch64Core` ganhou o overload pedido pela task, `Aarch64Core(AddressSpace64,
  Aarch64Architecture)`, mais o accessor `architecture()`. O construtor antigo delega para
  `this(memory, Aarch64Architecture.ARMV8_0_A)`.
- `Ir64BlockExecutor`/`StandardIr64BlockLifter` (os dois pontos que a task cita como "hoje
  instanciados sem args") ganharam o mesmo padrão: construtor com `Aarch64Architecture` que
  repassa para o `Aarch64Decoder` interno, construtor sem argumento delegando para
  `ARMV8_0_A`. **Achado de projeto**: `decode()` não recebe o `Aarch64Core` (só
  `AddressSpace64`+endereço), e um `Ir64BlockExecutor`/`StandardIr64BlockLifter` pode processar
  vários cores ao longo da vida (ver `JitRuntime64`, que os cria uma vez só) — por isso "a
  arquitetura do `Aarch64Core` dono" não é lida dinamicamente do core a cada chamada; é o
  MESMO valor configurado nos três pontos (core, executor, lifter) pelo código que os monta,
  exatamente como o lado 32-bit já faz (`ArmArchitecture` fixada na composição do `JitRuntime`,
  nunca lida de volta do `ArmCore`). `JitRuntime64` não precisou mudar — seu construtor de
  componentes explícitos já aceita um `Ir64BlockExecutor`/`Ir64BlockLifter` prontos, então quem
  quiser uma arquitetura não-default monta os três (core, executor, lifter) com o mesmo preset,
  sem precisar de uma 4ª sobrecarga.
- 4 suítes de teste novas/estendidas, todas com o mesmo formato "decode/lift/execução idêntico
  independente da arquitetura passada" + rejeição de `null` (`NullPointerException`):
  `Aarch64DecoderArchitectureWiringTest` (novo, 4 testes), `Aarch64CoreTest` (+3),
  `StandardIr64BlockLifterTest` (+2), `Ir64BlockExecutorTest` (+2).
- `mvn -o test` verde (core+truffle, 2524) + `mvn -o install`; G5 completo nos 5 consumidores
  (gbaemu 240 ✅, ndsemu 183 ✅, virtual-arm-box 87 ✅ — único consumidor A64 real, sem diferença
  observável —, n3dsemu 221 ✅, armbox 47/47 ✅, sem a falha pré-existente reproduzida). Sem
  mudança em `docs/COBERTURA-ISA.md` (zero-diff de decode, nada novo a medir). **Próximo da
  escada, não pego automaticamente**: B11.3 (auditoria de versão de cada instrução A64 já
  implementada) ou B11.4 (primeiro gate real, candidato natural `FEAT_RDM`).
