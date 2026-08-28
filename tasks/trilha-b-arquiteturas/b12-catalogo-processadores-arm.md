# B12 — Catálogo de processadores ARM nomeados (`ArmProcessor`/`Aarch64Processor`)

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce de uma pergunta do usuário (2026-08-27/28): na
[List of ARM processors](https://en.wikipedia.org/wiki/List_of_ARM_processors) da Wikipedia, é a
coluna **Architecture** (`ARMv7-A`, `ARMv8.2-A`, ...) que determina o conjunto de instruções — a
coluna **Processor**/core (`Cortex-A53`, `Cortex-A76`, ...) é uma implementação daquela
arquitetura, podendo variar em quais extensões opcionais aquele SKU específico tem. A partir daí, o
usuário perguntou se dava para ter um "builder" que já vem com os processadores reais catalogados
(arquitetura + features conhecidas), para os dois lados do projeto (32-bit e A64), em vez do
cliente montar a `ArmArchitecture`/`Aarch64Architecture` na mão toda vez.

## Relação com B11

Este épico é **irmão** de [B11](b11-plano-aarch64-feature-gating.md), não uma sub-task dele: B11
constrói o mecanismo de composição por VERSÃO de arquitetura (`ArmFeature`/`ArmArchitecture` já
existiam para 32-bit; `Aarch64Feature`/`Aarch64Architecture`, novos, nasceram na B11.1). B12
constrói uma camada de conveniência ACIMA disso: um catálogo nomeado por PROCESSADOR real, que
resolve para uma dessas arquiteturas (existente ou nova, quando necessário). B12.1+ depende de
B11.1 ✅ (já fechada) só no lado A64 — o lado 32-bit já tem `ArmArchitecture` pronta desde antes do
épico B11 existir.

## Por que dois catálogos (não um só)

`ArmProcessor` (pacote `arch`) resolve para `ArmArchitecture` (32-bit, `A32`/`T32`).
`Aarch64Processor` (pacote `arch64`) resolve para `Aarch64Architecture` (`A64`). Um núcleo
dual-mode real (ex. `Cortex-A53`, que tem A32 **e** A64) ganha uma entrada em CADA catálogo — são
dois "modos de execução" do mesmo silício, com decoders/executores completamente diferentes neste
projeto (`decoder`/`decoder64` nunca se misturam, mesma disciplina de B6). O Javadoc de cada
entrada cita a contraparte no outro catálogo quando ela existir (ex. `ArmProcessor.CORTEX_A53`
aponta para `Aarch64Processor.CORTEX_A53`).

## Meta

1. `arch.ArmProcessor` — enum (ou classe com instâncias estáticas nomeadas, mesmo padrão de
   `ArmArchitecture`) cobrindo os núcleos ARM32/T32 reais listados na Wikipedia, cada um resolvendo
   para uma `ArmArchitecture` (reaproveitando os presets já existentes quando o conjunto de
   features bate; documentando divergência quando não bate — ver "Fatos de referência" abaixo).
2. `arch64.Aarch64Processor` — o mesmo para os núcleos A64 reais, resolvendo para
   `Aarch64Architecture` (presets de B11.1 ou compostos ad-hoc via `of`/`extending` quando a versão
   exata do núcleo tiver uma feature documentada que nenhum preset de versão ainda carrega).
3. **Curadoria factual, não geração em massa**: cada entrada só entra com a arquitetura REAL citada
   (fonte: a tabela da Wikipedia é o ponto de partida factual, mas para o conjunto de features
   opcionais — ex. `FEAT_FP16` ligado ou não num Cortex específico — a fonte final é o *Technical
   Reference Manual* do núcleo, não a Wikipedia; ver Armadilhas). Um núcleo sem fonte suficiente
   para o conjunto de features fica com a arquitetura de VERSÃO (ex. `ARMV8_2_A`) como aproximação
   documentada, nunca inventado.
4. **G3 (sem breaking change) e zero escopo novo de decode**: este épico NUNCA implementa uma
   instrução/feature nova — só cataloga. Se um núcleo precisar de uma `Aarch64Feature`/`ArmFeature`
   que ainda não existe, isso vira task de decode própria (fora do B12), e a entrada do catálogo
   fica marcada como bloqueada nela até fechar.
5. **Regra máxima do projeto**: nenhuma família de processador (clássico, Cortex-A/R/M/X,
   Neoverse, C-Series) fica de fora "por ser rara" ou "sem consumidor hoje" — a Wikipedia é o
   inventário completo a cobrir, cedo ou tarde (ver `tasks/README.md`).

## Inventário de origem (Wikipedia, `List of ARM processors`, consultado 2026-08-28)

Tabela completa por família — cada linha é candidata a uma entrada do catálogo. `arquitetura` é a
string exata da coluna "Architecture" da Wikipedia (ponto de partida, não a palavra final — ver
Armadilhas sobre precisão de sub-revisão). `modo` é A32/T32/A64 conforme a coluna "Instruction set".

### ARM clássico (pré-Cortex) — candidato a `arch.ArmProcessor`

| Núcleo | Arquitetura (Wikipedia) | Modo |
|---|---|---|
| ARM1 | ARMv1 | A32 |
| ARM2 | ARMv2 | A32 |
| ARM250 | ARMv2a | A32 |
| ARM60/600/610/700/710/710a | ARMv3 | A32 |
| ARM7TDMI/710T/720T/740T | ARMv4T | A32/T32 |
| ARM810 | ARMv4 | A32 |
| ARM9TDMI/920T/922T/940T | ARMv4T | A32/T32 |
| ARM7EJ-S | ARMv5TEJ | A32/T32 |
| ARM946E-S/966E-S/968E-S/996HS | ARMv5TE | A32/T32 |
| ARM926EJ-S/1026EJ-S | ARMv5TEJ | A32/T32 |
| ARM1020E/1022E | ARMv5TE | A32/T32 |
| ARM1136J(F)-S | ARMv6 | A32/T32 |
| ARM1156T2(F)-S | ARMv6T2 | A32/T32 |
| ARM1176JZ(F)-S | ARMv6Z | A32/T32 |
| ARM11 MPCore | ARMv6K | A32/T32 |

**Já cobertos por presets existentes** (não precisam de trabalho de arquitetura novo, só a entrada
do catálogo apontando): ARM7TDMI→`ARMV4T`; ARM926EJ-S/família ARMv5TE→`ARMV5TE`; ARM11
MPCore→`ARM11_MPCORE`. Os demais (ARMv1/v2/v2a/v3, ARMv6/ARMv6T2/ARMv6Z puros, ARMv5TEJ "J"/Jazelle)
não têm preset — decisão de escopo por sub-task (ver escada).

### SecurCore

| Núcleo | Arquitetura | Modo |
|---|---|---|
| SC000 | ARMv6-M | T32 |
| SC100 | ARMv4T | A32/T32 |
| SC300 | ARMv7-M | T32 |

### Cortex-M (perfil M) — candidato a `arch.ArmProcessor`

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Cortex-M0/M0+/M1 | ARMv6-M | T32 |
| Cortex-M3 | ARMv7-M | T32 |
| Cortex-M4/M7 | ARMv7E-M | T32 |
| Cortex-M23 | ARMv8-M Baseline | T32 |
| Cortex-M33/M35P | ARMv8-M Mainline | T32 |
| Cortex-M52/M55/M85 | ARMv8.1-M Mainline | T32 |

**Já cobertos**: Cortex-M0/M0+/M1→`ARMV6M`; Cortex-M3→`ARMV7M` (nota: `ARMV7M` hoje modela
`ARMv7E-M`-like sem DSP puro de M4 — conferir no momento da sub-task se M3 "ARMv7-M" puro precisa de
um preset ligeiramente menor, sem `SATURATING`/DSP). `ARMv7E-M`/`ARMv8-M`/`ARMv8.1-M` não têm preset
ainda.

### Cortex-R (perfil R)

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Cortex-R4/R5/R7/R8 | ARMv7-R | A32/T32 |
| Cortex-R52/R52+ | ARMv8-R | A32/A64 |
| Cortex-R82 | ARMv8-R | A64 |

Nenhum preset hoje (perfil R nunca foi modelado neste projeto — nem `ArmFeature`/`ArmArchitecture`
nem `Aarch64Feature`/`Aarch64Architecture` distinguem perfil A/R hoje, só perfil M via
`M_PROFILE`). Candidata a abrir um épico próprio de perfil R antes de ter entradas de catálogo úteis
(diferente de A/M, que já têm decoders A32/A64 e M_PROFILE prontos).

### Cortex-A 32-bit — candidato a `arch.ArmProcessor`

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Cortex-A5/A7/A8/A9/A12/A15/A17 | ARMv7-A | A32/T32 |
| Cortex-A32 | ARMv8-A | A32 (AArch32-only) |

**Já coberto**: toda a linha ARMv7-A→`ARMV7A`. Cortex-A32 é AArch32-only rodando o perfil ARMv8-A —
hoje `ARMV7A` já modela um "ARMv7-A user-level" sem VMSA64; decidir na sub-task se ele precisa de um
preset PRÓPRIO (ARMv8-A AArch32, potencialmente com `SDIV`/`VFPv4` que já estão em `ARMV7A`, mas
talvez `CRC32`/load-acquire-release que `ARMV7A` não tem — ver `docs/isa-nao-aplicavel.tsv`,
seção "ARMv8-A: instruções de aquisição/liberação").

### Cortex-A 64-bit — candidato a `arch64.Aarch64Processor` (+ `arch.ArmProcessor` para o modo A32)

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Cortex-A34 | ARMv8-A | A64 only |
| Cortex-A35/A53/A57/A72/A73 | ARMv8-A | A32/A64 |
| Cortex-A55/A75 | ARMv8.2-A | A32/A64 |
| Cortex-A65/A65AE/A76/A76AE/A77/A78/A78AE/A78C | ARMv8.2-A | A64 only |
| Cortex-A510/A710/A715 | ARMv9-A | A64 only |
| Cortex-A320/A520/A720/A725 | ARMv9.2-A | A64 only |

**Já coberto**: Cortex-A53→`Aarch64Architecture.ARMV8_0_A` (é literalmente o núcleo de referência do
`virtual-arm-box`/raspi3-64 — primeira entrada óbvia do catálogo); toda a linha ARMv8.2-A→
`ARMV8_2_A`; ARMv9-A "puro" (sem sub-revisão) precisa decidir se mapeia para `ARMV9_0_A` (o mais
conservador — ver Armadilhas sobre a diferença entre "ARMv9-A" genérico da Wikipedia e "ARMv9.0-A"
formal) ou se precisa de uma feature adicional não coberta ainda; ARMv9.2-A→`ARMV9_2_A`.

### Cortex-X

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Cortex-X1 | ARMv8.2-A | A64 |
| Cortex-X2/X3 | ARMv9-A | A64 |
| Cortex-X4/X925 | ARMv9.2-A | A64 |

Mesmos presets da linha Cortex-A 64-bit correspondente — nenhum trabalho de arquitetura novo,
só entradas de catálogo.

### Neoverse (servidor)

| Núcleo | Arquitetura | Modo |
|---|---|---|
| Neoverse N1/E1 | ARMv8.2-A | A64 |
| Neoverse V1 | ARMv8.4-A | A64 |
| Neoverse N2/V2 | ARMv9-A | A64 |
| Neoverse N3/V3 | ARMv9.2-A | A64 |

`ARMv8.4-A` (Neoverse V1) já tem preset (`ARMV8_4_A`, hoje vazio de features extra — ver nota na
B11.1 sobre "nenhuma feature mapeada ainda", auditoria fica pra quando esta entrada for pega).

### C-Series (branding novo, pós-2025)

| Núcleo | Arquitetura | Modo |
|---|---|---|
| C1-Ultra/Premium/Pro/Nano | ARMv9.3-A | A64 |

`ARMv9.3-A` não tem preset ainda (B11.1 parou em `ARMV9_5_A`, mas `ARMV9_3_A` já existe — conferir
na hora; se não existir, é pré-requisito trivial, mesmo padrão de `ARMV8_4_A`/`ARMV8_7_A` "degrau
vazio").

## Escada (proposta inicial — refinar em spec própria quando cada item for pego)

| Task | Escopo | Depende de |
|---|---|---|
| **B12.1** | `arch64.Aarch64Processor`, família Cortex-A/X/Neoverse ARMv8.0-A→ARMv8.2-A (maior massa de núcleos já cobertos por preset existente, zero trabalho de arquitetura novo — só a tabela de resolução). Cortex-A53 como primeira entrada (núcleo real do `virtual-arm-box`) | B11.1 ✅ |
| **B12.2** | `arch64.Aarch64Processor`, restante ARMv8.4-A→ARMv9.5-A (Neoverse V1/N2/V2/N3/V3, Cortex-A510+/X2+/A320+, C-Series) — inclui decidir a correspondência "ARMv9-A genérico da Wikipedia" → qual `ARMV9_x_A` | B12.1 |
| **B12.3** | `arch.ArmProcessor`, ARM clássico + linha Cortex-A32-bit completa (ARM7TDMI→`ARMV4T`, ARM926EJ-S→`ARMV5TE`, ARM11 MPCore→`ARM11_MPCORE`, Cortex-A5..A17→`ARMV7A`) — maior massa 32-bit já coberta por preset existente | — |
| **B12.4** | `arch.ArmProcessor`, perfil M (Cortex-M0..M85, SecurCore SC000/SC300) — parte já coberta (`ARMV6M`/`ARMV7M`), parte precisa avaliar se `ARMv7E-M`/`ARMv8-M`/`ARMv8.1-M` cabem nos presets atuais ou pedem um novo (ver nota na tabela acima) | — |
| **B12.5** | Núcleos ARM clássicos SEM preset hoje (ARMv1/v2/v2a/v3, ARMv6/ARMv6T2/ARMv6Z puros sem VFP, ARMv5TEJ com Jazelle) — decidir por sub-task se cada um vira preset novo (aditivo) ou fica documentado como "arquitetura sem preset ainda, candidata pendente" (nunca "fora de escopo para sempre", regra máxima) | — |
| **B12.6** | Cortex-A32 (ARMv8-A AArch32-only) — decidir se cabe em `ARMV7A` ou precisa preset próprio (load-acquire/release `LDA`/`STL`/CRC32 do ARMv8-A "de verdade") | — |
| **B12.x** | Perfil R (Cortex-R4..R82) — épico próprio primeiro (perfil nunca modelado), catálogo depois | novo épico de perfil R |

**Ordem sugerida**: B12.1 (maior cobertura A64 pelo menor esforço) → B12.3 (equivalente 32-bit) →
B12.2/B12.4 em paralelo → B12.5/B12.6 (os que pedem arquitetura nova, mais trabalho de fonte) →
B12.x fica para depois de um épico de perfil R.

## Especificação de implementação (vale para toda sub-task desta escada)

- **Formato**: enum (não classe com campos estáticos soltos) — permite `values()`/`valueOf()`,
  útil para um cliente futuro que queira listar/escolher um processador por nome em UI/CLI. Cada
  constante recebe `(String displayName, ArmArchitecture architecture)` (ou `Aarch64Architecture`
  no catálogo A64) no construtor.
- **Reaproveitar presets existentes sempre que o conjunto de features bater** — nunca duplicar um
  `EnumSet` idêntico a um preset já nomeado só para dar outro nome. Quando MÚLTIPLOS núcleos reais
  mapeiam para o mesmo preset (ex. toda a linha ARMv7-A → `ARMV7A`), é isso mesmo — o catálogo é
  N:1 processador→arquitetura, não 1:1.
- **Javadoc de cada constante cita a fonte** (Wikipedia para a versão de arquitetura; TRM real do
  núcleo — `developer.arm.com` — quando a entrada afirmar algo sobre features opcionais além da
  versão pura), mesma disciplina de `docs/isa-nao-aplicavel.tsv`.
- **Getter simétrico ao padrão do projeto**: `architecture()` (retorna `ArmArchitecture`/
  `Aarch64Architecture`), `displayName()` (nome comercial, ex. `"Cortex-A53"`).
- **Sem uso ainda em `ArmCore`/`Aarch64Core`**: este catálogo é só uma tabela de resolução
  nome→arquitetura para o CLIENTE da biblioteca escolher; não muda nenhuma factory/API pública
  existente (G3). Quem quiser usar hoje faz `new ArmCore(memory, ArmProcessor.CORTEX_A9.architecture())`
  manualmente (ou o equivalente A64 quando B11.2 destravar o construtor com `Aarch64Architecture`).

## Fatos de referência

- Fonte primária desta task:
  [List of ARM processors](https://en.wikipedia.org/wiki/List_of_ARM_processors) (Wikipedia,
  consultada 2026-08-28) — tabela completa reproduzida acima por família.
- Padrão a espelhar: `arch.ArmArchitecture`/`arch64.Aarch64Architecture` (`of`/`extending`/`has`),
  ver [[B11]] para o lado A64.
- **Armadilha de precisão já conhecida**: a Wikipedia usa `"ARMv9-A"` genérico para vários núcleos
  (Cortex-A510/A710/A715/X2/X3/N2/V2) sem apontar a sub-revisão (`ARMv9.0`/`.1`); e usa `"ARMv7-A"`
  genérico para toda a linha Cortex-A5..A17 mesmo alguns tendo VFPv4/NEON opcionais diferentes uns
  dos outros na prática. **Não presumir que "mesma string da Wikipedia" implica "mesmo preset já
  correto"** — cada sub-task que pegar um núcleo específico precisa confirmar contra o TRM real
  antes de declarar `architecture()` definitivo; até lá, documentar como aproximação.

## Consumidores a revalidar (G5, toda task desta escada)

Nenhum consumidor real (`gbaemu`/`ndsemu`/`armbox`/`virtual-arm-box`/`n3dsemu`) usa este catálogo
ainda — é infraestrutura nova, aditiva, sem consumidor interno. G5 continua obrigatório mesmo assim
(mudança em `core`, compartilhado por todos). Push obrigatório em toda task — ver `tasks/README.md`.

## Resultado (B12.1, 2026-08-28)

`arch64.Aarch64Processor` criado — enum com 11 constantes cobrindo a família Cortex-A/X/Neoverse
`ARMv8.0-A`→`ARMv8.2-A` (a fatia do inventário da tabela "Cortex-A 64-bit"/"Cortex-X"/"Neoverse"
deste arquivo que já resolve para preset existente, zero trabalho de arquitetura novo — só a tabela
nome→`Aarch64Architecture`, conforme a especificação de implementação acima):

- `ARMV8_0_A`: `CORTEX_A34`, `CORTEX_A35`, `CORTEX_A53` (primeira entrada, núcleo real do
  `virtual-arm-box`/raspi3-64), `CORTEX_A57`, `CORTEX_A72`, `CORTEX_A73`.
- `ARMV8_2_A`: `CORTEX_A55`, `CORTEX_A75`, `CORTEX_X1`, `NEOVERSE_N1`, `NEOVERSE_E1`.

Cada constante recebe `(String displayName, Aarch64Architecture architecture)` no construtor, com
getters `architecture()`/`displayName()` e `toString()` retornando o `displayName` — mesmo padrão de
`Aarch64Architecture`. Nenhum núcleo `ARMv8.1-A` "puro" aparece na tabela de origem (Wikipedia não
lista nenhum core mapeado só para essa versão), então o preset `ARMV8_1_A` fica sem entrada nesta
sub-task — não é uma lacuna, é reflexo do inventário real.

**Sem uso ainda em `Aarch64Core`** (G3, conforme a especificação do épico) — só a tabela de
resolução nova, aditiva, sem mudança de comportamento observável em nenhum decoder/executor
existente.

Testes novos: `Aarch64ProcessorTest` (6 casos — resolução por família, `displayName()`, `toString()`,
unicidade de nome comercial, round-trip de `valueOf`/`name()`), espelhando a disciplina de
`Aarch64ArchitectureTest`. `mvn -o test` verde (suíte inteira do `arm-jitter`, JBR 25) + `install`
local; G5 nos 3 consumidores relevantes ao Java (`gbaemu`, `ndsemu`, `armbox`) verde — nenhum diff de
comportamento esperado (catálogo sem consumidor interno ainda).

Próximo da escada: B12.2 (restante `ARMv8.4-A`→`ARMv9.5-A` — Neoverse V1/N2/V2/N3/V3,
Cortex-A510+/X2+/A320+, C-Series) ou B12.3 (`arch.ArmProcessor`, lado 32-bit — ver a "Ordem
sugerida" acima).

## Resultado (B12.3, 2026-08-28)

`arch.ArmProcessor` criado — enum com 24 constantes cobrindo ARM clássico + a linha Cortex-A
32-bit já cobertos por preset existente (zero trabalho de arquitetura novo, só a tabela de
resolução nome→`ArmArchitecture`):

- `ARMV4T`: `ARM7TDMI`, `ARM710T`, `ARM720T`, `ARM740T`, `ARM9TDMI`, `ARM920T`, `ARM922T`,
  `ARM940T`, `SC100` (SecurCore `SC100` — único núcleo SecurCore fora do perfil M, os demais
  `SC000`/`SC300` são perfil M e ficam em B12.4, achado ao ler a linha da Wikipedia que a escada
  original de B12 não tinha citado explicitamente para este item).
- `ARMV5TE`: `ARM946E-S`, `ARM966E-S`, `ARM968E-S`, `ARM996HS`, `ARM1020E`, `ARM1022E` (arquitetura
  literal `ARMv5TE` na Wikipedia) mais `ARM7EJ-S`, `ARM926EJ-S`, `ARM1026EJ-S` (a Wikipedia lista
  `ARMv5TEJ` para estes — **aproximação documentada no Javadoc**: este projeto não modela nenhum
  modo Jazelle, `ArmFeature` não tem entrada para isso, então o conjunto ARM/Thumb visível ao
  decoder/executor é idêntico ao `ARMv5TE` puro).
- `ARM11_MPCORE`: `ARM11 MPCore` (único núcleo da linha, `ARMv6K`).
- `ARMV7A`: `Cortex-A5`, `Cortex-A7`, `Cortex-A8`, `Cortex-A9`, `Cortex-A12`, `Cortex-A15`,
  `Cortex-A17`.

**Deixados de fora desta task** (por escopo, não por esquecimento — candidatos às próximas
sub-tasks da escada B12, ver o corpo do épico acima): `ARM810` (`ARMv4` puro, sem Thumb — mapear
para `ARMV4T` seria incorreto, o preset assume Thumb disponível; sem preset próprio hoje, B12.5),
`ARM1136J(F)-S`/`ARM1156T2(F)-S`/`ARM1176JZ(F)-S` (`ARMv6`/`ARMv6T2`/`ARMv6Z` puros, sem preset,
B12.5), `Cortex-A32` (`ARMv8-A` AArch32-only, decisão de preset pendente, B12.6), `SecurCore
SC000`/`SC300` e toda a linha `Cortex-M` (perfil M, B12.4), perfil R (nunca modelado, épico
próprio).

Mesmo padrão de `Aarch64Processor` (B12.1): `(String displayName, ArmArchitecture architecture)`
no construtor, getters `architecture()`/`displayName()`, `toString()` retornando o `displayName`.

**Sem uso ainda em `ArmCore`** (G3, conforme a especificação do épico) — só a tabela de resolução
nova, aditiva, sem mudança de comportamento observável em nenhum decoder/executor existente.

Testes novos: `ArmProcessorTest` (9 casos — resolução por família incl. a aproximação `ARMv5TEJ`,
`displayName()`, `toString()`, unicidade de nome comercial, round-trip de `valueOf`/`name()`),
espelhando `Aarch64ProcessorTest`. `mvn -o test` verde (suíte inteira do `arm-jitter`, JBR 25,
2665 testes) + `install` local; G5 nos 3 consumidores relevantes ao Java (`gbaemu`/`ndsemu`/
`armbox`) verde — zero-diff esperado (catálogo sem consumidor interno ainda).

Próximo da escada: B12.2 (A64 `ARMv8.4-A`→`ARMv9.5-A`) e B12.4 (perfil M) podem rodar em paralelo;
B12.5/B12.6 (núcleos sem preset, pedem decisão de arquitetura nova) ficam para depois.

## Resultado (B12.2, 2026-08-28)

`arch64.Aarch64Processor` estendido com 30 constantes novas cobrindo o restante do inventário A64
(`ARMv8.4-A`→`ARMv9.5-A`, zero trabalho de arquitetura novo — todos os presets já existiam em
`Aarch64Architecture`):

- `ARMV8_4_A`: `NEOVERSE_V1`.
- `ARMV9_0_A`: `CORTEX_A510`, `CORTEX_A710`, `CORTEX_A715`, `CORTEX_X2`, `CORTEX_X3`, `NEOVERSE_N2`,
  `NEOVERSE_V2` — a Wikipedia lista `"ARMv9-A"` genérico (sem sub-revisão) para esses 7 núcleos;
  mapeados para `ARMV9_0_A` por ser o preset mais conservador (aproximação documentada no Javadoc de
  cada constante, conforme a "Armadilha de precisão" do plano mestre acima — não confirmado contra o
  TRM real).
- `ARMV9_2_A`: `CORTEX_A320`, `CORTEX_A520`, `CORTEX_A720`, `CORTEX_A725`, `CORTEX_X4`,
  `CORTEX_X925`, `NEOVERSE_N3`, `NEOVERSE_V3`.
- `ARMV9_3_A`: `C1_ULTRA`, `C1_PREMIUM`, `C1_PRO`, `C1_NANO` (branding C-Series pós-2025).

**Achado real (gap de B12.1)**: revisando a tabela de origem para B12.2, 8 núcleos A64-only
`ARMv8.2-A` da linha "Cortex-A 64-bit" (`Cortex-A65`/`A65AE`/`A76`/`A76AE`/`A77`/`A78`/`A78AE`/
`A78C`) estavam na mesma linha da Wikipedia que `Cortex-A55`/`A75` (já cobertos por B12.1) mas não
entraram no catálogo — oversight da sub-task anterior, não decisão consciente (B12.1 não tem seção
"deixados de fora" citando-os, diferente de B12.3). Fechados aqui também, reaproveitando o preset
`ARMV8_2_A` já em uso — zero risco, mesmo padrão. Documentado no Javadoc da classe como "B12.2:
gap-fill de B12.1".

Mesmo padrão de B12.1/B12.3: `(String displayName, Aarch64Architecture architecture)` no construtor,
`architecture()`/`displayName()`/`toString()` herdados sem mudança. **Sem uso ainda em
`Aarch64Core`** (G3) — só tabela de resolução, aditiva.

Testes novos: 6 métodos de teste adicionados a `Aarch64ProcessorTest` (gap-fill ARMv8.2-A, ARMv8.4-A,
ARMv9-A genérico→ARMV9_0_A, ARMv9.2-A, C-Series→ARMv9.3-A, displayName com hífen do C-Series) — os 3
testes estruturais existentes (`everyEntryHasAUniqueDisplayName`, `valueOfRoundTripsForEveryConstant`,
etc.) cobrem as 30 constantes novas automaticamente por iterar `values()`. `mvn -o test` verde (suíte
inteira do `arm-jitter`, JBR 25) + `install` local; G5 verde em `gbaemu`/`ndsemu`/`armbox` (zero-diff
esperado, catálogo sem consumidor interno ainda).

Catálogo A64 (`Aarch64Processor`) agora tem 41 constantes cobrindo toda a fatia "Cortex-A 64-bit +
Cortex-X + Neoverse + C-Series" do inventário da Wikipedia deste épico. Restam no catálogo A64: nada
do inventário listado no corpo do épico (perfil R fica para épico próprio). Próximo da escada: B12.4
(perfil M, `arch.ArmProcessor`) segue elegível; B12.5/B12.6 (núcleos sem preset) ficam para depois.
