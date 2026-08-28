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
| **B11.1** | `arch64.Aarch64Feature` (enum) + `arch64.Aarch64Architecture` (presets `ARMV8_0_A`...`ARMV8_x_A`, `ARMV9_0_A`...), mesmo padrão de `EnumSet<Aarch64Feature>`/`has()`/`extending()` do lado 32-bit. SEM plugar em lugar nenhum ainda — só a estrutura, testada isoladamente (mesmo padrão de B10.1: fundação primeiro) | — |
| **B11.2** | Overload `Aarch64Core(AddressSpace64, Aarch64Architecture)` + threading da arquitetura para `Aarch64Decoder` (que hoje é instanciado sem args em `Ir64BlockExecutor`/`StandardIr64BlockLifter` — precisa passar a receber a arquitetura do `Aarch64Core` dono). Construtor antigo vira `this(memory, Aarch64Architecture.ARMV8_0_A)` (ou o preset que corresponder ao estado atual 100% do decoder) | B11.1 |
| **B11.3** | Auditoria: mapear TODAS as instruções/registradores já implementados no A64 para a versão/feature ARM real que os introduziu (muito trabalho já foi feito em `docs/isa-nao-aplicavel.tsv`, que já cita `FEAT_*`/versão para tudo excluído — esta task inverte a lógica: em vez de só excluir o que falta, marca cada linha IMPLEMENTADA com a feature real) — sem isso, `Aarch64Architecture` não sabe o que gatear | B11.1 |
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
